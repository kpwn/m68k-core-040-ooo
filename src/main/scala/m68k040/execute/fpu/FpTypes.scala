/*
 * Third-party notice: SoftFloat-derived portions of this file and its FPU
 * helpers are adaptations of John R. Hauser's SoftFloat Release 2b.
 * Local hardware adaptation: m68k-core-040-ooo contributors, 2026.
 * See THIRD_PARTY_NOTICES.md for scope; unrelated original code remains MIT.
 * Original notice for retained/adapted SoftFloat portions follows:
 *
 * This C source file is part of the SoftFloat IEC/IEEE Floating-point Arithmetic
 * Package, Release 2b.
 *
 * Written by John R. Hauser.  This work was made possible in part by the
 * International Computer Science Institute, located at Suite 600, 1947 Center
 * Street, Berkeley, California 94704.  Funding was partially provided by the
 * National Science Foundation under grant MIP-9311980.  The original version
 * of this code was written as part of a project to build a fixed-point vector
 * processor in collaboration with the University of California at Berkeley,
 * overseen by Profs. Nelson Morgan and John Wawrzynek.  More information
 * is available through the Web page `http://www.cs.berkeley.edu/~jhauser/
 * arithmetic/SoftFloat.html'.
 *
 * THIS SOFTWARE IS DISTRIBUTED AS IS, FOR FREE.  Although reasonable effort has
 * been made to avoid it, THIS SOFTWARE MAY CONTAIN FAULTS THAT WILL AT TIMES
 * RESULT IN INCORRECT BEHAVIOR.  USE OF THIS SOFTWARE IS RESTRICTED TO PERSONS
 * AND ORGANIZATIONS WHO CAN AND WILL TAKE FULL RESPONSIBILITY FOR ALL LOSSES,
 * COSTS, OR OTHER PROBLEMS THEY INCUR DUE TO THE SOFTWARE, AND WHO FURTHERMORE
 * EFFECTIVELY INDEMNIFY JOHN HAUSER AND THE INTERNATIONAL COMPUTER SCIENCE
 * INSTITUTE (possibly via similar legal warning) AGAINST ALL LOSSES, COSTS, OR
 * OTHER PROBLEMS INCURRED BY THEIR CUSTOMERS AND CLIENTS DUE TO THE SOFTWARE.
 *
 * Derivative works are acceptable, even for commercial purposes, so long as
 * (1) the source code for the derivative work includes prominent notice that
 * the work is derivative, and (2) the source code includes prominent notice with
 * these four paragraphs for those parts of this code that are retained.
 */

package m68k040.execute.fpu

import spinal.core._

/** The 11-op hardware-native FPU baseline (design spec Decision 2) plus FMOVECR/FTST
  * (2026-08-09 spec §1). Everything else traps to FPSP and never reaches FpuCore.
  *
  * NOTE (design spec VERIFY-3, confirmed): FMOVECR, FINT and FINTRZ are NOT hardware
  * instructions on a real MC68040 -- the Motorola FPSP's unimplemented-instruction
  * dispatch table carries real emulation routines for extension-word opcodes $00
  * (FMOVECR), $01 (FINT) and $03 (FINTRZ). Including them here is a deliberate,
  * recorded superset of real silicon, observationally equivalent to a correct FPSP.
  */
object FpOp extends SpinalEnum {
  val FADD, FSUB, FMUL, FDIV, FSQRT, FABS, FNEG, FMOVE, FCMP, FTST,
      FINT, FINTRZ, FMOVECR = newElement()
}

/** FPCR PREC (bits 7:6) as a rounding-PRECISION selector, and everything that selection
  * changes about a rounding request.
  *
  * MC68040UM 9.4.1 / M68000PRM 3.5.1-3.5.2 (both quoted below) make PREC drive TWO
  * things, not one:
  *
  *  (a) THE MANTISSA ROUNDING BOUNDARY. "Single-precision results are rounded to a 24-bit
  *      boundary; double-precision results are rounded to a 53-bit boundary; and
  *      extended-precision results are rounded to a 64-bit boundary" (PRM 3.5.2), and
  *      "All mantissa bits beyond the selected precision are zero" (UM 9.4.1). Our 64-bit
  *      significand carries an EXPLICIT integer bit, so a 24-bit single result keeps bits
  *      63..40 and zeroes 39..0 -- i.e. `lsbPos` = 64 - 24 = 40. Double keeps 63..11
  *      (64 - 53 = 11). Extended keeps everything (`lsbPos` = 0).
  *
  *  (b) RANGE CONTROL. "Range control is the process of rounding the mantissa of the
  *      intermediate result to the specified precision AND CHECKING THE 16-BIT
  *      INTERMEDIATE EXPONENT to ensure that it is within the representable range of the
  *      selected rounding-precision format" (UM 9.4.2). Overflow is detected "when the
  *      intermediate result's exponent is greater than or equal to the maximum exponent
  *      value of the selected rounding precision" (UM 9.7.4) and underflow "when the
  *      intermediate result exponent is less than or equal to the minimum exponent value
  *      of the selected rounding precision" (UM 9.7.5).
  *
  * THE LIMITS, in the internal 15-bit EXTENDED bias (16383 = $3FFF), because the value
  * that finally lands in FPn is always an extended-format word ("the exponent value is in
  * the correct range even if it is stored in extended-precision format", UM 9.4.1):
  *
  *   precision  largest finite unbiased exp   biased    smallest NORMAL exp   biased
  *   single      +127                          $407E     -126                  $3F81
  *   double     +1023                          $43FE    -1022                  $3C01
  *   extended  +16383                          $7FFE   -16382                  $0001
  *
  * so `expMax` is the largest exponent a finite result may have and `expMinNorm` is the
  * smallest exponent a NORMALISED result may have; underflow is `exp < expMinNorm` and
  * overflow is `exp > expMax`. For extended those are exactly the `exp <= 0` / `exp >
  * 0x7FFE` tests SoftFloat's precision80 path uses, so the extended path is unchanged
  * bit-for-bit -- see FpRoundPack's header.
  *
  * ⚠ SOFTFLOAT IS NOT AN ORACLE FOR (b). The vendored `roundAndPackFloatx80`'s
  * roundingPrecision 32/64 arms round the MANTISSA at the reduced boundary but keep the
  * EXTENDED exponent limits ($7FFE / `zExp <= 0`) -- that is the x87 PC-field semantic,
  * not the 68k one. Musashi never even sets `floatx80_rounding_precision` (its
  * `fmove_fpcr` writes only `float_rounding_mode`), so LOCK-STEP CANNOT VALIDATE ANY OF
  * THIS, in either direction. The directed tests in FpuPrecisionSpec are the only oracle.
  *
  * Encoding is FPCR[7:6] verbatim: 00 Extend, 01 Single, 10 Double, 11 "Undefined"
  * (PRM Table 3-21). 11 is treated as Extend -- the architecture defines no behaviour, and
  * Extend is the reset value and the do-nothing choice. */
object FpPrec {
  val Ext = 0
  val Sgl = 1
  val Dbl = 2

  /** Index of the RETAINED least-significant mantissa bit. Bits below it are forced to
    * zero by rounding ("All mantissa bits beyond the selected precision are zero"). */
  def lsbPos(p: Int): Int = p match { case Sgl => 40; case Dbl => 11; case _ => 0 }
  /** Mask of the DISCARDED mantissa bits. */
  def roundMask(p: Int): BigInt = (BigInt(1) << lsbPos(p)) - 1
  /** Largest biased extended exponent a finite result of this precision may carry. */
  def expMax(p: Int): Int = p match { case Sgl => 0x407E; case Dbl => 0x43FE; case _ => 0x7FFE }
  /** Smallest biased extended exponent a NORMALISED result of this precision may carry. */
  def expMinNorm(p: Int): Int = p match { case Sgl => 0x3F81; case Dbl => 0x3C01; case _ => 1 }

  /** 3-way select on a 2-bit PREC field, mapping the undefined encoding 11 onto Extend. */
  def sel[T <: Data](p: Bits, ext: T, sgl: T, dbl: T): T =
    Mux(p === B(Sgl, 2 bits), sgl, Mux(p === B(Dbl, 2 bits), dbl, ext))
}

/** FPSR exception-status bits this arithmetic core can raise. BSUN is a branch-side
  * condition (FBcc on unordered) and INEX1 is packed-decimal-only; neither can originate
  * here, so neither is present. Never lock-stepped (Divergence Register D4). */
case class FpExcFlags() extends Bundle {
  val snan  = Bool()   // a signalling-NaN operand was consumed
  val operr = Bool()   // inf-inf, 0*inf, 0/0, inf/inf, sqrt(negative)
  val ovfl  = Bool()
  val unfl  = Bool()
  val dz    = Bool()   // finite / zero
  val inex2 = Bool()
  def clearExc(): Unit = {
    snan := False; operr := False; ovfl := False
    unfl := False; dz := False; inex2 := False
  }
}

/** One completed FPU operation. `fpcc` uses the internal layout fixed by the 2026-08-09
  * spec §8: [3:0] = {NaN, I, Z, N}, i.e. bit0=N, bit1=Z, bit2=I, bit3=NaN. Architectural
  * FPSR[27:24] = {N,Z,I,NaN} is the reversed presentation of this group; the reversal is
  * the EU/FPSR task's job, not this component's. */
case class FpResult() extends Bundle {
  val value   = Bits(80 bits)   // packed extended result; undefined when writeFp is False
  val writeFp = Bool()          // op produces an FP-register value (False for FCMP/FTST)
  val fpcc    = Bits(4 bits)
  val exc     = FpExcFlags()
}

/** The one canonical hand-off from every arithmetic front-end into FpRoundPack.
  *
  * Normal path: value = sig * 2^(exp - 16383 - 63), with `sig` normalised (bit 63 set) OR
  * `exp <= 0` (the subnormal arm re-shifts). `round`/`sticky` are the two bits below `sig`;
  * together they are exactly equivalent to SoftFloat's 64-bit `zSig1` low word for every
  * quantity `roundAndPackFloatx80`'s precision80 path actually reads, namely `zSig1[63]`
  * (round) and `(zSig1 << 1) != 0` (sticky).
  *
  * Bypass path: `bypass` means the front-end already produced the final 80-bit pattern
  * (NaN propagation, infinity results, FABS/FNEG/FMOVE/FMOVECR/FTST/FINT). Rounding,
  * range-checking and the OVFL/UNFL substitution are all suppressed; FPCC is still
  * classified from the bypassed value, which is what makes FTST fall out for free.
  * `exc` is still carried through verbatim, so a bypassing front-end (FINT) can still
  * declare its own INEX2. */
case class FpRoundReq() extends Bundle {
  val sign         = Bool()
  val exp          = SInt(18 bits)   // biased, signed & widened (FMUL can reach 49150, and
                                     // a subnormal-operand FMUL can reach -16506)
  val sig          = UInt(64 bits)
  val round        = Bool()
  val sticky       = Bool()
  val bypass       = Bool()
  val bypassValue  = Bits(80 bits)
  val writeFp      = Bool()
  val fpccFromSrc  = Bool()          // FCMP's explicit infinity table only
  val fpccOverride = Bits(4 bits)
  val exc          = FpExcFlags()    // upstream-detected snan/operr/dz/inex2
  /** FPCR[5:4] as it stood when this operation was ISSUED (0=RN 1=RZ 2=RM 3=RP). Carried
    * with the request rather than read live in FpRoundPack, so an FPCR write landing while
    * an operation is in flight cannot re-mux an already-issued result. */
  val rmode        = Bits(2 bits)

  /** The EFFECTIVE rounding precision for this operation, FPCR[7:6]-encoded (see
    * `FpPrec`): either FPCR.PREC as it stood at ISSUE, or the precision the INSTRUCTION
    * forces -- "Instructions with an S or D (e.g., FSADD) have the same effect as setting
    * the rounding precision to S or D" (MC68040UM 10.7). The choice between those two is
    * made once, at issue, by `FpSource.opmodeToPrecision`; by the time a request exists
    * the distinction is gone. Latched WITH the request for exactly the reason `rmode` is:
    * an FPCR write landing while an operation is in flight must not re-mux an
    * already-issued result.
    *
    * IGNORED when `bypass` is set -- a bypassed value is already the final bit pattern
    * (NaN/infinity propagation, FTST, FINT). The one subtlety that costs real logic is
    * that FABS/FNEG/FMOVE are NOT bypassed at single/double precision: their result is a
    * genuine 64-bit-significand extended value that still has to be rounded and
    * range-checked at the selected precision (PRM FMOVE: "MOVE will round the result to
    * the precision selected in the floating-point control register"). See FpCheapPipe. */
  val prec         = Bits(2 bits)

  /** Mark every leaf as overridable. The front-ends deliberately build a request as
    * "assign a complete default, then refine a few fields", which SpinalHDL's
    * PhaseCheck_noLatchNoOverride otherwise rejects as an unconditional assignment overlap.
    * Only the request-construction locals use this; no io/Reg is ever made overridable. */
  def overridable(): this.type = { flatten.foreach(_.allowOverride); this }
}

object Fp80 {
  val ExpInf  = 0x7FFF
  val ExpBias = 16383

  /** SoftFloat's floatx80_default_nan (softfloat-specialize:249-250): 0xFFFF_FFFFFFFFFFFF_FFFF */
  def defaultNan: Bits = B"16'hFFFF" ## B(BigInt("FFFFFFFFFFFFFFFF", 16), 64 bits)

  def sign(v: Bits): Bool = v(79)
  def exp (v: Bits): UInt = v(78 downto 64).asUInt
  def sig (v: Bits): UInt = v(63 downto 0).asUInt

  def pack(s: Bool, e: UInt, m: UInt): Bits = s ## e.resize(15) ## m.resize(64)
  def packInf(s: Bool): Bits  = pack(s, U(ExpInf, 15 bits), U(BigInt(1) << 63, 64 bits))
  def packZero(s: Bool): Bits = pack(s, U(0, 15 bits), U(0, 64 bits))

  /** SoftFloat: (exp == 0x7FFF) && (sig << 1) != 0 */
  def isNan(v: Bits): Bool = exp(v) === ExpInf && v(62 downto 0) =/= 0
  /** SoftFloat: (exp == 0x7FFF) && (sig << 1) == 0  (a mantissa of 0 counts, as in Musashi) */
  def isInf(v: Bits): Bool = exp(v) === ExpInf && v(62 downto 0) === 0
  /** Musashi SET_CONDITION_CODES' zero test (m68kfpu.c:282): exp==0 && (low<<1)==0. Note
    * this deliberately ignores bit 63, so a pseudo-denormal reads as zero. Match it. */
  def isZero(v: Bits): Bool = exp(v) === 0 && v(62 downto 0) === 0
  /** softfloat-specialize:269-278: a NaN whose bit 62 is clear. */
  def isSNan(v: Bits): Bool = isNan(v) && !v(62)
  /** exp != 0 and exp != 0x7FFF but the explicit integer bit is clear (VERIFY-4). */
  def isUnnormal(v: Bits): Bool = exp(v) =/= 0 && exp(v) =/= ExpInf && !v(63)
  def isDenorm(v: Bits): Bool   = exp(v) === 0 && v(62 downto 0) =/= 0

  /** Musashi SET_CONDITION_CODES (m68kfpu.c:271-298), in the internal {NaN,I,Z,N} order. */
  def classify(v: Bits): Bits = {
    val n = Bits(4 bits)
    n(0) := sign(v)
    n(1) := isZero(v)
    n(2) := isInf(v)
    n(3) := isNan(v)
    n
  }

  /** SoftFloat propagateFloatx80NaN (softfloat-specialize:320-337): quiet both operands by
    * OR-ing 0xC000000000000000 into the mantissa, then pick a unless a is a signalling NaN
    * and b is also a NaN, in which case pick b; if a is not a NaN at all, pick b. */
  def propagateNan(a: Bits, b: Bits): Bits = {
    val quiet = B(BigInt("C000000000000000", 16), 64 bits)
    val aq = a(79 downto 64) ## (a(63 downto 0) | quiet)
    val bq = b(79 downto 64) ## (b(63 downto 0) | quiet)
    Mux(isNan(a), Mux(isSNan(a) && isNan(b), bq, aq), bq)
  }

  /** Leading-zero count, MSB-first, returning `x.getWidth` when x is zero. Same linear
    * priority-encode idiom (and same caveat) as the existing Bitfield.scala clz32:
    * Vivado collapses it. Every use site in this design gives it a dedicated pipeline
    * stage, so it is never in series with a barrel shifter. */
  def clz(x: Bits): UInt = {
    val w  = x.getWidth
    val cw = log2Up(w + 1)
    val r  = x.reversed                       // r(0) is x's MSB
    val res = UInt(cw bits)
    res := U(w, cw bits)
    for (i <- w - 1 downto 0) when(r(i)) { res := U(i, cw bits) }   // lowest index wins
    res
  }

  /** Right shift by `8*k` with all shifted-out bits OR-jammed into bit 0. `k` is 0..8, so
    * this is nine constant (free) shifts, nine cheap OR-reductions and one 9:1 mux -- about
    * two LUT levels, versus the ~7 of a monolithic 67-bit barrel shifter. */
  def shiftRightJamCoarse(w: UInt, k: UInt): UInt = {
    val n = w.getWidth
    val out = UInt(n bits)
    out := w
    switch(k) {
      for (i <- 1 to 8) is(U(i, k.getWidth bits)) {
        val lost = w(8 * i - 1 downto 0) =/= 0
        out := (w >> (8 * i)).resize(n) | lost.asUInt.resize(n)
      }
    }
    out
  }

  /** Right shift by `k` (0..7) with jamming, same construction. */
  def shiftRightJamFine(w: UInt, k: UInt): UInt = {
    val n = w.getWidth
    val out = UInt(n bits)
    out := w
    switch(k) {
      for (i <- 1 to 7) is(U(i, k.getWidth bits)) {
        val lost = w(i - 1 downto 0) =/= 0
        out := (w >> i).resize(n) | lost.asUInt.resize(n)
      }
    }
    out
  }

  /** Left shift by `8*k`, k = 0..8 (no jamming needed -- see FpAddPipe's header comment). */
  def shiftLeftCoarse(w: UInt, k: UInt): UInt = {
    val n = w.getWidth
    val out = UInt(n bits)
    out := w
    switch(k) { for (i <- 1 to 8) is(U(i, k.getWidth bits)) { out := (w << (8 * i)).resize(n) } }
    out
  }

  /** Left shift by `k`, k = 0..7. */
  def shiftLeftFine(w: UInt, k: UInt): UInt = {
    val n = w.getWidth
    val out = UInt(n bits)
    out := w
    switch(k) { for (i <- 1 to 7) is(U(i, k.getWidth bits)) { out := (w << i).resize(n) } }
    out
  }
}
