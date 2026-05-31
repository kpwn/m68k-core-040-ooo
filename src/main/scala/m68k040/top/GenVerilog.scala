package m68k040.top

import m68k040.M68kParams
import m68k040.core.{M68kCore, ParamPlugin, HelloPlugin}
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

object GenVerilog {
  def plugins(p: M68kParams): Seq[FiberPlugin] =
    Seq(new ParamPlugin(p), new HelloPlugin())

  def main(args: Array[String]): Unit = {
    val p = M68kParams()
    SpinalConfig(targetDirectory = "generated")
      .generateVerilog(new M68kCore(plugins(p)))
    println("Generated generated/M68kCore.v")
  }
}
