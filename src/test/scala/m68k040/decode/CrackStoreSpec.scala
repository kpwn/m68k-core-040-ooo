package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, MemOp, Size}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Task 4: crack MOVE reg -> memSimple destination into a single STORE µop
  * (no temp; the data is the register source). RMW-to-mem stays unimplemented. */
class CrackStoreSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val a   = MicroOpAssembler.assemble(pkt)
    val count = out(UInt(2 bits)); count := a.count
    val uop0  = out(DecodedUop()); uop0 := a.uops(0)
    val uop1  = out(DecodedUop()); uop1 := a.uops(1)
  }

  def drive(dut: Dut, op: Int, w1: Int = 0, w2: Int = 0, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x3000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  test("MOVE.L D1,(A0) -> 1 STORE uop (base=8, srcB=1)", VerilatorTest) {
    // MOVE.L D1,(A0): size=.L (00 10), dst EA = (A0) -> dstMode=2,dstReg=0 (bits 8-6=010, 11-9=000),
    // src EA = D1 (bits 5-0 = 000 001). opword = 0010 000 010 000 001 = 0x2081
    run { dut => drive(dut, 0x2081); sleep(1)
      assert(dut.count.toInt == 1, s"store is a single µop, got ${dut.count.toInt}")
      assert(dut.uop0.cluster.toEnum == Cluster.LS && dut.uop0.memOp.toEnum == MemOp.STORE)
      assert(dut.uop0.srcAReg.toInt == 8 && dut.uop0.srcAValid.toBoolean, "store base = A0 (8)")
      assert(dut.uop0.imm.toLong == 0, "store disp = 0 for (An)")
      assert(dut.uop0.srcBReg.toInt == 1 && dut.uop0.srcBValid.toBoolean, "store data = D1 (srcB)")
      assert(!dut.uop0.dstValid.toBoolean, "a store writes no int reg")
      assert(dut.uop0.writesNzvc.toBoolean, "MOVE to memory SETS NZVC (N/Z of the moved value)")
      assert(!dut.uop0.writesX.toBoolean, "MOVE never writes X")
      assert(dut.uop0.size.toEnum == Size.LONG && !dut.uop0.unimplemented.toBoolean)
    }
  }

  test("MOVE.W D2,(d16,A1) -> STORE with disp", VerilatorTest) {
    // MOVE.W D2,(d16,A1): size=.W (line 3), dst EA = (d16,A1) -> dstMode=5,dstReg=1
    // (bits 8-6=101, 11-9=001), src EA = D2 (000 010). opword = 0011 001 101 000 010 = 0x3342
    run { dut => drive(dut, 0x3342, 0x0020, len = 2); sleep(1)
      assert(dut.count.toInt == 1)
      assert(dut.uop0.memOp.toEnum == MemOp.STORE)
      assert(dut.uop0.srcAReg.toInt == 9 && dut.uop0.srcAValid.toBoolean, "store base = A1 (9)")
      assert(dut.uop0.imm.toLong == 0x20, s"store disp 0x20, got 0x${dut.uop0.imm.toLong.toHexString}")
      assert(dut.uop0.srcBReg.toInt == 2 && dut.uop0.srcBValid.toBoolean, "store data = D2")
      assert(dut.uop0.size.toEnum == Size.WORD)
    }
  }

  test("MOVE.L D0,(xxx).W -> STORE base=0, abs disp", VerilatorTest) {
    // MOVE.L D0,(xxx).W: dst EA mode=7 reg=0 -> dstMode=111 dstReg=000, src=D0.
    // opword = 0010 000 111 000 000 = 0x21C0, ext = abs16
    run { dut => drive(dut, 0x21C0, 0x0400, len = 2); sleep(1)
      assert(dut.count.toInt == 1)
      assert(dut.uop0.memOp.toEnum == MemOp.STORE)
      assert(!dut.uop0.srcAValid.toBoolean, "(xxx).W store needs no base reg")
      assert(dut.uop0.imm.toLong == 0x400, s"abs disp 0x400, got 0x${dut.uop0.imm.toLong.toHexString}")
      assert(dut.uop0.srcBReg.toInt == 0 && dut.uop0.srcBValid.toBoolean, "store data = D0")
    }
  }

  test("ADD.L D1,(A0) RMW-to-mem now cracks to a leading LOAD (see MemRmwDecodeSpec)", VerilatorTest) {
    // ADD.L D1,(A0): line D, opmode=110 (.L Dn,EA = RMW). opword = 1101 001 110 010 000 = 0xD390.
    // Now implemented: cracks into [load -> T0][op -> T1][store T1] (full triple in MemRmwDecodeSpec).
    run { dut => drive(dut, 0xD390); sleep(1)
      assert(!dut.uop0.unimplemented.toBoolean && dut.uop0.memOp.toEnum == MemOp.LOAD,
        "ALU mem-dest RMW now cracks to a leading load")
    }
  }

  test("MOVE.L (A1),(A0) mem-to-mem cracks to load(A1)->T0 + store T0->(A0)", VerilatorTest) {
    // MOVE.L (A1),(A0): src EA = (A1) memSimple, dst EA = (A0) memSimple.
    // dstMode=010 dstReg=000, src=(A1) = 010 001. opword = 0010 000 010 010 001 = 0x2091
    // Now implemented (crackMemMem): [load.L (A1) -> T0][store.L T0 -> (A0)], count 2.
    run { dut => drive(dut, 0x2091); sleep(1)
      assert(!dut.uop0.unimplemented.toBoolean, "mem-to-mem MOVE now cracks")
      assert(dut.count.toInt == 2, "load + store")
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD && dut.uop0.size.toEnum == Size.LONG, "uop0 = load.L")
      assert(dut.uop0.srcAReg.toInt == 9 && dut.uop0.srcAValid.toBoolean, "load base = A1 (reg 9)")
      assert(dut.uop0.dstReg.toInt == MicroOpAssembler.T0, "load -> T0")
      assert(dut.uop1.memOp.toEnum == MemOp.STORE && dut.uop1.size.toEnum == Size.LONG, "uop1 = store.L")
      assert(dut.uop1.srcAReg.toInt == 8 && dut.uop1.srcAValid.toBoolean, "store base = A0 (reg 8)")
      assert(dut.uop1.srcBReg.toInt == MicroOpAssembler.T0 && dut.uop1.srcBValid.toBoolean, "store data = T0")
    }
  }
}
