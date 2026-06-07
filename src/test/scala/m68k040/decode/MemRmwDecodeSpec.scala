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

  // ── still-deferred dest modes stay illegal ─────────────────────────────────
  test("ADD.L D1,(A0)+ (post-inc dest) -> illegal (MEMCOMPLEX deferred)", VerilatorTest) {
    // ADD.L D1,(A0)+: line D opmode 6, EA mode 3 reg 0. 1101 001 110 011 000 = 0xD398
    run { dut => drive(dut, 0xD398); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean, "post-inc mem-dest stays illegal")
    }
  }

  test("ADD.L D1,-(A0) (pre-dec dest) -> illegal (MEMCOMPLEX deferred)", VerilatorTest) {
    // ADD.L D1,-(A0): line D opmode 6, EA mode 4 reg 0. 1101 001 110 100 000 = 0xD3A0
    run { dut => drive(dut, 0xD3A0); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean, "pre-dec mem-dest stays illegal")
    }
  }

  test("NEG.L (d8,A0,D0) indexed dest -> illegal (MEMCOMPLEX deferred)", VerilatorTest) {
    // NEG.L (d8,A0,Xn): 0100 0100 10 110 000 = 0x44B0, ext = index word
    run { dut => drive(dut, 0x44B0, 0x0000, len = 2); sleep(1)
      assert(dut.uop0.unimplemented.toBoolean, "indexed mem-dest stays illegal")
    }
  }
}
