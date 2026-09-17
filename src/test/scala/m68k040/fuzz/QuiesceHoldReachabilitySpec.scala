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
  * The mechanism is sound. What the race audit could not establish is that the guarded
  * circumstance is ever REACHED, and an assertion that never sees its circumstance is
  * indistinguishable from one that holds -- `quiesceHold` could be dead for a release
  * and every suite would still be green. That is the same shape as the rest of this
  * campaign (`RobIdBits` documented as load-bearing in two files with zero readers), so
  * the pair was classified UNKNOWN rather than HANDLED.
  *
  * MEASURED SO FAR (2026-09-18), and the reason this is a CANCEL and not a FAILURE:
  *
  *   mmu_swapmode_cpush_race   walks 13671 (itlb 5205 / dtlb 8466) over 557975 cycles,
  *                             quiesceHold-blocked walker cycles = 0
  *
  * The program walks heavily, so the zero is not vacuous -- but it issues exactly ONE
  * `cpusha`, so there is a single maintenance window for a walk to straddle and it did
  * not. That is a statement about the STIMULUS, not about `quiesceHold`, which is why
  * this test sweeps several corpus programs and reports rather than asserting a
  * guarantee it cannot yet establish.
  *
  * WHY THIS IS NOT A RED TEST. A permanently-failing test is exactly the pollution this
  * campaign has just spent its time removing: seventeen `m68k040.ls` reds that were
  * stimulus rot and cost several agents whole cycles in attribution. An unproven
  * REACHABILITY claim is a gap in coverage, not a defect, so it CANCELS with its
  * evidence attached and stays visible in the suite summary. What is asserted is the
  * part that is real: that the programs actually walk, so a future zero still means
  * something.
  *
  * TO CLOSE THIS: find or write a program that interleaves MANY maintenance ops with
  * sustained table walking -- the measurement above says one CPUSH is not enough -- and
  * the `assume` below turns into a pass. The counter
  * (`LsEuPlugin.quiesceBlockedWalker`, surfaced via `PostureProbe`) is already in place;
  * only the stimulus is missing.
  */
class QuiesceHoldReachabilitySpec extends AnyFunSuite {
  private val asmDir = Paths.get("src/test/resources/m68kooo-ported-tests/asm")

  /** Corpus programs that combine table walking with cache maintenance, best first.
    * `maint` is the count of `cpush`/`cinv` sites: the measurement above suggests that
    * is the binding variable, because a walk has to be in flight when one retires. */
  private val Candidates = Seq(
    "mmu_split_store_cold_atc_dcache_2mod4",  // maint 4, MMU-configuring
    "split_store_dirty_clean_line_mix",       // maint 12, the most maintenance in the corpus
    "mmu_swapmode_cpush_race"                 // maint 1, MMU-configuring -- measured 0
  )

  private def timeoutFor(name: String): Long = {
    val f = asmDir.resolve(s"$name.timeout")
    if (Files.exists(f)) new String(Files.readAllBytes(f)).trim.toLong else 2000000L
  }

  test("does any corpus program put a table walker against the maintenance quiesce window?",
       VerilatorTest) {
    val results = Candidates.flatMap { name =>
      val src = asmDir.resolve(s"$name.s")
      if (!Files.exists(src)) None
      else {
        val probe = new PostureProbe
        val outcome = PortedTestRunner.run(name, new String(Files.readAllBytes(src)),
          timeoutFor(name), 1, CachePosture.AsWritten, probe)
        val walks = probe.itlbWalkReads + probe.dtlbWalkReads
        info(f"$name%-40s outcome=$outcome blockedWalkerCycles=${probe.quiesceBlockedWalker} " +
             f"walks=$walks (itlb=${probe.itlbWalkReads} dtlb=${probe.dtlbWalkReads}) " +
             f"cycles=${probe.cycles}")
        Some((name, probe.quiesceBlockedWalker, walks))
      }
    }
    assert(results.nonEmpty, "no candidate program was found on disk")

    // ASSERTED: the sweep is not vacuous. If nothing walked, a zero contention count
    // would say nothing about `quiesceHold` and everything about the programs.
    val totalWalks = results.map(_._3).sum
    assert(totalWalks > 0,
      s"vacuous sweep: no candidate performed a single table walk, so this measurement " +
      s"cannot speak about walker-vs-quiesce contention at all. Results: $results")

    val best = results.maxBy(_._2)
    // NOT ASSERTED: reachability. See the class comment -- an unproven coverage claim
    // cancels with its evidence rather than reddening the suite.
    assume(best._2 > 0,
      s"STILL UNKNOWN: across ${results.size} candidate programs ($totalWalks table walks " +
      s"total) no cycle had a table walker requesting a D-cache port while `quiesceHold` " +
      s"was active. Best was ${best._1} at ${best._2}. LsEuPlugin's walker-vs-maintenance " +
      s"assertion therefore remains UNEXERCISED and that (window, event) pair stays " +
      s"UNKNOWN rather than HANDLED. This is a coverage gap, not a defect: the guard is " +
      s"still correct, nothing has proved it necessary. Close it with a program that " +
      s"interleaves MANY maintenance ops with sustained walking.")

    info(s"REACHED: ${best._1} put a walker against the hold for ${best._2} cycles -- " +
         s"the walker-vs-maintenance pair is now HANDLED rather than UNKNOWN")
  }
}
