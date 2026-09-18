package m68k040.frontend

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** THE straddle-soundness property, exhaustively.
  *
  * `IcachePlugin.classifyBeat` bakes a length for every word of a refilled beat. For the
  * last words of a 64-byte line the lookahead runs past the line end, so classify is
  * called with the out-of-line words ZERO-FILLED and their validity flags FALSE. That
  * baked verdict is what the whole front end consumes; the ONLY thing that protects it
  * from being a wrong guess is `ambiguousLine`, which makes `Aligner` discard it and
  * re-resolve live (`p0LiveReg`) or refuse slot-1 packing.
  *
  * So the load-bearing invariant is:
  *
  *   for every opword, every extension-word content, and every prefix k of KNOWN
  *   lookahead words --
  *     classify(op, <first k words real>, <rest zero>, valid = first k).simple
  *       && !.ambiguousLine
  *   IMPLIES
  *     that verdict equals classify(op, <all words real>, valid = all).{simple,lenWords}
  *
  * i.e. a verdict that does NOT declare itself ambiguous must not depend on a word it
  * declared unknown. A violation is a silently wrong instruction length baked into the
  * I-cache that nothing downstream re-resolves -- the exact shape of an instruction-stream
  * desynchronisation ("crashes in a different place every run").
  *
  * Only PREFIX validity is reachable: `classifyBeat` derives every flag from one
  * `nextValid`, and `FetchAlignPlugin`'s live reclassify from `availEffPrev >= n`.
  *
  * The comparison is computed in HARDWARE (one `bad` bit) so the sweep can afford
  * 65536 opwords x 2048 extension-word patterns.
  */
class PredecodeStraddleSoundnessSpec extends AnyFunSuite {
  class Dut extends Component {
    val op   = in Bits (16 bits)
    val pat  = in Bits (16 bits)
    val bad  = out Bool ()
    val badK = out UInt (3 bits)

    val Z = B(0, 16 bits)
    // Reference: every lookahead word resident and real.
    val ref = PredecodeWord.classify(op, pat, pat, pat, pat, pat, pat,
      True, True, True, True, True, True)

    // k = number of lookahead words the refill could actually see (0..5).
    val flags = (0 until 6).map { k =>
      def w(i: Int): Bits = if (i < k) pat else Z
      def v(i: Int): Bool = Bool(i < k)
      val c = PredecodeWord.classify(op, w(0), w(1), w(2), w(3), w(4), w(5),
        v(0), v(1), v(2), v(3), v(4), v(5))
      // A definite (non-ambiguous) SIMPLE verdict must match the fully-resident one.
      val viol = c.simple && !c.ambiguousLine &&
                 (!ref.simple || (c.lenWords =/= ref.lenWords))
      viol
    }
    bad  := flags.reduce(_ || _)
    badK := U(0, 3 bits)
    for (k <- 5 to 0 by -1) when(flags(k)) { badK := U(k, 3 bits) }
  }

  test("EXHAUSTIVE: a non-ambiguous baked length never depends on an out-of-line word", VerilatorTest) {
    // Extension-word bits that can move a length: 0x0133 (bit8 brief/full, bits5:4 bd
    // size, bits1:0 outer-displacement size) for every EA, plus 0xFC00 (opclass ext[15:13]
    // + source specifier / register mask ext[12:10]) for the line-F cpGEN family.
    val eaBits = Seq(8, 5, 4, 1, 0)
    val fpBits = Seq(15, 14, 13, 12, 11, 10)
    def enumerate(bits: Seq[Int]): Seq[Int] =
      (0 until (1 << bits.length)).map(m => bits.zipWithIndex.map {
        case (b, i) => if ((m >> i & 1) == 1) 1 << b else 0
      }.sum)
    val pats = (for (a <- enumerate(eaBits); b <- enumerate(fpBits)) yield a | b).distinct.sorted
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val fails = scala.collection.mutable.ArrayBuffer[String]()
      var checked = 0L
      var op = 0
      while (op < 65536) {
        dut.op #= op
        var i = 0
        while (i < pats.length) {
          dut.pat #= pats(i)
          sleep(1)
          checked += 1
          if (dut.bad.toBoolean && fails.size < 40)
            fails += f"op=0x$op%04x pat=0x${pats(i)}%04x k=${dut.badK.toInt}"
          i += 1
        }
        op += 1
      }
      println(s"[straddle] swept 65536 opwords x ${pats.length} ext patterns x 6 validity " +
        s"prefixes = $checked configurations")
      assert(fails.isEmpty,
        s"${fails.size}+ non-ambiguous verdicts depend on an unresident word:\n" +
          fails.mkString("\n"))
    }
  }
}
