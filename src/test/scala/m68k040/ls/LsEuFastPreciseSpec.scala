package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.{DtlbPlugin, MmuControlPlugin}
import m68k040.sim.{AxiMemModel, AxiMemModelConfig, L2LatencyModel}
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

/** Task P2.5 post-review fix: a minimal glue plugin mirroring FullCoreSynth's
  * `BackendWiringPlugin` pattern (see FullCoreSynth.scala's `lsEu.robHeadIn :=
  * rob.logic.h0` block). `LsEuPlugin.robHeadIn`/`robHeadValidIn`/`sqCompletionPort`
  * are bare plugin-level pass-through fields (populated during `setup`, driven/read
  * in `logic`'s `during build`), not proper `in()`/`out()` IO nested in an Area. In
  * a standalone LS-only DUT (no RobPlugin providing a real HDL-level override),
  * `robHeadIn`'s only driver is its own idle default (`robHeadIn := U(0,6 bits)`) --
  * Verilog generation constant-folds it away entirely, so a raw sim poke on it
  * (even simPublic-tagged) cannot work. Likewise `sqCompletionPort`, having no
  * consumer at all in a standalone DUT, gets pruned despite simPublic(). This
  * plugin gives both a genuine IO-backed detour: real `in()` ports drive
  * `robHeadIn`/`robHeadValidIn` (a real HDL-level override, exactly like
  * FullCoreSynth's own wiring), and real `out()` ports mirror `sqCompletionPort`
  * for observation -- both are then real, freely sim-pokeable/readable signals. */
class TbPreciseDrainWirePlugin(eu: LsEuPlugin) extends FiberPlugin {
  val logic = during build new Area {
    val iRobHeadIn      = in UInt (6 bits)
    val iRobHeadValidIn = in Bool ()
    eu.robHeadIn      := iRobHeadIn
    eu.robHeadValidIn := iRobHeadValidIn

    val oSqCompValid   = out Bool ()
    val oSqCompPayload = out UInt (6 bits)
    oSqCompValid   := eu.sqCompletionPort.valid
    oSqCompPayload := eu.sqCompletionPort.payload
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
    val wire      = new TbPreciseDrainWirePlugin(eu)
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, ctrl, dtlb, dcache, cacheCtrl, eu, src, wire)) }
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

  // Task P5.6: a COPYBACK page (CM=01, page-descriptor bit5 set per
  // IcacheTypes.CacheMode.decode's bits[6:5] encoding — mirrors
  // buildResidentWritethroughPage exactly, differing only in the CM bits) used to
  // prove DE=0 overrides the page's OWN attribute down to INHIBITED.
  def buildResidentCopybackPage(mem: BehavioralMemAgent, va: Long, ppn: Long): Unit = {
    pokeWordLE(mem, ROOT + rootIdx(va) * 4, (PTRT & 0xfffffff0L) | 0x3L)
    pokeWordLE(mem, PTRT + ptrIdx(va) * 4, (PAGT & 0xfffffff0L) | 0x3L)
    val pd = ((ppn << 12) & 0xfffff000L) | 0x20L | 0x1L   // resident, CM=01 (COPYBACK), no write-protect
    pokeWordLE(mem, PAGT + pageIdx(va) * 4, pd)
  }

  def seed(dut: Dut, cd: ClockDomain, preg: Int, value: Long): Unit = {
    dut.src.logic.seedValid #= true; dut.src.logic.seedAddr #= preg; dut.src.logic.seedData #= BigInt(value & 0xffffffffL)
    cd.waitSampling()
    dut.src.logic.seedValid #= false
    cd.waitSampling(2)
  }

  def initDut(dut: Dut, dataMemCfg: AxiMemModelConfig = AxiMemModelConfig()):
      (ClockDomain, AxiMemModel, BehavioralMemAgent) = {
    val cd = dut.clockDomain; cd.forkStimulus(10)
    val mem   = AxiMemModel.attachFull(dut.dcache.logic.axi, cd, dataMemCfg)
    val ptmem = new BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
    val s = dut.src.logic
    s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
    s.iStkPush #= false; s.iLeaAddr #= false
    s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
    dut.ctrl.logic.mmuEnable #= false
    dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
    dut.cacheCtrl.logic.dcacheEnabled #= false
    dut.wire.logic.iRobHeadIn #= 0; dut.wire.logic.iRobHeadValidIn #= false
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

  def issueLoad(dut: Dut, cd: ClockDomain, basePreg: Int, disp: Long,
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

  def waitCompletion(dut: Dut, cd: ClockDomain, robId: Int, maxCycles: Int = 200): Boolean = {
    var seen = false
    var n = 0
    while (!seen && n < maxCycles) {
      seen = dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId
      if (!seen) cd.waitSampling()
      n += 1
    }
    seen
  }

  /** LEA address-generate: completes deterministically (NO translate, NO cache/AXI
    * access at all -- see LsEuPlugin's `IDLE.whenIsActive` `when(u1.leaAddr)` arm) a
    * FIXED 2 cycles after issue is accepted (capture at accept+1, ready-for-next-issue
    * at accept+3). Used as the "live op" in the `liveCompletionFires` collision test
    * below: its captureCompletion timing has zero dependency on the randomized AXI
    * write-ready timing that governs a precise store's real background drain, so a
    * continuous back-to-back LEA train gives a fully deterministic (for a fixed sim
    * seed), period-3 stream of `liveCompletionFires` pulses to collide against. */
  def issueLea(dut: Dut, cd: ClockDomain, basePreg: Int, robId: Int): Unit = {
    val s = dut.src.logic
    s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
    s.iPsrcA #= basePreg; s.iPsrcAValid #= true
    s.iPsrcB #= 0; s.iPsrcBValid #= false
    s.iImm #= 0
    s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= robId
    s.iLeaAddr #= true
    cd.waitSamplingWhere(s.iReady.toBoolean)
    s.iValid #= false
    s.iLeaAddr #= false
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

  test("VIPT slice B: real DTLB lookup and virtual-set read launch together; an " +
       "SQ-forwarded load cancels its unused cache probe", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x2800L
      val vpn  = (base >> 12) & 0xfffff
      buildResidentWritethroughPage(ptmem, base, ppn = vpn)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      dut.cacheCtrl.logic.dcacheEnabled #= true
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xA1B2C3D4L)

      // Leave an older fast store resident and uncommitted so the younger load must
      // complete from the SQ, not from the speculative cache probe.
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11,
                 Size.LONG, robId = 6)
      val (idx, completed) = waitAlloc(dut, cd, robId = 6)
      assert(idx >= 0 && completed, "mapped cacheable store must allocate/complete fast")
      cd.waitSampling(2)

      var tracking = true
      var parallelSeen = false
      var cancelSeen = false
      fork {
        while (tracking) {
          cd.waitSampling()
          if (dut.eu.logic.parallelViptLaunch.toBoolean) parallelSeen = true
          if (dut.eu.logic.probeCancel.toBoolean) cancelSeen = true
        }
      }
      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 22, robId = 7)
      assert(waitCompletion(dut, cd, robId = 7), "forwarded load must complete")
      tracking = false
      assert(parallelSeen,
        "the real DTLB demand and virtual-set RAM read must launch on the same token cycle")
      assert(cancelSeen, "SQ forwarding must cancel the probe that will never get loadCmd")
      assert(!dut.dcache.logic.earlyProbeValid.toBoolean, "D-cache probe slot must clear")
      cd.waitSampling(3)
      dut.src.logic.obsIntAddr #= 22; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == BigInt("A1B2C3D4", 16),
        "forwarded data must remain correct")

      // Now exercise the consuming path with the SAME real, enabled DTLB: prime a
      // different line in this mapped page, then prove the resident hit uses the
      // RAM output launched alongside translation instead of issuing another read.
      val hotBase = 0x2900L
      val image = Seq(0x90, 0x91, 0x92, 0x93, 0x11, 0x22, 0x33, 0x44,
                      0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F)
      image.indices.foreach(i => mem.pokeByte(hotBase + i, image(i)))
      seed(dut, cd, preg = 12, value = hotBase)
      issueLoad(dut, cd, basePreg = 12, disp = 0, pdst = 20, robId = 8)
      assert(waitCompletion(dut, cd, robId = 8), "priming mapped load must complete")
      cd.waitSampling(4)

      tracking = true
      parallelSeen = false
      var earlyConsumeSeen = false
      fork {
        while (tracking) {
          cd.waitSampling()
          if (dut.eu.logic.parallelViptLaunch.toBoolean) parallelSeen = true
          if (dut.dcache.logic.useEarlyProbe.toBoolean &&
              dut.dcache.logic.loadCmdPort.valid.toBoolean &&
              dut.dcache.logic.loadCmdPort.ready.toBoolean) earlyConsumeSeen = true
        }
      }
      issueLoad(dut, cd, basePreg = 12, disp = 4, pdst = 21, robId = 9)
      assert(waitCompletion(dut, cd, robId = 9), "mapped L1 hit must complete")
      tracking = false
      assert(parallelSeen, "real DTLB and cache probe must launch together on the hit")
      assert(earlyConsumeSeen,
        "physical-tag resolve must consume the RAM result launched with the real DTLB lookup")
      cd.waitSampling(3)
      dut.src.logic.obsIntAddr #= 21; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == BigInt("11223344", 16),
        "real-DTLB parallel VIPT hit data")
    }
  }

  test("LS front translates and allocates a younger store while an older cache miss is outstanding", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut,
        AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 40)))
      val loadBase  = 0x7000L
      val storeBase = 0x8000L
      val loadWord  = BigInt("6A7B8C9D", 16)

      // Keep the D-cache enabled so the older load takes the ordinary cold-miss /
      // line-refill path. MMU-off remains useful here: translation is deterministic
      // identity, while the front/back overlap property is independent of a walker.
      dut.cacheCtrl.logic.dcacheEnabled #= true
      val lineImage = Seq(0x6A, 0x7B, 0x8C, 0x9D) ++ (4 until 16).map(i => 0x60 + i)
      lineImage.indices.foreach(i => mem.pokeByte(loadBase + i, lineImage(i)))
      seed(dut, cd, preg = 10, value = loadBase)
      seed(dut, cd, preg = 11, value = storeBase)
      seed(dut, cd, preg = 12, value = 0x11223344L)

      val olderRob   = 10
      val youngerRob = 11
      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 20, robId = olderRob)
      sleep(1) // let ready reflect the just-captured S1 entry after the issue edge

      // Hold the younger store at issue until the front accepts it. At that edge the
      // older load may still be in any elastic front stage (the point of D1 is that
      // acceptance no longer waits for it to reach the descriptor ring), but it must
      // remain physically resident and must not be completing on the same edge. The
      // old monolithic FSM could never satisfy this: issue.ready stayed low through
      // the entire miss/refill.
      val s = dut.src.logic
      s.iValid #= true; s.iMemOp #= MemOp.STORE; s.iSize #= Size.LONG
      s.iPsrcA #= 11; s.iPsrcAValid #= true
      s.iPsrcB #= 12; s.iPsrcBValid #= true
      s.iImm #= 0
      s.iPdstValid #= false; s.iPdst #= 0; s.iRobId #= youngerRob

      var issueWait = 0
      while (!s.iReady.toBoolean && issueWait < 200) {
        cd.waitSampling()
        issueWait += 1
      }
      assert(issueWait < 200, "younger store never reached the released LS front")
      val olderInElasticFront =
        (dut.eu.logic.s1Valid.toBoolean && dut.eu.logic.s1Ctx.robId.toInt == olderRob) ||
        (dut.eu.logic.tValid.toBoolean && dut.eu.logic.tCtx.robId.toInt == olderRob) ||
        (dut.eu.logic.p3Valid.toBoolean && dut.eu.logic.p3Ctx.front.robId.toInt == olderRob) ||
        (dut.eu.logic.p4Valid.toBoolean && dut.eu.logic.p4Ctx.xlate.front.robId.toInt == olderRob)
      val acceptedWithOlderPending =
        (olderInElasticFront || dut.eu.logic.alignedCount.toInt != 0 || dut.eu.logic.bkBusy.toBoolean) &&
        !dut.eu.logic.alignedRspFire.toBoolean &&
        !(s.cValid.toBoolean && s.cRob.toInt == olderRob)
      cd.waitSampling() // younger issue handshake
      s.iValid #= false
      assert(acceptedWithOlderPending,
        s"younger memory op must issue while the older cold load is still resident in the LS pipeline " +
        s"(wait=$issueWait busy=${dut.eu.logic.busy.toBoolean} s1=${dut.eu.logic.s1Valid.toBoolean} " +
        s"alignedCount=${dut.eu.logic.alignedCount.toInt} alignedRsp=${dut.eu.logic.alignedRspFire.toBoolean} " +
        s"cValid=${s.cValid.toBoolean} cRob=${s.cRob.toInt})")

      // Go beyond mere acceptance: prove the younger op completes translation and
      // reaches its SQ terminal action before the older miss response returns.
      var allocSeen = false
      var allocWhileOlderPending = false
      var oldCompleted = false
      var n = 0
      while (!allocSeen && n < 200) {
        if (s.cValid.toBoolean && s.cRob.toInt == olderRob) oldCompleted = true
        if (dut.eu.logic.sq.io.alloc.valid.toBoolean &&
            dut.eu.logic.sq.io.alloc.payload.robId.toInt == youngerRob) {
          allocSeen = true
          // The older descriptor can enter the ring on this exact edge. In that
          // consume-and-accept case alignedEnq is the authoritative evidence;
          // alignedCount is a Reg and still exposes its pre-edge value here.
          allocWhileOlderPending = (dut.eu.logic.alignedCount.toInt != 0 ||
                                    dut.eu.logic.alignedEnq.toBoolean) &&
                                   !dut.eu.logic.alignedRspFire.toBoolean &&
                                   !oldCompleted
        }
        if (!allocSeen) cd.waitSampling()
        n += 1
      }
      assert(allocSeen, "younger store never reached SQ allocation")
      assert(allocWhileOlderPending,
        "younger store must translate and allocate before the older cold-load response")

      // The decoupling must not lose or misattribute the untagged cache response.
      if (!oldCompleted)
        assert(waitCompletion(dut, cd, robId = olderRob), "older load completion was lost after front-stage overlap")
      cd.waitSampling(3)
      s.obsIntAddr #= 20; sleep(1)
      assert(s.obsIntData.toBigInt == loadWord,
        f"older load data corrupted across front/back overlap: got 0x${s.obsIntData.toBigInt}%08X")
    }
  }

  test("D1 same-page resident loads issue, translate, enqueue, command, and complete at II=1", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0xA800L
      val vpn  = base >>> 12
      val image = (0 until 16).map(i => (0x20 + i * 7) & 0xff)
      image.indices.foreach(i => mem.pokeByte(base + i, image(i)))
      buildResidentCopybackPage(ptmem, base, ppn = vpn)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      dut.cacheCtrl.logic.dcacheEnabled #= true
      seed(dut, cd, preg = 10, value = base)

      // Warm both the real DTLB entry and the L1D line before measuring the hit
      // stream. The burst itself then isolates resident-hit initiation interval.
      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 20, robId = 8)
      assert(waitCompletion(dut, cd, robId = 8), "warm-up load must complete")
      cd.waitSampling(4)

      // Eight requests exceed both four-entry queues, so the property also proves
      // full-queue consume-and-replace rather than only initial fill behavior.
      val disps = Seq(0, 4, 8, 12, 0, 4, 8, 12)
      val robs  = 16 until 24
      val pdsts = 24 until 32
      val issueCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
      val enqCycles   = scala.collection.mutable.ArrayBuffer.empty[Int]
      val cmdCycles   = scala.collection.mutable.ArrayBuffer.empty[Int]
      val compCycles  = scala.collection.mutable.ArrayBuffer.empty[Int]
      val compRobs    = scala.collection.mutable.ArrayBuffer.empty[Int]
      var allFrontStagesOccupied = false
      var parallelLaunches = 0

      def driveLoad(i: Int): Unit = {
        val s = dut.src.logic
        s.iValid #= true; s.iMemOp #= MemOp.LOAD; s.iSize #= Size.LONG
        s.iPsrcA #= 10; s.iPsrcAValid #= true
        s.iPsrcB #= 0; s.iPsrcBValid #= false
        s.iImm #= disps(i)
        s.iPdst #= pdsts(i); s.iPdstValid #= true; s.iRobId #= robs(i)
      }

      driveLoad(0)
      var nextIssue = 0
      var cycle = 0
      while (compRobs.size < robs.size && cycle < 200) {
        sleep(1)
        val s = dut.src.logic
        val issueFire = s.iValid.toBoolean && s.iReady.toBoolean
        if (issueFire) issueCycles += cycle
        if (dut.eu.logic.alignedEnq.toBoolean) enqCycles += cycle
        if (dut.dcache.logic.loadCmdPort.valid.toBoolean &&
            dut.dcache.logic.loadCmdPort.ready.toBoolean) cmdCycles += cycle
        if (dut.eu.logic.parallelViptLaunch.toBoolean) parallelLaunches += 1
        if (s.cValid.toBoolean && robs.contains(s.cRob.toInt)) {
          compCycles += cycle
          compRobs += s.cRob.toInt
        }
        if (dut.eu.logic.tValid.toBoolean && dut.eu.logic.p3Valid.toBoolean &&
            dut.eu.logic.p4Valid.toBoolean) allFrontStagesOccupied = true

        cd.waitSampling()
        cycle += 1
        if (issueFire) {
          nextIssue += 1
          if (nextIssue < robs.size) driveLoad(nextIssue)
          else s.iValid #= false
        }
      }

      def isConsecutive(xs: Seq[Int]): Boolean =
        xs.size == robs.size && xs.sliding(2).forall { case Seq(a, b) => b == a + 1 }

      assert(isConsecutive(issueCycles.toSeq),
        s"resident load issue must be bubble-free, cycles=$issueCycles")
      assert(allFrontStagesOccupied,
        "the burst must physically occupy P2/P3/P4 concurrently (multiple in flight)")
      assert(isConsecutive(enqCycles.toSeq),
        s"translated aligned descriptors must enqueue at II=1, cycles=$enqCycles")
      assert(isConsecutive(cmdCycles.toSeq),
        s"resident load commands must reach L1D at II=1, cycles=$cmdCycles")
      assert(isConsecutive(compCycles.toSeq),
        s"resident L1D hits must complete at II=1, cycles=$compCycles robs=$compRobs")
      assert(compRobs.toSeq == robs,
        s"untagged L1D responses must remain in issue order, got=$compRobs expected=$robs")
      assert(parallelLaunches == robs.size,
        s"every warm same-page DTLB lookup must launch its virtual-set probe in " +
        s"parallel (launches=$parallelLaunches loads=${robs.size})")

      cd.waitSampling(3)
      for (i <- robs.indices) {
        dut.src.logic.obsIntAddr #= pdsts(i); sleep(1)
        val expected = image.slice(disps(i), disps(i) + 4)
          .foldLeft(BigInt(0))((acc, b) => (acc << 8) | BigInt(b))
        assert(dut.src.logic.obsIntData.toBigInt == expected,
          f"burst load $i data mismatch: got=0x${dut.src.logic.obsIntData.toBigInt}%08X expected=0x$expected%08X")
      }
    }
  }

  test("aligned-load descriptor ring holds multiple cache loads and pairs untagged responses in order", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut,
        AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 40)))
      val baseA = 0xA000L
      val baseB = 0xB000L
      val wordA = BigInt("10203040", 16)
      val wordB = BigInt("50607080", 16)

      dut.cacheCtrl.logic.dcacheEnabled #= true
      Seq(0x10, 0x20, 0x30, 0x40).zipWithIndex.foreach { case (b, i) => mem.pokeByte(baseA + i, b) }
      Seq(0x50, 0x60, 0x70, 0x80).zipWithIndex.foreach { case (b, i) => mem.pokeByte(baseB + i, b) }
      seed(dut, cd, preg = 10, value = baseA)
      seed(dut, cd, preg = 11, value = baseB)

      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 20, robId = 10)
      issueLoad(dut, cd, basePreg = 11, disp = 0, pdst = 21, robId = 11)

      // A is already in refill when B reaches RESOLVE. The former one-entry back
      // end stalled B there; C3 must enqueue both pruned descriptors concurrently.
      var sawTwo = false
      var earlyCompletion = false
      var n = 0
      while (!sawTwo && n < 80) {
        sawTwo ||= dut.eu.logic.alignedCount.toInt >= 2
        earlyCompletion ||= dut.src.logic.cValid.toBoolean
        if (!sawTwo) cd.waitSampling()
        n += 1
      }
      assert(sawTwo,
        s"descriptor ring never held both aligned loads (count=${dut.eu.logic.alignedCount.toInt})")
      assert(!earlyCompletion, "the delayed older refill must still be pending when both descriptors are resident")

      val completions = scala.collection.mutable.ArrayBuffer.empty[Int]
      n = 0
      while (completions.size < 2 && n < 300) {
        if (dut.src.logic.cValid.toBoolean) completions += dut.src.logic.cRob.toInt
        if (completions.size < 2) cd.waitSampling()
        n += 1
      }
      assert(completions == Seq(10, 11),
        s"untagged cache responses must remain paired in acceptance order, got $completions")
      cd.waitSampling(3)
      dut.src.logic.obsIntAddr #= 20; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == wordA, "older queued load data")
      dut.src.logic.obsIntAddr #= 21; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == wordB, "younger queued load data")
      assert(dut.eu.logic.alignedCount.toInt == 0, "descriptor ring must drain completely")
    }
  }

  test("aligned-load descriptor ring backpressures at four and retains the fifth across serialized misses", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut,
        // Keep the first refill pending long enough for all four descriptors plus
        // the fifth front token to arrive.  The old 40-cycle value became too short
        // after the II=1 DTLB/front work and could let response turnover coincide
        // with the first full cycle, never exercising the advertised backpressure.
        AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 200)))
      dut.cacheCtrl.logic.dcacheEnabled #= true

      val bases = (0 until 5).map(i => 0x12000L + i * 0x1000L)
      bases.zipWithIndex.foreach { case (base, i) =>
        val bytes = Seq(0x20 + i, 0x30 + i, 0x40 + i, 0x50 + i)
        bytes.zipWithIndex.foreach { case (b, j) => mem.pokeByte(base + j, b) }
        seed(dut, cd, preg = 10 + i, value = base)
      }

      // The fifth µop may enter the elastic front, but must remain resident upstream
      // while all four descriptors are occupied.  With parallel VIPT, its exact hold
      // point can be P1/P2/P2T/P3/P4: the four-entry early-probe queue may apply
      // backpressure before RESOLVE.  Requiring the token to remain in one of those
      // real stages is the non-vacuous capacity contract; requiring only P4 was stale.
      (0 until 5).foreach { i =>
        issueLoad(dut, cd, basePreg = 10 + i, disp = 0,
                  pdst = 20 + i, robId = 10 + i)
      }

      var sawFullStall = false
      var n = 0
      while (!sawFullStall && n < 100) {
        val fifthResident =
          (dut.eu.logic.s1Valid.toBoolean && dut.eu.logic.s1Ctx.robId.toInt == 14) ||
          (dut.eu.logic.tValid.toBoolean && dut.eu.logic.tCtx.robId.toInt == 14) ||
          (dut.eu.logic.txValid.toBoolean && dut.eu.logic.txCtx.robId.toInt == 14) ||
          (dut.eu.logic.p3Valid.toBoolean && dut.eu.logic.p3Ctx.front.robId.toInt == 14) ||
          (dut.eu.logic.p4Valid.toBoolean && dut.eu.logic.p4Ctx.xlate.front.robId.toInt == 14)
        sawFullStall = dut.eu.logic.alignedFull.toBoolean && fifthResident
        if (!sawFullStall) cd.waitSampling()
        n += 1
      }
      assert(sawFullStall,
        s"fifth load was not retained in the front behind a full descriptor ring " +
        s"(count=${dut.eu.logic.alignedCount.toInt})")

      val completions = scala.collection.mutable.ArrayBuffer.empty[Int]
      var sawSerializedMissBoundary = false
      var sawFifthEnqueue = false
      n = 0
      while (completions.size < 5 && n < 1300) {
        val p4Rob = if (dut.eu.logic.p4Valid.toBoolean)
          dut.eu.logic.p4Ctx.xlate.front.robId.toInt else -1
        // A cold miss deliberately blocks further D-cache probes: general
        // hit-under-miss is out of scope.  The fifth request therefore remains in
        // the elastic LS front when the oldest ring entry completes; it cannot be
        // used as a resident-hit pop/push turnover test.  Prove that boundary
        // explicitly, then prove that the retained request is eventually admitted.
        sawSerializedMissBoundary ||=
          dut.eu.logic.alignedRspFire.toBoolean &&
          dut.eu.logic.tValid.toBoolean &&
          dut.eu.logic.tCtx.robId.toInt == 14 &&
          !dut.eu.logic.alignedEnq.toBoolean
        sawFifthEnqueue ||=
          dut.eu.logic.alignedEnq.toBoolean && p4Rob == 14
        if (dut.src.logic.cValid.toBoolean)
          completions += dut.src.logic.cRob.toInt
        if (completions.size < 5) cd.waitSampling()
        n += 1
      }
      assert(sawSerializedMissBoundary,
        "cold-miss serialization must retain the fifth request upstream when the oldest ring entry completes")
      assert(sawFifthEnqueue,
        "the fifth request retained behind the full ring must eventually enqueue exactly once")
      assert(completions == Seq(10, 11, 12, 13, 14),
        s"full-ring untagged response association/order: got $completions")
      assert(dut.eu.logic.alignedCount.toInt == 0, "full descriptor ring must drain")
    }
  }

  test("flush poisons every resident aligned-load descriptor and broadcasts VIPT cancel-all", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut,
        AxiMemModelConfig(latency = L2LatencyModel(enabled = true, dramCycles = 40)))
      dut.cacheCtrl.logic.dcacheEnabled #= true
      val bases = Seq(0x18000L, 0x19000L)
      bases.zipWithIndex.foreach { case (base, i) =>
        (0 until 16).foreach(j => mem.pokeByte(base + j, 0x70 + i * 0x10 + j))
        seed(dut, cd, preg = 10 + i, value = base)
      }
      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 20, robId = 10)
      issueLoad(dut, cd, basePreg = 11, disp = 0, pdst = 21, robId = 11)

      var n = 0
      while (dut.eu.logic.alignedCount.toInt < 2 && n < 80) {
        cd.waitSampling(); n += 1
      }
      assert(dut.eu.logic.alignedCount.toInt == 2, "precondition: two queued loads")
      dut.src.logic.iSqFlush #= true
      sleep(1)
      assert(dut.eu.logic.probeCancelAll.toBoolean,
        "the LS flush boundary must broadcast the queue-wide VIPT cancellation form")
      cd.waitSampling()
      dut.src.logic.iSqFlush #= false
      sleep(1)
      assert(!dut.dcache.logic.earlyProbeValids.exists(_.toBoolean),
        "LS cancel-all must leave no speculative VIPT token resident")

      var leakedCompletion = false
      n = 0
      while (dut.eu.logic.alignedCount.toInt != 0 && n < 400) {
        leakedCompletion ||= dut.src.logic.cValid.toBoolean &&
                            Set(10, 11).contains(dut.src.logic.cRob.toInt)
        cd.waitSampling(); n += 1
      }
      leakedCompletion ||= dut.src.logic.cValid.toBoolean &&
                          Set(10, 11).contains(dut.src.logic.cRob.toInt)
      assert(dut.eu.logic.alignedCount.toInt == 0,
        "poisoned descriptors must still drain their untagged responses")
      assert(!leakedCompletion, "a flushed queued load must not complete")
      dut.src.logic.obsIntAddr #= 20; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 0, "flushed older load wrote PRF")
      dut.src.logic.obsIntAddr #= 21; sleep(1)
      assert(dut.src.logic.obsIntData.toBigInt == 0, "flushed younger load wrote PRF")
    }
  }

  // Task P5.6: CACR.DE=0 must mean literally fully-uncached, matching real 68040
  // silicon -- effectiveMode = DE ? pageMode : INHIBITED for EVERY data access,
  // regardless of the page's own attribute. This is the direct counterpart of the
  // DE=1 test immediately above: same MMU-on setup, but the page is COPYBACK (not
  // WRITETHROUGH) and DE is poked to 0. If DE=0 correctly overrides the page's own
  // COPYBACK classification, s2Cmode must read INHIBITED and the store must
  // classify precise=True (exactly like a genuinely architecturally-INHIBITED page
  // would) -- NOT precise=False just because the page itself is cacheable.
  test("MMU-on COPYBACK-page store with DE=0 classifies precise=True and s2Cmode=INHIBITED (DE overrides page attribute)", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x3000L
      val vpn  = (base >> 12) & 0xfffff
      buildResidentCopybackPage(ptmem, base, ppn = vpn)   // identity PPN=VPN
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      dut.cacheCtrl.logic.dcacheEnabled #= false   // DE=0: literally fully uncached
      seed(dut, cd, preg = 10, value = base)
      seed(dut, cd, preg = 11, value = 0xFACEFEEDL)
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 7)
      val (idx, completedNextCycle) = waitAlloc(dut, cd, robId = 7)
      assert(idx >= 0, "store must allocate into the SQ")
      assert(!completedNextCycle, "DE=0 (precise-path) store must NOT drive completionPort right after allocating")
      cd.waitSampling(2)
      assert(dut.eu.logic.sq.robIds(idx).toInt == 7, "sanity: allocated entry belongs to this store")
      assert(dut.eu.logic.sq.precises(idx).toBoolean, "MMU-on COPYBACK DE=0 store must classify precise=True -- DE=0 overrides the page's own COPYBACK attribute")
      assert(dut.eu.logic.s2Cmode.toEnum == m68k040.cache.CacheMode.INHIBITED,
        s"DE=0 must fold into s2Cmode as INHIBITED regardless of the page's own COPYBACK attribute, got ${dut.eu.logic.s2Cmode.toEnum}")
    }
  }

  // ── Task P2.5 post-review fix: `liveCompletionFires` same-cycle collision ────────
  // (LsEuPlugin.scala's deferred-completion replay: `deferCompletion`/`pendMem`/
  // `pendReady`/`pendApply`/`liveCompletionFires`). If the live EU pipe's own
  // `captureCompletion`/`captureFault` and a pending replay entry BOTH want the
  // shared `comp*` stage on the SAME cycle, the ROB-head precise replay is older and
  // must preempt the younger front completion. A launched cache response remains the
  // only higher-priority producer because its Flow cannot be backpressured.
  //
  // This directed test engineers that exact collision: it drives the SQ's real
  // precise-path at-head drain (`robHeadIn`/`robHeadValidIn`, normally supplied by
  // RobPlugin -- see the `simPublic()` taps added to those pass-throughs for exactly
  // this purpose) for a full BATCH of precise stores, WHILE a continuous,
  // fully-deterministic II=1 LEA train (LEA never translates / never touches the
  // D-cache or AXI at all) runs concurrently through the elastic front. A
  // precise store's drain-confirm cycle (`sq.io.sqCompletion`) is timed by the
  // D-cache write path's randomized AXI-ready handshake (`BehavioralMemAgent`'s
  // `StreamReadyRandomizer`s) -- running a whole batch (one per SQ slot, 8 independent
  // draws) with the LEA train active throughout reliably produces at least one
  // exact-cycle collision for a FIXED sim seed. Pinning the seed makes the entire run
  // (including every AXI-ready draw) bit-for-bit reproducible, so this is
  // deterministic on every future run, not flaky -- `priorityCollisionSeen` is asserted
  // explicitly so a future change that accidentally stops exercising the collision
  // path fails loudly instead of silently passing a vacuous test.
  test("precise replay preempts a colliding younger completion without dropping either stream", VerilatorTest) {
    simConfig.compile(new Dut).doSim(2) { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x5000L
      seed(dut, cd, preg = 10, value = base)          // store base
      seed(dut, cd, preg = 11, value = 0xC001C0DEL)   // store data
      seed(dut, cd, preg = 12, value = 0x9000L)       // LEA base (unrelated address space)

      val numStores   = 8   // == the SQ's own depth: maximizes independent drain-latency draws
      val storeRobIds = (0 until numStores).map(20 + _)

      // Phase 1: allocate every precise store UP FRONT (robHeadValidIn stays False --
      // no drain trigger yet), one at a time through the single-outstanding EU pipe.
      for ((robId, i) <- storeRobIds.zipWithIndex) {
        issueStore(dut, cd, basePreg = 10, disp = i * 4, dataPreg = 11, Size.LONG, robId = robId)
      }
      // A few extra cycles of margin: `iReady` returning for store N only guarantees
      // store N-1 has already allocated, not that store N itself has (its own
      // translate/resolve still needs a couple more cycles) -- wait for it to settle.
      cd.waitSampling(10)
      assert(dut.eu.logic.sq.io.full.toBoolean, s"all $numStores stores must be resident (SQ full) before draining starts")

      // Phase 2: start the SQ's real background drain (auto-track whichever entry is
      // CURRENTLY at the ring head -- entries drain strictly in order) AND a
      // continuous LEA train through the SAME EU pipe, concurrently, via forks.
      val doneFlag = new java.util.concurrent.atomic.AtomicBoolean(false)
      fork {
        while (!doneFlag.get()) {
          val h = dut.eu.logic.sq.head.toInt
          dut.wire.logic.iRobHeadIn      #= dut.eu.logic.sq.robIds(h).toInt
          dut.wire.logic.iRobHeadValidIn #= dut.eu.logic.sq.valids(h).toBoolean
          cd.waitSampling()
        }
      }
      fork {
        var i = 0
        while (!doneFlag.get()) {
          issueLea(dut, cd, basePreg = 12, robId = 40 + (i % 8))
          i += 1
        }
      }

      // Observe: every precise store's sqCompletionPort pulse (must land EXACTLY
      // once each, never dropped/duplicated even across a collision), and whether a
      // genuine younger-front / precise-replay collision was observed and resolved
      // in favor of the older replay (the interesting path this test exercises).
      val seenCounts = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
      var priorityCollisionSeen = false
      var n = 0
      val maxCycles = 4000
      while (seenCounts.keySet.size < numStores && n < maxCycles) {
        val wantsApply =
          (dut.eu.logic.pendApply.toInt == dut.eu.logic.pendReady.toInt && dut.eu.logic.sq.io.sqCompletion.valid.toBoolean) ||
          (dut.eu.logic.pendApply.toInt != dut.eu.logic.pendReady.toInt)
        if (wantsApply && dut.eu.logic.preciseReplayClaimsComp.toBoolean &&
            dut.eu.logic.frontCompHeld.toBoolean) {
          assert(!dut.eu.logic.liveCompletionFires.toBoolean,
            "a younger front completion fired despite the older precise replay owning the slot")
          priorityCollisionSeen = true
        }
        if (dut.wire.logic.oSqCompValid.toBoolean) {
          val rid = dut.wire.logic.oSqCompPayload.toInt
          seenCounts(rid) += 1
        }
        cd.waitSampling()
        n += 1
      }
      doneFlag.set(true)
      cd.waitSampling(4)

      assert(n < maxCycles, s"timed out waiting for all $numStores precise-store completions (only saw ${seenCounts.keySet.size})")
      assert(priorityCollisionSeen,
        "test failed to engineer a younger-front collision with an older precise replay -- " +
        "adjust numStores/seed (this assertion is intentional: it proves the interesting priority path was " +
        "actually exercised, not merely inferred)")
      assert(storeRobIds.forall(seenCounts.contains),
        s"every store's robId must be observed via sqCompletionPort, got ${seenCounts.keySet}")
      assert(storeRobIds.forall(r => seenCounts(r) == 1),
        "every precise store's sqCompletionPort pulse must fire EXACTLY once (no drop, no duplicate) " +
        s"even across a liveCompletionFires collision, got $seenCounts")
    }
  }

  // ── P2.7 ROOT-CAUSE regression test: `pendMem` and the SQ ring are a lock-step PAIR.
  // A flush landing on the EXACT cycle a precise store's alloc arm fires must drop BOTH
  // the SQ alloc (StoreQueue's own `when(io.alloc.valid && !io.flush)`) AND the pendMem
  // push -- otherwise the two streams desynchronize by one FOREVER and, from then on,
  // every precise store's drain replays the WRONG pendMem entry: ROB completion port 4
  // fires for a stale robId while the real store's robId never completes (ROB head
  // parks -> HANG), and the stale entry's An-auto/A7/NZVC writeback lands with the
  // wrong data (WRONG ANSWER). This is what hung 15 ported-corpus tests.
  //
  // The pre-fix code believed the separate `when(sqFlushSig) { pendPush :=
  // pendReadyAfterThisCycle }` rollback covered this. It cannot: that rollback is a
  // PLAIN component statement while the push lives in a `StateMachine` state body, and
  // SpinalHDL elaborates StateMachine bodies from a pre-pop task -- AFTER every plain
  // statement -- so the FSM's `pendPush := pendPush + 1` is emitted last and silently
  // overrides the rollback (visible directly in the generated Verilog). `poisoned`
  // cannot cover it either: it is a Reg SET BY this same flush pulse, so it only reads
  // True from the following cycle on.
  //
  // Reverting the `when(!sqFlushSig)` gate in `deferCompletion` makes BOTH assertions
  // below fail (the pointer-skew one immediately, then the wrong-robId one) -- confirmed
  // by revert-and-rerun.
  test("P2.7: a flush on a precise store's alloc cycle drops the pendMem push in lock-step with the SQ alloc", VerilatorTest) {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      seed(dut, cd, preg = 10, value = 0x6000L)       // store base
      seed(dut, cd, preg = 11, value = 0xFEEDFACEL)   // store data

      // MMU off + DE=0 (initDut defaults) => every store is a PRECISE store.
      assert(dut.eu.logic.pendPush.toInt == dut.eu.logic.pendReady.toInt,
        "precondition: pend FIFO starts balanced")

      // Wait until the real D1 P3 terminal action is combinationally poised to
      // allocate, then raise flush before its sampling edge. This is deterministic
      // and non-vacuous: unlike an offset sweep it observes the actual action point,
      // and it remains valid if the issue->P3 latency changes again.
      issueStore(dut, cd, basePreg = 10, disp = 0, dataPreg = 11, Size.LONG, robId = 5)
      var waitPoised = 0
      while (!dut.eu.logic.sq.io.alloc.valid.toBoolean && waitPoised < 100) {
        cd.waitSampling()
        waitPoised += 1
      }
      assert(waitPoised < 100,
        "precise store never became poised for SQ allocation; coincidence test would be vacuous")
      assert(dut.eu.logic.sq.io.alloc.payload.robId.toInt == 5,
        "the poised allocation must belong to the store under test")
      val pushBeforeFlush = dut.eu.logic.pendPush.toInt
      val readyBeforeFlush = dut.eu.logic.pendReady.toInt

      dut.src.logic.iSqFlush #= true
      sleep(1) // let the same-cycle combinational flush arbitration settle
      assert(dut.eu.logic.sq.io.flush.toBoolean,
        "testbench flush must reach the SQ in the engineered coincidence cycle")
      assert(!dut.eu.logic.sq.io.alloc.valid.toBoolean,
        "D1 must suppress the poised speculative allocation combinationally on flush")
      cd.waitSampling()
      dut.src.logic.iSqFlush #= false
      cd.waitSampling(10)

      assert(!dut.eu.logic.sq.valids.exists(_.toBoolean),
        "flush at the poised allocation boundary must leave no resident SQ entry")
      assert(dut.eu.logic.pendPush.toInt == pushBeforeFlush &&
             dut.eu.logic.pendReady.toInt == readyBeforeFlush,
        s"flush at the poised allocation boundary must not advance either pending pointer " +
        s"(before push/ready=$pushBeforeFlush/$readyBeforeFlush, after=" +
        s"${dut.eu.logic.pendPush.toInt}/${dut.eu.logic.pendReady.toInt})")

      // End-to-end consequence check: the NEXT precise store must have its OWN robId
      // replayed out sqCompletionPort. With the skew, its drain would replay a squashed
      // (robId 5) pendMem entry instead.
      issueStore(dut, cd, basePreg = 10, disp = 8, dataPreg = 11, Size.LONG, robId = 7)
      var m = 0
      while (!dut.eu.logic.sq.valids.exists(_.toBoolean) && m < 200) { cd.waitSampling(); m += 1 }
      assert(m < 200, "store B never became resident in the SQ")
      // Park the ROB head on store B's robId so its at-head precise drain launches.
      val h = dut.eu.logic.sq.head.toInt
      dut.wire.logic.iRobHeadIn      #= dut.eu.logic.sq.robIds(h).toInt
      dut.wire.logic.iRobHeadValidIn #= true

      var seenRid = -1
      var k = 0
      while (seenRid < 0 && k < 400) {
        if (dut.wire.logic.oSqCompValid.toBoolean) seenRid = dut.wire.logic.oSqCompPayload.toInt
        cd.waitSampling(); k += 1
      }
      assert(seenRid >= 0, "store B's precise at-head drain never produced a completion")
      assert(seenRid == 7,
        s"P2.7 FIX: store B's drain must complete its OWN robId 7, got $seenRid -- a value of 5 " +
        "is the flushed store A's stale pendMem entry being replayed under B's drain (the exact " +
        "mis-attributed completion that hung 15 ported tests)")
    }
  }
}
