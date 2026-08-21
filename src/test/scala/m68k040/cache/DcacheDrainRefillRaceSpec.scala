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
    val dcache = new DcachePlugin()
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
    dut.probe.logic.loadCmdIn.payload.token #= 0
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean && dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
    dut.probe.logic.loadRspOut.payload.data.toBigInt
  }

  def fireCopyback(dut: Dut, cd: ClockDomain, paddr: Long, data: BigInt): Unit = {
    dut.probe.logic.storeIn.valid #= true
    dut.probe.logic.storeIn.payload.paddr #= paddr
    dut.probe.logic.storeIn.payload.data #= data
    dut.probe.logic.storeIn.payload.size #= Size.LONG
    dut.probe.logic.storeIn.payload.useStrb #= false
    dut.probe.logic.storeIn.payload.strb #= 0
    dut.probe.logic.storeIn.payload.lineData #= 0
    dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
    dut.probe.logic.storeIn.payload.precise #= false
    cd.waitSamplingWhere(dut.probe.logic.storeIn.valid.toBoolean &&
      dut.probe.logic.storeIn.ready.toBoolean)
    dut.probe.logic.storeIn.valid #= false
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
      // addrK(0) (X, way 0) -- empirically the exact offset
      // (Task P4.4 Step 1) that lands the store's S1 read-launch on the SAME cycle
      // the refill's AXI R response writes way 0 with the NEW line (addrK(4) = Z).
      fork {
        dut.probe.logic.loadCmdIn.valid #= true
        dut.probe.logic.loadCmdIn.payload.vaddr #= addrK(4)
        dut.probe.logic.loadCmdIn.payload.paddr #= addrK(4)
        dut.probe.logic.loadCmdIn.payload.size  #= Size.LONG
        dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        dut.probe.logic.loadCmdIn.payload.token #= 0
        dut.clockDomain.waitSamplingWhere(
          dut.probe.logic.loadCmdIn.valid.toBoolean && dut.probe.logic.loadCmdIn.ready.toBoolean)
        dut.probe.logic.loadCmdIn.valid #= false
      }
      fork {
        dut.clockDomain.waitSampling(1) // the empirically-confirmed racing offset
        dut.probe.logic.storeIn.valid #= true
        dut.probe.logic.storeIn.payload.paddr #= addrK(0)
        dut.probe.logic.storeIn.payload.data  #= BigInt("DEADBEEF", 16)
        dut.probe.logic.storeIn.payload.size  #= Size.LONG
        dut.probe.logic.storeIn.payload.useStrb #= false
        dut.probe.logic.storeIn.payload.strb #= 0
        dut.probe.logic.storeIn.payload.lineData #= 0
        dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        dut.probe.logic.storeIn.payload.precise #= false
        dut.clockDomain.waitSamplingWhere(
          dut.probe.logic.storeIn.valid.toBoolean && dut.probe.logic.storeIn.ready.toBoolean)
        dut.probe.logic.storeIn.valid #= false
      }
      dut.clockDomain.waitSampling(30)
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

  /** Result of one `raceSameWay` run: the racing refill's AR count (scenario sanity)
    * plus whether the new load-miss barrier actually parked the store before the
    * formerly-dangerous array-write window. */
  case class RaceResult(arCount: Int, barrierHit: Boolean)

  /** Presents the racing pair on EXACT, independent cycles (two forks) rather than
    * "present A, wait N, present B". The naive form is not offset-independent: at
    * offset 0 the load's `valid` gets deasserted before its first clock edge and the
    * load never happens at all -- silently turning the test into a no-op. */
  def raceSameWay(dut: Dut, cd: ClockDomain, storeCycle: Int,
                  storeMode: SpinalEnumElement[CacheMode.type]): RaceResult = {
    val LOAD_CYCLE = 4
    var arCount = 0
    // The new dirty-victim repair is stronger than the historical refill-write
    // coincidence interlock: a load miss freezes a waiting store-S1 before that
    // store can become an S3 writer at all. Therefore an `r.valid && S3-write`
    // witness is now structurally unreachable in this one-store sweep. Prove the
    // replacement mechanism was exercised instead of retaining a stale/vacuous
    // collision counter.
    var barrierHit = false
    fork {
      while (true) {
        cd.waitSampling()
        if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1
        if (dut.dcache.logic.loadMissStoreBarrier.toBoolean &&
            dut.dcache.logic.stS1Valid.toBoolean) barrierHit = true
      }
    }
    fork {
      cd.waitSampling(LOAD_CYCLE)
      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= addrA(4)
      dut.probe.logic.loadCmdIn.payload.paddr #= addrA(4)
      dut.probe.logic.loadCmdIn.payload.size  #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadCmdIn.payload.token #= 0
      cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.valid.toBoolean &&
        dut.probe.logic.loadCmdIn.ready.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= false
    }
    fork {
      // waitSampling(0) is a documented no-op in SpinalHDL sim, so no `if` guard
      // is needed here for the storeCycle=0 case.
      cd.waitSampling(storeCycle)
      dut.probe.logic.storeIn.valid #= true
      dut.probe.logic.storeIn.payload.paddr #= addrB + 4
      dut.probe.logic.storeIn.payload.data  #= BigInt("DEADBEEF", 16)
      dut.probe.logic.storeIn.payload.size  #= Size.LONG
      dut.probe.logic.storeIn.payload.useStrb #= false
      dut.probe.logic.storeIn.payload.strb #= 0
      dut.probe.logic.storeIn.payload.lineData #= 0
      dut.probe.logic.storeIn.payload.cacheMode #= storeMode
      dut.probe.logic.storeIn.payload.precise #= false
      cd.waitSamplingWhere(dut.probe.logic.storeIn.valid.toBoolean &&
        dut.probe.logic.storeIn.ready.toBoolean)
      dut.probe.logic.storeIn.valid #= false
    }
    cd.waitSampling(80)
    RaceResult(arCount, barrierHit)
  }

  // I2: aggregated across the whole sweep -- did ANY offset actually hit the real
  // array-write-port collision cycle (`raceSameWay`'s `collisionHit`)? Checked by a
  // final test appended after each sweep (below), so a future refill-latency/RNG
  // change that silently makes every offset miss the race fails LOUDLY instead of
  // all offsets' DATA assertions merely (and vacuously) continuing to pass.
  var wtBarrierHitAny = false
  var cbBarrierHitAny = false

  // The result/write boundary moved from S2 to S3 in the II=1 pipeline. Keep a
  // deliberately wider window than the historical 0..10 sweep so the non-vacuity
  // witness follows that registered cycle instead of silently missing it.
  for (storeCycle <- 0 to 18) {
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

        val race = raceSameWay(dut, cd, storeCycle, CacheMode.WRITETHROUGH)
        if (race.barrierHit) wtBarrierHitAny = true

        // Scenario sanity: the racing refill must actually have happened (otherwise
        // the assertions below would be vacuously satisfied).
        assert(race.arCount == 1, s"scenario sanity: expected exactly one racing refill AR, got ${race.arCount}")
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

  test("I2 scenario sanity: the load-miss barrier parked a WRITETHROUGH store in S1 " +
       "for at least one sweep offset", VerilatorTest) {
    assert(wtBarrierHitAny,
      "NONE of the WRITETHROUGH sweep offsets exercised loadMissStoreBarrier with a " +
      "resident store-S1; the data checks above would not prove the repaired race")
  }

  for (storeCycle <- 0 to 18) {
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

        val race = raceSameWay(dut, cd, storeCycle, CacheMode.COPYBACK)
        if (race.barrierHit) cbBarrierHitAny = true
        assert(race.arCount == 1, s"scenario sanity: expected exactly one racing refill AR, got ${race.arCount}")

        // A COPYBACK-hit store resolves ENTIRELY on-chip: the ONLY copy of the stored
        // data is the cache line itself. If the array write was dropped, the line is
        // nonetheless left DIRTY -- `dirtys` is a separate register array indexed by a
        // DIFFERENT set, so it is NOT dropped by the same collision -- holding STALE
        // data, which the eviction machinery would later write back to memory as if it
        // were the store's own result.
        val setB = (SET_B & 0x7F).toInt
        val dirtyWays = (0 until 4).filter(w => dut.dcache.logic.dirtysMem(w).getBigInt(setB) != 0)
        assert(dirtyWays.nonEmpty, "COPYBACK hit store must have dirtied its line")
        val gotB = load(dut, cd, addrB + 4, Size.LONG)
        assert(gotB == BigInt("DEADBEEF", 16),
          f"COPYBACK line left DIRTY with STALE data (dropped same-way array write): " +
          f"read back 0x$gotB%08x, expected 0xDEADBEEF -- this exact state is what " +
          f"EVICT_WR would later write back to memory as if it were the store's result")
      }
    }
  }

  test("I2 scenario sanity: the load-miss barrier parked a COPYBACK store in S1 " +
       "for at least one sweep offset", VerilatorTest) {
    assert(cbBarrierHitAny,
      "NONE of the COPYBACK sweep offsets exercised loadMissStoreBarrier with a " +
      "resident store-S1; the data checks above would not prove the repaired race")
  }

  // The old Lever-F positive control deliberately violated a Flow producer
  // contract.  The production boundary is now a Stream, so the meaningful hazard
  // is a younger descriptor already accepted into S1 when an older S2 lookup
  // discovers a COPYBACK miss.  In particular, a same-set younger S1 must not hold
  // the older refill forever: it parks without driving the read/hold predicate,
  // then relaunches after the allocation ack.
  test("same-set younger COPYBACK hit parks behind a drain miss and resumes exactly once",
       VerilatorTest) {
    compiled.doSim("sameSetMissBarrier", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      dut.probe.logic.loadCmdIn.valid #= false
      dut.probe.logic.storeIn.valid   #= false
      val set = 40L
      val missAddr = set * 16L
      val hitAddr  = missAddr + 0x800L // same set, different tag
      preload(mem, missAddr, 16)
      preload(mem, hitAddr, 16)
      load(dut, cd, hitAddr, Size.LONG)
      cd.waitSampling(3)

      def fireStore(addr: Long, data: BigInt): Unit = {
        dut.probe.logic.storeIn.valid #= true
        dut.probe.logic.storeIn.payload.paddr #= addr
        dut.probe.logic.storeIn.payload.data #= data
        dut.probe.logic.storeIn.payload.size #= Size.LONG
        dut.probe.logic.storeIn.payload.useStrb #= false
        dut.probe.logic.storeIn.payload.strb #= 0
        dut.probe.logic.storeIn.payload.lineData #= 0
        dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
        dut.probe.logic.storeIn.payload.precise #= false
        cd.waitSamplingWhere(dut.probe.logic.storeIn.valid.toBoolean &&
          dut.probe.logic.storeIn.ready.toBoolean)
        dut.probe.logic.storeIn.valid #= false
      }

      fireStore(missAddr + 4, BigInt("CAFEBABE", 16))
      fireStore(hitAddr + 4, BigInt("DEADBEEF", 16))

      var barrierWithParkedS1 = false
      var allocAcks = 0
      var totalAcks = 0
      var cycles = 0
      while (totalAcks < 2 && cycles < 300) {
        cd.waitSampling()
        if (dut.dcache.logic.storeMissBarrier.toBoolean &&
            dut.dcache.logic.stS1Valid.toBoolean) barrierWithParkedS1 = true
        if (dut.dcache.logic.storeAllocAckReg.toBoolean) allocAcks += 1
        if (dut.dcache.logic.storeAckReg.toBoolean) totalAcks += 1
        cycles += 1
      }
      assert(barrierWithParkedS1,
        "test never placed a same-set younger descriptor behind the live miss barrier")
      assert(allocAcks == 1, s"older miss allocated/acked $allocAcks times")
      assert(totalAcks == 2, s"expected two ordered terminal acks, saw $totalAcks")
      assert(load(dut, cd, missAddr + 4, Size.LONG) == BigInt("CAFEBABE", 16),
        "older COPYBACK miss merge was lost")
      assert(load(dut, cd, hitAddr + 4, Size.LONG) == BigInt("DEADBEEF", 16),
        "parked younger same-set hit was lost, duplicated, or merged from stale data")
    }
  }

  // A load miss snapshots the chosen dirty victim before EVICT_WR starts. If an
  // older COPYBACK hit is waiting in store-S1 on that exact decision cycle, letting
  // it advance would write/ack the new bytes after the snapshot; EVICT_WR would then
  // write the old snapshot and REFILL would replace the line, silently losing the
  // store. The fix parks S1 until load replay, then naturally re-resolves it (as a
  // store miss after the victim was evicted). This test checks final data, not merely
  // the presence of a barrier pulse, and proves the exact race was reached.
  test("load dirty-victim snapshot parks an older store-S1 until refill completes",
       VerilatorTest) {
    compiled.doSim("loadVictimStoreS1", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      dut.probe.logic.loadCmdIn.valid #= false
      dut.probe.logic.storeIn.valid   #= false
      cd.waitSampling(5)

      val set = 52L
      def line(k: Long): Long = set * 16L + k * 0x800L
      for (k <- 0L until 5L) preload(mem, line(k), 16)
      for (k <- 0L until 4L) load(dut, cd, line(k), Size.LONG)

      def fireCopyback(addr: Long, data: BigInt): Unit = {
        dut.probe.logic.storeIn.valid #= true
        dut.probe.logic.storeIn.payload.paddr #= addr
        dut.probe.logic.storeIn.payload.data #= data
        dut.probe.logic.storeIn.payload.size #= Size.LONG
        dut.probe.logic.storeIn.payload.useStrb #= false
        dut.probe.logic.storeIn.payload.strb #= 0
        dut.probe.logic.storeIn.payload.lineData #= 0
        dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
        dut.probe.logic.storeIn.payload.precise #= false
        cd.waitSamplingWhere(dut.probe.logic.storeIn.valid.toBoolean &&
          dut.probe.logic.storeIn.ready.toBoolean)
        dut.probe.logic.storeIn.valid #= false
      }

      // Make way 0 dirty first, so the racing load miss really takes EVICT_WR.
      fireCopyback(line(0), BigInt("A5A55A5A", 16))
      cd.waitSamplingWhere(dut.dcache.logic.storeAckReg.toBoolean)

      var sawDecisionWithS1 = false
      var sawHeldS1 = false
      var measuredAcks = 0
      var refillArs = 0
      var victimAws = 0
      var monitor = true
      fork {
        while (monitor) {
          cd.waitSampling()
          if (dut.dcache.logic.loadMissDiscovered.toBoolean &&
              dut.dcache.logic.stS1Valid.toBoolean) sawDecisionWithS1 = true
          if (dut.dcache.logic.loadMissStoreBarrier.toBoolean &&
              dut.dcache.logic.stS1Valid.toBoolean) sawHeldS1 = true
          if (dut.dcache.logic.storeAckReg.toBoolean) measuredAcks += 1
          if (dut.dcache.logic.axi.ar.valid.toBoolean &&
              dut.dcache.logic.axi.ar.ready.toBoolean) refillArs += 1
          if (dut.dcache.logic.axi.aw.valid.toBoolean &&
              dut.dcache.logic.axi.aw.ready.toBoolean &&
              ((dut.dcache.logic.axi.aw.payload.addr.toLong & ~0xfL) == line(0)))
            victimAws += 1
        }
      }

      // Put the older store in S0, then launch the conflicting load read while S0
      // advances to S1. On the next cycle the load miss chooses way 0 while the
      // store is exactly at the dangerous pre-write stage.
      fireCopyback(line(0) + 4, BigInt("DEADBEEF", 16))
      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= line(4)
      dut.probe.logic.loadCmdIn.payload.paddr #= line(4)
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadCmdIn.payload.token #= 7
      cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.valid.toBoolean &&
        dut.probe.logic.loadCmdIn.ready.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
      assert(dut.probe.logic.loadRspOut.payload.data.toBigInt == expected(line(4), 4),
        "racing load refill returned the wrong target line")

      var waitAck = 0
      while (measuredAcks < 1 && waitAck < 300) { cd.waitSampling(); waitAck += 1 }
      monitor = false
      cd.waitSampling()
      assert(sawDecisionWithS1,
        "test never aligned the dirty-victim decision with an older store in S1")
      assert(sawHeldS1,
        "store-S1 was not retained throughout the load dirty-victim refill")
      assert(measuredAcks == 1,
        s"racing store must terminate exactly once, saw $measuredAcks acks")
      assert(refillArs == 2,
        s"expected one load refill plus one replayed store write-allocate, saw $refillArs ARs")
      assert(victimAws == 1,
        s"the dirty victim must be written back exactly once, saw $victimAws eviction AWs")
      val evictedWord = (0 until 4).foldLeft(BigInt(0)) { (acc, i) =>
        (acc << 8) | BigInt(mem.peekByte(line(0) + i))
      }
      assert(evictedWord == BigInt("A5A55A5A", 16),
        f"dirty eviction wrote a pre-store victim image: memory=0x$evictedWord%08x")

      assert(load(dut, cd, line(0), Size.LONG) == BigInt("A5A55A5A", 16),
        "the first dirty bytes were lost across victim eviction/reload")
      assert(load(dut, cd, line(0) + 4, Size.LONG) == BigInt("DEADBEEF", 16),
        "older store-S1 was acked but lost after the load replaced its victim line")
    }
  }

  test("load dirty-victim snapshot forwards the same-cycle S3 store result",
       VerilatorTest) {
    compiled.doSim("loadVictimFromS3", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      dut.probe.logic.loadCmdIn.valid #= false
      dut.probe.logic.storeIn.valid #= false
      cd.waitSampling(5)

      val set = 53L
      def line(k: Long): Long = set * 16L + k * 0x800L
      for (k <- 0L until 5L) preload(mem, line(k), 16)
      for (k <- 0L until 4L) load(dut, cd, line(k), Size.LONG)

      // Establish a dirty victim, then measure a second hit which reaches S3 on the
      // exact cycle the fifth-tag load snapshots way 0.
      fireCopyback(dut, cd, line(0), BigInt("A5A55A5A", 16))
      cd.waitSamplingWhere(dut.dcache.logic.storeAckReg.toBoolean)
      cd.waitSampling(2)

      var sawForward = false
      var storeAcks = 0
      var refillArs = 0
      var victimAws = 0
      var monitor = true
      var stageTrace = Vector.empty[String]
      fork {
        while (monitor) {
          cd.waitSampling()
          stageTrace :+= s"s0=${dut.dcache.logic.s0Valid.toBoolean} " +
            s"s1=${dut.dcache.logic.stS1Valid.toBoolean} " +
            s"s2=${dut.dcache.logic.stS2Valid.toBoolean} " +
            s"s3=${dut.dcache.logic.stS3Valid.toBoolean} " +
            s"ld1=${dut.dcache.logic.ldS1Valid.toBoolean} " +
            s"lm=${dut.dcache.logic.loadMissDiscovered.toBoolean} " +
            s"fwd=${dut.dcache.logic.loadVictimFromS3Dbg.toBoolean}"
          if (dut.dcache.logic.loadVictimFromS3Dbg.toBoolean) sawForward = true
          if (dut.dcache.logic.storeAckReg.toBoolean) storeAcks += 1
          if (dut.dcache.logic.axi.ar.valid.toBoolean &&
              dut.dcache.logic.axi.ar.ready.toBoolean) refillArs += 1
          if (dut.dcache.logic.axi.aw.valid.toBoolean &&
              dut.dcache.logic.axi.aw.ready.toBoolean &&
              ((dut.dcache.logic.axi.aw.payload.addr.toLong & ~0xfL) == line(0)))
            victimAws += 1
        }
      }

      fireCopyback(dut, cd, line(0) + 4, BigInt("DEADBEEF", 16))
      // `waitSamplingWhere` returns after the matching sampling edge; starting the
      // load while the store is in S1 makes its registered miss decision coincide
      // with that store's later S3 write.
      cd.waitSamplingWhere(dut.dcache.logic.stS1Valid.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= line(4)
      dut.probe.logic.loadCmdIn.payload.paddr #= line(4)
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadCmdIn.payload.token #= 8
      cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.valid.toBoolean &&
        dut.probe.logic.loadCmdIn.ready.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
      assert(dut.probe.logic.loadRspOut.payload.data.toBigInt == expected(line(4), 4),
        "racing target load returned corrupt refill data")
      cd.waitSampling(8)
      monitor = false
      cd.waitSampling()

      assert(sawForward,
        s"test never exercised the load victimFromS3 mux; trace=${stageTrace.mkString(" | ")}")
      assert(storeAcks == 1, s"racing S3 store acknowledged $storeAcks times")
      assert(refillArs == 1, s"target load issued $refillArs refill reads")
      assert(victimAws == 1, s"dirty victim issued $victimAws eviction writes")
      val evicted = (0 until 4).foldLeft(BigInt(0)) { (acc, i) =>
        (acc << 8) | BigInt(mem.peekByte(line(0) + 4 + i))
      }
      assert(evicted == BigInt("DEADBEEF", 16),
        f"load victim snapshot lost the same-cycle S3 update: 0x$evicted%08x")
    }
  }

  test("load dirty-victim read launch forwards the prior-cycle S3 store result",
       VerilatorTest) {
    compiled.doSim("loadVictimFromS3Launch", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      dut.probe.logic.loadCmdIn.valid #= false
      dut.probe.logic.storeIn.valid #= false
      cd.waitSampling(5)

      val set = 55L
      def line(k: Long): Long = set * 16L + k * 0x800L
      for (k <- 0L until 5L) preload(mem, line(k), 16)
      for (k <- 0L until 4L) load(dut, cd, line(k), Size.LONG)

      var sawLaunchCollision = false
      var sawForward = false
      var storeAcks = 0
      var refillArs = 0
      var victimAws = 0
      var monitor = true
      fork {
        while (monitor) {
          cd.waitSampling()
          if (dut.dcache.logic.loadMissDiscovered.toBoolean &&
              dut.dcache.logic.stS3WriteD1.toBoolean) sawLaunchCollision = true
          if (dut.dcache.logic.loadVictimFromS3Dbg.toBoolean) sawForward = true
          if (dut.dcache.logic.storeAckReg.toBoolean) storeAcks += 1
          if (dut.dcache.logic.axi.ar.valid.toBoolean &&
              dut.dcache.logic.axi.ar.ready.toBoolean) refillArs += 1
          if (dut.dcache.logic.axi.aw.valid.toBoolean &&
              dut.dcache.logic.axi.aw.ready.toBoolean &&
              ((dut.dcache.logic.axi.aw.payload.addr.toLong & ~0xfL) == line(0)))
            victimAws += 1
        }
      }

      // This is the Q700 boot signature: a LONG at +2 within a resident line.
      // Present the fifth-tag load while the store is in S2. On the acceptance
      // cycle the store advances to S3 and writes the exact set/way whose sync
      // victim read the load launches. The miss decision is one cycle later,
      // after the live S3 bypass has disappeared.
      fireCopyback(dut, cd, line(0) + 2, BigInt("000088B0", 16))
      cd.waitSamplingWhere(dut.dcache.logic.stS2Valid.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= line(4)
      dut.probe.logic.loadCmdIn.payload.paddr #= line(4)
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadCmdIn.payload.token #= 9
      cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.valid.toBoolean &&
        dut.probe.logic.loadCmdIn.ready.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
      assert(dut.probe.logic.loadRspOut.payload.data.toBigInt == expected(line(4), 4),
        "racing target load returned corrupt refill data")
      cd.waitSampling(8)
      monitor = false
      cd.waitSampling()

      assert(sawLaunchCollision,
        "test never aligned the victim read launch with the preceding S3 write")
      assert(sawForward,
        "the one-cycle S3 write snapshot did not bypass the stale victim RAM read")
      assert(storeAcks == 1, s"racing S3 store acknowledged $storeAcks times")
      assert(refillArs == 1, s"target load issued $refillArs refill reads")
      assert(victimAws == 1, s"newly dirtied victim issued $victimAws eviction writes")
      val evicted = (0 until 4).foldLeft(BigInt(0)) { (acc, i) =>
        (acc << 8) | BigInt(mem.peekByte(line(0) + 2 + i))
      }
      assert(evicted == BigInt("000088B0", 16),
        f"launch-cycle collision lost the unaligned link store: 0x$evicted%08x")
      assert(load(dut, cd, line(0) + 2, Size.LONG) == BigInt("000088B0", 16),
        "unaligned link store was lost across dirty victim eviction/reload")
    }
  }

  test("COPYBACK miss victim snapshot forwards the preceding S3 store result",
       VerilatorTest) {
    compiled.doSim("storeVictimFromS3", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      dut.probe.logic.loadCmdIn.valid #= false
      dut.probe.logic.storeIn.valid #= false
      cd.waitSampling(5)

      val set = 54L
      def line(k: Long): Long = set * 16L + k * 0x800L
      for (k <- 0L until 5L) preload(mem, line(k), 16)
      for (k <- 0L until 4L) load(dut, cd, line(k), Size.LONG)

      var sawForward = false
      var storeAcks = 0
      var refillArs = 0
      var victimAws = 0
      var monitor = true
      fork {
        while (monitor) {
          cd.waitSampling()
          if (dut.dcache.logic.storeVictimFromS3Dbg.toBoolean) sawForward = true
          if (dut.dcache.logic.storeAckReg.toBoolean) storeAcks += 1
          if (dut.dcache.logic.axi.ar.valid.toBoolean &&
              dut.dcache.logic.axi.ar.ready.toBoolean) refillArs += 1
          if (dut.dcache.logic.axi.aw.valid.toBoolean &&
              dut.dcache.logic.axi.aw.ready.toBoolean &&
              ((dut.dcache.logic.axi.aw.payload.addr.toLong & ~0xfL) == line(0)))
            victimAws += 1
        }
      }

      // Consecutive accepts align the older resident hit in S3 with the younger
      // fifth-tag miss in S2. Way 0 was clean before this pair, so the younger miss
      // can know it is dirty only through pVictimFromS3.
      fireCopyback(dut, cd, line(0) + 4, BigInt("CAFEBABE", 16))
      fireCopyback(dut, cd, line(4) + 4, BigInt("11223344", 16))
      var waitAcks = 0
      while (storeAcks < 2 && waitAcks < 300) { cd.waitSampling(); waitAcks += 1 }
      cd.waitSampling(6)
      monitor = false
      cd.waitSampling()

      assert(sawForward,
        "test never exercised the store-miss pVictimFromS3 mux")
      assert(storeAcks == 2, s"expected two ordered store acks, saw $storeAcks")
      assert(refillArs == 1, s"younger COPYBACK miss issued $refillArs refill reads")
      assert(victimAws == 1, s"newly dirtied victim issued $victimAws eviction writes")
      val evicted = (0 until 4).foldLeft(BigInt(0)) { (acc, i) =>
        (acc << 8) | BigInt(mem.peekByte(line(0) + 4 + i))
      }
      assert(evicted == BigInt("CAFEBABE", 16),
        f"store-miss victim snapshot lost the preceding S3 update: 0x$evicted%08x")
      assert(load(dut, cd, line(4) + 4, Size.LONG) == BigInt("11223344", 16),
        "younger COPYBACK miss did not merge into its allocated line")
    }
  }

  test("COPYBACK miss victim read launch forwards the prior-cycle S3 store result",
       VerilatorTest) {
    compiled.doSim("storeVictimFromS3Launch", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      dut.probe.logic.loadCmdIn.valid #= false
      dut.probe.logic.storeIn.valid #= false
      cd.waitSampling(5)

      val set = 56L
      def line(k: Long): Long = set * 16L + k * 0x800L
      for (k <- 0L until 5L) preload(mem, line(k), 16)
      for (k <- 0L until 4L) load(dut, cd, line(k), Size.LONG)

      var sawLaunchCollision = false
      var sawForward = false
      var storeAcks = 0
      var refillArs = 0
      var victimAws = 0
      var monitor = true
      fork {
        while (monitor) {
          cd.waitSampling()
          if (dut.dcache.logic.storeMissDiscovered.toBoolean &&
              dut.dcache.logic.stS3WriteD1.toBoolean) sawLaunchCollision = true
          if (dut.dcache.logic.storeVictimFromS3Dbg.toBoolean) sawForward = true
          if (dut.dcache.logic.storeAckReg.toBoolean) storeAcks += 1
          if (dut.dcache.logic.axi.ar.valid.toBoolean &&
              dut.dcache.logic.axi.ar.ready.toBoolean) refillArs += 1
          if (dut.dcache.logic.axi.aw.valid.toBoolean &&
              dut.dcache.logic.axi.aw.ready.toBoolean &&
              ((dut.dcache.logic.axi.aw.payload.addr.toLong & ~0xfL) == line(0)))
            victimAws += 1
        }
      }

      fireCopyback(dut, cd, line(0) + 2, BigInt("000088B0", 16))
      // One bubble relative to the existing resolution-cycle test shifts the
      // younger store's sync victim-read launch onto the older store's S3 write.
      cd.waitSampling(1)
      fireCopyback(dut, cd, line(4) + 4, BigInt("11223344", 16))
      var waitAcks = 0
      while (storeAcks < 2 && waitAcks < 300) { cd.waitSampling(); waitAcks += 1 }
      cd.waitSampling(6)
      monitor = false
      cd.waitSampling()

      assert(sawLaunchCollision,
        "test never aligned the store-miss victim read launch with the prior S3 write")
      assert(sawForward,
        "store-miss victim capture did not use the one-cycle S3 write snapshot")
      assert(storeAcks == 2, s"expected two ordered store acks, saw $storeAcks")
      assert(refillArs == 1, s"younger COPYBACK miss issued $refillArs refill reads")
      assert(victimAws == 1, s"newly dirtied victim issued $victimAws eviction writes")
      val evicted = (0 until 4).foldLeft(BigInt(0)) { (acc, i) =>
        (acc << 8) | BigInt(mem.peekByte(line(0) + 2 + i))
      }
      assert(evicted == BigInt("000088B0", 16),
        f"store-miss launch collision lost the unaligned link store: 0x$evicted%08x")
      assert(load(dut, cd, line(4) + 4, Size.LONG) == BigInt("11223344", 16),
        "younger COPYBACK miss did not merge into its allocated line")
    }
  }
}
