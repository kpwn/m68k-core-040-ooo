package m68k040.socket

import m68k040.M68kSim
import m68k040.core.ParamPlugin
import m68k040.decode.{DecOp, SysKind}
import m68k040.isa.{Cluster, Size}
import m68k040.rename.RenamedUop
import m68k040.rob.{RobPlugin, RenameCommitSinkPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin}
import m68k040.M68kParams
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Socket port surface and RESET":
  *   "D22: cpu_peripheral_reset rises on RESET retirement and stays high for exactly 518
  *    core-clock cycles, then falls; back-to-back RESETs re-arm it without a glitch low."
  *
  * OPEN-1 is RESOLVED as Option A (output only) -- design spec section 14.1, decided by
  * explicit user sign-off during the implementation-plan pass. The surviving test below is
  * therefore "execution is unaffected by the hold": µops continue to retire while
  * `cpu_peripheral_reset` (the plugin's internal `level`) is asserted. The Option-B test
  * ("no µop retires during the hold") is deleted outright, not left `cancel`ed -- spec
  * section 13's own rule: "a test written against the unsigned-off assumption is worse than
  * none." */
class PeripheralResetSpec extends AnyFunSuite {

  /** The hold counter in isolation, with a short width so the test is quick; the RTL value
    * is 518 and is asserted separately as a constant. */
  // NOTE: the output is named `heldLevel`, not `level` -- `spinal.core.Component` itself
  // declares an inherited `def level: Int` (component nesting depth), so a field literally
  // named `level` type-checks its RHS against Component's OWN `Int` override signature
  // ("found: Bool, required: Int") rather than against `out Bool()`'s real type. Confirmed
  // via isolated repro against this tree's SpinalHDL 1.14.1 -- drift from the brief's sketch.
  class HoldDut(hold: Int) extends Component {
    val trigger   = in Bool ()
    val heldLevel = out Bool ()
    val cnt = Reg(UInt(log2Up(hold + 1) bits)) init 0
    when(trigger)          { cnt := U(hold, cnt.getWidth bits) }
      .elsewhen(cnt =/= 0) { cnt := cnt - 1 }
    heldLevel := cnt =/= 0
    cnt.simPublic()
  }

  test("D22's width is 518, copied from v1 and not re-derived") {
    // commit.v:2081-2101: `localparam integer RESET_INSTR_CYCLES = 518;` -- "MAME's 68040
    // model charges 518 clocks for RESET. Holding the core for the same interval also
    // exceeds the 68040 RSTO minimum of 124 clocks."
    assert(PeripheralResetPlugin.V1_HOLD_CYCLES == 518,
      s"got ${PeripheralResetPlugin.V1_HOLD_CYCLES}")
    assert(PeripheralResetPlugin.V1_HOLD_CYCLES != 512,
      "512 was the WITHDRAWN 'if v1 emits a bare pulse' fallback; v1 emits a 518-cycle LEVEL")
    assert(PeripheralResetPlugin.V1_HOLD_CYCLES > 124,
      "the 68040 RSTO minimum is 124 clocks")
  }

  test("the level rises on the trigger and stays high for exactly the hold") {
    val hold = 24
    SimConfig.compile(new HoldDut(hold)).doSim("hold", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.trigger #= false
      dut.clockDomain.waitSampling(4)
      assert(!dut.heldLevel.toBoolean, "level high before any RESET")
      dut.trigger #= true
      dut.clockDomain.waitSampling()
      dut.trigger #= false
      // A poke followed by a single waitSampling is NOT reliably visible to the register
      // that samples it on THIS harness (confirmed by direct instrumentation, and matching
      // the documented 2-edge poke latency in RobPluginSpec's "LUT-reduction B1" test
      // comment: "the poke needs TWO waitSampling edges to be visibly reflected") -- an
      // extra edge is needed before `cnt` has genuinely latched `hold`. Drift from the
      // brief's sketch, which assumed 1-edge visibility.
      dut.clockDomain.waitSampling()
      var high = 0
      while (dut.heldLevel.toBoolean && high < hold * 4) { dut.clockDomain.waitSampling(); high += 1 }
      assert(high == hold, s"level held for $high cycles, wanted exactly $hold")
    }
  }

  test("back-to-back RESETs re-arm the level without a glitch low") {
    val hold = 24
    SimConfig.compile(new HoldDut(hold)).doSim("rearm", seed = 2) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.trigger #= false
      dut.clockDomain.waitSampling(4)
      dut.trigger #= true; dut.clockDomain.waitSampling(); dut.trigger #= false
      dut.clockDomain.waitSampling(hold / 2)
      assert(dut.heldLevel.toBoolean, "level fell early")
      // A second RESET mid-hold.
      dut.trigger #= true; dut.clockDomain.waitSampling(); dut.trigger #= false
      var glitched = false
      for (_ <- 0 until hold) {
        if (!dut.heldLevel.toBoolean) glitched = true
        dut.clockDomain.waitSampling()
      }
      assert(!glitched, "the level glitched low while re-arming")
    }
  }

  // ── OPTION A (output only, OPEN-1 resolved -- design spec section 14.1) ────────────
  // A real ROB-hosting DUT: RenameUopSourcePlugin (fake rename source) + RobAllocDriverPlugin
  // (fake dispatch) + the real RobPlugin (which owns the real ExceptionUnit `exc`) +
  // RenameCommitSinkPlugin (observes retire) + the real PeripheralResetPlugin under test,
  // with gateDispatch = false per the signed-off answer. Same shape as RobPluginSpec's
  // SimpleDut / HaltReasonSpec's HaltDut.
  class ExecDut(holdCycles: Int) extends Component {
    val db    = new Database
    val host  = db on (new PluginHost)
    val rsrc  = new RenameUopSourcePlugin
    val drv   = new RobAllocDriverPlugin
    val rob   = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    val prp   = new PeripheralResetPlugin(enable = false, holdCycles = holdCycles, gateDispatch = false)
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink, prp)) }
  }

  /** Poke a RenamedUop slot with sane defaults -- mirrors RobPluginSpec's `pokeRu`, plus the
    * commit-time sysOp fields a RESET µop needs. */
  def pokeRu(u: RenamedUop, pc: Long = 0, dstArch: Int = 0,
             pdst: Int = 0, pdstValid: Boolean = false, pdstOld: Int = 0,
             sysOp: Boolean = false, sysKind: SpinalEnumElement[SysKind.type] = SysKind.NONE): Unit = {
    u.valid #= true
    u.pc #= pc
    u.nextPc #= pc + 2
    u.faultUsesNextPc #= false
    u.op #= DecOp.MOVE
    u.cluster #= Cluster.INT
    u.size #= Size.LONG
    u.useImm #= false; u.imm #= 0
    u.isBranch #= false; u.cond #= 0; u.branchDisp #= 0
    u.unimplemented #= false
    u.dstArch #= dstArch
    u.psrcA #= 0; u.psrcAValid #= false
    u.psrcB #= 0; u.psrcBValid #= false
    u.pdst #= pdst; u.pdstValid #= pdstValid; u.pdstOld #= pdstOld
    u.pNzvcSrc #= 0; u.readsNzvc #= false
    u.pNzvcDst #= 0; u.writesNzvc #= false; u.pNzvcOld #= 0
    u.pXSrc #= 0; u.readsX #= false
    u.pXDst #= 0; u.writesX #= false; u.pXOld #= 0
    u.faulted #= false; u.faultVector #= 0; u.isRte #= false
    u.debugBreakValid #= false; u.debugBreakSlot #= 0
    u.sysOp #= sysOp; u.sysKind #= sysKind; u.sysReadDir #= false
    u.needsSupervisor #= false
  }

  def initExec(dut: ExecDut, cd: ClockDomain): Unit = {
    dut.rsrc.logic.src.valid #= false
    dut.rsrc.logic.u1v #= false
    for (k <- 0 until 6) dut.rob.logic.completion(k).valid #= false
    for (k <- 0 until 4) dut.rob.logic.ccrCompletion(k).valid #= false
    dut.rob.logic.flush.valid #= false
    dut.rob.logic.coreHaltedIn #= false
    dut.rob.logic.haltReasonIn #= HaltReason.NONE
    cd.waitSampling(4)
  }

  def waitUntil(cd: ClockDomain, cond: => Boolean, max: Int = 400): Unit = {
    var n = 0
    while (!cond) { assert(n < max, "waitUntil timed out"); n += 1; cd.waitSampling() }
  }

  test("OPTION A: execution is unaffected by the hold -- a uop retires while cpu_peripheral_reset is held high") {
    val holdCycles = 60   // short so the test is quick; D22's actual width (518) is pinned separately
    M68kSim().compile(new ExecDut(holdCycles)).doSim("exec-unaffected", seed = 7) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      initExec(dut, cd)

      assert(!dut.prp.logic.level.toBoolean, "level asserted with no RESET ever retired")

      // ── Dispatch + retire a RESET sysOp ──────────────────────────────────────────
      val resetRobId = dut.rob.logic.tail.toInt
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x1000, sysOp = true, sysKind = SysKind.RESET)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 1, "RESET sysOp did not allocate")

      // sysRetire needs BOTH `completes(h0)` (completion port) and `sysValRdyStore(h0)`
      // (ccrCompletion port) -- RobPlugin.scala's `sysRetire` gate, identical for every
      // sysKind including RESET (it has no read/write value of its own, but the gate does
      // not distinguish).
      dut.rob.logic.completion(0).valid   #= true
      dut.rob.logic.completion(0).payload #= resetRobId
      dut.rob.logic.ccrCompletion(0).valid          #= true
      dut.rob.logic.ccrCompletion(0).payload.robId  #= resetRobId
      cd.waitSampling(); cd.waitSampling()
      dut.rob.logic.completion(0).valid   #= false
      dut.rob.logic.ccrCompletion(0).valid #= false

      // The RESET propagates through the exception FSM's S_APPLY -> S_REDIR and pulses
      // `resetInstrRetire`, which PeripheralResetPlugin latches into a 60-cycle hold.
      waitUntil(cd, dut.prp.logic.level.toBoolean,
        max = 40)
      assert(dut.prp.logic.level.toBoolean, "cpu_peripheral_reset never asserted after RESET retired")

      // `resetInstrRetire` (S_APPLY) fires BEFORE the ROB's own squash of that entry
      // (S_REDIR's `redirectValid` -> the registered `flushing` pulse that resets
      // tail/count) -- the hold rising is not the same instant as the ROB going empty.
      // Wait for the real squash to land before allocating the next uop, or its robId
      // guess (and the `count == 1` check below) would race the FSM.
      waitUntil(cd, dut.rob.logic.count.toInt == 0 && !dut.rob.logic.exc.active.toBoolean,
        max = 40)

      // ── While the hold is asserted, dispatch + retire an ORDINARY uop ────────────
      // gateDispatch=false means PeripheralResetPlugin never touches RobPlugin at all;
      // this proves it directly rather than by code inspection.
      val ordRobId = dut.rob.logic.tail.toInt
      pokeRu(dut.rsrc.logic.src.payload(0), pc = 0x2000, dstArch = 3,
             pdst = 20, pdstValid = true, pdstOld = 3)
      dut.rsrc.logic.src.valid #= true
      dut.rsrc.logic.u1v #= false
      cd.waitSamplingWhere(dut.rsrc.logic.src.ready.toBoolean)
      dut.rsrc.logic.src.valid #= false
      cd.waitSampling()
      assert(dut.rob.logic.count.toInt == 1, "ordinary uop did not allocate right after the RESET")
      assert(dut.prp.logic.level.toBoolean, "hold decayed before the follow-on uop could even allocate")

      dut.rob.logic.completion(0).valid   #= true
      dut.rob.logic.completion(0).payload #= ordRobId

      var sawCommit = false
      var levelWhenCommitted = false
      var archWhenCommitted = -1
      for (_ <- 0 until 20) {
        if (dut.csink.logic.commitValidOut(0).toBoolean && !sawCommit) {
          sawCommit = true
          levelWhenCommitted = dut.prp.logic.level.toBoolean
          archWhenCommitted = dut.csink.logic.commitArchOut(0).toInt
        }
        cd.waitSampling()
        if (sawCommit) {
          dut.rob.logic.completion(0).valid #= false
        }
      }
      assert(sawCommit, "the ordinary uop never retired at all while cpu_peripheral_reset was held")
      assert(archWhenCommitted == 3, s"wrong entry retired: archRegId=$archWhenCommitted, expected 3")
      assert(levelWhenCommitted, "the ordinary uop retired but cpu_peripheral_reset had already fallen -- " +
        "the hold window (60 cycles) was too short for this test's own timing, not a real bug; widen it")
    }
  }
}
