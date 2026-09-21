# Retirement bandwidth experiments

Status: implementing prerequisites for the four-wide control experiment in
[proposal 1](ipc-design-options.md). Production retirement remains two-wide.
This amendment permits an explicit two- or four-lane rename commit interface;
it does not authorize a ROB head advance without the corresponding side effects.

## Commit and reclamation interface

`RenameStage(retireWidth = 2)` owns the lane count, exposed by the length of
`RenameCommitService.commitPorts`. Four is an experimental alternative. Decode,
speculative rename and allocation remain two-wide. No new Global key or producer
is introduced. The ROB must eventually drive every lane through this service;
no plugin may reach into a sibling's committed maps or freelists.

Each valid lane describes one retiring entry in age order. For a same-register
WAW chain the highest valid lane wins the committed mapping. **Every** old
physical-register release remains present, including intermediate mappings that
are overwritten within the batch. Integer, NZVC, X, FP and FPCC all obey the same
rule; coalescing map writes must never coalesce away reclamation records.

All five committed maps and committed-allocation pointers update on the commit
edge. Existing one-cycle reclamation scales to the selected lane count, accepting
and draining that many records without backpressure. With P pending frees and S
uncommitted allocations, `available + P + S = physical - architectural` still
holds. Sparse valid lanes compact in age order. Flush accepts no new commit or
allocation, but drains all previously committed frees while restoring the
committed allocation boundary. Reset clears pending frees and reseeds identity
state. This extends [registered reclamation](deferred-register-reclamation.md).

## Full-core integration requirements

The four-wide ROB experiment must publish only a safe contiguous in-order prefix.
Uncommon system, exception, trace/debug and branch-training cases may retain a
conservative path; correctness gates must test a stop/fault at every lane and
macro boundary. Increasing width does not permit speculative architectural state.

Before enabling it in a full core, update and verify all of:

- head/count arithmetic and complete/fault checks for every candidate lane;
- ordered commit maps, free records and SQ authorization;
- committed CCR/PC, A7/FP state, interrupts and precise-store boundaries;
- single-step, breakpoints, halt-after and recovery PC selection;
- lossless commit observations, history and performance counters.

The current source audit also identifies I- and D-side **page-table U/M write
queues** as retirement consumers, even for instructions without an SQ entry.
Both expose only `commit`/`commitB`. Omitting lanes 2/3 there would leave pending
updates uncommitted or misassociate them after ROB-ID reuse. Widen their notices
alongside SQ authorization; cacheable/no-MMU microbenchmarks cannot validate this.

| Consumer | Current implementation to generalize |
| --- | --- |
| Maps and committed/free allocation boundaries | `RenameStage`, `RatTable`, `Freelist` |
| SQ drain authorization | `LsEuService.sqCommit/sqCommitB`, `StoreQueue` |
| Page-table U/M authorization | `ItlbPlugin`, `DtlbPlugin`, `UmWriteQueue` |
| CCR value, including same-edge precise-store completion | `RobPlugin.ccrAfter0/ccrAfter1` and live completion bypass |
| Final PC and debug stop/count | `debugLivePcReg`, `debugMacroCountInc`, macro-retire PC stream |
| FP state, system transfer temporaries | committed FP status and `sysAux` capture paths |
| Observations | `CommitTraceService`, simulation `commitObs`, history/perf consumers |

Production and test backend wiring must use an explicit ROB-owned service for
new lane events, rather than adding more cross-plugin reads of `rob.logic`.
The present ROB rejects a four-lane rename interface at elaboration until these
consumers and precise boundary rules are implemented together.

Then compare actual warmed IPC against two-wide retirement on both the original
and improved LSU/predictor configurations, before queueing synthesis. Completed
prefix histograms are not a counterfactual IPC result. Four-wide retirement is
the control for prepared shadow-map publication, not a substitute for that
separate experiment. Final acceptance still requires routed 200 MHz and the
integrated SoC/board checks.
