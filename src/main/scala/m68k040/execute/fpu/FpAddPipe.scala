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

object FpAddPipe {
  /** Registered stages from `io.start` to `io.outValid`. Must equal FpMulPipe.Latency and
    * FpCheapPipe.Latency -- FpuCore relies on all three fronts being the same depth so the
    * shared FpRoundPack back-end can never see two results in one cycle. */
  val Latency = 10
}

/** FADD / FSUB / FCMP. Elastic, fully registered, initiation interval 1: `start` may be
  * asserted every cycle and results leave in issue order at exactly FpAddPipe.Latency.
  * Never stalls -- the EU must reserve result capacity before asserting start (2026-08-09
  * spec §3, "Issue reserves result capacity before entering any unstallable tail").
  *
  * Operand naming follows Musashi: `dst` is the FPn destination (SoftFloat's `a`), `src` is
  * the <ea>/FPm source (SoftFloat's `b`); FSUB computes dst - src.
  *
  * ── THE SINGLE-PATH CORRECTNESS ARGUMENT (why there is no near/far two-path adder) ──
  *
  * The datapath is one 67-bit word `W`, laid out `W[66:3] = significand`, `W[2] = G`,
  * `W[1] = R`, `W[0] = S`. Right shifts jam into `W[0]`; left shifts fill with zeros. This
  * is correct for BOTH the large-cancellation and the large-alignment case, with no second
  * path:
  *
  *  - Alignment <= 1 (the only case that can cancel by more than one bit). Nothing is lost
  *    in the align shift, so the subtraction is exact and a left shift of up to 66 positions
  *    shifts in only genuine zeros. Round and sticky end up zero, which is correct.
  *  - Alignment >= 2 (the only case that loses bits). The result then has at most one
  *    leading zero, so the left shift is 0 or 1 positions. Correctness with only G/R/S rests
  *    on the borrow argument: performing `{A,0,0,0} - {B,G,R,S}` as one 67-bit two's-
  *    complement subtract puts `0 - S` in the S position, so a nonzero true tail borrows into
  *    the R position exactly as the real infinite-precision subtraction would, and the
  *    result's true tail is nonzero iff `S` was -- so the S position after the subtract still
  *    summarises it. After a 1-position left shift, `sig <- ...G`, `round <- R`,
  *    `sticky <- S`, which is what `sig = W'[66:3]`, `round = W'[2]`,
  *    `sticky = W'[1] | W'[0]` reads out. (Exhaustively checked against SoftFloat's 128-bit
  *    `sub128` + `normalizeRoundAndPackFloatx80` for all eight {G,R,S} patterns and both
  *    left-shift amounts.)
  *
  * Consequently the ONLY structure needed beyond the align shifter is one 67-bit LZC and one
  * 67-bit left shifter, both of which get their own pipeline stage.
  *
  * ── SUBNORMAL HANDLING WITHOUT A PRE-NORMALISER ──
  *
  * SoftFloat's add/sub never pre-normalises subnormals; it uses the effective-exponent trick
  * (`if (bExp == 0) --expDiff` / `if (aExp == 0) ++expDiff`, softfloat.c:3172,3183,
  * 3242-3245). This design does the same by defining `eX = (expX == 0) ? 1 : expX` once and
  * using it for both the alignment amount and the result exponent. A subnormal sum comes out
  * with `exp <= 0`, and FpRoundPack's underflow arm shifts it back -- exactly, bit for bit,
  * reproducing SoftFloat's `normalizeFloatx80Subnormal` -> `roundAndPackFloatx80` round trip.
  *
  * Ordering by EFFECTIVE rather than raw exponent is equivalent to SoftFloat's raw-exponent
  * ordering for every non-unnormal operand pair: the only way two operands can have equal
  * effective exponents but different raw ones is `{0, S}` vs `{1, S'}`, and then the
  * subnormal necessarily has `S < 2^63 <= S'`, so the significand tie-break picks the same
  * operand SoftFloat's raw compare does. Pseudo-denormals (exp 0 with the integer bit set)
  * are the one exception -- see design-spec Divergence Register D5. */
class FpAddPipe extends Component {
  val io = new Bundle {
    val start    = in Bool ()
    val op       = in(FpOp())
    val dst      = in Bits (80 bits)
    val src      = in Bits (80 bits)
    val rmode    = in Bits (2 bits)
    val precision = in Bits (2 bits)     // effective FPCR.PREC / forced FS<op>,FD<op>
    val outValid = out Bool ()
    val outReq   = out(FpRoundReq())
  }

  // -- A0: unpack + classify ---------------------------------------------------
  val a0 = new Area {
    val isSub  = io.op === FpOp.FSUB || io.op === FpOp.FCMP
    val isCmp  = io.op === FpOp.FCMP

    val vld  = RegNext(io.start) init False
    val dst  = RegNext(io.dst)
    val src  = RegNext(io.src)
    val sub  = RegNext(isSub)
    val cmp  = RegNext(isCmp)
    val rmRM = RegNext(io.rmode === 2)                 // for the exact-cancellation zero sign
    val rmod = RegNext(io.rmode)                       // latched at issue, travels with the req
    // FCMP is FORCED to extended regardless of FPCR.PREC. It has no forced-precision
    // opmode of its own ($38 only) and, per MC68040UM 9.7.4/9.7.5, OVFL/UNFL are detected
    // only "for arithmetic operations in which the DESTINATION is a floating-point data
    // register or memory" -- FCMP's only destination is the FPCC. Rounding its internal
    // difference at single precision would both raise a spurious OVFL for
    // `FCMP MAX,-MAX` and let a tiny difference flush to zero and report EQUAL.
    val prec = RegNext(Mux(isCmp, B(FpPrec.Ext, 2 bits), io.precision))

    val dNan  = RegNext(Fp80.isNan(io.dst));  val sNan  = RegNext(Fp80.isNan(io.src))
    val dInf  = RegNext(Fp80.isInf(io.dst));  val sInf  = RegNext(Fp80.isInf(io.src))
    val dSNan = RegNext(Fp80.isSNan(io.dst)); val sSNan = RegNext(Fp80.isSNan(io.src))
    // effective exponent: subnormal (exp 0) behaves as exp 1
    val dE = RegNext(Mux(Fp80.exp(io.dst) === 0, U(1, 15 bits), Fp80.exp(io.dst)))
    val sE = RegNext(Mux(Fp80.exp(io.src) === 0, U(1, 15 bits), Fp80.exp(io.src)))
  }

  // -- A1: order by magnitude, compute the alignment amount, resolve all specials ----
  val a1 = new Area {
    val dSign = Fp80.sign(a0.dst); val sSign = Fp80.sign(a0.src)
    val dSig  = Fp80.sig(a0.dst);  val sSig  = Fp80.sig(a0.src)
    // effective add iff the two effective signs agree (FSUB flips src's)
    val effAdd = dSign === (sSign ^ a0.sub)
    val zSign  = dSign                                  // SoftFloat always passes aSign

    val srcBigger = (a0.sE > a0.dE) || (a0.sE === a0.dE && sSig > dSig)
    val bigSig  = Mux(srcBigger, sSig, dSig)
    val smlSig  = Mux(srcBigger, dSig, sSig)
    val bigE    = Mux(srcBigger, a0.sE, a0.dE)
    val smlE    = Mux(srcBigger, a0.dE, a0.sE)
    val diff    = (bigE - smlE).resize(16)
    val shAmt   = Mux(diff > U(66, 16 bits), U(66, 7 bits), diff.resize(7))
    // magnitude-subtract result sign flips when src is the larger operand
    val resSign = Mux(effAdd, zSign, Mux(srcBigger, !zSign, zSign))

    val anyNan   = a0.dNan || a0.sNan
    val bothInf  = a0.dInf && a0.sInf
    val exactZero= !effAdd && !anyNan && !a0.dInf && !a0.sInf &&
                   a0.dE === a0.sE && dSig === sSig

    // FCMP's explicit infinity table (Musashi m68kfpu.c:1517-1545). d = is_inf(dst),
    // s = is_inf(src). Internal FPCC order is {NaN,I,Z,N} = bit3..bit0.
    val fpccCmp = Bits(4 bits)
    fpccCmp := 0
    when(a0.sInf && sSign) {                                    // s < 0
      when(a0.dInf && dSign) { fpccCmp := B"0011" }             //   d < 0 -> Z|N
    }.elsewhen(a0.sInf && !sSign) {                             // s > 0
      when(a0.dInf && !dSign) { fpccCmp := B"0010" }            //   d > 0 -> Z
        .otherwise            { fpccCmp := B"0001" }            //   else  -> N
    }.otherwise {                                               // s == 0 (finite)
      when(a0.dInf && dSign) { fpccCmp := B"0001" }             //   d < 0 -> N
    }
    val cmpSpecial = a0.cmp && !anyNan && (a0.dInf || a0.sInf)

    val req = FpRoundReq().overridable()
    req.sign := resSign
    req.exp  := bigE.resize(18).asSInt
    req.sig  := bigSig                            // A2..A7 recompute sig/round/sticky
    req.round := False
    req.sticky := False
    req.writeFp := !a0.cmp
    req.fpccFromSrc  := cmpSpecial
    req.fpccOverride := fpccCmp
    req.rmode := a0.rmod
    req.prec  := a0.prec
    req.exc.clearExc()
    req.exc.snan  := a0.dSNan || a0.sSNan
    // Divergence Register D6: Musashi's FCMP resolves infinities from the explicit table and
    // never calls floatx80_sub, so an FCMP against an infinity raises no invalid operation.
    req.exc.operr := bothInf && !effAdd && !anyNan && !cmpSpecial
    req.bypass      := False
    req.bypassValue := Fp80.defaultNan

    when(anyNan) {
      req.bypass := True; req.bypassValue := Fp80.propagateNan(a0.dst, a0.src)
    } elsewhen(bothInf && !effAdd) {
      req.bypass := True; req.bypassValue := Fp80.defaultNan      // inf - inf
    } elsewhen(a0.dInf) {
      req.bypass := True; req.bypassValue := a0.dst               // SoftFloat "return a"
    } elsewhen(a0.sInf) {
      req.bypass := True; req.bypassValue := Fp80.packInf(Mux(effAdd, zSign, !zSign))
    } elsewhen(exactZero) {
      // SoftFloat subFloatx80Sigs: pack(rmode == RM, 0, 0)
      req.bypass := True; req.bypassValue := Fp80.packZero(a0.rmRM)
    }
    when(cmpSpecial) { req.bypass := True }        // value irrelevant, FPCC comes from table

    val vld    = RegNext(a0.vld) init False
    val rReq   = RegNext(req)
    val rBig   = RegNext(bigSig)
    val rSml   = RegNext(smlSig)
    val rSh    = RegNext(shAmt)
    val rEffAdd= RegNext(effAdd)
  }

  // -- A2/A3: align the smaller operand (coarse then fine, jamming into bit 0) ----
  // W[66:3] = significand, W[2] = G, W[1] = R, W[0] = S.
  val a2 = new Area {
    val w    = (a1.rSml ## B"000").asUInt                    // 67 bits
    val vld  = RegNext(a1.vld) init False
    val rReq = RegNext(a1.rReq); val rBig = RegNext(a1.rBig)
    val rEffAdd = RegNext(a1.rEffAdd); val rShLo = RegNext(a1.rSh(2 downto 0))
    val rW   = RegNext(Fp80.shiftRightJamCoarse(w, a1.rSh(6 downto 3)))
  }
  val a3 = new Area {
    val vld  = RegNext(a2.vld) init False
    val rReq = RegNext(a2.rReq); val rBig = RegNext(a2.rBig)
    val rEffAdd = RegNext(a2.rEffAdd)
    val rW   = RegNext(Fp80.shiftRightJamFine(a2.rW, a2.rShLo))
  }

  // -- A4: 68-bit add / 67-bit subtract, with the add's carry folded back in -----
  val a4 = new Area {
    val bigW = (a3.rBig ## B"000").asUInt                    // 67 bits
    val sum  = bigW +^ a3.rW                                 // 68 bits
    // On carry, shift right one with jamming: {sum[67:2], sum[1] | sum[0]}
    val sumN = Mux(sum(67), (sum(67 downto 2) ## (sum(1) | sum(0))).asUInt, sum(66 downto 0))
    val dif  = bigW - a3.rW                                  // never negative (ordered above)
    val vld  = RegNext(a3.vld) init False
    val rReq = RegNext(a3.rReq)
    val rW   = RegNext(Mux(a3.rEffAdd, sumN, dif))
    val rExpAdj = RegNext(Mux(a3.rEffAdd && sum(67), S(1, 18 bits), S(0, 18 bits)))
  }

  // -- A5: leading-zero count (own stage; never in series with a shifter) --------
  val a5 = new Area {
    val vld  = RegNext(a4.vld) init False
    val rReq = RegNext(a4.rReq); val rW = RegNext(a4.rW); val rExpAdj = RegNext(a4.rExpAdj)
    val rClz = RegNext(Fp80.clz(a4.rW.asBits))               // 0..67
  }

  // -- A6/A7: left-normalise (coarse then fine) and adjust the exponent ---------
  val a6 = new Area {
    val vld  = RegNext(a5.vld) init False
    val rReq = RegNext(a5.rReq); val rExpAdj = RegNext(a5.rExpAdj)
    val rClz = RegNext(a5.rClz); val rClzLo = RegNext(a5.rClz(2 downto 0))
    val rW   = RegNext(Fp80.shiftLeftCoarse(a5.rW, a5.rClz(6 downto 3)))
  }
  val a7 = new Area {
    val w    = Fp80.shiftLeftFine(a6.rW, a6.rClzLo)
    val isZero = a6.rClz === 67
    val req  = FpRoundReq().overridable()
    req := a6.rReq
    req.sig    := w(66 downto 3)
    req.round  := w(2)
    req.sticky := w(1) | w(0)
    // exp = bigExp + carryAdjust - clz. A fully cancelled effective SUBTRACT cannot reach
    // here (A1's `exactZero` bypasses it), but 0 + 0 can, so force the exponent to 0 rather
    // than letting bigExp+adj leak into FpRoundPack's overflow compare.
    req.exp    := a6.rReq.exp + a6.rExpAdj - a6.rClz.resize(18).asSInt
    when(isZero) { req.sig := 0; req.round := False; req.sticky := False; req.exp := 0 }
    val vld  = RegNext(a6.vld) init False
    val rReq = RegNext(req)
  }

  // -- A8/A9: FMax reserve. DO NOT silently delete these. ------------------------
  // They exist so that FpAddPipe.Latency equals FpMulPipe.Latency, which is what lets
  // FpuCore have exactly ONE fixed-result port with MulCore-identical semantics. They cost
  // ~2 x 95 = 190 flops. If the Task H FMax gate shows A1 (the 64-bit magnitude compare +
  // special-case mux) or A4 (the 68-bit add) as the limiter, the fix is to MOVE WORK INTO
  // A8/A9, not to remove them.
  val a8 = new Area { val vld = RegNext(a7.vld) init False; val rReq = RegNext(a7.rReq) }
  val a9 = new Area { val vld = RegNext(a8.vld) init False; val rReq = RegNext(a8.rReq) }

  io.outValid := a9.vld
  io.outReq   := a9.rReq
}
