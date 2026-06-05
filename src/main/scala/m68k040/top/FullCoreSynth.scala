package m68k040.top

import m68k040.M68kParams
import m68k040.M68kSpinalConfig
import m68k040.core.{M68kCore, ParamPlugin}
import m68k040.cache.{IcachePlugin, DcachePlugin}
import m68k040.mmu.{ItlbPlugin, DtlbPlugin, MmuControlPlugin}
import m68k040.frontend.FetchAlignPlugin
import m68k040.decode.DecodeStage
import m68k040.rename.RenameStage
import m68k040.dispatch.DispatchPlugin
import m68k040.rob.RobPlugin
import m68k040.execute.{AluEuPlugin, BranchEuPlugin, LsEuPlugin}
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
class BackendWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin, branchEu: BranchEuPlugin, lsEu: LsEuPlugin) extends FiberPlugin {
  // Int PRF write port for the exception unit's A7 (reg 15) write-back.
  var a7Wr: m68k040.execute.regfile.RegFileWritePort = null
  during setup { a7Wr = host[m68k040.execute.regfile.IntRegFileService].newWrite(latency = 1, sharingKey = "excA7") }
  val logic = during build new Area {
    val iq  = host[IssueQueueService]
    val rob = host[RobPlugin]
    // Commit-time mispredict redirect fan-out (registered doFlush pulse). doFlush
    // fans only to pointer/bitmap/skid-valid resets + the registered redirect PC
    // (FMax: no combinational execute->flush path).
    val doFlush = host[RedirectService].doFlush
    val flushPc = host[RedirectService].flushPc
    val excActive = rob.logic.excActive
    // IQ/skid flush held high while the commit-side exception sequencer runs
    // (serializing) so wrong-path uops fetched during the sequence are squashed.
    iq.flushPort := doFlush || excActive                      // IQ clear
    host[DecodeStage].logic.pipeFlush := doFlush || excActive // FE skid (decode->rename)
    host[RenameStage].logic.pipeFlush := doFlush || excActive // FE skid (rename->dispatch)
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
    // TRAPV execute-time conditional fault (vector 7 if V) -> ROB.
    rob.logic.trapvFaultCompletion.valid   := branchEu.trapvFault.valid
    rob.logic.trapvFaultCompletion.payload := branchEu.trapvFault.payload
    rob.logic.completion(0).valid   := eu0.completion.valid
    rob.logic.completion(0).payload := eu0.completion.payload
    rob.logic.completion(1).valid   := eu1.completion.valid
    rob.logic.completion(1).payload := eu1.completion.payload
    // Committed-CCR VALUE completion (per EU) for the exception FSM's stacked SR.
    def wireCcr(idx: Int, w: m68k040.execute.WbObs): Unit = {
      rob.logic.ccrCompletion(idx).valid            := w.valid
      rob.logic.ccrCompletion(idx).payload.robId    := w.robId
      rob.logic.ccrCompletion(idx).payload.nzvc     := w.nzvc.asUInt
      rob.logic.ccrCompletion(idx).payload.nzvcWrite:= w.nzvcWrite
      rob.logic.ccrCompletion(idx).payload.x        := w.x
      rob.logic.ccrCompletion(idx).payload.xWrite   := w.xWrite
    }
    wireCcr(0, eu0.logic.wbObs); wireCcr(1, eu1.logic.wbObs); wireCcr(2, lsEu.logic.wbObs)

    // ---- LS cluster wiring ----
    // LS issue port (3) -> LS EU. Its completion is BOTH a ROB completion (3rd
    // port) AND the IQ dynamic-wakeup broadcast (variant A: dependents of a load
    // wake when the load's data is actually ready).
    lsEu.issue << iq.issue(3)
    rob.logic.completion(2).valid   := lsEu.completion.valid
    rob.logic.completion(2).payload := lsEu.completion.payload
    // MMU access-fault completion -> ROB (flags the entry vector 2 + faultAddr/SSW
    // for precise format-$7 delivery at retire).
    rob.logic.lsFaultCompletion.valid   := lsEu.faultCompletion.valid
    rob.logic.lsFaultCompletion.payload := lsEu.faultCompletion.payload
    // The IQ dynamic wakeup is keyed by the producer pdst. The LS EU drives a
    // dedicated `wakeup` Flow from its REGISTERED completion stage (valid only for a
    // completing LOAD that produces a physreg — a store completes too but writes no
    // register), so consumers don't reach into the (now-pipelined) internal context.
    iq.lsWakeup.valid   := lsEu.wakeup.valid
    iq.lsWakeup.payload := lsEu.wakeup.payload
    // ROB retire (slot 0) -> SQ commit; doFlush -> SQ flush (squash speculative).
    lsEu.sqCommit.valid   := rob.logic.retire0
    lsEu.sqCommit.payload := rob.logic.h0
    lsEu.sqFlush          := doFlush

    // ── DTLB U/M deferred-write queue wiring ──
    // The walk tags its U/M descriptor write with the LS EU's in-flight access
    // robId; the queue drains at that robId's commit (same retire port as the SQ)
    // and discards on flush.
    val dtlb = host[m68k040.mmu.DtlbPlugin]
    // Drive the ONE shared MMU control from registered synth-top inputs: (a) so they
    // are not UNASSIGNED (clean elaboration — sim-poked / future-MOVEC-driven, no RTL
    // driver otherwise), and (b) so OOC synth cannot const-fold mmuEnable to its init
    // (False) and prune the DTLB/ITLB/walkers — the gate must measure the MMU
    // translate path (BOTH TLBs + both walkers) in the netlist.
    val mmuCtrl = host[m68k040.services.MmuControlService]
    val mmuEnableIn = in Bool ()
    val rootPtrIn   = in UInt (32 bits)
    mmuCtrl.mmuEnable := RegNext(mmuEnableIn) init False
    mmuCtrl.rootPtr   := RegNext(rootPtrIn) init 0
    dtlb.umAccessRobId := lsEu.xlateRobId
    dtlb.umCommitValid := rob.logic.retire0
    dtlb.umCommitId    := rob.logic.h0
    dtlb.umFlush       := doFlush
    // ── ITLB U deferred-write queue wiring (U-only; instruction fetch sets U not M) ──
    // The fetch walk's U-descriptor write drains at retire (tagged robId 0; the U bit
    // is idempotent / not architecturally compared) and discards on flush.
    val itlb = host[m68k040.mmu.ItlbPlugin]
    itlb.umAccessRobId := U(0, 6 bits)
    itlb.umCommitValid := rob.logic.retire0
    itlb.umCommitId    := rob.logic.h0
    itlb.umFlush       := doFlush
    // The D-cache's `axi` is declared master() inside its plugin and surfaces as a
    // top-level IO automatically (like the I-cache's), so no extra wiring needed.

    // ── Exception-unit cache arbitration (routed through the LS EU's mux) + the
    // exc reads the cache/TLB responses. While excActive the commit-side FSM owns
    // the D-cache ports (the LS pipe is squashed — serializing). ──
    val dc    = host[m68k040.cache.DcacheService]
    val xlate = host[m68k040.services.DTranslationService]
    val exc   = rob.logic.exc
    exc.dcLoadRsp.valid   := dc.loadRsp.valid
    exc.dcLoadRsp.payload := dc.loadRsp.payload
    exc.dcLoadBusy        := dc.loadBusy
    exc.dcStoreAck        := dc.storeAck
    exc.dtRsp.ready       := xlate.rsp.ready
    exc.dtRsp.ppn         := xlate.rsp.ppn
    exc.dtRsp.cacheMode   := xlate.rsp.cacheMode
    exc.dtRsp.fault       := xlate.rsp.fault
    lsEu.excActive          := excActive
    lsEu.excLoadCmdValid    := exc.dcLoadCmd.valid
    lsEu.excLoadCmdVaddr    := exc.dcLoadCmd.payload.vaddr
    lsEu.excLoadCmdSize     := exc.dcLoadCmd.payload.size
    exc.dcLoadCmd.ready     := lsEu.excLoadCmdReady
    lsEu.excStoreValid      := exc.dcStore.valid
    lsEu.excStorePayload    := exc.dcStore.payload
    lsEu.excXlateValid      := exc.dtReq.valid
    lsEu.excXlateVpn        := exc.dtReq.vpn
    lsEu.excXlateWrite      := exc.dtReq.write
    lsEu.excXlateSupervisor := exc.dtReq.supervisor
    exc.sqDrained           := lsEu.sqEmptySig
    // A7 (int reg 15) write-back on an exception/RTE A7 change (committed arch-15 ==
    // phys-15, unrenamed).
    a7Wr.valid   := exc.a7WriteValid
    a7Wr.address := U(15, a7Wr.address.getWidth bits)
    a7Wr.data    := exc.a7WriteData.asBits

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
        val lsEu = new LsEuPlugin
        new M68kCore(Seq[FiberPlugin](
          new ParamPlugin(p),
          new MmuControlPlugin(),
          new ItlbPlugin(),
          new DtlbPlugin(),
          new IcachePlugin(),
          new DcachePlugin(),
          new FetchAlignPlugin(),
          new DecodeStage(),
          new RenameStage(),
          new DispatchPlugin(),
          new RobPlugin(),
          new IssueQueuePlugin(),
          eu0, eu1, branchEu, lsEu,
          new RegFilePluginInt(),
          new RegFilePluginNzvc(),
          new RegFilePluginX(),
          new BackendWiringPlugin(eu0, eu1, branchEu, lsEu)
        )).setDefinitionName("M68kFullCoreSynth")
      }
    println("Generated generated/M68kFullCoreSynth.v")
  }
}
