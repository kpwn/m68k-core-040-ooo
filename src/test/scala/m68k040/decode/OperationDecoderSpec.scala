package m68k040.decode

import m68k040.VerilatorTest
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class OperationDecoderSpec extends AnyFunSuite {
  class Dut extends Component {
    val opword = in Bits (16 bits)
    val o      = out(OpSpec())
    o := OperationDecoder.decode(opword)
  }
  def run(op: Int)(check: Dut => Unit): Unit =
    SimConfig.withVerilator.compile(new Dut).doSim { dut => dut.opword #= op; sleep(1); check(dut) }

  test("MOVEQ: MOVE/LONG, dst REGFIELD data, srcB IMMQ, writesNzvc", VerilatorTest) {
    run(0x7605) { dut =>
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && !dut.o.dst.isAddr.toBoolean && dut.o.dstWrites.toBoolean)
      assert(dut.o.srcB.kind.toEnum == OperandKind.IMMQ && dut.o.writesNzvc.toBoolean && !dut.o.illegal.toBoolean)
    }
  }
  test("MOVE.W: srcB EASRC, dst EADST, writesNzvcIfDataDst", VerilatorTest) {
    run(0x3200) { dut =>
      assert(dut.o.op.toEnum == DecOp.MOVE && dut.o.size.toEnum == Size.WORD)
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.dst.kind.toEnum == OperandKind.EADST && dut.o.dstWrites.toBoolean)
      assert(dut.o.writesNzvcIfDataDst.toBoolean && !dut.o.writesNzvc.toBoolean)
    }
  }
  test("ADD.L EA->Dn: srcA REGFIELD(Dn), srcB EASRC, writesNzvc+X", VerilatorTest) {
    run(0xD081) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADD && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.srcA.kind.toEnum == OperandKind.REGFIELD && !dut.o.srcA.isAddr.toBoolean)
      assert(dut.o.srcB.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && dut.o.dstWrites.toBoolean)
      assert(dut.o.writesNzvc.toBoolean && dut.o.writesX.toBoolean)
    }
  }
  test("CMP.L EA->Dn: no dst write, writesNzvc", VerilatorTest) {
    run(0xB081) { dut =>
      assert(dut.o.op.toEnum == DecOp.CMP && !dut.o.dstWrites.toBoolean && dut.o.writesNzvc.toBoolean)
    }
  }
  test("ADDA.L: opmode7, srcA EASRC, srcB REGFIELD(An), no flags", VerilatorTest) {
    run(0xD1C1) { dut =>
      assert(dut.o.op.toEnum == DecOp.ADD && dut.o.size.toEnum == Size.LONG)
      assert(dut.o.srcA.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.srcB.kind.toEnum == OperandKind.REGFIELD && dut.o.srcB.isAddr.toBoolean)
      assert(dut.o.dst.kind.toEnum == OperandKind.REGFIELD && dut.o.dst.isAddr.toBoolean && dut.o.dstWrites.toBoolean)
      assert(!dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean)
    }
  }
  test("Bcc: isBranch, cond, readsNzvc for cond>=2", VerilatorTest) {
    run(0x6700) { dut => assert(dut.o.isBranch.toBoolean && dut.o.cond.toInt == 0x7 && dut.o.readsNzvc.toBoolean) }
  }
  test("RMW form (ADD Dn->EA, opmode4) -> illegal in this slice", VerilatorTest) {
    run(0xD181) { dut => assert(dut.o.illegal.toBoolean) }
  }
}
