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

  // The THIRD posture (see MmuWalkPosture.scala): D-cache ON *and* real 3-level table
  // walks, with all four TTRs zero so nothing can short-circuit the walk. Same wiring
  // convention as the copyback sweep above -- a manifest plus an env override, so it can
  // be run narrowly (the checked-in list) or broadly (point PORTED_MMU_SWEEP_LIST at
  // any subset, e.g. the full corpus).
  //
  // The checked-in manifest deliberately EXCLUDES every corpus program that programs
  // %urp/%srp/%tc/%itt*/%dtt* itself: those install their own translation and would
  // simply take the posture over, so they gain nothing here and would defeat the
  // non-vacuity assertions below.
  private val mmuSweepManifest = Paths.get(sys.env.getOrElse(
    "PORTED_MMU_SWEEP_LIST",
    "src/test/resources/m68kooo-ported-tests/mmu-walk-sweep-list.txt"))
  private val mmuSweepNames: Vector[String] =
    if (Files.isRegularFile(mmuSweepManifest)) {
      new String(Files.readAllBytes(mmuSweepManifest)).linesIterator
        .map(_.trim).filter(_.nonEmpty).toVector
    } else Vector.empty
  private val mmuSweep8K = sys.env.get("PORTED_MMU_SWEEP_8K").exists(v => v == "1" || v == "true")
  // Diagnostic knob: plant the leaf descriptors with U = M = 1 so no walk ever queues a
  // deferred U/M descriptor writeback. Everything else about the posture is unchanged, so
  // a failure that survives this is in the walker's descriptor READ path and one that does
  // not is in the U/M writeback path. Not the default -- the default posture deliberately
  // exercises both.
  private val mmuSweepPresetUM =
    sys.env.get("PORTED_MMU_SWEEP_PRESET_UM").exists(v => v == "1" || v == "true")

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

  test("ported MMU-walk sweep manifest is valid") {
    assert(Files.isRegularFile(mmuSweepManifest),
      s"missing required MMU-walk sweep manifest: $mmuSweepManifest")
    assert(mmuSweepNames.nonEmpty, s"MMU-walk sweep manifest is empty: $mmuSweepManifest")
    val duplicates = mmuSweepNames.groupBy(identity).collect {
      case (name, entries) if entries.size > 1 => name
    }.toVector.sorted
    assert(duplicates.isEmpty, s"duplicate MMU-walk sweep entries: ${duplicates.mkString(", ")}")
    val missing = mmuSweepNames.filterNot(name =>
      Files.isRegularFile(canonicalAsmDir.resolve(s"$name.s")))
    assert(missing.isEmpty,
      s"MMU-walk sweep entries missing from canonical corpus: ${missing.mkString(", ")}")
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

  for (name <- mmuSweepNames if names.contains(name)) {
    test(s"ported-sweep-mmuwalk: $name", VerilatorTest) {
      val src = new String(Files.readAllBytes(dir.resolve(s"$name.s")))
      val timeoutPath = dir.resolve(s"$name.timeout")
      val timeout =
        if (Files.exists(timeoutPath)) new String(Files.readAllBytes(timeoutPath)).trim.toLong
        else DefaultTimeoutCycles
      val r = MmuWalkDriver.runWithRealTables(name, src, timeout, pages8K = mmuSweep8K,
                                             presetUM = mmuSweepPresetUM)
      val ctx =
        s"posture: ${r.probe.summary}\n" +
        s"baseline(AsWritten): ${r.baseline.summary} -> ${r.baselineOutcome}\n" +
        s"map: ${r.map.blockCount} block(s), arena=0x${r.map.arenaBase.toHexString}, " +
        s"attempts=${r.attempts}${r.extensions.map("\n  " + _).mkString}"
      info(ctx)

      // ---- POSTURE NON-VACUITY, asserted before the program's own verdict -----------
      // A "cached + MMU" run that silently executed with the MMU off, or with a TTR
      // covering everything, is a vacuous pass and is worth strictly less than a red
      // test. These are measurements taken from inside the running DUT, not a restatement
      // of the setup code.
      assert(r.probe.itlbWalkStarts > 0,
        s"VACUOUS POSTURE [$name]: no ITLB table walk ever started -- instruction fetch " +
          s"was not translated by a page table.\n$ctx")
      assert(r.probe.dtlbWalkStarts > 0,
        s"VACUOUS POSTURE [$name]: no DTLB table walk ever started -- data accesses were " +
          s"not translated by a page table.\n$ctx")
      if (!mmuSweepPresetUM)
        assert(r.probe.walkStores > 0,
          s"VACUOUS POSTURE [$name]: no walker U/M descriptor writeback ever fired, so no " +
            s"leaf descriptor was actually consumed and updated.\n$ctx")
      assert(r.probe.dcLoadHits + r.probe.dcStoreHits > 0,
        s"VACUOUS POSTURE [$name]: the D-cache reported zero hits, so accesses were still " +
          s"effectively inhibited and no line-crossing behaviour was exercised.\n$ctx")
      // A descriptor READ that bus-faulted means the descriptor arena itself landed on an
      // undecoded physical address -- a harness placement bug, never a core defect.
      assert(r.probe.itlbDescFaults == 0 && r.probe.dtlbDescFaults == 0,
        s"HARNESS BUG [$name]: a page-table descriptor read took a bus fault, so the " +
          s"descriptor arena is not backed by memory.\n$ctx")

      // ---- hole policy -------------------------------------------------------------
      // A walk into a region the map does not cover is NOT automatically a harness bug:
      // a wrong-path speculative fetch or a speculative load off a stale address register
      // legitimately asks to translate garbage, and the core is supposed to fault-and-
      // squash it. That is exactly what the board does. So holes are fatal only when the
      // program ALSO failed -- in which case they are the leading suspect and the failure
      // must be triaged as a harness gap rather than reported as a core defect. (The
      // driver has already re-built the map around any hole it saw and re-run; see
      // MmuWalkDriver.runWithRealTables.)
      val holes = r.probe.holeBlocks.nonEmpty || r.probe.holeRegions.nonEmpty

      r.outcome match {
        case PortedPass       => ()
        case other if holes =>
          fail(s"[mmu-walk sweep] HARNESS MAP HOLE (not a core verdict) [$name]: $other, " +
            s"and a walk demanded translation the harness page table does not provide " +
            s"after ${r.attempts} attempt(s): ${r.probe.holeSummary}\n$ctx")
        case PortedFail(word) =>
          fail(f"[mmu-walk sweep] FAIL sentinel word=0x$word%08x " +
            f"(expected 0x${PortedTestRunner.PassWord}%08x)%n$ctx")
        case PortedHang(cycles) =>
          fail(s"[mmu-walk sweep] HANG: no sentinel write within $cycles cycles " +
            s"(timeout=$timeout)\n$ctx")
        case PortedGenFail(reason) =>
          fail(s"[mmu-walk sweep] assemble/toolchain error: $reason")
      }
    }
  }

  if (names.isEmpty) {
    test("no ported tests found (vendoring not run yet?)", VerilatorTest) {
      info(s"$dir is empty or missing -- run tools/fuzz/vendor-ported-tests.sh first")
    }
  }
}
