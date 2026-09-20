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

  test("optional subword path excludes split producers and independently translated split queries", VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8, subwordForwarding = true)).doSim { dut =>
      val cd = initDut(dut)
      allocSplit(dut, cd, 4, 0x10e, 2, 0x900, 2, data = 0x89abcdefL)
      setQuery(dut, 6, 0x900, Size.BYTE)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.stall.toBoolean)
      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false
      allocAligned(dut, cd, 4, 0x900, 0x89abcdefL, Size.LONG)
      setQuery(dut, 6, 0x10f, Size.WORD, splitB = true, paddrB = 0x900)
      sleep(1)
      assert(!dut.io.fwd.rsp.hit.toBoolean && dut.io.fwd.rsp.stall.toBoolean,
        "query's second physical fragment must still block, never become a subword hit")
    }
  }

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
                 cacheModeB: SpinalEnumElement[m68k040.cache.CacheMode.type] = m68k040.cache.CacheMode.WRITETHROUGH,
                 data: Long = 0): Unit = {
    val a = dut.io.alloc
    a.valid #= true
    a.payload.robId #= robId
    a.payload.paddr #= paddrA
    a.payload.vaddr #= paddrA   // identity for this test (no vaddr-specific case here)
    a.payload.data #= data
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

  // ── Split-store slot-reuse aliasing stress (campaign/fmovem-predec-store) ──────────
  //
  // BACKGROUND / WHY THIS TEST EXISTS IN THIS FORM (not as an FMOVEM.X lock-step test):
  // the campaign brief for this scenario asked for `FMOVEM.X FP0-FP7,-(An)` (the
  // store-direction data-register-list form) driven end-to-end through
  // ExecuteLockStepSpec, to stress StoreQueue's split-store mechanism with a burst of
  // ~24 chunk stores at a rotating cache-line phase, mirroring the sibling agent's
  // load-ring investigation. That instruction is NOT buildable on this branch:
  // `DecodeStage.scala`'s `slot0IsFmovemx` gate (`s0FpOpClass === B"3'b110"` only) and
  // the existing `FpMemLoadSpec` test "FMOVEM.X list,(A0) (opclass 111, store -- task
  // #242, still unowned): still traps" both confirm FMOVEM.X's STORE direction
  // (opclass 111, task #242) is unimplemented — it decodes and traps (vector 11)
  // before ever reaching rename/dispatch/the LS EU/StoreQueue. `-(An)`/`(An)+`
  // auto-update addressing for the data-list form is *also* unimplemented for either
  // direction (only `(An)`/`(d16,An)` are admitted today, `s0FpGenEaMode`). See this
  // task's final report for the full trace; not re-litigated here.
  //
  // So this test drives StoreQueue directly (the same component + the same
  // `useStrbA`/`validB`/slot-A/B mechanism FMOVEM.X store would have gone through),
  // reproducing the SPECIFIC boundary condition the brief asked to scrutinize: "a
  // fresh unrelated store landing in the exact cycle an old split store's second half
  // drains/retires and its StoreQueue slot gets reused." The rotating byte-offset
  // list below (14,10,6,2 repeating) is not arbitrary -- it is the exact line-offset
  // sequence produced by an 8-element FMOVEM-style burst from a base misaligned by 2
  // with a 4-byte access stride (worked by hand: `An=base+2`, addr(3:0) cycles
  // 14,10,6,2,14,10,6,2), so it reproduces the SAME split/non-split shape (2 of 8
  // cross a line) the original scenario would have, even though the vehicle here is
  // a bare StoreQueue-unit burst instead of the (unbuildable) real instruction.
  //
  // ARCHITECTURAL FINDING (established by reading StoreQueue.scala before writing this
  // test, not assumed): unlike the load-ring bug this campaign is modeled on -- which
  // tracked "is slot A's response still pending" via a POSITIONAL proxy instead of a
  // real pointer compare -- a split store's two halves live as FIELDS of ONE ring
  // entry (`validB`/`paddrB`/`maskBs`/`cacheModesB`, all indexed by the SAME `i` as
  // slot A), not as separate entries in a parallel ring. Residency (`valids(i)`) is
  // real per-entry state cleared ONLY by the real ack cursor (`head`/`ackPhaseB`) once
  // BOTH halves have acked (`terminalAck`'s `!validBs(head)` / `ackPhaseB` gate) --
  // never derived from a counter or index relationship. A fresh alloc can only ever
  // target `tail`, and `io.full` (the LS-EU's sole allocation gate) is computed from
  // the LIVE (unregistered) `valids` popcount, so a same-cycle pop-then-realloc can
  // never race: the popped entry's register write does not take effect until the
  // NEXT edge, so `io.full` for the pop's own cycle still reflects the pre-pop
  // occupancy. This test is the executable form of that architectural read: it
  // manufactures the exact adjacency (alloc lands in the ring slot a split entry
  // JUST vacated) and checks for data corruption/misattribution, not merely that the
  // module doesn't hang.
  test("split-store slot reuse: a fresh alloc landing in the ring slot an old SPLIT " +
       "entry's second half just vacated does not alias/corrupt data, under a " +
       "sustained multi-store backlog (8-store full-ring burst, ack-delayed drain)",
       VerilatorTest) {
    M68kSim().withVerilator.compile(new StoreQueue(8)).doSim { dut =>
      val cd = initDut(dut)
      val depth = 8

      // Rotating line-offset sequence -- see header comment for the by-hand derivation.
      // Only offset 14 crosses the 16-byte line (14+4=18>16); 10/6/2 stay within one
      // line (10+4=14, 6+4=10, 2+4=6, all <=16). So entries 0 and 4 are SPLIT.
      val offsets  = Seq(14, 10, 6, 2, 14, 10, 6, 2)
      // Each entry gets its OWN pair of lines (32 bytes apart) so no two of the 8
      // original entries -- nor the later-injected 9th store -- can legitimately
      // overlap; any observed cross-contamination is therefore unambiguously a real
      // aliasing bug, never a legitimate forward/overlap.
      val lineBase = (i: Int) => 0x00005000L + i * 0x20L
      val pattern  = (i: Int) => 0xA0000000L + i         // distinct, recognizable per entry
      def isSplit(off: Int) = off + 4 > 16

      case class Half(paddr: Long, useStrb: Boolean, expStrb: Long, expData: BigInt)
      val expected = scala.collection.mutable.ListBuffer[Half]()

      def strbAOf(off: Int, n: Int): Long = { var s = 0L; for (k <- 0 until n) { val p = off + k; if (p < 16) s |= (1L << p) }; s }
      def strbBOf(off: Int, n: Int): Long = { var s = 0L; for (k <- 0 until n) { val p = off + k; if (p >= 16) s |= (1L << (p - 16)) }; s }
      // Reconstruct the 128-bit line word (as a BigInt) that storeDataA/B would
      // produce: byte i of the value (big-endian, k=0=MSB) lands at line-byte
      // position (off+k); byte i of the 128-bit line occupies bits [i*8 +: 8].
      def lineDataOf(off: Int, n: Int, data: Long, sideIsA: Boolean): BigInt = {
        val bytes = Array.fill(16)(0)
        for (k <- 0 until n) {
          val pos  = off + k
          val inA  = pos < 16
          if (inA == sideIsA) {
            val idx   = if (inA) pos else pos - 16
            val shift = (n - 1 - k) * 8
            bytes(idx) = ((data >> shift) & 0xff).toInt
          }
        }
        var v = BigInt(0)
        for (i <- 0 until 16) v = v | (BigInt(bytes(i)) << (i * 8))
        v
      }
      // Expand a 16-bit byte-strobe into a 128-bit byte-granular mask, so the
      // (non-strobed, don't-care) lanes drop out of the content comparison above.
      def strb2mask(strb: Long): BigInt = {
        var m = BigInt(0)
        for (i <- 0 until 16) if (((strb >> i) & 1) != 0) m = m | (BigInt(0xff) << (i * 8))
        m
      }

      // ---- allocate exactly `depth` entries, uncommitted, so occupancy hits FULL
      // deterministically before anything can drain. ----
      for (i <- 0 until depth) {
        val off    = offsets(i)
        val paddrA = lineBase(i) + off
        val data   = pattern(i)
        if (isSplit(off)) {
          val nbytesA = 16 - off
          val nbytesB = 4 - nbytesA
          val paddrB  = lineBase(i) + 16
          allocSplit(dut, cd, robId = i, paddrA = paddrA, nbytesA = nbytesA,
                     paddrB = paddrB, nbytesB = nbytesB, data = data)
          expected += Half(paddrA, useStrb = true, strbAOf(off, 4), lineDataOf(off, 4, data, sideIsA = true))
          expected += Half(paddrB, useStrb = true, strbBOf(off, 4), lineDataOf(off, 4, data, sideIsA = false))
        } else {
          allocAligned(dut, cd, robId = i, paddr = paddrA, data = data, size = Size.LONG)
          expected += Half(paddrA, useStrb = false, 0, BigInt(data))
        }
      }
      sleep(1)
      assert(dut.io.full.toBoolean, "ring must read exactly FULL after 8 back-to-back allocations")
      assert(dut.head.toInt == 0 && dut.tail.toInt == 0,
        s"expected head=tail=0 (wrapped) after exactly 8 allocs into a depth-8 ring; " +
        s"got head=${dut.head.toInt} tail=${dut.tail.toInt}")

      // ---- record every drained half + drive an ACK-DELAYED (not same-cycle) ack,
      // so accepted halves genuinely BACK UP (multiple outstanding halves in flight)
      // instead of a trivial 1-outstanding pipeline. ----
      val ackLatency = 3
      var t = 0
      val due = scala.collection.mutable.Queue[Int]()
      val drainedA = scala.collection.mutable.ListBuffer[(Long, Boolean, Long, BigInt)]()
      dut.io.drainAck #= false
      fork {
        while (true) {
          cd.waitSampling()
          t += 1
          if (dut.io.drain.valid.toBoolean && dut.io.drain.ready.toBoolean) {
            val p = dut.io.drain.payload
            drainedA += ((p.paddr.toLong, p.useStrb.toBoolean, p.strb.toLong,
              if (p.useStrb.toBoolean) p.lineData.toBigInt else BigInt(p.data.toLong)))
            due.enqueue(t + ackLatency)
          }
          if (due.nonEmpty && due.front <= t) { due.dequeue(); dut.io.drainAck #= true }
          else dut.io.drainAck #= false
        }
      }

      // ---- commit all 8 in program order, from a BACKGROUND fork (one robId/cycle),
      // concurrently with the poll loop below -- entry 0 (the split store, at the
      // ring head) can pop in as few as ~6-8 cycles (2 sends + ack-latency-3 twice),
      // which is well within the 8 cycles the commit loop itself would otherwise
      // take run sequentially on the main thread. Committing sequentially on the main
      // thread BEFORE polling (the first version of this test) let 2-3 entries pop
      // before polling ever started, silently testing a LATER, less-precise reuse
      // event than the one asked for -- this fork is what makes the poll loop below
      // actually catch entry 0's OWN pop, not a downstream one. ----
      fork {
        for (i <- 0 until depth) {
          dut.io.commit.valid #= true; dut.io.commit.payload #= i
          cd.waitSampling()
        }
        dut.io.commit.valid #= false
      }

      // ---- poll for the ring's FIRST freed slot (entry 0, the oldest, a SPLIT store)
      // and inject a brand-new, unrelated store the moment it's observed -- the exact
      // adjacency the brief asked to scrutinize. Polling starts THIS cycle, concurrent
      // with the commit fork above, so it observes the very first full->!full edge. ----
      var guard = 0
      var injected = false
      var tailAtInject = -1
      var headAtInject = -1
      // The TOP of the id space, i.e. as far as the ROB allows from the 0..7 range used
      // above. This was the literal 40 with the comment "robId is only 6 bits (0..63)" --
      // true when the ROB was 64 deep, an out-of-range poke once it became 32.
      val injRobId = m68k040.TestRobIds.highBlock(1).head
      val injPaddr = 0x00009000L
      val injData  = 0xFEEDFACEL
      while (!injected && guard < 400) {
        cd.waitSampling(); sleep(1); guard += 1
        if (!dut.io.full.toBoolean) {
          tailAtInject = dut.tail.toInt
          headAtInject = dut.head.toInt
          val a = dut.io.alloc
          a.valid #= true
          a.payload.robId #= injRobId
          a.payload.paddr #= injPaddr; a.payload.vaddr #= injPaddr
          a.payload.data #= injData; a.payload.size #= Size.LONG
          a.payload.nbytesA #= 4; a.payload.useStrbA #= false
          a.payload.validB #= false; a.payload.paddrB #= 0; a.payload.vaddrB #= 0; a.payload.nbytesB #= 0
          a.payload.cacheMode #= m68k040.cache.CacheMode.WRITETHROUGH
          a.payload.cacheModeB #= m68k040.cache.CacheMode.WRITETHROUGH
          a.payload.supervisor #= false; a.payload.precise #= false
          cd.waitSampling()
          a.valid #= false
          injected = true
        }
      }
      assert(injected, "ring never freed a slot within the guard window -- test setup is broken " +
        "(entry 0 should pop well before this)")
      // ── THE aliasing proof: the new store's alloc index (tail, unmoved since the
      // ring filled) must equal the ring slot entry 0 (the split store) JUST vacated
      // (its OLD head index = new head - 1 mod depth). If StoreQueue tracked slot-B
      // pendingness via anything positional instead of the real `valids`/ack-cursor
      // state, this is exactly where it would show up as a wrong index or a stale
      // read of the about-to-be-overwritten fields. ──
      val freedIdx = (headAtInject - 1 + depth) % depth
      assert(tailAtInject == freedIdx,
        s"the injected alloc's write index (tail=$tailAtInject) must equal the just-freed " +
        s"slot (head-1=$freedIdx) -- if these differ the reuse this test targets never " +
        s"actually happened, so the test proves nothing")
      expected += Half(injPaddr, useStrb = false, 0, BigInt(injData))
      commit(dut, cd, robId = injRobId)

      // ---- drain everything to empty; a wedge under this backlog shape (several
      // split entries + sustained multi-outstanding acks + a same-slot-reuse alloc)
      // would show up as a timeout here. ----
      guard = 0
      while (!dut.io.empty.toBoolean && guard < 4000) { cd.waitSampling(); guard += 1 }
      assert(dut.io.empty.toBoolean,
        s"StoreQueue never drained to empty within $guard cycles -- possible wedge under " +
        s"split-store burst + slot-reuse backlog")
      cd.waitSampling(4)

      // ---- content verification: every expected half appears EXACTLY once, with the
      // exact strobe (split halves) or exact data (aligned halves incl. the injected
      // store), and nothing else drained that wasn't expected (no phantom/duplicate
      // halves, no cross-entry data bleed). ----
      assert(drainedA.length == expected.length,
        s"expected exactly ${expected.length} drained halves (6 aligned + 2*2 split-halves " +
        s"+ 1 injected), got ${drainedA.length}: $drainedA")
      for (e <- expected) {
        val matches = drainedA.filter(d => d._1 == e.paddr && d._2 == e.useStrb)
        assert(matches.length == 1,
          s"expected exactly one drained half at paddr=0x${e.paddr.toHexString} useStrb=${e.useStrb}, " +
          s"got ${matches.length}: $matches")
        val (_, _, strb, data) = matches.head
        if (e.useStrb) {
          assert(strb == e.expStrb,
            s"split half at 0x${e.paddr.toHexString}: strobe mismatch, expected 0x${e.expStrb.toHexString} got 0x${strb.toHexString}")
          assert((data & (strb2mask(e.expStrb))) == (e.expData & strb2mask(e.expStrb)),
            s"split half at 0x${e.paddr.toHexString}: strobed byte content mismatch, expected 0x${e.expData.toString(16)} " +
            s"got 0x${data.toString(16)} (strb=0x${e.expStrb.toHexString})")
        } else {
          assert(data == e.expData,
            s"aligned half at 0x${e.paddr.toHexString}: data mismatch, expected 0x${e.expData.toString(16)} got 0x${data.toString(16)} " +
            s"-- if this is the injected store (0x${injPaddr.toHexString}) reading back stale/foreign bytes, " +
            s"or an original entry reading back the injected store's 0x${injData.toHexString}, that is the " +
            s"exact aliasing corruption this test targets")
        }
      }
      // Explicit negative check: the injected store's unique pattern must not appear
      // on any OTHER address, and no original entry's pattern must appear on the
      // injected store's address.
      for (d <- drainedA if d._1 != injPaddr) {
        assert(d._4 != BigInt(injData) || d._2,
          s"the injected store's unique pattern 0x${injData.toHexString} leaked onto an " +
          s"unrelated address 0x${d._1.toHexString}")
      }
    }
  }
}
