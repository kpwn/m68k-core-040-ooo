package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.services.TranslationService
import m68k040.core.ParamPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4ReadOnly}
import spinal.lib.bus.amba4.axi.sim.Axi4ReadOnlySlaveAgent
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class IcacheSpec extends AnyFunSuite {

  // ---- Dut: host plugins; IcachePlugin's during-build ports become top-level IO automatically ----
  // `xlateFactory` defaults to the plain identity stub (every existing test below is
  // unaffected); the cacheMode-directed tests pass `new ICacheModeTranslationPlugin`
  // instead, so a specific fetch's cache mode (WRITETHROUGH vs INHIBITED) can be
  // sim-poked without standing up a full ITLB + page-table walker.
  class Dut(xlateFactory: => FiberPlugin with TranslationService = new IdentityTranslationPlugin) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)

    val param  = new ParamPlugin(M68kParams())
    val xlate  = xlateFactory
    val icache = new IcachePlugin
    val probe  = new FetchProbePlugin   // exposes cmdIn/rspOut top-level IO

    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache, probe)) }
    // The I-cache's cmd/rsp are plain (directionless) service Streams. The probe
    // plugin's during-build wires them to its own slave/master IO, which become
    // top-level IO of this Component for the sim to drive/observe.
  }

  // ---- sim helpers (all port access through dut.icache.logic.* post-elaboration) ----

  /** Pulse invalidateAll high for one cycle, then low. */
  def pulseInvalidateAll(dut: Dut, cd: ClockDomain): Unit = {
    dut.icache.logic.invalidateAll #= true
    cd.waitSampling()
    dut.icache.logic.invalidateAll #= false
    cd.waitSampling()
  }

  /** Drive a fetch cmd, wait for it to be accepted, wait for rsp.valid.
    * Returns the 64-bit response data. */
  def fetch(dut: Dut, cd: ClockDomain, pc: Long): BigInt = {
    // present the command
    dut.probe.logic.cmdIn.valid #= true
    dut.probe.logic.cmdIn.payload.pc #= pc
    // wait until cmd fires (ready && valid)
    cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
    dut.probe.logic.cmdIn.valid #= false
    // wait for response
    cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
    dut.probe.logic.rspOut.payload.data.toBigInt
  }

  // ---- test harness factory ----
  def simConfig = SimConfig.withVerilator

  // -------- Test 1: cold miss refills then returns correct 64-bit window --------
  test("cold miss refills then returns the correct 64-bit window", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }

      // attach behavioral AXI memory (cover a big range)
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)

      dut.probe.logic.cmdIn.valid    #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll    #= false

      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      val pc       = 0x1040L
      val result   = fetch(dut, cd, pc)
      val expected = IcacheSim.window64(pc)

      assert(result == expected,
        s"cold miss: data mismatch at 0x${pc.toHexString}: got 0x${result.toString(16)} expected 0x${expected.toString(16)}")
      cd.waitSampling(4)
    }
  }

  // -------- Test 2: second fetch in same line hits with no new AXI burst --------
  test("second fetch in the same line hits with no new AXI burst", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }

      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)

      dut.probe.logic.cmdIn.valid    #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll    #= false

      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      var arBeatCounter = 0
      // fork to count AR handshakes
      val arMonitor = fork {
        while (true) {
          cd.waitSampling()
          if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) {
            arBeatCounter += 1
          }
        }
      }

      val base = 0x2000L
      fetch(dut, cd, base)           // cold miss → fills line
      val arAfterFirst = arBeatCounter

      val result   = fetch(dut, cd, base + 8L)  // same line → hit
      val expected = IcacheSim.window64(base + 8L)

      assert(result == expected,
        s"hit: data mismatch at 0x${(base+8).toHexString}: got 0x${result.toString(16)} expected 0x${expected.toString(16)}")
      assert(arBeatCounter == arAfterFirst,
        s"second fetch in same line must not issue a new AXI burst: ar count was $arAfterFirst before, $arBeatCounter after")
      cd.waitSampling(4)
    }
  }

  // -------- Test 4: round-robin eviction (way 0 first) --------
  test("filling all 4 ways then a 5th tag evicts round-robin (way 0 first)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) arCount += 1 } }

      // 5 addresses, SAME set index (bits[11:6]=0), DIFFERENT tags (bits[31:12]) via 0x1000 stride.
      val addrs = (0 until 5).map(i => i.toLong * 0x1000L)
      addrs.take(4).foreach(a => fetch(dut, cd, a))   // fill ways 0..3 (4 refills)
      val cBeforeFifth = arCount
      fetch(dut, cd, addrs(4))                          // 5th distinct tag -> evicts victim (way 0)
      assert(arCount == cBeforeFifth + 1, s"5th distinct tag must refill once: $cBeforeFifth -> $arCount")
      val cBeforeReFetch = arCount
      val got = fetch(dut, cd, addrs(0))                // addr0 was in way0 -> evicted -> miss again
      assert(got == IcacheSim.window64(addrs(0)),
        s"evicted line refetch data mismatch: got 0x${got.toString(16)} expected 0x${IcacheSim.window64(addrs(0)).toString(16)}")
      assert(arCount == cBeforeReFetch + 1, s"evicted line (way 0) must miss again: $cBeforeReFetch -> $arCount")
      cd.waitSampling(4)
    }
  }

  // -------- Test 5: invalidateAll forces a re-miss --------
  test("invalidateAll forces a re-miss", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) arCount += 1 } }

      val pc = 0x4080L
      fetch(dut, cd, pc)                 // cold miss -> refill
      val cAfterFirst = arCount
      fetch(dut, cd, pc)                 // hit -> no new AR
      assert(arCount == cAfterFirst, s"second fetch should hit (no AR): $cAfterFirst -> $arCount")
      pulseInvalidateAll(dut, cd)        // wipe
      val cBeforeReMiss = arCount
      val got = fetch(dut, cd, pc)       // must miss again
      assert(got == IcacheSim.window64(pc),
        s"post-invalidate data mismatch: got 0x${got.toString(16)} expected 0x${IcacheSim.window64(pc).toString(16)}")
      assert(arCount == cBeforeReMiss + 1, s"invalidateAll must force a refill: $cBeforeReMiss -> $arCount")
      cd.waitSampling(4)
    }
  }

  // -------- Test 5b (Task P5.5): an invalidate landing on the EXACT refill-commit
  // cycle must WIN over the refill's own valid-bit write --------
  //
  // The bug this pins down: `valids(w)(missSet) := True` (PREDECODE) elaborates LATER
  // in IcachePlugin.scala than the `when(invalidateAll || maintInvalidateAll) { valids
  // := False }` priority clear, so under last-assignment-wins the refill silently won
  // any cycle both fired -- re-validating a line the invalidate was supposed to clear,
  // leaving stale bytes fetchable after a CINV/CPUSH. Nothing gates instruction FETCH
  // on `excActive` (only decode/rename/IQ are), so a run-ahead or wrong-path refill
  // really can be committing on the exact cycle the commit-time CPUSH/CINV dispatch
  // pulses the invalidate. Task P5.5, which first drives that internal wire, is what
  // made the (identical, pre-existing) external-port gap live.
  //
  // `dbgAllocCommitCycle` is high for exactly the PREDECODE cycle that performs the
  // tag/valid write. A testbench samples just BEFORE the edge that latches what it
  // observed, so a poke issued at that sampling point only takes effect for the NEXT
  // cycle -- triggering off `dbgAllocCommitCycle` itself lands the invalidate one cycle
  // LATE, where it clears the (already set) valid bit and the test passes whether or
  // not the guard exists. So we trigger off `dbgAllocCommitPending` (the cycle before)
  // and then RE-CHECK `dbgAllocCommitCycle` on the next sampling point, so the test
  // proves its own alignment instead of assuming it. The external port is poked rather
  // than the internal `maintInvalidateAll` (which has no driver in this standalone
  // DUT): the guard is a single `when` covering both, so this exercises identical logic.
  test("an invalidateAll on the refill commit cycle wins over the refill's valid write", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      val pc  = 0x1040L
      val set = ((pc >> 6) & 0x3F).toInt   // 64 sets, 64-byte lines -> pc[11:6]
      // Fresh out of an invalidate, `victim(set)` is still 0, so this first allocation
      // targets way 0.
      val way = 0

      // Race the invalidate onto the allocation write.
      var aligned = false
      val racer = fork {
        // Trigger one cycle EARLY (see the comment above) so the pulse is high during
        // the allocation-write cycle itself.
        cd.waitSamplingWhere(dut.icache.logic.dbgAllocCommitPending.toBoolean)
        dut.icache.logic.invalidateAll #= true
        cd.waitSampling()
        // Self-check: this sampling point observes the cycle the pulse was high for.
        aligned = dut.icache.logic.dbgAllocCommitCycle.toBoolean
        dut.icache.logic.invalidateAll #= false
      }

      val got = fetch(dut, cd, pc)
      racer.join()
      cd.waitSampling(4)

      // If this ever fails the test below is vacuous, not passing -- fail loudly.
      assert(aligned,
        "test alignment lost: the invalidateAll pulse did not overlap the PREDECODE " +
          "allocation-write cycle, so nothing was actually raced")

      // The refill's DATA path is untouched by the guard (only `valids` is gated), so
      // the in-flight fetch that caused the refill still gets its correct bytes.
      assert(got == IcacheSim.window64(pc),
        s"racing refill must still deliver correct data: got 0x${got.toString(16)} expected 0x${IcacheSim.window64(pc).toString(16)}")

      // THE ASSERTION: the line must NOT be left resident. Pre-fix this read True.
      assert(!IcacheArrayProbe.wayValid(dut.icache, way, set),
        s"invalidateAll fired on the refill's own commit cycle but valids($way)($set) is still set -- " +
          "the refill's write won over the priority clear (elaboration-order race)")

      // Positive control: with no invalidate racing it, the very next refill of the
      // same address DOES leave the line resident -- so the assertion above is really
      // detecting the race, not a permanently broken allocate path.
      val got2 = fetch(dut, cd, pc)
      cd.waitSampling(4)
      assert(got2 == IcacheSim.window64(pc),
        s"post-race refetch data mismatch: got 0x${got2.toString(16)} expected 0x${IcacheSim.window64(pc).toString(16)}")
      assert((0 until 4).exists(w => IcacheArrayProbe.wayValid(dut.icache, w, set)),
        s"positive control: an unraced refill of 0x${pc.toHexString} must leave set $set resident in some way")
    }
  }

  // -------- Test 6: predecode on miss -- fetched window carries PredecodeRef-matching chunks --------
  test("fetched window carries predecode matching PredecodeRef", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      val base = 0x5000L
      val words = Seq(0x7005, 0x5240, 0x3200, 0x6000) // MOVEQ(simple1), ADDQ(complex), MOVE.W D0,D1(simple1), BRA.w(simple2)
      IcacheSim.attachMemoryWithWords(dut.icache.logic.axi, cd, base, words)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2); pulseInvalidateAll(dut, cd)
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= base
      cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      for (i <- 0 until 4) {
        val ref = m68k040.frontend.PredecodeRef.classify(words(i))
        assert(dut.probe.logic.rspOut.payload.pred(i).simple.toBoolean == ref.simple, s"chunk $i simple: ref=${ref.simple}")
        if (ref.simple)
          assert(dut.probe.logic.rspOut.payload.pred(i).lenWords.toInt == ref.lenWords, s"chunk $i len: ref=${ref.lenWords}")
      }
      cd.waitSampling(4)
    }
  }

  // -------- Latency: a warm hit responds exactly 2 cycles after accept --------
  // The parallel-VIPT path arms BRAM from virtual set/beat in the acceptance cycle
  // while the live translation qualifies the tag into S1. Registered S1 control then
  // selects the BRAM output into the response register on +1; FetchRsp is visible +2.
  test("warm hit responds exactly two cycles after cmd accept", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      val base = 0x6000L
      fetch(dut, cd, base)            // cold miss warms the line
      cd.waitSampling(4)              // let the pipeline drain to idle

      // Present a hit and measure latency precisely.
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= base
      cd.waitSamplingWhere(
        dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      // +1 cycle: BRAM output and registered hit context are being selected into the
      // response register — no externally visible response yet.
      cd.waitSampling()
      assert(!dut.probe.logic.rspOut.valid.toBoolean,
        "rsp must NOT be valid 1 cycle after accept (response register not visible)")
      // +2 cycles: registered response arrives.
      cd.waitSampling()
      assert(dut.probe.logic.rspOut.valid.toBoolean,
        "rsp must be valid exactly 2 cycles after accept")
      assert(dut.probe.logic.rspOut.payload.data.toBigInt == IcacheSim.window64(base),
        "2-cycle hit data mismatch")
      cd.waitSampling(4)
    }
  }

  // -------- Throughput: the two-cycle resident pipe has initiation interval one --------
  // A serialized `fetch()` loop cannot prove this: it waits for each response before
  // presenting the next command. Drive six unique same-line windows back-to-back and
  // require both the accepts and their associated responses to be consecutive. The
  // zero-AXI assertion proves the measured cadence is the resident path, not refills.
  test("warm resident hits accept and respond every cycle at fixed two-cycle latency", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false

      var cycle = 0
      var arCount = 0
      val accepts = scala.collection.mutable.ArrayBuffer.empty[(Int, Long)]
      val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, Long, BigInt)]
      cd.onSamplings {
        cycle += 1
        if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean)
          arCount += 1
        if (dut.probe.logic.cmdIn.valid.toBoolean && dut.probe.logic.cmdIn.ready.toBoolean)
          accepts += ((cycle, dut.probe.logic.cmdIn.payload.pc.toLong))
        if (dut.probe.logic.rspOut.valid.toBoolean)
          responses += ((cycle, dut.probe.logic.rspOut.payload.pc.toLong,
            dut.probe.logic.rspOut.payload.data.toBigInt))
      }

      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)
      val base = 0x6000L
      fetch(dut, cd, base) // demand-fill the line
      cd.waitSampling(4)   // drain replay/S1/rsp before the measured burst
      accepts.clear()
      responses.clear()
      val arBefore = arCount

      val pcs = (0 until 6).map(i => base + i * 8L)
      for (pc <- pcs) {
        dut.probe.logic.cmdIn.valid #= true
        dut.probe.logic.cmdIn.payload.pc #= pc
        cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean)
      }
      dut.probe.logic.cmdIn.valid #= false

      var guard = 0
      while (responses.size < pcs.size && guard < 20) {
        cd.waitSampling()
        guard += 1
      }
      assert(accepts.size == pcs.size,
        s"expected ${pcs.size} measured accepts, got ${accepts.mkString(",")}")
      assert(responses.size == pcs.size,
        s"expected ${pcs.size} measured responses, got ${responses.mkString(",")}")
      assert(accepts.map(_._1).sliding(2).forall(w => w(1) == w(0) + 1),
        s"resident accepts must have II=1: ${accepts.map(_._1)}")
      assert(responses.map(_._1).sliding(2).forall(w => w(1) == w(0) + 1),
        s"resident responses must be bubble-free: ${responses.map(_._1)}")
      for (((acceptCycle, acceptPc), (responseCycle, responsePc, data)) <-
           accepts.zip(responses)) {
        assert(responseCycle - acceptCycle == 2,
          s"pc=0x${acceptPc.toHexString} latency=${responseCycle - acceptCycle}, expected 2")
        assert(responsePc == acceptPc,
          f"response order mismatch: accepted 0x$acceptPc%x, returned 0x$responsePc%x")
        assert(data == IcacheSim.window64(acceptPc),
          f"resident data mismatch at 0x$acceptPc%x")
      }
      assert(accepts.map(_._2).toSeq == pcs,
        s"accept order mismatch: ${accepts.map(_._2).map(_.toHexString)}")
      assert(arCount == arBefore,
        s"warm same-line burst issued AXI reads: $arBefore -> $arCount")
    }
  }

  // -------- Test 3: crossing 64B boundary triggers a new refill --------
  test("crossing the 64B line boundary triggers a new refill", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }

      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)

      dut.probe.logic.cmdIn.valid    #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll    #= false

      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      var arBeatCounter = 0
      val arMonitor = fork {
        while (true) {
          cd.waitSampling()
          if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) {
            arBeatCounter += 1
          }
        }
      }

      val base = 0x3000L
      fetch(dut, cd, base + 56L)    // fetch last 8 bytes of line at 0x3000
      val arAfterFirst = arBeatCounter

      val result   = fetch(dut, cd, base + 64L)  // fetch first 8 bytes of next line 0x3040
      val expected = IcacheSim.window64(base + 64L)

      assert(result == expected,
        s"new-line: data mismatch at 0x${(base+64).toHexString}: got 0x${result.toString(16)} expected 0x${expected.toString(16)}")
      assert(arBeatCounter == arAfterFirst + 1,
        s"crossing 64B boundary must issue exactly one new AXI burst: ar before=$arAfterFirst after=$arBeatCounter")
      cd.waitSampling(4)
    }
  }

  // -------- Streaming: 3 consecutive windows in a warm line all return correct data --------
  test("three consecutive same-line hits return correct windows at the new latency", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)
      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      val base = 0x7000L
      fetch(dut, cd, base)   // cold miss warms the whole 64B line
      // Three windows within the same line (offsets 8, 16, 24): all hits.
      for (off <- Seq(8L, 16L, 24L)) {
        val got = fetch(dut, cd, base + off)
        assert(got == IcacheSim.window64(base + off),
          s"streaming hit at +$off mismatch: got 0x${got.toString(16)} expected 0x${IcacheSim.window64(base + off).toString(16)}")
      }
      cd.waitSampling(4)
    }
  }

  // ---- I-side cacheMode wiring (mirrors DcachePlugin task P1.4's D-side tests) ----

  /** Configure the poke-able translation stub so `vpn` (addr >> 12) resolves to
    * INHIBITED; every other VPN stays WRITETHROUGH (the stub's default). */
  def setInhibited(dut: Dut, vpn: Long): Unit = {
    val x = dut.xlate.asInstanceOf[ICacheModeTranslationPlugin]
    x.logic.cmodeEn  #= true
    x.logic.cmodeVpn #= vpn
    x.logic.cmodeSel #= CacheMode.INHIBITED
  }

  def clearCmodeOverride(dut: Dut): Unit =
    dut.xlate.asInstanceOf[ICacheModeTranslationPlugin].logic.cmodeEn #= false

  // (I-g) an INHIBITED-mode fetch never allocates an I-cache line: repeated fetches
  // to the SAME address each issue a fresh AXI refill (no resident line is ever
  // created) — mirrors DcacheSpec's "inhibited load never allocates a line".
  test("inhibited fetch never allocates an I-cache line", VerilatorTest) {
    simConfig.compile(new Dut(new ICacheModeTranslationPlugin)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      clearCmodeOverride(dut)
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      val base = 0xB000L
      setInhibited(dut, base >> 12)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) arCount += 1 } }

      val got1 = fetch(dut, cd, base)
      assert(got1 == IcacheSim.window64(base), "inhibited fetch still returns correct data")
      val after1 = arCount
      assert(after1 == 1, s"first inhibited fetch must refill once: $after1")

      val got2 = fetch(dut, cd, base)
      assert(got2 == IcacheSim.window64(base), "second inhibited fetch still returns correct data")
      val after2 = arCount
      assert(after2 == after1 + 1,
        s"a SECOND inhibited fetch to the SAME line must ALSO refill (no line was ever allocated): $after1 -> $after2")
      cd.waitSampling(4)
    }
  }

  // (I-h) an INHIBITED-mode fetch bypasses a resident cached alias: a prior
  // CACHEABLE (WRITETHROUGH) fetch at the same address left a line resident;
  // memory is then mutated directly (bypassing the cache entirely, like an MMIO
  // register changing on its own, or a page whose cache-mode attribute changed
  // after the line was cached). The inhibited fetch must see the NEW value, never
  // the stale resident alias — mirrors DcacheSpec's "inhibited load bypasses a
  // resident cached alias".
  test("inhibited fetch bypasses a stale resident cached alias", VerilatorTest) {
    simConfig.compile(new Dut(new ICacheModeTranslationPlugin)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      val (_, sparse) = IcacheSim.attachMemoryMutable(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      clearCmodeOverride(dut)
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      val base = 0xC000L
      // warm the line as ordinary cacheable (WRITETHROUGH, the stub's default)
      val warm = fetch(dut, cd, base)
      assert(warm == IcacheSim.window64(base), "warm cacheable fetch")

      // mutate memory underneath the now-resident line, bypassing the cache
      // entirely (the freshly poked bytes deliberately differ from memByte()).
      sparse.write(base + 0, 0xAA.toByte)
      sparse.write(base + 1, 0xBB.toByte)
      sparse.write(base + 2, 0xCC.toByte)
      sparse.write(base + 3, 0xDD.toByte)
      sparse.write(base + 4, 0x11.toByte)
      sparse.write(base + 5, 0x22.toByte)
      sparse.write(base + 6, 0x33.toByte)
      sparse.write(base + 7, 0x44.toByte)
      val mutated = BigInt("44332211DDCCBBAA", 16)

      setInhibited(dut, base >> 12)
      val got = fetch(dut, cd, base)
      assert(got == mutated,
        s"inhibited fetch must bypass the stale resident alias: got 0x${got.toString(16)} expected 0x${mutated.toString(16)}")
      cd.waitSampling(4)
    }
  }

  // (I-i) CRITICAL regression (review of 69a867c, "icache: wire xlate.rsp.cacheMode
  // into IcachePlugin"): that commit correctly gated tagMem/valids/victim-advance
  // under doAllocate for an INHIBITED-mode miss, but left the ACTUAL dataMem write
  // (REFILL) and predMem write (PREDECODE) unconditional. The round-robin `victim`
  // pointer is the SAME pointer used by cacheable and INHIBITED misses alike: once a
  // set has taken >=4 real allocations it wraps back onto a way that is CURRENTLY
  // VALID/resident for some OTHER address. An unconditional write there silently
  // corrupts that other way's data/pred while its tag/valid stay untouched -- still
  // claiming the OLD address is validly resident -- so a later ordinary fetch to
  // that address would silently return the WRONG bytes/predecode.
  //
  // This test forces exactly that scenario: warm all 4 ways of set 0 with distinct
  // cacheable lines (the round-robin victim pointer wraps back to way 0 after the
  // 4th fill), then trigger an INHIBITED-mode miss at a DIFFERENT address that maps
  // to the SAME set index (aliasing onto victimWay=0, way 0's resident way). Way 0's
  // raw dataMem/predMem/tagMem/valid content must be byte-for-byte UNCHANGED
  // afterward -- not just that the inhibited fetch itself returned the right data,
  // but that the OTHER, unrelated way was left completely alone -- and a plain
  // re-fetch of the original way-0 address must still HIT (no new AR) with correct
  // data. FAILS against the pre-fix (69a867c) code; PASSES after the fix.
  test("INHIBITED miss aliasing the round-robin victim pointer must not corrupt the resident way", VerilatorTest) {
    simConfig.compile(new Dut(new ICacheModeTranslationPlugin)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      val (_, sparse) = IcacheSim.attachMemoryMutable(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)

      // IcacheSim's default `memByte(addr) = (addr*7+0x11)&0xff` pattern is
      // PERIODIC with period 256: for any two line-aligned addresses that share
      // the same set index, addr mod 256 is ALWAYS 0 (the offset bits [5:0] are 0
      // by line-alignment and the set-index bits [11:6] -- which fully determine
      // the rest of addr mod 256 alongside the offset -- are, by construction,
      // identical between any two same-set addresses). So EVERY line-aligned
      // address in the same set reads back byte-IDENTICAL content under the
      // default pattern, which would make a raw dataMem/predMem content
      // comparison unable to distinguish "way 0 kept its original content" from
      // "way 0 was silently overwritten with a byte-identical INHIBITED line" --
      // masking the exact corruption this test exists to catch. Override each
      // tested line with a distinct, address-tagged constant byte pattern instead.
      def fillLine(addr: Long, byte: Int): Unit =
        (0 until 64).foreach(o => sparse.write(addr + o, byte.toByte))
      def lineWindow64(byte: Int): BigInt =
        (0 until 8).foldLeft(BigInt(0)) { (acc, i) => acc | (BigInt(byte & 0xff) << (8 * i)) }

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      clearCmodeOverride(dut)
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) arCount += 1 } }

      // 4 distinct tags, SAME set index (bits[11:6]=0), stride 0x1000 (mirrors the
      // existing round-robin eviction test) -- warms ways 0,1,2,3 in order, leaving
      // the round-robin victim pointer for set 0 wrapped back to way 0. Each line
      // is filled with a unique byte (0x10+i repeated across all 64 bytes).
      val addrs = (0 until 4).map(i => i.toLong * 0x1000L)
      addrs.zipWithIndex.foreach { case (a, i) => fillLine(a, 0x10 + i) }
      val inhibitedBase = 0x4000L
      fillLine(inhibitedBase, 0x14)   // distinct from all 4 warm lines (0x10..0x13)

      addrs.zipWithIndex.foreach { case (a, i) =>
        val got = fetch(dut, cd, a)
        assert(got == lineWindow64(0x10 + i), s"warm fetch at 0x${a.toHexString} data mismatch")
      }
      val arAfterWarm = arCount
      assert(arAfterWarm == 4, s"4 distinct-tag warm fetches must each refill once: $arAfterWarm")

      // Snapshot way 0's raw array content BEFORE the INHIBITED miss (set index 0).
      val way0Before = IcacheArrayProbe.snapshotWay(dut.icache, 0, 0)
      assert(way0Before.valid, "way 0 must be resident/valid after the warm fetches")

      // Trigger an INHIBITED-mode miss to a DIFFERENT address that maps to the SAME
      // set (low 12 bits still 0 -> set index 0), aliasing onto victimWay=0.
      setInhibited(dut, inhibitedBase >> 12)
      val gotInhibited = fetch(dut, cd, inhibitedBase)
      assert(gotInhibited == lineWindow64(0x14),
        "inhibited fetch itself must still return correct (distinguishable) data")
      val arAfterInhibited = arCount
      assert(arAfterInhibited == arAfterWarm + 1, s"inhibited fetch must refill once: $arAfterWarm -> $arAfterInhibited")

      // THE regression check: way 0's raw array content must be COMPLETELY UNCHANGED.
      val way0After = IcacheArrayProbe.snapshotWay(dut.icache, 0, 0)
      IcacheArrayProbe.assertUnchanged(way0Before, way0After, "way 0 after an unrelated INHIBITED miss")

      // Extra correctness check: a plain re-fetch of the ORIGINAL way-0 address must
      // still HIT (no new AR) and return the correct, unpoisoned data.
      clearCmodeOverride(dut)
      val arBeforeRefetch = arCount
      val got0 = fetch(dut, cd, addrs(0))
      assert(got0 == lineWindow64(0x10),
        s"way-0 address must still return correct (unpoisoned) data after the INHIBITED alias: got 0x${got0.toString(16)}")
      assert(arCount == arBeforeRefetch,
        s"way-0 address must still HIT (no new AR): $arBeforeRefetch -> $arCount")

      cd.waitSampling(4)
    }
  }

  // (I-j) CRITICAL regression (review of 156cf6b, "icache: gate dataMem/predMem
  // writes under doAllocate, fix silent corruption"): 156cf6b's `refillAllocate`
  // gate is evaluated PER-BEAT, using `missBusFault`/`respErr` AS OF THAT BEAT. This
  // correctly suppresses the dataMem write for a beat that itself errors, and for
  // every beat AFTER an earlier beat in the same burst errored (missBusFault, once
  // latched, carries forward). It does NOT protect the OPPOSITE, equally legal AXI
  // ordering: beat 0 of a 2-beat line burst returns OKAY and gets written for real
  // (nothing yet knows the burst will fail), and only THEN does beat 1 (the LAST
  // beat) return SLVERR/DECERR. The overall refill still correctly routes to FAULT
  // (no tag/pred/valid allocation), but beat 0's real fetched data was already
  // spliced into `dataMem(victimWay)` by the time the fault is known — corrupting
  // that way's beat-0 half exactly like the original 69a867c bug, just triggered by
  // a mid-burst AXI error instead of a cache-mode setting.
  //
  // This test forces exactly that scenario: warm all 4 ways of set 0 (same
  // round-robin-aliasing technique as the 156cf6b test above, wrapping the victim
  // pointer back onto way 0), then trigger a miss at a DIFFERENT same-set address
  // whose beat 0 succeeds (OKAY, real distinguishable data) and whose beat 1 (the
  // last beat) DECERRs — using `BeatFaultAxiResponder` (IcacheSim.scala), a
  // purpose-built driver, because neither `attachMemory`/`attachMemoryMutable` (no
  // resp-injection hook at all) nor `AxiMemModel`'s `injectBusErrors` (address-decode
  // boundaries are all multiples of the 64-byte line size, so a decode-based
  // bad/good split can never fall strictly between one line's beat 0 and beat 1) can
  // produce this specific per-beat pattern. Way 0's raw dataMem/tagMem/predMem/valid
  // content must be byte-for-byte UNCHANGED afterward. FAILS against the pre-fix
  // (156cf6b) code (way 0's beat-0 dataMem half is corrupted); PASSES after the fix.
  test("beat-0-OK-then-beat-1-error mid-burst refill must not corrupt the aliased resident way", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // This suite is the DEMAND-path unit test: every one of its assertions counts
      // exact AXI bursts and exact round-robin victim-way sequences, both of which
      // slice I3's next-line prefetch legitimately perturbs (an extra AR per fill, an
      // extra victim advance in the NEXT set). Prefetch behaviour has its own suite,
      // IcachePrefetchSpec, which turns this back on and asserts rules P1-P4. Using
      // the real runtime control here rather than a compile-time knob is exactly what
      // that control is for (design doc §8.3).
      // `prefetchEnable` is a RegInit(True), so a single poke here would be overwritten
      // by the reset value on the next edge (`forkStimulus` holds reset for the first
      // cycles). Re-poke from a NON-BLOCKING fork instead: a blocking `waitSampling`
      // here would run before `attachMemory` below, leaving the AXI port unserved while
      // an uninitialised `cmdIn.valid` can start a refill that never completes.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      val mem = new BeatFaultAxiResponder(dut.icache.logic.axi, cd)

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.icache.logic.axi.ar.valid.toBoolean && dut.icache.logic.axi.ar.ready.toBoolean) arCount += 1 } }

      // A 64-byte line filled entirely with `byte` (distinguishable per warm way).
      def line(byte: Int): Array[Byte] = Array.fill(64)(byte.toByte)
      def lineWindow64(byte: Int): BigInt =
        (0 until 8).foldLeft(BigInt(0)) { (acc, i) => acc | (BigInt(byte & 0xff) << (8 * i)) }

      // 4 distinct tags, SAME set index (bits[11:6]=0), stride 0x1000 — warms ways
      // 0..3 in order, leaving the round-robin victim pointer for set 0 wrapped back
      // to way 0 (mirrors the 156cf6b aliasing test above).
      val addrs = (0 until 4).map(i => i.toLong * 0x1000L)
      addrs.zipWithIndex.foreach { case (a, i) => mem.writeLine(a, line(0x10 + i)) }
      val faultBase = 0x4000L   // distinct tag, same set index (low 12 bits still 0)
      // Beat 0 (first 32 bytes) = 0x77 repeated — deliberately distinct from every
      // warm way's content (0x10..0x13), so a beat-0 corruption of way 0 is
      // unambiguously detectable. Beat 1 content is irrelevant (its beat DECERRs).
      mem.writeLine(faultBase, Array.tabulate(64)(i => if (i < 32) 0x77.toByte else 0x00.toByte))

      addrs.zipWithIndex.foreach { case (a, i) =>
        val got = fetch(dut, cd, a)
        assert(got == lineWindow64(0x10 + i), s"warm fetch at 0x${a.toHexString} data mismatch")
      }
      val arAfterWarm = arCount
      assert(arAfterWarm == 4, s"4 distinct-tag warm fetches must each refill once: $arAfterWarm")

      // Snapshot way 0's raw array content BEFORE the faulting refill (set index 0).
      val way0Before = IcacheArrayProbe.snapshotWay(dut.icache, 0, 0)
      assert(way0Before.valid, "way 0 must be resident/valid after the warm fetches")

      // Arm beat 1 (the LAST beat) of the upcoming refill at faultBase to DECERR;
      // beat 0 returns OKAY with the real 0x77 data.
      mem.armBeatFault(faultBase, 1)

      // Trigger the fault-inducing fetch. Use a raw cmd/rsp drive (not the shared
      // `fetch` helper) so the fault flag on the response can also be inspected.
      dut.probe.logic.cmdIn.valid #= true
      dut.probe.logic.cmdIn.payload.pc #= faultBase
      cd.waitSamplingWhere(dut.probe.logic.cmdIn.ready.toBoolean && dut.probe.logic.cmdIn.valid.toBoolean)
      dut.probe.logic.cmdIn.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.rspOut.valid.toBoolean)
      assert(dut.probe.logic.rspOut.payload.fault.toBoolean,
        "a mid-burst DECERR must be reported as a fault response (no line allocated)")
      val arAfterFault = arCount
      assert(arAfterFault == arAfterWarm + 1, s"faulting fetch must issue exactly one refill: $arAfterWarm -> $arAfterFault")

      // THE regression check: way 0's raw array content must be COMPLETELY
      // UNCHANGED, in particular its beat-0 half (the one 156cf6b's per-beat gate
      // does NOT protect against this ordering).
      val way0After = IcacheArrayProbe.snapshotWay(dut.icache, 0, 0)
      IcacheArrayProbe.assertUnchanged(way0Before, way0After,
        "way 0 after a mid-burst (beat-0-OK, beat-1-error) refill")

      // Extra correctness check: a plain re-fetch of the ORIGINAL way-0 address must
      // still HIT (no new AR) and return the correct, unpoisoned data.
      val arBeforeRefetch = arCount
      val got0 = fetch(dut, cd, addrs(0))
      assert(got0 == lineWindow64(0x10),
        s"way-0 address must still return correct (unpoisoned) data after the mid-burst-error alias: got 0x${got0.toString(16)}")
      assert(arCount == arBeforeRefetch,
        s"way-0 address must still HIT (no new AR): $arBeforeRefetch -> $arCount")

      cd.waitSampling(4)
    }
  }

  // Task 3 (plan R5 mitigation): pins IcacheArrayProbe against the OLD RTL, so the
  // helper is proven equivalent to the raw reads it replaces BEFORE M1/M2 change the
  // array shapes underneath it. If this test and the raw reads ever disagree, the
  // helper is wrong and every corruption assertion built on it is worthless.
  test("IcacheArrayProbe agrees with raw array reads on a freshly filled line", VerilatorTest) {
    simConfig.compile(new Dut).doSim("array-probe-selftest") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      // Keep prefetch out of the way -- see the identical fork in every other test in
      // this file for why this must be a non-blocking re-poke, not a single early one.
      fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }

      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)

      dut.probe.logic.cmdIn.valid #= false
      dut.probe.logic.cmdIn.payload.pc #= 0
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      pulseInvalidateAll(dut, cd)

      // Fetch address 0x1000 -> set 0, both beats installed by the refill.
      val pc = 0x1000L
      val got = fetch(dut, cd, pc)
      assert(got == IcacheSim.window64(pc), "sanity: the fetch itself must return correct data")
      cd.waitSampling(5)

      val way = (0 until 4).find(w => dut.icache.logic.valids(w)(0).toBoolean)
        .getOrElse(fail("no way became valid after a demand fill of set 0"))

      // Raw reads. M1a (Task 5): the data half now lives in the Unified Fetch Array's
      // low 256 bits (`lineMem` replaced `dataMem`); `predMem` survives this commit as
      // the shadow, so the predecode assertions below are a genuine CROSS-ARRAY
      // equivalence check (unified inline field vs the array it replaces), not a
      // self-comparison.
      val dataMask = (BigInt(1) << 256) - 1
      val rawData0 = dut.icache.logic.lineMem(way).getBigInt(0) & dataMask
      val rawData1 = dut.icache.logic.lineMem(way).getBigInt(1) & dataMask
      val rawTag   = dut.icache.logic.tagMem(way).getBigInt(0)
      val rawPred  = dut.icache.logic.predMem(way).getBigInt(0)
      val predBits = dut.icache.logic.PRED_BITS_PER_BEAT

      assert(IcacheArrayProbe.wayData(dut.icache, way, 0, 0) == rawData0,
        s"wayData(beat 0) disagrees with lineMem($way).getBigInt(0)[255:0]")
      assert(IcacheArrayProbe.wayData(dut.icache, way, 0, 1) == rawData1,
        s"wayData(beat 1) disagrees with lineMem($way).getBigInt(1)[255:0]")
      assert(IcacheArrayProbe.wayTag(dut.icache, way, 0) == rawTag,
        s"wayTag disagrees with tagMem($way).getBigInt(0)")
      assert(IcacheArrayProbe.wayValid(dut.icache, way, 0),
        "wayValid disagrees with valids")

      val mask = (BigInt(1) << predBits) - 1
      assert(IcacheArrayProbe.wayPred(dut.icache, way, 0, 0) == (rawPred & mask),
        "M1a: the unified array's beat-0 inline predecode is not the low half of the " +
        "shadow predMem's line-granular entry")
      assert(IcacheArrayProbe.wayPred(dut.icache, way, 0, 1) == ((rawPred >> predBits) & mask),
        "M1a: the unified array's beat-1 inline predecode is not the high half of the " +
        "shadow predMem's line-granular entry")

      val snap = IcacheArrayProbe.snapshotWay(dut.icache, way, 0)
      assert(snap.data == Seq(rawData0, rawData1), "snapshotWay.data disagrees")
      assert(snap.pred == Seq(rawPred & mask, (rawPred >> predBits) & mask), "snapshotWay.pred disagrees")
      assert(snap.tag == rawTag, "snapshotWay.tag disagrees")
      assert(snap.valid, "snapshotWay.valid disagrees")

      cd.waitSampling(4)
    }
  }
}
