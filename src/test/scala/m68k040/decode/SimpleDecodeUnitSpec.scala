package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class SimpleDecodeUnitSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := SimpleDecodeUnit.decode(pkt)
  }
  def drivePkt(dut: Dut, opword: Int, w1: Int = 0, w2: Int = 0, simple: Boolean = true, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= simple
    dut.pkt.complex #= !simple; dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= opword; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  test("MOVEQ #5,D3", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x7605); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 5)
      assert(dut.uop.size.toEnum == Size.LONG && dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean)
    }
  }
  test("MOVE.W D0,D1", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x3200); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.srcAReg.toInt == 0 && dut.uop.srcAValid.toBoolean)
      assert(dut.uop.dstReg.toInt == 1 && dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean)
    }
  }
  test("MOVEA.L A0,A1 (no flags)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x2248); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.srcAReg.toInt == 8 && dut.uop.dstReg.toInt == 9)
      assert(dut.uop.dstValid.toBoolean && !dut.uop.writesNzvc.toBoolean)
    }
  }
  test("ADD.L D1,D0", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0xD081); sleep(1)
      // ALU convention: srcA = destination operand (Dn), srcB = source (EA), so the
      // datapath computes src1(=Dn) op src2(=EA) — required for SUB/CMP ordering.
      assert(dut.uop.op.toEnum == DecOp.ADD && dut.uop.srcAReg.toInt == 0 && dut.uop.srcBReg.toInt == 1)
      assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean)
      assert(dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
    }
  }
  test("CMP.W D2,D3 (no dst write)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0xB642); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.CMP && !dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean)
      // srcA = destination operand (Dn=D3), srcB = source (EA=D2): CMP computes D3 - D2.
      assert(dut.uop.srcAReg.toInt == 3 && dut.uop.srcBReg.toInt == 2)
    }
  }
  test("BRA.w", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x6000, w1 = 0x0010, len = 2); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BRANCH && dut.uop.isBranch.toBoolean && dut.uop.cond.toInt == 0)
      assert(dut.uop.branchDisp.toLong == 0x10 && !dut.uop.readsNzvc.toBoolean)
    }
  }
  test("BEQ.s +4", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x6704); sleep(1)
      assert(dut.uop.op.toEnum == DecOp.BRANCH && dut.uop.cond.toInt == 7 && dut.uop.branchDisp.toLong == 4)
      assert(dut.uop.readsNzvc.toBoolean)
    }
  }
  test("complex packet -> unimplemented", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0xC0C1, simple = false); sleep(1)
      assert(dut.uop.unimplemented.toBoolean && dut.uop.op.toEnum == DecOp.ILLEGAL)
    }
  }
  test("memory-EA simple -> unimplemented", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drivePkt(dut, 0x2010); sleep(1)  // MOVE.L (A0),D0
      assert(dut.uop.unimplemented.toBoolean)
    }
  }
}
