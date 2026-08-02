package m68k040.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Ar, Axi4Aw, Axi4B, Axi4Config, Axi4R, Axi4ReadOnly, Axi4W}
import spinal.lib.Stream
import spinal.lib.sim.{SparseMemory, StreamDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.mutable

/** Response-ordering mode for the read data channel.
  *
  *  - `InOrder`            : one global FIFO across every ID -- byte-for-byte the
  *                           behaviour of the legacy `BehavioralMemAgent` (a single
  *                           `rQueue`, `BehavioralMem.scala:97`). This mode exists to
  *                           prove no regression, and is the default.
  *  - `Reordered`          : cross-ID reordering is free; per-ID beat order is
  *                           preserved and a burst is never interleaved with another
  *                           ID's beats. This is what the real SoC L2 does BY DESIGN
  *                           (design doc §3.1, `docs/l2c_spec.md:474-477`).
  *  - `Chaos`              : maximally adversarial legal reordering -- always serve
  *                           the NEWEST eligible ID first, so any latent in-order
  *                           assumption in the DUT fails on the first opportunity.
  *  - `IllegalInterleave`  : deliberately ILLEGAL under AXI4 -- beats of different IDs
  *                           may interleave INSIDE a burst. Reproduces design doc
  *                           §3.4 U2 (the L2 may do this; its own per-ID-deque
  *                           testbench checker is blind to it by construction). This
  *                           design is supposed to TOLERATE it, and this mode is the
  *                           only place that claim can be tested. */
object AxiRspMode extends Enumeration {
  type AxiRspMode = Value
  val InOrder, Reordered, Chaos, IllegalInterleave = Value
}

/** Two-tier latency model parameterised from the MEASURED L2 contract (design doc
  * §3.1 -- `macqd700-soc/rtl/soc/l2c*.v` + `docs/l2c_spec.md`). Every number here is
  * either measured or explicitly a swept unknown; none is invented.
  *
  *  - `lineBytes = 64`        : `l2c_defs.vh:26-42` (`L2C_LINE_BYTES 64`).
  *  - `hitCycles = 5`         : measured 5.1-5.3 cycle round trip, `docs/l2c_spec.md:668-679`.
  *  - `dramCycles`            : *** UNMEASURED (design doc §3.4 U1) *** -- real DDR4/MIG
  *                              latency is undocumented in BOTH repos. This MUST be
  *                              swept, never assumed. Default 40 is a placeholder that
  *                              no performance claim may rest on.
  *  - `fillFixedCycles = 4`   : `S_INSTALL -> S_PRSP` plus assembly, `l2c_mshr.v:211-246`.
  *  - NO critical-word-first  : the L2's fill AR is always line-base-aligned ARLEN=3 and
  *                              the response is produced only after the WHOLE line is
  *                              assembled (`l2c_mshr.v:160-163,211-246`), so the
  *                              requester pays the full 64-byte line even for a 4-byte
  *                              load. Modelled by making the FIRST beat of a miss burst
  *                              ready only after the whole-line latency.
  *  - `l2Mshrs = 8`, `secondaryPerMshr = 4` : `l2c_defs.vh:38-41`. Allocation
  *                              BACK-PRESSURES (AR ready low), it never drops. */
case class L2LatencyModel(
  enabled: Boolean = false,
  lineBytes: Int = 64,
  hitCycles: Int = 5,
  dramCycles: Int = 40,
  fillFixedCycles: Int = 4,
  interBeatGap: Int = 1,
  l2Mshrs: Int = 8,
  secondaryPerMshr: Int = 4
)

/** Full configuration of one attached memory model instance.
  *
  *  - `idBusyBlock`               : mirror the L2's `id_busy_c` front-door CAM
  *                                  (`l2c_ctrl.v:140-142,151`, `l2c_mshr.v:100-116`):
  *                                  a second AR presenting an ID that is already live
  *                                  is NOT accepted. This is the knob that PUNISHES a
  *                                  constant-ID master, and it is invisible in any
  *                                  simpler model.
  *  - `crossbarSingleOutstanding` : model `macqd700-soc/rtl/soc/axi_xbar.v` as it
  *                                  EXISTS TODAY -- exactly ONE outstanding read and
  *                                  ONE outstanding write per master port. Reads: the
  *                                  per-master `rs_state` FSM
  *                                  (`RS_IDLE`/`RS_WAIT_SLV_AR`/`RS_WAIT_R` enum,
  *                                  `axi_xbar.v:2722-2724`) only re-arms `RS_IDLE` after
  *                                  the slave's RLAST (`RS_WAIT_SLV_AR` -> `RS_WAIT_R`
  *                                  -> `RS_IDLE`, `:3219-3316`, IDLE-on-RLAST at
  *                                  `:3285-3286`) -- one live AR per master port at a
  *                                  time. Writes: `sw_owned` locks a slave's whole
  *                                  AW->B sequence to one master, and combined with the
  *                                  single per-master `ws_state` FSM this is 1
  *                                  outstanding write per master AND per slave
  *                                  (`:1449-1473`; the same comment block records that
  *                                  AW pipelining was analysed 2026-07-21 and
  *                                  deliberately NOT implemented). VERIFIED against
  *                                  `macqd700-soc` commit `6bd833d0` (2026-07-30) --
  *                                  re-check these line numbers if that repo's
  *                                  `axi_xbar.v` moves again; the file has already
  *                                  shifted once since an earlier citation of this
  *                                  comment was written. This is the configuration
  *                                  against which slice D1's benefit must be shown to
  *                                  be REAL rather than asserted.
  *  - `checkIdUnique`             : assert if the DUT presents an AR/AW whose ID is
  *                                  already outstanding. Leave TRUE from slice V2 on.
  *  - `injectBusErrors`           : task #189/#211 behaviour, unchanged semantics --
  *                                  DECERR for an address outside `AxiMemModel.decoded`. */
case class AxiMemModelConfig(
  rspMode: AxiRspMode.Value = AxiRspMode.InOrder,
  latency: L2LatencyModel = L2LatencyModel(),
  idBusyBlock: Boolean = false,
  crossbarSingleOutstanding: Boolean = false,
  maxLen: Int = 255,
  incrOnly: Boolean = true,
  checkProtocol: Boolean = true,
  checkIdUnique: Boolean = true,
  injectBusErrors: Boolean = false,
  maxPendingBeats: Int = 8,
  bQueueDepth: Int = 4
)

/** Free-running simulation cycle counter shared by the read and write engines of one
  * model instance. `BehavioralMem.scala` has NO cycle counter at all (design doc
  * §1.6), which is why it can have no latency model. */
class SimCycleCounter(cd: ClockDomain) {
  private var c: Long = 0L
  cd.onSamplings { c += 1 }
  def now: Long = c
}

/** Observability for the measurement work (design doc §8.3): there are currently NO
  * miss/stall counters anywhere in the tree. Read-only from the testbench. */
class AxiMemStats(idWidth: Int) {
  val nIds = 1 << idWidth
  val arCount = Array.fill(nIds)(0L)
  val awCount = Array.fill(nIds)(0L)
  var rBeats = 0L
  var wBeats = 0L
  var l2Hits = 0L
  var l2Misses = 0L
  var l2SecondaryMerges = 0L
  var maxConcurrentReads = 0
  def totalAr: Long = arCount.sum
  def totalAw: Long = awCount.sum
  def reset(): Unit = {
    for (i <- 0 until nIds) { arCount(i) = 0L; awCount(i) = 0L }
    rBeats = 0L; wBeats = 0L; l2Hits = 0L; l2Misses = 0L; l2SecondaryMerges = 0L
    maxConcurrentReads = 0
  }
}

/** Always-on AXI4 protocol checker. Every violation is BOTH recorded (so a test can
  * assert on the list) and raised as a hard `assert` (so a violation cannot be
  * silently ignored by a test that forgets to look).
  *
  * Checks (design doc §8.1 item 5, §7.1 "Mandatory assertions"):
  *   - an AR/AW ID that is ALREADY outstanding (the ID-uniqueness rule that makes
  *     per-ID routing sound; also exactly what the L2's id_busy_c CAM enforces);
  *   - burst type: INCR only (the L2 SLVERRs FIXED/WRAP, l2c_ctrl.v:26-37,136-139);
  *   - `len` within the configured maximum;
  *   - an R beat driven for an ID with NO outstanding transaction (a model self-check
  *     -- if this ever fires the MODEL is broken, not the DUT);
  *   - per-ID beat count: exactly `len+1` beats and exactly one `r.last`, at the end;
  *   - W beat count matches the AW's `len+1`, with `w.last` on exactly the last beat;
  *   - WSTRB sanity: no strobe bit set beyond the beat's byte width.
  *
  * NOTE: this class is specified in full by Task V1.5 of the MSHR implementation plan
  * (`docs/superpowers/plans/2026-07-30-mshr-multi-outstanding-implementation-plan.md`
  * §"Task V1.5"). It is pulled forward into this file (verbatim, not reinvented)
  * because `AxiReadEngine`/`AxiWriteEngine`/`AxiMemModel` below reference it directly
  * and this file must compile and its own spec must pass in isolation (Task V1.2's own
  * acceptance criteria). Task V1.5 is expected to find this class already present and
  * add only its own `AxiProtocolCheckerSpec.scala` -- see this task's report for the
  * full note. */
class AxiProtocolChecker(cfg: AxiMemModelConfig, busConfig: Axi4Config) {
  private val _violations = scala.collection.mutable.ArrayBuffer[String]()
  def violations: Seq[String] = _violations.toSeq

  private def fail(msg: String, cycle: Long): Unit = {
    val full = s"[AXI-PROTOCOL @cyc$cycle] $msg"
    _violations += full
    if (cfg.checkProtocol) assert(false, full)
  }

  // per-ID expected/observed read beat bookkeeping
  private val rExpected = scala.collection.mutable.Map[Int, Int]()
  private val rSeen     = scala.collection.mutable.Map[Int, Int]()
  private val wExpected = scala.collection.mutable.Queue[(Int, Int)]()   // (id, len+1)
  private var wSeen = 0

  def onAr(id: Int, addr: BigInt, size: Int, len: Int, burst: Int,
           idAlreadyLive: Boolean, cycle: Long): Unit = {
    if (cfg.checkIdUnique && idAlreadyLive)
      fail(f"AR id=$id presented while ALREADY outstanding (addr=0x${addr}%x)", cycle)
    if (cfg.incrOnly && burst != 1)
      fail(s"AR id=$id burst=$burst is not INCR (the L2 SLVERRs FIXED/WRAP)", cycle)
    if (len > cfg.maxLen)
      fail(s"AR id=$id len=$len exceeds maxLen=${cfg.maxLen}", cycle)
    rExpected(id) = len + 1
    rSeen(id) = 0
  }

  def onAw(id: Int, addr: BigInt, size: Int, len: Int, burst: Int,
           idAlreadyLive: Boolean, cycle: Long): Unit = {
    if (cfg.checkIdUnique && idAlreadyLive)
      fail(f"AW id=$id presented while ALREADY outstanding (addr=0x${addr}%x)", cycle)
    if (cfg.incrOnly && burst != 1)
      fail(s"AW id=$id burst=$burst is not INCR", cycle)
    if (len > cfg.maxLen)
      fail(s"AW id=$id len=$len exceeds maxLen=${cfg.maxLen}", cycle)
    wExpected.enqueue((id, len + 1))
  }

  def onRBeat(id: Int, isLast: Boolean, idOutstanding: Boolean, cycle: Long): Unit = {
    if (!idOutstanding)
      fail(s"R beat driven for id=$id with NO outstanding AR (MODEL BUG, not DUT)", cycle)
    val seen = rSeen.getOrElse(id, 0) + 1
    rSeen(id) = seen
    val exp = rExpected.getOrElse(id, -1)
    if (exp >= 0) {
      if (isLast && seen != exp) fail(s"R id=$id last on beat $seen but len+1=$exp", cycle)
      if (!isLast && seen >= exp) fail(s"R id=$id beat $seen exceeds len+1=$exp without last", cycle)
    }
  }

  def onWBeat(id: Int, wlast: Boolean, engineSaysLast: Boolean,
              strb: BigInt, bytesPerBeat: Int, cycle: Long): Unit = {
    wSeen += 1
    if (wlast != engineSaysLast)
      fail(s"W id=$id w.last=$wlast but the AW's len says last=$engineSaysLast (beat $wSeen)", cycle)
    if (engineSaysLast) { if (wExpected.nonEmpty) wExpected.dequeue(); wSeen = 0 }
    val overflow = strb >> bytesPerBeat
    if (overflow != 0) fail(f"W id=$id strb=0x${strb}%x has bits beyond $bytesPerBeat bytes", cycle)
  }
}

/** One R beat, fully resolved at AR-accept time except for the data itself.
  *
  * `resp`/`last` are frozen at AR-accept (exactly as the legacy model did,
  * `BehavioralMem.scala:116-117`); only the DATA is read lazily at drive time
  * (`:115`) so a write that lands between AR-accept and beat-drive is observed.
  * `readyAt` is the earliest cycle this beat may be driven (latency model).
  * `installLine` is `Some(line)` exactly on the LAST beat of an AR that
  * `latencyFor` classified as the PRIMARY fill for a brand-new miss -- the L2 line
  * becomes resident (`l2Lines`) only when THIS beat is actually driven, never
  * earlier (see `latencyFor`'s doc comment for why AR-accept time was wrong). */
private case class RBeat(id: Int, base: Long, bytes: Int, bad: Boolean,
                         isLast: Boolean, seq: Long, var readyAt: Long,
                         installLine: Option[Long] = None)

/** Read side of the model. Written against the raw `ar`/`r` streams (not `Axi4` /
  * `Axi4ReadOnly`) so the SAME implementation serves both master shapes -- this is
  * the consolidation the legacy `Axi4ReadOnlyBehavioralAgent` (an acknowledged
  * copy-paste, `BehavioralMem.scala:250-259`) exists to work around. */
class AxiReadEngine(ar: Stream[Axi4Ar], r: Stream[Axi4R], busConfig: Axi4Config,
                    cd: ClockDomain, mem: SparseMemory, cfg: AxiMemModelConfig,
                    clk: SimCycleCounter, stats: AxiMemStats,
                    checker: AxiProtocolChecker) {

  private val nIds = if (busConfig.useId) (1 << busConfig.idWidth) else 1
  private val queues = Array.fill(nIds)(mutable.Queue[RBeat]())
  // liveArIds: an ID with at least one un-driven beat. Drives BOTH the `id_busy_c`
  // front-door block and the DUT-side ID-uniqueness assert.
  private val liveArIds = mutable.Set[Int]()
  private var seqCounter = 0L
  // The ID whose burst is currently mid-flight on R. Cleared on `last`. Enforces
  // "no cross-ID interleaving INSIDE a burst" for every mode except IllegalInterleave.
  private var lockedId: Int = -1
  // L2 model state: lines currently resident, and in-flight primary misses.
  private val l2Lines = mutable.Set[Long]()
  private val l2InFlightLines = mutable.Map[Long, Long]()   // line -> readyAt

  private def totalPendingBeats: Int = queues.map(_.size).sum

  private def readBeatData(base: Long, bytes: Int): BigInt = {
    var v = BigInt(0)
    for (i <- 0 until bytes) v = v | (BigInt(mem.read(base + i).toInt & 0xff) << (8 * i))
    v
  }

  /** Latency for the burst starting at `addr` covering `nBytes`, in cycles from now,
    * plus (on the PRIMARY-miss path only) the line that must become resident once
    * that primary's own fill actually completes. Two-tier, L2-faithful (design doc
    * §3.1) -- see `L2LatencyModel`.
    *
    * IMPORTANT: a line must NOT become "resident" (`l2Lines`) until its fill has
    * actually elapsed -- the caller installs it at LAST-BEAT-DRIVE time (see
    * `RBeat.installLine`), never here at AR-accept time. Installing here would let
    * a same-line AR arriving on the very next cycle (which is the ONLY way two ARs
    * for the same line can ever be ordered, since a single AXI AR channel accepts
    * at most one transaction per cycle) see the still-in-flight line as already
    * resident and score an instant hit -- which would make the secondary-merge
    * tier below (`l2InFlightLines`) permanently unreachable. */
  private def latencyFor(addr: Long, nBytes: Int): (Long, Option[Long]) = {
    val L = cfg.latency
    if (!L.enabled) return (0L, None)
    val line = addr & ~(L.lineBytes.toLong - 1)
    if (l2Lines.contains(line)) { stats.l2Hits += 1; (L.hitCycles.toLong, None) }
    else l2InFlightLines.get(line) match {
      case Some(t) =>
        // Same-line secondary merge onto an already-in-flight primary miss
        // (`l2c_mshr.v:1-7,131`): shares the SAME completion time, no new fill.
        // The line's install is the PRIMARY's responsibility (`Some(line)` only on
        // the miss branch below), not this secondary's.
        stats.l2SecondaryMerges += 1
        (math.max(t - clk.now, L.hitCycles.toLong), None)
      case None =>
        stats.l2Misses += 1
        // NO critical-word-first: the requester waits for the whole line.
        val t = L.dramCycles.toLong + L.fillFixedCycles.toLong +
                (L.lineBytes / (busConfig.dataWidth / 8)).toLong
        l2InFlightLines(line) = clk.now + t
        (t, Some(line))
    }
  }

  /** AR acceptance policy: capacity, then `id_busy_c`, then the crossbar model, then
    * the L2's own MSHR allocation back-pressure. */
  private def arAcceptable(): Boolean = {
    if (totalPendingBeats >= cfg.maxPendingBeats) return false
    if (cfg.crossbarSingleOutstanding && liveArIds.nonEmpty) return false
    if (cfg.latency.enabled && l2InFlightLines.size >= cfg.latency.l2Mshrs) return false
    if (cfg.idBusyBlock) {
      // Reads the ID the DUT is PRESENTING this cycle. `ready` is therefore a
      // registered function of the previous cycle's presented ID -- a faithful-enough
      // model of a slave that takes a cycle to decide, and the same shape the legacy
      // `StreamReadyRandomizer` capacity gate already had.
      if (ar.valid.toBoolean) {
        val presented = if (busConfig.useId) ar.id.toInt else 0
        if (liveArIds.contains(presented)) return false
      }
    }
    true
  }

  val arMonitor = StreamMonitor(ar, cd) { a =>
    val id    = if (busConfig.useId) a.id.toInt else 0
    val size  = if (busConfig.useSize) a.size.toInt else log2Up(busConfig.dataWidth / 8)
    val len   = if (busConfig.useLen) a.len.toInt else 0
    val burst = if (busConfig.useBurst) a.burst.toInt else 1
    val addr  = a.addr.toBigInt
    checker.onAr(id, addr, size, len, burst, liveArIds.contains(id), clk.now)
    stats.arCount(id) += 1
    liveArIds += id
    if (liveArIds.size > stats.maxConcurrentReads) stats.maxConcurrentReads = liveArIds.size
    val bpb   = 1 << size
    val (lat, installLine) = latencyFor(addr.toLong, bpb * (len + 1))
    val L     = cfg.latency
    for (beat <- 0 to len) {
      val base = (burst match {
        case 1 => addr + BigInt(bpb) * beat   // INCR
        case _ => addr                        // FIXED
      }).toLong
      val bad = cfg.injectBusErrors && !AxiMemModel.decoded(base)
      val isLast = beat == len
      seqCounter += 1
      queues(id) += RBeat(id, base, bpb, bad, isLast, seqCounter,
                          clk.now + lat + (if (L.enabled) beat.toLong * L.interBeatGap else 0L),
                          if (isLast) installLine else None)
    }
  }

  /** Pick the next beat to drive, honouring the configured ordering mode. Returns
    * None when nothing is eligible this cycle (latency not yet elapsed, or empty). */
  private def pick(): Option[RBeat] = {
    def eligible(q: mutable.Queue[RBeat]): Boolean =
      q.nonEmpty && q.head.readyAt <= clk.now
    // Mid-burst lock: every mode except IllegalInterleave must finish the burst it
    // started before serving another ID (AXI4 forbids cross-ID interleaving inside
    // a burst).
    if (lockedId >= 0 && cfg.rspMode != AxiRspMode.IllegalInterleave) {
      return if (eligible(queues(lockedId))) Some(queues(lockedId).dequeue()) else None
    }
    val candidates = (0 until nIds).filter(i => eligible(queues(i)))
    if (candidates.isEmpty) return None
    val chosen = cfg.rspMode match {
      case AxiRspMode.InOrder =>
        // One global FIFO across every ID: the globally OLDEST enqueued beat.
        candidates.minBy(i => queues(i).head.seq)
      case AxiRspMode.Reordered =>
        candidates(simRandom.nextInt(candidates.size))
      case AxiRspMode.Chaos =>
        // Maximally adversarial: always the NEWEST eligible ID.
        candidates.maxBy(i => queues(i).head.seq)
      case AxiRspMode.IllegalInterleave =>
        candidates(simRandom.nextInt(candidates.size))
    }
    Some(queues(chosen).dequeue())
  }

  val rDriver = StreamDriver(r, cd) { _ =>
    pick() match {
      case None => false
      case Some(b) =>
        checker.onRBeat(b.id, b.isLast, liveArIds.contains(b.id), clk.now)
        // A completed fill installs the line in the model's L2 image -- NOW, at the
        // moment its last beat is actually driven, not back at AR-accept time (see
        // `latencyFor`'s doc comment). Skipped on a bus-error response, matching the
        // real L2's `fill_err` skip of `inst_valid` (`l2c_mshr.v` S_INSTALL).
        if (!b.bad) b.installLine.foreach { line =>
          l2Lines += line
          l2InFlightLines.remove(line)
        }
        if (busConfig.useId)   r.id   #= b.id
        r.data #= (if (b.bad) BigInt(0) else readBeatData(b.base, b.bytes))
        if (busConfig.useResp) r.resp #= (if (b.bad) 3 else 0)   // DECERR : OKAY
        if (busConfig.useLast) r.last #= b.isLast
        stats.rBeats += 1
        if (b.isLast) { liveArIds -= b.id; lockedId = -1 } else { lockedId = b.id }
        true
    }
  }

  val arDriver = StreamReadyRandomizer(ar, cd, () => arAcceptable())
}

/** Write side. This is a FAITHFUL port of `BehavioralMemAgent`'s hand-rolled write
  * path (`BehavioralMem.scala:127-234`), including its central invariant:
  *
  *   *** WRITE-BEFORE-B: the bytes are applied to `mem` at the moment a W beat is
  *   paired with its AW, and a burst's B closure is enqueued ONLY after every byte
  *   of that burst has been written. ***
  *
  * Any deferred-apply latency model that broke this would re-introduce the
  * harness-induced store->load race the original comment exists to prevent (design
  * doc §8.1 item 6). Latency is therefore applied to WHEN B IS DRIVEN, never to when
  * the bytes land. */
class AxiWriteEngine(aw: Stream[Axi4Aw], w: Stream[Axi4W], b: Stream[Axi4B],
                     busConfig: Axi4Config, cd: ClockDomain, mem: SparseMemory,
                     cfg: AxiMemModelConfig, clk: SimCycleCounter,
                     stats: AxiMemStats, checker: AxiProtocolChecker) {

  private case class AwState(addr: BigInt, size: Int, len: Int, burst: Int, id: Int, var beat: Int)
  private case class BRsp(id: Int, bad: Boolean, readyAt: Long)

  private val nIds = if (busConfig.useId) (1 << busConfig.idWidth) else 1
  private val awQueue = mutable.Queue[AwState]()
  private val wQueue  = mutable.Queue[(BigInt, BigInt, Boolean)]()
  private val bQueue  = Array.fill(nIds)(mutable.Queue[BRsp]())
  private val liveAwIds = mutable.Set[Int]()
  private var qPending = 0

  // Task P4.7: a ONE-SHOT non-OKAY (DECERR) response for the NEXT beat whose target
  // address matches exactly, independent of `AxiMemModel.decoded`. Needed for
  // eviction-writeback fault injection: the SAME physical address must succeed on an
  // earlier READ (the line's original load/warm, which must land resident+dirty for
  // there to be anything to evict) and then fail on a LATER WRITE (its own eviction) --
  // a purely address-decode-based split (`injectBusErrors`) can never produce that,
  // since `decoded(addr)` is a pure function of the address and applies identically to
  // both directions. Mirrors `IcacheSim.scala`'s `BeatFaultAxiResponder.armBeatFault`
  // rationale for the read side, ported here to the write-completion (B) side.
  private var armedWriteFault: Option[Long] = None
  def armWriteFault(addr: Long): Unit = armedWriteFault = Some(addr)

  private def bytesPerBeat = busConfig.dataWidth / 8

  private def applyBeat(st: AwState, data: BigInt, strb: BigInt, bad: Boolean): Unit = {
    val bpb  = 1 << st.size
    val base = (st.burst match { case 1 => st.addr + BigInt(bpb) * st.beat; case _ => st.addr }).toLong
    if (!bad) {
      for (i <- 0 until bytesPerBeat) {
        if (((strb >> i) & 1) == 1) {
          val byte = ((data >> (8 * i)) & 0xff).toInt.toByte
          mem.write((base + i).toLong, byte)
        }
      }
    }
    st.beat += 1
  }

  private def update(): Unit = {
    while (awQueue.nonEmpty && wQueue.nonEmpty) {
      val st = awQueue.head
      val (data, strb, wlast) = wQueue.dequeue()
      val isLast = st.beat == st.len
      checker.onWBeat(st.id, wlast, isLast, strb, bytesPerBeat, clk.now)
      val bpb  = 1 << st.size
      val base = (st.burst match { case 1 => st.addr + BigInt(bpb) * st.beat; case _ => st.addr }).toLong
      val armedBad = armedWriteFault.contains(base)
      if (armedBad) armedWriteFault = None   // one-shot: consumed on match
      val bad  = (cfg.injectBusErrors && !AxiMemModel.decoded(base)) || armedBad
      applyBeat(st, data, strb, bad)
      stats.wBeats += 1
      if (isLast) {
        awQueue.dequeue()
        qPending += 1
        val lat = if (cfg.latency.enabled) cfg.latency.hitCycles.toLong else 0L
        bQueue(st.id) += BRsp(st.id, bad, clk.now + lat)
      }
    }
  }

  private def awAcceptable(): Boolean = {
    if (qPending >= cfg.bQueueDepth) return false
    if (cfg.crossbarSingleOutstanding && (liveAwIds.nonEmpty || awQueue.nonEmpty)) return false
    if (cfg.idBusyBlock && aw.valid.toBoolean) {
      val presented = if (busConfig.useId) aw.id.toInt else 0
      if (liveAwIds.contains(presented)) return false
    }
    true
  }

  val awMonitor = StreamMonitor(aw, cd) { a =>
    val id    = if (busConfig.useId) a.id.toInt else 0
    val size  = if (busConfig.useSize) a.size.toInt else log2Up(busConfig.dataWidth / 8)
    val len   = if (busConfig.useLen) a.len.toInt else 0
    val burst = if (busConfig.useBurst) a.burst.toInt else 1
    checker.onAw(id, a.addr.toBigInt, size, len, burst, liveAwIds.contains(id), clk.now)
    stats.awCount(id) += 1
    liveAwIds += id
    awQueue += AwState(a.addr.toBigInt, size, len, burst, id, 0)
    update()
  }

  val wMonitor = StreamMonitor(w, cd) { x =>
    val last = if (busConfig.useLast) x.last.toBoolean else false
    wQueue += ((x.data.toBigInt, x.strb.toBigInt, last))
    update()
  }

  val bDriver = StreamDriver(b, cd) { _ =>
    val ready = (0 until nIds).filter(i => bQueue(i).nonEmpty && bQueue(i).head.readyAt <= clk.now)
    if (ready.isEmpty) false
    else {
      val id = cfg.rspMode match {
        case AxiRspMode.InOrder => ready.minBy(i => i)   // deterministic; matches legacy single-ID use
        case _                  => ready(simRandom.nextInt(ready.size))
      }
      val rsp = bQueue(id).dequeue()
      if (busConfig.useId)   b.id   #= rsp.id
      if (busConfig.useResp) b.resp #= (if (rsp.bad) 3 else 0)
      liveAwIds -= rsp.id
      qPending -= 1
      true
    }
  }

  val awDriver = StreamReadyRandomizer(aw, cd, () => awAcceptable())
  val wDriver  = StreamReadyRandomizer(w,  cd, () => qPending < cfg.bQueueDepth)
}

class AxiMemModel private (busConfig: Axi4Config, cd: ClockDomain,
                           val cfg: AxiMemModelConfig, sharedMem: SparseMemory) {
  val mem   = if (sharedMem != null) sharedMem else SparseMemory()
  val clk   = new SimCycleCounter(cd)
  val stats = new AxiMemStats(if (busConfig.useId) busConfig.idWidth else 1)
  val checker = new AxiProtocolChecker(cfg, busConfig)
  private[sim] var readEngine:  AxiReadEngine  = null
  private[sim] var writeEngine: AxiWriteEngine = null

  def pokeByte(addr: Long, value: Int): Unit = mem.write(addr, value.toByte)
  def peekByte(addr: Long): Int             = mem.read(addr).toInt & 0xff
  def poke128(addr: Long, data: BigInt): Unit =
    for (i <- 0 until 16) pokeByte(addr + i, ((data >> (8 * i)) & 0xff).toInt)
  def peek128(addr: Long): BigInt =
    (0 until 16).foldLeft(BigInt(0)) { (acc, i) => acc | (BigInt(peekByte(addr + i)) << (8 * i)) }

  /** Task P4.7: see `AxiWriteEngine.armWriteFault` -- forces the NEXT write beat that
    * targets `addr` exactly to DECERR, independent of `decoded(addr)`. Only valid on a
    * model attached via `attachFull` (a read-only attachment has no write engine). */
  def armWriteFault(addr: Long): Unit = writeEngine.armWriteFault(addr)
}

object AxiMemModel {
  val DATA_BITS = 128
  val BYTES     = DATA_BITS / 8

  /** The D-side / walker AXI config, unchanged from `BehavioralMem.axiConfig`. */
  def axiConfig(dataWidth: Int = DATA_BITS, idWidth: Int = 4) = Axi4Config(
    addressWidth = 32, dataWidth = dataWidth, idWidth = idWidth,
    useId = true, useRegion = false, useBurst = true, useLock = false,
    useCache = false, useSize = true, useQos = false, useLen = true,
    useLast = true, useResp = true, useProt = false, useStrb = true)

  /** Task #189/#211 bus-error decode, MOVED VERBATIM from `BehavioralMem.decoded`.
    *
    * NOTE FOR REVIEWERS: this is a *test-harness* address map. It models the SoC the
    * ported corpus's own headers describe, so that a probe to genuinely-unmapped
    * space DECERRs instead of silently reading zeros. It lives entirely on the
    * simulation side and NOTHING in `src/main/scala` may ever consult anything of
    * this shape -- see GC1. */
  def decoded(addr: Long): Boolean = {
    val a = addr & 0xffffffffL
    val topNibble = (a >>> 28) & 0xf
    (topNibble == 0x0L) || (topNibble == 0x4L) || (topNibble == 0x5L) || (topNibble == 0x6L) ||
      ((a >>> 16) == 0xffffL)
  }

  def attachFull(axi: Axi4, cd: ClockDomain,
                 cfg: AxiMemModelConfig = AxiMemModelConfig(),
                 sharedMem: SparseMemory = null): AxiMemModel = {
    val m = new AxiMemModel(axi.config, cd, cfg, sharedMem)
    m.readEngine  = new AxiReadEngine(axi.ar, axi.r, axi.config, cd, m.mem, cfg, m.clk, m.stats, m.checker)
    m.writeEngine = new AxiWriteEngine(axi.aw, axi.w, axi.b, axi.config, cd, m.mem, cfg, m.clk, m.stats, m.checker)
    m
  }

  def attachReadOnly(axi: Axi4ReadOnly, cd: ClockDomain,
                     cfg: AxiMemModelConfig = AxiMemModelConfig(),
                     sharedMem: SparseMemory = null): AxiMemModel = {
    val m = new AxiMemModel(axi.config, cd, cfg, sharedMem)
    m.readEngine = new AxiReadEngine(axi.ar, axi.r, axi.config, cd, m.mem, cfg, m.clk, m.stats, m.checker)
    m
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // *** TWO INCOMPATIBLE PROGRAM-IMAGE CONVENTIONS EXIST IN THIS TREE. ***
  // Do NOT "unify" them -- both are load-bearing and every passing test depends on
  // the one it uses. Documented at `PortedTestRunner.scala:109-115`.
  //
  //  - I-SIDE (instruction fetch): each 16-bit big-endian opword is stored
  //    LOW-BYTE-FIRST, i.e. BYTE-SWAPPED per word relative to the raw image. Every
  //    existing `attachProgram` clone (`ExecuteLockStepSpec.scala:379-395`,
  //    `FuzzDut.scala:283-294`, `IpcBenchSpec.scala:289-300`) and
  //    `IcacheSim.attachMemoryWithWords` uses this.
  //  - D-SIDE (data): PLAIN byte-at-address, no swap. `BehavioralMemAgent` and every
  //    store/load ported test use this.
  //
  // `PortedTestRunner` deliberately seeds the SAME image into BOTH views, each in its
  // own convention, so a PC-relative literal-pool read sees the same bytes the
  // I-cache fetched as code.
  // ─────────────────────────────────────────────────────────────────────────────

  /** I-side convention: byte-swapped per 16-bit word. */
  def loadProgramIFetch(mem: SparseMemory, loadAddr: Long, bytes: Vector[Int]): Unit = {
    val nWords = bytes.length / 2
    for (i <- 0 until nWords) {
      val w = ((bytes(2 * i) & 0xff) << 8) | (bytes(2 * i + 1) & 0xff)   // big-endian word
      mem.write(loadAddr + 2 * i,     (w & 0xff).toByte)
      mem.write(loadAddr + 2 * i + 1, ((w >> 8) & 0xff).toByte)
    }
  }

  /** D-side convention: plain byte-at-address, no swap. */
  def loadProgramData(mem: SparseMemory, loadAddr: Long, bytes: Vector[Int]): Unit =
    for (i <- bytes.indices) mem.write(loadAddr + i, bytes(i).toByte)
}

/** The configuration sweep every measurement task in this plan reports against
  * (design doc §8.3). `N_MSHR` is a DUT-side build parameter and is swept separately;
  * these are the MEMORY-side axes: latency tier x response mode x crossbar.
  *
  * `dramCycles` is UNMEASURED (design doc §3.4 U1) -- the two values below are a
  * deliberate bracket, not a claim. No performance statement may quote one of them
  * as "the" DRAM latency. */
object L2Sweeps {
  val zeroLatency   = AxiMemModelConfig()
  val l2HitOnly     = AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 0))
  val l2DramFast    = AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 20))
  val l2DramSlow    = AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 60))
  val todaysCrossbar = AxiMemModelConfig(
    latency = L2LatencyModel(enabled = true, dramCycles = 20),
    idBusyBlock = true, crossbarSingleOutstanding = true)
  val chaosDram = AxiMemModelConfig(
    rspMode = AxiRspMode.Chaos,
    latency = L2LatencyModel(enabled = true, dramCycles = 20), idBusyBlock = true)

  val standard: Seq[(String, AxiMemModelConfig)] = Seq(
    "zero-latency"       -> zeroLatency,
    "l2-hit-only"        -> l2HitOnly,
    "l2+dram(20)"        -> l2DramFast,
    "l2+dram(60)"        -> l2DramSlow,
    "todays-crossbar"    -> todaysCrossbar,
    "chaos+dram(20)"     -> chaosDram)
}
