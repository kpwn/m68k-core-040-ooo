package m68k040.execute.regfile

import m68k040.M68kSim
import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Standalone round-trip test for the new FP physical register file (Task 1 of the
  * FPU/FPSP plan). No renamer/decode/EU consumer exists yet, so -- exactly like
  * RegFileSpec.scala's own RegFileProbePlugin pattern for Int/Nzvc/X -- a small local
  * probe plugin calls `newRead`/`newWrite` during its own `during setup` phase (the
  * only place a RegfileService's ports may legally be acquired) and surfaces them as
  * plain IO for the testbench to drive/observe. */
class RegFilePluginFpSpec extends AnyFunSuite {
  class FpProbePlugin extends FiberPlugin {
    var rd0: RegFileReadPort = null
    var wr0: RegFileWritePort = null

    during setup {
      val rf = host[FpRegFileService]
      rd0 = rf.newRead()
      wr0 = rf.newWrite(latency = 1)
    }

    val logic = during build new Area {
      val rd0Addr = in UInt (rd0.addr.getWidth bits); rd0.addr := rd0Addr
      val rd0Data = out Bits (rd0.data.getWidth bits); rd0Data := rd0.data
      val w0v = in Bool(); val w0a = in UInt (wr0.address.getWidth bits); val w0d = in Bits (wr0.data.getWidth bits)
      wr0.valid := w0v; wr0.address := w0a; wr0.data := w0d
    }
  }

  class Dut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val rfFp  = new RegFilePluginFp
    val probe = new FpProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](rfFp, probe)) }
  }

  test("RegFilePluginFp: write then read round-trips an 80-bit value", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      dut.probe.logic.w0v #= false; dut.probe.logic.w0a #= 0; dut.probe.logic.w0d #= 0
      dut.probe.logic.rd0Addr #= 0
      cd.waitSampling(20) // wait out the depth-16 init-zero sweep, with margin

      val testAddr = 5
      val testVal  = BigInt("FF800000000000000001", 16) // 80 bits: sign=1,exp=all-1s (inf/nan pattern), mantissa low bit set

      dut.probe.logic.w0v #= true; dut.probe.logic.w0a #= testAddr; dut.probe.logic.w0d #= testVal
      cd.waitSampling()
      dut.probe.logic.w0v #= false
      cd.waitSampling()
      dut.probe.logic.rd0Addr #= testAddr
      sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == testVal,
        s"FP regfile addr $testAddr: wrote 0x${testVal.toString(16)}, read back 0x${dut.probe.logic.rd0Data.toBigInt.toString(16)}")
    }
  }

  test("RegFilePluginFp: spec width and depth are 80 bits x 16 entries") {
    assert(RegfileSpec.Fp.dataWidth == 80)
    assert(RegfileSpec.Fp.depth == 16)
    assert(RegfileSpec.Fp.addressWidth == 4) // log2Up(16)
  }
}
