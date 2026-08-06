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
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, tsink, cacheCtrl)) }
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
      isBranch: Boolean = false
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
  }

  def initSimple(dut: SimpleDut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.completion(0).valid #= false
    dut.rob.logic.completion(1).valid #= false
    dut.rob.logic.flush.valid #= false
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
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), intCtrl, rsrc, drv, rob, csink, tsink)) }
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
  }

  def initGate(dut: GateDut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.flush.valid #= false
    for (c <- dut.rob.logic.completion) { c.valid #= false; c.payload #= 0 }
    dut.rob.logic.branchCompletion.valid #= false
    dut.rob.logic.preciseDrainBusyIn #= false
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
      dut.rob.logic.coreHaltedIn #= true
      cd.waitSampling()
      dut.rob.logic.coreHaltedIn #= false
      cd.waitSampling()
      assert(dut.rob.logic.coreHalted.toBoolean, "coreHalted must latch after a 1-cycle coreHaltedIn pulse")

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
}
