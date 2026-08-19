package m68k040.frontend

import m68k040.{M68kParams, VerilatorTest}
import m68k040.cache.{FetchCmd, FetchRsp}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.services.{BtbUpdate, BtbUpdateService, FetchService,
  FrontendQuiesceService, GshareUpdateService}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import java.io.{PrintWriter, StringWriter}
import scala.collection.mutable

/** Binding FTQ capacity proof for the fetch-directed FTB token pipeline.
  *
  * The owning amendment
  * `docs/superpowers/specs/2026-08-09-ipc-fetch-directed-btb-token-pipeline-amendment.md`
  * section 5 states that the legal maximum frontend run-ahead is `RING` outstanding
  * fetch windows plus at most `BUF_WORDS` one-word windows resident in the IBuf, that
  * the 32-entry depth therefore makes `ftqFull` unreachable, and that `ftqFull` must not
  * feed `ftbBlocked`, `applyNow`, the selected fetch PC, I-cache readiness, or
  * prefetch/install state.  Capacity correctness terminates at the elaboration bound and
  * a simulation assertion instead of at a live combinational veto.
  *
  * This spec is the measurement half of that claim.  It builds a chain of eight-byte
  * windows in which every window's first word is a learned unconditional one-word branch
  * to the next window, so every accepted fetch command produces exactly one applied
  * prediction, exactly one FTQ push, and exactly one word landing in the IBuf.  That is
  * the densest legal FTQ producer the frontend can construct: no legal stimulus can push
  * an entry without also consuming a genuine IBuf word or a ring slot.
  *
  * Decode is held for the whole run-ahead phase, so nothing pops.  Every FTQ push, pop,
  * and flush is counted as a real event and mirrored against the hardware `ftqCount`
  * register, so a design which silently declines applications, or a stimulus which never
  * actually reached the frontend, cannot pass.
  */
class FtqCapacitySpec extends AnyFunSuite {

  class ControlledFetchPlugin extends FiberPlugin with FetchService {
    val logic = during build new Area {
      val cmdPort = Stream(FetchCmd())
      val rspPort = Flow(FetchRsp())
      val cmdOut = master(Stream(FetchCmd()))
      val rspIn = slave(Flow(FetchRsp()))
      cmdOut << cmdPort
      rspPort << rspIn
    }
    override def cmd: Stream[FetchCmd] = logic.cmdPort
    override def rsp: Flow[FetchRsp] = logic.rspPort
  }

  class UpdateDriver extends FiberPlugin with BtbUpdateService {
    val logic = during build new Area {
      val update = Flow(BtbUpdate())
      in(update.valid)
      update.payload.flatten.foreach(in(_))
    }
    override def btbUpdate: Flow[BtbUpdate] = logic.update
  }

  class GshareUpdateDriver extends FiberPlugin {
    val logic = during build new Area {
      val valid = in Bool()
      val index = in UInt(11 bits)
      val taken = in Bool()
      val update = host[GshareUpdateService].gshareUpdate
      update.valid := valid
      update.payload.index := index
      update.payload.taken := taken
    }
  }

  class QuiesceDriver extends FiberPlugin with FrontendQuiesceService {
    val logic = during build new Area {
      val nextIn = in Bool()
      val activeReg = RegNext(nextIn) init False
      activeReg.simPublic()
    }
    override def active: Bool = logic.activeReg
    override def next: Bool = logic.nextIn
  }

  class InternalRedirectDriver extends FiberPlugin {
    val logic = during build new Area {
      val valid = in Bool()
      val payload = in UInt(32 bits)
      val redirect = host[FetchAlignPlugin].logic.mispredictRedirect
      redirect.valid := valid
      redirect.payload := payload
    }
  }

  class DecodeBtbWire extends FiberPlugin {
    val logic = during build new Area {
      val fa = host[FetchAlignPlugin]
      val btb = host[BtbPlugin]
      btb.logic.queryPc := fa.logic.btbQueryPc0
      btb.logic.queryValid := fa.logic.btbQueryValid0
      btb.logic.invalidateAll := False
      fa.logic.btbPredTaken0 := btb.logic.predTakenComb
      fa.logic.btbPredTarget0 := btb.logic.predTargetComb
    }
  }

  class Dut(ftqDepth: Int) extends Component {
    val db = new Database
    val host = db on new PluginHost
    val fetch = new ControlledFetchPlugin
    val update = new UpdateDriver
    val btb = new BtbPlugin
    val ftb = new FtbPlugin(entries = 128)
    val gshare = new GsharePlugin
    val gshareUpdate = new GshareUpdateDriver
    val quiesce = new QuiesceDriver
    val fa = new FetchAlignPlugin(enableFetchDirected = true, ftqDepth = ftqDepth)
    val internalRedirect = new InternalRedirectDriver
    val btbWire = new DecodeBtbWire
    val probe = new DecodeFeedProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), fetch, update, btb, ftb, gshare, gshareUpdate, quiesce,
      fa, internalRedirect, btbWire, probe)) }
  }

  /** Chain base.  Window `k` is `BASE + 8*k`; its first word is a one-word taken branch
    * to window `k+1`.  128 direct-mapped FTB entries index on `pc(9 downto 3)`, so all
    * `WINDOWS` chain members occupy distinct entries with an identical tag. */
  private val BASE = 0x1000L
  private val WINDOWS = 40
  private val BRA_S = 0x601e // BRA.S — one word, `simple`
  private val POISON = Seq(0x72ee, 0x74ee, 0x76ee)

  private def idle(dut: Dut): Unit = {
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
    }
    dut.update.logic.update.valid #= false
    dut.update.logic.update.payload.pc #= 0
    dut.update.logic.update.payload.taken #= false
    dut.update.logic.update.payload.target #= 0
    dut.update.logic.update.payload.brType #= 0
    dut.update.logic.update.payload.len #= 0
    dut.gshareUpdate.logic.valid #= false
    dut.gshareUpdate.logic.index #= 0
    dut.gshareUpdate.logic.taken #= false
    dut.quiesce.logic.nextIn #= false
    dut.fa.logic.redirect.valid #= false
    dut.fa.logic.redirect.payload #= 0
    dut.fa.logic.resume.valid #= false
    dut.fa.logic.resume.payload #= 0
    dut.internalRedirect.logic.valid #= false
    dut.internalRedirect.logic.payload #= 0
    dut.probe.logic.feedOut.ready #= false
  }

  /** Install the full window chain.  `brType = 1` (unconditional) allocates a saturated
    * counter, so the applied direction never depends on gshare training. */
  private def trainChain(dut: Dut, cd: ClockDomain): Unit = {
    val u = dut.update.logic.update
    for (k <- 0 until WINDOWS) {
      u.valid #= true
      u.payload.pc #= BASE + 8 * k
      u.payload.taken #= true
      u.payload.target #= BASE + 8 * (k + 1)
      u.payload.brType #= 1
      u.payload.len #= 1
      cd.waitSampling()
    }
    u.valid #= false
    cd.waitSampling(2)
  }

  /** Live capacity accounting.  `model` is an independent mirror of the hardware FTQ
    * occupancy rebuilt purely from counted push/pop/flush events; any divergence from the
    * `ftqCount` register fails immediately, so neither side can be vacuous. */
  private class Census {
    var cycles = 0
    var pushes = 0
    var pops = 0
    var flushes = 0
    var flushesWithEntries = 0
    var fullPulses = 0
    var peak = 0
    var peakRing = 0
    var peakIbuf = 0
    var applies = 0
    var targetHolds = 0
    var mismatches = 0
    var blockedApplies = 0
    var emptyCycles = 0
    var model = 0
    var divergence: Option[String] = None
  }

  private def census(dut: Dut, cd: ClockDomain): Census = {
    val c = new Census
    cd.onSamplings {
      val fa = dut.fa.logic
      val count = fa.ftqCount.toInt
      if (c.divergence.isEmpty && count != c.model)
        c.divergence = Some(s"cycle ${c.cycles}: ftqCount=$count but counted model=${c.model}")
      if (fa.ftqFull.toBoolean) c.fullPulses += 1
      if (count == 0) c.emptyCycles += 1
      if (count > c.peak) c.peak = count
      val ring = fa.ringCount.toInt
      if (ring > c.peakRing) c.peakRing = ring
      val ibufCnt = fa.ibuf.io.cnt.toInt
      if (ibufCnt > c.peakIbuf) c.peakIbuf = ibufCnt
      if (fa.targetHoldValid.toBoolean) c.targetHolds += 1
      if (fa.ftqMismatch.toBoolean) c.mismatches += 1
      if (fa.ftbDeclineBlocked.toBoolean) c.blockedApplies += 1
      val push = fa.ftqPush.toBoolean
      val pop = fa.ftqPop.toBoolean
      val flush = fa.ftqFlush.toBoolean
      if (fa.applyNow.toBoolean) c.applies += 1
      if (push) c.pushes += 1
      if (pop) c.pops += 1
      if (flush) {
        c.flushes += 1
        if (count > 0) c.flushesWithEntries += 1
      }
      // Mirror the register's own priority: push/pop net first, flush last.
      c.model =
        if (flush) 0
        else count + (if (push) 1 else 0) - (if (pop) 1 else 0)
      c.cycles += 1
    }
    c
  }

  /** Fixed three-cycle window service.  The registered FTB result for a command issued in
    * C lands in C+1, so a response may not be presented before C+2; the RTL asserts that
    * application precedes its own cache response. */
  private def serveWindows(dut: Dut, cd: ClockDomain): mutable.Queue[(Int, Long)] = {
    val inflight = mutable.Queue.empty[(Int, Long)]
    var cycle = 0
    val words = Seq(BRA_S) ++ POISON
    val data = words.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (w, lane)) =>
      acc | (BigInt(w & 0xffff) << (lane * 16))
    }
    cd.onSamplings {
      if (dut.fetch.logic.cmdOut.valid.toBoolean && dut.fetch.logic.cmdOut.ready.toBoolean)
        inflight.enqueue(cycle -> dut.fetch.logic.cmdOut.payload.pc.toLong)
      val r = dut.fetch.logic.rspIn
      if (inflight.nonEmpty && cycle - inflight.head._1 >= 2) {
        val (_, pc) = inflight.dequeue()
        r.valid #= true
        r.payload.pc #= pc
        r.payload.data #= data
        r.payload.fault #= false
        r.payload.atc #= false
        for (p <- r.payload.pred) {
          p.simple #= true
          p.lenWords #= 1
          p.ambiguousLine #= false
          p.size #= Size.LONG
        }
      } else {
        r.valid #= false
      }
      cycle += 1
    }
    inflight
  }

  private def redirectTo(dut: Dut, cd: ClockDomain, pc: Long): Unit = {
    dut.fa.logic.redirect.valid #= true
    dut.fa.logic.redirect.payload #= pc
    cd.waitSampling()
    dut.fa.logic.redirect.valid #= false
  }

  test("maximum legal frontend run-ahead never reaches the FTQ capacity bound", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut(32)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      idle(dut)
      cd.waitSampling(3)
      trainChain(dut, cd)

      val c = census(dut, cd)
      serveWindows(dut, cd)

      // Phase 1 — cache-command backpressure, ring turnover, and target holds.
      // Decode is held throughout; the command port is withdrawn periodically so a
      // registered result must apply into the one-entry target hold.
      dut.fetch.logic.cmdOut.ready #= true
      redirectTo(dut, cd, BASE)
      for (i <- 0 until 80) {
        dut.fetch.logic.cmdOut.ready #= (i % 7) < 4
        cd.waitSampling()
      }

      // Phase 2 — unrestricted run-ahead.  Free-run until the frontend stops issuing,
      // which is the real structural limit (IBuf landing reservation plus ring depth).
      dut.fetch.logic.cmdOut.ready #= true
      var quietCycles = 0
      var guard = 0
      while (quietCycles < 20 && guard < 400) {
        val fired = dut.fetch.logic.cmdOut.valid.toBoolean &&
          dut.fetch.logic.cmdOut.ready.toBoolean
        cd.waitSampling()
        quietCycles = if (fired) 0 else quietCycles + 1
        guard += 1
      }
      val settledPeak = c.peak
      val settledCount = dut.fa.logic.ftqCount.toInt
      val settledIbuf = dut.fa.logic.ibuf.io.cnt.toInt

      println(s"[FTQ run-ahead] settledCount=$settledCount settledIbuf=$settledIbuf " +
        s"peak=${c.peak} peakRing=${c.peakRing} peakIbuf=${c.peakIbuf} " +
        s"pushes=${c.pushes} targetHoldCycles=${c.targetHolds} " +
        s"blockedApplies=${c.blockedApplies} fullPulses=${c.fullPulses}")
      assert(c.divergence.isEmpty, c.divergence.getOrElse(""))
      assert(settledCount >= 12,
        s"run-ahead phase did not build a deep FTQ: settled occupancy $settledCount")
      assert(c.peakIbuf >= 16,
        s"IBuf never approached its landing bound: peak ${c.peakIbuf} words, " +
          s"settled $settledIbuf")
      assert(c.peakRing == 3, s"ring never reached full occupancy: peak ${c.peakRing}")
      assert(c.targetHolds > 0, "backpressure never exercised the one-entry target hold")
      assert(c.mismatches == 0,
        s"legal maximum run-ahead produced ${c.mismatches} framing mismatches")

      // Phase 3 — redirect at maximum occupancy.  A real flush must discard live entries.
      val beforeFlush = dut.fa.logic.ftqCount.toInt
      assert(beforeFlush > 0, "redirect phase started with an already-empty FTQ")
      redirectTo(dut, cd, BASE)
      // The redirect cycle's own cache command is born stale, so it launches no lookup and
      // the following cycle cannot push. Observe the flushed state one edge later.
      cd.waitSampling()
      assert(dut.fa.logic.ftqCount.toInt == 0,
        s"redirect left ${dut.fa.logic.ftqCount.toInt} FTQ entries")
      assert(c.flushesWithEntries > 0, "no flush ever discarded a live FTQ entry")

      // Phase 4 — rebuild run-ahead after the flush (prediction turnover across the
      // whole trained chain rather than a single re-applied entry).
      val pushesBeforeRefill = c.pushes
      guard = 0
      quietCycles = 0
      while (quietCycles < 20 && guard < 400) {
        val fired = dut.fetch.logic.cmdOut.valid.toBoolean &&
          dut.fetch.logic.cmdOut.ready.toBoolean
        cd.waitSampling()
        quietCycles = if (fired) 0 else quietCycles + 1
        guard += 1
      }
      assert(c.pushes - pushesBeforeRefill >= 12,
        s"refill after redirect only pushed ${c.pushes - pushesBeforeRefill} entries")

      // Phase 5 — release decode.  Confirmations must pop real entries, proving the
      // occupancy measured above was genuine held state and not a stuck counter.
      val popsBeforeDrain = c.pops
      val emptyBeforeDrain = c.emptyCycles
      dut.probe.logic.feedOut.ready #= true
      cd.waitSampling(300)
      dut.probe.logic.feedOut.ready #= false
      cd.waitSampling(2)

      assert(c.divergence.isEmpty, c.divergence.getOrElse(""))
      assert(c.pops - popsBeforeDrain >= 10,
        s"drain confirmed only ${c.pops - popsBeforeDrain} FTQ entries")
      assert(c.emptyCycles - emptyBeforeDrain > 0,
        "the FTQ was never observed empty while decode consumed it")

      // The binding capacity claim.
      assert(c.fullPulses == 0,
        s"ftqFull asserted on ${c.fullPulses} cycles under legal run-ahead")
      assert(c.peak < 32,
        s"peak FTQ occupancy ${c.peak} reached the configured depth")
      assert(c.pushes >= 30 && c.applies == c.pushes,
        s"weak or inconsistent application census: applies=${c.applies} pushes=${c.pushes}")

      // Non-vacuity of the depth choice itself: the measured peak must exceed the next
      // lower power-of-two depth, so 32 is load-bearing rather than arbitrary.
      assert(settledPeak > 16,
        s"peak occupancy $settledPeak did not exceed the next lower power-of-two depth; " +
          "a 16-entry FTQ would have sufficed and this stress is too weak to justify 32")

      println(s"[FTQ capacity] cycles=${c.cycles} peak=${c.peak} peakRing=${c.peakRing} " +
        s"peakIbuf=${c.peakIbuf} pushes=${c.pushes} pops=${c.pops} flushes=${c.flushes} " +
        s"flushesWithEntries=${c.flushesWithEntries} targetHoldCycles=${c.targetHolds} " +
        s"blockedApplies=${c.blockedApplies} fullPulses=${c.fullPulses}")
    }
  }

  test("an undersized FTQ depth is rejected at elaboration") {
    val dir = "simWorkspace/ftqCapacity"
    // Control: the shipped depth elaborates.
    SpinalConfig(targetDirectory = dir).generateVerilog(new Dut(32))
    val err = intercept[Throwable] {
      SpinalConfig(targetDirectory = dir).generateVerilog(new Dut(16))
    }
    val sw = new StringWriter
    err.printStackTrace(new PrintWriter(sw))
    val text = sw.toString + "\n" + String.valueOf(err.getMessage)
    assert(text.contains("below the legal frontend run-ahead bound"),
      s"depth 16 failed for an unrelated reason:\n$text")
  }
}
