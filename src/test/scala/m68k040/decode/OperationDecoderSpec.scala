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
  // EOR.L D1,D2 = 0xB382 (1011 001 1 10 000 010): opmode6 (.L), src Dn=bits11:9=D1,
  // dst = the EA (op[5:0] = D2). Dm := Dm ^ Dn. EOR's dst is the SAME EA it reads
  // (srcA=EASRC, dst=EASRC); srcB = Dn. writes the reg; NZ, V=C=0 (no X).
  test("EOR.L Dn,Dm (lineB opmode6, reg dest): EOR op, writesNzvc, no X", VerilatorTest) {
    run(0xB382) { dut =>
      assert(dut.o.op.toEnum == DecOp.EOR && dut.o.size.toEnum == Size.LONG && !dut.o.illegal.toBoolean)
      assert(dut.o.srcA.kind.toEnum == OperandKind.EASRC)
      assert(dut.o.srcB.kind.toEnum == OperandKind.REGFIELD && !dut.o.srcB.isAddr.toBoolean)
      assert(dut.o.dst.kind.toEnum == OperandKind.EASRC && dut.o.dstWrites.toBoolean)
      assert(dut.o.writesNzvc.toBoolean && !dut.o.writesX.toBoolean)
    }
  }
  test("EOR.B Dn,Dm (lineB opmode4, reg dest): EOR/BYTE", VerilatorTest) {
    run(0xB302) { dut =>
      assert(dut.o.op.toEnum == DecOp.EOR && dut.o.size.toEnum == Size.BYTE && !dut.o.illegal.toBoolean)
    }
  }
  // CMPM (line B opmode 4/5/6, An-direct mode 1) is NOT EOR -> stays illegal here
  // (this slice; the (An)+,(An)+ compare is deferred).
  test("CMPM (lineB opmode4, An-direct) -> not EOR, illegal", VerilatorTest) {
    run(0xB509) { dut => assert(dut.o.illegal.toBoolean && dut.o.op.toEnum != DecOp.EOR) }
  }
}
