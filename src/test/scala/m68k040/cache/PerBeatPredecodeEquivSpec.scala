package m68k040.cache

import org.scalatest.funsuite.AnyFunSuite

/** Equivalence gate for slice I2 (per-beat predecode).
  *
  * `IcachePlugin` used to predecode a 64-byte line with a SINGLE 32-way unrolled
  * `PredecodeWord.classify` group. Slice I2 replaced that with ONE 16-instance group
  * fired twice (once per 256-bit AXI beat). The two schemes can only differ in the
  * `(w1, w2, w3, extWValid, extW2Valid, extW3Valid)` tuple presented to `classify`
  * for each absolute word index 0..31, so checking those tuples agree for all 32
  * positions is a TOTAL proof of output equivalence — no simulation, no sampling.
  *
  * *** STANDING WARNING (design doc §8.2, and this project's own memory): ***
  * `PredecodeWordSpec`'s "exhaustive 65536-opword" test does NOT actually run
  * exhaustively — it aborts at the first mismatch, so a past "it passes" claim
  * carries no information about how many cases were checked. This spec deliberately
  * does the opposite: it collects EVERY mismatch, never aborts early, and its failure
  * message reports the mismatch count against the total checked. */
class PerBeatPredecodeEquivSpec extends AnyFunSuite {

  private val WORDS_PER_LINE = 32
  /** 256-bit AXI beat / 16-bit word. Mirrors `IcachePlugin.logic.WORDS_PER_BEAT`.
    * (The ratified plan's I2 section says 8, because it assumed slice V2b had already
    * narrowed the I master to 128 bits. V2b is out of scope for the initiative I2
    * landed under, so the real geometry is 16 words/beat, 2 beats/line.) */
  private val WORDS_PER_BEAT = 16
  private val BEATS          = WORDS_PER_LINE / WORDS_PER_BEAT

  /** What the WHOLE-LINE scheme (the pre-I2 32-way unroll) presented for absolute word
    * index `i`: the source word index for each of the three lookahead slots, and
    * whether that slot was flagged valid. */
  private def wholeLine(i: Int): Seq[(Int, Boolean)] =
    (1 to 3).map(k => (i + k, i + k < WORDS_PER_LINE))

  /** What the PER-BEAT scheme presents for absolute word index `i`. Beat b = i/16,
    * local index l = i%16; lookahead words beyond the beat come from the NEXT beat's
    * low 3 words, flagged valid iff there IS a next beat. */
  private def perBeat(i: Int): Seq[(Int, Boolean)] = {
    val b       = i / WORDS_PER_BEAT
    val l       = i % WORDS_PER_BEAT
    val hasNext = b < BEATS - 1
    (1 to 3).map { k =>
      val ll = l + k
      if (ll < WORDS_PER_BEAT) (b * WORDS_PER_BEAT + ll, true)
      else ((b + 1) * WORDS_PER_BEAT + (ll - WORDS_PER_BEAT), hasNext)
    }
  }

  test("per-beat and whole-line predecode present IDENTICAL inputs for every word") {
    // EXHAUSTIVE over all 32 word positions, and it does NOT abort on the first
    // mismatch. Checks the lookahead SOURCE INDICES as well as the validity flags —
    // an off-by-one in the beat wiring shows up only in the indices.
    val mismatches = (0 until WORDS_PER_LINE).flatMap { i =>
      val a = wholeLine(i)
      val b = perBeat(i)
      if (a == b) None else Some(s"word $i: whole-line=$a per-beat=$b")
    }
    assert(mismatches.isEmpty,
      s"${mismatches.size} of $WORDS_PER_LINE word positions differ:\n" + mismatches.mkString("\n"))
  }

  test("only the final 3 words of a line are ever flagged invalid (zero NEW ambiguity)") {
    // The whole point of the beat scheme's correctness argument: the set of words that
    // see `extWValid = false` must be UNCHANGED, i.e. exactly the last 3 of the line.
    val invalidWords = (0 until WORDS_PER_LINE).filter(i => perBeat(i).exists(!_._2)).toSet
    val expected     = (WORDS_PER_LINE - 3 until WORDS_PER_LINE).toSet
    assert(invalidWords == expected,
      s"per-beat scheme flags lookahead invalid for words $invalidWords, expected exactly $expected " +
        "-- any other word means a NEW ambiguity was introduced, which slice I2 explicitly claims not to do")
  }

  test("the beat geometry the model assumes matches the RTL's own constant") {
    // Guards against this spec silently going stale if the I master is ever narrowed
    // (slice V2b) without revisiting the model above.
    val beatBits = 256
    assert(WORDS_PER_BEAT == beatBits / 16,
      s"model assumes $WORDS_PER_BEAT words/beat but a $beatBits-bit beat holds ${beatBits / 16}")
    assert(BEATS * WORDS_PER_BEAT == WORDS_PER_LINE)
  }
}
