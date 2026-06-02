# Frontend Pipelining Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Insert flush-able registered Stream stages at the decode→rename and rename→dispatch boundaries so the frontend dispatch path is no longer one combinational cloud, pushing the full-core synth past 250 MHz — with no behavioral change.

**Architecture:** A small reusable flushable skid (registers valid/payload forward; `flush` clears valid; tied False this slice, wired to mispredict in 3d). `DecodeStage` and `RenameStage` skid their combinational output before exposing it as their service. +2 cycles frontend latency, lock-step unaffected (latency-agnostic). If the backward `ready` chain becomes the new critical path after forward-registering, add `s2mPipe` (register ready) at the boundaries.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado (xcku5p-ffvb676-2). Reuses the full-core synth gate `GenFullCoreSynthVerilog` + `synth/ooc_M68kFullCoreSynth.tcl`, and `ExecuteLockStepSpec`.

**Branch:** `feat/frontend-pipeline` (created; spec committed).

**Facts:** DecodeStage exposes `uops: Stream[Vec[DecodedUop],2]` + `uop1Valid` (== `uops.payload(1).valid`). RenameStage exposes `uops: Stream[Vec[RenamedUop],2]` + `uop1Valid` (== `uops.payload(1).valid`). Both currently combinational (`override def uops = logic.uopsPort`). Rename mutates RAT/freelist on its INPUT fire; registering its OUTPUT is functionally transparent (+1 cycle).

---

### Task 1: Flushable skid helper + skid decode & rename outputs

**Files:**
- Create: `src/main/scala/m68k040/frontend/PipeStage.scala` (reusable flushable skid)
- Modify: `src/main/scala/m68k040/decode/DecodeStage.scala`
- Modify: `src/main/scala/m68k040/rename/RenameStage.scala`

- [ ] **Step 1: Flushable skid helper**

Create `src/main/scala/m68k040/frontend/PipeStage.scala`:

```scala
package m68k040.frontend

import spinal.core._
import spinal.lib._

/** A 1-deep registered Stream stage (m2s: registers valid + payload forward) with
  * a `flush` that clears the held entry. Full throughput when downstream is ready.
  * `flush` is tied False until 3d wires it to the mispredict-flush broadcast (then
  * an in-flight wrong-path group is discarded — see the frontend-pipeline spec). */
object PipeStage {
  def apply[T <: Data](in: Stream[T], flush: Bool): Stream[T] = {
    val out = Stream(in.payloadType())
    val valid = RegInit(False)
    val data  = Reg(in.payloadType())
    // accept a new entry when the slot is empty or draining this cycle
    val slotFree = !valid || out.ready
    when(slotFree) { valid := in.valid; data := in.payload }
    when(flush)    { valid := False }
    in.ready    := slotFree
    out.valid   := valid
    out.payload := data
    out
  }
}
```
This registers the forward path (valid+payload) and is flushable. `in.ready := slotFree` keeps throughput at 1/cycle when the consumer is ready. (The backward `ready` is combinational through one level — if synth in Step 5 shows the backward chain as critical, Step 6 adds an s2m register.)

- [ ] **Step 2: Skid DecodeStage output**

In `src/main/scala/m68k040/decode/DecodeStage.scala`, after the combinational `uopsPort` is fully driven, add a flush input + skid and expose the staged stream:

```scala
    // Flush-able pipeline register (decode -> rename boundary). flush tied False
    // until 3d wires the mispredict-flush (frontend pipeline squash).
    val pipeFlush = Bool(); pipeFlush := False
    val uopsStaged = m68k040.frontend.PipeStage(uopsPort, pipeFlush)
```
and change the service overrides:
```scala
  override def uops: Stream[Vec[DecodedUop]] = logic.uopsStaged
  override def uop1Valid: Bool               = logic.uopsStaged.payload(1).valid
```
(`uopsStaged.payload(1).valid` carries the registered slot1Valid — equals the old `uop1Valid`. Keep the internal `uop1Sig` if used elsewhere internally, but the SERVICE `uop1Valid` must come from the staged stream so it's in sync with the staged `uops`.)

- [ ] **Step 3: Skid RenameStage output**

In `src/main/scala/m68k040/rename/RenameStage.scala`, likewise after `uopsPort` is fully driven:
```scala
    val pipeFlush = Bool(); pipeFlush := False
    val uopsStaged = m68k040.frontend.PipeStage(uopsPort, pipeFlush)
```
and:
```scala
  override def uops: Stream[Vec[RenamedUop]] = logic.uopsStaged
  override def uop1Valid: Bool               = logic.uopsStaged.payload(1).valid
```
IMPORTANT: rename's RAT/freelist updates are driven by its INPUT fire (`du.uops.fire`, gated by `uopsPort.ready` which is now the skid's `in.ready`). Do NOT change the input-side logic — only skid the output. The `RenameCommitService` commit/flush ports are unaffected. Confirm `du.uops.fire` still equals the cycle the RAT/freelist advance (it does — the skid sits on the output).

- [ ] **Step 4: Compile + run the affected unit specs**

Run: `~/sbt/bin/sbt "testOnly m68k040.decode.DecodeStageSpec m68k040.rename.RenameStageSpec m68k040.rob.RobPluginSpec m68k040.frontend.FetchAlignSpec"` (allow 590000 ms).
Expected: pass. The +1-cycle latency on each stage may shift cycle-exact timing in a spec that hard-codes a wait — if so, fix the test to **poll** the relevant `valid`/`fire`/output (do NOT change asserted values). If a spec drove the now-staged service expecting same-cycle output, add the extra cycle.

- [ ] **Step 5: Re-run lock-step (correctness unchanged) + full suites**

Run: `~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"` → all 5 programs green, deterministically (run twice). The +2 frontend latency must NOT change results (whitebox is latency-agnostic). If a program's commit count or values change, that's a real bug from the staging — investigate (likely the rename input-fire vs output-skid interaction, or uop1Valid desync), do not weaken asserts.
Run: `make SBT=~/sbt/bin/sbt test-fast` + `make SBT=~/sbt/bin/sbt test-verilator` → all pass; report totals.

- [ ] **Step 6: Re-synth the full core (the goal) — add s2m if backward ready is critical**

Run: `~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"` then
`vivado -mode batch -nojournal -log synth/vivado_FullCore.log -source synth/ooc_M68kFullCoreSynth.tcl 2>&1 | grep -iE "RESULT|Unsupported|multi-driven net on pin|CRITICAL WARNING|Synth Design complete"` (don't grep-out the RESULT line).
Expected: WNS ≥ 0 (≥ 250 MHz) post-synth, and the critical path NO LONGER the decode→rename→dispatch chain (check `synth/M68kFullCoreSynth_timing.rpt` Source/Destination). Report the new WNS + critical-path Source→Destination + logic levels.
- If WNS is still negative AND the new critical path is the **backward `ready`** chain (Source/Dest spanning dispatch.ready→…→ibuf, or a `ready`/`halt` net), add `.s2mPipe()` after the `PipeStage` (or register `in.ready`) at both boundaries and re-synth.
- If WNS is still negative on a DIFFERENT path (e.g. inside rename's async-RAT, or the IQ trigger), report it — may need a 3rd boundary (feed→decode) or is a separate concern; do NOT thrash, report the path.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "frontend: pipeline decode->rename + rename->dispatch (flushable skids); full core >=250MHz"
```

---

## Self-Review

**1. Spec coverage:** §3 skid both boundaries (T1 S2/S3), flushable + flush-tied-False (PipeStage + `pipeFlush := False`), uop1Valid from staged payload(1).valid (S2/S3), both-direction if needed (S6 s2m fallback). §4 verification: unit specs (S4), lock-step determinism (S5), synth re-gate + critical-path check (S6). §6 flush TODO: `pipeFlush` exists + tied False, wired in 3d (the PipeStage `flush` param). ✓

**2. Placeholder scan:** PipeStage code is complete; the s2m fallback (S6) is conditional-on-synth-evidence, not vague (explicit trigger + action). No TBD.

**3. Type consistency:** `PipeStage.apply[T](in: Stream[T], flush: Bool): Stream[T]` used at both boundaries with `DecodedUop`/`RenamedUop` Vec payloads. `uops`/`uop1Valid` overrides return the staged stream + its `payload(1).valid` consistently in both plugins. `pipeFlush: Bool` tied False both places.

**Note:** behavior is unchanged (pure timing); the lock-step determinism check (S5) is the correctness gate, the synth re-gate (S6) is the goal.
