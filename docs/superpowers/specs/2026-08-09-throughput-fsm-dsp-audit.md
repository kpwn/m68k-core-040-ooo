# Throughput audit: one-at-a-time controllers, DSP pipelines, and Markdown reconciliation

**Status:** REVIEW COMPLETE; this document records findings and priorities. It
does not amend the fixed architecture by itself. Any item marked "spec update
required" must be reconciled in the owning architecture document before RTL is
changed.

**Scope:** repository-wide inventory of the 212 Markdown files visible with
hidden progress ledgers included (96 specifications, 80 plans, 30 SDD progress
files, and 6 other files), followed by RTL and routed-report checks for every
controller relevant to steady-state issue, memory, cache, translation, DSP, and
debug throughput. The tables below supersede the older 14-block throughput
inventory in `.superpowers/sdd/progress-ipc-push-2026-08-09.md`, which did not
follow the store path through its acknowledgement-gated producer and understated
the combined CPLX serialization.

## 1. Executive result

The aligned resident-load hot path is no longer one-at-a-time. It has elastic
P1/P2/P3/P4 stages, a four-entry ordered aligned-load descriptor ring, and a
four-entry tokenized VIPT-result queue. A directed eight-load burst proves
consecutive issue, DTLB/VIPT launch, descriptor enqueue, D-cache command, and
completion, with P2/P3/P4 occupied concurrently. Same-page warm L1D loads are
therefore genuinely II=1.

VIPT is real and latency-hiding: `LsEuPlugin` launches the DTLB request and the
virtual-set D-cache RAM probe from the same registered P2 token. The later
resolved command supplies the physical tag and consumes the tokenized result;
it does not repeat the RAM read on an early hit. A changed VPN can still insert a
bubble because `DtlbPlugin`'s registered hit response is associated by the live
VPN rather than a returned token. This is a DTLB response-contract limitation,
not a failure to overlap TLB and cache lookup.

The remaining material one-at-a-time behavior is concentrated in four places:

1. the ALU slow stages are physically pipelined but globally occupancy-gated;
2. the combined CPLX EU serializes fixed-latency MUL/CHK/CMP2 behind DIV;
3. the SQ producer waits for every store acknowledgement before presenting the
   next store; and
4. a D-cache demand miss owns the sole miss engine until replay, so hits cannot
   pass it.

The first three affect execution/store throughput directly. The fourth is off
the all-hit path but becomes important at realistic miss rates.

## 2. Hot and performance-relevant controllers

| Priority | controller | current initiation behavior | classification | FPGA-friendly action | difficulty and main hazards |
|---|---|---:|---|---|---|
| landed | ALU slow path (`AluEuPlugin`) | six physical stages, now II=1 with consecutive issue/completion proof; fast ops reserve the shared S1/S3 write port exactly one cycle ahead | hot when shifts/bitfields occur; completed in simulation | retain the existing registers, stored IQ class bit, fail-safe forecast, flush poison, and precise immediate/memory X dependencies | routed area/FMax still required; mixed-loop residual belongs to the separately specified fetch-directed-BTB lever |
| landed | aligned LS/L1D hit path (`LsEuPlugin`, `DcachePlugin`) | same-page resident load II=1; multiple operations in P2/P3/P4, descriptor ring, and VIPT-result queue | hottest memory path; hard, completed in simulation | retain pruned contexts, ordered untagged responses, accept-last turnover, and tokenized early results | precise fault order, store forwarding, flush poison, completion priority; routed area/FMax still required |
| P1 | changed-VPN DTLB turnaround (`DtlbPlugin`) | same VPN II=1; back-to-back different VPNs require the registered result to settle | memory hot-path edge; moderate | return a decoupled/tagged hit result to an LS slot; keep one TLB and one walker | younger hit versus older walk ordering, U/M identity, faults, permissions, PFLUSHA, exception arbitration |
| P1 | D-cache demand-miss engine (`DcachePlugin`) | one `IDLE/EVICT_WR/REFILL/REPLAY` context; no demand hit-under-demand-miss | miss path; moderate for one parked miss, hard for many | implement one parked MSHR plus hit-under-miss, with same-set/claimed-way exclusion and ordered completion; do not start with general multi-MSHR | untagged responses, dirty victim ordering, shared store RMW port, fault/flush association; the current SoC crossbar cannot exploit multiple simultaneous bus misses |
| P1 | combined CPLX/divide EU (`DivEuPlugin`) | CHK/CMP2 about II=2, integrated MUL about II=3, DIV about II=67; only one operation in the EU | MUL is potentially hot; moderate–hard | separate the iterative divider context; make CHK/CMP2 and MUL fixed-latency elastic streams; buffer divider result and arbitrate completion | current global `s1Ctx`, poison, remainder, overflow, and high-product latches assume one outstanding operation |
| P1/P2 | SQ-to-D-cache drain (`StoreQueue`, `DcachePlugin`) | producer holds one store until local cache ack or AXI B; split stores repeat serially | store-heavy hot path; moderate for copyback hits, hard for WT/MMIO | queue COPYBACK-hit drains first using send/ack pointers; keep SQ entries forwarding-visible until ack; later use one central 2–4-entry D-side write descriptor serializer | S1 read-port conflicts, WT error/B association, split phases, precise order, and proven AW/W cross-pair corruption if independent writers are loosened |
| P2 | I-cache demand fill (`IcachePlugin`) | resident hit path II=1; one demand/prefetch fill engine closes demand fetch until replay | performance-relevant miss path; moderate–hard | retain next-line prefetch as the cheap mechanism; only if measured, add a tiny ordered fetch request/response queue around one demand MSHR | `FetchRsp` is untagged and FetchAlign attributes it to the ring head; bypass needs ordering or tags |

### 2.1 ALU evidence

The existing slow-path design specification is the strongest immediately
actionable follow-up:
`docs/superpowers/specs/2026-08-09-ipc-alu-eu-slow-path-serialization-design.md`.
It already describes the completion-port collision solution and small-area
shape. The current ROM histogram attributes about 1.50% of operations to shifts
and 0.37% to bitfields.  The baseline dedicated benchmarks measured
`shift-stream` IPC 0.286 and `shift-mixed` IPC 0.646.  After enabling the
existing pipe at II=1 and removing false immediate-shift X dependencies, the
same pinned zero-latency measurements are 0.843 and 1.303; the L2-faithful
measurements are 0.765 and 1.204.  Directed tests prove six consecutive accepts
and six uniquely tagged consecutive completions, exact S1/S3 port reservation,
candidate rerouting, and dense-pipe flush/reuse.  Physical route remains the
handoff gate.

### 2.2 D-cache hit-under-miss boundary

General multi-MSHR support is not the next sensible cache step. The crossbar is
single-outstanding per master, so extra bus-side miss contexts add unconditional
area and verification cost without end-to-end concurrency. One parked miss can
still provide useful **hit-under-miss** without issuing another bus miss. The
existing `2026-07-30-mshr-multi-outstanding-design-proposal.md` reaches the same
conclusion and remains directionally correct.

### 2.3 Store-drain boundary

The D-cache store datapath already has internal S0/S1/S2 storage, but the SQ
producer is acknowledgement-gated. Declaring the cache store pipe "pipelined"
therefore overstates system throughput. The first safe scope is COPYBACK hits,
where acknowledgement is local and ordered. A dequeued entry must remain visible
to load forwarding until its cache effect is acknowledged. WT, MMIO, eviction,
and maintenance writes should converge on a central ordered descriptor/ack
serializer rather than independently relaxing AW and W FSMs. The hazards in
`2026-06-07-ls-store-drain-race-design.md` remain binding.

## 3. Controllers that should remain serial

| controller | reason to keep one-at-a-time | better FPGA strategy if measurement later justifies work |
|---|---|---|
| split LS replay | rare, inherently requires two translations/cache accesses; already isolated from aligned hits | keep `BK_IDLE/LAUNCH/WAIT_A/WAIT_B`; optimize only surrounding handoff |
| divider core | one 64-step restoring register set; a fully pipelined or replicated divider is area-heavy | consider higher radix or a 32-step W/L32 mode, not a 64-stage pipe |
| D-cache maintenance | cold, ROB/exception serialized, dependent on shared RAM and AXI ports | retime address/control if it limits FMax |
| ITLB/DTLB page walkers | three dependent descriptor reads; I and D already have independent walkers | add a tiny upper-level walk cache if measured, not another walker |
| deferred U/M drains | depth-four queues already hide the serialized commit-ordered drain | add real queue-full backpressure before considering more drain issue |
| exception entry and RTE | cold and architecturally precise; partial frame/error ordering dominates | keep held Stream commands honest; do not parallelize frame words |
| MOVEM expander | already emits up to two transfer µops/cycle, saturating normal width | remove arithmetic timing waste but retain macro sequencing |
| microcode sequencer | rows are often T0/T1-dependent and macro boundaries must remain contiguous | only redesign after measured microcode occupancy, with namespaced temporaries |
| MOVEP sequencer | 3/7/11 dependent steps by form | keep serial |
| fetch complex-instruction barrier | correctness-sensitive raw-word/resume-PC boundary | improve length predecode or add a framing queue if it becomes hot |
| reset initializers | bootstrap-only | keep serial |

## 4. DSP-backed users

The answer to "are DSP users pipelined?" is **no at the execution-system
boundary**.

| user | DSP count | core latency / II | integrated behavior | finding |
|---|---:|---:|---|---|
| integer `MulCore` | 4 | nominal 1 / 1 | `DivEuPlugin` makes issue about II=3 and single-outstanding | only one output register; DSP A/B/M registers are unused |
| divider | 0 | about 66 / 67 | single iterative context | keep iterative unless a measured workload justifies a different algorithm |
| MOVEM decode arithmetic | 2 before strength reduction | combinational | not a queue | shift/mux/negate replacement implemented and simulation-gated; synthesized DSP/FMax confirmation pending |
| FPU | not implemented | draft only | draft is explicitly busy-gated/single-outstanding | amend before implementation: fixed-latency FADD/FMUL should be elastic II=1 |

All six synthesized DSP48s are accounted for: four in the integer multiplier and
two in MOVEM decode. There are no arithmetic blackboxes.

### 4.1 Integer multiply architecture mismatch

The binding architecture document requires a roughly 3–4-cycle, fully
pipelined fixed-latency MUL under the integer cluster
(`2026-05-31-m68k-040-ooo-architecture-design.md`, execute sections). The live
implementation instead puts a one-register `MulCore` behind the dynamic CPLX
completion FSM. The later `2026-06-06-multiply-design.md` selected that CPLX
integration without clearly amending the binding architecture. This is a real
spec/implementation inconsistency and must be reconciled before MUL RTL changes.

Vivado reports only one multiplier pipeline register where four are recommended;
the DSP properties have `AREG=BREG=MREG=0`, with only cascade outputs using
`PREG=1`. A correct MUL conversion therefore needs:

- a roughly four-stage DSP A/B/M/P pipeline at II=1;
- a pruned per-operation descriptor/epoch pipe, not a full `IqContext` shift;
- per-operation flush poison rather than the current global latch;
- completion buffering/arbitration across zero-, fixed-, and iterative-latency
  results; and
- a ROB-keyed high-product stash for cracked `MULHI`, replacing the global
  `mulHiLatch` assumption.

Removing `busy` alone is incorrect. Required tests are consecutive-cycle MUL
bursts, mixed CHK/CMP2/MUL/DIV completion collisions, multi-flight flush, and
overlapping `.L64` crack sequences. `MulCoreSpec` currently drives one operation
at a time and cannot prove this contract.

### 4.2 MOVEM strength reduction

`DecodeStage` formerly computed `emitted * step` and `step * numThisCycle` even
though `step` is only ±2/±4, `emitted` is 0–16, and the per-cycle count is 1/2.
Synthesis mapped this to the other two DSP48E2s. The routed endpoint was a
measured 4.094 ns, 12-level path with WNS -0.112 ns at 250 MHz. The RTL now uses
a widened count left shift plus optional negate for the final delta, and selects
between `step` and `step << 1` for the running update. No latency or state was
added. Directed decode covers every offset and both ±64 final deltas for all 16
registers; 11/11 decode and 8/8 MOVEM lock-step tests pass. The phase-local
`test-fast` result was 133/134 with only the stale `PredecodeRefSpec` line-B
expectation; the combined branch passes 138/138 after the reference corrections.
Confirmation that both DSPs disappear and the endpoint improves awaits the
shared Vivado window.

### 4.3 FPU draft

`2026-08-09-fpu-hardware-design.md` currently proposes a shared busy-gated,
non-pipelined unit while also estimating a 16-DSP FMUL. That contradicts the
throughput direction before any RTL exists. Revise the draft so fixed-latency
FADD/FSUB/FMUL are elastic II=1 and use internal DSP registers. FDIV/FSQRT may
remain iterative and separately buffered.

## 5. Test honesty requirements

The LSU work exposed three examples of tests adapting to an old slow consumer
rather than checking the architectural contract:

- standalone `LsEuSpec` never enabled a `CacheControlService`, so its alleged
  warm-cache hit was actually cache-inhibited;
- precise stores were incorrectly expected to complete at SQ allocation rather
  than at retirement/acknowledgement; and
- an RTE producer used a one-cycle `RegNext(valid)` pulse and duplicated its tail
  command when the consumer became II=1.

The repaired tests now enable the D-cache for the warm-hit case, distinguish SQ
allocation from precise completion, wait for forwarding-visible store residency,
sample Stream handshakes before the edge, and assert exact command counts. New
throughput tests must prove consecutive `fire` cycles, simultaneous occupancy,
association/order, backpressure, flush, and collision behavior. A latency window
alone is insufficient because it can pass vacuously without demonstrating that
multiple operations were ever resident.

The ALU phase uncovered two more stale assertions: ADDA was checked with the
pre-fix source/destination order, and legal microcoded CMPM was still expected
to decode as illegal. The repaired tests now assert the binding
`srcA=destination, srcB=source` contract and the exact `CMPM_ENTRY` association,
including that the normal operand descriptors are unused. This is the preferred
failure outcome: a broad gate exposed false expectations instead of driving RTL
back toward them.

Current gate evidence for the LSU change:

- D-cache focused suite: 49/49;
- focused LS suites: 24/24;
- RTE regression: 1/1;
- phase-local `test-fast`: 133/134 with only the independently reproduced stale
  line-B oracle; combined-branch `test-fast`: 138/138 after the line-B and
  subsequently exposed line-0 oracle corrections; and
- seed-1 IPC: `load-stream` 638 cycles ideal and 769 L2-faithful, versus C3
  1829/2030. Excluding `load-stream`, aggregate cycles improve slightly rather
  than regress.

Spinal also emits an initial elaboration failure for three undriven
`InterruptControlPlugin` fixture registers (`iplIn`, `iackAvec`, `iackVector`),
then restarts and the interrupt tests pass. That should be repaired as fixture
debt; suppressing or ignoring it would make the gate less trustworthy.

## 6. Markdown reconciliation

| document family | review result | action |
|---|---|---|
| binding core architecture | fully pipelined MUL requirement conflicts with live CPLX implementation | ratify one architecture before MUL RTL work |
| LS pipeline specs/plans | newest full-pipeline spec is correct and now carries measured D1/D2 results; older late-split documents are historical checkpoints | treat `2026-08-09-ipc-ls-eu-full-pipeline-design.md` as current |
| ALU slow-path spec | implemented and simulation-gated at II=1 | run the paired routed FMax/LUT and IQ-endpoint census before final acceptance |
| MSHR proposal | correctly prioritizes D-side hit-under-miss and warns about crossbar limits | implement one parked miss before general MSHRs |
| store-drain/race documents | correctly require ordered AW/W and acknowledgement ownership | use a descriptor/ack queue, never independent loose FSMs |
| old FMax retiming documents | valid for timing changes but some explicitly preserve single-outstanding behavior | do not read a retiming non-goal as a throughput endorsement |
| FPU draft | busy-gated fixed-latency operations conflict with the new throughput requirement | amend before RTL |
| debug/JTAG | new core had no debug controller; sibling SoC contract was the only live compatibility definition | use the new JTAG-compatible debug-controller addendum |

## 7. Ordered implementation recommendation

1. Run the paired routed FMax/LUT gate for the landed LSU/VIPT pipeline when the
   serialized Vivado window is free. Do not infer closure from simulation.
2. Verify the landed MOVEM strength reduction removes both decode DSPs and the
   measured timing failure in the paired routed gate.
3. Run the paired routed FMax/LUT and IQ-endpoint census for the landed ALU II=1
   pipeline; retain it only if the issue-select cone stays under control.
4. Reconcile the binding MUL architecture, then implement the four-stage II=1
   DSP pipeline and completion protocol.
5. Measure D-cache miss occupancy, SQ-full/drain stalls, CPLX mix, and changed-VPN
   DTLB bubbles on representative workloads.
6. Choose among one-MSHR D-cache hit-under-miss, COPYBACK store-drain queuing,
   and tagged changed-VPN DTLB response based on those counters. These are
   independent levers and should remain separately revertible.
7. Consider a 32-step W/L32 divider only after the higher-value fixed-latency
   paths are complete.

Area/FMax discipline is uniform across these phases: use existing DSP internal
registers, pruned descriptors, shallow FIFOs, and one parked context before
replication; avoid new IQ/ROB ports until a measured bottleneck requires them;
and require post-route evidence for every structural throughput change.
