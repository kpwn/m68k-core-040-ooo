package m68k040.fuzz

/** The third ported-corpus posture: **D-cache ON *and* a real 3-level table walk**.
  *
  * ==Why this exists (the structural blind spot it closes)==
  *
  * Until this file, NOTHING in the regression suite ran the 910-program directed corpus
  * with caches enabled AND genuine page-table translation at the same time, which is
  * precisely what the board runs:
  *
  *  - `CachePosture.AsWritten` leaves `CACR = 0` and `TC.E = 0`.  Every data access is
  *    INHIBITED, so a cache-line-crossing defect is structurally invisible, and no TLB
  *    is ever consulted so no walk ever happens.
  *  - `CachePosture.ForceCacheableCopyback` turns the D-cache on, but does it by
  *    blanketing the low 2 GiB with `ITT0`/`DTT0` and the high 2 GiB with `DTT1`.
  *    A transparent-translation hit resolves BEFORE the TLB, so — again — NO TABLE WALK
  *    EVER HAPPENS under it (recorded verbatim in
  *    `docs/superpowers/specs/2026-09-04-walker-dcache-passthrough-implementation-report.md`
  *    §1 trap 2, and re-derived independently in `WalkerDescriptorCoherencySpec`'s header).
  *  - The LSU/lock-step cluster specs pin `mmuEnable = false` outright.
  *
  * `ForceMmuWalkCopyback` sets **all four TTRs to zero** — there is no transparent
  * translation at all — installs harness-built identity page tables, and turns on
  * `TC.E` + `CACR.DE|IE`.  Every instruction fetch and every data access to a cold TLB
  * entry therefore runs a real root -> pointer -> page walk through the D-cache, and
  * every leaf descriptor is planted with `U = M = 0` so each first touch of a page also
  * issues the walker's deferred U/M writeback STORE.  That is the exact traffic mix the
  * board ROM generates and that `2db5bd3` ("route MMU table walks through L1D") changed.
  *
  * ==Identity mapping, and why the map is MEASURED rather than guessed==
  *
  * A blanket identity map of all 4 GiB is not buildable (1 Mi leaf descriptors per test).
  * Instead the posture is *self-calibrating*: `PortedM68kOooSpec` first runs the very same
  * program under `AsWritten` with a `PostureProbe` attached, which records every 256 KiB
  * block that the I-side and D-side AXI ports actually touched.  The page table is then
  * built to cover exactly those blocks (plus the low 2 MiB, the program span and the
  * sentinel).  If the MMU pass diverges and walks for a block the map lacks, the walker's
  * own descriptor-read addresses identify it (see `PostureProbe.holeBlocks`) and the
  * caller re-builds and re-runs.  Nothing about the working set is hard-coded.
  *
  * ==Descriptor format==
  *
  * Long-format, per `MmuDesc`:
  *  - root / pointer descriptor: `nextTableBase | 0x02` (UDT = 10, resident; `tblNextBase`
  *    strips the low 4 bits so the type field never disturbs the address).
  *  - page (leaf) descriptor: `pa | (CM << 5) | 0x01` (PDT = 01 resident, W = 0, U = 0,
  *    M = 0, S = 0).  `CM = 01` is COPYBACK, `CM = 10` is inhibited/serialized.
  *
  * Descriptors are written BIG-ENDIAN into the D-side backing memory, because that is
  * what a real `move.l` from supervisor code would leave there and what the walker's
  * `DcacheByteLane.extract` reads back (task #194).
  */
object MmuWalkPosture {
  /** One leaf table always spans 256 KiB, whatever the page size (64 x 4 KiB == 32 x
    * 8 KiB), so 256 KiB is the natural granule for "a region the map covers". */
  val BlockShift: Int = 18
  val BlockBytes: Long = 1L << BlockShift

  val CmWritethrough = 0
  val CmCopyback     = 1
  val CmInhibitedSer = 2

  /** Table-descriptor: resident (UDT = 10). */
  def tableDesc(nextBase: Long): Long = (nextBase & 0xfffffff0L) | 0x02L
  /** Leaf page descriptor: resident (PDT = 01), given cache mode, W = S = 0.
    *
    * `u`/`m` default to CLEAR, which is deliberate: a walk over a `U = 0` descriptor also
    * queues the deferred U/M writeback STORE, so the default posture exercises the
    * walker's descriptor-WRITE path as well as its read path. Presetting them (see
    * `build`'s `presetUM`) removes that store and is the discriminator between the two. */
  def pageDesc(pa: Long, cm: Int, u: Boolean = false, m: Boolean = false): Long =
    (pa & 0xfffff000L) | ((cm.toLong & 3L) << 5) |
      (if (m) 0x10L else 0L) | (if (u) 0x08L else 0L) | 0x01L

  def blockOf(addr: Long): Long = (addr & 0xffffffffL) >>> BlockShift
  def blockBase(block: Long): Long = block << BlockShift

  /** A built identity map: the literal (physical address, longword) descriptor writes to
    * plant in backing memory, plus everything needed to (a) prove the posture engaged and
    * (b) decode a walker descriptor read back into the VA region it was asking about. */
  final case class Map(rootBase: Long,
                       pages8K: Boolean,
                       descriptors: Vector[(Long, Long)],
                       blocks: Vector[Long],
                       ptrTableBase: scala.collection.immutable.Map[Long, Long],
                       leafTableBase: scala.collection.immutable.Map[Long, Long],
                       arenaBase: Long,
                       arenaBytes: Long) {
    val ptrTableOwner: scala.collection.immutable.Map[Long, Long] =
      ptrTableBase.map { case (region, base) => base -> region }
    val leafTableOwner: scala.collection.immutable.Map[Long, Long] =
      leafTableBase.map { case (block, base) => base -> block }
    def blockCount: Int = blocks.size
    def covers(addr: Long): Boolean = leafTableBase.contains(blockOf(addr))
    def arenaBlocks: Set[Long] =
      (blockOf(arenaBase) to blockOf(arenaBase + arenaBytes - 1)).toSet

    /** Decode a walker descriptor-read physical address back into its level and index.
      * Returns `(level, region, blockOrMinus1)` where level is 0 = root, 1 = pointer,
      * 2 = leaf, or `None` if the address is not one of ours. */
    def decodeDescRead(pa: Long): Option[(Int, Long, Long)] = {
      val a = pa & 0xffffffffL
      if (a >= rootBase && a < rootBase + RootTableBytes) {
        Some((0, (a - rootBase) / 4L, -1L))
      } else {
        val ptrHit = ptrTableOwner.collectFirst {
          case (base, region) if a >= base && a < base + PtrTableBytes =>
            (1, region, region * 128L + ((a - base) / 4L))
        }
        ptrHit.orElse(leafTableOwner.collectFirst {
          case (base, block) if a >= base && a < base + LeafTableBytesMax => (2, block >>> 7, block)
        })
      }
    }
  }

  val RootTableBytes: Long = 128L * 4L   // root index = VA[31:25], 7 bits
  val PtrTableBytes: Long  = 128L * 4L   // pointer index = VA[24:18], 7 bits
  val LeafTableBytesMax: Long = 64L * 4L // 64 entries at 4 KiB, 32 at 8 KiB
  /** Every table is placed on a 512-byte boundary; the walker only strips the low 4
    * address bits, so this is comfortably stricter than the hardware needs. */
  val TableStride: Long = 512L

  /** Cap so a runaway probe (e.g. a wrong-path fetch storm that dragged in a whole
    * 32 MiB region) can never silently allocate an enormous table. */
  val MaxBlocks: Int = 768   // 192 MiB of identity mapping, ~400 KiB of descriptors

  /** Build the identity map.
    *
    * @param blocks     256 KiB block indices (`va >>> 18`) to identity-map.
    * @param inhibited  predicate on the block index: true => leaf CM = inhibited.
    *                   The sentinel block MUST be inhibited or the harness (which polls
    *                   the AXI backing store) can never observe the PASS word.
    * @param tableBase  physical base of the descriptor arena. Must be 512-aligned and
    *                   must lie in a region the sim AXI model decodes.
    */
  def build(blocks: Set[Long], inhibited: Long => Boolean, tableBase: Long,
            pages8K: Boolean, presetUM: Boolean = false): Map = {
    require((tableBase & (TableStride - 1)) == 0, f"tableBase 0x$tableBase%08x not 512-aligned")
    require(blocks.nonEmpty, "MmuWalkPosture.build: empty block set")
    require(blocks.size <= MaxBlocks,
      s"MmuWalkPosture.build: ${blocks.size} blocks exceeds MaxBlocks=$MaxBlocks -- " +
        "the probe pass touched an implausibly large working set")

    val sorted  = blocks.toVector.sorted
    val regions = sorted.map(_ >>> 7).distinct.sorted   // 32 MiB regions (root index)

    var next = tableBase
    def alloc(): Long = { val a = next; next += TableStride; a }

    val rootBase = alloc()
    val ptrBase  = regions.map(r => r -> alloc()).toMap
    val leafBase = sorted.map(b => b -> alloc()).toMap

    val leafEntries = if (pages8K) 32 else 64
    val pageBytes   = if (pages8K) 8192L else 4096L

    val descs = Vector.newBuilder[(Long, Long)]
    // Root level. Only the touched regions are resident; every other root entry is left
    // as an all-zero INVALID descriptor, which is what makes a stray access fault instead
    // of silently succeeding.
    for (r <- regions) descs += (rootBase + r * 4L) -> tableDesc(ptrBase(r))
    // Pointer level.
    for (b <- sorted) descs += (ptrBase(b >>> 7) + (b & 0x7fL) * 4L) -> tableDesc(leafBase(b))
    // Leaf level: identity PA == VA.
    for (b <- sorted) {
      val cm = if (inhibited(b)) CmInhibitedSer else CmCopyback
      val base = blockBase(b)
      for (i <- 0 until leafEntries)
        descs += (leafBase(b) + i * 4L) ->
          pageDesc(base + i * pageBytes, cm, u = presetUM, m = presetUM)
    }

    Map(rootBase = rootBase, pages8K = pages8K, descriptors = descs.result(),
        blocks = sorted, ptrTableBase = ptrBase, leafTableBase = leafBase,
        arenaBase = tableBase, arenaBytes = next - tableBase)
  }

  /** Pick a descriptor-arena base that the program under test demonstrably does NOT
    * touch (measured, not assumed) and that the sim AXI model decodes.
    *
    * `AxiMemModel.decoded` accepts top nibbles 0/4/5/6 plus 0xFFFFxxxx; the candidates
    * below sit in the 0x5.../0x6... halves that the corpus does not use for anything,
    * with a low-memory fallback. A candidate is rejected if ANY of the blocks it would
    * occupy were touched. */
  val ArenaCandidates: Vector[Long] =
    Vector(0x06000000L, 0x06400000L, 0x05C00000L, 0x05800000L, 0x00C00000L, 0x00E00000L)

  def chooseArena(touched: Set[Long], neededBytes: Long): Long = {
    val nBlocks = ((neededBytes + BlockBytes - 1) / BlockBytes).max(1L)
    ArenaCandidates.find { c =>
      val first = blockOf(c)
      (0L until nBlocks).forall(i => !touched.contains(first + i))
    }.getOrElse(throw new AssertionError(
      s"no free descriptor arena: every candidate collides with the measured working set " +
        s"(${touched.toVector.sorted.map(b => f"0x${blockBase(b)}%08x").mkString(",")})"))
  }

  /** Worst-case arena size for `n` blocks spanning `r` regions (used before the map is
    * built, to size the collision check). */
  def arenaBytesFor(nBlocks: Int, nRegions: Int): Long =
    TableStride * (1L + nRegions.toLong + nBlocks.toLong)
}

/** Per-run observation of whether a posture is ACTUALLY engaged.
  *
  * This is the single most important part of the suite: a "cached + MMU" posture that
  * silently decays into an MMU-off run, or into a run where a TTR short-circuits every
  * walk, is a vacuous pass. Every counter here is a positive measurement, and
  * `PortedM68kOooSpec` asserts on them rather than trusting the setup code. */
final class PostureProbe {
  /** 256 KiB blocks seen on the I-side and D-side AXI ports. */
  val touchedBlocks = scala.collection.mutable.Set.empty[Long]
  /** 256 KiB blocks a walk demanded that the installed map did not contain (measured
    * from the walker's own descriptor-read addresses, see `MmuWalkPosture.Map.decodeDescRead`). */
  val holeBlocks = scala.collection.mutable.Set.empty[Long]
  /** 32 MiB regions whose ROOT descriptor was missing (a walk that never got as far as
    * naming a block). */
  val holeRegions = scala.collection.mutable.Set.empty[Long]

  var itlbHoles = 0L        // holes attributed to the INSTRUCTION side
  var dtlbHoles = 0L        // ...and to the DATA side
  /** Walks still in flight when the program hit its sentinel. These are NOT holes: the
    * walk simply had not issued its next descriptor read yet, and counting them would
    * fabricate a map gap out of nothing (it did, on the first run of this suite). */
  var inFlightWalksAtEnd = 0L

  var itlbWalkReads = 0L    // walker descriptor READS (root/pointer/leaf) on the I side
  var dtlbWalkReads = 0L
  var itlbWalkStarts = 0L   // ROOT-level reads == number of walks begun
  var dtlbWalkStarts = 0L
  var walkStores = 0L       // deferred U/M descriptor writebacks that actually fired
  var itlbDescFaults = 0L   // descriptor read came back with a bus fault
  var dtlbDescFaults = 0L
  var dtlbXlateFaults = 0L  // DTLB response with fault set (non-resident/WP/supervisor)

  var dcLoadHits = 0L       // D-cache load-pipe S2 HIT  (zero when every access is inhibited)
  var dcStoreHits = 0L      // D-cache store-pipe S3 HIT
  var dcLoadCmds = 0L
  var dAxiAr = 0L
  var dAxiAw = 0L
  var iAxiAr = 0L

  var retiredUops = 0L
  var cycles = 0L

  def summary: String =
    f"walks(I=$itlbWalkStarts/$itlbWalkReads D=$dtlbWalkStarts/$dtlbWalkReads) " +
      f"umStores=$walkStores dcHits(ld=$dcLoadHits st=$dcStoreHits) " +
      f"dcLoadCmds=$dcLoadCmds axi(dAr=$dAxiAr dAw=$dAxiAw iAr=$iAxiAr) " +
      f"descFaults(I=$itlbDescFaults D=$dtlbDescFaults) xlateFaults=$dtlbXlateFaults " +
      f"blocks=${touchedBlocks.size} holes=${holeBlocks.size}/${holeRegions.size}" +
      f"(I=$itlbHoles D=$dtlbHoles) inFlightAtEnd=$inFlightWalksAtEnd cyc=$cycles"

  def holeSummary: String = {
    val b = holeBlocks.toVector.sorted.map(x => f"0x${MmuWalkPosture.blockBase(x)}%08x")
    val r = holeRegions.toVector.sorted.map(x => f"0x${x << 25}%08x")
    s"blocks=[${b.mkString(",")}] regions=[${r.mkString(",")}]"
  }
}

/** Result of a full `ForceMmuWalkCopyback` corpus run: the MMU-pass outcome plus BOTH
  * probes, so a caller can assert on the posture as well as on the program. */
final case class MmuWalkRun(outcome: PortedOutcome,
                            probe: PostureProbe,
                            baseline: PostureProbe,
                            baselineOutcome: PortedOutcome,
                            map: MmuWalkPosture.Map,
                            attempts: Int,
                            extensions: Vector[String])

object MmuWalkDriver {
  import MmuWalkPosture._

  /** Blocks that are mapped unconditionally regardless of what the probe measured:
    *  - 0x00000000..0x001FFFFF: the vector table, low RAM, and the supervisor stack
    *    (the harness seeds ISP = 0x00100000 and the first push goes BELOW it, into the
    *    previous block -- an easy hole to miss),
    *  - the whole program span,
    *  - the completion sentinel block. */
  def seedBlocks(imageBytes: Int): Set[Long] = {
    val low = (0L until 8L).toSet                                   // low 2 MiB
    val code = (blockOf(PortedTestRunner.loadAddr) to
                blockOf(PortedTestRunner.loadAddr + math.max(imageBytes - 1, 0))).toSet
    low ++ code + blockOf(PortedTestRunner.SentinelAddr)
  }

  /** Which blocks get a CACHE-INHIBITED leaf descriptor.
    *
    *  - The sentinel block MUST be inhibited: the harness detects completion by polling
    *    the AXI backing store, and a copyback mapping could legally hold the PASS word in
    *    a dirty L1D line forever. (`ForceCacheableCopyback` solves the same problem with
    *    DTT1; here it is a leaf-descriptor cache mode, so the walk still happens.)
    *  - Any block the sim AXI model does NOT decode must be inhibited too. Several corpus
    *    programs deliberately access unbacked space to raise a bus error; mapping such a
    *    page COPYBACK would turn that into a cacheable line fill that errors, which trips
    *    `DcachePlugin`'s "unexpected async diagnostic fault (a trusted-cacheable-path AXI
    *    transaction errored)" assertion -- a harness artifact, not a core defect. Marking
    *    unbacked space cache-inhibited is also what a real SoC's page tables do, and is
    *    the same effect `ForceCacheableCopyback` gets from its inhibited DTT1. */
  def inhibitedBlock(b: Long): Boolean =
    b == blockOf(PortedTestRunner.SentinelAddr) ||
      !m68k040.sim.AxiMemModel.decoded(blockBase(b))

  def buildFor(blocks: Set[Long], pages8K: Boolean,
               presetUM: Boolean = false): MmuWalkPosture.Map = {
    val nRegions = blocks.map(_ >>> 7).size
    val need = arenaBytesFor(blocks.size, nRegions)
    val arena = chooseArena(blocks, need)
    build(blocks, inhibitedBlock, arena, pages8K, presetUM)
  }

  /** Run one corpus program under REAL page tables.
    *
    * Pass 0 measures the working set under the corpus's own `AsWritten` posture (every
    * access inhibited, so every access is visible on AXI). Pass 1+ installs a page table
    * built to cover exactly that measured set and runs for real. If the MMU pass diverges
    * and walks for a region the map lacks, the walker's own descriptor-read addresses name
    * it and the map is extended and re-run -- so the covered set is DERIVED, never
    * hard-coded. A run that already passes is never retried.
    *
    * @param maxAttempts total MMU passes, including the first. */
  def runWithRealTables(name: String, src: String, timeoutCycles: Long,
                        pages8K: Boolean = false, simSeed: Int = 1,
                        maxAttempts: Int = 3, presetUM: Boolean = false): MmuWalkRun = {
    val imageBytes = m68k040.oracle.ProgramAssembler
      .assemble(src, PortedTestRunner.loadAddr).map(_.bytes.length).getOrElse(0)

    val baseline = new PostureProbe
    val baseOutcome = PortedTestRunner.run(
      name, src, timeoutCycles, simSeed, CachePosture.AsWritten, baseline)

    var blocks = baseline.touchedBlocks.toSet ++ seedBlocks(imageBytes)
    var attempt = 0
    var last: MmuWalkRun = null
    var extensions = Vector.empty[String]
    var keepGoing = true
    while (keepGoing) {
      attempt += 1
      val map = buildFor(blocks, pages8K, presetUM)
      val probe = new PostureProbe
      val outcome = PortedTestRunner.run(
        name, src, timeoutCycles, simSeed, CachePosture.ForceMmuWalkCopyback(map), probe)
      last = MmuWalkRun(outcome, probe, baseline, baseOutcome, map, attempt, extensions)
      val newBlocks =
        probe.holeBlocks.toSet ++
          probe.holeRegions.toSet.flatMap((r: Long) => (r * 128L until r * 128L + 128L).toSet)
      val grown = (blocks ++ newBlocks) -- blocks
      if (outcome == PortedPass || grown.isEmpty || attempt >= maxAttempts ||
          (blocks.size + grown.size) > MaxBlocks) {
        keepGoing = false
      } else {
        extensions = extensions :+
          s"attempt $attempt: extended map by ${grown.size} block(s) -- ${probe.holeSummary}"
        blocks = blocks ++ grown
      }
    }
    last.copy(extensions = extensions)
  }
}
