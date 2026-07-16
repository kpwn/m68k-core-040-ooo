package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}

/** Validates PortedTestRunner end-to-end against ONE known-simple vendored
  * test before Task 3 wires up the full data-driven suite. */
class PortedSmokeSpec extends AnyFunSuite {
  test("smoke: neg_not.s passes via PortedTestRunner", VerilatorTest) {
    val path = Paths.get("src/test/resources/m68kooo-ported-tests/asm/neg_not.s")
    val src = new String(Files.readAllBytes(path))
    val outcome = PortedTestRunner.run("neg_not", src, timeoutCycles = 200000)
    println(s"[smoke] neg_not.s -> $outcome")
    outcome match {
      case PortedPass         => ()
      case PortedFail(word)   => fail(f"FAIL sentinel word=0x$word%08x (expected 0x${PortedTestRunner.PassWord}%08x)")
      case PortedHang(cycles) => fail(s"HANG: no sentinel write within $cycles cycles")
      case PortedGenFail(r)   => fail(s"assemble/toolchain error: $r")
    }
  }
}
