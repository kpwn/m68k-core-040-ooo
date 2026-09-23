package m68k040.bench

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.{ItlbPlugin, DtlbPlugin, MmuControlPlugin}
import m68k040.cache.{AxiIds, IcachePlugin, DcachePlugin, DcacheService}
import m68k040.frontend.{FetchAlignPlugin, BtbPlugin, ComplexResumeActionPipe}
import m68k040.decode.DecodeStage
import m68k040.rename.RenameStage
import m68k040.rob.RobPlugin
import m68k040.execute.{AluEuPlugin, BranchEuPlugin, LsEuPlugin}
import m68k040.execute.iq.{IssueQueuePlugin, IssueQueueService}
import m68k040.execute.regfile.{RegFilePluginFp, RegFilePluginFpcc, RegFilePluginInt,
  RegFilePluginNzvc, RegFilePluginX}
import m68k040.services.{RedirectService, DTranslationService}
import m68k040.lockstep.WhiteboxCapture
import m68k040.oracle.ProgramAssembler
import m68k040.sim.{AxiMemModel, AxiMemModelConfig, L2LatencyModel}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer
/** IPC (instructions-per-cycle) microbenchmark harness for the 2-wide OoO 68040.
  *
  * Assembles small kernels, runs each in the cycle-accurate Verilator DUT to a
  * known retired-instruction count, and measures:
  *   - IPC = retired-macro-instructions / clock-cycles (steady-state window)
  *   - dual-issue% = fraction of commit-active cycles that retire 2 instructions
  *     (the 2-wide superscalar utilization)
  *
  * Macro-instruction counting: the ROB retires AT µop granularity (2 commit
  * ports). Some instructions crack into multiple µops (a memSimple SOURCE operand
  * cracks into [load->temp, op]). We count MACRO-instructions via WhiteboxCapture,
  * which drops the cracked-load temp µop (dstArch >= 16, no flags) — exactly the
  * lock-step macro-instruction stream. Cycles and the dual-issue histogram are
  * taken from the RAW per-cycle commit-port fire stream.
  *
  * Steady-state window: we exclude pipeline FILL (fetch -> first commit) and DRAIN
  * (after the last commit) by measuring cycles from the FIRST committed instruction
  * to the LAST. Kernels loop / unroll enough to amortize the residual fill/drain.
  *
  * This is MEASUREMENT TOOLING: it reports the REAL measured numbers. The only
  * hard assertions are sanity bounds (dependent-ALU near 1; independent-ALU
  * meaningfully higher). A failed bound is a genuine finding, not a harness bug.
  */
object IpcBenchSpec {
  /** Pinned simulation seed (IPC_SEED=<int>); unset keeps the historical random seed. */
  val simSeed: Int = sys.env.get("IPC_SEED").map(_.toInt).getOrElse(scala.util.Random.nextInt())
}

class IpcBenchSpec extends CoreBenchHarness {

  test("IPC microbenchmark suite", VerilatorTest) {
    val allKernels = Seq(kDependentAlu, kIndependentAlu, kLoadStore, kLoadStream,
      kStoreStream, kSameLineCopyback, kShiftStream, kShiftMixed, kBranchy, kDeepBacklog,
      kHotLoop, kMixed, kCallReturn, kDhrystone(), kDhrystone(extraAlu = 4), kDhrystone(extraAlu = 8), kChasePure(), kDhrystone(strCopy = false),
      kDhrystone(copyStyle = "longMemMem"), kDhrystone(copyStyle = "byteSplit"),
      kDhrystone(copyStyle = "byteAbs"), kDhrystone(copyStyle = "byteLoadOnly"),
      kDhrystone(copyStyle = "byteStoreOnly"), kDhrystone(copyStyle = "longStoreOnly"),
      kDhrystone(copyback = true), kDhrystone(copyStyle = "byteStoreOnly", copyback = true),
      kDhrystone(strCopy = false, copyback = true),
      kDhrystone(copyStyle = "byteAbs", copyback = true),
      kDhrystone(copyStyle = "byteSplit", copyback = true),
      kDhrystone(copyStyle = "byteLoadOnly", copyback = true),
      kDhrystone(copyStyle = "byteX4", copyback = true),
      kDhrystone(copyStyle = "byteDispX4", copyback = true),
      kDhrystone(copyStyle = "byteLdIncX4", copyback = true),
      kDhrystone(copyStyle = "byteStIncX4", copyback = true))
    // Optional kernel filter for debugging a single kernel (IPC_ONLY=load/store).
    val kernels = sys.env.get("IPC_ONLY") match {
      case Some(sel) => val names = sel.split(',').map(_.trim).toSet; allKernels.filter(k => names.contains(k.name))
      case None      => allKernels
    }

    // IPC_V2=1 builds the DUT the BOARD ships: SocketIpcProfile."throughput-v2"
    // turns every LS/front-end option on (SocketTop.scala `enabled` returns true for
    // it, and `lateStore` adds the postincrement reservation). The suite's historical
    // default is `new FullCoreDut` with every option FALSE, i.e. the baseline core --
    // so an unqualified number from here is NOT comparable to a board counter.
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
        // IQ_LOAD_BYPASS=1 lets a load pass an older UNREADY LOAD (stores stay ordered).
        loadBypassUnreadyLoad = sys.env.get("IQ_LOAD_BYPASS").contains("1"),
        earlyAutoAnWriteback = sys.env.get("LS_EARLY_AN").contains("1"),
        // IPC_V2_DEFER=1 adds the two slot-1 conditional-deferral options, which are
        // the only validated FullCoreDut options the shipped profile does not set.
        // `deferSlot1Conditional` EXCLUDES slot-1 training; `deferTakenSlot1Conditional`
        // REQUIRES it. So the only valid addition to the shipped profile is taken-only
        // deferral on top of training, which is what this A/B toggles.
        deferTakenSlot1Conditional = sys.env.get("IPC_V2_DEFER").contains("1")))
    println(s"  core profile: ${if (v2) "throughput-v2 (board)" else "baseline (all options off)"}")
    val results = kernels.map(k => runKernel(compiled, k))

    // ── Print the table ───────────────────────────────────────────────────────
    println()
    println("=" * 96)
    println("  68040 OoO 2-wide superscalar — IPC microbenchmark")
    println("  steady-state window = first-commit .. last-commit (fill/drain excluded)")
    println("  IPC = retired macro-instructions / window-cycles")
    println(s"  memory model: $memLabel")
    println(s"  sim seed: ${IpcBenchSpec.simSeed}")
    println("  dual%(act) = cycles retiring 2 / commit-active cycles (backend ILP)")
    println("  dual%(win) = cycles retiring 2 / all window cycles")
    println("  active%    = cycles retiring >=1 / all window cycles (backend occupancy)")
    println("=" * 96)
    println(f"${"kernel"}%-16s ${"retired"}%8s ${"cycles"}%7s ${"IPC"}%6s ${"dual%(act)"}%10s ${"dual%(win)"}%10s ${"active%"}%8s ${"FTB app/ok/bad"}%16s")
    println("-" * 96)
    for (r <- results) {
      println(f"${r.name}%-16s ${r.retiredInstrs}%8d ${r.windowCycles}%7d ${r.ipc}%6.3f " +
        f"${r.dualPctActive}%9.1f%% ${r.dualPctWindow}%9.1f%% ${r.activePct}%7.1f%% " +
        f"${r.ftbApplies}%5d/${r.ftqConfirms}%d/${r.ftqMismatches}%-5d")
    }
    println("-" * 96)
    val totRet = results.map(_.retiredInstrs).sum
    val totCyc = results.map(_.windowCycles).sum
    println(f"${"AGGREGATE"}%-16s ${totRet}%8d ${totCyc}%7d ${totRet.toDouble / totCyc}%6.3f")
    println("=" * 96)
    println()
    for (r <- results) {
      println(s"[ftb:${r.name}] apply=${r.ftbApplies} confirm=${r.ftqConfirms} " +
        s"mismatch=${r.ftqMismatches} declineDir=${r.ftbDirDeclines} " +
        s"declineFrame=${r.ftbFrameDeclines} declineBlocked=${r.ftbBusyDeclines}")
    }
    println()

    val depO = results.find(_.name == "dependent-ALU")
    val indO = results.find(_.name == "independent-ALU")
    if (depO.isEmpty || indO.isEmpty) {
      println("[sanity] dependent/independent-ALU not in this run (filtered) — skipping bounds")
    } else {
    val dep = depO.get
    val ind = indO.get

    // ── Sanity bounds + findings (REAL measured numbers, nothing faked) ───────
    println(f"[sanity] dependent-ALU   IPC=${dep.ipc}%.3f  dual%%(act)=${dep.dualPctActive}%.1f%%  (latency-bound chain)")
    println(f"[sanity] independent-ALU IPC=${ind.ipc}%.3f  dual%%(act)=${ind.dualPctActive}%.1f%%  active%%=${ind.activePct}%.1f%%")

    // (1) HARD bound: a strict dependency chain is latency-bound; it cannot sustain
    //     2 retires/cycle. IPC must stay near (and below) 1 — this validates the
    //     measurement: a chain that measured ~2 would mean we mis-counted.
    assert(dep.ipc <= 1.25,
      f"dependent-ALU IPC=${dep.ipc}%.3f exceeded 1.25 — a true dependency chain cannot " +
      f"retire 2/cycle; the macro-instruction or cycle count is wrong.")

    // (2) HARD check: the 2-wide RETIRE mechanism works — with abundant ILP the
    //     backend DOES retire 2/cycle on the large majority of commit-active cycles.
    //     (This proves dual-issue is real; the IPC ceiling below is a SEPARATE,
    //     upstream throughput limit, not a retire-width limit.)
    assert(ind.dualPctActive > 50.0,
      f"independent-ALU only dual-retired ${ind.dualPctActive}%.1f%% of commit-active cycles " +
      f"(<50%%) — the 2-wide retire path is not engaging even with full ILP.")

    // (3) FINDING: does independent-ALU IPC meaningfully EXCEED dependent-ALU IPC?
    //     If NOT, the 2-wide backend is starved by an UPSTREAM throughput limit
    //     (fetch/align/decode/rename/dispatch not sustaining 2 µops/cycle), so the
    //     extra ILP cannot translate into IPC. This is a REAL bottleneck — surfaced,
    //     not hidden, and not asserted-away (the harness itself is correct).
    if (ind.ipc <= dep.ipc * 1.2) {
      println()
      println("!" * 78)
      println(f"  FINDING — FRONT-END THROUGHPUT BOTTLENECK (real, measured)")
      println(f"  independent-ALU IPC (${ind.ipc}%.3f) does NOT meaningfully exceed")
      println(f"  dependent-ALU IPC (${dep.ipc}%.3f), even though the backend dual-RETIRES")
      println(f"  ${ind.dualPctActive}%.1f%% of its commit-active cycles. The limiter is OCCUPANCY:")
      println(f"  the backend only commits on ${ind.activePct}%.1f%% of cycles (it is idle the rest).")
      println(f"  => extra ILP is wasted; IPC is capped ~${scala.math.max(dep.ipc, ind.ipc)}%.2f by the")
      println(f"     front-end (fetch/align/decode/rename/dispatch) not sustaining 2/cycle,")
      println(f"     NOT by the 2-wide retire width. Optimize fetch/rename throughput.")
      println("!" * 78)
    } else {
      println(f"[result] independent-ALU IPC (${ind.ipc}%.3f) exceeds dependent-ALU " +
        f"(${dep.ipc}%.3f) — superscalar gain realized.")
    }
    }
  }
}
