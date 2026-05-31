package m68k040

import spinal.lib.misc.database.Database

/** Single registry of Database keys. INVARIANT #3: every key has exactly one
  * producer plugin (documented in the comment). `blocking` keys make readers
  * await the producer; `value` keys are plain. */
object Global {
  // Producer: ParamPlugin (setup phase)
  val ROB_DEPTH       = Database.blocking[Int]()
  val PHYS_INT_REGS   = Database.blocking[Int]()
  val PHYS_NZVC_REGS  = Database.blocking[Int]()
  val PHYS_X_REGS     = Database.blocking[Int]()
  val DECODE_WIDTH    = Database.blocking[Int]()
  val RETIRE_WIDTH    = Database.blocking[Int]()
}
