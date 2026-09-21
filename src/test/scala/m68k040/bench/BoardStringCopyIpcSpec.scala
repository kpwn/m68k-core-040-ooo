package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** Four-instruction byte-copy loop observed in the running Mac's Dhrystone
  * window at 03be19c2/19c6/19ca/19cc. This reproduces the instruction/dependency
  * shape, not the whole executable, its virtual-memory layout, or its board IPC.
  * The paired readback variant verifies every destination byte, including NUL.
  */
class BoardStringCopyIpcSpec extends CoreBenchHarness {
  private def byteAt(index: Int): Int = (index * 73 + 19) % 255 + 1

  private def copyKernel(length: Int, verifyBytes: Boolean): Kernel = {
    val setup = Seq("lea 0x4600,%a6", "lea 0x5000,%a1", "move.l #0x4000,12(%a6)")
    val body = Seq("movea.l 12(%a6),%a0", "addq.l #1,12(%a6)",
      "move.b (%a0),(%a1)+", "bne.s .LboardCopy")
    val ending = Seq("move.l 12(%a6),%d1", "move.l %a1,%d2")
    val checks = if (verifyBytes)
      Seq("moveq #0,%d0") ++ (0 to length).map(i => f"move.b 0x${0x5000 + i}%x,%%d0")
    else Seq.empty
    Kernel(s"board-byte-copy-$length${if (verifyBytes) "-verify" else ""}",
      setup.mkString(" ; ") + " ; .LboardCopy: " + body.mkString(" ; ") + " ; " +
        (ending ++ checks).mkString(" ; ") + " ; .LboardDone: bra.s .LboardDone ; .rept 64 ; nop ; .endr",
      setup.size + (length + 1) * body.size + ending.size + checks.size,
      copybackDtt = true, warmupInstrs = setup.size + 8 * body.size,
      prepMem = m => {
        for (i <- 0 to length) {
          m.dmem.pokeByte(0x4000L + i, if (i == length) 0 else byteAt(i))
          m.dmem.pokeByte(0x5000L + i, 0xa5)
        }
      },
      verifyRetirement = obs => {
        def lastReg(id: Int): Long =
          obs.filter(o => o.archRegValid && o.archRegId == id).last.archRegWrite & 0xffffffffL
        assert(lastReg(1) == 0x4000L + length + 1, "source pointer did not reach NUL")
        assert(lastReg(2) == 0x5000L + length + 1, "destination pointer did not reach NUL")
        if (verifyBytes) {
          val actual = obs.filter(o => o.archRegValid && o.archRegId == 0)
            .map(o => (o.archRegWrite & 255).toInt).drop(1) // skip MOVEQ initialization
          assert(actual == (0 until length).map(byteAt) :+ 0,
            s"copied bytes differ: $actual")
        }
      }, profileRetirement = true)
  }

  test("board byte-copy dependency shape compares baseline and combined socket options", VerilatorTest) {
    val traceOnly = sys.env.get("IPC_BOARD_COPY_TRACE").contains("1")
    val debugProfile = sys.env.getOrElse("CPU_DEBUG_PROFILE", "full")
    val pcRangeEnable = m68k040.top.SocketDebugProfile.pcRangeEnabled(debugProfile)
    println(s"BOARD_COPY_DEBUG_PROFILE=$debugProfile")
    val seeds = if (traceOnly) Seq(1) else Seq(1, 17)
    val kernels = if (traceOnly) Seq(copyKernel(32, false)) else
      for (length <- Seq(32, 128); verify <- Seq(false, true)) yield copyKernel(length, verify)
    val results = Seq(false, true).map { combined =>
      val earlyAuto = combined && sys.env.get("IPC_EARLY_AUTO_STORE").contains("1")
      val earlyDataWake = combined && sys.env.get("IPC_EARLY_STORE_DATA_WAKE").contains("1")
      println(s"BOARD_COPY_PROFILE combined=$combined earlyAuto=$earlyAuto earlyDataWake=$earlyDataWake traceOnly=$traceOnly")
      val compiled = M68kSim().withVerilator.compile(new FullCoreDut(
        alignedLoadFallThrough = combined, earlyLsIntWakeup = combined,
        sqSubwordForwarding = combined, earlyStoreAddress = combined,
        fuseLongMoveLoads = combined, reserveLateStore = combined,
        detachLateStore = combined, forwardOnPublish = combined,
        earlyLsNzvcWakeup = combined, detachedStoreEntries = if (combined) 4 else 1,
        trainSlot1Conditional = combined, deferTakenSlot1Conditional = combined,
        retainRedirectHistory = combined, earlyAutoStoreAddress = earlyAuto,
        earlyStoreDataWake = earlyDataWake, pcRangeEnable = pcRangeEnable))
      (for (kernel <- kernels; seed <- seeds) yield {
        val r = runKernel(compiled, kernel, seed)
        if (earlyAuto) assert(r.reservedStores > 0 && r.reservedPublishes > 0,
          "postincrement-store benchmark never exercised reservation/publication")
        println(f"BOARD_COPY_IPC combined=$combined kernel=${kernel.name} seed=$seed " +
          f"retired=${r.retiredInstrs} cycles=${r.windowCycles} IPC=${r.ipc}%.6f " +
          s"branches=${r.pipelineProfile.get.retiredBranches} misses=${r.pipelineProfile.get.branchMisses} " +
          s"reserved=${r.reservedStores} published=${r.reservedPublishes} memory=$memLabel")
        (kernel.name, seed) -> r
      }).toMap
    }
    for (kernel <- kernels; seed <- seeds) {
      val before = results(0)((kernel.name, seed)); val after = results(1)((kernel.name, seed))
      assert(before.retiredInstrs == after.retiredInstrs)
      println(f"BOARD_COPY_GAIN kernel=${kernel.name} seed=$seed gain=${after.ipc / before.ipc - 1}%.6f")
    }
  }
}
