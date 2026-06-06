package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.{Size, MemOp}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class MicroOpAssemblerSpec extends AnyFunSuite {
  class Dut extends Component {
    val pkt = in(DecodePacket())
    val uop = out(DecodedUop())
    uop := MicroOpAssembler.assemble(pkt).uops(0)
  }
  def drive(dut: Dut, op: Int, w1: Int = 0, w2: Int = 0, len: Int = 1): Unit = {
    dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
    dut.pkt.lenWords #= len; dut.pkt.wordCount #= len; dut.pkt.fault #= false
    dut.pkt.words(0) #= op; dut.pkt.words(1) #= w1; dut.pkt.words(2) #= w2
    dut.pkt.words(3) #= 0; dut.pkt.words(4) #= 0
  }
  def run(check: Dut => Unit): Unit = SimConfig.withVerilator.compile(new Dut).doSim(check)

  test("MOVEQ #5,D3", VerilatorTest) { run { dut => drive(dut, 0x7605); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.dstReg.toInt == 3 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 5 && dut.uop.size.toEnum == Size.LONG)
    assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.unimplemented.toBoolean)
  }}
  test("MOVE.W D0,D1 (src in srcB, writesNzvc)", VerilatorTest) { run { dut => drive(dut, 0x3200); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.MOVE && dut.uop.srcBReg.toInt == 0 && dut.uop.srcBValid.toBoolean)
    assert(!dut.uop.srcAValid.toBoolean && dut.uop.dstReg.toInt == 1 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.writesNzvc.toBoolean)
  }}
  test("MOVEA.L A0,A1 (src in srcB, no flags)", VerilatorTest) { run { dut => drive(dut, 0x2248); sleep(1)
    assert(dut.uop.srcBReg.toInt == 8 && dut.uop.srcBValid.toBoolean)
    assert(dut.uop.dstReg.toInt == 9 && dut.uop.dstValid.toBoolean && !dut.uop.writesNzvc.toBoolean)
  }}
  test("MOVE.L #imm,D0", VerilatorTest) { run { dut => drive(dut, 0x203C, 0x1234, 0x5678, len = 3); sleep(1)
    assert(dut.uop.useImm.toBoolean && dut.uop.imm.toLong == 0x12345678L && dut.uop.dstReg.toInt == 0)
    assert(dut.uop.writesNzvc.toBoolean)
  }}
  test("ADD.L D1,D0 (srcA=Dn dest, srcB=EA, NZVC+X)", VerilatorTest) { run { dut => drive(dut, 0xD081); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.ADD && dut.uop.srcAReg.toInt == 0 && dut.uop.srcBReg.toInt == 1)
    assert(dut.uop.dstReg.toInt == 0 && dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean && dut.uop.writesX.toBoolean)
  }}
  test("CMP.L D1,D0 (no write, NZVC)", VerilatorTest) { run { dut => drive(dut, 0xB081); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.CMP && !dut.uop.dstValid.toBoolean && dut.uop.writesNzvc.toBoolean)
  }}
  test("Bcc word disp", VerilatorTest) { run { dut => drive(dut, 0x6700, 0x0010, len = 2); sleep(1)
    assert(dut.uop.isBranch.toBoolean && dut.uop.cond.toInt == 7 && dut.uop.branchDisp.toLong == 0x10)
  }}
  test("memSimple-EA source -> cracked load uop (slot0), not unimplemented", VerilatorTest) { run { dut => drive(dut, 0xD090); sleep(1)
    // ADD.L (A0),D0 — EA (A0) is memSimple -> slot0 is now the LOAD µop (cracking
    // slice 2), no longer unimplemented. (Full sequence covered by CrackLoadSpec.)
    assert(!dut.uop.unimplemented.toBoolean && dut.uop.memOp.toEnum == MemOp.LOAD)
  }}
  // EOR.L D1,D2 (0xB382): reg dest. srcA = EA reg (D2 = dst operand), srcB = Dn (D1),
  // dst = EA reg (D2). Writes the reg + NZVC, no X.
  test("EOR.L D1,D2 (reg dest): srcA=D2, srcB=D1, dst=D2, NZVC no X", VerilatorTest) { run { dut => drive(dut, 0xB382); sleep(1)
    assert(dut.uop.op.toEnum == DecOp.EOR && !dut.uop.unimplemented.toBoolean)
    assert(dut.uop.srcAReg.toInt == 2 && dut.uop.srcAValid.toBoolean)
    assert(dut.uop.srcBReg.toInt == 1 && dut.uop.srcBValid.toBoolean)
    assert(dut.uop.dstReg.toInt == 2 && dut.uop.dstValid.toBoolean)
    assert(dut.uop.writesNzvc.toBoolean && !dut.uop.writesX.toBoolean && !dut.uop.useImm.toBoolean)
  }}
  // EOR.B D2,(A0) (0xB510): memory destination = the deferred RMW form -> illegal
  // (unimplemented). Must NOT crack a leading load.
  test("EOR.B D2,(A0) (mem dest) -> unimplemented (RMW deferred)", VerilatorTest) { run { dut => drive(dut, 0xB510); sleep(1)
    assert(dut.uop.unimplemented.toBoolean && dut.uop.memOp.toEnum == MemOp.NONE)
  }}
  test("non-simple packet -> unimplemented", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      drive(dut, 0x7605); dut.pkt.simple #= false; dut.pkt.complex #= true; sleep(1)
      assert(dut.uop.unimplemented.toBoolean)
    }
  }
}
