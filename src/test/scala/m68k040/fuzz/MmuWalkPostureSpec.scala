package m68k040.fuzz

import m68k040.VerilatorTest
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}

/** Proof obligations for the `ForceMmuWalkCopyback` posture itself, kept separate from
  * the corpus sweep in `PortedM68kOooSpec`.
  *
  * Two things have to be true before any "the corpus passes with caches + MMU on" claim
  * means anything, and neither can be established by reading the setup code:
  *
  *  1. **The posture is really engaged.** A "cached + MMU" run that silently executed with
  *     `TC.E = 0`, or with a TTR blanketing memory so the TLB is never consulted, is a
  *     VACUOUS pass. `posture engagement is measurable...` below runs the identical program
  *     under both postures and asserts the measured difference: zero table walks and zero
  *     D-cache hits under `AsWritten`, nonzero under `ForceMmuWalkCopyback`. That the SAME
  *     counters read zero in the baseline is what makes them evidence rather than decoration.
  *
  *  2. **The posture can fail.** A harness that maps everything correctly no matter what it
  *     is told to map cannot detect a mistranslation, so a green sweep would prove nothing.
  *     The two `negative control` tests deliberately corrupt the page table -- once in the
  *     PPN (a leaf descriptor pointing somewhere else) and once in residency (a pointer
  *     descriptor cleared) -- and assert the run stops passing. The second also asserts the
  *     harness's own hole detector NAMES the missing block, which is the mechanism the
  *     sweep uses to self-calibrate its map.
  */
class MmuWalkPostureSpec extends AnyFunSuite {
  private val asmDir = Paths.get("src/test/resources/m68kooo-ported-tests/asm")
  /** A short, entirely ordinary corpus program: it stores and reloads through memory and
    * finishes in a few hundred cycles, so each negative control is cheap. Nothing about
    * these tests is specific to it. */
  private val Subject = "movem_basic"
  private lazy val subjectSrc = new String(Files.readAllBytes(asmDir.resolve(s"$Subject.s")))
  private val Timeout = 200000L

  test("page-table builder produces an identity map that a hand walk resolves") {
    // No simulation: walk the generated descriptor image the way the hardware would and
    // check PA == VA. Catches a table-layout/index-width mistake without a 30 s sim.
    val blocks = Set(0L, 3L, MmuWalkPosture.blockOf(0x40800000L),
                     MmuWalkPosture.blockOf(PortedTestRunner.SentinelAddr))
    val map = MmuWalkDriver.buildFor(blocks, pages8K = false)
    val mem = map.descriptors.toMap
    def walk(va: Long): Long = {
      val rootIdx = (va >>> 25) & 0x7f
      val ptrIdx  = (va >>> 18) & 0x7f
      val pgIdx   = (va >>> 12) & 0x3f
      val rootD = mem.getOrElse(map.rootBase + rootIdx * 4, 0L)
      assert((rootD & 2L) != 0L, f"root descriptor for VA 0x$va%08x is not resident")
      val ptrD = mem.getOrElse((rootD & 0xfffffff0L) + ptrIdx * 4, 0L)
      assert((ptrD & 2L) != 0L, f"pointer descriptor for VA 0x$va%08x is not resident")
      val pgD = mem.getOrElse((ptrD & 0xfffffff0L) + pgIdx * 4, 0L)
      assert((pgD & 3L) == 1L, f"page descriptor for VA 0x$va%08x is not resident")
      (pgD & 0xfffff000L) | (va & 0xfffL)
    }
    for (va <- Seq(0x00000000L, 0x00000ff0L, 0x000C1234L, 0x40800000L, 0x4083F004L,
                   PortedTestRunner.SentinelAddr))
      assert(walk(va) == va, f"identity map broken at VA 0x$va%08x -> 0x${walk(va)}%08x")

    // The sentinel page must be INHIBITED (CM[1] set) or the harness -- which polls the
    // AXI backing store -- could never observe a PASS word held in a dirty copyback line.
    val sPa = PortedTestRunner.SentinelAddr
    val sLeaf = map.leafTableBase(MmuWalkPosture.blockOf(sPa))
    val sDesc = mem(sLeaf + ((sPa >>> 12) & 0x3f) * 4)
    assert(((sDesc >>> 6) & 1L) == 1L,
      f"sentinel page descriptor 0x$sDesc%08x is cacheable; the harness could never see completion")
    // ...and an ordinary data page must NOT be, or nothing is being cached.
    val cLeaf = map.leafTableBase(MmuWalkPosture.blockOf(0x40800000L))
    val cDesc = mem(cLeaf)
    assert(((cDesc >>> 5) & 3L) == MmuWalkPosture.CmCopyback.toLong,
      f"code/data page descriptor 0x$cDesc%08x is not COPYBACK")
  }

  test("posture engagement is measurable, and AsWritten measurably lacks it", VerilatorTest) {
    val r = MmuWalkDriver.runWithRealTables(Subject, subjectSrc, Timeout)
    info(s"mmu-walk : ${r.probe.summary}")
    info(s"as-written: ${r.baseline.summary}")

    // The baseline half. If these ever become nonzero the discriminator above has lost its
    // meaning and every "the posture is engaged" assertion in the sweep becomes vacuous.
    assert(r.baseline.itlbWalkStarts == 0 && r.baseline.dtlbWalkStarts == 0,
      s"AsWritten unexpectedly ran table walks: ${r.baseline.summary}")
    assert(r.baseline.dcLoadHits == 0 && r.baseline.dcStoreHits == 0,
      s"AsWritten unexpectedly hit in the D-cache (CACR=0 inhibits every access): " +
        s"${r.baseline.summary}")

    // The posture half.
    assert(r.probe.itlbWalkStarts > 0, s"no ITLB walk: ${r.probe.summary}")
    assert(r.probe.dtlbWalkStarts > 0, s"no DTLB walk: ${r.probe.summary}")
    assert(r.probe.walkStores > 0,
      s"no walker U/M descriptor writeback -- no leaf descriptor was consumed: ${r.probe.summary}")
    assert(r.probe.dcLoadHits > 0, s"no D-cache load hit: ${r.probe.summary}")
    assert(r.probe.itlbDescFaults == 0 && r.probe.dtlbDescFaults == 0,
      s"a descriptor read bus-faulted -- the arena is not backed by memory: ${r.probe.summary}")
    assert(r.outcome == PortedPass, s"$Subject did not pass under the posture: ${r.outcome}")
  }

  test("negative control: a leaf descriptor with the wrong PPN stops the run passing",
       VerilatorTest) {
    val good = MmuWalkDriver.runWithRealTables(Subject, subjectSrc, Timeout)
    assume(good.outcome == PortedPass, "baseline for the negative control must pass first")

    // Re-point the SENTINEL page at a different (still decoded, otherwise unused) physical
    // page and change nothing else. If the leaf descriptor's PPN is genuinely what the
    // hardware translates through, the completion word lands at 0x00600000 instead and the
    // harness -- which polls 0xFFFF0000 in the AXI backing store -- can no longer see it.
    // If the run still passed, translation was NOT coming from the page table.
    val sPa = PortedTestRunner.SentinelAddr
    val sBlock = MmuWalkPosture.blockOf(sPa)
    val sLeaf = good.map.leafTableBase(sBlock)
    val victim = sLeaf + ((sPa >>> 12) & 0x3f) * 4
    val decoy = 0x00600000L
    val broken = good.map.copy(descriptors = good.map.descriptors.map {
      case (a, _) if a == victim => a -> MmuWalkPosture.pageDesc(decoy, MmuWalkPosture.CmInhibitedSer)
      case other                 => other
    })
    assert(broken.descriptors != good.map.descriptors, "negative control did not modify anything")

    val probe = new PostureProbe
    val outcome = PortedTestRunner.run(Subject, subjectSrc, Timeout, 1,
      CachePosture.ForceMmuWalkCopyback(broken), probe)
    info(s"mistranslated-sentinel run: $outcome -- ${probe.summary}")
    assert(outcome != PortedPass,
      s"NEGATIVE CONTROL FAILED: the run still PASSED with the sentinel page mistranslated " +
        f"to 0x$decoy%08x, so the posture is not actually translating through the page table")
  }

  test("negative control: a cleared pointer descriptor stops the run and is reported as a hole",
       VerilatorTest) {
    val good = MmuWalkDriver.runWithRealTables(Subject, subjectSrc, Timeout)
    assume(good.outcome == PortedPass, "baseline for the negative control must pass first")

    // Make the CODE block non-resident by zeroing its pointer descriptor. Instruction fetch
    // must then fault, and -- just as importantly -- the harness's own hole detector must
    // NAME that block, because that detector is what lets the sweep extend its map instead
    // of blaming the core for a harness gap.
    val codeBlock = MmuWalkPosture.blockOf(PortedTestRunner.loadAddr)
    val ptrEntry = good.map.ptrTableBase(codeBlock >>> 7) + (codeBlock & 0x7fL) * 4
    val broken = good.map.copy(descriptors = good.map.descriptors.map {
      case (a, _) if a == ptrEntry => a -> 0L
      case other                   => other
    })

    val probe = new PostureProbe
    val outcome = PortedTestRunner.run(Subject, subjectSrc, Timeout, 1,
      CachePosture.ForceMmuWalkCopyback(broken), probe)
    info(s"non-resident-code run: $outcome -- ${probe.summary} holes=${probe.holeSummary}")
    assert(outcome != PortedPass,
      "NEGATIVE CONTROL FAILED: the run still PASSED with the code block's pointer " +
        "descriptor cleared, so instruction fetch was not translated by the page table")
    assert(probe.holeBlocks.contains(codeBlock),
      f"hole detector did not name the unmapped code block 0x${MmuWalkPosture.blockBase(codeBlock)}%08x; " +
        s"it reported ${probe.holeSummary}")
  }
}
