package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** BANDWIDTH, reported as BYTES PER CYCLE -- the metric this corpus never had.
  *
  * The goal of this core is high parallelism and high bandwidth. Every existing
  * bench reports IPC, which for a copy loop is nearly constant by construction:
  * the instruction count is fixed by the copy size, so IPC and cycles carry the
  * same information and neither says how many bytes moved per cycle. A lever that
  * doubles memory-level parallelism shows up directly in B/cyc and only indirectly
  * in IPC.
  *
  * Why B/cyc is the honest headline here (Little's Law):
  *
  *     bandwidth = bytes_per_miss x MLP / miss_latency
  *
  * D-side MLP is currently measured at exactly 1.000 mean and 1 max, at every
  * injected miss rate up to 100%, because the D-cache has ONE refill MSHR and the
  * SoC crossbar is single-outstanding per master port. With a 16-byte L1 line and
  * ~10 cycles per miss that caps streaming at ~1.6 B/cyc regardless of the code.
  * This spec exists to make that ceiling visible and to measure anything that
  * lifts it.
  *
  * `memcpy-16k` is sized deliberately: 16 KB is 2x the 8 KB L1 (so every pass
  * re-misses L1) and far inside the 2 MB L2 (so passes after the first are L2
  * hits, not DRAM). Pass 1 is charged to warm-up. `memcpy-64k` widens the working
  * set 8x past L1 to check the result is a streaming property and not an artifact
  * of a set that half-fits.
  */
class MemcpyBandwidthSpec extends CoreBenchHarness {

  test("memcpy streaming bandwidth in bytes per cycle", VerilatorTest) {
    val kernels = Seq(
      kMemcpy(bytes = 16384, passes = 4, label = "memcpy-16k"),
      kMemcpy(bytes = 65536, passes = 2, label = "memcpy-64k"))

    val dut = M68kSim().withVerilator.compile(new FullCoreDut(
      alignedLoadFallThrough = true, earlyLsIntWakeup = true,
      earlyStoreAddress = true, trainSlot1Conditional = true,
      deferTakenSlot1Conditional = true, retainRedirectHistory = true))

    for (k <- kernels; seed <- Seq(1, 17)) {
      val r = runKernel(dut, k, seed)
      // Bytes moved inside the MEASURED window only. Pass 1 is warm-up, so the
      // measured passes are (passes - 1). Derived from the kernel's own name so a
      // mis-sized kernel cannot silently be credited the wrong byte count.
      val bytes   = if (k.name.contains("64k")) 65536L else 16384L
      val passes  = if (k.name.contains("64k")) 2 else 4
      val moved   = bytes * (passes - 1)
      // A copy READS and WRITES every byte, so traffic is 2x the copy size. Both
      // are printed: `copyBpc` is what a user cares about, `trafficBpc` is what
      // the memory system actually sustained.
      val copyBpc    = moved.toDouble / r.windowCycles
      val trafficBpc = 2.0 * moved / r.windowCycles
      println(f"MEMCPY_BW kernel=${k.name} seed=$seed bytes=$moved cycles=${r.windowCycles} " +
              f"copyB/cyc=$copyBpc%.4f trafficB/cyc=$trafficBpc%.4f " +
              f"retired=${r.retiredInstrs} IPC=${r.ipc}%.4f memory=$memLabel")
    }
  }
}
