# Decode→ring Push Register (Front-End FMax P1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register the decode→`MicroOpQueue` push payload so the deep `MicroOpAssembler` decode cone ends at a register and the ring write consumes only registered signals — clearing the post-route front-end FMax limiter (~204 MHz, 77% route) while keeping the flop ring.

**Architecture:** Insert a 1-deep `PipeStage` (m2s register, flush-squashed) between the produced push `{4 µops, count, valid}` and `queue.io.push` in `DecodeStage`. Move the stash/backpressure "group accepted" signal from `queue.io.push.ready` to the register's input-ready. `MicroOpQueue` is untouched. If P1 misses 250 post-route, escalate within flops (depth 16→8, then payload-slim), measured.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt at `~/sbt/bin/sbt` (NOT on PATH) / Verilator / Vivado 2025.2 (`xcku5p-ffvb676-2-e`, OOC 4 ns). Lock-step vs `m68k040.oracle.Musashi`.

**Working dir:** isolated git worktree on `feat/decode-push-register` off current `master` (`02a004e`). Run all `sbt`/`vivado` from the worktree root.

**Memory discipline (HARD):** NEVER run Verilator and Vivado concurrently. NEVER run the whole `ExecuteLockStepSpec` (OOMs a 12 GB heap) — always `-z "<substr>"` subsets, `JAVA_OPTS=-Xmx10g`. Before any vivado run, `pgrep -af vivado`; a separate m68k030 QoR job may run — only ever kill `040`/`FullCore` jobs. `make test-fast` (`sbt fastTest`) EXCLUDES `VerilatorTest` — run those explicitly with `testOnly`.

---

## File Structure

- `src/main/scala/m68k040/decode/DecodeStage.scala` — **the only RTL change.** Refactor the push production into a `Stream[PushPayload]`, register it via `PipeStage`, drive `queue.io.push` from the register, and move the stash/`fed.ready` "accepted" signal to the register input. `MicroOpQueue.scala` is NOT touched (except depth, only as the conditional escalation in Task 4).
- Tests (run only, no change expected): `MicroOpQueueSpec`, `DecodeStageSpec`, `DecodeCrackPipeSpec`, `CrackLoadSpec`, `CrackStoreSpec`, `MemRmwDecodeSpec`, `ExecuteLockStepSpec` (subsets), `IpcBenchSpec`.

Reference — the existing `PipeStage` API (`frontend/PipeStage.scala`): `PipeStage[T](in: Stream[T], flush: Bool): Stream[T]` — 1-deep m2s register; `in.ready = !heldValid || out.ready`; `flush` clears the held entry. It is already used for the `fed` boundary.

---

## Task 1: P1 — register the decode→ring push

**Files:**
- Modify: `src/main/scala/m68k040/decode/DecodeStage.scala` (the push-production block, currently lines ~98–127)

The current code (for reference) drives `queue.io.push.*` combinationally and keys the stash + `fed.ready` off `queue.io.push.ready`:

```scala
    val nCur = Mux(stashValid, stashCount, a0.count)
    val n1   = Mux(slot1Emit, a1raw.count, U(0, 2 bits))
    def headUop(i: Int): DecodedUop = Mux(stashValid, stashUops(i), a0.uops(i))
    queue.io.push.uops(0) := headUop(0)
    queue.io.push.uops(1) := Mux(nCur >= U(2), headUop(1), a1raw.uops(0))
    queue.io.push.uops(2) := Mux(nCur === U(3), headUop(2), Mux(nCur === U(2), a1raw.uops(0), a1raw.uops(1)))
    queue.io.push.uops(3) := a1raw.uops(1)
    val totalCount = (nCur +^ n1).resize(3)
    queue.io.push.count := totalCount
    queue.io.push.valid := stashValid || fed.valid
    fed.ready := !stashValid && queue.io.push.ready
    when(queue.io.push.ready) {
      when(stashValid) { stashValid := False }
      elsewhen(deferSlot1 && fed.valid) {
        stashValid := True; stashCount := a1raw.count
        for (i <- 0 until 3) { stashUops(i) := a1raw.uops(i) }
      }
    }
```

- [ ] **Step 1: Add the `PushPayload` bundle**

In `DecodeStage` (next to the existing `FedPacket` case class, ~line 33), add:

```scala
  // Push payload registered between the decode/pack output and the MicroOpQueue write
  // (P1 timing skid): the 4 packed µops + their count. Carried through a 1-deep PipeStage
  // so the deep MicroOpAssembler decode cone ends at a register and the ring write
  // consumes only registered signals (kills the decode-cone half of the push critical arc).
  case class PushPayload() extends Bundle {
    val uops  = Vec(DecodedUop(), 4)
    val count = UInt(3 bits)
  }
```

- [ ] **Step 2: Produce a push Stream instead of driving the queue directly**

Replace the `queue.io.push.uops/count/valid := …` assignments with a `pushProduced : Stream[PushPayload]` driven by the SAME expressions:

```scala
    val pushProduced = Stream(PushPayload())
    pushProduced.valid           := stashValid || fed.valid
    pushProduced.payload.uops(0) := headUop(0)
    pushProduced.payload.uops(1) := Mux(nCur >= U(2), headUop(1), a1raw.uops(0))
    pushProduced.payload.uops(2) := Mux(nCur === U(3), headUop(2), Mux(nCur === U(2), a1raw.uops(0), a1raw.uops(1)))
    pushProduced.payload.uops(3) := a1raw.uops(1)
    pushProduced.payload.count   := totalCount
```

(`nCur`, `n1`, `headUop`, `totalCount` stay exactly as they are above this block.)

- [ ] **Step 3: Register the push and drive the queue from it**

```scala
    // P1: register the produced push. The deep `assemble` cone ends at pushReg's input;
    // the ring write in N+1 is a shallow, register-driven broadcast. Flushed by the SAME
    // pipeFlush that squashes `fed` and the queue, so a held wrong-path group is discarded.
    val pushReg = PipeStage(pushProduced, pipeFlush)
    queue.io.push.valid := pushReg.valid
    queue.io.push.count := pushReg.payload.count
    queue.io.push.uops  := pushReg.payload.uops
    pushReg.ready       := queue.io.push.ready
```

- [ ] **Step 4: Move backpressure + stash advance to the produce side**

The "group accepted" signal is now the register's input-ready (`pushProduced.ready =
!pushReg.heldValid || queue.io.push.ready`), not `queue.io.push.ready` directly:

```scala
    // Consume the fed group / advance the stash when the PRODUCED group enters pushReg.
    fed.ready := !stashValid && pushProduced.ready
    when(pushProduced.ready) {
      when(stashValid) {
        stashValid := False                  // the stashed slot1/RTR was emitted (into pushReg) this cycle
      } elsewhen(deferSlot1 && fed.valid) {
        stashValid  := True                  // defer slot1 (3-µop slot0 or slot1)
        stashCount  := a1raw.count
        for (i <- 0 until 3) { stashUops(i) := a1raw.uops(i) }
      }
    }
```

Leave the existing `when(pipeFlush) { stashValid := False }` (line ~85) unchanged — the stash and `pushReg` are now both squashed on a redirect.

- [ ] **Step 5: Compile**

Run: `~/sbt/bin/sbt compile`
Expected: success, no `NO DRIVER` / latch / type errors. (`Vec` direct-assign `queue.io.push.uops := pushReg.payload.uops` is legal; both are `Vec(DecodedUop(),4)`.)

- [ ] **Step 6: Queue-contract regression (storage untouched)**

Run: `~/sbt/bin/sbt 'testOnly m68k040.decode.MicroOpQueueSpec'`
Expected: PASS unchanged — `MicroOpQueue` was not modified, this guards against accidental contract drift.

- [ ] **Step 7: Decode + crack specs (the stash/3-µop interplay through the new register)**

Run:
```bash
~/sbt/bin/sbt 'testOnly m68k040.decode.DecodeStageSpec' \
              'testOnly m68k040.decode.DecodeCrackPipeSpec' \
              'testOnly m68k040.decode.CrackLoadSpec' \
              'testOnly m68k040.decode.CrackStoreSpec' \
              'testOnly m68k040.decode.MemRmwDecodeSpec'
```
Expected: all PASS. These exercise the 2-µop and 3-µop cracks + the deferred-slot1 stash replay across the added skid. If a test asserts a fixed cycle for when a µop appears at `pop`, the µop now arrives one cycle later — bump that test's settle/`waitSampling` count by 1 (timing only; the µop VALUES/order are unchanged). A divergence in µop order/content is a real bug (stash/backpressure), not a timing fix.

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/m68k040/decode/DecodeStage.scala
git commit -m "decode: register the decode->ring push (P1 front-end FMax); decode cone ends at a register, ring write consumes registers"
```

---

## Task 2: Full lock-step regression + IPC + test-fast

**Files:** none (run only).

- [ ] **Step 1: Lock-step vs Musashi — redirect / crack / loop / IRQ subsets**

The added skid changes front-end timing; correctness is latency-agnostic (robId join), so all of these must still pass. Run subsets (NEVER the whole spec):
```bash
~/sbt/bin/sbt 'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "loop"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "call"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "RMW"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "IRQ"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "bne"' \
              'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "DBcc"'
```
Expected: all PASS, 0 diverged, 0 "Simulation failed". Rationale: `call` (RTR 3-µop crack + redirect), `RMW` (mem-dest 3-µop crack), `loop`/`bne`/`DBcc` (branch redirect → `pipeFlush` must squash `pushReg`), `IRQ` (redirect mid-stream). A deadlock ("Simulation failed at time=…") or divergence here = a flush/backpressure/stash bug in Task 1.

- [ ] **Step 2: IPC — confirm the +1 push cycle is throughput-neutral**

Run: `JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec'`
Expected: PASS. dependent-ALU / independent-ALU / aggregate within noise of the current numbers (aggregate ≈ 0.496). A material aggregate drop means the queue is NOT acting as a skid (back-pressuring the front end) — investigate before proceeding; do NOT merge a real IPC regression.

- [ ] **Step 3: test-fast (full non-Verilator gate)**

Run: `~/sbt/bin/sbt fastTest`
Expected: `All tests passed.` (~91).

- [ ] **Step 4: Commit (only if a test's settle-timing needed bumping)**

```bash
git add -A
git commit -m "test: bump decode-spec settle windows for the +1 push-register cycle (values unchanged); lock-step + IPC + test-fast green"
```

---

## Task 3: OOC worst-path + utilization measurement, adaptive decision

**Files:** uses `synth/ooc_M68kFullCoreSynth.tcl`; conditionally re-modifies `DecodeStage.scala` (depth).

- [ ] **Step 1: Generate the branch netlist**

Run: `JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt "runMain m68k040.top.GenFullCoreSynthVerilog"`
Expected: `Generated generated/M68kFullCoreSynth.v`.

- [ ] **Step 2: OOC synth + worst-path + utilization (vivado — NO Verilator concurrent)**

`pgrep -af vivado` first (spare any `030`). Then:
```bash
timeout 1800 vivado -mode batch -nojournal -nolog -source synth/ooc_M68kFullCoreSynth.tcl
grep -nE "Slack \(VIOLATED|Source:|Destination:|Logic Levels:|Data Path Delay" synth/M68kFullCoreSynth_timing.rpt | head -16
grep -nE "Slice Registers|LUT as Memory|RAMB|Slice LUTs" synth/M68kFullCoreSynth_util.rpt | head
```
Expected: a `RESULT FullCore WNS … FMAX …` line. Confirm: (a) the decode `assemble` cone is OFF the push path (worst path no longer `fed_payload → ring` via decode LUTs); (b) the ring is STILL flops (no unexpected `LUT as Memory`/`RAMB` growth — P1 must not have accidentally inferred RAM); (c) FMax up from ~204 OOC.

- [ ] **Step 3: Adaptive decision gate (log the branch taken)**

- Worst path is no longer the decode→ring push cone → **stop here**, record the new limiter, go to Task 4.
- Worst path is now the registered broadcast `pushReg → DecodeStage…queue/ring_*` (shallow, route-bound, into 16 slots) and FMax still < ~250 OOC → **escalate: depth 16→8.** Change `DecodeStage.scala` line ~42 `new MicroOpQueue(depth = 16)` to `depth = 8`, then:
  ```bash
  JAVA_OPTS=-Xmx10g ~/sbt/bin/sbt 'testOnly m68k040.bench.IpcBenchSpec' \
    'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "loop"' \
    'testOnly m68k040.lockstep.ExecuteLockStepSpec -- -z "RMW"'
  ```
  Expected: IPC aggregate unchanged (the skid is rarely full at depth 8; if it drops, depth-8 is too shallow — revert to 16 and note it). Lock-step green. Then re-gen + re-measure (Step 1–2). Commit: `git commit -am "decode: MicroOpQueue depth 16->8 (front-end FMax escalation); IPC neutral, lock-step green"`.
- If depth-8 still misses 250 → the residual is the 197-b width broadcast; **payload-slim is a separate slice** (own spec, touches the decode→rename contract). Do NOT attempt it here — record it as the recommended follow-up and proceed to Task 4 with the best result so far.

- [ ] **Step 4: Commit the measurement note**

```bash
git add -A 2>/dev/null
git commit --allow-empty -m "synth: OOC measurement for decode-push-register — <FMax>, worst path now <path>; <stopped at P1 | escalated depth 16->8>"
```

---

## Task 4: Post-route gate + honest report

**Files:** uses `synth/impl_FullCore.tcl`.

- [ ] **Step 1: Post-route P&R (vivado — NO Verilator concurrent)**

Ensure `generated/M68kFullCoreSynth.v` is the final branch netlist (re-gen if RTL changed in Task 3). `pgrep -af vivado` first. Then:
```bash
timeout 3600 vivado -mode batch -nojournal -nolog -source synth/impl_FullCore.tcl
grep -nE "POSTROUTE_FULLCORE_WNS_NS|POSTROUTE_FULLCORE_RESULT|Slack \(VIOLATED|Source:|Destination:|Logic Levels" synth/fullcore_route_timing.rpt | head -12
```
Expected: a `POSTROUTE_FULLCORE_RESULT …` line + WNS. Read the worst path.

- [ ] **Step 2: Honest report**

Record: post-route FMax before (~204, baseline `synth/fullcore_route_timing_lat2-baseline.rpt` is lat2; the lat3/shifter-merge baseline ≈ 201–204) vs after this slice; the new worst path; whether the decode→ring cone is gone post-route; latency chosen (P1 only / +depth-8). Per the standing rule, the post-route number is authoritative: if it lands ≥250, great; if between ~204 and 250 with the front-end cone gone (new limiter elsewhere) or remaining (→ payload-slim follow-up), report honestly and let the user decide on merge — the slice still wins if it lifts FMax and isolates the next limiter.

- [ ] **Step 3: Final commit**

```bash
git add -A 2>/dev/null; git commit --allow-empty -m "synth: post-route gate for decode-push-register — <FMax> (was ~204); worst path now <path>"
```

---

## Done-When

- P1 implemented in `DecodeStage` only; `MicroOpQueue` storage/contract untouched (depth is the only queue knob, and only if escalated).
- `MicroOpQueueSpec` + decode/crack specs green (stash/3-µop interplay through the skid); full lock-step subsets (redirect/RTR/RMW/loop/IRQ) green, 0 diverged.
- `IpcBenchSpec` aggregate unchanged; `fastTest` green.
- OOC: decode cone off the push path, ring still flops (no inferred RAM), FMax up from ~204; adaptive branch logged.
- Post-route FMax measured and reported honestly (authoritative gate).
- Memory updated (`synth-gate-every-slice.md`) with the result + new worst path; if payload-slim is still needed, record it as the next slice.
