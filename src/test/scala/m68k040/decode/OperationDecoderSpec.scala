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
  // NOTE: use a MEMORY EA (0xD190 = ADD.L D0,(A0), mode 010) — the Dn-direct opmode-6 form
  // (e.g. 0xD181) is now ADDX (the ADDX/SUBX slice claims line-9/D RMW + EA-mode-000).
  test("RMW form (ADD.L D0,(A0), opmode6) -> ADD named (EA gated in the assembler)", VerilatorTest) {
    run(0xD190) { dut =>
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

  // ── Track C: LEA / PEA / MOVE from-SR / from-CCR / to-CCR (line-4) ───────────
  test("LEA (A0),A0 (0x41D0): non-illegal, MOVE/LONG", VerilatorTest) {
    run(0x41D0) { dut =>
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.LONG)
    }
  }
  test("LEA (d16,A0),A1 (0x43E8): non-illegal (control EA)", VerilatorTest) {
    run(0x43E8) { dut => assert(!dut.o.illegal.toBoolean) }
  }
  test("EXTB.L D0 (0x49C0): stays EXT (NOT mis-decoded as LEA)", VerilatorTest) {
    run(0x49C0) { dut =>
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.op.toEnum == DecOp.EXT && dut.o.size.toEnum == Size.LONG && dut.o.extByte.toBoolean)
    }
  }
  test("PEA (A0) (0x4850): non-illegal, MOVE/LONG", VerilatorTest) {
    run(0x4850) { dut =>
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.LONG)
    }
  }
  test("MOVE from SR (A0) (0x40D0): non-illegal, MOVE/WORD", VerilatorTest) {
    run(0x40D0) { dut =>
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.WORD)
    }
  }
  test("MOVE from CCR (A0) (0x42D0): non-illegal, MOVE/WORD", VerilatorTest) {
    run(0x42D0) { dut =>
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.WORD)
    }
  }
  test("MOVE to CCR (A0) (0x44D0): non-illegal, MOVE/WORD, srcB EASRC", VerilatorTest) {
    run(0x44D0) { dut =>
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.WORD)
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC)
    }
  }
  test("MOVE to SR (A0) (0x46D0): now implemented (Track D) -> legal sysOp", VerilatorTest) {
    run(0x46D0) { dut => assert(!dut.o.illegal.toBoolean && dut.o.sysOp.toBoolean) }
  }
  // ── Privileged commit-time SYSTEM ops (Track D) ─────────────────────────────
  test("MOVE to SR (0x46C0|Dn): sysOp MOVE_TO_SR write, srcB EASRC, non-illegal", VerilatorTest) {
    run(0x46C0) { dut =>     // move %d0,%sr
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.MOVE_TO_SR)
      assert(!dut.o.sysReadDir.toBoolean)          // write <ea> -> SR
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC && dut.o.size.toEnum == Size.WORD)
      assert(!dut.o.dstWrites.toBoolean)
    }
  }
  test("MOVE USP write (0x4E60|An): sysOp MOVE_USP, write dir, srcB An", VerilatorTest) {
    run(0x4E63) { dut =>     // move %a3,%usp
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.MOVE_USP)
      assert(!dut.o.sysReadDir.toBoolean)
      assert(dut.o.srcB.kind.toEnum == OperandKind.REGFIELD && dut.o.srcB.isAddr.toBoolean)
    }
  }
  test("MOVE USP read (0x4E68|An): sysOp MOVE_USP, read dir", VerilatorTest) {
    run(0x4E6C) { dut =>     // move %usp,%a4
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.MOVE_USP)
      assert(dut.o.sysReadDir.toBoolean)           // USP -> An (read)
    }
  }
  test("MOVEC Rc->Rn (0x4E7A): sysOp MOVEC, read dir, non-illegal", VerilatorTest) {
    run(0x4E7A) { dut =>
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.MOVEC)
      assert(dut.o.sysReadDir.toBoolean)           // Rc -> Rn (read)
    }
  }
  test("MOVEC Rn->Rc (0x4E7B): sysOp MOVEC, write dir", VerilatorTest) {
    run(0x4E7B) { dut =>
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.MOVEC)
      assert(!dut.o.sysReadDir.toBoolean)          // Rn -> Rc (write)
    }
  }
  test("RESET (0x4E70): sysOp RESET, non-illegal, write dir", VerilatorTest) {
    run(0x4E70) { dut =>
      assert(!dut.o.illegal.toBoolean, "RESET must not be illegal")
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.RESET)
      assert(!dut.o.sysReadDir.toBoolean)
    }
  }
  test("STOP (0x4E72): sysOp STOP, non-illegal, write dir", VerilatorTest) {
    run(0x4E72) { dut =>
      assert(!dut.o.illegal.toBoolean, "STOP must not be illegal")
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.STOP)
      assert(!dut.o.sysReadDir.toBoolean)
    }
  }

  // ── CPUSH/CINV (Task P5.2): line-1111 top byte 0xF4, bit[5] selects CPUSH(1)/
  // CINV(0). The arm claims the WHOLE 0xF4xx byte unconditionally (CC/SS/AAA are not
  // gated by this decoder), so it's always a non-illegal, commit-time SYSTEM MOVE/LONG
  // with no dst write. Ground-truth opwords per Task P5.1's cross-check: 0xF448 has
  // bit5=0 (CINV), 0xF468/0xF478 have bit5=1 (CPUSH).
  test("CINV (0xF448): sysOp CINV, MOVE/LONG, no dst write, write dir", VerilatorTest) {
    run(0xF448) { dut =>
      assert(!dut.o.illegal.toBoolean, "CINV must not be illegal")
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.CINV)
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.LONG)
      assert(!dut.o.sysReadDir.toBoolean && !dut.o.dstWrites.toBoolean)
    }
  }
  test("CPUSH (0xF468): sysOp CPUSH, MOVE/LONG, no dst write, write dir", VerilatorTest) {
    run(0xF468) { dut =>
      assert(!dut.o.illegal.toBoolean, "CPUSH must not be illegal")
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.CPUSH)
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.LONG)
      assert(!dut.o.sysReadDir.toBoolean && !dut.o.dstWrites.toBoolean)
    }
  }
  test("CPUSH (0xF478): sysOp CPUSH (a different CC/SS/An bit pattern, same bit[5]=1)", VerilatorTest) {
    run(0xF478) { dut =>
      assert(!dut.o.illegal.toBoolean, "CPUSH must not be illegal")
      assert(dut.o.sysOp.toBoolean && dut.o.sysKind.toEnum == SysKind.CPUSH)
    }
  }
  // Every OTHER opword in the 0xF4xx/0xF5xx byte-pair does NOT hit the CPUSH/CINV arm
  // (it requires bits[11:8]==0100, i.e. the fixed 0xF4 byte — 0xF5xx has bit8=1 and is
  // claimed, if at all, by the separate PFLUSH/PTEST arms). These representative 0xF5xx
  // opwords match neither the PFLUSH-family pattern (op[7:6]=00 fixed, mode field<=3)
  // nor the PTEST pattern (op[7:6]=01, op[4]=0,op[3]=1) nor PFLUSHA/PTEST/MOVE16/FSF, so
  // they correctly fall through to the F-line illegalDefault.
  test("0xF520 (F5xx, mode field=4>3): not PFLUSH-family, not PTEST -> illegal", VerilatorTest) {
    run(0xF520) { dut => assert(dut.o.illegal.toBoolean) }
  }
  test("0xF580 (F5xx, op[7:6]=10): not PFLUSH-family, not PTEST -> illegal", VerilatorTest) {
    run(0xF580) { dut => assert(dut.o.illegal.toBoolean) }
  }
  test("0xF5C0 (F5xx, op[7:6]=11): not PFLUSH-family, not PTEST -> illegal", VerilatorTest) {
    run(0xF5C0) { dut => assert(dut.o.illegal.toBoolean) }
  }

  /** EXHAUSTIVE dst-EA placement contract — a permanent, load-bearing invariant of
    * `OperationDecoder` that `MicroOpAssembler` depends on but nothing else tests.
    *
    * Two claims, both swept over ALL 65536 opwords:
    *
    *   1. `dst.kind === EADST  ==>  opword(15 downto 12) in {1, 2, 3}`
    *      i.e. plain MOVE.B/.W/.L is the ONLY family that architecturally consumes a
    *      destination EA. `MicroOpAssembler.scala`'s `usesDstEa` is *literally* this
    *      predicate, and it is the gate on every `dstEa`-derived decision there
    *      (`crackStore`, `crackMemMem`, and the `dstOk` legality check). Any reasoning
    *      about which extension words a dst EA may read — including the framing
    *      guarantee that a MOVE's dst extension words live inside its own instruction
    *      length — rests on this being true.
    *
    *   2. `srcA.kind`/`srcB.kind` are NEVER `EADST`. `MicroOpAssembler` has
    *      `is(OperandKind.EADST)` arms in its srcA/srcB operand routing that read
    *      `dstEa` WITHOUT the `usesDstEa` gate; those are safe today only because
    *      `OperationDecoder` puts its single `eadst` value in `o.dst` and nowhere else.
    *      That is an unstated premise everywhere else, so pin it here.
    *
    * A future opcode adding `o.dst := eadst` outside lines {1,2,3}, or routing `eadst`
    * into a source slot, would silently invalidate those arguments with no other test
    * failing.
    *
    * This RUNS TO COMPLETION and REPORTS COUNTS — violations are accumulated, never
    * thrown at the first mismatch. (The project has been burned by `PredecodeWordSpec`'s
    * "exhaustive" test, which aborts at its first mismatch and so silently
    * under-reports; do not repeat that.) Non-vacuity is self-evident from the reported
    * histogram and pinned by the exact-count asserts at the end: the invariant is
    * satisfied by *matching* lines 1/2/3, not by "EADST never happens".
    */
  test("EXHAUSTIVE 65536-opword contract: dst.kind === EADST only on MOVE line nibbles, never in a source slot", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      var swept         = 0
      var eadstCount    = 0
      var srcAEadst     = 0
      var srcBEadst     = 0
      val lineHisto     = Array.fill(16)(0)
      val violations    = scala.collection.mutable.ArrayBuffer[Int]()
      val srcViolations = scala.collection.mutable.ArrayBuffer[Int]()

      for (op <- 0 until 65536) {
        dut.opword #= op
        sleep(1)
        if (dut.o.dst.kind.toEnum == OperandKind.EADST) {
          eadstCount += 1
          val line = (op >> 12) & 0xF
          lineHisto(line) += 1
          if (line != 1 && line != 2 && line != 3) violations += op    // accumulate, DO NOT abort
        }
        if (dut.o.srcA.kind.toEnum == OperandKind.EADST) { srcAEadst += 1; if (srcViolations.size < 64) srcViolations += op }
        if (dut.o.srcB.kind.toEnum == OperandKind.EADST) { srcBEadst += 1; if (srcViolations.size < 64) srcViolations += op }
        swept += 1
      }

      assert(swept == 65536, s"sweep did not run to completion: only $swept opwords")
      val histo = (0 until 16).filter(lineHisto(_) > 0)
                              .map(l => f"line$l%x=${lineHisto(l)}").mkString(" ")
      println(s"[dstEa-contract] EXHAUSTIVE opword sweep: swept $swept/65536 opwords to completion; " +
              s"dst.kind===EADST on $eadstCount of them; per-line histogram: $histo; " +
              s"line-nibble violations: ${violations.size}; " +
              s"srcA-EADST: $srcAEadst, srcB-EADST: $srcBEadst")

      // (`violations.size == 0` rather than `.isEmpty`: ScalaTest's assert macro would
      // otherwise dump the whole ArrayBuffer -- up to thousands of opwords -- ahead of the
      // clue. Verified against a deliberate mutation: `1024 did not equal 0` + the clue.)
      assert(violations.size == 0,
        s"DST-EA CONTRACT BROKEN: ${violations.size} opword(s) set spec.dst.kind===EADST OUTSIDE the " +
        s"MOVE line nibbles {1,2,3}. MicroOpAssembler's `usesDstEa` is exactly this predicate and gates " +
        s"every dstEa-derived decision there (crackStore, crackMemMem, dstOk legality), including the " +
        s"assumption that a dst EA's extension words lie inside the instruction's own framing. " +
        s"Re-derive those arguments before adding this opcode. First 16 offending opwords: " +
        s"${violations.take(16).map(o => f"0x$o%04X").mkString(",")}")

      assert(srcAEadst == 0 && srcBEadst == 0,
        s"OperationDecoder now routes EADST into a SOURCE operand slot ($srcAEadst srcA, $srcBEadst srcB). " +
        s"MicroOpAssembler's srcA/srcB `is(OperandKind.EADST)` arms read dstEa WITHOUT the usesDstEa gate, " +
        s"so every blast-radius argument about dstEa reads must be re-derived for those. First offenders: " +
        s"${srcViolations.take(16).map(o => f"0x$o%04X").mkString(",")}")

      // Exactness / non-vacuity. OperationDecoder's sole `o.dst := eadst` site is
      // UNCONDITIONAL inside `is(0x1, 0x3, 0x2)`, so every one of the 3*4096 MOVE-line
      // opwords must hit it and nothing else may. A deliberate narrowing (e.g. carving a
      // sub-encoding out of a MOVE line) is still contract-safe -- update this expectation
      // and say why. A count of 0 would mean the sweep is vacuous and proves nothing.
      assert(eadstCount == 3 * 4096,
        s"expected exactly ${3 * 4096} dst-EADST opwords (the whole of lines 1/2/3), got $eadstCount " +
        s"(histogram: $histo)")
      assert(lineHisto(1) == 4096 && lineHisto(2) == 4096 && lineHisto(3) == 4096,
        s"MOVE lines are not uniformly dst-EADST (histogram: $histo)")
    }
  }
}
