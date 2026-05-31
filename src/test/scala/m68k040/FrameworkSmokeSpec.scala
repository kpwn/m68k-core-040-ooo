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
  }
}
