package m68k040.frontend

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class RetainedHistoryRepairSpec extends AnyFunSuite {
  test("retained suffix rebases across width saturation, replacement and flush", VerilatorTest) {
    SimConfig.withVerilator.compile(new RetainedHistoryRepair(16)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.start #= false; dut.io.flush #= false; dut.io.keep #= false
      dut.io.repair #= false; dut.io.invalidate #= false
      dut.io.shift #= false; dut.io.direction #= false; dut.io.arch #= 0
      cd.waitSampling(3)
      def edge(): Unit = { cd.waitSampling(); sleep(1) }
      def start(): Unit = {
        // This same-edge event belongs to the discarded, not refetched, stream.
        dut.io.start #= true; dut.io.shift #= true; dut.io.direction #= true
        edge()
        dut.io.start #= false; dut.io.shift #= false
      }
      def append(bit: Boolean): Unit = {
        dut.io.shift #= true; dut.io.direction #= bit; edge(); dut.io.shift #= false
      }
      val rng = new scala.util.Random(0x68040)
      for (size <- Seq(0, 1, 2, 15, 16, 17, 40); sameEdge <- Seq(false, true)) {
        start()
        var suffix = Vector.fill(size)(rng.nextBoolean())
        suffix.foreach(append)
        // A newer recovery decision for an OLDER branch invalidates this suffix.
        if (size == 17) {
          start()
          suffix = Vector(true, false, true)
          suffix.foreach(append)
        }
        val arch = rng.nextInt(65536)
        dut.io.arch #= arch
        dut.io.flush #= true; dut.io.keep #= true
        edge()
        dut.io.flush #= false; dut.io.keep #= false; dut.io.repair #= true
        dut.io.shift #= sameEdge; dut.io.direction #= true
        sleep(1)
        val bits = if (sameEdge) suffix :+ true else suffix
        val expected = bits.foldLeft(arch)((h, b) => ((h << 1) | (if (b) 1 else 0)) & 65535)
        assert(dut.io.preserved.toBoolean)
        assert(dut.io.value.toInt == expected, s"size=$size sameEdge=$sameEdge")
        edge()
        dut.io.repair #= false; dut.io.shift #= false
        sleep(1)
        assert(!dut.io.preserved.toBoolean)
      }
      for (invalidating <- Seq(false, true)) {
        start(); append(true); append(false)
        dut.io.arch #= 0x9a35
        dut.io.flush #= true; dut.io.keep #= invalidating
        dut.io.invalidate #= invalidating
        edge()
        dut.io.flush #= false; dut.io.keep #= false; dut.io.invalidate #= false
        dut.io.repair #= true; dut.io.shift #= true; dut.io.direction #= true
        sleep(1)
        assert(!dut.io.preserved.toBoolean)
        assert(dut.io.value.toInt == 0x9a35, "discarded suffix must not survive repair")
        edge(); dut.io.repair #= false; dut.io.shift #= false
      }
      // A replacement redirect on the delayed repair edge must discard the
      // prior epoch, and must not append that edge's old-path prediction.
      start(); append(true); append(true)
      dut.io.arch #= 0x1234
      dut.io.flush #= true; dut.io.keep #= true; edge()
      dut.io.flush #= false; dut.io.keep #= false
      dut.io.repair #= true; dut.io.start #= true
      dut.io.shift #= true; dut.io.direction #= true
      sleep(1)
      assert(!dut.io.preserved.toBoolean)
      assert(dut.io.value.toInt == 0x1234)
      edge()
      dut.io.repair #= false; dut.io.start #= false; dut.io.shift #= false
      append(false)
      dut.io.flush #= true; dut.io.keep #= true; edge()
      dut.io.flush #= false; dut.io.keep #= false; dut.io.repair #= true
      sleep(1)
      assert(dut.io.preserved.toBoolean)
      assert(dut.io.value.toInt == 0x2468, "replacement must retain only its new suffix")
      edge(); dut.io.repair #= false
    }
  }
}
