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
