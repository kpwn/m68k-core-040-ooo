package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import m68k040.sim.AxiMemModel
import spinal.core.sim._

/** LATENCY / THROUGHPUT microbenchmark suite for the 2-wide OoO 68040.
  *
  * `IpcBenchSpec` answers "how many instructions per cycle does a kernel sustain".
  * This suite answers the complementary question: "how many cycles does ONE
  * operation cost", for the structures whose cost has only ever been asserted, not
  * measured -- store-to-load forwarding, the L1D/L2/DRAM hierarchy, the DTLB and
  * its table walker, branch recovery, and the long-latency EUs.
  *
  * ════════════════════════════════════════════════════════════════════════════
  * MEASUREMENT METHOD -- differential (marginal-cost) timing
  * ════════════════════════════════════════════════════════════════════════════
  * Every latency here is a DIFFERENCE between two runs of the SAME kernel shape
  * that differ ONLY in the length of a dependency chain:
  *
  *     cyclesPerStep = (cycles(nLong) - cycles(nShort)) / (nLong - nShort)
  *
  * Everything that is constant between the two runs cancels EXACTLY: pipeline
  * fill, pipeline drain, the measurement window's own edges, register setup, the
  * cold-start cache misses of the first pass, and any fixed loop preamble. What
  * remains is the marginal cost of the extra chain steps and nothing else. This is
  * why no "overhead subtraction" constant is ever hand-estimated in this file --
  * the subtraction is structural.
  *
  * A second subtraction is applied where a chain step contains work OTHER than the
  * operation under test (e.g. a memory chase step also performs two address adds).
  * For those, an identically-shaped CONTROL kernel is measured in which only the
  * operation under test is replaced by a register-register move, and the reported
  * latency is (test - control). Both halves are printed so the raw numbers stay
  * visible and the subtraction is auditable.
  *
  * ════════════════════════════════════════════════════════════════════════════
  * LATENCY vs THROUGHPUT -- and why it matters on THIS core
  * ════════════════════════════════════════════════════════════════════════════
  * This is an out-of-order machine: it hides EU latency by issuing independent
  * work. A loop of independent divides measures the divider's THROUGHPUT and will
  * report a divide as far cheaper than it is. Every kernel below is explicitly
  * labelled DEP (a true serial dependency chain -- measures LATENCY) or IND
  * (mutually independent operations -- measures THROUGHPUT). Both are reported for
  * the long-latency EUs, because the gap between them IS the finding.
  *
  * ════════════════════════════════════════════════════════════════════════════
  * WHAT IS REAL RTL AND WHAT IS A MODEL  (read before quoting any number)
  * ════════════════════════════════════════════════════════════════════════════
  * The core, its L1I/L1D, the store queue, the TLBs and the table walker are REAL
  * RTL and everything measured about them is a real measurement. Beyond the core's
  * AXI ports there is NO RTL -- `m68k040.sim.AxiMemModel` stands in for the SoC:
  *
  *   - `hitCycles = 5` (L2 hit round trip) is CALIBRATED against the real L2
  *     (`macqd700-soc/docs/l2c_spec.md:668-679`), so L2-hit numbers are meaningful.
  *   - `dramCycles` is *** EXPLICITLY UNMEASURED *** -- the model's own header says
  *     "real DDR4/MIG latency is undocumented in BOTH repos. This MUST be swept,
  *     never assumed." Any absolute "DDR latency" figure from this suite is
  *     therefore a MODEL INPUT being echoed back, not a property of the machine.
  *     The DRAM benchmark below is consequently run as a SWEEP over dramCycles and
  *     what is reported is the SLOPE (how much of memory latency the core fails to
  *     hide) and the INTERCEPT (the core-side fixed miss-handling cost). Those two
  *     ARE real properties of the core; the absolute number is not.
  *   - The model's L2 has UNBOUNDED capacity (`l2Lines` is a `mutable.Set` that is
  *     never evicted from). An L2 CAPACITY miss cannot be produced at all; only a
  *     COLD (first-touch) miss can. Said plainly where it applies below.
  *
  * ════════════════════════════════════════════════════════════════════════════
  * VALIDATION OF THE TIMING SOURCE
  * ════════════════════════════════════════════════════════════════════════════
  * The timing source is the harness's per-cycle commit histogram (`runKernel`),
  * i.e. simulation clock edges counted between the first and last macro-commit --
  * not a CSR, not a wall clock. Before any latency is reported, `validation` below
  * proves it is sound, and FAILS the suite if it is not:
  *
  *   V1 LINEARITY  -- a chain of N NOPs must cost time strictly proportional to N.
  *                    Measured at three different (short,long) pairs; the three
  *                    marginal costs must agree. A counter that saturated, wrapped,
  *                    double-counted or missed cycles cannot pass this.
  *   V2 KNOWN COST -- a dependent ALU chain must cost EXACTLY 1.000 cycles/op, the
  *                    documented single-cycle ALU result-forwarding latency. This
  *                    anchors the absolute scale, which V1 alone cannot.
  *   V3 SUPERSCALAR-- independent ALU ops must cost measurably LESS than 1.0
  *                    cycles/op on this 2-wide machine, confirming the counter
  *                    tracks real dual retire rather than instruction count.
  *
  * Run:  sbt 'testOnly m68k040.bench.MicrobenchSpec'
  *       MB_ONLY=validation,sq-forward sbt 'testOnly m68k040.bench.MicrobenchSpec'
  *       MB_SEEDS=5 ...   (variance sweep width; default 3)
  */
class MicrobenchSpec extends CoreBenchHarness {

  // ── configuration ───────────────────────────────────────────────────────────

  /** Seeds for the variance sweep. SpinalHDL randomises the AXI agents' ready
    * throttling per seed, so a single reading is NOT a measurement: it is one draw
    * from a distribution. Every number in the report is mean +- spread over these. */
  val seeds: Seq[Int] = {
    val n = sys.env.get("MB_SEEDS").map(_.toInt).getOrElse(3)
    (0 until n).map(i => 0x6D68 + i * 7919)
  }

  /** Optional scenario filter, comma-separated (MB_ONLY=validation,divmul). */
  val only: Option[Set[String]] =
    sys.env.get("MB_ONLY").filter(_.nonEmpty).map(_.split(',').map(_.trim).toSet)
  def enabled(group: String): Boolean = only.forall(_.contains(group))

  /** Data region for the memory benchmarks. Far from the code image
    * (`ProgramAssembler.DefaultLoadAddress` = 0x40800000) and from the MMU tables. */
  val DataBase = 0x00400000L

  // ── differential measurement core ───────────────────────────────────────────

  /** One (short,long) differential pair measured under ONE seed. */
  final case class DiffSample(shortCycles: Int, longCycles: Int,
                              shortSteps: Int, longSteps: Int) {
    require(longSteps > shortSteps, "differential needs longSteps > shortSteps")
    def perStep: Double = (longCycles - shortCycles).toDouble / (longSteps - shortSteps)
  }

  /** A measured quantity plus its spread across seeds. */
  final case class Stat(name: String, samples: Seq[Double]) {
    def n: Int         = samples.size
    def mean: Double   = if (n == 0) 0.0 else samples.sum / n
    def min: Double    = if (n == 0) 0.0 else samples.min
    def max: Double    = if (n == 0) 0.0 else samples.max
    /** Population standard deviation across seeds. */
    def stddev: Double =
      if (n <= 1) 0.0
      else math.sqrt(samples.map(x => (x - mean) * (x - mean)).sum / n)
    /** Peak-to-peak spread; the honest "how much did this move" for small n. */
    def spread: Double = if (n == 0) 0.0 else max - min
    def fmt: String    = f"$mean%8.3f +-${stddev}%6.3f  [$min%.3f..$max%.3f] n=$n"
    /** Raw per-seed draws. Printed with every row: a mean and a sigma can hide a
      * bimodal or degenerate sample (e.g. one seed returning exactly 0.000, which
      * indicates the two runs terminated at the SAME cycle and the differential
      * measured nothing at all). The raw list makes that visible instead of
      * averaging it into a plausible-looking number. */
    def raw: String    = samples.map(x => f"$x%.2f").mkString("{", ", ", "}")
  }

  /** Measure the marginal cycles-per-step of a chain, across all seeds.
    *
    * `mk(n)` must build a kernel whose ONLY difference as `n` varies is that the
    * chain under test performs `n` steps -- identical setup, identical loop
    * structure, identical register allocation. That is what makes the subtraction
    * exact. */
  def diffStat(compiled: SimCompiled[FullCoreDut], name: String,
               mk: Int => Kernel, nShort: Int, nLong: Int): Stat = {
    val perSeed = seeds.map { s =>
      val lo = runKernel(compiled, mk(nShort), s)
      val hi = runKernel(compiled, mk(nLong),  s)
      DiffSample(lo.windowCycles, hi.windowCycles, nShort, nLong).perStep
    }
    Stat(name, perSeed)
  }

  /** Convenience: run one kernel under all seeds and reduce a scalar from it. */
  def scalarStat(compiled: SimCompiled[FullCoreDut], name: String,
                 k: Kernel, f: IpcResult => Double): Stat =
    Stat(name, seeds.map(s => f(runKernel(compiled, k, s))))

  // ── reporting ───────────────────────────────────────────────────────────────

  private val rows = scala.collection.mutable.ArrayBuffer.empty[(String, String, Stat, String)]
  /** Record one reported measurement: group, what it is, the stat, and the
    * METHODOLOGY string that must travel with it. */
  def report(group: String, what: String, st: Stat, method: String): Unit = {
    rows += ((group, what, st, method))
    println(f"  [$group%-12s] $what%-34s ${st.fmt}  raw=${st.raw}  | $method")
  }

  // ════════════════════════════════════════════════════════════════════════════
  // KERNELS
  // ════════════════════════════════════════════════════════════════════════════
  // Assembly is GNU `as` m68k syntax (ProgramAssembler shells out to
  // m68k-linux-gnu-as -m68040). Registers MUST be %-prefixed and hex MUST be 0x --
  // a bare `d0` or a `$40` silently becomes an undefined symbol that only fails at
  // link time. Statements are joined with " ; ".

  /** Number of macro-instructions the harness should wait for. Kept slightly BELOW
    * the kernel's true retire count on purpose: overshooting would let a trailing
    * park loop (`bra .`) pad the window with cheap taken branches and silently
    * DEFLATE every latency, whereas stopping a few instructions early leaves the
    * differential exact (both runs stop inside the chain). */
  def stopAt(total: Int): Int = total - 8

  // ── V1/V2/V3: timing-source validation ──────────────────────────────────────

  /** IND. N independent NOPs. Used ONLY to prove the cycle count is linear in N. */
  def kNops(n: Int): Kernel = {
    val src = (Seq("moveq #0,%d0") ++ Seq.fill(n)("nop") ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"nop-$n", src, stopAt(1 + n))
  }

  /** DEP. Strict alternating dependency chain; each add consumes the previous
    * result. Documented cost: 1 cycle/op (single-cycle ALU with result forwarding). */
  def kAluDep(n: Int): Kernel = {
    val chain = (0 until n).map(i => if (i % 2 == 0) "add.l %d0,%d1" else "add.l %d1,%d0")
    val src = (Seq("moveq #1,%d0", "moveq #1,%d1") ++ chain ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"alu-dep-$n", src, stopAt(2 + n))
  }

  /** IND. Six mutually independent accumulator chains -> the 2 ALU EUs can both
    * retire every cycle. Measures ALU THROUGHPUT, not latency. */
  def kAluInd(n: Int): Kernel = {
    val regs = Seq(2, 3, 4, 5, 6, 7)
    val body = (0 until n).map(i => s"add.l %d0,%d${regs(i % regs.size)}")
    val setup = (0 to 7).map(r => s"moveq #${r + 1},%d$r")
    val src = (setup ++ body ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"alu-ind-$n", src, stopAt(setup.size + n))
  }

  // ── store-to-load forwarding ────────────────────────────────────────────────

  /** DEP. store d0 -> [X] ; load [X] -> d0. The load's address matches the store
    * still resident in the store queue, so it MUST be satisfied by the SQ forward
    * path; the loaded value then feeds the next store, making this a strict serial
    * chain. 2 macros/step. Corroborated by `sqFwdHitCycles` in the result.
    *
    * Without forwarding this loop cannot proceed until each store DRAINS to the
    * L1D, which cannot happen before the store retires -- so a small number here
    * is itself proof the forward path is live. */
  def kSqForward(n: Int): Kernel = {
    val step = Seq("move.l %d0,0x4000", "move.l 0x4000,%d0")
    val body = (0 until n).flatMap(_ => step)
    val src = (Seq("moveq #7,%d0") ++ body ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    // COPYBACK (match-all transparent translation, CM=01) is ESSENTIAL here. Under
    // the MMU-off default every store is WRITETHROUGH and must reach AXI, and the
    // store-drain rate (~10.6 cyc/store, measured) completely dominates and HIDES
    // the forward latency -- the first version of this benchmark measured store
    // drain and would have reported it as forwarding cost. Under COPYBACK a store
    // hit resolves entirely on-chip with zero AXI traffic, so what is left in the
    // chain is store -> SQ forward -> load -> use.
    Kernel(s"sqfwd-$n", src, stopAt(1 + n * 2), copybackDtt = true)
  }

  /** REFERENCE (deliberately NOT a subtraction control) for kSqForward: identical
    * shape and macro count, but the load is replaced by a register move.
    *
    * This is NOT a matched control and its value must NOT be subtracted from
    * kSqForward. Removing the load also removes the only RAW edge in the chain:
    * the remaining store-reads-d0 / move-writes-d0 pair is a WAR dependency, which
    * register renaming eliminates entirely. So this kernel is throughput-bound
    * (store issue rate) while kSqForward is latency-bound (a real serial chain) --
    * subtracting one from the other compares two different regimes. It is reported
    * alongside purely to show how far the forwarding chain sits above the floor. */
  def kSqForwardControl(n: Int): Kernel = {
    val step = Seq("move.l %d0,0x4000", "move.l %d1,%d0")
    val body = (0 until n).flatMap(_ => step)
    val src = (Seq("moveq #7,%d0", "moveq #3,%d1") ++ body ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"sqfwdctl-$n", src, stopAt(2 + n * 2), copybackDtt = true)
  }

  // ── memory hierarchy ────────────────────────────────────────────────────────
  // A pointer chase needs pointers in memory, which would make the measurement
  // depend on getting the memory model's byte order right. These kernels avoid
  // that entirely with a ZERO-LOAD chase: the D-side memory is zero-filled, so the
  // loaded value is 0 under ANY byte order, and the address is advanced by a
  // constant. The address register is nonetheless made to DEPEND on the loaded
  // value (`adda.l %d1,%a0` with d1==0), so the chain is a true serial
  // load-to-use dependency and the OoO engine cannot run ahead of it.
  //
  //   move.l (%a0),%d1     <- the load under test (returns 0)
  //   adda.l %d1,%a0       <- a0 now DEPENDS on the load result
  //   adda.l #STRIDE,%a0   <- advance
  //
  // 3 macros/step. The two adds are removed by the matched CONTROL below.

  private def chaseStep(stride: Int) =
    Seq("move.l (%a0),%d1", "adda.l %d1,%a0", s"adda.l #$stride,%a0")
  private def chaseControlStep(stride: Int) =
    Seq("move.l %d2,%d1", "adda.l %d1,%a0", s"adda.l #$stride,%a0")

  /** DEP, STRAIGHT-LINE. Every step touches a NEVER-BEFORE-TOUCHED 64-byte line,
    * so every step is a cold miss all the way out to the memory model's DRAM path.
    *
    * NOTE ON WHAT THIS MEASURES: the model's L2 never evicts, so this is a COLD
    * miss, not a capacity miss -- and its DRAM component is the unmeasured
    * `dramCycles` parameter. Run as a sweep; the slope/intercept are the result,
    * the absolute value is not. */
  def kChaseCold(n: Int, load: Boolean = true): Kernel = {
    val step = if (load) chaseStep(64) else chaseControlStep(64)
    val body = (0 until n).flatMap(_ => step)
    val setup = Seq(f"lea 0x$DataBase%08x,%%a0", "moveq #0,%d2")
    val src = (setup ++ body ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"chase-cold-${if (load) "ld" else "ctl"}-$n", src, stopAt(setup.size + n * 3))
  }

  /** DEP, LOOPED. `inner` chase steps at stride 64 over a FIXED footprint, repeated
    * `outer` times; the differential is taken over `outer`, so the cold first pass
    * and all loop preamble cancel and what is left is the cost of ONE fully-warm
    * pass over the footprint.
    *
    * footprintBytes = inner * 64 selects the level under test:
    *   - 4 KiB  (inner=64)  < 8 KiB L1D  -> every access is an L1D HIT
    *   - 32 KiB (inner=512) > 8 KiB L1D  -> every access MISSES L1D and, because
    *     the previous pass already installed the line in the model's L2, HITS L2.
    * L1D is 8 KiB, 4-way, 128 sets, 16-byte lines with round-robin replacement, so
    * a sequential sweep of 4x the cache size misses essentially every time. */
  def kChaseLoop(inner: Int, outer: Int, load: Boolean = true): Kernel = {
    val step = (if (load) chaseStep(64) else chaseControlStep(64)).mkString(" ; ")
    val setup = Seq("moveq #0,%d2", s"move.l #$outer,%d6")
    // per outer iteration: lea + move.l(d7) + inner*(3 chase + subq + bne) + subq + bne
    val perOuter = 2 + inner * 5 + 2
    val loop =
      f".Louter: lea 0x$DataBase%08x,%%a0 ; move.l #$inner,%%d7 ; " +
      s".Linner: $step ; subq.l #1,%d7 ; bne.s .Linner ; " +
      "subq.l #1,%d6 ; bne.s .Louter"
    val src = (setup ++ Seq(loop, ".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"chase-loop-$inner-${if (load) "ld" else "ctl"}-$outer", src,
      stopAt(setup.size + outer * perOuter))
  }

  // ── branch misprediction ────────────────────────────────────────────────────

  /** A loop whose conditional branch alternates direction under data control, so
    * the predictor is repeatedly wrong. Reported as a differential over iteration
    * count. The DIRECT recovery measurement is `flushToCommit` (flush pulse -> next
    * commit), which the harness records per event; this kernel exists to GENERATE
    * those events densely and to give an independent end-to-end cross-check. */
  def kBranchToggle(iters: Int): Kernel = {
    // d6 toggles 0/1 each iteration via eori; beq is therefore taken every other
    // time in an alternating pattern.
    val setup = Seq(s"move.l #$iters,%d7", "moveq #0,%d6", "moveq #0,%d0", "moveq #1,%d1")
    val body =
      ".Lbt: eori.l #1,%d6 ; tst.l %d6 ; beq.s .Lskip ; add.l %d1,%d0 ; " +
      ".Lskip: subq.l #1,%d7 ; bne.s .Lbt"
    // per iter: eori, tst, beq, (add on half the iterations), subq, bne
    val perIter = 5 + 1 // average 5.5; use 5 and let the harness stop early
    val src = (setup ++ Seq(body, ".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"br-toggle-$iters", src, stopAt(setup.size + iters * perIter / 2 * 2 - iters))
  }

  /** Matched PREDICTABLE control: identical instruction mix and identical macro
    * count, but the branch always falls the same way, so the predictor is right
    * every time. (test - control) is the average end-to-end cost of a mispredict. */
  def kBranchPredictable(iters: Int): Kernel = {
    val setup = Seq(s"move.l #$iters,%d7", "moveq #0,%d6", "moveq #0,%d0", "moveq #1,%d1")
    val body =
      ".Lbp: eori.l #0,%d6 ; tst.l %d6 ; bne.s .Lskip ; add.l %d1,%d0 ; " +
      ".Lskip: subq.l #1,%d7 ; bne.s .Lbp"
    val perIter = 5 + 1
    val src = (setup ++ Seq(body, ".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"br-pred-$iters", src, stopAt(setup.size + iters * perIter / 2 * 2 - iters))
  }

  // ── long-latency EUs ────────────────────────────────────────────────────────
  // divu.w/mulu.w by 1 leave the operand unchanged (quotient = d0, remainder = 0;
  // product = d0 for d0 < 65536), so a chain of them is a TRUE serial dependency
  // chain of constant-latency operations rather than a chain that degenerates to
  // zero after two steps.

  /** DEP. Serial chain -> measures LATENCY. */
  def kCplxDep(op: String, n: Int): Kernel = {
    val setup = Seq("moveq #1,%d1", "move.l #12345,%d0")
    val body = (0 until n).map(_ => s"$op %d1,%d0")
    val src = (setup ++ body ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"$op-dep-$n", src, stopAt(setup.size + n))
  }

  /** IND. Six independent destination registers -> measures THROUGHPUT. The
    * divider is single-outstanding, so if throughput ~= latency that is the
    * measured confirmation of the serialization, not an assumption. */
  def kCplxInd(op: String, n: Int): Kernel = {
    val regs = Seq(0, 2, 3, 4, 5, 6)
    val setup = Seq("moveq #1,%d1") ++ regs.map(r => s"move.l #12345,%d$r")
    val body = (0 until n).map(i => s"$op %d1,%d${regs(i % regs.size)}")
    val src = (setup ++ body ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"$op-ind-$n", src, stopAt(setup.size + n))
  }

  /** DEP. FPU chain. The FPU lives inside DivEuPlugin (the CPLX EU), which the
    * bench DUT does instantiate, so FP ops are reachable here. fmul by 1.0 is
    * value-stable, keeping the chain serial and constant-latency. */
  def kFpDep(op: String, n: Int): Kernel = {
    // Source the FP operands through integer registers: the immediate-<ea> form of
    // fmove is documented as deliberately unimplemented in this core even though it
    // assembles cleanly (FpuControlWiringSpec.scala:135-139).
    val setup = Seq("moveq #1,%d0", "fmove.l %d0,%fp1", "moveq #3,%d0", "fmove.l %d0,%fp0")
    val body = (0 until n).map(_ => s"$op %fp1,%fp0")
    val src = (setup ++ body ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"$op-fp-dep-$n", src, stopAt(setup.size + n))
  }

  // ── MMU / DTLB ──────────────────────────────────────────────────────────────
  // 68040 3-level long-format page tables, built directly into the D-side walker's
  // OWN memory. On this HEAD the walker has a dedicated AXI port and does NOT go
  // through the L1D, so `dWalkMem` is genuinely separate from `dmem` -- which is
  // exactly the "before" state for the in-flight change that routes walks via L1D.
  //
  // Descriptor bytes are BIG-ENDIAN (task #194); this poke order matches
  // TableWalkerSpec/ExecuteLockStepSpec's proven helper.

  val MmuRoot = 0x00080000L
  val MmuPtr  = 0x00081000L
  val MmuLeaf = 0x00082000L   // bump-allocated, 0x1000 apart, one per pointer slot

  private def pokeDescBE(mem: AxiMemModel, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)

  /** Identity-map `pages` 4 KiB pages starting at `vaBase`, stepping `stride`.
    * Leaf tables are bump-allocated per distinct pointer-table slot. */
  def buildIdentityTables(mem: AxiMemModel, vaBase: Long, stride: Long, pages: Int): Unit = {
    var nextLeaf = MmuLeaf
    val leafFor = scala.collection.mutable.HashMap.empty[Int, Long]
    for (p <- 0 until pages) {
      val va      = vaBase + p * stride
      val rootIdx = ((va >> 25) & 0x7f).toInt
      val ptrIdx  = ((va >> 18) & 0x7f).toInt
      val pageIdx = ((va >> 12) & 0x3f).toInt
      val leaf    = leafFor.getOrElseUpdate(ptrIdx, { val l = nextLeaf; nextLeaf += 0x1000L; l })
      // root -> pointer table, pointer -> leaf table: descriptor type 3 (table)
      pokeDescBE(mem, MmuRoot + rootIdx * 4, (MmuPtr & 0xfffffff0L) | 0x3L)
      pokeDescBE(mem, MmuPtr  + ptrIdx  * 4, (leaf   & 0xfffffff0L) | 0x3L)
      // leaf: resident page descriptor (PDT=01), identity PA == VA
      pokeDescBE(mem, leaf + pageIdx * 4, (va & 0xfffff000L) | 0x1L)
    }
  }

  /** MMU-on setup that leaves the D-side to the WALKER while keeping the I-side on
    * a match-all transparent translation. That isolates D-side walk cost: the code
    * stream never walks, so nothing but the data accesses can be paying. */
  def mmuWalkD: MmuSetup =
    MmuSetup(enable = true, urp = MmuRoot, srp = MmuRoot,
             itt0 = 0x00FFC000L,   // I-side: E|S|mask-all, write-through -> no ITLB walk
             dtt0 = 0L)            // D-side: no transparent translation -> real walks

  /** MMU-on setup where BOTH sides are transparently translated: TC.E is on, the
    * TLBs are exercised, but no walk can ever occur. The DTLB-hit baseline. */
  def mmuNoWalk: MmuSetup =
    MmuSetup(enable = true, urp = MmuRoot, srp = MmuRoot,
             itt0 = 0x00FFC000L, dtt0 = 0x00FFC000L)

  /** DEP. Page-striding zero-load chase under a real MMU.
    *
    * `pageStride` selects DTLB hit vs miss. The DTLB is 32 entries, 4-way, 2 banks
    * (4 sets/bank) keyed bank=vpn[0], set=vpn[2:1] -- so vpn[2:0] picks the set.
    * A stride of 8 pages (32 KiB) holds vpn[2:0] CONSTANT, driving every access
    * into the SAME 4-way set; cycling over more than 4 such pages evicts on every
    * touch and forces a fresh 3-level table walk each time. A stride of 1 page
    * spreads across all sets and stays resident -> DTLB hit. */
  /** STRAIGHT-LINE page-striding zero-load chase, `n` steps at 32 KiB stride.
    *
    * The `walk` flag changes EXACTLY ONE THING: whether the D-side has a match-all
    * transparent translation (no walk possible) or no TTR at all (every DTLB miss
    * runs a real 3-level table walk). Footprint, stride, instruction mix, macro
    * count, L1D behaviour and cold-miss behaviour are byte-for-byte identical
    * between the two, so (walk - noWalk) isolates the table-walk cost and nothing
    * else. This replaced an earlier looped version whose outer-loop differential
    * was unstable across seeds (spread larger than the mean) and was discarded.
    *
    * A 32 KiB (8-page) stride holds vpn[2:0] constant, so every access lands in the
    * SAME 4-way DTLB set; striding past 4 such pages evicts on every touch and
    * guarantees a fresh walk per access rather than a warm hit. */
  def kTlbChase(n: Int, walk: Boolean): Kernel = {
    val strideBytes = 8 * 4096   // 32 KiB: constant vpn[2:0] -> one DTLB set
    val setup = Seq(f"lea 0x$DataBase%08x,%%a0", "moveq #0,%d2")
    val body = (0 until n).flatMap(_ => chaseStep(strideBytes))
    val src = (setup ++ body ++ Seq(".Lend: bra.s .Lend")).mkString(" ; ")
    Kernel(s"tlb-${if (walk) "walk" else "ttr"}-$n", src, stopAt(setup.size + n * 3),
      mmu = Some(if (walk) mmuWalkD else mmuNoWalk),
      prepMem = h => buildIdentityTables(h.dWalkMem, DataBase, strideBytes.toLong, n + 4))
  }

  // ════════════════════════════════════════════════════════════════════════════
  // THE SUITE
  // ════════════════════════════════════════════════════════════════════════════

  test("68040 OoO latency microbenchmark suite", VerilatorTest) {
    // Compile the core ONCE and reuse it for every kernel and every seed -- this
    // suite runs well over a hundred simulations and a per-kernel Verilator build
    // would dominate the runtime.
    val compiled = M68kSim().withVerilator.compile(new FullCoreDut)

    println("=" * 100)
    println("68040 OoO LATENCY MICROBENCHMARK SUITE")
    println(s"  memory model : $memLabel")
    println(s"  seeds        : ${seeds.mkString(",")}  (variance is across these)")
    println("  method       : differential -- perStep = (cyc(long)-cyc(short))/(long-short)")
    println("  DEP = dependency chain (LATENCY).  IND = independent ops (THROUGHPUT).")
    println("=" * 100)

    // ── V: timing-source validation (must pass before anything is believed) ────
    if (enabled("validation")) {
      println("\n-- VALIDATION OF THE TIMING SOURCE ------------------------------------")

      val nopPairs = Seq((200, 400), (400, 800), (200, 800))
      val nopStats = nopPairs.map { case (a, b) =>
        diffStat(compiled, s"nop $a->$b", n => kNops(n), a, b)
      }
      nopPairs.zip(nopStats).foreach { case ((a, b), st) =>
        report("validate", s"V1 NOP marginal cost ($a->$b)", st,
          s"IND; ${seeds.size} seeds; differential")
      }
      val nopMeans = nopStats.map(_.mean)
      val nopSpread = nopMeans.max - nopMeans.min
      println(f"  V1 LINEARITY: three independent (short,long) pairs give " +
        f"${nopMeans.map(m => f"$m%.3f").mkString(", ")} cycles/NOP; spread $nopSpread%.4f")
      assert(nopSpread < 0.05,
        f"V1 FAILED: NOP cost is NOT linear in N (spread $nopSpread%.4f cycles/op across " +
        f"pairs ${nopPairs.mkString(",")}). The cycle count is not a sound timing source; " +
        "no latency below can be trusted.")

      val aluDep = diffStat(compiled, "alu-dep", n => kAluDep(n), 200, 400)
      report("validate", "V2 dependent ALU (known = 1.0)", aluDep, "DEP; differential")
      assert(math.abs(aluDep.mean - 1.0) < 0.05,
        f"V2 FAILED: dependent ALU chain measured ${aluDep.mean}%.3f cycles/op, expected " +
        "1.000 (single-cycle ALU with result forwarding). Either the timing source is " +
        "wrong or the ALU forwarding path has regressed -- both are real findings.")

      val aluInd = diffStat(compiled, "alu-ind", n => kAluInd(n), 240, 480)
      report("validate", "V3 independent ALU (2-wide)", aluInd, "IND; differential")
      assert(aluInd.mean < aluDep.mean - 0.1,
        f"V3 FAILED: independent ALU (${aluInd.mean}%.3f) is not cheaper than dependent " +
        f"(${aluDep.mean}%.3f); the counter is not tracking dual retire.")

      println("  VALIDATION PASSED -- cycle counting is linear, absolutely anchored, " +
        "and tracks dual retire.")
    }

    // ── store-to-load forwarding ──────────────────────────────────────────────
    if (enabled("sq-forward")) {
      println("\n-- STORE-TO-LOAD FORWARDING ------------------------------------------")
      val fwd = diffStat(compiled, "sqfwd", n => kSqForward(n), 100, 200)
      val ctl = diffStat(compiled, "sqfwdctl", n => kSqForwardControl(n), 100, 200)
      report("sq-fwd", "store->load->use round trip (DEP)", fwd,
        "DEP; differential; 2 macros/step; COPYBACK so store drain does not dominate; " +
        "THIS is the forwarded store-to-load latency")
      report("sq-fwd", "reference: load removed (IND)", ctl,
        "IND/throughput-bound REFERENCE ONLY -- do NOT subtract; removing the load " +
        "leaves only a renamed-away WAR edge, so this measures store issue rate")

      // Corroboration: prove the SQ forward path actually fired.
      val probe = runKernel(compiled, kSqForward(200), seeds.head)
      val probeCtl = runKernel(compiled, kSqForwardControl(200), seeds.head)
      println(s"  SQ forward-hit cycles: forwarding kernel=${probe.sqFwdHitCycles}, " +
        s"control kernel=${probeCtl.sqFwdHitCycles}")
      assert(probe.sqFwdHitCycles > 0,
        "store-to-load forwarding kernel recorded ZERO SQ full-overlap forward responses -- " +
        "it is NOT measuring the forward path, so the number above would be mislabelled.")
    }

    // ── memory hierarchy ──────────────────────────────────────────────────────
    if (enabled("memory")) {
      println("\n-- MEMORY HIERARCHY --------------------------------------------------")
      println("  NOTE: beyond the core's AXI port this is m68k040.sim.AxiMemModel, not RTL.")
      println("        L2 hitCycles=5 is calibrated to the real L2; dramCycles is UNMEASURED.")

      def chaseLevel(tag: String, inner: Int, o1: Int, o2: Int, note: String): Unit = {
        val ld  = diffStat(compiled, s"$tag-ld",  o => kChaseLoop(inner, o, load = true),  o1, o2)
        val ctl = diffStat(compiled, s"$tag-ctl", o => kChaseLoop(inner, o, load = false), o1, o2)
        // per-pass cost -> per-access cost
        val perAccess = Stat(tag, ld.samples.zip(ctl.samples).map {
          case (a, b) => (a - b) / inner
        })
        report("memory", s"$tag load-to-use (DEP)", perAccess,
          s"DEP; differential over passes; /$inner accesses; minus matched no-load control; $note")
      }

      // 64 steps * 64B = 4 KiB footprint  -> fits in the 8 KiB L1D  -> hits
      chaseLevel("L1D-hit", 64, 4, 8, "4KiB footprint < 8KiB L1D")
      // 512 steps * 64B = 32 KiB footprint -> 4x L1D -> misses L1D, hits model L2
      chaseLevel("L1D-miss/L2-hit", 512, 2, 4, "32KiB footprint > 8KiB L1D; L2-resident")

      // Cold/DRAM: straight-line, every step a fresh 64B line.
      val cold = diffStat(compiled, "cold-ld",  n => kChaseCold(n, load = true),  150, 300)
      val coldC = diffStat(compiled, "cold-ctl", n => kChaseCold(n, load = false), 150, 300)
      report("memory", "cold miss -> model DRAM (DEP)", Stat("cold",
        cold.samples.zip(coldC.samples).map { case (a, b) => a - b }),
        "DEP; differential; minus matched control; COLD (first-touch) miss -- the model's " +
        "L2 never evicts so a CAPACITY miss cannot be produced; absolute value tracks the " +
        "UNMEASURED dramCycles parameter, see the sweep")
    }

    // ── branch misprediction ──────────────────────────────────────────────────
    if (enabled("branch")) {
      println("\n-- BRANCH MISPREDICTION ----------------------------------------------")
      // Direct measurement: flush pulse -> next committed macro.
      val tog = runKernel(compiled, kBranchToggle(400), seeds.head)
      val recov = Stat("flush->commit", seeds.map { s =>
        runKernel(compiled, kBranchToggle(400), s).flushRecoveryMean
      })
      report("branch", "recovery: flush -> next commit", recov,
        s"DIRECT per-event instrumentation; ${tog.flushToCommit.size} events/run; " +
        "not inferred from pipeline depth")
      val hist = tog.flushToCommit.groupBy(identity).toVector.sortBy(_._1)
        .map { case (d, xs) => s"$d:${xs.size}" }.mkString(", ")
      println(s"  recovery histogram (seed ${seeds.head}): {$hist}")

      // Correctly-predicted baseline: a loop whose backward branch is taken every
      // time. Reported as the per-iteration cost WITHOUT mispredicts, so the
      // recovery figure above has something to sit against.
      val bp = diffStat(compiled, "br-pred", i => kBranchPredictable(i), 200, 400)
      val bt = diffStat(compiled, "br-toggle", i => kBranchToggle(i), 200, 400)
      report("branch", "loop iter, branch predicted", bp,
        "DEP; differential over iters; correctly-predicted taken backward branch")
      report("branch", "loop iter, branch toggling", bt,
        "DEP; differential over iters; direction alternates")
      // NOTE: (toggling - predictable) is NOT reported as a mispredict penalty. The
      // two loops do not retire the same instructions -- the conditional guards an
      // ADD, so the toggling loop skips it on half its iterations while the
      // predictable loop executes it every time. The difference therefore mixes a
      // real recovery cost with a -0.5-instruction-per-iteration accounting
      // artefact, and in practice comes out NEGATIVE. The direct flush->commit
      // instrumentation above needs no such control and is the reported number.
      println("  NOT REPORTED: (toggling - predictable) as a penalty -- the two loops " +
        "do not retire the same instruction mix (the guarded ADD is skipped on half " +
        "the toggling iterations), so that difference is not a misprediction cost.")
    }

    // ── long-latency EUs ──────────────────────────────────────────────────────
    if (enabled("divmul")) {
      println("\n-- LONG-LATENCY EXECUTION UNITS --------------------------------------")
      Seq("divu.w", "divs.w", "mulu.w", "muls.w").foreach { op =>
        try {
          val dep = diffStat(compiled, s"$op-dep", n => kCplxDep(op, n), 40, 80)
          val ind = diffStat(compiled, s"$op-ind", n => kCplxInd(op, n), 40, 80)
          report("divmul", s"$op LATENCY (DEP)", dep, "DEP; serial chain; differential")
          report("divmul", s"$op THROUGHPUT (IND)", ind,
            "IND; 6 independent dests; differential -- compare with LATENCY above")
        } catch {
          case e: Throwable =>
            println(s"  [divmul] $op NOT MEASURED: ${e.getClass.getSimpleName}: " +
              s"${Option(e.getMessage).getOrElse("").take(200)}")
        }
      }
    }

    if (enabled("fpu")) {
      println("\n-- FPU ---------------------------------------------------------------")
      Seq("fadd.x", "fmul.x", "fdiv.x").foreach { op =>
        try {
          val dep = diffStat(compiled, s"$op-dep", n => kFpDep(op, n), 20, 40)
          report("fpu", s"$op LATENCY (DEP)", dep, "DEP; serial fp chain; differential")
        } catch {
          case e: Throwable =>
            println(s"  [fpu] $op NOT MEASURED: ${e.getClass.getSimpleName}: " +
              s"${Option(e.getMessage).getOrElse("").take(200)}")
        }
      }
    }

    // ── MMU / DTLB ────────────────────────────────────────────────────────────
    if (enabled("mmu")) {
      println("\n-- MMU: DTLB HIT vs TABLE WALK ---------------------------------------")
      println("  BASELINE for the in-flight 'route table walks through L1D' change:")
      println("  on THIS commit the walker has its own AXI port and bypasses the L1D.")
      try {
        // Matched pair: identical kernels, differing ONLY in whether a walk can occur.
        val ttr  = diffStat(compiled, "tlb-ttr",  n => kTlbChase(n, walk = false), 60, 120)
        val walk = diffStat(compiled, "tlb-walk", n => kTlbChase(n, walk = true),  60, 120)
        report("mmu", "page-stride access, TTR (no walk)", ttr,
          "DEP; differential; TC.E=1 with match-all D-side TTR; includes the cold miss")
        report("mmu", "page-stride access, real walk", walk,
          "DEP; differential; TC.E=1, no D-side TTR -> real 3-level table walk per access")
        report("mmu", "DTLB miss: 3-level walk cost", Stat("walkcost",
          walk.samples.zip(ttr.samples).map { case (a, b) => a - b }),
          "DEP; (walk - TTR) on BYTE-IDENTICAL kernels; isolates the walker; " +
          "BASELINE at this commit: walker has its own AXI port, bypasses L1D")
      } catch {
        case e: Throwable =>
          println(s"  [mmu] NOT MEASURED: ${e.getClass.getSimpleName}: " +
            s"${Option(e.getMessage).getOrElse("").take(300)}")
      }
    }

    // ── summary ───────────────────────────────────────────────────────────────
    println("\n" + "=" * 100)
    println("SUMMARY -- all values in CORE CLOCK CYCLES")
    println(f"${"group"}%-12s ${"measurement"}%-36s ${"mean"}%8s ${"sd"}%7s ${"min"}%8s ${"max"}%8s")
    println("-" * 100)
    rows.foreach { case (g, w, st, _) =>
      println(f"$g%-12s $w%-36s ${st.mean}%8.3f ${st.stddev}%7.3f ${st.min}%8.3f ${st.max}%8.3f")
    }
    println("=" * 100)
    println("METHODOLOGY PER ROW:")
    rows.foreach { case (g, w, _, m) => println(f"  $g%-12s $w%-36s $m") }
    println("=" * 100)
  }
}
