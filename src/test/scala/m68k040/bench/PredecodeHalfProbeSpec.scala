package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._
import scala.language.reflectiveCalls

/** ICACHE_PREDECODE_WORDS=8 characterisation: runs each kernel on a 16-wide and an
  * 8-wide shipping build (one compile each) and reports cycles plus the install-dwell
  * census (dwell length = PREDECODE cycles; 2 is the baseline schedule, 3/4 is the
  * eight-wide fallback that re-classifies a lower half the early AXI-cycle classifier
  * missed). `PDH_TRACE=<kernel>` prints a per-cycle I-cache install trace for that kernel.
  * `PDH_ONLY=a,b` restricts kernels. `PDH_ARMS=16,8` selects builds. */
class PredecodeHalfProbeSpec extends CoreBenchHarness {

  /** I-cache thrash: `blocks` 2-instruction blocks chained by bra.w in a fixed random
    * order, `stride` bytes apart (stride 320 = 5 lines, so the chain covers every set),
    * each at a random even offset inside its slot (so targets land anywhere in a line,
    * either beat half). Non-sequential, so the next-line prefetcher cannot cover it. */
  def kThrash(name: String, blocks: Int, stride: Int, passes: Int, seed: Int): Kernel = {
    val rnd = new scala.util.Random(seed)
    val order = rnd.shuffle((1 until blocks).toList)
    val chain = 0 :: order                       // chain position -> slot
    val nextOf = chain.zip(chain.tail :+ -1).toMap   // slot -> next slot (-1 = last)
    val off = (0 until blocks).map(_ => 2 * rnd.nextInt((stride - 32) / 2))
    val sb = new StringBuilder
    sb.append(s"moveq #$passes,%d7\nmoveq #0,%d0\nbra.l .Ls0\n")
    for (s <- 0 until blocks) {
      sb.append(s".org ${0x100 + s * stride + off(s)}\n.Ls$s:\n")
      val n = nextOf(s)
      if (n >= 0) sb.append(s"addq.l #1,%d0\nbra.l .Ls$n\n")
      else sb.append("addq.l #1,%d0\nsubq.l #1,%d7\nbne.l .Ls0\n.Lend: bra.s .Lend\n")
    }
    val retired = 3 + passes * (2 * blocks + 1)
    Kernel(name, sb.toString, retired, warmupInstrs = 2 * blocks + 4)
  }

  /** Slow-path cost probe: a loop whose body has `nIdx` indexed-EA instructions (brief
    * PC- and An-indexed lea) among plain ALU ops; under CPU_PREDECODE_CUTS=idx each of them
    * is delivered ambiguous and framed by the live classifier. */
  def kIdxLoop(iters: Int): Kernel = {
    val setup = Seq("move.l #" + iters + ",%d7", "moveq #1,%d1", "moveq #0,%d0", "lea 0x100,%a0")
    val body = ".Lix: lea (8,%pc,%d1.w),%a1 ; add.l %d1,%d0 ; lea (2,%a0,%d1.l),%a2 ; add.l %d1,%d2 ; " +
      "lea (4,%a0,%d1.w*2),%a3 ; add.l %d1,%d3 ; subq.l #1,%d7 ; bne.s .Lix"
    Kernel("idx-loop", (setup :+ body).mkString(" ; "), setup.size + iters * 8)
  }

  /** Sequential code footprint `kb` KiB (> the 16 KiB I-cache): straight-line 1-word ALU
    * ops, looped `passes` times. Every line is an I-cache miss each pass; the next-line
    * prefetcher covers most of them, so this measures install THROUGHPUT. */
  def kSeq(kb: Int, passes: Int): Kernel = {
    val n = kb * 512
    val body = (0 until n).map(i => if (i % 2 == 0) "add.l %d1,%d0" else "add.l %d1,%d2")
    val src = (Seq(s"moveq #$passes,%d7", "moveq #1,%d1", ".Lsq:") ++ body ++
      Seq("subq.l #1,%d7", "bne.l .Lsq", ".Lsqe: bra.s .Lsqe")).mkString("\n")
    Kernel(s"seq-${kb}k", src, 2 + passes * (n + 2), warmupInstrs = n + 4)
  }

  test("ICACHE_PREDECODE_WORDS=8 characterisation", VerilatorTest) {
    val all = Seq(kIdxLoop(300), kSeq(32, 3), kHotLoop, kBranchy, kCallReturn, kDhrystone(copyback = true),
      kThrash("thrash-512x320", 512, 320, 6, 7),
      kThrash("thrash-512x448", 512, 448, 6, 11),
      kThrash("thrash-1024x192", 1024, 192, 4, 13))
    val kernels = sys.env.get("PDH_ONLY") match {
      case Some(s) => val n = s.split(',').toSet; all.filter(k => n.contains(k.name))
      case None => all
    }
    val trace = sys.env.getOrElse("PDH_TRACE", "")
    val arms = sys.env.getOrElse("PDH_ARMS", "16,8").split(',').map(_.toInt).toSeq
    val simCfg = M68kSim().withVerilator.addSimulatorFlag("--x-initial 0")
    val res = scala.collection.mutable.LinkedHashMap.empty[(String, Int), String]
    for (w <- arms) {
      val compiled = simCfg.compile(new FullCoreDut(shippingProfile = true, icachePredecodeWords = w))
      for (k <- kernels) {
        var lazyPend = 0L; var lazyHold = 0L
        val hist = Array.fill(8)(0); var demand = 0; var pf = 0; var demandExtra = 0; var pfExtra = 0
        dutProbe = { dut =>
          val cd = dut.clockDomain
          val ic = dut.icache.logic
          var cyc = 0L; var dwell = 0; var isPf = false; var prevAct = false
          cd.onSamplings {
            cyc += 1
            if (ic.lazy4) {
              if (ic.lazyPendS1.toBoolean && ic.s0Valid.toBoolean) lazyPend += 1
              if (ic.refillDonePend.toBoolean) lazyHold += 1
            }
            val act = ic.predActive.toBoolean
            if (act) {
              if (!prevAct) { dwell = 0; isPf = ic.predIsPf.toBoolean }
              dwell += 1
            } else if (prevAct) {
              hist(math.min(dwell, 7)) += 1
              if (isPf) { pf += 1; pfExtra += dwell - 2 } else { demand += 1; demandExtra += dwell - 2 }
            }
            if (k.name == trace && (act || prevAct)) {
              val idx = ic.installIdx.toInt
              val ep = if (w == 8) {
                val e = ic.earlyPred
                s" loV=${e.loValid(idx).toBoolean} hiV=${e.hiValid(idx).toBoolean} fbU=${e.fallbackUpper.toBoolean}"
              } else ""
              println(f"[pdh-trace w=$w] cyc=$cyc%6d act=$act idx=$idx pf=${ic.predIsPf.toBoolean} " +
                s"beat=${ic.commitBeat.toInt} done=${ic.predBeatDone.toBoolean}$ep " +
                s"mshrBeat=${(0 until ic.mshrBeat.length).map(i => if (ic.mshrBeat(i).toBoolean) 1 else 0).mkString}")
            }
            prevAct = act
          }
        }
        val r = runKernel(compiled, k)
        dutProbe = null
        val line = f"cycles=${r.windowCycles}%7d retired=${r.retiredInstrs}%7d demandInstalls=$demand%5d " +
          f"pfInstalls=$pf%5d demandExtraCyc=$demandExtra%5d pfExtraCyc=$pfExtra%5d dwellHist=${hist.mkString("/")} lazyPendRsp=$lazyPend lazyRefillHold=$lazyHold"
        res((k.name, w)) = line
        println(s"[pdh] w=$w ${k.name} $line")
      }
    }
    println("==== PREDECODE_HALF summary ====")
    for (((n, w), l) <- res) println(f"[pdh-sum] $n%-18s w=$w%2d $l")
  }
}
