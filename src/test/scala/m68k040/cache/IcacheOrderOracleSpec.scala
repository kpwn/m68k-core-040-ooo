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
    val icache = new IcachePlugin
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
}
