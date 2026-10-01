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

  test("D-side bandwidth: copy, grouped copy, MOVE16 copy, load stream, chase", VerilatorTest) {
    val all = Seq(
      "memcpy-16k"     -> (() => kMemcpy(bytes = 16384, passes = 4, label = "memcpy-16k")),
      "memcpy-64k"     -> (() => kMemcpy(bytes = 65536, passes = 2, label = "memcpy-64k")),
      "memcpy-grp-16k" -> (() => kMemcpyGrouped(bytes = 16384, passes = 4)),
      "memcpy-m16-16k" -> (() => kMemcpyMove16(bytes = 16384, passes = 4)),
      "stream-64k"     -> (() => kStream(bytes = 65536, passes = 2)),
      "stream-16k"     -> (() => kStream(bytes = 16384, passes = 2)),
      "chase-four"     -> (() => kChaseFour(records = 256, iters = 768)),
      "chase-128"      -> (() => kChasePure(records = 128, iters = 512)))
    val want = sys.env.get("DSIDE_KERNELS").map(_.split(",").map(_.trim).toSet)
    val kernels = all.filter { case (n, _) => want.forall(_.contains(n)) }.map(_._2())
    val seeds = sys.env.get("DSIDE_SEEDS").map(_.split(",").toSeq.map(_.trim.toInt)).getOrElse(Seq(1, 17))
    val lsOoo = sys.env.get("IQ_LOAD_BYPASS").contains("1")

    val dut = M68kSim().withVerilator.compile(new FullCoreDut(
      alignedLoadFallThrough = true, earlyLsIntWakeup = true,
      earlyStoreAddress = true, trainSlot1Conditional = true,
      deferTakenSlot1Conditional = true, retainRedirectHistory = true,
      loadBypassUnreadyLoad = lsOoo))

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
      var hotOutstandingMax = 0
      val hotOutstanding = scala.collection.mutable.Set.empty[Int]
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
          val arFire = if (d.dcache.hotDoor)
            d.dcache.logic.axiDh.ar.valid.toBoolean && d.dcache.logic.axiDh.ar.ready.toBoolean
          else
            d.dcache.logic.axi.ar.valid.toBoolean && d.dcache.logic.axi.ar.ready.toBoolean
          if (arFire) {
            if (d.dcache.hotDoor) {
              val id = d.dcache.logic.axiDh.ar.payload.id.toInt
              assert(!hotOutstanding.contains(id), s"hot AXI ID $id was reused before R")
              hotOutstanding += id
              hotOutstandingMax = scala.math.max(hotOutstandingMax, hotOutstanding.size)
            }
            if (d.dcache.logic.nb.wbIssued.toBoolean) arDuringWb += 1
            if (lastAr >= 0) arIntervals += cycle - lastAr
            lastAr = cycle
          }
          if (d.dcache.hotDoor && d.dcache.logic.axiDh.r.valid.toBoolean &&
              d.dcache.logic.axiDh.r.ready.toBoolean) {
            val id = d.dcache.logic.axiDh.r.payload.id.toInt
            assert(hotOutstanding.remove(id), s"hot AXI R ID $id had no outstanding AR")
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
      val moved = if (k.name == "chase-four") 4L * (768 - 256) * 4
                  else if (k.name == "chase-pure") 4L * (512 - 128)
                  else bytes * (passes - 1)
      val bpc = moved.toDouble / r.windowCycles
      println(f"DSIDE_BW kernel=${k.name} seed=$seed bytes=$moved cycles=${r.windowCycles} " +
              f"B/cyc=$bpc%.4f retired=${r.retiredInstrs} IPC=${r.ipc}%.4f memory=$memLabel " +
              s"nonBlocking=${m68k040.top.ShippingCoreConfig.dcacheNonBlocking} " +
              s"hotDoor=${m68k040.top.ShippingCoreConfig.dcacheHotDoor} lsOoo=$lsOoo " +
              f"CPI=${r.windowCycles.toDouble / r.retiredInstrs}%.3f")
      if (m68k040.top.ShippingCoreConfig.dcacheNonBlocking)
        println(f"DSIDE_CONCURRENCY kernel=${k.name} seed=$seed " +
          f"ringAvg=${ringOccSum.toDouble / scala.math.max(1L, ringSamples)}%.3f " +
          s"ringMax=$ringMax hotOutstandingMax=$hotOutstandingMax")
      if (m68k040.top.ShippingCoreConfig.dcacheNonBlocking && arIntervals.nonEmpty) {
        val sorted = arIntervals.sorted
        val median = sorted(sorted.size / 2)
        val p95 = sorted(scala.math.min(sorted.size - 1, (sorted.size * 95) / 100))
        println(s"DSIDE_WB_OVERLAP kernel=${k.name} seed=$seed arDuringWb=$arDuringWb " +
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
