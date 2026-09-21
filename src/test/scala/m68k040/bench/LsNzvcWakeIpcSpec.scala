package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** Targeted dependency controls, not representative system performance. */
class LsNzvcWakeIpcSpec extends CoreBenchHarness {
  private val guard = " ; .LflagDone: bra.s .LflagDone ; .LflagFail: illegal ; .rept 64 ; nop ; .endr"
  private def lastReg(reg: Int, expected: Long)(obs: Seq[m68k040.lockstep.CommitObservation]): Unit = {
    val got = obs.filter(o => o.archRegValid && o.archRegId == reg).last.archRegWrite & 0xffffffffL
    assert(got == expected, s"register $reg: $got != $expected")
  }
  private def recurrence(load: Boolean, copyback: Boolean): Kernel = {
    val setup = Seq("lea 0x4600,%a0", "moveq #0,%d0", "move.l %d0,(%a0)")
    val body = if(load) Seq("move.l (%a0),%d0", "seq %d0", "move.l %d0,(%a0)")
      else Seq("move.b %d0,(%a0)", "seq %d0")
    Kernel(s"${if(load) "load" else "store"}-seq-${if(copyback) "copyback" else "precise"}",
      (setup ++ Seq.fill(96)(body).flatten).mkString(" ; ") + guard,
      setup.size + 96 * body.size, copybackDtt = copyback,
      warmupInstrs = setup.size + 16 * body.size, verifyRetirement = lastReg(0, 0))
  }
  private def branch(value: Int): Kernel = {
    val setup = Seq("lea 0x4600,%a0", s"moveq #$value,%d0", "move.l %d0,(%a0)",
      "moveq #0,%d2", "moveq #64,%d7")
    val body = Seq("move.l (%a0),%d0", s"${if(value == 0) "bne" else "beq"}.w .LflagFail",
      "addq.l #1,%d2", "subq.l #1,%d7", "bne.s .LflagLoop")
    Kernel(s"load-branch-$value", setup.mkString(" ; ") + " ; .LflagLoop: " + body.mkString(" ; ") + guard,
      setup.size + 64 * body.size, copybackDtt = true,
      warmupInstrs = setup.size + 16 * body.size, verifyRetirement = obs => {
        lastReg(0, value)(obs); lastReg(2, 64)(obs); lastReg(7, 0)(obs)
      }, profileRetirement = true)
  }

  test("selected NZVC wakeup compares matched full-core flag dependencies", VerilatorTest) {
    val kernels = (for(load <- Seq(false, true); copyback <- Seq(false, true))
      yield recurrence(load, copyback)) ++ Seq(branch(0), branch(1))
    val result = Seq(false, true).map { early =>
      val dut = M68kSim().withVerilator.compile(new FullCoreDut(
        alignedLoadFallThrough = true, earlyLsIntWakeup = true,
        earlyStoreAddress = true, fuseLongMoveLoads = true,
        reserveLateStore = true, detachLateStore = true, forwardOnPublish = true,
        trainSlot1Conditional = true, deferTakenSlot1Conditional = true,
        retainRedirectHistory = true, earlyLsNzvcWakeup = early))
      (for(k <- kernels; seed <- Seq(1, 17)) yield {
        val r = runKernel(dut, k, seed)
        val profile = r.pipelineProfile
        if(k.name.startsWith("load-branch")) assert(profile.get.retiredBranches == 96)
        println(f"LS_NZVC_IPC early=$early kernel=${k.name} seed=$seed retired=${r.retiredInstrs} " +
          f"cycles=${r.windowCycles} IPC=${r.ipc}%.6f " +
          s"branches=${profile.map(_.retiredBranches).getOrElse(0)} misses=${profile.map(_.branchMisses).getOrElse(0)}")
        (k.name, seed) -> r
      }).toMap
    }
    for(k <- kernels; seed <- Seq(1, 17)) {
      val a = result(0)((k.name, seed)); val b = result(1)((k.name, seed))
      assert(a.retiredInstrs == b.retiredInstrs)
      println(f"LS_NZVC_GAIN kernel=${k.name} seed=$seed gain=${b.ipc / a.ipc - 1}%.6f memory=$memLabel")
    }
  }
}
