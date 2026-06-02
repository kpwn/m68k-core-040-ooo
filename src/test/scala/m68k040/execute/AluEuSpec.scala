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

class AluEuSpec extends AnyFunSuite {
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

  // helper: drive one issue for a cycle (call inside doSim)
  def idle(d: Dut): Unit = { d.src.logic.iValid #= false }
  def issueMoveq(d: Dut, imm: Long, pdst: Int, robId: Int): Unit = {
    val s = d.src.logic
    s.iValid #= true; s.iOp #= DecOp.MOVE; s.iSize #= Size.LONG
    s.iUseImm #= true; s.iImm #= BigInt(imm & 0xffffffffL)
    s.iPsrcAValid #= false; s.iPsrcBValid #= false
    s.iPdst #= pdst; s.iPdstValid #= true
    s.iWritesNz #= true; s.iPNzvcDst #= (pdst & 0xf)
    s.iWritesX #= false; s.iPXDst #= 0; s.iRobId #= robId
  }
  def issueAdd(d: Dut, pa: Int, pb: Int, pdst: Int, robId: Int): Unit = {
    val s = d.src.logic
    s.iValid #= true; s.iOp #= DecOp.ADD; s.iSize #= Size.LONG
    s.iUseImm #= false
    s.iPsrcA #= pa; s.iPsrcAValid #= true
    s.iPsrcB #= pb; s.iPsrcBValid #= true
    s.iPdst #= pdst; s.iPdstValid #= true
    s.iWritesNz #= true; s.iPNzvcDst #= (pdst & 0xf)
    s.iWritesX #= true; s.iPXDst #= (pdst & 0xf); s.iRobId #= robId
  }

  test("single immediate op writes int PRF + fires completion", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut); dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0
      cd.waitSampling(80) // PRF init sweep
      issueMoveq(dut, 0x12345678L, pdst = 7, robId = 3)
      cd.waitSampling()   // accepted at T; now at T+1 (S1)
      idle(dut)
      // completion should fire at T+1 (this cycle, since RegNext)
      // sample over the next few cycles
      var sawCompl = false
      for (_ <- 0 until 4) {
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 3) sawCompl = true
        cd.waitSampling()
      }
      assert(sawCompl, "completion for robId 3 must fire")
      dut.src.logic.obsIntAddr #= 7; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == BigInt(0x12345678L), s"R7 got 0x${dut.src.logic.obsIntData.toBigInt.toString(16)}")
    }
  }

  test("reg-reg ADD reads operands from PRF", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut); dut.src.logic.obsIntAddr #= 0; dut.src.logic.obsNzvcAddr #= 0
      cd.waitSampling(80)
      // preload R0=100, R1=23 via two MOVEQ
      issueMoveq(dut, 100, pdst = 0, robId = 0); cd.waitSampling()
      issueMoveq(dut, 23,  pdst = 1, robId = 1); cd.waitSampling()
      idle(dut); cd.waitSampling(4)   // let writes commit to RF
      // ADD R1,R0 -> R2  (src1=R0=100, src2=R1=23)
      issueAdd(dut, pa = 0, pb = 1, pdst = 2, robId = 2); cd.waitSampling()
      idle(dut); cd.waitSampling(4)
      dut.src.logic.obsIntAddr #= 2; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 123, s"R2 got ${dut.src.logic.obsIntData.toBigInt}")
    }
  }
}
