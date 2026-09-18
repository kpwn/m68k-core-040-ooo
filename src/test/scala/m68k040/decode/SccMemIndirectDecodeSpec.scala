package m68k040.decode

import m68k040.VerilatorTest
import m68k040.cache.IcacheSim
import m68k040.isa.{MemOp, Size}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class SccMemIndirectDecodeSpec extends AnyFunSuite {
  test("board 56f6 8161 001c emits pointer, SNE condition, byte store instead of vector 4", VerilatorTest) {
    val fixture = new MicrocodeSpec
    SimConfig.withVerilator.compile(new fixture.Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val base = 0x001df38cL
      IcacheSim.attachMemoryWithWords(dut.ic.logic.axi, cd, base,
        Seq(0x56f6, 0x8161, 0x001c) ++ Seq.fill(32)(0x4e71))
      dut.sink.logic.uopsOut.ready #= true
      dut.fa.logic.resume.valid #= false
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= base
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      var found = 0
      for (_ <- 0 until 400 if found < 3) {
        cd.waitSampling()
        if (dut.sink.logic.uopsOut.valid.toBoolean) {
          for (slot <- 0 until (if (dut.sink.logic.u1v.toBoolean) 2 else 1)) {
            val u = dut.sink.logic.uopsOut.payload(slot)
            if (u.pc.toLong == base) {
              assert(!u.faulted.toBoolean, s"board SNE still traps vector ${u.faultVector.toInt}")
              assert(!u.writesNzvc.toBoolean && !u.writesX.toBoolean)
              assert(u.lenWords.toInt == 3)
              found match {
                case 0 =>
                  assert(u.memOp.toEnum == MemOp.LOAD && u.size.toEnum == Size.LONG)
                  assert(u.srcAReg.toInt == 14 && u.srcAValid.toBoolean)
                  assert(u.imm.toLong == 28 && u.dstReg.toInt == MicroOpAssembler.T0)
                  assert(!u.srcCValid.toBoolean)
                case 1 =>
                  assert(u.isScc.toBoolean && u.isBranch.toBoolean && !u.ibranch.toBoolean)
                  assert(u.cond.toInt == 6 && u.readsNzvc.toBoolean)
                  assert(u.dstReg.toInt == MicroOpAssembler.T1 && u.dstValid.toBoolean)
                  assert(!u.srcAValid.toBoolean && !u.srcBValid.toBoolean)
                case 2 =>
                  assert(u.memOp.toEnum == MemOp.STORE && u.size.toEnum == Size.BYTE)
                  assert(u.srcAReg.toInt == MicroOpAssembler.T0)
                  assert(u.srcBReg.toInt == MicroOpAssembler.T1 && u.srcBValid.toBoolean)
                  assert(u.imm.toLong == 0 && !u.srcCValid.toBoolean)
                case _ => fail("unexpected extra SNE micro-op")
              }
              found += 1
            }
          }
        }
      }
      assert(found == 3, s"only $found SNE micro-ops observed")
    }
  }
}
