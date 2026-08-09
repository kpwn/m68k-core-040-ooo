# FPU (hardware core) — design spec

**Status**: drafted from a live design conversation (this session,
2026-08-09), grounded against real Vivado synthesis data and this
project's existing RTL. Awaiting user review before an implementation
plan is written.

## 0. Context and goal

This project's integer 68040 ISA has been complete since 2026-07-12.
The user's stated goal: **"an FPSP plan that targets not the best perf
but an okay perf, implementing fpsp's minimum set and
fadd/fsub/fmul/fdiv plus fsave/fmove"**, extended to include other
cheap operations, explicitly **in-order** (no pipelining requirement),
and **area-light / FMax-neutral**, with an explicit preference for
**DSP48E2 blocks over LUT fabric** wherever the datapath needs
multiply/accumulate hardware.

The target platform is a macqd700 (Quadra 700 clone) SoC running
classic Mac OS. This matters architecturally: **the Quadra 700 ROM
already contains Apple's own FPSP-equivalent software** (real 68040
silicon architecturally requires OS/ROM-level software completion for
unimplemented FP instructions and denormal handling — this is not
optional even on real hardware). This core's job is therefore:

1. Implement a small, real hardware FP op set correctly.
2. Deliver a correct, real vector-11 (F-line unimplemented) exception
   for everything else, with the ARCHITECTURALLY CORRECT stack frame
   format for each of the two distinct vector-11 cases (see §5).
3. Ship **zero** FPSP code. The ROM's own FPSP runs unmodified on this
   core exactly as it would on real silicon.

This also confirmed a real gap does NOT exist: A-line (vector 10,
classic Mac OS Toolbox call dispatch) and F-line (vector 11) opcode
routing were investigated this session and found to already be
correct, with genuine exhaustive test coverage added where none
existed before (commit `387921c` — all 4096 line-A opwords, all
unimplemented line-F opwords, both swept to vector 10/11
respectively). This spec builds on that as a confirmed-solid
foundation, not an open risk.

## 1. Goals

- Real hardware datapath for: FMOVE, FABS, FNEG, FCMP, FTST (synthetic
  FCMP-vs-implicit-zero, no dedicated datapath), FADD, FSUB, FMUL,
  FDIV, FSQRT, FMOVECR (ROM constant table), FMOVEM (both data-register
  -list and control-register-list forms), FSAVE/FRESTORE (idle-frame
  only — see §4).
- FMOVE to/from the 3 control registers (FPCR, FPSR, FPIAR).
- Correct FPCC (condition-code) computation and a real, renamed FPCC
  register file, following this project's existing NZVC/X rename
  pattern.
- Correct vector-11 delivery, with the correct format-$0-vs-format-$2
  frame distinction, for every op NOT in the hardware set (§5).
- Native 80-bit IEEE-754 extended-precision compute throughout the
  arithmetic datapath — not a narrower internal format widened at
  register-write time (§2, real Vivado-grounded cost data).
- Area-light: use DSP48E2 inference for all multiply-class datapath
  elements (matches this project's existing `MulCore`/`DivCore`
  idiom), matching the explicit user instruction.
- FMax-neutral: the FPU EU must not become a new session-worthy
  critical-path family the way `DcachePlugin`/`RobPlugin` families
  were this session — gated with the same real post-route discipline
  established this session (§7).
- In-order: no FPU-specific pipelining, no new IQ/scoreboard/
  reservation-station scheme. Reuse the existing `DivEuPlugin` pattern
  (§3) — single shared multi-cycle datapath, busy-gated issue.

## 2. Non-goals

- No FPSP code shipped in this core (§0). No packed-decimal support.
  No transcendentals (FSIN/FCOS/FATAN/FETOX/FLOGN/etc. — real 68040
  hardware doesn't have these either; they trap to FPSP on real
  silicon and will trap to the ROM's FPSP here too).
- No non-idle FSAVE/FRESTORE frame capture/replay (§4).
- No pipelined/high-throughput FPU datapath. "Okay perf," explicitly
  not best-in-class, per the user's own framing.
- No attempt to match m68k-ooo's own FPU's pipelined FADD/FMUL
  datapath shape — that design optimizes for throughput this project
  explicitly does not need; porting it would cost more area for a
  property (pipelining) this design isn't targeting.

## 3. Architecture: the FPU EU

**Recommended base: extend the existing `DivEuPlugin` pattern**, not a
new architectural mechanism. `DivEuPlugin.scala` already demonstrates
every structural property this FPU EU needs:
- Deasserts `issue.ready` while a multi-cycle op iterates (busy-gated,
  in-order, no new reservation station).
- Broadcasts a dynamic-completion `Flow[UInt]` (`completionPort`) into
  the existing IQ/ROB completion-port infrastructure — no new ROB
  port class needed architecturally, though the width/payload will
  differ (FP result + FPCC, not an integer completion).
- Already houses two structurally dissimilar ops in one EU slot
  (1-cycle CHK alongside multi-cycle iterative DIV) — directly
  analogous to housing 1-cycle FMOVE/FABS/FNEG/FCMP alongside
  multi-cycle FADD/FSUB/FMUL/FDIV/FSQRT in one FPU EU slot.

**Open implementation-time question** (flag for the plan, not decided
here): does the FPU EU become a NEW, additional CPLX-class EU slot, or
does it fold into the EXISTING `DivEuPlugin` slot (a 3rd/4th op class
sharing the same issue port, mirroring how DIV/MUL/CHK/CMP2/CHK2
already share one)? This affects IQ port count and ROB completion-port
count directly — needs to be resolved with real IQ-occupancy/
port-count analysis at plan-writing time, not guessed here.

## 4. FSAVE/FRESTORE — idle-frame only

Real usage justification: no op in this design's scope leaves
genuinely interruptible mid-flight state at a granularity FSAVE would
need to capture (worst-case latency ~55-67 cycles for FDIV/FSQRT — not
truly "long-running" in the sense that motivated real 68040's non-idle
frame format). FSAVE always produces a valid idle-format state frame;
FRESTORE accepts idle-format frames. This directly matches the
existing ported test target set (`fsave_frestore_basic`,
`fpu_fsave_idle_format_byte`, `fpu_fsave_null_frame`,
`fpu_fsave_frestore_idle_roundtrip` — all idle-frame-shaped tests, no
busy-frame test exists in the vendored corpus).

## 5. Exception delivery: the format-$0 vs format-$2 fix

`ExceptionUnit.scala:685-708` currently hardcodes **format-$2** for
EVERY vector-11 delivery. This was correct-by-coincidence until now
(nothing previously reaching vector 11 needed to distinguish the two
cases). Real MC68040 UM §9.6.1 requires:

- **format-$0** ("F-line emulator") — the opword isn't a recognized
  FPU coprocessor instruction at all (not even a valid FPU
  format/coprocessor-ID pattern within the 0xF200-0xF3FF general
  group, or any other line-F opword this core doesn't claim).
- **format-$2** ("unimplemented FP instruction") — the opword IS a
  real, recognized FPU instruction (valid coprocessor-ID and format
  bits), just not one this hardware implements (a transcendental,
  packed-decimal move, or similar — routed to the ROM's FPSP).

**Required fix, part of this work**: `MicroOpAssembler`'s F-line
decode must distinguish these two cases at the SAME granularity the
new hardware decode does (i.e., "recognized-but-unimplemented FPU op"
vs. "not FPU at all"), and `ExceptionUnit.scala` must select the frame
format accordingly rather than hardcoding format-$2 unconditionally.

## 6. Precision: native 80-bit extended (real Vivado-grounded decision)

FPn registers are 80-bit IEEE-754 extended precision regardless of
this decision — that's the fixed 68040 architecture. The open question
was whether the ARITHMETIC DATAPATH computes internally at 64-bit
double (widening to 80-bit only at register-write time) or natively at
80-bit extended throughout.

**Measured** (real OOC Vivado `synth_design` on hand-written probes
matching this project's `MulCore.scala`/`DivCore.scala` DSP-inference
idiom exactly — not SpinalHDL-generated, but structurally identical;
pre-place resource counts, valid for a resource-count question, no
timing claim):

| Structure | 53-bit (double) | 64-bit (extended) | Δ |
|---|---|---|---|
| FADD core (align+add+LZC+norm+round) | 490 LUT, 141 FF | 628 LUT, 175 FF | +138 LUT, +34 FF |
| FMUL significand | 110 LUT, 76 FF, 9 DSP | 161 LUT, 102 FF, 16 DSP | +51 LUT, +7 DSP |
| FDIV radix-2 iteration engine | 142 LUT, 224 FF | 171 LUT, 268 FF | +29 LUT, +44 FF |
| FSQRT radix-2 iteration engine | 144 LUT, 173 FF | 173 LUT, 206 FF | +29 LUT, +33 FF |
| **Total marginal cost** | | | **+247 LUT, +137 FF, +7 DSP** |

Device headroom (`xcku5p-ffvb676-2-e`, current post-Lever-F usage):
113,377/216,960 LUT (52.3% used, 103,583 free), 47,073/433,920 FF,
6/1,824 DSP48E2 (0.33% used, 1,818 free).

**Decision: native 80-bit extended compute.** The marginal cost
(+247 LUT / +7 DSP) is 0.22% of current design LUTs and 0.4% of
remaining DSP headroom — noise at this design's utilization. The
64-bit-double compromise would cost real 68040 fidelity
(double-rounding bugs on FADD/FMUL that a widen-on-store step cannot
fix after the fact) to save an amount of area that isn't a real
tradeoff here.

**Caveats carried forward, explicitly, for the implementation plan**:
- These are pre-place resource counts. The wider 80-bit align/
  normalize shifters may need their own pipelining/register insertion
  to stay FMax-neutral — this is an OPEN FMAX QUESTION the probe does
  not answer, and must be gated with a real post-route measurement
  once actual RTL exists (§7), not assumed free from this data alone.
- FDIV/FSQRT width scaling is mostly a LATENCY cost (56→67 iterations,
  ~20% more cycles for 64-bit vs 53-bit significand), not an area
  cost — consistent with, and already covered by, the "in-order,
  doesn't have to be the fastest" acceptance.
- The probe FADD core omits NaN/Inf/denormal special-casing (the
  measured delta is width-independent so it stays sound; the absolute
  counts are a floor — real RTL will add more for full IEEE-754
  compliance).

## 7. FMax and area gating (binding constraint)

Per the user's explicit instruction ("area light and fmax neutral"),
every implementation slice of this work must be gated with the SAME
discipline established across this session's FMax-closure and
IPC-push initiatives:
- Real post-route FMax gate (`vivado -mode batch -source
  synth/impl_FullCore.tcl`), not OOC-only (proven unreliable this
  session, including for targeted-family checks — see the FMax
  ledger's Lever N-A entry).
- Explicit LUT/FF/DSP-count report, pre/post.
- TNS and failing-endpoint-count tracked alongside WNS (not WNS alone
  — the session's standing methodology correction).
- git-worktree isolation for all A/B comparisons.
- Current confirmed baseline to gate against: **207.17 MHz post-route,
  113,377 LUT** (Lever F landing, commit `9752c5c`).

## 8. FPCC register rename

A 4th `RegFilePlugin(RegfileSpec.Fpcc)` instantiation, structurally
identical to the existing `RegFilePluginNzvc`/`RegFilePluginX` — same
rename/freelist/bypass machinery, already proven correct and
FMax-characterized this session. Exact FPCC bit layout (which
condition bits: N/Z/I/NaN at minimum, matching real 68040 FPSR.FPCC)
is an implementation-time detail, not a design-level open question.

## 9. Test target

The ported test corpus already has 100% FPU coverage relative to the
sibling m68k-ooo repo (confirmed this session, commit `9e2a7ae`'s
vendoring pass — 53 fpu_*/fpsp_*/fsave*/fmovem_ctrl_* tests in the
sibling, 53 already present here). The 48 currently-failing tests in
this repo's `fpu_*`/`fpsp_*`/`fsave*`/`fmovem_ctrl_*` families ARE the
target — no new test sourcing needed before implementation starts.
Some of these tests likely exercise transcendental/FPSP-only behavior
(`fpsp_dec2bin_sp_integrity`, `fpsp_packed_kernel_e2e`) that is
explicitly OUT of this hardware core's scope (§2) — expect a subset of
the 48 to remain legitimately failing (or to require the real ROM's
FPSP to be present in a boot-level test, not achievable in the
existing bare-core lock-step harness) even after this work lands. This
needs triage at implementation-plan time to separate "this hardware
op is genuinely missing" from "this test needs the ROM's FPSP, which
is out of scope for a bare-core test."

## 10. Open items requiring implementation-time verification (not silently assumed)

1. FPU EU port topology (§3): new EU slot vs. folded into
   `DivEuPlugin`'s existing slot — needs real IQ-port/ROB-completion-
   port-count analysis.
2. Exact FMOVE/FMOVEM/FMOVECR EA-addressing-mode coverage — full
   parity with the existing integer MOVE EA-decode matrix, or a
   narrower subset matching only what the 48 target tests exercise?
   Recommend: narrower-but-correct first pass targeting the test
   corpus, explicitly scoped, rather than a blind full-EA-matrix port.
3. FPCR rounding-mode and exception-enable bits — real 68040 FPCR has
   both; this spec doesn't yet lock how much of FPCR's control
   semantics (vs. just FPSR's status/condition semantics) are in
   scope. Needs an explicit decision before the plan is written.
4. §5's format-$0/format-$2 fix needs the EXACT bit-level definition
   of "recognized FPU coprocessor-ID/format pattern" cross-checked
   against the MC68040 UM before implementation (not assumed from this
   spec's prose description alone) — same standing discipline this
   project used for CPUSH/CINV's bit encoding (Task P5.1).
5. FTST's synthesis as FCMP-vs-implicit-zero: confirm this exactly
   matches real 68040 FTST semantics (condition-code effects
   specifically) before locking it as a decode-time rewrite rather
   than a distinct micro-op.
