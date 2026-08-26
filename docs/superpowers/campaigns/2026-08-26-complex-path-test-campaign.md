# Campaign: complex-path test coverage (2026-08-26)

Lead: this Claude session. Base tip: `748197b5` on
`fmax-postroute-200mhz-closure` in
`/home/qwertyoruiop/macqd700-soc-worktrees/m68k040ooo-integration/cpu040`.
Integration branch: `campaign/complex-path-tests` (this branch), worktree at
`/home/qwertyoruiop/macqd700-soc-worktrees/campaign-complex-path-tests`.

Mission: hunt for the "positional proxy instead of real pointer" bug class
(see `e7ee618a` MOVEM split-ring fix) and the "two halves of a split access
assumed to share a relationship that only holds in the common case" class,
across complex-path RTL, by writing new directed tests -- one subagent per
scenario, isolated worktree per subagent, merged back here.

Notes:
- Checked `git log` on the shared worktree tip: no commit mentioning
  `paddrB`/cross-page forward-hazard found yet as of tip `748197b5` --
  task #278 (StoreQueue page-crossing forward-hazard) had NOT landed on this
  tip at campaign start. Treated as still in flight elsewhere; campaign
  avoided re-solving that exact scenario until confirmed otherwise.
- `LsEuSplitRingSpec.scala` already exists and already covers: split-ring
  enqueue while other loads resident, LONG offsets 13/14/15 + WORD offset 15
  merge correctness, fault-on-either-half abort-cancel, INHIBITED-load busy
  signal continuity across both sub-accesses. Scenarios below targeting the
  aligned split ring avoid duplicating those specific cases.

## Wave 0 (survey, non-scenario)

Dispatched a read-only Explore agent to survey 5 further candidate areas
(DcachePlugin MSHR, UmWriteQueue/DTLB deferred-M-bit race, IQ compacting
shift-register flush race, ROB/freelist sibling-of-#176/#194/#200, StoreQueue
eviction/drain/way-wrap) for concrete scenario candidates with file:line
evidence. Findings will seed Wave 2 scenarios.

## Ledger

| # | Scenario | Worktree/Branch | Status | Commit | Finding |
|---|----------|------------------|--------|--------|---------|
| 1 | CAS2 concurrent double-split ring fill (both operands misaligned, line-crossing, exhausts 4-deep ring with 2 concurrent split pairs from one instruction) | cpu040-cas2-splitring / campaign/cas2-splitring | DISPATCHED | - | - |
| 2 | BFINS 5-byte span at misaligned line-crossing base (load.L split + load.B + RMW + store.L split + store.B, stacks bitfield-chain complexity with split-ring complexity) | cpu040-bfins-splitring / campaign/bfins-splitring | DISPATCHED | - | - |
| 3 | FMOVEM.X (An)+ 8-register load, misaligned base, 12-byte stride rotates phase vs 16-byte line across registers (unlike MOVEM.L's fixed-phase stride) -- multi-split ring stress | cpu040-fmovem-postinc-ring / campaign/fmovem-postinc-ring | DISPATCHED | - | - |
| 4 | FMOVEM.X FP0-FP7,-(An) 8-register store, misaligned base -- StoreQueue two-descriptor split-store mechanism under rotating-phase multi-split pressure (store side is architecturally separate from the load ring) | cpu040-fmovem-predec-store / campaign/fmovem-predec-store | DISPATCHED | - | - |
| 5 | MOVEM.L (An)+ mid-list fault (3rd of 4 registers faults): verify per-uop ROB precise-commit semantics already correctly leave earlier registers retired, later ones untouched, An/PC correct per real-chip/Musashi semantics (load + store direction) | cpu040-movem-midlist-fault / campaign/movem-midlist-fault | DISPATCHED | - | - |
