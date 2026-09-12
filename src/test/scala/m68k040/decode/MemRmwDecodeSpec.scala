package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, MemOp, Size}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Memory-destination RMW crack (MEMSIMPLE EAs): an ALU/unary op with a memory
  * destination cracks into [load.sz <ea> -> T0] + [op (T0 with Dn/#imm) -> T1 +
  * flags] + [store.sz T1 -> <ea>]. TST = load+flags (no store); CLR = op-0+store
  * (no load). MEMSIMPLE only; MEMCOMPLEX / An / #imm dest stay illegal. */
class MemRmwDecodeSpec extends AnyFunSuite {
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
  // drive with explicit extension words (words(1..4)), for ops where the EA ext follows
  // the immediate (so we can place a disp at words(3)/(4)).
  def drive2(dut: Dut, op: Int, ext: Seq[Int], len: Int): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op
    for (i <- 1 to 4) dut.pkt.words(i) #= (if (i - 1 < ext.length) ext(i - 1) else 0)
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // helper: assert a [load -> T0][op -> T1][store T1 -> ea] triple
  def assertRmwTriple(dut: Dut, base: Int, baseValid: Boolean, disp: Long,
                      sz: Size.E, expOp: DecOp.E, opSrcReg: Int): Unit = {
    assert(dut.count.toInt == 3, s"RMW = 3 µops, got ${dut.count.toInt}")
    // load
    assert(dut.uop0.cluster.toEnum == Cluster.LS && dut.uop0.memOp.toEnum == MemOp.LOAD, "uop0 = LOAD")
    assert(dut.uop0.srcAValid.toBoolean == baseValid, "load baseValid")
    if (baseValid) assert(dut.uop0.srcAReg.toInt == base, s"load base $base")
    assert((dut.uop0.imm.toLong & 0xffffffffL) == (disp & 0xffffffffL),
      s"load disp 0x${disp.toHexString} got 0x${(dut.uop0.imm.toLong & 0xffffffffL).toHexString}")
    assert(dut.uop0.dstReg.toInt == T0 && dut.uop0.dstValid.toBoolean, "load dst = T0")
    assert(dut.uop0.size.toEnum == sz, "load size")
    assert(dut.uop0.firstOfInstr.toBoolean, "load is firstOfInstr")
    assert(!dut.uop0.unimplemented.toBoolean)
    // op -> T1
    assert(dut.uop1.op.toEnum == expOp, s"uop1 op = $expOp")
    assert(dut.uop1.dstReg.toInt == T1 && dut.uop1.dstValid.toBoolean, "op dst = T1")
    assert(dut.uop1.size.toEnum == sz, "op size")
    assert(!dut.uop1.firstOfInstr.toBoolean, "op not firstOfInstr")
    assert(!dut.uop1.unimplemented.toBoolean)
    assert(dut.uop1.writesNzvc.toBoolean, "op writes flags")
    // store T1 -> ea
    assert(dut.uop2.cluster.toEnum == Cluster.LS && dut.uop2.memOp.toEnum == MemOp.STORE, "uop2 = STORE")
    assert(dut.uop2.srcAValid.toBoolean == baseValid, "store baseValid")
    if (baseValid) assert(dut.uop2.srcAReg.toInt == base, s"store base $base")
    assert((dut.uop2.imm.toLong & 0xffffffffL) == (disp & 0xffffffffL), "store disp = load disp (same EA)")
    assert(dut.uop2.srcBReg.toInt == T1 && dut.uop2.srcBValid.toBoolean, "store data = T1")
    assert(!dut.uop2.dstValid.toBoolean, "store writes no int reg")
    assert(!dut.uop2.writesNzvc.toBoolean, "store sets no flags (op owns them)")
    assert(dut.uop2.size.toEnum == sz, "store size")
    assert(!dut.uop2.firstOfInstr.toBoolean, "store not firstOfInstr")
    assert(!dut.uop2.unimplemented.toBoolean)
  }

  // ── ALU Dn,<ea> RMW (line 8/9/B(EOR)/C/D opmode 4/5/6) ──────────────────────
  test("ADD.L D1,(A0) -> load->T0, ADD(D1,T0)->T1, store T1->(A0)", VerilatorTest) {
    // ADD.L D1,(A0): 1101 001 110 010 000 = 0xD390 (line D, opmode 6 = .L mem-dest)
    run { dut => drive(dut, 0xD390); sleep(1)
      assertRmwTriple(dut, base = 8, baseValid = true, disp = 0, sz = Size.LONG, expOp = DecOp.ADD, opSrcReg = 1)
      // ADD reads Dn (D1) as the other operand + T0 (loaded mem)
      val reads1 = dut.uop1.srcAReg.toInt == 1 || dut.uop1.srcBReg.toInt == 1
      val readsT0 = dut.uop1.srcAReg.toInt == T0 || dut.uop1.srcBReg.toInt == T0
      assert(reads1 && readsT0, "ADD reads D1 and T0")
      assert(dut.uop1.writesX.toBoolean, "ADD writes X")
    }
  }

  test("AND.B D2,(d16,A1) -> RMW triple with disp + .B size", VerilatorTest) {
    // AND.B D2,(d16,A1): line C, opmode 4 (.B mem-dest), EA (d16,A1)=mode5 reg1.
    // 1100 010 100 101 001 = 0xC529, ext = disp16
    run { dut => drive(dut, 0xC529, 0x0012, len = 2); sleep(1)
      assertRmwTriple(dut, base = 9, baseValid = true, disp = 0x12, sz = Size.BYTE, expOp = DecOp.AND, opSrcReg = 2)
      assert(!dut.uop1.writesX.toBoolean, "AND does not write X")
    }
  }

  test("EOR.W D3,(A2) -> RMW triple (EOR mem-dest)", VerilatorTest) {
    // EOR.W D3,(A2): line B, opmode 5 (.W), EA (A2)=mode2 reg2.
    // 1011 011 101 010 010 = 0xB752
    run { dut => drive(dut, 0xB752); sleep(1)
      assertRmwTriple(dut, base = 10, baseValid = true, disp = 0, sz = Size.WORD, expOp = DecOp.EOR, opSrcReg = 3)
    }
  }

  test("SUB.L D0,(xxx).W -> RMW triple, base=0 abs disp", VerilatorTest) {
    // SUB.L D0,(xxx).W: line 9, opmode 6 (.L), EA mode7 reg0. 1001 000 110 111 000 = 0x91B8
    run { dut => drive(dut, 0x91B8, 0x0400, len = 2); sleep(1)
      assertRmwTriple(dut, base = 0, baseValid = false, disp = 0x400, sz = Size.LONG, expOp = DecOp.SUB, opSrcReg = 0)
    }
  }

  // ── line-4 unary mem-dest ───────────────────────────────────────────────────
  test("NEG.L (A0) -> load->T0, NEG(T0)->T1, store T1->(A0)", VerilatorTest) {
    // NEG.L (A0): 0100 0100 10 010 000 = 0x4490
    run { dut => drive(dut, 0x4490); sleep(1)
      assertRmwTriple(dut, base = 8, baseValid = true, disp = 0, sz = Size.LONG, expOp = DecOp.NEG, opSrcReg = -1)
    }
  }

  test("NOT.W (d16,A1) -> RMW triple", VerilatorTest) {
    // NOT.W (d16,A1): 0100 0110 01 101 001 = 0x4669, ext disp16
    run { dut => drive(dut, 0x4669, 0x0008, len = 2); sleep(1)
      assertRmwTriple(dut, base = 9, baseValid = true, disp = 0x8, sz = Size.WORD, expOp = DecOp.NOT, opSrcReg = -1)
    }
  }

  test("NEGX.B (A2) -> RMW triple", VerilatorTest) {
    // NEGX.B (A2): 0100 0000 00 010 010 = 0x4012
    run { dut => drive(dut, 0x4012); sleep(1)
      assertRmwTriple(dut, base = 10, baseValid = true, disp = 0, sz = Size.BYTE, expOp = DecOp.NEGX, opSrcReg = -1)
      assert(dut.uop1.readsX.toBoolean, "NEGX reads X")
    }
  }

  // ── TST mem -> load + flags, NO store (count 2) ─────────────────────────────
  test("TST.L (A0) -> load->T0 + flags, NO store (count 2)", VerilatorTest) {
    // TST.L (A0): 0100 1010 10 010 000 = 0x4A90
    run { dut => drive(dut, 0x4A90); sleep(1)
      assert(dut.count.toInt == 2, s"TST mem = load + op (no store), got ${dut.count.toInt}")
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD && dut.uop0.dstReg.toInt == T0)
      assert(dut.uop1.op.toEnum == DecOp.TST && dut.uop1.writesNzvc.toBoolean)
      assert(!dut.uop1.dstValid.toBoolean, "TST writes no register")
      assert(dut.uop1.memOp.toEnum == MemOp.NONE, "no store µop for TST")
      assert(!dut.uop1.unimplemented.toBoolean)
    }
  }

  // ── CLR mem -> store 0 (no load), Z=1/N=0 ──────────────────────────────────
  test("CLR.L (A0) -> op (CLR)->T1=0 + store T1->(A0), no load (count 2)", VerilatorTest) {
    // CLR.L (A0): 0100 0010 10 010 000 = 0x4290
    run { dut => drive(dut, 0x4290); sleep(1)
      assert(dut.count.toInt == 2, s"CLR mem = op(0) + store (no load), got ${dut.count.toInt}")
      // uop0 = CLR op -> T1
      assert(dut.uop0.op.toEnum == DecOp.CLR, "uop0 = CLR")
      assert(dut.uop0.dstReg.toInt == T1 && dut.uop0.dstValid.toBoolean, "CLR dst = T1")
      assert(dut.uop0.writesNzvc.toBoolean, "CLR sets flags (Z=1,N=0)")
      assert(dut.uop0.firstOfInstr.toBoolean, "CLR op is firstOfInstr (no load)")
      assert(!dut.uop0.unimplemented.toBoolean)
      // uop1 = store T1
      assert(dut.uop1.memOp.toEnum == MemOp.STORE && dut.uop1.srcBReg.toInt == T1)
      assert(dut.uop1.srcAReg.toInt == 8 && dut.uop1.srcAValid.toBoolean, "store base = A0")
      assert(!dut.uop1.writesNzvc.toBoolean, "store sets no flags")
      assert(!dut.uop1.unimplemented.toBoolean)
    }
  }

  // ── line-0 immediates #imm,<ea> (the imm precedes the EA ext) ──────────────
  test("ADDI.W #0x1234,(A0) -> load->T0, ADD(T0,#imm)->T1, store T1->(A0)", VerilatorTest) {
    // ADDI.W #imm,(A0): 0000 011 0 01 010 000 = 0x0650, imm word = words(1)
    run { dut => drive(dut, 0x0650, 0x1234, len = 2); sleep(1)
      assertRmwTriple(dut, base = 8, baseValid = true, disp = 0, sz = Size.WORD, expOp = DecOp.ADD, opSrcReg = -1)
      assert(dut.uop1.useImm.toBoolean && (dut.uop1.imm.toLong & 0xffff) == 0x1234, "ADD uses #imm")
      assert(dut.uop1.writesX.toBoolean, "ADDI writes X")
    }
  }

  test("ANDI.L #imm,(d16,A1) -> RMW triple; EA disp16 follows the imm32 (right offset)", VerilatorTest) {
    // ANDI.L #imm,(d16,A1): 0000 001 0 10 101 001 = 0x02A9, imm32 = words(1..2), disp16 = words(3)
    run { dut => drive2(dut, 0x02A9, Seq(0x1111, 0x2222, 0x0030), len = 4); sleep(1)
      assert(dut.count.toInt == 3)
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD && dut.uop0.srcAReg.toInt == 9)
      assert((dut.uop0.imm.toLong & 0xffffffffL) == 0x30L, "load disp16 = 0x30 (the word AFTER imm32)")
      assert(dut.uop1.op.toEnum == DecOp.AND && dut.uop1.dstReg.toInt == T1)
      assert((dut.uop1.imm.toLong & 0xffffffffL) == 0x11112222L, "AND uses imm32 (words 1..2)")
      assert(dut.uop2.memOp.toEnum == MemOp.STORE && dut.uop2.srcBReg.toInt == T1)
      assert((dut.uop2.imm.toLong & 0xffffffffL) == 0x30L, "store disp = load disp (same EA)")
    }
  }

  test("EORI.B #imm,(xxx).W -> RMW triple, base=0", VerilatorTest) {
    // EORI.B #imm,(xxx).W: 0000 101 0 00 111 000 = 0x0A38, imm.B = words(1), abs16 = words(2)
    run { dut => drive(dut, 0x0A38, 0x0055, 0x3000, len = 3); sleep(1)
      assert(dut.count.toInt == 3)
      assert(!dut.uop0.srcAValid.toBoolean, "(xxx).W needs no base")
      assert((dut.uop0.imm.toLong & 0xffffffffL) == 0x3000L, "load abs = 0x3000")
      assert(dut.uop1.op.toEnum == DecOp.EOR && (dut.uop1.imm.toLong & 0xff) == 0x55)
      assert((dut.uop2.imm.toLong & 0xffffffffL) == 0x3000L, "store abs = load abs (same EA)")
    }
  }

  // ── ADDQ/SUBQ #n,<ea> ──────────────────────────────────────────────────────
  test("ADDQ.L #3,(A0) -> load->T0, ADD(T0,#3)->T1, store T1->(A0)", VerilatorTest) {
    // ADDQ.L #3,(A0): 0101 011 0 10 010 000 = 0x5690 (ddd=3, q=0=ADDQ, ss=.L)
    run { dut => drive(dut, 0x5690); sleep(1)
      assertRmwTriple(dut, base = 8, baseValid = true, disp = 0, sz = Size.LONG, expOp = DecOp.ADD, opSrcReg = -1)
      assert(dut.uop1.useImm.toBoolean && dut.uop1.imm.toLong == 3, "ADDQ #3")
    }
  }

  test("SUBQ.W #8,(d16,A1) -> RMW triple, ddd=0 -> 8", VerilatorTest) {
    // SUBQ.W #8,(d16,A1): 0101 000 1 01 101 001 = 0x5169 (ddd=0->8, q=1=SUBQ, ss=.W)
    run { dut => drive(dut, 0x5169, 0x000A, len = 2); sleep(1)
      assertRmwTriple(dut, base = 9, baseValid = true, disp = 0xA, sz = Size.WORD, expOp = DecOp.SUB, opSrcReg = -1)
      assert(dut.uop1.imm.toLong == 8, "SUBQ ddd=0 -> 8")
    }
  }

  // ── CMPI #imm,<ea> = load + compare, NO store ──────────────────────────────
  test("CMPI.L #imm,(A0) -> load->T0 + compare (NO store, no write)", VerilatorTest) {
    // CMPI.L #imm,(A0): 0000 110 0 10 010 000 = 0x0C90, imm32 = words(1..2)
    run { dut => drive(dut, 0x0C90, 0x0000, 0x0001, len = 3); sleep(1)
      assert(dut.count.toInt == 2, s"CMPI mem = load + compare (no store), got ${dut.count.toInt}")
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD && dut.uop0.dstReg.toInt == T0)
      assert(dut.uop1.op.toEnum == DecOp.CMP && dut.uop1.writesNzvc.toBoolean)
      assert(!dut.uop1.dstValid.toBoolean, "CMPI writes no register")
      assert(dut.uop1.memOp.toEnum == MemOp.NONE, "no store µop for CMPI")
      assert(dut.uop1.useImm.toBoolean && (dut.uop1.imm.toLong & 0xffffffffL) == 1L)
      assert(!dut.uop1.unimplemented.toBoolean)
    }
  }

  // ── CMP <ea>,Dn (mem SOURCE) = load + compare, no store (already supported) ──
  test("CMP.L (A0),D1 (mem source) -> load->T0 + compare, no store", VerilatorTest) {
    // CMP.L (A0),D1: line B, opmode 2 (.L EA->Dn). 1011 001 010 010 000 = 0xB290
    run { dut => drive(dut, 0xB290); sleep(1)
      assert(dut.count.toInt == 2, "CMP mem-source = load + compare")
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD)
      assert(dut.uop1.op.toEnum == DecOp.CMP && !dut.uop1.dstValid.toBoolean && dut.uop1.writesNzvc.toBoolean)
      assert(dut.uop1.memOp.toEnum == MemOp.NONE)
    }
  }

  // ── auto-update (-(An)/(An)+) mem-dest RMW (now in scope) ───────────────────
  // Like a plain mem-dest RMW [load -> T0][op (T0,Dn) -> T1][store T1 -> ea] but the
  // load + store both carry the SAME eaAuto (so they hit the SAME address) and the STORE
  // (the lone int-writer of the triple) folds the An update on its int dst (dst = An).
  def assertAutoRmwTriple(dut: Dut, an: Int, auto: EaAuto.E, delta: Int,
                          sz: Size.E, expOp: DecOp.E): Unit = {
    assert(dut.count.toInt == 3, s"auto-RMW = 3 µops, got ${dut.count.toInt}")
    // load <ea> -> T0, with the auto-update marker (the load itself writes no int reg).
    assert(dut.uop0.cluster.toEnum == Cluster.LS && dut.uop0.memOp.toEnum == MemOp.LOAD, "uop0 = LOAD")
    assert(dut.uop0.srcAReg.toInt == an && dut.uop0.srcAValid.toBoolean, s"load base = A$an")
    assert(dut.uop0.eaAuto.toEnum == auto && dut.uop0.eaDelta.toInt == delta, "load carries the auto-update")
    assert(dut.uop0.dstReg.toInt == T0 && dut.uop0.dstValid.toBoolean, "load dst = T0")
    assert(dut.uop0.size.toEnum == sz, "load size")
    assert(dut.uop0.firstOfInstr.toBoolean, "load is firstOfInstr")
    assert(!dut.uop0.unimplemented.toBoolean, "auto-update mem-dest is now in scope")
    // op (T0 with Dn) -> T1 + flags.
    assert(dut.uop1.op.toEnum == expOp, s"uop1 op = $expOp")
    assert(dut.uop1.dstReg.toInt == T1 && dut.uop1.dstValid.toBoolean, "op dst = T1")
    assert(dut.uop1.writesNzvc.toBoolean, "op writes flags")
    assert(!dut.uop1.unimplemented.toBoolean)
    // store T1 -> <ea>, SAME eaAuto, and the An update rides this store's int dst.
    assert(dut.uop2.cluster.toEnum == Cluster.LS && dut.uop2.memOp.toEnum == MemOp.STORE, "uop2 = STORE")
    assert(dut.uop2.srcAReg.toInt == an && dut.uop2.srcAValid.toBoolean, s"store base = A$an")
    assert(dut.uop2.srcBReg.toInt == T1 && dut.uop2.srcBValid.toBoolean, "store data = T1")
    assert(dut.uop2.eaAuto.toEnum == auto && dut.uop2.eaDelta.toInt == delta, "store carries the SAME auto-update")
    assert(dut.uop2.dstReg.toInt == an && dut.uop2.dstValid.toBoolean, "store folds the An update on its int dst")
    assert(!dut.uop2.writesNzvc.toBoolean, "store sets no flags (op owns them)")
    assert(!dut.uop2.unimplemented.toBoolean)
  }

  test("ADD.L D1,(A0)+ (post-inc dest) -> load(A0)+ -> T0, ADD -> T1, store T1 -> (A0)+ (A0+=4)", VerilatorTest) {
    // ADD.L D1,(A0)+: line D opmode 6, EA mode 3 reg 0. 1101 001 110 011 000 = 0xD398
    run { dut => drive(dut, 0xD398); sleep(1)
      assertAutoRmwTriple(dut, an = 8, auto = EaAuto.POSTINC, delta = 4, sz = Size.LONG, expOp = DecOp.ADD)
      // ADD reads D1 (the register operand) + T0 (loaded mem); writes X.
      val reads1  = dut.uop1.srcAReg.toInt == 1 || dut.uop1.srcBReg.toInt == 1
      val readsT0 = dut.uop1.srcAReg.toInt == T0 || dut.uop1.srcBReg.toInt == T0
      assert(reads1 && readsT0, "ADD reads D1 and T0")
      assert(dut.uop1.writesX.toBoolean, "ADD writes X")
    }
  }

  test("ADD.L D1,-(A0) (pre-dec dest) -> load-(A0) -> T0, ADD -> T1, store T1 -> -(A0) (A0-=4)", VerilatorTest) {
    // ADD.L D1,-(A0): line D opmode 6, EA mode 4 reg 0. 1101 001 110 100 000 = 0xD3A0
    run { dut => drive(dut, 0xD3A0); sleep(1)
      assertAutoRmwTriple(dut, an = 8, auto = EaAuto.PREDEC, delta = 4, sz = Size.LONG, expOp = DecOp.ADD)
      val reads1  = dut.uop1.srcAReg.toInt == 1 || dut.uop1.srcBReg.toInt == 1
      val readsT0 = dut.uop1.srcAReg.toInt == T0 || dut.uop1.srcBReg.toInt == T0
      assert(reads1 && readsT0, "ADD reads D1 and T0")
      assert(dut.uop1.writesX.toBoolean, "ADD writes X")
    }
  }

  test("NEG.L (d8,A0,D0) indexed dest -> load->T0, NEG->T1, store T1 (index on srcC)", VerilatorTest) {
    // NEG.L (d8,A0,Xn): 0100 0100 10 110 000 = 0x44B0; ext 0x0000 = Xn=D0, .W, *1, d8=0.
    // Indexed is now in scope -> the SAME load-op-store RMW crack, with the index reg on
    // srcC of BOTH the load and the store (so they recompute the SAME indexed address).
    run { dut => drive(dut, 0x44B0, 0x0000, len = 2); sleep(1)
      assertRmwTriple(dut, base = 8, baseValid = true, disp = 0, sz = Size.LONG, expOp = DecOp.NEG, opSrcReg = -1)
      // index reg D0 (srcC) on both the load and the store; .W / scale *1.
      assert(dut.uop0.srcCReg.toInt == 0 && dut.uop0.srcCValid.toBoolean, "load index = D0 (srcC)")
      assert(dut.uop2.srcCReg.toInt == 0 && dut.uop2.srcCValid.toBoolean, "store index = D0 (srcC)")
      assert(!dut.uop0.indexLong.toBoolean && dut.uop0.indexScale.toInt == 0, "load .W / scale *1")
      assert(!dut.uop2.indexLong.toBoolean && dut.uop2.indexScale.toInt == 0, "store .W / scale *1")
    }
  }

  // ── .L-immediate + FULL-FORMAT dst EA: gated ILLEGAL (silent-corruption hole) ──
  // A line-0 .L-immediate op with a full-format indexed dst EA (mode 6 / 7-3, ext
  // bit8=1) puts the EA's first ext word at op+3 — beyond the per-word predecode window
  // (op+1/op+2) — so predecode frames it BRIEF (too short) and the FOLLOWING instr
  // mis-fetches. Both classifications (no-mem-indirect MEMSIMPLE-full AND mem-indirect)
  // are gated ILLEGAL (vector 4) here rather than silently corrupting. (Musashi executes
  // it; this is a documented, NON-silent illegal-trap gap — NOT a lock-step assertion.)
  def assertIllegalVec4(dut: Dut): Unit = {
    assert(dut.count.toInt == 1, s"illegal = 1 µop, got ${dut.count.toInt}")
    assert(dut.uop0.op.toEnum == DecOp.ILLEGAL, "op = ILLEGAL")
    assert(dut.uop0.unimplemented.toBoolean, "unimplemented")
    assert(dut.uop0.faulted.toBoolean, "faulted")
    assert(dut.uop0.faultVector.toInt == 4, s"vector 4, got ${dut.uop0.faultVector.toInt}")
  }

  test("ADDI.L #imm,([0x10,A0,D1.L*4],0x20) FULL-FORMAT MEM-INDIRECT dst -> ILLEGAL (vector 4)", VerilatorTest) {
    // Same opword 0x06B0 (ADDI.L mode6 reg0). full-format ext at words(3): bit8=1, Xn=D1,
    // .L, *4, bd-size=word(2), I/IS=010 (pre-index, word od) -> MEMINDIRECT. ext = 0x1B22.
    // bd.w = words(4) = 0x0010, od.w = words(5) = 0x0020. imm32 = words(1..2).
    run { dut => drive2(dut, 0x06B0, Seq(0x1111, 0x2222, 0x1B22, 0x0010), len = 6)
      dut.pkt.words(5) #= 0x0020; sleep(1)
      assertIllegalVec4(dut)
    }
  }

  // The .B/.W immediate full-format dst forms are FINE (EA ext at op+2, visible to
  // predecode) — they must NOT be gated illegal. ADDI.W #imm,(8,A0,D1.W*1) no-mem-indir:
  //   0000 0110 01 110 000 = 0x0670, imm.W = words(1), full-ext at words(2): bit8=1,
  //   Xn=D1, .W, *1, bd-size=word(2), I/IS=000 -> MEMSIMPLE-full RMW (NOT illegal).
  //   ext = (1<<12)|(1<<8)|(2<<4) = 0x1120, bd.w = words(3) = 0x0008.
  test(".W immediate full-format dst stays a legal RMW (NOT gated illegal)", VerilatorTest) {
    run { dut => drive2(dut, 0x0670, Seq(0x1234, 0x1120, 0x0008), len = 4); sleep(1)
      assert(dut.count.toInt == 3, "RMW triple (legal)")
      assert(!dut.uop0.unimplemented.toBoolean, "load NOT unimplemented")
      assert(!dut.uop1.unimplemented.toBoolean, "op NOT unimplemented")
      assert(dut.uop1.op.toEnum == DecOp.ADD, "ADDI -> ADD")
    }
  }
}
