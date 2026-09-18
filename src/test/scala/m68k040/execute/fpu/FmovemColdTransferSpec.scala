package m68k040.execute.fpu

import m68k040.{M68kSim, VerilatorTest}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class FmovemColdTransferSpec extends AnyFunSuite {
  test("cold extended transfer preserves bits, padding, byte addresses and fault boundaries", VerilatorTest) {
    M68kSim().withVerilator.compile(new FmovemColdTransfer).doSim(seed = 68041) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.request.valid #= false
      dut.io.request.payload.address #= 0
      dut.io.request.payload.store #= false
      dut.io.request.payload.value #= 0
      dut.io.command.ready #= false
      dut.io.response.valid #= false
      dut.io.response.payload.data #= 0
      dut.io.response.payload.fault #= false
      dut.io.result.ready #= false
      cd.waitSampling(5)
      val rng = new scala.util.Random(68041)
      val mantissaMask = (BigInt(1) << 64)-1
      val values = Seq(BigInt(0), BigInt("ffffdeadbeef01234567",16),
        BigInt("7fff8000000000000001",16)) ++ Seq.fill(24)(BigInt(80,rng))
      for (value <- values; store <- Seq(false,true); faultAt <- -1 until 12) {
        // Nonzero memory padding on loads must be discarded, not mixed into FP.
        val image = ((value >> 64) << 80) | (if (store) BigInt(0) else BigInt(0xabcd) << 64) |
          (value & mantissaMask)
        val base = if (rng.nextBoolean()) 0xfffffff9L else 0x20ffdL
        assert(dut.io.request.ready.toBoolean)
        dut.io.request.payload.address #= base
        dut.io.request.payload.store #= store
        dut.io.request.payload.value #= value
        dut.io.request.valid #= true
        cd.waitSampling()
        dut.io.request.valid #= false
        cd.waitSampling()
        val transfers = if (faultAt < 0) 12 else faultAt+1
        for (i <- 0 until transfers) {
          val byte = ((image >> ((11-i)*8)) & 255).toInt
          val address = (base+i) & 0xffffffffL
          for (_ <- 0 until 1+rng.nextInt(3)) {
            assert(dut.io.command.valid.toBoolean)
            assert(dut.io.command.payload.address.toLong == address)
            assert(dut.io.command.payload.store.toBoolean == store)
            if (store) assert(dut.io.command.payload.data.toInt == byte)
            cd.waitSampling()
          }
          dut.io.command.ready #= true
          cd.waitSampling()
          dut.io.command.ready #= false
          cd.waitSampling()
          for (_ <- 0 until 1+rng.nextInt(3)) {
            assert(!dut.io.command.valid.toBoolean)
            assert(!dut.io.result.valid.toBoolean)
            cd.waitSampling()
          }
          dut.io.response.payload.data #= byte
          dut.io.response.payload.fault #= (i == faultAt)
          dut.io.response.valid #= true
          cd.waitSampling()
          dut.io.response.valid #= false
          cd.waitSampling()
        }
        for (_ <- 0 until 3) {
          assert(dut.io.result.valid.toBoolean)
          assert(!dut.io.command.valid.toBoolean)
          assert(!dut.io.request.ready.toBoolean)
          assert(dut.io.result.payload.fault.toBoolean == (faultAt >= 0))
          assert(dut.io.result.payload.completedBytes.toInt == (if (faultAt < 0) 12 else faultAt))
          assert(dut.io.result.payload.faultAddress.toLong ==
            (if (faultAt < 0) 0L else (base+faultAt) & 0xffffffffL))
          if (faultAt < 0) assert(dut.io.result.payload.value.toBigInt == value)
          cd.waitSampling()
        }
        dut.io.result.ready #= true
        cd.waitSampling()
        dut.io.result.ready #= false
        cd.waitSampling()
      }
    }
  }
}
