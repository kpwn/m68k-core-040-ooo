package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** TRAP #n (0x4E4n): a decode-time UNCONDITIONAL faulted µop. The op is don't-care
  * (it only delivers at retire). faultVector = 32 + opword[3:0]. The stacked PC is
  * the NEXT instruction's PC (TRAP is not restartable) -> faultPc = nextPc = pc+2. */
class TrapDecodeSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }
  def drive(dut: Dut, op: Int, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= 0; dut.pkt.words(2) #= 0
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  test("TRAP #5 (0x4E45) -> faulted uop, vector 37, faultPc = pc+2", VerilatorTest) {
    run { dut => drive(dut, 0x4E45); sleep(1)
      assert(dut.uop.faulted.toBoolean, "TRAP #n must be a faulted uop")
      assert(dut.uop.faultVector.toInt == 37, s"vector must be 32+5=37, got ${dut.uop.faultVector.toInt}")
      assert(!dut.uop.unimplemented.toBoolean, "TRAP is implemented (not illegal)")
      assert(!dut.uop.dstValid.toBoolean && !dut.uop.srcAValid.toBoolean && !dut.uop.srcBValid.toBoolean)
      assert(!dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.isRte.toBoolean)
      assert(dut.uop.faultPc.toLong == 0x1002L, s"TRAP stacks NEXT PC (pc+2), got 0x${dut.uop.faultPc.toLong.toHexString}")
    }
  }
  test("TRAP #0 (0x4E40) -> vector 32", VerilatorTest) {
    run { dut => drive(dut, 0x4E40); sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 32)
    }
  }
  test("TRAP #15 (0x4E4F) -> vector 47", VerilatorTest) {
    run { dut => drive(dut, 0x4E4F); sleep(1)
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 47)
    }
  }
}
