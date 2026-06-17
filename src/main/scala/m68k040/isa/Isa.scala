package m68k040.isa

import spinal.core._

/** Architectural constants shared across plugins. m68k CCR layout is
  * bit4=X, bit3=N, bit2=Z, bit1=V, bit0=C. */
object Isa {
  // D0-D7 (0..7), A0-A7 (8..15), plus 3 internal temp arch regs T0/T1/T2 (16,17,18)
  // used by memory-EA cracking + the microcode engine's 5-byte bit-field RMW chain
  // (which needs 3 simultaneously-live temps {lo,hi,res}). log2Up(19) = 5 (same as
  // log2Up(18)) -> int arch reg ids stay 5 bits; only the RAT/Freelist DEPTH grows.
  val ARCH_INT_REGS = 19
  val DATA_WIDTH    = 32

  val CCR_C = 0
  val CCR_V = 1
  val CCR_Z = 2
  val CCR_N = 3
  val CCR_X = 4

  val MASK_NZVC = 0xF       // N,Z,V,C (bits 3..0)
  val MASK_X    = 0x10      // X (bit 4)
}

/** Operand/result access size. */
object Size extends SpinalEnum {
  val BYTE, WORD, LONG = newElement()
}

/** Memory operation carried by a µop. */
object MemOp extends SpinalEnum {
  val NONE, LOAD, STORE = newElement()
}

/** Scheduler cluster a µop is steered to (spec 4.8). */
object Cluster extends SpinalEnum {
  val INT, EA, LS, CPLX = newElement()
}
