package m68k040.debug

import m68k040.{M68kParams, M68kSim, M68kSpinalConfig}
import m68k040.core.ParamPlugin
import m68k040.decode.{DecOp, SysKind}
import m68k040.frontend.{DecodeFeedProbePlugin, FetchAlignPlugin}
import m68k040.isa.{Cluster, Size}
import m68k040.rename.RenamedUop
import m68k040.rob.{RenameCommitSinkPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin, RobPlugin}
import m68k040.services.FetchService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 2 (V2-V6): the plugin exists, is constructible, and enable=false elaborates
  * to nothing. No probe logic is tested here -- that is Tasks 2-3's job.
  *
  * Follows the standalone-PluginHost-in-a-Component pattern established by
  * `DebugCtrlDut`/`RobPluginSpec.SimpleDut` (own `Database`+`PluginHost`, plugins wired via
  * `host.asHostOf`), NOT the `M68kSim().compile { ... }` shape from the task brief's original
  * sketch -- that sketch isn't this codebase's real pattern (`M68kSim().compile` takes an
  * already-built `Component`, not an elaboration block, and spinning up a simulator is
  * unnecessary and far more expensive than plain `generateVerilog` when all that's being
  * proven is that construction/placement/port-surface are clean). */
class VioProbePluginSpec extends AnyFunSuite {

  class Dut(enable: Boolean) extends Component {
    val db     = new Database
    val host   = db on (new PluginHost)
    val plugin = new VioProbePlugin(enable = enable)
    db.on { host.asHostOf(Seq[FiberPlugin](new ParamPlugin(M68kParams()), plugin)) }
  }

  test("VioProbePlugin(enable=false) elaborates with no ports") {
    val report = M68kSpinalConfig(targetDirectory = "simWorkspace/gen")
      .generateVerilog(new Dut(enable = false))
    assert(report != null)
    // Elaboration succeeding at all, with no exception, is most of the test: a plugin that
    // tries to declare a port unconditionally would fail to elaborate as a standalone
    // component with no top-level IO declared for it. Additionally confirm the generated
    // RTL carries no vio_-prefixed signal (there is no probe logic yet, so this is trivially
    // true today, but pins the enable=false zero-port-surface contract going forward).
    val rtl = scala.io.Source.fromFile(s"simWorkspace/gen/${report.toplevelName}.v").mkString
    assert(!rtl.contains("vio_"), "enable=false must add zero port surface")
  }

  // ── Task 2: the probe_in bundle -- coherent snapshot, heartbeat, build-ID (V8-V15) ──────
  //
  // `VioProbePlugin(enable=true)` now genuinely consumes `host[RobPlugin]`,
  // `host[CommitTraceService]`, `host[FrontendQuiesceService]` and
  // `host[FetchAlignPlugin]`, so (per Task 1's own report note) the old
  // "enable=true elaborates standalone with no RobPlugin/CommitTraceService" test no
  // longer describes a real, useful contract -- replaced below by `VioDut`, which hosts
  // a REAL `RobPlugin` (via the same `RenameUopSourcePlugin` + `RobAllocDriverPlugin` +
  // `RenameCommitSinkPlugin` rig `RobPluginSpec.SimpleDut`/`HaltReasonSpec.HaltDut` use)
  // plus a real `FetchAlignPlugin` (fed by a minimal test-only `FetchService` stub, since
  // `host[FetchAlignPlugin]` is a concrete-class lookup, not a trait -- no service
  // substitute is possible) and `VioProbePlugin(enable=true)` itself.
  //
  // `CommitTraceService.trace`/`.traceFire` are RobPlugin-internal combinational signals
  // driven by real retire logic (RobPlugin.scala's `traceVec`/`traceFireVec`), not
  // sim-pokable inputs -- unlike `HaltReasonSpec`'s `coreHaltedIn`, there is no test seam
  // to force a "retire" directly. So the PC-latch/counter properties are proven by
  // driving REAL dual retires through the ROB, exactly like `RobPluginSpec`'s own
  // "alloc + 2-wide retire" test.

  /** Test-only: minimal `FetchService` PROVIDER so `FetchAlignPlugin` can elaborate
    * standalone without a real `IcachePlugin`. Idle-defaulted (never accepts a fetch,
    * never returns a response) -- `VioDut` only needs `FetchAlignPlugin` instantiated far
    * enough to expose its `started` Reg through `host[FetchAlignPlugin].logic.started`;
    * no real fetch traffic is exercised by any test in this file. */
  class FetchServiceStubPlugin extends FiberPlugin with FetchService {
    val logic = during build new Area {
      val cmdPort = Stream(m68k040.cache.FetchCmd())
      val rspPort = Flow(m68k040.cache.FetchRsp())
      cmdPort.ready := False
      rspPort.valid := False
      rspPort.payload.assignDontCare()
      // Task 3's boot-injector test reads `cmdPort.valid`/`.payload.pc` as the
      // "resulting I-cache command address" observation point -- unlike a poke (`#=`),
      // a whitebox READ (`.toBoolean`/`.toBigInt`) needs `.simPublic()` even on a
      // signal with real fanout.
      cmdPort.valid.simPublic(); cmdPort.payload.pc.simPublic()
    }
    override def cmd: Stream[m68k040.cache.FetchCmd] = logic.cmdPort
    override def rsp: Flow[m68k040.cache.FetchRsp]   = logic.rspPort
  }

  class VioDut(buildId: BigInt = BigInt(0xCAFEBABEL)) extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val rsrc  = new RenameUopSourcePlugin
    val drv   = new RobAllocDriverPlugin
    val rob   = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val fic   = new FetchServiceStubPlugin
    val fa    = new FetchAlignPlugin()
    // FetchAlignPlugin's `feed` is a plain directionless Stream (producer drives
    // valid/payload, consumer drives ready) -- with no decode-stage consumer in this
    // DUT, `feed.ready` is otherwise left undriven. `DecodeFeedProbePlugin` (the same
    // consumer stub `FetchAlignSpec.scala` uses) supplies that driver via a top-level
    // `feedOut` master port the test holds not-ready.
    val probe = new DecodeFeedProbePlugin
    val vio   = new VioProbePlugin(enable = true, buildId = buildId)
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, fic, fa, probe, vio)) }
  }

  test("VioProbePlugin(enable=true) elaborates as a real RobPlugin+FetchAlignPlugin host") {
    val report = M68kSpinalConfig(targetDirectory = "simWorkspace/gen")
      .generateVerilog(new VioDut())
    assert(report != null)
  }

  /** Poke a `RenamedUop` slot with sane defaults -- copied from
    * `RobPluginSpec.pokeRu` (same shape, same purpose: dispatch a MOVE-shaped uop
    * straight into the ROB, no decode/rename needed). */
  private def pokeRu(u: RenamedUop, pc: Long, dstArch: Int, pdst: Int, pdstOld: Int): Unit = {
    u.valid #= true
    u.pc #= pc
    u.lenWords #= 1   // 2-byte instruction model: commit pc = nextPc = pc + 2
    u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= false; u.cond #= 0
    u.unimplemented #= false
    u.dstArch #= dstArch
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= pdst; u.pdstValid #= true; u.pdstOld #= pdstOld
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.debugBreakValid #= false; u.debugBreakSlot #= 0
    u.sysOp #= false; u.sysKind #= SysKind.NONE; u.sysReadDir #= false
    u.needsSupervisor #= false
  }

  private def settle(dut: VioDut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    dut.rob.logic.completion(0).valid #= false
    dut.rob.logic.completion(1).valid #= false
    dut.rob.logic.flush.valid #= false
    dut.rob.logic.coreHaltedIn #= false
    dut.fa.logic.redirect.valid #= false
    dut.fa.logic.resume.valid #= false
    dut.probe.logic.feedOut.ready #= false
    cd.waitSampling(2)
  }

  /** Dispatch a 2-wide alloc (robId 0, 1) and drive both to complete + retire in the
    * same cycle -- exactly `RobPluginSpec`'s "alloc + 2-wide retire" flow. Returns once
    * the dual-retire cycle has been observed. */
  private def dualRetire(dut: VioDut, cd: ClockDomain, pc0: Long, pc1: Long): Unit = {
    pokeRu(dut.rsrc.logic.src.payload(0), pc = pc0, dstArch = 3, pdst = 20, pdstOld = 3)
    pokeRu(dut.rsrc.logic.src.payload(1), pc = pc1, dstArch = 5, pdst = 21, pdstOld = 5)
    dut.rsrc.logic.src.valid #= true
    dut.rsrc.logic.u1v #= true
    cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    cd.waitSampling()

    // Completion is a single Flow port: mark robId 1 first (head=0 still incomplete,
    // so no retire yet), then robId 0 -> both land complete -> 2-wide retire.
    dut.rob.logic.completion(0).valid #= true
    dut.rob.logic.completion(0).payload #= 1
    cd.waitSampling()
    dut.rob.logic.completion(0).payload #= 0
    cd.waitSampling()
    dut.rob.logic.completion(0).valid #= false

    cd.waitSamplingWhere((dut.vio.logic.vioCpuSnapshot.toBigInt & 0xFFFFFFFFL) == 2)
  }

  test("vio_cpu_snapshot: PC latch takes slot 1 over slot 0 on a dual retire") {
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)
      dualRetire(dut, cd, pc0 = 0x100, pc1 = 0x200)
      // commit pc = nextPc = pc + 2, so slot0 -> 0x102, slot1 -> 0x202.
      val snap = dut.vio.logic.vioCpuSnapshot.toBigInt
      val lastCommitPc = (snap >> 32) & BigInt("FFFFFFFF", 16)
      assert(lastCommitPc == 0x202,
        s"expected slot1's pc 0x202, got 0x${lastCommitPc.toString(16)} " +
        s"(V12 slot-priority violated -- slot0's 0x102 must lose)")
    }
  }

  test("vio_cpu_snapshot: commit_pc_valid is false until first retire, true thereafter") {
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)
      val snapBefore = dut.vio.logic.vioCpuSnapshot.toBigInt
      assert(((snapBefore >> 64) & 1) == 0, "bit 64 (commit_pc_valid) set before any retire")

      dualRetire(dut, cd, pc0 = 0x300, pc1 = 0x400)
      val snapAfter = dut.vio.logic.vioCpuSnapshot.toBigInt
      assert(((snapAfter >> 64) & 1) == 1, "bit 64 (commit_pc_valid) not set after a retire")
    }
  }

  test("vio_cpu_snapshot: qualifies on traceFire, never on trace(k).fire's x-default") {
    // An idle ROB (no allocations, no retires) drives traceFireVec(k) := False every
    // cycle from its own concrete idle default -- exactly the V11 property under test.
    // If the PC latch were wrongly qualified on `trace(k).fire` (X-defaulted between
    // retires) instead of `traceFire(k)`, this would either latch garbage nondeterministically
    // or the simulator would surface an X-propagation issue; qualifying correctly means
    // last_commit_pc and commit_pc_valid simply never move across many idle cycles.
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)
      val snap0 = dut.vio.logic.vioCpuSnapshot.toBigInt
      assert(((snap0 >> 64) & 1) == 0, "commit_pc_valid set on an idle ROB before any retire")
      for (_ <- 0 until 40) {
        cd.waitSampling()
        val snap = dut.vio.logic.vioCpuSnapshot.toBigInt
        assert(((snap >> 64) & 1) == 0,
          "commit_pc_valid moved on an idle ROB -- V11 qualification is wrong")
        assert(snap == snap0,
          "vio_cpu_snapshot changed on an idle ROB -- V11 qualification is wrong")
      }
    }
  }

  test("vio_cpu_snapshot: reserved bits [95:73] and [69:66] read zero") {
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)
      def checkReserved(): Unit = {
        val snap = dut.vio.logic.vioCpuSnapshot.toBigInt
        val reservedHigh = snap >> 73          // bits [95:73]
        val reservedHaltReason = (snap >> 66) & 0xF   // bits [69:66]
        assert(reservedHigh == 0, s"reserved bits [95:73] not zero: 0x${reservedHigh.toString(16)}")
        assert(reservedHaltReason == 0, s"reserved bits [69:66] not zero: 0x${reservedHaltReason.toString(16)}")
      }
      checkReserved()
      dualRetire(dut, cd, pc0 = 0x500, pc1 = 0x600)
      dut.rob.logic.coreHaltedIn #= true
      cd.waitSampling(3)
      dut.rob.logic.coreHaltedIn #= false
      cd.waitSampling(2)
      checkReserved()
    }
  }

  test("vio_cpu_snapshot: halted/stopped/frontend_quiesce/fetch_started track their sources") {
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)

      def bit(n: Int): Boolean = ((dut.vio.logic.vioCpuSnapshot.toBigInt >> n) & 1) == 1

      // Idle: nothing set yet.
      assert(!bit(65) && !bit(70) && !bit(71) && !bit(72), "a source bit is set before it is forced")

      // coreHalted (bit 65) -- forced via coreHaltedIn, and STICKY: stays set even once
      // the forcing condition withdraws (RobPlugin.scala: "!coreHalted is the first-wins
      // guard", coreHalted never clears itself once latched).
      dut.rob.logic.coreHaltedIn #= true
      cd.waitSampling(2)
      assert(bit(65), "halted bit did not track coreHaltedIn")
      assert(bit(71), "frontend_quiesce bit did not track coreHalted (stopped||coreHalted)")
      dut.rob.logic.coreHaltedIn #= false
      cd.waitSampling(5)
      assert(bit(65), "halted bit lost its stickiness after coreHaltedIn withdrew")

      // stopped (bit 70) -- forced directly on the Reg, same seam RobPluginSpec/
      // HaltReasonSpec use (`dut.rob.logic.stopped #= true`); self-sustaining because
      // `stoppedNext := (stopped || stopEnter) && !interruptPending` reads the poked
      // value forward into the very next edge.
      dut.rob.logic.stopped #= true
      cd.waitSampling(2)
      assert(bit(70), "stopped bit did not track RobPlugin's stopped")
      assert(bit(71), "frontend_quiesce bit did not track stopped")
      dut.rob.logic.stopped #= false
      cd.waitSampling(2)

      // fetch_started (bit 72) -- forced via FetchAlignPlugin's real `redirect` input
      // port, which sets `started := True` (sticky: no path ever clears it again).
      assert(!bit(72), "fetch_started set before any redirect")
      dut.fa.logic.redirect.valid #= true
      dut.fa.logic.redirect.payload #= 0x1000L
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= false
      cd.waitSampling(2)
      assert(bit(72), "fetch_started bit did not track FetchAlignPlugin.started")
    }
  }

  test("vio_heartbeat: advances every cycle even while core reset is asserted") {
    // V15. `dut.clockDomain.assertReset()` held across a `waitSampling` hangs the
    // simulator in this codebase (DebugCtrlResetSpec's standing DEVIATION note) --
    // use `sleep(ns)` instead while reset is held.
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)
      cd.assertReset()
      sleep(20)
      val h0 = dut.vio.logic.vioHeartbeat.toBigInt
      sleep(50)
      val h1 = dut.vio.logic.vioHeartbeat.toBigInt
      cd.deassertReset()
      assert(h1 != h0,
        s"vio_heartbeat did not advance while core reset was held (h0=$h0, h1=$h1) -- " +
        "V15's whole purpose is answering \"is the clock running\" during reset")
    }
  }

  test("vio_heartbeat: unaffected by coreHalted") {
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)
      dut.rob.logic.coreHaltedIn #= true
      cd.waitSampling(3)
      val h0 = dut.vio.logic.vioHeartbeat.toBigInt
      cd.waitSampling(10)
      val h1 = dut.vio.logic.vioHeartbeat.toBigInt
      assert(h1 == h0 + 10, s"vio_heartbeat did not advance normally under coreHalted (h0=$h0, h1=$h1)")
    }
  }

  test("vio_build_id: reflects the constructor's buildId parameter") {
    val expected = BigInt("DEADBEEF", 16)
    M68kSim().compile(new VioDut(buildId = expected)).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)
      assert(dut.vio.logic.vioBuildId.toBigInt == expected,
        s"vio_build_id=0x${dut.vio.logic.vioBuildId.toBigInt.toString(16)}, expected 0x${expected.toString(16)}")
    }
  }

  // ── Task 3: the probe_out group -- mandatory edge discipline + boot-PC injector ─────────
  // (V16-V20). `VioDut` already hosts a real `FetchAlignPlugin` (Task 2's own rig), so no
  // new DUT is needed -- these tests drive `dut.vio.logic.vioCtl`/`.vioBootPc` and observe
  // `dut.fa.logic.*` and `dut.fic.logic.cmdPort` (the stub I-cache's command port, the
  // "resulting I-cache command address" the brief's test list asks for) directly.

  test("probe_out edge discipline: vio_ctl[0] held high across reset produces NO goPulse (V16)") {
    // THE single most important test in this whole plan (spec section 11's own words) --
    // this exact failure mode has hit the real bench TWICE. `vio_ctl(0)` sitting high
    // across a dropped JTAG link must not manufacture a spurious edge the moment reset
    // deasserts.
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)

      // Drive vio_ctl(0) high BEFORE asserting core reset -- exactly the "probe left high
      // across a dropped link" scenario.
      dut.vio.logic.vioCtl #= 1
      cd.waitSampling()

      // NEVER waitSampling while reset is held (this project's own documented SpinalSim
      // deadlock gotcha, re-affirmed by this very file's V15 heartbeat test) -- use sleep.
      cd.assertReset()
      sleep(20)
      cd.deassertReset()

      // Sample many cycles across and after the reset deassertion: goPulse must NEVER
      // fire merely because vio_ctl(0) was already high when reset dropped. A
      // RegInit(False) history register would fire exactly here, on the very first
      // post-reset cycle.
      for (_ <- 0 until 20) {
        assert(!dut.vio.logic.goPulse.toBoolean,
          "goPulse fired across/after a reset with vio_ctl(0) already held high -- V16 violated")
        cd.waitSampling()
      }

      // Still holding vio_ctl(0) high the whole time, toggle it low then high again:
      // assert exactly ONE pulse on that genuine transition.
      dut.vio.logic.vioCtl #= 0
      cd.waitSampling(2)
      assert(!dut.vio.logic.goPulse.toBoolean, "goPulse fired on a falling edge")
      dut.vio.logic.vioCtl #= 1
      cd.waitSampling()
      assert(dut.vio.logic.goPulse.toBoolean,
        "goPulse did not fire on a genuine low-to-high transition after a real toggle")
      cd.waitSampling()
      assert(!dut.vio.logic.goPulse.toBoolean,
        "goPulse stayed asserted past a single cycle -- not edge-converted")
    }
  }

  test("boot injector: goPulse with vio_boot_pc=P redirects fetch to P, none before it") {
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)

      // Precondition (FetchAlignPlugin's own reset state): nothing has redirected fetch
      // yet in this DUT, so `fetch_started` (vio_cpu_snapshot bit 72 -- the same
      // simPublic-exposed proxy the pre-existing "halted/stopped/.../fetch_started"
      // test already reads; `FetchAlignPlugin.logic.started` itself has no
      // `.simPublic()` and is out of this task's file scope to add one for) is false,
      // and no I-cache command is outstanding.
      def fetchStarted(): Boolean = ((dut.vio.logic.vioCpuSnapshot.toBigInt >> 72) & 1) == 1
      assert(!fetchStarted(), "fetch_started already true before any redirect")
      assert(!dut.fic.logic.cmdPort.valid.toBoolean, "an I-cache command was issued before any redirect")

      // 8-byte-aligned target, so fetchPc (the 8-aligned window base that becomes the
      // I-cache command address) equals vio_boot_pc exactly -- no alignment-arithmetic
      // ambiguity in the assertion below.
      val bootPc = 0x4000L
      dut.vio.logic.vioBootPc #= bootPc
      dut.vio.logic.vioCtl #= 1   // 0 -> 1 transition: exactly one goPulse
      cd.waitSampling()
      assert(dut.vio.logic.goPulse.toBoolean, "goPulse did not fire on the rising edge")
      cd.waitSampling()

      assert(fetchStarted(), "fetch_started not set after the injector fired")
      assert(dut.fic.logic.cmdPort.valid.toBoolean, "no I-cache command outstanding after the injector fired")
      assert(dut.fic.logic.cmdPort.payload.pc.toBigInt == BigInt(bootPc),
        s"I-cache command address = 0x${dut.fic.logic.cmdPort.payload.pc.toBigInt.toString(16)}, " +
        s"expected 0x${BigInt(bootPc).toString(16)}")
    }
  }

  test("V18: goPulse on a HALTED core produces no retire") {
    // Asserting the ABSENCE of resurrection is what stops someone shipping the limit as
    // an accidental feature (spec section 11's own framing). No new RTL should be needed
    // for this to pass -- `RobPlugin.logic.headReady` already reads `!coreHalted`
    // independent of any fetch redirect source (confirmed by direct read of
    // RobPlugin.scala before writing this test).
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)

      // Latch coreHalted (sticky: RobPlugin.scala's "!coreHalted is the first-wins guard").
      dut.rob.logic.coreHaltedIn #= true
      cd.waitSampling(3)
      dut.rob.logic.coreHaltedIn #= false
      cd.waitSampling(2)
      assert(((dut.vio.logic.vioCpuSnapshot.toBigInt >> 65) & 1) == 1,
        "halted bit not latched before firing goPulse -- test precondition broken")

      val retireCountBefore = dut.vio.logic.vioCpuSnapshot.toBigInt & 0xFFFFFFFFL

      // Fire the injector. Fetch is allowed to restart (started tracks the redirect
      // regardless of halt -- FetchAlignPlugin's own quiesce gate, driven by
      // stopped||coreHalted, separately blocks the I-cache command itself); what must
      // NOT happen is any instruction retiring off the back of it.
      dut.vio.logic.vioBootPc #= 0x8000L
      dut.vio.logic.vioCtl #= 1
      cd.waitSampling()
      assert(dut.vio.logic.goPulse.toBoolean, "goPulse did not fire")
      cd.waitSampling()
      assert(((dut.vio.logic.vioCpuSnapshot.toBigInt >> 72) & 1) == 1,
        "fetch_started did not track the injector's redirect even under halt")

      // Attempt a genuine dual-alloc-and-complete sequence through the ROB -- the same
      // shape as `dualRetire`, but WITHOUT waiting on the dual-retire condition:
      // `headReady`'s `!coreHalted` gate should make that condition never become true,
      // so waiting on it would hang the test.
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x900, dstArch = 3, pdst = 20, pdstOld = 3)
      pokeRu(dut.rsrc.logic.src.payload(1), pc = 0xA00, dstArch = 5, pdst = 21, pdstOld = 5)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= true
      cd.waitSampling(3)
      dut.rsrc.logic.src.valid #= false
      dut.rsrc.logic.u1v #= false
      dut.rob.logic.completion(0).valid #= true
      dut.rob.logic.completion(0).payload #= 1
      cd.waitSampling()
      dut.rob.logic.completion(0).payload #= 0
      cd.waitSampling()
      dut.rob.logic.completion(0).valid #= false

      cd.waitSampling(30)
      val retireCountAfter = dut.vio.logic.vioCpuSnapshot.toBigInt & 0xFFFFFFFFL
      assert(retireCountAfter == retireCountBefore,
        s"retire_count changed under coreHalted despite the injector firing " +
        s"(before=$retireCountBefore, after=$retireCountAfter) -- V18 violated")
    }
  }

  test("vio_ctl bits [3:1] are reserved: driving them has no observable effect") {
    M68kSim().compile(new VioDut()).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      settle(dut, cd)

      val snapBefore = dut.vio.logic.vioCpuSnapshot.toBigInt

      // Drive every reserved bit high while bit 0 (boot_go) stays low -- no goPulse, no
      // redirect, no observable change anywhere (V20). `vio_cpu_snapshot` equality
      // already subsumes `fetch_started` (bit 72), so no separate check is needed.
      dut.vio.logic.vioCtl #= 0xE   // 0b1110: bits [3:1] high, bit [0] low
      for (_ <- 0 until 10) {
        cd.waitSampling()
        assert(!dut.vio.logic.goPulse.toBoolean, "goPulse fired from a reserved bit, not bit 0")
        assert(dut.vio.logic.vioCpuSnapshot.toBigInt == snapBefore,
          "vio_cpu_snapshot changed from a reserved vio_ctl bit")
      }

      // Toggling them further, still with bit 0 low, still produces nothing.
      dut.vio.logic.vioCtl #= 0x0
      cd.waitSampling(2)
      dut.vio.logic.vioCtl #= 0xA   // 0b1010
      cd.waitSampling(3)
      assert(!dut.vio.logic.goPulse.toBoolean, "goPulse fired from toggling reserved bits")
    }
  }
}
