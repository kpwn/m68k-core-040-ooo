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

/** M3a (implementation plan Task 10) shadow check: the S1 verdict computed from the
  * REGISTERED S0 context (`tagQ`, `validsQ`, `s0Ppn`, `s0Cacheable`) must equal the LIVE
  * accept-cycle verdict (`isHit`), delayed one cycle, on every accepted command.
  *
  * ── WHY A SHADOW ────────────────────────────────────────────────────────────────
  * Spec risk R3: M3 restructures the accept/verdict boundary that guarantees the
  * in-order response contract, and a violation is SILENT -- `FetchRsp` carries no tag
  * and `FetchAlignPlugin` attributes every response to its outstanding ring's HEAD, so
  * a wrong verdict shows up as mis-paired instruction bytes, not as a crash. Building
  * the registered path in parallel and asserting equivalence BEFORE any consumer
  * depends on it turns "did I get the registered verdict right?" into a question the
  * existing 396-test lock-step suite answers, instead of a question a post-flip
  * debugging session answers.
  *
  * ── WHY THIS IS NOT A TAUTOLOGY ─────────────────────────────────────────────────
  * Task 5's review found the design spec's own literal suggested check would have been
  * one (a signal compared against the expression it is assigned from). This check has
  * THREE independent legs, and only the first is an RTL-internal comparison:
  *
  *  1. `dbgVerdictMatch` (in RTL, and additionally re-checked here): 1 bit latched
  *     BEFORE the compare tree vs. 105 bits latched and the compare tree re-evaluated
  *     AFTER. Different storage, different evaluation order.
  *  2. A pure-Scala INDEPENDENT MODEL below re-derives the verdict from the raw arrays
  *     (`IcacheArrayProbe.wayValid`/`wayTag`) and the registered S0 context, and checks
  *     BOTH the shadow AND the live verdict against it. Neither RTL path participates.
  *  3. Coverage counters that make each of the above non-vacuous: both verdict
  *     polarities, all four ways, both beats, all four lanes, the INHIBITED
  *     (`s0Cacheable == false`) case and the translation-fault case must all be
  *     observed, or the test fails as "barely exercised".
  *
  * Deleted in Task 11 (M3b) together with `dbgVerdictMatch`/`dbgS0Fresh`/`dbgLiveHitQ`
  * and the live comparator.
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

  // ── Shadow monitor ────────────────────────────────────────────────────────────
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

  /** Fork the per-cycle shadow monitor.
    *
    * TIMING DISCIPLINE, which is the whole reason this is written the way it is.
    * SpinalSim resumes a thread at the SAMPLING POINT, i.e. just before the clock edge
    * that latches the registers (`IcachePlugin.scala`'s `dbgAllocCommitPending` comment
    * states this explicitly). So `cmdIn.valid`/`cmdIn.ready` read at a sampling point
    * race the command driver's own poke for the NEXT cycle. This monitor therefore
    * never reads a driven input to decide anything: it triggers on `dbgS0Fresh`, a
    * REGISTER that is high exactly on the cycle after `cmdPort.fire`, and reads only
    * registers and array content.
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
        if (ic.logic.dbgS0Fresh.toBoolean) {
          st.checked += 1
          val live   = ic.logic.dbgLiveHitQ.toBoolean
          val shadow = ic.logic.s1Hit.toBoolean
          val pc     = ic.logic.s0Pc.toLong
          val set    = ic.logic.s0Set.toInt
          val ppn    = ic.logic.s0Ppn.toBigInt
          val cache  = ic.logic.s0Cacheable.toBoolean
          val flt    = ic.logic.s0Fault.toBoolean
          if (!ic.logic.dbgVerdictMatch.toBoolean || live != shadow)
            simFailure(
              f"M3a SHADOW VIOLATED at pc=0x$pc%x (set=$set ppn=0x${ppn.toString(16)} " +
              f"cacheable=$cache fault=$flt): the S1 verdict computed from registered " +
              f"inputs (tagQ/validsQ/s0Ppn/s0Cacheable) says hit=$shadow but the live " +
              f"accept-cycle isHit said hit=$live. M3 cannot flip until these are " +
              f"identical -- risk R3's failure mode is silent instruction-byte " +
              f"mis-pairing, not a crash.")
          if (live) {
            st.hits += 1
            for (w <- 0 until 4) if (ic.logic.s1HitVec(w).toBoolean) st.wayHits(w) += 1
          } else st.misses += 1
          if (flt) st.faults += 1
          if (!cache) st.inhibited += 1
          st.beatSeen(if (ic.logic.s0Beat.toBoolean) 1 else 0) += 1
          st.laneSeen(ic.logic.s0Lane.toInt) += 1

          if (model) {
            if (prevArrayWrite) st.modelSkips += 1
            else {
              // INDEPENDENT MODEL: neither the live comparator nor the shadow one is
              // consulted -- the verdict is re-derived from the raw arrays.
              val exp = cache && (0 until 4).exists(w =>
                IcacheArrayProbe.wayValid(ic, w, set) &&
                IcacheArrayProbe.wayTag(ic, w, set) == ppn)
              if (!exp && cache &&
                  (0 until 4).exists(w => IcacheArrayProbe.wayTag(ic, w, set) == ppn))
                st.validDecidedMiss += 1
              st.modelChecks += 1
              if (exp != shadow)
                simFailure(
                  f"M3a MODEL DISAGREES WITH THE SHADOW at pc=0x$pc%x: array content " +
                  f"for set=$set says hit=$exp, the registered S1 verdict says " +
                  f"hit=$shadow (ppn=0x${ppn.toString(16)} cacheable=$cache)")
              if (exp != live)
                simFailure(
                  f"M3a MODEL DISAGREES WITH THE LIVE VERDICT at pc=0x$pc%x: array " +
                  f"content for set=$set says hit=$exp, the live accept-cycle isHit " +
                  f"said hit=$live -- the model itself is wrong, or the live path is")
            }
          }
        }
        prevArrayWrite = ic.logic.dbgAllocCommitCycle.toBoolean ||
                         dut.icache.logic.invalidateAll.toBoolean
      }
    }
    st
  }

  /** Drive `addrs` through the ordered-stream driver with the shadow monitor running. */
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
        s"${addrs.length} commands issued -- the shadow was barely exercised, so it " +
        s"proved nothing (${st.describe})")
      if (requireHits) assert(st.hits > 0,
        s"no HIT verdict was ever observed: a shadow stuck at 'miss' would pass " +
        s"vacuously (${st.describe})")
      if (requireMisses) assert(st.misses > 0,
        s"no MISS verdict was ever observed: a shadow stuck at 'hit' would pass " +
        s"vacuously (${st.describe})")
      assert(st.modelChecks >= st.checked / 2,
        s"the independent array model ran on only ${st.modelChecks} of ${st.checked} " +
        s"checked commands (${st.modelSkips} skipped for a concurrent array write) " +
        s"-- too few to be meaningful (${st.describe})")
      if (requireAllWays) assert(st.wayHits.forall(_ > 0),
        s"not every way produced a shadow HIT (${st.wayHits.mkString(",")}) -- a " +
        s"per-way capture bug (e.g. tagQ(w) sourced from the wrong way) could hide " +
        s"in an unexercised way (${st.describe})")
      info(name + ": " + st.describe)
      out = st
    }
    out
  }

  // ── The sweeps ────────────────────────────────────────────────────────────────

  test("M3a shadow: sequential misses and hits, prefetch on", VerilatorTest) {
    // DELIBERATE DEVIATION from the plan's literal `0x1000 + i*64` stimulus. Every one
    // of those addresses is 64-BYTE ALIGNED, so pc(5) and pc(4:3) are constant zero and
    // the whole sweep would exercise beat 0 / lane 0 only -- exactly the
    // accidental-alignment blind spot Task 5's review caught in M1a's own shadow check.
    // The `+ (i % 8) * 8` walks all 8 windows (both beats, all four lanes) while still
    // touching one distinct line per command.
    sweep("shadow-seq-pf-on", (0 until 128).map(i => 0x1000L + i * 64 + (i % 8) * 8),
          prefetch = true)
  }

  test("M3a shadow: same-set thrash forcing evictions, prefetch off", VerilatorTest) {
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

  test("M3a shadow: repeated hits in one line (the II=1 case)", VerilatorTest) {
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
  test("M3a shadow: alternating set AND tag on every command, plus a valid-bit-decided " +
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
  test("M3a shadow: an INHIBITED page over a RESIDENT line, and a translation fault, " +
       "both keep the shadow identical to the live verdict", VerilatorTest) {
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
      // and this test's entire point is to clobber them in exactly that window. The RTL
      // shadow-equivalence leg (`dbgVerdictMatch`) is unaffected and still checked.
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

      assert(dut.icache.logic.dbgVerdictMatch.toBoolean,
        "SG-4: the registered verdict diverged from the live one across an " +
        "invalidateAll landing between S0 and S1")
      // NON-VACUITY: the accepted fetch must have been a HIT, i.e. the invalidate really
      // did land on a verdict that had already been decided. If it had been a miss this
      // test would prove nothing about the window at all.
      assert(dut.icache.logic.s1Hit.toBoolean,
        "SG-4 is vacuous: the fetch accepted immediately before the invalidateAll did " +
        "not produce a HIT verdict, so nothing was clobbered between S0 and S1")

      // And the response must still arrive -- a dropped response wedges FetchAlign's
      // ring permanently (ringCount never decrements).
      var sawRsp = false
      for (_ <- 0 until 200 if !sawRsp) {
        dut.clockDomain.waitSampling()
        if (dut.probe.logic.rspOut.valid.toBoolean) sawRsp = true
      }
      assert(sawRsp, "the accepted fetch never got a response after a mid-flight invalidateAll")
      assert(st.hits > hitsBefore, s"SG-4 observed no hit verdict at all: ${st.describe}")
      // The invalidate must actually have taken effect, or the "window" never existed.
      val set = 0x1000 >> 6 & 63
      assert(!(0 until 4).exists(w => IcacheArrayProbe.wayValid(dut.icache, w, set)),
        "SG-4: invalidateAll did not clear the valid bits, so no S0/S1 window was opened")
      info("sg4: " + st.describe)
    }
  }
}
