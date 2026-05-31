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
    memWrite:     Boolean
)
