package m68k040

import m68k040.core.{M68kCore, ParamPlugin, HelloPlugin}
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin
import org.scalatest.funsuite.AnyFunSuite

class FrameworkSmokeSpec extends AnyFunSuite {
  test("core hosts plugins and elaborates to Verilog") {
    val params = M68kParams()
    val report = SpinalConfig(targetDirectory = "simWorkspace/gen").generateVerilog {
      val plugins = Seq[FiberPlugin](new ParamPlugin(params), new HelloPlugin())
      new M68kCore(plugins)
    }
    assert(report.toplevelName == "M68kCore")

    // Canary: prove the param flowed Database -> HelloPlugin -> RTL.
    // The canary follows the configured ROB depth, including ROB-32 builds.
    val width = log2Up(params.robDepth)
    val rtl = scala.util.Using.resource(scala.io.Source.fromFile("simWorkspace/gen/M68kCore.v"))(_.mkString)
    assert(rtl.contains("HelloPlugin_logic_counter"),
      "generated RTL must contain the HelloPlugin counter register")
    val declaration = (s"reg\\s+\\[${width - 1}:0\\]\\s+HelloPlugin_logic_counter\\s*;").r
    assert(declaration.findFirstIn(rtl).nonEmpty,
      s"counter must be $width bits wide (log2Up(ROB_DEPTH=${params.robDepth})), proving the param flowed through the Database")
  }
}
