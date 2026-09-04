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
      iq.lsNzvcWakeup.valid   := lsEu.wakeupNzvc.valid
      iq.lsNzvcWakeup.payload := lsEu.wakeupNzvc.payload
      lsEu.sqCommit.valid   := rob.logic.retire0
      lsEu.sqCommit.payload := rob.logic.h0
      lsEu.sqCommitB.valid   := rob.logic.retire1
      lsEu.sqCommitB.payload := rob.logic.h1
      lsEu.sqFlush          := host[RedirectService].doFlush

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
      rasP.logic.checkpointSave    := (rob.logic.count === U(0, rob.logic.count.getWidth bits)) &&
                                       !rasCheckpointRestore
      rasP.logic.checkpointRestore := rasCheckpointRestore

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

  class FullCoreDut extends Component {
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
    val btb    = new BtbPlugin
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
      require(parts(0) == "l2", s"unknown IPC_MEM=$spec (expected 'zero' or 'l2[:hit[:dram]]')")
      val hit  = if (parts.length > 1) parts(1).toInt else 5
      val dram = if (parts.length > 2) parts(2).toInt else 70
      AxiMemModelConfig(latency = L2LatencyModel(enabled = true, hitCycles = hit, dramCycles = dram))
  }
  // Five live 64-byte I-cache lines occupy ten beats on the core's 256-bit AXI.
  // Keep the legacy D-side capacity unchanged, but do not let the shared model's
  // old eight-beat default silently turn the I-side five-ID contract into four.
  val iMemCfg: AxiMemModelConfig = memCfg.copy(
    maxPendingBeats = scala.math.max(memCfg.maxPendingBeats, 2 * (1 + AxiIds.I_SPEC_SLOTS)))
  def memLabel: String =
    if (!memCfg.latency.enabled) "zero-latency (ideal memory)"
    else s"L2-faithful: L2 hit=${memCfg.latency.hitCycles}cyc, DDR=${memCfg.latency.dramCycles}cyc, 64B line"

  def attachProgram(axi: Axi4ReadOnly, cd: ClockDomain, loadAddr: Long, bytes: Vector[Int]): Unit = {
    AxiMemModel.attachProgramIFetch(axi, cd, loadAddr, bytes, cfg = iMemCfg)
  }

  // ── per-kernel measurement result ───────────────────────────────────────────
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
      lsWbCycles:  Seq[Long] = Nil
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
                          zeroFillData: Boolean = false)

  /** Compile the core ONCE; return a handle that runs one kernel per call. Reusing
    * one compiled DUT across all kernels keeps this a single Verilator build. */
  def runKernel(compiled: SimCompiled[FullCoreDut], k: Kernel,
                seed: Int = IpcBenchSpec.simSeed): IpcResult = {
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

      // Per-cycle MACRO-commit histogram, trimmed to [first-commit, last-commit]
      // after the run (steady-state). To get a MACRO (not µop) histogram we mirror
      // WhiteboxCapture's temp-only rule: a cracked-load temp µop (dstArch >= 16,
      // int-write only, no flags) is the leading load of a [load, op] crack and is
      // NOT counted as a macro instruction. We classify each committing robId by
      // the persistent writeback observed for it (writeback may precede commit).
      val histo = ArrayBuffer.empty[Int]   // macro-commits per sampled cycle
      var totalCycles = 0L
      var sqFwdHitCycles = 0               // SQ full-overlap forward responses
      val traceOn = sys.env.get("MB_TRACE").exists(p => p.nonEmpty && k.name.startsWith(p))
      val traceLines = ArrayBuffer.empty[String]
      val ldCmdAddrs  = ArrayBuffer.empty[Long]  // D$ load physical addresses (premise check)
      val ldCmdCycles = ArrayBuffer.empty[Long]  // D$ load command accepted
      val ldRspCycles = ArrayBuffer.empty[Long]  // D$ load data returned
      val lsWbCycles  = ArrayBuffer.empty[Long]  // LsEu writeback visible
      var firstCommitCycle = -1L
      var lastCommitCycle  = -1L
      val wbMap = scala.collection.mutable.HashMap[Int, WhiteboxCapture.Wb]()

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

      def isTempOnly(wb: WhiteboxCapture.Wb): Boolean =
        wb.intWrite && wb.dstArch >= 16 && !wb.nzvcWrite && !wb.xWrite

      // Snapshot one EU writeback this cycle: record in wbMap (for macro-classify)
      // AND feed the lock-step handle (authoritative macro count, sanity).
      def snapWb(w: m68k040.execute.WbObs): Unit = if (w.valid.toBoolean) {
        val wb = WhiteboxCapture.Wb(
          dstArch   = w.dstArch.toInt,
          result    = w.result.toLong & 0xffffffffL,
          intWrite  = w.intWrite.toBoolean,
          nzvc      = w.nzvc.toInt,
          nzvcWrite = w.nzvcWrite.toBoolean,
          x         = if (w.x.toBoolean) 1 else 0,
          xWrite    = w.xWrite.toBoolean)
        wbMap(w.robId.toInt) = wb
        handle.onWb(w.robId.toInt, wb)
      }

      cd.onSamplings {
        telemCycle += 1
        if (dut.rob.logic.branchCompletion.valid.toBoolean &&
            dut.rob.logic.branchCompletion.payload.mispredict.toBoolean)
          misResolveCycle(dut.rob.logic.branchCompletion.payload.robId.toInt) = telemCycle
        if (dut.rob.logic.branchRedirect.toBoolean)
          misResolveCycle.remove(dut.rob.logic.head.toInt)
            .foreach(c => resolveToRetire += (telemCycle - c).toInt)
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
        // Branch EU writeback: no register/flag write — record a non-temp (it is a
        // macro instruction) and feed the handle so its commit-join succeeds.
        locally {
          val bw = dut.branchEu.logic.wbObs
          if (bw.valid.toBoolean) {
            val wb = WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false)
            handle.onWb(bw.robId.toInt, wb); wbMap(bw.robId.toInt) = wb
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
            handle.onWb(sc.payload.toInt, wb); wbMap(sc.payload.toInt) = wb
          }
        }

        // Count MACRO commits this cycle from the two normal commit ports.
        if (flushPendingCycle >= 0 && telemCycle > flushPendingCycle &&
            (0 until 2).exists(dut.rob.logic.commitObs(_).fire.toBoolean)) {
          flushToCommit += (telemCycle - flushPendingCycle).toInt
          flushPendingCycle = -1L
        }
        var macrosThisCycle = 0
        for (kk <- 0 until 2) {
          val c = dut.rob.logic.commitObs(kk)
          if (c.fire.toBoolean) {
            val id = c.robId.toInt
            val isMacro = wbMap.get(id) match {
              case Some(w) => !isTempOnly(w)
              case None    => true // no writeback seen (e.g. store µop) -> macro
            }
            if (isMacro) macrosThisCycle += 1
            handle.onCommit(id, c.pc.toLong & 0xffffffffL)
          }
        }
        // Exception channel: not exercised by these kernels, but feed it for safety.
        locally {
          val c = dut.rob.logic.commitObs(2)
          if (c.fire.toBoolean) handle.onExcCommit(c.pc.toLong & 0xffffffffL, 0x27, -1L)
        }

        if (macrosThisCycle > 0) {
          if (firstCommitCycle < 0) firstCommitCycle = totalCycles
          lastCommitCycle = totalCycles
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
        }
        if (dut.dcache.logic.loadRspPort.valid.toBoolean) ldRspCycles += telemCycle
        if (dut.lsEu.logic.wbObs.valid.toBoolean) lsWbCycles += telemCycle
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
      val cap   = if (memCfg.latency.enabled) 500000 else 20000
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
        s"[${k.name}] only ${handle.result.size}/$n macro-instructions retired within $cap cycles")

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
      val activeCycles  = windowHisto.count(_ >= 1)
      val dualCycles    = windowHisto.count(_ == 2)

      result = IpcResult(k.name, windowRetired, windowCycles, activeCycles, dualCycles,
        ftbApplies, ftqConfirms, ftqMismatches,
        ftbDirDeclines, ftbFrameDeclines, ftbBusyDeclines, sqFwdHitCycles,
        flushToCommit.toVector,
        ldCmdCycles.toVector, ldCmdAddrs.toVector, ldRspCycles.toVector, lsWbCycles.toVector)
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

}
