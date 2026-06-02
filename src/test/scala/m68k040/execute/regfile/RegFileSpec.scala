package m68k040.execute.regfile

import m68k040.M68kSim
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class RegFileSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rf    = new RegFilePlugin(RegfileSpec.Int)
    val probe = new RegFileProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](rf, probe)) }
  }

  test("write then read returns the written value") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      dut.probe.logic.w0v #= false; dut.probe.logic.w1v #= false; dut.probe.logic.b0v #= false
      dut.probe.logic.rd0Addr #= 0; dut.probe.logic.rd1Addr #= 0
      dut.probe.logic.w0a #= 0; dut.probe.logic.w0d #= 0; dut.probe.logic.w1a #= 0; dut.probe.logic.w1d #= 0
      dut.probe.logic.b0a #= 0; dut.probe.logic.b0d #= 0
      cd.waitSampling(80)   // wait out the init sweep (depth 48 + margin)
      dut.probe.logic.w0v #= true; dut.probe.logic.w0a #= 5; dut.probe.logic.w0d #= BigInt("DEADBEEF", 16)
      cd.waitSampling()
      dut.probe.logic.w0v #= false
      cd.waitSampling()
      dut.probe.logic.rd0Addr #= 5
      sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("DEADBEEF", 16),
        s"got 0x${dut.probe.logic.rd0Data.toBigInt.toString(16)}")
    }
  }

  test("reads return 0 after the init sweep") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      dut.probe.logic.w0v #= false; dut.probe.logic.w1v #= false; dut.probe.logic.b0v #= false
      dut.probe.logic.w0a #= 0; dut.probe.logic.w0d #= 0; dut.probe.logic.w1a #= 0; dut.probe.logic.w1d #= 0
      dut.probe.logic.b0a #= 0; dut.probe.logic.b0d #= 0; dut.probe.logic.rd1Addr #= 0
      cd.waitSampling(80)   // wait out the init sweep (depth 48 + margin)
      dut.probe.logic.rd0Addr #= 7
      sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == 0, s"got 0x${dut.probe.logic.rd0Data.toBigInt.toString(16)}")
    }
  }

  test("bypass overrides RF data on a matching address") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      dut.probe.logic.w0v #= false; dut.probe.logic.w1v #= false; dut.probe.logic.b0v #= false
      dut.probe.logic.w0a #= 0; dut.probe.logic.w0d #= 0; dut.probe.logic.w1a #= 0; dut.probe.logic.w1d #= 0
      dut.probe.logic.b0a #= 0; dut.probe.logic.b0d #= 0; dut.probe.logic.rd0Addr #= 0; dut.probe.logic.rd1Addr #= 0
      cd.waitSampling(80)
      dut.probe.logic.w0v #= true; dut.probe.logic.w0a #= 9; dut.probe.logic.w0d #= BigInt("11111111", 16)
      cd.waitSampling(); dut.probe.logic.w0v #= false; cd.waitSampling()
      dut.probe.logic.rd0Addr #= 9; sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("11111111", 16), "RF read")
      dut.probe.logic.b0v #= true; dut.probe.logic.b0a #= 9; dut.probe.logic.b0d #= BigInt("22222222", 16)
      sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("22222222", 16), "bypass should beat RF")
      dut.probe.logic.b0v #= false; sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("11111111", 16), "RF after bypass drops")
    }
  }

  test("same-key writes merge: higher priority wins a conflict") {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      dut.probe.logic.w0v #= false; dut.probe.logic.w1v #= false; dut.probe.logic.b0v #= false
      dut.probe.logic.w0a #= 0; dut.probe.logic.w0d #= 0; dut.probe.logic.w1a #= 0; dut.probe.logic.w1d #= 0
      dut.probe.logic.b0a #= 0; dut.probe.logic.b0d #= 0; dut.probe.logic.rd0Addr #= 0; dut.probe.logic.rd1Addr #= 0
      cd.waitSampling(80)
      // only wr1 (low prio) valid -> its value lands
      dut.probe.logic.w1v #= true; dut.probe.logic.w1a #= 3; dut.probe.logic.w1d #= BigInt("0000AAAA", 16)
      cd.waitSampling(); dut.probe.logic.w1v #= false; cd.waitSampling()
      dut.probe.logic.rd0Addr #= 3; sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("0000AAAA", 16), "low-prio write alone should land")
      // both valid same addr -> high prio (wr0) wins
      dut.probe.logic.w0v #= true; dut.probe.logic.w0a #= 4; dut.probe.logic.w0d #= BigInt("0000BBBB", 16)
      dut.probe.logic.w1v #= true; dut.probe.logic.w1a #= 4; dut.probe.logic.w1d #= BigInt("0000CCCC", 16)
      cd.waitSampling(); dut.probe.logic.w0v #= false; dut.probe.logic.w1v #= false; cd.waitSampling()
      dut.probe.logic.rd0Addr #= 4; sleep(1)
      assert(dut.probe.logic.rd0Data.toBigInt == BigInt("0000BBBB", 16), "high-prio write should win")
    }
  }
}
