package m68k040.dispatch

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.rob.{RobPlugin, RenameUopSourcePlugin, RenameCommitSinkPlugin}
import m68k040.rename.RenamedUop
import m68k040.decode.DecOp
import m68k040.isa.{Cluster, Size}
import m68k040.execute.iq.{IssueQueuePlugin, IqSinkPlugin, IssueQueueService}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Unit test for DispatchPlugin: rename → ROB-alloc + IQ-push with combined
  * back-pressure. Verifies (1) a renamed µop fans to BOTH the ROB and IQ with a
  * matching robId, (2) 2-wide dispatch gets robIds 0 and 1, (3) ren.uops.ready
  * deasserts when EITHER the IQ or the ROB is full. */
class DispatchSpec extends AnyFunSuite {

  /** Ties off the IQ flush port (no flush exercised in this unit test). */
  class IqFlushTiePlugin extends FiberPlugin {
    val logic = during build new Area { host[IssueQueueService].flushPort := False }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rsrc = new RenameUopSourcePlugin
    val rob  = new RobPlugin
    val disp = new DispatchPlugin
    val iq   = new IssueQueuePlugin
    val sink = new IqSinkPlugin
    val ftie = new IqFlushTiePlugin
    val csink = new RenameCommitSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, rob, disp, iq, sink, ftie, csink)) }
  }

  /** Poke a RenamedUop slot. dst written to an int physreg (so it has a real op). */
  def pokeRu(
      u: RenamedUop,
      valid: Boolean = true,
      pc: Long = 0,
      dstArch: Int = 0,
      pdst: Int = 0, pdstValid: Boolean = true, pdstOld: Int = 0
  ): Unit = {
    u.valid #= valid
    u.pc #= pc; u.nextPc #= pc + 2; u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
    u.useImm #= true; u.imm #= 0
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
    u.unimplemented #= false
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.dstArch #= dstArch
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= pdst; u.pdstValid #= pdstValid; u.pdstOld #= pdstOld
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
  }

  def init(dut: Dut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.completion(0).valid #= false
    dut.rob.logic.completion(1).valid #= false
    dut.rob.logic.flush.valid #= false
    dut.sink.logic.ready0 #= true
    dut.sink.logic.ready1 #= true
    cd.waitSampling()
  }

  /** Fork an issue-collector: records (robId) for every IQ issue-port fire,
    * in cycle order. The sink keeps issue ready high. */
  def forkIssueCollector(dut: Dut, cd: ClockDomain): scala.collection.mutable.ArrayBuffer[Int] = {
    val issued = scala.collection.mutable.ArrayBuffer[Int]()
    fork {
      while (true) {
        cd.waitSampling()
        if (dut.sink.logic.v0.toBoolean) issued += dut.sink.logic.rob0.toInt
        if (dut.sink.logic.v1.toBoolean) issued += dut.sink.logic.rob1.toInt
      }
    }
    issued
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("dispatch fans one µop to ROB + IQ with matching robId", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)
      val issued = forkIssueCollector(dut, cd)

      // Push one renamed µop (slot0 only). robId should be 0 (first alloc).
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 3, pdst = 20, pdstOld = 3)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false

      // ROB tail must advance to 1.
      cd.waitSampling()
      assert(dut.rob.logic.tail.toInt == 1, s"ROB tail after 1 alloc = ${dut.rob.logic.tail.toInt}")
      assert(dut.rob.logic.count.toInt == 1, s"ROB count = ${dut.rob.logic.count.toInt}")

      // Let it issue + drain, then push a SECOND µop -> robId 1, tail -> 2.
      cd.waitSampling(4)
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x102, dstArch = 4, pdst = 21, pdstOld = 4)
      dut.rsrc.logic.src.valid #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling(6)
      assert(dut.rob.logic.tail.toInt == 2, s"ROB tail after 2 allocs = ${dut.rob.logic.tail.toInt}")

      // The two µops issued from the IQ in order with robIds 0 then 1.
      assert(issued.toSeq == Seq(0, 1), s"issued robIds = ${issued.toSeq}, expected (0,1)")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("2-wide dispatch: robIds 0 and 1, tail += 2", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      val issued = forkIssueCollector(dut, cd)
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x100, dstArch = 1, pdst = 20, pdstOld = 1)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0x102, dstArch = 2, pdst = 21, pdstOld = 2)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false

      cd.waitSampling()
      assert(dut.rob.logic.tail.toInt == 2, s"ROB tail after 2-wide alloc = ${dut.rob.logic.tail.toInt}")
      assert(dut.rob.logic.count.toInt == 2, s"ROB count = ${dut.rob.logic.count.toInt}")

      // Both must issue from the IQ (two age-ordered ports) with robIds 0 and 1.
      cd.waitSampling(6)
      assert(issued.toSet == Set(0, 1), s"2-wide issued robIds = ${issued.toSeq}, expected {0,1}")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  test("combined back-pressure: IQ full OR ROB full deasserts ren.uops.ready", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      init(dut, cd)

      // --- IQ full: hold the sink not-ready so nothing issues; keep pushing.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false
      var pushed = 0
      var cyc = 0
      while (pushed < 16 && cyc < 200) {
        pokeRu(dut.rsrc.logic.src.payload(0), pc = pushed * 2, dstArch = pushed % 8, pdst = 20 + (pushed % 8), pdstOld = pushed % 8)
        dut.rsrc.logic.src.valid #= true
        dut.rsrc.logic.u1v #= false
        cd.waitSampling()
        if (dut.rsrc.logic.src.ready.toBoolean) pushed += 1
        cyc += 1
      }
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      // IQ is now full (slots can't drain, sink not ready) -> push.ready low ->
      // ren.uops.ready must be low even though the ROB still has room (count<16<62).
      assert(dut.rob.logic.count.toInt <= 16, s"ROB count = ${dut.rob.logic.count.toInt} (room remains)")
      dut.rsrc.logic.src.valid #= true
      sleep(1)
      assert(!dut.rsrc.logic.src.ready.toBoolean, "ren.uops.ready must deassert when IQ is full (ROB has room)")
      dut.rsrc.logic.src.valid #= false

      // Drain the IQ by re-enabling the sink, then re-init for the ROB-full case.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      for (_ <- 0 until 40) cd.waitSampling()

      // --- ROB full: complete nothing, never let entries retire, keep pushing
      // single-wide until the ROB fills (count reaches depth-1). The IQ drains
      // freely (sink ready), so the ONLY back-pressure source is the ROB.
      var allocs = 0
      cyc = 0
      while (dut.rob.logic.count.toInt < 62 && cyc < 400) {
        pokeRu(dut.rsrc.logic.src.payload(0), pc = allocs * 2, dstArch = allocs % 8, pdst = 20 + (allocs % 8), pdstOld = allocs % 8)
        dut.rsrc.logic.src.valid #= true
        dut.rsrc.logic.u1v #= false
        cd.waitSampling()
        if (dut.rsrc.logic.src.ready.toBoolean) allocs += 1
        cyc += 1
      }
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt >= 62, s"ROB did not fill: count=${dut.rob.logic.count.toInt}")
      // ROB allocReady = count <= depth-2 = 62 -> at count>=63 it's low; ensure
      // we are at the boundary where another 2-wide push is refused.
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      sleep(1)
      assert(!dut.rsrc.logic.src.ready.toBoolean, "ren.uops.ready must deassert when ROB is full")
      dut.rsrc.logic.src.valid #= false
    }
  }
}
