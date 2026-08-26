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

## Wave 0 (survey, non-scenario) -- COMPLETE

Dispatched a read-only Explore agent to survey 5 further candidate areas
(DcachePlugin MSHR, UmWriteQueue/DTLB deferred-M-bit race, IQ compacting
shift-register flush race, ROB/freelist sibling-of-#176/#194/#200, StoreQueue
eviction/drain/way-wrap). Results:

- **DcachePlugin MSHR**: single-outstanding FSM (no ring), earlyProbe queue
  matches by tag/token CAM not position. No credible bug. Already covered by
  `DcacheDrainRefillRaceSpec.scala`. Skipped.
- **UmWriteQueue/DTLB**: `pageHazard` is an address-tag scan (not positional),
  `missPending` timing proven to close the one-cycle gap. Heavily covered
  already (`UmWriteSpec.scala`, `DtlbMissFlushSpec.scala`). Skipped.
- **IQ compacting scoreboard**: `physToSlot` IS index-derived-from-index
  (same shape as the fixed bug) but bounded linear shift not a ring, guarded
  by an externally-enforced invariant, and flush-wins-over-push by
  last-assignment-wins textual ordering. Low confidence, two adjacent races
  already fixed (#219, #139). Rated not worth a dedicated test -- skipped.
- **ROB/Freelist**: real pointer arithmetic (head/tail/commHead) throughout,
  BUT found `Freelist.scala`'s push-consumption gated `!io.flush` with no
  LOCAL invariant enforcement -- relies on retire-ordering discipline
  elsewhere (branch-mispredict-commit precedes doFlushReg by 1 cycle;
  excSquash occupies ROB head). Medium-low confidence, no concrete trigger
  found yet -- dispatched as scenario 6 to investigate further / harden.
- **StoreQueue**: cross-page forward-hazard fix (task #278) confirmed
  **in-progress but UNCOMMITTED** on the shared worktree as of this survey
  (no commit hash exists yet) -- a peer agent is actively editing
  `StoreQueue.scala`/`LsEuPlugin.scala`/3 StoreQueue spec files live. NOT
  duplicated. Own StoreQueue pointer/tag structures (sendPtr/ackPhaseB/
  head/tail) are real comparisons, not positional proxies -- no other
  candidate found.

## Ledger

| # | Scenario | Worktree/Branch | Status | Commit | Finding |
|---|----------|------------------|--------|--------|---------|
| 1 | CAS2 concurrent double-split ring fill (both operands misaligned, line-crossing, exhausts 4-deep ring with 2 concurrent split pairs from one instruction) | cpu040-cas2-splitring / campaign/cas2-splitring | GREEN | bbe51a63 (merged cff1df52) | Ring's shared `splitMergeLine` proven safe: strict-FIFO send/response pointer ordering guarantees pair1's merge completes before pair2's slot-A response could overwrite it. New test proves ring hits full 4/4 occupancy (2 slot-A+2 slot-B) with no cross-corruption. 6/6 LsEuSplitRingSpec green. |
| 2 | BFINS 5-byte span at misaligned line-crossing base (load.L split + load.B + RMW + store.L split + store.B, stacks bitfield-chain complexity with split-ring complexity) | cpu040-bfins-splitring / campaign/bfins-splitring | GREEN (confirmed-covered-by-#278) | 6eee53ec (merged 7d681f06) + `7700c3a8` cherry-picked as `5ffb1d38` | Subagent's root-cause diagnosis and repro were fully correct: BFINS 5-byte-span RMW LOAD.L at split-eligible LONG@14 + spill-byte one line over hit real memory corruption via StoreQueue's `sameLine` WRITETHROUGH-refill-staleness hazard never checking the line a cross-line query SPILLS into. Its own fix (re-adding `linePrevAs`/`linePrevBs`, a narrower `qLine+1` mechanism) was superseded before landing: this worktree forked BEFORE task #278 (StoreQueue cross-page forward-hazard fix, `7700c3a8`) landed on the shared branch, and #278 independently fixed the SAME underlying gap via a more general mechanism (real translated `paddrB`, covering same-page AND cross-page) -- explicitly deleting those same `linePrevAs`/`linePrevBs` registers as dead code. Coordinator confirmed both of scenario 2's tests pass CLEAN at the post-#278 tip with zero RTL changes. Resolution: dropped the redundant `StoreQueue.scala` hunk from the commit (amended to test-only), then cherry-picked `7700c3a8` itself into the integration branch (clean, no conflicts) so the branch's own test suite is fully green rather than carrying a documented "will pass once combined" caveat. Tests now stand as independent confirming regression coverage for #278. Verified post-cherry-pick: 3/3 relevant lock-step tests, 41/41 StoreQueue-family (`StoreQueueSplitSpec`+`StoreQueueSpec`+`LsEuSplitRingSpec`+`StoreQueueDcacheDrainPipelineSpec`), 331/331 fastTest, 407/408 ExecuteLockStepSpec (task #257 CMP2 only, 1 pending = scenario 5's own pendingUntilFixed test), 28/28 FpuLockStepSpec (2 correctly ignored = scenario 3/7's known-blocked repros). |
| 3 | FMOVEM.X (An)+ 8-register load, misaligned base, 12-byte stride rotates phase vs 16-byte line across registers (unlike MOVEM.L's fixed-phase stride) -- multi-split ring stress | cpu040-fmovem-postinc-ring / campaign/fmovem-postinc-ring | RED-FLAGGED (+ GREEN sub-result) | 2b988e7f (merged 1b18ffbe, conflict-resolved by lead) | **3 outcomes in one scenario.** (a) Scope-gap: `(An)+`/`-(An)`/store direction still unimplemented (design-doc §7 items 2-4 not landed) -- 2 new decode regressions pin the trap boundary, `docs/BUG_fmovemx_postinc_unimplemented.md`. (b) **New RED-FLAGGED bug, previously undiscovered**: first-ever real ROB-retire lock-step test of FMOVEM.X data-list LOAD found ROB commit-PC corruption -- `(An)` 1-elem: wholesale garbage commit (pc AND untouched a7 both wrong, X-propagation signature); `(d16,An)` 8-elem: trailing instruction's commit-pc short by one word. Root-caused to `RobPlugin.commitPc0`'s `retireAlone` mux / single-writer `nextPcMem` area but NOT pinned to one line -- deliberately not guess-patched (FMax-critical shared branch-retire mux). Full repro+leads in `docs/BUG_fmovemx_dataload_rob_commit_corruption.md`; 2 tests checked in `ignore`d pending the fix. (c) The campaign's actual ring-stress hypothesis independently PROVEN GREEN via a new EU-level `LsEuSplitRingSpec` test bypassing the buggy ROB path entirely: >=6 genuine back-to-back split pushes, all 24 rotating-phase chunks byte-correct. Test-only, zero src/main changes, zero FMax impact. Lead merge-conflicted with scenario 1 in `LsEuSplitRingSpec.scala` (both added independent tests) -- resolved by keeping both, re-verified 7/7 green post-resolution. |
| 4 | FMOVEM.X FP0-FP7,-(An) 8-register store, misaligned base -- StoreQueue two-descriptor split-store mechanism under rotating-phase multi-split pressure (store side is architecturally separate from the load ring) | cpu040-fmovem-predec-store / campaign/fmovem-predec-store | SCOPE-BLOCKED-> GREEN (substituted mechanism test) | 22acca8c (merged 576e840c) | Literal vehicle unimplemented: FMOVEM.X store direction (opclass 111) still traps (`DecodeStage.scala` gates `slot0IsFmovemx` to load/110 only, comment cites task #242 not landed); `-(An)`/`(An)+` data-list addressing unimplemented for EITHER direction. Not a new bug (pre-existing tracked gap, confirmed via 2 independent existing tests + a task report). Substituted a StoreQueue-mechanism-level test: split-store halves are FIELDS of one ring entry (`validB`/`paddrB`/`maskBs`, same index as slot A) not a parallel ring, `io.full` computed from live popcount -- same-cycle pop-then-realloc structurally cannot alias. New `StoreQueueSplitSpec` test drives the rotating-offset (14,10,6,2) burst + forced drain backlog + fresh-alloc-into-just-freed-slot directly, no corruption. 7/7 StoreQueueSplitSpec, 329/329 fastTest, 404/405 lockstep (task #257 only). Test-only, not FMax-sensitive. |
| 5 | MOVEM.L (An)+ mid-list fault (3rd of 4 registers faults): verify per-uop ROB precise-commit semantics already correctly leave earlier registers retired, later ones untouched, An/PC correct per real-chip/Musashi semantics (load + store direction) | cpu040-movem-midlist-fault / campaign/movem-midlist-fault | RED-FLAGGED (LOAD) + GREEN (STORE) | 265fe24d (merged 06494a6e) | **Corrected my own scenario-design assumption**: ground truth (pulled from Musashi's actual `m68kcpu.c` REG_DA_SAVE mechanism, not general lore) is a real mid-MOVEM-LOAD fault rolls back ALL touched registers (D0/D1 too), not just the untouched trailing ones. Fault delivery itself (PC/EA/SSW/faultAddr) matches Musashi exactly. Gap: this core's ROB retires/frees each MOVEM move-uop's phys-reg independently and immediately at ROB-head, so an already-committed earlier uop in the same macro is structurally unreachable by the later uop's fault-flush -- fixing needs macro-wide group-commit or fault-time snapshot/undo touching the ROB's shared FMax-sensitive retire/freelist path. Judged genuinely architectural, NOT force-fixed. LOAD test marked `pendingUntilFixed` (real regression coverage, fails loudly if RTL ever silently starts passing). STORE-direction mirror is fully GREEN (already-landed stores stay landed, An correctly rolls back via existing "trailing An-update never commits on fault" path) -- also noted, not chased: a low-confidence secondary finding that Musashi's own predec-store EA modeling has an unrelated 16-bit-bus-era oracle artifact. `docs/BUG_movem_midlist_load_fault_no_rollback.md`. 329/329 fastTest, ExecuteLockStepSpec 405 succeeded/1 pre-existing task #257/1 pending (new test), no new failures. |
## Wave 2

Dispatched after Wave 1 turned up a real, high-value bug in scenario 3 --
going deeper in that area per the brief's own guidance ("if a wave turns up
several real bugs in one area, it's reasonable to spend a second wave going
deeper there").

| # | Scenario | Worktree/Branch | Status | Commit | Finding |
|---|----------|------------------|--------|--------|---------|
| 7 | Isolate + root-cause + (if safely scoped) fix the FMOVEM.X data-list ROB commit-PC corruption bug found by scenario 3 (`docs/BUG_fmovemx_dataload_rob_commit_corruption.md`) -- whitebox-isolate `RobPlugin`'s `commitPc0`/`retireAlone` mux before touching the shared FMax-critical branch-retire path, per the bug doc's own recommended methodology | cpu040-rob-commitpc-fmovemx-fix / campaign/rob-commitpc-fmovemx-fix | RED-FLAGGED (real narrowing progress, not fixed) | 0fa37476 (merged 7a8f47ad) | 2 new `RobPlugin`-level whitebox tests (synthetic FMOVEM.X-shaped uop: `isBranch=False`, `pdstValid=False`, completes via real FP lane port 5) directly PROVE `commitPc0`/`retireAlone`/`predNextPc` behave correctly for this uop shape, single AND back-to-back -- **exonerates the bug doc's original top suspect**. Also established repro A's second corrupted field (`a7`) sources from a completely separate `exc.ss.a7` mechanism, not `commitPc0` -- meaning repro A's simultaneous double-corruption is genuine wrong execution upstream of the ROB, not one retire-time misread. Real defect narrowed to: predecode's FMOVEM.X length computation, the `fmovemxActive` FSM's rename-allocation indexing interaction, or `DivEuPlugin`'s FP-completion robId capture -- none pinned to a line yet, bug doc updated with a concrete next-step (test predecode length output standalone first). Both repro tests stay `ignore`d. 42/42 RobPluginSpec (40+2 new), 331 fastTest (330 pass, 1 pre-existing contention flake re-confirmed clean standalone 4/4, not a regression). Test+doc only, zero src/main changes, zero synth impact. |

## Wave 1 ledger

| # | Scenario | Worktree/Branch | Status | Commit | Finding |
|---|----------|------------------|--------|--------|---------|
| 6 | Freelist.scala push-consumption gated `!io.flush` with no local invariant enforcement (relies on retire-ordering discipline elsewhere) -- investigate for a live silent-physreg-leak trigger, else harden with a sim-only property assertion | cpu040-freelist-flush-invariant / campaign/freelist-flush-invariant | GREEN (no live bug) | 44786749 (merged af8a63c4) | Invariant holds by CONSTRUCTION not just convention: every `commitPorts(k).valid` producer in RobPlugin routes through `headReady`, which ANDs `!flushing` directly -- same wire RenameStage forwards to `Freelist.io.flush`, so `commitPorts(k).valid && flushing` is tautologically unreachable. Added sim-only `GenerationFlags.simulation` assert pinning this + explanatory comment + new FreelistSpec raw-component test. 4/4 FreelistSpec, 329/329 fastTest, 404/405 ExecuteLockStepSpec (task #257 CMP2 pre-existing only). Sim-only, does not touch FMax-sensitive ROB/Freelist synth cone. |

## FINAL SUMMARY (campaign concluded 2026-08-26)

**7 scenarios dispatched across 2 waves, all resolved.** Final integration
branch tip: `5ffb1d38` on `campaign/complex-path-tests`
(`/home/qwertyoruiop/macqd700-soc-worktrees/campaign-complex-path-tests`).

**Outcome counts** (a scenario can contribute to more than one bucket when it
had multiple sub-results):
- **GREEN** (new regression coverage, no bug / invariant holds): scenarios 1,
  2, 4, 6, plus scenario 3's ring-stress sub-result (7/7) -- **5 green
  results**.
- **RED-FIXED** (real bug found + fixed in this campaign): **0** direct
  RTL fixes landed by a campaign subagent. Scenario 2 found a real bug whose
  fix had *already independently landed upstream* (task #278) before it
  could merge -- reconciled as GREEN, not counted as a campaign RTL fix.
- **RED-FLAGGED** (real bug found, properly documented, deliberately not
  force-fixed): scenarios 3 (ROB commit-PC corruption), 5-LOAD (MOVEM
  mid-list fault non-rollback), 7 (same ROB bug, narrowed further but still
  open) -- **3 RED-FLAGGED bug docs**, 2 distinct bugs (3 and 7 are the same
  bug, 7 is a deeper investigation of 3).

**Real, previously-undiscovered bugs found and documented** (2 distinct):
1. `docs/BUG_fmovemx_dataload_rob_commit_corruption.md` -- `FMOVEM.X
   <ea>,<list>` (data-list LOAD) corrupts ROB commit-PC framing. First-ever
   real ROB-retire lock-step test of this feature (existing coverage was
   decode-level only). Investigated twice (scenarios 3 and 7): original top
   suspect (`RobPlugin.commitPc0`/`retireAlone` mux) whitebox-tested and
   EXONERATED; real defect narrowed to somewhere upstream of the ROB
   (predecode length computation, `fmovemxActive` FSM/rename-allocation
   indexing, or `DivEuPlugin`'s FP-completion robId capture) but not yet
   pinned to a line. Two directed repro tests checked in `ignore`d.
2. `docs/BUG_movem_midlist_load_fault_no_rollback.md` -- a real 68040 (per
   Musashi's actual `REG_DA_SAVE` mechanism) rolls back ALL registers a
   `MOVEM.L (An)+` load touched on a mid-list fault, not just the untouched
   trailing ones. This core's ROB retires/frees each MOVEM move-uop's
   phys-reg independently and immediately at ROB-head, so an
   already-committed earlier register write is structurally unreachable by
   a later uop's fault-flush. Needs macro-wide group-commit or fault-time
   snapshot/undo touching the shared FMax-sensitive ROB retire/freelist
   path -- correctly judged architectural, not force-fixed. One directed
   test checked in as `pendingUntilFixed` (real regression coverage that
   fails loudly the moment this silently starts passing). STORE-direction
   mirror is fully GREEN (already correct).

**Bug reported and independently already fixed upstream** (1): the
`campaign/bfins-splitring` scenario (2) independently rediscovered, via a
genuinely novel BFINS 5-byte-span-RMW-stacked-with-a-split-ring scenario, the
same StoreQueue `sameLine` spill-line staleness gap that task #278 (landed
mid-campaign, commit `7700c3a8`) had already fixed with a more general
mechanism. Root-cause and repro were fully correct and independently
confirmed; reconciled by dropping the redundant narrower RTL hunk and
cherry-picking `7700c3a8` into the integration branch so the tests stand as
confirming coverage for #278 rather than a competing fix.

**All RTL/test changes and their commits on `campaign/complex-path-tests`**:
- `bbe51a63` -- scenario 1: CAS2 double-split ring stress test (test-only)
- `6eee53ec` -- scenario 2: BFINS split-ring + spill-line regression tests (test-only, post-reconciliation)
- `5ffb1d38` -- task #278 (StoreQueue cross-page forward-hazard fix), cherry-picked from upstream `7700c3a8` so scenario 2's tests are green on this branch
- `2b988e7f` -- scenario 3: FMOVEM.X rotating-phase ring stress + 2 scope-gap decode regressions + `docs/BUG_fmovemx_postinc_unimplemented.md` + `docs/BUG_fmovemx_dataload_rob_commit_corruption.md` (test+doc only)
- `22acca8c` -- scenario 4: StoreQueue split-store slot-reuse stress test (test-only)
- `265fe24d` -- scenario 5: MOVEM mid-list fault LOAD (pendingUntilFixed) + STORE (green) tests + `docs/BUG_movem_midlist_load_fault_no_rollback.md` (test+doc only)
- `44786749` -- scenario 6: Freelist push-vs-flush invariant assert + regression test (sim-only assert + test)
- `0fa37476` -- scenario 7: RobPlugin whitebox isolation tests, exonerating the original ROB bug suspect, updating `docs/BUG_fmovemx_dataload_rob_commit_corruption.md` (test+doc only)

**Zero `src/main/scala` changes originated by this campaign** beyond the
cherry-picked upstream `7700c3a8` and the sim-only `GenerationFlags.simulation`
assert in scenario 6's `RobPlugin.scala`/`Freelist.scala` (both verified
synth-neutral -- sim-only guard, does not affect `FullCoreSynth`). No campaign
scenario required a Vivado/synth gate.

**Final verification on the merged integration branch** (tip `5ffb1d38`):
- `sbt fastTest`: 331/331 passed, 2 ignored, 0 failed
- `sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"`: 407/408 succeeded, 1 failed (pre-existing task #257 CMP2 `(d8,An,Xn)`, unchanged), 1 pending (scenario 5's own new `pendingUntilFixed` test) -- **no new failures**
- `sbt "testOnly m68k040.lockstep.FpuLockStepSpec"`: 28/28 succeeded, 2 ignored (scenario 3/7's own known-blocked repros) -- **no new failures**
- StoreQueue/split-ring family (`StoreQueueSplitSpec`+`StoreQueueSpec`+`LsEuSplitRingSpec`+`StoreQueueDcacheDrainPipelineSpec`): 41/41
- `RobPluginSpec`: 42/42

**Not merged into `fmax-postroute-200mhz-closure`** -- per the campaign
brief, that decision is left to the human/parent session. `campaign/complex-path-tests`
tip `5ffb1d38` is ready for review and merge.
