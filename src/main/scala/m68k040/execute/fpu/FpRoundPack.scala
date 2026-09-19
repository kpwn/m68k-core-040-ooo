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

object FpRoundPack {
  /** Registered stages from `io.inValid` to `io.outValid`. */
  val Latency = 3
}

/** Normalise-range / round / pack, plus Decision 10's hardware-native OVFL and UNFL
  * substitution, for 80-bit extended results -- now at the rounding PRECISION the request
  * carries (`FpRoundReq.prec`, see `FpPrec`), which is FPCR.PREC or the precision an
  * FS<op>/FD<op> instruction forces.
  *
  * At `FpPrec.Ext` this is a direct hardware transcription of SoftFloat's
  * `roundAndPackFloatx80` precision80 path (softfloat.c:598-670) -- deliberately, because
  * that function IS the lock-step oracle (Decision 1). The two SoftFloat quantities that
  * need justification:
  *
  *  (a) The 64-bit `zSig1` low word is compressed to {round, sticky}. The precision80 path
  *      reads exactly two things from zSig1: `(sbits64)zSig1 < 0` (i.e. zSig1[63], our
  *      `round`) and `(bits64)(zSig1<<1) == 0` (i.e. zSig1[62:0] == 0, our `!sticky`),
  *      plus `zSig1 != 0` (our `round | sticky`). Nothing else. The compression is exact.
  *
  *  (b) `shift64ExtraRightJamming(zSig0, zSig1, 1 - zExp, ...)` on the subnormal arm is
  *      reproduced by jam-shifting the 66-bit word {sig, round, sticky}: for count c >= 1
  *      SoftFloat produces z1[63] = a0[c-1] and z1[62:0] != 0 iff (a0[c-2:0] != 0 or
  *      a1 != 0), which is bit-for-bit what a jam shift of {sig,round,sticky} yields, and
  *      saturating c at 66 is exact because a 67-position shift and a 4000-position shift
  *      produce the identical {0, 0, sticky=1}.
  *
  * ── HOW PRECISION GENERALISES EACH PIECE, AND WHY THE EXTENDED PATH IS UNCHANGED ──
  *
  * Three per-precision quantities drive everything (all from `FpPrec`, all decoded from
  * the request's own 2-bit field so an in-flight FPCR write cannot re-mux a result):
  * `lsbPos` (index of the retained mantissa LSB: 0 / 11 / 40), `expMax` and `expMinNorm`.
  *
  *  - GUARD / STICKY / LSB. Extended reads the request's own `round`/`sticky` and `sig(0)`.
  *    Single reads `sig(39)` for guard, `sig(38 downto 0) != 0 || round || sticky` for
  *    sticky and `sig(40)` for the LSB; double the same with 10/9/11. This is the UM's
  *    "depending on the ... destination data format in effect, the LOCATION of the least
  *    significant bit of the mantissa and the locations of the guard, round, and sticky
  *    bits in the 67-bit intermediate result mantissa varies" (9.4.1), literally.
  *
  *  - THE INCREMENT. SoftFloat's precision80 arm increments on the guard bit and then
  *    fixes exact ties up by clearing the LSB afterwards (`zSig0 &= ~((zSig1<<1 == 0) &
  *    roundNearestEven)`). The identical function, written WITHOUT the post-adder fix-up,
  *    is `increment = guard && (sticky || lsb)` -- round-half-to-EVEN stated directly.
  *    Proof of equivalence at any boundary: on a tie (guard set, sticky clear) SoftFloat
  *    adds one ulp and then clears the LSB, which restores the truncated value when the
  *    LSB was 0 and keeps the carry when it was 1; that is exactly `sticky || lsb`. This
  *    is not a cosmetic rewrite -- it is what keeps the 65-bit adder in RP2 free of a
  *    following variable 64-bit AND mask, which a precision-dependent fix-up would need.
  *
  *  - "ALL MANTISSA BITS BEYOND THE SELECTED PRECISION ARE ZERO" (UM 9.4.1) falls out of
  *    masking the significand BEFORE the adder (`sig & ~roundMask`, done in RP1 where
  *    there is slack) and adding one ulp-at-precision (`1 << lsbPos`) rather than 1.
  *
  *  - RANGE CONTROL (UM 9.4.2 / 9.7.4 / 9.7.5). Overflow is `exp > expMax`, underflow is
  *    `exp < expMinNorm`; at Extend those are `exp > 0x7FFE` and `exp <= 0`, i.e. the
  *    SoftFloat tests verbatim. The UNFL arm's jam shift count becomes `expMinNorm - exp`
  *    (1 - exp at Extend) and the post-shift exponent becomes `expMinNorm`, EXCEPT at
  *    Extend where the format's denormal encoding forces the stored field to 0 (an
  *    extended exponent field of 0 denotes the effective exponent 1). A single-precision
  *    denormal therefore lands in FPn as an ordinary, perfectly normal-looking extended
  *    word with exponent $3F81 and leading mantissa zeros -- which is what "the exponent
  *    value is in the correct range even if it is stored in extended-precision format"
  *    (UM 9.4.1) describes, and what makes the UM's own worked overflow example
  *    (mantissa $FFFFFF0000000000) come out right.
  *
  * ⚠ SOFTFLOAT'S OWN REDUCED-PRECISION ARMS ARE NOT A MODEL FOR THIS. Its
  * roundingPrecision 32/64 paths round the mantissa at the narrow boundary but keep
  * `0x7FFE` / `zExp <= 0` -- the x87 PC-field semantic, with no range control. Do not
  * "fix" this file toward them. See `FpPrec`'s header.
  *
  * OVERFLOW (Decision 10's 4-way table) is the `exp > expMax` arm. SoftFloat's own
  * selection expression is `RZ || (sign && RP) || (!sign && RM)` -> largest finite,
  * otherwise infinity, which is *exactly* the design spec's table and UM Table 9-12:
  *   RN -> Infinity(sign) | RZ -> largest(sign) | RM -> +ovfl:largest, -ovfl:Inf
  *   | RP -> +ovfl:Inf, -ovfl:largest.
  * "Largest" is now the largest finite AT THE SELECTED PRECISION -- exponent `expMax`,
  * mantissa `~roundMask` (all retained bits set) -- while infinity stays the EXTENDED
  * infinity, because the destination data format is the extended FPn register.
  *
  * UNDERFLOW (Decision 10's denormalise-then-round path) is the `exp < expMinNorm` arm:
  * shift the mantissa right while the exponent climbs to `expMinNorm`, then round. If the
  * shift empties the mantissa entirely the same rounding increment produces
  * zero-vs-smallest-denormal per sign and rounding mode (UM Table 9-13) -- i.e. the spec's
  * "structurally identical in shape to overflow's" fallback table drops out of the same
  * increment logic rather than being a second table.
  *
  * Tininess is detected AFTER rounding, matching SoftFloat's
  * `float_detect_tininess = float_tininess_after_rounding` (softfloat-specialize:43). */
class FpRoundPack extends Component {
  val io = new Bundle {
    val inValid  = in Bool ()
    val inReq    = in(FpRoundReq())
    val outValid = out Bool ()
    val outRes   = out(FpResult())
  }

  // Rounding mode travels WITH the request (io.inReq.rmode = FPCR[5:4] at issue), so a
  // mid-flight FPCR write can never re-mux an already-issued result.
  val rn = io.inReq.rmode === 0
  val rz = io.inReq.rmode === 1
  val rm = io.inReq.rmode === 2
  val rp = io.inReq.rmode === 3

  private val allOnes64 = U(BigInt("FFFFFFFFFFFFFFFF", 16), 64 bits)

  private[fpu] def m64(p: Int) = U(FpPrec.roundMask(p), 64 bits)
  private[fpu] def u64(p: Int) = U(BigInt(1) << FpPrec.lsbPos(p), 64 bits)
  private[fpu] def e18(v: Int) = S(v, 18 bits)

  /** Everything the 2-bit PREC field decides, decoded from ONE request's own field.
    * Re-derived per stage rather than piped as extra registers: every member below is a
    * mux of compile-time constants selected by two already-registered bits, which
    * synthesises to at most one LUT level and costs no flops. */
  class PrecCtl(p: Bits) {
    val isSgl = p === B(FpPrec.Sgl, 2 bits)
    val isDbl = p === B(FpPrec.Dbl, 2 bits)
    val isExt = !isSgl && !isDbl                  // encoding 11 ("Undefined") lands here
    /** Mask of the mantissa bits this precision DISCARDS. */
    val roundMask  = FpPrec.sel(p, m64(FpPrec.Ext), m64(FpPrec.Sgl), m64(FpPrec.Dbl))
    /** Mask of the mantissa bits this precision KEEPS -- also the all-ones mantissa of the
      * largest finite number at this precision. */
    val keep       = ~roundMask
    /** One unit in the last place at this precision, i.e. 1 << lsbPos. */
    val oneUlp     = FpPrec.sel(p, u64(FpPrec.Ext), u64(FpPrec.Sgl), u64(FpPrec.Dbl))
    val expMax     = FpPrec.sel(p, e18(FpPrec.expMax(FpPrec.Ext)),
                                   e18(FpPrec.expMax(FpPrec.Sgl)),
                                   e18(FpPrec.expMax(FpPrec.Dbl)))
    val expMinNorm = FpPrec.sel(p, e18(FpPrec.expMinNorm(FpPrec.Ext)),
                                   e18(FpPrec.expMinNorm(FpPrec.Sgl)),
                                   e18(FpPrec.expMinNorm(FpPrec.Dbl)))

    /** Guard / sticky / retained-LSB of a {sig, round, sticky} triple at this precision,
      * and the resulting round-to-nearest-EVEN increment decision. */
    def digits(sig: UInt, rnd: Bool, stk: Bool) = new Area {
      val guard  = Mux(isSgl, sig(39), Mux(isDbl, sig(10), rnd))
      val below  = Mux(isSgl, sig(38 downto 0) =/= 0, Mux(isDbl, sig(9 downto 0) =/= 0, False))
      val sticky = Mux(isExt, stk, below || rnd || stk)
      val lsb    = Mux(isSgl, sig(40), Mux(isDbl, sig(11), sig(0)))
      /** Any discarded bit is non-zero => the result is INEXACT. */
      val nz     = guard || sticky
      def incrFor(sign: Bool, eRn: Bool, eRz: Bool, eRm: Bool, eRp: Bool): Bool =
        Mux(eRn, guard && (sticky || lsb),
        Mux(eRz, False,
        Mux(sign, eRm && nz, eRp && nz)))
    }
  }

  // -- RP0: range classification -----------------------------------------------
  // Decides which of the three arms (overflow / subnormal / normal) applies, and the
  // rounding increment for the normal arm. Only 18-bit compares and a handful of gates.
  val s0 = new Area {
    val pc      = new PrecCtl(io.inReq.prec)
    val d       = pc.digits(io.inReq.sig, io.inReq.round, io.inReq.sticky)
    val incr    = d.incrFor(io.inReq.sign, rn, rz, rm, rp)
    /** Every RETAINED bit set: the significand is already the largest finite mantissa at
      * this precision, so an increment carries out of the format. */
    val allOnes = (io.inReq.sig | pc.roundMask) === allOnes64
    val ovfl    = (io.inReq.exp > pc.expMax) ||
                  (io.inReq.exp === pc.expMax && allOnes && incr)
    val sub     = !ovfl && (io.inReq.exp < pc.expMinNorm)
    // shift count expMinNorm - exp, saturated at 66 (exact, see header note (b)).
    val cntFull = pc.expMinNorm - io.inReq.exp
    val cntSat  = Mux(cntFull > S(66, 18 bits), U(66, 7 bits), cntFull.asUInt.resize(7))

    val vld   = RegNext(io.inValid) init False
    val req   = RegNext(io.inReq)
    val rIncr = RegNext(incr)
    val rOvfl = RegNext(ovfl)
    val rSub  = RegNext(sub)
    val rCnt  = RegNext(cntSat)
    val rAll  = RegNext(allOnes)
    // Latched so a mid-flight FPCR write cannot re-mux an in-flight result.
    val rRz   = RegNext(rz); val rRm = RegNext(rm); val rRp = RegNext(rp); val rRn = RegNext(rn)
  }

  // -- RP1: the subnormal (underflow) right-shift, and the final increment decision ---
  val s1 = new Area {
    val pc = new PrecCtl(s0.req.prec)
    // 66-bit {sig, round, sticky}; bit 0 IS the sticky position, so jamming into it is
    // always semantically correct.
    val ext   = (s0.req.sig ## s0.req.round ## s0.req.sticky).asUInt      // 66 bits
    val shC   = Fp80.shiftRightJamCoarse(ext, s0.rCnt(6 downto 3))
    val shF   = Fp80.shiftRightJamFine(shC, s0.rCnt(2 downto 0))
    val subSig    = shF(65 downto 2)
    val subRound  = shF(1)
    val subSticky = shF(0)

    val sig    = Mux(s0.rSub, subSig,    s0.req.sig)
    val round  = Mux(s0.rSub, subRound,  s0.req.round)
    val sticky = Mux(s0.rSub, subSticky, s0.req.sticky)
    val d      = pc.digits(sig, round, sticky)
    val incr   = Mux(s0.rSub, d.incrFor(s0.req.sign, s0.rRn, s0.rRz, s0.rRm, s0.rRp),
                              s0.rIncr)
    // SoftFloat tininess is `float_tininess_after_rounding` (softfloat-specialize:43), so
    // isTiny is (exp < expMinNorm-1) || !increment || (not already the largest mantissa at
    // this precision) -- true in every case this arm can reach except the exact "rounds
    // back up to the smallest normal" corner. At Extend that reads (exp < 0) || !incr ||
    // (sig != all-ones), i.e. SoftFloat verbatim.
    val isTiny = (s0.req.exp < (pc.expMinNorm - 1)) || !s0.rIncr || !s0.rAll
    val exp0   = Mux(s0.rSub, Mux(pc.isExt, S(0, 18 bits), pc.expMinNorm), s0.req.exp)

    val vld    = RegNext(s0.vld) init False
    val req    = RegNext(s0.req)
    // Masked HERE, not after the adder: "All mantissa bits beyond the selected precision
    // are zero" (UM 9.4.1), and RP2's 65-bit carry chain must not be followed by a
    // variable-width AND.
    val rSig   = RegNext(sig & pc.keep)
    val rIncr  = RegNext(incr)
    val rIncrA = RegNext(Mux(incr, pc.oneUlp, U(0, 64 bits)))    // the value actually added
    val rExp   = RegNext(exp0)
    val rOvfl  = RegNext(s0.rOvfl)
    val rSub   = RegNext(s0.rSub)
    val rInex  = RegNext(d.nz)
    val rUnfl  = RegNext(s0.rSub && isTiny && d.nz)
    val rToMax = RegNext(s0.rRz || (s0.req.sign && s0.rRp) || (!s0.req.sign && s0.rRm))
  }

  // -- RP2: increment, carry fix-up, substitution, pack, classify ----------------
  val s2 = new Area {
    val pc    = new PrecCtl(s1.req.prec)
    val sum   = s1.rSig +^ s1.rIncrA                           // 65 bits
    val carry = sum(64)
    val incd  = sum(63 downto 0)

    val sigN = UInt(64 bits)
    val expN = SInt(18 bits)
    when(carry) {                                              // 0xFFFF..F + 1 -> 2^64
      sigN := U(BigInt(1) << 63, 64 bits); expN := s1.rExp + 1
    } elsewhen(!s1.rIncr && s1.rSig === 0) {                   // SoftFloat: zSig0==0 => exp=0
      sigN := U(0, 64 bits);              expN := S(0, 18 bits)
    } otherwise {
      sigN := incd
      // Extended subnormal that rounded up into the smallest normal: SoftFloat sets
      // zExp = 1. Single/double need no such fix-up -- their denormal arm already parks
      // the exponent at expMinNorm, which is ALSO the right exponent once the integer bit
      // reappears, because extended has no special encoding at $3F81/$3C01.
      expN := Mux(s1.rSub && pc.isExt && incd(63), S(1, 18 bits), s1.rExp)
    }

    val packed = Fp80.pack(s1.req.sign, expN.asUInt.resize(15), sigN)
    // Decision 10's / UM Table 9-12's overflow table, at the SELECTED precision: the
    // largest finite is `expMax` with every retained mantissa bit set (the UM's own
    // single-precision example: mantissa $FFFFFF0000000000), while infinity is the
    // DESTINATION format's infinity, and the destination is the extended FPn register.
    val ovflVal = Mux(s1.rToMax,
                      Fp80.pack(s1.req.sign, pc.expMax.asUInt.resize(15), pc.keep),
                      Fp80.packInf(s1.req.sign))

    val value = Mux(s1.req.bypass, s1.req.bypassValue,
                Mux(s1.rOvfl, ovflVal, packed))

    val exc = FpExcFlags()
    exc.snan  := s1.req.exc.snan
    exc.operr := s1.req.exc.operr
    exc.dz    := s1.req.exc.dz
    exc.ovfl  := !s1.req.bypass && s1.rOvfl
    exc.unfl  := !s1.req.bypass && s1.rUnfl
    // A bypassing front-end may declare its own inexactness (FINT/FINTRZ); the datapath's
    // own inexactness is only meaningful on the non-bypass path. Overflow is always inexact.
    exc.inex2 := s1.req.exc.inex2 || (!s1.req.bypass && (s1.rInex || s1.rOvfl))

    io.outValid    := RegNext(s1.vld) init False
    io.outRes.value   := RegNext(value)
    io.outRes.writeFp := RegNext(s1.req.writeFp)
    io.outRes.fpcc    := RegNext(Mux(s1.req.fpccFromSrc, s1.req.fpccOverride, Fp80.classify(value)))
    io.outRes.exc     := RegNext(exc)
  }
}
