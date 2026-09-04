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
  /** `secondDst` marks a dropped crack µop that is the SECOND ARCHITECTURAL DESTINATION
    * of its macro-instruction, so its value must still be compared -- it is folded into
    * the preceding kept step's `archReg2*`. Set ONLY for the CPLX/DivEu writeback lane,
    * where `divRem` means exactly {DIVREM -> Dr, MULHI -> Dh}. It is deliberately NOT
    * derived from `divRem` alone: LsEuPlugin:2732 reuses that same bit as a GENERIC
    * "drop this commit record" marker (stack pushes, CCR restores, RMW stores, EA
    * auto-update drops) and AluEuPlugin:849 passes through `divIsRem` for its own cracked
    * µops (EXG's halves). Those carry MID-INSTRUCTION values, not second destinations, and
    * folding them produces spurious divergences (measured: 63 of 429 lock-step tests). */
  final case class Wb(dstArch: Int, result: Long, intWrite: Boolean,
                      nzvc: Int, nzvcWrite: Boolean, x: Int, xWrite: Boolean,
                      divRem: Boolean = false, keepCommit: Boolean = false,
                      secondDst: Boolean = false)

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
  // `macroLast` = the RETIRING µop is its macro-instruction's LAST µop (RobPayload.last
  // / DecodedUop.lastOfInstr, surfaced on the ROB commit-obs). It matters only for a
  // DROPPED record: a dropped LEADING µop (BSR/JSR/LINK push, cracked leading load,
  // source-EA anUpd ADD) folds FORWARD onto its own macro's kept record, which is
  // emitted later -- correct as-is; a dropped TRAILING µop folds forward onto the NEXT
  // macro's record, which is one instruction too late. See the `!emit` block in
  // `result` for the backward fold that fixes it.
  private final case class NormRec(pc: Long, sysByte: Int, a7Static: Long, wb: Wb, emit: Boolean, msp: Long = -1L, isp: Long = -1L, macroLast: Boolean = false) extends Rec
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
    // Incremental count of records that `result` will EMIT (== result.size), maintained
    // as commits arrive. `result` is a full O(n) refold of the whole buffer, so the
    // structural comparator -- which needs this number once per retire cycle -- must not
    // call `.result.size` to get it (that is O(n^2) over a run).
    private var emittedCount = 0
    def emitted: Int = emittedCount

    /** Record an EU writeback (keyed by robId). */
    def onWb(robId: Int, wb: Wb): Unit = { wbMap(robId) = wb }

    /** Debug-only: peek the last-observed wb for a robId (None if never observed). */
    def peekWb(robId: Int): Option[Wb] = wbMap.get(robId)

    /** Record a retired NORMAL commit (robId + post-instruction pc + the committed
      * SR system byte + A7), snapshotting the committing instruction's writeback. A
      * cracked-load temp µop (arch ≥ 16, no flags) is DROPPED (decode §4.5). */
    def onCommit(robId: Int, pc: Long, sysByte: Int = 0x27, a7: Long = -1L, msp: Long = -1L, isp: Long = -1L,
                 macroLast: Boolean = false): Unit = {
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
      commits += NormRec(pc, sysByte, a7, wb, emit, msp, isp, macroLast)
      if (emit) emittedCount += 1
    }

    /** Record an exception / RTE "instruction" commit: the handler-entry / restored
      * PC + the post-event SR system byte + A7. CCR is unchanged (carried over). */
    def onExcCommit(pc: Long, sysByte: Int, a7: Long, foldNzvc: Int = -1, setCcr5: Int = -1, msp: Long = -1L, isp: Long = -1L): Unit = {
      commits += ExcRec(pc, sysByte, a7, foldNzvc, setCcr5, msp, isp)
      emittedCount += 1   // an ExcRec always emits
    }

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
      // ── a7Static resync: EVENT-TRIGGERED, never on a bare value edge ────────────
      // The comment that used to live here claimed "ss.a7 tracks ONLY exception/RTE/boot
      // A7 changes ... an OoO write leaves ss.a7 unchanged, so it never triggers a
      // spurious resync". That premise is FALSE in the current RTL and was the direct
      // cause of fuzz cluster C (8/200 seeds). Verified against the RTL, not comments:
      //   ExceptionUnit.scala:734  `ss.writeA7.valid := True`  -- UNCONDITIONAL, every
      //                            cycle, from committedA7In (the int-PRF readback at
      //                            committedPhysA7, forceNoBypass).
      //   ExceptionUnit.scala:727  documents the consequence inline: "the ACTIVE bank
      //                            tracks A7 with ~1-2 cycle lag".
      //   SystemState.scala:70     `val a7 = Mux(s, supBank, usp)` -- a mux of those
      //                            continuously-written, lagging banks.
      //   RobPlugin.scala:2629     `obs(0).a7 := RegNext(exc.ss.a7)` -- one more cycle.
      // So ss.a7 mirrors EVERY A7 write (MOVEM/LINK/BSR/RTS included) and lags by >=2
      // commit cycles. Resyncing on a VALUE EDGE therefore replays the A7 history ~2
      // retirements late, on top of the correctly-folded live value. Because the resync
      // ran BEFORE the OoO fold below, a record carrying its own arch-15 writeback was
      // immune, and only a record that does NOT write A7 was exposed -- so two adjacent
      // A7-changing retirements (e.g. `movem -(%sp)` then `movem (%sp)+`) followed by any
      // ordinary instruction made that instruction report the STALE pre-pop A7 and then
      // self-correct one step later. Exactly the observed cluster-C signature.
      //
      // The fold below is authoritative for every ORDINARY A7 change (they are all real
      // arch-15 writebacks). a7Static is needed only for A7 movement that does NOT appear
      // as an arch-15 writeback, which is exactly:
      //   (1) exception entry / RTE  -> ExcRec resyncs directly;
      //   (2) an EXCEPTION FRAME PUSH -> the exc unit writes A7 straight into the PRF at
      //       committedPhysA7 (ExceptionUnit.scala:588) WITHOUT going through an EU, so it
      //       produces no wbObs and the fold structurally cannot see it. a7Static is the
      //       only witness. (A first attempt at this fix resynced only on a (S,M) bank
      //       switch and broke exactly here -- an IRQ taken while already supervisor
      //       changes A7 by -8 with S and M unchanged: 7 ExecuteLockStepSpec failures,
      //       all IRQ/STOP/RTE. Kept as a comment because the narrow rule looks correct.)
      //   (3) a (S,M) BANK SWITCH -> `ss.a7` selects usp/isp/msp, so changing S or M
      //       changes architectural A7 with no writeback at all;
      //   (4) seeding the very first value (the boot SSP).
      //
      // So a7Static IS still needed on a value edge -- the bug was resyncing on EVERY
      // edge, including the LAG REPLAY of a value the fold already applied correctly.
      // Discriminator: the lag is bounded (~2 commits), so a replayed edge is always a
      // value a7Run held very recently, whereas a genuine exception push is a value
      // a7Run has NEVER held. Suppress the resync iff a7Static is in the recent a7Run
      // history; otherwise it carries information the fold does not have -> resync.
      // MOVEM push/pop: a7Static replays 0x000ffffc, which a7Run held one step earlier
      //   -> suppressed (this is cluster C).
      // IRQ frame push:  a7Static becomes 0x000ffff8, never held by a7Run -> resync.
      // WINDOW DEPTH (Part 124): this queue is indexed in RECORDS -- i.e. RETIRED µops --
      // but the lag it has to span is measured in COMMIT CYCLES, and one macro can
      // contribute several µops (a mem-dest RMW is 3, a MOVEM many more). A 4-deep window
      // was therefore too tight the moment a couple of multi-µop macros sat between an
      // A7 write and the lagging `ss.a7` shadow catching up: MEASURED, adding three plain
      // seeding MOVEs ahead of `tst.l (%sp)+` pushed the pre-write value out of a 4-deep
      // window and the shadow then "resynced" A7 backwards to the boot SSP. 16 keeps the
      // discriminator's intent (a replayed lag value is one a7Run held VERY recently; a
      // genuine exception push is a value it has NEVER held) with room for µop density.
      val a7Recent = mutable.Queue[Long]()          // bounded a7Run history (lag window)
      def a7Seen(v: Long): Boolean = a7Recent.contains(v)
      def a7Note(v: Long): Unit = { a7Recent.enqueue(v); if (a7Recent.size > 16) a7Recent.dequeue() }
      var lastSysSm: Int = -1
      // Built into a buffer (not a flatMap) so a DROPPED crack-tail record can be folded
      // BACK into the step it belongs to -- see the archReg2* block below.
      val out = mutable.ArrayBuffer[CommitObservation]()
      commits.toSeq.foreach {
        case NormRec(pc, sysByte, a7Static, wb, emit, msp, isp, macroLast) =>
          // (2) bank switch: the (S,M) selector changed -> ss.a7 now names a different
          //     bank, a change no arch-15 writeback can express. (3) seed the first value.
          val sysSm = ((sysByte >> 5) & 1) * 2 + ((sysByte >> 4) & 1)   // S,M selector
          val a7S   = if (a7Static >= 0) a7Static & 0xffffffffL else -1L
          val bankSwitched = lastSysSm >= 0 && sysSm != lastSysSm
          // Resync only on NEW information: a bank switch, or a value the fold has never
          // produced (an exception push). A value a7Run held recently is the lagged
          // shadow replaying what the fold already applied -> ignore it.
          if (a7S >= 0 && a7S != a7Run && (bankSwitched || !a7Seen(a7S))) a7Run = a7S
          if (a7Run < 0 && a7S >= 0) a7Run = a7S
          lastSysSm = sysSm
          if (a7Run >= 0) a7Note(a7Run)
          if (wb.nzvcWrite) ccr = (ccr & 0x10) | (wb.nzvc & 0xf)
          if (wb.xWrite)    ccr = (ccr & 0x0f) | ((wb.x & 1) << 4)
          // Fold an arch-15 (A7) int write into the running A7 (even for a dropped
          // crack µop like the stack-push store).
          if (wb.intWrite && wb.dstArch == 15) { a7Run = wb.result & 0xffffffffL; a7Note(a7Run) }
          // A TEMP destination (arch >= 16, e.g. the mem-dest RMW op µop -> T1) is NOT
          // architectural: emit the step (it carries the instruction PC + the folded
          // CCR — the RMW's flags), but force archRegValid=False so the comparator does
          // NOT index a non-existent oracle register (D/A are 0..15). The memory effect
          // is checked separately via checkMem.
          val isTemp = wb.intWrite && wb.dstArch >= 16
          // The committed ss.* banks are registered readback shadows that lag the per-step
          // commit boundary for a normal A7 write. The ACTIVE (S,M)-selected bank's timely
          // value is a7Run (== the validated live A7). Inactive banks are stable -> sample
          // them directly. Guard: only override when a7Run is valid (>= 0).
          val sBitN  = (sysByte >> 5) & 1
          val mBitN  = (sysByte >> 4) & 1
          val mspOut = if (a7Run >= 0 && sBitN == 1 && mBitN == 1) a7Run else msp
          val ispOut = if (a7Run >= 0 && sBitN == 1 && mBitN == 0) a7Run else isp
          if (!emit) {
            // ---- CRACK TAIL -> SECOND architectural destination of the kept step ------
            // `divRem` marks the trailing µop of a two-destination macro-instruction:
            // DIVU.L/DIVS.L's `DIVREM` (-> Dr, the REMAINDER) and 64-bit MULU.L/MULS.L's
            // `MULHI` (-> Dh, the HIGH product). Dropping the record is correct for
            // step ALIGNMENT (one oracle step per instruction), but dropping the VALUE
            // meant lock-step never compared either register: the old comment here
            // asserted the write was "verified by a later instruction that reads Dr",
            // which is true only if such an instruction happens to exist -- and in the
            // ported corpus, the fuzz corpus and most lock-step programs it does not.
            // A real wrong-remainder RTL bug (BUG_calibration_word_misplaced_0d00.md
            // Part 116/117) survived every one of those suites through this hole.
            // Fold the tail's write into the step it belongs to instead, so the
            // comparator checks BOTH destinations of the macro-instruction.
            if (wb.secondDst && wb.intWrite && wb.dstArch < 16 && out.nonEmpty) {
              val prev = out(out.size - 1)
              out(out.size - 1) = prev.copy(
                archReg2Id = wb.dstArch, archReg2Write = wb.result, archReg2Valid = true)
            }
            // ---- TRAILING dropped µop -> fold BACKWARD into its own macro's record ----
            // Part 124. The forward-fold contract documented on `NormRec.emit` ("its
            // arch-reg write STILL folds into the running state so the NEXT kept record
            // carries it") is correct ONLY for a dropped µop that LEADS its macro -- a
            // BSR/JSR/LINK stack push, a cracked leading load, a source-EA `anUpdUop`.
            // For those the next kept record IS the same macro's.
            //
            // It is WRONG for a dropped µop that TRAILS its macro, which is exactly the
            // `(An)+`/`-(An)` auto-update folded onto the STORE of a memory-destination
            // RMW: `CLR/NEG/NEGX/NOT/TAS <ea>` (crackClr/crackRmw), `Scc <ea>`, `CAS`.
            // There the macro's kept record is the OP µop (it owns NZVC) and the An
            // write lands one µop later, so the forward fold attributed the new An to
            // the NEXT INSTRUCTION's step -- a measured one-instruction lag.
            //
            // For An != A7 that was invisible (the dropped store's arch write was never
            // compared at all -- the same coverage hole `secondDst` above was added to
            // close). For An == A7 the harness compares `a7` on EVERY step, so it
            // surfaced as a hard divergence: `CLR.L (%sp)+ -> a7: dut=0x3000
            // oracle=0x3004`. MEASURED, not inferred: the DUT's architectural A7 is
            // correct and the very next instruction reads the post-incremented value
            // (`clr.l (%sp)+ ; move.l %sp,%d1` yields D1=0x3004, and the ROM's
            // `clr.l (%sp)+ / move.w %sp,%d0 / bne` loop exits with a PC sequence
            // byte-identical to Musashi's).
            //
            // `pc` guard: every µop of one macro carries the same post-instruction pc
            // (the macro's nextPc), so a mismatch means this is not the record's macro
            // and the fold is skipped rather than corrupting a neighbour.
            else if (macroLast && wb.intWrite && wb.dstArch < 16 && out.nonEmpty &&
                     out(out.size - 1).pc == pc) {
              val prev  = out(out.size - 1)
              val isA7  = wb.dstArch == 15
              out(out.size - 1) = prev.copy(
                a7  = if (isA7) a7Run else prev.a7,
                msp = if (isA7 && sBitN == 1 && mBitN == 1) a7Run else prev.msp,
                isp = if (isA7 && sBitN == 1 && mBitN == 0) a7Run else prev.isp,
                // Also CLOSE the coverage hole for a NON-A7 base: the auto-updated An of
                // a mem-dest RMW was never compared against the oracle before this.
                archReg2Id    = if (prev.archReg2Valid) prev.archReg2Id    else wb.dstArch,
                archReg2Write = if (prev.archReg2Valid) prev.archReg2Write else wb.result,
                archReg2Valid = true)
            }
          } else out += CommitObservation(
            pc = pc, archRegId = if (isTemp) 0 else wb.dstArch,
            archRegWrite = if (isTemp) 0L else wb.result, archRegValid = wb.intWrite && !isTemp,
            ccr = ccr, memAddr = 0, memData = 0, memWrite = false,
            sr = ((sysByte & 0xff) << 8) | (ccr & 0x1f), a7 = a7Run, msp = mspOut, isp = ispOut)
        case ExcRec(pc, sysByte, a7, foldNzvc, setCcr5, msp, isp) =>
          // An exception/RTE step uses the ROB-surfaced ss.a7 (the exc unit's banked
          // A7); resync the running A7 to it. This is resync EVENT (1) -- see a7Run's
          // header. An exception moves A7 with no arch-15 writeback, so ss.a7 is the only
          // source, and the exception path is serializing so the sample has settled.
          if (a7 >= 0) { a7Run = a7 & 0xffffffffL; a7Note(a7Run) }
          // Keep the bank selector in step so the NEXT NormRec does not see this event's
          // (S,M) change as a fresh bank switch and resync a second time off a stale sample.
          lastSysSm = ((sysByte >> 5) & 1) * 2 + ((sysByte >> 4) & 1)
          // Fold the faulting instruction's own NZVC (CHK) onto the running CCR before
          // the entry step; -1 => no fold (the running CCR already reflects the
          // architectural state for TRAPV/DIV0/access-fault/interrupt/RTE).
          if (foldNzvc >= 0) ccr = (ccr & 0x10) | (foldNzvc & 0xf)
          // MOVE-to-SR's ABSOLUTE 5-bit CCR write SETS the running CCR (X N Z V C).
          if (setCcr5 >= 0) ccr = setCcr5 & 0x1f
          // Same active-bank shadow-lag fix for ExcRec: the exc FSM drains before consuming
          // the shadow, but surface the active bank from a7 (timely for exc commits) and
          // the inactive banks from the stable shadow. Guard: only override when a7 >= 0.
          val sBitE  = (sysByte >> 5) & 1
          val mBitE  = (sysByte >> 4) & 1
          val mspOutE = if (a7 >= 0 && sBitE == 1 && mBitE == 1) a7 & 0xffffffffL else msp
          val ispOutE = if (a7 >= 0 && sBitE == 1 && mBitE == 0) a7 & 0xffffffffL else isp
          out += CommitObservation(
            pc = pc, archRegId = 0, archRegWrite = 0, archRegValid = false,
            ccr = ccr, memAddr = 0, memData = 0, memWrite = false,
            sr = ((sysByte & 0xff) << 8) | (ccr & 0x1f), a7 = a7, msp = mspOutE, isp = ispOutE)
      }
      out.toSeq
    }
  }
}
