# FMax "Lever A": reqMatch freshness flag — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `LsEuPlugin.scala`'s `reqMatch` 20-bit VPN value-compare
(fed by two chained 32-bit adders, ~56%/3.193ns of a -1.779ns post-route
critical path) with a 1-bit event-driven freshness flag that answers the
same question ("has `reqReg` caught up to the currently-presented access")
without comparing address values.

**Architecture:** Track the two register-write events that are the ONLY
way `xlateVaddr` can change (`issuePort.fire`, `xlateBArm`'s value
changing) via one cycle of history (`RegNext`), and gate `reqMatch` on
"neither happened last cycle" instead of a value compare. Provably
closed and strictly more conservative than the value compare (see design
spec's correctness argument) — never a stale-consume risk, at most one
extra harmless stall cycle in a rare VPN-coincidence edge case.

## Global Constraints

- Design spec (read in full before starting):
  `docs/superpowers/specs/2026-08-08-fmax-levera-reqmatch-freshness-design.md`
- `reqReg`'s own capture logic (`LsEuPlugin.scala:1735-1739`), the
  `xlate.req` drive (`:1740-1744`), DTLB-miss stall behavior, MMU-off/
  identity-mode behavior, and split-access (slot A/B) semantics are
  UNCHANGED — only `reqMatch`'s definition and its 3 new supporting
  signals change.
- This is genuinely correctness-sensitive (a stale-DTLB-translation
  guard). The Step 4 divergence (new code is strictly MORE conservative,
  never less) must be explicitly proven by a directed test, not assumed.
- `~/sbt/bin/sbt compile` must stay clean throughout.
- `ExecuteLockStepSpec` must stay at 390/394 (same 4 pre-existing
  failures).
- Full ported corpus (~870 tests) must show zero new regressions vs. the
  current baseline (check `.superpowers/sdd/progress-fmax-slice3.md`'s
  "THE REVERT" section for the current fail count/list).
- This slice ALONE measures 0MHz post-route gain (a different path family
  currently holds WNS) — do NOT judge this task's success on a standalone
  post-route number. Success criterion: the targeted family's slack
  improves causally, zero regressions, and both OOC and post-route deltas
  reported explicitly (they may disagree — post-route is binding).

---

### Task 1: Replace `reqMatch` with the freshness flag, verify, synth-gate

**Files:**
- Modify: `src/main/scala/m68k040/execute/LsEuPlugin.scala` (replace lines
  673-674's `reqMatch` definition with the new block; re-verify exact
  live line numbers before editing)
- Test: locate the DTLB/MMU-adjacent test suite(s) via
  `grep -rl "Dtlb\|Mmu" src/test/scala/` and add the 2 new directed tests
  there (or a new file if none fits — match this project's existing
  harness conventions for whichever file is chosen)

**Interfaces:**
- Consumes: `issuePort.fire` (existing signal, `LsEuPlugin.scala:1085`-
  adjacent), `xlateBArm` (existing `Reg(Bool())`, written at `:1229`/
  `:1267`/`:1275`) — both already in scope in the same `logic` Area.
- Produces: `xlateBArmPrev: Bool` (new `Reg`), `presentedAccessChanging:
  Bool` (new combinational), `reqStale: Bool` (new `Reg`), `reqFresh:
  Bool` (new combinational), `reqMatch: Bool` (redefined, same name,
  same 2 consumption sites at `:1194`/`:1261` — signature/type
  unchanged, only the RHS expression changes).

- [ ] **Step 1: Re-verify exact current line numbers**

```bash
grep -n "val reqMatch\|val xlateVaddr\|val xlateBArm\|issuePort.fire\|issuePort.ready" src/main/scala/m68k040/execute/LsEuPlugin.scala
```
Confirm the design spec's citations (lines 622, 660-666, 673-674, 1085,
1126-1131, 1194, 1229, 1261, 1267, 1275, 1735-1744) still match live
source before editing.

- [ ] **Step 2: Replace `reqMatch`'s definition**

Replace the existing (around line 673-674):
```scala
val reqMatch = reqReg.valid && (reqReg.vpn === xlateVaddr(31 downto 12)) &&
               (reqReg.write === isStore)
```
with:
```scala
// FMax closure, "Lever A" (2026-08-08): reqMatch used to be a 20-bit VPN
// value-compare fed by two chained 32-bit adders (s1Va/s1AddrB) -- ~56% of
// a -1.779ns post-route critical path. Since reqReg re-samples xlateVaddr
// unconditionally every cycle, reqMatch is really answering "is this
// cycle's xlateVaddr the same as last cycle's" -- answerable by tracking
// the two register-write events that are the ONLY way xlateVaddr can
// change (issuePort.fire, xlateBArm's value changing), not by comparing
// values. See docs/superpowers/specs/2026-08-08-fmax-levera-reqmatch-freshness-design.md
// for the full closure argument (why nothing else can change xlateVaddr
// while a translation is pending) and the one known, harmless, STRICTLY
// MORE CONSERVATIVE divergence from the old value-compare (a VPN
// coincidence between consecutive accesses could make the old code
// consume one cycle earlier; the new code never does, only ever costs an
// extra harmless stall cycle in that rare case -- never a stale consume).
val xlateBArmPrev = RegNext(xlateBArm, init = False)
val presentedAccessChanging = issuePort.fire || (xlateBArm =/= xlateBArmPrev)
val reqStale = RegNext(presentedAccessChanging, init = False)
val reqFresh = !reqStale
val reqMatch = reqReg.valid && reqFresh
```

Do not touch `reqReg`'s capture block or `xlate.req`'s drive.

- [ ] **Step 3: Compile**

```bash
~/sbt/bin/sbt compile
```
Expected: clean. Fix any reference issues (e.g. if `issuePort` isn't the
exact live name — re-verify from Step 1) before proceeding.

- [ ] **Step 4: Locate existing DTLB/MMU test coverage**

```bash
grep -rl "Dtlb\|Mmu" src/test/scala/ | sort
```
Read what's found. Identify the right home for the 2 new directed tests
(prefer extending an existing spec that already has a DTLB-aware DUT/
harness over creating a new one).

- [ ] **Step 5: Run existing DTLB/MMU tests BEFORE adding new ones**

Run every file found in Step 4. Confirm all pass on the modified code
before adding anything new — isolates whether the change itself broke
anything from whether a new test is well-formed.

- [ ] **Step 6: Add the coincidental-VPN-match directed test**

Per the design spec's Step 4 / Verification Requirements: construct a
scenario where two consecutive accesses (or a split access's slot A and
slot B) share the same VPN. Confirm:
1. On an ISOLATED WORKTREE checked out to the pre-change commit (the
   current HEAD before Step 2's edit), the OLD code's exact consume
   timing in this scenario (does it consume one cycle earlier than the
   new code, as the spec predicts? Prove it, don't assume it).
2. On the NEW code, the final translated address and fault behavior are
   CORRECT (matches the OLD code's eventual result), possibly one cycle
   later.
3. Neither version ever produces a WRONG address — this is the binding
   correctness property.

- [ ] **Step 7: Add the split-access transition directed test**

Construct an access whose translation resolves on the SAME cycle
`xlateBArm` toggles (the slot-A-to-slot-B transition in a split/
misaligned access). Confirm `reqFresh`/`reqMatch` reads False for
exactly one cycle at the transition, and the FSM stalls rather than
consuming a stale DTLB response meant for the other slot.

- [ ] **Step 8: Run the new tests**

```bash
~/sbt/bin/sbt "testOnly *<TheSpecFileName>*"
```
Expected: PASS. If FAIL, debug the actual timing/closure argument — do
not weaken the assertions.

- [ ] **Step 9: Targeted MMU/split-access ported tests**

Grep the ported corpus for `mmu_split`/`idx_alias`/split-access-family
test names (this is the highest-risk area for this specific change) and
run them explicitly before the full sweep.

- [ ] **Step 10: `ExecuteLockStepSpec` full suite**

```bash
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec"
```
Expected: 390/394, the same 4 pre-existing failures. Investigate any
difference before proceeding.

- [ ] **Step 11: Full ported test corpus (isolated worktree, before/after)**

Use `tools/fuzz/ported-sweep-parallel.sh` against the current baseline
commit (record its SHA before starting Step 2). Compare sorted fail-name
lists — expect byte-identical.

- [ ] **Step 12: OOC-synth-only gate**

```bash
vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl
```
Record WNS/FMax. Check whether the `eaAuto -> faultAddrStore` path
family's slack has improved in the worst-path report (it may not be OOC's
worst path at all per this session's finding that OOC and post-route
diverge — report what you find honestly either way).

- [ ] **Step 13: Real post-route gate**

```bash
vivado -mode batch -source synth/impl_FullCore.tcl
```
Record WNS/FMax. Per the Global Constraints, this slice ALONE is expected
to measure ~0MHz change in overall WNS/FMax (a different family currently
holds WNS) — the success criterion is that the `eaAuto -> faultAddrStore`
family's OWN slack improves causally (check the specific path/family in
the slack-matrix report, not just the top-line WNS number), with zero
regressions elsewhere.

- [ ] **Step 14: Commit**

```bash
git add src/main/scala/m68k040/execute/LsEuPlugin.scala \
        src/test/scala/<the modified/new spec file(s)>
git commit -m "execute: replace LsEuPlugin reqMatch value-compare with a freshness flag (FMax Lever A)"
```

## Self-Review Note

One cohesive task, matching this initiative's established plan structure
(Slice 1/2/3 were each a single task): the mechanism, its correctness
proof, and its directed tests are not independently meaningful
milestones — a partial version (e.g. the freshness flag added but the
coincidental-VPN divergence untested) is actively unverified, not merely
incomplete.
