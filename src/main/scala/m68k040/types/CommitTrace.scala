package m68k040.types

import spinal.core._

/** Designed-in observability (spec invariant #5). One pulse per retired
  * instruction, consumed by the lock-step harness which compares architectural
  * state against the reference model at the ROB retire boundary. */
case class CommitTrace() extends Bundle {
  val fire         = Bool()          // a single instruction retired this cycle
  val pc           = UInt(32 bits)   // POST-instruction PC (next-instruction addr); must match Musashi --trace for lock-step
  val opword       = Bits(16 bits)
  val archRegId    = UInt(5 bits)
  val archRegWrite = Bits(32 bits)
  val archRegValid = Bool()
  val ccr          = Bits(5 bits)    // X,N,Z,V,C after this instruction
  val ccrValid     = Bool()
  val memAddr      = UInt(32 bits)
  val memData      = Bits(32 bits)
  val memWrite     = Bool()
  val excTaken     = Bool()
  val excVector    = UInt(8 bits)
}
