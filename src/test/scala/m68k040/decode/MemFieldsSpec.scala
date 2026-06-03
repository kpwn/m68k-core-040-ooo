package m68k040.decode
import m68k040.VerilatorTest
import m68k040.isa.MemOp
import m68k040.frontend.DecodePacket
import spinal.core._; import spinal.core.sim._; import org.scalatest.funsuite.AnyFunSuite
class MemFieldsSpec extends AnyFunSuite {
  class Dut extends Component { val pkt = in(DecodePacket()); val uop = out(DecodedUop()); uop := MicroOpAssembler.assemble(pkt).uops(0) }
  test("non-memory decode sets memOp=NONE", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.pkt.valid #= true; dut.pkt.pc #= 0; dut.pkt.simple #= true; dut.pkt.complex #= false
      dut.pkt.lenWords #= 1; dut.pkt.wordCount #= 1; dut.pkt.fault #= false
      dut.pkt.words(0) #= 0x7605; (1 to 4).foreach(i => dut.pkt.words(i) #= 0); sleep(1)
      assert(dut.uop.memOp.toEnum == MemOp.NONE)
    }
  }
}
