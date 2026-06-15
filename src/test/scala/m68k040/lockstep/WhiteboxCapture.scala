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

  /** One EU writeback observation (the value + flags + write masks for a robId).
    * `divRem` marks the trailing DIVREM crack µop -> its commit is DROPPED (the 2-µop
    * DIVU.L/DIVS.L maps to ONE oracle instruction step; the Dr write still lands in the
    * PRF and is verified by a later instruction that reads Dr). */
  final case class Wb(dstArch: Int, result: Long, intWrite: Boolean,
                      nzvc: Int, nzvcWrite: Boolean, x: Int, xWrite: Boolean,
                      divRem: Boolean = false, keepCommit: Boolean = false)

  /** Stateful reconstruction handle. Drive `onWb` for every cycle an EU's wbObs
    * is valid, and `onCommit` for every fired ROB commit-obs (in retire order).
    * Read `result` after the program drains. */
  /** A normal commit (Wb-backed) or an exception/RTE commit (SR/A7 directly). */
  private sealed trait Rec
  // `emit` = produce a CommitObservation for this record. A DROPPED crack µop
  // (temp-only load / DIVREM / stack-push store) sets emit=false: it does NOT add an
  // oracle-aligned step, but its arch-reg write (e.g. a stack-push store's A7 = A7-4)
  // STILL folds into the running architectural state (A7 + CCR) so the NEXT kept
  // record carries it. `a7Static` is the ROB-surfaced ss.a7 (used only for exception
  // steps / when no OoO A7 write has been seen).
  private final case class NormRec(pc: Long, sysByte: Int, a7Static: Long, wb: Wb, emit: Boolean, msp: Long = -1L, isp: Long = -1L) extends Rec
  // ExcRec: an exception/trap ENTRY or RTE step. `foldNzvc` (>=0) folds the FAULTING
  // instruction's own NZVC write onto the running CCR before this step — needed for
  // CHK, which sets N as it traps but never retires normally (so its Wb is never
  // folded by a NormRec). -1 => no fold (TRAPV/DIV0/access-fault/interrupt/RTE leave
  // the running CCR as-is; RTE restores the same CCR the entry saved, which the
  // running fold already reflects).
  // setCcr5 >= 0 => MOVE-to-SR's ABSOLUTE 5-bit CCR write (X N Z V C); the running CCR
  // is SET to it (vs the per-bit NZVC fold). -1 => no absolute CCR write.
  private final case class ExcRec(pc: Long, sysByte: Int, a7: Long, foldNzvc: Int, setCcr5: Int = -1, msp: Long = -1L, isp: Long = -1L) extends Rec

  final class Handle {
    private val wbMap   = mutable.HashMap[Int, Wb]()
    private val commits = mutable.ArrayBuffer[Rec]()

    /** Record an EU writeback (keyed by robId). */
    def onWb(robId: Int, wb: Wb): Unit = { wbMap(robId) = wb }

    /** Debug-only: peek the last-observed wb for a robId (None if never observed). */
    def peekWb(robId: Int): Option[Wb] = wbMap.get(robId)

    /** Record a retired NORMAL commit (robId + post-instruction pc + the committed
      * SR system byte + A7), snapshotting the committing instruction's writeback. A
      * cracked-load temp µop (arch ≥ 16, no flags) is DROPPED (decode §4.5). */
    def onCommit(robId: Int, pc: Long, sysByte: Int = 0x27, a7: Long = -1L, msp: Long = -1L, isp: Long = -1L): Unit = {
      val wb = wbMap.getOrElse(robId,
        sys.error(s"commit robId=$robId with no writeback observed"))
      val isTempOnly = wb.intWrite && wb.dstArch >= 16 && !wb.nzvcWrite && !wb.xWrite
      // DROP (emit=false) the crack µops that are NOT their own oracle step: a temp-only
      // load (cracked load -> T0), the trailing DIVREM, and a stack-push store (the
      // BSR/JSR leading push; its A7 write still folds). Their reg writes still fold
      // into the running architectural A7/CCR (handled in `result`).
      // EXCEPTION: a `keepCommit` op (the mem-dest MOVE-from-CCR/SR op µop -> T1) IS the
      // macro instruction's single kept oracle step (its trailing store is an rmwStore
      // drop), so keep it even though it writes only a temp.
      val emit = (!isTempOnly && !wb.divRem) || wb.keepCommit
      commits += NormRec(pc, sysByte, a7, wb, emit, msp, isp)
    }

    /** Record an exception / RTE "instruction" commit: the handler-entry / restored
      * PC + the post-event SR system byte + A7. CCR is unchanged (carried over). */
    def onExcCommit(pc: Long, sysByte: Int, a7: Long, foldNzvc: Int = -1, setCcr5: Int = -1, msp: Long = -1L, isp: Long = -1L): Unit =
      commits += ExcRec(pc, sysByte, a7, foldNzvc, setCcr5, msp, isp)

    /** Reconstruct the CommitObservation stream AFTER the run: fold the CCR over Wb
      * snapshots and combine with the per-commit SR system byte + A7 into the full
      * 16-bit SR. */
    def result: Seq[CommitObservation] = {
      var ccr = 0 // running architectural CCR (X N Z V C), bit4..bit0
      // Running architectural A7 (arch reg 15). Seeded lazily to the first surfaced
      // ss.a7 (the boot SSP); thereafter it FOLLOWS OoO arch-15 writes (call/return,
      // MOVE-to-A7) — the synthesizable ss.a7 only tracks EXCEPTION A7 changes, so the
      // OoO A7 must be reconstructed from the whitebox writes here.
      var a7Run: Long = -1L
      // The committed ss.a7 (a7Static) tracks ONLY exception/RTE/boot A7 changes (the
      // exc unit writes it + PRF arch-15). OoO arch-15 writes (call/return, MOVE-to-A7)
      // are NOT in ss.a7; they are reconstructed from the whitebox arch-15 wb. So:
      // resync a7Run to a7Static whenever a7Static CHANGES (exc/boot moved A7), and
      // otherwise fold OoO arch-15 wb writes. (An OoO write leaves ss.a7 unchanged, so
      // it never triggers a spurious resync.)
      var lastA7Static: Long = -2L
      commits.toSeq.flatMap {
        case NormRec(pc, sysByte, a7Static, wb, emit, msp, isp) =>
          if (a7Static >= 0 && a7Static != lastA7Static) { a7Run = a7Static & 0xffffffffL; lastA7Static = a7Static }
          if (a7Run < 0 && a7Static >= 0) a7Run = a7Static & 0xffffffffL
          if (wb.nzvcWrite) ccr = (ccr & 0x10) | (wb.nzvc & 0xf)
          if (wb.xWrite)    ccr = (ccr & 0x0f) | ((wb.x & 1) << 4)
          // Fold an arch-15 (A7) int write into the running A7 (even for a dropped
          // crack µop like the stack-push store).
          if (wb.intWrite && wb.dstArch == 15) a7Run = wb.result & 0xffffffffL
          // A TEMP destination (arch >= 16, e.g. the mem-dest RMW op µop -> T1) is NOT
          // architectural: emit the step (it carries the instruction PC + the folded
          // CCR — the RMW's flags), but force archRegValid=False so the comparator does
          // NOT index a non-existent oracle register (D/A are 0..15). The memory effect
          // is checked separately via checkMem.
          val isTemp = wb.intWrite && wb.dstArch >= 16
          if (!emit) Nil
          else Seq(CommitObservation(
            pc = pc, archRegId = if (isTemp) 0 else wb.dstArch,
            archRegWrite = if (isTemp) 0L else wb.result, archRegValid = wb.intWrite && !isTemp,
            ccr = ccr, memAddr = 0, memData = 0, memWrite = false,
            sr = ((sysByte & 0xff) << 8) | (ccr & 0x1f), a7 = a7Run, msp = msp, isp = isp))
        case ExcRec(pc, sysByte, a7, foldNzvc, setCcr5, msp, isp) =>
          // An exception/RTE step uses the ROB-surfaced ss.a7 (the exc unit's banked
          // A7); resync the running A7 to it (+ lastA7Static so the next NormRec, which
          // carries the SAME ss.a7, does not re-resync over a subsequent OoO write).
          if (a7 >= 0) { a7Run = a7 & 0xffffffffL; lastA7Static = a7 }
          // Fold the faulting instruction's own NZVC (CHK) onto the running CCR before
          // the entry step; -1 => no fold (the running CCR already reflects the
          // architectural state for TRAPV/DIV0/access-fault/interrupt/RTE).
          if (foldNzvc >= 0) ccr = (ccr & 0x10) | (foldNzvc & 0xf)
          // MOVE-to-SR's ABSOLUTE 5-bit CCR write SETS the running CCR (X N Z V C).
          if (setCcr5 >= 0) ccr = setCcr5 & 0x1f
          Seq(CommitObservation(
            pc = pc, archRegId = 0, archRegWrite = 0, archRegValid = false,
            ccr = ccr, memAddr = 0, memData = 0, memWrite = false,
            sr = ((sysByte & 0xff) << 8) | (ccr & 0x1f), a7 = a7, msp = msp, isp = isp))
      }
    }
  }
}
