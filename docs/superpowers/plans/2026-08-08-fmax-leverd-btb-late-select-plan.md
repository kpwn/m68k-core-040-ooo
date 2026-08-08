# FMax "Lever D": BTB L0-late-select speculative lookup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace slot-1's BTB lookup (addressed by `decodePc + 2*L0`,
requiring `L0` before the read can start) with a speculative 9-way read
off the already-registered `decodePc` alone, selecting among the 9
precomputed results with `L0` only at the point of consumption. Removes
`L0` from the BTB read's critical arc, closing both the `headPtr`
feedback loop and Frontend Lever C's own residual `slot1Valid` floor
(confirmed to share identical physical cells).

**Architecture:** 9 parallel speculative `readAsync` accesses (one per
legal `L0` value) off `decodePc` alone, each producing its own
`predTaken`/target result; a late combinational mux selects among the 9
results using `L0`, once `L0` is available. Bit-identical final result to
today's single-addressed-read approach — a pure reordering of when `L0`
is consumed, not a change to what's computed. No register added inside
the `headPtr` loop — zero IPC/loop-throughput cost.

## Global Constraints

- Design spec (read in full before starting):
  `docs/superpowers/specs/2026-08-08-fmax-leverd-btb-late-select-design.md`
- This is a PURE combinational restructuring — no register added inside
  the `headPtr` loop, no change to prediction accuracy/semantics. The
  bit-identical-result proof is the load-bearing verification.
- `fed.packets -> pushReg` (a separate, independent bottleneck) is
  explicitly OUT OF SCOPE — do not attempt to fix it here.
- `~/sbt/bin/sbt compile` must stay clean throughout.
- `ExecuteLockStepSpec` must stay at 390/394.
- Full ported corpus (~870 tests) must show zero new regressions.
- This lever ALONE is not expected to clear 200MHz (a separate family
  caps the design around ~189.8MHz even with all four in-flight levers
  combined) — do not judge success on a standalone top-line number.

---

### Task 1: Determine the exact candidate set and read structure

**Files:**
- Read: `src/main/scala/m68k040/frontend/Btb.scala` (full file)
- Read: `src/main/scala/m68k040/frontend/Aligner.scala` (`L0`/`WINDOW`
  definitions, and every consumer of the current BTB slot-1 read result)

- [ ] **Step 1: Confirm `L0`'s legal range**

Re-derive from live `Aligner.scala` source (do not assume the design
spec's "L0 ∈ 1..9" phrasing without checking) — what is `WINDOW`, what is
`L0`'s actual bit width and legal value range at the point it's used to
address the BTB?

- [ ] **Step 2: Confirm every consumer of the current slot-1 BTB read**

What exactly does the current `decodePc + 2*L0`-addressed read produce
(`predTaken`? a target PC? both?), and what consumes it downstream? This
determines exactly what must be replicated 9-ways and what the late-mux
must select.

- [ ] **Step 3: Confirm the 3 `readAsync` ports' current roles**

`Btb.scala` has THREE `readAsync` ports per the design spec — confirm
which port is slot-0's, which is slot-1's (the one this lever changes),
and what the third is used for (do not accidentally touch it).

---

### Task 2: Implement the speculative 9-way read + late select, verify, synth-gate

**Files:**
- Modify: `src/main/scala/m68k040/frontend/Btb.scala` (add the 8
  additional speculative read ports, or however Task 1 determines the
  read structure should be shaped — e.g. a `Vec` of 9 read results)
- Modify: `src/main/scala/m68k040/frontend/Aligner.scala` (the `L0`-late
  select mux, replacing the current `decodePc + 2*L0` address computation
  at the read site)
- Test: locate existing BTB/branch-prediction test coverage (grep, don't
  guess file names)

**Interfaces:**
- Consumes: `decodePc` (existing register, unchanged), `L0` (existing
  signal, now consumed LATER — at the mux, not at the read address).
- Produces: the same final `predTaken`/target result type as today,
  bit-identical for every reachable `(decodePc, L0)` pair.

- [ ] **Step 1: Implement the 9-way speculative read**

Add read ports (or restructure the existing slot-1 port) to read all 9
`L0`-candidate BTB entries off `decodePc` alone, each producing its own
result.

- [ ] **Step 2: Implement the late-`L0`-select mux**

Replace the current address computation with a mux selecting among the 9
speculative results using `L0`, at the point the result is consumed.

- [ ] **Step 3: Compile**

```bash
~/sbt/bin/sbt compile
```

- [ ] **Step 4: The bit-identical-result proof**

Add a directed (ideally exhaustive over the BTB's address space, matching
this round's established precedent) test comparing OLD (single-addressed
read) vs. NEW (9-way speculative + late select) for every reachable
`(decodePc, L0)` combination. This is the load-bearing verification.

- [ ] **Step 5: Run it**

```bash
~/sbt/bin/sbt "testOnly <the spec>"
```
Expected: PASS, with a reported count proving genuine exhaustive coverage
(not just "no assertion failed").

- [ ] **Step 6: Existing BTB/branch-prediction test coverage**

Run whatever existing tests cover this path. Confirm full pass.

- [ ] **Step 7: Targeted branch-prediction/redirect-heavy ported tests**

Run these explicitly before the full sweep — this is exactly the area a
BTB timing bug would manifest as wrong-target mispredicts, not just slow
FMax.

- [ ] **Step 8: `ExecuteLockStepSpec` full suite**

```bash
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec"
```
Expected: 390/394.

- [ ] **Step 9: Full ported test corpus (isolated worktree, before/after)**

`tools/fuzz/ported-sweep-parallel.sh` against the current baseline.
Expect byte-identical fail-name lists.

- [ ] **Step 10: OOC-synth-only gate**

```bash
vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl
```
Report the delta on BOTH the `headPtr` loop family AND Frontend Lever C's
`slot1Valid` floor specifically (both should improve, per the shared-
cells finding) — not just the top-line number.

- [ ] **Step 11: Real post-route gate**

```bash
vivado -mode batch -source synth/impl_FullCore.tcl
```
Same targeted-family reporting as Step 10.

- [ ] **Step 12: Commit**

```bash
git add src/main/scala/m68k040/frontend/Btb.scala \
        src/main/scala/m68k040/frontend/Aligner.scala \
        src/test/scala/<the modified/new spec file(s)>
git commit -m "frontend: BTB L0-late-select speculative slot-1 lookup (FMax Lever D)"
```

## Self-Review Note

Two tasks (investigation, then implementation) because the exact read/mux
structure genuinely depends on Task 1's determination of `L0`'s real
range and the current read's exact consumers — not a formality, real
prerequisite information.
