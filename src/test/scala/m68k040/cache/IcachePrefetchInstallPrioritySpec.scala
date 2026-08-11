package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Slice 1a (design spec section 3 boundary B3, hazard H9): a completed speculative
  * install and a fresh demand miss arriving while it is pending must BOTH complete,
  * install first, and the demand must be admitted within the bounded wait.
  *
  * Before slice 1a, `lineReg`'s capture enable was `pfInstallAny && !demandFillStart`
  * -- a function of the LIVE `cmdPort.fire` verdict -- so a same-cycle demand miss
  * always won outright and no bounded-wait counter existed at all (no register lag,
  * ever). After slice 1a the install is armed from a register (`pfInstallArm`) and a
  * held demand yields to it, bounded by `installDeferBound` = `pfSlots * 3` = 12
  * counted cycles (hazard H9, `installDeferCnt`; `installStarves`/`installDeferMax`
  * survive only as a telemetry threshold and gate nothing -- the arming gate they
  * used to drive was removed in commit e596c9b because it could deadlock the very
  * case it guarded). Because `pfInstallArm` is always one cycle behind the live
  * `pfInstallAny` it mirrors, the demand rides out one extra register-latency cycle
  * beyond the pre-slice-1a scheduler's same-cycle tie break every single time it is
  * genuinely raced against an armed install. That mechanistic,
  * register-vs-combinational difference is what this test measures. It is NOT a
  * measurement of "how many installs chain": the bound now covers a full drain of
  * the pool (every slot installing ahead of the held demand, 3 counted cycles each),
  * so a chain of queued installs is legal traffic under it, not a violation.
  *
  * The AXI side is driven by hand (`sendLine`/`waitReq`, mirroring the
  * `IcachePrefetchSpec` "AR-pending demotion"/"invalidation poisons" tests) rather
  * than the shared zero-latency `AxiMemModel`, for exact control over exactly when a
  * speculative line's data lands relative to the demand's own presentation.
  */
class IcachePrefetchInstallPrioritySpec extends AnyFunSuite {

  class Dut extends Component {
    val db     = new Database
    val host   = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new IdentityTranslationPlugin
    val icache = new IcachePlugin
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  /** Review round 1 minor: the hand-driven AXI helpers below used unbounded blocking
    * waits. They sit AFTER this file's own bounded-wait detector, so the H9 deadlock
    * they exist to catch still fails cleanly -- but any OTHER regression that stopped
    * the R channel or the AR stream would wedge the JVM instead of failing. These two
    * caps make every wait in this file terminate with a diagnosable assertion.
    * 4000 cycles is ~50x a single line's service time through this harness. */
  private val waitCapCycles = 4000

  /** `cd.waitSamplingWhere` semantics (sample first, THEN test), with a cap. */
  def waitSamplingWhereBounded(cd: ClockDomain, what: String)(cond: => Boolean): Unit = {
    var waited = 0
    var done = false
    while (!done) {
      cd.waitSampling()
      waited += 1
      done = cond
      assert(done || waited < waitCapCycles,
        s"bounded sim wait expired after $waitCapCycles cycles waiting for: $what")
    }
  }

  /** Poll-first wait, with a cap. */
  def waitUntilBounded(cd: ClockDomain, what: String)(cond: => Boolean): Unit = {
    var waited = 0
    while (!cond) {
      assert(waited < waitCapCycles,
        s"bounded sim wait expired after $waitCapCycles cycles waiting for: $what")
      cd.waitSampling()
      waited += 1
    }
  }

  test("a completed install and a same-cycle demand miss both complete, install first, within the bounded wait",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd  = dut.clockDomain
      val axi = dut.icache.logic.axi
      cd.forkStimulus(10)

      // Two DIFFERENT sets so the install is not simply the line the demand wants:
      // streamBase's window occupies sets 0..4; demandPa's set (16) is untouched by
      // it, so the ONLY thing that can ever hold the demand is an armed install.
      val streamBase = 0x0000_2000L
      val demandPa   = streamBase + 0x400L
      require(((streamBase >> 6) & 0x3f) != ((demandPa >> 6) & 0x3f),
        "test setup bug: demandPa must land in a different cache set than streamBase's window")

      axi.ar.ready #= true
      axi.r.valid #= false
      axi.r.payload.data #= 0
      axi.r.payload.id #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0

      val arTrace = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
      cd.onSamplings {
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
          arTrace += ((axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong))
      }

      def beat(address: Long, phase: Int): BigInt =
        (0 until 32).foldLeft(BigInt(0)) { (acc, i) =>
          acc | (BigInt(IcacheSim.memByte(address + phase * 32L + i)) << (8 * i))
        }
      def sendLine(id: Int, address: Long): Unit = {
        for (phase <- 0 to 1) {
          axi.r.valid #= true
          axi.r.payload.id #= id
          axi.r.payload.data #= beat(address, phase)
          axi.r.payload.resp #= 0
          axi.r.payload.last #= (phase == 1)
          waitSamplingWhereBounded(cd, f"R ready for id $id line 0x$address%x phase $phase")(
            axi.r.ready.toBoolean)
          axi.r.valid #= false
          cd.waitSampling()
        }
      }
      def waitReq(id: Int, address: Long): Unit =
        waitUntilBounded(cd, f"AR id $id at 0x$address%x")(arTrace.contains((id, address)))

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      // Continuous, passive observation from a single fork (never poked into by the
      // main thread, so its samples are never entangled with the exact simulated-
      // delta ordering of the main thread's own `#=` pokes -- an earlier version of
      // this test tried to read state combinationally right after presenting the
      // demand in the SAME simulated instant and got inconsistent/stale reads for
      // exactly that reason; every measurement here instead comes from this one
      // free-running, clock-synchronous loop).
      var installSeen = 0   // total predIsPf-high samples, whole test (non-vacuity)
      var maxDefer     = 0   // peak installDeferCnt observed (the discriminating signal)
      val installFork = fork {
        while (true) {
          cd.waitSampling()
          if (dut.icache.logic.predIsPf.toBoolean) installSeen += 1
          val d = dut.icache.logic.installDeferCnt.toInt
          if (d > maxDefer) maxDefer = d
        }
      }

      // Phase 1: a demand miss at streamBase opens the standard five-line window --
      // its own line (ID_DEMAND) plus four speculative candidates (I_SPEC_BASE..LAST)
      // at +0x40/+0x80/+0xC0/+0x100. Resolve the demand's OWN line immediately so the
      // FSM returns to IDLE with all four speculative fills outstanding.
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= streamBase
      cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      waitReq(AxiIds.I_DEMAND, streamBase)
      sendLine(AxiIds.I_DEMAND, streamBase)
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      assert(dut.probe.logic.rspOut.payload.data.toBigInt == IcacheSim.window64(streamBase),
        "streamBase demand response data mismatch")

      for (k <- 0 to 3) waitReq(AxiIds.I_SPEC_BASE + k, streamBase + 0x40L * (k + 1))

      // Phase 2: complete ONE speculative line, then -- with no artificial gap --
      // present the cold, wholly unrelated demand while that install is in flight,
      // so it genuinely races an ARMED install rather than finding the machine idle.
      sendLine(AxiIds.I_SPEC_BASE, streamBase + 0x40L)

      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= demandPa
      cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      waitReq(AxiIds.I_DEMAND, demandPa)
      sendLine(AxiIds.I_DEMAND, demandPa)
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      val demandRspPc   = dut.probe.logic.rspOut.payload.pc.toLong
      val demandRspData = dut.probe.logic.rspOut.payload.data.toBigInt

      // Drain the remaining three speculative lines so the DUT is left quiescent.
      for (k <- 1 to 3) sendLine(AxiIds.I_SPEC_BASE + k, streamBase + 0x40L * (k + 1))
      cd.waitSampling(20)   // let any trailing installs settle
      installFork.terminate()

      // GC20 non-vacuity: exact counts/PCs, not merely "some activity happened". All
      // four speculative candidates from the streamBase window install exactly once
      // each (2-cycle PF_PRED dwell) -> exactly 8 predIsPf-high samples total.
      assert(installSeen == 8,
        s"expected exactly 4 completed slots x 2-cycle PF_PRED dwell = 8 predIsPf-high " +
          s"samples over the whole test, got $installSeen")
      assert(demandRspPc == demandPa, s"demand miss lost or mis-attributed: 0x${demandRspPc.toHexString}")
      assert(demandRspData == IcacheSim.window64(demandPa), "demand miss response data mismatch")
      // The RTL's own H9 oracle (in-RTL `assert`) already proves `installDeferCnt`
      // never exceeds `installDeferBound` = `pfSlots * 3` -- it would have aborted the
      // whole simulation otherwise -- so the upper bound here is a belt-and-braces
      // restatement of the same guarantee from the testbench side. (That bound was
      // `installDeferMax + 1` = 5 while `installStarves` still hard-gated the install
      // arm, which capped the counter at the threshold by construction; that gate was
      // a deadlock and is gone -- see the B3 1a regression test below.)
      //
      // THE mutation-discriminating assertion: `pfInstallArm` arms the IDLE decision
      // cycle, and the H9 counter also counts the two PF_PRED cycles that follow it
      // (see the RTL's own counter-update comment) -- three cycles total for a
      // demand that genuinely raced a single armed install to a full stop, which is
      // exactly `maxDefer` here. Reverting slice 1a restores the pre-slice-1a
      // same-cycle combinational tie-break (`pfInstallAny && !demandFillStart`,
      // decided in the very same `lookupTick` call that admits the demand): the
      // demand wins the FIRST genuinely-idle cycle outright instead of yielding to
      // the still-queued install, so it is admitted DURING that install's own
      // PF_PRED window rather than after it -- one cycle short of the full 3.
      assert(maxDefer == 3,
        s"H9 bounded-wait was not genuinely exercised to its full peak: maxDefer=$maxDefer " +
          s"(expected exactly 3: the IDLE arm-decision cycle plus the install's own " +
          s"2-cycle PF_PRED dwell). Topping out short of that is exactly what reverting " +
          s"slice 1a's `answerable`/`when(pfInstallArm)` change (the demand-always-wins, " +
          s"same-cycle-tie-break `pfInstallAny && !demandFillStart` scheduler) produces: " +
          s"the demand wins the tie and is admitted DURING the install's PF_PRED window " +
          s"instead of yielding until after it.")
    }
  }

  /** Slice 1a deadlock regression (B3): `pfInstallArm` must NOT be gated by
    * `installStarves`.
    *
    * The loop that gate creates: a demand held by `pfLookupSetBusy` against
    * speculative slot S is released only by S INSTALLING (`pfLookupSetBusy` tests
    * `pfValid(i)` alone -- a complete-but-uninstalled slot still asserts it). Once
    * `installStarves` latches while that demand is still held, arming stops, so S
    * never installs, so the hold never clears, so `heldDemandMiss` never falls -- and
    * the counter then neither resets (needs `!heldDemandMiss`) nor increments (needs
    * `pfInstallArm || predActive`, both false), so `installStarves` never clears
    * either. There is no internal escape: the AR-demotion path only accelerates slots
    * that have not yet sent an AR, and the only `pfValid` clear independent of the arm
    * is poison/error cleanup, which requires an EXTERNAL `anyInvalidate` or bus error.
    *
    * This test drives exactly that state -- `installStarves` genuinely latched (the
    * threshold is reached by two real install episodes, asserted, not assumed) while a
    * third, still-incomplete slot holds a demand -- then completes the blocking slot
    * and requires the demand to be admitted inside an explicit cycle budget. The
    * budget is a bounded polling loop rather than a blocking `waitSamplingWhere` on
    * purpose: still-deadlocked RTL must FAIL with a diagnosis, not wedge the test JVM.
    */
  test("a complete slot still installs after installStarves latches, so a demand held behind it is not deadlocked (B3 1a)",
       VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val cd  = dut.clockDomain
      val axi = dut.icache.logic.axi
      cd.forkStimulus(10)

      val streamBase = 0x0000_2000L
      // The window's slot 0 covers streamBase+0x40. `blockedPa` deliberately ALIASES
      // onto that same VIPT set with a different tag, so it is a genuine cold miss
      // whose ONLY obstacle is slot 0 still being live -- the exact `pfLookupSetBusy`
      // hold the deadlock needs. Slots 1..3 sit in other sets and never block it.
      val blockedPa  = 0x0000_3040L
      def setOf(pa: Long): Long = (pa >> 6) & 0x3f
      def tagOf(pa: Long): Long = pa >> 12
      require(setOf(blockedPa) == setOf(streamBase + 0x40L),
        "test setup bug: blockedPa must alias onto slot 0's cache set")
      require(tagOf(blockedPa) != tagOf(streamBase + 0x40L),
        "test setup bug: blockedPa must be a DIFFERENT line, not the same one slot 0 is fetching")
      require(Seq(0x80L, 0xC0L, 0x100L).forall(o => setOf(streamBase + o) != setOf(blockedPa)),
        "test setup bug: slots 1..3 must not also block the demand, or the test proves nothing about slot 0")

      axi.ar.ready #= true
      axi.r.valid #= false
      axi.r.payload.data #= 0
      axi.r.payload.id #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0

      val arTrace = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
      cd.onSamplings {
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
          arTrace += ((axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong))
      }

      def beat(address: Long, phase: Int): BigInt =
        (0 until 32).foldLeft(BigInt(0)) { (acc, i) =>
          acc | (BigInt(IcacheSim.memByte(address + phase * 32L + i)) << (8 * i))
        }
      def sendLine(id: Int, address: Long): Unit = {
        for (phase <- 0 to 1) {
          axi.r.valid #= true
          axi.r.payload.id #= id
          axi.r.payload.data #= beat(address, phase)
          axi.r.payload.resp #= 0
          axi.r.payload.last #= (phase == 1)
          waitSamplingWhereBounded(cd, f"R ready for id $id line 0x$address%x phase $phase")(
            axi.r.ready.toBoolean)
          axi.r.valid #= false
          cd.waitSampling()
        }
      }
      def waitReq(id: Int, address: Long): Unit =
        waitUntilBounded(cd, f"AR id $id at 0x$address%x")(arTrace.contains((id, address)))

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      // Single free-running passive observer (same rationale as the test above: never
      // sample combinationally from the poking thread in the same simulated instant).
      var maxDefer    = 0
      var starvesSeen = false
      var installSeen = 0
      val obs = fork {
        while (true) {
          cd.waitSampling()
          val d = dut.icache.logic.installDeferCnt.toInt
          if (d > maxDefer) maxDefer = d
          if (dut.icache.logic.installStarves.toBoolean) starvesSeen = true
          if (dut.icache.logic.predIsPf.toBoolean) installSeen += 1
        }
      }

      // Phase 1 -- open the five-line window and retire the demand's own line.
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= streamBase
      cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      waitReq(AxiIds.I_DEMAND, streamBase)
      sendLine(AxiIds.I_DEMAND, streamBase)
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      for (k <- 0 to 3) waitReq(AxiIds.I_SPEC_BASE + k, streamBase + 0x40L * (k + 1))
      assert((0 until AxiIds.I_SPEC_SLOTS).forall(i => dut.icache.logic.pfValid(i).toBoolean),
        "setup: all four speculative slots must be live before the demand is presented")

      // Phase 2 -- present the aliasing demand and HOLD it (valid stays asserted).
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= blockedPa
      // It must genuinely be blocked, not merely slow: no accept for 10 cycles while
      // slot 0 (its set owner) is still outstanding.
      for (c <- 0 until 10) {
        cd.waitSampling()
        assert(!dut.probe.logic.cmdIn.ready.toBoolean,
          s"demand at 0x${blockedPa.toHexString} was admitted at hold cycle $c, but slot 0 still owns " +
            s"its set -- the pfLookupSetBusy hold this test depends on did not happen")
      }

      // Phase 3 -- two REAL install episodes (slots 1 and 2) run while the demand
      // stays held. These are what drive installDeferCnt past installDeferMax.
      sendLine(AxiIds.I_SPEC_BASE + 1, streamBase + 0x80L)
      sendLine(AxiIds.I_SPEC_BASE + 2, streamBase + 0xC0L)
      var w = 0
      while (!starvesSeen && w < 100) { cd.waitSampling(); w += 1 }
      assert(starvesSeen,
        s"precondition not reached: installStarves never latched after two install episodes " +
          s"(maxDefer=$maxDefer, installDeferMax=4). This test is vacuous unless the starve " +
          s"threshold is genuinely crossed while the demand is held.")
      assert(dut.icache.logic.pfValid(0).toBoolean && !dut.icache.logic.pfComplete(0).toBoolean,
        "slot 0 must still be live-and-incomplete at the moment installStarves latches")
      assert(!dut.probe.logic.cmdIn.ready.toBoolean,
        "the demand must still be held at the moment installStarves latches")

      // Phase 4 -- the blocking slot completes. THIS is the deadlock trigger: with
      // `!installStarves` on the arm, slot 0 can now never install and the demand can
      // never be admitted.
      sendLine(AxiIds.I_SPEC_BASE, streamBase + 0x40L)

      // Phase 5 -- bounded liveness check. Budget is ~16x the fix's guaranteed worst
      // case (pfSlots install episodes x 3 cycles = 12), so a pass is never marginal
      // and a failure is unambiguous.
      val budget = 200
      var admittedAt = -1
      var c = 0
      while (admittedAt < 0 && c < budget) {
        cd.waitSampling()
        c += 1
        if (dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean) admittedAt = c
      }
      if (admittedAt < 0)
        fail(s"DEADLOCK: the demand at 0x${blockedPa.toHexString} was never admitted within $budget " +
          s"cycles of its blocking slot completing. State: pfValid(0)=${dut.icache.logic.pfValid(0).toBoolean} " +
          s"pfComplete(0)=${dut.icache.logic.pfComplete(0).toBoolean} " +
          s"pfInstallArm=${dut.icache.logic.pfInstallArm.toBoolean} " +
          s"installDeferCnt=${dut.icache.logic.installDeferCnt.toInt} " +
          s"installStarves=${dut.icache.logic.installStarves.toBoolean} maxDefer=$maxDefer. " +
          s"A complete-but-uninstalled slot keeps pfLookupSetBusy asserted, so gating the install " +
          s"arm on installStarves blocks the very install that would release this demand.")
      dut.probe.logic.cmdIn.valid #= false

      // Phase 6 -- the demand must then be a real, correct refill.
      waitReq(AxiIds.I_DEMAND, blockedPa)
      sendLine(AxiIds.I_DEMAND, blockedPa)
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      val rspPc   = dut.probe.logic.rspOut.payload.pc.toLong
      val rspData = dut.probe.logic.rspOut.payload.data.toBigInt

      sendLine(AxiIds.I_SPEC_BASE + 3, streamBase + 0x100L)
      cd.waitSampling(20)
      obs.terminate()

      assert(rspPc == blockedPa,
        s"released demand mis-attributed: got 0x${rspPc.toHexString}, expected 0x${blockedPa.toHexString}")
      assert(rspData == IcacheSim.window64(blockedPa), "released demand response data mismatch")
      // Non-vacuity: slot 0 really did install (it is what released the hold), and the
      // admission happened inside the structural bound, not merely inside the budget.
      assert(admittedAt <= dut.icache.logic.installDeferBound,
        s"the demand was admitted at cycle $admittedAt after its blocking slot completed, beyond the " +
          s"${dut.icache.logic.installDeferBound}-cycle structural worst case (pfSlots install episodes " +
          s"x 3 cycles each)")
      assert(installSeen == 8,
        s"expected exactly 4 slots x 2-cycle PF_PRED dwell = 8 predIsPf-high samples, got $installSeen")
      assert(maxDefer >= 4 && maxDefer <= dut.icache.logic.installDeferBound,
        s"installDeferCnt peaked at $maxDefer: must reach installDeferMax=4 (else installStarves was " +
          s"never genuinely latched and this test is vacuous) and must stay within the " +
          s"${dut.icache.logic.installDeferBound}-cycle H9 bound")
    }
  }
}
