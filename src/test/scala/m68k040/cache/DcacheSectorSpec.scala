package m68k040.cache

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.isa.Size
import m68k040.ls.BehavioralMemAgent
import m68k040.mmu.{DIdentityTranslationPlugin, MmuControlPlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** SLICE `D3-BURST` -- SECTORED 64-BYTE L1D LINES.
  *
  * ⚠ NAMING (plan rule GC9): `D3-BURST`, never bare "D3" -- `D3-SET` is the unrelated
  * one-outstanding-fill-per-set invariant.
  *
  * ── WHAT THIS FILE IS FOR ────────────────────────────────────────────────────────
  * The first test is the REASON the widening is sectored rather than plain, and it is
  * WRITTEN TO FAIL ON AN UNSECTORED 64-BYTE LINE. The 68040's architectural cache line
  * is 16 bytes, so `CINVL` on address A must discard exactly the 16 bytes containing A.
  * With one valid+dirty bit per 64-byte line it would discard 64 and silently drop up
  * to 48 bytes of dirty data the programmer never asked to lose. Per-16-byte sectors
  * are therefore what make the widening CORRECT, not merely cheaper.
  *
  * Every test here runs the SECTORED arm only (`sectored = true`). The unsectored arm's
  * behaviour is covered by the existing `DcacheSpec`/`DcacheFillForwardSpec`, and the
  * round-trip netlist diff -- not a test -- is what proves the OFF arm is unchanged.
  *
  * ── THE FOUR THINGS WORTH TESTING, AND WHY EACH ONE CAN ACTUALLY FAIL ────────────
  *  1. `CINVL` granularity. Fails on an unsectored line (over-invalidation) AND on a
  *     sectored line whose refill path bursts the whole line into a TAG-MATCHING way
  *     (refill clobber of a dirty sibling). Those are two independent defects that
  *     this one test catches, which is why it is written as a read-back of the
  *     siblings AFTER re-touching the invalidated sector.
  *  2. CRITICAL-SECTOR SELECTION. `fillForward` extracts the load's datum from
  *     `missLine`; at `len = 3` the beat carrying `r.last` is the line's LAST sector,
  *     so without per-beat selection a load anywhere but sector 3 returns the wrong 16
  *     bytes. Tested at all four sector positions: a broken implementation returns
  *     sector 3's bytes for sectors 0-2, so three of the four assertions fire.
  *  3. THE BURST ITSELF. One cold miss must install all four sectors, so the next
  *     three accesses hit and issue no further AR. This is the bandwidth mechanism;
  *     without it sectoring is a pure capacity regression.
  *  4. THE EVICTION WALK. A line with two NON-ADJACENT dirty sectors must write back
  *     exactly those two, leave the clean ones untouched in memory, and not push the
  *     stale contents of a sector it never wrote. The non-adjacency is deliberate: a
  *     walk that stops at the first clean sector passes an all-dirty test.
  */
class DcacheSectorSpec extends AnyFunSuite {

  class Dut(sectored: Boolean, fillForward: Boolean) extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param   = new ParamPlugin(M68kParams())
    val xlate   = new DIdentityTranslationPlugin
    val dcache  = new DcachePlugin(sectored = sectored, fillForward = fillForward)
    val probe   = new DcacheProbePlugin
    val mmuCtrl = new MmuControlPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, xlate, dcache, probe, mmuCtrl)) }
  }

  // Compiled ONCE and shared: Verilator compilation is ~30 s and every test below uses
  // the same configuration. `fillForward = true` deliberately, because critical-sector
  // selection exists to serve exactly that path -- with it off, test 2 would pass
  // vacuously by taking the REPLAY array relaunch instead.
  private lazy val sectoredDut =
    M68kSim().withVerilator.compile(new Dut(sectored = true, fillForward = true))
  /** The UNSECTORED arm, built only for the capacity probe below -- the one measurement
    * that is meaningless without both arms in the same units. */
  private lazy val flatDut =
    M68kSim().withVerilator.compile(new Dut(sectored = false, fillForward = true))

  /** A distinctive per-address byte so a wrong-sector read is unmistakable rather than
    * plausibly-zero. */
  private def memByte(addr: Long): Int = ((addr * 7 + 0x41) & 0xff).toInt
  private def memLong(base: Long): BigInt =
    (0 until 4).foldLeft(BigInt(0))((a, i) => (a << 8) | BigInt(memByte(base + i)))

  private val SECTOR = 16
  private val LINE   = 64
  /** Sectors per line in the sectored arm; used only by the capacity probe. */
  private val nSecTest = LINE / SECTOR

  private def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent) = {
    val cd = dut.clockDomain
    cd.forkStimulus(period = 10)
    val mem = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
    dut.probe.logic.loadCmdIn.valid #= false
    dut.probe.logic.loadProbeIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.valid #= false
    dut.probe.logic.loadProbeCancelIn.payload.all #= false
    dut.probe.logic.storeIn.valid #= false
    dut.probe.logic.maintCmdIn.valid #= false
    dut.probe.logic.maintCmdIn.payload.push #= false
    dut.probe.logic.maintCmdIn.payload.invalidate #= false
    dut.probe.logic.maintCmdIn.payload.scope #= 0
    dut.probe.logic.maintCmdIn.payload.sel #= 0
    dut.probe.logic.maintCmdIn.payload.addr #= 0
    cd.waitSampling(4)
    cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)
    (cd, mem)
  }

  /** A LONG read out of the memory model, assembled big-endian from BYTES.
    * Deliberately NOT `peek128(addr) & 0xFFFFFFFF`: `peek128` returns the whole
    * 16-byte lane-packed beat, so masking the low 32 bits reads the WRONG END of the
    * sector -- which is exactly how the first version of this spec reported two
    * phantom RTL failures (`0xd0d0` against `0xd0d00000`). Bytes are unambiguous. */
  private def memPeekLong(mem: BehavioralMemAgent, base: Long): BigInt =
    (0 until 4).foldLeft(BigInt(0))((a, i) => (a << 8) | BigInt(mem.peekByte(base + i) & 0xff))

  private def fillMem(mem: BehavioralMemAgent, base: Long, bytes: Int): Unit =
    for (i <- 0 until bytes) mem.pokeByte(base + i, memByte(base + i))

  private def load(dut: Dut, cd: ClockDomain, vaddr: Long,
                   size: SpinalEnumElement[Size.type] = Size.LONG,
                   cacheMode: SpinalEnumElement[CacheMode.type] = CacheMode.COPYBACK,
                   budget: Int = 400): BigInt = {
    dut.probe.logic.loadCmdIn.valid #= true
    dut.probe.logic.loadCmdIn.payload.vaddr #= vaddr
    dut.probe.logic.loadCmdIn.payload.paddr #= vaddr   // identity translation
    dut.probe.logic.loadCmdIn.payload.size #= size
    dut.probe.logic.loadCmdIn.payload.cacheMode #= cacheMode
    dut.probe.logic.loadCmdIn.payload.token #= 0
    cd.waitSamplingWhere(dut.probe.logic.loadCmdIn.ready.toBoolean &&
                         dut.probe.logic.loadCmdIn.valid.toBoolean)
    dut.probe.logic.loadCmdIn.valid #= false
    var n = 0
    while (!dut.probe.logic.loadRspOut.valid.toBoolean && n < budget) { cd.waitSampling(); n += 1 }
    assert(n < budget, f"no load response for 0x$vaddr%x within $budget cycles")
    dut.probe.logic.loadRspOut.payload.data.toBigInt
  }

  private def store(dut: Dut, cd: ClockDomain, paddr: Long, data: BigInt,
                    cacheMode: SpinalEnumElement[CacheMode.type] = CacheMode.COPYBACK): Unit = {
    dut.probe.logic.storeIn.valid #= true
    dut.probe.logic.storeIn.payload.paddr #= paddr
    dut.probe.logic.storeIn.payload.data #= data
    dut.probe.logic.storeIn.payload.size #= Size.LONG
    dut.probe.logic.storeIn.payload.useStrb #= false
    dut.probe.logic.storeIn.payload.strb #= 0
    dut.probe.logic.storeIn.payload.lineData #= 0
    dut.probe.logic.storeIn.payload.cacheMode #= cacheMode
    dut.probe.logic.storeIn.payload.precise #= false
    cd.waitSamplingWhere(dut.probe.logic.storeIn.valid.toBoolean &&
                         dut.probe.logic.storeIn.ready.toBoolean)
    dut.probe.logic.storeIn.valid #= false
    // Long enough for a COPYBACK store to complete on-chip, and for a store MISS to
    // finish its whole write-allocate excursion (eviction walk + 4-beat burst + merge).
    cd.waitSampling(120)
  }

  private def maint(dut: Dut, cd: ClockDomain, push: Boolean, invalidate: Boolean,
                    scope: Int, addr: Long, budget: Int = 40000): Unit = {
    dut.probe.logic.maintCmdIn.valid #= true
    dut.probe.logic.maintCmdIn.payload.push #= push
    dut.probe.logic.maintCmdIn.payload.invalidate #= invalidate
    dut.probe.logic.maintCmdIn.payload.scope #= scope
    dut.probe.logic.maintCmdIn.payload.sel #= 1            // DC only
    dut.probe.logic.maintCmdIn.payload.addr #= addr
    cd.waitSampling()
    dut.probe.logic.maintCmdIn.valid #= false
    var n = 0
    var done = false
    while (!done && n < budget) { cd.waitSampling(); n += 1; done = dut.probe.logic.maintDoneOut.toBoolean }
    assert(done, s"cache-maintenance walk never pulsed maintDone within $budget cycles")
    cd.waitSampling(8)
  }

  /** How many AR beats the D-cache issued while `body` ran. The mechanism claim of this
    * slice is a TRANSACTION COUNT, so it is counted rather than inferred from timing. */
  private def countAr(dut: Dut, cd: ClockDomain)(body: => Unit): Int = {
    var n = 0
    val watcher = fork {
      while (true) {
        cd.waitSampling()
        if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) n += 1
      }
    }
    body
    cd.waitSampling(4)
    watcher.terminate()
    n
  }

  // ───────────────────────────────────────────────────────────────────────────────
  // 1. THE LOAD-BEARING ONE.
  // ───────────────────────────────────────────────────────────────────────────────
  test("CINVL discards ONE 16-byte sector and leaves the other three sectors' dirty " +
       "data intact", VerilatorTest) {
    sectoredDut.doSim("cinvl-sector-granularity") { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x21000L                    // 64 B aligned, so sector k is base + 16k
      fillMem(mem, base, LINE)

      // Dirty ALL FOUR sectors of one line, each with its own recognisable value.
      val stored = (0 until 4).map(k => BigInt(0xD0D00000L + k)).toVector
      for (k <- 0 until 4) store(dut, cd, base + k * SECTOR, stored(k))
      // Precondition: every sector really is dirty. Without this the test could pass
      // because nothing was ever dirty in the first place.
      for (k <- 0 until 4)
        assert(load(dut, cd, base + k * SECTOR) == stored(k),
          f"setup failed: sector $k did not read back its stored value")

      // CINVL sector 1 ONLY. `invalidate` without `push` -- CINV discards dirty data by
      // design; that is exactly what distinguishes it from CPUSH, and what makes
      // over-invalidation a DATA LOSS bug rather than a performance one.
      maint(dut, cd, push = false, invalidate = true, scope = 1, addr = base + 1 * SECTOR)

      // Sector 1 must now come back from MEMORY (its store was legitimately discarded).
      // This load is a SECTOR MISS -- the tag still matches, only this sector's valid
      // bit is clear -- so it also exercises the one refill path that could clobber a
      // dirty sibling.
      val s1 = load(dut, cd, base + 1 * SECTOR)
      assert(s1 == memLong(base + 1 * SECTOR),
        f"CINVL must DISCARD sector 1's dirty store: read 0x$s1%x, want memory's " +
        f"0x${memLong(base + 1 * SECTOR)}%x")

      // ⛔ THE ASSERTION THE WHOLE SLICE EXISTS FOR. On an unsectored 64-byte line the
      // CINVL above dropped all four sectors and these three now read memory instead.
      // On a sectored line whose sector-miss refill bursts the full line, the load of
      // sector 1 just overwrote them and they also read memory. Either defect fails
      // here, with the value naming which sector was lost.
      for (k <- Seq(0, 2, 3)) {
        val got = load(dut, cd, base + k * SECTOR)
        assert(got == stored(k),
          f"SECTOR $k LOST ITS DIRTY DATA to a CINVL of sector 1: read 0x$got%x, " +
          f"want the stored 0x${stored(k)}%x (memory holds 0x${memLong(base + k * SECTOR)}%x). " +
          f"This is the 48-bytes-of-silent-data-loss failure that per-16-byte dirty " +
          f"sectors exist to prevent.")
      }

      // And the surviving dirty data must still reach MEMORY when asked, so the test
      // cannot pass on a cache that merely kept the bytes but lost the dirty bits.
      maint(dut, cd, push = true, invalidate = false, scope = 1, addr = base + 0 * SECTOR)
      maint(dut, cd, push = true, invalidate = false, scope = 1, addr = base + 2 * SECTOR)
      maint(dut, cd, push = true, invalidate = false, scope = 1, addr = base + 3 * SECTOR)
      for (k <- Seq(0, 2, 3)) {
        val got = memPeekLong(mem, base + k * SECTOR)
        assert(got == stored(k),
          f"CPUSHL of sector $k did not reach memory: 0x$got%x vs 0x${stored(k)}%x")
      }
      // Sector 1 in memory must be UNTOUCHED by all of that -- a CPUSHL of a sibling
      // must not over-push, which is the mirror of the CINVL hazard.
      val mem1 = memPeekLong(mem, base + 1 * SECTOR)
      assert(mem1 == memLong(base + 1 * SECTOR),
        f"a CPUSHL of sectors 0/2/3 wrote over sector 1: 0x$mem1%x")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────────
  // 2. CRITICAL-SECTOR SELECTION (the hard dependency slice D1.2 recorded).
  // ───────────────────────────────────────────────────────────────────────────────
  test("a cold line-miss load returns the DEMANDED sector's bytes, from every one of " +
       "the four beat positions", VerilatorTest) {
    sectoredDut.doSim("critical-sector-selection") { dut =>
      val (cd, mem) = initDut(dut)
      // Four DIFFERENT lines, one per sector position, so each is a genuinely cold line
      // miss and the demanded sector sits at a different beat index each time. 4 KB
      // apart keeps them in the same set, which is harmless here (4 ways) and makes the
      // addresses easy to read.
      for (k <- 0 until 4) {
        val base = 0x30000L + k * 0x1000L
        fillMem(mem, base, LINE)
        val demanded = base + k * SECTOR
        val got = load(dut, cd, demanded)
        assert(got == memLong(demanded),
          f"CRITICAL-SECTOR SELECTION IS BROKEN at beat $k: a load of 0x$demanded%x " +
          f"returned 0x$got%x, want 0x${memLong(demanded)}%x. The `len = 3` burst's " +
          f"`r.last` beat carries sector 3 (0x${memLong(base + 3 * SECTOR)}%x); getting " +
          f"THAT value here means `missLine` is still latched from `r.last` instead of " +
          f"from the beat whose index equals the demanded sector.")
      }
    }
  }

  // ───────────────────────────────────────────────────────────────────────────────
  // 3. THE BURST -- the bandwidth mechanism itself.
  // ───────────────────────────────────────────────────────────────────────────────
  test("one cold line miss installs all four sectors in ONE AXI transaction",
       VerilatorTest) {
    sectoredDut.doSim("burst-installs-whole-line") { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x44000L
      fillMem(mem, base, LINE)

      val arFirst = countAr(dut, cd) {
        assert(load(dut, cd, base) == memLong(base), "first sector read back wrong")
      }
      assert(arFirst == 1,
        s"a 64-byte line miss must be ONE AR (a `len = 3` burst); counted $arFirst. " +
        s"Four means the line is still being fetched a sector at a time and the whole " +
        s"transaction-count saving -- which IS the bandwidth lever -- is absent.")

      val arRest = countAr(dut, cd) {
        for (k <- 1 until 4) {
          val a = base + k * SECTOR
          assert(load(dut, cd, a) == memLong(a), f"sector $k read back wrong")
        }
      }
      assert(arRest == 0,
        s"the other three sectors must HIT after the burst; counted $arRest extra AR. " +
        s"A non-zero count means the beats did not install their own sector slots.")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────────
  // 3b. THE SPLIT ITSELF, at the mechanism level.
  // ───────────────────────────────────────────────────────────────────────────────
  test("a SECTOR miss issues ONE single-beat transaction, not a burst", VerilatorTest) {
    sectoredDut.doSim("sector-miss-is-single-beat") { dut =>
      val (cd, mem) = initDut(dut)
      val base = 0x62000L
      fillMem(mem, base, LINE)

      // Cold LINE miss: one AR, and it must carry len = 3.
      var lineLen = -1
      val w1 = fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean)
            lineLen = dut.dcache.logic.axi.ar.payload.len.toInt
        }
      }
      assert(load(dut, cd, base + 2 * SECTOR) == memLong(base + 2 * SECTOR))
      cd.waitSampling(4); w1.terminate()
      assert(lineLen == 3,
        s"a LINE miss must be a 4-beat burst (len = 3); saw len = $lineLen. len = 0 means " +
        s"`refillFullLine` never reached the AR and the whole bandwidth mechanism is off.")

      // Now make ONE sector of that resident line invalid, and re-touch it. That is a
      // SECTOR miss: it must be a single beat, because a 4-beat burst here would
      // overwrite the line's other three sectors -- the exact data-loss the split exists
      // to prevent, and what `CINVL discards ONE 16-byte sector...` catches behaviourally.
      maint(dut, cd, push = false, invalidate = true, scope = 1, addr = base + 1 * SECTOR)
      var secLen = -1
      var arCount = 0
      val w2 = fork {
        while (true) {
          cd.waitSampling()
          if (dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean) {
            secLen = dut.dcache.logic.axi.ar.payload.len.toInt; arCount += 1
          }
        }
      }
      assert(load(dut, cd, base + 1 * SECTOR) == memLong(base + 1 * SECTOR))
      cd.waitSampling(4); w2.terminate()
      assert(arCount == 1, s"a sector miss must be exactly ONE transaction; counted $arCount")
      assert(secLen == 0,
        s"a SECTOR miss must be a SINGLE BEAT (len = 0); saw len = $secLen. A burst here " +
        s"refetches the whole line into a way that already holds it, overwriting any " +
        s"DIRTY sibling sector -- reintroducing, through the fill path, exactly the " +
        s"silent write loss that sectoring exists to prevent.")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────────
  // 3c. SIZING THE OWNER-ACCEPTED COST: the scattered-access capacity regression.
  // ───────────────────────────────────────────────────────────────────────────────
  /** The amendment records this as a KNOWN, RATIFIED cost ("memory bandwidth is
    * valuable") and explicitly not a gate -- but also says an accepted cost of UNKNOWN
    * SIZE is harder to trade away later, and that the corpus cannot show it because no
    * scattered kernel has a footprint larger than L1. That coverage hole is real at the
    * CORE level, but it does not apply here: the D-cache DUT can be driven directly, so
    * the cost is measurable now instead of waiting for a new core kernel.
    *
    * ⚠ WHY THE PATTERN MUST BE SCATTERED AND NOT STRIDED. A uniform 64-byte stride
    * measures NOTHING: in the unsectored arm the set index is `paddr[10:4]`, so a
    * stride of 64 steps the set by 4 and touches only 32 of the 128 sets -- giving it
    * 32 x 4 x 16 B = 2 KB of usable capacity, which is EXACTLY what the sectored arm has
    * (32 sets x 4 ways x 16 useful bytes). The two arms tie, and the regression is
    * invisible. It only appears when the addresses spread across all sets, i.e. when
    * they are scattered. Getting this wrong would have produced a confident "sectoring
    * is free" from a probe that structurally could not see the cost.
    *
    * The prediction from the geometry is 4x: one tag now covers 64 bytes, so a workload
    * touching ONE sector per line gets 128 x 16 B = 2 KB of effective capacity instead
    * of 512 x 16 B = 8 KB. */
  private def scatterMissRate(compiled: spinal.core.sim.SimCompiled[Dut], tag: String): Double = {
    var rate = 0.0
    compiled.doSim(s"scatter-capacity-$tag") { d =>
      val (cd, mem) = initDut(d)
      // 256 distinct 64-byte lines, one 16-byte sector touched in each, scattered over a
      // 64 KB span. A FIXED shuffle, not a random one, so both arms see the identical
      // address sequence and the comparison is a matched pair.
      val span  = 0x10000
      val base  = 0x80000L
      // ⚠ THE SECTOR INDEX MUST BE INDEPENDENT OF THE LINE INDEX. The first version of
      // this probe used `(l % 4) * SECTOR`, which is a FUNCTION of `l` -- and in the
      // unsectored arm the set is `paddr[10:4] = 4*(l mod 32) + sec`, so a correlated
      // `sec` collapses the address set onto only 32 of the 128 sets, oversubscribing
      // them 2:1 and driving BOTH arms to a 100% pass-2 miss rate. The probe then
      // reported a +0.0 pp "regression" from two saturated arms. With `sec` drawn
      // independently the expression spans all 128 unsectored sets, while the sectored
      // arm's line index stays `paddr[10:6] = l mod 32` -- 32 sets x 4 ways = 128 line
      // slots for 256 distinct lines. THAT asymmetry is the thing being measured.
      val rng   = new scala.util.Random(12345)
      val lines = rng.shuffle((0 until span / LINE).toList).take(256)
      val addrs = lines.map(l => base + l.toLong * LINE + rng.nextInt(nSecTest) * SECTOR)
      for (a <- addrs) fillMem(mem, a, 4)
      // Pass 1 warms; pass 2 is measured.
      for (a <- addrs) assert(load(d, cd, a) == memLong(a), f"pass 1 wrong at 0x$a%x")
      var misses = 0
      for (a <- addrs) {
        val n = countAr(d, cd) { assert(load(d, cd, a) == memLong(a), f"pass 2 wrong at 0x$a%x") }
        if (n > 0) misses += 1
      }
      rate = 100.0 * misses / addrs.size
      println(f"[d3-burst-capacity] $tag: pass-2 miss rate ${rate}%.1f%% " +
              f"(${misses}/${addrs.size} of 256 scattered 16-byte sectors over a 64 KB span)")
    }
    rate
  }

  test("SIZED: the scattered-access capacity regression sectoring accepts", VerilatorTest) {
    val flat = scatterMissRate(flatDut, "unsectored")
    val sect = scatterMissRate(sectoredDut, "sectored")
    println(f"[d3-burst-capacity] REGRESSION: ${flat}%.1f%% -> ${sect}%.1f%% pass-2 miss " +
            f"rate, i.e. ${sect - flat}%+.1f pp on a scattered working set. Predicted from " +
            f"the geometry: the sectored arm has 128 x 16 B = 2 KB of effective capacity " +
            f"for a one-sector-per-line pattern against the unsectored arm's " +
            f"512 x 16 B = 8 KB, so up to 4x worse.")
    // Asserted as a DIRECTION with a floor, not a point value: the number is the
    // deliverable (an accepted cost, now sized), while the assert exists only so a
    // future change that silently removes the regression -- or silently makes it total
    // -- is noticed. A tight band here would be a maintenance burden with no customer.
    assert(sect >= flat,
      f"the sectored arm must not MISS LESS than the unsectored one on a scattered " +
      f"working set ($sect%% vs $flat%%); if it does, this probe is not measuring " +
      f"capacity and the number above must not be quoted.")
    // ⚠ THE SATURATION CONTROL, and it is the assert that matters. A probe whose
    // UNSECTORED arm also misses is above the capacity limit of BOTH arms and measures
    // nothing -- the first version of this test did exactly that and printed a
    // confident "+0.0 pp" from 100% against 100%. The unsectored arm has 512 sector
    // slots for 256 scattered sectors, so it MUST mostly hit; if it does not, the
    // address set is not spread over its 128 sets and no number here may be quoted.
    assert(flat < 50.0,
      f"SATURATED PROBE: the UNSECTORED arm misses $flat%% on pass 2, but 256 scattered " +
      f"16-byte sectors fit its 512 sector slots comfortably. Both arms are above their " +
      f"capacity limit, so the printed regression is meaningless. This is the failure " +
      f"the correlated-sector-index bug produced; check the set spread first.")
    assert(sect > flat + 10.0,
      f"the sectored arm's scattered miss rate ($sect%%) is not meaningfully worse than " +
      f"the unsectored arm's ($flat%%), but 256 distinct 64-byte lines cannot fit 128 " +
      f"line slots. Either the probe is not reaching the capacity limit or the tag array " +
      f"is not actually line-indexed.")
  }

  // ───────────────────────────────────────────────────────────────────────────────
  // 4. THE EVICTION WALK.
  // ───────────────────────────────────────────────────────────────────────────────
  test("evicting a line writes back exactly its DIRTY sectors and leaves the clean " +
       "ones untouched in memory", VerilatorTest) {
    sectoredDut.doSim("eviction-walk-pushes-only-dirty-sectors") { dut =>
      val (cd, mem) = initDut(dut)
      // 32 sets of 64 bytes => the set stride is 2 KB, and there are 4 ways. Five lines
      // 2 KB apart therefore map to ONE set and the fifth forces a replacement.
      val stride = 2048L
      val victim = 0x50000L
      for (i <- 0 until 5) fillMem(mem, victim + i * stride, LINE)

      // Bring the victim line in, then dirty sectors 0 and 2 ONLY. Non-adjacent on
      // purpose: a walk that stops at the first clean sector would still pass with
      // sectors 0 and 1 dirty.
      assert(load(dut, cd, victim) == memLong(victim), "victim line did not fill")
      val d0 = BigInt(0x11110000L)
      val d2 = BigInt(0x22220000L)
      store(dut, cd, victim + 0 * SECTOR, d0)
      store(dut, cd, victim + 2 * SECTOR, d2)
      // Nothing may have reached memory yet -- COPYBACK.
      assert(memPeekLong(mem, victim) == memLong(victim),
        "a COPYBACK store must not have reached memory before the eviction")

      // Four more lines in the same set evict it (round-robin over 4 ways).
      for (i <- 1 until 5) {
        val a = victim + i * stride
        assert(load(dut, cd, a) == memLong(a), f"filler line $i did not fill")
      }
      cd.waitSampling(400)

      val got0 = memPeekLong(mem, victim + 0 * SECTOR)
      val got2 = memPeekLong(mem, victim + 2 * SECTOR)
      assert(got0 == d0,
        f"the eviction walk did not push DIRTY sector 0: memory holds 0x$got0%x, want " +
        f"0x$d0%x. A walk that only pushes the DEMANDED sector loses every other one.")
      assert(got2 == d2,
        f"the eviction walk did not push DIRTY sector 2: memory holds 0x$got2%x, want " +
        f"0x$d2%x. Sector 2 is deliberately NOT adjacent to sector 0 -- a walk that " +
        f"stops at the first clean sector fails exactly here and nowhere else.")
      for (k <- Seq(1, 3)) {
        val a = victim + k * SECTOR
        val got = memPeekLong(mem, a)
        assert(got == memLong(a),
          f"the eviction over-pushed CLEAN sector $k: memory holds 0x$got%x, want the " +
          f"original 0x${memLong(a)}%x. Over-pushing clean data is harmless for a " +
          f"VALID sector but writes garbage for an invalid one, so the walk gates on " +
          f"dirty AND valid.")
      }
    }
  }
}
