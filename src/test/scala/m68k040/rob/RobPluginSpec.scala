package m68k040.rob

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.cache.{IcachePlugin, IcacheSim}
import m68k040.frontend.FetchAlignPlugin
import m68k040.decode.{DecodeStage, DecOp}
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
    val rob  = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val tsink = new CommitTraceSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, rob, csink, tsink)) }
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
  }

  def initSimple(dut: SimpleDut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.markComplete.valid #= false
    dut.rob.logic.flush.valid #= false
    cd.waitSampling()
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("alloc + 2-wide retire") {
    SimConfig.compile(new SimpleDut).doSim { dut =>
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
      dut.rob.logic.markComplete.valid #= true
      dut.rob.logic.markComplete.payload #= 1
      cd.waitSampling()
      dut.rob.logic.markComplete.payload #= 0
      cd.waitSampling()
      dut.rob.logic.markComplete.valid #= false

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
    SimConfig.compile(new SimpleDut).doSim { dut =>
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
      dut.rob.logic.markComplete.valid #= true
      dut.rob.logic.markComplete.payload #= 1
      cd.waitSampling()
      dut.rob.logic.markComplete.valid #= false

      // For several cycles, no retire (head=0 not complete).
      for (_ <- 0 until 5) {
        assert(!dut.tsink.logic.fireOut(0).toBoolean, "no retire while head incomplete")
        cd.waitSampling()
      }
      assert(dut.rob.logic.count.toInt == 2, s"count still 2, got ${dut.rob.logic.count.toInt}")

      // Now complete robId 0 -> 2-wide retire fires.
      dut.rob.logic.markComplete.valid #= true
      dut.rob.logic.markComplete.payload #= 0
      cd.waitSampling()
      dut.rob.logic.markComplete.valid #= false
      cd.waitSamplingWhere(dut.tsink.logic.fireOut(0).toBoolean)
      assert(dut.tsink.logic.fireOut(0).toBoolean && dut.tsink.logic.fireOut(1).toBoolean, "both retire once head completes")
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 0, "ROB drained")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("retireAlone: branch retires 1-wide even if head+1 complete") {
    SimConfig.compile(new SimpleDut).doSim { dut =>
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
      dut.rob.logic.markComplete.valid #= true
      dut.rob.logic.markComplete.payload #= 1
      cd.waitSampling()
      dut.rob.logic.markComplete.payload #= 0
      cd.waitSampling()
      dut.rob.logic.markComplete.valid #= false

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
    SimConfig.compile(new SimpleDut).doSim { dut =>
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

  // ── End-to-end sustained free-loop DUT ────────────────────────────────────────
  class E2EDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val dsrc = new m68k040.rename.DecodeUopSourcePlugin
    val ren  = new RenameStage
    val rob  = new RobPlugin
    val tsink = new CommitTraceSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), dsrc, ren, rob, tsink)) }
  }

  test("sustained free-loop: real rename->rob keeps flowing past 48 allocations", VerilatorTest) {
    SimConfig.withVerilator.compile(new E2EDut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.dsrc.logic.src.valid #= false
      dut.dsrc.logic.s1v #= false
      dut.rob.logic.markComplete.valid #= false
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
        // Set up completion for any due entries (single markComplete port -> at most 1/cycle).
        // Default no complete.
        dut.rob.logic.markComplete.valid #= false
        if (pending.nonEmpty && pending.front._2 <= cyc) {
          val (rid, _) = pending.dequeue()
          dut.rob.logic.markComplete.valid #= true
          dut.rob.logic.markComplete.payload #= rid
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
        dut.rob.logic.markComplete.valid #= false
        if (pending.front._2 <= cyc) {
          val (rid, _) = pending.dequeue()
          dut.rob.logic.markComplete.valid #= true
          dut.rob.logic.markComplete.payload #= rid
        }
        cd.waitSampling()
        cyc += 1; drain += 1
      }
      dut.rob.logic.markComplete.valid #= false
      // let final retires drain
      for (_ <- 0 until 20) cd.waitSampling()

      assert(allocs >= totalUops, s"pipeline stalled: only $allocs of $totalUops allocated (freelist drained -> loop not closed)")
      assert(totalFires > 48, s"only $totalFires retires observed; loop not sustained past 48 (freelist would have drained)")
    }
  }
}
