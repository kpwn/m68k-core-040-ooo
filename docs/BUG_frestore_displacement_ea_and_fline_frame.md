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
2. **Route FPU-space F-lines to the FP frame.** An undecoded F-line whose
   cpID is 001 should take the FPU-unimplemented path (format-$2 + the FSAVE
   state capture) rather than the generic illegal path, so the FPSP gets a frame
   it can parse. Worth doing independently of (1): it makes every future
   unimplemented-FP case report truthfully instead of printing garbage.

## Reproducing

Any FPU context restore through `d16(An)`. On the machine it reproduced every
time; caches on (`CACR = 0x80008000`).
