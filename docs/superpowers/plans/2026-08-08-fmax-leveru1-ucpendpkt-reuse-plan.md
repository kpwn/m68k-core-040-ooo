# FMax "Lever U1": reuse registered spec for ucPendPkt resume — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `DecodeStage.scala`'s re-decode of a stashed slot-1
opword (`OperationDecoder.decode(ucPendPkt.words(0))`, driving the
µcode-resume handoff) with a stashed copy of the ALREADY-COMPUTED,
provably-identical `spec` value already carried through
`fed.payload.specs(1).spec`. Deletes a redundant `OperationDecoder`
instance and its ~1.6ns critical path contribution, at zero latency cost.

**Architecture:** Determine the exact stash-register shape by locating
every `ucPendPkt` writer site and confirming each one's source opword
already has a computed `spec` value available at that same site; capture
that value into a new stash register(s) instead of re-deriving it later.

## Global Constraints

- Design spec (read in full before starting):
  `docs/superpowers/specs/2026-08-08-fmax-leveru1-ucpendpkt-reuse-design.md`
- This lever alone is NOT expected to move top-line FMax — two other
  independent families are equally/more critical and are being addressed
  in parallel. Do not judge this task's success on a standalone post-route
  number; the success criterion is the targeted family's own slack
  improving causally, with zero regressions.
- The stash/replay control flow itself (when an instruction gets stashed,
  how long held, what triggers replay) is UNCHANGED — this lever only
  changes what VALUE drives the resume decision once a stash exists.
- `~/sbt/bin/sbt compile` must stay clean throughout.
- `ExecuteLockStepSpec` must stay at 390/394.
- Full ported corpus (~870 tests) must show zero new regressions vs. the
  current baseline (`.superpowers/sdd/progress-fmax-levers-2026-08-08.md`).

---

### Task 1: Investigate the exact stash mechanism and prove the reuse is sound

**Files:**
- Read: `src/main/scala/m68k040/decode/DecodeStage.scala` (full file)
- Read: `src/main/scala/m68k040/decode/OperationDecoder.scala` (confirm
  `decode`'s full return type/fields)

- [ ] **Step 1: Locate every `ucPendPkt` writer**

```bash
grep -n "ucPendPkt" src/main/scala/m68k040/decode/DecodeStage.scala
```
For each write site, determine: what opword does it write from, and is a
`spec`/`Offload` value for that SAME opword already computed and
available (registered) at that exact site? Document each site's answer —
this determination is the actual risk-reduction work this task exists to
do; do not skip it.

- [ ] **Step 2: Confirm `OperationDecoder.decode`'s consumed fields at the re-decode site**

At `DecodeStage.scala:886` (re-verify live line number), confirm exactly
which field(s) of `OperationDecoder.decode`'s return value are consumed
downstream (just `size`? `op`? something else?). If more than `spec`'s
fields already carried by `fed.payload.specs(1).spec` are needed, this
changes what must be stashed — update the design accordingly and note the
deviation in your report.

- [ ] **Step 3: Decide the stash-register shape**

Based on Steps 1-2: one shared stash register, or per-writer-site
registers? Document the decision and why.

---

### Task 2: Implement the stash + reuse, verify, synth-gate

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodeStage.scala` (add the
  stash register(s) from Task 1's determination; replace the re-decode at
  the resume-handoff site with a read of the stashed value)
- Test: locate the `DecodeStage`-adjacent test coverage for the µcode-
  resume/stash path (grep for existing specs covering this — this
  project's established practice, do not guess file names)

**Interfaces:**
- Consumes: whatever `spec`/`Offload` value Task 1 determines is already
  available at each `ucPendPkt` writer site.
- Produces: the stash register(s), consumed at the resume-handoff site
  instead of a fresh `OperationDecoder.decode(...)` call.

- [ ] **Step 1: Implement the stash + reuse per Task 1's determination**

Add the stash register(s), capture the already-computed `spec` value at
each writer site, and replace the re-decode at the resume-handoff site
with a read of the stash. Comment citing this plan/spec and the
provable-identity argument.

- [ ] **Step 2: Compile**

```bash
~/sbt/bin/sbt compile
```

- [ ] **Step 3: Prove the correctness claim**

Add a directed (or, if tractable, exhaustive — matching this round's
established precedent of `PredecodeSimpleLenSpec`/`OperationDecoderSpec`)
test comparing the OLD re-decoded value against the NEW stashed value for
every reachable stash scenario. This is the load-bearing verification for
this task.

- [ ] **Step 4: Existing DecodeStage/stash/µcode test coverage**

Run whatever existing tests cover this path (located in Task 1). Confirm
full pass.

- [ ] **Step 5: Targeted µcode/complex-instruction ported tests**

Run these explicitly (the stash mechanism exists specifically for
multi-µop/µcode-engine-resume instructions) before the full sweep.

- [ ] **Step 6: `ExecuteLockStepSpec` full suite**

```bash
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec"
```
Expected: 390/394.

- [ ] **Step 7: Full ported test corpus (isolated worktree, before/after)**

`tools/fuzz/ported-sweep-parallel.sh` against the current baseline
commit. Expect byte-identical fail-name lists.

- [ ] **Step 8: OOC-synth-only gate**

```bash
vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl
```
Report the delta on the targeted `ucPendPkt -> decodePc` family
specifically, not just the top-line number (which is not expected to
move — a different family currently holds WNS).

- [ ] **Step 9: Real post-route gate**

```bash
vivado -mode batch -source synth/impl_FullCore.tcl
```
Same reporting approach as Step 8 — targeted family's slack, not top-line.

- [ ] **Step 10: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodeStage.scala \
        src/test/scala/<the modified/new spec file(s)>
git commit -m "decode: reuse registered spec instead of re-decoding ucPendPkt resume (FMax Lever U1)"
```

## Self-Review Note

Two tasks (investigation, then implementation) because the exact stash
mechanism genuinely isn't known until Task 1's grep/read is done — the
design spec explicitly left this as an "implementation-time
determination" rather than guessing. Matches this round's established
practice of investigation-before-RTL-change for anything where the exact
mechanism isn't already nailed down by prior netlist grounding.
