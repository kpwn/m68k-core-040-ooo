package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import m68k040.sim.AxiMemModel
import spinal.core.sim._

/** WRITE-SIDE BANDWIDTH (full-line coalescing + no-fill): bytes/cycle AND the mechanism.
  *
  * Runs the same kernels under whatever `CPU_SQ_COALESCE_LINES` / `CPU_DCACHE_FULLLINE_NOFILL`
  * the environment sets (the build is elaborated from them), so an A/B is two invocations
  * of this one spec. Every kernel is `copybackDtt = true` -- the default-precise-store
  * bench trap would make every store precise and hide the lever entirely.
  *
  * Prints per kernel/seed: window cycles, copy/stored B/cyc, total D-side AXI reads (dAR)
  * and writes (dAW), and -- the "did it fire" evidence -- the number of coalesced lines
  * and of no-fill allocations over the whole run, each also per 16-byte line touched.
  */
class WriteBandwidthSpec extends CoreBenchHarness {
  test("write-side bandwidth: memcpy / MOVE16 / memset, with mechanism counts", VerilatorTest) {
    val kernels = Seq(
      (kMemcpy(bytes = 16384, passes = 4, label = "memcpy-16k"), 16384, 4),
      (kMemcpy(bytes = 65536, passes = 2, label = "memcpy-64k"), 65536, 2),
      (kMemcpyMove16(16384, 4), 16384, 4),
      (kMemset(16384, 4), 16384, 4))
    val dut = M68kSim().withVerilator.compile(new FullCoreDut(
      alignedLoadFallThrough = true, earlyLsIntWakeup = true,
      earlyStoreAddress = true, trainSlot1Conditional = true,
      deferTakenSlot1Conditional = true, retainRedirectHistory = true))
    val coal = m68k040.top.ShippingCoreConfig.sqCoalesceLines
    val nofill = m68k040.top.ShippingCoreConfig.dcacheFullLineNoFill
    for ((k, bytes, passes) <- kernels; seed <- Seq(1, 17)) {
      var nCoal = 0L; var nNoFill = 0L; var nHold = 0L; var nTimeout = 0L
      dutProbe = d => fork {
        val cd = d.clockDomain
        while (true) {
          cd.waitSampling()
          if (coal) {
            if (d.lsEu.logic.sqCoalesceFire.toBoolean) nCoal += 1
            if (d.lsEu.logic.sqCoalesceHolding.toBoolean) nHold += 1
            if (d.lsEu.logic.sqCoalesceTimeout.toBoolean) nTimeout += 1
          }
          if (nofill && d.dcache.logic.noFillAlloc.toBoolean) nNoFill += 1
        }
      }
      var cap: AxiMemModel = null
      val orig = k.prepMem
      val r = runKernel(dut, k.copy(prepMem = h => { cap = h.dmem; orig(h) }), seed)
      dutProbe = null
      val lines   = (bytes / 16).toLong * (passes - 1)
      val allLines = (bytes / 16).toLong * passes
      val bpc = lines * 16.0 / r.windowCycles
      val ar = if (cap != null) cap.stats.totalAr else -1L
      val aw = if (cap != null) cap.stats.totalAw else -1L
      println(f"WRITE_BW kernel=${k.name}%-14s seed=$seed coalesce=$coal nofill=$nofill " +
        f"cycles=${r.windowCycles}%d cyc/line=${r.windowCycles.toDouble / lines}%.3f B/cyc=$bpc%.4f " +
        f"dAR=$ar%d dAW=$aw%d coalesced=$nCoal%d (${nCoal.toDouble / allLines}%.3f/line) " +
        f"noFill=$nNoFill%d (${nNoFill.toDouble / allLines}%.3f/line) hold=$nHold%d timeouts=$nTimeout%d " +
        f"IPC=${r.ipc}%.4f")
    }
  }
}
