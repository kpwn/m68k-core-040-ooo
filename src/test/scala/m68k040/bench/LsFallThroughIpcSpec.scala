package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** Matched full-core experiment, including the real IQ and ROB. This is not a
  * whole-system performance claim: kernels use transparent cacheable mappings
  * and the harness's explicit simulation memory model. */
class LsFallThroughIpcSpec extends CoreBenchHarness {
  // The I-side model deliberately randomizes bytes outside the image. A
  // predicted fall-through at loop warm-up must encounter harmless code, not
  // random memory instructions that contaminate the access-order measurement.
  private val guard = " ; .LlatencyGuard: bra.s .LlatencyGuard ; .rept 64 ; nop ; .endr"
  private def delayedStore: Kernel = {
    val setup = Seq("moveq #3,%d1", "move.l 0x4600,%d2", "move.l 0x4610,%d3")
    val body = Seq.fill(32)(Seq("move.l #0x10000,%d0", "divu.w %d1,%d0",
      "move.l %d0,0x4600", "move.l 0x4610,%d3", "addq.l #1,%d3")).flatten
    Kernel("delayed-store-disjoint-load", (setup ++ body).mkString(" ; ") + guard,
      setup.size + body.size, copybackDtt = true)
  }
  private def pointerChain: Kernel = {
    val iters = 64
    val setup = Seq("lea 0x4000,%a0", s"moveq #$iters,%d7",
      "movea.l (%a0),%a0", "movea.l (%a0),%a0")
    val body = Seq.fill(4)("movea.l (%a0),%a0").mkString(" ; ")
    Kernel("fallthrough-pointer-chain",
      setup.mkString(" ; ") + " ; .Lchase: " + body +
        " ; subq.l #1,%d7 ; bne.s .Lchase" + guard,
      setup.size + iters * 6, copybackDtt = true,
      prepMem = m => {
        for ((addr, next) <- Seq(0x4000L -> 0x4010L, 0x4010L -> 0x4000L);
             b <- 0 until 16) {
          m.dmem.pokeByte(addr + b,
            if(b < 4) ((next >>> (24 - 8 * b)) & 255).toInt else 0)
        }
      })
  }

  test("fall-through improves full-core dependent-load IPC without changing access order", VerilatorTest) {
    val stream = kLoadStream
    val mixed = kSameLineCopyback
    val kernels = Seq(pointerChain,
      stream.copy(src = stream.src + guard, copybackDtt = true),
      mixed.copy(src = mixed.src + guard)) ++
      Seq(kDependentAlu, kIndependentAlu, kHotLoop, kLoadStore, kMixed,
        kStoreStream, kCallReturn).map(k => k.copy(src = k.src + guard)) ++
      Seq(kLoadStore, kMixed).map(k => k.copy(name = k.name + "-copyback",
        src = k.src + guard, copybackDtt = true)) ++ Seq(delayedStore)
    val seeds = Seq(1, 17)
    val modes = Seq((false, false), (true, false), (false, true), (true, true))
    val results = modes.map { case (enabled, earlyWake) =>
      val compiled = M68kSim().withVerilator.compile(new FullCoreDut(
        alignedLoadFallThrough = enabled, earlyLsIntWakeup = earlyWake,
        sqSubwordForwarding = sys.env.get("IPC_SQ_SUBWORD").contains("1"),
        deferSlot1Conditional = sys.env.get("IPC_DEFER_CONDITIONAL").contains("1"),
        trainSlot1Conditional = sys.env.get("IPC_TRAIN_SLOT1").contains("1") || sys.env.get("IPC_DEFER_TAKEN_SLOT1").contains("1"),
        deferTakenSlot1Conditional = sys.env.get("IPC_DEFER_TAKEN_SLOT1").contains("1")))
      (for(k <- kernels; seed <- seeds) yield {
        val r = runKernel(compiled, k, seed)
        assert(r.retiredInstrs >= k.retiredInstrs)
        if(k.name == pointerChain.name) {
          assert(r.ldCmdAddrs.size >= 258, s"pointer chase did not execute its loads: ${r.ldCmdAddrs.size}")
          r.ldCmdAddrs.zipWithIndex.foreach { case (addr, n) =>
            assert(addr == (if((n & 1) == 0) 0x4000L else 0x4010L),
              f"pointer chase diverged at load $n: address=0x$addr%x")
          }
        }
        println(f"LS_FULL_CORE fallThrough=$enabled earlyWake=$earlyWake seed=$seed kernel=${k.name} " +
          f"retired=${r.retiredInstrs} cycles=${r.windowCycles} IPC=${r.ipc}%.6f")
        if(k.name == pointerChain.name) {
          val gaps = r.ldCmdCycles.sliding(2).collect { case Seq(a, b) => b - a }.toSeq
          println(s"LS_FULL_CORE_LOAD_SPACING fallThrough=$enabled earlyWake=$earlyWake seed=$seed " +
            gaps.groupBy(identity).toSeq.sortBy(_._1).map { case (gap, xs) => s"$gap:${xs.size}" }.mkString(","))
        }
        (k.name, seed) -> r
      }).toMap
    }
    for(k <- kernels; seed <- seeds; mode <- 1 until modes.size) {
      val before = results(0)((k.name, seed))
      val after = results(mode)((k.name, seed))
      assert(after.retiredInstrs == before.retiredInstrs,
        s"matched ${k.name} windows retired different instruction counts")
      if(k.name == pointerChain.name) {
        assert(after.windowCycles < before.windowCycles,
          s"shorter LSU path did not improve dependent-load full-core IPC for seed $seed")
      }
      println(f"LS_FULL_CORE_GAIN mode=${modes(mode)} seed=$seed kernel=${k.name} gain=${after.ipc / before.ipc - 1}%.6f memory=$memLabel")
    }
  }
}
