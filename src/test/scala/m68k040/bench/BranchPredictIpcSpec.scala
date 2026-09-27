package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** BRANCH-PREDICTION ATTRIBUTION SUITE.
  *
  * Silicon `perf` ranks branch mispredicts as the second-largest stall in the machine
  * (24-77 per 1000 retired instructions, ~11-14% of all cycles). The two shipped board
  * class counters split that only by `brType` -- and BOTH are sourced from
  * `debugBranchRetire`, which is gated on `isBtbBranch` and therefore excludes RETURNS,
  * while `OFF_MISPRED_COUNT` (`branchRedirect`) counts them. So on the board:
  *   - returns/RAS are invisible to both class counters;
  *   - "no prediction existed" is indistinguishable from "predicted the wrong way";
  *   - "the stored target was stale" is indistinguishable from "the BTB missed".
  * Those three distinctions are the ones that decide which project is worth doing.
  *
  * `IpcBenchSpec`'s kernels cannot answer it either: they retire 25 mispredicts in
  * total (1.49 MPKI aggregate against the board's 24.4), and the per-kernel counts are
  * 3-10 events, so any predictor delta is sampling noise. That blind spot is recorded
  * in `perf(btb): 128 -> 512` ("THIS CANNOT BE VALIDATED IN THE BENCH") and in the
  * bench-stall-mix memory note.
  *
  * This suite closes both gaps: the `kBr*` probes each drive ONE predictor failure
  * mode to ~1000 retired mispredicts, and `[br-attr]` classifies every retired
  * mispredict exactly, joined by robId to the branch EU's resolution. Each probe is
  * paired with a `-fit` control sized to fit the structure it stresses -- the pair is
  * what proves the probe measures that structure.
  *
  * DUT = the `throughput-v2` frontend the board ships (`retainRedirectHistory` +
  * `trainSlot1Conditional`), so the attribution is of the shipped predictor.
  */
class BranchPredictIpcSpec extends CoreBenchHarness {

  test("branch mispredict attribution across the predictor failure modes", VerilatorTest) {
    val probes = Seq(
      // Indirect target: one `jsr (%a0)` site, 32 targets in a fixed cycle.
      kBrIndirect(handlers = 32, iters = 1024),
      // Indirect control: the SAME shape with a single target, so the site is
      // last-target predictable. Separates "indirect" from "jsr/rts cost".
      kBrIndirect(handlers = 2, iters = 1024, label = "br-ind-2"),
      // BTB/FTB capacity: 256 taken BRA sites against a 128-entry direct-mapped BTB.
      kBrCapacity(sites = 256, iters = 8),
      kBrCapacity(sites = 64, iters = 32, label = "br-cap-fit"),
      // RAS: nest 24 deep against a 16-entry return stack, and the fitting control.
      kBrReturn(depth = 24, iters = 128),
      kBrReturn(depth = 8, iters = 384, label = "br-ras-fit"),
      // gshare direction: a period-32 pattern a bimodal counter cannot learn.
      kBrPattern(iters = 1024),
      // DBcc in SLOT 1: the canonical `add ; dbra` counted loop. Predicted by nothing
      // until `deferSlot1Dbcc` moves it to slot 0.
      kBrDbcc(iters = 1024))
    // The kernels the IPC brief asks to report, so the probes are read next to the
    // workload-shaped numbers rather than in isolation.
    val reference = Seq(kBranchy, kHotLoop, kCallReturn, kDhrystone(copyback = true))

    // BR_COMPUTE_TARGETS=1 turns on FetchAlignPlugin.computeDirectTargets (the computed
    // PC-relative unconditional target). ON vs OFF is run on IDENTICAL trees, one
    // `M68kSim` build each, so the only difference is that flag.
    val computeOn = sys.env.get("BR_COMPUTE_TARGETS").contains("1")
    val deferOn   = sys.env.get("BR_DEFER_SLOT1_UNCOND").contains("1")
    val dbccOn    = sys.env.get("BR_DEFER_SLOT1_DBCC").contains("1")
    println(s"  BRANCH_PROBE_CONFIG computeDirectTargets=$computeOn deferSlot1Uncond=$deferOn deferSlot1Dbcc=$dbccOn")
    val compiled = M68kSim().withVerilator.compile(new FullCoreDut(
      alignedLoadFallThrough = true, earlyLsIntWakeup = true, sqSubwordForwarding = true,
      pairCorrectBranch = true, retainRedirectHistory = true, trainSlot1Conditional = true,
      // MUST be set: `SocketTop` gives throughput-v2 BOTH slot-1 knobs
      // (`deferTakenSlot1Conditional = ipcThroughput`), and without it a slot-1
      // conditional carries only the IMPLICIT NOT-TAKEN record `brPredLive1` stamps --
      // so a slot-1 TAKEN conditional mispredicts 100% of the time and shows up in
      // `[br-attr]` as `cond-dir-gshare`, i.e. as a gshare direction error that is
      // really a slot-1 coverage hole. `IpcBenchSpec`'s IPC_V2 path leaves this to
      // IPC_V2_DEFER and is therefore NOT the board's frontend either.
      deferTakenSlot1Conditional = true,
      earlyStoreAddress = true, fuseLongMoveLoads = true, reserveLateStore = true,
      detachLateStore = true, forwardOnPublish = true, earlyLsNzvcWakeup = true,
      detachedStoreEntries = 4, earlyAutoStoreAddress = true, earlyStoreDataWake = true,
      computeDirectTargets = computeOn, deferSlot1Uncond = deferOn,
      deferSlot1Dbcc = dbccOn))

    val results = (probes ++ reference).map(k => k.name -> runKernel(compiled, k))
    println()
    println("=" * 88)
    println(s"  branch-prediction probes, computeDirectTargets=$computeOn deferSlot1Uncond=$deferOn deferSlot1Dbcc=$dbccOn")
    println("  see the [br-attr] lines above for the per-bucket attribution")
    println("=" * 88)
    println(f"${"kernel"}%-18s ${"retired"}%8s ${"cycles"}%8s ${"IPC"}%7s")
    results.foreach { case (n, r) =>
      println(f"$n%-18s ${r.retiredInstrs}%8d ${r.windowCycles}%8d ${r.ipc}%7.3f")
    }
    println("=" * 88)
  }
}
