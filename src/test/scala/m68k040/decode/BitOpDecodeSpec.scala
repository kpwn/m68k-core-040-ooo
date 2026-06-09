package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, MemOp, Size}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Bit-op decode + assemble (BTST/BCHG/BCLR/BSET, static + dynamic, Dn + memory).
  *
  * Encoding (line 0): dynamic `0000 rrr 1 tt mmmrrr` (bit8=1, NOT mode 001=MOVEP);
  * static `0000 1000 tt mmmrrr` + ext word. tt: 00 BTST, 01 BCHG, 10 BCLR, 11 BSET.
  * Dn dest -> LONG (bit mod 32); memory dest -> BYTE (bit mod 8): BTST load-only,
  * BSET/BCLR/BCHG mem-RMW crack. The decode names operands; the assembler resolves
  * the size/crack. */
class BitOpDecodeSpec extends AnyFunSuite {
  val T0 = MicroOpAssembler.T0   // 16
  val T1 = MicroOpAssembler.T1   // 17

  class Dut extends Component {
    val pkt = in(DecodePacket())
    val a   = MicroOpAssembler.assemble(pkt)
    val count = out(UInt(2 bits)); count := a.count
    val uop0  = out(DecodedUop()); uop0 := a.uops(0)
    val uop1  = out(DecodedUop()); uop1 := a.uops(1)
    val uop2  = out(DecodedUop()); uop2 := a.uops(2)
  }

  def drive(dut: Dut, op: Int, w1: Int = 0, w2: Int = 0, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // ── Dynamic Dn dest -> single BITOP µop, LONG, srcA=Dn(data), srcB=Dn(bit) ──────
  // BSET D1,D0 = 0x03C0 (0000 001 1 11 000 000): dynamic, tt=11 (BSET), EA mode0 D0.
  test("BSET D1,D0 (dynamic) -> BITOP bitOp=11, srcA=D0, srcB=D1, LONG, dstWrites", VerilatorTest) {
    run { dut => drive(dut, 0x03C0); sleep(1)
      assert(dut.count.toInt == 1, "Dn bit-op = 1 µop")
      assert(dut.uop0.op.toEnum == DecOp.BITOP, "op = BITOP")
      assert(dut.uop0.bitOp.toInt == 3, "bitOp = 11 (BSET)")
      assert(dut.uop0.size.toEnum == Size.LONG, "Dn dest -> LONG")
      assert(dut.uop0.srcAReg.toInt == 0 && dut.uop0.srcAValid.toBoolean, "srcA = D0 (data)")
      assert(dut.uop0.srcBReg.toInt == 1 && dut.uop0.srcBValid.toBoolean, "srcB = D1 (bit number)")
      assert(!dut.uop0.useImm.toBoolean, "dynamic -> no imm")
      assert(dut.uop0.dstReg.toInt == 0 && dut.uop0.dstValid.toBoolean, "dst = D0 (written)")
      assert(dut.uop0.readsNzvc.toBoolean && dut.uop0.writesNzvc.toBoolean, "reads+writes NZVC (Z-only)")
      assert(!dut.uop0.writesX.toBoolean, "no X write")
      assert(!dut.uop0.unimplemented.toBoolean)
    }
  }

  // ── Static Dn dest -> single BITOP µop, LONG, useImm = ext-word bit number ──────
  // BCLR #n,D0 = 0x0880 (0000 1000 10 000 000) + ext word (bit number). tt=10 (BCLR).
  test("BCLR #5,D0 (static) -> BITOP bitOp=10, useImm=5, srcA=D0, LONG, dstWrites", VerilatorTest) {
    run { dut => drive(dut, 0x0880, 0x0005, len = 2); sleep(1)
      assert(dut.count.toInt == 1)
      assert(dut.uop0.op.toEnum == DecOp.BITOP)
      assert(dut.uop0.bitOp.toInt == 2, "bitOp = 10 (BCLR)")
      assert(dut.uop0.size.toEnum == Size.LONG, "Dn dest -> LONG")
      assert(dut.uop0.useImm.toBoolean && (dut.uop0.imm.toLong & 0xff) == 5, "static bit number = ext word")
      assert(dut.uop0.srcAReg.toInt == 0 && dut.uop0.srcAValid.toBoolean, "srcA = D0 (data)")
      assert(dut.uop0.dstReg.toInt == 0 && dut.uop0.dstValid.toBoolean, "dst = D0")
      assert(!dut.uop0.unimplemented.toBoolean)
    }
  }

  // ── BTST Dn dest -> no write ────────────────────────────────────────────────────
  // BTST D2,D3 = 0x0503 (0000 010 1 00 000 011): dynamic, tt=00 (BTST), Dn=D2, EA=D3.
  test("BTST D2,D3 (dynamic) -> BITOP bitOp=00, no write, LONG", VerilatorTest) {
    run { dut => drive(dut, 0x0503); sleep(1)
      assert(dut.uop0.op.toEnum == DecOp.BITOP && dut.uop0.bitOp.toInt == 0, "BTST")
      assert(dut.uop0.size.toEnum == Size.LONG)
      assert(dut.uop0.srcAReg.toInt == 3 && dut.uop0.srcBReg.toInt == 2, "srcA=D3, srcB=D2")
      assert(!dut.uop0.dstValid.toBoolean, "BTST writes no register")
      assert(dut.uop0.writesNzvc.toBoolean && !dut.uop0.writesX.toBoolean)
      assert(!dut.uop0.unimplemented.toBoolean)
    }
  }

  // ── Dynamic BTST memory -> load-only (BYTE), NO store, count 2 ──────────────────
  // BTST D1,(A0) = 0x0310 (0000 001 1 00 010 000): dynamic, tt=00, EA (A0)=mode2 reg0.
  test("BTST D1,(A0) (dynamic mem) -> load.B->T0 + BITOP flags, NO store (count 2)", VerilatorTest) {
    run { dut => drive(dut, 0x0310); sleep(1)
      assert(dut.count.toInt == 2, "mem BTST = load + flags (no store)")
      assert(dut.uop0.cluster.toEnum == Cluster.LS && dut.uop0.memOp.toEnum == MemOp.LOAD, "uop0 = LOAD")
      assert(dut.uop0.size.toEnum == Size.BYTE, "mem -> BYTE")
      assert(dut.uop0.srcAReg.toInt == 8 && dut.uop0.dstReg.toInt == T0, "load (A0) -> T0")
      assert(dut.uop1.op.toEnum == DecOp.BITOP && dut.uop1.bitOp.toInt == 0, "uop1 = BITOP BTST")
      assert(dut.uop1.size.toEnum == Size.BYTE, "BITOP on byte")
      assert(dut.uop1.srcAReg.toInt == T0, "BITOP data = T0 (loaded byte)")
      assert(dut.uop1.srcBReg.toInt == 1 && dut.uop1.srcBValid.toBoolean, "bit number = D1")
      assert(!dut.uop1.dstValid.toBoolean, "BTST writes no register")
      assert(dut.uop1.memOp.toEnum == MemOp.NONE, "no store µop for mem BTST")
      assert(dut.uop1.writesNzvc.toBoolean)
      assert(!dut.uop0.unimplemented.toBoolean && !dut.uop1.unimplemented.toBoolean)
    }
  }

  // ── Dynamic BSET memory -> mem-RMW crack (load.B -> BITOP -> store.B), count 3 ──
  // BSET D2,(A1) = 0x05D1 (0000 010 1 11 010 001): dynamic, tt=11, EA (A1)=mode2 reg1.
  test("BSET D2,(A1) (dynamic mem) -> load.B->T0, BITOP->T1, store.B T1->(A1) (count 3)", VerilatorTest) {
    run { dut => drive(dut, 0x05D1); sleep(1)
      assert(dut.count.toInt == 3, "mem BSET = load + op + store")
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD && dut.uop0.dstReg.toInt == T0, "load -> T0")
      assert(dut.uop0.size.toEnum == Size.BYTE && dut.uop0.srcAReg.toInt == 9, "load.B (A1)")
      assert(dut.uop1.op.toEnum == DecOp.BITOP && dut.uop1.bitOp.toInt == 3, "BITOP BSET")
      assert(dut.uop1.size.toEnum == Size.BYTE, "BITOP on byte")
      assert(dut.uop1.dstReg.toInt == T1 && dut.uop1.dstValid.toBoolean, "BITOP -> T1")
      assert(dut.uop1.srcAReg.toInt == T0, "BITOP reads T0 (loaded byte)")
      assert(dut.uop1.srcBReg.toInt == 2, "bit number = D2")
      assert(dut.uop1.writesNzvc.toBoolean && !dut.uop1.firstOfInstr.toBoolean)
      assert(dut.uop2.memOp.toEnum == MemOp.STORE && dut.uop2.srcBReg.toInt == T1, "store T1")
      assert(dut.uop2.size.toEnum == Size.BYTE && dut.uop2.srcAReg.toInt == 9, "store.B (A1)")
      assert(!dut.uop2.writesNzvc.toBoolean, "store sets no flags (op owns them)")
    }
  }

  // ── Static BCHG memory -> mem-RMW crack; bit-number ext word precedes EA disp ───
  // BCHG #n,(d16,A2) = 0x0869+... wait: static BCHG = 0000 1000 01 mmmrrr. tt=01.
  // EA (d16,A2) = mode5 reg2 -> op = 0000 1000 01 101 010 = 0x086A. ext: bit word, then disp16.
  test("BCHG #3,(d16,A2) (static mem) -> RMW; bit word THEN disp16 (count 3)", VerilatorTest) {
    run { dut => drive(dut, 0x086A, 0x0003, 0x0020, len = 3); sleep(1)
      assert(dut.count.toInt == 3, "mem BCHG = load + op + store")
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD && dut.uop0.size.toEnum == Size.BYTE)
      assert(dut.uop0.srcAReg.toInt == 10 && dut.uop0.srcAValid.toBoolean, "load base = A2")
      assert((dut.uop0.imm.toLong & 0xffffffffL) == 0x20L, "load disp16 = 0x20 (word AFTER the bit word)")
      assert(dut.uop1.op.toEnum == DecOp.BITOP && dut.uop1.bitOp.toInt == 1, "BCHG")
      assert(dut.uop1.useImm.toBoolean && (dut.uop1.imm.toLong & 0xff) == 3, "static bit number = 3 (words(1))")
      assert(dut.uop1.dstReg.toInt == T1, "BITOP -> T1")
      assert(dut.uop2.memOp.toEnum == MemOp.STORE && dut.uop2.srcBReg.toInt == T1)
      assert((dut.uop2.imm.toLong & 0xffffffffL) == 0x20L, "store disp = load disp (same EA)")
    }
  }

  // ── MOVEP (dynamic mode 001) -> NOT a bit-op (illegal/deferred) ─────────────────
  // MOVEP 0x0108 (0000 000 1 00 001 000, mode 001): must NOT decode as BITOP.
  test("MOVEP 0x0108 (dynamic mode 001) -> NOT BITOP (illegal/deferred)", VerilatorTest) {
    run { dut => drive(dut, 0x0108); sleep(1)
      assert(dut.uop0.op.toEnum != DecOp.BITOP, "MOVEP is not a bit-op")
      assert(dut.uop0.unimplemented.toBoolean, "MOVEP -> illegal/deferred")
    }
  }

  // ── BSET #n,(A0)+ post-inc -> deferred (MEMCOMPLEX), illegal ───────────────────
  // static BSET = 0000 1000 11 mmmrrr; (A0)+ = mode3 reg0 -> 0000 1000 11 011 000 = 0x08D8.
  test("BSET #n,(A0)+ (post-inc) -> illegal (MEMCOMPLEX deferred)", VerilatorTest) {
    run { dut => drive(dut, 0x08D8, 0x0001, len = 2); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean, "post-inc bit-op mem-dest stays illegal")
    }
  }
}
