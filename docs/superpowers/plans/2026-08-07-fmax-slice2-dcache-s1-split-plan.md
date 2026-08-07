# FMax closure Slice 2: D-cache LOAD S1a/S1b split — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `DcachePlugin`'s LOAD S1 (tag-compare/way-select/byte-extract/
respond, one flat ~13-level combinational cone) into S1a (compare+way-select,
unchanged timing, still drives the untouched miss/REFILL trigger) and a new
S1b (byte-extract+respond, new registers `ldS2*`), closing the
`DcachePlugin_logic_tagMem_2_reg -> LsEuPlugin_logic_compData_reg` critical
path family identified after Slice 1 (-1.987ns post-route).

**Architecture:** Register `ldS1Hit`/`ldS1Line`/`ldS1Off`/`ldS1Size`/
`ldS1Fault`/`ldS1Valid` into new `ldS2*` registers at the end of the existing
S1 cycle; drive `loadRspPort` from `ldS2*` instead of `ldS1*`. Extend
`inFlight` and `dcIdleForMaint` to also gate on `ldS2Valid`, preserving
exact single-outstanding semantics (one uniform extra cycle of load
latency, no throughput/pipelining semantics change). Zero changes to
`LsEuPlugin.scala` (confirmed unnecessary — `WAIT` is `Flow.valid`-driven).

**Tech Stack:** SpinalHDL/Scala, sbt, ScalaTest (SpinalSim), Vivado
(`synth/impl_FullCore.tcl` for the post-route gate).

## Global Constraints

- Design spec (read in full before starting):
  `docs/superpowers/specs/2026-08-07-fmax-slice2-dcache-s1-split-design.md`
- `ldS1Hit`/`ldS1HitVec`'s use at `DcachePlugin.scala:666`
  (`when(ldS1Valid && !ldS1Hit)`, the miss/REFILL trigger) is NOT touched —
  it stays keyed off the S1 (not S2) signals. Do not delay miss detection.
- `busFaultResp`/`inhibitedResp` (REPLAY-driven response pulses) are NOT
  touched — still Mux'd into `loadRspPort` alongside the new `ldS2Resp`,
  same shape/priority as today.
- `LsEuPlugin.scala`: NO changes. If verification reveals this assumption
  is wrong, STOP and escalate — do not improvise a fix.
- `inFlight` (line 621) and `dcIdleForMaint` (line 1092) BOTH gain a
  `|| ldS2Valid` / `&& !ldS2Valid` conjunct respectively — this is
  REQUIRED, not optional (see design spec's correctness argument).
- No change to the STORE pipeline (S0/S1/S2 write-through/copyback RMW).
- `~/sbt/bin/sbt compile` must stay clean throughout.
- `ExecuteLockStepSpec` must stay at 390/394 (same 4 pre-existing failures
  as Slice 1's baseline — investigate any different count/list before
  proceeding).
- Full ported corpus (~870 tests) must show zero new regressions vs. the
  post-Slice-1 baseline (812 pass / 58 fail,
  `.superpowers/sdd/progress-fmax-p0live.md`).
- Both OOC-synth-only AND the real post-route gate
  (`synth/impl_FullCore.tcl`) are required before this task is considered
  done. Report the measured FMax delta explicitly.

---

### Task 1: Split LOAD S1 into S1a/S1b, verify, synth-gate

**Files:**
- Modify: `src/main/scala/m68k040/cache/DcachePlugin.scala` (add `ldS2*`
  registers near line 280; replace the `loadRspPort` drive at lines
  306-311; extend `inFlight` at line 621 and `dcIdleForMaint` at line 1092)
- Test: locate and modify/extend the relevant `src/test/scala/m68k040/cache/*Spec.scala`
  file(s) — grep for `DcachePlugin`/`DcacheDrainRefillRaceSpec`/any test
  referencing `pendingStoreMiss.simPublic()` or `ldS1*`/`ldS2*` before
  writing new tests, to find the right home and avoid duplicating existing
  coverage.

**Interfaces:**
- Consumes: `DcachePlugin.scala`'s existing `ldS1Valid`/`ldS1Hit`/
  `ldS1HitWay`/`ldS1Line`/`ldS1Off`/`ldS1Size`/`ldS1Fault` (all computed
  exactly as today, UNCHANGED) as the new registers' `D` inputs.
- Produces: `ldS2Valid`/`ldS2Hit`/`ldS2Line`/`ldS2Off`/`ldS2Size`/
  `ldS2Fault` (new `Reg`s); `ldS2Resp = ldS2Valid && ldS2Hit`. `loadRspPort`
  (the existing `DcacheService` output — `def loadRsp` at line ~1630, `val
  loadRspPort = Flow(DLoadRsp())` at line 55) keeps its EXACT same type/
  interface — only the timing of a HIT response's `.valid` pulse changes
  (by +1 cycle relative to accept), consumed unchanged by
  `LsEuPlugin.scala`'s `WAIT` state.

- [ ] **Step 1: Locate and read the exact current line numbers**

Before editing, re-verify against live source (this project's own
established convention — line numbers drift):
```bash
grep -n "ldS1Hit\|ldS1HitVec\|ldS1HitWay\|ldS1Line\|ldS1Resp\|ldS1Fault\|ldS1Off\|ldS1Size\|ldS1Valid\|loadRspPort\|val inFlight\|dcIdleForMaint" src/main/scala/m68k040/cache/DcachePlugin.scala
```
Confirm the design spec's citations still match (they were verified
against HEAD at spec-writing time, but re-verify before editing).

- [ ] **Step 2: Add the S2 registers and re-point `loadRspPort`**

Immediately after the existing `val ldS1Line = rdData(ldS1HitWay)` line
(around line 280) and BEFORE the existing `loadRspPort.valid := ...` block
(around line 306), insert:

```scala
    // ---- LOAD S2 (registered post-hit-detect response build) ----
    // FMax closure Slice 2 (2026-08-07): the old S1 response build (way-
    // select -> byte-lane extract -> loadRspPort) was one flat ~13-level
    // combinational cone from the BRAM tag-read output straight into
    // LsEuPlugin's compData register -- see
    // docs/superpowers/specs/2026-08-07-fmax-slice2-dcache-s1-split-design.md.
    // S1's hit/miss DECISION (ldS1Hit, used only by the miss/REFILL trigger
    // below) is untouched -- only the HIT RESPONSE DATA moves one cycle
    // later. Costs one uniform extra cycle of load-to-use latency on every
    // cache hit (LsEuPlugin's WAIT state is already Flow.valid-driven /
    // latency-agnostic -- no consumer-side change needed).
    val ldS2Valid = RegInit(False)
    val ldS2Hit   = Reg(Bool())
    val ldS2Line  = Reg(Bits(128 bits))
    val ldS2Off   = Reg(UInt(offBits bits))
    val ldS2Size  = Reg(Size())
    val ldS2Fault = Reg(Bool())
    ldS2Valid := ldS1Valid
    ldS2Hit   := ldS1Hit
    ldS2Line  := ldS1Line
    ldS2Off   := ldS1Off
    ldS2Size  := ldS1Size
    ldS2Fault := ldS1Fault
    val ldS2Resp = ldS2Valid && ldS2Hit
```

Then REPLACE the existing `loadRspPort.valid := ...` / `.data := ...` /
`.line := ...` / `.fault := ...` block (the 4 lines starting around line
306) with:

```scala
    loadRspPort.valid         := ldS2Resp || busFaultResp || inhibitedResp
    loadRspPort.payload.data  := Mux(inhibitedResp,
                                      DcacheByteLane.extract(missLine, missOff, missSize),
                                      DcacheByteLane.extract(ldS2Line, ldS2Off, ldS2Size))
    loadRspPort.payload.line  := Mux(inhibitedResp, missLine, ldS2Line)
    loadRspPort.payload.fault := Mux(busFaultResp, True, ldS2Fault)
```

Do NOT touch `ldS1Resp` (line 287) or the `when(ldS1Valid && !ldS1Hit)`
miss-trigger block (line 666 onward) — leave every reference to `ldS1Hit`
there exactly as-is. (`ldS1Resp` itself becomes dead/unused once
`loadRspPort` no longer reads it — leave the `val ldS1Resp = ldS1Valid &&
ldS1Hit` declaration in place if removing it causes any other reference to
break, otherwise delete it; check with a compile.)

- [ ] **Step 3: Extend `inFlight`**

At the existing `val inFlight = ldS1Valid` line (around line 621), change
to:
```scala
      val inFlight = ldS1Valid || ldS2Valid
```

- [ ] **Step 4: Extend `dcIdleForMaint`**

At the existing `dcIdleForMaint` definition (around line 1092):
```scala
    val dcIdleForMaint = !busy && !ldS1Valid && !ldS2Valid && !pendingStoreMiss && !pendingWtKickoff &&
                         !storePort.valid && !s0Valid && !stS1Valid && !stS2Valid &&
                         stAwDone && stWDone && evictAwDone && evictWDone
```
(only the `&& !ldS2Valid` conjunct is new — keep every other term exactly
as it is today, do not reformat unrelated lines).

- [ ] **Step 5: Compile**

```bash
~/sbt/bin/sbt compile
```
Expected: clean compile, no errors. Fix any type/reference issues before
proceeding.

- [ ] **Step 6: Locate existing D-cache test coverage**

```bash
find src/test -iname "*Dcache*Spec*.scala" -o -iname "*DCache*Spec*.scala"
grep -rln "pendingStoreMiss" src/test/scala/
```
Read whatever files this finds. Identify: (a) the "Fable5 Bug1 regression
test" referenced by `DcachePlugin.scala:174`'s comment (the store-drain-miss
race test), (b) `DcacheDrainRefillRaceSpec` or equivalent (referenced by
`DcachePlugin.scala:128`'s `victimWay.simPublic()` comment), (c) any other
directed D-cache load-latency tests.

- [ ] **Step 7: Run the existing D-cache test suite BEFORE adding new tests**

Run every test file found in Step 6. Confirm all pass on the modified code.
If any fail, determine whether the failure is a genuine timing-shift
consequence of this change (expected: a load-hit response now arrives one
cycle later — any test that hardcodes the OLD 2-cycle response latency via
an exact cycle-count assertion needs its expected cycle count bumped by 1,
which is an ACCEPTABLE, EXPECTED test update, not a regression) versus a
genuine behavioral bug (unacceptable — stop and investigate).

- [ ] **Step 8: Add a directed test proving the added latency + unchanged miss timing**

In whichever file Step 6 identifies as the right home (or a new
`DcacheS1SplitSpec.scala` if none fits), add a test that:
1. Issues a load that will HIT (prime the cache first if needed, matching
   this file's existing test-setup conventions).
2. Confirms `loadRspPort.valid` (via existing `simPublic()` hooks — check
   `DcachePlugin.scala` for what's already exposed, e.g. `ldS1Valid`/
   `ldS1Hit`/`busFaultResp`/`inhibitedResp`/`pendingStoreMiss`/`victimWay`
   are all `simPublic()`'d already; add `ldS2Valid`/`ldS2Resp` as
   `simPublic()` too if the test needs direct visibility, following the
   exact pattern at e.g. line 278) pulses on accept-cycle+3, not the old
   accept-cycle+2.
3. Separately, issues a load that will MISS, and confirms the
   miss/REFILL-entry decision (e.g. via `busy` or an FSM-state
   `simPublic()` hook, or observing the AXI `ar` channel firing) still
   happens on accept-cycle+2 — i.e., prove miss-detection timing is
   UNCHANGED by this slice.

Follow this file's existing test style exactly (check an existing
`DcachePlugin`-adjacent spec for the harness/poke/wait conventions used).

- [ ] **Step 9: Run the new tests**

```bash
~/sbt/bin/sbt "testOnly *<TheSpecFileName>*"
```
Expected: PASS. If FAIL, debug — do not weaken the assertion to make it
pass; the whole point of this test is to pin the new timing precisely.

- [ ] **Step 10: `ExecuteLockStepSpec` full suite**

```bash
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec"
```
Expected: 390/394, the SAME 4 pre-existing failures as Slice 1's baseline.
If the count or the specific failing tests differ, STOP and investigate
before proceeding — do not assume it's unrelated.

- [ ] **Step 11: Full ported test corpus (isolated worktree, before/after)**

Use `tools/fuzz/ported-sweep-parallel.sh` (see its own `--help`/comments
for exact invocation; this project's established pattern from Slice 1 was
a git-worktree-isolated before/after comparison against a known-good base
commit). Base commit: `HEAD` at the start of this task (record its SHA
before starting Step 2). Compare the sorted fail-name list before/after —
expect BYTE-IDENTICAL (or, if Step 7 required bumping a hardcoded cycle
count in an EXISTING ported test, document that specific test's change
explicitly and confirm no OTHER test's outcome changed).

- [ ] **Step 12: OOC-synth-only gate**

```bash
vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl
```
Record the WNS/FMax. Confirm the `DcachePlugin_logic_tagMem_2_reg ->
LsEuPlugin_logic_compData_reg` path family (or its direct descendant) no
longer appears in the worst-8 paths report. Note the new worst path family
for the report.

- [ ] **Step 13: Real post-route gate**

```bash
vivado -mode batch -source synth/impl_FullCore.tcl
```
Record WNS/FMax. Compare against the post-Slice-1 baseline (146.11 ->
167.029 MHz was Slice 1 alone; if Slice 3 has already landed, compare
against whatever the current baseline is). Report explicitly whether the
targeted path family is gone from the worst-path report and what the new
worst path is.

- [ ] **Step 14: Commit**

```bash
git add src/main/scala/m68k040/cache/DcachePlugin.scala src/test/scala/m68k040/cache/<the modified/new spec file(s)>
git commit -m "cache: split D-cache LOAD S1 into S1a/S1b (FMax closure slice 2)"
```

## Self-Review Note

This is deliberately ONE task, not split further: the register addition,
its `inFlight`/`dcIdleForMaint` extension, and its verification are not
independently meaningful/reviewable milestones — a partial version (e.g.
registers added but `dcIdleForMaint` not yet extended) is actively
INCORRECT, not merely incomplete, so splitting would create a
reviewable-but-broken intermediate state. Matches Slice 1's plan structure
and rationale exactly.
