package m68k040.execute

import spinal.core._
import spinal.lib._

object MulCore {
  /** Number of registered valid/data stages from an accepted `start` through `done`.
    * Keep the descriptor pipe in DivEuPlugin exactly aligned with this constant. */
  val Latency = 4
}

/** DSP48-mappable 32x32 -> 64 multiplier (signed/unsigned), validated standalone in
  * MulCoreSpec. Two 32-bit source operands only (no 3rd source — unlike DIV 64/32),
  * so it does not touch the CPLX EU's psrcC operand-delivery cone.
  *
  * The product uses an A/B/M/P-shaped four-register pipeline so Vivado can use the
  * DSP48E2 input, multiply, and product registers rather than leaving a 33x33
  * combinational cone in front of one output register. To get ONE multiply datapath
  * that covers both MULU and MULS, the operands are sign/zero-extended to 33 bits per
  * `signed` and a single signed 33x33 multiply is registered; the low 64 bits are the
  * two's-complement product for either signedness. Vivado tiles the 33x33 across a few
  * DSP48E2 slices.
  *
  * Latency: [[MulCore.Latency]] registered stages, initiation interval 1. `start`
  * may be asserted on consecutive cycles; `done` reproduces that valid pattern after
  * the fixed delay and products stay in input order. The EU narrows to .W/.L32/.L64
  * and computes N/Z/V. For the .W form the EU presents the 16-bit operands already
  * sign/zero-extended to 32 bits, so the full 32-bit .W product is prodLo.
  */
class MulCore extends Component {
  val io = new Bundle {
    val start  = in Bool ()
    val a      = in Bits (32 bits)
    val b      = in Bits (32 bits)
    val signed = in Bool ()
    val busy   = out Bool ()
    val done   = out Bool ()             // 1-cycle pulse when the product is valid
    val prodLo = out Bits (32 bits)      // product[31:0]
    val prodHi = out Bits (32 bits)      // product[63:32]
  }

  // Extend each operand to 33 bits: signed -> sign bit; unsigned -> 0. A single
  // signed 33x33 multiply then yields the correct two's-complement low-64 product
  // for both MULU and MULS.
  val aExt = (Mux(io.signed, io.a(31), False) ## io.a).asSInt   // 33-bit signed
  val bExt = (Mux(io.signed, io.b(31), False) ## io.b).asSInt   // 33-bit signed

  // A/B stage 0, second DSP input stage 1, multiply stage 2, product stage 3.
  // Two operand stages intentionally match DSP48E2 AREG/BREG depth=2.  The
  // multiplication and final product registers target MREG/PREG respectively.
  val a0 = Reg(SInt(33 bits)); val b0 = Reg(SInt(33 bits))
  val a1 = Reg(SInt(33 bits)); val b1 = Reg(SInt(33 bits))
  val mul2 = Reg(SInt(66 bits))
  val prod3 = Reg(SInt(66 bits))
  val valid = Vec.fill(MulCore.Latency)(RegInit(False))

  valid(0) := io.start
  when(io.start) {
    a0 := aExt
    b0 := bExt
  }
  for (i <- 1 until MulCore.Latency) valid(i) := valid(i - 1)
  when(valid(0)) {
    a1 := a0
    b1 := b0
  }
  when(valid(1)) {
    mul2 := a1 * b1
  }
  when(valid(2)) {
    prod3 := mul2
  }

  val prod64 = prod3.asBits(63 downto 0)
  io.busy   := False                     // fixed pipeline never blocks a new start
  io.done   := valid.last
  io.prodLo := prod64(31 downto 0)
  io.prodHi := prod64(63 downto 32)
}
