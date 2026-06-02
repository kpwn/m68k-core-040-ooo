# Execute Integration + First Lock-Step (Slice 3c) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the whole execute backend together (rename → DispatchPlugin → ROB-alloc + IQ-push → 2 ALU EUs → completion → ROB retire) and run the first Musashi lock-step on straight-line programs via NaxRiscv-style sim-only whitebox observation.

**Architecture:** A dedicated `DispatchPlugin` consumes the renamed stream, takes robIds from the ROB's new passive `RobAllocService`, and fans each µop to ROB-alloc + IQ-push with combined back-pressure. The ROB stops consuming rename directly (passive alloc), gains two completion ports (replacing the test `markComplete`), and exposes a sim-public commit-obs. Each ALU EU exposes a sim-public writeback-obs (robId + value + flags + masks). The commit path does NO register-file reads — lock-step is reconstructed in the harness by joining writeback-by-robId with the commit stream (folding CCR), compared to Musashi via the existing `LockStep`. Built in 3 tasks: dispatch+ROB-alloc → backend whitebox path → full-core lock-step.

**Tech Stack:** SpinalHDL 1.14.1 / Scala 2.13 / sbt `~/sbt/bin/sbt` (NOT on PATH) / Verilator. Tests use `M68kSim` (PRF lowering in the loop). Reuses `LockStep`/`CommitObservation` (`src/test/scala/m68k040/lockstep/`), `Musashi.assembleAndTrace`/`OracleStep`. Reference for any nuance: NaxRiscv `frontend/DispatchPlugin.scala`, `misc/CommitPlugin.scala`, `misc/RegFilePlugin.scala`.

**Branch:** `feat/execute-integration` (created; spec committed).

**Key facts:** `RobPlugin` currently consumes `host[RenameUopService]` and self-allocs (RobPlugin.scala:34, 118-141) — this MOVES to DispatchPlugin. robIdW=6, depth=64. `IqContext={uop:RenamedUop, robId:UInt(6)}`. `IssueQueueService.push: Stream[Vec[IqContext]]` + `pushSlot1Valid`; `issue: Vec[Stream[IqContext]]`. `AluEuService.{issue:Stream[IqContext], completion:Flow[UInt(6)]}`. EU S1 has `s1Valid`, `s1Ctx` (IqContext, with `s1Ctx.uop.dstArch/.pdstValid/.writesNzvc/.writesX`), `rsp.{result,nzvc,xOut}`. `CommitObservation{pc,archRegId,archRegWrite,archRegValid,ccr,memAddr,memData,memWrite}`. `OracleStep{pc, sr, d, a}`, `ccr=sr&0x1f`. CCR bits: X=4,N=3,Z=2,V=1,C=0.

---

### Task 1: DispatchPlugin + ROB passive-alloc + 2 completion ports

**Files:**
- Modify: `src/main/scala/m68k040/services/Services.scala` (add `RobAllocService`)
- Modify: `src/main/scala/m68k040/rob/RobPlugin.scala` (passive alloc, 2 completions, commit-obs)
- Create: `src/main/scala/m68k040/dispatch/DispatchPlugin.scala`
- Create: `src/test/scala/m68k040/dispatch/DispatchSpec.scala` (+ reuse `IqSink`/a rename source)

- [ ] **Step 1: Add `RobAllocService`**

In `src/main/scala/m68k040/services/Services.scala` add (alongside the other traits):

```scala
import m68k040.rename.RenamedUop  // already imported

/** ROB exposes a passive allocation interface; DispatchPlugin drives it.
  * robId0/robId1 are the ring indices the next 0th/1st µop will occupy. */
trait RobAllocService {
  def allocReady: Bool                 // room for 2 (ROB drives)
  def robId0: UInt                     // = tail
  def robId1: UInt                     // = tail+1
  def allocFire: Bool                  // dispatch drives: commit the allocation this cycle
  def allocUop: Vec[RenamedUop]        // dispatch drives: the 2 µops (length 2)
  def allocSlot1: Bool                 // dispatch drives: 2nd µop valid
}
```

- [ ] **Step 2: Refactor `RobPlugin` to passive alloc + 2 completions + commit-obs**

In `src/main/scala/m68k040/rob/RobPlugin.scala`:
- Change the class to `extends FiberPlugin with CommitTraceService with RobAllocService`.
- REMOVE `val ru = host[RenameUopService]` and the dispatch block that reads `ru` (lines 119-136). REMOVE the `RenameUopService` import if now unused.
- Add the alloc interface signals (created in the `logic` Area, exposed via overrides). Replace the dispatch block with:

```scala
    // ── Passive alloc interface (driven by DispatchPlugin) ──────────────────────
    val allocReadySig = Bool();           allocReadySig := count <= (depth - 2)
    val allocFireSig  = Bool();           allocFireSig  := False   // dispatch overrides via the service wire
    val allocUopVec   = Vec(RenamedUop(), 2)
    val allocSlot1Sig = Bool()
    // NOTE: allocFireSig/allocUopVec/allocSlot1Sig are DRIVEN by DispatchPlugin through the
    // RobAllocService wires (plain-wire convention). Default-drive the inputs to avoid latch:
    allocUopVec.foreach(_.assignDontCare())   // dispatch overrides
    allocSlot1Sig := False                      // dispatch overrides
    allocFireSig  := False                      // dispatch overrides

    val alloc0 = allocFireSig
    val alloc1 = allocFireSig && allocSlot1Sig
    val allocThisCycle = (alloc1 ? U(2) | (alloc0 ? U(1) | U(0))).resize(count.getWidth)
    when(alloc0) {
      payload.write(tail, payloadFrom(allocUopVec(0)))
      valids(tail) := True; completes(tail) := False
    }
    when(alloc1) {
      payload.write(tail + 1, payloadFrom(allocUopVec(1)))
      valids(tail + 1) := True; completes(tail + 1) := False
    }
    when(allocFireSig) {
      tail := tail + Mux(allocSlot1Sig, U(2, robIdW bits), U(1, robIdW bits))
    }
```

  The catch: a plain-wire service input (`allocFireSig` etc.) driven by a SIBLING plugin (DispatchPlugin) must NOT also be default-driven here (double-drive). Follow the established plain-wire convention: the ROB *exposes* these as service wires; **DispatchPlugin drives them**; the ROB does NOT assign them. So declare `val allocFireSig = Bool()` / `allocUopVec = Vec(...)` / `allocSlot1Sig = Bool()` WITHOUT the default assignments (delete the `:= False`/`assignDontCare` lines above), exactly like `RenameStage.commitPorts` (driven by ROB) / `IcachePlugin.cmdPort` are plain wires driven by the consumer. (If elaboration complains about an undriven signal because no test drives them, the DispatchPlugin/test always drives them — that's the contract.)

- Replace `markComplete` (line 50, 139-141) with two completion ports:
```scala
    val completion = Vec.fill(2)(slave(Flow(UInt(robIdW bits))))
    for (c <- completion) when(c.valid) { completes(c.payload) := True }
```
- Add the sim-public commit-obs (after `driveCommit` calls):
```scala
    case class CommitObs() extends Bundle { val fire = Bool(); val robId = UInt(robIdW bits); val pc = UInt(32 bits) }
    val commitObs = Vec(CommitObs(), 2); commitObs.simPublic()
    commitObs(0).fire := retire0; commitObs(0).robId := h0; commitObs(0).pc := p0.predNextPc
    commitObs(1).fire := retire1; commitObs(1).robId := h1; commitObs(1).pc := p1.predNextPc
```
  (Keep the existing `traceVec`/`traceFireVec` + `CommitTraceService` overrides as-is — dormant; lock-step now uses `commitObs`. Optionally stop driving the stubbed trace, but leaving it is harmless.)
- Add the override methods (outside `logic`):
```scala
  override def allocReady = logic.allocReadySig
  override def robId0     = logic.tail
  override def robId1     = logic.tail + 1
  override def allocFire  = logic.allocFireSig
  override def allocUop   = logic.allocUopVec
  override def allocSlot1 = logic.allocSlot1Sig
```
  (`robId1 = tail+1` — expose as a combinational wire; if a `def` returning an expression each call is awkward, make `val robId1Sig = tail + 1` in logic and return it.)

- [ ] **Step 3: Create `DispatchPlugin`**

Create `src/main/scala/m68k040/dispatch/DispatchPlugin.scala`:

```scala
package m68k040.dispatch

import m68k040.services.{RenameUopService, RobAllocService}
import m68k040.execute.iq.{IqContext, IssueQueueService}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Consumes the renamed µop stream; takes robIds from the ROB alloc interface;
  * fans each µop to ROB-alloc AND IssueQueue-push with combined back-pressure. */
class DispatchPlugin extends FiberPlugin {
  val logic = during build new Area {
    val ren = host[RenameUopService]
    val rob = host[RobAllocService]
    val iq  = host[IssueQueueService]

    val fire = ren.uops.valid && rob.allocReady && iq.push.ready
    ren.uops.ready := rob.allocReady && iq.push.ready

    // drive ROB alloc
    rob.allocFire  := fire
    rob.allocUop(0) := ren.uops.payload(0)
    rob.allocUop(1) := ren.uops.payload(1)
    rob.allocSlot1 := ren.uop1Valid

    // drive IQ push (same robIds the ROB will use)
    iq.push.valid := fire
    iq.push.payload(0).uop := ren.uops.payload(0); iq.push.payload(0).robId := rob.robId0
    iq.push.payload(1).uop := ren.uops.payload(1); iq.push.payload(1).robId := rob.robId1
    iq.pushSlot1Valid := ren.uop1Valid
  }
}
```
Note: `ren.uops.ready` and `iq.push.valid`/`rob.allocFire` form the handshake; `fire` is asserted only when all three agree, so ROB and IQ allocate the same µops with the same robIds the same cycle. (`iq.push` is a Stream; `iq.push.valid := fire` and the ROB alloc gated by the same `fire` keep them in lockstep. Confirm the IQ `push.ready` and ROB `allocReady` are both pure-combinational/registered predicates that don't depend on `fire` in a loop — they don't: IQ ready is a registered line-0 predicate, ROB allocReady is `count<=depth-2`.)

- [ ] **Step 4: Unit test — DispatchSpec**

Create `src/test/scala/m68k040/dispatch/DispatchSpec.scala`: a Dut hosting a **rename source** (drive `RenameUopService.uops` from IO — adapt the existing `IqSourcePlugin`/`AluEuSourcePlugin` field-driving style, or reuse `m68k040.rob.RobTestHelpers.RenameUopSourcePlugin` if it exists), the `RobPlugin`, the `DispatchPlugin`, the `IssueQueuePlugin`, and a sink reading the IQ issue ports (`m68k040.execute.iq.IqSinkPlugin`). Tests (tag `VerilatorTest`, use `M68kSim`):
1. **Dispatch fans to ROB + IQ with matching robId:** push one renamed µop (pdst etc.); assert (a) it issues from the IQ with `robId == 0` (first alloc), (b) the ROB `count`/`tail` advanced. Push a second; robId 1. (Observe via the IQ sink's `rob0` output + ROB `tail.simPublic`.)
2. **2-wide:** push 2 valid µops one cycle; both enter (robIds 0 and 1); ROB tail += 2.
3. **Combined back-pressure:** force the IQ full (sink not ready, push 16) → `ren.uops.ready` deasserts even though ROB has room; and conversely fill the ROB → ready deasserts. (Assert the dispatch stalls when either is full.)

Write concrete bodies (drive the rename-source IO; read IQ sink + ROB `tail`/`count`). Keep strict.

- [ ] **Step 5: Run + commit**

Run: `~/sbt/bin/sbt "testOnly m68k040.dispatch.DispatchSpec"` (allow 590000 ms) → pass. Also run the EXISTING `m68k040.rob.RobPluginSpec` — it drove the OLD `markComplete`/`ru.uops` interface and will break; **update RobPluginSpec** to the new interface (drive alloc via a DispatchPlugin or directly via the `RobAllocService` wires; complete via the new `completion(0/1)` ports) — fix the spec to the new contract, do NOT weaken what it asserts (ring/retire/free-loop behavior). Then:
```bash
git add src/main/scala/m68k040/services/Services.scala src/main/scala/m68k040/rob/RobPlugin.scala src/main/scala/m68k040/dispatch/DispatchPlugin.scala src/test/scala/m68k040/dispatch/DispatchSpec.scala src/test/scala/m68k040/rob/RobPluginSpec.scala
git commit -m "dispatch: DispatchPlugin + ROB passive-alloc + 2 completion ports"
```

---

### Task 2: EU writeback-obs + backend whitebox lock-step (rename→dispatch→IQ→EU→ROB, no frontend)

**Files:**
- Modify: `src/main/scala/m68k040/execute/AluEuPlugin.scala` (sim-public `wbObs`)
- Create: `src/test/scala/m68k040/lockstep/WhiteboxCapture.scala`
- Create: `src/test/scala/m68k040/execute/BackendWhiteboxSpec.scala`

- [ ] **Step 1: Add `wbObs` to AluEuPlugin (sim-public)**

In `AluEuPlugin.logic`, at S1, add:
```scala
    // sim-only whitebox: per-instruction writeback observation (NaxRiscv-style)
    val wbObs = new Bundle {
      val valid     = Bool()
      val robId     = UInt(6 bits)
      val dstArch   = UInt(4 bits)
      val result    = Bits(32 bits)
      val intWrite  = Bool()
      val nzvc      = Bits(4 bits)
      val nzvcWrite = Bool()
      val x         = Bool()
      val xWrite    = Bool()
    }
    wbObs.valid     := s1Valid
    wbObs.robId     := s1Ctx.robId
    wbObs.dstArch   := u1.dstArch
    wbObs.result    := rsp.result
    wbObs.intWrite  := u1.pdstValid
    wbObs.nzvc      := rsp.nzvc
    wbObs.nzvcWrite := u1.writesNzvc
    wbObs.x         := rsp.xOut
    wbObs.xWrite    := u1.writesX
    wbObs.simPublic()
```
(`u1` = `s1Ctx.uop`. If `simPublic()` on an anonymous Bundle is awkward, make it a named `case class` field. It is sim-only — no synthesizable cost.)

- [ ] **Step 2: WhiteboxCapture (harness reconstruction)**

Create `src/test/scala/m68k040/lockstep/WhiteboxCapture.scala`:

```scala
package m68k040.lockstep

import spinal.core.sim._
import scala.collection.mutable

/** Reconstructs the per-retired-instruction CommitObservation stream from the
  * sim-only whitebox: EU writeback-obs (value+flags by robId) joined with the
  * ROB commit-obs (retire order + pc), folding the architectural CCR in commit
  * order. NaxRiscv-style: the synthesizable commit path does no RF reads. */
object WhiteboxCapture {
  final case class Wb(dstArch: Int, result: Long, intWrite: Boolean,
                      nzvc: Int, nzvcWrite: Boolean, x: Int, xWrite: Boolean)

  /** `sampleWb`: read each EU's wbObs this cycle -> Seq of (robId, Wb) for valid ones.
    * `sampleCommit`: read the ROB commitObs this cycle -> ordered Seq of (robId, pc) for fired ones.
    * Call both on every clock sampling. */
  final class Handle {
    private val wbMap   = mutable.HashMap[Int, Wb]()
    private val commits = mutable.ArrayBuffer[CommitObservation]()
    private var ccr     = 0   // running architectural CCR (X N Z V C), bit4..bit0

    def onWb(robId: Int, wb: Wb): Unit = { wbMap(robId) = wb }
    def onCommit(robId: Int, pc: Long): Unit = {
      val wb = wbMap.getOrElse(robId, sys.error(s"commit robId=$robId with no writeback observed"))
      if (wb.nzvcWrite) ccr = (ccr & 0x10) | (wb.nzvc & 0xf)        // N,Z,V,C bits
      if (wb.xWrite)    ccr = (ccr & 0x0f) | ((wb.x & 1) << 4)      // X bit
      commits += CommitObservation(
        pc = pc, archRegId = wb.dstArch,
        archRegWrite = wb.result, archRegValid = wb.intWrite,
        ccr = ccr, memAddr = 0, memData = 0, memWrite = false)
    }
    def result: Seq[CommitObservation] = commits.toSeq
  }
}
```
(The harness drives `onWb`/`onCommit` from `clockDomain.onSamplings` reading the DUT's `wbObs`/`commitObs` simPublic signals. CCR fold: NZVC ops set the low 4 bits; X-writers set bit 4; non-writers leave that part — matching the post-instruction architectural CCR.)

- [ ] **Step 3: Backend whitebox test (no frontend)**

Create `src/test/scala/m68k040/execute/BackendWhiteboxSpec.scala`: a Dut hosting a rename source + DispatchPlugin + RobPlugin + IssueQueuePlugin + two `AluEuPlugin`s + the 3 PRFs, wired: IQ `issue(0/1)` → the two EUs' `issue`; each EU `completion` → ROB `completion(0/1)`. A sim fork samples the two EU `wbObs` and the ROB `commitObs` into a `WhiteboxCapture.Handle` each cycle. Drive a short hand-built renamed program (e.g. two MOVEQ then an ADD reading them — express directly as RenamedUops via the source: pdst/psrc/op/imm/masks). After it drains, build the reconstructed `Seq[CommitObservation]` and assert the values match the expected (computed by hand or by `AluDatapath` semantics): the MOVEQ results, the ADD result, the flags. (This validates the whole backend path + the whitebox reconstruction WITHOUT the frontend/Musashi — a focused integration check.)

Write concrete bodies. Keep strict asserts on the reconstructed values.

- [ ] **Step 4: Run + commit**

Run: `~/sbt/bin/sbt "testOnly m68k040.execute.BackendWhiteboxSpec"` → pass. Iterate the wiring/timing (the EU is latency-1; a dependent issues 1 cycle after; the whitebox captures over enough cycles). Then:
```bash
git add src/main/scala/m68k040/execute/AluEuPlugin.scala src/test/scala/m68k040/lockstep/WhiteboxCapture.scala src/test/scala/m68k040/execute/BackendWhiteboxSpec.scala
git commit -m "execute: EU writeback-obs + backend whitebox reconstruction test"
```

---

### Task 3: Full-core top wiring + first Musashi lock-step (straight-line)

**Files:**
- Modify: `src/main/scala/m68k040/top/GenVerilog.scala` (full-core plugin set incl. IQ + 2 EUs + 3 PRFs + Dispatch)
- Create: `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`

- [ ] **Step 1: Full-core plugin set**

Add a plugin-set helper (e.g. in `GenVerilog` or a shared object) listing the full core:
`ParamPlugin, IdentityTranslationPlugin, IcachePlugin, FetchAlignPlugin, DecodeStage, RenameStage, DispatchPlugin, RobPlugin, IssueQueuePlugin, AluEuPlugin (×2), RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX`, plus the IQ-issue→EU wiring and EU-completion→ROB wiring. Since services auto-resolve via `host[...]`, the explicit wiring needed is: the two EUs each consume one `iq.issue(k)` and drive one `rob.completion(k)`. Add a tiny `BackendWiringPlugin` (in `during build`) that does:
```scala
    val iq = host[IssueQueueService]; val rob = host[RobPlugin]   // or a service exposing completion
    val eus = host.list[AluEuService]   // the 2 EUs
    for (k <- 0 until 2) { eus(k).issue << iq.issue(k); rob.logic.completion(k) <> eus(k).completion }
```
Resolve the exact handle for the ROB completion ports + the list of EUs per our framework (`host.list[T]` or two distinct EU instances referenced directly in the top). The cleanest: construct the two `AluEuPlugin`s explicitly in the top and wire `iq.issue(0)→eu0.issue`, `eu0.completion→rob.completion(0)`, etc., in a wiring plugin or the Dut. (Use `<<`/`>>` Stream/Flow connect or plain assignment per our convention.)

- [ ] **Step 2: End-to-end lock-step test**

Create `src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`. For each program: assemble it, run `Musashi.assembleAndTrace(src)` → `Vector[OracleStep]`; compile the full-core Dut (`M68kSim`), attach the I-cache AXI memory with the program image (reuse `IcacheSim.attachMemory`/`attachMemoryWithWords` — the program bytes), `redirect` FetchControl to the program start PC, run, sample `wbObs`+`commitObs` into a `WhiteboxCapture.Handle`; after N commits (= oracle step count), `LockStep.compare(handle.result, oracleSteps)` must be `ok`. Programs (straight-line, no taken branches):

```
1) moveq:    moveq #1,%d0 ; moveq #2,%d1 ; moveq #-1,%d2 ; moveq #0,%d3
2) alu-chain: moveq #10,%d0 ; moveq #3,%d1 ; add.l %d1,%d0 ; sub.l %d1,%d0 ; and.l %d1,%d0 ; or.l %d1,%d0
3) cmp:      moveq #5,%d0 ; moveq #5,%d1 ; cmp.l %d1,%d0 ; moveq #7,%d2
4) mixed ~24 instrs of moveq/add/sub/and/or/cmp on D0-D7 (straight-line)
```
Notes on the harness:
- `OracleStep.pc` is post-instruction; the reconstructed `CommitObservation.pc` = `commitObs.pc = predNextPc = pc+2`. For MOVEQ (2 bytes) and `.l` reg-reg ALU (2 bytes) this matches; if a program instruction is >2 bytes, `predNextPc=pc+2` is WRONG (the ROB stubs `pc+2`). **Keep the corpus to 2-byte instructions** (MOVEQ and reg-reg `.l`/.w/.b ALU/CMP are all 2 bytes) so `pc+2` is exact; longer instructions are out of scope until real PC-length flows through (logged in the spec).
- Start: pulse `redirect` to the program load PC; let the pipeline fill and retire.
- Stop: capture until `handle.result.size == oracleSteps.size` (or a cycle cap with an assert that enough committed).
- Initial state: PRFs init 0, committed RAT identity → regs start 0, matching Musashi reset. Lock-step compares each retired instruction's reg write + full CCR + pc.

Write concrete bodies; assert `res.ok` with the divergence detail in the failure message.

- [ ] **Step 3: Run + iterate (the milestone)**

Run: `~/sbt/bin/sbt "testOnly m68k040.lockstep.ExecuteLockStepSpec"` (allow 590000 ms; Musashi + full-core Verilator). Iterate: the most likely divergences are (a) pc convention (ensure `pc+2` matches; keep 2-byte ops), (b) CCR fold (X unchanged on non-X-writers — the fold leaves bit4; verify against Musashi which keeps X), (c) commit ordering vs whitebox capture timing (sample every cycle; a commit's writeback was observed earlier). Fix the harness/wiring (NOT the asserted Musashi values). This passing = the **first full-core lock-step**.

- [ ] **Step 4: Suites + commit**

Run: `make SBT=~/sbt/bin/sbt test-fast` → all pass; report total.
Run: `make SBT=~/sbt/bin/sbt test-verilator` → all pass; report total.
```bash
git add src/main/scala/m68k040/top/GenVerilog.scala src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala
git commit -m "execute: full-core wiring + first Musashi lock-step (straight-line)"
```

---

## Self-Review

**1. Spec coverage:**
- §3.1 ROB passive alloc + 2 completion + commit-obs → Task 1 Step 2. ✓
- §3.2 DispatchPlugin (rename→ROB+IQ, combined ready) → Task 1 Step 3. ✓
- §3.3 EU writeback-obs → Task 2 Step 1. ✓
- §3.4 IQ→EU + EU→ROB-completion wiring → Task 3 Step 1 (+ Task 2 Dut). ✓
- §3.5 harness reconstruction (join wb-by-robId + commit order, fold CCR) → Task 2 Step 2 (`WhiteboxCapture`). ✓
- §5 verification: dispatch unit (T1.4), backend whitebox (T2.3), end-to-end lock-step ×4 programs (T3.2). ✓
- Commit→freelist real loop: the ROB already drives `RenameCommitService.commitPorts` at retire (unchanged); exercised by the long program (T3 #4). ✓

**2. Placeholder scan:** The ROB plain-wire alloc-input convention (Step 2) has a "delete the default assignments" note — that's a real instruction (plain-wire service inputs are driven by the sibling, not defaulted), with the established pattern cited. Task 3 Step 1's wiring uses "resolve the exact handle per our framework" — bounded (construct 2 EUs explicitly, wire issue/completion); the connect idiom is shown. Not vague placeholders. Test bodies are described with exact programs + the reconstruction asserted.

**3. Type consistency:** `RobAllocService{allocReady,robId0,robId1,allocFire,allocUop:Vec[RenamedUop],allocSlot1}` consistent between Services, RobPlugin overrides, and DispatchPlugin drives. `IqContext{uop,robId}` push consistent. `completion: Vec(Flow(UInt(6)),2)` ROB ← EU `completion: Flow(UInt(6))`. `wbObs` fields consumed by `WhiteboxCapture.Wb` (dstArch/result/intWrite/nzvc/nzvcWrite/x/xWrite) match. `CommitObservation` fields match `LockStep`. CCR bit layout (X=4,N=3,Z=2,V=1,C=0) consistent in the fold + the EU's nzvc(N3Z2V1C0)+x.

**Note:** Task 1 breaks `RobPluginSpec` (old interface) — fixing it to the new alloc/completion contract is explicitly in T1 Step 5, preserving its asserted ring/retire/free-loop behavior.
