package m68k040.cache

import m68k040.M68kParams
import m68k040.core.ParamPlugin
import m68k040.mmu.IdentityTranslationPlugin
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin
import org.scalatest.funsuite.AnyFunSuite

class IcacheElaborateSpec extends AnyFunSuite {
  test("I-cache + identity translation elaborate inside a plugin host") {
    val report = SpinalConfig(targetDirectory = "simWorkspace/gen").generateVerilog {
      IcacheTop(Seq[FiberPlugin](
        new ParamPlugin(M68kParams()), new IdentityTranslationPlugin, new IcachePlugin,
        new FetchProbePlugin))   // provides a cmd producer for the plain service Stream
    }
    assert(report.toplevelName.nonEmpty)
  }
}
