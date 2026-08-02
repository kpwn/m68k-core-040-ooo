package m68k040.cache

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.ls.BehavioralMemAgent
import m68k040.mmu.DIdentityTranslationPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

class DcacheSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin
    val probe  = new DcacheProbePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe)) }
  }

  def simConfig = M68kSim().withVerilator

  /** ONE shared verilator build reused by the post-P4.3/P4.4-review regression sweeps
    * below (9 offsets; recompiling per offset costs minutes for zero
    * benefit -- the DUT is identical). Same pattern as ExecuteLockStepSpec's shared
    * FullCoreDut build. */
  lazy val sharedCompiled = simConfig.compile(new Dut)

  /** Deterministic image byte. */
  def memByte(addr: Long): Int = ((addr * 5 + 0x23) & 0xff).toInt
  def preload(mem: BehavioralMemAgent, base: Long, n: Int): Unit =
    for (i <- 0 until n) mem.pokeByte(base + i, memByte(base + i))

  /** Big-endian extracted value at addr for a given size. */
  def expected(base: Long, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  /** Drive a load cmd, wait accept, wait rsp.valid; return data. */
  def load(dut: Dut, cd: ClockDomain, vaddr: Long, size: SpinalEnumElement[Size.type],
           cacheMode: SpinalEnumElement[CacheMode.type] = CacheMode.WRITETHROUGH): BigInt = {
    dut.probe.logic.loadCmdIn.valid #= true
    dut.probe.logic.loadCmdIn.payload.vaddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.paddr #= vaddr   // identity translation in this spec
    dut.probe.logic.loadCmdIn.payload.size #= size
    dut.probe.logic.loadCmdIn.payload.cacheMode #= cacheMode
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean && dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
    dut.probe.logic.loadRspOut.payload.data.toBigInt
  }

  def doStore(dut: Dut, cd: ClockDomain, paddr: Long, data: BigInt, size: SpinalEnumElement[Size.type],
              cacheMode: SpinalEnumElement[CacheMode.type] = CacheMode.WRITETHROUGH): Unit = {
    dut.probe.logic.storeIn.valid #= true
    dut.probe.logic.storeIn.payload.paddr #= paddr
    dut.probe.logic.storeIn.payload.data #= data
    dut.probe.logic.storeIn.payload.size #= size
    dut.probe.logic.storeIn.payload.useStrb #= false
    dut.probe.logic.storeIn.payload.cacheMode #= cacheMode
    cd.waitSampling()
    dut.probe.logic.storeIn.valid #= false
    cd.waitSampling(12)
  }

  /** Mimic the exception-FSM frame push: pulse one WORD store, then wait for the
    * write-through ACK (AXI B) before the next — single-outstanding, ack-gated. */
  def doStoreAckGated(dut: Dut, cd: ClockDomain, paddr: Long, data: BigInt): Unit = {
    dut.probe.logic.storeIn.valid #= true
    dut.probe.logic.storeIn.payload.paddr #= paddr
    dut.probe.logic.storeIn.payload.data #= data
    dut.probe.logic.storeIn.payload.size #= Size.WORD
    dut.probe.logic.storeIn.payload.useStrb #= false
    dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
    cd.waitSampling()
    dut.probe.logic.storeIn.valid #= false
    // storeAck == AXI B handshake (b.ready is held True by the cache).
    cd.waitSamplingWhere(dut.dcache.logic.axi.b.valid.toBoolean && dut.dcache.logic.axi.b.ready.toBoolean)
  }

  /** Same ack-gated single-WORD-store shape as `doStoreAckGated`, but also samples
    * `storeErrReg` (task P1.4, `simPublic`) at the exact B handshake cycle and
    * returns whether it pulsed — `storeErrReg` is a combinational net off
    * axi.b.valid/ready/resp (same cycle class as `storeAckReg`), so reading it right
    * after `waitSamplingWhere` returns observes the value live for that handshake. */
  def doStoreAckGatedObserveErr(dut: Dut, cd: ClockDomain, paddr: Long, data: BigInt,
                                 cacheMode: SpinalEnumElement[CacheMode.type] = CacheMode.WRITETHROUGH): Boolean = {
    dut.probe.logic.storeIn.valid #= true
    dut.probe.logic.storeIn.payload.paddr #= paddr
    dut.probe.logic.storeIn.payload.data #= data
    dut.probe.logic.storeIn.payload.size #= Size.WORD
    dut.probe.logic.storeIn.payload.useStrb #= false
    dut.probe.logic.storeIn.payload.cacheMode #= cacheMode
    cd.waitSampling()
    dut.probe.logic.storeIn.valid #= false
    cd.waitSamplingWhere(dut.dcache.logic.axi.b.valid.toBoolean && dut.dcache.logic.axi.b.ready.toBoolean)
    val err = dut.dcache.logic.storeErrReg.toBoolean
    cd.waitSampling(4)
    err
  }

  def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
    dut.probe.logic.loadCmdIn.valid #= false
    dut.probe.logic.storeIn.valid #= false
    cd.waitSampling(4)
    (cd, mem)
  }

  /** Task P1.4: `BehavioralMemAgent` with `injectBusErrors` enabled, so an access
    * to an address outside `BehavioralMem.decoded` gets a genuine AXI DECERR (see
    * that class's doc comment) instead of a silently-successful OKAY. */
  def initDutErrInject(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd, injectBusErrors = true)
    dut.probe.logic.loadCmdIn.valid #= false
    dut.probe.logic.storeIn.valid #= false
    cd.waitSampling(4)
    (cd, mem)
  }

  // (a) cold miss -> refill -> size-extracted data; (e) byte/word/long extraction
  test("cold miss refills and returns size-extracted big-endian data", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x1230L  // 16-aligned line at 0x1230
      preload(mem, base & ~0xfL, 16)

      assert(load(dut, cd, base, Size.LONG) == expected(base, 4), "LONG extract")
      assert(load(dut, cd, base, Size.WORD) == expected(base, 2), "WORD extract")
      assert(load(dut, cd, base, Size.BYTE) == expected(base, 1), "BYTE extract")
      // a byte deeper in the line
      assert(load(dut, cd, base + 3, Size.BYTE) == expected(base + 3, 1), "BYTE @+3")
      cd.waitSampling(4)
    }
  }

  // (b) re-load same addr -> hit (no refill, loadBusy stays low)
  test("warm hit issues no new AXI burst", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x2000L
      preload(mem, base, 16)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1 } }

      load(dut, cd, base, Size.LONG)         // cold miss -> 1 refill
      val after1 = arCount
      val got = load(dut, cd, base + 4, Size.LONG)  // same line -> hit
      assert(got == expected(base + 4, 4), "hit data")
      assert(arCount == after1, s"warm hit must not refill: $after1 -> $arCount")
      cd.waitSampling(4)
    }
  }

  // (c) store to a hit line -> later load sees new data AND memory written
  test("store to a hit line is write-through and updates the line", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x3000L
      preload(mem, base, 16)
      load(dut, cd, base, Size.LONG)   // warm the line

      // store a LONG 0xDEADBEEF at base+4 (big-endian)
      doStore(dut, cd, base + 4, BigInt("DEADBEEF", 16), Size.LONG)

      // memory written (big-endian: byte base+4 = 0xDE ...)
      assert(mem.peekByte(base + 4) == 0xDE, "mem byte +4")
      assert(mem.peekByte(base + 5) == 0xAD, "mem byte +5")
      assert(mem.peekByte(base + 6) == 0xBE, "mem byte +6")
      assert(mem.peekByte(base + 7) == 0xEF, "mem byte +7")
      // cached line updated -> a hit load sees the new value
      val got = load(dut, cd, base + 4, Size.LONG)
      assert(got == BigInt("DEADBEEF", 16), s"updated line: got ${got.toString(16)}")
      cd.waitSampling(4)
    }
  }

  // (c2) directed S0/S1 pipeline: a partial sub-word store hit MERGES into the line
  // (surrounding bytes preserved) and reads back correctly. Pins the read-modify-write
  // across the (now pipelined) +1-cycle store write.
  test("store-RMW merges a sub-word into a hit line preserving neighbors", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x5000L
      preload(mem, base, 16)         // known image
      load(dut, cd, base, Size.LONG) // warm the whole line

      // store a WORD 0xCAFE at base+2 (big-endian: byte+2=0xCA, byte+3=0xFE)
      doStore(dut, cd, base + 2, BigInt("CAFE", 16), Size.WORD)

      // memory: only the two stored bytes change
      assert(mem.peekByte(base + 0) == memByte(base + 0), "mem byte 0 preserved")
      assert(mem.peekByte(base + 1) == memByte(base + 1), "mem byte 1 preserved")
      assert(mem.peekByte(base + 2) == 0xCA, "mem byte 2 = CA")
      assert(mem.peekByte(base + 3) == 0xFE, "mem byte 3 = FE")
      assert(mem.peekByte(base + 4) == memByte(base + 4), "mem byte 4 preserved")

      // cached line: the WORD reads back merged, neighbors intact
      assert(load(dut, cd, base + 2, Size.WORD) == BigInt("CAFE", 16), "merged word")
      assert(load(dut, cd, base + 0, Size.WORD) == expected(base + 0, 2), "neighbor lo word")
      assert(load(dut, cd, base + 4, Size.LONG) == expected(base + 4, 4), "neighbor hi long")
      cd.waitSampling(4)
    }
  }

  // (c3) two back-to-back stores to the same hit line both land (S0/S1 sequencing).
  test("two sequential stores to the same line both land", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x6000L
      preload(mem, base, 16)
      load(dut, cd, base, Size.LONG)

      doStore(dut, cd, base + 0, BigInt("AABBCCDD", 16), Size.LONG)
      doStore(dut, cd, base + 8, BigInt("11223344", 16), Size.LONG)

      assert(load(dut, cd, base + 0, Size.LONG) == BigInt("AABBCCDD", 16), "store 1 landed")
      assert(load(dut, cd, base + 8, Size.LONG) == BigInt("11223344", 16), "store 2 landed")
      cd.waitSampling(4)
    }
  }

  // (d) store to a missing line -> memory written, no allocate (re-load misses then refills new value)
  test("store to a missing line is write-through with no allocate", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x4000L
      preload(mem, base, 16)
      // no prior load -> line not cached
      doStore(dut, cd, base, BigInt("11223344", 16), Size.LONG)
      assert(mem.peekByte(base + 0) == 0x11, "mem byte 0")
      assert(mem.peekByte(base + 3) == 0x44, "mem byte 3")

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1 } }
      // re-load: must miss (no allocate happened) -> refill picks up the new memory value
      val got = load(dut, cd, base, Size.LONG)
      assert(arCount == 1, s"missing-line store must not allocate: refill count $arCount")
      assert(got == BigInt("11223344", 16), s"refill sees stored value: ${got.toString(16)}")
      cd.waitSampling(4)
    }
  }

  // (f) ack-gated back-to-back WORD stores to an UNCACHED line each land in memory.
  // Reproduces the exception-FSM frame push (issue store -> wait storeAck -> next):
  // every write-through beat must reach memory; none may be dropped by the pipeline.
  test("ack-gated back-to-back word stores to a missing line all land", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x7f00L                  // uncached line (no prior load)
      // Eight ascending WORD stores within one 16-byte line, ack-gated each.
      val words = Seq(0x1111, 0x2222, 0x3333, 0x4444, 0x5555, 0x6666, 0x7777, 0x8888)
      for ((w, i) <- words.zipWithIndex) doStoreAckGated(dut, cd, base + i * 2, BigInt(w))
      cd.waitSampling(8)
      for ((w, i) <- words.zipWithIndex) {
        val hi = (w >> 8) & 0xff; val lo = w & 0xff
        assert(mem.peekByte(base + i * 2)     == hi, f"word $i%d hi byte @+${i*2}%x")
        assert(mem.peekByte(base + i * 2 + 1) == lo, f"word $i%d lo byte @+${i*2+1}%x")
      }
    }
  }

  // ---- Task P1.4: inhibited (MMIO) load/store bypass + storeErr ----

  // (g) an INHIBITED load never allocates a line: repeated inhibited loads to the
  // SAME address each issue a fresh AXI refill (no resident line is ever created).
  test("inhibited load never allocates a line", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x8000L
      preload(mem, base, 16)

      var arCount = 0
      fork { while (true) { cd.waitSampling()
        if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1 } }

      val got1 = load(dut, cd, base, Size.LONG, CacheMode.INHIBITED)
      assert(got1 == expected(base, 4), "inhibited load still returns correct data")
      val after1 = arCount
      assert(after1 == 1, s"first inhibited load must refill once: $after1")

      val got2 = load(dut, cd, base, Size.LONG, CacheMode.INHIBITED)
      assert(got2 == expected(base, 4), "second inhibited load still returns correct data")
      val after2 = arCount
      assert(after2 == after1 + 1,
        s"a SECOND inhibited load to the SAME line must ALSO refill (no line was ever allocated): $after1 -> $after2")
      cd.waitSampling(4)
    }
  }

  // (h) an INHIBITED load bypasses a resident cached alias: a prior CACHEABLE load
  // at the same address left a line resident; memory is then mutated directly
  // (bypassing the cache, like an MMIO register changing on its own). The inhibited
  // load must see the NEW value, never the stale resident alias.
  test("inhibited load bypasses a resident cached alias", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x9000L
      preload(mem, base, 16)

      val cachedVal = load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)
      assert(cachedVal == expected(base, 4), "warm cacheable load")

      mem.pokeByte(base + 0, 0xAA); mem.pokeByte(base + 1, 0xBB)
      mem.pokeByte(base + 2, 0xCC); mem.pokeByte(base + 3, 0xDD)

      val got = load(dut, cd, base, Size.LONG, CacheMode.INHIBITED)
      assert(got == BigInt("AABBCCDD", 16),
        s"inhibited load must bypass the stale resident alias: got ${got.toString(16)}")
      cd.waitSampling(4)
    }
  }

  // (i) an INHIBITED store skips the line write (drains AXI-only): memory updates,
  // but a resident cached line at the same address is left untouched — a later
  // CACHEABLE reload still observes the OLD (stale) cached value.
  test("inhibited store skips the line write (drains AXI-only)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0xA000L
      preload(mem, base, 16)
      val cachedVal = load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)
      assert(cachedVal == expected(base, 4), "warm cacheable load")

      doStore(dut, cd, base, BigInt("11223344", 16), Size.LONG, CacheMode.INHIBITED)
      assert(mem.peekByte(base + 0) == 0x11, "mem written by the inhibited store")
      assert(mem.peekByte(base + 1) == 0x22, "mem written by the inhibited store")
      assert(mem.peekByte(base + 2) == 0x33, "mem written by the inhibited store")
      assert(mem.peekByte(base + 3) == 0x44, "mem written by the inhibited store")

      val got = load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)
      assert(got == expected(base, 4),
        s"cacheable reload must still see the STALE resident value (array untouched by the inhibited store): got ${got.toString(16)}")
      cd.waitSampling(4)
    }
  }

  // (j) storeErr pulses exactly on a non-OKAY B (SLVERR/DECERR), never otherwise.
  // storeAck is unaffected either way (pulses on ANY B handshake).
  test("storeErr pulses exactly on a non-OKAY B and never otherwise", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDutErrInject(dut)

      val errGood1 = doStoreAckGatedObserveErr(dut, cd, 0x1000L, BigInt("ABCD", 16))
      assert(!errGood1, "storeErr must NOT pulse on an OKAY B (decoded address)")

      val errBad = doStoreAckGatedObserveErr(dut, cd, 0xAAAA0000L, BigInt("DEAD", 16))
      assert(errBad, "storeErr MUST pulse on a non-OKAY B (undecoded address -> DECERR)")

      val errGood2 = doStoreAckGatedObserveErr(dut, cd, 0x1010L, BigInt("1234", 16))
      assert(!errGood2, "storeErr must not remain latched/stuck after a prior error pulse")

      cd.waitSampling(4)
    }
  }

  // (k) Task P4.1: a COPYBACK-hit store drain resolves ENTIRELY on-chip -- it merges
  // into the line, sets the line's dirty bit, and acks off the registered S2-local
  // pulse (cbHitAckReg) instead of an AXI B round trip. ZERO axi.aw/axi.w activity.
  test("COPYBACK hit drain resolves locally: no AXI aw/w, acks within ~3 cycles, sets dirtys",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x4000L
      preload(mem, base, 16)
      // Warm the line. Allocation on a cold miss is unconditional for any non-
      // INHIBITED mode (the resident tag/valid array carries no cache-mode of its
      // own), so which mode warms it is irrelevant to hit-detection.
      load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)

      var awCount = 0
      var wCount  = 0
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.axi.aw.valid.toBoolean && dut.dcache.logic.axi.aw.ready.toBoolean) awCount += 1
          if (dut.dcache.logic.axi.w.valid.toBoolean  && dut.dcache.logic.axi.w.ready.toBoolean)  wCount  += 1
        }
      }

      dut.probe.logic.storeIn.valid #= true
      dut.probe.logic.storeIn.payload.paddr #= base + 4
      dut.probe.logic.storeIn.payload.data #= BigInt("CAFEBABE", 16)
      dut.probe.logic.storeIn.payload.size #= Size.LONG
      dut.probe.logic.storeIn.payload.useStrb #= false
      dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
      cd.waitSampling()
      dut.probe.logic.storeIn.valid #= false

      // Local ack (cbHitAckReg -> storeAckReg) must land within ~3 cycles of the
      // drain being presented (design doc: "S2 or the following cycle") -- give a
      // small margin above the expected 3 to avoid pipeline-count brittleness.
      var cyc = 0
      var acked = false
      while (!acked && cyc < 5) {
        cd.waitSampling()
        cyc += 1
        if (dut.dcache.logic.storeAckReg.toBoolean) acked = true
      }
      assert(acked, s"COPYBACK-hit drain must ack within ~3 cycles (none seen by cycle $cyc)")
      assert(cyc <= 4, s"COPYBACK-hit drain ack landed suspiciously late (cycle $cyc), expected ~3")

      cd.waitSampling(6)
      assert(awCount == 0, s"COPYBACK hit drain must issue ZERO AXI aw beats: got $awCount")
      assert(wCount == 0, s"COPYBACK hit drain must issue ZERO AXI w beats: got $wCount")

      // Memory must remain UNTOUCHED -- no AXI write ever happened on this path; the
      // merged data lives only in the cache line + its new dirty bit.
      assert(mem.peekByte(base + 4) == memByte(base + 4), "mem byte +4 must be unwritten (copyback hit, no AXI beat)")
      assert(mem.peekByte(base + 5) == memByte(base + 5), "mem byte +5 must be unwritten (copyback hit, no AXI beat)")
      assert(mem.peekByte(base + 6) == memByte(base + 6), "mem byte +6 must be unwritten (copyback hit, no AXI beat)")
      assert(mem.peekByte(base + 7) == memByte(base + 7), "mem byte +7 must be unwritten (copyback hit, no AXI beat)")

      // The dirty bit for this set (some way) must now be set.
      val setIdx = ((base + 4) >> 4) & 0x7F
      val anyDirty = (0 until 4).exists(w => dut.dcache.logic.dirtys(w)(setIdx.toInt).toBoolean)
      assert(anyDirty, s"dirtys must be set for set $setIdx after a COPYBACK hit drain")

      // The merge landed in the cache array: a subsequent hit load sees the new value.
      val got = load(dut, cd, base + 4, Size.LONG, CacheMode.COPYBACK)
      assert(got == BigInt("CAFEBABE", 16), s"COPYBACK hit line updated: got ${got.toString(16)}")
      cd.waitSampling(4)
    }
  }

  // (l) Task P4.1 regression guard: a WRITETHROUGH hit drain is BYTE-FOR-BYTE
  // UNCHANGED from before this task -- still issues exactly one AXI aw+w beat pair,
  // still writes memory synchronously, and NEVER sets a dirty bit (dirtys is a
  // COPYBACK-only concept).
  test("WRITETHROUGH hit drain is unchanged: issues one AXI beat, never sets dirtys",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x4200L
      preload(mem, base, 16)
      load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)

      var awCount = 0
      var wCount  = 0
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.axi.aw.valid.toBoolean && dut.dcache.logic.axi.aw.ready.toBoolean) awCount += 1
          if (dut.dcache.logic.axi.w.valid.toBoolean  && dut.dcache.logic.axi.w.ready.toBoolean)  wCount  += 1
        }
      }

      doStore(dut, cd, base + 4, BigInt("11223344", 16), Size.LONG, CacheMode.WRITETHROUGH)

      assert(awCount == 1, s"WRITETHROUGH hit drain must still issue exactly one AXI aw beat: got $awCount")
      assert(wCount == 1, s"WRITETHROUGH hit drain must still issue exactly one AXI w beat: got $wCount")

      assert(mem.peekByte(base + 4) == 0x11, "mem written through as before")
      assert(mem.peekByte(base + 5) == 0x22, "mem written through as before")
      assert(mem.peekByte(base + 6) == 0x33, "mem written through as before")
      assert(mem.peekByte(base + 7) == 0x44, "mem written through as before")

      val setIdx = ((base + 4) >> 4) & 0x7F
      val anyDirty = (0 until 4).exists(w => dut.dcache.logic.dirtys(w)(setIdx.toInt).toBoolean)
      assert(!anyDirty, "WRITETHROUGH must never set a dirty bit")

      val got = load(dut, cd, base + 4, Size.LONG, CacheMode.WRITETHROUGH)
      assert(got == BigInt("11223344", 16), s"cached line updated as before: got ${got.toString(16)}")
      cd.waitSampling(4)
    }
  }

  // (m) Task P4.2: a COPYBACK store to a NOT-resident line (cold, no prior load)
  // drains via post-commit write-allocate off the (shared) load-refill engine --
  // exactly one AXI AR/R refill, ZERO AXI aw/w beats (the merge happens on-chip
  // after the line lands), the merged line is left dirty, and a subsequent hit
  // load observes the merged value with NO further refill. `busFaultResp` (the
  // architectural-fault carrier) must never pulse -- this is a clean drain, not
  // a fault path.
  test("COPYBACK miss drains via write-allocate: sets valid+dirty, no architectural fault",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0xB000L
      preload(mem, base, 16)
      // No prior load -- the line is NOT resident at this address.

      var arCount = 0
      var awCount = 0
      var wCount  = 0
      var faulted = false
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1
          if (dut.dcache.logic.axi.aw.valid.toBoolean && dut.dcache.logic.axi.aw.ready.toBoolean) awCount += 1
          if (dut.dcache.logic.axi.w.valid.toBoolean  && dut.dcache.logic.axi.w.ready.toBoolean)  wCount  += 1
          if (dut.dcache.logic.busFaultResp.toBoolean) faulted = true
        }
      }

      dut.probe.logic.storeIn.valid #= true
      dut.probe.logic.storeIn.payload.paddr #= base + 4
      dut.probe.logic.storeIn.payload.data #= BigInt("CAFEBABE", 16)
      dut.probe.logic.storeIn.payload.size #= Size.LONG
      dut.probe.logic.storeIn.payload.useStrb #= false
      dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
      cd.waitSampling()
      dut.probe.logic.storeIn.valid #= false

      // The drain now depends on an actual AXI refill round trip (AR handshake +
      // R data + REPLAY merge), unlike the COPYBACK-hit case's immediate local
      // ack -- give it real slack.
      var cyc = 0
      var acked = false
      while (!acked && cyc < 60) {
        cd.waitSampling()
        cyc += 1
        if (dut.dcache.logic.storeAckReg.toBoolean) acked = true
      }
      assert(acked, s"COPYBACK-miss drain must eventually ack via write-allocate (none seen by cycle $cyc)")

      cd.waitSampling(6)
      assert(!faulted, "a clean COPYBACK-miss drain must never raise busFaultResp (no architectural fault)")
      assert(arCount == 1, s"write-allocate refill must issue exactly one AXI AR beat: got $arCount")
      assert(awCount == 0, s"COPYBACK-miss drain must issue ZERO AXI aw beats (merge is on-chip): got $awCount")
      assert(wCount == 0, s"COPYBACK-miss drain must issue ZERO AXI w beats (merge is on-chip): got $wCount")

      // Memory must remain UNTOUCHED by the store's own bytes -- no AXI write ever
      // happened; only the refill's READ of the preloaded image occurred.
      assert(mem.peekByte(base + 4) == memByte(base + 4), "mem byte +4 unwritten (write-allocate is on-chip only)")
      assert(mem.peekByte(base + 7) == memByte(base + 7), "mem byte +7 unwritten (write-allocate is on-chip only)")

      // The dirty bit for this set (some way) must now be set.
      val setIdx = ((base + 4) >> 4) & 0x7F
      val anyDirty = (0 until 4).exists(w => dut.dcache.logic.dirtys(w)(setIdx.toInt).toBoolean)
      assert(anyDirty, s"dirtys must be set for set $setIdx after a COPYBACK-miss write-allocate drain")

      // The line is now resident (valid): a subsequent hit load sees the merged
      // value WITHOUT triggering a second refill.
      val beforeAr = arCount
      val got = load(dut, cd, base + 4, Size.LONG, CacheMode.COPYBACK)
      assert(got == BigInt("CAFEBABE", 16), s"write-allocated line holds the merged store: got ${got.toString(16)}")
      assert(arCount == beforeAr, s"post-allocate reload must be a HIT (no second refill): $beforeAr -> $arCount")

      // Neighboring bytes (not covered by the store's strobe) came from the
      // refill's read of the original preloaded image, untouched by the merge.
      val neighbor = load(dut, cd, base, Size.LONG, CacheMode.COPYBACK)
      assert(neighbor == expected(base, 4), s"neighbor bytes preserved from refill image: got ${neighbor.toString(16)}")
      cd.waitSampling(4)
    }
  }

  // (n) Task P4.3: EVICT_WR -- a dirty COPYBACK victim's line must be written back
  // to memory BEFORE its way is reallocated by the incoming refill.
  test("EVICT_WR: a dirty COPYBACK victim is written back before its way is reallocated",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val SET  = 30L
      val base = SET * 16L
      def addrK(k: Long): Long = base + k * 0x800L
      for (k <- 0L until 5L) preload(mem, addrK(k), 16)

      // Way 0: warm + dirty via a COPYBACK-hit store (design doc's drain-throughput
      // path -- P4.1).
      load(dut, cd, addrK(0), Size.LONG, CacheMode.WRITETHROUGH)
      dut.probe.logic.storeIn.valid #= true
      dut.probe.logic.storeIn.payload.paddr #= addrK(0) + 4
      dut.probe.logic.storeIn.payload.data #= BigInt("CAFEBABE", 16)
      dut.probe.logic.storeIn.payload.size #= Size.LONG
      dut.probe.logic.storeIn.payload.useStrb #= false
      dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
      cd.waitSampling()
      dut.probe.logic.storeIn.valid #= false
      cd.waitSamplingWhere(dut.dcache.logic.storeAckReg.toBoolean)
      cd.waitSampling(2)
      val setIdx = SET.toInt
      assert(dut.dcache.logic.dirtys(0)(setIdx).toBoolean, "way 0 must be dirty before the eviction")

      // Ways 1..3: warm, clean -- victim round-robins back to way 0 on the 5th miss.
      for (k <- 1L until 4L) load(dut, cd, addrK(k), Size.LONG, CacheMode.WRITETHROUGH)

      var arCount = 0
      var awCount = 0
      var wCount  = 0
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1
          if (dut.dcache.logic.axi.aw.valid.toBoolean && dut.dcache.logic.axi.aw.ready.toBoolean) awCount += 1
          if (dut.dcache.logic.axi.w.valid.toBoolean  && dut.dcache.logic.axi.w.ready.toBoolean)  wCount  += 1
        }
      }

      // The 5th distinct line (same set) misses, picks way 0 (dirty) as victim --
      // must trigger EXACTLY one eviction beat (aw+w) before the new line's own AR.
      val got = load(dut, cd, addrK(4), Size.LONG, CacheMode.WRITETHROUGH)
      assert(got == expected(addrK(4), 4), s"the new line loads correctly after eviction: got ${got.toString(16)}")
      cd.waitSampling(4)

      assert(awCount == 1, s"exactly one eviction AW beat expected: got $awCount")
      assert(wCount == 1, s"exactly one eviction W beat expected: got $wCount")
      assert(arCount == 1, s"exactly one AR beat for the new line's own refill: got $arCount")

      // The evicted (dirty) line landed in memory EXACTLY as it was cached --
      // original preloaded bytes except the CAFEBABE-merged word at +4 (checked
      // byte-by-byte, matching this file's existing peekByte convention, rather
      // than via peek128's byte-order which is not otherwise exercised here).
      for (i <- Seq(0, 1, 2, 3, 8, 9, 10, 11, 12, 13, 14, 15))
        assert(mem.peekByte(addrK(0) + i) == memByte(addrK(0) + i), s"evicted line byte +$i preserved")
      assert(mem.peekByte(addrK(0) + 4) == 0xCA, "evicted line CAFEBABE byte 0 (+4) landed")
      assert(mem.peekByte(addrK(0) + 5) == 0xFE, "evicted line CAFEBABE byte 1 (+5) landed")
      assert(mem.peekByte(addrK(0) + 6) == 0xBA, "evicted line CAFEBABE byte 2 (+6) landed")
      assert(mem.peekByte(addrK(0) + 7) == 0xBE, "evicted line CAFEBABE byte 3 (+7) landed")

      // The way is now clean (a fresh allocate always clears dirty) and holds the
      // NEW (addrK(4)) line -- a reload must NOT re-trigger a refill.
      assert(!dut.dcache.logic.dirtys(0)(setIdx).toBoolean, "way 0 must be clean after reallocation")
      val beforeAr = arCount
      val got2 = load(dut, cd, addrK(4), Size.LONG, CacheMode.WRITETHROUGH)
      assert(got2 == expected(addrK(4), 4), "reload of the new line is a clean hit")
      assert(arCount == beforeAr, "reload of the new line must NOT trigger a second refill")
      cd.waitSampling(4)
    }
  }

  // (o) Task P4.3: a CLEAN victim (never dirtied) skips straight to REFILL -- no
  // eviction writeback at all.
  test("EVICT_WR: a clean victim skips straight to REFILL (no eviction writeback)",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val SET  = 31L
      val base = SET * 16L
      def addrK(k: Long): Long = base + k * 0x800L
      for (k <- 0L until 5L) preload(mem, addrK(k), 16)

      // All 4 ways warm + CLEAN (plain WRITETHROUGH loads only, never dirtied).
      for (k <- 0L until 4L) load(dut, cd, addrK(k), Size.LONG, CacheMode.WRITETHROUGH)

      var arCount = 0
      var awCount = 0
      var wCount  = 0
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) arCount += 1
          if (dut.dcache.logic.axi.aw.valid.toBoolean && dut.dcache.logic.axi.aw.ready.toBoolean) awCount += 1
          if (dut.dcache.logic.axi.w.valid.toBoolean  && dut.dcache.logic.axi.w.ready.toBoolean)  wCount  += 1
        }
      }

      val got = load(dut, cd, addrK(4), Size.LONG, CacheMode.WRITETHROUGH)
      assert(got == expected(addrK(4), 4), s"the new line loads correctly: got ${got.toString(16)}")
      cd.waitSampling(4)

      assert(awCount == 0, s"a CLEAN victim must issue ZERO eviction AW beats: got $awCount")
      assert(wCount == 0, s"a CLEAN victim must issue ZERO eviction W beats: got $wCount")
      assert(arCount == 1, s"exactly one AR beat for the new line's own refill: got $arCount")
    }
  }

  // (p) Task P4.3 combined AXI-hazard synthesis: a concurrent ordinary store-S2
  // write-through and an EVICT_WR dirty-victim writeback, fired close together (a
  // range of relative offsets, to sweep near the contested cycle without depending
  // on hitting one exact alignment), must NOT corrupt each other's shared AXI
  // registers -- both writes must land correctly in memory, and both sides must
  // eventually complete (storeAck / a correct reload), never silently drop either.
  for (offset <- 0 to 4) {
    test(s"AXI-hazard regression: concurrent store-S2 write and EVICT_WR writeback " +
         s"do not corrupt each other (offset=$offset)", VerilatorTest) {
      sharedCompiled.doSim(s"axiHazard_$offset", 1) { dut =>
        val (cd, mem) = initDut(dut)
        val SET_A  = 40L
        val baseA  = SET_A * 16L
        def addrA(k: Long): Long = baseA + k * 0x800L
        val SET_B  = 41L
        val baseB  = SET_B * 16L
        for (k <- 0L until 5L) preload(mem, addrA(k), 16)
        preload(mem, baseB, 16)

        // SET_A way 0: warm + dirty via a COPYBACK-hit store.
        load(dut, cd, addrA(0), Size.LONG, CacheMode.WRITETHROUGH)
        dut.probe.logic.storeIn.valid #= true
        dut.probe.logic.storeIn.payload.paddr #= addrA(0) + 4
        dut.probe.logic.storeIn.payload.data #= BigInt("CAFEBABE", 16)
        dut.probe.logic.storeIn.payload.size #= Size.LONG
        dut.probe.logic.storeIn.payload.useStrb #= false
        dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
        cd.waitSampling()
        dut.probe.logic.storeIn.valid #= false
        cd.waitSamplingWhere(dut.dcache.logic.storeAckReg.toBoolean)
        cd.waitSampling(2)

        // SET_A ways 1..3: warm, clean -- victim wraps back to way 0.
        for (k <- 1L until 4L) load(dut, cd, addrA(k), Size.LONG, CacheMode.WRITETHROUGH)

        // Fire the young load that will trigger EVICT_WR (SET_A, way 0, dirty),
        // then -- after `offset` cycles -- fire an UNRELATED ordinary WRITETHROUGH
        // store to SET_B for exactly one cycle. Fire-and-forget both; we only
        // check the end state (memory content + a clean reload), not exact timing.
        dut.probe.logic.loadCmdIn.valid #= true
        dut.probe.logic.loadCmdIn.payload.vaddr #= addrA(4)
        dut.probe.logic.loadCmdIn.payload.paddr #= addrA(4)
        dut.probe.logic.loadCmdIn.payload.size  #= Size.LONG
        dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        cd.waitSampling(offset)
        dut.probe.logic.storeIn.valid #= true
        dut.probe.logic.storeIn.payload.paddr #= baseB
        dut.probe.logic.storeIn.payload.data #= BigInt("11223344", 16)
        dut.probe.logic.storeIn.payload.size #= Size.LONG
        dut.probe.logic.storeIn.payload.useStrb #= false
        dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH

        var storePulsed = false
        for (_ <- 0 until 40) {
          if (dut.probe.logic.loadCmdIn.ready.toBoolean) dut.probe.logic.loadCmdIn.valid #= false
          cd.waitSampling()
          if (!storePulsed) { dut.probe.logic.storeIn.valid #= false; storePulsed = true }
        }
        dut.probe.logic.loadCmdIn.valid #= false
        dut.probe.logic.storeIn.valid   #= false
        // Generous settle window -- both the eviction beat and the unrelated
        // store's own beat (each a full AW/W/B handshake, possibly retried under
        // this task's retry-on-preemption design) must have long since resolved.
        cd.waitSampling(60)

        // Both writes must have landed, uncorrupted, in memory.
        assert(mem.peekByte(baseB) == 0x11, "unrelated store's OWN byte 0 must land (not stomped by EVICT_WR)")
        assert(mem.peekByte(baseB + 1) == 0x22, "unrelated store's OWN byte 1 must land")
        assert(mem.peekByte(baseB + 2) == 0x33, "unrelated store's OWN byte 2 must land")
        assert(mem.peekByte(baseB + 3) == 0x44, "unrelated store's OWN byte 3 must land")
        assert(mem.peekByte(addrA(0) + 4) == 0xCA, "evicted dirty line's CAFEBABE byte 0 must land (not stomped by the store)")
        assert(mem.peekByte(addrA(0) + 5) == 0xFE, "evicted dirty line's CAFEBABE byte 1 must land")
        assert(mem.peekByte(addrA(0) + 6) == 0xBA, "evicted dirty line's CAFEBABE byte 2 must land")
        assert(mem.peekByte(addrA(0) + 7) == 0xBE, "evicted dirty line's CAFEBABE byte 3 must land")

        // The new SET_A line must be resident (correctly refilled, not corrupted
        // by a preempted/retried eviction) and the reload of SET_B's store target
        // must observe the store's own value from the cache (uncorrupted hit).
        val gotA = load(dut, cd, addrA(4), Size.LONG, CacheMode.WRITETHROUGH)
        assert(gotA == expected(addrA(4), 4), s"new SET_A line correctly refilled: got ${gotA.toString(16)}")
        val gotB = load(dut, cd, baseB, Size.LONG, CacheMode.WRITETHROUGH)
        assert(gotB == BigInt("11223344", 16), s"SET_B's stored value correctly cached: got ${gotB.toString(16)}")
        cd.waitSampling(4)
      }
    }
  }

  // (q) POST-P4.3 REVIEW, BUG 1 REGRESSION: EVICT_WR is a SECOND AXI write issuer on
  // the one physical write port, tagged id=2 (the store-S2 write-through path is
  // id=1). `storeAckReg`/`storeErrReg` accepted ANY axi.b beat, so every dirty-victim
  // eviction's own B response fired a SPURIOUS store ack.
  //
  // That is architecturally load-bearing, not cosmetic: `sq.io.drainAck :=
  // dcache.storeAck` (LsEuPlugin), so a spurious ack pops the StoreQueue head and
  // clears drainBusy BEFORE that store's own AW/W have been accepted -- the next
  // drain is then free to re-kick the SHARED stAddrReg/stMergeReg/stStrbReg while the
  // first store's beat is still notionally in flight. `exc.dcStoreAck` likewise
  // sequences the ExceptionUnit's E_STWAIT frame writer, and `storeErrReg` feeds
  // `sq.io.drainErr` (inert today, the precise-path fault source per the design doc --
  // an eviction writeback's non-OKAY response must stay diagnostic-only, never
  // architectural).
  //
  // Scenario: the same shape as the (p) sweep above -- exactly ONE ordinary
  // WRITETHROUGH store (id=1, one AW/W/B round trip) racing exactly ONE dirty-victim
  // eviction (id=2, one AW/W/B round trip); the racing load's own refill is AR/R only
  // and produces no B at all. So EXACTLY ONE storeAck pulse is architecturally
  // correct. Pre-fix this observed 2 on every non-degenerate offset.
  //
  // The two stimuli are driven from independent forks at ABSOLUTE cycles rather than
  // "present the load, wait N, present the store": the naive form silently degenerates
  // at offset 0 (the load's `valid` is deasserted before its first clock edge, so no
  // load -- and therefore no eviction -- ever happens).
  for (storeCycle <- 0 to 8) {
    test(s"EVICT_WR's own B (id=2) must NOT pulse storeAck (storeCycle=$storeCycle)",
         VerilatorTest) {
      sharedCompiled.doSim(s"evictBAck_$storeCycle", 1) { dut =>
        val (cd, mem) = initDut(dut)
        val SET_A  = 50L
        val baseA  = SET_A * 16L
        def addrA(k: Long): Long = baseA + k * 0x800L
        val SET_B  = 51L
        val baseB  = SET_B * 16L
        for (k <- 0L until 5L) preload(mem, addrA(k), 16)
        preload(mem, baseB, 16)

        // SET_A way 0: warm + dirty via a COPYBACK-hit store, so the 5th miss's
        // round-robin victim (way 0) is DIRTY -> EVICT_WR.
        load(dut, cd, addrA(0), Size.LONG, CacheMode.WRITETHROUGH)
        dut.probe.logic.storeIn.valid #= true
        dut.probe.logic.storeIn.payload.paddr #= addrA(0) + 4
        dut.probe.logic.storeIn.payload.data #= BigInt("CAFEBABE", 16)
        dut.probe.logic.storeIn.payload.size #= Size.LONG
        dut.probe.logic.storeIn.payload.useStrb #= false
        dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
        cd.waitSampling()
        dut.probe.logic.storeIn.valid #= false
        cd.waitSamplingWhere(dut.dcache.logic.storeAckReg.toBoolean)
        cd.waitSampling(2)
        // SET_A ways 1..3: warm, clean.
        for (k <- 1L until 4L) load(dut, cd, addrA(k), Size.LONG, CacheMode.WRITETHROUGH)
        cd.waitSampling(4)

        // Count storeAck / storeErr pulses ONLY across the racing window (the setup
        // above legitimately produced one COPYBACK-hit local ack of its own).
        var counting    = false
        var ackCount    = 0
        var errCount    = 0
        var evictBCount = 0
        var storeBCount = 0
        fork {
          while (true) {
            cd.waitSampling()
            if (counting) {
              if (dut.dcache.logic.storeAckReg.toBoolean) ackCount += 1
              if (dut.dcache.logic.storeErrReg.toBoolean) errCount += 1
              if (dut.dcache.logic.axi.b.valid.toBoolean && dut.dcache.logic.axi.b.ready.toBoolean) {
                if (dut.dcache.logic.axi.b.payload.id.toInt == 2) evictBCount += 1 else storeBCount += 1
              }
            }
          }
        }
        counting = true

        val LOAD_CYCLE = 4
        fork {
          cd.waitSampling(LOAD_CYCLE)
          dut.probe.logic.loadCmdIn.valid #= true
          dut.probe.logic.loadCmdIn.payload.vaddr #= addrA(4)
          dut.probe.logic.loadCmdIn.payload.paddr #= addrA(4)
          dut.probe.logic.loadCmdIn.payload.size  #= Size.LONG
          dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
          cd.waitSampling()
          dut.probe.logic.loadCmdIn.valid #= false
        }
        fork {
          if (storeCycle > 0) cd.waitSampling(storeCycle)
          dut.probe.logic.storeIn.valid #= true
          dut.probe.logic.storeIn.payload.paddr #= baseB
          dut.probe.logic.storeIn.payload.data #= BigInt("11223344", 16)
          dut.probe.logic.storeIn.payload.size #= Size.LONG
          dut.probe.logic.storeIn.payload.useStrb #= false
          dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
          cd.waitSampling()
          dut.probe.logic.storeIn.valid #= false
        }
        cd.waitSampling(100)
        counting = false

        // Scenario sanity: the run must actually have contained exactly one eviction
        // writeback and exactly one store write-through (otherwise the ack-count
        // assertion below would be vacuous).
        assert(evictBCount == 1,
          s"scenario sanity: expected exactly one EVICT_WR B (id=2), got $evictBCount")
        assert(storeBCount == 1,
          s"scenario sanity: expected exactly one store-through B (id=1), got $storeBCount")
        assert(ackCount == 1,
          s"exactly ONE storeAck expected (the single id=1 write-through store); got $ackCount " +
          s"-- an eviction's own B (id=2) must never be mistaken for a store completion")
        assert(errCount == 0, s"no storeErr expected on an all-OKAY run: got $errCount")

        // ...and the functional end state stays correct (guards against "fixing" the
        // ack count by dropping one of the two writes).
        assert(mem.peekByte(baseB) == 0x11, "the store's own byte still lands in memory")
        assert(mem.peekByte(addrA(0) + 4) == 0xCA, "the evicted dirty line still lands in memory")
      }
    }
  }

  // (r) FABLE5 BUG1 REGRESSION: `loadCmdPort.ready` in IDLE must also gate on
  // `!pendingStoreMiss`. Without that term, a load accepted on the SAME cycle
  // IDLE's `.elsewhen(pendingStoreMiss)` arm is about to pick up a pending
  // COPYBACK store-drain miss leaves that load's `ldS1Valid` armed while the FSM
  // immediately leaves IDLE for REFILL/EVICT_WR to service the store. If that
  // racing load then MISSES, its miss-handling (`when(ldS1Valid && !ldS1Hit)`,
  // which only exists inside `IDLE.whenIsActive`) never runs next cycle (the FSM
  // isn't in IDLE anymore) -- `ldS1Valid` self-clears with no response ever sent
  // to `loadRsp`, hanging the LS EU's WAIT state forever. (A HIT would still
  // resolve fine -- `ldS1Resp` is combinational and state-independent -- but
  // there's no way to know that before accepting, hence the fix defers
  // acceptance by one cycle instead.)
  //
  // Scenario: a COPYBACK store to a COLD line (no prior load) sets
  // `pendingStoreMiss` (mirrors test (m) above, straight to REFILL -- an empty
  // cache has nothing dirty to evict). An independent load, targeting a
  // DIFFERENT cold line (guaranteed MISS, never resident), is driven from its
  // own fork at a fixed absolute cycle offset from the store's own kickoff --
  // this file's own established idiom for racing two independently-timed
  // stimuli (see the (p)/(q) sweeps' doc comments: "present A, wait N, present
  // B" is NOT reliable for hitting an exact single-cycle window here, because
  // this harness's fork scheduling resolves a `waitSamplingWhere`-triggered poke
  // one cycle too late to influence the very edge that made the condition true
  // -- empirically confirmed while building this test: a condition-triggered
  // poke on `pendingStoreMiss` consistently missed the race by exactly one
  // cycle, landing after the FSM had already left IDLE). `LOAD_OFFSET` below was
  // determined empirically against this store-drain's fixed S0->S1->S2 latency
  // and is cross-checked below by a fix-independent scenario-sanity assertion
  // (`loadValidAtRace`) rather than trusted blindly.
  //
  // The load's `valid` is held (re-driven every cycle, like the real LS EU's
  // back-pressure path) until actually accepted, so pre-fix it fires on the SAME
  // cycle as the race (the bug); post-fix it's deferred to a LATER IDLE cycle
  // once the store-drain has been serviced -- either way the stimulus looks the
  // same from outside, only the accept cycle differs.
  //
  // A bounded wait (200 cycles, generous slack above a normal store-drain-miss +
  // load-miss round trip) on `loadRspOut.valid` turns a regression into a clean
  // test FAILURE (assert false) instead of hanging the whole sbt run.
  test("racing load MISS on the exact IDLE cycle pendingStoreMiss is picked up must not hang " +
       "(Fable5 review Bug1 regression)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val storeBase = 0xC000L   // COPYBACK store target, cold -> triggers pendingStoreMiss
      val loadBase  = 0xC800L   // racing load target, cold, different set -> guaranteed MISS
      preload(mem, storeBase, 16)
      preload(mem, loadBase, 16)

      // Fire the COPYBACK store to the cold line: store-S2 detects a miss and
      // latches `pendingStoreMiss` (same shape as test (m) above).
      dut.probe.logic.storeIn.valid #= true
      dut.probe.logic.storeIn.payload.paddr #= storeBase + 4
      dut.probe.logic.storeIn.payload.data #= BigInt("CAFEBABE", 16)
      dut.probe.logic.storeIn.payload.size #= Size.LONG
      dut.probe.logic.storeIn.payload.useStrb #= false
      dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
      cd.waitSampling()
      dut.probe.logic.storeIn.valid #= false

      // Fix-independent scenario sanity: was `loadCmdIn.valid` actually held
      // during the SAME cycle `pendingStoreMiss` first became visible True (the
      // exact cycle IDLE's `.elsewhen(pendingStoreMiss)` arm evaluates it)? This
      // is true regardless of the fix (the load's `valid` is asserted starting
      // `LOAD_OFFSET` and held for several cycles spanning the race window
      // either way) -- it only proves the STIMULUS reached the intended window,
      // not that the bug was hit. Guards against a future latency change
      // silently turning this into a vacuous pass.
      var loadValidAtRace = false
      fork {
        var seenPending = false
        while (!seenPending) {
          cd.waitSampling()
          if (dut.dcache.logic.pendingStoreMiss.toBoolean) {
            seenPending = true
            loadValidAtRace = dut.probe.logic.loadCmdIn.valid.toBoolean
          }
        }
      }

      // Race the independent load in from its own fork, at the empirically-
      // determined fixed offset (see doc comment above).
      val LOAD_OFFSET = 3
      fork {
        cd.waitSampling(LOAD_OFFSET)
        dut.probe.logic.loadCmdIn.valid #= true
        dut.probe.logic.loadCmdIn.payload.vaddr #= loadBase
        dut.probe.logic.loadCmdIn.payload.paddr #= loadBase
        dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
        dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean &&
                              dut.probe.logic.loadCmdIn.valid.toBoolean)
        dut.probe.logic.loadCmdIn.valid #= false
      }

      var cyc       = 0
      var responded = false
      while (!responded && cyc < 200) {
        cd.waitSampling()
        cyc += 1
        if (dut.probe.logic.loadRspOut.valid.toBoolean) responded = true
      }
      assert(loadValidAtRace,
        "scenario sanity: the racing load's `valid` must be held during the exact cycle " +
        "`pendingStoreMiss` first becomes visible True -- LOAD_OFFSET may need retuning")
      assert(responded,
        s"racing load never completed within $cyc cycles -- Fable5 Bug1 regression (LS EU hang)")
      val got = dut.probe.logic.loadRspOut.payload.data.toBigInt
      assert(got == expected(loadBase, 4), s"racing load must return correct data: got ${got.toString(16)}")
      cd.waitSampling(4)
    }
  }
}
