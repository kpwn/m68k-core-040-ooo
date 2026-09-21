# IPC at 200 MHz: design alternatives

Status: proposal evaluation with individually identified prototype results below;
the list as a whole is not an implementation or a measured speedup.
Initial RTL audit at `86273cde`, updated through `982e0b3d`; measurements are recorded in
[the experiment ledger](ipc-experiments.md). These proposals do not change the
current architecture contract. Each selected implementation needs its own spec
amendment and correctness tests first.

## Selection rules

1. Prefer removing mechanisms, dependencies and duplicated state to adding them.
   Count control states, recovery cases, ports and fanout, not just source lines.
2. Increase useful macro IPC and throughput, reducing stalls, bubbles and
   mispredictions, with a final routed **200 MHz** implementation. A lower-clock
   diagnostic build is not the finished result.

Resource constraint: BRAM is relatively plentiful; LUT/FF fabric and routing
are not. Prefer replacing wide muxes and register arrays with banked BRAM-backed
storage and narrow registered control, where access scheduling preserves useful
latency and throughput. BRAM capacity does not supply arbitrary read/write ports.
Charge replication, collision bypass and added access cycles to each proposal.

A first timing failure is a development result, not automatic rejection of the
idea. Preserve the IPC-positive version, identify the actual critical cone, and
try a specific repair: early metadata, banking, narrow control, buffered events,
or pipeline cuts that leave the consumer's required-data cycle unchanged.
Re-measure IPC after every cut. If it adds a recurrence cycle, report that loss;
do not assume pipelining is free. Never relax functional timing constraints to
claim a win. Core out-of-context timing is a screening gate, not SoC signoff.

At fixed 200 MHz, useful instruction throughput is `IPC * 200 million/s`.
We prefer the simpler design when workload gains are comparable. A complex
design remains worth pursuing if it delivers a substantial measured advantage
that the simpler alternatives cannot provide. No arbitrary percentage threshold
or paper's speedup is treated as a prediction for this core.

## What the current RTL actually limits

- `Global.scala`: 32 ROB entries. Dispatch and ordinary retirement are two-wide
  in micro-ops, not necessarily two architectural instructions per cycle.
- `RobPlugin.scala`: `retire1` rejects a branch in either slot. All branches
  currently retire alone, including correct predictions. Exceptions, trace,
  debug stops and precise-store boundaries add distinct eligibility conditions.
- `RenameStage.scala`, `Isa.scala` and `RatTable.scala`: five renamed classes
  (integer, NZVC, X, FP, FPCC), with two commit lanes. The integer map is 20 by 6 bits
  including four internal temporaries; older comments saying 18 are stale.
  FP is 8 by 4, with three 4-bit flag mappings. Thus one complete mapping image
  is only **164 raw bits**, excluding valid/version/control metadata. The hard
  part is ownership and recovery, not copying PRF values.
- `Freelist.scala`: architectural allocation accounting advances at commit;
  two old-register IDs per class are reclaimed through a one-cycle stage.
  This is not an elastic queue capable of accepting arbitrary bulk commits.
- `StoreQueue.scala`: two ROB-ID commit notices; speculative entries forward,
  committed entries drain later. Precise/device stores have separate handling.
- `RobPlugin.scala`: branch training and architectural CCR/PC/debug observations
  also depend on the existing retirement convention. Merely changing the head
  increment to eight would lose updates.
- `IssueQueuePlugin.scala`: LS selects the oldest occupied LS entry, not the
  oldest dependency-safe ready entry. The new memory-dependency tracker remains
  standalone. Cacheable stores already execute speculatively into SQ.

With two allocations and two retirements per cycle, occupancy stays constant:
two-wide retire cannot reduce an existing backlog during sustained full-rate
dispatch. Wider/bulk retire provides catch-up bandwidth, not a steady-state
throughput exceeding dispatch. Conversely, a nonempty ROB with no retirement
does not prove retire bandwidth is the bottleneck: the head may simply be waiting
for a load, dependency, fault handling or another execution result.

## Concrete proposals

Priority is an initial engineering judgement, not a measured ranking. Options
1–5 are alternative retirement implementations; do not stack all of them.

### 1. Four-wide ordinary retirement, hot instructions only

Retire up to four contiguous ready, safe entries; retain the current cold path
for complex/system/precise operations. This is the simplest performance control
experiment for the two-wide backlog hypothesis. It changes more than the ROB:
map updates, reclamation, SQ authorization and observations must accept the burst.

**Cost / timing repair:** more head reads and last-writer selection. Bank payload
reads and buffer retirement side effects; compare against option 2 before adding
eight-wide ports everywhere. Preserve every committed event under backpressure.
**Experiment:** 2/4-wide matched runs, completed-prefix histogram, actual
dispatch-blocked cycles saved, area and routed paths. **Priority: high as a control.**

The actual four-wide control now runs in the full-core simulation, with ordered
map/free, SQ and owned U/M authorization, CCR/PC/system/FP updates and lossless
observations. See [the integration contract](retirement-bandwidth.md) and
[matched results](ipc-experiments.md#four-wide-retirement-full-core-control--2026-09-21).
Production remains two-wide. With the improved LSU/predictor, the long backlog
window retires four entries on 336 cycles but takes the same 2,240 cycles as the
two-wide control. Short backlog improves 380→376 cycles and call/return improves
1,174→1,173; the other windows are unchanged. Without the predictor improvements,
the long backlog regresses badly with increased misses. **Lower priority as an
optimization; retain as the measured control for prepared retirement.** There is
no four-wide routed timing or board result.

### 2. Pipelined retirement lookahead and eligibility certificates

Read and classify the next 4–8 entries ahead of consumption, retaining prepared
metadata. Check live completion/fault status and urgent stop conditions at final
publication. This removes repeated cold payload decoding from the final decision;
it does not permit ignoring a late fault or interrupt boundary.

**Cost / timing repair:** a small metadata queue plus head/allocation identities.
Use local registered group summaries instead of a long global ready-prefix chain.
Flush, reuse and head movement invalidate stale certificates. Avoid introducing
one bubble per certificate or demanding that a whole group finish.
**Experiment:** compare with option 1 at identical width; latency, sustainable
retirement rate and stale-certificate fault tests. **Priority: high if 1 is timing-limited.**

### 3. Prepared shadow-map bulk retirement — the requested side buffer

Walk forward while head is blocked, folding destinations in program order into
one shadow committed map. Record the batch's endpoint, unresolved obligations,
final architectural state and buffered side effects. Once its entire contiguous
prefix is safe, publish the prepared image and advance ROB head by the batch size.
An older late completion must never overwrite a younger destination mapping.

**Cost / timing repair:** narrow two-lane preparation plus a registered publication
decision, rather than many same-cycle committed-map write ports. Values remain in
PRFs. The map is small; accounting for frees, stores, CCR values and boundaries is
the real work. Map identity alone does not update the ROB's committed CCR value.
**Experiment:** 4/8/16-entry caps; compare actual IPC against four-wide retirement,
including short stalls where preparation cannot get ahead. **Priority: high.**

The default-off 4/8/16 prototype is now implemented and measured with an actual
shadow-map image. [Results and contract](ipc-experiments.md#prepared-shadow-map-retirement--2026-09-21)
show real batches but no gain on the composed long-backlog window; the best short
window improves 380→375 cycles. Even a synthetic divider/ROB-pressure window saves
only 1/3/7 cycles across the three caps, with unchanged ROB-full cycles. Retain the
experiment, but **lower its implementation priority behind dependency latency and
prediction** until a representative recurring bottleneck benefits. No timing,
area, SoC or board acceptance is implied.

### 4. Rename-time epoch snapshots

Capture the map at chosen dispatch boundaries instead of walking ROB payload
again. A completed, fault-free epoch can publish that exact map. Boundaries must
include both rename lanes' updates and exclude younger dispatch. All state outside
the rename map still needs a boundary record.

**Cost / timing repair:** potentially deletes the preparation reader, but adds
snapshot capture and epoch completion tracking. A versioned or banked map can
keep copying out of the rename critical path. Two banks alone may be insufficient
when committed, preparing and newly renaming versions overlap.
**Experiment:** fixed 4/8-entry epochs versus option 3; test a younger miss delaying
an otherwise-ready older prefix. **Priority: medium, alternative to 3.**

### 5. Committed map overlay with lazy materialization

Publish a bounded committed delta and a retirement boundary, then merge that
delta into the base committed map over subsequent cycles. Recovery and every
architectural reader see overlay-over-base, never the stale base alone.

**Cost / timing repair:** trades wide map write logic for bounded lookup and merge
control. Overwriting-register deltas may coalesce; register-free records may not
be silently dropped with them. Limit overlay depth and backpressure publication
before exhaustion. A multi-layer associative overlay could cost more than the
164-bit image it replaces.
**Experiment:** one/two overlay slots against option 3, with immediate post-commit
exceptions and A7/CCR reads. **Priority: low unless it actually removes more logic.**

### 6. Stop forcing correctly predicted branches to retire alone

Start narrowly: a resolved, correctly predicted branch in slot 0 may retire with
one eligible non-branch successor. Keep at most one branch-training event per
cycle, and retain single retirement for mispredict, trace, debug and exceptional
boundaries. Slot-1 branches are a separate extension, not a prerequisite.

**Cost / timing repair:** reuses existing width; removes a hot serialization rule
without adding a second training port. Audit next-PC, T0 trace and single-step
assumptions explicitly. Precompute branch eligibility if its resolution flags
lengthen the retirement gate.
**Experiment:** hot loops and branch-heavy code, count otherwise-eligible pairs
lost to `retireAlone`, then matched IPC. **Priority: parked after the first experiment.**
The optional `pairCorrectBranch` prototype successfully pairs 168 branches in the
call/return window but saves zero cycles. Seven kernels, two seeds, including
longer warmed branch/backlog windows, show at most a one-cycle window difference
in either direction. See `ipc-experiments.md`; structural pairing opportunities
are not equivalent to throughput gains. Default remains off, with no timing run
queued for this version.

Re-tested after composing the LSU and retained-history improvements: call/return
is faster (1342 to 1174 cycles), but pairing still saves no cycles. It increases
head-incomplete observations from 503 to 587 by exposing the next blocked head
earlier. Long backlog is 2240 cycles without pairing and 2241 with it. Keep this
option parked; neither stall counts nor pair counts establish useful throughput.

### 7. Compact retirement records for selected cracked macros

Keep execution micro-ops where needed, but represent a hot multi-uop instruction
with one retirement descriptor, a completion mask and its final destination set.
This can reduce ROB occupancy and retirement traffic without raising macro decode
width. Start with a measured common pattern, not all microcode.

**Cost / timing repair:** fewer duplicated PC/control fields versus more completion
and destination metadata per entry. Preserve required partial-fault/restart
behavior; “same macro” is not permission to hide all intermediate effects.
Bank final-destination data rather than create a large combinational gather.
**Experiment:** dynamic uops/macros and ROB bytes saved, plus fault at every
micro-op boundary. **Priority: medium if hot cracking consumes meaningful capacity.**

#### Controlled experiment: direct longword MOVE loads

Default-off `fuseLongMoveLoads` replaces the ordinary non-auto-update
`MOVE.L <memory>,Dn` / `MOVEA.L <memory>,An` load-to-temporary plus ALU-copy
crack with one LS micro-op. Keep the existing load EA, displacement, index,
translation/fault handling and memory ordering. Its integer destination and
NZVC-write declaration come from the original final MOVE micro-op. MOVEA keeps
NZVC unchanged; neither form writes X. The existing LSU completion paths already
produce full-width data and sized MOVE flags, including SQ forwards and split
loads, so this experiment adds no execution port or speculative state.

The one load is both first and last of the macro, carries the original PC/length,
and publishes only after successful completion. A fault must leave the old
integer and flag mappings architectural, with the same exception PC and frame.
Byte/word partial-register merges, source auto-update, memory-to-memory MOVE,
RMW, privileged/system, microcoded and non-MOVE operations keep their cracks.
Decoder admission still takes priority over this optimization. Validate decode
eligibility/exclusions, every LSU completion route, immediate dependent integer
and condition-code consumers, destination/base/index aliasing, line/page splits,
fault/IRQ/trace/debug recovery, and tag wrap. Compare useful macro IPC, not uop
throughput; fewer uops are not themselves evidence of improved performance.

This is an experiment under proposal 7, not wider or out-of-order retirement,
and is independent of the pending memory-dependency/SQ-reservation work.

### 8. BOOM-style point-of-no-return frontier

Track the oldest operation that can still redirect or fault. Past that safety
frontier, selected actions may no longer need to wait for ordinary result-order
retirement. This is not blanket permission for early device access or architectural
visibility: memory ordering, precise stops and unresolved older results still matter.

**Cost / timing repair:** useful if one monotonic safety frontier replaces several
distributed ROB-head checks. Otherwise it adds a scanner without removing the
real bottleneck. Pipeline summaries and carry stable identities.
**Experiment:** enumerate each current head-only consumer and its actual safety
requirements; measure newly permitted work. **Priority: medium, enabling mechanism.**
BOOM itself retains in-order architectural commit; see the primary reference below.

### 9. Compact completion buffer / early ROB-slot recycling

Move completed younger operations into a smaller retirement record structure,
freeing large ROB storage before an older stall resolves. Keep their architectural
effects speculative. This creates effective window capacity while head is blocked,
unlike option 3 which initially keeps ROB entries until publication.

**Cost / timing repair:** a second structure is worthwhile only if it replaces
expensive state, not duplicates it. Every outstanding response, SQ entry, branch
event and recovery reference must survive original ROB-slot reuse through stable
identities. Bank narrow records; do not add a global associative search.
**Experiment:** equal-area comparison with a larger ordinary ROB; separate ROB,
PRF, SQ and IQ pressure. **Priority: low until ROB capacity is proven limiting.**

### 10. Earlier physical-register reclamation

Release an overwritten physical value when no live consumer, store-data capture,
architectural map or recovery checkpoint can still need it. This addresses PRF
exhaustion behind head stalls, not architectural publication width itself.

**Cost / timing repair:** reference/liveness tracking is harder than the current
two-lane delayed free. Begin with narrowly provable cases; do not free simply
because the producer completed or a younger writer exists. Bank counters or use
allocation/source-lifetime metadata away from wake/select.
**Experiment:** identify which register class actually stalls rename, compare
against a modest capacity increase, stress delayed consumers and recovery.
**Priority: low without measured PRF pressure.**

### 11. Checkpoint-centric retirement replacing the conventional ROB

Use checkpoint epochs, completion obligations and recoverable speculative state
to release execution records out of order, publishing completed prefixes in bulk.
This is the larger architectural alternative to the bounded side buffer. A true
out-of-order resource-retirement scheme is possible; exposing arbitrary younger
architectural effects is not made safe merely by having a second map.

**Cost / timing repair:** potentially removes a centralized ROB, but redistributes
its fault, ordering and recovery duties. Evaluate as a replacement, not a second
full machine beside the existing one. Start with a functional model and limit
study, then small banked epoch logic. **Experiment:** total state/ports removed,
precise-fault replay, and equal-area IPC versus 3/9. **Priority: research, not dismissed.**
CPR is a research precedent; its reported gains are not estimates for this FPGA.

### 12. Conservative memory-dependency scheduling

Replace oldest-occupied-LS scheduling with permission based on older memory
dependencies, allowing ready disjoint loads past data-waiting stores. Reserve
records at dispatch and separate store-address readiness from store-data readiness.
Use conservative page-offset rejection only where non-overlap is provable; still
check translated attributes and full overlap where required.

**Cost / timing repair:** adds tracking/retry capacity but can remove unnecessary
global serialization. Unknown older addresses remain blocking initially, avoiding
memory-violation rollback. Register permission outside IQ select; an unsuccessful
load must release the LSU front so the older store can progress.
**Experiment:** delayed-store/disjoint-load, overlap, aliases and cold barriers;
full-core IPC after integration, not just checker tests. **Priority: high.**

Intermediate result before direct-load fusion: default-off early store address execution, still without
load bypass, passes 128 matched full-core cases. Divide-fed and rotate-fed
store/load recurrences improve 4.34–6.21%; the load-fed recurrence is unchanged
and never takes late capture. There are no measured cycle regressions. It reuses
the existing PRF read port and P3 data fields. The subsequent default-off detached
owner experiment permits actual younger-disjoint-load bypass after translation
and SQ reservation, without a duplicate address table. All 136 matched windows
retain macro counts: 28 improve, 108 are identical, none newly regress. The new
disjoint-load recurrence gains 13.99% with both older load optimizations. This
does not permit bypass past an unknown address or integrate the general dispatch
tracker/retry mechanism. See the experiment ledger for gates and pending routing,
not a board-speedup claim.

### 13. Reserved SQ slots and guaranteed early store-data wake

Reserve SQ capacity before publication; wake a dependent load early only when
correct address/data publication by its lookup edge is guaranteed. Separate SQ
payload publication from shared completion-port arbitration, or reserve both.
This can remove avoidable handshake cycles without predictive memory rollback.

**Cost / timing repair:** prefer one reservation/publication owner over duplicated
waiting state. Slots need stale-update protection and reserved-capacity accounting.
Address-verified PRF-tag forwarding is a variant to test against ordinary SQ data,
not automatically better: extra PRF ports and source lifetime may outweigh a mux.
**Experiment:** one-chain latency and multi-chain throughput, saturation/flush/
arbitration assertions, matched routing. **Priority: high; directly requested.**

Intermediate implementation: P3-owned late-data SQ reservation writes the data
directly on its existing PRF capture edge, instead of staging and allocating one
cycle later. With direct-load fusion plus early store address as the baseline,
128 matched cases produce 32 improvements and 96 identical results, with no new
regressions. Load-fed recurrence gains another 17.00–17.05%; rotate-fed recurrence
11.21–11.24%. Full-capacity fallback and redirect cancellation are observed and
checked against the oracle. The source tag/P3 owner is canceled synchronously;
no asynchronous response uses the bare SQ slot.

The follow-on detached-owner option moves only source/completion metadata out of
P3; addresses and data remain in the existing SQ. Publication can proceed even
when completion loses arbitration, and younger disjoint loads actually bypass.
It has one independent pending owner and reuses the existing PRF port. **No
guaranteed advance memory wake is emitted yet.** The next optional experiment
forwards on actual SQ publication: initial 136 matched cases yield 24 improvements,
112 unchanged, none worse; load-fed recurrence gains another 19.93–20.00% with
both older load optimizations. It also exposed a pre-existing ROB-generation
winner-selection bug, now fixed with existing SQ FIFO position rather than
wrapping ROB distance. The repaired disabled arm reproduces all 136 earlier
cycles exactly; the final candidate also reproduces all its pre-repair counts.
The 65-test combined-option oracle run, default-generation control, six subword
composition controls, 42 SQ/split tests and 384-test fast gate pass. Routing remains
pending. Details and the remaining advance-wakeup/capacity/retry work are in the
experiment ledger.

Transferring source-readiness qualification on owner admission was also tested:
all 136 windows retain exactly the same macro/cycle counts, so the functional
shortcut was removed. Ready-admission and overlapping owner/P4/full-SQ counters
remain. In the load-fed recurrence every owner-wait cycle overlaps a forwarding
stall; owner occupancy alone is not evidence that more slots would help.

The subsequent bounded-capacity experiment queues only source/completion records
behind that existing owner, still using one readiness query and PRF read port.
Address issue remains ordered and SQ ownership is unchanged. The first 48 matched
targeted runs compare one/two/four owners: each larger capacity improves 12 windows
and leaves four unchanged. Two-store/disjoint-load recurrence improves 15.01%;
four-store/disjoint-load improves 23.98% with four owners. Single-store controls
remain unchanged. This justifies broader correctness and IPC testing; it is not
evidence for associative out-of-order data capture or unrestricted LS issue.
The four-owner tail contains only three 23-bit records, not duplicated SQ data.
Resource inference, routed 200 MHz and board gains are not established.
The broader 136-window run favors **two owners** as the simpler first candidate:
four small improvements and 132 unchanged windows, versus four improvements,
128 unchanged and four one-cycle regressions for four owners. The larger targeted
four-store gain is retained as a separate tradeoff. The resident-load extension
also passes: two owners improve the two-store disjoint recurrence by 25.37%
and four-store disjoint recurrence by 31.39%. Four owners improve the latter by
37.23%, at the cost of the separate broad-corpus regressions. These load-fed gains
have no measured younger-load overtakes; they come from faster store handling.
All 84 expanded target windows pass, as do 37 focused correctness tests, the
13-test bounded set at each capacity and the 387-test fast gate. A shared baseline
odd-SSP IRQ-schedule failure remains unresolved; this is not full acceptance.

Follow-up empty-tail turnover removes the four-owner one-cycle regressions:
four broad windows improve, 132 are unchanged, none regress versus its original
implementation. The two-owner corpus stays identical in all 136 windows. In the
84-run target, four-owner load/four-store disjoint recurrence improves again,
607 to 598 cycles; the other 82 windows are unchanged. This uses the same active
context and tail, with no new stored state or PRF port. It does not borrow a
same-cycle completion credit. Each capacity passes the 48-test focused
correctness set, and production generation and the 387-test fast gate pass.
Routing of this handoff revision remains pending; see the ledger for evidence.

Completed narrow audit: `issuePort.ready` blocks **all** new LSU issues on a late-data
capture edge, although base and index use separate PRF read ports from store
data. The tested ordinary-load overlap saves no end-to-end cycles across 136
matched windows, despite actual concurrent capture/issue. The functional option
was removed; retain only opportunity counters and ownership assertions. The
load-fed recurrence has 55 blocked-load opportunities in the baseline but just
two overlaps in the candidate, with identical 286/285-cycle windows. Removing
an upstream interlock is not enough when the downstream recurrence still waits.
This rules out that simple change for the measured corpus, not every possible
load/capture scheduling policy. Continue attribution of forwarding/retry latency
and completion-port conflicts before adding an advance-wakeup guarantee.

### 14. Shorter resident L1D path without a long permission cone

Overlap safe index/data work with translation and separate early payload choice
from late validity/permission. Keep a conservative cold path for splits, faults
and inhibited accesses. Combine only the fast-path cuts that preserve dependent
load timing; a pipeline stage that returns the gained cycle is not a repair.

**Cost / timing repair:** consolidate duplicate descriptors/rechecks where proven
redundant. Existing fall-through and early-wake candidates are the starting point,
not a claim that the whole path is solved. The original fall-through missed timing;
the payload-select repair retained every measured cycle count. Matched core-only
routing at `b99636f4` passed 200 MHz: baseline +11 ps, early-wake +30 ps, combined
+2 ps setup slack, all with zero hold/pulse failures. Combined uses 1,082 fewer
LUTs than baseline. This is not integrated-SoC timing or board validation.
**Experiment:** pointer recurrence, independent loads, calls/returns, L1 misses,
same IPC windows and end-to-end critical path. **Priority: high, already in progress.**

### 15. Branch-history correctness and compact prediction/recovery upgrades

First classify retired misses by PC and branch family: target/BTB, return/RAS,
direction, cold startup and history timing. Audit history repair against the
existing two-tier redirect before growing predictor tables. Then compare modest
hash/history changes, a small loop predictor, or a compact hybrid only for the
remaining measured patterns. Separate accuracy from redirect/recovery latency.

The first full-core diagnosis now identifies two concrete leads (see the ledger):
late repair overwrites conditional-history bits from a Tier-1-refetched frontend
that Tier 2 keeps, and slot-1 conditionals have inert prediction/training tags.
In long backlog, all 28 inner-branch misses are untrained instances; in long
alternating code only four of 24 are. First compare simple slot-1 conditional
deferral through the existing predictor port, then repair retained history under
an explicit recovery contract. The first deferral prototype now improves long
backlog IPC by 16.41%, but loses 5.82% on the long tight alternating loop despite
improving its accuracy to 99.870%. Keep it default-off and preserve both results.
Correct-branch retirement pairing does not remove that regression. This initial
all-conditional-deferral comparison does not include history repair.

**Implemented, default-off comparison:** allow an unpredicted slot-1 conditional to
carry a not-taken history/training record through the *existing* single tag-write
port when slot 0 has no record. Defer only on a tag-port conflict (and retain the
existing FTQ taken-branch deferral). This could retain training without every
extra decode cycle, but does not guarantee fewer misses: a taken branch missed
by the FTQ would still execute from an implicit not-taken prediction. It needs an
explicit lane-selection contract in DecodeStage, one tag allocation/history event
per emitted packet, fault/flush tests, and a real slot-1 index source. The prototype
now uses the Gshare-owned secondary lookup service, not an inert aligner default
or an internal-field shortcut. Training alone improves the long alternating
window by 2.75% but leaves long backlog unchanged. Additionally deferring PHT-taken
slot-1 branches retains the alternating gain and improves long backlog by 16.41%.
The short alternating seed-1 window regresses 2.17%; keep that result visible.
These are checked synthetic windows, not representative-workload or timing signoff.

**Retained-history follow-on:** preserving the valid post-redirect frontend history
suffix on top of selective deferral reduces the long backlog window from 2285 to
2240 cycles (+2.01% incremental IPC), and misses from 4 to 1 out of 224 branches
(99.554% accuracy in that synthetic window). Core-only routed timing passes at
200 MHz: +17 ps setup, +21 ps hold, zero setup/hold/pulse failures; 93,883 LUTs,
38,782 FFs, 37 BRAM tiles. The matched selective-only baseline misses by 280 ps.
This does not establish representative board accuracy, combined LSU timing, or
whole-SoC closure; the short-alternating regression remains in the record.

Local NaxRiscv reference checked at `9f452d50560d02fb391bc8039f5453c54e0911af`:
`prediction/DecoderPredictionPlugin.scala` supplies masked history events for
each decode lane; `prediction/HistoryPlugin.scala` orders those pushes and
restores instruction-associated ROB history plus the resolved branch outcome on
reschedule. Its `GSharePlugin.scala` carries counter context through alignment
and supports a registered memory read with write bypass. The applicable lesson is
explicit event coverage and recovery-point ownership, not copying its complete
predictor. Our single-record port and retained frontend require their own contract.
Compare a bounded retained-history suffix against full per-instruction snapshots;
neither may infer 16 history bits from the folded 11-bit PHT index. A full snapshot
also needs coverage for unpredicted/tag-zero branches, not just PHT-trained ones.

**Cost / timing repair:** deleting redundant repair/stall machinery may beat a
larger predictor. Registered token-tagged lookup and local history checkpoints
can preserve the fetch clock; verify they do not add equivalent bubbles. Selective
rename checkpoints or a map walk are separate recovery variants when restart
waits for retirement; they need allocation recovery, not just a copied RAT.
**Experiment:** per-PC retired-branch accuracy, MPKI, lost cycles and macro IPC
in identical warmed windows. Target >=95% on representative workloads, not a
guarantee for arbitrary unpredictable branches. **Priority: high.**

### 16. Admit rename by actual destination demand

The current `RenameStage.freeReady` unconditionally requires two free entries
in **all five** physical-register classes, even when the offered packet writes
none in an exhausted class or only needs one entry. Before adding PRF capacity,
measure cycles where this conservative rule alone rejects a safe packet. A
two-lane demand count can admit only the classes and counts actually allocated.
This is an RTL-audit proposal, not implemented or an observed IPC gain.

**Cost / timing repair:** avoid moving decoded destination flags onto a long
freelist→rename→dispatch ready path. Carry compact demand metadata at an existing
stage boundary if needed; do not add a steady-state rename bubble. The same
acceptance predicate must gate input ready, output valid and every allocation,
so no pool underflows and no held packet dispatches twice. Check unused/one/two
destinations in each class, mixed packets, flush and resource-pressure recovery.
**Priority: medium pending attributed rename-stall measurements; simpler than
early-free reference tracking if it addresses the measured pressure.**

## Controlled frontend amendment: single-port conditional admission

`deferSlot1Conditional` is a default-off experiment. An integer Bcc (opcode
`0x6xxx`, condition 2–15) that would emit in slot 1 is deferred to slot 0.
Suppress both its feed-valid lane and consumption of its words, using the same
existing deferral mechanism as returns and FTQ-confirmed branches. The next
accepted packet must still name the deferred instruction, including word/long
displacements and fetch-window crossings. Backpressure must not consume it.
BRA, BSR, DBcc, floating-point branches and non-branches retain their old rules.

There is still one prediction-tag allocation, one history shift and one eventual
training event per cycle. Slot 1 remains prediction-inert. The existing slot-0
BTB/PHT lookup handles the deferred branch; cold BTB misses retain conservative
not-taken prediction. This is not a second predictor port, a wider decoder, or
a change to architectural execution/retirement. History-repair behavior is
unchanged so its contribution can be tested independently. Compare matched warm
IPC and trained/missed branch counts before queuing timing; retain any decode
throughput regression in the ledger.

## Controlled frontend amendment: slot-1 training through one record port

`trainSlot1Conditional` defaults off and is mutually exclusive with unconditional
slot-1 Bcc deferral. If a simple slot-1 Bcc is actually emitted and slot 0 has no
prediction record, give it the existing implicit not-taken prediction, one
nonzero prediction tag, and its current secondary Gshare index. Shift one false
history bit on that feed handshake. A tagged slot-0 conditional instead defers
the slot-1 Bcc: two history shifts or two record writes are never required.
Existing taken FTQ/BTB/RAS suppression remains authoritative. Fault draining
must not introduce an unmatched history/training event.

Gshare owns the secondary-index service; FetchAlign consumes it rather than an
unwired default or another plugin's internals. Decode's optional lane selector
chooses the sole tagged valid packet for the existing idempotent table write.
The table, tag lifetime bound, read ports and retire-time training port do not
grow. Assertions reject simultaneous tagged lanes and a tagged slot 1 fed into
a decoder without this capability. Predictor misreads still undergo normal
branch resolution; this experiment does not change architectural commit.

Check exact lane/tag/index association through backpressure, recovery and tag
wrap, history-event coverage, cold/unknown-target branches, and matched IPC.
An extra false history bit without its matching carried training index is not
an acceptable approximation. The late-repair issue remains a separate change.

A separate `deferTakenSlot1Conditional` refinement requires this training mode.
It also defers when the secondary PHT direction is taken, using slot 0's target
lookup on the following cycle; not-taken branches still co-emit and train.
This retains a secondary PHT read that may otherwise be pruned (area/timing must
be measured), but does not reinstate the removed multiway secondary BTB read.
Cold BTB misses remain conservative; no target or architectural outcome is
invented from a direction bit alone. Measure this arm independently.

### Controlled experiment: retain the refetched conditional-history suffix

`retainRedirectHistory` is default-off and independent of slot-1 admission.
ROB supplies a setup-allocated service identifying the actual Tier-1 redirect
and its exact Tier-2 frontend-retention decision (including the exception-active
veto). Gshare records only the predicted conditional bits emitted after the
latest Tier-1 redirect, plus a saturating count up to GHR width. An older branch
replacing that redirect starts a new suffix. This is bounded history metadata,
not an architectural checkpoint or a new retirement/training path.

At the existing delayed repair, a kept frontend receives architectural history
through the retired redirecting branch followed by its retained suffix, including
a same-cycle new shift. A discarded frontend receives the existing architectural
repair. Invalidation clears the suffix; an unrelated flush cancels it. No history
is copied from architectural state at Tier 1, when older branches can still be
unretired. No PHT counter or carried training index is changed retroactively.

Check zero/one/more-than-GHR-width suffixes, redirect replacement, unrelated
flushes, reset/invalidation, same-cycle repair/shift, and full-core recovery. The
early redirect's edge can still emit an old-path conditional, but DecodeStage's
flush wins over capturing that packet, so it must be excluded from the suffix.
The first full-core run exposed this edge and rejected an overly strict assertion
that no shift could occur; the suffix start already has priority over appending.
Later normal predicted redirects and FTQ corrections do not flush already-emitted DecodeUop records and
must not restart this suffix. A new whole-frontend flush must do so. The measured
gain and added mux/count logic must be compared against the disabled mode before
queuing timing. This amendment authorizes the experiment, not acceptance.

## Other possibilities considered

- **A larger ROB/PRF/SQ:** useful control experiments, not an assumed solution.
  The first exhausted resource determines benefit. Expanding all structures
  simultaneously hides the cause and can enlarge wakeup/forwarding logic.
- **Runahead:** checkpoint, execute ahead for prefetching, then replay after the
  long miss. It attacks memory-miss latency, not publishing already-completed work.
  Retain it if DDR misses dominate; count only useful retirement, not pseudo-retire.
- **Value prediction / speculative memory renaming:** may shorten true recurrences,
  but require validation and recovery. Prefer verified forwarding and known-disjoint
  bypass first; revisit only if their residual bottleneck justifies the machinery.
- **Moving hot execution to another clock / false-path constraints:** not a way to
  satisfy the 200 MHz requirement. Real multicycle interfaces need explicit
  protocols and throughput accounting; functional paths stay constrained.

### 17. Wake LSU-produced condition-code dependents at selected completion

The optional integer early-wakeup path already announces an irrevocably selected
LSU result before its registered writeback, but by default `wakeupNzvcPort` broadcasts
only from `compValid && compNzvcWrite && !compIsFault`. Thus a MOVE-to-memory,
flag-producing load or CCR-restore dependency may wait an extra cycle even when
the winning completion and destination are already known. Audit the IQ's dynamic
NZVC dependency clear and registered issue/operand capture before mirroring the
integer guarantee for flags.

The default-off `earlyNzvcWakeup` prototype now implements that guarantee on the
existing port. Targeted full-core, warmed comparisons at `l2:5:70`, seeds 1/17,
save 80 cycles over 80 copyback recurrences: store→Scc is 636→556 cycles (+14.39%
IPC) and load→Scc→store is 799→719 (+11.13%). Independent load/branch windows
are unchanged, including branch counts and misses. These are recurrence controls,
not representative-workload or board gains. Forty-nine selected oracle tests
pass, including precise/fast stores, split/fault/flush and IRQ/CCR recovery.
Broader profiles and routed timing remain separate gates; see the experiment
ledger for complete results and regressions.

Use the existing wakeup service/port: no predictor, extra register-file port or
speculative flag value. Cover front/back completion, detached stores and precise
deferred replay; fault, orphan and flush paths must never announce a stale tag.
Assert that each announcement exactly matches next-cycle valid NZVC writeback
and destination, preserving same-cycle physical-tag allocation priority.
Keep X/FPCC and unproven cold paths unchanged initially.

**Experiment:** store→Scc/branch, load→branch and mixed data/flag recurrences,
normal profile controls, strict CCR/exception/IRQ oracle comparisons. Attribute
cycles waiting only for LS flags, and report accuracy separately from shorter
branch-resolution latency. **Cost / timing repair:** the announcement adds a
control fanout from selected completion into IQ dependency state; keep NZVC data
registered and do not bypass arbitration. Compare routed paths if IPC improves.
**Priority: high as a small, directly testable dependency-latency candidate.**

## BRAM-rich, fabric-limited implementation choices

| Structure / proposal | Prefer investigating | Port / latency condition |
| --- | --- | --- |
| ROB payload and lookahead (1–3) | Banked synchronous metadata reads; small prefetched head window | Sustain preparation bandwidth across wrap without making every completion access wide metadata |
| Prepared retirement side effects (3–5) | BRAM FIFO of compact free/store/training records | Reserve capacity before publication; separate committed watermark from later narrow drain |
| Epoch map history (4, 11) | BRAM snapshots with a small active register image | Snapshot writes and restore reads must fit ports; no assumption of many simultaneous map versions for free |
| Completion spill (9) | Narrow BRAM records, identity and completion summaries in small control arrays | No many-port search of every record on each completion |
| SQ (12–13) | BRAM data/cold payload; compact searchable address/age/valid metadata | Big-endian extraction, forwarding read latency and drain/read port collisions remain on the measured path |
| Predictor (15) | Banked synchronous tables within the existing tokenized fetch pipeline | Schedule lookup and training collisions; preserve fetch throughput and account for redirect latency |
| Checkpoint/free history (10, 15) | Sequential allocation logs in BRAM, narrow live summaries | Restore allocator ownership exactly, including allocations and frees after a checkpoint |
| Existing cold IQ payload | Synchronous banked payload behind compact issue-selection metadata | Multiple EU issue ports can conflict; prove prefetch/read scheduling avoids extra recurrence cycles or duplicating the whole payload per port |

Do not move everything to BRAM indiscriminately. A single 164-bit active map,
one-bit completion summaries and hot wakeup metadata may be cheaper/faster in
registers. Conversely, a BRAM-based PRF with many replicated read copies can still
consume excessive LUT muxing and routing. Compare **routed LUT/FF use, BRAM tiles,
replication, read/write ports, control fanout, recurrence latency and throughput**,
not just stored bits. Prefer a small fixed-port engine processing prepared records
over a wide associative structure whenever the preparation can overlap head stalls.

This favors options 2/3 over blindly widening every retirement consumer, and makes
option 9 worth an equal-area experiment if ROB pressure is demonstrated. It does
not remove the correctness cost of early slot recycling or make arbitrary rollback
free. Event queues must drain fast enough in the long run; BRAM only absorbs bursts.

## Side-buffer experiment: smallest useful version

Compare option 3 directly with 1, not with an artificially restricted baseline.
Prepare at two entries/cycle while the old head waits; initially cap batches at
eight and end at safe macro boundaries. Keep ROB slots allocated until publication.
Do not cross unsupported system, precise/device, trace or debug boundaries.
Initially stop before branches; separately test incorporating option 6 so branch
density does not silently defeat batching.

Use one shadow-map image with ordered overwrite semantics, a bounded obligation
bitmap and pre-reserved queues for reclaim/training/other deferred records.
Decouple the architectural allocation boundary from physical free draining.
For stores, evaluate an allocation-sequence commit watermark instead of widening
ROB-ID broadcast ports; preserve committed-but-undrained entries across reuse.
No external write becomes visible before authorized publication.

A single batch can create a new head-of-line stall if its last entry is incomplete.
Keep ordinary retirement available; cancel/rebuild preparation when its head is
consumed, or retain explicitly versioned sub-boundaries. Do not quietly turn one
simple buffer into an unbounded checkpoint hierarchy. Freeze batch endpoints;
continually extending a batch can starve publication.
Seal a partial batch under resource pressure rather than waiting to collect a
fixed number of entries. In particular, the current flag pools have only 16
physical entries each; SQ and integer/FP pools can also fill before the ROB does.
A batch builder waiting for more allocations while it withholds the frees needed
for those allocations is a deadlock, not merely a performance regression.

For an already-prepared, entirely safe 16-entry prefix, two-wide draining takes
eight publication cycles versus one bulk publication. This is an illustrative
seven-cycle drain difference, **not a seven-cycle whole-program speedup**: preparation,
dispatch backpressure, execution availability and free-queue drain change the result.
Two-wide preparation needs eight cycles to prepare those 16 entries in the first
place; it is valuable only where that work overlaps otherwise blocked time.

## Measurement and iteration order

1. Collect windowed occupancy, contiguous complete/eligible prefixes, head-wait
   reasons, branch pairing loss, and ROB/PRF/SQ/IQ dispatch-block causes. Completed
   younger entries alone are not proof of safe bulk retirement. Record branch
   counts as the accuracy denominator; MPKI uses instructions separately.
2. Finish current latency/forwarding timing experiments. Preserve the IPC-positive
   revisions; repair specific measured paths, not random stage boundaries.
3. Prototype 6 and 1 as relatively small retirement baselines; compare 3 against
   them. Continue 12/13 and 15 according to measured lost cycles. Do not accumulate
   every optional implementation in the final core.
4. For each candidate, log baseline/candidate macro counts, cycles, IPC, seeds,
   memory model, warm-up, correctness, area, setup/hold/pulse checks and disposition
   in the experiment ledger. Include unchanged or worse results.
5. Keep timing-repair attempts attached to their parent idea. Accept only with
   matched correctness and routed 200 MHz timing; then test the integrated SoC.

Before any bulk-retirement implementation, test WAW chains and delayed flags,
faults at each batch position, branch redirects, A7/stack banking, interrupts,
single-step/T0/T1 trace, debug stops, SQ backpressure, full reclaim queues, flush
on publication, and wrapped/reused identities. Existing special commit paths must
observe the same architectural image. Service ownership and one-producer Global
rules still apply; a side buffer must not become a second uncontrolled commit owner.

## 18. Conservative committed-store coalescing

User-proposed, not implemented or timed. Start with consecutive, committed,
data-ready, aligned same-size COPYBACK stores to exactly the same physical word,
before either is presented on the cache's drain Stream. Exclude precise/device,
split, atomic and ordering-boundary cases. Keep the youngest value and account
for both architectural stores without inventing a cache acknowledgement. A
single-neighbor comparison is preferable to a full-SQ associative merge search.
Already-presented payloads must remain stable even under backpressure; accepted
writes cannot be cancelled. Translation/permission/fault checking, flag and
address-register results are not removed by dropping a redundant memory write.

Do not overwrite an older speculative store with younger data: intervening loads
may require the older value, and a squash or exception may discard the younger
store. Coalescing adjacent bytes with masks is a separate expansion, not the
initial implementation. Nonadjacent coalescing needs an explicit memory-ordering
argument and must not move a younger value across externally observable accesses.

The board-derived copy loop is not an obvious win for this minimal proposal:
each repeated pointer store is separated by a destination-byte store, and the
next iteration consumes the pointer value. The baseline and combined simulation
both peak at only two resident SQ entries and one accepted drain. Measure eligible
pending pairs first; do not delay ordinary draining merely to manufacture merges.
Even perfect elimination of pointer cache writes leaves its load/add/forwarding
recurrence and the byte store's A1/NZVC results intact.

## Primary references

- [BOOM ROB and PNR](https://docs.boom-core.org/en/latest/sections/reorder-buffer.html):
  distinguishes safety-frontier advance from architectural commit; stores drain
  after authorization. Our proposed batching is not BOOM's existing mechanism.
- [BOOM rename](https://docs.boom-core.org/en/latest/sections/rename-stage.html):
  map recovery and free-list allocation recovery must both be considered.
- [Checkpoint Processing and Recovery, MICRO 2003](https://www.microarch.org/micro36/html/pdf/akkary-CheckpointProcessing.pdf):
  checkpoint-based recovery, reclamation and bulk retirement as a larger design
  alternative. The bounded ROB-preserving proposal here is not an implementation
  of CPR, and none of that paper's quantitative results are claimed for this core.
- [Runahead Execution, HPCA 2003](https://www.cs.cmu.edu/~18742/papers/Mutlu2003.pdf):
  execute-ahead prefetching with subsequent replay, distinct from useful retirement.
- Local NaxRiscv `9f452d50560d02fb391bc8039f5453c54e0911af`,
  `src/main/scala/naxriscv/lsu/LsuPlugin.scala`: dispatch SQ allocation and
  `loadedAhead`/`loadedDone` separation; details in [memory dependencies](memory-dependencies.md).
