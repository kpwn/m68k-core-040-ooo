# FMax closure, Slice 1: register p0Live off the L0 consume path — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the live `PredecodeWord.classify()` re-run (`p0Live`) from the
Aligner's same-cycle `L0` consume path, replacing it with a registered value
consumed one cycle later — closing the single largest attributable chunk
(~1.6-1.9ns) of this core's post-route FMax shortfall, without changing
architectural behavior.

**Architecture:** `Aligner.align` becomes a pure function of an ADDITIONAL
input, `p0LiveReg: ChunkPredecode` (the previously-computed, now-registered
live reclassification of the buffer head), instead of computing `p0Live`
itself combinationally inside `align`. `FetchAlignPlugin` gains the register:
`p0LiveReg := PredecodeWord.classify(...)` computed every cycle from the
LIVE (unregistered) `ibuf.io.head`/`ibuf.io.avail` — identical computation to
today's `p0Live`, just landing in a register instead of feeding `Aligner.align`
same-cycle — with the register forced to `ambiguousLine := True` (i.e.
"not yet resolved") on `ibuf.io.flush`, since a flush invalidates whatever the
register was resolving for the old head.

**Tech Stack:** SpinalHDL / Scala, this project's existing ScalaTest +
Verilator directed-test conventions.

## Global Constraints

- Behavior-neutral for every NON-ambiguous instruction (the overwhelming
  majority) — `p0 = Mux(preds(0).ambiguousLine, p0LiveReg, preds(0))`, and
  `preds(0)` is completely unaffected by this change.
- For the rare `ambiguousLine` case: bit-identical resolved `ChunkPredecode`
  values to today's `p0Live` (same `classify()` call, same inputs) — only
  the TIMING of when it becomes available to `L0` changes (one cycle later
  than today, at minimum).
- Zero IPC cost for the non-ambiguous case. At most one additional stall
  cycle for the (already rare, already multi-cycle-tolerant) ambiguous case.
- Do NOT touch: `ChunkPredecode`'s bit layout, `IcachePlugin`'s refill-time
  predecode, `InstructionBuffer`'s storage/push/shift/flush semantics, slot-1's
  own `ambiguousLine` handling (`Aligner.scala` lines ~157-182, task #209's
  fix — this stays exactly as-is; it already defers an ambiguous slot-1
  candidate to become next cycle's head, where it will correctly flow through
  the NEW registered mechanism this plan builds, same as any other ambiguous
  head).
- `~/sbt/bin/sbt compile` clean at every step.
- `ExecuteLockStepSpec`: expect 390/394 (the 4 standing pre-existing
  failures: STOP-#imm/IRQ/RTE + 3 ITLB tests — unrelated to this area).
- Full ported test corpus (~870 tests): zero new regressions vs the current
  baseline. Use `tools/fuzz/ported-sweep-parallel.sh` (this project's
  established sharded-worktree sweep tool) for a controlled before/after
  comparison, matching the discipline used throughout this session's other
  synth/RTL-change verification.
- Both synth gates required before this slice is considered closed:
  `synth/ooc_M68kFullCoreSynth.tcl` (fast sanity check; expect roughly the
  ~206MHz OOC-synth-only region confirmed by the pre-implementation
  attribution measurement, though the EXACT number will differ slightly since
  that measurement used a deliberately-incorrect throwaway edit, not this
  real mechanism) and `synth/impl_FullCore.tcl` (the real post-route gate —
  this slice's actual pass/fail criterion). Report the measured FMax delta
  explicitly against the current post-route baseline (~146.11MHz,
  WNS -2.844ns) — success is a clear, reproducible improvement; do not
  expect this alone to clear 250MHz (per the design spec and Fable 5's
  original research, the remaining slot-1 realignment/offload segments are a
  separate, later slice).

---

### Task 1: Register `p0Live`, wire it through `Aligner.align`, verify no regressions

**Files:**
- Modify: `src/main/scala/m68k040/frontend/Aligner.scala` (signature change,
  ~line 20, and the `p0Live`/`p0` computation, ~lines 63-66)
- Modify: `src/main/scala/m68k040/frontend/FetchAlignPlugin.scala` (add the
  register + its flush-gating, near the existing call site at ~line 315)
- Modify: `src/test/scala/m68k040/frontend/AlignerSpec.scala` (update the
  `Dut` for the new `Aligner.align` signature; existing tests need a new
  input wired but should otherwise be unaffected; add new directed tests for
  the `ambiguousLine` resolve behavior)
- Modify: `src/test/scala/m68k040/frontend/FetchAlignSpec.scala` (add a new
  directed test proving the flush-mid-resolve interaction is safe)

**Interfaces:**
- `Aligner.align`'s new signature:
  `def align(headPc: UInt, words: Vec[Bits], preds: Vec[ChunkPredecode], avail: UInt, p0LiveReg: ChunkPredecode): Result`
- Produces: `FetchAlignPlugin`'s new `p0LiveReg: Reg(ChunkPredecode())`
  (name at your discretion, but use this exact name unless you find a
  strong reason not to, since the design spec and this plan both refer to
  it) — this is the ONLY new named signal this task introduces outside
  `Aligner`'s own scope.

- [ ] **Step 1: Read the CURRENT live source before editing anything**

Line numbers below are accurate as of the commit this plan was written
against (`Aligner.scala`, `FetchAlignPlugin.scala` at the tip of
`feat/rob-predictor-mem` as of the design spec's commit `0c606a8`) — this
project's own standing practice is to re-verify against the live file
before editing regardless, since other tasks' edits can shift lines; do
that now with a plain read of both files' relevant regions (`Aligner.scala`
lines 1-70 and 140-220; `FetchAlignPlugin.scala` lines 300-320) before
proceeding.

- [ ] **Step 2: Change `Aligner.align`'s signature and remove the live
  `classify()` call**

Current (`Aligner.scala:20`):
```scala
def align(headPc: UInt, words: Vec[Bits], preds: Vec[ChunkPredecode], avail: UInt): Result = {
```
becomes:
```scala
def align(headPc: UInt, words: Vec[Bits], preds: Vec[ChunkPredecode], avail: UInt, p0LiveReg: ChunkPredecode): Result = {
```

Current (`Aligner.scala:63-66`, including the explanatory comment above it
— READ the full comment block at lines 43-62 first; it stays, mostly
unchanged, but its final sentence needs a small update, see below):
```scala
    val p0Live = PredecodeWord.classify(words(0), words(1), words(2), words(3),
      extWValid = avail >= U(2, 4 bits), extW2Valid = avail >= U(3, 4 bits), extW3Valid = avail >= U(4, 4 bits))
    val p0 = Mux(preds(0).ambiguousLine, p0Live, preds(0))
    val L0 = p0.lenWords  // UInt(4 bits)
```
becomes:
```scala
    val p0 = Mux(preds(0).ambiguousLine, p0LiveReg, preds(0))
    val L0 = p0.lenWords  // UInt(4 bits)
```

Update the LAST sentence of the comment block above (currently ending
"...Selected by a single bit (`ambiguousLine` is False for the overwhelming
majority of instructions), but see the commit message / task202 report for
the synth-gate verdict on whether instantiating a 2nd classify() here is
FMax-safe on this front-end-critical path.") to instead say something like:
"Selected by a single bit (`ambiguousLine` is False for the overwhelming
majority of instructions). The live reclassify itself is computed in
`FetchAlignPlugin` and REGISTERED (`p0LiveReg`, passed in here) rather than
consumed same-cycle — this function only ever sees the already-resolved (or
still-ambiguous, if not yet resolved) value; see
`docs/superpowers/specs/2026-08-07-fmax-frontend-p0live-pipelining-design.md`
for the full rationale (this was previously a same-cycle combinational
`classify()` call here, which was the single largest attributable chunk of
this core's post-route FMax shortfall — confirmed via a direct attribution
measurement before this change, not merely suspected)." — exact wording at
your discretion, but the comment must no longer describe `p0Live` as
computed inside this function, since it no longer is.

`PredecodeWord` no longer needs to be imported/referenced in `Aligner.scala`
if this was its only use — check with a grep for `PredecodeWord\.` in the
file after this edit; remove the import if it's now unused (avoid an unused
import warning).

- [ ] **Step 3: Add the registered `p0Live` computation to `FetchAlignPlugin.scala`**

Near the existing call site (`FetchAlignPlugin.scala:315`,
`val res = Aligner.align(decodePc, ibuf.io.head, ibuf.io.headPred, ibuf.io.avail)`),
add the new register and its combinational next-value computation, then
update the call site to pass it in:

```scala
    // ---- p0Live: registered live re-classify of the buffer head (FMax slice 1,
    // 2026-08-07) ----
    // Computed EVERY cycle from the LIVE (unregistered) head words/avail — identical
    // classify() call to what `Aligner.align` used to run combinationally on its own
    // L0-consume path (see that file's comment for the full task #202 rationale this
    // preserves). The ONLY change: this result now lands in a register, consumed by
    // `Aligner.align` ONE CYCLE LATER, instead of feeding `L0`'s arrival time same-cycle.
    // Correctness argument (see the design spec for the full version): InstructionBuffer's
    // head entry is provably immutable for as long as the aligner stalls on
    // `preds(0).ambiguousLine` (pushes only ever write the TAIL slot; only shift/flush
    // move `headPtr`, and the aligner's own `.elsewhen(p0.ambiguousLine)` stall arm holds
    // `shiftWords=0` — see `Aligner.scala`), so re-registering this every cycle and
    // consuming it next cycle is safe UNTIL a flush lands, which invalidates it (handled
    // below by forcing `ambiguousLine := True`, i.e. "not yet resolved", on flush — costs
    // at most one extra stall cycle in the rare compound case of a flush landing exactly
    // on an ambiguous head).
    val p0LiveReg = Reg(ChunkPredecode())
    p0LiveReg := PredecodeWord.classify(ibuf.io.head(0), ibuf.io.head(1), ibuf.io.head(2), ibuf.io.head(3),
      extWValid = ibuf.io.avail >= U(2, 4 bits), extW2Valid = ibuf.io.avail >= U(3, 4 bits), extW3Valid = ibuf.io.avail >= U(4, 4 bits))
    when(ibuf.io.flush) {
      p0LiveReg.ambiguousLine := True
    }

    // ---- Aligner: combinational decode of buffer head ----
    val res = Aligner.align(decodePc, ibuf.io.head, ibuf.io.headPred, ibuf.io.avail, p0LiveReg)
```

Confirm `PredecodeWord` is imported in `FetchAlignPlugin.scala` (it's in the
same package, `m68k040.frontend`, so likely no import statement is even
needed — verify by checking whether the file already references
`PredecodeWord` anywhere, or whether `Aligner.scala`'s own now-possibly-removed
import gives a hint about the required import path if one IS needed).

`Reg(ChunkPredecode())` needs a reset value for compile — check whether
`ChunkPredecode()` as a plain `Reg(...)` without `init` compiles cleanly in
this codebase's SpinalHDL version (Scala/SpinalHDL usually requires either
an explicit `init` per-field or relies on `RegInit`-per-field like
`InstructionBuffer.scala`'s `entries` does — re-read that file's exact
pattern, lines 71-78, and mirror it if a bare `Reg(ChunkPredecode())` doesn't
compile: e.g.
```scala
val p0LiveReg = Reg(ChunkPredecode())
p0LiveReg.simple init False
p0LiveReg.lenWords init 0
p0LiveReg.ambiguousLine init True   // "not yet resolved" is the safe power-on default
```
— use whichever form compiles; if both compile, prefer explicit per-field
`init` matching `InstructionBuffer`'s established style, with
`ambiguousLine init True` as the safe "not yet resolved" default, not
`False` (an uninitialized/reset-state register must never be mistaken for
"already resolved").

- [ ] **Step 4: Compile**

```bash
~/sbt/bin/sbt compile
```
Fix any compile errors (missing import, `Reg` init requirements, etc.)
before proceeding.

- [ ] **Step 5: Update `AlignerSpec.scala` for the new signature**

The `Dut` (`AlignerSpec.scala:11-18`) needs a new input port and to pass it
through:
```scala
  class Dut extends Component {
    val headPc    = in UInt(32 bits)
    val words     = in Vec(Bits(16 bits), Aligner.WINDOW)
    val preds     = in Vec(ChunkPredecode(), Aligner.WINDOW)
    val avail     = in UInt(4 bits)
    val p0LiveReg = in(ChunkPredecode())
    val res       = out(Aligner.Result())
    res := Aligner.align(headPc, words, preds, avail, p0LiveReg)
  }
```
Every EXISTING `test(...)` block in this file (5 of them, lines 23-95) does
not set `dut.p0LiveReg` at all today (it doesn't exist yet) and none of
them set `preds(0).ambiguousLine = true`, so `p0LiveReg`'s value is
irrelevant to their outcomes (the `Mux` in `Aligner.align` never selects
it) — BUT SpinalSim requires every `in` port to be driven or the sim may
read X/uninitialized garbage into `p0LiveReg` even though it's unused;
confirm whether this codebase's SimConfig defaults uninitialized inputs to
a defined value (check for a global `SpinalConfig`/`SimConfig` default in
`M68kSim.scala` or similar) — if not, add a one-line default poke
(`dut.p0LiveReg.simple #= false; dut.p0LiveReg.lenWords #= 0; dut.p0LiveReg.ambiguousLine #= true`)
at the top of each existing test's `doSim` block, OR (cleaner) add it once
inside a shared setup helper if one already exists in this file (there
isn't one currently — `setAll`/`setPred` only touch `words`/`preds`; you
may add a `setP0Live` helper or fold the default poke into a modified
`setAll`, your call, but keep every existing test's actual assertions
unchanged).

- [ ] **Step 6: Add new directed tests to `AlignerSpec.scala` for the
  `ambiguousLine` resolve mechanism**

Add tests proving:
1. **Ambiguous head, not yet resolved** (`p0LiveReg.ambiguousLine = true`):
   the aligner must stall (mirror the existing "insufficient bytes" test's
   assertion shape) regardless of `preds(0).lenWords`/`avail` — the
   `p0.ambiguousLine` stall arm must trigger.
2. **Ambiguous head, now resolved** (`preds(0).ambiguousLine = true`,
   `p0LiveReg.ambiguousLine = false`, `p0LiveReg.simple = true`,
   `p0LiveReg.lenWords = <some value>`): the aligner must emit slot0 using
   `p0LiveReg`'s `lenWords`/`simple`, NOT `preds(0)`'s (which should be set
   to an obviously-different, wrong value in the test to prove the Mux
   picked the right side — e.g. `preds(0).lenWords = 1` (a guess) vs
   `p0LiveReg.lenWords = 3` (the resolved truth), and assert
   `dut.res.slot0.lenWords.toInt == 3`).
3. **Non-ambiguous head** (`preds(0).ambiguousLine = false`): `p0LiveReg`'s
   value must be IGNORED even if it's set to something that would produce a
   different, wrong result if consumed — proves the Mux correctly ignores
   `p0LiveReg` in the common case.

Write these following the exact style of the existing tests in this file
(same `SimConfig.withVerilator.compile(new Dut).doSim { dut => ... }`
shape, `VerilatorTest` tag, `sleep(1)` before asserting since this remains
a pure-combinational `Dut` with no clock).

- [ ] **Step 7: Add a directed test to `FetchAlignSpec.scala` for the
  flush-mid-resolve interaction**

This is the ONE piece of behavior with no prior test coverage anywhere in
this codebase (confirmed via the pre-implementation research pass) — a
flush arriving while `p0LiveReg` is mid-resolve (i.e. `ambiguousLine` was
true, is now being re-attempted, and a `redirect` fires before it settles).

Construct a memory image using an EXISTING ported test's straddle
technique as reference — `src/test/resources/m68kooo-ported-tests/asm/bf_memind_dyn_straddle.s`
and `src/test/resources/m68kooo-ported-tests/asm/pea_memind.s` are the
established real-world cases that exercise `ambiguousLine`; read BOTH to
understand exactly how they position a mode-6/mode7-reg3 EA's extension
word across a 64-byte I-cache line boundary (in terms of word offset from a
line-aligned base) so you can derive an equivalent instruction placement
for this test's own memory image, matching `FetchAlignSpec.scala`'s
existing `IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base, Seq(...))`
convention. The exact hex opword/extension-word values must come from
this derivation (grounded in the real EA encoding those two `.s` files
exercise), not be invented.

The test's shape:
1. Redirect to a PC positioned so the ambiguous-EA instruction's head lands
   with its disambiguating extension word past a 64-byte line boundary
   (so `ambiguousLine=True` at refill-time predecode, forcing the aligner
   to stall on it).
2. Confirm the aligner IS stalling (poll `dut.probe.logic.feedOut.valid`
   staying false, or an equivalent observable stall signal — check
   `DecodeFeedProbePlugin` for what it exposes) for at least 1-2 cycles
   (long enough that `avail` has grown past the line boundary and
   `p0LiveReg` has had at least one real classify() attempt, but BEFORE it
   would have naturally resolved and been consumed).
3. Fire a `redirect` (mirroring this file's existing `redirect(dut, cd, pc)`
   helper) to a DIFFERENT, unrelated target while the stall is still
   in-flight.
4. Assert the new target's instruction stream is fetched and decoded
   CORRECTLY (a simple, unambiguous instruction at the new target — e.g.
   reuse this file's own `MOVEQ` pattern from the other tests) — i.e. no
   stale/wrong `p0LiveReg` content from the abandoned resolve leaks into
   the new target's decode. This is the load-bearing assertion: if the
   flush-clear (`when(ibuf.io.flush) { p0LiveReg.ambiguousLine := True }`)
   were missing or wrong, a coincidentally-also-ambiguous new head could
   consume STALE `p0LiveReg` content from the abandoned resolve and
   silently misdecode — this test's whole purpose is to catch exactly that
   class of bug, so don't weaken it to something that wouldn't catch it.

- [ ] **Step 8: Run the new and existing frontend unit tests**

```bash
~/sbt/bin/sbt "testOnly m68k040.frontend.AlignerSpec"
~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
All must pass (5 pre-existing + your new ones in `AlignerSpec`; 3
pre-existing + your new one in `FetchAlignSpec`).

- [ ] **Step 9: Run the targeted ambiguousLine-related ported corpus tests**

```bash
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" -- -z pea_memind
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" -- -z rom_frontier_decode_matrix
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" -- -z bf_memind_dyn_straddle
~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec" -- -z move_l_memind_to_memind
```
(Confirm these `-z` filters actually subset the suite as expected before
relying on them — a prior session task found `-z` filtering didn't always
work as assumed on this test runner; if so, fall back to the full corpus
run in Step 11 as the real check and treat these as a fast first-pass
sanity check only.) All must pass with no new failures.

- [ ] **Step 10: Run the full lock-step suite**

```bash
~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"
```
Expect 390/394 (the 4 documented pre-existing failures: STOP-#imm/IRQ/RTE +
3 ITLB tests). Any different count or different failing tests is a
regression — investigate before proceeding, do not dismiss it.

- [ ] **Step 11: Run the full ported test corpus, before/after controlled comparison**

Use this project's established sharded-worktree sweep tool
(`tools/fuzz/ported-sweep-parallel.sh`) to run the full ~870-test corpus on
BOTH the pre-this-task commit and the post-this-task commit, and diff the
sorted fail-name lists — this is the same discipline used for every other
RTL-touching task this session (LUT-reduction Tasks A5/B1, etc.), and is
especially important here given this touches a historically
regression-prone area (tasks #202/#204/#209 all found real bugs in exactly
this code). Zero new failures required; any new failure is a real
regression to root-cause, not something to work around.

- [ ] **Step 12: OOC-synth-only sanity check**

```bash
JAVA_OPTS=-Xmx10g timeout 1200 ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"
vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl
```
Confirm the `RESULT FullCore WNS ... FMAX ...` line shows a clear
improvement over the current baseline (154.202MHz/WNS -2.485ns) — this is
a fast sanity check, not the slice's real pass/fail criterion (see Step 13).

- [ ] **Step 13: Real post-route synth gate (the slice's actual pass/fail criterion)**

```bash
vivado -mode batch -source synth/impl_FullCore.tcl
```
Report the measured post-route FMax explicitly against the current
baseline (~146.11MHz, WNS -2.844ns at the 4.000ns/250MHz target). Success
for THIS SLICE is a clear, reproducible improvement (this plan does not
require clearing 250MHz alone — the attribution measurement's own honest
caveat noted the post-route number might land just under 200MHz even
though the OOC-synth-only number cleared it comfortably; report the REAL
number, do not round up or extrapolate). Also note (do not need to
investigate in this task, just record for the ledger/task #201) whether
the worst post-route path matches the attribution measurement's prediction
(a `DecodeStage_logic_ucPendPkt_words_0_reg` → `FetchAlignPlugin`
`decodePc`/`fetchPc` PC-adder-chain family) or something else — this
informs what the NEXT FMax slice should target.

- [ ] **Step 14: Commit**

```bash
git add src/main/scala/m68k040/frontend/Aligner.scala \
        src/main/scala/m68k040/frontend/FetchAlignPlugin.scala \
        src/test/scala/m68k040/frontend/AlignerSpec.scala \
        src/test/scala/m68k040/frontend/FetchAlignSpec.scala
git commit -m "frontend: register p0Live off the L0 consume path (FMax closure slice 1)"
```
(Do NOT include `generated/M68kFullCoreSynth.v` or any `synth/*.rpt`
output — those are build artifacts, check this project's `.gitignore`
already excludes them, matching every other synth-touching commit this
session.)

---

## Self-review notes (from the plan author, not a placeholder — record here
## rather than leaving implicit)

- This plan deliberately does NOT split into multiple subagent-driven-development
  tasks with review gates between them, even though it's long — the mechanism
  (2 file edits) and its test coverage (2 more file edits) and its
  verification (6 more steps) are not independently meaningful milestones; an
  implementer should deliver the whole thing as one reviewable unit, matching
  how this session's other single-cohesive-mechanism tasks (e.g. the
  LUT-reduction plan's Task A3, a 283-line rewrite) were sized.
- The exact hex/word values for Step 7's new flush-interaction test are
  explicitly NOT pre-computed here — they require deriving from two real,
  cited, existing ported-test `.s` files' EA-encoding technique. This is a
  deliberate, bounded exception to "no placeholders": the task fully
  specifies what to build, how to derive it, and how to verify it; only the
  exact bit pattern is left to the implementer, grounded in real cited
  reference material rather than invented.
- If Step 3's `Reg(ChunkPredecode())` bare-vs-per-field-init question turns
  out to have only one working answer, that's fine — the plan gives both
  forms so the implementer isn't blocked figuring out SpinalHDL's exact
  requirement live; note whichever was needed in the task's own report for
  future reference.
