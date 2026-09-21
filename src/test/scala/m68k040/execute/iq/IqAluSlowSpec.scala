package m68k040.execute.iq

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.{Cluster, MemOp}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** IQ dynamic wakeup for SLOW-ALU (shift, S3-completing) producers. A dependent of a shift
  * (int OR flag source) is held NOT-ready until the ALU EU broadcasts aluSlowWakeup
  * (emulated here). A dependent of a FAST ALU op issues via the static latency-1
  * trigger with NO such broadcast (unchanged). */
class IqAluSlowSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val iq     = new IssueQueuePlugin
    val source = new IqSourcePlugin
    val sink   = new IqSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), iq, source, sink)) }
  }

  def idle(dut: Dut): Unit = {
    val s = dut.source.logic
    s.pushValid #= false; s.slot1Valid #= false; s.flush #= false
    s.aluFastAccept0 #= true; s.aluFastAccept1 #= true
    for (slot <- Seq(s.s0, s.s1)) {
      slot.robId #= 0; slot.cluster #= Cluster.INT; slot.memOp #= MemOp.NONE
      slot.pdst #= 0; slot.pdstValid #= false
      slot.psrcA #= 0; slot.psrcAValid #= false
      slot.psrcB #= 0; slot.psrcBValid #= false
      slot.useImm #= false
      slot.readsNzvc #= false; slot.writesNzvc #= false
      slot.pNzvcSrc #= 0; slot.pNzvcDst #= 0
      slot.readsX #= false; slot.writesX #= false
      slot.pXSrc #= 0; slot.pXDst #= 0
      slot.isShift #= false
      // These ALU-only tests must not acquire random FP dependencies from
      // uninitialized top-level inputs in the shared source harness.
      slot.pFpSrcA #= 0; slot.psrcAFpValid #= false
      slot.pFpSrcB #= 0; slot.psrcBFpValid #= false
      slot.pFpDst #= 0; slot.pFpDstValid #= false
      slot.pFpccSrc #= 0; slot.readsFpcc #= false
      slot.pFpccDst #= 0; slot.writesFpcc #= false
    }
    dut.sink.logic.ready0 #= true
    dut.sink.logic.ready1 #= true
    dut.sink.logic.ready3 #= true
    val s2 = dut.source.logic
    s2.lsWakeupValid #= false; s2.lsWakeupPdst #= 0
    s2.aluSlowWakeupValid #= false
    s2.aluSlowWakeupPdst #= 0; s2.aluSlowWakeupPdstV #= false
    s2.aluSlowWakeupNzvc #= 0; s2.aluSlowWakeupNzvcV #= false
    s2.aluSlowWakeupX #= 0; s2.aluSlowWakeupXV #= false
    s2.cplxFpWakeupValid #= false; s2.cplxFpWakeupTag #= 0
    s2.cplxFpccWakeupValid #= false; s2.cplxFpccWakeupTag #= 0
  }

  def issued(dut: Dut, rob: Int): Boolean = {
    val k = dut.sink.logic
    (k.v0.toBoolean && k.rob0.toInt == rob) || (k.v1.toBoolean && k.rob1.toInt == rob)
  }

  test("dependent of a FAST ALU producer issues (static lat1, no wakeup needed)", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut); cd.waitSampling(4)
      val s = dut.source.logic
      // fast producer rob1 -> pdst 9
      s.s0.robId #= 1; s.s0.pdst #= 9; s.s0.pdstValid #= true; s.s0.isShift #= false
      s.pushValid #= true; s.slot1Valid #= false
      cd.waitSamplingWhere(s.pushReady.toBoolean)
      // dependent rob2 reads pdst 9
      s.s0.robId #= 2; s.s0.pdst #= 20; s.s0.pdstValid #= true
      s.s0.psrcA #= 9; s.s0.psrcAValid #= true; s.s0.isShift #= false
      cd.waitSamplingWhere(s.pushReady.toBoolean)
      s.pushValid #= false
      var dep = false
      for (_ <- 0 until 10) { if (issued(dut, 2)) dep = true; cd.waitSampling() }
      assert(dep, "fast-producer dependent must issue (static lat1 trigger)")
    }
  }

  test("int-dependent of a SLOW shift waits for aluSlowWakeup", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut); cd.waitSampling(4)
      val s = dut.source.logic
      // shift producer rob1 -> int pdst 9
      s.s0.robId #= 1; s.s0.pdst #= 9; s.s0.pdstValid #= true; s.s0.isShift #= true
      s.pushValid #= true; s.slot1Valid #= false
      cd.waitSamplingWhere(s.pushReady.toBoolean)
      // dependent rob2 reads int pdst 9
      s.s0.robId #= 2; s.s0.pdst #= 20; s.s0.pdstValid #= true
      s.s0.psrcA #= 9; s.s0.psrcAValid #= true; s.s0.isShift #= false
      cd.waitSamplingWhere(s.pushReady.toBoolean)
      s.pushValid #= false
      // The dependent must NOT issue before the wakeup.
      var early = false
      for (_ <- 0 until 6) { if (issued(dut, 2)) early = true; cd.waitSampling() }
      assert(!early, "shift int-dependent must NOT issue before aluSlowWakeup")
      // broadcast the shift's int wakeup (pdst 9).
      s.aluSlowWakeupValid #= true; s.aluSlowWakeupPdst #= 9; s.aluSlowWakeupPdstV #= true
      cd.waitSampling()
      s.aluSlowWakeupValid #= false; s.aluSlowWakeupPdstV #= false
      var dep = false
      for (_ <- 0 until 6) { if (issued(dut, 2)) dep = true; cd.waitSampling() }
      assert(dep, "shift int-dependent must issue after aluSlowWakeup")
    }
  }

  test("FLAG-dependent of a SLOW shift waits for aluSlowWakeup (NZVC)", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut); cd.waitSampling(4)
      val s = dut.source.logic
      // shift producer rob1: int pdst 9 + NZVC dst 5
      s.s0.robId #= 1; s.s0.pdst #= 9; s.s0.pdstValid #= true
      s.s0.writesNzvc #= true; s.s0.pNzvcDst #= 5; s.s0.isShift #= true
      s.pushValid #= true; s.slot1Valid #= false
      cd.waitSamplingWhere(s.pushReady.toBoolean)
      // flag-dependent rob2: reads NZVC physreg 5 (no int src dep)
      s.s0.robId #= 2; s.s0.pdst #= 20; s.s0.pdstValid #= true
      s.s0.psrcAValid #= false
      s.s0.readsNzvc #= true; s.s0.pNzvcSrc #= 5; s.s0.writesNzvc #= false; s.s0.isShift #= false
      cd.waitSamplingWhere(s.pushReady.toBoolean)
      s.pushValid #= false
      var early = false
      for (_ <- 0 until 6) { if (issued(dut, 2)) early = true; cd.waitSampling() }
      assert(!early, "shift flag-dependent must NOT issue before aluSlowWakeup")
      // broadcast the shift's wakeup carrying the NZVC dst (5).
      s.aluSlowWakeupValid #= true
      s.aluSlowWakeupPdst #= 9; s.aluSlowWakeupPdstV #= true
      s.aluSlowWakeupNzvc #= 5; s.aluSlowWakeupNzvcV #= true
      cd.waitSampling()
      s.aluSlowWakeupValid #= false; s.aluSlowWakeupPdstV #= false; s.aluSlowWakeupNzvcV #= false
      var dep = false
      for (_ <- 0 until 6) { if (issued(dut, 2)) dep = true; cd.waitSampling() }
      assert(dep, "shift flag-dependent must issue after aluSlowWakeup (NZVC)")
    }
  }

  test("candidate masks route compacted SLOW around an older blocked FAST", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut); cd.waitSampling(4)
      val s = dut.source.logic
      dut.sink.logic.ready0 #= false; dut.sink.logic.ready1 #= false

      // First group: oldest FAST followed by younger SLOW.
      s.s0.robId #= 10; s.s0.pdst #= 10; s.s0.pdstValid #= true; s.s0.isShift #= false
      s.s1.robId #= 11; s.s1.pdst #= 11; s.s1.pdstValid #= true; s.s1.isShift #= true
      s.pushValid #= true; s.slot1Valid #= true
      cd.waitSamplingWhere(s.pushReady.toBoolean)

      // A second push compacts the first group, proving the stored slow bit moves
      // with its slot rather than being freshly decoded after selection.
      s.s0.robId #= 12; s.s0.pdst #= 12; s.s0.pdstValid #= true; s.s0.isShift #= false
      s.slot1Valid #= false
      cd.waitSamplingWhere(s.pushReady.toBoolean)
      s.pushValid #= false

      s.aluFastAccept0 #= false
      s.aluFastAccept1 #= true
      dut.sink.logic.ready0 #= true; dut.sink.logic.ready1 #= true
      var sawPair = false
      for (_ <- 0 until 6) {
        cd.waitSampling(); sleep(1)
        if (dut.sink.logic.v0.toBoolean && dut.sink.logic.v1.toBoolean) {
          assert(dut.sink.logic.rob0.toInt == 11,
            s"port0 must take younger SLOW, got rob${dut.sink.logic.rob0.toInt}")
          assert(dut.sink.logic.rob1.toInt == 10,
            s"port1 must take oldest FAST, got rob${dut.sink.logic.rob1.toInt}")
          sawPair = true
        }
      }
      assert(sawPair, "did not observe simultaneous SLOW-port0 / FAST-port1 routing")
    }
  }

  test("both blocked-fast forecasts still issue SLOW and retain FAST", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      idle(dut); cd.waitSampling(4)
      val s = dut.source.logic
      dut.sink.logic.ready0 #= false; dut.sink.logic.ready1 #= false
      s.s0.robId #= 20; s.s0.pdst #= 20; s.s0.pdstValid #= true; s.s0.isShift #= false
      s.s1.robId #= 21; s.s1.pdst #= 21; s.s1.pdstValid #= true; s.s1.isShift #= true
      s.pushValid #= true; s.slot1Valid #= true
      cd.waitSamplingWhere(s.pushReady.toBoolean)
      s.pushValid #= false

      s.aluFastAccept0 #= false; s.aluFastAccept1 #= false
      dut.sink.logic.ready0 #= true; dut.sink.logic.ready1 #= true
      var sawSlow = false; var sawFastEarly = false
      for (_ <- 0 until 5) {
        cd.waitSampling(); sleep(1)
        if (issued(dut, 21)) sawSlow = true
        if (issued(dut, 20)) sawFastEarly = true
      }
      assert(sawSlow, "SLOW candidate was incorrectly blocked with both forecasts false")
      assert(!sawFastEarly, "FAST candidate escaped while both forecasts were false")

      s.aluFastAccept0 #= true; s.aluFastAccept1 #= true
      var sawFast = false
      for (_ <- 0 until 5) { cd.waitSampling(); sleep(1); if (issued(dut, 20)) sawFast = true }
      assert(sawFast, "retained FAST candidate did not issue after forecast reopened")
    }
  }
}
