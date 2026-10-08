package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** RAS PER-BRANCH SNAPSHOT (`CPU_RAS_BRANCH_SNAPSHOT`, RasPlugin `branchSnapshot`) -- the
  * full-core A/B, with `branchRepair` ON in both arms (the shipping posture).
  *
  * THE PROPERTY: a mispredict anywhere inside a call tree must not cost the returns of
  * that tree. Without the snapshot the Tier-1 redirect restores the RAS to the last
  * ROB-drain checkpoint, which in busy code predates every call fetched since -- so the
  * returns above the mispredicting branch find a stale (or empty) stack and mispredict
  * too. `branchRepair` re-applies only the branch's OWN push/pop, so it cannot help when
  * the mispredicting branch is a plain conditional.
  *
  * Metric: `retiredMispredicts` (exactly what the board's `OFF_MISPRED_COUNT` counts).
  * The `ras-cond-*` probes' only unpredictable branch is an LFSR-driven `bcc` (~50%
  * mispredicts); everything above that floor is return damage. */
class RasBranchSnapshotIpcSpec extends CoreBenchHarness {

  /** loop -> f -> g (LFSR `bcc`, both paths 4 macros) ; f -> h. 13 macros/iteration,
    * d0 += 2 per iteration (self-checking: any wrong-path retirement breaks the sum). */
  private def kCondInCallTree(iters: Int, label: String, depth: Int = 1, cb: Boolean = true): Kernel = {
    val setup = Seq("lea 0x00300000,%sp", s"move.l #$iters,%d7", "moveq #1,%d1", "moveq #0,%d0",
                    "move.l #0x12345679,%d5", "move.l #0x80200003,%d4", "moveq #0,%d6")
    // `depth` extra wrapper frames between the loop and f, to give the stack something
    // a stale checkpoint can lose.
    val wrappers = (0 until depth).map(i =>
      if (i == depth - 1) s".Lw$i: bsr .Lsf ; rts" else s".Lw$i: bsr .Lw${i + 1} ; rts")
    val src = (setup ++ Seq(".Lsl: bsr .Lw0 ; subq.l #1,%d7 ; bne.s .Lsl", ".Lslend: bra.s .Lslend") ++
      wrappers ++ Seq(
        ".Lsf: bsr .Lsg ; add.l %d1,%d0 ; bsr .Lsh ; rts",
        ".Lsg: lsr.l #1,%d5 ; bcc.s .Lsg1 ; eor.l %d4,%d5 ; rts",
        ".Lsg1: or.l %d1,%d6 ; rts",
        ".Lsh: add.l %d1,%d0 ; rts")).mkString(" ; ")
    Kernel(label, src, setup.size + iters * (13 + 2 * depth),
      copybackDtt = cb,
      warmupInstrs = setup.size,
      verifyRetirement = obs => {
        val writes = obs.filter(o => o.archRegValid && o.archRegId == 0)
        assert(writes.nonEmpty, "the probe never wrote d0")
        val got = writes.last.archRegWrite & 0xffffffffL
        assert(got == 2L * iters, f"call-tree probe went wrong: d0 = 0x$got%x, expected ${2 * iters}")
      })
  }

  /** `RasBranchRepairIpcSpec`'s indirect-call probe (one unpredictable `jsr (%a0)`). */
  private def kIndirectCall(handlers: Int, iters: Int, label: String): Kernel = {
    val tab  = 0x30000L
    val mask = handlers * 4 - 1
    val setup = Seq("lea 0x00300000,%sp", f"lea 0x$tab%x,%%a2", "moveq #0,%d2",
                    f"move.l #$mask,%%d3", "moveq #1,%d1", "moveq #0,%d0", s"move.l #$iters,%d7")
    val tabInit = (0 until handlers).flatMap(i => Seq(s"lea .Lh$i,%a0", f"move.l %%a0,0x${tab + i * 4}%x"))
    val body = Seq("move.l (%a2,%d2.l),%a0", "addq.l #4,%d2", "and.l %d3,%d2",
                   "jsr (%a0)", "subq.l #1,%d7", "bne.s .Lbri")
    val handlerCode = (0 until handlers).map(i => s".Lh$i: add.l %d1,%d0 ; rts")
    val src = (setup ++ tabInit ++ Seq(".Lbri: " + body.mkString(" ; "),
      ".Lbriend: bra.s .Lbriend") ++ handlerCode).mkString(" ; ")
    Kernel(label, src, setup.size + tabInit.size + iters * 8, copybackDtt = true,
      warmupInstrs = setup.size + tabInit.size,
      verifyRetirement = obs => {
        val got = obs.filter(o => o.archRegValid && o.archRegId == 0).last.archRegWrite & 0xffffffffL
        assert(got == iters.toLong, f"indirect-call probe went wrong: d0 = 0x$got%x")
      })
  }

  test("RAS snapshot: a mispredict inside a call tree must not cost its returns", VerilatorTest) {
    val iters = 256
    val probes = Seq(
      kCondInCallTree(iters, "ras-cond-d1-cb", depth = 1),
      kCondInCallTree(iters, "ras-cond-d4-cb", depth = 4),
      kCondInCallTree(iters, "ras-cond-d1-wt", depth = 1, cb = false),
      kIndirectCall(handlers = 32, iters = iters, label = "ras-ind-32-cb"),
      kCallReturn)
    val seeds = Seq(1, 17)
    def build(on: Boolean) = M68kSim().withVerilator.compile(new FullCoreDut(
      alignedLoadFallThrough = true, earlyLsIntWakeup = true, sqSubwordForwarding = true,
      pairCorrectBranch = true, retainRedirectHistory = true, trainSlot1Conditional = true,
      deferTakenSlot1Conditional = true, earlyStoreAddress = true, fuseLongMoveLoads = true,
      reserveLateStore = true, detachLateStore = true, forwardOnPublish = true,
      earlyLsNzvcWakeup = true, detachedStoreEntries = 4, earlyAutoStoreAddress = true,
      earlyAutoAnWriteback = true, rasBranchRepair = true, rasBranchSnapshot = on))

    val arms = Seq(false, true).map { on =>
      val compiled = build(on)
      on -> (for (k <- probes; seed <- seeds) yield {
        val r = runKernel(compiled, k, seed)
        println(f"[ras-snap] on=$on%-5s ${k.name}%-15s seed=$seed%2d " +
          f"retiredMispredicts=${r.retiredMispredicts}%5d retired=${r.retiredInstrs}%6d " +
          f"cycles=${r.windowCycles}%6d IPC=${r.ipc}%5.3f")
        (k.name, seed) -> r
      }).toMap
    }.toMap

    println()
    println("=" * 96)
    println("  RAS per-branch snapshot A/B (branchRepair ON in both) -- RETIRED MISPREDICTS")
    println("=" * 96)
    println(f"${"kernel"}%-15s ${"seed"}%4s ${"mispred OFF"}%11s ${"mispred ON"}%10s ${"delta"}%7s " +
      f"${"cyc OFF"}%8s ${"cyc ON"}%8s ${"dcyc"}%7s")
    for (k <- probes; seed <- seeds) {
      val off = arms(false)((k.name, seed)); val on = arms(true)((k.name, seed))
      def pct(a: Double, b: Double) = if (a == 0) 0.0 else 100.0 * (b - a) / a
      println(f"${k.name}%-15s $seed%4d ${off.retiredMispredicts}%11d ${on.retiredMispredicts}%10d " +
        f"${pct(off.retiredMispredicts, on.retiredMispredicts)}%6.1f%% ${off.windowCycles}%8d " +
        f"${on.windowCycles}%8d ${pct(off.windowCycles, on.windowCycles)}%6.1f%%")
    }
    println("=" * 96)

    // Nothing may get worse (a +2 allowance for seed-level flush/refill overlap).
    for (k <- probes; seed <- seeds) {
      val off = arms(false)((k.name, seed)).retiredMispredicts
      val on  = arms(true)((k.name, seed)).retiredMispredicts
      assert(on <= off + 2, s"[${k.name} seed $seed] the snapshot must not ADD mispredicts (off=$off on=$on)")
    }
    // The property: the call-tree probes lose their return-mispredict excess. The `bcc`
    // floor is ~iters/2; the snapshot must take the copyback probes most of the way there.
    for (probe <- Seq("ras-cond-d1-cb", "ras-cond-d4-cb"); seed <- seeds) {
      val off = arms(false)((probe, seed)).retiredMispredicts
      val on  = arms(true)((probe, seed)).retiredMispredicts
      assert(on < off, s"[$probe seed $seed] the snapshot must remove return mispredicts (off=$off on=$on)")
    }
  }
}
