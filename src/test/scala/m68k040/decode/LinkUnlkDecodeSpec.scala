package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Size, MemOp}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** LINK / UNLK crack decode (line-4 stack-frame ops).
  *
  * LINK An,#disp16 -> 3 µops: [stkPush store dst=An, push old An] + [A7:=A7+disp (drop)]
  *                            + [An:=A7-disp (kept)].
  * UNLK An        -> 3 µops: [load.l (An)->T0] + [A7:=An+4 (drop)] + [An:=T0 (kept)]. */
class LinkUnlkDecodeSpec extends AnyFunSuite {
  val T0 = 16
  class Dut extends Component {
    val pkt   = in(DecodePacket())
    val uop0  = out(DecodedUop()); val uop1 = out(DecodedUop()); val uop2 = out(DecodedUop())
    val count = out(UInt(2 bits))
    val a = MicroOpAssembler.assemble(pkt)
    uop0 := a.uops(0); uop1 := a.uops(1); uop2 := a.uops(2); count := a.count
  }
  def drive(dut: Dut, op: Int, w1: Int = 0, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x2000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= 0
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  // LINK A6,#-8 = 0x4E56 + 0xFFF8. An = A6 = arch 14.
  test("LINK A6,#-8 -> 3 µops: stkPush(dst=A6,data=A6) + A7+=disp(drop) + A6:=A7-disp(kept)", VerilatorTest) {
    run { dut => drive(dut, 0x4E56, 0xFFF8, len = 2); sleep(1)
      assert(dut.count.toInt == 3, "LINK cracks to 3 µops")
      assert(!dut.uop0.unimplemented.toBoolean, "LINK is not illegal")
      // µop0: stkPush store, base/dst A7 (15), data srcB = A6 (14).
      assert(dut.uop0.memOp.toEnum == MemOp.STORE && dut.uop0.stkPush.toBoolean)
      assert(dut.uop0.srcAReg.toInt == 15 && dut.uop0.srcAValid.toBoolean, "base A7")
      assert(dut.uop0.srcBReg.toInt == 14 && dut.uop0.srcBValid.toBoolean, "push data = old A6")
      assert(dut.uop0.dstReg.toInt == 15 && dut.uop0.dstValid.toBoolean, "int dst A7 (predecrement)")
      assert(dut.uop0.firstOfInstr.toBoolean)
      // µop1: A7 := A7 + disp, DROPPED (divIsRem), no flags.
      assert(dut.uop1.op.toEnum == DecOp.ADD && dut.uop1.size.toEnum == Size.LONG)
      assert(dut.uop1.srcAReg.toInt == 15 && dut.uop1.dstReg.toInt == 15 && dut.uop1.dstValid.toBoolean)
      assert(dut.uop1.useImm.toBoolean && (dut.uop1.imm.toLong & 0xffffffffL) == 0xFFFFFFF8L, "imm = sext(-8)")
      assert(dut.uop1.divIsRem.toBoolean, "A7-fold µop is dropped via divIsRem")
      assert(!dut.uop1.writesNzvc.toBoolean && !dut.uop1.writesX.toBoolean && !dut.uop1.firstOfInstr.toBoolean)
      // µop2: An := A7 + (-disp) -> A7-4. KEPT (writes A6=14, not dropped).
      assert(dut.uop2.op.toEnum == DecOp.ADD && dut.uop2.size.toEnum == Size.LONG)
      assert(dut.uop2.srcAReg.toInt == 15 && dut.uop2.dstReg.toInt == 14 && dut.uop2.dstValid.toBoolean)
      assert((dut.uop2.imm.toLong & 0xffffffffL) == 0x00000008L, "imm = -disp = +8")
      assert(!dut.uop2.divIsRem.toBoolean, "the kept µop is NOT dropped")
      assert(!dut.uop2.writesNzvc.toBoolean && !dut.uop2.firstOfInstr.toBoolean)
    }
  }

  // LINK A5,#+16 = 0x4E55 + 0x0010. An = A5 = arch 13.
  test("LINK A5,#16 -> positive disp imm threading", VerilatorTest) {
    run { dut => drive(dut, 0x4E55, 0x0010, len = 2); sleep(1)
      assert(dut.count.toInt == 3)
      assert(dut.uop0.srcBReg.toInt == 13, "push data = old A5")
      assert((dut.uop1.imm.toLong & 0xffffffffL) == 0x10L, "A7 += +16")
      assert((dut.uop2.imm.toLong & 0xffffffffL) == 0xFFFFFFF0L, "An := A7 + (-16)")
      assert(dut.uop2.dstReg.toInt == 13)
    }
  }

  // UNLK A6 = 0x4E5E. An = A6 = arch 14.
  test("UNLK A6 -> 3 µops: load(A6)->T0 + A7:=A6+4(drop) + A6:=T0(kept)", VerilatorTest) {
    run { dut => drive(dut, 0x4E5E, 0, len = 1); sleep(1)
      assert(dut.count.toInt == 3, "UNLK cracks to 3 µops")
      assert(!dut.uop0.unimplemented.toBoolean, "UNLK is not illegal")
      // µop0: load.l (A6) -> T0.
      assert(dut.uop0.memOp.toEnum == MemOp.LOAD)
      assert(dut.uop0.srcAReg.toInt == 14 && dut.uop0.srcAValid.toBoolean, "addr base A6")
      assert(dut.uop0.dstReg.toInt == T0 && dut.uop0.dstValid.toBoolean, "load -> T0")
      assert((dut.uop0.imm.toLong & 0xffffffffL) == 0L, "disp 0")
      assert(dut.uop0.firstOfInstr.toBoolean)
      // µop1: A7 := A6 + 4, DROPPED.
      assert(dut.uop1.op.toEnum == DecOp.ADD && dut.uop1.srcAReg.toInt == 14 && dut.uop1.dstReg.toInt == 15)
      assert((dut.uop1.imm.toLong & 0xffffffffL) == 4L && dut.uop1.divIsRem.toBoolean)
      assert(!dut.uop1.writesNzvc.toBoolean && !dut.uop1.firstOfInstr.toBoolean)
      // µop2: A6 := T0. KEPT (MOVE).
      assert(dut.uop2.op.toEnum == DecOp.MOVE && dut.uop2.srcAReg.toInt == T0 && dut.uop2.dstReg.toInt == 14)
      assert(dut.uop2.dstValid.toBoolean && !dut.uop2.divIsRem.toBoolean && !dut.uop2.firstOfInstr.toBoolean)
    }
  }

  // UNLK A0 (lowest An) = 0x4E58. An = A0 = arch 8.
  test("UNLK A0 -> An = arch 8", VerilatorTest) {
    run { dut => drive(dut, 0x4E58, 0, len = 1); sleep(1)
      assert(dut.count.toInt == 3)
      assert(dut.uop0.srcAReg.toInt == 8 && dut.uop1.srcAReg.toInt == 8 && dut.uop2.dstReg.toInt == 8)
    }
  }
}
