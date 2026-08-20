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
    val dcache = new DcachePlugin()
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
    dut.probe.logic.loadCmdIn.payload.token #= 0
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean && dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    dut.probe.logic.loadProbeIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.payload.all #= false
    cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
    dut.probe.logic.loadRspOut.payload.data.toBigInt
  }

  def doStore(dut: Dut, cd: ClockDomain, paddr: Long, data: BigInt, size: SpinalEnumElement[Size.type],
              cacheMode: SpinalEnumElement[CacheMode.type] = CacheMode.WRITETHROUGH): Unit = {
    fireStore(dut, cd, paddr, data, size, cacheMode)
    cd.waitSampling(12)
  }

  /** Present one complete store Stream item and hold every payload bit until fire. */
  def fireStore(dut: Dut, cd: ClockDomain, paddr: Long, data: BigInt,
                size: SpinalEnumElement[Size.type],
                cacheMode: SpinalEnumElement[CacheMode.type] = CacheMode.WRITETHROUGH,
                precise: Boolean = false): Unit = {
    dut.probe.logic.storeIn.valid #= true
    dut.probe.logic.storeIn.payload.paddr #= paddr
    dut.probe.logic.storeIn.payload.data #= data
    dut.probe.logic.storeIn.payload.size #= size
    dut.probe.logic.storeIn.payload.useStrb #= false
    dut.probe.logic.storeIn.payload.strb #= 0
    dut.probe.logic.storeIn.payload.lineData #= 0
    dut.probe.logic.storeIn.payload.cacheMode #= cacheMode
    dut.probe.logic.storeIn.payload.precise #= precise
    cd.waitSamplingWhere(dut.probe.logic.storeIn.valid.toBoolean &&
      dut.probe.logic.storeIn.ready.toBoolean)
    dut.probe.logic.storeIn.valid #= false
  }

  /** Mimic the exception-FSM frame push: pulse one WORD store, then wait for the
    * write-through ACK (AXI B) before the next — single-outstanding, ack-gated. */
  def doStoreAckGated(dut: Dut, cd: ClockDomain, paddr: Long, data: BigInt): Unit = {
    fireStore(dut, cd, paddr, data, Size.WORD, CacheMode.WRITETHROUGH)
    // storeAck == AXI B handshake (b.ready is held True by the cache).
    cd.waitSamplingWhere(dut.dcache.logic.axi.b.valid.toBoolean && dut.dcache.logic.axi.b.ready.toBoolean)
  }

  /** Same ack-gated single-WORD-store shape as `doStoreAckGated`, but also samples
    * `storeErrReg` (task P1.4, `simPublic`) at the exact B handshake cycle and
    * returns whether it pulsed — `storeErrReg` is a combinational net off
    * axi.b.valid/ready/resp (same cycle class as `storeAckReg`), so reading it right
    * after `waitSamplingWhere` returns observes the value live for that handshake. */
  def doStoreAckGatedObserveErr(dut: Dut, cd: ClockDomain, paddr: Long, data: BigInt,
                                 cacheMode: SpinalEnumElement[CacheMode.type] = CacheMode.WRITETHROUGH,
                                 precise: Boolean = false): Boolean = {
    // Task P4.5: `precise` gates whether a B error routes to the async diagFault
    // channel (false, this default) or would-be the SQ's precise-path fault source
    // (true) -- explicitly pinned (SpinalHDL sim-poke gotcha: an unpoked testbench-
    // driven input field is NOT guaranteed 0 across seeds/runs).
    fireStore(dut, cd, paddr, data, Size.WORD, cacheMode, precise)
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
    dut.probe.logic.loadProbeIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.payload.all #= false
    dut.probe.logic.storeIn.valid #= false
    // Task P5.4: pin the maintenance port idle (an un-poked testbench-driven input is
    // NOT guaranteed 0 across seeds/runs -- this project's documented sim gotcha).
    dut.probe.logic.maintCmdIn.valid #= false
    dut.probe.logic.maintCmdIn.payload.push #= false
    dut.probe.logic.maintCmdIn.payload.invalidate #= false
    dut.probe.logic.maintCmdIn.payload.scope #= 0
    dut.probe.logic.maintCmdIn.payload.sel #= 0
    dut.probe.logic.maintCmdIn.payload.addr #= 0
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
    // Task P5.4: pin the maintenance port idle (an un-poked testbench-driven input is
    // NOT guaranteed 0 across seeds/runs -- this project's documented sim gotcha).
    dut.probe.logic.maintCmdIn.valid #= false
    dut.probe.logic.maintCmdIn.payload.push #= false
    dut.probe.logic.maintCmdIn.payload.invalidate #= false
    dut.probe.logic.maintCmdIn.payload.scope #= 0
    dut.probe.logic.maintCmdIn.payload.sel #= 0
    dut.probe.logic.maintCmdIn.payload.addr #= 0
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

      dut.dcache.logic.diagFaultExpected #= false
      val errGood1 = doStoreAckGatedObserveErr(dut, cd, 0x1000L, BigInt("ABCD", 16))
      assert(!errGood1, "storeErr must NOT pulse on an OKAY B (decoded address)")

      // This store deliberately drives a bus error (undecoded address -> DECERR) to
      // exercise storeErr -- opt in via diagFaultExpected before triggering it (else
      // the sim-side assert in DcachePlugin's own P4.5 GenerationFlags.simulation
      // block fires fatally, per that file's documented "poke diagFaultExpected :=
      // True first" contract; same pattern as the "diagFault sticky-latches..."
      // tests below).
      dut.dcache.logic.diagFaultExpected #= true
      val errBad = doStoreAckGatedObserveErr(dut, cd, 0xAAAA0000L, BigInt("DEAD", 16))
      assert(errBad, "storeErr MUST pulse on a non-OKAY B (undecoded address -> DECERR)")
      dut.dcache.logic.diagFaultExpected #= false

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

      fireStore(dut, cd, base + 4, BigInt("CAFEBABE", 16), Size.LONG, CacheMode.COPYBACK)

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
      val anyDirty = (0 until 4).exists(w => dut.dcache.logic.dirtysMem(w).getBigInt(setIdx.toInt) != 0)
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
      val anyDirty = (0 until 4).exists(w => dut.dcache.logic.dirtysMem(w).getBigInt(setIdx.toInt) != 0)
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

      fireStore(dut, cd, base + 4, BigInt("CAFEBABE", 16), Size.LONG, CacheMode.COPYBACK)

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
      val anyDirty = (0 until 4).exists(w => dut.dcache.logic.dirtysMem(w).getBigInt(setIdx.toInt) != 0)
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
      fireStore(dut, cd, addrK(0) + 4, BigInt("CAFEBABE", 16), Size.LONG, CacheMode.COPYBACK)
      cd.waitSamplingWhere(dut.dcache.logic.storeAckReg.toBoolean)
      cd.waitSampling(2)
      val setIdx = SET.toInt
      assert(dut.dcache.logic.dirtysMem(0).getBigInt(setIdx) != 0, "way 0 must be dirty before the eviction")

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
      assert(dut.dcache.logic.dirtysMem(0).getBigInt(setIdx) == 0, "way 0 must be clean after reallocation")
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
        fireStore(dut, cd, addrA(0) + 4, BigInt("CAFEBABE", 16), Size.LONG, CacheMode.COPYBACK)
        cd.waitSamplingWhere(dut.dcache.logic.storeAckReg.toBoolean)
        cd.waitSampling(2)

        // SET_A ways 1..3: warm, clean -- victim wraps back to way 0.
        for (k <- 1L until 4L) load(dut, cd, addrA(k), Size.LONG, CacheMode.WRITETHROUGH)

        // Fire the young load that will trigger EVICT_WR (SET_A, way 0, dirty),
        // then -- after `offset` cycles from its ACCEPT EDGE -- fire an unrelated
        // ordinary WRITETHROUGH store to SET_B for exactly one cycle.
        //
        // Stream protocol is load-bearing here: hold valid through the sampling edge
        // on which ready is observed, then deassert it. The old driver tested ready
        // combinationally and dropped valid BEFORE an edge. That happened to work
        // while the D-cache's conservative S2 credit kept ready low, but under the
        // legal S1/S2-overlap configuration ready is already high and the load simply
        // vanished without ever firing. The resulting untouched preload byte is 183,
        // exactly the historical `183 != 202` "corruption" report. Pin the handshake
        // so this remains an AXI/cache hazard test rather than a ready-latency test.
        dut.probe.logic.loadCmdIn.valid #= true
        dut.probe.logic.loadCmdIn.payload.vaddr #= addrA(4)
        dut.probe.logic.loadCmdIn.payload.paddr #= addrA(4)
        dut.probe.logic.loadCmdIn.payload.size  #= Size.LONG
        dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.valid.toBoolean &&
                             dut.probe.logic.loadCmdIn.ready.toBoolean)
        dut.probe.logic.loadCmdIn.valid #= false
        cd.waitSampling(offset)
        fireStore(dut, cd, baseB, BigInt("11223344", 16), Size.LONG, CacheMode.WRITETHROUGH)
        cd.waitSampling(39)
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
        fireStore(dut, cd, addrA(0) + 4, BigInt("CAFEBABE", 16), Size.LONG, CacheMode.COPYBACK)
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
          dut.probe.logic.loadCmdIn.payload.token #= 0
          cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.valid.toBoolean &&
            dut.probe.logic.loadCmdIn.ready.toBoolean)
          dut.probe.logic.loadCmdIn.valid #= false
        }
        fork {
          if (storeCycle > 0) cd.waitSampling(storeCycle)
          fireStore(dut, cd, baseB, BigInt("11223344", 16), Size.LONG, CacheMode.WRITETHROUGH)
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
  // resolve fine -- the hit response path, `ldS2Resp` since the Slice 2 S1a/S1b
  // split and `ldS1Resp` before it, is FSM-state-independent -- but there's no
  // way to know that before accepting, hence the fix defers acceptance by one
  // cycle instead.)
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
      fireStore(dut, cd, storeBase + 4, BigInt("CAFEBABE", 16), Size.LONG, CacheMode.COPYBACK)

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

  // ── Task P4.5: async diagnostic-fault channel ──────────────────────────────────
  // (r) The NEW WT-beat / INHIBITED-drain site (kind=0): a non-precise (INHIBITED-
  // drain `precise` defaults false in this DUT, matching a real fast-path drain)
  // store's AXI-B error sticky-latches {addr,resp,kind}. A subsequent, DIFFERENT
  // bus error must NOT overwrite the record (first-error-wins). storeErr/storeAck
  // keep pulsing exactly as before this task (see the "storeErr pulses exactly..."
  // test above) -- diagFault is purely additive, never a replacement.
  test("diagFault sticky-latches exactly once on a WT-beat AXI-B error (kind=0), first-error-wins",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDutErrInject(dut)

      // A clean (decoded-address) store must never pulse diagFault.
      dut.dcache.logic.diagFaultExpected #= false
      val errClean = doStoreAckGatedObserveErr(dut, cd, 0x1000L, BigInt("ABCD", 16))
      assert(!errClean, "sanity: storeErr must not pulse on an OKAY B")
      assert(!dut.dcache.logic.diagFaultValid.toBoolean, "diagFault must stay clear after an OKAY B")

      // A bad (undecoded) WRITETHROUGH store errors -- this test WANTS that, so opt
      // in via diagFaultExpected before triggering it (else the sim-side assert in
      // DcachePlugin's own Step-1 GenerationFlags.simulation block fires fatally).
      dut.dcache.logic.diagFaultExpected #= true
      val badAddr1 = 0xAAAA0000L
      val err1 = doStoreAckGatedObserveErr(dut, cd, badAddr1, BigInt("DEAD", 16))
      assert(err1, "sanity: storeErr must pulse on the injected DECERR")
      assert(dut.dcache.logic.diagFaultValid.toBoolean, "diagFault must latch on the WT-beat B error")
      assert(dut.dcache.logic.diagFaultKind.toInt == 0, "kind=0 (WT-beat / INHIBITED-drain)")
      assert(dut.dcache.logic.diagFaultResp.toInt == 3, "resp must carry the real DECERR code (3)")
      val latchedAddr1 = dut.dcache.logic.diagFaultAddr.toBigInt
      assert(latchedAddr1 == BigInt(badAddr1),
        s"latched addr must be the erroring store's own line-aligned addr: got ${latchedAddr1.toString(16)}")

      // A SECOND, DIFFERENT bad store must NOT overwrite the sticky record.
      val badAddr2 = 0xBBBB1000L
      val err2 = doStoreAckGatedObserveErr(dut, cd, badAddr2, BigInt("BEEF", 16))
      assert(err2, "sanity: the second store's own (non-sticky) storeErr must still pulse")
      assert(dut.dcache.logic.diagFaultValid.toBoolean, "diagFault must remain latched")
      assert(dut.dcache.logic.diagFaultAddr.toBigInt == BigInt(badAddr1),
        "first-error-wins: the sticky record must still hold the FIRST error's address")
      assert(dut.dcache.logic.diagFaultKind.toInt == 0, "first-error-wins: kind unchanged")
      assert(dut.dcache.logic.diagFaultResp.toInt == 3, "first-error-wins: resp unchanged")

      cd.waitSampling(4)
    }
  }

  // (s) The site pulled forward from Task P4.2 (kind=1, drain-miss write-allocate
  // refill's own AXI-R error) also feeds this task's NEW sticky diagFaultValid
  // latch -- no architectural fault, the drain is unconditionally acked via
  // `storeAllocAckReg` regardless of the refill's own error.
  test("diagFault sticky-latches on a COPYBACK write-allocate refill's AXI-R error (kind=1)",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDutErrInject(dut)
      dut.dcache.logic.diagFaultExpected #= true

      val storePaddr = 0xAAAA2004L   // undecoded -> the write-allocate refill's AR/R DECERRs
      fireStore(dut, cd, storePaddr, BigInt("CAFEBABE", 16), Size.LONG, CacheMode.COPYBACK)

      // diagFaultValid (a sticky Reg) becomes visible the cycle after REPLAY's
      // `missFault && refillReqIsStore` arm pulses diagFaultPulse (same cycle it
      // also pulses `storeAllocAckReg`, the drain's own local ack). Poll manually
      // (check-first, not `waitSamplingWhere` -- consumed one extra edge before its
      // first check, which can walk past a value that is already stably true).
      var gotIt = false
      var n = 0
      while (!gotIt && n < 200) {
        gotIt = dut.dcache.logic.diagFaultValid.toBoolean
        if (!gotIt) cd.waitSampling()
        n += 1
      }
      assert(gotIt, "diagFaultValid never latched within 200 cycles")

      assert(dut.dcache.logic.diagFaultValid.toBoolean,
        "diagFault must latch on the write-allocate refill's own AXI-R error")
      assert(dut.dcache.logic.diagFaultKind.toInt == 1, "kind=1 (drain-miss write-allocate refill)")
      assert(dut.dcache.logic.diagFaultResp.toInt == 2,
        "kind=1's resp is the hardcoded SLVERR-class placeholder (U(2,2 bits)), not the real DECERR")
      assert(dut.dcache.logic.diagFaultAddr.toBigInt == BigInt(storePaddr),
        "latched addr must be the store's own (un-line-aligned) paddr, per missPaddr's own convention")

      cd.waitSampling(4)
    }
  }

  // ── Task P4.7: eviction-writeback diagnostic fault (kind=2) ────────────────────
  // (t) The third diagFault site: a non-OKAY response on a dirty-victim eviction's
  // OWN AXI B (EVICT_WR, `DcachePlugin.scala` ~L746-752) also sticky-latches this
  // task's async diagFault channel. Unlike kind=0/kind=1 above, a plain undecoded
  // target address is NOT enough here: the eviction's own AW/W target is the
  // VICTIM'S OLD line address -- the SAME address that must have already succeeded
  // on an EARLIER read (the line's original load/warm; there is nothing to evict
  // otherwise). `AxiMemModel.decoded` is a pure function of the address, so a
  // decode-based split can never make one direction succeed and the other fail for
  // the same address. This test instead uses the new one-shot
  // `BehavioralMemAgent.armWriteFault` hook (`AxiMemModel.scala`, added for this
  // task, mirrors `IcacheSim.scala`'s `BeatFaultAxiResponder.armBeatFault` rationale
  // for the read side): everything through the dirty warm-up is a plain,
  // error-free `initDut` run (same recipe as the "EVICT_WR: a dirty COPYBACK
  // victim..." test above), and only the SPECIFIC eviction beat (targeting the
  // victim's own old line address) is armed to DECERR right before the evicting
  // load is issued.
  test("a non-OKAY eviction-writeback response (kind=2) sticky-latches diagFault and never raises an architectural fault",
       VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem) = initDut(dut)
      val SET  = 60L
      val base = SET * 16L
      def addrK(k: Long): Long = base + k * 0x800L
      for (k <- 0L until 5L) preload(mem, addrK(k), 16)

      // Way 0: warm + dirty via a COPYBACK-hit store (same recipe as the "EVICT_WR:
      // a dirty COPYBACK victim..." test above).
      load(dut, cd, addrK(0), Size.LONG, CacheMode.WRITETHROUGH)
      fireStore(dut, cd, addrK(0) + 4, BigInt("CAFEBABE", 16), Size.LONG, CacheMode.COPYBACK)
      cd.waitSamplingWhere(dut.dcache.logic.storeAckReg.toBoolean)
      cd.waitSampling(2)
      val setIdx = SET.toInt
      assert(dut.dcache.logic.dirtysMem(0).getBigInt(setIdx) != 0, "way 0 must be dirty before the eviction")

      // Ways 1..3: warm, clean -- victim round-robins back to way 0 on the 5th miss.
      for (k <- 1L until 4L) load(dut, cd, addrK(k), Size.LONG, CacheMode.WRITETHROUGH)

      // This test intentionally triggers the async diagFault path -- opt in first
      // (else the sim-side assert in DcachePlugin's own GenerationFlags.simulation
      // block fires fatally), same contract as the kind=0/kind=1 tests above. Then
      // arm the ONE beat that must fail: the victim's own OLD line address (exactly
      // the address EVICT_WR's `evictAddr` computes -- `victimEvictTag ## missSet ##
      // 0` -- which for this already-line-aligned `addrK(0)` is `addrK(0)` itself).
      dut.dcache.logic.diagFaultExpected #= true
      mem.armWriteFault(addrK(0))

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

      // The 5th distinct line (same set) misses, picks way 0 (dirty) as victim --
      // EVICT_WR fires its one aw+w beat, whose B now DECERRs (armed above), then
      // proceeds to REFILL the new line exactly as if nothing had gone wrong (kind=2
      // is diagnostic-only, never architectural/precise, per the design doc).
      val got = load(dut, cd, addrK(4), Size.LONG, CacheMode.WRITETHROUGH)
      assert(got == expected(addrK(4), 4),
        s"the new line must still load correctly despite the eviction's own error: got ${got.toString(16)}")
      cd.waitSampling(4)

      assert(!faulted, "a non-OKAY EVICTION response must never raise busFaultResp (diagnostic-only, never architectural)")
      assert(awCount == 1, s"exactly one eviction AW beat expected: got $awCount")
      assert(wCount == 1, s"exactly one eviction W beat expected: got $wCount")
      assert(arCount == 1, s"exactly one AR beat for the new line's own (unrelated, clean) refill: got $arCount")

      // The armed fault genuinely took effect at the AXI-model level (not just a
      // flag poke): the evicted line's own CAFEBABE-merged bytes must NOT have
      // landed in memory (contrast with the "EVICT_WR: a dirty COPYBACK victim..."
      // test above, where they DO land -- this is the differentiator proving the
      // write really errored instead of the assertion below being vacuous).
      assert(mem.peekByte(addrK(0) + 4) == memByte(addrK(0) + 4),
        "a DECERR'd eviction write must NOT actually land in memory (armed fault genuinely took effect)")

      // The sticky diagFault record must latch (poll -- lands the same cycle EVICT_WR
      // sees its own bad B, a few cycles before the load above even returns; same
      // defensive-poll idiom as the kind=1 test above, robust either way).
      var gotIt = false
      var n = 0
      while (!gotIt && n < 200) {
        gotIt = dut.dcache.logic.diagFaultValid.toBoolean
        if (!gotIt) cd.waitSampling()
        n += 1
      }
      assert(gotIt, "diagFaultValid never latched within 200 cycles")
      assert(dut.dcache.logic.diagFaultKind.toInt == 2, "kind=2 (dirty-victim eviction writeback)")
      assert(dut.dcache.logic.diagFaultResp.toInt == 3, "resp must carry the real DECERR code (3)")
      assert(dut.dcache.logic.diagFaultAddr.toBigInt == BigInt(addrK(0)),
        s"latched addr must be the EVICTED (old) line's own address, not the new incoming line's: " +
        s"got 0x${dut.dcache.logic.diagFaultAddr.toBigInt.toString(16)}")

      // The way is still cleanly reallocated (REFILL's own allocate write is
      // unconditional on the eviction's B response -- EVICT_WR's `goto(REFILL)` above
      // fires regardless of `axi.b.payload.resp`) and holds the NEW line; a reload
      // must not re-trigger a refill.
      assert(dut.dcache.logic.dirtysMem(0).getBigInt(setIdx) == 0, "way 0 must be clean after reallocation")
      val beforeAr = arCount
      val got2 = load(dut, cd, addrK(4), Size.LONG, CacheMode.WRITETHROUGH)
      assert(got2 == expected(addrK(4), 4), "reload of the new line is a clean hit")
      assert(arCount == beforeAr, "reload of the new line must NOT trigger a second refill")

      // ---- coreHalted/retire-freeze: Option A (documented, deliberately NOT built
      // here) ----
      //
      // The original brief also wants an end-to-end check that this diagFault drives
      // `RobPlugin.coreHalted` and freezes retire. Investigated directly (grep, not
      // assumed): as of this task, NONE of the 3 test-harness DUTs
      // (`ExecuteLockStepSpec.scala`'s `FullCoreDut`, `FuzzDut.scala`'s
      // `FuzzCoreDut`, `IpcBenchSpec.scala`'s DUT) wire
      // `rob.logic.coreHaltedIn := dc.diagFault` -- only the real production
      // top-level (`FullCoreSynth.scala:306`) has that line. This matches the
      // earlier P4.5 finding, still current.
      //
      // `RobPluginSpec.scala`'s own directed test ("coreHaltedIn freezes retire and
      // is NOT cleared by a subsequent interruptPending") already proves
      // `coreHaltedIn` genuinely freezes retire (and survives a later
      // interrupt-eligible condition) in isolation, on RobPlugin's own whitebox DUT.
      // Combined with THIS test proving kind=2 genuinely sticky-latches
      // `diagFaultValid` (`DcachePlugin.diagFault`'s override target), every link in
      // the "bad eviction B -> diagFault -> coreHaltedIn -> coreHalted -> retire
      // frozen" chain is independently, directly proven -- the ONLY thing neither
      // test exercises is the ONE-LINE wire itself
      // (`rob.logic.coreHaltedIn := dc.diagFault`), which is identical in shape to
      // `FullCoreSynth.scala`'s own line and carries essentially zero independent
      // logic to get wrong.
      //
      // Building a genuine combined end-to-end DUT would mean adding that wire to
      // the shared, 394-test `FullCoreDut` (`ExecuteLockStepSpec.scala`) purely to
      // cover a single, visually-trivial, direct signal connection -- judged
      // disproportionate effort/risk relative to what it would additionally prove,
      // given the two existing directed tests already compose to cover every real
      // link in the chain. This is Option A per the task brief's own explicit menu.
      // If a future task wants a true single-DUT end-to-end halt-on-cache-fault
      // regression (e.g. as part of wiring up the production top-level's own
      // lock-step coverage), this comment is the pointer to why it wasn't done here.
      cd.waitSampling(4)
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Task P5.4: cache-maintenance (CPUSH / CINV) walk
  // ═══════════════════════════════════════════════════════════════════════════

  val SCOPE_LINE = 1
  val SCOPE_PAGE = 2
  val SCOPE_ALL  = 3
  val SEL_DC     = 1
  val SEL_IC     = 2
  val SEL_BC     = 3

  /** Pulse one maintenance command (1 cycle, a Flow). Does NOT wait for completion. */
  def maintPulse(dut: Dut, cd: ClockDomain, push: Boolean, invalidate: Boolean,
                 scope: Int, sel: Int, addr: Long): Unit = {
    dut.probe.logic.maintCmdIn.valid #= true
    dut.probe.logic.maintCmdIn.payload.push #= push
    dut.probe.logic.maintCmdIn.payload.invalidate #= invalidate
    dut.probe.logic.maintCmdIn.payload.scope #= scope
    dut.probe.logic.maintCmdIn.payload.sel #= sel
    dut.probe.logic.maintCmdIn.payload.addr #= addr
    cd.waitSampling()
    dut.probe.logic.maintCmdIn.valid #= false
  }

  /** Wait for `maintDone`, failing loudly (rather than hanging) on a stuck walk. */
  def maintWait(dut: Dut, cd: ClockDomain, budget: Int = 40000): Int = {
    var cyc = 0
    var done = false
    while (!done && cyc < budget) {
      cd.waitSampling(); cyc += 1
      if (dut.probe.logic.maintDoneOut.toBoolean) done = true
    }
    assert(done, s"cache-maintenance walk never pulsed maintDone within $budget cycles")
    cyc
  }

  def setOf(addr: Long): Int = ((addr >> 4) & 0x7F).toInt
  def anyDirtyIn(dut: Dut, addr: Long): Boolean =
    (0 until 4).exists(w => dut.dcache.logic.dirtysMem(w).getBigInt(setOf(addr)) != 0)

  // (P5.4-a) CPUSH, Line scope: a dirty COPYBACK line is written back to memory and
  // left CLEAN but still RESIDENT (push without invalidate).
  test("CPUSH Line-scope pushes a dirty line to memory, leaving it clean and resident",
       VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x4000L
      preload(mem, base, 16)
      load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)   // warm the line

      // COPYBACK hit -> merges on-chip, sets dirty, writes NO memory.
      doStore(dut, cd, base + 4, BigInt("DEADBEEF", 16), Size.LONG, CacheMode.COPYBACK)
      assert(anyDirtyIn(dut, base), "precondition: the COPYBACK store must have dirtied the line")
      assert(mem.peekByte(base + 4) == memByte(base + 4),
        "precondition: a COPYBACK hit must not have written memory yet")

      maintPulse(dut, cd, push = true, invalidate = false, SCOPE_LINE, SEL_DC, base)
      maintWait(dut, cd)
      cd.waitSampling(4)

      assert(mem.peekByte(base + 4) == 0xDE && mem.peekByte(base + 5) == 0xAD &&
             mem.peekByte(base + 6) == 0xBE && mem.peekByte(base + 7) == 0xEF,
        f"CPUSH did not write the dirty line back: mem=${mem.peekByte(base+4)}%02x" +
        f"${mem.peekByte(base+5)}%02x${mem.peekByte(base+6)}%02x${mem.peekByte(base+7)}%02x")
      // Untouched bytes of the same line must round-trip unchanged (the whole 16-byte
      // line is written back, so this also proves the merge/line content is intact).
      assert(mem.peekByte(base + 0) == memByte(base + 0), "line byte +0 corrupted by the writeback")
      assert(mem.peekByte(base + 15) == memByte(base + 15), "line byte +15 corrupted by the writeback")
      assert(!anyDirtyIn(dut, base), "CPUSH must leave the pushed line CLEAN")
      // Still resident: a load hits and returns the merged value with no refill.
      assert(load(dut, cd, base + 4, Size.LONG, CacheMode.COPYBACK) == BigInt("DEADBEEF", 16),
        "CPUSH (no invalidate) must leave the line RESIDENT with its merged contents")
    }
  }

  test("a failed CPUSH reports maintError and preserves the dirty line for retry",
       VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x4100L
      preload(mem, base, 16)
      load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)
      doStore(dut, cd, base + 4, BigInt("DEADBEEF", 16), Size.LONG, CacheMode.COPYBACK)
      assert(anyDirtyIn(dut, base), "precondition: line must be dirty")

      dut.dcache.logic.diagFaultExpected #= true
      mem.armWriteFault(base)
      var sawMaintError = false
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.probe.logic.maintErrorOut.toBoolean) sawMaintError = true
        }
      }

      maintPulse(dut, cd, push = true, invalidate = true, SCOPE_LINE, SEL_DC, base)
      maintWait(dut, cd)
      cd.waitSampling(2)

      assert(sawMaintError, "the failed writeback must pulse maintError")
      assert(anyDirtyIn(dut, base),
        "a failed push must retain dirty state so the only current copy is retryable")
      assert(mem.peekByte(base + 4) == memByte(base + 4),
        "the injected failed writeback must not have updated memory")
      assert(load(dut, cd, base + 4, Size.LONG, CacheMode.COPYBACK) == BigInt("DEADBEEF", 16),
        "a failed push+invalidate must leave the dirty line resident and intact")

      // The fault is one-shot. A retry must now establish memory truth and invalidate.
      dut.dcache.logic.diagFaultExpected #= false
      maintPulse(dut, cd, push = true, invalidate = true, SCOPE_LINE, SEL_DC, base)
      maintWait(dut, cd)
      cd.waitSampling(2)
      assert(!anyDirtyIn(dut, base), "the successful retry must clear dirty state")
      assert(mem.peekByte(base + 4) == 0xDE && mem.peekByte(base + 7) == 0xEF,
        "the successful retry must write the retained line to memory")
      assert(load(dut, cd, base + 4, Size.LONG, CacheMode.COPYBACK) == BigInt("DEADBEEF", 16),
        "the retry's invalidation must refill the now-current memory value")
    }
  }

  // (P5.4-b) CINV, Line scope: drops the line with NO writeback -- the dirty data is
  // deliberately discarded (that is what CINV means), so memory keeps the old value
  // and the next load refills from memory.
  test("CINV Line-scope invalidates without any writeback (dirty data discarded)",
       VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x4200L
      preload(mem, base, 16)
      load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)
      doStore(dut, cd, base + 4, BigInt("DEADBEEF", 16), Size.LONG, CacheMode.COPYBACK)
      assert(anyDirtyIn(dut, base), "precondition: line must be dirty")

      var awCount = 0
      fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.axi.aw.valid.toBoolean && dut.dcache.logic.axi.aw.ready.toBoolean) awCount += 1
        }
      }

      maintPulse(dut, cd, push = false, invalidate = true, SCOPE_LINE, SEL_DC, base)
      maintWait(dut, cd)
      cd.waitSampling(4)

      assert(awCount == 0, s"CINV must issue ZERO AXI write beats, got $awCount")
      assert(!anyDirtyIn(dut, base), "CINV must clear the dirty bit")
      assert(mem.peekByte(base + 4) == memByte(base + 4),
        "CINV must NOT write the dirty data back -- it is discarded by definition")
      // The line is gone: the next load refills from memory and sees the OLD value.
      assert(load(dut, cd, base + 4, Size.LONG, CacheMode.COPYBACK) == expected(base + 4, 4),
        "after CINV the line must be non-resident (a load must refill the ORIGINAL memory contents)")
    }
  }

  // (P5.4-c) All scope walks the entire array: several dirty lines in DIFFERENT sets
  // are all pushed by a single CPUSH-All.
  test("CPUSH All-scope walks the whole array and pushes every dirty line", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      // Three lines in three DIFFERENT sets (0x40 apart -> distinct set indices).
      val bases = Seq(0x5000L, 0x5040L, 0x5080L)
      bases.foreach { b => preload(mem, b, 16) }
      bases.foreach { b =>
        load(dut, cd, b, Size.LONG, CacheMode.WRITETHROUGH)
        doStore(dut, cd, b + 4, BigInt("A5A5A5A5", 16), Size.LONG, CacheMode.COPYBACK)
        assert(anyDirtyIn(dut, b), f"precondition: line 0x$b%x must be dirty")
      }

      maintPulse(dut, cd, push = true, invalidate = true, SCOPE_ALL, SEL_DC, 0)
      maintWait(dut, cd)
      cd.waitSampling(4)

      bases.foreach { b =>
        assert(mem.peekByte(b + 4) == 0xA5 && mem.peekByte(b + 7) == 0xA5,
          f"CPUSH-All missed the dirty line at 0x$b%x")
        assert(!anyDirtyIn(dut, b), f"line 0x$b%x still dirty after CPUSH-All")
      }
    }
  }

  // (P5.4-d) An IC-only selector has no D-cache work: it completes without walking and
  // without disturbing a resident dirty line.
  test("an IC-only maintenance selector does no D-cache work at all", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x5400L
      preload(mem, base, 16)
      load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)
      doStore(dut, cd, base + 4, BigInt("DEADBEEF", 16), Size.LONG, CacheMode.COPYBACK)

      maintPulse(dut, cd, push = true, invalidate = true, SCOPE_ALL, SEL_IC, 0)
      val cycles = maintWait(dut, cd, budget = 32)
      assert(cycles <= 4, s"an IC-only selector must complete immediately, took $cycles cycles")
      assert(anyDirtyIn(dut, base), "an IC-only selector must not touch D-cache dirty state")
      assert(load(dut, cd, base + 4, Size.LONG, CacheMode.COPYBACK) == BigInt("DEADBEEF", 16),
        "an IC-only selector must leave the D-cache line resident and intact")
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // (P5.4-e) THE SAFETY REGRESSION -- the walk must not begin while a store is still
  // in flight in the D-cache.
  //
  // WHAT THIS PINS. Task P5.4's brief claimed the maintenance walk could share the
  // store path's array read port and AXI write registers "safely because excActive
  // guarantees the load FSM / store-S0..S3 pipeline are idle". That claim is FALSE as
  // stated, and is the same bug class this file already fixed twice (see EVICT_WR's
  // revision history, ~L691, and `evictAwDone`'s, ~L344): `excActive` only stops the LS
  // EU issuing anything NEW, while an OLDER, already-COMMITTED store drains out of the
  // StoreQueue completely asynchronously.
  //
  // The scenario below is the minimal, concrete corruption: a COPYBACK store is
  // presented (it merges on-chip and sets the dirty bit at its S3, several cycles
  // later), and a Line-scope CPUSH of that SAME line is requested one cycle after.
  // If the walk starts immediately it (a) steals rdSet/rdEn from the store's own S1
  // read, and (b) samples `dirtys` BEFORE the store's S3 sets it -- so it sees a clean
  // line, pushes nothing, and the store's data is silently LOST (memory keeps the old
  // value forever, and the walk reports success).
  //
  // The fix is two independent gates, either of which alone prevents this: the caller
  // (ExceptionUnit's new S_DRAIN state) waits on sqDrained + maintQuiesced, and the
  // walk's OWN `WAIT` state re-checks `dcIdleForMaint` locally regardless of what the
  // caller promised. This test drives `maintCmd` directly, so it exercises the SECOND
  // gate -- the plugin defending itself.
  // ═══════════════════════════════════════════════════════════════════════════
  test("a maintenance walk requested mid-store-drain waits for quiesce and never loses the store",
       VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x5800L
      preload(mem, base, 16)
      load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)   // warm, clean
      assert(!anyDirtyIn(dut, base), "precondition: the warmed line must start clean")

      // Continuously prove the invariant the whole design rests on: the walk is never
      // ACTIVE on a cycle when the D-cache is not idle for it.
      var violations = 0
      var walkedWhileStoreInPipe = 0
      fork {
        while (true) {
          cd.waitSampling()
          val walking = dut.dcache.logic.maintWalkingDbg.toBoolean
          val idle    = dut.dcache.logic.dcIdleForMaint.toBoolean
          val stInPipe = dut.dcache.logic.stS2Valid.toBoolean ||
                         dut.dcache.logic.stS3Valid.toBoolean
          if (walking && !idle) violations += 1
          if (walking && stInPipe) walkedWhileStoreInPipe += 1
        }
      }

      // Accept the COPYBACK store through the real Stream handshake ...
      fireStore(dut, cd, base + 4, BigInt("DEADBEEF", 16), Size.LONG, CacheMode.COPYBACK)

      // ... and request the CPUSH of that same line IMMEDIATELY -- while the store is
      // still only at S0/S1 and has NOT yet merged or set its dirty bit.
      maintPulse(dut, cd, push = true, invalidate = false, SCOPE_LINE, SEL_DC, base)
      maintWait(dut, cd)
      cd.waitSampling(6)

      assert(violations == 0,
        s"the maintenance walk was ACTIVE on $violations cycle(s) when the D-cache was not idle " +
        "for it -- the WAIT quiesce self-check did not hold it off")
      assert(walkedWhileStoreInPipe == 0,
        s"the maintenance walk ran on $walkedWhileStoreInPipe cycle(s) while a store occupied S2/S3 " +
        "-- it would steal the shared array read/write port from the store's own RMW")

      // THE PAYLOAD ASSERTION: the store's data reached memory via the CPUSH. If the
      // walk had started early it would have sampled `dirtys` before the store's S3 set
      // it, pushed nothing, and left memory holding the ORIGINAL preloaded bytes.
      assert(mem.peekByte(base + 4) == 0xDE && mem.peekByte(base + 5) == 0xAD &&
             mem.peekByte(base + 6) == 0xBE && mem.peekByte(base + 7) == 0xEF,
        f"the store was LOST: CPUSH pushed stale data. mem[base+4..7] = " +
        f"${mem.peekByte(base+4)}%02x${mem.peekByte(base+5)}%02x" +
        f"${mem.peekByte(base+6)}%02x${mem.peekByte(base+7)}%02x, expected deadbeef")
      assert(!anyDirtyIn(dut, base), "the line must be clean after the push completed")
    }
  }

  // Stream-era mirror case: maintenance is latched first, then a store holds valid
  // through WAIT/walking.  The cache must lower ready, complete the walk, accept the
  // unchanged descriptor once, and preserve it for the following push.
  test("a store held across a maintenance walk is backpressured then accepted exactly once",
       VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x5C00L
      preload(mem, base, 16)
      load(dut, cd, base, Size.LONG, CacheMode.WRITETHROUGH)   // warm, clean, way 0
      assert(!anyDirtyIn(dut, base), "precondition: the warmed line must start clean")
      cd.waitSampling(4)

      var walkedWhileStoreInPipe = 0
      var violations = 0
      fork {
        while (true) {
          cd.waitSampling()
          val walking = dut.dcache.logic.maintWalkingDbg.toBoolean
          val idle    = dut.dcache.logic.dcIdleForMaint.toBoolean
          val stInPipe = dut.dcache.logic.stS2Valid.toBoolean ||
                         dut.dcache.logic.stS3Valid.toBoolean
          if (walking && !idle) violations += 1
          if (walking && stInPipe) walkedWhileStoreInPipe += 1
        }
      }

      // Latch the CPUSH first. `maintPulse` returns in the cycle immediately after the
      // command was sampled -- which is the cycle the walk spends in `WAIT` with the
      // whole datapath (registers) idle. This is the fall-through cycle.
      maintPulse(dut, cd, push = true, invalidate = false, SCOPE_LINE, SEL_DC, base)

      // Present the COPYBACK store during WAIT and obey the Stream contract: keep
      // valid and payload stable until the cache reopens admission.
      dut.probe.logic.storeIn.valid #= true
      dut.probe.logic.storeIn.payload.paddr #= base + 4
      dut.probe.logic.storeIn.payload.data #= BigInt("DEADBEEF", 16)
      dut.probe.logic.storeIn.payload.size #= Size.LONG
      dut.probe.logic.storeIn.payload.useStrb #= false
      dut.probe.logic.storeIn.payload.strb #= 0
      dut.probe.logic.storeIn.payload.lineData #= 0
      dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
      dut.probe.logic.storeIn.payload.precise #= false
      sleep(1)
      var heldCycles = 0
      while (!dut.probe.logic.storeIn.ready.toBoolean && heldCycles < 2000) {
        assert(dut.probe.logic.storeIn.valid.toBoolean, "held store valid dropped")
        assert(dut.probe.logic.storeIn.payload.paddr.toLong == base + 4,
          "held store address changed under maintenance backpressure")
        cd.waitSampling()
        heldCycles += 1
      }
      assert(heldCycles > 0, "maintenance did not actually backpressure the store")
      assert(heldCycles < 2000, "maintenance never reopened store admission")
      cd.waitSamplingWhere(dut.probe.logic.storeIn.valid.toBoolean &&
        dut.probe.logic.storeIn.ready.toBoolean)
      dut.probe.logic.storeIn.valid #= false
      cd.waitSamplingWhere(dut.dcache.logic.storeAckReg.toBoolean)
      cd.waitSampling(3)

      assert(violations == 0,
        s"the maintenance walk was ACTIVE on $violations cycle(s) when resident D-cache " +
        "resources were not quiesced")
      assert(walkedWhileStoreInPipe == 0,
        s"the maintenance walk ran on $walkedWhileStoreInPipe cycle(s) while a store occupied " +
        "S2/S3 -- it would collide with the store's own lookup/write")

      // The first push precedes the backpressured store.  Once admitted, the store
      // must be resident+dirty exactly once; a second push proves its payload was
      // neither lost nor duplicated.
      assert(load(dut, cd, base + 4, Size.LONG, CacheMode.COPYBACK) == BigInt("DEADBEEF", 16),
        "backpressured store did not update the resident line")
      assert(anyDirtyIn(dut, base), "accepted COPYBACK store did not mark the line dirty")
      maintPulse(dut, cd, push = true, invalidate = false, SCOPE_LINE, SEL_DC, base)
      maintWait(dut, cd, budget = 2000)
      assert(mem.peekByte(base + 4) == 0xDE && mem.peekByte(base + 5) == 0xAD &&
             mem.peekByte(base + 6) == 0xBE && mem.peekByte(base + 7) == 0xEF,
        "second CPUSH did not write the accepted store payload exactly once")
      assert(mem.peekByte(base + 0) == memByte(base + 0), "line byte +0 corrupted")
      assert(mem.peekByte(base + 15) == memByte(base + 15), "line byte +15 corrupted")
      assert(!anyDirtyIn(dut, base), "the line must be clean after the push completed")
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // (P5.4-g) POST-P5.4-REVIEW REGRESSION #2 -- LIVENESS. A maintenance command latched
  // while a COPYBACK drain-miss is still PENDING must still complete.
  //
  // This test exists because the obvious form of the review's Critical-fix-2 gate --
  // gating the load FSM's latched-`pendingStoreMiss` pickup arm on `!maintBusyReg` --
  // is a REAL DEADLOCK, and this is the stimulus that proves it. `maintBusyReg` is set
  // in `WAIT` as well as during the walk proper, and `WAIT` only advances on
  // `dcIdleForMaint`, which itself requires `!pendingStoreMiss`. So a maintenance
  // command that lands while a drain miss is latched would wait forever for a latch
  // that can no longer ever be picked up. The landed gate is `!maintWalking` (False
  // during `WAIT`), which lets the pending miss drain normally while `busy` keeps
  // `WAIT` held off.
  //
  // Cycle alignment (deliberate, this is a 1-cycle-precision test): the store fire
  // returns with `s0Valid` set; +1 cycle puts the store in S1; the `maintPulse` is then
  // sampled on the store's S2 cycle, so `maintBusyReg` is already set on the cycle
  // `pendingStoreMiss` first reads True. With the `maintBusyReg` form this hangs (the
  // `maintWait` budget assert fires); with the landed form it completes and the
  // write-allocated line is pushed.
  // ═══════════════════════════════════════════════════════════════════════════
  test("a maintenance command latched while a COPYBACK drain-miss is pending still completes",
       VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x6400L        // NEVER loaded -> the COPYBACK store MISSES
      preload(mem, base, 16)
      cd.waitSampling(4)

      // Present the COPYBACK store to a non-resident line: its S2 latches
      // `pendingStoreMiss` (post-commit write-allocate).
      fireStore(dut, cd, base + 4, BigInt("CAFEBABE", 16), Size.LONG, CacheMode.COPYBACK)
      cd.waitSampling()                 // store now in S1

      // Sampled on the store's S2 cycle => maintBusyReg is set on the very cycle
      // pendingStoreMiss first reads True.
      maintPulse(dut, cd, push = true, invalidate = false, SCOPE_LINE, SEL_DC, base)

      // Fails loudly (budget assert) rather than hanging if the deadlock is present.
      maintWait(dut, cd, budget = 2000)
      cd.waitSampling(6)

      // The drain miss write-allocated the line and dirtied it; the CPUSH then pushed
      // it. Either way the store's bytes must be in memory and the line left clean.
      assert(mem.peekByte(base + 4) == 0xCA && mem.peekByte(base + 5) == 0xFE &&
             mem.peekByte(base + 6) == 0xBA && mem.peekByte(base + 7) == 0xBE,
        f"the pending drain-miss store never reached memory: mem[base+4..7] = " +
        f"${mem.peekByte(base+4)}%02x${mem.peekByte(base+5)}%02x" +
        f"${mem.peekByte(base+6)}%02x${mem.peekByte(base+7)}%02x, expected cafebabe")
      assert(mem.peekByte(base + 0) == memByte(base + 0), "line byte +0 corrupted")
      assert(!anyDirtyIn(dut, base), "the line must be clean after the push completed")
      // The cache must still be usable afterwards (no stuck latch / stuck FSM).
      assert(load(dut, cd, base + 4, Size.LONG, CacheMode.COPYBACK) == BigInt("CAFEBABE", 16),
        "the D-cache did not survive the pending-drain-miss + maintenance overlap")
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // FMax closure Slice 2 (2026-08-07): LOAD S1a/S1b split -- directed timing pins.
  //
  // The load hit RESPONSE build (way-select -> byte-lane extract -> loadRsp) moved
  // out of S1 into a new S2 register stage, costing one uniform extra cycle of
  // load-to-use latency on every D-cache hit. Hit/miss DETECTION and the whole
  // EVICT_WR/REFILL/REPLAY machinery were deliberately NOT moved (they read only
  // `ldS1Hit`). These two tests pin BOTH halves of that claim to the cycle:
  //   * the hit response is exactly ONE cycle later than it used to be, and
  //   * miss-detect / refill-kickoff timing is byte-for-byte what it was before.
  // Measured against commit 90c1f36 (pre-split) the numbers were hit=1, miss=1,
  // ar=2; post-split they are hit=2, miss=1, ar=2.
  //
  // These are EXACT equality asserts on purpose: a future change that silently
  // adds (or removes) a load-pipeline cycle should fail here loudly rather than
  // be absorbed by the latency-agnostic `waitSamplingWhere` style used everywhere
  // else in this file.
  // ═══════════════════════════════════════════════════════════════════════════

  /** Cycles, counted from the load's accept edge, until each of three events first
    * becomes observable:
    *   `_1` -- `loadRsp.valid` (the response)
    *   `_2` -- `ldS1Valid && !ldS1Hit` (the S1 miss DECISION, which drives the
    *           `when(ldS1Valid && !ldS1Hit)` REFILL trigger)
    *   `_3` -- the refill's AXI `ar.valid` ASSERTION (the refill kickoff)
    * `0` means "already live in the same observation slot as the accept"; `-1`
    * means "never happened before the response landed".
    *
    * All three are DUT-DRIVEN signals ONLY. Deliberately NOT the `ar` HANDSHAKE:
    * `ar.ready` comes from `BehavioralMemAgent`, a forked sim thread, so reading it
    * from this thread is sensitive to intra-timestep thread ordering (this project's
    * documented SpinalSim gotcha) AND to the memory model's own latency shape --
    * neither of which says anything about the D-cache's timing. `ar.valid` is a
    * pure DUT output off the REFILL state and is fully deterministic. */
  def loadTimed(dut: Dut, cd: ClockDomain, vaddr: Long,
                size: SpinalEnumElement[Size.type],
                cacheMode: SpinalEnumElement[CacheMode.type] = CacheMode.WRITETHROUGH,
                budget: Int = 200): (Int, Int, Int) = {
    dut.probe.logic.loadCmdIn.valid #= true
    dut.probe.logic.loadCmdIn.payload.vaddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.paddr #= vaddr   // identity translation in this spec
    dut.probe.logic.loadCmdIn.payload.size #= size
    dut.probe.logic.loadCmdIn.payload.cacheMode #= cacheMode
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean &&
                          dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    var rspAt  = -1
    var missAt = -1
    var arAt   = -1
    var n      = 0
    while (rspAt < 0 && n <= budget) {
      if (missAt < 0 && dut.dcache.logic.ldS1Valid.toBoolean &&
                        !dut.dcache.logic.ldS1Hit.toBoolean) missAt = n
      if (arAt < 0 && dut.dcache.logic.axi.ar.valid.toBoolean) arAt = n
      if (dut.probe.logic.loadRspOut.valid.toBoolean) rspAt = n
      if (rspAt < 0) { cd.waitSampling(); n += 1 }
    }
    (rspAt, missAt, arAt)
  }

  test("FMax slice 2: a D-cache load HIT responds out of the NEW S2 stage " +
       "(exactly one cycle later than the old S1 response)", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x7A00L
      preload(mem, base, 16)

      // Warm the line (cold miss -> refill -> resident, clean).
      assert(load(dut, cd, base, Size.LONG) == expected(base, 4), "priming load data")
      cd.waitSampling(8)

      val (rspAt, missAt, arAt) = loadTimed(dut, cd, base + 4, Size.LONG)
      assert(missAt == -1, s"this load must HIT (no S1 miss decision seen), got missAt=$missAt")
      assert(arAt == -1, s"a hit must issue no refill burst, got arAt=$arAt")
      assert(!dut.probe.logic.loadBusyOut.toBoolean, "a hit must never set loadBusy")
      assert(rspAt == 2,
        s"load HIT response must land 2 cycles after accept (S1a compare/way-select, " +
        s"S1b extract/respond); got $rspAt -- was 1 before the Slice 2 split, so a " +
        s"value of 1 means the S2 register stage is gone and the -1.987ns critical " +
        s"path is back")
      assert(dut.probe.logic.loadRspOut.payload.data.toBigInt == expected(base + 4, 4),
        "the S2-registered response must still carry the right byte-extracted data")

      // ...and a second back-to-back hit behaves identically (proves the extended
      // `inFlight = ldS1Valid || ldS2Valid` gate does not wedge the accept path).
      cd.waitSampling(4)
      val (rspAt2, _, _) = loadTimed(dut, cd, base + 8, Size.LONG)
      assert(rspAt2 == 2, s"second back-to-back hit must have the same latency, got $rspAt2")
      assert(dut.probe.logic.loadRspOut.payload.data.toBigInt == expected(base + 8, 4),
        "second hit data")
      cd.waitSampling(4)
    }
  }

  test("IPC slice C0: resident L1D hits accept and respond every cycle after warm-up " +
       "(three operations in flight, accept II=1)", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x7C00L
      preload(mem, base, 16)

      // Prime the line, then let every miss/replay pipeline register drain.
      assert(load(dut, cd, base, Size.LONG) == expected(base, 4), "priming load data")
      cd.waitSampling(8)

      val addrs = Seq(base + 4, base + 8, base + 12)
      val accepts = scala.collection.mutable.ArrayBuffer.empty[Int]
      val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt)]

      // Hold each Stream token until its own sampling-edge handshake, then replace
      // the payload with the next token. This is deliberately a three-token run:
      // A can be in S2, B in S1, and C accepted into the sync-read launch together.
      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= addrs.head
      dut.probe.logic.loadCmdIn.payload.paddr #= addrs.head
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      var next = 1
      var cycle = 0
      while ((accepts.size < addrs.size || responses.size < addrs.size) && cycle < 20) {
        cd.waitSampling()
        cycle += 1
        if (dut.probe.logic.loadCmdIn.valid.toBoolean &&
            dut.probe.logic.loadCmdIn.ready.toBoolean) {
          accepts += cycle
          if (next < addrs.size) {
            dut.probe.logic.loadCmdIn.payload.vaddr #= addrs(next)
            dut.probe.logic.loadCmdIn.payload.paddr #= addrs(next)
            next += 1
          } else {
            dut.probe.logic.loadCmdIn.valid #= false
          }
        }
        if (dut.probe.logic.loadRspOut.valid.toBoolean)
          responses += ((cycle, dut.probe.logic.loadRspOut.payload.data.toBigInt))
      }

      assert(accepts.size == 3, s"all three loads must be accepted, got $accepts")
      assert(accepts.sliding(2).forall(p => p(1) - p(0) == 1),
        s"resident-hit accepts must have II=1, got cycles $accepts")
      assert(responses.size == 3, s"all three loads must respond, got $responses")
      assert(responses.map(_._1).sliding(2).forall(p => p(1) - p(0) == 1),
        s"resident-hit responses must be bubble-free, got cycles ${responses.map(_._1)}")
      assert(responses.map(_._2) == addrs.map(a => expected(a, 4)),
        s"responses must remain in accept order: got ${responses.map(_._2)}")
      cd.waitSampling(4)
    }
  }

  test("mixed resident loads and a waiting store make bounded read-port progress",
       VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val loadBase  = 0x7E00L
      val storeBase = 0x7F40L
      preload(mem, loadBase, 16)
      preload(mem, storeBase, 16)
      load(dut, cd, loadBase, Size.LONG, CacheMode.COPYBACK)
      load(dut, cd, storeBase, Size.LONG, CacheMode.COPYBACK)
      cd.waitSampling(6)

      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= loadBase
      dut.probe.logic.loadCmdIn.payload.paddr #= loadBase
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
      dut.probe.logic.loadCmdIn.payload.token #= 0
      dut.probe.logic.storeIn.valid #= true
      dut.probe.logic.storeIn.payload.paddr #= storeBase + 4
      dut.probe.logic.storeIn.payload.data #= BigInt("DEADBEEF", 16)
      dut.probe.logic.storeIn.payload.size #= Size.LONG
      dut.probe.logic.storeIn.payload.useStrb #= false
      dut.probe.logic.storeIn.payload.strb #= 0
      dut.probe.logic.storeIn.payload.lineData #= 0
      dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
      dut.probe.logic.storeIn.payload.precise #= false

      var storeFired = false
      var storeAcked = false
      var sawOwed = false
      var sawLoadYield = false
      var loadFires = 0
      var cycle = 0
      while (!storeAcked && cycle < 24) {
        cd.waitSampling()
        cycle += 1
        if (dut.probe.logic.loadCmdIn.valid.toBoolean &&
            dut.probe.logic.loadCmdIn.ready.toBoolean) loadFires += 1
        if (dut.probe.logic.loadCmdIn.valid.toBoolean &&
            !dut.probe.logic.loadCmdIn.ready.toBoolean) sawLoadYield = true
        if (dut.probe.logic.storeIn.valid.toBoolean &&
            dut.probe.logic.storeIn.ready.toBoolean) {
          assert(!storeFired, "store command fired more than once")
          storeFired = true
          dut.probe.logic.storeIn.valid #= false
        }
        if (dut.dcache.logic.storeReadOwed.toBoolean) sawOwed = true
        if (dut.dcache.logic.storeAckReg.toBoolean) storeAcked = true
      }
      dut.probe.logic.loadCmdIn.valid #= false

      assert(storeFired, "waiting store never crossed the Stream boundary")
      assert(storeAcked, s"continuous resident loads starved the store for $cycle cycles")
      assert(sawOwed && sawLoadYield,
        "test did not exercise the registered one-load-then-store arbitration")
      assert(loadFires >= 2, s"load side was not genuinely active during contention: $loadFires fires")
      cd.waitSampling(6)
      assert(load(dut, cd, storeBase + 4, Size.LONG, CacheMode.COPYBACK) ==
        BigInt("DEADBEEF", 16), "the arbitrated store did not update the resident line")
    }
  }

  test("IPC slice C0: the load accepted behind an S1 miss is replayed after the " +
       "refill and cannot respond out of order", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val hitBase  = 0x7D00L
      val missBase = 0x9E40L
      preload(mem, hitBase, 16)
      preload(mem, missBase, 16)

      // Keep B resident while A remains cold.
      assert(load(dut, cd, hitBase, Size.LONG) == expected(hitBase, 4), "prime B")
      cd.waitSampling(8)

      val addrs = Seq(missBase, hitBase + 4)
      val accepts = scala.collection.mutable.ArrayBuffer.empty[Int]
      val responses = scala.collection.mutable.ArrayBuffer.empty[(Int, BigInt)]
      var shadowSeen = false

      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= addrs.head
      dut.probe.logic.loadCmdIn.payload.paddr #= addrs.head
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH

      var next = 1
      var cycle = 0
      while ((accepts.size < 2 || responses.size < 2) && cycle < 100) {
        cd.waitSampling()
        cycle += 1
        shadowSeen ||= dut.dcache.logic.loadShadowValid.toBoolean
        if (dut.probe.logic.loadCmdIn.valid.toBoolean &&
            dut.probe.logic.loadCmdIn.ready.toBoolean) {
          accepts += cycle
          if (next < addrs.size) {
            dut.probe.logic.loadCmdIn.payload.vaddr #= addrs(next)
            dut.probe.logic.loadCmdIn.payload.paddr #= addrs(next)
            next += 1
          } else {
            dut.probe.logic.loadCmdIn.valid #= false
          }
        }
        if (dut.probe.logic.loadRspOut.valid.toBoolean)
          responses += ((cycle, dut.probe.logic.loadRspOut.payload.data.toBigInt))
      }

      assert(accepts.size == 2 && accepts(1) - accepts(0) == 1,
        s"B must be accepted in A's S1 decision cycle, got accepts $accepts")
      assert(shadowSeen, "the directed miss must exercise the bounded replay slot")
      assert(responses.size == 2, s"both accepted loads must respond, got $responses")
      assert(responses.map(_._2) == addrs.map(a => expected(a, 4)),
        s"the miss response must precede the younger hit: got ${responses.map(_._2)}")
      assert(responses(1)._1 > responses(0)._1,
        s"the younger hit must not bypass the older refill: response cycles $responses")
      assert(!dut.dcache.logic.loadShadowValid.toBoolean, "replay slot must drain")
      cd.waitSampling(4)
    }
  }

  test("VIPT slice B: an early virtual-set probe is consumed by the matching " +
       "physical-tag command without re-reading the RAM", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x7E00L
      val addr = base + 8
      val token = 0x25
      preload(mem, base, 16)
      assert(load(dut, cd, base, Size.LONG) == expected(base, 4), "prime line")
      cd.waitSampling(8)

      // P1: virtual index only. The synchronous RAM result becomes the held early
      // probe one cycle later, while translation would proceed independently.
      dut.probe.logic.loadProbeIn.valid #= true
      dut.probe.logic.loadProbeIn.payload.vaddr #= addr
      dut.probe.logic.loadProbeIn.payload.token #= token
      dut.probe.logic.loadProbeIn.payload.resolved #= true
      dut.probe.logic.loadProbeIn.payload.paddr #= addr
      dut.probe.logic.loadProbeIn.payload.size #= Size.LONG
      dut.probe.logic.loadProbeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadProbeIn.payload.needsLine #= false
      cd.waitSamplingWhere(dut.probe.logic.loadProbeIn.valid.toBoolean &&
                           dut.probe.logic.loadProbeIn.ready.toBoolean)
      dut.probe.logic.loadProbeIn.valid #= false
      var readyWait = 0
      while (!dut.dcache.logic.earlyProbeFresh.toBoolean && readyWait < 8) {
        cd.waitSampling(); readyWait += 1
      }
      assert(dut.dcache.logic.earlyProbeValid.toBoolean, "probe metadata must be held")
      assert(dut.dcache.logic.earlyProbeFresh.toBoolean,
        s"tokenized probe result must become ready (waited $readyWait cycles)")

      // P2/P5: translation metadata arrives later with the same token. Before valid
      // is asserted, the combinational selector already proves the held result owns
      // this command. The accept must consume it without asserting rdEn again.
      dut.probe.logic.loadCmdIn.payload.vaddr #= addr
      dut.probe.logic.loadCmdIn.payload.paddr #= addr
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadCmdIn.payload.token #= token
      sleep(1)
      assert(dut.dcache.logic.useEarlyProbe.toBoolean,
        "matching token+VA must select the DTLB-parallel RAM result")
      assert(!dut.dcache.logic.rdEn.toBoolean,
        "resolved consume must not launch a redundant synchronous read")
      dut.probe.logic.loadCmdIn.valid #= true
      cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.valid.toBoolean &&
                           dut.probe.logic.loadCmdIn.ready.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
      assert(dut.probe.logic.loadRspOut.payload.data.toBigInt == expected(addr, 4),
        "physical-tag resolve must return the held virtual-set line")
      assert(!dut.dcache.logic.earlyProbeValid.toBoolean, "probe slot must be consumed")
      cd.waitSampling(4)
    }
  }

  test("VIPT D2: a same-set store write stales a held snapshot and forces updated fallback",
       VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base  = 0x7D00L
      val addr  = base + 4
      val token = 0x2D
      val replacement = BigInt("CAFEBABE", 16)
      preload(mem, base, 16)
      assert(load(dut, cd, base, Size.LONG) == expected(base, 4), "prime resident line")
      cd.waitSampling(4)

      // Capture the old line into a tokenized VIPT result, but deliberately hold
      // the matching resolved command until an older store mutates this same set.
      dut.probe.logic.loadProbeIn.valid #= true
      dut.probe.logic.loadProbeIn.payload.vaddr #= addr
      dut.probe.logic.loadProbeIn.payload.token #= token
      dut.probe.logic.loadProbeIn.payload.resolved #= true
      dut.probe.logic.loadProbeIn.payload.paddr #= addr
      dut.probe.logic.loadProbeIn.payload.size #= Size.LONG
      dut.probe.logic.loadProbeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadProbeIn.payload.needsLine #= false
      cd.waitSamplingWhere(dut.probe.logic.loadProbeIn.valid.toBoolean &&
                           dut.probe.logic.loadProbeIn.ready.toBoolean)
      dut.probe.logic.loadProbeIn.valid #= false

      dut.probe.logic.loadCmdIn.payload.vaddr #= addr
      dut.probe.logic.loadCmdIn.payload.paddr #= addr
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadCmdIn.payload.token #= token
      var readyWait = 0
      while (!dut.dcache.logic.earlyProbeOwnsCmd.toBoolean && readyWait < 8) {
        cd.waitSampling(); readyWait += 1
      }
      assert(dut.dcache.logic.useEarlyProbe.toBoolean,
        "setup must hold a usable pre-store snapshot before the mutation")

      var sawSameSetWrite = false
      var trackWrite = true
      fork {
        while (trackWrite) {
          cd.waitSampling()
          if (dut.dcache.logic.stS3ArrayWrite.toBoolean &&
              dut.dcache.logic.stS3Set.toLong == ((addr >> 4) & 0x7fL)) {
            sawSameSetWrite = true
          }
        }
      }
      fireStore(dut, cd, addr, replacement, Size.LONG, CacheMode.WRITETHROUGH)
      cd.waitSamplingWhere(dut.dcache.logic.storeAckReg.toBoolean)
      trackWrite = false
      cd.waitSampling()

      assert(sawSameSetWrite, "the directed store must really mutate the probed set")
      assert(dut.dcache.logic.earlyProbeOwnsCmd.toBoolean,
        "the stale result must retain its token so the later command can consume it")
      assert(!dut.dcache.logic.useEarlyProbe.toBoolean,
        "an intervening same-set array write must make the old snapshot unusable")

      dut.probe.logic.loadCmdIn.valid #= true
      sleep(1)
      assert(dut.dcache.logic.rdEn.toBoolean,
        "the stale snapshot must fall back to a fresh synchronous array read")
      cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.valid.toBoolean &&
                           dut.probe.logic.loadCmdIn.ready.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
      assert(dut.probe.logic.loadRspOut.payload.data.toBigInt == replacement,
        f"fallback must observe the post-store line: got 0x${dut.probe.logic.loadRspOut.payload.data.toBigInt}%08x")
      cd.waitSampling(4)
    }
  }

  test("VIPT slice C3: a proven-hit probe is consumed while the next virtual-set " +
       "probe launches on the same edge", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val addrA = 0x7E84L
      val addrB = 0x7F48L
      val tokenA = 0x31
      val tokenB = 0x32
      preload(mem, addrA & ~0xFL, 16)
      preload(mem, addrB & ~0xFL, 16)
      assert(load(dut, cd, addrA, Size.LONG) == expected(addrA, 4), "prime A")
      assert(load(dut, cd, addrB, Size.LONG) == expected(addrB, 4), "prime B")
      cd.waitSampling(8)

      // Hold A's virtual-set result in one probe-result queue entry.
      dut.probe.logic.loadProbeIn.valid #= true
      dut.probe.logic.loadProbeIn.payload.vaddr #= addrA
      dut.probe.logic.loadProbeIn.payload.token #= tokenA
      dut.probe.logic.loadProbeIn.payload.resolved #= true
      dut.probe.logic.loadProbeIn.payload.paddr #= addrA
      dut.probe.logic.loadProbeIn.payload.size #= Size.LONG
      dut.probe.logic.loadProbeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadProbeIn.payload.needsLine #= false
      cd.waitSamplingWhere(dut.probe.logic.loadProbeIn.valid.toBoolean &&
                           dut.probe.logic.loadProbeIn.ready.toBoolean)
      dut.probe.logic.loadProbeIn.valid #= false
      var readyAWait = 0
      while (!dut.dcache.logic.earlyProbeFresh.toBoolean && readyAWait < 8) {
        cd.waitSampling(); readyAWait += 1
      }
      assert(dut.dcache.logic.earlyProbeValid.toBoolean)
      assert(dut.dcache.logic.earlyProbeFresh.toBoolean,
        s"probe A result did not become ready within 8 cycles (waited $readyAWait)")

      // Resolve A while presenting B's virtual probe. Because A is a proven
      // physical-tag hit, both Streams must handshake on this same edge: A consumes
      // the old RAM output and B replaces it with a new synchronous read.
      dut.probe.logic.loadCmdIn.valid #= true
      dut.probe.logic.loadCmdIn.payload.vaddr #= addrA
      dut.probe.logic.loadCmdIn.payload.paddr #= addrA
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadCmdIn.payload.token #= tokenA
      dut.probe.logic.loadProbeIn.valid #= true
      dut.probe.logic.loadProbeIn.payload.vaddr #= addrB
      dut.probe.logic.loadProbeIn.payload.token #= tokenB
      dut.probe.logic.loadProbeIn.payload.resolved #= true
      dut.probe.logic.loadProbeIn.payload.paddr #= addrB
      dut.probe.logic.loadProbeIn.payload.size #= Size.LONG
      dut.probe.logic.loadProbeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadProbeIn.payload.needsLine #= false
      sleep(1)
      assert(dut.dcache.logic.useEarlyProbe.toBoolean,
        "A must consume its held VIPT result")
      assert(dut.probe.logic.loadCmdIn.ready.toBoolean,
        "A's resolved hit command must be accepted")
      assert(dut.probe.logic.loadProbeIn.ready.toBoolean,
        "B's virtual probe must replace A on the same proven-hit edge")
      cd.waitSampling()
      dut.probe.logic.loadCmdIn.valid #= false
      dut.probe.logic.loadProbeIn.valid #= false

      val got = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      def sampleRsp(): Unit =
        if (dut.probe.logic.loadRspOut.valid.toBoolean)
          got += dut.probe.logic.loadRspOut.payload.data.toBigInt
      sampleRsp() // A may respond while B's independent result pipe is still maturing

      assert(dut.dcache.logic.earlyProbeValid.toBoolean,
        "the result queue must remain occupied by probe B")

      // Present B's command while its queued result matures; wait on the exact
      // token+VA ownership predicate rather than the queue-wide "some result ready"
      // summary (which can transiently describe a different entry).
      dut.probe.logic.loadCmdIn.payload.vaddr #= addrB
      dut.probe.logic.loadCmdIn.payload.paddr #= addrB
      dut.probe.logic.loadCmdIn.payload.token #= tokenB
      sleep(1) // settle the token+VA CAM before sampling its ownership predicate
      var readyBWait = 0
      while (!dut.dcache.logic.earlyProbeOwnsCmd.toBoolean && readyBWait < 8) {
        cd.waitSampling(); readyBWait += 1; sampleRsp()
      }
      assert(dut.dcache.logic.earlyProbeOwnsCmd.toBoolean,
        s"B's matching pipelined result must become ready independently of A's consume " +
        s"(waited $readyBWait cycles)")

      // The very next resolved command must own B's newly allocated result, proving
      // that the turnover did not merely leave stale A metadata marked valid.
      sleep(1)
      assert(dut.dcache.logic.useEarlyProbe.toBoolean,
        "B's token+VA must own a HIT VIPT result; queue=" +
        (0 until 4).map { i =>
          s"$i:v=${dut.dcache.logic.earlyProbeValids(i).toBoolean}" +
          s",r=${dut.dcache.logic.earlyProbeReadies(i).toBoolean}" +
          s",h=${dut.dcache.logic.earlyProbeHits(i).toBoolean}" +
          f",t=0x${dut.dcache.logic.earlyProbeTokens(i).toInt}%02X" +
          f",va=0x${dut.dcache.logic.earlyProbeVaddrs(i).toBigInt}%08X"
        }.mkString("[", "; ", "]"))
      dut.probe.logic.loadCmdIn.valid #= true
      cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.valid.toBoolean &&
                           dut.probe.logic.loadCmdIn.ready.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= false
      sampleRsp()

      var cycles = 0
      while (got.size < 2 && cycles < 10) {
        cd.waitSampling()
        sampleRsp()
        cycles += 1
      }
      assert(got == Seq(expected(addrA, 4), expected(addrB, 4)),
        s"consume-and-replace responses must remain ordered and correct, got $got")
      cd.waitSampling(4)
    }
  }

  test("VIPT D2: four distinct probe results queue without aliasing and cancel-all releases them", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val addrs  = Seq(0x7200L, 0x7310L, 0x7420L, 0x7530L)
      val tokens = Seq(0x40, 0x41, 0x42, 0x43)

      for (i <- addrs.indices) {
        dut.probe.logic.loadProbeIn.valid #= true
        dut.probe.logic.loadProbeIn.payload.vaddr #= addrs(i)
        dut.probe.logic.loadProbeIn.payload.token #= tokens(i)
        // Deliberately unresolved: queue allocation/cancellation must not depend on
        // a hit, and the resulting entry must be safe to fall back later.
        dut.probe.logic.loadProbeIn.payload.resolved #= false
        dut.probe.logic.loadProbeIn.payload.paddr #= 0
        dut.probe.logic.loadProbeIn.payload.size #= Size.LONG
        dut.probe.logic.loadProbeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        dut.probe.logic.loadProbeIn.payload.needsLine #= false
        sleep(1)
        assert(dut.probe.logic.loadProbeIn.ready.toBoolean,
          s"probe $i must launch on the cycle after probe ${i - 1}")
        cd.waitSampling()
      }

      // A fifth token proves the depth rather than merely observing four writes.
      dut.probe.logic.loadProbeIn.payload.vaddr #= 0x7640L
      dut.probe.logic.loadProbeIn.payload.token #= 0x44
      sleep(1)
      assert(!dut.probe.logic.loadProbeIn.ready.toBoolean,
        "the four-entry probe-result queue must backpressure a fifth resident token")
      assert(dut.dcache.logic.earlyProbeValids.count(_.toBoolean) == 4,
        "all four queue entries must be physically resident")
      assert(dut.dcache.logic.earlyProbeTokens.zip(dut.dcache.logic.earlyProbeValids)
        .collect { case (t, v) if v.toBoolean => t.toInt }.toSet == tokens.toSet,
        "free-slot selection must preserve four distinct tokens without aliasing")

      dut.probe.logic.loadProbeCancelIn.valid #= true
      dut.probe.logic.loadProbeCancelIn.payload.all #= true
      dut.probe.logic.loadProbeCancelIn.payload.token #= 0
      cd.waitSampling()
      dut.probe.logic.loadProbeCancelIn.valid #= false
      dut.probe.logic.loadProbeCancelIn.payload.all #= false
      dut.probe.logic.loadProbeIn.valid #= false
      sleep(1)
      assert(!dut.dcache.logic.earlyProbeValids.exists(_.toBoolean),
        "flush-style cancel-all must release every resident probe token in one edge")
      assert(dut.probe.logic.loadProbeIn.ready.toBoolean,
        "probe admission must recover immediately after cancel-all")
    }
  }

  test("VIPT D2: an unresolved probe is consumed safely through the ordinary hit pipe", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val addr = 0x7788L
      val token = 0x4A
      preload(mem, addr & ~0xFL, 16)
      assert(load(dut, cd, addr, Size.LONG) == expected(addr, 4), "prime resident line")
      cd.waitSampling(4)

      dut.probe.logic.loadProbeIn.valid #= true
      dut.probe.logic.loadProbeIn.payload.vaddr #= addr
      dut.probe.logic.loadProbeIn.payload.token #= token
      dut.probe.logic.loadProbeIn.payload.resolved #= false
      dut.probe.logic.loadProbeIn.payload.paddr #= 0
      dut.probe.logic.loadProbeIn.payload.size #= Size.LONG
      dut.probe.logic.loadProbeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadProbeIn.payload.needsLine #= false
      cd.waitSamplingWhere(dut.probe.logic.loadProbeIn.valid.toBoolean &&
                           dut.probe.logic.loadProbeIn.ready.toBoolean)
      dut.probe.logic.loadProbeIn.valid #= false

      dut.probe.logic.loadCmdIn.payload.vaddr #= addr
      dut.probe.logic.loadCmdIn.payload.paddr #= addr
      dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
      dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      dut.probe.logic.loadCmdIn.payload.token #= token
      sleep(1)
      var waitReady = 0
      while (!dut.dcache.logic.earlyProbeOwnsCmd.toBoolean && waitReady < 8) {
        cd.waitSampling(); waitReady += 1
      }
      assert(dut.dcache.logic.earlyProbeOwnsCmd.toBoolean,
        "the unresolved result entry must still associate with its later command")
      assert(!dut.dcache.logic.useEarlyProbe.toBoolean,
        "an unresolved PA hint must never be treated as a physical-tag hit")

      dut.probe.logic.loadCmdIn.valid #= true
      sleep(1)
      assert(dut.dcache.logic.rdEn.toBoolean,
        "unresolved probe fallback must launch the ordinary virtual-set read")
      cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.valid.toBoolean &&
                           dut.probe.logic.loadCmdIn.ready.toBoolean)
      dut.probe.logic.loadCmdIn.valid #= false
      cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
      assert(dut.probe.logic.loadRspOut.payload.data.toBigInt == expected(addr, 4),
        "fallback hit data must remain correct")
      assert(!dut.dcache.logic.earlyProbeValid.toBoolean,
        "fallback command must consume its unusable queued result")
    }
  }

  test("FMax slice 2: miss DETECTION and refill kickoff timing are unchanged by the " +
       "S1a/S1b split", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x7B00L        // cold, never touched -> guaranteed MISS, clean victim
      preload(mem, base, 16)
      cd.waitSampling(4)

      val (rspAt, missAt, arAt) = loadTimed(dut, cd, base, Size.LONG)
      // These two are the numbers this slice promises NOT to move: `ldS1Hit` still
      // feeds `when(ldS1Valid && !ldS1Hit)` straight out of S1, so the miss decision
      // (and therefore the eviction/refill kickoff behind it) is on exactly the same
      // cycle as before the split.
      assert(missAt == 1,
        s"the S1 miss decision must still be visible 1 cycle after accept; got $missAt " +
        s"-- Slice 2 must NOT have delayed hit/miss detection")
      assert(arAt == 2,
        s"the refill's AXI ar.valid must still assert 2 cycles after accept; got $arAt")
      // The response itself IS one cycle later than before (the REPLAY re-launch
      // now runs S1a+S1b too), which is the accepted cost.
      assert(rspAt > arAt, s"the refill response must follow the burst, got $rspAt")
      assert(dut.probe.logic.loadRspOut.payload.data.toBigInt == expected(base, 4),
        "refilled data")
      cd.waitSampling(4)
    }
  }
}
