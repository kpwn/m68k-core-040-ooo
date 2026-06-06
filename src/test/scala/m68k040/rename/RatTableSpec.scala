package m68k040.rename

import m68k040.{M68kSim, VerilatorTest}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class RatTableSpec extends AnyFunSuite {
  def mk = RatTable(physIdWidth = 6, archDepth = 16, writePorts = 2, commitPorts = 2, readPorts = 2)
  test("write then read returns speculative mapping", VerilatorTest) {
    M68kSim().withVerilator.compile(mk).doSim { dut =>
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
    M68kSim().withVerilator.compile(mk).doSim { dut =>
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
    M68kSim().withVerilator.compile(mk).doSim { dut =>
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
  // ── Regression: the flag-RAT (archDepth=1) lone-slot-0 write bug ──────────────
  // The NZVC/X RATs are 1-entry, 2-write-port tables where BOTH write ports always
  // address arch 0. The old multi-write async-read Mem dropped write port 0: a lone
  // slot-0 write never persisted (only the highest write port ever updated the
  // cell). A later branch then read a STALE youngest-flag mapping -> the loop-with-
  // load divergence. This pins that a write through port 0 ALONE is visible to a
  // later read in the exact flag-RAT configuration.
  def mk1 = RatTable(physIdWidth = 4, archDepth = 1, writePorts = 2, commitPorts = 2, readPorts = 2)
  test("1-entry RAT: lone write port 0 persists to a later read", VerilatorTest) {
    M68kSim().withVerilator.compile(mk1).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.rollback #= false; dut.io.writes.foreach(_.valid #= false); dut.io.commits.foreach(_.valid #= false)
      dut.io.writes.foreach(_.addr #= 0); dut.io.commits.foreach(_.addr #= 0); dut.io.reads.foreach(_.addr #= 0)
      dut.clockDomain.waitSampling()
      // Cycle A: write port 1 alone -> p9 (mirrors the cracked move taking slot 1).
      dut.io.writes(1).valid #= true; dut.io.writes(1).data #= 9
      dut.clockDomain.waitSampling(); dut.io.writes(1).valid #= false
      // Cycle B: write port 0 ALONE -> p10 (mirrors the sub renaming alone in slot 0).
      dut.io.writes(0).valid #= true; dut.io.writes(0).data #= 10
      dut.clockDomain.waitSampling(); dut.io.writes(0).valid #= false
      // Idle a couple cycles (the bne reads several cycles later), then read.
      dut.clockDomain.waitSampling(2)
      dut.io.reads(0).addr #= 0; sleep(1)
      assert(dut.io.reads(0).data.toInt == 10,
        s"lone slot-0 write must win the youngest mapping, got p${dut.io.reads(0).data.toInt} (pre-fix: stale p9)")
    }
  }

  test("multi-port writes reconstruct via lowered banks (XOR)", VerilatorTest) {
    M68kSim().withVerilator.compile(mk).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      dut.io.rollback #= false
      dut.io.writes.foreach { w => w.valid #= false }
      dut.io.commits.foreach { c => c.valid #= false }
      cd.waitSampling(2)
      dut.io.writes(0).valid #= true; dut.io.writes(0).addr #= 3; dut.io.writes(0).data #= 17
      dut.io.writes(1).valid #= true; dut.io.writes(1).addr #= 9; dut.io.writes(1).data #= 42
      cd.waitSampling()
      dut.io.writes.foreach { w => w.valid #= false }
      cd.waitSampling()
      dut.io.reads(0).addr #= 3
      dut.io.reads(1).addr #= 9
      sleep(1)
      assert(dut.io.reads(0).data.toBigInt == 17, s"reg3 got ${dut.io.reads(0).data.toBigInt}")
      assert(dut.io.reads(1).data.toBigInt == 42, s"reg9 got ${dut.io.reads(1).data.toBigInt}")
    }
  }
}
