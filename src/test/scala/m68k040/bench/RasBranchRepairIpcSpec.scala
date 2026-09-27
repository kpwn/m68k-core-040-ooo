package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** RAS FLUSH-REPAIR (`RasPlugin.branchRepair`) -- the full-core A/B.
  *
  * THE PROPERTY THIS PINS, end to end: after a MISPREDICTED CALL, the callee's `rts` is
  * predicted correctly. The defect is that it is not, and that it is not is worth half of
  * this probe's mispredicts. `checkpointRestore` rolls the RAS back to the last
  * ROB-drain checkpoint, which by construction PREDATES the branch the flush is for -- so
  * a mispredicted call loses its own, already architectural, push and its callee's return
  * finds nothing on the stack. EVERY MISPREDICTED CALL THEREFORE COSTS TWO MISPREDICTS.
  *
  * The metric is `retiredMispredicts` (one per `RobPlugin.branchRedirect`, i.e. exactly
  * what the board's `OFF_MISPRED_COUNT` counts), NOT cycles: the bench mispredicts ~0.2%
  * where the board burns ~9.6% of its cycles on recovery, so a cycle delta here is not
  * the quantity that transfers. The probe drives ONE mispredict source -- an indirect call
  * site -- to a per-iteration event, so the count is a direct, low-variance readout of
  * "how many mispredicts does one unpredictable call cost".
  *
  * The probe shape is `CoreBenchHarness`'s `br-ind` (the branch-attribution suite's
  * indirect-target probe, where the 1024-of-2050 measurement was taken). It is kept LOCAL
  * to this spec so this fix does not depend on that suite landing first.
  *
  * SYNTHETIC UPPER BOUND, deliberately: a dispatch site whose target is unpredictable on
  * every single execution. Read it as "what one mispredicted call costs", not as the Mac
  * workload -- for which the ranking number is the board's, where returns are 34.5% of
  * mispredicts against 3.9% for conditional direction.
  */
class RasBranchRepairIpcSpec extends CoreBenchHarness {

  /** ONE `jsr (%a0)` site whose target cycles through `handlers` leaves, each leaf being
    * `add.l %d1,%d0 ; rts`. The BTB/FTB hold one last-seen target per entry, so with more
    * than one handler in a fixed cycle the stored target is always the PREVIOUS handler
    * and the call mispredicts every time -- which is the input this fix is about.
    *
    * The table is built by the kernel itself (`lea` + absolute store) because the
    * assembled image is attached to the I-side AXI only, so a `prepMem` cannot know the
    * code addresses. Those stores are warm-up.
    *
    * Self-checking: every leaf adds `%d1` (= 1) to `%d0`, so `%d0 == iters` iff the
    * dispatch reached a handler AND returned to the right place on every iteration. A
    * predictor change that takes a wrong path fails here instead of measuring faster. */
  private def kIndirectCall(handlers: Int, iters: Int, label: String, cb: Boolean = true): Kernel = {
    require(handlers >= 2 && (handlers & (handlers - 1)) == 0, "handlers must be a power of two")
    val tab  = 0x30000L
    val mask = handlers * 4 - 1
    val setup = Seq("lea 0x00300000,%sp", f"lea 0x$tab%x,%%a2", "moveq #0,%d2",
                    f"move.l #$mask,%%d3", "moveq #1,%d1", "moveq #0,%d0",
                    s"move.l #$iters,%d7")
    val tabInit = (0 until handlers).flatMap(i =>
      Seq(s"lea .Lh$i,%a0", f"move.l %%a0,0x${tab + i * 4}%x"))
    val body = Seq("move.l (%a2,%d2.l),%a0", "addq.l #4,%d2", "and.l %d3,%d2",
                   "jsr (%a0)", "subq.l #1,%d7", "bne.s .Lbri")
    val handlerCode = (0 until handlers).map(i => s".Lh$i: add.l %d1,%d0 ; rts")
    val src = (setup ++ tabInit ++ Seq(".Lbri: " + body.mkString(" ; "),
      ".Lbriend: bra.s .Lbriend") ++ handlerCode).mkString(" ; ")
    // Per iteration: 6 loop macros + the handler's add + its rts = 8.
    Kernel(label, src, setup.size + tabInit.size + iters * 8,
      copybackDtt = cb,
      warmupInstrs = setup.size + tabInit.size,
      verifyRetirement = obs => {
        val writes = obs.filter(o => o.archRegValid && o.archRegId == 0)
        assert(writes.nonEmpty, "the probe never wrote d0 -- the dispatch never ran")
        val got = writes.last.archRegWrite & 0xffffffffL
        assert(got == iters.toLong,
          f"the probe took the wrong control-flow path: d0 = 0x$got%x, expected $iters")
      })
  }

  /** `depth` nested `bsr`/`rts` pairs per iteration -- the RAS's own capacity probe, kept
    * as the control: nesting INSIDE the 16-entry stack must not get worse. */
  private def kNestedCall(depth: Int, iters: Int, label: String, cb: Boolean = true): Kernel = {
    val setup = Seq("lea 0x00300000,%sp", s"move.l #$iters,%d7", "moveq #1,%d1",
                    "moveq #0,%d0")
    val loop = ".Lrt: bsr .Lf0 ; subq.l #1,%d7 ; bne.s .Lrt"
    val frames = (0 until depth - 1).map(i => s".Lf$i: bsr .Lf${i + 1} ; rts") :+
                 s".Lf${depth - 1}: add.l %d1,%d0 ; rts"
    val src = (setup ++ Seq(loop, ".Lrtend: bra.s .Lrtend") ++ frames).mkString(" ; ")
    Kernel(label, src, setup.size + iters * (2 * depth + 3),
      copybackDtt = cb,
      verifyRetirement = obs => {
        val writes = obs.filter(o => o.archRegValid && o.archRegId == 0)
        assert(writes.nonEmpty, "the probe never wrote d0")
        val got = writes.last.archRegWrite & 0xffffffffL
        assert(got == iters.toLong, f"nested-call probe went wrong: d0 = 0x$got%x, expected $iters")
      })
  }

  test("RAS flush-repair: a mispredicted call must not also cost its callee's return", VerilatorTest) {
    val iters  = 256
    // ⚠️ `-cb` IS THE DEFAULT HERE, and it is not cosmetic. Every one of these probes is
    // store-sensitive by construction -- a call PUSHES and a return POPS -- and the bench
    // default `copybackDtt=false` makes EVERY STORE PRECISE (~12.4 cycles vs ~1.31). That
    // default has already produced two wrong conclusions in this project, and here it also
    // changes the thing under test: precise stores drain the ROB constantly, which makes
    // the ROB-drain checkpoint FRESHER and can hide the defect. The `ras-ind-32-wt` row is
    // the write-through (default-posture) control, kept so the two postures are visible
    // side by side rather than assumed equivalent.
    val probes = Seq(
      kIndirectCall(handlers = 32, iters = iters, label = "ras-ind-32-cb"),
      kIndirectCall(handlers = 32, iters = iters, label = "ras-ind-32-wt", cb = false),
      kIndirectCall(handlers = 2,  iters = iters, label = "ras-ind-2-cb"),
      kNestedCall(depth = 24, iters = 64, label = "ras-nest-24-cb"),
      kNestedCall(depth = 8,  iters = 96, label = "ras-nest-8-cb"),
      kCallReturn)
    // TWO seeds: the AXI ready-randomisers move the flush/refill overlap, so a one-seed
    // mispredict count is not a measurement (the store-stream kernels are +-9.9% on seed
    // alone). The COUNT is expected to be seed-insensitive -- which is itself checked.
    val seeds = Seq(1, 17)

    // Identical trees, one build each, the flag as the only difference.
    def build(on: Boolean) = M68kSim().withVerilator.compile(new FullCoreDut(
      alignedLoadFallThrough = true, earlyLsIntWakeup = true, sqSubwordForwarding = true,
      pairCorrectBranch = true, retainRedirectHistory = true, trainSlot1Conditional = true,
      deferTakenSlot1Conditional = true, earlyStoreAddress = true, fuseLongMoveLoads = true,
      reserveLateStore = true, detachLateStore = true, forwardOnPublish = true,
      earlyLsNzvcWakeup = true, detachedStoreEntries = 4, earlyAutoStoreAddress = true,
      earlyAutoAnWriteback = true, rasBranchRepair = on))

    val arms = Seq(false, true).map { on =>
      val compiled = build(on)
      on -> (for (k <- probes; seed <- seeds) yield {
        val r = runKernel(compiled, k, seed)
        println(f"[ras-repair] on=$on%-5s ${k.name}%-15s seed=$seed%2d " +
          f"retiredMispredicts=${r.retiredMispredicts}%5d retired=${r.retiredInstrs}%6d " +
          f"cycles=${r.windowCycles}%6d IPC=${r.ipc}%5.3f " +
          f"flushRecoveryMean=${r.flushRecoveryMean}%5.2f")
        (k.name, seed) -> r
      }).toMap
    }.toMap

    println()
    println("=" * 96)
    println("  RAS flush-repair A/B -- primary metric is RETIRED MISPREDICTS, not cycles")
    println("=" * 96)
    println(f"${"kernel"}%-15s ${"seed"}%4s ${"mispred OFF"}%11s ${"mispred ON"}%10s ${"delta"}%7s " +
      f"${"IPC OFF"}%8s ${"IPC ON"}%8s")
    for (k <- probes; seed <- seeds) {
      val off = arms(false)((k.name, seed))
      val on  = arms(true)((k.name, seed))
      val d   = if (off.retiredMispredicts == 0) 0.0
                else 100.0 * (on.retiredMispredicts - off.retiredMispredicts) / off.retiredMispredicts
      println(f"${k.name}%-15s $seed%4d ${off.retiredMispredicts}%11d ${on.retiredMispredicts}%10d " +
        f"$d%6.1f%% ${off.ipc}%8.3f ${on.ipc}%8.3f")
    }
    println("=" * 96)

    // ── THE PROPERTY ──────────────────────────────────────────────────────────────
    // `ras-ind-32`'s only unpredictable branch is the `jsr (%a0)`: one mispredict per
    // iteration is the floor, and TWO per iteration is the defect (the call, plus its
    // callee's `rts`, whose RAS entry the restore threw away).
    for (probe <- Seq("ras-ind-32-cb", "ras-ind-32-wt"); seed <- seeds) {
      val off = arms(false)((probe, seed)).retiredMispredicts
      val on  = arms(true)((probe, seed)).retiredMispredicts
      assert(off >= 1.8 * iters,
        s"[$probe seed $seed] premise: without the repair the probe must cost ~2 mispredicts per " +
        s"iteration (got $off for $iters iterations) -- if this fails the probe is not " +
        "measuring the double mispredict any more and the assertion below proves nothing")
      assert(on <= 1.25 * iters,
        s"[$probe seed $seed] with the repair a mispredicted call must cost ~ONE mispredict, not two " +
        s"(got $on for $iters iterations, off=$off): the callee's rts is still not predicted")
      assert(on < 0.65 * off,
        s"[$probe seed $seed] the repair must remove the callee-return mispredict bucket outright " +
        s"(off=$off on=$on)")
    }
    // Controls: nothing else may get WORSE. A blind re-push would show up here, as a
    // deeper-than-architectural stack moving the mispredict to the caller's return.
    for (k <- probes; seed <- seeds) {
      val off = arms(false)((k.name, seed)).retiredMispredicts
      val on  = arms(true)((k.name, seed)).retiredMispredicts
      assert(on <= off + 2,
        s"[${k.name} seed $seed] the repair must not ADD retired mispredicts (off=$off on=$on)")
    }
  }
}
