# FPU/FPSP Design

**Status:** Design complete, approved, ready for `writing-plans`.

**Goal:** Reverse this project's long-standing "FPU is permanently out of scope" convention.
Implement a minimal hardware FPU sufficient to (a) let a real Motorola/Apple FPSP
(trap-and-emulate software layer) run correctly, so real Mac System 7/68040 ROM software
that depends on it is reachable, and (b) accelerate the hot/common FP operations in hardware
rather than paying full trap-and-emulate cost for everything.

**Architecture:** An 11-op hardware-native FPU folded into the existing CPLX execute cluster
(shared with DIV/MUL/CHK/CMP2/CHK2), with a renamed FPCC extending the existing NZVC/X
rename machinery, everything outside the 11-op baseline routed to a real FPSP kernel via
genuine 68040 F-line/unimplemented-FP-instruction exception semantics.

**Tech Stack:** SpinalHDL, existing rename/scoreboard/freelist infrastructure, existing CPLX
EU/IQ-port/ROB-port, existing `PortedTestRunner`/`ProgramAssembler` test infrastructure.

---

## Background

This design reverses the project's own `isa-completion-roadmap` convention that FPU/line-F
was permanently out of scope. Motivation (2026-08-01, user-stated): real Mac ROM software
depends on FPSP being reachable, and hardware acceleration of hot FP ops matters. This
document consolidates a brainstorming session that ran across two sittings (2026-08-01,
paused for the integer-feature-completeness push, and resumed 2026-08-14) into one coherent,
approved design.

Primary research inputs: ~2,500 lines of `m68k-ooo`'s real FPU RTL
(`/home/qwertyoruiop/m68k-ooo/rtl/core/execute/fpu/`), Musashi's real FPU emulation core
(`tools/musashi/musashi/m68kfpu.c`), and the real **MC68040 User's Manual** (Motorola, 1989,
verified directly from `http://www.bitsavers.org/components/motorola/68000/68040/MC68040_Users_Manual_1989.pdf`
— chosen deliberately over trusting a reference implementation's numbers, per this project's
established practice of catching spec-conformance bugs by going to primary sources).

## Locked Decisions

### 1. Internal precision: 80-bit extended (REVERSED from the original 2026-08-01 decision)

The 2026-08-01 session originally locked IEEE-754 double, explicitly rejecting 80-bit
extended, accepting a weaker ±1-ULP/round-both-to-double Musashi comparison scheme as the
cost. **This is reversed as of 2026-08-14**: internal FP register file and arithmetic are
80-bit extended precision, matching real 68881/68882/68040 hardware.

Rationale for the reversal, established this session:
- **Musashi's actual internal representation is `floatx80`** (a portable SoftFloat 80-bit
  extended type — not a platform-dependent `long double`), used natively for every FP
  register, every arithmetic op, and every EA read/write (`tools/musashi/musashi/m68kfpu.c`,
  `READ_EA_FPE`/`WRITE_EA_FPE`). This means **bit-exact lock-step on the 11 HW-native ops'
  arithmetic results is achievable**, not merely a ±1-ULP approximation — a real upgrade over
  the original plan's accepted verification compromise. (This does NOT extend to the FPSP
  trap-boundary itself — see Decision 3's Musashi-gap list, unaffected by this choice.)
- **FSAVE state frame fields are natively extended-precision** (ETS/ETE/ETM, FPTS/FPTE/FPTM
  per the real MC68040 UM, Figure 9-7 — see Decision 7). With an 80-bit internal register
  file, populating these frames is a direct copy; with a 64-bit double register file it would
  require a genuine double-to-extended conversion on every FSAVE — exactly the class of bug
  already flagged as a known m68k-ooo weakness (their EA-source conversion bit-reinterprets
  rather than converts).
- **Genuinely cheap, not just tolerable**: extended precision uses an *explicit* leading
  mantissa bit (no implicit-bit packing/unpacking IEEE double requires), so arithmetic
  hardware complexity doesn't scale linearly with the wider format. Register file grows
  ~25% (80×16 vs 64×16 bits), execution datapath ~23% (64-bit vs 52-bit mantissa).
- More faithful to real 68040 hardware generally: real silicon computes in extended
  precision internally even for single/double-precision FMOVE operations to/from memory.

### 2. Hardware op scope: match m68k-ooo's 11-op baseline

FADD/FSUB/FMUL/FDIV/FSQRT/FABS/FNEG/FMOV/FCMP/FINT/FINTRZ implemented in hardware. Everything
else (transcendentals, packed decimal, rounded-precision variants, FScc/FDBcc/FTRAPcc,
double-precision memory source) traps to a real Motorola/Apple FPSP kernel, embedded
byte-for-byte from an actual Quadra 700 ROM copy, per m68k-ooo's validated approach. Extend
later based on real profiling once FPSP is running, not upfront guesswork. FScc/FDBcc/FTRAPcc
hardware acceleration (an acknowledged m68k-ooo gap) explicitly deferred, not ruled out.

### 3. Musashi oracle gaps: fix the cheap bridge gaps, work around the rest

Extend `tools/musashi/m68k_ref.h` with FP register accessors (`get_fp`/`get_fpcr`/`get_fpsr`/
`get_fpiar` reaching into `m68ki_cpu` directly, mirroring the existing int-register pattern at
`m68k_ref.h:101-116`) to enable real FP-data-register lock-step, now bit-exact-capable per
Decision 1. Do NOT attempt full parity: Musashi raises zero FP exceptions, has no
unimplemented-instruction/data-type trap, wrong FSAVE frame shape (Musashi's own frame is
28 bytes/format $1f, not the real 68040's 4/44/100-byte null/idle/unimplemented frames — see
Decision 7), and has buggy multi-register FMOVEM EA handling. Use directed ROM-kernel tests
for that surface instead (Decision 8).

### 4. FPCC renaming: yes, extend the existing NZVC/X machinery

FPCC (N/Z/I/NAN) is renamed, extending the existing NZVC/X rename/scoreboard/freelist
machinery. This is the deliberate fix for m68k-ooo's single worst known defect: non-renamed
FPCC forces a full ROB drain on every FBcc/FP-to-int move, measured IPC as low as 0.065-0.24
on their FPU tests.

### 5. FPCR/FPSR-exception-bits/FPIAR: non-renamed, single-copy state

FPCR (rounding/exception-enable control), FPSR's non-FPCC bytes (exception-status/accrued/
quotient), and FPIAR (last-FP-exception PC) are simple non-renamed architectural registers,
written in-order — contrast with Decision 4's FPCC. None of these three sit on the
speculative hot path FPCC does: FPCR changes rarely (explicit FMOVE-to-FPCR) and is read for
rounding mode, not branched on; FPSR's exception-status bits are architecturally an
in-order/precise-exception concept, updated at commit like this project's existing
exception-frame capture pattern (e.g. `faultPc`); FPIAR is write-once-per-exception, read
only by handlers. A single physical copy each with a simple interlock on the rare write is
cheaper and simpler to get precise-exception-correct than extending rename to registers that
gain nothing from speculative read-ahead.

### 6. FP register file: 16 physical registers

8 architectural FP0-FP7, each 80 bits (Decision 1), backed by 16 physical registers — matching
this project's existing flag-RAT scale (`nzvcRat`/`xRat` both use 16 physical slots, 4-bit
tags: `src/main/scala/m68k040/rename/RenameStage.scala:31-32`), not the larger ~2.5x
int-PRF ratio (`intFree = Freelist(physCount = 50, archCount = 20, ...)`,
`RenameStage.scala:34`). Justified because FP ops route through the shared CPLX cluster
(Decision 9), which is inherently low-throughput/serialized — realistic in-flight FP writes
at any moment will be far below ROB depth (64, `RobPlugin.scala:126`) regardless of PRF depth,
so 16 gives 2x headroom over the architectural count without over-provisioning an 80-bit-wide
register file for a constraint (rename depth) that isn't actually binding.

### 7. Exception vector routing: real 68040 F-line/unimplemented-FP-instruction semantics

Unsupported FP ops trap via vector 11 (unimplemented instruction — "Line 1111 Emulator") for
the unimplemented-instruction path, and vectors 48-55 for the numeric-exception family — NOT
a bespoke/novel trap type. User's own framing: "FPSP just lives off F-Line; so all we gotta do
is implement a subset and leave the rest as F-line." The FPSP handler's own prologue
instructions must decode as ordinary/HW-native so they don't self-trap (see Decision 8's
self-recursion test).

**Vector table, verified directly against the MC68040 User's Manual (1989), Chapter 9 exception
vector assignment table — NOT copied from m68k-ooo, which has at least one confirmed-wrong
vector:**

| Vector | Offset | Assignment |
|---|---|---|
| 11 | $02C | Line 1111 Emulator (Unimplemented F-Line Opcode) |
| 48 | $0C0 | FP Branch or Set on Unordered Condition (BSUN) |
| 49 | $0C4 | FP Inexact Result (INEX2) |
| 50 | $0C8 | FP Divide by Zero (DZ) |
| 51 | $0CC | FP Underflow (UNFL) |
| 52 | $0D0 | FP Operand Error (OPERR) |
| 53 | $0D4 | FP Overflow (OVFL) |
| 54 | $0D8 | FP Signaling NAN (SNAN) |
| 55 | $0DC | FP Unimplemented Data Type |

**Confirmed bug in m68k-ooo**: they use vector 49 for FDIV-by-zero; the real assignment is
vector 50 (49 is Inexact Result). Do not port this mistake.

Note: vector *number* assignment order above is NOT the same as exception *priority* order
(which determines which exception wins when multiple are pending simultaneously). Per the
manual, priority order is: BSUN, SNAN, OPERR, OVFL, UNFL, DZ, INEX2. Do not conflate the two
orderings when implementing exception detection/dispatch logic.

### 8. FPSP verification fixture: test fixture only, never shipped in RTL

The CPU hardware's only job is the trap protocol; whatever FPSP a booted OS provides (e.g.
real Mac ROM's own copy) is what actually runs in production. Reuse m68k-ooo's real extracted
FPSP copy as the directed-test kernel (proven — caught 4 real deadlocks in m68k-ooo's own
history) rather than writing a custom test-only handler.

**No new test harness is needed.** This project already vendored m68k-ooo's entire directed
test corpus (`tools/fuzz/vendor-ported-tests.sh` → `src/test/resources/m68kooo-ported-tests/asm/`,
923 files), including **35 `fpu_*`/`fpsp_*` `.s` tests**, already wired through
`PortedM68kOooSpec.scala` → `PortedTestRunner.scala` → `ProgramAssembler.assemble` (real GNU
`m68k-linux-gnu-as`/`ld`/`objcopy` pipeline) → `AxiMemModel.attachProgramIFetch`. All 35 are
currently expected failures (FPU out of scope until this design), tracked as such in this
project's own triage history — they become the acceptance corpus as FPU work lands, watched
to flip from FAIL to PASS.

Two tests specifically validate the deepest parts of this design and require no new
infrastructure:
- **`fpu_fpsp_selfrecursion_repro.s`** — the self-recursion-guard test for the exact
  "handler's own prologue re-traps into itself" deadlock class m68k-ooo hit 4 times on real
  hardware. Installs a real vector-11 handler whose first instruction is the ROM's own
  prologue opword (`FMOVEM.L FPIAR/FPSR/FPCR,-(A7)`, encoding `F227 BC00`), triggers a genuine
  trap, and fails fast (sentinel `0xDEAD3001`) if re-entered before the first entry completes,
  rather than spinning to a timeout.
- **`fpsp_packed_kernel_e2e.s`** — embeds the actual Q700 ROM FPSP kernel bytes verbatim
  (`.byte` directives, real ROM bytes at `[0x8D000..0x95000)`), executed at its native address,
  triggered by a real `FMOVE.P` (packed decimal) trap through vector 11. Validates SP/A0
  integrity across the trap, correct FSAVE frame size, and exactly-one kernel re-entry.

### 9. EU integration: fold into the existing CPLX cluster

FP ops fold into the existing CPLX cluster (DIV/MUL/CHK/CMP2/CHK2's shared multi-cycle EU +
IQ port), the same pattern MUL used when added — NOT a dedicated new EU/IQ-port/ROB-port.
Chosen given this project's LUT-bloat sensitivity, at the cost of FP ops contending with
integer DIV/MUL/CHK for the same slot.

### 10. Overflow/Underflow: hardware-native substitution, NOT deferred to software

**REVISION 2026-08-15**, replacing the original blanket busy-frame deferral for OVFL/UNFL
(see the narrowed FSAVE/FRESTORE section below). Verified directly against the MC68040 UM,
§9.8.6 (Overflow) and §9.8.7 (Underflow) — a materially different picture than initially
assumed:

**Real 68040 hardware does not write anything to a floating-point-register destination on
overflow or underflow — ever, regardless of whether the user enabled the FPCR trap bit.**
Quoted directly: "Trap Disabled Results: If the destination is a floating-point data
register, then the register is not affected, and ... an exception is reported ... Trap
Enabled Results: Results are identical to the trap disabled case." This is not an optional
compatibility refinement — every real 68040 unconditionally traps to software to complete
these two conditions for register destinations. The completion algorithm itself is fully
specified and simple:

**Overflow** — substitute a value keyed on rounding mode and sign only:

| Mode | Result |
|---|---|
| RN | Infinity, sign of the intermediate result |
| RZ | Largest-magnitude number, same sign |
| RM | +overflow → largest positive; −overflow → infinity |
| RP | +overflow → infinity; −overflow → largest negative |

**Underflow** — denormalize: shift the mantissa right while incrementing the exponent until
it reaches the destination format's denormalized exponent value, then round; if the shift
empties the mantissa entirely, fall back to a sign/rounding-mode table structurally identical
in shape to overflow's (zero vs. smallest-denormal).

**Decision: implement both substitutions directly in hardware, in the CPLX-cluster FP EU,
rather than trapping to software at all for the baseline case.** Neither needs CU_SAVEPC, the
writeback buffer, or any busy-frame-specific state — overflow is a 4-way combinational lookup
on inputs the EU already has; underflow is a right-shift-until-normalized, which real 1990
silicon likely punted to software specifically because a variable-length shift doesn't fit a
fixed-latency in-order pipeline stage — a constraint this design does not share (the CPLX EU
is already multi-cycle for FDIV). This is a genuine improvement over "68881-compatible", not
merely equivalent to it: since real hardware traps unconditionally for this case, implementing
the substitution ourselves means we never trap at all for the common case, where every real
68040 pays a full exception round-trip.

**What this does NOT cover**: the separate, narrower case of a user program that explicitly
enables the FPCR trap bit for OVFL/UNFL and wants a custom handler to inspect the true
pre-overflow value via the $6000-exponent-biased "exceptional operand" (ETEMP) the manual
also specifies for that path. That case still needs the busy frame's fuller field set —
see the FSAVE/FRESTORE section below for the now-narrowed scope of what remains deferred.

## FSAVE/FRESTORE State Frames

Verified directly against the MC68040 User's Manual, §9.7 and Figure 9-7 — this **corrects**
the original session's recollection of "4/44/52/100 bytes, versions 0x40/0x41" (there is no
52-byte frame; null and idle are both 4 bytes; the byte at offset $01 of the header is a
**length-in-hex indicator**, not a format-type enum with values $40/$41 — those numbers in
the busy-frame diagram are the FPU's internal *version number*, a silicon revision ID).

Four state frame types the manual defines, of which this design implements three in full and
scopes the fourth down:

| Frame | Size | Header ($00) | Implemented |
|---|---|---|---|
| Null | 4 bytes | Version forced to `$00` (identifies null, not a length code); byte 1 undefined | **Yes, in full** |
| Idle | 4 bytes | Real version number; byte 1 = `$00` (0 extra bytes) | **Yes, in full** |
| Unimplemented FP instruction | 44 bytes (22 words) | Version; byte 1 = `$28` (40 extra bytes) | **Yes, in full** — this is the actual "route to FPSP" path |
| Busy (numeric exceptions) | 100 bytes (50 words) | Version; byte 1 = `$60` (96 extra bytes) | **Narrowed — see below** |

**Unimplemented-instruction frame fields** (the one that matters for the FPSP-routing path):
STAG/DTAG (3-bit source/dest operand-type tags), CMDREG1B (instruction command word), E1
(unsupported-data-type flag), FPTS/FPTE/FPTM (destination operand, extended precision —
direct copy from the 80-bit register file per Decision 1, no conversion needed), ETS/ETE/ETM
(source operand, extended precision, same).

**Busy frame scope, REVISED 2026-08-15 — narrower than the original blanket deferral, and
verified against both the manual and the real ROM FPSP kernel's disassembly:**

Per Decision 10, OVFL/UNFL substitution is now implemented directly in hardware, so the busy
frame is no longer needed at all for the baseline/common case of those two exceptions. What
remains genuinely deferred is only the case of a user-enabled FPCR trap wanting the true
pre-overflow "exceptional operand" (the $6000-exponent-biased ETEMP) via a custom handler —
a narrow, advanced use case, not baseline correctness.

Disassembly of the real Q700 ROM FPSP kernel (`420dbff3.rom`, base `0x40800000`) confirms
this split cleanly maps onto which numeric-exception vectors the kernel actually installs.
**Only 6 of the 8 numeric-exception vectors are wired by this ROM at all**: BSUN (48),
Underflow (51), Operand Error (52), Overflow (53), Signaling NaN (54), Unimplemented Data
Type (55) — installed at `0x4088D252`/`0x4088D856`/`0x4088D28C`/`0x4088D544`/`0x4088D68E`/
`0x4088DAB0` respectively. Vectors 49 (Inexact) and 50 (Divide-by-Zero) have no handler
installed anywhere in the ROM — consistent with real 68040 hardware always computing those
two exactly, with no discrepancy vs. a 68881/68882 ever possible, hence nothing to correct.

Of the 6 installed handlers, disassembly shows a clean split matching the manual's own E1/E3
distinction:
- **BSUN, Operand Error, Signaling NaN, Unimplemented Data Type** (4 of 6) never reference
  busy-frame-only offsets at all — they read only fields the smaller 44-byte
  unimplemented-instruction frame already carries (an opclass/CMDREG1B-class field at what
  resolves to offset $08 of that frame). These already work correctly once the
  unimplemented-instruction frame (above) is implemented — no additional work needed.
- **Overflow, Underflow** (2 of 6) genuinely and unconditionally read CU_SAVEPC (busy-frame
  offset $08) and WBTS (offset $18) on the mainline path, gating real conditional branches —
  confirmed empirically, not a corner case. But the actual instructions doing this
  (`moveb %fp@(-284),%d0; andib #-2,%d0; beqw ...`) are a software bit-test-and-branch on a
  byte value, not a jump to a hardware micro-PC address — meaning CU_SAVEPC most likely
  encodes a status/discriminant byte the handler reads as data, not a literal
  pipeline-resumption mechanism we'd need to fake. WBTS/WBTE/WBTM is simply our EU's
  already-computed (but exception-flagged) tentative result, which we currently discard —
  capturing it into the frame is plumbing, not a new capability. Since Decision 10 already
  eliminates the trap for the baseline OVFL/UNFL case, this handler code path is now ONLY
  reached when a user program has explicitly enabled the FPCR trap for these two exceptions —
  genuinely rare. **Still deferred**: pin down CU_SAVEPC's exact bit encoding (not yet
  confirmed against primary source) before attempting this narrow path; not a blocker for
  anything else in this design.

**Version number**: real hardware's own busy-frame diagram example uses `$40`. This design
does not need to match a specific real silicon stepping; `$40` is a reasonable default choice,
finalized at implementation time (not a design blocker).

## Verification Strategy

Two independent surfaces, deliberately not conflated:

1. **Arithmetic correctness of the 11 HW-native ops**: lock-step against Musashi, now
   bit-exact-capable per Decision 1 (both sides use 80-bit extended `floatx80`/equivalent
   internally). Extend `tools/musashi/m68k_ref.h` per Decision 3.
2. **FPSP trap-boundary behavior** (exception delivery, FSAVE frame contents, self-recursion
   safety, FPSP kernel execution): directed ROM-kernel tests, NOT lock-step (Musashi cannot
   referee this surface regardless of internal precision — it has no real exception model,
   wrong FSAVE frame shape, no unimplemented-instruction trap). Already-vendored 35
   `fpu_*`/`fpsp_*` tests are the acceptance corpus (Decision 8) — no new harness needed.

## Explicitly Out of Scope / Deferred

- FScc/FDBcc/FTRAPcc hardware acceleration (Decision 2) — deferred, not ruled out.
- **Only** the user-enabled-FPCR-trap-with-custom-handler path for OVFL/UNFL exceptional-
  operand inspection (FSAVE/FRESTORE section, Decision 10) — the baseline OVFL/UNFL
  completion is now hardware-native and NOT deferred; only the narrow $6000-biased-ETEMP/
  CU_SAVEPC path for advanced user handlers remains open, and even that only needs
  CU_SAVEPC's exact bit encoding pinned down, not new architecture.
- Full Musashi FP-exception/FSAVE-frame parity (Decision 3) — Musashi's own emulation-core
  limitations, worked around via directed tests instead.
- FPSP itself is never shipped in this project's RTL (Decision 8) — a booted OS's own copy
  runs; the CPU's only job is the trap protocol.

## FpuCore ↔ Musashi Divergence Register (2026-08-15, from the arithmetic-core plan)

Decision 1 promises "bit-exact lock-step on the 11 HW-native ops' arithmetic results."
That promise holds, **with these documented exceptions**, each of which must be
excluded from the Musashi-refereed corpus and covered by a directed test instead.

**D1 — FINT/FINTRZ: Musashi clamps to a 32-bit integer; real 68040/FPSP does not.**
`tools/musashi/musashi/m68kfpu.c` opmode `0x01` (Fsint) and `0x03` (FsintRZ) are implemented
as `int32_to_floatx80(floatx80_to_int32(source))`. For `|x| >= 2^31` this destroys the value.
Real FINT semantics are `floatx80_round_to_int` (which Musashi's own SoftFloat *provides* at
`softfloat.c:3082` but `m68kfpu.c` never calls). `FpuCore` implements the real semantics.
=> Lock-step FINT/FINTRZ only for `|x| < 2^31`. Larger magnitudes: directed test only.
=> Candidate Decision-3-class Musashi-bridge fix; do NOT "fix" our hardware to match.

**D2 — FMOVECR $38..$3F (10^32 … 10^4096): Musashi's table is double-derived, not the ROM.**
`m68kfpu.c` builds `10^32`..`10^256` with `double_to_fx80(1e32)`-style calls (10^32 is the
first power of ten not exactly representable in an IEEE double), and `10^512`..`10^4096` by
repeatedly squaring an already-rounded `1e256`. Independently computing the correctly-rounded
64-bit-significand value of each 10^k (exact rational arithmetic) reproduces the well-known
real-68881-ROM values and **disagrees with Musashi in the low mantissa bits for all eight
entries $38..$3F**. `FpuCore` implements the correctly-rounded/real-ROM values.
=> Exclude FMOVECR offsets $38..$3F from Musashi lock-step; directed test vs. the UM table.
=> **VERIFY-1 RESOLVED (2026-08-16)**: an exact rational recomputation of every 10^k and an
independent second implementation (QEMU `target/m68k` `fpu_rom[]`) both agree bit-for-bit
with the table `FpCheapPipe.cromWords` carries. D2 stands as written.

**D3 — FMOVECR $0B (log10(2)): Musashi stores 1 ULP below correctly-rounded.**
Musashi: `0x3FFD 9A209A84FBCFF798`. Correctly-rounded-to-nearest: `...F799`.
=> **VERIFY-1 RESOLVED (2026-08-16), in Musashi's favour.** Three independent lines of
evidence: (a) QEMU's `fpu_rom[]` independently carries `...F798`; (b) rounding the exact
value to 67 bits and *then* to 64 (the 68881's documented constant-ROM double-rounding, the
ROM storing a few guard bits past extended) yields exactly `...F798`; (c) log10(2) is the
**only** one of the seven transcendental ROM constants for which that 67-bit intermediate
rounding changes the 64-bit answer — pi, e, log2(e), log10(e), ln(2), ln(10) all give the
correctly-rounded word either way, which is precisely why $0B is the lone anomaly. `FpuCore`
therefore keeps `...F798`: it is the genuine 68881 FMOVECR-under-RN result, not a Musashi bug.

**D4 — Every FPSR exception-status output is directed-test-only.** Decision 3 already
records that Musashi raises zero FP exceptions (confirmed in the vendored source: neither
`fmove_reg_mem` nor `fpgen_rm_reg` ever calls `float_raise`; every `REG_FPSR` write in
`m68kfpu.c` is an FPCC-nibble write). `FpuCore.io.res*.exc` (SNAN/OPERR/OVFL/UNFL/DZ/INEX2)
therefore has no oracle and is directed-tested only.

*Precise statement (tightened 2026-08-17 — the original wording said FPSR "is never
lock-stepped", which is no longer true).* FPSR **is** read architecturally inside the
lock-step corpus: `FpuLockStepSpec`'s FTST-vs-FCMP test executes `fmove.l %fpsr,%d0..%d3`
and the resulting D-registers are compared **bit-exactly, all 32 bits**, by the ordinary
`LockStep.compare` machinery. That is intentional and correct *for the FPCC nibble*, which
Musashi does model. It is safe today only because that particular program never raises an
FP exception — an incidental property of the chosen instructions, not a designed guard. So
the real rule is:

> **FPSR exception status is never lock-stepped IN A PROGRAM THAT CAN RAISE A FLAG.** Any
> lock-stepped program containing an instruction that can set FPSR.EXC/AEXC must either
> mask those bytes (`& 0xF0FF0000`, keeping FPCC and dropping EXC/AEXC) before comparing,
> or must not compare FPSR at all — because our RTL will set the sticky bit and Musashi
> will not, and the resulting divergence is *the oracle being wrong*, not the DUT.

A future edit that adds a raising instruction to an existing lock-stepped FPSR-reading
program would otherwise produce a confusing false divergence that a naive "fix" would try
to match by *removing* correct FPSR accrual — exactly the class of mistake this Divergence
Register exists to prevent. `FpuLockStepSpec` carries an in-file guard comment pointing
back here.

**D5 — pseudo-denormal (exp = 0, explicit integer bit set) exact cancellation.** `FpAddPipe`
declares an exact cancellation whenever the two operands have equal *effective* exponents
(`exp == 0` reading as 1) and equal significands, and returns SoftFloat's
`packFloatx80(rmode == RM, 0, 0)`. SoftFloat orders operands by the *raw* exponent, so for
`{exp=0, sig=S}` vs `{exp=1, sig=S}` it instead takes its `bExpBigger`/`aExpBigger` arm and
returns a zero whose sign is the flipped operand sign rather than the rounding-mode sign.
The two disagree **only** when exactly one operand is a pseudo-denormal, because a genuine
subnormal always has `sig < 2^63` while a normal always has `sig >= 2^63`, so equal
significands with unequal raw exponents is otherwise impossible. `FpuCore` (and
`FpRefModel`) implement the value-correct answer; SoftFloat's is the outlier.
=> Pseudo-denormal operands are excluded from Musashi lock-step for FADD/FSUB.

**D6 — FCMP against an infinity does not raise OPERR.** Musashi's FCMP (`m68kfpu.c:1517-1545`)
resolves `inf` operands from an explicit table and never calls `floatx80_sub`, so no invalid
operation is raised. `FpAddPipe` therefore suppresses its `inf − inf` OPERR when the op is
FCMP and the explicit table applies. (FADD/FSUB of `inf − inf` still raise OPERR.)

**D7 — FABS/FNEG/FMOVE do not quiet a signalling-NaN source.** Musashi implements them as
pure bit twiddles (`m68kfpu.c:1409-1424`: FABS is `high &= 0x7fff`, FNEG is `high ^= 0x8000`,
FMOVE is a plain copy) with no `propagateFloatx80NaN` call, so an SNaN source is written to
the destination still signalling. `FpuCore` matches Musashi bit-for-bit and *does* raise
`exc.snan`, but does not quiet the value. Real 68040 behaviour with the SNAN trap disabled is
to write the QUIETED NaN. This is a one-bit difference in the destination register, visible
only when an SNaN is moved/negated/absolute-valued with traps off.
=> Deliberately left matching Musashi so lock-step stays clean. If the FPSR/exception task
later implements the real "quiet on untrapped SNAN" destination rule, it belongs in the EU's
result-writeback path (one OR of 0xC000000000000000 gated on `exc.snan && !writeFp_is_cmp`),
not in this datapath, and this entry must be revisited at that point.

**D8 — `FMOVE.B (A7)+,FPn` / `FMOVE.B -(A7),FPn`: Musashi's FPU EA path adjusts A7 by ±1,
real hardware (and this core) by ±2.** Found during Task 8, independently re-confirmed
against the vendored source. The 68k rule is that a BYTE `(An)+`/`-(An)` access on the stack
pointer adjusts by **2**, not 1, so A7 stays word-aligned. Musashi implements that rule in
its INTEGER effective-address path only: `m68kcpu.h:749-750` defines the A7-specific
`EA_A7_PI_8() ((REG_A[7]+=2)-2)` / `EA_A7_PD_8() (REG_A[7]-=2)`, and the integer opcode
handlers use them (e.g. `m68kops.c:227,802,1399`). Musashi's **FPU** EA path does not:
`m68kfpu.c`'s `READ_EA_8` uses the plain `EA_AY_PI_8()`/`EA_AY_PD_8()` — i.e. `(AY++)` /
`(--AY)`, `m68kcpu.h:720,723` — for mode 3 and mode 4 at `m68kfpu.c:380` and `:385`, with no
`reg == 7` special case anywhere in the function (`m68kfpu.c:362-425`); `WRITE_EA_8` has the
same gap at `m68kfpu.c:802` and `:808`. So Musashi decrements/increments A7 by **1** for a
Byte-format FP memory source, leaving the stack pointer odd. This core gets it right on both
paths: the FP memory-source crack's own per-format delta table applies the quirk explicitly
(`src/main/scala/m68k040/decode/DecodeStage.scala:1227,1234` —
`B"3'b110" -> Mux(ucFpAnIsA7, U(2, 5 bits), U(1, 5 bits))`), matching the shared integer
`EaDecoder` rule (`src/main/scala/m68k040/decode/EaDecoder.scala:44-49`) — i.e. the
architecturally correct ±2.
Scope of the divergence, precisely: **only the Byte source/destination format** (source
specifier `110`), **only** `(A7)+` and `-(A7)`, and only the FP `<ea>` path. Word/Long/
Single/Double/Extended are unaffected because `EA_AY_PI_16/32` already adjust by 2/4, which
matches the A7 rule for those sizes; every address register other than A7 is unaffected by
construction; and register-to-register / immediate FP forms have no EA at all.
`FpuCore` is not involved — this is purely an address-generation difference, visible in A7
(and in the byte actually read) rather than in any FP value.
=> **Musashi is wrong here and our hardware is right; do NOT "fix" the core to match.**
=> **Task 13 (bit-exact lock-step tests): no action required as currently scoped** — Task 13
is explicitly register-to-register plus immediate loads, and states that memory-source `<ea>`
forms (Task 6b's) are a separate, later subset. This entry exists so that the **later
memory-source lock-step subset** either excludes `FMOVE.B (A7)+,FPn` / `FMOVE.B -(A7),FPn`
from the Musashi-refereed corpus with this entry as the documented reason, or special-cases
them in the oracle glue (post-step A7 fixup of ∓1); a directed whitebox test then owns the
±2 behaviour, exactly as D1/D2/D5 are handled.

**D9 — `FMOVEM.L <list>,-(An)` (control-register list, popcount ≥ 2): Musashi writes the
register images in the wrong ADDRESS order.** Found during Task 9b. **This entry is
primary-source-closed** — unlike most of this register, it does not rest on a derivation.

Primary source, quoted verbatim from a non-OCR `pdftotext -layout` extraction of the actual
PDF — **M68000 Family Programmer's Reference Manual** (Motorola, 1992, M68000PRM), Section 5
"Floating-Point Instructions", instruction page *"FMOVEM — Move Multiple Floating-Point
Control Registers (MC6888X, MC68040)"*, **page 5-91** (note the page header explicitly scopes
the description to the MC68040, so it is directly authoritative for this core):

> "Moves one or more 32-bit values into or out of the specified system control registers. Any
> combination of the three system control registers may be specified. The registers are always
> moved in the same order, regardless of the addressing mode used; the floating-point control
> register is moved first, followed by the floating-point status register, and the
> floating-point instruction address register is moved last. If a register is not selected for
> the transfer, the relative order of the transfer of the other registers is the same. The
> first register is transferred between the floating-point unit and the specified address, with
> successive registers located up through higher addresses."

> "When more than one register is moved, the memory or memory-alterable addressing modes can be
> used as shown in the addressing mode tables. If the addressing mode is predecrement, the
> address register is first decremented by the total size of the register images to be moved
> (i.e., four times the number of registers), and then the registers are transferred starting at
> the resultant address. For the postincrement addressing mode, the selected registers are
> transferred to or from the specified address, and then the address register is incremented by
> the total size of the register images transferred."

Obtained at `https://ia801904.us.archive.org/10/items/M68000PRM/M68000PRM.pdf` (item
`https://archive.org/details/M68000PRM`; searchable OCR twin `.../M68000PRM_djvu.txt` agrees
verbatim). Independently corroborated by the **MC68881/MC68882 Floating-Point Coprocessor
User's Manual** (Motorola, MC68881UM/AD Rev 1, 1st ed. 1987), Section 4.6, instruction page
*"FMOVEM — Move Multiple Control Registers"*, **page 4-76**, whose wording is substantively
identical (`https://archive.org/details/bitsavers_motorola68882FloatingPointCoprocessorUsersManual1e_23895950`).

**The architectural rule, therefore:** the memory image is **always** FPCR at the lowest
address, then FPSR, then FPIAR at the highest — for **every** addressing mode. `-(An)` is
implemented as a single up-front `An -= 4 × popcount(mask)` followed by ascending transfers;
the per-register *processing* order is never reversed.

**Musashi's divergence.** `tools/musashi/musashi/m68kfpu.c:1635-1660` (`fmove_fpcr`) processes
`reg & 4` (FPCR), then `reg & 2` (FPSR), then `reg & 1` (FPIAR) and calls `WRITE_EA_32` /
`READ_EA_32` once per selected register. Those helpers re-evaluate `EA_AY_PD_32()` freshly on
every call, so for `-(An)` each call independently decrements `An` by 4 *before* writing.
Result for `FMOVEM.L FPIAR/FPSR/FPCR,-(A7)` (`F227 BC00`): Musashi leaves FPIAR at the lowest
address and FPCR at the highest — **the exact reverse of the architectural image**. It is
also self-inconsistent: Musashi's own matching `FMOVEM.L (A7)+,FPIAR/FPSR/FPCR` reload (same
fixed order, postincrement) reads that image back and swaps FPCR with FPIAR, so a
push/pop pair does not round-trip. Only `popcount(mask) == 1` and non-predecrement modes are
unaffected. Independently corroborated by WinUAE's `fpp.cpp` control-register path, which
implements the Motorola mechanism exactly (sum the sizes, `ad -= incr`, then ascend).
=> **Musashi is wrong here; do NOT implement the core to match it.** Exclude
`FMOVEM.L <list>,-(An)` with `popcount ≥ 2` from any Musashi-refereed corpus; a directed
whitebox round-trip test owns it, exactly as D1/D2/D5/D8 are handled. Non-predecrement modes
remain lock-step-eligible against Musashi.

**D9a — corrections to two claims that circulated in the Task 9b brief before this citation
existed** (recorded so they are not re-derived or re-propagated):
1. The brief derived a *"predecrement reverses the register processing order (FPIAR, FPSR,
   FPCR)"* rule. That produces the **correct memory image** but is **not** the mechanism
   Motorola documents, and the two are distinguishable: they differ in the temporal order of
   the bus writes, which is observable on a partially-faulted access (which write faults
   first) and in any bus-accurate trace comparison. Implement the documented mechanism
   (decrement by the total, then ascend), not the reversal.
2. The brief's supporting WinUAE quote (*"6888x … predecrement have inverted register list
   order … 68040+ only use inverted register order if EA is predecrement"*) is real but was
   **misattributed**: in WinUAE it governs the FMOVEM **data**-register form (FP0–FP7,
   opclass 110/111), keyed on the 2-bit MODE field in ext[12:11], which has **no analogue in
   the control-register encoding**. It is not evidence about the control-register form.

**D9b — register-select mask `RRR == 000`.** WinUAE's control-register FMOVEM path treats an
all-zero mask as *FPIAR selected* (`extra |= 0x0400`) for both directions. This is **not**
confirmed against either manual and is recorded as an open, unverified question, not as a
rule to implement. The conservative behaviour (leave `RRR == 000` undecoded → vector-11 F-line
trap) is what this core does today and is safe with respect to it.

**D10 — `FMOVE FPn,(d16,PC)` / `FMOVE FPn,(d8,PC,Xn)`: Musashi WRITES through a PC-relative
destination; this core rejects it.** Found during Task 14b (the `FMOVE FPn,<ea>` store
direction). Every one of Musashi's FP write helpers carries a PC-relative arm and uses it as
a *destination*: `WRITE_EA_8` (`m68kfpu.c:832`), `WRITE_EA_16`, `WRITE_EA_32` and
`WRITE_EA_64` all have a `case 2: // (d16, PC)` under mode 7 that computes `EA_PCDI_*()` and
then calls `m68ki_write_*` to it. PC-relative modes are **not alterable** on any 68k, so an
`FMOVE FPn,(d16,PC)` is an illegal encoding, not a store. This core's `ucFpStoreOk`
(`src/main/scala/m68k040/decode/DecodeStage.scala`) requires `!ucBfEaDec.pcRel` and routes the
attempt to the same clean vector-11 F-line trap the rest of the band already produces.
=> **Musashi is wrong here and our hardware is right; do NOT "fix" the core to match.**
=> No corpus test currently exercises it, so lock-step is not expected to hit this; a future
fuzz seed could. `FpMemStoreSpec` owns it as a directed decode-level test.

**D11 — `FMOVE.W FPn,Dn` / `FMOVE.B FPn,Dn`: Musashi ZERO-EXTENDS over the whole data
register; real hardware writes only the low word/byte.** Also from Task 14b.
`WRITE_EA_16`/`WRITE_EA_8` (`m68kfpu.c`) take their data parameter as `uint16`/`uint8` and
their mode-0 arm is a bare `REG_D[reg] = data;`, so the upper 16/24 bits of Dn are cleared.
That contradicts the universal 68k data-register rule (a `.W`/`.B` destination is a partial
write; the rest of Dn is untouched), which this core implements by reading the old Dn back as
a merge source on the `DecOp.FPSTORECVT` uop. Note Musashi's *own* integer `MOVE.W`/`MOVE.B`
into a data register do the partial write correctly — this is a gap in its FPU EA path only,
exactly like D8.
=> **Musashi is wrong here and our hardware is right.** Exclude `FMOVE.W/.B FPn,Dn` from the
Musashi-refereed corpus; `FpMemStoreSpec` asserts the merge shape at decode level, and the
memory forms of `.W`/`.B` (which have no such divergence) ARE lock-stepped in
`FpuLockStepSpec`. The ported corpus test `fpu_fmove_fp_to_ea_matrix.s` deliberately does not
assert this case either ("Numeric exactness for .W/.B register merge is not asserted here").

**D12 — `FMOVE FPn,<ea>` raises no exception at all in Musashi.** `fmove_reg_mem`
(`m68kfpu.c:1570-1632`) calls neither `float_raise` nor `SET_CONDITION_CODES` on any of its
eight arms — verified by direct search of the function body. The underlying SoftFloat
routines it calls *do* set `float_exception_flags` (`roundAndPackInt32` raises invalid on
saturation; `roundAndPackFloat32/64` raise overflow/underflow/inexact), but Musashi never
reads that back into FPSR. This core raises the architecturally correct OPERR/OVFL/UNFL/
INEX2/SNAN from `FpNarrowPack`, accrues them into FPSR.EXC, and vectors only when the
matching FPCR enable bit is set (Task 14c's gate). Additionally, the Word and Byte
destination formats raise OPERR when the converted int32 does not fit the narrower
destination — Musashi merely truncates via a `(sint16)`/`(sint8)` cast, and the *stored
value* matches it exactly; only the exception is extra.
=> An extension of D4 ("every FPSR exception-status output is directed-test-only") to the
store direction. `FpNarrowPackSpec` owns the exception coverage; the lock-stepped store
programs never enable an FPCR trap and never read FPSR.

### VERIFY-AT-IMPLEMENTATION

- **VERIFY-1 — the FMOVECR constant ROM words for offsets $0B and $38..$3F.**
  **RESOLVED 2026-08-16** — see D2 and D3 above. The table in `FpCheapPipe.cromWords` is
  confirmed by two independent implementations plus an exact recomputation, and the $0B
  anomaly now has a mechanistic explanation (68881 constant-ROM double rounding). The
  MC68881/MC68882 UM's own ROM table was **not** read directly; the evidence above is
  circumstantial-but-convergent rather than primary. Re-open only if the UM contradicts it.
- **VERIFY-2 — whether FPSR.I is *unconditionally* cleared by FCMP. STILL OPEN.** Musashi
  clears it only via the explicit infinity-comparison table (`m68kfpu.c:1517-1545`); an
  *overflowing finite* difference (e.g. `LARGEST − (−LARGEST)` under RN) still runs through
  `SET_CONDITION_CODES(res)` and would set I. `FpuCore` matches Musashi. Check the M68000
  Family PRM's FCMP page; if it says I is always cleared, that is a further divergence, not a
  bug in our hardware.
- **VERIFY-3 — FMOVECR and FINT/FINTRZ are NOT hardware ops on a real MC68040. CONFIRMED
  2026-08-16.** The Motorola 68040 FPSP's unimplemented-instruction dispatch table
  (`arch/m68k/fpsp040/tbldo.S` in Linux, verbatim Motorola source) carries real emulation
  routines for extension-word opcode `$00` (FMOVECR → `smovcr`), `$01` (FINT → `sint`) and
  `$03` (FINTRZ → `sintrz`); a hardware-implemented instruction would never reach that table.
  All three therefore take the F-line unimplemented trap on real silicon. Decision 2 and the
  2026-08-09 spec §1 nevertheless put them in the hardware set: that is a deliberate,
  now-recorded **superset** of real silicon (same posture as Decision 10's OVFL/UNFL
  substitution), observationally equivalent to a correct FPSP. **The EU-integration and
  decode tasks must NOT route FMOVECR/FINT/FINTRZ to the F-line trap.**
- **VERIFY-4 — "unnormal" (exp≠0, integer bit = 0) source operands. OPEN BY DESIGN.** Real
  68040 raises the unimplemented-data-type exception (vector 55); SoftFloat/Musashi silently
  computes with them. `FpuCore` **classifies** them (`io.srcUnnormal`/`io.dstUnnormal`,
  combinational) but applies no policy — the trap-or-compute decision belongs to the
  EU-integration task.
