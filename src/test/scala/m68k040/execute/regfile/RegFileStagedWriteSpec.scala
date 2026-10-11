package m68k040.execute.regfile

import m68k040.M68kSim
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** CPU_BE_RETIME `newStagedWrite`: a STAGED (cold) member of a shared write port is
  * registered one cycle before the group's priority mux, so its write lands one cycle
  * after the request; the hot member of the same group is unaffected. */
class RegFileStagedWriteSpec extends AnyFunSuite {
  class Probe extends FiberPlugin {
    var rd: RegFileReadPort = null
    var hot: RegFileWritePort = null
    var cold: RegFileWritePort = null
    during setup {
      val rf = host[IntRegFileService]
      rd = rf.newRead(forceNoBypass = true)
      val key = new Object
      hot  = rf.newWrite(latency = 1, sharingKey = key, priority = 1)
      cold = rf.newStagedWrite(sharingKey = key, priority = 0)
    }
    val logic = during build new Area {
      val rdAddr = in UInt (rd.addr.getWidth bits); rd.addr := rdAddr
      val rdData = out Bits (rd.data.getWidth bits); rdData := rd.data
      val hv = in Bool(); val ha = in UInt (hot.address.getWidth bits); val hd = in Bits (32 bits)
      hot.valid := hv; hot.address := ha; hot.data := hd
      val cv = in Bool(); val ca = in UInt (cold.address.getWidth bits); val cd = in Bits (32 bits)
      cold.valid := cv; cold.address := ca; cold.data := cd
    }
  }
  class Dut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val rfInt = new RegFilePluginInt
    val probe = new Probe
    db.on { host.asHostOf(Seq[FiberPlugin](rfInt, probe)) }
  }

  test("a staged write lands one cycle after its request; the hot writer is unchanged") {
    M68kSim().compile(new Dut).doSim { dut =>
      val p = dut.probe.logic
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      p.hv #= false; p.cv #= false; p.ha #= 0; p.hd #= 0; p.ca #= 0; p.cd #= 0; p.rdAddr #= 0
      cd.waitSampling(80)                       // init sweep
      def rdAt(a: Int): BigInt = { p.rdAddr #= a; sleep(1); p.rdData.toBigInt }
      // cycle t: staged request to r7
      p.cv #= true; p.ca #= 7; p.cd #= BigInt("CAFEF00D", 16)
      cd.waitSampling(); p.cv #= false
      // t+1: not landed yet (the registered request writes on THIS edge)
      assert(rdAt(7) == 0, "staged write must not be visible one cycle after its request edge")
      // a hot write in the cycle AFTER the staged landing (different address)
      cd.waitSampling()
      assert(rdAt(7) == BigInt("CAFEF00D", 16), "staged write must land one cycle late")
      p.hv #= true; p.ha #= 9; p.hd #= BigInt("12345678", 16)
      cd.waitSampling(); p.hv #= false
      assert(rdAt(9) == BigInt("12345678", 16), "hot write lands on its own edge")
      assert(rdAt(7) == BigInt("CAFEF00D", 16), "staged value preserved")
      // back-to-back staged requests keep their order
      p.cv #= true; p.ca #= 7; p.cd #= 1; cd.waitSampling()
      p.ca #= 7; p.cd #= 2; cd.waitSampling(); p.cv #= false
      cd.waitSampling()
      assert(rdAt(7) == 2, "the later staged request wins")
    }
  }
}
