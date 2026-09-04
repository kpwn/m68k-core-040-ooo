package m68k040.lockstep

import m68k040.oracle.OracleStep

/** Full architectural state of the DUT at one observable retire boundary.
  *
  * `oracleIdx` is the index of the oracle step this boundary is claimed to follow --
  * i.e. after this boundary the DUT's architectural state must equal
  * `oracle(oracleIdx)`'s post-instruction state. It is derived from the emitted-record
  * count, NOT from any PC arithmetic.
  *
  * `d`/`a` are read STRUCTURALLY: `intPrf[CommittedMapService.intPhys(i)]` for
  * i in 0..15, so a register the DUT never volunteered a writeback record for is
  * still compared. `ccr` is likewise read from the committed NZVC/X physical
  * registers, not folded from writeback observations. */
final case class ArchSnapshot(
    oracleIdx: Int,
    cycle:     Long,
    d:         Vector[Long],   // 8
    a:         Vector[Long],   // 8 (a(7) = the ACTIVE-bank A7)
    ccr:       Int,            // {X,N,Z,V,C} = bits 4..0
    srSys:     Int             // SR system byte (bits 15..8), from the ROB commit obs
) {
  def reg(i: Int): Long = if (i < 8) d(i) else a(i - 8)
  def regName(i: Int): String = if (i < 8) s"D$i" else s"A${i - 8}"
}

final case class ArchDivergence(oracleIdx: Int, kind: String, detail: String)

/** Result of the ORACLE-DRIVEN comparison.
  *
  * `covered` = how many oracle steps had an observable DUT retire boundary. This is
  * reported, never silently assumed: an oracle step with no boundary is a coverage
  * hole, and the harness must say so rather than pass.  */
final case class ArchLockStepResult(
    ok:          Boolean,
    divergences: Seq[ArchDivergence],
    covered:     Int,
    total:       Int,
    uncoveredIdx: Seq[Int]
) {
  def firstDetail: String = divergences.headOption.map(d => s"idx=${d.oracleIdx} ${d.kind}: ${d.detail}").getOrElse("")
}

/** REFERENCE-DRIVEN architectural lock-step comparator.
  *
  * This is the inversion of `LockStep.compare`. `LockStep` iterates the DUT's own
  * records and gates the register check on the DUT's own `archRegValid`, which its
  * header concedes is *"the trust model, not full coverage"*: if the DUT never emits
  * a record, or emits one claiming it wrote nothing, nothing is compared and the
  * omission is silent. Four of the seven verification defects found on 2026-09-03
  * lived in exactly that gap (a long divide's remainder and a 64-bit multiply's high
  * half were NEVER compared; a lagged A7 shadow and a mis-attributed crack-tail fold
  * produced phantom divergences that a structural read cannot express).
  *
  * NaxRiscv drives its comparison from the reference model's write log
  * (`src/test/cpp/naxriscv/src/main.cpp:2193-2210`): every entry of Spike's
  * `log_reg_write` must be accounted for by the DUT, and a missing one asserts. We go
  * one step further, because our oracle carries the FULL architectural state per step
  * (`OracleStep.d/a/sr`) where Spike's sits behind an API: for every oracle step we
  * compare ALL 16 architectural registers plus the CCR, read structurally out of the
  * committed RAT + PRF. That subsumes the write-log check and cannot be defeated by
  * any amount of DUT under-reporting.
  *
  * The write-log check is still run FIRST, purely for the error message: naming the
  * register the reference wrote at this step ("INTEGER WRITE MISSING D3") localises a
  * failure far better than "some register differs".
  */
object ArchLockStep {

  /** Registers the reference model wrote at step `i` (delta against step i-1).
    * For i == 0 there is no predecessor, so the write set is empty and only the
    * full-state compare applies. */
  def referenceWriteSet(oracle: Seq[OracleStep], i: Int): Seq[Int] = {
    if (i == 0) Seq.empty
    else {
      val cur = oracle(i); val prev = oracle(i - 1)
      (0 until 16).filter { r =>
        val c = if (r < 8) cur.d(r) else cur.a(r - 8)
        val p = if (r < 8) prev.d(r) else prev.a(r - 8)
        (c & 0xffffffffL) != (p & 0xffffffffL)
      }
    }
  }

  /** Compare `snaps` (DUT retire boundaries) against oracle steps [0, upTo).
    *
    * The loop is over the ORACLE. A snapshot is looked up by its claimed oracle
    * index; an oracle step with no snapshot is recorded as UNCOVERED (reported, and
    * a hard failure only when `requireFullCoverage`), never silently skipped.
    *
    * `maxReport` bounds the divergence list; comparison does not stop at the first
    * one, because the second and third are usually what identify the class.
    */
  def compare(snaps: Seq[ArchSnapshot], oracle: Seq[OracleStep], upTo: Int,
              requireFullCoverage: Boolean = false,
              compareSrSys: Boolean = true,
              maxReport: Int = 8): ArchLockStepResult = {
    val byIdx = new scala.collection.mutable.HashMap[Int, ArchSnapshot]()
    // Last snapshot wins for a given index. Two snapshots for one index can only
    // arise from a harness bug; it is reported below rather than papered over.
    val dupes = new scala.collection.mutable.ArrayBuffer[Int]()
    for (s <- snaps) {
      if (byIdx.contains(s.oracleIdx)) dupes += s.oracleIdx
      byIdx(s.oracleIdx) = s
    }

    val divs = new scala.collection.mutable.ArrayBuffer[ArchDivergence]()
    val uncovered = new scala.collection.mutable.ArrayBuffer[Int]()
    for (i <- dupes.distinct.take(4))
      divs += ArchDivergence(i, "DUPLICATE BOUNDARY",
        s"two DUT retire boundaries claim oracle step $i (harness bug, not an RTL bug)")

    var i = 0
    while (i < upTo && divs.size < maxReport) {
      byIdx.get(i) match {
        case None => uncovered += i
        case Some(s) =>
          val o = oracle(i)
          def oreg(r: Int): Long = (if (r < 8) o.d(r) else o.a(r - 8)) & 0xffffffffL
          def dreg(r: Int): Long = s.reg(r) & 0xffffffffL
          // ── 1. the REFERENCE's write set: every write it made must be accounted for.
          //     This is NaxRiscv's `for (auto item : state->log_reg_write)` loop.
          for (r <- referenceWriteSet(oracle, i) if divs.size < maxReport) {
            if (dreg(r) != oreg(r))
              divs += ArchDivergence(i, "INTEGER WRITE MISSING/WRONG",
                f"${s.regName(r)}: dut=0x${dreg(r)}%08x oracle=0x${oreg(r)}%08x " +
                f"(reference wrote it at this step; previous oracle value 0x${
                  (if (r < 8) oracle(i - 1).d(r) else oracle(i - 1).a(r - 8)) & 0xffffffffL}%08x)")
          }
          // ── 2. full 16-register structural compare -- catches an UNEXPECTED DUT
          //     write just as well as a missing one.
          for (r <- 0 until 16 if divs.size < maxReport) {
            if (dreg(r) != oreg(r))
              divs += ArchDivergence(i, "ARCH REG MISMATCH",
                f"${s.regName(r)}: dut=0x${dreg(r)}%08x oracle=0x${oreg(r)}%08x")
          }
          // ── 3. CCR, read from the committed NZVC/X physical registers.
          if (divs.size < maxReport && (s.ccr & 0x1f) != (o.sr & 0x1f))
            divs += ArchDivergence(i, "CCR MISMATCH",
              f"ccr: dut=0x${s.ccr & 0x1f}%02x oracle=0x${o.sr & 0x1f}%02x")
          // ── 4. SR system byte (not renamed; taken from the ROB's commit obs).
          if (compareSrSys && divs.size < maxReport &&
              ((s.srSys << 8) & 0xff00) != (o.sr & 0xff00))
            divs += ArchDivergence(i, "SR SYSBYTE MISMATCH",
              f"srSys: dut=0x${(s.srSys << 8) & 0xff00}%04x oracle=0x${o.sr & 0xff00}%04x")
      }
      i += 1
    }

    val covered = upTo - uncovered.size
    if (requireFullCoverage && uncovered.nonEmpty && divs.size < maxReport)
      divs += ArchDivergence(uncovered.head, "NO RETIRE BOUNDARY",
        s"${uncovered.size} of $upTo oracle steps had no observable DUT retire boundary " +
        s"(first: ${uncovered.head})")
    ArchLockStepResult(divs.isEmpty, divs.toSeq, covered, upTo, uncovered.toSeq)
  }
}
