package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import m68k040.sim.AxiMemModel

/** ORACLE for the "make MOVE16 a single wide access" lever, measured BEFORE any RTL.
  *
  * The lever's claimed mechanism is LS-PIPE / D-cache ACCESS OCCUPANCY: a 16-byte
  * copy costs 4 loads + 4 stores today and should cost 1 + 1. This spec measures the
  * marginal cost of a D-cache access on exactly this loop, at CONSTANT memory-system
  * behaviour, by copying 1, 2 or 4 of the four longs in every 16-byte line
  * (`kMemcpyQuads`). The line footprint, the L1 misses, the destination
  * write-allocate fetches and the dirty writebacks are identical across the three --
  * only the access count changes (2, 4, 8 per line).
  *
  *   marginal cycles per access = (cycles(q4) - cycles(q1)) / 6
  *   predicted cycles for a 2-access MOVE16 = cycles(q1) + 0 * marginal
  *
  * i.e. `q1` IS the upper bound for any implementation that gets the line moved in
  * two accesses, and the upper bound is measurable with no RTL at all.
  *
  * `memcpy-m16-16k` is the MOVE16 loop as microcoded TODAY: same eight accesses,
  * three instructions instead of eight. It separates the instruction-count half of
  * the story from the access-count half, and it is the fail-before control for the
  * RTL change.
  *
  * ⚠️ PRE-RECORDED PREDICTION AND FALSIFIER (2026-09-28, before the first run):
  *   PREDICTION: q1 is 5-20% faster than q4 (point estimate +12%), because the 48.8
  *   cycles per line are dominated by serialised miss service through ONE refill
  *   MSHR, not by pipe occupancy -- so removing 6 of 8 accesses removes roughly 6
  *   cycles of ~48. m16 lands within +/-3% of q4 (front-end is not the constraint).
  *   FALSIFIER: if q1 is >=35% faster than q4, the occupancy slice is much larger
  *   than the miss-latency model allows and the model is WRONG. If q1 is <3% faster,
  *   the occupancy mechanism is DEAD and a wide MOVE16 cannot pay on its own.
  */
class Move16OracleSpec extends CoreBenchHarness {

  test("MOVE16 oracle: cycles per 16-byte line vs D-cache accesses per line", VerilatorTest) {
    val bytes  = 16384
    val passes = 4
    val kernels = Seq(
      (kMemcpy(bytes = bytes, passes = passes, label = "memcpy-16k"), 8, 4),
      (kMemcpyQuads(4, bytes, passes), 8, 4),
      (kMemcpyQuads(2, bytes, passes), 4, 2),
      (kMemcpyQuads(1, bytes, passes), 2, 1),
      (kMemcpyMove16(bytes, passes), 8, 4))

    val dut = M68kSim().withVerilator.compile(new FullCoreDut(
      alignedLoadFallThrough = true, earlyLsIntWakeup = true,
      earlyStoreAddress = true, trainSlot1Conditional = true,
      deferTakenSlot1Conditional = true, retainRedirectHistory = true))

    for ((k, accesses, quads) <- kernels; seed <- Seq(1, 17)) {
      var cap: AxiMemModel = null
      val orig = k.prepMem
      val kk = k.copy(prepMem = h => { cap = h.dmem; orig(h) })
      val r = runKernel(dut, kk, seed)
      val lines      = (bytes / 16) * (passes - 1)          // measured passes only
      val copyBytes  = lines.toLong * 4 * quads
      val cycPerLine = r.windowCycles.toDouble / lines
      val copyBpc    = copyBytes.toDouble / r.windowCycles
      val ar = if (cap != null) cap.stats.totalAr else -1L
      val aw = if (cap != null) cap.stats.totalAw else -1L
      println(f"MOVE16_ORACLE kernel=${k.name}%-16s seed=$seed acc/line=$accesses%d " +
              f"cycles=${r.windowCycles}%d lines=$lines%d cyc/line=$cycPerLine%.3f " +
              f"copyB/cyc=$copyBpc%.4f retired=${r.retiredInstrs}%d IPC=${r.ipc}%.4f " +
              f"dAR=$ar%d dAW=$aw%d memory=$memLabel")
    }
  }

  /** SECOND HALF OF THE ATTRIBUTION: the same three access-count points on a working
    * set that FITS IN L1, so after the warm-up pass there are ZERO misses, ZERO
    * write-allocate fetches and ZERO writebacks. 2 KB source + 2 KB destination = 256
    * of the 512 L1 lines, and the two regions differ only in paddr bit 20 so they land
    * in the same 128 sets using 2 of 4 ways -- both resident simultaneously.
    *
    * cycles/line here is the PURE LS-PIPE cost of `2*q` accesses. Subtracting it from
    * the 16 KB streaming number attributes the rest to the memory system, which is the
    * decomposition `docs/PERF_LEVER_QUEUE.md` lever 16 asked for and nobody supplied.
    */
  test("MOVE16 oracle: L1-resident pipe cost per access", VerilatorTest) {
    val bytes  = 2048
    val passes = 4
    val kernels = Seq(
      (kMemcpyQuads(4, bytes, passes, "l1res-q4"), 8, 4),
      (kMemcpyQuads(2, bytes, passes, "l1res-q2"), 4, 2),
      (kMemcpyQuads(1, bytes, passes, "l1res-q1"), 2, 1),
      (kMemcpyMove16(bytes, passes, "l1res-m16"), 8, 4))

    val dut = M68kSim().withVerilator.compile(new FullCoreDut(
      alignedLoadFallThrough = true, earlyLsIntWakeup = true,
      earlyStoreAddress = true, trainSlot1Conditional = true,
      deferTakenSlot1Conditional = true, retainRedirectHistory = true))

    for ((k, accesses, quads) <- kernels; seed <- Seq(1, 17)) {
      var cap: AxiMemModel = null
      val orig = k.prepMem
      val kk = k.copy(prepMem = h => { cap = h.dmem; orig(h) })
      val r = runKernel(dut, kk, seed)
      val lines      = (bytes / 16) * (passes - 1)
      val cycPerLine = r.windowCycles.toDouble / lines
      val ar = if (cap != null) cap.stats.totalAr else -1L
      val aw = if (cap != null) cap.stats.totalAw else -1L
      println(f"MOVE16_L1RES kernel=${k.name}%-12s seed=$seed acc/line=$accesses%d " +
              f"cycles=${r.windowCycles}%d lines=$lines%d cyc/line=$cycPerLine%.3f " +
              f"retired=${r.retiredInstrs}%d IPC=${r.ipc}%.4f dAR=$ar%d dAW=$aw%d")
    }
  }
}
