package m68k040.exception

import m68k040.{M68kSim}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Task 1: committed architectural system state — SR system byte (S/I/T), VBR,
  * USP/SSP with A7 banking by committed S.
  *
  * SystemState is a small committed-register block:
  *  - srSys: the SR system byte (high 8 bits of SR; S=bit13, I2..0=bits10..8,
  *    T1/T0=bits15/14). Reset = 0x27 (S=1, I=7, T=0) to match Musashi boot SR.
  *  - vbr: vector base register (reset 0).
  *  - usp / ssp: the two A7 banks (reset: only ssp is the boot SP).
  *  - a7Out: the architectural A7 value, muxed by committed S (S ? ssp : usp).
  *
  * Write ports (driven by the exception FSM / RTE / privileged moves at commit):
  *  - setSrSys/setVbr/setUsp/setSsp: simple committed-register writes.
  *  - writeA7: writes the BANK selected by committed S (so a handler's pushes to
  *    A7 update SSP while in supervisor; user pushes update USP).
  */
class SystemStateSpec extends AnyFunSuite {

  class Dut extends Component {
    val ss = new SystemState
    // expose IO
    val srSysOut = out UInt (8 bits)
    val vbrOut   = out UInt (32 bits)
    val uspOut   = out UInt (32 bits)
    val sspOut   = out UInt (32 bits)
    val a7Out    = out UInt (32 bits)
    srSysOut := ss.srSys
    vbrOut   := ss.vbr
    uspOut   := ss.usp
    sspOut   := ss.ssp
    a7Out    := ss.a7

    // drive control inputs (default idle)
    val setSrSysV = in Bool (); val setSrSysD = in UInt (8 bits)
    val setVbrV   = in Bool (); val setVbrD   = in UInt (32 bits)
    val setUspV   = in Bool (); val setUspD   = in UInt (32 bits)
    val setSspV   = in Bool (); val setSspD   = in UInt (32 bits)
    val writeA7V  = in Bool (); val writeA7D  = in UInt (32 bits)
    ss.setSrSys.valid := setSrSysV; ss.setSrSys.payload := setSrSysD
    ss.setVbr.valid   := setVbrV;   ss.setVbr.payload   := setVbrD
    ss.setUsp.valid   := setUspV;   ss.setUsp.payload   := setUspD
    ss.setSsp.valid   := setSspV;   ss.setSsp.payload   := setSspD
    ss.writeA7.valid  := writeA7V;  ss.writeA7.payload  := writeA7D
  }

  def idle(dut: Dut): Unit = {
    dut.setSrSysV #= false; dut.setSrSysD #= 0
    dut.setVbrV #= false; dut.setVbrD #= 0
    dut.setUspV #= false; dut.setUspD #= 0
    dut.setSspV #= false; dut.setSspD #= 0
    dut.writeA7V #= false; dut.writeA7D #= 0
  }

  test("reset state: srSys=0x27, vbr=0, A7 selects SSP (S=1)") {
    M68kSim().compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      idle(dut)
      // initialize banks: ssp=0x100000, usp=0xDEAD
      dut.setSspV #= true; dut.setSspD #= 0x00100000L
      dut.setUspV #= true; dut.setUspD #= 0x0000DEADL
      dut.clockDomain.waitSampling()
      idle(dut)
      dut.clockDomain.waitSampling()
      assert((dut.srSysOut.toInt & 0xff) == 0x27, f"srSys=0x${dut.srSysOut.toInt}%02x")
      assert(dut.vbrOut.toLong == 0)
      // S=1 (bit13 of SR => bit5 of the 8-bit system byte) at reset -> A7 == SSP
      assert(dut.a7Out.toLong == 0x00100000L, f"a7=0x${dut.a7Out.toLong}%x")
    }
  }

  test("A7 reads USP when S=0, SSP when S=1") {
    M68kSim().compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      idle(dut)
      dut.setSspV #= true; dut.setSspD #= 0x00100000L
      dut.setUspV #= true; dut.setUspD #= 0x0000DEADL
      dut.clockDomain.waitSampling()
      idle(dut)
      dut.clockDomain.waitSampling()
      // S=1 -> A7 == SSP
      assert(dut.a7Out.toLong == 0x00100000L)
      // clear S (system byte bit5 = S). srSys 0x07 = S=0, I=7
      dut.setSrSysV #= true; dut.setSrSysD #= 0x07
      dut.clockDomain.waitSampling()
      idle(dut)
      dut.clockDomain.waitSampling()
      assert((dut.srSysOut.toInt & 0xff) == 0x07)
      assert(dut.a7Out.toLong == 0x0000DEADL, f"a7=0x${dut.a7Out.toLong}%x (expected USP)")
      // set S again -> A7 back to SSP
      dut.setSrSysV #= true; dut.setSrSysD #= 0x27
      dut.clockDomain.waitSampling()
      idle(dut)
      dut.clockDomain.waitSampling()
      assert(dut.a7Out.toLong == 0x00100000L)
    }
  }

  test("writeA7 updates the bank selected by committed S") {
    M68kSim().compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      idle(dut)
      dut.setSspV #= true; dut.setSspD #= 0x00100000L
      dut.setUspV #= true; dut.setUspD #= 0x0000DEADL
      dut.clockDomain.waitSampling()
      idle(dut)
      dut.clockDomain.waitSampling()
      // S=1: writeA7 -> updates SSP
      dut.writeA7V #= true; dut.writeA7D #= 0x00200000L
      dut.clockDomain.waitSampling()
      idle(dut)
      dut.clockDomain.waitSampling()
      assert(dut.sspOut.toLong == 0x00200000L, f"ssp=0x${dut.sspOut.toLong}%x")
      assert(dut.uspOut.toLong == 0x0000DEADL)
      assert(dut.a7Out.toLong == 0x00200000L)
      // clear S, writeA7 -> updates USP
      dut.setSrSysV #= true; dut.setSrSysD #= 0x07
      dut.clockDomain.waitSampling()
      idle(dut)
      dut.writeA7V #= true; dut.writeA7D #= 0x00077777L
      dut.clockDomain.waitSampling()
      idle(dut)
      dut.clockDomain.waitSampling()
      assert(dut.uspOut.toLong == 0x00077777L, f"usp=0x${dut.uspOut.toLong}%x")
      assert(dut.sspOut.toLong == 0x00200000L)
      assert(dut.a7Out.toLong == 0x00077777L)
    }
  }

  test("VBR and SR system-byte read/write") {
    M68kSim().compile(new Dut).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      idle(dut)
      dut.setVbrV #= true; dut.setVbrD #= 0x00012000L
      dut.clockDomain.waitSampling()
      idle(dut)
      dut.clockDomain.waitSampling()
      assert(dut.vbrOut.toLong == 0x00012000L)
      dut.setSrSysV #= true; dut.setSrSysD #= 0x20  // S=1, I=0, T=0
      dut.clockDomain.waitSampling()
      idle(dut)
      dut.clockDomain.waitSampling()
      assert((dut.srSysOut.toInt & 0xff) == 0x20)
    }
  }
}
