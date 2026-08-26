package m68k040.ls

import m68k040.{M68kSim, VerilatorTest}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

/** Directed test of the store-queue two-slot atomic drain + dual-slot forward (Task 4).
  *
  * A SPLIT (cross-line/page) store occupies ONE SQ entry with an optional slot B
  * {paddrB, nbytesB, validB} (strb/lineData are derived at drain time, task #252,
  * not carried per-entry). Forwarding checks a younger load
  * against BOTH slots. Commit-drain emits slot A then slot B (two DStoreCmds),
  * popping only after BOTH are drain-ACKed. Flush drops both. */
class StoreQueueSplitSpec extends AnyFunSuite {

  /** Alloc a SPLIT store: slot A at paddrA (nbytesA), slot B at paddrB (nbytesB).
    * strb/lineData are no longer alloc-time inputs (task #252) -- StoreQueue derives
    * them at drain from (paddr low nibble, size, data); with `data #= 0` here (this
    * test only checks paddr association, never the merge-data content), the derived
    * strb still comes out exactly `0xC000`/`0x0003` for this paddrA/paddrB pair since
    * the strobe depends only on (offset, size), never data. */
  def allocSplit(dut: StoreQueue, cd: ClockDomain, robId: Int,
                 paddrA: Long, nbytesA: Int,
                 paddrB: Long, nbytesB: Int,
                 cacheModeA: SpinalEnumElement[m68k040.cache.CacheMode.type] = m68k040.cache.CacheMode.WRITETHROUGH,
                 cacheModeB: SpinalEnumElement[m68k040.cache.CacheMode.type] = m68k040.cache.CacheMode.WRITETHROUGH): Unit = {
    val a = dut.io.alloc
    a.valid #= true
    a.payload.robId #= robId
    a.payload.paddr #= paddrA
    a.payload.vaddr #= paddrA   // identity for this test (no vaddr-specific case here)
    a.payload.data #= 0
    a.payload.size #= Size.LONG
    a.payload.nbytesA #= nbytesA
    a.payload.useStrbA #= true
    a.payload.validB #= true
    a.payload.paddrB #= paddrB
    a.payload.vaddrB #= paddrB   // identity for this test
    a.payload.nbytesB #= nbytesB
    a.payload.cacheMode #= cacheModeA
    a.payload.cacheModeB #= cacheModeB
    a.payload.supervisor #= false
    a.payload.precise #= false
    cd.waitSampling()
    a.valid #= false
  }

  def allocAligned(dut: StoreQueue, cd: ClockDomain, robId: Int, paddr: Long, data: Long, size: SpinalEnumElement[Size.type]): Unit = {
    val a = dut.io.alloc
    a.valid #= true
    a.payload.robId #= robId
    a.payload.paddr #= paddr
    a.payload.vaddr #= paddr   // identity for this test (no vaddr-specific case here)
    a.payload.data #= data
    a.payload.size #= size
    a.payload.nbytesA #= (size match { case Size.BYTE => 1; case Size.WORD => 2; case _ => 4 })
    a.payload.useStrbA #= false
    a.payload.validB #= false
    a.payload.paddrB #= 0
    a.payload.vaddrB #= 0
    a.payload.nbytesB #= 0
    a.payload.cacheMode #= m68k040.cache.CacheMode.WRITETHROUGH
    a.payload.supervisor #= false
    a.payload.precise #= false
    cd.waitSampling()
    a.valid #= false
  }

  def commit(dut: StoreQueue, cd: ClockDomain, robId: Int): Unit = {
    dut.io.commit.valid #= true; dut.io.commit.payload #= robId
    cd.waitSampling(); dut.io.commit.valid #= false
  }

  def setQuery(dut: StoreQueue, robId: Int, paddr: Long, size: SpinalEnumElement[Size.type],
               splitB: Boolean = false, paddrB: Long = 0): Unit = {
    dut.io.fwd.query.robId #= robId; dut.io.fwd.query.paddr #= paddr; dut.io.fwd.query.size #= size
    dut.io.fwd.query.splitB #= splitB; dut.io.fwd.query.paddrB #= paddrB
    dut.io.fwd.query.inhibited #= false
  }

  def initDut(dut: StoreQueue): ClockDomain = {
    val cd = dut.clockDomain; cd.forkStimulus(period = 10)
    dut.io.alloc.valid #= false; dut.io.commit.valid #= false
    dut.io.commitB.valid #= false; dut.io.commitB.payload #= 0
    dut.io.flush #= false; dut.io.drainAck #= false
    dut.io.drain.ready #= true
    dut.io.drainErr #= false
    dut.io.robHeadIn #= 0; dut.io.robHeadValidIn #= false; dut.io.irqPreemptPendingIn #= false
    dut.io.alloc.payload.validB #= false; dut.io.alloc.payload.useStrbA #= false
    setQuery(dut, 0, 0, Size.LONG)
    cd.waitSampling(3)
    cd
  }

  /** Auto-ack each accepted drain the next cycle (1-cycle D-cache model). */
  def forkDrainAck(dut: StoreQueue, cd: ClockDomain): Unit = fork {
    while (true) {
      cd.waitSamplingWhere(dut.io.drain.valid.toBoolean && dut.io.drain.ready.toBoolean)
      dut.io.drainAck #= true
      cd.waitSampling()
      dut.io.drainAck #= false
    }
  }

  test("split store: younger load overlapping slot B does not spuriously hit (stalls)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // split LONG at 0x10E..0x111: slot A = 0x10E (2 bytes: byte 14,15), slot B = 0x110 (2 bytes: byte 0,1)
      allocSplit(dut, cd, robId = 4,
        paddrA = 0x10E, nbytesA = 2,
        paddrB = 0x110, nbytesB = 2)
      // younger load (robId 6) of a WORD at 0x110 overlaps slot B -> must NOT full-hit; must stall.
      setQuery(dut, robId = 6, paddr = 0x110, Size.WORD)
      cd.waitSampling(); sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "split-store slot-B overlap must not full-forward")
      assert(dut.io.fwd.rsp.stall.toBoolean, "overlap with slot B -> stall")
      // a non-overlapping load -> no hit, no stall
      setQuery(dut, robId = 6, paddr = 0x200, Size.LONG)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && !dut.io.fwd.rsp.stall.toBoolean, "no overlap -> idle")
      cd.waitSampling(2)
    }
  }

  // Cross-page forward-hazard fix. Found by the agent that landed `9e0af36f` while
  // proving that commit's own line+mask fold correct: `9e0af36f` preserved the
  // PRE-EXISTING query-side assumption bit for bit -- a query's spilled bytes were
  // always tested against `qLine + 1`, correct for a same-page line-crossing load
  // (the page offset survives translation verbatim, so the second half really is
  // physically `qLine + 1` there -- exercised by the exhaustive sweep above) but
  // silently WRONG for a genuine page-crossing load, whose second half is
  // independently DTLB-translated and can land on ANY physical line. This directed
  // test is exactly that: an older store sitting where the load's REAL second half
  // is (`paddrB`), physically nowhere near `paddr`'s line or `paddr`'s line + 1 (the
  // old assumed target, which this test also proves is genuinely empty).
  test("cross-page split load: forward-hazard against an older store detected via " +
       "the REAL translated paddrB, not an assumed qLine+1", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // Older store: aligned WORD at 0x50000 (line 0x5000) -- physically nowhere near
      // the load's first-half line (0x1000) OR "0x1000's line + 1" (0x1010, the OLD
      // buggy assumption's target). Uncommitted (age via robHeadIn=0 / robId compare).
      allocAligned(dut, cd, robId = 4, paddr = 0x50000, data = 0xDEADBEEFL, Size.WORD)

      // Younger load (robId 6): a WORD at line 0x1000, offset 15 -- spills its LAST
      // byte into a second, independently-translated half. In real hardware that
      // second half is the genuinely page-crossing `p3Ctx.paddrB` / `LsEuPlugin`'s
      // `s1PaddrB`; here it is poked directly as 0x50000 -- where the store above
      // actually lives, NOT at 0x1010 (`paddr`'s line + 1).
      setQuery(dut, robId = 6, paddr = 0x1000FL, size = Size.WORD,
               splitB = true, paddrB = 0x50000L)
      cd.waitSampling(); sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean,
        "byte-partial cross-page overlap must not full-forward")
      assert(dut.io.fwd.rsp.stall.toBoolean,
        "cross-page split load's second half genuinely overlaps an older store -- " +
        "must stall (THIS is the bug: pre-fix, this silently missed the hazard)")

      // Negative control: same split query geometry, but `paddrB` now points
      // somewhere that does NOT overlap the store -> no hit, no stall.
      setQuery(dut, robId = 6, paddr = 0x1000FL, size = Size.WORD,
               splitB = true, paddrB = 0x60000L)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && !dut.io.fwd.rsp.stall.toBoolean,
        "paddrB not overlapping the store -> idle")

      // Negative control: the OLD assumed location (`qLine + 1` = 0x1010) is
      // genuinely empty -- confirms the hazard above is found via the REAL paddrB,
      // not by some accidental match on the old (unrelated) line.
      setQuery(dut, robId = 6, paddr = 0x1010L, size = Size.LONG)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && !dut.io.fwd.rsp.stall.toBoolean,
        "the old assumed qLine+1 location is genuinely empty")
      cd.waitSampling(2)
    }
  }

  // LS-cluster review finding P5 (this task): `SqAlloc.cacheModeB` is genuinely
  // independent of `cacheMode` (a cross-line/page split store's two halves can be
  // translated under different page attributes), so the `sameLine` fix must gate EACH
  // half's line term on ITS OWN cache mode, not a single per-entry mode. This directed
  // case proves both halves independently: slot A is COPYBACK, slot B is WRITETHROUGH,
  // in two DIFFERENT cache lines -- a same-line-as-A, non-overlapping query must NOT
  // stall (P5 narrowing applies to A's line), while a same-line-as-B, non-overlapping
  // query on the SAME still-undrained entry must STILL stall (WT conservatism on B's
  // line is untouched).
  test("split store: per-half cache mode independently gates the sameLine stall " +
       "(COPYBACK slot A does not stall, WRITETHROUGH slot B still does)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      // split LONG, slot A at 0x10E (line 0x100, COPYBACK), slot B at 0x110 (line 0x110, WRITETHROUGH).
      allocSplit(dut, cd, robId = 4,
        paddrA = 0x10E, nbytesA = 2,
        paddrB = 0x110, nbytesB = 2,
        cacheModeA = m68k040.cache.CacheMode.COPYBACK,
        cacheModeB = m68k040.cache.CacheMode.WRITETHROUGH)
      // Same line as slot A (0x100..0x10F), no byte overlap with EITHER slot -> must NOT
      // stall: slot A's line term is excluded because slot A is COPYBACK.
      setQuery(dut, robId = 6, paddr = 0x108, Size.WORD)
      cd.waitSampling(); sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "no byte overlap -> not a forward")
      assert(!dut.io.fwd.rsp.stall.toBoolean,
        "same line as COPYBACK slot A, no byte overlap -> must NOT stall (P5 fix)")
      // Same line as slot B (0x110..0x11F), no byte overlap with EITHER slot -> must
      // STILL stall: slot B's line term is untouched because slot B is WRITETHROUGH.
      setQuery(dut, robId = 6, paddr = 0x118, Size.WORD)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean, "no byte overlap -> not a forward")
      assert(dut.io.fwd.rsp.stall.toBoolean,
        "same line as WRITETHROUGH slot B, no byte overlap -> must still stall")
      cd.waitSampling(2)
    }
  }

  test("split store commit-drain writes BOTH halves; pops only after both ACK", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val drained = scala.collection.mutable.ListBuffer[(Long, Long)]()
      fork { while (true) { cd.waitSampling()
        if (dut.io.drain.valid.toBoolean && dut.io.drain.ready.toBoolean)
          drained += ((dut.io.drain.payload.paddr.toLong, dut.io.drain.payload.strb.toLong)) } }
      forkDrainAck(dut, cd)
      allocSplit(dut, cd, robId = 4,
        paddrA = 0x10E, nbytesA = 2,
        paddrB = 0x110, nbytesB = 2)
      // uncommitted -> no drain
      sleep(1); assert(!dut.io.drain.valid.toBoolean, "uncommitted split store must not drain")
      commit(dut, cd, robId = 4)
      cd.waitSampling(12)
      assert(drained.exists(_._1 == 0x10E), "slot A (0x10E) drains")
      assert(drained.exists(_._1 == 0x110), "slot B (0x110) drains")
      // after both halves drain the queue is empty
      sleep(1); assert(!dut.io.drain.valid.toBoolean, "empty after both halves drain")
      cd.waitSampling(2)
    }
  }

  test("flush squashes a split store (neither half drains)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val drained = scala.collection.mutable.ListBuffer[Long]()
      fork { while (true) { cd.waitSampling()
        if (dut.io.drain.valid.toBoolean && dut.io.drain.ready.toBoolean)
          drained += dut.io.drain.payload.paddr.toLong } }
      forkDrainAck(dut, cd)
      allocSplit(dut, cd, robId = 9,
        paddrA = 0x10E, nbytesA = 2,
        paddrB = 0x110, nbytesB = 2)
      // flush before commit -> squashed; neither half ever drains
      dut.io.flush #= true; cd.waitSampling(); dut.io.flush #= false
      cd.waitSampling(12)
      assert(!drained.contains(0x10EL), "squashed split store slot A must not drain")
      assert(!drained.contains(0x110L), "squashed split store slot B must not drain")
      cd.waitSampling(2)
    }
  }

  test("aligned store still drains as a single slot (fast path unchanged)", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val drained = scala.collection.mutable.ListBuffer[(Long, Long)]()
      fork { while (true) { cd.waitSampling()
        if (dut.io.drain.valid.toBoolean && dut.io.drain.ready.toBoolean)
          drained += ((dut.io.drain.payload.paddr.toLong, dut.io.drain.payload.data.toLong)) } }
      forkDrainAck(dut, cd)
      allocAligned(dut, cd, robId = 4, paddr = 0x100, data = 0xCAFEBABEL, Size.LONG)
      // forward full-overlap still works
      setQuery(dut, robId = 6, paddr = 0x100, Size.LONG)
      cd.waitSampling(); sleep(1)
      assert(dut.io.fwd.rsp.hit.toBoolean, "aligned full-overlap still forwards")
      assert(dut.io.fwd.rsp.data.toLong == 0xCAFEBABEL, "forward data")
      commit(dut, cd, robId = 4)
      cd.waitSampling(8)
      assert(drained.count(_._1 == 0x100) == 1, "aligned store drains exactly once")
      cd.waitSampling(2)
    }
  }

  // ── Exhaustive byte-level forward-geometry sweep ────────────────────────────
  // The forward overlap test is a line-equality + byte-lane mask intersection (the
  // masks pre-registered at alloc), replacing the 32-bit magnitude comparators that
  // used to put a `q.paddr + qBytes` CARRY8 adder on the design's worst post-route
  // path. Store-to-load forwarding fails SILENTLY when it is wrong (wrong bytes, not
  // a crash), so the replacement is proven two ways at once:
  //
  //   1. StoreQueue's own permanent `GenerationFlags.simulation` tripwire re-evaluates
  //      the ORIGINAL magnitude-comparator form against sim-only shadow registers and
  //      asserts agreement on every cycle for every resident entry. It is armed
  //      throughout this sweep (M68kSim sets `includeSimulation`), so every one of the
  //      cases below is ALSO an internal-equivalence check, not only an I/O check.
  //   2. This test independently re-derives the EXPECTED hit/stall/data from an
  //      interval model in Scala and checks the module's real outputs against it.
  //
  // The sweep is exhaustive over the whole geometry space rather than a hand-picked
  // list, so it contains by construction every class this replacement could plausibly
  // break: byte-level partial overlaps at a line boundary; adjacent-but-disjoint
  // same-line accesses; exact full overlap (the only forwarding case); a line-crossing
  // QUERY meeting slot A, slot B, or neither (the query is the one operand that is NOT
  // line-confined -- it is what forces the 19-bit two-line span mask); and a
  // line-crossing STORE's two slots against every query in the neighbourhood.
  private def sweepOverlap(aLo: Long, n: Int, qLo: Long, nq: Int): Boolean =
    n > 0 && (qLo < aLo + n) && (aLo < qLo + nq)

  test("forward geometry: exhaustive byte-level sweep vs an interval model", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      dut.io.drain.ready #= false          // nothing may drain out from under the sweep
      dut.io.robHeadIn #= 0
      val stLine  = 0x1000L                // line 0x100, far from the 2^32 wrap
      val stData  = 0xCAFEBABEL
      val sizes   = Seq((Size.BYTE, 1), (Size.WORD, 2), (Size.LONG, 4))
      var checked = 0

      for ((stSize, stN) <- sizes; stOff <- 0 until 16) {
        val paddrA  = stLine + stOff
        val split   = stOff + stN > 16
        val nbytesA = if (split) 16 - stOff else stN
        val paddrB  = stLine + 16
        val nbytesB = if (split) stN - nbytesA else 0

        // Uncommitted store at robId 4; every query below uses robId 6, so `ent` comes
        // from the head-anchored age compare (robHeadIn = 0) with no drain exposure.
        val a = dut.io.alloc
        a.valid #= true
        a.payload.robId #= 4
        a.payload.paddr #= paddrA;  a.payload.vaddr #= paddrA
        a.payload.data #= stData;   a.payload.size #= stSize
        a.payload.nbytesA #= nbytesA
        a.payload.useStrbA #= split
        a.payload.validB #= split
        a.payload.paddrB #= (if (split) paddrB else 0L)
        a.payload.vaddrB #= (if (split) paddrB else 0L)
        a.payload.nbytesB #= nbytesB
        a.payload.cacheMode  #= m68k040.cache.CacheMode.WRITETHROUGH
        a.payload.cacheModeB #= m68k040.cache.CacheMode.WRITETHROUGH
        a.payload.supervisor #= false
        a.payload.precise #= false
        cd.waitSampling()
        a.valid #= false

        for (qDeltaLine <- Seq(-1, 0, 1); (qSize, qN) <- sizes; qOff <- 0 until 16) {
          val qPaddr = stLine + qDeltaLine * 16 + qOff
          // A query that itself spills past its own line (qOff + qN > 16) is a split
          // access -- its second half lands, by construction (both here and in real
          // hardware: LsEuPlugin's `addrB = (va & ~15) + 16`), at the line-aligned base
          // of the NEXT line. This sweep only ever exercises the same-page relationship
          // (`qPaddrB` is a deterministic function of `qPaddr`'s own line); the DEDICATED
          // cross-page test below exercises a `paddrB` that is NOT `qLine + 1`.
          val qSplitB = (qOff + qN) > 16
          val qPaddrB = stLine + (qDeltaLine + 1) * 16
          setQuery(dut, robId = 6, paddr = qPaddr, size = qSize, splitB = qSplitB, paddrB = qPaddrB)
          cd.waitSampling(); sleep(1)

          val ovA      = sweepOverlap(paddrA, nbytesA, qPaddr, qN)
          val ovB      = split && sweepOverlap(paddrB, nbytesB, qPaddr, qN)
          val full     = ovA && !split && paddrA == qPaddr && nbytesA == qN
          val partial  = (ovA || ovB) && !full
          // WRITETHROUGH on both halves here, so the sameLine stall is NOT scoped out
          // (the COPYBACK narrowing of `9130a0b2` is exercised by its own tests). The
          // query's SECOND half (when it itself spills, `qSplitB`) triggers the exact
          // same refill-stale-line hazard against either store slot -- this task's
          // `sameLineA2`/`sameLineB2` extension.
          val sameLine = ((paddrA >> 4) == (qPaddr >> 4)) ||
                         (split && ((paddrB >> 4) == (qPaddr >> 4))) ||
                         (qSplitB && ((paddrA >> 4) == (qPaddrB >> 4))) ||
                         (qSplitB && split && ((paddrB >> 4) == (qPaddrB >> 4)))
          val expHit   = full
          val expStall = (partial || sameLine) && !full

          val what = f"store ${if (split) "SPLIT" else "aligned"} $stSize%s @+$stOff%d " +
                     f"(A=$paddrA%x/$nbytesA%d B=$paddrB%x/$nbytesB%d) vs load $qSize%s @$qPaddr%x"
          assert(dut.io.fwd.rsp.hit.toBoolean == expHit,   s"hit mismatch: $what")
          assert(dut.io.fwd.rsp.stall.toBoolean == expStall, s"stall mismatch: $what")
          if (expHit)
            assert(dut.io.fwd.rsp.data.toLong == stData, s"forward data mismatch: $what")
          checked += 1
        }

        // Squash the uncommitted entry and move to the next store geometry.
        dut.io.flush #= true; cd.waitSampling(); dut.io.flush #= false
        cd.waitSampling()
      }
      assert(checked == 48 * 144, s"sweep coverage regressed: only $checked cases run")
      cd.waitSampling(2)
    }
  }
}
