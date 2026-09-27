package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** Track 6 — the strcmp recurrence (load -> compare -> conditional branch) that the
  * board spends its retire stall in, and which this suite had no instance of.
  *
  * Two things are measured here, and they are separable:
  *
  *  (a) IS THE KERNEL THE RIGHT SHAPE? The census line reports, per run, the branch
  *      mispredicts per kilo-instruction and the D-cache load miss ratio, against the
  *      board's own numbers (MPKI 21.41, dc-miss 1.17/kinst, dc-stall 0.83%). A kernel
  *      that misses the cache is a DIFFERENT benchmark and the line says so.
  *
  *  (b) DOES AN OPTION MOVE IT? `strcmp-mem` and `strcmp-reg` retire the same
  *      instructions with the same branch outcomes and differ only in whether the
  *      compare's source operand is a load, so a delta can be attributed. Three seeds,
  *      because store-sensitive kernels in this suite swing ±9.9% seed to seed -- these
  *      hold no stores and MEASURE bit-identical across seeds 1/17/101, so a delta here
  *      needs no seed averaging. That is a property of these kernels, not a licence to
  *      drop the seeds elsewhere.
  *
  * Arms come from `extraArms` so a branch that carries a further option adds one line
  * and nothing else. `STRCMP_AB=1` runs the whole sweep; unset runs the single baseline
  * arm at one seed, which is the kernel's own correctness/shape validation.
  */
class StrcmpIpcSpec extends CoreBenchHarness {

  /** The arms under test. `master` has exactly one: the baseline core. A branch that
    * carries an extra issue-queue option adds its arms to `extraArms` and changes
    * NOTHING else in this file -- that is the whole point of the indirection, so this
    * kernel can be cherry-picked onto any feature branch and measured there. */
  protected def extraArms: Seq[(String, () => FullCoreDut)] = Seq(
    // `specLoadWakeup` on the BASELINE core: the minimal controlled pair. Nothing else
    // differs, so a delta is the announce and nothing else.
    "specwake" -> (() => new FullCoreDut(specLoadWakeup = true)),
    // And the pair the BOARD would actually ship: the throughput-v2 option set the socket
    // build turns on, with and without the announce. Select it with
    // STRCMP_ARMS=v2,v2-specwake so `v2` becomes the baseline the delta is taken against
    // -- a v2 arm measured against the all-options-off baseline attributes nothing.
    "v2"          -> (() => v2Dut(specWake = false)),
    "v2-specwake" -> (() => v2Dut(specWake = true)))

  /** The board's `throughput-v2` option set (SocketTop `ipcThroughput` + `lateStore`),
    * mirroring `IpcBenchSpec`'s IPC_V2=1 DUT so the two suites' arms are the same core. */
  private def v2Dut(specWake: Boolean): FullCoreDut = new FullCoreDut(
    alignedLoadFallThrough = true, earlyLsIntWakeup = true, sqSubwordForwarding = true,
    pairCorrectBranch = true, retainRedirectHistory = true, trainSlot1Conditional = true,
    earlyStoreAddress = true, fuseLongMoveLoads = true, reserveLateStore = true,
    detachLateStore = true, forwardOnPublish = true, earlyLsNzvcWakeup = true,
    detachedStoreEntries = 4, earlyAutoStoreAddress = true, earlyStoreDataWake = true,
    specLoadWakeup = specWake)

  private def allArms: Seq[(String, () => FullCoreDut)] =
    Seq[(String, () => FullCoreDut)]("off" -> (() => new FullCoreDut())) ++
      (if (sys.env.get("STRCMP_AB").contains("1")) extraArms else Nil)

  /** STRCMP_ARMS=a,b selects a subset by name, first one becoming the baseline the
    * deltas are taken against. Needed because some instrumentation is only reachable
    * on some arms: SPEC_CEIL=1 reads `lsEu.wakeupSpec`, which does not exist on a DUT
    * built without the speculative announce, so the ledger run cannot include the
    * baseline arm and has to be a separate invocation. */
  private def arms: Seq[(String, () => FullCoreDut)] =
    sys.env.get("STRCMP_ARMS") match {
      case Some(sel) =>
        val names = sel.split(',').map(_.trim).toSet
        val picked = allArms.filter(a => names.contains(a._1))
        require(picked.nonEmpty, s"STRCMP_ARMS=$sel selected no arm of ${allArms.map(_._1)}")
        picked
      case None => allArms
    }

  private def seeds: Seq[Int] =
    if (sys.env.get("STRCMP_AB").contains("1")) Seq(1, 17, 101) else Seq(1)

  /** The `-cb` (COPYBACK) variants are the ones to read: the default posture makes
    * every store precise, which has produced three wrong conclusions in this campaign.
    * These kernels hold no stores at all, so the posture should not matter -- STRCMP_ALL=1
    * adds the default-posture siblings so that claim is measured, not assumed. */
  private def kernels: Seq[Kernel] =
    if (sys.env.get("STRCMP_ALL").contains("1")) strcmpKernels
    else Seq(kStrcmp("mem", copyback = true), kStrcmp("reg", copyback = true),
             kStrcmp("pair", copyback = true))

  test("strcmp load->compare->branch recurrence across speculative-wakeup arms", VerilatorTest) {
    val ks = kernels
    val results = arms.map { case (arm, build) =>
      val compiled = M68kSim().withVerilator.compile(build())
      arm -> (for (k <- ks; seed <- seeds) yield {
        val r = runKernel(compiled, k, seed)
        val p = r.pipelineProfile.get
        val lines = r.ldCmdAddrs.map(_ >> 4).distinct.size
        println(f"STRCMP arm=$arm kernel=${k.name} seed=$seed " +
          f"macros=${r.retiredInstrs} cycles=${r.windowCycles} IPC=${r.ipc}%.6f " +
          f"dual%%=${r.dualPctActive}%.1f active%%=${r.activePct}%.1f")
        println(f"STRCMP_CENSUS arm=$arm kernel=${k.name} seed=$seed " +
          f"branches=${p.retiredBranches} mispredicts=${p.branchMisses} " +
          f"MPKI=${1000.0 * p.branchMisses / r.retiredInstrs}%.2f (board 21.41) " +
          f"dcLoadMisses=${r.dcLoadMisses} acceptedLoads=${r.ldCmdAddrs.size} " +
          f"missRatio=${100.0 * r.dcLoadMisses / scala.math.max(1, r.ldCmdAddrs.size)}%.2f%% " +
          f"missPerKinst=${1000.0 * r.dcLoadMisses / r.retiredInstrs}%.2f (board 1.17) " +
          f"distinct16BLines=$lines s1Lookups=${r.dcLoadLookups} " +
          f"retireStall%%=${100.0 * p.nonemptyNoRetireCycles / r.windowCycles}%.2f (board 52.22)")
        (k.name, seed) -> r
      }).toMap
    }
    // Paired deltas against the first arm, per kernel AND per seed: one aggregate can
    // rest entirely on one kernel at one seed, which has already happened on a sibling
    // track, so every pair is printed individually.
    val (baseArm, base) = results.head
    for ((arm, res) <- results.tail; k <- ks; seed <- seeds) {
      val b = base((k.name, seed)); val a = res((k.name, seed))
      assert(b.retiredInstrs == a.retiredInstrs,
        s"[${k.name}] arm $arm retired ${a.retiredInstrs} vs $baseArm ${b.retiredInstrs}")
      println(f"STRCMP_GAIN arm=$arm vs=$baseArm kernel=${k.name} seed=$seed " +
        f"cycles ${b.windowCycles}->${a.windowCycles} IPC ${b.ipc}%.6f->${a.ipc}%.6f " +
        f"gain=${a.ipc / b.ipc - 1}%+.6f")
    }
    // The mem/reg pair is matched by construction: same macros, same branch outcomes.
    for (seed <- seeds; (arm, res) <- results) {
      for (mem <- res.get(("strcmp-mem-cb", seed)); reg <- res.get(("strcmp-reg-cb", seed)))
        println(f"STRCMP_MEMOPERAND arm=$arm seed=$seed macros=${mem.retiredInstrs}/${reg.retiredInstrs} " +
          f"cycles mem=${mem.windowCycles} reg=${reg.windowCycles} " +
          f"IPC mem=${mem.ipc}%.6f reg=${reg.ipc}%.6f " +
          f"memOperandCost=${reg.ipc / mem.ipc - 1}%+.4f")
    }
  }
}
