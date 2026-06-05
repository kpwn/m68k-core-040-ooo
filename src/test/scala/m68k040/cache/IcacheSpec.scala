package m68k040.cache

import m68k040.{M68kParams, VerilatorTest}
import m68k040.mmu.IdentityTranslationPlugin
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
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)

    val param  = new ParamPlugin(M68kParams())
    val xlate  = new IdentityTranslationPlugin
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

  // -------- Test 6: predecode on miss -- fetched window carries PredecodeRef-matching chunks --------
  test("fetched window carries predecode matching PredecodeRef", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
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

  // -------- Latency: a warm hit responds exactly 3 cycles after accept --------
  // The accept now lands in the registered ITLB-translate (T) stage; the hit-detect
  // + BRAM read run off the REGISTERED physical paddr the NEXT cycle (FMax: the live
  // ITLB way-mux is out of the hit cone). So a warm hit is: accept -> (+1) T-consume
  // arms the BRAM read -> (+2) S1 muxes the beat -> (+3) rsp register drives the Flow.
  test("warm hit responds exactly three cycles after cmd accept", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
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
      // +1 cycle: T-stage just registered the translation; hit-detect/BRAM read not
      // yet launched — no response.
      cd.waitSampling()
      assert(!dut.probe.logic.rspOut.valid.toBoolean,
        "rsp must NOT be valid 1 cycle after accept (translation just registered)")
      // +2 cycles: BRAM read in flight (armed off the registered paddr), no response yet
      cd.waitSampling()
      assert(!dut.probe.logic.rspOut.valid.toBoolean,
        "rsp must NOT be valid 2 cycles after accept (BRAM read in flight)")
      // +3 cycles: response arrives
      cd.waitSampling()
      assert(dut.probe.logic.rspOut.valid.toBoolean,
        "rsp must be valid exactly 3 cycles after accept")
      assert(dut.probe.logic.rspOut.payload.data.toBigInt == IcacheSim.window64(base),
        "3-cycle hit data mismatch")
      cd.waitSampling(4)
    }
  }

  // -------- Test 3: crossing 64B boundary triggers a new refill --------
  test("crossing the 64B line boundary triggers a new refill", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)

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
}
