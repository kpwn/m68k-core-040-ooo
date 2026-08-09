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
  * NOTE: -z is a substring filter, not an exact match — it may run multiple tests if the substring is a prefix of other test names.
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

  // Binding design §6.1: this manifest is the single named LSU-stress subset.
  // The ordinary loop below remains its AsWritten half (and keeps every existing
  // test name/regression signal unchanged); the second loop reuses the same asm
  // source under ForceCacheableCopyback. PORTED_TEST_DIR may stage only part of
  // the corpus, so registration uses the intersection, while manifest integrity
  // is checked against the canonical vendored directory and cannot silently skip
  // a misspelled/deleted entry.
  private val sweepManifest = Paths.get(sys.env.getOrElse(
    "PORTED_CACHE_SWEEP_LIST",
    "src/test/resources/m68kooo-ported-tests/cache-mode-sweep-list.txt"))
  private val sweepNames: Vector[String] =
    if (Files.isRegularFile(sweepManifest)) {
      new String(Files.readAllBytes(sweepManifest)).linesIterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toVector
    } else Vector.empty
  private val canonicalAsmDir =
    Paths.get("src/test/resources/m68kooo-ported-tests/asm")

  test("ported cache-mode sweep manifest is valid") {
    assert(Files.isRegularFile(sweepManifest),
      s"missing required cache-mode sweep manifest: $sweepManifest")
    assert(sweepNames.nonEmpty,
      s"cache-mode sweep manifest is empty: $sweepManifest")
    val duplicates = sweepNames.groupBy(identity).collect {
      case (name, entries) if entries.size > 1 => name
    }.toVector.sorted
    assert(duplicates.isEmpty,
      s"duplicate cache-mode sweep entries: ${duplicates.mkString(", ")}")
    val missing = sweepNames.filterNot(name =>
      Files.isRegularFile(canonicalAsmDir.resolve(s"$name.s")))
    assert(missing.isEmpty,
      s"cache-mode sweep entries missing from canonical corpus: ${missing.mkString(", ")}")
  }

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

  for (name <- sweepNames if names.contains(name)) {
    test(s"ported-sweep-copyback: $name", VerilatorTest) {
      val src = new String(Files.readAllBytes(dir.resolve(s"$name.s")))
      val timeoutPath = dir.resolve(s"$name.timeout")
      val timeout =
        if (Files.exists(timeoutPath)) new String(Files.readAllBytes(timeoutPath)).trim.toLong
        else DefaultTimeoutCycles
      val outcome = PortedTestRunner.run(
        name, src, timeout, cachePosture = CachePosture.ForceCacheableCopyback)
      outcome match {
        case PortedPass          => ()
        case PortedFail(word)    =>
          fail(f"[copyback sweep] FAIL sentinel word=0x$word%08x " +
            f"(expected 0x${PortedTestRunner.PassWord}%08x)")
        case PortedHang(cycles)  =>
          fail(s"[copyback sweep] HANG: no sentinel write within $cycles cycles " +
            s"(timeout=$timeout)")
        case PortedGenFail(r)    => fail(s"[copyback sweep] assemble/toolchain error: $r")
      }
    }
  }

  if (names.isEmpty) {
    test("no ported tests found (vendoring not run yet?)", VerilatorTest) {
      info(s"$dir is empty or missing -- run tools/fuzz/vendor-ported-tests.sh first")
    }
  }
}
