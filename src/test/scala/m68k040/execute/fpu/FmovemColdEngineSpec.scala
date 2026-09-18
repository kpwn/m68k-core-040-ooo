package m68k040.execute.fpu

import m68k040.{M68kSim, VerilatorTest}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class FmovemColdEngineSpec extends AnyFunSuite {
  test("backend engine stalls on staged loads and reports exact later byte faults", VerilatorTest) {
    M68kSim().withVerilator.compile(new FmovemColdEngine).doSim(seed = 68042) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.request.valid #= false
      dut.io.request.payload.mask #= 0x81 // FP0 followed by FP7
      dut.io.request.payload.address #= 0x20ffd
      dut.io.request.payload.store #= false
      dut.io.request.payload.predecrement #= false
      dut.io.request.payload.postincrement #= true
      dut.io.fpReadValue #= 0
      dut.io.loaded.ready #= false
      dut.io.command.ready #= false
      dut.io.response.valid #= false
      dut.io.response.payload.data #= 0
      dut.io.response.payload.fault #= false
      dut.io.result.ready #= false
      cd.waitSampling(5)
      dut.io.request.valid #= true
      cd.waitSampling()
      dut.io.request.valid #= false
      def await(p: => Boolean): Unit = {
        var n = 0
        while (!p && n < 30) { cd.waitSampling(); n += 1 }
        assert(p, "backend handshake did not progress")
      }
      val value = BigInt("c1230123456789abcdef",16)
      val image = ((value >> 64) << 80) | (BigInt(0xfeed) << 64) |
        (value & ((BigInt(1) << 64)-1))
      for (i <- 0 until 17) { // full FP0, then FP7 fails on its fifth byte
        await(dut.io.command.valid.toBoolean)
        assert(dut.io.command.payload.address.toLong == 0x20ffdL+i)
        assert(!dut.io.command.payload.store.toBoolean)
        dut.io.command.ready #= true
        cd.waitSampling()
        dut.io.command.ready #= false
        cd.waitSampling(2)
        dut.io.response.payload.data #= ((image >> ((11-i%12)*8)) & 255)
        dut.io.response.payload.fault #= (i == 16)
        dut.io.response.valid #= true
        cd.waitSampling()
        dut.io.response.valid #= false
        cd.waitSampling()
        if (i == 11) {
          await(dut.io.loaded.valid.toBoolean)
          for (_ <- 0 until 5) {
            assert(dut.io.loaded.payload.fpReg.toInt == 0)
            assert(dut.io.loaded.payload.value.toBigInt == value)
            assert(!dut.io.command.valid.toBoolean)
            assert(!dut.io.result.valid.toBoolean)
            cd.waitSampling()
          }
          dut.io.loaded.ready #= true
          cd.waitSampling()
          dut.io.loaded.ready #= false
          cd.waitSampling()
        }
      }
      await(dut.io.result.valid.toBoolean)
      assert(dut.io.result.payload.fault.toBoolean)
      assert(dut.io.result.payload.completed.toInt == 1)
      assert(dut.io.result.payload.nextAddress.toLong == 0x21009L)
      assert(dut.io.result.payload.faultAddress.toLong == 0x21009L)
      assert(dut.io.byteFaultAddress.toLong == 0x2100dL)
      assert(!dut.io.loaded.valid.toBoolean, "a partial faulting FP value must not escape")
      cd.waitSampling(4)
      assert(!dut.io.command.valid.toBoolean)
    }
  }
}
