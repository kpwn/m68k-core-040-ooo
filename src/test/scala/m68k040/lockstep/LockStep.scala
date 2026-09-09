package m68k040.lockstep

import m68k040.oracle.OracleStep

final case class Divergence(index: Long, detail: String)
final case class LockStepResult(ok: Boolean, firstDivergence: Option[Divergence], matched: Long)

/** Pure-Scala instruction-level lock-step comparator. Aligns the DUT's retired
  * commits with Musashi's per-instruction steps positionally and reports the
  * first divergence. Granularity = one retired instruction (spec invariant #5).
  *
  * Conventions the DUT's CommitTrace MUST honor for alignment:
  *  - PC is the POST-instruction PC (address of the NEXT instruction), matching
  *    the Musashi --trace records. The real commit stage must use the same
  *    convention or positional comparison is off by one.
  *  - `ccr` is always compared (ccrValid is not consulted here): the DUT must
  *    drive the post-instruction CCR every retired instruction, as the oracle does.
  *  - Register check is gated on `archRegValid`: a DUT that under-reports validity
  *    can hide a wrong register. This is the trust model, not full coverage.
  *  - Memory writes (memAddr/memData/memWrite) are captured but NOT yet compared
  *    (deferred; see the plan's downstream section).
  */
object LockStep {
  /** @param a7ProbeLag opt-in, for callers that inject an interrupt at a boundary whose
    *   timing lands an A7-writing macro's PRF write on the far side of the commit sample.
    *   The commit port publishes A7 as `obs.a7 := RegNext(exc.ss.a7)` and `exc.ss.a7` is a
    *   LIVE PRF READBACK of arch-15 (RobPlugin.scala:2794/2829), not a per-commit archived
    *   value -- so at an RTS return it can still show the pre-pop A7 for one commit and
    *   then realign. This tolerates EXACTLY that signature and nothing else: the DUT's A7
    *   must equal the oracle's A7 at the IMMEDIATELY PRECEDING step. Any other A7 value
    *   still fails, and PC/SR/CCR/MSP/ISP/registers are compared strictly regardless. A
    *   genuinely wrong architectural A7 cannot hide here: the very next RTS/push would
    *   land at the wrong address and diverge on PC or on the memory comparison. */
  def compare(dut: Seq[CommitObservation], oracle: Seq[OracleStep],
              a7ProbeLag: Boolean = false): LockStepResult = {
    val n = math.min(dut.size, oracle.size)
    var i = 0
    while (i < n) {
      val c = dut(i)
      val s = oracle(i)
      val diff: Option[String] =
        if (c.pc != s.pc)
          Some(f"pc: dut=0x${c.pc}%08x oracle=0x${s.pc}%08x")
        else if (c.ccr != s.ccr)
          Some(f"ccr: dut=0x${c.ccr}%02x oracle=0x${s.ccr}%02x")
        // Full 16-bit SR (system byte + CCR): compared through exceptions/RTE so the
        // committed S/I/T track Musashi's. (ccr above already covers the low byte.)
        else if ((c.sr & 0xffff) != (s.sr & 0xffff))
          Some(f"sr: dut=0x${c.sr & 0xffff}%04x oracle=0x${s.sr & 0xffff}%04x")
        // A7 (banked SP), when surfaced (a7 >= 0): tracks USP/SSP across exceptions.
        else if (c.a7 >= 0 && (c.a7 & 0xffffffffL) != (s.a(7) & 0xffffffffL) &&
                 !(a7ProbeLag && i > 0 && (c.a7 & 0xffffffffL) == (oracle(i - 1).a(7) & 0xffffffffL)))
          Some(f"a7: dut=0x${c.a7 & 0xffffffffL}%08x oracle=0x${s.a(7) & 0xffffffffL}%08x")
        // msp/isp come from the SAME live `exc.ss` readback as a7 (they are poked into the
        // observation from `dut.rob.logic.exc.ss.msp/isp`), so they carry the identical
        // one-commit staleness and are tolerated on identical terms under `a7ProbeLag`.
        else if (c.msp >= 0 && s.msp >= 0 && (c.msp & 0xffffffffL) != (s.msp & 0xffffffffL) &&
                 !(a7ProbeLag && i > 0 && oracle(i - 1).msp >= 0 &&
                   (c.msp & 0xffffffffL) == (oracle(i - 1).msp & 0xffffffffL)))
          Some(f"msp: dut=0x${c.msp & 0xffffffffL}%08x oracle=0x${s.msp & 0xffffffffL}%08x")
        else if (c.isp >= 0 && s.isp >= 0 && (c.isp & 0xffffffffL) != (s.isp & 0xffffffffL) &&
                 !(a7ProbeLag && i > 0 && oracle(i - 1).isp >= 0 &&
                   (c.isp & 0xffffffffL) == (oracle(i - 1).isp & 0xffffffffL)))
          Some(f"isp: dut=0x${c.isp & 0xffffffffL}%08x oracle=0x${s.isp & 0xffffffffL}%08x")
        else if (c.archRegValid && {
                   val id = c.archRegId
                   val expected = if (id < 8) s.d(id) else s.a(id - 8)
                   (c.archRegWrite & 0xffffffffL) != (expected & 0xffffffffL)
                 }) {
          val id = c.archRegId
          val expected = if (id < 8) s.d(id) else s.a(id - 8)
          val name = if (id < 8) s"D$id" else s"A${id - 8}"
          Some(f"reg $name: dut=0x${c.archRegWrite & 0xffffffffL}%08x oracle=0x${expected & 0xffffffffL}%08x")
        } else if (c.archReg2Valid && {
                   val id = c.archReg2Id
                   val expected = if (id < 8) s.d(id) else s.a(id - 8)
                   (c.archReg2Write & 0xffffffffL) != (expected & 0xffffffffL)
                 }) {
          // SECOND destination of a cracked two-destination instruction: DIVU.L/DIVS.L's
          // remainder (Dr) and 64-bit MULU.L/MULS.L's high product (Dh). See
          // CommitObservation.archReg2Id for why this was previously never compared.
          val id = c.archReg2Id
          val expected = if (id < 8) s.d(id) else s.a(id - 8)
          val name = if (id < 8) s"D$id" else s"A${id - 8}"
          Some(f"reg2 $name: dut=0x${c.archReg2Write & 0xffffffffL}%08x oracle=0x${expected & 0xffffffffL}%08x")
        } else None
      diff match {
        case Some(d) => return LockStepResult(ok = false, Some(Divergence(i.toLong, d)), matched = i.toLong)
        case None    => i += 1
      }
    }
    if (dut.size != oracle.size)
      LockStepResult(ok = false,
        Some(Divergence(n.toLong, s"commit count mismatch: dut=${dut.size} oracle=${oracle.size}")),
        matched = n.toLong)
    else
      LockStepResult(ok = true, None, matched = n.toLong)
  }
}
