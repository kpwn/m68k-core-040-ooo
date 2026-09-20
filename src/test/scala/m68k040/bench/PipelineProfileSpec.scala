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
    val compiled = M68kSim().withVerilator.compile(new FullCoreDut(pairCorrectBranch = pairBranches))
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
      if (expectedBranches == 0) assert(p.branchAccuracy.isEmpty)
      if (!pairBranches) assert(p.pairedBranchCycles == 0)
      if (pairBranches && kernel.name == "call-return") assert(p.pairedBranchCycles > 0)
      val accuracy = p.branchAccuracy.map(a => f"$a%.3f").getOrElse("NA")
      println(f"PIPELINE_PROFILE kernel=${kernel.name} seed=$seed " +
        s"pairBranches=$pairBranches " +
        s"first=${p.firstCycle} last=${p.lastCycle} pairedBranchCycles=${p.pairedBranchCycles} " +
        s"macros=${measured.retiredInstrs} baselineCycles=${control.windowCycles} cycles=${measured.windowCycles} " +
        f"IPC=${measured.ipc}%.6f branches=${p.retiredBranches} misses=${p.branchMisses} accuracy=$accuracy " +
        s"noPairCapacity=${p.noPairCapacityCycles} headIncomplete=${p.headIncompleteCycles} " +
        s"completedBacklog=${p.completedBacklogCycles} dualWithExtraComplete=${p.dualWithExtraCompleteCycles} " +
        s"branchPairPotential=${p.branchPairPotentialCycles} memory=$memLabel")
    }
  }
}
