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

      // Snapshot way 0's raw array content BEFORE the INHIBITED miss. Set index 0:
      // dataMem address = set*beatsPerLine + beat (0 and 1); tagMem/predMem address
      // = set (0).
      val way0DataBefore  = Seq(0, 1).map(b => dut.icache.logic.dataMem(0).getBigInt(b))
      val way0TagBefore   = dut.icache.logic.tagMem(0).getBigInt(0)
      val way0PredBefore  = dut.icache.logic.predMem(0).getBigInt(0)
      val way0ValidBefore = dut.icache.logic.valids(0)(0).toBoolean
      assert(way0ValidBefore, "way 0 must be resident/valid after the warm fetches")

      // Trigger an INHIBITED-mode miss to a DIFFERENT address that maps to the SAME
      // set (low 12 bits still 0 -> set index 0), aliasing onto victimWay=0.
      setInhibited(dut, inhibitedBase >> 12)
      val gotInhibited = fetch(dut, cd, inhibitedBase)
      assert(gotInhibited == lineWindow64(0x14),
        "inhibited fetch itself must still return correct (distinguishable) data")
      val arAfterInhibited = arCount
      assert(arAfterInhibited == arAfterWarm + 1, s"inhibited fetch must refill once: $arAfterWarm -> $arAfterInhibited")

      // THE regression check: way 0's raw array content must be COMPLETELY UNCHANGED.
      val way0DataAfter  = Seq(0, 1).map(b => dut.icache.logic.dataMem(0).getBigInt(b))
      val way0TagAfter   = dut.icache.logic.tagMem(0).getBigInt(0)
      val way0PredAfter  = dut.icache.logic.predMem(0).getBigInt(0)
      val way0ValidAfter = dut.icache.logic.valids(0)(0).toBoolean

      assert(way0DataAfter == way0DataBefore,
        s"way 0 dataMem CORRUPTED by an unrelated INHIBITED miss: before=$way0DataBefore after=$way0DataAfter")
      assert(way0TagAfter == way0TagBefore,
        s"way 0 tagMem changed: before=0x${way0TagBefore.toString(16)} after=0x${way0TagAfter.toString(16)}")
      assert(way0PredAfter == way0PredBefore,
        s"way 0 predMem CORRUPTED by an unrelated INHIBITED miss: before=0x${way0PredBefore.toString(16)} after=0x${way0PredAfter.toString(16)}")
      assert(way0ValidAfter == way0ValidBefore, "way 0 valid bit must be unaffected by the INHIBITED miss")

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
}
