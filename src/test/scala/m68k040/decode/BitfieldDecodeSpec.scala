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

  // OperationDecoder spec DUT (the microcoded routing + illegal split live HERE for the
  // bit-field RMW, since the assembler only emits a benign placeholder for a microcoded op).
  class SpecDut extends Component {
    val opword     = in(Bits(16 bits))
    val s          = OperationDecoder.decode(opword)
    val microcoded = out(Bool());      microcoded := s.microcoded
    val ucEntry    = out(UInt(5 bits)); ucEntry := s.ucEntry
    val illegal    = out(Bool());      illegal := s.illegal
    val bfOp       = out(Bits(3 bits)); bfOp := s.bfOp
    val op         = out(DecOp());      op := s.op
  }
  def runSpec(check: SpecDut => Unit): Unit = SimConfig.withVerilator.compile(new SpecDut).doSim(check)

  // PredecodeWord framing DUT (length only).
  class PreDut extends Component {
    val w0 = in(Bits(16 bits))
    val r  = m68k040.frontend.PredecodeWord.classify(w0)
    val simple   = out(Bool());        simple := r.simple
    val lenWords = out(UInt(3 bits));  lenWords := r.lenWords
  }
  def runPre(check: PreDut => Unit): Unit = SimConfig.withVerilator.compile(new PreDut).doSim(check)

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
  // ── Slice 3b: memory RMW form (mode>=2 control-alterable) -> microcoded ──────
  test("BFCLR memory form (mode=2 (An)) -> microcoded BITFIELD (slice 3b)", VerilatorTest) {
    runSpec { dut => // mode 010 (An indirect), reg 2: opword 1110 1100 11 010 010 (bfOp=4)
      dut.opword #= (0xE000 | (1 << 11) | (4 << 8) | (3 << 6) | (2 << 3) | 2); sleep(1)
      assert(dut.microcoded.toBoolean, "BFCLR (An) is microcoded")
      assert(!dut.illegal.toBoolean)
      assert(dut.op.toEnum == DecOp.BITFIELD && dut.bfOp.toInt == 4)
    }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // MEMORY load-only forms (slice 3a): BFTST/BFEXTU/BFEXTS/BFFFO at a memory EA.
  // Crack: [load.L byteAddr -> T0] (opt [load.B byteAddr+4 -> T1]) [BITFIELD bfMem].
  // ════════════════════════════════════════════════════════════════════════════
  val T0 = 16; val T1 = 17
  // opword 1110 1ooo 11 mmm rrr (memory EA mode/reg).
  def opwMem(bfOp: Int, mode: Int, reg: Int): Int =
    0xE000 | (1 << 11) | (bfOp << 8) | (3 << 6) | (mode << 3) | reg
  // drive with an explicit lenWords (mem forms can be 2-4 words).
  def driveMem(dut: Dut, op: Int, ext: Int, w2: Int = 0, len: Int = 2): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= ext; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }

  // ── BFTST (A0){#3:#8}: bfOp 0, (An) mode 2 -> [load.L T0][BITFIELD bfMem, no dst] ──
  test("BFTST mem (An) {#3:#8} -> load.L + BITFIELD bfMem, no dst", VerilatorTest) {
    run { dut => driveMem(dut, opwMem(0, 2, 0), ext(3, 8)); sleep(1)
      assert(dut.count.toInt == 2, "bitOff=3,width=8 -> bitOff+width=11<=32 -> 2 µops")
      // µop0 = LOAD.L (An=A0) -> T0
      assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.memOp.toEnum == m68k040.isa.MemOp.LOAD)
      assert(dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.srcAReg.toInt == 8 && dut.uop.srcAValid.toBoolean, "base A0")
      assert(dut.uop.dstReg.toInt == T0 && dut.uop.dstValid.toBoolean)
      assert((dut.uop.imm.toLong & 0xffffffffL) == 0, "offset 3 >> 3 = 0 byte fold, (An) disp 0")
      // µop1 = BITFIELD bfMem, srcA=T0, no T1 (needHi false), no dst (BFTST)
      assert(dut.uop1.op.toEnum == DecOp.BITFIELD && dut.uop1.bfOp.toInt == 0)
      assert(dut.uop1.bfMem.toBoolean, "compute µop flagged bfMem")
      assert(dut.uop1.srcAReg.toInt == T0 && dut.uop1.srcAValid.toBoolean, "srcA = T0 (lo)")
      assert(!dut.uop1.srcBValid.toBoolean, "needHi false -> no T1")
      assert(!dut.uop1.dstValid.toBoolean, "BFTST writes no reg")
      assert(dut.uop1.writesNzvc.toBoolean && !dut.uop1.writesX.toBoolean)
      // imm packing: rotate offset (imm[4:0]) = 0; rawWidth (imm[9:5]) = 8; bitOff
      // (imm[12:10]) = 3; needHi (imm[13]) = 0; origOffset (imm[18:14]) = 3.
      val imm = dut.uop1.imm.toLong
      assert((imm & 0x1f) == 0, "rotate offset 0")
      assert(((imm >> 5) & 0x1f) == 8, "rawWidth 8")
      assert(((imm >> 10) & 0x7) == 3, "bitOff 3")
      assert(((imm >> 13) & 0x1) == 0, "needHi 0")
      assert(((imm >> 14) & 0x1f) == 3, "origOffset 3")
      assert(!dut.uop1.unimplemented.toBoolean)
    }
  }
  // ── BFEXTU (A1){#4:#12},D1: bfOp 1, dst = Dn2 = D1 ──────────────────────────
  test("BFEXTU mem (An) {#4:#12},D1 -> dst Dn2", VerilatorTest) {
    run { dut => driveMem(dut, opwMem(1, 2, 1), ext(4, 12, dn2 = 1)); sleep(1)
      assert(dut.count.toInt == 2, "bitOff=4,width=12 -> 16<=32")
      assert(dut.uop.srcAReg.toInt == 9, "base A1")
      assert(dut.uop1.op.toEnum == DecOp.BITFIELD && dut.uop1.bfOp.toInt == 1 && dut.uop1.bfMem.toBoolean)
      assert(dut.uop1.dstReg.toInt == 1 && dut.uop1.dstValid.toBoolean, "dst Dn2 = D1")
      val imm = dut.uop1.imm.toLong
      assert(((imm >> 5) & 0x1f) == 12 && ((imm >> 10) & 0x7) == 4 && ((imm >> 14) & 0x1f) == 4)
    }
  }
  // ── BFEXTS 12(A1){#7:#20},D2: (d16,An) mode 5 -> byteAddr disp folds offset>>3 ──
  test("BFEXTS mem (d16,An) {#7:#20},D2 -> disp + offset>>3, dst Dn2", VerilatorTest) {
    run { dut => // mode 5 (d16,An), reg 1; disp16 = 12 in words(2); offset 7 width 20
      driveMem(dut, opwMem(3, 5, 1), ext(7, 20, dn2 = 2), w2 = 12, len = 3); sleep(1)
      // bitOff=7, width=20 -> 27<=32 -> needHi false -> 2 µops
      assert(dut.count.toInt == 2)
      assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.size.toEnum == Size.LONG)
      assert(dut.uop.srcAReg.toInt == 9, "base A1")
      // byteAddr disp = 12 + (7>>3=0) = 12
      assert((dut.uop.imm.toLong & 0xffffffffL) == 12, "disp 12 + offset>>3(0)")
      assert(dut.uop1.op.toEnum == DecOp.BITFIELD && dut.uop1.bfOp.toInt == 3 && dut.uop1.bfMem.toBoolean)
      assert(dut.uop1.dstReg.toInt == 2 && dut.uop1.dstValid.toBoolean)
      val imm = dut.uop1.imm.toLong
      assert(((imm >> 10) & 0x7) == 7 && ((imm >> 13) & 1) == 0, "bitOff 7, needHi 0")
    }
  }
  // ── BFFFO (A2){#1:#9},D3: bfOp 5, dst Dn2 = D3 ──────────────────────────────
  test("BFFFO mem (An) {#1:#9},D3 -> dst Dn2", VerilatorTest) {
    run { dut => driveMem(dut, opwMem(5, 2, 2), ext(1, 9, dn2 = 3)); sleep(1)
      assert(dut.count.toInt == 2)
      assert(dut.uop.srcAReg.toInt == 10, "base A2")
      assert(dut.uop1.op.toEnum == DecOp.BITFIELD && dut.uop1.bfOp.toInt == 5 && dut.uop1.bfMem.toBoolean)
      assert(dut.uop1.dstReg.toInt == 3 && dut.uop1.dstValid.toBoolean)
      assert(((dut.uop1.imm.toLong >> 14) & 0x1f) == 1, "origOffset 1 (the FFO base)")
    }
  }
  // ── 5-BYTE SPAN: offset=7, width=28 -> bitOff=7, bitOff+width=35>32 -> needHi -> 3 µops ──
  test("BFEXTU mem 5-byte span (offset 7, width 28) -> 3 µops (load.L + load.B + compute)", VerilatorTest) {
    run { dut => driveMem(dut, opwMem(1, 2, 0), ext(7, 28, dn2 = 4)); sleep(1)
      assert(dut.count.toInt == 3, "bitOff+width=35>32 -> spill byte load")
      // µop0 = load.L T0 (disp = offset>>3 = 0)
      assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.size.toEnum == Size.LONG && dut.uop.dstReg.toInt == T0)
      assert((dut.uop.imm.toLong & 0xffffffffL) == 0)
      // µop1 = load.B T1 at byteAddr+4 (disp = 0+4 = 4)
      assert(dut.uop1.op.toEnum == DecOp.MOVE && dut.uop1.memOp.toEnum == m68k040.isa.MemOp.LOAD)
      assert(dut.uop1.size.toEnum == Size.BYTE && dut.uop1.dstReg.toInt == T1)
      assert((dut.uop1.imm.toLong & 0xffffffffL) == 4, "byteAddr + 4")
    }
  }
  // ── width 32 (encoded 0): bfextu (A0){#0:#0},D1 -> width 32 raw 0 carried ─────
  test("BFEXTU mem width-32 (raw 0) {#0:#0} -> 2 µops, rawWidth 0 in imm", VerilatorTest) {
    run { dut => driveMem(dut, opwMem(1, 2, 0), ext(0, 0, dn2 = 1)); sleep(1)
      // offset 0 -> bitOff 0; width 32, bitOff+width=32 (NOT >32) -> needHi false -> 2 µops
      assert(dut.count.toInt == 2)
      assert(((dut.uop1.imm.toLong >> 5) & 0x1f) == 0, "rawWidth 0 (=32) carried")
      assert(((dut.uop1.imm.toLong >> 13) & 1) == 0, "needHi false (bitOff 0)")
    }
  }
  // ── (xxx).L abs (mode 7 reg 1): 2 ext words -> 4-word instruction ────────────
  test("BFTST mem (xxx).L {#0:#16} -> abs.L base folded, 2 µops", VerilatorTest) {
    run { dut => // mode 7 reg 1 (abs.L); abs in words(2)/words(3)
      driveMem(dut, opwMem(0, 7, 1), ext(0, 16), w2 = 0x0001, len = 4); sleep(1)
      assert(dut.count.toInt == 2)
      assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.size.toEnum == Size.LONG)
      assert(!dut.uop.srcAValid.toBoolean, "abs.L: no base reg (folded into disp)")
      assert(dut.uop1.bfMem.toBoolean && dut.uop1.bfOp.toInt == 0)
    }
  }
  // ── (An)+ is NOT valid for bit-fields -> illegal ─────────────────────────────
  test("BFTST mem (An)+ (mode 3) -> illegal (postinc invalid for bit-fields)", VerilatorTest) {
    run { dut => driveMem(dut, opwMem(0, 3, 0), ext(0, 8)); sleep(1)
      assert(dut.uop.unimplemented.toBoolean, "(An)+ not a control EA -> illegal")
      assert(dut.uop.faulted.toBoolean && dut.uop.faultVector.toInt == 4)
    }
  }
  // ── -(An) is NOT valid for bit-fields -> illegal ─────────────────────────────
  test("BFEXTU mem -(An) (mode 4) -> illegal (predec invalid for bit-fields)", VerilatorTest) {
    run { dut => driveMem(dut, opwMem(1, 4, 0), ext(0, 8, dn2 = 1)); sleep(1)
      assert(dut.uop.unimplemented.toBoolean, "-(An) not a control EA -> illegal")
    }
  }
  // ════════════════════════════════════════════════════════════════════════════
  // Slice 3b: memory RMW (BFCHG/BFCLR/BFSET/BFINS) decode — microcoded via the engine.
  // The microcoded routing + illegal split live in OperationDecoder (the assembler emits
  // a benign placeholder for a microcoded op). The 4B-vs-5B entry is picked in DecodeStage
  // ucBegin from needHi, NOT here (OperationDecoder is ext-word-free -> emits the 4B entry).
  // ════════════════════════════════════════════════════════════════════════════
  // All 4 RMW ops x each control-alterable mode -> microcoded + correct bfOp.
  for ((bfOp, name) <- Seq(2 -> "BFCHG", 4 -> "BFCLR", 6 -> "BFSET", 7 -> "BFINS")) {
    for ((mode, reg, ea) <- Seq((2, 0, "(An)"), (5, 0, "(d16,An)"), (6, 0, "(d8,An,Xn)"),
                                (7, 0, "(xxx).W"), (7, 1, "(xxx).L"))) {
      test(s"$bfOp $name $ea -> microcoded BITFIELD, default 4B entry", VerilatorTest) {
        runSpec { dut =>
          dut.opword #= (0xE000 | (1 << 11) | (bfOp << 8) | (3 << 6) | (mode << 3) | reg); sleep(1)
          assert(dut.microcoded.toBoolean, s"$name $ea microcoded")
          assert(!dut.illegal.toBoolean, s"$name $ea not illegal")
          assert(dut.op.toEnum == DecOp.BITFIELD && dut.bfOp.toInt == bfOp)
          assert(dut.ucEntry.toInt == Microcode.BF_RMW_4B_ENTRY, "default entry = 4B (ucBegin overrides)")
        }
      }
    }
  }
  // ILLEGAL EAs for RMW: PC-rel (7-2/7-3), (An)+ (3), -(An) (4), An (1), #imm (7-4).
  // (mode 0 is the REGISTER form BFCHG/BFINS — a different, legal instruction, NOT a
  // memory RMW — so it is NOT in the illegal-RMW set here.)
  for ((bfOp, name) <- Seq(2 -> "BFCHG", 7 -> "BFINS")) {
    for ((mode, reg, ea) <- Seq((7, 2, "(d16,PC)"), (7, 3, "(d8,PC,Xn)"), (3, 0, "(An)+"),
                                (4, 0, "-(An)"), (1, 0, "An"), (7, 4, "#imm"))) {
      test(s"$bfOp $name $ea -> ILLEGAL (not control-alterable)", VerilatorTest) {
        runSpec { dut =>
          dut.opword #= (0xE000 | (1 << 11) | (bfOp << 8) | (3 << 6) | (mode << 3) | reg); sleep(1)
          assert(dut.illegal.toBoolean, s"$name $ea illegal")
          assert(!dut.microcoded.toBoolean, s"$name $ea not microcoded")
        }
      }
    }
  }
  // Predecode framing: RMW ops now frame (slice 3a only framed load-only). Same length as
  // the load-only op at the same EA.
  test("predecode frames BFSET (d16,An) (RMW) — len = opword + bf-ext + 1 EA ext", VerilatorTest) {
    runPre { dut => dut.w0 #= (0xE000 | (1 << 11) | (6 << 8) | (3 << 6) | (5 << 3) | 0); sleep(1)
      assert(dut.simple.toBoolean, "BFSET (d16,An) framed")
      assert(dut.lenWords.toInt == 3, "opword + bf-ext + 1 disp ext")
    }
  }
  test("predecode frames BFINS (xxx).L (RMW) — len = opword + bf-ext + 2 EA ext", VerilatorTest) {
    runPre { dut => dut.w0 #= (0xE000 | (1 << 11) | (7 << 8) | (3 << 6) | (7 << 3) | 1); sleep(1)
      assert(dut.simple.toBoolean, "BFINS (xxx).L framed")
      assert(dut.lenWords.toInt == 4, "opword + bf-ext + 2 abs ext")
    }
  }
  // ── Register form (mode 0) still decodes to a single non-mem BITFIELD ────────
  test("BFEXTU register form still single µop, bfMem false (regression)", VerilatorTest) {
    run { dut => drive(dut, opw(1, 0), ext(0, 16, dn2 = 1)); sleep(1)
      assert(dut.count.toInt == 1)
      assert(dut.uop.op.toEnum == DecOp.BITFIELD && !dut.uop.bfMem.toBoolean)
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
