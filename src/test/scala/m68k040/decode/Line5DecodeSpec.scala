package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Line-5 decode: ADDQ/SUBQ (`0101 ddd q ss mmmrrr`) to Dn (NZVCX, size-merged) and
  * to An (full-32, NO flags). Scc/DBcc decode is exercised in SccSpec / DbccSpec. */
class Line5DecodeSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }
  def drive(dut: Dut, op: Int, w1: Int = 0, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= 0
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // ADDQ.L #1,D0 = 0x5280 (0101 001 0 10 000 000): ddd=1, q=0 (ADD), ss=10 (.L), Dn=D0.
  test("ADDQ.L #1,D0 -> ADD, srcA=D0(dst read), imm=1, dst=D0, NZVC+X", VerilatorTest) {
    run { dut => drive(dut, 0x5280); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.ADD && dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.srcAValid.toBoolean)     // dest read (merge/operand)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 1)
      assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  // ADDQ.W #8,D3 = 0x5043 (0101 000 0 01 000 011): ddd=0 -> 8, ss=01 (.W), Dn=D3.
  test("ADDQ.W #8,D3 -> imm=8 (ddd=0 maps to 8), WORD, D3, NZVC+X", VerilatorTest) {
    run { dut => drive(dut, 0x5043); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.ADD && dut.uop.size.toEnum == Size.WORD)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 8)            // ddd=0 -> 8
      assert(dut.uop.srcAReg.toInt == 3 && dut.uop.dstReg.toInt == 3)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
    }
  }
  // SUBQ.B #3,D1 = 0x5701 (0101 011 1 00 000 001): ddd=3, q=1 (SUB), ss=00 (.B), Dn=D1.
  test("SUBQ.B #3,D1 -> SUB, imm=3, BYTE, D1, NZVC+X", VerilatorTest) {
    run { dut => drive(dut, 0x5701); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.SUB && dut.uop.size.toEnum == Size.BYTE)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 3)
      assert(dut.uop.srcAReg.toInt == 1 && dut.uop.dstReg.toInt == 1)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
    }
  }
  // ADDQ.W #1,A0 = 0x5248 (0101 001 0 01 001 000): ddd=1, An dest (mode 001). An =
  // full-32, NO flags. Force size LONG (full-32 add) + clear writesNzvc/writesX.
  test("ADDQ.W #1,A0 -> An dest: full-32 (size LONG), NO flags", VerilatorTest) {
    run { dut => drive(dut, 0x5248); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.ADD)
      assert(dut.uop.size.toEnum == Size.LONG, "An dest is full-32 -> size LONG")
      assert(dut.uop.srcAReg.toInt == 8 && dut.uop.dstReg.toInt == 8 && dut.uop.dstValid.toBoolean)  // A0 = reg 8
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 1)
      assert(!dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean, "An ADDQ sets NO flags")
      assert(!dut.uop.unimplemented.toBoolean)
    }
  }
  // SUBQ.L #2,A5 = 0x558D (0101 010 1 10 001 101): ddd=2, q=1 (SUB), ss=10, An=A5.
  test("SUBQ.L #2,A5 -> An dest: SUB full-32, NO flags", VerilatorTest) {
    run { dut => drive(dut, 0x558D); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.SUB && dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.srcAReg.toInt == 13 && dut.uop.dstReg.toInt == 13)      // A5 = reg 13
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 2)
      assert(!dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean)
    }
  }
}
