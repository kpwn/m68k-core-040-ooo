package m68k040.lockstep

import org.scalatest.Assertions.fail

/** Bit-exact 80-bit FP comparison for lock-step tests.
  *
  * DELIBERATELY OUTSIDE LockStep.compare/CommitObservation.  Both are Long-based
  * end to end, so an 80-bit extended value does not fit.  The established project
  * precedent for spot-checking a register class outside the generic comparator is
  * direct BigInt signal access (ExecuteLockStepSpec.scala's USP-untouched
  * assertion). These helpers are that precedent, packaged. */
object FpCompare {
  val Width  = 80
  val Mask   = (BigInt(1) << Width) - 1

  // NOTE: the plan text's original `f"...%16s...%020s"` does not compile -- Scala's
  // f-interpolator rejects the '0' flag on an %s conversion ("Illegal flag '0'"),
  // and a blanket `.replace(' ', '0')` over the whole string would have also
  // clobbered the literal space in " (raw". Reimplemented with explicit zero-padding
  // instead; same sign:exp:sig(raw) diagnostic format, only used in failure messages.
  private def hexPad(b: BigInt, digits: Int): String = {
    val s = b.toString(16)
    ("0" * math.max(0, digits - s.length)) + s
  }

  def fmt(v: BigInt): String = {
    val x    = v & Mask
    val sign = ((x >> 79) & 1).toInt
    val exp  = (x >> 64) & 0x7fff
    val sig  = x & ((BigInt(1) << 64) - 1)
    s"$sign:${hexPad(exp, 4)}:${hexPad(sig, 16)} (raw 0x${hexPad(x, 20)})"
  }

  def assertFpEqual(dut: BigInt, oracle: BigInt, what: String): Unit = {
    val d = dut & Mask
    val o = oracle & Mask
    if (d != o) {
      val diff = d ^ o
      fail(s"$what: FP value diverged (bit-exact compare)\n" +
           s"  dut    = ${fmt(d)}\n" +
           s"  oracle = ${fmt(o)}\n" +
           s"  xor    = 0x${diff.toString(16)}" +
           (if (((diff >> 64) & 0x7fff) != 0) "  [exponent differs]" else "") +
           (if ((diff & ((BigInt(1) << 64) - 1)) != 0) "  [significand differs]" else "") +
           (if (((diff >> 79) & 1) != 0) "  [sign differs]" else ""))
    }
  }

  def assertFpBits(actual: BigInt, expectedHex: String, what: String): Unit =
    assertFpEqual(actual, BigInt(expectedHex, 16), s"$what (anchor 0x$expectedHex)")

  def fp80(high: Int, low: BigInt): BigInt =
    ((BigInt(high) & 0xffff) << 64) | (low & ((BigInt(1) << 64) - 1))
}
