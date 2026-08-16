package m68k040.execute

/** Exact BigInt floatx80 reference, mirroring the SoftFloat in tools/musashi/musashi/
  * softfloat/softfloat.c that Decision 1 makes the bit-exact oracle. Values are the 80-bit
  * pattern as a BigInt: bit79 sign, bits78..64 exponent, bits63..0 explicit-integer-bit
  * mantissa. Rounding modes use the FPCR/SoftFloat encoding 0=RN 1=RZ 2=RM 3=RP.
  *
  * The arithmetic ops are built from EXACT RATIONAL arithmetic, deliberately a different
  * construction from the RTL's (which mirrors SoftFloat's shift/subtract structure), so
  * agreement between them is real evidence rather than a shared-bug tautology. Only
  * `roundPack` -- the final round/pack/substitute step -- is a transcription, because that
  * step IS the specification.
  *
  * `sqrt` is included in that claim as of 2026-08-16: it used to replicate the RTL's own
  * formulation (clz pre-normalise, `E = ee - 0x3FFF` parity split, `sig << (65 or 66)`,
  * `zExp = floor(E/2) + 0x3FFF`) and differ only in how the integer square root was computed,
  * which made the randomised FSQRT sweep a tautology for exactly the exponent/pre-normalise
  * logic it looked like it was covering. It is now a fixed-window rational scaling; see the
  * comment on `sqrt` itself. */
object FpRefModel {
  val M64 = (BigInt(1) << 64) - 1
  val M63 = (BigInt(1) << 63) - 1
  val DefaultNan = (BigInt(0xFFFF) << 64) | M64

  def sign(v: BigInt): Int = ((v >> 79) & 1).toInt
  def exp (v: BigInt): Int = ((v >> 64) & 0x7FFF).toInt
  def sig (v: BigInt): BigInt = v & M64
  def pack(s: Int, e: Int, m: BigInt): BigInt =
    (BigInt(s & 1) << 79) | (BigInt(e & 0x7FFF) << 64) | (m & M64)
  def isNan(v: BigInt)  = exp(v) == 0x7FFF && (sig(v) & M63) != 0
  def isInf(v: BigInt)  = exp(v) == 0x7FFF && (sig(v) & M63) == 0
  def isSNan(v: BigInt) = isNan(v) && ((sig(v) >> 62) & 1) == 0
  def isTrueZero(v: BigInt) = (v & ((BigInt(1) << 79) - 1)) == 0

  def propagateNan(a: BigInt, b: BigInt): BigInt = {
    val q = BigInt("C000000000000000", 16)
    val aq = (a & ~M64) | (sig(a) | q)
    val bq = (b & ~M64) | (sig(b) | q)
    if (isNan(a)) { if (isSNan(a) && isNan(b)) bq else aq } else bq
  }

  /** SoftFloat roundAndPackFloatx80, precision80 path (softfloat.c:598-670), including
    * Decision 10's OVFL substitution table and the UNFL denormalise-then-round arm.
    * Returns (packed80, ovfl, unfl, inex). */
  def roundPack(s: Int, e0: Int, sig0: BigInt, rnd0: Boolean, stk0: Boolean,
                rm: Int): (BigInt, Boolean, Boolean, Boolean) = {
    var e = e0; var m = sig0 & M64; var r = rnd0; var k = stk0
    def incrOf(rr: Boolean, ss: Boolean): Boolean = rm match {
      case 0 => rr
      case 1 => false
      case 2 => s == 1 && (rr || ss)
      case _ => s == 0 && (rr || ss)
    }
    val incr0 = incrOf(r, k)
    val ovfl = e > 0x7FFE || (e == 0x7FFE && m == M64 && incr0)
    if (ovfl) {
      val toMax = rm == 1 || (s == 1 && rm == 3) || (s == 0 && rm == 2)
      return (if (toMax) pack(s, 0x7FFE, M64) else pack(s, 0x7FFF, BigInt(1) << 63),
              true, false, true)
    }
    var unfl = false
    if (e <= 0) {
      val c = math.min(1 - e, 66)
      val ext = (m << 2) | (if (r) BigInt(2) else BigInt(0)) | (if (k) BigInt(1) else BigInt(0))
      val shifted = ext >> c
      val lost = (ext & ((BigInt(1) << c) - 1)) != 0
      // tininess AFTER rounding (float_tininess_after_rounding)
      val isTiny = e < 0 || !incr0 || m != M64
      m = (shifted >> 2) & M64
      r = ((shifted >> 1) & 1) == 1
      k = ((shifted & 1) == 1) || lost
      e = 0
      if (isTiny && (r || k)) unfl = true
    }
    val inex = r || k
    val incr = incrOf(r, k)
    if (incr) {
      m += 1
      if (m > M64) { m = BigInt(1) << 63; e += 1 }
      else {
        if (rm == 0 && !k) m &= ~BigInt(1)
        if (e == 0 && ((m >> 63) & 1) == 1) e = 1
      }
    } else if (m == 0) e = 0
    (pack(s, e, m), false, unfl, inex)
  }

  /** Real value of a non-special floatx80 as an exact rational (num, den). */
  private def rat(v: BigInt): (BigInt, BigInt) = {
    val e = if (exp(v) == 0) 1 else exp(v)
    val sh = e - 16383 - 63
    if (sh >= 0) (sig(v) << sh, BigInt(1)) else (sig(v), BigInt(1) << (-sh))
  }

  /** Round an exact rational magnitude to extended, then pack. */
  def roundRational(s: Int, num: BigInt, den: BigInt, rm: Int)
      : (BigInt, Boolean, Boolean, Boolean) = {
    if (num == 0) return (pack(s, 0, 0), false, false, false)
    // scale so that 2^63 <= num/den < 2^64
    var k = 0
    var n = num; var d = den
    while (n / d < (BigInt(1) << 63)) { n <<= 1; k += 1 }
    while (n / d >= (BigInt(1) << 64)) { d <<= 1; k -= 1 }
    val q = n / d
    val rem = n - q * d
    val e = 16383 + 63 - k
    val half = 2 * rem
    val rnd = half >= d
    val stk = if (rnd) (half != d) else (rem != 0)
    roundPack(s, e, q, rnd, stk, rm)
  }

  def add(a: BigInt, b: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean) =
    addsub(a, b, rm, sub = false)
  def sub(a: BigInt, b: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean) =
    addsub(a, b, rm, sub = true)

  def addsub(a: BigInt, b: BigInt, rm: Int, sub: Boolean)
      : (BigInt, Boolean, Boolean, Boolean) = {
    if (isNan(a) || isNan(b)) return (propagateNan(a, b), false, false, false)
    val bs = sign(b) ^ (if (sub) 1 else 0)
    val effAdd = sign(a) == bs
    if (isInf(a) && isInf(b) && !effAdd) return (DefaultNan, false, false, false)
    if (isInf(a)) return (a, false, false, false)
    if (isInf(b)) return (pack(if (effAdd) sign(a) else 1 - sign(a), 0x7FFF, BigInt(1) << 63),
                          false, false, false)
    val (an, ad) = rat(a); val (bn, bd) = rat(b)
    val sA = if (sign(a) == 1) -an else an
    val sB = if (bs == 1) -bn else bn
    val num = sA * bd + sB * ad
    val den = ad * bd
    // exact cancellation: SoftFloat's subFloatx80Sigs returns pack(rmode == RM, 0, 0)
    if (num == 0) return (pack(if (rm == 2) 1 else 0, 0, 0), false, false, false)
    roundRational(if (num < 0) 1 else 0, num.abs, den, rm)
  }

  def mul(a: BigInt, b: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean) = {
    val s = sign(a) ^ sign(b)
    if (isNan(a) || isNan(b)) return (propagateNan(a, b), false, false, false)
    if ((isInf(a) && isTrueZero(b)) || (isInf(b) && isTrueZero(a)))
      return (DefaultNan, false, false, false)
    if (isInf(a) || isInf(b)) return (pack(s, 0x7FFF, BigInt(1) << 63), false, false, false)
    if (isTrueZero(a) || isTrueZero(b)) return (pack(s, 0, 0), false, false, false)
    val (an, ad) = rat(a); val (bn, bd) = rat(b)
    roundRational(s, an * bn, ad * bd, rm)
  }

  def div(a: BigInt, b: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean) = {
    val s = sign(a) ^ sign(b)
    if (isNan(a) || isNan(b)) return (propagateNan(a, b), false, false, false)
    if (isInf(a) && isInf(b)) return (DefaultNan, false, false, false)
    if (isInf(a)) return (pack(s, 0x7FFF, BigInt(1) << 63), false, false, false)
    if (isInf(b)) return (pack(s, 0, 0), false, false, false)
    if (isTrueZero(b)) {
      if (isTrueZero(a)) return (DefaultNan, false, false, false)
      return (pack(s, 0x7FFF, BigInt(1) << 63), false, false, false)
    }
    if (isTrueZero(a)) return (pack(s, 0, 0), false, false, false)
    val (an, ad) = rat(a); val (bn, bd) = rat(b)
    roundRational(s, an * bd, ad * bn, rm)
  }

  def sqrt(a: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean) = {
    if (isNan(a)) return (propagateNan(a, a), false, false, false)
    if (isInf(a) && sign(a) == 0) return (a, false, false, false)
    if (isInf(a)) return (DefaultNan, false, false, false)
    if (sign(a) == 1 && isTrueZero(a)) return (a, false, false, false)
    if (sign(a) == 1) return (DefaultNan, false, false, false)
    if (isTrueZero(a)) return (pack(0, 0, 0), false, false, false)

    // Exact value of the operand, V = M * 2^E, from the same raw extraction `rat` uses: the
    // stored significand is taken AS IT SITS (no clz pre-normalise, no normalised
    // significand/exponent pair reconstructed), and a subnormal is simply an operand whose
    // biased exponent reads as 1.
    val M = sig(a)
    val E = (if (exp(a) == 0) 1 else exp(a)) - 16383 - 63

    // Scale V into the FIXED window [2^130, 2^132) by a power of FOUR -- the direct analogue
    // of `roundRational` scaling into [2^63, 2^64) by a power of two:
    //     V == W * 4^g,   W = M << s an exact integer,   2^130 <= W < 2^132.
    // `s` puts M's top bit at bit 131 and then backs off by one iff that would leave an odd
    // residual power of two (only a power of FOUR has an exact square root, 4^g -> 2^g). The
    // adjustment is a property of the chosen window, not a case split on the operand's
    // exponent, and it applies identically to normals and subnormals. M < 2^64 guarantees
    // s >= 67, so the shift is always LEFT and W is exact -- nothing is ever discarded.
    var s = 132 - M.bitLength
    if ((((E - s) % 2) + 2) % 2 != 0) s -= 1
    val W = M << s
    val g = (E - s) / 2
    require(W >= (BigInt(1) << 130) && W < (BigInt(1) << 132), s"sqrt window escape: $W")

    // floor(sqrt(W)) is 66 bits wide for EVERY W in the window, unconditionally, so there are
    // always exactly two bits below the 64-bit significand and the exact remainder says
    // whether anything at all lies below those. The integer square root is delegated to
    // java.math.BigInteger.sqrt so that not even the inner loop is hand-written here.
    val Q   = BigInt(W.bigInteger.sqrt())
    val rem = W - Q * Q
    val extra = Q.bitLength - 64
    val m   = Q >> extra
    val rnd = ((Q >> (extra - 1)) & 1) == 1
    val stk = (Q & ((BigInt(1) << (extra - 1)) - 1)) != 0 || rem != 0

    // sqrt(V) = sqrt(W) * 2^g = (Q + f) * 2^g with 0 <= f < 1, so the result is
    // m * 2^(g + extra): the exponent falls out of the SCALING plus where Q's bits landed,
    // never out of a parity branch on the operand exponent.
    roundPack(0, g + extra + 16383 + 63, m, rnd, stk, rm)
  }

  /** SoftFloat floatx80_round_to_int (softfloat.c:3082-3145) -- the REAL FINT semantics.
    * Musashi's m68kfpu.c does NOT use this (Divergence Register D1). */
  def roundToInt(a: BigInt, rm: Int): BigInt = {
    val e = exp(a); val s = sign(a)
    if (e >= 0x403E) return if (isNan(a)) propagateNan(a, a) else a
    if (e < 0x3FFF) {
      if (e == 0 && (sig(a) & M63) == 0) return a
      rm match {
        case 0 => if (e == 0x3FFE && (sig(a) & M63) != 0)
                    pack(s, 0x3FFF, BigInt(1) << 63) else pack(s, 0, 0)
        case 2 => if (s == 1) pack(1, 0x3FFF, BigInt(1) << 63) else pack(0, 0, 0)
        case 3 => if (s == 1) pack(1, 0, 0) else pack(0, 0x3FFF, BigInt(1) << 63)
        case _ => pack(s, 0, 0)
      }
    } else {
      val lastBit = BigInt(1) << (0x403E - e)
      val roundM  = lastBit - 1
      var lo = sig(a)
      if (rm == 0) { lo = (lo + (lastBit >> 1)) & M64; if ((lo & roundM) == 0) lo &= ~lastBit }
      else if (rm != 1) { if ((s == 1) != (rm == 3)) lo = (lo + roundM) & M64 }
      lo &= ~roundM
      if (lo == 0) pack(s, e + 1, BigInt(1) << 63) else pack(s, e, lo)
    }
  }

  /** True iff floatx80_round_to_int would raise INEX2 on this operand. */
  def roundToIntInexact(a: BigInt, rm: Int): Boolean = {
    val e = exp(a)
    if (e >= 0x403E) false
    else if (e < 0x3FFF) !(e == 0 && (sig(a) & M63) == 0)
    else sig(roundToInt(a, rm)) != sig(a)
  }

  // ══════════════════════════════════════════════════════════════════════════════
  // Task 14b: the NARROWING conversions FMOVE FPn,<ea> needs (extended -> Long/Word/
  // Byte integer, Single, Double). Unlike the arithmetic above, these ARE direct
  // transcriptions of SoftFloat -- `floatx80_to_int32` / `floatx80_to_float32` /
  // `floatx80_to_float64` plus their `roundAndPackInt32` / `roundAndPackFloat32` /
  // `roundAndPackFloat64` tails ARE the specification (Musashi's `fmove_reg_mem` calls
  // exactly these, so they are also the lock-step oracle for the stored VALUE).
  // Citations: softfloat.c:67-101 (roundAndPackInt32), :245-294 (roundAndPackFloat32),
  // :403-452 (roundAndPackFloat64), :2845-2859 / :2998 / :3026 (the three floatx80_to_*
  // entry points), softfloat-macros shift64RightJamming, softfloat-specialize:249-337
  // (commonNaNToFloat32/64).
  // ══════════════════════════════════════════════════════════════════════════════

  /** softfloat-macros `shift64RightJamming`. */
  def shr64Jam(a: BigInt, count: Int): BigInt = {
    require(count >= 0)
    if (count == 0) a & M64
    else if (count < 64) ((a & M64) >> count) | (if (((a & M64) & ((BigInt(1) << count) - 1)) != 0) BigInt(1) else BigInt(0))
    else if ((a & M64) != 0) BigInt(1) else BigInt(0)
  }

  /** softfloat-macros `shift32RightJamming`. */
  def shr32Jam(a: BigInt, count: Int): BigInt = {
    val m32 = (BigInt(1) << 32) - 1
    require(count >= 0)
    if (count == 0) a & m32
    else if (count < 32) ((a & m32) >> count) | (if (((a & m32) & ((BigInt(1) << count) - 1)) != 0) BigInt(1) else BigInt(0))
    else if ((a & m32) != 0) BigInt(1) else BigInt(0)
  }

  /** The SoftFloat `roundIncrement` selection shared by roundAndPackInt32/Float32/Float64:
    * RN -> half; RZ -> 0; RM/RP -> all-ones when rounding AWAY from zero for this sign,
    * 0 when rounding toward zero. `half` is 0x40 / 0x40 / 0x200 and `allOnes` 0x7F / 0x7F /
    * 0x3FF for int32 / float32 / float64 respectively. */
  private def roundIncrement(zSign: Int, rm: Int, half: Int, allOnes: Int): Int = rm match {
    case 0 => half
    case 1 => 0
    case 2 => if (zSign == 1) allOnes else 0    // RM (down): away from zero only when negative
    case _ => if (zSign == 1) 0 else allOnes    // RP (up)  : away from zero only when positive
  }

  /** softfloat.c:67-101 `roundAndPackInt32`. Returns (the 32-bit result PATTERN, invalid,
    * inexact). On invalid the saturated value is returned and inexact is NOT set (SoftFloat
    * returns before reaching the inexact test). */
  def roundAndPackInt32(zSign: Int, absZ0: BigInt, rm: Int): (BigInt, Boolean, Boolean) = {
    val M32 = (BigInt(1) << 32) - 1
    val incr = roundIncrement(zSign, rm, 0x40, 0x7F)
    val roundBits = (absZ0 & 0x7F).toInt
    var absZ = (absZ0 + incr) >> 7
    if (((roundBits ^ 0x40) == 0) && rm == 0) absZ = absZ & ~BigInt(1)
    val overflow =
      (absZ >> 32) != 0 ||
      (if (zSign == 1) (absZ & M32) > (BigInt(1) << 31) else (absZ & M32) > ((BigInt(1) << 31) - 1))
    if (overflow) return (if (zSign == 1) BigInt(1) << 31 else (BigInt(1) << 31) - 1, true, false)
    val z = if (zSign == 1) (-(absZ & M32)) & M32 else absZ & M32
    (z, false, roundBits != 0)
  }

  /** softfloat.c `floatx80_to_int32`. Returns (32-bit pattern, invalid, inexact). */
  def toInt32(a: BigInt, rm: Int): (BigInt, Boolean, Boolean) = {
    val aExp = exp(a)
    val aSig = sig(a)
    // A NaN is forced POSITIVE before rounding, so it saturates to 0x7FFFFFFF.
    val zSign = if (aExp == 0x7FFF && (aSig & M63) != 0) 0 else sign(a)
    var sc = 0x4037 - aExp
    if (sc <= 0) sc = 1
    roundAndPackInt32(zSign, shr64Jam(aSig, sc), rm)
  }

  /** softfloat.c:245-294 `roundAndPackFloat32`. Returns (32-bit pattern, ovfl, unfl, inex).
    * `float_detect_tininess` is `after_rounding` for this build (softfloat-specialize:43,
    * the same constant `FpRoundPack` already relies on). */
  def roundAndPackFloat32(zSign: Int, zExp0: Int, zSig0: BigInt, rm: Int)
      : (BigInt, Boolean, Boolean, Boolean) = {
    val incr = roundIncrement(zSign, rm, 0x40, 0x7F)
    var zExp = zExp0
    var zSig = zSig0
    var roundBits = (zSig & 0x7F).toInt
    var ovfl = false; var unfl = false
    if (zExp > 0xFD || (zExp == 0xFD && ((zSig + incr) & (BigInt(1) << 31)) != 0)) {
      val inf = (BigInt(zSign) << 31) | (BigInt(0xFF) << 23)
      return (if (incr == 0) inf - 1 else inf, true, false, true)
    }
    if (zExp < 0) {
      val isTiny = zExp < -1 || (zSig + incr) < (BigInt(1) << 31)
      zSig = shr32Jam(zSig, -zExp)
      zExp = 0
      roundBits = (zSig & 0x7F).toInt
      if (isTiny && roundBits != 0) unfl = true
    }
    val inex = roundBits != 0
    var z = (zSig + incr) >> 7
    if (((roundBits ^ 0x40) == 0) && rm == 0) z = z & ~BigInt(1)
    if (z == 0) zExp = 0
    ((BigInt(zSign) << 31) + (BigInt(zExp) << 23) + z, ovfl, unfl, inex)
  }

  /** softfloat.c:403-452 `roundAndPackFloat64`. Returns (64-bit pattern, ovfl, unfl, inex). */
  def roundAndPackFloat64(zSign: Int, zExp0: Int, zSig0: BigInt, rm: Int)
      : (BigInt, Boolean, Boolean, Boolean) = {
    val incr = roundIncrement(zSign, rm, 0x200, 0x3FF)
    var zExp = zExp0
    var zSig = zSig0
    var roundBits = (zSig & 0x3FF).toInt
    var unfl = false
    if (zExp > 0x7FD || (zExp == 0x7FD && ((zSig + incr) & (BigInt(1) << 63)) != 0)) {
      val inf = (BigInt(zSign) << 63) | (BigInt(0x7FF) << 52)
      return (if (incr == 0) inf - 1 else inf, true, false, true)
    }
    if (zExp < 0) {
      val isTiny = zExp < -1 || (zSig + incr) < (BigInt(1) << 63)
      zSig = shr64Jam(zSig, -zExp)
      zExp = 0
      roundBits = (zSig & 0x3FF).toInt
      if (isTiny && roundBits != 0) unfl = true
    }
    val inex = roundBits != 0
    var z = (zSig + incr) >> 10
    if (((roundBits ^ 0x200) == 0) && rm == 0) z = z & ~BigInt(1)
    if (z == 0) zExp = 0
    ((BigInt(zSign) << 63) + (BigInt(zExp) << 52) + z, false, unfl, inex)
  }

  /** softfloat.c `floatx80_to_float32`. Returns (32-bit pattern, snan, ovfl, unfl, inex). */
  def toFloat32(a: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean, Boolean) = {
    val aSign = sign(a); val aExp = exp(a); val aSig = sig(a)
    if (aExp == 0x7FFF) {
      if ((aSig & M63) != 0) {
        // commonNaNToFloat32 (softfloat-specialize): sign<<31 | 0x7FC00000 | ((aSig<<1)>>41)
        val hi = (aSig << 1) & M64
        return ((BigInt(aSign) << 31) | BigInt(0x7FC00000L) | (hi >> 41), isSNan(a), false, false, false)
      }
      return ((BigInt(aSign) << 31) | (BigInt(0xFF) << 23), false, false, false, false)
    }
    val zSig = shr64Jam(aSig, 33)
    val zExp = if (aExp != 0 || zSig != 0) aExp - 0x3F81 else aExp
    val (v, o, u, i) = roundAndPackFloat32(aSign, zExp, zSig, rm)
    (v, false, o, u, i)
  }

  /** softfloat.c `floatx80_to_float64`. Returns (64-bit pattern, snan, ovfl, unfl, inex). */
  def toFloat64(a: BigInt, rm: Int): (BigInt, Boolean, Boolean, Boolean, Boolean) = {
    val aSign = sign(a); val aExp = exp(a); val aSig = sig(a)
    if (aExp == 0x7FFF) {
      if ((aSig & M63) != 0) {
        // commonNaNToFloat64: sign<<63 | 0x7FF8000000000000 | ((aSig<<1)>>12)
        val hi = (aSig << 1) & M64
        return ((BigInt(aSign) << 63) | BigInt("7FF8000000000000", 16) | (hi >> 12),
                isSNan(a), false, false, false)
      }
      return ((BigInt(aSign) << 63) | (BigInt(0x7FF) << 52), false, false, false, false)
    }
    val zSig = shr64Jam(aSig, 1)
    val zExp = if (aExp != 0 || aSig != 0) aExp - 0x3C01 else aExp
    val (v, o, u, i) = roundAndPackFloat64(aSign, zExp, zSig, rm)
    (v, false, o, u, i)
  }

  /** `store_extended_float80` (m68kfpu.c:80-85) as three 32-bit chunks at ea+0/+4/+8:
    * {sign+exp, 0x0000}, mantissa[63:32], mantissa[31:0]. The reserved word at ea+2 is
    * written as ZERO (it is SKIPPED on the load side, load_extended_float80). */
  def extChunks(a: BigInt): Vector[BigInt] = Vector(
    ((a >> 64) & 0xFFFF) << 16,
    (a >> 32) & ((BigInt(1) << 32) - 1),
    a & ((BigInt(1) << 32) - 1))

  /** Musashi SET_CONDITION_CODES in the internal {NaN,I,Z,N} bit order. */
  def fpcc(v: BigInt): Int = {
    val low63nz = (sig(v) & M63) != 0
    var f = 0
    if (sign(v) == 1) f |= 1
    if (exp(v) == 0 && !low63nz) f |= 2
    if (exp(v) == 0x7FFF && !low63nz) f |= 4
    if (isNan(v)) f |= 8
    f
  }

  /** Musashi FCMP (m68kfpu.c:1517-1545): the explicit infinity table, else classify the
    * rounded difference. */
  def fcmp(d: BigInt, s: BigInt, rm: Int): Int = {
    def inf(v: BigInt) = if (!isInf(v)) 0 else if (sign(v) == 1) -1 else 1
    val di = inf(d); val si = inf(s)
    if (!isNan(d) && !isNan(s) && (di != 0 || si != 0)) {
      if (si < 0) { if (di < 0) 3 else 0 }
      else if (si > 0) { if (di > 0) 2 else 1 }
      else { if (di < 0) 1 else 0 }
    } else fpcc(sub(d, s, rm)._1)
  }
}

/** Scala-side classification helpers the sweep needs (`Fp80` is hardware-only). */
object Fp80RefHelpers {
  def isUnnormal(v: BigInt): Boolean = {
    val e = ((v >> 64) & 0x7FFF).toInt
    e != 0 && e != 0x7FFF && ((v >> 63) & 1) == 0
  }
  /** exp == 0 with the explicit integer bit SET -- a pseudo-denormal. SoftFloat's
    * raw-exponent operand ordering makes exact cancellation against a normal with exp 1
    * diverge here (design-spec Divergence Register D5), so the sweep excludes them. */
  def isPseudoDenorm(v: BigInt): Boolean =
    ((v >> 64) & 0x7FFF) == 0 && ((v >> 63) & 1) == 1
}
