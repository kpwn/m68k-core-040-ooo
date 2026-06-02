package m68k040.execute.iq

import m68k040.M68kSim
import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

class IssueQueueSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val iq     = new IssueQueuePlugin
    val source = new IqSourcePlugin
    val sink   = new IqSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](iq, source, sink)) }
  }

  /** Drive both push slots independent/ready with the given robIds. */
  def setPush(dut: Dut, valid: Boolean, slot1Valid: Boolean, rob0: Int, rob1: Int): Unit = {
    val s = dut.source.logic
    s.pushValid #= valid
    s.slot1Valid #= slot1Valid
    s.s0.robId #= rob0
    s.s1.robId #= rob1
  }

  def idle(dut: Dut): Unit = {
    val s = dut.source.logic
    s.pushValid #= false; s.slot1Valid #= false; s.flush #= false
    for (slot <- Seq(s.s0, s.s1)) {
      slot.robId #= 0
      slot.pdst #= 0; slot.pdstValid #= false
      slot.psrcA #= 0; slot.psrcAValid #= false
      slot.psrcB #= 0; slot.psrcBValid #= false
      slot.useImm #= false
      slot.readsNzvc #= false; slot.writesNzvc #= false
      slot.pNzvcSrc #= 0; slot.pNzvcDst #= 0
      slot.readsX #= false; slot.writesX #= false
      slot.pXSrc #= 0; slot.pXDst #= 0
    }
    dut.sink.logic.ready0 #= false
    dut.sink.logic.ready1 #= false
  }

  test("2-wide independent issue, age order", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Sinks always ready.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true

      // Push robIds 0..5 two-per-cycle (3 cycles), all independent.
      // Push only while push.ready (it will stay ready, queue drains fully).
      val buf = ArrayBuffer[(Boolean, Int, Boolean, Int)]()
      val pushSeq = Seq((0, 1), (2, 3), (4, 5))
      var pi = 0
      // Run enough cycles to push all and observe issue.
      for (_ <- 0 until 12) {
        if (pi < pushSeq.size && dut.source.logic.pushReady.toBoolean) {
          val (a, b) = pushSeq(pi)
          setPush(dut, valid = true, slot1Valid = true, a, b)
          pi += 1
        } else {
          setPush(dut, valid = false, slot1Valid = false, 0, 0)
        }
        cd.waitSampling()
        // sample issue outputs (registered slots -> combinational issue this cycle)
        val s = dut.sink.logic
        buf += ((s.v0.toBoolean, s.rob0.toInt, s.v1.toBoolean, s.rob1.toInt))
      }

      // Extract the issued pairs in order.
      val issued = ArrayBuffer[Int]()
      for ((v0, r0, v1, r1) <- buf) {
        if (v0) issued += r0
        if (v1) issued += r1
      }
      assert(issued.toSeq == Seq(0, 1, 2, 3, 4, 5),
        s"expected ascending 0..5, got ${issued.mkString(",")}")
    }
  }

  test("back-pressure: pushReady deasserts after 16 queued, re-asserts on drain", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Sinks NOT ready -> nothing drains.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false

      // Push two-per-cycle with valid held continuously and distinct robIds per
      // cycle. After enough cycles the queue fills (16 slots = 8 fires) and
      // pushReady deasserts. We then drain and count issued uops: exactly 16
      // must come out (no slot lost or duplicated), proving the queue admitted
      // exactly 8 two-wide pushes before back-pressuring.
      for (cyc <- 0 until 20) {
        // robIds 0..15 across the first 8 cycles; later cycles repeat but never
        // fire (queue full), so they are harmless.
        val a = (cyc * 2) % 64
        val b = (cyc * 2 + 1) % 64
        setPush(dut, valid = true, slot1Valid = true, a, b)
        cd.waitSampling()
      }
      // valid still high but queue full -> pushReady deasserted.
      assert(!dut.source.logic.pushReady.toBoolean, "pushReady should be deasserted when full")

      setPush(dut, valid = false, slot1Valid = false, 0, 0)
      cd.waitSampling()

      // Drain: enable sinks, count distinct issued uops.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      val drained = scala.collection.mutable.Set[Int]()
      for (_ <- 0 until 16) {
        cd.waitSampling()
        val s = dut.sink.logic
        if (s.v0.toBoolean) drained += s.rob0.toInt
        if (s.v1.toBoolean) drained += s.rob1.toInt
      }
      // The 16 admitted uops carried robIds 0..15 (first 8 push cycles).
      assert(drained == (0 until 16).toSet,
        s"expected robIds 0..15 to drain, got ${drained.toSeq.sorted.mkString(",")}")
      assert(dut.source.logic.pushReady.toBoolean, "pushReady should re-assert after drain")
    }
  }

  test("flush discards queued uops; fresh pushes still issue", VerilatorTest) {
    M68kSim().compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      idle(dut)
      cd.waitSampling(2)

      // Sinks not ready -> queue holds.
      dut.sink.logic.ready0 #= false
      dut.sink.logic.ready1 #= false

      // Push 4 uops (robIds 40..43) over 2 cycles (robId is 6 bits, 0..63).
      setPush(dut, valid = true, slot1Valid = true, 40, 41); cd.waitSampling()
      setPush(dut, valid = true, slot1Valid = true, 42, 43); cd.waitSampling()
      setPush(dut, valid = false, slot1Valid = false, 0, 0); cd.waitSampling()

      // Flush for one cycle.
      dut.source.logic.flush #= true
      cd.waitSampling()
      dut.source.logic.flush #= false
      cd.waitSampling()

      // Enable sinks; assert the flushed robIds NEVER issue.
      dut.sink.logic.ready0 #= true
      dut.sink.logic.ready1 #= true
      for (_ <- 0 until 6) {
        val s = dut.sink.logic
        val flushedSet = Set(40, 41, 42, 43)
        if (s.v0.toBoolean) assert(!flushedSet.contains(s.rob0.toInt), s"flushed uop ${s.rob0.toInt} issued on port0")
        if (s.v1.toBoolean) assert(!flushedSet.contains(s.rob1.toInt), s"flushed uop ${s.rob1.toInt} issued on port1")
        cd.waitSampling()
      }

      // Push 2 fresh uops; assert they issue.
      val got = ArrayBuffer[Int]()
      setPush(dut, valid = true, slot1Valid = true, 50, 51); cd.waitSampling()
      setPush(dut, valid = false, slot1Valid = false, 0, 0)
      for (_ <- 0 until 6) {
        val s = dut.sink.logic
        if (s.v0.toBoolean) got += s.rob0.toInt
        if (s.v1.toBoolean) got += s.rob1.toInt
        cd.waitSampling()
      }
      assert(got.contains(50) && got.contains(51), s"fresh uops did not issue, got ${got.mkString(",")}")
    }
  }
}
