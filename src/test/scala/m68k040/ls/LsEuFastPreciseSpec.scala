package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.{DtlbPlugin, MmuControlPlugin}
import m68k040.services.CacheControlService
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Test-only CacheControlService provider: a directly sim-pokeable `dcacheEnabled`
  * bit. The real hardware source is RobPlugin's CACR.DE (bit 31) — dragging in the
  * whole ROB just to flip one control bit for an LS-EU-only directed test isn't
  * worth it, so this stub plugin publishes the SAME service interface with a plain
  * RegInit(False) (matches the real architectural reset default: CACR resets to 0,
  * D-cache disabled) that the test can poke directly, mirroring MmuControlPlugin's
  * own sim-poke discipline. */
class CacheControlStubPlugin extends FiberPlugin with CacheControlService {
  var _dcacheEnabled: Bool = null
  override def dcacheEnabled: Bool = _dcacheEnabled
  val logic = during build new Area {
    // Sim-poked control register (held; the sim pokes it). Self-assign so it has a
    // driver (no UNASSIGNED REGISTER), mirroring DFaultingTranslationPlugin's
    // established pattern for this exact situation.
    val dcacheEnabled = RegInit(False); dcacheEnabled.simPublic(); dcacheEnabled := dcacheEnabled
    _dcacheEnabled = dcacheEnabled
  }
}

/** Task P2.2 directed tests: LsEuPlugin's fast-vs-precise store classification
  * (`fastStore := mmuEnable && cacheable(s2Cmode) && CACR.DE`) and the resulting
  * CONDITIONAL `captureCompletion` at the SQ-alloc point.
  *
  *  - MMU-off store: fastStore is False regardless of cache mode (DtlbPlugin's own
  *    MMU-disabled response is always WRITETHROUGH+ready, so cacheability alone
  *    would otherwise look "fast" — mmuEnable is the deciding factor here). The
  *    store must still allocate into the SQ (exactly as before) but must NOT drive
  *    completionPort that cycle — precise=True, ROB completion deferred to the SQ's
  *    at-head drain (not yet built; Task P2.4).
  *  - MMU-on, WRITETHROUGH-page (CM=00, the page-table default), DE=1: fastStore is
  *    True — the store allocates AND completes the same cycle as alloc, exactly like
  *    today (pre-Task-P2.1 behavior), precise=False.
  *
  * Real MmuControlPlugin + DtlbPlugin (not the identity stub) are needed to exercise
  * the MMU-on case, mirroring DtlbSpec's own DUT/table-build helpers. */
class LsEuFastPreciseSpec extends AnyFunSuite {
  class Dut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val param     = new ParamPlugin(M68kParams())
    val rfInt     = new RegFilePluginInt
    val rfNzvc    = new RegFilePluginNzvc
    val rfX       = new RegFilePluginX
    val ctrl      = new MmuControlPlugin
    val dtlb      = new DtlbPlugin
    val dcache    = new DcachePlugin
    val cacheCtrl = new CacheControlStubPlugin
    val eu        = new LsEuPlugin
    val src       = new LsEuSourcePlugin
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, ctrl, dtlb, dcache, cacheCtrl, eu, src)) }
  }

  def simConfig = M68kSim().withVerilator

  val ROOT = 0x10000L
  val PTRT = 0x11000L
  val PAGT = 0x12000L

  // Mirrors DtlbSpec's helpers (big-endian descriptor words; CM bits left 0 =>
  // WRITETHROUGH, the page-table default).
  def pokeWordLE(mem: BehavioralMemAgent, addr: Long, w: Long): Unit =
    for (i <- 0 until 4) mem.pokeByte(addr + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
  def rootIdx(va: Long): Int = ((va >> 25) & 0x7f).toInt
  def ptrIdx(va: Long): Int  = ((va >> 18) & 0x7f).toInt
  def pageIdx(va: Long): Int = ((va >> 12) & 0x3f).toInt

  def buildResidentWritethroughPage(mem: BehavioralMemAgent, va: Long, ppn: Long): Unit = {
    pokeWordLE(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWordLE(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    val pd = ((ppn << 12) & 0xfffff000L) | 0x1L   // resident, CM=00 (WRITETHROUGH), no write-protect
    pokeWordLE(mem, PAGT + pageIdx(va) * 4, pd)
  }

  def seed(dut: Dut, cd: ClockDomain, preg: Int, value: Long): Unit = {
    dut.src.logic.seedValid #= true; dut.src.logic.seedAddr #= preg; dut.src.logic.seedData #= BigInt(value & 0xffffffffL)
    cd.waitSampling()
    dut.src.logic.seedValid #= false
    cd.waitSampling(2)
  }

  def initDut(dut: Dut): (ClockDomain, BehavioralMemAgent, BehavioralMemAgent) = {
    val cd = dut.clockDomain; cd.forkStimulus(10)
    val mem   = new BehavioralMemAgent(dut.dcache.logic.axi, cd)
    val ptmem = new BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
    val s = dut.src.logic
    s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
    s.iStkPush #= false
    s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
    dut.ctrl.logic.mmuEnable #= false
    dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
    dut.cacheCtrl.logic.dcacheEnabled #= false
    cd.waitSampling(80) // PRF init sweep
    (cd, mem, ptmem)
  }

  def issueStore(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long, dataPreg: Int, size: SpinalEnumElement[Size.type], robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.STORE; s.iSize #= size
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true
    s.iPsrcB #= dataPreg; s.iPsrcBValid #= true
    s.iImm #= BigInt(disp & 0xffffffffL)
    s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= robId
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
  }

  /** Poll for the SQ alloc pulse. Returns (entryIdx, completedNextCycle) — the SQ
    * ring index the store was written to (`sq.tail` read the SAME cycle
    * `sq.io.alloc.valid` fires — a plain combinational Flow.valid, immediately
    * observable — before it increments) and whether completionPort fired for
    * `robId` the FOLLOWING cycle. `captureCompletion` writes `compValid` (a
    * RegInit(False) that drives completionPort) — a REGISTERED assignment, so a
    * capture decided the same FSM cycle `sq.io.alloc.valid` is asserted is only
    * OBSERVABLE on completionPort one clock edge later (pre-existing pipeline
    * latency, unrelated to Task P2.2 — see LsEuPlugin's own "compValid is a
    * single-cycle pulse" comment). */
  def waitAlloc(dut: Dut, cd: ClockDomain, robId: Int, maxCycles: Int = 200): (Int, Boolean) = {
    var idx = -1
    var completedNextCycle = false
    var n = 0
    while (idx < 0 && n < maxCycles) {
      if (dut.eu.logic.sq.io.alloc.valid.toBoolean) {
        idx = dut.eu.logic.sq.tail.toInt
        cd.waitSampling()
        completedNextCycle = dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId
      } else {
        cd.waitSampling()
      }
      n += 1
    }
    (idx, completedNextCycle)
  }

  test("MMU-off store allocates precise=True and withholds completion at alloc", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x1000L
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xDEADBEEFL)
      // MMU stays disabled (initDut default) — mmuEnable=False alone must force
      // fastStore=False regardless of cache mode (DtlbPlugin's MMU-off response is
      // always WRITETHROUGH+ready).
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 5)
      val (idx, completedNextCycle) = waitAlloc(dut, cd, robId = 5)
      assert(idx >= 0, "MMU-off store must still allocate into the SQ")
      assert(!completedNextCycle, "MMU-off (precise-path) store must NOT drive completionPort right after allocating")
      cd.waitSampling(2)
      assert(dut.eu.logic.sq.robIds(idx).toInt == 5, "sanity: allocated entry belongs to this store")
      assert(dut.eu.logic.sq.precises(idx).toBoolean, "MMU-off store must classify precise=True")
      // Known intermediate state (Task P2.2): no drain trigger exists yet, so this
      // precise-path store's ROB completion never arrives. Confirm it STAYS withheld
      // over a further bounded window (not just the alloc cycle) — the expected
      // "hangs, doesn't crash" regression shape, not a full hang (bounded here).
      var completedLater = false
      for (_ <- 0 until 40) {
        if (dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == 5) completedLater = true
        cd.waitSampling()
      }
      assert(!completedLater, "precise-path store completion must stay withheld (no drain trigger built yet — Task P2.4)")
    }
  }

  test("MMU-on WRITETHROUGH-page store with DE=1 allocates precise=False and completes at alloc, as before", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x2000L
      val vpn  = (base >> 12) & 0xfffff
      buildResidentWritethroughPage(ptmem, base, ppn = vpn)   // identity PPN=VPN
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      dut.cacheCtrl.logic.dcacheEnabled #= true
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xCAFEBABEL)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 6)
      val (idx, completedNextCycle) = waitAlloc(dut, cd, robId = 6)
      assert(idx >= 0, "store must allocate into the SQ")
      assert(completedNextCycle, "fast-path store must drive completionPort right after allocating (unchanged from before P2.2)")
      cd.waitSampling(2)
      assert(dut.eu.logic.sq.robIds(idx).toInt == 6, "sanity: allocated entry belongs to this store")
      assert(!dut.eu.logic.sq.precises(idx).toBoolean, "MMU-on WRITETHROUGH DE=1 store must classify precise=False")
    }
  }
}
