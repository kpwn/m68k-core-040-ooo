package m68k040.debug

import m68k040.{M68kParams, M68kSpinalConfig}
import m68k040.core.ParamPlugin
import spinal.core._
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

  test("VioProbePlugin(enable=true) elaborates without host[RobPlugin]/host[CommitTraceService] crashing the skeleton") {
    // Deliberately does NOT provide RobPlugin/CommitTraceService -- Task 1's skeleton must not
    // reach into those services yet (that is Task 2). If this test needs a RobPlugin/CommitTraceService
    // stub to pass, the skeleton is already doing Task 2's work early; narrow it back down.
    val report = M68kSpinalConfig(targetDirectory = "simWorkspace/gen")
      .generateVerilog(new Dut(enable = true))
    assert(report != null)
  }
}
