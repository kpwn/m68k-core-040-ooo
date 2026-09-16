package m68k040.execute.iq

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.{Cluster, MemOp}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import scala.collection.mutable.ArrayBuffer

/** Directed coverage for the FP-data and FPCC dependency classes in the IQ (Task 3).
  *
  * FP ops are CPLX-cluster and variable-latency, so they use completion wakeup, not the
  * latency-1 trigger matrix. Two properties are load-bearing and are proven here:
  *   1. a dyadic consumer reading TWO in-flight FP results is NOT released by the first
  *      wakeup (a single cplxFpWait bit covers both sources -- releasing early would read
  *      a stale 80-bit PRF entry, a SILENT wrong result, not a hang);
  *   2. an FPCC reader is tracked independently of the FP-data class (FCMP/FTST write FPCC
  *      with no FP dst at all), and flush clears both bitmaps.
  */
class IqFpSpec extends AnyFunSuite {
  class FpIssueObserverPlugin extends FiberPlugin {
    val logic = during build new Area {
      val iq = host[IssueQueueService]
      val fire = out Bool (); val rob = out UInt (m68k040.Global.ROB_ID_W_DEFAULT bits)
      fire := iq.issue(4).fire
      rob  := iq.issue(4).payload.robId
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val iq     = new IssueQueuePlugin
    val source = new IqSourcePlugin
    val sink   = new IqSinkPlugin
    val fpObs  = new FpIssueObserverPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), iq, source, sink, fpObs
    )) }
  }

  case class FpUop(
      rob: Int,
      cluster: SpinalEnumElement[Cluster.type] = Cluster.CPLX,
      pFpSrcA: Int = 0, psrcAFpValid: Boolean = false,
      pFpSrcB: Int = 0, psrcBFpValid: Boolean = false,
      pFpDst: Int = 0,  pFpDstValid: Boolean = false,
      pFpccSrc: Int = 0, readsFpcc: Boolean = false,
      pFpccDst: Int = 0, writesFpcc: Boolean = false)

  private def driveSlot(dut: Dut, slot1: Boolean, u: FpUop): Unit = {
    val s = if (slot1) dut.source.logic.s1 else dut.source.logic.s0
    s.robId #= u.rob; s.cluster #= u.cluster; s.memOp #= MemOp.NONE
    s.pdst #= 0; s.pdstValid #= false
    s.psrcA #= 0; s.psrcAValid #= false
    s.psrcB #= 0; s.psrcBValid #= false
    s.useImm #= false
    s.readsNzvc #= false; s.writesNzvc #= false; s.pNzvcSrc #= 0; s.pNzvcDst #= 0
    s.readsX #= false; s.writesX #= false; s.pXSrc #= 0; s.pXDst #= 0
    s.isShift #= false
    s.pFpSrcA #= u.pFpSrcA; s.psrcAFpValid #= u.psrcAFpValid
    s.pFpSrcB #= u.pFpSrcB; s.psrcBFpValid #= u.psrcBFpValid
    s.pFpDst  #= u.pFpDst;  s.pFpDstValid  #= u.pFpDstValid
    s.pFpccSrc #= u.pFpccSrc; s.readsFpcc  #= u.readsFpcc
    s.pFpccDst #= u.pFpccDst; s.writesFpcc #= u.writesFpcc
  }

  private def idle(dut: Dut): Unit = {
    val src = dut.source.logic
    src.pushValid #= false; src.slot1Valid #= false; src.flush #= false
    src.aluFastAccept0 #= true; src.aluFastAccept1 #= true
    src.lsWakeupValid #= false; src.lsWakeupPdst #= 0
    src.aluSlowWakeupValid #= false; src.aluSlowWakeupPdst #= 0; src.aluSlowWakeupPdstV #= false
    src.aluSlowWakeupNzvc #= 0; src.aluSlowWakeupNzvcV #= false
    src.aluSlowWakeupX #= 0; src.aluSlowWakeupXV #= false
    src.cplxFpWakeupValid #= false;   src.cplxFpWakeupTag #= 0
    src.cplxFpccWakeupValid #= false; src.cplxFpccWakeupTag #= 0
    driveSlot(dut, slot1 = false, FpUop(0, cluster = Cluster.INT))
    driveSlot(dut, slot1 = true,  FpUop(0, cluster = Cluster.INT))
    dut.sink.logic.ready0 #= true; dut.sink.logic.ready1 #= true; dut.sink.logic.ready3 #= true
  }

  private def push(dut: Dut, u0: FpUop, u1: Option[FpUop] = None): Unit = {
    val src = dut.source.logic
    driveSlot(dut, slot1 = false, u0)
    driveSlot(dut, slot1 = true, u1.getOrElse(FpUop(0, cluster = Cluster.INT)))
    src.slot1Valid #= u1.nonEmpty
    src.pushValid #= true
    dut.clockDomain.waitSamplingWhere(src.pushReady.toBoolean)
    src.pushValid #= false; src.slot1Valid #= false
  }

  private def wakeFp(dut: Dut, tag: Int): Unit = {
    dut.source.logic.cplxFpWakeupTag #= tag
    dut.source.logic.cplxFpWakeupValid #= true
    dut.clockDomain.waitSampling()
    dut.source.logic.cplxFpWakeupValid #= false
  }
  private def wakeFpcc(dut: Dut, tag: Int): Unit = {
    dut.source.logic.cplxFpccWakeupTag #= tag
    dut.source.logic.cplxFpccWakeupValid #= true
    dut.clockDomain.waitSampling()
    dut.source.logic.cplxFpccWakeupValid #= false
  }

  test("FP consumers wait for EVERY FP source; FPCC is tracked independently; flush clears both", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      idle(dut)

      val fpIssues = ArrayBuffer[Int]()
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.fpObs.logic.fire.toBoolean) fpIssues += dut.fpObs.logic.rob.toInt
        }
      }
      cd.waitSampling(4)

      // Two in-flight FP producers (FP phys 9 and 10), then a dyadic consumer of both.
      push(dut,
        FpUop(rob = 1, pFpDst = 9,  pFpDstValid = true, pFpccDst = 1, writesFpcc = true),
        Some(FpUop(rob = 2, pFpDst = 10, pFpDstValid = true, pFpccDst = 2, writesFpcc = true)))
      push(dut, FpUop(rob = 3,
        pFpSrcA = 9,  psrcAFpValid = true,
        pFpSrcB = 10, psrcBFpValid = true,
        pFpDst = 11, pFpDstValid = true, pFpccDst = 3, writesFpcc = true))

      var w = 0
      while ((!fpIssues.contains(1) || !fpIssues.contains(2)) && w < 12) { cd.waitSampling(); w += 1 }
      assert(fpIssues.count(_ == 1) == 1 && fpIssues.count(_ == 2) == 1,
        s"both FP producers must issue exactly once, saw ${fpIssues.mkString(",")}")
      assert(!fpIssues.contains(3), "dyadic FP consumer issued before any FP wakeup")

      wakeFp(dut, tag = 9)
      cd.waitSampling(4)
      assert(!fpIssues.contains(3),
        "dyadic FP consumer must stay blocked after only its FIRST FP source wakes " +
        "(a single cplxFpWait bit covers both sources -- an early release reads a stale 80-bit PRF entry)")

      wakeFp(dut, tag = 10)
      w = 0
      while (!fpIssues.contains(3) && w < 8) { cd.waitSampling(); w += 1 }
      assert(fpIssues.count(_ == 3) == 1,
        s"FP consumer must issue exactly once after its SECOND FP wake, saw ${fpIssues.mkString(",")}")

      // FPCC is an independent class: a pure FPCC reader (no FP data source at all, the
      // FBcc/FMOVE-from-FPSR shape) waits on cplxFpccWakeup only.
      push(dut, FpUop(rob = 4, pFpccDst = 5, writesFpcc = true))   // FCMP-shaped: FPCC only, no FP dst
      while (!fpIssues.contains(4)) cd.waitSampling()
      push(dut, FpUop(rob = 5, pFpccSrc = 5, readsFpcc = true))
      cd.waitSampling(4)
      assert(!fpIssues.contains(5), "FPCC reader escaped before its FPCC wakeup")
      wakeFp(dut, tag = 5)   // an FP-DATA wake on the same numeric tag must NOT release it
      cd.waitSampling(3)
      assert(!fpIssues.contains(5),
        "an FP-DATA wakeup must not release an FPCC reader (separate classes, same tag width)")
      wakeFpcc(dut, tag = 5)
      w = 0
      while (!fpIssues.contains(5) && w < 8) { cd.waitSampling(); w += 1 }
      assert(fpIssues.count(_ == 5) == 1, "FPCC reader must issue exactly once after its FPCC wake")

      // Flush must drop a waiting FP consumer and clear BOTH busy bitmaps, so a freshly
      // pushed reader of the same tags issues without ever receiving a wakeup.
      push(dut, FpUop(rob = 6, pFpDst = 12, pFpDstValid = true, pFpccDst = 6, writesFpcc = true))
      while (!fpIssues.contains(6)) cd.waitSampling()
      push(dut, FpUop(rob = 7, pFpSrcA = 12, psrcAFpValid = true, pFpccSrc = 6, readsFpcc = true))
      cd.waitSampling(3)
      assert(!fpIssues.contains(7), "pre-flush FP consumer escaped before its wakeups")
      dut.source.logic.flush #= true
      cd.waitSampling()
      dut.source.logic.flush #= false
      push(dut, FpUop(rob = 8, pFpSrcA = 12, psrcAFpValid = true, pFpccSrc = 6, readsFpcc = true))
      w = 0
      while (!fpIssues.contains(8) && w < 8) { cd.waitSampling(); w += 1 }
      assert(!fpIssues.contains(7), "flushed FP-dependent consumer must never issue")
      assert(fpIssues.count(_ == 8) == 1,
        "flush must clear cplxFpBusy AND cplxFpccBusy for a newly accepted consumer")
    }
  }
}
