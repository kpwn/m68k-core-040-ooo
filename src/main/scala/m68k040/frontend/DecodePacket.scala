package m68k040.frontend

import spinal.core._

/** One m68k instruction handed to the (future) decode stage. */
case class DecodePacket() extends Bundle {
  val pc        = UInt(32 bits)
  val words     = Vec(Bits(16 bits), 5)   // opword + up to 4 following words (10 bytes max simple)
  val wordCount = UInt(3 bits)            // valid words in `words` (1..5)
  val simple    = Bool()
  val lenWords  = UInt(3 bits)            // predecode length in words (meaningful iff simple)
  val complex   = Bool()                  // !simple
  val fault     = Bool()
}
