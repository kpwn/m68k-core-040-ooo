package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.fpu._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

/** Task 14b: standalone validation of `FpNarrowPack`, the extended -> {Long/Word/Byte
  * integer, Single, Double, Extended} narrowing converter that FMOVE `FPn,<ea>` (opclass
  * 011) stores through.
  *
  * ORACLE. `FpRefModel.toInt32` / `.toFloat32` / `.toFloat64` / `.extChunks`, which ARE
  * direct transcriptions of the SoftFloat functions Musashi's own `fmove_reg_mem` calls
  * (`floatx80_to_int32`, `floatx80_to_float32`, `floatx80_to_float64`,
  * `store_extended_float80`). Unlike the arithmetic ops -- where FpRefModel is deliberately
  * an INDEPENDENT construction so agreement is evidence -- these narrowing paths have no
  * second construction available: the SoftFloat routine IS the specification and Musashi
  * IS the lock-step oracle for the stored value. So this file guards against a
  * shared-transcription bug the only way that is actually available: every directed vector
  * is checked TWICE, against a hand-computed literal AND against FpRefModel, and the
  * reference self-check below runs with no simulator at all so a wrong literal is caught
  * before a cycle of RTL is exercised. */
class FpNarrowPackSpec extends AnyFunSuite {

  // ── format codes (ext[12:10], the DESTINATION format for opclass 011) ──────────
  val FmtL = 0 // Long integer
  val FmtS = 1 // Single
  val FmtX = 2 // Extended
  val FmtW = 4 // Word integer
  val FmtD = 5 // Double
  val FmtB = 6 // Byte integer

  def ext(e: Int, sig: BigInt, sign: Int = 0): BigInt =
    (BigInt(sign) << 79) | (BigInt(e) << 64) | sig

  val PosZero = BigInt(0)
  val NegZero = BigInt(1) << 79
  val PosInf  = ext(0x7FFF, BigInt(1) << 63)
  val NegInf  = ext(0x7FFF, BigInt(1) << 63, 1)
  val QNan    = ext(0x7FFF, (BigInt(3) << 62) | BigInt(0x1234))
  val SNan    = ext(0x7FFF, (BigInt(1) << 63) | BigInt(0x1234))
  val One     = ext(0x3FFF, BigInt(1) << 63)
  val NegOne  = ext(0x3FFF, BigInt(1) << 63, 1)
  val Two     = ext(0x4000, BigInt(1) << 63)
  val TwoP5   = ext(0x4000, BigInt("A000000000000000", 16))   // 2.5
  val ThreeP5 = ext(0x4000, BigInt("E000000000000000", 16))   // 3.5
  val Three   = ext(0x4000, BigInt("C000000000000000", 16))
  val Pow31   = ext(0x401E, BigInt(1) << 63)                  // 2^31
  val NegPow31= ext(0x401E, BigInt(1) << 63, 1)               // -2^31
  val Pow128  = ext(0x407F, BigInt(1) << 63)                  // 2^128  (float32 overflow)
  val PowM140 = ext(0x3FFF - 140, BigInt(1) << 63)            // 2^-140 (float32 subnormal)
  val Big     = ext(0x4020, BigInt(1) << 63)                  // 2^33   (int32 overflow)
  val Word1E4 = ext(0x400C, BigInt("9C40000000000000", 16))   // 10000.0
  val Big16   = ext(0x400F, BigInt("C350000000000000", 16))   // 100000.0 (word-range OPERR)

  // ════════════════════════════════════════════════════════════════════════════
  // Reference-model self-check. NO simulator: a wrong literal dies here.
  // ════════════════════════════════════════════════════════════════════════════
  test("FpRefModel narrowing conversions agree with hand-computed literals") {
    // ---- Long integer (floatx80_to_int32 + roundAndPackInt32) ----
    assert(FpRefModel.toInt32(One, 0) == (BigInt(1), false, false))
    assert(FpRefModel.toInt32(NegOne, 0) == (BigInt("FFFFFFFF", 16), false, false))
    assert(FpRefModel.toInt32(PosZero, 0) == (BigInt(0), false, false))
    assert(FpRefModel.toInt32(NegZero, 0) == (BigInt(0), false, false))
    // 2.5 -> 2 (round half to EVEN), 3.5 -> 4; both inexact.
    assert(FpRefModel.toInt32(TwoP5, 0) == (BigInt(2), false, true))
    assert(FpRefModel.toInt32(ThreeP5, 0) == (BigInt(4), false, true))
    // RZ truncates, RM floors, RP ceils.
    assert(FpRefModel.toInt32(TwoP5, 1)._1 == BigInt(2))
    assert(FpRefModel.toInt32(TwoP5, 2)._1 == BigInt(2))
    assert(FpRefModel.toInt32(TwoP5, 3)._1 == BigInt(3))
    // saturation: +2^31 does NOT fit, -2^31 DOES.
    assert(FpRefModel.toInt32(Pow31, 0) == (BigInt("7FFFFFFF", 16), true, false))
    assert(FpRefModel.toInt32(NegPow31, 0) == (BigInt("80000000", 16), false, false))
    assert(FpRefModel.toInt32(Big, 0) == (BigInt("7FFFFFFF", 16), true, false))
    // a NaN is forced POSITIVE, so it saturates HIGH regardless of its own sign bit.
    assert(FpRefModel.toInt32(QNan, 0) == (BigInt("7FFFFFFF", 16), true, false))
    assert(FpRefModel.toInt32(PosInf, 0) == (BigInt("7FFFFFFF", 16), true, false))
    assert(FpRefModel.toInt32(NegInf, 0) == (BigInt("80000000", 16), true, false))
    assert(FpRefModel.toInt32(Word1E4, 0)._1 == BigInt(10000))
    assert(FpRefModel.toInt32(Big16, 0)._1 == BigInt(100000))

    // ---- Single (floatx80_to_float32 + roundAndPackFloat32) ----
    assert(FpRefModel.toFloat32(One, 0)._1 == BigInt("3F800000", 16))
    assert(FpRefModel.toFloat32(NegOne, 0)._1 == BigInt("BF800000", 16))
    assert(FpRefModel.toFloat32(Three, 0)._1 == BigInt("40400000", 16))
    assert(FpRefModel.toFloat32(PosZero, 0)._1 == BigInt(0))
    assert(FpRefModel.toFloat32(NegZero, 0)._1 == BigInt("80000000", 16))
    assert(FpRefModel.toFloat32(PosInf, 0)._1 == BigInt("7F800000", 16))
    assert(FpRefModel.toFloat32(NegInf, 0)._1 == BigInt("FF800000", 16))
    // overflow: RN -> infinity, RZ -> the largest finite single.
    assert(FpRefModel.toFloat32(Pow128, 0) == (BigInt("7F800000", 16), false, true, false, true))
    assert(FpRefModel.toFloat32(Pow128, 1)._1 == BigInt("7F7FFFFF", 16))
    // 2^-140 is exactly representable as a single SUBNORMAL (2^-149 * 512), so the
    // subnormal arm is taken but nothing is lost -> no UNFL.
    assert(FpRefModel.toFloat32(PowM140, 0) == (BigInt("00000200", 16), false, false, false, false))
    // a signalling NaN quiets (bit 22 set) and raises SNAN.
    assert(FpRefModel.toFloat32(SNan, 0)._2, "SNaN input must raise SNAN")
    assert((FpRefModel.toFloat32(SNan, 0)._1 & BigInt("7FC00000", 16)) == BigInt("7FC00000", 16))

    // ---- Double (floatx80_to_float64 + roundAndPackFloat64) ----
    assert(FpRefModel.toFloat64(One, 0)._1 == BigInt("3FF0000000000000", 16))
    assert(FpRefModel.toFloat64(Three, 0)._1 == BigInt("4008000000000000", 16))
    assert(FpRefModel.toFloat64(NegZero, 0)._1 == (BigInt(1) << 63))
    assert(FpRefModel.toFloat64(PosInf, 0)._1 == BigInt("7FF0000000000000", 16))
    assert(FpRefModel.toFloat64(NegInf, 0)._1 == BigInt("FFF0000000000000", 16))
    // 2^-1080 underflows to a double subnormal; 2^1030 overflows to infinity.
    assert(FpRefModel.toFloat64(ext(0x3FFF + 1030, BigInt(1) << 63), 0)
             == (BigInt("7FF0000000000000", 16), false, true, false, true))

    // ---- Extended (pure bit placement, store_extended_float80) ----
    assert(FpRefModel.extChunks(One) ==
      Vector(BigInt("3FFF0000", 16), BigInt("80000000", 16), BigInt(0)))
    assert(FpRefModel.extChunks(NegOne) ==
      Vector(BigInt("BFFF0000", 16), BigInt("80000000", 16), BigInt(0)))
  }

  // ════════════════════════════════════════════════════════════════════════════
  // RTL
  // ════════════════════════════════════════════════════════════════════════════
  lazy val dut = M68kSim().withVerilator.compile(new FpNarrowPack)

  case class Got(word: BigInt, snan: Boolean, operr: Boolean, ovfl: Boolean,
                 unfl: Boolean, inex: Boolean)

  /** Drives one vector and reads the result back after exactly `FpNarrowPack.Latency`
    * clocks -- asserting the declared latency rather than trusting it, the same convention
    * `FpuCoreSpec.runFixed` locks in for `FpuCore.FixedLatency`. */
  def run(d: FpNarrowPack, src: BigInt, fmt: Int, chunk: Int, rm: Int): Got = {
    d.io.src #= src; d.io.fmt #= fmt; d.io.chunk #= chunk; d.io.rmode #= rm
    d.clockDomain.waitSampling(FpNarrowPack.Latency)
    sleep(1)
    Got(d.io.word.toBigInt, d.io.exc.snan.toBoolean, d.io.exc.operr.toBoolean,
        d.io.exc.ovfl.toBoolean, d.io.exc.unfl.toBoolean, d.io.exc.inex2.toBoolean)
  }

  /** The expected 32-bit chunk word + exception byte for one (value, format, chunk, rm). */
  def expect(src: BigInt, fmt: Int, chunk: Int, rm: Int): Got = fmt match {
    case f if f == FmtL || f == FmtW || f == FmtB =>
      val (v, inv, inex) = FpRefModel.toInt32(src, rm)
      // Musashi truncates (sint16)/(sint8) -- the VALUE is the int32 and the store's own
      // access size does the truncation, so the converter's word is the full int32.
      val s = if ((v >> 31) != 0) v - (BigInt(1) << 32) else v
      val narrowOperr =
        (f == FmtW && (s > 32767 || s < -32768)) || (f == FmtB && (s > 127 || s < -128))
      Got(v, false, inv || narrowOperr, false, false, inex)
    case FmtS =>
      val (v, sn, o, u, i) = FpRefModel.toFloat32(src, rm)
      Got(v, sn, false, o, u, i)
    case FmtD =>
      val (v, sn, o, u, i) = FpRefModel.toFloat64(src, rm)
      val w = if (chunk == 0) (v >> 32) & ((BigInt(1) << 32) - 1) else v & ((BigInt(1) << 32) - 1)
      // D2: the exception byte is raised by CHUNK 0's row only.
      if (chunk == 0) Got(w, sn, false, o, u, i) else Got(w, false, false, false, false, false)
    case FmtX =>
      Got(FpRefModel.extChunks(src)(chunk), false, false, false, false, false)
  }

  def check(d: FpNarrowPack, src: BigInt, fmt: Int, chunk: Int, rm: Int, tag: String): Unit = {
    val got = run(d, src, fmt, chunk, rm)
    val exp = expect(src, fmt, chunk, rm)
    assert(got == exp,
      f"$tag: src=0x$src%020X fmt=$fmt chunk=$chunk rm=$rm%n  got $got%n  exp $exp")
  }

  test("FpNarrowPack: directed per-format vectors", VerilatorTest) {
    dut.doSim("directed") { d =>
      d.clockDomain.forkStimulus(10); d.clockDomain.waitSampling(3)
      val intVecs = Seq(
        ("+1", One), ("-1", NegOne), ("+0", PosZero), ("-0", NegZero),
        ("2.5", TwoP5), ("3.5", ThreeP5), ("+inf", PosInf), ("-inf", NegInf),
        ("qnan", QNan), ("snan", SNan), ("2^31", Pow31), ("-2^31", NegPow31),
        ("2^33", Big), ("1e4", Word1E4), ("1e5", Big16))
      for (rm <- 0 to 3; (tag, v) <- intVecs; fmt <- Seq(FmtL, FmtW, FmtB))
        check(d, v, fmt, 0, rm, s"int/$tag")

      val fltVecs = Seq(
        ("+1", One), ("-1", NegOne), ("+0", PosZero), ("-0", NegZero),
        ("3", Three), ("+inf", PosInf), ("-inf", NegInf), ("qnan", QNan),
        ("snan", SNan), ("2^128", Pow128), ("2^-140", PowM140),
        ("2^1030", ext(0x3FFF + 1030, BigInt(1) << 63)),
        ("2^-1080", ext(0x3FFF - 1080, BigInt(1) << 63)),
        ("maxfin", ext(0x7FFE, (BigInt(1) << 64) - 1)),
        ("minsub", ext(0, BigInt(1))))
      for (rm <- 0 to 3; (tag, v) <- fltVecs) {
        check(d, v, FmtS, 0, rm, s"single/$tag")
        for (c <- 0 to 1) check(d, v, FmtD, c, rm, s"double/$tag")
        for (c <- 0 to 2) check(d, v, FmtX, c, rm, s"ext/$tag")
      }
    }
  }

  test("FpNarrowPack: integer range boundaries preserve words flags and one-cycle latency", VerilatorTest) {
    dut.doSim("integer-range-boundaries", 0x68040200) { d =>
      d.clockDomain.forkStimulus(10); d.clockDomain.waitSampling(3)
      // Exact dyadic inputs n/4; independent integer arithmetic computes rounding,
      // saturation and narrow-range flags rather than using FpRefModel here.
      def quarterExt(n: BigInt, sign: Int): BigInt = {
        if (n == 0) BigInt(sign) << 79
        else ext(0x3FFF + n.bitLength - 3, n << (64 - n.bitLength), sign)
      }
      val centers = Seq(BigInt(0), BigInt(128), BigInt(32768),
        BigInt(1) << 31, BigInt(1) << 32, BigInt(1) << 33)
      val quarters = centers.flatMap(c => (-8 to 8).map(i => c * 4 + i))
        .filter(_ >= 0).distinct
      var digest = 0xcbf29ce484222325L
      var vectors = 0
      for (q <- quarters; sign <- 0 to 1; rm <- 0 to 3;
           fmt <- Seq(FmtL, FmtW, FmtB); chunk <- 0 to 2) {
        val whole = q / 4; val fraction = (q % 4).toInt
        val increment = rm match {
          case 0 => fraction > 2 || (fraction == 2 && whole.testBit(0))
          case 1 => false
          case 2 => sign == 1 && fraction != 0
          case 3 => sign == 0 && fraction != 0
        }
        val magnitude = whole + (if (increment) 1 else 0)
        val signed = if (sign == 1) -magnitude else magnitude
        val min32 = -(BigInt(1) << 31); val max32 = (BigInt(1) << 31) - 1
        val overflow = signed < min32 || signed > max32
        val saturated = signed.max(min32).min(max32)
        val narrow = (fmt == FmtW && (saturated < -32768 || saturated > 32767)) ||
          (fmt == FmtB && (saturated < -128 || saturated > 127))
        val expected = Got(saturated & ((BigInt(1) << 32) - 1), false,
          chunk == 0 && (overflow || narrow), false, false,
          chunk == 0 && !overflow && fraction != 0)
        val got = run(d, quarterExt(q, sign), fmt, chunk, rm)
        assert(got == expected,
          s"quarter=$q sign=$sign rm=$rm fmt=$fmt chunk=$chunk got=$got expected=$expected")
        assert(!d.io.exc.dz.toBoolean)
        val flags = (if (got.operr) 1L else 0L) | (if (got.inex) 2L else 0L)
        digest = (digest ^ got.word.toLong) * 0x100000001b3L
        digest = (digest ^ flags) * 0x100000001b3L
        vectors += 1
      }
      println(s"FP_NARROW_RANGE_TRACE vectors=$vectors latency=${FpNarrowPack.Latency} " +
        s"digest=${java.lang.Long.toUnsignedString(digest, 16)}")
    }
  }

  test("FpNarrowPack: randomised sweep against the SoftFloat reference", VerilatorTest) {
    dut.doSim("sweep") { d =>
      d.clockDomain.forkStimulus(10); d.clockDomain.waitSampling(3)
      val rnd = new Random(0x14B)
      for (_ <- 0 until 3000) {
        val e = rnd.nextInt(0x8000)
        val sig = (BigInt(rnd.nextLong() & 0xFFFFFFFFL) << 32) | BigInt(rnd.nextLong() & 0xFFFFFFFFL)
        val s = rnd.nextInt(2)
        val v = ext(e, sig, s)
        val rm = rnd.nextInt(4)
        check(d, v, FmtL, 0, rm, "sweep")
        check(d, v, FmtW, 0, rm, "sweep")
        check(d, v, FmtB, 0, rm, "sweep")
        check(d, v, FmtS, 0, rm, "sweep")
        check(d, v, FmtD, rnd.nextInt(2), rm, "sweep")
        check(d, v, FmtX, rnd.nextInt(3), rm, "sweep")
      }
      // Extra density around the exponent boundaries that pick the overflow/subnormal arms.
      for (e <- (0x3F70 to 0x3F92) ++ (0x407C to 0x4082) ++ (0x3BF0 to 0x3C10) ++
                (0x4030 to 0x4040)) {
        for (rm <- 0 to 3; sigTag <- 0 to 3) {
          val sig = sigTag match {
            case 0 => BigInt(1) << 63
            case 1 => (BigInt(1) << 64) - 1
            case 2 => (BigInt(1) << 63) | (BigInt(1) << 32)
            case _ => (BigInt(1) << 63) | ((BigInt(1) << 33) - 1)
          }
          for (s <- 0 to 1) {
            val v = ext(e, sig, s)
            check(d, v, FmtL, 0, rm, "edge")
            check(d, v, FmtS, 0, rm, "edge")
            check(d, v, FmtD, 0, rm, "edge")
          }
        }
      }
    }
  }
}
