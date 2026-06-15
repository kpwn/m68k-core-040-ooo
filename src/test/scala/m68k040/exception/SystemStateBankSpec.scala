package m68k040.exception

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import m68k040.VerilatorTest

class SystemStateHarness extends Component {
  val ss = new SystemState
  val io = new Bundle {
    val srSys = in UInt (8 bits)
    val isp   = in UInt (32 bits)
    val msp   = in UInt (32 bits)
    val usp   = in UInt (32 bits)
    val a7    = out UInt (32 bits)
  }
  ss.setSrSys.valid := True; ss.setSrSys.payload := io.srSys
  ss.setIsp.valid   := True; ss.setIsp.payload   := io.isp
  ss.setMsp.valid   := True; ss.setMsp.payload   := io.msp
  ss.setUsp.valid   := True; ss.setUsp.payload   := io.usp
  io.a7 := ss.a7
}

class SystemStateBankSpec extends AnyFunSuite {
  test("a7 banks USP/ISP/MSP by (S,M)", VerilatorTest) {
    SimConfig.compile(new SystemStateHarness).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.usp #= 0xAAAA0000L; dut.io.isp #= 0xBBBB0000L; dut.io.msp #= 0xCCCC0000L
      dut.io.srSys #= 0x00; dut.clockDomain.waitSampling(2); assert((dut.io.a7.toLong & 0xffffffffL) == 0xAAAA0000L) // S=0 -> USP
      dut.io.srSys #= 0x10; dut.clockDomain.waitSampling(2); assert((dut.io.a7.toLong & 0xffffffffL) == 0xAAAA0000L) // S=0,M=1 -> USP
      dut.io.srSys #= 0x20; dut.clockDomain.waitSampling(2); assert((dut.io.a7.toLong & 0xffffffffL) == 0xBBBB0000L) // S=1,M=0 -> ISP
      dut.io.srSys #= 0x30; dut.clockDomain.waitSampling(2); assert((dut.io.a7.toLong & 0xffffffffL) == 0xCCCC0000L) // S=1,M=1 -> MSP
    }
  }

  test("writeA7 routes to the (S,M)-selected bank, leaving others intact", VerilatorTest) {
    class WHarness extends Component {
      val ss = new SystemState
      val io = new Bundle {
        val srSys = in UInt (8 bits)
        val data  = in UInt (32 bits)
        val wr    = in Bool()
        val isp = out UInt (32 bits); val msp = out UInt (32 bits); val usp = out UInt (32 bits)
      }
      ss.setSrSys.valid := True; ss.setSrSys.payload := io.srSys
      ss.writeA7.valid := io.wr; ss.writeA7.payload := io.data
      io.isp := ss.isp; io.msp := ss.msp; io.usp := ss.usp
    }
    SimConfig.compile(new WHarness).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.wr #= false; dut.io.srSys #= 0x30; dut.clockDomain.waitSampling()
      dut.io.data #= 0x12340000L; dut.io.wr #= true; dut.clockDomain.waitSampling(); dut.io.wr #= false
      dut.clockDomain.waitSampling()
      assert((dut.io.msp.toLong & 0xffffffffL) == 0x12340000L) // M=1 -> MSP
      assert((dut.io.isp.toLong & 0xffffffffL) == 0L)
      assert((dut.io.usp.toLong & 0xffffffffL) == 0L)
    }
  }
}
