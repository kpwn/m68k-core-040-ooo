package m68k040.debug

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.{DcachePlugin, DcacheProbePlugin, FetchProbePlugin, IcachePlugin}
import m68k040.mmu.{DIdentityTranslationPlugin, DtlbPlugin, DtlbProbePlugin, IdentityTranslationPlugin,
                    ItlbPlugin, ItlbProbePlugin, MmuControlPlugin}
import m68k040.sim.WalkerDcacheSimIo
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import org.scalatest.funsuite.AnyFunSuite

/** PROVES THE MULTI-HOT EVIDENCE REGISTERS ARE NOT DEAD PROBES.
  *
  * This project has a long history of debug probes that read zero and lie -- `pc_live`,
  * `exc_count`, `OFF_CYCLE_LO` (declared, never served, so a healthy 200 MHz core
  * reported a dead clock), and `wedge-status`, which the host REPL now refuses outright
  * because it reads v1 probes cpu040's mux never implemented and would otherwise render
  * all-zero as a confident "everything is IDLE" line. On 2026-09-05 exactly that
  * fabricated line was used to RULE OUT a root cause. Nothing had been measured.
  *
  * A latch we cannot trust is worse than none, and it is worse here than usual, because
  * the READING THAT MATTERS MOST IS ZERO: an absent multi-hot after a failed boot is
  * what FALSIFIES the duplicate-entry hypothesis. A probe that reads zero because its
  * mux arm is missing is indistinguishable from one that reads zero because the
  * condition never happened -- and it would retire a live hypothesis for free.
  *
  * So each site is checked four ways, over the REAL `dbg_axi` path, against the REAL
  * plugins (`host.get` resolves concrete classes, so no stub can stand in):
  *   1. CLEAN ZERO when nothing has happened -- no reset garbage, no aliasing.
  *   2. A DISTINCT seeded value, written into the actual producer register and read
  *      back through the mux. Distinct per site and per field, so "reads back the right
  *      number" cannot be satisfied by accident.
  *   3. NO NEIGHBOUR ALIASING: with exactly one site seeded, every other site's word
  *      still reads zero.
  *   4. The host CLEAR actually clears -- both the producer state and the CSR view.
  *
  * It is also the first simulation DUT in this repo to wire `DebugCtrlPlugin` to the
  * real caches and TLBs at all; before this, OFF_STALL_DC/_GRANT/_WALK had no
  * functional coverage either, which is that same hazard applied to itself.
  */
class DebugCtrlMultiHotSpec extends AnyFunSuite {

  /** TLB half: the two priority sites. An ITLB multi-hot mistranslates a correct PC and
    * explains a wild BRANCH TARGET; a DTLB one explains a wild DATA address. */
  class TlbDut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dtlb = new DtlbPlugin()
    val itlb = new ItlbPlugin()
    val dProbe = new DtlbProbePlugin
    val iProbe = new ItlbProbePlugin
    val dWalk = new WalkerDcacheSimIo(dtlb, "dtlbWalk")
    val iWalk = new WalkerDcacheSimIo(itlb, "itlbWalk")
    val dbg = new DebugCtrlPlugin(buildId = BigInt(0x4D480001L), porCycles = 4, stage = 2)
    db.on {
      host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dtlb, itlb,
        dProbe, iProbe, dWalk, iWalk, dbg))
    }
    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  /** Cache half: the four cache sites. */
  class CacheDut extends Component {
    val db = new Database
    val host = db on (new PluginHost)
    val ctrl = new MmuControlPlugin()
    val dXlate = new DIdentityTranslationPlugin
    val iXlate = new IdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val icache = new IcachePlugin
    val dProbe = new DcacheProbePlugin
    val iProbe = new FetchProbePlugin
    val dbg = new DebugCtrlPlugin(buildId = BigInt(0x4D480002L), porCycles = 4, stage = 2)
    db.on {
      host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), ctrl, dXlate, iXlate,
        dcache, icache, dProbe, iProbe, dbg))
    }
    def axi: DbgAxiLite = dbg.logic.dbgAxi
  }

  import DebugRegMap._
  private val AddrOffsets = Seq(
    OFF_MULTIHOT_IC, OFF_MULTIHOT_DCLOAD, OFF_MULTIHOT_DCPROBE,
    OFF_MULTIHOT_DCSTORE, OFF_MULTIHOT_ITLB, OFF_MULTIHOT_DTLB)
  private val SiteNames = Seq("icache", "dcache-load", "dcache-probe",
                              "dcache-store", "itlb", "dtlb")

  private def u(v: Long): Long = v & 0xFFFFFFFFL

  /** One site's seedable producer state. */
  private case class Site(name: String, idx: Int,
                          setSticky: Boolean => Unit,
                          setCount: Int => Unit,
                          setAddr: Long => Unit)

  private def runSites(dutAxi: DbgAxiLite, cd: ClockDomain, sites: Seq[Site],
                       settle: () => Unit): Unit = {
    val b = dutAxi
    DbgAxiDriver.idle(b)
    cd.waitSampling(30)

    // ---- 1. CLEAN ZERO -------------------------------------------------------
    val status0 = u(DbgAxiDriver.read(b, cd, OFF_MULTIHOT_STATUS))
    assert(status0 == 0L,
      f"OFF_MULTIHOT_STATUS read 0x$status0%08x out of reset, expected 0. A nonzero " +
      f"idle value would make every later reading uninterpretable.")
    for ((off, nm) <- AddrOffsets.zip(SiteNames)) {
      val v = u(DbgAxiDriver.read(b, cd, off))
      assert(v == 0L, f"$nm address word read 0x$v%08x out of reset, expected 0")
    }

    // ---- 2 + 3. seed ONE site at a time, distinct values, check no aliasing ---
    for (site <- sites) {
      val seedCount = 1 + site.idx                 // 1..6, distinct, never 0 or 15
      val seedAddr  = 0xA5000000L | (site.idx.toLong << 16) | 0x1234L
      site.setSticky(true); site.setCount(seedCount); site.setAddr(seedAddr)
      settle()

      val status = u(DbgAxiDriver.read(b, cd, OFF_MULTIHOT_STATUS))
      val gotSticky = ((status >> site.idx) & 1L) == 1L
      val gotCount  = ((status >> (8 + 4 * site.idx)) & 0xfL).toInt
      val gotAddr   = u(DbgAxiDriver.read(b, cd, AddrOffsets(site.idx)))
      println(f"[multihot-csr] ${site.name}%-13s status=0x$status%08x sticky=$gotSticky " +
              f"count=$gotCount addr=0x$gotAddr%08x (seeded count=$seedCount " +
              f"addr=0x$seedAddr%08x)")

      assert(gotSticky,
        s"${site.name}: sticky bit ${site.idx} did not appear in OFF_MULTIHOT_STATUS " +
        f"(read 0x$status%08x). The mux arm or the bit position is wrong -- and this " +
        s"register would then read a confident zero on a board that really was corrupting.")
      assert(gotCount == seedCount,
        s"${site.name}: counter read $gotCount, seeded $seedCount. Wrong nibble position " +
        s"or wrong source register.")
      assert(gotAddr == seedAddr,
        f"${site.name}: address word read 0x$gotAddr%08x, seeded 0x$seedAddr%08x. The " +
        f"mux arm does not reach the intended register.")

      // no neighbour aliasing: every OTHER site is still clean
      for (other <- sites if other.idx != site.idx) {
        val ov = u(DbgAxiDriver.read(b, cd, AddrOffsets(other.idx)))
        assert(ov == 0L,
          f"ALIASING: seeding ${site.name} made ${other.name} read 0x$ov%08x. Two " +
          f"offsets share a source, so a reading would name the wrong structure.")
        val osticky = ((status >> other.idx) & 1L) == 1L
        assert(!osticky,
          s"ALIASING: seeding ${site.name} also set ${other.name}'s sticky bit")
      }

      // ---- 4. the host clear really clears ----------------------------------
      DbgAxiDriver.write(b, cd, OFF_MULTIHOT_STATUS, 1L)
      cd.waitSampling(10)
      settle()
      val afterClear = u(DbgAxiDriver.read(b, cd, OFF_MULTIHOT_STATUS))
      val afterAddr  = u(DbgAxiDriver.read(b, cd, AddrOffsets(site.idx)))
      assert(afterClear == 0L,
        f"${site.name}: OFF_MULTIHOT_STATUS still 0x$afterClear%08x after a clear write. " +
        f"A latch that cannot be cleared reports the first boot for ever.")
      assert(afterAddr == 0L,
        f"${site.name}: address word still 0x$afterAddr%08x after a clear write")
    }
  }

  test("TLB multi-hot evidence survives the real dbg_axi path (ITLB and DTLB)",
       VerilatorTest) {
    M68kSim().compile(new TlbDut).doSim("multihot_csr_tlb", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.dbg.logic.initDoneSeen #= false
      // Pin every producer input so nothing walks or fills while we seed.
      dut.dProbe.logic.reqIn.valid #= false
      dut.iProbe.logic.reqIn.valid #= false
      for (p <- Seq("dtlbWalk", "itlbWalk")) {}
      dut.dWalk.logic.rsp.valid #= false
      dut.iWalk.logic.rsp.valid #= false
      dut.dWalk.logic.cmd.ready #= false
      dut.iWalk.logic.cmd.ready #= false
      dut.dWalk.logic.st.ready #= false
      dut.iWalk.logic.st.ready #= false
      dut.dWalk.logic.stAck #= false
      dut.iWalk.logic.stAck #= false
      dut.dWalk.logic.stErr #= false
      dut.iWalk.logic.stErr #= false

      def settle(): Unit = cd.waitSampling(6)
      val sites = Seq(
        Site("itlb", 4,
          v => dut.itlb.logic.tlb.dbgEvidence.sticky #= v,
          v => dut.itlb.logic.tlb.dbgEvidence.count #= v,
          v => dut.itlb.logic.tlb.dbgEvidence.addr #= BigInt(v)),
        Site("dtlb", 5,
          v => dut.dtlb.logic.tlb.dbgEvidence.sticky #= v,
          v => dut.dtlb.logic.tlb.dbgEvidence.count #= v,
          v => dut.dtlb.logic.tlb.dbgEvidence.addr #= BigInt(v)))
      runSites(dut.axi, cd, sites, settle)
    }
  }

  test("cache multi-hot evidence survives the real dbg_axi path (4 sites)",
       VerilatorTest) {
    M68kSim().compile(new CacheDut).doSim("multihot_csr_cache", 1) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      // Both caches' AXI must be ANSWERED, not left dangling: an unattached read
      // channel delivers X/random beats and the I-cache's own "R beat has no live RID
      // owner" tripwire fires long before any CSR is read. The caches are never
      // stimulated here -- the evidence registers are seeded directly -- but their bus
      // still has to be well-formed.
      m68k040.cache.IcacheSim.attachMemory(dut.icache.logic.axi, cd, 0L, 0x1000)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      dut.dbg.logic.initDoneSeen #= false
      dut.dProbe.logic.loadCmdIn.valid #= false
      dut.dProbe.logic.loadProbeIn.valid #= false
      dut.dProbe.logic.loadProbeCancelIn.valid #= false
      dut.dProbe.logic.storeIn.valid #= false
      dut.dProbe.logic.maintCmdIn.valid #= false
      dut.iProbe.logic.cmdIn.valid #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)

      def settle(): Unit = cd.waitSampling(6)
      val ic = dut.icache.logic.dbgMultiHot
      val dl = dut.dcache.logic.dbgMultiHotLoad
      val dp = dut.dcache.logic.dbgMultiHotProbe
      val ds = dut.dcache.logic.dbgMultiHotStore
      val sites = Seq(
        Site("icache", 0, v => ic.sticky #= v, v => ic.count #= v, v => ic.addr #= BigInt(v)),
        Site("dcache-load", 1, v => dl.sticky #= v, v => dl.count #= v, v => dl.addr #= BigInt(v)),
        Site("dcache-probe", 2, v => dp.sticky #= v, v => dp.count #= v, v => dp.addr #= BigInt(v)),
        Site("dcache-store", 3, v => ds.sticky #= v, v => ds.count #= v, v => ds.addr #= BigInt(v)))
      runSites(dut.axi, cd, sites, settle)
    }
  }
}
