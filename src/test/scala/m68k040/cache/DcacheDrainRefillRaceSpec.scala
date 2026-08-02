package m68k040.cache

import m68k040.{M68kParams, M68kSim}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.ls.BehavioralMemAgent
import m68k040.mmu.DIdentityTranslationPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Standalone regression for design doc §5 item 11 / Task P4.4: a store drain's S1
  * tag-read / S2 stale-registered hit-detect+write can race a younger load-refill's
  * same-SET-different-LINE array write. PRE-EXISTING bug, independent of copyback
  * (write-through masks the damage in real MEMORY but not in the CACHE).
  *
  * Root cause (confirmed via a cycle-exact repro against the pre-P1.2 base commit
  * `cec4ab2`, throwaway worktree, Task P4.4 Step 1 -- NOT the same-cycle
  * assignment-ordering collision the design doc's prose might suggest at a glance;
  * SpinalHDL's last-assignment-wins actually favours the REFILL write when both fire
  * on the exact same cycle, since the FSM's REFILL write and the store-S2 write both
  * land on the array write port and the refill's tag write is uncontested either
  * way -- empirically confirmed BENIGN). The REAL hazard is a one-cycle-EARLIER
  * window: if the refill's array write lands on the EXACT cycle the store's S1 read
  * is LAUNCHED (not the S2 resolve cycle), the store's S1 sync-read (a simple
  * dual-port BRAM, no read-during-write forwarding, per this file's own documented
  * design) returns the PRE-write (stale) tag/data at S2 one cycle later -- by which
  * point the refill's write has already landed and moved on, so the store's S2
  * write is UNCONTESTED and silently corrupts the just-refilled line with a merge
  * based on stale data. Concretely: way W held line X; a store to X launches its S1
  * read on the SAME cycle a younger load's REFILL writes way W with a DIFFERENT
  * line Z (same set); the store's S2 (next cycle) still "hits" (stale tag == X) and
  * overwrites way W's DATA (now tagged Z, courtesy of the refill's own write, which
  * the store's block does not touch) with corrupted stale-X-based bytes. A later
  * load to Z then HITS the corrupted line.
  *
  * Task P4.4's fix: `refillWriteHold` (`(stS1Valid && stS1Set===missSet) ||
  * (stS2Valid && stS2Set===missSet)`) holds off ACCEPTING the refill's AXI R beat
  * (`axi.r.ready := !refillWriteHold`) while a same-set store drain is anywhere in
  * its S1/S2 window -- closing exactly the window this test exploits. */
class DcacheDrainRefillRaceSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val probe  = new DcacheProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe)) }
  }

  def simConfig = M68kSim().withVerilator

  def memByte(addr: Long): Int = ((addr * 5 + 0x23) & 0xff).toInt
  def preload(mem: BehavioralMemAgent, base: Long, n: Int): Unit =
    for (i <- 0 until n) mem.pokeByte(base + i, memByte(base + i))
  def expected(base: Long, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  def load(dut: Dut, cd: ClockDomain, vaddr: Long, size: SpinalEnumElement[Size.type]): BigInt = {
    dut.probe.logic.loadCmdIn.valid #= true
    dut.probe.logic.loadCmdIn.payload.vaddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.paddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.size #= size
    dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean && dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
    dut.probe.logic.loadRspOut.payload.data.toBigInt
  }

  // Same set (5), distinct tags per k (k*0x800 steps clear of the set/offset bits).
  val SET  = 5L
  val base = SET * 16L
  def addrK(k: Long): Long = base + k * 0x800L

  test("PRE-EXISTING BUG (design doc §5 item 11): same-set different-line refill " +
       "racing a store drain's S1 read must not corrupt the refilled line") {
    simConfig.compile(new Dut).doSim(seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, dut.clockDomain)
      dut.probe.logic.loadCmdIn.valid #= false
      dut.probe.logic.storeIn.valid   #= false
      dut.clockDomain.waitSampling(5)

      // addrK(0..3) fill ways 0..3 of set 5; addrK(4) is the young load's target
      // (a cold line in the SAME set -- refill's round-robin victim wraps to way 0
      // after the 4 warm-up loads, exactly re-using the way X (addrK(0)) occupies).
      for (k <- 0L until 5L) preload(mem, addrK(k), 16)
      for (k <- 0L until 4L) load(dut, dut.clockDomain, addrK(k), Size.LONG)

      // Fire the younger load (addrK(4), a miss -> REFILL, victim = way 0, which
      // currently holds addrK(0) = X) first, then ONE cycle later fire a store to
      // addrK(0) (X, way 0) for exactly one cycle -- empirically the exact offset
      // (Task P4.4 Step 1) that lands the store's S1 read-launch on the SAME cycle
      // the refill's AXI R response writes way 0 with the NEW line (addrK(4) = Z).
      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= addrK(4)
      dut.probe.logic.loadCmdIn.payload.paddr #= addrK(4)
      dut.probe.logic.loadCmdIn.payload.size  #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.clockDomain.waitSampling(1)   // the empirically-confirmed racing offset
      dut.probe.logic.storeIn.valid #= true
      dut.probe.logic.storeIn.payload.paddr #= addrK(0)
      dut.probe.logic.storeIn.payload.data  #= BigInt("DEADBEEF", 16)
      dut.probe.logic.storeIn.payload.size  #= Size.LONG
      dut.probe.logic.storeIn.payload.useStrb #= false
      dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH

      var storePulsed = false
      for (_ <- 0 until 30) {
        if (dut.probe.logic.loadCmdIn.ready.toBoolean) dut.probe.logic.loadCmdIn.valid #= false
        dut.clockDomain.waitSampling()
        if (!storePulsed) { dut.probe.logic.storeIn.valid #= false; storePulsed = true }
      }
      dut.probe.logic.storeIn.valid #= false
      dut.probe.logic.loadCmdIn.valid #= false
      dut.clockDomain.waitSampling(20)

      // A clean load to Z (addrK(4)) must see Z's REAL fetched data, not the
      // store's payload (DEADBEEF) nor any X-derived garbage merged into it.
      val got = load(dut, dut.clockDomain, addrK(4), Size.LONG)
      val exp = expected(addrK(4), 4)
      assert(got == exp,
        f"same-set drain-vs-refill race corrupted the refilled line: got=0x$got%08x " +
        f"expected=0x$exp%08x (store payload was 0xDEADBEEF -- a match against THAT " +
        f"value specifically confirms the exact stale-tag corruption this test targets)")
    }
  }
}
