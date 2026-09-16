package m68k040.rob

import spinal.core._

/** One retired instruction's commit + free info: rename writes committed RAT, frees old pdsts. */
case class CommitSlot() extends Bundle {
  val intArch  = UInt(5 bits); val intNew  = UInt(6 bits); val intOld  = UInt(6 bits); val intWrite  = Bool()
  val nzvcNew  = UInt(4 bits); val nzvcOld = UInt(4 bits); val nzvcWrite = Bool()
  val xNew     = UInt(4 bits); val xOld    = UInt(4 bits); val xWrite    = Bool()
  // FP data (8 arch FP0-FP7, 16 physical) + FPCC (N/Z/I/NAN, archDepth=1) commit
  // info -- mirrors intArch/intNew/intOld/intWrite and nzvcNew/nzvcOld/nzvcWrite
  // exactly. fpArchDst is the architectural FP dest reg (0..7) for the FP RAT
  // commit-port address (mirrors intArch); FPCC has no arch-addr field since its
  // RAT commit address is always hardwired to 0 (archDepth=1, like nzvc/x).
  val fpArchDst = UInt(3 bits)
  val fpNew    = UInt(4 bits); val fpOld    = UInt(4 bits); val fpWrite    = Bool()
  val fpccNew  = UInt(4 bits); val fpccOld  = UInt(4 bits); val fpccWrite  = Bool()
}

/** Per-completion committed-CCR VALUE record: an EU reports the {N,Z,V,C} + X
  * VALUES (not phys IDs) for a completing CCR-writer, keyed by robId. The ROB
  * records it per-entry and folds it into the committed CCR at retire (used by the
  * exception FSM's stacked frame SR low byte). */
case class CcrCompletion() extends Bundle {
  val robId     = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
  val nzvc      = UInt(4 bits); val nzvcWrite = Bool()
  val x         = Bool();       val xWrite    = Bool()
  // The EU writeback VALUE (wbObs.result) + intWrite, captured per-ROB-entry. Used by
  // the commit-time PRIVILEGED SYSTEM ops (MOVE-to-SR / MOVE-USP-write / MOVEC-write):
  // their op µop is a MOVE (result = the source register), so wbObs.result IS the
  // value the ExceptionUnit writes into the committed system state (srSys/usp/vbr/...).
  // For non-sysOps the ROB ignores it. Default 0/False if unwired.
  val result    = Bits(32 bits)
  val intWrite  = Bool()
}
