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

  /** Per-ID AR counter. Counts a fired AR handshake and buckets it by `ar.id`, which
    * is what makes the P1/P2/P3 assertions discriminating: a demand refill uses
    * `AxiIds.I_DEMAND` and a prefetch uses `AxiIds.I_PREFETCH`. */
  class ArCounter(dut: Dut, cd: ClockDomain) {
    val byId = scala.collection.mutable.HashMap[Int, Int]().withDefaultValue(0)
    cd.onSamplings {
      if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) {
        val id = dut.icache.logic.axi.ar.payload.id.toInt
        byId(id) = byId(id) + 1
      }
    }
    def demand: Int   = byId(AxiIds.I_DEMAND)
    def prefetch: Int = byId(AxiIds.I_PREFETCH)
    def total: Int    = byId.values.sum
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
  test("a demand miss triggers exactly ONE next-line prefetch, and the next line then HITS", VerilatorTest) {
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
      assert(ar.prefetch == 1, s"exactly one PREFETCH expected, got ${ar.prefetch}")

      // The prefetched line must now be a genuine HIT: no further AR of any ID for it.
      val dBefore = ar.demand
      val got2 = fetch(dut, cd, pc + 0x40)
      assert(got2 == IcacheSim.window64(pc + 0x40), "prefetched line data must be correct")
      assert(ar.demand == dBefore,
        s"the prefetched line must HIT (no new demand AR): ${dBefore} -> ${ar.demand}")
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

  test("the 'useful prefetch' hit trigger keeps the stream running (a run of lines needs no demand miss after the first)", VerilatorTest) {
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

      // Walk 6 consecutive lines. Only the FIRST may take a demand refill: each hit in
      // a prefetched line re-arms the prefetcher for the line after it. Without the hit
      // trigger the stream alternates miss/hit and this would be ~3 demand refills.
      val base = 0x3000L
      for (i <- 0 until 6) {
        val a = base + 0x40L * i
        // Give the prefetch time to land before demanding the next line, which is the
        // regime the trigger exists for (8 sequential 8-byte fetches per 64-byte line).
        settle(cd, 40)
        val got = fetch(dut, cd, a)
        assert(got == IcacheSim.window64(a), f"line $i data at 0x$a%x")
      }
      settle(cd)
      assert(ar.demand == 1,
        s"only the first line should demand-refill; got ${ar.demand} demand ARs " +
          s"(${ar.prefetch} prefetch ARs). A count > 1 means the useful-prefetch hit trigger is not re-arming.")
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
      val pfAfterWarm = ar.prefetch
      // Now demand-miss line 0x2000, whose next line 0x2040 is already resident.
      fetch(dut, cd, 0x2000L)
      settle(cd)
      assert(ar.prefetch == pfAfterWarm,
        s"an already-resident next line must not be re-prefetched: $pfAfterWarm -> ${ar.prefetch}")
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
      // the CYCLE COUNT, measured against the known ~3-cycle hit latency.)
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
      // Immediately demand 0x6040 while its prefetch is still in flight. The T-stage is
      // HELD and re-looks-up (plan I1.2 / I3.2's sanctioned merge implementation), so
      // this must produce NO additional AR of any ID.
      val got = fetch(dut, cd, 0x6040L)
      assert(got == IcacheSim.window64(0x6040L), "demoted demand fetch must get correct data")
      settle(cd)
      // Settling may legitimately add ONE more prefetch (0x6080) triggered by the hit.
      assert(ar.byId(AxiIds.I_DEMAND) == 1,
        s"the demand fetch of an in-flight prefetched line must NOT issue a second demand AR; " +
          s"demand ARs = ${ar.byId(AxiIds.I_DEMAND)} (total ${before} -> ${ar.total})")
    }
  }
}
