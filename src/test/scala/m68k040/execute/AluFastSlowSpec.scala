package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.decode.DecOp
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Fast/slow ALU split: fast ops (ADD) complete at latency-1 (S1) with bypass;
  * slow ops (SHIFT, ANDI-to-CCR) complete at latency-2 (S2). Latency-agnostic
  * correctness: the slow result is correct, just one cycle later. */
class AluFastSlowSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
    val eu     = new AluEuPlugin
    val src    = new AluEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, rfNzvc, rfX, eu, src)) }
  }

  def initPorts(d: Dut): Unit = {
    val s = d.src.logic
    s.iShiftOp #= 0; s.iShiftDir #= false; s.iToCcr #= false
    s.iReadsNz #= false; s.iPNzvcSrc #= 0; s.iReadsX #= false; s.iPXSrc #= 0
  }
  def idle(d: Dut): Unit = { d.src.logic.iValid #= false }

  def issueMoveq(d: Dut, imm: Long, pdst: Int, robId: Int): Unit = {
    val s = d.src.logic
    s.iValid #= true; s.iOp #= DecOp.MOVE; s.iSize #= Size.LONG
    s.iUseImm #= true; s.iImm #= BigInt(imm & 0xffffffffL)
    s.iPsrcAValid #= false; s.iPsrcBValid #= false
    s.iPdst #= pdst; s.iPdstValid #= true
    s.iWritesNz #= true; s.iPNzvcDst #= (pdst & 0xf)
    s.iWritesX #= false; s.iPXDst #= 0
    s.iShiftOp #= 0; s.iShiftDir #= false; s.iToCcr #= false
    s.iReadsNz #= false; s.iReadsX #= false
    s.iRobId #= robId
  }
  def issueAdd(d: Dut, pa: Int, pb: Int, pdst: Int, robId: Int): Unit = {
    val s = d.src.logic
    s.iValid #= true; s.iOp #= DecOp.ADD; s.iSize #= Size.LONG
    s.iUseImm #= false
    s.iPsrcA #= pa; s.iPsrcAValid #= true
    s.iPsrcB #= pb; s.iPsrcBValid #= true
    s.iPdst #= pdst; s.iPdstValid #= true
    s.iWritesNz #= true; s.iPNzvcDst #= (pdst & 0xf)
    s.iWritesX #= true; s.iPXDst #= (pdst & 0xf)
    s.iShiftOp #= 0; s.iShiftDir #= false; s.iToCcr #= false
    s.iReadsNz #= false; s.iReadsX #= false
    s.iRobId #= robId
  }
  // SHIFT (line-E) immediate form: data = Dr (psrcA), count = imm[5:0].
  // shiftOp 1 = LS, dir true = left -> LSL.
  def issueLslImm(d: Dut, pa: Int, count: Int, pdst: Int, robId: Int): Unit = {
    val s = d.src.logic
    s.iValid #= true; s.iOp #= DecOp.SHIFT; s.iSize #= Size.LONG
    s.iUseImm #= true; s.iImm #= BigInt(count & 0x3f)
    s.iPsrcA #= pa; s.iPsrcAValid #= true
    s.iPsrcBValid #= false
    s.iPdst #= pdst; s.iPdstValid #= true
    s.iWritesNz #= true; s.iPNzvcDst #= (pdst & 0xf)
    s.iWritesX #= true; s.iPXDst #= (pdst & 0xf)
    s.iShiftOp #= 1; s.iShiftDir #= true; s.iToCcr #= false
    s.iReadsNz #= false; s.iReadsX #= false
    s.iRobId #= robId
  }

  // Issue exactly ONE cycle (drive at the current edge, then deassert) and count the
  // number of cycles from the issue edge until completion(robId) is observed. Returns
  // the completion latency in cycles (1 for a lat1 op, 2 for a lat2 op), or -1.
  // `drive` pokes the issue inputs for this single cycle.
  def latencyOf(d: Dut, robId: Int, window: Int)(drive: => Unit): Int = {
    val cd = d.clockDomain
    drive
    cd.waitSampling()      // issue accepted on this edge (T); now at T (post-edge)
    idle(d)
    var lat = -1
    var i = 1
    while (i <= window && lat < 0) {
      // after `i` edges past issue we are at T+i; sample the registered completion.
      if (d.src.logic.cValid.toBoolean && d.src.logic.cRob.toInt == robId) lat = i
      cd.waitSampling(); i += 1
    }
    lat
  }

  test("fast ADD completes at latency-1 (S1)", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initPorts(dut); idle(dut)
      dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0
      cd.waitSampling(80)
      issueMoveq(dut, 10, pdst = 0, robId = 0); cd.waitSampling()
      issueMoveq(dut, 5,  pdst = 1, robId = 1); cd.waitSampling()
      idle(dut); cd.waitSampling(6)
      val lat = latencyOf(dut, 7, window = 6) { issueAdd(dut, pa = 0, pb = 1, pdst = 2, robId = 7) }
      assert(lat == 2, s"fast ADD completion latency=$lat (expected 2 = arch lat1; the harness counts one extra pre-capture edge)")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 2; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 15, s"R2 got ${dut.src.logic.obsIntData.toBigInt}")
    }
  }

  test("slow SHIFT (LSL #1) result correct, completes at latency-2 (S2)", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initPorts(dut); idle(dut)
      dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0
      cd.waitSampling(80)
      issueMoveq(dut, 0x21, pdst = 3, robId = 0); cd.waitSampling()  // R3 = 0x21
      idle(dut); cd.waitSampling(6)
      val lat = latencyOf(dut, 9, window = 6) { issueLslImm(dut, pa = 3, count = 1, pdst = 4, robId = 9) }
      assert(lat == 3, s"slow SHIFT completion latency=$lat (expected 3 = arch lat2, exactly ONE cycle after the fast ADD's 2)")
      cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 4; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 0x42,
        s"R4 (LSL.L #1 of 0x21) got 0x${dut.src.logic.obsIntData.toBigInt.toString(16)} (expected 0x42)")
    }
  }

  test("slow SHIFT writeback lands at S2 (PRF holds old dst until then)", VerilatorTest) {
    // The slow producer must NOT write the PRF / bypass its S1 partial. Reading the
    // dst forceNoBypass while the shift is in S1 still observes the OLD value; only the
    // S2 (lat2) write updates it. Verifies the result + the no-early-write property.
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initPorts(dut); idle(dut)
      dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0
      cd.waitSampling(80)
      issueMoveq(dut, 0x11, pdst = 5, robId = 0); cd.waitSampling()  // R5 = 0x11 (the shift src)
      issueMoveq(dut, 0xDD, pdst = 6, robId = 1); cd.waitSampling()  // R6 = 0xDD (old dst marker)
      idle(dut); cd.waitSampling(6)
      // LSL.L #2, R5 -> R6 (=0x44). issue at T.
      issueLslImm(dut, pa = 5, count = 2, pdst = 6, robId = 11); cd.waitSampling()
      idle(dut)
      // at T+1 the shift is in S1 (partial). Reading R6 forceNoBypass observes the PRF,
      // which must still hold 0xDD (no early/partial write).
      dut.src.logic.obsIntAddr #= 6; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 0xDD,
        s"R6 at S1 of the shift must still be the OLD 0xDD (no early write), got 0x${dut.src.logic.obsIntData.toBigInt.toString(16)}")
      cd.waitSampling(5)
      dut.src.logic.obsIntAddr #= 6; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 0x44,
        s"R6 (LSL.L #2 of 0x11) got 0x${dut.src.logic.obsIntData.toBigInt.toString(16)} (expected 0x44 at lat2)")
    }
  }
}
