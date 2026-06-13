package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, MemOp, Size}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Task 3: crack a memSimple EA SOURCE (the EASRC role) into a load µop (→ temp
  * T0) followed by the operation reading T0. */
class CrackLoadSpec extends AnyFunSuite {
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

  test("ADD.L (A0),D1 -> LOAD(base=8,dst=16) + ADD(srcB=16,dst=1)", VerilatorTest) {
    run { dut => drive(dut, 0xD290); sleep(1)   // ADD.L (A0),D1 : 1101 001 010 010 000
      assert(dut.count.toInt == 2, s"expected 2 µops, got ${dut.count.toInt}")
      // µop0 = load
      assert(dut.uop0.cluster.toEnum == Cluster.LS && dut.uop0.memOp.toEnum == MemOp.LOAD)
      assert(dut.uop0.srcAReg.toInt == 8 && dut.uop0.srcAValid.toBoolean, "load base = A0 (8)")
      assert(dut.uop0.imm.toLong == 0, "load disp = 0 for (An)")
      assert(dut.uop0.dstReg.toInt == 16 && dut.uop0.dstValid.toBoolean, "load dst = T0 (16)")
      assert(dut.uop0.size.toEnum == Size.LONG)
      assert(!dut.uop0.unimplemented.toBoolean)
      // µop1 = ADD reading T0 in srcB
      assert(dut.uop1.op.toEnum == DecOp.ADD && dut.uop1.cluster.toEnum == Cluster.INT)
      assert(dut.uop1.srcBReg.toInt == 16 && dut.uop1.srcBValid.toBoolean, "ADD srcB = T0 (16)")
      assert(dut.uop1.srcAReg.toInt == 1 && dut.uop1.srcAValid.toBoolean, "ADD srcA = D1")
      assert(dut.uop1.dstReg.toInt == 1 && dut.uop1.dstValid.toBoolean && dut.uop1.writesNzvc.toBoolean)
      assert(!dut.uop1.unimplemented.toBoolean)
    }
  }

  test("MOVE.L (d16,A0),D2 -> LOAD(base=8,disp) + MOVE reading T0", VerilatorTest) {
    // MOVE.L (d16,A0),D2 : 0010 010 000 101 000 = 0x2428, ext word = disp16
    run { dut => drive(dut, 0x2428, 0x0010, len = 2); sleep(1)
      assert(dut.count.toInt == 2, s"expected 2 µops, got ${dut.count.toInt}")
      assert(dut.uop0.cluster.toEnum == Cluster.LS && dut.uop0.memOp.toEnum == MemOp.LOAD)
      assert(dut.uop0.srcAReg.toInt == 8 && dut.uop0.srcAValid.toBoolean, "load base = A0")
      assert(dut.uop0.imm.toLong == 0x10, s"load disp = 0x10, got 0x${dut.uop0.imm.toLong.toHexString}")
      assert(dut.uop0.dstReg.toInt == 16, "load dst = T0")
      // µop1 = MOVE reading T0 in srcB (MOVE source EA -> srcB)
      assert(dut.uop1.op.toEnum == DecOp.MOVE)
      assert(dut.uop1.srcBReg.toInt == 16 && dut.uop1.srcBValid.toBoolean, "MOVE srcB = T0")
      assert(dut.uop1.dstReg.toInt == 2 && dut.uop1.dstValid.toBoolean, "MOVE dst = D2")
    }
  }

  test("(xxx).W absolute load: base=0, disp=abs", VerilatorTest) {
    // MOVE.L (xxx).W,D0 : 0010 000 000 111 000 = 0x2038, ext = abs16
    run { dut => drive(dut, 0x2038, 0x1234, len = 2); sleep(1)
      assert(dut.count.toInt == 2)
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD)
      assert(!dut.uop0.srcAValid.toBoolean, "(xxx).W needs no base register")
      assert(dut.uop0.imm.toLong == 0x1234, s"abs disp 0x1234, got 0x${dut.uop0.imm.toLong.toHexString}")
      assert(dut.uop0.dstReg.toInt == 16)
    }
  }

  test("(xxx).L absolute load: base=0, disp=32-bit abs", VerilatorTest) {
    // MOVE.L (xxx).L,D0 : 0010 000 000 111 001 = 0x2039, ext = abs32 (w1<<16|w2)
    run { dut => drive(dut, 0x2039, 0x0001, 0x2000, len = 3); sleep(1)
      assert(dut.count.toInt == 2)
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD)
      assert(!dut.uop0.srcAValid.toBoolean)
      assert((dut.uop0.imm.toLong & 0xffffffffL) == 0x00012000L,
        s"abs32 0x12000, got 0x${(dut.uop0.imm.toLong & 0xffffffffL).toHexString}")
    }
  }

  test("(d16,PC) load: base=0, disp=pc+2+d16", VerilatorTest) {
    // MOVE.L (d16,PC),D0 : 0010 000 000 111 010 = 0x203A, ext = d16
    run { dut => drive(dut, 0x203A, 0x0004, len = 2); sleep(1)
      assert(dut.count.toInt == 2)
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD)
      assert(!dut.uop0.srcAValid.toBoolean, "(d16,PC) folds PC into the absolute disp")
      // pc=0x2000, ext word at pc+2, target = pc+2 + 4 = 0x2006
      assert((dut.uop0.imm.toLong & 0xffffffffL) == 0x2006L,
        s"pc-rel target 0x2006, got 0x${(dut.uop0.imm.toLong & 0xffffffffL).toHexString}")
    }
  }

  test("indexed (d8,A0,Xn) EA source -> LOAD(base=A0,index=Xn) + ADD reading T0", VerilatorTest) {
    // ADD.L (d8,A0,D0.w),D1 : mode 6 = brief indexed -> now cracks a leading load.
    // opword 0xD2B0; ext word 0x0000 = Xn=D0, .W (bit11=0), scale*1 (bits10:9=0), d8=0.
    run { dut => drive(dut, 0xD2B0, 0x0000, len = 2); sleep(1)
      assert(dut.count.toInt == 2, s"expected 2 µops, got ${dut.count.toInt}")
      assert(!dut.uop0.unimplemented.toBoolean, "indexed EA is now in scope")
      // µop0 = load: base = A0 (8), index = D0 (srcC), disp = d8 = 0.
      assert(dut.uop0.cluster.toEnum == Cluster.LS && dut.uop0.memOp.toEnum == MemOp.LOAD)
      assert(dut.uop0.srcAReg.toInt == 8 && dut.uop0.srcAValid.toBoolean, "load base = A0")
      assert(dut.uop0.srcCReg.toInt == 0 && dut.uop0.srcCValid.toBoolean, "load index = D0 (srcC)")
      assert(!dut.uop0.indexLong.toBoolean && dut.uop0.indexScale.toInt == 0, ".W, scale *1")
      assert(dut.uop0.imm.toLong == 0, "load disp = d8 = 0")
      assert(dut.uop0.dstReg.toInt == 16, "load dst = T0")
      // µop1 = ADD reading T0 in srcB.
      assert(dut.uop1.op.toEnum == DecOp.ADD)
      assert(dut.uop1.srcBReg.toInt == 16 && dut.uop1.srcBValid.toBoolean, "ADD srcB = T0")
      assert(!dut.uop1.unimplemented.toBoolean)
    }
  }

  test("(An)+ post-increment EA source -> LOAD(A0)+ + An-update + ADD reading T0", VerilatorTest) {
    // ADD.L (A0)+,D1 : mode 3 = (An)+ -> now cracks [load (A0)+ -> T0][A0 += 4][ADD D1,T0].
    // POSTINC: access addr = A0 (psrcA), A0 := A0 + 4. The load can write only one int reg
    // (T0), so the An update rides a SEPARATE ADD µop; the op (ADD) reads T0 last.
    run { dut => drive(dut, 0xD298); sleep(1)
      assert(dut.count.toInt == 3, s"expected 3 µops, got ${dut.count.toInt}")
      // µop0 = load (A0)+ -> T0, POSTINC delta 4 (the load itself does NOT write A0).
      assert(!dut.uop0.unimplemented.toBoolean, "(An)+ side-effect EA is now in scope")
      assert(dut.uop0.cluster.toEnum == Cluster.LS && dut.uop0.memOp.toEnum == MemOp.LOAD)
      assert(dut.uop0.srcAReg.toInt == 8 && dut.uop0.srcAValid.toBoolean, "load base = A0 (8)")
      assert(dut.uop0.eaAuto.toEnum == EaAuto.POSTINC && dut.uop0.eaDelta.toInt == 4, "(A0)+ POSTINC, delta 4")
      assert(dut.uop0.dstReg.toInt == 16 && dut.uop0.dstValid.toBoolean, "load dst = T0 (16)")
      assert(dut.uop0.size.toEnum == Size.LONG)
      assert(dut.uop0.firstOfInstr.toBoolean, "load is the first µop")
      // µop1 = the An postincrement: A0 := A0 + 4 (a plain ADD reading A0 + #4 -> A0).
      assert(dut.uop1.op.toEnum == DecOp.ADD, "An-update is an ADD")
      assert(dut.uop1.srcAReg.toInt == 8 && dut.uop1.srcAValid.toBoolean, "An-update reads A0")
      assert(dut.uop1.useImm.toBoolean && dut.uop1.imm.toLong == 4, "An-update adds the +4 delta")
      assert(dut.uop1.dstReg.toInt == 8 && dut.uop1.dstValid.toBoolean, "An-update writes A0")
      assert(!dut.uop1.writesNzvc.toBoolean, "An-update sets no flags (address arithmetic)")
      // µop2 = ADD reading T0 in srcB, D1 in srcA -> D1, NZVCX.
      assert(dut.uop2.op.toEnum == DecOp.ADD && dut.uop2.cluster.toEnum == Cluster.INT)
      assert(dut.uop2.srcBReg.toInt == 16 && dut.uop2.srcBValid.toBoolean, "ADD srcB = T0 (16)")
      assert(dut.uop2.srcAReg.toInt == 1 && dut.uop2.srcAValid.toBoolean, "ADD srcA = D1")
      assert(dut.uop2.dstReg.toInt == 1 && dut.uop2.dstValid.toBoolean && dut.uop2.writesNzvc.toBoolean)
      assert(!dut.uop2.unimplemented.toBoolean)
    }
  }
}
