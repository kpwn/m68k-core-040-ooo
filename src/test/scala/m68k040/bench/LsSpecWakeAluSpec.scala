package m68k040.bench

import m68k040.{M68kSim, VerilatorTest}
import spinal.core.sim._

/** Directed full-core gate for `CPU_LS_SPEC_WAKE_ALU` (week plan B2), on the SHIPPING plugin
  * set (`FullCoreDut(shippingProfile = true)`, so the env flags of the build apply).
  *
  * The mechanism: the LS EU announces a P3-admitted load one cycle before its irrevocable
  * wakeup; an ALU consumer whose only outstanding source is that load is selected on the
  * announce; if the wakeup does not follow next cycle the consumer is HELD in its ALU issue
  * register and every IQ select is frozen until the load wakes. Kernels:
  *   hit    strcmp-shaped byte loop over an L1-resident pair of strings: the confirm path
  *          (load -> cmp.b -> beq), every result checked.
  *   miss   a 64-byte stride over cold memory: every announce is an L1 MISS, so every
  *          spec-selected consumer is held across the refill; its result feeds a STORE's
  *          data and the next iteration (static ALU dependents released by the held uop's
  *          already-fired events) -- every running sum and every stored word checked.
  *   sqfwd  a store then a load of the same address (forwarded at P4, never announced),
  *          then a load of another line behind it (measured: almost never admitted at P3,
  *          since the forwarding load still holds P4) -- forwarded values checked.
  *   flush  a data-dependent branch behind a slow rotate chain guards a cold-miss load and its
  *          consumer: when the branch mispredicts, it RETIRES and flushes while the
  *          wrong-path consumer is held. Counted (flush cycles that land on a held uop).
  * Faults: an announced load that completes as a bus error releases the hold through
  * `specAbort`; that path is exercised by the PM-run ported corpus (bus-error programs),
  * not here (this harness has no read-error injection).
  *
  * Gate it twice: CPU_LS_SPEC_WAKE_ALU=1 (shipping form, probe-hit filter on: confirm path)
  * and additionally CPU_LS_SPEC_WAKE_ALU_PROBE=0 (every cold miss is announced, so the hold,
  * the freeze and flush-during-hold are exercised and REQUIRED to have happened).
  * With the flag OFF the kernels still run and every value is still checked (the
  * mechanism counters are then zero). With it ON the spec REQUIRES non-vacuity: grants
  * and confirms on `hit`, holds on `miss`, and at least one flush landing on a held uop. */
class LsSpecWakeAluSpec extends CoreBenchHarness {
  private def poke32(h: MemHandles, a: Long, v: Long): Unit =
    for (b <- 0 until 4) h.dmem.pokeByte(a + b, ((v >> (24 - 8 * b)) & 0xff).toInt)
  private def regWrites(obs: Seq[m68k040.lockstep.CommitObservation], r: Int): Seq[Long] =
    obs.filter(o => o.archRegValid && o.archRegId == r).map(_.archRegWrite & 0xffffffffL)

  // ── hit: strcmp-shaped ────────────────────────────────────────────────────────────
  private val strA = (0 until 64).map(i => (i * 37 + 11) & 0xff)
  private val strB = (0 until 64).map(i => if (i % 5 == 0) (strA(i) ^ 0x10) else strA(i))
  private def kHit(outer: Int): Kernel = {
    val setup = Seq(s"move.l #$outer,%d7", "moveq #0,%d6")
    val src = (setup ++ Seq(
      ".Lo: lea 0x20000,%a2", "lea 0x20100,%a3", "moveq #63,%d5",
      ".Li: move.b (%a2)+,%d0", "cmp.b (%a3)+,%d0", "beq.s .Le", "addq.l #1,%d6",
      ".Le: add.b %d0,%d6", "dbra %d5,.Li", "subq.l #1,%d7", "bne.s .Lo",
      "move.l %d6,%d0", ".Lend: bra.s .Lend")).mkString(" ; ")
    // Model: per inner step 5 or 6 instructions; per outer 3 + inner + 2.
    var d6 = 0L; var n = setup.size
    for (_ <- 0 until outer) {
      n += 3
      for (i <- 0 until 64) {
        n += 5
        if (strA(i) != strB(i)) { n += 1; d6 = (d6 + 1) & 0xffffffffL }
        d6 = (d6 & 0xffffff00L) | ((d6 + strA(i)) & 0xff)
      }
      n += 2
    }
    n += 1
    val expect = d6
    Kernel("specalu-hit", src, n, zeroFillData = true,
      prepMem = h => for (i <- 0 until 64) {
        h.dmem.pokeByte(0x20000L + i, strA(i)); h.dmem.pokeByte(0x20100L + i, strB(i)) },
      warmupInstrs = setup.size + 3 + 64 * 6,
      verifyRetirement = obs => {
        val d0 = regWrites(obs, 0)
        assert(d0.nonEmpty && d0.last == expect, f"hit: checksum 0x${d0.lastOption.getOrElse(-1L)}%x expected 0x$expect%x")
      })
  }

  // ── miss: cold stride, consumer feeds a store and the next iteration ──────────────
  private def missVal(i: Int): Long = (0x01020304L * (i + 1) + 0x5a5a0000L) & 0xffffffffL
  private def kMiss(iters: Int): Kernel = {
    val setup = Seq("lea 0x30000,%a0", "lea 0x90000,%a1", s"move.l #$iters,%d7", "moveq #0,%d6")
    val body = Seq("move.l (%a0),%d1", "add.l %d1,%d6", "move.l %d6,(%a1)+", "lea 64(%a0),%a0",
      "subq.l #1,%d7", "bne.s .Lm")
    // Read every stored word back (the commit stream does not surface store data here).
    val readBack = Seq("lea 0x90000,%a1", s"move.l #$iters,%d7", "moveq #0,%d4",
      ".Lr: add.l (%a1)+,%d4", "subq.l #1,%d7", "bne.s .Lr", "move.l %d4,%d0")
    val src = (setup ++ Seq(".Lm: " + body.mkString(" ; ")) ++ readBack ++ Seq(".Lend: bra.s .Lend"))
      .mkString(" ; ")
    val sums = (0 until iters).scanLeft(0L)((acc, i) => (acc + missVal(i)) & 0xffffffffL).tail
    val storeSum = sums.foldLeft(0L)((a, v) => (a + v) & 0xffffffffL)
    Kernel("specalu-miss", src, setup.size + iters * body.size + 3 + 3 * iters + 1, zeroFillData = true,
      prepMem = h => for (i <- 0 until iters) poke32(h, 0x30000L + 64L * i, missVal(i)),
      verifyRetirement = obs => {
        val d6 = regWrites(obs, 6).drop(1) // the setup moveq
        assert(d6.size == iters, s"miss: d6 written ${d6.size} times, expected $iters")
        for (i <- 0 until iters)
          assert(d6(i) == sums(i), f"miss: iter $i d6=0x${d6(i)}%x expected 0x${sums(i)}%x")
        val d0 = regWrites(obs, 0)
        assert(d0.nonEmpty && d0.last == storeSum, f"miss: stored-word sum 0x${d0.lastOption.getOrElse(-1L)}%x expected 0x$storeSum%x")
      })
  }

  // ── sqfwd: forwarded load (never announced) beside an announced one ──────────────
  private val SqC = 0x0badf00dL
  private def kSqFwd(iters: Int): Kernel = {
    val setup = Seq("lea 0x50000,%a0", s"move.l #$iters,%d7", "moveq #0,%d6")
    val body = Seq("move.l %d7,(%a0)", "move.l (%a0),%d1", "add.l %d1,%d6",
      "move.l 0x40(%a0),%d2", "add.l %d2,%d6", "eor.l %d1,%d6", "subq.l #1,%d7", "bne.s .Lq")
    val src = (setup ++ Seq(".Lq: " + body.mkString(" ; "), "move.l %d6,%d0", ".Lend: bra.s .Lend"))
      .mkString(" ; ")
    val expect = (1 to iters).reverse.foldLeft(0L) { (acc, d7) =>
      ((((acc + d7) & 0xffffffffL) + SqC) & 0xffffffffL) ^ d7.toLong }
    Kernel("specalu-sqfwd", src, setup.size + iters * body.size + 1, zeroFillData = true,
      prepMem = h => poke32(h, 0x50040L, SqC),
      verifyRetirement = obs => {
        val d1 = regWrites(obs, 1)
        assert(d1 == (1 to iters).reverse.map(_.toLong), "sqfwd: a forwarded load returned a wrong value")
        val d0 = regWrites(obs, 0)
        assert(d0.nonEmpty && d0.last == expect, f"sqfwd: checksum 0x${d0.lastOption.getOrElse(-1L)}%x expected 0x$expect%x")
      })
  }

  // ── flush: a mispredicted branch older than a held consumer retires ───────────────
  private def flushVal(i: Int): Long = (0x10203040L + 0x01000193L * i) & 0xffffffffL
  private def kFlush(iters: Int): Kernel = {
    val setup = Seq("lea 0x60000,%a0", s"move.l #$iters,%d7", "moveq #0,%d6",
      "move.l #0x1234567,%d5")
    // A chain of four dependent rotates (the slow ALU path) delays the branch.
    val body = Seq("move.l %d5,%d3", "ror.l #3,%d3", "ror.l #3,%d3", "ror.l #3,%d3", "ror.l #3,%d3",
      "lsr.l #1,%d5",
      "bcc.s .Lnx", "eor.l #0xA3000000,%d5",
      ".Lnx: btst #0,%d3", "beq.s .Lsk", "move.l (%a0),%d1", "add.l %d1,%d6",
      ".Lsk: lea 64(%a0),%a0", "subq.l #1,%d7", "bne.s .Lf")
    val src = (setup ++ Seq(".Lf: " + body.mkString(" ; "), "move.l %d6,%d0", ".Lend: bra.s .Lend"))
      .mkString(" ; ")
    var d5 = 0x1234567L; var d6 = 0L; var n = setup.size
    for (i <- 0 until iters) {
      val q = d5 >>> 12   // bit 0 of ror.l #12
      val carry = (d5 & 1) != 0
      d5 = d5 >>> 1
      n += 7
      if (carry) { d5 ^= 0xA3000000L; n += 1 }
      n += 2
      if ((q & 1) != 0) { n += 2; d6 = (d6 + flushVal(i)) & 0xffffffffL }
      n += 3
    }
    n += 1
    val expect = d6
    Kernel("specalu-flush", src, n, zeroFillData = true,
      prepMem = h => for (i <- 0 until iters) poke32(h, 0x60000L + 64L * i, flushVal(i)),
      verifyRetirement = obs => {
        val d0 = regWrites(obs, 0)
        assert(d0.nonEmpty && d0.last == expect, f"flush: checksum 0x${d0.lastOption.getOrElse(-1L)}%x expected 0x$expect%x")
      })
  }

  test("speculative ALU wakeup: confirm / miss hold / SQ forward / flush during hold", VerilatorTest) {
    val on = m68k040.top.ShippingCoreConfig.lsSpecWakeAlu
    val compiled = M68kSim().withVerilator.compile(new FullCoreDut(shippingProfile = true))
    case class Stat(var ann: Int = 0, var confirm: Int = 0, var grants: Int = 0, var holds: Int = 0,
                    var held: Int = 0, var flushOnHeld: Int = 0)
    val stats = scala.collection.mutable.LinkedHashMap.empty[String, Stat]
    for (k <- Seq(kHit(24), kMiss(384), kSqFwd(128), kFlush(256))) {
      val st = Stat(); stats(k.name) = st
      dutProbe = d => if (d.iq.logic.specAluOn) fork {
        val l = d.iq.logic; val c = l.specAluCtl; val pp = l.aluSpecPipes
        while (true) {
          d.clockDomain.waitSampling()
          if (c.statAnnounce.toBoolean) st.ann += 1
          if (c.statConfirm.toBoolean) st.confirm += 1
          for (p <- pp) {
            if (p.grant.toBoolean) st.grants += 1
            if (p.holdStart.toBoolean) st.holds += 1
          }
          val heldNow = pp.exists(_.blocked.toBoolean)
          if (heldNow) st.held += 1
          if (heldNow && d.iq.flushPort.toBoolean) st.flushOnHeld += 1
        }
      }
      val r = runKernel(compiled, k)
      println(s"[specalu-gate] ${r.name} on=$on cycles=${r.windowCycles} announce=${st.ann} " +
        s"confirm=${st.confirm} grants=${st.grants} holdStarts=${st.holds} heldCyc=${st.held} " +
        s"flushOnHeld=${st.flushOnHeld}")
    }
    dutProbe = null
    if (on) {
      assert(stats("specalu-hit").grants > 0 && stats("specalu-hit").confirm > 0,
        "hit kernel: no confirmed speculative grant")
      // The hold path is reachable on these kernels only with the probe-hit filter OFF
      // (with it ON a cold miss is never announced). Run the spec with
      // CPU_LS_SPEC_WAKE_ALU_PROBE=0 to gate the hold/flush paths.
      if (!m68k040.top.ShippingCoreConfig.lsSpecWakeAluProbe) {
        assert(stats("specalu-miss").holds > 0, "miss kernel: no failed-confirm hold")
        assert(stats.values.map(_.flushOnHeld).sum > 0, "no flush landed on a held uop in any kernel")
      }
    }
  }
}
