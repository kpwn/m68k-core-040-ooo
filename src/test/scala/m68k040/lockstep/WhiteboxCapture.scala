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
  final class Handle {
    private val wbMap   = mutable.HashMap[Int, Wb]()
    // (pc, wb-snapshot) in retire order. The wb is SNAPSHOTTED at commit time, not
    // joined lazily at the end: under branch mispredict recovery a robId is REUSED
    // (a squashed wrong-path uop and a later correct uop share an index), so a
    // global last-writer map is ambiguous. At the cycle a commit fires, wbMap holds
    // the COMMITTING instruction's wb (it wrote before retiring; any earlier
    // squashed reuse was overwritten by the real producer; any FUTURE reuse of the
    // index has not happened yet). Snapshotting here is robId-reuse-correct.
    private val commits = mutable.ArrayBuffer[(Long, Wb)]()

    /** Record an EU writeback (keyed by robId). */
    def onWb(robId: Int, wb: Wb): Unit = { wbMap(robId) = wb }

    /** Record a retired commit (robId + post-instruction pc) in retire order,
      * snapshotting the committing instruction's writeback now (see above). */
    def onCommit(robId: Int, pc: Long): Unit = {
      val wb = wbMap.getOrElse(robId,
        sys.error(s"commit robId=$robId with no writeback observed"))
      commits += ((pc, wb))
    }

    /** Reconstruct the CommitObservation stream AFTER the run, folding the
      * architectural CCR in retire order over the per-commit wb snapshots. */
    def result: Seq[CommitObservation] = {
      var ccr = 0 // running architectural CCR (X N Z V C), bit4..bit0
      commits.toSeq.map { case (pc, wb) =>
        if (wb.nzvcWrite) ccr = (ccr & 0x10) | (wb.nzvc & 0xf)     // N,Z,V,C bits
        if (wb.xWrite)    ccr = (ccr & 0x0f) | ((wb.x & 1) << 4)   // X bit
        CommitObservation(
          pc = pc, archRegId = wb.dstArch,
          archRegWrite = wb.result, archRegValid = wb.intWrite,
          ccr = ccr, memAddr = 0, memData = 0, memWrite = false)
      }
    }
  }
}
