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
  */
class MissInjectionSweepSpec extends CoreBenchHarness {

  private def envList(name: String): Option[Seq[String]] =
    sys.env.get(name).filter(_.nonEmpty).map(_.split(',').map(_.trim).filter(_.nonEmpty).toSeq)

  test("miss-injection rate sweep", VerilatorTest) {
    val allKernels = Seq(kLoadStream, kMixed, kChasePure(), kDhrystone(copyback = true),
      kBranchy, kHotLoop)
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
    val seedCount = sys.env.get("INJ_SEEDS").filter(_.nonEmpty).map(_.toInt).getOrElse(2)
    require(seedCount >= 1)

    val compiled = M68kSim().withVerilator.compile(new FullCoreDut)
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
  }
}
