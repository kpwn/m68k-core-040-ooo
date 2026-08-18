package m68k040.socket

import m68k040.M68kSim
import m68k040.core.ParamPlugin
import m68k040.rob.{RobPlugin, RenameCommitSinkPlugin, RenameUopSourcePlugin, RobAllocDriverPlugin}
import m68k040.M68kParams
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** Spec section 13, "Halt-reason channel (section 6.4, D28)":
  *   - "Each of the four producers ... latches its own distinct reason."
  *   - "First-wins: a second halt cause after the first does not overwrite the
  *      recorded reason."
  *   - "coreHalted's existing control behaviour is bit-identical to before the
  *      widening (regression, not a new property)."
  *
  * The producers themselves land in Tasks 5 and 9; what this suite pins is the
  * CHANNEL -- distinctness, stickiness, first-wins, and the untouched latch. */
class HaltReasonSpec extends AnyFunSuite {

  /** Minimal ROB-only host: enough for `coreHaltedIn`/`haltReasonIn` to be pokable,
    * nothing else. Same shape as the ROB's own directed specs (`RobPluginSpec`'s
    * `SimpleDut` / `RobTestHelpers.scala`): `RobPlugin` PROVIDES CommitTraceService,
    * RobAllocService, RedirectService, BtbUpdateService, GshareUpdateService,
    * PrivilegeService, CacheControlService and FrontendQuiesceService itself (its own
    * class declaration says so), so the only external dependency to satisfy is the
    * ONE service it *consumes* -- `host[RenameCommitService]` (`RobPlugin.scala:133`)
    * -- via the existing test-only `RenameCommitSinkPlugin`. Deviation from the
    * brief's literal `M68kCore(Seq(ParamPlugin, RobPlugin))`, which does not build
    * (`RenameCommitService` unresolved) -- see task-3-report.md. */
  class HaltDut extends Component {
    val db   = new Database
    val host = db on (new PluginHost)
    val rsrc  = new RenameUopSourcePlugin
    val drv   = new RobAllocDriverPlugin
    val rob   = new RobPlugin
    val csink = new RenameCommitSinkPlugin
    db.on { host.asHostOf(Seq[FiberPlugin](
      new ParamPlugin(M68kParams()), rsrc, drv, rob, csink)) }
  }

  private def rob(dut: HaltDut): RobPlugin = dut.rob

  test("reason codes are distinct, dense from zero, and fit the declared width") {
    val codes = Seq(HaltReason.NONE, HaltReason.DCACHE_DIAG, HaltReason.FS_XLATE,
                    HaltReason.RESET_VECTOR, HaltReason.ARBITER_WEDGE)
    assert(codes.distinct == codes, s"duplicate reason code in $codes")
    assert(codes == codes.indices.toList, s"codes must be dense from 0, got $codes")
    assert(codes.forall(c => c >= 0 && c < (1 << HaltReason.W)),
      s"a code does not fit ${HaltReason.W} bits")
    assert(codes.map(HaltReason.name).distinct.length == codes.length,
      "two codes share a name")
  }

  test("an idle core reports reason NONE and is not halted") {
    M68kSim().compile(new HaltDut).doSim("idle", seed = 1) { dut =>
      val r = rob(dut)
      dut.clockDomain.forkStimulus(10)
      r.logic.coreHaltedIn #= false
      r.logic.haltReasonIn #= HaltReason.NONE
      dut.clockDomain.waitSampling(8)
      assert(!r.logic.coreHalted.toBoolean, "halted with no producer")
      assert(r.logic.haltReason.toInt == HaltReason.NONE, "reason set with no producer")
    }
  }

  test("each producer latches its own distinct reason, and it is sticky") {
    for (code <- Seq(HaltReason.DCACHE_DIAG, HaltReason.FS_XLATE,
                     HaltReason.RESET_VECTOR, HaltReason.ARBITER_WEDGE)) {
      M68kSim().compile(new HaltDut).doSim(s"latch-$code", seed = 2) { dut =>
        val r = rob(dut)
        dut.clockDomain.forkStimulus(10)
        r.logic.coreHaltedIn #= false
        r.logic.haltReasonIn #= HaltReason.NONE
        dut.clockDomain.waitSampling(4)
        r.logic.coreHaltedIn #= true
        r.logic.haltReasonIn #= code
        dut.clockDomain.waitSampling()
        r.logic.coreHaltedIn #= false
        r.logic.haltReasonIn #= HaltReason.NONE
        dut.clockDomain.waitSampling(2)
        assert(r.logic.coreHalted.toBoolean, s"coreHalted not latched for reason $code")
        assert(r.logic.haltReason.toInt == code,
          s"reason ${r.logic.haltReason.toInt} latched, expected $code")
        // Sticky: still there many cycles later, with the producer long gone.
        dut.clockDomain.waitSampling(20)
        assert(r.logic.coreHalted.toBoolean, "coreHalted lost its stickiness")
        assert(r.logic.haltReason.toInt == code, "reason lost its stickiness")
      }
    }
  }

  test("first-wins: a second cause does not overwrite the recorded reason") {
    M68kSim().compile(new HaltDut).doSim("first-wins", seed = 3) { dut =>
      val r = rob(dut)
      dut.clockDomain.forkStimulus(10)
      r.logic.coreHaltedIn #= false
      r.logic.haltReasonIn #= HaltReason.NONE
      dut.clockDomain.waitSampling(4)
      r.logic.coreHaltedIn #= true
      r.logic.haltReasonIn #= HaltReason.RESET_VECTOR
      dut.clockDomain.waitSampling()
      // A DIFFERENT producer fires later. The FIRST cause is what an operator needs.
      r.logic.haltReasonIn #= HaltReason.ARBITER_WEDGE
      dut.clockDomain.waitSampling(6)
      r.logic.coreHaltedIn #= false
      dut.clockDomain.waitSampling(2)
      assert(r.logic.haltReason.toInt == HaltReason.RESET_VECTOR,
        s"first-wins violated: got ${r.logic.haltReason.toInt}")
    }
  }

  test("a halt whose producer supplies NONE still halts, and records NONE") {
    // Regression guard for the widening itself: coreHaltedIn's behaviour must not
    // become conditional on the reason being non-zero. A legacy driver that only
    // knows about the Bool must still halt the core.
    M68kSim().compile(new HaltDut).doSim("legacy-driver", seed = 4) { dut =>
      val r = rob(dut)
      dut.clockDomain.forkStimulus(10)
      r.logic.coreHaltedIn #= false
      r.logic.haltReasonIn #= HaltReason.NONE
      dut.clockDomain.waitSampling(4)
      r.logic.coreHaltedIn #= true
      dut.clockDomain.waitSampling(2)
      r.logic.coreHaltedIn #= false
      dut.clockDomain.waitSampling(2)
      assert(r.logic.coreHalted.toBoolean,
        "widening the seam made the halt itself conditional on a non-zero reason")
      assert(r.logic.haltReason.toInt == HaltReason.NONE)
    }
  }
}
