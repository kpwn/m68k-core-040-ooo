package m68k040.cache

import m68k040.decode.FedSpecsPacketPairingSpec
import org.scalatest.funsuite.AnyFunSuite

/** Design spec section 12.2's required mutation proofs, RUN and RECORDED (implementation
  * plan Task 13).
  *
  * Spec section 12.2's rule, verbatim: *"Each of these must make a NAMED test fail; if
  * one does not, the test suite does not cover the invariant and a test must be added
  * before the change lands."*
  *
  * Each mutation below was applied to `IcachePlugin.scala` temporarily, ONE AT A TIME,
  * the named test was observed to fail with the message quoted, and the mutation was
  * reverted (`git diff --stat -- src/main/` empty at commit). This file is the RECORD.
  * It does not re-run the mutations -- SpinalHDL RTL mutation cannot be driven from
  * ScalaTest -- it pins that every covering test still EXISTS under the name the proof
  * was taken against, so a later refactor cannot quietly retire coverage a mutation
  * proved. That is deliberately a weaker guarantee than re-running; the running is
  * manual and its result is the record above each assertion.
  *
  * ══ M1  Force `s1Unresolved` low ═══════════════════════════════════════════════════
  * Applied as `val s1Unresolved = (s0Valid && !s0Replay && !s0Fault && !s1Hit) && False`.
  * (The plan's literal form -- a second, unconditional `s1Unresolved := False`
  * statement -- does NOT elaborate: SpinalHDL rejects it in
  * `PhaseCheck_noLatchNoOverride` with "ASSIGNMENT OVERLAP", so every test in the suite
  * "fails" at elaboration and nothing is proven. Recorded as a plan defect; the
  * expression form above is the behavioural equivalent and is what was run.)
  *
  *   -> FAILS `IcacheOrderOracleSpec`, "oracle 2: N consecutive fetches to one line
  *      produce exactly one AR":
  *        ArrayBuffer() had length 0 instead of expected length 1 ORACLE 2 VIOLATED:
  *        8 fetches into line 0x1000 produced 0 ARs (), expected exactly 1.
  *        Duplicate-miss suppression is backpressure-based ...; under M3 it is
  *        s1Unresolved's job. (IcacheOrderOracleSpec.scala:195)
  *      Oracle 1 PASSES (responses stay in order; they are simply data-less), which is
  *      why spec section 12.2 says "oracle 1 OR 2". Oracle 3 also fails, on its own
  *      vacuity guard ("oracle 3 never observed the DEMAND MSHR entry live"), and
  *      "oracle 3 (directed): ... ACCEPTED but not yet dispatched demand miss" wedges
  *      (no fill is ever dispatched).
  *
  * ══ M2  Drop the `when(!anyInvalidate)` guard on the `valids` write ════════════════
  *   -> FAILS `IcacheSpec`, "an invalidateAll on the refill commit cycle wins over the
  *      refill's valid write":
  *        IcacheArrayProbe.wayValid(dut.icache, way, set) was true invalidateAll fired
  *        on the refill's own commit cycle but valids(0)(1) is still set -- the refill's
  *        write won over the priority clear (elaboration-order race)
  *        (IcacheSpec.scala:328)
  *      This is risk R4's exact guard; `IcachePlugin.scala`'s comment at the `valids`
  *      write documents the real shipped bug it prevents. Per risk R4's own wording the
  *      test also had to still work UNMODIFIED IN INTENT under M2's install cadence: it
  *      does -- its `dbgAllocCommitPending` trigger and its `aligned` self-check both
  *      still line the invalidate up with the write cycle, unchanged.
  *      `IcacheInvalidateSpec` (3 tests) PASSES under this mutation, so the `IcacheSpec`
  *      test is the SOLE catcher and deleting it would silently retire risk R4.
  *
  * ══ M3  Write the high beat's predecode at the low beat's address ══════════════════
  * Applied as `lineMem(w).write((installSet ## U(0, 1 bits)).asUInt, beatPred ## beatSrc)`.
  *   -> FAILS `IcacheUnifiedArraySpec`, "oracle 4b: the unified array's DATA half is
  *      unchanged and still feeds correct fetches":
  *        UFA data half CORRUPTED: way=0 set=0 beat=0 line=0x1000
  *          got 0xcac3bcb5aea7a099928b847d766f68615a534c453e373029221b140d06fff8f1
  *          exp 0xeae3dcd5cec7c0b9b2aba49d968f88817a736c655e575049423b342d261f1811
  *        (IcacheUnifiedArraySpec.scala:206)
  *   -> and FAILS `FedSpecsPacketPairingSpec`, "Lever C: fed.specs(i) ===
  *      computeOffload(fed.packets(i)), live, both slots":
  *        [movem+ucode] slot0: fed.payload.specs(0) DISAGREES with
  *        computeOffload(fed.payload.packets(0)) ... (FedSpecsPacketPairingSpec.scala:203)
  *      DRIFT NOTE: the plan names `IcacheUnifiedArraySpec`'s M1b READ-PATH test as the
  *      catcher. It is not -- it PASSES, because it compares the response against the
  *      array entry at the same (set, beat) index, so a uniform write-address error
  *      moves both sides equally. Oracle 4b is the address oracle in that suite (it
  *      re-derives every resident beat from the backing-memory image), exactly as that
  *      file's own header claims. Both pins below are kept.
  *
  * ══ M4  Remove the `fillArrayWrActive && (lookupSet === installSet)` block ═════════
  * `val setBlocked = False`.
  *   -> KILLS NOTHING ON ITS OWN. Full `IcacheSpec` (17/17) and full `IcachePrefetchSpec`
  *      (24/24 at the time) stay green. That is not the hazard being imaginary; it is the
  *      guard being REDUNDANT today, in the same way -- and provably so -- as its sibling
  *      `pfInstallSetConflict` (see that signal's "HONEST STATUS" comment).
  *      `fillArrayWrActive` is asserted ONLY from the PREDECODE dwell of a SPECULATIVE
  *      install, whose MSHR entry is not freed until the end of that dwell, so
  *      `pfLookupSetBusy` -- and with it SG-1's `pfAcceptOk` -- already holds the accept
  *      gate shut for exactly `lookupSet === installSet`.
  *      The plan anticipates this and requires a directed test rather than a shrug. One
  *      was written: `IcachePrefetchSpec`, "D3-SET-I: a lookup into the installing SET on
  *      the array-write cycle must not be answered from a tag/valid pair that is one
  *      cycle apart". Its proof is the MATRIX, because no single-lane mutation can be the
  *      proof here (all four rows were run):
  *        no mutation ......................... PASSES
  *        setBlocked removed only ............. PASSES (pfAcceptOk still shuts the gate)
  *        pfAcceptOk opened on the write cycle  PASSES (setBlocked still shuts the gate)
  *        BOTH ................................ FAILS:
  *          2 did not equal 1 D3-SET-I VIOLATED: line 0x5FC0 was fetched over AXI 2
  *          times. A lookup answered on the array-write cycle read the NEW tag against
  *          the OLD (cold) valid bit, reported a miss for a line the cache was installing
  *          that very cycle, and issued a duplicate transaction for it (probe accepted ON
  *          the array-write cycle: true; AR trace=ArrayBuffer((0,24448), (1,24512),
  *          (0,24512))) (IcachePrefetchSpec.scala:1076)
  *      (`pfAcceptOk opened` == `pfAcceptOk := !pfLookupSetBusy || fillArrayWrActive`,
  *      i.e. the gate opened on exactly the cycle `setBlocked` would have closed it.)
  *      The test asserts on ARCHITECTURAL facts -- one AR per line, one way per tag per
  *      set -- not on `cmdPort.ready`, so it survives the SG-1 relaxation that would make
  *      `setBlocked` load-bearing on its own.
  *      NEGATIVE RESULT, recorded because it is the obvious way to write this test and it
  *      does NOT work: the "victim way already VALID under a different tag" variant, where
  *      the split verdict produces a spurious HIT on a line being overwritten, does not
  *      corrupt the delivered data -- measured green under the full double mutation. By
  *      the tag/valid write cycle the incoming line's low beat is already written and its
  *      high beat is written by that same cycle's `lineMem` write, seen write-first by the
  *      read port. The COLD victim way is the variant that discriminates.
  *
  * ══ M5  Route the miss bypass to the array instead of `bypWindow`/`bypPred` ════════
  * `val s1Window = ohOr(s1WayOh, wayWindow)` / `val s1PredBits = ohOr(s1WayOh, wayPred)`,
  * i.e. the `Mux(s0FromMiss, ...)` deleted. (Spec section 12.2 writes `s1FromMiss`; the
  * signal is named `s0FromMiss` in the tree -- it is a Reg read on the S1->rsp cycle.)
  *   -> FAILS `IcacheSpec`, "INHIBITED replay delivers the correct predecode WINDOW, not
  *      window 0":
  *        INHIBITED replay at pc=0x1000 data mismatch: got 0xb9bac8e656e9a774
  *        expected 0x423b342d261f1811 (IcacheSpec.scala:918)
  *   -> and FAILS `IcacheSpec`, "INHIBITED miss aliasing the round-robin victim pointer
  *      must not corrupt the resident way":
  *        inhibited fetch itself must still return correct (distinguishable) data
  *        (IcacheSpec.scala:816)
  *      The arrays were never written for a non-allocated line, so the response carries
  *      stale content from a prior allocation to the same way/set.
  *
  * ══ M6  The M4 "cycle T" residual -- spec section 12.2's SIXTH entry ═══════════════
  * Not in spec section 12.2's original list: it was opened by Task 12's review fix for
  * finding I1 and explicitly handed to this task. `s0KillsWindowQ` closes the T+1 half
  * of the window-kill hole and cycle T is an accepted, documented, one-cycle rule-P1
  * residual. It was documented in a comment and exercised by NOTHING, which is not an
  * acceptable state for a safety-rule exemption; a bounded violation still has to be
  * observable, or "bounded" is an assertion nobody is checking.
  *   -> New test: `IcachePrefetchSpec`, "P1 residual (cycle T): a fetch whose LIVE
  *      verdict is FAULT allocates AT MOST ONE speculative line, in its own page".
  *      Green on the shipped RTL, and it observes the residual actually happening (one
  *      speculative AR to 0x2040, the frontier's own next line, same page).
  *   -> MUTATION (drop `&& !s0KillsWindowQ` from the allocator's enable, i.e. re-open
  *      T+1, which is the regression this term exists to prevent) -> FAILS:
  *        RULE-P1 BOUND VIOLATED: 2 speculative ARs were launched out of a window
  *        condemned on cycle T; IcachePlugin.scala's s0KillsWindowQ comment bounds this
  *        residual at ONE line.
  *
  * ══ SPOT-CHECKS OF EARLIER TASKS' MUTATION CLAIMS ═════════════════════════════════
  * The six records above are the ones spec section 12.2 requires. Tasks 5-12 also made
  * mutation claims in their own commit messages, and a record is only worth what its
  * weakest entry is, so two of those were re-run from scratch here rather than restated.
  * Both reproduce EXACTLY as claimed:
  *
  *   - Task 12 (`ca793c1`): drop `demandSetOwned`'s `s1Unresolved && (s0Set ===
  *     pfCandSet)` lane -> `IcacheOrderOracleSpec`, "oracle 3 (directed): no speculative
  *     allocation into the set of an ACCEPTED but not yet dispatched demand miss" FAILS
  *     with "ORACLE 3 VIOLATED: live MSHR entries Vector(0, 2) own sets Vector(1, 1)".
  *     The commit claimed "entries 0 and 2 both owning set 1". Matches.
  *
  *   - Task 11 (`78b1a21`, deviation 1): `rspValidReg := s0Valid` (dropping
  *     `&& !s1Unresolved`) -> oracles 1, 2 AND 3 fail, oracle 1 with "response #1 has
  *     pc=0x1000 but the #1 ACCEPTED command was pc=0x1008". The commit claimed "fails
  *     oracles 1, 2 and 3". Matches.
  */
class IcacheMutationProofSpec extends AnyFunSuite {

  /** Pins that a suite still declares the test a mutation proof was taken against. A
    * renamed or deleted test breaks THIS, loudly and in the fast gate, instead of
    * silently retiring a proven invariant. Matching is case-insensitive substring, so a
    * cosmetic rewording of the tail of a test name does not fail spuriously. */
  private def declares(suiteClass: Class[_], substring: String): Boolean = {
    val suite = suiteClass.getDeclaredConstructor().newInstance()
      .asInstanceOf[org.scalatest.Suite]
    suite.testNames.exists(_.toLowerCase.contains(substring.toLowerCase))
  }

  private def requireTest(suiteClass: Class[_], substring: String): Unit =
    assert(declares(suiteClass, substring),
      s"${suiteClass.getSimpleName} no longer declares a test whose name contains " +
      s"'$substring'. A spec section 12.2 mutation proof was taken against that test; " +
      s"renaming or deleting it retires the proof. Re-run the mutation against whatever " +
      s"replaced it and update IcacheMutationProofSpec's record, or restore the name.")

  test("M1: the response-order and one-AR-per-line oracles still exist") {
    requireTest(classOf[IcacheOrderOracleSpec], "in order")
    requireTest(classOf[IcacheOrderOracleSpec], "exactly one AR")
  }

  test("M2: the invalidate-vs-allocate race test still exists") {
    requireTest(classOf[IcacheSpec], "invalidateAll on the refill commit cycle")
  }

  test("M3: the unified-array address oracle and the packet-pairing test still exist") {
    requireTest(classOf[IcacheUnifiedArraySpec], "oracle 4b")
    requireTest(classOf[FedSpecsPacketPairingSpec], "Lever C")
  }

  test("M4: the D3-SET-I directed test still exists") {
    requireTest(classOf[IcachePrefetchSpec], "D3-SET-I")
  }

  test("M5: the INHIBITED-replay corruption tests still exist") {
    requireTest(classOf[IcacheSpec], "INHIBITED replay")
    requireTest(classOf[IcacheSpec], "INHIBITED miss aliasing")
  }

  test("M6: the cycle-T rule-P1 residual test still exists") {
    requireTest(classOf[IcachePrefetchSpec], "P1 residual (cycle T)")
  }
}
