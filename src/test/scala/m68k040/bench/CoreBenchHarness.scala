package m68k040.bench

import m68k040.{M68kParams, M68kSim, VerilatorTest}
import m68k040.core.ParamPlugin
import m68k040.mmu.{ItlbPlugin, DtlbPlugin, MmuControlPlugin}
import m68k040.cache.{AxiIds, IcachePlugin, DcachePlugin, DcacheService}
import m68k040.frontend.{FetchAlignPlugin, BtbPlugin, ComplexResumeActionPipe}
import m68k040.decode.DecodeStage
import m68k040.rename.RenameStage
import m68k040.rob.RobPlugin
import m68k040.execute.{AluEuPlugin, BranchEuPlugin, LsEuPlugin}
import m68k040.execute.iq.{IssueQueuePlugin, IssueQueueService}
import m68k040.execute.regfile.{RegFilePluginFp, RegFilePluginFpcc, RegFilePluginInt,
  RegFilePluginNzvc, RegFilePluginX}
import m68k040.services.{RedirectService, DTranslationService}
import m68k040.lockstep.WhiteboxCapture
import m68k040.oracle.ProgramAssembler
import m68k040.sim.{AxiMemModel, AxiMemModelConfig, ConstFillSparseMemory, L2LatencyModel}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

/** Shared full-core Verilator bench harness: the DUT wiring, the AXI/L2 memory
  * model selection, the Kernel type and `runKernel` cycle/commit accounting, and
  * the IPC kernel corpus. Extracted verbatim from IpcBenchSpec so that BOTH the
  * IPC suite and the latency microbenchmark suite (MicrobenchSpec) drive the
  * SAME proven DUT and the SAME commit-stream cycle counting. No behavioural
  * change: IpcBenchSpec now mixes this in and keeps only its test body.
  */
trait CoreBenchHarness extends AnyFunSuite {

  // ── DUT (mirrors lockstep.ExecuteLockStepSpec.FullCoreDut byte-for-byte) ─────
  // Copied (not shared) because the lock-step DUT is a private inner class. The
  // wiring is the SAME proven full-core chain.

  class BackendWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin, branchEu: BranchEuPlugin, lsEu: LsEuPlugin, divEu: m68k040.execute.DivEuPlugin) extends FiberPlugin {
    var a7Wr: m68k040.execute.regfile.RegFileWritePort = null
    // NZVC/X PRF write ports for RTE's CCR restore (task #176-regression): a DIRECT
    // write into whatever physical register nzvcRat/xRat's COMMITTED mapping
    // currently names, mirroring a7Wr's already-safe pattern -- see
    // ExceptionUnit.scala's rteNzvcWriteValid doc comment.
    var nzvcWr: m68k040.execute.regfile.RegFileWritePort = null
    var xWr:    m68k040.execute.regfile.RegFileWritePort = null
  // FPCC PRF read/write ports for the architectural FMOVE to/from FPSR (Task 9):
  // exact NZVC analogue of nzvcWr -- read the committed mapping to splice the live
  // FPCC into an FPSR read, write that same committed mapping on an FPSR write.
  var fpccRd: m68k040.execute.regfile.RegFileReadPort  = null
  var fpccWr: m68k040.execute.regfile.RegFileWritePort = null
    during setup {
      a7Wr   = host[m68k040.execute.regfile.IntRegFileService].newWrite(latency = 1, sharingKey = "excA7")
      nzvcWr = host[m68k040.execute.regfile.NzvcRegFileService].newWrite(latency = 1, sharingKey = "rteNzvc")
      xWr    = host[m68k040.execute.regfile.XRegFileService].newWrite(latency = 1, sharingKey = "rteX")
  fpccRd = host[m68k040.execute.regfile.FpccRegFileService].newRead(forceNoBypass = true)
  fpccWr = host[m68k040.execute.regfile.FpccRegFileService].newWrite(latency = 1, sharingKey = "excFpcc")
    }
    val logic = during build new Area {
      val iq  = host[IssueQueueService]
      val rob = host[RobPlugin]
      eu0.issue << iq.issue(0)
      eu1.issue << iq.issue(1)
      iq.aluFastAcceptNext(0) := eu0.fastAcceptNext
      iq.aluFastAcceptNext(1) := eu1.fastAcceptNext
      eu0.flush := iq.flushPort
      eu1.flush := iq.flushPort
      eu0.srSysIn := U(0, 8 bits); eu1.srSysIn := U(0, 8 bits)  // MOVE-from-SR srSys input (unused here)
      // SLOW-ALU (SHIFT/BITFIELD, S3) dynamic wakeup — one IQ port per ALU EU (mirrors
      // top/FullCoreSynth). Inert for the shift-free IPC kernels, but required so a
      // shift's dependents could wake (consistency with the production wiring).
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
      }
      wireCcr(0, eu0.logic.ccrObs); wireCcr(1, eu1.logic.ccrObs); wireCcr(2, lsEu.logic.ccrObs)
      wireCcr(3, divEu.logic.ccrObs)

      lsEu.issue << iq.issue(3)
      rob.logic.completion(2).valid   := lsEu.completion.valid
      rob.logic.completion(2).payload := lsEu.completion.payload
      // LS order violation (idle unless the LS EU's `lsOooIssue` is on): an inhibited op
      // whose barrier a younger already-launched access violated. Recovered at retire.
      rob.logic.lsOrderViolation.valid   := lsEu.orderViolation.valid
      rob.logic.lsOrderViolation.payload := lsEu.orderViolation.payload
      rob.logic.lsReplay.valid         := lsEu.replayRequest.valid
      rob.logic.lsReplay.payload       := lsEu.replayRequest.payload
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

      // CPLX (DivEu) wiring (mirrors top/FullCoreSynth).
      divEu.issue << iq.issue(4)
      rob.logic.completion(3).valid   := divEu.completion.valid
      rob.logic.completion(3).payload := divEu.completion.payload
      iq.cplxWakeup.valid   := divEu.wakeup.valid
      iq.cplxWakeup.payload := divEu.wakeup.payload
      // Dynamic NZVC wakeup (task #167): mirrors top/FullCoreSynth.
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
      iq.lsWakeup.valid   := lsEu.wakeup.valid
      iq.lsWakeup.payload := lsEu.wakeup.payload
      // SPECULATIVE load wakeup (idle unless the LS EU's `specLoadWakeup` is on). One
      // cycle earlier than `wakeup` and a cache-hit PREDICTION; the IQ re-checks it
      // against `wakeup` before any consumer reaches an EU.
      iq.lsWakeupSpec.valid   := lsEu.wakeupSpec.valid
      iq.lsWakeupSpec.payload := lsEu.wakeupSpec.payload
      iq.lsNzvcWakeup.valid   := lsEu.wakeupNzvc.valid
      iq.lsNzvcWakeup.payload := lsEu.wakeupNzvc.payload
      lsEu.sqCommit.valid   := rob.logic.retire0
      lsEu.sqCommit.payload := rob.logic.h0
      lsEu.sqCommitB.valid   := rob.logic.retire1
      lsEu.sqCommitB.payload := rob.logic.h1
      lsEu.sqFlush          := host[RedirectService].doFlush

      val dtlb = host[m68k040.mmu.DtlbPlugin]
      dtlb.umAccessRobId := lsEu.xlateRobId
      // ...and: is this translation the COMMIT-TIME EXCEPTION SEQUENCER's? Its accesses
      // share this DTLB port while `excActive` is held, and they own no robId -- the
      // `xlateRobId` above is the squashed LS pipe's. Such a walk's U/M descriptor write
      // is born committed instead of waiting for an identity it borrowed.
      dtlb.umAccessPreCommitted := rob.logic.excActive
      dtlb.umCommitValid := rob.logic.retire0
      dtlb.umCommitBValid := rob.logic.retire1
      dtlb.umCommitBId    := rob.logic.h1
      dtlb.umCommitId    := rob.logic.h0
      dtlb.umFlush       := host[RedirectService].doFlush
      val itlb = host[m68k040.mmu.ItlbPlugin]
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
      decodeUop.backendFlush := pipeFlush      // FP wide-imm side table: backend-owned entries (Tier 2 only)
      host[RenameStage].logic.pipeFlush := doFlush || excActive
      host[RenameStage].logic.allocHalt := rob.logic.earlyPend
      // Front-end complex-packet resume (task #178, ported-tests cluster 11) -- see
      // DecodeStage.scala's `ucComplexResume` comment / FullCoreSynth.scala's mirror.
      val frontendResume = ComplexResumeActionPipe(decodeUop.complexResume, feFlush)
      val faRedir = host[FetchAlignPlugin].logic.mispredictRedirect
      faRedir.valid   := (doFlush && !feSuppress) || earlyFire || frontendResume.valid
      faRedir.payload := Mux(doFlush && !feSuppress, flushPc,
                         Mux(earlyFire, rob.logic.earlyPcReg, frontendResume.payload))

      // Fetch-time BTB wiring (slice 1): read off the fetch PC, invalidate off the
      // I-cache, feed the registered prediction into FetchAlign's predict input.
      val fa  = host[FetchAlignPlugin]
      val btb = host[m68k040.frontend.BtbPlugin]
      val ftb = host[m68k040.frontend.FtbPlugin]
      // Both invalidate sources: the external boot/reset port AND the internal
      // CPUSH/CINV maintenance pulse (P5.5 follow-up — a stale BTB entry can redirect
      // fetch on a non-branch after SMC, with nothing downstream to catch it).
      val predictorInvalidate = host[IcachePlugin].logic.invalidateAll ||
                                host[IcachePlugin].logic.maintInvalidateAll
      btb.logic.invalidateAll := predictorInvalidate
      ftb.logic.invalidateAll := predictorInvalidate
      btb.logic.queryPc     := fa.logic.btbQueryPc0
      btb.logic.queryValid  := fa.logic.btbQueryValid0
      fa.logic.btbPredTaken0  := btb.logic.predTakenComb
      fa.logic.btbPredTarget0 := btb.logic.predTargetComb
      // RAS (slice 2): drive push/pop, read the combinational predict.
      val rasP = host[m68k040.frontend.RasPlugin]
      rasP.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll
      rasP.logic.pushValid     := fa.logic.rasPushValid
      rasP.logic.pushRetPc     := fa.logic.rasPushRetPc
      rasP.logic.popValid      := fa.logic.rasPopValid
      fa.logic.rasPredValid    := rasP.logic.predValid
      fa.logic.rasPredTarget   := rasP.logic.predTarget
      // Rollback-on-flush (mirrors FullCoreSynth.BackendWiringPlugin's RAS wiring --
      // see Ras.scala's doc comment for the design).
      val rasCheckpointRestore = (doFlush && !feSuppress) || earlyFire || fa.logic.ftqMismatch
      // checkpointSave is now an ARM whose copy lands the cycle after, and the RAS
      // itself gates it with !checkpointRestore (restore wins by construction), so
      // this driver is a BARE REGISTER OUTPUT: `rob.logic.countIsZero` is a bit-exact
      // registered restatement of `count === 0` (see RobPlugin), not an approximation.
      // Deliberately NO combinational term here -- the point of the 2026-09-15 FMax
      // change is that the long ROB->frontend route into 500+ clock-enable pins
      // starts at a flop Q with the whole period in front of it.
      rasP.logic.checkpointSave    := rob.logic.countIsZero
      rasP.logic.checkpointRestore := rasCheckpointRestore
      // RAS flush-repair (RasPlugin `branchRepair`) -- same three bare register Qs the
      // shipping wiring drives; see BackendWiringPlugin.
      require(rob.rasBranchRepair == rasP.branchRepair, "rasBranchRepair must agree")
      if (rob.rasBranchRepair) {
        rasP.logic.repairValid := rob.logic.earlyFire
        rasP.logic.repairKind  := rob.logic.rasRepairKind
        rasP.logic.repairData  := rob.logic.rasRepairData
      }

      // gshare (slice 3): query the PHT with the aligner slot PCs, feed BTB hit/brType
      // into FetchAlign (condBtbHit), shift the GHR on the emitted conditional, train at
      // retire (ROB GshareUpdateService). Invalidate (GHR clear) on the I-cache flush.
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
      // Whole-GHR repair trigger -- MUST MIRROR top/FullCoreSynth.scala's
      // BackendWiringPlugin. `doFlush` is the ROB's REGISTERED commit-flush pulse and
      // the GsharePlugin consumes it behind one more flop, so nothing here reaches the
      // redirect / ftbBlocked cone. Deliberately NOT feFlush/earlyFire/ftqMismatch --
      // see the FullCoreSynth mirror for why.
      gsh.logic.flushRepair   := doFlush
      gsh.gshareUpdate.valid   := rob.logic.gshareUpdateFlow.valid
      gsh.gshareUpdate.payload := rob.logic.gshareUpdateFlow.payload
      // Test-only observation: does late whole-history repair overlap branches
      // fetched after Tier 1, whose frontend survives the Tier-2 rollback?
      spinal.core.sim.SimPublic(gsh.logic.shiftValid, gsh.logic.shiftDir,
        gsh.logic.queryPc0, gsh.logic.flushRepair)
      // Test-only observation for the STALL BUDGET's `walk` category. The board's
      // OFF_PERF_STALL_WALK counts `perfLvlDtlbWalk || perfLvlItlbWalk`, each of which is
      // `RegNext(!walker.io.dbgPack(0))` = `RegNext(!fsm.isActive(IDLE))` -- and
      // `TableWalker.io.busy` is DEFINED as `!fsm.isActive(fsm.IDLE)` (TableWalker.scala:356),
      // the identical term. Tapping `io.busy` therefore matches the board counter's
      // definition exactly without adding a second copy of the expression. These are
      // nested-component ports, so they need an explicit SimPublic to survive Verilator;
      // this is a TEST harness, so nothing here reaches the synthesized core.
      spinal.core.sim.SimPublic(host[DtlbPlugin].logic.walker.io.busy,
        host[ItlbPlugin].logic.walker.io.busy)

      val dc    = host[DcacheService]
      val exc   = rob.logic.exc
      exc.dcLoadRsp.valid   := dc.loadRsp.valid
      exc.dcLoadRsp.payload := dc.loadRsp.payload
      exc.dcLoadBusy        := dc.loadBusy
      // W13: the walker is a THIRD store client and the terminal ack is untagged, so it
      // must be demultiplexed before the exception sequencer consumes it. See
      // `LsEuPlugin.logic.excStoreAckOut`.
      exc.dcStoreAck        := lsEu.logic.excStoreAckOut
    exc.dcStoreErr        := lsEu.logic.excStoreErrOut
      lsEu.excActive            := excActive
      lsEu.excLoadCmdValid      := exc.dcLoadCmd.valid
      lsEu.excLoadCmdVaddr      := exc.dcLoadCmd.payload.vaddr
      lsEu.excLoadCmdSize       := exc.dcLoadCmd.payload.size
      exc.dcLoadCmd.ready       := lsEu.excLoadCmdReady
      lsEu.excStoreValid        := exc.dcStore.valid
      lsEu.excStorePayload      := exc.dcStore.payload
      exc.dcStore.ready         := lsEu.excStoreReady
      exc.sqDrained             := lsEu.sqEmptySig
      // Task P5.4/P5.5 parity with FullCoreSynth (this block mirrors it by hand; the
      // P5.4 `dcQuiesced` line was missing here, leaving the ExceptionUnit default of a
      // hardcoded `True` and silently disabling S_DRAIN's D-cache-idle protection for
      // any CPUSH/CINV now that the maintenance command is actually wired below).
      exc.dcQuiesced            := dc.maintQuiesced
      dc.maintCmd               := exc.maintCmdOut
      exc.maintDoneIn           := dc.maintDone
      host[IcachePlugin].logic.maintInvalidateAll := exc.icMaintPulse
      // FullCoreSynth parity (2026-09-16): the SAME pulse must also drop the fetch
      // BUFFER, not just the I-cache array -- `ibuf` sits DOWNSTREAM of the cache and is
      // invalidated by nothing else, so the canonical store/CPUSHL/jump SMC sequence
      // executes pre-patch bytes straight out of it. This line exists in
      // FullCoreSynth.scala and was MISSING from all three simulation harnesses, so the
      // whole ifstage_smc_* / cpush corpus was passing against a DUT in which the fix was
      // absent -- i.e. it had zero simulation coverage and a regression deleting it from
      // FullCoreSynth would have been invisible to the test suite.
      host[FetchAlignPlugin].logic.icMaintFlush := host[IcachePlugin].logic.maintInvalidateAll
      a7Wr.valid   := exc.a7WriteValid
      a7Wr.address := U(15, a7Wr.address.getWidth bits)
      a7Wr.data    := exc.a7WriteData.asBits
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

  class FullCoreDut(alignedLoadFallThrough: Boolean = false,
                    earlyLsIntWakeup: Boolean = false,
                    sqSubwordForwarding: Boolean = false,
                    pairCorrectBranch: Boolean = false,
                    deferSlot1Conditional: Boolean = false,
                    trainSlot1Conditional: Boolean = false,
                    deferTakenSlot1Conditional: Boolean = false,
                    retainRedirectHistory: Boolean = false,
                    earlyStoreAddress: Boolean = false,
                    fuseLongMoveLoads: Boolean = false,
                    reserveLateStore: Boolean = false,
                    detachLateStore: Boolean = false,
                    forwardOnPublish: Boolean = false,
                    earlyLsNzvcWakeup: Boolean = false,
                    detachedStoreEntries: Int = 1,
                    earlyAutoStoreAddress: Boolean = false,
                    earlyStoreDataWake: Boolean = false,
                    loadBypassUnreadyLoad: Boolean = false,
                    earlyAutoAnWriteback: Boolean = false,
                    /** Slice D1.2 fill-forward (`DcachePlugin.fillForward`). Named
                      * explicitly here rather than taken from `ShippingCoreConfig`, per
                      * that file's rule that a bench must vary a knob by NAME. */
                    dcacheFillForward: Boolean = m68k040.top.ShippingCoreConfig.dcacheFillForward,
                    specLoadWakeup: Boolean = false,
                    rasBranchRepair: Boolean = false,
                    computeDirectTargets: Boolean = false,
                    deferSlot1Uncond: Boolean = false,
                    deferSlot1Dbcc: Boolean = false,
                    pcRangeEnable: Boolean = true,
                    icachePredecodeWords: Int = m68k040.cache.IcachePredecodeConfig.fromEnvironment) extends Component {
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
    val itlb   = new ItlbPlugin(victimEntries = m68k040.top.ShippingCoreConfig.itlbVictimEntries)
    val dtlb   = new DtlbPlugin()
    val icache = new IcachePlugin(icachePredecodeWords)
    val dcache = new DcachePlugin(fillForward = dcacheFillForward)
    val btb    = new BtbPlugin
    val ftb    = new m68k040.frontend.FtbPlugin
    val ras    = new m68k040.frontend.RasPlugin(branchRepair = rasBranchRepair)
    val gsh    = new m68k040.frontend.GsharePlugin(retainRedirectHistory = retainRedirectHistory)
    val fa     = new FetchAlignPlugin(enableFetchDirected = true,
      deferSlot1Conditional = deferSlot1Conditional, trainSlot1Conditional = trainSlot1Conditional,
      deferTakenSlot1Conditional = deferTakenSlot1Conditional,
      computeDirectTargets = computeDirectTargets, deferSlot1Uncond = deferSlot1Uncond,
      deferSlot1Dbcc = deferSlot1Dbcc)
    val dec    = new DecodeStage(allowSlot1Prediction = trainSlot1Conditional,
      fuseLongMoveLoads = fuseLongMoveLoads)
    val preparedCap = sys.env.get("IPC_PREPARED_RETIRE").map(_.toInt).getOrElse(0)
    val ren    = new RenameStage(
      retireWidth = if (preparedCap != 0) preparedCap else sys.env.get("IPC_RETIRE_WIDTH").map(_.toInt).getOrElse(2),
      preparedRetirement = preparedCap != 0)
    val disp   = new m68k040.dispatch.DispatchPlugin
    // `lsOooIssue` MUST be set on the ROB as well as the LS EU: the barrier's RECOVERY
    // half (`orderViolated` / `orderRedirect`) lives HERE, and with it False the LS EU's
    // `orderViolation` port is wired but IGNORED. `SocketTop` already drives all three
    // from one switch; every SIM harness omitted it, so the recovery had never been
    // exercised in simulation -- the same shape as the CPUSH `icMaintFlush` fix that was
    // wired only in FullCoreSynth and had zero sim coverage.
    val rob    = new RobPlugin(pairCorrectBranch = pairCorrectBranch, preparedRetireEntries = preparedCap,
      pcRangeEnable = pcRangeEnable, rasBranchRepair = rasBranchRepair,
      lsOooIssue = loadBypassUnreadyLoad)
    val iq     = new IssueQueuePlugin(earlyStoreAddress = earlyStoreAddress,
      earlyAutoStoreAddress = earlyAutoStoreAddress,
      loadBypassUnreadyLoad = loadBypassUnreadyLoad,
      specLoadWakeup = specLoadWakeup)
    val eu0    = new AluEuPlugin
    val eu1    = new AluEuPlugin
    val branchEu = new BranchEuPlugin
    val lsEu   = new LsEuPlugin(alignedLoadFallThrough = alignedLoadFallThrough,
      earlyIntWakeup = earlyLsIntWakeup, sqSubwordForwarding = sqSubwordForwarding,
      reserveLateStore = reserveLateStore, detachLateStore = detachLateStore,
      forwardOnPublish = forwardOnPublish, earlyNzvcWakeup = earlyLsNzvcWakeup,
      detachedStoreEntries = detachedStoreEntries, earlyAutoStoreAddress = earlyAutoStoreAddress,
      earlyAutoAnWriteback = earlyAutoAnWriteback,
      earlyStoreDataWake = earlyStoreDataWake,
      specLoadWakeup = specLoadWakeup,
      lsOooIssue = loadBypassUnreadyLoad)
    val divEu  = new m68k040.execute.DivEuPlugin
    val rfInt  = new RegFilePluginInt
    val rfNzvc = new RegFilePluginNzvc
    val rfX    = new RegFilePluginX
  val rfFp   = new RegFilePluginFp
  val rfFpcc = new RegFilePluginFpcc
    val wire   = new BackendWiringPlugin(eu0, eu1, branchEu, lsEu, divEu)
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

  /** Attach the assembled program to the I-cache AXI (low-byte-first convention,
    * mirrors ExecuteLockStepSpec.attachProgram). */
  // -- Memory-system model (env-selectable) ------------------------------------
  // IPC_MEM unset (or "zero"): zero-latency memory -- the historical default, and
  //   both I-side and D-side use the shared AxiMemModel with its default config.
  // IPC_MEM=l2 : the L2-faithful two-tier model (m68k040.sim.AxiMemModel), 5-cycle
  //   L2 hit / 70-cycle DDR by default; IPC_MEM=l2:<hit>:<dram> overrides. Applied
  //   to BOTH the D-side and the I-side (an I-fetch refill is the dominant memory
  //   stall for these kernels, so an I-side zero-latency model would make the
  //   measurement meaningless).
  val memCfg: AxiMemModelConfig = sys.env.get("IPC_MEM") match {
    case None | Some("") | Some("zero") => AxiMemModelConfig()
    case Some(spec) =>
      val parts = spec.split(':')
      require(parts(0) == "l2",
        s"unknown IPC_MEM=$spec (expected 'zero' or 'l2[:hit[:dram[:sets]]]')")
      val hit  = if (parts.length > 1) parts(1).toInt else 5
      val dram = if (parts.length > 2) parts(2).toInt else 70
      // FOURTH field: L2 SET COUNT. 0 (the default) is the historical UNBOUNDED L2 --
      // a residency set that is never evicted, so after one touch every line hits at
      // `hitCycles` forever and `dramCycles` only reaches COMPULSORY misses. Pass 4096
      // for the real 2 MB 8-way L2 (`l2c_defs.vh:25-32`). Any measurement of a lever
      // whose mechanism is "hide memory latency" must state which of the two it used.
      val sets = if (parts.length > 3) parts(3).toInt else 0
      AxiMemModelConfig(latency = L2LatencyModel(enabled = true, hitCycles = hit,
        dramCycles = dram, sets = sets))
  }
  // Five live 64-byte I-cache lines occupy ten beats on the core's 256-bit AXI.
  // Keep the legacy D-side capacity unchanged, but do not let the shared model's
  // old eight-beat default silently turn the I-side five-ID contract into four.
  val iMemCfg: AxiMemModelConfig = memCfg.copy(
    maxPendingBeats = scala.math.max(memCfg.maxPendingBeats, 2 * (1 + AxiIds.I_SPEC_SLOTS)))
  def memLabel: String =
    if (!memCfg.latency.enabled) "zero-latency (ideal memory)"
    else s"L2-faithful: L2 hit=${memCfg.latency.hitCycles}cyc, DDR=${memCfg.latency.dramCycles}cyc, 64B line" +
      (if (memCfg.latency.finiteCapacity)
         f", L2 ${memCfg.latency.capacityBytes / 1024}%d KiB ${memCfg.latency.ways}%d-way tree-PLRU"
       else ", L2 UNBOUNDED (never evicts -- dramCycles reaches compulsory misses ONLY)")

  def attachProgram(axi: Axi4ReadOnly, cd: ClockDomain, loadAddr: Long, bytes: Vector[Int]): Unit = {
    AxiMemModel.attachProgramIFetch(axi, cd, loadAddr, bytes, cfg = iMemCfg)
  }

  // ── per-kernel measurement result ───────────────────────────────────────────
  final case class RetiredBranchStats(pc: Long, branchType: Int, retired: Int,
                                      taken: Int, misses: Int, phtTrained: Int,
                                      untrainedMisses: Int)
  final case class RobCycle(occupancy: Int = 0, completePrefix: Int = 0,
                           completeYounger: Int = 0, headIncomplete: Boolean = false,
                           retires: Int = 0, branchPairPotential: Boolean = false,
                           headPc: Long = 0L, pairedBranch: Boolean = false,
                           prepareStart: Boolean = false, prepareAbort: Boolean = false,
                           preparedPublish: Int = 0)
  final case class PipelineProfile(branches: Vector[RetiredBranchStats],
                                   rob: Vector[RobCycle], robDepth: Int,
                                   firstCycle: Int, lastCycle: Int) {
    def retiredBranches: Int = branches.map(_.retired).sum
    def branchMisses: Int = branches.map(_.misses).sum
    // Aggregate prediction failure (direction OR target), not direction-only.
    def branchAccuracy: Option[Double] = if (retiredBranches == 0) None
      else Some(100.0 * (retiredBranches - branchMisses) / retiredBranches)
    def meanOccupancy: Double = rob.map(_.occupancy).sum.toDouble / rob.size
    def noPairCapacityCycles: Int = rob.count(_.occupancy > robDepth - 2)
    def headIncompleteCycles: Int = rob.count(_.headIncomplete)
    def nonemptyNoRetireCycles: Int = rob.count(s => s.occupancy > 0 && s.retires == 0)
    def completedBacklogCycles: Int = rob.count(s => s.headIncomplete && s.completeYounger > 0)
    def dualWithExtraCompleteCycles: Int = rob.count(s => s.retires == 2 && s.completePrefix > 2)
    def branchPairPotentialCycles: Int = rob.count(_.branchPairPotential)
    def pairedBranchCycles: Int = rob.count(_.pairedBranch)
  }

  /** PER-KERNEL CYCLE DECOMPOSITION ("stall budget"), sim-only and OPT-IN via
    * `IPC_STALL_BUDGET=1`.
    *
    * WHY THIS EXISTS. Three separate levers were recorded as "no effect" because the
    * only thing the bench reported was WINDOW CYCLES, and cycles were bit-identical:
    * ALU-class speculative wakeup (2,048-3,106 deferred selects granted), the announce
    * family (gap bucket ~3,900 -> ~30 windows), and the confirm-rate lever (0.0% ->
    * 46.4%). "The mechanism did nothing" and "the mechanism removed its stall and a
    * different one became binding" are DIFFERENT findings with different next steps, and
    * a single cycle count cannot tell them apart. This decomposition can.
    *
    * BOARD PARITY IS THE POINT. Every category below mirrors a `DebugCtrlPlugin` perf
    * counter TERM FOR TERM, including its quirks, so a sim A/B and a silicon `perf`
    * capture can finally be compared category by category:
    *
    *   retireStall  = `perfStallRetire`      = `perfLvlRobBusy && !perfLvlRetire`
    *                                          (`DebugCtrlPlugin.scala:705`)
    *   retireCycles = `perfLvlRetire`        = `rob.retire0` ONLY. This is the board's
    *                  quirk and it is PRESERVED: it is an ANY-retire level, not a macro
    *                  commit and not a dual-retire count. It therefore differs from
    *                  `IpcResult.activeCycles` (macro-granular, cracked temps dropped);
    *                  both are reported so the gap is visible instead of assumed.
    *   robEmpty     = `rob.count === 0`. The board can only DERIVE this
    *                  (`cycles - stallRetire - cyclesWithARetire`); here it is measured
    *                  directly, which is also what makes the closure assertion possible.
    *   dcStall      = `OFF_PERF_STALL_DC`    = `stallDcPackReg(1)` = `DcachePlugin.busy`
    *   walkStall    = `OFF_PERF_STALL_WALK`  = either table walker out of IDLE
    *
    * ALIGNMENT. Every board tap is a `RegNext` of its level (`perfTap`), and the
    * harness's own commit histogram is likewise built from the REGISTERED
    * `ordinaryCommitObs`. So each level here is sampled and then recorded ONE sampling
    * edge later -- the same one-cycle delay `precedingRobCycle` already applies -- which
    * makes these counts both board-faithful AND index-aligned with `histo`, i.e. with
    * the IPC window itself.
    *
    * WHAT CLOSES AND WHAT DOES NOT. `robEmpty` / `retireStall` / `retireCycles` is a
    * genuine PARTITION of the window: `retire0` implies `count > 0` (it is gated on
    * `headReady`, which is `(count > 0) && ...`, `RobPlugin.scala:1177`), so the three
    * are mutually exclusive and exhaustive. `runKernel` ASSERTS that they sum to the
    * window -- a decomposition that does not close is not a decomposition.
    * `dcStall` and `walkStall` are OVERLAPPING OVERLAYS, exactly as they are on the
    * board: the D-cache can be busy on a cycle that also retires. They are reported
    * alongside the partition, never inside it, and the retire-stall intersections
    * (`retireStallWithDc` / `retireStallWithWalk`) are what attribute the stall. */
  final case class StallBudget(
      cycles: Int,          // window cycles -- must equal the partition's sum
      robEmpty: Int,        // PARTITION: rob.count === 0 (front-end starvation)
      retireStall: Int,     // PARTITION: rob busy, retire0 low  == perfStallRetire
      retireCycles: Int,    // PARTITION: retire0 high           == perfLvlRetire
      retire1Cycles: Int,   // sim-exact: exactly one retire lane fired
      retire2Cycles: Int,   // sim-exact: two or more retire lanes fired
      retiredUops: Int,     // sim-exact: retire lanes fired, summed over the window
      dcStall: Int,         // OVERLAY: DcachePlugin.busy       == OFF_PERF_STALL_DC
      walkStall: Int,       // OVERLAY: either walker off IDLE  == OFF_PERF_STALL_WALK
      retireStallWithDc: Int,   // retireStall AND the D-cache was busy
      retireStallWithWalk: Int, // retireStall AND a walker was running
      robEmptyWithDc: Int) {    // robEmpty AND the D-cache was busy (I-side vs D-side)
    /** Zero iff the partition closes to the window. Reported, never fudged. */
    def residual: Int = cycles - robEmpty - retireStall - retireCycles
    /** Uops beyond what a 2-wide retire can explain; 0 unless IPC_RETIRE_WIDTH widened it. */
    def wideRetireUops: Int = retiredUops - retire1Cycles - 2 * retire2Cycles
    /** THE CONSERVATION TEST -- "did the gain exist, or was it never there?"
      *
      * On a kernel that is never front-end starved (`robEmpty == 0`) with a 2-wide retire,
      * the budget is not merely a decomposition, it is an exact ACCOUNTING IDENTITY:
      *
      *     cycles = retireStall + retire1 + retire2        (the partition)
      *     uops   = retire1 + 2*retire2                   (a 2-wide retire)
      *  => cycles = retireStall + uops - retire2
      *
      * `uops` is fixed by the program. So for such a kernel a lever that cuts retire-stall
      * and gives back the SAME NUMBER of dual-retire cycles is cycle-neutral BY
      * CONSTRUCTION -- the two deltas are literally the same cycles counted once as a
      * stall and once as an extra single-retire cycle. There is no unrealised gain to
      * chase, and "it worked but something ate it" is the wrong reading.
      *
      * Conversely a lever that cuts retire-stall and HOLDS retire2 MUST move cycles. If it
      * does not, something genuinely absorbed the gain and that is worth attacking.
      *
      * This is the cheap discriminator the bench previously lacked: read
      * `d(retireStall)` against `d(retire2)` before writing a single line of RTL.
      * `None` when the preconditions do not hold, rather than a number that means nothing. */
    def conservationResidual: Option[Int] =
      if (robEmpty != 0 || wideRetireUops != 0) None
      else Some(cycles - (retireStall + retiredUops - retire2Cycles))
    def closes: Boolean = residual == 0
    private def pct(x: Int): Double = if (cycles == 0) 0.0 else 100.0 * x / cycles
    def robEmptyPct: Double    = pct(robEmpty)
    def retireStallPct: Double = pct(retireStall)
    def retirePct: Double      = pct(retireCycles)
    def dcStallPct: Double     = pct(dcStall)
    def walkStallPct: Double   = pct(walkStall)
    /** Board-comparable one-liner: the five terms a silicon `perf` capture prints. */
    def line(name: String): String =
      f"[stall-budget] $name cycles=$cycles " +
      f"robEmpty=$robEmpty (${robEmptyPct}%.1f%%) " +
      f"retireStall=$retireStall (${retireStallPct}%.1f%%) " +
      f"retire=$retireCycles (${retirePct}%.1f%%) " +
      f"residual=$residual | retire1=$retire1Cycles retire2=$retire2Cycles uops=$retiredUops " +
      f"| overlay dcStall=$dcStall (${dcStallPct}%.1f%%) walkStall=$walkStall (${walkStallPct}%.1f%%) " +
      f"retireStall&dc=$retireStallWithDc retireStall&walk=$retireStallWithWalk robEmpty&dc=$robEmptyWithDc" +
      // See `conservationResidual`: `conserved` marks a kernel where cycles are pinned to
      // `retireStall + uops - retire2`, so a retire-stall win paid for by dual-retire is
      // cycle-neutral BY CONSTRUCTION and there is nothing eaten to recover.
      (conservationResidual match {
        case Some(0) => " | conserved (cycles == retireStall + uops - retire2)"
        case Some(r) => s" | CONSERVATION BROKEN residual=$r"
        case None    => ""
      })
  }

  final case class IpcResult(
      name: String,
      retiredInstrs: Int,   // MACRO instructions (cracked-load temps dropped)
      windowCycles: Int,    // first-commit .. last-commit (steady-state window)
      activeCycles: Int,    // cycles in window with >=1 macro-commit
      dualCycles: Int,      // cycles in window retiring 2 macro-instructions
      ftbApplies: Int,      // registered fetch plans applied at the I-cache boundary
      ftqConfirms: Int,     // applied plans confirmed by exact decode framing
      ftqMismatches: Int,   // applied plans recovered by the mismatch path
      ftbDirDeclines: Int,  // conditional entry predicted not taken
      ftbFrameDeclines: Int, // unsafe length/window/drop relationship
      ftbBusyDeclines: Int, // redirect/quiesce/fault/held-target/full collision
      // Cycles in which the store queue asserted a FULL-overlap forward response
      // (`StoreQueue.io.fwd.rsp.hit`). This is the corroborating evidence that a
      // store-to-load-forwarding benchmark actually exercised the forward path
      // rather than quietly measuring an ordinary L1D hit. It is a raw per-cycle
      // count over the whole run (not the steady-state window) and the query port
      // is driven continuously, so treat it as a PRESENCE/ABSENCE signal and a
      // relative magnitude -- not as an exact count of forwarded loads.
      sqFwdHitCycles: Int = 0,
      // Direct branch-recovery instrumentation: for every retire-gated pipeline
      // flush, the number of cycles from the flush pulse to the NEXT committed
      // macro-instruction. This is the measured misprediction recovery penalty --
      // it does not have to be inferred from pipeline depth. `flushToCommit` is
      // the raw per-event sample list so a caller can report mean AND spread.
      flushToCommit: Seq[Int] = Nil,
      // ── load-pipeline event cycles, for STAGE DECOMPOSITION ──────────────────
      // Cycle stamps for the three observable points on a load's path:
      //   ldCmdCycles : D-cache loadCmdPort fire   (address accepted by the cache)
      //   ldRspCycles : D-cache loadRspPort valid  (data returned by the cache)
      //   lsWbCycles  : LsEu writeback observed    (result visible to consumers)
      // In a STRICTLY SERIAL dependent load chain exactly one load is in flight at
      // a time, so these three streams pair up positionally with no ID matching,
      // and their successive differences decompose load-to-use into stages. The
      // cmd->cmd interval is an INDEPENDENT per-event measurement of the same
      // quantity the differential method reports, so the two can be cross-checked.
      ldCmdCycles: Seq[Long] = Nil,
      // Physical addresses of every accepted D-cache load. A chase kernel that is
      // behaving touches only a handful of distinct lines; hundreds of distinct
      // addresses means the chain derailed and the run measured nothing it claims.
      ldCmdAddrs: Seq[Long] = Nil,
      ldRspCycles: Seq[Long] = Nil,
      lsWbCycles:  Seq[Long] = Nil,
      // Actual SQ-forward completions in the IPC cycle window, not raw query hits.
      sqForwardCompletions: Int = 0,
      pipelineProfile: Option[PipelineProfile] = None,
      lateStoreCaptures: Int = 0,
      reservedStores: Int = 0,
      reservedPublishes: Int = 0,
      reservedCompletionHolds: Int = 0,
      detachedLoadOvertakes: Int = 0,
      publicationForwards: Int = 0,
      readyStoreAdmissions: Int = 0,
      pendingStoreOwnerWaits: Int = 0,
      ownerWaitWithP4Overlap: Int = 0,
      ownerWaitWithSqFull: Int = 0,
      captureIssueCandidates: Int = 0,
      captureLoadOpportunities: Int = 0,
      captureLoadOverlaps: Int = 0,
      queuedStoreAdmissions: Int = 0,
      // ── D-cache LOAD miss census (window-sliced) ─────────────────────────────
      // `dcLoadMisses` counts cycles of `ldS1Valid && !ldS1Hit`, which is the very term
      // that TRIGGERS a refill, so it is a real miss count. `dcLoadLookups` counts
      // `ldS1Valid` and is NOT the load count: an EARLY-PROBE hit satisfies a load with
      // `useEarlyProbe = earlyProbeHit && !ldS1Valid` and never reaches S1, so the S1
      // lookup stream is only the loads that took the ordinary read path. Use
      // `ldCmdAddrs.size` (accepted loads) as the denominator; `dcLoadLookups` is kept
      // only so the two populations can be told apart. A cache-RESIDENT benchmark must
      // show misses near zero over its measured window -- if it does not, the kernel is
      // measuring cold refills instead of the recurrence it claims.
      dcLoadLookups: Int = 0,
      dcLoadMisses: Int = 0,
      // RETIRED mispredicts: one per `RobPlugin.branchRedirect` pulse, i.e. exactly what
      // the board's `OFF_MISPRED_COUNT` counts (a mispredicting branch reaching the ROB
      // head), and NOT the branch EU's completion-time count, which includes wrong-path
      // branches that never retire. This is the primary metric for any predictor change.
      retiredMispredicts: Int = 0,
      // OPT-IN per-kernel cycle decomposition; `None` unless IPC_STALL_BUDGET=1, so
      // every existing caller and every default report is untouched. See `StallBudget`.
      stallBudget: Option[StallBudget] = None
  ) {
    def flushRecoveryMean: Double =
      if (flushToCommit.isEmpty) 0.0 else flushToCommit.sum.toDouble / flushToCommit.size
    def ipc: Double = if (windowCycles == 0) 0.0 else retiredInstrs.toDouble / windowCycles
    // Dual-issue% over commit-ACTIVE cycles (how often, when retiring, we retire 2).
    def dualPctActive: Double = if (activeCycles == 0) 0.0 else 100.0 * dualCycles / activeCycles
    // Dual-issue% over the whole window (fraction of all cycles that retire 2).
    def dualPctWindow: Double = if (windowCycles == 0) 0.0 else 100.0 * dualCycles / windowCycles
    // Fraction of window cycles that retire >=1 instruction (backend occupancy).
    def activePct: Double = if (windowCycles == 0) 0.0 else 100.0 * activeCycles / windowCycles
  }

  /** Explicit MMU programming for a kernel, overriding the default
    * `copybackDtt` shorthand. Values are the raw architectural register images
    * poked into MmuControlPlugin after reset completes.
    *
    * The default kernels want either MMU-off identity (`copybackDtt = false`) or
    * match-all transparent translation (`copybackDtt = true`); NEITHER ever
    * invokes the table walker. A kernel that wants REAL page-table walks (the
    * DTLB/ITLB latency benchmarks) must set `mmu` explicitly: enable = true,
    * urp/srp pointing at a root table built by `prepMem`, and the TTR for the
    * side under test left at 0 so it does not short-circuit the walk. */
  final case class MmuSetup(enable: Boolean,
                            urp: Long = 0L, srp: Long = 0L,
                            itt0: Long = 0L, itt1: Long = 0L,
                            dtt0: Long = 0L, dtt1: Long = 0L)

  /** The three physical memories behind the core, handed to `Kernel.prepMem` so a
    * benchmark can install pointer chains / page tables BEFORE the CPU runs.
    *  - `dmem`      : behind the D-cache AXI (normal data).
    *  - `dWalkMem`  : D-side page tables.
    *  - `iWalkMem`  : I-side page tables.
    * HISTORY: when this harness was written the walkers had their OWN AXI ports and
    * bypassed the D-cache, so these were three genuinely separate memories. Since
    * `2db5bd3` (walker->L1D passthrough) the walkers issue no AXI of their own and
    * all three handles are THE SAME `AxiMemModel` behind the D-cache. Page tables
    * and data therefore share one memory and one cache: a `prepMem` that writes
    * tables and data must keep them at non-overlapping addresses (they already are:
    * tables at `MicrobenchSpec.MmuRoot` = 0x00080000, data at `DataBase` =
    * 0x00400000, code at 0x40800000). */
  final case class MemHandles(dmem: AxiMemModel, dWalkMem: AxiMemModel, iWalkMem: AxiMemModel)

  /** A kernel: assembly source + the EXACT number of MACRO-instructions it retires
    * before stopping (the harness runs until that many commit). */
  final case class Kernel(name: String, src: String, retiredInstrs: Int,
                          copybackDtt: Boolean = false,
                          expectedStoreDrains: Int = 0,
                          mmu: Option[MmuSetup] = None,
                          prepMem: MemHandles => Unit = _ => (),
                          // Back the D-side memory with ZERO-filled storage instead of
                          // AxiMemModel's default `SparseMemory()`, whose freshly-allocated
                          // pages are PRNG-FILLED. Any "chase" kernel whose dependency chain
                          // assumes the loaded value is 0 has a PREMISE that is silently false
                          // under the default memory: the address register jumps to garbage,
                          // the chain walks random addresses (or faults, under an MMU), and
                          // the differential reports a confident number for a run that never
                          // did what the kernel claims. Kernels that depend on loaded values
                          // MUST set this, and MUST additionally assert the premise held --
                          // see `assertChasePremise`.
                          zeroFillData: Boolean = false,
                          warmupInstrs: Int = 0,
                          verifyRetirement: Seq[m68k040.lockstep.CommitObservation] => Unit = _ => (),
                          profileRetirement: Boolean = false)

  /** Compile the core ONCE; return a handle that runs one kernel per call. Reusing
    * one compiled DUT across all kernels keeps this a single Verilator build. */
  def runKernel(compiled: SimCompiled[FullCoreDut], k: Kernel,
                seed: Int = IpcBenchSpec.simSeed): IpcResult = {
    require(k.warmupInstrs >= 0 && k.warmupInstrs < k.retiredInstrs)
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val image = ProgramAssembler.assemble(k.src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[${k.name}] assemble failed: ${err.reason}")
    }

    var result: IpcResult = null
    // Deterministic, PAIRED measurement: SpinalHDL picks a RANDOM sim seed per
    // doSim() by default, and the AXI agents' ready-randomizers consume it -- so
    // an unpinned run has ~1%% aggregate run-to-run jitter. IPC_SEED pins it.
    // Sim name carries the seed so repeated runs of the SAME kernel under different
    // seeds (the variance sweep in MicrobenchSpec) get distinct waveform/sim dirs
    // instead of silently colliding.
    compiled.doSim(s"${k.name}_s$seed", seed) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      // S-pre (2026-08-12 large-scale-frontend-restructure-design, §14 Q5 / D4):
      // IPC_PREFETCH=off disables IcachePlugin's next-line prefetch engine for the
      // WHOLE run, to measure its real aggregate/per-kernel IPC contribution.
      // `prefetchEnable` is a self-assigned RegInit(True) (see IcachePlugin.scala's
      // comment at its declaration) -- a single poke is overwritten by the reset
      // value on the next edge while `forkStimulus` still holds reset, so re-poke
      // from a non-blocking fork across the reset window (mirrors IcacheSpec.scala).
      if (sys.env.get("IPC_PREFETCH").contains("off")) {
        fork { for (_ <- 0 until 8) { dut.icache.logic.prefetchEnable #= false; cd.waitSampling() } }
      }
      val handle = new WhiteboxCapture.Handle
      // Sim-only cache-miss injection + MLP/miss-rate instrumentation. Inert (and a
      // single boolean test per cycle) unless IPC_INJ_D/IPC_INJ_I/IPC_MISS_STATS is set.
      val missInj = MissInjector.maybeNew(dut, k.name)

      // Per-cycle MACRO-commit histogram, trimmed to [first-commit, last-commit]
      // after the run. Use WhiteboxCapture's authoritative emitted count, including
      // drop/keep markers for stack pushes and other multi-uop instructions.
      // End at the requested macro count, excluding guard-code retirements while
      // the harness waits for final stores to drain.
      val histo = ArrayBuffer.empty[Int]   // macro-commits per sampled cycle
      // Overlapping diagnostic predicates, sliced to the exact IPC window below.
      val lsOrderHisto = ArrayBuffer.empty[(Boolean, Boolean, Boolean, Boolean)]
      val sqForwardHisto = ArrayBuffer.empty[Boolean]
      val lateStoreHisto = ArrayBuffer.empty[Boolean]
      val reserveStoreHisto = ArrayBuffer.empty[(Boolean, Boolean, Boolean)]
      val detachedOvertakeHisto = ArrayBuffer.empty[Boolean]
      val publicationForwardHisto = ArrayBuffer.empty[Boolean]
      val readyAdmissionHisto = ArrayBuffer.empty[Boolean]
      val queuedAdmissionHisto = ArrayBuffer.empty[Boolean]
      val pendingOwnerWaitHisto = ArrayBuffer.empty[(Boolean, Boolean, Boolean)]
      val captureIssueHisto = ArrayBuffer.empty[(Boolean, Boolean, Boolean)]
      val robHisto = ArrayBuffer.empty[RobCycle]
      // IQ head-of-line telemetry (IQ_HOL=1). `push.ready` is gated on the OLDEST
      // line being empty -- lines(0).ways.map(!selComb).reduce(_&&_) -- so one
      // long-residency uop in slot 0/1 refuses every push no matter how many of the
      // 16 slots are free. That is indistinguishable from capacity exhaustion in the
      // board's `blocked_iq` counter, and the two want opposite fixes. The
      // discriminator is the FREE-SLOT COUNT during blocked cycles.
      // (occupancy, line0Occupied, oldestOccupiedReady, cluster, memOp)
      // 6th field: the blocking slot's `lsWait` (OR of its LS_A/LS_B/LS_C dynamic-wait
      // bits) -- "this consumer is stalled on an in-flight LS LOAD". Crossing it with the
      // consumer's own CLUSTER is what sizes speculative wakeup: the shipped mechanism can
      // only release LS-class consumers, so `lsWait && !isLs` is the part it cannot reach.
      val iqHolHisto = ArrayBuffer.empty[(Int, Boolean, Boolean, String, String, Boolean)]
      // Branch events are retained by macro ordinal, not just cycle inclusion:
      // a warm-up/stop boundary can bisect a dual-retirement cycle.
      val branchEvents = ArrayBuffer.empty[(Long, Int, Boolean, Boolean, Boolean)]
      // debugBranchRetire is a BTB-training stream: it deliberately EXCLUDES
      // returns. Join ALL branch completions to actual branch retirement instead.
      // Kind 2 below means non-BTB control flow (returns in the current corpus),
      // not a fabricated direction-predictor or BTB event.
      val branchCompletions = scala.collection.mutable.HashMap.empty[Int, (Long, Int, Boolean, Boolean, Boolean)]
      var precedingRetiredBranch: Option[(Long, Int, Boolean, Boolean, Boolean)] = None
      var precedingRobCycle = RobCycle()
      var countedMacros = 0
      var totalCycles = 0L
      var sqFwdHitCycles = 0               // SQ full-overlap forward responses
      val traceOn = sys.env.get("MB_TRACE").exists(p => p.nonEmpty && k.name.startsWith(p))
      val traceLines = ArrayBuffer.empty[String]
      val lsEventsOn = sys.env.get("IPC_LS_EVENTS").exists(p => p.nonEmpty && k.name.startsWith(p))
      val iqHolOn = sys.env.get("IQ_HOL").contains("1")
      // IPC_STALL_BUDGET=1: collect the per-cycle STALL BUDGET (see `StallBudget`).
      // OFF by default so neither the default report nor the default sim runtime moves.
      // Collection is pure observation of already-simPublic levels -- it cannot perturb
      // the DUT, so cycles are bit-identical with it on or off (verified, not assumed).
      val stallBudgetOn = sys.env.get("IPC_STALL_BUDGET").contains("1")
      // IPC_PAIR_CENSUS=1: WHY did a cycle retire ONE instead of TWO?
      //
      // The stall budget found a lever (IQ_SPEC_ALU on dhrystone-x0-byteAbs-cb) that cut
      // retire-stall 8.2% and handed every recovered cycle straight back as LOST DUAL
      // RETIRE -- 1,519 pair cycles became 3,038 singles at bit-identical cycles and
      // bit-identical uop count. "Cycles neutral" therefore splits two ways, and only one
      // is worth attacking: the pair was NOT THERE / NOT READY (an arrival-order effect,
      // a design consequence), or the pair was present and complete and THE GATE DECLINED
      // IT (an accident, and recoverable). This census answers exactly that.
      //
      // Buckets follow `retire1`'s OWN conjunction order (RobPlugin.scala:1361-1368) so
      // every blocked cycle is charged to exactly one term and the attribution is the
      // RTL's, not a re-derivation:
      //   0 paired          retire1 fired
      //   1 noSecondEntry   count <= 1          -- nothing behind the head to pair with
      //   2 h1NotComplete   !completes(h1)      -- the younger uop has not executed yet
      //   3 h1LateByOne     as 2, and h1 completes on the VERY NEXT cycle: the pair was
      //                     split by ONE cycle of arrival order, nothing more
      //   4 headForbidsPair !headAllowsPair     -- p0 must retire alone (GATE)
      //   5 h1RetireAlone   p1.retireAlone      -- h1 must retire alone (GATE)
      //   6 h1Serializing   faulted/RTE/sup/sysOp/debugBreak on h1
      //   7 irqBoundary     irqBoundaryHold && p0.last
      //   8 h0TraceOrPrecise h0TraceArmed || h0PreciseCompletedSticky
      //   9 other           the residue (sysAuxRdy1, halt/step/stop terms)
      // Buckets 1-3 are ARRIVAL ORDER. Buckets 4-9 are A GATE SAYING NO.
      val pairCensusOn = sys.env.get("IPC_PAIR_CENSUS").contains("1")
      val censusHisto = ArrayBuffer.empty[Int]
      // (bucket, the ROB index of h1) held one edge, so bucket 2 can be refined to 3.
      var pendingCensus = (-1, 0)
      // One packed Int per cycle keeps this cheap next to the existing per-cycle
      // histograms: bits[3:0] retire lanes fired, [4] ROB busy, [5] D-cache busy,
      // [6] a table walker off IDLE.
      val budgetHisto = ArrayBuffer.empty[Int]
      // The board's `perfTap` REGISTERS every level before counting it, and the commit
      // histogram is built from the REGISTERED `ordinaryCommitObs`. Hold this cycle's
      // raw sample and record it on the NEXT edge, so the budget is both board-faithful
      // and index-aligned with `histo` (identical treatment to `precedingRobCycle`).
      var pendingBudget = 0
      // BYP_LIVE=1: which int-PRF bypass sources actually carry traffic? Each one is a
      // comparator + mux input in EVERY operand read, on the core's tightest datapath.
      val bypLiveOn = sys.env.get("BYP_LIVE").contains("1")
      val ldCmdAddrs  = ArrayBuffer.empty[Long]  // D$ load physical addresses (premise check)
      val ldCmdCycles = ArrayBuffer.empty[Long]  // D$ load command accepted
      // Per-load cycles spent PRESENTED-AND-REFUSED while the D$ FSM was off IDLE in a
      // refill/evict. Paired with that same load's post-accept cmd->rsp latency, this
      // separates the cycles hit-under-miss could actually recover (the load turned out
      // to HIT, so accepting it early would have completed it inside the refill window)
      // from cycles it could not (the load missed too and would have queued anyway).
      val ldRefillWaits = ArrayBuffer.empty[Long]
      var pendRefillWait = 0L
      val ldRefillSameLine = ArrayBuffer.empty[Int]
      var lastAcceptedLine = -1L
      val ldRspCycles = ArrayBuffer.empty[Long]  // D$ load data returned
      // Per-cycle D-cache load tag-compare outcome, kept as parallel per-cycle streams
      // so they can be sliced by the SAME steady-state window the IPC number uses.
      val dcLdLookupHisto = ArrayBuffer.empty[Boolean]
      val dcLdMissHisto   = ArrayBuffer.empty[Boolean]
      val lsWbCycles  = ArrayBuffer.empty[Long]  // LsEu writeback visible
      var firstCommitCycle = -1L
      var lastCommitCycle  = -1L

      // COPYBACK-store throughput observability.  Count real handshakes at every
      // boundary; a valid pulse is deliberately not enough now that SQ -> D-cache
      // is elastic.  These counters keep a poor macro-IPC result diagnostic instead
      // of letting a starved cache pipe masquerade as a slow one.
      var lsIssueFires      = 0
      var sqAllocFires      = 0
      var fastSqAllocs      = 0
      var sqDrainFires      = 0
      var dcStoreFires      = 0
      var dcStoreAcks       = 0
      var dcStoreHits       = 0
      var dcStoreMisses     = 0
      var lsIssueValidCyc   = 0
      var drainValidCyc     = 0
      var drainBlockedCyc   = 0
      var maxSqAccepted     = 0
      var maxSqResident     = 0
      var maxDcOutstanding  = 0
      var lsBypassFires     = 0
      var lsReplays         = 0
      var lsOrderViols      = 0
      val bypLiveCount      = Array.fill(16)(0)
      var aluSlowWr0 = 0; var aluSlowWr1 = 0; var aluFastWr0 = 0
      val nzvcLiveCount     = Array.fill(16)(0)
      var lsNzvcWrites      = 0
      var brTotal = 0; var brMis = 0; var brMisNoPred = 0; var brMisWrongDir = 0; var brNoPred = 0
      val brMisByType = scala.collection.mutable.Map.empty[Int, Int]
      // ── RETIRE-ACCURATE MISPREDICT ATTRIBUTION (`[br-attr]`) ────────────────────
      // The counters above sample the branch EU's COMPLETION, so they count WRONG-PATH
      // branches that never retire -- the board's counters do not. These count exactly
      // what silicon counts (`branchRedirect` = retire of a mispredicting branch) and
      // then classify it with the BranchEu attribution fields, joined by robId.
      //
      // The classification the shipped board counters CANNOT make:
      //  - returns are in `branchRedirect` (OFF_MISPRED_COUNT) but in NEITHER class
      //    counter, because both come from `debugBranchRetire` (gated `isBtbBranch`).
      //  - "no prediction at all" (BTB/FTB coverage) is indistinguishable from
      //    "predicted the wrong way" (gshare) inside OFF_PERF_MISPRED_COND.
      //  - "last-target BTB was stale" (the thing an indirect predictor fixes) is
      //    indistinguishable from "BTB missed" inside OFF_PERF_MISPRED_UNCOND.
      // brDbg snapshot per robId: (misp, predTaken, predTarget, actTarget, taken,
      //                            ibranch, isReturn, phtValid, brType, btbTrained, pc)
      val brAttrPend = scala.collection.mutable.HashMap.empty[Int,
        (Boolean, Boolean, Long, Long, Boolean, Boolean, Boolean, Boolean, Int, Boolean, Long)]
      var raTotalBranches = 0      // retired BTB-trainable branches (== the board's OFF_PERF_BRANCH)
      var raTotalMis = 0           // retired mispredicts (== the board's OFF_MISPRED_COUNT)
      var raUnattributed = 0       // no brDbg snapshot for the retiring robId (should be 0)
      val raBucket = scala.collection.mutable.Map.empty[String, Int]
      val raMisPc  = scala.collection.mutable.Map.empty[Long, (String, Int)]
      def raAdd(b: String): Unit = raBucket(b) = raBucket.getOrElse(b, 0) + 1
      var decFires = 0; var decDualFires = 0
      var oooWbFires = 0; var oooParkedCyc = 0
      var humAcceptsInRefill = 0; var humProbeLaunchInRefill = 0
      var dcLoadPresented   = 0; var dcLoadRefused = 0
      var dcRefusedRefill   = 0; var dcRefusedReplay = 0; var dcRefusedIdle = 0
      var hum2Shadow = 0; var hum2S1 = 0; var hum2StoreMiss = 0; var hum2SameSet = 0
      var hum2StoreClaim = 0; var hum2Other = 0
      var humEntriesValid = 0; var humEntriesReady = 0; var humProbeOffered = 0
      var humProbeCredit = 0; var humProbeLaunched = 0; var humStoreClaim = 0
      var humNoEntry = 0; var humEntryNoHit = 0; var humS1Busy = 0; var humNotOoOk = 0; var humOtherTerm = 0
      // Two-tier reschedule telemetry (2026-09-04). Sim-only reads of already-
      // simPublic ROB signals; they do not perturb the DUT.
      // THE instrument for a two-tier reschedule, and it is deliberately NOT the
      // 2026-09-03 "ROB entries ahead of the branch" histogram — backlog DEPTH is
      // the wrong quantity (see 2026-09-04-naxriscv-architecture-comparison.md §4.3).
      // What an early PC redirect can recover is exactly the number of cycles
      // between the branch RESOLVING in the EU and its retire-gated redirect
      // firing at the ROB head. Measured off signals that are simPublic on BOTH
      // arms (`branchCompletion`, `branchRedirect`, `head`), so the same code runs
      // unchanged against a baseline netlist.
      val misResolveCycle = scala.collection.mutable.HashMap[Int, Long]()
      val resolveToRetire = ArrayBuffer.empty[Int]
      val flushToCommit   = ArrayBuffer.empty[Int]
      var flushPendingCycle = -1L
      var feedFires         = 0
      var telemCycle        = 0L
      var historyCheckEnabled = false
      var historyExpectedNext = Option.empty[Int]
      var historyModelSuffix = Vector.empty[Boolean]
      var historyModelActive = false
      var historyModelKeep = false
      var historyCheckedRepairs = 0
      var t1PendFeedValid   = 0
      var t1PendFeedReady   = 0
      var t1PendFeedFire    = 0
      var t1EarlyFires      = 0   // Tier-1 early PC redirect pulses
      var t1PendCycles      = 0   // cycles rename was frozen by Tier 1
      var t1Suppressed      = 0   // Tier-2 flushes that KEPT the refetched frontend
      var t2Flushes         = 0   // doFlushReg pulses
      var t2BranchRedirects = 0   // retire-gated branch mispredict redirects
      var ftbApplies        = 0
      var ftqConfirms       = 0
      var ftqMismatches     = 0
      var ftbDirDeclines    = 0
      var ftbFrameDeclines  = 0
      var ftbBusyDeclines   = 0
      val historyTraceOn = sys.env.get("IPC_GHR_TRACE").exists(p => p.nonEmpty && k.name.startsWith(p))
      val historyHisto = ArrayBuffer.empty[(Boolean, Boolean, Boolean, Boolean)]
      var historyTraceEvents = 0
      var shiftsAfterEarly = 0
      var trackingEarly = false
      var precedingKeptFrontend = false

      // Feed the lock-step handle, which also owns macro classification.
      def snapWb(w: m68k040.execute.WbObs): Unit = if (w.valid.toBoolean) {
        val wb = WhiteboxCapture.Wb(
          dstArch   = w.dstArch.toInt,
          result    = w.result.toLong & 0xffffffffL,
          intWrite  = w.intWrite.toBoolean,
          nzvc      = w.nzvc.toInt,
          nzvcWrite = w.nzvcWrite.toBoolean,
          x         = if (w.x.toBoolean) 1 else 0,
          xWrite    = w.xWrite.toBoolean,
          // 2026-09-14: mirror ExecuteLockStepSpec's capture. Without these two the
          // handle's emit rule (`WhiteboxCapture.scala:107`, `(!isTempOnly && !divRem)
          // || keepCommit`) counted every crack tail -- a BSR/JSR stack push, an RMW
          // store, a DIVREM -- as its own macro instruction, inflating call/return IPC
          // and reading RMW kernels ~2x.
          divRem     = w.divRem.toBoolean,
          keepCommit = w.keepCommit.toBoolean)
        handle.onWb(w.robId.toInt, wb)
      }

      cd.onSamplings {
        telemCycle += 1
        missInj.onCycle()
        if (lsEventsOn && telemCycle <= 1400) {
          val ls = dut.lsEu.logic
          def event(s: String): Unit = println(s"LS_EVENT kernel=${k.name} cycle=$telemCycle $s")
          if (dut.lsEu.issuePort.valid.toBoolean && dut.lsEu.issuePort.ready.toBoolean) {
            val p = dut.lsEu.issuePort.payload
            event(f"issue rob=${p.robId.toInt} pc=${p.uop.pc.toLong}%08x " +
              s"mem=${p.uop.memOp.toEnum} srcB=${p.uop.psrcB.toInt} dst=${p.uop.pdst.toInt}")
          }
          if (ls.sq.io.alloc.valid.toBoolean)
            event(f"alloc rob=${ls.sq.io.alloc.robId.toInt} pa=${ls.sq.io.alloc.paddr.toLong}%08x " +
              s"reserved=${ls.p3ReservationFire.toBoolean}")
          if (dut.lsEu.reserveLateStore && ls.sq.io.publish.valid.toBoolean)
            event(s"publish rob=${ls.sq.io.publish.robId.toInt} slot=${ls.sq.io.publish.slot.toInt}")
          if ((ls.p3Valid.toBoolean || ls.p4Valid.toBoolean) && ls.sq.io.fwd.rsp.hit.toBoolean)
            event(f"forward rob=${ls.sq.io.fwd.query.robId.toInt} pa=${ls.sq.io.fwd.query.paddr.toLong}%08x " +
              s"publishHit=${ls.sq.publishForwardHit.toBoolean}")
          if (ls.p4CompletionFire.toBoolean)
            event(s"forwardComplete rob=${ls.p4Ctx.xlate.front.robId.toInt}")
          if (ls.nextIntWake.valid.toBoolean)
            event(s"intWakeSelected dst=${ls.nextIntWake.payload.toInt}")
          if (ls.wbObs.valid.toBoolean)
            event(s"lsWb rob=${ls.wbObs.robId.toInt} intWrite=${ls.wbObs.intWrite.toBoolean} dst=${ls.compPdst.toInt}")
          for ((eu, lane) <- Seq(dut.eu0, dut.eu1).zipWithIndex if eu.logic.wbObs.valid.toBoolean) {
            val w = eu.logic.wbObs
            event(f"aluWb lane=$lane rob=${w.robId.toInt} arch=${w.dstArch.toInt} data=${w.result.toLong}%08x")
          }
          if (ls.sq.io.flush.toBoolean) event("flush")
        }
        if (k.profileRetirement && dut.rob.logic.branchCompletion.valid.toBoolean) {
          val b = dut.rob.logic.branchCompletion.payload
          branchCompletions(b.robId.toInt) = ((b.btbPc.toLong & 0xffffffffL,
            if (b.isBranch.toBoolean) b.brType.toInt else 2,
            b.btbTaken.toBoolean, b.mispredict.toBoolean, b.phtValid.toBoolean))
        }
        // MISPREDICT ATTRIBUTION. Silicon shows 68 mispredicts per 1000 instructions on the
        // boot workload (~9.6% of all cycles in recovery), which is far worse than a working
        // gshare should give. `phtValid` says whether a prediction EXISTED: a mispredict with
        // phtValid=false was never predicted at all (defaulted), which is a COVERAGE problem,
        // while phtValid=true is a direction problem in the predictor itself. They need
        // completely different fixes, so measure the split before touching either.
        if (dut.rob.logic.branchCompletion.valid.toBoolean) {
          val bc = dut.rob.logic.branchCompletion.payload
          if (bc.isBranch.toBoolean) {
            brTotal += 1
            val mis = bc.mispredict.toBoolean
            val pv  = bc.phtValid.toBoolean
            if (mis) {
              brMis += 1
              if (!pv) brMisNoPred += 1 else brMisWrongDir += 1
              val t = bc.brType.toInt
              brMisByType(t) = brMisByType.getOrElse(t, 0) + 1
            }
            if (!pv) brNoPred += 1
          }
        }
        if (dut.rob.logic.branchCompletion.valid.toBoolean &&
            dut.rob.logic.branchCompletion.payload.mispredict.toBoolean)
          misResolveCycle(dut.rob.logic.branchCompletion.payload.robId.toInt) = telemCycle
        if (dut.rob.logic.branchRedirect.toBoolean)
          misResolveCycle.remove(dut.rob.logic.head.toInt)
            .foreach(c => resolveToRetire += (telemCycle - c).toInt)
        // ── `[br-attr]`: snapshot every branch resolution, classify at RETIRE ──────
        val brDbgNow = dut.branchEu.logic.brDbg
        if (brDbgNow.valid.toBoolean)
          brAttrPend(brDbgNow.robId.toInt) = (brDbgNow.mispredict.toBoolean,
            brDbgNow.predTaken.toBoolean,
            brDbgNow.predTarget.toLong & 0xffffffffL, brDbgNow.actTarget.toLong & 0xffffffffL,
            brDbgNow.redirect.toBoolean, brDbgNow.ibranch.toBoolean, brDbgNow.isReturn.toBoolean,
            brDbgNow.phtValid.toBoolean, brDbgNow.brType.toInt, brDbgNow.btbTrained.toBoolean,
            brDbgNow.pc.toLong & 0xffffffffL)
        if (dut.rob.logic.debugBranchRetire.valid.toBoolean) raTotalBranches += 1
        if (dut.rob.logic.branchRedirect.toBoolean) {
          raTotalMis += 1
          brAttrPend.get(dut.rob.logic.head.toInt) match {
            case None => raUnattributed += 1
            case Some((_, predTaken, predTgt, actTgt, actTaken, ibr, isRet, phtV, bt, trained, pc)) =>
              // Exactly the three independent failure modes of BranchEu's `mispredict`
              // formula: (predTaken =/= actualTaken) || (actualTaken && tgt mismatch).
              val dirWrong = predTaken != actTaken
              val bucket =
                if (!trained) {
                  // Not BTB-trainable: a return (RAS), or a faulting/odd-target transfer.
                  if (isRet) { if (!predTaken) "ret-nopred" else "ret-wrongtgt" } else "other-notrained"
                } else if (bt == 1) {
                  // Unconditional: direction is never in doubt (always resolved taken),
                  // so a mispredict is either NO prediction (BTB/FTB coverage) or a STALE
                  // TARGET. Split indirect (JMP/JSR (An)) from relative (BRA/BSR).
                  val kind = if (ibr) "ind" else "rel"
                  if (dirWrong) s"uncond-$kind-nopred" else s"uncond-$kind-wrongtgt"
                } else {
                  // Conditional: direction, or (rarely) a correct direction with a stale
                  // target. `phtValid` says whether gshare owned the direction at all.
                  if (dirWrong) (if (phtV) "cond-dir-gshare" else "cond-dir-nopred")
                  else "cond-wrongtgt"
                }
              raAdd(bucket)
              val prev = raMisPc.getOrElse(pc, (bucket, 0))
              raMisPc(pc) = (bucket, prev._2 + 1)
              if (predTgt == actTgt && !dirWrong) raAdd("ZZ-inconsistent")
          }
          brAttrPend.remove(dut.rob.logic.head.toInt)
        }
        // Recovery latency: cycles from the retire-gated flush pulse to the next
        // macro commit. THIS is the term an early PC redirect is supposed to
        // shorten; it is what an aggregate IPC delta is made of, one flush at a
        // time, and it is readable identically on a baseline netlist.
        if (dut.rob.logic.doFlushReg.toBoolean) flushPendingCycle = telemCycle
        if (dut.fa.logic.feed.valid.toBoolean && dut.fa.logic.feed.ready.toBoolean)
          feedFires += 1
        if (dut.rob.logic.earlyFire.toBoolean) t1EarlyFires += 1
        if (dut.rob.logic.earlyPend.toBoolean) {
          t1PendCycles += 1
          if (dut.fa.logic.feed.valid.toBoolean) t1PendFeedValid += 1
          if (dut.fa.logic.feed.ready.toBoolean) t1PendFeedReady += 1
          if (dut.fa.logic.feed.valid.toBoolean && dut.fa.logic.feed.ready.toBoolean) t1PendFeedFire += 1
        }
        if (dut.rob.logic.earlySuppressFe.toBoolean) t1Suppressed += 1
        if (dut.rob.logic.doFlushReg.toBoolean) t2Flushes += 1
        if (dut.rob.logic.branchRedirect.toBoolean) t2BranchRedirects += 1
        if (dut.fa.logic.applyNow.toBoolean) ftbApplies += 1
        if (dut.fa.logic.ftqConfirmFire.toBoolean) ftqConfirms += 1
        if (dut.fa.logic.ftqMismatch.toBoolean) ftqMismatches += 1
        if (dut.fa.logic.ftbDeclineDirection.toBoolean) ftbDirDeclines += 1
        if (dut.fa.logic.ftbDeclineFraming.toBoolean) ftbFrameDeclines += 1
        if (dut.fa.logic.ftbDeclineBlocked.toBoolean) ftbBusyDeclines += 1
        if (k.profileRetirement) {
          val gs = dut.gsh.logic
          val shifting = gs.shiftValid.toBoolean
          val repairing = gs.repairArm.toBoolean
          val early = dut.rob.logic.earlyFire.toBoolean
          val kept = dut.rob.logic.earlySuppressFe.toBoolean
          if (historyCheckEnabled && gs.retainedRepair.nonEmpty) {
            // Independent event-stream model, not the helper's internal count or
            // suffix. Check actual GHR on the following edge, across the full run.
            historyExpectedNext.foreach { expected =>
              assert(gs.ghr.toInt == expected,
                f"${k.name} GHR cycle=$telemCycle got=0x${gs.ghr.toInt}%04x expected=0x$expected%04x")
            }
            val invalidating = dut.icache.logic.invalidateAll.toBoolean
            val flushing = dut.rob.logic.doFlushReg.toBoolean
            val retain = kept && !dut.rob.logic.excActive.toBoolean
            val direction = gs.shiftDir.toBoolean
            val retained = historyModelActive && historyModelKeep && !early && !invalidating
            def push(h: Int, b: Boolean): Int = ((h << 1) | (if (b) 1 else 0)) & 65535
            val emitted = if (shifting) Vector(direction) else Vector.empty[Boolean]
            historyExpectedNext = Some(if (invalidating) 0
              else if (repairing && retained) {
                historyCheckedRepairs += 1
                (historyModelSuffix ++ emitted).foldLeft(gs.ghrArch.toInt)(push)
              } else if (repairing) gs.ghrArch.toInt
              else if (shifting) push(gs.ghr.toInt, direction)
              else gs.ghr.toInt)
            if (historyModelActive && shifting)
              historyModelSuffix = (historyModelSuffix :+ direction).takeRight(16)
            if (repairing || (flushing && !retain)) historyModelActive = false
            if (early) { historyModelActive = true; historyModelSuffix = Vector.empty }
            if (invalidating) { historyModelActive = false; historyModelSuffix = Vector.empty }
            historyModelKeep = flushing && retain
          }
          // The early redirect edge itself discards the old frontend. Count only
          // later emissions, and restart for an older redirect superseding it.
          if (early) { shiftsAfterEarly = 0; trackingEarly = true }
          else if (trackingEarly && shifting) shiftsAfterEarly += 1
          historyHisto += ((repairing, repairing && shifting,
            shifting && dut.rob.logic.earlyPend.toBoolean,
            repairing && precedingKeptFrontend && shiftsAfterEarly > 0))
          if (historyTraceOn && (early || kept || shifting || repairing || gs.upd.valid.toBoolean)) {
            if (historyTraceEvents < 1200) {
              println(f"GHR_EVENT kernel=${k.name} cycle=$telemCycle " +
                f"ghr=0x${gs.ghr.toInt}%04x arch=0x${gs.ghrArch.toInt}%04x " +
                s"early=$early kept=$kept repair=$repairing shift=$shifting " +
                s"dir=${gs.shiftDir.toBoolean} afterEarly=$shiftsAfterEarly " +
                f"pc=0x${gs.queryPc0.toLong}%08x index=${gs.phtIndex0.toInt} " +
                s"update=${gs.upd.valid.toBoolean} updateIndex=${gs.upd.payload.index.toInt} " +
                s"updateTaken=${gs.upd.payload.taken.toBoolean}")
            }
            historyTraceEvents += 1
          }
          if (repairing) trackingEarly = false
          precedingKeptFrontend = kept
        }
        if (k.copybackDtt) {
          if (dut.lsEu.issuePort.valid.toBoolean) lsIssueValidCyc += 1
          if (dut.lsEu.issuePort.valid.toBoolean &&
              dut.lsEu.issuePort.ready.toBoolean) lsIssueFires += 1
          if (dut.lsEu.logic.sq.io.alloc.valid.toBoolean) {
            sqAllocFires += 1
            if (dut.lsEu.logic.fastStore.toBoolean) fastSqAllocs += 1
          }
          if (dut.lsEu.logic.sq.io.drain.valid.toBoolean) drainValidCyc += 1
          if (dut.lsEu.logic.sq.io.drain.valid.toBoolean &&
              !dut.lsEu.logic.sq.io.drain.ready.toBoolean) drainBlockedCyc += 1
          if (dut.lsEu.logic.sq.io.drain.valid.toBoolean &&
              dut.lsEu.logic.sq.io.drain.ready.toBoolean) sqDrainFires += 1
          if (dut.dcache.logic.storePort.valid.toBoolean &&
              dut.dcache.logic.storePort.ready.toBoolean) dcStoreFires += 1
          if (dut.dcache.logic.storeAckReg.toBoolean) dcStoreAcks += 1
          if (dut.dcache.logic.stS3Valid.toBoolean &&
              dut.dcache.logic.stS3Hit.toBoolean) dcStoreHits += 1
          if (dut.dcache.logic.storeMissDiscovered.toBoolean) dcStoreMisses += 1
          maxSqAccepted = scala.math.max(maxSqAccepted,
            dut.lsEu.logic.sq.acceptedHalves.toInt)
          maxSqResident = scala.math.max(maxSqResident,
            dut.lsEu.logic.sq.valids.count(_.toBoolean))
          maxDcOutstanding = scala.math.max(maxDcOutstanding,
            dut.dcache.logic.storeOutstanding.toInt)
        }
        snapWb(dut.eu0.logic.wbObs); snapWb(dut.eu1.logic.wbObs); snapWb(dut.lsEu.logic.wbObs)
        // divEu is the CPLX EU: MULU/MULS, DIVU/DIVS *and* the FPU core all retire
        // through it. It was previously NOT observed here, so any kernel containing
        // one of those instructions died with "commit robId=N with no writeback
        // observed" -- which is precisely why the IPC kernel corpus carries the
        // restriction "NO DIV/MUL/CHK". Observing it is what makes the long-latency
        // EU benchmarks (and FPU) measurable at all.
        snapWb(dut.divEu.logic.wbObs)
        // Branch-EU Scc/DBcc and RTS/RTR also write an integer register. Preserve
        // those observations just as ExecuteLockStepSpec does; a no-op placeholder
        // hides Scc results and makes a last-register check report the prior load.
        locally {
          val bw = dut.branchEu.logic.wbObs
          if (bw.valid.toBoolean) {
            val writes = bw.anWrite.toBoolean
            val wb = WhiteboxCapture.Wb(
              if(writes) bw.anArch.toInt else 0,
              if(writes) bw.anData.toLong & 0xffffffffL else 0L,
              writes, 0, false, 0, false, keepCommit = bw.keepCommit.toBoolean)
            handle.onWb(bw.robId.toInt, wb)
          }
        }
        // Precise-path store completion (Task P2.5): the SQ's at-head drain fires
        // rob.logic.completion(4)/lsEu.sqCompletionPort instead of lsEu.completion
        // for a precise store -- no lsEu.logic.wbObs pulse accompanies it (compValid
        // is never asserted on that path), so synthesize a no-op Wb here too (mirrors
        // the branch EU capture above), or handle.onCommit below finds no Wb record
        // and throws.
        locally {
          val sc = dut.lsEu.sqCompletionPort
          if (sc.valid.toBoolean) {
            val wb = WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false)
            handle.onWb(sc.payload.toInt, wb)
          }
        }

        // Count MACRO commits this cycle from the two normal commit ports.
        if (flushPendingCycle >= 0 && telemCycle > flushPendingCycle &&
            dut.rob.logic.ordinaryCommitObs.exists(_.fire.toBoolean)) {
          flushToCommit += (telemCycle - flushPendingCycle).toInt
          flushPendingCycle = -1L
        }
        val emittedBefore = handle.emitted
        for (kk <- dut.rob.logic.ordinaryCommitObs.indices) {
          val c = dut.rob.logic.ordinaryCommitObs(kk)
          if (c.fire.toBoolean) {
            val id = c.robId.toInt
            val slotEmittedBefore = handle.emitted
            handle.onCommit(id, c.pc.toLong & 0xffffffffL)
            if (k.profileRetirement && kk == 0 && precedingRetiredBranch.nonEmpty) {
              val b = precedingRetiredBranch.get
              assert(handle.emitted == slotEmittedBefore + 1,
                "a retired branch must correspond to exactly one emitted macro")
              assert(b._1 == precedingRobCycle.headPc,
                "registered branch event is not aligned with the preceding ROB head")
              if (handle.emitted > k.warmupInstrs && handle.emitted <= k.retiredInstrs)
                branchEvents += b
              if (historyTraceOn && historyTraceEvents < 1200) {
                println(f"GHR_BRANCH kernel=${k.name} cycle=$telemCycle pc=0x${b._1}%08x " +
                  s"type=${b._2} taken=${b._3} miss=${b._4} phtValid=${b._5}")
                historyTraceEvents += 1
              }
            }
          }
        }
        if (k.profileRetirement) {
          val obsRetires = dut.rob.logic.ordinaryCommitObs.count(_.fire.toBoolean)
          assert(obsRetires == precedingRobCycle.retires,
            "ROB pressure and registered commit observations differ by more than one sampling edge")
          assert(!dut.rob.logic.debugBranchRetire.valid.toBoolean ||
            dut.rob.logic.commitObs(0).fire.toBoolean,
            "branch retirement event without slot-0 retirement")
          assert(dut.rob.logic.debugBranchRetire.valid.toBoolean ==
            precedingRetiredBranch.exists(_._2 != 2), "BTB retirement subset disagrees with all-branch stream")
          // commitObs is registered one cycle after the raw retirement decision.
          // Delay pressure snapshots too, so they describe the very same IPC edges.
          robHisto += precedingRobCycle
          val rob = dut.rob.logic
          val occupancy = rob.count.toInt
          val head = rob.head.toInt
          val complete = (0 until occupancy).map(i => rob.completes((head + i) % rob.depth).toBoolean)
          val rawRetires = rob.retireLanes.count(_.toBoolean)
          precedingRetiredBranch = if (rob.retire0.toBoolean && rob.p0.retireAlone.toBoolean) {
            val b = branchCompletions.getOrElse(head,
              fail(s"retiring branch at ROB $head without its completion metadata"))
            assert(b._4 == rob.mispredictStore(head).toBoolean)
            Some(b)
          } else None
          if (rob.flushing.toBoolean) branchCompletions.clear()
          // A structural opportunity, NOT proof of legal dual commit: precise
          // stops, trace and cold-path eligibility still need the RTL audit.
          val branchPairPotential = rawRetires == 1 && occupancy > 1 && complete(1) &&
            rob.p0.retireAlone.toBoolean && !rob.mispredictStore(head).toBoolean &&
            !rob.p1.retireAlone.toBoolean && !rob.faultedStore((head + 1) % rob.depth).toBoolean &&
            !rob.p1.needsSup.toBoolean && !rob.p1.sysOp.toBoolean && !rob.p1.isRte.toBoolean
          val preparation = rob.preparedBatch.map(b =>
            (b.start.toBoolean, b.active.toBoolean && b.abort.toBoolean,
              if (b.fire.toBoolean) b.target.toInt else 0)).getOrElse((false, false, 0))
          precedingRobCycle = RobCycle(occupancy, complete.takeWhile(identity).size,
            complete.drop(1).count(identity), complete.headOption.contains(false), rawRetires,
            branchPairPotential, if (occupancy > 0) rob.p0.pc.toLong & 0xffffffffL else 0L,
            rawRetires == 2 && rob.p0.retireAlone.toBoolean,
            preparation._1, preparation._2, preparation._3)
        }
        // Exception channel: not exercised by these kernels, but feed it for safety.
        locally {
          val c = dut.rob.logic.commitObs(2)
          if (c.fire.toBoolean) handle.onExcCommit(c.pc.toLong & 0xffffffffL, 0x27, -1L)
        }

        val acceptedMacros = scala.math.min(handle.emitted - emittedBefore,
          k.retiredInstrs - countedMacros)
        val macrosThisCycle = scala.math.max(0, countedMacros + acceptedMacros - k.warmupInstrs) -
          scala.math.max(0, countedMacros - k.warmupInstrs)
        val lsSlots = dut.iq.logic.slots.filter(s => s.sel.toBoolean &&
          s.hot.cluster.toEnum == m68k040.isa.Cluster.LS &&
          (s.hot.memOp.toEnum != m68k040.isa.MemOp.NONE || s.hot.leaAddr.toBoolean))
        val oldestBlocked = lsSlots.headOption.exists(s => !s.ready.toBoolean)
        val blockedStore = oldestBlocked &&
          lsSlots.head.hot.memOp.toEnum == m68k040.isa.MemOp.STORE
        val youngerReadyLoad = oldestBlocked && lsSlots.drop(1).exists(s =>
          s.ready.toBoolean && s.hot.memOp.toEnum == m68k040.isa.MemOp.LOAD)
        lsOrderHisto += ((oldestBlocked, blockedStore, youngerReadyLoad,
          dut.iq.logic.lsSkidValid.toBoolean))
        if (dut.iq.logic.lsBypassFired.toBoolean) lsBypassFires += 1
        // LS-OoO liveness replays (a P4 op vacated because an older LS op was stuck behind
        // it) and the order-violation recoveries: the two costs the relaxation can incur.
        if (dut.lsEu.replayRequestPort.valid.toBoolean) lsReplays += 1
        if (dut.lsEu.orderViolationPort.valid.toBoolean) lsOrderViols += 1
        if (bypLiveOn) {
          val v = dut.rfInt.logic.bypLive
          for (i <- 0 until v.length) if (v(i).toBoolean) bypLiveCount(i) += 1
          val vn = dut.rfNzvc.logic.bypLive
          for (i <- 0 until vn.length) if (vn(i).toBoolean) nzvcLiveCount(i) += 1
        }
        if (bypLiveOn) {
          // Who PRODUCES flags: the LS side (move-to-memory) or the ALU? The LS flag
          // cone is the core's critical path at 20 levels; retiming it costs one cycle
          // of flag latency, so its value depends on how rare LS-produced flags are.
          // HIT-UNDER-MISS opportunity. The D-cache leaves IDLE on a miss edge and
          // captures younger requests "for ordered replay", so during a refill it
          // REFUSES loads. Cycles with a load presented and refused are exactly what
          // hit-under-miss would recover -- an upper bound, since some of those loads
          // would themselves miss. An L1D HIT also proves the access is cacheable (a
          // device read can never hit), which is what makes the reorder safe where the
          // IQ-level relaxation was not.
          if (dut.dcache.logic.loadCmdPort.valid.toBoolean) {
            dcLoadPresented += 1
            if (!dut.dcache.logic.loadCmdPort.ready.toBoolean) {
              dcLoadRefused += 1
              if (dut.dcache.logic.dbgFsmRefill.toBoolean) {
                dcRefusedRefill += 1
                // Which term of the hit-under-miss accept arm is refusing this cycle?
                if (!dut.dcache.logic.earlyProbeOwnsCmd.toBoolean) {
                  humNoEntry += 1
                  // Which term of the REAL-READ arm (arm 2) refuses this cycle? Checked in
                  // the arm's own order so each cycle is attributed to one blocker.
                  if (dut.dcache.logic.loadShadowValid.toBoolean) hum2Shadow += 1
                  else if (dut.dcache.logic.ldS1Valid.toBoolean) hum2S1 += 1
                  else if (dut.dcache.logic.pendingStoreMiss.toBoolean) hum2StoreMiss += 1
                  else if (((dut.dcache.logic.loadCmdPort.payload.vaddr.toLong >> 4) & 0x7f) ==
                           dut.dcache.logic.missSet.toLong) hum2SameSet += 1
                  else if (dut.dcache.logic.storeClaimReg.toBoolean) hum2StoreClaim += 1
                  else hum2Other += 1
                  val nV = (0 until 5).count(i => dut.dcache.logic.earlyProbeValids(i).toBoolean)
                  val nR = (0 until 5).count(i => dut.dcache.logic.earlyProbeReadies(i).toBoolean)
                  humEntriesValid += nV; humEntriesReady += nR
                  if (dut.dcache.logic.loadProbePort.valid.toBoolean) humProbeOffered += 1
                  if (dut.dcache.logic.loadProbePort.ready.toBoolean) humProbeCredit += 1
                  if (dut.dcache.logic.loadProbeLaunch.toBoolean) humProbeLaunched += 1
                  if (dut.dcache.logic.storeClaimReg.toBoolean) humStoreClaim += 1
                }
                else if (!dut.dcache.logic.earlyProbeHit.toBoolean)  humEntryNoHit += 1
                else if (!dut.dcache.logic.useEarlyProbe.toBoolean)  humS1Busy += 1
                else if (!dut.dcache.logic.loadCmdPort.payload.ooOk.toBoolean) humNotOoOk += 1
                else humOtherTerm += 1
              }
              else if (dut.dcache.logic.dbgFsmReplay.toBoolean) dcRefusedReplay += 1
              else if (dut.dcache.logic.dbgFsmIdle.toBoolean)   dcRefusedIdle += 1
            }
          }
          // Is the OUT-OF-ORDER writeback actually firing, or is the gain only the pipe
          // unblocking with an in-order writeback? These separate the two.
          if (dut.dcache.logic.loadCmdPort.valid.toBoolean &&
              dut.dcache.logic.loadCmdPort.ready.toBoolean &&
              dut.dcache.logic.dbgFsmRefill.toBoolean) humAcceptsInRefill += 1
          if (dut.dcache.logic.loadProbeLaunch.toBoolean &&
              dut.dcache.logic.dbgFsmRefill.toBoolean) humProbeLaunchInRefill += 1
          if (dut.lsEu.logic.alignedEarlyWbFire.toBoolean) oooWbFires += 1
          if ((0 until 4).exists(i => dut.lsEu.logic.alignedDone(i).toBoolean)) oooParkedCyc += 1
          // DUAL DECODE rate. `slot1Ok` in Aligner.scala requires the SECOND instruction to
          // be `p1.simple && !p1.ambiguousLine`, so any complex/microcoded instruction in
          // slot 1 drops the group to single-issue at the very front of the machine. This
          // measures how often that costs us, before touching the aligner.
          if (dut.dec.logic.fed.valid.toBoolean && dut.dec.logic.fed.ready.toBoolean) {
            decFires += 1
            if (dut.dec.logic.fed.payload.slot1Valid.toBoolean) decDualFires += 1
          }
          if (dut.lsEu.logic.compNzvcWrite.toBoolean) lsNzvcWrites += 1
          if (dut.eu0.intWs.valid.toBoolean) aluSlowWr0 += 1
          if (dut.eu1.intWs.valid.toBoolean) aluSlowWr1 += 1
          if (dut.eu0.intW.valid.toBoolean)  aluFastWr0 += 1
        }
        if (iqHolOn) {
          val allSlots = dut.iq.logic.slots
          val occ = allSlots.count(_.sel.toBoolean)
          val s0 = allSlots(0); val s1 = allSlots(1)
          val oldest = if (s0.sel.toBoolean) Some(s0) else if (s1.sel.toBoolean) Some(s1) else None
          iqHolHisto += ((occ, oldest.isDefined, oldest.forall(_.ready.toBoolean),
            oldest.map(_.hot.cluster.toEnum.toString).getOrElse("none"),
            oldest.map(_.hot.memOp.toEnum.toString).getOrElse("none"),
            oldest.exists(_.lsWait.toBoolean)))
        }
        sqForwardHisto += dut.lsEu.logic.p4CompletionFire.toBoolean
        lateStoreHisto += dut.lsEu.logic.lateDataCapture.toBoolean
        captureIssueHisto += ((dut.lsEu.logic.captureIssueCandidate.toBoolean,
          dut.lsEu.logic.captureLoadOpportunity.toBoolean, dut.lsEu.logic.captureLoadOverlap.toBoolean))
        reserveStoreHisto += ((dut.lsEu.logic.p3ReservationFire.toBoolean,
          dut.lsEu.logic.p3ReservedPublish.toBoolean,
          dut.lsEu.logic.p3Reserved.toBoolean && dut.lsEu.logic.frontCompHeld.toBoolean))
        publicationForwardHisto += dut.lsEu.logic.sq.publishForwardHit.toBoolean
        readyAdmissionHisto += dut.lsEu.logic.detachedStore.exists(_.readyAdmission.toBoolean)
        queuedAdmissionHisto += dut.lsEu.logic.detachedStore.exists(_.queuedAdmission.toBoolean)
        val waitingOnOwner = dut.lsEu.logic.detachedStore.exists { d =>
          d.valid.toBoolean && dut.lsEu.logic.p3Valid.toBoolean &&
            dut.lsEu.logic.p3LateDataPending.toBoolean
        }
        // These predicates overlap: an occupied owner is not necessarily the
        // unique bottleneck, nor does it prove the second source is already ready.
        pendingOwnerWaitHisto += ((waitingOnOwner,
          waitingOnOwner && dut.lsEu.logic.p4Valid.toBoolean && dut.lsEu.logic.p4Ctx.fwdStall.toBoolean,
          waitingOnOwner && dut.lsEu.logic.sq.io.full.toBoolean))
        detachedOvertakeHisto += dut.lsEu.logic.detachedStore.exists { d =>
          val mask = (1 << d.ctx.robId.getWidth) - 1
          val head = dut.rob.logic.h0.toInt
          d.valid.toBoolean && !d.captured.toBoolean && !d.capture.toBoolean &&
            dut.lsEu.logic.compValid.toBoolean && dut.lsEu.logic.compIsLoad.toBoolean &&
            !dut.lsEu.logic.compIsFault.toBoolean &&
            (((dut.lsEu.logic.compRobId.toInt - head) & mask) > ((d.ctx.robId.toInt - head) & mask))
        }
        countedMacros += acceptedMacros
        if (macrosThisCycle > 0) {
          if (firstCommitCycle < 0) firstCommitCycle = totalCycles
          lastCommitCycle = totalCycles
          missInj.markCommit()   // takes the miss/MLP counters over the SAME window
        }
        // MB_TRACE=<kernel-name-prefix>: dump a per-cycle table of every LOAD-path
        // pipeline stage, so the cost of a load can be attributed to named stages and
        // BUBBLES (cycles where nothing advances) can be told apart from genuine
        // pipeline depth. Stage names follow the LS EU / D-cache design docs:
        //   P1  s1Valid   issue context + registered operands
        //   P2  tValid    DTLB + VIPT probe launched
        //   P2T txValid   translation response awaited
        //   P3  p3Valid   resolved PA -> SQ query / store alloc
        //   P4  p4Valid   SQ forward response -> resolve / cache launch
        //   C0  loadCmd   D-cache accepts the address (valid&ready)
        //   C1  ldS1Valid tag compare + way select
        //   C2  ldS2Valid byte-lane extract
        //   RSP loadRsp   data returned to the LS EU
        //   CMP compValid registered completion
        //   WB  wbObs     writeback visible / wakeup broadcast
        if (traceOn) {
          def b(x: Boolean) = if (x) "#" else "."
          val cmdFire = dut.dcache.logic.loadCmdPort.valid.toBoolean &&
                        dut.dcache.logic.loadCmdPort.ready.toBoolean
          val line = Seq(
            b(dut.lsEu.logic.s1Valid.toBoolean), b(dut.lsEu.logic.tValid.toBoolean),
            b(dut.lsEu.logic.txValid.toBoolean), b(dut.lsEu.logic.p3Valid.toBoolean),
            b(dut.lsEu.logic.p4Valid.toBoolean), b(cmdFire),
            b(dut.dcache.logic.ldS1Valid.toBoolean), b(dut.dcache.logic.ldS2Valid.toBoolean),
            b(dut.dcache.logic.loadRspPort.valid.toBoolean),
            b(dut.lsEu.logic.compValid.toBoolean), b(dut.lsEu.logic.wbObs.valid.toBoolean)
          ).mkString(" ")
          if (traceLines.size < 400) traceLines += f"$telemCycle%5d  $line  commits=$macrosThisCycle"
        }
        if (dut.lsEu.logic.sq.io.fwd.rsp.hit.toBoolean) sqFwdHitCycles += 1
        if (dut.dcache.logic.loadCmdPort.valid.toBoolean &&
            dut.dcache.logic.loadCmdPort.ready.toBoolean) {
          ldCmdCycles += telemCycle
          ldCmdAddrs  += (dut.dcache.logic.loadCmdPort.payload.paddr.toLong & 0xffffffffL)
          ldRefillWaits += pendRefillWait
          // Was this load in the SAME 64B line as the load that caused the refill it
          // waited behind? If so it HITS ONLY BECAUSE IT WAITED -- the refill is what
          // put its data in the array -- and hit-under-miss could not have completed it
          // early. (`move.l 4(%a0),%d0` right after `move.l (%a0),%a0` is exactly this.)
          // Only different-line waiters are genuinely recoverable.
          val thisLine = (dut.dcache.logic.loadCmdPort.payload.paddr.toLong & 0xffffffffL) >> 6
          ldRefillSameLine += (if (pendRefillWait > 0 && thisLine == lastAcceptedLine) 1 else 0)
          lastAcceptedLine = thisLine
          pendRefillWait = 0L
        } else if (dut.dcache.logic.loadCmdPort.valid.toBoolean &&
                   dut.dcache.logic.dbgFsmRefill.toBoolean) {
          pendRefillWait += 1
        }
        if (dut.dcache.logic.loadRspPort.valid.toBoolean) ldRspCycles += telemCycle
        if (dut.lsEu.logic.wbObs.valid.toBoolean) lsWbCycles += telemCycle
        val ldLookup = dut.dcache.logic.ldS1Valid.toBoolean
        dcLdLookupHisto += ldLookup
        dcLdMissHisto   += (ldLookup && !dut.dcache.logic.ldS1Hit.toBoolean)
        if (pairCensusOn) {
          val rob = dut.rob.logic
          // Refine the HELD bucket first: a `h1NotComplete` whose h1 has completed by NOW
          // was blocked by ONE cycle of arrival order, not by anything structural.
          val (heldBucket, heldH1) = pendingCensus
          val refined = if (heldBucket == 2 && rob.completes(heldH1).toBoolean) 3 else heldBucket
          if (refined >= 0) censusHisto += refined else censusHisto += -1
          val depth = rob.depth
          val h0 = rob.h0.toInt
          val h1 = (h0 + 1) % depth
          val count = rob.count.toInt
          val r0 = rob.retireLanes(0).toBoolean
          val r1 = rob.retireLanes(1).toBoolean
          val bucket =
            if (!r0) -1
            else if (r1) 0
            else if (count <= 1) 1
            else if (!rob.completes(h1).toBoolean) 2
            else if (!(!rob.p0.retireAlone.toBoolean ||
                       (!rob.mispredictStore(h0).toBoolean && rob.p0.last.toBoolean))) 4
            else if (rob.p1.retireAlone.toBoolean) 5
            else if (rob.faultedStore(h1).toBoolean || rob.p1.isRte.toBoolean ||
                     rob.p1.needsSup.toBoolean || rob.p1.sysOp.toBoolean ||
                     rob.p1.debugBreakValid.toBoolean) 6
            else if (rob.irqBoundaryHold.toBoolean && rob.p0.last.toBoolean) 7
            else if (rob.h0TraceArmed.toBoolean || rob.h0PreciseCompletedSticky.toBoolean) 8
            else 9
          pendingCensus = (bucket, h1)
        }
        if (stallBudgetOn) {
          // Record the PREVIOUS edge's sample (the board's registered tap), then take
          // this edge's. Exactly one append per sampling, so `budgetHisto` indexes the
          // same cycles `histo` does and the IPC window slices both identically.
          budgetHisto += pendingBudget
          val rob = dut.rob.logic
          val lanes = rob.retireLanes.count(_.toBoolean)
          val robBusy = rob.count.toInt != 0
          // Bit 1 of `dbgStallDcPack` IS `DcachePlugin.busy` -- the very signal the board's
          // OFF_PERF_STALL_DC counts through `stallDcPackReg(1)` (DcachePlugin.scala:2904,
          // DebugCtrlPlugin.scala:710), so this is the same term, not a re-derivation.
          val dcBusy = ((dut.dcache.logic.dbgStallDcPack.toBigInt >> 1) & 1) == 1
          val walkBusy = dut.dtlb.logic.walker.io.busy.toBoolean ||
                         dut.itlb.logic.walker.io.busy.toBoolean
          // `retire0` is read as ITS OWN bit rather than inferred from `lanes > 0`: the
          // board's `perfLvlRetire` taps `rob.logic.retire0` literally, and under
          // IPC_PREPARED_RETIRE the wider lanes are driven by `preparedBatch.fire`
          // instead of the `retireLanes(lane-1)` chain, so the two are not the same
          // expression in every configuration.
          val retire0 = rob.retireLanes(0).toBoolean
          pendingBudget = (lanes & 0xf) |
            (if (robBusy) 0x10 else 0) | (if (dcBusy) 0x20 else 0) |
            (if (walkBusy) 0x40 else 0) | (if (retire0) 0x80 else 0)
        }
        histo += macrosThisCycle
        totalCycles += 1
      }

      // Attach memories (I-cache program; D-cache image). The two TLB-walker memories
      // are gone: since `2db5bd3` (walker->L1D passthrough) the walkers no longer emit
      // AXI, so their descriptor reads and U/M writebacks reach memory THROUGH the
      // D-cache and land in `dmem`. `dWalkMem`/`iWalkMem` are kept in `MemHandles` as
      // aliases of `dmem` so kernel `prepMem` bodies need no change -- but note that
      // page tables and data now share one memory AND one cache.
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem      = AxiMemModel.attachFull(dut.dcache.logic.axi, cd, memCfg,
        if (k.zeroFillData) new ConstFillSparseMemory(0.toByte) else null)
      val ptmem     = dmem
      val itlbPtmem = dmem

      // Kernel-supplied preload: pointer chains into `dmem`, page tables into the
      // walker memories. Runs BEFORE the CPU is released, so the program observes a
      // fully-initialised memory and no setup store traffic pollutes the window.
      k.prepMem(MemHandles(dmem, ptmem, itlbPtmem))

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false

      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      historyCheckEnabled = true // forkStimulus reset and explicit invalidation have completed

      // Program architectural controls only AFTER forkStimulus's reset has
      // completed.  Programming these beside the initial port defaults silently
      // lost the values to reset and made the COPYBACK benchmark take the precise
      // MMU-off path.  Most kernels retain identity/MMU-off behavior; store-stream
      // uses match-all transparent translations (VA==PA, no walker) with WT I-side
      // and real COPYBACK D-side cache mode.
      // `k.mmu` (when present) fully replaces the copybackDtt shorthand -- this is
      // how the TLB-walk benchmarks get real page tables instead of a match-all TTR.
      val mmuCfg = k.mmu.getOrElse(MmuSetup(
        enable = k.copybackDtt,
        itt0 = if (k.copybackDtt) 0x00FFC000L else 0L,  // E|S|mask-all, WT
        dtt0 = if (k.copybackDtt) 0x00FFC020L else 0L)) // E|S|mask-all, CB
      dut.ctrl.logic.mmuEnable #= mmuCfg.enable
      dut.ctrl.logic.urp   #= mmuCfg.urp
      dut.ctrl.logic.srp   #= mmuCfg.srp
      dut.ctrl.logic.itt0 #= mmuCfg.itt0
      dut.ctrl.logic.itt1 #= mmuCfg.itt1
      dut.ctrl.logic.dtt0 #= mmuCfg.dtt0
      dut.ctrl.logic.dtt1 #= mmuCfg.dtt1
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      val n = k.retiredInstrs
      var guard = 0
      // The cycle budget must SCALE WITH THE KERNEL, not be a flat number. As
      // committed this was a flat 20000 / 500000, which the suite's own largest kernel
      // outgrew: `chase-loop-512-ld-4` needs 10250 macro-instructions and a dependent
      // load chase retires roughly one macro every four cycles, so it was truncated at
      // ~5270/10250 and the assertion below aborted the whole suite -- taking the
      // branch, divmul, fpu and mmu groups with it -- on BOTH bb3bca1 and current HEAD.
      // The loop exits the moment `n` macros have retired, so a generous per-macro
      // budget costs nothing on a healthy kernel and still terminates a wedged one.
      val perMacro = if (memCfg.latency.enabled) 300 else 30
      // NB `math` alone resolves to spinal.lib.math inside this file.
      val cap   = scala.math.max(if (memCfg.latency.enabled) 500000 else 20000, n * perMacro)
      val debug = sys.env.contains("IPC_DEBUG")
      var lastSize = -1; var stuck = 0
      while (handle.result.size < n && guard < cap) {
        cd.waitSampling(); guard += 1
        if (debug) {
          val sz = handle.result.size
          if (sz == lastSize) stuck += 1 else { stuck = 0; lastSize = sz }
          if (stuck == 50 || (guard < 400 && guard % 40 == 0)) {
            val hd = dut.rob.logic.head.toInt; val tl = dut.rob.logic.tail.toInt
            val cnt = dut.rob.logic.count.toInt; val ex = dut.rob.logic.excActive.toBoolean
            val fl = dut.rob.logic.doFlushReg.toBoolean
            val excP = dut.rob.logic.exceptionPending.toBoolean
            val excV = dut.rob.logic.exceptionVector.toInt
            println(f"[${k.name}] g=$guard sz=$sz head=$hd tail=$tl cnt=$cnt excActive=$ex flush=$fl excPend=$excP vec=$excV")
          }
        }
      }
      // Let the last commit's cycle land in the histogram.  For the COPYBACK
      // throughput kernel, also drain the final committed store outside the IPC
      // window so the boundary scoreboard can require exact command/ack counts.
      cd.waitSampling(2)
      if (k.expectedStoreDrains > 0) {
        var drainGuard = 0
        while (dcStoreAcks < k.expectedStoreDrains && drainGuard < 1000) {
          cd.waitSampling(); drainGuard += 1
        }
        assert(sqAllocFires >= k.expectedStoreDrains,
          s"[${k.name}] only $sqAllocFires store allocations for ${k.expectedStoreDrains} architectural stores")
        assert(fastSqAllocs == sqAllocFires,
          s"[${k.name}] only $fastSqAllocs/$sqAllocFires allocations used the fast COPYBACK path")
        assert(sqDrainFires == k.expectedStoreDrains,
          s"[${k.name}] SQ drain handshakes=$sqDrainFires expected=${k.expectedStoreDrains}")
        assert(dcStoreFires == k.expectedStoreDrains,
          s"[${k.name}] D-cache store handshakes=$dcStoreFires expected=${k.expectedStoreDrains}")
        assert(dcStoreAcks == k.expectedStoreDrains,
          s"[${k.name}] terminal store acks=$dcStoreAcks expected=${k.expectedStoreDrains}")
        assert(dcStoreHits == k.expectedStoreDrains && dcStoreMisses == 0,
          s"[${k.name}] COPYBACK hits=$dcStoreHits misses=$dcStoreMisses expected all hits")
        assert(maxSqAccepted >= 2 && maxDcOutstanding >= 2,
          s"[${k.name}] pipeline never overlapped stores: sq=$maxSqAccepted dc=$maxDcOutstanding")
      }
      assert(handle.result.size >= n,
        f"[${k.name}] only ${handle.result.size}/$n macro-instructions retired within $cap cycles " +
        f"(${cap.toDouble / scala.math.max(handle.result.size, 1)}%.1f cyc/macro achieved). A TRUNCATED run " +
        f"still yields a plausible-looking differential, so this is a hard failure, not a warning.")
      k.verifyRetirement(handle.result.take(n))

      // ── Build the steady-state window from the histogram ──────────────────────
      // Use the FIRST..LAST macro-commit span. The histogram is per-cycle; trim to
      // [firstCommitCycle, lastCommitCycle] inclusive.
      val lo = firstCommitCycle.toInt
      val hi = lastCommitCycle.toInt
      val windowHisto = histo.slice(lo, hi + 1)
      // windowCycles = number of cycles spanned (inclusive). IPC counts the macro
      // instructions retired across exactly these cycles.
      val windowCycles = windowHisto.size
      val windowRetired = windowHisto.sum
      val lsOrderWindow = lsOrderHisto.slice(lo, hi + 1)
      if (iqHolOn) {
        // Same window the IPC number uses, capped to the last 1000 cycles so the
        // sample is steady-state rather than including pipeline fill.
        val full = iqHolHisto.slice(lo, hi + 1)
        val w = if (full.size > 1000) full.takeRight(1000) else full
        val blocked = w.filter(_._2)
        val meanFree = if (blocked.isEmpty) 0.0
                       else blocked.map(t => 16 - t._1).sum.toDouble / blocked.size
        val notReady = blocked.count(!_._3)
        val minFree = if (blocked.isEmpty) 0 else blocked.map(t => 16 - t._1).min
        println(f"[iq-hol] ${k.name} sampled=${w.size} line0Occupied=${blocked.size} " +
          f"(${100.0 * blocked.size / scala.math.max(1, w.size)}%.1f%%) " +
          f"meanFreeSlotsWhenBlocked=$meanFree%.2f/16 minFree=$minFree " +
          f"oldestUnready=$notReady (${100.0 * notReady / scala.math.max(1, blocked.size)}%.1f%% of blocked)")
        println(s"[iq-hol] ${k.name} occupancyHist=" +
          w.groupBy(_._1).view.mapValues(_.size).toSeq.sortBy(_._1).mkString(","))
        println(s"[iq-hol] ${k.name} blockerCluster=" +
          blocked.groupBy(_._4).view.mapValues(_.size).toSeq.sortBy(-_._2).mkString(",") +
          " blockerMemOp=" +
          blocked.groupBy(_._5).view.mapValues(_.size).toSeq.sortBy(-_._2).mkString(","))
        // ── SIZING FOR SPECULATIVE LOAD WAKEUP ────────────────────────────────────
        // Split the head-of-line STALLED cycles by what the stalled consumer is waiting
        // for and what CLASS it is. `specLoadWakeup` releases LS-class consumers only, so:
        //   lsWait & LS      the part the shipped mechanism can already reach
        //   lsWait & non-LS  the part an ALU-consumer extension would reach
        //   !lsWait          stalled on something else entirely (slow-ALU, DIV, flags) --
        //                    not addressable by this feature at any scope
        // Measured OFF, this sizes the prize before anything is built.
        val stalled = blocked.filter(!_._3)
        val lsWaitLs    = stalled.count(t => t._6 && t._4.startsWith("LS"))
        val lsWaitOther = stalled.count(t => t._6 && !t._4.startsWith("LS"))
        val noLsWait    = stalled.count(!_._6)
        def pct(n: Int) = 100.0 * n / scala.math.max(1, stalled.size)
        println(f"[hol-dep] ${k.name} headStalled=${stalled.size} " +
          f"waitingOnLOAD-LSconsumer=$lsWaitLs (${pct(lsWaitLs)}%.1f%%) " +
          f"waitingOnLOAD-nonLSconsumer=$lsWaitOther (${pct(lsWaitOther)}%.1f%%) " +
          f"notWaitingOnLoad=$noLsWait (${pct(noLsWait)}%.1f%%)")
        println(s"[hol-dep] ${k.name} stalledLoadWaitersByCluster=" +
          stalled.filter(_._6).groupBy(_._4).view.mapValues(_.size).toSeq.sortBy(-_._2).mkString(","))
        // Where the dependent-chain link actually goes. The extraAlu sweep shows this
        // workload is chain-bound, so per-link latency -- not issue capacity -- sets IPC.
        // Pairing cmd[i] with rsp[i] assumes in-order responses, which holds for a serial
        // chase (at most one load outstanding); maxDcOutstanding is printed so that
        // assumption is visible rather than implied.
        val nPairs = scala.math.min(ldCmdCycles.size, ldRspCycles.size)
        if (nPairs > 8) {
          val rt = (0 until nPairs).map(i => ldRspCycles(i) - ldCmdCycles(i)).filter(_ >= 0)
          val nWb = scala.math.min(nPairs, lsWbCycles.size)
          val use = (0 until nWb).map(i => lsWbCycles(i) - ldCmdCycles(i)).filter(_ >= 0)
          def med(v: Seq[Long]): Long = if (v.isEmpty) -1L else v.sorted.apply(v.size / 2)
          val rtMean = if (rt.isEmpty) 0.0 else rt.sum.toDouble / rt.size
          println(f"[ld-lat] ${k.name} loads=$nPairs cmd->rsp median=${med(rt)} mean=$rtMean%.2f " +
            f"cmd->writeback median=${med(use)} maxDcOutstanding=$maxDcOutstanding")
          val nW = scala.math.min(nPairs, ldRefillWaits.size)
          val waitHit  = (0 until nW).filter(i => ldRspCycles(i) - ldCmdCycles(i) <= 2)
          val waitMiss = (0 until nW).filter(i => ldRspCycles(i) - ldCmdCycles(i) >  2)
          val hitSame  = waitHit.filter(i => ldRefillSameLine(i) == 1)
          val hitDiff  = waitHit.filter(i => ldRefillSameLine(i) == 0)
          println(f"[hum-line] ${k.name} waitedThenHIT-sameLineAsRefill=${hitSame.map(ldRefillWaits).sum} " +
            f"(${hitSame.size} loads, NOT recoverable: the refill is what made them hit) " +
            f"waitedThenHIT-differentLine=${hitDiff.map(ldRefillWaits).sum} (${hitDiff.size} loads, RECOVERABLE) " +
            f"= ${100.0 * hitDiff.map(ldRefillWaits).sum / scala.math.max(1L, windowCycles)}%.1f%% of window")
          println(f"[hum-split] ${k.name} loadsWaitingBehindRefill=${(0 until nW).count(ldRefillWaits(_) > 0)}/$nW " +
            f"recoverableCycles(waitedThenHIT)=${waitHit.map(ldRefillWaits).sum} " +
            f"overWaitedThenMISS=${waitMiss.map(ldRefillWaits).sum} " +
            f"windowCycles=$windowCycles " +
            f"(recoverable = ${100.0 * waitHit.map(ldRefillWaits).sum / scala.math.max(1L, windowCycles)}%.1f%% of window)")
          println(s"[ld-lat] ${k.name} cmd->rsp hist=" +
            rt.groupBy(identity).view.mapValues(_.size).toSeq.sortBy(_._1).take(14).mkString(","))
        }
        // Store path. A single store costs 6.6 cycles/iteration and a LONG store costs
        // exactly the same as a BYTE store, so it is not subword read-modify-write.
        // These counters separate the two remaining explanations: the SQ filling
        // (capacity, which back-pressures dispatch through the `memoryReady` gate that
        // no dispatch perf bucket counts) from the drain being slow (throughput).
        println(s"[ls-bypass] ${k.name} relaxedSelectDifferedCycles=$lsBypassFires " +
          s"livenessReplays=$lsReplays orderViolationReports=$lsOrderViols")
        if (bypLiveOn) println(s"[nzvc-live] ${k.name} nzvcBypassHitCycles=" +
          nzvcLiveCount.take(dut.rfNzvc.logic.bypLive.length).zipWithIndex.map { case (c, i) => s"#$i=$c" }.mkString(" "))
        if (bypLiveOn) println(f"[hum] ${k.name} loadPresentedCycles=$dcLoadPresented refusedCycles=$dcLoadRefused " +
          f"(${100.0 * dcLoadRefused / scala.math.max(1, dcLoadPresented)}%.1f%% of presented) " +
          f"refill=$dcRefusedRefill replay=$dcRefusedReplay idleArb=$dcRefusedIdle windowCycles=$windowCycles")
        if (bypLiveOn) println(s"[hum-why] ${k.name} ofRefillRefused: noProbeEntry=$humNoEntry " +
          s"entryButNoHit=$humEntryNoHit s1Busy=$humS1Busy notOoOk=$humNotOoOk otherTerm=$humOtherTerm")
        if (bypLiveOn) println(s"[hum-why3] ${k.name} realReadArmBlockedBy: shadowBusy=$hum2Shadow " +
          s"s1Busy=$hum2S1 pendingStoreMiss=$hum2StoreMiss sameSetAsRefill=$hum2SameSet " +
          s"storeClaimsPort=$hum2StoreClaim other=$hum2Other")
        if (bypLiveOn) println(f"[hum-why2] ${k.name} onNoEntryCycles=$humNoEntry " +
          f"avgValidEntries=${humEntriesValid.toDouble / scala.math.max(1, humNoEntry)}%.2f " +
          f"avgReadyEntries=${humEntriesReady.toDouble / scala.math.max(1, humNoEntry)}%.2f " +
          f"probeOffered=$humProbeOffered credit=$humProbeCredit launched=$humProbeLaunched storeClaim=$humStoreClaim")
        println(f"[br] ${k.name} branches=$brTotal mispredicts=$brMis " +
          f"(${100.0 * brMis / scala.math.max(1, brTotal)}%.1f%%) noPrediction=$brNoPred " +
          f"| mis-because-unpredicted=$brMisNoPred mis-because-wrong-direction=$brMisWrongDir " +
          f"| byType=${brMisByType.toSeq.sortBy(-_._2).mkString(",")}")
        println(f"[dual-dec] ${k.name} decodeGroups=$decFires dualGroups=$decDualFires " +
          f"(${100.0 * decDualFires / scala.math.max(1, decFires)}%.1f%% of groups carried a slot1)")
        println(s"[ooo] ${k.name} earlyWritebackFires=$oooWbFires cyclesWithAParkedResponse=$oooParkedCyc " +
          s"acceptsDuringRefill=$humAcceptsInRefill probeLaunchesDuringRefill=$humProbeLaunchInRefill")
        if (bypLiveOn) println(s"[alu-wr] ${k.name} eu0fastWrites=$aluFastWr0 eu0slowWrites=$aluSlowWr0 eu1slowWrites=$aluSlowWr1 lsNzvcWrites=$lsNzvcWrites")
        if (bypLiveOn) println(s"[byp-live] ${k.name} intBypassHitCycles=" +
          bypLiveCount.take(dut.rfInt.logic.bypLive.length).zipWithIndex.map { case (c, i) => s"#$i=$c" }.mkString(" "))
        println(f"[st-path] ${k.name} sqAlloc=$sqAllocFires sqDrain=$sqDrainFires " +
          f"drainBlockedCyc=$drainBlockedCyc dcStoreFires=$dcStoreFires dcStoreAcks=$dcStoreAcks " +
          f"hits=$dcStoreHits misses=$dcStoreMisses maxSqResident=$maxSqResident maxSqAccepted=$maxSqAccepted " +
          f"maxDcOutstanding=$maxDcOutstanding")
      }
      // ── L2 COVERAGE. `evict=0` is a FINDING, not a pass: a kernel whose working set
      // fits the 2 MB L2 never takes a DRAM-latency miss, so it cannot measure any
      // lever whose mechanism is "hide a long miss". Printed only for the finite model,
      // because under the unbounded one the answer is 0 by construction.
      if (memCfg.latency.finiteCapacity) {
        val st = dmem.stats
        println(f"[l2] ${k.name}%s hits=${st.l2Hits}%d misses=${st.l2Misses}%d " +
          f"merges=${st.l2SecondaryMerges}%d evictions=${st.l2Evictions}%d " +
          f"missRate=${100.0 * st.l2Misses / scala.math.max(1L, st.l2Hits + st.l2Misses)}%.2f%%")
      }
      // ── `[br-attr]`: the retire-accurate mispredict attribution ─────────────────
      // MPKI here is over the FULL run (raTotalMis / total retired macros), not the
      // steady-state window, so it is directly comparable to the board's
      // OFF_MISPRED_COUNT / retired-instruction ratio.
      {
        val mpki = 1000.0 * raTotalMis / scala.math.max(1, countedMacros)
        val classSum = raBucket.toSeq.filter { case (b, _) => b.startsWith("uncond-") || b.startsWith("cond-") }.map(_._2).sum
        println(f"[br-attr] ${k.name} retiredMacros=$countedMacros retiredTrainableBranches=$raTotalBranches " +
          f"retiredMispredicts=$raTotalMis MPKI=$mpki%.2f " +
          f"misRateOfTrainable=${100.0 * classSum / scala.math.max(1, raTotalBranches)}%.1f%% " +
          f"unattributed=$raUnattributed")
        println(s"[br-attr] ${k.name} buckets=" +
          (if (raBucket.isEmpty) "-" else raBucket.toSeq.sortBy(-_._2).map { case (b, c) =>
            f"$b=$c(${100.0 * c / scala.math.max(1, raTotalMis)}%.0f%%)" }.mkString(" ")))
        // The gap the board's two class counters cannot see: `branchRedirect` counts
        // returns, `debugBranchRetire` does not, so returns fall out of BOTH.
        val retMis = raBucket.getOrElse("ret-nopred", 0) + raBucket.getOrElse("ret-wrongtgt", 0)
        println(f"[br-attr] ${k.name} boardCounterGap: total=$raTotalMis " +
          f"uncond+cond=$classSum invisibleToBothClassCounters=${raTotalMis - classSum} " +
          f"(ofWhichReturns=$retMis)")
        if (raMisPc.nonEmpty)
          println(s"[br-attr-pc] ${k.name} " + raMisPc.toSeq.sortBy(-_._2._2).take(8)
            .map { case (pc, (b, c)) => f"0x$pc%08x:$b=$c" }.mkString(" "))
      }
      // D4: RTL-counted inhibited launches (null counters when the barrier is not built).
      // Zero here means the kernel contains no cache-inhibited access at all, so the
      // bench CANNOT see the barrier's cost -- report that, never a measured "0%".
      if (dut.lsEu.logic.d4Sim != null)
        println(s"[d4-bench] ${k.name} inhibLoadLaunches=${dut.lsEu.logic.d4Sim.loadLaunches.toLong} " +
          s"inhibStoreLaunches=${dut.lsEu.logic.sq.d4StoreLaunches.toLong} " +
          s"quietWait=${dut.lsEu.logic.d4Sim.quietWaitCycles.toLong} " +
          s"youngerHeld=${dut.lsEu.logic.d4Sim.youngerHeldCycles.toLong}")
      println(s"[ls-order-window] ${k.name} cycles=$windowCycles " +
        s"oldestUnready=${lsOrderWindow.count(_._1)} " +
        s"oldestStoreUnready=${lsOrderWindow.count(_._2)} " +
        s"youngerReadyLoadBlocked=${lsOrderWindow.count(_._3)} " +
        s"skidOccupied=${lsOrderWindow.count(_._4)}")
      assert(windowRetired == n - k.warmupInstrs,
        s"[${k.name}] macro histogram counted $windowRetired instructions, expected ${n - k.warmupInstrs}")
      val activeCycles  = windowHisto.count(_ >= 1)
      val dualCycles    = windowHisto.count(_ == 2)

      // ── PAIR CENSUS: why did a retiring cycle retire ONE and not TWO? ───────────
      if (pairCensusOn) {
        val w = censusHisto.slice(lo, hi + 1)
        assert(w.size == windowCycles,
          s"[${k.name}] pair-census samples=${w.size} but the IPC window is $windowCycles")
        val names = Vector("paired", "noSecondEntry", "h1NotComplete", "h1LateByOne",
          "headForbidsPair", "h1RetireAlone", "h1Serializing", "irqBoundary",
          "h0TraceOrPrecise", "other")
        val n = (0 until 10).map(b => w.count(_ == b))
        val retiring = n.sum
        // ARRIVAL ORDER (1..3) vs A GATE SAYING NO (4..9) -- the constraint/accident split.
        val arrival = n(1) + n(2) + n(3)
        val gated   = (4 until 10).map(n).sum
        assert(retiring + w.count(_ < 0) == windowCycles,
          s"[${k.name}] pair census does not close: retiring=$retiring idle=${w.count(_ < 0)}")
        println(f"[pair-census] ${k.name} cycles=$windowCycles retiringCycles=$retiring " +
          f"paired=${n(0)} single=${retiring - n(0)} " +
          f"| ARRIVAL-ORDER=$arrival (noSecondEntry=${n(1)} h1NotComplete=${n(2)} " +
          f"h1LateByOne=${n(3)}) " +
          f"| GATE-SAID-NO=$gated (headForbidsPair=${n(4)} h1RetireAlone=${n(5)} " +
          f"h1Serializing=${n(6)} irqBoundary=${n(7)} h0TraceOrPrecise=${n(8)} other=${n(9)})")
      }

      // ── STALL BUDGET: reduce the per-cycle samples over the SAME IPC window ─────
      val stallBudget = if (!stallBudgetOn) None else {
        val w = budgetHisto.slice(lo, hi + 1)
        assert(w.size == windowCycles,
          s"[${k.name}] stall-budget samples=${w.size} but the IPC window is $windowCycles " +
          "cycles -- the budget is not describing the window it claims to")
        def lanes(s: Int): Int = s & 0xf
        def robBusy(s: Int): Boolean   = (s & 0x10) != 0
        def dcBusy(s: Int): Boolean    = (s & 0x20) != 0
        def walkBusy(s: Int): Boolean  = (s & 0x40) != 0
        def retire0(s: Int): Boolean   = (s & 0x80) != 0
        val b = StallBudget(
          cycles              = windowCycles,
          robEmpty            = w.count(s => !robBusy(s)),
          retireStall         = w.count(s => robBusy(s) && !retire0(s)),
          retireCycles        = w.count(retire0),
          retire1Cycles       = w.count(s => lanes(s) == 1),
          retire2Cycles       = w.count(s => lanes(s) >= 2),
          retiredUops         = w.map(lanes).sum,
          dcStall             = w.count(dcBusy),
          walkStall           = w.count(walkBusy),
          retireStallWithDc   = w.count(s => robBusy(s) && !retire0(s) && dcBusy(s)),
          retireStallWithWalk = w.count(s => robBusy(s) && !retire0(s) && walkBusy(s)),
          robEmptyWithDc      = w.count(s => !robBusy(s) && dcBusy(s)))
        // A decomposition that does not close is not a decomposition. `retire0` is gated
        // on `headReady` = `(count > 0) && ...`, so the three buckets are mutually
        // exclusive AND exhaustive by construction; a non-zero residual means the
        // sampling itself drifted and every number above is suspect.
        assert(b.closes,
          s"[${k.name}] stall budget does not close: cycles=${b.cycles} " +
          s"robEmpty=${b.robEmpty} retireStall=${b.retireStall} retire=${b.retireCycles} " +
          s"residual=${b.residual}")
        println(b.line(k.name))
        // The board can only BOUND macro throughput; sim knows it exactly. Print both
        // views of "did something retire" so their difference (uop retires that carry no
        // macro -- cracked load temps, stack pushes, RMW tails) is visible rather than
        // silently conflated when a sim number is set beside a board counter.
        println(f"[stall-budget-macro] ${k.name} boardRetireCycles=${b.retireCycles} " +
          f"macroActiveCycles=$activeCycles macroDualCycles=$dualCycles " +
          f"macroRetired=$windowRetired uopsRetired=${b.retiredUops} " +
          f"ipc=${windowRetired.toDouble / windowCycles}%.4f")
        Some(b)
      }

      val pipelineProfile = if (!k.profileRetirement) None else {
        val branches = branchEvents.groupBy(e => (e._1, e._2)).toVector.sortBy(_._1).map {
          case ((pc, kind), events) =>
            RetiredBranchStats(pc, kind, events.size, events.count(_._3), events.count(_._4),
              events.count(_._5), events.count(e => e._4 && !e._5))
        }
        val profile = PipelineProfile(branches, robHisto.slice(lo, hi + 1).toVector, dut.rob.logic.depth, lo, hi)
        assert(profile.rob.size == windowCycles)
        println(f"[pipeline-window] ${k.name} macros=$windowRetired cycles=$windowCycles " +
          f"branches=${profile.retiredBranches} misses=${profile.branchMisses} " +
          f"MPKI=${1000.0 * profile.branchMisses / windowRetired}%.3f " +
          f"meanRob=${profile.meanOccupancy}%.2f noPairCapacity=${profile.noPairCapacityCycles} " +
          s"headIncomplete=${profile.headIncompleteCycles} nonemptyNoRetire=${profile.nonemptyNoRetireCycles} " +
          s"completedBacklog=${profile.completedBacklogCycles} dualWithExtraComplete=${profile.dualWithExtraCompleteCycles} " +
          s"branchPairPotential=${profile.branchPairPotentialCycles}")
        branches.foreach { b =>
          println(f"[retired-branch-window] ${k.name} pc=0x${b.pc}%08x type=${b.branchType} " +
            s"retired=${b.retired} taken=${b.taken} misses=${b.misses} " +
            s"phtTrained=${b.phtTrained} untrainedMisses=${b.untrainedMisses}")
        }
        val historyWindow = historyHisto.slice(lo, hi + 1)
        assert(historyWindow.size == windowCycles)
        println(s"[history-window] ${k.name} cycles=$windowCycles " +
          s"repairs=${historyWindow.count(_._1)} sameEdgeShift=${historyWindow.count(_._2)} " +
          s"earlyPendingShifts=${historyWindow.count(_._3)} " +
          s"keptFrontendRepairAfterShifts=${historyWindow.count(_._4)} " +
          s"wholeRunTraceEvents=$historyTraceEvents")
        if (dut.gsh.logic.retainedRepair.nonEmpty) {
          println(s"[history-check] ${k.name} fullRunRebasedRepairs=$historyCheckedRepairs")
          if (k.name.startsWith("deep-backlog")) assert(historyCheckedRepairs > 0)
        }
        Some(profile)
      }

      missInj.publish(windowCycles, windowRetired)
      MissInjector.archDump(k.name, handle.result)
      result = IpcResult(k.name, windowRetired, windowCycles, activeCycles, dualCycles,
        ftbApplies, ftqConfirms, ftqMismatches,
        ftbDirDeclines, ftbFrameDeclines, ftbBusyDeclines, sqFwdHitCycles,
        flushToCommit.toVector,
        ldCmdCycles.toVector, ldCmdAddrs.toVector, ldRspCycles.toVector, lsWbCycles.toVector,
        sqForwardHisto.slice(lo, hi + 1).count(identity), pipelineProfile,
        lateStoreHisto.slice(lo, hi + 1).count(identity),
        reserveStoreHisto.slice(lo, hi + 1).count(_._1),
        reserveStoreHisto.slice(lo, hi + 1).count(_._2),
        reserveStoreHisto.slice(lo, hi + 1).count(_._3),
        detachedOvertakeHisto.slice(lo, hi + 1).count(identity),
        publicationForwardHisto.slice(lo, hi + 1).count(identity),
        readyAdmissionHisto.slice(lo, hi + 1).count(identity),
        pendingOwnerWaitHisto.slice(lo, hi + 1).count(_._1),
        pendingOwnerWaitHisto.slice(lo, hi + 1).count(_._2),
        pendingOwnerWaitHisto.slice(lo, hi + 1).count(_._3),
        captureIssueHisto.slice(lo, hi + 1).count(_._1),
        captureIssueHisto.slice(lo, hi + 1).count(_._2),
        captureIssueHisto.slice(lo, hi + 1).count(_._3),
        queuedAdmissionHisto.slice(lo, hi + 1).count(identity),
        dcLdLookupHisto.slice(lo, hi + 1).count(identity),
        dcLdMissHisto.slice(lo, hi + 1).count(identity),
        t2BranchRedirects,
        stallBudget)
      if (traceOn) {
        println(s"=== LOAD-PATH CYCLE TRACE: ${k.name} ===")
        println("cycle  P1 P2 PT P3 P4 C0 C1 C2 RS CM WB   (# = active)")
        traceLines.foreach(println)
        println(s"=== end trace (${traceLines.size} cycles) ===")
      }
      val r2rN   = resolveToRetire.size
      val r2rAvg = if (r2rN == 0) 0.0 else resolveToRetire.sum.toDouble / r2rN
      val r2rHis = resolveToRetire.groupBy(identity).toVector.sortBy(_._1)
        .map { case (d, xs) => s"$d:${xs.size}" }.mkString(", ")
      println(f"[tier1] ${k.name} earlyFire=$t1EarlyFires pendCyc=$t1PendCycles " +
        f"suppressed=$t1Suppressed doFlush=$t2Flushes branchRedirect=$t2BranchRedirects " +
        f"pendFeedV=$t1PendFeedValid pendFeedR=$t1PendFeedReady pendFeedFire=$t1PendFeedFire")
      println(f"[resolve2retire] ${k.name} n=$r2rN avg=$r2rAvg%.2f  histogram{$r2rHis}")
      val f2cN   = flushToCommit.size
      val f2cAvg = if (f2cN == 0) 0.0 else flushToCommit.sum.toDouble / f2cN
      val f2cHis = flushToCommit.groupBy(identity).toVector.sortBy(_._1)
        .map { case (d, xs) => s"$d:${xs.size}" }.mkString(", ")
      println(f"[flush2commit] ${k.name} n=$f2cN avg=$f2cAvg%.2f feedFires=$feedFires  histogram{$f2cHis}")
      if (k.copybackDtt) {
        println(s"[store-path] lsIssue=$lsIssueFires sqAlloc=$sqAllocFires fastAlloc=$fastSqAllocs " +
          s"sqDrainFire=$sqDrainFires dcStoreFire=$dcStoreFires ack=$dcStoreAcks " +
          s"s3Hit=$dcStoreHits miss=$dcStoreMisses " +
          s"issueValidCyc=$lsIssueValidCyc drainValidCyc=$drainValidCyc " +
          s"drainBlockedCyc=$drainBlockedCyc maxSqResident=$maxSqResident " +
          s"maxSqAccepted=$maxSqAccepted maxDcOutstanding=$maxDcOutstanding")
      }
    }
    result
  }

  // ── Kernel suite ────────────────────────────────────────────────────────────
  // Kernels use identity addressing (MMU off, except store-stream's match-all
  // transparent COPYBACK DTT), and only implemented opcodes/EAs (reg-reg ALU,
  // MOVEQ, MOVE, absolute & (An) loads/stores, Bcc). NO DIV/MUL/CHK.

  // 1. dependent-ALU: a long chain of dependent adds. Each add reads the previous
  //    add's result -> the OoO core cannot overlap them; latency-bound. IPC ~ 1.
  def kDependentAlu: Kernel = {
    val setup = Seq("moveq #1,%d0", "moveq #1,%d1")
    // Alternate add.l d0,d1 ; add.l d1,d0 : a strict dependency chain (each uses
    // the prior result). 400 dependent adds (long enough that fill/drain is noise).
    val chain = (0 until 400).map(i => if (i % 2 == 0) "add.l %d0,%d1" else "add.l %d1,%d0")
    val src = (setup ++ chain).mkString(" ; ")
    Kernel("dependent-ALU", src, setup.size + chain.size)
  }

  // 2. independent-ALU: many independent add chains across distinct registers, with
  //    NO cross-register dependency within a group -> the two ALU EUs can both
  //    retire each cycle. Superscalar best case. IPC -> ~2.
  def kIndependentAlu: Kernel = {
    val setup = (0 to 7).map(r => s"moveq #${r + 1},%d$r")
    // 6 independent accumulators (d2..d7), each accumulating a constant-ish source
    // from d0/d1 (read-only). add.l %d0,%dN and add.l %d1,%dN never write d0/d1,
    // so the only dependency is dN on its OWN previous add (a per-register chain),
    // but the 6 chains are mutually independent -> 2 retire per cycle.
    val regs = Seq(2, 3, 4, 5, 6, 7)
    val body = (0 until 70).flatMap { _ =>
      regs.map(r => s"add.l %d0,%d$r")
    }
    val src = (setup ++ body).mkString(" ; ")
    Kernel("independent-ALU", src, setup.size + body.size)
  }

  // 3. load/store stream: a STRAIGHT-LINE unrolled stream of store+load+ALU over a
  //    small set of D-cache lines (all hits after first refill). Measures load/store
  //    throughput (store-queue drain, SQ-forward, D-cache hit). Unrolled (not a
  //    backward loop) so the LS pipe is never crossed by a mispredict redirect —
  //    see note in kMixed; a load that trails a taken-branch redirect exposes a
  //    core-level LS-replay stall, which is out of scope for this measurement tool.
  def kLoadStore: Kernel = {
    // Walk a 4-line window (offsets 0x3000,0x3010,0x3020,0x3030). For each of the
    // `unroll` repetitions and each of the 4 lines:
    //   move.l %dS,addr   store (1 macro)
    //   move.l addr,%d0   load back (cracks [load->T0, move T0->d0] = 1 macro)
    //   add.l  %d1,%d0    ALU consume the loaded value (1 macro)
    // 3 macros per (rep,line). 24 reps * 4 lines = 96 groups -> 288 body macros.
    val reps  = 24
    val lines = Seq(0x3000, 0x3010, 0x3020, 0x3030)
    val setup = Seq("moveq #1,%d1", "moveq #42,%d2")
    val body  = (0 until reps).flatMap { _ =>
      lines.flatMap { a =>
        Seq(f"move.l %%d2,0x$a%x", f"move.l 0x$a%x,%%d0", "add.l %d1,%d0")
      }
    }
    val src = (setup ++ body).mkString(" ; ")
    Kernel("load/store", src, setup.size + reps * lines.size * 3)
  }

  // 4. branchy: a loop whose body contains a data-dependent conditional branch that
  //    alternates taken / not-taken. No branch predictor -> a taken conditional is a
  //    commit-time mispredict redirect (squash + refetch). Measures branch cost.
  def kBranchy: Kernel = {
    // d7 = counter (down from 40); d1 = 1; d6 = toggle (0/1); d0 = accumulator.
    // Body per iter:
    //   sub.l %d1,%d6     toggle: d6 = d6 - 1. Starts 0 -> -1 (nonzero), next -1-1=-2
    //                     ... always nonzero after the first, so use a parity trick:
    //   We instead alternate by comparing d6 against d5: see below.
    // Simpler deterministic alternation with the in-scope op set: keep a 1-bit
    // toggle in d6 by ADD-ing d1(=1) then AND-ing with d4(=1):
    //   add.l %d1,%d6     d6 += 1
    //   and.l %d4,%d6     d6 &= 1  -> 1,0,1,0,...  (AND sets Z)
    //   beq.s .Lskip      Z=1 (d6==0) -> taken (skip the add); Z=0 -> fall through
    //   add.l %d1,%d0     (only on odd iters where d6==1)
    // .Lskip:
    //   sub.l %d1,%d7     decrement counter (sets Z at 0)
    //   bne.s .Lbr        loop
    // Per iter macros: add(1)+and(1)+beq(1)+[add(1) if d6!=0]+sub(1)+bne(1).
    // d6 sequence after add+and: iter1 ->1 (beq NOT taken, add runs, 6 macros),
    // iter2 ->0 (beq taken, skip add, 5 macros), alternating. 40 iters: 20 of 6
    // + 20 of 5 = 220 body macros. (The final bne, iter40, is not-taken.)
    val iters = 40
    val setup = Seq("moveq #40,%d7", "moveq #1,%d1", "moveq #0,%d6", "moveq #1,%d4", "moveq #0,%d0")
    val body =
      ".Lbr: add.l %d1,%d6 ; and.l %d4,%d6 ; beq.s .Lskip ; add.l %d1,%d0 ; " +
      ".Lskip: sub.l %d1,%d7 ; bne.s .Lbr"
    val src = setup.mkString(" ; ") + " ; " + body
    Kernel("branchy", src, setup.size + 220)
  }

  // 4a. deep-backlog: SYNTHETIC. Built for the two-tier-reschedule measurement
  //     (2026-09-04), reconstructed from the description in
  //     docs/superpowers/specs/2026-09-03-early-flush-ipc-ab-measurement.md §2.3.
  //     `branchy`'s mispredicting branch reaches the ROB head almost immediately
  //     (measured resolve->retire delay ~1 cycle), so it CANNOT resolve any
  //     mechanism whose whole benefit is "start refetching before the branch
  //     retires". This kernel deliberately pins the ROB head far behind the
  //     branch so that delay is large:
  //       * 5 dependent slow-path shifts on d2 hold the head for tens of cycles
  //         (the ALU slow path is a multi-cycle dependent chain);
  //       * 12 independent `add.l %d1,%aN` complete in a cycle each and then just
  //         SIT in the ROB, completed-but-unretired (ADDA writes no NZVC, so they
  //         neither depend on nor disturb the branch's flag producer);
  //       * the branch's own flag producer depends on nothing in that chain, so
  //         the Bcc resolves in the EU almost immediately -- while the head is
  //         still stuck ~15 entries behind it.
  //     Read it as an UPPER BOUND on the mechanism, not as representative code.
  def kDeepBacklog: Kernel = {
    val iters = 20
    val setup = Seq("moveq #20,%d7", "moveq #1,%d1", "moveq #0,%d6", "moveq #1,%d4",
                    "moveq #0,%d0", "moveq #17,%d2", "moveq #3,%d3")
    // 5 dependent slow-path shifts on d2 (each depends on the previous result).
    val shifts = (0 until 5).map(_ => "lsl.l %d3,%d2")
    // 12 independent ADDA (no NZVC write, no dependency on the shift chain).
    val addas  = (0 until 12).map(i => f"add.l %%d1,%%a${i % 6}%d")
    val body =
      ".Ldb: " + (shifts ++ addas).mkString(" ; ") +
      " ; add.l %d1,%d6 ; and.l %d4,%d6 ; beq.s .Ldbskip ; add.l %d1,%d0 ; " +
      ".Ldbskip: sub.l %d1,%d7 ; bne.s .Ldb"
    val src = setup.mkString(" ; ") + " ; " + body
    // Per iter: 5 shifts + 12 addas + add + and + beq + (add on odd iters) + sub + bne
    //   = 22 always + 1 on half the iterations.
    Kernel("deep-backlog", src, setup.size + iters * 22 + iters / 2)
  }

  // 4b. hot-loop: a TIGHT backward `bne.s` loop with a tiny independent-ALU body. The
  //     back-edge is taken on EVERY iteration but the loop-top BTB entry warms after the
  //     first 1-2 iterations -> the back-edge is predicted-taken -> NO squash. Without
  //     prediction every back-edge is a ~5-6cyc commit-time mispredict; with it the loop
  //     approaches the backend's dual-retire ceiling. This is the kernel the predictor
  //     most directly targets (a hot back-edge).
  def kHotLoop: Kernel = {
    // d7 = trip count (down from 100); d1 = 1; d0/d2 = independent accumulators (ILP).
    // Body per iter: add.l %d1,%d0 ; add.l %d1,%d2 ; sub.l %d1,%d7 ; bne.s .Lhot
    //   -> 4 macros/iter, the bne taken 99x (back-edge) + 1 not-taken exit.
    val iters = 100
    val setup = Seq("moveq #100,%d7", "moveq #1,%d1", "moveq #0,%d0", "moveq #0,%d2")
    val body  = ".Lhot: add.l %d1,%d0 ; add.l %d1,%d2 ; sub.l %d1,%d7 ; bne.s .Lhot"
    val src = setup.mkString(" ; ") + " ; " + body
    Kernel("hot-loop", src, setup.size + iters * 4)
  }

  // 5. mixed: a STRAIGHT-LINE realistic blend of ALU + memory + branch. The branch
  //    in each group is a NOT-TAKEN forward beq (a value compared against a
  //    different value -> Z=0 -> falls through). A not-taken branch exercises the
  //    branch EU's decode/issue/resolve WITHOUT a commit-time redirect, so it never
  //    flushes the in-flight LS pipe (the loop+load core stall noted in
  //    kLoadStore). This blends all three EU classes in steady state.
  def kMixed: Kernel = {
    // Per group (over 4 D-cache lines, `reps` repetitions):
    //   move.l %d2,addr   store (1 macro)
    //   move.l addr,%d0   load  (1 macro)
    //   add.l  %d3,%d0    ALU
    //   sub.l  %d4,%d0    ALU
    //   add.l  %d1,%d5    independent accumulate (ILP)
    //   cmp.l  %d6,%d0    compare (d6 never equals d0 here) -> Z=0
    //   beq.s  .LkN       NOT taken (falls through, no redirect). It skips the next
    //   add.l  %d1,%d5    add (a non-zero byte displacement); since beq is never
    // .LkN:               taken, this add ALWAYS runs.
    // 8 macros per group (the skipped add always executes). Each group gets a UNIQUE
    // skip label so the unroll is straight-line. reps=10 * 4 lines = 40 groups ->
    // 320 body macros.
    val reps  = 10
    val lines = Seq(0x3800, 0x3810, 0x3820, 0x3830)
    val setup = Seq("moveq #1,%d1", "moveq #42,%d2", "moveq #3,%d3", "moveq #5,%d4",
                    "moveq #0,%d5", "moveq #0x7f,%d6")
    var lbl = 0
    val body = (0 until reps).flatMap { _ =>
      lines.flatMap { a =>
        val L = s".Lmk$lbl"; lbl += 1
        Seq(
          f"move.l %%d2,0x$a%x", f"move.l 0x$a%x,%%d0", "add.l %d3,%d0", "sub.l %d4,%d0",
          "add.l %d1,%d5", "cmp.l %d6,%d0", s"beq.s $L", "add.l %d1,%d5", s"$L:")
      }
    }
    // The `$L:` is a label, not an instruction; count only the 8 real instrs/group.
    val src = (setup ++ body).mkString(" ; ")
    Kernel("mixed", src, setup.size + reps * lines.size * 8)
  }

  // 5b. load-stream: NON-FORWARDED D-cache load throughput. This kernel exists to
  //     close a real BENCHMARK-COVERAGE GAP found by the 2026-08-09 "LS EU pipeline
  //     depth" grounding pass: `load/store` and `mixed` are BOTH same-address
  //     store-then-load-back patterns whose every load is satisfied by the store
  //     queue (100% SQ-forward, measured 25/25; LAUNCH/WAIT occupancy exactly 0
  //     cycles). Neither of them ever performs a real D-cache read, so the suite
  //     could not measure the far more common case — a load that does NOT forward.
  //
  //     Construction:
  //       * ZERO stores anywhere in the kernel => the store queue is permanently
  //         empty => `sq.io.fwd.rsp.hit`/`.stall` are always False => every load
  //         takes the genuine cache path (LsEuPlugin RESOLVE -> LAUNCH -> WAIT ->
  //         `dcache.loadRsp`). This is the exact path the two existing kernels skip.
  //       * 6 loads per iteration to 6 DISTINCT long-aligned addresses spanning two
  //         16-byte D-cache lines (0x4000..0x4014), each into a DIFFERENT destination
  //         register => no load-to-load data dependency, so the measured rate is LS
  //         EU + D-cache THROUGHPUT (initiation interval), not load-use latency.
  //       * a TIGHT backward loop (not the straight-line unroll `load/store` uses) so
  //         the body stays I-cache resident: under `IPC_MEM=l2:5:70` a long
  //         straight-line unroll becomes I-fetch-bound (documented 66-77% loss on
  //         straight-line kernels) and would mask the D-side effect entirely. The
  //         back-edge is BTB-predicted after warmup (same mechanism `hot-loop`
  //         relies on), so no commit-time redirect crosses the LS pipe.
  //       * the working set is 2 lines / 32 bytes, far under L1D capacity, so after
  //         the first iteration every access is an L1D HIT — this measures hit
  //         throughput, not refill latency.
  //     Per iter: 6 loads + subq + bne = 8 macros.
  /** Dhrystone-shaped kernel for the real-world latency profile.
    *
    * The existing kernels do not reproduce the board's dispatch behaviour: measured
    * under IPC_MEM=l2 they hold 2-9 of the 16 IQ slots and block dispatch at most
    * 38.6% of cycles, against 57.2% `blocked_iq` on the board running Dhrystone. The
    * difference is the shape of the memory dependence, not the memory model:
    *   - `kChaseLoop` is zero-filled, so its "chase" reloads ONE line forever -- a
    *     load-to-use latency probe with a resident working set.
    *   - `kLoadStream` issues six INDEPENDENT loads, which overlap.
    * Dhrystone does neither. It dereferences a record pointer, then reads a field
    * THROUGH that pointer, so two dependent L1 misses serialise per iteration, and
    * it does that over a working set larger than the 8 KiB L1D.
    *
    * So this walks a SHUFFLED 16 KiB cycle of 1024 sixteen-byte records -- twice the
    * cache, one record per line, in an order no stride prefetcher can follow -- and
    * per iteration does: chase the pointer, load the record's field through it,
    * consume that field in the ALU, copy two string bytes, and do one independent
    * increment. The increment and the copy are register-independent of the chase, so
    * they are exactly the work a machine that is NOT head-of-line blocked would
    * overlap with the misses.
    *
    * `verifyChase` asserts the walk really happened: a derailed chain (loaded value
    * not a valid record pointer) would collapse to a handful of lines and every
    * number measured here would be meaningless.
    */
  /** Pure pointer chase: isolates LOAD-TO-USE latency, the quantity that sets IPC on
    * chain-bound code.
    *
    * The dhrystone sweep showed this workload is chain-bound (8 extra independent ALU
    * ops per iteration doubled retired work for 10.5% more cycles), and the D-cache
    * measurement showed 84% of its loads return in ONE cycle. So the ~10 cycles per
    * dependent link are not cache latency -- they are the LS pipeline plus wakeup plus
    * re-issue. This kernel removes everything else so cycles/iteration IS that number.
    *
    * The record set is sized to FIT: 512 x 16 B over 128 sets is 4 per set against 4
    * ways, so the walk is L1-resident by construction and the measurement is the
    * hit-path load-to-use, not a miss.
    */
  def kChasePure(records: Int = 512, iters: Int = 2048): Kernel = {
    val RecBase = 0x10000L
    val RecBytes = 16
    val order = { val rng = new scala.util.Random(0x5eed); rng.shuffle((0 until records).toVector) }
    val prep: MemHandles => Unit = { h =>
      for (i <- 0 until records) {
        val here = RecBase + order(i) * RecBytes
        val next = RecBase + order((i + 1) % records) * RecBytes
        h.dmem.pokeByte(here + 0, ((next >> 24) & 0xff).toInt)
        h.dmem.pokeByte(here + 1, ((next >> 16) & 0xff).toInt)
        h.dmem.pokeByte(here + 2, ((next >> 8) & 0xff).toInt)
        h.dmem.pokeByte(here + 3, (next & 0xff).toInt)
      }
    }
    val setup = Seq(f"lea 0x$RecBase%x,%%a0", s"move.l #$iters,%d7")
    val body  = Seq("move.l (%a0),%a0", "subq.l #1,%d7", "bne.s .Lchase")
    val src = (setup ++ Seq(".Lchase: " + body.mkString(" ; "))).mkString(" ; ")
    Kernel("chase-pure", src, setup.size + iters * body.size,
      zeroFillData = true, prepMem = prep,
      warmupInstrs = setup.size + records * body.size)
  }

  def kDhrystone(records: Int = 512, iters: Int = 2048, extraAlu: Int = 0,
                 strCopy: Boolean = true, copyStyle: String = "byteMemMem",
                 copyback: Boolean = false): Kernel = {
    val RecBase  = 0x10000L
    val RecBytes = 16
    val StrSrc   = 0x20000L
    val StrDst   = 0x28000L

    // WHY THESE SIZES. The point is L1-miss / L2-HIT, which is where the board
    // actually runs: `records * 16 B` = 8 KiB is exactly L1D's capacity (8 KiB,
    // 4-way, 16 B lines), so a cyclic walk thrashes L1 by construction, while the
    // whole set is only 128 of the L2 model's 64 B lines and stays resident.
    //
    // The first pass through the cycle is all COLD, and the latency model charges
    // dramCycles(40) + fill for a line it has not seen (`l2Lines.contains`). An
    // earlier version of this kernel walked 200 records of a 1024-record cycle, so
    // EVERY access was a first touch and it measured cold DRAM -- 94 cycles per
    // iteration and IPC 0.075, which is not the board's regime. `warmupInstrs`
    // excludes exactly one full cold walk so the measured window is warm.
    val order = {
      val rng = new scala.util.Random(0x5eed)
      rng.shuffle((0 until records).toVector)
    }

    val prep: MemHandles => Unit = { h =>
      for (i <- 0 until records) {
        val here = RecBase + order(i) * RecBytes
        val next = RecBase + order((i + 1) % records) * RecBytes
        // m68k is big-endian: most significant byte first.
        h.dmem.pokeByte(here + 0, ((next >> 24) & 0xff).toInt)
        h.dmem.pokeByte(here + 1, ((next >> 16) & 0xff).toInt)
        h.dmem.pokeByte(here + 2, ((next >> 8) & 0xff).toInt)
        h.dmem.pokeByte(here + 3, (next & 0xff).toInt)
        h.dmem.pokeByte(here + 4, 0); h.dmem.pokeByte(here + 5, 0)
        h.dmem.pokeByte(here + 6, 0); h.dmem.pokeByte(here + 7, 1)
      }
      // The string source is re-read cyclically; keep it inside one page.
      for (i <- 0 until 4096) h.dmem.pokeByte(StrSrc + i, 0x41 + (i % 26))
    }

    // `extraAlu` adds independent ALU work per iteration. It is the DISCRIMINATOR
    // between the two possible readings of the blocked-dispatch measurement:
    //   chain-bound  -- cycles/iteration is set by the serial dependent-load chain,
    //                   dispatch has spare capacity, and added independent work is
    //                   absorbed for free (cycles flat, IPC rises).
    //   dispatch-bound -- the head-of-line block genuinely turns work away, so added
    //                   independent work cannot be absorbed (cycles grow, IPC flat).
    // Only in the second case does unblocking `push.ready` buy throughput.
    val extras = (0 until extraAlu).map(i => s"addq.l #1,%d${3 + (i % 3)}")
    val setup = Seq(
      f"lea 0x$RecBase%x,%%a0", f"lea 0x$StrSrc%x,%%a2", f"lea 0x$StrDst%x,%%a3",
      "moveq #0,%d1", "moveq #0,%d2", "moveq #0,%d3", "moveq #0,%d4", "moveq #0,%d5",
      s"move.l #$iters,%d7")
    val body = Seq(
      "move.l (%a0),%a0",        // chase: dependent load INTO the address register
      "move.l 4(%a0),%d0",       // field read THROUGH the chased pointer
      "add.l %d0,%d1") ++            // consume the loaded field
      // The byte copy is a load AND a store, both independent of the chase. Dropping
      // it isolates how much of the iteration the STORE side costs: the chain accounts
      // for 2 links, and whatever remains is this plus loop control. The board's
      // head_store counter is 31%, so this is worth separating rather than assuming.
      (if (!strCopy) Nil else copyStyle match {
        // Decompose the 10.45 cycles/iteration that one byte copy costs.
        //   byteMemMem : the 68k string primitive -- memory-to-memory, both postinc
        //   longMemMem : same shape, LONG -- isolates subword/read-modify-write cost
        //   byteSplit  : the same work as two instructions through a register --
        //                isolates the cost of cracking a mem-to-mem move
        //   byteAbs    : absolute addressing -- isolates the postincrement updates
        case "byteMemMem" => Seq("move.b (%a2)+,(%a3)+")
        case "longMemMem" => Seq("move.l (%a2)+,(%a3)+")
        case "byteSplit"  => Seq("move.b (%a2)+,%d6", "move.b %d6,(%a3)+")
        case "byteAbs"    => Seq("move.b 0x20000,%d6", "move.b %d6,0x28000")
        // Split byteAbs into its halves: which side costs the 7.66 cycles?
        // Four copies, postincrement: iteration N+1's addresses depend on N's updates.
        case "byteX4"     => Seq.fill(4)("move.b (%a2)+,(%a3)+")
        // The SAME four copies with fixed displacements off a base bumped once. The
        // per-copy loop-carried address dependence is gone; the copies are independent.
        // If this is materially faster per copy, the postincrement update is sitting on
        // the critical path even though it needs only An, never the loaded data.
        case "byteDispX4" => Seq("move.b 0(%a2),0(%a3)", "move.b 1(%a2),1(%a3)",
                                 "move.b 2(%a2),2(%a3)", "move.b 3(%a2),3(%a3)",
                                 "addq.l #4,%a2", "addq.l #4,%a3")
        // Which side's An auto-update is on the critical path? Same four copies, with
        // the postincrement on only ONE side and an explicit addq for the other. The
        // store's An rides the store uop and writes back at LS COMPLETION (autoStoreAn);
        // the load's does not. If only the store-postinc variant is slow, that writeback
        // timing is the cost and moving it to S1 -- where base+delta is already known --
        // is the fix.
        case "byteLdIncX4" => Seq("move.b (%a2)+,0(%a3)", "move.b (%a2)+,1(%a3)",
                                  "move.b (%a2)+,2(%a3)", "move.b (%a2)+,3(%a3)",
                                  "addq.l #4,%a3")
        case "byteStIncX4" => Seq("move.b 0(%a2),(%a3)+", "move.b 1(%a2),(%a3)+",
                                  "move.b 2(%a2),(%a3)+", "move.b 3(%a2),(%a3)+",
                                  "addq.l #4,%a2")
        case "byteLoadOnly"  => Seq("move.b 0x20000,%d6")
        case "byteStoreOnly" => Seq("move.b %d2,0x28000")
        case "longStoreOnly" => Seq("move.l %d2,0x28000")
        case other        => throw new IllegalArgumentException(s"copyStyle: $other")
      }) ++ Seq(
      "addq.l #1,%d2") ++ extras ++ Seq(
      "subq.l #1,%d7", "bne.s .Ldhry")
    val perIter = body.size
    val src = (setup ++ Seq(".Ldhry: " + body.mkString(" ; "))).mkString(" ; ")
    // copybackDtt supplies COPYBACK translation. WITHOUT it every store is precise /
    // write-through, which is a BENCH artifact and not what the Mac runs -- the recorded
    // gap is ~12.4 cycles precise vs ~1.31 copyback. A store-cost number measured with
    // this false does not describe the real machine.
    Kernel(s"dhrystone-x$extraAlu${if (strCopy) (if (copyStyle == "byteMemMem") "" else "-" + copyStyle) else "-nocopy"}${if (copyback) "-cb" else ""}", src, setup.size + iters * perIter,
      copybackDtt = copyback,
      zeroFillData = true, prepMem = prep,
      warmupInstrs = setup.size + records * perIter)   // one full cold walk
  }

  /** MEMCPY BANDWIDTH probe -- the instrument this corpus has never had.
    *
    * The project's goal is a core with high parallelism and high BANDWIDTH, and
    * nothing here measures bandwidth. `load-stream`'s ENTIRE working set is TWO
    * LINES; every other kernel fits comfortably inside the 8 KB L1. So the bench has
    * never once asked how many BYTES PER CYCLE this machine can move.
    *
    * SIZING IS THE DESIGN. L1D is 8 KB, 4-way, 16-byte lines; the SoC L2 is 2 MB,
    * 8-way, 64-byte lines (`l2c_data.v:4`). A working set that EXCEEDS L1 but FITS IN
    * L2 puts every access in the regime that matters for memory-level parallelism:
    * every load misses L1 and hits L2, and because the L2 line is 4x the L1 line a
    * sequential stream takes FOUR consecutive L1 misses per L2 line -- all serialised
    * today, since D-side MLP is measured at exactly 1.000 mean AND 1 max at every
    * injected miss rate up to 100%.
    *
    * Little's Law is why this is the D-side MSHR lever's instrument:
    *
    *     bandwidth = bytes_per_miss x MLP / miss_latency
    *
    * At MLP 1, 16 bytes and ~10 cycles per miss that is ~1.6 B/cycle -- and NO
    * workload can beat it however well it streams. **Report BYTES PER CYCLE**, not
    * only a cycle delta: a cycle delta on a fixed copy hides which term moved.
    *
    * `passes` is load-bearing, not padding. A SINGLE pass touches every line for the
    * first time, so every miss is compulsory and goes to DRAM -- that measures DRAM
    * streaming, not the L2-hit regime. Pass 1 is therefore charged to `warmupInstrs`
    * and passes 2..N are the measurement, where the region is L2-resident but still
    * far too big for L1.
    *
    * ⚠️ `copybackDtt = true` is MANDATORY here, not a variant. Under the default
    * `copybackDtt = false` EVERY store is PRECISE (~12.4 cycles against ~1.31), which
    * turns a bandwidth bench into a store-latency bench -- the default that has
    * already produced two wrong conclusions in this project.
    *
    * The body is `move.l (%a0)+,(%a1)+` x4 = exactly ONE 16-byte L1 line per
    * iteration, so iterations map 1:1 onto L1 lines. Loop control is `subq.l`/`bne.s`
    * DELIBERATELY rather than `dbra`, so this kernel does not confound with the
    * slot-1 DBcc deferral lever.
    *
    * Verification is real, not a liveness check: `prepMem` fills the source so every
    * long CONTAINS ITS OWN ADDRESS, and the epilogue loads the LAST copied long.
    * `d0 == Src + bytes - 4` proves the data actually moved AND landed at the right
    * offset; `d7 == 0` proves the inner loop ran to completion. A copy that shifted
    * by one long, or stopped early, fails both.
    *
    * ⛔ CORRECTED 2026-09-28. This comment used to claim "a MOVE16-based copy is NOT
    * currently a faster path and this `move.l` loop is the fair baseline". **MEASURED
    * FALSE by 35%**: `kMemcpyMove16` runs the SAME 8 D-cache accesses per 16 bytes in
    * **36.084 cyc/line against this loop's 48.819** (`Move16OracleSpec`, both seeds),
    * because MOVE16's microcode issues L,L,S,S rather than this loop's L,S,L,S and the
    * interleave lets the source fill and the destination write-allocate fill overlap
    * through the single refill MSHR. And the ROM's own `BlockMove` uses MOVE16, so for
    * real block copy **`kMemcpyMove16` is the fair baseline and this loop is not**.
    * See `docs/PERF_LEVER_QUEUE.md` lever 17. The wrong comment is why the corpus
    * contained zero MOVE16 instructions for as long as it did. */
  def kMemcpy(bytes: Int = 16384, passes: Int = 4, label: String = "memcpy-16k"): Kernel = {
    require(bytes % 16 == 0, "memcpy bytes must be a whole number of 16-byte L1 lines")
    require(passes >= 2, "pass 1 is the L2 warm-up and is excluded; need at least one measured pass")
    val Src = 0x00500000L
    val Dst = 0x00600000L
    val iters = bytes / 16
    val prep: MemHandles => Unit = { h =>
      var a = 0
      while (a < bytes) {
        val v = Src + a          // every long contains its own address
        h.dmem.pokeByte(Src + a + 0, ((v >> 24) & 0xff).toInt)
        h.dmem.pokeByte(Src + a + 1, ((v >> 16) & 0xff).toInt)
        h.dmem.pokeByte(Src + a + 2, ((v >> 8) & 0xff).toInt)
        h.dmem.pokeByte(Src + a + 3, (v & 0xff).toInt)
        a += 4
      }
    }
    val setup = Seq(f"lea 0x$Src%x,%%a2", f"lea 0x$Dst%x,%%a3", s"move.l #$passes,%d6")
    val outer = Seq("movea.l %a2,%a0", "movea.l %a3,%a1", s"move.l #$iters,%d7")
    val body  = Seq("move.l (%a0)+,(%a1)+", "move.l (%a0)+,(%a1)+",
                    "move.l (%a0)+,(%a1)+", "move.l (%a0)+,(%a1)+",
                    "subq.l #1,%d7", "bne.s .Lcpy")
    val tail  = Seq("subq.l #1,%d6", "bne.s .Louter")
    val epi   = Seq(f"move.l 0x${Dst + bytes - 4}%x,%%d0")
    val src = (setup ++ Seq(".Louter: " + outer.mkString(" ; "),
                            ".Lcpy: "   + body.mkString(" ; "),
                            tail.mkString(" ; ")) ++ epi ++
               Seq(".Lcpyend: bra.s .Lcpyend")).mkString(" ; ")
    val perPass = outer.size + iters * body.size + tail.size
    Kernel(label, src, setup.size + passes * perPass + epi.size,
      copybackDtt = true, zeroFillData = true, prepMem = prep,
      // Pass 1 is the compulsory-miss / L2 warm pass and is NOT measured.
      warmupInstrs = setup.size + perPass,
      verifyRetirement = obs => {
        // d0 = the LAST copied long, which prepMem made equal to its own SOURCE
        // address: proves the data really moved and landed at the right offset,
        // not merely that the loop ran. d7 = 0 proves the inner loop completed.
        def lastOf(reg: Int): Long = {
          val w = obs.filter(o => o.archRegValid && o.archRegId == reg)
          assert(w.nonEmpty, s"memcpy: register d$reg was never written")
          w.last.archRegWrite & 0xffffffffL
        }
        val want = (Src + bytes - 4) & 0xffffffffL
        assert(lastOf(0) == want,
          f"memcpy copied the WRONG DATA: d0 = 0x${lastOf(0)}%x, expected 0x$want%x")
        assert(lastOf(7) == 0L, s"memcpy inner loop did not complete: d7 = ${lastOf(7)}")
      })
  }

  /** ── MOVE16 LEVER: the MEMORY-OP-COUNT ORACLE (2026-09-28) ────────────────────
    *
    * `kMemcpyQuads(q)` copies `q` of the four longs of every 16-byte line and skips
    * the rest, advancing both pointers by 16 with one `lea` pair per line. For
    * q = 1/2/4 the LINE FOOTPRINT, the L1 MISS COUNT, the destination
    * write-allocate fetches and the dirty writebacks are all IDENTICAL -- a 16-byte
    * L1 line is fetched whole on first touch and written back whole because there
    * is ONE dirty bit per line -- while the number of D-cache ACCESSES per line is
    * exactly `2*q`. So the q=1 -> q=4 slope MEASURES the marginal cycle cost of an
    * LS-pipe access at constant memory-system behaviour, which is the entire
    * mechanism a single-access `MOVE16` can attack. It needs no RTL and it BOUNDS
    * the lever before it is built, which is what `docs/PERF_LEVER_QUEUE.md` demands
    * after a transaction-count model missed lever 16 by 8x.
    *
    * q=4 is the same work as `kMemcpy` written in the displacement form with one
    * `lea` pair, so the three points differ ONLY in the number of `move.l`s; it is
    * also the cross-check against `kMemcpy`'s post-increment form.
    *
    * Verification is the same real check `kMemcpy` uses (every source long contains
    * its own address), aimed at the LAST long this variant actually copies -- so a
    * copy that shifted, stopped early, or skipped the wrong quad still fails.
    */
  def kMemcpyQuads(quads: Int, bytes: Int = 16384, passes: Int = 4,
                   label: String = null): Kernel = {
    require(quads >= 1 && quads <= 4, "quads must be 1..4")
    require(bytes % 16 == 0, "memcpy bytes must be a whole number of 16-byte L1 lines")
    require(passes >= 2, "pass 1 is the L2 warm-up and is excluded")
    val Src = 0x00500000L
    val Dst = 0x00600000L
    val iters = bytes / 16
    val name = if (label != null) label else s"memcpy-q$quads-${bytes / 1024}k"
    val prep: MemHandles => Unit = { h =>
      var a = 0
      while (a < bytes) {
        val v = Src + a
        h.dmem.pokeByte(Src + a + 0, ((v >> 24) & 0xff).toInt)
        h.dmem.pokeByte(Src + a + 1, ((v >> 16) & 0xff).toInt)
        h.dmem.pokeByte(Src + a + 2, ((v >> 8) & 0xff).toInt)
        h.dmem.pokeByte(Src + a + 3, (v & 0xff).toInt)
        a += 4
      }
    }
    val setup = Seq(f"lea 0x$Src%x,%%a2", f"lea 0x$Dst%x,%%a3", s"move.l #$passes,%d6")
    val outer = Seq("movea.l %a2,%a0", "movea.l %a3,%a1", s"move.l #$iters,%d7")
    val moves = (0 until quads).map(i => s"move.l ${i * 4}(%a0),${i * 4}(%a1)")
    val body  = moves ++ Seq("lea 16(%a0),%a0", "lea 16(%a1),%a1",
                             "subq.l #1,%d7", "bne.s .Lcpy")
    val tail  = Seq("subq.l #1,%d6", "bne.s .Louter")
    // The last long this variant really copies: line (iters-1), quad (quads-1).
    val lastOff = bytes - 16 + (quads - 1) * 4
    val epi   = Seq(f"move.l 0x${Dst + lastOff}%x,%%d0")
    val src = (setup ++ Seq(".Louter: " + outer.mkString(" ; "),
                            ".Lcpy: "   + body.mkString(" ; "),
                            tail.mkString(" ; ")) ++ epi ++
               Seq(".Lcpyend: bra.s .Lcpyend")).mkString(" ; ")
    val perPass = outer.size + iters * body.size + tail.size
    Kernel(name, src, setup.size + passes * perPass + epi.size,
      copybackDtt = true, zeroFillData = true, prepMem = prep,
      warmupInstrs = setup.size + perPass,
      verifyRetirement = obs => {
        def lastOf(reg: Int): Long = {
          val w = obs.filter(o => o.archRegValid && o.archRegId == reg)
          assert(w.nonEmpty, s"$name: register d$reg was never written")
          w.last.archRegWrite & 0xffffffffL
        }
        val want = (Src + lastOff) & 0xffffffffL
        assert(lastOf(0) == want,
          f"$name copied the WRONG DATA: d0 = 0x${lastOf(0)}%x, expected 0x$want%x")
        assert(lastOf(7) == 0L, s"$name inner loop did not complete: d7 = ${lastOf(7)}")
      })
  }

  /** `MOVE16 (Ax)+,(Ay)+` copy loop -- the SAME 16 bytes per iteration as `kMemcpy`
    * in ONE instruction instead of four, and with the pointer bumps folded in
    * (`move16` post-increments both by 16 unconditionally).
    *
    * As microcoded TODAY (`Microcode.scala`'s `MOVE16_ENTRY`, rows 242..251) this is
    * 4 LONG loads + 4 LONG stores + 2 dropped address ADDs = the SAME EIGHT D-cache
    * accesses per line the `move.l` loop performs, from TWELVE uops instead of ten.
    * So against `kMemcpy`/`kMemcpyQuads(4)` this kernel isolates the INSTRUCTION-COUNT
    * and decode-bandwidth half of the lever with the memory-op count held constant,
    * and it is the fail-before control for making MOVE16 a single wide access.
    *
    * Both operands are 16-byte aligned here, which is the only case where a
    * line-wide implementation and Musashi's non-masking four-LONG semantics agree.
    */
  def kMemcpyMove16(bytes: Int = 16384, passes: Int = 4,
                    label: String = null): Kernel = {
    require(bytes % 16 == 0, "memcpy bytes must be a whole number of 16-byte L1 lines")
    require(passes >= 2, "pass 1 is the L2 warm-up and is excluded")
    val Src = 0x00500000L
    val Dst = 0x00600000L
    require(Src % 16 == 0 && Dst % 16 == 0, "MOVE16 operands must be line aligned")
    val iters = bytes / 16
    val name = if (label != null) label else s"memcpy-m16-${bytes / 1024}k"
    val prep: MemHandles => Unit = { h =>
      var a = 0
      while (a < bytes) {
        val v = Src + a
        h.dmem.pokeByte(Src + a + 0, ((v >> 24) & 0xff).toInt)
        h.dmem.pokeByte(Src + a + 1, ((v >> 16) & 0xff).toInt)
        h.dmem.pokeByte(Src + a + 2, ((v >> 8) & 0xff).toInt)
        h.dmem.pokeByte(Src + a + 3, (v & 0xff).toInt)
        a += 4
      }
    }
    val setup = Seq(f"lea 0x$Src%x,%%a2", f"lea 0x$Dst%x,%%a3", s"move.l #$passes,%d6")
    val outer = Seq("movea.l %a2,%a0", "movea.l %a3,%a1", s"move.l #$iters,%d7")
    val body  = Seq("move16 (%a0)+,(%a1)+", "subq.l #1,%d7", "bne.s .Lcpy")
    val tail  = Seq("subq.l #1,%d6", "bne.s .Louter")
    val epi   = Seq(f"move.l 0x${Dst + bytes - 4}%x,%%d0")
    val src = (setup ++ Seq(".Louter: " + outer.mkString(" ; "),
                            ".Lcpy: "   + body.mkString(" ; "),
                            tail.mkString(" ; ")) ++ epi ++
               Seq(".Lcpyend: bra.s .Lcpyend")).mkString(" ; ")
    val perPass = outer.size + iters * body.size + tail.size
    Kernel(name, src, setup.size + passes * perPass + epi.size,
      copybackDtt = true, zeroFillData = true, prepMem = prep,
      warmupInstrs = setup.size + perPass,
      verifyRetirement = obs => {
        def lastOf(reg: Int): Long = {
          val w = obs.filter(o => o.archRegValid && o.archRegId == reg)
          assert(w.nonEmpty, s"$name: register d$reg was never written")
          w.last.archRegWrite & 0xffffffffL
        }
        val want = (Src + bytes - 4) & 0xffffffffL
        assert(lastOf(0) == want,
          f"$name copied the WRONG DATA: d0 = 0x${lastOf(0)}%x, expected 0x$want%x")
        assert(lastOf(7) == 0L, s"$name inner loop did not complete: d7 = ${lastOf(7)}")
      })
  }


  def kLoadStream: Kernel = {
    val iters = 60
    val addrs = Seq(0x4000, 0x4004, 0x4008, 0x400c, 0x4010, 0x4014)
    val setup = Seq("moveq #60,%d7")
    // d7 is the trip counter; d0..d5 are the six independent load destinations.
    val loads = addrs.zipWithIndex.map { case (a, i) => f"move.l 0x$a%x,%%d$i" }
    val body  = ".Lldst: " + loads.mkString(" ; ") + " ; subq.l #1,%d7 ; bne.s .Lldst"
    val src   = setup.mkString(" ; ") + " ; " + body
    Kernel("load-stream", src, setup.size + iters * (addrs.size + 2))
  }

  // 5c. store-stream: real COPYBACK-hit StoreQueue→D-cache throughput. Match-all
  // transparent translation keeps VA==PA but supplies COPYBACK mode. Six setup
  // loads make the lines resident before the loop; the measured body is six
  // independent stores plus loop control. Unlike `load/store`, no younger load can
  // consume these stores by SQ forwarding, so sustained progress requires the SQ
  // send cursor and D-cache S0/S1/S2/S3 drain pipeline to turn over.
  def kStoreStream: Kernel = {
    val iters = 60
    val addrs = Seq(0x4200, 0x4210, 0x4220, 0x4230, 0x4240, 0x4250)
    val setup = Seq("moveq #42,%d0", "moveq #60,%d7") ++
      addrs.map(a => f"move.l 0x$a%x,%%d1")
    val stores = addrs.map(a => f"move.l %%d0,0x$a%x")
    val body = ".Lstst: " + stores.mkString(" ; ") +
      " ; subq.l #1,%d7 ; bne.s .Lstst"
    val src = setup.mkString(" ; ") + " ; " + body
    Kernel("store-stream", src, setup.size + iters * (stores.size + 2),
      copybackDtt = true, expectedStoreDrains = iters * stores.size)
  }

  // 5c-2. same-line-copyback: directed kernel for the LS-cluster review finding P5
  // (StoreQueue.scala `sameLine` COPYBACK narrowing). Store LONG at 0x4300 (bytes
  // 0..3), load LONG at 0x4308 (bytes 8..11) -- SAME 16-byte cache line (0x4300..
  // 0x430F), ZERO byte overlap, both under COPYBACK (match-all transparent DTT,
  // like store-stream). Before the P5 fix, EVERY loop-body load stalled behind the
  // immediately-preceding same-line store until that store's full drain-ack came
  // back (the `sameLine` refill hazard applied mode-agnostically); after the fix,
  // COPYBACK is excluded from that term, so the load may launch as soon as the LS
  // EU is free. Both addresses are pre-warmed resident (setup loads, mirroring
  // store-stream's own pattern) so every loop-body store is a COPYBACK-HIT RMW --
  // steady-state throughput, not one-time miss/refill latency. Per iter: store +
  // load + subq + bne = 4 macros.
  // NOTE: deliberately NOT setting `expectedStoreDrains` here (unlike store-stream).
  // Those strict assertions include `maxSqAccepted >= 2 && maxDcOutstanding >= 2`
  // (pipeline overlap) -- which this kernel is EXPECTED to violate pre-fix (the
  // whole point: the pre-fix `sameLine` stall serializes each store fully behind
  // the load that follows it, so overlap never happens) and only satisfies post-fix.
  // Gating a hard assert on that would make the "before" A/B measurement itself
  // fail to run rather than produce a comparable number. The `[store-path]`
  // diagnostic printout (`k.copybackDtt`-gated, unconditional) still reports real
  // hit/miss/overlap counts for both runs.
  def kSameLineCopyback: Kernel = {
    val iters = 60
    val setup = Seq("moveq #55,%d0", "moveq #60,%d7", "move.l 0x4300,%d1", "move.l 0x4308,%d2")
    val body  = ".Lslcb: move.l %d0,0x4300 ; move.l 0x4308,%d2 ; subq.l #1,%d7 ; bne.s .Lslcb"
    val src   = setup.mkString(" ; ") + " ; " + body
    Kernel("same-line-copyback", src, setup.size + iters * 4, copybackDtt = true)
  }

  // 5d. shift-stream: ALU SLOW-PATH (SHIFT) THROUGHPUT. This kernel exists to close a
  //     real BENCHMARK-COVERAGE GAP found by the 2026-08-09 EU-wide one-at-a-time-FSM
  //     audit: EVERY other kernel in this suite is shift-free and bit-field-free (see
  //     the `iq.aluSlowWakeup` note near the top of this file: "Inert for the shift-free
  //     IPC kernels"), so the ALU EU's SLOW path -- `DecOp.SHIFT` and `DecOp.BITFIELD`,
  //     the S1/S1a/S1a2/S1b/S2/S3 pipe -- has never been measured at all, even though
  //     the pre-II1 baseline deasserted `issuePort.ready` for that op's ENTIRE 6-stage
  //     occupancy (initiation interval = 7 cycles per ALU port, and the gate blocked that
  //     EU's FAST ops too). A word histogram over the real Quadra 950 ROM puts
  //     register-form shifts at 1.50% and bit-field ops at 0.37% of all words, i.e. the
  //     order of 3-4% of real instructions -- concentrated in exactly the QuickDraw /
  //     blit / bit-packing code this core's deployment target runs.
  //
  //     Construction (deliberately mirrors `load-stream`, for the same reasons):
  //       * 6 shifts per iteration on 6 DISTINCT data registers => six INDEPENDENT
  //         slow-op streams, so what is measured is the slow path's THROUGHPUT
  //         (initiation interval), not its ~8-9 cycle dependent-use latency.
  //       * ZERO memory traffic => no LS EU / D-cache component in the number.
  //       * a TIGHT backward loop (not a straight-line unroll) so the body stays
  //         I-cache resident and the measurement does not turn into the documented
  //         66-77% straight-line I-fetch loss under `IPC_MEM=l2:5:70`. The back-edge is
  //         BTB-predicted after warmup (same mechanism `hot-loop` relies on).
  //     HONEST CEILING: with one shift per register per iteration, the per-register
  //     loop-carried slow-op chain (~8-9 cycles) puts a floor of ~9 cycles on the
  //     iteration period. So even a perfect II=1 slow path cannot drive this kernel
  //     past ~8/9 IPC; the point is the DELTA against today's issue-bound ~3 x 7 = 21
  //     cycles/iteration, not an absolute ceiling.
  //     Per iter: 6 shifts + subq + bne = 8 macros.
  def kShiftStream: Kernel = {
    val iters = 60
    val setup = Seq("moveq #60,%d7") ++ (0 to 5).map(r => s"moveq #-1,%d$r")
    val shifts = (0 to 5).map(r => s"lsl.l #1,%d$r")
    val body  = ".Lsh: " + shifts.mkString(" ; ") + " ; subq.l #1,%d7 ; bne.s .Lsh"
    val src   = setup.mkString(" ; ") + " ; " + body
    Kernel("shift-stream", src, setup.size + iters * (shifts.size + 2))
  }

  // 5e. shift-mixed: the AMPLIFIER the pure `shift-stream` kernel cannot show. The
  //     pre-II1 gate was UNCONDITIONAL -- while a slow op occupied the pipe, that EU
  //     accepted NOTHING, so the machine dropped from 2-wide ALU issue to
  //     1-wide for the full 7-cycle window; and because the IQ maps oldest->port0 /
  //     second-oldest->port1 statically (`IssueQueuePlugin.scala:313-314`), the OLDEST
  //     ready ALU uop can be head-of-line-blocked on a busy port 0 while younger ops
  //     overtake it on port 1, stalling in-order retire. A realistic blit/bit-packing
  //     inner loop is exactly this shape: a few shifts/bit-field ops surrounded by
  //     plenty of independent cheap ALU work that SHOULD fill the other port.
  //
  //     Construction: 6 slow ops (4 shifts + 2 register-form BFEXTU, so BOTH slow op
  //     classes are covered -- they share the identical S1..S3 pipe) on 6 independent
  //     data registers, INTERLEAVED with 12 independent one-cycle `add.l %d6,%aN`
  //     (ADDA, an ordinary FAST ALU op) over 6 independent address registers. d6 is a
  //     read-only constant source, d7 the trip counter, a7 untouched. No memory traffic,
  //     no branches other than the BTB-predicted back-edge.
  //     Per iter: 4 lsl + 2 bfextu + 12 adda + subq + bne = 20 macros.
  def kShiftMixed: Kernel = {
    val iters = 40
    val setup = Seq("moveq #40,%d7", "moveq #1,%d6") ++ (0 to 5).map(r => s"moveq #-1,%d$r")
    def fast(n: Int) = s"add.l %d6,%a$n"
    val body = Seq(
      "lsl.l #1,%d0", fast(0), fast(1),
      "lsl.l #1,%d1", fast(2), fast(3),
      "lsl.l #1,%d2", fast(4), fast(5),
      "lsl.l #1,%d3", fast(0), fast(1),
      "bfextu %d4{#0:#8},%d4", fast(2), fast(3),
      "bfextu %d5{#4:#12},%d5", fast(4), fast(5),
      "subq.l #1,%d7", "bne.s .Lshm")
    val src = setup.mkString(" ; ") + " ; .Lshm: " + body.mkString(" ; ")
    Kernel("shift-mixed", src, setup.size + iters * body.size)
  }

  // 6. call/return: a loop that CALLS a leaf subroutine each iteration. The leaf's
  //    `rts` is the kernel the RAS (slice 2) targets: without return prediction every
  //    rts pays the ~5-6cyc commit-time squash; with the RAS warm the return target is
  //    predicted (top-of-stack) -> ~zero squash. The loop back-edge (bne) is BTB-
  //    predicted (slice 1). Per iter: bsr leaf ; subq #1,%d7 ; bne .Lcr (3 macros in the
  //    loop) + the leaf's 2 macros (add + rts) = 5 macros/iter. The first iteration
  //    warms the RAS/BTB; thereafter the call+return is fully predicted.
  def kCallReturn: Kernel = {
    val iters = 100
    val setup = Seq("moveq #100,%d7", "moveq #0,%d0", "moveq #1,%d1",
                    "moveq #0,%d2", "moveq #0,%d3")
    // The loop body: call the leaf, then several INDEPENDENT ALU ops in the caller (so
    // a correctly-predicted return lets these post-return instructions be fetched +
    // issued WITHOUT waiting for the rts load to resolve — the squash a mispredicted
    // return would cause is exactly what the RAS removes), then the decrement + back-edge.
    // The leaf does one add + rts (a balanced call/return). Per iter:
    //   bsr leaf ; add d1,d2 ; add d1,d3 ; sub d1,d2 ; subq #1,d7 ; bne ; leaf-add ; rts
    //   = 8 macros/iter.
    val loop  = ".Lcr: bsr leaf ; add.l %d1,%d2 ; add.l %d1,%d3 ; sub.l %d1,%d2 ; " +
                "subq.l #1,%d7 ; bne.s .Lcr"
    val tail  = "moveq #9,%d6 ; .Lend: bra.s .Lend ; leaf: add.l %d1,%d0 ; rts"
    val src = (setup.mkString(" ; ")) + " ; " + loop + " ; " + tail
    // Retired macros: setup(5) + per-iter [bsr, add, add, sub, subq, bne, leaf-add,
    //   leaf-rts] = 8 * iters.
    Kernel("call-return", src, setup.size + iters * 8)
  }


  // ══════════════════════════════════════════════════════════════════════════════
  // Track 6 — strcmp: a byte LOAD -> COMPARE -> CONDITIONAL BRANCH recurrence
  // ══════════════════════════════════════════════════════════════════════════════
  // The board's Dhrystone window spends ~52% of its cycles with the ROB non-empty
  // and nothing retiring while the D-cache stalls only 0.83% (dc-miss 1.17/kinst),
  // i.e. the loads HIT and the machine is waiting on LATENCY, not on memory. PC
  // sampling put 14 of 40 samples in one 60-byte window, which disassembled to a
  // byte-at-a-time strcmp:
  //
  //     tstb  %a2@              <- LOAD
  //     bnes  .Lnext
  //     ...                     (equal-and-NUL exit)
  //   .Lnext:
  //     addql #1,%a2
  //     addql #1,%a3
  //     moveb %a2@,%d0          <- LOAD
  //     cmpb  %a3@,%d0          <- LOAD + COMPARE, consumes d0
  //     beqs  .Ltop             <- BRANCH, consumes the compare
  //
  // This suite had NOTHING of that shape: a mechanical sweep of the whole bench
  // package finds exactly ONE compare, `cmp.l %d6,%d0`, with a REGISTER source, and
  // the only byte traffic is `move.b (%a2)+,(%a3)+` -- strcpy, not strcmp. So the
  // per-iteration recurrence
  //     pointer increment -> LOAD -> ALU compare -> conditional branch -> increment
  // was unrepresented, and so was the ALU-class consumer of a load: in
  // `cmp.b (%a3),%d0` the consumer of the cracked load is the CMP, not an address.
  //
  // The kernels below reproduce that loop, its rotation, and its two taken branches
  // per iteration. They are LOAD-ONLY (no stores at all), so `copybackDtt` cannot
  // change what they measure through the precise-store path -- the `-cb` variants
  // exist so the posture is stated rather than assumed, and both are reported.
  //
  // Opt-in via IPC_STRCMP_KERNELS=1 so the default IpcBenchSpec aggregate stays
  // byte-identical to every earlier run.

  private val StrcmpPairs     = 64      // distinct string pairs in the table
  // TWO passes over the SAME table. The first is entirely inside `warmupInstrs`, so by
  // the time the measured window opens every string line AND every pointer-table line
  // has been touched once and the run is cache-RESIDENT -- which is the board's regime
  // (dc-miss 1.17/kinst, dc-stall 0.83%). Measured over a single pass instead, the
  // window pays ~100 COMPULSORY refills for first-touching its own data, which is not
  // what the board does and would have made this a memory benchmark.
  private val StrcmpPasses    = 2
  private val StrcmpSlotBytes = 13      // bytes reserved per string (unaligned stride)
  private val StrcmpAStr      = 0x00003000L
  private val StrcmpBStr      = 0x00003400L
  private val StrcmpTable     = 0x00003800L
  private val StrcmpConst     = 0x41    // the constant byte the matched control compares against
  private val StrcmpFiller    = 0x2e

  /** The pair table: two NUL-capable byte images per pair.
    *
    *  - mode "mem" / "reg": string B is a buffer of ONE REPEATED BYTE (`StrcmpConst`).
    *    That is what makes an EXACT control possible: comparing `s[k]` against `B[k]`
    *    and comparing `s[k]` against a register holding the same constant produce the
    *    SAME flags, so the two kernels retire the same instructions in the same order
    *    with the same branch outcomes, and the memory operand on the compare is the
    *    ONLY difference. (Constant DATA changes nothing microarchitecturally: the load
    *    is a real byte load through a real incrementing pointer over its own lines.)
    *  - mode "pair": both strings vary and one pair in five is fully equal, so the
    *    NUL-exit path through `tst.b (%a2)` runs too. No exact control exists for this
    *    one; it is the realism/branch-behaviour reference.
    *
    * First-difference positions come from a fixed LCG rather than a short cycle, so
    * the exit branch is not learnable by a bounded global history. */
  private def strcmpData(mode: String): Vector[(Vector[Int], Vector[Int])] = {
    var x = 0x13579bdfL
    def next(n: Int): Int = { x = (x * 1103515245L + 12345L) & 0x7fffffffL; ((x >>> 9) % n).toInt }
    val out = ArrayBuffer.empty[(Vector[Int], Vector[Int])]
    for (_ <- 0 until StrcmpPairs) {
      val m = 2 + next(11)                       // first-difference / NUL index, 2..12
      val equalPair = mode == "pair" && next(5) == 0
      if (mode == "pair") {
        val prefix = Vector.tabulate(m)(_ => 0x21 + next(0x5d))   // never 0
        if (equalPair) {
          val s = prefix :+ 0
          out += ((s, s))
        } else {
          val av = prefix :+ (0x21 + next(0x5d))
          val delta = 1 + next(0x10)
          val bLast = if (av(m) + delta <= 0xfe) av(m) + delta else av(m) - delta
          out += ((av, prefix :+ bLast))
        }
      } else {
        // The differing byte straddles the constant in BOTH directions so `bcs`
        // (the sign leg) is itself data-dependent and not learnable either.
        val diff = if (next(2) == 0) StrcmpConst - (1 + next(0x20))
                   else              StrcmpConst + (1 + next(0x20))
        out += ((Vector.fill(m)(StrcmpConst) :+ diff,
                 Vector.fill(StrcmpSlotBytes)(StrcmpConst)))
      }
    }
    out.toVector
  }

  /** EXACT architectural trace of one pair through the loop below: retired macro
    * count and the d3 result the kernel must leave behind. Written as an interpreter
    * over the very same control flow, not as a closed-form formula, so the count the
    * harness runs to cannot silently drift from the assembly. */
  private def strcmpPairTrace(a: Vector[Int], b: Vector[Int]): (Int, Int) = {
    var n = 4                     // movea, movea, lea, bra .LscNext
    var k = 0
    var res = 0
    var done = false
    while (!done) {
      n += 5                      // addq, addq, move.b, cmp, beq
      if (a(k) != b(k)) {
        n += 3                    // move.b, cmp, bcs
        if (a(k) < b(k)) { n += 1; res = -1 }   // bcs TAKEN  -> moveq #-1,%d3
        else             { n += 2; res =  1 }   // moveq #1,%d3 ; bra .LscOut
        done = true
      } else {
        n += 2                    // tst.b (%a2) ; bne
        if (a(k) == 0) { n += 2; res = 0; done = true }  // moveq #0,%d3 ; bra .LscOut
        else k += 1
      }
    }
    n += 3                        // add.l %d3,%d2 ; subq.l #1,%d7 ; bne.w .LscPair
    (n, res)
  }

  /** The loop, in the board's rotation: the NUL test at the top, the two pointer
    * increments and the memory-operand compare at the bottom, both branches TAKEN on
    * a continuing iteration. `mode == "reg"` is the matched control: the compare's
    * source is a register instead of `(%a3)`, the pointer increment on %a3 is KEPT so
    * the instruction sequence and the address arithmetic are unchanged, and the only
    * thing that goes away is the load the compare depends on.
    *
    * What that control does NOT remove: `move.b (%a2),%d0` still feeds the compare, so
    * an ALU consumer of a load survives in the control too. The mem/reg delta is
    * therefore the cost of the COMPARE'S OWN memory operand -- the second load in the
    * iteration, and the compare having to wait on two producers instead of one -- not
    * the whole load-to-ALU-use cost. Read it as the marginal term, not as a zero. */
  private def strcmpSrc(mode: String): String = {
    val cmp = if (mode == "reg") "cmp.b %d1,%d0" else "cmp.b (%a3),%d0"
    val setup = Seq("moveq #0,%d2", f"moveq #0x$StrcmpConst%x,%%d1",
                    s"moveq #$StrcmpPasses,%d6")
    val body = Seq(
      f".LscPass: lea 0x$StrcmpTable%x,%%a4",
      s"moveq #$StrcmpPairs,%d7",
      ".LscPair: movea.l (%a4),%a2",     // pre-decremented source pointer
      "movea.l 4(%a4),%a3",              // pre-decremented compare pointer
      "lea 8(%a4),%a4",
      "bra.s .LscNext",
      ".LscTop: tst.b (%a2)",            // LOAD -> branch (the NUL test)
      "bne.s .LscNext",
      "moveq #0,%d3",
      "bra.s .LscOut",
      ".LscNext: addq.l #1,%a2",
      "addq.l #1,%a3",
      "move.b (%a2),%d0",                // LOAD
      cmp,                               // LOAD + COMPARE, consumes d0
      "beq.s .LscTop",                   // BRANCH, consumes the compare
      "move.b (%a2),%d0",                // mismatch tail: recover the sign
      cmp,
      "bcs.s .LscNeg",
      "moveq #1,%d3",
      "bra.s .LscOut",
      ".LscNeg: moveq #-1,%d3",
      ".LscOut: add.l %d3,%d2",
      "subq.l #1,%d7",
      "bne.w .LscPair",
      "subq.l #1,%d6",
      "bne.w .LscPass")
    (setup ++ body).mkString(" ; ") +
      " ; .LscStop: bra.s .LscStop ; .rept 64 ; nop ; .endr"
  }

  /** `mode` is "mem" (memory-operand compare), "reg" (the matched register-operand
    * control) or "pair" (both strings real, NUL exits included). */
  def kStrcmp(mode: String, copyback: Boolean): Kernel = {
    require(Set("mem", "reg", "pair").contains(mode), s"bad strcmp mode $mode")
    val data    = strcmpData(mode)
    val traces  = data.map { case (a, b) => strcmpPairTrace(a, b) }
    val setupN  = 3                                   // moveq d2, moveq d1, moveq d6
    val passN   = 2 + traces.map(_._1).sum + 2        // lea/moveq d7 ... subq d6/bne
    val total   = setupN + StrcmpPasses * passN
    val warmup  = setupN + passN                      // the whole first (warming) pass
    val results = Vector.fill(StrcmpPasses)(traces.map(_._2)).flatten
    val name    = s"strcmp-$mode${if (copyback) "-cb" else ""}"
    Kernel(name, strcmpSrc(mode), total,
      copybackDtt = copyback,
      zeroFillData = true,
      warmupInstrs = warmup,
      prepMem = m => {
        for (i <- 0 until StrcmpPairs) {
          val (a, b) = data(i)
          val aAddr = StrcmpAStr + i * StrcmpSlotBytes
          val bAddr = StrcmpBStr + i * StrcmpSlotBytes
          for (j <- 0 until StrcmpSlotBytes) {
            m.dmem.pokeByte(aAddr + j, if (j < a.size) a(j) else StrcmpFiller)
            m.dmem.pokeByte(bAddr + j, if (j < b.size) b(j) else StrcmpFiller)
          }
          // The loop increments BEFORE its first load, so the table holds ptr-1.
          for ((base, ptr) <- Seq(0 -> (aAddr - 1), 4 -> (bAddr - 1)))
            for (byte <- 0 until 4)
              m.dmem.pokeByte(StrcmpTable + i * 8 + base + byte,
                ((ptr >> (8 * (3 - byte))) & 0xff).toInt)
        }
      },
      // ARCHITECTURAL check, not a retire count: `runKernel` stops after N macros
      // WHATEVER THEY ARE, so a vanished branch can leave the count intact. d3 carries
      // one -1/0/+1 per pair and d2 their running sum, so the full write streams of
      // both pin every load, every compare outcome and every branch decision.
      verifyRetirement = obs => {
        def writes(reg: Int): Seq[Long] =
          obs.filter(o => o.archRegValid && o.archRegId == reg).map(_.archRegWrite & 0xffffffffL)
        val d3 = writes(3)
        val want3 = results.map(_.toLong & 0xffffffffL)
        assert(d3.size == want3.size,
          s"[$name] d3 produced ${d3.size} pair results, expected ${want3.size} " +
          "-- the loop structure changed, not just its timing")
        assert(d3 == want3, s"[$name] pair result stream differs:\n  got  $d3\n  want $want3")
        val d2 = writes(2)
        val want2 = results.scanLeft(0)(_ + _).map(_.toLong & 0xffffffffL)
        assert(d2 == want2, s"[$name] accumulator stream differs:\n  got  $d2\n  want $want2")
      },
      profileRetirement = true)
  }

  /** Opt-in (IPC_STRCMP_KERNELS=1) strcmp coverage kernels: the faithful shape, its
    * exactly matched register-operand control, and the fully varied both-strings-real
    * variant -- each in the default and the COPYBACK posture. */
  def strcmpKernels: Seq[Kernel] =
    for (copyback <- Seq(false, true); mode <- Seq("mem", "reg", "pair"))
      yield kStrcmp(mode, copyback)


  //  BRANCH-PREDICTION PROBES (2026-09-26)
  //
  //  WHY THESE EXIST. Silicon `perf` puts branch mispredicts at 24-77 per 1000
  //  retired instructions (~11-14% of all cycles), the second-largest stall in the
  //  machine. The pre-existing kernels above retire TWENTY-FIVE mispredicts in
  //  total across the whole suite (1.49 MPKI aggregate, and 3 of those in every
  //  kernel are just the cold-start and loop-exit branches) -- so a predictor
  //  change of any size is inside the sampling noise, and `perf(btb): 128 -> 512`
  //  correctly recorded "THIS CANNOT BE VALIDATED IN THE BENCH".
  //
  //  Each probe below isolates ONE failure mode of the fetch-time predictor and
  //  drives it to ~1000 retired mispredicts, so `[br-attr]` can rank the buckets
  //  and an A/B has statistical power. They are SYNTHETIC UPPER BOUNDS, not a model
  //  of the Mac workload: read them as "how much does this mechanism cost when it
  //  is the whole workload", and pair each with its `-fit` control (the same kernel
  //  sized to fit the existing structure), which is what proves the probe is
  //  measuring the structure and not something else.
  //
  //  Every probe sets %sp explicitly. SpinalSim randomises the PRF, so a kernel
  //  that pushes without initialising A7 writes to a random address (see the
  //  odd-ssp/a7 lockstep note); at 0x00300000 the stack is clear of the record
  //  data at 0x10000-0x30000 and of the code at 0x40800000.
  // ══════════════════════════════════════════════════════════════════════════════

  /** The branch probes below are self-checking. A computed-target or predictor change
    * that is WRONG about a branch is only a perf loss in principle (the branch EU
    * verifies every direction and target), but "only a perf loss" is a claim, not a
    * measurement -- so each probe states the exact architectural register value its
    * control flow must produce, and the harness fails if the run took a different path.
    * This is the same discipline `assertChasePremise` applies to the pointer chase. */
  private def brProbeExpect(expect: (Int, Long)*)(
      obs: Seq[m68k040.lockstep.CommitObservation]): Unit =
    expect.foreach { case (reg, want) =>
      val writes = obs.filter(o => o.archRegValid && o.archRegId == reg)
      assert(writes.nonEmpty, s"branch probe: register $reg was never written")
      val got = writes.last.archRegWrite & 0xffffffffL
      assert(got == want,
        f"branch probe took the wrong control-flow path: d$reg = 0x$got%x, expected 0x$want%x")
    }

  /** INDIRECT-TARGET probe: ONE `jsr (%a0)` site whose target cycles through
    * `handlers` distinct leaves, the address loaded from a table in memory.
    *
    * This is the Mac OS dispatch shape (A-line trap table / jump table / `jsr (An)`
    * through a loaded pointer) and it is the one the frontend CANNOT predict by
    * construction: the BTB/FTB hold ONE last-seen target per entry, so at a site
    * whose target changes every execution the stored target is always the PREVIOUS
    * handler. Expect ~100% mispredict on the indirect and ~0 on everything else.
    *
    * The target sequence is a FIXED CYCLE of period `handlers`, i.e. perfectly
    * predictable from 5+ bits of global history while being 0% predictable from the
    * last target. That makes this kernel the CEILING measurement for an
    * ITTAGE/cascaded indirect predictor, not an average case.
    *
    * The table is built by the kernel itself (`lea .Lh<i>,%a0` + an absolute store)
    * because the assembled code image is attached to the I-side AXI only -- code
    * addresses are not readable through the D-cache, so a `prepMem` cannot know
    * them. Those 2*handlers stores are counted as warm-up. */
  def kBrIndirect(handlers: Int = 32, iters: Int = 1024, label: String = "br-ind"): Kernel = {
    require(handlers >= 2 && (handlers & (handlers - 1)) == 0, "handlers must be a power of two")
    val Tab  = 0x30000L
    val mask = handlers * 4 - 1
    val setup = Seq("lea 0x00300000,%sp", f"lea 0x$Tab%x,%%a2", "moveq #0,%d2",
                    f"move.l #$mask,%%d3", "moveq #1,%d1", "moveq #0,%d0",
                    s"move.l #$iters,%d7")
    val tabInit = (0 until handlers).flatMap(i =>
      Seq(s"lea .Lh$i,%a0", f"move.l %%a0,0x${Tab + i * 4}%x"))
    val body = Seq("move.l (%a2,%d2.l),%a0", "addq.l #4,%d2", "and.l %d3,%d2",
                   "jsr (%a0)", "subq.l #1,%d7", "bne.s .Lbri")
    val handlerCode = (0 until handlers).map(i => s".Lh$i: add.l %d1,%d0 ; rts")
    val src = (setup ++ tabInit ++ Seq(".Lbri: " + body.mkString(" ; "),
      ".Lbriend: bra.s .Lbriend") ++ handlerCode).mkString(" ; ")
    // Per iteration: 6 loop macros + the handler's add + rts = 8.
    Kernel(label, src, setup.size + tabInit.size + iters * 8,
      warmupInstrs = setup.size + tabInit.size,
      // Every handler adds d1(=1) to d0, so d0 == iters iff the dispatch actually
      // reached a handler on every iteration.
      verifyRetirement = brProbeExpect(0 -> iters.toLong))
  }

  /** DBcc SLOT-1 COVERAGE probe: the canonical 68k counted loop, `add ; dbra`.
    *
    * `add.l %d1,%d0` is 2 bytes and `dbra %d7,.L` is 4, so the two emit as slot0 +
    * slot1 in ONE cycle -- which puts the LOOP-CLOSING branch in SLOT 1, where nothing
    * predicts it. DBcc is line-5, so neither `slot1WouldUncond` (line-6 unconditional)
    * nor `slot1IsConditional` (line-6, cond >= 2) matches it, and it is not JSR/JMP nor
    * RTS/RTR. It is emitted with no prediction from anywhere, falls through, and
    * mispredicts on every iteration that should have looped.
    *
    * ⛔ The bench had NO DBcc IN IT AT ALL before this probe -- the fifth measured
    * coverage hole in this corpus, after zero A6/A7 operands, zero store->load pairs,
    * zero load->compare->branch chains and 25 total suite mispredicts. A lever aimed at
    * DBcc would have measured an exact null against the old corpus and been called dead.
    *
    * `dbra` loops while `Dn != -1` AFTER the decrement, so `d7 = iters - 1` runs the
    * body exactly `iters` times. Each pass adds d1(=1) to d0, so `d0 == iters` iff every
    * iteration actually executed -- which also catches a deferral that drops the
    * instruction out of the buffer instead of re-emitting it (the `slot1WouldUncond`
    * two-list bug, caught that way by `br-ind`'s own check). */
  def kBrDbcc(iters: Int = 1024, label: String = "br-dbcc"): Kernel = {
    val setup = Seq("lea 0x00300000,%sp", "moveq #1,%d1", "moveq #0,%d0",
                    s"move.l #${iters - 1},%d7")
    val body  = Seq("add.l %d1,%d0", "dbra %d7,.Ldb")
    val src = (setup ++ Seq(".Ldb: " + body.mkString(" ; "),
      ".Ldbend: bra.s .Ldbend")).mkString(" ; ")
    // Per iteration: the add + the dbra = 2 macros.
    Kernel(label, src, setup.size + iters * 2,
      warmupInstrs = setup.size,
      verifyRetirement = brProbeExpect(0 -> iters.toLong))
  }

  /** DBcc SLOT-1 probe, TWO BRANCHES PER WINDOW -- the case `br-dbcc` does NOT reach.
    *
    * `br-dbcc` (above) turned out to be already covered: with ONE branch in its 8-byte
    * window the FTB holds it, `slot1WouldFtq` defers it to slot 0, and gshare predicts
    * it -- measured 3 mispredicts in 1024 iterations, IPC 0.989 (~1 macro/cycle, i.e.
    * the pair never issued together because the deferral already fired). That is the
    * existing mechanism working, and no DBcc lever can improve on it.
    *
    * The uncovered case is the one `br-ind` shows for `jsr`+`bne`: the FTB holds ONE
    * branch per EIGHT-BYTE WINDOW, so a window with TWO control transfers leaves the
    * second predicted by nothing. Here:
    *
    *   .Lds: beq.s .Lnever   (2 bytes, NEVER taken -- `moveq #1,%d2` leaves Z=0)
    *         dbra  %d7,.Lds  (4 bytes)
    *
    * Both live in one window. `ftqAt0` points at the `beq`, so `slot1WouldFtq` cannot
    * defer the `dbra`, which emits in SLOT 1 with no prediction and falls through on a
    * loop that should have been taken. The `beq` is correctly predicted NOT-taken, so
    * `slot0IsPred` stays low and the deferral predicate is reachable.
    *
    * Verified on d7. ⚠️ `DBcc` is a WORD operation -- it decrements and tests only the
    * LOW 16 BITS of Dn -- so starting from `move.l #iters-1` an exhausted loop leaves
    * d7 = 0x0000FFFF, NOT 0xFFFFFFFF. Reaching 0xFFFF means exactly `iters` decrements
    * happened, so it catches an early exit through the `beq` AND a deferral that drops
    * the instruction instead of re-emitting it. */
  def kBrDbccPair(iters: Int = 1024, label: String = "br-dbcc-2"): Kernel = {
    val setup = Seq("lea 0x00300000,%sp", "moveq #0,%d0",
                    s"move.l #${iters - 1},%d7", "moveq #1,%d2")
    val src = (setup ++ Seq(".Lds: beq.s .Lnever ; dbra %d7,.Lds",
      ".Lnever: bra.s .Lnever")).mkString(" ; ")
    // Per iteration: the never-taken beq + the dbra = 2 macros.
    Kernel(label, src, setup.size + iters * 2,
      warmupInstrs = setup.size,
      verifyRetirement = brProbeExpect(7 -> 0x0000FFFFL))
  }

  /** BTB/FTB CAPACITY probe: `sites` distinct always-taken `bra.s` hops, each at a
    * 6-byte stride, walked in a loop.
    *
    * A taken BRA has a FIXED PC-relative target that the BTB learns on first sight,
    * so it can only mispredict if its entry was EVICTED. The BTB is DIRECT-MAPPED on
    * `pc[1+log2(entries) : 1]`, so `sites` branches at a 6-byte stride land on
    * `min(sites, entries)` distinct indices (stride 3 in word units is coprime with
    * any power of two, so the indices are spread uniformly rather than aliased into
    * a fraction of the table -- a 4-byte stride would only ever reach half the
    * table and would overstate the conflict).
    *
    * `sites > entries` therefore thrashes and every hop mispredicts; `br-cap-fit`
    * (sites <= entries) is the control and must stay near zero. The pair is what
    * makes a BTB resize measurable here at all. */
  def kBrCapacity(sites: Int = 256, iters: Int = 8, label: String = "br-cap"): Kernel = {
    val setup = Seq("lea 0x00300000,%sp", s"move.l #$iters,%d7", "moveq #1,%d1",
                    "moveq #0,%d0", "moveq #0,%d2")
    // 6 bytes per site => the branch PCs advance 3 words at a time, which is coprime
    // with any power-of-two index width, so the sites spread uniformly over the table
    // instead of aliasing into a fraction of it (a 4-byte stride would only ever reach
    // half the sets and would overstate the conflict).
    //
    // The hop JUMPS OVER a dead `add.l %d1,%d2`, and not merely to the next instruction,
    // for two reasons. Encoding: a `bra.s` whose displacement is ZERO is the .W escape,
    // so a byte branch to pc+2 does not assemble at all. Measurement: `%d2` then counts
    // exactly the hops that FELL THROUGH, so `d2 == 0` proves every one of the
    // `sites * iters` branches was actually taken.
    val chain = (0 until sites).map { i =>
      s".Lc$i: add.l %d1,%d0 ; bra.s .Lc${i + 1} ; add.l %d1,%d2"
    }
    val src = (setup ++ chain ++ Seq(
      s".Lc$sites: subq.l #1,%d7 ; bne.w .Lc0", ".Lcend: bra.s .Lcend")).mkString(" ; ")
    // Per iteration: sites * (add + bra) + subq + bne. The skipped add never retires.
    Kernel(label, src, setup.size + iters * (sites * 2 + 2),
      verifyRetirement = brProbeExpect(0 -> (iters.toLong * sites), 2 -> 0L))
  }

  /** RETURN-ADDRESS-STACK probe: `depth` nested `bsr`/`rts` pairs per iteration.
    *
    * The RAS is a 16-entry circular buffer (`Global.RAS_ENTRIES`). Nesting DEEPER
    * than that overwrites the oldest entries, so the outermost `depth - 16` returns
    * of every iteration pop a wrong address and mispredict. `br-ras-fit` nests
    * inside the RAS and is the control.
    *
    * These mispredicts are the bucket NEITHER shipped board class counter can see:
    * `OFF_PERF_MISPRED_UNCOND`/`_COND` both come from `debugBranchRetire`, which is
    * gated on `isBtbBranch` and deliberately excludes returns, while
    * `OFF_MISPRED_COUNT` (`branchRedirect`) counts them. `[br-attr]`'s
    * `boardCounterGap` line is the sim-side view of that difference. */
  def kBrReturn(depth: Int = 24, iters: Int = 128, label: String = "br-ras"): Kernel = {
    require(depth >= 2)
    val setup = Seq("lea 0x00300000,%sp", s"move.l #$iters,%d7", "moveq #1,%d1",
                    "moveq #0,%d0")
    val loop = ".Lrt: bsr .Lf0 ; subq.l #1,%d7 ; bne.s .Lrt"
    val frames = (0 until depth - 1).map(i => s".Lf$i: bsr .Lf${i + 1} ; rts") :+
                 s".Lf${depth - 1}: add.l %d1,%d0 ; rts"
    val src = (setup ++ Seq(loop, ".Lrtend: bra.s .Lrtend") ++ frames).mkString(" ; ")
    // Per iteration: bsr + subq + bne = 3, then (depth-1) x (bsr + rts) = 2(depth-1),
    // then the leaf's add + rts = 2.
    Kernel(label, src, setup.size + iters * (2 * depth + 3),
      // Only the innermost frame adds, so d0 == iters iff every nest reached the leaf
      // AND every return came back to the right place.
      verifyRetirement = brProbeExpect(0 -> iters.toLong))
  }

  /** gshare DIRECTION probe: a conditional whose direction follows a fixed 32-bit
    * pattern, read out one bit per iteration by `rol.l #1` (which rotates the top
    * bit into C, so `bcs.s` takes the branch exactly on the pattern's 1 bits).
    *
    * A per-PC bimodal counter cannot do better than the pattern's bias (~50% here)
    * because it is ONE counter for all 32 positions. gshare CAN be perfect: 5 bits
    * of global history uniquely identify the position in a period-32 sequence, and
    * the index folds 11 history bits. So this probe is a direct test of whether the
    * GHR path actually works end to end -- speculative shift, retire-time
    * `ghrArch`, and the flush repair -- not just of table sizing. A residual
    * mispredict rate near the pattern's bias means the history is not reaching the
    * index; near zero means gshare is doing its job and conditional direction is
    * NOT where the remaining stall lives. */
  def kBrPattern(pattern: Int = 0x6a5c93d2, iters: Int = 1024): Kernel = {
    require(iters % 32 == 0, "iters must be a whole number of pattern periods")
    val ones = Integer.bitCount(pattern)
    val setup = Seq("lea 0x00300000,%sp", f"move.l #0x$pattern%08x,%%d6",
                    s"move.l #$iters,%d7", "moveq #1,%d1", "moveq #0,%d0", "moveq #0,%d2")
    val body = ".Lcp: rol.l #1,%d6 ; bcs.s .Lcp1 ; add.l %d1,%d0 ; bra.s .Lcp2 ; " +
               ".Lcp1: add.l %d1,%d2 ; .Lcp2: subq.l #1,%d7 ; bne.s .Lcp"
    val src = (setup ++ Seq(body, ".Lcpend: bra.s .Lcpend")).mkString(" ; ")
    // Taken path (pattern bit 1): rol, bcs, add, subq, bne = 5 macros.
    // Not-taken path (bit 0):     rol, bcs, add, bra, subq, bne = 6 macros.
    val perPeriod = ones * 5 + (32 - ones) * 6
    Kernel("br-patt", src, setup.size + (iters / 32) * perPeriod,
      // d2 counts the pattern's 1 bits (branch taken), d0 its 0 bits: the pair pins the
      // exact sequence of directions the run executed.
      verifyRetirement = brProbeExpect(
        2 -> (ones.toLong * (iters / 32)), 0 -> ((32 - ones).toLong * (iters / 32))))
  }
}
