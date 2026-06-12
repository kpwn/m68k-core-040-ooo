package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** ADDX/SUBX register form (Dy,Dx) decode + assemble.
  *
  * Encoding (lines D/9): `1101 xxx 1 ss 000 yyy` (ADDX) / `1001 xxx 1 ss 000 yyy`
  * (SUBX). bit8=1 (RMW opmode), EA mode field (bits 5:3) == 000 = Dn-direct -> the
  * register form. xxx = Dx (bits 11:9, read+written), yyy = Dy (bits 2:0, the source).
  * Carved out of the line-9/D RMW slot (mode 000 is NOT a valid ADD/SUB-to-mem dst).
  * The decode names operands EA-agnostically; the assembler resolves register ids +
  * gates the deferred memory form (bit3=1 -> mode 001) to illegal. */
class AddxSubxDecodeSpec extends AnyFunSuite {

  // ── OperationDecoder level: op / size / operand kinds / flag masks ──────────
  class DecDut extends Component {
    val opword = in Bits (16 bits)
    val o      = out(OpSpec())
    o := OperationDecoder.decode(opword)
  }
  def runDec(op: Int)(check: DecDut => Unit): Unit =
    SimConfig.withVerilator.compile(new DecDut).doSim { dut => dut.opword #= op; sleep(1); check(dut) }

  // ── Assembler level: resolved register ids + illegal guards ─────────────────
  class AsmDut extends Component {
    val pkt = in(DecodePacket())
    val a   = MicroOpAssembler.assemble(pkt)
    val count = out(UInt(2 bits)); count := a.count
    val uop0  = out(DecodedUop()); uop0 := a.uops(0)
  }
  def drive(dut: AsmDut, op: Int): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= 1; dut.pkt.wordCount #= 1; dut.pkt.fault #= false
    dut.pkt.words(0) #= op
    for (i <- 1 to 4) dut.pkt.words(i) #= 0
  }
  def runAsm(check: AsmDut => Unit): Unit =
    SimConfig.withVerilator.compile(new AsmDut).doSim(check)

  // ── ADDX D2,D3 .L = 0xD782 (1101 011 1 10 000 010) ──────────────────────────
  test("ADDX.L D2,D3 (0xD782): op=ADDX size=LONG, flag masks", VerilatorTest) {
    runDec(0xD782) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADDX, "op = ADDX")
      assert(dut.o.size.toEnum == Size.LONG, "size = LONG")
      assert(!dut.o.illegal.toBoolean, "not illegal")
      assert(dut.o.srcA.kind.toEnum == OperandKind.REGFIELD && !dut.o.srcA.isAddr.toBoolean, "srcA = REGFIELD (Dx)")
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC, "srcB = EASRC (Dy)")
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && dut.o.dstWrites.toBoolean, "dst = REGFIELD, writes")
      assert(dut.o.readsX.toBoolean && dut.o.readsNzvc.toBoolean, "reads X + NZVC (old Z)")
      assert(dut.o.writesX.toBoolean && dut.o.writesNzvc.toBoolean, "writes X + NZVC")
    }
  }
  test("ADDX.L D2,D3 (0xD782): assembler resolves srcA=D3, srcB=D2, dst=D3", VerilatorTest) {
    runAsm { dut => drive(dut, 0xD782); sleep(1)
      assert(dut.count.toInt == 1, "single ALU µop, no crack")
      assert(dut.uop0.op.toEnum == DecOp.ADDX, "op = ADDX")
      assert(!dut.uop0.unimplemented.toBoolean, "not illegal")
      assert(dut.uop0.srcAReg.toInt == 3 && dut.uop0.srcAValid.toBoolean, "srcA = D3 (Dx)")
      assert(dut.uop0.srcBReg.toInt == 2 && dut.uop0.srcBValid.toBoolean, "srcB = D2 (Dy)")
      assert(dut.uop0.dstReg.toInt == 3 && dut.uop0.dstValid.toBoolean, "dst = D3 (Dx)")
      assert(dut.uop0.size.toEnum == Size.LONG)
      assert(dut.uop0.readsX.toBoolean && dut.uop0.readsNzvc.toBoolean)
      assert(dut.uop0.writesX.toBoolean && dut.uop0.writesNzvc.toBoolean)
      assert(!dut.uop0.useImm.toBoolean, "no immediate")
    }
  }

  // ── SUBX D0,D1 .B = 0x9300 (1001 001 1 00 000 000) ──────────────────────────
  test("SUBX.B D0,D1 (0x9300): op=SUBX size=BYTE, srcA=D1, srcB=D0", VerilatorTest) {
    runDec(0x9300) { dut =>
      assert(dut.o.op.toEnum == DecOp.SUBX, "op = SUBX")
      assert(dut.o.size.toEnum == Size.BYTE, "size = BYTE")
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.readsX.toBoolean && dut.o.readsNzvc.toBoolean)
      assert(dut.o.writesX.toBoolean && dut.o.writesNzvc.toBoolean)
    }
    runAsm { dut => drive(dut, 0x9300); sleep(1)
      assert(dut.uop0.op.toEnum == DecOp.SUBX, "op = SUBX")
      assert(dut.uop0.size.toEnum == Size.BYTE, "size = BYTE")
      assert(dut.uop0.srcAReg.toInt == 1 && dut.uop0.dstReg.toInt == 1, "srcA/dst = D1 (Dx)")
      assert(dut.uop0.srcBReg.toInt == 0, "srcB = D0 (Dy)")
      assert(!dut.uop0.unimplemented.toBoolean)
    }
  }

  // ── ADDX D5,D5 .W = 0xDB45 (1101 101 1 01 000 101): Dx == Dy ─────────────────
  test("ADDX.W D5,D5 (0xDB45): srcA=srcB=dst=D5, size=WORD", VerilatorTest) {
    runDec(0xDB45) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADDX && dut.o.size.toEnum == Size.WORD)
    }
    runAsm { dut => drive(dut, 0xDB45); sleep(1)
      assert(dut.uop0.op.toEnum == DecOp.ADDX, "op = ADDX")
      assert(dut.uop0.size.toEnum == Size.WORD, "size = WORD")
      assert(dut.uop0.srcAReg.toInt == 5 && dut.uop0.srcBReg.toInt == 5 && dut.uop0.dstReg.toInt == 5,
        "srcA = srcB = dst = D5")
      assert(!dut.uop0.unimplemented.toBoolean)
    }
  }

  // ── Negative guards: the carve-out must NOT disturb ADD/SUB/ADDA, and the
  //    deferred memory form must stay illegal. ─────────────────────────────────
  test("ADD.L D2,D3 (0xD682, bit8=0): still ADD, NOT ADDX", VerilatorTest) {
    runDec(0xD682) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADD, "bit8=0 -> ADD (EA->Dn), not ADDX")
      assert(dut.o.size.toEnum == Size.LONG)
    }
  }

  test("ADDA.L A2,A3 (0xD7CA, opmode 7): still ADD/ADDA, NOT ADDX", VerilatorTest) {
    // ADDA.L A2,A3: 1101 011 111 001 010 = 0xD7CA (opmode 7 = .L An dst)
    runDec(0xD7CA) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADD, "ADDA decodes as ADD, not ADDX")
      assert(dut.o.dst.isAddr.toBoolean, "dst = An (ADDA)")
    }
  }

  test("ADDX.L -(A2),-(A3) (0xD78A, bit3=1 -> EA mode 001): MICROCODED (NOT illegal)", VerilatorTest) {
    // Memory form: EA mode field = 001 (An-direct in the RMW slot) = the -(Ay),-(Ax) form.
    // A >3-µop sequence the DecodeStage µcode SEQUENCER emits; OperationDecoder marks it
    // microcoded + NON-illegal (op=ADDX + size carry the kind). Validated end-to-end in
    // MicrocodeSpec + ExecuteLockStepSpec.
    runDec(0xD78A) { dut =>
      assert(!dut.o.illegal.toBoolean, "memory-form ADDX is NOT illegal (microcoded)")
      assert(dut.o.microcoded.toBoolean, "memory-form ADDX is microcoded")
      assert(dut.o.op.toEnum == DecOp.ADDX, "op = ADDX (the latched op for the engine)")
      assert(dut.o.size.toEnum == Size.LONG, "ADDX.L mem -> size LONG")
    }
  }

  test("SUBX.B -(A1),-(A0) (0x9109, mode 001): MICROCODED", VerilatorTest) {
    runDec(0x9109) { dut =>   // 1001 000 1 00 001 001 (line9 Ax=A0 opmode4 .B eaMode001 Ay=A1)
      assert(dut.o.microcoded.toBoolean, "memory-form SUBX is microcoded")
      assert(dut.o.op.toEnum == DecOp.SUBX, "op = SUBX")
      assert(dut.o.size.toEnum == Size.BYTE, "SUBX.B mem -> size BYTE")
    }
  }

  test("SUB.L D0,D1 (0x9081, bit8=0): still SUB, NOT SUBX", VerilatorTest) {
    runDec(0x9081) { dut =>
      assert(dut.o.op.toEnum == DecOp.SUB, "bit8=0 -> SUB (EA->Dn), not SUBX")
    }
  }

  test("SUB.L D1,(A0) (0x9390, mem-dest RMW): still SUB mem-RMW, NOT SUBX", VerilatorTest) {
    // line 9 opmode 6, EA (A0)=mode2 -> a legitimate SUB-to-mem RMW; eaMode != 000.
    runDec(0x9390) { dut =>
      assert(dut.o.op.toEnum == DecOp.SUB, "mem-RMW SUB unchanged (eaMode != 000)")
    }
  }
}
