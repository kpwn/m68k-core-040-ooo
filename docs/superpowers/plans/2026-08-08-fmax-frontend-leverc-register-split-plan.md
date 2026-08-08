# FMax "Frontend Lever C": split Aligner→offload cone with a register — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Insert a new `PipeStage` in `DecodeStage.scala` between the
Aligner's raw packet output and `computeOffload`, splitting the
`fedIn`/`fed` cone's ~5.674ns critical path into two ~halves. Measured
(not estimated) effect: family worst slack -1.693ns -> ~-0.943ns.

**Architecture:** `[ibuf+Aligner] -> REG(raw: packets+slot1Valid) ->
computeOffload -> REG(fedIn->fed: packets+specs) -> assemble`. Pure
latency change — `computeOffload`'s only input is `pkt.words`, applied to
a registered (not recomputed) copy of the identical packet one cycle
later. Costs +1 frontend cycle on every redirect/mispredict/µcode-resume;
zero steady-state throughput cost (queue absorbs it).

## Global Constraints

- Design spec (read in full before starting, especially §3.4's honest
  "even with this lever the design caps ~186MHz" finding and §4's two
  fully-resolved hazards):
  `docs/superpowers/specs/2026-08-08-fmax-frontend-leverc-register-split-design.md`
- This is a PURE LATENCY change — no computed value may differ, only
  when it arrives. The no-behavior-change proof is the load-bearing
  verification, per the spec's own explicit warning ("this round has
  twice shipped a spec whose central premise was false and was only
  caught by actually running something").
- Lever U1 (`ucPendPkt` reuse) is being implemented concurrently in the
  SAME file. Design spec §4.3 confirms zero textual overlap as of its own
  writing, but re-verify exact line numbers against live source AFTER
  checking whether U1 has landed — this project's standing convention,
  doubly warranted with two concurrent editors of one file.
- The `headPtr`/`io.shift` feedback loop, half-1's own `slot1Valid`
  floor, the `fed.packets -> pushReg` assemble cone, LS/ROB Lever C, and
  Lever B (`ChunkPredecode` size baking, explicitly rejected) are all
  OUT OF SCOPE — do not attempt to fix any of them here.
- `~/sbt/bin/sbt compile` must stay clean throughout.
- `ExecuteLockStepSpec` must stay at 390/394.
- `AlignerSpec`/`FetchAlignSpec` must be BYTE-IDENTICAL to baseline (they
  sit upstream of `DecodeStage`; any movement means the change leaked out
  of scope).
- `IpcBenchSpec` is the accept/reject gate for the latency cost — report
  every kernel before/after, not just aggregate; a material aggregate
  drop means investigate, do not merge a real IPC regression.
- Full ported corpus (~870 tests) must show zero new regressions.
- **This lever must be gated COMBINED with Lever U1 and LS/ROB Lever C,
  never solo** — do not judge this task's success on a standalone
  top-line post-route number.

---

### Task 1: Insert the new register stage

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodeStage.scala` (new
  `RawPacket` bundle near `:35-49`; new `rawIn`/`raw` `PipeStage` and
  restructured `fedIn` source at `:78-92`; re-verify exact live line
  numbers, especially if Lever U1 has already landed and shifted
  everything below its own insertion point)

**Interfaces:**
- Consumes: `df.feed` (`Stream[Vec[DecodePacket]]`), `df.slot1Valid`
  (existing `DecodeFeedService` members, unchanged).
- Produces: a new `raw: PipeStage[RawPacket]` register; `fedIn`/`fed`
  unchanged in TYPE, only their SOURCE moves from `df.feed.payload`
  (combinational) to `raw.payload.packets` (registered).

- [ ] **Step 1: Re-verify exact current line numbers**

```bash
git log --oneline -1   # confirm whether Lever U1 has landed
grep -n "case class FedPacket\|val fedIn\|val fed = PipeStage" src/main/scala/m68k040/decode/DecodeStage.scala
```

- [ ] **Step 2: Add the `RawPacket` bundle**

Per the design spec's §8 open question 3 — use a DISTINCT bundle (not
`FedPacket` minus `specs`), so "this register carries no offload" is
structurally enforced:

```scala
case class RawPacket() extends Bundle {
  val packets    = Vec(DecodePacket(), 2)
  val slot1Valid = Bool()
}
```

- [ ] **Step 3: Insert the new `raw` PipeStage and restructure `fedIn`**

Per the design spec's §2 code block exactly:

```scala
val rawIn = Stream(RawPacket())
rawIn.valid              := df.feed.valid
rawIn.payload.packets(0) := df.feed.payload(0)
rawIn.payload.packets(1) := df.feed.payload(1)
rawIn.payload.slot1Valid := df.slot1Valid
df.feed.ready            := rawIn.ready

val raw = PipeStage(rawIn, pipeFlush)

val fedIn = Stream(FedPacket())
fedIn.valid              := raw.valid
fedIn.payload.packets(0) := raw.payload.packets(0)
fedIn.payload.packets(1) := raw.payload.packets(1)
fedIn.payload.slot1Valid := raw.payload.slot1Valid
fedIn.payload.specs(0)   := MicroOpAssembler.computeOffload(raw.payload.packets(0))
fedIn.payload.specs(1)   := MicroOpAssembler.computeOffload(raw.payload.packets(1))
raw.ready := fedIn.ready

val fed = PipeStage(fedIn, pipeFlush)   // unchanged
```

Per design spec §8 open question 4: `packets` IS re-registered into
`fed` (not read directly from `raw` by `assemble`) — this keeps `specs`
and `packets` aligned in the same cycle, which Lever U1's own identity
proof depends on. Comment this reasoning explicitly (a reviewer will ask
why the ~500-flop packets-registered-twice cost is accepted).

Comment the whole block citing this plan/spec.

- [ ] **Step 4: Compile**

```bash
~/sbt/bin/sbt compile
```

---

### Task 2: Verify no behavior change, run full verification suite, synth-gate

**Files:**
- Test: `src/test/scala/m68k040/frontend/AlignerSpec.scala`,
  `FetchAlignSpec.scala` (must be untouched, run to confirm
  byte-identical); `DecodeStageSpec`, `DecodeCrackPipeSpec`,
  `MicrocodeSpec`, `MovemDecodeSpec`, `MovepDecodeSpec`, `FetchFaultSpec`,
  `UcPendSpecStashEquivalenceSpec` (Lever U1's test — locate exact names
  via grep, do not guess) — expect settle-window bumps (acceptable) but
  NOT changed expected values (a bug until proven otherwise).

- [ ] **Step 1: State the no-behavior-change closure argument in your report**

Re-verify against live source (do not transcribe the design spec): (a)
`computeOffload`'s only input is `pkt.words`; (b) `raw.payload.packets(i)`
is bit-identical to `df.feed.payload(i)` one cycle later. This prose
argument alone is NOT sufficient — back it with Step 2's differential run.

- [ ] **Step 2: `AlignerSpec`/`FetchAlignSpec` — byte-identical to baseline**

```bash
~/sbt/bin/sbt "testOnly m68k040.frontend.AlignerSpec"
~/sbt/bin/sbt "testOnly m68k040.frontend.FetchAlignSpec"
```
Expected: `AlignerSpec` 9/9; `FetchAlignSpec` the SAME baseline count/
failure as recorded in `.superpowers/sdd/progress-fmax-levers-2026-08-08.md`
(do not assume 5/5). Any DIFFERENT result means the change leaked upstream
of `DecodeStage` — investigate before proceeding.

- [ ] **Step 3: Cycle-sensitive decode suites — expect settle-window bumps only**

Run `DecodeStageSpec`, `DecodeCrackPipeSpec`, `MicrocodeSpec`,
`MovemDecodeSpec`, `MovepDecodeSpec`, `FetchFaultSpec`,
`UcPendSpecStashEquivalenceSpec`. A settle-window bump (waiting one more
cycle for a value to appear) is acceptable and should be committed as
part of this task. A CHANGED expected value is a bug — stop and
investigate.

- [ ] **Step 4: Targeted lock-step subsets**

```bash
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec -- -z \"loop\""
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec -- -z \"call\""
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec -- -z \"RMW\""
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec -- -z \"IRQ\""
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec -- -z \"bne\""
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec -- -z \"DBcc\""
```
A deadlock ("Simulation failed at time=...") is a backpressure bug in the
new stage, not a flake — investigate immediately.

- [ ] **Step 5: `ExecuteLockStepSpec` full suite**

```bash
~/sbt/bin/sbt "testOnly m68k040.*.ExecuteLockStepSpec"
```
Expected: 390/394, the same 4 pre-existing failures.

- [ ] **Step 6: Targeted ported tests**

Grep the corpus for `memind`, `bf_`, `movem`, `movep`, `bcd`, `cas`
(complex/µcode-resume family) plus branch/redirect-heavy tests. Run
explicitly before the full sweep.

- [ ] **Step 7: Full ported test corpus (isolated worktree, before/after)**

`tools/fuzz/ported-sweep-parallel.sh` against the current baseline.
Expect byte-identical fail-name lists (diffed directly, not compared by
count).

- [ ] **Step 8: `IpcBenchSpec` — the accept/reject gate**

```bash
JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "testOnly m68k040.bench.IpcBenchSpec"
```
Report EVERY kernel before/after, not just aggregate. Pay specific
attention to branch-predictor/`rts`/RAS kernels and any kernel with
complex/µcoded instructions. A material aggregate drop means investigate
before merging — quantify what "material" turns out to be.

- [ ] **Step 9: OOC-synth-only gate**

```bash
vivado -mode batch -source synth/ooc_M68kFullCoreSynth.tcl
```
Report the delta on the TARGETED family specifically (`pred_lenWords ->
specs_*_dstEa_*`, expect -1.693ns -> ~-0.94ns), not just top-line.

- [ ] **Step 10: Real post-route gate**

```bash
vivado -mode batch -source synth/impl_FullCore.tcl
```
Same targeted-family reporting. Also explicitly check `feed.ready`'s
derived nets against the top-100 worst paths (design spec §4.1's
disclosed, expected-benign interaction) and report whether the new WNS
holder matches the design spec's prediction (the `headPtr` family at
~-1.37ns).

- [ ] **Step 11: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodeStage.scala \
        src/test/scala/<any modified test files>
git commit -m "decode: split Aligner->offload cone with a new pipeline register (FMax Frontend Lever C)"
```

## Self-Review Note

Two tasks (insert register, then verify) rather than the usual single
task, because this lever's verification burden is genuinely larger than
a typical slice (IPC gate, multiple settle-window-affected test suites,
two full lock-step passes) and deserves its own explicit checklist rather
than being folded into the RTL-change task.
