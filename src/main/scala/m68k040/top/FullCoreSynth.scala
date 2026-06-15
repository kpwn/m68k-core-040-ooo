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
import m68k040.execute.{AluEuPlugin, BranchEuPlugin, LsEuPlugin, DivEuPlugin}
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
class BackendWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin, branchEu: BranchEuPlugin, lsEu: LsEuPlugin, divEu: DivEuPlugin) extends FiberPlugin {
  // Int PRF write port for the exception unit's A7 (reg 15) write-back.
  var a7Wr: m68k040.execute.regfile.RegFileWritePort = null
  // Int PRF READ port for the LIVE committed A7 readback (arch-15 committed phys). Feeds
  // exc.committedA7In so ss.usp/isp/msp continuously mirror the architectural A7.
  var a7Rd: m68k040.execute.regfile.RegFileReadPort = null
  during setup {
    a7Wr = host[m68k040.execute.regfile.IntRegFileService].newWrite(latency = 1, sharingKey = "excA7")
    a7Rd = host[m68k040.execute.regfile.IntRegFileService].newRead(forceNoBypass = true)
  }
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
    // STOP-halt: while the ROB is in the `stopped` state, quiesce the front-end (hold
    // fetch + feed at the STOP successor PC). The IRQ-entry vector redirect clears it.
    host[FetchAlignPlugin].logic.quiesce := rob.logic.stopped
    eu0.issue << iq.issue(0)
    eu1.issue << iq.issue(1)
    // MOVE-from-SR int result needs the committed SR system byte: wire the ROB's
    // exc.ss.srSys to both ALU EUs (read-only; the only srSys writer is exception
    // entry/RTE, which fully flushes -> no in-flight fromSr observes a stale value).
    eu0.srSysIn := rob.logic.exc.ss.srSys
    eu1.srSysIn := rob.logic.exc.ss.srSys
    // SLOW-ALU (shift, latency-2) dynamic wakeup: ONE IQ port per ALU EU (both EUs can
    // complete a distinct shift the same cycle, so no shared/OR'd port).
    iq.aluSlowWakeup(0).valid   := eu0.slowWakeup.valid
    iq.aluSlowWakeup(0).payload := eu0.slowWakeup.payload
    iq.aluSlowWakeup(1).valid   := eu1.slowWakeup.valid
    iq.aluSlowWakeup(1).payload := eu1.slowWakeup.payload
    // Branch EU: issue port 2 (branch-class) -> branch EU; its completion records
    // {mispredict, nextPc} into the ROB for commit-time recovery (sibling-driven,
    // exactly like the ALU completion ports above).
    branchEu.issue << iq.issue(2)
    rob.logic.branchCompletion.valid   := branchEu.completion.valid
    rob.logic.branchCompletion.payload := branchEu.completion.payload
    // Execute-time conditional fault (TRAPV vector 7) -> ROB euFault (generalized).
    rob.logic.euFaultCompletion.valid   := branchEu.trapvFault.valid
    rob.logic.euFaultCompletion.payload := branchEu.trapvFault.payload
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
      rob.logic.ccrCompletion(idx).payload.result   := w.result
      rob.logic.ccrCompletion(idx).payload.intWrite := w.intWrite
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

    // ---- CPLX (DivEu) wiring: issue port 4 -> DivEu; completion (port 3) + dynamic
    // wakeup + euFault (CHK vec6 / DIV0 vec5). ----
    divEu.issue << iq.issue(4)
    // Squash a multi-cycle DIV/MUL flushed in flight (same flush the IQ uses) so its
    // late wrong-path completion can't land on a reused robId.
    divEu.cplxFlush := doFlush || excActive
    rob.logic.completion(3).valid   := divEu.completion.valid
    rob.logic.completion(3).payload := divEu.completion.payload
    iq.cplxWakeup.valid   := divEu.wakeup.valid
    iq.cplxWakeup.payload := divEu.wakeup.payload
    // DivEu euFault shares the generalized ROB euFaultCompletion with the branch EU's
    // TRAPV. They are mutually exclusive in practice (different EUs, single-outstanding),
    // but to be safe the branch EU's fault takes priority via last-driver: drive the
    // DivEu fault FIRST, then the branch EU below would override — instead OR them with
    // an explicit mux (the branch EU's drive above already set it; OR the DivEu in).
    when(divEu.euFault.valid) {
      rob.logic.euFaultCompletion.valid   := True
      rob.logic.euFaultCompletion.payload := divEu.euFault.payload
    }
    wireCcr(3, divEu.logic.wbObs)
    // The IQ dynamic wakeup is keyed by the producer pdst. The LS EU drives a
    // dedicated `wakeup` Flow from its REGISTERED completion stage (valid only for a
    // completing LOAD that produces a physreg — a store completes too but writes no
    // register), so consumers don't reach into the (now-pipelined) internal context.
    iq.lsWakeup.valid   := lsEu.wakeup.valid
    iq.lsWakeup.payload := lsEu.wakeup.payload
    // Dynamic NZVC wakeup: a completing NZVC-writing LS store (MOVE-to-mem) wakes a
    // flag-reader of its NZVC (e.g. a bit-op RMW µop). Mirrors the int load wakeup.
    iq.lsNzvcWakeup.valid   := lsEu.wakeupNzvc.valid
    iq.lsNzvcWakeup.payload := lsEu.wakeupNzvc.payload
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
    // ── Interrupt inputs (simple protocol) from REGISTERED OOC inputs ──
    // Mirror the mmuEnable/rootPtr pattern: drive the InterruptControlPlugin's regs
    // from RegNext(in...) init 0 so the IPL-compare + vector-select cone is LIVE in
    // the netlist (OOC synth can't const-fold iplIn=0 and prune the recognition
    // logic). Idle (ipl=0) by default -> no interrupt unless the SoC raises iplIn.
    val intCtrlPlug = host[m68k040.exception.InterruptControlPlugin]
    val iplInPort      = in UInt (3 bits)
    val iackAvecIn     = in Bool ()
    val iackVectorIn   = in UInt (8 bits)
    intCtrlPlug.logic.iplIn      := RegNext(iplInPort) init 0
    intCtrlPlug.logic.iackAvec   := RegNext(iackAvecIn) init False
    intCtrlPlug.logic.iackVector := RegNext(iackVectorIn) init 0
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
    // A7 (arch-15) write on exc/RTE A7 change; the SAME port also serves a commit-time
    // SYSTEM op's READ direction (MOVE-USP/MOVEC Rc->Rn writes an arbitrary arch-Rn).
    // sysRegWrite fires in S_APPLY, a7Write in S_REDIR (consecutive -> no port collision).
    // The a7Write address uses committedPhysA7 (commReg(15)) so the write is correct
    // even when arch-15 has been renamed by an OoO A7 write (move ...,%sp / push / bsr).
    // When A7 is unrenamed, committedPhysA7 == 15, so this is identical to the old U(15).
    a7Wr.valid   := exc.a7WriteValid || exc.sysRegWriteValid
    a7Wr.address := Mux(exc.sysRegWriteValid, exc.sysRegWritePhys.resize(a7Wr.address.getWidth),
                                              host[RenameStage].committedPhysA7.resize(a7Wr.address.getWidth))
    a7Wr.data    := Mux(exc.sysRegWriteValid, exc.sysRegWriteData.asBits, exc.a7WriteData.asBits)
    // LIVE committed-A7 readback: read the int PRF at the committed arch-15 phys mapping
    // and feed it to the exception unit, which drives ss.writeA7 every cycle (routed by
    // committed S,M) so ss.usp/isp/msp track the architectural A7 of the active bank.
    a7Rd.addr := host[RenameStage].committedPhysA7.resize(a7Rd.addr.getWidth)
    exc.committedA7In := a7Rd.data.asUInt

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
        val divEu = new DivEuPlugin
        new M68kCore(Seq[FiberPlugin](
          new ParamPlugin(p),
          new MmuControlPlugin(),
          new m68k040.exception.InterruptControlPlugin(),
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
          eu0, eu1, branchEu, lsEu, divEu,
          new RegFilePluginInt(),
          new RegFilePluginNzvc(),
          new RegFilePluginX(),
          new BackendWiringPlugin(eu0, eu1, branchEu, lsEu, divEu)
        )).setDefinitionName("M68kFullCoreSynth")
      }
    println("Generated generated/M68kFullCoreSynth.v")
  }
}
