package m68k040.fuzz

import m68k040.M68kParams
import m68k040.core.ParamPlugin
import m68k040.mmu.{ItlbPlugin, DtlbPlugin, MmuControlPlugin}
import m68k040.cache.{IcachePlugin, DcachePlugin, DcacheService}
import m68k040.frontend.{FetchAlignPlugin, ComplexResumeActionPipe}
import m68k040.decode.DecodeStage
import m68k040.rename.RenameStage
import m68k040.rob.RobPlugin
import m68k040.execute.{AluEuPlugin, BranchEuPlugin, LsEuPlugin, DivEuPlugin}
import m68k040.execute.iq.{IssueQueuePlugin, IssueQueueService}
import m68k040.execute.regfile.{RegFilePluginFp, RegFilePluginFpcc, RegFilePluginInt,
  RegFilePluginNzvc, RegFilePluginX}
import m68k040.services.{RedirectService, DTranslationService}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database

/** Full-core DUT + backend wiring for the FUZZ lock-step harness.
  *
  * The DUT/wiring shape deliberately mirrors
  * `lockstep.ExecuteLockStepSpec.{BackendWiringPlugin, FullCoreDut}`. The program
  * memory is not duplicated: both harnesses route through
  * `AxiMemModel.attachProgramIFetch`. A later DUT-fixture refactor can hoist the
  * remaining wiring without changing the AXI model. */
class FuzzWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin, branchEu: BranchEuPlugin,
                       lsEu: LsEuPlugin, divEu: DivEuPlugin) extends FiberPlugin {
  var a7Wr: m68k040.execute.regfile.RegFileWritePort = null
  var seedWr: m68k040.execute.regfile.RegFileWritePort = null
  var a7Rd: m68k040.execute.regfile.RegFileReadPort = null
  // NZVC/X PRF write ports for RTE's CCR restore (task #176-regression): a DIRECT
  // write into whatever physical register nzvcRat/xRat's COMMITTED mapping currently
  // names, mirroring a7Wr's already-safe pattern -- see ExceptionUnit.scala's
  // rteNzvcWriteValid doc comment for why this replaced a rename-allocation approach.
  var nzvcWr: m68k040.execute.regfile.RegFileWritePort = null
  var xWr:    m68k040.execute.regfile.RegFileWritePort = null
  // FPCC PRF read/write ports for the architectural FMOVE to/from FPSR (Task 9):
  // exact NZVC analogue of nzvcWr -- read the committed mapping to splice the live
  // FPCC into an FPSR read, write that same committed mapping on an FPSR write.
  var fpccRd: m68k040.execute.regfile.RegFileReadPort  = null
  var fpccWr: m68k040.execute.regfile.RegFileWritePort = null
  during setup {
    a7Wr   = host[m68k040.execute.regfile.IntRegFileService].newWrite(latency = 1, sharingKey = "excA7")
    seedWr = host[m68k040.execute.regfile.IntRegFileService].newWrite(latency = 1, sharingKey = "excA7", priority = 1)
    a7Rd   = host[m68k040.execute.regfile.IntRegFileService].newRead(forceNoBypass = true)
    nzvcWr = host[m68k040.execute.regfile.NzvcRegFileService].newWrite(latency = 1, sharingKey = "rteNzvc")
    xWr    = host[m68k040.execute.regfile.XRegFileService].newWrite(latency = 1, sharingKey = "rteX")
  fpccRd = host[m68k040.execute.regfile.FpccRegFileService].newRead(forceNoBypass = true)
  fpccWr = host[m68k040.execute.regfile.FpccRegFileService].newWrite(latency = 1, sharingKey = "excFpcc")
  }
  val logic = during build new Area {
    val seedValid = in Bool (); val seedAddr = in UInt (6 bits); val seedData = in Bits (32 bits)
    seedValid.simPublic(); seedAddr.simPublic(); seedData.simPublic()
    seedWr.valid := seedValid; seedWr.address := seedAddr; seedWr.data := seedData
    val iq  = host[IssueQueueService]
    val rob = host[RobPlugin]
    eu0.issue << iq.issue(0)
    eu1.issue << iq.issue(1)
    iq.aluFastAcceptNext(0) := eu0.fastAcceptNext
    iq.aluFastAcceptNext(1) := eu1.fastAcceptNext
    eu0.flush := iq.flushPort
    eu1.flush := iq.flushPort
    eu0.srSysIn := rob.logic.exc.ss.srSys
    eu1.srSysIn := rob.logic.exc.ss.srSys
    iq.aluSlowWakeup(0).valid   := eu0.slowWakeup.valid
    iq.aluSlowWakeup(0).payload := eu0.slowWakeup.payload
    iq.aluSlowWakeup(1).valid   := eu1.slowWakeup.valid
    iq.aluSlowWakeup(1).payload := eu1.slowWakeup.payload
    branchEu.issue << iq.issue(2)
    rob.logic.branchCompletion.valid   := branchEu.completion.valid
    rob.logic.branchCompletion.payload := branchEu.completion.payload
    rob.logic.euFaultCompletion.valid   := branchEu.trapvFault.valid
    rob.logic.euFaultCompletion.payload := branchEu.trapvFault.payload
    rob.logic.completion(0).valid   := eu0.completion.valid
    rob.logic.completion(0).payload := eu0.completion.payload
    rob.logic.completion(1).valid   := eu1.completion.valid
    rob.logic.completion(1).payload := eu1.completion.payload
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
    wireCcr(3, divEu.logic.ccrObs)

    divEu.issue << iq.issue(4)
    divEu.cplxFlush := host[RedirectService].doFlush || rob.logic.excActive
    rob.logic.completion(3).valid   := divEu.completion.valid
    rob.logic.completion(3).payload := divEu.completion.payload
    iq.cplxWakeup.valid   := divEu.wakeup.valid
    iq.cplxWakeup.payload := divEu.wakeup.payload
    // Dynamic NZVC wakeup (task #167): a completing CPLX flag-writer (DIV/MUL/CHK/
    // CMP2/CHK2) wakes a flag-reader of its NZVC. Mirrors FullCoreSynth.scala's wiring
    // (this class is a DELIBERATE DUPLICATION, per the class-doc comment above).
    iq.cplxNzvcWakeup.valid   := divEu.wakeupNzvc.valid
    iq.cplxNzvcWakeup.payload := divEu.wakeupNzvc.payload
    // CPLX FP writeback lane (mirrors top/FullCoreSynth): its own ROB completion port,
    // FP-data/FPCC dynamic wakeups, and enabled-trap fault port. Required even for a DUT
    // that runs no FP code today -- an F-line encoding that decode now emits as a real FP
    // uop would otherwise never complete and would wedge the ROB head.
    rob.logic.completion(5).valid   := divEu.fpCompletion.valid
    rob.logic.completion(5).payload := divEu.fpCompletion.payload
    iq.cplxFpWakeup.valid     := divEu.fpWakeup.valid
    iq.cplxFpWakeup.payload   := divEu.fpWakeup.payload
    iq.cplxFpccWakeup.valid   := divEu.fpccWakeup.valid
    iq.cplxFpccWakeup.payload := divEu.fpccWakeup.payload
    rob.logic.fpFaultCompletion.valid   := divEu.fpFault.valid
    rob.logic.fpFaultCompletion.payload := divEu.fpFault.payload
    when(divEu.euFault.valid) {
      rob.logic.euFaultCompletion.valid   := True
      rob.logic.euFaultCompletion.payload := divEu.euFault.payload
    }

    lsEu.issue << iq.issue(3)
    rob.logic.completion(2).valid   := lsEu.completion.valid
    rob.logic.completion(2).payload := lsEu.completion.payload
    rob.logic.lsFaultCompletion.valid   := lsEu.faultCompletion.valid
    rob.logic.lsFaultCompletion.payload := lsEu.faultCompletion.payload
    // Precise-path SQ<->ROB loop (Task P2.5, mirrors top/FullCoreSynth).
    rob.logic.completion(4).valid   := lsEu.sqCompletionPort.valid
    rob.logic.completion(4).payload := lsEu.sqCompletionPort.payload
    rob.logic.sqFaultCompletion.valid   := lsEu.sqFaultCompletionPort.valid
    rob.logic.sqFaultCompletion.payload := lsEu.sqFaultCompletionPort.payload
    rob.logic.preciseDrainBusyIn        := lsEu.preciseDrainBusySig
    rob.logic.inhibitedLoadBusyIn       := lsEu.inhibitedLoadBusySig
    lsEu.robHeadIn           := rob.logic.h0
    lsEu.robHeadValidIn      := rob.logic.count > 0
    // I-side cache-inhibited speculation gate — the fetch-side counterpart of
    // `robHeadValidIn`/`p4LaunchOk` above (mirrors top/FullCoreSynth.scala's
    // BackendWiringPlugin). See m68k040.top.SpeculativeFetchGate.
    m68k040.top.SpeculativeFetchGate.wire(host)
    lsEu.irqPreemptPendingIn := rob.logic.interruptPending || rob.logic.tracePendingFire
    lsEu.debugHaltImminentIn := rob.logic.haltAfterDue || rob.logic.haltAfterRetireBlock
    iq.lsWakeup.valid   := lsEu.wakeup.valid
    iq.lsWakeup.payload := lsEu.wakeup.payload
    iq.lsNzvcWakeup.valid   := lsEu.wakeupNzvc.valid
    iq.lsNzvcWakeup.payload := lsEu.wakeupNzvc.payload
    lsEu.sqCommit.valid   := rob.logic.retire0
    lsEu.sqCommit.payload := rob.logic.h0
    lsEu.sqCommitB.valid   := rob.logic.retire1
    lsEu.sqCommitB.payload := rob.logic.h1
    val excEntering = rob.logic.excActive && !RegNext(rob.logic.excActive, init = False)
    lsEu.sqFlush          := host[RedirectService].doFlush || excEntering

    val dtlb = host[m68k040.mmu.DtlbPlugin]
    dtlb.umAccessRobId := lsEu.xlateRobId
    dtlb.umCommitValid := rob.logic.retire0
    dtlb.umCommitBValid := rob.logic.retire1
    dtlb.umCommitBId    := rob.logic.h1
    dtlb.umCommitId    := rob.logic.h0
    dtlb.umFlush       := host[RedirectService].doFlush
    val itlb = host[m68k040.mmu.ItlbPlugin]
    itlb.umAccessRobId := U(0, 6 bits)
    itlb.umCommitValid := rob.logic.retire0
    itlb.umCommitBValid := rob.logic.retire1
    itlb.umCommitBId    := rob.logic.h1
    itlb.umCommitId    := rob.logic.h0
    itlb.umFlush       := host[RedirectService].doFlush
    dtlb.flushAll      := rob.logic.exc.sysFlushAllValid
    itlb.flushAll      := rob.logic.exc.sysFlushAllValid

    val doFlush = host[RedirectService].doFlush
    val flushPc = host[RedirectService].flushPc
    val excActive = rob.logic.excActive
    val pipeFlush = doFlush || excActive
    val decodeUop = host[m68k040.services.DecodeUopService]
    iq.flushPort := pipeFlush
    // ── Two-tier reschedule wiring (2026-09-04) — MUST MIRROR
    // top/FullCoreSynth.scala's BackendWiringPlugin. Tier 1 drives ONLY the
    // frontend redirect, the pre-rename skid flush and the RAS checkpoint; it
    // never reaches iq.flushPort / RenameStage.pipeFlush / sqFlush / umFlush.
    val earlyFire  = rob.logic.earlyFire
    val feSuppress = rob.logic.earlySuppressFe && !excActive
    val feFlush    = (doFlush && !feSuppress) || excActive || earlyFire
    decodeUop.pipeFlush := feFlush
    decodeUop.backendFlush := pipeFlush        // FP wide-imm side table: backend-owned entries (Tier 2 only)
    host[RenameStage].logic.pipeFlush := doFlush || excActive
    host[RenameStage].logic.allocHalt := rob.logic.earlyPend
    // Front-end complex-packet resume (task #178, ported-tests cluster 11): a genuinely-
    // `complex` predecode packet permanently stalls FetchAlignPlugin until its `resume`
    // port fires, but `resume` is a TEST-BOOT-ONLY external port (poked directly by the
    // testbench for the initial fetch redirect) -- driving it here would fight that poke.
    // `mispredictRedirect` is the established INTERNAL wiring point instead (already
    // designed as a directionless, sibling-plugin-driven Flow) and its handler already does
    // exactly what a `resume` would (ibuf flush + stalled:=False + decodePc:=newPc) WITHOUT
    // touching `pipeFlush` (driven separately below), so the in-flight µcode engine
    // correctly keeps running -- only the front-end fetch pointer is unstuck. Priority:
    // a real doFlush (branch mispredict / exception) wins over a same-cycle resume (should
    // never coincide in practice; doFlush is the architecturally "real" redirect).
    val frontendResume = ComplexResumeActionPipe(decodeUop.complexResume, feFlush)
    val faRedir = host[FetchAlignPlugin].logic.mispredictRedirect
    faRedir.valid   := (doFlush && !feSuppress) || earlyFire || frontendResume.valid
    faRedir.payload := Mux(doFlush && !feSuppress, flushPc,
                       Mux(earlyFire, rob.logic.earlyPcReg, frontendResume.payload))

    val faBtb = host[FetchAlignPlugin]
    val btb   = host[m68k040.frontend.BtbPlugin]
    val ftb   = host[m68k040.frontend.FtbPlugin]
    // Both invalidate sources: the external boot/reset port AND the internal CPUSH/CINV
    // maintenance pulse (P5.5 follow-up — a stale BTB entry can redirect fetch on a
    // non-branch after SMC, with nothing downstream to catch it).
    val predictorInvalidate = host[IcachePlugin].logic.invalidateAll ||
                              host[IcachePlugin].logic.maintInvalidateAll
    btb.logic.invalidateAll := predictorInvalidate
    ftb.logic.invalidateAll := predictorInvalidate
    btb.logic.queryPc     := faBtb.logic.btbQueryPc0
    btb.logic.queryValid  := faBtb.logic.btbQueryValid0
    faBtb.logic.btbPredTaken0  := btb.logic.predTakenComb
    faBtb.logic.btbPredTarget0 := btb.logic.predTargetComb
    val ras   = host[m68k040.frontend.RasPlugin]
    ras.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll
    ras.logic.pushValid     := faBtb.logic.rasPushValid
    ras.logic.pushRetPc     := faBtb.logic.rasPushRetPc
    ras.logic.popValid      := faBtb.logic.rasPopValid
    faBtb.logic.rasPredValid  := ras.logic.predValid
    faBtb.logic.rasPredTarget := ras.logic.predTarget
    // Rollback-on-flush (mirrors FullCoreSynth.BackendWiringPlugin's RAS wiring --
    // see Ras.scala's doc comment for the design).
    val rasCheckpointRestore = (doFlush && !feSuppress) || earlyFire || faBtb.logic.ftqMismatch
    // checkpointSave is now an ARM whose copy lands the cycle after, and the RAS
    // itself gates it with !checkpointRestore (restore wins by construction), so
    // this driver is a BARE REGISTER OUTPUT: `rob.logic.countIsZero` is a bit-exact
    // registered restatement of `count === 0` (see RobPlugin), not an approximation.
    // Deliberately NO combinational term here -- the point of the 2026-09-15 FMax
    // change is that the long ROB->frontend route into 500+ clock-enable pins
    // starts at a flop Q with the whole period in front of it.
    ras.logic.checkpointSave    := rob.logic.countIsZero
    ras.logic.checkpointRestore := rasCheckpointRestore

    val gsh   = host[m68k040.frontend.GsharePlugin]
    gsh.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll
    gsh.logic.queryPc0      := faBtb.logic.btbQueryPc0
    gsh.logic.queryValid0   := faBtb.logic.btbQueryValid0
    gsh.logic.queryPc1      := faBtb.logic.btbQueryPc1
    gsh.logic.queryValid1   := faBtb.logic.btbQueryValid1
    faBtb.logic.gsBtbHit0   := btb.logic.predHitComb
    faBtb.logic.gsBtbType0  := btb.logic.predTypeComb
    faBtb.logic.gsPhtTaken0 := gsh.logic.phtTaken0
    faBtb.logic.gsPhtIndex0 := gsh.logic.phtIndex0
    faBtb.logic.gsPhtTaken1 := gsh.logic.phtTaken1
    faBtb.logic.gsPhtIndex1 := gsh.logic.phtIndex1
    gsh.logic.shiftValid    := faBtb.logic.gsShiftValid
    gsh.logic.shiftDir      := faBtb.logic.gsShiftDir
    gsh.gshareUpdate.valid   := rob.logic.gshareUpdateFlow.valid
    gsh.gshareUpdate.payload := rob.logic.gshareUpdateFlow.payload

    val dc    = host[DcacheService]
    val exc   = rob.logic.exc
    exc.dcLoadRsp.valid   := dc.loadRsp.valid
    exc.dcLoadRsp.payload := dc.loadRsp.payload
    exc.dcLoadBusy        := dc.loadBusy
    // W13: the walker is a THIRD store client and the terminal ack is untagged, so it
    // must be demultiplexed before the exception sequencer consumes it. See
    // `LsEuPlugin.logic.excStoreAckOut`.
    exc.dcStoreAck        := lsEu.logic.excStoreAckOut
    lsEu.excActive            := excActive
    lsEu.excLoadCmdValid      := exc.dcLoadCmd.valid
    lsEu.excLoadCmdVaddr      := exc.dcLoadCmd.payload.vaddr
    lsEu.excLoadCmdPaddr      := exc.dcLoadCmd.payload.paddr
    lsEu.excLoadCmdSize       := exc.dcLoadCmd.payload.size
    exc.dcLoadCmd.ready       := lsEu.excLoadCmdReady
    lsEu.excStoreValid        := exc.dcStore.valid
    lsEu.excStorePayload      := exc.dcStore.payload
    exc.dcStore.ready         := lsEu.excStoreReady
    // Task 11 parity with FullCoreSynth: the exception sequencer's own D-side DTLB port
    // (request through the LS EU's `excActive` MUX, response straight off the service),
    // plus the FSAVE/FRESTORE translation-fault escalation into `coreHaltedIn`. Without
    // these the ExceptionUnit falls back to its identity-translation defaults, which
    // would silently make FSAVE/FRESTORE ignore the MMU in this harness.
    lsEu.excXlateValid        := exc.dxReqValid
    lsEu.excXlateVpn          := exc.dxReqVpn
    lsEu.excXlateWrite        := exc.dxReqWrite
    lsEu.excXlateToken        := U(exc.ExcDtlbToken, m68k040.cache.DTranslationToken.Width bits)
    exc.dxReqReady            := lsEu.excXlateReady
    exc.dxRspValid            := dtlb.rsp.valid
    exc.dxRspPpn              := dtlb.rsp.payload.ppn
    exc.dxRspFault            := dtlb.rsp.payload.fault
    exc.dxRspToken            := dtlb.rsp.payload.token
    // NOTE: deliberately only the Task-11 producer. This harness has never wired
    // `dc.diagFault` into `coreHaltedIn` (a pre-existing parity gap with FullCoreSynth,
    // not this task's business); adding it here would change ported-corpus behaviour for
    // unrelated reasons.
    // 2026-09-09: `exc.dblFault` is a SECOND producer and must be here too, for exactly
    // the reason the note above gives about the Task-11 one -- without it the DOUBLE
    // FAULT this harness can genuinely provoke (an all-zero page table makes the
    // vector-2 entry's OWN frame push fault) is invisible, and a halt that a real 68040
    // would assert simply does not happen in simulation. Same parity argument, same
    // shape; `dc.diagFault` deliberately stays out (see the note).
    rob.logic.coreHaltedIn    := exc.fsXlateFault || exc.dblFault
    exc.sqDrained             := lsEu.sqEmptySig
    // Task P5.4/P5.5 parity with FullCoreSynth (this block mirrors it by hand; the
    // P5.4 `dcQuiesced` line was missing here, leaving the ExceptionUnit default of a
    // hardcoded `True` and silently disabling S_DRAIN's D-cache-idle protection for
    // any CPUSH/CINV now that the maintenance command is actually wired below).
    exc.dcQuiesced            := dc.maintQuiesced
    dc.maintCmd               := exc.maintCmdOut
    exc.maintDoneIn           := dc.maintDone
    host[IcachePlugin].logic.maintInvalidateAll := exc.icMaintPulse
    a7Wr.valid   := exc.a7WriteValid || exc.sysRegWriteValid
    a7Wr.address := Mux(exc.sysRegWriteValid, exc.sysRegWritePhys.resize(a7Wr.address.getWidth),
                                              host[RenameStage].committedPhysA7.resize(a7Wr.address.getWidth))
    a7Wr.data    := Mux(exc.sysRegWriteValid, exc.sysRegWriteData.asBits, exc.a7WriteData.asBits)
    a7Rd.addr := host[RenameStage].committedPhysA7.resize(a7Rd.addr.getWidth)
    exc.committedA7In := a7Rd.data.asUInt
    nzvcWr.valid   := exc.rteNzvcWriteValid
    nzvcWr.address := host[RenameStage].committedPhysNzvc.resize(nzvcWr.address.getWidth)
    nzvcWr.data    := exc.rteNzvcWriteData
    xWr.valid      := exc.rteXWriteValid
    xWr.address    := host[RenameStage].committedPhysX.resize(xWr.address.getWidth)
    xWr.data       := exc.rteXWriteData.asBits
  // Architectural FMOVE to/from FPSR (Task 9): FPSR's FPCC nibble is RENAMED and lives
  // in the FPCC PRF, not in FpuControlPlugin -- read it back at the committed mapping
  // for the FPSR READ splice, write it directly there on an FPSR WRITE. Same pattern,
  // and same safety argument, as the nzvcWr direct write just above.
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
  // `DivEuPlugin.wireFpControl` is the single shared definition of all of it, so this
  // DUT cannot drift away from the synthesized core's FP semantics.
  val fpCtlSvc = host[m68k040.services.FpuControlService]
  m68k040.execute.DivEuPlugin.wireFpControl(divEu, fpCtlSvc)
  fpccWr.valid   := exc.fpccWriteValid
  fpccWr.address := host[RenameStage].committedPhysFpcc.resize(fpccWr.address.getWidth)
  fpccWr.data    := exc.fpccWriteData
  }
}

/** Full-core DUT (duplicated from ExecuteLockStepSpec.FullCoreDut — see the
  * duplication note on FuzzWiringPlugin). */
class FuzzCoreDut extends Component {
  val db    = new Database
  val host  = db on (new PluginHost)
  val ctrl   = new MmuControlPlugin
  // Non-renamed FP control state (FPCR / FPSR non-FPCC bytes / FPIAR), Task 9.
  // Declared HERE, next to MmuControlPlugin, and not further down with the register
  // files: Fiber `during build` tasks run in plugin-INSTANCE-CREATION order, and
  // RobPlugin's build constructs the ExceptionUnit, which reads fpuCtrl.fpsr/.fpcr
  // EAGERLY. Creating it after `rob` leaves those accessors null (guarded by an
  // explicit `require` in ExceptionUnit).
  val fpuCtl = new m68k040.execute.FpuControlPlugin
  val intCtrl = new m68k040.exception.InterruptControlPlugin
  val itlb   = new ItlbPlugin()
  val dtlb   = new DtlbPlugin()
  val icache = new IcachePlugin
  val dcache = new DcachePlugin()
  val btb    = new m68k040.frontend.BtbPlugin
  val ftb    = new m68k040.frontend.FtbPlugin
  val ras    = new m68k040.frontend.RasPlugin
  val gsh    = new m68k040.frontend.GsharePlugin
  val fa     = new FetchAlignPlugin(enableFetchDirected = true)
  val dec    = new DecodeStage
  val ren    = new RenameStage
  val disp   = new m68k040.dispatch.DispatchPlugin
  val rob    = new RobPlugin
  val iq     = new IssueQueuePlugin
  val eu0    = new AluEuPlugin
  val eu1    = new AluEuPlugin
  val branchEu = new BranchEuPlugin
  val lsEu   = new LsEuPlugin
  val divEu  = new DivEuPlugin
  val rfInt  = new RegFilePluginInt
  val rfNzvc = new RegFilePluginNzvc
  val rfX    = new RegFilePluginX
  val rfFp   = new RegFilePluginFp
  val rfFpcc = new RegFilePluginFpcc
  val wire   = new FuzzWiringPlugin(eu0, eu1, branchEu, lsEu, divEu)
  db.on { host.asHostOf(Seq[FiberPlugin](
    new ParamPlugin(M68kParams()),
    ctrl,
    // Non-renamed FP control state (Task 9). MUST precede `rob` in this Seq, exactly
    // like `ctrl`: RobPlugin's build constructs the ExceptionUnit, which reads
    // fpuCtrl.fpsr/.fpcr eagerly.
    fpuCtl,
    intCtrl,
    itlb,
    dtlb,
    icache, dcache, btb, ftb, ras, gsh, fa, dec, ren, disp, rob, iq, eu0, eu1, branchEu, lsEu, divEu,
    rfInt, rfNzvc, rfX, rfFp, rfFpcc, wire)) }
}

object FuzzDut {
  /** Behavioral AXI read-only memory holding the program image. The centralized
    * helper owns the I-side byte-swap convention. */
  def attachProgram(axi: Axi4ReadOnly, cd: ClockDomain, loadAddr: Long,
                    bytes: Vector[Int]): m68k040.sim.AxiMemModel =
    m68k040.sim.AxiMemModel.attachProgramIFetch(axi, cd, loadAddr, bytes)

  /** Task #211: like `attachProgram`, but the read agent injects a genuine AXI
    * DECERR (task #189's `BehavioralMem.decoded`/`injectBusErrors` model) for an
    * address outside the decoded range, instead of silently serving a zero-filled
    * OKAY read. Needed so an instruction fetch to genuinely-unmapped space (e.g.
    * exc_ifetch_bus_error.s's 0xAAAA0000 target) can exercise IcachePlugin's
    * REFILL resp-check at all. The memory-population convention is shared with
    * `attachProgram`; only the response code changes for a genuinely undecoded
    * address. */
  def attachProgramWithBusErrors(axi: Axi4ReadOnly, cd: ClockDomain, loadAddr: Long,
                                  bytes: Vector[Int]): m68k040.sim.AxiMemModel =
    m68k040.sim.AxiMemModel.attachProgramIFetch(
      axi, cd, loadAddr, bytes,
      cfg = m68k040.sim.AxiMemModelConfig(injectBusErrors = true))
}
