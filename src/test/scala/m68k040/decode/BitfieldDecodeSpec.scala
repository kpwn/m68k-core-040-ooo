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

  // ── Deferred: dynamic offset (Do=1) -> illegal ──────────────────────────────
  test("BFTST dynamic offset (Do=1) -> illegal/unimplemented", VerilatorTest) {
    run { dut => drive(dut, opw(0, 0), ext(4, 8, doBit = true)); sleep(1)
      assert(dut.uop.unimplemented.toBoolean, "Do=1 dynamic form deferred")
    }
  }
  // ── Deferred: dynamic width (Dw=1) -> illegal ───────────────────────────────
  test("BFEXTU dynamic width (Dw=1) -> illegal/unimplemented", VerilatorTest) {
    run { dut => drive(dut, opw(1, 0), ext(0, 4, dn2 = 1, dwBit = true)); sleep(1)
      assert(dut.uop.unimplemented.toBoolean, "Dw=1 dynamic form deferred")
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
