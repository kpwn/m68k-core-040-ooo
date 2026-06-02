package m68k040.top

import m68k040.M68kParams
import m68k040.M68kSpinalConfig
import m68k040.core.{M68kCore, ParamPlugin}
import m68k040.cache.IcachePlugin
import m68k040.mmu.IdentityTranslationPlugin
import m68k040.frontend.FetchAlignPlugin
import m68k040.decode.DecodeStage
import m68k040.rename.RenameStage
import m68k040.dispatch.DispatchPlugin
import m68k040.rob.RobPlugin
import m68k040.execute.{AluEuPlugin, BranchEuPlugin}
import m68k040.execute.iq.{IssueQueuePlugin, IssueQueueService}
import m68k040.execute.regfile.{RegFilePluginInt, RegFilePluginNzvc, RegFilePluginX}
import m68k040.services.{CommitTraceService, RedirectService}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Wires the IssueQueue's two issue ports to the two ALU EUs, and the EUs'
  * completions back to the ROB's two completion ports. For OOC synthesis it also
  * anchors the pipeline to top IO so nothing is pruned: the EU int-write results
  * (anchors the ALU+PRF datapath, since a PRF read value is then observed) and
  * the CommitTrace (anchors the ROB retire/control path). */
class BackendWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin, branchEu: BranchEuPlugin) extends FiberPlugin {
  val logic = during build new Area {
    val iq  = host[IssueQueueService]
    val rob = host[RobPlugin]
    // Commit-time mispredict redirect fan-out (registered doFlush pulse). doFlush
    // fans only to pointer/bitmap/skid-valid resets + the registered redirect PC
    // (FMax: no combinational execute->flush path).
    val doFlush = host[RedirectService].doFlush
    val flushPc = host[RedirectService].flushPc
    iq.flushPort := doFlush                                   // IQ clear
    host[DecodeStage].logic.pipeFlush := doFlush              // FE skid (decode->rename)
    host[RenameStage].logic.pipeFlush := doFlush              // FE skid (rename->dispatch)
    // RAT-rollback (rename.flushPort) already driven by the ROB (rc.flushPort).
    val faRedir = host[FetchAlignPlugin].logic.mispredictRedirect
    faRedir.valid   := doFlush
    faRedir.payload := flushPc
    eu0.issue << iq.issue(0)
    eu1.issue << iq.issue(1)
    // Branch EU: issue port 2 (branch-class) -> branch EU; its completion records
    // {mispredict, nextPc} into the ROB for commit-time recovery (sibling-driven,
    // exactly like the ALU completion ports above).
    branchEu.issue << iq.issue(2)
    rob.logic.branchCompletion.valid   := branchEu.completion.valid
    rob.logic.branchCompletion.payload := branchEu.completion.payload
    rob.logic.completion(0).valid   := eu0.completion.valid
    rob.logic.completion(0).payload := eu0.completion.payload
    rob.logic.completion(1).valid   := eu1.completion.valid
    rob.logic.completion(1).payload := eu1.completion.payload

    // Synth anchors (registered top outputs) so synthesis can't trim the core.
    val eu0Res = out(RegNext(eu0.intW.data))   // EU0 int result -> anchors datapath+PRF
    val eu1Res = out(RegNext(eu1.intW.data))
    val ct = host[CommitTraceService]
    val traceOut = out(Vec.fill(2)(m68k040.types.CommitTrace()))
    val fireOut  = out(Vec.fill(2)(Bool()))
    for (k <- 0 until 2) {
      traceOut(k) := RegNext(ct.trace(k))
      fireOut(k)  := RegNext(ct.traceFire(k)) init False
    }
  }
}

/** Full integrated execute core (slice 3c) for OOC synthesis: frontend → rename →
  * dispatch → ROB + IssueQueue → 2 ALU EUs → 3 PRFs. SynthProbePlugin anchors the
  * CommitTrace outputs so the retire path (and the whole pipeline feeding it) is
  * not pruned. I-cache AXI master + redirect/flush slave ports surface as top IO. */
object GenFullCoreSynthVerilog {
  def main(args: Array[String]): Unit = {
    val p = M68kParams()
    M68kSpinalConfig(targetDirectory = "generated")
      .generateVerilog {
        val eu0 = new AluEuPlugin
        val eu1 = new AluEuPlugin
        val branchEu = new BranchEuPlugin
        new M68kCore(Seq[FiberPlugin](
          new ParamPlugin(p),
          new IdentityTranslationPlugin(),
          new IcachePlugin(),
          new FetchAlignPlugin(),
          new DecodeStage(),
          new RenameStage(),
          new DispatchPlugin(),
          new RobPlugin(),
          new IssueQueuePlugin(),
          eu0, eu1, branchEu,
          new RegFilePluginInt(),
          new RegFilePluginNzvc(),
          new RegFilePluginX(),
          new BackendWiringPlugin(eu0, eu1, branchEu)
        )).setDefinitionName("M68kFullCoreSynth")
      }
    println("Generated generated/M68kFullCoreSynth.v")
  }
}
