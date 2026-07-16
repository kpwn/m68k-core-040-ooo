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
  // Divisor magnitude, latched at start (used by the divide-by-(-1) erratum check
  // below -- see its comment). Not strictly required for correctness given io.divisor
  // is held stable by the EU for the whole DIVING state, but latching mirrors the
  // existing sign-capture pattern and avoids depending on that assumption.
  val dvsrMagReg   = Reg(UInt(32 bits))

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
    dvsrMagReg   := dvsrMag
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
  // 68020+ DIVS.L/DIVU.L divide-by-(-1) erratum (documented real-silicon behavior,
  // matched by Musashi -- our lock-step/whitebox oracle): dividing by exactly -1 in
  // the 32-bit-quotient LONG divide form does NOT set V even when the mathematical
  // result overflows (INT_MIN / -1 being the canonical case). Scoped to the LONG
  // forms only -- the original .W 16-bit-quotient divide is not documented/tested as
  // sharing this erratum. Ported-tests triage (divl_basic.s Test 5): without this,
  // the overflow path's writeInt=False left the DIV's (and the trailing DIVREM's)
  // destination physical register permanently not-ready in the scoreboard (no
  // wakeup ever fires for a register nothing ever writes), deadlocking the ROB head
  // on the very next consumer -- not a "hang in the divider" (DivCore is a fixed
  // 64-cycle iterator, always completes) but a downstream scoreboard-starvation hang
  // caused by taking this overflow branch in a case Musashi does not.
  val divisorIsNegOne = signedReg && signDivisor && (dvsrMagReg === U(1, 32 bits))
  io.overflow := formReg.mux(
    DivForm.W   -> ovW,
    DivForm.L32 -> (ovL && !divisorIsNegOne),
    DivForm.L64 -> (ovL && !divisorIsNegOne))

  io.done := core.io.done
}
