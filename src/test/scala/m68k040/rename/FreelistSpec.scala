package m68k040.rename

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class FreelistSpec extends AnyFunSuite {
  def mk = Freelist(physCount = 48, archCount = 16, popPorts = 2, pushPorts = 2)
  test("pops return distinct free ids (>= archCount)", VerilatorTest) {
    SimConfig.withVerilator.compile(mk).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.flush #= false; dut.io.pop.foreach(_.take #= false); dut.io.push.foreach(_.valid #= false)
      dut.clockDomain.waitSamplingWhere(dut.io.popReady.toBoolean)   // wait for init to finish
      // Set both takes so each port's id is computed at its true offset (head+0, head+1)
      dut.io.pop(0).take #= true; dut.io.pop(1).take #= true; sleep(1)
      val a = dut.io.pop(0).id.toInt; val b = dut.io.pop(1).id.toInt
      assert(a >= 16 && b >= 16 && a != b, s"distinct free ids >=16: a=$a b=$b")
      dut.clockDomain.waitSampling(); dut.io.pop.foreach(_.take #= false); sleep(1)
      val c = dut.io.pop(0).id.toInt
      assert(c != a && c != b, s"next pop distinct from consumed: c=$c")
    }
  }
  test("non-prefix take (only pop(1)) consumes exactly one distinct id", VerilatorTest) {
    SimConfig.withVerilator.compile(mk).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.flush #= false; dut.io.pop.foreach(_.take #= false); dut.io.push.foreach(_.valid #= false)
      dut.clockDomain.waitSamplingWhere(dut.io.popReady.toBoolean)
      val id1 = dut.io.pop(1).id.toInt           // the id slot1 will consume
      // take ONLY pop(1) (slot0 does not allocate)
      dut.io.pop(0).take #= false; dut.io.pop(1).take #= true
      dut.clockDomain.waitSampling(); dut.io.pop.foreach(_.take #= false); sleep(1)
      // after consuming exactly one id, neither pop port may return id1 again (no double-alloc)
      assert(dut.io.pop(0).id.toInt != id1 && dut.io.pop(1).id.toInt != id1,
        s"id $id1 was consumed by pop(1) but reappeared (double-allocation)")
    }
  }
  test("push returns an id to the pool", VerilatorTest) {
    SimConfig.withVerilator.compile(mk).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.flush #= false; dut.io.pop.foreach(_.take #= false); dut.io.push.foreach(_.valid #= false)
      dut.clockDomain.waitSamplingWhere(dut.io.popReady.toBoolean)
      dut.io.push(0).valid #= true; dut.io.push(0).payload #= 5
      dut.clockDomain.waitSampling(); dut.io.push(0).valid #= false; dut.clockDomain.waitSampling()
      var seen = false; var i = 0
      while (!seen && i < 60) {
        if (dut.io.pop(0).id.toInt == 5) seen = true
        else { dut.io.pop(0).take #= true; dut.clockDomain.waitSampling(); dut.io.pop(0).take #= false; sleep(1) }
        i += 1
      }
      assert(seen, "pushed id 5 should become poppable")
    }
  }
}
