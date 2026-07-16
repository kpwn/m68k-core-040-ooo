package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** One named ScalaTest case per vendored m68k-ooo directed asm test (see
  * tools/fuzz/vendor-ported-tests.sh + PortedTestRunner.scala).
  *
  * Run everything vendored so far:
  *   ~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec"
  * Run one test:
  *   ~/sbt/bin/sbt "testOnly m68k040.fuzz.PortedM68kOooSpec -- -z neg_not"
  */
class PortedM68kOooSpec extends AnyFunSuite {
  private val DefaultTimeoutCycles = 200000L
  // PORTED_TEST_DIR lets tools/fuzz/ported-sweep.sh point this suite at a
  // scratch subset for batching (ScalaTest's -z is a substring filter, not
  // an OR-of-names matcher, so batching by staging a directory subset is
  // the robust option -- see Task 6).
  private val dir = Paths.get(sys.env.getOrElse(
    "PORTED_TEST_DIR", "src/test/resources/m68kooo-ported-tests/asm"))
  private val names: Vector[String] =
    if (Files.isDirectory(dir)) {
      val listing = Files.list(dir)
      try {
        listing.iterator().asScala
          .map(_.getFileName.toString)
          .filter(_.endsWith(".s"))
          .map(_.stripSuffix(".s"))
          .toVector.sorted
      } finally listing.close()
    } else Vector.empty

  for (name <- names) {
    test(s"ported: $name", VerilatorTest) {
      val src = new String(Files.readAllBytes(dir.resolve(s"$name.s")))
      val timeoutPath = dir.resolve(s"$name.timeout")
      val timeout =
        if (Files.exists(timeoutPath)) new String(Files.readAllBytes(timeoutPath)).trim.toLong
        else DefaultTimeoutCycles
      val outcome = PortedTestRunner.run(name, src, timeout)
      outcome match {
        case PortedPass          => ()
        case PortedFail(word)    =>
          fail(f"FAIL sentinel word=0x$word%08x (expected 0x${PortedTestRunner.PassWord}%08x)")
        case PortedHang(cycles)  =>
          fail(s"HANG: no sentinel write within $cycles cycles (timeout=$timeout)")
        case PortedGenFail(r)    => fail(s"assemble/toolchain error: $r")
      }
    }
  }

  if (names.isEmpty) {
    test("no ported tests found (vendoring not run yet?)", VerilatorTest) {
      info(s"$dir is empty or missing -- run tools/fuzz/vendor-ported-tests.sh first")
    }
  }
}
