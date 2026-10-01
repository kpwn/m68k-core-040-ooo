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

import scala.collection.mutable

/** NON-BLOCKING L1D (`DcachePlugin.nonBlocking`, design note
  * `docs/superpowers/specs/2026-09-30-dside-nonblocking-l1d-and-hot-door.md` section 14) -- a
  * randomized shadow-memory stress of the CACHE ALONE, driven hard at multiple misses,
  * secondary merges, store merges, full-line no-fill stores, D3-SET conflicts, WB-buffer
  * pressure and out-of-order refill responses.
  *
  * Adopted from the same-line-merge agent's `DcacheMissMergeSpec` (itself a port of
  * `DcacheSectorSpec` test 6, branch `fix/dcache-sectored-wedge`). Additions:
  *  - arms for the hot door (`axiDh`, sharing memory AND the model L2 with `axi`), for
  *    `axi` with the crossbar's one-outstanding rule, and for `axi` multi-outstanding with
  *    `Reordered` response order; the hot door also runs `Chaos` (newest first);
  *  - stores INSIDE load groups (an older store draining while loads wait, never
  *    overlapping an outstanding load's line -- the SQ/retirement contract), and 1-in-12
  *    FULL-LINE strobe stores (the noFill allocate);
  *  - INHIBITED traffic only after a drain (the D4 contract the cache relies on);
  *  - mechanism counters read from `nb.ctr` and ASSERTED non-trivial.
  * The CONTROL arm (legacy blocking cache) runs the identical op stream and must pass.
  */
class DcacheNonBlockingSpec extends AnyFunSuite {

  class ProbeResolveDriver extends FiberPlugin {
    val logic = during build new Area {
      val ds = host[DcacheService]
      val resolveIn = slave(Flow(DLoadProbeResolve()))
      ds.loadProbeResolve.valid   := resolveIn.valid
      ds.loadProbeResolve.payload := resolveIn.payload
    }
  }

  /** Built as `M68kSocketTop` builds the cache on the probe path
    * (`allowPretranslatedProbeHints = false`). */
  class Dut(val nb: Boolean, val dh: Boolean, val early: Boolean, val eagerAr: Boolean,
            val preselectAr: Boolean, val storeDelay: Int) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param   = new ParamPlugin(M68kParams())
    val xlate   = new DIdentityTranslationPlugin
    val dcache  = new DcachePlugin(sectored = false, fillForward = false,
                                   directRefillResponse = m68k040.top.ShippingCoreConfig.dcacheDirectRefillResponse,
                                   allowPretranslatedProbeHints = false, hitUnderMissRead = false,
                                   nonBlocking = nb, nMshr = 4, hotDoor = dh, storeAllocArDelay = storeDelay,
                                   nbEarlyResponse = nb && early, nbEagerAr = eagerAr,
                                   nbPreselectAr = preselectAr)
    val probe   = new DcacheProbePlugin
    val mmuCtrl = new MmuControlPlugin
    val resolve = new ProbeResolveDriver
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe, mmuCtrl, resolve)) }
  }

  private val earlyEnv = m68k040.top.ShippingCoreConfig.dcacheNbEarlyResponse
  private val eagerEnv = sys.env.get("CPU_DCACHE_NB_EAGER_AR").contains("1")
  private val preselectEnv = m68k040.top.ShippingCoreConfig.dcacheNbPreselectAr
  private lazy val controlDut = M68kSim().withVerilator.compile(new Dut(nb = false, dh = false, early = false, eagerAr = false, preselectAr = false, storeDelay = 0))
  private lazy val nbColdDut  = M68kSim().withVerilator.compile(new Dut(nb = true, dh = false, early = earlyEnv, eagerAr = eagerEnv, preselectAr = preselectEnv, storeDelay = 0))
  private lazy val nbHotDut   = M68kSim().withVerilator.compile(new Dut(nb = true, dh = true, early = earlyEnv, eagerAr = eagerEnv, preselectAr = preselectEnv, storeDelay = 0))
  private lazy val nbHotEagerDut = M68kSim().withVerilator.compile(new Dut(nb = true, dh = true, early = false, eagerAr = true, preselectAr = false, storeDelay = 0))
  private lazy val nbHotPreselectDut = M68kSim().withVerilator.compile(new Dut(nb = true, dh = true, early = false, eagerAr = true, preselectAr = true, storeDelay = 0))
  private lazy val nbHotLegacyArDut = M68kSim().withVerilator.compile(new Dut(nb = true, dh = true, early = false, eagerAr = false, preselectAr = false, storeDelay = 0))
  private lazy val nbHotEagerStoreDelayDut = M68kSim().withVerilator.compile(new Dut(nb = true, dh = true, early = false, eagerAr = true, preselectAr = false, storeDelay = 3))
  private lazy val nbHotPreselectStoreDelayDut = M68kSim().withVerilator.compile(new Dut(nb = true, dh = true, early = false, eagerAr = true, preselectAr = true, storeDelay = 3))

  private val LINE = 16

  private def maint(dut: Dut, cd: ClockDomain, push: Boolean, invalidate: Boolean,
                    scope: Int, addr: Long, budget: Int = 40000): Unit = {
    val pl = dut.probe.logic
    pl.maintCmdIn.valid #= true
    pl.maintCmdIn.payload.push #= push
    pl.maintCmdIn.payload.invalidate #= invalidate
    pl.maintCmdIn.payload.scope #= scope
    pl.maintCmdIn.payload.sel #= 1
    pl.maintCmdIn.payload.addr #= addr
    cd.waitSampling()
    pl.maintCmdIn.valid #= false
    var n = 0
    var done = false
    while (!done && n < budget) { cd.waitSampling(); n += 1; done = pl.maintDoneOut.toBoolean }
    assert(done, s"cache-maintenance walk never pulsed maintDone within $budget cycles")
    cd.waitSampling(8)
  }

  case class Stats(var loads: Long = 0, var stores: Long = 0, var maints: Long = 0,
                   var groups: Long = 0, var faultLoads: Long = 0, var squashed: Long = 0,
                   var merges: Long = 0, var shadowMerges: Long = 0, var waiterRsp: Long = 0,
                   var waiterFaultRsp: Long = 0, var maxWaiters: Int = 0,
                   var probeServed: Long = 0, var flushWithWaiters: Long = 0)

  /** `coldMulti`: `axi` may have several reads outstanding (a standalone multi-ID slave);
    * false = the crossbar's one-outstanding rule. `mode`: R ordering across IDs. */
  private def shadowStress(dut: Dut, seed: Long, ops: Int, coldMulti: Boolean,
                           mode: m68k040.sim.AxiRspMode.Value): Stats = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    val lat = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 5, dramCycles = 24)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd,
      dcfg = m68k040.sim.AxiMemModelConfig(latency = lat, crossbarSingleOutstanding = !coldMulti,
        rspMode = if (coldMulti) mode else m68k040.sim.AxiRspMode.InOrder, injectBusErrors = true))
    val hot = if (!dut.dh) null else m68k040.sim.AxiMemModel.attachReadOnly(dut.dcache.logic.axiDh, cd,
      m68k040.sim.AxiMemModelConfig(latency = lat, rspMode = mode, injectBusErrors = true),
      sharedMem = mem.mem, sharedL2From = mem.model)
    val pl = dut.probe.logic
    val lg = dut.dcache.logic
    val rp = dut.resolve.logic.resolveIn
    pl.loadCmdIn.valid #= false
    pl.loadProbeIn.valid #= false
    pl.loadProbeCancelIn.valid #= false
    pl.loadProbeCancelIn.payload.all #= false
    pl.loadProbeCancelIn.payload.token #= 0
    pl.storeIn.valid #= false
    pl.maintCmdIn.valid #= false
    pl.maintCmdIn.payload.push #= false
    pl.maintCmdIn.payload.invalidate #= false
    pl.maintCmdIn.payload.scope #= 0
    pl.maintCmdIn.payload.sel #= 0
    pl.maintCmdIn.payload.addr #= 0
    rp.valid #= false
    cd.waitSampling(4)
    cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)

    val st = Stats()
    val rnd  = new scala.util.Random(seed)
    val base = 0x90000L
    val nTag = 6; val nSet = 2
    // 16-byte lines, 128 sets: a 2048-byte stride lands every tag on the same set.
    def lineBase(s: Int, tag: Int): Long = base + tag * 2048L + s * 64L
    val region: Seq[Long] =
      for (t <- 0 until nTag; s <- 0 until nSet; b <- 0 until LINE) yield lineBase(s, t) + b
    def pageMode(a: Long): SpinalEnumElement[CacheMode.type] =
      if (((a - base) / 2048L) == nTag - 1) CacheMode.WRITETHROUGH else CacheMode.COPYBACK
    def modeOf(a: Long): SpinalEnumElement[CacheMode.type] =
      if (rnd.nextInt(4) == 0) CacheMode.WRITETHROUGH else pageMode(a)
    val shadow = mutable.Map[Long, Int]()
    for (a <- region) { val v = rnd.nextInt(256); mem.pokeByte(a, v); shadow(a) = v }
    // An UNDECODED window (AxiMemModel.decoded: top nibble 1 is not mapped): DECERR.
    val faultBase = 0x10090000L

    // ── store ack contract (see DcacheSectorSpec) ──
    val pending = mutable.Set[Long]()
    val unacked = mutable.Queue[Seq[Long]]()
    fork {
      while (true) {
        cd.waitSampling()
        if (lg.storeAckReg.toBoolean && unacked.nonEmpty) {
          val bytes = unacked.dequeue()
          bytes.foreach(b => if (!unacked.exists(_.contains(b))) pending -= b)
        }
      }
    }
    // ── response monitor: every response, attributed by token ──
    case class Rsp(data: BigInt, line: BigInt, fault: Boolean)
    val rsps = mutable.Map[Int, mutable.ArrayBuffer[Rsp]]()
    val outstanding = mutable.Set[Int]()
    fork {
      while (true) {
        cd.waitSampling()
        if (pl.loadRspOut.valid.toBoolean) {
          val t = pl.loadRspOut.payload.token.toInt
          assert(outstanding.contains(t),
            s"seed $seed: a load response for token $t, which no accepted command is waiting on " +
            "(duplicate or spurious response)")
          rsps.getOrElseUpdate(t, mutable.ArrayBuffer()) +=
            Rsp(pl.loadRspOut.payload.data.toBigInt, pl.loadRspOut.payload.line.toBigInt,
                pl.loadRspOut.payload.fault.toBoolean)
          outstanding -= t
        }
      }
    }
    fork {
      while (true) {
        cd.waitSampling()
        if (pl.loadCmdIn.valid.toBoolean && pl.loadCmdIn.ready.toBoolean && lg.useEarlyProbe.toBoolean)
          st.probeServed += 1
      }
    }

    def idle: Boolean = lg.dcIdleForMaint.toBoolean
    def drain(): Unit = {
      var n = 0
      cd.waitSampling()
      while (!idle && n < 4000) { cd.waitSampling(); n += 1 }
      assert(idle, s"seed $seed: the D-cache never drained (4000 cycles)")
      assert(unacked.isEmpty, s"seed $seed: idle but ${unacked.size} store(s) never acked")
      assert(outstanding.isEmpty, s"seed $seed: idle but loads $outstanding never answered")
      pending.clear()
    }
    def waitAcked(a: Long, n: Int): Unit = {
      var w = 0
      while ((0 until n).exists(k => pending.contains(a + k)) && w < 4000) { cd.waitSampling(); w += 1 }
      assert(w < 4000, s"seed $seed: a store to 0x${a.toHexString} was never acked")
    }
    def nBytes(sz: Int) = sz match { case 0 => 1; case 1 => 2; case _ => 4 }
    def sizeEl(sz: Int) = sz match { case 0 => Size.BYTE; case 1 => Size.WORD; case _ => Size.LONG }
    def randLine(): Long = lineBase(rnd.nextInt(nSet), rnd.nextInt(nTag))
    def inLine(ln: Long, sz: Int): Long = ln + rnd.nextInt(LINE - nBytes(sz) + 1)
    var tok = 0
    def nextTok(): Int = {
      do { tok = (tok + 1) % (1 << DLoadToken.Width) } while (outstanding.contains(tok))
      tok
    }

    def doStore(avoid: Long => Boolean = _ => false): Unit = {
      val sz = rnd.nextInt(3); val n = nBytes(sz)
      var a = inLine(randLine(), sz)
      var tries = 0
      while (avoid(a & ~0xFL) && tries < 20) { a = inLine(randLine(), sz); tries += 1 }
      if (tries >= 20) return
      val v = BigInt(32, rnd) & ((BigInt(1) << (8 * n)) - 1)
      val fullLine = rnd.nextInt(12) == 0
      val strobeStore = fullLine || rnd.nextInt(8) == 0
      val sBase = a & ~0xFL
      var strb = 0; var line = BigInt(0)
      for (k <- 0 until n) {
        val off = ((a + k) - sBase).toInt
        strb |= 1 << off
        line |= ((v >> (8 * (n - 1 - k))) & 0xff) << (8 * off)
      }
      val fullBytes = if (fullLine) (0 until 16).map(_ => rnd.nextInt(256)) else Nil
      if (fullLine) { strb = 0xFFFF; line = fullBytes.zipWithIndex.map { case (b, o) => BigInt(b) << (8 * o) }.sum }
      val storeMode = if (fullLine) pageMode(a) else modeOf(a)
      pl.storeIn.valid #= true
      pl.storeIn.payload.paddr #= a
      pl.storeIn.payload.data #= v
      pl.storeIn.payload.size #= sizeEl(sz)
      pl.storeIn.payload.useStrb #= strobeStore
      pl.storeIn.payload.strb #= (if (strobeStore) strb else 0)
      pl.storeIn.payload.lineData #= (if (strobeStore) line else BigInt(0))
      pl.storeIn.payload.cacheMode #= storeMode
      pl.storeIn.payload.precise #= (rnd.nextInt(6) == 0)
      var w = 0
      cd.waitSampling()
      while (!pl.storeIn.ready.toBoolean && w < 4000) { cd.waitSampling(); w += 1 }
      assert(w < 4000, s"seed $seed: store port never accepted 0x${a.toHexString}")
      pl.storeIn.valid #= false
      if (fullLine) {
        for (o <- 0 until 16) { shadow(sBase + o) = fullBytes(o); pending += sBase + o }
        unacked.enqueue((0 until 16).map(sBase + _))
      } else {
        for (k <- 0 until n) {
          shadow(a + k) = ((v >> (8 * (n - 1 - k))) & 0xff).toInt
          pending += a + k
        }
        unacked.enqueue((0 until n).map(a + _))
      }
      st.stores += 1
    }

    /** One LOAD GROUP: up to four loads, back to back, mostly on one line. */
    def doGroup(faulting: Boolean): Unit = {
      val k = 1 + rnd.nextInt(4)
      val first = if (faulting) faultBase + rnd.nextInt(4) * 2048L else randLine()
      case class Ld(a: Long, sz: Int, tok: Int, lineOnly: Boolean, mode: SpinalEnumElement[CacheMode.type],
                    fault: Boolean, var probed: Boolean = false, var dropped: Boolean = false)
      val lds = (0 until k).map { i =>
        val sameLine = i == 0 || rnd.nextInt(4) != 0
        val ln = if (sameLine) first else if (faulting && rnd.nextBoolean()) faultBase + 64 else randLine()
        val sz = rnd.nextInt(3)
        val a = inLine(ln, sz)
        val isFault = (a & 0xF0000000L) == 0x10000000L
        // `lineOnly` (walker-shaped, never ooOk) only as a group's FIRST access: the
        // walkers are single-outstanding, they never have a younger load behind them.
        val lineOnly = i == 0 && !isFault && rnd.nextInt(6) == 0
        val mode = if (isFault) CacheMode.COPYBACK else modeOf(a)
        Ld(a, sz, -1, lineOnly, mode, isFault)
      }
      // The SQ contract: a load never reaches the cache past an un-acked overlapping store.
      for (l <- lds if !l.fault) {
        val n = if (l.lineOnly) 16 else nBytes(l.sz)
        val a0 = if (l.lineOnly) l.a & ~0xFL else l.a
        if ((0 until n).exists(j => pending.contains(a0 + j))) waitAcked(a0, n)
      }
      val withToks = lds.map(l => l.copy(tok = nextTok()))
      // ── probes (P2): no paddr at launch, resolved 0-2 cycles later ──
      for (l <- withToks if rnd.nextInt(8) != 0) {
        pl.loadProbeIn.valid #= true
        pl.loadProbeIn.payload.vaddr #= l.a
        pl.loadProbeIn.payload.token #= l.tok
        pl.loadProbeIn.payload.resolved #= false
        pl.loadProbeIn.payload.paddrHint #= 0
        pl.loadProbeIn.payload.size #= sizeEl(l.sz)
        pl.loadProbeIn.payload.cacheMode #= l.mode
        pl.loadProbeIn.payload.needsLine #= l.lineOnly
        var tries = 0; var ok = false
        while (!ok && tries < 3) { cd.waitSampling(); ok = pl.loadProbeIn.ready.toBoolean; tries += 1 }
        pl.loadProbeIn.valid #= false
        if (ok) {
          l.probed = true
          cd.waitSampling(rnd.nextInt(3))
          rp.valid #= true
          rp.payload.token #= l.tok
          rp.payload.paddr #= l.a
          rp.payload.cacheMode #= l.mode
          cd.waitSampling()
          rp.valid #= false
          // SQUASH of this one load before its command is sent: cancel its probe by token.
          if (rnd.nextInt(12) == 0) {
            pl.loadProbeCancelIn.valid #= true
            pl.loadProbeCancelIn.payload.all #= false
            pl.loadProbeCancelIn.payload.token #= l.tok
            cd.waitSampling()
            pl.loadProbeCancelIn.valid #= false
            l.dropped = true
            st.squashed += 1
          }
        }
      }
      // ── commands, back to back ──
      val flushAt = if (rnd.nextInt(10) == 0) rnd.nextInt(k) + 1 else k + 1
      val sent = mutable.ArrayBuffer[Ld]()
      for ((l, i) <- withToks.zipWithIndex if !l.dropped) {
        if (i == flushAt) {
          // A pipeline FLUSH: every probe is cancelled and nothing younger is sent. Commands
          // already accepted (possibly parked as merge waiters) must still be answered.
          pl.loadProbeCancelIn.valid #= true
          pl.loadProbeCancelIn.payload.all #= true
          cd.waitSampling()
          pl.loadProbeCancelIn.valid #= false
          pl.loadProbeCancelIn.payload.all #= false
          withToks.drop(i).foreach(_.dropped = true)
          st.squashed += withToks.drop(i).size
        }
        if (!l.dropped) {
          pl.loadCmdIn.valid #= true
          pl.loadCmdIn.payload.vaddr #= l.a
          pl.loadCmdIn.payload.paddr #= l.a
          pl.loadCmdIn.payload.size #= sizeEl(l.sz)
          pl.loadCmdIn.payload.cacheMode #= l.mode
          pl.loadCmdIn.payload.token #= l.tok
          pl.loadCmdIn.payload.lineOnly #= l.lineOnly
          pl.loadCmdIn.payload.ooOk #= !l.lineOnly
          pl.loadCmdIn.payload.rid #= i & 3
          pl.loadCmdIn.payload.ridValid #= !l.lineOnly
          var w = 0
          cd.waitSampling()
          while (!pl.loadCmdIn.ready.toBoolean && w < 4000) { cd.waitSampling(); w += 1 }
          assert(w < 4000, s"seed $seed: load port never accepted 0x${l.a.toHexString}")
          outstanding += l.tok
          sent += l
          pl.loadCmdIn.valid #= false
          // An OLDER store draining while these loads wait: never on a line an outstanding
          // load of this group touches (the SQ would have forwarded or stalled the load).
          if (rnd.nextInt(4) == 0) {
            val busyLines = withToks.filter(x => !x.dropped).map(_.a & ~0xFL).toSet
            doStore(b => busyLines.contains(b))
          }
        }
      }
      // ── every accepted command answered exactly once, with the right bytes ──
      var r = 0
      while (sent.exists(l => outstanding.contains(l.tok)) && r < 4000) { cd.waitSampling(); r += 1 }
      assert(r < 4000, s"seed $seed: no response for tokens " +
        sent.filter(l => outstanding.contains(l.tok)).map(l => f"${l.tok}@0x${l.a}%x").mkString(","))
      cd.waitSampling(2)   // a DUPLICATE response would land here and trip the monitor
      for (l <- sent) {
        val got = rsps.remove(l.tok).get
        assert(got.size == 1, s"seed $seed: token ${l.tok} answered ${got.size} times")
        val rsp = got.head
        if (l.fault) {
          assert(rsp.fault, f"seed $seed op#${st.loads}: load at UNDECODED 0x${l.a}%x came back " +
            "WITHOUT a bus fault -- a merged waiter skipped its primary's fault")
          st.faultLoads += 1
        } else {
          assert(!rsp.fault, f"seed $seed op#${st.loads}: load at 0x${l.a}%x came back FAULTED")
          val n = nBytes(l.sz)
          val want = (0 until n).foldLeft(BigInt(0))((acc, j) => (acc << 8) | shadow(l.a + j))
          if (l.lineOnly) {
            val sb = l.a & ~0xFL
            for (j <- 0 until 16) {
              val g = ((rsp.line >> (8 * j)) & 0xff).toInt
              assert(g == shadow(sb + j), f"seed $seed: lineOnly load 0x${l.a}%x byte $j = 0x$g%02x, " +
                f"shadow 0x${shadow(sb + j)}%02x")
            }
          } else {
            val gotV = rsp.data & ((BigInt(1) << (8 * n)) - 1)
            assert(gotV == want,
              f"seed $seed op#${st.loads}: ${n}-byte load at 0x${l.a}%x (group of $k) returned 0x$gotV%x, " +
              f"the shadow says 0x$want%x (DRAM " +
              (0 until n).map(j => f"${mem.peekByte(l.a + j) & 0xff}%02x").mkString + ")")
          }
        }
        st.loads += 1
      }
      st.groups += 1
    }

    def doMaint(): Unit = {
      drain()
      val ln = randLine()
      rnd.nextInt(8) match {
        case 0 | 1 | 2 => maint(dut, cd, push = true,  invalidate = true,  scope = 1, addr = ln)
        case 3         => maint(dut, cd, push = true,  invalidate = false, scope = 1, addr = ln)
        case 4         =>
          if (rnd.nextBoolean()) maint(dut, cd, push = true, invalidate = true, scope = 0, addr = 0)
          else maint(dut, cd, push = true, invalidate = rnd.nextBoolean(), scope = 2, addr = ln)
        case 5 | 6     =>
          maint(dut, cd, push = false, invalidate = true, scope = 1, addr = ln)
          for (b <- 0 until 16) shadow(ln + b) = mem.peekByte(ln + b) & 0xff
        case _         =>
          maint(dut, cd, push = false, invalidate = true, scope = 0, addr = 0)
          for (a <- region) shadow(a) = mem.peekByte(a) & 0xff
      }
      st.maints += 1
    }

    // INHIBITED device traffic: never merges, never allocates, rides the same REFILL.
    val ioBase = 0xA0000L
    val ioShadow = mutable.Map[Long, Int]()
    for (a <- ioBase until ioBase + 64) { val v = rnd.nextInt(256); mem.pokeByte(a, v); ioShadow(a) = v }
    def doIo(): Unit = {
      drain()   // D4: an inhibited access launches only on a quiet D-side bus
      val sz = rnd.nextInt(3); val n = nBytes(sz)
      val a = ioBase + rnd.nextInt(64 / n) * n
      if (rnd.nextBoolean()) {
        val v = BigInt(32, rnd) & ((BigInt(1) << (8 * n)) - 1)
        pl.storeIn.valid #= true
        pl.storeIn.payload.paddr #= a; pl.storeIn.payload.data #= v
        pl.storeIn.payload.size #= sizeEl(sz); pl.storeIn.payload.useStrb #= false
        pl.storeIn.payload.strb #= 0; pl.storeIn.payload.lineData #= 0
        pl.storeIn.payload.cacheMode #= CacheMode.INHIBITED
        pl.storeIn.payload.precise #= rnd.nextBoolean()
        cd.waitSampling()
        var w = 0
        while (!pl.storeIn.ready.toBoolean && w < 4000) { cd.waitSampling(); w += 1 }
        pl.storeIn.valid #= false
        for (j <- 0 until n) { ioShadow(a + j) = ((v >> (8 * (n - 1 - j))) & 0xff).toInt; pending += a + j }
        unacked.enqueue((0 until n).map(a + _))
        st.stores += 1
      } else {
        if ((0 until n).exists(j => pending.contains(a + j))) waitAcked(a, n)
        val t = nextTok()
        pl.loadCmdIn.valid #= true
        pl.loadCmdIn.payload.vaddr #= a; pl.loadCmdIn.payload.paddr #= a
        pl.loadCmdIn.payload.size #= sizeEl(sz)
        pl.loadCmdIn.payload.cacheMode #= CacheMode.INHIBITED
        pl.loadCmdIn.payload.token #= t; pl.loadCmdIn.payload.lineOnly #= false
        pl.loadCmdIn.payload.ooOk #= false; pl.loadCmdIn.payload.rid #= 0
        pl.loadCmdIn.payload.ridValid #= false
        cd.waitSampling()
        var w = 0
        while (!pl.loadCmdIn.ready.toBoolean && w < 4000) { cd.waitSampling(); w += 1 }
        outstanding += t
        pl.loadCmdIn.valid #= false
        var r = 0
        while (outstanding.contains(t) && r < 4000) { cd.waitSampling(); r += 1 }
        assert(r < 4000, s"seed $seed: no INHIBITED load response for 0x${a.toHexString}")
        val rsp = rsps.remove(t).get.head
        val got  = rsp.data & ((BigInt(1) << (8 * n)) - 1)
        val want = (0 until n).foldLeft(BigInt(0))((acc, j) => (acc << 8) | ioShadow(a + j))
        assert(got == want, f"seed $seed: INHIBITED ${n}-byte load at 0x$a%x returned 0x$got%x, want 0x$want%x")
        st.loads += 1
      }
    }

    for (_ <- 0 until ops) {
      val r = rnd.nextInt(100)
      if (r < 5) doIo()
      else if (r < 17) { val kk = 1 + rnd.nextInt(8); for (_ <- 0 until kk) doStore() }
      else if (r < 40) doStore()
      else if (r < 44) doGroup(faulting = true)
      else if (r < 97) doGroup(faulting = false)
      else doMaint()
    }
    drain()
    maint(dut, cd, push = true, invalidate = true, scope = 0, addr = 0)
    val bad = region.filter(a => (mem.peekByte(a) & 0xff) != shadow(a))
    assert(bad.isEmpty,
      f"seed $seed: after CPUSHA, ${bad.size} DRAM bytes disagree with the shadow; first at " +
      bad.take(4).map(a => f"0x$a%x(dram=${mem.peekByte(a) & 0xff}%02x shadow=${shadow(a)}%02x)").mkString(" "))
    if (dut.nb) {
      val c = lg.nb.ctrMap
      def v(k: String) = c(k).toBigInt.toLong
      for (k <- c.keys) nbTot(k) += v(k)
      val occ = c.keys.filter(_.startsWith("occ")).toSeq.sorted
      nbTot("occ2plus") += occ.drop(2).map(v).sum
      println(s"[nbStress dh=${dut.dh} seed=$seed] " +
        c.keys.filterNot(_.startsWith("occ")).map(k => s"$k=${v(k)}").mkString(" ") +
        s" occ=${occ.map(v).mkString("/")} " +
        f"MLP=${v("mlpSum").toDouble / scala.math.max(1L, v("mlpCyc"))}%.3f")
    }
    println(s"[nbStress nb=${dut.nb} dh=${dut.dh} seed=$seed] $st -- CLEAN")
    st
  }

  private val nbTot = mutable.Map[String, Long]().withDefaultValue(0L)

  private val seeds: Seq[Long] = sys.env.get("STRESS_SEEDS")
    .map(_.split(",").toSeq.map(_.trim.toLong)).getOrElse((1L to 8L).toSeq)
  private val ops = sys.env.get("STRESS_OPS").map(_.toInt).getOrElse(4000)

  private def runArm(c: => spinal.core.sim.SimCompiled[Dut], tag: String, nb: Boolean, coldMulti: Boolean,
                     mode: m68k040.sim.AxiRspMode.Value): Unit = {
    nbTot.clear()
    for (seed <- seeds) c.doSim(s"nb-stress-$tag-$seed", seed.toInt) { dut =>
      shadowStress(dut, seed, ops, coldMulti, mode) }
    if (nb) {
      println(s"[nbStress $tag TOTAL over ${seeds.size} seeds] " +
        nbTot.toSeq.sortBy(_._1).map { case (k, x) => s"$k=$x" }.mkString(" "))
      // A pass is worthless unless every mechanism fired.
      for (k <- Seq("primaryAllocs", "loadSecondaries", "storeAllocs", "noFillAllocs", "storeMerges",
                    "allocFail", "replays", "wbPushes", "occ2plus"))
        assert(nbTot(k) > 0, s"$tag: mechanism '$k' never fired: $nbTot")
    }
  }

  private def armOn(name: String): Boolean =
    sys.env.get("STRESS_ARMS").forall(_.split(",").map(_.trim).contains(name))

  test("eager and preselected AR each remove one allocation-to-AR cycle", VerilatorTest) {
    if (armOn("eagerArTiming")) {
      def measure(dut: SimCompiled[Dut], mode: String): (Int, Int) = {
        var allocGap = -1
        var cmdGap = -1
        dut.doSim(s"ar-timing-$mode") { d =>
          val cd = d.clockDomain
          cd.forkStimulus(10)
          val lg = d.dcache.logic
          val pl = d.probe.logic
          val cold = new BehavioralMemAgent(lg.axi, cd)
          m68k040.sim.AxiMemModel.attachReadOnly(lg.axiDh, cd,
            m68k040.sim.AxiMemModelConfig(
              latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 8, dramCycles = 8)),
            sharedMem = cold.mem, sharedL2From = cold.model)
          pl.loadCmdIn.valid #= false
          pl.loadProbeIn.valid #= false
          pl.loadProbeCancelIn.valid #= false
          pl.storeIn.valid #= false
          pl.maintCmdIn.valid #= false
          d.resolve.logic.resolveIn.valid #= false
          cd.waitSampling(4)
          cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)
          val x = 0xa6000L
          for (b <- 0 until 16) cold.pokeByte(x + b, 0x3c)
          var cycle = 0
          var cmdAccepted = -1
          var allocated = -1
          var advertised = -1
          var response: Option[BigInt] = None
          fork {
            while (true) {
              cd.waitSampling()
              cycle += 1
              if (cmdAccepted < 0 && lg.loadCmdPort.valid.toBoolean &&
                  lg.loadCmdPort.ready.toBoolean) cmdAccepted = cycle
              if (allocated < 0 && lg.nb.lAlloc.toBoolean) allocated = cycle
              if (advertised < 0 && lg.axiDh.ar.valid.toBoolean) {
                advertised = cycle
                assert(lg.axiDh.ar.payload.addr.toLong == x)
              }
              if (pl.loadRspOut.valid.toBoolean) response = Some(pl.loadRspOut.payload.data.toBigInt)
            }
          }
          pl.loadCmdIn.valid #= true
          pl.loadCmdIn.payload.vaddr #= x
          pl.loadCmdIn.payload.paddr #= x
          pl.loadCmdIn.payload.size #= Size.LONG
          pl.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
          pl.loadCmdIn.payload.token #= 1
          pl.loadCmdIn.payload.lineOnly #= false
          pl.loadCmdIn.payload.ooOk #= true
          pl.loadCmdIn.payload.rid #= 0
          pl.loadCmdIn.payload.ridValid #= true
          var acceptWait = 0
          while (!pl.loadCmdIn.ready.toBoolean && acceptWait < 500) {
            cd.waitSampling(); acceptWait += 1
          }
          assert(pl.loadCmdIn.ready.toBoolean, "timing load was not accepted")
          cd.waitSampling()
          pl.loadCmdIn.valid #= false
          var n = 0
          while (response.isEmpty && n < 500) { cd.waitSampling(); n += 1 }
          assert(response.exists(v => (v & BigInt("ffffffff", 16)) == BigInt("3c3c3c3c", 16)),
            s"mode=$mode returned $response")
          assert(cmdAccepted >= 0 && allocated > cmdAccepted && advertised > allocated,
            s"mode=$mode command=$cmdAccepted allocation=$allocated advertised=$advertised")
          allocGap = advertised - allocated
          cmdGap = advertised - cmdAccepted
          println(s"[nbArTiming] mode=$mode command-to-advertised-AR=$cmdGap " +
            s"allocation-to-advertised-AR=$allocGap cycles")
        }
        (allocGap, cmdGap)
      }
      val off = measure(nbHotLegacyArDut, "legacy")
      val eager = measure(nbHotEagerDut, "eager")
      val preselected = measure(nbHotPreselectDut, "preselected")
      assert(off._1 == 3 && eager._1 == 2 && preselected._1 == 1 &&
          off._2 == eager._2 + 1 && eager._2 == preselected._2 + 1,
        s"unexpected command/allocation-to-AR timing legacy=$off eager=$eager preselected=$preselected")
    }
  }

  test("eager and preselected AR hold a same-line WT through the pipeline and delayed B", VerilatorTest) {
    if (armOn("eagerArWtS3")) Seq(
      ("eager", nbHotEagerDut), ("preselected", nbHotPreselectDut)).foreach { case (mode, compiled) =>
      compiled.doSim(s"$mode-ar-wt-s3") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val lg = dut.dcache.logic
      val pl = dut.probe.logic
      val cold = new BehavioralMemAgent(lg.axi, cd,
        dcfg = m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 80, dramCycles = 80),
          crossbarSingleOutstanding = true, deferWriteVisibilityUntilB = true))
      m68k040.sim.AxiMemModel.attachReadOnly(lg.axiDh, cd,
        m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 5, dramCycles = 5)),
        sharedMem = cold.mem, sharedL2From = cold.model)
      pl.loadCmdIn.valid #= false
      pl.loadProbeIn.valid #= false
      pl.loadProbeCancelIn.valid #= false
      pl.storeIn.valid #= false
      pl.maintCmdIn.valid #= false
      dut.resolve.logic.resolveIn.valid #= false
      cd.waitSampling(4)
      cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)
      val x = 0xa8000L
      for (b <- 0 until 16) cold.pokeByte(x + b, 0x11)
      var s3WhileWaitAr = 0
      var s0AtAllocation = 0
      var firstWaitAr = -1
      var s1OnFirstWaitAr = 0
      var s2WhileWaitAr = 0
      var wtB = 0
      var arBeforeB = 0
      var arAdvertisedBeforeB = 0
      var hotAr = 0
      var response: Option[BigInt] = None
      var traceCycle = 0
      def waitingForLine: Boolean = (0 until 4).exists { k =>
        lg.nb.st(k).toInt == 1 && lg.nb.line(k).toLong == (x >>> 4)
      }
      fork {
        while (true) {
          cd.waitSampling()
          traceCycle += 1
          if (lg.nb.lAlloc.toBoolean && lg.s0Valid.toBoolean) s0AtAllocation += 1
          val lineWaiting = waitingForLine
          if (firstWaitAr < 0 && lineWaiting) firstWaitAr = traceCycle
          // This isolated DUT receives exactly one store, the same-line WT below.
          // Its stage payload fields are optimized out of the Verilator model.
          if (traceCycle == firstWaitAr && lg.stS1Valid.toBoolean) s1OnFirstWaitAr += 1
          if (lineWaiting && lg.stS2Valid.toBoolean) s2WhileWaitAr += 1
          if (lg.stS3Valid.toBoolean && lineWaiting) {
            s3WhileWaitAr += 1
          }
          if (wtB == 0 && lg.axiDh.ar.valid.toBoolean) arAdvertisedBeforeB += 1
          if (lg.axiDh.ar.valid.toBoolean && lg.axiDh.ar.ready.toBoolean) {
            hotAr += 1
            if (wtB == 0) arBeforeB += 1
          }
          if (lg.axi.b.valid.toBoolean && lg.axi.b.ready.toBoolean &&
              lg.axi.b.payload.id.toInt == AxiIds.D_STORE) wtB += 1
          if (pl.loadRspOut.valid.toBoolean) response = Some(pl.loadRspOut.payload.data.toBigInt)
        }
      }
      // The WT precedes the load. It is already in the pipeline when the
      // load allocates, so both selection modes must wait for its delayed B.
      pl.storeIn.payload.paddr #= x
      pl.storeIn.payload.data #= 0
      pl.storeIn.payload.size #= Size.LONG
      pl.storeIn.payload.useStrb #= true
      pl.storeIn.payload.strb #= 0xffff
      pl.storeIn.payload.lineData #= BigInt("66" * 16, 16)
      pl.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      pl.storeIn.payload.precise #= false
      pl.loadCmdIn.payload.vaddr #= x
      pl.loadCmdIn.payload.paddr #= x
      pl.loadCmdIn.payload.size #= Size.LONG
      pl.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
      pl.loadCmdIn.payload.token #= 1
      pl.loadCmdIn.payload.lineOnly #= false
      pl.loadCmdIn.payload.ooOk #= true
      pl.loadCmdIn.payload.rid #= 0
      pl.loadCmdIn.payload.ridValid #= true
      def acceptStore(): Unit = {
        pl.storeIn.valid #= true
        assert(pl.storeIn.ready.toBoolean, "WT store input was not ready")
        cd.waitSampling()
        pl.storeIn.valid #= false
      }
      def acceptLoad(): Unit = {
        pl.loadCmdIn.valid #= true
        assert(pl.loadCmdIn.ready.toBoolean, "load input was not ready")
        cd.waitSampling()
        pl.loadCmdIn.valid #= false
      }
      acceptStore()
      acceptLoad()
      var n = 0
      while (response.isEmpty && n < 1000) { cd.waitSampling(); n += 1 }
      println(s"[nbArWtTrace] mode=$mode allocS0=$s0AtAllocation firstWait=$firstWaitAr " +
        s"s1=$s1OnFirstWaitAr s2=$s2WhileWaitAr s3=$s3WhileWaitAr " +
        s"wtB=$wtB validBeforeB=$arAdvertisedBeforeB fireBeforeB=$arBeforeB " +
        s"hotAr=$hotAr response=$response")
      assert(s1OnFirstWaitAr > 0 && s2WhileWaitAr > 0 && s3WhileWaitAr > 0,
        s"$mode WT did not cross all AR gates: firstS1=$s1OnFirstWaitAr " +
        s"S2=$s2WhileWaitAr S3=$s3WhileWaitAr")
      assert(wtB == 1 && arBeforeB == 0 && arAdvertisedBeforeB == 0 && hotAr == 1,
        s"$mode AR crossed WT delayed B: wtB=$wtB fireBeforeB=$arBeforeB " +
        s"validBeforeB=$arAdvertisedBeforeB hotAr=$hotAr")
      assert(response.exists(v => (v & BigInt("ffffffff", 16)) == BigInt("66666666", 16)),
        s"$mode refill saw stale memory before WT B: $response")
      println(s"[nbArTiming] mode=$mode WT S0-at-allocation=$s0AtAllocation, " +
        "S1/S2/S3 gated AR; delayed B preceded checked refill")
      }
    }
  }

  test("eager and preselected AR retain store delay and full-line no-fill", VerilatorTest) {
    if (armOn("eagerArStoreDelay")) Seq(
      ("eager", nbHotEagerStoreDelayDut),
      ("preselected", nbHotPreselectStoreDelayDut)).foreach { case (mode, compiled) =>
      compiled.doSim(s"$mode-ar-store-delay") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val lg = dut.dcache.logic
      val pl = dut.probe.logic
      val cold = new BehavioralMemAgent(lg.axi, cd)
      m68k040.sim.AxiMemModel.attachReadOnly(lg.axiDh, cd,
        m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 8, dramCycles = 8)),
        sharedMem = cold.mem, sharedL2From = cold.model)
      pl.loadCmdIn.valid #= false
      pl.loadProbeIn.valid #= false
      pl.loadProbeCancelIn.valid #= false
      pl.storeIn.valid #= false
      pl.maintCmdIn.valid #= false
      dut.resolve.logic.resolveIn.valid #= false
      cd.waitSampling(4)
      cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)
      val full = 0xaa000L
      val partial = full + 16
      for (b <- 0 until 16) { cold.pokeByte(full + b, 0x11); cold.pokeByte(partial + b, 0x22) }
      var cycle = 0
      var fullAr = 0
      var partialArAt = -1
      var partialWaitAt = -1
      var rsp: Option[BigInt] = None
      fork {
        while (true) {
          cd.waitSampling()
          cycle += 1
          if (partialWaitAt < 0 && lg.nb.st.indices.exists(k =>
              lg.nb.st(k).toInt == 1 && lg.nb.line(k).toLong == (partial >>> 4)))
            partialWaitAt = cycle
          if (lg.axiDh.ar.valid.toBoolean) {
            val a = lg.axiDh.ar.payload.addr.toLong
            if (a == full) fullAr += 1
            if (a == partial && partialArAt < 0) partialArAt = cycle
          }
          if (pl.loadRspOut.valid.toBoolean) rsp = Some(pl.loadRspOut.payload.data.toBigInt)
        }
      }
      def until(label: String)(p: => Boolean): Unit = {
        var n = 0
        while (!p && n < 500) { cd.waitSampling(); n += 1 }
        assert(p, s"$label timed out")
      }
      def store(a: Long, byte: Int, strb: Int): Unit = {
        pl.storeIn.valid #= true
        pl.storeIn.payload.paddr #= a
        pl.storeIn.payload.data #= 0
        pl.storeIn.payload.size #= Size.LONG
        pl.storeIn.payload.useStrb #= true
        pl.storeIn.payload.strb #= strb
        pl.storeIn.payload.lineData #= (if (strb == 0xffff) BigInt("66" * 16, 16) else BigInt(byte))
        pl.storeIn.payload.cacheMode #= CacheMode.COPYBACK
        pl.storeIn.payload.precise #= false
        until("store accept") { pl.storeIn.ready.toBoolean }
        cd.waitSampling()
        pl.storeIn.valid #= false
        until("store ack") { lg.storeAckReg.toBoolean }
        until("store drain") { lg.dcIdleForMaint.toBoolean }
      }
      store(full, 0x66, 0xffff)
      assert(fullAr == 0, s"full-line no-fill store advertised $fullAr refill AR cycles")
      assert(lg.nb.ctrMap("noFillAllocs").toLong == 1,
        "full-line store did not allocate by the no-fill path")
      store(partial, 0x77, 1)
      assert(partialArAt >= 0, "partial store never advertised refill AR")
      assert(partialWaitAt >= 0 && partialArAt - partialWaitAt >= 4,
        s"store AR delay bypassed: WAIT_AR=$partialWaitAt advertised=$partialArAt")
      assert(lg.nb.ctrMap("hotAr").toLong == 1,
        "partial store did not issue exactly one hot refill")
      pl.loadCmdIn.valid #= true
      pl.loadCmdIn.payload.vaddr #= partial
      pl.loadCmdIn.payload.paddr #= partial
      pl.loadCmdIn.payload.size #= Size.LONG
      pl.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
      pl.loadCmdIn.payload.token #= 1
      pl.loadCmdIn.payload.lineOnly #= false
      pl.loadCmdIn.payload.ooOk #= true
      pl.loadCmdIn.payload.rid #= 0
      pl.loadCmdIn.payload.ridValid #= true
      until("load accept") { pl.loadCmdIn.ready.toBoolean }
      cd.waitSampling()
      pl.loadCmdIn.valid #= false
      until("load response") { rsp.nonEmpty }
      assert(rsp.exists(v => (v & BigInt("ffffffff", 16)) == BigInt("77222222", 16)),
        s"partial store returned wrong merged bytes: $rsp")
      println(s"[nbArTiming] mode=$mode storeDelay=3 noFillAR=$fullAr " +
        s"partialAR=$partialArAt checked=${rsp.get.toString(16)}")
      }
    }
  }

  test("eager and preselected AR handle adjacent dirty store and load victim writebacks", VerilatorTest) {
    if (armOn("eagerArAdjacentWb")) Seq(
      ("eager", nbHotEagerDut), ("preselected", nbHotPreselectDut)).foreach { case (mode, compiled) =>
      compiled.doSim(s"$mode-ar-adjacent-wb") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val lg = dut.dcache.logic
      val pl = dut.probe.logic
      val cold = new BehavioralMemAgent(lg.axi, cd,
        dcfg = m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 80, dramCycles = 80),
          crossbarSingleOutstanding = true, deferWriteVisibilityUntilB = true))
      m68k040.sim.AxiMemModel.attachReadOnly(lg.axiDh, cd,
        m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 5, dramCycles = 5)),
        sharedMem = cold.mem, sharedL2From = cold.model)
      pl.loadCmdIn.valid #= false
      pl.loadProbeIn.valid #= false
      pl.loadProbeCancelIn.valid #= false
      pl.storeIn.valid #= false
      pl.maintCmdIn.valid #= false
      dut.resolve.logic.resolveIn.valid #= false
      cd.waitSampling(4)
      cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)
      val base = 0xc0000L
      def addr(set: Int, tag: Int): Long = base + set * 16L + tag * 2048L
      var ackCount = 0
      var storeAccepts = 0
      var loadAccepts = 0
      var storesIssued = 0
      var loadsIssued = 0
      var traceCycle = 0
      var watchPair = false
      val pairEvents = mutable.ArrayBuffer.empty[String]
      var storeFirst = 0
      var loadFirst = 0
      var minPushGap = Int.MaxValue
      val responses = mutable.Map.empty[Int, BigInt]
      fork {
        while (true) {
          cd.waitSampling()
          traceCycle += 1
          if (watchPair && lg.nb.sWbPush.toBoolean) pairEvents += s"$traceCycle:S"
          if (watchPair && lg.nb.lWbPush.toBoolean) pairEvents += s"$traceCycle:L"
          if (pl.storeIn.valid.toBoolean && pl.storeIn.ready.toBoolean) storeAccepts += 1
          if (pl.loadCmdIn.valid.toBoolean && pl.loadCmdIn.ready.toBoolean) loadAccepts += 1
          if (lg.storeAckReg.toBoolean) ackCount += 1
          if (pl.loadRspOut.valid.toBoolean)
            responses(pl.loadRspOut.payload.token.toInt) = pl.loadRspOut.payload.data.toBigInt
        }
      }
      def until(label: String)(p: => Boolean): Unit = {
        var n = 0
        while (!p && n < 3000) { cd.waitSampling(); n += 1 }
        assert(p, s"$label timed out (ack=$ackCount wb=${lg.nb.wbCount.toInt} " +
          s"storeHold=${lg.nb.ctrMap("storeHold").toLong} " +
          s"storeAllocs=${lg.nb.ctrMap("storeAllocs").toLong} " +
          s"loadAllocs=${lg.nb.ctrMap("primaryAllocs").toLong} " +
          s"storeAccepts=$storeAccepts/$storesIssued loadAccepts=$loadAccepts/$loadsIssued)")
      }
      def driveStore(a: Long, byte: Int): Unit = {
        pl.storeIn.valid #= true
        pl.storeIn.payload.paddr #= a
        pl.storeIn.payload.data #= 0
        pl.storeIn.payload.size #= Size.LONG
        pl.storeIn.payload.useStrb #= true
        pl.storeIn.payload.strb #= 0xffff
        pl.storeIn.payload.lineData #= BigInt(f"$byte%02x" * 16, 16)
        pl.storeIn.payload.cacheMode #= CacheMode.COPYBACK
        pl.storeIn.payload.precise #= false
        cd.waitSamplingWhere(pl.storeIn.ready.toBoolean)
        sleep(1) // leave the sampled handshake visible to the independent monitor
        pl.storeIn.valid #= false
        storesIssued += 1
      }
      def driveLoad(a: Long, token: Int): Unit = {
        pl.loadCmdIn.valid #= true
        pl.loadCmdIn.payload.vaddr #= a
        pl.loadCmdIn.payload.paddr #= a
        pl.loadCmdIn.payload.size #= Size.LONG
        pl.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
        pl.loadCmdIn.payload.token #= token
        pl.loadCmdIn.payload.lineOnly #= false
        pl.loadCmdIn.payload.ooOk #= true
        pl.loadCmdIn.payload.rid #= (token & 3)
        pl.loadCmdIn.payload.ridValid #= true
        cd.waitSamplingWhere(pl.loadCmdIn.ready.toBoolean)
        sleep(1)
        pl.loadCmdIn.valid #= false
        loadsIssued += 1
      }
      for ((offset, i) <- (-7 to 7).zipWithIndex) {
        val storeSet = 2 * i
        val loadSet = storeSet + 1
        val loadByte = 0x80 + i
        for (b <- 0 until 16) cold.pokeByte(addr(loadSet, 4) + b, loadByte)
        // All four ways in each independent set are dirty. Both misses below
        // must evict a dirty line regardless of the replacement pointer.
        for (set <- Seq(storeSet, loadSet); tag <- 0 until 4) {
          val before = ackCount
          driveStore(addr(set, tag), 0x40 + tag)
          until("priming store ack") { ackCount > before }
          until("priming cache drain") { lg.dcIdleForMaint.toBoolean }
        }
        val beforeAck = ackCount
        val storeAddr = addr(storeSet, 4)
        val loadAddr = addr(loadSet, 4)
        val token = i + 1
        val wbBefore = lg.nb.ctrMap("wbPushes").toLong
        pairEvents.clear()
        watchPair = true
        println(s"[nbArWb $mode] offset=$offset start ack=$beforeAck dual=${lg.nb.ctrMap("dualWbPush").toLong}")
        if (offset <= 0) {
          driveLoad(loadAddr, token)
          cd.waitSampling(-offset)
          driveStore(storeAddr, 0x70 + i)
        } else {
          driveStore(storeAddr, 0x70 + i)
          cd.waitSampling(offset)
          driveLoad(loadAddr, token)
        }
        until(s"paired store ack offset=$offset") { ackCount > beforeAck }
        until(s"paired load response offset=$offset") { responses.contains(token) }
        val expected = BigInt(loadByte) * BigInt("01010101", 16)
        assert((responses(token) & BigInt("ffffffff", 16)) == expected,
          s"offset=$offset returned ${responses(token).toString(16)}, expected ${expected.toString(16)}")
        until("paired dirty WB drain") { lg.dcIdleForMaint.toBoolean }
        watchPair = false
        assert(storeAccepts == storesIssued && loadAccepts == loadsIssued,
          s"offset=$offset handshake mismatch store=$storeAccepts/$storesIssued load=$loadAccepts/$loadsIssued")
        val wbDelta = lg.nb.ctrMap("wbPushes").toLong - wbBefore
        assert(wbDelta == 2 && pairEvents.size == 2 &&
          pairEvents.count(_.endsWith(":S")) == 1 && pairEvents.count(_.endsWith(":L")) == 1,
          s"offset=$offset did not push both dirty victims: wbDelta=$wbDelta events=$pairEvents")
        val sAt = pairEvents.find(_.endsWith(":S")).get.split(":")(0).toInt
        val lAt = pairEvents.find(_.endsWith(":L")).get.split(":")(0).toInt
        if (sAt < lAt) storeFirst += 1 else if (lAt < sAt) loadFirst += 1
        minPushGap = scala.math.min(minPushGap, scala.math.abs(sAt - lAt))
        println(s"[nbArWb $mode] offset=$offset complete ack=$ackCount " +
          s"wbPushes=$wbDelta " +
          s"events=${pairEvents.mkString(",")} dual=${lg.nb.ctrMap("dualWbPush").toLong}")
      }
      val dual = lg.nb.ctrMap("dualWbPush").toLong
      assert(storeFirst > 0 && loadFirst > 0 && minPushGap == 1,
        s"phase sweep missed adjacent dirty victims: storeFirst=$storeFirst " +
        s"loadFirst=$loadFirst minGap=$minPushGap")
      println(s"[nbArWb $mode] adjacent dirty store/load WB pushes checked: " +
        s"storeFirst=$storeFirst loadFirst=$loadFirst minGap=$minPushGap dual=$dual")
      }
    }
  }

  test("inhibited translation releases its probe alongside an unrelated fault cancel", VerilatorTest) {
    if (armOn("inhibitedProbeCancel")) controlDut.doSim("inhibited-probe-cancel") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val pl = dut.probe.logic
      val rp = dut.resolve.logic.resolveIn
      pl.loadCmdIn.valid #= false
      pl.loadProbeIn.valid #= false
      pl.loadProbeCancelIn.valid #= false
      pl.storeIn.valid #= false
      pl.maintCmdIn.valid #= false
      rp.valid #= false
      cd.waitSampling(4)
      cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)

      def probe(token: Int, addr: Long): Unit = {
        pl.loadProbeIn.valid #= true
        pl.loadProbeIn.payload.vaddr #= addr
        pl.loadProbeIn.payload.token #= token
        pl.loadProbeIn.payload.resolved #= false
        pl.loadProbeIn.payload.paddrHint #= 0
        pl.loadProbeIn.payload.size #= Size.LONG
        pl.loadProbeIn.payload.cacheMode #= CacheMode.INHIBITED
        pl.loadProbeIn.payload.needsLine #= false
        cd.waitSamplingWhere(pl.loadProbeIn.ready.toBoolean)
        pl.loadProbeIn.valid #= false
      }
      val device = 0xA1000L
      mem.pokeByte(device, 0x12); mem.pokeByte(device + 1, 0x34)
      mem.pokeByte(device + 2, 0x56); mem.pokeByte(device + 3, 0x78)
      probe(0x31, device)
      probe(0x32, device + 16)
      cd.waitSampling(3)
      assert(dut.dcache.logic.earlyProbeValids.count(_.toBoolean) == 2,
        "both unresolved probes must be resident before simultaneous retirement")

      // The fault-cancel Flow has only one payload. A second, inhibited load must
      // release its token from the resolve port without stealing that Flow.
      rp.valid #= true
      rp.payload.token #= 0x31
      rp.payload.paddr #= device
      rp.payload.cacheMode #= CacheMode.INHIBITED
      pl.loadProbeCancelIn.valid #= true
      pl.loadProbeCancelIn.payload.all #= false
      pl.loadProbeCancelIn.payload.token #= 0x32
      cd.waitSampling()
      rp.valid #= false
      pl.loadProbeCancelIn.valid #= false
      cd.waitSampling()
      assert(!dut.dcache.logic.earlyProbeValids.exists(_.toBoolean),
        "inhibited resolve and unrelated fault cancel must both retire their probes")

      // Park drain sends this inhibited command later. With no probe token it must
      // still use the ordinary serial read and return the device value once.
      pl.loadCmdIn.valid #= true
      pl.loadCmdIn.payload.vaddr #= device
      pl.loadCmdIn.payload.paddr #= device
      pl.loadCmdIn.payload.size #= Size.LONG
      pl.loadCmdIn.payload.cacheMode #= CacheMode.INHIBITED
      pl.loadCmdIn.payload.token #= 0x31
      pl.loadCmdIn.payload.ooOk #= false
      pl.loadCmdIn.payload.rid #= 0
      pl.loadCmdIn.payload.ridValid #= false
      cd.waitSamplingWhere(pl.loadCmdIn.ready.toBoolean)
      pl.loadCmdIn.valid #= false
      cd.waitSamplingWhere(pl.loadRspOut.valid.toBoolean)
      assert(pl.loadRspOut.payload.data.toBigInt == BigInt("12345678", 16) &&
             pl.loadRspOut.payload.token.toInt == 0x31,
        "inhibited fallback must preserve data and token after early-probe retirement")
    }
  }

  test("COPYBACK store merged into a clean load MSHR remains dirty through CPUSH", VerilatorTest) {
    if (armOn("mergeDirty")) nbHotDut.doSim("merge-dirty") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val lg = dut.dcache.logic
      val pl = dut.probe.logic
      val cold = new BehavioralMemAgent(lg.axi, cd,
        dcfg = m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 8, dramCycles = 8),
          crossbarSingleOutstanding = true))
      m68k040.sim.AxiMemModel.attachReadOnly(lg.axiDh, cd,
        m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 100, dramCycles = 100)),
        sharedMem = cold.mem, sharedL2From = cold.model)
      pl.loadCmdIn.valid #= false
      pl.loadProbeIn.valid #= false
      pl.loadProbeCancelIn.valid #= false
      pl.storeIn.valid #= false
      pl.maintCmdIn.valid #= false
      dut.resolve.logic.resolveIn.valid #= false
      cd.waitSampling(4)
      cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)
      val x = 0xa4000L
      for (b <- 0 until 16) cold.pokeByte(x + b, 0x11)
      var hotAr = 0
      var storeAcks = 0
      var loadRsp: Option[BigInt] = None
      fork {
        while (true) {
          cd.waitSampling()
          if (lg.axiDh.ar.valid.toBoolean && lg.axiDh.ar.ready.toBoolean) hotAr += 1
          if (lg.storeAckReg.toBoolean) storeAcks += 1
          if (pl.loadRspOut.valid.toBoolean) loadRsp = Some(pl.loadRspOut.payload.data.toBigInt)
        }
      }
      def until(label: String)(p: => Boolean): Unit = {
        var n = 0
        while (!p && n < 2000) { cd.waitSampling(); n += 1 }
        assert(p, s"$label timed out")
      }
      pl.loadCmdIn.valid #= true
      pl.loadCmdIn.payload.vaddr #= x
      pl.loadCmdIn.payload.paddr #= x
      pl.loadCmdIn.payload.size #= Size.LONG
      pl.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
      pl.loadCmdIn.payload.token #= 1
      pl.loadCmdIn.payload.lineOnly #= false
      pl.loadCmdIn.payload.ooOk #= true
      pl.loadCmdIn.payload.rid #= 0
      pl.loadCmdIn.payload.ridValid #= true
      until("clean primary accept") { pl.loadCmdIn.ready.toBoolean }
      cd.waitSampling()
      pl.loadCmdIn.valid #= false
      until("clean primary hot AR") { hotAr == 1 }

      pl.storeIn.valid #= true
      pl.storeIn.payload.paddr #= x
      pl.storeIn.payload.data #= BigInt("77777777", 16)
      pl.storeIn.payload.size #= Size.LONG
      pl.storeIn.payload.useStrb #= true
      pl.storeIn.payload.strb #= 0x000f
      pl.storeIn.payload.lineData #= BigInt("77777777", 16)
      pl.storeIn.payload.cacheMode #= CacheMode.COPYBACK
      pl.storeIn.payload.precise #= false
      until("same-line COPYBACK store accept") { pl.storeIn.ready.toBoolean }
      cd.waitSampling()
      pl.storeIn.valid #= false
      until("merged store ack") { storeAcks > 0 }
      until("primary response") { loadRsp.nonEmpty }
      assert(lg.nb.ctrMap("storeMerges").toLong > 0,
        "store never merged into the clean load MSHR")
      assert((loadRsp.get & BigInt("ffffffff", 16)) == BigInt("77777777", 16),
        s"merged primary returned ${loadRsp.get.toString(16)}")
      assert(cold.peekByte(x) == 0x11, "COPYBACK store reached memory before CPUSH")
      until("cache idle before CPUSH") { lg.dcIdleForMaint.toBoolean }
      maint(dut, cd, push = true, invalidate = true, scope = 1, addr = x)
      assert((0 until 4).forall(b => cold.peekByte(x + b) == 0x77),
        "CPUSH dropped a COPYBACK store merged into a previously clean MSHR")
      println("[nbMergeDirty] clean load MSHR accepted COPYBACK store; CPUSH wrote merged dirty bytes")
    }
  }

  test("four dirty writebacks fill the buffer; a fifth demand waits and resumes after B", VerilatorTest) {
    if (armOn("wbFull")) nbHotDut.doSim("wb-full") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val lg = dut.dcache.logic
      val pl = dut.probe.logic
      val cold = new BehavioralMemAgent(lg.axi, cd,
        dcfg = m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 1000, dramCycles = 1000),
          crossbarSingleOutstanding = true, deferWriteVisibilityUntilB = true))
      m68k040.sim.AxiMemModel.attachReadOnly(lg.axiDh, cd,
        m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 5, dramCycles = 5)),
        sharedMem = cold.mem, sharedL2From = cold.model)
      pl.loadCmdIn.valid #= false
      pl.loadProbeIn.valid #= false
      pl.loadProbeCancelIn.valid #= false
      pl.storeIn.valid #= false
      pl.maintCmdIn.valid #= false
      dut.resolve.logic.resolveIn.valid #= false
      cd.waitSampling(4)
      cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)

      val base = 0x98000L
      def addr(set: Int, tag: Int): Long = base + set * 16L + tag * 2048L
      for (s <- 0 until 5; b <- 0 until 16)
        cold.pokeByte(addr(s, 4) + b, 0x90 + s)
      var aw = 0; var w = 0; var b = 0; var hotAr = 0
      val responses = mutable.Map.empty[Int, BigInt]
      fork {
        while (true) {
          cd.waitSampling()
          if (lg.axi.aw.valid.toBoolean && lg.axi.aw.ready.toBoolean &&
              lg.axi.aw.payload.id.toInt == AxiIds.D_PUSH) aw += 1
          if (lg.axi.w.valid.toBoolean && lg.axi.w.ready.toBoolean) w += 1
          if (lg.axi.b.valid.toBoolean && lg.axi.b.ready.toBoolean &&
              lg.axi.b.payload.id.toInt == AxiIds.D_PUSH) b += 1
          if (lg.axiDh.ar.valid.toBoolean && lg.axiDh.ar.ready.toBoolean) hotAr += 1
          if (pl.loadRspOut.valid.toBoolean)
            responses(pl.loadRspOut.payload.token.toInt) = pl.loadRspOut.payload.data.toBigInt
        }
      }
      def until(label: String, budget: Int = 6000)(p: => Boolean): Unit = {
        var n = 0
        while (!p && n < budget) { cd.waitSampling(); n += 1 }
        assert(p, s"$label timed out (wb=${lg.nb.wbCount.toInt}, AW=$aw W=$w B=$b hotAR=$hotAr)")
      }
      def storeLine(a: Long, byte: Int): Unit = {
        val line = (0 until 16).foldLeft(BigInt(0))((v, i) => v | (BigInt(byte) << (8 * i)))
        pl.storeIn.valid #= true
        pl.storeIn.payload.paddr #= a
        pl.storeIn.payload.data #= 0
        pl.storeIn.payload.size #= Size.LONG
        pl.storeIn.payload.useStrb #= true
        pl.storeIn.payload.strb #= 0xffff
        pl.storeIn.payload.lineData #= line
        pl.storeIn.payload.cacheMode #= CacheMode.COPYBACK
        pl.storeIn.payload.precise #= false
        until("full-line store accept") { pl.storeIn.ready.toBoolean }
        cd.waitSampling()
        pl.storeIn.valid #= false
        until("full-line store ack") { lg.storeAckReg.toBoolean }
        until("cache settle after priming") { lg.dcIdleForMaint.toBoolean }
      }
      def load(set: Int): Unit = {
        val a = addr(set, 4)
        pl.loadCmdIn.valid #= true
        pl.loadCmdIn.payload.vaddr #= a
        pl.loadCmdIn.payload.paddr #= a
        pl.loadCmdIn.payload.size #= Size.LONG
        pl.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
        pl.loadCmdIn.payload.token #= set + 1
        pl.loadCmdIn.payload.lineOnly #= false
        pl.loadCmdIn.payload.ooOk #= true
        pl.loadCmdIn.payload.rid #= (set & 3)
        pl.loadCmdIn.payload.ridValid #= true
        until(s"load $set accept") { pl.loadCmdIn.ready.toBoolean }
        cd.waitSampling()
        pl.loadCmdIn.valid #= false
      }
      // Four dirty residents per set guarantee the next tag's miss must evict dirty.
      for (s <- 0 until 5; t <- 0 until 4) storeLine(addr(s, t), 0x40 + s * 4 + t)
      assert(aw == 0 && b == 0, "priming unexpectedly sent a dirty writeback")
      for (s <- 0 until 4) {
        load(s)
        until(s"load $s response") { responses.contains(s + 1) }
        assert((responses(s + 1) & BigInt("ffffffff", 16)) ==
          BigInt(0x90 + s) * BigInt("01010101", 16), s"load $s returned wrong data")
      }
      until("writeback queue full") { lg.nb.wbCount.toInt == 4 }
      until("independent AW and W accepted") { aw > 0 && w > 0 }
      assert(b == 0, "the adversarial window closed before the fifth demand")
      val arBefore = hotAr
      load(4)
      cd.waitSampling(20)
      assert(!responses.contains(5), "fifth demand bypassed a full dirty-WB buffer")
      assert(hotAr == arBefore, "fifth demand issued a refill AR while WB was full")
      assert(lg.nb.ctrMap("failWb").toLong > 0, "WB-full admission failure never fired")
      until("first delayed dirty B") { b > 0 }
      until("fifth demand response after B") { responses.contains(5) }
      assert((responses(5) & BigInt("ffffffff", 16)) == BigInt("94949494", 16),
        "fifth demand returned wrong data after WB pressure released")
      assert(hotAr > arBefore, "held demand never issued its refill after WB space freed")
      println(s"[nbWB] forced full: AW=$aw W=$w B=$b failWb=${lg.nb.ctrMap("failWb").toLong} " +
        s"hotAR=$hotAr, fifth demand held until B and returned checked data")
    }
  }

  test("serial load admission waits for an older outstanding cacheable load", VerilatorTest) {
    if (armOn("serialBarrier")) nbHotDut.doSim("serial-barrier") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val lg = dut.dcache.logic
      val pl = dut.probe.logic
      val cold = new BehavioralMemAgent(lg.axi, cd,
        dcfg = m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 120, dramCycles = 120),
          crossbarSingleOutstanding = true))
      m68k040.sim.AxiMemModel.attachReadOnly(lg.axiDh, cd,
        m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 120, dramCycles = 120)),
        sharedMem = cold.mem, sharedL2From = cold.model)
      pl.loadCmdIn.valid #= false
      pl.loadProbeIn.valid #= false
      pl.loadProbeCancelIn.valid #= false
      pl.storeIn.valid #= false
      pl.maintCmdIn.valid #= false
      dut.resolve.logic.resolveIn.valid #= false
      val first = 0x9c000L
      val second = 0x9c400L
      for (b <- 0 until 16) { cold.pokeByte(first + b, 0x5a); cold.pokeByte(second + b, 0xa5) }
      cd.waitSampling(4)
      cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)
      val responses = mutable.ArrayBuffer.empty[(Int, BigInt)]
      fork {
        while (true) {
          cd.waitSampling()
          if (pl.loadRspOut.valid.toBoolean)
            responses += ((pl.loadRspOut.payload.token.toInt, pl.loadRspOut.payload.data.toBigInt))
        }
      }
      def command(a: Long, tok: Int, rid: Int, oo: Boolean): Unit = {
        pl.loadCmdIn.valid #= true
        pl.loadCmdIn.payload.vaddr #= a
        pl.loadCmdIn.payload.paddr #= a
        pl.loadCmdIn.payload.size #= Size.LONG
        pl.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
        pl.loadCmdIn.payload.token #= tok
        pl.loadCmdIn.payload.lineOnly #= false
        pl.loadCmdIn.payload.ooOk #= oo
        pl.loadCmdIn.payload.rid #= rid
        pl.loadCmdIn.payload.ridValid #= true
      }
      command(first, 1, 0, oo = true)
      assert(pl.loadCmdIn.ready.toBoolean, "ordinary first load did not admit")
      cd.waitSampling()
      pl.loadCmdIn.valid #= false
      // The second command remains valid, so an early ready is an architectural
      // acceptance violation, not merely a combinational pulse we failed to see.
      command(second, 2, 1, oo = false)
      for (_ <- 0 until 20) {
        cd.waitSampling()
        assert(!pl.loadCmdIn.ready.toBoolean,
          "serial load was admitted while an older load was still outstanding")
        assert(!responses.exists(_._1 == 1), "the first load returned before the adversarial window")
      }
      var n = 0
      while (!pl.loadCmdIn.ready.toBoolean && n < 1000) { cd.waitSampling(); n += 1 }
      assert(pl.loadCmdIn.ready.toBoolean, "serial load never admitted after the older load")
      assert(responses.exists(_._1 == 1), "serial load admitted before the older response")
      cd.waitSampling()
      pl.loadCmdIn.valid #= false
      n = 0
      while (!responses.exists(_._1 == 2) && n < 1000) { cd.waitSampling(); n += 1 }
      assert(responses.map(_._1).toSeq == Seq(1, 2), s"serial response order ${responses.map(_._1)}")
      assert((responses(0)._2 & BigInt("ffffffff", 16)) == BigInt("5a5a5a5a", 16))
      assert((responses(1)._2 & BigInt("ffffffff", 16)) == BigInt("a5a5a5a5", 16))
      println("[nbSerial] older response completed before serial admission; both data values checked")
    }
  }

  test("backpressured hot AR keeps VALID and payload through a same-line cold write", VerilatorTest) {
    if (armOn("arStable")) nbHotDut.doSim("ar-stable") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val lg = dut.dcache.logic
      val pl = dut.probe.logic
      var releaseAr = false
      val cold = new BehavioralMemAgent(lg.axi, cd,
        dcfg = m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 50, dramCycles = 50),
          crossbarSingleOutstanding = true, deferWriteVisibilityUntilB = true))
      m68k040.sim.AxiMemModel.attachReadOnly(lg.axiDh, cd,
        m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 5, dramCycles = 5),
          arReadyGate = () => releaseAr),
        sharedMem = cold.mem, sharedL2From = cold.model)
      pl.loadCmdIn.valid #= false
      pl.loadProbeIn.valid #= false
      pl.loadProbeCancelIn.valid #= false
      pl.storeIn.valid #= false
      pl.maintCmdIn.valid #= false
      dut.resolve.logic.resolveIn.valid #= false
      cd.waitSampling(4)
      cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)
      val x = 0xa0000L
      for (b <- 0 until 16) cold.pokeByte(x + b, 0x11)
      pl.loadCmdIn.valid #= true
      pl.loadCmdIn.payload.vaddr #= x
      pl.loadCmdIn.payload.paddr #= x
      pl.loadCmdIn.payload.size #= Size.LONG
      pl.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
      pl.loadCmdIn.payload.token #= 1
      pl.loadCmdIn.payload.lineOnly #= false
      pl.loadCmdIn.payload.ooOk #= true
      pl.loadCmdIn.payload.rid #= 0
      pl.loadCmdIn.payload.ridValid #= true
      var n = 0
      while (!pl.loadCmdIn.ready.toBoolean && n < 1000) { cd.waitSampling(); n += 1 }
      assert(pl.loadCmdIn.ready.toBoolean, "first load did not admit")
      cd.waitSampling()
      pl.loadCmdIn.valid #= false
      n = 0
      while (!lg.axiDh.ar.valid.toBoolean && n < 1000) { cd.waitSampling(); n += 1 }
      assert(lg.axiDh.ar.valid.toBoolean && !lg.axiDh.ar.ready.toBoolean,
        "hot AR was not presented under backpressure")
      val heldAddr = lg.axiDh.ar.payload.addr.toLong
      val heldId = lg.axiDh.ar.payload.id.toInt
      assert(heldAddr == x)
      var heldCycles = 0
      var arFire = 0
      var wtW = 0
      var wtB = 0
      var loadRsp: Option[BigInt] = None
      fork {
        while (true) {
          cd.waitSampling()
          if (!releaseAr) {
            assert(lg.axiDh.ar.valid.toBoolean,
              s"hot ARVALID withdrew after $heldCycles held cycles before ARREADY")
            assert(lg.axiDh.ar.payload.addr.toLong == heldAddr &&
                   lg.axiDh.ar.payload.id.toInt == heldId,
              "hot AR address or ID changed while backpressured")
            heldCycles += 1
          }
          if (lg.axiDh.ar.valid.toBoolean && lg.axiDh.ar.ready.toBoolean) arFire += 1
          if (lg.axi.w.valid.toBoolean && lg.axi.w.ready.toBoolean) wtW += 1
          if (lg.axi.b.valid.toBoolean && lg.axi.b.ready.toBoolean) wtB += 1
          if (pl.loadRspOut.valid.toBoolean) loadRsp = Some(pl.loadRspOut.payload.data.toBigInt)
        }
      }
      // A retired WT store arrives while the chosen AR is backpressured. The
      // cache must hold its cold write behind the advertised AR and merge its
      // bytes into the MSHR before answering the speculative load.
      pl.storeIn.valid #= true
      pl.storeIn.payload.paddr #= x
      pl.storeIn.payload.data #= 0
      pl.storeIn.payload.size #= Size.LONG
      pl.storeIn.payload.useStrb #= true
      pl.storeIn.payload.strb #= 0xffff
      pl.storeIn.payload.lineData #= BigInt("55" * 16, 16)
      pl.storeIn.payload.cacheMode #= CacheMode.WRITETHROUGH
      pl.storeIn.payload.precise #= false
      n = 0
      while (!pl.storeIn.ready.toBoolean && n < 1000) { cd.waitSampling(); n += 1 }
      assert(pl.storeIn.ready.toBoolean, "intervening WT store did not admit")
      cd.waitSampling()
      pl.storeIn.valid #= false
      cd.waitSampling(20)
      assert(wtW == 0 && wtB == 0 && cold.peekByte(x) == 0x11,
        "the intervening WT store overtook a backpressured advertised AR")
      assert(heldCycles >= 20, s"only $heldCycles backpressured AR cycles were exercised")
      assert(arFire == 0 && loadRsp.isEmpty, "hot refill completed while ARREADY was held low")
      releaseAr = true
      n = 0
      while (loadRsp.isEmpty && n < 1000) { cd.waitSampling(); n += 1 }
      assert(arFire == 1, s"expected one hot AR handshake, got $arFire")
      assert(loadRsp.exists(v => (v & BigInt("ffffffff", 16)) == BigInt("55555555", 16)),
        s"hot refill did not see older WT bytes: $loadRsp")
      n = 0
      while (wtW == 0 && n < 1000) { cd.waitSampling(); n += 1 }
      assert(wtW > 0, "held WT store sent no W beat after AR handshake")
      n = 0
      while (wtB == 0 && n < 1000) { cd.waitSampling(); n += 1 }
      assert(wtB > 0 && cold.peekByte(x) == 0x55,
        "intervening WT bytes were not committed to memory at B")
      println(s"[nbAR] VALID/address/ID stable for $heldCycles stalled cycles; " +
        "hot response merged pre-B WT bytes and memory committed at B")
    }
  }

  test("hot refill of an evicted dirty line waits for B and sees delayed write visibility", VerilatorTest) {
    if (armOn("wbVisibility")) nbHotDut.doSim("wb-visibility") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      val cold = new BehavioralMemAgent(dut.dcache.logic.axi, cd,
        dcfg = m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 100, dramCycles = 100),
          crossbarSingleOutstanding = true, deferWriteVisibilityUntilB = true))
      m68k040.sim.AxiMemModel.attachReadOnly(dut.dcache.logic.axiDh, cd,
        m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 5, dramCycles = 5)),
        sharedMem = cold.mem, sharedL2From = cold.model)
      val pl = dut.probe.logic
      val lg = dut.dcache.logic
      val rp = dut.resolve.logic.resolveIn
      pl.loadCmdIn.valid #= false
      pl.loadProbeIn.valid #= false
      pl.loadProbeCancelIn.valid #= false
      pl.storeIn.valid #= false
      pl.maintCmdIn.valid #= false
      rp.valid #= false
      cd.waitSampling(4)
      cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)

      val x = 0x94000L
      val stride = 2048L // same D-cache set, different tag
      for (i <- 0 until 6; b <- 0 until 16)
        cold.pokeByte(x + i * stride + b, 0x11)
      var awX = false
      var wX = false
      var bX = false
      var watchX = false
      var arXBeforeB = 0
      var arXAfterB = 0
      fork {
        while (true) {
          cd.waitSampling()
          if (lg.axi.aw.valid.toBoolean && lg.axi.aw.ready.toBoolean &&
              lg.axi.aw.payload.addr.toLong == x && lg.axi.aw.payload.id.toInt == AxiIds.D_PUSH)
            awX = true
          // AXI AW and W are independent; W may handshake before AW. Priming is
          // fully drained, so the first W after arming belongs to victim X.
          if (watchX && lg.axi.w.valid.toBoolean && lg.axi.w.ready.toBoolean) wX = true
          if (lg.axiDh.ar.valid.toBoolean && lg.axiDh.ar.ready.toBoolean &&
              lg.axiDh.ar.payload.addr.toLong == x) {
            if (bX) arXAfterB += 1 else arXBeforeB += 1
          }
          if (lg.axi.b.valid.toBoolean && lg.axi.b.ready.toBoolean &&
              lg.axi.b.payload.id.toInt == AxiIds.D_PUSH && awX) bX = true
        }
      }
      def until(label: String)(p: => Boolean): Unit = {
        var n = 0
        while (!p && n < 5000) { cd.waitSampling(); n += 1 }
        assert(p, s"$label did not happen within 5000 cycles")
      }
      def storeLine(addr: Long, byte: Int): Unit = {
        val line = (0 until 16).foldLeft(BigInt(0))((v, i) => v | (BigInt(byte) << (8 * i)))
        pl.storeIn.valid #= true
        pl.storeIn.payload.paddr #= addr
        pl.storeIn.payload.data #= 0
        pl.storeIn.payload.size #= Size.LONG
        pl.storeIn.payload.useStrb #= true
        pl.storeIn.payload.strb #= 0xffff
        pl.storeIn.payload.lineData #= line
        pl.storeIn.payload.cacheMode #= CacheMode.COPYBACK
        pl.storeIn.payload.precise #= false
        until("full-line store accept") { pl.storeIn.ready.toBoolean }
        cd.waitSampling()
        pl.storeIn.valid #= false
        until("full-line store ack") { lg.storeAckReg.toBoolean }
      }
      // Four dirty resident ways. The fifth same-set store evicts the first.
      for (i <- 0 until 4) {
        storeLine(x + i * stride, 0x55 + i)
        until("cache settle after priming") { lg.dcIdleForMaint.toBoolean }
      }
      watchX = true
      storeLine(x + 4 * stride, 0x66)
      until("dirty victim X AW") { awX }
      until("dirty victim X W") { wX }
      assert(!bX, "the delayed-B adversarial window was missed")
      assert(cold.peekByte(x) == 0x11, "test model exposed W before B")

      pl.loadCmdIn.valid #= true
      pl.loadCmdIn.payload.vaddr #= x
      pl.loadCmdIn.payload.paddr #= x
      pl.loadCmdIn.payload.size #= Size.LONG
      pl.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
      pl.loadCmdIn.payload.token #= 1
      pl.loadCmdIn.payload.lineOnly #= false
      pl.loadCmdIn.payload.ooOk #= true
      pl.loadCmdIn.payload.rid #= 0
      pl.loadCmdIn.payload.ridValid #= true
      until("same-line load accept") { pl.loadCmdIn.ready.toBoolean }
      cd.waitSampling()
      pl.loadCmdIn.valid #= false
      assert(!bX, "same-line load was not accepted until after the victim B")
      until("dirty victim X B") { bX }
      assert(arXBeforeB == 0, s"$arXBeforeB hot AR(s) read X before its dirty WB B")
      assert(cold.peekByte(x) == 0x55, "test model did not make the dirty W visible at B")
      assert(lg.nb.ctrMap("wbGate").toLong > 0,
        "same-line load never reached the writeback-address gate")
      until("post-B hot AR for X") { arXAfterB > 0 }
      until("same-line load response") { pl.loadRspOut.valid.toBoolean }
      assert(!pl.loadRspOut.payload.fault.toBoolean)
      assert((pl.loadRspOut.payload.data.toBigInt & BigInt("ffffffff", 16)) == BigInt("55555555", 16),
        "hot refill did not see the dirty line after B made it visible")
      println("[nbWB] delayed-visibility same-line AR gate CLEAN")
    }
  }

  private def singleMissPostR(early: Boolean): Int = {
    var postR = -1
    M68kSim().withVerilator.compile(new Dut(nb = true, dh = false, early = early,
      eagerAr = false, preselectAr = false, storeDelay = 0)).doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd,
        dcfg = m68k040.sim.AxiMemModelConfig(
          latency = m68k040.sim.L2LatencyModel(enabled = true, hitCycles = 6, dramCycles = 30)))
      val pl = dut.probe.logic
      val lg = dut.dcache.logic
      val addr = 0x00009B00L
      for (i <- 0 until 16) mem.pokeByte(addr + i, (0x31 + i) & 0xff)
      pl.loadCmdIn.valid #= false
      pl.loadProbeIn.valid #= false
      pl.loadProbeCancelIn.valid #= false
      pl.storeIn.valid #= false
      pl.maintCmdIn.valid #= false
      dut.resolve.logic.resolveIn.valid #= false
      cd.waitSampling(4)
      cd.waitSamplingWhere(!lg.resetSweepBusy.toBoolean)
      pl.loadCmdIn.valid #= true
      pl.loadCmdIn.payload.vaddr #= addr
      pl.loadCmdIn.payload.paddr #= addr
      pl.loadCmdIn.payload.size #= Size.LONG
      pl.loadCmdIn.payload.cacheMode #= CacheMode.COPYBACK
      pl.loadCmdIn.payload.token #= 3
      pl.loadCmdIn.payload.rid #= 0
      pl.loadCmdIn.payload.ridValid #= true
      pl.loadCmdIn.payload.lineOnly #= false
      pl.loadCmdIn.payload.ooOk #= true
      cd.waitSamplingWhere(pl.loadCmdIn.ready.toBoolean)
      pl.loadCmdIn.valid #= false
      var n = 0; var rAt = -1; var rspAt = -1; var rspCount = 0
      while (n < 200 && rspAt < 0) {
        if (lg.axi.r.valid.toBoolean && lg.axi.r.ready.toBoolean) rAt = n
        if (pl.loadRspOut.valid.toBoolean) {
          rspAt = n; rspCount += 1
          assert(pl.loadRspOut.payload.token.toInt == 3)
          assert(!pl.loadRspOut.payload.fault.toBoolean)
          assert(pl.loadRspOut.payload.data.toBigInt == BigInt("31323334", 16))
        }
        cd.waitSampling(); n += 1
      }
      for (_ <- 0 until 8) {
        cd.waitSampling()
        if (pl.loadRspOut.valid.toBoolean) rspCount += 1
      }
      assert(rAt >= 0 && rspAt >= 0, s"early=$early missed R or response: R=$rAt response=$rspAt")
      assert(rspCount == 1, s"early=$early duplicate response")
      postR = rspAt - rAt
    }
    postR
  }

  test("registered NB clean-refill shortcut saves one post-R cycle", VerilatorTest) {
    val off = singleMissPostR(early = false)
    val on = singleMissPostR(early = true)
    println(s"[nbEarlyResponse] accepted R -> loadRsp: OFF=$off ON=$on cycles")
    assert(off == 2 && on == 1, s"registered response-slot shortcut changed: OFF=$off ON=$on")
  }

  test("HARNESS CONTROL: the stress is clean on the legacy blocking cache", VerilatorTest) {
    if (armOn("control")) runArm(controlDut, "control", nb = false, coldMulti = false, m68k040.sim.AxiRspMode.InOrder)
  }
  test("non-blocking, refills on axi_d behind the crossbar's one-outstanding rule", VerilatorTest) {
    if (armOn("cold")) runArm(nbColdDut, "cold", nb = true, coldMulti = false, m68k040.sim.AxiRspMode.InOrder)
  }
  test("non-blocking, axi_d multi-outstanding with REORDERED responses", VerilatorTest) {
    if (armOn("coldReorder")) runArm(nbColdDut, "coldReorder", nb = true, coldMulti = true, m68k040.sim.AxiRspMode.Reordered)
  }
  test("non-blocking, hot door in order", VerilatorTest) {
    if (armOn("hot")) runArm(nbHotDut, "hot", nb = true, coldMulti = false, m68k040.sim.AxiRspMode.InOrder)
  }
  test("non-blocking, hot door CHAOS order (newest first)", VerilatorTest) {
    if (armOn("hotChaos")) runArm(nbHotDut, "hotChaos", nb = true, coldMulti = false, m68k040.sim.AxiRspMode.Chaos)
  }
}
