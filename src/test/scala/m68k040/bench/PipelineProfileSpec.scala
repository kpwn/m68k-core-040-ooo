package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import m68k040.lockstep.CommitObservation

/** Retired-branch denominators and ROB pressure in the exact warmed IPC window.
  * Every profile run has an instrumentation-disabled matched control. */
class PipelineProfileSpec extends CoreBenchHarness {
  private def checkRegisters(expected: (Int, Long)*)(obs: Seq[CommitObservation]): Unit = {
    expected.foreach { case (reg, value) =>
      val actual = obs.filter(o => o.archRegValid && o.archRegId == reg).last.archRegWrite & 0xffffffffL
      assert(actual == value, s"D$reg=$actual expected=$value")
    }
  }

  test("windowed branch accuracy and retirement backlog with unchanged IPC", VerilatorTest) {
    val hot = kHotLoop.copy(warmupInstrs = 4 + 16 * 4,
      verifyRetirement = checkRegisters(0 -> 100L, 2 -> 100L, 7 -> 0L))
    val alternating = kBranchy.copy(warmupInstrs = 5 + 16 * 11 / 2,
      verifyRetirement = checkRegisters(0 -> 20L, 6 -> 0L, 7 -> 0L))
    val backlog = kDeepBacklog.copy(warmupInstrs = 7 + 4 * 45 / 2,
      verifyRetirement = checkRegisters(0 -> 10L, 2 -> 0L, 7 -> 0L))
    val calls = kCallReturn.copy(copybackDtt = true, warmupInstrs = 5 + 16 * 8,
      verifyRetirement = checkRegisters(0 -> 100L, 2 -> 0L, 3 -> 100L, 7 -> 0L))
    val independent = kIndependentAlu.copy(warmupInstrs = 32)
    val alternatingLong = kBranchy.copy(name = "branchy-long",
      src = kBranchy.src.replace("moveq #40,%d7", "move.l #512,%d7"),
      retiredInstrs = 5 + 512 * 11 / 2, warmupInstrs = 5 + 128 * 11 / 2,
      verifyRetirement = checkRegisters(0 -> 256L, 6 -> 0L, 7 -> 0L))
    val backlogLong = kDeepBacklog.copy(name = "deep-backlog-long",
      src = kDeepBacklog.src.replace("moveq #20,%d7", "move.l #128,%d7"),
      retiredInstrs = 7 + 128 * 45 / 2, warmupInstrs = 7 + 16 * 45 / 2,
      verifyRetirement = checkRegisters(0 -> 64L, 2 -> 0L, 7 -> 0L))
    val cases = Seq(hot -> 84, alternating -> 48, backlog -> 32,
      calls -> 252, independent -> 0, alternatingLong -> 768, backlogLong -> 224)
    val pairBranches = sys.env.get("IPC_PAIR_BRANCH").contains("1")
    val deferConditionals = sys.env.get("IPC_DEFER_CONDITIONAL").contains("1")
    val deferTaken = sys.env.get("IPC_DEFER_TAKEN_SLOT1").contains("1")
    val trainSlot1 = sys.env.get("IPC_TRAIN_SLOT1").contains("1") || deferTaken
    val retainHistory = sys.env.get("IPC_RETAIN_HISTORY").contains("1")
    val lsFlags = Seq(
      "fallThrough" -> "IPC_LS_FALLTHROUGH", "earlyWake" -> "IPC_LS_EARLY_WAKEUP",
      "subword" -> "IPC_SQ_SUBWORD", "earlyStore" -> "IPC_EARLY_STORE_ADDRESS",
      "fusion" -> "IPC_FUSE_LONG_MOVE_LOADS", "reserve" -> "IPC_RESERVE_LATE_STORE",
      "detach" -> "IPC_DETACH_LATE_STORE", "publish" -> "IPC_FORWARD_ON_PUBLISH")
      .map { case (name, env) => name -> sys.env.get(env).contains("1") }.toMap
    val compiled = M68kSim().withVerilator.compile(new FullCoreDut(pairCorrectBranch = pairBranches,
      deferSlot1Conditional = deferConditionals, trainSlot1Conditional = trainSlot1,
      deferTakenSlot1Conditional = deferTaken, retainRedirectHistory = retainHistory,
      alignedLoadFallThrough = lsFlags("fallThrough"), earlyLsIntWakeup = lsFlags("earlyWake"),
      sqSubwordForwarding = lsFlags("subword"), earlyStoreAddress = lsFlags("earlyStore"),
      fuseLongMoveLoads = lsFlags("fusion"), reserveLateStore = lsFlags("reserve"),
      detachLateStore = lsFlags("detach"), forwardOnPublish = lsFlags("publish")))
    for ((kernel, expectedBranches) <- cases; seed <- Seq(1, 17)) {
      val control = runKernel(compiled, kernel.copy(name = s"${kernel.name}-control"), seed)
      val measured = runKernel(compiled,
        kernel.copy(name = s"${kernel.name}-profile", profileRetirement = true), seed)
      assert(control.retiredInstrs == measured.retiredInstrs)
      assert(control.windowCycles == measured.windowCycles, "profiling must not change IPC")
      val p = measured.pipelineProfile.get
      assert(p.retiredBranches == expectedBranches,
        s"${kernel.name}: ${p.retiredBranches} retired branches, expected $expectedBranches")
      assert(p.rob.size == measured.windowCycles)
      assert(p.rob.forall(s => s.completePrefix <= s.occupancy && s.completeYounger < math.max(1, s.occupancy)))
      assert(p.branches.forall(b => b.misses <= b.retired && b.taken <= b.retired))
      assert(p.branches.forall(b => b.phtTrained <= b.retired &&
        b.untrainedMisses <= b.misses && b.untrainedMisses <= b.retired - b.phtTrained))
      if (trainSlot1 && kernel.name.endsWith("-long"))
        assert(p.branches.forall(b => b.phtTrained == b.retired),
          "warmed conditional branches must retain all training metadata")
      if (expectedBranches == 0) assert(p.branchAccuracy.isEmpty)
      if (!pairBranches) assert(p.pairedBranchCycles == 0)
      if (pairBranches && kernel.name == "call-return") assert(p.pairedBranchCycles > 0)
      val accuracy = p.branchAccuracy.map(a => f"$a%.3f").getOrElse("NA")
      val prefixHistogram = p.rob.groupBy(_.completePrefix).toSeq.sortBy(_._1)
        .map { case (prefix, cycles) => s"$prefix:${cycles.size}" }.mkString(",")
      println(f"PIPELINE_PROFILE kernel=${kernel.name} seed=$seed " +
        s"pairBranches=$pairBranches deferConditionals=$deferConditionals trainSlot1=$trainSlot1 deferTaken=$deferTaken retainHistory=$retainHistory " +
        s"lsFlags=${lsFlags.collect { case (name, true) => name }.toSeq.sorted.mkString(",")} " +
        s"first=${p.firstCycle} last=${p.lastCycle} pairedBranchCycles=${p.pairedBranchCycles} " +
        s"macros=${measured.retiredInstrs} baselineCycles=${control.windowCycles} cycles=${measured.windowCycles} " +
        f"IPC=${measured.ipc}%.6f branches=${p.retiredBranches} misses=${p.branchMisses} accuracy=$accuracy " +
        s"noPairCapacity=${p.noPairCapacityCycles} headIncomplete=${p.headIncompleteCycles} " +
        s"completedBacklog=${p.completedBacklogCycles} dualWithExtraComplete=${p.dualWithExtraCompleteCycles} " +
        s"branchPairPotential=${p.branchPairPotentialCycles} " +
        f"meanOccupancy=${p.meanOccupancy}%.3f completePrefix=$prefixHistogram memory=$memLabel")
    }
  }
}
