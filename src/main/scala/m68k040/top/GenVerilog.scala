package m68k040.top

import m68k040.{M68kParams, M68kSpinalConfig}
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
    M68kSpinalConfig(targetDirectory = "generated")
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
    new m68k040.dispatch.DispatchPlugin(),
    new RobPlugin(),
    new m68k040.execute.iq.IssueQueuePlugin(),
    new SynthProbePlugin()
  )

  def main(args: Array[String]): Unit = {
    val p = M68kParams()
    M68kSpinalConfig(targetDirectory = "generated")
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
    new m68k040.dispatch.DispatchPlugin(),
    new RobPlugin(),
    new m68k040.execute.iq.IssueQueuePlugin(),
    new SynthProbePlugin()
  )

  def main(args: Array[String]): Unit = {
    val p = M68kParams()
    M68kSpinalConfig(targetDirectory = "generated")
      .generateVerilog(new M68kCore(plugins(p)).setDefinitionName("M68kBackendSynth"))
    println("Generated generated/M68kBackendSynth.v")
  }
}

/** OOC synth gate for the IssueQueue (slice 3b) — registered IO boundary so the
  * measured paths are the internal compaction / wakeup / age-select reg→reg. */
object GenIqSynthVerilog {
  def main(args: Array[String]): Unit = {
    val p = M68kParams()
    M68kSpinalConfig(targetDirectory = "generated")
      .generateVerilog(new M68kCore(Seq[FiberPlugin](
        new ParamPlugin(p),
        new m68k040.execute.iq.IssueQueuePlugin(),
        new IqSynthProbePlugin()
      )).setDefinitionName("M68kIqSynth"))
    println("Generated generated/M68kIqSynth.v")
  }
}

/** OOC synth gate for the three PRFs (slice 2) — registered IO boundary so the
  * measured paths are the RF read/bypass/LVT-mux + write-merge reg→reg. */
object GenPrfSynthVerilog {
  def main(args: Array[String]): Unit = {
    M68kSpinalConfig(targetDirectory = "generated")
      .generateVerilog(new M68kCore(Seq[FiberPlugin](
        new m68k040.execute.regfile.RegFilePluginInt(),
        new m68k040.execute.regfile.RegFilePluginNzvc(),
        new m68k040.execute.regfile.RegFilePluginX(),
        new PrfSynthProbePlugin()
      )).setDefinitionName("M68kPrfSynth"))
    println("Generated generated/M68kPrfSynth.v")
  }
}

/** OOC synth/impl gate for the standalone 80-bit FPU arithmetic core (FpuCore, the FPU
  * plan's Task 7). FpuCore is not yet wired into the core -- the EU-integration task does
  * that -- so it is gated on its own, exactly the way M68kIqSynth/M68kPrfSynth gate their
  * slices, against the same 4.000 ns (250 MHz) clock constraint and the same part. */
object GenFpuCoreSynthVerilog {
  def main(args: Array[String]): Unit = {
    M68kSpinalConfig(targetDirectory = "generated")
      .generateVerilog(new m68k040.execute.fpu.FpuCore().setDefinitionName("M68kFpuCoreSynth"))
    println("Generated generated/M68kFpuCoreSynth.v")
  }
}
