package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.DecodePacket
import m68k040.isa.MemOp
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** CPU_RTS_SPLIT_A7 (`ShippingCoreConfig.rtsSplitA7`): the RTS crack shape.
  *
  * OFF: [load.l (A7) -> T0] + [ibranch -> T0 ; A7 := A7 + 4 (anInc)].
  * ON : [load.l (A7) -> T0] + [A7 := A7 + 4 (ADD, crack-drop)] + [ibranch -> T0], so the
  *      new A7 no longer waits for the return-address load (RTD's shape, displacement 0).
  * The flag is a JVM-wide env read, so this spec checks whichever shape the current
  * environment selects; run it with and without CPU_RTS_SPLIT_A7=1. */
class RtsSplitA7Spec extends AnyFunSuite {
  class Dut extends Component {
    val pkt  = in(DecodePacket())
    val asm  = MicroOpAssembler.assemble(pkt)
    val uops = out(Vec(DecodedUop(), 3)); uops := asm.uops
    val count = out(UInt(2 bits)); count := asm.count
  }
  private val A7 = 15
  private val T0 = MicroOpAssembler.T0

  test("RTD #8 crack shape follows CPU_RTD_PRECISE_A7", VerilatorTest) {
    val precise = m68k040.top.ShippingCoreConfig.rtdPreciseA7
    val T1 = MicroOpAssembler.T1
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
      dut.pkt.lenWords #= 2; dut.pkt.wordCount #= 2; dut.pkt.fault #= false
      dut.pkt.words.foreach(_ #= 0); dut.pkt.words(0) #= 0x4E74; dut.pkt.words(1) #= 8
      sleep(1)
      assert(dut.count.toInt == 3)
      val add = dut.uops(1); val br = dut.uops(2)
      assert(add.op.toEnum == DecOp.ADD && add.srcAReg.toInt == A7 && add.imm.toLong == 12L)
      assert(add.divIsRem.toBoolean && br.ibranch.toBoolean && br.isReturn.toBoolean)
      assert(br.srcAReg.toInt == T0 && br.anInc.toInt == 0)
      if (precise) {
        assert(add.dstReg.toInt == T1, "the dealloc goes to T1, not A7")
        assert(br.srcBReg.toInt == T1 && br.srcBValid.toBoolean)
        assert(br.dstReg.toInt == A7 && br.dstValid.toBoolean, "the ibranch commits A7")
      } else {
        assert(add.dstReg.toInt == A7)
        assert(!br.dstValid.toBoolean)
      }
    }
  }

  test("RTS crack shape follows CPU_RTS_SPLIT_A7", VerilatorTest) {
    val split = m68k040.top.ShippingCoreConfig.rtsSplitA7
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      dut.pkt.valid #= true; dut.pkt.pc #= 0x1000; dut.pkt.simple #= true; dut.pkt.complex #= false
      dut.pkt.lenWords #= 1; dut.pkt.wordCount #= 1; dut.pkt.fault #= false
      dut.pkt.words.foreach(_ #= 0); dut.pkt.words(0) #= 0x4E75
      sleep(1)
      val ld = dut.uops(0)
      assert(ld.memOp.toEnum == MemOp.LOAD && ld.srcAReg.toInt == A7 && ld.srcAValid.toBoolean)
      assert(ld.dstReg.toInt == T0 && ld.dstValid.toBoolean && ld.firstOfInstr.toBoolean)
      assert(!ld.lastOfInstr.toBoolean)
      if (split) {
        assert(dut.count.toInt == 3, "split RTS must be 3 uops")
        val add = dut.uops(1); val br = dut.uops(2)
        assert(add.op.toEnum == DecOp.ADD && add.srcAReg.toInt == A7 && add.srcAValid.toBoolean)
        assert(add.useImm.toBoolean && add.imm.toLong == 4L, "A7 += 4")
        assert(add.dstReg.toInt == A7 && add.dstValid.toBoolean)
        assert(add.divIsRem.toBoolean, "the A7 add is a dropped crack uop")
        assert(!add.writesNzvc.toBoolean && !add.writesX.toBoolean, "RTS leaves the CCR alone")
        assert(!add.isBranch.toBoolean && !add.firstOfInstr.toBoolean && !add.lastOfInstr.toBoolean)
        assert(br.isBranch.toBoolean && br.ibranch.toBoolean && br.isReturn.toBoolean)
        assert(br.srcAReg.toInt == T0 && br.srcAValid.toBoolean)
        assert(!br.dstValid.toBoolean, "the ibranch no longer writes A7")
        assert(br.anInc.toInt == 4, "anInc = 4 marks the split RTS for the odd-target A7 restore")
        assert(br.lastOfInstr.toBoolean)
      } else {
        assert(dut.count.toInt == 2, "folded RTS must be 2 uops")
        val br = dut.uops(1)
        assert(br.isBranch.toBoolean && br.ibranch.toBoolean && br.isReturn.toBoolean)
        assert(br.srcAReg.toInt == T0 && br.dstReg.toInt == A7 && br.dstValid.toBoolean)
        assert(br.anInc.toInt == 4 && br.lastOfInstr.toBoolean)
      }
    }
  }
}
