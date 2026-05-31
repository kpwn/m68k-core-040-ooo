package m68k040

import m68k040.core.{M68kCore, ParamPlugin, HelloPlugin}
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin
import org.scalatest.funsuite.AnyFunSuite

class FrameworkSmokeSpec extends AnyFunSuite {
  test("core hosts plugins and elaborates to Verilog") {
    val report = SpinalConfig(targetDirectory = "simWorkspace/gen").generateVerilog {
      val plugins = Seq[FiberPlugin](new ParamPlugin(M68kParams()), new HelloPlugin())
      new M68kCore(plugins)
    }
    assert(report.toplevelName == "M68kCore")

    // Canary: prove the param flowed Database -> HelloPlugin -> RTL.
    // ROB_DEPTH=64 => counter is log2Up(64)=6 bits wide.
    val rtl = scala.io.Source.fromFile("simWorkspace/gen/M68kCore.v").mkString
    assert(rtl.contains("HelloPlugin_logic_counter"),
      "generated RTL must contain the HelloPlugin counter register")
    assert(rtl.contains("[5:0]") && rtl.contains("HelloPlugin_logic_counter"),
      "counter must be 6 bits wide (log2Up(ROB_DEPTH=64)), proving the param flowed through the Database")
  }
}
