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

  /** Deterministic image byte. */
  def memByte(addr: Long): Int = ((addr * 5 + 0x23) & 0xff).toInt
  def preload(mem: BehavioralMemAgent, base: Long, n: Int): Unit =
    for (i <- 0 until n) mem.pokeByte(base + i, memByte(base + i))

  /** Big-endian extracted value at addr for a given size. */
  def expected(base: Long, size: Int): BigInt =
    (0 until size).foldLeft(BigInt(0))((acc, i) => (acc << 8) | BigInt(memByte(base + i)))

  /** Drive a load cmd, wait accept, wait rsp.valid; return data. */
  def load(dut: Dut, cd: ClockDomain, vaddr: Long, size: SpinalEnumElement[Size.type]): BigInt = {
    dut.probe.logic.loadCmdIn.valid #= true
    dut.probe.logic.loadCmdIn.payload.vaddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.paddr #= vaddr   // identity translation in this spec
    dut.probe.logic.loadCmdIn.payload.size #= size
    dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.WRITETHROUGH
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean && dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
    dut.probe.logic.loadRspOut.payload.data.toBigInt
  }

  def doStore(dut: Dut, cd: ClockDomain, paddr: Long, data: BigInt, size: SpinalEnumElement[Size.type]): Unit = {
    dut.probe.logic.storeIn.valid #= true
    dut.probe.logic.storeIn.payload.paddr #= paddr
    dut.probe.logic.storeIn.payload.data #= data
    dut.probe.logic.storeIn.payload.size #= size
    dut.probe.logic.storeIn.payload.useStrb #= false
    dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
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

  def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
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
}
