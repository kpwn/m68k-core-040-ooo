package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.cache.{FetchCmd, FetchRsp}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.services.FetchService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import scala.collection.mutable.ArrayBuffer

/** Directed proof for the depth-3 FetchAlign outstanding-record ring.
  *
  * A controlled FetchService is used instead of the real I-cache so the test can
  * hold three requests outstanding, place the oldest response on an exact chosen
  * cycle, and observe whether a fourth request fires on that same cycle. This is
  * intentionally not a latency-window test: it proves full occupancy, simultaneous
  * consume/replace, pointer/count turnover, ordered response data, and the
  * redirect+stale+leading-drop collision.
  */
class FetchAlignRingTurnoverSpec extends AnyFunSuite {

  class ControlledFetchPlugin extends FiberPlugin with FetchService {
    val logic = during build new Area {
      val cmdPort = Stream(FetchCmd())
      val rspPort = Flow(FetchRsp())

      // Top-level test IO: observe/accept FetchAlign's command and inject responses.
      val cmdOut = master(Stream(FetchCmd()))
      val rspIn  = slave(Flow(FetchRsp()))
      cmdOut << cmdPort
      rspPort << rspIn
    }

    override def cmd: Stream[FetchCmd] = logic.cmdPort
    override def rsp: Flow[FetchRsp]   = logic.rspPort
  }

  class Dut extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val fetch = new ControlledFetchPlugin
    val fa    = new FetchAlignPlugin
    val probe = new DecodeFeedProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), fetch, fa, probe)) }
  }

  private case class SeenPacket(pc: Long, opword: Int)

  private def idleInputs(dut: Dut): Unit = {
    dut.fetch.logic.cmdOut.ready #= false
    dut.fetch.logic.rspIn.valid #= false
    dut.fetch.logic.rspIn.payload.pc #= 0
    dut.fetch.logic.rspIn.payload.data #= 0
    dut.fetch.logic.rspIn.payload.fault #= false
    dut.fetch.logic.rspIn.payload.atc #= false
    for (p <- dut.fetch.logic.rspIn.payload.pred) {
      p.simple #= true
      p.lenWords #= 1
      p.ambiguousLine #= false
      p.size #= Size.LONG
      p.ctrlXfer #= false
    }
    dut.probe.logic.feedOut.ready #= true
    dut.fa.logic.redirect.valid #= false
    dut.fa.logic.redirect.payload #= 0
    dut.fa.logic.resume.valid #= false
    dut.fa.logic.resume.payload #= 0
  }

  /** Inject four one-word MOVEQ packets. `firstId` makes every word unique. */
  private def driveRsp(dut: Dut, pc: Long, firstId: Int): Unit = {
    val words = (0 until 4).map(i => 0x7000 | ((firstId + i) & 0xff))
    val data = words.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (word, lane)) =>
      acc | (BigInt(word & 0xffff) << (lane * 16))
    }
    dut.fetch.logic.rspIn.valid #= true
    dut.fetch.logic.rspIn.payload.pc #= pc
    dut.fetch.logic.rspIn.payload.data #= data
    dut.fetch.logic.rspIn.payload.fault #= false
    dut.fetch.logic.rspIn.payload.atc #= false
    for (p <- dut.fetch.logic.rspIn.payload.pred) {
      p.simple #= true
      p.lenWords #= 1
      p.ambiguousLine #= false
      p.size #= Size.LONG
      p.ctrlXfer #= false
    }
  }

  private def pulseRedirect(dut: Dut, cd: ClockDomain, pc: Long): Unit = {
    dut.fa.logic.redirect.valid #= true
    dut.fa.logic.redirect.payload #= pc
    cd.waitSampling()
    dut.fa.logic.redirect.valid #= false
  }

  private def await(cd: ClockDomain, maxCycles: Int, clue: String)(cond: => Boolean): Unit = {
    var cycles = 0
    while (!cond && cycles < maxCycles) {
      cd.waitSampling()
      cycles += 1
    }
    assert(cond, s"$clue (not reached within $maxCycles cycles)")
  }

  test("full depth-3 ring consumes and replaces in one cycle without stale/drop aliasing", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      idleInputs(dut)

      val cmdPcs = ArrayBuffer.empty[Long]
      val packets = ArrayBuffer.empty[SeenPacket]
      cd.onSamplings {
        if (dut.fetch.logic.cmdOut.valid.toBoolean && dut.fetch.logic.cmdOut.ready.toBoolean)
          cmdPcs += dut.fetch.logic.cmdOut.payload.pc.toLong
        if (dut.probe.logic.feedOut.valid.toBoolean && dut.probe.logic.feedOut.ready.toBoolean) {
          packets += SeenPacket(
            dut.probe.logic.feedOut.payload(0).pc.toLong,
            dut.probe.logic.feedOut.payload(0).words(0).toInt)
          if (dut.probe.logic.s1v.toBoolean) {
            packets += SeenPacket(
              dut.probe.logic.feedOut.payload(1).pc.toLong,
              dut.probe.logic.feedOut.payload(1).words(0).toInt)
          }
        }
      }

      cd.waitSampling(3)

      // Phase A: unaligned start gives the oldest record drop=2. Fill all three
      // records with no responses, then return the head and require request #4 in
      // the exact same cycle. At full occupancy head==tail, so the response must
      // still observe the OLD drop=2 record while the edge overwrites that slot.
      val aDecode = 0x1004L
      val aBase   = aDecode & ~7L
      dut.fetch.logic.cmdOut.ready #= true
      pulseRedirect(dut, cd, aDecode)
      await(cd, 10, "phase A ringCount must reach three") {
        dut.fa.logic.ringCount.toInt == 3
      }
      assert(cmdPcs.toSeq == Seq(aBase, aBase + 8, aBase + 16),
        s"phase A initial command order: ${cmdPcs.map(p => f"0x$p%x")}")
      assert(dut.fa.logic.ringHead.toInt == dut.fa.logic.ringTail.toInt,
        "a full non-power-of-two ring must have head==tail before turnover")
      assert(dut.fa.logic.ibuf.count.toInt == 0,
        "phase A setup must leave enough IBuf room for consume-and-replace")

      driveRsp(dut, aBase, firstId = 0)
      sleep(1) // settle rsp.valid -> ringSlotAvailable -> cmd.valid
      assert(dut.fa.logic.ringCount.toInt == 3, "turnover must start from a full ring")
      assert(dut.fetch.logic.cmdOut.valid.toBoolean && dut.fetch.logic.cmdOut.ready.toBoolean,
        "full ring must assert ic.cmd.fire when its head response arrives")
      assert(dut.fetch.logic.cmdOut.payload.pc.toLong == aBase + 24,
        f"replacement command PC must be 0x${aBase + 24}%x")
      val aHeadBefore = dut.fa.logic.ringHead.toInt
      val aTailBefore = dut.fa.logic.ringTail.toInt
      cd.waitSampling() // simultaneous response consume + replacement issue
      sleep(1)          // observe post-edge register values, not the sampling-region old values
      dut.fetch.logic.rspIn.valid #= false
      dut.fetch.logic.cmdOut.ready #= false // exactly four requests in phase A
      assert(dut.fa.logic.ringCount.toInt == 3,
        "simultaneous consume/replace must leave ringCount at three")
      assert(dut.fa.logic.ringHead.toInt != aHeadBefore && dut.fa.logic.ringTail.toInt != aTailBefore,
        "both ring pointers must advance on full-ring turnover")
      assert(cmdPcs.toSeq == Seq(aBase, aBase + 8, aBase + 16, aBase + 24),
        s"phase A replacement was not accepted exactly once: ${cmdPcs.map(p => f"0x$p%x")}")

      // Return the remaining requests in issue order. The feed must contain word
      // ids 2..15 exactly once: ids 0/1 were removed by the first record's drop=2,
      // proving that the full-ring overwrite did not alias new tail metadata into
      // the head response.
      for (i <- 1 until 4) {
        driveRsp(dut, aBase + i * 8L, firstId = i * 4)
        cd.waitSampling()
      }
      dut.fetch.logic.rspIn.valid #= false
      await(cd, 30, "phase A responses must drain in order") { packets.size == 14 }
      assert(dut.fa.logic.ringCount.toInt == 0, "phase A ring must fully drain")
      val expectedA = (2 until 16).map { id =>
        SeenPacket(aBase + id * 2L, 0x7000 | id)
      }
      assert(packets.toSeq == expectedA,
        s"phase A response/drop order mismatch\n got=${packets.mkString(",")}\n exp=${expectedA.mkString(",")}")

      // Phase B: fill the ring again, then collide the full-ring turnover with an
      // unaligned redirect. The replacement uses the old PC and must be born stale;
      // the redirect's drop=3 must survive for the later target request. None of the
      // four old-path windows may reach feed.
      val phaseBPacketStart = packets.size
      val phaseBCmdStart = cmdPcs.size
      val oldBase = 0x2000L
      val target  = 0x3006L
      val targetBase = target & ~7L
      pulseRedirect(dut, cd, oldBase)
      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 10, "phase B ringCount must reach three") {
        dut.fa.logic.ringCount.toInt == 3
      }
      assert(cmdPcs.drop(phaseBCmdStart).toSeq == Seq(oldBase, oldBase + 8, oldBase + 16),
        "phase B must begin with three ordered old-path requests")
      assert(dut.fa.logic.ibuf.count.toInt == 0,
        "phase B setup must leave enough IBuf room for redirect turnover")

      driveRsp(dut, oldBase, firstId = 0x40)
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= target
      sleep(1)
      assert(dut.fetch.logic.cmdOut.valid.toBoolean && dut.fetch.logic.cmdOut.ready.toBoolean,
        "redirect collision must not hide the full-ring consume-and-replace fire")
      assert(dut.fetch.logic.cmdOut.payload.pc.toLong == oldBase + 24,
        "the coincident replacement used the pre-redirect PC and must be marked stale")
      cd.waitSampling()
      sleep(1)
      dut.fa.logic.redirect.valid #= false
      dut.fetch.logic.rspIn.valid #= false
      dut.fetch.logic.cmdOut.ready #= false
      assert(dut.fa.logic.ringCount.toInt == 3,
        "redirect turnover must still replace exactly one record")
      assert(cmdPcs.drop(phaseBCmdStart).toSeq == Seq(oldBase, oldBase + 8, oldBase + 16, oldBase + 24),
        "phase B replacement must be accepted once before the redirect target")

      // Drain the two older sequential requests plus the same-cycle replacement.
      // All three records were marked stale by the redirect.
      for (i <- 1 until 4) {
        driveRsp(dut, oldBase + i * 8L, firstId = 0x40 + i * 4)
        cd.waitSampling()
      }
      dut.fetch.logic.rspIn.valid #= false
      cd.waitSampling(2)
      assert(dut.fa.logic.ringCount.toInt == 0, "all redirected old-path records must drain")
      assert(packets.size == phaseBPacketStart,
        s"redirected old-path response leaked into feed: ${packets.drop(phaseBPacketStart)}")

      // Only now accept the redirect target. pendingDrop=3 must be attached to this
      // request, not to the stale replacement that fired with the redirect.
      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 10, "redirect target request must issue after stale drain") {
        cmdPcs.size == phaseBCmdStart + 5
      }
      assert(cmdPcs.last == targetBase,
        f"redirect target command must use aligned base 0x$targetBase%x, got 0x${cmdPcs.last}%x")
      dut.fetch.logic.cmdOut.ready #= false
      driveRsp(dut, targetBase, firstId = 0x80)
      cd.waitSampling()
      dut.fetch.logic.rspIn.valid #= false
      await(cd, 20, "drop=3 target word must reach feed") {
        packets.size == phaseBPacketStart + 1
      }
      assert(packets.drop(phaseBPacketStart).toSeq == Seq(
        SeenPacket(target, 0x7000 | 0x83)),
        s"redirect target leading-drop association failed: ${packets.drop(phaseBPacketStart)}")
      assert(dut.fa.logic.ringCount.toInt == 0, "target response must retire its record")

      // Phase C: fault responses are deliberately excluded from the full-ring
      // credit. `faultHold` is still false before this edge, so using bare
      // rsp.valid as capacity would incorrectly accept one extra younger request.
      val faultBase = 0x4000L
      val phaseCCmdStart = cmdPcs.size
      pulseRedirect(dut, cd, faultBase)
      dut.fetch.logic.cmdOut.ready #= true
      await(cd, 10, "phase C ringCount must reach three") {
        dut.fa.logic.ringCount.toInt == 3
      }
      assert(cmdPcs.drop(phaseCCmdStart).toSeq == Seq(faultBase, faultBase + 8, faultBase + 16),
        "phase C must begin with exactly three requests")
      driveRsp(dut, faultBase, firstId = 0xc0)
      dut.fetch.logic.rspIn.payload.fault #= true
      dut.fetch.logic.rspIn.payload.atc #= true
      sleep(1)
      assert(!dut.fetch.logic.cmdOut.valid.toBoolean,
        "a full ring must not consume-and-replace on the cycle faultHold first latches")
      cd.waitSampling()
      sleep(1)
      dut.fetch.logic.rspIn.valid #= false
      dut.fetch.logic.rspIn.payload.fault #= false
      assert(cmdPcs.size == phaseCCmdStart + 3,
        "faulting full-ring response must not accept a younger replacement")
      assert(dut.fa.logic.ringCount.toInt == 2,
        "fault response must free the head record without replacement")
      sleep(1)
      assert(!dut.fetch.logic.cmdOut.valid.toBoolean,
        "latched faultHold must suppress later issue even after the ring is no longer full")
    }
  }
}
