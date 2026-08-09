# Throughput audit: one-at-a-time controllers, DSP pipelines, and Markdown reconciliation

**Status:** REVIEW COMPLETE; the aligned LSU/VIPT, slow-ALU, MOVEM arithmetic,
fetch-ring turnover, and fixed-latency CPLX MUL recommendations have landed and
passed their simulation gates.  Their shared post-route FMax/area acceptance is
still pending.  This document records findings and priorities; it does not amend
the fixed architecture by itself. Any item marked "spec update required" must be
reconciled in the owning architecture document before RTL is changed.

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

1. changed-VPN DTLB hits cannot turn over every cycle because the registered
   response is associated with the live VPN rather than a returned token;
2. the CPLX legacy lane retains serial CHK/CMP2 and one iterative divider context,
   even though fixed-latency MUL is now independent and II=1;
3. the SQ producer waits for every store acknowledgement before presenting the
   next store; and
4. a D-cache demand miss owns the sole miss engine until replay, so hits cannot
   pass it.

The latter two affect store/miss-heavy workloads. The first is an all-hit-path
edge when adjacent accesses change pages; the second primarily leaves DIV and
the much colder bound-check operations serialized.

## 2. Hot and performance-relevant controllers

| Priority | controller | current initiation behavior | classification | FPGA-friendly action | difficulty and main hazards |
|---|---|---:|---|---|---|
| landed | ALU slow path (`AluEuPlugin`) | six physical stages, now II=1 with consecutive issue/completion proof; fast ops reserve the shared S1/S3 write port exactly one cycle ahead | hot when shifts/bitfields occur; completed in simulation | retain the existing registers, stored IQ class bit, fail-safe forecast, flush poison, and precise immediate/memory X dependencies | routed area/FMax still required; mixed-loop residual belongs to the separately specified fetch-directed-BTB lever |
| landed | aligned LS/L1D hit path (`LsEuPlugin`, `DcachePlugin`) | same-page resident load II=1; multiple operations in P2/P3/P4, descriptor ring, and VIPT-result queue | hottest memory path; hard, completed in simulation | retain pruned contexts, ordered untagged responses, accept-last turnover, and tokenized early results | precise fault order, store forwarding, flush poison, completion priority; routed area/FMax still required |
| P1 | changed-VPN DTLB turnaround (`DtlbPlugin`) | same VPN II=1; back-to-back different VPNs require the registered result to settle | memory hot-path edge; moderate | return a decoupled/tagged hit result to an LS slot; keep one TLB and one walker | younger hit versus older walk ordering, U/M identity, faults, permissions, PFLUSHA, exception arbitration |
| P1 | D-cache demand-miss engine (`DcachePlugin`) | one `IDLE/EVICT_WR/REFILL/REPLAY` context; no demand hit-under-demand-miss | miss path; moderate for one parked miss, hard for many | implement one parked MSHR plus hit-under-miss, with same-set/claimed-way exclusion and ordered completion; do not start with general multi-MSHR | untagged responses, dirty victim ordering, shared store RMW port, fault/flush association; the current SoC crossbar cannot exploit multiple simultaneous bus misses |
| landed | fixed-latency CPLX MUL (`MulCore`, `DivEuPlugin`) | seven-stage datapath, II=1 integrated issue and completion; MUL remains live while DIV iterates | potentially hot; completed in simulation at the former latency | retain pruned descriptor pipe, reserved result credits, one existing completion port, pending MULHI tails, and ROB-keyed high halves | seven-stage DSP-register reshape and routed area/FMax acceptance remain |
| P2 | legacy CPLX/divide lane (`DivEuPlugin`) | CHK/CMP2 about II=2; one DIV context about II=67; a parked second DIV can still block a younger MUL at the registered issue port | mostly cold/iterative; moderate | keep one divider; measure before adding a pending-DIV slot or IQ eligibility forecast; consider 32-step W/L32 iteration only if DIV matters | forecast must reserve the registered issue slot; global remainder/overflow association must be replaced before allowing multiple DIV families in flight |
| P1/P2 | SQ-to-D-cache drain (`StoreQueue`, `DcachePlugin`) | producer holds one store until local cache ack or AXI B; split stores repeat serially | store-heavy hot path; moderate for copyback hits, hard for WT/MMIO | queue COPYBACK-hit drains first using send/ack pointers; keep SQ entries forwarding-visible until ack; later use one central 2–4-entry D-side write descriptor serializer | S1 read-port conflicts, WT error/B association, split phases, precise order, and proven AW/W cross-pair corruption if independent writers are loosened |
| landed/P2 | L1I/frontend and demand fill (`IcachePlugin`, `FetchAlignPlugin`) | resident hit path is latency 3 / II=1 and its fetch ring now turns over at full occupancy; one demand/prefetch fill engine still closes demand fetch until replay | all-hit path completed in simulation; miss path remains moderate–hard | retain the staged TLB/cache path for FMax and the full-ring consume/replace credit; only if measured, add a tiny ordered fetch request/response queue around one demand MSHR | redirect first-use remains N+5 without fetch-directed prediction; `FetchRsp` is untagged, so miss bypass needs ordering or tags |

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

### 2.4 L1I/frontend latency coverage

The resident L1I is VIPT-safe but intentionally does not put live ITLB lookup
and tag/data lookup in one combinational cycle: virtual `pc[11:6]` selects one
of 64 sets and the physical page number supplies the tag, while registered T and
S1 stages protect FMax.  A command accepted at N returns at N+3 and the frontend
can use it at N+4.  The depth-three tagged fetch ring now grants a same-cycle
consume/replace credit even when initially full, so that latency disappears from
the sequential steady state.

The real MMU-on integration test warms ITLB and L1I, then observes eight
consecutive useful two-wide groups at four instruction words per cycle with
exact PCs, opwords, and extensions and no cache or page-walker AXI traffic.  It
also redirects a full ring and proves stale traffic is discarded and the target
stream restarts in order.  Restoring the old `!ringFull` admission makes the test
show repeated useful-feed bubbles.  The remaining clean-redirect cost is target
command N+1 to first useful group N+5; collapsing T/S1 would rebuild a measured
route-dominated TLB-to-cache cone, so fetch-directed prediction is the safer
FPGA lever.

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

The integer DSP user is now pipelined at the execution-system boundary in RTL
and directed simulation.  Physical confirmation that Vivado uses the intended
DSP48E2 internal registers remains open.

| user | DSP count | core latency / II | integrated behavior | finding |
|---|---:|---:|---|---|
| integer `MulCore` | 4 expected | 7 / 1 | integrated `DivEuPlugin` accepts and completes dense MUL at II=1, including while DIV is active | seven-stage reshape selected after physical mapping proved the four-cycle form left every MREG unused |
| divider | 0 | about 66 / 67 | single iterative context | keep iterative unless a measured workload justifies a different algorithm |
| MOVEM decode arithmetic | 2 before strength reduction | combinational | not a queue | shift/mux/negate replacement implemented and simulation-gated; synthesized DSP/FMax confirmation pending |
| FPU | not implemented | draft only | draft is explicitly busy-gated/single-outstanding | amend before implementation: fixed-latency FADD/FMUL should be elastic II=1 |

All six synthesized DSP48s are accounted for: four in the integer multiplier and
two in MOVEM decode. There are no arithmetic blackboxes.

### 4.1 Integer multiply reconciliation and landing

The former architecture mismatch is resolved in the binding architecture and
the owning `2026-06-06-multiply-design.md`: logically integer MUL uses the
existing shared CPLX operand/writeback gateway so no extra IQ, PRF, or ROB port
is added.  The implementation now has:

- a seven-stage `MulCore` pipeline at II=1: two operand levels, registered
  multiply, and four post-multiply product levels;
- a pruned per-operation descriptor pipe rather than a full `IqContext` shift;
- flush clearing for every fixed-pipeline valid, result credit, pending tail, and
  ROB-keyed high-product entry;
- a credit-reserved depth-eight result FIFO and atomic arbitration with held
  legacy/DIV results through the existing one-result Flow port; and
- a ROB-keyed high-product stash plus pending-tail FIFO for cracked `MULHI`,
  including main ROB 63 to tail ROB 0 wrap.

The tests are deliberately protocol-level rather than a latency-window claim.
`MulCoreSpec` drives eight starts on eight consecutive cycles.  The integrated
test accepts twelve consecutive MULs, checks twelve consecutive exactly-once
completions and both wakeup domains, overlaps two `.L64` pairs, observes a real
simultaneous DIV/MUL arbiter collision, and immediately reuses ROB/physical
destinations after a dense flush.  `IqCplxSpec` independently proves a consumer
with two outstanding CPLX sources remains blocked after the first wake and is
released only by the second.  Restoring the old single-outstanding EU fails the
integrated test on its second consecutive request.

The first current-branch physical checkpoint found the source-level four-stage
shape was still not an effective wide-DSP pipeline. All four slices had
`AREG=BREG=2` and `MREG=0`; only the two accumulation slices had `PREG=1`.
Vivado reported one post-multiply pipeline register and recommended four. An
isolated seven-stage inference probe with unconditional invalid-cycle data
shifting maps all four slices to `AREG=BREG=2`, `MREG=PREG=1`, keeps the DSP
count at four, reduces total LUTs from 48 to 18, and adds 66 FF. The full-core
mapping and endpoint result remain the open acceptance items.

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

The multiplier phase applied the same rule to pre-existing CPLX fixtures.
`DivWSpec` and `ChkSpec` formerly pulsed `valid` without observing `ready`; they
now hold a transaction until handshake.  The CHK fixture now drives the real
NZVC-write contract and distinguishes architectural fault-flag observation from
renamed-destination wakeup.  DIV overflow now checks the required old-Dn
write-through into the freshly renamed physical destination instead of expecting
no write and silently blessing a scoreboard deadlock.

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

Current integrated MUL landing evidence:

- focused `MulCore` + integrated CPLX + DIV/CHK + IQ dependency cluster: 15/15;
- the integrated test's legacy single-outstanding negative control fails on the
  second consecutive MUL request;
- full-core synthesis-top elaboration passes; and
- mandatory combined-branch `test-fast`: 138/138 across 144 suites.

Spinal also emits an initial elaboration failure for three undriven
`InterruptControlPlugin` fixture registers (`iplIn`, `iackAvec`, `iackVector`),
then restarts and the interrupt tests pass. That should be repaired as fixture
debt; suppressing or ignoring it would make the gate less trustworthy.

## 6. Markdown reconciliation

| document family | review result | action |
|---|---|---|
| binding core architecture | reconciled: seven-stage II=1 integer MUL uses the existing shared CPLX gateway and ports | full-core physical mapping/FMax/area acceptance remains open |
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
4. Run the paired routed DSP-register/FMax/LUT/FF gate for the landed seven-stage
   II=1 MUL pipeline and completion protocol.
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
