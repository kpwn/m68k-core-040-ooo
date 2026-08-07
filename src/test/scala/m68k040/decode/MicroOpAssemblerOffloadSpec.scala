package m68k040.decode

import m68k040.VerilatorTest
import m68k040.frontend.{Aligner, DecodePacket}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

/** FMax closure slice 3 — the BINDING correctness invariant for the slot-1 dst-EA
  * chained-shift collapse.
  *
  * `MicroOpAssembler.computeOffload(pkt)` (the 1-arg, slot-0 overload) builds slot-1's
  * `dstEa` by dynamically indexing `pkt.words` — an array Aligner ALREADY built by
  * dynamically indexing the raw IBuf head window at `L0 + j`. That mux-of-mux was the
  * 2nd-worst post-route path after slice 1. The new 3-arg overload collapses it into ONE
  * dynamic select on `L0 + dstShift`, straight off the raw window.
  *
  * This spec drives BOTH overloads from the SAME stimulus — the DUT rebuilds slot-1's
  * `DecodePacket` from `(rawWords, l0, l1)` byte-for-byte the way `Aligner.align` does —
  * and proves the outputs agree. It is a pure-refactor proof, not a "does it decode
  * correctly" check (though the directed cases also assert the decoded disp/od/klass
  * against the hand-derived encoding, so a mutually-consistent-but-wrong pair fails too).
  *
  * NOTE on the one place the identity is NOT unconditional (documented, deliberate):
  * Aligner ZERO-FILLS `pkt.words(j)` for j >= L1, whereas `rawWords(L0+j)` there holds the
  * NEXT instruction's real words. `EaDecoder` only ever consumes words that belong to the
  * dst EA's own extension words, which live strictly inside the L1 region for any MOVE —
  * and MOVE is the only op whose `spec.dst.kind === EADST`, i.e. the only op that
  * architecturally consumes `dstEa` at all. The single dstEa-derived predicate that is NOT
  * MOVE-gated (`dstEaOk`, klass in {DATAREG, ADDRREG}) depends on the EA MODE FIELD only,
  * never on `words`. Tests 2/4 below assert exactly that: `spec`/`srcEa`/`dstEaOk` are
  * identical for EVERY opword and EVERY (L0, L1, window) — bounded blast radius proven,
  * not assumed.
  */
class MicroOpAssemblerOffloadSpec extends AnyFunSuite {

  val W = Aligner.WINDOW   // 10

  class Dut extends Component {
    val rawWords = in Vec(Bits(16 bits), W)
    val l0       = in UInt(4 bits)
    val l1       = in UInt(4 bits)

    // Rebuild slot1's DecodePacket EXACTLY as Aligner.align's slot1Ok arm does
    // (Aligner.scala's `val idx = (L0 +^ U(i)).resize(4)` / `when(U(i) < L1)` loop).
    val pkt = DecodePacket()
    for (i <- 0 until W) {
      val idx = (l0 +^ U(i, 4 bits)).resize(4)
      when(U(i, 4 bits) < l1) { pkt.words(i) := rawWords(idx) }
        .otherwise           { pkt.words(i) := B(0, 16 bits) }
    }
    pkt.valid      := True
    pkt.pc         := U(0x1000, 32 bits)
    pkt.wordCount  := l1
    pkt.simple     := True
    pkt.lenWords   := l1
    pkt.complex    := False
    pkt.fault      := False
    pkt.faultAtc   := False
    pkt.predTaken  := False; pkt.predTarget := U(0, 32 bits)
    pkt.phtValid   := False; pkt.phtIndex   := U(0, 11 bits)

    val oldOff = MicroOpAssembler.computeOffload(pkt)                 // chained double shift
    val newOff = MicroOpAssembler.computeOffload(pkt, rawWords, l0)   // collapsed single shift

    val dstMatch  = out(Bool()); dstMatch  := oldOff.dstEa.asBits === newOff.dstEa.asBits
    val srcMatch  = out(Bool()); srcMatch  := oldOff.srcEa.asBits === newOff.srcEa.asBits
    val specMatch = out(Bool()); specMatch := oldOff.spec.asBits  === newOff.spec.asBits

    private def eaOk(e: EaSpec): Bool = (e.klass === EaClass.DATAREG) || (e.klass === EaClass.ADDRREG)
    val dstEaOkMatch = out(Bool()); dstEaOkMatch := eaOk(oldOff.dstEa) === eaOk(newOff.dstEa)

    // Decoded-value observation (independent "is it actually right" check).
    val dstDisp      = out(Bits(32 bits)); dstDisp := newOff.dstEa.disp
    val dstOd        = out(Bits(32 bits)); dstOd   := newOff.dstEa.od
    val dstBase      = out(UInt(5 bits));  dstBase := newOff.dstEa.base
    val dstBaseValid = out(Bool());        dstBaseValid := newOff.dstEa.baseValid
    val dstIndexReg  = out(UInt(5 bits));  dstIndexReg := newOff.dstEa.indexReg
    val dstIsMemSim  = out(Bool());        dstIsMemSim := newOff.dstEa.klass === EaClass.MEMSIMPLE
    val dstIsMemInd  = out(Bool());        dstIsMemInd := newOff.dstEa.klass === EaClass.MEMINDIRECT
  }

  // ── Scala-side stimulus model ────────────────────────────────────────────────
  def sext16(w: Int): BigInt = BigInt(w.toShort.toInt) & BigInt("FFFFFFFF", 16)
  def sext8(b: Int): BigInt  = BigInt(b.toByte.toInt) & BigInt("FFFFFFFF", 16)

  /** A MOVE scenario: line (1=.B / 3=.W / 2=.L), src EA (mode/reg + its own ext words),
    * dst EA (mode/reg + its own ext words). `L1 = 1 + srcExt + dstExt` — the exact framing
    * PredecodeWord would produce, and the exact `dstShift` `srcEaWordCount` computes. */
  case class Scen(name: String, line: Int,
                  srcMode: Int, srcReg: Int, srcExt: Seq[Int],
                  dstMode: Int, dstReg: Int, dstExt: Seq[Int]) {
    val opword: Int = (line << 12) | (dstReg << 9) | (dstMode << 6) | (srcMode << 3) | srcReg
    val words: Seq[Int] = opword +: (srcExt ++ dstExt)
    val l1: Int = words.length
  }

  val FILL_PRE  = Seq(0xDEAD, 0xBEEF, 0xCAFE, 0xF00D, 0x0BAD, 0xFACE, 0x1234, 0x5A5A, 0xA5A5, 0x7E7E)
  val FILL_POST = Seq(0xFFFF, 0x8001, 0x4242, 0x9999, 0x3C3C, 0xC3C3, 0x0F0F, 0xF0F0, 0x2B2B, 0xD00D)

  /** Place `s`'s words at offset l0 in a WINDOW-wide raw window, everything else
    * DISTINCTIVE non-zero filler (so an under- or over-shift is caught, and so the
    * beyond-L1 region is never accidentally zero). */
  def window(s: Scen, l0: Int): Seq[Int] = {
    val out = Array.fill(W)(0)
    for (i <- 0 until W) out(i) = if (i < l0) FILL_PRE(i % FILL_PRE.length) else FILL_POST(i % FILL_POST.length)
    for ((w, i) <- s.words.zipWithIndex) out(l0 + i) = w
    out.toSeq
  }

  def poke(dut: Dut, ws: Seq[Int], l0: Int, l1: Int): Unit = {
    for (i <- 0 until W) dut.rawWords(i) #= ws(i) & 0xFFFF
    dut.l0 #= l0
    dut.l1 #= l1
    sleep(1)
  }

  // ── The directed scenarios ───────────────────────────────────────────────────
  // Every one has dstShift > 0 (the source EA carries its OWN extension words), which is
  // the ONLY case where the two shifts actually compound.
  val scenarios: Seq[(Scen, Dut => Unit)] = Seq(
    // A: MOVE.L (d16,A1),(d16,A2)   src 1 ext word -> dstShift=1, dst ext at pkt.words(2)
    (Scen("MOVE.L (d16,A1),(d16,A2)", 2, 5, 1, Seq(0x1234), 5, 2, Seq(0x8001)),
     (d: Dut) => {
       assert(d.dstIsMemSim.toBoolean, "A: dst should be MEMSIMPLE")
       assert(d.dstBaseValid.toBoolean && d.dstBase.toInt == 8 + 2, "A: dst base should be A2")
       assert(d.dstDisp.toBigInt == sext16(0x8001), s"A: dst disp = ${d.dstDisp.toBigInt.toString(16)}")
     }),
    // B: MOVE.L #imm32,(xxx).L      src 2 ext words -> dstShift=2, dst ext at words(3),(4)
    (Scen("MOVE.L #imm32,(xxx).L", 2, 7, 4, Seq(0xAAAA, 0xBBBB), 7, 1, Seq(0x0012, 0x3456)),
     (d: Dut) => {
       assert(d.dstIsMemSim.toBoolean, "B: dst should be MEMSIMPLE")
       assert(!d.dstBaseValid.toBoolean, "B: abs.L has no base register")
       assert(d.dstDisp.toBigInt == BigInt(0x00123456), s"B: dst disp = ${d.dstDisp.toBigInt.toString(16)}")
     }),
    // C: MOVE.W (xxx).W,(d8,A3,D1.W)  src 1 ext word -> dstShift=1, brief dst ext at words(2)
    (Scen("MOVE.W (xxx).W,(d8,A3,D1.W)", 3, 7, 0, Seq(0x7000), 6, 3, Seq(0x1020)),
     (d: Dut) => {
       assert(d.dstIsMemSim.toBoolean, "C: brief-format dst should be MEMSIMPLE")
       assert(d.dstBaseValid.toBoolean && d.dstBase.toInt == 8 + 3, "C: dst base should be A3")
       assert(d.dstIndexReg.toInt == 1, "C: dst index should be D1")
       assert(d.dstDisp.toBigInt == sext8(0x20), s"C: dst d8 = ${d.dstDisp.toBigInt.toString(16)}")
     }),
    // D: the deep one — FULL-format bd.L source (3 ext words -> dstShift=3) with a
    //    FULL-format MEMORY-INDIRECT dst carrying bd.W + od.L (4 ext words). Exercises
    //    miEaWordCountG's bd/od mux chain on the src side AND EaDecoder's fOdWordAt
    //    DYNAMIC od read (shifted indices 3 and 4) on the dst side — i.e. the deepest
    //    reachable combination of the two shifts. L1 = 8.
    (Scen("MOVE.L (bd.L,A1,D0.W),([bd.W,A3,D0.W],od.L)", 2,
          6, 1, Seq(0x0130, 0x1111, 0x2222),
          6, 3, Seq(0x0123, 0x3333, 0x4444, 0x5555)),
     (d: Dut) => {
       assert(d.dstIsMemInd.toBoolean, "D: dst should be MEMINDIRECT")
       assert(d.dstBaseValid.toBoolean && d.dstBase.toInt == 8 + 3, "D: dst base should be A3")
       assert(d.dstDisp.toBigInt == sext16(0x3333), s"D: dst bd = ${d.dstDisp.toBigInt.toString(16)}")
       assert(d.dstOd.toBigInt == BigInt(0x44445555L), s"D: dst od = ${d.dstOd.toBigInt.toString(16)}")
     })
  )

  // One compile, all tests (the project's shared-DUT convention — see ExecuteLockStepSpec).
  lazy val compiled = SimConfig.withVerilator.compile(new Dut)

  test("slice3: collapsed slot-1 dst-EA read is bit-identical to the chained read (directed, L0 swept)", VerilatorTest) {
    compiled.doSim { dut =>
      var checked = 0
      for ((s, verify) <- scenarios) {
        // L0 sweep: every placement Aligner can legally produce (L0 + L1 <= WINDOW).
        for (l0 <- 0 to (W - s.l1)) {
          poke(dut, window(s, l0), l0, s.l1)
          assert(dut.specMatch.toBoolean, s"${s.name} @L0=$l0: spec differs (must be untouched)")
          assert(dut.srcMatch.toBoolean,  s"${s.name} @L0=$l0: srcEa differs (must be untouched)")
          assert(dut.dstMatch.toBoolean,  s"${s.name} @L0=$l0: dstEa NOT bit-identical -- the collapse is wrong")
          verify(dut)
          checked += 1
        }
      }
      assert(checked >= 20, s"expected a real sweep, only $checked points")
      println(s"[slice3] directed dst-EA identity: $checked (scenario, L0) points, all bit-identical")
    }
  }

  test("slice3: randomized MOVE sweep -- dstEa bit-identical for random ext-word data and L0", VerilatorTest) {
    compiled.doSim { dut =>
      val rnd = new Random(0x5115ce3)
      // EA shapes with a FIXED, data-independent extension-word count, so the Scala-side
      // L1 is exactly the framing PredecodeWord/srcEaWordCount agree on.
      //   (mode, reg, extCount, extMask) -- extMask forces the format bits that fix the count.
      case class Shape(mode: Int, reg: Int, n: Int, fixed: Seq[(Int, Int, Int)])
      // fixed = (index into this EA's ext words, andMask, orMask)
      val shapes = Seq(
        Shape(2, 1, 0, Nil),                                  // (A1)
        Shape(0, 4, 0, Nil),                                  // D4
        Shape(5, 2, 1, Nil),                                  // (d16,A2)
        Shape(7, 0, 1, Nil),                                  // (xxx).W
        Shape(7, 1, 2, Nil),                                  // (xxx).L
        // The masks pin ONLY the format bits that fix the extension-word COUNT
        // (bit8 brief/full, BD-SIZE[5:4], I/IS[2:0] -> od presence/size); everything else
        // (index reg/scale, BS, IS, the displacement payloads) stays random.
        Shape(6, 3, 1, Seq((0, 0xFEFF, 0x0000))),             // (d8,A3,Xn) brief  (bit8=0)
        Shape(6, 5, 3, Seq((0, 0xFEC8, 0x0130))),             // full: bd.L, no od (bit8=1, bdsz=11, iis=000)
        Shape(6, 2, 4, Seq((0, 0xFEC8, 0x0123)))              // full mem-ind: bd.W + od.L (iis=011)
      )
      def mkExt(sh: Shape): Seq[Int] = {
        val base = Array.fill(sh.n)(rnd.nextInt(0x10000))
        for ((i, andM, orM) <- sh.fixed) base(i) = (base(i) & andM) | orM
        base.toSeq
      }
      var checked = 0
      for (_ <- 0 until 600) {
        val ss = shapes(rnd.nextInt(shapes.length))
        val ds = shapes(rnd.nextInt(shapes.length))
        // .L only for the shapes whose count is size-independent here (#imm excluded above).
        val s = Scen("rnd", 2, ss.mode, ss.reg, mkExt(ss), ds.mode, ds.reg, mkExt(ds))
        if (s.l1 <= W) {
          val l0 = rnd.nextInt(W - s.l1 + 1)
          val ws = Array.fill(W)(rnd.nextInt(0x10000))
          for ((w, i) <- s.words.zipWithIndex) ws(l0 + i) = w
          poke(dut, ws.toSeq, l0, s.l1)
          assert(dut.specMatch.toBoolean, s"rnd op=${s.opword.toHexString} L0=$l0: spec differs")
          assert(dut.srcMatch.toBoolean,  s"rnd op=${s.opword.toHexString} L0=$l0: srcEa differs")
          assert(dut.dstMatch.toBoolean,
            s"rnd op=${s.opword.toHexString} L0=$l0 L1=${s.l1} words=${ws.map(_.toHexString).mkString(",")}: dstEa NOT identical")
          checked += 1
        }
      }
      assert(checked > 400, s"only $checked random MOVE points exercised")
      println(s"[slice3] randomized MOVE dst-EA identity: $checked points, all bit-identical")
    }
  }

  test("slice3: spec / srcEa / dstEaOk identical for EVERY opword, L0, L1 and window content", VerilatorTest) {
    // Blast-radius proof: outside MOVE the two overloads may differ ONLY in dstEa bits that
    // nothing consumes. `spec` and `srcEa` (untouched by the change) and `dstEaOk` (the one
    // non-MOVE-gated dstEa predicate, a pure function of the EA mode field) must agree
    // unconditionally -- including for random garbage opwords with arbitrary framing.
    compiled.doSim { dut =>
      val rnd = new Random(0xA11ce)
      var moveLineDiff = 0
      var otherDiff    = 0
      for (_ <- 0 until 4000) {
        val ws = Array.fill(W)(rnd.nextInt(0x10000))
        val l0 = rnd.nextInt(W)
        val l1 = 1 + rnd.nextInt(W - l0)
        poke(dut, ws.toSeq, l0, l1)
        assert(dut.specMatch.toBoolean,     s"spec differs @op=${ws(l0).toHexString} L0=$l0 L1=$l1")
        assert(dut.srcMatch.toBoolean,      s"srcEa differs @op=${ws(l0).toHexString} L0=$l0 L1=$l1")
        assert(dut.dstEaOkMatch.toBoolean,  s"dstEaOk differs @op=${ws(l0).toHexString} L0=$l0 L1=$l1")
        if (!dut.dstMatch.toBoolean) {
          if (((ws(l0) >> 12) & 0xF) match { case 1 | 2 | 3 => true; case _ => false }) moveLineDiff += 1
          else otherDiff += 1
        }
      }
      // A MOVE-line opword CAN show a dstEa difference here only because this test feeds a
      // RANDOM L1 that need not match the instruction's real framing (Aligner never does
      // that); the correctly-framed MOVE cases are covered by the two tests above.
      println(s"[slice3] unconditional spec/srcEa/dstEaOk identity over 4000 random points OK " +
              s"(dstEa raw-bit diffs seen in provably-unread beyond-L1 region: " +
              s"$otherDiff non-MOVE-line, $moveLineDiff MOVE-line-with-mismatched-L1)")
    }
  }
}
