package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Bit-field register-form decode (BFxxx Dn{#off:#wd}, static offset/width — slice
  * 1). The 8 ops decode to a single DecOp.BITFIELD µop carrying bfOp (op[10:8]),
  * srcA = Dy (op[2:0]), srcB = Dn2 (BFINS only), dst = Dn2 (EXTU/EXTS/FFO) / Dy
  * (CHG/CLR/SET/INS) / none (TST), and the offset/width packed into imm. The DYNAMIC
  * forms (Do=ext[11] / Dw=ext[5]) + the memory forms (mode!=0) stay illegal (deferred).
  * Shifts (ss!=3) still decode to DecOp.SHIFT. */
class BitfieldDecodeSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val a   = MicroOpAssembler.assemble(pkt)
    val count = out(UInt(2 bits)); count := a.count
    val uop = out(DecodedUop()); uop := a.uops(0)
    val uop1 = out(DecodedUop()); uop1 := a.uops(1)
  }
  def drive(dut: Dut, op: Int, ext: Int): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= 2; dut.pkt.wordCount #= 2; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= ext; dut.pkt.words(2) #= 0
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // ext word: bit11=Do, [10:6]=offset, bit5=Dw, [4:0]=width, [14:12]=Dn2.
  def ext(off: Int, wd: Int, dn2: Int = 0, doBit: Boolean = false, dwBit: Boolean = false): Int =
    (dn2 << 12) | (if (doBit) (1 << 11) else 0) | ((off & 0x1f) << 6) |
      (if (dwBit) (1 << 5) else 0) | (wd & 0x1f)

  // opword 1110 1ooo 11 000 rrr. ooo (op[10:8]) = bfOp; rrr = Dy. The 020 bit-field
  // op map: 0=BFTST,1=BFEXTU,2=BFCHG,3=BFEXTS,4=BFCLR,5=BFFFO,6=BFSET,7=BFINS.
  def opw(bfOp: Int, dy: Int): Int = 0xE000 | (1 << 11) | (bfOp << 8) | (3 << 6) | dy

  // ── BFTST D0{#4:#8} (bfOp 0): srcA=D0, NO dst, NZ write ─────────────────────
  test("BFTST -> BITFIELD bfOp=0, srcA=Dy, no dst, writesNzvc (no X)", VerilatorTest) {
    run { dut => drive(dut, opw(0, 0), ext(4, 8)); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BITFIELD)
      assert(dut.uop.bfOp.toInt == 0 && dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.srcAValid.toBoolean)
      assert(!dut.uop.srcBValid.toBoolean)
      assert(!dut.uop.dstValid.toBoolean, "BFTST writes no reg")
      assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.readsX.toBoolean)
      assert(!dut.uop.unimplemented.toBoolean)
      // imm packs offset(4) + width(8): imm[4:0]=4, imm[9:5]=8.
      assert((dut.uop.imm.toLong & 0x1f) == 4 && ((dut.uop.imm.toLong >> 5) & 0x1f) == 8)
      assert(dut.uop.useImm.toBoolean)
    }
  }
  // ── BFCHG D1{#0:#16} (bfOp 2): srcA=Dy, dst=Dy ───────────────────────────────
  test("BFCHG -> BITFIELD bfOp=2, dst=Dy", VerilatorTest) {
    run { dut => drive(dut, opw(2, 1), ext(0, 16)); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BITFIELD && dut.uop.bfOp.toInt == 2)
      assert(dut.uop.srcAReg.toInt == 1 && dut.uop.dstReg.toInt == 1 && dut.uop.dstValid.toBoolean)
      assert(!dut.uop.srcBValid.toBoolean && !dut.uop.unimplemented.toBoolean)
    }
  }
  // ── BFCLR D2{#8:#8} (bfOp 4): dst=Dy ─────────────────────────────────────────
  test("BFCLR -> BITFIELD bfOp=4, dst=Dy", VerilatorTest) {
    run { dut => drive(dut, opw(4, 2), ext(8, 8)); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BITFIELD && dut.uop.bfOp.toInt == 4)
      assert(dut.uop.srcAReg.toInt == 2 && dut.uop.dstReg.toInt == 2 && dut.uop.dstValid.toBoolean)
      assert(!dut.uop.unimplemented.toBoolean)
    }
  }
  // ── BFSET D3{#31:#1} (bfOp 6): dst=Dy ────────────────────────────────────────
  test("BFSET -> BITFIELD bfOp=6, dst=Dy, width 1", VerilatorTest) {
    run { dut => drive(dut, opw(6, 3), ext(31, 1)); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BITFIELD && dut.uop.bfOp.toInt == 6)
      assert(dut.uop.srcAReg.toInt == 3 && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
      assert((dut.uop.imm.toLong & 0x1f) == 31 && ((dut.uop.imm.toLong >> 5) & 0x1f) == 1)
      assert(!dut.uop.unimplemented.toBoolean)
    }
  }
  // ── BFEXTU D0{#0:#16},D1 (bfOp 1): srcA=Dy=D0, dst=Dn2=D1 ────────────────────
  test("BFEXTU -> BITFIELD bfOp=1, srcA=Dy, dst=Dn2", VerilatorTest) {
    run { dut => drive(dut, opw(1, 0), ext(0, 16, dn2 = 1)); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BITFIELD && dut.uop.bfOp.toInt == 1)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.srcAValid.toBoolean)
      assert(!dut.uop.srcBValid.toBoolean)
      assert(dut.uop.dstReg.toInt == 1 && dut.uop.dstValid.toBoolean, "dst = Dn2")
      assert(!dut.uop.unimplemented.toBoolean)
    }
  }
  // ── BFEXTS D0{#4:#12},D2 (bfOp 3): dst=Dn2=D2 ────────────────────────────────
  test("BFEXTS -> BITFIELD bfOp=3, dst=Dn2", VerilatorTest) {
    run { dut => drive(dut, opw(3, 0), ext(4, 12, dn2 = 2)); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BITFIELD && dut.uop.bfOp.toInt == 3)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.dstReg.toInt == 2 && dut.uop.dstValid.toBoolean)
      assert(!dut.uop.unimplemented.toBoolean)
    }
  }
  // ── BFFFO D0{#0:#32},D3 (bfOp 5): dst=Dn2=D3, width 0->32 ────────────────────
  test("BFFFO -> BITFIELD bfOp=5, dst=Dn2, width-field 0", VerilatorTest) {
    run { dut => drive(dut, opw(5, 0), ext(0, 0, dn2 = 3)); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BITFIELD && dut.uop.bfOp.toInt == 5)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
      assert(((dut.uop.imm.toLong >> 5) & 0x1f) == 0, "raw width field 0 (= 32) carried")
      assert(!dut.uop.unimplemented.toBoolean)
    }
  }
  // ── BFINS D4,D0{#8:#8} (bfOp 7): srcA=Dy=D0, srcB=Dn2=D4, dst=Dy=D0 ──────────
  test("BFINS -> BITFIELD bfOp=7, srcA=Dy, srcB=Dn2, dst=Dy", VerilatorTest) {
    run { dut => drive(dut, opw(7, 0), ext(8, 8, dn2 = 4)); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BITFIELD && dut.uop.bfOp.toInt == 7)
      assert(dut.uop.srcAReg.toInt == 0 && dut.uop.srcAValid.toBoolean, "srcA = Dy")
      assert(dut.uop.srcBReg.toInt == 4 && dut.uop.srcBValid.toBoolean, "srcB = Dn2 (insert)")
      assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean, "dst = Dy")
      assert(!dut.uop.unimplemented.toBoolean)
    }
  }

  // ── Dynamic offset (Do=1): 2-µop crack [BFRESOLVE -> T0] [BITFIELD bfDynamic] ─
  // bftst %d0{%d1:#8} -> Do=1, offset-Dn = ext[8:6], static width 8.
  test("BFTST dynamic offset (Do=1) -> BFRESOLVE + BITFIELD bfDynamic crack", VerilatorTest) {
    run { dut => // offset-Dn field = 1 (ext[8:6]); width static 8
      drive(dut, opw(0, 0), (0 << 12) | (1 << 11) | (1 << 6) | 8); sleep(1)
      assert(dut.count.toInt == 2, "Do/Dw dynamic = 2-µop crack")
      // µop0 = BFRESOLVE -> T0, reads offset-Dn (srcA = D1), Do flag in imm[10].
      assert(dut.uop.op.toEnum == DecOp.BFRESOLVE, "first µop = bf-resolve")
      assert(dut.uop.srcAReg.toInt == 1 && dut.uop.srcAValid.toBoolean, "resolve reads offset-Dn = D1")
      assert(dut.uop.dstValid.toBoolean, "resolve writes T0 (temp dst)")
      assert(dut.uop.useImm.toBoolean)
      assert(((dut.uop.imm.toLong >> 10) & 1) == 1, "Do flag in imm[10]")
      assert(((dut.uop.imm.toLong >> 11) & 1) == 0, "Dw clear")
      assert(((dut.uop.imm.toLong >> 5) & 0x1f) == 8, "static width 8 in imm[9:5]")
      assert(!dut.uop.unimplemented.toBoolean)
      // µop1 = BITFIELD bfDynamic, srcA = Dy = D0, srcC = T0 (packed off/wd), no dst (TST).
      assert(dut.uop1.op.toEnum == DecOp.BITFIELD && dut.uop1.bfOp.toInt == 0)
      assert(dut.uop1.bfDynamic.toBoolean, "bit-field µop flagged dynamic")
      assert(dut.uop1.srcAReg.toInt == 0 && dut.uop1.srcAValid.toBoolean, "srcA = Dy")
      assert(dut.uop1.srcCValid.toBoolean, "srcC = T0 (packed offset/width)")
      assert(dut.uop1.srcCReg.toInt == dut.uop.dstReg.toInt, "srcC = the resolve's T0")
      assert(!dut.uop1.dstValid.toBoolean, "BFTST writes no reg")
    }
  }
  // ── Dynamic width (Dw=1): bfextu %d0{#0:%d2},%d1 ────────────────────────────
  test("BFEXTU dynamic width (Dw=1) -> BFRESOLVE(reads width-Dn) + BITFIELD bfDynamic", VerilatorTest) {
    run { dut => // static offset 0; width-Dn field = 2 (ext[2:0]); Dw=1; Dn2=1
      drive(dut, opw(1, 0), (1 << 12) | (0 << 6) | (1 << 5) | 2); sleep(1)
      assert(dut.count.toInt == 2)
      assert(dut.uop.op.toEnum == DecOp.BFRESOLVE)
      assert(dut.uop.srcBReg.toInt == 2 && dut.uop.srcBValid.toBoolean, "resolve reads width-Dn = D2 (srcB)")
      assert(((dut.uop.imm.toLong >> 11) & 1) == 1, "Dw flag in imm[11]")
      assert(((dut.uop.imm.toLong >> 10) & 1) == 0, "Do clear")
      assert(!dut.uop.unimplemented.toBoolean)
      assert(dut.uop1.op.toEnum == DecOp.BITFIELD && dut.uop1.bfOp.toInt == 1 && dut.uop1.bfDynamic.toBoolean)
      assert(dut.uop1.srcAReg.toInt == 0, "srcA = Dy")
      assert(dut.uop1.dstReg.toInt == 1 && dut.uop1.dstValid.toBoolean, "dst = Dn2")
      assert(dut.uop1.srcCValid.toBoolean)
    }
  }
  // ── BFINS dynamic both (Do=1 && Dw=1): the 3-source case (Dy + Dn2 + T0) ─────
  // bfins %d4,%d0{%d1:%d2} -> Do=1 (offset-Dn=D1), Dw=1 (width-Dn=D2), Dn2=D4, Dy=D0.
  test("BFINS dynamic both -> resolve reads off-Dn+wd-Dn; bf µop srcA=Dy,srcB=Dn2,srcC=T0", VerilatorTest) {
    run { dut =>
      drive(dut, opw(7, 0), (4 << 12) | (1 << 11) | (1 << 6) | (1 << 5) | 2); sleep(1)
      assert(dut.count.toInt == 2)
      assert(dut.uop.op.toEnum == DecOp.BFRESOLVE)
      assert(dut.uop.srcAReg.toInt == 1 && dut.uop.srcAValid.toBoolean, "off-Dn = D1")
      assert(dut.uop.srcBReg.toInt == 2 && dut.uop.srcBValid.toBoolean, "wd-Dn = D2")
      assert(((dut.uop.imm.toLong >> 10) & 3) == 3, "Do+Dw set")
      assert(dut.uop1.op.toEnum == DecOp.BITFIELD && dut.uop1.bfOp.toInt == 7 && dut.uop1.bfDynamic.toBoolean)
      assert(dut.uop1.srcAReg.toInt == 0 && dut.uop1.srcAValid.toBoolean, "srcA = Dy")
      assert(dut.uop1.srcBReg.toInt == 4 && dut.uop1.srcBValid.toBoolean, "srcB = Dn2 (insert)")
      assert(dut.uop1.srcCValid.toBoolean, "srcC = T0 (packed)")
      assert(dut.uop1.dstReg.toInt == 0 && dut.uop1.dstValid.toBoolean, "dst = Dy")
    }
  }
  // ── Static form is NOT cracked (count 1, no bfDynamic) — regression ──────────
  test("BFTST static stays a single non-dynamic µop", VerilatorTest) {
    run { dut => drive(dut, opw(0, 0), ext(4, 8)); sleep(1)
      assert(dut.count.toInt == 1)
      assert(dut.uop.op.toEnum == DecOp.BITFIELD && !dut.uop.bfDynamic.toBoolean)
    }
  }
  // ── Deferred: memory form (mode != 0) -> illegal ────────────────────────────
  test("BFCLR memory form (mode=2 (An)) -> illegal/unimplemented", VerilatorTest) {
    run { dut => // mode 010 (An indirect), reg 2: opword 1110 1010 11 010 010
      val opMem = 0xE000 | (1 << 11) | (2 << 8) | (3 << 6) | (2 << 3) | 2
      drive(dut, opMem, ext(0, 8)); sleep(1)
      assert(dut.uop.unimplemented.toBoolean, "memory bit-field deferred")
    }
  }

  // ── Shifts still decode (ss != 3) ───────────────────────────────────────────
  test("ASL.L #1,D0 (ss!=3) still decodes to SHIFT", VerilatorTest) {
    run { dut => // 1110 0011 10 000 000 = 0xE380 (asl.l #1,d0)
      drive(dut, 0xE380, 0); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.SHIFT && !dut.uop.unimplemented.toBoolean)
    }
  }
}
