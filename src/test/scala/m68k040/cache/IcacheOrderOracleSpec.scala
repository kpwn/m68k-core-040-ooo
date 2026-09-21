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

import scala.collection.mutable.ArrayBuffer

/** Spec section 12.1 oracles 1-3, built against the PRE-restructure RTL on purpose.
  *
  * WHY THESE EXIST AND WHY THEY EXIST NOW. Spec risk R3: M3 restructures the
  * accept/verdict boundary that guarantees the in-order response contract, and a
  * violation of that contract is SILENT -- FetchRsp carries no tag and
  * FetchAlignPlugin attributes every response to its outstanding ring's HEAD
  * (FetchAlignPlugin.scala:263,283), so a reordering shows up as mis-paired
  * instruction bytes, not as a crash. An oracle written AFTER M3 cannot distinguish
  * "M3 broke it" from "it was never true". Proven green on unmodified RTL first, then
  * run at every subsequent task boundary, it is a real regression detector.
  *
  * These are simulation-only. Nothing here adds a synthesised register.
  */
object IcacheOrderOracle {

  /** Issue `addrs` back-to-back on cmdPort and check that responses come back in
    * exactly that order, one per command, none dropped, none duplicated.
    *
    * The stamp is maintained in the TESTBENCH, not in RTL: the invariant is
    * "the k-th response corresponds to the k-th accepted command", and the
    * testbench knows the accept order because it is the one driving cmdPort.
    *
    * CALLER PRECONDITION (found the hard way during Task 4 — a caller that skips
    * this produced a spurious garbage-pc response that looked exactly like a real
    * RTL ordering violation): `cmdPc` and any other `in` IO this driver does not
    * itself own (e.g. `invalidateAll`) MUST already be poked to a defined value
    * before calling this function. `runOrderedStream` only self-resets `cmdValid`
    * (line below) — it does NOT drive `cmdPc` to a safe default before the driver
    * fork first samples it, and it has no knowledge of `invalidateAll` at all. All
    * three call sites in this file poke `cmdIn.valid/.pc` and `invalidateAll`
    * immediately after `attachMemory`, with no intervening `waitSampling`, mirroring
    * `IcacheSpec.scala`'s established reset-poke pattern (`IcacheSpec.scala:68-91`)
    * — any future caller (e.g. Task 13's mutation proofs) must do the same. */
  def runOrderedStream(
      cmdValid: Bool, cmdReady: Bool, cmdPc: UInt,
      rspValid: Bool, rspPc: UInt,
      cd: ClockDomain,
      addrs: Seq[Long],
      timeoutCycles: Int = 20000): Unit = {

    val accepted = ArrayBuffer[Long]()
    val observed = ArrayBuffer[Long]()
    var issueIdx = 0
    var cycles   = 0

    cmdValid #= false
    cd.waitSampling()

    val driver = fork {
      while (issueIdx < addrs.length) {
        cmdPc    #= addrs(issueIdx)
        cmdValid #= true
        cd.waitSampling()
        if (cmdReady.toBoolean) {
          accepted += addrs(issueIdx)
          issueIdx += 1
        }
      }
      cmdValid #= false
    }

    val monitor = fork {
      while (observed.length < addrs.length && cycles < timeoutCycles) {
        cd.waitSampling()
        cycles += 1
        if (rspValid.toBoolean) {
          val pc = rspPc.toLong
          val k  = observed.length
          assert(k < accepted.length,
            s"ORACLE 1 VIOLATED: response #$k (pc=0x${pc.toHexString}) arrived before " +
            s"any ${k + 1}-th command had been accepted -- a response was manufactured")
          assert(pc == accepted(k),
            s"ORACLE 1 VIOLATED: response #$k has pc=0x${pc.toHexString} but the #$k " +
            s"ACCEPTED command was pc=0x${accepted(k).toHexString}. Responses must leave " +
            s"the cache in accept order -- FetchRsp carries no tag and FetchAlignPlugin " +
            s"attributes by ring head, so this is silent instruction-byte mis-pairing.")
          observed += pc
        }
      }
    }

    driver.join()
    monitor.join()
    assert(observed.length == addrs.length,
      s"ORACLE 1 VIOLATED: ${addrs.length} commands accepted but only ${observed.length} " +
      s"responses observed within $timeoutCycles cycles -- a dropped response wedges " +
      s"FetchAlignPlugin's ring permanently (ringCount never decrements).")
  }
}

class IcacheOrderOracleSpec extends AnyFunSuite {

  class Dut(xlateFactory: => FiberPlugin with TranslationService = new IdentityTranslationPlugin)
      extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = xlateFactory
    val icache = new IcachePlugin(IcachePredecodeConfig.fromEnvironment)
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  private def compiled = SimConfig.withVerilator.compile(new Dut())

  // ---- Oracle 1 ------------------------------------------------------------
  test("oracle 1: every response is the next accepted command, in order", VerilatorTest) {
    compiled.doSim("oracle1-order") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x4000)
      // Deviation from the plan's literal listing: cmdIn.valid/pc and invalidateAll
      // are plain `in Bool()`/`in UInt()` top-level IO with no default driver, so they
      // are X/random-per-seed until explicitly poked (the same gotcha IcacheSpec's
      // `fetch`/test-setup code works around -- see IcacheSpec.scala:122-124's comment
      // on "an uninitialised cmdIn.valid can start a refill that never completes").
      // Without this, `runOrderedStream`'s own `cmdValid #= false` (set only once
      // inside the function) leaves a multi-cycle X-driven window beforehand, which
      // manifested as a spurious response with garbage pc (0xc621cdb6) during initial
      // triage -- a testbench reset-discipline bug, not an RTL ordering violation.
      dut.probe.logic.cmdIn.valid    #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      dut.clockDomain.waitSampling(5)

      // A deliberately hostile mix: same-line hits, sequential-line misses, a
      // backwards jump that re-hits, and a same-SET conflict that forces eviction.
      // 0x1000 and 0x2000 differ in tag but share set 0 (set = pc(11:6), 64 sets,
      // 64B lines -> 0x1000 and 0x2000 are both set 0).
      val addrs = Seq(
        0x1000L, 0x1008L, 0x1010L,          // one miss then two hits in the same line
        0x1040L, 0x1080L, 0x10c0L,          // sequential misses (also exercises prefetch)
        0x2000L,                            // same-set conflict, forces an eviction
        0x1000L,                            // may or may not still be resident
        0x1008L, 0x2000L, 0x2008L, 0x1040L) // thrash

      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.logic.cmdIn.valid, cmdReady = dut.probe.logic.cmdIn.ready,
        cmdPc    = dut.probe.logic.cmdIn.payload.pc,
        rspValid = dut.probe.logic.rspOut.valid, rspPc = dut.probe.logic.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = addrs)
    }
  }

  // ---- Oracle 2 ------------------------------------------------------------
  test("oracle 2: N consecutive fetches to one line produce exactly one AR", VerilatorTest) {
    compiled.doSim("oracle2-one-ar") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x4000)
      // Prefetch off: this oracle is about the DEMAND path's duplicate-miss
      // suppression (IcachePlugin.scala:625-632), which M3 re-implements via
      // s1Unresolved. Speculative ARs are a separate, legitimate source of ARs and
      // would make the count untestable.
      dut.icache.logic.prefetchEnable #= false
      // See oracle 1's comment: cmdIn.valid/pc and invalidateAll need an explicit
      // defined default before any waitSampling, else they are X for a few cycles.
      dut.probe.logic.cmdIn.valid    #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      dut.clockDomain.waitSampling(5)

      val demandArs = ArrayBuffer[Long]()
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) {
            demandArs += dut.icache.logic.axi.ar.payload.addr.toLong
          }
        }
      }

      // Eight distinct offsets inside ONE 64-byte line.
      val addrs = (0 until 8).map(i => 0x1000L + i * 8)
      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.logic.cmdIn.valid, cmdReady = dut.probe.logic.cmdIn.ready,
        cmdPc    = dut.probe.logic.cmdIn.payload.pc,
        rspValid = dut.probe.logic.rspOut.valid, rspPc = dut.probe.logic.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = addrs)
      dut.clockDomain.waitSampling(20)

      val toLine = demandArs.filter(a => (a & ~63L) == 0x1000L)
      assert(toLine.length == 1,
        s"ORACLE 2 VIOLATED: ${addrs.length} fetches into line 0x1000 produced " +
        s"${toLine.length} ARs (${toLine.map(a => f"0x$a%x").mkString(",")}), expected " +
        s"exactly 1. Duplicate-miss suppression is backpressure-based " +
        s"(IcachePlugin.scala:625-632); under M3 it is s1Unresolved's job.")
    }
  }

  // ---- Oracle 3 ------------------------------------------------------------
  //
  // TASK 9 (M2c): THIS ORACLE NOW COVERS THE DEMAND ENTRY.
  //
  // Task 4 wrote it against the pre-M2 RTL, where the demand context was a pile of
  // `miss*` scalars rather than an indexed MSHR entry, so it could only iterate the
  // SPECULATIVE slots and left an explicit note that Task 9 was where entry 0 would
  // land. That gap was not academic: Task 8 (M2b) introduced a new `INSTALL_ARM` state
  // and found that, without adding it to `demandSetOwned`'s hand-written state list, a
  // ONE-CYCLE window existed in which the background allocator could hand a speculative
  // slot the very set -- and the very victim way -- a demand fill was about to commit
  // into. Task 8 closed it by hand and was allowed to land specifically because THIS is
  // where the coverage was supposed to arrive.
  //
  // Two RTL changes make the extension possible and non-vacuous:
  //   - `mshrSet` is now simPublic(), so the set of EVERY entry can be read directly
  //     instead of being reconstructed from AR addresses (which could never have worked
  //     for entry 0: the demand set is owned across four states after its AR, and an
  //     AR-derived shadow cannot tell when that ownership ends);
  //   - `mshrValid(DEMAND_IDX)` is a real register meaning "the demand MSHR is live and
  //     owns mshrSet(0)", set at miss-detect and cleared on the way back to IDLE. It is
  //     what replaced `demandSetOwned`'s hand-written five-state list, and it is what
  //     this oracle reads. (If a future edit makes it always-False, this oracle goes
  //     silently vacuous -- so the test asserts its coverage window is non-empty.)
  test("oracle 3: no two live MSHR entries own the same set", VerilatorTest) {
    compiled.doSim("oracle3-mshr-exclusivity") { dut =>
      dut.clockDomain.forkStimulus(10)
      IcacheSim.attachMemory(dut.icache.logic.axi, dut.clockDomain, 0x1000, 0x8000)
      // See oracle 1's comment: cmdIn.valid/pc and invalidateAll need an explicit
      // defined default before any waitSampling, else they are X for a few cycles.
      dut.probe.logic.cmdIn.valid    #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      dut.clockDomain.waitSampling(5)

      // DRIFT NOTE, verified against AxiIds.scala: the plan writes `MSHR_N_TEST =
      // 1 + AxiIds.I_SPEC_SLOTS` and parenthesises it as 6. I_SPEC_SLOTS is
      // I_SPEC_LAST(4) - I_SPEC_BASE(1) + 1 == 4, so the real value is FIVE. The
      // formula is the plan's and is used verbatim; only its parenthesised constant
      // was stale. (`IcacheArrayProbe.mshrEntries` is the same expression.)
      val MSHR_N_TEST = IcacheArrayProbe.mshrEntries
      var violations   = 0
      // Coverage instrumentation: how many sampled cycles actually had the DEMAND entry
      // live, and how many had it live ALONGSIDE at least one speculative entry. The
      // second number is the one that matters -- entry-0 coverage is only real on
      // cycles where a duplicate was even possible.
      var demandLiveCycles   = 0
      var demandVsSpecCycles = 0
      fork {
        while (true) {
          dut.clockDomain.waitSampling()

          val live = (0 until MSHR_N_TEST).filter(i =>
            IcacheArrayProbe.mshrValid(dut.icache, i))
          val sets = live.map(i => IcacheArrayProbe.mshrSet(dut.icache, i))
          if (live.contains(AxiIds.I_DEMAND)) {
            demandLiveCycles += 1
            if (live.length > 1) demandVsSpecCycles += 1
          }
          if (sets.distinct.length != sets.length) {
            violations += 1
            simFailure(
              s"ORACLE 3 VIOLATED: live MSHR entries $live own sets $sets -- two fill " +
              s"owners for one set. Entry ${AxiIds.I_DEMAND} is the DEMAND MSHR; a " +
              s"duplicate with a speculative entry means the same line can be installed " +
              s"into two ways of one set, and (because both read victim(set) before " +
              s"PREDECODE advances it) potentially the SAME way. IcachePlugin.scala's " +
              s"`pfCandSetBusy`/`demandSetOwned` guard enforces one fill owner per set.")
          }
        }
      }

      // Long sequential run: keeps the prefetch window saturated and cycles the same
      // sets repeatedly through allocate/install/free, with demand misses interleaved
      // throughout (every line is a demand miss the first time it is touched).
      val addrs = (0 until 128).map(i => 0x1000L + i * 64)
      IcacheOrderOracle.runOrderedStream(
        cmdValid = dut.probe.logic.cmdIn.valid, cmdReady = dut.probe.logic.cmdIn.ready,
        cmdPc    = dut.probe.logic.cmdIn.payload.pc,
        rspValid = dut.probe.logic.rspOut.valid, rspPc = dut.probe.logic.rspOut.payload.pc,
        cd = dut.clockDomain, addrs = addrs, timeoutCycles = 60000)
      dut.clockDomain.waitSampling(200)
      assert(violations == 0, s"$violations MSHR set-exclusivity violations")
      // NON-VACUITY. Without these the entry-0 extension could silently degrade to the
      // pre-Task-9 speculative-only check (an always-False mshrValid(0) never joins
      // `live`) and the suite would still be green.
      assert(demandLiveCycles > 0,
        "oracle 3 never observed the DEMAND MSHR entry live -- mshrValid(DEMAND_IDX) is " +
        "not being maintained, so entry-0 coverage is VACUOUS")
      assert(demandVsSpecCycles > 0,
        s"oracle 3 observed the demand entry live for $demandLiveCycles cycles but NEVER " +
        "alongside a live speculative entry -- the demand-vs-speculative exclusivity this " +
        "extension exists to check was never exercised")
    }
  }

  // ---- Oracle 3, directed: the one shape that actually breaks it --------------
  //
  // WHY A DIRECTED TEST AND NOT JUST THE STREAM ABOVE. The 128-line sequential stream
  // exercises entry 0 heavily, but it can never produce a demand/speculative set
  // COLLISION, and that is structural rather than lucky: the speculative window is
  // clamped to the demand line's own 4 KiB page (`pfWindowHasCandidate`), one page is
  // exactly 64 lines, and the cache has exactly 64 sets -- so two distinct lines inside
  // one page always have distinct sets. A candidate can only share the demand entry's
  // set by being the demand's VERY OWN LINE. Confirmed empirically: deleting the
  // `demandSetOwned` guard entirely leaves the stream test green.
  //
  // The frontier points at the demand line whenever it has stalled and demand has
  // caught up to it, because `seedPfWindow` deliberately does NOT rewind `pfNextPa` on
  // a SEQUENTIAL advance. This test builds that state on purpose: warm line L0 with
  // prefetch OFF (which seeds the frontier at L1 and then freezes it), take a demand
  // miss on L1 -- the sequential successor, so the frontier still points AT it -- hold
  // that refill open on the bus, and only then turn prefetch ON. Now the one and only
  // candidate the allocator can see is the line the demand MSHR is filling.
  //
  // MUTATION-PROVEN (Task 9): with `pfCandSetBusy` narrowed back to the speculative
  // entries and `demandSetOwned` forced False, this test fails with
  //   ORACLE 3 VIOLATED: live MSHR entries List(0, 1) own sets List(1, 1)
  // and the pre-Task-9 speculative-only oracle -- `(0 until AxiIds.I_SPEC_SLOTS)` over
  // `pfValid` -- passes on that same mutated RTL, because only ONE speculative entry is
  // ever live. That is the coverage gap this task closes, stated as an experiment.
  test("oracle 3 (directed): the frontier pointing AT the demand line must not allocate " +
       "a second owner of its set", VerilatorTest) {
    compiled.doSim("oracle3-demand-vs-spec") { dut =>
      val cd  = dut.clockDomain
      val axi = dut.icache.logic.axi
      cd.forkStimulus(10)

      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      axi.ar.ready       #= true
      axi.r.valid        #= false
      axi.r.payload.data #= 0
      axi.r.payload.id   #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0
      cd.waitSampling(5)
      // `prefetchEnable` is a self-assigned RegInit(True); a poke issued before reset
      // deasserts is simply overwritten by its init value (observed: four speculative
      // ARs fired anyway). Poke it AFTER the reset window, exactly as IcachePrefetchSpec
      // does.
      dut.icache.logic.prefetchEnable #= false
      cd.waitSampling(2)

      // Hand-driven AXI so the demand refill can be held open for as long as the
      // allocator needs to be observed. `attachMemory` answers in a handful of cycles,
      // which is not enough to poke `prefetchEnable` inside the window.
      val ars = scala.collection.mutable.Queue[(Int, Long)]()
      cd.onSamplings {
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
          ars.enqueue((axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong))
      }
      def beatData(addr: Long, beat: Int): BigInt =
        (0 until 32).foldLeft(BigInt(0)) { (acc, i) =>
          acc | (BigInt(IcacheSim.memByte(addr + beat * 32L + i)) << (8 * i)) }
      def respond(id: Int, addr: Long): Unit = for (beat <- 0 to 1) {
        axi.r.valid        #= true
        axi.r.payload.id   #= id
        axi.r.payload.data #= beatData(addr, beat)
        axi.r.payload.resp #= 0
        axi.r.payload.last #= (beat == 1)
        cd.waitSamplingWhere(axi.r.ready.toBoolean)
        axi.r.valid #= false
        cd.waitSampling()
      }

      val MSHR_N_TEST = IcacheArrayProbe.mshrEntries
      var violations       = 0
      var demandVsSpec     = 0
      var demandLiveCycles = 0
      fork {
        while (true) {
          cd.waitSampling()
          val live = (0 until MSHR_N_TEST).filter(i =>
            IcacheArrayProbe.mshrValid(dut.icache, i))
          val sets = live.map(i => IcacheArrayProbe.mshrSet(dut.icache, i))
          if (live.contains(AxiIds.I_DEMAND)) {
            demandLiveCycles += 1
            if (live.length > 1) demandVsSpec += 1
          }
          if (sets.distinct.length != sets.length) {
            violations += 1
            simFailure(
              s"ORACLE 3 VIOLATED: live MSHR entries $live own sets $sets -- two fill " +
              s"owners for one set, and entry ${AxiIds.I_DEMAND} (the DEMAND MSHR) is " +
              s"one of them. Both read victim(set) before PREDECODE advances it, so " +
              s"this is two fills racing into the SAME way of the same set.")
          }
        }
      }

      def issue(pc: Long): Unit = {
        dut.probe.logic.cmdIn.valid      #= true
        dut.probe.logic.cmdIn.payload.pc #= pc
        cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean)
        dut.probe.logic.cmdIn.valid #= false
      }

      val L0 = 0x2000L
      val L1 = L0 + 64

      // 1) Warm L0 with prefetch OFF. This seeds pfSeqValid/pfDemandLine = L0 and the
      //    frontier pfNextPa = L1, and then leaves the frontier frozen there.
      issue(L0)
      while (ars.isEmpty) cd.waitSampling()
      val (id0, a0) = ars.dequeue()
      assert(id0 == AxiIds.I_DEMAND && a0 == L0, s"expected demand AR for L0, got ($id0,0x${a0.toHexString})")
      respond(id0, a0)
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      cd.waitSampling(5)
      assert(ars.isEmpty, s"prefetch was supposed to be OFF, but ${ars.size} extra ARs fired")

      // 2) Demand-miss L1 -- the SEQUENTIAL successor, so `seedPfWindow` leaves the
      //    frontier where it is, i.e. pointing at L1 itself. Hold the refill open.
      issue(L1)
      while (ars.isEmpty) cd.waitSampling()
      val (id1, a1) = ars.dequeue()
      assert(id1 == AxiIds.I_DEMAND && a1 == L1, s"expected demand AR for L1, got ($id1,0x${a1.toHexString})")
      assert(IcacheArrayProbe.mshrValid(dut.icache, AxiIds.I_DEMAND),
        "setup: the demand MSHR entry must be live during its own refill")

      // 3) Open the allocator on exactly this state.
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(30)

      // The monitor above is the real assertion. This one makes the SETUP itself
      // non-vacuous: if the allocator never even looked, the test proves nothing.
      assert(demandLiveCycles > 25,
        s"setup collapsed: the demand entry was live for only $demandLiveCycles cycles " +
        "while its refill was being held open")

      // 4) Let everything drain, answering whatever the allocator did decide to launch.
      dut.icache.logic.prefetchEnable #= false
      respond(id1, a1)
      var guard = 0
      while (guard < 4000 && (ars.nonEmpty ||
             (0 until MSHR_N_TEST).exists(i => IcacheArrayProbe.mshrValid(dut.icache, i)))) {
        if (ars.nonEmpty) { val (i, a) = ars.dequeue(); respond(i, a) }
        else cd.waitSampling()
        guard += 1
      }
      assert(violations == 0, s"$violations demand-vs-speculative set-exclusivity violations")
      // Task 9 review fix M1: `demandVsSpec` was declared and incremented but never
      // read. The reviewer's point stands and was empirically confirmed (unmodified
      // RTL: demandVsSpec == 0, demandLiveCycles == 48 -- non-vacuous): this specific
      // stimulus (a demand refill held open with the frontier seeded exactly at the
      // demand's own line, then the allocator opened on top of it) never produces a
      // co-live demand+speculative pair under correct behaviour, because the
      // allocator's `demandSetOwned`/`pfCandLive` guards keep the frontier from
      // allocating a speculative entry until it has advanced clear of the demand's own
      // set. So this assertion is STRICTLY STRONGER than the same-set-only oracle
      // above: it fires on ANY co-live speculative entry, not only a same-set one, and
      // would still catch the fault-injection mutation the review used to validate
      // oracle 3's sensitivity.
      assert(demandVsSpec == 0,
        s"$demandVsSpec cycles saw the demand MSHR entry co-live with a speculative " +
        "entry -- the allocator is not supposed to allocate speculatively while the " +
        "demand entry is live under this stimulus")
    }
  }

  // ---- Oracle 3, directed #2: the M3b ACCEPTED-BUT-UNDISPATCHED window ---------
  //
  // TASK 11 REVIEW FIX I2. The directed test above covers `demandSetOwned`'s FIRST
  // lane (`mshrValid(DEMAND_IDX) && mshrSet(DEMAND_IDX) === pfCandSet`) -- the window
  // in which the demand fill is already dispatched and live on the bus. M3b opened a
  // BRAND-NEW window in front of that one, and added a lane for it:
  //
  //     s1Unresolved && (s0Set === pfCandSet)            IcachePlugin.scala (lane 2)
  //     demandStuck  = s1Unresolved || heldOnSetBusy     IcachePlugin.scala
  //
  // Under M3 a demand miss is DISCOVERED at S1, one cycle after acceptance, and is
  // dispatched later still if the FSM is not in IDLE at that moment. Across that whole
  // accepted-but-undispatched window `mshrValid(DEMAND_IDX)` is FALSE, so lane 1 and
  // `pfCandSetBusy` both read "nobody owns that set" while the S0 context (whose frozen
  // `tagQ`/`validsQ` are what makes the held miss correct) is already committed to it.
  // If the background allocator hands a speculative slot the SAME set during that
  // window, the demand's own dispatch a few cycles later produces two live fill owners
  // for one set -- and, since both read `victim(set)` before either PREDECODE advances
  // it, potentially the SAME way. A line installed into two ways of one set makes
  // `s1HitVec` 2-hot, and `s1SelOh`'s one-hot AND-OR then delivers the bitwise OR of
  // two ways as instruction bytes. Silent, per spec risk R3.
  //
  // WHAT THIS TEST CONSTRUCTS, exactly the four conditions that make the window real:
  //   (a) a demand miss is ACCEPTED (s1Unresolved goes high, mshrValid(0) still False);
  //   (b) on the SAME cycle a SPECULATIVE INSTALL starts, which is what keeps the FSM
  //       out of IDLE and stretches the window to its full INSTALL_ARM + 2xPREDECODE
  //       length -- a one-cycle window is much harder to observe;
  //   (c) the speculative frontier `pfNextPa` is parked EXACTLY on the demand's own
  //       line, so `pfCandSet === s0Set`. Built with `seedPfWindow`'s sequential rule:
  //       fetching line L+64 right after L leaves the frontier where it already was
  //       (at L+64), i.e. pointing AT the line just accepted;
  //   (d) at least one speculative MSHR slot is FREE, so the allocator could actually
  //       take one.
  //
  // Getting (a) and (b) onto one cycle is a timing construction, not a stimulus mix:
  // the last R beat of a held speculative fill sets `mshrComplete`, IDLE turns that
  // into the INSTALL_ARM transition on the NEXT edge, and the demand command is raised
  // in between so its accept lands on that same edge. The single-candidate prefetch
  // window (a demand line two lines from a page end clamps `pfLimitPa` to exactly one
  // candidate) is what leaves three of the four speculative slots free for (d).
  //
  // MUTATION MATRIX (all three re-run by Task 11's review fix, and the middle row is
  // the honest reason a single-lane mutation is NOT the right proof here):
  //   lane 2 removed only .................. PASSES. Lane 2 is redundant with the outer
  //     `!demandStuck` gate, exactly as `demandSetOwned`'s own comment states; no test
  //     can distinguish its presence while `demandStuck` still carries `s1Unresolved`.
  //   `s1Unresolved` removed from `demandStuck` only ... PASSES -- and that is lane 2
  //     DOING ITS JOB: the allocator loop now runs during the window, sees
  //     `demandSetOwned` high, and takes the "wait" arm instead of allocating. This row
  //     is the proof that lane 2 is load-bearing rather than decorative.
  //   BOTH removed ......................... FAILS, with
  //     ORACLE 3 VIOLATED: live MSHR entries Vector(0, 2, 3, 4) own sets
  //                        Vector(1, 1, 2, 3)
  //     -- entry 0 is the demand miss on D (set 1) and entry 2 is the speculative slot
  //     the unfrozen allocator handed the SAME set during the window.
  //   i.e. the two guards the review named are jointly necessary and individually
  //   sufficient, and this test is what says so. None of the other four tests in this
  //   file, and none of the 60 icache tests, moves under any of the three rows.
  test("oracle 3 (directed): no speculative allocation into the set of an ACCEPTED but " +
       "not yet dispatched demand miss", VerilatorTest) {
    compiled.doSim("oracle3-s1unresolved-window") { dut =>
      val cd  = dut.clockDomain
      val axi = dut.icache.logic.axi
      cd.forkStimulus(10)

      dut.probe.logic.cmdIn.valid      #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll   #= false
      axi.ar.ready       #= true
      axi.r.valid        #= false
      axi.r.payload.data #= 0
      axi.r.payload.id   #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0
      cd.waitSampling(5)
      // Same gotcha as the directed test above: `prefetchEnable` is a self-assigned
      // RegInit(True), so a poke before reset deasserts is overwritten by its init.
      dut.icache.logic.prefetchEnable #= false
      cd.waitSampling(2)

      // ---- addresses -----------------------------------------------------------
      // A0 sits two lines from the end of page 0x1000, so `seedPfWindow` clamps
      // `pfLimitPa` to the page end and the frontier has EXACTLY ONE candidate (SPEC).
      // That is what leaves 3 of the 4 speculative slots free for condition (d).
      val A0   = 0x1f80L                 // demand warm-up, page 0x1000
      val SPEC = 0x1fc0L                 // the single speculative candidate, set 63
      val B    = 0x3000L                 // fresh page -> reseeds the frontier to B+64
      val D    = B + 64                  // == 0x3040, set 1: the frontier parks HERE
      val D_SET = ((D >> 6) & 63).toInt  // 1

      val ars = scala.collection.mutable.Queue[(Int, Long)]()
      def beatData(addr: Long, beat: Int): BigInt =
        (0 until 32).foldLeft(BigInt(0)) { (acc, i) =>
          acc | (BigInt(IcacheSim.memByte(addr + beat * 32L + i)) << (8 * i)) }
      /** ONE R beat, returning immediately after the edge that consumed it -- the
        * caller owns what happens on the very next edge. `respond` is the usual
        * two-beat form built on it. */
      def sendBeat(id: Int, addr: Long, beat: Int): Unit = {
        axi.r.valid        #= true
        axi.r.payload.id   #= id
        axi.r.payload.data #= beatData(addr, beat)
        axi.r.payload.resp #= 0
        axi.r.payload.last #= (beat == 1)
        cd.waitSamplingWhere(axi.r.ready.toBoolean)
        axi.r.valid #= false
      }
      def respond(id: Int, addr: Long): Unit =
        for (beat <- 0 to 1) { sendBeat(id, addr, beat); cd.waitSampling() }
      def issue(pc: Long): Unit = {
        dut.probe.logic.cmdIn.valid      #= true
        dut.probe.logic.cmdIn.payload.pc #= pc
        cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean)
        dut.probe.logic.cmdIn.valid #= false
      }

      // ---- the monitor ---------------------------------------------------------
      val MSHR_N_TEST = IcacheArrayProbe.mshrEntries
      var cycle              = 0
      var violations         = 0
      var windowCycles       = 0   // (a): accepted, unresolved, NOT yet dispatched
      var windowWithInstall  = 0   // (b): ... while a SPECULATIVE install dwell runs
      var windowWithFreeSlot = 0   // (d): ... with a free speculative slot available
      var windowNoDemandMshr = 0   // ... with mshrValid(DEMAND_IDX) provably False
      val windowSets         = scala.collection.mutable.Set[Int]()
      var specOwnsDemandSet  = 0   // the direct statement of the property under test
      var armed              = false
      var acceptCycleD       = -1
      var firstPredCycle     = -1
      val specArs            = ArrayBuffer[(Long, Int)]()

      cd.onSamplings {
        cycle += 1
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean) {
          val id = axi.ar.payload.id.toInt
          val a  = axi.ar.payload.addr.toLong
          ars.enqueue((id, a))
          if (id != AxiIds.I_DEMAND) specArs += ((a, cycle))
        }
        if (dut.probe.logic.cmdIn.valid.toBoolean && dut.probe.logic.cmdIn.ready.toBoolean &&
            dut.probe.logic.cmdIn.payload.pc.toLong == D && acceptCycleD < 0)
          acceptCycleD = cycle
        if (armed && firstPredCycle < 0 &&
            dut.icache.logic.predActive.toBoolean && dut.icache.logic.predIsPf.toBoolean)
          firstPredCycle = cycle

        val live = (0 until MSHR_N_TEST).filter(i => IcacheArrayProbe.mshrValid(dut.icache, i))
        val sets = live.map(i => IcacheArrayProbe.mshrSet(dut.icache, i))
        if (sets.distinct.length != sets.length) {
          violations += 1
          simFailure(
            s"ORACLE 3 VIOLATED: live MSHR entries $live own sets $sets -- two fill " +
            s"owners for one set. Entry ${AxiIds.I_DEMAND} is the DEMAND MSHR. This is " +
            s"the M3b ACCEPTED-BUT-UNDISPATCHED window: `mshrValid(DEMAND_IDX)` is " +
            s"False across it, so only `demandSetOwned`'s `s1Unresolved && s0Set === " +
            s"pfCandSet` lane and `demandStuck`'s `s1Unresolved` term keep the " +
            s"background allocator out of the set the accepted miss has already " +
            s"committed to. Two owners of one set can install one line into two ways, " +
            s"making `s1HitVec` 2-hot and the one-hot AND-OR deliver the OR of both.")
        }

        // `armed` scopes the window accounting to the CONSTRUCTED window. Every
        // ordinary demand miss in the set-up phase also spends one cycle
        // accepted-but-undispatched (the S1 dispatch is one cycle after the accept),
        // and counting those would make `windowSets` a union over three different sets
        // and hide a collapsed construction behind a healthy-looking cycle count.
        val demandLive = IcacheArrayProbe.mshrValid(dut.icache, AxiIds.I_DEMAND)
        if (armed && dut.icache.logic.s1Unresolved.toBoolean && !demandLive) {
          windowCycles += 1
          windowSets += dut.icache.logic.s0Set.toInt
          windowNoDemandMshr += 1
          if (dut.icache.logic.predActive.toBoolean && dut.icache.logic.predIsPf.toBoolean)
            windowWithInstall += 1
          if ((1 until MSHR_N_TEST).exists(i => !IcacheArrayProbe.mshrValid(dut.icache, i)))
            windowWithFreeSlot += 1
        }
        if (armed && (AxiIds.I_SPEC_BASE to AxiIds.I_SPEC_LAST).exists(i =>
              IcacheArrayProbe.mshrValid(dut.icache, i) &&
              IcacheArrayProbe.mshrSet(dut.icache, i) == D_SET))
          specOwnsDemandSet += 1
      }

      // ---- 1) warm A0 with prefetch OFF: frontier -> SPEC, window clamped to it --
      issue(A0)
      while (ars.isEmpty) cd.waitSampling()
      val (id0, a0) = ars.dequeue()
      assert(id0 == AxiIds.I_DEMAND && a0 == A0,
        s"expected demand AR for A0, got ($id0,0x${a0.toHexString})")
      respond(id0, a0)
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      cd.waitSampling(5)
      assert(ars.isEmpty, s"prefetch was supposed to be OFF, but ${ars.size} extra ARs fired")

      // ---- 2) open the allocator just long enough to launch that ONE candidate ---
      dut.icache.logic.prefetchEnable #= true
      var guard0 = 0
      while (ars.isEmpty && guard0 < 200) { cd.waitSampling(); guard0 += 1 }
      assert(ars.nonEmpty, "the single speculative candidate never issued an AR")
      val (specId, specAddr) = ars.dequeue()
      assert(specId != AxiIds.I_DEMAND && specAddr == SPEC,
        s"expected a SPECULATIVE AR for 0x${SPEC.toHexString}, got " +
        s"($specId,0x${specAddr.toHexString})")
      cd.waitSampling(5)
      dut.icache.logic.prefetchEnable #= false
      cd.waitSampling(2)
      assert(ars.isEmpty,
        s"the page-end clamp was supposed to leave exactly ONE candidate, but " +
        s"${ars.size} further ARs fired -- setup (d) (a free slot) is not guaranteed")
      assert((AxiIds.I_SPEC_BASE to AxiIds.I_SPEC_LAST).count(i =>
        !IcacheArrayProbe.mshrValid(dut.icache, i)) >= 1,
        "setup (d): no free speculative MSHR slot remains")

      // ---- 3) demand-fetch B: reseeds the frontier to B+64 == D and parks it -----
      // The speculative fill for SPEC stays OUTSTANDING throughout (no R beats yet).
      issue(B)
      while (ars.isEmpty) cd.waitSampling()
      val (id1, a1) = ars.dequeue()
      assert(id1 == AxiIds.I_DEMAND && a1 == B,
        s"expected demand AR for B, got ($id1,0x${a1.toHexString})")
      respond(id1, a1)
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      cd.waitSampling(6)
      assert(IcacheArrayProbe.mshrValid(dut.icache, specId),
        "setup: the held speculative entry must still be live and outstanding")
      assert(!IcacheArrayProbe.mshrValid(dut.icache, AxiIds.I_DEMAND),
        "setup: B's demand fill must have fully retired before the window is built")

      // ---- 4) THE CONSTRUCTION: land the accept of D on the install-start edge ---
      armed = true
      sendBeat(specId, specAddr, 0)
      cd.waitSampling()
      // This beat is `last`, so the edge that consumes it registers
      // `mshrComplete(specId) := True`. IDLE turns that into the INSTALL_ARM
      // transition on the NEXT edge -- so raising the command NOW (between the two
      // edges, with no intervening wait) puts `cmdPort.fire` on exactly that edge.
      sendBeat(specId, specAddr, 1)
      dut.probe.logic.cmdIn.valid      #= true
      dut.probe.logic.cmdIn.payload.pc #= D
      cd.waitSampling()                 // <- accept of D  +  start of the install
      dut.probe.logic.cmdIn.valid #= false
      // ---- 5) open the allocator on exactly that state --------------------------
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(12)

      // ---- 6) drain: answer D's own fill and everything the allocator launched ---
      var guard = 0
      while (guard < 4000 && (ars.nonEmpty ||
             (0 until MSHR_N_TEST).exists(i => IcacheArrayProbe.mshrValid(dut.icache, i)))) {
        if (ars.nonEmpty) { val (i, a) = ars.dequeue(); respond(i, a) }
        else cd.waitSampling()
        guard += 1
      }
      cd.waitSampling(20)
      armed = false

      // ---- 7) the property, then the four setup conditions ----------------------
      assert(violations == 0, s"$violations MSHR set-exclusivity violations")
      assert(specOwnsDemandSet == 0,
        s"$specOwnsDemandSet cycles saw a SPECULATIVE MSHR entry owning set $D_SET -- " +
        s"the set the accepted demand miss on 0x${D.toHexString} had already committed " +
        "to. This is the violation `demandSetOwned`'s `s1Unresolved` lane exists to " +
        "prevent, caught directly rather than via the two-owners-at-once coincidence.")

      // (a) the window existed at all, and mshrValid(DEMAND_IDX) really was False across
      //     it -- i.e. lane 1 and `pfCandSetBusy` genuinely could not have covered it.
      assert(windowCycles >= 3,
        s"setup (a) collapsed: the accepted-but-undispatched window lasted only " +
        s"$windowCycles cycles; the construction targets INSTALL_ARM + 2 x PREDECODE")
      assert(windowNoDemandMshr == windowCycles,
        s"setup (a): mshrValid(DEMAND_IDX) was set on some of the $windowCycles window " +
        "cycles, so lane 1 covered part of it and the test is not isolating lane 2")
      // (b) a speculative install really was running inside it.
      assert(windowWithInstall >= 1,
        s"setup (b) collapsed: no SPECULATIVE install dwell overlapped the window " +
        s"($windowCycles window cycles, $windowWithInstall with a dwell). Without it " +
        "the FSM reaches IDLE immediately and the window is one cycle long.")
      assert(acceptCycleD >= 0 && firstPredCycle >= 0 && firstPredCycle == acceptCycleD + 2,
        s"setup (b): D was accepted on cycle $acceptCycleD and the speculative install " +
        s"dwell first showed at cycle $firstPredCycle; the construction requires the " +
        "accept and the INSTALL_ARM transition on ONE edge (dwell at accept+2)")
      // (c) the frontier was parked on D's own set for the whole window.
      assert(windowSets.toSet == Set(D_SET),
        s"setup (c): the window's s0Set was $windowSets, expected exactly {$D_SET}")
      val postWindowSpecArs = specArs.filter(_._2 > acceptCycleD).map(_._1)
      assert(postWindowSpecArs.nonEmpty && postWindowSpecArs.head == D + 64,
        s"setup (c): after the window the frontier's first speculative AR was " +
        s"${postWindowSpecArs.headOption.map(a => f"0x$a%x").getOrElse("none")}, " +
        f"expected 0x${D + 64}%x. The frontier can only skip D itself as RESIDENT and " +
        "land on D+64 if it really had been parked ON D throughout the window -- this " +
        "is what makes `pfCandSet === s0Set` a fact rather than an assumption.")
      // (d) a free slot really was available on every window cycle.
      assert(windowWithFreeSlot == windowCycles,
        s"setup (d): only $windowWithFreeSlot of $windowCycles window cycles had a free " +
        "speculative MSHR slot; on the rest the allocator was blocked by exhaustion " +
        "rather than by the guard under test")
    }
  }
}
