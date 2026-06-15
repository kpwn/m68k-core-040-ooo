package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** PACK/UNPK register form decode + assemble.
  *
  * Encoding:
  *   PACK Dy,Dx,#adj  = `1000 xxx 1 0100 0 yyy` + adj16 (opmode 5 = bits[8:6]=`101`)
  *   UNPK Dy,Dx,#adj  = `1000 xxx 1 1000 0 yyy` + adj16 (opmode 6 = bits[8:6]=`110`)
  * xxx = op[11:9] = Dx (DEST), yyy = op[2:0] = Dy (SOURCE).
  *
  * No CCR effect. size=BYTE (PACK, .B merge preserves Dx[31:8]) /
  * WORD (UNPK, .W merge preserves Dx[31:16]).
  *
  * The memory form (op[5:3]=001, op[3]=1) is DEFERRED -> stays illegal.
  * Line-C opmode 5/6 is OR.W/.L, NOT PACK/UNPK.
  */
class PackUnpkDecodeSpec extends AnyFunSuite {

  // ── OperationDecoder level ──────────────────────────────────────────────────
  class DecDut extends Component {
    val opword = in Bits (16 bits)
    val o      = out(OpSpec())
    o := OperationDecoder.decode(opword)
  }
  def runDec(op: Int)(check: DecDut => Unit): Unit =
    SimConfig.withVerilator.compile(new DecDut).doSim { dut => dut.opword #= op; sleep(1); check(dut) }

  // ── Assembler level ─────────────────────────────────────────────────────────
  class AsmDut extends Component {
    val pkt   = in(DecodePacket())
    val a     = MicroOpAssembler.assemble(pkt)
    val count = out(UInt(2 bits)); count := a.count
    val uop0  = out(DecodedUop()); uop0 := a.uops(0)
  }
  def driveAsm(dut: AsmDut, op: Int, adj16: Int = 0): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= 2; dut.pkt.wordCount #= 2; dut.pkt.fault #= false
    dut.pkt.words(0) #= op
    dut.pkt.words(1) #= adj16
    for (i <- 2 to 4) dut.pkt.words(i) #= 0
  }
  def runAsm(check: AsmDut => Unit): Unit =
    SimConfig.withVerilator.compile(new AsmDut).doSim(check)

  // ─────────────────────────────────────────────────────────────────────────────
  // PACK D1,D0,#0 = 0x8141  (1000 000 1 0100 0 001)
  //   Dx=D0 (op[11:9]=000), Dy=D1 (op[2:0]=001), opmode=5 (PACK)
  // ─────────────────────────────────────────────────────────────────────────────

  test("PACK D1,D0,#0 (0x8141): op=PACK size=BYTE not-illegal, no CCR", VerilatorTest) {
    runDec(0x8141) { dut =>
      assert(dut.o.op.toEnum == DecOp.PACK,           "op = PACK")
      assert(!dut.o.illegal.toBoolean,                "not illegal")
      assert(dut.o.size.toEnum == Size.BYTE,          "size = BYTE (PACK)")
      assert(!dut.o.readsNzvc.toBoolean,              "no NZVC read")
      assert(!dut.o.writesNzvc.toBoolean,             "no NZVC write")
      assert(!dut.o.readsX.toBoolean,                 "no X read")
      assert(!dut.o.writesX.toBoolean,                "no X write")
      // srcA = dnField (Dx, op[11:9]), srcB = easrc (Dy, op[2:0]), dst = dnField
      assert(dut.o.srcA.kind.toEnum == OperandKind.REGFIELD && !dut.o.srcA.isAddr.toBoolean, "srcA = REGFIELD (Dx)")
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC,   "srcB = EASRC (Dy via mode-000)")
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && dut.o.dstWrites.toBoolean, "dst = REGFIELD, writes")
    }
  }

  test("PACK D1,D0,#0 (0x8141): assembler srcA=D0 srcB=D1 dst=D0 size=BYTE useImm=adj16", VerilatorTest) {
    runAsm { dut => driveAsm(dut, 0x8141, 0x0000); sleep(1)
      assert(dut.count.toInt == 1,                         "single µop, no crack")
      assert(dut.uop0.op.toEnum == DecOp.PACK,             "op = PACK")
      assert(!dut.uop0.unimplemented.toBoolean,            "not unimplemented")
      assert(!dut.uop0.faulted.toBoolean,                  "not faulted")
      assert(dut.uop0.srcAReg.toInt == 0 && dut.uop0.srcAValid.toBoolean, "srcA = D0 (Dx)")
      assert(dut.uop0.srcBReg.toInt == 1 && dut.uop0.srcBValid.toBoolean, "srcB = D1 (Dy)")
      assert(dut.uop0.dstReg.toInt == 0 && dut.uop0.dstValid.toBoolean,   "dst  = D0 (Dx)")
      assert(dut.uop0.size.toEnum == Size.BYTE,            "size = BYTE")
      assert(dut.uop0.useImm.toBoolean,                   "useImm = True (adj16)")
      assert(dut.uop0.imm.toLong == 0,                    "imm = 0 (adj = 0)")
      assert(!dut.uop0.readsNzvc.toBoolean,               "no NZVC read")
      assert(!dut.uop0.writesNzvc.toBoolean,              "no NZVC write")
      assert(!dut.uop0.readsX.toBoolean,                  "no X read")
      assert(!dut.uop0.writesX.toBoolean,                 "no X write")
    }
  }

  // PACK D3,D5,#0x0042 = 0x8B43 + 0x0042
  //   Dx=D5 (op[11:9]=101), Dy=D3 (op[2:0]=011)
  test("PACK D3,D5,#0x42 (0x8B43 +0x0042): srcA=D5 srcB=D3 dst=D5 imm=0x42", VerilatorTest) {
    runAsm { dut => driveAsm(dut, 0x8B43, 0x0042); sleep(1)
      assert(dut.uop0.op.toEnum == DecOp.PACK,  "op = PACK")
      assert(!dut.uop0.unimplemented.toBoolean, "not unimplemented")
      assert(dut.uop0.srcAReg.toInt == 5,       "srcA = D5 (Dx)")
      assert(dut.uop0.srcBReg.toInt == 3,       "srcB = D3 (Dy)")
      assert(dut.uop0.dstReg.toInt == 5,        "dst  = D5 (Dx)")
      assert(dut.uop0.useImm.toBoolean,         "useImm = True")
      assert((dut.uop0.imm.toLong & 0xffffL) == 0x0042L, "imm = 0x0042")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // UNPK D2,D7,#0x0011 = 0x8F82  (1000 111 1 1000 0 010)
  //   Dx=D7 (op[11:9]=111), Dy=D2 (op[2:0]=010), opmode=6 (UNPK)
  // ─────────────────────────────────────────────────────────────────────────────

  test("UNPK D1,D0,#0 (0x8181): op=UNPK size=WORD not-illegal, no CCR", VerilatorTest) {
    // UNPK D1,D0,#0 = 0x8181: 1000 000 1 1000 0 001 -> Dx=D0(0), Dy=D1(1), opmode=6
    runDec(0x8181) { dut =>
      assert(dut.o.op.toEnum == DecOp.UNPK,           "op = UNPK")
      assert(!dut.o.illegal.toBoolean,                "not illegal")
      assert(dut.o.size.toEnum == Size.WORD,          "size = WORD (UNPK)")
      assert(!dut.o.readsNzvc.toBoolean,              "no NZVC read")
      assert(!dut.o.writesNzvc.toBoolean,             "no NZVC write")
      assert(!dut.o.readsX.toBoolean,                 "no X read")
      assert(!dut.o.writesX.toBoolean,                "no X write")
    }
  }

  test("UNPK D2,D7,#0x11 (0x8F82 +0x0011): srcA=D7 srcB=D2 dst=D7 size=WORD imm=0x11", VerilatorTest) {
    runAsm { dut => driveAsm(dut, 0x8F82, 0x0011); sleep(1)
      assert(dut.count.toInt == 1,                         "single µop, no crack")
      assert(dut.uop0.op.toEnum == DecOp.UNPK,             "op = UNPK")
      assert(!dut.uop0.unimplemented.toBoolean,            "not unimplemented")
      assert(!dut.uop0.faulted.toBoolean,                  "not faulted")
      assert(dut.uop0.srcAReg.toInt == 7 && dut.uop0.srcAValid.toBoolean, "srcA = D7 (Dx)")
      assert(dut.uop0.srcBReg.toInt == 2 && dut.uop0.srcBValid.toBoolean, "srcB = D2 (Dy)")
      assert(dut.uop0.dstReg.toInt == 7 && dut.uop0.dstValid.toBoolean,   "dst  = D7 (Dx)")
      assert(dut.uop0.size.toEnum == Size.WORD,            "size = WORD (UNPK .W merge)")
      assert(dut.uop0.useImm.toBoolean,                   "useImm = True (adj16)")
      assert((dut.uop0.imm.toLong & 0xffffL) == 0x0011L,  "imm = 0x0011")
      assert(!dut.uop0.readsNzvc.toBoolean,               "no NZVC read")
      assert(!dut.uop0.writesNzvc.toBoolean,              "no NZVC write")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // NEGATIVE: memory form (op[5:3]=001, EA mode 001) must not decode as PACK/UNPK.
  // The decoder leaves it as a non-illegal OR RMW placeholder (the existing RMW path
  // matches it), but the assembler makes it unimplemented (An-direct EA is not a valid
  // mem-dest). We check at the assembler level: unimplemented=True, op != PACK/UNPK.
  // ─────────────────────────────────────────────────────────────────────────────

  test("PACK memory form op[5:3]=001 (0x8149): assembler unimplemented (deferred)", VerilatorTest) {
    // 0x8149 = 1000 000 1 0100 1 001: opmode=5 (PACK field), op[5:3]=001 (memory form)
    runAsm { dut => driveAsm(dut, 0x8149, 0x0000); sleep(1)
      assert(dut.uop0.op.toEnum != DecOp.PACK,    "memory-form must NOT decode as PACK")
      assert(dut.uop0.unimplemented.toBoolean,     "memory-form PACK is unimplemented (deferred)")
    }
  }

  test("UNPK memory form op[5:3]=001 (0x8189): assembler unimplemented (deferred)", VerilatorTest) {
    // 0x8189 = 1000 000 1 1000 1 001: opmode=6 (UNPK field), op[5:3]=001 (memory form)
    runAsm { dut => driveAsm(dut, 0x8189, 0x0000); sleep(1)
      assert(dut.uop0.op.toEnum != DecOp.UNPK,    "memory-form must NOT decode as UNPK")
      assert(dut.uop0.unimplemented.toBoolean,     "memory-form UNPK is unimplemented (deferred)")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // NEGATIVE: line-C opmode 5/6 is AND.B/.W RMW, NOT PACK/UNPK
  // (line 8 opmode 5 = PACK; line C opmode 5 = AND.B RMW; must NOT be confused)
  // ─────────────────────────────────────────────────────────────────────────────

  test("AND.W D1,D0 (0xC041, line C opmode 1): decodes as AND not PACK/UNPK", VerilatorTest) {
    // 0xC041 = 1100 000 0 0100 0 001 = AND.W D1,D0 (line C opmode 1, EA->Dn)
    runDec(0xC041) { dut =>
      assert(dut.o.op.toEnum == DecOp.AND,             "line-C opmode 1 -> AND, not PACK/UNPK")
      assert(dut.o.op.toEnum != DecOp.PACK,            "line-C opmode 1 NOT PACK")
      assert(dut.o.op.toEnum != DecOp.UNPK,            "line-C opmode 1 NOT UNPK")
    }
  }
}
