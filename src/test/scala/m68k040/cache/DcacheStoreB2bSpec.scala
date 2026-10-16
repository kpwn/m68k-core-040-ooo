package m68k040.cache

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.ls.BehavioralMemAgent
import m68k040.mmu.{DIdentityTranslationPlugin, MmuControlPlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

/** Directed coverage for `CPU_DCACHE_STORE_B2B` (`DcachePlugin.storeB2b`).
  *
  * Without the flag the non-blocking L1D drains a same-line COPYBACK store stream at one
  * store per THREE cycles: the younger store waits out the older one's S2 (`nbStoreHold`'s
  * same-set S2 term) and then its S3 write (`stS1SameLineAsS3`). With it, a same-line
  * COPYBACK store directly behind another advances back to back and takes the older
  * store's merged line through `stS2UsesS3Line`.
  *
  * Every test checks the CACHE ARRAY through a real CPUSH writeback (never a forwarded
  * load value), runs the identical stimulus on a flag-OFF DUT, and requires both arrays
  * to agree byte for byte with the reference. Shapes:
  *   - a full 16-byte line written one byte at a time, back to back (the b2b case);
  *   - the same under sustained probe demand (the store gets the port every other cycle);
  *   - two lines of ONE set interleaved (same set, different line: b2b must NOT fire);
  *   - a same-line run whose line is NOT resident (the first store misses and allocates;
  *     the rest must merge, never take a stale array line);
  *   - mixed sizes and offsets over a few lines, randomised. */
class DcacheStoreB2bSpec extends AnyFunSuite {

  class Dut(b2b: Boolean, d1: Boolean, ldLine: Boolean) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    private val b = DcacheTestBuild.Shipping
    require(b.nonBlocking, "DcacheStoreB2bSpec needs the non-blocking L1D (the flag requires it)")
    val dcache = new DcachePlugin(nonBlocking = b.nonBlocking, hotDoor = b.hotDoor,
      nbEarlyResponse = b.nbEarlyResponse, nbEagerAr = b.nbEagerAr,
      nbPreselectAr = b.nbPreselectAr, nbDynamicRelease = b.nbDynamicRelease,
      nbProbeMissStage = b.nbProbeMissStage, storeB2bFlag = b2b, storeD1FwdFlag = d1, storeLdLineFlag = ldLine)
    val probe  = new DcacheProbePlugin
    val mmuCtrl = new MmuControlPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe, mmuCtrl)) }
  }

  def simConfig = M68kSim().withVerilator
  /** ON = all three store-pipe flags (CPU_DCACHE_STORE_B2B / _D1FWD / _LDLINE). */
  lazy val onCompiled  = simConfig.compile(new Dut(b2b = true, d1 = true, ldLine = true))
  lazy val offCompiled = simConfig.compile(new Dut(b2b = false, d1 = false, ldLine = false))

  val SCOPE_LINE = 1
  val SEL_DC     = 1

  def memByte(addr: Long): Int = ((addr * 7 + 0x31) & 0xff).toInt
  def preload(mem: BehavioralMemAgent, base: Long, n: Int): Unit =
    for (i <- 0 until n) mem.pokeByte(base + i, memByte(base + i))

  def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    DcacheTestAxi.inOrderLoadDefaults(dut.probe.logic.loadCmdIn)
    val mem = m68k040.sim.HotDoorAttach(dut.dcache, cd, new BehavioralMemAgent(dut.dcache.logic.axi, cd))
    dut.probe.logic.loadCmdIn.valid #= false
    dut.probe.logic.loadProbeIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.payload.all #= false
    dut.probe.logic.storeIn.valid #= false
    dut.probe.logic.maintCmdIn.valid #= false
    dut.probe.logic.maintCmdIn.payload.push #= false
    dut.probe.logic.maintCmdIn.payload.invalidate #= false
    dut.probe.logic.maintCmdIn.payload.scope #= 0
    dut.probe.logic.maintCmdIn.payload.sel #= 0
    dut.probe.logic.maintCmdIn.payload.addr #= 0
    cd.waitSampling(4)
    cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)
    (cd, mem)
  }

  def load(dut: Dut, cd: ClockDomain, vaddr: Long): Unit = {
    dut.probe.logic.loadCmdIn.valid #= true
    dut.probe.logic.loadCmdIn.payload.vaddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.paddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
    dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
    dut.probe.logic.loadCmdIn.payload.token #= 0
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean &&
                         dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
  }

  /** One COPYBACK store of `size` at `paddr`; returns on its accept edge. `data` is the
    * big-endian value (its low `bytes` bytes are stored). */
  def fireStore(dut: Dut, cd: ClockDomain, paddr: Long, size: SpinalEnumElement[Size.type],
                data: Long): Unit = {
    dut.probe.logic.storeIn.valid #= true
    dut.probe.logic.storeIn.payload.paddr #= paddr
    dut.probe.logic.storeIn.payload.data #= BigInt(data & 0xFFFFFFFFL)
    dut.probe.logic.storeIn.payload.size #= size
    dut.probe.logic.storeIn.payload.useStrb #= false
    dut.probe.logic.storeIn.payload.strb #= 0
    dut.probe.logic.storeIn.payload.lineData #= 0
    dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
    dut.probe.logic.storeIn.payload.precise #= false
    cd.waitSamplingWhere(dut.probe.logic.storeIn.valid.toBoolean &&
                         dut.probe.logic.storeIn.ready.toBoolean)
    dut.probe.logic.storeIn.valid #= false
  }

  def pushLine(dut: Dut, cd: ClockDomain, addr: Long): Unit = {
    dut.probe.logic.maintCmdIn.valid #= true
    dut.probe.logic.maintCmdIn.payload.push #= true
    dut.probe.logic.maintCmdIn.payload.invalidate #= false
    dut.probe.logic.maintCmdIn.payload.scope #= SCOPE_LINE
    dut.probe.logic.maintCmdIn.payload.sel #= SEL_DC
    dut.probe.logic.maintCmdIn.payload.addr #= addr
    cd.waitSampling()
    dut.probe.logic.maintCmdIn.valid #= false
    var cyc = 0
    while (!dut.probe.logic.maintDoneOut.toBoolean && cyc < 40000) { cd.waitSampling(); cyc += 1 }
    assert(cyc < 40000, "CPUSH never completed")
    cd.waitSampling(4)
  }

  final class Trace {
    var cycles = 0; var b2bAdvances = 0; var fwd = 0; var s2Valid = 0; var d1Fwd = 0; var ldLineAdv = 0
    val s2Cycles = ArrayBuffer[(Int, Long)]()
  }
  def startTrace(dut: Dut, cd: ClockDomain): Trace = {
    val t = new Trace
    val l = dut.dcache.logic
    fork {
      while (true) {
        cd.waitSampling(); sleep(1)
        t.cycles += 1
        if (l.stB2bOk != null && l.stB2bOk.toBoolean && l.stS1Advance.toBoolean) t.b2bAdvances += 1
        if (l.stS2Valid.toBoolean) t.s2Cycles += ((t.cycles, l.stS2Payload.paddr.toLong))
        if (l.stS2Valid.toBoolean && l.stS2UsesS3Line.toBoolean) t.fwd += 1
        if (l.stS2UsesD1Line != null && l.stS2Valid.toBoolean && l.stS2UsesD1Line.toBoolean &&
            !l.stS2UsesS3Line.toBoolean) t.d1Fwd += 1
        if (l.ldS1LineEqStS1R != null && l.ldS1Valid.toBoolean && l.stS1Valid.toBoolean &&
            l.ldS1LineEqStS1R.toBoolean && l.stS1Advance.toBoolean) t.ldLineAdv += 1
      }
    }
    t
  }

  /** Sustained early-probe demand (see DcacheStorePortReservationSpec.startProbeDemand). */
  def startProbeDemand(dut: Dut, cd: ClockDomain): () => Unit = {
    var run = true
    fork {
      var tok = 0x60
      while (run) {
        dut.probe.logic.loadProbeIn.valid #= true
        dut.probe.logic.loadProbeIn.payload.vaddr #= 0x20000L + (tok & 0xF) * 0x110L
        dut.probe.logic.loadProbeIn.payload.token #= tok
        dut.probe.logic.loadProbeIn.payload.resolved #= false
        dut.probe.logic.loadProbeIn.payload.paddrHint #= 0
        dut.probe.logic.loadProbeIn.payload.size #= Size.LONG
        dut.probe.logic.loadProbeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
        dut.probe.logic.loadProbeIn.payload.needsLine #= false
        cd.waitSampling(); sleep(1)
        if (dut.probe.logic.loadProbeIn.ready.toBoolean) tok = 0x60 + ((tok - 0x60 + 1) % 8)
      }
      dut.probe.logic.loadProbeIn.valid #= false
    }
    () => {
      run = false
      cd.waitSampling(2)
      dut.probe.logic.loadProbeIn.valid #= false
      dut.probe.logic.loadProbeCancelIn.valid #= true
      dut.probe.logic.loadProbeCancelIn.payload.all #= true
      cd.waitSampling()
      dut.probe.logic.loadProbeCancelIn.valid #= false
      dut.probe.logic.loadProbeCancelIn.payload.all #= false
      cd.waitSampling(4)
    }
  }

  case class St(addr: Long, size: SpinalEnumElement[Size.type], data: Long)
  def bytesOf(s: St): Int = if (s.size == Size.BYTE) 1 else if (s.size == Size.WORD) 2 else 4

  /** Runs `stores` back to back (after making `resident` lines resident), pushes every
    * touched line and checks memory against a byte-level reference. Returns the trace and
    * the cycles from the first accept to the last S2. */
  def run(dut: Dut, resident: Seq[Long], cold: Seq[Long], stores: Seq[St],
          demand: Boolean): (Trace, Int) = {
    val (cd, mem) = initDut(dut)
    val lines = (resident ++ cold).distinct
    for (b <- lines) preload(mem, b, 16)
    for (b <- resident) load(dut, cd, b)
    DcacheTestAxi.awaitQuiet(dut.dcache, cd)
    val ref = scala.collection.mutable.Map[Long, Int]()
    for (b <- lines; i <- 0 until 16) ref(b + i) = memByte(b + i)
    for (s <- stores; i <- 0 until bytesOf(s))
      ref(s.addr + i) = ((s.data >> (8 * (bytesOf(s) - 1 - i))) & 0xFF).toInt
    val stop = if (demand) startProbeDemand(dut, cd) else () => ()
    val t = startTrace(dut, cd)
    val start = t.cycles
    for (s <- stores) fireStore(dut, cd, s.addr, s.size, s.data)
    cd.waitSampling(60)
    val span = t.s2Cycles.lastOption.map(_._1 - start).getOrElse(0)
    stop()
    DcacheTestAxi.awaitQuiet(dut.dcache, cd)
    for (b <- lines) pushLine(dut, cd, b)
    for (b <- lines; i <- 0 until 16)
      assert(mem.peekByte(b + i) == ref(b + i),
        f"ARRAY MISMATCH at ${b + i}%08x: memory ${mem.peekByte(b + i)}%02x expected ${ref(b + i)}%02x " +
        s"(b2b=${dut.dcache.storeB2b} demand=$demand b2bAdvances=${t.b2bAdvances} fwd=${t.fwd})")
    (t, span)
  }

  def both(name: String, resident: Seq[Long], cold: Seq[Long], stores: Seq[St], demand: Boolean,
           expectB2b: Option[Boolean]): Unit = {
    var offSpan = 0
    offCompiled.doSim(seed = 11) { dut => offSpan = run(dut, resident, cold, stores, demand)._2 }
    onCompiled.doSim(seed = 11) { dut =>
      val (t, span) = run(dut, resident, cold, stores, demand)
      info(s"$name: OFF span=$offSpan ON span=$span b2bAdvances=${t.b2bAdvances} fwd=${t.fwd} " +
        s"d1Fwd=${t.d1Fwd} ldLineAdv=${t.ldLineAdv}")
      expectB2b.foreach { e =>
        if (e) assert(t.b2bAdvances > 0 && t.fwd > 0 && span < offSpan,
          s"$name: the back-to-back path must fire and drain faster " +
          s"(b2bAdvances=${t.b2bAdvances} fwd=${t.fwd} span ON $span vs OFF $offSpan)")
        else assert(t.b2bAdvances == 0, s"$name: b2b must not fire here (b2bAdvances=${t.b2bAdvances})")
      }
    }
  }

  val lineA = 0x9000L
  test("full line, byte at a time, back to back: every byte lands", VerilatorTest) {
    both("byte-run", Seq(lineA), Nil,
      (0 until 16).map(i => St(lineA + i, Size.BYTE, 0xA0 + i)), demand = false, Some(true))
  }

  test("same-line run under sustained probe demand: every byte lands", VerilatorTest) {
    both("byte-run-demand", Seq(lineA), Nil,
      (0 until 16).map(i => St(lineA + i, Size.BYTE, 0x50 + i)), demand = true, None)
  }

  test("repeated stores to the SAME bytes keep program order (last writer wins)", VerilatorTest) {
    both("overwrite", Seq(lineA), Nil,
      (0 until 12).map(i => St(lineA + 4, Size.LONG, 0x11111111L * (i + 1))) ++
        Seq(St(lineA + 5, Size.BYTE, 0xEE), St(lineA + 4, Size.WORD, 0xBEEF)),
      demand = false, Some(true))
  }

  // 8 KiB / 4 ways / 16 B: lines 0x800 apart share a set.
  val lineB = lineA + 0x800L
  test("two lines of ONE set interleaved: same set, different line, b2b stays off", VerilatorTest) {
    both("same-set-interleave", Seq(lineA, lineB), Nil,
      (0 until 8).flatMap(i => Seq(St(lineA + i, Size.BYTE, 0x30 + i), St(lineB + i, Size.BYTE, 0x70 + i))),
      demand = false, Some(false))
  }

  test("same-line run into a NON-resident line: miss, allocate, merge", VerilatorTest) {
    both("cold-run", Nil, Seq(lineA + 0x40),
      (0 until 16).map(i => St(lineA + 0x40 + i, Size.BYTE, 0xC0 + i)), demand = false, None)
  }

  test("pairs of same-line runs across lines and sets, mixed sizes (randomised)", VerilatorTest) {
    val rng = new scala.util.Random(0xB2B)
    val lines = Seq(lineA, lineA + 0x10, lineB, lineA + 0x20)
    val stores = (0 until 64).map { _ =>
      val l = lines(rng.nextInt(if (rng.nextInt(4) == 0) lines.size else 2))
      rng.nextInt(3) match {
        case 0 => St(l + rng.nextInt(16), Size.BYTE, rng.nextInt(256))
        case 1 => St(l + 2 * rng.nextInt(8), Size.WORD, rng.nextInt(65536))
        case _ => St(l + 4 * rng.nextInt(4), Size.LONG, rng.nextLong() & 0xFFFFFFFFL)
      }
    }
    both("random", lines.take(3), Seq(lineA + 0x20), stores, demand = false, None)
    both("random-demand", lines.take(3), Seq(lineA + 0x20), stores, demand = true, None)
  }

  /** Stores to bytes 0..7 of a line interleaved with loads of bytes 8..15 of the SAME line
    * (the `same-line-copyback` kernel shape): exercises the same-line load relaxation and
    * the D1 forward. Loaded bytes are never stored, so every load must return memory's
    * value; the array is checked through CPUSH as everywhere else. */
  def interleaved(dut: Dut, base: Long, n: Int): Trace = {
    val (cd, mem) = initDut(dut)
    preload(mem, base, 16)
    load(dut, cd, base)
    DcacheTestAxi.awaitQuiet(dut.dcache, cd)
    val t = startTrace(dut, cd)
    var loadsDone = 0
    val ldr = fork {
      for (i <- 0 until n) {
        val off = 8 + 4 * (i % 2)
        dut.probe.logic.loadCmdIn.valid #= true
        dut.probe.logic.loadCmdIn.payload.vaddr #= base + off
        dut.probe.logic.loadCmdIn.payload.paddr #= base + off
        dut.probe.logic.loadCmdIn.payload.size #= Size.LONG
        dut.probe.logic.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
        dut.probe.logic.loadCmdIn.payload.token #= 0
        cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean &&
                             dut.probe.logic.loadCmdIn.valid.toBoolean)
        dut.probe.logic.loadCmdIn.valid #= false
        cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
        val got = dut.probe.logic.loadRspOut.payload.data.toBigInt.toLong & 0xFFFFFFFFL
        val exp = (0 until 4).foldLeft(0L)((a, k) => (a << 8) | memByte(base + off + k))
        assert(got == exp, f"load +$off returned $got%08x expected $exp%08x")
        loadsDone += 1
      }
    }
    for (i <- 0 until n) fireStore(dut, cd, base + (i % 8), Size.BYTE, 0x40 + i)
    ldr.join()
    cd.waitSampling(60)
    DcacheTestAxi.awaitQuiet(dut.dcache, cd)
    pushLine(dut, cd, base)
    val last = (0 until 8).map(b => (b, (0 until n).filter(_ % 8 == b).lastOption))
    for ((b, li) <- last; i <- li)
      assert(mem.peekByte(base + b) == 0x40 + i,
        f"ARRAY MISMATCH at +$b: ${mem.peekByte(base + b)}%02x expected ${0x40 + i}%02x")
    for (b <- 8 until 16) assert(mem.peekByte(base + b) == memByte(base + b))
    assert(loadsDone == n)
    t
  }

  test("same-line stores interleaved with same-line loads: array and load data exact", VerilatorTest) {
    var offCyc = 0
    offCompiled.doSim(seed = 5) { dut => offCyc = interleaved(dut, 0x9300L, 32).cycles }
    onCompiled.doSim(seed = 5) { dut =>
      val t = interleaved(dut, 0x9300L, 32)
      info(s"interleaved: b2bAdvances=${t.b2bAdvances} fwd=${t.fwd} d1Fwd=${t.d1Fwd} ldLineAdv=${t.ldLineAdv}")
    }
  }
}
