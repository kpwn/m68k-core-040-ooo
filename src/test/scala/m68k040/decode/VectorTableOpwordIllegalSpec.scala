package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** The two opwords a Quadra 700 vector table presents when control transfers INTO it.
  *
  * A hardware capture halted the CPU at `0x0000002e` with VBR = 0 — two bytes into the
  * vector-11 slot, whose contents are the ROM FPSP entry `0x4088d9fe`. Executing the
  * slot as code means decoding `0x4088` (its high half) and then `0xd9fe` (its low
  * half). Both must be ILLEGAL on a 68040, and the exact landing PC depends on it:
  *
  *   0x4088 = 0100 0000 10 001 000 = NEGX.L A0. The line-4 unary family (CLR/NEG/NEGX/
  *            NOT/TST) needs an ALTERABLE destination, and address-register direct
  *            (mode 001) is not one — only TST.W/TST.L accept An (68020+), because TST
  *            never writes back. If our decoder accepted NEGX.L A0 it would EXECUTE the
  *            slot's high half and advance to 0x2e, which is precisely the observed
  *            +2 offset; the illegal trap must instead be raised at 0x2c.
  *   0xd9fe = 1101 100 111 111 110 = ADDA.L (xxx),A4 with EA mode 7 / reg 6 — an
  *            encoding that does not exist (mode-7 register selects only 0..4).
  *
  * Both are driven here with the MOST PERMISSIVE framing the frontend could hand the
  * assembler (`simple`, one word), so the verdict comes from the assembler's own EA
  * legality gates rather than from a predecode length refusal — i.e. the test cannot
  * pass for the wrong reason. */
class VectorTableOpwordIllegalSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val a   = MicroOpAssembler.assemble(pkt)
    val count = out(UInt(2 bits)); count := a.count
    val uop = out(DecodedUop()); uop := a.uops(0)
  }

  private lazy val compiled = SimConfig.withVerilator.compile(new Dut)

  def drive(dut: Dut, op: Int, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2c; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    for (i <- 0 until 5) dut.pkt.words(i) #= 0
    dut.pkt.words(0) #= op
  }

  def expectIllegal(op: Int, vector: Int, what: String): Unit =
    compiled.doSim(f"op$op%04x") { dut =>
      drive(dut, op)
      sleep(1)
      assert(dut.uop.unimplemented.toBoolean, f"$what (0x$op%04x) decoded as a LEGAL instruction")
      assert(dut.uop.faulted.toBoolean, f"$what (0x$op%04x) did not raise a decode fault")
      assert(dut.uop.op.toEnum == DecOp.ILLEGAL, f"$what (0x$op%04x) kept a live ALU op")
      assert(dut.uop.faultVector.toInt == vector,
        f"$what (0x$op%04x) raised vector ${dut.uop.faultVector.toInt}, expected $vector")
      assert(!dut.uop.dstValid.toBoolean, f"$what (0x$op%04x) would still write a register")
    }

  test("NEGX.L A0 (0x4088, the high half of the vector-11 slot) is ILLEGAL, vector 4", VerilatorTest) {
    expectIllegal(0x4088, 4, "NEGX.L A0")
  }

  // The rest of the line-4 unary family against An-direct, for the same reason. TST is
  // deliberately absent: TST.W/TST.L An IS legal on the 68020+ and has its own coverage
  // in Line4DecodeSpec.
  test("the line-4 unary family rejects address-register-direct destinations", VerilatorTest) {
    // 0x40c8.. is ss=11 (not a unary size); walk oooo in {0,2,4,6} at ss=.L, mode 001.
    for ((op, name) <- Seq(0x4088 -> "NEGX.L A0", 0x4288 -> "CLR.L A0",
                           0x4488 -> "NEG.L A0", 0x4688 -> "NOT.L A0"))
      expectIllegal(op, 4, name)
  }

  test("0xd9fe (the low half of the vector-11 slot) is ILLEGAL, vector 4", VerilatorTest) {
    expectIllegal(0xd9fe, 4, "ADDA.L with mode-7 reg 6")
  }
}
