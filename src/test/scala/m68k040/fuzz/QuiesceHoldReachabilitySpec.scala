package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}

/** IS THE `quiesceHold` WINDOW EVER ACTUALLY ENTERED?
  *
  * `LsEuPlugin` asserts `!(quiesceHold && (ldGrantOk || stGrantOk))` -- a table walker
  * must never be granted a D-cache port inside the exception sequencer's maintenance
  * quiesce window, because `ExceptionUnit`'s written deadlock proof for `S_DRAIN` ends
  * "With the LS EU flushed, nothing re-arms them" and a table walker makes that sentence
  * false: it is not flushed, it is not part of the LS pipe, and it can start a fresh
  * D-cache access at any time.
  *
  * The mechanism is sound. What the race audit could NOT establish is that the guarded
  * circumstance is ever reached, and an assertion that never sees its circumstance is
  * indistinguishable from one that holds -- `quiesceHold` could be dead for a release and
  * every suite would still be green. That is the same shape as the defects this campaign
  * keeps finding (`RobIdBits` documented as load-bearing in two files with zero readers;
  * four MMU config-latch faults masked by an unasserted `doFlush -> umFlush ->
  * walkUmPoison` chain), so the audit recorded the pair UNKNOWN rather than HANDLED.
  *
  * `LsEuPlugin.quiesceBlockedWalker` counts the cycles a walker WANTED a port while the
  * hold was denying grants. This test drives a program that should produce them and
  * asserts the count is non-zero, which is what converts the UNKNOWN into a HANDLED.
  *
  * WHY THIS PROGRAM. `mmu_swapmode_cpush_race.s` already exists in the corpus and is an
  * exact fit -- it was written for an unrelated reason (a real-hardware boot deadlock at
  * the ROM's `_SwapMMUMode` tail) but it happens to combine everything this window needs:
  * translation ENABLED with real 3-level 8 KB tables on both sides, TTRs that cover
  * neither the code region nor RAM so the very next fetch and the SR-restore load both
  * need COLD table walks, and a `cpusha %dc` to put the sequencer into
  * `S_DRAIN`/`S_APPLY`/`S_MAINTWAIT`. Writing a new program would have been redundant.
  *
  * A ZERO HERE IS A REAL ANSWER, not a broken test -- it would mean this program does not
  * reach the window and the pair stays UNKNOWN until one that does is found. The message
  * says so, so a future failure is not misread as a regression.
  */
class QuiesceHoldReachabilitySpec extends AnyFunSuite {
  private val asmDir = Paths.get("src/test/resources/m68kooo-ported-tests/asm")
  private val Subject = "mmu_swapmode_cpush_race"
  private lazy val subjectSrc = new String(Files.readAllBytes(asmDir.resolve(s"$Subject.s")))
  private lazy val timeout =
    new String(Files.readAllBytes(asmDir.resolve(s"$Subject.timeout"))).trim.toLong

  test("a table walker does contend for a D-cache port inside the maintenance quiesce window",
       VerilatorTest) {
    val probe = new PostureProbe
    // AsWritten: this program installs its OWN page tables and enables translation, so it
    // needs no posture help -- forcing one would change the very thing under test.
    val outcome = PortedTestRunner.run(Subject, subjectSrc, timeout, 1,
      CachePosture.AsWritten, probe)
    info(s"$Subject: outcome=$outcome, quiesceHold-blocked walker cycles=" +
         s"${probe.quiesceBlockedWalker}, itlbWalkReads=${probe.itlbWalkReads}, " +
         s"dtlbWalkReads=${probe.dtlbWalkReads}, cycles=${probe.cycles}")

    // Guard the guard: if the program never walked at all, a zero below would say nothing
    // about `quiesceHold` and everything about the program not doing its job.
    assert(probe.itlbWalkReads + probe.dtlbWalkReads > 0,
      s"vacuous: this program performed NO table walks (itlb=${probe.itlbWalkReads}, " +
      s"dtlb=${probe.dtlbWalkReads}), so it cannot say anything about walker-vs-quiesce " +
      s"contention. The posture or the program has changed underneath this test.")

    assert(probe.quiesceBlockedWalker > 0,
      s"NOT REACHED: no cycle had a table walker requesting a D-cache port while " +
      s"`quiesceHold` was active, so `LsEuPlugin`'s walker-vs-maintenance assertion is " +
      s"still unexercised and that (window, event) pair remains UNKNOWN rather than " +
      s"HANDLED. This is a real answer about coverage, not necessarily a regression: if " +
      s"the program changed, find one that does reach the window rather than deleting " +
      s"this check.")
  }
}
