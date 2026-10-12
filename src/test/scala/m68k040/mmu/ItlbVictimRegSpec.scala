package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{TranslationReq, TranslationRsp}
import m68k040.services.TranslationService
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** THE REGISTERED ITLB VICTIM BUFFER — its invalidation guarantees, each proven by
  * DELETING the mechanism and watching a real mistranslation appear.
  *
  * The 2026-09-29 retime moved the victim buffer off the fetch cone: a probe is
  * captured at N, CAMs from flops at N+1, resolves (promote or walk launch) at N+2, and
  * an eviction is inserted one cycle after it happens. Invalidation became
  * GENERATION-TAGGED (a flush bumps `curGen`; stale slots stop matching; a background
  * sweep frees them). Three mechanisms keep that architecturally immediate, and each
  * test below runs the SAME stimulus against the real RTL and against a DUT elaborated
  * with exactly one mechanism removed (`ItlbPlugin.VictimNegativeControl`) — the broken
  * arm must mistranslate, or the test proves nothing:
  *
  *   1. NoGeneration  — a PFLUSHA after a page-table edit must not let the pre-flush
  *                      translation be promoted back.
  *   2. NoProbeCancel — a TC.P write landing in the probe's CAM cycle must not let the
  *                      probe launch a walk keyed under the OLD page size (the
  *                      `ItlbCaptureRaceSpec` family, reopened two cycles later).
  *   3. NoWrapHazard  — four flushes inside one sweep pass bring a 2-bit generation
  *                      back round onto a slot the sweep has not yet freed.
  *
  * Every run also reports the mechanism counters (inserts, promotes, launches,
  * generation bumps, sweep clears, hazard cycles) so a pass can be told from a test
  * that never exercised the structure.
  */
class ItlbVictimRegSpec extends AnyFunSuite {
  import ItlbPlugin.VictimNegativeControl

  class ProbePlugin extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[TranslationService]
      val itlb  = host[ItlbPlugin]
      val mctrl = host[m68k040.services.MmuControlService]
      val reqIn  = in(TranslationReq())
      val rspOut = out(TranslationRsp())
      val tcWriteValid = in Bool ()
      val tcWriteP     = in Bool ()
      val pflusha      = in Bool ()
      val walkStart    = out Bool ()
      when(tcWriteValid) {
        mctrl.setPageSize.valid   := True
        mctrl.setPageSize.payload := tcWriteP
      }
      xlate.req.valid      := reqIn.valid
      xlate.req.vpn        := reqIn.vpn
      xlate.req.supervisor := reqIn.supervisor
      xlate.req.write      := reqIn.write
      rspOut.ready     := xlate.rsp.ready
      rspOut.ppn       := xlate.rsp.ppn
      rspOut.cacheMode := xlate.rsp.cacheMode
      rspOut.fault     := xlate.rsp.fault
      itlb.umCommitValid := False
      itlb.umCommitId    := U(0, m68k040.Global.ROB_ID_W_DEFAULT bits)
      itlb.umFlush       := False
      itlb.flushAll      := pflusha
      walkStart := itlb.logic.walker.io.start
    }
  }

  class Dut(neg: Int) extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val ctrl  = new MmuControlPlugin()
    val itlb  = new ItlbPlugin(victimEntries = 32, victimNegativeControl = neg)
    val probe = new ProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(itlb, "itlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, itlb, probe, walkPort)) }
  }

  private val ROOT = 0x10000L
  private val PTRT = 0x11000L
  private val PAGT = 0x12000L

  private def pokeWord(mem: DcacheClientMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  /** Mechanism counters, sampled every cycle. */
  final class Counters {
    var walks = 0; var promotes = 0; var launches = 0; var inserts = 0
    var bumps = 0; var sweepClears = 0; var hazardCycles = 0; var probes = 0
    def reset(): Unit = { walks = 0; promotes = 0; launches = 0; inserts = 0; bumps = 0
                          sweepClears = 0; hazardCycles = 0; probes = 0 }
    override def toString =
      s"probes=$probes promotes=$promotes launches=$launches walks=$walks inserts=$inserts " +
      s"genBumps=$bumps sweepClears=$sweepClears hazardCycles=$hazardCycles"
  }

  private def startCounters(dut: Dut, cd: ClockDomain): Counters = {
    val c = new Counters
    val v = dut.itlb.logic.vic
    fork {
      while (true) {
        cd.waitSampling()
        if (dut.probe.logic.walkStart.toBoolean) c.walks += 1
        if (v.promote.toBoolean)    c.promotes += 1
        if (v.launch.toBoolean)     c.launches += 1
        if (v.insertFire.toBoolean) c.inserts += 1
        if (v.genBump.toBoolean)    c.bumps += 1
        if (v.sweepClear.toBoolean) c.sweepClears += 1
        if (v.wrapHazard.toBoolean) c.hazardCycles += 1
        if (v.s1Valid.toBoolean)    c.probes += 1
      }
    }
    c
  }

  private def initDut(dut: Dut, cd: ClockDomain): Unit = {
    val p = dut.probe.logic
    p.reqIn.valid #= false; p.reqIn.vpn #= 0; p.reqIn.write #= false
    p.reqIn.supervisor #= true
    p.tcWriteValid #= false; p.tcWriteP #= false; p.pflusha #= false
    cd.waitSampling(4)
  }

  private def lookupUntilReady(dut: Dut, cd: ClockDomain, vpn: Long,
                               onCycle: () => Unit = () => ()): (Long, Boolean) = {
    val p = dut.probe.logic
    p.reqIn.valid #= true; p.reqIn.vpn #= vpn; p.reqIn.write #= false
    p.reqIn.supervisor #= true
    cd.waitSampling()
    var guard = 0
    while (!p.rspOut.ready.toBoolean && guard < 400) { onCycle(); cd.waitSampling(); guard += 1 }
    assert(guard < 400, f"lookup of vpn 0x$vpn%x never became ready")
    sleep(1)
    val r = (p.rspOut.ppn.toLong, p.rspOut.fault.toBoolean)
    p.reqIn.valid #= false
    cd.waitSampling(2)
    r
  }

  // ── 4 KB geometry: five VPNs in one 4-way row; the fifth evicts the first. ───────
  private def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  private def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  private def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt
  private val vpns = (0 until 5).map(i => 0x400L + i * 8)
  private val ppns = (0 until 5).map(i => 0x5000L + i)
  private val REMAPPED = 0x7777L
  private def writePte(mem: DcacheClientMemAgent, vpn: Long, ppn: Long): Unit = {
    val va = vpn << 12
    pokeWord(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWord(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    pokeWord(mem, PAGT + pageIdx(va) * 4, ((ppn << 12) & 0xfffff000L) | 0x1L)
  }

  /** Prime: five walks, VPN0 evicted into the victim buffer. Then REMAP VPN0 in memory
    * (so a stale answer is distinguishable from a fresh one) and run `flushes`. */
  private case class Out(ppn: Long, c: String, promotes: Int, walks: Int, inserts: Int,
                         bumps: Int, hazardCycles: Int, sweepClears: Int)

  private def remapScenario(neg: Int, flushes: Int, alignToSweep: Boolean): Out = {
    var out: Out = null
    SimConfig.withVerilator.compile(new Dut(neg)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      initDut(dut, cd)
      for (i <- vpns.indices) writePte(mem, vpns(i), ppns(i))
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      cd.waitSampling(2)
      val c = startCounters(dut, cd)
      for (i <- vpns.indices) {
        val (ppn, f) = lookupUntilReady(dut, cd, vpns(i))
        assert(!f && ppn == ppns(i), f"prime $i: got 0x$ppn%x")
      }
      assert(c.walks == 5 && c.inserts == 1,
        s"prime must walk 5 times and insert exactly one victim: $c")
      // VPN0 went to slot 0 (the ring starts at 0).
      assert(dut.itlb.logic.vic.valids(0).toBoolean, "VPN0 must be resident in victim slot 0")

      writePte(mem, vpns(0), REMAPPED)
      if (alignToSweep) {
        // Start the burst just AFTER the sweep has passed slot 0, so it cannot free
        // slot 0 for ~30 cycles: only the wrap guard stands between the stale slot and
        // a fresh-generation lookup.
        while (dut.itlb.logic.vic.sweepPtr.toInt != 2) cd.waitSampling()
      }
      c.reset()
      for (_ <- 0 until flushes) {
        dut.probe.logic.pflusha #= true
        cd.waitSampling()
      }
      dut.probe.logic.pflusha #= false
      val (rPpn, rF) = lookupUntilReady(dut, cd, vpns(0))
      assert(!rF, "the re-touch must not fault")
      out = Out(rPpn, c.toString, c.promotes, c.walks, c.inserts, c.bumps, c.hazardCycles, c.sweepClears)
    }
    out
  }

  test("registered victim: PFLUSHA after a remap -- the generation tag stops the stale promote " +
       "(and deleting it mistranslates)", VerilatorTest) {
    val good = remapScenario(VictimNegativeControl.None, flushes = 1, alignToSweep = false)
    info(s"real RTL:      ppn=0x${good.ppn.toHexString} ${good.c}")
    assert(good.ppn == REMAPPED, f"after PFLUSHA the re-touch must see the REMAPPED ppn, got 0x${good.ppn}%x")
    assert(good.promotes == 0 && good.walks == 1, s"must re-walk, not promote: ${good.c}")

    val broken = remapScenario(VictimNegativeControl.NoGeneration, flushes = 1, alignToSweep = false)
    info(s"NoGeneration:  ppn=0x${broken.ppn.toHexString} ${broken.c}")
    assert(broken.ppn == ppns(0) && broken.promotes == 1,
      s"NEGATIVE CONTROL FAILED TO FAIL: with the generation compare deleted the stale " +
      f"pre-PFLUSHA translation must be promoted back (ppn 0x${ppns(0)}%x), got 0x${broken.ppn}%x ${broken.c}")
  }

  test("registered victim: FOUR flushes inside one sweep pass wrap the 2-bit generation -- " +
       "the wrap hazard stops the stale hit (and deleting it mistranslates)", VerilatorTest) {
    val good = remapScenario(VictimNegativeControl.None, flushes = 4, alignToSweep = true)
    info(s"real RTL:      ppn=0x${good.ppn.toHexString} ${good.c}")
    assert(good.ppn == REMAPPED && good.promotes == 0,
      f"a generation wrap must not resurrect the stale slot, got 0x${good.ppn}%x ${good.c}")
    assert(good.hazardCycles > 0, s"the wrap hazard must actually have been raised: ${good.c}")

    val broken = remapScenario(VictimNegativeControl.NoWrapHazard, flushes = 4, alignToSweep = true)
    info(s"NoWrapHazard:  ppn=0x${broken.ppn.toHexString} ${broken.c}")
    assert(broken.ppn == ppns(0) && broken.promotes == 1,
      s"NEGATIVE CONTROL FAILED TO FAIL: without the hazard the 4th bump must alias the stale " +
      f"slot (ppn 0x${ppns(0)}%x), got 0x${broken.ppn}%x ${broken.c}")

    // Three flushes do NOT wrap: generation alone must suffice, with no hazard at all.
    val three = remapScenario(VictimNegativeControl.None, flushes = 3, alignToSweep = true)
    info(s"3 flushes:     ppn=0x${three.ppn.toHexString} ${three.c}")
    assert(three.ppn == REMAPPED && three.promotes == 0 && three.hazardCycles == 0,
      s"three flushes must be handled by the generation compare alone: ${three.c}")
  }

  // ── TC.P write in the CAM cycle of a MISSING probe ─────────────────────────────
  // Aliasing construction from `ItlbCaptureRaceSpec`: vpnLo filed under a 4 KB key is a
  // clean one-hot hit for the 8 KB lookup of vpnHi.
  private def tcRaceScenario(neg: Int): (Long, String) = {
    var out: (Long, String) = null
    SimConfig.withVerilator.compile(new Dut(neg)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      initDut(dut, cd)
      val PAGT0 = 0x12000L; val PAGT1 = 0x13000L
      def rIdx(v: Long) = ((v >> 13) & 0x7f).toInt
      def pIdx(v: Long) = ((v >> 6) & 0x7f).toInt
      def g4(v: Long) = (v & 0x3f).toInt
      def g8(v: Long) = ((v >> 1) & 0x1f).toInt
      def desc(ppn: Long) = ((ppn << 12) & 0xfffff000L) | 0x19L
      val vpnLo = 0x00402L; val vpnHi = 0x00804L
      val ppnLo = 0x12340L; val ppnHi = 0x5678AL
      pokeWord(mem, ROOT + rIdx(vpnLo) * 4, (PTRT & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PTRT + pIdx(vpnLo) * 4, (PAGT0 & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PTRT + pIdx(vpnHi) * 4, (PAGT1 & 0xfffffff0L) | 0x3L)
      pokeWord(mem, PAGT0 + g4(vpnLo) * 4, desc(ppnLo))
      pokeWord(mem, PAGT1 + g8(vpnHi) * 4, desc(ppnHi))
      pokeWord(mem, PAGT0 + g8(vpnLo) * 4, desc(ppnLo))
      dut.ctrl.logic.mmuEnable  #= true
      dut.ctrl.logic.pageSize8K #= false
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      cd.waitSampling(2)
      val c = startCounters(dut, cd)
      var fired = false
      val p = dut.probe.logic
      // Fire the TC.P write on the cycle the probe is in its CAM stage (s1Valid).
      lookupUntilReady(dut, cd, vpnLo, () => {
        if (!fired && dut.itlb.logic.vic.s1Valid.toBoolean) {
          p.tcWriteP #= true; p.tcWriteValid #= true; fired = true
        } else p.tcWriteValid #= false
      })
      p.tcWriteValid #= false
      assert(fired, "the TC.P write never met a probe in its CAM stage")
      cd.waitSampling(4)
      assert(dut.ctrl.logic.pageSize8K.toBoolean, "TCR.P must now be 8 KB")
      val (hiPpn, _) = lookupUntilReady(dut, cd, vpnHi)
      out = (hiPpn, c.toString)
    }
    out
  }

  test("registered victim: a TC.P write in the probe's CAM cycle cancels it -- " +
       "(and deleting the cancel launches an OLD-page-size walk that mistranslates)", VerilatorTest) {
    val (good, gc) = tcRaceScenario(VictimNegativeControl.None)
    info(f"real RTL:      vpnHi -> 0x$good%x  $gc")
    assert(good == 0x5678AL, f"vpnHi must translate to its own page 0x5678A, got 0x$good%x")
    val (bad, bc) = tcRaceScenario(VictimNegativeControl.NoProbeCancel)
    info(f"NoProbeCancel: vpnHi -> 0x$bad%x  $bc")
    assert(bad == 0x12340L,
      f"NEGATIVE CONTROL FAILED TO FAIL: without the cancel the probe must launch a 4K-keyed " +
      f"walk that aliases vpnHi onto vpnLo's page 0x12340, got 0x$bad%x")
  }

  // ── Back-to-back ring stress: requests change on the SAME cycle the previous one is
  //    answered, so re-touches land inside the insertion window and the probe pipeline.
  test("registered victim: back-to-back 30-page ring -- every access returns its OWN translation",
       VerilatorTest) {
    val N = 30
    val vs = (0 until N).map(i => 0x400L + (i % 8) * 8 + (i / 8) * 64)
    val ps = (0 until N).map(i => 0x6000L + i)
    // Interleave re-touches of the page evicted one access ago -- the tightest reuse
    // distance, which hits the staging window.
    val order = (0 until 3).flatMap(_ => (0 until N).flatMap(i => Seq(i, (i + N - 5) % N)))
    SimConfig.withVerilator.compile(new Dut(VictimNegativeControl.None)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd)
      initDut(dut, cd)
      for (i <- 0 until N) {
        val va = vs(i) << 12
        pokeWord(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
        val pagT = PAGT + (i / 8) * 0x100
        pokeWord(mem, PTRT + ptrIdx(va) * 4, (pagT & 0xfffffff0L) | 0x3L)
        pokeWord(mem, pagT + pageIdx(va) * 4, ((ps(i) << 12) & 0xfffff000L) | 0x1L)
      }
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      cd.waitSampling(2)
      val c = startCounters(dut, cd)
      val p = dut.probe.logic
      p.reqIn.valid #= true; p.reqIn.supervisor #= true
      for (k <- order) {
        p.reqIn.vpn #= vs(k)
        cd.waitSampling()
        var guard = 0
        while (!p.rspOut.ready.toBoolean && guard < 400) { cd.waitSampling(); guard += 1 }
        assert(guard < 400)
        sleep(1)
        assert(!p.rspOut.fault.toBoolean, s"page $k faulted")
        assert(p.rspOut.ppn.toLong == ps(k),
          f"page $k: got 0x${p.rspOut.ppn.toLong}%x want 0x${ps(k)}%x")
      }
      p.reqIn.valid #= false
      cd.waitSampling(4)
      info(s"back-to-back ${order.size} accesses: $c")
      assert(c.promotes > 32 && c.inserts > 32, s"the buffer must have been exercised: $c")
      assert(c.promotes + c.launches == c.probes, s"every probe resolves exactly once: $c")
    }
  }
}
