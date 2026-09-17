package m68k040.cache

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.ls.BehavioralMemAgent
import m68k040.mmu.{DIdentityTranslationPlugin, MmuControlPlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** D-cache MULTI-HOT fail-safe, its duplicate purge, and the purge's DIRTY carve-out.
  *
  * Every set-associative lookup in this core reduces its per-way match vector twice:
  * a scalar hit (`orR`) and a way SELECT. The select is defined only for a one-hot
  * vector. `ldS1HitWay = OHToUInt(ldS1HitVec)` OR-s the matching bit POSITIONS, so a
  * 2-hot {0,1} selects way 3 -- a way that did not match at all -- and `ldS1Line` then
  * delivers an UNRELATED cache line as load data with nothing raised anywhere.
  *
  * The fail-safe treats a multi-hot match as a MISS (an outcome the miss/REFILL path
  * already handles on every cold access) and leaves the data mux alone, so the
  * wrong-way line is still computed and simply never consumed.
  *
  * ── WHY THE PURGE IS NOT OPTIONAL ───────────────────────────────────────────────
  * Forcing a miss ALONE livelocks on a persistent duplicate: the refill picks a
  * round-robin victim, and if that victim is not one of the duplicates the set now
  * holds THREE matching ways. Every access then misses for ever and the load never
  * completes -- the naive fail-safe would convert silent corruption into a wedged LS
  * pipe, which on hardware looks exactly like "the machine froze with every
  * architectural register intact". Test 2 is the termination proof.
  *
  * ── AND WHY THE PURGE SPARES DIRTY WAYS ─────────────────────────────────────────
  * A hit bit is `cacheable && rdValid(w) && (rdTag(w) === tag)`. If the multi-hot came
  * from a TRANSIENT -- a metastable tag output making a way that does not really match
  * compare equal -- then "clear every match" would drop the valid bit of an unrelated
  * line picked by the glitch, and if that line were DIRTY a read-side glitch would have
  * caused permanent, silent WRITE-side data loss: strictly worse than the corruption
  * being prevented. Test 3 pins that carve-out.
  */
class DcacheMultiHotFailSafeSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val probe  = new DcacheProbePlugin
    val mmuCtrl = new MmuControlPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe, mmuCtrl)) }
  }

  // geometry: 8 KiB, 16-byte lines, 4 ways -> 128 sets. addr[3:0] off, addr[10:4] set,
  // addr[31:11] tag.
  private val Ways = 4
  private def setOf(a: Long): Int  = ((a >> 4) & 0x7f).toInt
  private def tagOf(a: Long): Long = a >> 11

  // Two addresses in the SAME set with DIFFERENT tags.
  private val AddrA = 0x00001000L   // set 0, tag 2
  private val AddrB = 0x00001800L   // set 0, tag 3

  private def memByte(addr: Long): Int = ((addr * 5 + 0x23) & 0xff).toInt
  private def preload(mem: BehavioralMemAgent, base: Long, n: Int): Unit =
    for (i <- 0 until n) mem.pokeByte(base + i, memByte(base + i))
  private def expectedLong(base: Long): BigInt =
    (0 until 4).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  private def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
    dut.probe.logic.loadCmdIn.valid #= false
    dut.probe.logic.loadProbeIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.payload.all #= false
    dut.probe.logic.storeIn.valid #= false
    dut.probe.logic.maintCmdIn.valid #= false
    dut.probe.logic.maintCmdIn.payload.push #= false
    dut.probe.logic.maintCmdIn.payload.invalidate #= false
    dut.probe.logic.maintCmdIn.payload.scope #= 0
    dut.probe.logic.maintCmdIn.payload.sel #= 0
    dut.probe.logic.maintCmdIn.payload.addr #= 0
    cd.waitSampling(4)
    cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)
    (cd, mem)
  }

  private def load(dut: Dut, cd: ClockDomain, addr: Long): BigInt = {
    dut.probe.logic.loadCmdIn.valid #= true
    dut.probe.logic.loadCmdIn.payload.vaddr #= addr
    dut.probe.logic.loadCmdIn.payload.paddr #= addr
    dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
    dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
    dut.probe.logic.loadCmdIn.payload.token #= 0
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean &&
                         dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
    dut.probe.logic.loadRspOut.payload.data.toBigInt
  }

  private def store(dut: Dut, cd: ClockDomain, addr: Long, data: BigInt): Unit = {
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
    cd.waitSampling(16)
  }

  private def wayTag(dut: Dut, w: Int, set: Int): Long =
    dut.dcache.logic.tagMem(w).getBigInt(set).toLong
  private def wayValid(dut: Dut, w: Int, set: Int): Boolean =
    dut.dcache.logic.validsMem(w).getBigInt(set) != 0
  private def wayDirty(dut: Dut, w: Int, set: Int): Boolean =
    dut.dcache.logic.dirtysMem(w).getBigInt(set) != 0
  private def waysMatching(dut: Dut, addr: Long): Seq[Int] = {
    val set = setOf(addr); val tg = tagOf(addr)
    (0 until Ways).filter(w => wayValid(dut, w, set) && wayTag(dut, w, set) == tg)
  }

  /** Plant a persistent duplicate of `AddrA`: fill A and B into two ways of the shared
    * set, then retag B's way to A's tag. Returns the retagged way. */
  private def plantDuplicate(dut: Dut, cd: ClockDomain): Int = {
    val set = setOf(AddrA)
    val wA = waysMatching(dut, AddrA)
    assert(wA.size == 1, s"setup: A occupies ${wA.size} ways, expected 1")
    val wB = waysMatching(dut, AddrB)
    assert(wB.size == 1, s"setup: B occupies ${wB.size} ways, expected 1")
    assert(wA.head != wB.head, "setup: A and B landed in the same way")
    dut.dcache.logic.tagMem(wB.head).setBigInt(set, BigInt(tagOf(AddrA)))
    cd.waitSampling()
    val m = waysMatching(dut, AddrA)
    assert(m.size == 2, s"setup failed: ${m.size} ways match A, expected 2")
    wB.head
  }

  private lazy val compiled = M68kSim().withVerilator.compile(new Dut)

  test("a multi-hot load reports MISS and refills, instead of delivering a wrong way",
       VerilatorTest) {
    compiled.doSim("dc_multihot_failsafe", 1) { dut =>
      val (cd, mem) = initDut(dut)
      preload(mem, AddrA, 16); preload(mem, AddrB, 16)

      // non-vacuity: both lines are resident and read back correctly first
      assert(load(dut, cd, AddrA) == expectedLong(AddrA), "cold load of A was wrong")
      assert(load(dut, cd, AddrB) == expectedLong(AddrB), "cold load of B was wrong")
      assert(load(dut, cd, AddrA) == expectedLong(AddrA), "warm load of A was wrong")

      val retagged = plantDuplicate(dut, cd)
      println(s"[dc-multihot] planted: ways ${waysMatching(dut, AddrA).mkString(",")} " +
              s"now match A (way $retagged retagged)")

      var sawMultiHot = false
      var sawPurge    = false
      val mon = fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.ldS1Valid.toBoolean &&
              dut.dcache.logic.ldS1MultiHot.toBoolean) sawMultiHot = true
          if (dut.dcache.logic.dupPurgeFire.toBoolean) sawPurge = true
        }
      }

      val got = load(dut, cd, AddrA)
      cd.waitSampling(20)
      mon.terminate()

      println(f"[dc-multihot] load after duplicate: got 0x$got%08x expected " +
              f"0x${expectedLong(AddrA)}%08x  multiHot=$sawMultiHot purge=$sawPurge")

      assert(sawMultiHot,
        "the load never saw a multi-hot vector -- the planted duplicate did not reach " +
        "the S1 compare, so this test proves nothing")
      assert(sawPurge, "the duplicate purge never fired on a multi-hot load")
      assert(got == expectedLong(AddrA),
        f"MULTI-HOT DELIVERED WRONG DATA: got 0x$got%08x, expected 0x${expectedLong(AddrA)}%08x. " +
        f"`OHToUInt` on a 2-hot vector selects a way that never matched, and its line is " +
        f"returned as load data with no fault raised anywhere.")
    }
  }

  test("a forced persistent duplicate CONVERGES -- the purge terminates", VerilatorTest) {
    compiled.doSim("dc_multihot_terminates", 1) { dut =>
      val (cd, mem) = initDut(dut)
      preload(mem, AddrA, 16); preload(mem, AddrB, 16)
      load(dut, cd, AddrA); load(dut, cd, AddrB)
      plantDuplicate(dut, cd)

      // The real closed loop: repeat the access that missed. Without the purge this
      // never converges -- the refill's round-robin victim need not be one of the
      // duplicates, so the set can grow to THREE matching ways and every subsequent
      // access misses for ever.
      val MaxIterations = 8
      var iterations = 0
      var matching = waysMatching(dut, AddrA).size
      while (matching > 1 && iterations < MaxIterations) {
        assert(load(dut, cd, AddrA) == expectedLong(AddrA),
          "a load during convergence returned the wrong value")
        cd.waitSampling(20)
        matching = waysMatching(dut, AddrA).size
        iterations += 1
      }
      println(s"[dc-multihot] converged to $matching matching way(s) after " +
              s"$iterations access(es)")

      assert(matching == 1,
        s"LIVELOCK: after $MaxIterations accesses the set still has $matching ways " +
        s"matching A. This is the exact failure mode of forcing a miss WITHOUT the " +
        s"duplicate purge, and on hardware it presents as an LS pipe that stops making " +
        s"progress with every architectural register intact.")
      assert(iterations <= 2,
        s"convergence took $iterations accesses; the purge clears the clean matches in " +
        s"one, so more than two means it is not repairing the duplicate.")
      assert(load(dut, cd, AddrA) == expectedLong(AddrA),
        "after convergence the line still does not read back correctly")
    }
  }

  test("the purge spares a DIRTY matching way -- a transient can never lose a store",
       VerilatorTest) {
    compiled.doSim("dc_multihot_dirty", 1) { dut =>
      val (cd, mem) = initDut(dut)
      preload(mem, AddrA, 16); preload(mem, AddrB, 16)
      load(dut, cd, AddrA); load(dut, cd, AddrB)

      // Dirty A's way with a copyback store: this value exists ONLY in the cache.
      val Committed = BigInt("CAFEBABE", 16)
      store(dut, cd, AddrA, Committed)
      val set = setOf(AddrA)
      val wA = waysMatching(dut, AddrA)
      assert(wA.size == 1 && wayDirty(dut, wA.head, set),
        s"setup: A's way ${wA.mkString} is not dirty after a copyback store")

      val retagged = plantDuplicate(dut, cd)
      assert(!wayDirty(dut, retagged, set),
        "setup: the retagged (duplicate) way should be CLEAN")
      println(s"[dc-multihot] dirty way=${wA.head} clean duplicate way=$retagged")

      // Sample the arrays at the PURGE INSTANT, not at the end of the access. What
      // happens afterwards is the ordinary refill -- which may legitimately choose the
      // (still dirty) way as its victim and WRITE IT BACK, leaving it clean. That is
      // the copyback path doing its job, not data loss, and conflating the two would
      // make this test assert the wrong thing.
      val dirtyWay = wA.head
      var purgeSeen        = false
      var dirtyValidAfter  = true
      var dirtyDirtyAfter  = true
      var cleanValidAfter  = true
      val mon = fork {
        while (!purgeSeen) {
          cd.waitSampling()
          if (dut.dcache.logic.dupPurgeFire.toBoolean) {
            purgeSeen = true
            cd.waitSampling()          // the purge's write has now committed
            dirtyValidAfter = wayValid(dut, dirtyWay, set)
            dirtyDirtyAfter = wayDirty(dut, dirtyWay, set)
            cleanValidAfter = wayValid(dut, retagged, set)
          }
        }
      }

      // Touch it: the fail-safe misses, the purge runs.
      val got = load(dut, cd, AddrA)
      cd.waitSampling(20)
      mon.join()

      val after = waysMatching(dut, AddrA)
      println(f"[dc-multihot] at the purge instant: dirty way $dirtyWay valid=" +
              f"$dirtyValidAfter dirty=$dirtyDirtyAfter; clean way $retagged valid=" +
              f"$cleanValidAfter. After the access: matching ways ${after.mkString(",")}, " +
              f"load returned 0x$got%08x (committed 0x$Committed%08x)")

      assert(purgeSeen, "the duplicate purge never fired")

      // THE CARVE-OUT, observed directly at the write.
      assert(dirtyValidAfter && dirtyDirtyAfter,
        s"THE PURGE DISCARDED A DIRTY WAY (valid=$dirtyValidAfter dirty=$dirtyDirtyAfter). " +
        s"A committed store lived only in that line. A hit bit is a compare against a " +
        s"tag-array output, so a TRANSIENT can make a way that does not really match " +
        s"appear to -- and clearing it would turn a read-side glitch into permanent, " +
        s"silent WRITE-side data loss, strictly worse than the corruption this fail-safe " +
        s"exists to prevent.")
      assert(!cleanValidAfter,
        s"the CLEAN duplicate way $retagged survived the purge -- it must be cleared, or " +
        s"the duplicate is never repaired and the forced miss livelocks")

      // ...and the committed value is still there, whether it stayed in the dirty line
      // or was written back by the refill's eviction.
      assert(after.size == 1,
        s"expected exactly one matching way after the access, got ${after.mkString(",")}")
      assert(got == Committed,
        f"the committed store 0x$Committed%08x was not returned by the load that " +
        f"triggered the purge; got 0x$got%08x")
      assert(load(dut, cd, AddrA) == Committed,
        f"the committed store 0x$Committed%08x did not survive the purge")
    }
  }
}
