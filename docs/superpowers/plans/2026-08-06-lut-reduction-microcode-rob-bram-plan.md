# LUT Reduction: Microcode ROM + ROB Alloc-Field BRAM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** convert the microcode ROM's resolve-then-mux pattern and 9 confirmed-safe ROB per-entry register arrays into real BRAM, with zero net pipeline latency added.

**Architecture:** two independent slices (Feature A: microcode ROM, Feature B: ROB alloc fields), implemented in that order per explicit user priority. See the design spec for full rationale: `docs/superpowers/specs/2026-08-06-lut-reduction-microcode-rob-bram-design.md` — every task below assumes that spec has been read.

**Tech Stack:** SpinalHDL/Scala, Xilinx UltraScale+ BRAM primitives.

## Global Constraints

- Latency-neutral: neither feature may add a pipeline cycle beyond what's already implicit in today's design (see spec's timing analysis for Feature A).
- `ExecuteLockStepSpec` must stay at 390/394 with the exact same 4 documented pre-existing failures throughout every task.
- Full ported-test corpus: zero new regressions at the end of each slice.
- No ISA/architectural behavior change — pure microarchitecture/area change.
- OOC + post-route synth gate (≥250MHz) required before either slice *closes* — currently blocked on Vivado availability; do not let this block task-by-task implementation progress, but do not declare a slice fully closed without it.
- Feature A's mandatory per-row equivalence test (spec, Feature A section) is a hard gate — Task A5 (the production cutover) must not proceed until Task A4's equivalence test passes for every ROM row.

---

## Slice A: Microcode ROM as real BRAM

### Task A1: `DescBits` hardware bundle + Scala→hardware conversion

**Files:**
- Modify: `src/main/scala/m68k040/decode/Microcode.scala` (add `DescBits` bundle + `Desc.toBits`-style conversion, near the existing `Desc` case class at line 202)

**Interfaces:**
- Produces: `case class DescBits() extends Bundle` mirroring every field of `Desc` (`Microcode.scala:202-`, read the full class before starting — it has ~25 fields spanning `SpinalEnum` types (`UOp`, `Mem`, `Auto`, `Sel`, `Sz`), `Boolean`, and `Int` fields like `bfStoreForm`). Also produces a function `def descToBits(d: Desc): DescBits.T` (exact name/signature at implementer's discretion, but must be usable at Scala elaboration time to build a `Mem`'s initial content — i.e. it must return a hardware-literal-valued `DescBits`, not use runtime SpinalHDL `when`/assignment).

- [ ] **Step 1: Read `Desc`'s complete field list**

Read `src/main/scala/m68k040/decode/Microcode.scala` lines 202 through the end of the `Desc` case class declaration (locate the closing paren after the field list). Note every field's Scala type. Also read the `UOp`/`Mem`/`Auto`/`Sel`/`Sz` type definitions (search for `object UOp`, `sealed trait UOp` or similar in the same file) to find their existing SpinalHDL `SpinalEnum` encodings if they already have one, or note that a new `SpinalEnum` must be defined for each if these are currently plain Scala `sealed trait`/case-object hierarchies (not yet hardware types).

- [ ] **Step 2: Define `DescBits`**

For each `Desc` field, add a same-named field to `DescBits`:
- `Boolean` fields → `Bool()`
- `Int` fields (e.g. `bfStoreForm: Int`) → `UInt(n bits)` sized to the field's actual value range (check every call site in `Microcode.rom`'s row definitions for the actual range used — do not guess a width, verify it against real usage)
- Scala `sealed trait`/case-object fields (`UOp`, `Mem`, `Auto`, `Sel`, `Sz`) → if a `SpinalEnum` already exists for the type, reuse it; if not, define one now, with one enum element per existing case object, preserving the exact same set of cases

- [ ] **Step 3: Write `descToBits`**

```scala
// Microcode.scala, near DescBits
def descToBits(d: Desc): DescBits = {
  val b = DescBits()
  b.uop    := <hardware enum literal matching d.uop>
  b.mem    := <...>
  // ... one line per field, direct 1:1 mapping
  b.useImm := Bool(d.useImm)
  b.bfStoreForm := U(d.bfStoreForm, <width> bits)
  // ...
  b
}
```

This runs at Scala elaboration time (called once per ROM row when building the `Mem`'s initial content in Task A2) — it produces a hardware-literal-valued `DescBits`, safe to use as `Mem` initial content.

- [ ] **Step 4: Compile-check only (no functional test yet — `DescBits`/`descToBits` are unused by any other code at this point)**

```bash
~/sbt/bin/sbt compile
```

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/decode/Microcode.scala
git commit -m "decode: add DescBits hardware bundle + descToBits conversion (LUT-reduction A1)"
```

---

### Task A2: `Mem(DescBits(), romSize)` with initial content, wired but unconsumed

**Files:**
- Modify: `src/main/scala/m68k040/decode/Microcode.scala` (add the `Mem`, near `romSize` at line 1402)

**Interfaces:**
- Consumes: `DescBits`, `descToBits` (Task A1), `Microcode.rom` (existing).
- Produces: `def romMem: Mem[DescBits]` (or equivalent accessor) — a `Mem(DescBits(), romSize)` whose initial content is `Vector(Microcode.rom.map(descToBits))`, with a synchronous (registered) read port. Does NOT yet replace `ucResolved`/`ucCurUop` in `DecodeStage.scala` — this task only builds and exposes the Mem; wiring it into the live decode path is Task A5, gated on Task A4's equivalence proof.

- [ ] **Step 1: Build the Mem**

```scala
// Microcode.scala, near romSize (line 1402)
val romMem = Mem(DescBits(), romSize) init Vector.tabulate(romSize)(i => descToBits(rom(i)))
```

Confirm the exact `Mem(...) init ...` syntax this SpinalHDL version expects (check another existing `Mem` construction with initial content elsewhere in the codebase for the project's established pattern, e.g. search for `.init(` or `initialContent` usage) and match it.

- [ ] **Step 2: Add a synchronous read accessor**

```scala
// A read-by-address method other code will call in Task A5. Signature at
// implementer's discretion, but the read MUST be registered (readSync,
// not a combinational/async read) to genuinely map to BRAM.
def readRow(addr: UInt): DescBits = romMem.readSync(addr)
```

- [ ] **Step 3: Compile-check**

```bash
~/sbt/bin/sbt compile
```

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/m68k040/decode/Microcode.scala
git commit -m "decode: build initialized romMem (unwired) for LUT-reduction A2"
```

---

### Task A3: `resolveFromBits` — the runtime-hardware-decoder rewrite of `resolve()`

**Files:**
- Modify: `src/main/scala/m68k040/decode/Microcode.scala` (new function, alongside existing `resolve` at line 1621 — do NOT delete or modify `resolve` itself yet, it's still needed for Task A4's equivalence test and as the production path until Task A5 cuts over)

**Interfaces:**
- Consumes: `DescBits` (Task A1), the existing `Ctx` type (unchanged).
- Produces: `def resolveFromBits(d: DescBits, ctx: Ctx, valid: Bool): DecodedUop` — functionally equivalent to `resolve(d: Desc, ...)` for every reachable `(row, ctx)` combination, but operating on `d` as a runtime hardware value.

- [ ] **Step 1: Read the complete existing `resolve()` function**

Read `src/main/scala/m68k040/decode/Microcode.scala` lines 1621 through the end of the function (285 lines total — locate the closing brace). This is your source of truth. Do not skim; every branch must be accounted for in the rewrite.

- [ ] **Step 2: Write `resolveFromBits` as a mechanical 1:1 transform**

For every Scala-level construct in `resolve()`, apply the matching runtime-hardware transform:
- `d.uop match { case UMove => X; case UAddDrop => Y; ... }` → `switch(d.uop) { is(DescBits.UopEnum.UMove) { X }; is(DescBits.UopEnum.UAddDrop) { Y }; ... }` (adjust to whatever enum-access syntax Task A1's `DescBits` actually uses)
- `if (d.someBoolField) X else Y` (where `X`/`Y` are hardware-signal-valued expressions) → `Mux(d.someBoolField, X, Y)` for combinational value selection, or `when(d.someBoolField) { X } .otherwise { Y }` for statement-style assignment — pick whichever matches the original's usage shape (value-producing vs. assignment-producing)
- Nested `if/else if/else` chains → nested `when/elsewhen/otherwise`, preserving the exact same priority order (first-match-wins, same as the original's top-to-bottom `if` chain)
- Any place `resolve()` calls a helper function (e.g. `selReg(d.srcA, ctx)`) with `d`'s Scala-typed field as an argument: check whether that helper itself branches on the Scala type (if so, it ALSO needs a hardware-typed variant — do not assume; read `selReg` and every other helper `resolve()` calls, and rewrite each one that takes a `Desc`-sourced Scala value)

Do NOT change the logical decisions themselves — this is a type-level conversion of an already-correct decision tree. If you find a place where the transform is genuinely ambiguous (e.g. a Scala `if` on a field that also feeds a compile-time-only concern like a bit width parameter, which cannot become a runtime `when`), STOP and report it rather than guessing — this is exactly the kind of edge case Task A1's width-checking should have already surfaced, but flag it if you find one anyway.

Preserve every explanatory comment from the original, adjusted only where the code shape itself changed (e.g. "this Scala `if` becomes a runtime `when` because...").

- [ ] **Step 3: Compile-check**

```bash
~/sbt/bin/sbt compile
```

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/m68k040/decode/Microcode.scala
git commit -m "decode: resolveFromBits — runtime-hardware-decoder rewrite of resolve() (LUT-reduction A3)"
```

---

### Task A4: Mandatory per-row equivalence test (hard gate before A5)

**Files:**
- Test: `src/test/scala/m68k040/decode/MicrocodeResolveEquivalenceSpec.scala` (new file)

**Interfaces:**
- Consumes: `Microcode.rom`, `Microcode.resolve` (old path), `Microcode.romMem`/`readRow`, `Microcode.resolveFromBits` (new path, Tasks A2-A3).
- Produces: a passing test suite proving bit-identical output between the old and new resolve paths for every ROM row, across a representative sweep of `Ctx` values.

- [ ] **Step 1: Design the `Ctx` sweep**

Read the `Ctx` type's full field list (search `Microcode.scala` for `case class Ctx` or wherever it's defined). Identify which `Ctx` fields actually influence `resolve()`'s output for at least one ROM row (read `resolve()` again with this lens) — you do not need to sweep fields `resolve()` never reads. For fields that DO matter, pick a small representative set of values (e.g. a boolean field: both values; a small enum: every case; a wide field like an address: 2-3 representative values, e.g. 0, a typical value, an edge value) — exhaustive cross-product across ALL rows x ALL ctx combinations is likely impractically large; instead, for EVERY row, sweep the ctx fields THAT ROW actually reads (this may differ row to row) exhaustively, and for fields it doesn't read, use one fixed value.

- [ ] **Step 2: Write the test**

For each of the ~250 rows (`0 until Microcode.romSize`), for each representative `Ctx` in that row's sweep:
- Build old-path output: `Microcode.resolve(Microcode.rom(i), ctx, True)`
- Build new-path output: `Microcode.resolveFromBits(Microcode.descToBits(Microcode.rom(i)), ctx, True)` (use `descToBits` directly here rather than a live `Mem` read, since this test is checking `resolveFromBits`'s logical correctness independent of the Mem plumbing — Task A5's own integration will separately confirm the Mem read wiring is correct)
- Assert every field of the resulting `DecodedUop` is equal between the two paths. Use a whitebox/direct-instantiation test style consistent with this project's existing decode-layer tests (check `MicroOpAssemblerSpec.scala` or similar for the established pattern of testing pure decode-logic functions without a full DUT).

- [ ] **Step 3: Run and fix any discrepancy**

```bash
~/sbt/bin/sbt "testOnly m68k040.decode.MicrocodeResolveEquivalenceSpec"
```

If ANY row/ctx combination shows a mismatch, this means Task A3's transform has a genuine bug (a missed branch, an inverted condition, a wrong enum mapping) — fix it in Task A3's code (do not weaken this test to work around a real discrepancy) and re-run until 100% of rows pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/scala/m68k040/decode/MicrocodeResolveEquivalenceSpec.scala
git commit -m "test: mandatory microcode resolve() equivalence gate (LUT-reduction A4)"
```

---

### Task A5: Cut over `DecodeStage.scala` to the Mem-based single-row read

**Files:**
- Modify: `src/main/scala/m68k040/frontend/DecodeStage.scala` (lines 1729-1735 — the `ucResolved`/`ucLastVec`/`ucIdx`/`ucCurUop` block)
- Modify: `src/main/scala/m68k040/decode/Microcode.scala` (remove `resolve()` — the old compile-time path — once A4's equivalence test proves `resolveFromBits` is its exact replacement; keep `descToBits` since A4's test still needs it for its own direct-construction comparison, or update A4's test to read via the Mem instead if that's cleaner — implementer's judgment, document whichever is chosen)

**Interfaces:**
- Consumes: `Microcode.romMem`/`readRow` (Task A2), `Microcode.resolveFromBits` (Task A3), the equivalence proof (Task A4).
- Produces: `ucCurUop` derived from exactly one `resolveFromBits` call per cycle, fed by one `Mem` read, instead of 250 parallel `resolve()` calls + a 250-wide mux.

- [ ] **Step 1: Re-derive the exact next-`ucPc` expression**

Read `DecodeStage.scala` around lines 1969-2001 (`ucBegin`'s `ucPc := ucRealEntry.resize(8)` and the straight-line `ucPc := ucPc + 1`) to get the EXACT expression(s) that compute `ucPc`'s next value. Per the spec's timing analysis, this same expression is what must feed `romMem`'s read address — confirm there is a single combinable "next ucPc" signal you can reuse, or if it's split across multiple `when` arms, construct the equivalent combinational "what will ucPc become" signal without duplicating logic (e.g. by computing it once as a named signal both the `Reg` assignment and the Mem read address reference, rather than reconstructing the `when` chain twice).

- [ ] **Step 2: Replace lines 1729-1735**

```scala
// OLD (removing):
// val ucResolved = Vec(Microcode.rom.map(d => Microcode.resolve(d, ucCtx, True)))
// val ucLastVec  = Vec(Microcode.rom.map(d => Bool(d.isLast)))
// val ucIdx      = ucPc.resize(log2Up(Microcode.romSize))
// val ucCurUop   = ucResolved(ucIdx)

// NEW:
val ucNextPcForRead = <the next-ucPc expression from Step 1>
val ucRowBits        = Microcode.readRow(ucNextPcForRead.resize(log2Up(Microcode.romSize)))
val ucCurUop          = Microcode.resolveFromBits(ucRowBits, ucCtx, True)
val ucCurLast         = <the isLast bit from ucRowBits, or a small side Mem/lookup if isLast wasn't
                          folded into DescBits in Task A1 — check whether Desc has an isLast field
                          and whether Task A1 included it>
```

Confirm `isLast` is either a `DescBits` field (if Task A1 included it) or handle it via whatever mechanism Task A1 actually chose — do not silently drop this bit, `ucLastVec`'s consumer(s) still need it.

- [ ] **Step 3: Remove the old `resolve()` function** from `Microcode.scala`, now that nothing in production code calls it (confirm via a repo-wide grep for `Microcode.resolve(` that only the equivalence test file references it, then update that test to no longer need it or keep a minimal reference copy inside the test file itself if useful for future re-verification — implementer's judgment).

- [ ] **Step 4: Compile + targeted microcoded-instruction tests**

```bash
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.decode.MicrocodeResolveEquivalenceSpec"
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" -- -z bf
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" -- -z movem
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" -- -z div
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" -- -z pack
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" -- -z bcd
```

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/m68k040/frontend/DecodeStage.scala src/main/scala/m68k040/decode/Microcode.scala
git commit -m "decode: cut over to Mem-based microcode ROM read, remove old 250x-resolve path (LUT-reduction A5)"
```

---

### Task A6: Full verification for Slice A

**Files:** none (verification-only)

- [ ] **Step 1: Full lock-step + full ported corpus**

```bash
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec"
```

Expect `ExecuteLockStepSpec` at 390/394 (same 4 pre-existing failures). Expect the ported corpus at zero new failures relative to the pre-slice baseline (check `.superpowers/sdd/progress.md`'s ledger, or the git history, for the most recent full-corpus pass/fail count and diff against it — follow this project's established pattern of substituting a fresh baseline when an old one has gone stale, documented extensively in the ledger).

- [ ] **Step 2: Record the outcome in the ledger** (`.superpowers/sdd/progress.md` if that file still exists and is this project's active ledger convention; otherwise follow whatever convention is current)

No commit for this task (verification-only).

---

## Slice B: ROB alloc-only fields into `payload`

### Task B1: Fold the 9 confirmed-safe fields into `RobPayload`

**Files:**
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala`

**Interfaces:**
- Consumes: the existing `payload = Mem(RobPayload(), depth)`, `allocUopVec(0)`/`allocUopVec(1)` (existing allocation path).
- Produces: `RobPayload` extended with 9 new fields; every read/write site of the 9 standalone `Vec.fill(depth)(...)` arrays redirected through `payload`.

- [ ] **Step 1: Read the current `RobPayload` and every write/read site of the 9 target fields**

Read `RobPlugin.scala:47-54` (`RobPayload`'s current fields) and re-confirm, via `grep -n "isRteStore\|sysOpStore\|sysReadDirStore\|sysDstArchStore\|sysRcStore\|needsSupStore\|firstStore\|pcStore\|sysKindStore" src/main/scala/m68k040/rob/RobPlugin.scala`, every read and write site of these 9 fields (the design spec's Feature B section lists the exact write-site line numbers as of this plan's writing, but line numbers may have drifted — re-verify against the live file, per this project's own standing pre-dispatch-drift-check discipline).

- [ ] **Step 2: Extend `RobPayload`**

```scala
// RobPlugin.scala, RobPayload — ADD these 9 fields (exact widths/types per the
// original Vec.fill declarations at RobPlugin.scala:211-297, re-verify each
// against the live file):
case class RobPayload() extends Bundle {
  val predNextPc = UInt(32 bits)
  val archRegId  = UInt(5 bits)
  val intNew     = UInt(6 bits); val intOld = UInt(6 bits); val intWrite = Bool()
  val nzvcNew    = UInt(4 bits); val nzvcOld = UInt(4 bits); val nzvcWrite = Bool()
  val xNew       = UInt(4 bits); val xOld = UInt(4 bits); val xWrite = Bool()
  val retireAlone = Bool()
  // -- folded from standalone Vec.fill arrays, LUT-reduction B1:
  val isRte      = Bool()
  val sysOp      = Bool()
  val sysKind    = m68k040.decode.SysKind()
  val sysReadDir = Bool()
  val sysDstArch = UInt(5 bits)
  val sysRc      = UInt(12 bits)
  val needsSup   = Bool()
  val first      = Bool()
  val pc         = UInt(32 bits)
}
```

- [ ] **Step 3: Redirect every write site**

For each of the two allocation arms (`tail` and `tail + 1`, currently writing e.g. `isRteStore(tail) := allocUopVec(0).isRte`), change to write the corresponding `payload` field via `payload.write(...)` at the SAME address/same cycle as the rest of `payload`'s existing allocation write (check exactly how `payload`'s own write currently happens — likely `payload.write(tail, ...)` with a full-bundle assignment, or per-field — match the existing pattern exactly so you're extending the SAME write, not adding a second competing write port).

- [ ] **Step 4: Redirect every read site**

Every place that currently reads e.g. `isRteStore(h0)` must instead read `payload.readAsync(h0).isRte` or however `payload`'s existing fields are read at `h0`/`h1`/other indices (check the exact existing read pattern for `payload`'s current fields, e.g. `archRegId`, and match it — likely there's already a `payloadAtH0`-style named intermediate signal; if so, extend it rather than adding N new separate reads).

- [ ] **Step 5: Delete the 9 standalone `Vec.fill(depth)(...)` declarations** (`RobPlugin.scala:211-297` region, re-verify exact lines against the live file) and their now-dead `.simPublic()` calls if any — but check whether any `simPublic()` debug hook on these signals (e.g. `pcStore.foreach(_.simPublic())` at line 297) is relied on by an existing test's whitebox peek; if so, add an equivalent `simPublic()` exposure on the new `payload`-derived read path so no existing test breaks.

- [ ] **Step 6: Compile + targeted tests**

```bash
~/sbt/bin/sbt compile
~/sbt/bin/sbt "testOnly m68k040.rob.*"
```

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/m68k040/rob/RobPlugin.scala
git commit -m "rob: fold 9 confirmed-alloc-only fields into payload Mem (LUT-reduction B1)"
```

---

### Task B2: Directed round-trip test

**Files:**
- Test: `src/test/scala/m68k040/rob/RobPluginSpec.scala` (or wherever this project's existing ROB directed tests live — check first)

- [ ] **Step 1: Find the existing ROB test file and its established DUT/poke conventions**, and add one directed test: allocate an entry with distinct, non-default values for all 9 folded fields, advance to retire, and assert every field reads back correctly at `h0`.

- [ ] **Step 2: Run it**

```bash
~/sbt/bin/sbt "testOnly <the located spec>"
```

- [ ] **Step 3: Commit**

```bash
git add <the test file>
git commit -m "test: directed round-trip for the 9 LUT-reduction-B1-folded ROB fields"
```

---

### Task B3: Full verification for Slice B

- [ ] **Step 1: Full lock-step + full ported corpus** (same commands as Task A6). Expect zero new regressions relative to Slice A's own closing baseline.
- [ ] **Step 2: Record the outcome in the ledger.**

No commit for this task.

---

## Final whole-branch review

After both slices' tasks are complete and independently task-reviewed, dispatch the final whole-branch code-reviewer per `superpowers:subagent-driven-development`'s standard closing step, covering the full diff from this plan's starting commit. Pay particular attention to Task A3's `resolveFromBits` rewrite (the highest-risk single piece of work in this plan) and confirm the reviewer independently spot-checks at least a handful of the trickiest branches in the original `resolve()` (the `srcC`/`indexFromEa`/`indexFromMiOtherEa` mem-indirect resolution logic, and the BCD/bitfield `bfStoreForm`/`bfDyn` handling) against their `resolveFromBits` equivalents, not just trusting Task A4's automated equivalence test in isolation.
