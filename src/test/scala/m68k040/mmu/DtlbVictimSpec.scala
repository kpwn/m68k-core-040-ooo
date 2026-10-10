package m68k040.mmu

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DTranslationToken
import m68k040.services.{DTranslationService, MmuControlService}
import m68k040.sim.DcacheClientMemAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** The DTLB victim buffer (`CPU_DTLB_VICTIM`, `DtlbPlugin.victimEntries`).
  *
  * Every DUT is constructed with EXPLICIT constructor arguments, so these tests cover
  * the ON arm (and the OFF control) in every environment, whatever the env flags say.
  *
  * Geometry used: 4 KB pages, the 32-entry array is 2 banks x 4 sets x 4 ways and the
  * row is vpn[2:0], so vpns 0x02000 + 8k (k = 0..7) all fight for the same 4 ways. */
class DtlbVictimSpec extends AnyFunSuite {

  class Probe extends FiberPlugin {
    val logic = during build new Area {
      val xlate = host[DTranslationService]
      val mctrl = host[MmuControlService]
      val dtlb  = host[DtlbPlugin]
      val reqValid = in Bool ()
      val reqVpn   = in UInt (20 bits)
      val reqWrite = in Bool ()
      val reqSup   = in Bool ()
      val reqToken = in UInt (DTranslationToken.Width bits)
      val reqReady = out Bool ()
      xlate.req.valid              := reqValid
      xlate.req.payload.vpn        := reqVpn
      xlate.req.payload.supervisor := reqSup
      xlate.req.payload.write      := reqWrite
      xlate.req.payload.token      := reqToken
      reqReady := xlate.req.ready
      xlate.rsp.ready := True
      val rspValid = out Bool ();  rspValid := xlate.rsp.valid
      val rspToken = out UInt (DTranslationToken.Width bits); rspToken := xlate.rsp.payload.token
      val rspPpn   = out UInt (20 bits); rspPpn := xlate.rsp.payload.ppn
      val rspFault = out Bool ();  rspFault := xlate.rsp.payload.fault

      // ── every ATC flush source, through its real port ──
      val pflush   = in Bool ()
      val umFlush  = in Bool ()
      val wSrp     = in Bool ()
      val wUrp     = in Bool ()
      val wDtt0    = in Bool ()
      val wDtt1    = in Bool ()
      val wTcP     = in Bool ()
      val tcPVal   = in Bool ()
      val wTcE     = in Bool ()
      val tcEVal   = in Bool ()
      val rootVal  = in UInt (32 bits)
      val walkHold = in Bool ()
      val preCommitted = in Bool ()
      dtlb.umAccessPreCommitted := preCommitted
      dtlb.flushAll := pflush
      dtlb.umFlush  := umFlush
      when(wSrp)  { mctrl.setSrp.valid := True;  mctrl.setSrp.payload := rootVal }
      when(wUrp)  { mctrl.setUrp.valid := True;  mctrl.setUrp.payload := rootVal }
      when(wDtt0) { mctrl.setDtt0.valid := True; mctrl.setDtt0.payload := 0 }
      when(wDtt1) { mctrl.setDtt1.valid := True; mctrl.setDtt1.payload := 0 }
      when(wTcP)  { mctrl.setPageSize.valid := True; mctrl.setPageSize.payload := tcPVal }
      when(wTcE)  { mctrl.setEnable.valid := True;   mctrl.setEnable.payload := tcEVal }
      dtlb.walkOrderHold := walkHold
      val walkerBusy = out Bool (); walkerBusy := dtlb.logic.walker.io.busy
      val walkerStart = out Bool (); walkerStart := dtlb.logic.walker.io.start
    }
  }

  class Dut(victim: Int, neg: Int = DtlbPlugin.VictimNegativeControl.None,
            hitUnderWalk: Boolean = true) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin(hitUnderWalk = hitUnderWalk, abortOnFlush = true, walkSqCheck = true,
                              victimEntries = victim, victimNegativeControl = neg)
    val probe = new Probe()
    val walkPort = new m68k040.sim.WalkerDcacheSimIo(dtlb, "dtlbWalk")
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, probe, walkPort)) }
  }

  private val ROOT = 0x10000L; private val PTRT = 0x11000L; private val PAGT = 0x12000L
  private val RowBase = 0x02000L
  private def rowVpn(k: Int): Long = RowBase + 8L * k
  private def ppnOf(vpn: Long): Long = 0x30000L | (vpn & 0xfff)
  // Page descriptor low byte: PDT=01 resident, W=0x04, U=0x08, M=0x10, S=0x80.
  private val UM = 0x19L

  case class Rsp(ppn: Long, fault: Boolean, cycles: Long)

  class Env(val dut: Dut, val mem: DcacheClientMemAgent) {
    val cd = dut.clockDomain
    val p  = dut.probe.logic
    var cycle = 0L
    var walks = 0           // walker launches
    var promotes = 0
    var walkOnHits = 0
    cd.onSamplings {
      cycle += 1
      if (p.walkerStart.toBoolean) walks += 1
      if (dut.dtlb.logic.vic.enabled) {
        if (dut.dtlb.logic.vic.promote.toBoolean) promotes += 1
        if (dut.dtlb.logic.vic.walkOnHit.toBoolean) walkOnHits += 1
      }
    }
    var token = 0
    def build(vpn: Long, ppn: Long, flags: Long = UM): Unit = {
      mem.pokeLong(ROOT + ((vpn >> 13) & 0x7f) * 4, (PTRT & 0xfffffff0L) | 0x3L)
      mem.pokeLong(PTRT + ((vpn >> 6) & 0x7f) * 4, (PAGT & 0xfffffff0L) | 0x3L)
      mem.pokeLong(PAGT + (vpn & 0x3f) * 4, ((ppn << 12) & 0xfffff000L) | flags)
    }
    def desc(vpn: Long): Long = mem.peekLong(PAGT + (vpn & 0x3f) * 4)
    /** One access, waited to its own response. */
    def access(vpn: Long, write: Boolean = false, sup: Boolean = true, limit: Int = 3000): Rsp = {
      token = (token + 1) & ((1 << DTranslationToken.Width) - 1)
      val t = token
      p.reqVpn #= vpn; p.reqWrite #= write; p.reqSup #= sup; p.reqToken #= t; p.reqValid #= true
      cd.waitSampling()
      var g = 0
      while (!p.reqReady.toBoolean && g < limit) { cd.waitSampling(); g += 1 }
      assert(g < limit, f"request vpn=0x$vpn%05x never accepted")
      val start = cycle
      p.reqValid #= false
      while (!(p.rspValid.toBoolean && p.rspToken.toInt == t) && g < limit) { cd.waitSampling(); g += 1 }
      assert(g < limit, f"no response for vpn=0x$vpn%05x")
      val r = Rsp(p.rspPpn.toLong, p.rspFault.toBoolean, cycle - start)
      cd.waitSampling(2)
      r
    }
    def pulse(sig: Bool): Unit = { sig #= true; cd.waitSampling(); sig #= false; cd.waitSampling(3) }
    /** Slots a probe could hit right now. */
    def liveSlots: Int = {
      val v = dut.dtlb.logic.vic
      if (v.wrapHazard.toBoolean) 0
      else (0 until v.n).count(i => v.valids(i).toBoolean && v.gens(i).toInt == v.curGen.toInt)
    }
  }

  private def withDut(victim: Int, neg: Int = DtlbPlugin.VictimNegativeControl.None,
                      hitUnderWalk: Boolean = true, lat: Int = 1)(body: Env => Unit): Unit =
    SimConfig.withVerilator.compile(new Dut(victim, neg, hitUnderWalk)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new DcacheClientMemAgent(dut.walkPort, cd, null, lat, true)
      val p = dut.probe.logic
      p.reqValid #= false; p.reqVpn #= 0; p.reqWrite #= false; p.reqSup #= true; p.reqToken #= 0
      Seq(p.pflush, p.umFlush, p.wSrp, p.wUrp, p.wDtt0, p.wDtt1, p.wTcP, p.tcPVal, p.wTcE,
          p.tcEVal, p.walkHold, p.preCommitted).foreach(_ #= false)
      p.rootVal #= ROOT
      cd.waitSampling(4)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.srp #= ROOT
      dut.ctrl.logic.urp #= ROOT
      cd.waitSampling(2)
      val env = new Env(dut, mem)
      for (k <- 0 until 8) env.build(rowVpn(k), ppnOf(rowVpn(k)))
      body(env)
    }

  /** Touch rows 0..7: rows 0..3 end up in the victim buffer (ON). */
  private def fillRow(e: Env): Unit =
    for (k <- 0 until 8) {
      val r = e.access(rowVpn(k))
      assert(r.ppn == ppnOf(rowVpn(k)) && !r.fault)
    }

  test("DTLB victim: an 8-page row conflict -- re-touches are promoted, every PPN its own", VerilatorTest) {
    var offWalks = 0; var onWalks = 0; var onPromotes = 0
    for (victim <- Seq(0, 32)) withDut(victim) { e =>
      for (pass <- 0 until 3; k <- 0 until 8) {
        val v = rowVpn(k)
        val r = e.access(v)
        assert(r.ppn == ppnOf(v) && !r.fault,
          f"[victim=$victim] pass $pass vpn 0x$v%05x -> ppn 0x${r.ppn}%05x fault=${r.fault}")
      }
      if (victim == 0) offWalks = e.walks else { onWalks = e.walks; onPromotes = e.promotes }
    }
    info(s"24 accesses over 8 same-row pages: OFF walks=$offWalks; ON walks=$onWalks promotes=$onPromotes")
    assert(offWalks == 24, s"control: the 4-way row must thrash (got $offWalks walks)")
    assert(onWalks == 8 && onPromotes == 16, s"ON: only the 8 cold walks, the rest promoted")
  }

  // ── every ATC flush source must retire the buffer ──────────────────────────────
  private val flushSources: Seq[(String, Env => Unit)] = Seq(
    "PFLUSH/PFLUSHA (flushAll)" -> (e => e.pulse(e.p.pflush)),
    "MOVEC SRP"                 -> (e => e.pulse(e.p.wSrp)),
    "MOVEC URP"                 -> (e => e.pulse(e.p.wUrp)),
    "MOVEC DTT0"                -> (e => e.pulse(e.p.wDtt0)),
    "MOVEC DTT1"                -> (e => e.pulse(e.p.wDtt1)),
    "MOVEC TC (P 4K->8K->4K)"   -> { e => e.p.tcPVal #= true; e.pulse(e.p.wTcP)
                                          e.p.tcPVal #= false; e.pulse(e.p.wTcP) },
    "MOVEC TC (E 1->0->1)"      -> { e => e.p.tcEVal #= false; e.pulse(e.p.wTcE)
                                          e.p.tcEVal #= true; e.pulse(e.p.wTcE) },
    "4 back-to-back PFLUSHAs (2-bit generation WRAP)" -> { e =>
                                     e.p.pflush #= true; e.cd.waitSampling(4)
                                     e.p.pflush #= false; e.cd.waitSampling(3) }
  )

  for ((name, flush) <- flushSources)
    test(s"DTLB victim: $name retires every victim entry -- a remapped page re-walks", VerilatorTest) {
      withDut(32) { e =>
        fillRow(e)
        assert(e.liveSlots == 4, s"setup: rows 0..3 must be in the buffer (live=${e.liveSlots})")
        val v = rowVpn(0)
        e.build(v, 0x4aaaa)               // remap row 0 behind the ATC's back
        flush(e)
        assert(e.liveSlots == 0, s"$name: ${e.liveSlots} victim entries still live after the flush")
        val w0 = e.walks; val pr0 = e.promotes
        val r = e.access(v)
        assert(r.ppn == 0x4aaaa, f"$name: STALE victim translation 0x${r.ppn}%05x served after the flush")
        assert(e.walks == w0 + 1 && e.promotes == pr0, s"$name: must re-walk, not promote")
      }
    }

  test("DTLB victim NEGATIVE CONTROL: without generation tags a PFLUSHA leaves a stale hit", VerilatorTest) {
    withDut(32, neg = DtlbPlugin.VictimNegativeControl.NoGeneration) { e =>
      fillRow(e)
      val v = rowVpn(0)
      e.build(v, 0x4aaaa)
      e.pulse(e.p.pflush)
      val r = e.access(v)
      assert(r.ppn == ppnOf(v),
        "negative control: with the generation check deleted the stale entry must be served " +
        "(otherwise the flush test above is not sensitive to the mechanism)")
    }
  }

  test("DTLB victim: write-protect and supervisor-only answer exactly as an array hit", VerilatorTest) {
    val vW = rowVpn(0); val vS = rowVpn(1)
    for (victim <- Seq(0, 32)) withDut(victim) { e =>
      e.build(vW, ppnOf(vW), 0x09L | 0x04L) // write-protected, U set, M clear
      e.build(vS, ppnOf(vS), UM | 0x80L)   // supervisor-only
      // Prime both by a permitted access, then push them out of the array.
      assert(!e.access(vW).fault)
      assert(!e.access(vS, sup = true).fault)
      for (k <- 2 until 8) e.access(rowVpn(k))
      val pr0 = e.promotes
      val rw = e.access(vW, write = true)
      assert(rw.fault, s"[victim=$victim] a write to a WP page must fault")
      val ru = e.access(vS, sup = false)
      assert(ru.fault, s"[victim=$victim] a user access to an S page must fault")
      val rs = e.access(vS, sup = true)
      assert(!rs.fault && rs.ppn == ppnOf(vS), s"[victim=$victim] supervisor access to the S page")
      if (victim > 0)
        assert(e.promotes > pr0, "the WP / supervisor answers must have come from victim promotes")
      // A WP fault leaves the page readable and no M is ever set.
      assert((e.desc(vW) & 0x10L) == 0, "a faulting write must not set M")
    }
  }

  test("DTLB victim: a WRITE to a clean (M=0) victim entry walks to set M -- U/M identical to a walk", VerilatorTest) {
    val v = rowVpn(0)
    def run(neg: Int): (Int, Int, Boolean) = {
      var res = (0, 0, false)
      withDut(32, neg = neg) { e =>
        // Exception-sequencer-owned accesses: the U/M write is born committed, so it
        // drains to memory in this standalone DUT and M can be read back.
        e.p.preCommitted #= true
        e.build(v, ppnOf(v), 0x09L)        // resident, U set, M CLEAR
        assert(!e.access(v).fault)          // read walk: entry cached with M=0
        for (k <- 1 until 8) e.access(rowVpn(k))
        val w0 = e.walks
        val r = e.access(v, write = true)
        assert(!r.fault && r.ppn == ppnOf(v))
        val walked = e.walks - w0
        e.cd.waitSampling(60)
        val queued = (e.desc(v) & 0x10L) != 0   // M now set in the descriptor in memory
        // A second write now hits the ARRAY with M=1: no walk.
        val w1 = e.walks
        assert(!e.access(v, write = true).fault)
        if (neg == DtlbPlugin.VictimNegativeControl.None)
          assert(e.walks == w1, "after the M-refresh walk the entry is cached with M=1")
        res = (walked, e.walkOnHits, queued)
      }
      res
    }
    val (walked, woh, queued) = run(DtlbPlugin.VictimNegativeControl.None)
    assert(walked == 1 && woh == 1, s"the write must take the table search (walks=$walked walkOnHit=$woh)")
    assert(queued, "the walk must set M in the page descriptor in memory, exactly as for a walk")
    val (nWalked, _, nQueued) = run(DtlbPlugin.VictimNegativeControl.NoMRefreshWalk)
    assert(nWalked == 0 && !nQueued,
      "negative control: promoting a clean entry for a write must LOSE the M update " +
      "(otherwise this test is not sensitive to the mechanism)")
  }

  test("DTLB victim: user and supervisor translations of one page are separate entries", VerilatorTest) {
    withDut(32) { e =>
      val v = rowVpn(0)
      e.access(v, sup = true); e.access(v, sup = false)   // both cached (same set; 2 ways)
      for (k <- 1 until 8) e.access(rowVpn(k))
      val pr0 = e.promotes
      assert(e.access(v, sup = false).ppn == ppnOf(v))
      assert(e.access(v, sup = true).ppn == ppnOf(v))
      assert(e.promotes == pr0 + 2, "each address space promotes its own entry")
    }
  }

  test("DTLB victim x CPU_WALK_SQ_CHECK: a promote needs no descriptor read, a walk still waits", VerilatorTest) {
    withDut(32) { e =>
      fillRow(e)                              // rows 0..3 in the buffer
      e.p.walkHold #= true                    // an older store to a descriptor line
      val pr0 = e.promotes
      val r = e.access(rowVpn(0), limit = 200)
      assert(r.ppn == ppnOf(rowVpn(0)) && e.promotes == pr0 + 1,
        "a victim hit is answered while descriptor reads are withheld")
      // A genuine miss must NOT complete while the hold stands.
      val vNew = 0x02020L + 1                 // different row, never touched
      e.build(vNew, ppnOf(vNew))
      e.p.reqVpn #= vNew; e.p.reqWrite #= false; e.p.reqSup #= true; e.p.reqToken #= 0x5
      e.p.reqValid #= true; e.cd.waitSampling(); e.p.reqValid #= false
      var sawRsp = false
      for (_ <- 0 until 100) { e.cd.waitSampling(); if (e.p.rspValid.toBoolean) sawRsp = true }
      assert(!sawRsp, "a walk must still be withheld by the SQ check")
      e.p.walkHold #= false
      var g = 0
      while (!e.p.rspValid.toBoolean && g < 500) { e.cd.waitSampling(); g += 1 }
      assert(e.p.rspValid.toBoolean && e.p.rspPpn.toLong == ppnOf(vNew), "the walk completes once released")
    }
  }

  test("DTLB victim x hit-under-walk: a victim hit is answered under a DETACHED walk", VerilatorTest) {
    withDut(32, lat = 24) { e =>
      fillRow(e)                              // rows 0..3 in the buffer
      val vB = 0x02021L
      e.build(vB, ppnOf(vB))
      // Start B's walk, then squash its owner: the walk detaches and keeps running.
      e.p.reqVpn #= vB; e.p.reqWrite #= false; e.p.reqSup #= true; e.p.reqToken #= 0x6
      e.p.reqValid #= true; e.cd.waitSampling(); e.p.reqValid #= false
      var g = 0
      while (!e.p.walkerBusy.toBoolean && g < 50) { e.cd.waitSampling(); g += 1 }
      assert(e.p.walkerBusy.toBoolean, "B's walk launched")
      e.cd.waitSampling(2)
      e.p.umFlush #= true; e.cd.waitSampling(); e.p.umFlush #= false
      val pr0 = e.promotes
      val r = e.access(rowVpn(1), limit = 60)
      assert(r.ppn == ppnOf(rowVpn(1)) && e.promotes == pr0 + 1, "promoted")
      assert(e.p.walkerBusy.toBoolean, "...while the dead walk was still running")
      while (e.p.walkerBusy.toBoolean) e.cd.waitSampling()
      e.cd.waitSampling(4)
      val w0 = e.walks
      assert(e.access(vB).ppn == ppnOf(vB), "B itself re-walks to the right PPN")
      assert(e.walks == w0 + 1, "the detached walk installed nothing")
    }
  }
}
