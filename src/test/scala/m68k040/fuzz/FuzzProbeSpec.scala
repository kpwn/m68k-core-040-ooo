package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite

/** One-shot probe: run an arbitrary program (a minimized fuzz repro, or a
  * hand-written variant while triaging one) through the SAME harness as the
  * fuzzer, and print the outcome.
  *
  *   FUZZ_PROG=/path/to/prog.s  — GNU-as source; the harness appends NOTHING,
  *   so the file must end with its own `Lend: bra.s Lend` terminal spin as the
  *   FINAL 2 image bytes (stopPc is computed from the image length).
  *   FUZZ_PROBE_SEED — sim seed (default 1).
  */
class FuzzProbeSpec extends AnyFunSuite {
  test("probe: run FUZZ_PROG through the fuzz lock-step harness", VerilatorTest) {
    val path = sys.env.getOrElse("FUZZ_PROG", fail("set FUZZ_PROG=/path/to/prog.s"))
    val src  = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)))
    val simSeed = sys.env.get("FUZZ_PROBE_SEED").map(_.toInt).getOrElse(1)
    val outcome = FuzzRunner.run(src, simSeed)
    println(s"[probe] $path -> $outcome")
    outcome match {
      case FuzzRunner.Pass => ()
      case FuzzRunner.GenFail(r) => fail(s"probe genfail: $r")
      case FuzzRunner.Diverged(k, d, c) => fail(s"probe diverged [$k]: $d\n$c")
    }
  }
}
