package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Size, MemOp, Cluster}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** EXG crack decode (line-C exchange-registers).
  *
  * EXG Rx,Ry -> 3 full-32 MOVE µops through int temp T0 (NO flags):
  *   µ0: MOVE.L regA -> T0   (firstOfInstr = True)
  *   µ1: MOVE.L regB -> regA (firstOfInstr = False)
  *   µ2: MOVE.L T0   -> regB (firstOfInstr = False)
  * regA = bits-11:9 reg, regB = bits-2:0 reg (D = field, A = 8+field per opmode).
  * Also asserts AND opwords (to-Dn and RMW-to-mem) are unchanged (still DecOp.AND). */
class ExgDecodeSpec extends AnyFunSuite {
  val T0 = 16
  class Dut extends Component {
    val pkt   = in(DecodePacket())
    val uop0  = out(DecodedUop()); val uop1 = out(DecodedUop()); val uop2 = out(DecodedUop())
    val count = out(UInt(2 bits))
    val a = MicroOpAssembler.assemble(pkt)
    uop0 := a.uops(0); uop1 := a.uops(1); uop2 := a.uops(2); count := a.count
  }
  def drive(dut: Dut, op: Int): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= 1; dut.pkt.wordCount #= 1; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= 0; dut.pkt.words(2) #= 0
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // Assert a single EXG crack µop is a plain full-32 no-flags register MOVE.
  // `drop` = the divIsRem crack-drop marker (µ1 only; its commit-obs is dropped, the
  // reg write still lands in the PRF — verified by a later reader, the DIVREM pattern).
  def assertMove(u: DecodedUop, srcB: Int, dst: Int, first: Boolean, tag: String,
                 drop: Boolean = false): Unit = {
    assert(u.op.toEnum == DecOp.MOVE, s"$tag op MOVE")
    assert(u.cluster.toEnum == Cluster.INT, s"$tag INT cluster")
    assert(u.size.toEnum == Size.LONG, s"$tag size LONG")
    assert(u.memOp.toEnum == MemOp.NONE, s"$tag no mem")
    assert(u.srcBReg.toInt == srcB && u.srcBValid.toBoolean, s"$tag srcB = $srcB")
    assert(u.dstReg.toInt == dst && u.dstValid.toBoolean, s"$tag dst = $dst")
    assert(!u.writesNzvc.toBoolean, s"$tag NO writesNzvc")
    assert(!u.writesX.toBoolean, s"$tag NO writesX")
    assert(!u.isMovea.toBoolean, s"$tag no isMovea")
    assert(u.divIsRem.toBoolean == drop, s"$tag divIsRem (crack-drop) = $drop")
    assert(u.firstOfInstr.toBoolean == first, s"$tag firstOfInstr = $first")
  }

  // EXG D1,D2 = 0xC342 (1100 001 1 01000 010): regA=D1(1), regB=D2(2).
  test("EXG D1,D2 -> 3 MOVEs through T0 (data/data)", VerilatorTest) {
    run { dut => drive(dut, 0xC342); sleep(1)
      assert(dut.count.toInt == 3, "EXG cracks to 3 µops")
      assert(!dut.uop0.unimplemented.toBoolean, "EXG not illegal")
      assertMove(dut.uop0, srcB = 1,  dst = T0, first = true,  "µ0")
      assertMove(dut.uop1, srcB = 2,  dst = 1,  first = false, "µ1", drop = true)
      assertMove(dut.uop2, srcB = T0, dst = 2,  first = false, "µ2")
    }
  }

  // EXG A1,A2 = 0xC34A (1100 001 1 01001 010): regA=A1(9), regB=A2(10).
  test("EXG A1,A2 -> 3 MOVEs through T0 (addr/addr, +8 mapping)", VerilatorTest) {
    run { dut => drive(dut, 0xC34A); sleep(1)
      assert(dut.count.toInt == 3)
      assertMove(dut.uop0, srcB = 9,  dst = T0, first = true,  "µ0")
      assertMove(dut.uop1, srcB = 10, dst = 9,  first = false, "µ1", drop = true)
      assertMove(dut.uop2, srcB = T0, dst = 10, first = false, "µ2")
    }
  }

  // EXG D1,A2 = 0xC38A (1100 001 1 10001 010): regA=D1(1), regB=A2(10) — mixed files.
  test("EXG D1,A2 -> 3 MOVEs through T0 (data/addr mixed mapping)", VerilatorTest) {
    run { dut => drive(dut, 0xC38A); sleep(1)
      assert(dut.count.toInt == 3)
      assertMove(dut.uop0, srcB = 1,  dst = T0, first = true,  "µ0")
      assertMove(dut.uop1, srcB = 10, dst = 1,  first = false, "µ1", drop = true)
      assertMove(dut.uop2, srcB = T0, dst = 10, first = false, "µ2")
    }
  }

  // EXG D3,D3 = 0xC743 (1100 011 1 01000 011): regA=regB=3 (same-reg, net no-op).
  test("EXG D3,D3 (same reg) -> 3 MOVEs, D3 net unchanged", VerilatorTest) {
    run { dut => drive(dut, 0xC743); sleep(1)
      assert(dut.count.toInt == 3)
      assertMove(dut.uop0, srcB = 3,  dst = T0, first = true,  "µ0")
      assertMove(dut.uop1, srcB = 3,  dst = 3,  first = false, "µ1", drop = true)
      assertMove(dut.uop2, srcB = T0, dst = 3,  first = false, "µ2")
    }
  }

  // Regression: AND <ea>,Dn (opmode 1, AND.W D2,D0 = 0xC042) still decodes DecOp.AND, not EXG.
  test("regression: AND.W D2,D0 (0xC042) still DecOp.AND, not EXG", VerilatorTest) {
    SimConfig.compile(new Component {
      val op = out(DecOp())
      op := OperationDecoder.decode(B(0xC042, 16 bits)).op
    }).doSim { dut => sleep(1)
      assert(dut.op.toEnum == DecOp.AND, "AND.W to-Dn must stay AND")
    }
  }

  // Regression: AND.B D0,(A1) RMW-to-memory (0xC111) still decodes DecOp.AND (assembler cracks).
  test("regression: AND.B D0,(A1) RMW (0xC111) still DecOp.AND, not EXG", VerilatorTest) {
    SimConfig.compile(new Component {
      val op = out(DecOp())
      op := OperationDecoder.decode(B(0xC111, 16 bits)).op
    }).doSim { dut => sleep(1)
      assert(dut.op.toEnum == DecOp.AND, "AND RMW-to-mem must stay AND")
    }
  }
}
