package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Directed tests for slice I3's next-line prefetch MSHR and slice I1's
  * non-blocking-accept-under-prefetch.
  *
  * The rule tests here ARE the acceptance criteria for the binding rules P1-P4
  * (MSHR design doc §4.1(ii)), which are the standing no-SoC-address-map rule
  * applied to speculation. They are written to be genuinely discriminating: each
  * asserts on AXI AR traffic **by ID**, so "the prefetch did not happen" and "the
  * prefetch happened but was silently absorbed" cannot be confused.
  *
  * `IcacheSpec` is the complementary DEMAND-path suite; it runs with
  * `prefetchEnable = false` for exactly the reason this file exists.
  */
class IcachePrefetchSpec extends AnyFunSuite {

  class Dut(xlateFactory: => FiberPlugin with TranslationService = new IdentityTranslationPlugin) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = xlateFactory
    val icache = new IcachePlugin(IcachePredecodeConfig.fromEnvironment)
    val probe  = new FetchProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
  }

  def simConfig = SimConfig.withVerilator

  /** Per-ID/address AR trace.  ID zero is demand; IDs one through four are the
    * independent silent-fill owners. */
  class ArCounter(dut: Dut, cd: ClockDomain) {
    val byId = scala.collection.mutable.HashMap[Int, Int]().withDefaultValue(0)
    val fires = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
    cd.onSamplings {
      if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) {
        val id = dut.icache.logic.axi.ar.payload.id.toInt
        val address = dut.icache.logic.axi.ar.payload.addr.toLong
        byId(id) = byId(id) + 1
        fires += ((id, address))
      }
    }
    def demand: Int   = byId(AxiIds.I_DEMAND)
    def prefetch: Int = (AxiIds.I_SPEC_BASE to AxiIds.I_SPEC_LAST).map(byId).sum
    def at(address: Long): Int = fires.count(_._2 == address)
    def total: Int    = byId.values.sum
  }

  /** AXI responder that deliberately withholds every R beat until the complete
    * demand+four-speculative window is live.  It then interleaves beats and retires
    * IDs in a different order, so neither a serialized implementation nor FIFO
    * response attribution can pass. */
  class HeldFiveIdResponder(axi: Axi4ReadOnly, cd: ClockDomain) {
    case class Req(id: Int, address: Long, len: Int, size: Int)
    val requests = scala.collection.mutable.ArrayBuffer[Req]()
    private var heldAr: Option[Req] = None
    @volatile var releasedResponses = false

    axi.ar.ready #= false
    axi.r.valid #= false
    axi.r.payload.data #= 0
    axi.r.payload.id #= 0
    axi.r.payload.last #= false
    axi.r.payload.resp #= 0

    // Force two genuine ready-low observations for every offered AR.  The monitor
    // below requires the entire payload to remain stable through them.
    fork {
      var heldCycles = 0
      while (true) {
        cd.waitSampling()
        if (axi.ar.valid.toBoolean) {
          if (axi.ar.ready.toBoolean) {
            axi.ar.ready #= false
            heldCycles = 0
          } else if (heldCycles >= 1) {
            axi.ar.ready #= true
          } else {
            heldCycles += 1
          }
        } else {
          axi.ar.ready #= false
          heldCycles = 0
        }
      }
    }

    cd.onSamplings {
      if (axi.ar.valid.toBoolean) {
        val req = Req(axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong,
          axi.ar.payload.len.toInt, axi.ar.payload.size.toInt)
        heldAr.foreach(old => assert(req == old,
          s"AR payload changed under backpressure: old=$old new=$req"))
        if (!axi.ar.ready.toBoolean) heldAr = Some(req)
        else {
          assert(!requests.exists(_.id == req.id),
            s"ID ${req.id} was reused before its response: ${requests.toSeq}")
          requests += req
          heldAr = None
        }
      } else heldAr = None
      assert(!axi.r.valid.toBoolean || releasedResponses,
        "an R beat escaped before all five AR owners were live")
    }

    private def byteAt(address: Long): Int =
      ((address * 7L + (address >> 6) * 13L + 0x11L) & 0xffL).toInt

    def window64(address: Long): BigInt =
      (0 until 8).foldLeft(BigInt(0)) { (acc, i) =>
        acc | (BigInt(byteAt(address + i)) << (8 * i))
      }

    private def beatData(address: Long, beat: Int): BigInt =
      (0 until 32).foldLeft(BigInt(0)) { (acc, i) =>
        acc | (BigInt(byteAt(address + beat * 32L + i)) << (8 * i))
      }

    private def sendBeat(req: Req, beat: Int): Unit = {
      axi.r.valid #= true
      axi.r.payload.id #= req.id
      axi.r.payload.data #= beatData(req.address, beat)
      axi.r.payload.resp #= 0
      axi.r.payload.last #= (beat == 1)
      cd.waitSamplingWhere(axi.r.ready.toBoolean)
      axi.r.valid #= false
      cd.waitSampling()
    }

    def start(): Unit = fork {
      while (requests.size < 5) cd.waitSampling()
      val firstFive = requests.take(5)
      assert(firstFive.map(_.id).toSet == (AxiIds.I_DEMAND to AxiIds.I_SPEC_LAST).toSet,
        s"five distinct owners 0..4 required before any R, got $firstFive")
      assert(firstFive.forall(r => r.len == 1 && r.size == 5),
        s"every core line must be two 256-bit beats, got $firstFive")
      val byId = firstFive.map(r => r.id -> r).toMap
      val base = byId(0).address
      (AxiIds.I_DEMAND to AxiIds.I_SPEC_LAST).foreach { id =>
        assert(byId(id).address == base + id * 64L,
          f"ID $id line mismatch: 0x${byId(id).address}%x != 0x${base + id * 64L}%x")
      }

      // Keep all five owners live long enough to prove there is no hidden sixth
      // transaction or live-ID reuse before a response makes capacity available.
      cd.waitSampling(6)
      assert(requests.size == 5,
        s"a sixth request escaped while all five IDs were live: ${requests.toSeq}")
      releasedResponses = true
      // Legal cross-ID beat interleaving, with completion order unrelated to AR order.
      Seq(4, 1, 3, 0, 2).foreach(id => sendBeat(byId(id), beat = 0))
      Seq(3, 1, 4, 2, 0).foreach(id => sendBeat(byId(id), beat = 1))
    }
  }

  /** Bounded replacements for the hand-driven AXI helpers' unbounded blocking waits,
    * so a regression that stops the R or AR channel fails with a diagnosable message
    * instead of wedging the JVM. 4000 cycles is ~50x a single line's service time
    * through these harnesses. */
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

  def fetch(dut: Dut, cd: ClockDomain, pc: Long): BigInt = {
    dut.probe.logic.cmdIn.valid #= true
    dut.probe.logic.cmdIn.payload.pc #= pc
    cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
    dut.probe.logic.cmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
    dut.probe.logic.rspOut.payload.data.toBigInt
  }

  /** Wait until no fill is in flight, so a test can settle before sampling counters.
    * Prefetch runs asynchronously behind the fetch stream, so simply returning from
    * `fetch` does NOT mean the machine is quiet. */
  def settle(cd: ClockDomain, n: Int = 60): Unit = cd.waitSampling(n)

  // ---------------------------------------------------------------------------
  test("five IDs become live before data and cross-ID beat routing preserves every line", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val mem = new HeldFiveIdResponder(dut.icache.logic.axi, cd)
      mem.start()
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      val base = 0x2000L
      val got = fetch(dut, cd, base)
      assert(got == mem.window64(base), "demand line data was associated with the wrong RID")
      assert(mem.requests.size >= 5, s"only ${mem.requests.size} ARs fired before demand completed")

      // Stop the frontier from recycling a now-free speculative ID; the proof below
      // concerns the original five live lines and their out-of-order returns.
      dut.icache.logic.prefetchEnable #= false
      settle(cd, 40)
      val demandBefore = mem.requests.count(_.id == AxiIds.I_DEMAND)
      for (i <- 1 to 4) {
        val pc = base + i * 64L
        assert(fetch(dut, cd, pc) == mem.window64(pc),
          f"silent line $i at 0x$pc%x was corrupted by RID/beat routing")
        assert(fetch(dut, cd, pc + 0x20) == mem.window64(pc + 0x20),
          f"silent line $i high beat at 0x${pc + 0x20}%x was corrupted by RID/beat routing")
      }
      assert(mem.requests.count(_.id == AxiIds.I_DEMAND) == demandBefore,
        s"a speculative line was not resident after install: ${mem.requests.toSeq}")
    }
  }

  test("a demand miss opens the five-line window, and the next line then HITS", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar = new ArCounter(dut, cd)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      val pc = 0x2000L                     // line 0x2000, next line 0x2040 (same page)
      val got = fetch(dut, cd, pc)
      assert(got == IcacheSim.window64(pc), "demand fetch data")
      settle(cd)
      assert(ar.demand == 1, s"exactly one DEMAND refill expected, got ${ar.demand}")
      assert(ar.prefetch >= 4,
        s"the four speculative IDs must all be exercised, got ${ar.prefetch} prefetch ARs")
      assert((AxiIds.I_SPEC_BASE to AxiIds.I_SPEC_LAST).forall(id => ar.byId(id) > 0),
        s"the initial window must use IDs 1..4, got ${ar.byId.toSeq.sortBy(_._1)}")

      // The prefetched line must now be a genuine HIT: no further AR of any ID for it.
      val dBefore = ar.demand
      val got2 = fetch(dut, cd, pc + 0x40)
      assert(got2 == IcacheSim.window64(pc + 0x40), "prefetched line data must be correct")
      assert(ar.demand == dBefore,
        s"the prefetched line must HIT (no new demand AR): ${dBefore} -> ${ar.demand}")
    }
  }

  test("bubble-free resident demand stream does not starve speculative allocation", VerilatorTest) {
    // ══ TASK 12 REVIEW FIX I2: THIS TEST WAS SEED-FLAKY. ROOT-CAUSED, NOT PINNED-OVER.
    // As written before this fix it failed on roughly a fifth to two fifths of unpinned
    // runs, on BOTH sides of Task 12 -- a pre-existing defect in the test, not a
    // regression in the RTL. That matters beyond the annoyance: `IcachePrefetchSpec` is
    // part of the combined suite the plan's Tasks 14/16 gate on, so a member with a
    // ~1-in-5 spurious failure rate makes every "green" a single Bernoulli sample.
    //
    // ROOT CAUSE (measured, not guessed): `IcacheSim.attachMemory` builds an
    // `AxiMemModel`, whose AR channel is driven by `StreamReadyRandomizer` -- `ar.ready`
    // toggles pseudo-randomly, off the SIMULATION SEED. The old form counted AR FIRES
    // inside a fixed window of exactly eight accepted commands (~8 cycles), and the AR
    // holder can retire at most one request per two cycles, so the count landed anywhere
    // in 1..3 depending purely on how the randomizer happened to gate those cycles.
    // The threshold was `>= 2`. Nothing about the DUT varied; a seed sweep over 20 seeds
    // produced 1, 2 or 3 with 8/20 below the threshold. (Uninitialised registers, this
    // project's usual per-seed suspect, are NOT the cause here: the frontier registers
    // are all seeded deterministically by the warm-up fetch before the window opens.)
    //
    // FIX, in three parts, all attacking the root cause rather than hiding it:
    //   1. The measurement window is an OUTCOME wait, not a fixed cycle count. The
    //      bubble-free stream is sustained -- `cmdIn.valid` never drops, the PC cycles
    //      through the eight windows of the one resident line -- until two speculative
    //      ARs have fired or a 256-accept cap trips. Random AR backpressure now changes
    //      only HOW LONG the property takes to show, not WHETHER it holds. Across the
    //      same 20 seeds the loop finishes in 8..12 accepts, i.e. ~20x under the cap,
    //      and real starvation still fails the test by exhausting it.
    //   2. The original stimulus is preserved exactly: at least eight accepts, so all
    //      eight resident windows are still offered back to back before the loop may
    //      exit.
    //   3. The stream is PROVEN to have been bubble-free rather than assumed. The old
    //      form asserted nothing about the accept rate, so a DUT that stalled the fetch
    //      port and prefetched in the gap would have passed it. `burstCycles` /
    //      `burstAccepts` now bound the non-accepting cycles inside the window; the
    //      small slack allowed is the RTL's own documented, bounded (<= 3-cycle)
    //      speculative install dwell (see `pfAcceptOk` in `IcachePlugin`). Measured
    //      slack across the 20 seeds is 0 or 1 cycle.
    // Seeds are additionally PINNED and PLURAL: pinned so a gate result is reproducible,
    // plural so the claim is "holds across the randomizer's space" rather than one lucky
    // draw. All 20 verified passing at the fix commit. Compilation is shared, so the
    // twenty simulations cost a few hundred milliseconds in total.
    val compiled = simConfig.compile(new Dut)
    for (seed <- (1 to 20).map(_ * 7919)) {
      compiled.doSim(s"resident_stream_seed_$seed", seed) { dut =>
        val cd = dut.clockDomain
        cd.forkStimulus(period = 10)
        IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
        val ar = new ArCounter(dut, cd)

        // Accept-rate accounting for part 3 above, live only inside the burst window.
        var burstActive  = false
        var burstCycles  = 0
        var burstAccepts = 0
        cd.onSamplings {
          if (burstActive) {
            burstCycles += 1
            if (dut.probe.logic.cmdIn.valid.toBoolean && dut.probe.logic.cmdIn.ready.toBoolean)
              burstAccepts += 1
          }
        }

        dut.probe.logic.cmdIn.valid #= false
        dut.probe.logic.cmdIn.payload.pc #= 0
        dut.icache.logic.invalidateAll #= false
        cd.waitSampling(4)

        // Warm one whole line without speculation, then offer all eight resident
        // windows without an intentional bubble.  Same-line accepts update demand
        // provenance but must not suppress the background allocator.
        dut.icache.logic.prefetchEnable #= false
        assert(fetch(dut, cd, 0x4000L) == IcacheSim.window64(0x4000L))
        dut.icache.logic.prefetchEnable #= true
        val pfBeforeBurst = ar.prefetch
        dut.probe.logic.cmdIn.valid #= true
        burstActive = true
        var issued = 0
        while (issued < 8 || ((ar.prefetch - pfBeforeBurst < 2) && issued < 256)) {
          dut.probe.logic.cmdIn.payload.pc #= (0x4000L + (issued % 8) * 8L)
          waitSamplingWhereBounded(cd, s"seed $seed: cmdIn.ready during the resident burst")(
            dut.probe.logic.cmdIn.ready.toBoolean)
          issued += 1
        }
        burstActive = false
        val pfDuringBurst = ar.prefetch - pfBeforeBurst
        dut.probe.logic.cmdIn.valid #= false
        dut.icache.logic.prefetchEnable #= false

        assert(pfDuringBurst >= 2,
          s"seed $seed: resident II=1 traffic starved the fill allocator; only $pfDuringBurst speculative ARs fired across $issued continuously offered commands")
        assert(burstCycles - burstAccepts <= 4,
          s"seed $seed: the demand stream was not bubble-free, so the allocator was not measured under II=1 pressure: $burstAccepts accepts in $burstCycles cycles")
      }
    }
  }

  test("AR-pending demotion launches the blocking silent owner without deadlock", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val axi = dut.icache.logic.axi
      val arTrace = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
      @volatile var releaseSpec = false

      axi.ar.ready #= true
      axi.r.valid #= false
      axi.r.payload.data #= 0
      axi.r.payload.id #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0

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
          cd.waitSamplingWhere(axi.r.ready.toBoolean)
          axi.r.valid #= false
          cd.waitSampling()
        }
      }

      fork {
        cd.waitSamplingWhere(axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
        val demand = (axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong)
        arTrace += demand
        assert(demand == ((AxiIds.I_DEMAND, 0x6000L)), s"unexpected bootstrap AR $demand")
        // Keep the first speculative request in the AR holder and later slots in
        // AR_PENDING while the architectural demand for ID2's line is offered.
        axi.ar.ready #= false
        sendLine(AxiIds.I_DEMAND, 0x6000L)

        while (!releaseSpec) cd.waitSampling()
        axi.ar.ready #= true
        cd.waitSamplingWhere(axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
        arTrace += ((axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong))
        cd.waitSamplingWhere(axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
        val blocker = (axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong)
        arTrace += blocker
        assert(blocker == ((AxiIds.iRefill(2), 0x6080L)),
          s"held demand did not prioritize its blocking AR-pending owner: trace=$arTrace")
        axi.ar.ready #= false
        sendLine(blocker._1, blocker._2)
      }

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      assert(fetch(dut, cd, 0x6000L) == IcacheSim.window64(0x6000L))
      cd.waitSamplingWhere(IcacheArrayProbe.pfSlotValid(dut.icache, 1) &&
                           !IcacheArrayProbe.pfSlotArSent(dut.icache, 1))

      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= 0x6080L
      cd.waitSampling(4)
      assert(!dut.probe.logic.cmdIn.ready.toBoolean,
        "setup did not hold the demand behind its AR-pending silent owner")
      releaseSpec = true
      cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      assert(dut.probe.logic.rspOut.payload.data.toBigInt == IcacheSim.window64(0x6080L),
        "demoted AR-pending demand returned incorrect data")
      assert(arTrace.count { case (id, address) =>
        id == AxiIds.I_DEMAND && address == 0x6080L
      } == 0, s"demotion issued a duplicate demand AR: $arTrace")
    }
  }

  test("silent refill error is discarded and a held demand retries precisely on ID0", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val axi = dut.icache.logic.axi
      val arTrace = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
      @volatile var releaseSilentError = false
      var rspCount = 0

      axi.ar.ready #= true
      axi.r.valid #= false
      axi.r.payload.data #= 0
      axi.r.payload.id #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0
      cd.onSamplings {
        if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
          arTrace += ((axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong))
        if (dut.probe.logic.rspOut.valid.toBoolean) rspCount += 1
      }

      def beat(address: Long, phase: Int): BigInt =
        (0 until 32).foldLeft(BigInt(0)) { (acc, i) =>
          acc | (BigInt(IcacheSim.memByte(address + phase * 32L + i)) << (8 * i))
        }
      def sendLine(id: Int, address: Long, fail: Boolean): Unit = {
        for (phase <- 0 to 1) {
          axi.r.valid #= true
          axi.r.payload.id #= id
          axi.r.payload.data #= beat(address, phase)
          axi.r.payload.resp #= (if (fail && phase == 1) 3 else 0)
          axi.r.payload.last #= (phase == 1)
          cd.waitSamplingWhere(axi.r.ready.toBoolean)
          axi.r.valid #= false
          cd.waitSampling()
        }
      }
      def waitReq(id: Int, address: Long, occurrence: Int = 1): Unit = {
        while (arTrace.count(_ == ((id, address))) < occurrence) cd.waitSampling()
      }

      fork {
        waitReq(AxiIds.I_DEMAND, 0x2000L)
        sendLine(AxiIds.I_DEMAND, 0x2000L, fail = false)
        waitReq(AxiIds.I_SPEC_BASE, 0x2040L)
        while (!releaseSilentError) cd.waitSampling()
        sendLine(AxiIds.I_SPEC_BASE, 0x2040L, fail = true)
        waitReq(AxiIds.I_DEMAND, 0x2040L)
        sendLine(AxiIds.I_DEMAND, 0x2040L, fail = false)
      }

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      assert(fetch(dut, cd, 0x2000L) == IcacheSim.window64(0x2000L))
      cd.waitSampling()
      val rspAfterBase = rspCount
      assert(!dut.probe.logic.rspOut.valid.toBoolean)
      waitReq(AxiIds.I_SPEC_BASE, 0x2040L)

      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= 0x2040L
      cd.waitSampling(4)
      assert(!dut.probe.logic.cmdIn.ready.toBoolean,
        "matching demand was not held behind the silent refill")
      releaseSilentError = true
      cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      assert(!dut.probe.logic.rspOut.payload.fault.toBoolean,
        "silent speculative error leaked into the architectural retry")
      assert(dut.probe.logic.rspOut.payload.data.toBigInt == IcacheSim.window64(0x2040L),
        "architectural retry returned incorrect data")
      assert(arTrace.count(_._2 == 0x2040L) == 2,
        s"expected one silent error plus one precise demand retry: $arTrace")
      assert(arTrace.count(_ == ((AxiIds.I_DEMAND, 0x2040L))) == 1,
        s"precise retry did not use ID0 exactly once: $arTrace")
      assert(rspCount == rspAfterBase + 1,
        s"silent error leaked an extra architectural response: $rspAfterBase -> $rspCount")
    }
  }

  Seq("AR_PENDING", "R0", "R1", "COMPLETE", "INSTALL0", "INSTALL1").foreach { stage =>
    test(s"invalidation poisons a silent fill at $stage and forces a later demand refill", VerilatorTest) {
      simConfig.compile(new Dut).doSim { dut =>
        val cd = dut.clockDomain
        cd.forkStimulus(period = 10)
        val axi = dut.icache.logic.axi
        val arTrace = scala.collection.mutable.ArrayBuffer[(Int, Long)]()
        val responded = scala.collection.mutable.Set[Int]()

        axi.ar.ready #= true
        axi.r.valid #= false
        axi.r.payload.data #= 0
        axi.r.payload.id #= 0
        axi.r.payload.last #= false
        axi.r.payload.resp #= 0
        cd.onSamplings {
          if (axi.ar.valid.toBoolean && axi.ar.ready.toBoolean)
            arTrace += ((axi.ar.payload.id.toInt, axi.ar.payload.addr.toLong))
        }

        def beat(address: Long, phase: Int): BigInt =
          (0 until 32).foldLeft(BigInt(0)) { (acc, i) =>
            acc | (BigInt(IcacheSim.memByte(address + phase * 32L + i)) << (8 * i))
          }
        def sendBeat(id: Int, address: Long, phase: Int): Unit = {
          axi.r.valid #= true
          axi.r.payload.id #= id
          axi.r.payload.data #= beat(address, phase)
          axi.r.payload.resp #= 0
          axi.r.payload.last #= (phase == 1)
          cd.waitSamplingWhere(axi.r.ready.toBoolean)
          axi.r.valid #= false
        }
        def sendLine(id: Int, address: Long): Unit = {
          sendBeat(id, address, 0)
          cd.waitSampling()
          sendBeat(id, address, 1)
          cd.waitSampling()
          responded += id
        }
        def waitReq(id: Int, address: Long): Unit =
          while (!arTrace.contains((id, address))) cd.waitSampling()
        def pulseInvalidate(): Unit = {
          dut.icache.logic.invalidateAll #= true
          cd.waitSampling()
          dut.icache.logic.invalidateAll #= false
        }

        // Bootstrap demand responds, but every speculative AR is held so ID1 is
        // observably AR_PENDING and later stages can be reached deterministically.
        fork {
          waitReq(AxiIds.I_DEMAND, 0x2000L)
          axi.ar.ready #= false
          sendLine(AxiIds.I_DEMAND, 0x2000L)
        }

        dut.probe.logic.cmdIn.valid #= false
        dut.probe.logic.cmdIn.payload.pc #= 0
        dut.icache.logic.invalidateAll #= false
        cd.waitSampling(4)
        dut.icache.logic.prefetchEnable #= true
        cd.waitSampling(2)
        assert(fetch(dut, cd, 0x2000L) == IcacheSim.window64(0x2000L))
        responded += AxiIds.I_DEMAND
        cd.waitSamplingWhere(IcacheArrayProbe.pfSlotValid(dut.icache, 0) &&
                             !IcacheArrayProbe.pfSlotArSent(dut.icache, 0))

        if (stage == "AR_PENDING") pulseInvalidate()

        axi.ar.ready #= true
        waitReq(AxiIds.I_SPEC_BASE, 0x2040L)
        axi.ar.ready #= false

        stage match {
          case "AR_PENDING" => sendLine(AxiIds.I_SPEC_BASE, 0x2040L)
          case "R0" =>
            dut.icache.logic.invalidateAll #= true
            sendBeat(AxiIds.I_SPEC_BASE, 0x2040L, 0)
            dut.icache.logic.invalidateAll #= false
            cd.waitSampling()
            sendBeat(AxiIds.I_SPEC_BASE, 0x2040L, 1)
            responded += AxiIds.I_SPEC_BASE
          case "R1" =>
            sendBeat(AxiIds.I_SPEC_BASE, 0x2040L, 0)
            cd.waitSampling()
            dut.icache.logic.invalidateAll #= true
            sendBeat(AxiIds.I_SPEC_BASE, 0x2040L, 1)
            dut.icache.logic.invalidateAll #= false
            responded += AxiIds.I_SPEC_BASE
          case "COMPLETE" =>
            sendBeat(AxiIds.I_SPEC_BASE, 0x2040L, 0)
            cd.waitSampling()
            sendBeat(AxiIds.I_SPEC_BASE, 0x2040L, 1)
            responded += AxiIds.I_SPEC_BASE
            sleep(1)
            assert(IcacheArrayProbe.pfSlotComplete(dut.icache, 0),
              "COMPLETE setup missed the post-RLAST/pre-install window")
            pulseInvalidate()
          case "INSTALL0" | "INSTALL1" =>
            sendLine(AxiIds.I_SPEC_BASE, 0x2040L)
            val phase = if (stage == "INSTALL0") 0 else 1
            cd.waitSamplingWhere(dut.icache.logic.predActive.toBoolean &&
              dut.icache.logic.predIsPf.toBoolean && dut.icache.logic.commitBeat.toInt == phase)
            pulseInvalidate()
        }

        // Drain every poisoned live ID so no stable AXI request can obscure the
        // architectural retry. None may install after the invalidation.
        axi.ar.ready #= true
        while ((0 until AxiIds.I_SPEC_SLOTS).exists(i => IcacheArrayProbe.pfSlotValid(dut.icache, i))) {
          val pending = arTrace.collectFirst {
            case (id, address) if id >= AxiIds.I_SPEC_BASE && id <= AxiIds.I_SPEC_LAST &&
              !responded(id) => (id, address)
          }
          pending match {
            case Some((id, address)) =>
              axi.ar.ready #= false
              sendLine(id, address)
              axi.ar.ready #= true
            case None => cd.waitSampling()
          }
        }

        dut.icache.logic.prefetchEnable #= false
        cd.waitSampling(4)
        fork {
          waitReq(AxiIds.I_DEMAND, 0x2040L)
          sendLine(AxiIds.I_DEMAND, 0x2040L)
        }
        assert(fetch(dut, cd, 0x2040L) == IcacheSim.window64(0x2040L),
          s"$stage invalidation allowed poisoned/corrupt line visibility")
        assert(arTrace.count(_ == ((AxiIds.I_DEMAND, 0x2040L))) == 1,
          s"$stage invalidation did not force exactly one real demand refill: $arTrace")
      }
    }
  }

  test("four wrong-path silent IDs cannot block an unrelated redirect demand on ID0", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val axi = dut.icache.logic.axi
      val arTrace = scala.collection.mutable.ArrayBuffer[(Int, Long)]()

      axi.ar.ready #= true
      axi.r.valid #= false
      axi.r.payload.data #= 0
      axi.r.payload.id #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0
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
          cd.waitSamplingWhere(axi.r.ready.toBoolean)
          axi.r.valid #= false
          cd.waitSampling()
        }
      }
      def waitReq(id: Int, address: Long): Unit =
        while (!arTrace.contains((id, address))) cd.waitSampling()

      fork {
        waitReq(AxiIds.I_DEMAND, 0x2000L)
        sendLine(AxiIds.I_DEMAND, 0x2000L)
        // Do not return any wrong-path speculative data. Reserved demand ID0 must
        // still be admitted and routed independently.
        waitReq(AxiIds.I_DEMAND, 0x8000L)
        sendLine(AxiIds.I_DEMAND, 0x8000L)
      }

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      assert(fetch(dut, cd, 0x2000L) == IcacheSim.window64(0x2000L))
      cd.waitSamplingWhere((AxiIds.I_SPEC_BASE to AxiIds.I_SPEC_LAST).forall(id =>
        arTrace.exists(_._1 == id)))
      // `arTrace` is appended from `cd.onSamplings` on the sampling the AR FIRES, but
      // `pfArSent(idx) := True` is a register write on that same edge, so it only reads
      // back True from the FOLLOWING sampling. Whether the `waitSamplingWhere` predicate
      // above observes the fourth AR's trace entry before or after that edge is decided
      // by sim-thread/callback scheduling, which is not stable under host CPU contention
      // -- observed live: this assert failed once with `slot3(v=true,ar=false,c=false)`
      // and its own AR already present in `arTrace`, while the AR cadence (fires at
      // cycles 9/11/13/15/17, check at 18) is byte-identical either way, i.e. the flake
      // is this sampling boundary and not an RTL timing shift. One extra sampling
      // removes the ambiguity without weakening the check: all four slots must still be
      // simultaneously valid, AR-sent and incomplete.
      cd.waitSampling()
      assert((0 until AxiIds.I_SPEC_SLOTS).forall(i =>
        IcacheArrayProbe.pfSlotValid(dut.icache, i) && IcacheArrayProbe.pfSlotArSent(dut.icache, i) &&
          !IcacheArrayProbe.pfSlotComplete(dut.icache, i)),
        "setup failed to hold all four wrong-path slots in FILL")

      val got = fetch(dut, cd, 0x8000L)
      assert(got == IcacheSim.window64(0x8000L),
        "redirect target demand was corrupted or blocked by wrong-path fills")
      assert(arTrace.count(_ == ((AxiIds.I_DEMAND, 0x8000L))) == 1,
        s"redirect target did not get its one reserved ID0 transaction: $arTrace")
      assert((0 until AxiIds.I_SPEC_SLOTS).forall(i =>
        IcacheArrayProbe.pfSlotValid(dut.icache, i) && !IcacheArrayProbe.pfSlotComplete(dut.icache, i)),
        "redirect target incorrectly waited for or consumed a wrong-path response")
    }
  }

  test("prefetchEnable=false issues ZERO prefetch ARs", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar = new ArCounter(dut, cd)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= false
      cd.waitSampling(2)
      fetch(dut, cd, 0x2000L)
      settle(cd)
      assert(ar.prefetch == 0, s"prefetch disabled but ${ar.prefetch} prefetch ARs issued")
      assert(ar.demand == 1, s"exactly one demand refill expected, got ${ar.demand}")
    }
  }

  test("sequential demand extension keeps a run of lines demand-miss free", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar = new ArCounter(dut, cd)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      // Walk 6 consecutive lines. Only the FIRST may take a demand refill: every
      // accepted cacheable demand extends the bounded moving window.
      val base = 0x3000L
      for (i <- 0 until 6) {
        val a = base + 0x40L * i
        // Give the prefetch time to land before demanding the next line, which is the
        // normal regime: eight sequential 8-byte fetches per 64-byte line.
        settle(cd, 40)
        val got = fetch(dut, cd, a)
        assert(got == IcacheSim.window64(a), f"line $i data at 0x$a%x")
      }
      settle(cd)
      assert(ar.demand == 1,
        s"only the first line should demand-refill; got ${ar.demand} demand ARs " +
          s"(${ar.prefetch} prefetch ARs). A count > 1 means the moving window did not stay ahead.")
    }
  }

  test("P1/P4: no prefetch is issued when the next line would cross a page boundary", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar = new ArCounter(dut, cd)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      // 0x2FC0 is the LAST 64-byte line of the 4 KiB page at 0x2000; its next line
      // 0x3000 is in the NEXT page, whose translation this fetch has NOT resolved.
      // Rule P4: dropped, not guessed. Rule P1: no walk is triggered either -- there is
      // no ITLB probe on this path at all, by construction.
      fetch(dut, cd, 0x2FC0L)
      settle(cd)
      assert(ar.prefetch == 0,
        s"a page-crossing next-line must NOT be prefetched (rule P4); got ${ar.prefetch} prefetch ARs")
      assert(ar.demand == 1, s"the demand refill itself must still happen, got ${ar.demand}")
    }
  }

  test("P1: no prefetch is issued off an INHIBITED-page fetch", VerilatorTest) {
    simConfig.compile(new Dut(new ICacheModeTranslationPlugin)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar = new ArCounter(dut, cd)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      // Mark VPN 0x00002 (VA 0x2000-0x2fff) cache-INHIBITED.
      dut.xlate.asInstanceOf[ICacheModeTranslationPlugin].logic.cmodeEn  #= true
      dut.xlate.asInstanceOf[ICacheModeTranslationPlugin].logic.cmodeVpn #= 0x00002
      dut.xlate.asInstanceOf[ICacheModeTranslationPlugin].logic.cmodeSel #= CacheMode.INHIBITED
      cd.waitSampling(2)

      fetch(dut, cd, 0x2000L)
      settle(cd)
      assert(ar.prefetch == 0,
        s"rule P1 forbids prefetching for an INHIBITED page; got ${ar.prefetch} prefetch ARs")
    }
  }

  /** The M4 "cycle T" residual, made OBSERVABLE (implementation plan Task 13).
    *
    * `IcachePlugin.scala`'s `s0KillsWindowQ` comment block documents a deliberately
    * accepted, one-cycle rule-P1 residual: on the ACCEPT cycle T of a command whose
    * LIVE translation verdict is FAULT (or INHIBITED), nothing registered carries that
    * verdict yet, so the speculative allocator can still make ONE allocation -- and
    * launch its AR -- out of a window the core is being told, on that very cycle, no
    * longer describes a page it may speculate into. `s0KillsWindowQ` closes T+1 and the
    * window kill itself closes T+2 onward; cycle T is the tail the design does not hold.
    *
    * Until this test that residual was documented in a comment and exercised by
    * nothing. The rule it bends is a SAFETY rule, so "bounded to one line of the same
    * page" has to be a measured fact, not a paragraph. This test measures it:
    *
    *   - it seeds a real five-line window at 0x2000 with the allocator SHUT OFF
    *     (`prefetchEnable = false` still seeds `pfSeqValid`/`pfNextPa`/`pfLimitPa` --
    *     the seed runs off `s1Disp`, and `prefetchEnable` appears only inside
    *     `pfWindowHasCandidate`, i.e. it gates ALLOCATION, not the frontier), so the
    *     speculative AR count going in is provably zero;
    *   - it then opens the allocator and offers the faulting command IN THE SAME
    *     SIMULATION DELTA, so the first cycle `prefetchEnable` is visible IS the accept
    *     cycle T, by construction and with no cycle counting;
    *   - and it asserts the residual's exact documented shape: at most ONE speculative
    *     line, in the SAME 4 KiB page.
    *
    * Both directions matter. `<= 1` is the safety bound -- it is what fails if the
    * window kill regresses (deleting `!s0KillsWindowQ` re-opens T+1 and makes it two;
    * mutation-verified, Task 13). `== 1` is the non-vacuity check: a zero would mean
    * the residual is not being reached and the test proves nothing -- or that the
    * residual was closed, in which case `IcachePlugin.scala`'s comment is now wrong and
    * must be updated together with this test. Neither is allowed to pass silently.
    */
  test("P1 residual (cycle T): a fetch whose LIVE verdict is FAULT allocates AT MOST ONE " +
       "speculative line, in its own page", VerilatorTest) {
    simConfig.compile(new Dut(new ICacheModeTranslationPlugin)).doSim("pf-cycle-t-residual") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar    = new ArCounter(dut, cd)
      val xlate = dut.xlate.asInstanceOf[ICacheModeTranslationPlugin]
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      xlate.logic.forceFault #= false
      cd.waitSampling(4)
      // AFTER the reset window, not before: `prefetchEnable` is a RegInit(True), so a
      // poke issued while forkStimulus still holds reset is simply re-initialised away
      // (this suite's other prefetchEnable tests all poke here for the same reason).
      dut.icache.logic.prefetchEnable #= false
      cd.waitSampling(2)

      fetch(dut, cd, 0x2000L)
      settle(cd)
      assert(ar.demand == 1, s"the seeding demand refill did not happen: AR trace=${ar.fires}")
      assert(ar.prefetch == 0,
        s"prefetchEnable=false must issue ZERO speculative ARs; AR trace=${ar.fires}")
      // Window state going in: pfSeqValid, pfDemandLine=0x2000, pfNextPa=0x2040,
      // pfLimitPa=0x2140 -- four in-page candidates and four free slots.

      // Make the page's LIVE verdict a fault. Nothing is in flight, so this changes no
      // registered state; it is visible only to the next accept's combinational verdict.
      xlate.logic.forceFault #= true
      cd.waitSampling(2)
      assert(ar.prefetch == 0, s"the allocator is still shut off; AR trace=${ar.fires}")

      // THE CYCLE-T SETUP. Both pokes land in one delta, so the first cycle on which
      // `pfWindowHasCandidate` can be true is the same cycle `cmdPort.fire` is true and
      // the ITLB is reporting FAULT -- cycle T exactly, with nothing to calibrate.
      dut.icache.logic.prefetchEnable  #= true
      dut.probe.logic.cmdIn.valid      #= true
      dut.probe.logic.cmdIn.payload.pc #= 0x2000L
      waitSamplingWhereBounded(cd, "the faulting command to be accepted")(
        dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      waitSamplingWhereBounded(cd, "the faulting command's response")(
        dut.probe.logic.rspOut.valid.toBoolean)
      assert(dut.probe.logic.rspOut.payload.fault.toBoolean,
        "the second fetch of 0x2000 did NOT fault -- forceFault never reached the accept, " +
        "so cycle T was never entered and this test proves nothing")
      settle(cd, 200)
      dut.icache.logic.prefetchEnable #= false
      xlate.logic.forceFault #= false

      val spec = ar.fires.filter { case (id, _) =>
        id >= AxiIds.I_SPEC_BASE && id <= AxiIds.I_SPEC_LAST }
      assert(spec.size <= 1,
        s"RULE-P1 BOUND VIOLATED: ${spec.size} speculative ARs were launched out of a " +
        s"window condemned on cycle T; IcachePlugin.scala's s0KillsWindowQ comment bounds " +
        s"this residual at ONE line. The T+1 window-kill term is the usual regression " +
        s"here. Speculative trace=$spec, full AR trace=${ar.fires}")
      assert(spec.size == 1,
        s"NON-VACUITY: expected the documented one-line cycle-T residual, saw none. Either " +
        s"the window/frontier setup stopped reaching the residual (this test then proves " +
        s"nothing and must be repaired) or the residual was CLOSED, in which case " +
        s"IcachePlugin.scala's 'ACCEPTED RESIDUAL: CYCLE T' comment is now wrong. Full AR " +
        s"trace=${ar.fires}")
      val (_, addr) = spec.head
      assert((addr & ~0xfffL) == 0x2000L,
        f"the cycle-T residual escaped its own page: a speculative AR to 0x$addr%x is not in " +
        f"the 4 KiB page of 0x2000. The residual's whole containment argument is that " +
        f"pfWindowHasCandidate pins every candidate to pfDemandLine's page.")
      assert(addr == 0x2040L,
        f"expected the frontier's own next line 0x2040, got 0x$addr%x (AR trace=${ar.fires})")
    }
  }

  /** D3-SET-I, the `setBlocked` guard's directed test (implementation plan Task 13,
    * spec section 12.2 mutation 4).
    *
    * A lookup that indexes the SET an in-flight fill is committing into must not be
    * answered on the array-write cycle: `tagMem` is a write-first async-read LUTRAM
    * while `valids` is a register array, so on that cycle `tagQ` captures the NEW tag
    * against the OLD valid bit -- a verdict computed from two sources that disagree by
    * exactly one cycle. Because the victim way is cold, `validsQ` says INVALID while
    * `tagQ` already says the line is there, so the lookup is answered as a MISS for a
    * line that becomes resident on this very cycle: a duplicate AR for an address the
    * cache already owns, and a SECOND way of the same set allocated to the SAME tag,
    * which is a 2-hot `s1HitVec` on every later lookup of it. Spec risk R3's class --
    * a silently mis-attributed line, not a crash.
    *
    * WHY THIS TEST EXISTS AT ALL, and read this before "simplifying" the guard away:
    * the plan's mutation 4 (`setBlocked := False`) kills NOTHING on its own -- the full
    * IcacheSpec (17/17) and IcachePrefetchSpec both stay green. That is not evidence the
    * hazard is imaginary; it is evidence the guard is REDUNDANT today, exactly like its
    * sibling `pfInstallSetConflict` (see that signal's own "HONEST STATUS" comment).
    * `fillArrayWrActive` is asserted only from the PREDECODE dwell of a SPECULATIVE
    * install, and that entry's `mshrValid` is not cleared until the end of the dwell, so
    * `pfLookupSetBusy` -- and therefore SG-1's `pfAcceptOk` -- already holds the accept
    * gate shut for exactly `lookupSet === installSet`. The two guards overlap perfectly.
    *
    * So the honest proof is a MATRIX, and this test is its bottom row (Task 13, all four
    * rows re-run):
    *   setBlocked removed only ............... PASSES (redundant with pfAcceptOk).
    *   pfAcceptOk opened on the write cycle .. PASSES (setBlocked still shuts the gate).
    *   BOTH ................................. THIS TEST FAILS.
    * A future relaxation of `pfAcceptOk` (SG-1 is explicitly flagged as conservative and
    * is re-measured by the IPC gate) makes `setBlocked` the only thing left holding this.
    *
    * A NEGATIVE RESULT WORTH KEEPING, because it is the obvious way to write this test
    * and it does not work: the "victim way ALREADY VALID under a different tag" variant
    * -- pre-fill all four ways so the spurious HIT lands on a line being overwritten --
    * does NOT corrupt the delivered data, and was measured not to (it passes under the
    * full double mutation). By the tag/valid write cycle the incoming line's LOW beat is
    * already written and its HIGH beat is written by that same cycle's `lineMem` write,
    * which the read port sees write-first, so both windows read back correct. The cold
    * way above is the variant that actually discriminates.
    */
  test("D3-SET-I: a lookup into the installing SET on the array-write cycle must not be " +
       "answered from a tag/valid pair that is one cycle apart", VerilatorTest) {
    simConfig.compile(new Dut).doSim("d3-set-i-write-cycle") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar = new ArCounter(dut, cd)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      // AFTER the reset window (see the cycle-T test above for why).
      dut.icache.logic.prefetchEnable #= false
      cd.waitSampling(2)

      // 1. Seed a window with EXACTLY ONE in-page candidate, so the next allocation
      //    commit is unambiguous: 0x5FC0 is the LAST line of page 5, so `seedPfWindow`'s
      //    page clamp pins pfLimitPa at it and rule P4 stops the frontier there. The
      //    demand line 0x5F80 is set 62, so set 63 stays COLD and victim(63) stays 0.
      fetch(dut, cd, 0x5F80L)
      settle(cd)
      for (w <- 0 until 4)
        assert(!IcacheArrayProbe.wayValid(dut.icache, w, 63),
          s"set 63 way $w is already valid -- the cold-victim precondition this test " +
          s"needs (validsQ False while tagQ already carries the incoming tag) is gone")

      // 2. Open the allocator: exactly one speculative fill, of 0x5FC0, into set 63,
      //    way victim(63) == 0.
      dut.icache.logic.prefetchEnable #= true
      waitSamplingWhereBounded(cd, "the speculative install's allocation-commit cycle")(
        dut.icache.logic.dbgAllocCommitPending.toBoolean)

      // 3. Offer a fetch of that same line so that it is visible during the tag/valid
      //    write cycle ITSELF. A testbench samples just BEFORE the edge that latches what
      //    it observed, so a poke issued at the `dbgAllocCommitPending` sampling point
      //    first takes effect on the NEXT cycle -- which is the write cycle. This is the
      //    identical one-cycle-early trigger IcacheSpec's invalidate-race test uses, for
      //    the identical reason; aiming at `dbgAllocCommitCycle` directly lands one cycle
      //    late, where the test passes whether or not the guard exists.
      dut.probe.logic.cmdIn.valid      #= true
      dut.probe.logic.cmdIn.payload.pc #= 0x5FC0L
      cd.waitSampling()
      // Self-check the alignment instead of assuming it: this sampling point observes the
      // cycle the offer was high for, and it must be the array-write cycle.
      assert(dut.icache.logic.dbgAllocCommitCycle.toBoolean,
        "test alignment lost: the probe fetch was not offered during the PREDECODE " +
        "allocation-write cycle, so the D3-SET-I window was never actually entered")
      // Was it taken ON that cycle? On correct RTL, no -- SG-1's `pfAcceptOk` and
      // `setBlocked` each independently hold the gate shut. Recorded for the diagnostic
      // message rather than asserted: the properties under test are architectural.
      val takenOnWriteCycle = dut.probe.logic.cmdIn.ready.toBoolean
      if (!takenOnWriteCycle)
        waitSamplingWhereBounded(cd, "the probe fetch to be accepted")(
          dut.probe.logic.cmdIn.ready.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      waitSamplingWhereBounded(cd, "the probe fetch's response")(
        dut.probe.logic.rspOut.valid.toBoolean)
      val got = dut.probe.logic.rspOut.payload.data.toBigInt
      settle(cd, 200)

      val ctx = f" (probe accepted ON the array-write cycle: $takenOnWriteCycle; " +
                f"AR trace=${ar.fires})"
      assert(got == IcacheSim.window64(0x5FC0L),
        f"the fetch of 0x5FC0 was answered with 0x${got.toString(16)}, expected " +
        f"0x${IcacheSim.window64(0x5FC0L).toString(16)}$ctx")
      // THE ASSERTIONS. A lookup answered off the split tag/valid pair reports a MISS for
      // a line that is becoming resident on that cycle, so it re-fetches it...
      assert(ar.at(0x5FC0L) == 1,
        s"D3-SET-I VIOLATED: line 0x5FC0 was fetched over AXI ${ar.at(0x5FC0L)} times. A " +
        s"lookup answered on the array-write cycle read the NEW tag against the OLD " +
        s"(cold) valid bit, reported a miss for a line the cache was installing that very " +
        s"cycle, and issued a duplicate transaction for it$ctx")
      // ...and installs it a SECOND time, into a second way of the same set, which makes
      // every later lookup of it a 2-hot way select.
      val tagged = (0 until 4).count(w =>
        IcacheArrayProbe.wayValid(dut.icache, w, 63) &&
        IcacheArrayProbe.wayTag(dut.icache, w, 63) == BigInt(0x5))
      assert(tagged == 1,
        s"D3-SET-I VIOLATED: $tagged ways of set 63 are valid with tag 0x5. One line is " +
        s"resident twice in the same set -- a 2-hot s1HitVec on every later lookup of " +
        s"it, which is the design's own hard invariant$ctx")
    }
  }

  test("suppression: a next line that is ALREADY RESIDENT is not prefetched again", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar = new ArCounter(dut, cd)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      // Make line 0x2040 resident first (fetching it also prefetches 0x2080).
      fetch(dut, cd, 0x2040L)
      settle(cd)
      // Now demand-miss line 0x2000, whose next line 0x2040 is already resident.
      fetch(dut, cd, 0x2000L)
      settle(cd)
      assert(ar.at(0x2040L) == 1,
        s"an already-resident line must not be fetched twice; AR trace=${ar.fires}")
    }
  }

  test("slice I1: the fetch port stays OPEN during a prefetch fill and HITS are served", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar = new ArCounter(dut, cd)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      // Warm line 0x4000 resident (and let its prefetch of 0x4040 finish).
      fetch(dut, cd, 0x4000L)
      settle(cd)
      // Now demand a line whose prefetch will be in flight: 0x8000 -> prefetch 0x8040.
      fetch(dut, cd, 0x8000L)
      // Do NOT settle. Immediately fetch the already-resident 0x4000 -- if the fetch
      // port were closed for the whole prefetch fill this would block until it landed.
      // (`fetch` itself would simply take longer, so the discriminating assertion is
      // the CYCLE COUNT, measured against the known two-cycle resident latency.)
      val t0 = simTime()
      val got = fetch(dut, cd, 0x4000L)
      val t1 = simTime()
      assert(got == IcacheSim.window64(0x4000L), "hit-under-prefetch must return correct data")
      // A 64-byte line fill through this harness takes far more than 20 cycles (200 sim
      // time units at period 10); a hit served under it takes a handful.
      assert((t1 - t0) < 200,
        s"a HIT served during a prefetch fill took ${(t1 - t0) / 10} cycles -- the fetch " +
          "port appears to be blocked for the whole prefetch, which is the thing slice I1 exists to prevent")
    }
  }

  test("demotion: a demand fetch of the line currently being prefetched issues NO second AR", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar = new ArCounter(dut, cd)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      fetch(dut, cd, 0x6000L)         // demand 0x6000 -> prefetch 0x6040 starts
      val before = ar.total
      // Immediately demand 0x6040 while its prefetch is still in flight. The offered
      // Stream command must be backpressured with a stable payload, then re-look up
      // after install (plan I1.2 / I3.2's sanctioned merge implementation). This must
      // produce NO additional AR of any ID and exactly one eventual command fire.
      var heldCycles = 0
      var demandFires = 0
      cd.onSamplings {
        if (dut.probe.logic.cmdIn.valid.toBoolean) {
          assert(dut.probe.logic.cmdIn.payload.pc.toLong == 0x6040L,
            f"held demotion payload changed to 0x${dut.probe.logic.cmdIn.payload.pc.toLong}%x")
          if (dut.probe.logic.cmdIn.ready.toBoolean) demandFires += 1 else heldCycles += 1
        }
      }
      val got = fetch(dut, cd, 0x6040L)
      assert(got == IcacheSim.window64(0x6040L), "demoted demand fetch must get correct data")
      assert(heldCycles > 0, "demotion setup never exercised Stream backpressure")
      assert(demandFires == 1, s"demoted command must fire exactly once, got $demandFires")
      settle(cd)
      // Settling may legitimately extend the moving window to 0x6080.
      assert(ar.byId(AxiIds.I_DEMAND) == 1,
        s"the demand fetch of an in-flight prefetched line must NOT issue a second demand AR; " +
          s"demand ARs = ${ar.byId(AxiIds.I_DEMAND)} (total ${before} -> ${ar.total})")
    }
  }

  /** The AR arbiter's demand-priority Mux
    * (`pfChosenArSel = Mux(heldDemandMiss, pfBlockingArSel, pfArSel)`) was UNPROVEN by
    * this suite: mutating it to a bare `pfArSel` failed nothing. The reason is a
    * coincidence in the pre-existing "AR-pending demotion" test above -- there the
    * blocking owner is also the LOWEST-numbered slot still wanting an AR, which is
    * exactly what the unconditional `pfArSel` picks, so the Mux is invisible to it.
    *
    * This test removes the coincidence: the held demand's blocking owner is the
    * HIGHEST-numbered slot (index 3, the farthest of the five-line window) while
    * slots 1 and 2 are still AR_PENDING and therefore rank ahead of it under
    * `pfArSel`. Only the Mux launches the AR that can actually unblock the demand.
    * Confirmed by mutation: with `pfChosenArSel = pfArSel` this test fails on the
    * "blocking owner" assert with slot 1's line instead.
    *
    * `heldDemandMiss` itself is not `simPublic`, and the RTL is deliberately kept
    * byte-identical to its pre-prefetch-decoupling state, so the held-demand
    * precondition is asserted the same way the pre-existing demotion test asserts it:
    * a valid demand that is not `ready`.
    */
  test("a held demand's blocking owner outranks lower-numbered slots in the AR arbiter", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val axi = dut.icache.logic.axi
      val arTrace = scala.collection.mutable.ArrayBuffer[(Int, Long)]()

      axi.ar.ready #= true
      axi.r.valid #= false
      axi.r.payload.data #= 0
      axi.r.payload.id #= 0
      axi.r.payload.last #= false
      axi.r.payload.resp #= 0
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

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      val base      = 0x7000L
      val blockLine = base + 0x100L   // the FOURTH speculative candidate -> slot index 3

      // Bootstrap: let ONLY the demand's own AR through, then close the AR channel.
      // Allocation does not need the AR channel, so all four speculative slots reach
      // AR_PENDING with nothing sent, and slot 0's AR parks in the holding register.
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= base
      waitSamplingWhereBounded(cd, "demand accept")(
        dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      waitSamplingWhereBounded(cd, f"demand AR at 0x$base%x")(
        axi.ar.valid.toBoolean && axi.ar.ready.toBoolean &&
        axi.ar.payload.id.toInt == AxiIds.I_DEMAND)
      axi.ar.ready #= false
      sendLine(AxiIds.I_DEMAND, base)

      waitUntilBounded(cd, "all four speculative slots AR_PENDING")(
        (0 until AxiIds.I_SPEC_SLOTS).forall(i =>
          IcacheArrayProbe.pfSlotValid(dut.icache, i) && !IcacheArrayProbe.pfSlotArSent(dut.icache, i)))
      assert(axi.ar.valid.toBoolean, "setup: no speculative AR is being offered")
      assert(axi.ar.payload.addr.toLong == base + 0x40L,
        f"setup: the holder should carry the LOWEST-numbered slot's line 0x${base + 0x40L}%x, " +
          f"got 0x${axi.ar.payload.addr.toLong}%x")
      assert(arTrace.count(_._1 != AxiIds.I_DEMAND) == 0,
        s"setup: no speculative AR may have fired yet, trace=$arTrace")

      // Offer the demand for the line owned by the HIGHEST-numbered slot. It is held
      // (`pfLookupSetBusy`), so `heldDemandMiss` is high, and slots 1 and 2 outrank
      // slot 3 under the plain `pfArSel` order.
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= blockLine
      cd.waitSampling(4)
      assert(!dut.probe.logic.cmdIn.ready.toBoolean,
        "setup did not hold the demand behind its AR-pending owner")

      // Re-open the AR channel: the already-latched slot-0 AR fires (its payload was
      // committed before the demand was even offered and AXI forbids changing it),
      // and the VERY NEXT grant is the arbiter decision under test.
      val before = arTrace.size
      axi.ar.ready #= true
      waitUntilBounded(cd, "two more ARs")(arTrace.size >= before + 2)
      axi.ar.ready #= false
      assert(arTrace(before)._2 == base + 0x40L,
        f"the parked AR should have fired first: got 0x${arTrace(before)._2}%x")
      val granted = arTrace(before + 1)
      assert(granted._1 >= AxiIds.I_SPEC_BASE && granted._1 <= AxiIds.I_SPEC_LAST,
        s"expected a speculative AR, got $granted")
      assert(granted._2 == blockLine,
        f"the AR arbiter did not prioritise the held demand's BLOCKING owner: it launched " +
          f"0x${granted._2}%x (id ${granted._1}) instead of 0x$blockLine%x. `pfChosenArSel` must " +
          f"be Mux(heldDemandMiss, pfBlockingArSel, pfArSel) -- a plain `pfArSel` picks the " +
          f"lowest-numbered pending slot, which cannot unblock the demand. trace=$arTrace")

      // Architectural close-out: answering exactly those two lines must install the
      // blocking owner and let the held demand through as a HIT, with no second AR
      // for its own line.
      sendLine(arTrace(before)._1, arTrace(before)._2)
      sendLine(granted._1, granted._2)
      waitSamplingWhereBounded(cd, "held demand admitted")(dut.probe.logic.cmdIn.ready.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      waitSamplingWhereBounded(cd, "held demand response")(dut.probe.logic.rspOut.valid.toBoolean)
      assert(dut.probe.logic.rspOut.payload.data.toBigInt == IcacheSim.window64(blockLine),
        "the unblocked demand returned incorrect data")
      assert(arTrace.count { case (id, address) => id == AxiIds.I_DEMAND && address == blockLine } == 0,
        s"the demand must have been served by the speculative install, not a second AR: $arTrace")
    }
  }

  // Task 8 (M2b): demand and speculative fills now share ONE MSHR line file, written
  // uniformly from the AXI R channel by RID. The old asymmetry -- demand into a
  // 512-flop lineReg, speculative into a 4-entry memory pair then COPIED into lineReg
  // in IDLE -- is deleted. This test pins the property that copy path existed to
  // provide: a line installed speculatively must be bit-identical to the same line
  // installed by demand, in data AND predecode.
  //
  // DEVIATION from the plan's literal listing, recorded deliberately: the plan's code
  // block calls a `compiled` val and a `fetchAndWait` helper, neither of which exists
  // in this suite (every test here compiles its own DUT and uses `fetch(dut, cd, pc)`;
  // `IcacheSim.attachMemory` is called with base = 0 / size = 0x10000 throughout).
  // Rewritten against the real helpers. The comparison is also made STRICTLY STRONGER
  // than the plan's: pass A's snapshot is restricted to the sets pass A never issued a
  // DEMAND fetch into, so every compared entry is one the prefetch engine installed on
  // its own. Comparing a demand-touched set would have compared demand against demand
  // and passed vacuously.
  test("a speculatively installed line is byte-identical to a demand-installed one",
       VerilatorTest) {
    val compiled = simConfig.compile(new Dut)

    // Page 0x2000..0x2fff is one 4 KiB page and exactly 64 lines, so it covers each of
    // the 64 sets exactly once -- at most one way per set is ever valid, which is what
    // lets the two passes be compared by (set, beat) alone.
    val pageBase   = 0x2000L
    val demandStep = 256                         // touch every 4th line by DEMAND
    val demandSets = (0 until 16).map(i => ((pageBase + i * demandStep) >> 6).toInt & 63).toSet

    // Pass A: prefetch ON. A long strided run installs the 3 lines between each
    // demand-touched line via the prefetch engine ONLY.
    val speculative = scala.collection.mutable.Map[(Int, Int), (BigInt, BigInt)]()
    compiled.doSim("mshr-spec-install") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      for (i <- 0 until 16) {
        val pc = pageBase + i * demandStep
        assert(fetch(dut, cd, pc) == IcacheSim.window64(pc), f"pass A demand data at 0x$pc%x")
      }
      settle(cd, 500)

      for (w <- 0 until 4; s <- 0 until 64
           if !demandSets.contains(s) && IcacheArrayProbe.wayValid(dut.icache, w, s);
           b <- 0 until 2) {
        speculative((s, b)) = (IcacheArrayProbe.wayData(dut.icache, w, s, b),
                               IcacheArrayProbe.wayPred(dut.icache, w, s, b))
      }
    }
    assert(speculative.nonEmpty, "no prefetch-only line became resident in the prefetch pass")

    // Pass B: prefetch OFF. Every line is installed by demand.
    compiled.doSim("mshr-demand-install") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= false
      cd.waitSampling(2)

      for (i <- 0 until 64) {
        val pc = pageBase + i * 64
        assert(fetch(dut, cd, pc) == IcacheSim.window64(pc), f"pass B demand data at 0x$pc%x")
      }
      settle(cd, 200)

      var compared = 0
      for (w <- 0 until 4; s <- 0 until 64 if IcacheArrayProbe.wayValid(dut.icache, w, s);
           b <- 0 until 2) {
        val demandPair = (IcacheArrayProbe.wayData(dut.icache, w, s, b),
                          IcacheArrayProbe.wayPred(dut.icache, w, s, b))
        speculative.get((s, b)).foreach { specPair =>
          assert(specPair == demandPair,
            f"set=$s beat=$b: speculatively installed content differs from demand-installed. " +
            f"spec data=0x${specPair._1.toString(16)} pred=0x${specPair._2.toString(16)}; " +
            f"demand data=0x${demandPair._1.toString(16)} pred=0x${demandPair._2.toString(16)}")
          compared += 1
        }
      }
      assert(compared >= 16,
        s"only $compared (set,beat) pairs overlapped between the two passes -- the " +
        s"comparison is too thin to prove anything")
    }
  }

  // Task 8 (M2b): the missPC-immutability invariant, made non-vacuous.
  //
  // `IcachePlugin.scala` now carries an RTL assertion that `missPC` cannot change on
  // any cycle where the fill machine was already engaged on the previous cycle -- the
  // property `bypPred` (M2a) and `bypWindow` (M2b) both rest on, since they capture a
  // window selected by missPC(5)/missPC(4:3) on one dwell cycle and REPLAY re-reads
  // missPC to build s1Pc/s1Lane on a later one. That assertion runs under every test
  // in the suite, but an assertion whose guard is never true passes for free. This
  // test pins the guard: it drives a workload that puts the machine through demand
  // refills AND speculative installs while the fetch port is being offered commands
  // continuously, and requires the covered-cycle count to be substantial. It also
  // mirrors the check in the testbench, so the property is asserted from two
  // independent places (RTL `assert` and Scala), not one.
  test("M2b: missPC is immutable across the whole PREDECODE dwell", VerilatorTest) {
    simConfig.compile(new Dut).doSim("m2b-misspc-immutable", seed = 559387700) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      var lockedCycles = 0
      var prevMissPc   = BigInt(-1)
      var prevLocked   = false
      var expectedCommitBeat = 0
      var demandCommitBeats = 0
      var prefetchCommitBeats = 0
      cd.onSamplings {
        val predActive = dut.icache.logic.predActive.toBoolean
        val commitBeat = dut.icache.logic.commitBeat.toInt
        assert(commitBeat == expectedCommitBeat,
          s"install phase changed outside its two-beat dwell: expected=$expectedCommitBeat actual=$commitBeat")
        if (predActive) {
          if (dut.icache.logic.predIsPf.toBoolean) prefetchCommitBeats += 1
          else demandCommitBeats += 1
          expectedCommitBeat ^= 1
        } else {
          assert(commitBeat == 0, "idle installer retained a partial beat")
        }
        val locked = dut.icache.logic.dbgMissPcLocked.toBoolean
        val pc     = dut.icache.logic.missPC.toBigInt
        if (locked) {
          lockedCycles += 1
          if (prevLocked)
            assert(pc == prevMissPc,
              f"missPC changed mid-dwell: 0x${prevMissPc.toString(16)} -> 0x${pc.toString(16)}. " +
              f"The bypPred/bypWindow capture window is selected by missPC(5)/missPC(4:3) on " +
              f"ONE dwell cycle and re-read by REPLAY on a later one, so a mid-dwell change " +
              f"silently delivers a window from the wrong part of the line.")
        }
        prevMissPc = pc
        prevLocked = locked
      }

      // Sequential misses (demand refills, each opening a speculative window whose
      // installs are the merged PREDECODE's other arm) interleaved with same-line hits,
      // then a same-set thrash that forces repeated eviction and re-fill. The fetch
      // port is offered a command on essentially every cycle, which is the only way the
      // merged PREDECODE's `lookupTick(canStartFill = false)` arm is ever exercised.
      for (i <- 0 until 24) {
        val pc = 0x3000L + i * 64
        assert(fetch(dut, cd, pc) == IcacheSim.window64(pc), f"data at 0x$pc%x")
        assert(fetch(dut, cd, pc + 8) == IcacheSim.window64(pc + 8), f"data at 0x${pc + 8}%x")
      }
      for (i <- 0 until 8) {
        // 0x3000 / 0x4000 / 0x5000 all share set 0 but differ in tag.
        val pc = 0x3000L + (i % 3) * 0x1000L
        assert(fetch(dut, cd, pc) == IcacheSim.window64(pc), f"thrash data at 0x$pc%x")
      }
      settle(cd, 300)

      // Non-vacuity. A demand refill alone spends REFILL + INSTALL_ARM + PREDECODE x2 +
      // REPLAY in the covered window, and there are 24+ of them here, so a count in the
      // low hundreds is expected; 100 is a deliberately loose floor that still fails
      // loudly if the guard collapses to never-true.
      assert(lockedCycles >= 100,
        s"the missPC-immutability assertion covered only $lockedCycles cycles -- its " +
        s"guard has collapsed and it is now passing vacuously")
      assert(demandCommitBeats >= 2 && prefetchCommitBeats >= 2,
        s"both install modes must be covered: demand=$demandCommitBeats prefetch=$prefetchCommitBeats")
      assert(expectedCommitBeat == 0, "test ended during a partial install")
      println(s"ICACHE_COMMIT_PHASE demand=$demandCommitBeats prefetch=$prefetchCommitBeats locked=$lockedCycles")
    }
  }

  // ══ Task 12 (M4): the frontier and the AR arbiter now read REGISTERED state ═══
  // Both effects are architecturally invisible (prefetch is a pure performance hint,
  // rules P1-P4): the frontier advances one cycle later, and on the specific cycle a
  // demand miss is also arbitrating the speculative AR is presented one cycle later.
  // But a frontier that NEVER advances is also "architecturally invisible", in exactly
  // the way that silently deletes the feature -- so it is measured, not reasoned about.
  //
  // DEVIATION from the plan's literal test code, recorded deliberately (the same
  // deviation Task 8 recorded for its own snippet): this suite has no `compiled` val
  // and no `fetchAndWait` helper -- every test compiles its own DUT and uses
  // `fetch(dut, cd, pc)`, and `IcacheSim.attachMemory` takes base/size. Rewritten
  // against the real helpers; the assertions are unchanged in substance.
  test("M4: the frontier still advances and speculative fills still land", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      // Touch ONLY line 0x1000. The frontier should pull in up to five lines ahead.
      assert(fetch(dut, cd, 0x1000L) == IcacheSim.window64(0x1000L), "demand line data")
      cd.waitSampling(2000)

      val ahead = (1 to 5).count { i =>
        val a   = 0x1000L + i * 64L
        val set = ((a >> 6) & 0x3f).toInt
        (0 until 4).exists(w => IcacheArrayProbe.wayValid(dut.icache, w, set) &&
          IcacheArrayProbe.wayTag(dut.icache, w, set) == BigInt(a >> 12))
      }
      assert(ahead >= 3,
        s"only $ahead of the 5 lines ahead of a single demand fetch became resident. " +
        s"M4 moved the frontier behind a registered disposition; a frontier that never " +
        s"advances is 'architecturally invisible' in exactly the way that silently " +
        s"deletes the feature.")
    }
  }

  test("M4: pfHitUseful telemetry still fires on a prefetched line's first demand hit",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(4)
      dut.icache.logic.prefetchEnable #= true
      cd.waitSampling(2)

      var useful = 0
      cd.onSamplings { if (dut.icache.logic.pfHitUseful.toBoolean) useful += 1 }

      assert(fetch(dut, cd, 0x1000L) == IcacheSim.window64(0x1000L), "demand line data")
      cd.waitSampling(2000)
      for (i <- 1 to 5) {
        val a = 0x1000L + i * 64L
        assert(fetch(dut, cd, a) == IcacheSim.window64(a), f"prefetched line data at 0x$a%x")
      }
      cd.waitSampling(50)
      assert(useful >= 3,
        s"pfHitUseful fired only $useful times across 5 demand hits into prefetched " +
        s"lines. M4 moved the pfFilled clear to S1 (Task 11 Step 6); the telemetry " +
        s"design doc section 8.3 depends on is measured, not asserted.")
    }
  }
}
