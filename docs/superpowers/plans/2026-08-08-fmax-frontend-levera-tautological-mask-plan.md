# FMax "Frontend Lever A": delete tautological i==0 word mask — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete `Aligner.scala`'s tautological `i==0` zero-mask from both
the slot-0 and slot-1 word-select loops, removing `L0`/`L1` from the
opword's availability chain and unblocking the `OperationDecoder`/
`EaDecoder` decode cone by a netlist-measured ≥0.318ns on the design's
current WNS-holding path.

**Architecture:** Two ~1-line RTL changes (one per loop, at `i==0` only),
backed by a mandatory exhaustive 65536-opword proof that
`PredecodeWord.classify(...).simple` never coincides with
`lenWords === 0` — the property the deletion relies on.

## Global Constraints

- Design spec (read in full before starting):
  `docs/superpowers/specs/2026-08-08-fmax-frontend-levera-tautological-mask-design.md`
- `i >= 1` masks in BOTH loops are UNTOUCHED — load-bearing per Slice 3's
  own post-mortem correctness finding (the offloaded `dstEa` computation
  depends on the zero-fill beyond `L1`/`L0`).
- The exhaustive proof test is THE load-bearing verification for this
  slice — a partial/sampled version does not satisfy this plan.
- This project's own `PredecodeWordSpec` "exhaustive" test is KNOWN to
  abort at first mismatch — do not reuse it or its pattern. Follow
  `MicroOpAssemblerOffloadSpec`'s post-Slice-3 exhaustive test (runs all
  65536 to completion, reports a count) as the precedent.
- `~/sbt/bin/sbt compile` must stay clean throughout.
- `AlignerSpec` 9/9, `FetchAlignSpec` same 1 pre-existing unrelated
  failure (this session's standing baseline).
- `ExecuteLockStepSpec` must stay at 390/394.
- Full ported corpus (~870 tests) must show zero new regressions vs. the
  current baseline.
- This slice ALONE is expected to move WNS from -2.062ns to ~-1.5 to
  -1.8ns, at which point a DIFFERENT family becomes binding. Do not judge
  this task's success on whether 200MHz is cleared standalone — report the
  delta honestly; the real gate is the combined measurement with LS/ROB
  Lever A.

---

### Task 1: Determine the exhaustive proof's exact scope

**Files:**
- Read only: `src/main/scala/m68k040/frontend/PredecodeWord.scala` (full
  file)

- [ ] **Step 1: Read every `r.simple := True` site in `PredecodeWord.scala`**

The grounding report's own grep found sites at (approximately, re-verify
against live line numbers) lines
`209,211,234,255,263,274,289,310,327,338,356,449,480,487,488,497,509,512,514,517,521,524,535,550,586,600,615,626,663,687,697,703,710,716`.
For EACH site, determine: does the co-assigned `r.lenWords` value at that
site depend on `extW`/`extW2`/`extW3`'s CONTENT (e.g. a `bdSize`/
`odPresent` field read from an ext word), or only on `op`'s own bits
(mode/reg/size fields) and the `extWKnown`/`extW2Known`/`extW3Known`
VALIDITY flags (not content)?

- [ ] **Step 2: Decide the exhaustive sweep's dimensions**

If EVERY `lenWords`-affecting-content path is provably independent of ext-
word CONTENT (only validity flags matter, which are Scala-level constants
for the real IcachePlugin caller and can be fixed to `True` for the sweep,
matching how the opword's own real caller always supplies valid ext
words) — a 65536-opword-only sweep (fixed representative ext words,
`extWValid`/`extW2Valid`/`extW3Valid` = True) suffices. If any site's
`lenWords` value genuinely varies with ext-word CONTENT (e.g. `bdSize`
determining whether `lenWords` is 2 vs 3 vs 4), the sweep must ALSO cover
the relevant ext-word bit ranges for at least the `simple`-producing
branches that depend on them — determine the minimal sufficient additional
sweep dimension (likely just a few relevant bits of `extW`, not a full
16-bit sweep, to keep the test tractable) and document the reduction's
correctness (why the untested ext-word bits cannot affect the `simple`/
`lenWords>=1` property).

Document your findings inline as a comment in the new test (Task 2) —
this determination IS the risk-reduction work this plan asks for; do not
skip documenting the reasoning.

---

### Task 2: Add the exhaustive proof test

**Files:**
- Test: locate the existing `PredecodeWordSpec` (or equivalent) via
  `grep -rl "PredecodeWord" src/test/scala/` and add the new test there,
  OR create a clearly-named new file if `PredecodeWordSpec` isn't a good
  fit (check its existing structure first) — match this project's
  established test-file conventions.

**Interfaces:**
- Consumes: `PredecodeWord.classify(...)` (existing, unchanged signature).

- [ ] **Step 1: Write the exhaustive test**

Following `MicroOpAssemblerOffloadSpec`'s post-Slice-3 exhaustive-opword
pattern (run to completion, report a count — do NOT early-abort): for
every opword (and, per Task 1's determination, every relevant ext-word
combination if needed), elaborate `PredecodeWord.classify(...)` and assert
`simple === True -> lenWords >= 1` (equivalently: `!(simple && lenWords
=== 0)`). Report the total count checked and the count where `simple ===
True`, matching the style of the `OperationDecoderSpec` exhaustive sweep
this project already has (from the Slice-3-revert's own precedent —
locate it via `grep -rl "65536\|EADST" src/test/scala/` for the exact
style to match).

- [ ] **Step 2: Run it, confirm it passes and reports a real count**

```bash
~/sbt/bin/sbt "testOnly <the spec>"
```
Expected: PASS, with a reported count confirming all opwords (and any
additional ext-word dimension from Task 1) were actually checked — not
just "no assertion failed" (which could also mean the loop silently
iterated zero times due to a bug).

- [ ] **Step 3: Mutation-check the test is not vacuous**

In an isolated worktree, temporarily break the property (e.g. find a
`r.simple := True` site and change its co-assigned `lenWords` to `0`) and
confirm the new test FAILS with a clear violation report. Restore
afterward.

---

### Task 3: Delete the `i==0` mask in both loops, verify, synth-gate

**Files:**
- Modify: `src/main/scala/m68k040/frontend/Aligner.scala` (the `i==0`
  case of the slot-0 loop around line 151-157, and the slot-1 loop around
  line 214-219; re-verify exact live line numbers before editing)

**Interfaces:**
- No signature changes — `r.slot0.words`/`r.slot1.words` keep their
  existing types; only the `i==0` assignment's logic changes from
  conditional to unconditional.

- [ ] **Step 1: Re-verify exact current line numbers**

```bash
grep -n "r.slot0.words(i)\|r.slot1.words(i)\|when(U(i) <" src/main/scala/m68k040/frontend/Aligner.scala
```

- [ ] **Step 2: Edit the slot-1 loop**

Change the `i==0` iteration of the slot-1 loop (around line 214-219) so
`r.slot1.words(0)` is assigned unconditionally (`words(idx)` where `idx =
(L0 +^ U(0)).resize(4)`, i.e. effectively `words(L0)`), while `i >= 1`
keeps the existing `when(U(i) < L1) { ... } .otherwise { ... := 0 }`
structure completely unchanged. Add a comment citing this plan/spec and
the exhaustive proof test that justifies the deletion.

- [ ] **Step 3: Edit the slot-0 loop**

Same shape, against `L0` (not `L1`), around line 151-157.

- [ ] **Step 4: Compile**

```bash
~/sbt/bin/sbt compile
```
Expected: clean.

- [ ] **Step 5: `AlignerSpec` and `FetchAlignSpec`**

```bash
~/sbt/bin/sbt "testOnly m68k040.frontend.AlignerSpec"
~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
Expected: `AlignerSpec` 9/9; `FetchAlignSpec` the same single pre-existing
failure as this session's standing baseline. A DIFFERENT failure means
investigate before proceeding.

- [ ] **Step 6: Targeted mem-indirect/complex-instruction ported tests**

Grep the ported corpus for `memind`/`idx`/`full`/`complex`-family tests
(this session's historically fragile area for exactly this kind of
frontend framing change) and run them explicitly before the full sweep.

- [ ] **Step 7: `ExecuteLockStepSpec` full suite**

```bash
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec"
```
Expected: 390/394, the same 4 pre-existing failures.

- [ ] **Step 8: Full ported test corpus (isolated worktree, before/after)**

Use `tools/fuzz/ported-sweep-parallel.sh` against the current baseline
commit (record its SHA before starting Task 3 Step 2). Compare sorted
fail-name lists — expect byte-identical.

- [ ] **Step 9: OOC-synth-only gate**

```bash
vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl
```
Record WNS/FMax. Note this session's finding that OOC and post-route can
disagree for this design's route-dominated paths — report honestly either
way, post-route is binding.

- [ ] **Step 10: Real post-route gate**

```bash
vivado -mode batch -source synth/impl_FullCore.tcl
```
Record WNS/FMax. Confirm whether the design's WNS moved from -2.062ns
toward the expected ~-1.5 to -1.8ns range, and identify the new binding
family (expected to be either the LS/ROB `eaAuto`/`tagMem` family at
~-1.779/-1.761ns, or the `ucPendPkt_words_0` family at ~-1.833ns per the
grounding report's prediction — report whichever it actually is).

- [ ] **Step 11: Commit**

```bash
git add src/main/scala/m68k040/frontend/Aligner.scala \
        src/test/scala/<the modified/new spec file(s)>
git commit -m "frontend: delete tautological i==0 word mask in Aligner slot-0/slot-1 loops (FMax Frontend Lever A)"
```

## Self-Review Note

Split into 3 tasks (unlike this initiative's usual one-task-per-slice
pattern) because the proof-scoping determination (Task 1) is a genuine
prerequisite decision that shapes what the test in Task 2 must cover, and
Task 2's test must exist and pass BEFORE Task 3's RTL deletion is
justified — these have a real, not artificial, dependency order. Task 3
without Tasks 1-2 would be exactly the kind of "grep-level evidence, not
proof" the design spec explicitly warns against.
