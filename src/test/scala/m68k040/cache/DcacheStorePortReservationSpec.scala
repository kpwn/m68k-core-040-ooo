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

/** Directed coverage for the 2026-09-17 shared-array-port RESERVATION cut
  * (`loadPortReserved`, DcachePlugin).
  *
  * The cut replaces the store S1->S2 arbiter's live `fsm.loadUsesPort` (which
  * carried `loadProbePort.valid` and therefore the ROB head pointer, ~24
  * combinational levels across two plugins) with a register-rooted SUPERSET. It is
  * strictly more restrictive, so it cannot create a port conflict -- but it DOES
  * change the cycle distance between two stores at S2, and that distance is exactly
  * what the same-line forward `stS2UsesS3Line` and the read-launch hold
  * `stS1SameLineAsS3` are calibrated for. `stS2UsesS3Line`'s own comment records
  * that it covers only a ONE-cycle S2-to-S2 gap and that the TWO-cycle case was
  * found LIVE via `pea_4x_cache_evict_once.s` -- a silently wrong byte in the array,
  * not a hang. This spec is the falsifiable form of the claim that the extra stall
  * cannot reach that hole.
  *
  * The structural argument (see `loadPortReserved`'s own comment) is that the store
  * pipe is in-order and one descriptor deep per stage with an UNGATED one-cycle
  * S2->S3, so an older store's array write cycle `w` and a younger same-line store's
  * S1-advance cycle `c` always satisfy `w <= c + 1`, leaving only the three cases
  * the two patches cover; stalling only pushes `c` later, i.e. towards the safest
  * one. These tests drive both reachable gap regimes on purpose and check the CACHE
  * ARRAY, via a CPUSH writeback into memory, rather than a forwarded load value.
  */
class DcacheStorePortReservationSpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin()
    val probe  = new DcacheProbePlugin
    val mmuCtrl = new MmuControlPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe, mmuCtrl)) }
  }

  def simConfig = M68kSim().withVerilator
  lazy val sharedCompiled = simConfig.compile(new Dut)

  val SCOPE_LINE = 1
  val SEL_DC     = 1

  def memByte(addr: Long): Int = ((addr * 5 + 0x23) & 0xff).toInt
  def preload(mem: BehavioralMemAgent, base: Long, n: Int): Unit =
    for (i <- 0 until n) mem.pokeByte(base + i, memByte(base + i))

  def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
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

  def load(dut: Dut, cd: ClockDomain, vaddr: Long, size: SpinalEnumElement[Size.type],
           cacheMode: SpinalEnumElement[CacheMode.type]): BigInt = {
    dut.probe.logic.loadCmdIn.valid #= true
    dut.probe.logic.loadCmdIn.payload.vaddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.paddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.size #= size
    dut.probe.logic.loadCmdIn.payload.cacheMode #= cacheMode
    dut.probe.logic.loadCmdIn.payload.token #= 0
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean &&
                         dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    cd.waitSamplingWhere(dut.probe.logic.loadRspOut.valid.toBoolean)
    dut.probe.logic.loadRspOut.payload.data.toBigInt
  }

  /** Present one byte store and return on its accept edge. */
  def fireByteStore(dut: Dut, cd: ClockDomain, paddr: Long, data: Int): Unit = {
    dut.probe.logic.storeIn.valid #= true
    dut.probe.logic.storeIn.payload.paddr #= paddr
    dut.probe.logic.storeIn.payload.data #= BigInt(data & 0xFF)
    dut.probe.logic.storeIn.payload.size #= Size.BYTE
    dut.probe.logic.storeIn.payload.useStrb #= false
    dut.probe.logic.storeIn.payload.strb #= 0
    dut.probe.logic.storeIn.payload.lineData #= 0
    dut.probe.logic.storeIn.payload.cacheMode #= CacheMode.COPYBACK
    dut.probe.logic.storeIn.payload.precise #= false
    cd.waitSamplingWhere(dut.probe.logic.storeIn.valid.toBoolean &&
                         dut.probe.logic.storeIn.ready.toBoolean)
    dut.probe.logic.storeIn.valid #= false
  }

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

  def maintWait(dut: Dut, cd: ClockDomain, budget: Int = 40000): Unit = {
    var cyc = 0; var done = false
    while (!done && cyc < budget) { cd.waitSampling(); cyc += 1
      if (dut.probe.logic.maintDoneOut.toBoolean) done = true }
    assert(done, s"maintenance walk never completed within $budget cycles")
  }

  /** Per-cycle store-pipe trace. `s2Cycles` is the cycle index of every S2-valid
    * cycle together with the descriptor's paddr, which is what the S2-to-S2 GAP is
    * measured from. */
  final class PipeTrace {
    val s2Cycles   = ArrayBuffer[(Int, Long)]()
    var gapOneFwd  = 0    // stS2UsesS3Line fired (the one-cycle-gap forward)
    var sameLineHold = 0  // stS1SameLineAsS3 fired (the two-cycle-gap hold)
    var advances   = 0
    var denials    = 0
    var cycles     = 0
    def gaps(line: Long, lineMask: Long): Seq[Int] = {
      val c: Seq[Int] = s2Cycles.filter { case (_, p) => (p & ~lineMask) == line }.map(_._1).toSeq
      c.zip(c.drop(1)).map { case (a, b) => b - a }
    }
  }

  def startTrace(dut: Dut, cd: ClockDomain): PipeTrace = {
    val t = new PipeTrace
    fork {
      while (true) {
        cd.waitSampling(); sleep(1)
        t.cycles += 1
        if (dut.dcache.logic.stS2Valid.toBoolean)
          t.s2Cycles += ((t.cycles, dut.dcache.logic.stS2Payload.paddr.toLong))
        // `stS2UsesS3Line` is NOT qualified by `stS2Valid` in the RTL (it is only
        // CONSUMED under `when(stS2Valid)`), and the stale `stS2Payload` register
        // trivially matches the S3 write it itself produced one cycle earlier. Count
        // only the cycles on which the forward is actually consumed.
        if (dut.dcache.logic.stS2Valid.toBoolean &&
            dut.dcache.logic.stS2UsesS3Line.toBoolean) t.gapOneFwd += 1
        if (dut.dcache.logic.stS1SameLineAsS3.toBoolean) t.sameLineHold += 1
        if (dut.dcache.logic.stS1Advance.toBoolean) t.advances += 1
        if (dut.dcache.logic.storeDeniedPort.toBoolean) t.denials += 1
      }
    }
    t
  }

  /** Hold the early-probe port's `valid` continuously with rotating tokens, i.e.
    * SUSTAINED PROBE DEMAND, which is the axis the store grant actually arbitrates
    * on: `storeClaimReg := stS1ValidNext && (!loadProbePort.valid || storeReadOwedNext)`.
    * With demand high the store is granted the port only when OWED -- one S1->S2
    * advance every other cycle. With demand low it is granted every cycle.
    *
    * The first `earlyProbeDepth` probes really launch and really take the read port
    * (the contention this arbiter exists for); after the queue fills, the credit
    * shuts and the remaining cycles are demand-only -- both halves of the contended
    * regime, in one stimulus. Returns a handle that stops the driver. */
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

  /** Store bytes `base+0 .. base+n-1` (one 16-byte LINE) with a controllable number
    * of idle cycles between successive producer accepts, then verify the CACHE ARRAY
    * by pushing the dirty line to memory and reading memory back. */
  def sameLineByteRun(dut: Dut, cd: ClockDomain, mem: BehavioralMemAgent,
                      base: Long, n: Int, producerGap: Int,
                      probeDemand: Boolean): PipeTrace = {
    preload(mem, base, 16)
    // Warm + allocate the line, then make it a COPYBACK resident so every store is
    // an on-chip RMW hit (no AXI, the exact shape of the four PEA stores).
    load(dut, cd, base, Size.LONG, CacheMode.COPYBACK)
    val stopDemand = if (probeDemand) startProbeDemand(dut, cd) else () => ()
    val t = startTrace(dut, cd)
    for (i <- 0 until n) {
      fireByteStore(dut, cd, base + i, 0xA0 + i)
      if (producerGap > 0) cd.waitSampling(producerGap)
    }
    // Drain the pipe.
    cd.waitSampling(40)
    stopDemand()
    // THE CHECK: not a forwarded load value -- the ARRAY, observed through a real
    // writeback into memory, exactly as a later eviction would observe it.
    maintPulse(dut, cd, push = true, invalidate = false, SCOPE_LINE, SEL_DC, base)
    maintWait(dut, cd)
    cd.waitSampling(4)
    for (i <- 0 until n)
      assert(mem.peekByte(base + i) == (0xA0 + i),
        f"ARRAY CORRUPTION at $base%08x+$i: expected ${0xA0 + i}%02x, " +
        f"line writeback holds ${mem.peekByte(base + i)}%02x " +
        s"(producerGap=$producerGap probeDemand=$probeDemand); " +
        s"S2 gaps=${t.gaps(base & ~0xFL, 0xFL).mkString(",")} " +
        s"stS2UsesS3Line=${t.gapOneFwd} stS1SameLineAsS3=${t.sameLineHold}")
    for (i <- n until 16)
      assert(mem.peekByte(base + i) == memByte(base + i),
        f"untouched line byte +$i was corrupted by the RMW merge")
    t
  }

  // ───────────────────────────────────────────────────────────────────────────
  // (1) The one-cycle S2-to-S2 gap -- the ONLY case `stS2UsesS3Line` covers. Under
  //     the store grant this is the UNCONTENDED regime: with no probe demand the
  //     store is granted the port every cycle and S2 gaps of 1 are reachable exactly
  //     as they were before the cut.
  // ───────────────────────────────────────────────────────────────────────────
  test("same-line COPYBACK RMW stream at a ONE-cycle S2 gap lands every byte in the array",
       VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val t = sameLineByteRun(dut, cd, mem, 0x9000L, 8, producerGap = 0, probeDemand = false)
      val g = t.gaps(0x9000L, 0xFL)
      assert(g.nonEmpty, "the run produced no measurable S2-to-S2 gaps at all")
      assert(g.contains(1) && t.gapOneFwd > 0,
        s"COVERAGE: this test exists to drive the one-cycle gap and the forward that " +
        s"patches it; observed gaps=${g.mkString(",")} consumed stS2UsesS3Line=${t.gapOneFwd}. " +
        "If the grant made gap 1 unreachable the forward is now dead code and that must " +
        "be REPORTED, not silently passed over")
      info(s"no-probe-demand run: S2 gaps=${g.mkString(",")} stS2UsesS3Line=${t.gapOneFwd} " +
           s"stS1SameLineAsS3=${t.sameLineHold} advances=${t.advances} denials=${t.denials}")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // (2) The CONTENDED regime -- sustained probe demand. The store is granted the
  //     port only when owed, so S2 gaps move to 2+ and `stS1SameLineAsS3` converts
  //     the dangerous 2 into a safe 3.
  // ───────────────────────────────────────────────────────────────────────────
  test("same-line COPYBACK RMW stream under sustained probe demand lands every byte in the array",
       VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val t = sameLineByteRun(dut, cd, mem, 0x9100L, 8, producerGap = 0, probeDemand = true)
      val g = t.gaps(0x9100L, 0xFL)
      assert(g.nonEmpty, "the run produced no measurable S2-to-S2 gaps at all")
      assert(t.denials > 0,
        "the store must actually have been denied the port at least once in this regime")
      assert(!g.contains(2),
        s"a TWO-cycle S2 gap must never be observable -- stS1SameLineAsS3 converts it to 3 " +
        s"(gaps=${g.mkString(",")})")
      info(s"probe-demand run: S2 gaps=${g.mkString(",")} stS2UsesS3Line=${t.gapOneFwd} " +
           s"stS1SameLineAsS3=${t.sameLineHold} advances=${t.advances} denials=${t.denials}")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // (3) The full producer-side gap sweep, in BOTH credit regimes. Every distance
  //     the new stall can produce between two same-line stores, checked against the
  //     array rather than against a forwarded value.
  // ───────────────────────────────────────────────────────────────────────────
  for (gap <- 0 to 6; demand <- Seq(true, false)) {
    test(s"same-line RMW gap sweep: producerGap=$gap probeDemand=$demand lands every byte",
         VerilatorTest) {
      sharedCompiled.doSim { dut =>
        val (cd, mem) = initDut(dut)
        val base = 0x9200L + (gap * 2 + (if (demand) 1 else 0)) * 0x40L
        val t = sameLineByteRun(dut, cd, mem, base, 6, producerGap = gap, probeDemand = demand)
        val g = t.gaps(base & ~0xFL, 0xFL)
        assert(!g.contains(2),
          s"a TWO-cycle S2 gap must never be observable (gaps=${g.mkString(",")})")
        info(s"gap=$gap demand=$demand S2 gaps=${g.mkString(",")} " +
             s"fwd=${t.gapOneFwd} hold=${t.sameLineHold}")
      }
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // (4) LIVENESS, both directions. Under SUSTAINED probe demand the store is granted
  //     the port only when owed, so the `storeDeniedPort -> storeReadOwed ->
  //     storeClaimReg` fairness loop is the ONLY thing that lets it advance at all --
  //     and no existing tripwire would catch its absence (the probe credit's own
  //     liveness assert watches a credit that never shuts here). This pins the bound.
  // ───────────────────────────────────────────────────────────────────────────
  test("a waiting store makes bounded progress under sustained probe demand", VerilatorTest) {
    sharedCompiled.doSim { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x9800L
      preload(mem, base, 16)
      load(dut, cd, base, Size.LONG, CacheMode.COPYBACK)
      val stopDemand = startProbeDemand(dut, cd)
      val t = startTrace(dut, cd)
      fireByteStore(dut, cd, base, 0x5A)
      var waited = 0
      while (!dut.dcache.logic.stS1Advance.toBoolean && waited < 64) {
        cd.waitSampling(); sleep(1); waited += 1
      }
      assert(waited < 64,
        s"a lone store never got the shared array port in 64 cycles under probe demand " +
        s"-- the storeDeniedPort -> storeReadOwed -> storeClaimReg fairness loop is broken")
      info(s"lone store waited $waited cycles for the port (denials=${t.denials})")
      cd.waitSampling(40)
      stopDemand()
      maintPulse(dut, cd, push = true, invalidate = false, SCOPE_LINE, SEL_DC, base)
      maintWait(dut, cd)
      cd.waitSampling(4)
      assert(mem.peekByte(base) == 0x5A, "the lone store must have landed in the array")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // (5) MEASUREMENT: the steady-state store drain rate in both regimes. This is the
  //     honest cost of the cut inside the D-cache, reported rather than asserted
  //     tightly.
  // ───────────────────────────────────────────────────────────────────────────
  for (demand <- Seq(true, false)) {
    test(s"MEASURE: steady-state COPYBACK store drain rate, probeDemand=$demand", VerilatorTest) {
      sharedCompiled.doSim { dut =>
        val (cd, mem) = initDut(dut)
        val n = 24
        // Distinct LINES in distinct sets so nothing else (same-line holds, way
        // pressure) interferes with the arbiter measurement.
        val bases = (0 until n).map(i => 0xA000L + i * 0x10L)
        for (b <- bases) { preload(mem, b, 16); load(dut, cd, b, Size.LONG, CacheMode.COPYBACK) }
        val stopDemand = if (demand) startProbeDemand(dut, cd) else () => ()
        val t = startTrace(dut, cd)
        val start = t.cycles
        for ((b, i) <- bases.zipWithIndex) fireByteStore(dut, cd, b, 0xC0 + i)
        val elapsed = t.cycles - start
        info(f"probeDemand=$demand: $n stores accepted in $elapsed cycles " +
             f"(${elapsed.toDouble / n}%.2f cycles/store, denials=${t.denials})")
        cd.waitSampling(40)
        // A resident probe-result queue keeps the cache from quiescing, so the
        // verification CPUSH below would never start. Release them first.
        stopDemand()
        for ((b, i) <- bases.zipWithIndex) {
          maintPulse(dut, cd, push = true, invalidate = false, SCOPE_LINE, SEL_DC, b)
          maintWait(dut, cd)
          cd.waitSampling(2)
          assert(mem.peekByte(b) == (0xC0 + i),
            f"store $i to $b%08x did not land in the array")
        }
      }
    }
  }
}
