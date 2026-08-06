# LUT reduction: microcode ROM + ROB alloc-field storage as real BRAM

> Design spec. Companion implementation plan(s) to be written via
> `superpowers:writing-plans` after this spec is accepted.

**Goal:** reduce LUT usage in two areas of the core by moving storage/lookup
structures that are currently LUT-inferred (or LUT-heavy by construction) into
real block RAM, with zero net latency cost to the pipeline paths involved.

**Architecture:** two independent, separately-implementable features:

- **Feature A** — convert the microcode ROM (`Microcode.rom`, ~250 rows) from
  "resolve every row combinationally every cycle, then index the result" into
  "index a real `Mem()` by the microcode PC, resolve only the selected row."
- **Feature B** — fold a confirmed-safe subset of the ROB's ~30 separate
  per-entry `Vec.fill(64)(Reg(...))` storage arrays into the ROB's existing
  `payload` `Mem()` (or a second alloc-only `Mem()`), matching the exact
  write-port shape `payload` and `branchTrainMem` already use safely today.

**Tech stack:** SpinalHDL/Scala, targeting Xilinx UltraScale+ BRAM primitives
(the project's standing synth target).

## Global Constraints

- Every change in this spec must be **latency-neutral** on the pipeline path
  it touches. Neither feature may add a cycle to the decode or ROB
  read/write timing that isn't already present in some other form today.
- Every change must pass this project's standing verification protocol:
  `ExecuteLockStepSpec` at its current baseline (390/394, the 4 documented
  pre-existing failures unchanged), the full ported-test corpus with zero
  new regressions, and (per the project's standing synth-gate rule) an
  OOC + post-route synth gate at ≥250MHz before either feature's slice is
  considered closed. The synth gate is currently blocked on Vivado
  availability (occupied by a sibling project this entire session) — both
  features' implementation work may proceed without it, but neither slice
  *closes* without it, per established project practice this session.
- No behavior change to the M68040 ISA semantics either feature's target
  code implements. This is a pure microarchitectural/area change.
- Follow this project's established subagent-driven-development workflow:
  fresh implementer subagent per task, independent task review, and a
  final whole-branch review, exactly as every other slice this session.

## Feature A: microcode ROM as real Mem()

### Current state (confirmed by direct source reading, not assumed)

`Microcode.rom` (`Microcode.scala`) is a **Scala-level** `Vector[Desc]` of
~250 rows (`romSize` currently up to at least 251; grows over time as new
microcoded instruction forms are added). `Desc` (`Microcode.scala:202`) is a
plain Scala `case class` — its fields (`uop: UOp`, `mem: Mem`, `auto: Auto`,
`srcA/srcB/srcC/dst: Sel`, `useImm: Boolean`, `imm: Sel`, `sz: Sz`, ~15 more
`Boolean`/`Int` fields) are **compile-time Scala values, not SpinalHDL
hardware signals.**

`Microcode.resolve(d: Desc, ctx: Ctx, valid: Bool): DecodedUop`
(`Microcode.scala:1621`, **285 lines**) builds a `DecodedUop` (**74
fields**, several 32-bit) by pattern-matching and branching directly on
`d`'s Scala fields — e.g. `d.uop match { case UMove => ...; case UAddDrop
=> ... }` and `if (d.indexFromEa) ctx.eaIndexReg else if (...) ... else
...`. Because `d` is a compile-time object, **every one of these branches
resolves at elaboration time**: each of the 250 calls to `resolve()`
generates a *different, dead-code-eliminated hardware fragment* specialized
to that row's `Desc` values, not 250 copies of one shared circuit.

`DecodeStage.scala:1729-1735` then does:

```scala
val ucResolved = Vec(Microcode.rom.map(d => Microcode.resolve(d, ucCtx, True)))
val ucLastVec  = Vec(Microcode.rom.map(d => Bool(d.isLast)))
val ucIdx      = ucPc.resize(log2Up(Microcode.romSize))
val ucCurUop   = ucResolved(ucIdx)
```

This resolves **all ~250 rows, every cycle**, into full 74-field
`DecodedUop`s, then muxes the one selected by `ucPc`. This is the single
largest identified LUT-reduction opportunity in the codebase (see the
source-level survey this spec is based on) — ~250x more combinational
logic than necessary for a lookup that only ever needs one row per cycle.

### Timing analysis (confirmed safe, zero added latency)

`ucPc` (`DecodeStage.scala:869`) is already a `Reg(UInt(8 bits))`. Its
*next* value is already computed one cycle ahead of when it's consumed:
`ucPc := ucRealEntry.resize(8)` at `ucBegin`, or `ucPc := ucPc + 1` for
straight-line advance (`DecodeStage.scala:1974`, `:2001`). `ucCurUop` is
consumed the same cycle `ucPc`'s *current* (already-registered) value is
read — i.e., today's design already has a "compute next address one cycle
early, consume the corresponding data the following cycle" shape, which is
**exactly** the address/data timing a synchronous-read BRAM needs.

**Locked decision:** feed a real `Mem()`'s synchronous read port with the
*same* next-`ucPc` expression that already drives `ucPc`'s own register
(not a new, separately-computed address). This means the BRAM's registered
output data lands exactly the cycle `ucPc` itself becomes the "current"
value — no new pipeline stage, no schedule change anywhere else in the
µcode sequencer FSM.

### The real engineering task: `resolve()` must become a runtime decoder

Storage conversion alone (`Vector[Desc]` → `Mem(DescBits(), romSize)`) is
not sufficient by itself, because `resolve()`'s Scala-level branching on
`Desc`'s fields is *how* the current design avoids being LUT-heavy in the
first way that matters (per-row specialization) while being LUT-heavy in
the way this spec targets (250x replication). Converting to a Mem read
means `d`'s fields become **runtime hardware signals** (bits read back
from the Mem), not compile-time constants — every Scala-level `match`/`if`
in `resolve()`'s 285 lines must become a runtime `switch`/`when`/`Mux` over
those hardware fields. This is a **mechanical but large, correctness-
critical rewrite**, not a pointer swap. `resolve()` feeds every
microcoded instruction's execution (BFINS chains, MOVEM, all mem-indirect
EA forms, DIVREM, PACK/UNPK, BCD ops, and more) — this project's own
history (tasks #144-214, the ported-test triage series) found numerous
subtle bugs in exactly this class of logic. Treat this rewrite with the
same rigor.

**Locked decisions:**

1. Define a new hardware `Bundle` (`DescBits` or similar) whose fields
   mirror `Desc`'s exactly (same names, hardware-typed: SpinalHDL `SpinalEnum`
   for `UOp`/`Mem`/`Auto`/`Sel`/`Sz`, `Bool()` for each `Boolean` field,
   `UInt`/`SInt` sized appropriately for `Int` fields like `bfStoreForm`).
2. Build the `Mem(DescBits(), romSize)`'s initial content at elaboration
   time from `Microcode.rom` (each Scala `Desc` converted to its
   `DescBits` hardware-literal encoding) — this is compile-time work, safe
   and mechanical.
3. Rewrite `resolve()` to accept a `DescBits` (hardware) instead of `Desc`
   (Scala), converting every Scala-level branch to its runtime hardware
   equivalent 1:1. Do not restructure the *logic* — this is a type-level
   conversion of an existing, already-correct decision tree, not a
   redesign. Preserve every comment explaining *why* a given branch exists.
4. **Mandatory verification gate, before this feature can be considered
   done**: an exhaustive per-row equivalence test. For every one of the
   ~250 ROM rows, drive the OLD compile-time `resolve(rom(i), ctx, True)`
   and the NEW `resolve(memReadOf(i), ctx, True)` with the same
   representative sweep of `Ctx` values, and assert the resulting
   `DecodedUop`s are bit-identical. This is the single most important
   safety net for this feature — do not accept "the ported test corpus
   still passes" alone as sufficient evidence, since the ported corpus
   may not exercise every microcoded row/ctx combination. Keep the OLD
   compile-time path available (e.g. behind a test-only flag or as a
   parallel construction in the equivalence-test harness only, not in the
   production build) for exactly as long as this equivalence test needs
   it, then remove it once the new path is proven equivalent.

### Explicitly out of scope for Feature A

- Restructuring `Desc`'s field set or `resolve()`'s logical decisions —
  this is a mechanical type conversion, not a redesign.
- Any change to `Microcode.rom`'s actual entries (no new/removed
  microcode rows as part of this work).

## Feature B: fold confirmed-safe ROB alloc-only fields into `payload`

### Current state (confirmed by exact write-site grep, not assumed)

`RobPlugin.scala` has one real `Mem()` for its main per-entry payload
(`payload = Mem(RobPayload(), depth)`, depth 64) and a second, task-#129-
added `Mem()` for BTB/gshare training data (`branchTrainMem`). Both are
already correctly BRAM-safe because each has a **bounded, known write-port
shape**: `payload` has exactly 2 alloc-time write ports (tail, tail+1 —
dual-issue allocation, non-colliding addresses by construction);
`branchTrainMem` has exactly 1 write port (`branchCompletion` only — see
its own doc comment, which explicitly cites the general hazard this spec
also respects: *"a naive multi-write sync-read Mem is unsafe"*).

Alongside these, ~30 more fields live in separate `Vec.fill(64)(Reg(...))`
arrays. **This spec's own investigation (exact write-site grep of every
field, not the initial source-survey's shape-level characterization) found
these do NOT all share one write-port shape** — they split into three
genuinely different categories:

**Category 1 — alloc-only (2 write ports max: tail, tail+1, identical
shape to `payload` itself).** Confirmed by grep: every write site for
these 9 fields is at `allocUopVec(0/1)...` targeting `tail`/`tail+1`, with
no other writer anywhere in the file:
`isRteStore`, `sysOpStore`, `sysReadDirStore`, `sysDstArchStore`,
`sysRcStore`, `needsSupStore`, `firstStore`, `pcStore`, `sysKindStore`.

**Category 2 — single-or-dual completion-source fields with an
additional alloc-time reset write.** E.g. `mispredictStore`/
`branchTakenStore`/`btbIsBranchStore`/`phtValidStore` (branchCompletion
completion write + a tail/tail+1 reset-to-False write); the LS-fault
family `faultAddrStore`/`faultWrStore`/`faultSizeStore`/`faultSupStore`/
`faultAtcStore` (2-3 DIFFERENT completion sources — `lsFaultCompletion`,
`sqFaultCompletion`, and for 2 of the 5 fields also `euFaultCompletion` —
confirmed via `RobPlugin.scala:309-312`'s own comment that a same-cycle
2-source collision on *different* ROB entries is a real, intended
scenario, not a hypothetical). These fields have MORE write ports than
`branchTrainMem`'s proven-safe 1-port shape, and the alloc-reset write
means they don't benefit from `branchTrainMem`'s own "no alloc write,
gated by a separate always-reg field" trick either.

**Category 3 — genuinely multi-port completion fields.** `completes`
(written from up to 5 `completion` ports + `branchCompletion` + 2 alloc-
reset writes — as many as 8 potential same-cycle writers targeting
different entries), `nzvcValStore`/`nzvcWrStore`/`xValStore`/`xWrStore`
("one port per EU" per the file's own comment — a genuine N-way CCR-
completion fan-in), `sysValStore`/`sysValRdyStore` (completion-only, tied
to the same multi-source completion fan-in). These are structurally
similar to the issue-queue's wakeup comparators identified in the source
survey as poor BRAM candidates — genuine multi-writer fan-in, not a
simple indexed-storage access pattern.

### Locked decisions

1. **This feature's scope is Category 1 only: 9 fields.** Fold them into
   the existing `RobPayload` bundle (extending `payload`'s `Mem()`) rather
   than creating a second alloc-only `Mem()` — they share `payload`'s
   exact write-port shape already, so this is the most direct, lowest-
   risk application of an already-proven pattern in this exact file.
2. **Category 2 and Category 3 fields are explicitly OUT OF SCOPE for
   this initiative.** They are not being ruled out as *permanently*
   un-improvable — Category 2 in particular could plausibly follow
   `branchTrainMem`'s own trick (finding a reliable existing gate that
   makes the alloc-time reset write unnecessary, the way
   `btbIsBranchStore`/`phtValidStore` already gate `branchTrainMem`'s
   un-reset rows) — but that requires a dedicated, careful per-field
   safety argument this spec has not done and should not hand-wave. If
   a future initiative wants to pursue Category 2, it needs its own
   design pass verifying each field's specific gate condition holds
   under every reachable ROB re-allocation sequence, with the same
   direct-write-site-grep rigor this spec applied. Category 3 fields
   should likely stay as register arrays permanently, the same
   conclusion the source survey reached for the IQ's wakeup comparators
   — genuine multi-port associative writes are a poor BRAM fit
   structurally, not just a scoping convenience.
3. Extending `RobPayload` widens `payload`'s per-entry width by
   (roughly) 1(isRte) + 1(sysOp) + 4(SysKind enum) + 1(sysReadDir) +
   5(sysDstArch) + 12(sysRc) + 1(needsSup) + 1(first) + 32(pc) +
   4(sysKindStore, already counted) = ~58 bits. Confirm the resulting
   `payload` row width against the target BRAM primitive's natural word
   width during implementation (a poor width choice can waste a
   disproportionate number of BRAM36/BRAM18 blocks) — this is an
   implementation-time check, not a spec-time blocker.

### Explicitly out of scope for Feature B

- Any Category 2 or Category 3 field (see above).
- `completes` specifically — even though textually a `Vec.fill`, its
  true multi-writer, per-cycle-multi-entry-completion semantics make it
  a poor Mem candidate on the same structural grounds as the IQ wakeup
  comparators, not merely deferred for lack of time.

## Testing strategy (both features)

- **Feature A**: the mandatory per-row equivalence test (see above) is
  the primary correctness gate. Beyond that, full `ExecuteLockStepSpec`
  and the full ported corpus, with special attention to every
  microcoded-instruction-family test (BFINS/BFEXTU/BFEXTS/BFFFO/BFSET
  family, MOVEM, all mem-indirect EA forms, DIVREM, PACK/UNPK, BCD ops) —
  these exercise the widest variety of `Desc` rows and are the highest-
  value regression signal if the rewrite introduces a subtle behavioral
  difference in one row's runtime-decoded logic.
- **Feature B**: full `ExecuteLockStepSpec` and full ported corpus (no
  new directed test strictly required, since this is a pure storage-
  location change with no behavioral difference to the 9 folded fields'
  semantics — but a small directed `RobPluginSpec`-style test confirming
  a folded field round-trips correctly through allocation → retire is
  cheap insurance and should be added).
- Both features: an OOC + post-route synth gate is required before
  either slice *closes* (see Global Constraints) — run the moment Vivado
  is free, on an uncontended machine per this project's standing rule
  about FMax-under-contention being untrustworthy.

## Sequencing

Feature A and Feature B are independent and can be implemented in either
order or in parallel by separate subagent chains. Given the user's own
stated priority (microcode ROM first, then ROB), implement Feature A
first. Given Feature A's larger size and correctness risk relative to
Feature B, expect Feature A to need more task-review iterations.
