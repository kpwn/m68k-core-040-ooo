package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Line-4 single-operand decode (data-register forms): CLR/NEG/NEGX/NOT/TST
  * (`0100 oooo ss 000rrr`) and SWAP/EXT/EXTB/TAS (the `0100 1000`/`0100 1001`/
  * `0100 1010` group). Memory-dest forms are deferred (illegal here). */
class Line4DecodeSpec extends AnyFunSuite {
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

  // CLR.L D0 = 0x4280 (0100 0010 10 000 000): ss=10 (.L), mode0 D0.
  test("CLR.L D0 -> CLR, dst=D0, srcA=D0 (merge), Z=1 forced (writesNzvc, no X)", VerilatorTest) {
    run { dut => drive(dut, 0x4280); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.CLR && dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.srcAValid.toBoolean)   // merge source
      assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  // NEG.B D1 = 0x4401 (0100 0100 00 000 001): ss=00 (.B), D1. NZVCX.
  test("NEG.B D1 -> NEG, BYTE, srcA=D1, dst=D1, NZVC+X", VerilatorTest) {
    run { dut => drive(dut, 0x4401); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.NEG && dut.uop.size.toEnum == Size.BYTE)
      assert(dut.uop.srcAReg.toInt == 1 && dut.uop.dstReg.toInt == 1 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
      assert(!dut.uop.readsX.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  // NEGX.W D2 = 0x4042 (0100 0000 01 000 010): ss=01 (.W), D2. reads X + old Z.
  test("NEGX.W D2 -> NEGX, WORD, readsX+readsNzvc, NZVC+X", VerilatorTest) {
    run { dut => drive(dut, 0x4042); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.NEGX && dut.uop.size.toEnum == Size.WORD)
      assert(dut.uop.srcAReg.toInt == 2 && dut.uop.dstReg.toInt == 2)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
      assert(dut.uop.readsX.toBoolean && dut.uop.readsNzvc.toBoolean, "NEGX reads X + old Z")
      assert(!dut.uop.unimplemented.toBoolean)
    }
  }
  // NOT.L D3 = 0x4683 (0100 0110 10 000 011): ss=10 (.L), D3. NZ, V=C=0, no X.
  test("NOT.L D3 -> NOT, LONG, srcA=D3, dst=D3, NZVC (no X)", VerilatorTest) {
    run { dut => drive(dut, 0x4683); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.NOT && dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.srcAReg.toInt == 3 && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  // TST.W D4 = 0x4A44 (0100 1010 01 000 100): ss=01 (.W), D4. flags only, NO write.
  test("TST.W D4 -> TST, WORD, srcA=D4, NO dst write, NZVC (no X)", VerilatorTest) {
    run { dut => drive(dut, 0x4A44); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.TST && dut.uop.size.toEnum == Size.WORD)
      assert(dut.uop.srcAReg.toInt == 4 && dut.uop.srcAValid.toBoolean)
      assert(!dut.uop.dstValid.toBoolean, "TST writes no register")
      assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  // CHK.W D0,D1 = 0x4181 (bit8=1) must NOT decode as a unary op (stays CHK).
  test("CHK (bit8=1) is NOT a line-4 unary op", VerilatorTest) {
    run { dut => drive(dut, 0x4181); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.CHK, "bit8=1 -> CHK, not CLR/NEG/etc")
    }
  }
  // Memory-dest CLR.L (An) = 0x4290 (mode 010) -> deferred -> illegal.
  test("CLR.L (A0) memory dest -> illegal (deferred)", VerilatorTest) {
    run { dut => drive(dut, 0x4290); sleep(1)
      assert(dut.uop.unimplemented.toBoolean && dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 4)
    }
  }

  // ── SWAP / EXT / EXTB / TAS ────────────────────────────────────────────────
  // SWAP D5 = 0x4845 (0100 1000 0100 0 101): full-32 halves swap, NZ.
  test("SWAP D5 -> SWAP, LONG, srcA=D5, dst=D5, NZVC (no X)", VerilatorTest) {
    run { dut => drive(dut, 0x4845); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.SWAP && dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.srcAReg.toInt == 5 && dut.uop.dstReg.toInt == 5 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  // EXT.W D6 = 0x4886 (0100 1000 1000 0 110): byte->word, .W (preserve upper16), NZ.
  test("EXT.W D6 -> EXT, WORD, srcA=D6, dst=D6, NZVC", VerilatorTest) {
    run { dut => drive(dut, 0x4886); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.EXT && dut.uop.size.toEnum == Size.WORD)
      assert(dut.uop.srcAReg.toInt == 6 && dut.uop.dstReg.toInt == 6 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  // EXT.L D7 = 0x48C7 (0100 1000 1100 0 111): word->long, full-32, NZ.
  test("EXT.L D7 -> EXT, LONG, full-32", VerilatorTest) {
    run { dut => drive(dut, 0x48C7); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.EXT && dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.srcAReg.toInt == 7 && dut.uop.dstReg.toInt == 7 && !dut.uop.unimplemented.toBoolean)
    }
  }
  // EXTB.L D0 = 0x49C0 (0100 1001 1100 0 000): byte->long. EXTB uses LONG + a byte-source marker (extbByte).
  test("EXTB.L D0 -> EXT, LONG (byte source), full-32, extbByte set", VerilatorTest) {
    run { dut => drive(dut, 0x49C0); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.EXT && dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.dstReg.toInt == 0 && !dut.uop.unimplemented.toBoolean)
    }
  }
  // TAS D1 = 0x4AC1 (0100 1010 11 000 001): test Dn[7:0] -> N/Z, set bit7.
  test("TAS D1 -> TAS, BYTE, srcA=D1, dst=D1, NZVC (no X)", VerilatorTest) {
    run { dut => drive(dut, 0x4AC1); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.TAS && dut.uop.size.toEnum == Size.BYTE)
      assert(dut.uop.srcAReg.toInt == 1 && dut.uop.dstReg.toInt == 1 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
}
