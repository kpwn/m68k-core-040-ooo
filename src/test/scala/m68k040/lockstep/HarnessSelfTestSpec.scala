package m68k040.lockstep

import m68k040.VerilatorTest
import m68k040.fuzz.FuzzRunner
import m68k040.oracle.OracleStep
import org.scalatest.funsuite.AnyFunSuite

/** THE VERIFICATION LAYER'S OWN VERIFICATION.
  *
  * On 2026-09-03 this project found seven defects in its own verification layers, two of
  * which had hidden real CPU bugs for months. The structural cause of four of them was the
  * same: the lock-step loop iterated DUT records and gated the register check on the DUT's
  * own `archRegValid`, so an omission was silent. `ArchLockStep` inverts that.
  *
  * A harness change that has not been shown to catch what it was built to catch is not
  * verified. This spec reintroduces the historical defects one at a time and asserts the
  * new comparison fails where the old one passed. Each test also runs an unmutated CONTROL
  * so a failure cannot be an artefact of the program itself.
  *
  * Defect numbering follows the 2026-09-03 list:
  *   1  lock-step never compared `Dr` (a long divide's remainder)
  *   2  lock-step never compared `Dh` (64-bit multiply high half)
  *   3  the fuzz harness silently deleted every `Scc <mem>` record (`keepCommit`)
  *   4  committed `a7` was a >=2-cycle lagged shadow replayed as live
  *   7  `WhiteboxCapture` folded a dropped crack µop's write onto the wrong instruction
  *
  * Defects 5 (a `set_bus_skew` Tcl glob matching 0 of 30 instances) and 6 (the OOC synth
  * gate's 0.919 ns noise floor) are synthesis-flow defects with no lock-step surface at
  * all; nothing here can or does address them, and the ranked comparison document says so
  * (§5.1's table scores the inversion 4 of 7, not 7 of 7).
  */
class HarnessSelfTestSpec extends AnyFunSuite {

  // ── Part 1: pure comparator level (no simulation) ──────────────────────────────────
  // Defects 1 and 2 in their exact original shape: the DUT emits ONE record for a macro
  // that writes TWO architectural registers, and the second one is wrong. Shows the
  // difference between the two loops with nothing else moving.

  private def oracleStep(pc: Long, d: Seq[Long], a: Seq[Long] = Seq.fill(8)(0L), sr: Int = 0x2700) =
    OracleStep(pc, sr, d.toVector, a.toVector)

  test("defect 1/2: a wrong SECOND destination passes the DUT-driven loop and fails the reference-driven one") {
    // `divu.l %d1,%d3:%d0` -> D0 = quotient, D3 = remainder. Oracle: D0=142, D3=6.
    // The DUT gets the quotient right and the remainder wrong (6 -> 99), which is exactly
    // the shape of the real bug: a divide returning ANOTHER divide's remainder.
    val oracle = Vector(
      oracleStep(0x1000, Seq(0L, 7L, 0L, 0L, 0, 0, 0, 0)),
      oracleStep(0x1006, Seq(142L, 7L, 0L, 6L, 0, 0, 0, 0)))

    // PRE-FIX DUT-driven record: the crack tail's write was DROPPED, so the record claims
    // only D0. `archReg2Valid = false` is literally what the harness produced before
    // `secondDst` was added (CommitObservation.scala:19-32).
    val dutPreFix = Seq(
      CommitObservation(0x1000, 1, 7L, true, 0, 0, 0, false, sr = 0x2700),
      CommitObservation(0x1006, 0, 142L, true, 0, 0, 0, false, sr = 0x2700))
    assert(LockStep.compare(dutPreFix, oracle).ok,
      "precondition: the pre-fix DUT-driven comparator must PASS on a wrong remainder — " +
        "that is the coverage hole being demonstrated")

    // Reference-driven: the DUT's full architectural state is compared, so the wrong
    // remainder is caught whether or not the DUT volunteered a record for it.
    val snaps = Seq(
      ArchSnapshot(0, 1, Vector(0L, 7L, 0L, 0L, 0, 0, 0, 0), Vector.fill(8)(0L), 0, 0x27),
      ArchSnapshot(1, 2, Vector(142L, 7L, 0L, 99L, 0, 0, 0, 0), Vector.fill(8)(0L), 0, 0x27))
    val ar = ArchLockStep.compare(snaps, oracle, 2)
    assert(!ar.ok, "the reference-driven comparator must FAIL on a wrong remainder")
    assert(ar.divergences.head.kind == "INTEGER WRITE MISSING/WRONG",
      s"expected the reference write-set check to name it first, got ${ar.divergences.head}")
    assert(ar.divergences.head.detail.contains("D3"),
      s"expected the message to name D3, got ${ar.divergences.head.detail}")
  }

  test("defect 1/2 control: a correct second destination passes the reference-driven loop") {
    val oracle = Vector(
      oracleStep(0x1000, Seq(0L, 7L, 0L, 0L, 0, 0, 0, 0)),
      oracleStep(0x1006, Seq(142L, 7L, 0L, 6L, 0, 0, 0, 0)))
    val snaps = Seq(
      ArchSnapshot(0, 1, Vector(0L, 7L, 0L, 0L, 0, 0, 0, 0), Vector.fill(8)(0L), 0, 0x27),
      ArchSnapshot(1, 2, Vector(142L, 7L, 0L, 6L, 0, 0, 0, 0), Vector.fill(8)(0L), 0, 0x27))
    assert(ArchLockStep.compare(snaps, oracle, 2).ok)
  }

  test("an UNEXPECTED DUT write (one the reference never made) also fails") {
    // The reference write-set loop alone would miss this — NaxRiscv's `log_reg_write`
    // check only asserts that the reference's writes happened. The full 16-register
    // compare is what catches a register the DUT clobbered on its own.
    val oracle = Vector(
      oracleStep(0x1000, Seq(0L, 7L, 0L, 0L, 0, 0, 0, 0)),
      oracleStep(0x1002, Seq(1L, 7L, 0L, 0L, 0, 0, 0, 0)))
    val snaps = Seq(
      ArchSnapshot(0, 1, Vector(0L, 7L, 0L, 0L, 0, 0, 0, 0), Vector.fill(8)(0L), 0, 0x27),
      ArchSnapshot(1, 2, Vector(1L, 7L, 0L, 0L, 0, 0, 0xdeadL, 0), Vector.fill(8)(0L), 0, 0x27))
    val ar = ArchLockStep.compare(snaps, oracle, 2)
    assert(!ar.ok && ar.divergences.head.kind == "ARCH REG MISMATCH" &&
           ar.divergences.head.detail.contains("D6"), ar.divergences.toString)
  }

  test("a missing oracle-step boundary is reported, not silently skipped") {
    val oracle = Vector(
      oracleStep(0x1000, Seq.fill(8)(0L)),
      oracleStep(0x1002, Seq.fill(8)(0L)),
      oracleStep(0x1004, Seq.fill(8)(0L)))
    val snaps = Seq(ArchSnapshot(2, 3, Vector.fill(8)(0L), Vector.fill(8)(0L), 0, 0x27))
    val lenient = ArchLockStep.compare(snaps, oracle, 3)
    assert(lenient.ok && lenient.covered == 1 && lenient.uncoveredIdx == Seq(0, 1))
    val strict = ArchLockStep.compare(snaps, oracle, 3, requireFullCoverage = true)
    assert(!strict.ok && strict.divergences.head.kind == "NO RETIRE BOUNDARY")
  }

  // ── Part 2: end-to-end, on the real DUT ────────────────────────────────────────────

  private val NL = " ; "

  /** Run `src` through the fuzz lock-step harness and return the outcome kind:
    * "Pass", "STEP" (the DUT-driven delta comparator), "ARCH" (the reference-driven
    * structural comparator), "MEMORDER", "ALLOC", "HANG". */
  private def kindOf(o: FuzzRunner.Outcome): String = o match {
    case FuzzRunner.Pass                    => "Pass"
    case FuzzRunner.GenFail(r)              => s"GENFAIL($r)"
    case FuzzRunner.Diverged(k, _, _)       => k
  }
  private def detailOf(o: FuzzRunner.Outcome): String = o match {
    case FuzzRunner.Diverged(_, d, c) => s"$d\n$c"
    case other                        => other.toString
  }

  // Defects 1 and 2 end-to-end. Corrupting the PHYSICAL register the committed RAT names
  // for the remainder, after the divide has retired, reproduces the real bug's observable
  // shape -- a wrong architectural remainder that no later instruction reads back -- while
  // leaving every EU writeback observation intact. Note this defeats BOTH the pre-fix and
  // the post-fix delta comparator: `archReg2*` compares the WRITEBACK VALUE, not the
  // architectural state, so `secondDst` closed the original hole but not this one.
  test("defect 1/2 end-to-end: architectural corruption of a long divide's remainder", VerilatorTest) {
    val prog =
      Seq("move.l #1000,%d0", "move.l #7,%d1", "moveq #0,%d3",
          "divu.l %d1,%d3:%d0", "nop", "nop", "nop", "nop",
          "Lend:", "bra.s Lend").mkString(NL)
    val control = FuzzRunner.run(prog, 1)
    assert(kindOf(control) == "Pass", s"control run must pass, got ${detailOf(control)}")

    // Same program, same seed, ONE input varied: D3's committed physical register is
    // overwritten after 5 records have retired (the divide is record 3).
    val corrupted = FuzzRunner.run(prog, 1,
      FuzzRunner.SelfTest(dropSecondDst = true, corruptArch = Some((5, 3, 0x0badbeefL))))
    assert(kindOf(corrupted) == "ARCH",
      s"expected the reference-driven comparator to catch the corrupted remainder; " +
        s"got ${kindOf(corrupted)}: ${detailOf(corrupted)}")
    assert(detailOf(corrupted).contains("D3"),
      s"expected the divergence to name D3, got ${detailOf(corrupted)}")
    // The "ARCH" kind IS the blindness proof: `FuzzRunner.run` only evaluates the
    // structural comparator after `LockStep.compare` has returned ok, so an ARCH outcome
    // means the DUT-driven loop passed this exact run.
  }

  // Defect 4. The a7Static resync bug replayed the >=2-cycle lagged `ss.a7` shadow on top
  // of the correctly-folded live value, making an ordinary instruction after two adjacent
  // A7-changing retirements report a stale A7 (fuzz cluster C, 8/200 seeds). The
  // structural comparator reads A7 out of `intPrf[committedPhys(15)]` and has no shadow to
  // lag, so it is IMMUNE by construction -- which is the demonstration.
  test("defect 4 end-to-end: the lagged a7 shadow breaks the delta loop and cannot touch the structural one", VerilatorTest) {
    val prog =
      Seq("movem.l %d0-%d1,-(%sp)", "movem.l (%sp)+,%d0-%d1",
          "move.l #1,%d2", "move.l #2,%d4", "nop",
          "Lend:", "bra.s Lend").mkString(NL)
    val control = FuzzRunner.run(prog, 1)
    assert(kindOf(control) == "Pass", s"control run must pass, got ${detailOf(control)}")

    val regressed = FuzzRunner.run(prog, 1,
      FuzzRunner.SelfTest(wb = WhiteboxCapture.Regressions(legacyA7Resync = true)))
    assert(kindOf(regressed) == "STEP",
      s"expected the reintroduced a7-shadow defect to break the DELTA comparator; " +
        s"got ${kindOf(regressed)}: ${detailOf(regressed)}")
    assert(detailOf(regressed).contains("a7"),
      s"expected an a7 divergence, got ${detailOf(regressed)}")
    // MEASURED immunity, not asserted: the same run's structural verdict is reported in
    // the divergence context. It must say OK.
    assert(detailOf(regressed).contains("structural=OK"),
      s"the structural comparator must be immune to a harness-side A7 shadow defect; " +
        s"got: ${detailOf(regressed).take(400)}")
  }

  // Defect 7. A macro's TRAILING `(An)+` auto-update µop is dropped; before `9469b4f` its
  // write folded FORWARD onto the next instruction's step. Same immunity argument as
  // defect 4: the structural comparator does no folding at all.
  test("defect 7 end-to-end: the crack-tail mis-attribution breaks the delta loop only", VerilatorTest) {
    val prog =
      Seq("clr.l (%sp)+", "move.l %sp,%d1", "move.l #3,%d2", "nop",
          "Lend:", "bra.s Lend").mkString(NL)
    val control = FuzzRunner.run(prog, 1)
    assert(kindOf(control) == "Pass", s"control run must pass, got ${detailOf(control)}")

    val regressed = FuzzRunner.run(prog, 1,
      FuzzRunner.SelfTest(wb = WhiteboxCapture.Regressions(dropTrailingFold = true)))
    assert(kindOf(regressed) == "STEP",
      s"expected the reintroduced crack-tail fold defect to break the DELTA comparator; " +
        s"got ${kindOf(regressed)}: ${detailOf(regressed)}")
    assert(detailOf(regressed).contains("structural=OK"),
      s"the structural comparator must be immune to a harness-side fold defect; " +
        s"got: ${detailOf(regressed).take(400)}")
  }

  // Defect 3. Dropping `keepCommit` deletes the whole `Scc <mem>` instruction from the
  // retire stream. Unlike 4 and 7 this one ALSO broke the delta comparator (it produced 40
  // of 57 fuzz divergences), so the claim under test is not "newly caught" but "still
  // caught, and now with a second, independent witness".
  test("defect 3 end-to-end: deleting an Scc <mem> record is caught by both comparators", VerilatorTest) {
    val prog =
      Seq("move.l #0x4000,%a0", "moveq #0,%d0", "tst.l %d0", "seq (%a0)",
          "move.l #5,%d2", "nop",
          "Lend:", "bra.s Lend").mkString(NL)
    val control = FuzzRunner.run(prog, 1)
    assert(kindOf(control) == "Pass", s"control run must pass, got ${detailOf(control)}")

    val regressed = FuzzRunner.run(prog, 1, FuzzRunner.SelfTest(dropKeepCommit = true))
    assert(kindOf(regressed) != "Pass",
      s"deleting the Scc <mem> record must be caught; got ${detailOf(regressed)}")
    // The improvement is ATTRIBUTION, and it is measurable. The delta comparator reports a
    // PC mismatch one instruction downstream of the deletion. The structural comparator
    // reports "DUPLICATE BOUNDARY ... (harness bug, not an RTL bug)" at the deleted
    // instruction itself -- two retire boundaries claiming one oracle step is a shape only
    // a harness defect can produce, which is exactly the thing that took a day to work out
    // in 2026-09-03's 40-of-57 false-divergence cluster.
    assert(detailOf(regressed).contains("DUPLICATE BOUNDARY"),
      s"expected the structural comparator to attribute this to the harness; " +
        s"got: ${detailOf(regressed).take(400)}")
    info(s"defect 3 reintroduced -> ${kindOf(regressed)}: ${detailOf(regressed).take(300)}")
  }
}
