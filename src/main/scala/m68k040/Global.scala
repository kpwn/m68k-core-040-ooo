package m68k040

import spinal.lib.misc.database.Database

/** Single registry of Database keys. INVARIANT #3: every key has exactly one
  * producer plugin (documented in the comment). `blocking` keys make readers
  * await the producer; `value` keys are plain. */
object Global {
  // Keys are added here as the consuming plugins are written; see M68kParams
  // for the full parameter set. The framework does NOT throw on double-set —
  // invariant #3 (one producer per key) is a convention, not runtime-enforced.
  // Producer: ParamPlugin (setup phase)
  val ROB_DEPTH       = Database.blocking[Int]()
  val PHYS_INT_REGS   = Database.blocking[Int]()
  val PHYS_NZVC_REGS  = Database.blocking[Int]()
  val PHYS_X_REGS     = Database.blocking[Int]()
  val DECODE_WIDTH    = Database.blocking[Int]()
  val RETIRE_WIDTH    = Database.blocking[Int]()
  val L1I_KB          = Database.blocking[Int]()
  val L1I_WAYS        = Database.blocking[Int]()
  val L1I_LINE_BYTES  = Database.blocking[Int]()
  val BTB_ENTRIES     = Database.blocking[Int]()
  val RAS_ENTRIES     = Database.blocking[Int]()
  val GHR_BITS        = Database.blocking[Int]()
  val PHT_ENTRIES     = Database.blocking[Int]()
}
