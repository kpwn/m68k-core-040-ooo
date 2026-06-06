package m68k040.execute

import spinal.core._
import spinal.lib._

/** DSP48-mappable 32x32 -> 64 multiplier (signed/unsigned), validated standalone in
  * MulCoreSpec. Two 32-bit source operands only (no 3rd source — unlike DIV 64/32),
  * so it does not touch the CPLX EU's psrcC operand-delivery cone.
  *
  * The product is computed as a REGISTERED `a * b` so Vivado infers DSP48E2 hard
  * blocks (a LUT-built wide multiply would blow area + FMax). To get ONE multiply
  * datapath that covers both MULU and MULS, the operands are sign/zero-extended to
  * 33 bits per `signed` and a single signed 33x33 multiply is registered; the low 64
  * bits are the two's-complement product for either signedness. Vivado tiles the
  * 33x33 across a few DSP48E2 slices.
  *
  * Latency: 1 cycle. `start` (with {a, b, signed}) latches the operands and the
  * registered product lands the NEXT cycle, flagged by a 1-cycle `done` pulse. The
  * EU narrows to .W/.L32/.L64 and computes N/Z/V. For the .W form the EU presents the
  * 16-bit operands already sign/zero-extended to 32 bits, so the full 32-bit .W
  * product is prodLo (prodHi is the sign/zero extension, unused for .W).
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

  // REGISTERED product (DSP48E2 inference). 33x33 -> 66-bit signed; take low 64.
  val prodReg = Reg(SInt(66 bits))
  val validReg = RegInit(False)
  validReg := False
  when(io.start) {
    prodReg  := aExt * bExt
    validReg := True
  }

  val prod64 = prodReg.asBits(63 downto 0)
  io.busy   := False                     // 1-cycle (never blocks beyond `start`)
  io.done   := validReg
  io.prodLo := prod64(31 downto 0)
  io.prodHi := prod64(63 downto 32)
}
