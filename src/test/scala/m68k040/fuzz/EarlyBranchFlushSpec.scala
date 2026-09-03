package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}

/** Direct proof that RobPlugin's NEW early (EU-resolution-time) branch-mispredict
  * flush trigger (`earlyBranchMispredict`) actually ENGAGES for the Part 29
  * regression shape (`adv_early_flush_younger_branch.s`), not merely that the
  * always-correct, slower retire-gated `branchRedirect` silently carried the test
  * to PASS on its own. `PortedM68kOooSpec` already runs this same program (it is
  * vendored into the shared asm corpus and picked up automatically), so this is a
  * mechanism-level companion assertion, not a duplicate correctness check.
  *
  * See docs/BUG_calibration_word_misplaced_0d00.md (SoC repo) for the newest Part
  * documenting this mechanism's design and full verification.
  */
class EarlyBranchFlushSpec extends AnyFunSuite {
  private val dir = Paths.get("src/test/resources/m68kooo-ported-tests/asm")

  test("adv_early_flush_younger_branch: early flush trigger genuinely fires " +
       "(not just the retire-gated fallback)", VerilatorTest) {
    val name = "adv_early_flush_younger_branch"
    val src = new String(Files.readAllBytes(dir.resolve(s"$name.s")))
    val outcome = PortedTestRunner.run(name, src, timeoutCycles = 200000L)
    outcome match {
      case PortedPass          => ()
      case PortedFail(word)    =>
        fail(f"FAIL sentinel word=0x$word%08x (expected 0x${PortedTestRunner.PassWord}%08x)")
      case PortedHang(cycles)  => fail(s"HANG: no sentinel write within $cycles cycles")
      case PortedGenFail(r)    => fail(s"assemble/toolchain error: $r")
    }
    assert(PortedTestRunner.lastEarlyFlushPulseCount > 0,
      "earlyBranchMispredict never pulsed during this run -- the test's older " +
      "mispredicting branch either didn't exercise the new EU-resolution-time " +
      "trigger, or it regressed back to purely retire-gated behavior. This test " +
      "exists specifically to prove the NEW mechanism engages, not just that the " +
      "old retire-gated backstop covered for it.")
  }
}
