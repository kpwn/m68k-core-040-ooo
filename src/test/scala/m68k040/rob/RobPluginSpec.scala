package m68k040.rob

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import m68k040.decode.{DecodeStage, DecOp}
import m68k040.exception.InterruptControlPlugin
import m68k040.rename.{RenameStage, RenamedUop}
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class RobPluginSpec extends AnyFunSuite {

  // ── Simple DUT: fake rename source + rob + fake commit sink + trace sink ──────
  class SimpleDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val drv  = new RobAllocDriverPlugin
    val rob  = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    val cacheCtrl = new CacheControlSinkPlugin
    val dsink = new DebugCommitSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink, cacheCtrl, dsink)) }
  }

  /** Poke a RenamedUop slot with sane defaults. */
  def pokeRu(
      u: RenamedUop,
      valid: Boolean = true,
      pc: Long = 0,
      dstArch: Int = 0,
      pdst: Int = 0, pdstValid: Boolean = false, pdstOld: Int = 0,
      writesNzvc: Boolean = false, pNzvcDst: Int = 0, pNzvcOld: Int = 0,
      writesX: Boolean = false, pXDst: Int = 0, pXOld: Int = 0,
      isBranch: Boolean = false,
      firstOfInstr: Boolean = false,
      lastOfInstr: Boolean = true,
      debugBreakValid: Boolean = false,
      debugBreakSlot: Int = 0
  ): Unit = {
    u.valid #= valid
    u.pc #= pc
    u.nextPc #= pc + 2   // 2-byte instruction model: commit pc = nextPc = pc + 2
    u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= isBranch; u.cond #= 0; u.branchDisp #= 0
    u.unimplemented #= false
    u.dstArch #= dstArch
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= pdst; u.pdstValid #= pdstValid; u.pdstOld #= pdstOld
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= pNzvcDst; u.writesNzvc #= writesNzvc; u.pNzvcOld #= pNzvcOld
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= pXDst; u.writesX #= writesX; u.pXOld #= pXOld
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
    u.needsSupervisor #= false   // Track C field (privViolation): inert, else garbage spuriously blocks retire
    u.firstOfInstr #= firstOfInstr   // macro boundary marker (Stage 2 task 3: real debugMacroCount consumer)
    // Trailing macro-boundary marker (Stage 2 task 4: RobPayload.last -> h0IsMacroLast).
    // Defaults True, matching DecodedUop.lastOfInstr's own "a single-µop macro is its own
    // first AND last µop" default -- a test that does not care pokes single-µop macros.
    u.lastOfInstr #= lastOfInstr
    u.debugBreakValid #= debugBreakValid
    u.debugBreakSlot #= debugBreakSlot
  }

  def initSimple(dut: SimpleDut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.completion(0).valid #= false
    dut.rob.logic.completion(1).valid #= false
    dut.rob.logic.flush.valid #= false
    dut.rob.logic.debugStopRequestIn #= false
    dut.rob.logic.debugResumeRequestIn #= false
    dut.rob.logic.debugStepRequestIn #= false
    dut.rob.logic.debugClearStickyIn #= false
    dut.rob.logic.debugSystemApplyIn.valid #= false
    dut.rob.logic.haltAfterTargetIn #= 0
    dut.rob.logic.haltAfterEpochIn #= 0
    dut.rob.logic.haltAfterArmedIn #= false
    dut.rob.logic.haltAfterInvalidateIn #= false
    dut.rob.logic.haltExceptionMaskIn #= 0
    cd.waitSampling()
  }

  /** Mark a single robId complete this cycle on completion port 0. */
  def markComplete(dut: SimpleDut, robId: Int): Unit = {
    dut.rob.logic.completion(0).valid #= true
    dut.rob.logic.completion(0).payload #= robId
  }
  def clearComplete(dut: SimpleDut): Unit = {
    dut.rob.logic.completion(0).valid #= false
  }

  /** Poll a combinational signal CHECK-FIRST. `waitSamplingWhere` waits one edge
    * BEFORE checking, which consumes a self-clearing one-cycle combinational pulse
    * (e.g. `fireOut`/retire, high only until the next edge advances head/count).
    * Check-first catches the pulse in the cycle it is asserted. */
  def waitUntil(cd: ClockDomain, cond: => Boolean, max: Int = 200): Unit = {
    var n = 0
    while (!cond) { assert(n < max, "waitUntil timed out"); n += 1; cd.waitSampling() }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("alloc + 2-wide retire") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // Dispatch 2 uops (robId 0,1), both write D-regs.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 3, pdst = 20, pdstValid = true, pdstOld = 3)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x200, dstArch = 5, pdst = 21, pdstValid = true, pdstOld = 5)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()

      // Mark robId complete. Flow is single-port; mark 1 FIRST (head=0 won't retire
      // since head incomplete), then 0 so both land complete -> 2-wide retire.
      markComplete(dut, 1)
      cd.waitSampling()
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      clearComplete(dut)

      // Now head=0, both complete: expect 2-wide retire in the same cycle.
      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      assert(dut.tsink.logic.fireOut(0).toBoolean, "trace fire 0")
      assert(dut.tsink.logic.fireOut(1).toBoolean, "trace fire 1 (2-wide retire)")
      assert(dut.tsink.logic.traceOut(0).archRegId.toInt == 3, s"slot0 archRegId=${dut.tsink.logic.traceOut(0).archRegId.toInt}")
      assert(dut.tsink.logic.traceOut(1).archRegId.toInt == 5, s"slot1 archRegId=${dut.tsink.logic.traceOut(1).archRegId.toInt}")
      // pc = predNextPc = pc + 2
      assert(dut.tsink.logic.traceOut(0).pc.toLong == 0x102, s"slot0 pc=${dut.tsink.logic.traceOut(0).pc.toLong}")
      assert(dut.tsink.logic.traceOut(1).pc.toLong == 0x202, s"slot1 pc=${dut.tsink.logic.traceOut(1).pc.toLong}")
      // commit ports drive correct old pdsts (for free)
      assert(dut.csink.logic.commitValidOut(0).toBoolean && dut.csink.logic.commitValidOut(1).toBoolean, "both commit valid")
      assert(dut.csink.logic.commitOldOut(0).toInt == 3, "commit0 old")
      assert(dut.csink.logic.commitOldOut(1).toInt == 5, "commit1 old")
      // head advances by 2 -> after this cycle ROB empty
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 0, s"count after 2-wide retire = ${dut.rob.logic.count.toInt}")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("in-order: complete robId 1 but not 0 -> no retire until 0 completes") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 1, pdst = 20, pdstValid = true, pdstOld = 1)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x200, dstArch = 2, pdst = 21, pdstValid = true, pdstOld = 2)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()

      // Complete ONLY robId 1.
      markComplete(dut, 1)
      cd.waitSampling()
      clearComplete(dut)

      // For several cycles, no retire (head=0 not complete).
      for (_ <- 0 until 5) {
        assert(!dut.tsink.logic.fireOut(0).toBoolean, "no retire while head incomplete")
        cd.waitSampling()
      }
      assert(dut.rob.logic.count.toInt == 2, s"count still 2, got ${dut.rob.logic.count.toInt}")

      // Now complete robId 0 -> 2-wide retire fires.
      markComplete(dut, 0)
      cd.waitSampling()
      clearComplete(dut)
      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      assert(dut.tsink.logic.fireOut(0).toBoolean && dut.tsink.logic.fireOut(1).toBoolean, "both retire once head completes")
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 0, "ROB drained")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("retireAlone: branch retires 1-wide even if head+1 complete") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // slot0 = branch (retireAlone), slot1 = normal.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 0, pdstValid = false, isBranch = true)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x200, dstArch = 4, pdst = 21, pdstValid = true, pdstOld = 4)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()

      // Complete slot1 FIRST (won't retire, head=branch incomplete), then slot0.
      markComplete(dut, 1)
      cd.waitSampling()
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      clearComplete(dut)

      // head=0 is a branch -> retire 1-wide only this cycle (slot1 held back).
      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      assert(dut.tsink.logic.fireOut(0).toBoolean, "branch retires")
      assert(!dut.tsink.logic.fireOut(1).toBoolean, "retireAlone: slot1 must NOT retire same cycle")
      // The branch retire (count 2 -> 1, head -> 1) applies at the next edge.
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 1, s"only branch retired, count=${dut.rob.logic.count.toInt}")
      // slot1 (now head) is already complete -> it retires this cycle.
      assert(dut.tsink.logic.fireOut(0).toBoolean, "slot1 retires after branch")
      assert(dut.tsink.logic.traceOut(0).archRegId.toInt == 4, "second retire is the D-reg writer")
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 0, "ROB drained")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("flush squashes in-flight entries") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // Dispatch 2 (uncompleted).
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 1, pdst = 20, pdstValid = true, pdstOld = 1)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x200, dstArch = 2, pdst = 21, pdstValid = true, pdstOld = 2)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 2, "2 in flight before flush")

      // Flush.
      dut.rob.logic.flush.valid #= true
      sleep(1) // let combinational flushPort settle
      assert(dut.csink.logic.flushOut.toBoolean, "flushPort asserted combinationally")
      // No spurious retire during flush.
      assert(!dut.tsink.logic.fireOut(0).toBoolean && !dut.tsink.logic.fireOut(1).toBoolean, "no retire during flush")
      cd.waitSampling()
      dut.rob.logic.flush.valid #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 0, s"ROB empty after flush, count=${dut.rob.logic.count.toInt}")
      assert(dut.rob.logic.tail.toInt == dut.rob.logic.head.toInt, "tail==head after flush")

      // A new dispatch starts cleanly at head==tail.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x300, dstArch = 7, pdst = 30, pdstValid = true, pdstOld = 7)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 1, "one new entry after flush")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("stale completion after flush does NOT retire a freshly re-allocated entry") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // Dispatch 2 uncompleted uops -> robId 0,1 occupy the ring.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 1, pdst = 20, pdstValid = true, pdstOld = 1)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x200, dstArch = 2, pdst = 21, pdstValid = true, pdstOld = 2)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 2, "2 in flight before flush")

      // Flush: pointer reset (tail:=head, count:=0). Entries 0,1 are now squashed.
      dut.rob.logic.flush.valid #= true
      cd.waitSampling()
      dut.rob.logic.flush.valid #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 0, "ROB empty after flush")
      val reuseId = dut.rob.logic.tail.toInt // next alloc lands here (a now-squashed id)

      // A wrong-path completion arrives LATE for the squashed robId -> sets a stale
      // completes(reuseId):=True. (Fire it on the same cycle we re-allocate that id,
      // so alloc-priority ordering is what must win.)
      // Re-allocate ONE new uop (single-wide) at reuseId and do NOT complete it.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x300, dstArch = 7, pdst = 30, pdstValid = true, pdstOld = 7)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      markComplete(dut, reuseId) // stale completion on the index being re-allocated
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      clearComplete(dut)
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 1, s"one new entry after re-alloc, count=${dut.rob.logic.count.toInt}")

      // The freshly re-allocated entry is NOT complete (alloc-reset beat the stale
      // completion). It must NOT retire over the next several cycles.
      for (_ <- 0 until 6) {
        assert(!dut.tsink.logic.fireOut(0).toBoolean,
          "stale completion must NOT retire the re-allocated entry (alloc-reset must win)")
        cd.waitSampling()
      }
      assert(dut.rob.logic.count.toInt == 1, "entry still in flight (never retired)")

      // Now legitimately complete it -> it retires (proves it was a normal live entry).
      markComplete(dut, reuseId)
      cd.waitSampling()
      clearComplete(dut)
      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      assert(dut.tsink.logic.traceOut(0).archRegId.toInt == 7, "retired entry is the re-allocated uop")
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 0, "ROB drained after legit completion")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("mispredicting branch retire fires a REGISTERED doFlush pulse + flushPc + self-squash") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)
      dut.rob.logic.branchCompletion.valid #= false

      // Allocate a single branch (retireAlone) at robId 0.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x1000, dstArch = 0, pdstValid = false, isBranch = true)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 1, "one branch in flight")

      // Branch EU completion: robId 0 mispredicted, resolved nextPc = 0xBEEF.
      dut.rob.logic.branchCompletion.valid #= true
      dut.rob.logic.branchCompletion.payload.robId #= 0
      dut.rob.logic.branchCompletion.payload.mispredict #= true
      dut.rob.logic.branchCompletion.payload.nextPc #= 0xBEEF
      cd.waitSampling()
      dut.rob.logic.branchCompletion.valid #= false

      // The branch retires (1-wide). doFlush is REGISTERED -> it pulses the cycle
      // AFTER retire0 asserts. Wait for the retire, then check next-cycle pulse.
      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
      // commit trace pc must be the RESOLVED nextPc, not predNextPc(=pc+2=0x1002).
      assert(dut.tsink.logic.traceOut(0).pc.toLong == 0xBEEF,
        s"branch trace pc must be resolved nextPc, got 0x${dut.tsink.logic.traceOut(0).pc.toLong.toHexString}")
      // doFlush is registered: not asserted in the retire cycle yet.
      assert(!dut.rob.logic.doFlushReg.toBoolean, "doFlush must be registered (not combinational with retire)")
      cd.waitSampling()
      // Now the registered pulse fires with the resolved PC.
      assert(dut.rob.logic.doFlushReg.toBoolean, "doFlush pulses the cycle after the mispredicting branch retires")
      assert(dut.rob.logic.flushPcReg.toLong == 0xBEEF, s"flushPc = 0x${dut.rob.logic.flushPcReg.toLong.toHexString}")
      // ROB self-squashes (pointer-only): count -> 0, tail == head.
      cd.waitSampling()
      assert(dut.rob.logic.doFlushReg.toBoolean == false, "doFlush is a one-cycle pulse")
      assert(dut.rob.logic.count.toInt == 0, s"ROB self-squashed, count=${dut.rob.logic.count.toInt}")
      assert(dut.rob.logic.tail.toInt == dut.rob.logic.head.toInt, "tail==head after self-squash")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("correctly-predicted branch retire does NOT fire doFlush") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)
      dut.rob.logic.branchCompletion.valid #= false

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x2000, dstArch = 0, pdstValid = false, isBranch = true)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()

      // Not mispredicted.
      dut.rob.logic.branchCompletion.valid #= true
      dut.rob.logic.branchCompletion.payload.robId #= 0
      dut.rob.logic.branchCompletion.payload.mispredict #= false
      dut.rob.logic.branchCompletion.payload.nextPc #= 0x2002
      cd.waitSampling()
      dut.rob.logic.branchCompletion.valid #= false

      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
      // No mispredict -> no doFlush over the next few cycles.
      for (_ <- 0 until 4) {
        assert(!dut.rob.logic.doFlushReg.toBoolean, "no doFlush for a correctly-predicted branch")
        cd.waitSampling()
      }
      assert(dut.rob.logic.count.toInt == 0, "branch retired normally, ROB drained")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("BTB training carries exact branch length through retire, wrap, flush, and non-branch suppression") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)
      dut.rob.logic.branchCompletion.valid #= false

      def allocBranch(pc: Long): Int = {
        val id = dut.rob.logic.tail.toInt
        pokeRu(dut.rsrc.logic.src.payload(0), pc = pc, dstArch = 0,
          pdstValid = false, isBranch = true)
        dut.rsrc.logic.src.valid #= true
        dut.rsrc.logic.u1v #= false
        cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
        dut.rsrc.logic.src.valid #= false
        cd.waitSampling()
        id
      }

      def completeBranch(id: Int, pc: Long, len: Int, learn: Boolean = true): Unit = {
        val bc = dut.rob.logic.branchCompletion
        bc.valid #= true
        bc.payload.robId #= id
        bc.payload.mispredict #= false
        bc.payload.nextPc #= (pc + 2L * len)
        bc.payload.isBranch #= learn
        bc.payload.btbPc #= pc
        bc.payload.btbTaken #= true
        bc.payload.btbTarget #= 0x8000
        bc.payload.brType #= 1
        bc.payload.btbLen #= len
        bc.payload.phtValid #= false
        bc.payload.phtIndex #= 0
        cd.waitSampling()
        bc.valid #= false
      }

      def awaitUpdate(pc: Long, len: Int): Unit = {
        var cycles = 0
        while (!dut.rob.logic.btbUpdateFlow.valid.toBoolean) {
          assert(cycles < 12, s"BTB update missing for pc=0x${pc.toHexString}")
          cycles += 1
          cd.waitSampling()
        }
        val upd = dut.rob.logic.btbUpdateFlow.payload
        assert(upd.pc.toLong == pc, s"training PC association lost across retire/wrap")
        assert(upd.len.toInt == len, s"training len=${upd.len.toInt}, expected $len")
        val hist = dut.rob.logic.debugBranchRetire
        assert(hist.valid.toBoolean, "retired branch must emit committed debug history")
        assert((hist.payload.pc.toLong & 0xffffffffL) == pc)
        assert((hist.payload.nextPc.toLong & 0xffffffffL) == 0x8000L)
        assert(hist.payload.taken.toBoolean)
        assert(!hist.payload.mispredicted.toBoolean)
        assert(hist.payload.branchType.toInt == 1)
        cd.waitSampling()
      }

      // More than one complete ROB turn makes the 63->0 association observable.
      val lengths = Seq(1, 2, 3, 5)
      var updates = 0
      for (n <- 0 until 68) {
        val pc = 0x4000L + n * 16L
        val len = lengths(n & 3)
        val id = allocBranch(pc)
        assert(id == (n & 63), s"expected ROB wrap id=${n & 63}, got $id")
        completeBranch(id, pc, len)
        awaitUpdate(pc, len)
        updates += 1
      }
      assert(updates == 68)

      // A flushed branch never reaches retire-time training.
      val flushedPc = 0x9000L
      allocBranch(flushedPc)
      dut.rob.logic.flush.valid #= true
      cd.waitSampling()
      dut.rob.logic.flush.valid #= false
      for (_ <- 0 until 4) {
        assert(!dut.rob.logic.btbUpdateFlow.valid.toBoolean,
          "flushed branch must not produce a BTB update")
        cd.waitSampling()
      }

      // A completing control-family uop explicitly classified non-learnable also
      // retires without producing a BTB update.
      val nonLearnPc = 0x9100L
      val nonLearnId = allocBranch(nonLearnPc)
      completeBranch(nonLearnId, nonLearnPc, len = 2, learn = false)
      for (_ <- 0 until 8) {
        assert(!dut.rob.logic.btbUpdateFlow.valid.toBoolean,
          "non-learnable branch must not produce a BTB update")
        cd.waitSampling()
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("CacheControlService.dcacheEnabled mirrors ss.cacr(31) combinationally") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // CACR.DE clear -> dcacheEnabled low.
      dut.rob.logic.exc.ss.cacr #= 0
      sleep(1)
      assert(!dut.cacheCtrl.logic.dcacheEnabledOut.toBoolean, "dcacheEnabled must be low when CACR.DE=0")

      // Set CACR bit 31 (DE) -> dcacheEnabled tracks it combinationally, same cycle.
      dut.rob.logic.exc.ss.cacr #= (1L << 31)
      sleep(1)
      assert(dut.cacheCtrl.logic.dcacheEnabledOut.toBoolean, "dcacheEnabled must be high when CACR.DE=1")

      // Other bits set but DE=0 -> still low (isolates bit 31, not "any bit set").
      dut.rob.logic.exc.ss.cacr #= 0x7fffffffL
      sleep(1)
      assert(!dut.cacheCtrl.logic.dcacheEnabledOut.toBoolean, "dcacheEnabled must ignore non-DE CACR bits")
    }
  }

  // ── End-to-end sustained free-loop DUT ────────────────────────────────────────
  class E2EDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val dsrc = new m68k040.rename.DecodeUopSourcePlugin
    val ren  = new RenameStage
    val drv  = new RobAllocDriverPlugin
    val rob  = new RobPlugin
    val tsink = new CommitTraceSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), dsrc, ren, drv, rob, tsink)) }
  }

  test("sustained free-loop: real rename->rob keeps flowing past 48 allocations", VerilatorTest) {
    M68kSim().withVerilator.compile(new E2EDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.dsrc.logic.src.valid #= false
      dut.dsrc.logic.s1v #= false
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      dut.rob.logic.flush.valid #= false
      cd.waitSampling()
      // wait for rename init.
      var n = 0
      while (!dut.dsrc.logic.src.ready.toBoolean && n < 300) { cd.waitSampling(); n += 1 }
      assert(dut.dsrc.logic.src.ready.toBoolean, "rename init never completed")

      // markComplete fork: a few cycles after dispatch, mark each allocated robId.
      // The ROB allocates sequentially; we track the next robId to complete and
      // chase the tail with a small lag so entries become complete and retire,
      // freeing old pdsts back to the freelist (closing the loop).
      val robIdW = 6
      val mask = (1 << robIdW) - 1
      // Drive a long MOVEQ-like stream: each uop writes a rotating D reg (single-wide
      // dispatch keeps the markComplete chase simple and deterministic).
      def pokeMoveq(s: Int, dst: Int): Unit = {
        val u = dut.dsrc.logic.src.payload(s)
        u.valid #= true
        u.pc #= 0
        u.nextPc #= 2
        u.faultUsesNextPc #= false
        u.op #= DecOp.MOVE
        u.cluster #= Cluster.INT
        u.size #= Size.LONG
        u.srcAReg #= 0; u.srcAValid #= false
        u.srcBReg #= 0; u.srcBValid #= false
        u.dstReg #= dst; u.dstValid #= true
        u.useImm #= true; u.imm #= 1
        u.readsNzvc #= false; u.readsX #= false
        u.writesNzvc #= false; u.writesX #= false
        u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
        u.unimplemented #= false
        u.faulted #= false; u.faultVector #= 0; u.isRte #= false
        u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
      }

      // Completion chaser fork: keep a queue of dispatched robIds and complete the
      // oldest a few cycles after dispatch.
      val dispatched = scala.collection.mutable.Queue[Int]()
      var nextTail = 0
      var totalFires = 0

      // Count retires fork.
      val firesFork = fork {
        while (true) {
          cd.waitSampling()
          if (dut.tsink.logic.fireOut(0).toBoolean) totalFires += 1
          if (dut.tsink.logic.fireOut(1).toBoolean) totalFires += 1
        }
      }

      // Drive single-wide MOVEQ stream; one alloc/cycle when ready.
      var allocs = 0
      val totalUops = 120
      var cyc = 0
      // pipeline of robIds awaiting completion, with lag.
      val pending = scala.collection.mutable.Queue[(Int, Int)]() // (robId, cycleDue)
      val completeLag = 4

      while (allocs < totalUops && cyc < 4000) {
        // Set up completion for any due entries (use completion port 0 -> at most 1/cycle).
        // Default no complete.
        dut.rob.logic.completion(0).valid #= false
        if (pending.nonEmpty && pending.front._2 <= cyc) {
          val (rid, _) = pending.dequeue()
          dut.rob.logic.completion(0).valid #= true
          dut.rob.logic.completion(0).payload #= rid
        }

        // Drive a uop on slot0 only.
        pokeMoveq(0, allocs % 8)
        dut.dsrc.logic.src.payload(1).valid #= false
        dut.dsrc.logic.src.valid #= true
        dut.dsrc.logic.s1v #= false

        cd.waitSampling()
        // Did it fire (rename consumed)?
        if (dut.dsrc.logic.src.ready.toBoolean) {
          pending.enqueue((nextTail, cyc + completeLag))
          nextTail = (nextTail + 1) & mask
          allocs += 1
        }
        cyc += 1
      }
      dut.dsrc.logic.src.valid #= false

      // Drain: keep completing remaining pending entries.
      var drain = 0
      while (pending.nonEmpty && drain < 2000) {
        dut.rob.logic.completion(0).valid #= false
        if (pending.front._2 <= cyc) {
          val (rid, _) = pending.dequeue()
          dut.rob.logic.completion(0).valid #= true
          dut.rob.logic.completion(0).payload #= rid
        }
        cd.waitSampling()
        cyc += 1; drain += 1
      }
      dut.rob.logic.completion(0).valid #= false
      // let final retires drain
      for (_ <- 0 until 20) cd.waitSampling()

      assert(allocs >= totalUops, s"pipeline stalled: only $allocs of $totalUops allocated (freelist drained -> loop not closed)")
      assert(totalFires > 48, s"only $totalFires retires observed; loop not sustained past 48 (freelist would have drained)")
    }
  }

  // ── Task P2.3: 5th completion port / sqFaultCompletion / preciseDrainBusyIn ────
  // Directed-only: no driver exists yet (Task P2.5 wires these to the real SQ), so
  // these tests poke the new ports/gate directly, mirroring how lsFaultCompletion /
  // completion(0..3) / normalIrqGate-traceNormalGate are exercised elsewhere in this
  // file / RobInterruptSpec.

  test("completion(4) (5th, SQ precise-drain port) marks completes and retires the head") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)
      dut.rob.logic.completion(4).valid #= false

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x900, dstArch = 4, pdst = 22, pdstValid = true, pdstOld = 4)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()

      // Complete robId 0 via ONLY the 5th completion port (ports 0-3 stay idle) --
      // this is the SQ precise-path drain's own port, sibling-driven at Task P2.5.
      dut.rob.logic.completion(4).valid #= true
      dut.rob.logic.completion(4).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(4).valid #= false

      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      assert(dut.tsink.logic.fireOut(0).toBoolean, "head retired via completion(4)")
      assert(dut.tsink.logic.traceOut(0).archRegId.toInt == 4, "correct archRegId")
    }
  }

  test("sqFaultCompletion flags entry vector 2 + records fault attrs (paired w/ completion(4))") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)
      dut.rob.logic.completion(4).valid #= false
      dut.rob.logic.sqFaultCompletion.valid #= false

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x1000)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()

      // Complete + fault robId 0 via the SQ-drain pair: completion(4) (completes) and
      // sqFaultCompletion (faults) -- exactly the shape Task P2.5 wires from the SQ
      // (an older, already-drained precise store's bus error).
      dut.rob.logic.completion(4).valid #= true
      dut.rob.logic.completion(4).payload #= 0
      dut.rob.logic.sqFaultCompletion.valid #= true
      dut.rob.logic.sqFaultCompletion.payload.robId #= 0
      dut.rob.logic.sqFaultCompletion.payload.faultAddr #= BigInt(0x4000L)
      dut.rob.logic.sqFaultCompletion.payload.write #= false
      dut.rob.logic.sqFaultCompletion.payload.sizeBits #= 1
      dut.rob.logic.sqFaultCompletion.payload.supervisor #= false
      cd.waitSampling()
      dut.rob.logic.completion(4).valid #= false
      dut.rob.logic.sqFaultCompletion.valid #= false

      var seen = false; var n = 0
      while (!seen && n < 50) {
        if (dut.rob.logic.exceptionPending.toBoolean) {
          seen = true
          assert(dut.rob.logic.exceptionVector.toInt == 2, s"vector=${dut.rob.logic.exceptionVector.toInt}")
          assert(dut.rob.logic.exceptionPc.toLong == 0x1000L, f"excPc=0x${dut.rob.logic.exceptionPc.toLong}%x")
          assert(dut.rob.logic.exceptionFaultAddr.toLong == 0x4000L,
            f"faultAddr=0x${dut.rob.logic.exceptionFaultAddr.toLong}%x")
          assert(!dut.rob.logic.exceptionFaultWr.toBoolean, "write attr false")
          assert(dut.rob.logic.exceptionFaultSize.toInt == 1, "size attr")
          assert(!dut.rob.logic.exceptionFaultSup.toBoolean, "supervisor attr false")
          assert(!dut.csink.logic.commitValidOut(0).toBoolean, "faulted entry must NOT commit normally")
        }
        n += 1; cd.waitSampling()
      }
      assert(seen, "exceptionPending never pulsed for the sqFaultCompletion-faulted entry")
    }
  }

  // ── SAME-CYCLE dual fault: lsFaultCompletion (#1) and sqFaultCompletion (#2)
  // firing the SAME cycle for DIFFERENT robIds. This is the exact case the two
  // ports were deliberately kept separate for (see RobPlugin's port-declaration
  // comment: "the LS EU can fault a YOUNGER access the SAME cycle the SQ faults an
  // OLDER, already-drained precise store"), and it had no directed coverage.
  //
  // It is also the precise failure mode of any restructuring of the two write
  // blocks' per-entry write-select (FMax "LS/ROB Lever C", spec
  // docs/superpowers/specs/2026-08-08-fmax-lsrob-leverc-writeenable-flatten-design.md):
  // a broken select-vector would collapse the two same-cycle writes into one, or
  // cross-alias one port's payload onto the other port's entry.
  //
  // Both directions are run (ls->younger/sq->older AND sq->younger/ls->older) and
  // ALL FIVE attribute fields differ between the two ports, so an alias in either
  // direction is caught. Only the ROB HEAD's fault state is architecturally
  // observable (a faulted head does not retire, RobPlugin's `retire0`), so each
  // direction observes the entry whose fault it targets at the head.
  private def sameCycleDualFaultCheck(lsRobId: Int, sqRobId: Int): Unit = {
    // Distinct in EVERY field so a cross-port alias cannot hide.
    val lsAddr = 0x2000L; val lsWr = true;  val lsSize = 2; val lsSup = true
    val sqAddr = 0x4000L; val sqWr = false; val sqSize = 1; val sqSup = false
    // The observed entry is robId 1 -- whichever port targets it.
    val obs = 1
    val (eAddr, eWr, eSize, eSup) =
      if (lsRobId == obs) (lsAddr, lsWr, lsSize, lsSup) else (sqAddr, sqWr, sqSize, sqSup)

    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)
      for (k <- 0 until 5) dut.rob.logic.completion(k).valid #= false
      dut.rob.logic.lsFaultCompletion.valid #= false
      dut.rob.logic.sqFaultCompletion.valid #= false

      // Alloc robId 0,1 (2-wide) then robId 2.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x1000, dstArch = 3, pdst = 20, pdstValid = true, pdstOld = 3)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x1100)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false; dut.rsrc.logic.u1v #= false
      cd.waitSampling()
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x1200)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()

      // THE cycle under test: entries 1 and 2 complete, and BOTH fault ports fire,
      // for two DIFFERENT robIds, simultaneously.
      dut.rob.logic.completion(0).valid #= true; dut.rob.logic.completion(0).payload #= 1
      dut.rob.logic.completion(4).valid #= true; dut.rob.logic.completion(4).payload #= 2
      dut.rob.logic.lsFaultCompletion.valid #= true
      dut.rob.logic.lsFaultCompletion.payload.robId      #= lsRobId
      dut.rob.logic.lsFaultCompletion.payload.faultAddr  #= BigInt(lsAddr)
      dut.rob.logic.lsFaultCompletion.payload.write      #= lsWr
      dut.rob.logic.lsFaultCompletion.payload.sizeBits   #= lsSize
      dut.rob.logic.lsFaultCompletion.payload.supervisor #= lsSup
      dut.rob.logic.lsFaultCompletion.payload.atc        #= false
      dut.rob.logic.sqFaultCompletion.valid #= true
      dut.rob.logic.sqFaultCompletion.payload.robId      #= sqRobId
      dut.rob.logic.sqFaultCompletion.payload.faultAddr  #= BigInt(sqAddr)
      dut.rob.logic.sqFaultCompletion.payload.write      #= sqWr
      dut.rob.logic.sqFaultCompletion.payload.sizeBits   #= sqSize
      dut.rob.logic.sqFaultCompletion.payload.supervisor #= sqSup
      dut.rob.logic.sqFaultCompletion.payload.atc        #= true
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(4).valid #= false
      dut.rob.logic.lsFaultCompletion.valid #= false
      dut.rob.logic.sqFaultCompletion.valid #= false
      cd.waitSampling()

      // Now let the (unfaulted) head robId 0 complete + retire, exposing entry 1.
      dut.rob.logic.completion(0).valid #= true; dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      var seen = false; var n = 0
      while (!seen && n < 60) {
        if (dut.rob.logic.exceptionPending.toBoolean) {
          seen = true
          assert(dut.rob.logic.exceptionVector.toInt == 2,
            s"vector=${dut.rob.logic.exceptionVector.toInt}")
          assert(dut.rob.logic.exceptionPc.toLong == 0x1100L,
            f"wrong ENTRY faulted: excPc=0x${dut.rob.logic.exceptionPc.toLong}%x (want 0x1100)")
          assert(dut.rob.logic.exceptionFaultAddr.toLong == eAddr,
            f"faultAddr=0x${dut.rob.logic.exceptionFaultAddr.toLong}%x want 0x$eAddr%x " +
            "(the OTHER port's payload landed here -> same-cycle writes aliased)")
          assert(dut.rob.logic.exceptionFaultWr.toBoolean == eWr, "write attr aliased")
          assert(dut.rob.logic.exceptionFaultSize.toInt == eSize, "size attr aliased")
          assert(dut.rob.logic.exceptionFaultSup.toBoolean == eSup, "supervisor attr aliased")
          assert(!dut.csink.logic.commitValidOut(0).toBoolean, "faulted entry must NOT commit normally")
        }
        n += 1; cd.waitSampling()
      }
      assert(seen, "exceptionPending never pulsed -> a same-cycle fault write was LOST")
    }
  }

  test("same-cycle lsFaultCompletion + sqFaultCompletion, different robIds (ls->younger)") {
    sameCycleDualFaultCheck(lsRobId = 1, sqRobId = 2)
  }

  test("same-cycle lsFaultCompletion + sqFaultCompletion, different robIds (sq->younger)") {
    sameCycleDualFaultCheck(lsRobId = 2, sqRobId = 1)
  }

  // ── preciseDrainBusyIn gates normalIrqGate/traceNormalGate (the ROB-side half of
  // the interrupt/trace preemption interlock; Task P2.4 builds the SQ-side half that
  // will drive it). Dut mirrors RobInterruptSpec's (adds InterruptControlPlugin so
  // interruptPending is reachable; a plain SimpleDut has no iplIn source).
  class GateDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val intCtrl = new InterruptControlPlugin
    val rsrc = new RenameUopSourcePlugin
    val drv  = new RobAllocDriverPlugin
    val rob  = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    val qsink = new FrontendQuiesceSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), intCtrl, rsrc, drv, rob, csink, tsink, qsink)) }
  }

  /** Mirrors RobInterruptSpec's pokeRu -- explicitly sets every field that
    * firstStore/faulted/isRte/interruptPending/tracePendingFire read, so those paths
    * are deterministic (unset RenamedUop fields randomize per sim seed). */
  def pokeGateRu(u: RenamedUop, pc: Long = 0, firstOfInstr: Boolean = true): Unit = {
    u.valid #= true
    u.pc #= pc
    u.nextPc #= pc + 2
    u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
    u.unimplemented #= false
    u.dstArch #= 0
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= 0; u.pdstValid #= false; u.pdstOld #= 0
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.sysOp #= false; u.sysKind #= m68k040.decode.SysKind.NONE; u.sysReadDir #= false
    u.needsSupervisor #= false
    u.isCondTrap #= false
    u.sswInstr #= false
    u.faultAddr #= 0
    u.firstOfInstr #= firstOfInstr
    u.lastOfInstr #= true    // single-µop macros; keeps the gate DUT's pokes deterministic
    u.debugBreakValid #= false
    u.debugBreakSlot #= 0
  }

  def initGate(dut: GateDut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.flush.valid #= false
    for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
    dut.rob.logic.branchCompletion.valid #= false
    dut.rob.logic.preciseDrainBusyIn #= false
    dut.rob.logic.inhibitedLoadBusyIn #= false
    dut.intCtrl.logic.iplIn #= 0
    dut.intCtrl.logic.iackAvec #= false
    dut.intCtrl.logic.iackVector #= 0
    pokeGateRu(dut.rsrc.logic.src.payload(0))
    pokeGateRu(dut.rsrc.logic.src.payload(1))
    cd.waitSampling(3)
  }

  def allocGateOne(dut: GateDut, cd: ClockDomain, pc: Long): Unit = {
    pokeGateRu(dut.rsrc.logic.src.payload(0), pc = pc)
    dut.rsrc.logic.src.valid #= true
    dut.rsrc.logic.u1v #= false
    cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
    dut.rsrc.logic.src.valid #= false
    cd.waitSampling()
  }

  def setGateMask(dut: GateDut, cd: ClockDomain, mask: Int): Unit = {
    dut.rob.logic.exc.ss.srSys #= (0x20 | (mask & 0x7))
    cd.waitSampling()
  }

  test("preciseDrainBusyIn blocks interruptPending even when ipl>mask at a first-uop head") {
    M68kSim().compile(new GateDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initGate(dut, cd)
      setGateMask(dut, cd, 2)
      allocGateOne(dut, cd, pc = 0xB00)
      dut.rob.logic.preciseDrainBusyIn #= true
      dut.intCtrl.logic.iplIn #= 5   // > mask -- would normally recognize immediately
      dut.intCtrl.logic.iackAvec #= true
      for (_ <- 0 until 10) {
        assert(!dut.rob.logic.interruptPending.toBoolean, "preciseDrainBusyIn must block interruptPending")
        cd.waitSampling()
      }
      // Sanity: dropping the gate lets the SAME still-pending condition fire.
      dut.rob.logic.preciseDrainBusyIn #= false
      var seen = false; var n = 0
      while (!seen && n < 10) {
        if (dut.rob.logic.interruptPending.toBoolean) seen = true
        n += 1; cd.waitSampling()
      }
      assert(seen, "interruptPending must fire once preciseDrainBusyIn drops (sanity)")
    }
  }

  test("preciseDrainBusyIn blocks tracePendingFire even with an armed T1 trace at a first-uop head") {
    M68kSim().compile(new GateDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initGate(dut, cd)
      // S=1, T1=1 (srSys bit 7) -- every retiring instruction arms a pending trace.
      dut.rob.logic.exc.ss.srSys #= 0xA0
      cd.waitSampling()
      dut.rob.logic.preciseDrainBusyIn #= true

      // Alloc + retire ONE instruction (via completion port 0) to ARM tracePendingReg
      // (retire0 && h0TraceArmed, h0TraceArmed = t1Armed here).
      allocGateOne(dut, cd, pc = 0xC00)
      dut.rob.logic.completion(0).valid #= true; dut.rob.logic.completion(0).payload #= 0
      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      dut.rob.logic.completion(0).valid #= false
      cd.waitSampling()   // let tracePendingReg's write (registered) land
      assert(dut.rob.logic.tracePendingReg.toBoolean, "trace must be armed after the T1-active retire")

      // A second instruction is now the head, eligible (firstStore, non-faulted/RTE/
      // sysOp) -- traceNormalGate does NOT wait on completes(h0), so WITHOUT the gate
      // this fires immediately. With preciseDrainBusyIn held, it must never fire.
      allocGateOne(dut, cd, pc = 0xC02)
      for (_ <- 0 until 10) {
        assert(!dut.rob.logic.tracePendingFire.toBoolean, "preciseDrainBusyIn must block tracePendingFire")
        cd.waitSampling()
      }
      assert(dut.rob.logic.tracePendingReg.toBoolean, "the armed trace must still be pending (never consumed)")

      // Sanity: dropping the gate lets the still-armed trace fire.
      dut.rob.logic.preciseDrainBusyIn #= false
      var seen = false; var n = 0
      while (!seen && n < 10) {
        if (dut.rob.logic.tracePendingFire.toBoolean) seen = true
        n += 1; cd.waitSampling()
      }
      assert(seen, "tracePendingFire must fire once preciseDrainBusyIn drops (sanity)")
    }
  }

  // ── `inhibitedLoadBusyIn` gates normalIrqGate/traceNormalGate (the ROB-side half
  // of the inhibited-LOAD preemption interlock -- LsEuPlugin's `inhibitedLoadBusySig`
  // drives this in the real core). Exact mirror of the `preciseDrainBusyIn` pair
  // immediately above: a cache-inhibited LOAD's already-launched device read is a
  // real, possibly clear-on-read/pop side effect, so an interrupt/trace must not be
  // newly recognized at the head while it is still in flight -- otherwise the
  // subsequent flush silently discards the response and the SAME instruction
  // re-issues a SECOND real device read after RTE (see LsEuPlugin.scala's
  // `inhibitedLoadBusySig` doc comment for the full mechanism).
  test("inhibitedLoadBusyIn blocks interruptPending even when ipl>mask at a first-uop head") {
    M68kSim().compile(new GateDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initGate(dut, cd)
      setGateMask(dut, cd, 2)
      allocGateOne(dut, cd, pc = 0xB10)
      dut.rob.logic.inhibitedLoadBusyIn #= true
      dut.intCtrl.logic.iplIn #= 5   // > mask -- would normally recognize immediately
      dut.intCtrl.logic.iackAvec #= true
      for (_ <- 0 until 10) {
        assert(!dut.rob.logic.interruptPending.toBoolean, "inhibitedLoadBusyIn must block interruptPending")
        cd.waitSampling()
      }
      // Sanity: dropping the gate lets the SAME still-pending condition fire.
      dut.rob.logic.inhibitedLoadBusyIn #= false
      var seen = false; var n = 0
      while (!seen && n < 10) {
        if (dut.rob.logic.interruptPending.toBoolean) seen = true
        n += 1; cd.waitSampling()
      }
      assert(seen, "interruptPending must fire once inhibitedLoadBusyIn drops (sanity)")
    }
  }

  test("inhibitedLoadBusyIn blocks tracePendingFire even with an armed T1 trace at a first-uop head") {
    M68kSim().compile(new GateDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initGate(dut, cd)
      // S=1, T1=1 (srSys bit 7) -- every retiring instruction arms a pending trace.
      dut.rob.logic.exc.ss.srSys #= 0xA0
      cd.waitSampling()
      dut.rob.logic.inhibitedLoadBusyIn #= true

      // Alloc + retire ONE instruction (via completion port 0) to ARM tracePendingReg
      // (retire0 && h0TraceArmed, h0TraceArmed = t1Armed here).
      allocGateOne(dut, cd, pc = 0xC10)
      dut.rob.logic.completion(0).valid #= true; dut.rob.logic.completion(0).payload #= 0
      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      dut.rob.logic.completion(0).valid #= false
      cd.waitSampling()   // let tracePendingReg's write (registered) land
      assert(dut.rob.logic.tracePendingReg.toBoolean, "trace must be armed after the T1-active retire")

      // A second instruction is now the head, eligible (firstStore, non-faulted/RTE/
      // sysOp) -- traceNormalGate does NOT wait on completes(h0), so WITHOUT the gate
      // this fires immediately. With inhibitedLoadBusyIn held, it must never fire.
      allocGateOne(dut, cd, pc = 0xC12)
      for (_ <- 0 until 10) {
        assert(!dut.rob.logic.tracePendingFire.toBoolean, "inhibitedLoadBusyIn must block tracePendingFire")
        cd.waitSampling()
      }
      assert(dut.rob.logic.tracePendingReg.toBoolean, "the armed trace must still be pending (never consumed)")

      // Sanity: dropping the gate lets the still-armed trace fire.
      dut.rob.logic.inhibitedLoadBusyIn #= false
      var seen = false; var n = 0
      while (!seen && n < 10) {
        if (dut.rob.logic.tracePendingFire.toBoolean) seen = true
        n += 1; cd.waitSampling()
      }
      assert(seen, "tracePendingFire must fire once inhibitedLoadBusyIn drops (sanity)")
    }
  }

  // ── Task P2.5 post-review fix: `h0PreciseCompletedSticky` must be LEVEL-sensitive
  // (stays asserted every cycle until retire0 actually fires), not a one-shot pulse
  // that self-clears exactly one cycle after completion(4) regardless of whether h0's
  // OWN retire has happened yet. The old one-shot `RegNext` would have already
  // self-cleared by the time a STACKED, unrelated stall finally lets h0 retire,
  // silently reopening the dual-retire race the mechanism exists to prevent.
  //
  // Stall choice: `stopped` (one of retire0's own gating terms, `!stopped`) rather
  // than interruptPending/tracePendingFire -- those two are ALSO `excEntryTrigger`
  // sources (see the entryTrigger comment above `excEntryTrigger`'s definition):
  // recognizing them doesn't just delay retire0, it fires the exception-entry FSM
  // and self-SQUASHES the ROB (pointer-reset flush, count->0 immediately) instead of
  // ever letting h0 retire normally via fireOut -- confirmed by direct instrumentation
  // during this test's development (excActive flips true, count drops to 0 via a
  // flush, fireOut(0) never fires). `stopped` has no such side effect: it only gates
  // retire0's own `!stopped` term and ORs into interruptPending's `normalIrqGate`
  // (neutralized here since iplIn/iackAvec are never touched, so iplActive stays
  // False and interruptPending never actually fires/flushes). Directly sim-poking
  // `stopped` (a plain RegInit(False) with only two conditional HDL drivers -- a
  // retiring STOP setting it, `interruptPending` clearing it, both inert/False here)
  // gives a clean, side-effect-free artificial stall.
  //
  // This test proves the sticky fix: completion(4) fires for h0 WHILE `stopped` is
  // ALSO held for several extra cycles past the single cycle the old one-shot would
  // have covered, and retire1 (h1, completes-ready throughout) must stay blocked for
  // EVERY cycle of that stall -- not just the first -- and only retire the cycle
  // AFTER h0 itself actually retires (forced single-wide retire, mirrors
  // h0TraceArmed exactly).
  test("h0PreciseCompletedSticky stays asserted across a stacked retire0 stall (task-P2.5 post-review fix)") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // Allocate h0 (robId 0) and h1 (robId 1), 2-wide.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0xE00, dstArch = 3, pdst = 20, pdstValid = true, pdstOld = 3)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0xE02, dstArch = 5, pdst = 21, pdstValid = true, pdstOld = 5)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 2, s"2 in flight, got ${dut.rob.logic.count.toInt}")

      // h1 becomes completes-ready via the ordinary completion port 0 -- `completes`
      // is a sticky per-entry register, stays set until h1 itself retires, so h1 is
      // completes-ready for the ENTIRE remainder of this test.
      markComplete(dut, 1)
      cd.waitSampling()
      clearComplete(dut)

      // Raise the artificial, side-effect-free stall BEFORE firing completion(4), so
      // the completion(4) pulse and h0's actual retire are separated by several
      // cycles -- the stacked-stall scenario the OLD one-shot RegNext missed.
      dut.rob.logic.stopped #= true
      cd.waitSampling()
      assert(!dut.rob.logic.retire0.toBoolean, "retire0 must be blocked by the stall")

      // Fire completion(4) for h0 (the SQ precise-drain port) WHILE the stall is
      // active -- h0PreciseCompletedSticky sets this cycle, but retire0 stays blocked.
      dut.rob.logic.completion(4).valid #= true
      dut.rob.logic.completion(4).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(4).valid #= false

      // Hold the stall SEVERAL MORE cycles past the single cycle the OLD one-shot
      // RegNext would have covered. retire1/fireOut(1) must stay blocked EVERY cycle
      // of this window (h1 is completes-ready the whole time -- the ONLY thing
      // stopping it is retire0 itself being blocked, but this also proves the sticky
      // bit hasn't spuriously done anything wrong yet).
      for (_ <- 0 until 5) {
        assert(!dut.tsink.logic.fireOut(1).toBoolean, "retire1 must stay blocked through the whole stalled window")
        assert(!dut.rob.logic.retire0.toBoolean, "retire0 must stay blocked (stall still held)")
        cd.waitSampling()
      }

      // Drop the stall -- h0 finally retires, several cycles after the completion(4)
      // pulse (well past the OLD one-shot's 1-cycle window). With the sticky fix,
      // h0PreciseCompletedSticky is STILL asserted this cycle (it only clears when
      // retire0 itself fires -- same cycle, so its REGISTERED clear takes effect NEXT
      // cycle), so retire1 must be forced blocked on h0's own retire cycle too
      // (single-wide retire) -- the exact race the OLD one-shot would have reopened
      // (it would have self-cleared cycles ago, letting h1 dual-retire with h0).
      // CHECK-FIRST (fireOut is a self-clearing one-cycle pulse that can fire as
      // early as the very next cycle -- waitSamplingWhere would consume/miss it).
      dut.rob.logic.stopped #= false
      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean, max = 50)
      assert(dut.tsink.logic.fireOut(0).toBoolean, "h0 finally retires once the stall clears")
      assert(!dut.tsink.logic.fireOut(1).toBoolean,
        "retire1 must STILL be blocked on h0's own retire cycle (forced single-wide retire -- " +
        "the OLD one-shot bug would have already self-cleared here, letting h1 dual-retire with h0)")

      // h1 (now the sole head) retires the very next cycle, single-wide, once the
      // sticky bit has actually cleared.
      cd.waitSampling()
      assert(dut.tsink.logic.fireOut(0).toBoolean, "h1 (now head) retires the cycle after h0")
      assert(dut.tsink.logic.traceOut(0).archRegId.toInt == 5, "the retiring entry must be h1 (archRegId=5)")
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 0, "ROB drained")
    }
  }

  // ── P2.7 review fix: `h0PreciseCompletedSticky` must ALSO clear on `flushing`, not
  // only on `retire0`. Every head-consuming path OTHER than retire0 -- faultRetire (a
  // precise store whose drain took a real bus error), rteRetire, sysRetire, a
  // branch-mispredict redirect, a test flush -- ends in `flushing`, which does
  // `count := 0; tail := head` and hands that SAME ROB index straight to the next,
  // brand-new instruction. Clearing only on retire0 carried the guard across that
  // boundary and force-serialized an unrelated successor (silent IPC loss; a chain of
  // non-retire0 heads could hold it for many instructions).
  //
  // The test drives the mechanism end-to-end and DISCRIMINATES: fire completion(4) for
  // h0 so the sticky arms, flush before h0 ever retires (so the OLD code has no clear
  // event at all), then re-fill the ROB and require a genuine 2-WIDE retire. With the
  // bug the sticky is still set when the new head becomes ready, so retire1 is blocked
  // and the pair retires single-wide over two cycles -- reverting the `|| flushing`
  // term makes the `fireOut(1)` assertion below fail (confirmed by revert-and-rerun).
  test("h0PreciseCompletedSticky clears on a flush, not only on retire0 (P2.7 review fix)") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // Allocate h0 (robId 0) + h1 (robId 1).
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0xF00, dstArch = 3, pdst = 20, pdstValid = true, pdstOld = 3)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0xF02, dstArch = 5, pdst = 21, pdstValid = true, pdstOld = 5)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 2, s"2 in flight, got ${dut.rob.logic.count.toInt}")

      // Arm the sticky: completion(4) (the SQ precise-drain port) for h0, while h0 is
      // held back from retiring by `stopped` (the same side-effect-free stall the
      // sibling test above uses -- see its comment for why interruptPending/trace are
      // unusable here). h0 therefore NEVER retires: with the pre-fix code the sticky
      // has no clear event whatsoever.
      dut.rob.logic.stopped #= true
      cd.waitSampling()
      dut.rob.logic.completion(4).valid #= true
      dut.rob.logic.completion(4).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(4).valid #= false
      cd.waitSampling()
      assert(dut.rob.logic.h0PreciseCompletedSticky.toBoolean,
        "sticky must be armed by completion(4) for h0 (test precondition)")
      assert(!dut.rob.logic.retire0.toBoolean, "h0 must NOT have retired (stall held)")

      // Flush: squashes h0/h1 (count -> 0, tail -> head) and hands robId 0 straight
      // back to the next allocation. THIS is the event the fix adds as a clear.
      dut.rob.logic.flush.valid #= true
      cd.waitSampling()
      dut.rob.logic.flush.valid #= false
      dut.rob.logic.stopped #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 0, "ROB squashed by the flush")
      assert(!dut.rob.logic.h0PreciseCompletedSticky.toBoolean,
        "P2.7 FIX: the sticky must be CLEARED by the flush -- the entry it was armed " +
        "for is gone and its ROB index has been handed to a brand-new instruction")

      // Re-fill with two fresh, ordinary (non-store) uops and let both complete. They
      // must retire 2-WIDE: nothing about them should be serialized by a guard armed
      // for a long-since-squashed predecessor.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0xF80, dstArch = 4, pdst = 22, pdstValid = true, pdstOld = 4)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0xF82, dstArch = 6, pdst = 23, pdstValid = true, pdstOld = 6)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 2, s"2 fresh uops in flight, got ${dut.rob.logic.count.toInt}")

      // Complete the YOUNGER one first (head stays incomplete so nothing retires yet),
      // then the head -- so both are completes-ready on the same cycle (same recipe as
      // the "alloc + 2-wide retire" test at the top of this file).
      markComplete(dut, 1)
      cd.waitSampling()
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      clearComplete(dut)

      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean, max = 50)
      assert(dut.tsink.logic.fireOut(1).toBoolean,
        "P2.7 FIX: the two fresh uops must retire 2-WIDE. With the pre-fix code " +
        "h0PreciseCompletedSticky is STILL set here (armed before the flush, cleared " +
        "only by a retire0 that never happened), which force-serializes retire1.")
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 0, "ROB drained by the 2-wide retire")
    }
  }

  // ── Task P4.5: sticky, non-interrupt-wakeable CORE HALT ─────────────────────────
  // Unlike `stopped` (interrupt-wakeable, self-clearing STOP quiesce), `coreHalted`
  // is a one-way latch: once `coreHaltedIn` pulses, retire freezes permanently (only
  // reset recovers it) and an otherwise-eligible interrupt must NOT be recognized --
  // `headReady`'s `!coreHalted` term and `interruptPending`'s `!coreHalted` term.
  // GateDut (has InterruptControlPlugin, so `interruptPending`/`iplIn` are reachable)
  // is the SAME Dut the "preciseDrainBusyIn blocks interruptPending..." test above
  // uses; that test's sibling ("preciseDrainBusyIn blocks tracePendingFire...")
  // independently proves allocGateOne + completion(0) genuinely retires via
  // fireOut(0) in this exact Dut shape with no gate active -- so this test doesn't
  // need its own un-halted baseline phase to rule out a vacuously-never-ready head.
  test("coreHaltedIn freezes retire and is NOT cleared by a subsequent interruptPending") {
    M68kSim().compile(new GateDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initGate(dut, cd)
      setGateMask(dut, cd, 0)   // mask=0 -- any ipl>0 is normally interrupt-eligible

      // A 1-cycle coreHaltedIn pulse must latch coreHalted permanently.
      assert(!dut.qsink.logic.activeOut.toBoolean && !dut.qsink.logic.nextOut.toBoolean,
        "frontend-quiesce service was not idle before fatal halt")
      dut.rob.logic.coreHaltedIn #= true
      sleep(1)
      assert(!dut.qsink.logic.activeOut.toBoolean && dut.qsink.logic.nextOut.toBoolean,
        "fatal input did not appear in service.next before the latching edge")
      cd.waitSampling()
      dut.rob.logic.coreHaltedIn #= false
      cd.waitSampling()
      assert(dut.rob.logic.coreHalted.toBoolean, "coreHalted must latch after a 1-cycle coreHaltedIn pulse")
      assert(dut.qsink.logic.activeOut.toBoolean && dut.qsink.logic.nextOut.toBoolean,
        "frontend-quiesce service did not become/stay active with sticky fatal halt")

      // Alloc + complete one instruction (robId 0) -- retire-eligible in every OTHER
      // respect (same shape the sibling GateDut tests DO see retire via fireOut(0)).
      allocGateOne(dut, cd, pc = 0xD00)
      dut.rob.logic.completion(0).valid #= true; dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      for (_ <- 0 until 15) {
        assert(!dut.tsink.logic.fireOut(0).toBoolean, "coreHalted must freeze retire (fireOut must never fire)")
        assert(dut.rob.logic.count.toInt > 0, "coreHalted must freeze retire (count must never drop)")
        cd.waitSampling()
      }

      // Raise an interrupt-eligible condition (ipl > mask). Unlike `stopped`,
      // coreHalted must NOT be bypassed/cleared by this -- a halted core recognizes
      // no interrupt at all (design doc decision), and retire must stay frozen.
      dut.intCtrl.logic.iplIn #= 5
      dut.intCtrl.logic.iackAvec #= true
      for (_ <- 0 until 15) {
        assert(!dut.rob.logic.interruptPending.toBoolean,
          "a halted core recognizes no interrupt -- interruptPending must never fire")
        assert(!dut.tsink.logic.fireOut(0).toBoolean, "retire must remain frozen despite the pending interrupt")
        assert(dut.rob.logic.coreHalted.toBoolean, "coreHalted must remain latched (not interrupt-clearable)")
        cd.waitSampling()
      }
    }
  }

  test("frontend-quiesce service predicts STOP interrupt wake on the exact edge") {
    M68kSim().compile(new GateDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initGate(dut, cd)
      setGateMask(dut, cd, 0)

      // Directly seed the architectural STOP state; existing lock-step coverage
      // independently reaches it through a real STOP sysOp. This test isolates the
      // service's current/next truth and the actual interrupt-clear priority.
      dut.rob.logic.stopped #= true
      cd.waitSampling()
      assert(dut.rob.logic.stopped.toBoolean && dut.qsink.logic.activeOut.toBoolean &&
             dut.qsink.logic.nextOut.toBoolean,
        "seeded STOP state was not visible through both service phases")

      dut.intCtrl.logic.iplIn #= 5
      dut.intCtrl.logic.iackAvec #= true
      sleep(1)
      assert(dut.rob.logic.interruptPending.toBoolean,
        "STOP wake setup did not produce a real interruptPending")
      assert(dut.qsink.logic.activeOut.toBoolean && !dut.qsink.logic.nextOut.toBoolean,
        "service.next did not predict interrupt-clear while active remained visible")

      cd.waitSampling()
      sleep(1)
      assert(!dut.rob.logic.stopped.toBoolean && !dut.qsink.logic.activeOut.toBoolean,
        "STOP and frontend-quiesce active did not clear on the predicted edge")
    }
  }

  // ── Task B2: directed round-trip for the 8 LUT-reduction-B1-folded RobPayload
  // fields (isRte/sysOp/sysKind/sysReadDir/sysRc/needsSup/first/pc). Task B1 folded
  // these from standalone Vec.fill(depth)(RegInit(...)) arrays into `payload` (the
  // same Mem already holding predNextPc/archRegId/intNew/nzvcNew/xNew/retireAlone),
  // verified there only INDIRECTLY via downstream consumers (rteRetire/sysRetire/
  // privViolation/...). This test reads p0 DIRECTLY (now simPublic, task B2) with a
  // distinct, non-default value for every one of the 8 fields, independently checked.
  test("LUT-reduction B1: all 8 folded RobPayload fields round-trip through the Mem") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      val u = dut.rsrc.logic.src.payload(0)
      pokeRu(u, pc = 0x00401234L, dstArch = 9, pdstValid = false)
      // Override the 8 LUT-reduction-B1-folded fields with distinct, non-default
      // values -- pokeRu itself hardcodes all of these to their "off" defaults.
      u.isRte #= true
      u.sysOp #= true
      u.sysKind #= m68k040.decode.SysKind.MOVEC
      u.sysReadDir #= true
      u.needsSupervisor #= true
      u.firstOfInstr #= true
      // sysRc rides `imm` (RenamedUop.scala, near the sysOp/sysKind fields: "The MOVEC
      // Rc id rides `imm`") -- payloadFrom maps p.sysRc := u.imm(11 downto 0).asUInt.
      u.useImm #= true
      u.imm #= 0xABC

      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 1, "one entry allocated at robId 0")

      // Mark robId 0 complete via completion port 0. `completes` is a registered Vec
      // (RegInit(False)); empirically (confirmed via instrumentation during this
      // test's development) the poke needs TWO waitSampling edges to be visibly
      // reflected in `completes(0)` -- one edge for the sim-side poke of
      // completion(0).valid/payload to settle onto the net, a second edge for the
      // register itself to latch `when(c.valid) { completes(c.payload) := True }`.
      // This matches the same 2-edge shape used by the "P2.7 review fix" test above
      // (markComplete; waitSampling; clear; waitSampling; THEN assert the derived
      // sticky register) -- a single edge is NOT enough to observably read back a
      // freshly-poked completion here.
      markComplete(dut, 0)
      cd.waitSampling()
      cd.waitSampling()
      clearComplete(dut)

      // headReady = count>0 && completes(h0) && !flushing && !coreHalted -- all four
      // hold right now (no flush/halt ever asserted in this test). Read p0 in THIS
      // SAME cycle, with NO further waitSampling before the asserts below -- an
      // additional edge here would let retire/rteRetire/privViolation (isRte=true and
      // needsSup=true are both live on this entry, so both would otherwise fire on the
      // very next edge) advance head/tail past this entry, invalidating p0.
      assert(dut.rob.logic.completes(0).toBoolean, "completes(0) must be set (test precondition)")
      assert(dut.rob.logic.count.toInt > 0, "count>0 (test precondition)")

      val p0 = dut.rob.logic.p0
      assert(p0.pc.toLong == 0x00401234L, f"pc round-trip: got 0x${p0.pc.toLong}%x")
      assert(p0.isRte.toBoolean, "isRte round-trip")
      assert(p0.sysOp.toBoolean, "sysOp round-trip")
      assert(p0.sysKind.toEnum == m68k040.decode.SysKind.MOVEC, s"sysKind round-trip, got ${p0.sysKind.toEnum}")
      assert(p0.sysReadDir.toBoolean, "sysReadDir round-trip")
      assert(p0.sysRc.toInt == 0xABC, f"sysRc round-trip: got 0x${p0.sysRc.toInt}%x")
      assert(p0.needsSup.toBoolean, "needsSup round-trip")
      assert(p0.first.toBoolean, "first (firstOfInstr) round-trip")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Stage 2 service defaults: halt state remains inert with no command/config driver;
  // the read-only retirement observations are nevertheless real.
  test("DebugCommitService resolves and halt controls stay inert with no debug driver") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      def assertInert(msg: String, expectedMacroCount: BigInt = 0): Unit = {
        assert(!dut.dsink.logic.effectiveHaltOut.toBoolean, s"effectiveHalt must stay False ($msg)")
        assert(!dut.dsink.logic.autoHaltLatchedOut.toBoolean, s"autoHaltLatched must stay False ($msg)")
        assert(dut.dsink.logic.haltReasonDebugOut.toInt == 0, s"haltReasonDebug must stay 0 ($msg)")
        assert(dut.dsink.logic.macroCountOut.toBigInt == expectedMacroCount,
          s"macroCount must be $expectedMacroCount ($msg)")
        assert(dut.dsink.logic.haltHitInstCountOut.toBigInt == 0, s"haltHitInstCount must stay 0 ($msg)")
        assert(!dut.dsink.logic.haltAfterConsumedOut.toBoolean,
          s"haltAfterConsumed must stay False ($msg)")
      }

      assertInert("before any traffic")

      // Drive normal alloc + 2-wide retire traffic and confirm no halt state appears.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 3, pdst = 20, pdstValid = true, pdstOld = 3)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x200, dstArch = 5, pdst = 21, pdstValid = true, pdstOld = 5)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()
      assertInert("after alloc")

      markComplete(dut, 1)
      cd.waitSampling()
      assertInert("mid-completion")
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      clearComplete(dut)

      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      assertInert("at 2-wide retire")

      for (_ <- 0 until 20) {
        cd.waitSampling()
        assertInert("post-retire settle", expectedMacroCount = 2)
      }
      assert(dut.rob.logic.count.toInt == 0, "ROB drained (test precondition sanity)")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Stage 2 task 3: free-running macro-retire counter -- counts macros, not uops.
  test("debugMacroCount increments once per retiring macro, not once per retired uop") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      /** Single-wide alloc of one uop, then complete + retire it alone (single-wide
        * retire, no dual-retire pairing -- keeps the sequencing simple per the
        * plan's own directed-test text). */
      def allocOne(pc: Long, first: Boolean, last: Boolean): Int = {
        pokeRu(dut.rsrc.logic.src.payload(0), pc = pc,
          firstOfInstr = first, lastOfInstr = last)
        dut.rsrc.logic.src.valid #= true
        dut.rsrc.logic.u1v #= false
        val id = dut.rob.logic.tail.toInt
        cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
        dut.rsrc.logic.src.valid #= false
        cd.waitSampling()
        id
      }
      def completeAndRetire(id: Int): Unit = {
        markComplete(dut, id)
        cd.waitSampling()
        clearComplete(dut)
        cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
        cd.waitSampling() // debugMacroCountReg is a Reg: settles one edge after the retire pulse
      }

      assert(dut.dsink.logic.macroCountOut.toBigInt == 0, "macroCount starts at 0")

      // 3-uop cracked macro: only its final uop completes the architectural macro.
      val id0 = allocOne(0x100, first = true, last = false)
      completeAndRetire(id0)
      assert(dut.dsink.logic.macroCountOut.toBigInt == 0,
        s"macroCount must stay 0 after the FIRST uop of an incomplete macro, got ${dut.dsink.logic.macroCountOut.toBigInt}")

      val id1 = allocOne(0x102, first = false, last = false)
      completeAndRetire(id1)
      assert(dut.dsink.logic.macroCountOut.toBigInt == 0,
        s"macroCount must stay 0 after the middle uop retires, got ${dut.dsink.logic.macroCountOut.toBigInt}")

      val id2 = allocOne(0x104, first = false, last = true)
      completeAndRetire(id2)
      assert(dut.dsink.logic.macroCountOut.toBigInt == 1,
        s"macroCount must become 1 only after the LAST uop retires, got ${dut.dsink.logic.macroCountOut.toBigInt}")

      // A second, 1-uop macro.
      val id3 = allocOne(0x200, first = true, last = true)
      completeAndRetire(id3)
      assert(dut.dsink.logic.macroCountOut.toBigInt == 2,
        s"macroCount must be 2 after both macros (3-uop + 1-uop) have fully retired, got ${dut.dsink.logic.macroCountOut.toBigInt}")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Stage 2 task 3: last-committed-PC capture, dual-retire slot-1 priority.
  test("debugLastPc tracks the most recently retired entry's PC, single- and dual-retire") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // Two independent single-uop macros at DIFFERENT PCs, dispatched together and
      // retiring in the SAME cycle (retire0 && retire1 both true).
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 3, pdst = 20, pdstValid = true, pdstOld = 3, firstOfInstr = true)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x200, dstArch = 5, pdst = 21, pdstValid = true, pdstOld = 5, firstOfInstr = true)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()

      markComplete(dut, 1)
      cd.waitSampling()
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      clearComplete(dut)

      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      assert(dut.tsink.logic.fireOut(1).toBoolean, "test precondition: genuine dual (2-wide) retire this cycle")
      cd.waitSampling() // debugLastPcReg settles one edge after the retire pulse

      // debugLastPcReg captures p1.pc/p0.pc -- the macro's OWN (raw) PC field, not the
      // commit/predNextPc value tracked by CommitTrace (that distinction is deliberate
      // in Task 3's own spec text: OFF_LAST_PC is "the most recently retired macro's
      // PC", the instruction's own address).
      assert(dut.dsink.logic.lastPcOut.toLong == 0x200L,
        s"debugLastPc must be slot-1's (p1) raw PC (0x200), not slot-0's (0x100), got 0x${dut.dsink.logic.lastPcOut.toLong.toHexString}")
      // Task 3 review finding: this same dual-retire (retire0 && retire1, both
      // p0.first/p1.first == true) cycle is also the highest-risk case for
      // debugMacroCountReg's increment expression -- it must add 2, not 1 or 0,
      // when BOTH slots retire a macro's own first uop the same cycle. Assert it
      // here rather than only in the reviewer's own (unrecorded) probe.
      assert(dut.dsink.logic.macroCountOut.toBigInt == BigInt(2),
        s"debugMacroCount must be 2 after a genuine dual-first-uop retire, got ${dut.dsink.logic.macroCountOut.toBigInt}")
      assert(dut.rob.logic.count.toInt == 0, "ROB drained (test precondition sanity)")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Stage 2 task 4: macro-boundary LAST detection (spec section 6.2's `macroLast`).
  // `h0IsMacroLast` is a plain read of the alloc-time-captured `RobPayload.last`, so
  // these tests pin the ALLOC->retire plumbing of that bit. The decode-side derivation
  // of `lastOfInstr` itself (which µop of a real crack carries it) is covered by
  // MovemDecodeSpec's own real-decode-path test.

  /** Single-wide alloc of one uop with explicit first/last markers. Returns its robId. */
  private def allocOneFL(dut: SimpleDut, cd: ClockDomain, pc: Long,
                         first: Boolean, last: Boolean): Int = {
    pokeRu(dut.rsrc.logic.src.payload(0), pc = pc, firstOfInstr = first, lastOfInstr = last)
    dut.rsrc.logic.src.valid #= true
    dut.rsrc.logic.u1v #= false
    val id = dut.rob.logic.tail.toInt
    cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
    dut.rsrc.logic.src.valid #= false
    cd.waitSampling()
    id
  }

  /** Complete `id`, wait for its (single-wide) retire pulse, and return h0IsMacroLast as
    * sampled IN the retiring cycle -- the cycle Task 5's stop FSM will read it. */
  private def retireAndSampleLast(dut: SimpleDut, cd: ClockDomain, id: Int): Boolean = {
    markComplete(dut, id)
    cd.waitSampling()
    clearComplete(dut)
    cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
    val v = dut.rob.logic.h0IsMacroLast.toBoolean
    cd.waitSampling()
    v
  }

  test("h0IsMacroLast is True on the trailing uop of a cracked MOVEM, False on its leading uops") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // A real An-base MOVEM (e.g. MOVEM.L (A1)+,D0/D1/A0) decodes to N transfer µops
      // PLUS a trailing An-update µop -- so the macro's last µop is the An update, NOT
      // the last DATA transfer. Markers: first = T,F,F,F ; last = F,F,F,T.
      // (MovemDecodeSpec asserts this exact shape out of the real decode FSM.)
      val ldA = allocOneFL(dut, cd, 0x300, first = true,  last = false)
      assert(!retireAndSampleLast(dut, cd, ldA), "MOVEM transfer 0 is not the macro's last uop")
      val ldB = allocOneFL(dut, cd, 0x300, first = false, last = false)
      assert(!retireAndSampleLast(dut, cd, ldB), "MOVEM transfer 1 is not the macro's last uop")
      val ldC = allocOneFL(dut, cd, 0x300, first = false, last = false)
      assert(!retireAndSampleLast(dut, cd, ldC),
        "the LAST DATA TRANSFER of a MOVEM is still not the macro's last uop -- the An update follows it")
      val anU = allocOneFL(dut, cd, 0x300, first = false, last = true)
      assert(retireAndSampleLast(dut, cd, anU),
        "the trailing An-update uop IS the MOVEM macro's last uop")
    }
  }

  test("h0IsMacroLast is True for every single-uop macro") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)
      // A plain uncracked instruction (NOP-shaped): first AND last, per
      // DecodedUop.lastOfInstr's documented default.
      val id = allocOneFL(dut, cd, 0x400, first = true, last = true)
      assert(retireAndSampleLast(dut, cd, id),
        "a single-uop macro is its own first AND last uop")
    }
  }

  test("h0IsMacroLast is False on OP mid-crack even when STORE has not yet been allocated") {
    // THE regression test for the derivation that had to be retracted: an earlier
    // attempt derived h0IsMacroLast as `(count <= 1) || p1.first`. That reads TRUE for
    // the scenario built below -- a 3-uop memory-destination RMW crack ([load, op,
    // store]) whose LOAD retires before the STORE is even allocated, leaving the ROB at
    // count==1 with the MIDDLE op uop at the head. Recovering a debug stop there would
    // silently drop the RMW's memory write. Reachable for real: MicroOpQueue pops at
    // most 2 uops/cycle and DispatchPlugin gates 2-wide dispatch on ROB/IQ backpressure.
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // 1. LOAD (first=True,last=False) + OP (first=False,last=False) in ONE 2-wide
      //    dispatch cycle. The STORE is deliberately NOT allocated yet.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x500, firstOfInstr = true,  lastOfInstr = false)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x500, firstOfInstr = false, lastOfInstr = false)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      val ldId = dut.rob.logic.tail.toInt
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()
      val opId = (ldId + 1) % 64
      assert(dut.rob.logic.count.toInt == 2, s"precondition: 2 entries allocated, got ${dut.rob.logic.count.toInt}")

      // 2. Complete + retire the LOAD ALONE (the OP is never completed, so retire1
      //    cannot fire and the pair cannot retire 2-wide).
      markComplete(dut, ldId)
      cd.waitSampling()
      clearComplete(dut)
      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      assert(!dut.tsink.logic.fireOut(1).toBoolean, "precondition: LOAD retires ALONE (single-wide)")
      assert(!dut.rob.logic.h0IsMacroLast.toBoolean, "the LOAD is not the macro's last uop either")
      cd.waitSampling()

      // 3. THE counterexample state: ROB holds exactly ONE entry, and it is the MIDDLE
      //    op uop of a still-incomplete macro. Nothing younger exists to look at.
      assert(dut.rob.logic.count.toInt == 1,
        s"precondition: ROB must be at count==1 with the OP at the head, got ${dut.rob.logic.count.toInt}")
      assert(dut.rob.logic.head.toInt == opId, "precondition: the OP is the head entry")
      assert(!dut.rob.logic.h0IsMacroLast.toBoolean,
        "h0IsMacroLast must be FALSE for a MIDDLE crack uop at count==1 -- the retracted " +
        "(count<=1)||p1.first derivation read True here and would have released a debug " +
        "stop before the RMW's STORE ever executed")

      // 4. Now allocate the STORE (first=False,last=True) -- the macro's real last uop --
      //    and confirm the head STILL does not claim the boundary while the OP is there.
      val stId = allocOneFL(dut, cd, 0x500, first = false, last = true)
      assert(stId == (opId + 1) % 64, "precondition: STORE allocated directly behind the OP")
      assert(!dut.rob.logic.h0IsMacroLast.toBoolean,
        "the OP at the head is still not the macro's last uop once the STORE is allocated")

      // 5. Retire the OP, then the STORE; only the STORE's retiring cycle is a boundary.
      assert(!retireAndSampleLast(dut, cd, opId), "the OP's own retiring cycle is not a macro boundary")
      assert(retireAndSampleLast(dut, cd, stId), "the STORE IS the RMW macro's last uop")
    }
  }

  test("debug stop commits the request-cycle macro, flushes younger work, then exposes a stable halt") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x600, firstOfInstr = true, lastOfInstr = true)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x700, firstOfInstr = true, lastOfInstr = true)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()

      markComplete(dut, 1); cd.waitSampling()
      dut.rob.logic.completion(0).payload #= 0; cd.waitSampling()
      clearComplete(dut)
      dut.rob.logic.debugStopRequestIn #= true

      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
      assert(!dut.tsink.logic.fireOut(1).toBoolean,
        "a stop boundary must suppress the following macro in retire slot 1")
      assert(!dut.dsink.logic.effectiveHaltOut.toBoolean,
        "effective halt must wait until recovery has flushed younger work")
      cd.waitSampling()
      dut.rob.logic.debugStopRequestIn #= false
      assert(dut.rob.logic.doFlushReg.toBoolean, "RECOVER must issue the registered global flush")
      assert(dut.dsink.logic.livePcOut.toLong == 0x602L,
        f"saved live PC must be the committed macro successor, got 0x${dut.dsink.logic.livePcOut.toLong}%x")
      cd.waitSampling()
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean, "halt becomes effective only after recovery")
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.MANUAL)
      assert(dut.rob.logic.count.toInt == 0, "recovery must flush all younger ROB work")
      for (_ <- 0 until 20) {
        assert(!dut.tsink.logic.fireOut(0).toBoolean && !dut.tsink.logic.fireOut(1).toBoolean)
        assert(dut.dsink.logic.livePcOut.toLong == 0x602L, "JTAG-visible live PC must remain stable")
        cd.waitSampling()
      }
    }
  }

  test("debug stop lets every remaining uop of the current cracked macro commit") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      val ids = Seq(
        allocOneFL(dut, cd, 0x800, first = true,  last = false),
        allocOneFL(dut, cd, 0x800, first = false, last = false),
        allocOneFL(dut, cd, 0x800, first = false, last = true),
        allocOneFL(dut, cd, 0x900, first = true,  last = true))

      dut.rob.logic.debugStopRequestIn #= true
      var retired = 0
      ids.take(3).foreach { id =>
        markComplete(dut, id); cd.waitSampling(); clearComplete(dut)
        waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
        retired += 1
        if (retired < 3) assert(!dut.rob.logic.debugRecoverEnter.toBoolean)
        if (retired == 3) assert(dut.rob.logic.debugRecoverEnter.toBoolean)
        cd.waitSampling()
        if (retired == 1) dut.rob.logic.debugStopRequestIn #= false
      }
      assert(retired == 3, "all three uops of the request-cycle macro must retire")
      cd.waitSampling()
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean)
      assert(dut.rob.logic.count.toInt == 0, "the following macro must be flushed, not committed")
      assert(dut.dsink.logic.lastPcOut.toLong == 0x800L)
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.MANUAL)
    }
  }

  test("debug resume releases a stable halt and retirement can continue") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      val id = allocOneFL(dut, cd, 0xa00, first = true, last = true)
      dut.rob.logic.debugStopRequestIn #= true
      markComplete(dut, id); cd.waitSampling(); clearComplete(dut)
      waitUntil(cd, dut.rob.logic.debugRecoverEnter.toBoolean)
      cd.waitSampling()
      dut.rob.logic.debugStopRequestIn #= false
      cd.waitSampling()
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean)

      dut.rob.logic.debugResumeRequestIn #= true
      cd.waitSampling()
      dut.rob.logic.debugResumeRequestIn #= false
      cd.waitSampling()
      assert(!dut.dsink.logic.effectiveHaltOut.toBoolean, "resume must leave HALTED")

      val next = allocOneFL(dut, cd, 0xa02, first = true, last = true)
      markComplete(dut, next); cd.waitSampling(); clearComplete(dut)
      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
      cd.waitSampling()
      assert(dut.dsink.logic.lastPcOut.toLong == 0xa02L, "retirement resumes after continue")
    }
  }

  test("single-step retires one single-uop macro, blocks its dual-retire successor, and re-halts") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      // Park at an empty, already-clean boundary.
      dut.rob.logic.debugStopRequestIn #= true
      waitUntil(cd, dut.rob.logic.debugRecoverEnter.toBoolean)
      cd.waitSampling()
      dut.rob.logic.debugStopRequestIn #= false
      cd.waitSampling()
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean)

      // Accept a one-cycle step command, then model the frontend delivering two
      // independently complete macros together. Only the first may retire.
      dut.rob.logic.debugStepRequestIn #= true
      cd.waitSampling()
      dut.rob.logic.debugStepRequestIn #= false
      cd.waitSampling()
      assert(dut.rob.logic.debugHaltState.toEnum == DebugHaltState.STEP_RUNNING)

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0xc00, firstOfInstr = true, lastOfInstr = true)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0xd00, firstOfInstr = true, lastOfInstr = true)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()

      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      dut.rob.logic.completion(1).valid #= true
      dut.rob.logic.completion(1).payload #= 1
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
      assert(!dut.tsink.logic.fireOut(1).toBoolean,
        "step must not retire the first uop of the following macro in slot 1")
      assert(dut.rob.logic.debugStepBoundaryHit.toBoolean)
      cd.waitSampling(2)
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean)
      assert(dut.rob.logic.count.toInt == 0, "the unexecuted successor must be flushed")
      assert(dut.dsink.logic.lastPcOut.toLong == 0xc00L)
      assert(dut.dsink.logic.livePcOut.toLong == 0xc02L)
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.STEP)
    }
  }

  test("single-step completes a cracked macro including a same-macro slot-1 tail") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      dut.rob.logic.debugStopRequestIn #= true
      waitUntil(cd, dut.rob.logic.debugRecoverEnter.toBoolean)
      cd.waitSampling()
      dut.rob.logic.debugStopRequestIn #= false
      cd.waitSampling()
      dut.rob.logic.debugStepRequestIn #= true
      cd.waitSampling()
      dut.rob.logic.debugStepRequestIn #= false

      // Both entries are uops of ONE macro. Slot 1 is therefore required to retire:
      // suppressing it unconditionally would stop halfway through the macro.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0xe00, firstOfInstr = true, lastOfInstr = false)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0xe00, firstOfInstr = false, lastOfInstr = true)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 0
      dut.rob.logic.completion(1).valid #= true
      dut.rob.logic.completion(1).payload #= 1
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false
      dut.rob.logic.completion(1).valid #= false
      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
      assert(dut.tsink.logic.fireOut(1).toBoolean,
        "same-macro tail must be allowed in slot 1 during a step")
      assert(dut.rob.logic.debugStepBoundaryHit.toBoolean)
      cd.waitSampling(2)
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean)
      assert(dut.dsink.logic.macroCountOut.toBigInt == 1)
      assert(dut.dsink.logic.livePcOut.toLong == 0xe02L)
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.STEP)
    }
  }

  test("single-step request while running is rejected without disturbing execution") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)
      dut.rob.logic.debugStepRequestIn #= true
      cd.waitSampling()
      dut.rob.logic.debugStepRequestIn #= false
      cd.waitSampling()
      assert(dut.rob.logic.debugStepRejected.toBoolean)
      assert(dut.rob.logic.debugHaltState.toEnum == DebugHaltState.RUNNING)

      val id = allocOneFL(dut, cd, 0xf00, first = true, last = true)
      markComplete(dut, id); cd.waitSampling(); clearComplete(dut)
      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
      assert(!dut.rob.logic.debugStepBoundaryHit.toBoolean)
      cd.waitSampling()
      assert(!dut.dsink.logic.effectiveHaltOut.toBoolean)
    }
  }

  test("fatal halt is effective, distinctly reported, and rejects resume and step") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      dut.rob.logic.debugStopRequestIn #= true
      waitUntil(cd, dut.rob.logic.debugRecoverEnter.toBoolean)
      cd.waitSampling()
      dut.rob.logic.debugStopRequestIn #= false
      cd.waitSampling()
      assert(dut.rob.logic.debugHaltState.toEnum == DebugHaltState.HALTED)

      dut.rob.logic.coreHaltedIn #= true
      cd.waitSampling()
      dut.rob.logic.coreHaltedIn #= false
      cd.waitSampling()
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean)
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.FATAL)

      dut.rob.logic.debugResumeRequestIn #= true
      dut.rob.logic.debugStepRequestIn #= true
      cd.waitSampling()
      dut.rob.logic.debugResumeRequestIn #= false
      dut.rob.logic.debugStepRequestIn #= false
      cd.waitSampling()
      assert(dut.rob.logic.debugHaltState.toEnum == DebugHaltState.HALTED)
      assert(dut.rob.logic.debugStepRejected.toBoolean)
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean)
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.FATAL)
    }
  }

  test("clear-sticky clears the displayed reason without releasing an active halt") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)
      dut.rob.logic.debugStopRequestIn #= true
      waitUntil(cd, dut.rob.logic.debugRecoverEnter.toBoolean)
      cd.waitSampling()
      dut.rob.logic.debugStopRequestIn #= false
      cd.waitSampling()
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.MANUAL)

      dut.rob.logic.debugClearStickyIn #= true
      cd.waitSampling()
      dut.rob.logic.debugClearStickyIn #= false
      cd.waitSampling()
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.NONE)
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean)
      assert(dut.rob.logic.debugHaltState.toEnum == DebugHaltState.HALTED)
    }
  }

  test("halt-after stops after the exact completed macro target and before its successor") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      val ids = Seq(
        allocOneFL(dut, cd, 0xb00, first = true, last = true),
        allocOneFL(dut, cd, 0xb02, first = true, last = true),
        allocOneFL(dut, cd, 0xb04, first = true, last = true))
      dut.rob.logic.haltAfterTargetIn #= 2
      dut.rob.logic.haltAfterEpochIn #= 1
      dut.rob.logic.haltAfterArmedIn #= true
      cd.waitSampling(4) // arm snapshot + registered wide comparison

      markComplete(dut, ids(0)); cd.waitSampling(); clearComplete(dut)
      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
      cd.waitSampling()
      assert(dut.dsink.logic.macroCountOut.toBigInt == 1)
      assert(!dut.dsink.logic.effectiveHaltOut.toBoolean)

      markComplete(dut, ids(1)); cd.waitSampling(); clearComplete(dut)
      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
      cd.waitSampling()
      assert(dut.dsink.logic.macroCountOut.toBigInt == 2)

      // Make the successor ready while the registered comparison catches up. It must
      // remain architecturally untouched and be discarded by recovery.
      markComplete(dut, ids(2)); cd.waitSampling(); clearComplete(dut)
      waitUntil(cd, dut.dsink.logic.effectiveHaltOut.toBoolean)
      assert(dut.dsink.logic.macroCountOut.toBigInt == 2,
        "halt-after retired a macro beyond the absolute target")
      assert(dut.dsink.logic.haltHitInstCountOut.toBigInt == 2,
        "halt descriptor did not carry the comparator's completed-macro count")
      assert(dut.dsink.logic.lastPcOut.toLong == 0xb02L,
        "the successor after the target macro committed")
      assert(dut.dsink.logic.livePcOut.toLong == 0xb04L,
        "halt-after resume PC must name the untouched successor, not skip past it")
      assert(dut.rob.logic.count.toInt == 0, "recovery did not flush the ready successor")
      assert(dut.dsink.logic.autoHaltLatchedOut.toBoolean)
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.HALT_AFTER)
    }
  }

  test("target reprogramming invalidates an in-flight halt-after comparison") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      val id = allocOneFL(dut, cd, 0xc00, first = true, last = true)
      dut.rob.logic.haltAfterTargetIn #= 1
      dut.rob.logic.haltAfterEpochIn #= 1
      dut.rob.logic.haltAfterArmedIn #= true
      cd.waitSampling(4)
      markComplete(dut, id); cd.waitSampling(); clearComplete(dut)
      waitUntil(cd, dut.tsink.logic.fireOut(0).toBoolean)
      cd.waitSampling() // count becomes 1; old-target compare is now pending

      // Model the accepted target write exactly: invalidate is visible on the write
      // edge, the new epoch/target and disarmed state are visible immediately after it.
      dut.rob.logic.haltAfterInvalidateIn #= true
      dut.rob.logic.haltAfterArmedIn #= false
      dut.rob.logic.haltAfterEpochIn #= 2
      dut.rob.logic.haltAfterTargetIn #= 10
      cd.waitSampling()
      dut.rob.logic.haltAfterInvalidateIn #= false
      cd.waitSampling(6)
      assert(!dut.dsink.logic.effectiveHaltOut.toBoolean,
        "a comparison from the superseded epoch stopped the core")
      assert(!dut.dsink.logic.autoHaltLatchedOut.toBoolean)

      // Re-arming the new, higher target is live but does not stop at count 1.
      dut.rob.logic.haltAfterArmedIn #= true
      cd.waitSampling(5)
      assert(!dut.dsink.logic.effectiveHaltOut.toBoolean)

      // A deliberately lower replacement target is not stale: after re-arm it must
      // stop at the next clean boundary because the absolute count already exceeds it.
      dut.rob.logic.haltAfterInvalidateIn #= true
      dut.rob.logic.haltAfterArmedIn #= false
      dut.rob.logic.haltAfterEpochIn #= 3
      dut.rob.logic.haltAfterTargetIn #= 0
      cd.waitSampling()
      dut.rob.logic.haltAfterInvalidateIn #= false
      dut.rob.logic.haltAfterArmedIn #= true
      waitUntil(cd, dut.dsink.logic.effectiveHaltOut.toBoolean)
      assert(dut.dsink.logic.macroCountOut.toBigInt == 1)
    }
  }

  test("marked PC breakpoint halts pre-effect, flushes to the hit PC, and reports its slot") {
    M68kSim().compile(new SimpleDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initSimple(dut, cd)

      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x12345678L,
        firstOfInstr = true, lastOfInstr = true,
        pdstValid = true, dstArch = 3, pdst = 20, pdstOld = 3,
        debugBreakValid = true, debugBreakSlot = 2)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false

      var sawHit = false
      var sawRetire = false
      var cycles = 0
      while (!dut.dsink.logic.effectiveHaltOut.toBoolean && cycles < 20) {
        cd.waitSampling()
        sawHit ||= dut.dsink.logic.breakpointHitValidOut.toBoolean &&
          dut.dsink.logic.breakpointHitSlotOut.toInt == 2
        sawRetire ||= dut.tsink.logic.fireOut(0).toBoolean || dut.tsink.logic.fireOut(1).toBoolean
        cycles += 1
      }
      assert(dut.dsink.logic.effectiveHaltOut.toBoolean, "breakpoint never reached effective halt")
      assert(sawHit, "DebugCommitService did not report breakpoint slot 2")
      assert(!sawRetire, "the breakpoint-marked macro retired before the stop")
      assert(dut.dsink.logic.macroCountOut.toBigInt == 0,
        "pre-effect breakpoint must not increment the macro-retire count")
      assert(dut.dsink.logic.haltReasonDebugOut.toInt == DebugHaltReasonCode.BREAKPOINT)
      assert(dut.dsink.logic.haltHitPcOut.toLong == 0x12345678L)
      assert(dut.dsink.logic.livePcOut.toLong == 0x12345678L)
      assert(dut.rob.logic.count.toInt == 0, "breakpoint recovery must flush all in-flight work")
    }
  }
}
