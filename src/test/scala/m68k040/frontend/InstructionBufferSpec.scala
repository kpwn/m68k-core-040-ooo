package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class InstructionBufferSpec extends AnyFunSuite {
  test("push window, FIFO head order; shift consumes; flush clears", VerilatorTest) {
    SimConfig.withVerilator.compile(new InstructionBuffer).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.flush #= true; dut.io.shift #= 0; dut.io.push.valid #= false
      dut.clockDomain.waitSampling(); dut.io.flush #= false
      def pushWindow(base: Int, n: Int): Unit = {
        dut.io.push.valid #= true
        for (i <- 0 until 4) {
          dut.io.push.payload.words(i) #= (base + i)
          dut.io.push.payload.preds(i).simple #= true
          dut.io.push.payload.preds(i).lenWords #= 1
        }
        dut.io.push.payload.n #= n
        dut.clockDomain.waitSamplingWhere(dut.io.push.ready.toBoolean)
        dut.io.push.valid #= false
      }
      pushWindow(0x10, 4)
      dut.clockDomain.waitSampling()
      assert(dut.io.avail.toInt >= 4, s"avail=${dut.io.avail.toInt}")
      assert(dut.io.head(0).toInt == 0x10 && dut.io.head(3).toInt == 0x13, s"head0=${dut.io.head(0).toInt} head3=${dut.io.head(3).toInt}")
      dut.io.shift #= 2; dut.clockDomain.waitSampling(); dut.io.shift #= 0; dut.clockDomain.waitSampling()
      assert(dut.io.head(0).toInt == 0x12, s"after shift 2, head0 should be 0x12, got ${dut.io.head(0).toInt}")
      assert(dut.io.avail.toInt == 2, s"after shift 2 of 4, avail should be 2, got ${dut.io.avail.toInt}")
      dut.io.flush #= true; dut.clockDomain.waitSampling(); dut.io.flush #= false; dut.clockDomain.waitSampling()
      assert(dut.io.avail.toInt == 0)
    }
  }
  test("two windows accumulate in order", VerilatorTest) {
    SimConfig.withVerilator.compile(new InstructionBuffer).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.flush #= true; dut.io.shift #= 0; dut.io.push.valid #= false
      dut.clockDomain.waitSampling(); dut.io.flush #= false
      def pushWindow(base: Int): Unit = {
        dut.io.push.valid #= true
        for (i <- 0 until 4) { dut.io.push.payload.words(i) #= (base+i); dut.io.push.payload.preds(i).simple #= true; dut.io.push.payload.preds(i).lenWords #= 1 }
        dut.io.push.payload.n #= 4
        dut.clockDomain.waitSamplingWhere(dut.io.push.ready.toBoolean); dut.io.push.valid #= false
        dut.clockDomain.waitSampling()
      }
      pushWindow(0x20); pushWindow(0x30)
      dut.clockDomain.waitSampling()
      assert(dut.io.head(0).toInt == 0x20 && dut.io.head(4).toInt == 0x30, s"head0=${dut.io.head(0).toInt} head4=${dut.io.head(4).toInt}")
      assert(dut.io.avail.toInt == 8)
    }
  }
}
