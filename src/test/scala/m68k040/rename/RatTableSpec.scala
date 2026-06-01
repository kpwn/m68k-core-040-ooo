package m68k040.rename

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class RatTableSpec extends AnyFunSuite {
  def mk = RatTable(physIdWidth = 6, archDepth = 16, writePorts = 2, commitPorts = 2, readPorts = 2)
  test("write then read returns speculative mapping", VerilatorTest) {
    SimConfig.withVerilator.compile(mk).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.rollback #= false
      dut.io.writes.foreach(_.valid #= false); dut.io.commits.foreach(_.valid #= false)
      dut.clockDomain.waitSampling()
      dut.io.writes(0).valid #= true; dut.io.writes(0).addr #= 3; dut.io.writes(0).data #= 40
      dut.clockDomain.waitSampling(); dut.io.writes(0).valid #= false; dut.clockDomain.waitSampling()
      dut.io.reads(0).addr #= 3; sleep(1)
      assert(dut.io.reads(0).data.toInt == 40)
    }
  }
  test("commit + rollback restores committed mapping (O(1))", VerilatorTest) {
    SimConfig.withVerilator.compile(mk).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.rollback #= false; dut.io.writes.foreach(_.valid #= false); dut.io.commits.foreach(_.valid #= false)
      dut.clockDomain.waitSampling()
      dut.io.commits(0).valid #= true; dut.io.commits(0).addr #= 5; dut.io.commits(0).data #= 12
      dut.clockDomain.waitSampling(); dut.io.commits(0).valid #= false
      dut.io.writes(0).valid #= true; dut.io.writes(0).addr #= 5; dut.io.writes(0).data #= 33
      dut.clockDomain.waitSampling(); dut.io.writes(0).valid #= false; dut.clockDomain.waitSampling()
      dut.io.reads(0).addr #= 5; sleep(1)
      assert(dut.io.reads(0).data.toInt == 33, "spec mapping before rollback")
      dut.io.rollback #= true; dut.clockDomain.waitSampling(); dut.io.rollback #= false; dut.clockDomain.waitSampling()
      dut.io.reads(0).addr #= 5; sleep(1)
      assert(dut.io.reads(0).data.toInt == 12, "committed mapping after rollback")
    }
  }
  test("two write ports same addr -> later port wins", VerilatorTest) {
    SimConfig.withVerilator.compile(mk).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.rollback #= false; dut.io.writes.foreach(_.valid #= false); dut.io.commits.foreach(_.valid #= false)
      dut.clockDomain.waitSampling()
      dut.io.writes(0).valid #= true; dut.io.writes(0).addr #= 7; dut.io.writes(0).data #= 20
      dut.io.writes(1).valid #= true; dut.io.writes(1).addr #= 7; dut.io.writes(1).data #= 21
      dut.clockDomain.waitSampling(); dut.io.writes.foreach(_.valid #= false); dut.clockDomain.waitSampling()
      dut.io.reads(0).addr #= 7; sleep(1)
      assert(dut.io.reads(0).data.toInt == 21, "later write port wins WAW")
    }
  }
}
