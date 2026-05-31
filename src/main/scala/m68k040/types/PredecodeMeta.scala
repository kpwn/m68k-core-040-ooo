package m68k040.types

import spinal.core._

/** Rich predecode metadata produced in the PD stage (spec 5.1 / "Frontend
  * Metadata"). Drives scheduling and retirement decisions. */
case class PredecodeMeta() extends Bundle {
  val length      = UInt(3 bits)     // instruction length in words (1..5)
  val simple      = Bool()           // simple (fast path) vs complex (microcode)
  val branchType  = Bits(3 bits)     // none/Bcc/BSR/JMP/JSR/RTS/RTR/DBcc encoding
  val isCall      = Bool()           // BSR/JSR -> RAS push
  val isReturn    = Bool()           // RTS/RTR -> RAS pop
  val usesMem     = Bool()
  val writesMem   = Bool()
  val mayTrap     = Bool()
  val ccrReadMask = Bits(5 bits)     // X,N,Z,V,C consumed
  val ccrWriteMask= Bits(5 bits)     // X,N,Z,V,C produced
  val eaClass     = Bits(4 bits)     // EA addressing-mode class
}
