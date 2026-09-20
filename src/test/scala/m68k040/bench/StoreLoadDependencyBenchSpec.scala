package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** Baseline for address-verified PRF forwarding. One chain measures recurrence
  * latency; multiple independent chains expose sustainable pair throughput.
  * No PRF-forwarding implementation or performance improvement is assumed. */
class StoreLoadDependencyBenchSpec extends CoreBenchHarness {
  private val iterations = 96
  private val warmup = 16

  private def chainKernel(chains: Int): Kernel = {
    val setup = Seq("lea 0x6000,%a0", s"moveq #$iterations,%d7") ++
      (0 until chains).map(r => s"moveq #${7 + r},%d$r")
    val body = (0 until chains).flatMap { r => Seq(
      s"addq.l #1,%d$r", s"move.l %d$r,${16 * r}(%a0)",
      s"move.l ${16 * r}(%a0),%d$r") }
    val perIteration = body.size + 2
    Kernel(s"store-load-$chains-chain",
      setup.mkString(" ; ") + " ; .Lloop: " + body.mkString(" ; ") +
        " ; subq.l #1,%d7 ; bne.w .Lloop ; .Lstop: bra.s .Lstop ; .rept 64 ; nop ; .endr",
      setup.size + iterations * perIteration,
      copybackDtt = true,
      warmupInstrs = setup.size + warmup * perIteration,
      verifyRetirement = observations => {
        for(r <- 0 until chains) {
          val writes = observations.filter(o => o.archRegValid && o.archRegId == r)
            .map(_.archRegWrite & 0xffffffffL)
          val expected = Seq(7L + r) ++ (1 to iterations).flatMap(i => Seq.fill(2)(7L + r + i))
          assert(writes == expected,
            s"$chains-chain D$r producer/reload stream differs: $writes expected $expected")
        }
      })
  }

  test("checked warm-window store-load latency and independent-chain throughput", VerilatorTest) {
    val compiled = M68kSim().withVerilator.compile(new FullCoreDut)
    for(chains <- Seq(1, 2, 4); seed <- Seq(1, 17)) {
      val k = chainKernel(chains)
      val r = runKernel(compiled, k, seed)
      val pairs = (iterations - warmup) * chains
      assert(r.retiredInstrs == k.retiredInstrs - k.warmupInstrs)
      println(f"STORE_LOAD_BASELINE chains=$chains seed=$seed pairs=$pairs " +
        f"macros=${r.retiredInstrs} cycles=${r.windowCycles} IPC=${r.ipc}%.6f " +
        f"pairsPerCycle=${pairs.toDouble / r.windowCycles}%.6f " +
        f"cyclesPerPair=${r.windowCycles.toDouble / pairs}%.6f memory=$memLabel")
    }
  }
}
