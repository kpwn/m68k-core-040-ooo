package m68k040.execute

import spinal.core._
import spinal.lib._

/** DIV form: the 040 integer-divide variants. qWidth(form) = the quotient/remainder
  * width; the .W form is 16-bit, the .L forms 32-bit. div64 selects a 64-bit dividend
  * (Dr:Dq) vs 32-bit. */
object DivForm extends SpinalEnum {
  val W, L32, L64 = newElement()   // 32/16, 32/32, 64/32
}

/** Signed/unsigned wrapper around DivCore: sign-normalizes the operands (DIVS),
  * divides the magnitudes, fixes up the quotient/remainder signs (68k rule: the
  * remainder takes the DIVIDEND's sign), and detects overflow per form. Produces a
  * 32-bit quotient + 32-bit remainder (the .W form's results occupy the low 16 bits).
  *
  * `start` (with {dividend64, divisor32, signed, form}) -> iterates -> `done` pulses
  * with {quotient, remainder, overflow, divByZero}.
  *
  * Pre-normalization (combinational, latched into DivCore at start):
  *   dividend magnitude = signed ? |sext(dividend)| : zext(dividend)
  *   divisor  magnitude = signed ? |sext(divisor)|  : zext(divisor)
  * The input `dividend`/`divisor` are already presented at the proper width by the EU
  * (.W: 32-bit dividend sign/zero-extended to 64, 16-bit divisor s/z-ext to 32; .L32:
  * 32-bit dividend s/z-ext to 64; .L64: full 64-bit dividend), so here they are the
  * full 64/32 operands and we only take |.| when signed.
  */
class DivUnit extends Component {
  val io = new Bundle {
    val start     = in Bool ()
    val dividend  = in UInt (64 bits)   // pre-extended by the EU (sext for signed)
    val divisor   = in UInt (32 bits)   // pre-extended by the EU
    val signed    = in Bool ()
    val form      = in (DivForm())
    val busy      = out Bool ()
    val done      = out Bool ()
    val quotient  = out UInt (32 bits)
    val remainder = out UInt (32 bits)
    val overflow  = out Bool ()
    val divByZero = out Bool ()
  }

  val core = new DivCore

  // ── sign capture (latched at start, used at done for the fix-up) ──
  val signDividend = RegInit(False)
  val signDivisor  = RegInit(False)
  val signedReg    = RegInit(False)
  val formReg      = Reg(DivForm())
  // The divide-by-(-1) erratum check below (see its comment) only ever consumes 3
  // booleans derived from the divisor magnitude / raw dividend, never the full values
  // themselves -- so latch the booleans directly (task #253) instead of carrying the
  // full 96 bits (32+64) of source data through registers just to re-derive them at
  // `done`. Computed combinationally from the same io.divisor/io.dividend/io.signed
  // this class already captures at the same `start` pulse (io.divisor/io.dividend are
  // held stable by the EU for the whole DIVING state, same assumption the old
  // dvsrMagReg/dividendReg registers relied on).
  val divisorIsNegOneReg     = RegInit(False)
  val l32DividendIsIntMinReg = RegInit(False)
  val l64DividendIsExactReg  = RegInit(False)

  // Magnitudes: for signed, negate a negative operand. The dividend is a 64-bit
  // sign-extended value; the divisor is 32-bit sign-extended.
  val divdNeg = io.signed && io.dividend(63)
  val dvsrNeg = io.signed && io.divisor(31)
  val divdMag = Mux(divdNeg, (~io.dividend + 1), io.dividend)
  val dvsrMag = Mux(dvsrNeg, (~io.divisor + 1), io.divisor)

  core.io.start    := io.start && !core.io.busy
  core.io.dividend := divdMag
  core.io.divisor  := dvsrMag

  when(io.start && !core.io.busy) {
    signDividend := divdNeg
    signDivisor  := dvsrNeg
    signedReg    := io.signed
    formReg      := io.form
    divisorIsNegOneReg     := io.signed && dvsrNeg && (dvsrMag === U(1, 32 bits))
    l32DividendIsIntMinReg := io.dividend(31 downto 0) === U(0x80000000L, 32 bits)
    l64DividendIsExactReg  := io.dividend === U(0x80000000L, 64 bits)
  }

  io.busy      := core.io.busy
  io.divByZero := core.io.divByZero

  // ── result fix-up at done ──
  val magQ = core.io.quotient            // 64-bit magnitude quotient
  val magR = core.io.remainder           // 32-bit magnitude remainder
  val qNeg = signedReg && (signDividend ^ signDivisor)
  val rNeg = signedReg && signDividend   // remainder takes the dividend's sign

  // Apply signs to the low 32 bits (the EU narrows .W to 16). For overflow detection
  // we also need the FULL magnitude quotient (magQ is 64-bit).
  val q32mag = magQ(31 downto 0)
  val signedQ = Mux(qNeg, (~q32mag + 1), q32mag)
  val signedR = Mux(rNeg, (~magR + 1), magR)
  io.quotient  := signedQ
  io.remainder := signedR

  // ── overflow detection per form ──
  // The result fits if the SIGNED/UNSIGNED quotient is representable in the dest width.
  // We check the MAGNITUDE quotient (magQ, 64-bit) against the form's limit, accounting
  // for sign (signed allows one extra negative value: INT_MIN).
  def overflowFor(qw: Int): Bool = {
    val ov = Bool()
    // bits above the dest width must be zero for the magnitude to fit at all.
    val hiZero = magQ(63 downto qw) === 0
    when(!signedReg) {
      // unsigned: magnitude must fit in qw bits.
      ov := !hiZero
    } otherwise {
      // signed: |q| <= 2^(qw-1) when negative (allows INT_MIN), |q| <= 2^(qw-1)-1 when
      // positive. Equivalent: magnitude fits in qw bits AND (positive ? mag < 2^(qw-1)
      // : mag <= 2^(qw-1)).
      val magLow = magQ(qw - 1 downto 0)
      val topBit = magLow(qw - 1)                  // 2^(qw-1) bit of the magnitude
      val restNZ = magLow(qw - 2 downto 0).orR     // any bit below the top
      // mag == 2^(qw-1) exactly: topBit set, rest zero.
      val isPow  = topBit && !restNZ
      // positive overflow if mag >= 2^(qw-1) (topBit set, regardless of rest).
      // negative overflow if mag > 2^(qw-1) (topBit set AND rest nonzero).
      val posOv = topBit
      val negOv = topBit && restNZ
      ov := !hiZero || Mux(qNeg, negOv, posOv)
    }
    ov
  }
  val ovW   = overflowFor(16)
  val ovL   = overflowFor(32)
  // 68020+ DIVS.W/DIVS.L/DIVU.L divide-by-(-1) erratum (documented real-silicon behavior,
  // matched by Musashi -- our lock-step/whitebox oracle, tools/musashi/musashi/
  // m68kops.c m68k_op_divl_32_d): dividing by exactly -1 does NOT set V for one
  // EXACT dividend pattern per form, even though the mathematical result overflows.
  // Musashi's own source has TWO SEPARATE special cases with DIFFERENT dividend
  // patterns -- this is NOT one blanket "any divisor==-1" rule:
  //   L32 (32-bit dividend, BIT_A=0 path): `dividend_lo == 0x80000000` (i.e. the
  //     32-bit dividend IS exactly INT32_MIN -- the only dividend for which a 32-bit
  //     value divided by -1 can overflow at all, so this is equivalent to "any
  //     signed L32 divide by -1").
  //   L64 (64-bit dividend, BIT_A=1 path): `dividend_hi == 0 && dividend_lo ==
  //     0x80000000` -- i.e. the RAW 64-bit dividend bit pattern is EXACTLY
  //     0x00000000_80000000 (+2^31, NOT INT64_MIN = 0x80000000_00000000). This is a
  //     narrow, essentially-arbitrary Musashi implementation quirk, NOT "any negative
  //     divisor" -- unlike L32, a 64-bit dividend divided by -1 overflows for MANY
  //     different dividend values (any |dividend| > 2^31-ish), and Musashi does NOT
  //     suppress V for those -- only for this one exact pattern.
  //   W (16-bit dividend, 32-bit r_dst): Musashi's `M68KMAKE_OP(divs, 16, ., .)` uses
  //     the SAME trigger as L32 -- `*r_dst == 0x80000000 && src == -1` -- i.e. the
  //     dividend check is against the FULL 32-bit Dn (not just its low 16 bits), so
  //     `l32DividendIsIntMin` (already computed for L32) is the right predicate here
  //     too. Musashi's override for .W is a DIFFERENT shape than .L's, though: it
  //     forces the WHOLE 32-bit dest to 0 (Z=1,N=0,V=0,C=0), not "keep the mathematical
  //     quotient and just clear V" (that's what .L does, and it's correct there because
  //     INT32_MIN happens to be a representable 32-bit quotient). For .W this needs NO
  //     separate override path, though: the ordinary magnitude divide already produces
  //     magQ=0x0000000080000000 (0x80000000 magnitude / 1), and the EU packs the .W
  //     result from the LOW 16 BITS of quotient/remainder (divResultW = resR[15:0] ##
  //     resQ[15:0]) -- 0x80000000's low 16 bits are 0x0000 and the remainder is exactly
  //     0, so the generic non-overflow datapath already yields dest=0x00000000 and
  //     N=0/Z=1 for free. Suppressing V here (exactly like L32) is therefore sufficient;
  //     divs_word_intmin_minus1.s pins this. task divs_word_intmin_minus1: the sibling
  //     v1 project's mul_div.v only ever special-cased the .L form (`divsl_special`,
  //     gated on is_divl_signed) -- DIVS.W fell through to the generic overflow check
  //     there and silently left the destination unchanged with V=1. This datapath does
  //     NOT share that structure (there is one shared `overflowFor`/erratum mux for all
  //     three forms), but the W arm was still missing the suppression term before this
  //     fix -- same observable bug, different root cause.
  // Task #167 (ported-tests triage, divl_sz1_overflow.s Test 2) FIXED a regression
  // from task #149's original fix: task #149 validated only the L32 case (divl_basic.s
  // Test 5, dividend=INT32_MIN) and over-generalized the suppression to "any signed
  // divisor==-1" for L64 too, which incorrectly ALSO suppressed V for INT64_MIN/-1 (a
  // completely different dividend pattern that Musashi does NOT special-case) --
  // silently producing a WRONG V=0 instead of the correct V=1. Ported-tests triage
  // (divl_basic.s Test 5, task #149): without SOME suppression for the L32 case, the
  // overflow path's writeInt=False left the DIV's (and the trailing DIVREM's)
  // destination physical register permanently not-ready in the scoreboard (no
  // wakeup ever fires for a register nothing ever writes), deadlocking the ROB head
  // on the very next consumer -- not a "hang in the divider" (DivCore is a fixed
  // 64-cycle iterator, always completes) but a downstream scoreboard-starvation hang
  // caused by taking this overflow branch in a case Musashi does not. (That hazard is
  // now ALSO closed generally, independent of this erratum, via DIVREM's own real
  // srcA=Dr fix -- see DivEuPlugin.scala's isDivRem branch -- so an L64 overflow that
  // legitimately DOES set V, like INT64_MIN/-1, no longer deadlocks either.)
  // (divisorIsNegOneReg/l32DividendIsIntMinReg/l64DividendIsExactReg latched at `start`
  // above -- see their declaration comment; task #253 shrunk this from two full-width
  // source registers (96 bits) to the 3 booleans actually consumed here.)
  io.overflow := formReg.mux(
    DivForm.W   -> (ovW && !(divisorIsNegOneReg && l32DividendIsIntMinReg)),
    DivForm.L32 -> (ovL && !(divisorIsNegOneReg && l32DividendIsIntMinReg)),
    DivForm.L64 -> (ovL && !(divisorIsNegOneReg && l64DividendIsExactReg)))

  io.done := core.io.done
}
