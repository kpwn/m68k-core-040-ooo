package m68k040.mmu

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.cache.{CacheMode, DcachePlugin, DcacheService}
import m68k040.core.ParamPlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.ls.{CacheControlStubPlugin, LsEuSourcePlugin, TbPreciseDrainWirePlugin}
import m68k040.services.DTranslationService
import m68k040.sim.AxiMemModel
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}

/** Wires the DTLB's deferred U/M queue to sim-drivable commit inputs, mirroring
  * `FullCoreSynth.scala`'s `dtlb.umAccessRobId := lsEu.xlateRobId` /
  * `dtlb.umCommitValid := rob.logic.retire0` block. Without this the queue never
  * marks an entry committed and the U/M descriptor write never drains at all. */
class WalkerCoherencyUmWirePlugin(eu: LsEuPlugin, dtlb: DtlbPlugin) extends FiberPlugin {
  val logic = during build new Area {
    dtlb.umAccessRobId := eu.xlateRobId
    val iCommitValid = in Bool ()
    val iCommitId    = in UInt (m68k040.Global.ROB_ID_W_DEFAULT bits)
    dtlb.umCommitValid := iCommitValid
    dtlb.umCommitId    := iCommitId
  }
}

/** Passive view of the D-side translation stream plus the walker's own port. */
class WalkerCoherencyTracePlugin extends FiberPlugin {
  val logic = during build new Area {
    val xlate  = host[DTranslationService]
    val dcache = host[DcacheService]
    val rspFire  = out Bool ();        rspFire  := xlate.rsp.fire
    val rspPpn   = out UInt (20 bits); rspPpn   := xlate.rsp.payload.ppn
    val rspToken = out UInt (8 bits);  rspToken := xlate.rsp.payload.token
    val rspFault = out Bool ();        rspFault := xlate.rsp.payload.fault
    val cmdFire  = out Bool ()
    cmdFire := dcache.loadCmd.valid && dcache.loadCmd.ready
    val cmdToken = out UInt (8 bits);  cmdToken := dcache.loadCmd.payload.token
    val cmdPaddr = out UInt (32 bits); cmdPaddr := dcache.loadCmd.payload.paddr
    val stFire   = out Bool ()
    stFire := dcache.store.valid && dcache.store.ready
    val stPaddr  = out UInt (32 bits); stPaddr := dcache.store.payload.paddr
    val rspValid = out Bool ();        rspValid := dcache.loadRsp.valid
    val rspData  = out Bits (32 bits); rspData  := dcache.loadRsp.payload.data
    val cmdCmode = out(CacheMode());   cmdCmode := dcache.loadCmd.payload.cacheMode
  }
}

/** THE acceptance test for routing table walks through the L1 D-cache.
  *
  * ==What is being tested, and why the obvious posture does not test it==
  *
  * The defect is a coherency gap between the table walker and the copyback L1 D-cache,
  * in BOTH directions:
  *
  *  - STALE READ. A page-table entry a supervisor has written with an ordinary `move.l`
  *    lands DIRTY in the D-cache and is invisible in backing memory until that line is
  *    evicted. A walker that reads physical memory behind the cache's back reads the
  *    OLD descriptor and installs a wrong translation.
  *  - LOST UPDATE. The walker's U/M descriptor writeback goes only to memory, while the
  *    D-cache still holds a dirty copy of that line without the update. The later
  *    eviction writes the whole line back and silently discards it. Under-setting M
  *    means a dirty page is later evicted as clean: silent data loss.
  *
  * The gap is armed structurally and is prevented today only by a GLOBAL default --
  * `CACR = 0` out of reset makes every data access INHIBITED, so the D-cache holds
  * nothing at all. That is a cache-disable that exists by reset accident, not a
  * protection, and it is exactly the bit any real supervisor turns on. This is also why
  * the previously-nominated acceptance test (`mmu_atc_write_hit_sets_modified`) can no
  * longer show the bug: it never writes CACR, so `DE = 0` makes the question moot in its
  * posture, and it passes either way.
  *
  * The other trap worth recording: the existing `ForceCacheableCopyback` posture is NOT
  * usable either. It covers the whole 4 GiB in TTRs, a TTR hit is resolved before the
  * TLB is even consulted, and therefore NO TABLE WALK EVER HAPPENS under it. A posture
  * that transparently maps everything cannot exhibit a walker bug.
  *
  * ==The posture that does work==
  *
  *  1. `CACR.DE = 1` (`dcacheEnabled`), so the D-cache is actually live.
  *  2. `DTT0` covers ONLY the 16 MiB block holding the page tables, with `CM = 01`
  *     (COPYBACK). That is what makes an ordinary supervisor store to a descriptor leave
  *     the line dirty in L1D and backing memory stale.
  *  3. The translated VA sits in a DIFFERENT 16 MiB block, covered by NO TTR, so it is
  *     genuinely table-walked.
  *  4. `TC.E = 1` (`mmuEnable`).
  *
  * Both tests below assert on a value that DIFFERS between the two implementations, and
  * both establish non-vacuity first: the read-side test proves backing memory still holds
  * the OLD descriptor (i.e. the store really is dirty-only in L1D) before it forces the
  * walk, and the write-side test proves the walk actually queued a U/M write. */
class WalkerDescriptorCoherencySpec extends AnyFunSuite {

  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param     = new ParamPlugin(M68kParams())
    val rfInt     = new RegFilePluginInt
    val rfNzvc    = new RegFilePluginNzvc
    val rfX       = new RegFilePluginX
    val ctrl      = new MmuControlPlugin
    val dtlb      = new DtlbPlugin()
    val dcache    = new DcachePlugin()
    val cacheCtrl = new CacheControlStubPlugin
    val eu        = new LsEuPlugin
    val src       = new LsEuSourcePlugin
    val wire      = new TbPreciseDrainWirePlugin(eu)
    val umWire    = new WalkerCoherencyUmWirePlugin(eu, dtlb)
    val trace     = new WalkerCoherencyTracePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      param, rfInt, rfNzvc, rfX, ctrl, dtlb, dcache, cacheCtrl, eu, src, wire, umWire, trace)) }
  }

  // ── Page-table layout. All three levels sit inside the 16 MiB block VA[31:24] = 0x00,
  // which is the block DTT0 will cover COPYBACK below.
  private val Root = 0x00010000L
  private val Ptrt = 0x00011000L
  private val Pagt = 0x00012000L

  // The translated VA. VA[31:24] = 0x40, so DTT0 (base 0x00 / mask 0x00) does NOT cover
  // it and it is genuinely table-walked.
  private val TestVa = 0x40000000L

  private val PpnOld = 0x00801L
  private val PpnNew = 0x00901L

  /** DTT0: base 0x00, mask 0x00 (exact match on VA[31:24]), E = 1, S = 11 (either
    * privilege), CM = 01 (COPYBACK). Same field layout as the `ForceCacheableCopyback`
    * posture's own TTR words. */
  private val Dtt0Copyback = 0x0000E020L

  private def rootIdx(va: Long): Int = ((va >>> 25) & 0x7f).toInt
  private def ptrIdx(va: Long): Int  = ((va >>> 18) & 0x7f).toInt
  private def pageIdx(va: Long): Int = ((va >>> 12) & 0x3f).toInt
  private def vpnOf(va: Long): Long  = (va >>> 12) & 0xfffffL

  /** Big-endian longword: the byte at the LOWEST address is the descriptor's MSB, which
    * is what a real architected `move.l` lays down. */
  private def pokeDesc(mem: AxiMemModel, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >>> (8 * (3 - i))) & 0xff).toInt)
  private def peekDesc(mem: AxiMemModel, addr: Long): Long = {
    var v = 0L
    for (i <- 0 until 4) v = (v << 8) | (mem.peekByte(addr + i).toLong & 0xff)
    v
  }

  private def leafAddr(va: Long): Long = Pagt + pageIdx(va) * 4
  /** Leaf page descriptor: PPN, CM = 00 (writethrough/cacheable), PDT = 01 resident,
    * U and M both CLEAR so a walk has real work to do. */
  private def leafDesc(ppn: Long): Long = ((ppn << 12) & 0xfffff000L) | 0x1L

  private def buildTable(mem: AxiMemModel, va: Long, ppn: Long): Unit = {
    pokeDesc(mem, Root + rootIdx(va) * 4, (Ptrt & 0xfffffff0L) | 0x3L)
    pokeDesc(mem, Ptrt + ptrIdx(va) * 4, (Pagt & 0xfffffff0L) | 0x3L)
    pokeDesc(mem, leafAddr(va), leafDesc(ppn))
  }

  private def initDut(dut: Dut): (ClockDomain, AxiMemModel) = {
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    val mem = AxiMemModel.attachFull(dut.dcache.logic.axi, cd)
    val s = dut.src.logic
    s.iValid #= false
    s.iMemOp #= MemOp.LOAD
    s.iSize #= Size.LONG
    s.iPsrcA #= 0; s.iPsrcAValid #= false
    s.iPsrcB #= 0; s.iPsrcBValid #= false
    s.iImm #= 0
    s.iPdst #= 0; s.iPdstValid #= false
    s.iRobId #= 0
    s.iStkPush #= false
    s.iLeaAddr #= false
    s.iSqCommitValid #= false; s.iSqCommitRob #= 0; s.iSqFlush #= false
    s.seedValid #= false; s.seedAddr #= 0; s.seedData #= 0
    s.obsIntAddr #= 0
    dut.umWire.logic.iCommitValid #= false
    dut.umWire.logic.iCommitId #= 0
    dut.ctrl.logic.mmuEnable #= false
    dut.ctrl.logic.pageSize8K #= false
    dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
    dut.ctrl.logic.dtt0 #= 0; dut.ctrl.logic.dtt1 #= 0
    dut.cacheCtrl.logic.dcacheEnabled #= false
    dut.wire.logic.iRobHeadIn #= 0; dut.wire.logic.iRobHeadValidIn #= false
    cd.waitSampling(80)                                   // PRF init sweep
    // The D-cache re-invalidates every set after reset (one set per cycle, 128 sets) and
    // refuses ALL admission for the whole walk. Issuing inside that window looks exactly
    // like an ordering bug at the assertion site.
    cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)
    (cd, mem)
  }

  /** Turn the MMU and the D-cache on, with DTT0 covering the page tables COPYBACK. */
  private def armPosture(dut: Dut, cd: ClockDomain): Unit = {
    dut.ctrl.logic.urp #= Root
    dut.ctrl.logic.srp #= Root
    dut.ctrl.logic.dtt0 #= Dtt0Copyback
    dut.ctrl.logic.mmuEnable #= true
    dut.cacheCtrl.logic.dcacheEnabled #= true
    cd.waitSampling(4)
  }

  private def seed(dut: Dut, cd: ClockDomain, preg: Int, value: Long): Unit = {
    val s = dut.src.logic
    s.seedValid #= true; s.seedAddr #= preg; s.seedData #= BigInt(value & 0xffffffffL)
    cd.waitSampling()
    s.seedValid #= false
    cd.waitSampling(2)
  }

  private def issueStore(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long,
                         dataPreg: Int, robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.STORE; s.iSize #= Size.LONG
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true
    s.iPsrcB #= dataPreg; s.iPsrcBValid #= true
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= robId
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
  }

  private def issueLoad(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long,
                        pdst: Int, robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true
    s.iPsrcB #= 0; s.iPsrcBValid #= false
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdstValid #= true; s.iPdst #= pdst; s.iRobId #= robId
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
  }

  private def commitSq(dut: Dut, cd: ClockDomain, robId: Int): Unit = {
    val s = dut.src.logic
    s.iSqCommitValid #= true; s.iSqCommitRob #= robId
    cd.waitSampling()
    s.iSqCommitValid #= false
    cd.waitSampling()
  }

  private def pulseUmCommit(dut: Dut, cd: ClockDomain, robId: Int): Unit = {
    dut.umWire.logic.iCommitValid #= true
    dut.umWire.logic.iCommitId #= robId
    cd.waitSampling()
    dut.umWire.logic.iCommitValid #= false
    cd.waitSampling()
  }

  /** Wait for the store to actually reach the SQ.
    *
    * This is load-bearing, not defensive. `sq.io.commit` is a ONE-CYCLE Flow that marks
    * an ALREADY-ALLOCATED entry committed; pulsing it before the store has allocated
    * loses the pulse outright, and -- worse -- `sq.io.empty` is then TRIVIALLY true, so a
    * naive "wait for the SQ to drain" returns instantly and the test proceeds with the
    * descriptor never written at all. That produces a test which fails on both
    * implementations for a reason that has nothing to do with the bug under test. */
  private def waitSqAlloc(dut: Dut, cd: ClockDomain, maxCycles: Int = 200): Boolean = {
    var seen = false
    var n = 0
    while (!seen && n < maxCycles) {
      sleep(1)
      seen = dut.eu.logic.sq.io.alloc.valid.toBoolean
      cd.waitSampling()
      n += 1
    }
    seen
  }

  private def waitSqEmpty(dut: Dut, cd: ClockDomain, maxCycles: Int = 400): Boolean = {
    var n = 0
    while (!dut.eu.logic.sq.io.empty.toBoolean && n < maxCycles) { cd.waitSampling(); n += 1 }
    dut.eu.logic.sq.io.empty.toBoolean
  }

  private def readPreg(dut: Dut, preg: Int): BigInt = {
    dut.src.logic.obsIntAddr #= preg
    sleep(1)
    dut.src.logic.obsIntData.toBigInt
  }

  /** Run for `cycles`, collecting every translation response and every table-walk
    * descriptor read (identified by its reserved D-cache load token). */
  private case class Observed(ppns: Vector[(Int, Long, Boolean)], walkReads: Vector[Long],
                              sqAlloc: Boolean)
  private def observe(dut: Dut, cd: ClockDomain, cycles: Int): Observed = {
    var ppns = Vector.empty[(Int, Long, Boolean)]
    var walkReads = Vector.empty[Long]
    var sqAlloc = false
    for (_ <- 0 until cycles) {
      sleep(1)
      val t = dut.trace.logic
      if (dut.eu.logic.sq.io.alloc.valid.toBoolean) sqAlloc = true
      if (t.rspFire.toBoolean)
        ppns :+= ((t.rspToken.toInt, t.rspPpn.toLong, t.rspFault.toBoolean))
      if (t.cmdFire.toBoolean) {
        val tok = t.cmdToken.toInt
        if (tok == m68k040.cache.DLoadToken.WALK_ITLB ||
            tok == m68k040.cache.DLoadToken.WALK_DTLB)
          walkReads :+= (t.cmdPaddr.toLong & 0xffffffffL)
      }
      cd.waitSampling()
    }
    Observed(ppns, walkReads, sqAlloc)
  }

  // ───────────────────────────────────────────────────────────────────────────────
  // READ SIDE: the walk must observe a descriptor that is still DIRTY in L1D.
  // ───────────────────────────────────────────────────────────────────────────────
  test("a table walk observes a page-table entry that is still dirty in L1D", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim("walkSeesDirtyPte") { dut =>
      val (cd, mem) = initDut(dut)
      buildTable(mem, TestVa, PpnOld)
      armPosture(dut, cd)

      val leaf = leafAddr(TestVa)
      assert(peekDesc(mem, leaf) == leafDesc(PpnOld), "sanity: memory holds the OLD descriptor")

      // ── 1. An ordinary supervisor-style store rewrites the leaf descriptor. DTT0
      // makes this COPYBACK, so it lands in the array and marks the line dirty; NO AXI
      // write is issued and backing memory is untouched. This is exactly a `move.l` to a
      // page-table entry with no CPUSH after it.
      seed(dut, cd, preg = 10, value = leaf)
      seed(dut, cd, preg = 11, value = leafDesc(PpnNew))
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, robId = 5)
      assert(waitSqAlloc(dut, cd), "the descriptor store never reached the store queue")
      cd.waitSampling(4)
      commitSq(dut, cd, robId = 5)
      assert(waitSqEmpty(dut, cd), "the descriptor store never drained out of the SQ")
      cd.waitSampling(60)

      // ── 2. NON-VACUITY, both halves. The store must be IN the array and NOT in memory;
      // either half failing means the posture is broken and the test proves nothing in
      // either direction.
      assert(peekDesc(mem, leaf) == leafDesc(PpnOld),
        f"posture broken: the COPYBACK store reached backing memory " +
        f"(0x${peekDesc(mem, leaf)}%08x); it was supposed to stay dirty in L1D only")
      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 25, robId = 9)
      cd.waitSampling(120)
      val arrayImage = readPreg(dut, 25).toLong & 0xffffffffL
      assert(arrayImage == leafDesc(PpnNew),
        f"posture broken: an ordinary load of 0x$leaf%08x read 0x$arrayImage%08x, so the " +
        f"COPYBACK store never landed in the array either")

      // ── 3. Force a real table walk of a VA no TTR covers.
      seed(dut, cd, preg = 12, value = TestVa)
      issueLoad(dut, cd, basePreg = 12, disp = 0, pdst = 20, robId = 6)
      val obs = observe(dut, cd, 400)

      assert(obs.walkReads.nonEmpty,
        "no table walk happened at all -- the posture is transparently mapping TestVa")
      assert(obs.walkReads.contains(leaf),
        f"the walk never read the leaf descriptor at 0x$leaf%08x: ${obs.walkReads.map(a => f"0x$a%08x")}")
      val resolved = obs.ppns.filter(!_._3).map(_._2)
      assert(resolved.nonEmpty, s"the translation never resolved: ${obs.ppns}")

      // ── 4. THE ASSERTION. Before the walker was routed through the D-cache it read
      // physical memory directly and got PpnOld.
      assert(resolved.forall(_ == PpnNew),
        f"the walk installed a STALE translation: got PPN(s) " +
        f"${resolved.map(p => f"0x$p%05x")}, expected 0x$PpnNew%05x. The descriptor " +
        f"rewritten at 0x$leaf%08x was still dirty in L1D and the walk read the " +
        f"pre-store image out of backing memory.")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────────
  // WRITE SIDE: the walk's own U/M descriptor update must be visible in L1D.
  // ───────────────────────────────────────────────────────────────────────────────
  test("a table walk's U/M descriptor update is visible to the D-cache", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim("walkUmVisibleInL1d") { dut =>
      val (cd, mem) = initDut(dut)
      buildTable(mem, TestVa, PpnOld)
      armPosture(dut, cd)
      val leaf = leafAddr(TestVa)

      // ── 1. Make the descriptor's line resident and DIRTY without changing its value:
      // load it, then store the same bytes back. Under COPYBACK that is a local RMW with
      // no bus traffic, so backing memory and the array agree -- for now.
      seed(dut, cd, preg = 10, value = leaf)
      seed(dut, cd, preg = 11, value = leafDesc(PpnOld))
      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 20, robId = 3)
      cd.waitSampling(60)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, robId = 4)
      assert(waitSqAlloc(dut, cd), "the descriptor priming store never reached the SQ")
      cd.waitSampling(4)
      commitSq(dut, cd, robId = 4)
      assert(waitSqEmpty(dut, cd), "the descriptor priming store never drained")
      cd.waitSampling(40)
      assert((readPreg(dut, 20).toLong & 0xffffffffL) == leafDesc(PpnOld),
        f"sanity: priming load read 0x${readPreg(dut, 20)}%08x, expected 0x${leafDesc(PpnOld)}%08x")

      // ── 2. A WRITE access to the walked page. The walk sets U and M and queues the
      // deferred descriptor write, which drains once its robId commits.
      seed(dut, cd, preg = 12, value = TestVa)
      seed(dut, cd, preg = 13, value = 0x5a5a5a5aL)
      issueStore(dut, cd, basePreg = 12, disp = 0, dataPreg = 13, robId = 7)
      val obs = observe(dut, cd, 300)
      assert(obs.sqAlloc, "the walked-page store never reached the SQ")
      assert(obs.walkReads.contains(leaf),
        f"no walk of the leaf descriptor at 0x$leaf%08x happened: " +
        f"${obs.walkReads.map(a => f"0x$a%08x")}")
      commitSq(dut, cd, robId = 7)
      pulseUmCommit(dut, cd, robId = 7)
      cd.waitSampling(200)

      // ── 3. THE ASSERTION. Read the descriptor back THROUGH THE D-CACHE. Before the
      // walker's U/M write was routed through the cache it went straight to memory,
      // leaving the dirty array copy without U/M -- and a later eviction of that line
      // would have written the stale byte back over the update (the lost-update
      // direction: an M that never sticks means a dirty page is evicted as clean).
      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 21, robId = 8)
      cd.waitSampling(120)
      val readBack = readPreg(dut, 21).toLong & 0xffffffffL
      assert((readBack & 0x18L) == 0x18L,
        f"the walk's U/M update is invisible to the D-cache: read back 0x$readBack%08x " +
        f"from 0x$leaf%08x, expected U (bit 3) and M (bit 4) both set. The array still " +
        f"holds the pre-walk image and its eviction would discard the update.")
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // THE DEFERRED-WRITE CLOBBER.
  //
  // UmWriteQueue.scala says so in its own words: "the drain is a whole-BYTE RMW
  // and that byte also carries PDT/W/CM/S. Software that rewrites a descriptor
  // between a walk's read and the drain has its change clobbered. No interlock in
  // this file ever addressed that."
  //
  // The captured byte is sampled when the walk READS the descriptor. It is written
  // back much later, when the triggering instruction's robId commits. A supervisor
  // that write-protects, re-types, or changes the cache mode of that page inside
  // that window has its store silently REVERTED -- not delayed, reverted -- because
  // the drain rewrites the whole low byte from the stale sample.
  //
  // A reverted page-table update is a nondeterministic memory-corruption engine:
  // the page keeps stale protection or a stale cache mode, and which access loses
  // depends purely on where the drain lands relative to the supervisor's store.
  // That matches the failure seen on hardware, where a 7.5.3 boot lands somewhere
  // different every time.
  //
  // This test makes the window explicit rather than racing for it: the drain is
  // gated on `pulseUmCommit`, so the supervisor's store is placed between the walk
  // and the drain deterministically.
  // ────────────────────────────────────────────────────────────────────────────
  test("a deferred U/M write must not revert a descriptor written in the meantime", VerilatorTest) {
    M68kSim().withVerilator.compile(new Dut).doSim("walkUmClobbersSwWrite") { dut =>
      val (cd, mem) = initDut(dut)
      buildTable(mem, TestVa, PpnOld)
      armPosture(dut, cd)
      val leaf     = leafAddr(TestVa)
      val descBase = leafDesc(PpnOld)          // PDT=01, U=0, M=0, W=0
      val WBit     = 0x4L                      // write-protect, bit 2 of the low byte

      seed(dut, cd, preg = 10, value = leaf)

      // ── 1. A WRITE to the walked page. The walk reads the leaf and SAMPLES its
      // low byte; the deferred U/M write is queued but cannot drain until its
      // robId is committed below.
      seed(dut, cd, preg = 12, value = TestVa)
      seed(dut, cd, preg = 13, value = 0x5a5a5a5aL)
      issueStore(dut, cd, basePreg = 12, disp = 0, dataPreg = 13, robId = 7)
      val obs = observe(dut, cd, 300)
      assert(obs.sqAlloc, "the walked-page store never reached the SQ")
      assert(obs.walkReads.contains(leaf),
        f"no walk of the leaf descriptor at 0x$leaf%08x happened, so nothing was " +
        f"sampled and this test would be vacuous: " +
        f"${obs.walkReads.map(a => f"0x$a%08x").mkString(", ")}")
      commitSq(dut, cd, robId = 7)

      // ── 2. SUPERVISOR WRITES THE DESCRIPTOR, inside the window. This is an
      // ordinary store to the page-table entry, exactly what an OS does when it
      // write-protects a page.
      seed(dut, cd, preg = 11, value = descBase | WBit)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, robId = 9)
      assert(waitSqAlloc(dut, cd), "the supervisor's descriptor store never reached the SQ")
      cd.waitSampling(4)
      commitSq(dut, cd, robId = 9)
      assert(waitSqEmpty(dut, cd), "the supervisor's descriptor store never drained")
      cd.waitSampling(40)

      // Non-vacuity: the supervisor's W really is in place before the drain runs.
      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 22, robId = 10)
      cd.waitSampling(120)
      val beforeDrain = readPreg(dut, 22).toLong & 0xffffffffL
      assert((beforeDrain & WBit) == WBit,
        f"setup invalid: the supervisor's write-protect never landed (read back " +
        f"0x$beforeDrain%08x from 0x$leaf%08x), so the clobber below would be untestable")

      // ── 3. NOW let the deferred U/M write drain.
      pulseUmCommit(dut, cd, robId = 7)
      cd.waitSampling(200)

      // ── 4. THE ASSERTION. The drain must merge U and M into whatever the
      // descriptor now holds, not rewrite the byte it sampled before the
      // supervisor's store.
      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 21, robId = 11)
      cd.waitSampling(120)
      val after = readPreg(dut, 21).toLong & 0xffffffffL
      assert((after & WBit) == WBit,
        f"REVERTED: the deferred U/M write clobbered the supervisor's descriptor " +
        f"store. Read back 0x$after%08x from 0x$leaf%08x; write-protect (bit 2) was " +
        f"set before the drain and is clear after it. The drain rewrote the whole " +
        f"low byte from the value sampled at walk time, discarding everything " +
        f"software changed in between.")
      assert((after & 0x18L) == 0x18L,
        f"the drain lost its own update: read back 0x$after%08x, expected U (bit 3) " +
        f"and M (bit 4) both set")
    }
  }
}
