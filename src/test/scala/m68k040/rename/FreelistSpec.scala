package m68k040.rename

import m68k040.{M68kSim, VerilatorTest}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class FreelistSpec extends AnyFunSuite {
  def mk = Freelist(physCount = 48, archCount = 16, popPorts = 2, pushPorts = 2)
  test("pops return distinct free ids (>= archCount)", VerilatorTest) {
    M68kSim().withVerilator.compile(mk).doSim { dut =>
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
    M68kSim().withVerilator.compile(mk).doSim { dut =>
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
    M68kSim().withVerilator.compile(mk).doSim { dut =>
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

  // freelist-flush-invariant campaign: this component's push-consumption block is
  // gated `when(initDone && !io.flush)` (see Freelist.scala), so a push offered on
  // the SAME cycle io.flush is high is silently dropped -- the pushed id never
  // enters the ring. At the raw Freelist IO level nothing stops a caller from
  // driving push+flush concurrently (Freelist has no notion of "legitimate
  // commit"), so this test documents that CURRENT, EXPECTED raw behavior directly
  // rather than asserting it can never be poked.
  //
  // This combination is NOT reachable from real commit traffic in the integrated
  // core: RenameStage wires Freelist.io.push(k).valid directly from
  // `commitPorts(k).valid && commitPorts(k).<x>Write`, and RobPlugin.scala's
  // `headReady` (which gates EVERY producer of commitPorts(k).valid -- both the
  // driveCommit/retire0/retire1 path and the sysOp-read/sysTriggerSig path) ANDs
  // in `!flushing` directly. `flushing` is the exact same wire RenameStage forwards
  // to Freelist.io.flush (`rc.flushPort := flushing`), so `commitPorts(k).valid &&
  // flushing` is architecturally unreachable by construction, not merely by a
  // retire-ordering convention (branch-mispredict commit landing the cycle before
  // doFlushReg asserts, excSquash occupying the ROB head for its whole run). See
  // RobPlugin.scala's `headReady` definition and the `GenerationFlags.simulation {
  // assert(...) }` immediately after `rc.flushPort := flushing`, which pins that
  // upstream guarantee so a future commit path that bypasses `headReady` trips
  // there instead of silently leaking a physreg here.
  test("push concurrent with flush is dropped at the raw component level (documents the invariant)", VerilatorTest) {
    M68kSim().withVerilator.compile(mk).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.flush #= false; dut.io.pop.foreach(_.take #= false); dut.io.push.foreach(_.valid #= false)
      dut.clockDomain.waitSamplingWhere(dut.io.popReady.toBoolean)   // wait for init to finish
      val countBefore = dut.count.toInt
      // Drive a push (id 5, never yet freed) on the SAME cycle flush is asserted.
      dut.io.push(0).valid #= true; dut.io.push(0).payload #= 5
      dut.io.flush #= true
      dut.clockDomain.waitSampling()
      dut.io.push(0).valid #= false; dut.io.flush #= false; sleep(1)
      // count is unchanged (the push never landed) -- the raw component silently
      // dropped it, exactly as Freelist.scala's push-gate comment documents.
      assert(dut.count.toInt == countBefore,
        s"push concurrent with flush should be dropped at the raw component level: " +
        s"count before=$countBefore after=${dut.count.toInt}")
      // Inspect only the occupied ring. Walking 60 entries would underflow the
      // 32-entry pool and interpret uninitialized RAM as valid allocated IDs.
      // id 5 never becomes poppable (it was never actually returned to the pool).
      var seen = false; var i = 0
      while (!seen && i < countBefore) {
        if (dut.io.pop(0).id.toInt == 5) seen = true
        else { dut.io.pop(0).take #= true; dut.clockDomain.waitSampling(); dut.io.pop(0).take #= false; sleep(1) }
        i += 1
      }
      assert(!seen, "dropped push must NOT have silently entered the ring")
    }
  }
}
