package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Size, MemOp}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class MicroOpAssemblerSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }
  def drive(dut: Dut, op: Int, w1: Int = 0, w2: Int = 0, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  test("MOVEQ #5,D3", VerilatorTest) { run { dut => drive(dut, 0x7605); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 5 && dut.uop.size.toEnum == Size.LONG)
    assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
  }}
  test("MOVE.W D0,D1 (src in srcB; srcA=dst Dn for partial merge, writesNzvc)", VerilatorTest) { run { dut => drive(dut, 0x3200); sleep(1)
    // MOVE.W to a DATA register is a PARTIAL-register write (preserve D1[31:16]). The
    // assembler makes it READ its destination Dn (D1) as srcA so the ALU EU's .B/.W
    // size-merge has the old value as the merge source.
    assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.srcBReg.toInt == 0 && dut.uop.srcBValid.toBoolean)
    assert(dut.uop.srcAValid.toBoolean && dut.uop.srcAReg.toInt == 1)   // srcA = dst D1 (merge source)
    assert(!dut.uop.isMovea.toBoolean && dut.uop.dstReg.toInt == 1 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.writesNzvc.toBoolean)
  }}
  test("MOVEA.L A0,A1 (src in srcB, no flags, isMovea)", VerilatorTest) { run { dut => drive(dut, 0x2248); sleep(1)
    assert(dut.uop.srcBReg.toInt == 8 && dut.uop.srcBValid.toBoolean)
    assert(dut.uop.dstReg.toInt == 9 && dut.uop.dstValid.toBoolean && !dut.uop.writesNzvc.toBoolean)
    // An-dst MOVE -> isMovea (full-32 write, no partial merge; .W sign-extends). No
    // srcA dst-read merge source (An is never partially written).
    assert(dut.uop.isMovea.toBoolean && !dut.uop.srcAValid.toBoolean)
  }}
  test("MOVE.L #imm,D0", VerilatorTest) { run { dut => drive(dut, 0x203C, 0x1234, 0x5678, len = 3); sleep(1)
    assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 0x12345678L && dut.uop.dstReg.toInt == 0)
    assert(dut.uop.writesNzvc.toBoolean)
  }}
  test("ADD.L D1,D0 (srcA=Dn dest, srcB=EA, NZVC+X)", VerilatorTest) { run { dut => drive(dut, 0xD081); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.ADD && dut.uop.srcAReg.toInt == 0 && dut.uop.srcBReg.toInt == 1)
    assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
  }}
  test("CMP.L D1,D0 (no write, NZVC)", VerilatorTest) { run { dut => drive(dut, 0xB081); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.CMP && !dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean)
  }}
  test("Bcc word disp", VerilatorTest) { run { dut => drive(dut, 0x6700, 0x0010, len = 2); sleep(1)
    assert(dut.uop.isBranch.toBoolean && dut.uop.cond.toInt == 7 && dut.uop.branchDisp.toLong == 0x10)
  }}
  test("memSimple-EA source -> cracked load uop (slot0), not unimplemented", VerilatorTest) { run { dut => drive(dut, 0xD090); sleep(1)
    // ADD.L (A0),D0 — EA (A0) is memSimple -> slot0 is now the LOAD µop (cracking
    // slice 2), no longer unimplemented. (Full sequence covered by CrackLoadSpec.)
    assert(!dut.uop.unimplemented.toBoolean && dut.uop.memOp.toEnum == MemOp.LOAD)
  }}
  // EOR.L D1,D2 (0xB382): reg dest. srcA = EA reg (D2 = dst operand), srcB = Dn (D1),
  // dst = EA reg (D2). Writes the reg + NZVC, no X.
  test("EOR.L D1,D2 (reg dest): srcA=D2, srcB=D1, dst=D2, NZVC no X", VerilatorTest) { run { dut => drive(dut, 0xB382); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.EOR && !dut.uop.unimplemented.toBoolean)
    assert(dut.uop.srcAReg.toInt == 2 && dut.uop.srcAValid.toBoolean)
    assert(dut.uop.srcBReg.toInt == 1 && dut.uop.srcBValid.toBoolean)
    assert(dut.uop.dstReg.toInt == 2 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.useImm.toBoolean)
  }}
  // EOR.B D2,(A0) (0xB510): memory destination = the RMW form -> cracks into a leading
  // LOAD ([load -> T0][EOR -> T1][store T1]). uops(0) is the load (full triple in
  // MemRmwDecodeSpec). An-direct / MEMCOMPLEX dest still illegal.
  test("EOR.B D2,(A0) (mem dest) -> leading LOAD (RMW crack)", VerilatorTest) { run { dut => drive(dut, 0xB510); sleep(1)
    assert(!dut.uop.unimplemented.toBoolean && dut.uop.memOp.toEnum == MemOp.LOAD)
  }}
  // EOR.B D2,A0 (0xB508, An-direct): not a valid EOR EA (CMPM region) -> illegal.
  test("EOR.B D2,A0 (An-direct) -> unimplemented", VerilatorTest) { run { dut => drive(dut, 0xB508); sleep(1)
    assert(dut.uop.unimplemented.toBoolean)
  }}
  // Line-0 immediates: srcA = EA reg (Dn dst operand), srcB = the trailing imm
  // word(s) via useImm, dst = EA reg. ADDI.L #imm,D0 (0x0680) + imm32 = words(1..2).
  test("ADDI.L #0x12345678,D0 (reg dest): srcA=D0, useImm imm32, dst=D0, NZVCX", VerilatorTest) {
    run { dut => drive(dut, 0x0680, 0x1234, 0x5678, len = 3); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.ADD && !dut.uop.unimplemented.toBoolean)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.srcAValid.toBoolean)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 0x12345678L && !dut.uop.srcBValid.toBoolean)
      assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean && dut.uop.size.toEnum == Size.LONG)
    }
  }
  // ANDI.W #0xABCD,D3 (0x0243) + imm word = words(1). .W -> 1 imm word.
  test("ANDI.W #0xABCD,D3 (reg dest): useImm low word, dst=D3, NZ no X", VerilatorTest) {
    run { dut => drive(dut, 0x0243, 0xABCD, len = 2); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.AND && !dut.uop.unimplemented.toBoolean)
      assert(dut.uop.srcAReg.toInt == 3 && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.useImm.toBoolean && (dut.uop.imm.toLong & 0xffff) == 0xABCD)
      assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && dut.uop.size.toEnum == Size.WORD)
    }
  }
  // CMPI.L #imm,D1 (0x0C81): writes NO register.
  test("CMPI.L #imm,D1 (reg dest): no dst write, NZVC", VerilatorTest) {
    run { dut => drive(dut, 0x0C81, 0x0000, 0x0001, len = 3); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.CMP && !dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean)
      assert(dut.uop.srcAReg.toInt == 1 && dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 1)
    }
  }
  // ADDI.B #imm,(A0) (0x0610): memory destination = the RMW form -> cracks into a
  // leading LOAD ([load -> T0][ADD #imm -> T1][store T1]). uops(0) is the load.
  test("ADDI.B #imm,(A0) (mem dest) -> leading LOAD (RMW crack)", VerilatorTest) {
    run { dut => drive(dut, 0x0610, 0x0042, len = 2); sleep(1)
      assert(!dut.uop.unimplemented.toBoolean && dut.uop.memOp.toEnum == MemOp.LOAD)
    }
  }
  // ANDI #imm,CCR (0x023C) + imm.B = words(1). toCcr µop: reads+writes NZVC+X, no int
  // operands/dst, op = AND, useImm = the imm byte. NOT unimplemented (CCR is non-priv).
  test("ANDI #0x1f,CCR (0x023C): toCcr, AND, reads+writes NZVC+X, no int dst", VerilatorTest) {
    run { dut => drive(dut, 0x023C, 0x001F, len = 2); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.AND && !dut.uop.unimplemented.toBoolean)
      assert(dut.uop.toCcr.toBoolean)
      assert(dut.uop.readsNzvc.toBoolean && dut.uop.readsX.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
      assert(!dut.uop.srcAValid.toBoolean && !dut.uop.srcBValid.toBoolean && !dut.uop.dstValid.toBoolean)
      assert(dut.uop.useImm.toBoolean && (dut.uop.imm.toLong & 0x1f) == 0x1f)
    }
  }
  test("ORI #imm,CCR (0x003C): toCcr, OR", VerilatorTest) {
    run { dut => drive(dut, 0x003C, 0x0003, len = 2); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.OR && dut.uop.toCcr.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  test("EORI #imm,CCR (0x0A3C): toCcr, EOR", VerilatorTest) {
    run { dut => drive(dut, 0x0A3C, 0x0010, len = 2); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.EOR && dut.uop.toCcr.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  // ANDI #imm,SR (0x027C, word) is privileged -> deferred (illegal). CMPI #imm,CCR is
  // not a valid form -> illegal. Neither is a toCcr op.
  test("ANDI #imm,SR (word, 0x027C) -> unimplemented (privileged, deferred)", VerilatorTest) {
    run { dut => drive(dut, 0x027C, 0x0000, len = 2); sleep(1)
      assert(dut.uop.unimplemented.toBoolean && !dut.uop.toCcr.toBoolean)
    }
  }
  test("non-simple packet -> unimplemented", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drive(dut, 0x7605); dut.pkt.simple #= false; dut.pkt.complex #= true; sleep(1)
      assert(dut.uop.unimplemented.toBoolean)
    }
  }
}
