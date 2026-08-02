package m68k040.cache

import m68k040.{M68kParams, M68kSim, VerilatorTest}
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
  * Task P4.4's fix: `refillWriteHold` holds off ACCEPTING the refill's AXI R beat
  * (`axi.r.ready := !refillWriteHold`) while a same-set store drain is anywhere in
  * its S1/S2 window -- closing exactly the window this test exploits.
  *
  * POST-P4.4 REVIEW: this spec now ALSO covers a second, independent instance of the
  * same class that P4.4's original set-only comparison did not close -- the SAME-WAY,
  * DIFFERENT-SET array-write-port collision. See the block comment above those sweeps
  * at the bottom of this file. */
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

  /** One shared verilator build for every test in this spec (the same-way sweeps below
    * are 11 offsets each; the DUT is identical for all of them). */
  lazy val compiled = simConfig.compile(new Dut)

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
       "racing a store drain's S1 read must not corrupt the refilled line",
       VerilatorTest) {
    compiled.doSim("sameSetDrainRefillRace", 1) { dut =>
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

  // ------------------------------------------------------------------------------
  // POST-P4.4 REVIEW, BUG 2 REGRESSION: SAME-WAY, DIFFERENT-SET array-write collision.
  //
  // `wrEn(w)/wrSet(w)/wrData(w)` is ONE write port PER WAY, SHARED ACROSS ALL SETS --
  // not one port per (way, set). P4.4's original `refillWriteHold` compared only SETS,
  // so a refill's allocate write to (way w, set A) and a store-S2 RMW hit-write to
  // (way w, set B != A) could both drive that one port on the same cycle. The load
  // FSM's assignments elaborate LAST (SpinalHDL StateMachine bodies run as a
  // `prePopTask`), so the refill silently WINS and the store's array write is DROPPED
  // -- no error, no retry, no fault.
  //
  // Detection here uses WRITETHROUGH, where the damage is cleanly observable and
  // unambiguous: the store's AXI beat is unconditional so MEMORY still gets the right
  // bytes, but the resident cache line (still valid, still correctly tagged) keeps its
  // STALE pre-store contents, and the very next load HITS it and returns them.
  //
  // Under COPYBACK the same dropped write is far worse: the S2 hit arm's dirty-bit
  // write (`dirtys(w)(stS2Set) := True`) is a SEPARATE register array indexed by a
  // DIFFERENT set, so it is NOT dropped by the collision -- the line is left marked
  // dirty holding stale data, which EVICT_WR then faithfully writes back to memory as
  // though it were the store's own result. The COPYBACK variant is swept below too.
  //
  // The collision is a ONE-CYCLE window (the refill's `axi.r.fire` cycle must be the
  // store's S2 cycle), so the store's presentation cycle is swept against a FIXED load
  // presentation cycle. Confirmed on the pre-fix RTL: `storeCycle=6` lands exactly on
  // it (traced: `rfire=true s2=true hitWay=0`, victimWay=0) and reads back the stale
  // pre-store image; the surrounding offsets are clean. The sweep is kept wide so the
  // test survives small, legitimate changes in refill latency.
  // ------------------------------------------------------------------------------
  val SET_A = 20L                     // refill target set
  val SET_B = 21L                     // store target set (DIFFERENT set, SAME way 0)
  def addrA(k: Long): Long = SET_A * 16L + k * 0x800L
  val addrB = SET_B * 16L

  /** Warm SET_A ways 0..3 (all CLEAN, so the 5th miss goes straight to REFILL with no
    * EVICT_WR in the way -- this test is about the array write port alone; the
    * round-robin victim has wrapped back to way 0) and SET_B way 0, so a store to
    * `addrB` HITS way 0 -- the SAME way index the SET_A refill is about to allocate. */
  def warmSameWaySetup(dut: Dut, cd: ClockDomain, mem: BehavioralMemAgent): Unit = {
    for (k <- 0L until 5L) preload(mem, addrA(k), 16)
    preload(mem, addrB, 16)
    for (k <- 0L until 4L) load(dut, cd, addrA(k), Size.LONG)   // SET_A ways 0..3
    load(dut, cd, addrB, Size.LONG)                             // SET_B way 0
    cd.waitSampling(4)
  }

  /** Presents the racing pair on EXACT, independent cycles (two forks) rather than
    * "present A, wait N, present B". The naive form is not offset-independent: at
    * offset 0 the load's `valid` gets deasserted before its first clock edge and the
    * load never happens at all -- silently turning the test into a no-op. */
  def raceSameWay(dut: Dut, cd: ClockDomain, storeCycle: Int,
                  storeMode: SpinalEnumElement[CacheMode.type]): Int = {
    val LOAD_CYCLE = 4
    var arCount = 0
    fork {
      while (true) {
        cd.waitSampling()
        if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1
      }
    }
    fork {
      cd.waitSampling(LOAD_CYCLE)
      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= addrA(4)
      dut.probe.logic.loadCmdIn.payload.paddr #= addrA(4)
      dut.probe.logic.loadCmdIn.payload.size  #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      cd.waitSampling()
      dut.probe.logic.loadCmdIn.valid #= false
    }
    fork {
      if (storeCycle > 0) cd.waitSampling(storeCycle)
      dut.probe.logic.storeIn.valid #= true
      dut.probe.logic.storeIn.payload.paddr #= addrB + 4
      dut.probe.logic.storeIn.payload.data  #= BigInt("DEADBEEF", 16)
      dut.probe.logic.storeIn.payload.size  #= Size.LONG
      dut.probe.logic.storeIn.payload.useStrb #= false
      dut.probe.logic.storeIn.payload.cacheMode #= storeMode
      cd.waitSampling()
      dut.probe.logic.storeIn.valid #= false
    }
    cd.waitSampling(80)
    arCount
  }

  for (storeCycle <- 0 to 10) {
    test(s"same-WAY different-SET refill must not silently drop a store-S2 array " +
         s"write (WRITETHROUGH, storeCycle=$storeCycle)", VerilatorTest) {
      compiled.doSim(s"sameWayWt_$storeCycle", 1) { dut =>
        val cd = dut.clockDomain
        cd.forkStimulus(10)
        val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
        dut.probe.logic.loadCmdIn.valid #= false
        dut.probe.logic.storeIn.valid   #= false
        cd.waitSampling(5)
        warmSameWaySetup(dut, cd, mem)

        val arCount = raceSameWay(dut, cd, storeCycle, CacheMode.WRITETHROUGH)

        // Scenario sanity: the racing refill must actually have happened (otherwise
        // the assertions below would be vacuously satisfied).
        assert(arCount == 1, s"scenario sanity: expected exactly one racing refill AR, got $arCount")
        // Memory always gets the bytes (the write-through beat is unconditional) --
        // this only confirms the store really happened; it does NOT detect the bug.
        assert(mem.peekByte(addrB + 4) == 0xDE, "write-through beat reached memory")

        // THE ASSERTION THAT DETECTS THE BUG: the SET_B line is still resident and
        // correctly tagged, so this load HITS the cache array. If the store's array
        // write was dropped by the same-way refill collision, it returns the STALE
        // pre-store image instead of the store's own data.
        val gotB = load(dut, cd, addrB + 4, Size.LONG)
        assert(gotB == BigInt("DEADBEEF", 16),
          f"same-WAY different-SET refill dropped the store's array write: cached read " +
          f"back 0x$gotB%08x, expected 0xDEADBEEF (0x${expected(addrB + 4, 4)}%08x is the " +
          f"STALE pre-store image -- a match against THAT confirms the dropped write)")
        // And the refill itself still landed correctly (the hold must delay, not drop).
        val gotA = load(dut, cd, addrA(4), Size.LONG)
        assert(gotA == expected(addrA(4), 4),
          f"the held refill must still allocate correctly: got 0x$gotA%08x")
      }
    }
  }

  for (storeCycle <- 0 to 10) {
    test(s"same-WAY different-SET refill must not leave a COPYBACK line dirty with " +
         s"stale data (storeCycle=$storeCycle)", VerilatorTest) {
      compiled.doSim(s"sameWayCb_$storeCycle", 1) { dut =>
        val cd = dut.clockDomain
        cd.forkStimulus(10)
        val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
        dut.probe.logic.loadCmdIn.valid #= false
        dut.probe.logic.storeIn.valid   #= false
        cd.waitSampling(5)
        warmSameWaySetup(dut, cd, mem)

        val arCount = raceSameWay(dut, cd, storeCycle, CacheMode.COPYBACK)
        assert(arCount == 1, s"scenario sanity: expected exactly one racing refill AR, got $arCount")

        // A COPYBACK-hit store resolves ENTIRELY on-chip: the ONLY copy of the stored
        // data is the cache line itself. If the array write was dropped, the line is
        // nonetheless left DIRTY -- `dirtys` is a separate register array indexed by a
        // DIFFERENT set, so it is NOT dropped by the same collision -- holding STALE
        // data, which the eviction machinery would later write back to memory as if it
        // were the store's own result.
        val setB = (SET_B & 0x7F).toInt
        val dirtyWays = (0 until 4).filter(w => dut.dcache.logic.dirtys(w)(setB).toBoolean)
        assert(dirtyWays.nonEmpty, "COPYBACK hit store must have dirtied its line")
        val gotB = load(dut, cd, addrB + 4, Size.LONG)
        assert(gotB == BigInt("DEADBEEF", 16),
          f"COPYBACK line left DIRTY with STALE data (dropped same-way array write): " +
          f"read back 0x$gotB%08x, expected 0xDEADBEEF -- this exact state is what " +
          f"EVICT_WR would later write back to memory as if it were the store's result")
      }
    }
  }
}
