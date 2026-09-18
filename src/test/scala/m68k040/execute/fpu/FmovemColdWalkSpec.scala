package m68k040.execute.fpu

import m68k040.{M68kSim, VerilatorTest}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class FmovemColdWalkSpec extends AnyFunSuite {
  test("cold FMOVEM walk: every mask, ordering, stalls, errors and illegal modes", VerilatorTest) {
    M68kSim().withVerilator.compile(new FmovemColdWalk).doSim(seed = 68040) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.request.valid #= false
      dut.io.request.payload.mask #= 0
      dut.io.request.payload.address #= 0
      dut.io.request.payload.store #= false
      dut.io.request.payload.predecrement #= false
      dut.io.request.payload.postincrement #= false
      dut.io.element.ready #= false
      dut.io.completion.valid #= false
      dut.io.completion.payload #= false
      dut.io.result.ready #= false
      cd.waitSampling(5)
      val rng = new scala.util.Random(68040)

      def run(mask: Int, pre: Boolean, post: Boolean, store: Boolean,
              base: Long, faultAt: Int = -1): Unit = {
        assert(dut.io.request.ready.toBoolean)
        dut.io.request.payload.mask #= BigInt(0xa5c30000L | mask)
        dut.io.request.payload.address #= base
        dut.io.request.payload.store #= store
        dut.io.request.payload.predecrement #= pre
        dut.io.request.payload.postincrement #= post
        dut.io.request.valid #= true
        cd.waitSampling()
        dut.io.request.valid #= false
        cd.waitSampling()
        val illegal = (pre && !store) || (post && store) || (pre && post)
        // Oracle follows architectural FP register order, not the hardware's
        // bit-priority helper. Masks differ between predecrement and control.
        val regs = if (illegal) Seq.empty else {
          val order = if (pre) (7 to 0 by -1) else (0 to 7)
          order.filter(r => (mask & (1 << (if (pre) r else 7-r))) != 0)
        }
        var cursor = base
        var completed = 0
        var faultAddress = 0L
        var didFault = false
        for ((reg, i) <- regs.zipWithIndex if !didFault) {
          val address = (if (pre) cursor-12 else cursor) & 0xffffffffL
          assert(dut.io.element.valid.toBoolean)
          assert(!dut.io.request.ready.toBoolean)
          for (_ <- 0 until 1+rng.nextInt(4)) {
            assert(dut.io.element.payload.fpReg.toInt == reg)
            assert(dut.io.element.payload.address.toLong == address)
            assert(dut.io.element.payload.store.toBoolean == store)
            cd.waitSampling()
          }
          dut.io.element.ready #= true
          cd.waitSampling()
          dut.io.element.ready #= false
          cd.waitSampling()
          for (_ <- 0 until 1+rng.nextInt(4)) {
            assert(!dut.io.element.valid.toBoolean)
            assert(!dut.io.result.valid.toBoolean)
            cd.waitSampling()
          }
          didFault = i == faultAt
          dut.io.completion.payload #= didFault
          dut.io.completion.valid #= true
          cd.waitSampling()
          dut.io.completion.valid #= false
          cd.waitSampling()
          if (didFault) faultAddress = address
          else {
            cursor = (if (pre) address else cursor+12) & 0xffffffffL
            completed += 1
          }
        }
        for (_ <- 0 until 3) {
          assert(dut.io.result.valid.toBoolean)
          assert(!dut.io.element.valid.toBoolean)
          assert(dut.io.result.payload.illegal.toBoolean == illegal)
          assert(dut.io.result.payload.fault.toBoolean == didFault)
          assert(dut.io.result.payload.completed.toInt == completed)
          assert(dut.io.result.payload.nextAddress.toLong == cursor)
          assert(dut.io.result.payload.faultAddress.toLong == faultAddress)
          cd.waitSampling()
        }
        dut.io.result.ready #= true
        cd.waitSampling()
        dut.io.result.ready #= false
        cd.waitSampling()
      }
      for (mask <- 0 until 256) {
        run(mask, false, false, false, 0x20001L)
        run(mask, false, false, true, 0x20001L)
        run(mask, false, true, false, 0xfffffff1L)
        run(mask, true, false, true, 7L)
      }
      for (i <- 0 until 8) {
        run(255, false, false, false, 0x20ff9L, i)
        run(255, true, false, true, 0x21007L, i)
      }
      for (mask <- Seq(0, 255)) {
        run(mask, true, false, false, 0x30000L)
        run(mask, false, true, true, 0x30000L)
        run(mask, true, true, true, 0x30000L)
      }
    }
  }
}
