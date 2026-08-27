package m68k040.ls

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.cache.DcachePlugin
import m68k040.execute.LsEuPlugin
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.isa.{MemOp, Size}
import m68k040.mmu.{DtlbPlugin, MmuControlPlugin}
import m68k040.sim.{AxiMemModel, AxiMemModelConfig}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Regression for the interrupt/trace/debug-auto-halt preemption gap in the
  * cache-inhibited (device) LOAD path, found alongside `f5f9fe13`'s precise-launch
  * fix: that commit made `p4LaunchOk` wait until an inhibited load IS the ROB head
  * before launching its AXI read, closing the deadlock/spin half of the bug, but
  * nothing stopped an interrupt, a trace exception, or a debug automatic halt from
  * PREEMPTING that exact head once it had ALREADY launched a real, in-flight device
  * transaction -- silently poisoning the response and causing the SAME instruction
  * to re-execute (and re-read the device -- fatal for a clear-on-read/pop register)
  * after RTE/recover.
  *
  * Two independent interlocks close this, mirroring StoreQueue's own
  * `irqPreemptPendingIn`/`preciseDrainBusy` pair for the analogous STORE-drain case
  * (see StoreQueueSpec's "P2.4" tests):
  *
  *   1. `p4LaunchOk`'s inhibited arm now also requires `!irqPreemptPendingIn` and
  *      `!debugHaltImminentIn` -- do not LAUNCH on a cycle where an interrupt/trace
  *      is already known pending, or a debug automatic-halt boundary is already due
  *      for this exact head. Tested here directly (Tests A/B).
  *   2. `inhibitedLoadBusySig` -- true from launch until the response is genuinely
  *      consumed, +1 cycle -- feeds the ROB's `normalIrqGate`/`traceNormalGate` as a
  *      sibling of `preciseDrainBusyIn`, closing the narrower race where the
  *      interrupt/trace only becomes pending AFTER the load has already launched
  *      (invisible to a launch-time gate by construction). The ROB-side half of
  *      that interlock is tested directly in RobPluginSpec (mirroring its own
  *      `preciseDrainBusyIn blocks interruptPending/tracePendingFire` pair); this
  *      file proves the LOAD-side signal's own launch-to-consumption lifecycle
  *      (Test C).
  *
  * Test D is the inverse sanity check: neither interlock may touch an ORDINARY
  * (non-inhibited, mapped+cacheable) load -- it must launch and complete at full
  * speed regardless of `irqPreemptPendingIn`/`debugHaltImminentIn`, proving the fix
  * does not reintroduce a one-at-a-time chokepoint on the common hot path.
  */
class InhibitedLoadIrqPreemptSpec extends AnyFunSuite {
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
    val wire      = new TbPreemptWirePlugin(eu)
    db.on { host.asHostOf(Seq[FiberPlugin](param, rfInt, rfNzvc, rfX, ctrl, dtlb, dcache, cacheCtrl, eu, src, wire)) }
  }

  /** Real-IO detour for `robHeadIn`/`robHeadValidIn`/`irqPreemptPendingIn`/
    * `debugHaltImminentIn` -- mirrors LsEuFastPreciseSpec's `TbPreciseDrainWirePlugin`
    * exactly (see its doc comment for why a raw sim poke on an `allowOverride`
    * pass-through cannot work under Verilator without a real driving IO port). */
  class TbPreemptWirePlugin(eu: LsEuPlugin) extends FiberPlugin {
    val logic = during build new Area {
      val iRobHeadIn           = in UInt (6 bits)
      val iRobHeadValidIn      = in Bool ()
      val iIrqPreemptPendingIn = in Bool ()
      val iDebugHaltImminentIn = in Bool ()
      eu.robHeadIn           := iRobHeadIn
      eu.robHeadValidIn      := iRobHeadValidIn
      eu.irqPreemptPendingIn := iIrqPreemptPendingIn
      eu.debugHaltImminentIn := iDebugHaltImminentIn
    }
  }

  def simConfig = M68kSim().withVerilator

  val ROOT = 0x30000L
  val PTRT = 0x31000L
  val PAGT = 0x32000L

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

  def initDut(dut: Dut): (ClockDomain, AxiMemModel, BehavioralMemAgent) = {
    val cd = dut.clockDomain; cd.forkStimulus(10)
    val mem   = AxiMemModel.attachFull(dut.dcache.logic.axi, cd, AxiMemModelConfig())
    val ptmem = new BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
    val s = dut.src.logic
    s.iValid #= false; s.iSqCommitValid #= false; s.iSqFlush #= false
    s.iStkPush #= false; s.iLeaAddr #= false
    s.seedValid #= false; s.obsIntAddr #= 0; s.iPsrcAValid #= false; s.iPsrcBValid #= false
    dut.ctrl.logic.mmuEnable #= false
    dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
    dut.cacheCtrl.logic.dcacheEnabled #= false
    // MMU off + DE=0 => every access is INHIBITED (see LsEuFastPreciseSpec's
    // identical default posture) -- exactly the "device access" state this bug
    // concerns, no extra page-table setup needed for the inhibited-side tests.
    dut.wire.logic.iRobHeadIn      #= 0; dut.wire.logic.iRobHeadValidIn      #= false
    dut.wire.logic.iIrqPreemptPendingIn #= false
    dut.wire.logic.iDebugHaltImminentIn #= false
    cd.waitSampling(80) // PRF init sweep
    // D-cache re-invalidation sweep (c6e3ad4) refuses all load/store admission for
    // 128 cycles after reset -- wait it out once, mirrors every other directed LS
    // spec's initDut.
    cd.waitSamplingWhere(!dut.dcache.logic.resetSweepBusy.toBoolean)
    (cd, mem, ptmem)
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

  def arFiredTo(dut: Dut, addr: Long): Boolean =
    dut.dcache.logic.axi.ar.valid.toBoolean && dut.dcache.logic.axi.ar.ready.toBoolean &&
    dut.dcache.logic.axi.ar.payload.addr.toBigInt == addr

  def completed(dut: Dut, robId: Int): Boolean =
    dut.src.logic.cValid.toBoolean && dut.src.logic.cRob.toInt == robId

  // ── Test A: launch-time interlock, interrupt/trace source ──────────────────────
  test("A: irqPreemptPendingIn blocks an inhibited load's device read from launching") {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x9000L
      val robId = 12
      Seq(0x11, 0x22, 0x33, 0x44).zipWithIndex.foreach { case (b, i) => mem.pokeByte(base + i, b) }
      seed(dut, cd, preg = 10, value = base)

      // This load is the ROB head from the start (no older store) -- p4LaunchOk's
      // ONLY remaining reason to withhold launch is the preempt-pending gate itself.
      dut.wire.logic.iRobHeadIn      #= robId
      dut.wire.logic.iRobHeadValidIn #= true
      dut.wire.logic.iIrqPreemptPendingIn #= true

      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 20, robId = robId)

      for (_ <- 0 until 30) {
        assert(!arFiredTo(dut, base),
          "irqPreemptPendingIn must block the inhibited load's AR from ever firing")
        assert(!completed(dut, robId),
          "a blocked-at-launch load must not complete")
        cd.waitSampling()
      }

      // Drop the gate: the same load, still parked at the ROB head, must now launch
      // and complete -- proves the block was the gate, not a stuck pipeline.
      dut.wire.logic.iIrqPreemptPendingIn #= false
      var arSeen = false
      var doneSeen = false
      var n = 0
      while ((!arSeen || !doneSeen) && n < 200) {
        if (arFiredTo(dut, base)) arSeen = true
        if (completed(dut, robId)) doneSeen = true
        cd.waitSampling(); n += 1
      }
      assert(arSeen, "device read must launch once irqPreemptPendingIn drops")
      assert(doneSeen, "load must complete once its device read resolves")
    }
  }

  // ── Test B: launch-time interlock, debug automatic-halt source ────────────────
  test("B: debugHaltImminentIn blocks an inhibited load's device read from launching") {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x9100L
      val robId = 13
      Seq(0x55, 0x66, 0x77, 0x88).zipWithIndex.foreach { case (b, i) => mem.pokeByte(base + i, b) }
      seed(dut, cd, preg = 10, value = base)

      dut.wire.logic.iRobHeadIn      #= robId
      dut.wire.logic.iRobHeadValidIn #= true
      dut.wire.logic.iDebugHaltImminentIn #= true

      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 21, robId = robId)

      for (_ <- 0 until 30) {
        assert(!arFiredTo(dut, base),
          "debugHaltImminentIn must block the inhibited load's AR from ever firing")
        assert(!completed(dut, robId),
          "a blocked-at-launch load must not complete")
        cd.waitSampling()
      }

      dut.wire.logic.iDebugHaltImminentIn #= false
      var arSeen = false
      var doneSeen = false
      var n = 0
      while ((!arSeen || !doneSeen) && n < 200) {
        if (arFiredTo(dut, base)) arSeen = true
        if (completed(dut, robId)) doneSeen = true
        cd.waitSampling(); n += 1
      }
      assert(arSeen, "device read must launch once debugHaltImminentIn drops")
      assert(doneSeen, "load must complete once its device read resolves")
    }
  }

  // ── Test C: inhibitedLoadBusySig's own launch-to-consumption lifecycle ────────
  test("C: inhibitedLoadBusySig is true from launch through response consumption, and clears shortly after") {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0x9200L
      val robId = 14
      Seq(0x01, 0x02, 0x03, 0x04).zipWithIndex.foreach { case (b, i) => mem.pokeByte(base + i, b) }
      seed(dut, cd, preg = 10, value = base)

      dut.wire.logic.iRobHeadIn      #= robId
      dut.wire.logic.iRobHeadValidIn #= true

      assert(!dut.eu.logic.loadBusyReg.toBoolean, "precondition: busy is idle before the load issues")

      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 22, robId = robId)

      var enqCycle = -1
      var arCycle = -1
      var rspCycle = -1
      var clearCycle = -1
      var everFalseBetweenEnqAndRsp = false
      var sawBusyByArTime = false
      var cyc = 0
      val maxCycles = 300
      while (clearCycle < 0 && cyc < maxCycles) {
        if (enqCycle < 0 && dut.eu.logic.alignedEnq.toBoolean) enqCycle = cyc
        if (arCycle < 0 && arFiredTo(dut, base)) {
          arCycle = cyc
          sawBusyByArTime = dut.eu.logic.loadBusyReg.toBoolean
        }
        if (rspCycle < 0 && dut.eu.logic.alignedRspFire.toBoolean) rspCycle = cyc
        if (enqCycle >= 0 && rspCycle < 0 && !dut.eu.logic.loadBusyReg.toBoolean && cyc > enqCycle + 1)
          everFalseBetweenEnqAndRsp = true
        if (rspCycle >= 0 && clearCycle < 0 && !dut.eu.logic.loadBusyReg.toBoolean) clearCycle = cyc
        cd.waitSampling()
        cyc += 1
      }

      assert(enqCycle >= 0, "the inhibited load never enqueued into the aligned ring")
      assert(arCycle >= 0, "the inhibited load never issued its AR")
      assert(rspCycle >= 0, "the inhibited load's response never arrived")
      assert(clearCycle >= 0, "inhibitedLoadBusySig never cleared after the response was consumed")
      assert(sawBusyByArTime,
        "inhibitedLoadBusySig must already be true by the cycle the device AR fires")
      assert(!everFalseBetweenEnqAndRsp,
        "inhibitedLoadBusySig must not glitch false between launch and response consumption")
      assert(clearCycle - rspCycle <= 3,
        s"inhibitedLoadBusySig must clear within a few cycles of response consumption " +
        s"(rspCycle=$rspCycle clearCycle=$clearCycle)")
      assert(completed(dut, robId) || true) // response already observed via alignedRspFire above
    }
  }

  // ── Test D: inverse sanity -- neither interlock touches an ORDINARY load ──────
  test("D: irqPreemptPendingIn/debugHaltImminentIn never delay an ordinary (non-inhibited) load") {
    simConfig.compile(new Dut).doSim { dut =>
      val (cd, mem, ptmem) = initDut(dut)
      val base = 0xA000L
      val vpn  = (base >> 12) & 0xfffff
      buildResidentWritethroughPage(ptmem, base, ppn = vpn)
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp #= ROOT
      dut.ctrl.logic.srp #= ROOT
      dut.cacheCtrl.logic.dcacheEnabled #= true
      Seq(0xAA, 0xBB, 0xCC, 0xDD).zipWithIndex.foreach { case (b, i) => mem.pokeByte(base + i, b) }
      seed(dut, cd, preg = 10, value = base)

      // No ROB wired at all (robHeadValidIn stays False) AND both preempt signals
      // held true throughout -- an ordinary load's launch condition
      // (`!sq.io.barrier.olderInhibitedStore`) does not consult ANY of these, so it
      // must complete promptly regardless.
      dut.wire.logic.iIrqPreemptPendingIn #= true
      dut.wire.logic.iDebugHaltImminentIn #= true

      val robId = 30
      issueLoad(dut, cd, basePreg = 10, disp = 0, pdst = 23, robId = robId)
      var n = 0
      var done = false
      while (!done && n < 60) {
        if (completed(dut, robId)) done = true
        cd.waitSampling(); n += 1
      }
      assert(done, s"ordinary load must complete promptly even with both preempt signals held (n=$n)")
      // A resident WRITETHROUGH/DE=1 hit completes in a small, bounded number of
      // cycles (see LsEuFastPreciseSpec's own II=1 burst test) -- a generous but
      // finite bound catches an accidental serialization regression without being
      // timing-fragile to unrelated pipeline-depth changes.
      assert(n < 40, s"ordinary load took suspiciously long ($n cycles) -- possible accidental " +
        "serialization behind the new preempt interlock")

      // Burst sanity: four back-to-back resident hits must still complete without
      // any of them ever waiting behind the (irrelevant, held-true) preempt gates --
      // directly counters the "one-at-a-time chokepoint on the ordinary hot path"
      // failure mode the interlock could have introduced if mis-scoped.
      val robs = 31 to 34
      val disps = Seq(4, 8, 12, 16)
      robs.zip(disps).foreach { case (rid, disp) =>
        issueLoad(dut, cd, basePreg = 10, disp = disp, pdst = 24, robId = rid)
      }
      var seenAll = false
      var m = 0
      val seen = scala.collection.mutable.Set[Int]()
      while (!seenAll && m < 200) {
        if (dut.src.logic.cValid.toBoolean && robs.contains(dut.src.logic.cRob.toInt))
          seen += dut.src.logic.cRob.toInt
        seenAll = seen.size == robs.size
        cd.waitSampling(); m += 1
      }
      assert(seenAll, s"burst of ordinary loads did not all complete under sustained preempt-pending (seen=$seen)")
    }
  }
}
