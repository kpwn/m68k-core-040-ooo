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

/** Directed coverage for CPLX integer dependencies in the IQ.
  *
  * CPLX results are variable-latency and use a completion wakeup rather than the
  * latency-one trigger matrix. In particular, a consumer may read two independently
  * completing CPLX results, so the first wake must not release it.
  */
class IqCplxSpec extends AnyFunSuite {
  /** Test-only CPLX wakeup source. Keep it separate from the common IQ fixture so this
    * spec cannot accidentally perturb the defaults of unrelated IQ tests. */
  class CplxWakeSourcePlugin extends FiberPlugin {
    val logic = during build new Area {
      val iq = host[IssueQueueService]
      val valid = in Bool ()
      val pdst  = in UInt (6 bits)
      iq.cplxWakeup.valid   := valid
      iq.cplxWakeup.payload := pdst
    }
  }

  /** The common sink consumes CPLX issue port 4; this observer makes its handshakes
    * visible so the test proves both producers actually entered flight. */
  class CplxIssueObserverPlugin extends FiberPlugin {
    val logic = during build new Area {
      val iq = host[IssueQueueService]
      val fire = out Bool ()
      val rob  = out UInt (6 bits)
      fire := iq.issue(4).fire
      rob  := iq.issue(4).payload.robId
    }
  }

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val iq       = new IssueQueuePlugin
    val source   = new IqSourcePlugin
    val sink     = new IqSinkPlugin
    val cplxWake = new CplxWakeSourcePlugin
    val cplxObs  = new CplxIssueObserverPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), iq, source, sink, cplxWake, cplxObs
    )) }
  }

  case class Uop(
      rob: Int,
      cluster: SpinalEnumElement[Cluster.type] = Cluster.INT,
      pdst: Int = 0,
      pdstValid: Boolean = false,
      psrcA: Int = 0,
      psrcAValid: Boolean = false,
      psrcB: Int = 0,
      psrcBValid: Boolean = false
  )

  private def driveSlot(dut: Dut, slot1: Boolean, uop: Uop): Unit = {
    val slot = if (slot1) dut.source.logic.s1 else dut.source.logic.s0
    slot.robId #= uop.rob
    slot.cluster #= uop.cluster
    slot.memOp #= MemOp.NONE
    slot.pdst #= uop.pdst
    slot.pdstValid #= uop.pdstValid
    slot.psrcA #= uop.psrcA
    slot.psrcAValid #= uop.psrcAValid
    slot.psrcB #= uop.psrcB
    slot.psrcBValid #= uop.psrcBValid
    slot.useImm #= false
    slot.readsNzvc #= false
    slot.writesNzvc #= false
    slot.pNzvcSrc #= 0
    slot.pNzvcDst #= 0
    slot.readsX #= false
    slot.writesX #= false
    slot.pXSrc #= 0
    slot.pXDst #= 0
    slot.isShift #= false
  }

  private def idle(dut: Dut): Unit = {
    val source = dut.source.logic
    source.pushValid #= false
    source.slot1Valid #= false
    source.flush #= false
    source.aluFastAccept0 #= true
    source.aluFastAccept1 #= true
    source.lsWakeupValid #= false
    source.lsWakeupPdst #= 0
    source.aluSlowWakeupValid #= false
    source.aluSlowWakeupPdst #= 0
    source.aluSlowWakeupPdstV #= false
    source.aluSlowWakeupNzvc #= 0
    source.aluSlowWakeupNzvcV #= false
    source.aluSlowWakeupX #= 0
    source.aluSlowWakeupXV #= false
    driveSlot(dut, slot1 = false, Uop(0))
    driveSlot(dut, slot1 = true, Uop(0))
    dut.sink.logic.ready0 #= true
    dut.sink.logic.ready1 #= true
    dut.sink.logic.ready3 #= true
    dut.cplxWake.logic.valid #= false
    dut.cplxWake.logic.pdst #= 0
  }

  /** Hold a complete push transaction stable until the IQ accepts it. */
  private def push(dut: Dut, uop0: Uop, uop1: Option[Uop] = None): Unit = {
    val source = dut.source.logic
    driveSlot(dut, slot1 = false, uop0)
    driveSlot(dut, slot1 = true, uop1.getOrElse(Uop(0)))
    source.slot1Valid #= uop1.nonEmpty
    source.pushValid #= true
    dut.clockDomain.waitSamplingWhere(source.pushReady.toBoolean)
    source.pushValid #= false
    source.slot1Valid #= false
  }

  /** A wakeup is a Flow, so one sampled cycle is the complete transfer. */
  private def wake(dut: Dut, pdst: Int): Unit = {
    dut.cplxWake.logic.pdst #= pdst
    dut.cplxWake.logic.valid #= true
    dut.clockDomain.waitSampling()
    dut.cplxWake.logic.valid #= false
  }

  test("CPLX consumers wait for every source and handle concurrent push-wake plus flush", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      idle(dut)

      val intIssues  = ArrayBuffer[Int]()
      val cplxIssues = ArrayBuffer[Int]()
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.sink.logic.v0.toBoolean) intIssues += dut.sink.logic.rob0.toInt
          if (dut.sink.logic.v1.toBoolean) intIssues += dut.sink.logic.rob1.toInt
          if (dut.cplxObs.logic.fire.toBoolean) cplxIssues += dut.cplxObs.logic.rob.toInt
        }
      }
      cd.waitSampling(4)

      // Two independently completing CPLX producers are accepted in one transaction.
      push(dut,
        Uop(rob = 1, cluster = Cluster.CPLX, pdst = 9, pdstValid = true),
        Some(Uop(rob = 2, cluster = Cluster.CPLX, pdst = 10, pdstValid = true)))
      push(dut, Uop(
        rob = 3, pdst = 20, pdstValid = true,
        psrcA = 9, psrcAValid = true,
        psrcB = 10, psrcBValid = true))

      var issueWait = 0
      while ((!cplxIssues.contains(1) || !cplxIssues.contains(2)) && issueWait < 12) {
        cd.waitSampling()
        issueWait += 1
      }
      assert(cplxIssues.count(_ == 1) == 1 && cplxIssues.count(_ == 2) == 1,
        s"both CPLX producers must issue exactly once, saw ${cplxIssues.mkString(",")}")
      assert(!intIssues.contains(3), "two-source consumer issued before any CPLX wakeup")

      wake(dut, pdst = 9)
      cd.waitSampling(4)
      assert(!intIssues.contains(3),
        "two-source consumer must remain blocked after only its first CPLX source wakes")

      wake(dut, pdst = 10)
      var releaseWait = 0
      while (!intIssues.contains(3) && releaseWait < 8) {
        cd.waitSampling()
        releaseWait += 1
      }
      assert(intIssues.count(_ == 3) == 1,
        s"consumer must issue exactly once after its second CPLX wake, saw ${intIssues.mkString(",")}")

      // A wake concurrent with an accepted consumer push must not leave cplxWait set
      // from the pre-edge busy bitmap. Wait for push.ready first, then hold the entire
      // transaction stable over the accepting edge while issuing the Flow wakeup.
      push(dut, Uop(rob = 4, cluster = Cluster.CPLX, pdst = 11, pdstValid = true))
      while (!cplxIssues.contains(4)) cd.waitSampling()
      driveSlot(dut, slot1 = false,
        Uop(rob = 5, pdst = 21, pdstValid = true, psrcA = 11, psrcAValid = true))
      driveSlot(dut, slot1 = true, Uop(0))
      dut.source.logic.slot1Valid #= false
      dut.source.logic.pushValid #= true
      dut.cplxWake.logic.pdst #= 11
      dut.cplxWake.logic.valid #= true
      cd.waitSamplingWhere(dut.source.logic.pushReady.toBoolean)
      dut.source.logic.pushValid #= false
      dut.cplxWake.logic.valid #= false
      var sameCycleWait = 0
      while (!intIssues.contains(5) && sameCycleWait < 8) {
        cd.waitSampling()
        sameCycleWait += 1
      }
      assert(intIssues.count(_ == 5) == 1,
        "consumer accepted with a same-cycle matching wakeup must issue exactly once")

      // Flush must remove a waiting consumer and clear the CPLX busy bitmap. A new
      // consumer of the old destination then issues without receiving a stale wakeup.
      push(dut, Uop(rob = 6, cluster = Cluster.CPLX, pdst = 12, pdstValid = true))
      while (!cplxIssues.contains(6)) cd.waitSampling()
      push(dut, Uop(rob = 7, pdst = 22, pdstValid = true, psrcA = 12, psrcAValid = true))
      cd.waitSampling(3)
      assert(!intIssues.contains(7), "pre-flush consumer escaped before its CPLX wakeup")
      dut.source.logic.flush #= true
      cd.waitSampling()
      dut.source.logic.flush #= false
      push(dut, Uop(rob = 8, pdst = 23, pdstValid = true, psrcA = 12, psrcAValid = true))
      var flushWait = 0
      while (!intIssues.contains(8) && flushWait < 8) {
        cd.waitSampling()
        flushWait += 1
      }
      assert(!intIssues.contains(7), "flushed CPLX-dependent consumer must never issue")
      assert(intIssues.count(_ == 8) == 1,
        "flush must clear CPLX busy state for a newly accepted consumer")
    }
  }
}
