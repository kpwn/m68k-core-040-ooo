package m68k040.mmu

import m68k040.M68kParams
import m68k040.core.ParamPlugin
import m68k040.cache.DTranslationToken
import m68k040.services.DTranslationService
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

/** TLB-miss cost chain, tiers (b)/(c) (Fable review §2.3/§4.3): what the DTLB does with a
  * table walk whose OWNING access has been squashed.
  *
  * The real core's `umFlush` is `doFlush`, which empties the whole in-order LS pipe
  * (P2T included), and `umAccessPreCommitted` is `excActive`, during which the LS pipe
  * is held empty -- so in both cases nobody is waiting for the walk's answer. Today the
  * DTLB still holds `missPending` (and `_req.ready` low) until that walk finishes, so
  * the correct path's first translation waits for it.
  *
  * Each scenario runs against the four flag combinations, constructed EXPLICITLY (the
  * env flags only set the constructor defaults), so test-fast covers the ON arms in
  * every environment:
  *   * `hitUnderWalk` (`CPU_DTLB_HIT_UNDER_WALK`): the dead walk detaches; a resident
  *     page is answered within a couple of cycles of the flush.
  *   * `abortOnFlush` (`CPU_WALK_ABORT_ON_FLUSH`): the walker stops at the next level
  *     boundary, so it issues no further descriptor reads.
  * Invariants checked in every arm: the squashed walk's response is never delivered
  * (flags ON), the squashed page is NOT installed (it re-walks), a miss captured behind
  * a dead walk resolves to the right PPN, and a pre-committed (exception-sequencer)
  * walk is never squashed. */
class DtlbSquashedWalkSpec extends AnyFunSuite {

  class ProbePlugin extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[DTranslationService]
      val dtlb  = host[DtlbPlugin]
      val reqValid = in Bool ()
      val reqVpn   = in UInt (20 bits)
      val reqToken = in UInt (DTranslationToken.Width bits)
      val reqReady = out Bool ()
      val flush    = in Bool ()
      val preCommitted = in Bool ()
      xlate.req.valid              := reqValid
      xlate.req.payload.vpn        := reqVpn
      xlate.req.payload.supervisor := True
      xlate.req.payload.write      := False
      xlate.req.payload.token      := reqToken
      reqReady := xlate.req.ready
      xlate.rsp.ready := True
      val rspValid = out Bool (); rspValid := xlate.rsp.valid
      val rspToken = out UInt (DTranslationToken.Width bits); rspToken := xlate.rsp.payload.token
      val rspPpn   = out UInt (20 bits); rspPpn := xlate.rsp.payload.ppn
      val rspFault = out Bool (); rspFault := xlate.rsp.payload.fault
      dtlb.umFlush := flush
      dtlb.umAccessPreCommitted := preCommitted
      val walkerBusy = out Bool (); walkerBusy := dtlb.logic.walker.io.busy
    }
  }

  class Dut(hitUnderWalk: Boolean, abortOnFlush: Boolean) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin(hitUnderWalk = hitUnderWalk, abortOnFlush = abortOnFlush)
    val probe = new ProbePlugin()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
  }

  private val ROOT = 0x10000L; private val PTRT = 0x11000L; private val PAGT = 0x12000L
  /** Descriptor read latency: a walk costs ~3x this, so "waited for the walk" and
    * "did not" are separated by a wide margin. */
  private val Lat = 24

  // Distinct ATC sets/banks (vpn[2:0]) so no entry evicts another.
  private val vpnA = 0x02001L; private val ppnA = 0x31001L   // resident
  private val vpnB = 0x02002L; private val ppnB = 0x31002L   // the wrong-path walk
  private val vpnC = 0x02003L; private val ppnC = 0x31003L   // a miss behind the dead walk
  private val vpnD = 0x02004L; private val ppnD = 0x31004L   // exception-sequencer walk

  private def build(mem: DcacheClientMemAgent, vpn: Long, ppn: Long): Unit = {
    mem.pokeLong(ROOT + ((vpn >> 13) & 0x7f) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    mem.pokeLong(PTRT + ((vpn >> 6) & 0x7f) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    // resident + U + M: no deferred descriptor write is ever queued.
    mem.pokeLong(PAGT + (vpn & 0x3f) * 4, ((ppn << 12) & 0xfffff000L) | 0x19L)
  }

  case class Rsp(cycle: Long, token: Int, ppn: Long, fault: Boolean)

  /** Returns (cycles from the flush to A's response, number of descriptor reads issued
    * after the flush before A answered). */
  private def run(hitUnderWalk: Boolean, abortOnFlush: Boolean): Unit = {
    SimConfig.withVerilator.compile(new Dut(hitUnderWalk, abortOnFlush)).doSim { dut =>
      val tag = s"[hitUnderWalk=$hitUnderWalk abortOnFlush=$abortOnFlush]"
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd, null, Lat, true)
      val p = dut.probe.logic
      p.reqValid #= false; p.reqVpn #= 0; p.reqToken #= 0
      p.flush #= false; p.preCommitted #= false
      Seq((vpnA, ppnA), (vpnB, ppnB), (vpnC, ppnC), (vpnD, ppnD)).foreach { case (v, q) => build(mem, v, q) }
      cd.waitSampling(4)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.srp #= ROOT
      dut.ctrl.logic.urp #= ROOT
      cd.waitSampling(2)

      var cycle = 0L
      val rsps = ArrayBuffer[Rsp]()
      cd.onSamplings {
        cycle += 1
        if (p.rspValid.toBoolean)
          rsps += Rsp(cycle, p.rspToken.toInt, p.rspPpn.toLong, p.rspFault.toBoolean)
      }
      /** Present one request and hold it until it is accepted; returns the fire cycle. */
      def issue(vpn: Long, token: Int): Long = {
        p.reqVpn #= vpn; p.reqToken #= token; p.reqValid #= true
        var g = 0
        cd.waitSampling()
        while (!p.reqReady.toBoolean && g < 2000) { cd.waitSampling(); g += 1 }
        assert(g < 2000, s"$tag request vpn=0x${vpn.toHexString} never accepted")
        val c = cycle
        p.reqValid #= false
        c
      }
      def awaitRsp(token: Int, after: Long, limit: Int = 2000): Rsp = {
        var g = 0
        while (!rsps.exists(r => r.token == token && r.cycle >= after) && g < limit) { cd.waitSampling(); g += 1 }
        val r = rsps.find(r => r.token == token && r.cycle >= after)
        assert(r.isDefined, s"$tag no response for token $token")
        r.get
      }
      def pulseFlush(): Long = { p.flush #= true; cd.waitSampling(); p.flush #= false; cycle }

      // ── 1. warm A into the ATC ──────────────────────────────────────────────────
      val fA0 = issue(vpnA, 1)
      val rA0 = awaitRsp(1, fA0)
      assert(rA0.ppn == ppnA && !rA0.fault, s"$tag warm-up translation of A")
      cd.waitSampling(4)

      // ── 2. a "wrong-path" access misses on B; flush it once the walk is under way ─
      val loadsBefore = mem.loadCount
      issue(vpnB, 2)
      var g = 0
      while (mem.loadCount < loadsBefore + 1 && g < 200) { cd.waitSampling(); g += 1 }
      cd.waitSampling(Lat / 2)          // root read outstanding, mid-level
      val flushCycle = pulseFlush()
      val loadsAtFlush = mem.loadCount

      // ── 3. the correct path's first access: A, resident ───────────────────────────
      val fA1 = issue(vpnA, 3)
      val rA1 = awaitRsp(3, fA1)
      val aLatency = rA1.cycle - flushCycle
      assert(rA1.ppn == ppnA && !rA1.fault, s"$tag A after the flush")
      // wait until the walker is idle so the read count is final
      g = 0
      while (p.walkerBusy.toBoolean && g < 2000) { cd.waitSampling(); g += 1 }
      cd.waitSampling(4)
      val readsAfterFlush = mem.loadCount - loadsAtFlush
      val bRsp = rsps.filter(r => r.token == 2)
      info(f"$tag A answered $aLatency cycles after the flush; descriptor reads issued after " +
           f"the flush by the dead walk = $readsAfterFlush; B responses delivered = ${bRsp.size}")
      if (hitUnderWalk) {
        assert(aLatency <= 6, s"$tag a resident page must be answered under the dead walk (took $aLatency)")
      } else if (abortOnFlush) {
        assert(aLatency <= Lat + 10,
          s"$tag the aborted walk must release the DTLB after its outstanding read (took $aLatency)")
      } else {
        assert(aLatency > 2 * Lat, s"$tag BASELINE: A waits for the whole dead walk (took $aLatency)")
      }
      if (abortOnFlush) assert(readsAfterFlush == 0,
        s"$tag the aborted walk issued $readsAfterFlush descriptor read(s) after the flush")
      else assert(readsAfterFlush == 2, s"$tag an unaborted walk finishes its 2 remaining levels")
      if (hitUnderWalk || abortOnFlush)
        assert(bRsp.isEmpty, s"$tag the squashed walk's response must not be delivered: $bRsp")

      // ── 4. B was not installed: re-requesting it re-walks and gets the right PPN ──
      val lB = mem.loadCount
      val fB = issue(vpnB, 4)
      val rB = awaitRsp(4, fB)
      assert(rB.ppn == ppnB && !rB.fault, s"$tag B re-request")
      assert(mem.loadCount - lB == 3, s"$tag B must re-walk (3 reads), saw ${mem.loadCount - lB}")

      // ── 5. a MISS captured behind a dead walk: flush mid-walk of A' (re-use C as
      //       the squashed walk's victim is not needed -- squash a fresh walk of D's
      //       neighbour), then immediately request C, which must walk after it. ──────
      val vpnE = 0x02005L; build(mem, vpnE, 0x31005L)
      val lE = mem.loadCount
      issue(vpnE, 5)
      g = 0
      while (mem.loadCount < lE + 1 && g < 200) { cd.waitSampling(); g += 1 }
      pulseFlush()
      val fC = issue(vpnC, 6)
      val rC = awaitRsp(6, fC)
      assert(rC.ppn == ppnC && !rC.fault, s"$tag C (a miss behind the dead walk) -> 0x${rC.ppn.toHexString}")
      if (hitUnderWalk || abortOnFlush)
        assert(!rsps.exists(_.token == 5), s"$tag squashed walk E delivered a response")

      // ── 6. an exception-sequencer (pre-committed) walk is never squashed ─────────
      cd.waitSampling(4)
      p.preCommitted #= true
      cd.waitSampling()
      val lD = mem.loadCount
      val fD = issue(vpnD, 7)
      g = 0
      while (mem.loadCount < lD + 1 && g < 200) { cd.waitSampling(); g += 1 }
      pulseFlush()                       // a doFlush during the episode: still owned
      val rD = awaitRsp(7, fD)
      assert(rD.ppn == ppnD && !rD.fault, s"$tag pre-committed walk D must complete")
      p.preCommitted #= false
      cd.waitSampling(4)

      // ── 7. excActive rising squashes an LS walk exactly like a flush ─────────────
      val vpnF = 0x02006L; build(mem, vpnF, 0x31006L)
      val lF = mem.loadCount
      issue(vpnF, 8)
      g = 0
      while (mem.loadCount < lF + 1 && g < 200) { cd.waitSampling(); g += 1 }
      p.preCommitted #= true            // exception sequencer takes over
      val excStart = cycle
      val fA2 = issue(vpnA, 9)
      val rA2 = awaitRsp(9, fA2)
      info(s"$tag exception-entry: resident translation answered ${rA2.cycle - excStart} cycles after excActive")
      if (hitUnderWalk) assert(rA2.cycle - excStart <= 6, s"$tag exc access must not wait for the LS walk")
      if (hitUnderWalk || abortOnFlush) assert(!rsps.exists(_.token == 8), s"$tag squashed walk F responded")
      p.preCommitted #= false
      cd.waitSampling(Lat * 4)
    }
  }

  for (h <- Seq(false, true); a <- Seq(false, true))
    test(s"squashed DTLB walk: hitUnderWalk=$h abortOnFlush=$a") { run(h, a) }
}
