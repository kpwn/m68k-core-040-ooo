package m68k040.top

import m68k040.M68kParams
import m68k040.M68kSpinalConfig
import m68k040.core.{M68kCore, ParamPlugin}
import m68k040.cache.{IcachePlugin, DcachePlugin}
import m68k040.mmu.{ItlbPlugin, DtlbPlugin, MmuControlPlugin}
import m68k040.frontend.{FetchAlignPlugin, BtbPlugin}
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
  // NZVC/X PRF write ports for RTE's CCR restore (task #176-regression): a DIRECT
  // write into whatever physical register nzvcRat/xRat's COMMITTED mapping currently
  // names, mirroring a7Wr's already-safe pattern -- see ExceptionUnit.scala's
  // rteNzvcWriteValid doc comment for why this replaced a rename-allocation approach.
  var nzvcWr: m68k040.execute.regfile.RegFileWritePort = null
  var xWr:    m68k040.execute.regfile.RegFileWritePort = null
  during setup {
    a7Wr = host[m68k040.execute.regfile.IntRegFileService].newWrite(latency = 1, sharingKey = "excA7")
    a7Rd = host[m68k040.execute.regfile.IntRegFileService].newRead(forceNoBypass = true)
    nzvcWr = host[m68k040.execute.regfile.NzvcRegFileService].newWrite(latency = 1, sharingKey = "rteNzvc")
    xWr    = host[m68k040.execute.regfile.XRegFileService].newWrite(latency = 1, sharingKey = "rteX")
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
    // Front-end complex-packet resume (task #178, ported-tests cluster 11): a genuinely-
    // `complex` predecode packet permanently stalls FetchAlignPlugin until its `resume`
    // port fires, but `resume` is a TEST-BOOT-ONLY external port -- `mispredictRedirect`
    // is the established INTERNAL wiring point instead (its handler already does exactly
    // what a `resume` would: ibuf flush + stalled:=False + decodePc:=newPc, WITHOUT
    // touching `pipeFlush`, so an in-flight µcode engine keeps running -- only the
    // front-end fetch pointer is unstuck). See DecodeStage.scala's `ucComplexResume`
    // comment for the full story. Priority: a real doFlush wins over a same-cycle resume.
    val ucComplexResume = host[DecodeStage].logic.ucComplexResume
    val faRedir = host[FetchAlignPlugin].logic.mispredictRedirect
    faRedir.valid   := doFlush || ucComplexResume.valid
    faRedir.payload := Mux(doFlush, flushPc, ucComplexResume.payload)

    // ── Fetch-time BTB wiring (slice 1) ──────────────────────────────────────────
    // Read port: query the BTB with the fetch window PC when a fetch is issued. The
    // registered lookup result drives FetchAlign's predictRedirect (Step 3). Update
    // port: consumed by the BtbPlugin from the ROB's BtbUpdateService (retire). The
    // BTB invalidates on the SAME signal that clears the I-cache.
    val fa  = host[FetchAlignPlugin]
    val btb = host[BtbPlugin]
    btb.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll
    // Two per-instruction combinational BTB lookups (the aligner's slot0/slot1 PCs);
    // the predict-taken + target return THIS cycle into FetchAlign's prediction inputs.
    btb.logic.queryPc     := fa.logic.btbQueryPc0
    btb.logic.queryValid  := fa.logic.btbQueryValid0
    btb.logic.query2Pc    := fa.logic.btbQueryPc1
    btb.logic.query2Valid := fa.logic.btbQueryValid1
    fa.logic.btbPredTaken0  := btb.logic.predTakenComb
    fa.logic.btbPredTarget0 := btb.logic.predTargetComb
    fa.logic.btbPredTaken1  := btb.logic.predTaken2Comb
    fa.logic.btbPredTarget1 := btb.logic.predTarget2Comb
    // RAS (slice 2): FetchAlign drives the push (call retPC) + pop (predicted return);
    // the RAS returns its combinational top-of-stack predict. Invalidate on the same
    // I-cache flush that clears the BTB.
    val ras = host[m68k040.frontend.RasPlugin]
    ras.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll
    ras.logic.pushValid     := fa.logic.rasPushValid
    ras.logic.pushRetPc     := fa.logic.rasPushRetPc
    ras.logic.popValid      := fa.logic.rasPopValid
    fa.logic.rasPredValid   := ras.logic.predValid
    fa.logic.rasPredTarget  := ras.logic.predTarget
    // ── gshare direction predictor (slice 3) ────────────────────────────────────
    // Query the PHT with the same slot0/slot1 aligner PCs the BTB sees; feed the BTB hit
    // + brType into FetchAlign so it can form condBtbHit and source the conditional
    // direction from the PHT. Drive the GHR shift on the emitted predicted conditional;
    // train the PHT at retire (the ROB's GshareUpdateService). Invalidate (GHR clear) on
    // the same I-cache flush that clears the BTB/RAS.
    val gsh = host[m68k040.frontend.GsharePlugin]
    gsh.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll
    gsh.logic.queryPc0      := fa.logic.btbQueryPc0
    gsh.logic.queryValid0   := fa.logic.btbQueryValid0
    gsh.logic.queryPc1      := fa.logic.btbQueryPc1
    gsh.logic.queryValid1   := fa.logic.btbQueryValid1
    fa.logic.gsBtbHit0      := btb.logic.predHitComb
    fa.logic.gsBtbType0     := btb.logic.predTypeComb
    fa.logic.gsBtbHit1      := btb.logic.predHit2Comb
    fa.logic.gsBtbType1     := btb.logic.predType2Comb
    fa.logic.gsPhtTaken0    := gsh.logic.phtTaken0
    fa.logic.gsPhtIndex0    := gsh.logic.phtIndex0
    fa.logic.gsPhtTaken1    := gsh.logic.phtTaken1
    fa.logic.gsPhtIndex1    := gsh.logic.phtIndex1
    gsh.logic.shiftValid    := fa.logic.gsShiftValid
    gsh.logic.shiftDir      := fa.logic.gsShiftDir
    gsh.gshareUpdate.valid   := rob.logic.gshareUpdateFlow.valid
    gsh.gshareUpdate.payload := rob.logic.gshareUpdateFlow.payload
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
    wireCcr(0, eu0.logic.ccrObs); wireCcr(1, eu1.logic.ccrObs); wireCcr(2, lsEu.logic.ccrObs)

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
    // Dynamic NZVC wakeup (task #167): a completing CPLX flag-writer (DIV/MUL/CHK/
    // CMP2/CHK2) wakes a flag-reader of its NZVC. Mirrors lsNzvcWakeup below.
    iq.cplxNzvcWakeup.valid   := divEu.wakeupNzvc.valid
    iq.cplxNzvcWakeup.payload := divEu.wakeupNzvc.payload
    // DivEu euFault shares the generalized ROB euFaultCompletion with the branch EU's
    // TRAPV. They are mutually exclusive in practice (different EUs, single-outstanding),
    // but to be safe the branch EU's fault takes priority via last-driver: drive the
    // DivEu fault FIRST, then the branch EU below would override — instead OR them with
    // an explicit mux (the branch EU's drive above already set it; OR the DivEu in).
    when(divEu.euFault.valid) {
      rob.logic.euFaultCompletion.valid   := True
      rob.logic.euFaultCompletion.payload := divEu.euFault.payload
    }
    wireCcr(3, divEu.logic.ccrObs)
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
    // ALSO squash speculative SQ entries with a ONE-CYCLE pulse at exception ENTRY (the
    // rising edge of excActive): a privilege-trapped / faulted STORE-form op (e.g. MOVES
    // write in user mode) leaves an UNCOMMITTED store in the SQ; the exc FSM's E_DRAIN waits
    // for sqDrained, which would deadlock on that orphan. The flush KEEPS committed entries
    // (only speculative ones squash). It MUST be a single-cycle pulse: a HELD flush gates
    // `headReady` (drain), blocking committed stores from draining into the handler frame.
    lsEu.sqCommit.valid   := rob.logic.retire0
    lsEu.sqCommit.payload := rob.logic.h0
    // Slot-1 retire: a store CAN dual-retire at h1 (completed early behind a
    // long-latency head, e.g. DIV) — missing this commit pulse loses the store
    // (never drains, or squashed by the next flush). Same-cycle mark is required.
    lsEu.sqCommitB.valid   := rob.logic.retire1
    lsEu.sqCommitB.payload := rob.logic.h1
    val excEnteringSq = rob.logic.excActive && !RegNext(rob.logic.excActive, init = False)
    lsEu.sqFlush          := doFlush || excEnteringSq

    // ── DTLB U/M deferred-write queue wiring ──
    // The walk tags its U/M descriptor write with the LS EU's in-flight access
    // robId; the queue drains at that robId's commit (same retire port as the SQ)
    // and discards on flush.
    val dtlb = host[m68k040.mmu.DtlbPlugin]
    // Drive the ONE shared MMU control from registered synth-top inputs: (a) so
    // they are not UNASSIGNED (clean elaboration — sim-poked only, no RTL driver
    // otherwise), and (b) so OOC synth cannot const-fold mmuEnable to its init
    // (False) and prune the DTLB/ITLB/walkers — the gate must measure the MMU
    // translate path (BOTH TLBs + both walkers) in the netlist.
    // RESTORED 2026-07-16 (task #131 revert): a MOVEC-driven write path was
    // attempted here (removing the need for this override, since MOVEC would be a
    // real, non-constant-foldable driver) but was REVERTED after it broke sim-poke
    // persistence on these same regs — see MmuControlPlugin's doc comment. mmuEnable/
    // urp/srp are back to sim-poke-only, so this override is needed again (now TWO
    // root-pointer inputs, urp+srp, instead of the old single rootPtr).
    val mmuCtrl = host[m68k040.services.MmuControlService]
    val mmuEnableIn = in Bool ()
    val urpIn       = in UInt (32 bits)
    val srpIn       = in UInt (32 bits)
    mmuCtrl.mmuEnable := RegNext(mmuEnableIn) init False
    mmuCtrl.urp       := RegNext(urpIn) init 0
    mmuCtrl.srp       := RegNext(srpIn) init 0
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
    dtlb.umCommitBValid := rob.logic.retire1
    dtlb.umCommitBId    := rob.logic.h1
    dtlb.umCommitId    := rob.logic.h0
    dtlb.umFlush       := doFlush
    // ── ITLB U deferred-write queue wiring (U-only; instruction fetch sets U not M) ──
    // The fetch walk's U-descriptor write drains at retire (tagged robId 0; the U bit
    // is idempotent / not architecturally compared) and discards on flush.
    val itlb = host[m68k040.mmu.ItlbPlugin]
    itlb.umAccessRobId := U(0, 6 bits)
    itlb.umCommitValid := rob.logic.retire0
    itlb.umCommitBValid := rob.logic.retire1
    itlb.umCommitBId    := rob.logic.h1
    itlb.umCommitId    := rob.logic.h0
    itlb.umFlush       := doFlush
    // PFLUSHA (task #136): the commit-time flush-all pulse reaches both TLBs the same
    // way umFlush does — direct top-level fan-out, no service round-trip.
    dtlb.flushAll      := rob.logic.exc.sysFlushAllValid
    itlb.flushAll      := rob.logic.exc.sysFlushAllValid
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
    // RTE CCR restore -> REAL flags PRF (task #176-regression): DIRECT write into
    // whatever physical register nzvcRat/xRat's committed mapping currently names.
    // Safe for the same reason a7Wr's direct write is safe: nothing else can be
    // committing to this same physical register while RTE's serializing FSM owns the
    // ROB head (retire0/1 blocked the whole time) -- no rename allocation, no
    // freelist interaction, no RAT remap, so no exposure to the ROB-head-serializing
    // squash window at all.
    nzvcWr.valid   := exc.rteNzvcWriteValid
    nzvcWr.address := host[RenameStage].committedPhysNzvc.resize(nzvcWr.address.getWidth)
    nzvcWr.data    := exc.rteNzvcWriteData
    xWr.valid      := exc.rteXWriteValid
    xWr.address    := host[RenameStage].committedPhysX.resize(xWr.address.getWidth)
    xWr.data       := exc.rteXWriteData.asBits

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
          new BtbPlugin(),
          new m68k040.frontend.RasPlugin(),
          new m68k040.frontend.GsharePlugin(),
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
