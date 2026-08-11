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
    val icache = new IcachePlugin
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
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      val ar = new ArCounter(dut, cd)
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
      var issued = 0
      while (issued < 8) {
        dut.probe.logic.cmdIn.payload.pc #= (0x4000L + issued * 8L)
        cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean)
        issued += 1
      }
      val pfDuringBurst = ar.prefetch - pfBeforeBurst
      dut.probe.logic.cmdIn.valid #= false
      dut.icache.logic.prefetchEnable #= false
      assert(pfDuringBurst >= 2,
        s"resident II=1 traffic starved the fill allocator; only $pfDuringBurst speculative ARs fired while all eight commands were continuously offered")
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
      cd.waitSamplingWhere(dut.icache.logic.pfValid(1).toBoolean &&
                           !dut.icache.logic.pfArSent(1).toBoolean)

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
        cd.waitSamplingWhere(dut.icache.logic.pfValid(0).toBoolean &&
                             !dut.icache.logic.pfArSent(0).toBoolean)

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
            assert(dut.icache.logic.pfComplete(0).toBoolean,
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
        while ((0 until AxiIds.I_SPEC_SLOTS).exists(i => dut.icache.logic.pfValid(i).toBoolean)) {
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
        dut.icache.logic.pfValid(i).toBoolean && dut.icache.logic.pfArSent(i).toBoolean &&
          !dut.icache.logic.pfComplete(i).toBoolean),
        "setup failed to hold all four wrong-path slots in FILL")

      val got = fetch(dut, cd, 0x8000L)
      assert(got == IcacheSim.window64(0x8000L),
        "redirect target demand was corrupted or blocked by wrong-path fills")
      assert(arTrace.count(_ == ((AxiIds.I_DEMAND, 0x8000L))) == 1,
        s"redirect target did not get its one reserved ID0 transaction: $arTrace")
      assert((0 until AxiIds.I_SPEC_SLOTS).forall(i =>
        dut.icache.logic.pfValid(i).toBoolean && !dut.icache.logic.pfComplete(i).toBoolean),
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
          dut.icache.logic.pfValid(i).toBoolean && !dut.icache.logic.pfArSent(i).toBoolean))
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
}
