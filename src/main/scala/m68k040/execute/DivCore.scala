package m68k040.execute

import spinal.core._
import spinal.lib._

/** Unsigned radix-2 restoring sequential divider core (standalone, validated vs a
  * Scala reference in DivDatapathSpec). Single-outstanding: pulse `start` with the
  * UNSIGNED magnitudes; it iterates `nIters` cycles (= the quotient width: 16 for
  * DIVx.W, 32 for DIVx.L) and pulses `done` with {quotient, remainder, divByZero}.
  *
  * Datapath: a (W+1)-bit running remainder `rem` is shifted left and the divisor is
  * trial-subtracted each cycle; the quotient bit is the subtract-succeeds flag. The
  * dividend MSBs feed the remainder; W = 64 covers the widest form (64-bit dividend).
  * The divisor occupies the low 32 bits. The per-cycle path is one (W+1)-bit compare/
  * subtract + a 2:1 mux (shallow), so FMax is dominated elsewhere.
  *
  * OVERFLOW is NOT decided here (it depends on the form + sign): the EU compares the
  * produced quotient against the destination width. divByZero is flagged directly.
  *
  * `nIters` selects how many quotient bits to produce; the dividend is presented
  * left-justified so the first `nIters` shifts cover the meaningful dividend bits:
  *   - DIVU.W : 32-bit dividend in bits[31:0], divisor 16-bit, nIters=16, but the
  *              dividend is the FULL 32 bits -> we always run 32 shift positions for
  *              the .W/.L32 forms and 64 for .L64. To keep one core we ALWAYS run
  *              `dividendBits` shifts and read the low `nIters`... simpler: we run a
  *              fixed 64 shifts over a 64-bit dividend and the quotient is the full
  *              64-bit result; the EU picks the low bits + checks overflow. (Restoring
  *              over the full width is exact for every form.)
  */
class DivCore extends Component {
  val io = new Bundle {
    val start     = in Bool ()
    val dividend  = in UInt (64 bits)   // unsigned magnitude
    val divisor   = in UInt (32 bits)   // unsigned magnitude
    val busy      = out Bool ()
    val done      = out Bool ()         // 1-cycle pulse when a divide finishes
    val quotient  = out UInt (64 bits)  // full 64-bit quotient (EU narrows + overflow-checks)
    val remainder = out UInt (32 bits)  // 32-bit remainder
    val divByZero = out Bool ()
  }

  // Restoring divider over the full 64-bit dividend: 64 iterations. The running
  // remainder is 33 bits (enough to hold a shifted-in bit above a 32-bit value for
  // the trial subtract against the 32-bit divisor).
  val rem  = Reg(UInt(33 bits)) init 0    // running partial remainder
  val quot = Reg(UInt(64 bits)) init 0    // accumulated quotient
  val divd = Reg(UInt(64 bits)) init 0    // remaining dividend bits (shifted out MSB-first)
  val dvsr = Reg(UInt(32 bits)) init 0
  val cnt  = Reg(UInt(7 bits)) init 0     // 0..64
  val running = RegInit(False)
  val dz   = RegInit(False)
  val donePulse = RegInit(False)

  io.busy      := running
  io.done      := donePulse
  io.quotient  := quot
  io.remainder := rem(31 downto 0)
  io.divByZero := dz

  donePulse := False

  when(io.start && !running) {
    // Latch operands; divide-by-zero short-circuits (no iteration).
    when(io.divisor === 0) {
      dz        := True
      quot      := 0
      rem       := 0
      donePulse := True
      running   := False
    } otherwise {
      dz      := False
      rem     := 0
      quot    := 0
      divd    := io.dividend
      dvsr    := io.divisor
      cnt     := 0
      running := True
    }
  } elsewhen(running) {
    // One restoring step: shift the next dividend MSB into rem, trial-subtract dvsr.
    val shifted = (rem(31 downto 0) ## divd(63)).asUInt        // 33 bits
    val sub     = shifted - (False ## dvsr).asUInt             // 33-bit subtract
    val fits    = !sub(32)                                     // no borrow -> divisor fits
    rem  := Mux(fits, sub, shifted)
    quot := (quot(62 downto 0) ## fits).asUInt                 // quotient bit = fits
    divd := (divd(62 downto 0) ## False).asUInt                // shift dividend left
    cnt  := cnt + 1
    when(cnt === U(63)) {
      running   := False
      donePulse := True
    }
  }
}
