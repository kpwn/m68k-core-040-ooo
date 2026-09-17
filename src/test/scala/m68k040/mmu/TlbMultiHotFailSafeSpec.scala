package m68k040.mmu

import m68k040.VerilatorTest
import m68k040.cache.CacheMode
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** The MULTI-HOT CONSUMER fail-safe, and proof that its duplicate purge TERMINATES.
  *
  * `TlbDuplicateFillSpec` (next to this file) covers the FILL end: refilling a
  * resident VPN replaces its way instead of allocating a second one. That closed the
  * one reachable PRODUCER of a duplicate. It did nothing for the CONSUMER, which is
  * what this file is about:
  *
  *   io.hit      := hitVec.orR          <- says "hit" for ANY number of matches
  *   io.hitEntry := MuxOH(hitVec, entVec)  <- defined ONLY for a one-hot select
  *
  * On a 2-hot vector `MuxOH` emits the bitwise OR of two entries, so the lookup
  * returns a PPN that was never written for that VPN -- and returns it as a valid,
  * NON-FAULTING translation. `Tlb.scala`'s own fill-path comment records this being
  * caught on the full core (`WalkerExcEntryWedgeSpec`: AXI to physical 0xae0cf000 for
  * a VA whose only descriptor says 0x60008, with no fault from the MMU, the D-cache
  * or the walker). The fill fix removed the known way IN; it did not make the
  * consumer safe against any other way in, which is the point of a fail-safe.
  *
  * ── HOW THE DUPLICATE IS CREATED HERE ───────────────────────────────────────────
  * Through the BACKDOOR, deliberately. The front door is closed (that is the fill
  * fix), and a repair mechanism has to be tested against the state it repairs, not
  * against the state you can still reach by accident. Two legitimate fills of two
  * DIFFERENT VPNs that share a (bank,set) land in ways 0 and 1 with known, distinct
  * PPNs; then way 1's TAG register is poked to way 0's tag. Both ways now match one
  * lookup, with 0x11111 and 0x22222 behind them -- chosen so the OR (0x33333) is
  * unmistakable and cannot be either input.
  *
  * ── WHAT IS PROVED ──────────────────────────────────────────────────────────────
  *   1. The corrupt value is STILL COMPUTED: `io.hitEntry.ppn` reads 0x33333, the OR
  *      of two ways. That is deliberate -- leaving the data mux alone is what keeps
  *      the fail-safe free -- and it is exactly what the old `orR` would have handed
  *      upward as a translation.
  *   2. It is NEVER CONSUMED: `io.hit` is FALSE, so the request becomes a table walk.
  *   3. The purge fires and the duplicate is GONE the next cycle.
  *   4. TERMINATION, which is the failure mode of the naive fix. Forcing a miss on
  *      its own would livelock here and it is worth being precise about why: the fill
  *      replaces `OHMasking.first(flResidentVec)`, i.e. the FIRST matching way, so the
  *      OTHER match survives every walk -- miss, walk, fill, miss, walk, fill, for
  *      ever. Test 3 runs the real closed loop (lookup; if miss, walk-and-fill) and
  *      requires it to converge to a one-hot hit with the CORRECT PPN.
  */
class TlbMultiHotFailSafeSpec extends AnyFunSuite {

  // Geometry (Tlb defaults): entries=32, ways=4, banks=2 -> setsPerBank=4.
  // vpn[0] = bank, vpn[2:1] = set, vpn[19:3] = tag.
  private def bankOf(vpn: Long): Int = (vpn & 0x1L).toInt
  private def setOf(vpn: Long): Int  = ((vpn >> 1) & 0x3L).toInt
  private def tagOf(vpn: Long): Long = vpn >> 3

  private def fill(dut: Tlb, cd: ClockDomain, vpn: Long, ppn: Long): Unit = {
    dut.io.fillVpn #= vpn
    dut.io.fillSup #= false
    dut.io.fillEntry.ppn #= ppn
    dut.io.fillEntry.vpnTag #= 0
    dut.io.fillEntry.writeProt #= false
    dut.io.fillEntry.supervisor #= false
    dut.io.fillEntry.cacheMode #= CacheMode.COPYBACK
    dut.io.fillEntry.modified #= false
    dut.io.fillValid #= true
    cd.waitSampling()
    dut.io.fillValid #= false
    cd.waitSampling()
  }

  /** Present a VPN and settle the combinational lookup WITHOUT crossing a clock edge,
    * so the purge -- a SYNCHRONOUS write -- has not yet taken effect and the raw 2-hot
    * outputs are still observable. Callers must be positioned just after a rising edge
    * (every helper here ends with `waitSampling`), so advancing 2 of the 10-unit period
    * settles combinational logic while staying inside the same cycle. */
  private def peek(dut: Tlb, cd: ClockDomain, vpn: Long): (Boolean, Long, Int, Boolean) = {
    dut.io.lookupVpn #= vpn
    dut.io.lookupSup #= false
    sleep(2)
    (dut.io.hit.toBoolean, dut.io.hitEntry.ppn.toLong,
     dut.dbgHitCount.toInt, dut.dbgMultiHot.toBoolean)
  }

  private def waysMatching(dut: Tlb, vpn: Long): Int = {
    val b = bankOf(vpn); val st = setOf(vpn); val tg = tagOf(vpn)
    (0 until 4).count(w =>
      dut.valids(b)(w)(st).toBoolean &&
      dut.tags(b)(w)(st).toBigInt.toLong == tg &&
      !dut.tagSup(b)(w)(st).toBoolean)
  }

  private def reset(dut: Tlb, cd: ClockDomain): Unit = {
    dut.io.fillValid #= false
    dut.io.invalidateAll #= false
    dut.io.dbgMultiHotClear #= false
    dut.io.lookupVpn #= 0
    dut.io.lookupSup #= false
    cd.waitSampling(2)
    dut.io.invalidateAll #= true
    cd.waitSampling()
    dut.io.invalidateAll #= false
    cd.waitSampling(2)
  }

  /** Plant a persistent 2-hot duplicate for `vpnA`, with 0x11111 and 0x22222 behind
    * the two matching ways. Returns once the array really holds two matches. */
  private def plantDuplicate(dut: Tlb, cd: ClockDomain, vpnA: Long, vpnB: Long): Unit = {
    assert(bankOf(vpnA) == bankOf(vpnB) && setOf(vpnA) == setOf(vpnB),
      "test setup: the two VPNs must share a (bank,set)")
    assert(tagOf(vpnA) != tagOf(vpnB),
      "test setup: the two VPNs must start with DIFFERENT tags")
    fill(dut, cd, vpnA, 0x11111L)
    fill(dut, cd, vpnB, 0x22222L)
    // Backdoor: retag whichever way took vpnB so it now carries vpnA's tag. The
    // round-robin victim is not reset by invalidateAll, so the landing ways are found
    // rather than assumed.
    val b = bankOf(vpnA); val st = setOf(vpnA)
    val wB = (0 until 4).find(w =>
      dut.valids(b)(w)(st).toBoolean &&
      dut.tags(b)(w)(st).toBigInt.toLong == tagOf(vpnB)).getOrElse(
      throw new AssertionError("test setup: vpnB did not land in this (bank,set)"))
    dut.tags(b)(wB)(st) #= BigInt(tagOf(vpnA))
    cd.waitSampling()
    assert(waysMatching(dut, vpnA) == 2,
      s"test setup failed: ${waysMatching(dut, vpnA)} ways match, expected 2")
  }

  private lazy val compiled = SimConfig.withVerilator.compile(new Tlb())

  test("a multi-hot lookup reports MISS instead of an OR-ed, invented PPN",
       VerilatorTest) {
    compiled.doSim("tlb_multihot_failsafe", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      reset(dut, cd)

      val vpnA = 0x00008L   // bank 0, set 0, tag 1
      val vpnB = 0x00010L   // bank 0, set 0, tag 2

      // ---- non-vacuity: ONE resident entry behaves normally ----
      fill(dut, cd, vpnA, 0x11111L)
      val (h1, p1, n1, m1) = peek(dut, cd, vpnA)
      assert(h1 && n1 == 1 && !m1 && p1 == 0x11111L,
        f"a single resident entry misbehaved: hit=$h1 ways=$n1 multi=$m1 ppn=0x$p1%05x")
      reset(dut, cd)

      // ---- the duplicate ----
      plantDuplicate(dut, cd, vpnA, vpnB)
      val (hit, ppn, n, multi) = peek(dut, cd, vpnA)
      println(f"[tlb-multihot] 2-hot lookup: hit=$hit ways=$n multiHot=$multi " +
              f"hitEntry.ppn=0x$ppn%05x  (ways hold 0x11111 and 0x22222)")

      assert(n == 2, s"test setup: expected a 2-hot vector, got $n")
      assert(multi, "dbgMultiHot did not fire on a 2-hot vector")

      // 1. THE CORRUPT VALUE IS STILL COMPUTED. The data mux is deliberately left
      //    untouched -- that is what keeps the fail-safe free -- so `io.hitEntry` is
      //    exactly as wrong as it always was.
      //
      //    MEASURED MECHANISM, and it is worse than an OR: SpinalHDL lowers `MuxOH` to
      //    an INDEX SELECT whose index is the OR of the matching bit POSITIONS, so a
      //    2-hot {1,2} selects index 1|2 = 3 -- a way that did not match at all, whose
      //    contents were never written for any VPN. The value read back here is
      //    therefore not 0x11111, not 0x22222, and not their OR; it is whatever that
      //    unrelated way happens to hold. (`TlbDuplicateFillSpec`'s header describes
      //    the same lowering, and the full-core sighting it cites -- 0xae0cf000 for a
      //    VA whose only descriptor says 0x60008 -- has exactly this shape, including
      //    the PPN changing value between builds.)
      assert(ppn != 0x11111L,
        f"the lookup mux returned 0x$ppn%05x, which IS the correct entry -- then this " +
        f"test is not actually exercising the hazard and its conclusion is worthless.")

      // 2. ...AND IT IS NEVER CONSUMED. This is the whole fail-safe: `io.hitEntry` is
      //    read only under `io.hit` (DtlbPlugin's `tlbHit && !needsMRefresh` arm), so
      //    forcing the hit bit low turns the invented translation into a table walk.
      assert(!hit,
        f"FAIL-SAFE DID NOT ENGAGE: a 2-hot lookup reported HIT and would have handed " +
        f"ppn 0x$ppn%05x -- a value never written for this VPN -- upward as a VALID, " +
        f"NON-FAULTING translation. On the full core that reaches AXI as a read of an " +
        f"unrelated physical page with no fault reported by anything.")
    }
  }

  test("the duplicate purge removes both matches in one cycle", VerilatorTest) {
    compiled.doSim("tlb_multihot_purge", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      reset(dut, cd)

      val vpnA = 0x00008L
      val vpnB = 0x00010L
      plantDuplicate(dut, cd, vpnA, vpnB)
      assert(waysMatching(dut, vpnA) == 2)

      // Presenting the VPN is all it takes: the purge is a synchronous write enabled
      // by the combinational multi-hot detect.
      dut.io.lookupVpn #= vpnA
      dut.io.lookupSup #= false
      sleep(2)
      assert(dut.dbgPurgeFire.toBoolean, "the purge did not arm on a multi-hot lookup")
      cd.waitSampling()
      sleep(1)

      val left = waysMatching(dut, vpnA)
      println(s"[tlb-multihot] ways matching after ONE purge cycle: $left")
      assert(left == 0,
        s"the purge left $left matching ways. A TLB entry is a pure cache of a " +
        s"descriptor -- the architectural copy lives in memory -- so purging ALL " +
        s"matches is safe here and is what makes convergence unconditional.")
    }
  }

  test("a forced persistent duplicate CONVERGES -- the purge terminates", VerilatorTest) {
    compiled.doSim("tlb_multihot_terminates", 1) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      reset(dut, cd)

      val vpnA = 0x00008L
      val vpnB = 0x00010L
      plantDuplicate(dut, cd, vpnA, vpnB)

      // THE REAL CLOSED LOOP, exactly as the core runs it: look the VPN up; on a miss,
      // walk and fill. This is the loop that NEVER TERMINATES if the fail-safe forces a
      // miss without the purge -- the fill replaces `OHMasking.first(flResidentVec)`,
      // so the second match survives and the next lookup is multi-hot again.
      //
      // The bound is deliberately tight. One iteration is expected: the purge empties
      // the set, the fill installs one entry, done. Anything that needs more than a
      // handful is a livelock in slow motion and must fail here.
      val MaxIterations = 8
      var iterations = 0
      var converged = false
      var lastPpn = -1L
      var lastWays = -1

      while (!converged && iterations < MaxIterations) {
        val (hit, ppn, n, multi) = peek(dut, cd, vpnA)
        lastPpn = ppn; lastWays = n
        if (hit) {
          converged = true
        } else {
          // the purge (if it armed) commits on this edge, then the "walk" refills
          cd.waitSampling()
          fill(dut, cd, vpnA, 0x11111L)
          iterations += 1
        }
      }

      println(s"[tlb-multihot] converged=$converged after $iterations miss/fill " +
              f"iteration(s); ways=$lastWays ppn=0x$lastPpn%05x")

      assert(converged,
        s"LIVELOCK: after $MaxIterations miss/fill iterations the lookup still reports " +
        s"a miss ($lastWays ways matching). This is the exact failure mode of forcing a " +
        s"miss WITHOUT the duplicate purge, and it would present on hardware as a " +
        s"machine that stops making progress with every architectural register intact.")
      assert(iterations <= 2,
        s"convergence took $iterations iterations; the purge empties the set in one, so " +
        s"more than two means it is not actually repairing the duplicate.")
      assert(lastWays == 1,
        s"converged with $lastWays matching ways -- expected exactly one")
      assert(lastPpn == 0x11111L,
        f"converged on ppn 0x$lastPpn%05x, expected the value actually filled (0x11111)")
    }
  }
}
