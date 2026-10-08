package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._

/** Directed full-core gate for `CPU_LS_P3_LAUNCH` (Fable review 4.4), on the SHIPPING
  * plugin set (`FullCoreDut(shippingProfile = true)`, so the env flags of the build apply).
  *
  * One loop mixes, every iteration, the four store-queue verdict cases a P3 launch must
  * respect, plus a data-dependent (LFSR) branch whose mispredicts flush loads that were
  * launched from P3:
  *   1. `move.l d7,(a0)` then `move.l (a0),d0`   -- exact forward; the store leaves P3 on
  *                                                  the edge the load enters it.
  *   2. `move.w d7,8(a0)` then `move.l 8(a0),d1` -- partial overlap: stall until drain.
  *   3. `move.l (a1),d2`, a1 = a0 + 4 KiB         -- page-offset alias of store 1: the
  *                                                  line filter's false positive -> P4.
  *   4. `move.l 0x40(a0),d3`                      -- unrelated line, SQ NOT empty: P3.
  *   5. LFSR branch, then `move.l 0x60(a0),d4`    -- loads behind a mispredict are
  *                                                  launched from P3 and then flushed.
  * Every load result is summed into d6; the final checksum and every per-iteration d0/d1
  * value are checked against a model. The RTL tripwire (a P3 launch implies a clean real
  * SQ verdict, `LsEuPlugin`) is a FAILURE assertion live on every cycle.
  * Runs MMU-off (writethrough, precise stores) and copyback-DTT (fast stores). */
class P3LaunchSpec extends CoreBenchHarness {
  private val iters = 96
  private val C2 = 0x11223344L; private val C3 = 0x55667788L; private val C4 = 0x0badf00dL

  private def kernel(copyback: Boolean): Kernel = {
    val setup = Seq("lea 0x20000,%a0", "lea 0x21000,%a1", s"move.l #$iters,%d7",
                    "moveq #0,%d6")
    val body = Seq(
      "move.l %d7,(%a0)", "move.l (%a0),%d0", "add.l %d0,%d6",          // 1 forward
      "move.l (%a1),%d2", "add.l %d2,%d6",                                // 3 alias -> P4
      "move.l 0x40(%a0),%d3", "add.l %d3,%d6",                            // 4 P3 past store
      // LFSR kept in memory: the branch waits on a forwarded load, so loads on the
      // predicted path reach P3 (and launch) before a mispredict flushes them.
      "move.l 0x70(%a0),%d5", "lsr.l #1,%d5", "bcc.s .La", "eor.l #0xA3000000,%d5", "bra.s .Lb",
      ".La: or.l #0,%d5", "tst.l %d5",                                    // equal-length arms
      ".Lb: move.l %d5,0x70(%a0)", "move.l 0x60(%a0),%d4", "add.l %d4,%d6", // 5
      "move.w %d7,8(%a0)", "move.l 8(%a0),%d1", "add.l %d1,%d6",          // 2 stall (last)
      "subq.l #1,%d7", "bne.w .Lp3")
    val bodyInstrs = body.size
    val src = (setup ++ Seq(".Lp3: " + body.mkString(" ; ")) ++
      Seq("move.l %d6,%d0", ".Lend: bra.s .Lend")).mkString(" ; ")
    val expectedSum = (1 to iters).foldLeft(0L) { (acc, d7) =>
      (acc + d7 + ((d7.toLong & 0xffff) << 16) + C2 + C3 + C4) & 0xffffffffL }
    def poke(h: MemHandles, a: Long, v: Long): Unit =
      for (b <- 0 until 4) h.dmem.pokeByte(a + b, ((v >> (24 - 8 * b)) & 0xff).toInt)
    Kernel(s"p3launch${if (copyback) "-cb" else ""}", src,
      setup.size + iters * bodyInstrs + 1, copybackDtt = copyback, zeroFillData = true,
      prepMem = h => { poke(h, 0x20070L, 0x1234567L); poke(h, 0x21000L, C2); poke(h, 0x20040L, C3); poke(h, 0x20060L, C4) },
      verifyRetirement = obs => {
        def writes(r: Int) = obs.filter(o => o.archRegValid && o.archRegId == r).map(_.archRegWrite & 0xffffffffL)
        val d0 = writes(0); val d1 = writes(1)
        assert(d0.size == iters + 1, s"d0 written ${d0.size} times")
        for (i <- 0 until iters) {
          val d7 = (iters - i).toLong
          assert(d0(i) == d7, f"iter $i: forwarded d0=0x${d0(i)}%x expected 0x$d7%x")
          assert(d1(i) == ((d7 & 0xffff) << 16), f"iter $i: stalled d1=0x${d1(i)}%x")
        }
        assert(writes(2).forall(_ == C2) && writes(3).forall(_ == C3) && writes(4).forall(_ == C4),
          "alias / unrelated / post-branch load returned wrong data")
        assert(d0.last == expectedSum, f"checksum 0x${d0.last}%x expected 0x$expectedSum%x")
      })
  }

  test("P3 launch: forward / stall / alias / unrelated / flushed loads on the shipping core", VerilatorTest) {
    val compiled = M68kSim().withVerilator.compile(new FullCoreDut(shippingProfile = true))
    var flushAfterP3Total = 0
    for (cb <- Seq(false, true)) {
      var p3 = 0; var p3Resident = 0; var p3ThenFlush = 0; var flushes = 0
      dutProbe = d => if (d.lsEu.p3FastLoad) fork {
        val ls = d.lsEu.logic
        var lastP3 = -100L; var cyc = 0L
        while (true) {
          d.clockDomain.waitSampling(); cyc += 1
          if (d.lsEu.sqFlushSig.toBoolean) { flushes += 1; if (cyc - lastP3 <= 8) p3ThenFlush += 1 }
          if (ls.p3FastEnq.toBoolean) {
            p3 += 1; lastP3 = cyc
            if (!ls.sq.io.empty.toBoolean) p3Resident += 1
          }
        }
      }
      val r = runKernel(compiled, kernel(cb))
      println(f"[p3launch] ${r.name} cycles=${r.windowCycles} p3Launches=$p3 " +
        s"withResidentStore=$p3Resident flushes=$flushes flushWithin8OfP3=$p3ThenFlush " +
        s"lsP3Launch=${m68k040.top.ShippingCoreConfig.lsP3Launch}")
      if (m68k040.top.ShippingCoreConfig.lsP3Launch) {
        assert(p3Resident >= iters, s"P3 launch past a resident store fired only $p3Resident times")
      }
      flushAfterP3Total += p3ThenFlush
    }
    if (m68k040.top.ShippingCoreConfig.lsP3Launch)
      assert(flushAfterP3Total > 0, "no flush landed within 8 cycles of a P3 launch")
    dutProbe = null
  }
}
