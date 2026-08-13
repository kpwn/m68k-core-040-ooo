package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** The I-cache's S1 hit/miss VERDICT, validated against an independent model of the
  * raw arrays.
  *
  * ── HISTORY, because the file name still says "shadow" ──────────────────────────
  * Built for Task 10 (M3a) as a SHADOW check: the verdict computed from the registered
  * S0 context (`tagQ`, `validsQ`, `s0Ppn`, `s0Cacheable`) had to equal the LIVE
  * accept-cycle comparator (`isHit`), delayed one cycle, on every accepted command --
  * a per-way equality, not merely an OR-reduced one (review fix I1). Task 11 (M3b)
  * FLIPPED every consumer onto the registered verdict and DELETED the live comparator,
  * so there is no second verdict left to compare against and the shadow leg
  * (`dbgVerdictMatch`, `dbgS0Fresh`, `dbgLiveHitQ`, `dbgLiveHitVecQ`) is gone with it.
  *
  * ── WHAT SURVIVES, AND WHY IT IS NOT SHADOW SCAFFOLDING ─────────────────────────
  * The shadow was only ONE of three legs. The other two never referenced the live path
  * and are now the primary external evidence that the plugin's single surviving verdict
  * is RIGHT rather than merely self-consistent:
  *
  *  1. An INDEPENDENT Scala MODEL re-derives the verdict from raw array content
  *     (`IcacheArrayProbe.wayValid`/`wayTag`) plus the registered S0 context, and
  *     checks the RTL verdict against it. No RTL verdict expression participates.
  *  2. Coverage counters that make leg 1 non-vacuous: both verdict polarities, all four
  *     ways, both beats, all four lanes, the INHIBITED (`s0Cacheable == false`) case and
  *     the translation-fault case must all be observed, or the test fails as "barely
  *     exercised". Plus the S0 ADDRESS-CAPTURE checks (review fix I2): `s0Set`/`s0Beat`/
  *     `s0Lane` re-derived from `s0Pc` and the geometry constants.
  *  3. NEW at M3b: `s1HitVec` must be exactly ONE-HOT on every hit. Post-flip it IS the
  *     response's data/predecode select (`s1WayOh`), so a 2-hot vector silently
  *     delivers the bitwise OR of two ways -- and it is the direct observable for the
  *     "one fill owner per set" invariant that SG-1's accept gate protects.
  *
  * Spec risk R3 is why all of this exists: `FetchRsp` carries no tag and
  * `FetchAlignPlugin` attributes every response to its outstanding ring's HEAD, so a
  * wrong verdict or a wrong WAY shows up as mis-paired instruction bytes, not as a
  * crash.
  *
  * The file also carries M3b's two directed flip regressions (sustained II=1 on hits;
  * no younger command accepted behind an unresolved miss) at the bottom.
  */
class IcacheVerdictShadowSpec extends AnyFunSuite {

  class Dut(xlateFactory: => FiberPlugin with TranslationService = new IdentityTranslationPlugin)
      extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = xlateFactory
    val icache = new IcachePlugin
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  // `def`, not `lazy val` -- see IcacheUnifiedArraySpec's note on the elaboration
  // deadlock an inner-class `lazy val` causes.
  private def compiled      = SimConfig.withVerilator.compile(new Dut())
  private def compiledCmode = SimConfig.withVerilator.compile(new Dut(new ICacheModeTranslationPlugin))

  // ── Verdict monitor ───────────────────────────────────────────────────────────
  final class ShadowStats {
    var checked     = 0     // accepted commands whose verdict was compared
    var hits        = 0
    var misses      = 0
    var faults      = 0
    var inhibited   = 0
    var modelChecks = 0
    var modelSkips  = 0
    /** Commands whose verdict was MISS even though some way's `tagMem[s0Set]` DOES
      * equal `s0Ppn` -- i.e. commands where the VALID bit, and nothing else, decided
      * the verdict. Without these, a `validsQ` capture bug is invisible: the tag
      * compare would reject the line anyway. */
    var validDecidedMiss = 0
    val wayHits     = Array.fill(4)(0)
    val beatSeen    = Array.fill(2)(0)
    val laneSeen    = Array.fill(4)(0)
    def describe: String =
      s"checked=$checked hits=$hits misses=$misses faults=$faults inhibited=$inhibited " +
      s"model=$modelChecks (skipped $modelSkips) validDecidedMiss=$validDecidedMiss " +
      s"wayHits=${wayHits.mkString(",")} " +
      s"beats=${beatSeen.mkString(",")} lanes=${laneSeen.mkString(",")}"
  }

  /** Fork the per-cycle verdict monitor.
    *
    * TIMING DISCIPLINE, which is the whole reason this is written the way it is.
    * SpinalSim resumes a thread at the SAMPLING POINT, i.e. just before the clock edge
    * that latches the registers (`IcachePlugin.scala`'s `dbgAllocCommitPending` comment
    * states this explicitly). So `cmdIn.valid`/`cmdIn.ready` read at a sampling point
    * race the command driver's own poke for the NEXT cycle. This monitor therefore
    * never reads a driven input to decide anything: it triggers on `s0Valid`, a
    * REGISTER that is high on the cycle after `cmdPort.fire` (and stays high while an
    * accepted miss waits for the fill engine), and reads only registers and array
    * content.
    *
    * The independent model needs the arrays as they were during the ACCEPT cycle, but
    * can only read them one cycle later. The only writers of `valids`/`tagMem` are the
    * allocation commit (`dbgAllocCommitCycle`, a register-derived combinational signal)
    * and `invalidateAll`, so the model is SKIPPED on any S1 cycle whose predecessor had
    * either high, and `modelSkips` is asserted to be a small minority.
    */
  private def startMonitor(dut: Dut, model: Boolean = true): ShadowStats = {
    val st = new ShadowStats
    val ic = dut.icache
    fork {
      var prevArrayWrite = false
      while (true) {
        dut.clockDomain.waitSampling()
        // M3b (Task 11): the trigger was `dbgS0Fresh`, a transitional register deleted
        // with the live comparator. Its replacement is the production S0 valid bit
        // itself, qualified with `!s0Replay` to exclude REPLAY/FAULT's synthetic
        // contexts (whose `tagQ`/`validsQ`/`s0Ppn` are the LAST accepted command's and
        // whose verdict is deliberately not consulted by the RTL either). Under M3b
        // `s0Valid` is HELD while an accepted miss waits for the fill engine, so one
        // command can be observed on more than one cycle -- harmless here (the values
        // are frozen for exactly that duration, so every re-check checks the same thing)
        // and it only inflates the `checked`/`misses` counters, which are used as
        // lower-bound non-vacuity floors.
        if (ic.logic.s0Valid.toBoolean && !ic.logic.s0Replay.toBoolean) {
          st.checked += 1
          val verdict = ic.logic.s1Hit.toBoolean
          val pc     = ic.logic.s0Pc.toLong
          val set    = ic.logic.s0Set.toInt
          val ppn    = ic.logic.s0Ppn.toBigInt
          val cache  = ic.logic.s0Cacheable.toBoolean
          val flt    = ic.logic.s0Fault.toBoolean

          // ── TASK 10 REVIEW FIX I2: independently validate the S0 ADDRESS CAPTURE ──
          // `s0Set`/`s0Beat`/`s0Lane` were captured but validated by NEITHER leg of the
          // equivalence check: the in-RTL assertion never reads them (no RTL consumer at
          // this task), and the independent model BELOW *consumes* `s0Set` for its own
          // array indexing -- so a mis-captured set is read consistently by the model and
          // silently "agrees". Proven by mutation (`s0Set := lookupSet(4 downto 0).resized`).
          // Task 11 uses `s0Set` in the extended `demandSetOwned` check and Task 12 uses
          // it to re-address the arrays, so it is safety-relevant, not decorative.
          // NOT CIRCULAR: the expectation is derived from `s0Pc`, which is independently
          // end-to-end validated via `rspPcReg` and the order oracle, and the field
          // positions are the ISA/geometry constants (64B line, 64 sets, 8B lane).
          if (set != ((pc >> 6) & 63).toInt)
            simFailure(
              f"M3a S0 SET CAPTURE WRONG at pc=0x$pc%x: s0Set=$set but pc(11:6)=" +
              f"${((pc >> 6) & 63).toInt}. The registered set index does not address the " +
              f"line the accepted command asked for -- Task 11's `demandSetOwned` and " +
              f"Task 12's array re-address both read this.")
          if (ic.logic.s0Beat.toBoolean != (((pc >> 5) & 1) == 1))
            simFailure(
              f"M3a S0 BEAT CAPTURE WRONG at pc=0x$pc%x: s0Beat=" +
              f"${ic.logic.s0Beat.toBoolean} but pc(5)=${((pc >> 5) & 1) == 1}. The " +
              f"registered beat select would deliver the WRONG HALF-LINE.")
          if (ic.logic.s0Lane.toInt != ((pc >> 3) & 3).toInt)
            simFailure(
              f"M3a S0 LANE CAPTURE WRONG at pc=0x$pc%x: s0Lane=${ic.logic.s0Lane.toInt} " +
              f"but pc(4:3)=${((pc >> 3) & 3).toInt}. The registered lane index would " +
              f"deliver the WRONG 8-BYTE WINDOW -- risk R3's silent mis-paired bytes.")

          // M3b: the shadow-vs-live comparison stood here and is deleted with the live
          // comparator it compared against -- there is only ONE verdict now. What is
          // NOT deleted is the leg that was never a comparison of RTL against RTL: the
          // independent Scala array model below, which re-derives the verdict from raw
          // `tagMem`/`valids` content and is now the only external check that the sole
          // surviving verdict is RIGHT (rather than merely equal to something).
          //
          // WAY COVERAGE also survives, and it matters MORE post-flip than it did
          // pre-flip: `s1HitVec` is now the literal one-hot select for the response's
          // data and predecode (`s1WayOh`), so `requireAllWays` is a direct check that
          // every way can be selected and deliver a correct response end to end -- the
          // property Task 10's review fix I1 added the per-way comparison for.
          if (verdict) {
            st.hits += 1
            val oneHot = (0 until 4).count(w => ic.logic.s1HitVec(w).toBoolean)
            if (oneHot != 1)
              simFailure(
                f"M3b: s1HitVec is $oneHot-hot at pc=0x$pc%x (set=$set) on a HIT. It is " +
                f"the one-hot select for the response's data AND predecode, so a 2-hot " +
                f"vector delivers the bitwise OR of two ways -- silent wrong " +
                f"instruction bytes (risk R3), and a direct sign that two valid ways in " +
                f"one set carry the same tag.")
            for (w <- 0 until 4) if (ic.logic.s1HitVec(w).toBoolean) st.wayHits(w) += 1
          } else st.misses += 1
          if (flt) st.faults += 1
          if (!cache) st.inhibited += 1
          st.beatSeen(if (ic.logic.s0Beat.toBoolean) 1 else 0) += 1
          st.laneSeen(ic.logic.s0Lane.toInt) += 1

          if (model) {
            if (prevArrayWrite) st.modelSkips += 1
            else {
              // INDEPENDENT MODEL: the RTL verdict is not consulted at all -- the
              // expectation is re-derived from the raw arrays.
              val exp = cache && (0 until 4).exists(w =>
                IcacheArrayProbe.wayValid(ic, w, set) &&
                IcacheArrayProbe.wayTag(ic, w, set) == ppn)
              if (!exp && cache &&
                  (0 until 4).exists(w => IcacheArrayProbe.wayTag(ic, w, set) == ppn))
                st.validDecidedMiss += 1
              st.modelChecks += 1
              if (exp != verdict)
                simFailure(
                  f"MODEL DISAGREES WITH THE S1 VERDICT at pc=0x$pc%x: array content " +
                  f"for set=$set says hit=$exp, the registered S1 verdict says " +
                  f"hit=$verdict (ppn=0x${ppn.toString(16)} cacheable=$cache). This is " +
                  f"now the ONLY verdict in the plugin: it gates cmdPort.ready, selects " +
                  f"the response way and decides whether a fill is dispatched.")
            }
          }
        }
        prevArrayWrite = ic.logic.dbgAllocCommitCycle.toBoolean ||
                         dut.icache.logic.invalidateAll.toBoolean
      }
    }
    st
  }

  /** Drive `addrs` through the ordered-stream driver with the verdict monitor running. */
  private def sweep(name: String, addrs: Seq[Long], prefetch: Boolean,
                    requireHits: Boolean = true, requireMisses: Boolean = true,
                    requireAllWays: Boolean = false): ShadowStats = {
    var out: ShadowStats = null
    compiled.doSim(name) { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      // See IcacheOrderOracleSpec's note: cmdIn.valid/pc and invalidateAll are
      // undriven top-level IO and are X/random-per-seed until explicitly poked.
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      dut.clockDomain.waitSampling(5)
      dut.icache.logic.prefetchEnable  #= prefetch
      dut.clockDomain.waitSampling(2)

      val st = startMonitor(dut)
      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.logic.cmdIn.valid, cmdReady = dut.probe.logic.cmdIn.ready,
        cmdPc    = dut.probe.logic.cmdIn.payload.pc,
        rspValid = dut.probe.logic.rspOut.valid, rspPc = dut.probe.logic.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = addrs, timeoutCycles = 120000)
      dut.clockDomain.waitSampling(200)

      // ---- non-vacuity ---------------------------------------------------------
      assert(st.checked >= addrs.length,
        s"only ${st.checked} accepted commands had their verdict compared for " +
        s"${addrs.length} commands issued -- the verdict was barely exercised, so it " +
        s"proved nothing (${st.describe})")
      if (requireHits) assert(st.hits > 0,
        s"no HIT verdict was ever observed: a verdict stuck at 'miss' would pass " +
        s"vacuously (${st.describe})")
      if (requireMisses) assert(st.misses > 0,
        s"no MISS verdict was ever observed: a verdict stuck at 'hit' would pass " +
        s"vacuously (${st.describe})")
      assert(st.modelChecks >= st.checked / 2,
        s"the independent array model ran on only ${st.modelChecks} of ${st.checked} " +
        s"checked commands (${st.modelSkips} skipped for a concurrent array write) " +
        s"-- too few to be meaningful (${st.describe})")
      if (requireAllWays) assert(st.wayHits.forall(_ > 0),
        s"not every way produced a HIT (${st.wayHits.mkString(",")}) -- a " +
        s"per-way capture bug (e.g. tagQ(w) sourced from the wrong way) could hide " +
        s"in an unexercised way (${st.describe})")
      info(name + ": " + st.describe)
      out = st
    }
    out
  }

  // ── The sweeps ────────────────────────────────────────────────────────────────

  test("verdict: sequential misses and hits, prefetch on", VerilatorTest) {
    // DELIBERATE DEVIATION from the plan's literal `0x1000 + i*64` stimulus. Every one
    // of those addresses is 64-BYTE ALIGNED, so pc(5) and pc(4:3) are constant zero and
    // the whole sweep would exercise beat 0 / lane 0 only -- exactly the
    // accidental-alignment blind spot Task 5's review caught in M1a's own shadow check.
    // The `+ (i % 8) * 8` walks all 8 windows (both beats, all four lanes) while still
    // touching one distinct line per command.
    sweep("shadow-seq-pf-on", (0 until 128).map(i => 0x1000L + i * 64 + (i % 8) * 8),
          prefetch = true)
  }

  test("verdict: same-set thrash forcing evictions, prefetch off", VerilatorTest) {
    // 0x1000, 0x2000, 0x3000, 0x4000, 0x5000 all map to set 0 (set = pc(11:6)).
    //
    // MEASURED CORRECTION to the plan's literal stimulus, which cycles all FIVE tags
    // every iteration: five distinct tags into a 4-way set with a round-robin victim
    // pointer is the textbook thrash, and it produces a 100 % MISS stream -- observed,
    // `hits=0 misses=200`. A shadow verdict hardwired to "miss" would have passed it.
    // So the four tags that FIT are the steady state (they hit, and because the victim
    // pointer walks, they hit in all four ways in turn) and the fifth tag is injected
    // every fifth iteration to force a real eviction and re-miss. That gives both
    // verdict polarities, continuous `validsQ`/`tagQ` churn, and all-four-way coverage.
    val thrash = (0 until 40).flatMap { i =>
      val core = Seq(0x1000L, 0x2000L, 0x3000L, 0x4000L).map(_ + (i % 8) * 8)
      if (i % 5 == 4) core :+ (0x5000L + (i % 8) * 8) else core
    }
    val st = sweep("shadow-thrash-pf-off", thrash, prefetch = false, requireAllWays = true)
    assert(st.beatSeen.forall(_ > 0) && st.laneSeen.forall(_ > 0),
      s"the thrash sweep did not cover both beats and all four lanes: ${st.describe}")
  }

  test("verdict: repeated hits in one line (the II=1 case)", VerilatorTest) {
    sweep("shadow-hits", (0 until 200).map(i => 0x1000L + (i % 8) * 8), prefetch = false)
  }

  // ── The cross-set / cross-tag case ────────────────────────────────────────────
  //
  // WHY THIS TEST EXISTS, and it is the single most important coverage lesson of this
  // task. The three sweeps above CANNOT catch a wrong INDEX on the `tagQ`/`validsQ`
  // capture -- proven by experiment, not argued: mutating `tagQ(w) := lookupTags(w)`
  // into `tagQ(w) := tagMem(w).readAsync(s0Set)` (a one-command-stale set index) left
  // all of them GREEN. Two independent reasons conspire:
  //   - the sequential sweep walks lines inside ONE 4 KiB page, and every line of a
  //     page has the SAME physical tag, so reading a neighbouring set's tag still
  //     compares equal;
  //   - the thrash sweep is deliberately confined to set 0, so the stale index IS the
  //     right index.
  // This test alternates two addresses that differ in BOTH set AND tag on every single
  // command, which makes a stale set index change the verdict immediately. Its second
  // phase then builds the one state in which the VALID bit alone decides the verdict --
  // an invalidated set whose `tagMem` entry still matches -- because without that a
  // `validsQ` capture bug is equally invisible (the tag compare would reject anyway).
  test("verdict: alternating set AND tag on every command, plus a valid-bit-decided " +
       "miss over a stale matching tag", VerilatorTest) {
    compiled.doSim("shadow-cross-set") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      dut.clockDomain.waitSampling(5)
      // Prefetch off: an alternating two-page stream resets the frontier constantly and
      // would make residency (and therefore the hit/miss mix) seed-dependent.
      dut.icache.logic.prefetchEnable  #= false
      dut.clockDomain.waitSampling(2)

      val st = startMonitor(dut)
      // A: set 0, tag 0x001.   B: set 1, tag 0x002.  Different in both, every command.
      val A = 0x1000L
      val B = 0x2040L
      assert(((A >> 6) & 63) != ((B >> 6) & 63) && (A >> 12) != (B >> 12),
        "stimulus bug: A and B must differ in BOTH set and tag")

      // Phase 1: alternate A/B, walking all 8 windows of each line as we go.
      for (i <- 0 until 60) {
        IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, A + (i % 8) * 8)
        IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, B + (i % 8) * 8)
      }
      assert(st.hits > 100 && st.misses >= 2,
        s"phase 1 did not produce the expected alternating hit stream: ${st.describe}")

      // Phase 2: clear the valid bits (tagMem KEEPS its content -- invalidateAll only
      // touches `valids`), refill A so set 0 is valid again, then fetch B. B's set is
      // now INVALID while its `tagMem` entry still equals its PPN, so the valid bit and
      // nothing else decides the verdict -- and the preceding command (A) was a HIT, so
      // a `validsQ` captured from the wrong place would say HIT here.
      for (round <- 0 until 4) {
        dut.icache.logic.invalidateAll #= true
        dut.clockDomain.waitSampling()
        dut.icache.logic.invalidateAll #= false
        dut.clockDomain.waitSampling(4)
        val setB = ((B >> 6) & 63).toInt
        assert((0 until 4).exists(w => IcacheArrayProbe.wayTag(dut.icache, w, setB) == BigInt(B >> 12)),
          s"round $round: set $setB lost its stale matching tag, so the valid bit is no " +
          s"longer the deciding factor and this phase proves nothing")
        IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, A)  // refill set 0
        IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, A)  // hit, set 0
        IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, B)  // <-- decisive
      }
      dut.clockDomain.waitSampling(20)
      assert(st.validDecidedMiss >= 4,
        s"the valid-bit-decided miss never occurred (${st.describe}) -- a validsQ " +
        s"capture bug would be invisible to this suite")
      assert(st.modelChecks >= st.checked / 2,
        s"the independent array model ran on only ${st.modelChecks} of ${st.checked} " +
        s"checked commands (${st.describe})")
      info("shadow-cross-set: " + st.describe)
    }
  }

  // ── The INHIBITED / fault directed case ───────────────────────────────────────
  //
  // WHY THIS EXISTS AND WHAT IT IS LOAD-BEARING FOR. Under `IdentityTranslationPlugin`
  // the cache mode is hardcoded WRITETHROUGH, so `s0Cacheable` is a constant True and
  // the `s0Cacheable &&` term of `s1HitVec` is completely untested by the three sweeps
  // above -- deleting it would leave them all green. The same is true of the
  // translation-fault path, which is the ONE case the plan's own text proposed to
  // EXEMPT from the equivalence check; this test is the evidence that no exemption is
  // needed (see the "NO FAULT EXEMPTION" note in IcachePlugin.scala).
  test("verdict: an INHIBITED page over a RESIDENT line, and a translation fault, " +
       "both agree with the independent array model", VerilatorTest) {
    compiledCmode.doSim("shadow-inhibited-and-fault") { dut =>
      val xlate = dut.xlate.asInstanceOf[ICacheModeTranslationPlugin]
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x2000)
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      xlate.logic.forceFault           #= false
      xlate.logic.cmodeEn              #= false
      dut.clockDomain.waitSampling(5)
      dut.icache.logic.prefetchEnable  #= false
      dut.clockDomain.waitSampling(2)

      val st = startMonitor(dut)
      val base = 0x1000L

      // 1) Make the line RESIDENT and cacheable, and re-hit every window in it.
      for (win <- 0 until 8)
        IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, base + win * 8)
      dut.clockDomain.waitSampling(10)
      val set = ((base >> 6) & 63).toInt
      assert((0 until 4).exists(w => IcacheArrayProbe.wayValid(dut.icache, w, set)),
        f"setup: line 0x$base%x did not become resident in set $set")
      val hitsAfterWarm = st.hits
      assert(hitsAfterWarm > 0, s"setup produced no hits at all: ${st.describe}")

      // 2) Flip the SAME page to INHIBITED. The live path reports MISS (lookupCacheable
      //    is false) even though `validsQ`/`tagQ` still match -- so if the shadow had
      //    dropped its `s0Cacheable &&` term it would say HIT and diverge here.
      xlate.logic.cmodeEn  #= true
      xlate.logic.cmodeVpn #= (base >> 12)
      xlate.logic.cmodeSel #= CacheMode.INHIBITED
      dut.clockDomain.waitSampling(2)
      for (win <- 0 until 8)
        IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, base + win * 8)
      dut.clockDomain.waitSampling(10)
      assert(st.inhibited >= 8,
        s"the INHIBITED window was not exercised (${st.describe}) -- the s0Cacheable " +
        s"term of the shadow verdict is then untested")

      // 3) Back to cacheable, then FAULT the same (still resident) address. The live
      //    `isHit` is genuinely TRUE here (the tag compare does not know about the
      //    fault), so this is the case the plan proposed to exempt: it is proof the
      //    un-exempted check holds.
      xlate.logic.cmodeEn #= false
      dut.clockDomain.waitSampling(2)
      IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, base)
      val faultsBefore = st.faults
      xlate.logic.forceFault #= true
      dut.clockDomain.waitSampling(2)
      for (win <- 0 until 8) {
        val rsp = IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain,
                                                 base + win * 8)
        assert(rsp.fault, s"setup: the fetch was supposed to fault: ${rsp.describe}")
      }
      xlate.logic.forceFault #= false
      dut.clockDomain.waitSampling(10)
      assert(st.faults - faultsBefore >= 8,
        s"the translation-fault window was not exercised (${st.describe})")
      assert(st.modelChecks >= st.checked / 2,
        s"the independent array model ran on only ${st.modelChecks} of ${st.checked} " +
        s"checked commands (${st.describe})")
      info("shadow-inhibited-and-fault: " + st.describe)
    }
  }

  // ── SG-4: an invalidateAll landing between S0 and S1 ──────────────────────────
  test("SG-4: an invalidateAll landing between S0 and S1 leaves the accepted fetch's " +
       "verdict identical to pre-M3 behaviour", VerilatorTest) {
    compiled.doSim("sg4-invalidate-window") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x2000)
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      dut.clockDomain.waitSampling(5)
      dut.icache.logic.prefetchEnable  #= false
      dut.clockDomain.waitSampling(2)

      // The model leg is disabled here: it reads the arrays one cycle late on purpose,
      // and this test's entire point is to clobber them in exactly that window. The
      // verdict is checked directly below instead.
      val st = startMonitor(dut, model = false)

      IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, 0x1000L)
      dut.clockDomain.waitSampling(10)
      val hitsBefore = st.hits

      // Accept a fetch to the resident line, then pulse invalidateAll on the very next
      // cycle -- i.e. between S0 and S1.
      //
      // EXPECTED, and this is the whole point: the verdict is UNCHANGED from today.
      // `valids` is a register array, so a clear issued at cycle N takes effect for
      // cycle N+1. Today's live path reads `valids` at cycle N (the accept cycle) and
      // sees the OLD value; M3's `validsQ` is captured at cycle N and holds the same OLD
      // value. The observable window is identical -- M3 neither opens nor closes one.
      // CINV/CPUSH is architecturally a software synchronisation point, so a fetch
      // already accepted when the invalidate lands is entitled to its data.
      dut.probe.logic.cmdIn.payload.pc #= 0x1000L
      dut.probe.logic.cmdIn.valid      #= true
      dut.clockDomain.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean)
      dut.probe.logic.cmdIn.valid    #= false
      dut.icache.logic.invalidateAll #= true
      dut.clockDomain.waitSampling()
      dut.icache.logic.invalidateAll #= false

      // M3b: the `dbgVerdictMatch` assertion that stood here compared two verdicts;
      // there is only one now. The property it was standing in for is unchanged and is
      // checked DIRECTLY: the accepted fetch's verdict must still be HIT, i.e. the
      // invalidate that landed one cycle after acceptance did NOT retroactively turn an
      // already-decided hit into a miss. `valids` is a register array (a clear issued in
      // cycle N takes effect for N+1) and `validsQ` was captured in cycle N, so the
      // observable window is byte-for-byte the pre-M3 one -- M3 neither opens nor
      // closes one. This doubles as the NON-VACUITY check: had it been a miss, the test
      // would prove nothing about the window at all.
      assert(dut.icache.logic.s1Hit.toBoolean,
        "SG-4: the fetch accepted immediately before the invalidateAll did not produce " +
        "a HIT verdict at S1. Either nothing was clobbered between S0 and S1 (the test " +
        "is vacuous) or -- worse -- the invalidate retroactively changed a verdict that " +
        "had already been decided for an accepted command, which would turn an " +
        "answerable fetch into a fill.")

      // And the response must still arrive -- a dropped response wedges FetchAlign's
      // ring permanently (ringCount never decrements).
      var sawRsp = false
      var rspPc  = -1L
      for (_ <- 0 until 200 if !sawRsp) {
        dut.clockDomain.waitSampling()
        if (dut.probe.logic.rspOut.valid.toBoolean) {
          sawRsp = true
          rspPc  = dut.probe.logic.rspOut.payload.pc.toLong
        }
      }
      assert(sawRsp, "the accepted fetch never got a response after a mid-flight invalidateAll")
      // ...and it must be THAT fetch's response, carrying the pre-invalidate data path
      // (a hit answered from the array, not a refill of a line the invalidate dropped).
      assert(rspPc == 0x1000L,
        f"the response after the mid-flight invalidateAll carried pc=0x$rspPc%x, not " +
        f"the accepted fetch's 0x1000")
      assert(st.hits > hitsBefore, s"SG-4 observed no hit verdict at all: ${st.describe}")
      // The invalidate must actually have taken effect, or the "window" never existed.
      val set = 0x1000 >> 6 & 63
      assert(!(0 until 4).exists(w => IcacheArrayProbe.wayValid(dut.icache, w, set)),
        "SG-4: invalidateAll did not clear the valid bits, so no S0/S1 window was opened")
      info("sg4: " + st.describe)
    }
  }

  // ── M3b (Task 11): the two properties the FLIP must preserve ──────────────────
  // `cmdPort.ready` is now gated by `s1Unresolved` instead of by a live translation-
  // fed comparator. Two properties must survive that, and they pull in OPPOSITE
  // directions -- which is why both are tested, and why both are written to fail
  // loudly rather than to be re-derived by inspection:
  //
  //   - the gate must be LOW on a hit, or every straight-line fetch loses an issue
  //     slot (spec goal G2, sustained II=1);
  //   - the gate must be HIGH from the cycle a miss is DISCOVERED (S1) until the fill
  //     resolves, or a younger command is accepted behind an older unresolved miss --
  //     and `FetchRsp` carries no tag while `FetchAlignPlugin` attributes responses by
  //     ring head, so that is silent instruction-byte mis-pairing (risk R3).
  //
  // Both PASS on the pre-flip RTL too (today's `answerable` already refuses to admit a
  // younger command behind a miss, and today's hit path is already II=1): they are
  // REGRESSION DETECTORS for the flip, not new-behaviour tests.
  test("M3b: sustained II=1 on hits (ready never drops in a hit stream)", VerilatorTest) {
    compiled.doSim("m3b-ii1") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x2000)
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      dut.clockDomain.waitSampling(5)
      dut.icache.logic.prefetchEnable  #= false
      dut.clockDomain.waitSampling(2)

      IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, 0x1000L)
      dut.clockDomain.waitSampling(20)

      // Hold cmd.valid high on a RESIDENT line for 100 cycles and count accepts.
      dut.probe.logic.cmdIn.payload.pc #= 0x1000L
      dut.probe.logic.cmdIn.valid      #= true
      var accepts = 0
      for (_ <- 0 until 100) {
        dut.clockDomain.waitSampling()
        if (dut.probe.logic.cmdIn.ready.toBoolean) accepts += 1
      }
      dut.probe.logic.cmdIn.valid #= false
      assert(accepts >= 95,
        s"only $accepts of 100 cycles accepted a command on a RESIDENT line. M3's " +
        s"s1Unresolved gate must be low on hits -- sustained II=1 is spec goal G2 and " +
        s"a regression here is a direct IPC loss on every straight-line fetch.")
      info(s"m3b-ii1: $accepts/100 cycles accepted")
    }
  }

  // ── M3b: the ACCEPT-CYCLE hole that `s1Unresolved` alone does not cover ──────────
  // The deleted live `heldDemandMiss` was TRUE ON THE ACCEPT CYCLE of a miss, and one
  // of its jobs was to freeze the prefetch allocator for exactly that cycle.
  // `s1Unresolved` is its successor but is one cycle LATER, so the accept cycle itself
  // is uncovered -- and on that cycle the allocator can still allocate a speculative
  // slot to `pfCandSet`. When the frontier has stalled with `pfNextPa` sitting exactly
  // ON the line the demand is now accepting (prefetch disabled, or no free slot, so it
  // never advanced), `pfCandSet == lookupSet` and the two fills end up owning the SAME
  // SET -- for the same LINE, in fact, which installs two valid ways with one tag and
  // makes `s1HitVec` 2-hot. `IcachePlugin.scala`'s `demandSetOwned` carries a third
  // lane, `cmdPort.fire && (lookupSet === pfCandSet)`, for precisely this cycle;
  // MUTATION-PROVEN: `&& False`-ing that lane makes this test report violations.
  test("M3b: a demand accepted on the very cycle the frontier points AT its own line " +
       "must not allocate a second owner of that set", VerilatorTest) {
    compiled.doSim("m3b-accept-cycle-frontier") { dut =>
      val ic = dut.icache
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(ic.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      ic.logic.invalidateAll           #= false
      dut.clockDomain.waitSampling(5)
      // Prefetch OFF: `seedPfWindow` still runs on every accepted cacheable command, so
      // the frontier is SEEDED at 0x2040 but `pfWindowHasCandidate` is false and it
      // never advances off that line.
      ic.logic.prefetchEnable #= false
      dut.clockDomain.waitSampling(2)
      IcacheFetchDriver.fetchAndWait(ic, dut.probe, dut.clockDomain, 0x2000L)
      dut.clockDomain.waitSampling(20)

      var violations = 0
      var firstDup   = ""
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          val live = (0 until IcacheArrayProbe.mshrEntries)
            .filter(i => IcacheArrayProbe.mshrValid(ic, i))
          val sets = live.map(i => IcacheArrayProbe.mshrSet(ic, i))
          if (sets.distinct.length != sets.length) {
            violations += 1
            if (firstDup.isEmpty)
              firstDup = live.map(i => s"entry$i:set${IcacheArrayProbe.mshrSet(ic, i)}").mkString(" ")
          }
        }
      }

      // Both pokes land on the same edge, so the FIRST cycle in which the allocator
      // sees a candidate is also the cycle the demand for that same line is accepted.
      dut.probe.logic.cmdIn.payload.pc #= 0x2040L
      ic.logic.prefetchEnable          #= true
      dut.probe.logic.cmdIn.valid      #= true
      dut.clockDomain.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      dut.clockDomain.waitSampling(400)

      assert(violations == 0,
        s"two live MSHR entries owned the same set on $violations cycles ($firstDup). " +
        s"A speculative slot was allocated to the set of a demand that was accepted on " +
        s"the SAME cycle -- both then fill it, and if they are the same line the set " +
        s"ends up with two valid ways carrying one tag, i.e. a 2-hot s1HitVec feeding " +
        s"the response's one-hot data/predecode select.")
    }
  }

  test("M3b: no younger command is accepted behind an unresolved miss", VerilatorTest) {
    compiled.doSim("m3b-no-overtake") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      dut.clockDomain.waitSampling(5)
      dut.icache.logic.prefetchEnable  #= false
      dut.clockDomain.waitSampling(2)

      // Command A misses (cold line). Immediately offer command B to a DIFFERENT,
      // ALREADY-RESIDENT line. B must not be accepted until A's fill has resolved,
      // or its response would be attributed to A's ring entry.
      IcacheFetchDriver.fetchAndWait(dut.icache, dut.probe, dut.clockDomain, 0x2000L)
      dut.clockDomain.waitSampling(20)

      dut.probe.logic.cmdIn.payload.pc #= 0x5000L      // cold
      dut.probe.logic.cmdIn.valid      #= true
      dut.clockDomain.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean)  // A accepted
      dut.probe.logic.cmdIn.payload.pc #= 0x2000L      // resident -- would HIT

      // ── DEVIATION FROM THE PLAN'S LITERAL TEST, and it is a REAL off-by-one in the
      // plan's text, not a concession. The plan's window runs "until `rspOut.valid`".
      // But `rspValidReg` is an OUTPUT REGISTER: REPLAY arms `s0Valid` in cycle M, the
      // machine is back in IDLE in cycle M+1 -- where it legitimately accepts B -- and
      // A's response only becomes VISIBLE on `rspOut.valid` in cycle M+2. So there is
      // exactly ONE cycle in which B is accepted while A's response is already
      // committed to the output register but not yet observable, and the plan's literal
      // window counts it as a violation. It is not one: A's response is emitted BEFORE
      // B's by construction, which is the actual in-order contract.
      //
      // The window is therefore cut at `dbgAllocCommitCycle` -- the cycle PREDECODE
      // commits the refilled line -- which is the last cycle of the miss proper and is
      // strictly BEFORE REPLAY/IDLE. Between it and the accept of B the fetch port is
      // structurally closed (neither PREDECODE-for-a-demand-install nor REPLAY calls
      // `lookupTick`), so nothing is exempted that could actually overtake. The
      // response's PC is additionally checked, so the in-order claim is observed and
      // not merely inferred.
      var acceptedDuringMiss = 0
      var sawRsp   = false
      var resolved = false
      var rspPc    = -1L
      // TIMING, verified empirically rather than assumed (an earlier draft of this test
      // got it wrong and reported a false violation): `waitSamplingWhere` resumes at the
      // SAMPLING POINT, i.e. BEFORE the edge that accepts A, and a poke issued there
      // applies only AFTER that edge (this is exactly why `IcacheFetchDriver.fetchAndWait`
      // can deassert `valid` on the line right after its own `waitSamplingWhere` without
      // un-accepting the command). So a read taken here would still return cycle N's
      // values, and the loop's FIRST iteration is cycle N+1 -- the cycle A's miss is
      // discovered at S1, and the single most important cycle in this test.
      for (_ <- 0 until 400 if !sawRsp) {
        dut.clockDomain.waitSampling()
        if (dut.probe.logic.rspOut.valid.toBoolean) {
          sawRsp = true
          rspPc  = dut.probe.logic.rspOut.payload.pc.toLong
        } else {
          if (!resolved && dut.probe.logic.cmdIn.ready.toBoolean) acceptedDuringMiss += 1
          if (dut.icache.logic.dbgAllocCommitCycle.toBoolean) resolved = true
        }
      }
      dut.probe.logic.cmdIn.valid #= false
      assert(sawRsp, "command A never got a response")
      assert(resolved, "the refill never committed a line -- the test never reached its " +
        "own window boundary, so it proved nothing")
      assert(rspPc == 0x5000L,
        f"the FIRST response after the miss carried pc=0x$rspPc%x, not command A's " +
        f"0x5000 -- a younger command's response overtook an older one. FetchRsp " +
        f"carries no tag and FetchAlignPlugin attributes by ring head, so this is " +
        f"silent instruction-byte mis-pairing (risk R3).")
      assert(acceptedDuringMiss == 0,
        s"$acceptedDuringMiss younger commands were accepted while an older miss was " +
        s"unresolved. FetchRsp carries no tag and FetchAlignPlugin attributes by ring " +
        s"head (FetchAlignPlugin.scala:263,283), so this is silent instruction-byte " +
        s"mis-pairing -- risk R3, the most expensive failure mode in this file.")
    }
  }
}
