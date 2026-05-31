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

    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, icache)) }
    // No manual wiring needed: slave(Stream(FetchCmd())) etc. created inside
    // icache.logic (during build) automatically become top-level IO of this Component.
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
    dut.icache.logic.cmdPort.valid #= true
    dut.icache.logic.cmdPort.payload.pc #= pc
    // wait until cmd fires (ready && valid)
    cd.waitSamplingWhere(dut.icache.logic.cmdPort.ready.toBoolean && dut.icache.logic.cmdPort.valid.toBoolean)
    dut.icache.logic.cmdPort.valid #= false
    // wait for response
    cd.waitSamplingWhere(dut.icache.logic.rspPort.valid.toBoolean)
    dut.icache.logic.rspPort.payload.data.toBigInt
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

      dut.icache.logic.cmdPort.valid    #= false
      dut.icache.logic.cmdPort.payload.pc #= 0
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

      dut.icache.logic.cmdPort.valid    #= false
      dut.icache.logic.cmdPort.payload.pc #= 0
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
      dut.icache.logic.cmdPort.valid #= false
      dut.icache.logic.cmdPort.payload.pc #= 0
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
      dut.icache.logic.cmdPort.valid #= false
      dut.icache.logic.cmdPort.payload.pc #= 0
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

  // -------- Test 3: crossing 64B boundary triggers a new refill --------
  test("crossing the 64B line boundary triggers a new refill", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)

      IcacheSim.attachMemory(dut.icache.logic.axi, cd, base = 0L, size = 0x10000)

      dut.icache.logic.cmdPort.valid    #= false
      dut.icache.logic.cmdPort.payload.pc #= 0
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
}
