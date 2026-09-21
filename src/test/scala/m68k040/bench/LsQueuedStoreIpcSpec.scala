package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** Isolate known-address bypass capacity; the divider itself stays unchanged. */
class LsQueuedStoreIpcSpec extends CoreBenchHarness {
  private val guard = " ; .LqueuedDone: bra.s .LqueuedDone ; .rept 64 ; nop ; .endr"
  private def recurrence(stores: Int, alias: Boolean, loadProducer: Boolean = false): Kernel = {
    val setup = if(loadProducer)
      Seq("move.l #0x4610,0x4610", "move.l #0x4610,0x4800", "lea 0x4610,%a0")
    else Seq("moveq #1,%d1", "move.l #0x7fff,0x4610", "move.l 0x4610,%d0")
    val next = if(loadProducer) s"movea.l 0x${if(alias) "4700" else "4800"},%a0"
      else s"move.l 0x${if(alias) "4700" else "4610"},%d0"
    val body = Seq(if(loadProducer) "move.l (%a0),%d0" else "divu.w %d1,%d0") ++
      (0 until stores).map(n => s"move.l %d0,0x${(0x4700 + n * 4).toHexString}") ++
      Seq(next)
    Kernel(s"queued-${if(loadProducer) "load-" else ""}$stores-${if(alias) "alias" else "disjoint"}",
      (setup ++ Seq.fill(48)(body).flatten).mkString(" ; ") + guard,
      setup.size + 48 * body.size, copybackDtt = true,
      warmupInstrs = setup.size + 8 * body.size,
      verifyRetirement = obs => {
        val got = obs.filter(o => o.archRegValid && o.archRegId == 0).last.archRegWrite & 0xffffffffL
        assert(got == (if(loadProducer) 0x4610L else 0x7fffL))
        if(loadProducer) {
          val a0 = obs.filter(o => o.archRegValid && o.archRegId == 8).last.archRegWrite & 0xffffffffL
          assert(a0 == 0x4610L)
        }
      })
  }

  test("queued late stores compare matched full-core bypass capacity", VerilatorTest) {
    val kernels = (for(stores <- Seq(1, 2, 4, 8); alias <- Seq(false, true))
      yield recurrence(stores, alias)) ++
      (for(stores <- Seq(1, 2, 4); alias <- Seq(false, true))
        yield recurrence(stores, alias, loadProducer = true))
    val results = Seq(1, 2, 4).map { entries =>
      val compiled = M68kSim().withVerilator.compile(new FullCoreDut(
        alignedLoadFallThrough = true, earlyLsIntWakeup = true,
        earlyStoreAddress = true, fuseLongMoveLoads = true,
        reserveLateStore = true, detachLateStore = true, forwardOnPublish = true,
        trainSlot1Conditional = true, deferTakenSlot1Conditional = true,
        retainRedirectHistory = true, earlyLsNzvcWakeup = true,
        detachedStoreEntries = entries))
      entries -> (for(k <- kernels; seed <- Seq(1, 17)) yield {
        val r = runKernel(compiled, k, seed)
        println(f"LS_QUEUED_IPC entries=$entries kernel=${k.name} seed=$seed retired=${r.retiredInstrs} " +
          f"cycles=${r.windowCycles} IPC=${r.ipc}%.6f queued=${r.queuedStoreAdmissions} " +
          s"overtakes=${r.detachedLoadOvertakes} ownerWaits=${r.pendingStoreOwnerWaits}")
        (k.name, seed) -> r
      }).toMap
    }.toMap
    for(entries <- Seq(2, 4); k <- kernels; seed <- Seq(1, 17)) {
      val a = results(1)((k.name, seed)); val b = results(entries)((k.name, seed))
      assert(a.retiredInstrs == b.retiredInstrs)
      if(!k.name.endsWith("alias") && !k.name.startsWith("queued-8-") && !k.name.startsWith("queued-load-"))
        assert(b.queuedStoreAdmissions > 0, "additional context capacity never exercised")
      println(f"LS_QUEUED_GAIN entries=$entries kernel=${k.name} seed=$seed gain=${b.ipc / a.ipc - 1}%.6f memory=$memLabel")
    }
  }
}
