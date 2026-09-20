package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}

/** Baseline for address-verified PRF forwarding. One chain measures recurrence
  * latency; multiple independent chains expose sustainable pair throughput.
  * No PRF-forwarding implementation or performance improvement is assumed. */
class StoreLoadDependencyBenchSpec extends CoreBenchHarness {
  private val iterations = 96
  private val warmup = 16

  private def chainKernel(chains: Int, bytes: Int = 4, offset: Int = 0): Kernel = {
    val suffix = Map(1 -> "b", 2 -> "w", 4 -> "l")(bytes)
    def initial(r: Int): Long = if(bytes == 4) 7L + r else 0x89abcd70L + r
    val setup = Seq("lea 0x6000,%a0", s"moveq #$iterations,%d7") ++
      (0 until chains).map(r => if(bytes == 4) s"moveq #${initial(r)},%d$r"
        else s"move.l #0x${initial(r).toHexString},%d$r")
    val body = (0 until chains).flatMap { r => Seq(
      s"addq.l #1,%d$r", s"move.l %d$r,${16 * r}(%a0)",
      s"move.$suffix ${16 * r + offset}(%a0),%d$r") }
    val perIteration = body.size + 2
    Kernel(s"store-load-$chains-chain-$bytes-byte-offset-$offset",
      setup.mkString(" ; ") + " ; .Lloop: " + body.mkString(" ; ") +
        " ; subq.l #1,%d7 ; bne.w .Lloop ; .Lstop: bra.s .Lstop ; .rept 64 ; nop ; .endr",
      setup.size + iterations * perIteration,
      copybackDtt = true,
      warmupInstrs = setup.size + warmup * perIteration,
      verifyRetirement = observations => {
        for(r <- 0 until chains) {
          val writes = observations.filter(o => o.archRegValid && o.archRegId == r)
            .map(_.archRegWrite & 0xffffffffL)
          val expected = scala.collection.mutable.ArrayBuffer(initial(r))
          var value = initial(r)
          val mask = (1L << (8 * bytes)) - 1
          for(i <- 1 to iterations) {
            val producer = (value + 1) & 0xffffffffL
            value = (producer & ~mask) | ((producer >>> (8 * (4 - offset - bytes))) & mask)
            expected ++= Seq(producer, value)
          }
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

  test("matched checked subword forwarding IPC", VerilatorTest) {
    val cases = for(chains <- Seq(1, 4); (bytes, offset) <-
      Seq(4 -> 0, 1 -> 0, 1 -> 1, 1 -> 2, 1 -> 3, 2 -> 0, 2 -> 1, 2 -> 2))
      yield (chains, bytes, offset)
    val results = Seq(false, true).map { enabled =>
      val compiled = M68kSim().withVerilator.compile(new FullCoreDut(sqSubwordForwarding = enabled))
      (for((chains, bytes, offset) <- cases; seed <- Seq(1, 17)) yield {
        val k = chainKernel(chains, bytes, offset)
        val r = runKernel(compiled, k, seed)
        println(f"SQ_SUBWORD enabled=$enabled seed=$seed kernel=${k.name} " +
          f"macros=${r.retiredInstrs} cycles=${r.windowCycles} IPC=${r.ipc}%.6f " +
          s"forwardCompletions=${r.sqForwardCompletions}")
        if(bytes < 4) assert(if(enabled) r.sqForwardCompletions > 0 else r.sqForwardCompletions == 0,
          s"subword forwarding path not exercised as expected: enabled=$enabled kernel=${k.name}")
        (chains, bytes, offset, seed) -> r
      }).toMap
    }
    for((key, before) <- results.head.toSeq.sortBy(_._1)) {
      val after = results(1)(key)
      assert(before.retiredInstrs == after.retiredInstrs)
      println(f"SQ_SUBWORD_GAIN case=$key baseline=${before.ipc}%.6f candidate=${after.ipc}%.6f " +
        f"gain=${after.ipc / before.ipc - 1}%.6f")
    }
  }
}
