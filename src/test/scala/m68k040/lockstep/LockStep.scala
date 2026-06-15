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
  def compare(dut: Seq[CommitObservation], oracle: Seq[OracleStep]): LockStepResult = {
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
        else if (c.a7 >= 0 && (c.a7 & 0xffffffffL) != (s.a(7) & 0xffffffffL))
          Some(f"a7: dut=0x${c.a7 & 0xffffffffL}%08x oracle=0x${s.a(7) & 0xffffffffL}%08x")
        else if (c.msp >= 0 && s.msp >= 0 && (c.msp & 0xffffffffL) != (s.msp & 0xffffffffL))
          Some(f"msp: dut=0x${c.msp & 0xffffffffL}%08x oracle=0x${s.msp & 0xffffffffL}%08x")
        else if (c.isp >= 0 && s.isp >= 0 && (c.isp & 0xffffffffL) != (s.isp & 0xffffffffL))
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
