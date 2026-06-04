package m68k040.rob

import spinal.core._

/** One retired instruction's commit + free info: rename writes committed RAT, frees old pdsts. */
case class CommitSlot() extends Bundle {
  val intArch  = UInt(5 bits); val intNew  = UInt(6 bits); val intOld  = UInt(6 bits); val intWrite  = Bool()
  val nzvcNew  = UInt(4 bits); val nzvcOld = UInt(4 bits); val nzvcWrite = Bool()
  val xNew     = UInt(4 bits); val xOld    = UInt(4 bits); val xWrite    = Bool()
}

/** Per-completion committed-CCR VALUE record: an EU reports the {N,Z,V,C} + X
  * VALUES (not phys IDs) for a completing CCR-writer, keyed by robId. The ROB
  * records it per-entry and folds it into the committed CCR at retire (used by the
  * exception FSM's stacked frame SR low byte). */
case class CcrCompletion() extends Bundle {
  val robId     = UInt(6 bits)
  val nzvc      = UInt(4 bits); val nzvcWrite = Bool()
  val x         = Bool();       val xWrite    = Bool()
}
