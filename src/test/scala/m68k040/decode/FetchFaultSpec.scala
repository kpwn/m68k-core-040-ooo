package m68k040.decode

import m68k040.{M68kParams, VerilatorTest}
import m68k040.frontend.DecodePacket
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

/** Task 3: a DecodePacket with `fault` (the I-cache raised it from the ITLB) decodes
  * into a single faulted µop: vector 2 (access fault), faultAddr = the fetch PC,
  * sswInstr = 1 (program-space SSW). The op is don't-care (it only delivers the
  * exception at retire). A non-faulting packet is UNCHANGED. */
class FetchFaultSpec extends AnyFunSuite {

  class Dut extends Component {
    val pktIn = in(DecodePacket())
    val outFaulted = out(Bool())
    val outVec     = out(UInt(8 bits))
    val outAddr    = out(UInt(32 bits))
    val outSsw     = out(Bool())
    val outCount   = out(UInt(2 bits))
    val a = MicroOpAssembler.assemble(pktIn)
    outFaulted := a.uops(0).faulted
    outVec     := a.uops(0).faultVector
    outAddr    := a.uops(0).faultAddr
    outSsw     := a.uops(0).sswInstr
    outCount   := a.count
  }

  test("DecodePacket.fault -> faulted vector-2 uop (faultAddr=PC, sswInstr)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      // A faulting fetch: bytes are garbage; only `fault` + pc matter.
      dut.pktIn.valid     #= true
      dut.pktIn.fault     #= true
      dut.pktIn.pc        #= 0x40801234L
      dut.pktIn.wordCount #= 1
      dut.pktIn.lenWords  #= 1
      dut.pktIn.simple    #= true
      dut.pktIn.complex   #= false
      for (i <- 0 until 5) dut.pktIn.words(i) #= 0x4afc   // illegal-pattern bytes (don't-care)
      sleep(1)
      assert(dut.outFaulted.toBoolean, "a faulting fetch must produce a faulted uop")
      assert(dut.outVec.toInt == 2, s"vector must be 2 (access fault), got ${dut.outVec.toInt}")
      assert(dut.outAddr.toLong == 0x40801234L, f"faultAddr must be the fetch PC, got 0x${dut.outAddr.toLong}%x")
      assert(dut.outSsw.toBoolean, "sswInstr must be set for an instruction-fetch fault")
      assert(dut.outCount.toInt == 1, s"a fetch fault emits a single uop, got ${dut.outCount.toInt}")
    }
  }

  test("no fetch fault -> a normal MOVEQ uop is unchanged", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      // MOVEQ #1,%d0 = 0x7001, no fault.
      dut.pktIn.valid     #= true
      dut.pktIn.fault     #= false
      dut.pktIn.pc        #= 0x8000L
      dut.pktIn.wordCount #= 1
      dut.pktIn.lenWords  #= 1
      dut.pktIn.simple    #= true
      dut.pktIn.complex   #= false
      dut.pktIn.words(0)  #= 0x7001
      for (i <- 1 until 5) dut.pktIn.words(i) #= 0
      sleep(1)
      assert(!dut.outFaulted.toBoolean, "a non-faulting MOVEQ must not be faulted")
      assert(dut.outCount.toInt == 1, "MOVEQ is a single uop")
    }
  }
}
