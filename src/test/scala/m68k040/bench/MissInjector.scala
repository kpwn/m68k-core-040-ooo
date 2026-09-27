package m68k040.bench

import spinal.core.sim._

import java.security.MessageDigest
import java.nio.file.{Files, Paths}

import scala.collection.mutable

/** SIM-ONLY cache-miss injection + memory-level-parallelism instrumentation.
  *
  * ── WHY ──────────────────────────────────────────────────────────────────────
  * Every kernel in the IPC corpus is cache-RESIDENT. Measured, on this harness:
  * `strcmp` takes ZERO D-cache misses; `dhrystone-x0-cb` takes ~1.2 D-misses and
  * ~0.2 I-misses per thousand instructions. The BOARD, running a real OS
  * workload, takes 24.4 D-misses and 32.2 I-misses per kilo-instruction -- about
  * twenty times more. So hit-under-miss, speculative load wakeup, out-of-order
  * load issue and prefetch all have nothing to work on in this bench, and every
  * sim verdict on them is suspect in the same direction (understated).
  *
  * ── MECHANISM: A SIM-SIDE LINE STEALER, NOT AN RTL HIT-GATE ──────────────────
  * There is deliberately NO RTL in this feature. Injection works by clearing the
  * VALID bit of a resident line from the testbench, which is exactly what the
  * architected CINV maintenance operation does. The next access to that line then
  * takes an ORDINARY cold miss through the cache's own, already-proven miss path:
  * no new hit-gate, no new refill mode, no new state, and nothing in the D-cache's
  * or I-cache's critical cone. Two consequences matter:
  *
  *   1. ZERO SYNTH COST IS STRUCTURAL, NOT ARGUED. `src/main` is untouched, so the
  *      emitted netlist is byte-identical by construction (see the report).
  *   2. ARCHITECTURALLY INERT BY CONSTRUCTION. A line is stolen only when it is
  *      VALID and CLEAN, so memory already holds exactly the bytes the cache held.
  *      The refill returns the same data, later. DIRTY lines are never stolen --
  *      stealing one would silently revert the store that dirtied it, which is the
  *      bug this design avoids by never having the option.
  *
  * Timing of the steal is what makes the rate stable at ~100% of eligible accesses
  * rather than alternating hit/miss. A demand miss ends with the cache RE-LAUNCHING
  * the access against the freshly-installed line -- D-cache REPLAY, I-cache
  * `s0Replay` -- so the FIRST S1 hit a line sees after a refill is that replay, not a
  * new demand access. Stealing on every eligible S1 hit therefore leaves the line
  * invalid again before the next real access arrives, and cannot livelock: the steal
  * strictly FOLLOWS the hit that resolved the refill.
  *
  * ── SELECTION: ADDRESS HASH, NOT PER-ACCESS RNG ──────────────────────────────
  * Eligibility is a pure function of the LINE address (plus a salt and an epoch),
  * so within an epoch the same lines miss every time and a loop genuinely re-misses
  * -- the behaviour a per-access coin flip cannot produce. The epoch term
  * (`IPC_INJ_EPOCH` cycles, default 512) rotates WHICH lines are eligible over
  * time. That is not cosmetic: the kernels have working sets of a handful of lines,
  * so a fixed hash quantises the achievable rate to 0%, 25%, 50%... Rotating the
  * eligible subset makes the rate a continuous knob while keeping it deterministic
  * and re-missable inside each epoch.
  *
  * ── KNOBS ────────────────────────────────────────────────────────────────────
  *   IPC_INJ_D=<0..100>   percent of the D-cache line-address space forced to miss
  *   IPC_INJ_I=<0..100>   percent of the I-cache line-address space forced to miss
  *   IPC_INJ_SEED=<int>   hash salt (default 1); changes WHICH lines, not how many
  *   IPC_INJ_EPOCH=<int>  eligibility rotation period in cycles (default 512)
  *   IPC_MISS_STATS=1     report miss rates / MLP / stall split with NO injection
  *
  * Everything is OFF unless one of those is set, and `onCycle` returns on a single
  * boolean when off -- so the default `IpcBenchSpec` run is bit-identical.
  *
  * ── KNOWN, DELIBERATE LIMITS (do not read a number here as more than it is) ──
  *   - STORE-DIRTIED LINES ARE IMMUNE. A copyback kernel's destination buffer goes
  *     dirty and stays resident, so D-injection reaches the LOAD stream and not the
  *     store stream. The levers this exists to test are load-latency levers, but a
  *     store-miss lever cannot be measured with this knob.
  *   - THE D-CACHE'S EARLY-PROBE ENTRIES LEAK. A probe that already latched its line
  *     serves that line after the steal (same, correct bytes). That costs rate, not
  *     soundness, and it is why the achieved rate is always MEASURED and reported
  *     rather than assumed equal to the requested percentage.
  *   - STEALING A WAY ALSO PERTURBS REPLACEMENT. A freed way changes which line the
  *     next victim is, so a few misses are collateral rather than injected. Again:
  *     measured, not assumed.
  */
object MissInjector {

  /** D-cache geometry (DcachePlugin: 8 KiB, 16 B lines, 4 ways). */
  private val dWays = 4
  private val dOffBits = 4
  private val dSetBits = 7
  /** I-cache geometry (CacheGeometry.l1i040: 16 KiB, 64 B lines, 4 ways). */
  private val iWays = 4
  private val iOffBits = 6
  private val iSetBits = 6

  private def envInt(name: String, dflt: Int): Int =
    sys.env.get(name).filter(_.nonEmpty).map(_.trim.toInt).getOrElse(dflt)

  /** Requested injection percentages. `var`, not `val`, so `MissInjectionSweepSpec`
    * can walk a CURVE inside one Verilator build instead of paying an elaboration per
    * sweep point. `IpcBenchSpec` and every other suite just take the env defaults. */
  var dPct: Int = envInt("IPC_INJ_D", 0)
  var iPct: Int = envInt("IPC_INJ_I", 0)
  var salt: Int = envInt("IPC_INJ_SEED", 1)
  /** A label for the current sweep point, carried into every `Stats` row. */
  var tag: String = ""
  val epochCycles: Int = math.max(1, envInt("IPC_INJ_EPOCH", 512))
  val statsOnly: Boolean = sys.env.get("IPC_MISS_STATS").contains("1")
  def injecting: Boolean = dPct > 0 || iPct > 0
  def enabled: Boolean = injecting || statsOnly

  require(dPct >= 0 && dPct <= 100, s"IPC_INJ_D=$dPct out of 0..100")
  require(iPct >= 0 && iPct <= 100, s"IPC_INJ_I=$iPct out of 0..100")

  /** Point the injector at a new rate. Called between sweep points only -- never
    * inside a run, so a run is always a single, reproducible configuration. */
  def configure(d: Int, i: Int, label: String = ""): Unit = {
    require(d >= 0 && d <= 100 && i >= 0 && i <= 100, s"injection rate out of 0..100: D=$d I=$i")
    dPct = d; iPct = i
    tag = if (label.nonEmpty) label else f"D$d%d/I$i%d"
  }

  def label: String =
    if (!enabled) "off"
    else s"D=${dPct}% I=${iPct}% salt=$salt epoch=${epochCycles}cyc" +
      (if (!injecting) " (stats only)" else "")

  /** SplitMix64-style finalizer. Deterministic, and it mixes the low line-address
    * bits that a set-index-shaped hash would throw away. */
  private def mix(x: Long): Long = {
    var h = x * 0x9E3779B97F4A7C15L
    h ^= h >>> 30; h *= 0xBF58476D1CE4E5B9L
    h ^= h >>> 27; h *= 0x94D049BB133111EBL
    h ^= h >>> 31
    h
  }

  private val Denom = 1 << 16

  private def eligible(lineAddr: Long, epoch: Long, pct: Int): Boolean = {
    if (pct <= 0) return false
    if (pct >= 100) return true
    val h = mix(lineAddr * 0x100000001L + epoch * 0x2545F4914F6CDD1DL + salt)
    ((h >>> 41) & (Denom - 1)) < (pct.toLong * Denom / 100)
  }

  /** Per-kernel measurement, published for the suite to print after the run. */
  final case class Stats(
      point: String,
      kernel: String,
      windowCycles: Long,
      retired: Long,
      // demand misses
      dLoadMisses: Long, dStoreMisses: Long, iDemandMisses: Long,
      // bus traffic (D: refills+inhibited+walk reads; I: demand + prefetch)
      dArFires: Long, iArFires: Long,
      // memory-level parallelism, sampled every cycle over the whole run
      dOutstandingSum: Long, dOutstandingMax: Int, dBusyCycles: Long,
      iOutstandingSum: Long, iOutstandingMax: Int, iBusyCycles: Long,
      // coarse stall split (see the note in `report`)
      robEmptyCycles: Long, retireStallCycles: Long,
      stallDcacheCycles: Long, stallWalkCycles: Long,
      dcRefusedInRefill: Long,
      // injection accounting
      dSteals: Long, dStealsSkippedDirty: Long, iSteals: Long,
      cycles: Long) {
    private def perK(n: Long): Double = if (retired == 0) 0.0 else 1000.0 * n / retired
    def dMissPerK: Double = perK(dLoadMisses + dStoreMisses)
    def iMissPerK: Double = perK(iDemandMisses)
    def dRefillPerK: Double = perK(dArFires)
    def iFetchPerK: Double = perK(iArFires)
    /** MLP over cycles with at least one transaction outstanding -- the honest
      * denominator for "when the machine is missing, how many misses overlap". */
    def dMlpBusy: Double = if (dBusyCycles == 0) 0.0 else dOutstandingSum.toDouble / dBusyCycles
    def iMlpBusy: Double = if (iBusyCycles == 0) 0.0 else iOutstandingSum.toDouble / iBusyCycles
    /** MLP over ALL cycles -- the memory-system utilisation figure. */
    def dMlpAll: Double = if (cycles == 0) 0.0 else dOutstandingSum.toDouble / cycles
    def iMlpAll: Double = if (cycles == 0) 0.0 else iOutstandingSum.toDouble / cycles
    def pct(n: Long): Double = if (cycles == 0) 0.0 else 100.0 * n / cycles
  }

  /** Collected across the whole suite run (one entry per kernel invocation). */
  val collected: mutable.ArrayBuffer[Stats] = mutable.ArrayBuffer.empty

  def report(): Unit = {
    if (collected.isEmpty) return
    println()
    println("=" * 148)
    println(s"  MISS INJECTION / MLP  --  injection: $label")
    println("  D/kI,I/kI  = DEMAND misses per 1000 retired macros (board, real OS workload: D 24.4, I 32.2)")
    println("  Dax/kI,Iax/kI = AXI READ transactions per 1000 macros (D: refills+inhibited+walk; I: demand+prefetch)")
    println("  MLP     = mean AXI read transactions outstanding, over BUSY cycles (max in brackets)")
    println("  D-side MLP is capped at 1.0 BY CONSTRUCTION: DcachePlugin has ONE refill MSHR")
    println("  (AxiIds.dRefill reserves 0..3, 'only 0 is used while N_MSHR == 1').")
    println("  stall split is COARSE and NOT the board's counter definitions -- see the note below.")
    println("=" * 148)
    println(f"${"point"}%-10s ${"kernel"}%-24s ${"retd"}%7s ${"cyc"}%7s ${"IPC"}%5s " +
      f"${"D/kI"}%6s ${"Dax/kI"}%7s ${"I/kI"}%6s ${"Iax/kI"}%7s ${"Dmlp"}%11s ${"Imlp"}%11s " +
      f"${"robE%"}%6s ${"rStl%"}%6s ${"dc%"}%5s ${"refus%"}%6s ${"steals"}%12s")
    println("-" * 148)
    for (s <- collected) {
      val ipc = if (s.windowCycles == 0) 0.0 else s.retired.toDouble / s.windowCycles
      println(f"${s.point}%-10s ${s.kernel}%-24s ${s.retired}%7d ${s.windowCycles}%7d $ipc%5.3f " +
        f"${s.dMissPerK}%6.2f ${s.dRefillPerK}%7.2f ${s.iMissPerK}%6.2f ${s.iFetchPerK}%7.2f " +
        f"${s.dMlpBusy}%6.3f[${s.dOutstandingMax}%d] ${s.iMlpBusy}%6.3f[${s.iOutstandingMax}%d] " +
        f"${s.pct(s.robEmptyCycles)}%6.1f ${s.pct(s.retireStallCycles)}%6.1f " +
        f"${s.pct(s.stallDcacheCycles)}%5.1f ${s.pct(s.dcRefusedInRefill)}%6.1f " +
        f"${s.dSteals}%5dD/${s.iSteals}%dI")
    }
    println("-" * 148)
    val retd = collected.map(_.retired).sum
    val cyc  = collected.map(_.windowCycles).sum
    val dm   = collected.map(s => s.dLoadMisses + s.dStoreMisses).sum
    val im   = collected.map(_.iDemandMisses).sum
    val dSum = collected.map(_.dOutstandingSum).sum
    val dBusy= collected.map(_.dBusyCycles).sum
    val iSum = collected.map(_.iOutstandingSum).sum
    val iBusy= collected.map(_.iBusyCycles).sum
    val dax = collected.map(_.dArFires).sum
    val iax = collected.map(_.iArFires).sum
    println(f"${"AGGREGATE"}%-35s $retd%7d $cyc%7d ${retd.toDouble / math.max(1, cyc)}%5.3f " +
      f"${1000.0 * dm / math.max(1, retd)}%6.2f ${1000.0 * dax / math.max(1, retd)}%7.2f " +
      f"${1000.0 * im / math.max(1, retd)}%6.2f ${1000.0 * iax / math.max(1, retd)}%7.2f " +
      f"${dSum.toDouble / math.max(1, dBusy)}%6.3f[${collected.map(_.dOutstandingMax).max}%d] " +
      f"${iSum.toDouble / math.max(1, iBusy)}%6.3f[${collected.map(_.iOutstandingMax).max}%d]")
    println("=" * 148)
    val dirty = collected.map(_.dStealsSkippedDirty).sum
    println(f"  D steals skipped because the line was DIRTY: $dirty " +
      f"(a copyback kernel's store destination is immune to injection by design)")
    println("  NOTE the stall split above is a coarse three-way read of already-simPublic")
    println("  signals (ROB empty / ROB non-empty with no retire / of those, D-cache FSM in a")
    println("  refill or replay). It is NOT the board's retire-stall / stall-dcache /")
    println("  stall-walk counter definitions; replace it with the stall-budget")
    println("  decomposition when that lands, and do not compare these columns to board counters.")
    println("=" * 148)
  }

  /** Optional directory for the ARCHITECTURAL-EQUIVALENCE A/B (IPC_ARCH_DUMP=<dir>).
    *
    * Injection is a TIMING perturbation and nothing else, and this is how that claim
    * is checked rather than asserted: the committed architectural stream -- PC, the
    * architectural register written and its value, CCR/SR, and every committed memory
    * address and datum -- is dumped per kernel and digested. Two runs that differ only
    * in injection must produce the SAME digest on every kernel. A digest that moves is
    * either a bug in the injector or a latent bug it has exposed; the dumped stream is
    * there so the first differing commit can be found instead of guessed at.
    */
  val archDumpDir: Option[String] = sys.env.get("IPC_ARCH_DUMP").filter(_.nonEmpty)

  def archDump(kernel: String, obs: Seq[m68k040.lockstep.CommitObservation]): Unit =
    archDumpDir.foreach { dir =>
      val md = MessageDigest.getInstance("SHA-256")
      val sb = new StringBuilder
      for (o <- obs) {
        val line = f"${o.pc}%08x r${o.archRegId}%02d=${o.archRegWrite}%08x v=${o.archRegValid} " +
          f"ccr=${o.ccr}%02x sr=${o.sr}%04x mem=${o.memAddr}%08x/${o.memData}%08x w=${o.memWrite}\n"
        sb ++= line
        md.update(line.getBytes("UTF-8"))
      }
      val pt = (if (tag.nonEmpty) tag else label).replace('/', '-').replace('%', 'p').replace(' ', '_')
      val safe = kernel.replace('/', '_')
      Files.createDirectories(Paths.get(dir))
      Files.write(Paths.get(dir, s"$safe@$pt.arch"), sb.toString.getBytes("UTF-8"))
      val hex = md.digest().map(b => f"$b%02x").mkString
      println(s"[arch] point=${if (tag.nonEmpty) tag else label} kernel=$kernel commits=${obs.size} sha256=$hex")
    }

  def maybeNew(dut: CoreBenchHarness#FullCoreDut, kernel: String): Instance =
    new Instance(dut, kernel)

  /** One per `doSim`. Cheap when disabled: `onCycle` exits on `enabled`. */
  final class Instance(dut: CoreBenchHarness#FullCoreDut, kernel: String) {
    private val dc = dut.dcache.logic
    private val ic = dut.icache.logic

    private var cycle = 0L
    private var epoch = 0L
    // arm only after the harness's reset + explicit I-cache invalidate window
    private val armAfter = 140

    private var dLoadMisses = 0L; private var dStoreMisses = 0L
    private var iDemandMisses = 0L; private var iUnresolvedPrev = false
    private var dArFires = 0L; private var iArFires = 0L
    private var dOut = 0; private var dOutSum = 0L; private var dOutMax = 0; private var dBusy = 0L
    private var iOutSum = 0L; private var iOutMax = 0; private var iBusy = 0L
    private var robEmpty = 0L; private var retireStall = 0L
    private var stallDcache = 0L; private var stallWalk = 0L
    private var refusedInRefill = 0L
    private var dSteals = 0L; private var dSkipDirty = 0L; private var iSteals = 0L

    // (set, way, tag) nominations awaiting a safe steal window. Bounded: overflow
    // drops the newest nomination, which costs rate, never correctness.
    private val PendCap = 8
    private val dPend = mutable.Queue.empty[(Int, Int, Long)]
    private val iPend = mutable.Queue.empty[(Int, Int, Long)]

    private def dLine(tag: Long, set: Int): Long =
      (tag << (dSetBits + dOffBits)) | (set.toLong << dOffBits)
    private def iLine(tag: Long, set: Int): Long =
      (tag << (iSetBits + iOffBits)) | (set.toLong << iOffBits)

    /** Called once per sampled cycle from `CoreBenchHarness.runKernel`. */
    def onCycle(): Unit = {
      if (!enabled) return
      cycle += 1
      epoch = cycle / epochCycles

      // ── bus-level accounting: misses and MLP, D and I, uniformly ─────────────
      if (dc.axi.ar.valid.toBoolean && dc.axi.ar.ready.toBoolean) { dArFires += 1; dOut += 1 }
      if (dc.axi.r.valid.toBoolean && dc.axi.r.ready.toBoolean && dc.axi.r.payload.last.toBoolean)
        dOut = math.max(0, dOut - 1)
      if (dOut > 0) { dBusy += 1; dOutSum += dOut; if (dOut > dOutMax) dOutMax = dOut }

      if (ic.axi.ar.valid.toBoolean && ic.axi.ar.ready.toBoolean) iArFires += 1
      // The I-cache tracks its own per-ID outstanding bits (one per MSHR slot), so
      // its MLP is read directly rather than reconstructed from the channel.
      val iOut = ic.arOutstanding.count(_.toBoolean)
      if (iOut > 0) { iBusy += 1; iOutSum += iOut; if (iOut > iOutMax) iOutMax = iOut }

      // ── demand misses ────────────────────────────────────────────────────────
      if (dc.loadMissDiscovered.toBoolean) dLoadMisses += 1
      if (dc.storeMissDiscovered.toBoolean) dStoreMisses += 1
      val iUnres = ic.s1Unresolved.toBoolean
      if (iUnres && !iUnresolvedPrev) iDemandMisses += 1
      iUnresolvedPrev = iUnres

      // ── coarse stall split ───────────────────────────────────────────────────
      val robCount = dut.rob.logic.count.toInt
      val retiring = dut.rob.logic.retire0.toBoolean
      val dcBusy = dc.dbgFsmRefill.toBoolean || dc.dbgFsmReplay.toBoolean
      if (robCount == 0) robEmpty += 1
      else if (!retiring) {
        retireStall += 1
        if (dcBusy) stallDcache += 1
      }
      if (dut.dtlb.logic.missPending.toBoolean) stallWalk += 1
      if (dcBusy && dc.loadCmdPort.valid.toBoolean && !dc.loadCmdPort.ready.toBoolean)
        refusedInRefill += 1

      if (!injecting || cycle < armAfter) return

      // ── LINE STEALING ────────────────────────────────────────────────────────
      // Observe first, steal later. A line is NOMINATED when an eligible access HITS
      // it, and the steal is EXECUTED only in a window where nothing else can be
      // touching that valid bit. Splitting the two is not fastidiousness:
      //
      //   * A demand miss ends by RE-LAUNCHING the access against the freshly
      //     installed line (D-cache REPLAY, I-cache `s0Replay`), so the hit that
      //     retires a refill happens while the fill machinery is still live. That
      //     hit is exactly the one worth stealing on -- steal there and the line is
      //     invalid again before the next real access -- but poking the array in
      //     that cycle would race the RTL's own allocate write.
      //   * Worse, on the I side, clearing a valid bit in the window between the
      //     install and the replay's tag capture makes the replay miss with
      //     `s0Replay` SET, which IcachePlugin documents as an infinite refill loop
      //     rather than an ordinary miss. The drain window forbids that by
      //     construction: it requires the demand MSHR to be idle.
      //
      // The queues are tiny and lossy on purpose. A dropped nomination costs
      // injection RATE, which is measured; it can never cost soundness.
      if (dPct > 0) {
        if (dc.ldS1Valid.toBoolean && dc.ldS1Hit.toBoolean) {
          val way = dc.ldS1HitWay.toInt
          if (way >= 0 && way < dWays) {
            val set = dc.ldS1Set.toInt
            val tag = dc.ldS1Tag.toLong
            if (eligible(dLine(tag, set), epoch, dPct) && dPend.size < PendCap)
              dPend.enqueue((set, way, tag))
          }
        }
        // Drain window: load FSM in IDLE (so no refill, eviction or replay is live),
        // no reset sweep, no maintenance walk, and no store array write in flight.
        if (dPend.nonEmpty && dc.dbgFsmIdle.toBoolean && !dc.resetSweepBusy.toBoolean &&
            !dc.maintBusyReg.toBoolean && !dc.stS2ArrayWrite.toBoolean &&
            !dc.stS3ArrayWrite.toBoolean) {
          val (set, way, tag) = dPend.dequeue()
          if (dc.tagMem(way).getBigInt(set).toLong == tag &&
              dc.validsMem(way).getBigInt(set) != 0) {
            // A DIRTY line is never stolen: memory does not hold its bytes, so the
            // refill that followed would return pre-store data. That is the one way
            // this feature could change an architectural result, and the check is
            // what makes it impossible rather than unlikely.
            if (dc.dirtysMem(way).getBigInt(set) != 0) dSkipDirty += 1
            else { dc.validsMem(way).setBigInt(set, BigInt(0)); dSteals += 1 }
          }
        }
      }

      if (iPct > 0) {
        if (ic.s0Valid.toBoolean && ic.s1Hit.toBoolean) {
          var way = -1; var w = 0
          while (w < iWays) { if (ic.s1HitVec(w).toBoolean) way = w; w += 1 }
          if (way >= 0) {
            val set = ic.s0Set.toInt
            val tag = ic.s0Ppn.toLong
            if (eligible(iLine(tag, set), epoch, iPct) && iPend.size < PendCap)
              iPend.enqueue((set, way, tag))
          }
        }
        // Drain window: the DEMAND MSHR must be idle, so no replay can be in flight,
        // and the current S1 context must not itself be a replay. An I-cache line is
        // never dirty, so there is no writeback hazard to check.
        if (iPend.nonEmpty && !ic.mshrValid(0).toBoolean && !ic.s0Replay.toBoolean) {
          val (set, way, tag) = iPend.dequeue()
          if (ic.tagMem(way).getBigInt(set).toLong == tag && ic.valids(way)(set).toBoolean) {
            ic.valids(way)(set) #= false
            iSteals += 1
          }
        }
      }
    }

    def publish(windowCycles: Long, retired: Long): Unit = {
      if (!enabled) return
      collected += Stats(if (tag.nonEmpty) tag else label, kernel, windowCycles, retired,
        dLoadMisses, dStoreMisses, iDemandMisses, dArFires, iArFires,
        dOutSum, dOutMax, dBusy, iOutSum, iOutMax, iBusy,
        robEmpty, retireStall, stallDcache, stallWalk, refusedInRefill,
        dSteals, dSkipDirty, iSteals, cycle)
    }
  }
}
