package m68k040.decode

import m68k040.VerilatorTest
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class OperationDecoderSpec extends AnyFunSuite {
  class Dut extends Component {
    val opword = in Bits (16 bits)
    val o      = out(OpSpec())
    o := OperationDecoder.decode(opword)
  }
  def run(op: Int)(check: Dut => Unit): Unit =
    SimConfig.withVerilator.compile(new Dut).doSim { dut => dut.opword #= op; sleep(1); check(dut) }

  test("MOVEQ: MOVE/LONG, dst REGFIELD data, srcB IMMQ, writesNzvc", VerilatorTest) {
    run(0x7605) { dut =>
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && !dut.o.dst.isAddr.toBoolean && dut.o.dstWrites.toBoolean)
      assert(dut.o.srcB.kind.toEnum == OperandKind.IMMQ && dut.o.writesNzvc.toBoolean && !dut.o.illegal.toBoolean)
    }
  }
  test("MOVE.W: srcB EASRC, dst EADST, writesNzvcIfDataDst", VerilatorTest) {
    run(0x3200) { dut =>
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.WORD)
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.dst.kind.toEnum == OperandKind.EADST && dut.o.dstWrites.toBoolean)
      assert(dut.o.writesNzvcIfDataDst.toBoolean && !dut.o.writesNzvc.toBoolean)
    }
  }
  test("ADD.L EA->Dn: srcA REGFIELD(Dn), srcB EASRC, writesNzvc+X", VerilatorTest) {
    run(0xD081) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADD && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.srcA.kind.toEnum == OperandKind.REGFIELD && !dut.o.srcA.isAddr.toBoolean)
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && dut.o.dstWrites.toBoolean)
      assert(dut.o.writesNzvc.toBoolean && dut.o.writesX.toBoolean)
    }
  }
  test("CMP.L EA->Dn: no dst write, writesNzvc", VerilatorTest) {
    run(0xB081) { dut =>
      assert(dut.o.op.toEnum == DecOp.CMP && !dut.o.dstWrites.toBoolean && dut.o.writesNzvc.toBoolean)
    }
  }
  test("ADDA.L: opmode7, srcA EASRC, srcB REGFIELD(An), no flags", VerilatorTest) {
    run(0xD1C1) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADD && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.srcA.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.srcB.kind.toEnum == OperandKind.REGFIELD && dut.o.srcB.isAddr.toBoolean)
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && dut.o.dst.isAddr.toBoolean && dut.o.dstWrites.toBoolean)
      assert(!dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean)
    }
  }
  test("Bcc: isBranch, cond, readsNzvc for cond>=2", VerilatorTest) {
    run(0x6700) { dut => assert(dut.o.isBranch.toBoolean && dut.o.cond.toInt == 0x7 && dut.o.readsNzvc.toBoolean) }
  }
  // ALU Dn,<ea> RMW (opmode 4/5/6): OperationDecoder NAMES the operands EA-agnostically
  // (srcA=EASRC dst operand, srcB=Dn, dst=EASRC, ADD writes NZVCX). The assembler gates
  // the EA — a memory EA cracks into load-op-store; a Dn/An/MEMCOMPLEX EA is illegalised
  // there (aluRmwMemBad). So OperationDecoder is NOT illegal for these opwords.
  test("RMW form (ADD Dn->EA, opmode6) -> ADD named (EA gated in the assembler)", VerilatorTest) {
    run(0xD181) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADD && !dut.o.illegal.toBoolean)
      assert(dut.o.srcA.kind.toEnum == OperandKind.EASRC && dut.o.dst.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.srcB.kind.toEnum == OperandKind.REGFIELD)
      assert(dut.o.writesNzvc.toBoolean && dut.o.writesX.toBoolean)
    }
  }
  // EOR.L D1,D2 = 0xB382 (1011 001 1 10 000 010): opmode6 (.L), src Dn=bits11:9=D1,
  // dst = the EA (op[5:0] = D2). Dm := Dm ^ Dn. EOR's dst is the SAME EA it reads
  // (srcA=EASRC, dst=EASRC); srcB = Dn. writes the reg; NZ, V=C=0 (no X).
  test("EOR.L Dn,Dm (lineB opmode6, reg dest): EOR op, writesNzvc, no X", VerilatorTest) {
    run(0xB382) { dut =>
      assert(dut.o.op.toEnum == DecOp.EOR && dut.o.size.toEnum == Size.LONG && !dut.o.illegal.toBoolean)
      assert(dut.o.srcA.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.srcB.kind.toEnum == OperandKind.REGFIELD && !dut.o.srcB.isAddr.toBoolean)
      assert(dut.o.dst.kind.toEnum == OperandKind.EASRC && dut.o.dstWrites.toBoolean)
      assert(dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean)
    }
  }
  test("EOR.B Dn,Dm (lineB opmode4, reg dest): EOR/BYTE", VerilatorTest) {
    run(0xB302) { dut =>
      assert(dut.o.op.toEnum == DecOp.EOR && dut.o.size.toEnum == Size.BYTE && !dut.o.illegal.toBoolean)
    }
  }
  // CMPM (line B opmode 4/5/6, An-direct mode 1) is NOT EOR -> stays illegal here
  // (this slice; the (An)+,(An)+ compare is deferred).
  test("CMPM (lineB opmode4, An-direct) -> not EOR, illegal", VerilatorTest) {
    run(0xB509) { dut => assert(dut.o.illegal.toBoolean && dut.o.op.toEnum != DecOp.EOR) }
  }

  // ── Line-0 immediates: 0000 ooo0 ss mmmrrr + imm. srcA=EA (Dn dst operand),
  // srcB=IMMEXT (the trailing imm word(s)), dst=EA. opmode: 0=ORI,1=ANDI,2=SUBI,
  // 3=ADDI,5=EORI,6=CMPI. CMPI writes no reg. Mapped to OR/AND/SUB/ADD/EOR/CMP. ──
  test("ADDI.L #imm,D0 (0x0680): ADD op, srcA=EA, srcB=IMMEXT, NZVCX", VerilatorTest) {
    run(0x0680) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADD && dut.o.size.toEnum == Size.LONG && !dut.o.illegal.toBoolean)
      assert(dut.o.srcA.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.srcB.kind.toEnum == OperandKind.IMMEXT)
      assert(dut.o.dst.kind.toEnum == OperandKind.EASRC && dut.o.dstWrites.toBoolean)
      assert(dut.o.writesNzvc.toBoolean && dut.o.writesX.toBoolean)
    }
  }
  test("SUBI.W #imm,D0 (0x0440): SUB/WORD, NZVCX", VerilatorTest) {
    run(0x0440) { dut =>
      assert(dut.o.op.toEnum == DecOp.SUB && dut.o.size.toEnum == Size.WORD)
      assert(dut.o.writesNzvc.toBoolean && dut.o.writesX.toBoolean && dut.o.dstWrites.toBoolean)
    }
  }
  test("ANDI.B #imm,D0 (0x0200): AND/BYTE, NZ no X", VerilatorTest) {
    run(0x0200) { dut =>
      assert(dut.o.op.toEnum == DecOp.AND && dut.o.size.toEnum == Size.BYTE)
      assert(dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean && dut.o.dstWrites.toBoolean)
    }
  }
  test("ORI.L #imm,D0 (0x0080): OR, NZ no X", VerilatorTest) {
    run(0x0080) { dut =>
      assert(dut.o.op.toEnum == DecOp.OR && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean && dut.o.dstWrites.toBoolean)
    }
  }
  test("EORI.W #imm,D0 (0x0A40): EOR, NZ no X", VerilatorTest) {
    run(0x0A40) { dut =>
      assert(dut.o.op.toEnum == DecOp.EOR && dut.o.size.toEnum == Size.WORD)
      assert(dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean && dut.o.dstWrites.toBoolean)
    }
  }
  test("CMPI.L #imm,D0 (0x0C80): CMP, NZVC, no reg write", VerilatorTest) {
    run(0x0C80) { dut =>
      assert(dut.o.op.toEnum == DecOp.CMP && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean && !dut.o.dstWrites.toBoolean)
    }
  }
  test("line-0 opmode4 static bit-op (0x0840 BCHG #imm,D0): BITOP, bitOp=01, not illegal", VerilatorTest) {
    // opmode 4 (bits 11:8 == 1000) is the STATIC bit-op space (was illegal/out-of-scope
    // before the bit-ops slice). tt = bits 7:6 = 01 -> BCHG.
    run(0x0840) { dut =>
      assert(dut.o.op.toEnum == DecOp.BITOP && !dut.o.illegal.toBoolean)
      assert(dut.o.bitOp.toInt == 1)
    }
  }

  // ── Line-E register-form shifts/rotates (1110 ccc d ss i tt rrr) ─────────────
  test("ASL.L #1,D0 (0xE380): SHIFT tt=00 dir=left .L, imm, NZVC+X", VerilatorTest) {
    run(0xE380) { dut =>
      assert(dut.o.op.toEnum == DecOp.SHIFT && dut.o.size.toEnum == Size.LONG && !dut.o.illegal.toBoolean)
      assert(dut.o.shiftOp.toInt == 0 && dut.o.shiftDir.toBoolean && dut.o.shiftImm.toBoolean)
      assert(dut.o.writesNzvc.toBoolean && dut.o.writesX.toBoolean && dut.o.dstWrites.toBoolean)
    }
  }
  test("ASR.W #3,D1 (0xE641): SHIFT tt=00 dir=right .W, imm, writesX", VerilatorTest) {
    run(0xE641) { dut =>
      assert(dut.o.op.toEnum == DecOp.SHIFT && dut.o.size.toEnum == Size.WORD)
      assert(dut.o.shiftOp.toInt == 0 && !dut.o.shiftDir.toBoolean && dut.o.shiftImm.toBoolean && dut.o.writesX.toBoolean)
    }
  }
  test("LSL.B Dc,D2 (0xE32A): SHIFT tt=01 dir=left .B, register count (i=1)", VerilatorTest) {
    run(0xE32A) { dut =>
      assert(dut.o.op.toEnum == DecOp.SHIFT && dut.o.size.toEnum == Size.BYTE)
      assert(dut.o.shiftOp.toInt == 1 && dut.o.shiftDir.toBoolean && !dut.o.shiftImm.toBoolean && dut.o.writesX.toBoolean)
    }
  }
  test("ROXR.L #2,D3 (0xE493): SHIFT tt=10 dir=right .L, imm, writesX", VerilatorTest) {
    run(0xE493) { dut =>
      assert(dut.o.op.toEnum == DecOp.SHIFT && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.shiftOp.toInt == 2 && !dut.o.shiftDir.toBoolean && dut.o.writesX.toBoolean)
    }
  }
  test("ROR.W #1,D4 (0xE25C): SHIFT tt=11 dir=right .W, ROL/ROR do NOT write X", VerilatorTest) {
    run(0xE25C) { dut =>
      assert(dut.o.op.toEnum == DecOp.SHIFT && dut.o.size.toEnum == Size.WORD)
      assert(dut.o.shiftOp.toInt == 3 && !dut.o.shiftDir.toBoolean)
      assert(dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean)
    }
  }
  test("line-E ss=11 (memory single-bit form) -> illegal (deferred)", VerilatorTest) {
    run(0xE0D0) { dut => assert(dut.o.illegal.toBoolean) }   // 1110 000 0 11 010000
  }
}
