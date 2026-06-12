package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** ABCD (line C) / SBCD (line 8) packed-BCD register form (Dy,Dx) decode + assemble.
  *
  * Encoding: `1100 xxx 1 0000 0 yyy` (ABCD) / `1000 xxx 1 0000 0 yyy` (SBCD). bit8=1 +
  * bits7:6=00 = opmode 4 (the AND/OR-RMW band); bits 5:4=00 + bit3=0 ("00000") select
  * the DATA-register form. xxx(11:9)=Dx (dst + a source), yyy(2:0)=Dy (a source). One
  * DecOp.BCD with bcdSub (False=ABCD add, True=SBCD subtract). srcB resolves to Dy via
  * `easrc` (EA mode 000 -> EaDecoder DATAREG D[op(2:0)], the ADDX/SUBX precedent), so at
  * the assembler level srcBReg == op(2:0). Carved off the AND/OR-RMW path (which would
  * reject this Dn-direct EA as illegal); the memory form (bit3=1) stays illegal/deferred. */
class BcdDecodeSpec extends AnyFunSuite {

  // ── OperationDecoder level ──────────────────────────────────────────────────
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

  // ── ABCD D1,D0 = 0xC101 (1100 000 1 0000 0 001) ─────────────────────────────
  test("ABCD D1,D0 (0xC101): op=BCD bcdSub=False size=BYTE, flag masks", VerilatorTest) {
    runDec(0xC101) { dut =>
      assert(dut.o.op.toEnum == DecOp.BCD, "op = BCD")
      assert(!dut.o.bcdSub.toBoolean, "bcdSub = False (ABCD)")
      assert(dut.o.size.toEnum == Size.BYTE, "size = BYTE")
      assert(!dut.o.illegal.toBoolean, "not illegal")
      assert(dut.o.srcA.kind.toEnum == OperandKind.REGFIELD && !dut.o.srcA.isAddr.toBoolean, "srcA = REGFIELD (Dx)")
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC, "srcB = EASRC (Dy via mode-000)")
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && dut.o.dstWrites.toBoolean, "dst = REGFIELD, writes")
      assert(dut.o.readsX.toBoolean && dut.o.readsNzvc.toBoolean, "reads X + NZVC (old Z)")
      assert(dut.o.writesX.toBoolean && dut.o.writesNzvc.toBoolean, "writes X + NZVC")
    }
  }
  test("ABCD D1,D0 (0xC101): assembler resolves srcA=D0(Dx), srcB=D1(Dy), dst=D0", VerilatorTest) {
    runAsm { dut => drive(dut, 0xC101); sleep(1)
      assert(dut.count.toInt == 1, "single ALU µop, no crack")
      assert(dut.uop0.op.toEnum == DecOp.BCD, "op = BCD")
      assert(!dut.uop0.bcdSub.toBoolean, "bcdSub = False (ABCD)")
      assert(!dut.uop0.unimplemented.toBoolean, "not illegal")
      assert(dut.uop0.srcAReg.toInt == 0 && dut.uop0.srcAValid.toBoolean, "srcA = D0 (Dx, 11:9)")
      assert(dut.uop0.srcBReg.toInt == 1 && dut.uop0.srcBValid.toBoolean, "srcB = D1 (Dy, 2:0)")
      assert(dut.uop0.dstReg.toInt == 0 && dut.uop0.dstValid.toBoolean, "dst = D0 (Dx)")
      assert(dut.uop0.size.toEnum == Size.BYTE, "size = BYTE")
      assert(dut.uop0.readsX.toBoolean && dut.uop0.readsNzvc.toBoolean)
      assert(dut.uop0.writesX.toBoolean && dut.uop0.writesNzvc.toBoolean)
      assert(!dut.uop0.useImm.toBoolean, "no immediate")
    }
  }

  // ── SBCD D1,D0 = 0x8101 (1000 000 1 0000 0 001) ─────────────────────────────
  test("SBCD D1,D0 (0x8101): op=BCD bcdSub=True size=BYTE, srcA=D0 srcB=D1 dst=D0", VerilatorTest) {
    runDec(0x8101) { dut =>
      assert(dut.o.op.toEnum == DecOp.BCD, "op = BCD")
      assert(dut.o.bcdSub.toBoolean, "bcdSub = True (SBCD)")
      assert(dut.o.size.toEnum == Size.BYTE, "size = BYTE")
      assert(!dut.o.illegal.toBoolean)
      assert(dut.o.readsX.toBoolean && dut.o.readsNzvc.toBoolean)
      assert(dut.o.writesX.toBoolean && dut.o.writesNzvc.toBoolean)
    }
    runAsm { dut => drive(dut, 0x8101); sleep(1)
      assert(dut.uop0.op.toEnum == DecOp.BCD, "op = BCD")
      assert(dut.uop0.bcdSub.toBoolean, "bcdSub = True (SBCD)")
      assert(dut.uop0.srcAReg.toInt == 0 && dut.uop0.dstReg.toInt == 0, "srcA/dst = D0 (Dx)")
      assert(dut.uop0.srcBReg.toInt == 1, "srcB = D1 (Dy)")
      assert(dut.uop0.size.toEnum == Size.BYTE)
      assert(!dut.uop0.unimplemented.toBoolean)
    }
  }

  // ── ABCD D7,D3 = 0xC707 (1100 011 1 0000 0 111): distinct Dx/Dy ──────────────
  test("ABCD D7,D3 (0xC707): srcA=D3(Dx), srcB=D7(Dy), dst=D3", VerilatorTest) {
    runAsm { dut => drive(dut, 0xC707); sleep(1)
      assert(dut.uop0.op.toEnum == DecOp.BCD && !dut.uop0.bcdSub.toBoolean)
      assert(dut.uop0.srcAReg.toInt == 3 && dut.uop0.dstReg.toInt == 3, "srcA/dst = D3 (Dx)")
      assert(dut.uop0.srcBReg.toInt == 7, "srcB = D7 (Dy)")
      assert(!dut.uop0.unimplemented.toBoolean)
    }
  }

  // ── Carve-out negatives: BCD must NOT swallow AND/OR/MUL, and the memory form
  //    (bit3=1) must stay illegal/deferred. ──────────────────────────────────────
  test("AND D1,D0 (0xC001, opmode 0): still AND, NOT BCD", VerilatorTest) {
    runDec(0xC001) { dut =>
      assert(dut.o.op.toEnum == DecOp.AND, "opmode 0 -> AND (EA->Dn), not BCD")
    }
  }

  test("OR D1,D0 (0x8001, opmode 0): still OR, NOT BCD", VerilatorTest) {
    runDec(0x8001) { dut =>
      assert(dut.o.op.toEnum == DecOp.OR, "opmode 0 -> OR (EA->Dn), not BCD")
    }
  }

  test("MULU D1,D0 (0xC0C1, opmode 3): still MUL, NOT BCD", VerilatorTest) {
    runDec(0xC0C1) { dut =>
      assert(dut.o.op.toEnum == DecOp.MUL, "opmode 3 -> MULU, not BCD")
    }
  }

  // ── ABCD/SBCD MEMORY form -(Ay),-(Ax) (bit3=1, EA mode 001): MICROCODED ───────
  // bits 5:3 = 001 (bit3=1) is the -(Ay),-(Ax) memory form: a >3-µop sequence the
  // DecodeStage µcode sequencer emits (NOT the fast crack). OperationDecoder marks it
  // microcoded + NON-illegal (op=BCD + bcdSub + size carry the kind), with the real µop
  // stream produced by the engine (validated in MicrocodeSpec + ExecuteLockStepSpec).
  test("ABCD memory form -(A1),-(A0) (0xC109, bit3=1): microcoded (NOT illegal)", VerilatorTest) {
    runDec(0xC109) { dut =>
      assert(!dut.o.illegal.toBoolean, "memory-form ABCD is NOT illegal (microcoded)")
      assert(dut.o.microcoded.toBoolean, "memory-form ABCD is microcoded")
      assert(dut.o.op.toEnum == DecOp.BCD, "op = BCD (the latched op for the engine)")
      assert(!dut.o.bcdSub.toBoolean, "ABCD: bcdSub = False")
      assert(dut.o.size.toEnum == Size.BYTE, "BCD mem is byte-only")
    }
  }

  test("SBCD memory form -(A1),-(A0) (0x8109, bit3=1): microcoded (NOT illegal)", VerilatorTest) {
    runDec(0x8109) { dut =>
      assert(!dut.o.illegal.toBoolean, "memory-form SBCD is NOT illegal (microcoded)")
      assert(dut.o.microcoded.toBoolean, "memory-form SBCD is microcoded")
      assert(dut.o.op.toEnum == DecOp.BCD, "op = BCD")
      assert(dut.o.bcdSub.toBoolean, "SBCD: bcdSub = True")
    }
  }
}
