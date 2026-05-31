package m68k040.lockstep

import m68k040.oracle.OracleStep

final case class Divergence(index: Long, detail: String)
final case class LockStepResult(ok: Boolean, firstDivergence: Option[Divergence], matched: Long)

/** Pure-Scala instruction-level lock-step comparator. Aligns the DUT's retired
  * commits with Musashi's per-instruction steps positionally and reports the
  * first divergence. Granularity = one retired instruction (spec invariant #5). */
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
