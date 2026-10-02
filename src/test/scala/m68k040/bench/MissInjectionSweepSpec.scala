package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._

/** THE MISS-PRESSURE SWEEP. One Verilator build, a curve of injection rates.
  *
  * ── WHAT THIS ANSWERS, AND WHAT IT DELIBERATELY DOES NOT ─────────────────────
  * Calibrating injection to one board-representative rate would answer only "does
  * this lever help today's Mac OS" -- a 1991 OS with an ~8 MB working set. The
  * question worth the compile time is how a lever RESPONDS as miss pressure rises
  * and WHERE IT SATURATES, because the saturation point is a property of this
  * microarchitecture and does not move with the workload.
  *
  * So this walks a curve. Sweep points are labelled by provenance, and the labels
  * are load-bearing: a synthetic headroom probe must never be quoted later as a
  * measurement of this machine.
  *
  *   BOARD-OBSERVED, and the only points that describe a real workload:
  *     D ~8/kI    the board's Dhrystone-only window
  *     D ~24/kI   the board's real OS workload
  *     I ~24/kI   the board's Dhrystone-only window
  *     I ~32/kI   the board's real OS workload
  *   SYNTHETIC HEADROOM PROBES, describing no workload that has been observed:
  *     ~50/kI, ~100/kI and above
  *   ASYMPTOTE, describing no workload at all: 100% injection. Fully serialised on
  *     refills and MSHR-bound. Useful as a bound, never as evidence.
  *
  * ── THE HEADLINE METRIC IS MLP, NOT IPC ──────────────────────────────────────
  * IPC is the aggregate that has been hiding everything else. What decides whether
  * ANY load-latency lever can pay is whether the machine overlaps misses at all.
  * Read the `Dmlp`/`Imlp` columns first: mean and max AXI read transactions
  * outstanding. If D-side MLP stays at 1.0 as the miss rate climbs, the D side is
  * serialising every miss and no amount of earlier wakeup can recover a cycle that
  * the memory system was never going to overlap.
  *
  * ── USAGE ────────────────────────────────────────────────────────────────────
  *   INJ_POINTS=0:0,25:0,50:0,100:0   sweep points as <Dpct>:<Ipct>, comma separated
  *   INJ_KERNELS=load-stream,mixed    kernel subset (default below)
  *   INJ_SEEDS=2                      seeds per point (default 2; >=2 is the rule)
  *   IPC_MEM=l2                       STRONGLY recommended -- see the note below
  *   IPC_SEED=<int>                   base seed; seed k is IPC_SEED + k
  *
  * ⚠ IPC_MEM DEFAULTS TO ZERO-LATENCY IDEAL MEMORY. Under that model a forced miss
  * costs almost nothing, so the sweep measures the cache's own occupancy cost and
  * not a memory stall. Always state which memory model a number came from.
  *
  * ── WHAT THE FIRST SWEEP FOUND (2026-09-27, IPC_MEM=l2, 2 seeds) ─────────────
  * THE CALIBRATION, on `chase-128` (the one kernel whose footprint is large enough
  * for the requested percentage to be delivered): requested 2% -> 2.6 D-misses/kI,
  * 5% -> 5.2, 10% -> 12.2, 20% -> 27.8, 40% -> 72.9, 70% -> 168.8, 100% -> 332.5,
  * from a baseline of ZERO. So on that kernel D≈6% reproduces the board's Dhrystone
  * window (8.2/kI) and D≈18% reproduces the board's real OS workload (24.4/kI). The
  * I side needs a much larger percentage for the same rate because it depends on the
  * loop's CODE footprint: I≈40-55% lands on the board's 23.9-32.2/kI.
  *
  * THE D-SIDE NEVER OVERLAPS TWO MISSES. Mean AXI reads outstanding on the D bus is
  * 1.000, and the MAXIMUM is 1, at EVERY rate up to 100% injection, on every kernel,
  * on both the baseline and the shipped throughput-v2 profile. That is not a
  * measurement artefact, it is `DcachePlugin`'s one refill MSHR (`AxiIds.dRefill`
  * reserves 0..3 and says "only 0 is used while N_MSHR == 1"). Any lever whose
  * mechanism is "overlap this miss with that one" therefore has NOTHING to overlap
  * with on the D side today, at any miss rate. Read that before costing one.
  *
  * THE I SIDE DOES OVERLAP -- AND INJECTION DESTROYS IT. I-side MLP is 1.9-3.2 with
  * five MSHR slots, and it FALLS towards 1.1 as the I-miss rate rises: the overlap
  * comes from the next-line prefetcher running ahead, and demand misses crowd it out
  * of its slots. So an I-side lever measured on a clean bench is measured in the one
  * regime where the prefetcher has room.
  */
class MissInjectionSweepSpec extends CoreBenchHarness {

  private def envList(name: String): Option[Seq[String]] =
    sys.env.get(name).filter(_.nonEmpty).map(_.split(',').map(_.trim).filter(_.nonEmpty).toSeq)

  test("miss-injection rate sweep", VerilatorTest) {
    // `chase-128` is the D-side workhorse and is sized on purpose: 128 records x 16 B
    // = 2 KiB over 128 DISTINCT lines, all of which fit the 8 KiB D-cache. So its
    // baseline is a cold pass (excluded by `warmupInstrs`) and then pure hits, which
    // makes the achieved miss rate a clean, near-linear function of the requested
    // percentage -- the opposite of `load-stream`, whose entire working set is TWO
    // lines and therefore cannot express a rate between 0%, 50% and 100%.
    // `mixed` is the D-side NEGATIVE CONTROL: every address it loads it stored first,
    // so the store queue forwards all of it and the D-cache sees no traffic at all. If
    // D injection ever moves `mixed`, the injector is reaching somewhere it should not.
    val allKernels = Seq(
      kChasePure(records = 128, iters = 512).copy(name = "chase-128"),
      kLoadStream, kMixed, kBranchy, kHotLoop,
      // `dhry-cb-128` is the -cb variant sized to be affordable in a sweep: the same
      // kernel as `dhrystone--cb`, with a 128-record footprint and a 128-record cold
      // walk excluded by `warmupInstrs`, so what is measured is steady state. Note its
      // copy DESTINATION goes dirty and is therefore immune to D injection by design --
      // the `dirty` skip count in the report says how much of the stream that is.
      kDhrystone(records = 128, iters = 512, copyback = true).copy(name = "dhry-cb-128"),
      kChasePure(), kDhrystone(copyback = true), kDhrystone())
    val kernels = envList("INJ_KERNELS") match {
      case Some(names) =>
        val want = names.toSet
        val got = allKernels.filter(k => want.contains(k.name))
        assert(got.nonEmpty, s"INJ_KERNELS matched nothing; available: ${allKernels.map(_.name)}")
        got
      case None => allKernels
    }
    val points: Seq[(Int, Int)] = envList("INJ_POINTS") match {
      case Some(specs) => specs.map { p =>
        val parts = p.split(':')
        require(parts.length == 2, s"INJ_POINTS entry '$p' is not <Dpct>:<Ipct>")
        (parts(0).toInt, parts(1).toInt)
      }
      case None => Seq((0, 0), (10, 0), (25, 0), (50, 0), (100, 0),
                       (0, 10), (0, 25), (0, 50), (0, 100), (25, 25), (50, 50))
    }
    // The zero-injection baseline row is the reference the whole curve is read against,
    // so statistics are on for every point whether it injects or not.
    MissInjector.statsOnly = true
    val seedCount = sys.env.get("INJ_SEEDS").filter(_.nonEmpty).map(_.toInt).getOrElse(2)
    require(seedCount >= 1)

    // IPC_V2=1 builds the profile the BOARD ships, exactly as IpcBenchSpec does. This
    // matters for a lever A/B: a lever validated on the shipped profile must be
    // re-measured on it, and the historical default here (every option FALSE) is a
    // different machine. Duplicated rather than hoisted into CoreBenchHarness on
    // purpose -- the stall-budget work is editing that file right now.
    val v2 = sys.env.get("IPC_V2").contains("1")
    val compiled = M68kSim().withVerilator.compile(
      if (!v2) new FullCoreDut
      else new FullCoreDut(
        alignedLoadFallThrough = true, earlyLsIntWakeup = true, sqSubwordForwarding = true,
        pairCorrectBranch = true, retainRedirectHistory = true,
        trainSlot1Conditional = true,
        earlyStoreAddress = true, fuseLongMoveLoads = true, reserveLateStore = true,
        detachLateStore = true, forwardOnPublish = true, earlyLsNzvcWakeup = true,
        detachedStoreEntries = 4, earlyAutoStoreAddress = true, earlyStoreDataWake = true,
        loadBypassUnreadyLoad = sys.env.get("IQ_LOAD_BYPASS").contains("1"),
        earlyAutoAnWriteback = sys.env.get("LS_EARLY_AN").contains("1"),
        deferTakenSlot1Conditional = sys.env.get("IPC_V2_DEFER").contains("1")))
    println(s"  core profile: ${if (v2) "throughput-v2 (board)" else "baseline (all options off)"}")
    println(s"  memory model: $memLabel")
    println(s"  kernels: ${kernels.map(_.name).mkString(",")}")
    println(s"  points: ${points.map { case (d, i) => s"D$d/I$i" }.mkString(",")}  seeds: $seedCount")

    // (point, kernel, seed) -> IpcResult, so the curve can be read per kernel and the
    // seed spread reported rather than averaged away.
    val ipc = scala.collection.mutable.ArrayBuffer.empty[(String, String, Int, Double, Int, Int)]
    for ((d, i) <- points; s <- 0 until seedCount) {
      val seed = IpcBenchSpec.simSeed + s
      MissInjector.configure(d, i, f"D$d%d/I$i%d")
      for (k <- kernels) {
        val r = runKernel(compiled, k, seed)
        ipc += ((MissInjector.tag, k.name, seed, r.ipc, r.retiredInstrs, r.windowCycles))
      }
    }

    MissInjector.report()

    // ── THE CURVE, per kernel, IPC against injection rate ────────────────────
    println()
    println("=" * 120)
    println("  IPC vs INJECTION RATE (per kernel, per seed). The requested percentage is a")
    println("  knob, not a result -- read the ACHIEVED D/kI and I/kI from the table above.")
    println("=" * 120)
    val byKernel = ipc.groupBy(_._2)
    for (kn <- kernels.map(_.name); rows <- byKernel.get(kn)) {
      println(f"  $kn%-24s " + points.map { case (d, i) =>
        val sel = rows.filter(_._1 == f"D$d%d/I$i%d").map(_._4)
        if (sel.isEmpty) "    --  " else f"${sel.sum / sel.size}%7.3f"
      }.mkString(" "))
    }
    println("  columns: " + points.map { case (d, i) => f"D$d%d/I$i%d" }.mkString(" "))
    println("=" * 120)

    // ── THE CALIBRATION, and the MLP curve next to it ────────────────────────
    // The requested percentage is a knob; these are the results it produced. Read
    // the board reference points off THIS table, not off the percentages.
    def curve(title: String, note: String, pick: MissInjector.Stats => Double): Unit = {
      println()
      println("-" * 120)
      println(s"  $title")
      if (note.nonEmpty) println(s"  $note")
      println("-" * 120)
      val rows = MissInjector.collected.groupBy(_.kernel)
      for (kn <- kernels.map(_.name); rs <- rows.get(kn)) {
        println(f"  $kn%-24s " + points.map { case (d, i) =>
          val sel = rs.filter(_.point == f"D$d%d/I$i%d").map(pick)
          if (sel.isEmpty) "    --  " else f"${sel.sum / sel.size}%7.2f"
        }.mkString(" "))
      }
      println("  columns: " + points.map { case (d, i) => f"D$d%d/I$i%d" }.mkString(" "))
    }
    curve("ACHIEVED D-cache demand misses per 1000 macros (THE D CALIBRATION)",
      "board reference: 8.2 = Dhrystone window (observed), 24.4 = real OS workload (observed)",
      _.dMissPerK)
    curve("ACHIEVED I-cache demand misses per 1000 macros (THE I CALIBRATION)",
      "board reference: 23.9 = Dhrystone window (observed), 32.2 = real OS workload (observed)",
      _.iMissPerK)
    curve("D-side MLP (mean AXI reads outstanding while the D bus is busy)",
      "1.000 at every rate means the D side never overlaps two misses -- one refill MSHR",
      _.dMlpBusy)
    curve("I-side MLP (mean AXI reads outstanding while the I bus is busy)",
      "falls as the miss rate rises: a demand miss crowds the prefetcher out of its slots",
      _.iMlpBusy)
    curve("cycles the D-cache REFUSED a presented load, % of all cycles",
      "already-measured behaviour (21,451 of 27,664 cycles): the cache takes no load during a refill",
      s => s.pct(s.dcRefusedInRefill))
    println("-" * 120)
  }
}
