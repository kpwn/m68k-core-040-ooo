package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** DBcc decode: `0101 cccc 11 001 rrr` + disp16 -> a branch-EU µop that conditionally
  * decrements Dn.W and branches to pc+2+disp. isDbcc, reads NZVC (cond) + Dn (psrcA),
  * writes Dn (pdst), imm = sign-extended disp16 (the shared branch-displacement slot). NO flags. len = 2 words. */
class DbccDecodeSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }
  def drive(dut: Dut, op: Int, disp: Int): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= 2; dut.pkt.wordCount #= 2; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= disp & 0xffff; dut.pkt.words(2) #= 0
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // DBRA D0,. = 0x51C8 (0101 0001 11 001 000): cccc=0001 (F=DBRA/DBF), mode 001, D0.
  test("DBRA D0 -> isDbcc, cond=1(F), reads NZVC, reads+writes D0, disp16, no flags", VerilatorTest) {
    run { dut => drive(dut, 0x51C8, -2); sleep(1)
      assert(dut.uop.isDbcc.toBoolean, "DBcc must set isDbcc")
      assert(dut.uop.isBranch.toBoolean, "DBcc routes to the branch EU")
      assert(dut.uop.cond.toInt == 0x1, "DBRA/DBF cond = F (1)")
      assert(dut.uop.readsNzvc.toBoolean)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.srcAValid.toBoolean, "reads D0 (counter)")
      assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean, "writes D0 (decremented)")
      assert(!dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean, "DBcc sets NO flags")
      assert((dut.uop.imm.toLong & 0xffffffffL) == 0xfffffffeL, "disp16 sign-extended (-2)")
      assert(!dut.uop.unimplemented.toBoolean && !dut.uop.isScc.toBoolean && !dut.uop.ibranch.toBoolean)
    }
  }
  // DBEQ D6,. = 0x57CE (cccc=0111 EQ), disp = +0x10.
  test("DBEQ D6 -> cond=7 (EQ), positive disp", VerilatorTest) {
    run { dut => drive(dut, 0x57CE, 0x0010); sleep(1)
      assert(dut.uop.isDbcc.toBoolean && dut.uop.cond.toInt == 0x7 && dut.uop.dstReg.toInt == 6)
      assert((dut.uop.imm.toLong & 0xffffffffL) == 0x10L)
    }
  }
  // DBNE D3,. = 0x56CB (cccc=0110 NE).
  test("DBNE D3 -> cond=6 (NE), D3", VerilatorTest) {
    run { dut => drive(dut, 0x56CB, -8); sleep(1)
      assert(dut.uop.isDbcc.toBoolean && dut.uop.cond.toInt == 0x6 && dut.uop.dstReg.toInt == 3)
      assert((dut.uop.imm.toLong & 0xffffffffL) == 0xfffffff8L)
    }
  }
}
