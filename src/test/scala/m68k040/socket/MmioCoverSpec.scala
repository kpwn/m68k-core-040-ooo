package m68k040.socket

import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "MMIO sizing", the D30 bullet: an EXHAUSTIVE, hard check over all 48
  * load cases and all 48 store cases -- "small enough to enumerate rather than sample,
  * which is what makes 'no byte outside the access is ever touched' a proved property
  * rather than a spot check."
  *
  * Deliberately UNTAGGED so `make test-fast` runs it. The integration checks (that the real
  * DcachePlugin emits exactly these transactions) are in Tasks 7 and 8 and are
  * Verilator-tagged alongside DcacheSpec; THIS is where the maths is proved. */
class MmioCoverSpec extends AnyFunSuite {

  private val sizes = Seq(("BYTE", 1), ("WORD", 2), ("LONG", 4))

  /** Tiny DUT exposing the two hardware primitives so they can be compared against the
    * Scala model over every reachable input. */
  class CoverDut extends Component {
    val p    = in  UInt (4 bits)
    val endI = in  UInt (5 bits)
    val off  = in  UInt (4 bits)
    val n    = in  UInt (3 bits)
    val strb = in  Bits (16 bits)
    val szL2 = out UInt (2 bits)
    val cEnd = out UInt (5 bits)
    val rSta = out UInt (4 bits)
    val rEnd = out UInt (5 bits)
    szL2 := MmioCover.stepLog2(p, endI)
    cEnd := MmioCover.clampedEnd(off, n)
    rSta := MmioCover.strbRunStart(strb)
    rEnd := MmioCover.strbRunEnd(strb)
  }

  // ── The model's own properties, over every reachable (off, size) ──────────────────
  test("D25/D30 model: every load case covers exactly, aligned, in at most 3 pieces") {
    var cases = 0
    for (off <- 0 until 16; (nm, n) <- sizes) {
      val end = math.min(off + n, 16)          // D25 -- the clamp
      val cov = MmioCover.model(off, end)
      cases += 1
      assert(cov.nonEmpty, s"$nm at off=$off produced no sub-transaction")
      assert(cov.length <= MmioCover.MAX_SUBS,
        s"$nm at off=$off needed ${cov.length} sub-transactions: $cov")
      // (a) each naturally aligned for its own AxSIZE
      for ((a, sz) <- cov) {
        assert(Seq(1, 2, 4).contains(sz), s"illegal sub-size $sz in $cov")
        assert(a % sz == 0, s"sub-transaction $a/$sz is not naturally aligned ($cov)")
      }
      // (b) an exact partition of [off, end): no gap, no overlap, nothing outside
      val touched = cov.flatMap { case (a, sz) => a until (a + sz) }
      assert(touched == touched.distinct, s"overlap in $cov")
      assert(touched.sorted == (off until end).toList,
        s"$nm at off=$off covered ${touched.sorted}, wanted ${(off until end).toList}")
      // and nothing leaves the containing 16-byte line -- the C1 regression
      assert(touched.forall(b => b >= 0 && b < 16),
        s"$nm at off=$off emitted a byte outside the line: $touched")
    }
    assert(cases == 48, s"expected 48 load cases, enumerated $cases")
  }

  test("D30: the three non-representable in-group shapes each cover in exactly 2 pieces") {
    // Spec section 3.4's table. These are the COMPLETE set of in-group ranges that no
    // single naturally-aligned AXI transfer covers exactly.
    assert(MmioCover.model(1, 3) == Seq((1, 1), (2, 1)), "L=2 gs=1")
    assert(MmioCover.model(0, 3) == Seq((0, 2), (2, 1)), "L=3 gs=0")
    assert(MmioCover.model(1, 4) == Seq((1, 1), (2, 2)), "L=3 gs=1")
  }

  test("D30: spec section 3.3.2's four worked examples, exactly") {
    // 0x6/WORD -> ONE sub-transaction, AxSIZE=1 at 0x6.
    assert(MmioCover.model(6, 8) == Seq((6, 2)))
    // 0x5/WORD -> TWO, AxSIZE=0 at 0x5 and 0x6. This is the case an earlier draft carried
    // as an accepted limitation and the user directed must not be.
    assert(MmioCover.model(5, 7) == Seq((5, 1), (6, 1)))
    // 0x2/LONG -> TWO, crossing the group boundary at 4.
    assert(MmioCover.model(2, 6) == Seq((2, 2), (4, 2)))
    // 0x1/LONG -> THREE. This is the worst case; nothing reaches four.
    assert(MmioCover.model(1, 5) == Seq((1, 1), (2, 2), (4, 1)))
    // 0xD/LONG slot A, AFTER the D25 clamp -> TWO, 3 bytes, no over-read.
    assert(MmioCover.model(13, 16) == Seq((13, 1), (14, 2)))
  }

  test("a naturally-aligned access still emits exactly one transaction") {
    for (off <- Seq(0, 4, 8, 12); n <- Seq(1, 2, 4)) {
      if (off % n == 0) {
        val cov = MmioCover.model(off, off + n)
        assert(cov == Seq((off, n)), s"aligned $n@$off split into $cov -- cost on the common case")
      }
    }
    for (off <- 0 until 16) assert(MmioCover.model(off, off + 1) == Seq((off, 1)))
    for (off <- 0 until 16 by 2) assert(MmioCover.model(off, off + 2) == Seq((off, 2)))
  }

  test("D25: the clamp is what keeps a cross-line access inside its own line") {
    // Spec section 3.4: off=13, size=LONG -> [13,17) unclamped -> group index 4, i.e. a
    // sub-transaction at line offset 16, in the NEXT physical page when the line is the
    // last of its page. Every cross-page access forces exactly this geometry.
    val unclamped = 13 + 4
    assert(unclamped > 16, "premise of the C1 regression case")
    val cov = MmioCover.model(13, math.min(unclamped, 16))
    assert(cov.flatMap { case (a, sz) => a until (a + sz) }.max == 15,
      s"the clamp did not hold: $cov")
  }

  // ── The hardware primitives, against the model, exhaustively ──────────────────────
  test("hardware stepLog2 matches the model at every reachable (p, end)") {
    SimConfig.compile(new CoverDut).doSim("stepLog2", seed = 1) { dut =>
      dut.strb #= 0; dut.off #= 0; dut.n #= 1
      for (off <- 0 until 16; (_, n) <- sizes) {
        val end = math.min(off + n, 16)
        var p = off
        for ((wantA, wantSz) <- MmioCover.model(off, end)) {
          assert(p == wantA, s"model desync at off=$off")
          dut.p #= p; dut.endI #= end
          sleep(1)
          val gotSz = 1 << dut.szL2.toInt
          assert(gotSz == wantSz,
            s"hw chose $gotSz at p=$p end=$end, model chose $wantSz")
          p += gotSz
        }
        assert(p == end, s"hardware walk did not terminate at end ($p vs $end)")
      }
    }
  }

  test("hardware clampedEnd implements min(off + n, 16)") {
    SimConfig.compile(new CoverDut).doSim("clamp", seed = 2) { dut =>
      dut.p #= 0; dut.endI #= 0; dut.strb #= 0
      for (off <- 0 until 16; (_, n) <- sizes) {
        dut.off #= off; dut.n #= n
        sleep(1)
        assert(dut.cEnd.toInt == math.min(off + n, 16),
          s"clampedEnd($off,$n) = ${dut.cEnd.toInt}")
      }
    }
  }

  test("D26 store side: the strobe run is the lowest set bit to the highest plus one") {
    SimConfig.compile(new CoverDut).doSim("strb-run", seed = 3) { dut =>
      dut.p #= 0; dut.endI #= 0; dut.off #= 0; dut.n #= 1
      // Exactly the 48 store cases: storeStrbA's run for every (off, size), which
      // DcacheTypes.scala:312-320 defines as bytes off .. min(off+n,16)-1.
      var cases = 0
      for (off <- 0 until 16; (nm, n) <- sizes) {
        val end = math.min(off + n, 16)
        val v = (off until end).foldLeft(BigInt(0))((a, i) => a | (BigInt(1) << i))
        dut.strb #= v
        sleep(1)
        assert(dut.rSta.toInt == off, s"$nm off=$off: run start ${dut.rSta.toInt}")
        assert(dut.rEnd.toInt == end, s"$nm off=$off: run end ${dut.rEnd.toInt}")
        // And the cover of THAT run is the same 48-case proof as the load side.
        val cov = MmioCover.model(off, end)
        assert(cov.length <= MmioCover.MAX_SUBS)
        assert(cov.flatMap { case (a, sz) => a until (a + sz) }.sorted == (off until end).toList)
        cases += 1
      }
      assert(cases == 48, s"expected 48 store cases, enumerated $cases")
      // storeStrbB's spilled remainder, the quantity `size` no longer carries
      // (StoreQueue.scala:264 forces slot B's size to LONG whatever its true extent).
      for (off <- 13 until 16; n <- Seq(4)) {
        val spill = off + n - 16
        if (spill > 0) {
          val v = (0 until spill).foldLeft(BigInt(0))((a, i) => a | (BigInt(1) << i))
          dut.strb #= v
          sleep(1)
          assert(dut.rSta.toInt == 0 && dut.rEnd.toInt == spill,
            s"slot-B run for off=$off: [${dut.rSta.toInt},${dut.rEnd.toInt}) wanted [0,$spill)")
        }
      }
    }
  }
}
