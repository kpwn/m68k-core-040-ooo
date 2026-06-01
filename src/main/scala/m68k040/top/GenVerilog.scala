package m68k040.top

import m68k040.M68kParams
import m68k040.core.{M68kCore, ParamPlugin, HelloPlugin}
import m68k040.cache.IcachePlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.frontend.FetchAlignPlugin
import m68k040.decode.DecodeStage
import m68k040.rename.RenameStage
import m68k040.rob.RobPlugin
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

/** Full frontend→backend pipeline wired for synthesis/FMax measurement.
  * I-cache AXI master + ROB markComplete/flush + redirect/resume surface as
  * top IO automatically (master/slave ports declared in plugin logic);
  * SynthProbePlugin anchors the CommitTrace outputs so nothing is trimmed. */
object GenSynthVerilog {
  def plugins(p: M68kParams): Seq[FiberPlugin] = Seq(
    new ParamPlugin(p),
    new IdentityTranslationPlugin(),
    new IcachePlugin(),
    new FetchAlignPlugin(),
    new DecodeStage(),
    new RenameStage(),
    new RobPlugin(),
    new SynthProbePlugin()
  )

  def main(args: Array[String]): Unit = {
    val p = M68kParams()
    SpinalConfig(targetDirectory = "generated")
      .generateVerilog(new M68kCore(plugins(p)).setDefinitionName("M68kCoreSynth"))
    println("Generated generated/M68kCoreSynth.v")
  }
}

/** OoO backend only (rename → ROB), fed by a registered DecodeUopService IO
  * boundary. This is the FMax-critical new logic (async-RAT rename + ROB ring,
  * no-high-fanout discipline); the frontend's BRAM/AXI is excluded so the
  * measured critical path is the backend reg→reg paths. */
object GenBackendSynthVerilog {
  def plugins(p: M68kParams): Seq[FiberPlugin] = Seq(
    new ParamPlugin(p),
    new DecodeUopInputPlugin(),
    new RenameStage(),
    new RobPlugin(),
    new SynthProbePlugin()
  )

  def main(args: Array[String]): Unit = {
    val p = M68kParams()
    SpinalConfig(targetDirectory = "generated")
      .generateVerilog(new M68kCore(plugins(p)).setDefinitionName("M68kBackendSynth"))
    println("Generated generated/M68kBackendSynth.v")
  }
}
