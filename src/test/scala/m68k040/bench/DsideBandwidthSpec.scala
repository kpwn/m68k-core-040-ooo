package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._

/** D-side bandwidth program (design note
  * `docs/superpowers/specs/2026-09-30-dside-nonblocking-l1d-and-hot-door.md` section 10):
  * bytes per cycle on the shapes the claims C1-C4 are about, with the non-blocking L1D's
  * `[mshr]` counters printed by the harness whenever it is built.
  *
  * The machine is selected ENTIRELY by env, so one spec serves every arm:
  *   CPU_DCACHE_NONBLOCKING=0|1, CPU_AXI_DH=0|1 (+ CPU_INHIBITED_FULL_BARRIER=1),
  *   IQ_LOAD_BYPASS=1 (LS out-of-order issue, the LS-OoO arm), IPC_MEM=l2:5:60:4096 ...
  *   DSIDE_KERNELS=memcpy-16k,memcpy-grp-16k,... (default: all), DSIDE_SEEDS=1,17.
  *
  * `chase-128` is the NEGATIVE CONTROL (C3): dependent loads, MLP 1 by construction. */
class DsideBandwidthSpec extends CoreBenchHarness {
  private final case class ChainCycle(cycle: Long, hotOutstanding: Int, ringOcc: Int,
      readyLoads: Int, lsSelectLoad: Boolean, lsEuFire: Boolean, cmdFire: Boolean,
      arFire: Boolean, rFire: Boolean, ringFullNoPop: Boolean,
      ringHeadRspHol: Boolean, waitAr: Int, arQueued: Int, waitR: Int,
      filled: Int, linger: Int, waiters: Int, ringDone: Int,
      ringDoneWb: Int, ringDoneNeedsWb: Int, earlyWbAny: Boolean,
      earlyWbFire: Boolean, headPopAlreadyWb: Boolean, frontCompHeld: Boolean,
      failSet: Long, failFull: Long)

  test("D-side bandwidth: copy, grouped copy, MOVE16 copy, load stream, chase", VerilatorTest) {
    val chainCounts = sys.env.get("DSIDE_CHAIN_COUNTS").map(_.split(",").map(_.trim.toInt).toSeq)
      .getOrElse(Seq.empty[Int])
    val chainRecords = sys.env.get("DSIDE_CHAIN_RECORDS").map(_.toInt).getOrElse(1024)
    val chainSkew = sys.env.get("DSIDE_CHAIN_SET_SKEW").contains("1")
    val chainKernels = chainCounts.map { n =>
      s"chase-chains-$n-$chainRecords-skew${if (chainSkew) 1 else 0}" ->
        (() => kChaseChains(n, chainRecords, chainSkew))
    }
    val all = Seq(
      "memcpy-16k"     -> (() => kMemcpy(bytes = 16384, passes = 4, label = "memcpy-16k")),
      "memcpy-64k"     -> (() => kMemcpy(bytes = 65536, passes = 2, label = "memcpy-64k")),
      "memcpy-grp-16k" -> (() => kMemcpyGrouped(bytes = 16384, passes = 4)),
      "memcpy-m16-16k" -> (() => kMemcpyMove16(bytes = 16384, passes = 4)),
      "stream-64k"     -> (() => kStream(bytes = 65536, passes = 2)),
      "stream-16k"     -> (() => kStream(bytes = 16384, passes = 2)),
      "chase-four"     -> (() => kChaseFour(records = 256, iters = 768)),
      "chase-128"      -> (() => kChasePure(records = 128, iters = 512))) ++ chainKernels
    val want = sys.env.get("DSIDE_KERNELS").map(_.split(",").map(_.trim).toSet)
    val kernels = all.filter { case (n, _) => want.forall(_.contains(n)) }.map(_._2())
    val seeds = sys.env.get("DSIDE_SEEDS").map(_.split(",").toSeq.map(_.trim.toInt)).getOrElse(Seq(1, 17))
    val lsOoo = sys.env.get("IQ_LOAD_BYPASS").contains("1")
    val specWake = sys.env.get("DSIDE_SPEC_WAKE").contains("1")
    val fuseLongMoveLoads = sys.env.get("DSIDE_FUSE_LONG_MOVE_LOADS").contains("1")

    val dut = M68kSim().withVerilator.compile(new FullCoreDut(
      alignedLoadFallThrough = true, earlyLsIntWakeup = true,
      earlyStoreAddress = true, trainSlot1Conditional = true,
      deferTakenSlot1Conditional = true, retainRedirectHistory = true,
      loadBypassUnreadyLoad = lsOoo, specLoadWakeup = specWake,
      fuseLongMoveLoads = fuseLongMoveLoads))

    for (k <- kernels; seed <- seeds) {
      // Observe one continuous kernel run. A refill AR while wbIssued is high is
      // necessarily before the previous dirty victim's B (the WB head pops on B).
      // The cache's AR-time same-line assertion proves that this overlapping AR is
      // for a different line. This distinguishes asynchronous eviction from merely
      // buffering an eviction and waiting for B before every new refill.
      var cycle = 0L
      var lastAr = -1L
      var lastWbB = -1L
      var arDuringWb = 0L
      var ringSamples = 0L
      var ringOccSum = 0L
      var ringMax = 0
      var ringFullNoPop = 0L
      var ringFullHeadRspHol = 0L
      var hotOutstandingMax = 0
      val hotOutstanding = scala.collection.mutable.Map.empty[Int, Long]
      val hotLatencies = scala.collection.mutable.ArrayBuffer.empty[(Long, Long)]
      val chainSamples = scala.collection.mutable.ArrayBuffer.empty[ChainCycle]
      var firstLdCmdProbeCycle = -1L
      val chainKernel = k.name.startsWith("chase-chains-")
      val arIntervals = scala.collection.mutable.ArrayBuffer.empty[Long]
      val wbBIntervals = scala.collection.mutable.ArrayBuffer.empty[Long]
      var wbBCount = 0L
      dutProbe = d => if (d.dcache.nonBlocking) fork {
        while (true) {
          d.clockDomain.waitSampling()
          cycle += 1
          val ringOcc = d.lsEu.logic.alignedCount.toInt
          ringSamples += 1
          ringOccSum += ringOcc
          ringMax = scala.math.max(ringMax, ringOcc)
          val ls = d.lsEu.logic
          val fullNoPop = ls.alignedFull.toBoolean && !ls.alignedRspFire.toBoolean
          var headRspHol = false
          if (fullNoPop) {
            ringFullNoPop += 1
            val head = ls.alignedRspPtr.toInt
            val headAwaiting = ls.alignedValid(head).toBoolean &&
              ls.alignedSent(head).toBoolean && !ls.alignedDone(head).toBoolean &&
              !ls.bkBusy.toBoolean
            val youngerParked = (0 until ls.alignedDone.length).exists(i =>
              i != head && ls.alignedDone(i).toBoolean)
            headRspHol = headAwaiting && youngerParked
            if (headRspHol) ringFullHeadRspHol += 1
          }
          val arFire = if (d.dcache.hotDoor)
            d.dcache.logic.axiDh.ar.valid.toBoolean && d.dcache.logic.axiDh.ar.ready.toBoolean
          else
            d.dcache.logic.axi.ar.valid.toBoolean && d.dcache.logic.axi.ar.ready.toBoolean
          if (arFire) {
            if (d.dcache.hotDoor) {
              val id = d.dcache.logic.axiDh.ar.payload.id.toInt
              assert(!hotOutstanding.contains(id), s"hot AXI ID $id was reused before R")
              hotOutstanding(id) = cycle
              hotOutstandingMax = scala.math.max(hotOutstandingMax, hotOutstanding.size)
            }
            if (d.dcache.logic.nb.wbIssued.toBoolean) arDuringWb += 1
            if (lastAr >= 0) arIntervals += cycle - lastAr
            lastAr = cycle
          }
          val rFire = d.dcache.hotDoor && d.dcache.logic.axiDh.r.valid.toBoolean &&
            d.dcache.logic.axiDh.r.ready.toBoolean
          if (rFire) {
            val id = d.dcache.logic.axiDh.r.payload.id.toInt
            val accepted = hotOutstanding.remove(id)
            assert(accepted.nonEmpty, s"hot AXI R ID $id had no outstanding AR")
            hotLatencies += ((accepted.get, cycle))
          }
          if (chainKernel) {
            val cmdFire = d.dcache.logic.loadCmdPort.valid.toBoolean &&
              d.dcache.logic.loadCmdPort.ready.toBoolean
            if (cmdFire && firstLdCmdProbeCycle < 0) firstLdCmdProbeCycle = cycle
            val readyLoads = d.iq.logic.slots.count(s => s.sel.toBoolean &&
              s.ready.toBoolean && s.hot.memOp.toEnum == m68k040.isa.MemOp.LOAD)
            val ctr = d.dcache.logic.nb.ctrMap
            val nb = d.dcache.logic.nb
            def stateCount(st: Int) = nb.st.count(_.toInt == st)
            val doneWb = (0 until ls.alignedDone.length).count(i =>
              ls.alignedValid(i).toBoolean && ls.alignedDone(i).toBoolean &&
                ls.alignedWb(i).toBoolean)
            val doneNeedsWb = (0 until ls.alignedDone.length).count(i =>
              ls.alignedValid(i).toBoolean && ls.alignedDone(i).toBoolean &&
                !ls.alignedWb(i).toBoolean)
            val headPopAlreadyWb = ls.alignedRspFire.toBoolean &&
              ls.alignedWb(ls.alignedRspPtr.toInt).toBoolean
            chainSamples += ChainCycle(cycle, hotOutstanding.size, ringOcc,
              readyLoads, d.iq.logic.lsSelectLoadFire.toBoolean,
              d.lsEu.issuePort.valid.toBoolean && d.lsEu.issuePort.ready.toBoolean,
              cmdFire, arFire, rFire, fullNoPop, headRspHol,
              stateCount(nb.WAIT_AR), stateCount(nb.ARQ), stateCount(nb.WAIT_R),
              stateCount(nb.FILLED), stateCount(nb.LINGER),
              nb.wv.count(_.toBoolean), ls.alignedDone.count(_.toBoolean),
              doneWb, doneNeedsWb, ls.alignedEarlyWbAny.toBoolean,
              ls.alignedEarlyWbFire.toBoolean, headPopAlreadyWb,
              ls.frontCompHeld.toBoolean,
              ctr("failSet").toLong, ctr("failFull").toLong)
          }
          if (d.dcache.logic.axi.b.valid.toBoolean &&
              d.dcache.logic.axi.b.ready.toBoolean &&
              d.dcache.logic.axi.b.payload.id.toInt == m68k040.cache.AxiIds.D_PUSH) {
            wbBCount += 1
            if (lastWbB >= 0) wbBIntervals += cycle - lastWbB
            lastWbB = cycle
          }
        }
      }
      val r = runKernel(dut, k, seed)
      dutProbe = null
      // Bytes touched in the MEASURED window (pass 1 excluded). Copy kernels move each
      // byte once (read + write); the stream reads it; chase reads one long per record hop.
      val (bytes, passes) =
        if (k.name == "stream-64k") (65536L, 2)
        else if (k.name == "stream-16k") (16384L, 2)
        else if (k.name.contains("64k")) (65536L, 2)
        else (16384L, 4)
      val moved = if (k.name.startsWith("chase-chains-")) (chainRecords.toLong * 2L + 8L) * 4L
                  else if (k.name == "chase-four") 4L * (768 - 256) * 4
                  else if (k.name == "chase-pure") 4L * (512 - 128)
                  else bytes * (passes - 1)
      val bpc = moved.toDouble / r.windowCycles
      if (k.name.startsWith("chase-chains-")) {
        val n = k.name.stripPrefix("chase-chains-").takeWhile(_ != '-').toInt
        println(s"DSIDE_CHAIN_CONFIG kernel=${k.name} chains=$n totalRecords=$chainRecords " +
          s"perChain=${chainRecords / n} setSkew=$chainSkew measuredPointerLoads=${chainRecords * 2 + 8} " +
          s"measuredLoopOps=${((chainRecords / n) * 2 + 8 / n) * 3} " +
          "EA=indexed(A0,Dn.L) for all loads; first traversal excluded")
        assert(r.ldCmdCycles.nonEmpty && firstLdCmdProbeCycle >= 0,
          s"${k.name}: missing common load-command clock reference")
        val clockOffset = firstLdCmdProbeCycle - r.ldCmdCycles.head
        // Harness window bounds index its zero-based histograms; both the
        // telemetry callback and this probe stamp the same first edge as 1.
        val winLo = r.windowStartCycle + 1 + clockOffset
        val winHi = r.windowEndCycle + 1 + clockOffset
        val win = chainSamples.filter(s => s.cycle >= winLo && s.cycle <= winHi)
        assert(win.size == r.windowCycles,
          s"${k.name}: probe samples=${win.size} != commit window=${r.windowCycles}")
        val acceptedWithin = hotLatencies.filter { case (ar, rsp) =>
          ar >= winLo && rsp <= winHi
        }.map { case (ar, rsp) => rsp - ar }.sorted
        val issueCycles = win.filter(_.lsSelectLoad).map(_.cycle)
        val issueGaps = issueCycles.zip(issueCycles.drop(1)).map { case (a, b) => b - a }.sorted
        val hotArea = win.map(_.hotOutstanding.toLong).sum
        val hotActive = win.count(_.hotOutstanding > 0)
        val hotHist = (0 to m68k040.top.ShippingCoreConfig.dcacheMshrs)
          .map(n => win.count(_.hotOutstanding == n))
        val before = chainSamples.find(_.cycle == winLo - 1)
        def delta(last: Long, previous: ChainCycle => Long): Long =
          last - before.map(previous).getOrElse(0L)
        val failSetWindow = delta(win.last.failSet, _.failSet)
        val failFullWindow = delta(win.last.failFull, _.failFull)
        val failFullSamples = chainSamples.zip(chainSamples.drop(1)).collect {
          case (previous, current) if current.cycle >= winLo && current.cycle <= winHi &&
              current.failFull > previous.failFull => current
        }
        val medIssueGap = if (issueGaps.isEmpty) 0L else issueGaps(issueGaps.size / 2)
        val medArToR = if (acceptedWithin.isEmpty) 0L else acceptedWithin(acceptedWithin.size / 2)
        val p95ArToR = if (acceptedWithin.isEmpty) 0L else
          acceptedWithin(scala.math.min(acceptedWithin.size - 1, acceptedWithin.size * 95 / 100))
        val arToRMean = acceptedWithin.sum.toDouble / scala.math.max(1, acceptedWithin.size)
        println(f"DSIDE_CHAIN_WINDOW kernel=${k.name} seed=$seed cycles=${r.windowCycles} " +
          s"loadSelect=${issueCycles.size} lsEuFire=${win.count(_.lsEuFire)} " +
          s"loadCmd=${win.count(_.cmdFire)} hotAR=${win.count(_.arFire)} " +
          s"hotR=${win.count(_.rFire)} completeArToR=${acceptedWithin.size} " +
          s"arToRMinMedMax=${if (acceptedWithin.isEmpty) "-" else s"${acceptedWithin.head}/$medArToR/${acceptedWithin.last}"} " +
          f"arToRMean=$arToRMean%.3f arToRP95=$p95ArToR " +
          s"issueGapMinMedMax=${if (issueGaps.isEmpty) "-" else s"${issueGaps.head}/$medIssueGap/${issueGaps.last}"} " +
          f"readyLoadsAvg=${win.map(_.readyLoads).sum.toDouble / win.size}%.3f " +
          f"ringAvg=${win.map(_.ringOcc).sum.toDouble / win.size}%.3f " +
          s"ringMax=${win.map(_.ringOcc).max} ringFullNoPop=${win.count(_.ringFullNoPop)} " +
          s"ringHeadRspHol=${win.count(_.ringHeadRspHol)} failSet=$failSetWindow failFull=$failFullWindow " +
          f"mshrAvgWaitAR=${win.map(_.waitAr).sum.toDouble / win.size}%.3f " +
          f"mshrAvgARQ=${win.map(_.arQueued).sum.toDouble / win.size}%.3f " +
          f"mshrAvgWaitR=${win.map(_.waitR).sum.toDouble / win.size}%.3f " +
          f"mshrAvgFilled=${win.map(_.filled).sum.toDouble / win.size}%.3f " +
          f"mshrAvgLinger=${win.map(_.linger).sum.toDouble / win.size}%.3f " +
          f"waiterAvg=${win.map(_.waiters).sum.toDouble / win.size}%.3f " +
          f"ringDoneAvg=${win.map(_.ringDone).sum.toDouble / win.size}%.3f " +
          s"hotOccHist=${hotHist.mkString("/")} " +
          f"hotMeanAllCycles=${hotArea.toDouble / win.size}%.3f " +
          f"hotMeanActiveCycles=${hotArea.toDouble / scala.math.max(1, hotActive)}%.3f " +
          s"hotActiveCycles=$hotActive scope=commit-window")
        // A counter increment is an admission failure, whereas full-ring cycles
        // merely describe occupied state. Sample both at the same clock edge.
        assert(failFullSamples.size == failFullWindow,
          s"${k.name}: failFull counter increment/sample mismatch")
        println(f"DSIDE_CHAIN_FAIL_FULL kernel=${k.name} seed=$seed events=$failFullWindow " +
          s"ringFull=${failFullSamples.count(_.ringOcc == m68k040.top.ShippingCoreConfig.lsLoadRingDepth)} " +
          s"ringFullNoPop=${failFullSamples.count(_.ringFullNoPop)} " +
          s"ringHeadRspHol=${failFullSamples.count(_.ringHeadRspHol)} " +
          f"readyLoadsMean=${failFullSamples.map(_.readyLoads).sum.toDouble / scala.math.max(1, failFullSamples.size)}%.3f " +
          s"anyFilledOrLinger=${failFullSamples.count(s => s.filled + s.linger > 0)} " +
          s"allWaitR=${failFullSamples.count(s => s.waitR > 0 &&
            s.waitAr + s.arQueued + s.filled + s.linger == 0)} " +
          f"waitRMean=${failFullSamples.map(_.waitR).sum.toDouble / scala.math.max(1, failFullSamples.size)}%.3f " +
          f"filledMean=${failFullSamples.map(_.filled).sum.toDouble / scala.math.max(1, failFullSamples.size)}%.3f " +
          f"lingerMean=${failFullSamples.map(_.linger).sum.toDouble / scala.math.max(1, failFullSamples.size)}%.3f " +
          f"ringDoneWbMean=${failFullSamples.map(_.ringDoneWb).sum.toDouble / scala.math.max(1, failFullSamples.size)}%.3f " +
          f"ringDoneNeedsWbMean=${failFullSamples.map(_.ringDoneNeedsWb).sum.toDouble / scala.math.max(1, failFullSamples.size)}%.3f " +
          "scope=commit-window")
        println(s"DSIDE_CHAIN_WB_ARB kernel=${k.name} seed=$seed " +
          s"doneWbCycles=${win.count(_.ringDoneWb > 0)} " +
          s"doneNeedsWbCycles=${win.count(_.ringDoneNeedsWb > 0)} " +
          s"earlyEligible=${win.count(_.earlyWbAny)} earlyFire=${win.count(_.earlyWbFire)} " +
          s"earlyBlocked=${win.count(s => s.earlyWbAny && !s.earlyWbFire)} " +
          s"headPopAlreadyWb=${win.count(_.headPopAlreadyWb)} " +
          s"blockedByAlreadyWbHead=${win.count(s => s.earlyWbAny &&
            !s.earlyWbFire && s.headPopAlreadyWb)} " +
          s"frontCompHeldByAlreadyWbHead=${win.count(s => s.frontCompHeld && s.headPopAlreadyWb)} " +
          "scope=commit-window")
      }
      println(f"DSIDE_BW scope=commit-window kernel=${k.name} seed=$seed bytes=$moved cycles=${r.windowCycles} " +
              f"B/cyc=$bpc%.4f retired=${r.retiredInstrs} IPC=${r.ipc}%.4f memory=$memLabel " +
              s"nonBlocking=${m68k040.top.ShippingCoreConfig.dcacheNonBlocking} " +
              s"hotDoor=${m68k040.top.ShippingCoreConfig.dcacheHotDoor} lsOoo=$lsOoo " +
              s"specWake=$specWake fuseLongMoveLoads=$fuseLongMoveLoads " +
              s"nbEarlyResponse=${m68k040.top.ShippingCoreConfig.dcacheNbEarlyResponse} " +
              s"nbEagerAr=${m68k040.top.ShippingCoreConfig.dcacheNbEagerAr} " +
              s"nbPreselectAr=${m68k040.top.ShippingCoreConfig.dcacheNbPreselectAr} " +
              s"nbDynamicRelease=${m68k040.top.ShippingCoreConfig.dcacheNbDynamicRelease} " +
              f"CPI=${r.windowCycles.toDouble / r.retiredInstrs}%.3f")
      if (m68k040.top.ShippingCoreConfig.dcacheNonBlocking)
        println(f"DSIDE_CONCURRENCY scope=whole-run kernel=${k.name} seed=$seed " +
          f"ringAvg=${ringOccSum.toDouble / scala.math.max(1L, ringSamples)}%.3f " +
          s"ringMax=$ringMax ringFullNoPopStateCycles=$ringFullNoPop " +
          s"ringFullHeadRspHolStateCycles=$ringFullHeadRspHol hotOutstandingMax=$hotOutstandingMax")
      if (m68k040.top.ShippingCoreConfig.dcacheNonBlocking && arIntervals.nonEmpty) {
        val sorted = arIntervals.sorted
        val median = sorted(sorted.size / 2)
        val p95 = sorted(scala.math.min(sorted.size - 1, (sorted.size * 95) / 100))
        println(s"DSIDE_WB_OVERLAP scope=whole-run kernel=${k.name} seed=$seed arDuringWb=$arDuringWb " +
          s"arCount=${arIntervals.size + 1} arIntervalMedian=$median arIntervalP95=$p95 " +
          s"wbBCount=$wbBCount wbBIntervalMedian=${if (wbBIntervals.nonEmpty) wbBIntervals.sorted.apply(wbBIntervals.size / 2) else 0L} " +
          "(one continuous kernel; failWb/wbFull/wbGate are in [mshr])")
      }
      if (sys.env.get("DSIDE_REQUIRE_WB_OVERLAP").contains("1") &&
          m68k040.top.ShippingCoreConfig.dcacheNonBlocking &&
          m68k040.top.ShippingCoreConfig.dcacheHotDoor && k.name.startsWith("memcpy"))
        assert(arDuringWb > 0,
          s"${k.name}: no new refill AR issued before a previous dirty victim's B")
    }
  }
}
