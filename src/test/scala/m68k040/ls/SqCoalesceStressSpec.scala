package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.cache._
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.mmu.DIdentityTranslationPlugin
import m68k040.services.DTranslationService
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

import scala.collection.mutable

/** WRITE-SIDE BANDWIDTH (parts A+B) -- randomized SHADOW-MEMORY STRESS of the REAL
  * `StoreQueue` draining into the REAL `DcachePlugin`.
  *
  * WHY THIS EXISTS. The previous version of this lever (no-write-allocate, `perf/dside-mshr2`)
  * was green on test-fast, the netlist check, the bandwidth bench AND two directed specs,
  * and wrong: only the corpus and lock-step caught it (a lost `storeAck`, an uncapped
  * FIFO). So the store path gets a stress whose oracle is independent of the design --
  * a byte-granular shadow of program-order RETIRED stores -- and which is PROVEN able to
  * fail: every "MUTANT" test below builds deliberately broken RTL (a corrupted merge byte,
  * a dropped word, a short pop, allocate-without-fill on a PARTIAL line, a dropped ack)
  * and requires the stress to go red. A green stress means nothing until those are red.
  *
  * TRAFFIC (weighted toward what the feature changes): full-line 4 x LONG runs in
  * ascending / descending / shuffled order; 3-of-4 partial runs; runs with a duplicated
  * offset; MOVE16-shaped L,L,S,S interleave with loads from another line; single
  * BYTE/WORD/LONG stores incl. line-crossing SPLITS; WT and CB mixed on one line; PRECISE
  * stores (CB, WT and INHIBITED) drained at the ROB head; precise stores to UNDECODED
  * memory (a real DECERR -> `sqFaultCompletion`, memory untouched); random retire delay
  * (which is what makes the coalescing hold matter), random FLUSHES of the unretired
  * window, random `coalesceBreak`, and a working set 1.5x the cache's associativity per
  * set so dirty evictions are constant.
  *
  * ORACLE. (1) Mid-run loads to a line with no resident store (the SQ's own forward
  * verdict says neither hit nor stall -- exactly when the real LSU would read the cache)
  * must equal the shadow. (2) At the end: drain, CPUSH-all, and every byte of memory must
  * equal the shadow. (3) Every sim assert in both RTL blocks is armed (FAILURE). (4) A
  * cycle budget turns any wedge into a failure.
  */
class SqCoalesceStressSpec extends AnyFunSuite {
  private val W = m68k040.Global.ROB_ID_W_DEFAULT
  private val ROBN = 1 << W

  /** Bridge between the real SQ and the real D-cache, plus test ports. `dropAckAt >= 0`
    * is the DROPPED-ACK MUTANT: the `dropAckAt`-th D-cache store ack never reaches the SQ. */
  class Bridge(sq: StoreQueue, dropAckAt: Int) extends FiberPlugin {
    val logic = during build new Area {
      val ds = host[DcacheService]
      val xlate = host[DTranslationService]
      ds.store.valid   := sq.io.drain.valid
      ds.store.payload := sq.io.drain.payload
      sq.io.drain.ready := ds.store.ready
      val ackCount = RegInit(U(0, 20 bits))
      when(ds.storeAck) { ackCount := ackCount + 1 }
      val drop: Bool = if (dropAckAt < 0) False else ds.storeAck && ackCount === U(dropAckAt, 20 bits)
      sq.io.drainAck := ds.storeAck && !drop
      sq.io.drainErr := ds.storeErr && !drop

      val loadCmdIn  = slave(Stream(DLoadCmd()))
      val loadRspOut = master(Flow(DLoadRsp()))
      ds.loadCmd << loadCmdIn
      loadRspOut << ds.loadRsp
      val maintCmdIn   = slave(Flow(CacheMaintCmd()))
      val maintDoneOut = out(Bool())
      ds.maintCmd.valid   := maintCmdIn.valid
      ds.maintCmd.payload := maintCmdIn.payload
      maintDoneOut := ds.maintDone
      val quiescedOut = out(Bool())
      quiescedOut := ds.maintQuiesced
      val arFire = out(Bool())
      arFire := False

      ds.loadProbe.valid             := False
      ds.loadProbe.payload.vaddr     := 0
      ds.loadProbe.payload.token     := 0
      ds.loadProbe.payload.resolved  := False
      ds.loadProbe.payload.paddrHint := 0
      ds.loadProbe.payload.size      := Size.LONG
      ds.loadProbe.payload.cacheMode := CacheMode.INHIBITED
      ds.loadProbe.payload.needsLine := False
      ds.loadProbeResolve.valid             := False
      ds.loadProbeResolve.payload.token     := 0
      ds.loadProbeResolve.payload.paddr     := 0
      ds.loadProbeResolve.payload.cacheMode := CacheMode.INHIBITED
      ds.loadProbeCancel.valid         := False
      ds.loadProbeCancel.payload.token := 0
      ds.loadProbeCancel.payload.all   := False
      xlate.req.valid              := False
      xlate.req.payload.vpn        := 0
      xlate.req.payload.supervisor := False
      xlate.req.payload.write      := False
      xlate.req.payload.token      := 0
      xlate.rsp.ready              := True
    }
  }

  class Dut(coalesce: Boolean, noFill: Boolean, sqMut: Int, dcMut: Int, dropAckAt: Int)
      extends Component {
    val sq = new StoreQueue(8, coalesceLines = coalesce, coalesceHold = 16,
                            coalesceMutation = sqMut)
    val allocIn   = slave(Flow(SqAlloc()))
    val commitIn  = slave(Flow(UInt(W bits)))
    val flushIn   = in Bool ()
    val robHeadIn = in UInt (W bits)
    val robHeadValidIn = in Bool ()
    val breakIn   = in Bool ()
    val fwdRobIn  = in UInt (W bits)
    val fwdAddrIn = in UInt (32 bits)
    val fwdHitOut   = out Bool ()
    val fwdStallOut = out Bool ()
    val emptyOut = out Bool ()
    val fullOut  = out Bool ()
    val compValid  = out Bool ()
    val compRob    = out UInt (W bits)
    val faultValid = out Bool ()
    val keptPrecise = out Bool ()
    val preciseBusy = out Bool ()
    val coalFire = out Bool ()
    val coalHold = out Bool ()
    val coalTimeout = out Bool ()

    sq.io.alloc.valid   := allocIn.valid
    sq.io.alloc.payload := allocIn.payload
    sq.io.commit.valid   := commitIn.valid
    sq.io.commit.payload := commitIn.payload
    sq.io.commitB.valid   := False
    sq.io.commitB.payload := 0
    sq.io.flush := flushIn
    sq.io.robHeadIn := robHeadIn
    sq.io.robHeadValidIn := robHeadValidIn
    sq.io.irqPreemptPendingIn := False
    sq.io.fwd.query.robId := fwdRobIn
    sq.io.fwd.query.paddr := fwdAddrIn
    sq.io.fwd.query.size  := Size.LONG
    sq.io.fwd.query.splitB := False
    sq.io.fwd.query.paddrB := U(0, 32 bits)
    sq.io.fwd.query.inhibited := False
    sq.io.barrier.robId := fwdRobIn
    fwdHitOut   := sq.io.fwd.rsp.hit
    fwdStallOut := sq.io.fwd.rsp.stall || sq.io.fwd.rsp.serial
    emptyOut := sq.io.empty
    fullOut  := sq.io.full
    compValid := sq.io.sqCompletion.valid && !sq.io.sqCompletionOrphan
    compRob   := sq.io.sqCompletion.payload
    faultValid := sq.io.sqFaultCompletion.valid
    keptPrecise := sq.io.flushKeptPrecise
    preciseBusy := sq.io.preciseDrainBusy
    if (coalesce) {
      sq.io.coalesceBreak := breakIn
      coalFire := sq.io.coalesceFire; coalHold := sq.io.coalesceHolding
      coalTimeout := sq.io.coalesceTimeout
    } else { coalFire := False; coalHold := False; coalTimeout := False }

    val db = new Database
    val host = db on (new PluginHost)
    val param  = new ParamPlugin(M68kParams())
    val xlate  = new DIdentityTranslationPlugin
    val dcache = new DcachePlugin(sectored = false, fullLineNoFill = noFill, noFillMutation = dcMut)
    val bridge = new Bridge(sq, dropAckAt)
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, bridge)) }
  }

  private val compiled = mutable.Map.empty[String, spinal.core.sim.SimCompiled[Dut]]
  private def dut(tag: String, coalesce: Boolean, noFill: Boolean, sqMut: Int = 0,
                  dcMut: Int = 0, dropAckAt: Int = -1) =
    compiled.getOrElseUpdate(tag, M68kSim().withVerilator.workspaceName(s"sqcoal_$tag")
      .compile(new Dut(coalesce, noFill, sqMut, dcMut, dropAckAt)))

  // ── address map ───────────────────────────────────────────────────────────────
  // Cacheable window: 4 sets x 6 tags, 2 KiB apart (= one way of the 8 KiB, 4-way L1D),
  // so every set holds 1.5x its associativity and dirty evictions are constant.
  private val CBASE = 0x00010000L
  private def cline(tag: Int, set: Int): Long = CBASE + tag * 0x800L + set * 16L
  private val cLines: IndexedSeq[Long] = for (t <- 0 until 6; s <- 0 until 4) yield cline(t, s)
  private val IBASE = 0x00030000L                    // INHIBITED page: precise only
  private val iLines: IndexedSeq[Long] = (0 until 4).map(i => IBASE + 16L * i)
  private val BAD = 0x20000000L                      // undecoded: every write DECERRs
  private def initByte(a: Long): Int = ((a * 13 + 0x5b) ^ (a >>> 8)).toInt & 0xff

  case class St(robId: Int, paddr: Long, size: Int, data: BigInt, mode: SpinalEnumElement[CacheMode.type],
                precise: Boolean, faults: Boolean) {
    def bytes: Seq[(Long, Int)] = (0 until size).map(k =>
      (paddr + k, ((data >> (8 * (size - 1 - k))) & 0xff).toInt))
  }

  case class Stats(var coalesced: Long = 0, var holdCyc: Long = 0, var timeouts: Long = 0,
                   var noFills: Long = 0, var ar: Long = 0, var stores: Long = 0,
                   var loads: Long = 0, var flushes: Long = 0, var faults: Long = 0,
                   var cycles: Long = 0)

  /** One randomized run. Throws on any mismatch, wedge or RTL assert. */
  private def stress(d: Dut, seed: Long, ops: Int, noFill: Boolean): Stats = {
    val rnd = new scala.util.Random(seed)
    val cd = d.clockDomain
    cd.forkStimulus(10)
    val mem = new BehavioralMemAgent(d.dcache.logic.axi, cd,
      dcfg = m68k040.sim.AxiMemModelConfig(injectBusErrors = true))
    val st = Stats()
    val shadow = mutable.Map.empty[Long, Int]
    for (l <- cLines ++ iLines; i <- 0 until 16) { mem.pokeByte(l + i, initByte(l + i)); shadow(l + i) = initByte(l + i) }

    d.allocIn.valid #= false; d.commitIn.valid #= false; d.flushIn #= false
    d.robHeadIn #= 0; d.robHeadValidIn #= false; d.breakIn #= false
    d.fwdRobIn #= ROBN - 1; d.fwdAddrIn #= 0
    val b = d.bridge.logic
    b.loadCmdIn.valid #= false; b.maintCmdIn.valid #= false
    b.maintCmdIn.payload.push #= false; b.maintCmdIn.payload.invalidate #= false
    b.maintCmdIn.payload.scope #= 0; b.maintCmdIn.payload.sel #= 0; b.maintCmdIn.payload.addr #= 0
    b.loadCmdIn.payload.token #= 0
    cd.waitSampling(4)
    cd.waitSamplingWhere(!d.dcache.logic.resetSweepBusy.toBoolean)

    // ── ROB model: unretired stores, oldest first ─────────────────────────────────
    val rob = mutable.Queue.empty[St]
    var nextRob = 0
    var retireWait = 0         // cycles the current head has left before it may retire
    var cyc = 0L
    val budget = 400L * ops + 200000L
    var lastProgress = 0L
    var nofillPrev = false

    // Sampling monitor: counts + keeps nothing that affects the oracle.
    val mon = fork {
      while (true) {
        cd.waitSampling()
        if (d.coalFire.toBoolean) st.coalesced += 1
        if (d.coalHold.toBoolean) st.holdCyc += 1
        if (d.coalTimeout.toBoolean) st.timeouts += 1
        if (noFill && d.dcache.logic.noFillAlloc.toBoolean) st.noFills += 1
        if (d.dcache.logic.axi.ar.valid.toBoolean && d.dcache.logic.axi.ar.ready.toBoolean) st.ar += 1
      }
    }

    def headRob: Int = rob.headOption.map(_.robId).getOrElse(nextRob)
    def driveRob(): Unit = {
      d.robHeadIn #= headRob
      d.robHeadValidIn #= rob.nonEmpty
      d.fwdRobIn #= nextRob     // younger than every allocated store (olderThan is strict)
      d.breakIn #= rnd.nextInt(40) == 0
    }

    /** One clock of background: retire the ROB head when allowed. */
    def tick(): Unit = {
      driveRob()
      d.commitIn.valid #= false
      if (rob.nonEmpty && !rob.head.precise) {
        if (retireWait > 0) retireWait -= 1
        else {
          val h = rob.dequeue()
          d.commitIn.valid #= true; d.commitIn.payload #= h.robId
          h.bytes.foreach { case (a, v) => shadow(a) = v }
          retireWait = pickRetireDelay()
          lastProgress = cyc
        }
      }
      cd.waitSampling()
      cyc += 1; st.cycles += 1
      d.commitIn.valid #= false
      // precise completion (sampled on the clock edge that popped it)
      if (d.compValid.toBoolean) {
        assert(rob.nonEmpty && rob.head.precise && rob.head.robId == d.compRob.toInt,
          s"seed $seed cyc $cyc: sqCompletion for rob ${d.compRob.toInt} but ROB head is ${rob.headOption}")
        val h = rob.dequeue()
        val faulted = d.faultValid.toBoolean
        assert(faulted == h.faults, s"seed $seed cyc $cyc: store $h fault=$faulted expected ${h.faults}")
        if (!faulted) h.bytes.foreach { case (a, v) => shadow(a) = v } else st.faults += 1
        retireWait = pickRetireDelay()
        lastProgress = cyc
      }
      assert(!d.keptPrecise.toBoolean, "harness never flushes over a precise head")
      if (cyc - lastProgress > 20000 || cyc > budget) {
        val h = d.sq.head.toInt
        throw new AssertionError(s"seed $seed: WEDGE at cyc $cyc (rob=${rob.size} head=${rob.headOption} " +
          s"empty=${d.emptyOut.toBoolean} sqHead=$h tail=${d.sq.tail.toInt} " +
          s"headV=${d.sq.valids(h).toBoolean} headC=${d.sq.committed(h).toBoolean} " +
          s"headRob=${d.sq.robIds(h).toInt} robHead=${d.robHeadIn.toInt} " +
          s"send=${d.sq.sendPtr.toInt} " +
          s"accepted=${d.sq.acceptedHalves.toInt} drainBusy=${d.sq.drainBusy.toBoolean} " +
          s"drainValid=${d.sq.io.drain.valid.toBoolean} drainReady=${d.sq.io.drain.ready.toBoolean})")
      }
    }
    def pickRetireDelay(): Int = rnd.nextInt(10) match {
      case 0 => 20 + rnd.nextInt(30)      // long: forces the hold to time out sometimes
      case 1 | 2 | 3 => 2 + rnd.nextInt(6)
      case _ => 0
    }

    def alloc(s: St): Unit = {
      // `tick` returns at the sampling edge, before the SQ's registered liveCount
      // has settled. Observe full in the following delta before driving a Flow
      // allocation; Flow has no ready handshake to recover an overfull pulse.
      sleep(1)
      while (d.fullOut.toBoolean || rob.size >= 24) { tick(); sleep(1) }
      val a = d.allocIn
      val off = (s.paddr & 0xf).toInt
      val split = off + s.size > 16
      val nA = if (split) 16 - off else s.size
      a.valid #= true
      a.payload.robId #= s.robId
      a.payload.paddr #= s.paddr; a.payload.vaddr #= s.paddr
      a.payload.data #= s.data
      a.payload.size #= (s.size match { case 1 => Size.BYTE; case 2 => Size.WORD; case _ => Size.LONG })
      a.payload.nbytesA #= nA
      a.payload.useStrbA #= split
      a.payload.validB #= split
      a.payload.paddrB #= (if (split) (s.paddr & ~0xfL) + 16 else 0L)
      a.payload.vaddrB #= (if (split) (s.paddr & ~0xfL) + 16 else 0L)
      a.payload.nbytesB #= (if (split) s.size - nA else 0)
      a.payload.cacheMode #= s.mode; a.payload.cacheModeB #= s.mode
      a.payload.supervisor #= true
      a.payload.precise #= s.precise
      rob.enqueue(s)
      // A new store cannot retire on the same edge as SQ allocation: the SQ
      // deliberately clears `committed(tail)` on alloc, so that coincidence would
      // drop the commit notice. Real issue-to-retire latency supplies this gap.
      if (rob.size == 1) retireWait = scala.math.max(1, pickRetireDelay())
      nextRob = (nextRob + 1) % ROBN
      st.stores += 1
      tick()
      a.valid #= false
    }

    def mkStore(paddr: Long, size: Int, mode: SpinalEnumElement[CacheMode.type], precise: Boolean,
                faults: Boolean = false): St =
      St(nextRob, paddr, size, BigInt(size * 8, rnd), mode, precise, faults)

    def cbOrWt(): SpinalEnumElement[CacheMode.type] = if (rnd.nextInt(8) == 0) CacheMode.WRITETHROUGH else CacheMode.COPYBACK
    def maybePrecise(): Boolean = rnd.nextInt(24) == 0

    /** Load a LONG through the D-cache if the SQ says the real LSU would; compare. */
    def tryLoad(addr: Long): Unit = {
      d.fwdAddrIn #= addr
      driveRob(); sleep(1)
      if (d.fwdHitOut.toBoolean || d.fwdStallOut.toBoolean) return
      // A store to this line that is allocated but not in the SQ does not exist here:
      // the harness allocates synchronously. So the line's stores have all drained.
      val p = b.loadCmdIn
      p.valid #= true
      p.payload.vaddr #= addr; p.payload.paddr #= addr
      p.payload.size #= Size.LONG; p.payload.cacheMode #= CacheMode.COPYBACK
      p.payload.token #= 0
      var n = 0
      while (!(p.ready.toBoolean && p.valid.toBoolean)) { tick(); n += 1
        if (n > 5000) throw new AssertionError(s"seed $seed: load 0x${addr.toHexString} never accepted") }
      tick()
      p.valid #= false
      n = 0
      while (!b.loadRspOut.valid.toBoolean) { tick(); n += 1
        if (n > 5000) throw new AssertionError(s"seed $seed: load 0x${addr.toHexString} no response") }
      val got = b.loadRspOut.payload.data.toBigInt
      val exp = (0 until 4).foldLeft(BigInt(0))((acc, k) => (acc << 8) | shadow(addr + k))
      // Any store to this line still UNRETIRED in the harness would have been visible to
      // the forward query, so `exp` (retired-only) is the exact architectural value.
      assert(!rob.exists(s => s.bytes.exists { case (a, _) => a >= addr && a < addr + 4 }),
        s"seed $seed: harness bug -- load issued across a resident store byte")
      assert(got == exp, f"seed $seed cyc $cyc: LOAD 0x$addr%08x got 0x$got%08x expected 0x$exp%08x")
      st.loads += 1
    }

    def flush(): Unit = {
      // Only over a non-precise head: a precise head may already be launched (see the
      // SQ's `headDrainInFlight`), which is exercised by the SQ's own directed specs.
      if (rob.isEmpty || rob.head.precise || rob.exists(_.precise) || d.preciseBusy.toBoolean) return
      d.flushIn #= true
      driveRob()
      cd.waitSampling(); cyc += 1
      d.flushIn #= false
      // Every unretired store is squashed; the ROB tail rolls back to the head.
      nextRob = headRob
      rob.clear()
      st.flushes += 1
    }

    // ── the op mix ────────────────────────────────────────────────────────────────
    var op = 0
    while (op < ops) {
      rnd.nextInt(100) match {
        case r if r < 30 =>                                      // FULL-LINE run
          val l = cLines(rnd.nextInt(cLines.size))
          val order = rnd.nextInt(4) match {
            case 0 => Seq(12, 8, 4, 0); case 1 => rnd.shuffle(Seq(0, 4, 8, 12)); case _ => Seq(0, 4, 8, 12)
          }
          val mode = if (rnd.nextInt(10) == 0) cbOrWt() else CacheMode.COPYBACK
          order.foreach { o =>
            val m = if (rnd.nextInt(30) == 0) CacheMode.WRITETHROUGH else mode
            alloc(mkStore(l + o, 4, m, maybePrecise()))
            (0 until rnd.nextInt(3)).foreach(_ => tick())
          }
        case r if r < 42 =>                                      // PARTIAL / duplicate run
          val l = cLines(rnd.nextInt(cLines.size))
          val offs = if (rnd.nextBoolean()) rnd.shuffle(Seq(0, 4, 8, 12)).take(3)
                     else Seq(0, 4, 4, 8, 12).take(3 + rnd.nextInt(3))
          offs.foreach(o => alloc(mkStore(l + o, 4, CacheMode.COPYBACK, false)))
        case r if r < 54 =>                                      // MOVE16-shaped L,L,S,S
          val src = cLines(rnd.nextInt(cLines.size))
          val dst = cLines(rnd.nextInt(cLines.size))
          if (src != dst) {
            for (h <- 0 until 2) {
              tryLoad(src + 8 * h); tryLoad(src + 8 * h + 4)
              alloc(mkStore(dst + 8 * h, 4, CacheMode.COPYBACK, false))
              alloc(mkStore(dst + 8 * h + 4, 4, CacheMode.COPYBACK, false))
            }
          }
        case r if r < 72 =>                                      // single, any size, splits
          val sz = Seq(1, 2, 4)(rnd.nextInt(3))
          val l = cLines(rnd.nextInt(cLines.size - 1))
          val off = if (rnd.nextInt(5) == 0) 13 + rnd.nextInt(3) else rnd.nextInt(17 - sz)
          // A split must stay inside the window: `cLines` is 16-byte contiguous within a
          // set run only for set < 3, so pick the next line explicitly.
          val base = if (off + sz > 16) cline(rnd.nextInt(6), rnd.nextInt(3)) else l
          alloc(mkStore(base + off, sz, cbOrWt(), maybePrecise()))
        case r if r < 76 =>                                      // PRECISE inhibited
          val l = iLines(rnd.nextInt(iLines.size))
          alloc(mkStore(l + 4 * rnd.nextInt(4), 4, CacheMode.INHIBITED, precise = true))
        case r if r < 78 =>                                      // PRECISE fault
          alloc(mkStore(BAD + 16 * rnd.nextInt(4), 4, CacheMode.WRITETHROUGH, precise = true,
                        faults = true))
        case r if r < 88 =>                                      // loads
          tryLoad(cLines(rnd.nextInt(cLines.size)) + 4 * rnd.nextInt(4))
        case r if r < 91 => flush()
        case _ => (0 until rnd.nextInt(12)).foreach(_ => tick())
      }
      op += 1
    }
    // Retire everything, drain, then CPUSH-all and compare memory.
    while (rob.nonEmpty || !d.emptyOut.toBoolean) tick()
    (0 until 20).foreach(_ => tick())
    cd.waitSamplingWhere(b.quiescedOut.toBoolean)
    b.maintCmdIn.valid #= true
    b.maintCmdIn.payload.push #= true; b.maintCmdIn.payload.invalidate #= true
    b.maintCmdIn.payload.scope #= 3; b.maintCmdIn.payload.sel #= 1
    cd.waitSampling()
    b.maintCmdIn.valid #= false
    var n = 0
    while (!b.maintDoneOut.toBoolean) { cd.waitSampling(); n += 1
      if (n > 200000) throw new AssertionError(s"seed $seed: CPUSH-all never finished") }
    cd.waitSampling(8)
    mon.terminate()
    val bad = shadow.toSeq.sortBy(_._1).filter { case (a, v) => (mem.peekByte(a) & 0xff) != v }
    assert(bad.isEmpty, s"seed $seed: ${bad.size} MEMORY BYTES WRONG after CPUSH, first: " +
      bad.take(8).map { case (a, v) => f"0x$a%08x mem=0x${mem.peekByte(a) & 0xff}%02x exp=0x$v%02x" }.mkString(", "))
    st
  }

  private def runSeeds(tag: String, coalesce: Boolean, noFill: Boolean, seeds: Seq[Long],
                       ops: Int): Seq[Stats] =
    seeds.map { s =>
      var out: Stats = null
      dut(tag, coalesce, noFill).doSim(s"seed$s") { d => out = stress(d, s, ops, noFill) }
      println(f"[sqcoal] $tag seed=$s coalesced=${out.coalesced} noFill=${out.noFills} " +
        f"hold=${out.holdCyc} timeouts=${out.timeouts} ar=${out.ar} stores=${out.stores} " +
        f"loads=${out.loads} flushes=${out.flushes} faults=${out.faults} cyc=${out.cycles}")
      out
    }

  private val SEEDS = sys.env.get("SQCOAL_SEEDS").map(_.toInt).getOrElse(8)
  private val OPS   = sys.env.get("SQCOAL_OPS").map(_.toInt).getOrElse(3000)

  test("HARNESS CONTROL: the shadow stress is clean with both flags OFF", VerilatorTest) {
    val r = runSeeds("off", coalesce = false, noFill = false, (1L to SEEDS.toLong), OPS)
    assert(r.forall(_.coalesced == 0))
  }

  test("coalescing + full-line no-fill: clean, and BOTH mechanisms fire", VerilatorTest) {
    val r = runSeeds("on", coalesce = true, noFill = true, (1L to SEEDS.toLong), OPS)
    assert(r.map(_.coalesced).sum > 0, "the coalescer never fired -- the ON arm is vacuous")
    assert(r.map(_.noFills).sum > 0, "no full-line no-fill allocation ever happened -- vacuous")
    assert(r.map(_.flushes).sum > 0 && r.map(_.faults).sum > 0 && r.map(_.loads).sum > 0)
  }

  test("coalescing alone (no-fill OFF): clean", VerilatorTest) {
    val r = runSeeds("coal", coalesce = true, noFill = false, (1L to (SEEDS / 2).max(2).toLong), OPS)
    assert(r.map(_.coalesced).sum > 0)
  }

  // ── MUTANTS: each MUST fail. A mutant that survives means the stress is blind. ──
  private def mustFail(tag: String, sqMut: Int = 0, dcMut: Int = 0, dropAckAt: Int = -1): Unit = {
    val c = dut(tag, coalesce = true, noFill = true, sqMut, dcMut, dropAckAt)
    val caught = (1L to 4L).exists { s =>
      try { c.doSim(s"mut_seed$s") { d => stress(d, s, OPS, noFill = true) }; false }
      catch { case t: Throwable => println(s"[sqcoal] MUTANT $tag seed=$s caught: " +
        t.getMessage.take(300).replace('\n', ' ')); true }
    }
    assert(caught, s"MUTANT $tag SURVIVED 4 seeds -- the stress cannot see this defect")
  }
  test("MUTANT: one corrupted byte in the merged line is caught", VerilatorTest) { mustFail("m_byte", sqMut = 1) }
  test("MUTANT: a dropped word in the merged line is caught", VerilatorTest) { mustFail("m_word", sqMut = 2) }
  test("MUTANT: a coalesced ack that pops ONE entry is caught", VerilatorTest) { mustFail("m_pop1", sqMut = 3) }
  test("MUTANT: allocate-without-fill on a PARTIAL line is caught", VerilatorTest) { mustFail("m_partial", dcMut = 1) }
  test("MUTANT: a dropped store ack is caught", VerilatorTest) { mustFail("m_dropack", dropAckAt = 300) }
}
