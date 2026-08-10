# FPU (hardware core) — design spec

**Status**: DRAFT, with the fixed-latency throughput amendment below ratified by
the later project-wide IPC direction. Grounded against real Vivado synthesis
data and this project's existing RTL. Awaiting an implementation plan; no FPU
RTL exists yet.

## 0. Context and goal

This project's integer 68040 ISA has been complete since 2026-07-12.
The user's stated goal: **"an FPSP plan that targets not the best perf
but an okay perf, implementing fpsp's minimum set and
fadd/fsub/fmul/fdiv plus fsave/fmove"**, extended to include other
cheap operations, architecturally precise and **area-light / FMax-neutral**,
with an explicit preference for
**DSP48E2 blocks over LUT fabric** wherever the datapath needs
multiply/accumulate hardware.

The later binding throughput direction is that an additional latency cycle is
worth taking when it permits multiple operations in flight. Accordingly,
"in-order" here means precise architectural retirement and no separate FPU
reservation station; it does not permit a DSP-backed fixed-latency operation to
be busy-gated one at a time. FADD/FSUB/FMUL and the cheap fixed-latency operations
must be elastic II=1 pipelines. FDIV/FSQRT may remain single-context iterative
lanes.

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

- Real hardware datapath for: FMOVE, FABS, FNEG, FCMP, FTST (direct
  source classification, no arithmetic datapath), FADD, FSUB, FMUL,
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
- Throughput without new scheduler ports: reuse one existing-style issue gateway
  and completion path, but feed separate fixed-latency and iterative lanes.
  FADD/FSUB/FMUL and cheap operations accept every cycle when result credit is
  available; FDIV/FSQRT retain one iterative context. Architectural precision is
  still provided by the existing ROB.

## 2. Non-goals

- No FPSP code shipped in this core (§0). No packed-decimal support.
  No transcendentals (FSIN/FCOS/FATAN/FETOX/FLOGN/etc. — real 68040
  hardware doesn't have these either; they trap to FPSP on real
  silicon and will trap to the ROM's FPSP here too).
- No non-idle FSAVE/FRESTORE frame capture/replay (§4).
- No replicated or fully unrolled FDIV/FSQRT and no general multi-context
  iterative engine. "Okay perf" does not justify that area.
- No FPU-specific IQ, reservation station, extra PRF write port, or extra ROB
  completion port unless later occupancy and physical evidence explicitly
  justify one. Elastic fixed-latency pipelines should use the existing gateway
  and internal DSP/register resources first.

## 3. Architecture: the FPU EU

**Recommended base: reuse the integrated CPLX-MUL lesson, not the former
single-outstanding `DivEuPlugin` pattern.** One logical FPU issue gateway feeds:

- a cheap fixed pipe for FMOVE/FABS/FNEG/FCMP/FTST/FMOVECR;
- elastic, fully registered FADD/FSUB and FMUL pipes, each with initiation
  interval one; and
- one held iterative context shared by FDIV/FSQRT.

The fixed pipes carry pruned per-operation descriptors and per-stage
valid/flush poison. FMUL must infer the DSP48E2 A/B/M/P registers; FADD/FSUB may
add normalization boundaries as needed for the 250-MHz goal. Issue reserves
result capacity before entering any unstallable tail. Separate fixed-result
FIFO/hold and iterative-result hold feed one atomic arbiter; its winner drives
FP PRF write, FPCC write, wakeup, fault/status observation, and ROB completion
together. Collision storage must cover a fixed result meeting FDIV/FSQRT done;
a non-backpressured completion Flow is not permission to drop either result.

The existing IQ/ROB already tolerates completion out of execution order. FPU
macro effects remain precise because the ROB retires in order. No global result,
exception, or "flushed" latch may identify more than one in-flight operation;
association must travel with the descriptor or be keyed by ROB identity.

FTST is a distinct cheap classifier, not a decode rewrite into FCMP against
zero. Musashi's `m68kfpu.c` opmode `0x3a` passes the source directly to
`SET_CONDITION_CODES`; in particular, FTST of infinity sets FPSR.I, whereas
FCMP's difference-result rules deliberately leave I clear. The implementation
may share the generic result-to-FPCC classifier used after arithmetic, but must
not enter the subtract/compare datapath.

**Gateway topology is resolved:** FPU uops use the existing registered CPLX IQ
issue gateway and existing ROB completion lane. They carry an explicit FPU-kind
marker while retaining `Cluster.CPLX`; do not widen the `Cluster` enum, add a
sixth IQ select port, or add a ROB completion port. The registered port is
demultiplexed after its M2S boundary into integer-CPLX and FPU lanes.

Sharing selection does not mean sharing operand datapaths. FP source/destination
physical IDs, busy state, wakeup, and FPCC dependencies are separate from the
integer and NZVC classes. The FPU reads its own 80-bit PRF after the registered
issue boundary; no 80-bit value is added to `IqContext`, the integer PRF, or the
DivEu operand mux. Memory-format inputs may continue to use the existing integer
temporary sources for their 32-bit pieces.

The IQ candidate mask must be opcode/lane-credit aware. A busy FDIV/FSQRT (or
integer DIV) is ineligible while its iterative context is occupied, but must not
park in the one registered issue slot and block a younger fixed FMUL/FADD or
integer MUL. The next-cycle eligibility promise accounts for the operation
currently firing from that registered slot, so consecutive selection cannot
overbook an iterative context or elastic fixed-pipe entry.

Integer-CPLX and FPU result tails each remain held until a small fair grant
arbiter selects one for the existing ROB completion lane. Each EU gates its own
PRF write, bypass, wakeup, status/fault observation, and completion atomically on
that grant. A Flow producer may not emit speculatively and hope the top-level mux
keeps it. This topology adds only narrow selection/grant metadata; a separate
FPU IQ/ROB port is a later evidence-driven option, not part of the base design.

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

## 5. Exception delivery: vector-11 frame format — RESOLVED, no fix needed; a different real gap found instead

**Original concern in this spec (now resolved): NOT a bug.** A
full vector-by-vector audit against m68k-ooo (per explicit user
instruction — see below) confirmed `ExceptionUnit.scala:684,729-731`'s
existing behavior is CORRECT: m68k-ooo also stacks **format-$2 for
vector 11 unconditionally** (`rtl/core/exception.v:523,531-535`),
confirmed across BOTH of its vector-11 delivery paths
(`decode.v:3896` and the pseudo-vector path translated back at
`commit.v:1432-1435`). **13 of 13 other comparable vector/format pairs
also matched exactly** (fmt-$7 <- vec 2; fmt-$2 <- vec {3,5,6,7,9,11};
fmt-$0 <- everything else, identical selection expression on both
sides). No format-selection fix is needed for this work.

**The real, actionable gap the audit found instead**: what actually
differs between m68k-ooo's two vector-11 paths is NOT the frame
format — it's the stacked **PC field**: pre-instruction vs
post-instruction PC (`commit.v:1443-1477`). **This project has no
post-instruction-PC flavor of vector-11 delivery at all** —
`MicroOpAssembler.scala`'s generic line-F fallback leaves
`faultUsesNextPc := False`. This matters directly for this FPU work:
an FPSP-style handler that expects to RTE past a recognized but
software-completed FPU instruction needs the post-instruction-PC
variant, or it will loop on the same opword forever.

The trigger is now resolved from the sibling RTL rather than left as
an implementation-time guess. Its packed-source capture crack knows
the complete instruction length and raises pseudo-vector `0x8B`
(`decode/decode_1111.vh:486-493`); commit translates that back to
architectural vector 11 while selecting the fall-through PC. The
plain top-nibble line-F fallback has unknown/untrusted length and keeps
ordinary vector 11 with the faulting PC. **Required here:** any
recognized FPU instruction deliberately handed to FPSP after its full
length and required source state have been captured sets
`faultUsesNextPc := True`; an unrecognized line-F encoding keeps it
False. The architectural vector remains 11 in both cases—do not expose
`0x8B` outside an internal control encoding.

**Also surfaced, unrelated to vector 11 but worth carrying forward**:
one genuine MISMATCH found — M=1 interrupt dual-frame placement
(format-$1 throwaway + format-$0 main frame) is placed on
OPPOSITE stacks between the two projects (m68k-ooo: throwaway on MSP,
main on ISP; this project: the reverse). **Do not fix this to match
m68k-ooo** — this project's own lock-step oracle, Musashi, agrees with
THIS project's current placement, not m68k-ooo's. m68k-ooo is the
right oracle for frame FORMAT (confirmed above), but Musashi remains
the right oracle for frame PLACEMENT/ordering specifically for this
one case. No action taken; noted so it isn't rediscovered as a false
alarm later. (m68k-ooo's own `exc_msp_irq_fmt1.s` test, which would
exercise this, is not vendored into this project's corpus.)

**Verification method used, per explicit user instruction**: "vector
frame types need to match m68k-ooo on a vector-by-vector basis" — a
full audit cross-checked every vector this project delivers against
m68k-ooo's own exception/trap-frame-stacking implementation (not
re-derived from UM prose in isolation). Full per-vector table with
file:line evidence on both sides is in
`.superpowers/sdd/progress-ipc-push-2026-08-09.md`.

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

Device headroom (`xcku5p-ffvb676-2-e`) is ample. The latest completed
full-core checkpoint used 117,956/216,960 LUT (54.37%), 50,075/433,920 FF,
26 BRAM, and 4/1,824 DSP48E2. The table above predates that checkpoint but its
isolated width delta remains the relevant evidence: native extended precision
adds little relative to the device, especially in DSP resources.

**Decision: native 80-bit extended compute.** The marginal cost
(+247 LUT / +7 DSP) is 0.22% of current design LUTs and 0.4% of
remaining DSP headroom — noise at this design's utilization. The
64-bit-double compromise would cost real 68040 fidelity
(double-rounding bugs on FADD/FMUL that a widen-on-store step cannot
fix after the fact) to save an amount of area that isn't a real
tradeoff here.

**Caveats carried forward, explicitly, for the implementation plan**:
- These are pre-place resource counts. The wider 80-bit align/normalize
  shifters require elastic registered boundaries selected from synthesis and
  route evidence. Those stages may add latency but must preserve II=1. This is
  an OPEN FMAX QUESTION the probe does not answer and must be gated with a real
  post-route measurement once actual RTL exists (§7).
- FDIV/FSQRT width scaling is mostly a LATENCY cost (56→67 iterations,
  ~20% more cycles for 64-bit vs 53-bit significand), not an area cost. That is
  acceptable for the explicitly iterative lane and does not relax the II=1
  requirement for the fixed-latency lanes.
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
- The architectural target remains **250 MHz**. **200 MHz** is the current
  deployment floor, not the optimization target.
- Latest completed checkpoint: **WNS -1.766 ns at a 4.000-ns constraint,
  equivalent to 173.430 MHz**, with 117,956 LUT, 50,075 FF, 26 BRAM, and 4 DSP.
  It is a diagnostic baseline taken before the final FTB/deep-MUL landed set,
  not an FPU acceptance result.
- Inspect every affected pblock's capture, CLB occupancy, and congestion as well
  as global utilization. The current D-cache pblock is already overfull even
  though global area is healthy.
- If an implementation crosses an area budget, report the exact resource and
  pblock deltas for review before rejecting or reverting it. Area pressure is a
  design tradeoff checkpoint, not an automatic rollback.

## 8. FP and FPCC register rename

Add two independent register-file classes using the existing proven
rename/freelist/bypass machinery:

- `RegfileSpec.Fp`: 32 physical entries x 80 bits for eight architectural
  FP0-FP7 registers. Two async reads feed the registered FPU input boundary;
  the result arbiter provides one physical write/bypass lane. The 24 rename
  entries beyond architectural state keep the fixed pipelines from exhausting
  rename capacity before their first results retire.
- `RegfileSpec.Fpcc`: 16 physical entries x 4 bits for the single architectural
  FPSR condition-code group, matching the existing NZVC/X shape.

Both RATs have two rename/commit ports and rollback with the integer RATs. FP
data and FPCC carry distinct source/destination/old physical IDs in the renamed
uop and ROB commit metadata; no global current-result latch is permitted.

The internal FPCC layout is fixed as `[3:0] = {NaN, I, Z, N}`. Architectural
FPSR bits `[27:24] = {N, Z, I, NaN}` are therefore the reversed presentation of
that group. FTST and every result-producing arithmetic/move operation write the
renamed FPCC result; FCMP writes FPCC without an FP-data destination.

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

The implementation also requires protocol-level tests that the inherited
instruction corpus cannot substitute for:

- drive at least eight independent operations into each fixed lane on
  consecutive cycles, require eight consecutive accepts and results at the
  documented fixed latency, and check exact ROB/destination/FPCC association;
- prove at least four fixed operations are simultaneously in flight, with no
  duplicate or missing PRF write, wakeup, status observation, or completion;
- collide a fixed-pipeline result with FDIV/FSQRT completion and prove the
  result arbiter retains both exactly once;
- hold a request valid while result capacity is unavailable and prove stable
  payload plus exactly one acceptance when credit returns;
- flush a dense fixed pipeline and an active iterative operation, immediately
  reuse ROB and physical destinations, and prove no stale side effect survives;
  and
- mutation-check the dense-start, collision, and flush/reuse tests so a return
  to a busy-gated singleton or global flush latch fails visibly.

Simulation cannot prove DSP A/B/M/P register use. The physical gate must report
the inferred FMUL DSP count and internal register properties in addition to
functional throughput.

## 10. Open items requiring implementation-time verification (not silently assumed)

1. FPU gateway topology (§3) is resolved: share the registered CPLX selection
   gateway and ROB completion lane, keep separate FP/FPCC PRFs and dependency
   tracking, mask candidates with next-cycle lane credits, and grant held result
   tails atomically. The implementation plan must include the no-head-of-line and
   simultaneous-result tests described in §9.
2. Exact FMOVE/FMOVEM/FMOVECR EA-addressing-mode coverage — full
   parity with the existing integer MOVE EA-decode matrix, or a
   narrower subset matching only what the 48 target tests exercise?
   Recommend: narrower-but-correct first pass targeting the test
   corpus, explicitly scoped, rather than a blind full-EA-matrix port.
3. FPCR rounding-mode and exception-enable bits — real 68040 FPCR has
   both; this spec doesn't yet lock how much of FPCR's control
   semantics (vs. just FPSR's status/condition semantics) are in
   scope. Needs an explicit decision before the plan is written.
4. §5's post-instruction-PC vector-11 delivery trigger is resolved:
   recognized, fully framed FPU software-completion uses `nextPc`;
   an unknown line-F fallback uses the faulting `pc`. The implementation
   plan must still specify and test the required FPSP command/source-state
   capture before enabling the recognized path; `faultUsesNextPc` alone
   is necessary but not sufficient for a usable ROM FPSP hand-off.
5. FTST semantics are resolved: keep it as a distinct cheap micro-op that
   classifies its source directly. Directed tests must include finite values,
   signed zero, infinity, and NaN so an FCMP-vs-zero rewrite fails visibly.
6. Exact fixed-operation latencies and FIFO depths: select them from inference
   probes and collision analysis while keeping II=1 and reserving every
   unstallable result before launch. Latency is deliberately not frozen before
   the DSP and normalization pipelines are physically characterized.
