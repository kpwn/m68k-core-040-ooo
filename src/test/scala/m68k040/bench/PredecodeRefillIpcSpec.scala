package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** Refill-heavy counterweight to the small, resident board byte-copy loop.
  * Paired seeds/options, exact architectural results; simulation, not board IPC.
  */
class PredecodeRefillIpcSpec extends CoreBenchHarness {
  private val sequential = Kernel("predecode-linear-8k",
    ".rept 4096 ; moveq #7,%d0 ; .endr ; .Ldone: bra.s .Ldone ; .rept 64 ; nop ; .endr",
    retiredInstrs = 4096, warmupInstrs = 32,
    verifyRetirement = obs => {
      val writes = obs.filter(o => o.archRegValid && o.archRegId == 0)
      assert(writes.size == 4096 && writes.forall(_.archRegWrite == 7))
    })

  private def sparse(shuffled: Boolean): Kernel = {
    val n = 512
    val order = Vector(0) ++ (if (shuffled) new scala.util.Random(0x68040L).shuffle((1 until n).toVector)
      else (1 until n).toVector)
    val next = order.zip(order.drop(1).map(i => s".Lnode$i") :+ ".Ldone").toMap
    val source = (0 until n).map { i =>
      // Exactly 64 bytes per node: MOVEQ(2), BRA.W(4), skipped NOPs(58).
      s".Lnode$i: moveq #${i % 64},%d0 ; bra.w ${next(i)} ; .rept 29 ; nop ; .endr"
    }.mkString(" ; ") + " ; .Ldone: bra.s .Ldone ; .rept 64 ; nop ; .endr"
    Kernel(s"predecode-sparse-32k-shuffled-$shuffled", source,
      retiredInstrs = n * 2, warmupInstrs = 16,
      verifyRetirement = obs => {
        val actual = obs.filter(o => o.archRegValid && o.archRegId == 0).map(_.archRegWrite)
        assert(actual == order.map(i => (i % 64).toLong), "sparse branch chain retired wrong data/order")
      })
  }

  test("paired refill-heavy IPC for sixteen and eight classifiers", VerilatorTest) {
    val kernels = Seq(sequential, sparse(false), sparse(true))
    val results = Seq(16, 8).map { width =>
      val compiled = M68kSim().withVerilator.compile(new FullCoreDut(
        alignedLoadFallThrough = true, earlyLsIntWakeup = true,
        sqSubwordForwarding = true, earlyStoreAddress = true,
        fuseLongMoveLoads = true, reserveLateStore = true,
        detachLateStore = true, forwardOnPublish = true,
        earlyLsNzvcWakeup = true, detachedStoreEntries = 4,
        trainSlot1Conditional = true, deferTakenSlot1Conditional = true,
        retainRedirectHistory = true, earlyAutoStoreAddress = true,
        earlyStoreDataWake = true, pcRangeEnable = false, icachePredecodeWords = width))
      (for (kernel <- kernels; seed <- Seq(1, 17)) yield {
        val r = runKernel(compiled, kernel, seed)
        println(f"PREDECODE_REFILL_IPC width=$width kernel=${kernel.name} seed=$seed " +
          f"retired=${r.retiredInstrs} cycles=${r.windowCycles} IPC=${r.ipc}%.6f memory=$memLabel")
        (kernel.name, seed) -> r
      }).toMap
    }
    for (kernel <- kernels; seed <- Seq(1, 17)) {
      val before = results(0)((kernel.name, seed))
      val after = results(1)((kernel.name, seed))
      assert(before.retiredInstrs == after.retiredInstrs)
      println(f"PREDECODE_REFILL_GAIN kernel=${kernel.name} seed=$seed gain=${after.ipc / before.ipc - 1}%.6f")
    }
  }
}
