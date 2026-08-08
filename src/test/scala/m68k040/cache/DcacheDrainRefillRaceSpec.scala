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

  /** Result of one `raceSameWay` run: the racing refill's AR count (scenario sanity)
    * plus whether the actual array-write-port collision cycle was ever observed --
    * see `collisionHit`'s doc below. */
  case class RaceResult(arCount: Int, collisionHit: Boolean)

  /** Presents the racing pair on EXACT, independent cycles (two forks) rather than
    * "present A, wait N, present B". The naive form is not offset-independent: at
    * offset 0 the load's `valid` gets deasserted before its first clock edge and the
    * load never happens at all -- silently turning the test into a no-op. */
  def raceSameWay(dut: Dut, cd: ClockDomain, storeCycle: Int,
                  storeMode: SpinalEnumElement[CacheMode.type]): RaceResult = {
    val LOAD_CYCLE = 4
    var arCount = 0
    // I2 (post-P4.4-cleanup review): a coincidence tap proving the sweep actually
    // EXERCISES the same-way collision at some offset, not merely that the final
    // DATA assertions happen to pass at every offset (which would also pass
    // vacuously if the race were never reached at all, e.g. after a future
    // refill-latency or RNG-stream change).
    //
    // NOTE this is deliberately `axi.r.valid` (an R beat PRESENTED), not
    // `axi.r.fire`: `refillWriteHold`'s same-way term
    // (`stS2ArrayWrite && stS2HitVec(victimWay)`) combinationally forces
    // `axi.r.ready` False on any cycle it is True (see DcachePlugin.scala's
    // `axi.r.ready := !refillWriteHold`), so on a CORRECTLY-fixed core
    // `axi.r.fire && stS2ArrayWrite && stS2HitVec(victimWay)` can never be True
    // simultaneously -- that is the entire point of the fix, and asserting on
    // `axi.r.fire` would make this tap permanently (and misleadingly) vacuous.
    // `axi.r.valid` catches the moment the interlock actually had something to
    // hold off: the refill beat was presented WHILE the store's own S2 array
    // write was hitting the exact way (`victimWay`) that refill is about to
    // allocate -- i.e. the real collision cycle this whole regression targets.
    var collisionHit = false
    fork {
      while (true) {
        cd.waitSampling()
        if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1
        if (dut.dcache.logic.axi.r.valid.toBoolean && dut.dcache.logic.stS2ArrayWrite.toBoolean) {
          val vw = dut.dcache.logic.victimWay.toInt
          if (dut.dcache.logic.stS2HitVec(vw).toBoolean) collisionHit = true
        }
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
      // waitSampling(0) is a documented no-op in SpinalHDL sim, so no `if` guard
      // is needed here for the storeCycle=0 case.
      cd.waitSampling(storeCycle)
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
    RaceResult(arCount, collisionHit)
  }

  // I2: aggregated across the whole sweep -- did ANY offset actually hit the real
  // array-write-port collision cycle (`raceSameWay`'s `collisionHit`)? Checked by a
  // final test appended after each sweep (below), so a future refill-latency/RNG
  // change that silently makes every offset miss the race fails LOUDLY instead of
  // all offsets' DATA assertions merely (and vacuously) continuing to pass.
  var wtCollisionHitAny = false
  var cbCollisionHitAny = false

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

        val race = raceSameWay(dut, cd, storeCycle, CacheMode.WRITETHROUGH)
        if (race.collisionHit) wtCollisionHitAny = true

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

  test("I2 scenario sanity: the same-way collision cycle was actually hit at least " +
       "once across the WRITETHROUGH storeCycle sweep") {
    assert(wtCollisionHitAny,
      "NONE of the WRITETHROUGH sweep's offsets ever produced the real array-write-port " +
      "collision cycle (axi.r.valid && stS2ArrayWrite && stS2HitVec(victimWay)) -- the " +
      "sweep above is VACUOUS: its data assertions could pass without ever exercising " +
      "the race this file targets. This likely means refill latency or the storeCycle " +
      "range no longer aligns with the collision window and needs re-tuning.")
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

        val race = raceSameWay(dut, cd, storeCycle, CacheMode.COPYBACK)
        if (race.collisionHit) cbCollisionHitAny = true
        assert(race.arCount == 1, s"scenario sanity: expected exactly one racing refill AR, got ${race.arCount}")

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

  test("I2 scenario sanity: the same-way collision cycle was actually hit at least " +
       "once across the COPYBACK storeCycle sweep") {
    assert(cbCollisionHitAny,
      "NONE of the COPYBACK sweep's offsets ever produced the real array-write-port " +
      "collision cycle (axi.r.valid && stS2ArrayWrite && stS2HitVec(victimWay)) -- the " +
      "sweep above is VACUOUS: its data assertions could pass without ever exercising " +
      "the race this file targets. This likely means refill latency or the storeCycle " +
      "range no longer aligns with the collision window and needs re-tuning.")
  }

  // ------------------------------------------------------------------------------
  // FMax Lever F POSITIVE CONTROL for `storeDrainRefillHold`
  // (design: docs/superpowers/specs/2026-08-08-fmax-leverf-upstream-storequeue-design.md
  //  §10.2 item 3 -- the test that makes correctness properties C1/C2/C3 FALSIFIABLE
  //  rather than merely argued.)
  //
  // Lever F replaces REPLAY's write-allocate-merge gate with the register-only,
  // strictly-stronger `storeDrainRefillHold = stS1Valid || stS2Valid`. Its timing
  // argument rests on that predicate being UNREACHABLE in the real core (the
  // StoreQueue's `drainBusy` interlock plus the ExceptionUnit's `sqDrained` gate mean
  // no store can be in the D-cache store pipe while a COPYBACK drain miss is being
  // serviced) -- which is precisely why the real core can never exercise it, and why
  // the hold's "delay, NEVER drop" behaviour would otherwise go completely untested.
  //
  // `DcacheProbePlugin` drives `storeIn` directly and is therefore the ONLY DUT in
  // this project that CAN violate that producer contract. This sweep does exactly
  // that on purpose (opting in via `storeDrainHoldExpected`, the same shape as
  // `diagFaultExpected`) and asserts all three of:
  //   (a) `storeDrainHoldFired` actually pulses -- the hold is LIVE, not dead code,
  //       and this test is not vacuous (aggregated across the sweep below);
  //   (b) the write-allocate merge is DELAYED, not DROPPED -- the allocated line's
  //       final contents are the correct merge of the store's bytes over the refilled
  //       line (C3, and by extension C1/C2's "the array write always still lands");
  //   (c) the contract-violating second store's OWN array write also lands.
  //
  // Geometry: SET_C is never touched, so a COPYBACK store to it MISSES (-> the
  // write-allocate drain miss) and its victim way is 0 (round-robin counter at reset).
  // SET_D is warmed with a DUMMY line in way 0 first so the real target lands in way
  // 1 -- deliberately NOT way 0, so `refillWriteHold`'s same-WAY term (which this
  // lever does not touch, and which guards a DIFFERENT site, `axi.r.ready`) cannot
  // fire and conflate the two mechanisms. The second store is WRITETHROUGH, not
  // COPYBACK: a COPYBACK HIT acks via `cbHitAckReg`, which is `RegNext(stS2Valid &&
  // ...)` and would therefore land on the EXACT cycle the just-released merge fires
  // its own `storeAllocAckReg`, tripping DcachePlugin's one-ack-per-store assert --
  // an artefact of the deliberate contract violation, not of the lever.
  // ------------------------------------------------------------------------------
  val SET_C   = 40L                       // drain-miss (write-allocate) target set -- COLD
  val SET_D   = 41L                       // contract-violating second store's set -- WARM
  val addrC   = SET_C * 16L               // never loaded => COPYBACK store to it MISSES
  val addrDdm = SET_D * 16L               // dummy, fills SET_D way 0
  val addrD   = SET_D * 16L + 0x800L      // real target, lands in SET_D way 1

  val holdFiredAt = scala.collection.mutable.SortedSet[Int]()

  // NOTE the sweep starts at 1, not 0: at offset 0 the two `storeIn` driver forks act
  // on the SAME simulation cycle (store 1's deassert vs store 2's assert) and their
  // execution order decides whether store 2 is presented at all -- the same
  // offset-independence trap documented above `raceSameWay`. Offset 0 is degenerate
  // for this scenario anyway (the second store retires long before REPLAY is reached,
  // so the hold cannot fire there).
  for (secondCycle <- 1 to 15) {
    test(s"Lever F positive control: a store in S1/S2 must DELAY (never drop) the " +
         s"COPYBACK drain-miss write-allocate merge (secondCycle=$secondCycle)",
         VerilatorTest) {
      compiled.doSim(s"leverFHold_$secondCycle", 1) { dut =>
        val cd = dut.clockDomain
        cd.forkStimulus(10)
        val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
        dut.probe.logic.loadCmdIn.valid #= false
        dut.probe.logic.storeIn.valid   #= false
        dut.dcache.logic.storeDrainHoldExpected #= false
        cd.waitSampling(5)

        preload(mem, addrC, 16)
        preload(mem, addrDdm, 16)
        preload(mem, addrD, 16)
        load(dut, cd, addrDdm, Size.LONG)   // SET_D way 0 (dummy)
        load(dut, cd, addrD, Size.LONG)     // SET_D way 1 (the second store's target)
        cd.waitSampling(4)

        // Opt in to the deliberate producer-contract violation BEFORE it happens --
        // otherwise DcachePlugin's own sim-side assert fires fatally (by design).
        dut.dcache.logic.storeDrainHoldExpected #= true

        var holdFired = false
        var mergeSeen = 0
        fork {
          while (true) {
            cd.waitSampling()
            if (dut.dcache.logic.storeDrainHoldFired.toBoolean) holdFired = true
            if (dut.dcache.logic.storeAllocAckReg.toBoolean) mergeSeen += 1
          }
        }
        // Store 1 (cycle 0): COPYBACK, MISSES SET_C -> pendingStoreMiss -> REFILL
        // (victim way 0 is clean, so no EVICT_WR) -> REPLAY's write-allocate merge.
        fork {
          dut.probe.logic.storeIn.valid #= true
          dut.probe.logic.storeIn.payload.paddr #= addrC + 4
          dut.probe.logic.storeIn.payload.data  #= BigInt("CAFEBABE", 16)
          dut.probe.logic.storeIn.payload.size  #= Size.LONG
          dut.probe.logic.storeIn.payload.useStrb #= false
          dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
          cd.waitSampling()
          dut.probe.logic.storeIn.valid #= false
        }
        // Store 2: the contract violation -- a second store presented while the first
        // is still mid-excursion, swept across the REPLAY window.
        fork {
          cd.waitSampling(secondCycle + 1)
          dut.probe.logic.storeIn.valid #= true
          dut.probe.logic.storeIn.payload.paddr #= addrD + 4
          dut.probe.logic.storeIn.payload.data  #= BigInt("DEADBEEF", 16)
          dut.probe.logic.storeIn.payload.size  #= Size.LONG
          dut.probe.logic.storeIn.payload.useStrb #= false
          dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
          cd.waitSampling()
          dut.probe.logic.storeIn.valid #= false
        }
        cd.waitSampling(160)
        dut.dcache.logic.storeDrainHoldExpected #= false
        if (holdFired) holdFiredAt += secondCycle

        // (b) DELAYED, NOT DROPPED: the merge fired exactly once, and the allocated
        // line holds the store's bytes over the refilled line's own data.
        assert(mergeSeen == 1,
          s"the write-allocate merge must fire EXACTLY once (delay, never drop / never " +
          s"double-ack) -- storeAllocAckReg pulsed $mergeSeen times")
        val gotC = load(dut, cd, addrC + 4, Size.LONG)
        assert(gotC == BigInt("CAFEBABE", 16),
          f"the HELD write-allocate merge was dropped or corrupted: cached read back " +
          f"0x$gotC%08x, expected 0xCAFEBABE (0x${expected(addrC + 4, 4)}%08x would be the " +
          f"un-merged refilled image)")
        val gotCrest = load(dut, cd, addrC + 8, Size.LONG)
        assert(gotCrest == expected(addrC + 8, 4),
          f"the rest of the write-allocated line must be the refilled memory image: " +
          f"got 0x$gotCrest%08x, expected 0x${expected(addrC + 8, 4)}%08x")

        // (c) the contract-violating store's OWN array write landed too.
        val gotD = load(dut, cd, addrD + 4, Size.LONG)
        assert(gotD == BigInt("DEADBEEF", 16),
          f"the second (contract-violating) store's array write was dropped: cached " +
          f"read back 0x$gotD%08x, expected 0xDEADBEEF")
        assert(mem.peekByte(addrD + 4) == 0xDE, "the second store's write-through beat reached memory")
      }
    }
  }

  // (a) NON-VACUITY: at least one offset must have actually asserted the hold. If none
  // did, the sweep above proves nothing about `storeDrainRefillHold` at all -- every
  // assertion in it would pass on a core with the gate deleted outright.
  test("Lever F positive control scenario sanity: storeDrainRefillHold was actually " +
       "asserted at least once across the secondCycle sweep") {
    assert(holdFiredAt.nonEmpty,
      "NONE of the Lever F sweep's offsets ever asserted `storeDrainRefillHold` inside " +
      "REPLAY's write-allocate arm -- the sweep above is VACUOUS: its delay-never-drop " +
      "assertions could all pass on a core with the hold removed entirely. Re-tune the " +
      "secondCycle range against the current refill latency.")
    info(s"storeDrainRefillHold asserted at secondCycle offsets: ${holdFiredAt.mkString(",")}")
  }
}
