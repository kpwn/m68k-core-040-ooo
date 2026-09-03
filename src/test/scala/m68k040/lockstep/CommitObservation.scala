package m68k040.lockstep

/** DUT-side per-retired-instruction observation, captured from the CommitTrace
  * SpinalHDL port. One per cycle where CommitTrace.fire is high. Mirrors the
  * lock-step-relevant CommitTrace fields. */
final case class CommitObservation(
    pc:           Long,
    archRegId:    Int,
    archRegWrite: Long,
    archRegValid: Boolean,
    ccr:          Int,
    memAddr:      Long,
    memData:      Long,
    memWrite:     Boolean,
    sr:           Int  = 0x2700,   // full 16-bit SR (system byte + CCR)
    a7:           Long = -1L,      // committed A7 (-1 = "not surfaced/don't compare")
    msp:          Long = -1L,      // committed MSP (-1 = not surfaced)
    isp:          Long = -1L,      // committed ISP (-1 = not surfaced)
    // ---- SECOND architectural destination of a CRACKED macro-instruction --------------
    // A few 68k instructions write TWO architectural registers but map to ONE oracle step,
    // because decode cracks them into two µops: DIVU.L/DIVS.L (`DIV` -> Dq, `DIVREM` ->
    // Dr) and the 64-bit MULU.L/MULS.L (`MUL` -> Dl, `MULHI` -> Dh). The tail µop's own
    // commit record is dropped (it is not its own oracle step), so before this field
    // existed the SECOND register was NEVER COMPARED by lock-step -- the remainder of
    // every long divide and the high half of every 64-bit multiply were invisible unless
    // a later instruction happened to read them back. That blind spot is what let a real
    // wrong-remainder bug (docs/BUG_calibration_word_misplaced_0d00.md Part 116/117) live
    // through the whole ported corpus, the fuzz campaign and every lock-step test.
    // WhiteboxCapture now folds the dropped tail's write into the preceding kept step.
    archReg2Id:    Int     = 0,
    archReg2Write: Long    = 0L,
    archReg2Valid: Boolean = false
)
