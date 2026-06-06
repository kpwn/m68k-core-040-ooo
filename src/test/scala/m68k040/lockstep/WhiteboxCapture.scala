package m68k040.lockstep

import scala.collection.mutable

/** Reconstructs the per-retired-instruction CommitObservation stream from the
  * sim-only whitebox: EU writeback-obs (value+flags by robId) joined with the
  * ROB commit-obs (retire order + pc), folding the architectural CCR in commit
  * order. NaxRiscv-style: the synthesizable commit path does no RF reads.
  *
  * CCR bit layout: X=4, N=3, Z=2, V=1, C=0. The EU's `nzvc` is the 4-bit
  * {N(bit3),Z(bit2),V(bit1),C(bit0)} field -> it is directly the low 4 CCR bits.
  */
object WhiteboxCapture {

  /** One EU writeback observation (the value + flags + write masks for a robId). */
  final case class Wb(dstArch: Int, result: Long, intWrite: Boolean,
                      nzvc: Int, nzvcWrite: Boolean, x: Int, xWrite: Boolean)

  /** Stateful reconstruction handle. Drive `onWb` for every cycle an EU's wbObs
    * is valid, and `onCommit` for every fired ROB commit-obs (in retire order).
    * Read `result` after the program drains. */
  /** A normal commit (Wb-backed) or an exception/RTE commit (SR/A7 directly). */
  private sealed trait Rec
  private final case class NormRec(pc: Long, sysByte: Int, a7: Long, wb: Wb) extends Rec
  // ExcRec: an exception/trap ENTRY or RTE step. `foldNzvc` (>=0) folds the FAULTING
  // instruction's own NZVC write onto the running CCR before this step — needed for
  // CHK, which sets N as it traps but never retires normally (so its Wb is never
  // folded by a NormRec). -1 => no fold (TRAPV/DIV0/access-fault/interrupt/RTE leave
  // the running CCR as-is; RTE restores the same CCR the entry saved, which the
  // running fold already reflects).
  private final case class ExcRec(pc: Long, sysByte: Int, a7: Long, foldNzvc: Int) extends Rec

  final class Handle {
    private val wbMap   = mutable.HashMap[Int, Wb]()
    private val commits = mutable.ArrayBuffer[Rec]()

    /** Record an EU writeback (keyed by robId). */
    def onWb(robId: Int, wb: Wb): Unit = { wbMap(robId) = wb }

    /** Record a retired NORMAL commit (robId + post-instruction pc + the committed
      * SR system byte + A7), snapshotting the committing instruction's writeback. A
      * cracked-load temp µop (arch ≥ 16, no flags) is DROPPED (decode §4.5). */
    def onCommit(robId: Int, pc: Long, sysByte: Int = 0x27, a7: Long = -1L): Unit = {
      val wb = wbMap.getOrElse(robId,
        sys.error(s"commit robId=$robId with no writeback observed"))
      val isTempOnly = wb.intWrite && wb.dstArch >= 16 && !wb.nzvcWrite && !wb.xWrite
      if (!isTempOnly) commits += NormRec(pc, sysByte, a7, wb)
    }

    /** Record an exception / RTE "instruction" commit: the handler-entry / restored
      * PC + the post-event SR system byte + A7. CCR is unchanged (carried over). */
    def onExcCommit(pc: Long, sysByte: Int, a7: Long, foldNzvc: Int = -1): Unit =
      commits += ExcRec(pc, sysByte, a7, foldNzvc)

    /** Reconstruct the CommitObservation stream AFTER the run: fold the CCR over Wb
      * snapshots and combine with the per-commit SR system byte + A7 into the full
      * 16-bit SR. */
    def result: Seq[CommitObservation] = {
      var ccr = 0 // running architectural CCR (X N Z V C), bit4..bit0
      commits.toSeq.map {
        case NormRec(pc, sysByte, a7, wb) =>
          if (wb.nzvcWrite) ccr = (ccr & 0x10) | (wb.nzvc & 0xf)
          if (wb.xWrite)    ccr = (ccr & 0x0f) | ((wb.x & 1) << 4)
          CommitObservation(
            pc = pc, archRegId = wb.dstArch,
            archRegWrite = wb.result, archRegValid = wb.intWrite,
            ccr = ccr, memAddr = 0, memData = 0, memWrite = false,
            sr = ((sysByte & 0xff) << 8) | (ccr & 0x1f), a7 = a7)
        case ExcRec(pc, sysByte, a7, foldNzvc) =>
          // Fold the faulting instruction's own NZVC (CHK) onto the running CCR before
          // the entry step; -1 => no fold (the running CCR already reflects the
          // architectural state for TRAPV/DIV0/access-fault/interrupt/RTE).
          if (foldNzvc >= 0) ccr = (ccr & 0x10) | (foldNzvc & 0xf)
          CommitObservation(
            pc = pc, archRegId = 0, archRegWrite = 0, archRegValid = false,
            ccr = ccr, memAddr = 0, memData = 0, memWrite = false,
            sr = ((sysByte & 0xff) << 8) | (ccr & 0x1f), a7 = a7)
      }
    }
  }
}
