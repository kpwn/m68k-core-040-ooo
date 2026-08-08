# FMax "LS/ROB Lever C": hoist per-entry write-select for ROB fault registers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hoist a named, `keep`-attributed per-entry write-select for
`RobPlugin.scala`'s `lsFaultCompletion`/`sqFaultCompletion` write ports,
so the late `sqFaultCompletion.valid` node stops sharing a driver with
the 2019-load `faultAddrStore` data-mux array. Pure logic restructuring
(bit-identical function, SpinalHDL already elaborates this exact
one-hot form) — the only new netlist object is a synthesis directive.

**Architecture:** Replace the two dynamic-index write blocks
(`RobPlugin.scala:750-763`, `:768-777`) with explicit per-entry `when`
loops keyed on named, `keep`-attributed select vectors
(`lsFaultSel`/`sqFaultSel`), preserving exact textual/priority order.

## Global Constraints

- Design spec (read in full before starting — especially §5.4's fanout-
  based acceptance criterion and fallback ladder, and §5.5's rejected
  alternatives which must NOT be revisited):
  `docs/superpowers/specs/2026-08-08-fmax-lsrob-leverc-writeenable-flatten-design.md`
- **Lever B is explicitly OUT OF SCOPE** — do not propose registering
  the deferred-replay ROB completion ports; it has a documented
  IRQ/NMI-mistiming landmine (`LsEuPlugin.scala:1646-1656`).
- Only ports #1 (`lsFaultCompletion`) and #2 (`sqFaultCompletion`) are
  restructured — ports #3/#4/#5 (`euFaultCompletion`, `alloc0`, `alloc1`)
  are UNCHANGED (confirmed not on the late cone).
- Textual order must be preserved EXACTLY (`lsFaultSel` loop, then
  `sqFaultSel` loop, both before `euFaultCompletion`/`alloc0`/`alloc1`)
  so SpinalHDL's last-assign priority (alloc1 > alloc0 > eu > sq > ls) is
  bit-for-bit unchanged.
- The acceptance criterion is FANOUT, not just slack — report the
  measured `FLAT_PIN_COUNT` of the `sqFaultCompletion.valid`/
  `lsFaultCompletion.valid` cone before and after; a null slack result
  without this measurement is uninterpretable per the design spec.
- `~/sbt/bin/sbt compile` must stay clean throughout.
- `ExecuteLockStepSpec` must stay at 390/394.
- Full ported corpus (~870 tests) must show zero new regressions.
- **This lever must be gated COMBINED with the others, not solo** —
  fixing this family alone leaves top-line WNS/FMax unchanged (a
  different, independent family holds WNS).

---

### Task 1: Implement the write-select hoist, verify bit-identical equivalence

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala` (replace the two
  write blocks at `:750-763`/`:768-777`; re-verify exact live line
  numbers before editing)

**Interfaces:**
- Consumes: `lsFaultCompletion`/`sqFaultCompletion` (existing `Flow`
  ports, unchanged types), `depth` (existing ROB depth constant).
- Produces: `lsFaultSel`/`sqFaultSel` (`Vec(Bool(), depth)`, new, kept-
  attributed), consumed by the per-entry write loops replacing the old
  dynamic-index blocks. No change to any read site or to the storage
  arrays' types.

- [ ] **Step 1: Re-verify exact current line numbers and the two write blocks' exact bodies**

```bash
grep -n "lsFaultCompletion\|sqFaultCompletion" src/main/scala/m68k040/rob/RobPlugin.scala
```
Confirm the design spec's citations (`:750-763`, `:768-777`) still match,
and copy the EXACT field-assignment bodies (faulted, faultVec, faultAddr,
faultWr, faultSize, faultSup, faultAtc, faultInstr) verbatim — these must
not change, only the write-select mechanism around them.

- [ ] **Step 2: Implement the hoisted select vectors and rewritten write blocks**

Per the design spec's §5.1 code block exactly:
```scala
val lsFaultOh  = UIntToOh(lsFaultCompletion.payload.robId, depth)
val sqFaultOh  = UIntToOh(sqFaultCompletion.payload.robId, depth)
val lsFaultSel = Vec(Bool(), depth)
val sqFaultSel = Vec(Bool(), depth)
for (i <- 0 until depth) {
  lsFaultSel(i) := lsFaultCompletion.valid && lsFaultOh(i)
  sqFaultSel(i) := sqFaultCompletion.valid && sqFaultOh(i)
  lsFaultSel(i).setName(s"lsFaultSel_$i").addAttribute("keep", "true")
  sqFaultSel(i).setName(s"sqFaultSel_$i").addAttribute("keep", "true")
}
for (i <- 0 until depth) {
  when(lsFaultSel(i)) { /* exact body of old :751-762, robId -> i */ }
}
for (i <- 0 until depth) {
  when(sqFaultSel(i)) { /* exact body of old :769-776, robId -> i */ }
}
```
Preserve exact textual order relative to `euFaultCompletion`/`alloc0`/
`alloc1`'s existing write blocks (do not reorder anything else in the
file). Comment the block citing this plan/spec, the `keep` rationale, and
the load-decomposition finding (2019 of 2083 loads are `faultAddrStore`
data muxes, not the enable decode).

- [ ] **Step 3: Compile**

```bash
~/sbt/bin/sbt compile
```

- [ ] **Step 4: The equivalence proof — do this FIRST, before any test suite**

Generate `generated/M68kFullCoreSynth.v` before and after this change (in
separate isolated worktrees or via git stash) and diff the
`RobPlugin_logic_fault*Store_*` always-block bodies. Expected diff: ONLY
the introduction of the named `lsFaultSel_*`/`sqFaultSel_*` wires (with
their `(* keep *)` attributes) and substitution of those wires for the
old inline conditions — no change to which entry is written, with what
data, in what priority order. If the diff shows anything else, STOP —
the restructure is not the identity it claims to be.

---

### Task 2: Full verification suite, netlist acceptance check, synth-gate

**Files:**
- Test: locate ROB/exception test coverage via
  `grep -rl "Rob\|Exception\|LsFault\|Precise" src/test/scala/` (do not
  guess file names)

- [ ] **Step 1: Existing ROB/exception suites**

Run whatever's found in the grep above. Confirm full pass.

- [ ] **Step 2: Targeted same-cycle multi-fault test**

Locate or ADD a test that constructs a precise-store-drain bus error
(port #2) concurrent with a live MMU fault on a different, younger
access (port #1) landing the SAME cycle for DIFFERENT robIds — this is
exactly the case ports #1/#2 exist for, and exactly the failure mode a
broken select-vector rewrite would produce ("two same-cycle writes
collapse into one"). Start from Task P2.4 / precise-drain test names,
and the `irq-nmi`/`bsr-loop-mispredict` tests named at
`LsEuPlugin.scala:1616-1626`/`:1646-1656`. If no existing test covers
this, write one.

- [ ] **Step 3: Targeted ported tests**

Grep the corpus for `bus_err`, `addr_err`, `mmu`, `access_fault`,
`trapv`, `chk`, `div0` (fault/exception families). Run explicitly before
the full sweep.

- [ ] **Step 4: `ExecuteLockStepSpec` full suite**

```bash
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec"
```
Expected: 390/394.

- [ ] **Step 5: Full ported test corpus (isolated worktree, before/after)**

`tools/fuzz/ported-sweep-parallel.sh` against the current baseline.
Expect byte-identical fail-name lists.

- [ ] **Step 6: Netlist acceptance check (REQUIRED, not optional)**

After OOC or post-route synthesis, check the `FLAT_PIN_COUNT`/fanout of
the `sqFaultCompletion.valid`/`lsFaultCompletion.valid` cone inside the
ROB fault region:
```tcl
get_property FLAT_PIN_COUNT [get_nets -hier -filter {NAME =~ "*sqFaultSel_*"}]
```
- **PASS**: no net in this cone has fanout > ~200 (baseline: 2084), and
  family-3's worst endpoint improves by >= 0.25ns.
- **INCONCLUSIVE**: fanout stays > 1000 (tool absorbed the `keep`
  anyway) — escalate per the design spec's fallback ladder: (1) `keep`
  -> `DONT_TOUCH`, re-measure; (2) if still inconclusive, add
  `MAX_FANOUT(128)` on `sqFaultCompletionPort.valid`
  (`LsEuPlugin.scala:1689`). Report which escalation (if any) was
  needed.
- **FAIL**: fanout drops as intended but slack doesn't improve >= 0.15ns
  — the fanout hypothesis is wrong for this placement; revert this lever
  (matching how Slice 3 was reverted after a disappointing result) and
  report honestly.

- [ ] **Step 7: OOC-synth-only gate**

```bash
vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl
```
Report the delta on the targeted `tagMem -> fault*Store` family
specifically (expect -1.521ns -> ~-1.15ns, central estimate; range -1.25
to -1.00), not just top-line.

- [ ] **Step 8: Real post-route gate**

```bash
vivado -mode batch -source synth/impl_FullCore.tcl
```
Same targeted-family reporting, plus the fanout check from Step 6.

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/m68k040/rob/RobPlugin.scala \
        src/test/scala/<any modified/new test files>
git commit -m "rob: hoist per-entry write-select for fault registers, reduce late-node fanout (FMax LS/ROB Lever C)"
```

## Self-Review Note

Two tasks (implement+prove-equivalence, then full verification) because
the equivalence proof (Task 1 Step 4) is cheap and is the whole
correctness argument for what's otherwise a "trust the synthesizer"
change — it should gate before spending time on the full test suite, not
be discovered as a problem after.
