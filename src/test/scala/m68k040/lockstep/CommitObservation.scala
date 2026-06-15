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
    isp:          Long = -1L       // committed ISP (-1 = not surfaced)
)
