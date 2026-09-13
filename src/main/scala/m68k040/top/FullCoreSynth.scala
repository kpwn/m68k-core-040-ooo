package m68k040.top

import m68k040.M68kParams
import m68k040.M68kSpinalConfig
import m68k040.core.{M68kCore, ParamPlugin}
import m68k040.cache.{IcachePlugin, DcachePlugin}
import m68k040.mmu.{ItlbPlugin, DtlbPlugin, MmuControlPlugin}
import m68k040.frontend.{FetchAlignPlugin, BtbPlugin, FtbPlugin, ComplexResumeActionPipe}
import m68k040.decode.DecodeStage
import m68k040.rename.RenameStage
import m68k040.dispatch.DispatchPlugin
import m68k040.rob.RobPlugin
import m68k040.execute.{AluEuPlugin, BranchEuPlugin, LsEuPlugin, LsEuService, DivEuPlugin}
import m68k040.execute.iq.{IssueQueuePlugin, IssueQueueService}
import m68k040.execute.regfile.{RegFilePluginFp, RegFilePluginFpcc, RegFilePluginInt,
  RegFilePluginNzvc, RegFilePluginX}
import m68k040.services.{CommitTraceService, DecodeUopService, RedirectService,
  DebugMemoryService, DebugMemoryCommand}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Wires the IssueQueue's two issue ports to the two ALU EUs, and the EUs'
  * completions back to the ROB's two completion ports. For OOC synthesis it also
  * anchors the pipeline to top IO so nothing is pruned: the EU int-write results
  * (anchors the ALU+PRF datapath, since a PRF read value is then observed) and
  * the CommitTrace (anchors the ROB retire/control path). */
class BackendWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin, branchEu: BranchEuPlugin,
                          lsEu: LsEuPlugin, divEu: DivEuPlugin, debugStage: Int = 2)
    extends FiberPlugin with DebugMemoryService {
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
  // FPCC PRF read/write ports for the architectural FMOVE to/from FPSR (Task 9). Exact
  // NZVC analogue of nzvcWr + a7Rd: read the committed mapping every cycle to splice the
  // live FPCC into an FPSR read, and write that same committed mapping directly on an
  // FPSR write. See RenameStage.committedPhysFpcc's doc comment.
  var fpccRd: m68k040.execute.regfile.RegFileReadPort  = null
  var fpccWr: m68k040.execute.regfile.RegFileWritePort = null
  private var debugMaintCmdIn: Flow[DebugMemoryCommand] = null
  private var debugQuiescedOut: Bool = null
  private var debugMaintDoneOut: Bool = null
  private var debugMaintErrorOut: Bool = null
  during setup {
    debugMaintCmdIn = Flow(DebugMemoryCommand())
    debugMaintCmdIn.valid.allowOverride; debugMaintCmdIn.valid := False
    debugMaintCmdIn.payload.flatten.foreach(_.allowOverride)
    debugMaintCmdIn.payload.push := False
    debugMaintCmdIn.payload.invalidate := False
    debugMaintCmdIn.payload.sel := 0
    debugQuiescedOut = Bool()
    debugMaintDoneOut = Bool()
    debugMaintErrorOut = Bool()
    // BORROWS BranchEu's int write port instead of owning a 6th physical port.
    // Measured: the 6th port costs 900 LUT (vs ~500 for ports 3-5), for machinery idle
    // outside exception entry / RTE. The ExceptionUnit already borrows rather than owns
    // elsewhere -- E_DRAIN is literally "wait for the SQ to drain before grabbing the
    // port", after which it drives the D-cache command ports directly.
    // Exclusivity is STATIC, which is why BranchEu is the lender: it is lat-1 and never
    // stalls, so it occupies the port for exactly one cycle after a branch issues. The
    // A7 write happens far later in the FSM (past the store-queue drain and the frame
    // stores) with the pipe flushed and retire blocked, so nothing can issue in between.
    // Lower priority than BranchEu; RegFilePlugin asserts in simulation if both are ever
    // valid together, so this is checked rather than argued.
    a7Wr = host[m68k040.execute.regfile.IntRegFileService].newWrite(
             latency = 1, sharingKey = m68k040.execute.BranchEuPlugin.IntWbKey, priority = 0)
    a7Rd = host[m68k040.execute.regfile.IntRegFileService].newRead(forceNoBypass = true)
    nzvcWr = host[m68k040.execute.regfile.NzvcRegFileService].newWrite(latency = 1, sharingKey = "rteNzvc")
    xWr    = host[m68k040.execute.regfile.XRegFileService].newWrite(latency = 1, sharingKey = "rteX")
    fpccRd = host[m68k040.execute.regfile.FpccRegFileService].newRead(forceNoBypass = true)
    fpccWr = host[m68k040.execute.regfile.FpccRegFileService].newWrite(latency = 1, sharingKey = "excFpcc")
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
    val debugMaintDonePulse = Bool()
    debugMaintDonePulse.allowOverride
    debugMaintDonePulse := False
    // IQ/skid flush held high while the commit-side exception sequencer runs
    // (serializing) so wrong-path uops fetched during the sequence are squashed.
    val pipeFlush = doFlush || excActive
    val decodeUop = host[DecodeUopService]
    // ── Two-tier reschedule wiring (2026-09-04) ─────────────────────────────────
    // See RobPlugin's `earlyPend` doc comment and
    // docs/superpowers/specs/2026-09-04-two-tier-reschedule-design.md.
    //
    // TIER 1 (`earlyFire`, `earlyPend`) touches ONLY the two frontend signals below
    // plus the RAS predictor checkpoint. It is deliberately absent from every
    // state-rollback consumer: `iq.flushPort`, `RenameStage.pipeFlush`,
    // `divEu.cplxFlush`, `lsEu.sqFlush` (StoreQueue) and `dtlb/itlb.umFlush`
    // (`UmWriteQueue`) all keep `doFlush` (the retire-gated `doFlushReg`) as their
    // sole ROB-side source. The TLB one is a data-loss constraint, not a style
    // choice — an early `umFlush` could discard a still-needed M (modified)
    // descriptor write and let a dirty page be evicted as clean.
    //
    // TIER 2 suppression (`feSuppress`): when this flush IS the retirement of the
    // branch Tier 1 already redirected for, the frontend keeps what it has already
    // fetched and decoded down the CORRECT path — that is the entire IPC benefit.
    // `earlySuppressFe` is registered inside the ROB and already requires a robId
    // match, a resolved-PC match and no other flush source; `!excActive` here is
    // belt-and-braces for the multi-cycle exception sequencer.
    val earlyFire  = rob.logic.earlyFire
    val feSuppress = rob.logic.earlySuppressFe && !excActive
    val feFlush    = (doFlush && !feSuppress) || excActive || earlyFire
    iq.flushPort := pipeFlush                  // IQ clear (Tier 2 only)
    decodeUop.pipeFlush := feFlush             // FE skid (decode->rename)
    host[RenameStage].logic.pipeFlush := doFlush || excActive // FE skid (rename->dispatch)
    host[RenameStage].logic.allocHalt := rob.logic.earlyPend  // Tier-1 rename freeze
    // RAT-rollback (rename.flushPort) already driven by the ROB (rc.flushPort).
    // Front-end complex-packet resume (task #178, ported-tests cluster 11): a genuinely-
    // `complex` predecode packet permanently stalls FetchAlignPlugin until its `resume`
    // port fires, but `resume` is a TEST-BOOT-ONLY external port -- `mispredictRedirect`
    // is the established INTERNAL wiring point instead (its handler already does exactly
    // what a `resume` would: ibuf flush + stalled:=False + decodePc:=newPc, WITHOUT
    // touching `pipeFlush`, so an in-flight µcode engine keeps running -- only the
    // front-end fetch pointer is unstuck). The service action is registered once more
    // beside the frontend: this cuts the measured decode-pblock -> ITLB/cache/ring route
    // at the cost of one additional cold-path cycle. Priority: a real doFlush wins over
    // a same-cycle resume.
    val frontendResume = ComplexResumeActionPipe(decodeUop.complexResume, feFlush)
    val faRedir = host[FetchAlignPlugin].logic.mispredictRedirect
    // Priority: a real (non-suppressed) Tier-2 flush > a Tier-1 early redirect > a
    // complex-packet resume. Tier 1 and a non-suppressed Tier 2 can only coincide
    // when the Tier-2 event belongs to a DIFFERENT (older) entry, in which case the
    // older one must win — which the mux order below gives.
    faRedir.valid   := (doFlush && !feSuppress) || earlyFire || frontendResume.valid
    faRedir.payload := Mux(doFlush && !feSuppress, flushPc,
                       Mux(earlyFire, rob.logic.earlyPcReg, frontendResume.payload))

    // ── Decode fallback BTB + fetch-directed FTB wiring ──────────────────────────
    // The retained BTB ports query the aligned decode packets combinationally. The FTB
    // and gshare window lookups instead travel through their registered services from
    // each accepted I-cache command; their update ports consume ROB retire training.
    // Both target tables invalidate on the SAME signal that clears the I-cache — BOTH
    // sources: the
    // external boot/reset port AND the internal CPUSH/CINV maintenance pulse. The BTB
    // needs the latter because its lookup is not gated on the slot actually being a
    // branch, so a stale entry can redirect fetch on a non-branch with nothing
    // downstream to catch it (see IcachePlugin's `maintInvalidateAll` declaration).
    val fa  = host[FetchAlignPlugin]
    val btb = host[BtbPlugin]
    val ftb = host[FtbPlugin]
    val predictorInvalidate = host[IcachePlugin].logic.invalidateAll ||
                              host[IcachePlugin].logic.maintInvalidateAll
    btb.logic.invalidateAll := predictorInvalidate
    ftb.logic.invalidateAll := predictorInvalidate
    // Task P5.5: the INTERNAL, CPUSH/CINV-driven I-cache invalidate. Fans out to the
    // I-cache, BTB, and FTB (above) but deliberately NOT to the RAS/gshare below — those
    // two are independently protected (RAS gates on live predecode; gshare's direction
    // is cross-checked at resolve). See IcachePlugin's `maintInvalidateAll` declaration
    // for the recorded rationale. Pulses only AFTER the D-side maintenance walk
    // completes (ExceptionUnit's S_MAINTWAIT), so the BTB clear inherits that timing.
    host[IcachePlugin].logic.maintInvalidateAll := rob.logic.exc.icMaintPulse || debugMaintDonePulse
    // The SAME pulse must also drop the fetch BUFFER, not just the I-cache array: `ibuf`
    // sits downstream of the cache and was invalidated by nothing, so the canonical
    // store/CPUSHL/jump SMC sequence executed pre-patch bytes straight out of it. See
    // FetchAlignPlugin's `icMaintFlush` for the full note.
    fa.logic.icMaintFlush := host[IcachePlugin].logic.maintInvalidateAll
    // Two per-instruction combinational BTB lookups (the aligner's slot0/slot1 PCs);
    // the predict-taken + target return THIS cycle into FetchAlign's prediction inputs.
    btb.logic.queryPc     := fa.logic.btbQueryPc0
    btb.logic.queryValid  := fa.logic.btbQueryValid0
    fa.logic.btbPredTaken0  := btb.logic.predTakenComb
    fa.logic.btbPredTarget0 := btb.logic.predTargetComb
    // RAS (slice 2): FetchAlign drives the push (call retPC) + pop (predicted return);
    // the RAS returns its combinational top-of-stack predict. Invalidate on the
    // external boot/reset I-cache-invalidate port only (`invalidateAll`) -- unlike
    // the BTB above, the RAS is deliberately NOT also wired to the internal
    // CPUSH/CINV `maintInvalidateAll` pulse (see that wiring's own comment for why:
    // the RAS is independently protected by gating on live predecode).
    val ras = host[m68k040.frontend.RasPlugin]
    ras.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll
    ras.logic.pushValid     := fa.logic.rasPushValid
    ras.logic.pushRetPc     := fa.logic.rasPushRetPc
    ras.logic.popValid      := fa.logic.rasPopValid
    fa.logic.rasPredValid   := ras.logic.predValid
    fa.logic.rasPredTarget  := ras.logic.predTarget
    // Rollback-on-flush (see Ras.scala's doc comment for the full design/soundness
    // argument -- independent fix, NOT part of the `0x40800284` wild-jump
    // investigation that surfaced this gap as a side effect). `checkpointSave`
    // refreshes the checkpoint to the live RAS state every cycle the ROB is fully
    // drained (`rob.logic.count === 0`): at that instant nothing is outstanding, so
    // the live state is architecturally correct by construction (mod a few cycles of
    // fetch->dispatch pipeline latency). `checkpointRestore` undoes any wrong-path
    // push/pop since that save on either flush-class event that can follow a bad
    // speculative excursion: the ROB's own commit-time correction (`doFlush`, already
    // in scope above) and FetchAlign's own `ftqMismatch` re-framing recovery (a
    // frontend-only correction that never touches the ROB, so it needs its own term).
    // Two-tier reschedule: the RAS is a pure predictor (no architectural state), so
    // its restore belongs at the same instant the wrong-path frontend is discarded —
    // Tier 1 when Tier 1 owns the redirect, Tier 2 otherwise. Restoring again at
    // Tier 2 under `feSuppress` would undo the LEGITIMATE correct-path pushes made
    // while rename was frozen, which is the same undo-too-much mistake in miniature.
    val rasCheckpointRestore = (doFlush && !feSuppress) || earlyFire || fa.logic.ftqMismatch
    ras.logic.checkpointSave    := (rob.logic.count === U(0, rob.logic.count.getWidth bits)) &&
                                    !rasCheckpointRestore
    ras.logic.checkpointRestore := rasCheckpointRestore
    // ── gshare direction predictor (slice 3) ────────────────────────────────────
    // Query the PHT with the same slot0/slot1 aligner PCs the BTB sees; feed the BTB hit
    // + brType into FetchAlign so it can form condBtbHit and source the conditional
    // direction from the PHT. Drive the GHR shift on the emitted predicted conditional;
    // train the PHT at retire (the ROB's GshareUpdateService). Invalidate (GHR clear)
    // on the external boot/reset I-cache-invalidate port only (`invalidateAll`) --
    // like the RAS above (and unlike the BTB, which also fans in the internal
    // CPUSH/CINV `maintInvalidateAll` pulse), gshare is independently protected
    // (its predicted direction is cross-checked at resolve) so it does not need it.
    val gsh = host[m68k040.frontend.GsharePlugin]
    gsh.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll
    gsh.logic.queryPc0      := fa.logic.btbQueryPc0
    gsh.logic.queryValid0   := fa.logic.btbQueryValid0
    gsh.logic.queryPc1      := fa.logic.btbQueryPc1
    gsh.logic.queryValid1   := fa.logic.btbQueryValid1
    fa.logic.gsBtbHit0      := btb.logic.predHitComb
    fa.logic.gsBtbType0     := btb.logic.predTypeComb
    fa.logic.gsPhtTaken0    := gsh.logic.phtTaken0
    fa.logic.gsPhtIndex0    := gsh.logic.phtIndex0
    fa.logic.gsPhtTaken1    := gsh.logic.phtTaken1
    fa.logic.gsPhtIndex1    := gsh.logic.phtIndex1
    gsh.logic.shiftValid    := fa.logic.gsShiftValid
    gsh.logic.shiftDir      := fa.logic.gsShiftDir
    gsh.gshareUpdate.valid   := rob.logic.gshareUpdateFlow.valid
    gsh.gshareUpdate.payload := rob.logic.gshareUpdateFlow.payload
    // STOP/fatal frontend quiesce is now carried by the ROB-owned
    // FrontendQuiesceService and captured at the FetchAlign boundary. There is no
    // sibling-driven active-level wire here: all full-core/test compositions inherit
    // the same cycle-exact contract automatically.
    eu0.issue << iq.issue(0)
    eu1.issue << iq.issue(1)
    iq.aluFastAcceptNext(0) := eu0.fastAcceptNext
    iq.aluFastAcceptNext(1) := eu1.fastAcceptNext
    eu0.flush := iq.flushPort
    eu1.flush := iq.flushPort
    // MOVE-from-SR int result needs the committed SR system byte: wire the ROB's
    // exc.ss.srSys to both ALU EUs (read-only; the only srSys writer is exception
    // entry/RTE, which fully flushes -> no in-flight fromSr observes a stale value).
    eu0.srSysIn := rob.logic.exc.ss.srSys
    eu1.srSysIn := rob.logic.exc.ss.srSys
    // SLOW-ALU (SHIFT, S3) dynamic wakeup: ONE IQ port per ALU EU (both EUs can
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
    // Precise-path SQ<->ROB loop (Task P2.5): SQ completion/fault-completion ->
    // ROB's 5th completion port + sqFaultCompletion; ROB head/preempt-pending ->
    // SQ's at-head drain trigger (via the LsEuPlugin pass-throughs).
    rob.logic.completion(4).valid   := lsEu.sqCompletionPort.valid
    rob.logic.completion(4).payload := lsEu.sqCompletionPort.payload
    rob.logic.sqFaultCompletion.valid   := lsEu.sqFaultCompletionPort.valid
    rob.logic.sqFaultCompletion.payload := lsEu.sqFaultCompletionPort.payload
    rob.logic.preciseDrainBusyIn        := lsEu.preciseDrainBusySig
    // Load-side sibling of preciseDrainBusyIn (see RobPlugin.scala's doc comment on
    // `inhibitedLoadBusyIn`): an inhibited load's device read is genuinely
    // outstanding -> block the ROB from recognizing a NEW interrupt/trace at the
    // head until it resolves, mirroring the store-drain interlock exactly.
    rob.logic.inhibitedLoadBusyIn       := lsEu.inhibitedLoadBusySig
    lsEu.robHeadIn           := rob.logic.h0
    lsEu.robHeadValidIn      := rob.logic.count > 0
    // Shallow SUPERSET of `interruptPending || tracePendingFire` -- see the long
    // rationale at `irqPreemptArmed`'s declaration in RobPlugin. Keeps the ROB's
    // 64-entry head muxes out of the LS EU launch decision (the core's -1.834 ns
    // worst path family) and breaks the Rob<->LsEu inhibited-load-busy ring.
    lsEu.irqPreemptPendingIn := rob.logic.irqPreemptArmed
    // The I-side counterpart of `robHeadValidIn`/`p4LaunchOk` above: an instruction
    // fetch into a CACHE-INHIBITED (device) page may not be issued speculatively
    // either. See SpeculativeFetchGate for the predicate and why it is the right
    // re-expression of "at the ROB head" for something that has no ROB entry yet.
    SpeculativeFetchGate.wire(host)
    // A debug automatic-halt (halt-after-N-macros) about to apply to the CURRENT
    // ROB head must ALSO stop an inhibited load at that head from launching its
    // device read -- the exact pair (`haltAfterDue || haltAfterRetireBlock`)
    // already gating RobPlugin's own `retire0` for this same successor. Without
    // this, the successor could launch its device read up to one cycle before
    // `haltAfterDue` itself becomes true (a RegNext-delayed comparison against the
    // macro-retire counter), then get discarded by the debug-recover flush that
    // follows -- the same silent double-device-read hazard as an interrupt
    // preempting an in-flight load, just triggered by the debug session instead.
    lsEu.debugHaltImminentIn := rob.logic.haltAfterDue || rob.logic.haltAfterRetireBlock

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
    // ── CPLX FP writeback lane ──
    // A structurally independent second lane out of the SAME EU: its own ROB completion
    // port (5), its own FP-data/FPCC dynamic wakeups, and its own enabled-trap fault port.
    // It shares only the CPLX issue port with DIV/MUL/CHK/CMP2/CHK2, so none of the wiring
    // above changes. (The 80-bit result cannot ride the existing 32-bit completion lane.)
    rob.logic.completion(5).valid   := divEu.fpCompletion.valid
    rob.logic.completion(5).payload := divEu.fpCompletion.payload
    iq.cplxFpWakeup.valid     := divEu.fpWakeup.valid
    iq.cplxFpWakeup.payload   := divEu.fpWakeup.payload
    iq.cplxFpccWakeup.valid   := divEu.fpccWakeup.valid
    iq.cplxFpccWakeup.payload := divEu.fpccWakeup.payload
    rob.logic.fpFaultCompletion.valid   := divEu.fpFault.valid
    rob.logic.fpFaultCompletion.payload := divEu.fpFault.payload
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
    // Task #194 (revives task #131's reverted attempt — see MmuControlPlugin's doc
    // comment): mmuEnable/urp/srp (and itt0/itt1/dtt0/dtt1) now have a REAL,
    // primary-IO-reachable driver through decode/rename/dispatch/commit (a
    // supervisor MOVEC to TC/URP/SRP/ITT*/DTT*), so the old registered-synth-top-
    // input override that used to drive them (to keep OOC synth from const-folding
    // mmuEnable to its False init and pruning the DTLB/ITLB/walkers out of the
    // netlist) is no longer needed — MOVEC's own decode/rename/dispatch/commit path
    // is itself the non-foldable, primary-IO-reachable driver the netlist needs. It
    // would also now ACTIVELY CONFLICT (two drivers into the same committed reg: the
    // internal MOVEC write and this external unconditional override).
    val mmuCtrl = host[m68k040.services.MmuControlService]
    // ── Interrupt inputs (simple protocol) from REGISTERED OOC inputs ──
    // Mirror the mmuEnable/rootPtr pattern: drive the InterruptControlPlugin's regs
    // from RegNext(in...) init 0 so the IPL-compare + vector-select cone is LIVE in
    // the netlist (OOC synth can't const-fold iplIn=0 and prune the recognition
    // logic). Idle (ipl=0) by default -> no interrupt unless the SoC raises iplIn.
    val intCtrlPlug = host[m68k040.exception.InterruptControlPlugin]
    val iplInPort      = in UInt (3 bits)
    val iackAvecIn     = in Bool ()
    val iackVectorIn   = in UInt (8 bits)
    // ── Debug-injected interrupts (OFF_IRQ_INJECT) ──────────────────────────────
    // Folded in HERE rather than at the socket: this build body already uses blocking
    // `host[...]` getters, so the provider's body is guaranteed to have run. Reading a
    // bare accessor from SocketTop instead produced a null at elaboration (the
    // cross-Fiber ordering race).
    //
    // MAX, never OR: IPL is a LEVEL, so OR-ing two 3-bit levels would fabricate a
    // priority neither source asked for (inject 1 during external 2 would give 3). Max
    // is what a real priority encoder does when two devices request at once.
    val dbgIrqOpt = host.get[m68k040.services.DebugIrqInjectService]
    val dbgIplLvl = dbgIrqOpt.map(_.irqInjectLevel).getOrElse(U(0, 3 bits))
    val iplMerged = Mux(dbgIplLvl > iplInPort, dbgIplLvl, iplInPort)
    intCtrlPlug.logic.iplIn      := RegNext(iplMerged) init 0
    // Clear the held debug request once the CPU actually TAKES an interrupt entry.
    dbgIrqOpt.foreach { d =>
      d.irqInjectAck := host.get[m68k040.socket.IplAckPlugin]
                            .map(_.logic.iplAck).getOrElse(False)
    }
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
    val exc   = rob.logic.exc
    exc.dcLoadRsp.valid   := dc.loadRsp.valid
    exc.dcLoadRsp.payload := dc.loadRsp.payload
    exc.dcLoadBusy        := dc.loadBusy
    // W13: the walker is a THIRD store client and the terminal ack is untagged, so it
    // must be demultiplexed before the exception sequencer consumes it. See
    // `LsEuPlugin.logic.excStoreAckOut`.
    exc.dcStoreAck        := lsEu.logic.excStoreAckOut
    // Task P4.5: the D-cache's async diagnostic-fault channel (a non-OKAY AXI
    // response on a trusted-cacheable-path transaction) latches a sticky,
    // non-interrupt-wakeable CORE HALT.
    // Task 11 adds a SECOND producer: a DTLB translation fault taken while the commit-side
    // sequencer is transferring an FSAVE/FRESTORE state frame. `coreHaltedIn` is a plain
    // `Bool` whose only consumer is the sticky `coreHalted` latch (which forces
    // `headReady` False, blocks `interruptPending`, and quiesces the frontend) -- nothing
    // downstream distinguishes WHICH producer fired, so a plain OR is exactly right and a
    // priority/first-wins encoding would buy nothing. See ExceptionUnit's `F_HALT` for why
    // a translation fault escalates here instead of taking a precise access fault.
    // D20: the merge arbiter's bounded-grant expiry is a THIRD halt producer, present only
    // in a socket build. `host.get` keeps M68kFullCoreSynth (which has no arbiter)
    // bit-identical -- the same Option shape FetchAlignPlugin uses for
    // FrontendQuiesceService at :89-99.
    val arbWedge = host.get[m68k040.socket.AxiDMergePlugin] match {
      case Some(a) => a.logic.wedge
      case None    => False
    }
    // D14/D28: resolved once here (rather than separately at the a7Wr site below) since
    // both the halt fold immediately below and the a7Wr drive further down need it, and a
    // single host.get keeps the two consumers from ever disagreeing about whether a
    // ResetVectorPlugin is present in this build.
    val rv = host.get[m68k040.socket.ResetVectorPlugin] match {
      case Some(p) => p.logic
      case None    => null
    }
    val rvHalt = if (rv != null) rv.haltPulse else False
    // W26: the walker/D-cache merge point is a FIFTH halt producer. It has to be its own
    // producer rather than folded into `arbWedge`, because a wedge there raises no AXI
    // grant at all and is invisible to every other watchdog in the core.
    val walkWedge = lsEu.logic.walkerPortWedge
    // 2026-09-09: a DOUBLE BUS FAULT is a SIXTH halt producer -- see `ExceptionUnit`'s
    // `dblFault`. It is the only one that is architectural rather than diagnostic: a real
    // 68040 halts on a bus error taken during exception processing, so this is not a
    // watchdog but the specified behaviour.
    rob.logic.coreHaltedIn := dc.diagFault || exc.fsXlateFault || arbWedge || rvHalt ||
                              walkWedge || exc.dblFault
    // D28: priority when several fire on the same cycle is stated here rather than left to
    // elaboration order. The D-cache's diagnostic fault wins because it is the one with a
    // sub-code (DcachePlugin's private diagFaultKind) that further localises the failure;
    // the arbiter wedge is last because it is the most likely CONSEQUENCE of the others.
    rob.logic.haltReasonIn := Mux(dc.diagFault,
      U(m68k040.socket.HaltReason.DCACHE_DIAG, m68k040.socket.HaltReason.W bits),
      Mux(exc.fsXlateFault,
        U(m68k040.socket.HaltReason.FS_XLATE, m68k040.socket.HaltReason.W bits),
        Mux(rvHalt,
          U(m68k040.socket.HaltReason.RESET_VECTOR, m68k040.socket.HaltReason.W bits),
          Mux(arbWedge,
            U(m68k040.socket.HaltReason.ARBITER_WEDGE, m68k040.socket.HaltReason.W bits),
            Mux(walkWedge,
              U(m68k040.socket.HaltReason.WALKER_PORT_WEDGE, m68k040.socket.HaltReason.W bits),
              // Last in the chain only because it is the newest producer; it cannot
              // collide with the others in practice (the exception sequencer is the sole
              // requester of the load port while it is active).
              Mux(exc.dblFault,
                U(m68k040.socket.HaltReason.DOUBLE_FAULT, m68k040.socket.HaltReason.W bits),
                U(m68k040.socket.HaltReason.NONE, m68k040.socket.HaltReason.W bits)))))))
    lsEu.excActive          := excActive
    // W19: closes table-walker admission to the D-cache ports across the commit-time
    // sysOp maintenance quiesce (`S_DRAIN`/`S_APPLY`). See `ExceptionUnit.quiesceHoldOut`.
    lsEu.quiesceHold        := exc.quiesceHoldOut
    lsEu.excLoadCmdValid    := exc.dcLoadCmd.valid
    lsEu.excLoadCmdVaddr    := exc.dcLoadCmd.payload.vaddr
    lsEu.excLoadCmdPaddr    := exc.dcLoadCmd.payload.paddr
    lsEu.excLoadCmdSize     := exc.dcLoadCmd.payload.size
    exc.dcLoadCmd.ready     := lsEu.excLoadCmdReady
    // ── Task 11: the exception sequencer's own D-side DTLB port ──────────────────
    // REQUEST goes through the LS EU's `excActive` MUX (the port is provably idle there);
    // RESPONSE is read straight off `DTranslationService`, exactly like `exc.dcLoadRsp` is
    // read straight off the D-cache rather than through that same MUX.
    lsEu.excXlateValid      := exc.dxReqValid
    lsEu.excXlateVpn        := exc.dxReqVpn
    lsEu.excXlateWrite      := exc.dxReqWrite
    lsEu.excXlateToken      := U(exc.ExcDtlbToken, m68k040.cache.DTranslationToken.Width bits)
    exc.dxReqReady          := lsEu.excXlateReady
    exc.dxRspValid          := dtlb.rsp.valid
    exc.dxRspPpn            := dtlb.rsp.payload.ppn
    exc.dxRspFault          := dtlb.rsp.payload.fault
    exc.dxRspToken          := dtlb.rsp.payload.token
    lsEu.excStoreValid      := exc.dcStore.valid
    lsEu.excStorePayload    := exc.dcStore.payload
    exc.dcStore.ready       := lsEu.excStoreReady
    exc.sqDrained           := lsEu.sqEmptySig
    // Task P5.4: the commit-time sysOp path (S_DRAIN) additionally waits for the
    // D-cache datapath itself to go idle before applying -- CPUSH/CINV's maintenance
    // walk takes the D-cache's shared array read port and AXI write channels, which
    // `excActive` alone does NOT free (an older committed store can still be draining,
    // and a refill/eviction accepted before the flush can still be in flight).
    exc.dcQuiesced          := dc.maintQuiesced
    // Task P5.5: the commit-time CPUSH/CINV dispatch itself. S_APPLY pulses
    // `maintCmdOut` for one cycle (having already waited on S_DRAIN's
    // sqDrained && dcQuiesced), then holds in S_MAINTWAIT until `maintDone` reports the
    // walk finished.
    dc.maintCmd.valid := exc.maintCmdOut.valid
    dc.maintCmd.payload := exc.maintCmdOut.payload
    if (debugStage >= 3) {
      val debugMaintActive = RegInit(False)
      val debugMaintFailed = RegInit(False)
      val debugMaintSel = Reg(UInt(2 bits)) init 0
      val debugMaintDone = debugMaintActive && dc.maintDone
      when(debugMaintCmdIn.valid) {
        debugMaintActive := True
        debugMaintFailed := False
        debugMaintSel := debugMaintCmdIn.payload.sel
      }
      when(debugMaintActive && dc.maintError) { debugMaintFailed := True }
      when(debugMaintDone) {
        debugMaintActive := False
        debugMaintFailed := False
      }
      debugMaintDonePulse := debugMaintDone && debugMaintSel(1)
      debugQuiescedOut := host[LsEuService].sqDrained && dc.maintQuiesced && !debugMaintActive
      debugMaintDoneOut := debugMaintDone
      debugMaintErrorOut := debugMaintDone && (debugMaintFailed || dc.maintError)
      when(debugMaintCmdIn.valid) {
        dc.maintCmd.valid := True
        dc.maintCmd.payload.push := debugMaintCmdIn.payload.push
        dc.maintCmd.payload.invalidate := debugMaintCmdIn.payload.invalidate
        dc.maintCmd.payload.scope := 3 // all
        dc.maintCmd.payload.sel := debugMaintCmdIn.payload.sel
        dc.maintCmd.payload.addr := 0
      }
    } else {
      debugQuiescedOut := True
      debugMaintDoneOut := False
      debugMaintErrorOut := False
    }
    exc.maintDoneIn         := dc.maintDone
    GenerationFlags.simulation {
      assert(!(debugMaintCmdIn.valid && exc.maintCmdOut.valid),
        "BackendWiringPlugin: debug and architectural cache maintenance collided", FAILURE)
    }
    // A7 (arch-15) write on exc/RTE A7 change; the SAME port also serves a commit-time
    // SYSTEM op's READ direction (MOVE-USP/MOVEC Rc->Rn writes an arbitrary arch-Rn).
    // sysRegWrite fires in S_APPLY, a7Write in S_REDIR (consecutive -> no port collision).
    // The a7Write address uses committedPhysA7 (commReg(15)) so the write is correct
    // even when arch-15 has been renamed by an OoO A7 write (move ...,%sp / push / bsr).
    // When A7 is unrenamed, committedPhysA7 == 15, so this is identical to the old U(15).
    // D14: the initial SSP reaches committed A7 through THIS existing shared int-PRF write
    // port, as a new HIGHEST-PRIORITY third source. Safe by construction and by the same
    // argument the existing direct writes rely on (the "task #176 safe fix pattern" of
    // writing committedPhysA7 directly, bypassing rename and the freelist): at the moment
    // the reset vector lands, no instruction has been fetched, so nothing is renamed, no
    // ROB entry exists, the ExceptionUnit is idle, and committedPhysA7 still equals 15.
    //
    // It reaches ISP for free: SystemState.scala:36 initialises srSys to 0x27 (S=1, M=0),
    // so A7 IS the ISP at reset, and the committedA7In readback below already routes it
    // into ss.isp on the following cycle with no extra wiring.
    // (`rv` is resolved once, up at the halt-fold site above, and reused here.)
    val rvSspValid = if (rv != null) rv.sspWriteValid else False
    val rvSspData  = if (rv != null) rv.sspData       else U(0, 32 bits)
    a7Wr.valid   := rvSspValid || exc.a7WriteValid || exc.sysRegWriteValid
    a7Wr.address := Mux(rvSspValid, host[RenameStage].committedPhysA7.resize(a7Wr.address.getWidth),
                    Mux(exc.sysRegWriteValid, exc.sysRegWritePhys.resize(a7Wr.address.getWidth),
                                              host[RenameStage].committedPhysA7.resize(a7Wr.address.getWidth)))
    a7Wr.data    := Mux(rvSspValid, rvSspData.asBits,
                    Mux(exc.sysRegWriteValid, exc.sysRegWriteData.asBits, exc.a7WriteData.asBits))
    GenerationFlags.simulation {
      assert(!(rvSspValid && (exc.a7WriteValid || exc.sysRegWriteValid)),
        "the reset-vector SSP write collided with an ExceptionUnit A7/sysReg write", FAILURE)
    }
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
    // Architectural FMOVE to/from FPSR (Task 9): the FPCC nibble of FPSR is RENAMED
    // (spec Decision 4) and therefore lives in the FPCC PRF, not in FpuControlPlugin.
    // Read it back at the committed mapping every cycle for the FPSR READ splice, and
    // write that same committed physical register directly on an FPSR WRITE -- the exact
    // nzvcWr/a7Rd pattern above, safe for the same reason (it fires only inside the
    // serializing S_APPLY, with retire0/retire1 blocked and the EUs flushed).
    fpccRd.addr    := host[RenameStage].committedPhysFpcc.resize(fpccRd.addr.getWidth)
    exc.committedFpccIn := fpccRd.data
    // Task 9b: the CPLX EU's live FPCR/FPSR/FPIAR reads (DecOp.FPCTRLRD, the FMOVEM
    // control-register LIST form's store direction). Driven from here rather than read
    // directly out of the service inside DivEuPlugin, because FpuControlPlugin publishes
    // those registers from inside its OWN `during build` Area -- a consumer plugin whose
    // `logic` elaborates first would read null. Same order-proof seam as
    // `exc.committedFpccIn` / `exc.committedA7In` just above.
    //
    // Task 14c added the rest of the FP-control seam on the SAME wire (rounding mode,
    // exception-enable byte, and the EU's FPSR exception-status accrual back-channel);
    // `DivEuPlugin.wireFpControl` is the single shared definition of all of it.
    val fpCtlSvc = host[m68k040.services.FpuControlService]
    m68k040.execute.DivEuPlugin.wireFpControl(divEu, fpCtlSvc)
    fpccWr.valid   := exc.fpccWriteValid
    fpccWr.address := host[RenameStage].committedPhysFpcc.resize(fpccWr.address.getWidth)
    fpccWr.data    := exc.fpccWriteData

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

  override def quiesced: Bool = debugQuiescedOut
  override def done: Bool = debugMaintDoneOut
  override def error: Bool = debugMaintErrorOut
  override def request(cmd: Flow[DebugMemoryCommand]): Unit = debugMaintCmdIn := cmd
}

/** Full integrated execute core (slice 3c) for OOC synthesis: frontend → rename →
  * dispatch → ROB + IssueQueue → 2 ALU EUs → 3 PRFs. SynthProbePlugin anchors the
  * CommitTrace outputs so the retire path (and the whole pipeline feeding it) is
  * not pruned. I-cache AXI master + redirect/flush slave ports surface as top IO. */
object GenFullCoreSynthVerilog {

  /** DBG_BUILD_ID is supplied by the SoC build (spec 3.1). It enters as a plugin
    * constructor parameter -- not a Global key (spec 0.9) and not a socket port -- so a
    * bitstream build can stamp it without touching RTL. The value is HEXADECIMAL, with
    * or without a leading 0x; anything else is a hard error rather than a silent 0,
    * because a wrong build ID is worse than none (it makes a stale bitstream look fresh). */
  def readDbgBuildIdEnv(): BigInt = sys.env.get("DBG_BUILD_ID") match {
    case None    => BigInt(0)
    case Some(s) =>
      val hex = s.trim.stripPrefix("0x").stripPrefix("0X")
      require(hex.nonEmpty && hex.forall(c => "0123456789abcdefABCDEF".contains(c)),
        s"DBG_BUILD_ID must be hexadecimal (got '$s')")
      BigInt(hex, 16)
  }

  /** Shared plugin-list construction for both the default (VioProbePlugin disabled) and
    * VIO-enabled generation targets (spec 2026-08-18-vio-jtag-debug-design.md, V4). The two
    * generation objects MUST share every plugin construction except the `VioProbePlugin`
    * line and the SpinalHDL config's output/component name -- everything else routes
    * through here so the two targets can't silently drift apart.
    *
    * `debugEnable` gates `DebugCtrlPlugin` the same way `vioEnable` gates `VioProbePlugin`
    * (Stage 2 task 1). Defaults `true` so every EXISTING caller (both generation objects
    * below, before this parameter existed) is unaffected -- `GenFullCoreSynth`'s own output
    * is a byte-for-byte no-op of this change. */
  def buildWith(dbgBuildId: BigInt, vioEnable: Boolean, outputName: String,
                debugEnable: Boolean = true, debugStage: Int = 2): Unit = {
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
          // Non-renamed FP control state (FPCR / FPSR non-FPCC bytes / FPIAR), Task 9.
          // MUST be listed BEFORE RobPlugin, exactly like MmuControlPlugin: RobPlugin's
          // `during build` constructs the ExceptionUnit, which reads `fpuCtrl.fpsr`/`.fpcr`
          // EAGERLY (the FPSR-read FPCC splice is a plain `val`), so this plugin's own
          // `logic` Area has to have elaborated by then or those accessors are still null.
          new m68k040.execute.FpuControlPlugin(),
          new m68k040.exception.InterruptControlPlugin(),
          new ItlbPlugin(),
          new DtlbPlugin(),
          new IcachePlugin(),
          new DcachePlugin(),
          new BtbPlugin(),
          new FtbPlugin(),
          new m68k040.frontend.RasPlugin(),
          new m68k040.frontend.GsharePlugin(),
          new FetchAlignPlugin(enableFetchDirected = true),
          new DecodeStage(),
          new RenameStage(),
          new DispatchPlugin(),
          new RobPlugin(),
          new IssueQueuePlugin(),
          eu0, eu1, branchEu, lsEu, divEu,
          new RegFilePluginInt(),
          new RegFilePluginNzvc(),
          new RegFilePluginX(),
          // FP data (80x16) + FPCC (4x16) physical files. Deferred from the FP-regfile
          // task, which had no writer yet (RegFilePlugin asserts on a file with zero write
          // ports); the CPLX EU's FP writeback lane is that writer.
          new RegFilePluginFp(),
          new RegFilePluginFpcc(),
          new BackendWiringPlugin(eu0, eu1, branchEu, lsEu, divEu, debugStage = debugStage)
          ,
          // Stage 2 debug/control slave. Placed after RobPlugin because halt, step,
          // macro-count and reason reporting communicate exclusively through the
          // ROB-owned DebugCommitService.
          new m68k040.debug.DebugCtrlPlugin(buildId = dbgBuildId, stage = debugStage,
            enable = debugEnable)
          ,
          // Vivado VIO integration (design spec 2026-08-18-vio-jtag-debug-design.md, V4).
          // enable=false here -- M68kFullCoreSynth is the OOC/FMax gate target and its port
          // surface must not move. GenFullCoreSynthVioVerilog below is the enabled twin.
          new m68k040.debug.VioProbePlugin(enable = vioEnable, buildId = dbgBuildId)
        )).setDefinitionName(outputName)
      }
    println(s"Generated generated/$outputName.v")
  }

  def main(args: Array[String]): Unit = {
    buildWith(readDbgBuildIdEnv(), vioEnable = false, outputName = "M68kFullCoreSynth")
  }
}

/** Elaboration/synthesis gate for the next debug tranche. The default shipped target
  * remains Stage 2 until this Stage 3 configuration passes its complete functional and
  * timing gates. */
object GenFullCoreSynthStage3Verilog {
  def main(args: Array[String]): Unit = {
    GenFullCoreSynthVerilog.buildWith(
      GenFullCoreSynthVerilog.readDbgBuildIdEnv(), vioEnable = false,
      outputName = "M68kFullCoreSynthStage3", debugStage = 3)
  }
}

object GenFullCoreSynthStage4Verilog {
  def main(args: Array[String]): Unit = {
    GenFullCoreSynthVerilog.buildWith(
      GenFullCoreSynthVerilog.readDbgBuildIdEnv(), vioEnable = false,
      outputName = "M68kFullCoreSynthStage4", debugStage = 4)
  }
}

object GenFullCoreSynthStage5Verilog {
  def main(args: Array[String]): Unit = {
    GenFullCoreSynthVerilog.buildWith(
      GenFullCoreSynthVerilog.readDbgBuildIdEnv(), vioEnable = false,
      outputName = "M68kFullCoreSynthStage5", debugStage = 5)
  }
}

/** Emits M68kFullCoreSynthVio.v -- the ONLY generation target with VioProbePlugin(enable=true).
  * Exists solely for the Stage-1.5 cost-delta measurement (spec section 8.1) and to give
  * Task 5's netlist checker a real "enabled" artifact to test against. NOT a synthesis target
  * anyone gates FMax on -- that stays M68kFullCoreSynth, generated by the object above. */
object GenFullCoreSynthVioVerilog {
  def main(args: Array[String]): Unit = {
    GenFullCoreSynthVerilog.buildWith(
      GenFullCoreSynthVerilog.readDbgBuildIdEnv(), vioEnable = true, outputName = "M68kFullCoreSynthVio")
  }
}

/** Emits M68kFullCoreSynthNoDebug.v -- the debug-OFF twin, `DebugCtrlPlugin(enable = false)`
  * (Stage 2 task 1). Exists so the production/area/FMax-reference build can compile the whole
  * feature out (spec `2026-08-20-debug-ctrl-stage2-halt-resume-step.md` Global Constraints:
  * "Gate the whole feature behind a real ... `enable` flag so it can be compiled out of
  * area/FMax-reference builds"). NOT the default synthesis target -- that stays
  * `M68kFullCoreSynth` (`enable = true`, unaffected by this object's existence), generated by
  * `GenFullCoreSynthVerilog` above. */
object GenFullCoreSynthNoDebugVerilog {
  def main(args: Array[String]): Unit = {
    GenFullCoreSynthVerilog.buildWith(
      GenFullCoreSynthVerilog.readDbgBuildIdEnv(), vioEnable = false,
      outputName = "M68kFullCoreSynthNoDebug", debugEnable = false)
  }
}
