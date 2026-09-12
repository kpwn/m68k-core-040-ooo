# FRESTORE `d16(An)` is not decoded — and the F-line frame it produces lies

Found 2026-09-12 on a booted System 7 machine. MacsBug reported
**"Unimplemented Instruction"** at **`0x40819dc2`** with **"unknown exception
frame type $4081"**. Both of those numbers are false. They are artefacts of the
second bug below.

## Bug 1 (the actual fault): FSAVE/FRESTORE admit only register-indirect EAs

`OperationDecoder.scala` restricts the admitted addressing modes:

```scala
val isFsave    = fsvBase && (opword(8 downto 6) === "100") &&
                 ((fsvMode === "100") || (fsvMode === "010"))   // -(An), (An)
val isFrestore = fsvBase && (opword(8 downto 6) === "101") &&
                 ((fsvMode === "011") || (fsvMode === "010"))   // (An)+, (An)
```

Its own comment admits the gap: *"The displacement/absolute forms (0xF338/0xF378
etc.) need a real EA computation"*. That work was deferred and never done.

| | implemented | a real 68040 accepts |
|---|---|---|
| `FSAVE` | `(An)`, `-(An)` | + `d16(An)`, `d8(An,Xn)`, `abs.W`, `abs.L` (control alterable) |
| `FRESTORE` | `(An)`, `(An)+` | + `d16(An)`, `d8(An,Xn)`, `abs.W`, `abs.L`, `d16(PC)`, `d8(PC,Xn)` (control) |

**Mac OS uses `d16(An)` for FPU context restore**, so this fires in ordinary
system code. The measured instruction was `0xF36D` = `FRESTORE d16(A5)`
(cpID=001, type=101, EA mode 101 / reg A5) at **`0x03fbbdf2`**, in RAM.

Undecoded, it falls to the F-line illegal default -> vector 11.

## Bug 2: an FPU-space F-line we fail to decode stacks a format-$0 frame

`RobPlugin.scala`: `p.fpuUnimp := u.fpuSoftwareComplete`, and
`fpuSoftwareComplete` is set only for **recognised** FP ops that need software
completion (FMOD/FREM/FSCALE/FGETEXP/FSINCOS). An F-line we do not decode gets
`fpuUnimp = false`, so `ExceptionUnit`'s `is2` term
(`entryVector === 11 && entryFpuUnimp`) is false and the CPU stacks the plain
**format-$0** (8-byte) frame.

But on a Mac, vector 11 lands in the ROM's **FPSP**, which expects an FP-shaped
frame. Handed a short frame, it reads past the end and reports nonsense:

* "frame type **$4081**" — that is the **high word of the stacked PC**
  `0x4081_9dc2`, read where the format/vector word should have been.
* faulting PC "**0x40819dc2**" — ROM context, not the real fault at
  `0x03fbbdf2`.

So every number in the bug report was wrong, which is why the investigation
chased the wrong address for hours. A 68040 hands FPU-class unimplemented
instructions a frame the FPSP can parse, on purpose.

## What was ruled out on the way (do not re-derive)

| hypothesis | verdict | how |
|---|---|---|
| stale I-cache / `if_stage` buffer | **ruled out** | `icache-op inv` + halt/release redirect (which also drains `lv`); fault unchanged |
| corrupted ROM copy in DRAM | **ruled out** | live `dump-mem 0x40819dc0` matches `420dbff3.rom` byte-for-byte, 8/8 words |
| A-line dispatched to the wrong vector | **ruled out** | `exc-ring` shows 32/32 entries `vec=0x0a`, handler `0x408099b0` (correct) |
| A-line in decode slot 1 stacks slot 0's PC | **ruled out** | `exc_aline_slot1_stacked_pc.s` passes in both cache postures |
| the A-line `a873` at `0x40819dc6` | **irrelevant** | red herring; the real fault is in RAM |

## Fixes

1. **Decode the missing EA modes.** Today the uop passes An's *value* on `srcB`
   as the frame base, with `imm[3:0]` carrying {EA mode, isRestore}. The
   displacement/absolute forms need the base to be a computed EA, so this needs
   either a packed displacement the EU adds, or real EA routing. `imm` already
   carries a side-channel, so the encoding needs a decision.
2. **Classify the F-line frame by ENCODING VALIDITY, not by instruction form.**

   The single site that sets `fpuSoftwareComplete` (MicroOpAssembler, the
   "Task 10, reduced scope" arm) is:

   ```scala
   val fpuGenRegUnimpl = bad && spec.fpGeneric && fpFormIsReg && pkt.simple &&
                         (pkt.lenWords === U(2, ...))
   ```

   i.e. **cpGEN register-to-register form only**. Every other FPU-space F-line --
   FSAVE, FRESTORE, FBcc, FScc, and every cpGEN MEMORY form -- falls through with
   `fpuUnimp = false` and stacks format-$0.

   A 68040 splits it differently:

   | case | exception | frame |
   |---|---|---|
   | a VALID FPU encoding the hardware cannot execute | Unimplemented FP Instruction | **$2** (the FPSP then EMULATES it) |
   | an encoding the FPU does not recognise at all | F-line Emulator | $0 |

   So the predicate should be "is this a valid FPU encoding?", not "is this
   cpGEN register form?". `FRESTORE d16(A5)` is a valid FPU encoding, which is
   why it deserved a parseable frame even before (1) made it execute.

   This is not only diagnostics: format-$2 is the mechanism by which the FPSP
   emulates instructions the hardware lacks, so widening the classification
   correctly would let unimplemented FP ops WORK in software rather than crash.

   CAUTION for whoever implements it: the same arm sets
   `fpuCmdWord := fpExt`, which assumes a cpGEN extension word exists.
   FSAVE/FRESTORE/FBcc have no FP command word in that sense, so the frame
   contents need deciding per family -- widening the predicate blindly would
   trade an UNPARSEABLE frame for a MALFORMED one.

## Reproducing

Any FPU context restore through `d16(An)`. On the machine it reproduced every
time; caches on (`CACR = 0x80008000`).

## Status (2026-09-12)

Fix (1) is IMPLEMENTED, generalized past the original d16(An) scope on owner
direction ("we want to generalize every possible opcode against every possible
EA mode"):

* `OperationDecoder` admits the architectural classes -- FSAVE = control
  alterable + `-(An)`, FRESTORE = control + `(An)+` + the PC-relative forms --
  rather than an enumerated mode list.
* `PredecodeWord` computes the length through the existing generic
  `eaExt(mode, reg, ...)` helper, so every mode's length (and its
  `ambiguousLine`) is covered by one arm.
* `MicroOpAssembler` cracks the computed-address modes into
  `[T0 := EA] + [sysOp reading T0]`, reusing PEA's `leaGenUop` verbatim (aliased,
  not re-instantiated, so the EA datapath is not duplicated). The sysOp then
  reports mode 010 `(An)` to the FSM because T0 already holds the final address
  -- a shape the FSM implements today, so `ExceptionUnit` and `RobPlugin` need
  no change. The auto-update modes keep the single-uop form since they write An.

Not yet compiled or tested at the time of writing.

Fix (2) is NOT implemented.

## Fix (2): v1 already has the proven rule — split on COPROCESSOR ID

Reviewing the v1 core (`/home/qwertyoruiop/m68k-ooo/rtl/core/decode/decode.v`,
the F-line fallback) settles the open question. v1 does NOT classify by
instruction family; it classifies by coprocessor ID:

```verilog
else if (op_f3[15:12] == 4'b1111) begin
    // Coprocessor-ID 1 is the on-chip 040 FPU and uses the format-$2
    // unsupported-instruction path consumed by the ROM FPSP.  Other
    // coprocessor IDs use generic line-F format $0.  Pseudo-vector $CB is
    // translated back to architectural vector 11 by commit.
    exc_vec   = (op_f3[11:9] == 3'b001) ? 8'd11 : 8'hCB;
    imm       = {ext1_f3, op_f3};   // CMDREG1B payload
```

So: **cpID == 001 -> vector 11 on the format-$2 FPSP path; any other cpID ->
format-$0.** That is the whole predicate.

It also answers the CMDREG1B caution recorded above. The worry was that
FSAVE/FRESTORE/FBcc have no cpGEN extension word to put in the frame. v1 passes
`{ext1, opword}` unconditionally, and that is exactly what the ROM FPSP kernel
reads at `fp@(-228)`. No per-family frame design is needed, and excluding those
families -- the conservative option first proposed here -- would be NARROWER
than the core that actually boots System 7. Do not exclude them.

Our current predicate (`bad && spec.fpGeneric && fpFormIsReg && pkt.simple &&
lenWords === 2`) is far tighter than v1's and is the defect.

### Musashi is NOT a usable oracle here

`tools/musashi/musashi/m68kfpu.c`'s `m68040_fpu_op1` implements FSAVE/FRESTORE
with a mode switch covering only `(An)`, `(An)+` and `-(An)`, and calls
`fatalerror()` on everything else (36 fatalerror sites in that file). So it
cannot lock-step-validate the EA modes fix (1) adds -- it hard-fails rather than
disagreeing. It is also loose: its FSAVE accepts `(An)+`, which is
architecturally a FRESTORE-only mode. Nine corpus asm tests touch these
instructions; plan their validation accordingly.

Musashi's `m68ki_exception_1111` always pushes `m68ki_stack_frame_0000`
(format-$0) -- unsurprising, since Musashi emulates the FP ops itself and never
needs to hand anything to an FPSP.
