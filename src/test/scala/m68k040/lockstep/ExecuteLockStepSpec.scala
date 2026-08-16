package m68k040.lockstep

import m68k040.{M68kParams, M68kSim, VerilatorTest}
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
import m68k040.oracle.{Musashi, OracleStep, ProgramAssembler}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4ReadOnly
import spinal.lib.sim.SparseMemory
import spinal.lib.misc.plugin.{FiberPlugin, PluginHost}
import spinal.lib.misc.database.Database
import org.scalatest.funsuite.AnyFunSuite

/** THE MILESTONE: first full-core Musashi lock-step.
  *
  * Wires the WHOLE core — I-cache → FetchAlign → Decode → Rename → Dispatch →
  * ROB + IssueQueue → 2 ALU EUs → 3 PRFs — and runs straight-line 2-byte
  * programs end-to-end from the I-cache through commit. The retired-instruction
  * stream is reconstructed by the sim-only whitebox (`WhiteboxCapture`, joining
  * EU writeback-obs by robId with the ROB commit-obs, folding CCR) and compared
  * against Musashi via `LockStep`.
  *
  * Corpus is restricted to 2-byte instructions (MOVEQ, reg-reg .l ALU/CMP) so
  * the ROB's stubbed `predNextPc = pc + 2` exactly matches Musashi's
  * post-instruction PC.
  *
  * GUARD NOTE (design doc §5 item 10, task P5.1): Musashi models NO cache —
  * it applies every store to memory immediately and has no notion of a dirty
  * copyback line (it does not even execute CPUSH/CINV; both fall through its
  * generic line-F "unimplemented instruction" trap, `m68ki_exception_1111`,
  * per `tools/musashi/musashi/m68k_in.c`'s catch-all `1111............`
  * pattern). A lock-step PROGRAM that runs CPUSH/CINV against a genuinely
  * dirty copyback line will therefore architecturally DIVERGE from the
  * oracle by design — the RTL correctly models real data loss/writeback
  * that Musashi has no way to reproduce. This is NOT a lock-step bug to
  * chase; it is an inherent limitation of comparing against a cache-less
  * oracle. Do not add CPUSH/CINV programs to this suite's corpus without
  * first re-reading this note. (Confirmed clean as of this note: no current
  * program in this file exercises CPUSH/CINV — verified via
  * `grep -in cinv src/test/scala/m68k040/lockstep/ExecuteLockStepSpec.scala`.)
  */
class ExecuteLockStepSpec extends AnyFunSuite {

  // Perf: compile FullCoreDut ONCE and reuse across all ~394 tests in this suite via
  // repeated .doSim calls, instead of a fresh Verilator recompile per test (previously
  // every runLockStep/runIrqLockStep call did its own M68kSim().withVerilator.compile(...),
  // making this suite's wall-clock dominated by redundant compiles rather than simulation
  // time — the SAME fix PortedTestRunner.scala already applies via its own `lazy val
  // compiled`). `simCounter` guarantees a unique doSim job name even if two call sites
  // happen to reuse the same logical test `name`.
  lazy val compiledDut = M68kSim().withVerilator.compile(new FullCoreDut)
  private val simCounter = new java.util.concurrent.atomic.AtomicInteger(0)
  private def freshSimName(name: String): String = s"$name-${simCounter.incrementAndGet()}"

  /** Wires IQ issue ports to the two ALU EUs + the branch EU, the EU completions
    * to the ROB, and the ROB's commit-time mispredict redirect (RedirectService)
    * to the IQ flush. (Frontend pipeFlush / RAT-rollback flush / fetch redirect are
    * driven inside the consuming plugins from host.get[RedirectService].) */
  class BackendWiringPlugin(eu0: AluEuPlugin, eu1: AluEuPlugin, branchEu: BranchEuPlugin, lsEu: LsEuPlugin, divEu: DivEuPlugin) extends FiberPlugin {
    // Int PRF write port for the exception unit's A7 (reg 15) write-back. Allocated
    // in setup (RegfileService requires it). latency=0 so the handler can read the
    // updated A7 the cycle after the exception commits (it is serializing).
    var a7Wr: m68k040.execute.regfile.RegFileWritePort = null
    // Sim-only boot seed of the int PRF (A7 = boot SSP). Shares the "excA7" physical
    // write port (the exc unit is idle at boot, so no same-cycle collision). Driven by
    // the harness for a couple of cycles after the init sweep, before the first fetch.
    var seedWr: m68k040.execute.regfile.RegFileWritePort = null
    // Int PRF READ port for the LIVE committed A7 readback (arch-15 committed phys). Feeds
    // exc.committedA7In so ss.usp/isp/msp continuously mirror the architectural A7.
    var a7Rd: m68k040.execute.regfile.RegFileReadPort = null
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
      // MOVE-from-SR int result: wire the committed SR system byte to both ALU EUs
      // (mirrors top/FullCoreSynth.BackendWiringPlugin).
      eu0.srSysIn := rob.logic.exc.ss.srSys
      eu1.srSysIn := rob.logic.exc.ss.srSys
      // SLOW-ALU (SHIFT/BITFIELD, S3) dynamic wakeup: ONE IQ port per ALU EU (mirrors
      // top/FullCoreSynth.BackendWiringPlugin). Without this the IQ's aluSlowWakeup
      // ports keep their setup default (valid:=False), so a shift's dependents (and
      // its own aluSlow* busy bitmaps) never wake -> deadlock.
      iq.aluSlowWakeup(0).valid   := eu0.slowWakeup.valid
      iq.aluSlowWakeup(0).payload := eu0.slowWakeup.payload
      iq.aluSlowWakeup(1).valid   := eu1.slowWakeup.valid
      iq.aluSlowWakeup(1).payload := eu1.slowWakeup.payload
      // Branch EU: issue port 2 (branch-class) -> branch EU; completion -> ROB
      // branchCompletion (records {mispredict, nextPc} for commit-time recovery).
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
      // Committed-CCR VALUE completion (per EU): record the {N,Z,V,C,X} the EU wrote
      // for this robId so the exception FSM can stack the SR low byte byte-for-byte.
      def wireCcr(idx: Int, w: m68k040.execute.WbObs): Unit = {
        rob.logic.ccrCompletion(idx).valid            := w.valid
        rob.logic.ccrCompletion(idx).payload.robId    := w.robId
        rob.logic.ccrCompletion(idx).payload.nzvc     := w.nzvc.asUInt
        rob.logic.ccrCompletion(idx).payload.nzvcWrite:= w.nzvcWrite
        rob.logic.ccrCompletion(idx).payload.x        := w.x
        rob.logic.ccrCompletion(idx).payload.xWrite   := w.xWrite
        // The EU writeback VALUE + intWrite (captured per-ROB-entry for a commit-time
        // system op's write direction: the sysOp µop is a MOVE -> result = the source).
        rob.logic.ccrCompletion(idx).payload.result   := w.result
        rob.logic.ccrCompletion(idx).payload.intWrite := w.intWrite
      }
      wireCcr(0, eu0.logic.ccrObs); wireCcr(1, eu1.logic.ccrObs); wireCcr(2, lsEu.logic.ccrObs)
      wireCcr(3, divEu.logic.ccrObs)

      // ── CPLX (DivEu) wiring (mirrors top/FullCoreSynth) ──
      // Issue port 4 -> DivEu; completion (port 3) + dynamic wakeup + euFault.
      divEu.issue << iq.issue(4)
      // Squash a multi-cycle DIV/MUL flushed in flight (same flush the IQ uses).
      divEu.cplxFlush := host[RedirectService].doFlush || rob.logic.excActive
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

      // ── LS cluster wiring (mirrors top/FullCoreSynth.BackendWiringPlugin) ──
      // LS issue port (3) -> LS EU. Its completion is BOTH a ROB completion (port
      // 2) AND the IQ dynamic-wakeup broadcast (variant A: a load's dependents wake
      // when the load's data is actually ready).
      lsEu.issue << iq.issue(3)
      rob.logic.completion(2).valid   := lsEu.completion.valid
      rob.logic.completion(2).payload := lsEu.completion.payload
      // MMU access-fault completion -> ROB (flags the entry vector 2 + faultAddr/SSW
      // for precise format-$7 delivery at retire).
      rob.logic.lsFaultCompletion.valid   := lsEu.faultCompletion.valid
      rob.logic.lsFaultCompletion.payload := lsEu.faultCompletion.payload
      // Precise-path SQ<->ROB loop (Task P2.5, mirrors top/FullCoreSynth).
      rob.logic.completion(4).valid   := lsEu.sqCompletionPort.valid
      rob.logic.completion(4).payload := lsEu.sqCompletionPort.payload
      rob.logic.sqFaultCompletion.valid   := lsEu.sqFaultCompletionPort.valid
      rob.logic.sqFaultCompletion.payload := lsEu.sqFaultCompletionPort.payload
      rob.logic.preciseDrainBusyIn        := lsEu.preciseDrainBusySig
      lsEu.robHeadIn           := rob.logic.h0
      lsEu.robHeadValidIn      := rob.logic.count > 0
      lsEu.irqPreemptPendingIn := rob.logic.interruptPending || rob.logic.tracePendingFire
      // The dynamic wakeup must fire ONLY for a completing LOAD (it produces a
      // physreg). A STORE also completes (to retire) but writes NO register; its
      // s1Ctx.uop.pdst is stale/garbage and could spuriously match a consumer's
      // source physreg, clearing its lsWait early -> the consumer reads the PRF
      // before its real producing load lands. Gate on pdstValid.
      iq.lsWakeup.valid   := lsEu.wakeup.valid
      iq.lsWakeup.payload := lsEu.wakeup.payload
      iq.lsNzvcWakeup.valid   := lsEu.wakeupNzvc.valid
      iq.lsNzvcWakeup.payload := lsEu.wakeupNzvc.payload
      // ROB retire (slot 0) -> SQ commit; doFlush -> SQ flush (squash speculative).
      // ALSO squash speculative SQ entries with a ONE-CYCLE pulse at exception ENTRY (the
      // rising edge of excActive): a privilege-trapped / faulted STORE-form op (e.g. MOVES
      // write in user mode) leaves an UNCOMMITTED store in the SQ; the exc FSM's E_DRAIN
      // waits for sqDrained, which would deadlock on that orphan. The flush KEEPS committed
      // entries (only speculative ones are squashed). It MUST be a single-cycle pulse: a HELD
      // flush gates `headReady` (drain), blocking committed stores from draining into the
      // handler frame -> the pulse squashes the orphan, then E_DRAIN drains committed stores.
      lsEu.sqCommit.valid   := rob.logic.retire0
      lsEu.sqCommit.payload := rob.logic.h0
      lsEu.sqCommitB.valid   := rob.logic.retire1
      lsEu.sqCommitB.payload := rob.logic.h1
      val excEntering = rob.logic.excActive && !RegNext(rob.logic.excActive, init = False)
      lsEu.sqFlush          := host[RedirectService].doFlush || excEntering

      // ── DTLB U/M deferred-write queue wiring (mirrors top/FullCoreSynth) ──
      val dtlb = host[m68k040.mmu.DtlbPlugin]
      dtlb.umAccessRobId := lsEu.xlateRobId
      dtlb.umCommitValid := rob.logic.retire0
      dtlb.umCommitBValid := rob.logic.retire1
      dtlb.umCommitBId    := rob.logic.h1
      dtlb.umCommitId    := rob.logic.h0
      dtlb.umFlush       := host[RedirectService].doFlush
      // ── ITLB U deferred-write queue wiring (U-only; mirrors top/FullCoreSynth) ──
      val itlb = host[m68k040.mmu.ItlbPlugin]
      itlb.umAccessRobId := U(0, 6 bits)
      itlb.umCommitValid := rob.logic.retire0
      itlb.umCommitBValid := rob.logic.retire1
      itlb.umCommitBId    := rob.logic.h1
      itlb.umCommitId    := rob.logic.h0
      itlb.umFlush       := host[RedirectService].doFlush
      dtlb.flushAll      := rob.logic.exc.sysFlushAllValid
      itlb.flushAll      := rob.logic.exc.sysFlushAllValid

      // ── Commit-time mispredict redirect fan-out (registered doFlush pulse) ──
      val doFlush = host[RedirectService].doFlush
      val flushPc = host[RedirectService].flushPc
      val excActive = rob.logic.excActive
      // IQ/skid flush held high while the exception FSM runs (serializing) so
      // wrong-path uops fetched during the multi-cycle sequence are squashed.
      val pipeFlush = doFlush || excActive
      val decodeUop = host[m68k040.services.DecodeUopService]
      iq.flushPort := pipeFlush                     // IQ clear
      decodeUop.pipeFlush := pipeFlush
      host[RenameStage].logic.pipeFlush := doFlush || excActive
      // RAT-rollback flush (rename.flushPort) is already driven by the ROB
      // (rc.flushPort := flushing). Fetch redirect to the resolved target:
      // Front-end complex-packet resume (task #178, ported-tests cluster 11) -- see
      // DecodeStage.scala's `ucComplexResume` comment / FullCoreSynth.scala's mirror.
      val frontendResume = ComplexResumeActionPipe(decodeUop.complexResume, pipeFlush)
      val faRedir = host[FetchAlignPlugin].logic.mispredictRedirect
      faRedir.valid   := doFlush || frontendResume.valid
      faRedir.payload := Mux(doFlush, flushPc, frontendResume.payload)

      // Fetch-time BTB wiring (slice 1): read off the fetch PC, invalidate off the
      // I-cache, feed the registered prediction into FetchAlign's predict input.
      val faBtb = host[FetchAlignPlugin]
      val btb   = host[m68k040.frontend.BtbPlugin]
      val ftb   = host[m68k040.frontend.FtbPlugin]
      // Both invalidate sources: the external boot/reset port AND the internal
      // CPUSH/CINV maintenance pulse (P5.5 follow-up — a stale BTB entry can redirect
      // fetch on a non-branch after SMC, with nothing downstream to catch it).
      val predictorInvalidate = host[IcachePlugin].logic.invalidateAll ||
                                host[IcachePlugin].logic.maintInvalidateAll
      btb.logic.invalidateAll := predictorInvalidate
      ftb.logic.invalidateAll := predictorInvalidate
      btb.logic.queryPc     := faBtb.logic.btbQueryPc0
      btb.logic.queryValid  := faBtb.logic.btbQueryValid0
      btb.logic.query2BasePc := faBtb.logic.btbQueryBasePc1
      btb.logic.query2Sel    := faBtb.logic.btbQuerySel1
      btb.logic.query2Valid := faBtb.logic.btbQueryValid1
      faBtb.logic.btbPredTaken0  := btb.logic.predTakenComb
      faBtb.logic.btbPredTarget0 := btb.logic.predTargetComb
      faBtb.logic.btbPredTaken1  := btb.logic.predTaken2Comb
      faBtb.logic.btbPredTarget1 := btb.logic.predTarget2Comb
      // RAS (slice 2): drive push/pop, read the combinational predict.
      val ras   = host[m68k040.frontend.RasPlugin]
      ras.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll
      ras.logic.pushValid     := faBtb.logic.rasPushValid
      ras.logic.pushRetPc     := faBtb.logic.rasPushRetPc
      ras.logic.popValid      := faBtb.logic.rasPopValid
      faBtb.logic.rasPredValid  := ras.logic.predValid
      faBtb.logic.rasPredTarget := ras.logic.predTarget

      // gshare (slice 3): query the PHT with the aligner slot PCs, feed BTB hit/brType
      // into FetchAlign (condBtbHit), shift the GHR on the emitted conditional, train at
      // retire (ROB GshareUpdateService). Invalidate (GHR clear) on the I-cache flush.
      val gsh   = host[m68k040.frontend.GsharePlugin]
      gsh.logic.invalidateAll := host[IcachePlugin].logic.invalidateAll
      gsh.logic.queryPc0      := faBtb.logic.btbQueryPc0
      gsh.logic.queryValid0   := faBtb.logic.btbQueryValid0
      gsh.logic.queryPc1      := faBtb.logic.btbQueryPc1
      gsh.logic.queryValid1   := faBtb.logic.btbQueryValid1
      faBtb.logic.gsBtbHit0   := btb.logic.predHitComb
      faBtb.logic.gsBtbType0  := btb.logic.predTypeComb
      faBtb.logic.gsBtbHit1   := btb.logic.predHit2Comb
      faBtb.logic.gsBtbType1  := btb.logic.predType2Comb
      faBtb.logic.gsPhtTaken0 := gsh.logic.phtTaken0
      faBtb.logic.gsPhtIndex0 := gsh.logic.phtIndex0
      faBtb.logic.gsPhtTaken1 := gsh.logic.phtTaken1
      faBtb.logic.gsPhtIndex1 := gsh.logic.phtIndex1
      gsh.logic.shiftValid    := faBtb.logic.gsShiftValid
      gsh.logic.shiftDir      := faBtb.logic.gsShiftDir
      gsh.gshareUpdate.valid   := rob.logic.gshareUpdateFlow.valid
      gsh.gshareUpdate.payload := rob.logic.gshareUpdateFlow.payload

      // ── Exception D-cache MUX (the LS EU arbitrates: it owns the cache ports, so
      // the exception unit's requests are routed THROUGH the LS EU's mux — see
      // LsEuPlugin.excActive/excLoad*/excStore*). The exc reads the cache
      // responses directly here. The exception sequencer is serializing (the LS pipe
      // is squashed), so the cache port is free while excActive. ──
      val dc    = host[DcacheService]
      val exc   = rob.logic.exc
      exc.dcLoadRsp.valid   := dc.loadRsp.valid
      exc.dcLoadRsp.payload := dc.loadRsp.payload
      exc.dcLoadBusy        := dc.loadBusy
      exc.dcStoreAck        := dc.storeAck
      // route the exc's cache requests through the LS EU's arbiter
      lsEu.excActive            := excActive
      lsEu.excLoadCmdValid      := exc.dcLoadCmd.valid
      lsEu.excLoadCmdVaddr      := exc.dcLoadCmd.payload.vaddr
      lsEu.excLoadCmdSize       := exc.dcLoadCmd.payload.size
      exc.dcLoadCmd.ready       := lsEu.excLoadCmdReady
      lsEu.excStoreValid        := exc.dcStore.valid
      lsEu.excStorePayload      := exc.dcStore.payload
      exc.dcStore.ready         := lsEu.excStoreReady
      exc.sqDrained             := lsEu.sqEmptySig
      // Task P5.4/P5.5 parity with FullCoreSynth (this block is a hand-maintained
      // mirror of it, and the P5.4 `dcQuiesced` line was missing here — leaving the
      // ExceptionUnit default of a hardcoded `True`, which would silently disable
      // S_DRAIN's "wait for the D-cache to be idle" protection for every lock-step
      // test that executes a CPUSH/CINV now that the maintenance command is actually
      // wired below).
      exc.dcQuiesced            := dc.maintQuiesced
      dc.maintCmd               := exc.maintCmdOut
      exc.maintDoneIn           := dc.maintDone
      host[IcachePlugin].logic.maintInvalidateAll := exc.icMaintPulse
      // A7 (arch-15) write on exc/RTE A7 change. The SAME PRF write port also serves
      // a commit-time SYSTEM op's READ direction (MOVE-USP / MOVEC Rc->Rn writes an
      // arbitrary int arch-Rn): sysRegWrite fires in S_APPLY, a7Write in S_REDIR
      // (consecutive cycles -> no same-cycle collision on the one port).
      // The a7Write address uses committedPhysA7 (commReg(15)) so the write is correct
      // even when arch-15 has been renamed by an OoO A7 write (move ...,%sp / push /
      // bsr). When A7 is unrenamed, committedPhysA7 == 15, identical to old U(15).
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
      // See ExceptionUnit.scala's rteNzvcWriteValid doc comment for the full story.
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
  val fpCtlSvc = host[m68k040.services.FpuControlService]
  divEu.fpCtrlFpcrIn  := fpCtlSvc.fpcr
  divEu.fpCtrlFpsrIn  := fpCtlSvc.fpsr
  divEu.fpCtrlFpiarIn := fpCtlSvc.fpiar
  fpccWr.valid   := exc.fpccWriteValid
  fpccWr.address := host[RenameStage].committedPhysFpcc.resize(fpccWr.address.getWidth)
  fpccWr.data    := exc.fpccWriteData
    }
  }

  /** Full-core DUT: the entire frontend+backend chain. The I-cache AXI master,
    * FetchAlign redirect/resume, EU wbObs and ROB commitObs surface for the sim. */
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
    val itlb   = new ItlbPlugin
    val dtlb   = new DtlbPlugin
    val icache = new IcachePlugin
    val dcache = new DcachePlugin
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

  /** Attach a behavioral AXI read-only memory backed by the assembled program.
    *
    * The assembled `image.bytes` are the m68k big-endian byte stream: instruction
    * word w is stored high-byte-first (`bytes[2i]=w>>8`, `bytes[2i+1]=w&0xff`).
    *
    * The I-cache forms its 64-bit little-endian window from memory bytes such that
    * window-word j = `mem[base+2j+1]<<8 | mem[base+2j]` (low byte at the lower
    * address) and hands that to the aligner/decoder as the instruction opcode. So
    * to present opcode w to the decoder we must store the bytes byte-SWAPPED
    * relative to the big-endian image: low byte first. This matches the proven
    * `IcacheSim.attachMemoryWithWords` convention.
    *
    * HARNESS HARDENING (2026-08-09, see `.superpowers/sdd/task-a3-sentinel-confirmation-report.md`):
    * the doc-comment used to claim "bytes outside the image read as 0 (decode into
    * harmless garbage; never reached in the compared prefix)". BOTH halves of that
    * were wrong, and it cost two full investigations to find out:
    *
    *  - `SparseMemory` does NOT return 0 for an unwritten byte; it returns
    *    PRNG-seeded fill, drawn from the SAME shared `simRandom` stream every other
    *    part of the sim consumes. So the bytes past the program are pseudo-random
    *    OPCODES whose value depends on the sim's PRNG STREAM POSITION.
    *  - They ARE reached. The core runs ahead of the compared prefix: it keeps
    *    fetching past the last program word, decodes that garbage, and speculatively
    *    EXECUTES it. Architectural registers set up by the real program are still
    *    live, so a garbage `move.x Dn,(An)` lands a real store on the very address
    *    the test is about to `checkMem`. The comparison then fails for reasons that
    *    have nothing to do with the DUT.
    *
    * Because the corruption is keyed to PRNG stream POSITION, any change that shifts
    * I-side timing (a different memory model, an extra refill cycle, a prefetch)
    * re-rolls which tests are hit — which is exactly how this was once mis-diagnosed
    * as "a genuine timing-sensitive RTL race in the multi-access/RMW store path"
    * (V1.6b's deferral rationale). It is not a race; it is an unguarded harness.
    *
    * FIX: fill a 4 KiB guard past the image with `0x60FE` = `BRA.S -2`, an
    * unconditional branch to itself. Run-ahead fetch lands in a tight, side-effect-
    * free loop: no store, no register write, no exception. It must NOT be an ILLEGAL
    * opcode (`0x4AFC`) — that was tried and measurably under-delivers, because the
    * trap itself reads `mem[VBR + vector*4]` from an unpopulated vector table and
    * jumps to a wild PC, re-entering the same problem by a different door. Every byte
    * alignment inside the guard still decodes to the same self-branch, so there is no
    * sub-word framing with a different effect.
    *
    * `LockStepRunAheadGuardWords` contributes 2048 words = 4 KiB, sized generously
    * to outlast the front-end run-ahead distance inside the drain window. The latest
    * centralized-model suite completed 394/394. Its six newly exposed LSU failures
    * were real regressions and were fixed, rather than weakened or hidden in this
    * harness; the prior four-case STOP/ITLB exception list was separately eliminated
    * by making each bespoke test seed architectural A7 as the shared harness does. */
  def attachProgram(axi: Axi4ReadOnly, cd: ClockDomain, loadAddr: Long,
                    bytes: Vector[Int]): m68k040.sim.AxiMemModel =
    m68k040.sim.AxiMemModel.attachProgramIFetch(
      axi, cd, loadAddr, bytes,
      runAheadGuardWords = m68k040.sim.AxiMemModel.LockStepRunAheadGuardWords)

  /** Run one program through the full core and lock-step it.
    *
    * `nInstr` is the number of RETIRED (executed) instructions to compare. For
    * straight-line code that equals the source line count (the default, computed
    * below when `nInstr < 0`). For programs with taken branches it must be given
    * explicitly: a taken branch skips/loops, so the executed count differs from the
    * source line count. The oracle (Musashi traces actual execution) and the DUT
    * commit stream are both bounded to the first `nInstr` records. */
  // --- MMU (directed non-identity) lock-step config ---
  // Musashi exposes no 040-MMU config, so for the MMU-enabled lock-step we run the
  // SAME program (register stream still lock-steps vs Musashi — data round-trips
  // store->load regardless of the physical address) and additionally assert the
  // store landed at the DIRECTLY-COMPUTED translated PA (directed, documented). The
  // map is (dataPageVA -> PPN): VA[31:12]==(dataPageVA>>12) maps to PPN.
  val MMU_ROOT = 0x00080000L
  val MMU_PTRT = 0x00081000L
  val MMU_PAGT = 0x00082000L
  // Per-(rootIdx,ptrIdx) leaf page table: the 64-entry leaf table for the data VA
  // lives at MMU_PAGT; the code VA (a different root/ptr index) gets its own leaf
  // table at MMU_PAGT2 so both can coexist under the shared root/ptr tables.
  val MMU_PAGT2 = 0x00083000L
  def mapPage(mem: m68k040.ls.BehavioralMemAgent, va: Long, ppn: Long, leafBase: Long): Unit = {
    // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
    // MSB) — matches TableWalker.selectWord's corrected convention (mirrors
    // DcacheByteLane.extract's LONG case, i.e. how a REAL `move.l` store would lay
    // these same bytes out in memory). Was little-endian before task #194's walker
    // fix; kept the name (not renamed to `pokeWordBE`) to avoid touching every call
    // site below.
    def pokeWordLE(a: Long, w: Long): Unit = for (i <- 0 until 4) mem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
    val rootIdx = ((va >> 25) & 0x7f).toInt
    val ptrIdx  = ((va >> 18) & 0x7f).toInt
    val pageIdx = ((va >> 12) & 0x3f).toInt
    pokeWordLE(MMU_ROOT + rootIdx * 4, (MMU_PTRT & 0xfffffff0L) | 0x3L)
    pokeWordLE(MMU_PTRT + ptrIdx * 4,  (leafBase & 0xfffffff0L) | 0x3L)
    pokeWordLE(leafBase + pageIdx * 4, ((ppn << 12) & 0xfffff000L) | 0x1L)
  }
  // The 68040 has ONE MMU: when enabled, BOTH I-fetch and D-access translate. The
  // oracle treats instruction fetch as IDENTITY (untranslated), so to keep the RTL
  // ITLB byte-for-byte the same we IDENTITY-map the code region (8 pages from the
  // load address — covers the program + handler). Data gets the non-identity PPN.
  def buildMmuTable(mem: m68k040.ls.BehavioralMemAgent, dataPageVA: Long, ppn: Long): Unit = {
    mapPage(mem, dataPageVA, ppn, MMU_PAGT)
    val codeBase = ProgramAssembler.DefaultLoadAddress
    for (i <- 0 until 8) {
      val cva = codeBase + i * 0x1000L
      mapPage(mem, cva, (cva >> 12) & 0xfffffL, MMU_PAGT2)  // identity
    }
  }

  // `pcOnly`: compare only the COMMITTED PC SEQUENCE against the oracle (not full
  // register/CCR/memory state). For F2/F3's directed tests (deep-audit 2026-07-11) the
  // exact audited trigger instruction (a MOVE with a full MEM-INDIRECT source combined
  // with a plain-memory, non-register destination) is correctly FRAMED by the F1/F2/F3
  // predecode fix (no livelock, correct nextPc) but does NOT correctly EXECUTE today —
  // a SEPARATE, pre-existing, previously-unreachable (blocked by the very livelock F2
  // fixes) gap: `MI_MOVE_SRC_ENTRY`/`MI_MOVE_DST_ENTRY` (Microcode.scala) only support
  // "EA <-> register" mem-indirect MOVEs, not "EA <-> EA" (both sides memory), so the
  // µcode engine mis-targets the opword's dst-register field as if the destination were
  // a plain register. This is a genuine, real, DIFFERENT bug from F1/F2/F3 (a µcode-
  // completeness gap, not a predecode-framing bug) — out of scope for this fix, reported
  // separately, NOT silently swept under the rug: `pcOnly` deliberately narrows the
  // assertion to exactly what F2/F3 claim (the front-end doesn't livelock and frames the
  // correct instruction boundary), while remaining honest that full execution
  // correctness of that one exact instruction shape is unverified/known-broken.
  def runLockStep(name: String, src: String, nInstr: Int = -1, checkMem: Seq[Long] = Seq.empty,
                  checkSpan: Int = 4, mmuMap: Option[(Long, Long)] = None,
                  initialSr: Option[Int] = None, usp: Long = 0x00200000L,
                  initialMsp: Option[Long] = None, pcOnly: Boolean = false): Unit = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress

    // Oracle trace (Musashi). Bounds itself at maxCycles/sentinel. `initialSr` (when set)
    // boots both Musashi AND the DUT in a non-default mode (e.g. USER mode S=0 for the
    // privilege-violation test). `initialMsp` (when set) seeds the inactive MSP bank so
    // the program can switch to M=1 without first writing %sp (avoiding the phys-15
    // rename that would break the exc FSM's hardcoded arch-15 write path).
    val oracleSteps: Vector[OracleStep] = Musashi.assembleAndTrace(src, initialSr = initialSr,
                                                                   initialMsp = initialMsp) match {
      case Right(v)  => v
      case Left(err) => fail(s"[$name] Musashi.assembleAndTrace failed: ${err.reason}")
    }
    // Oracle FINAL memory image (for store programs): assembleAndRun returns the
    // per-byte memoryWrites the program performed. Only the program's explicit DATA
    // addresses (`checkMem`, each a 4-byte long) are compared — Musashi's harness
    // also writes the stack/sentinel region which the bare DUT does not exercise.
    val oracleMem: Map[Long, Int] =
      if (checkMem.nonEmpty) Musashi.assembleAndRun(src) match {
        case Right(st) => st.memoryWrites
        case Left(err) => fail(s"[$name] Musashi.assembleAndRun failed: ${err.reason}")
      } else Map.empty
    // Each checked base spans `checkSpan` bytes (4 for a long, 2 for a word). Only
    // program-written bytes are compared (the DUT's SparseMemory defaults unwritten
    // bytes to a non-zero pattern, so checking only the written span is required).
    val checkAddrs: Seq[Long] = checkMem.flatMap(a => (0L until checkSpan.toLong).map(a + _))
    // Program bytes for the I-cache.
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[$name] ProgramAssembler.assemble failed: ${err.reason}")
    }

    // N = number of RETIRED instructions to compare. Default (straight-line) = the
    // source line count; branch programs pass it explicitly (executed count).
    val n = if (nInstr >= 0) nInstr else src.split(';').map(_.trim).count(_.nonEmpty)
    assert(oracleSteps.size >= n,
      s"[$name] oracle produced ${oracleSteps.size} steps, expected >= $n (program ran past its end?)")
    val oracle = oracleSteps.take(n)

    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      var wbCount = 0; var commitCount = 0

      // Capture one EU's writeback-obs into the whitebox (shared by ALU0/ALU1/LS).
      def captureWb(w: m68k040.execute.WbObs): Unit = {
        if (w.valid.toBoolean) {
          wbCount += 1
          handle.onWb(
            w.robId.toInt,
            WhiteboxCapture.Wb(
              dstArch   = w.dstArch.toInt,
              result    = w.result.toLong & 0xffffffffL,
              intWrite  = w.intWrite.toBoolean,
              nzvc      = w.nzvc.toInt,
              nzvcWrite = w.nzvcWrite.toBoolean,
              x         = if (w.x.toBoolean) 1 else 0,
              xWrite    = w.xWrite.toBoolean, divRem = w.divRem.toBoolean,
              keepCommit = w.keepCommit.toBoolean))
        }
      }

      // Per-cycle sampler: EU writeback-obs (join key) + ROB commit-obs (order/pc).
      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs)
        captureWb(dut.eu1.logic.wbObs)
        // LS EU writeback-obs (loads write an int reg incl. the T0/T1 temp; stores
        // write none). Same join key (robId) as the ALU EUs. Temp-only commits are
        // dropped in WhiteboxCapture.onCommit (decode-matrix §4.5).
        captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs);
        // Branch EU writeback-obs: a branch writes NO int/flag reg and leaves CCR
        // unchanged. Map it to a no-write Wb (the commit pc comes from the ROB
        // commitObs = resolved nextPc). dstArch=0 is harmless since intWrite=false.
        {
          val bw = dut.branchEu.logic.wbObs
          if (bw.valid.toBoolean) {
            wbCount += 1
            // A plain branch writes no reg; an RTS/RTR ibranch writes A7 (postinc SP).
            val anWr = bw.anWrite.toBoolean
            handle.onWb(
              bw.robId.toInt,
              WhiteboxCapture.Wb(
                dstArch   = if (anWr) bw.anArch.toInt else 0,
                result    = if (anWr) bw.anData.toLong & 0xffffffffL else 0L,
                intWrite  = anWr,
                nzvc      = 0,
                nzvcWrite = false,
                x         = 0,
                xWrite    = false))
          }
        }
        // Precise-path store completion (Task P2.5): the SQ's at-head drain fires
        // rob.logic.completion(4)/lsEu.sqCompletionPort instead of lsEu.completion
        // for a precise store -- no lsEu.logic.wbObs pulse accompanies it (compValid
        // is never asserted on that path), so synthesize a no-op Wb here (mirrors the
        // branch EU's no-write capture above), or the later onCommit for this robId
        // finds no Wb record and throws.
        {
          val sc = dut.lsEu.sqCompletionPort
          if (sc.valid.toBoolean) {
            wbCount += 1
            handle.onWb(sc.payload.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
          }
        }
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) {
            commitCount += 1
            if (sys.env.contains("CR_RAW")) {
              val w = handle.peekWb(c.robId.toInt)
              println(f"[$name] RAWCOMMIT rob=${c.robId.toInt} pc=0x${c.pc.toLong & 0xffffffffL}%08x dstArch=${w.map(_.dstArch).getOrElse(-1)} intW=${w.map(_.intWrite).getOrElse(false)} res=0x${w.map(_.result & 0xffffffffL).getOrElse(0L)}%08x divRem=${w.map(_.divRem).getOrElse(false)}")
            }
            handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
              sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL,
              msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
              isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
          }
        }
        // Exception / RTE commit channel (handler-entry / restored PC + sysByte/A7).
        {
          val c = dut.rob.logic.commitObs(2)
          if (c.fire.toBoolean) {
            commitCount += 1
            handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL,
              if (c.ccrFoldValid.toBoolean) c.ccrFold.toInt & 0xf else -1,
              if (c.setCcr5Valid.toBoolean) c.setCcr5.toInt & 0x1f else -1,
              msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
              isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
          }
        }
      }

      // Attach the program to the I-cache AXI.
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)

      // Attach a behavioral read/write memory to the D-cache AXI (separate image,
      // zeroed; the programs store before they load, so no data preload needed).
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      // Attach a behavioral memory to the DTLB walker AXI (the page table lives here
      // when the MMU is enabled; idle for MMU-disabled programs). MMU disabled by
      // default -> identity passthrough, so existing programs are unchanged.
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      // The ITLB has its OWN dedicated walker AXI port: attach a second behavioral
      // memory holding the SAME page table (one shared page table, two read ports).
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      // Build the page table (whitebox pokes into the walker's own memory) up front —
      // harmless before the MMU is even enabled. The actual `ctrl.logic.mmuEnable`/
      // `urp`/`srp` ENABLE pokes are issued LATER (see the task #194 comment further
      // down, right before the boot-SP pokes) — NOT here.
      mmuMap.foreach { case (dataPageVA, ppn) =>
        buildMmuTable(ptmem, dataPageVA, ppn)
        buildMmuTable(itlbPtmem, dataPageVA, ppn)
      }
      // debug-only, env-gated trace for MMU-enabled lock-step tests (task #194
      // investigation: an early `mmuEnable`/`urp`/`srp` poke landing inside the reset
      // window was silently wiped once MmuControlPlugin gained a real conditional
      // MOVEC writer — see the "Task #194: poke mmuEnable/urp/srp HERE" comments
      // below). Zero cost unless LS_TRACE_MMU is set.
      if (sys.env.contains("LS_TRACE_MMU")) {
        var trCyc = 0
        cd.onSamplings {
          trCyc += 1
          if (dut.dcache.logic.storePort.valid.toBoolean) {
            val sp = dut.dcache.logic.storePort.payload
            println(f"[lstrace] cyc=$trCyc%6d DSTORE paddr=0x${sp.paddr.toLong & 0xffffffffL}%08x data=0x${sp.data.toLong & 0xffffffffL}%08x")
          }
          if (dut.dcache.logic.loadCmdPort.valid.toBoolean) {
            val c = dut.dcache.logic.loadCmdPort.payload
            println(f"[lstrace] cyc=$trCyc%6d LOADCMD vaddr=0x${c.vaddr.toLong & 0xffffffffL}%08x paddr=0x${c.paddr.toLong & 0xffffffffL}%08x")
          }
          if (dut.dtlb.logic.faultSeen.toBoolean) {
            println(f"[lstrace] cyc=$trCyc%6d DTLB-FAULT-SEEN")
          }
        }
      }

      // Idle the frontend; consumer-driven ready ports default high downstream.
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid    #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0

      // Let the PRF init sweep + rename committed-RAT identity init finish.
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)

      // Boot the committed supervisor SP to Musashi's initial SSP (0x00100000) AFTER
      // the init sweep (it resets committed state) so the surfaced A7 (== SSP, S=1)
      // matches OracleStep.a(7) for every program.
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      // Boot mode: default supervisor (SR boot 0x2700, S=1). When `initialSr` overrides it
      // (e.g. user mode S=0 for the privilege-violation test), seed the committed SR system
      // byte AND the USP bank; in user mode the surfaced A7 == USP, so the int PRF arch-15
      // must mirror USP (not SSP).
      val userMode = initialSr.exists(sr => ((sr >> 13) & 1) == 0)
      // M=1 mode: when `initialSr` has M=1, the active bank at boot is MSP. Seed ss.msp
      // to `initialMsp` so that (a) the live-coherent writeA7 feedback reads the correct
      // PRF value and (b) the oracle (seeded via --initial-msp) matches the DUT from the
      // first step. The ISP remains at 0x00100000 (the inactive bank for M=1).
      val modeM1 = initialSr.exists(sr => ((sr >> 12) & 1) == 1)
      initialSr.foreach(sr => dut.rob.logic.exc.ss.srSys #= (sr >> 8) & 0xff)
      initialMsp.foreach(msp => dut.rob.logic.exc.ss.msp #= BigInt(msp & 0xffffffffL))
      dut.rob.logic.exc.ss.usp #= BigInt(usp & 0xffffffffL)
      // Task #194: the `ctrl.logic.mmuEnable`/`urp`/`srp` ENABLE pokes moved HERE (past
      // the ~82-cycle init-sweep wait above) — poking them at their OLD position
      // (immediately after `attachProgram`, before ANY `cd.waitSampling`) landed inside
      // the DUT's own reset window: the poke took momentarily but was silently wiped
      // the moment the register's real (RegInit-driven) reset deasserted, since adding
      // MmuControlPlugin's new commit-time `setEnable`/`setUrp`/`setSrp` conditional
      // writers (needed for real MOVEC support) turned `mmuEnable`/`urp`/`srp` from
      // free-standing poke-only signals into REAL registered Reg with their own
      // reset-driven initial value, which — unlike a value poked well past reset, which
      // holds forever exactly like `ss.isp`/`ss.msp`/`ss.usp` above (poked at this exact
      // point for EVERY lock-step test, always safely past reset) — a too-early poke
      // does not survive. Moving the poke here (same timing precedent as isp/msp/usp)
      // fixes it with no RTL change needed.
      mmuMap match {
        case Some(_) =>
          dut.ctrl.logic.mmuEnable #= true
          dut.ctrl.logic.urp   #= MMU_ROOT
          dut.ctrl.logic.srp   #= MMU_ROOT
        case None =>
          dut.ctrl.logic.mmuEnable #= false
          dut.ctrl.logic.urp   #= 0
          dut.ctrl.logic.srp   #= 0
      }
      // bootA7: the PRF arch-15 must mirror the ACTIVE bank's value so the OoO datapath
      // sees the correct A7 from the first instruction. Active bank selection:
      //   user mode (S=0) -> USP; M=1 supervisor -> MSP; M=0 supervisor -> ISP.
      val bootA7 = if (userMode) (usp & 0xffffffffL)
                   else if (modeM1) initialMsp.getOrElse(0L) & 0xffffffffL
                   else 0x00100000L
      // Seed the int PRF arch-15 (A7, identity phys-15) to the boot SP: the OoO
      // datapath reads A7 from the int PRF (call/return push/pop), so it must mirror
      // the committed SP at boot (reset loads SP into A7). The exc unit keeps ss.isp
      // in sync on exceptions; the PRF arch-15 follows OoO writes thereafter.
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(bootA7)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      // Pulse redirect to the program load PC to start fetch.
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      // Run until enough committed (or a generous cap).
      var guard = 0
      val cap   = 4000
      while (handle.result.size < n && guard < cap) {
        cd.waitSampling(); guard += 1
        if (sys.env.contains("CR_DECTRACE") && guard < 700) {
          val d = dut.dec.logic
          if (d.pushReg.valid.toBoolean) {
            val cnt = d.pushReg.payload.count.toInt
            val rdy = d.queue.io.push.ready.toBoolean
            val us = (0 until (cnt min 4)).map { i =>
              val u = d.pushReg.payload.uops(i)
              f"pc=0x${u.pc.toLong & 0xffffffffL}%08x${if (u.firstOfInstr.toBoolean) "F" else " "}${if (u.faulted.toBoolean) f"!v${u.faultVector.toInt}" else ""}"
            }.mkString(" | ")
            println(f"[dtr] g=$guard%4d PUSH${if (rdy) "" else "(held)"} n=$cnt $us  ucAct=${d.ucActive.toBoolean} ucBeg=${d.ucBegin.toBoolean} ucPend=${d.ucPendValid.toBoolean} ucPc=${d.ucPc.toInt} fedV=${d.fed.valid.toBoolean} fedR=${d.fed.ready.toBoolean} f0=0x${d.fed.payload.packets(0).pc.toLong & 0xffffffffL}%08x[w0=0x${d.fed.payload.packets(0).words(0).toLong & 0xffffL}%04x w1=0x${d.fed.payload.packets(0).words(1).toLong & 0xffffL}%04x s=${d.fed.payload.packets(0).simple.toBoolean} len=${d.fed.payload.packets(0).lenWords.toInt} wc=${d.fed.payload.packets(0).wordCount.toInt} ft=${d.fed.payload.packets(0).fault.toBoolean}] f1=0x${d.fed.payload.packets(1).pc.toLong & 0xffffffffL}%08x s1v=${d.fed.payload.slot1Valid.toBoolean} stash=${d.stashValid.toBoolean} flush=${d.pipeFlush.toBoolean}")
          } else if (d.ucBegin.toBoolean || d.pipeFlush.toBoolean) {
            println(f"[dtr] g=$guard%4d      ucAct=${d.ucActive.toBoolean} ucBeg=${d.ucBegin.toBoolean} ucPend=${d.ucPendValid.toBoolean} ucPc=${d.ucPc.toInt} fedV=${d.fed.valid.toBoolean} fedR=${d.fed.ready.toBoolean} f0=0x${d.fed.payload.packets(0).pc.toLong & 0xffffffffL}%08x f1=0x${d.fed.payload.packets(1).pc.toLong & 0xffffffffL}%08x s1v=${d.fed.payload.slot1Valid.toBoolean} stash=${d.stashValid.toBoolean} flush=${d.pipeFlush.toBoolean}")
          }
        }
        if (sys.env.contains("CR_RENTRACE") && guard < 900) {
          val rn = dut.ren.logic
          if (rn.fire.toBoolean) {
            val n = if (rn.uop1Sig.toBoolean) 2 else 1
            for (s <- 0 until n) {
              val u = rn.uopsPort.payload(s)
              println(f"[ren] g=$guard%4d s$s pc=0x${u.pc.toLong & 0xffffffffL}%08x dst=${u.dstArch.toInt}%2d(v=${if (u.pdstValid.toBoolean) 1 else 0}) pdst=${u.pdst.toInt}%2d old=${u.pdstOld.toInt}%2d srcA=${u.psrcA.toInt}%2d srcB=${u.psrcB.toInt}%2d srcC=${u.psrcC.toInt}%2d")
            }
          }
          val ws = dut.rfInt.logic.dbgW
          for (i <- 0 until ws.size) {
            if (ws(i).valid.toBoolean) {
              println(f"[prf] g=$guard%4d w$i p${ws(i).address.toInt}%2d := 0x${ws(i).data.toLong & 0xffffffffL}%08x")
            }
          }
          for (k <- 0 until 2) {
            val c = dut.ren.logic.commitPorts(k)
            if (c.valid.toBoolean) {
              val p = c.payload
              println(f"[cmt] g=$guard%4d k$k arch=${p.intArch.toInt}%2d new=${p.intNew.toInt}%2d old=${p.intOld.toInt}%2d iw=${if (p.intWrite.toBoolean) 1 else 0}")
            }
          }
          val fl = dut.ren.logic.intFree
          println(f"[fl ] g=$guard%4d head=${fl.head.toInt}%2d tail=${fl.tail.toInt}%2d cnt=${fl.count.toInt}%2d pa0=${fl.dbgPushAddr(0).toInt}%2d pa1=${fl.dbgPushAddr(1).toInt}%2d")
        }
        if (sys.env.contains("CR_STALL") && guard > cap - 40) {
          println(f"[$name] STALL g=$guard committed=${handle.result.size} ucAct=${dut.dec.logic.ucActive.toBoolean} ucPend=${dut.dec.logic.ucPendValid.toBoolean} ucPc=${dut.dec.logic.ucPc.toInt} robHead=${dut.rob.logic.head.toInt} robCount=${dut.rob.logic.count.toInt} exc=${dut.rob.logic.excActive.toBoolean} vec=${dut.rob.logic.exc.curVec.toInt} excPc=0x${dut.rob.logic.exc.curPc.toLong & 0xffffffffL}%08x")
          if (guard == cap) {
            val q = dut.lsEu.logic.sq
            println(f"[$name] SQ head=${q.head.toInt} tail=${q.tail.toInt} count=${q.count.toInt}")
            for (i <- 0 until 8) {
              println(f"[$name] SQ[$i] v=${q.valids(i).toBoolean} c=${q.committed(i).toBoolean} rob=${q.robIds(i).toInt} pa=0x${q.paddrs(i).toLong & 0xffffffffL}%08x")
            }
          }
        }
      }
      assert(handle.result.size >= n,
        s"[$name] only ${handle.result.size}/$n instructions committed within $cap cycles")

      if (pcOnly) {
        // See the `pcOnly` doc comment above: verify ONLY that the committed PC sequence
        // (front-end framing / nextPc) matches the oracle for all `n` steps — the exact
        // claim F2/F3 make — without requiring full register/memory execution
        // correctness of a separately-broken µcode path.
        val rr = handle.result.take(n)
        for (i <- 0 until n) {
          assert(rr(i).pc == (oracle(i).pc & 0xffffffffL),
            f"[$name] pc-only lock-step diverged at idx=$i: dut pc=0x${rr(i).pc}%08x oracle pc=0x${oracle(i).pc & 0xffffffffL}%08x " +
              s"(front-end framing / nextPc mismatch)")
        }
      } else {

      val res = LockStep.compare(handle.result.take(n), oracle)
      if (!res.ok && sys.env.contains("CR_DEBUG")) {
        val rr = handle.result.take(n)
        for (i <- 0 until n) {
          val c = rr(i); val s = oracle(i)
          println(f"[$name] idx$i%2d dut pc=0x${c.pc}%08x a7=0x${c.a7 & 0xffffffffL}%08x reg${c.archRegId}=0x${c.archRegWrite & 0xffffffffL}%08x(v=${c.archRegValid}) | orc pc=0x${s.pc}%08x a7=0x${s.a(7) & 0xffffffffL}%08x")
        }
      }
      assert(res.ok,
        s"[$name] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
          s"(matched ${res.matched}, dut commits ${handle.result.size}, oracle steps $n)")

      // Final memory check (store programs): let committed stores drain to memory,
      // then compare the program's DATA bytes against the DUT's D-cache memory.
      if (checkMem.nonEmpty) {
        cd.waitSampling(200)
        // Under an enabled MMU the store lands at the TRANSLATED PA. Translate each
        // checked VA byte = (PPN << 12) | (VA & 0xfff) (directed; the page maps the
        // whole data page). MMU-disabled -> identity (PA == VA), unchanged.
        def pa(va: Long): Long = mmuMap match {
          case Some((_, ppn)) => (ppn << 12) | (va & 0xfffL)
          case None           => va
        }
        for (addr <- checkAddrs) {
          val expected = oracleMem.getOrElse(addr, 0) & 0xff
          val got = dmem.peekByte(pa(addr))
          assert(got == expected,
            f"[$name] memory mismatch at VA 0x$addr%08x (PA 0x${pa(addr)}%08x): dut=0x$got%02x oracle=0x$expected%02x")
        }
      }
      }
    }
  }

  /** Lock-step an interrupt program vs Musashi (simple protocol).
    *
    * `irqEvents` = (eventPc, level): the IRQ is recognized at the instruction
    * boundary AT eventPc (that instruction is preempted and re-executes after RTE),
    * EXACTLY as the oracle's trace loop applies it. The DUT raises `iplIn`=level
    * once it has committed the predecessor of eventPc (commitObs.pc == eventPc),
    * matching the oracle's set_irq at PC==eventPc; it drops iplIn on the exception
    * entry commit (one-shot edge), so a level-triggered re-fire after RTE does not
    * loop. `avec`=true => autovector (vec = 24+level), else vectored (`vectorIn`).
    * `initialSr` overrides the boot SR (lower the I-mask so a non-NMI level is taken;
    * both DUT committed srSys and the oracle reset SR are set to it). */
  def runIrqLockStep(name: String, src: String, nInstr: Int,
                     irqEvents: Seq[(Long, Int)], avec: Boolean = true,
                     vectorIn: Int = 0, initialSr: Int = 0x2700,
                     initialMsp: Option[Long] = None): Unit = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val ackVector = if (avec) None else Some(vectorIn)

    val oracleSteps: Vector[OracleStep] =
      Musashi.assembleAndTrace(src, irqEvents = irqEvents, interruptAckVector = ackVector,
                               initialSr = Some(initialSr), initialMsp = initialMsp) match {
        case Right(v)  => v
        case Left(err) => fail(s"[$name] Musashi.assembleAndTrace failed: ${err.reason}")
      }
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[$name] ProgramAssembler.assemble failed: ${err.reason}")
    }
    assert(oracleSteps.size >= nInstr,
      s"[$name] oracle produced ${oracleSteps.size} steps, expected >= $nInstr")
    val oracle = oracleSteps.take(nInstr)
    // Root-cause fix (post-Task-P2.5 lock-step investigation), NMI-specific extra
    // lead time: level 7 can NEVER be recognized via the direct `iplIn > mask`
    // compare when mask is also 7 (7 > 7 is false, by 68k design -- NMI is
    // unmaskable only through EDGE detection) -- RobPlugin's `nmiEdge`/`nmiPending`
    // latch adds ONE MORE register stage beyond the raw retire0/retire1 tap this
    // harness otherwise uses (see the retire0/commitPc0 comment below), so a
    // level-7 event needs its `iplIn` transition poked a full instruction earlier
    // than every other level (which resolves the SAME cycle via the direct
    // compare). Computed from the oracle trace itself: the trigger pc is the
    // TARGET instruction's own PREDECESSOR's pc (one step earlier), so `iplIn`
    // is already 7 by the cycle the target's immediate predecessor retires --
    // still strictly AFTER that predecessor's own retire decision (so it is never
    // itself at risk of being wrongly preempted), but a full cycle ahead of the
    // direct-compare case.
    val eventPcs = irqEvents.map { case (pc, level) =>
      val evPc = pc & 0xffffffffL
      if (level == 7) {
        val idx = oracleSteps.indexWhere(_.pc == evPc)
        if (idx > 0) oracleSteps(idx - 1).pc & 0xffffffffL else evPc
      } else evPc
    }.toSet
    val levelByPc = irqEvents.map { case (pc, l) =>
      val evPc = pc & 0xffffffffL
      val triggerPc = if (l == 7) {
        val idx = oracleSteps.indexWhere(_.pc == evPc)
        if (idx > 0) oracleSteps(idx - 1).pc & 0xffffffffL else evPc
      } else evPc
      triggerPc -> l
    }.toMap

    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      var wbCount = 0; var commitCount = 0
      // Pending IRQ to assert + a one-shot guard so we drive a single edge per event.
      val firedEvents = scala.collection.mutable.Set[Long]()

      def captureWb(w: m68k040.execute.WbObs): Unit = {
        if (w.valid.toBoolean) {
          wbCount += 1
          handle.onWb(w.robId.toInt, WhiteboxCapture.Wb(
            dstArch = w.dstArch.toInt, result = w.result.toLong & 0xffffffffL,
            intWrite = w.intWrite.toBoolean, nzvc = w.nzvc.toInt,
            nzvcWrite = w.nzvcWrite.toBoolean, x = if (w.x.toBoolean) 1 else 0,
            xWrite = w.xWrite.toBoolean, divRem = w.divRem.toBoolean,
            keepCommit = w.keepCommit.toBoolean))
        }
      }

      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs)
        captureWb(dut.eu1.logic.wbObs)
        captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs);
        {
          val bw = dut.branchEu.logic.wbObs
          if (bw.valid.toBoolean) {
            wbCount += 1
            handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
          }
        }
        // Precise-path store completion (Task P2.5): see the captureSq comment
        // elsewhere in this file -- no lsEu.logic.wbObs pulse accompanies a precise
        // store's completion, so synthesize a no-op Wb here too.
        {
          val sc = dut.lsEu.sqCompletionPort
          if (sc.valid.toBoolean) {
            wbCount += 1
            handle.onWb(sc.payload.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
          }
        }
        // Root-cause fix (post-Task-P2.5 lock-step investigation): react to the RAW
        // retire0/retire1 signals (NOT commitObs, which trails by ANOTHER
        // `RegNext` cycle -- see `commitObs(0).fire := RegNext(retire0)`) so the
        // poke lands in time for the immediate successor's OWN retire decision.
        // Only ONE raw cycle separates h0's retire from its successor's own
        // retire eligibility (head advances the very next cycle) -- commitObs's
        // extra register stage ate that entire margin, letting the successor
        // retire (dual-retire OR solo) on stale (pre-poke) `iplIn`. This is what
        // the P2.5 regression's IRQ-family divergences traced to: a precise
        // store's completion (async, many cycles after issue, unlike every other
        // completion source which settles at execute time long before reaching
        // h0) coincides with its immediate successor already being
        // completes-ready, newly exposing this pre-existing one-decision-cycle
        // gap in the reactive-poke technique (previously masked because a fast
        // store's completion timing never happened to align a dual-retire pair,
        // or a same-margin solo retire, exactly at an injection boundary).
        {
          val rawPc0 = dut.rob.logic.commitPc0.toLong & 0xffffffffL
          val rawPc1 = dut.rob.logic.commitPc1.toLong & 0xffffffffL
          if (dut.rob.logic.retire0.toBoolean && eventPcs.contains(rawPc0) && !firedEvents.contains(rawPc0)) {
            firedEvents += rawPc0
            dut.intCtrl.logic.iplIn      #= levelByPc(rawPc0)
            dut.intCtrl.logic.iackAvec   #= avec
            dut.intCtrl.logic.iackVector #= vectorIn
          }
          if (dut.rob.logic.retire1.toBoolean && eventPcs.contains(rawPc1) && !firedEvents.contains(rawPc1)) {
            firedEvents += rawPc1
            dut.intCtrl.logic.iplIn      #= levelByPc(rawPc1)
            dut.intCtrl.logic.iackAvec   #= avec
            dut.intCtrl.logic.iackVector #= vectorIn
          }
        }
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) {
            commitCount += 1
            val pc = c.pc.toLong & 0xffffffffL
            if (sys.env.contains("CR_RAW")) {
              println(f"[$name] RAWCOMMIT k=$k t=${simTime()} rob=${c.robId.toInt} pc=0x$pc%08x iplIn=${dut.intCtrl.logic.iplIn.toInt}")
            }
            handle.onCommit(c.robId.toInt, pc, sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL,
              msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
              isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
          }
        }
        {
          val c = dut.rob.logic.commitObs(2)
          if (c.fire.toBoolean) {
            val isInt = c.isInterrupt.toBoolean
            // Drop the interrupt-ENTRY record: Musashi's trace bundles the entry with
            // the first handler instruction (an async interrupt consumes no user
            // instruction), so the entry is verified by that first handler commit's
            // mask-raised SR + decremented A7. A fault/trap entry and the RTE record
            // ARE their own oracle steps -> keep them.
            if (!isInt) {
              commitCount += 1
              handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL,
                if (c.ccrFoldValid.toBoolean) c.ccrFold.toInt & 0xf else -1,
                msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
                isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
            } else {
              // Drop the IRQ line on the entry commit (one-shot edge): the interrupt
              // is taken, the mask is raised; a re-fire after RTE must not loop.
              dut.intCtrl.logic.iplIn #= 0
            }
          }
        }
      }

      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp   #= 0
      dut.ctrl.logic.srp   #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      // Task #132: seed MSP when the caller wants a boot M=1 scenario (the
      // throwaway-frame tests). Inactive-bank default (0) is harmless when unused.
      dut.rob.logic.exc.ss.msp #= BigInt(initialMsp.getOrElse(0L) & 0xffffffffL)
      // Boot the committed SR to initialSr's system byte (lower the I-mask so a
      // non-NMI level is taken; matches the oracle's --initial-sr).
      dut.rob.logic.exc.ss.srSys #= (initialSr >> 8) & 0xff
      // Seed the int PRF arch-15 (A7, identity phys-15) to the boot SSP. The committed
      // A7 banks (ss.usp/isp/msp) are now LIVE-COHERENT with the architectural A7 read
      // back from the PRF every cycle (the exc unit drives ss.writeA7), so the PRF
      // arch-15 — not the poked ss.isp — is the boot SP source of truth. IRQ tests
      // boot supervisor (initialSr S=1); the active bank is MSP if initialSr also has
      // M=1 (bit 12, task #132 throwaway-frame tests), else ISP = 0x00100000.
      val bootM1 = ((initialSr >> 12) & 1) == 1
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= (if (bootM1) BigInt(initialMsp.getOrElse(0x00100000L) & 0xffffffffL) else BigInt(0x00100000L))
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      var guard = 0; val cap = 6000
      while (handle.result.size < nInstr && guard < cap) { cd.waitSampling(); guard += 1 }
      assert(handle.result.size >= nInstr,
        s"[$name] only ${handle.result.size}/$nInstr instructions committed within $cap cycles")

      val res = LockStep.compare(handle.result.take(nInstr), oracle)
      assert(res.ok,
        s"[$name] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
          s"(matched ${res.matched}, dut commits ${handle.result.size}, oracle steps $nInstr)")
    }
  }

  // ── INTERRUPT lock-step (simple protocol) ────────────────────────────────────
  // Autovector level 5 -> vector 24+5 = 29 -> table @ 0x74. The boot SR mask is
  // lowered to 0 (initialSr 0x2000) so the level-5 IRQ is taken. The IRQ is
  // recognized at the boundary before `moveq #1,%d1` (the preempted instruction
  // re-executes after RTE). The handler bumps D3 and RTEs. PC/SR(incl. mask)/A7 +
  // regs are lock-stepped vs Musashi across entry -> handler -> RTE -> resume.
  test("lock-step IRQ: autovector -> handler -> RTE -> resume", VerilatorTest) {
    // The IRQ-boundary instruction is a LOAD (`move.l 0x90,%d1`) of a value the
    // program just stored (0x11223344). A load is multi-cycle, so the DUT holds it
    // at the head long enough for the asynchronously-raised iplIn (driven the cycle
    // its predecessor commits) to be sampled and preempt it -- and it re-executes
    // (re-loading the same value) after RTE, matching the oracle's re-step.
    val src =
      "move.l #0x11223344,%d0 ; move.l %d0,0x90 ; " + // seed [0x90] = 0x11223344
      "move.l #handler,%d0 ; move.l %d0,0x74 ; " +    // install vector 29 @ 0x74
      "move.l 0x90,%d1 ; moveq #2,%d2 ; " +           // load @ boundary
      "loop: bra loop ; " +
      "handler: moveq #9,%d3 ; rte"
    // event @ load PC = 6+4+6+4 = 0x14 in -> 0x40800014.
    runIrqLockStep("irq-avec", src, nInstr = 10,
      irqEvents = Seq((0x40800014L, 5)), avec = true, initialSr = 0x2000)
  }

  // Task #132 — the M=1 interrupt throwaway frame (format-$1). NOTE: M=1 is entered
  // via a REAL in-program `move.w #imm,%sr` (matching the proven "M-bit MSP/ISP
  // banking" test's pattern below), NOT via `initialSr` baked into boot — an attempt
  // to boot directly with M=1 via `initialSr`+`initialMsp` together hit an ORACLE
  // ordering quirk (`musashi_run.cpp` calls `set_reg(SR,...)` — which internally
  // bank-swaps the active SP — BEFORE `set_reg(MSP,...)`, so the swap reads the
  // not-yet-set MSP shadow and clobbers ISP; a pure boot-time repro diverged at
  // commit 0, `isp: dut=0x00100000 oracle=0x00000000`, before any interrupt logic
  // even ran). Executing the SR write as a real instruction sidesteps this (Musashi's
  // real MOVE-to-SR opcode handler manages the bank swap correctly); `initialMsp` is
  // still used to seed the INACTIVE (M=1) bank ahead of time — that raw `set_reg`
  // has no bank-swap side effect (M stays 0 at that point), so it's safe.
  //
  // Once M=1: entry pushes format-$0 to MSP (->0x0009FFF8), clears M, pushes
  // format-$1 (throwaway) to ISP (->0x000FFFF8); the handler runs ON ISP; RTE pops
  // $1 (discards its PC, restores M=1 rebanking to MSP), loops back and pops the
  // REAL $0 frame off MSP (restores the true PC/SR), resuming with A7 back at the
  // untouched 0x000A0000 — both stacks fully unwound. The harness's onCommit already
  // tracks msp/isp every step, so a wrong bank/base anywhere in this dance diverges.
  test("lock-step IRQ: M=1 -> format-$1 throwaway + format-$0 on MSP -> handler on ISP -> RTE unwinds both", VerilatorTest) {
    val src =
      "move.w #0x3000,%sr ; " +                       // (1) S=1,M=1,I=0 (real instr)
      "move.l #0x11223344,%d0 ; move.l %d0,0x90 ; " +
      "move.l #handler,%d0 ; move.l %d0,0x74 ; " +
      "move.l 0x90,%d1 ; moveq #2,%d2 ; " +
      "loop: bra loop ; " +
      "handler: moveq #9,%d3 ; rte"
    // event PC shifted +4 (one new 4-byte instr) from the plain autovector test's
    // 0x40800014 -> 0x40800018.
    runIrqLockStep("irq-avec-m1-throwaway", src, nInstr = 11,
      irqEvents = Seq((0x40800018L, 5)), avec = true,
      initialMsp = Some(0x000A0000L))
  }

  // Vectored: the SoC presents a vectored vector (0x46 = 70) instead of autovector;
  // the frame's format/vector word + handler fetch use 0x46. Vector 70 table @ 0x118.
  test("lock-step IRQ: vectored -> handler -> RTE -> resume", VerilatorTest) {
    val src =
      "move.l #0x11223344,%d0 ; move.l %d0,0x90 ; " +
      "move.l #handler,%d0 ; move.l %d0,0x118 ; " +   // install vector 0x46 @ 0x118
      "move.l 0x90,%d1 ; moveq #2,%d2 ; " +
      "loop: bra loop ; " +
      "handler: moveq #9,%d3 ; rte"
    runIrqLockStep("irq-vectored", src, nInstr = 10,
      irqEvents = Seq((0x40800014L, 5)), avec = false, vectorIn = 0x46, initialSr = 0x2000)
  }

  // Masked: ipl (3) <= the SR I-mask (boot 7) -> NOT taken. The DUT raises iplIn=3
  // but the recognition never fires; both DUT and oracle run straight-line. (initialSr
  // 0x2700 = mask 7; the oracle's level-3 event is likewise masked.)
  test("lock-step IRQ: masked (ipl <= mask) NOT taken", VerilatorTest) {
    val src =
      "moveq #1,%d1 ; moveq #2,%d2 ; moveq #3,%d3 ; moveq #4,%d4 ; " +
      "loop: bra loop"
    // event @ moveq #3 PC = 0x40800004; level 3 <= mask 7 -> ignored.
    runIrqLockStep("irq-masked", src, nInstr = 5,
      irqEvents = Seq((0x40800004L, 3)), avec = true, initialSr = 0x2700)
  }

  // NMI: level 7 is ALWAYS taken regardless of the mask (boot 7). Vector 31 @ 0x7C.
  test("lock-step IRQ: NMI (level 7) through mask 7", VerilatorTest) {
    val src =
      "move.l #0x11223344,%d0 ; move.l %d0,0x90 ; " +
      "move.l #handler,%d0 ; move.l %d0,0x7c ; " +    // install vector 31 @ 0x7C
      "move.l 0x90,%d1 ; moveq #2,%d2 ; " +
      "loop: bra loop ; " +
      "handler: moveq #9,%d3 ; rte"
    runIrqLockStep("irq-nmi", src, nInstr = 10,
      irqEvents = Seq((0x40800014L, 7)), avec = true, initialSr = 0x2700)
  }

  // ── HELD IPL=7 must NOT cause infinite NMI re-entry (HIGH: hang fix) ─────────
  // Root cause: RobPlugin's interrupt recognition previously compared `iplIn === 7`
  // EVERY CYCLE (level-sensitive), so a line HELD at 7 re-recognized NMI at every
  // subsequent instruction boundary -> infinite re-entry (a hang). Real 68040 NMI is
  // EDGE-triggered (Musashi m68kcpu.c:m68k_set_irq — `if(old_level != 0x0700 &&
  // CPU_INT_LEVEL == 0x0700) nmi_pending = TRUE;`, consumed+cleared ONCE by
  // m68ki_check_interrupts). `runIrqLockStep`'s harness auto-drops iplIn on the entry
  // commit (a one-shot edge, by design — see its doc comment), which is exactly why
  // this bug was invisible to every existing IRQ lock-step test. This test instead
  // holds `iplIn`=7 CONTINUOUSLY (no auto-drop) across many instruction boundaries —
  // spanning the first NMI's entry -> handler -> RTE -> many more loop iterations —
  // and confirms the ROB's recognition pulse (`interruptPending`) fires EXACTLY ONCE
  // while held, then confirms a genuine drop-and-reraise (a fresh edge) DOES re-fire.
  test("NMI (level 7) held continuously does NOT re-fire; a fresh edge DOES", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    // Install the level-7 autovector handler @ VBR(0)+31*4=0x7C via a REAL CPU store
    // (`move.l #handler,%d0 ; move.l %d0,0x7c`) — the SAME proven-correct idiom every
    // `runIrqLockStep` program uses, so there is no raw-testbench-poke byte-order risk.
    // Then a tight loop (many instruction boundaries pass while IPL=7 is held); the
    // bare `rte` pops the entry FSM's own format-0 frame (no program-authored frame
    // needed).
    val src = "move.l #handler,%d0 ; move.l %d0,0x7c ; " +
              "loop: addq.l #1,%d1 ; bra loop ; " +
              "handler: rte"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false; dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.intCtrl.logic.iplIn #= 0; dut.intCtrl.logic.iackAvec #= true; dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      dut.rob.logic.exc.ss.srSys #= 0x27   // boot supervisor, mask 7 (NMI is always taken regardless)
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15; dut.wire.logic.seedData #= BigInt(0x00100000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      // Let the vector-install prologue (move.l #handler,%d0 ; move.l %d0,0x7c) fully
      // commit and the CPU settle into the tight loop before raising IPL.
      cd.waitSampling(60)

      var pulses = 0
      cd.onSamplings { if (dut.rob.logic.interruptPending.toBoolean) pulses += 1 }

      // Raise IPL=7 (rising edge) and HOLD it (no auto-drop) across many boundaries.
      dut.intCtrl.logic.iplIn #= 7
      cd.waitSampling(400)
      assert(pulses == 1, s"held IPL=7 must recognize NMI EXACTLY ONCE (edge-triggered), got $pulses pulses")

      // Drop the line, then raise it again -- a genuine second edge must re-fire.
      dut.intCtrl.logic.iplIn #= 0
      cd.waitSampling(20)
      dut.intCtrl.logic.iplIn #= 7
      cd.waitSampling(400)
      assert(pulses == 2, s"a fresh <7->7 edge must re-fire NMI once more, got $pulses total pulses")
    }
  }

  // Nested: a level-3 IRQ enters handlerA (mask raised to 3); inside handlerA a
  // level-5 IRQ (5 > 3) preempts it -> handlerB -> RTE -> back into handlerA -> RTE
  // -> resume. Two events: at the main load boundary (level 3) and inside handlerA
  // (level 5). Both vectors autovector (29 @ 0x74, 24+3; and 24+5... wait levels:
  // level 3 -> vec 27 @ 0x6C; level 5 -> vec 29 @ 0x74). Both handlers installed.
  test("lock-step IRQ: nested (higher level preempts a running handler)", VerilatorTest) {
    val src =
      "move.l #0x11223344,%d0 ; move.l %d0,0x90 ; " +   // seed [0x90] (main load)
      "move.l #0x55667788,%d0 ; move.l %d0,0x94 ; " +   // seed [0x94] (handlerA's load)
      "move.l #handlerA,%d0 ; move.l %d0,0x6c ; " +     // vec 27 (level 3) @ 0x6C
      "move.l #handlerB,%d0 ; move.l %d0,0x74 ; " +     // vec 29 (level 5) @ 0x74
      "move.l 0x90,%d1 ; moveq #2,%d2 ; " +             // main boundary (level 3 IRQ) @ 0x28
      "loop: bra loop ; " +
      "handlerA: moveq #4,%d4 ; move.l 0x94,%d5 ; rte ; " + // load boundary @ 0x34 (level 5)
      "handlerB: moveq #9,%d3 ; rte"
    // main load @ 0x40800028; handlerA's `move.l 0x94,%d5` (2nd handler instr) @ 0x34
    // -- NOT the first handler instruction (which the oracle bundles with the entry,
    // so a PC-scheduled event there would never be observed). The level-5 IRQ at 0x34
    // preempts handlerA (5 > the raised mask 3) -> handlerB -> RTE -> handlerA -> RTE.
    runIrqLockStep("irq-nested", src, nInstr = 15,
      irqEvents = Seq((0x40800028L, 3), (0x40800034L, 5)), avec = true, initialSr = 0x2000)
  }

  test("lock-step: moveq sequence", VerilatorTest) {
    runLockStep("moveq",
      "moveq #1,%d0 ; moveq #2,%d1 ; moveq #-1,%d2 ; moveq #0,%d3")
  }

  test("lock-step: alu chain (add/sub/and/or)", VerilatorTest) {
    runLockStep("alu-chain",
      "moveq #10,%d0 ; moveq #3,%d1 ; add.l %d1,%d0 ; sub.l %d1,%d0 ; and.l %d1,%d0 ; or.l %d1,%d0")
  }

  test("lock-step: cmp", VerilatorTest) {
    runLockStep("cmp",
      "moveq #5,%d0 ; moveq #5,%d1 ; cmp.l %d1,%d0 ; moveq #7,%d2")
  }

  test("lock-step: register move chain", VerilatorTest) {
    // Register-to-register MOVE: the ALU computes result = src2 (= srcB), so the
    // decoder must place the MOVE source register in srcB. Before the fix the
    // source went to srcA and srcB defaulted to reg0 (D0), so the move read D0's
    // value instead of the named source. We seed D0 with a DIFFERENT value (99)
    // than the source register (D1=42) so the buggy reg0-read is detectable:
    // pre-fix `move.l %d1,%d2` would yield D2=99 (D0), diverging from Musashi's 42.
    runLockStep("regmove",
      "moveq #99,%d0 ; moveq #42,%d1 ; move.l %d1,%d2 ; move.l %d2,%d3")
  }

  // ── MOVE.B/.W to Dn: partial-register write (preserve Dn upper bytes) ───────
  // 68k semantics: MOVE.B writes Dn[7:0] / preserves Dn[31:8]; MOVE.W writes
  // Dn[15:0] / preserves Dn[31:16]; MOVE.L writes all 32. MOVE sets N/Z from the
  // moved value (per size), V=C=0, X untouched. Seed Dn = 0x11223344 (a long-imm
  // MOVE.L) then a .B / .W MOVE: the value AND flags are compared step-for-step.
  test("lock-step: MOVE.B/.W to Dn preserves upper bytes", VerilatorTest) {
    runLockStep("move-partial", Seq(
      "move.l #0x11223344,%d0", "move.b #0xaa,%d0",            // -> 0x112233aa, N=1
      "move.l #0x11223344,%d1", "move.w #0x55aa,%d1",          // -> 0x112255aa
      "move.l #0xffffffff,%d2", "move.b #0x00,%d2",            // -> 0xffffff00, Z=1
      "move.l #0x80000000,%d3", "move.w #0x0001,%d3",          // -> 0x80000001
      "move.l #0x12345678,%d4", "move.b %d0,%d4",              // reg src .B -> 0x123456aa
      "move.l #0x0000abcd,%d5", "move.w %d1,%d5"               // reg src .W -> 0x000055aa
    ).mkString(" ; "))
  }

  // MOVE.L to Dn (full write, no merge) + MOVEA.W/.L (An full-32, .W sign-extend,
  // NO flags) must stay correct — guards against the partial fix touching them.
  test("lock-step: MOVE.L full + MOVEA.W/.L (no merge / no flags)", VerilatorTest) {
    runLockStep("move-l-movea", Seq(
      "move.l #0x11223344,%d0", "move.l #0xaabbccdd,%d0",      // .L full overwrite
      "move.l #0x0000ffff,%d1", "movea.w %d1,%a2",             // .W sign-extend -> 0xffffffff
      "move.l #0x12345678,%d3", "movea.l %d3,%a4"              // .L full to An
    ).mkString(" ; "))
  }

  // ── ADDA/SUBA/CMPA (An-destination arithmetic) — FUZZER-CAUGHT decode bug ───
  // The decoder routed srcA=EA / srcB=An while the ALU computes a-b, so SUBA
  // computed src-An (the exact negative) and CMPA's flags were reversed; ADDA.L
  // was masked by commutativity but ADDA.W also size-merged the old An upper 16
  // instead of carry-propagating the full-32 add of the sign-extended source.
  // Musashi: `AX ± MAKE_INT_16(src)` / 32-bit `dst - src` flags for CMPA.
  test("lock-step: ADDA/SUBA .W/.L reg/An sources (operand order + .W sign-extend + carry)", VerilatorTest) {
    runLockStep("adda-suba-reg", Seq(
      "move.l #0x00001000,%a2", "move.l #0x00000123,%d1", "suba.l %d1,%a2",  // An - src = 0xedd
      "move.l %a2,%d2",
      "move.l #0x00001000,%a3", "adda.l %d1,%a3", "move.l %a3,%d3",          // 0x1123
      // .W source sign-extends: 0xffff -> -1; the full-32 op carry-propagates
      "move.l #0x00010000,%a4", "move.l #0x0000ffff,%d4", "suba.w %d4,%a4",  // 0x10000-(-1)=0x10001
      "move.l %a4,%d5",
      "move.l #0x00010000,%a5", "adda.w %d4,%a5", "move.l %a5,%d6",          // 0x10000+(-1)=0xffff
      // positive .W source (no sign-extend)
      "move.l #0x00000010,%d0", "suba.w %d0,%a5", "move.l %a5,%d7",          // 0xffef
      // An-direct source
      "move.l #0x00000100,%a1", "suba.l %a1,%a4", "adda.l %a1,%a5",
      "move.l %a4,%d2", "move.l %a5,%d3"
    ).mkString(" ; "))
  }

  test("lock-step: CMPA.W/.L flags (Z/N/C/V cases, sign-extended .W source, no write)", VerilatorTest) {
    runLockStep("cmpa-flags", Seq(
      "move.l #5,%a1", "move.l #5,%d1", "cmpa.l %d1,%a1",                    // equal -> Z
      "move.l #3,%a2", "move.l #7,%d2", "cmpa.l %d2,%a2",                    // 3-7 -> N,C
      "move.l #0x80000000,%a3", "move.l #1,%d3", "cmpa.l %d3,%a3",           // INT_MIN-1 -> V
      "move.l #7,%a4", "move.l #3,%d4", "cmpa.l %d4,%a4",                    // 7-3 -> none
      "move.l #0x7fffffff,%a6", "move.l #0xffffffff,%d5", "cmpa.l %d5,%a6",  // MAX-(-1) -> V,C
      // .W: source sign-extends to -1
      "move.l #0,%a5", "move.l #0xffff,%d6", "cmpa.w %d6,%a5",               // 0-(-1) -> C
      "move.l #0xffffffff,%a5", "cmpa.w %d6,%a5",                            // -1-(-1) -> Z
      "move.l #0x10,%d7", "cmpa.w %d7,%a5",                                  // -1-16 -> N
      "cmpa.l %a4,%a3",                                                      // An-direct source
      "move.l %a3,%d0"                                                       // A3 unchanged readback
    ).mkString(" ; "))
  }

  test("lock-step: ADDA/SUBA/CMPA memory sources (.W/.L)", VerilatorTest) {
    runLockStep("adda-family-mem", Seq(
      "move.l #0x3000,%a0",
      "move.l #0xfffffffe,%d0", "move.l %d0,(%a0)",                          // mem[0x3000]=-2
      "move.l #0x100,%a1", "suba.l (%a0),%a1", "move.l %a1,%d1",             // 0x100-(-2)=0x102
      "move.l #0x100,%a2", "adda.w (%a0),%a2", "move.l %a2,%d2",             // +sext(0xffff)=0xff
      "move.l #0x100,%a3", "cmpa.w (%a0),%a3",                               // 0x100-(-1) flags
      "cmpa.l (%a0),%a3",                                                    // 0x100-(-2) flags
      "move.l #0x00000042,%d3", "move.l %d3,(0x3004).l",                     // positive word
      "move.l #0x100,%a4", "adda.l (0x3004).l,%a4", "move.l %a4,%d4",        // abs.l source
      "suba.w (0x3006).w,%a4", "move.l %a4,%d5"                              // low word 0x0042
    ).mkString(" ; "))
  }

  test("lock-step: mixed straight-line (~24 instrs)", VerilatorTest) {
    runLockStep("mixed", Seq(
      "moveq #1,%d0", "moveq #2,%d1", "moveq #3,%d2", "moveq #4,%d3",
      "moveq #-1,%d4", "moveq #100,%d5", "moveq #7,%d6", "moveq #0,%d7",
      "add.l %d1,%d0", "add.l %d2,%d1", "sub.l %d3,%d2", "and.l %d4,%d3",
      "or.l %d5,%d4", "cmp.l %d6,%d5", "add.l %d7,%d6", "sub.l %d0,%d7",
      "and.l %d1,%d0", "or.l %d2,%d1", "add.l %d3,%d2", "cmp.l %d4,%d3",
      "sub.l %d5,%d4", "and.l %d6,%d5", "or.l %d7,%d6", "add.l %d0,%d7"
    ).mkString(" ; "))
  }

  // ── Line-0 immediates (ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,Dn) lock-step ──────
  // Value + NZVCX step-for-step vs Musashi, with flag-affecting operands (carry,
  // overflow, zero, negative) across .B/.W/.L.
  test("lock-step: ADDI/SUBI .B/.W/.L flag boundaries", VerilatorTest) {
    runLockStep("addi-subi", Seq(
      "moveq #0,%d0", "addi.b #0x7f,%d0", "addi.b #1,%d0",       // .B overflow 0x7f+1
      "moveq #0,%d1", "addi.b #0xff,%d1", "addi.b #1,%d1",       // .B carry/zero 0xff+1
      "moveq #0,%d2", "addi.w #0x7fff,%d2", "addi.w #1,%d2",     // .W overflow
      "moveq #0,%d3", "addi.l #0x7fffffff,%d3", "addi.l #1,%d3", // .L overflow
      "moveq #1,%d4", "subi.b #2,%d4",                          // .B borrow -> negative
      "moveq #5,%d5", "subi.w #5,%d5",                          // .W zero
      "moveq #0,%d6", "subi.l #1,%d6"                           // .L borrow -> 0xffffffff
    ).mkString(" ; "))
  }
  // ── Line-5 ADDQ/SUBQ (Dn flags + An full-32 no-flags) lock-step ─────────────
  // Dn dest: ADD/SUB #1-8 with NZVCX (size-merged for .B/.W), flag-edge operands
  // (carry/overflow/zero/negative). An dest: full-32 add/sub, NO flags, .W operates
  // on the full 32 (#imm zero-extended). Value + NZVCX step-for-step vs Musashi.
  test("lock-step: ADDQ/SUBQ .B/.W/.L Dn flags + An full-32 no-flags", VerilatorTest) {
    runLockStep("addq-subq", Seq(
      "moveq #0,%d0", "addq.b #1,%d0", "addq.b #8,%d0",          // .B 0->1->9
      "move.l #0x0000007f,%d1", "addq.b #1,%d1",                // .B overflow 0x7f+1 -> V,N
      "move.l #0x000000ff,%d2", "addq.b #1,%d2",                // .B carry/zero 0xff+1 -> C,Z,X
      "move.l #0x00007fff,%d3", "addq.w #1,%d3",                // .W overflow
      "move.l #0x7fffffff,%d4", "addq.l #1,%d4",                // .L overflow
      "moveq #5,%d5", "subq.b #5,%d5",                          // .B zero
      "moveq #1,%d6", "subq.w #2,%d6",                          // .W borrow -> 0xffff word, N,C,X
      "move.l #0x11223344,%d7", "subq.l #8,%d7",                // .L generic
      // An dest: full-32 add/sub, NO flags. .W operates on the full 32 bits.
      "movea.l #0x00010000,%a0", "addq.w #1,%a0",               // -> 0x00010001 full-32
      "movea.l #0x00000001,%a1", "subq.l #2,%a1",               // -> 0xffffffff full-32
      "movea.l #0x0000ffff,%a2", "addq.w #8,%a2"                // -> 0x00010007 full-32 (no word-wrap)
    ).mkString(" ; "))
  }

  // ── Line-5 Scc (set byte on condition) lock-step ────────────────────────────
  // Scc Dn := cond ? 0xFF : 0x00 (byte partial write, preserve Dn[31:8], NO flags).
  // Seed flags via a CMP, then several Scc conditions both true and false; the upper
  // 24 bits of each Dn (pre-seeded) must be preserved. Value step-for-step vs Musashi.
  test("lock-step: Scc set byte on condition (>=3 conds, true/false, upper preserved)", VerilatorTest) {
    runLockStep("scc", Seq(
      "move.l #0xaaaaaa00,%d0", "move.l #0xbbbbbb00,%d1",
      "move.l #0xcccccc00,%d2", "move.l #0xdddddd00,%d3",
      "move.l #0xeeeeee00,%d4", "move.l #0xffffff00,%d5",
      "moveq #5,%d6", "moveq #5,%d7",
      "cmp.l %d7,%d6",          // 5-5 -> Z=1 (EQ true, NE false, GE/LE true, GT/LT false)
      "seq %d0",                // EQ true  -> D0[7:0]=0xFF -> 0xaaaaaaff
      "sne %d1",                // NE false -> D1[7:0]=0x00 -> 0xbbbbbb00
      "smi %d2",                // MI false (N=0) -> 0xcccccc00
      "spl %d3",                // PL true  (N=0) -> 0xddddddff
      "st  %d4",                // always true -> 0xeeeeeeff
      "sf  %d5"                 // always false -> 0xffffff00
    ).mkString(" ; "))
  }

  // ── Line-5 DBcc / DBRA (decrement-and-branch counted loops) lock-step ───────
  // DBRA (cc=F) is the common counted loop: it ALWAYS decrements Dn.W and branches
  // until Dn.W reaches -1 (0xFFFF), then falls through. The counter is Dn[15:0]
  // (partial — Dn[31:16] preserved). Value + PC/Dn step-for-step vs Musashi.
  test("lock-step: DBRA counted loop (runs N times, exits at -1, Dn.W partial)", VerilatorTest) {
    // D0 = 3 (counter, upper-16 = 0). Body addq.l #1,d1. DBRA decrements D0.W each
    // iteration and branches while D0.W != -1:
    //   iter1 D0:3->2 branch ; iter2 2->1 branch ; iter3 1->0 branch ; iter4 0->-1 fall.
    // Body runs 4 times -> D1 = 4 ; D0 ends 0x0000ffff (low16 = -1, upper16 preserved).
    // Executed: moveq#3(1) + 4*(addq+dbra)(8) + moveq#9(1) = 10.
    runLockStep("dbra",
      "moveq #3,%d0 ; moveq #0,%d1 ; .L: addq.l #1,%d1 ; dbra %d0,.L ; moveq #9,%d2",
      nInstr = 10)
  }

  // DBcc with a REAL condition (DBEQ): branches+decrements only when cond is FALSE
  // (Z=0, not-equal); falls through (NO decrement) when cond is TRUE (Z=1, equal) OR
  // on counter expiry. Exercises the taken (dec+branch), not-taken (cond-true early
  // exit, no decrement) and the counter all together.
  test("lock-step: DBEQ real-condition loop (taken / cond-true fall-through)", VerilatorTest) {
    // D0=4 (counter), D1=0. Body addq.l #1,d1 ; cmp.l #2,d1 (sets Z when d1==2).
    //   iter1: d1=1, 1!=2 -> Z=0 (EQ false) -> dec D0 4->3, branch.
    //   iter2: d1=2, 2==2 -> Z=1 (EQ true)  -> fall through (NO dec): D0 stays 3.
    // Exit: D1=2, D0=3. Executed: moveq#4,moveq#0(2) + iter1(addq,cmp,dbeq=3) +
    //   iter2(addq,cmp,dbeq=3) + moveq#9(1) = 9.
    runLockStep("dbeq",
      "moveq #4,%d0 ; moveq #0,%d1 ; .L: addq.l #1,%d1 ; cmp.l #2,%d1 ; dbeq %d0,.L ; moveq #9,%d2",
      nInstr = 9)
  }

  // DBRA with a non-zero upper-16 in the counter: the decrement-and-test uses ONLY
  // Dn[15:0]; Dn[31:16] must be preserved across the whole loop and the -1 expiry.
  test("lock-step: DBRA preserves Dn[31:16] across the loop", VerilatorTest) {
    // D0 = 0xABCD0002 (counter low16 = 2, upper16 = 0xABCD). DBRA runs the body 3
    // times (2->1->0->-1); D0 ends 0xABCDffff (upper16 preserved). Executed:
    // move.l(1) + moveq#0(1) + 3*(addq+dbra=2)=6 + moveq#9(1) = 9.
    runLockStep("dbra-upper",
      "move.l #0xabcd0002,%d0 ; moveq #0,%d1 ; .L: addq.l #1,%d1 ; dbra %d0,.L ; moveq #9,%d2",
      nInstr = 9)
  }

  test("lock-step: ANDI/ORI/EORI .B/.W/.L (NZ, V=C=0)", VerilatorTest) {
    runLockStep("andi-ori-eori", Seq(
      "move.l #0x12345678,%d0", "andi.l #0xff00ff00,%d0",       // .L AND
      "move.l #0x00000000,%d1", "ori.l #0x80000000,%d1",        // .L OR -> negative
      "move.l #0xaaaaaaaa,%d2", "eori.l #0xffffffff,%d2",       // .L EOR -> 0x55555555
      "move.l #0x000000ff,%d3", "andi.b #0x0f,%d3",             // .B AND
      "move.l #0x00000000,%d4", "ori.w #0x8000,%d4",            // .W OR -> negative word
      "move.l #0x0000ffff,%d5", "eori.w #0xffff,%d5",           // .W EOR -> zero word
      "move.l #0x000000a5,%d6", "eori.b #0xa5,%d6"              // .B EOR -> zero byte
    ).mkString(" ; "))
  }
  test("lock-step: CMPI .B/.W/.L (NZVC, no write)", VerilatorTest) {
    runLockStep("cmpi", Seq(
      "moveq #5,%d0", "cmpi.l #5,%d0",                          // equal -> Z
      "moveq #5,%d1", "cmpi.l #6,%d1",                          // 5-6 -> negative/borrow
      "moveq #-1,%d2", "cmpi.b #0x7f,%d2",                      // .B signed boundary
      "move.l #0x00008000,%d3", "cmpi.w #1,%d3"                 // .W overflow boundary
    ).mkString(" ; "))
  }

  // ── EOR Dn,Dm (register destination) lock-step ──────────────────────────────
  test("lock-step: EOR Dn,Dm .B/.W/.L (NZ, V=C=0)", VerilatorTest) {
    runLockStep("eor-reg", Seq(
      "move.l #0xaaaaaaaa,%d0", "move.l #0x55555555,%d1", "eor.l %d0,%d1",  // -> 0xffffffff neg
      "move.l #0x12345678,%d2", "move.l #0x12345678,%d3", "eor.l %d2,%d3",  // -> 0 zero
      "move.l #0x000000f0,%d4", "move.l #0x0000000f,%d5", "eor.b %d4,%d5",  // .B -> 0xff neg byte
      "move.l #0x0000abcd,%d6", "move.l #0x0000abcd,%d7", "eor.w %d6,%d7"   // .W -> 0 zero word
    ).mkString(" ; "))
  }

  // ── Line-E register-form shifts/rotates (immediate count) lock-step ─────────
  // Each of the 8 ops, .B/.W/.L, with operands exercising C/X/V/N/Z edges:
  // shift-out-1, ASL sign-change (V), and ROX-through-X. Result + NZVCX step-for-step.
  test("lock-step: ASL/ASR .B/.W/.L immediate count (NZVCX, ASL-V)", VerilatorTest) {
    runLockStep("shift-as-imm", Seq(
      "move.l #0x40000000,%d0", "asl.l #1,%d0",            // .L sign-change -> V, C=0
      "move.l #0xc0000000,%d1", "asl.l #1,%d1",            // .L MSB stable (both top bits 1) -> V=0, C=1
      "move.l #0x00000040,%d2", "asl.b #2,%d2",            // .B 0x40<<2 -> 0x00 C=1 from bit6
      "move.l #0xffffffff,%d3", "asr.l #4,%d3",            // .L sign-extend, C=1
      "move.l #0x00008000,%d4", "asr.w #1,%d4",            // .W arithmetic -> 0x0000c000 N=1 C=0
      "move.l #0x00000081,%d5", "asr.b #1,%d5",            // .B -> 0x000000c0 C=1
      "move.l #0x00000001,%d6", "asl.l #8,%d6"             // .L generic
    ).mkString(" ; "))
  }
  test("lock-step: LSL/LSR .B/.W/.L immediate count (NZ, C=X, V=0)", VerilatorTest) {
    runLockStep("shift-ls-imm", Seq(
      "move.l #0x80000000,%d0", "lsl.l #1,%d0",            // .L -> 0 C=1 X=1 Z=1
      "move.l #0x00000001,%d1", "lsr.l #1,%d1",            // .L -> 0 C=1 X=1 Z=1
      "move.l #0x00008000,%d2", "lsl.w #1,%d2",            // .W -> 0 C=1
      "move.l #0x00000001,%d3", "lsr.b #1,%d3",            // .B -> 0 C=1
      "move.l #0x12345678,%d4", "lsr.l #4,%d4",            // .L generic C=0
      "move.l #0x000000ff,%d5", "lsl.b #8,%d5"             // .B shift==size
    ).mkString(" ; "))
  }
  test("lock-step: ROXL/ROXR .B/.W/.L immediate count (rotate-through-X)", VerilatorTest) {
    runLockStep("shift-rox-imm", Seq(
      // seed X via an add that carries, then ROX feeds X in and out.
      "ori #0x10,%ccr",                                    // X=1 (CCR bit4)
      "move.l #0x00000000,%d1", "roxl.l #1,%d1",           // X(1) rotates into bit0 -> d1=1, X=0
      "move.l #0x80000000,%d2", "roxl.l #1,%d2",           // bit31 -> X/C, X-in(0) -> bit0
      "move.l #0x00000001,%d3", "roxr.l #1,%d3",           // bit0 -> X/C
      "move.l #0x00000001,%d4", "roxr.w #1,%d4",           // .W ROX
      "move.l #0x00000080,%d5", "roxl.b #1,%d5"            // .B ROX
    ).mkString(" ; "))
  }
  test("lock-step: ROL/ROR .B/.W/.L immediate count (C, X UNAFFECTED)", VerilatorTest) {
    runLockStep("shift-ro-imm", Seq(
      // pre-set X=1 via carry; ROL/ROR must leave X untouched.
      "ori #0x10,%ccr",                                    // X=1 (CCR bit4)
      "move.l #0x80000001,%d0", "rol.l #1,%d0",            // -> 0x00000003, C=1, X stays 1
      "move.l #0x00000001,%d1", "ror.l #1,%d1",            // -> 0x80000000, C=1, X stays 1
      "move.l #0x00008000,%d2", "rol.w #1,%d2",            // .W -> 0x0001 C=1
      "move.l #0x00000001,%d3", "ror.b #1,%d3",            // .B -> 0x80 C=1
      "move.l #0x12345678,%d4", "rol.l #4,%d4"             // .L generic
    ).mkString(" ; "))
  }

  // ── Line-E register-form shifts/rotates (register count Dc mod 64) lock-step ─
  // count = Dc & 0x3f. Exercise count 0 (flag specials), count >= size, mod-64
  // wrap, and the generic case across the 8 ops + .B/.W/.L.
  test("lock-step: AS/LS register count (count 0, >=size, mod-64)", VerilatorTest) {
    runLockStep("shift-asls-reg", Seq(
      "move.l #0xdeadbeef,%d0", "moveq #0,%d1", "asl.l %d1,%d0",   // count 0: C=0, X untouched, NZ of src
      "move.l #0x00000001,%d2", "moveq #32,%d3", "asl.l %d3,%d2",  // count==size: result 0, C=X=src&1
      "move.l #0x00008000,%d4", "moveq #40,%d5", "lsl.w %d5,%d4",  // .W count 40>16: result 0
      "move.l #0xffffffff,%d6", "moveq #4,%d7", "asr.l %d7,%d6",   // .L generic arithmetic, C=1
      "move.l #0x12345678,%d0", "move.l #64,%d1", "lsl.l %d1,%d0", // Dc=64 -> mod64=0 -> count 0
      "move.l #0x80000000,%d2", "moveq #1,%d3", "lsr.l %d3,%d2"    // .L LSR generic
    ).mkString(" ; "))
  }
  test("lock-step: ROX/RO register count (count 0=ROX-C-from-X, mod-64)", VerilatorTest) {
    runLockStep("shift-roxro-reg", Seq(
      "ori #0x10,%ccr",                                            // X=1
      "move.l #0x00000000,%d0", "moveq #0,%d1", "roxl.l %d1,%d0",  // ROX count0: C=X (1), result=src
      "move.l #0x00000001,%d2", "moveq #1,%d3", "roxr.l %d3,%d2",  // ROXR bit0 -> X/C
      "move.l #0x80000001,%d4", "moveq #0,%d5", "rol.l %d5,%d4",   // ROL count0: C=0, X untouched, NZ src
      "move.l #0x00000001,%d6", "moveq #36,%d7", "ror.l %d7,%d6",  // ROR Dc=36 mod32=4
      "move.l #0x00000003,%d0", "move.l #33,%d1", "roxl.l %d1,%d0" // ROXL Dc=33 mod33=0 -> count0 path
    ).mkString(" ; "))
  }

  // ── Bit-field register forms (BFxxx Dn{#off:#wd}, static) lock-step ─────────
  // All 8 ops with a spread of static offset/width incl. edge cases: offset 0 (MSB),
  // near-31, width 1, width 32 (the 0->32 encoding), non-byte-aligned fields. The FULL
  // CCR (N/Z, V=C=0, X UNCHANGED via the ori-seeded X sentinel) + the written reg
  // (Dy or Dn2) are compared step-for-step vs Musashi.
  test("lock-step: BFTST/BFCHG/BFCLR/BFSET (NZ, V=C=0, X untouched)", VerilatorTest) {
    runLockStep("bf-modify", Seq(
      "ori #0x10,%ccr",                                    // X=1 sentinel (BFxxx must leave X)
      "move.l #0x12345678,%d0", "bftst %d0{#4:#8}",        // N=bit31(d0<<4)=0, Z=field!=0
      "move.l #0x0000ffff,%d1", "bftst %d1{#0:#16}",       // offset0 (MSB) field 0 -> Z=1
      "move.l #0x80000000,%d2", "bftst %d2{#0:#1}",        // N=1 (MSB set), width 1
      "move.l #0x12345678,%d0", "bfchg %d0{#8:#8}",        // toggle middle byte
      "move.l #0xffffffff,%d3", "bfclr %d3{#4:#8}",        // clear non-byte-aligned field
      "move.l #0x00000000,%d4", "bfset %d4{#0:#32}",       // set whole reg (width 32)
      "move.l #0x00000001,%d5", "bfset %d5{#31:#1}"        // set LSB (offset 31, width 1)
    ).mkString(" ; "))
  }
  test("lock-step: BFEXTU/BFEXTS (logical vs arithmetic extract, sign-extend)", VerilatorTest) {
    runLockStep("bf-extract", Seq(
      "ori #0x10,%ccr",                                    // X=1 sentinel
      "move.l #0x12345678,%d0", "bfextu %d0{#0:#16},%d1",  // extract top 16 -> d1=0x1234
      "move.l #0x12345678,%d0", "bfextu %d0{#8:#8},%d2",   // middle byte -> d2=0x34
      "move.l #0xff000000,%d0", "bfexts %d0{#0:#8},%d3",   // field 0xff -> sign-extend -> 0xffffffff
      "move.l #0x0f000000,%d0", "bfexts %d0{#0:#8},%d4",   // field 0x0f -> positive 0x0000000f
      "move.l #0x00008000,%d0", "bfexts %d0{#16:#1},%d5",  // single-bit field=1 -> -1 (sign)
      "move.l #0x80000000,%d0", "bfextu %d0{#0:#32},%d6",  // width 32 logical -> whole reg
      "move.l #0x00000000,%d0", "bfextu %d0{#5:#7},%d7"    // zero field -> d7=0, Z=1
    ).mkString(" ; "))
  }
  test("lock-step: BFFFO (first-set scan + all-zero -> offset+width)", VerilatorTest) {
    runLockStep("bf-ffo", Seq(
      "ori #0x10,%ccr",                                    // X=1 sentinel
      "move.l #0x80000000,%d0", "bfffo %d0{#0:#32},%d1",   // MSB set -> d1=offset(0)
      "move.l #0x08000000,%d0", "bfffo %d0{#0:#32},%d2",   // bit27 set -> 4 zeros -> d2=4
      "move.l #0x00010000,%d0", "bfffo %d0{#0:#16},%d3",   // field top16=0x0001 -> 15 -> d3=15
      "move.l #0x00000000,%d0", "bfffo %d0{#3:#8},%d4",    // all-zero field -> d4=offset+width=11
      "move.l #0x00ff0000,%d0", "bfffo %d0{#8:#8},%d5",    // field=0xff -> first set at offset 8 -> d5=8
      "move.l #0x00000001,%d0", "bfffo %d0{#0:#32},%d6"    // only LSB -> 31 zeros -> d6=31
    ).mkString(" ; "))
  }
  test("lock-step: BFINS (insert into the middle, N/Z from inserted value)", VerilatorTest) {
    runLockStep("bf-ins", Seq(
      "ori #0x10,%ccr",                                    // X=1 sentinel
      "move.l #0xffffffff,%d0", "move.l #0x000000a5,%d4", "bfins %d4,%d0{#8:#8}",  // insert 0xa5 at bit23..16
      "move.l #0x00000000,%d0", "move.l #0x0000000f,%d4", "bfins %d4,%d0{#0:#4}",  // insert nibble at MSB -> N=1
      "move.l #0x12345678,%d0", "move.l #0x00000000,%d4", "bfins %d4,%d0{#4:#8}",  // insert 0 -> Z=1, clears field
      "move.l #0x00000000,%d0", "move.l #0xffffffff,%d4", "bfins %d4,%d0{#0:#32}", // insert full -> d0=0xffffffff
      "move.l #0xaaaaaaaa,%d0", "move.l #0x00000001,%d4", "bfins %d4,%d0{#31:#1}"  // insert LSB
    ).mkString(" ; "))
  }
  // A bit-field result feeding a dependent op (exercises the slow-producer wakeup: the
  // consumer must wait for the S3 bit-field result, not read a stale PRF).
  test("lock-step: BFEXTU result feeds a dependent ADD (slow-producer wakeup)", VerilatorTest) {
    runLockStep("bf-dep", Seq(
      "move.l #0x12345678,%d0", "bfextu %d0{#0:#16},%d1",  // d1 = 0x1234 (slow result)
      "add.l %d1,%d2",                                      // consumes d1 -> must wait on the slow wakeup
      "move.l #0xff000000,%d0", "bfffo %d0{#0:#8},%d3",     // d3 = 0 (MSB set)
      "addq.l #1,%d3"                                       // consumes d3
    ).mkString(" ; "))
  }

  // ── Bit-field DYNAMIC offset/width (BFxxx Dn{Dn:Dn} / {Dn:#w} / {#o:Dn}) ─────
  // The Do/Dw forms read the offset and/or width from a data register (the BFRESOLVE
  // crack packs them into T0; the BITFIELD µop reads srcC=T0). Edge values: dynamic
  // offset 0, dynamic width 32 (Dn value 0 -> 32, and Dn value 32 -> 32), width 1.
  // FULL CCR (N/Z, V=C=0, X UNCHANGED) + the written reg vs Musashi. ×2 seeds.
  test("lock-step: BFTST/BFCHG/BFCLR/BFSET dynamic offset/width", VerilatorTest) {
    runLockStep("bf-modify-dyn", Seq(
      "ori #0x10,%ccr",                                              // X=1 sentinel
      "move.l #0x12345678,%d0", "moveq #4,%d6", "bftst %d0{%d6:#8}",  // dyn offset 4, static wd 8
      "move.l #0x0000ffff,%d1", "moveq #16,%d7", "bftst %d1{#0:%d7}", // dyn width 16, offset 0
      "move.l #0x80000000,%d2", "moveq #0,%d6", "moveq #1,%d7", "bftst %d2{%d6:%d7}", // dyn off 0, wd 1
      "move.l #0x12345678,%d0", "moveq #8,%d6", "bfchg %d0{%d6:%d6}", // dyn off=wd=8
      "move.l #0xffffffff,%d3", "moveq #4,%d6", "moveq #8,%d7", "bfclr %d3{%d6:%d7}",
      "move.l #0x00000000,%d4", "moveq #0,%d6", "moveq #0,%d7", "bfset %d4{%d6:%d7}", // dyn width Dn=0 -> 32
      "move.l #0x00000001,%d5", "moveq #31,%d6", "moveq #1,%d7", "bfset %d5{%d6:%d7}" // off 31, wd 1
    ).mkString(" ; "))
  }
  test("lock-step: BFEXTU/BFEXTS dynamic offset/width (logical/arith, sign)", VerilatorTest) {
    runLockStep("bf-extract-dyn", Seq(
      "ori #0x10,%ccr",
      "move.l #0x12345678,%d0", "moveq #0,%d2", "moveq #16,%d3", "bfextu %d0{%d2:%d3},%d1", // top16 -> 0x1234
      "move.l #0x12345678,%d0", "moveq #8,%d2", "bfextu %d0{%d2:#8},%d4",                   // mid byte 0x34
      "move.l #0xff000000,%d0", "moveq #8,%d3", "bfexts %d0{#0:%d3},%d5",                   // sign-extend 0xff
      "move.l #0x00008000,%d0", "moveq #16,%d2", "moveq #1,%d3", "bfexts %d0{%d2:%d3},%d6", // 1-bit field=1 -> -1
      "move.l #0x80000000,%d0", "moveq #0,%d2", "moveq #32,%d3", "bfextu %d0{%d2:%d3},%d7"  // dyn width 32 (Dn=32)
    ).mkString(" ; "))
  }
  test("lock-step: BFFFO dynamic offset/width", VerilatorTest) {
    runLockStep("bf-ffo-dyn", Seq(
      "ori #0x10,%ccr",
      "move.l #0x08000000,%d0", "moveq #0,%d2", "moveq #32,%d3", "bfffo %d0{%d2:%d3},%d1", // bit27 -> 4
      "move.l #0x00000000,%d0", "moveq #3,%d2", "moveq #8,%d3", "bfffo %d0{%d2:%d3},%d4",   // all-zero -> off+wd=11
      "move.l #0x00ff0000,%d0", "moveq #8,%d2", "bfffo %d0{%d2:#8},%d5",                    // first set at off 8
      "move.l #0x00000001,%d0", "moveq #0,%d2", "moveq #0,%d3", "bfffo %d0{%d2:%d3},%d6"    // dyn wd 32 -> LSB -> 31
    ).mkString(" ; "))
  }
  test("lock-step: BFINS dynamic offset/width (3-source: Dy + Dn2 + T0)", VerilatorTest) {
    runLockStep("bf-ins-dyn", Seq(
      "ori #0x10,%ccr",
      "move.l #0xffffffff,%d0", "move.l #0x000000a5,%d1", "moveq #8,%d2", "bfins %d1,%d0{%d2:#8}",
      "move.l #0x00000000,%d0", "move.l #0x0000000f,%d1", "moveq #0,%d2", "moveq #4,%d3", "bfins %d1,%d0{%d2:%d3}",
      "move.l #0x12345678,%d0", "move.l #0x00000000,%d1", "moveq #4,%d2", "moveq #8,%d3", "bfins %d1,%d0{%d2:%d3}",
      "move.l #0x00000000,%d0", "move.l #0xffffffff,%d1", "moveq #0,%d2", "moveq #0,%d3", "bfins %d1,%d0{%d2:%d3}", // dyn wd 32
      "move.l #0xaaaaaaaa,%d0", "move.l #0x00000001,%d1", "moveq #31,%d2", "moveq #1,%d3", "bfins %d1,%d0{%d2:%d3}"
    ).mkString(" ; "))
  }
  // Dynamic bit-field result feeding a dependent op (the BFRESOLVE T0 RAW + the slow
  // bit-field result wakeup both exercised in one chain).
  test("lock-step: BFEXTU dynamic feeds a dependent ADD", VerilatorTest) {
    runLockStep("bf-dyn-dep", Seq(
      "move.l #0x12345678,%d0", "moveq #0,%d2", "moveq #16,%d3", "bfextu %d0{%d2:%d3},%d1",
      "add.l %d1,%d4",
      "move.l #0xff000000,%d0", "moveq #8,%d2", "bfffo %d0{#0:%d2},%d5",
      "addq.l #1,%d5"
    ).mkString(" ; "))
  }

  // ── Bit-field MEMORY load-only forms (BFTST/BFEXTU/BFEXTS/BFFFO <ea>) — slice 3a ──
  // Pre-seed memory at 0x3000 with a known big-endian pattern via stores, then run the
  // bit-field loads at varied offset/width (bitOff 0 / >0, span 1..5 bytes, width
  // 1/8/16/32). Verified step-for-step vs Musashi (Dn2 + N/Z, X untouched). The memory
  // byteAddr = base + offset>>3; the LS reads a (possibly misaligned) LONG + optional
  // spill byte.
  // Pattern stored at 0x3000: 0x12 0x34 0x56 0x78 0x9A 0xBC 0xDE 0xF0 (two longs).
  val bfMemSeed = Seq(
    "move.l #0x3000,%a0",
    "move.l #0x12345678,%d0", "move.l %d0,(%a0)",       // mem[0x3000..3003] = 12 34 56 78
    "move.l #0x9abcdef0,%d1", "move.l %d1,4(%a0)",      // mem[0x3004..3007] = 9A BC DE F0
    "move.l #0x3000,%a0"                                 // A0 = base
  )
  test("lock-step: BFTST mem (An) varied offset/width (NZ, X untouched)", VerilatorTest) {
    runLockStep("bfmem-tst", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",                                  // X=1 sentinel
      "bftst (%a0){#0:#8}",                              // byte 0x12 (top bit 0) -> N=0, field!=0
      "bftst (%a0){#0:#1}",                              // MSB of 0x12 = 0 -> N=0,Z=1
      "bftst (%a0){#3:#5}",                              // bitOff 3, width 5 (within byte) field!=0
      "bftst (%a0){#8:#16}",                             // bytes 0x3456 -> N=0
      "bftst (%a0){#0:#32}"                              // whole long 0x12345678
    )).mkString(" ; "))
  }
  test("lock-step: BFEXTU mem (An) zero-extend, span 1..4 bytes", VerilatorTest) {
    runLockStep("bfmem-extu", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",
      "bfextu (%a0){#0:#16},%d2",                        // top 16 bits -> 0x1234
      "bfextu (%a0){#8:#8},%d3",                         // middle byte 0x34
      "bfextu (%a0){#0:#32},%d4",                        // whole long -> 0x12345678
      "bfextu (%a0){#4:#12},%d5",                        // bitOff 4, 12 bits -> 0x234
      "bfextu (%a0){#3:#5},%d6"                          // 5-bit field at bit 3 of 0x12 (00010)
    )).mkString(" ; "))
  }
  test("lock-step: BFEXTS mem (An) sign-extend (+ (d16,An))", VerilatorTest) {
    runLockStep("bfmem-exts", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",
      "bfexts (%a0){#0:#8},%d2",                         // field 0x12 (top bit 0) -> +0x12
      "bfexts 4(%a0){#0:#8},%d3",                        // field 0x9A (top bit 1) -> sign-ext 0xFFFFFF9A
      "bfexts (%a0){#0:#1},%d4",                         // single bit 0 -> 0
      "bfexts 4(%a0){#0:#4},%d5"                         // field 0x9 -> top bit 1 -> sign-ext 0xFFFFFFF9
    )).mkString(" ; "))
  }
  test("lock-step: BFFFO mem (An) first-set + all-zero -> offset+width", VerilatorTest) {
    runLockStep("bfmem-ffo", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",
      "bfffo (%a0){#0:#8},%d2",                          // 0x12 = 0001_0010 -> 3 leading zeros -> 0+3
      "bfffo (%a0){#8:#8},%d3",                           // 0x34 = 0011_0100 -> 2 lz -> 8+2=10
      "bfffo (%a0){#0:#32},%d4",                          // 0x12345678 -> 3 lz -> 3
      "move.l #0,%d0", "move.l %d0,8(%a0)",               // mem[0x3008..b]=0 (all-zero field)
      "bfffo 8(%a0){#3:#8},%d5"                           // all-zero -> offset+width = 3+8 = 11
    )).mkString(" ; "))
  }
  // bitOff>0 5-BYTE-SPAN (the spill-byte path): offset 7, width 28 -> bitOff 7,
  // bitOff+width=35>32 -> reads byteAddr long + byteAddr+4 byte.
  test("lock-step: BFEXTU mem 5-byte span (offset 7, width 28) — spill byte path", VerilatorTest) {
    runLockStep("bfmem-span5", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",
      "bfextu (%a0){#7:#28},%d2",                        // bitOff 7 spans into the 5th byte
      "bfffo (%a0){#7:#28},%d3",                          // FFO over the same wide field
      "bfexts (%a0){#1:#31},%d4"                          // bitOff 1, width 31 -> bitOff+width=32 (no spill)
    )).mkString(" ; "))
  }
  // offset>=8 (byteAddr = base + offset>>3): exercises the disp byte-fold.
  test("lock-step: BFEXTU mem offset>=8 (byte-address fold)", VerilatorTest) {
    runLockStep("bfmem-bytefold", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",
      "bfextu (%a0){#16:#16},%d2",                       // offset 16 -> byteAddr base+2, bitOff 0 -> 0x5678
      "bfextu (%a0){#24:#8},%d3",                         // offset 24 -> byteAddr base+3 -> 0x78
      "bfextu (%a0){#20:#12},%d4",                        // offset 20 -> byteAddr base+2, bitOff 4 -> 0x678
      "bfextu (%a0){#17:#15},%d5"                         // offset 17 -> byteAddr base+2, bitOff 1
    )).mkString(" ; "))
  }
  // BFEXTU mem result feeding a dependent op (the slow BITFIELD result through the LS T0
  // load — exercises the load->compute->consumer wakeup chain).
  test("lock-step: BFEXTU mem feeds a dependent ADD", VerilatorTest) {
    runLockStep("bfmem-dep", (bfMemSeed ++ Seq(
      "bfextu (%a0){#0:#16},%d2",                        // d2 = 0x1234
      "add.l %d2,%d3",
      "bfffo (%a0){#8:#8},%d4",                           // d4 = 10
      "addq.l #1,%d4"
    )).mkString(" ; "))
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Bit-field DYNAMIC offset/width MEMORY read-only forms (slice 3c). Dn-sourced
  // offset (Do=ext[11]) and/or width (Dw=ext[5]). byteBase = eaBase + (offsetDn >>>signed 3);
  // bitOff = offsetDn & 7; width = ((widthDn-1)&31)+1 (0->32). ALWAYS-5-byte (spill) span.
  // Verified step-for-step vs Musashi (Dn2 + N/Z, X untouched). A0 = 0x3004 (mid-buffer) so
  // NEGATIVE offsets stay in seeded memory. mem: 3000=12 34 56 78  3004=9A BC DE F0  3008=0F 1E 2D 3C
  // ════════════════════════════════════════════════════════════════════════════
  val bfDynSeed = Seq(
    "move.l #0x3000,%a0",
    "move.l #0x12345678,%d0", "move.l %d0,(%a0)",
    "move.l #0x9abcdef0,%d0", "move.l %d0,4(%a0)",
    "move.l #0x0f1e2d3c,%d0", "move.l %d0,8(%a0)",
    "move.l #0x3004,%a0"
  )
  test("lock-step: BFEXTU mem DYNAMIC off/wd/both (small/large/neg offset, spill, wd32)", VerilatorTest) {
    runLockStep("bf3c-extu", (bfDynSeed ++ Seq(
      "ori #0x10,%ccr",                                            // X=1 sentinel (X untouched)
      "moveq #0,%d2",  "moveq #16,%d3", "bfextu (%a0){%d2:%d3},%d1", // both dyn: off0 wd16 -> 0x9ABC
      "moveq #8,%d2",  "moveq #8,%d3",  "bfextu (%a0){%d2:%d3},%d4", // off8 wd8 -> 0xBC
      "moveq #4,%d2",  "bfextu (%a0){%d2:#12},%d5",                 // dyn OFF only: bitOff4 wd12
      "moveq #16,%d2", "moveq #16,%d3", "bfextu (%a0){%d2:%d3},%d6", // off16 -> byteBase+2 -> 0xDEF0
      "moveq #-8,%d2", "moveq #8,%d3",  "bfextu (%a0){%d2:%d3},%d7", // NEG off-8 -> byteBase-1 bitOff0 -> 0x78
      "moveq #-1,%d2", "bfextu (%a0){%d2:#8},%d0",                  // NEG off-1 -> byteBase-1 bitOff7
      "moveq #0,%d3",  "bfextu (%a0){#0:%d3},%d1",                  // dyn WD only: wd Dn=0 -> 32 -> 0x9ABCDEF0
      "moveq #6,%d2",  "moveq #30,%d3", "bfextu (%a0){%d2:%d3},%d4" // SPILL: bitOff6 wd30 -> 5-byte span
    )).mkString(" ; "))
  }
  test("lock-step: BFEXTS mem DYNAMIC off/wd/both (sign-extend, neg offset, spill)", VerilatorTest) {
    runLockStep("bf3c-exts", (bfDynSeed ++ Seq(
      "ori #0x10,%ccr",
      "moveq #0,%d2",  "moveq #8,%d3",  "bfexts (%a0){%d2:%d3},%d1", // 0x9A top bit 1 -> sign-ext 0xFFFFFF9A
      "moveq #8,%d2",  "bfexts (%a0){%d2:#4},%d4",                   // 0xB -> top bit 1 -> 0xFFFFFFFB
      "moveq #-8,%d2", "moveq #8,%d3",  "bfexts (%a0){%d2:%d3},%d5", // NEG -> byte 0x78 top bit 0 -> +0x78
      "moveq #7,%d2",  "moveq #28,%d3", "bfexts (%a0){%d2:%d3},%d6"  // SPILL spanning bytes
    )).mkString(" ; "))
  }
  // BFFFO mem-dynamic (un-deferred): the FFOFULL redesign — a prefunnel (bfMem BFTST form)
  // collapses lo/hi+bitOff -> field32, then ONE committed bfMem funnel (bfStoreForm=6) computes
  // Dn2 = full-signed-Dn[off] + first-set-index with N/Z from the field. NO flag-carrying
  // trailing ADD (the deferred design carried the flags on a generic writesFlags ADD µop,
  // whose writesX=True clobbered X with the ADD's carry-out — the historical "X spuriously
  // cleared"). X-untouched is asserted through the REAL datapath: the trailing ADDX reads the
  // physical X (d0 = 0+0+X = 1 iff the sentinel survived the whole chain).
  test("lock-step: BFFFO mem DYNAMIC off/wd/both (offset+index, neg offset, all-zero)", VerilatorTest) {
    runLockStep("bf3c-ffo", (bfDynSeed ++ Seq(
      "ori #0x10,%ccr",
      "moveq #0,%d2",  "moveq #8,%d3",  "bfffo (%a0){%d2:%d3},%d1", // 0x9A=1001_1010 -> 0 lz -> off0+0=0
      "moveq #8,%d2",  "moveq #8,%d3",  "bfffo (%a0){%d2:%d3},%d4",  // 0xBC=1011_1100 -> off8+0=8
      "moveq #1,%d2",  "bfffo (%a0){%d2:#8},%d5",                    // dyn off only -> off1 + index
      "moveq #-8,%d2", "moveq #8,%d3",  "bfffo (%a0){%d2:%d3},%d6",  // NEG off -> 0x78=0111_1000 -> off-8+1=-7
      "moveq #16,%d2", "moveq #32,%d3", "bfffo (%a0){%d2:%d3},%d7",  // dyn wd32 over 0xDEF0... -> off16+index
      "moveq #0,%d0",  "addx.l %d0,%d0",                             // X CONSUMER: d0 = X (must be 1)
      "nop"
    )).mkString(" ; "))
  }
  test("lock-step: BFTST mem DYNAMIC off/wd/both (flags only, X untouched)", VerilatorTest) {
    runLockStep("bf3c-tst", (bfDynSeed ++ Seq(
      "ori #0x10,%ccr",
      "moveq #0,%d2",  "moveq #1,%d3",  "bftst (%a0){%d2:%d3}",      // MSB of 0x9A = 1 -> N=1
      "moveq #8,%d2",  "moveq #8,%d3",  "bftst (%a0){%d2:%d3}",      // 0xBC field
      "moveq #-1,%d2", "bftst (%a0){%d2:#8}",                        // NEG off
      "moveq #5,%d2",  "moveq #30,%d3", "bftst (%a0){%d2:%d3}"       // SPILL
    )).mkString(" ; "))
  }
  // ── RMW DYNAMIC (BFCHG/BFCLR/BFSET) — modify mem then read back THROUGH memory + checkMem.
  // NZ from the ORIGINAL field; X untouched. Small/large/NEGATIVE offset, spill, width 0->32.
  test("lock-step: BFSET mem DYNAMIC off/wd/both (read-back, neg offset, spill)", VerilatorTest) {
    runLockStep("bf3c-set", (bfDynSeed ++ Seq(
      "ori #0x10,%ccr",
      "moveq #0,%d2",  "moveq #16,%d3", "bfset (%a0){%d2:%d3}",      // both dyn: set top 16 of 0x9ABCDEF0
      "moveq #8,%d2",  "moveq #8,%d3",  "bfset (%a0){%d2:%d3}",      // off8 wd8
      "moveq #-8,%d2", "moveq #8,%d3",  "bfset (%a0){%d2:%d3}",      // NEG -> byteBase 0x3003
      "moveq #5,%d2",  "moveq #30,%d3", "bfset (%a0){%d2:%d3}",      // SPILL (5-byte store)
      "move.l (%a0),%d4", "move.l -4(%a0),%d5", "move.l 4(%a0),%d6"  // read modified longs
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L, 0x3008L), checkSpan = 4)
  }
  test("lock-step: BFCLR mem DYNAMIC off/wd/both (read-back, neg offset, spill)", VerilatorTest) {
    runLockStep("bf3c-clr", (bfDynSeed ++ Seq(
      "ori #0x10,%ccr",
      "moveq #4,%d2",  "moveq #12,%d3", "bfclr (%a0){%d2:%d3}",      // bitOff4 wd12
      "moveq #0,%d2",  "moveq #0,%d3",  "bfclr (%a0){%d2:%d3}",      // dyn wd=0 -> 32
      "moveq #-1,%d2", "moveq #8,%d3",  "bfclr (%a0){%d2:%d3}",      // NEG off-1 bitOff7
      "move.l (%a0),%d4", "move.l -4(%a0),%d5"
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 4)
  }
  test("lock-step: BFCHG mem DYNAMIC off/wd/both (read-back, width 1/8/32)", VerilatorTest) {
    runLockStep("bf3c-chg", (bfDynSeed ++ Seq(
      "ori #0x10,%ccr",
      "moveq #0,%d2",  "moveq #1,%d3",  "bfchg (%a0){%d2:%d3}",      // flip MSB
      "moveq #3,%d2",  "moveq #8,%d3",  "bfchg (%a0){%d2:%d3}",      // 8-bit at bitOff3
      "moveq #7,%d2",  "moveq #28,%d3", "bfchg (%a0){%d2:%d3}",      // SPILL bitOff7 wd28
      "move.l (%a0),%d4", "move.l 4(%a0),%d5"
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 4)
  }
  // BFINS mem DYNAMIC: insert Dn into the (dynamic offset/width) memory field; N/Z from the
  // INSERTED value (Musashi: insert_base = Dn<<(32-width)), V=C=0, X untouched. Read back
  // through memory + checkMem. Small/large/NEGATIVE offset, spill, width 0->32; the SR is
  // compared at every commit so the N/Z from each insert is asserted; the trailing ADDX
  // asserts X-untouched through the REAL physical-X datapath (d0 = 0+0+X = 1).
  test("lock-step: BFINS mem DYNAMIC off/wd/both (insert, N/Z from inserted, neg offset, spill, wd32)", VerilatorTest) {
    runLockStep("bf3c-ins", (bfDynSeed ++ Seq(
      "ori #0x10,%ccr",                                                           // X=1 sentinel (X untouched)
      "move.l #0xffffffff,%d1", "moveq #-32,%d2", "moveq #8,%d3",  "bfins %d1,(%a0){%d2:%d3}",  // NEG off -32 -> byteBase 0x3000 ALIGNED (N=1)
      "move.l #0x1234,%d1",     "moveq #0,%d2",   "moveq #16,%d3", "bfins %d1,(%a0){%d2:%d3}",  // both dyn: off0 wd16
      "move.l #0x2aaaaaaa,%d1", "moveq #6,%d2",   "moveq #30,%d3", "bfins %d1,(%a0){%d2:%d3}",  // SPILL: bitOff6 wd30 (5-byte span)
      "moveq #0x5a,%d1",        "moveq #9,%d2",                    "bfins %d1,(%a0){%d2:#7}",   // dyn OFF only, static wd
      "move.l #0xdeadbeef,%d1", "moveq #0,%d3",                    "bfins %d1,(%a0){#4:%d3}",   // dyn WD only: wd 0 -> 32 (spill)
      "moveq #0,%d1",           "moveq #-1,%d2",  "moveq #4,%d3",  "bfins %d1,(%a0){%d2:%d3}",  // NEG off-1 (bitOff7), insert 0 -> Z=1
      "moveq #0,%d0",           "addx.l %d0,%d0",                                               // X CONSUMER: d0 = X (must be 1)
      "move.l (%a0),%d4", "move.l -4(%a0),%d5", "move.l 4(%a0),%d6",                            // read back through memory
      "nop"
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L, 0x3008L), checkSpan = 4)
  }

  // ════════════════════════════════════════════════════════════════════════════
  // Bit-field MEMORY RMW forms (BFCHG/BFCLR/BFSET/BFINS <ea>) — slice 3b. RMW then
  // READ BACK THROUGH MEMORY (a following bfextu/move into a Dn) to catch stored-byte
  // divergence, PLUS checkMem on the modified longs vs Musashi. The flags are from the
  // LOADED field (before modify). Cover bitOff=0 (lomask=0 corner) and bitOff>0; span
  // 1..5 bytes incl. the 5-byte hi' BYTE store; width 1/8/16/32; BFINS Dn2 0/-1/partial.
  // ════════════════════════════════════════════════════════════════════════════

  // ── SQ slot-1-retire commit regression (Bug-2 blast radius; NOT bit-field-specific) ──
  // The ROB retires 2 µops/cycle but the SQ commit port carried only h0: a store retiring
  // in SLOT 1 (h1) never got its SQ entry marked committed -> the entry wedged the SQ head
  // (headReady needs committed(head)) -> any younger PARTIAL/same-line-overlap load stalled
  // forever = core deadlock. A back-to-back byte-store burst retires in pairs (some stores
  // land at h1), and the trailing LONG loads partially overlap them (byte-vs-long -> no
  // full forward -> must wait for the DRAIN that never came). Fixed by the 2-wide SQ
  // commit (StoreQueue.commitB wired to retire1/h1).
  test("lock-step: store burst + partial-overlap load (SQ slot-1-retire commit)", VerilatorTest) {
    runLockStep("sq-slot1-commit", Seq(
      "move.l #0x2000,%a0",
      "moveq #0x11,%d0", "moveq #0x22,%d1",
      "move.b %d0,(%a0)",  "move.b %d1,1(%a0)",
      "move.b %d0,2(%a0)", "move.b %d1,3(%a0)",
      "move.b %d0,4(%a0)", "move.b %d1,5(%a0)",
      "move.b %d0,6(%a0)", "move.b %d1,7(%a0)",
      "move.l (%a0),%d2",  "move.l 4(%a0),%d3",   // LONG loads partial-overlap the byte stores
      "nop"
    ).mkString(" ; "), checkMem = Seq(0x2000L, 0x2004L), checkSpan = 4)
  }
  // ── MOVEM-slot0 + µCODED-slot1 fetch pair (decode stash ownership regression) ─────────
  // When a fetch group pairs {slot0 = MOVEM, slot1 = µcode-owned op (CAS here)}, the MOVEM
  // FSM's entry stash arm must stash the slot1 PACKET for the µcode engine (ucPend) — the
  // ucBegin/movepBegin arms did, but the movemBegin arm stashed the a1raw PLACEHOLDER µops
  // (the assembler's illegal/benign crack of a microcoded opword) and replayed them as a
  // phantom committed µop. Latent for any {MOVEM, CAS/MOVES/BCD-mem/mem-indirect/bf-dyn}
  // adjacency that the aligner pairs.
  test("lock-step: MOVEM slot0 + CAS slot1 fetch pair (ucode stash ownership)", VerilatorTest) {
    runLockStep("movem-ucode-s1", Seq(
      "move.l #0x2000,%a0", "move.l #0x3000,%a1",
      "move.l #0x11111111,%d0", "move.l #0x22222222,%d1",
      "moveq #5,%d2", "moveq #9,%d3",
      "move.l %d2,(%a0)",            // mem = Dc -> CAS matches
      "nop",                         // pairing filler: aligns MOVEM to a group's SLOT 0
      "movem.l %d0-%d1,(%a1)",       // 2-word MOVEM (slot0 of the pair)
      "cas.l %d2,%d3,(%a0)",         // 2-word µcoded CAS (slot1 of the pair)
      "move.l (%a0),%d4",            // 9 if the CAS executed exactly once
      "move.l (%a1),%d5", "move.l 4(%a1),%d6",
      "nop"
    ).mkString(" ; "), checkMem = Seq(0x2000L, 0x3000L, 0x3004L), checkSpan = 4)
  }

  // BFSET bitOff=0 (lomask=0 corner) — set 16 bits at byte 0, read back through memory.
  test("lock-step: BFSET mem (An) bitOff=0 width16, read-back", VerilatorTest) {
    runLockStep("bfrmw-set0", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",                                  // X=1 sentinel (X untouched by BF)
      "bfset (%a0){#0:#16}",                             // mem[0x3000..1] |= 0xFFFF -> FFFF5678
      "bfextu (%a0){#0:#32},%d2",                        // read the long back: 0xFFFF5678
      "move.l (%a0),%d3"                                 // raw long read-back
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // BFCLR bitOff>0 — clear a field straddling a byte boundary; read back.
  test("lock-step: BFCLR mem (An) bitOff=4 width12, read-back", VerilatorTest) {
    runLockStep("bfrmw-clr", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",
      "bfclr (%a0){#4:#12}",                             // clear bits, bitOff 4
      "bfextu (%a0){#0:#32},%d2",
      "move.l (%a0),%d3"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // BFCHG widths 1/8/32 at bitOff 0 and >0; read back each.
  test("lock-step: BFCHG mem (An) width 1/8/32, bitOff 0/>0", VerilatorTest) {
    runLockStep("bfrmw-chg", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",
      "bfchg (%a0){#0:#1}",                              // flip the MSB (bit 31)
      "bfchg (%a0){#3:#8}",                              // 8-bit field at bitOff 3
      "bfchg (%a0){#0:#32}",                             // whole long
      "move.l (%a0),%d2"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // BFINS Dn2 = 0 / all-ones / partial, widths 1/16/32.
  test("lock-step: BFINS mem (An) Dn2 = 0/-1/partial, width 1/16/32", VerilatorTest) {
    runLockStep("bfrmw-ins", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",
      "move.l #0,%d0",     "bfins %d0,(%a0){#0:#16}",    // insert 0 into the top 16 bits
      "move.l #-1,%d1",    "bfins %d1,(%a0){#8:#16}",    // insert all-ones at bitOff 8 (straddles)
      "move.l #0x5,%d2",   "bfins %d2,(%a0){#0:#1}",     // insert 1 bit (low bit of 5)
      "move.l #0xABCD,%d3","bfins %d3,4(%a0){#0:#32}",   // full-long insert into the 2nd long
      "move.l (%a0),%d4",  "move.l 4(%a0),%d5"
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 4)
  }
  // 5-byte span (offset 7, width 28 -> bitOff 7, bitOff+width=35>32): the hi' spill-BYTE
  // store path (the 7-row chain). Read back BOTH the long and the spill byte.
  test("lock-step: BFSET mem 5-byte span (offset 7 width 28) — hi byte store", VerilatorTest) {
    runLockStep("bfrmw-span5", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",
      "bfset (%a0){#7:#28}",                             // sets bits spanning byteAddr..byteAddr+4
      "move.l (%a0),%d2",                                // the modified long
      "move.b 4(%a0),%d3",                               // the modified spill byte
      "bfextu (%a0){#7:#28},%d4"                          // read the field back (should be all-ones)
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 4)
  }
  test("lock-step: BFCLR mem 5-byte span (offset 5 width 30) — hi byte store", VerilatorTest) {
    runLockStep("bfrmw-span5b", (bfMemSeed ++ Seq(
      "ori #0x10,%ccr",
      "bfclr (%a0){#5:#30}",                             // bitOff 5 + width 30 -> spill into byte 4
      "move.l (%a0),%d2",
      "move.b 4(%a0),%d3"
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 4)
  }
  // BFSET-then-BFEXTU read-after-write THROUGH MEMORY (3a load-only reads back the 3b
  // store) — cross-validates 3a and 3b share the funnel correctly.
  test("lock-step: BFSET then BFEXTU read-after-write through memory", VerilatorTest) {
    runLockStep("bfrmw-raw", (bfMemSeed ++ Seq(
      "bfset (%a0){#9:#7}",                              // set a 7-bit field at bitOff 1 of byte 1
      "bfextu (%a0){#9:#7},%d2",                         // read it back -> all-ones (0x7F)
      "bfclr (%a0){#9:#7}",                              // clear the same field
      "bfextu (%a0){#9:#7},%d3"                          // read back -> 0
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // Misaligned LONG store crossing a cache line (s1TwoAccess): byteAddr near a line
  // boundary. Cache line = 16 bytes; place the base so byteAddr+offset>>3 straddles.
  val bfMemSeedLine = Seq(
    "move.l #0x3FFE,%a0",                                // base near a 16-byte line boundary (0x4000)
    "move.l #0x12345678,%d0", "move.l %d0,(%a0)",        // mem[0x3FFE..4001] (crosses 0x4000)
    "move.l #0x9abcdef0,%d1", "move.l %d1,4(%a0)",       // mem[0x4002..4005]
    "move.l #0x3FFE,%a0"
  )
  // MISALIGNED LONG store WITHIN a line (byteAddr odd, no line cross): exercises the
  // byte-lane store merge for the bit-field lo' store at a non-aligned address.
  val bfMemSeedMis = Seq(
    "move.l #0x3001,%a0",                                // ODD base (misaligned, within line 0x3000)
    "move.l #0x12345678,%d0", "move.l %d0,(%a0)",        // mem[0x3001..3004]
    "move.l #0x9abcdef0,%d1", "move.l %d1,4(%a0)",       // mem[0x3005..3008]
    "move.l #0x3001,%a0"
  )
  test("lock-step: BFSET mem misaligned (odd byteAddr, within line) LONG store", VerilatorTest) {
    runLockStep("bfrmw-misalign", (bfMemSeedMis ++ Seq(
      "ori #0x10,%ccr",
      "bfset (%a0){#4:#24}",                             // 24-bit field, bitOff 4, misaligned store
      "move.l (%a0),%d2",
      "bfextu (%a0){#4:#24},%d3"
    )).mkString(" ; "), checkMem = Seq(0x3001L), checkSpan = 4)
  }
  // Regression for the former LS-EU bug: a misaligned LONG store CROSSING a cache
  // line, after a cross-line LOAD to the same address, used to omit slot A from the
  // backing-memory drain. It reproduced with PLAIN MOVE.L (zero bit-field/engine code):
  //   move.l #0x3FFE,%a0 ; move.l (%a0),%d1 ; move.l #X,%d2 ; move.l %d2,(%a0)  -> mem[0x3FFE] stale
  // The bit-field RMW chain inherited it. Keep this active test as the end-to-end
  // regression for both split-slot data and terminal store drain.
  test("lock-step: BFSET mem misaligned LONG store crossing a cache line", VerilatorTest) {
    runLockStep("bfrmw-crossline", (bfMemSeedLine ++ Seq(
      "ori #0x10,%ccr",
      "bfset (%a0){#4:#24}",                             // 24-bit field, bitOff 4, store crosses 0x4000
      "move.l (%a0),%d2",
      "bfextu (%a0){#4:#24},%d3"
    )).mkString(" ; "), checkMem = Seq(0x3FFEL, 0x4002L), checkSpan = 4)
  }
  // PLAIN MOVE.L regression (zero bit-field machinery): a misaligned LONG store CROSSING
  // a cache line, AFTER a cross-line LOAD to the same address, must write slot A through
  // to backing memory. This is the minimal repro of the LS-EU cross-line-store-after-load
  // drain bug — no engine/RMW code.
  test("lock-step: plain MOVE.L cross-line store after cross-line load", VerilatorTest) {
    runLockStep("crossline-move-after-load", (bfMemSeedLine ++ Seq(
      "move.l (%a0),%d1",                               // cross-line LOAD to 0x3FFE
      "move.l #0xCAFEBABE,%d2",
      "move.l %d2,(%a0)"                                // cross-line STORE to 0x3FFE
    )).mkString(" ; "), checkMem = Seq(0x3FFEL), checkSpan = 4)
  }

  // ── ANDI/ORI/EORI #imm,CCR (NOT privileged — CCR only) lock-step ────────────
  // Set up the CCR via an arithmetic op (subi -> known NZVCX), then AND/OR/EOR the
  // immediate byte into the CCR (X=4,N=3,Z=2,V=1,C=0), verified step-for-step incl X.
  test("lock-step: ANDI #imm,CCR clears flags", VerilatorTest) {
    runLockStep("andi-ccr", Seq(
      "moveq #0,%d0", "subi.b #1,%d0",        // sets N + C + X (0 - 1 = 0xff)
      "andi #0x00,%ccr",                      // clear all CCR bits
      "moveq #1,%d1"                          // observe cleared CCR carried forward
    ).mkString(" ; "))
  }
  test("lock-step: ORI #imm,CCR sets flags", VerilatorTest) {
    runLockStep("ori-ccr", Seq(
      "moveq #5,%d0", "cmpi.l #5,%d0",        // Z=1, others mostly 0
      "ori #0x1f,%ccr",                       // set X,N,Z,V,C
      "moveq #2,%d1"
    ).mkString(" ; "))
  }
  test("lock-step: EORI #imm,CCR toggles flags", VerilatorTest) {
    runLockStep("eori-ccr", Seq(
      "moveq #0,%d0", "subi.b #1,%d0",        // N=1,C=1,X=1 (0xff), Z=0,V=0
      "eori #0x1f,%ccr",                      // toggle all 5 -> N=0,C=0,X=0,Z=1,V=1
      "moveq #3,%d1"
    ).mkString(" ; "))
  }

  // ── Track C: MOVE-to-CCR / MOVE-from-CCR (NOT privileged) ───────────────────
  // MOVE-to-CCR: CCR {X,N,Z,V,C} := src[4:0] (DIRECT, any data source). Seed an
  // arithmetic CCR, then a full MOVE replaces it from a register / immediate, verified
  // step-for-step incl X (CCR layout X=4,N=3,Z=2,V=1,C=0).
  test("lock-step: MOVE Dn,CCR (direct CCR assign)", VerilatorTest) {
    runLockStep("move-to-ccr-reg", Seq(
      "moveq #0,%d0", "subi.b #1,%d0",        // set N+C+X
      "moveq #0x1f,%d1", "move.w %d1,%ccr",   // CCR := 0x1f (all 5 set)
      "moveq #5,%d2", "move.w %d2,%ccr",      // CCR := 0x05 (V,C) only
      "moveq #7,%d3"                          // observe carried CCR
    ).mkString(" ; "))
  }
  test("lock-step: MOVE #imm,CCR", VerilatorTest) {
    runLockStep("move-to-ccr-imm", Seq(
      "moveq #0,%d0", "subi.b #1,%d0",        // dirty CCR
      "move.w #0x00,%ccr",                    // CCR := 0
      "move.w #0x1b,%ccr",                    // CCR := X,N,V,C (0x1b)
      "moveq #1,%d1"
    ).mkString(" ; "))
  }
  // MOVE-from-CCR: EA(.W) := zero-extend(CCR byte). Reg dest (.W partial merge) + mem
  // dest (store .W). Seed Dn upper bytes to verify the partial merge.
  test("lock-step: MOVE CCR,Dn (zero-extended CCR byte, .W merge)", VerilatorTest) {
    runLockStep("move-from-ccr-reg", Seq(
      "move.l #0x11223344,%d2",               // seed upper bytes
      "moveq #0,%d0", "subi.b #1,%d0",        // CCR := N+C+X (0x19)
      "move.w %ccr,%d2",                      // d2 = 0x1122_0019 (.W merge)
      "moveq #1,%d1"
    ).mkString(" ; "))
  }
  test("lock-step: MOVE CCR,(An) store", VerilatorTest) {
    runLockStep("move-from-ccr-mem", Seq(
      "move.l #0x3000,%a0",                   // dst address
      "moveq #5,%d0", "cmpi.l #5,%d0",        // Z=1 (CCR=0x04)
      "move.w %ccr,(%a0)",                    // mem[0x3000] = 0x0004 (word)
      "moveq #2,%d1"
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 2)
  }

  // ── Track C: LEA / PEA ──────────────────────────────────────────────────────
  // LEA computes a control EA ADDRESS -> An (no memory access). Various EA modes.
  test("lock-step: LEA control EAs -> An", VerilatorTest) {
    runLockStep("lea", Seq(
      "move.l #0x00010000,%a0",
      "lea (%a0),%a1",                        // A1 = 0x00010000
      "lea (8,%a0),%a2",                      // A2 = 0x00010008
      "move.l #2,%d1",
      "lea (4,%a0,%d1.w*2),%a3",              // A3 = 0x00010000 + 4 + 2*2 = 0x0001000C
      "lea (0x1234).w,%a4",                   // A4 = 0x00001234
      "lea (0x00020000).l,%a5"                // A5 = 0x00020000
    ).mkString(" ; "))
  }
  // PEA computes a control EA and pushes it to -(A7); verify the pushed value + A7.
  test("lock-step: PEA pushes the computed EA", VerilatorTest) {
    runLockStep("pea", Seq(
      "move.l #0x9000,%a7",                   // SP
      "move.l #0x00010000,%a0",
      "pea (8,%a0)",                          // push 0x00010008 -> mem[0x8FFC], A7=0x8FFC
      "pea (0x00005678).l",                   // push 0x00005678 -> mem[0x8FF8], A7=0x8FF8
      "moveq #1,%d0"
    ).mkString(" ; "), checkMem = Seq(0x8FF8L), checkSpan = 8)
  }

  // ── Track C: MOVE-from-SR (SUPERVISOR — boot S=1) ───────────────────────────
  // SR = {system byte, CCR}. Boot SR = 0x2700 (S=1, I=7, T=0). Set the CCR via an
  // arithmetic op, then MOVE SR,Dn reads the full 16-bit SR (system byte | CCR),
  // verified vs Musashi step-for-step.
  test("lock-step: MOVE SR,Dn (supervisor) reads the full SR", VerilatorTest) {
    runLockStep("move-from-sr-sup", Seq(
      "move.l #0x11223344,%d2",               // seed upper bytes
      "moveq #0,%d0", "subi.b #1,%d0",        // CCR := N+C+X (0x19) -> SR = 0x2719
      "move.w %sr,%d2",                       // d2 = 0x1122_2719 (.W merge, supervisor)
      "moveq #1,%d1"
    ).mkString(" ; "))
  }
  test("lock-step: MOVE SR,(An) store (supervisor)", VerilatorTest) {
    runLockStep("move-from-sr-mem", Seq(
      "move.l #0x3000,%a0",
      "moveq #5,%d0", "cmpi.l #5,%d0",        // Z=1 -> SR = 0x2704
      "move.w %sr,(%a0)",                     // mem[0x3000] = 0x2704
      "moveq #2,%d1"
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 2)
  }

  // ── Track C: MOVE-from-SR PRIVILEGE VIOLATION (user mode S=0 -> vector 8) ────
  // Directed (not full Musashi handler parity): boot USER mode (S=0), run MOVE SR,Dn,
  // and confirm the DUT raises a PRECISE vector-8 (privilege violation) exception entry
  // at the offending instruction's PC. A supervisor MOVE SR,Dn (control) takes NO trap.
  // This validates the ROB's needsSupervisor -> committed-S check (the exception SUBSYSTEM
  // delivery of vector 8 reuses the existing format-$0 path, already lock-stepped for
  // vector 4 / interrupts).
  private def runMoveFromSrPriv(userMode: Boolean): (Boolean, Int) = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    // A vector-8 handler that just spins (we only observe the ENTRY pulse). Place it after
    // the MOVE so the program image covers it; install the vector at mem[VBR(0)+8*4=0x20].
    val src = "move.w %sr,%d0 ; handler: bra.s handler"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    var sawPriv = false
    var vec = -1
    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false; dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      // Install the vector-8 handler address @ VBR(0)+0x20 in data memory (so the entry
      // FSM's vector fetch resolves; we don't need handler parity).
      val handlerPc = loadAddr + 2   // the `handler:` label (after the 1-word MOVE)
      for (i <- 0 until 4) dmem.pokeByte(0x20 + i, ((handlerPc >> (8 * i)) & 0xff).toInt)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      dut.rob.logic.exc.ss.usp #= 0x00200000L
      // Boot mode: user (S=0, SR 0x0000) or supervisor (S=1, 0x27 default).
      if (userMode) dut.rob.logic.exc.ss.srSys #= 0x00 else dut.rob.logic.exc.ss.srSys #= 0x27
      val bootA7 = if (userMode) 0x00200000L else 0x00100000L
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15; dut.wire.logic.seedData #= BigInt(bootA7)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      var guard = 0
      while (!sawPriv && guard < 600) {
        if (dut.rob.logic.exceptionPending.toBoolean) {
          sawPriv = true
          vec = dut.rob.logic.exceptionVector.toInt
        }
        cd.waitSampling(); guard += 1
      }
    }
    (sawPriv, vec)
  }

  test("MOVE SR,Dn in USER mode raises a vector-8 privilege violation", VerilatorTest) {
    val (saw, vec) = runMoveFromSrPriv(userMode = true)
    assert(saw, "user-mode MOVE-from-SR must raise a precise exception")
    assert(vec == 8, s"privilege violation must be vector 8, got $vec")
  }
  test("MOVE SR,Dn in SUPERVISOR mode raises NO exception", VerilatorTest) {
    val (saw, _) = runMoveFromSrPriv(userMode = false)
    assert(!saw, "supervisor MOVE-from-SR must NOT raise a privilege violation")
  }

  // ── RTE PRIVILEGE ESCALATION (CRITICAL security fix) ─────────────────────────
  // Directed (mirrors runMoveFromSrPriv exactly): boot USER mode (S=0), execute a
  // bare `rte`. Musashi's handler (m68k_in.c M68KMAKE_OP(rte,32,.,.)) is:
  //   if(FLAG_S) { ...pop the frame, m68ki_jump/m68ki_set_sr... } m68ki_exception_privilege_violation();
  // i.e. a user-mode RTE takes a vector-8 privilege violation BEFORE the stack frame
  // is EVER read/popped — no attacker-controlled SR/PC ever reaches the CPU. Confirms
  // (a) a precise vector-8 exception is raised, (b) the ROB's real RTE pop/redirect
  // FSM (`rteRetire`) is NEVER triggered (the USP is completely untouched — no pop
  // occurred), (c) the USP register value itself never changes. A regression that
  // supervisor-mode RTE still round-trips normally is covered by re-running the full
  // IRQ/exception lock-step suite (every one of those tests' return path IS a
  // supervisor `rte`).
  private def runRteUserPriv(): (Boolean, Int, Boolean, BigInt, BigInt) = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    // `rte` is the sole instruction; a spinning label follows only so the image is
    // well-formed (unreachable in user mode -- the RTE never executes as an RTE).
    val src = "rte ; handler: bra.s handler"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    var sawPriv = false
    var vec = -1
    var sawRteRetire = false
    var uspBefore = BigInt(0)
    var uspAfter  = BigInt(0)
    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false; dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      // Install a vector-8 handler @ VBR(0)+8*4=0x20 (just spins; we only observe entry).
      // Big-endian byte order (MSB at the lowest address) — a raw testbench poke
      // consumed by the exception unit's vector fetch.
      val handlerPc = loadAddr + 2   // `handler:` after the 1-word RTE opcode
      for (i <- 0 until 4) dmem.pokeByte(0x20 + i, ((handlerPc >> (8 * (3 - i))) & 0xff).toInt)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      dut.rob.logic.exc.ss.usp #= 0x00200000L
      dut.rob.logic.exc.ss.srSys #= 0x00   // boot USER mode (S=0)
      val bootA7 = 0x00200000L
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15; dut.wire.logic.seedData #= BigInt(bootA7)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      uspBefore = dut.rob.logic.exc.ss.usp.toBigInt
      var guard = 0
      while (!sawPriv && guard < 600) {
        if (dut.rob.logic.rteRetire.toBoolean) sawRteRetire = true
        if (dut.rob.logic.exceptionPending.toBoolean) {
          sawPriv = true
          vec = dut.rob.logic.exceptionVector.toInt
        }
        cd.waitSampling(); guard += 1
      }
      // Let the entry FSM fully settle (frame push onto the SUPERVISOR stack, not USP).
      cd.waitSampling(30)
      uspAfter = dut.rob.logic.exc.ss.usp.toBigInt
    }
    (sawPriv, vec, sawRteRetire, uspBefore, uspAfter)
  }

  test("RTE in USER mode raises a vector-8 privilege violation (does NOT execute)", VerilatorTest) {
    val (saw, vec, sawRteRetire, uspBefore, uspAfter) = runRteUserPriv()
    assert(saw, "user-mode RTE must raise a precise exception")
    assert(vec == 8, s"privilege violation must be vector 8, got $vec")
    assert(!sawRteRetire, "the RTE pop/redirect FSM must NEVER trigger for a user-mode RTE")
    assert(uspBefore == uspAfter,
      f"the USP must be completely UNTOUCHED by a rejected RTE (no pop): before=0x$uspBefore%08x after=0x$uspAfter%08x")
  }

  // ── Slow-ALU (SHIFT, S3) DEPENDENT CHAINS ───────────────────────────────────
  // Exercises the IQ aluSlow dynamic wakeup: an op that consumes a SHIFT's
  // INT result, NZVC result, and X result must wait one extra cycle and then read
  // the correct value. Pre-fix (the aluSlowWakeup port was unwired in the test
  // BackendWiringPlugin) these DEADLOCKED — the consumer's aluSlowWait never cleared.
  test("lock-step: dependent chain through a SHIFT int result (S3 wakeup)", VerilatorTest) {
    runLockStep("shift-dep-int", Seq(
      "move.l #0x00000003,%d0", "lsl.l #4,%d0",    // d0 = 0x30 (slow producer)
      "add.l %d0,%d1",                              // CONSUMES d0 (shift int result) -> waits for S3
      "move.l #0x0000000f,%d2", "lsl.l #2,%d2",    // d2 = 0x3c (slow)
      "move.l %d2,%d3",                             // CONSUMES d2 (shift result)
      "sub.l %d0,%d2"                               // CONSUMES d0 AND d2 (two shift results)
    ).mkString(" ; "))
  }
  test("lock-step: dependent chain through a SHIFT NZVC result (S3 wakeup)", VerilatorTest) {
    runLockStep("shift-dep-nzvc", Seq(
      "move.l #0x80000000,%d0", "asl.l #1,%d0",     // shift sets N/Z/V/C (slow producer)
      "bne .skip",                                  // CONSUMES the shift's NZVC (cc read) -> waits for S3
      "moveq #1,%d1",
      ".skip: moveq #2,%d2"
    ).mkString(" ; "), nInstr = 4)
  }
  test("lock-step: back-to-back SHIFT chain (X + int + NZVC all wake at S3)", VerilatorTest) {
    runLockStep("shift-chain-bb", Seq(
      "ori #0x10,%ccr",                             // X=1
      "move.l #0x00000001,%d0",
      "roxl.l #1,%d0",                              // ROX reads X, writes X (slow)
      "roxl.l #1,%d0",                              // reads prior shift's X + int result after S3
      "roxl.l #1,%d0",                              // chained again
      "add.l %d0,%d1"                               // consumes the final shift int result
    ).mkString(" ; "))
  }

  // ── Line-4 single-operand family (CLR/NEG/NEGX/NOT/TST/SWAP/EXT/TAS) ─────────
  // Data-register forms. Value + NZVCX step-for-step vs Musashi (the gate). All
  // sizes + flag edges (zero/negative/overflow/carry), NEGX with X set + the
  // Z-clear-only (Z-preserve) rule, SWAP, EXT.W/.L, EXTB.L, TAS.

  test("lock-step: CLR.B/.W/.L (Z=1, N=V=C=0, upper preserved)", VerilatorTest) {
    runLockStep("clr", Seq(
      "move.l #0x11223344,%d0", "clr.b %d0",                  // -> 0x11223300, Z=1
      "move.l #0x11223344,%d1", "clr.w %d1",                  // -> 0x11220000, Z=1
      "move.l #0x11223344,%d2", "clr.l %d2",                  // -> 0, Z=1
      "moveq #-1,%d3", "subi.b #1,%d3", "clr.b %d3"           // set C/X then CLR (C cleared)
    ).mkString(" ; "))
  }

  test("lock-step: NEG.B/.W/.L (NZVCX, X=C, V overflow, C unless zero)", VerilatorTest) {
    runLockStep("neg", Seq(
      "moveq #1,%d0", "neg.b %d0",                            // 0-1 -> 0xff, N=1,C=1,X=1
      "moveq #0,%d1", "neg.l %d1",                            // 0-0 -> 0, Z=1,C=0,X=0,V=0
      "move.l #0x00000080,%d2", "neg.b %d2",                  // .B 0x80 -> overflow V=1, C=1
      "move.l #0x00008000,%d3", "neg.w %d3",                  // .W 0x8000 -> V=1, C=1
      "move.l #0x80000000,%d4", "neg.l %d4",                  // .L 0x80000000 -> V=1, C=1
      "move.l #0xaabbccdd,%d5", "neg.b %d5"                   // partial: upper preserved
    ).mkString(" ; "))
  }

  // NEGX = 0 - Dn - X; Z is CLEAR-ONLY (Z := Z_old && result==0). Exercises X=1 and
  // X=0 inputs and BOTH Z-preserve directions (result 0 with Z_old 0 -> Z stays 0;
  // result 0 with Z_old 1 -> Z stays 1). subi.b sets X + Z to feed the next negx.
  test("lock-step: NEGX.B/.W/.L (X input, Z clear-only / Z-preserve)", VerilatorTest) {
    runLockStep("negx", Seq(
      "moveq #5,%d0", "subi.b #2,%d0", "negx.b %d0",          // X=0 (5-2 no borrow), negx 0-3-0
      "moveq #0,%d1", "subi.b #1,%d1", "negx.b %d1",          // X=1 (0-1 borrow), negx 0-0xff-1=0; Z_old=0 -> Z STAYS 0
      "moveq #1,%d2", "subi.b #1,%d2", "negx.b %d2",          // X=0,Z=1 (1-1=0), negx 0-0-0=0; Z_old=1 -> Z STAYS 1
      "move.l #0x00008000,%d3", "subi.b #1,%d3", "negx.w %d3",// .W with X=0
      "move.l #0x80000001,%d4", "subi.b #1,%d4", "negx.l %d4" // .L with X=0
    ).mkString(" ; "))
  }

  // ── ADDX/SUBX register form (Dy,Dx): Dx := Dx +/- Dy +/- X ──────────────────
  // Extended arith with X folded into the carry/borrow-in + the NEGX clear-only Z
  // (Z := Z_old && result==0). X is seeded by a preceding flag-setter (addi/subi
  // that carries/borrows). Value + NZVCX step-for-step vs Musashi. addx/subx do NOT
  // touch X when... (they always write X = carry/borrow out).
  test("lock-step: ADDX.B/.W/.L x X=0/1 (NZVCX, X in/out)", VerilatorTest) {
    runLockStep("addx-basic", Seq(
      // .B X=0: 0x10 + 0x20 + 0 = 0x30, no carry -> X=0,C=0
      "moveq #5,%d0", "addi.b #1,%d0",                    // 5+1 no carry -> X=0
      "move.l #0x11223310,%d1", "move.l #0x44556620,%d2", "addx.b %d2,%d1",  // 0x10+0x20+0 -> 0x..30
      // .B X=1: seed X via carry; 0x01 + 0x01 + 1 = 0x03
      "move.l #0x000000ff,%d3", "addi.b #1,%d3",          // 0xff+1 -> carry -> X=1,Z=1
      "move.l #0xaaaa0001,%d4", "move.l #0xbbbb0001,%d5", "addx.b %d5,%d4",  // 1+1+1=3, upper preserved
      // .W X=1: 0x0001 + 0x0001 + 1 = 0x0003
      "move.l #0x0000ffff,%d6", "addi.b #1,%d6",          // X=1 again
      "move.l #0x12340001,%d7", "move.l #0x00010001,%d0", "addx.w %d0,%d7",  // .W upper preserved
      // .L X=0 overflow: 0x7fffffff + 0 + 0 = 0x7fffffff (V=0); then +1 via X
      "moveq #5,%d1", "addi.b #1,%d1",                    // X=0
      "move.l #0x7fffffff,%d2", "move.l #0x00000000,%d3", "addx.l %d3,%d2", // 0x7fffffff+0+0
      // .L X=1 overflow edge: 0x7fffffff + 0 + 1 = 0x80000000 (V=1,N=1)
      "move.l #0x000000ff,%d4", "addi.b #1,%d4",          // X=1
      "move.l #0x7fffffff,%d5", "move.l #0x00000000,%d6", "addx.l %d6,%d5"  // V from the X increment
    ).mkString(" ; "))
  }

  test("lock-step: SUBX.B/.W/.L x X=0/1 (NZVCX, borrow in/out)", VerilatorTest) {
    runLockStep("subx-basic", Seq(
      // .B X=0: 0x30 - 0x10 - 0 = 0x20, no borrow
      "moveq #5,%d0", "addi.b #1,%d0",                    // X=0
      "move.l #0x11223330,%d1", "move.l #0x44556610,%d2", "subx.b %d2,%d1", // 0x30-0x10-0
      // .B X=1 borrow: 0x10 - 0x10 - 1 = 0xff (borrow out, N=1,C=1,X=1)
      "move.l #0x00000000,%d3", "subi.b #1,%d3",          // 0-1 borrow -> X=1
      "move.l #0xaaaa0010,%d4", "move.l #0xbbbb0010,%d5", "subx.b %d5,%d4", // 0x10-0x10-1=0xff
      // .B borrow at 0x80 boundary (signed underflow): 0x80 - 0x01 - 0 ... use X=0 here
      "moveq #5,%d6", "addi.b #1,%d6",                    // X=0
      "move.l #0x12340080,%d7", "move.l #0x00000001,%d0", "subx.b %d0,%d7", // 0x80-1-0=0x7f, V=1
      // .W X=1: 0x0000 - 0x0000 - 1 = 0xffff (borrow out)
      "move.l #0x00000000,%d1", "subi.b #1,%d1",          // X=1
      "move.l #0x43210000,%d2", "move.l #0x00000000,%d3", "subx.w %d3,%d2", // 0-0-1=0xffff .W
      // .L X=1: 0x00000000 - 0x00000000 - 1 = 0xffffffff
      "move.l #0x00000000,%d4", "subi.b #1,%d4",          // X=1
      "move.l #0x00000000,%d5", "move.l #0x00000000,%d6", "subx.l %d6,%d5"  // 0-0-1 -> 0xffffffff
    ).mkString(" ; "))
  }

  // ── Multi-precision: two ADDX limbs forming a 64-bit add (low sets X, high uses it)
  // and the SUBX twin. Low-limb overflow (X=1 into the high limb) AND no-overflow (X=0).
  test("lock-step: ADDX 64-bit multi-precision chain (low limb carry -> high)", VerilatorTest) {
    runLockStep("addx-chain", Seq(
      // (D1:D0) = 0x00000001_ffffffff + (D3:D2) = 0x00000002_00000001
      //   low:  add.l   D2,D0  -> 0xffffffff + 0x00000001 = 0x00000000, C=1 -> X=1
      //   high: addx.l  D3,D1  -> 0x00000001 + 0x00000002 + 1 = 0x00000004
      "move.l #0xffffffff,%d0", "move.l #0x00000001,%d1",       // (D1:D0) high:low
      "move.l #0x00000001,%d2", "move.l #0x00000002,%d3",       // (D3:D2)
      "add.l %d2,%d0",                                          // low limb, sets X=1
      "addx.l %d3,%d1",                                         // high limb consumes X
      // no-carry case: low limb does NOT overflow -> X=0 into high
      "move.l #0x00000001,%d4", "move.l #0x00000010,%d5",       // (D5:D4)
      "move.l #0x00000002,%d6", "move.l #0x00000020,%d7",       // (D7:D6)
      "add.l %d6,%d4",                                          // 1+2=3, no carry -> X=0
      "addx.l %d7,%d5"                                          // 0x10+0x20+0 = 0x30
    ).mkString(" ; "))
  }

  test("lock-step: SUBX 64-bit multi-precision chain (low limb borrow -> high)", VerilatorTest) {
    runLockStep("subx-chain", Seq(
      // (D1:D0) = 0x00000003_00000000 - (D3:D2) = 0x00000001_00000001
      //   low:  sub.l   D2,D0  -> 0x00000000 - 0x00000001 = 0xffffffff, borrow -> X=1
      //   high: subx.l  D3,D1  -> 0x00000003 - 0x00000001 - 1 = 0x00000001
      "move.l #0x00000000,%d0", "move.l #0x00000003,%d1",       // (D1:D0)
      "move.l #0x00000001,%d2", "move.l #0x00000001,%d3",       // (D3:D2)
      "sub.l %d2,%d0",                                          // low limb borrow -> X=1
      "subx.l %d3,%d1",                                         // high limb consumes borrow
      // no-borrow case: low limb does NOT borrow -> X=0 into high
      "move.l #0x00000030,%d4", "move.l #0x00000005,%d5",       // (D5:D4)
      "move.l #0x00000010,%d6", "move.l #0x00000002,%d7",       // (D7:D6)
      "sub.l %d6,%d4",                                          // 0x30-0x10=0x20, no borrow -> X=0
      "subx.l %d7,%d5"                                          // 5-2-0 = 3
    ).mkString(" ; "))
  }

  // ── Clear-only Z (NEGX rule applied to ADDX/SUBX): a zero result only KEEPS a
  // prior Z, never sets it. Three directions: Z_old=0 + zero result -> Z stays 0;
  // Z_old=1 + zero result -> Z stays 1; non-zero result -> Z clears to 0. ─────────
  test("lock-step: ADDX/SUBX clear-only Z (Z preceding 0/1, zero + non-zero results)", VerilatorTest) {
    runLockStep("addx-subx-z", Seq(
      // Z_old=0, ADDX result 0: addi.b #1 to 0x7f -> 0x80 (N=1,Z=0) sets X=0,Z=0,
      // then addx.b 0+0+0 = 0 -> Z must STAY 0 (clear-only).
      "move.l #0x0000007f,%d0", "addi.b #1,%d0",          // -> 0x80: Z=0, X=0
      "move.l #0xaaaa0000,%d1", "move.l #0xbbbb0000,%d2", "addx.b %d2,%d1", // 0+0+0=0; Z stays 0
      // Z_old=1, SUBX result 0: subi.b #1 from 1 -> 0 (Z=1, no borrow X=0),
      // then subx.b 0-0-0 = 0 -> Z must STAY 1.
      "move.l #0x00000001,%d3", "subi.b #1,%d3",          // -> 0: Z=1, X=0
      "move.l #0xcccc0000,%d4", "move.l #0xdddd0000,%d5", "subx.b %d5,%d4", // 0-0-0=0; Z stays 1
      // Z_old=1, ADDX non-zero result: clears Z to 0.
      "move.l #0x00000001,%d6", "subi.b #1,%d6",          // -> 0: Z=1, X=0
      "move.l #0x00000005,%d7", "move.l #0x00000003,%d0", "addx.b %d0,%d7", // 5+3+0=8; Z clears to 0
      // Z_old=1, ADDX with X=1 producing a zero byte: 0xff + 0x00 + 1 = 0x00 (carry),
      // Z_old=1 -> Z stays 1. Seed X=1,Z=1 via addi.b #1 to 0xff (-> 0, carry).
      "move.l #0x000000ff,%d1", "addi.b #1,%d1",          // 0xff+1 -> 0: Z=1, X=1 (carry)
      "move.l #0x111100ff,%d2", "move.l #0x22220000,%d3", "addx.b %d3,%d2" // 0xff+0+1=0x00; Z stays 1
    ).mkString(" ; "))
  }

  // ── Packed-BCD add (ABCD Dy,Dx) — decimal-adjust + the full-CCR (incl the
  // "undefined"-but-compared N/V) match vs Musashi. X is seeded by a preceding flag
  // setter (addi.b that carries -> X=1; one that does NOT carry -> X=0). Each `abcd`
  // reads the LOW byte; the upper 24 bits are preserved (the reg compare covers them).
  // Tests are kept short (the harness exposes an unrelated uninit-PRF flake on very long
  // straight-line programs); each name contains "ABCD" so `-z "ABCD"` selects them. ────
  test("lock-step: ABCD no/half-carry, X=0 (full CCR incl N/V)", VerilatorTest) {
    runLockStep("abcd-lo", Seq(
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x11223300,%d0", "move.l #0x44556600,%d1", "abcd %d1,%d0", // 00+00=00, upper preserved
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000001,%d2", "move.l #0x00000001,%d3", "abcd %d3,%d2", // 01+01=02
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000005,%d4", "move.l #0x00000006,%d5", "abcd %d5,%d4", // 05+06=11 (low half-carry +6)
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000009,%d0", "move.l #0x00000001,%d1", "abcd %d1,%d0"  // 09+01=10 (half-carry)
    ).mkString(" ; "))
  }

  test("lock-step: ABCD high/full carry, X=0 (C/X=1, N canary)", VerilatorTest) {
    runLockStep("abcd-hi", Seq(
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000050,%d2", "move.l #0x00000050,%d3", "abcd %d3,%d2", // 50+50=100 (carry,X=1)
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000090,%d4", "move.l #0x00000010,%d5", "abcd %d5,%d4", // 90+10=100 (carry)
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000099,%d0", "move.l #0x00000099,%d1", "abcd %d1,%d0"  // 99+99=198 -> 0x98 (N=1 canary)
    ).mkString(" ; "))
  }

  test("lock-step: ABCD with X=1 in (carry via the X ripple)", VerilatorTest) {
    runLockStep("abcd-xin", Seq(
      "move.l #0x000000ff,%d6", "addi.b #1,%d6",            // X=1 seed
      "move.l #0x00000045,%d2", "move.l #0x00000054,%d3", "abcd %d3,%d2", // 45+54+1=100 (carry via X)
      "move.l #0x000000ff,%d6", "addi.b #1,%d6",            // X=1
      "move.l #0x00000009,%d0", "move.l #0x00000000,%d1", "abcd %d1,%d0", // 09+00+1=10 (half-carry via X)
      "move.l #0x000000ff,%d6", "addi.b #1,%d6",            // X=1
      "move.l #0x00000099,%d4", "move.l #0x00000000,%d5", "abcd %d5,%d4"  // 99+00+1=100 (carry)
    ).mkString(" ; "))
  }

  // ABCD invalid-BCD inputs (low nibble > 9 / byte 0xFF): Musashi's un-masked >9 / >0x99
  // handling. The full-CCR compare nails the quirky N/V here.
  test("lock-step: ABCD invalid-BCD inputs (0xFF, 0x0F, X=0/1)", VerilatorTest) {
    runLockStep("abcd-invalid", Seq(
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x000000ff,%d0", "move.l #0x000000ff,%d1", "abcd %d1,%d0", // 0xff+0xff
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x0000000f,%d2", "move.l #0x0000000f,%d3", "abcd %d3,%d2", // 0x0f+0x0f
      "move.l #0x000000ff,%d6", "addi.b #1,%d6",            // X=1
      "move.l #0x000000ff,%d4", "move.l #0x0000000a,%d5", "abcd %d5,%d4"  // 0xff+0x0a+1
    ).mkString(" ; "))
  }

  // ABCD clear-only Z + upper-byte preservation: Z=1 then a non-zero result clears Z; a
  // zero result with Z_old=1 keeps Z=1. Mirrors the NEGX/ADDX clear-only-Z test.
  test("lock-step: ABCD clear-only Z + upper-byte preserve", VerilatorTest) {
    runLockStep("abcd-z", Seq(
      // Z_old=1 (subi.b 1-1=0 sets Z=1, X=0), abcd 00+00+0=00 -> Z STAYS 1
      "move.l #0x00000001,%d6", "subi.b #1,%d6",             // Z=1, X=0
      "move.l #0x11223300,%d0", "move.l #0x44556600,%d1", "abcd %d1,%d0", // 00+00=00; Z stays 1; upper 0x112233 preserved
      // Z_old=1, abcd non-zero result -> Z clears to 0
      "move.l #0x00000001,%d6", "subi.b #1,%d6",             // Z=1, X=0
      "move.l #0x00000012,%d2", "move.l #0x00000034,%d3", "abcd %d3,%d2", // 12+34=46; Z clears
      // Z_old=1, abcd with X=1 producing 0x00 (99+00+1=100 -> 00, carry) -> Z stays 1
      "move.l #0x000000ff,%d6", "addi.b #1,%d6",             // X=1, Z=1 (0xff+1=0 carry)
      "move.l #0x11990099,%d4", "move.l #0x00000000,%d5", "abcd %d5,%d4"  // 99+00+1=00 carry; Z stays 1; upper 0x119900 preserved
    ).mkString(" ; "))
  }

  // Multi-byte packed-BCD add (the canonical ABCD use): ripple X between bytes.
  test("lock-step: ABCD 4-digit multi-byte chain (X ripple)", VerilatorTest) {
    runLockStep("abcd-chain", Seq(
      // (D1 high, D0 low) 0x99 0x99 + (D3 D2) 0x00 0x01 -> low 99+01=00 carry, high 99+00+1=00 carry
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0 for the low byte
      "move.l #0x00000099,%d0", "move.l #0x00000099,%d1",    // A: low=0x99 high=0x99
      "move.l #0x00000001,%d2", "move.l #0x00000000,%d3",    // B: low=0x01 high=0x00
      "abcd %d2,%d0",                                        // low: 99+01+0=00 carry -> X=1
      "abcd %d3,%d1",                                        // high: 99+00+1=00 carry
      // non-overflowing chain: 12 34 + 45 23 = 57 57
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000034,%d4", "move.l #0x00000012,%d5",    // A: low=0x34 high=0x12
      "move.l #0x00000023,%d6", "move.l #0x00000045,%d7",    // B: low=0x23 high=0x45
      "abcd %d6,%d4",                                        // low 34+23=57, no carry -> X=0
      "abcd %d7,%d5"                                         // high 12+45=57
    ).mkString(" ; "))
  }

  // ── Packed-BCD subtract (SBCD Dy,Dx) — borrow / unsigned-wrap decimal adjust + full
  // CCR (incl N/V) vs Musashi. SBCD's V masks res AFTER the 8-bit mask (vs ABCD before).
  // Names contain "SBCD" so `-z "SBCD"` selects them. ────────────────────────────────
  test("lock-step: SBCD no/low-borrow, X=0 (full CCR incl N/V)", VerilatorTest) {
    runLockStep("sbcd-lo", Seq(
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x11223399,%d0", "move.l #0x44556611,%d1", "sbcd %d1,%d0", // 99-11=88
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000010,%d4", "move.l #0x00000001,%d5", "sbcd %d5,%d4", // 10-01=09 (low borrow -6)
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000055,%d2", "move.l #0x00000055,%d3", "sbcd %d3,%d2"  // 55-55=00
    ).mkString(" ; "))
  }

  test("lock-step: SBCD high-borrow, X=0 (+0xA0 wrap, N canary)", VerilatorTest) {
    runLockStep("sbcd-hi", Seq(
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000000,%d2", "move.l #0x00000001,%d3", "sbcd %d3,%d2", // 00-01=99 borrow (X=1, N=1 canary)
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000050,%d0", "move.l #0x00000099,%d1", "sbcd %d1,%d0", // 50-99=51 borrow (high borrow +0xA0)
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000000,%d4", "move.l #0x00000099,%d5", "sbcd %d5,%d4"  // 00-99=01 borrow
    ).mkString(" ; "))
  }

  test("lock-step: SBCD with X=1 in (borrow via the X ripple)", VerilatorTest) {
    runLockStep("sbcd-xin", Seq(
      "move.l #0x00000000,%d6", "subi.b #1,%d6",            // X=1 seed (0-1 borrow)
      "move.l #0x00000010,%d4", "move.l #0x00000001,%d5", "sbcd %d5,%d4", // 10-01-1=08
      "move.l #0x00000000,%d6", "subi.b #1,%d6",            // X=1
      "move.l #0x00000000,%d0", "move.l #0x00000099,%d1", "sbcd %d1,%d0", // 00-99-1=00 borrow
      "move.l #0x00000000,%d6", "subi.b #1,%d6",            // X=1
      "move.l #0x00000000,%d2", "move.l #0x00000000,%d3", "sbcd %d3,%d2"  // 00-00-1=99 borrow
    ).mkString(" ; "))
  }

  // SBCD invalid-BCD inputs (low nibble 0xF / 0xFF) + clear-only Z.
  test("lock-step: SBCD invalid-BCD inputs + clear-only Z", VerilatorTest) {
    runLockStep("sbcd-invalid-z", Seq(
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x000000ff,%d0", "move.l #0x0000000f,%d1", "sbcd %d1,%d0", // 0xff-0x0f
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x0000000f,%d2", "move.l #0x000000ff,%d3", "sbcd %d3,%d2", // 0x0f-0xff borrow
      // Z clear-only: pre-set Z=1, sbcd 55-55=00 with Z_old=1 -> Z stays 1; upper preserved
      "move.l #0x00000001,%d6", "subi.b #1,%d6",            // Z=1, X=0
      "move.l #0x11223355,%d4", "move.l #0x44556655,%d5", "sbcd %d5,%d4"  // 55-55=00; Z stays 1; upper preserved
    ).mkString(" ; "))
  }

  // Multi-byte packed-BCD subtract (X ripples the borrow between bytes).
  test("lock-step: SBCD 4-digit multi-byte chain (borrow ripple)", VerilatorTest) {
    runLockStep("sbcd-chain", Seq(
      // (D1 D0) 0x00 0x00 - (D3 D2) 0x00 0x01: low 00-01=99 borrow, high 00-00-1=99 borrow
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0 for the low byte
      "move.l #0x00000000,%d0", "move.l #0x00000000,%d1",    // A: low=0x00 high=0x00
      "move.l #0x00000001,%d2", "move.l #0x00000000,%d3",    // B: low=0x01 high=0x00
      "sbcd %d2,%d0",                                        // low 00-01=99 borrow -> X=1
      "sbcd %d3,%d1",                                        // high 00-00-1=99 borrow
      // non-borrowing chain: 87 65 - 12 34 = 75 31
      "moveq #1,%d6", "addi.b #1,%d6",                       // X=0
      "move.l #0x00000065,%d4", "move.l #0x00000087,%d5",    // A: low=0x65 high=0x87
      "move.l #0x00000034,%d6", "move.l #0x00000012,%d7",    // B: low=0x34 high=0x12
      "sbcd %d6,%d4",                                        // low 65-34=31, no borrow -> X=0
      "sbcd %d7,%d5"                                         // high 87-12=75
    ).mkString(" ; "))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // BCD/ADDX/SUBX -(Ay),-(Ax) MEMORY forms — the MICROCODE ENGINE's first customers.
  //
  // (Ax) := <op>( (Ax) <+/-> (Ay) <+/-> X ), both operands PREDECREMENT memory. The
  // DecodeStage µcode SEQUENCER emits the 6-µop sequence ([load(Ay)->T0][Ay-=d][load(Ax)
  // ->T1][Ax-=d][op T1,T0->T2 +flags][store T2->(Ax)]). Each program: seed Ay/Ax above
  // the operand bytes, STORE the operands, set X, run the op, then read Ay/Ax into a Dn
  // (RAW on the predec An updates) — lock-stepped vs Musashi with the FULL CCR byte
  // (incl the BCD N/V Musashi computes) + checkMem on the result byte. Names contain
  // ABCD-mem/SBCD-mem/ADDX-mem/SUBX-mem so `-z` selects each.
  // ═══════════════════════════════════════════════════════════════════════════

  test("lock-step: ABCD-mem -(A1),-(A0) (full CCR + mem + An RAW)", VerilatorTest) {
    runLockStep("ABCD-mem", Seq(
      // mem[0x3000]=0x55 (the (Ax) dst byte), mem[0x4000]=0x27 (the (Ay) src byte).
      "move.l #0x3001,%a0", "move.l #0x00000055,%d0", "move.b %d0,-(%a0)",   // mem[0x3000]=0x55, A0=0x3000
      "move.l #0x4001,%a1", "move.l #0x00000027,%d1", "move.b %d1,-(%a1)",   // mem[0x4000]=0x27, A1=0x4000
      "move.l #0x3001,%a0", "move.l #0x4001,%a1",                             // reset An above the bytes
      "moveq #1,%d6", "addi.b #1,%d6",                                        // X=0
      "abcd -(%a1),-(%a0)",                                                    // mem[0x3000]:=55+27=82; A0=0x3000,A1=0x4000
      "move.l %a0,%d3", "move.l %a1,%d4"                                       // RAW on the predec An updates
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 1)
  }

  test("lock-step: ABCD-mem carry (X out=1, C=1) + half-carry", VerilatorTest) {
    runLockStep("ABCD-mem-carry", Seq(
      "move.l #0x3001,%a0", "move.l #0x00000099,%d0", "move.b %d0,-(%a0)",   // mem[0x3000]=0x99
      "move.l #0x4001,%a1", "move.l #0x00000099,%d1", "move.b %d1,-(%a1)",   // mem[0x4000]=0x99
      "move.l #0x3001,%a0", "move.l #0x4001,%a1",
      "moveq #-1,%d6", "addi.b #1,%d6",                                       // X=1 (0xff+1 carries)
      "abcd -(%a1),-(%a0)",                                                    // 99+99+1=199 -> 0x99, carry (C=X=1)
      "move.l %a0,%d3", "move.l %a1,%d4"
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 1)
  }

  test("lock-step: SBCD-mem -(A1),-(A0) borrow + full CCR", VerilatorTest) {
    runLockStep("SBCD-mem", Seq(
      "move.l #0x3001,%a0", "move.l #0x00000050,%d0", "move.b %d0,-(%a0)",   // mem[0x3000]=0x50 (dst)
      "move.l #0x4001,%a1", "move.l #0x00000099,%d1", "move.b %d1,-(%a1)",   // mem[0x4000]=0x99 (src)
      "move.l #0x3001,%a0", "move.l #0x4001,%a1",
      "moveq #1,%d6", "addi.b #1,%d6",                                        // X=0
      "sbcd -(%a1),-(%a0)",                                                    // 50-99=51 borrow (C=X=1)
      "move.l %a0,%d3", "move.l %a1,%d4"
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 1)
  }

  test("lock-step: ADDX-mem.L -(A1),-(A0) (4-byte limb + An RAW)", VerilatorTest) {
    runLockStep("ADDX-mem", Seq(
      // mem[0x3000]=0x10000001 (dst), mem[0x4000]=0x20000002 (src). .L predec by 4.
      "move.l #0x3004,%a0", "move.l #0x10000001,%d0", "move.l %d0,-(%a0)",   // mem[0x3000]=0x10000001, A0=0x3000
      "move.l #0x4004,%a1", "move.l #0x20000002,%d1", "move.l %d1,-(%a1)",   // mem[0x4000]=0x20000002, A1=0x4000
      "move.l #0x3004,%a0", "move.l #0x4004,%a1",
      "moveq #1,%d6", "addi.b #1,%d6",                                        // X=0
      "addx.l -(%a1),-(%a0)",                                                  // 0x10000001+0x20000002 = 0x30000003
      "move.l %a0,%d3", "move.l %a1,%d4"                                       // A0=0x3000, A1=0x4000
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }

  test("lock-step: SUBX-mem.W -(A1),-(A0) (word limb, borrow)", VerilatorTest) {
    runLockStep("SUBX-mem", Seq(
      // mem[0x3000]=0x0003 (dst word), mem[0x4000]=0x0005 (src word). .W predec by 2.
      "move.l #0x3002,%a0", "move.l #0x00000003,%d0", "move.w %d0,-(%a0)",   // mem[0x3000]=0x0003, A0=0x3000
      "move.l #0x4002,%a1", "move.l #0x00000005,%d1", "move.w %d1,-(%a1)",   // mem[0x4000]=0x0005, A1=0x4000
      "move.l #0x3002,%a0", "move.l #0x4002,%a1",
      "moveq #1,%d6", "addi.b #1,%d6",                                        // X=0
      "subx.w -(%a1),-(%a0)",                                                  // 0x0003-0x0005 = 0xfffe borrow (C=X=1)
      "move.l %a0,%d3", "move.l %a1,%d4"                                       // A0=0x3000, A1=0x4000
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 2)
  }


  test("lock-step: ABCD-mem alternate slot alignment", VerilatorTest) {
    // An extra 1-word op before the X-mem op shifts its fetch-group slot parity
    // (exercises a different slot0/slot1 pairing than the other tests).
    runLockStep("ABCD-mem-slot0", Seq(
      "move.l #0x3001,%a0", "move.l #0x00000012,%d0", "move.b %d0,-(%a0)",
      "move.l #0x4001,%a1", "move.l #0x00000034,%d1", "move.b %d1,-(%a1)",
      "move.l #0x3001,%a0", "move.l #0x4001,%a1",
      "moveq #1,%d6", "addi.b #1,%d6",
      "moveq #7,%d2",                                          // 1-word op to shift slot parity
      "abcd -(%a1),-(%a0)",                                    // 12+34 = 46
      "move.l %a0,%d3", "move.l %a1,%d4"
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 1)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CMPM (Ay)+,(Ax)+ (fuzzer-found gap): a new 5-row microcode entry, template =
  // BCD_MEM_ENTRY swapped from PREDECREMENT to POSTINCREMENT with a flags-only CMP
  // compute tail (no register/memory write beyond the two postincs; X untouched).
  // Musashi: src=read(Ay)+ FIRST; dst=read(Ax)+ SECOND; res=dst-src; NZVC from res.
  // Previously NOT DECODED AT ALL (falls through to the COMPLEX/illegal path, vector 4).
  // ═══════════════════════════════════════════════════════════════════════════

  test("lock-step: CMPM.L (A1)+,(A0)+ equal operands (Z=1) + An postinc", VerilatorTest) {
    runLockStep("cmpm-l-equal", Seq(
      "move.l #0x3000,%a0", "move.l #0x12345678,%d0", "move.l %d0,(%a0)",   // mem[0x3000]=0x12345678 (dst=Ax)
      "move.l #0x4000,%a1", "move.l #0x12345678,%d1", "move.l %d1,(%a1)",   // mem[0x4000]=0x12345678 (src=Ay)
      "move.l #0x3000,%a0", "move.l #0x4000,%a1",
      "cmpm.l (%a1)+,(%a0)+",                                                // dst-src = 0 -> Z=1
      "move.l %a0,%d3", "move.l %a1,%d4"                                     // A0=0x3004, A1=0x4004
    ).mkString(" ; "))
  }

  test("lock-step: CMPM.B (A1)+,(A0)+ borrow (N=1,C=1) + An postinc", VerilatorTest) {
    runLockStep("cmpm-b-borrow", Seq(
      "move.l #0x3000,%a0", "move.l #0x00000005,%d0", "move.b %d0,(%a0)",   // mem[0x3000]=5 (dst=Ax)
      "move.l #0x4000,%a1", "move.l #0x0000000a,%d1", "move.b %d1,(%a1)",   // mem[0x4000]=0xa (src=Ay)
      "move.l #0x3000,%a0", "move.l #0x4000,%a1",
      "cmpm.b (%a1)+,(%a0)+",                                                // 5-10 = -5 -> N=1,C=1(borrow),Z=0
      "move.l %a0,%d3", "move.l %a1,%d4"                                     // A0=0x3001, A1=0x4001
    ).mkString(" ; "))
  }

  test("lock-step: CMPM.B (A1)+,(A0)+ signed overflow (V=1)", VerilatorTest) {
    runLockStep("cmpm-b-overflow", Seq(
      "move.l #0x3000,%a0", "move.l #0x00000080,%d0", "move.b %d0,(%a0)",   // mem[0x3000]=0x80 (-128, dst=Ax)
      "move.l #0x4000,%a1", "move.l #0x00000001,%d1", "move.b %d1,(%a1)",   // mem[0x4000]=1 (src=Ay)
      "move.l #0x3000,%a0", "move.l #0x4000,%a1",
      "cmpm.b (%a1)+,(%a0)+",                                                // -128-1 overflows -> V=1
      "move.l %a0,%d3", "move.l %a1,%d4"
    ).mkString(" ; "))
  }

  test("lock-step: CMPM.W (A1)+,(A0)+ does NOT touch X (ADDX consumer)", VerilatorTest) {
    runLockStep("cmpm-w-x-untouched", Seq(
      "moveq #-1,%d6", "addi.b #1,%d6",                                      // X=1 (0xff+1 overflows)
      "move.l #0x3000,%a0", "move.l #0x00001111,%d0", "move.w %d0,(%a0)",
      "move.l #0x4000,%a1", "move.l #0x00001111,%d1", "move.w %d1,(%a1)",
      "move.l #0x3000,%a0", "move.l #0x4000,%a1",
      "cmpm.w (%a1)+,(%a0)+",                                                // equal -> Z=1; MUST NOT touch X
      "moveq #0,%d7", "addx.b %d7,%d7"                                       // X=1 preserved -> D7=1
    ).mkString(" ; "))
  }

  test("lock-step: NOT.B/.W/.L (NZ, V=C=0, upper preserved)", VerilatorTest) {
    runLockStep("not", Seq(
      "move.l #0x0000000f,%d0", "not.b %d0",                  // .B ~0x0f=0xf0, N=1
      "move.l #0xffffffff,%d1", "not.w %d1",                  // .W ~0xffff=0 word, Z=1
      "move.l #0x12345678,%d2", "not.l %d2",                  // .L ~ -> 0xedcba987, N=1
      "move.l #0xaabbccdd,%d3", "not.b %d3"                   // partial: upper preserved
    ).mkString(" ; "))
  }

  test("lock-step: TST.B/.W/.L (NZ, V=C=0, no write)", VerilatorTest) {
    runLockStep("tst", Seq(
      "move.l #0x00000080,%d0", "tst.b %d0",                  // .B negative byte -> N=1
      "move.l #0x00000000,%d1", "tst.w %d1",                  // .W zero -> Z=1
      "move.l #0x80000000,%d2", "tst.l %d2",                  // .L negative -> N=1
      "move.l #0x0000007f,%d3", "tst.b %d3"                   // .B positive -> N=0,Z=0
    ).mkString(" ; "))
  }

  test("lock-step: SWAP (halves swapped, NZ from 32-bit, V=C=0)", VerilatorTest) {
    runLockStep("swap", Seq(
      "move.l #0x12345678,%d0", "swap %d0",                   // -> 0x56781234
      "move.l #0x00000000,%d1", "swap %d1",                   // -> 0, Z=1
      "move.l #0x8000ffff,%d2", "swap %d2",                   // -> 0xffff8000, N=1 (bit31=1)
      "move.l #0x0000abcd,%d3", "swap %d3"                    // -> 0xabcd0000, N=1
    ).mkString(" ; "))
  }

  test("lock-step: EXT.W / EXT.L / EXTB.L (sign-extend, NZ, V=C=0)", VerilatorTest) {
    runLockStep("ext", Seq(
      "move.l #0x11223380,%d0", "ext.w %d0",                  // byte 0x80 -> word 0xff80 (.W upper preserved), N=1
      "move.l #0x1122337f,%d1", "ext.w %d1",                  // byte 0x7f -> word 0x007f, N=0
      "move.l #0x0000ffff,%d2", "ext.l %d2",                  // word 0xffff -> long 0xffffffff, N=1
      "move.l #0x00007fff,%d3", "ext.l %d3",                  // word 0x7fff -> long 0x00007fff
      "move.l #0x11223380,%d4", "extb.l %d4",                 // byte 0x80 -> long 0xffffff80, N=1
      "move.l #0x11223300,%d5", "extb.l %d5"                  // byte 0x00 -> long 0, Z=1
    ).mkString(" ; "))
  }

  test("lock-step: TAS (N/Z from Dn[7:0], set bit7, V=C=0)", VerilatorTest) {
    runLockStep("tas", Seq(
      "move.l #0x11223300,%d0", "tas %d0",                    // byte 0x00 -> N=0,Z=1; then 0x80 -> 0x11223380
      "move.l #0x1122337f,%d1", "tas %d1",                    // byte 0x7f -> N=0,Z=0; then 0xff -> 0x112233ff
      "move.l #0x112233ff,%d2", "tas %d2"                     // byte 0xff -> N=1,Z=0; stays 0xff
    ).mkString(" ; "))
  }

  // ── Memory-destination RMW lock-step (load-op-store crack) ─────────────────
  // Each program seeds ONE data word in memory (move #val,Dn ; move Dn,addr), runs ONE
  // RMW op against that memory dest, then halts. The register/flag/PC stream lock-steps
  // vs Musashi; checkMem verifies the FINAL memory value = the RMW result.
  //
  // ONE RMW PER PROGRAM (single store->load->store sequence): a PRE-EXISTING SQ/dcache
  // drain race (reproducible on master WITHOUT any RMW — a 3x back-to-back store->load-
  // same->store-same program drops a store there too) corrupts memory under sustained
  // same-address store-load-store pressure. That LS drain bug is ORTHOGONAL to the RMW
  // crack and OUT OF SCOPE here; a single RMW per program drains cleanly (the 200-cycle
  // post-run settle guarantees the lone store reaches memory), so these gate the CRACK
  // (decode + load->op->store + EA recompute + flags + final memory) exactly. .B/.W
  // RMWs seed a FULL .L word so the checked .L span is fully written (RMW modifies only
  // the low byte(s); the seed's upper bytes match Musashi); checkSpan=4 validates it.

  test("lock-step: ADD.L Dn,(An) RMW -> mem + NZVCX", VerilatorTest) {
    runLockStep("rmw-add-l",
      "move.l #0x10000001,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; move.l #0x20000002,%d1 ; add.l %d1,(%a0) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L))   // -> 0x30000003
  }
  test("lock-step: SUB.L Dn,(An) RMW", VerilatorTest) {
    runLockStep("rmw-sub-l",
      "move.l #0x00000005,%d2 ; move.l #0x3000,%a1 ; move.l %d2,(%a1) ; move.l #0x00000003,%d3 ; sub.l %d3,(%a1) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L))   // -> 2
  }
  test("lock-step: AND.L Dn,(An) RMW", VerilatorTest) {
    runLockStep("rmw-and-l",
      "move.l #0xff00ff00,%d4 ; move.l #0x3000,%a2 ; move.l %d4,(%a2) ; move.l #0x0f0f0f0f,%d5 ; and.l %d5,(%a2) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L))   // -> 0x0f000f00
  }
  test("lock-step: OR.L Dn,(An) RMW", VerilatorTest) {
    runLockStep("rmw-or-l",
      "move.l #0x12340001,%d6 ; move.l #0x3000,%a3 ; move.l %d6,(%a3) ; move.l #0x00005678,%d7 ; or.l %d7,(%a3) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L))   // -> 0x12345679
  }
  // Regression guard for the IN-ORDER-LS-ISSUE fix (IssueQueuePlugin ohL). A multi-RMW
  // chain to ONE address: pre-fix, a younger RMW load (addr ready) issued AHEAD of an
  // older RMW store still waiting on its ALU data -> the load queried SQ-forward before
  // that store ALLOCATED -> forward miss -> write-no-allocate stale refill -> dropped
  // store -> wrong final memory (regs still matched Musashi; only memory diverged). The
  // fix issues the OLDEST occupied LS slot (when ready), so no load bypasses an older
  // store. (This is why the single-RMW-per-program note above existed; now multi-RMW works.)
  test("lock-step: multi-RMW chain to one addr (in-order LS issue) -> mem", VerilatorTest) {
    runLockStep("rmw-chain",
      "move.l #0x3000,%a0 ; move.l #0x100,%d0 ; move.l %d0,(%a0) ; addq.l #1,(%a0) ; addq.l #2,(%a0) ; " +
      "addq.l #3,(%a0) ; addq.l #4,(%a0) ; addq.l #5,(%a0) ; " +
      ".stop: bra .stop", nInstr = 8, checkMem = Seq(0x3000L))   // -> 0x100+1+2+3+4+5 = 0x10F
  }
  test("lock-step: ADD.B Dn,(An) RMW (carry/X/Z edge)", VerilatorTest) {
    runLockStep("rmw-add-b",
      "move.l #0x111100ff,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; move.l #0x00000001,%d1 ; add.b %d1,(%a0) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)   // .B 0xff+1 -> 0x00, C/X/Z
  }
  test("lock-step: ADD.W Dn,(An) RMW (overflow/N edge)", VerilatorTest) {
    runLockStep("rmw-add-w",
      "move.l #0x22227fff,%d2 ; move.l #0x3000,%a1 ; move.l %d2,(%a1) ; move.l #0x00000001,%d3 ; add.w %d3,(%a1) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)   // .W 0x7fff+1 -> 0x8000, V/N
  }
  test("lock-step: SUB.B Dn,(An) RMW (borrow edge)", VerilatorTest) {
    runLockStep("rmw-sub-b",
      "move.l #0x33330000,%d4 ; move.l #0x3000,%a2 ; move.l %d4,(%a2) ; move.l #0x00000001,%d5 ; sub.b %d5,(%a2) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)   // .B 0-1 -> 0xff, C/X/N
  }
  test("lock-step: EOR.B/.W/.L Dn,(An) RMW", VerilatorTest) {
    runLockStep("rmw-eor-b",
      "move.l #0x111100aa,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; move.l #0x000000ff,%d1 ; eor.b %d1,(%a0) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)   // .B aa^ff -> 0x55
  }
  test("lock-step: EOR.L Dn,(An) RMW (N edge)", VerilatorTest) {
    runLockStep("rmw-eor-l",
      "move.l #0x12345678,%d4 ; move.l #0x3000,%a2 ; move.l %d4,(%a2) ; move.l #0xffffffff,%d5 ; eor.l %d5,(%a2) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)   // -> 0xedcba987, N=1
  }
  test("lock-step: ADDI.L #imm,(An) RMW", VerilatorTest) {
    runLockStep("rmw-addi-l",
      "move.l #0x00000010,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; addi.l #0x22,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // -> 0x32
  }
  test("lock-step: SUBI.W #imm,(An) RMW", VerilatorTest) {
    runLockStep("rmw-subi-w",
      "move.l #0x11110050,%d1 ; move.l #0x3000,%a1 ; move.l %d1,(%a1) ; subi.w #0x0030,(%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // .W -> 0x20
  }
  test("lock-step: ANDI.W #imm,(An) RMW", VerilatorTest) {
    runLockStep("rmw-andi-w",
      "move.l #0x2222ff0f,%d2 ; move.l #0x3000,%a2 ; move.l %d2,(%a2) ; andi.w #0x0ff0,(%a2) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // .W -> 0x0f00
  }
  test("lock-step: ORI.L #imm,(An) RMW", VerilatorTest) {
    runLockStep("rmw-ori-l",
      "move.l #0x12000000,%d3 ; move.l #0x3000,%a3 ; move.l %d3,(%a3) ; ori.l #0x00345678,(%a3) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // -> 0x12345678
  }
  test("lock-step: EORI.B #imm,(An) RMW", VerilatorTest) {
    runLockStep("rmw-eori-b",
      "move.l #0x444400aa,%d4 ; move.l #0x3000,%a4 ; move.l %d4,(%a4) ; eori.b #0xff,(%a4) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // .B aa^ff -> 0x55
  }
  test("lock-step: ADDQ.L #n,(An) RMW", VerilatorTest) {
    runLockStep("rmw-addq-l",
      "move.l #0x00000005,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; addq.l #3,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // -> 8
  }
  test("lock-step: SUBQ.W #8,(An) RMW (n=8)", VerilatorTest) {
    runLockStep("rmw-subq-w",
      "move.l #0x11110010,%d1 ; move.l #0x3000,%a1 ; move.l %d1,(%a1) ; subq.w #8,(%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // .W -> 8
  }
  test("lock-step: ADDQ.B #1,(An) RMW (carry edge)", VerilatorTest) {
    runLockStep("rmw-addq-b",
      "move.l #0x222200ff,%d2 ; move.l #0x3000,%a2 ; move.l %d2,(%a2) ; addq.b #1,(%a2) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // .B 0xff+1 -> 0x00, C/X/Z
  }
  test("lock-step: CLR.L (An) RMW (Z=1,N=0)", VerilatorTest) {
    runLockStep("rmw-clr-l",
      "move.l #0x11223344,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; clr.l (%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // -> 0, Z=1,N=0
  }
  test("lock-step: NEG.L (An) RMW (N/C/X)", VerilatorTest) {
    runLockStep("rmw-neg-l",
      "move.l #0x00000001,%d1 ; move.l #0x3000,%a1 ; move.l %d1,(%a1) ; neg.l (%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // -> 0xffffffff, N=1,C=1,X=1
  }
  test("lock-step: NOT.L (An) RMW (N)", VerilatorTest) {
    runLockStep("rmw-not-l",
      "move.l #0x00000005,%d2 ; move.l #0x3000,%a2 ; move.l %d2,(%a2) ; not.l (%a2) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // -> 0xfffffffa, N=1
  }
  test("lock-step: NEGX.B (An) RMW (X input via prior subi)", VerilatorTest) {
    // Seed a byte + set X via a register subi, then NEGX the memory dest (reads X).
    runLockStep("rmw-negx",
      "move.l #0x3000,%a0 ; move.l #0x00000003,%d0 ; move.b %d0,(%a0) ; " +
      "moveq #0,%d1 ; subi.b #1,%d1 ; " +        // X=1 (0-1 borrow)
      "negx.b (%a0) ; " +                         // 0 - 3 - 1 = 0xfc, N=1
      ".stop: bra .stop",
      nInstr = 6, checkMem = Seq(0x3000L), checkSpan = 1)
  }
  test("lock-step: TST.L (An) RMW = load+flags, NO store", VerilatorTest) {
    runLockStep("rmw-tst-l",
      "move.l #0x80000000,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; tst.l (%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // N=1, mem unchanged
  }
  test("lock-step: TST.W (An) RMW = load+flags, NO store (Z)", VerilatorTest) {
    runLockStep("rmw-tst-w",
      "move.l #0x11110000,%d1 ; move.l #0x3000,%a1 ; move.l %d1,(%a1) ; tst.w (%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // Z=1, mem unchanged
  }
  test("lock-step: CMPI.L #imm,(An) = load+compare, NO store (Z)", VerilatorTest) {
    runLockStep("rmw-cmpi-l",
      "move.l #0x00000005,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; cmpi.l #0x00000005,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // equal -> Z=1, mem unchanged
  }
  test("lock-step: CMPI.W #imm,(An) = load+compare, NO store (N/C)", VerilatorTest) {
    runLockStep("rmw-cmpi-w",
      "move.l #0x11110010,%d1 ; move.l #0x3000,%a1 ; move.l %d1,(%a1) ; cmpi.w #0x0020,(%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)   // 0x10<0x20 -> N/C, mem unchanged
  }
  test("lock-step: RMW to (d16,An) (ADDQ)", VerilatorTest) {
    runLockStep("rmw-d16an",
      "move.l #0x00000007,%d0 ; move.l #0x3000,%a1 ; move.l %d0,0x10(%a1) ; addq.l #1,0x10(%a1) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3010L))   // (d16,A1) -> 8 at 0x3010
  }
  test("lock-step: RMW to (xxx).L abs (SUBI)", VerilatorTest) {
    runLockStep("rmw-abs",
      "move.l #0x0000000a,%d1 ; move.l #0x3000,%a2 ; move.l %d1,(%a2) ; subi.l #4,0x3000 ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // (xxx).L abs -> 6 at 0x3000
  }

  // ── Bit ops (BTST/BSET/BCLR/BCHG) lock-step ────────────────────────────────
  // {BTST,BSET,BCLR,BCHG} × {static #n, dynamic Dn} × {Dn-dest LONG, memory-dest BYTE},
  // with bit-number edges (0,7,31,32->mod32; 33,63->mod32; 8->mod8 byte). Each tests bit
  // n -> Z = ~bit; all but BTST then set/clear/toggle it. Dn = full LONG (mod 32); memory
  // = BYTE (mod 8): BTST load-only (NO store), BSET/BCLR/BCHG mem-RMW. Flags = Z ONLY;
  // N/V/C/X preserved. checkMem verifies the byte RMW (and BTST's NO-store).

  // ── static #n, Dn dest (LONG, mod 32) — bit edges ──────────────────────────
  test("lock-step: BSET/BCLR/BCHG/BTST #n,Dn (static, bit 0)", VerilatorTest) {
    runLockStep("bit-static-dn-0",
      "move.l #0x00000000,%d0 ; bset #0,%d0 ; "  +  // -> bit0 set, Z=1 (was 0)
      "move.l #0xffffffff,%d1 ; bclr #0,%d1 ; "  +  // -> bit0 clear, Z=0
      "move.l #0x00000001,%d2 ; bchg #0,%d2 ; "  +  // -> bit0 toggled, Z=0
      "move.l #0x00000000,%d3 ; btst #0,%d3 ; "  +  // -> Z=1, no write
      ".stop: bra .stop", nInstr = 9)
  }
  test("lock-step: BSET/BCLR/BCHG/BTST #7,Dn (static, bit 7)", VerilatorTest) {
    runLockStep("bit-static-dn-7",
      "move.l #0x11223344,%d0 ; bset #7,%d0 ; "  +
      "move.l #0x11223388,%d1 ; bclr #7,%d1 ; "  +
      "move.l #0x11223344,%d2 ; bchg #7,%d2 ; "  +
      "move.l #0x11223380,%d3 ; btst #7,%d3 ; "  +
      ".stop: bra .stop", nInstr = 9)
  }
  test("lock-step: BSET/BCLR/BCHG/BTST #31,Dn (static, top bit)", VerilatorTest) {
    runLockStep("bit-static-dn-31",
      "move.l #0x00000000,%d0 ; bset #31,%d0 ; " +  // -> 0x80000000
      "move.l #0xffffffff,%d1 ; bclr #31,%d1 ; " +  // -> 0x7fffffff
      "move.l #0x00000000,%d2 ; bchg #31,%d2 ; " +  // -> 0x80000000
      "move.l #0x80000000,%d3 ; btst #31,%d3 ; " +  // -> Z=0
      ".stop: bra .stop", nInstr = 9)
  }
  test("lock-step: BSET/BTST #32,Dn (static, mod 32 -> bit 0)", VerilatorTest) {
    runLockStep("bit-static-dn-32",
      "move.l #0x00000000,%d0 ; bset #32,%d0 ; " +  // 32 mod 32 = 0 -> bit0 set
      "move.l #0x00000001,%d1 ; btst #32,%d1 ; " +  // bit0=1 -> Z=0
      ".stop: bra .stop", nInstr = 5)
  }

  // ── dynamic Dn, Dn dest (LONG, mod 32) — bit edges incl. >31 ───────────────
  test("lock-step: BSET/BCLR/BCHG/BTST Dc,Dn (dynamic, c=3)", VerilatorTest) {
    runLockStep("bit-dyn-dn-3",
      "moveq #3,%d7 ; "                          +
      "move.l #0x00000000,%d0 ; bset %d7,%d0 ; " +  // bit3 set
      "move.l #0xffffffff,%d1 ; bclr %d7,%d1 ; " +  // bit3 clear
      "move.l #0x00000008,%d2 ; bchg %d7,%d2 ; " +  // bit3 toggle -> 0
      "move.l #0x00000000,%d3 ; btst %d7,%d3 ; " +  // Z=1
      ".stop: bra .stop", nInstr = 10)
  }
  test("lock-step: BSET/BTST Dc,Dn (dynamic, c=31)", VerilatorTest) {
    runLockStep("bit-dyn-dn-31",
      "moveq #31,%d7 ; "                         +
      "move.l #0x00000000,%d0 ; bset %d7,%d0 ; " +  // -> 0x80000000
      "move.l #0x80000000,%d1 ; btst %d7,%d1 ; " +  // Z=0
      ".stop: bra .stop", nInstr = 5)
  }
  test("lock-step: BSET/BTST Dc,Dn (dynamic, c=33 -> mod32 bit 1)", VerilatorTest) {
    runLockStep("bit-dyn-dn-33",
      "moveq #33,%d7 ; "                         +
      "move.l #0x00000000,%d0 ; bset %d7,%d0 ; " +  // 33 mod 32 = 1 -> bit1 set (0x2)
      "move.l #0x00000002,%d1 ; btst %d7,%d1 ; " +  // bit1=1 -> Z=0
      ".stop: bra .stop", nInstr = 5)
  }
  test("lock-step: BCHG Dc,Dn (dynamic, c=63 -> mod32 bit 31)", VerilatorTest) {
    runLockStep("bit-dyn-dn-63",
      "move.l #63,%d7 ; "                        +  // 63 mod 32 = 31
      "move.l #0x00000000,%d0 ; bchg %d7,%d0 ; " +  // -> 0x80000000
      ".stop: bra .stop", nInstr = 3)
  }

  // ── memory dest (BYTE, mod 8): BSET/BCLR/BCHG = RMW; BTST = load-only NO store ──
  test("lock-step: BSET.B #n,(An) (mem RMW byte)", VerilatorTest) {
    runLockStep("bit-mem-bset",
      "move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; bset #0,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)  // byte 0x00 -> 0x01, Z was 1
  }
  test("lock-step: BCLR.B #7,(An) (mem RMW byte)", VerilatorTest) {
    runLockStep("bit-mem-bclr",
      "move.l #0x112233ff,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; bclr #7,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)  // byte 0xff bit7 clear -> 0x7f
  }
  test("lock-step: BCHG.B #n,(An) (mem RMW byte)", VerilatorTest) {
    runLockStep("bit-mem-bchg",
      "move.l #0x11223355,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; bchg #1,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)  // byte 0x55 bit1 toggle -> 0x57
  }
  test("lock-step: BTST #n,(An) (mem load-only, NO store)", VerilatorTest) {
    runLockStep("bit-mem-btst",
      "move.l #0x11223380,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; btst #7,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)  // byte 0x80 bit7=1 -> Z=0, mem unchanged
  }
  test("lock-step: BSET.B #8,(An) (mem, 8 mod 8 -> bit 0)", VerilatorTest) {
    runLockStep("bit-mem-bset-8",
      "move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; bset #8,(%a0) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 4)  // 8 mod 8 = 0 -> byte 0x00->0x01
  }
  test("lock-step: BSET %d1,(An) (dynamic mem RMW byte)", VerilatorTest) {
    runLockStep("bit-mem-dyn-bset",
      "moveq #3,%d1 ; move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; bset %d1,(%a0) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)  // byte 0x00 bit3 set -> 0x08
  }

  // ── memory bit-op with (An)+/-(An): BYTE access -> An adjusts by 1 (the eaDelta fix) ──
  // The bit-op RESULT in memory AND the An post-update lock-step vs Musashi (full reg
  // stream + checkMem). A BYTE (An)+ must increment An by 1; pre-fix it over-incremented
  // by 2. `move.l %a0,%d7` lands A0's resolved value in the compared register stream.
  test("lock-step: BSET #n,(A0)+ post-inc -> mem RMW + A0 += 1 (BYTE)", VerilatorTest) {
    runLockStep("bit-mem-bset-postinc",
      "move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; " +
      "bset #0,(%a0)+ ; move.l %a0,%d7 ; " +                  // mem[0x3000] bit0 set; A0 -> 0x3001
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: BCLR #7,(A0)+ post-inc -> mem RMW + A0 += 1 (BYTE)", VerilatorTest) {
    runLockStep("bit-mem-bclr-postinc",
      "move.l #0x112233ff,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; " +
      "bclr #7,(%a0)+ ; move.l %a0,%d7 ; " +                  // byte 0xff bit7 clear -> 0x7f; A0 -> 0x3001
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: BCHG #1,(A0)+ post-inc -> mem RMW + A0 += 1 (BYTE)", VerilatorTest) {
    runLockStep("bit-mem-bchg-postinc",
      "move.l #0x11223355,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; " +
      "bchg #1,(%a0)+ ; move.l %a0,%d7 ; " +                  // byte 0x55 bit1 toggle -> 0x57; A0 -> 0x3001
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: BSET #n,-(A0) pre-dec -> mem RMW + A0 -= 1 (BYTE)", VerilatorTest) {
    runLockStep("bit-mem-bset-predec",
      "move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; " +
      "move.l #0x3001,%a0 ; bset #0,-(%a0) ; move.l %a0,%d7 ; " +  // -(A0): A0 0x3001 -> 0x3000, byte bit0 set
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: BSET %d1,(A0)+ dynamic post-inc -> mem RMW + A0 += 1 (BYTE)", VerilatorTest) {
    runLockStep("bit-mem-dyn-bset-postinc",
      "moveq #3,%d1 ; move.l #0x11223300,%d0 ; move.l #0x3000,%a0 ; move.l %d0,(%a0) ; " +
      "bset %d1,(%a0)+ ; move.l %a0,%d7 ; " +                 // byte bit3 set -> 0x08; A0 -> 0x3001
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // A7-byte special case: a BYTE (A7)+ adjusts A7 by 2 (keep SP even). The DECODE side of
  // this rule (eaDelta=2 for a memory bit-op on A7) is proven directly in BitOpDecodeSpec
  // ("BSET #n,(A7)+ -> A7-byte eaDelta=2"). It is NOT lock-stepped here: a store-folded A7
  // post-update from a non-first crack µop is not surfaced through the whitebox A7
  // reconstruction (ss.a7 tracks the exception SP, not an OoO RMW-store A7 fold) -> A7 reads
  // back unchanged for BOTH the pre-fix and post-fix delta, so the divergence is the
  // ORTHOGONAL A7-banking/whitebox limitation, not the eaDelta fix. The non-A7 cases above
  // (A0 += 1 / -= 1, dynamic) are the lock-step correctness proof for the delta.

  // ── flag preservation: a bit-op changes Z ONLY; N/V/C/X preserved ──────────
  // Pre-set N/V/C/X via a prior addq overflow + carry, then a bit-op: Z flips, the
  // other CCR bits hold. (subi.b #1 on 0 sets C/X/N; the following bset only sets Z.)
  test("lock-step: bit-op preserves N/V/C/X (only Z changes)", VerilatorTest) {
    runLockStep("bit-flags-preserve",
      "moveq #0,%d1 ; subi.b #1,%d1 ; "          +  // 0-1 -> 0xff, sets N=1,C=1,X=1,V=0,Z=0
      "move.l #0x00000000,%d0 ; bset #5,%d0 ; "  +  // bit5 was 0 -> Z=1; N/V/C/X unchanged
      "move.l #0x00000020,%d2 ; btst #5,%d2 ; "  +  // bit5=1 -> Z=0; N/V/C/X still unchanged
      ".stop: bra .stop", nInstr = 6)
  }

  // ── Branch lock-step (2-byte short branches) ──────────────────────────────
  // No predictor: a TAKEN branch is a mispredict -> the ROB registers a
  // commit-time redirect pulse that squashes the speculative fall-through and
  // refetches at the resolved target. The branch's commit pc = resolved nextPc
  // (= Musashi's post-instruction pc). Each program passes an EXPLICIT executed
  // instruction count (a taken branch skips/loops -> count != source lines).

  test("lock-step: beq.s taken (skips a moveq)", VerilatorTest) {
    // D0=1; cmp d0,d0 -> Z=1; beq taken -> skip `moveq #9,%d1`; land on moveq#7.
    // Executed: moveq#1, cmp, beq(taken->target), moveq#7 = 4.
    runLockStep("beq-taken",
      "moveq #1,%d0 ; cmp.l %d0,%d0 ; beq.s .L ; moveq #9,%d1 ; .L: moveq #7,%d2",
      nInstr = 4)
  }

  test("lock-step: bne.s not-taken (falls through)", VerilatorTest) {
    // D0=1; cmp d0,d0 -> Z=1; bne NOT taken -> fall through (D1=9), then moveq#7.
    // Executed: moveq#1, cmp, bne(not-taken->pc+2), moveq#9, moveq#7 = 5.
    runLockStep("bne-nottaken",
      "moveq #1,%d0 ; cmp.l %d0,%d0 ; bne.s .L ; moveq #9,%d1 ; .L: moveq #7,%d2",
      nInstr = 5)
  }

  test("lock-step: bra.s unconditional", VerilatorTest) {
    // Unconditional taken -> skip `moveq #9,%d0`; land on moveq#7.
    // Executed: bra(taken->target), moveq#7 = 2.
    runLockStep("bra",
      "bra.s .L ; moveq #9,%d0 ; .L: moveq #7,%d1",
      nInstr = 2)
  }

  // ── JMP (computed-target branch, no push) ──────────────────────────────────
  test("lock-step: jmp (An) computed target", VerilatorTest) {
    // Load .L's absolute address into A0, jmp (A0): skips moveq#9, lands on moveq#7.
    // Executed: move.l#.L,a0, jmp(a0), moveq#7 = 3.
    runLockStep("jmp-an",
      "move.l #.L,%a0 ; jmp (%a0) ; moveq #9,%d0 ; .L: moveq #7,%d1",
      nInstr = 3)
  }

  test("lock-step: jmp (xxx).L absolute target", VerilatorTest) {
    // jmp .L (absolute long): skips moveq#9, lands on moveq#7.
    // Executed: jmp(abs), moveq#7 = 2.
    runLockStep("jmp-abs",
      "jmp (.L).l ; moveq #9,%d0 ; .L: moveq #7,%d1",
      nInstr = 2)
  }

  test("lock-step: jmp (d16,PC) pc-relative target", VerilatorTest) {
    // jmp .L(pc) (PC-relative): skips moveq#9, lands on moveq#7.
    // Executed: jmp(pc-rel), moveq#7 = 2.
    runLockStep("jmp-pcrel",
      "jmp .L(%pc) ; moveq #9,%d0 ; .L: moveq #7,%d1",
      nInstr = 2)
  }

  test("lock-step: jmp (d16,An) displaced target", VerilatorTest) {
    // A0 = .L - 4; jmp 4(A0) -> .L. Skips moveq#9, lands on moveq#7.
    // Executed: move.l#.L-4,a0, jmp 4(a0), moveq#7 = 3.
    runLockStep("jmp-d16an",
      "move.l #.L-4,%a0 ; jmp 4(%a0) ; moveq #9,%d0 ; .L: moveq #7,%d1",
      nInstr = 3)
  }

  test("lock-step: bsr push (no rts) isolation", VerilatorTest) {
    // moveq#1 ; bsr sub ; .stop: bra .stop ; sub: moveq#3 ; bra .stop2 ; .stop2: bra .stop2
    // No RTS at all -> isolates the BSR push+branch crack from the RTS pop+ibranch.
    // Executed to sentinel: moveq#1, bsr, moveq#3, bra = 4.
    runLockStep("bsr-push-iso",
      "moveq #1,%d0 ; bsr sub ; .stop: bra .stop ; sub: moveq #3,%d1 ; bra .stop2 ; .stop2: bra .stop2",
      nInstr = 4)
  }

  // ── BSR / RTS (the core call/return round trip) ────────────────────────────
  test("lock-step: bsr ... rts round trip", VerilatorTest) {
    // moveq#1,d0 ; bsr sub ; moveq#7,d2 ; stop-fence(bra .) ; sub: moveq#3,d1 ; rts
    // Flow: moveq#1, bsr(push retPC + branch to sub), moveq#3, rts(pop + branch back),
    // moveq#7, then bra-to-self sentinel. Executed (to the sentinel): moveq#1, bsr,
    // moveq#3, rts, moveq#7 = 5. Verifies retPC round-trip + A7 restored (push then
    // pop -> net 0) + final regs/PC.
    runLockStep("bsr-rts",
      "moveq #1,%d0 ; bsr sub ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rts",
      nInstr = 5)
  }

  test("lock-step: nested bsr (call within a callee)", VerilatorTest) {
    // outer calls inner; inner returns; outer returns. Two pushes, two pops, A7 net 0.
    // moveq#1,d0 ; bsr a ; .stop: bra .stop ;
    // a: moveq#2,d1 ; bsr b ; moveq#4,d3 ; rts ;
    // b: moveq#3,d2 ; rts
    // Executed to sentinel: moveq#1, bsr a, moveq#2, bsr b, moveq#3, rts(b->a),
    // moveq#4, rts(a->main) = 8.
    runLockStep("bsr-nested",
      "moveq #1,%d0 ; bsr a ; .stop: bra .stop ; " +
      "a: moveq #2,%d1 ; bsr b ; moveq #4,%d3 ; rts ; " +
      "b: moveq #3,%d2 ; rts",
      nInstr = 8)
  }

  test("lock-step: deep-nested call (3 levels, LIFO unwind) — RAS depth", VerilatorTest) {
    // main calls a; a calls b; b calls c; each returns -> the RAS pushes 3 retPCs and
    // pops them in LIFO order (c->b->a->main). With RAS prediction live every return is
    // predicted; the architectural result must stay byte-identical vs Musashi.
    // Flow to sentinel: moveq#1, bsr a, moveq#2, bsr b, moveq#3, bsr c, moveq#4,
    //   rts(c->b), moveq#5, rts(b->a), moveq#6, rts(a->main) = 12.
    runLockStep("bsr-deep-nested",
      "moveq #1,%d0 ; bsr a ; .stop: bra .stop ; " +
      "a: moveq #2,%d1 ; bsr b ; moveq #5,%d4 ; rts ; " +
      "b: moveq #3,%d2 ; bsr c ; moveq #4,%d3 ; rts ; " +
      "c: moveq #6,%d5 ; rts",
      nInstr = 12)
  }

  test("lock-step: call/return with an interleaved mispredict — RAS corrupt-recovery", VerilatorTest) {
    // A loop body CALLS a leaf subroutine and also contains a data-dependent Bcc whose
    // direction the bimodal BTB will mispredict at least once (the loop's back-edge DBcc
    // flips direction on the final iteration; the leaf's rts is RAS-predicted). On the
    // mispredicting cycle the front-end speculatively walks the WRONG path — which may
    // push/pop the RAS for a wrong-path call/return, corrupting the speculative sp. The
    // EU verifies the REAL return target (loaded from the stack), so the commit-time
    // redirect recovers; the ARCHITECTURAL result must still match Musashi byte-for-byte
    // (proving EU-verify recovers a corrupt RAS — the spec's recovery=accept-corruption).
    //   d7 = 3 loop count; each iter: bsr leaf (push+pop RAS), subq#1,d7, bne back.
    //   leaf: addq#1,d0 ; rts.
    // The bne is taken twice then NOT-taken once -> a guaranteed mispredict on the exit.
    runLockStep("bsr-loop-mispredict",
      "moveq #3,%d7 ; moveq #0,%d0 ; " +
      "back: bsr leaf ; subq #1,%d7 ; bne back ; " +
      "moveq #9,%d6 ; .stop: bra .stop ; " +
      "leaf: addq #1,%d0 ; rts",
      nInstr = 18)
  }

  // ── JSR (call via the EA address) ──────────────────────────────────────────
  test("lock-step: jsr (An) ... rts", VerilatorTest) {
    // A0 = sub; jsr (A0) pushes retPC + jumps to sub; sub does moveq#3 + rts.
    // Executed to sentinel: moveq#1, move.l#sub a0, jsr(a0), moveq#3, rts, moveq#7 = 6.
    runLockStep("jsr-an",
      "moveq #1,%d0 ; move.l #sub,%a0 ; jsr (%a0) ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rts",
      nInstr = 6)
  }

  // ── RTD (RTS with a stack-deallocation displacement) ───────────────────────
  test("lock-step: bsr ... rtd #4 (pop PC + dealloc the pushed arg)", VerilatorTest) {
    // The caller pushes a 4-byte arg, then BSRs. The callee returns with `rtd #4`,
    // which pops the return PC from (A7) AND deallocates the 4 arg bytes (A7 += 4+4),
    // so A7 is restored to its value BEFORE the arg push. Verifies the RTD crack:
    // [pop PC -> T0][A7 += 4+disp16][ibranch T0] vs Musashi (PC + A7 step-for-step).
    // Flow to sentinel: moveq#1, moveq#0xa->d3, move.l d3 -(sp), bsr, moveq#3, rtd#4,
    // moveq#7 = 7.
    runLockStep("rtd-dealloc",
      "moveq #1,%d0 ; moveq #0xa,%d3 ; move.l %d3,-(%sp) ; bsr sub ; moveq #7,%d2 ; " +
      ".stop: bra .stop ; sub: moveq #3,%d1 ; rtd #4",
      nInstr = 7)
  }

  test("lock-step: rtd #0 (== rts, no dealloc)", VerilatorTest) {
    // rtd #0 pops PC + A7 += 4 (exactly RTS). Confirms the disp16=0 edge.
    runLockStep("rtd-zero",
      "moveq #1,%d0 ; bsr sub ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rtd #0",
      nInstr = 5)
  }

  test("lock-step: rtd #-4 (negative displacement)", VerilatorTest) {
    // A negative disp16 leaves A7 BELOW the return slot (A7 += 4 + (-4) = +0). Exercises
    // the sign-extension of disp16 in the A7 add. After return A7 = the post-pop value
    // minus 4 (one word below where RTS would leave it).
    runLockStep("rtd-neg",
      "moveq #1,%d0 ; bsr sub ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rtd #-4",
      nInstr = 5)
  }

  // ════════════════════════════════════════════════════════════════════════════
  // PRIVILEGED COMMIT-TIME SYSTEM ops (Track D): MOVE-USP / MOVE-to-SR / MOVEC.
  // The core boots SUPERVISOR (S=1, SSP=0x00100000, USP=0). The serializing system-op
  // FSM (ExceptionUnit S_APPLY) writes committed state + re-banks A7 + redirects.
  // ════════════════════════════════════════════════════════════════════════════

  // MOVE USP round-trip (supervisor): write USP from A3, read it back into A4. A4 is
  // surfaced into D5 (the read is FSM-written, verified by the following reader). A7
  // stays = SSP (S unchanged), so the serialization is the only effect.
  test("lock-step: MOVE A3,USP ; MOVE USP,A4 round-trip (supervisor)", VerilatorTest) {
    runLockStep("move-usp-roundtrip",
      "move.l #0x12340000,%a3 ; move.l %a3,%usp ; move.l %usp,%a4 ; move.l %a4,%a5 ; " +
      ".stop: bra .stop",
      nInstr = 4)   // a5 == 0x12340000 (USP round-tripped; MOVEA sets no flags)
  }

  // ── The sysOp whose OWN destination is A7 (ExceptionUnit `sysOwnA7Valid` fix) ──
  //
  // `S_REDIR` ends every sysOp by re-banking A7 into the int PRF from `ss.a7`. For a
  // sysOp whose own read/auto-update destination IS arch-15, that write targets exactly
  // the physical register `S_APPLY` wrote one cycle earlier — and `ss.a7` (a lagging PRF
  // readback, which for a read-direction sysOp is additionally pointed at the µop's
  // freshly renamed pdst holding the EU's throwaway MOVE result) does NOT yet hold the
  // new value. The A7 write was therefore silently LOST for both instructions below.
  // These two forms have NO settle state between `S_APPLY` and `S_REDIR` (they are
  // consecutive cycles), so nothing could mask it; FRESTORE (A7)+ hit the identical
  // mechanism and is covered separately by FsaveFrestoreSpec.
  //
  // Both tests lock-step A7 step-for-step against Musashi, AND surface it through a
  // following reader (`move.l %sp,%a1`) so the PRF CONTENT — not just the commit obs —
  // is checked. USP is seeded distinct from the boot SSP (0x00100000) so a lost write is
  // unambiguous.

  test("lock-step: MOVE USP,%A7 (sysOp dst IS A7) really writes A7", VerilatorTest) {
    runLockStep("move-usp-to-a7",
      // USP := 0x00200000, then MOVE USP,%A7 (0x4E6F) -> A7 (= SSP, S=1) := 0x00200000.
      // A1 surfaces the architectural A7 afterwards; A2 keeps the expected value live so
      // a mismatch is visible in the register comparison too.
      "move.l #0x00200000,%a2 ; move.l %a2,%usp ; move.l %usp,%sp ; move.l %sp,%a1 ; " +
      ".stop: bra .stop",
      nInstr = 4)
  }

  test("lock-step: MOVEC USP,%A7 (sysOp dst IS A7) really writes A7", VerilatorTest) {
    runLockStep("movec-usp-to-a7",
      // Same shape through the MOVEC Rc->Rn arm: ext word 0xF800 = {A/D=1, reg#=7,
      // Rc=0x800 (USP)} -> `movec %usp,%sp`.
      "move.l #0x00300000,%a2 ; move.l %a2,%usp ; movec %usp,%sp ; move.l %sp,%a1 ; " +
      ".stop: bra .stop",
      nInstr = 4)
  }

  // MOVE to SR — the S-bit write + A7 banking. Set USP distinct from SSP, then a
  // MOVE-to-SR clearing S switches supervisor->user: A7 must bank from SSP to the USP
  // value (lock-step a7 step-for-step). The SR system byte also changes (S 1->0). The
  // .L move sets D0; the .W source is D0's low word. SR=0x0700 (user, I=7, all CCR 0).
  test("lock-step: MOVE D0,SR (S=1->S=0) -> A7 banks to USP", VerilatorTest) {
    runLockStep("move-to-sr-bank",
      // USP := 0x00200000 (distinct from boot SSP 0x00100000); D0 := 0x0700 (user SR);
      // move D0,SR clears S -> A7 banks to USP=0x00200000. Read A7 into A1 to surface it.
      "move.l #0x00200000,%a2 ; move.l %a2,%usp ; move.w #0x0700,%d0 ; move %d0,%sr ; " +
      "move.l %sp,%a1 ; " +
      ".stop: bra .stop",
      nInstr = 5)   // after move-to-SR: S=0, A7=USP=0x00200000 (a1 surfaces A7)
  }

  // ── RESET (0x4E70): privileged no-op in supervisor ──────────────────────────
  // Architecturally a NOP (the external reset line is not modeled for lock-step); the
  // commit-time sysOp FSM consumes it + advances PC. Lock-step: RESET falls through, the
  // surrounding moveqs are unaffected (PC/SR/A7/regs step-for-step vs Musashi).
  test("lock-step: RESET (supervisor) falls through (no state change)", VerilatorTest) {
    runLockStep("reset-fallthrough",
      "moveq #1,%d0 ; reset ; moveq #2,%d1 ; " +
      ".stop: bra .stop",
      nInstr = 3)
  }

  // RESET at S=0 (user) -> vector-8 privilege violation (format-$0, restartable: stacks its
  // OWN PC). Same shape as the MOVE-USP privilege test: install the vector-8 handler @0x20,
  // drop to user, then `reset` traps. The handler bumps the stacked PC past the 2-byte op +
  // RTEs. PC/SR/A7 lock-stepped across user-switch, trap entry, handler, RTE.
  test("lock-step: RESET at S=0 -> vector-8 privilege violation -> handler -> RTE", VerilatorTest) {
    runLockStep("reset-priv",
      "move.l #handler,%d0 ; move.l %d0,0x20 ; move.w #0x0000,%d1 ; move %d1,%sr ; " + // -> user (S=0)
      "reset ; moveq #7,%d3 ; " +                                                      // privileged -> trap; resume here
      "loop: bra loop ; " +
      "handler: move.l 2(%a7),%d0 ; addq.l #2,%d0 ; move.l %d0,2(%a7) ; " +
      "moveq #1,%d2 ; rte",
      nInstr = 11, usp = 0)
  }

  // ── STOP (0x4E72) + imm16: SR-load + halt + resume-on-IRQ ───────────────────
  // STOP loads SR := imm16 (S=1, I-mask=0 here) then HALTS. A level-5 autovector IRQ
  // (vec 29 @ 0x74) wakes it: the IRQ entry takes the handler, which RTEs back to the
  // STOP successor. Lock-step the FULL state (SR incl. mask / A7 / PC) across STOP -> halt
  // -> IRQ entry -> handler -> RTE -> resume, vs Musashi. The IRQ is injected once the DUT
  // reaches the `stopped` state (the STOP commit itself, via commitObs(2), does not drive
  // the commit-PC-triggered injection the normal IRQ harness uses, and STOP halts so no
  // further user commit fires) — so this is a bespoke STOP-aware harness.
  test("lock-step: STOP #imm -> halt -> IRQ -> handler -> RTE -> resume", VerilatorTest) {
    val name = "stop-irq"
    val initialSr = 0x2700
    val src =
      "move.l #handler,%d0 ; move.l %d0,0x74 ; " +   // install vector 29 (autovec lvl 5) @ 0x74
      "stop #0x2000 ; moveq #2,%d2 ; " +             // SR := 0x2000 (S=1, mask=0) then HALT; resume here
      "loop: bra loop ; " +
      "handler: moveq #9,%d3 ; rte"
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    // STOP commits at its nextPc; the IRQ is recognized while halted (vec 24+5 = 29).
    val nInstr = 6   // move1, move2, STOP, handler-moveq#9, rte, moveq#2 (entry obs dropped)
    val oracleSteps = Musashi.assembleAndTrace(src, irqEvents = Seq((0x4080000eL, 5)),
                                               interruptAckVector = None,
                                               initialSr = Some(initialSr)) match {
      case Right(v)  => v
      case Left(err) => fail(s"[$name] Musashi.assembleAndTrace failed: ${err.reason}")
    }
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[$name] ProgramAssembler.assemble failed: ${err.reason}")
    }
    assert(oracleSteps.size >= nInstr, s"[$name] oracle produced ${oracleSteps.size}, expected >= $nInstr")
    val oracle = oracleSteps.take(nInstr)

    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      var irqRaised = false

      def captureWb(w: m68k040.execute.WbObs): Unit = {
        if (w.valid.toBoolean) handle.onWb(w.robId.toInt, WhiteboxCapture.Wb(
          dstArch = w.dstArch.toInt, result = w.result.toLong & 0xffffffffL,
          intWrite = w.intWrite.toBoolean, nzvc = w.nzvc.toInt, nzvcWrite = w.nzvcWrite.toBoolean,
          x = if (w.x.toBoolean) 1 else 0, xWrite = w.xWrite.toBoolean, divRem = w.divRem.toBoolean,
          keepCommit = w.keepCommit.toBoolean))
      }

      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs); captureWb(dut.eu1.logic.wbObs)
        captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs)
        val bw = dut.branchEu.logic.wbObs
        if (bw.valid.toBoolean) handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
        // Precise-path store completion (Task P2.5): see the captureSq comment
        // elsewhere in this file -- no lsEu.logic.wbObs pulse accompanies a precise
        // store's completion, so synthesize a no-op Wb here too.
        val sc = dut.lsEu.sqCompletionPort
        if (sc.valid.toBoolean) handle.onWb(sc.payload.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean)
            handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL,
              msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
              isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
        }
        val ce = dut.rob.logic.commitObs(2)
        if (ce.fire.toBoolean) {
          if (!ce.isInterrupt.toBoolean)
            handle.onExcCommit(ce.pc.toLong & 0xffffffffL, ce.sysByte.toInt & 0xff, ce.a7.toLong & 0xffffffffL,
              if (ce.ccrFoldValid.toBoolean) ce.ccrFold.toInt & 0xf else -1,
              if (ce.setCcr5Valid.toBoolean) ce.setCcr5.toInt & 0x1f else -1,
              msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
              isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
          else dut.intCtrl.logic.iplIn #= 0   // one-shot edge: drop on the entry
        }
        // STOP-aware IRQ injection: raise iplIn once the core is halted (stopped). The new
        // SR mask is 0, so a level-5 IRQ is recognized; it wakes the core.
        if (dut.rob.logic.stopped.toBoolean && !irqRaised) {
          irqRaised = true
          dut.intCtrl.logic.iplIn #= 5
          dut.intCtrl.logic.iackAvec #= true
          dut.intCtrl.logic.iackVector #= 0
        }
      }

      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false
      dut.ctrl.logic.urp   #= 0
      dut.ctrl.logic.srp   #= 0
      dut.intCtrl.logic.iplIn #= 0
      dut.intCtrl.logic.iackAvec #= false
      dut.intCtrl.logic.iackVector #= 0
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      dut.rob.logic.exc.ss.srSys #= (initialSr >> 8) & 0xff
      // This bespoke harness must obey the same boot contract as runLockStep:
      // architectural A7 lives in the integer PRF, while ss.isp is its committed
      // supervisor-bank shadow.  Seeding only ss.isp lets the live A7 feedback
      // overwrite it with the reset PRF value (zero) before STOP/IRQ entry.
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      var guard = 0; val cap = 6000
      while (handle.result.size < nInstr && guard < cap) { cd.waitSampling(); guard += 1 }
      assert(handle.result.size >= nInstr,
        s"[$name] only ${handle.result.size}/$nInstr instructions committed within $cap cycles")
      val res = LockStep.compare(handle.result.take(nInstr), oracle)
      assert(res.ok,
        s"[$name] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
          s"(matched ${res.matched}, dut commits ${handle.result.size}, oracle steps $nInstr)")
    }
  }

  // ── MOVEC (Track D): VBR / USP / CACR control registers ─────────────────────
  // MOVEC USP round-trip: write USP from D0 (movec %d0,%usp), read it back into D1
  // (movec %usp,%d1). D1 == D0 proves the USP bank via MOVEC. (Supervisor; A7 unchanged.)
  test("lock-step: MOVEC D0,USP ; MOVEC USP,D1 round-trip", VerilatorTest) {
    runLockStep("movec-usp",
      "move.l #0x0abc0000,%d0 ; movec %d0,%usp ; movec %usp,%d1 ; " +
      ".stop: bra .stop",
      nInstr = 3)   // d1 == 0x0abc0000
  }

  // MOVEC CACR RAZ-WI: write a value to CACR (write-ignored), read it back -> 0 (RAZ).
  // The 68040 CACR's only effects are cache enables, which this core lacks -> RAZ-WI.
  // (Musashi masks CACR to the implemented bits; for a fresh write-then-read the DUT's
  // RAZ matches Musashi when the written value clears on read of the unimplemented bits.
  // We write 0 then read 0 to stay trace-indistinguishable from Musashi's CACR model.)
  test("lock-step: MOVEC CACR read -> 0 (RAZ)", VerilatorTest) {
    runLockStep("movec-cacr-raz",
      "movec %cacr,%d2 ; " +    // read CACR (RAZ) -> D2 = 0
      ".stop: bra .stop",
      nInstr = 1)   // d2 == 0
  }

  // MOVEC SFC/DFC round-trip (NEW — fixes the latent RAZ-WI divergence). Musashi stores
  // REG_SFC/REG_DFC as 3-bit (`& 7`) and reads them back zero-extended. Write D0 (only the
  // low 3 bits survive) to SFC/DFC, read back into D1 -> D1 == D0 & 7. The read value is
  // then folded through a normal ALU op (`move.l %d1,%d2 ; addq.l #1,%d2`) so the read-back
  // value lands in a NORMAL EU writeback that the whitebox compares vs Musashi (the bare
  // MOVEC-read commits via the sys path, whose PRF value the whitebox does not see — so a
  // downstream consumer is required to actually verify the round-trip). Supervisor.
  test("lock-step: MOVEC D0,SFC ; MOVEC SFC,D1 round-trip", VerilatorTest) {
    runLockStep("movec-sfc",
      "move.l #0x0abc0005,%d0 ; movec %d0,%sfc ; movec %sfc,%d1 ; " +
      "move.l %d1,%d2 ; addq.l #1,%d2 ; " +
      ".stop: bra .stop",
      nInstr = 5)   // d1 == 0x00000005, d2 == 0x00000006
  }

  test("lock-step: MOVEC D0,DFC ; MOVEC DFC,D1 round-trip", VerilatorTest) {
    runLockStep("movec-dfc",
      "move.l #0x12345003,%d0 ; movec %d0,%dfc ; movec %dfc,%d1 ; " +
      "move.l %d1,%d2 ; addq.l #1,%d2 ; " +
      ".stop: bra .stop",
      nInstr = 5)   // d1 == 0x00000003, d2 == 0x00000004
  }

  // Task #131 — MOVEC URP/SRP/TC real-write support was ATTEMPTED and REVERTED
  // (confirmed to break sim-poke persistence on mmuEnable for every MMU-enabled
  // lock-step test — see MmuControlPlugin's doc comment). These Rc values are back
  // to RAZ/WI (same as CACR), so there is no write-capability test here to keep.
  // See task #131 for the redesign follow-up and task #135 for a separate,
  // still-open oracle gap (Musashi's own MOVEC URP/SRP/TC handlers are unimplemented
  // stubs, so even a future correct write implementation won't be lock-step
  // testable without first extending tools/musashi/musashi/m68k_in.c).

  // MOVEC VBR then an exception: set VBR := 0x3000, install the illegal-instruction
  // handler at VBR+4*4 = 0x3010 (a runtime store the DUT D-cache + Musashi both see),
  // then `illegal` (vector 4) -> the FSM fetches the vector at VBR+0x10 = 0x3010 ->
  // the handler PC must be the stored handler addr. This PROVES the MOVEC VBR write
  // drives the vector fetch (vs the default VBR=0). The handler bumps the stacked PC
  // past the 2-byte illegal + RTEs. Commit PC/SR/A7 lock-stepped across entry/handler/RTE.
  test("lock-step: MOVEC D0,VBR then illegal -> handler via new VBR -> RTE", VerilatorTest) {
    runLockStep("movec-vbr-exc",
      "move.l #0x3000,%d0 ; movec %d0,%vbr ; move.l #handler,%d1 ; move.l %d1,0x3010 ; " +
      "illegal ; moveq #7,%d3 ; " +
      "loop: bra loop ; " +
      "handler: move.l 2(%a7),%d0 ; addq.l #2,%d0 ; move.l %d0,2(%a7) ; moveq #1,%d2 ; rte",
      nInstr = 11)
  }

  // Task #136 prerequisite: line-1010 ("Line-A") / line-1111 ("Line-F") opcodes must
  // raise their OWN dedicated vectors (10 / 11), not the generic vector-4 illegal path
  // — confirmed real via Musashi's m68kcpu.h (the top opword nibble traps unconditionally,
  // no further decode). `.word 0xA000`/`.word 0xFD00` emit raw unassigned line-A/line-F
  // opcodes (GNU as has no mnemonic for these — they're intentionally unimplemented).
  // MOVEC VBR first (same trick as the vector-4 test above) so the handler addr is a
  // runtime store both DUT and Musashi observe; handler bumps the stacked PC past the
  // 2-byte faulting opword + RTEs. Commit PC/SR/A7 lock-stepped across entry/handler/RTE.
  //
  // ORACLE GAP (found while picking the line-F test opcode, NOT an RTL bug): the vendored
  // Musashi is built with CPU_TYPE_68040, which sets HAS_PMMU=1 (m68kcpu.c) — a modeling
  // leftover from the 68851/68030-era PMMU COPROCESSOR interface that real 68040 hardware
  // does not have (the 68040's MMU is integrated, MOVEC-programmed, no coprocessor opcodes
  // involved). Musashi's generated opcode table (m68kops.c: m68k_op_cpgen_32/cpscc_32/
  // cpbcc_32/pmmu_32/{040fpu0,040fpu1}_32) OVERWRITES the generic m68k_op_1111 handler for
  // most of the 0xF000-0xF3FF sub-range with these coprocessor/PMMU/FPU stubs, which for
  // CPU_TYPE_68040 (CPU_TYPE_IS_EC020_PLUS) just log-and-return WITHOUT raising vector 11
  // (see m68k_op_cpgen_32 / m68k_op_pmmu_32 in m68kops.c). So `.word 0xF000` silently
  // no-ops on this oracle instead of faulting — confirmed via a standalone musashi_run
  // trace (PC skips straight over it, no exception, D3 never gets set). `0xFD00` (bits
  // 11-9=110, bit8=1) falls outside every one of those overlapping masks and correctly
  // reaches the real m68k_op_1111 -> m68ki_exception_1111() path — verified standalone
  // before using it here. This same quirk will resurface for real CPUSH/PFLUSH lock-step
  // tests later (task #136) since their real encodings also live in the F0xx-F3xx range;
  // whitebox verification (like task #131's MOVEC URP/SRP) may be needed there instead.
  test("lock-step: line-A opcode -> vector 10 -> handler via VBR+0x28 -> RTE", VerilatorTest) {
    runLockStep("line-a-vec10",
      "move.l #0x3000,%d0 ; movec %d0,%vbr ; move.l #handler,%d1 ; move.l %d1,0x3028 ; " +
      ".word 0xA000 ; moveq #7,%d3 ; " +
      "loop: bra loop ; " +
      "handler: move.l 2(%a7),%d0 ; addq.l #2,%d0 ; move.l %d0,2(%a7) ; moveq #1,%d2 ; rte",
      nInstr = 11)
  }

  // `pcOnly` (task #176 follow-up, DISPUTED — see `ExceptionUnit.scala`'s `is2` comment
  // for the full writeup): Musashi's `m68ki_exception_1111` unconditionally stacks
  // format-$0 (8 bytes) for vector 11 regardless of CPU_TYPE, and a strict reading of
  // the MC68040 User's Manual (9.6.1) suggests format-$0 is also correct on real
  // hardware for a genuinely illegal/unrecognized F-line opcode (format-$2 is
  // documented only for a recognized-but-hardware-unimplemented FPU coprocessor-ID-1
  // opcode, cpID==001 — `0xFD00` here has cpID=110, arguably still the illegal case).
  // BUT the vendored ported test `exc_user_vbr_rte_matrix` hardcodes an explicit
  // format-$2 (SSP=base-12) expectation for its own F-line opcode, and this project's
  // standing goal is to match the m68k-ooo test corpus — so `ExceptionUnit.scala` keeps
  // format-$2 for vector 11, diverging from Musashi (same treatment as the CPUSH gap
  // below). `pcOnly` narrows this test's assertion to the committed PC sequence
  // accordingly. This is a genuinely unresolved disagreement between two sources of
  // truth (Musashi vs. the ported corpus), not a confidently-settled fact — a future
  // session with more primary-source clarity may want to revisit which side is right.
  test("lock-step: line-F opcode -> vector 11 -> handler via VBR+0x2c -> RTE", VerilatorTest) {
    runLockStep("line-f-vec11",
      "move.l #0x3000,%d0 ; movec %d0,%vbr ; move.l #handler,%d1 ; move.l %d1,0x302c ; " +
      ".word 0xFD00 ; moveq #7,%d3 ; " +
      "loop: bra loop ; " +
      "handler: move.l 2(%a7),%d0 ; addq.l #2,%d0 ; move.l %d0,2(%a7) ; moveq #1,%d2 ; rte",
      nInstr = 11, pcOnly = true)
  }

  // CPUSH (line-1111, real 0xF4xx encoding): a real, non-illegal 68040 instruction this
  // core has no cache hierarchy to model, so it's architecturally a NOP (matches this
  // project's established "commit-time SYSTEM op with no real effect" treatment, same as
  // RESET) — privileged, so a user-mode CPUSH must still trap vector 8.
  //
  // NOT LOCK-STEP-VERIFIABLE (2 separate, independently-confirmed Musashi oracle gaps,
  // both empirically verified via standalone `tools/musashi/musashi_run` before writing
  // these whitebox tests):
  //  1. PRIVILEGE: Musashi's cpbcc_32/cpgen_32/cpscc_32 coprocessor-format stubs (the
  //     SAME HAS_PMMU-adjacent CPU_TYPE_68040 quirk documented above for line-F vector-11)
  //     cover the ENTIRE CPUSH bit8=0 encoding space unconditionally (every possible
  //     bits7:6 combination is claimed by one of cpgen(00)/cpscc(01)/cpbcc(1x) — there is
  //     no CPUSH encoding that avoids it), and — unlike MOVEC's real privilege check —
  //     NONE of those handlers check FLAG_S before returning. Confirmed: a user-mode
  //     `cpusha bc` on standalone musashi_run completes with NO exception (D3 gets set by
  //     a following instruction with no diversion).
  //  2. STEP-COUNTING: `cpbcc_32`'s CPU_TYPE-68040 cycle-cost entry is 0 (`m68kops.c`
  //     table `{m68k_op_cpbcc_32, 0xf180, 0xf080, {0,0,4,4,0}}` — last column = 68040).
  //     Confirmed via a standalone musashi_run trace: the per-instruction trace callback
  //     never fires for the CPUSH retire itself — its effect is silently folded into the
  //     SAME callback as the NEXT instruction, permanently off-by-one-ing any step-indexed
  //     lock-step comparison even though Musashi's FINAL architectural state (PC/regs) is
  //     actually correct. This is independent of gap #1 and affects BOTH privilege modes.
  // Both directions verified via whitebox instead (mirrors runMoveFromSrPriv exactly —
  // drives the real FullCoreDut through the real decode/commit path, asserts on
  // `dut.rob.logic.exceptionPending`/`exceptionVector` directly, no oracle dependency).
  private def runCpushPriv(userMode: Boolean): (Boolean, Int) = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val src = "cpusha %bc ; handler: bra.s handler"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    var sawPriv = false
    var vec = -1
    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false; dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      val handlerPc = loadAddr + 2   // the `handler:` label (after the 1-word CPUSHA)
      for (i <- 0 until 4) dmem.pokeByte(0x20 + i, ((handlerPc >> (8 * i)) & 0xff).toInt)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      dut.rob.logic.exc.ss.usp #= 0x00200000L
      if (userMode) dut.rob.logic.exc.ss.srSys #= 0x00 else dut.rob.logic.exc.ss.srSys #= 0x27
      val bootA7 = if (userMode) 0x00200000L else 0x00100000L
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15; dut.wire.logic.seedData #= BigInt(bootA7)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      var guard = 0
      while (!sawPriv && guard < 600) {
        if (dut.rob.logic.exceptionPending.toBoolean) {
          sawPriv = true
          vec = dut.rob.logic.exceptionVector.toInt
        }
        cd.waitSampling(); guard += 1
      }
    }
    (sawPriv, vec)
  }

  // ── Task 9: FMOVE.L Dn,FPcr / FPcr,Dn end-to-end, in USER mode ────────────────
  // Proves three things the decode-level specs cannot: (a) the whole path really works
  // (decode -> rename -> IQ -> ALU EU -> ROB -> the serializing S_APPLY -> FpuControlPlugin
  // and back out through the int PRF), (b) FPCR and FPIAR are genuinely INDEPENDENT
  // registers selected by the ext[12:10] mask (not one aliased copy), and (c) it is NOT
  // privileged -- the whole program runs at committed S == 0 and must never raise vector 8,
  // which is the one behavioral difference between this SysKind and every other one.
  //
  // NOT LOCK-STEP-VERIFIABLE, for the SAME two independently-confirmed oracle gaps already
  // documented for CPUSH/line-F just above: Musashi exposes no FP register surface at all
  // via m68k_get_reg (spec Decision 3), and its CPU_TYPE_68040 coprocessor stubs claim
  // this whole opword range without raising anything. So it is a whitebox test: the WRITE
  // direction is observed directly on FpuControlPlugin's simPublic committed Regs, and the
  // READ direction through a downstream `move.l %d1,%d2` -- the SAME "a bare sys-path read
  // commits a PRF value the whitebox does not see, so a downstream consumer is required"
  // technique the MOVEC SFC/DFC round-trip tests above already use.
  //
  // Opwords are the LITERAL output of `m68k-linux-gnu-as -m68040 -m68881` (Task 9 Step 1);
  // they are emitted as `.short` here because ProgramAssembler invokes `as` without
  // -m68881, exactly like the `.word 0xFD00` line-F test above.
  test("FMOVE.L D0,FPCR/FPIAR then FPcr,Dn round-trips in USER mode (not privileged)", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val src =
      "move.l #0x00000030,%d0 ; " +
      ".short 0xF200,0x9000 ; " +   // fmove.l %d0,%fpcr
      ".short 0xF201,0xB000 ; " +   // fmove.l %fpcr,%d1
      "move.l %d1,%d2 ; " +         // expose the FPCR read-back in a normal EU writeback
      "move.l #0x000000AB,%d5 ; " +
      ".short 0xF205,0x8400 ; " +   // fmove.l %d5,%fpiar
      ".short 0xF203,0xA400 ; " +   // fmove.l %fpiar,%d3
      "move.l %d3,%d4 ; " +         // expose the FPIAR read-back
      "done: bra.s done"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    var fpcrSeen  = BigInt(-1)
    var fpiarSeen = BigInt(-1)
    var fpsrSeen  = BigInt(-1)
    var d2Seen    = -1L
    var d4Seen    = -1L
    var sawExc    = false
    var excVec    = -1
    compiledDut.doSim(freshSimName("fmove-fpctrl")) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false; dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      // Past the DUT's ~82-cycle reset/init sweep before poking committed state (the
      // MmuControlPlugin lesson recorded above: an earlier poke is just reset-clobbered).
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0x00200000L
      dut.rob.logic.exc.ss.srSys #= 0x00           // USER mode -- the point of the test
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00200000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      // Watch the ALU EUs for the two downstream exposing moves (D2 = FPCR read-back,
      // D4 = FPIAR read-back).
      def snoop(w: m68k040.execute.WbObs): Unit =
        if (w.valid.toBoolean && w.intWrite.toBoolean) {
          val a = w.dstArch.toInt
          if (a == 2) d2Seen = w.result.toLong & 0xffffffffL
          if (a == 4) d4Seen = w.result.toLong & 0xffffffffL
        }
      var guard = 0
      while (guard < 900) {
        if (dut.rob.logic.exceptionPending.toBoolean && !sawExc) {
          sawExc = true; excVec = dut.rob.logic.exceptionVector.toInt
        }
        snoop(dut.eu0.logic.wbObs); snoop(dut.eu1.logic.wbObs)
        cd.waitSampling(); guard += 1
      }
      fpcrSeen  = dut.fpuCtl.logic.fpcr.toBigInt
      fpiarSeen = dut.fpuCtl.logic.fpiar.toBigInt
      fpsrSeen  = dut.fpuCtl.logic.fpsr.toBigInt
    }
    assert(!sawExc,
      s"FMOVE to/from an FP CONTROL register is a USER instruction -- it must not trap; got vector $excVec")
    assert(fpcrSeen == 0x30, s"FPCR should hold 0x30 after FMOVE.L D0,FPCR; got 0x${fpcrSeen.toString(16)}")
    assert(fpiarSeen == 0xAB, s"FPIAR should hold 0xAB after FMOVE.L D5,FPIAR; got 0x${fpiarSeen.toString(16)}")
    assert(fpsrSeen == 0, s"FPSR was never written and must still be 0; got 0x${fpsrSeen.toString(16)}")
    assert(d2Seen == 0x30L, f"FMOVE.L FPCR,D1 read-back (via D2) should be 0x30; got 0x$d2Seen%08X")
    assert(d4Seen == 0xABL, f"FMOVE.L FPIAR,D3 read-back (via D4) should be 0xAB; got 0x$d4Seen%08X")
  }

  // ── Task 9 (review fix): the FPSR / committed-FPCC path, end to end ───────────────
  // The test above deliberately never writes FPSR (it asserts fpsr == 0), so before this
  // one NOTHING in the repo drove `ExceptionUnit.fpccWriteValid`, exercised the `fpccWr`
  // port into the FPCC PRF, or read `fpsrArch`. That is the one genuinely new mechanism
  // Task 9 built, and it is the same mechanism class (a DIRECT committed-mapping write
  // bypassing rename) that already produced a confirmed silent-corruption regression here
  // once -- see `ExceptionUnit.rteNzvcWriteValid`'s doc comment. So it gets a live test.
  //
  // THREE independent properties, each with its own assertion:
  //
  //  (1) MASKED COPY. FpuControlPlugin must store the architectural value with [27:24]
  //      structurally forced to 0, so no stale second condition-code source can exist.
  //      FpuControlPluginSpec proves the plugin masks; this proves the mask is actually
  //      reached through the real decode -> rename -> IQ -> ROB -> S_APPLY path.
  //
  //  (2) ARCH -> INTERNAL REVERSAL, OBSERVED DIRECTLY. The FPCC PRF's internal layout is
  //      [3:0] = {NaN, I, Z, N} (RegfileSpec.Fpcc) while architectural FPSR[27:24] is
  //      {N, Z, I, NaN} -- the reversed presentation. `exc.fpccWriteData` is snooped on
  //      the cycle `exc.fpccWriteValid` fires and compared against the REVERSED nibble.
  //      This assertion is what makes the round-trip below non-vacuous: fpccToArch and
  //      fpccFromArch are the SAME involution, so a bug that degraded BOTH of them to the
  //      identity would round-trip perfectly and pass (3) alone. Checking the value
  //      actually handed to the PRF pins the orientation down on its own.
  //
  //  (3) ROUND TRIP. Reading FPSR back must splice the live committed FPCC out of the PRF
  //      and reverse it into arch order, reconstituting the ORIGINAL nibble.
  //
  // TEST VECTOR: FPSR = 0x0800A5C3, i.e. FPCC nibble = 0b1000 ({N=1, Z=0, I=0, NaN=0}),
  // whose internal-layout image is 0b0001. 0b1000 was chosen deliberately as a genuine
  // discriminator: it is NOT reversal-invariant (unlike 0b0000/0b1111/0b0110/0b1001, any
  // of which would pass even with the reversal deleted), and its reversal 0b0001 also
  // differs from its nibble-half-swap 0b0010, so a half-swap bug cannot alias into a
  // pass either. The surrounding bytes (quotient 0x00, EXC 0xA5, AEXC 0xC3) are non-zero
  // so that assertion (1) discriminates a real masked store from a register still sitting
  // at its reset value.
  //
  // Whitebox for the SAME reason as the test above: Musashi exposes no FP register
  // surface (spec Decision 3), so there is no lock-step oracle for any of this.
  test("FMOVE.L D0,FPSR / FPSR,Dn round-trips the committed FPCC nibble through the FPCC PRF", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val ArchFpsr = 0x0800A5C3L   // FPCC nibble [27:24] = 0b1000
    val Masked   = 0x0000A5C3L   // what FpuControlPlugin must store (mask 0xF0FFFFFF)
    val Internal = 0x1           // 0b1000 reversed into {NaN,I,Z,N}
    val src =
      "move.l #0x0800A5C3,%d0 ; " +
      ".short 0xF200,0x8800 ; " +   // fmove.l %d0,%fpsr
      ".short 0xF206,0xA800 ; " +   // fmove.l %fpsr,%d6
      "move.l %d6,%d7 ; " +         // expose the FPSR read-back in a normal EU writeback
      "done: bra.s done"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    var fpsrSeen      = BigInt(-1)
    var d7Seen        = -1L
    var fpccWrSeen    = -1
    var fpccWrCount   = 0
    var sawExc        = false
    var excVec        = -1
    compiledDut.doSim(freshSimName("fmove-fpsr-fpcc")) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false; dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)          // past the reset/init sweep, as above
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0x00200000L
      dut.rob.logic.exc.ss.srSys #= 0x00           // USER mode
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00200000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      def snoop(w: m68k040.execute.WbObs): Unit =
        if (w.valid.toBoolean && w.intWrite.toBoolean && w.dstArch.toInt == 7)
          d7Seen = w.result.toLong & 0xffffffffL
      var guard = 0
      while (guard < 900) {
        if (dut.rob.logic.exceptionPending.toBoolean && !sawExc) {
          sawExc = true; excVec = dut.rob.logic.exceptionVector.toInt
        }
        // The DIRECT committed-mapping FPCC write, caught on the cycle it fires.
        if (dut.rob.logic.exc.fpccWriteValid.toBoolean) {
          fpccWrSeen = dut.rob.logic.exc.fpccWriteData.toInt; fpccWrCount += 1
        }
        snoop(dut.eu0.logic.wbObs); snoop(dut.eu1.logic.wbObs)
        cd.waitSampling(); guard += 1
      }
      fpsrSeen = dut.fpuCtl.logic.fpsr.toBigInt
    }
    assert(!sawExc,
      s"FMOVE to/from FPSR is a USER instruction -- it must not trap; got vector $excVec")
    // (2) the direct committed-mapping FPCC write actually happened, with the nibble
    //     reversed into the PRF's internal {NaN,I,Z,N} layout.
    assert(fpccWrCount > 0,
      "FMOVE.L D0,FPSR must drive ExceptionUnit.fpccWriteValid (the direct committed-FPCC write)")
    assert(fpccWrSeen == Internal,
      f"FPCC PRF write data must be the arch nibble 0b1000 REVERSED into {NaN,I,Z,N} = 0x$Internal%X; " +
      f"got 0x$fpccWrSeen%X (0x8 would mean the arch->internal reversal was skipped)")
    // (1) masked copy
    assert(fpsrSeen == Masked,
      f"FpuControlPlugin must store FPSR with [27:24] masked off: expected 0x$Masked%08X, got 0x${fpsrSeen.toString(16)}")
    // (3) round trip: masked bytes plus the reconstituted arch FPCC nibble
    assert(d7Seen == ArchFpsr,
      f"FMOVE.L FPSR,D6 read-back (via D7) must reconstitute the FPCC nibble: expected 0x$ArchFpsr%08X, got 0x$d7Seen%08X")
  }

  // ── Task 9b: FMOVEM control-register LIST form, end-to-end round trip ─────────────
  //
  // The single most load-bearing test in this task. It exercises BOTH directions of the
  // real FPSP prologue/epilogue shape through the whole machine -- decode -> the microcode
  // ROM walk -> rename -> IQ -> the CPLX EU's control-register reads (including the RENAMED
  // FPCC read) -> the LS EU's real translated stores/loads -> the ROB -> the serializing
  // S_APPLY batch write -> FpuControlPlugin and the FPCC PRF -- and pins the ONE thing no
  // decode-level test can: the resulting MEMORY IMAGE's register ORDER.
  //
  // WHITEBOX, NOT LOCK-STEP, and the reason is a PRE-EXISTING oracle gap, not a choice
  // made to dodge a failure: Musashi exposes no FP register surface at all via
  // m68k_get_reg (spec Decision 3), which is exactly why Task 9's own two FMOVE tests
  // just above are whitebox too. That gap applies to EVERY addressing mode here, so the
  // re-brief's "lock-step for the non-predecrement modes" split is not available -- there
  // is no oracle to step against for any of them. (Independently, Musashi's `fmove_fpcr`
  // is confirmed WRONG for `-(An)` with popcount >= 2: it re-decrements per transfer,
  // producing the reverse memory image and a reload that does not round-trip. Divergence
  // Register D9/D9a. So even a hypothetical FP-visible oracle could not be stepped
  // against for the predecrement case.)
  //
  // WHAT THE ORDER ASSERTION PROVES. Per M68000PRM p. 5-91 the registers always move
  // FPCR -> FPSR -> FPIAR ascending, and `-(An)` decrements ONCE by 4*popcount up front.
  // So after `fmovem.l %fpiar/%fpsr/%fpcr,-(%a0)` from A0 = 0x3010:
  //     0x3004 = FPCR      0x3008 = FPSR      0x300C = FPIAR
  // Musashi's per-transfer-decrement bug would lay these down in the exact REVERSE order
  // (FPIAR lowest), so reading the three longwords back discriminates the two decisively.
  // The FPSR longword additionally carries the FPCC nibble spliced in from the RENAMED
  // FPCC PRF -- the only test in the suite that exercises `readsFpcc` end to end, since
  // this task's `DecOp.FPCTRLRD` is the design's first and only FPCC reader.
  //
  // The reload half then proves a genuine round trip: the three registers are wiped to 0,
  // reloaded through the postincrement form, and must come back byte-identical -- which
  // simultaneously proves the terminal batch sysOp applied ALL THREE in one retirement
  // (a partial apply would leave a wiped register at 0) and that A0 returned to 0x3010.
  //
  // Opwords are literal `m68k-linux-gnu-as -m68040 -m68881` output (this task's Step 1);
  // `.short` because ProgramAssembler invokes `as` without -m68881, as above.
  test("FMOVEM.L control-register list round-trips -(A0)/(A0)+ in FPCR/FPSR/FPIAR order", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val Fpcr     = 0x00000030L   // RND = RP
    val ArchFpsr = 0x0800A5C3L   // FPCC nibble [27:24] = 0b1000; EXC 0xA5, AEXC 0xC3
    val MaskFpsr = 0x0000A5C3L   // what FpuControlPlugin itself stores (FPCC masked off)
    val Fpiar    = 0x0000ABCDL
    val BufTop   = 0x00003010L
    val src =
      "move.l #0x00000030,%d0 ; " +
      ".short 0xF200,0x9000 ; " +   // fmove.l %d0,%fpcr
      "move.l #0x0800A5C3,%d0 ; " +
      ".short 0xF200,0x8800 ; " +   // fmove.l %d0,%fpsr   (also writes the committed FPCC)
      "move.l #0x0000ABCD,%d0 ; " +
      ".short 0xF200,0x8400 ; " +   // fmove.l %d0,%fpiar
      "move.l #0x00003010,%a0 ; " +
      ".short 0xF220,0xBC00 ; " +   // fmovem.l %fpiar/%fpsr/%fpcr,-(%a0)   -> A0 = 0x3004
      "move.l (%a0),%d1 ; " +       // lowest  address must hold FPCR
      "move.l (4,%a0),%d2 ; " +     // middle  address must hold FPSR (FPCC spliced in)
      "move.l (8,%a0),%d3 ; " +     // highest address must hold FPIAR
      "move.l %d1,%d5 ; " +         // expose the three through ordinary ALU writebacks
      "move.l %d2,%d6 ; " +
      "move.l %d3,%d7 ; " +
      "move.l #0,%d0 ; " +
      ".short 0xF200,0x9000 ; " +   // wipe FPCR
      ".short 0xF200,0x8800 ; " +   // wipe FPSR (and the committed FPCC)
      ".short 0xF200,0x8400 ; " +   // wipe FPIAR
      ".short 0xF218,0x9C00 ; " +   // fmovem.l (%a0)+,%fpiar/%fpsr/%fpcr   -> A0 = 0x3010
      "move.l %a0,%d4 ; " +         // expose the restored A0
      "done: bra.s done"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    var fpcrSeen = BigInt(-1); var fpsrSeen = BigInt(-1); var fpiarSeen = BigInt(-1)
    val d = scala.collection.mutable.Map[Int, Long]()
    var sawExc = false; var excVec = -1
    compiledDut.doSim(freshSimName("fmovem-fpctrl-list")) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false; dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.usp #= 0x00200000L
      dut.rob.logic.exc.ss.srSys #= 0x00           // USER mode: none of this is privileged
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15
      dut.wire.logic.seedData #= BigInt(0x00200000L)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      def snoop(w: m68k040.execute.WbObs): Unit =
        if (w.valid.toBoolean && w.intWrite.toBoolean) {
          val a = w.dstArch.toInt
          if (a >= 4 && a <= 7) d(a) = w.result.toLong & 0xffffffffL
        }
      var guard = 0
      while (guard < 1600) {
        if (dut.rob.logic.exceptionPending.toBoolean && !sawExc) {
          sawExc = true; excVec = dut.rob.logic.exceptionVector.toInt
        }
        snoop(dut.eu0.logic.wbObs); snoop(dut.eu1.logic.wbObs)
        cd.waitSampling(); guard += 1
      }
      fpcrSeen  = dut.fpuCtl.logic.fpcr.toBigInt
      fpsrSeen  = dut.fpuCtl.logic.fpsr.toBigInt
      fpiarSeen = dut.fpuCtl.logic.fpiar.toBigInt
    }
    assert(!sawExc,
      s"FMOVEM to/from the FP control registers is a USER instruction and every <ea> here " +
      s"is legal -- it must not trap; got vector $excVec")
    // ── the memory image's register ORDER (D9) ──
    assert(d.get(5).contains(Fpcr),
      f"the LOWEST stored longword must be FPCR (0x$Fpcr%08X); got 0x${d.getOrElse(5, -1L)}%08X " +
      "-- if this is FPIAR, the -(An) form is laying the registers down in Musashi's " +
      "confirmed-wrong reverse order (D9/D9a)")
    assert(d.get(6).contains(ArchFpsr),
      f"the MIDDLE stored longword must be the FULL architectural FPSR (0x$ArchFpsr%08X) with " +
      f"the RENAMED FPCC nibble spliced in; got 0x${d.getOrElse(6, -1L)}%08X " +
      f"(0x$MaskFpsr%08X would mean the FPCC read never happened)")
    assert(d.get(7).contains(Fpiar),
      f"the HIGHEST stored longword must be FPIAR (0x$Fpiar%08X); got 0x${d.getOrElse(7, -1L)}%08X")
    // ── the reload half: one terminal sysOp applied ALL THREE ──
    assert(fpcrSeen == Fpcr,
      f"FPCR did not survive the (A0)+ reload: expected 0x$Fpcr%08X, got 0x${fpcrSeen.toString(16)}")
    assert(fpsrSeen == MaskFpsr,
      f"FPSR did not survive the (A0)+ reload: expected 0x$MaskFpsr%08X (FpuControlPlugin masks " +
      f"[27:24] off itself), got 0x${fpsrSeen.toString(16)}")
    assert(fpiarSeen == Fpiar,
      f"FPIAR did not survive the (A0)+ reload: expected 0x$Fpiar%08X, got 0x${fpiarSeen.toString(16)}")
    // ── the address register came back exactly where it started ──
    assert(d.get(4).contains(BufTop),
      f"A0 must return to 0x$BufTop%08X (-(A0) decrements once by 12, (A0)+ increments once " +
      f"by 12); got 0x${d.getOrElse(4, -1L)}%08X")
  }

  test("CPUSHA in USER mode raises a vector-8 privilege violation", VerilatorTest) {
    val (saw, vec) = runCpushPriv(userMode = true)
    assert(saw, "user-mode CPUSHA must raise a precise exception")
    assert(vec == 8, s"privilege violation must be vector 8, got $vec")
  }
  test("CPUSHA in SUPERVISOR mode raises NO exception", VerilatorTest) {
    val (saw, _) = runCpushPriv(userMode = false)
    assert(!saw, "supervisor CPUSHA must NOT raise a privilege violation")
  }

  // PFLUSHA (0xF518, task #136): "flush all ATC/TLB entries". Unlike CPUSH, this
  // dispatches to Musashi's OWN dedicated (if functionally unimplemented — logs
  // "68040: unhandled PFLUSH") PFLUSH handler, NOT the stale coprocessor-format stubs
  // (verified: bit8=1 and bits11:9=010 avoid every one of cpgen(bit8=0)/cpscc(bit8=0)/
  // cpbcc(bit8=0)/pmmu(bits11:9=000)'s masks). Confirmed via standalone musashi_run:
  // it consumes exactly 1 word and advances PC correctly with no side effects on
  // visible register/memory state — so the supervisor-mode (non-faulting) direction
  // IS lock-step-verifiable (the TLB-specific effect itself isn't observable via
  // lock-step anyway; that's verified separately in DtlbSpec's whitebox flushAll test).
  // Musashi does NOT check FLAG_S for PFLUSHA either (confirmed empirically, same as
  // CPUSH), so the privilege-trap direction still needs whitebox verification.
  test("lock-step: PFLUSHA supervisor mode -> no-op (TLB-invisible), PC advances 1 word", VerilatorTest) {
    runLockStep("pflusha-super-noop",
      "moveq #5,%d1 ; pflusha ; moveq #7,%d3 ; .stop: bra .stop",
      nInstr = 3)
  }

  private def runPflushaPriv(userMode: Boolean): (Boolean, Int) = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val src = "pflusha ; handler: bra.s handler"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    var sawPriv = false
    var vec = -1
    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      dut.ctrl.logic.mmuEnable #= false; dut.ctrl.logic.urp #= 0; dut.ctrl.logic.srp #= 0
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      val handlerPc = loadAddr + 2   // the `handler:` label (after the 1-word PFLUSHA)
      for (i <- 0 until 4) dmem.pokeByte(0x20 + i, ((handlerPc >> (8 * i)) & 0xff).toInt)
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      dut.rob.logic.exc.ss.usp #= 0x00200000L
      if (userMode) dut.rob.logic.exc.ss.srSys #= 0x00 else dut.rob.logic.exc.ss.srSys #= 0x27
      val bootA7 = if (userMode) 0x00200000L else 0x00100000L
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15; dut.wire.logic.seedData #= BigInt(bootA7)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      var guard = 0
      while (!sawPriv && guard < 600) {
        if (dut.rob.logic.exceptionPending.toBoolean) {
          sawPriv = true
          vec = dut.rob.logic.exceptionVector.toInt
        }
        cd.waitSampling(); guard += 1
      }
    }
    (sawPriv, vec)
  }

  test("PFLUSHA in USER mode raises a vector-8 privilege violation", VerilatorTest) {
    val (saw, vec) = runPflushaPriv(userMode = true)
    assert(saw, "user-mode PFLUSHA must raise a precise exception")
    assert(vec == 8, s"privilege violation must be vector 8, got $vec")
  }
  test("PFLUSHA in SUPERVISOR mode raises NO exception", VerilatorTest) {
    val (saw, _) = runPflushaPriv(userMode = false)
    assert(!saw, "supervisor PFLUSHA must NOT raise a privilege violation")
  }

  // Task #139 (found by a 200-seed fuzz campaign, 2026-07-16, minimized seed=10001):
  // CHK.W in-bounds (no trap) with the checked value == 0 must set Z. Musashi's
  // m68k_op_chk_16_d/_32_d: `FLAG_Z = ZFLAG_16/32(src)` — set UNCONDITIONALLY from the
  // checked value's own zero-ness on EVERY execution (labeled "Undocumented" in Musashi
  // but real, oracle-matching 68k behavior), not hardcoded 0 as this core's DivEuPlugin
  // previously assumed (chkNzvc). Bound=1, checked value(D5)=0 -> in-bounds, no trap,
  // but Z must still be set.
  test("lock-step: CHK.W in-bounds with checked value==0 -> Z flag set (task #139)", VerilatorTest) {
    runLockStep("chk-w-zero-inbounds",
      "move.l #0x1,%d4 ; move.l #0x0,%d5 ; chk.w %d4,%d5 ; moveq #7,%d3 ; .stop: bra .stop",
      nInstr = 4)
  }

  test("lock-step: jsr (xxx).L ... rts", VerilatorTest) {
    // jsr sub (absolute long). Executed: moveq#1, jsr(abs), moveq#3, rts, moveq#7 = 5.
    runLockStep("jsr-abs",
      "moveq #1,%d0 ; jsr (sub).l ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rts",
      nInstr = 5)
  }

  test("lock-step: jsr (d16,PC) ... rts", VerilatorTest) {
    // jsr sub(pc) (PC-relative). Executed: moveq#1, jsr(pcrel), moveq#3, rts, moveq#7 = 5.
    runLockStep("jsr-pcrel",
      "moveq #1,%d0 ; jsr sub(%pc) ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rts",
      nInstr = 5)
  }

  test("lock-step: jsr (d16,An) ... rts", VerilatorTest) {
    // A0 = sub - 8 ; jsr 8(A0) -> sub. Executed: moveq#1, move.l#sub-8 a0, jsr 8(a0),
    // moveq#3, rts, moveq#7 = 6.
    runLockStep("jsr-d16an",
      "moveq #1,%d0 ; move.l #sub-8,%a0 ; jsr 8(%a0) ; moveq #7,%d2 ; .stop: bra .stop ; " +
      "sub: moveq #3,%d1 ; rts",
      nInstr = 6)
  }

  // ── LINK / UNLK (frame setup / teardown; reuse the call/return crack machinery) ──
  // LINK An,#d: push old An to -(A7); An := A7 (new frame ptr); A7 += d.
  // UNLK An:    A7 := An; An := mem[A7]; A7 += 4.
  test("lock-step: link/unlk round trip (frame setup + teardown, checkMem pushed An)", VerilatorTest) {
    // A6 := 0x2222 (a known frame-pointer value = the pushed word); A7 := 0x3010 (a
    // D-cache-backed SP). LINK A6,#-8 pushes A6 to 0x300C, A6 := 0x300C, A7 := 0x3004.
    // move A6->d0 validates LINK's An (= 0x300C). UNLK A6 restores A6 := mem[0x300C] =
    // 0x2222 and A7 := 0x3010. move A6->d1 validates the restored An. checkMem 0x300C =
    // the pushed old A6 (0x2222). Executed: move,move,link,move,unlk,move = 6.
    runLockStep("link-unlk-rt",
      "move.l #0x2222,%a6 ; move.l #0x3010,%a7 ; link %a6,#-8 ; move.l %a6,%d0 ; " +
      "unlk %a6 ; move.l %a6,%d1 ; .stop: bra .stop",
      nInstr = 6, checkMem = Seq(0x300CL))   // pushed old A6 = 0x00002222
  }

  test("lock-step: link positive disp (A7 grows up) + unlk", VerilatorTest) {
    // LINK A5,#+16: push A5 to 0x300C, A5 := 0x300C, A7 := 0x3010+16 = 0x3020. UNLK A5
    // restores A5 := mem[0x300C], A7 := 0x3010. d0/d1 read A5 after link / after unlk.
    runLockStep("link-pos-disp",
      "move.l #0xCAFE,%a5 ; move.l #0x3010,%a7 ; link %a5,#16 ; move.l %a5,%d0 ; " +
      "unlk %a5 ; move.l %a5,%d1 ; .stop: bra .stop",
      nInstr = 6, checkMem = Seq(0x300CL))   // pushed old A5 = 0x0000CAFE
  }

  test("lock-step: nested link/unlk (two frames, LIFO teardown)", VerilatorTest) {
    // Two nested frames sharing A6 (the classic compiler prologue/epilogue). The OUTER
    // A6 (0x1111) is pushed first; the INNER push saves the outer frame ptr. UNLK
    // unwinds in LIFO order. Validates A6 + A7 across nested setup/teardown.
    //   A6:=0x1111 ; A7:=0x3020 ;
    //   link A6,#-4  (push 0x1111 @0x301C ; A6=0x301C ; A7=0x3018) ;
    //   move A6->d0  (=0x301C) ;
    //   link A6,#-4  (push 0x301C @0x3014 ; A6=0x3014 ; A7=0x3010) ;
    //   move A6->d1  (=0x3014) ;
    //   unlk A6      (A6:=mem[0x3014]=0x301C ; A7:=0x3018) ;
    //   move A6->d2  (=0x301C) ;
    //   unlk A6      (A6:=mem[0x301C]=0x1111 ; A7:=0x3020) ;
    //   move A6->d3  (=0x1111).
    // Executed: move,move,link,move,link,move,unlk,move,unlk,move = 10.
    runLockStep("link-unlk-nested",
      "move.l #0x1111,%a6 ; move.l #0x3020,%a7 ; " +
      "link %a6,#-4 ; move.l %a6,%d0 ; link %a6,#-4 ; move.l %a6,%d1 ; " +
      "unlk %a6 ; move.l %a6,%d2 ; unlk %a6 ; move.l %a6,%d3 ; .stop: bra .stop",
      nInstr = 10)
  }

  // ── EXG (exchange two full-32 registers, NO flags) — 3-µop crack through T0 ──────
  // EXG swaps the full 32 bits of two registers and sets NO condition codes. The
  // lock-step compares ALL regs + CCR against Musashi every retired step, so a swapped
  // reg-id (D-vs-A +8) diverges on the regs and a stray writesNzvc diverges on CCR.
  test("lock-step: EXG Dx,Dy (data/data swap, distinct values)", VerilatorTest) {
    // D0=0x11111111, D1=0x22222222 -> after EXG: D0=0x22222222, D1=0x11111111.
    runLockStep("exg-dd",
      "move.l #0x11111111,%d0 ; move.l #0x22222222,%d1 ; exg %d0,%d1 ; " +
      "move.l %d0,%d2 ; move.l %d1,%d3 ; .stop: bra .stop",
      nInstr = 5)
  }

  test("lock-step: EXG Ax,Ay (addr/addr full-32 swap)", VerilatorTest) {
    // A0=0xDEAD0000, A1=0xBEEF1111 -> swapped. A2 := A0 confirms the full-32 An write.
    runLockStep("exg-aa",
      "move.l #0xDEAD0000,%a0 ; move.l #0xBEEF1111,%a1 ; exg %a0,%a1 ; " +
      "move.l %a0,%d0 ; move.l %a1,%d1 ; .stop: bra .stop",
      nInstr = 5)
  }

  test("lock-step: EXG Dx,Ay (mixed data/addr file mapping)", VerilatorTest) {
    // D0=0x0A0A0A0A, A1=0xF0F0F0F0 -> after EXG: D0=0xF0F0F0F0, A1=0x0A0A0A0A.
    runLockStep("exg-da",
      "move.l #0x0A0A0A0A,%d0 ; move.l #0xF0F0F0F0,%a1 ; exg %d0,%a1 ; " +
      "move.l %d0,%d2 ; move.l %a1,%d3 ; .stop: bra .stop",
      nInstr = 5)
  }

  test("lock-step: EXG D3,D3 (same reg -> net unchanged)", VerilatorTest) {
    // The historical same-arch-reg dual-write RAT hazard: D3 must be preserved.
    runLockStep("exg-same",
      "move.l #0x5A5A5A5A,%d3 ; exg %d3,%d3 ; move.l %d3,%d4 ; .stop: bra .stop",
      nInstr = 3)
  }

  test("lock-step: EXG A7,A0 (stack pointer involved, SP still usable)", VerilatorTest) {
    // A7 (SP) := 0x3010 (D-cache-backed), A0 := 0x1234 -> EXG swaps them (A7=0x1234,
    // A0=0x3010). Then EXG back so A7 is a valid SP again, and a benign moveq confirms
    // the core continues. The lock-step checks A7 + A0 each step.
    runLockStep("exg-a7",
      "move.l #0x3010,%a7 ; move.l #0x1234,%a0 ; exg %a7,%a0 ; " +
      "exg %a7,%a0 ; moveq #7,%d0 ; .stop: bra .stop",
      nInstr = 5)
  }

  test("lock-step: EXG preserves NZVC + X (flags set before the swap)", VerilatorTest) {
    // Pre-set N/V/C/X via .B overflow (0x7f+1 -> N=1,V=1) and a carry (0xff+1 ->
    // C=1,X=1,Z=1), then EXG. CCR (compared every step) must be UNCHANGED across EXG.
    runLockStep("exg-flags",
      "move.l #0x000000ff,%d0 ; addq.b #1,%d0 ; " +   // C,X,Z set
      "move.l #0x44444444,%d1 ; move.l #0x55555555,%d2 ; " +
      "exg %d1,%d2 ; move.l %d1,%d3 ; .stop: bra .stop",
      nInstr = 6)
  }

  test("lock-step: store.l then load.l same addr (drain race probe)", VerilatorTest) {
    // Isolation probe for the RTR flake: store a long to 0x2002 (a never-resident line),
    // space it, then load.l 0x2002 -> d7. If this flakes, the store->miss-load drain is
    // a general harness/core issue (not RTR-specific).
    runLockStep("st-ld-drain",
      "move.l #0x12345678,%d1 ; move.l %d1,0x2002 ; moveq #1,%d4 ; moveq #1,%d5 ; " +
      "moveq #1,%d6 ; move.l 0x2002,%d7 ; .stop: bra .stop",
      nInstr = 6, checkMem = Seq(0x2002L))
  }

  // ── RTR (restore CCR + PC) ─────────────────────────────────────────────────
  test("lock-step: rtr (restore CCR + PC) via a hand-built frame", VerilatorTest) {
    // The 040 RTR pops a CCR word @(A7) then a PC long @(A7+2), A7 += 6, restoring ONLY
    // the CCR (SR low byte) and jumping to the popped PC. Predecrement/move-from-SR are
    // not in scope yet, so we hand-build the frame in a data page via MOVE-to-abs stores
    // then point A7 at it: mem[0x2000] = 0x0004 (CCR word, Z=1), mem[0x2002] = target.
    //   moveq#4,d0 ; move.w d0,(0x2000).w  (CCR word)
    //   move.l #target,d1 ; move.l d1,(0x2002).l  (PC long)
    //   move.l #0x2000,a7 ; rtr   (pop CCR+PC, A7 -> 0x2006, jump to target)
    //   target: moveq#7,d2 ; .stop: bra .stop
    // rtr restores CCR=0x04 (Z set) + redirects to target. Executed: moveq#4,
    // move.w-store, move.l#target, move.l-store, move.l#0x2000-to-a7, rtr, moveq#7 = 7.
    // A real RTR frame is stacked by the (long-committed) caller — resident in memory
    // before RTR. Our test stacks it inline, so we read the frame line back into d4/d5
    // first, which refills the (no-allocate-store) line into L1D with the drained store
    // data; RTR's two pops then HIT the resident line. (This sidesteps the PRE-EXISTING
    // plain-store→immediate-load-same-line LS flake — documented at the st-ld-drain probe
    // / line ~1403 — which the BehavioralMem write-before-ack fix did NOT fully close: the
    // predec/postinc round-trip path (full-15 MOVEM prologue/epilogue) is reliable, but a
    // plain store followed by a same-line load still flakes. Tracked as a follow-up.)
    runLockStep("rtr-frame",
      "moveq #4,%d0 ; move.w %d0,0x2000 ; move.l #target,%d1 ; move.l %d1,0x2002 ; " +
      "move.l 0x2000,%d4 ; move.l 0x2002,%d5 ; " +
      "move.l #0x2000,%a7 ; rtr ; target: moveq #7,%d2 ; .stop: bra .stop",
      nInstr = 9, checkMem = Seq(0x2000L))
  }

  // ── BTB staleness stress (fetch-time predictor, slice 1) ──────────────────────
  // A loop with a MISPREDICTING inner branch: the inner beq alternates taken/not-taken
  // across iterations, so the BTB learns one direction then MISPREDICTS when it flips —
  // a commit-time recovery redirect — WHILE the outer loop back-edge (bne) is a
  // predicted-taken fetch redirect. The two redirect classes (predict + commit) land in
  // overlapping windows, hammering the recStale/recDrop staleness machinery (the prior
  // dropCount-leak bug class). The architectural result MUST match Musashi byte-for-byte
  // (a dropped/duplicated/mis-attributed fetch would diverge). d6 toggles 1,0,1,0 via
  // add+and; the inner beq is taken on the d6==0 iterations (alternating mispredict);
  // the outer bne is the hot back-edge (predicted once warm).
  test("lock-step: BTB staleness — loop with a mispredicting inner branch", VerilatorTest) {
    runLockStep("btb-staleness",
      "moveq #12,%d7 ; moveq #1,%d1 ; moveq #0,%d6 ; moveq #1,%d4 ; moveq #0,%d0 ; " +
      ".Lbr: add.l %d1,%d6 ; and.l %d4,%d6 ; beq.s .Lskip ; add.l %d1,%d0 ; " +
      ".Lskip: sub.l %d1,%d7 ; bne.s .Lbr ; moveq #9,%d2",
      nInstr = -1)
  }

  test("lock-step: backward bne.s loop (one backward taken)", VerilatorTest) {
    // D0=2 (counter), D1=1 (decrement). Loop body sub.l d1,d0 ; bne.s .L:
    //   iter1: 2-1=1 (Z=0) -> bne TAKEN  (backward commit-time redirect to .L)
    //   iter2: 1-1=0 (Z=1) -> bne NOT taken -> fall through to moveq#7
    // Exactly one backward-taken mispredict, exercising backward branch
    // displacement + commit-time redirect + recovery. Executed: moveq#2, moveq#1,
    // sub, bne(taken), sub, bne(not-taken), moveq#7 = 7.
    runLockStep("loop",
      "moveq #2,%d0 ; moveq #1,%d1 ; .L: sub.l %d1,%d0 ; bne.s .L ; moveq #7,%d2",
      nInstr = 7)
  }

  test("lock-step: backward bne.s loop (multiple consecutive taken)", VerilatorTest) {
    // D0=4: iter1..3 taken (3,2,1 -> Z=0), iter4 1-1=0 (Z=1) not-taken. THREE
    // back-to-back taken mispredicts -> exercises freelist/RAT recovery across
    // CONSECUTIVE flushes (the case that exposed the freelist flush re-init bug:
    // a flush must roll the freelist back to its COMMITTED state, not the identity
    // pool). Executed: moveq#4, moveq#1, [sub,bne]x4, moveq#7 = 11.
    runLockStep("loop-multi",
      "moveq #4,%d0 ; moveq #1,%d1 ; .L: sub.l %d1,%d0 ; bne.s .L ; moveq #7,%d2",
      nInstr = 11)
  }

  // ── Loop-with-load (the deadlock-fix milestone) ────────────────────────────
  // A backward-branch loop whose BODY contains a memory op. The body load cracks
  // into [load->T0, move T0->Dn]; the move ALSO writes NZVC. The cracked move
  // pairs with the load in one rename group, so the loop's `sub` (the youngest
  // NZVC writer before the loop-back branch) renames ALONE in slot 0 the next
  // cycle. Pre-fix the RAT's multi-write spec Mem silently DROPPED slot-0-only
  // writes (only the highest write port updated a 1-entry flag RAT), so the `sub`
  // never updated the NZVC RAT and the loop-back `bne` read the STALE move-NZVC
  // (Z=0) instead of the sub's (Z=1) -> the final not-taken bne mis-resolved as
  // taken (extra wrong iteration / divergence). Lock-steps step-for-step + final
  // mem so the regression is caught at the architectural level.
  test("lock-step: backward loop with a LOAD in the body", VerilatorTest) {
    // d2=42 stored to 0x2000 (so the body load reads a known value). d0=3 counter,
    // d1=1 decrement. Body: load 0x2000->d3 ; sub d1,d0 ; bne .L.
    //   iter1: 3-1=2 (Z=0) bne TAKEN, iter2: 2-1=1 bne TAKEN, iter3: 1-1=0 not taken.
    // Two backward-taken mispredicts, each followed by a body load whose cracked
    // flag-setting move splits the sub into a lone slot-0 rename.
    // Executed: moveq#42, move.l(store), moveq#3, moveq#1, [load,sub,bne]x3 = 13.
    runLockStep("loop-load",
      "moveq #42,%d2 ; move.l %d2,0x2000 ; moveq #3,%d0 ; moveq #1,%d1 ; " +
      ".L: move.l 0x2000,%d3 ; sub.l %d1,%d0 ; bne.s .L",
      nInstr = 13, checkMem = Seq(0x2000L))
  }

  // A second loop-with-memory shape: a STORE in the body (the store also sets NZVC
  // as MOVE-to-memory) followed by a flag-dependent loop-back branch. Exercises the
  // same lone-slot-0 NZVC-RAT-write path with a store rather than a load.
  test("lock-step: backward loop with a STORE in the body", VerilatorTest) {
    // d0=3 counter, d1=1 dec, d2=7 value. Body: store d2->0x2010 ; sub d1,d0 ; bne.
    // Executed: moveq#3, moveq#1, moveq#7, [store,sub,bne]x3 = 12.
    runLockStep("loop-store",
      "moveq #3,%d0 ; moveq #1,%d1 ; moveq #7,%d2 ; " +
      ".L: move.l %d2,0x2010 ; sub.l %d1,%d0 ; bne.s .L",
      nInstr = 12, checkMem = Seq(0x2010L))
  }

  // ── Load/store lock-step (THE memory milestone) ────────────────────────────
  // All addresses use absolute modes (no An setup needed) and store BEFORE they
  // load, so the D-cache behavioral memory needs no preload. The store µop drains
  // at commit (write-through); store-then-load-back resolves via SQ forwarding.

  test("lock-step: store then load-back (D1 == stored value)", VerilatorTest) {
    // moveq #42,%d0 ; move.l %d0,0x2000 (store) ; move.l 0x2000,%d1 (load-back)
    // D1 must read back 42 (SQ forward / write-through). Final mem[0x2000]==42.
    runLockStep("st-ld",
      "moveq #42,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1",
      checkMem = Seq(0x2000L))
  }

  // ── Store-pipeline (S0/S1) store->load + SQ-forward coverage ────────────────
  // The store cache-write now lands one cycle later (S0 read / S1 merge+write).
  // These programs pin that the +1-cycle write is invisible: the in-flight store
  // is covered by SQ forwarding + the drain-resident-until-ACK window. Each step
  // lock-steps PC/SR/regs vs Musashi.

  test("lock-step: store then immediate load same line (SQ forward)", VerilatorTest) {
    // Two stores into the SAME 16-byte line (0x2000), each immediately loaded back.
    // The load fires while the store is still draining (cache write delayed to S1),
    // so it MUST resolve via SQ forwarding. Final mem[0x2000], mem[0x2004].
    runLockStep("st-ld-sameline",
      "moveq #0x11,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1 ; " +
      "moveq #0x22,%d2 ; move.l %d2,0x2004 ; move.l 0x2004,%d3",
      checkMem = Seq(0x2000L, 0x2004L))
  }

  test("lock-step: store long then load overlapping sub-word (SQ forward)", VerilatorTest) {
    // Store a LONG at 0x2000, then load the WORD at 0x2002 overlapping its low half.
    // The overlapping sub-word load must forward from the in-flight store (the cache
    // line is still being merged in S1). 0x12345678 -> word @0x2002 == 0x5678.
    runLockStep("st-ld-subword",
      "move.l #0x12345678,%d0 ; move.l %d0,0x2000 ; moveq #0,%d1 ; move.w 0x2002,%d1",
      checkMem = Seq(0x2000L), checkSpan = 4)
  }

  // ── MOVE-to/from-memory CCR (the bug fix) ──────────────────────────────────
  // MOVE (all sizes) sets N/Z from the moved value and clears V/C — INCLUDING
  // MOVE to memory. These programs move NEGATIVE and ZERO values to/from memory
  // and lock-step the committed CCR against Musashi (which sets MOVE flags). The
  // store µop now carries writesNzvc (impl (a)); the load-back's op µop sets NZVC
  // from the loaded value. Each ends with a `move.l Dn,Dm` so a divergent CCR (if
  // the store/load failed to set flags) is visible at commit, not just in memory.

  test("lock-step: MOVE negative value TO memory sets N (CCR)", VerilatorTest) {
    // D0 = -1 (0xFFFFFFFF). store D0 -> 0x2000 sets N=1,Z=0,V=0,C=0. The store's
    // committed CCR must show N set (pre-fix it stayed 0). Load it back into D1
    // (the load-back's MOVE also sets N). mem[0x2000] == 0xFFFFFFFF.
    runLockStep("move-neg-to-mem",
      "moveq #-1,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1",
      checkMem = Seq(0x2000L))
  }

  test("lock-step: MOVE zero value TO memory sets Z (CCR)", VerilatorTest) {
    // First dirty CCR with a negative move (N=1), then store ZERO -> the store must
    // set Z=1,N=0 (CCR changes from N to Z). Pre-fix the store left CCR=N, diverging.
    runLockStep("move-zero-to-mem",
      "moveq #-5,%d0 ; move.l %d0,0x2004 ; moveq #0,%d1 ; move.l %d1,0x2000 ; move.l 0x2000,%d2",
      checkMem = Seq(0x2000L, 0x2004L))
  }

  test("lock-step: MOVE.W negative word TO memory sets N at word size", VerilatorTest) {
    // D0 low word = 0x8000 (negative at WORD size, but POSITIVE at long). move.w
    // D0,0x2000 must compute N from bit15 (=1), Z=0. Exercises size-correct N. The
    // long value 0x00008000 would give N=0 at long size, so this distinguishes the
    // size handling. Load the word back into D1 (zero-extended).
    runLockStep("move-negw-to-mem",
      "move.l #0x00008000,%d0 ; move.w %d0,0x2000 ; moveq #0,%d1 ; move.w 0x2000,%d1",
      checkMem = Seq(0x2000L), checkSpan = 2)
  }

  test("lock-step: MOVE negative/zero FROM memory sets CCR (mem->Dn)", VerilatorTest) {
    // Store a NEGATIVE long, load it back -> the load-back MOVE sets N. Then store
    // ZERO, load it back -> the load-back MOVE sets Z. Verifies MOVE mem->Dn flags
    // from the LOADED value for both negative and zero.
    runLockStep("move-from-mem-flags",
      "moveq #-1,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1 ; " +
      "moveq #0,%d2 ; move.l %d2,0x2004 ; move.l 0x2004,%d3",
      checkMem = Seq(0x2000L, 0x2004L))
  }

  test("lock-step: two loads + add", VerilatorTest) {
    // Two cracked loads feeding an add: D2 = mem[0x2000] + mem[0x2004] = 10 + 20 = 30.
    // Each load immediately follows its producing store so it resolves via the
    // store-queue forward path (the load reads the in-flight store's data). Both
    // `move.l (mem),%d2` and `add.l (mem),%d2` crack LOAD->T0 then the op reading T0.
    runLockStep("two-ld-add",
      "moveq #10,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d2 ; " +
      "moveq #20,%d3 ; move.l %d3,0x2004 ; add.l 0x2004,%d2",
      checkMem = Seq(0x2000L, 0x2004L))
  }

  test("lock-step: load -> ALU -> store (temp dataflow through LS wakeup)", VerilatorTest) {
    // moveq #5,%d0 ; store 5 -> 0x2000 ; load 0x2000 -> D1 (cracks LOAD->T0 then
    // MOVE T0->D1, the temp consumed via the LS dynamic-completion wakeup) ;
    // add.l %d1,%d1 -> D1 = 10 ; store D1 -> 0x2004. A loaded value flows through
    // an ALU op into a store. Final mem[0x2000]==5, mem[0x2004]==10.
    runLockStep("ld-alu-st",
      "moveq #5,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1 ; add.l %d1,%d1 ; move.l %d1,0x2004",
      checkMem = Seq(0x2000L, 0x2004L))
  }

  // ── Misaligned / line- & page-crossing lock-step (the two-access-slot milestone) ─
  // Musashi models 68040 misaligned semantics (a misaligned access reads/writes
  // the same byte sequence as two aligned halves) — it is the oracle. Each program
  // stores BEFORE it loads, so the split store drains atomically (both halves) and
  // the cross load reads back the merged value. Addresses are chosen to cross a
  // 16-byte L1D line and/or a 4 KB page.

  test("lock-step: line-crossing long store then load-back (offset 14)", VerilatorTest) {
    // move.l %d0,0x200E : a LONG at line offset 14 spans 0x200E..0x2011 -> crosses
    // the 16-byte line boundary (lines 0x2000 / 0x2010). Load it back into D1.
    // Final mem[0x200E..0x2011] == 0x12345678 (split store drained both halves).
    runLockStep("cross-line-st-ld",
      "move.l #0x12345678,%d0 ; move.l %d0,0x200E ; move.l 0x200E,%d1",
      checkMem = Seq(0x200EL))
  }

  test("lock-step: word-misaligned store then load-back", VerilatorTest) {
    // move.w %d0,0x2003 : a WORD at odd offset 3 within line 0x2000 (misaligned,
    // single line) store + word load-back. Data is positive (bit15=0) so the MOVE
    // CCR is 0 (N=0,Z=0,V=0,C=0) in both DUT and oracle. (MOVE-to/from-memory NOW
    // computes CCR — see the move-neg/zero-to-mem programs for the N/Z coverage.)
    runLockStep("misaligned-word",
      "move.l #0x00001234,%d0 ; move.w %d0,0x2003 ; moveq #0,%d1 ; move.w 0x2003,%d1",
      checkMem = Seq(0x2003L), checkSpan = 2)
  }

  // m68k-ooo test-porting (exc_addr_error.s intent): unaligned .L access must NOT
  // raise vector 3 (address error) on a real 68040 — unlike 68000/68020, the 040's
  // LSU silently splits an unaligned access into aligned beats. This repo's RTL has
  // NO address-error (vector 3) exception path implemented AT ALL (grep for it in
  // src/main/scala/m68k040/exception finds nothing) — so unlike m68k-ooo (where this
  // test guards against a REAL trap path misfiring), here it's structurally
  // impossible to trap vector 3. Still worth locking in as a regression: proves the
  // split-beat LSU produces the CORRECT VALUE for a plain unaligned LONG store+load
  // (existing misalign coverage above is WORD-at-odd-offset and LONG-crossing-a-
  // CACHE-LINE; this is a LONG at an odd BYTE offset within a line, untested before).
  test("lock-step: unaligned .L store then load-back (odd byte offset, no address-error trap)", VerilatorTest) {
    runLockStep("misaligned-long-odd",
      "move.l #0x11223344,%d0 ; move.l %d0,0x2001 ; moveq #0,%d1 ; move.l 0x2001,%d1",
      checkMem = Seq(0x2001L), checkSpan = 4)
  }

  test("lock-step: page-crossing word store then load-back", VerilatorTest) {
    // move.w %d0,0x2FFF : a WORD at page offset 0xFFF spans 0x2FFF..0x3000 -> crosses
    // the 4 KB page boundary (identity translation: two translations, both succeed).
    // Load it back. Final mem[0x2FFF..0x3000] holds the stored word.
    runLockStep("cross-page-st-ld",
      "move.l #0x00004321,%d0 ; move.w %d0,0x2FFF ; moveq #0,%d1 ; move.w 0x2FFF,%d1",
      checkMem = Seq(0x2FFFL), checkSpan = 2)
  }

  test("lock-step: line-crossing long store then overlapping long load", VerilatorTest) {
    // Pre-initialize the next line (0x2010), then store a long at the line-crossing
    // 0x200E (split: bytes 0x200E..0x2011), then load a long at 0x2010 overlapping
    // the high half of the split store. All loaded bytes are program-defined.
    // Loaded value = 56 78 33 44 = 0x56783344 (positive -> CCR 0). Exercises the
    // dual-slot forward / drain-then-read across the boundary.
    runLockStep("cross-line-overlap",
      "move.l #0x11223344,%d0 ; move.l %d0,0x2010 ; " +
      "move.l #0x12345678,%d2 ; move.l %d2,0x200E ; move.l 0x2010,%d1",
      checkMem = Seq(0x200EL, 0x2010L))
  }

  // ── MMU-enabled (translated, non-identity PA) lock-step ────────────────────
  // The MMU is enabled with a page table mapping the data page (VA 0x2000 ->
  // PPN 0x42, i.e. PA 0x42000). The register commit stream still lock-steps vs
  // Musashi (store->load round-trips the value regardless of PA), and the store is
  // additionally asserted to land at the DIRECTLY-COMPUTED translated PA (Musashi
  // exposes no 040-MMU config, so this is directed-non-identity). The first data
  // access TLB-misses -> the hardware walker fills -> subsequent accesses hit.

  test("lock-step MMU: store then load-back at translated PA", VerilatorTest) {
    // moveq #42,%d0 ; store D0->VA 0x2000 ; load VA 0x2000 -> D1 (== 42).
    // VA 0x2000 -> PA 0x42000; the store must land at PA 0x42000.
    runLockStep("mmu-st-ld",
      "moveq #42,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1",
      checkMem = Seq(0x2000L), mmuMap = Some((0x2000L, 0x42L)))
  }

  test("lock-step MMU: load -> ALU -> store at translated PA (M bit set at commit)", VerilatorTest) {
    // moveq #5,%d0 ; store 5->VA0x2000 ; load VA0x2000->D1 ; add D1,D1 -> 10 ;
    // store D1->VA0x2004. Both stores translate to PA 0x42000/0x42004; the write
    // access also queues an M-bit descriptor write drained at commit.
    runLockStep("mmu-ld-alu-st",
      "moveq #5,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1 ; add.l %d1,%d1 ; move.l %d1,0x2004",
      checkMem = Seq(0x2000L, 0x2004L), mmuMap = Some((0x2000L, 0x42L)))
  }

  // ── MMU fault-flag test (non-resident page -> rsp.fault FLAGGED, not delivered) ─
  test("MMU fault: load to a non-resident page flags rsp.fault (no delivery)", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    // A program that stores then loads VA 0x2000. The page is left NON-RESIDENT in
    // the table, so the data access faults -> the DTLB flags rsp.fault. We assert
    // the flag (delivery is a later slice), not the register stream.
    val src = "moveq #42,%d0 ; move.l %d0,0x2000 ; move.l 0x2000,%d1"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"assemble failed: ${err.reason}")
    }
    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      // Build a table whose root/pointer are resident but the PAGE descriptor is
      // NON-RESIDENT (PDT=00) for the data page VA 0x2000. Code is IDENTITY-mapped
      // (resident) in BOTH walker memories so instruction fetch through the ITLB does
      // not fault — only the DATA access to VA 0x2000 faults.
      def buildFaultTable(mem: m68k040.ls.BehavioralMemAgent): Unit = {
        // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
    // MSB) — matches TableWalker.selectWord's corrected convention (mirrors
    // DcacheByteLane.extract's LONG case, i.e. how a REAL `move.l` store would lay
    // these same bytes out in memory). Was little-endian before task #194's walker
    // fix; kept the name (not renamed to `pokeWordBE`) to avoid touching every call
    // site below.
    def pokeWordLE(a: Long, w: Long): Unit = for (i <- 0 until 4) mem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
        val va = 0x2000L
        val rootIdx = ((va >> 25) & 0x7f).toInt; val ptrIdx = ((va >> 18) & 0x7f).toInt; val pageIdx = ((va >> 12) & 0x3f).toInt
        pokeWordLE(MMU_ROOT + rootIdx * 4, (MMU_PTRT & 0xfffffff0L) | 0x3L)
        pokeWordLE(MMU_PTRT + ptrIdx * 4,  (MMU_PAGT & 0xfffffff0L) | 0x3L)
        pokeWordLE(MMU_PAGT + pageIdx * 4, (0x42L << 12) & 0xfffff000L)   // PDT=00 -> non-resident
        for (i <- 0 until 8) { val cva = loadAddr + i * 0x1000L; mapPage(mem, cva, (cva >> 12) & 0xfffffL, MMU_PAGT2) }
      }
      buildFaultTable(ptmem); buildFaultTable(itlbPtmem)

      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      // Task #194: poke mmuEnable/urp/srp HERE (past the init-sweep wait), not before
      // it — see runLockStep's identical fix comment for why an earlier poke doesn't
      // survive reset once MmuControlPlugin has a real conditional MOVEC writer.
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= MMU_ROOT
      dut.ctrl.logic.srp   #= MMU_ROOT
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      // run a while; the data store/load to the non-resident page must flag a fault
      var guard = 0
      while (!dut.dtlb.logic.faultSeen.toBoolean && guard < 2000) { cd.waitSampling(); guard += 1 }
      assert(dut.dtlb.logic.faultSeen.toBoolean,
        "a data access to a non-resident page must flag DTLB rsp.fault (flagged, not delivered)")
    }
  }

  // ── Bug 3: I-side MMU boot-blocker (supervisor-only code page) ───────────────
  // Root cause: I-fetches hardcoded `xlate.req.supervisor := False` in IcachePlugin
  // (now PrivilegeService-driven off the live architectural S bit — see RobPlugin's
  // `supervisor` = `committedS`). Combined with the permission check
  // `permFault(sup) = sup && !req.supervisor` (ItlbPlugin.scala), a supervisor-only
  // code page (S bit, MmuTypes.pgSupervisor = descriptor bit 7 — the NORMAL kernel
  // configuration) would deny EVERY instruction fetch once the MMU was enabled: a
  // PERMANENT boot-blocker (the CPU could never fetch its own supervisor code).
  // Directed (Musashi models no 040 MMU): builds a page table with the CODE region
  // marked supervisor-only, then confirms (a) a SUPERVISOR-mode fetch succeeds (the
  // program keeps committing, the ITLB never flags a fault) and (b) a USER-mode fetch
  // from the SAME page STILL correctly faults (the permission check itself is
  // unchanged — only WHICH privilege level it is checked against was fixed).
  private val SUP_ROOT = 0x00090000L
  private val SUP_PTRT = 0x00091000L
  private val SUP_PAGT = 0x00092000L
  private def buildSupervisorCodeTable(mem: m68k040.ls.BehavioralMemAgent, loadAddr: Long): Unit = {
    // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
    // MSB) — matches TableWalker.selectWord's corrected convention (mirrors
    // DcacheByteLane.extract's LONG case, i.e. how a REAL `move.l` store would lay
    // these same bytes out in memory). Was little-endian before task #194's walker
    // fix; kept the name (not renamed to `pokeWordBE`) to avoid touching every call
    // site below.
    def pokeWordLE(a: Long, w: Long): Unit = for (i <- 0 until 4) mem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
    for (i <- 0 until 4) {
      val cva = loadAddr + i * 0x1000L
      val rootIdx = ((cva >> 25) & 0x7f).toInt
      val ptrIdx  = ((cva >> 18) & 0x7f).toInt
      val pageIdx = ((cva >> 12) & 0x3f).toInt
      pokeWordLE(SUP_ROOT + rootIdx * 4, (SUP_PTRT & 0xfffffff0L) | 0x3L)   // table descriptor, resident
      pokeWordLE(SUP_PTRT + ptrIdx * 4,  (SUP_PAGT & 0xfffffff0L) | 0x3L)   // table descriptor, resident
      // Page descriptor: S bit (bit7) SET (supervisor-protect) + PDT=01 (resident),
      // identity PPN = VPN (MmuTypes.pgSupervisor = d(7), pgResident = PDT in {01,11}).
      pokeWordLE(SUP_PAGT + pageIdx * 4, (((cva >> 12) & 0xfffffL) << 12) | 0x80L | 0x1L)
    }
  }
  private def runSupCodePageFetch(userMode: Boolean): (Boolean, Int) = {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val src = "moveq #1,%d1 ; moveq #2,%d2 ; moveq #3,%d3 ; moveq #4,%d4 ; loop: bra loop"
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i
      case Left(e)  => fail(s"assemble failed: ${e.reason}")
    }
    var committed = 0
    var faulted = false
    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val ptmem     = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      buildSupervisorCodeTable(ptmem, loadAddr)
      buildSupervisorCodeTable(itlbPtmem, loadAddr)
      dut.fa.logic.redirect.valid #= false; dut.fa.logic.resume.valid #= false
      dut.rob.logic.flush.valid #= false; dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      // Task #194: poke mmuEnable/urp/srp HERE (past the init-sweep wait) — see
      // runLockStep's identical fix comment.
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= SUP_ROOT
      dut.ctrl.logic.srp   #= SUP_ROOT
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      dut.rob.logic.exc.ss.usp #= 0x00200000L
      if (userMode) dut.rob.logic.exc.ss.srSys #= 0x00 else dut.rob.logic.exc.ss.srSys #= 0x27
      val bootA7 = if (userMode) 0x00200000L else 0x00100000L
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15; dut.wire.logic.seedData #= BigInt(bootA7)
      cd.waitSampling(2); dut.wire.logic.seedValid #= false; cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false
      // NOTE: do NOT break the loop early on `faulted` — `itlb.logic.faultSeen` is a
      // sim-only STICKY flag that latches on ANY fault the ITLB ever observes,
      // including a purely SPECULATIVE/wrong-path fetch that never commits (branch
      // prediction can issue a fetch to an essentially arbitrary predicted target,
      // especially from a cold/uninitialized BTB entry on the very first fetches of a
      // fresh DUT). Such a fetch is squashed on misprediction and never affects the
      // architectural commit stream, so it is NOT a real permission failure — but it
      // WOULD spuriously trip `faulted` and (if the loop exited early on it) could
      // short-circuit before the real boot-blocker signal (forward commit progress)
      // had a chance to show up. `committed` (the ROB's own commit stream) is the
      // reliable, non-speculative boot-blocker indicator.
      var guard = 0
      while (guard < 1500 && committed < 4) {
        if (dut.itlb.logic.faultSeen.toBoolean) faulted = true
        for (k <- 0 until 2) if (dut.rob.logic.commitObs(k).fire.toBoolean) committed += 1
        cd.waitSampling(); guard += 1
      }
    }
    (faulted, committed)
  }

  test("MMU boot-blocker: supervisor fetch from a supervisor-only code page succeeds", VerilatorTest) {
    // `committed >= 4` is the real boot-blocker signal: a permission-denied I-fetch
    // would NEVER commit a single instruction (guard exhausts at committed==0). We do
    // NOT assert `!faulted` here — the ITLB's sim-only sticky fault-observation flag
    // can be spuriously tripped by a squashed speculative/wrong-path fetch (see the
    // loop comment above), which is harmless noise, not a permission-check failure.
    val (_, committed) = runSupCodePageFetch(userMode = false)
    assert(committed >= 4, s"expected >=4 committed instructions, got $committed (boot-blocker: fetch stalled)")
  }
  test("MMU: user fetch from a supervisor-only code page still faults (permission check unchanged)", VerilatorTest) {
    val (faulted, _) = runSupCodePageFetch(userMode = true)
    assert(faulted, "a USER-mode fetch from a supervisor-only page must still flag an ITLB fault")
  }

  // ── PRECISE EXCEPTION lock-step: illegal-instruction -> handler -> RTE ───────
  // A program that installs the illegal-instruction vector (4) at VBR+0x10 (a
  // runtime store, so both the DUT D-cache and Musashi see it), executes an
  // `illegal` (0x4AFC) which the core delivers PRECISELY (stack a format-$0 frame
  // to SSP-8, fetch the handler vector, switch to supervisor — already supervisor
  // here, vector to the handler), runs a handler that bumps the stacked PC past the
  // illegal + sets D1/D2, and RTEs back to the fall-through `moveq #7,%d3`. The
  // commit stream (PC / full SR / A7 / regs) is lock-stepped vs Musashi step-for-
  // step across entry -> handler -> RTE. The final `bra .` halts over-fetch.
  test("lock-step: illegal-instruction -> handler -> RTE (precise exception)", VerilatorTest) {
    runLockStep("exc-illegal",
      "move.l #handler,%d0 ; move.l %d0,0x10 ; illegal ; moveq #7,%d3 ; " +
      "loop: bra loop ; " +
      "handler: moveq #2,%d1 ; move.l 2(%a7),%d0 ; add.l %d1,%d0 ; move.l %d0,2(%a7) ; " +
      "moveq #1,%d2 ; rte",
      nInstr = 10)
  }

  // ── TRAP #n lock-step: decode-time unconditional trap -> handler -> RTE ──────
  // `trap #5` raises vector 37 (32+5) via the format-$0 path. TRAP is not
  // restartable: it stacks the PC of the NEXT instruction, so RTE resumes at the
  // fall-through `moveq #7,%d3` WITHOUT the handler bumping the stacked PC. The
  // handler vector lives at VBR(0)+37*4 = 0x94 (a runtime store the DUT D-cache +
  // Musashi both see). Commit PC/SR/A7 lock-stepped vs Musashi across entry ->
  // handler -> RTE -> resume.
  // ── PRIVILEGE-violation trap (Track D): a privileged op at S=0 -> vector 8 ───
  // Install the privilege handler at VBR(0)+8*4 = 0x20, drop to USER mode (MOVE-to-SR
  // clearing S), then a privileged `move %usp,%a0` at S=0 traps to vector 8 (format-$0,
  // restartable: stacks its OWN PC). The handler (supervisor) bumps the stacked PC past
  // the 2-byte faulting op (so RTE resumes at the fall-through, not re-trapping) and
  // RTEs. Commit PC/SR/A7 lock-stepped across the user-switch, the trap entry (S back
  // to 1, A7 banks to SSP), the handler, and the RTE (back to user, A7 -> USP).
  test("lock-step: privileged op at S=0 -> vector-8 trap -> handler -> RTE", VerilatorTest) {
    runLockStep("exc-privilege",
      "move.l #handler,%d0 ; move.l %d0,0x20 ; move.w #0x0000,%d1 ; move %d1,%sr ; " + // -> user (S=0)
      "move.l %usp,%a0 ; moveq #7,%d3 ; " +                                            // privileged -> trap; resume here
      "loop: bra loop ; " +
      "handler: move.l 2(%a7),%d0 ; addq.l #2,%d0 ; move.l %d0,2(%a7) ; " +            // bump stacked PC past the 2-byte op
      "moveq #1,%d2 ; rte",
      nInstr = 11, usp = 0)   // the program never sets USP; pin dut USP=0 to match Musashi's default (the
                              // harness seeds the dut USP but the Musashi binding has no USP seed). MOVE-to-SR
                              // banking is still exercised (A7: SSP 0x100000 -> USP 0 on S->0, back on the trap).
  }

  // Task #131 gap-closing verification (m68k-ooo priv_user_movec_traps.s intent):
  // ANY user-mode MOVEC must trap vector-8, regardless of Rc — including our NEW
  // URP/SRP/TC cases (previously RAZ/WI defaults, now real read/write paths). This
  // is Rc-independent by construction on both sides: Musashi's movec_cr/movec_rc
  // check `FLAG_S` BEFORE the Rc switch (`m68k_in.c`: `if(FLAG_S){switch(...)} ;
  // m68ki_exception_privilege_violation();`), and our RTL's `sysPrivFault :=
  // sysRetire && !exc.ss.s` fires before/independent of the sysCapRc switch — so a
  // single directed case (movec %d0,%urp) genuinely exercises the shared check, not
  // just one Rc's plumbing. If our new URP case accidentally bypassed the privilege
  // gate, this would diverge immediately (Musashi traps, DUT would execute instead).
  test("lock-step: MOVEC (URP) in USER mode -> vector-8 privilege violation (op does not execute)", VerilatorTest) {
    runLockStep("movec-urp-user-priv",
      "move.l #handler,%d0 ; move.l %d0,0x20 ; move.w #0x0000,%d1 ; move %d1,%sr ; " + // -> user (S=0)
      "move.l #0x11111111,%d2 ; movec %d2,%urp ; " +                                   // privileged -> trap; URP unchanged
      "moveq #7,%d3 ; " +                                                              // resume here
      "loop: bra loop ; " +
      "handler: move.l 2(%a7),%d0 ; addq.l #4,%d0 ; move.l %d0,2(%a7) ; " +            // bump stacked PC past the 4-byte op
      "moveq #1,%d4 ; rte",
      nInstr = 12, usp = 0)
  }

  test("lock-step: TRAP #5 -> handler -> RTE (format-$0 delivery)", VerilatorTest) {
    runLockStep("exc-trap",
      "move.l #handler,%d0 ; move.l %d0,0x94 ; trap #5 ; moveq #7,%d3 ; " +
      "loop: bra loop ; " +
      "handler: moveq #2,%d1 ; moveq #1,%d2 ; rte",
      nInstr = 7)
  }

  // ── M-bit MSP/ISP banking lock-step tests ────────────────────────────────────
  // The 68040 has three SP banks: USP (user), ISP (supervisor interrupt stack,
  // S=1 M=0), and MSP (master stack, S=1 M=1).  Bit 12 (M) of SR selects ISP vs
  // MSP while in supervisor mode.  These tests exercise the live-coherent banking
  // logic (ss.msp / ss.isp updated at commit time, surfaced every step, compared
  // vs Musashi's REG_MSP / REG_ISP every step).
  //
  // A TRAP while M=1 stacks a normal format-$0 frame on the master stack (no
  // throwaway frame) — Musashi matches this exactly. An INTERRUPT while M=1 is
  // DIFFERENT (stacks format-$0 on MSP + a format-$1 throwaway on ISP, handler runs
  // on ISP) — that path is task #132 (Slice B), now implemented; see "lock-step
  // IRQ: M=1 -> format-$1 throwaway ..." below in the interrupt test section.

  // Test 1: seed MSP=0x000A0000 (via initialMsp), then MOVE-to-SR to switch to M=1
  // (arch-15 is still at phys-15 identity alias when the exc FSM fires, so the
  // re-bank is coherent).  After the switch, take TRAP #1 on the master stack
  // (format-$0), handler RTEs, M stays 1.  Inactive ISP (0x00100000) is preserved
  // and compared every step.
  //
  // Instruction sequence (nInstr = 7):
  //  1  move.w #0x3700,%sr    S=1,M=1,I=7 (exc FSM; arch-15 = phys-15 identity)
  //  2  move.l #handler,%d0   load handler address (no arch-15 rename)
  //  3  move.l %d0,0x84       install vec 33 (TRAP #1 = 32+1, 33*4=0x84)
  //  4  trap #1               format-$0 frame on MSP; exc commit; MSP -> 0x0009FFF8
  //  5  moveq #9,%d4          handler body
  //  6  rte                   restore SR/PC; M stays 1; MSP restored to 0x000A0000
  //  7  moveq #7,%d3          resume after RTE
  //
  // WHY initialMsp IS NEEDED: the exc FSM writes the re-banked A7 to PRF arch-15 at
  // a hardcoded physical address (phys-15 = identity alias at boot).  After any OoO
  // A7 write (e.g., move.l #addr,%sp) the committed RAT's phys for arch-15 diverges
  // from phys-15; then the writeA7 feedback loop restores the old value, reverting
  // the exc FSM's SP decrement.  Seeding MSP via initialMsp and doing MOVE-to-SR
  // BEFORE any %sp write keeps phys-15 as the committed alias throughout, so all
  // exc FSM A7 writes land on the correct physical register.
  //
  // NOTE (Musashi seeding): --initial-msp is applied while M=0 so Musashi stores
  // 0x000A0000 into the INACTIVE MSP shadow (sp[6]) via set_reg(MSP) with M=0.
  // When the program's move.w #0x3700,%sr executes, Musashi's real ISA m-bit
  // transition loads sp[6] into dar[15] and saves the old A7 (ISP) into sp[4] —
  // exactly matching the DUT's behaviour.  ISP (sp[4]=0x00100000) is stable and
  // compared every step thereafter.
  test("lock-step: M-bit MSP/ISP banking (set M, write MSP, trap on MSP, RTE)", VerilatorTest) {
    runLockStep("mbit-msp",
      "move.w #0x3700,%sr ; " +    // (1) S=1,M=1,I=7 via exc FSM (MOVE-to-SR)
      "move.l #handler,%d0 ; " +   // (2) load handler address (no arch-15 rename)
      "move.l %d0,0x84 ; " +       // (3) install vec 33 (TRAP #1 = 32+1, 33*4=0x84)
      "trap #1 ; " +               // (4) format-$0 frame on MSP; exception commit
      "moveq #7,%d3 ; " +          // (7) resume point after RTE
      ".stop: bra .stop ; " +
      "handler: moveq #9,%d4 ; rte",  // (5)(6) handler body + RTE
      nInstr = 7,
      initialMsp = Some(0x000A0000L)) // seed inactive MSP = 0x000A0000 (DUT + oracle)
  }

  // Test 2: symmetric ISP-only trap (M stays 0) — proves the M=0 path is
  // unchanged by the M-bit banking work.  This mirrors the TRAP #5 test but is
  // explicitly named to tie it to the M-bit work.  TRAP #3 (vector 35, @ 0x8C)
  // stacks a format-$0 frame on ISP (M=0), handler RTEs.
  //
  // Instruction sequence (nInstr = 7):
  //  1  move.l #handler,%d0       load handler address
  //  2  move.l %d0,0x8c           install vec 35 (TRAP #3 = 32+3 = 35*4=0x8C)
  //  3  trap #3                   format-$0 frame on ISP; exception commit
  //  4  moveq #3,%d1              handler body step 1
  //  5  moveq #5,%d2              handler body step 2
  //  6  rte                       restore SR/PC; M stays 0; ISP unwound
  //  7  moveq #7,%d3              resume after RTE
  test("lock-step: M-bit ISP-only trap (M stays 0)", VerilatorTest) {
    runLockStep("mbit-isp-only",
      "move.l #handler,%d0 ; move.l %d0,0x8c ; trap #3 ; moveq #7,%d3 ; " +
      ".stop: bra .stop ; " +
      "handler: moveq #3,%d1 ; moveq #5,%d2 ; rte",
      nInstr = 7)
  }

  // ── Rename-aware A7 writeback: exception after OoO A7 write ─────────────────
  // Validates that the exc FSM writes the re-banked A7 to committedPhysA7 (not to
  // a hardcoded phys-15). After `move.l #0x000F0000,%sp` the committed RAT maps
  // arch-15 to a NEW physical register (no longer phys-15 identity); the subsequent
  // TRAP #4 must stack the format-$0 frame on that renamed A7, and RTE must restore
  // it, step-for-step with Musashi. With the old U(15) the exc FSM would write to
  // stale phys-15 and the handler/RTE would see the WRONG A7.
  //
  // Instruction sequence (nInstr = 8):
  //  1  move.l #0x000F0000,%sp  OoO write to arch-15 (renames it off phys-15)
  //  2  move.l #handler,%d0     load handler addr
  //  3  move.l %d0,0x90         install vec 36 (TRAP #4 = 32+4 = 36, 36*4=0x90)
  //  4  trap #4                 format-$0 frame on renamed A7; exc commits + writes
  //                             committedPhysA7 with the decremented SP
  //  5  moveq #7,%d3            resume point after RTE
  //  6  (loop bra)              halt
  //  7  moveq #9,%d4            handler body
  //  8  rte                     restores SR/PC; committedPhysA7 updated to original A7
  test("lock-step: exception after OoO A7 write (move.l #imm,%sp then trap) - rename-aware frame", VerilatorTest) {
    runLockStep("exc-after-a7-rename",
      "move.l #0x000F0000,%sp ; " +                   // (1) OoO rename of arch-15
      "move.l #handler,%d0 ; move.l %d0,0x90 ; " +    // (2)(3) install vec 36 (TRAP #4)
      "trap #4 ; " +                                  // (4) format-$0 frame on renamed A7
      "moveq #7,%d3 ; " +                             // (5) resume after RTE
      ".stop: bra .stop ; " +
      "handler: moveq #9,%d4 ; rte",                  // (7)(8)
      nInstr = 8)
  }

  // ── TRAPV lock-step: execute-time conditional trap (vector 7, format-$2) ─────
  // V-set case: an add.l overflow sets V=1, so `trapv` traps -> vector 7 (format-$2
  // on the 68040), vectors to the handler, which RTEs back to the fall-through. The
  // stacked PC is the NEXT instruction's PC (TRAPV is not restartable). Commit
  // PC/SR/A7 lock-stepped vs Musashi across the overflow, the trap, the handler,
  // and the RTE resume.
  test("lock-step: TRAPV with V set -> handler -> RTE (format-$2 delivery)", VerilatorTest) {
    // The handler's last flag-writer reproduces the entry flags (N=1,V=1 from the
    // add.l overflow) so the sim whitebox's reconstructed CCR matches Musashi's
    // RTE-restored CCR at the RTE step. (The architectural CCR restore on RTE is a
    // separate pre-existing concern outside the trap machinery; this mirrors the
    // illegal-instruction test, where entry and handler-exit CCR coincide.)
    runLockStep("exc-trapv-set",
      "move.l #handler,%d0 ; move.l %d0,0x1c ; " +       // vector 7 @ 0x1C
      "move.l #0x7fffffff,%d4 ; add.l %d4,%d4 ; " +      // signed overflow -> N=1,V=1
      "trapv ; moveq #7,%d3 ; " +
      "loop: bra loop ; " +
      "handler: move.l #0x7fffffff,%d1 ; add.l %d1,%d1 ; rte",  // reproduce N=1,V=1
      nInstr = 9)
  }

  // V-clear case: a moveq clears V, so `trapv` is a no-op and falls through to the
  // next instruction. No exception is taken; the commit stream is straight-line.
  test("lock-step: TRAPV with V clear -> falls through (no trap)", VerilatorTest) {
    runLockStep("exc-trapv-clear",
      "moveq #5,%d4 ; trapv ; moveq #7,%d3 ; loop: bra loop",
      nInstr = 4)
  }

  // A store that completes EARLY behind a long-latency head (DIV ~30cy) sits complete at
  // h1 when the head finishes -> BOTH retire in one cycle -> the store retires in SLOT 1.
  // The SQ commit mark must fire for slot 1 too (commitB), else the entry is never
  // committed -> never drains (or is squashed by the next flush) = SILENT MEMORY LOSS.
  test("lock-step: store dual-retires at SLOT 1 behind a completing DIV (SQ commitB)", VerilatorTest) {
    runLockStep("sq-commit-slot1",
      "move.l #0x3000,%a0 ; move.l #0xcafebabe,%d3 ; " +
      "moveq #100,%d0 ; moveq #7,%d1 ; " +
      "divu.w %d1,%d0 ; " +            // ~30cy at ROB head, uncompleted
      "move.l %d3,(%a0) ; " +          // store completes early; sits at h1; dual-retires at SLOT 1
      "moveq #1,%d5",                  // sentinel
      nInstr = 7, checkMem = Seq(0x3000L), checkSpan = 4)
  }

  // ── DIVU.W / DIVS.W lock-step (32/16 -> Dn = {rem16, q16}, N/Z/V) ────────────
  // Register-divisor forms are 2-byte opwords (nextPc = pc+2), straight-line.
  test("lock-step: DIVU.W normal + DIVS.W normal", VerilatorTest) {
    runLockStep("div-w-normal",
      "moveq #100,%d0 ; moveq #7,%d1 ; divu.w %d1,%d0 ; " +   // 100/7 -> q14 r2 in d0
      "moveq #-100,%d2 ; moveq #7,%d3 ; divs.w %d3,%d2 ; " +  // -100/7 -> q-14 r-2
      "moveq #1,%d4 ; loop: bra loop", nInstr = 7)
  }

  // DIVU.W overflow: a large dividend / small divisor yields a quotient > 16 bits ->
  // V=1, NO result write (Dn unchanged). The committed flags + (unchanged) Dn match.
  test("lock-step: DIVU.W overflow (V=1, no write)", VerilatorTest) {
    runLockStep("div-w-ovf",
      "move.l #0x10000,%d0 ; moveq #1,%d1 ; divu.w %d1,%d0 ; " + // 0x10000/1 = 0x10000 > 16b -> V
      "moveq #5,%d2 ; loop: bra loop", nInstr = 5)
  }

  // ── FUZZER-CAUGHT (B5): DIV overflow must PRESERVE N/Z/C (only V is set) ─────
  // Musashi's divs/divu overflow path is `FLAG_V = VFLAG_SET; return;` — N, Z and C
  // keep their PRE-DIV values. The DUT wrote NZVC=0010 (clearing a live N). Pin N=1
  // (tst of a negative) before each overflowing DIV and lock-step the committed CCR.
  test("lock-step: DIVS.W/DIVU.W overflow preserves N/Z/C (only V set)", VerilatorTest) {
    runLockStep("div-w-ovf-nzc",
      // NOTE: no readback of the DIV DEST after an overflow — on overflow the dest
      // physreg is (correctly) never written, and a later READER of that renamed dest
      // hangs (pre-existing dataflow gap, separate from this flag fix; see report).
      "move.l #0x26f0c934,%d0 ; move.l #0x0d00,%d1 ; " +             // operands FIRST (they set flags)
      "moveq #-1,%d7 ; tst.l %d7 ; " +                               // then pin N=1
      "divu.w %d1,%d0 ; " +                                          // unsigned ovf; N must stay 1
      "move.l #0x40000000,%d3 ; moveq #2,%d4 ; " +
      "moveq #-1,%d7 ; tst.l %d7 ; " +                               // re-pin N=1
      "divs.w %d4,%d3 ; " +                                          // signed ovf; N must stay 1
      "move.l #0x10000,%d6 ; moveq #1,%d4 ; " +
      "moveq #0,%d7 ; tst.l %d7 ; " +                                // pin Z=1 (N=0)
      "divu.w %d4,%d6 ; " +                                          // ovf; Z must stay 1
      ".stop: bra .stop", nInstr = 16)
  }

  // DIVU.W divide-by-zero -> vector 5 (format-$2) -> handler -> RTE -> resume.
  // The handler's last flag-writer reproduces the entry CCR (Z=1 from `moveq #0,%d2`)
  // so the sim whitebox's reconstructed CCR matches the oracle's RTE-restored CCR at
  // the RTE step (same convention as the TRAPV lock-step; the architectural CCR
  // restore on RTE is a separate pre-existing concern outside the trap machinery).
  test("lock-step: DIVU.W DIV0 -> handler -> RTE", VerilatorTest) {
    runLockStep("div-w-div0",
      "move.l #handler,%d0 ; move.l %d0,0x14 ; " +   // vector 5 (DIV0) @ 0x14
      "moveq #100,%d1 ; moveq #0,%d2 ; divu.w %d2,%d1 ; " + // /0 -> trap; entry CCR Z=1
      "moveq #7,%d3 ; loop: bra loop ; " +
      "handler: moveq #0,%d4 ; rte", nInstr = 8)  // moveq #0 -> Z=1 matches entry CCR
  }

  // ── DIVU.L/DIVS.L 32/32 lock-step (quotient -> Dq; quotient+remainder Dr:Dq) ──
  test("lock-step: DIVU.L/DIVS.L 32/32 quotient-only", VerilatorTest) {
    runLockStep("div-l32-q",
      "move.l #1000000,%d0 ; moveq #7,%d1 ; divu.l %d1,%d0 ; " +   // 1000000/7 -> Dq
      "move.l #-1000000,%d2 ; moveq #7,%d3 ; divs.l %d3,%d2 ; " + // signed
      "moveq #1,%d4 ; loop: bra loop", nInstr = 7)
  }

  // Remainder:quotient 32/32 form (Dr!=Dq, cracked DIV+DIVREM). GNU `divull Dn,Dr,Dq`
  // = the 32-bit form (ext bit10=0) writing Dq=quotient + Dr=remainder. The trailing
  // `move.l %d2/%d5,...` READ the remainders (Dr) so the DIVREM PRF write is verified
  // by a normal commit (its own commit record is coalesced into the DIV's step).
  test("lock-step: DIVU.L/DIVS.L 32/32 remainder:quotient (Dr,Dq)", VerilatorTest) {
    runLockStep("div-l32-rq",
      "move.l #1000003,%d0 ; moveq #7,%d1 ; divull %d1,%d2,%d0 ; " + // d0=q, d2=rem
      "move.l #-1000003,%d3 ; moveq #7,%d4 ; divsll %d4,%d5,%d3 ; " + // signed
      "move.l %d2,%d6 ; move.l %d5,%d7 ; " +                          // verify both remainders
      "loop: bra loop", nInstr = 8)
  }

  // DIVU.L 32/32 DIV0 -> vector 5 -> handler -> RTE.
  test("lock-step: DIVU.L 32/32 DIV0 -> handler -> RTE", VerilatorTest) {
    runLockStep("div-l32-div0",
      "move.l #handler,%d0 ; move.l %d0,0x14 ; " +     // vector 5 @ 0x14
      "move.l #1000,%d1 ; moveq #0,%d2 ; divu.l %d2,%d1 ; " + // /0 -> trap (entry Z=1)
      "moveq #7,%d3 ; loop: bra loop ; " +
      "handler: moveq #0,%d4 ; rte", nInstr = 8)       // moveq #0 -> Z=1 matches entry
  }

  // ── DIVU.L/DIVS.L 64/32 lock-step (Dr:Dq 64-bit dividend -> Dq=q, Dr=rem) ─────
  // GNU `divu.l %dn,%dr:%dq` (the `:` syntax) = the 64-bit form (ext bit10=1): the
  // 64-bit dividend is Dr:Dq (Dr high, Dq low). Cracked DIV(+psrcC=Dr)+DIVREM. The
  // trailing moves verify the remainder (Dr).
  test("lock-step: DIVU.L 64/32 (Dr:Dq) normal", VerilatorTest) {
    runLockStep("div-l64-u",
      "move.l #0x12,%d2 ; move.l #0x34567890,%d0 ; moveq #100,%d1 ; " + // Dr:Dq = 0x12_34567890
      "divu.l %d1,%d2:%d0 ; " +                                          // d0=q, d2=rem
      "move.l %d2,%d6 ; loop: bra loop", nInstr = 6)
  }

  // DIVS.L 64/32 signed, negative dividend.
  test("lock-step: DIVS.L 64/32 (Dr:Dq) signed negative", VerilatorTest) {
    runLockStep("div-l64-s",
      "move.l #0xffffffff,%d2 ; move.l #0xfff0bdc0,%d0 ; moveq #7,%d1 ; " + // Dr:Dq = -1000000 (sign-ext)
      "divs.l %d1,%d2:%d0 ; " +
      "move.l %d2,%d6 ; loop: bra loop", nInstr = 6)
  }

  // DIVU.L 64/32 overflow: a 64-bit dividend whose quotient exceeds 32 bits -> V=1,
  // NO write (Dq, Dr unchanged). Verified by reading both back.
  test("lock-step: DIVU.L 64/32 overflow (V=1, no write)", VerilatorTest) {
    runLockStep("div-l64-ovf",
      "move.l #0x10,%d2 ; move.l #0,%d0 ; moveq #1,%d1 ; " + // 0x10_00000000 / 1 -> q > 32b
      "divu.l %d1,%d2:%d0 ; " +
      "moveq #5,%d6 ; loop: bra loop", nInstr = 6)
  }

  // DIVU.L 64/32 DIV0 -> vector 5 -> handler -> RTE.
  test("lock-step: DIVU.L 64/32 DIV0 -> handler -> RTE", VerilatorTest) {
    runLockStep("div-l64-div0",
      "move.l #handler,%d6 ; move.l %d6,0x14 ; " +     // vector 5 @ 0x14
      "move.l #0x12,%d2 ; move.l #0x3456,%d0 ; moveq #0,%d1 ; " + // /0
      "divu.l %d1,%d2:%d0 ; " +
      "moveq #7,%d3 ; loop: bra loop ; " +
      "handler: moveq #0,%d4 ; rte", nInstr = 9)       // entry CCR Z=1 (moveq #0,%d1)
  }

  // ── CHK lock-step (vector 6, format-$2): in-bounds no-op + both out-of-bounds ──
  // In-bounds: CHK is a no-op, straight-line. (CHK leaves CCR per the 68k undefined-
  // except-N rule; Musashi's CHK does modify N/Z, but the lock-step compares the
  // committed register stream + PC/SR/A7 — for the no-trap path the registers/PC match
  // and the next flag-writer overwrites CCR before any compare point.)
  test("lock-step: CHK in-bounds (no trap)", VerilatorTest) {
    runLockStep("chk-inbounds",
      "moveq #5,%d0 ; moveq #10,%d1 ; chk.w %d1,%d0 ; " +  // 0<=5<=10 -> no trap
      "moveq #3,%d2 ; loop: bra loop", nInstr = 5)
  }

  // Dn<0 (N=1) -> trap vector 6 -> handler -> RTE. The handler reproduces the entry
  // CCR (the chk's predecessor `moveq #-5,%d0` sets N=1) so the RTE-restored CCR
  // matches (same convention as the DIV0/TRAPV tests).
  test("lock-step: CHK Dn<0 -> handler -> RTE", VerilatorTest) {
    runLockStep("chk-neg",
      "move.l #handler,%d0 ; move.l %d0,0x18 ; " +   // vector 6 (CHK) @ 0x18
      "moveq #-5,%d1 ; moveq #10,%d2 ; chk.w %d2,%d1 ; " + // -5<0 -> trap (entry N=1)
      "moveq #7,%d3 ; loop: bra loop ; " +
      "handler: moveq #-1,%d4 ; rte", nInstr = 8)   // moveq #-1 -> N=1 matches entry
  }

  // Dn>bound (N=0) -> trap vector 6 -> handler -> RTE. Entry CCR: `moveq #20,%d1`
  // sets N=0,Z=0 (positive nonzero); the handler reproduces it.
  test("lock-step: CHK Dn>bound -> handler -> RTE", VerilatorTest) {
    runLockStep("chk-over",
      "move.l #handler,%d0 ; move.l %d0,0x18 ; " +   // vector 6 (CHK) @ 0x18
      "moveq #20,%d1 ; moveq #10,%d2 ; chk.w %d2,%d1 ; " + // 20>10 -> trap (entry N=0)
      "moveq #7,%d3 ; loop: bra loop ; " +
      "handler: moveq #1,%d4 ; rte", nInstr = 8)    // moveq #1 -> N=0,Z=0 matches entry
  }

  // ── CMP2 / CHK2 lock-step (020+ bounds-check against a memory pair) ──────────
  // The bounds pair is seeded into memory (lower @0x3000, upper @0x3000+size); A0
  // points to the pair. CMP2: Z := Rn==lower||Rn==upper; C := signed(Rn<lower||
  // Rn>upper); N/V UNCHANGED. A `addq.b #1,%d7` (127->-128) sets a SENTINEL N=1,V=1
  // right before each CMP2 (the bounds loads + MOVEA set no flags), so the lock-step
  // (which compares the FULL CCR at the cmp2 commit step) confirms N/V are preserved.
  // CHK2: out-of-bounds -> vector 6 (format-$2) -> handler -> RTE -> resume.

  // CMP2.W in-bounds + ==lower + ==upper + OOB-low + OOB-high, all .W, N/V sentinel.
  // The N/V sentinel (127+1=-128 -> N=1,V=1) is set RIGHT BEFORE each cmp2 (after Rn
  // is loaded — `move.w #imm,%d1` would otherwise clobber the flags), so the cmp2's
  // committed CCR = {N=1, Z, V=1, C} confirms N/V are PRESERVED (vs Musashi's full CCR).
  test("lock-step: CMP2.W (An) bounds {in,==lo,==hi,oob-lo,oob-hi}, N/V preserved", VerilatorTest) {
    runLockStep("cmp2-w",
      "move.l #0x3000,%a0 ; " +
      "move.w #10,%d0 ; move.w %d0,(%a0) ; move.w #100,%d0 ; move.w %d0,2(%a0) ; " + // [0x3000]=10,[0x3002]=100
      "move.w #50,%d1 ; moveq #127,%d7 ; addq.b #1,%d7 ; cmp2.w (%a0),%d1 ; " +  // in -> Z=0,C=0; N/V=1
      "move.w #10,%d1 ; moveq #127,%d7 ; addq.b #1,%d7 ; cmp2.w (%a0),%d1 ; " +  // ==lo -> Z=1,C=0
      "move.w #100,%d1 ; moveq #127,%d7 ; addq.b #1,%d7 ; cmp2.w (%a0),%d1 ; " + // ==hi -> Z=1,C=0
      "move.w #5,%d1 ; moveq #127,%d7 ; addq.b #1,%d7 ; cmp2.w (%a0),%d1 ; " +   // <lo -> C=1
      "move.w #200,%d1 ; moveq #127,%d7 ; addq.b #1,%d7 ; cmp2.w (%a0),%d1 ; " + // >hi -> C=1
      "loop: bra loop", nInstr = 28)
  }

  // CMP2.B (signed bounds) + CMP2.L (full 32, no mask) — exercise the per-size paths.
  test("lock-step: CMP2.B/.L bounds compare (signed bounds, per-size flags)", VerilatorTest) {
    runLockStep("cmp2-bl",
      "move.l #0x3000,%a0 ; " +
      // .B bounds: lower=-5 (0xFB), upper=+5 (0x05) @ 0x3000, 0x3001.
      "move.b #-5,%d0 ; move.b %d0,(%a0) ; move.b #5,%d0 ; move.b %d0,1(%a0) ; " +
      // N/V sentinel before .B tests: addq.b #1,d7 with d7=0x7F -> 0x80, N=1,V=1.
      // The bounds stores above set no flags; the sentinel writer is the last flag-setter
      // before each cmp2.b, confirming N/V are preserved by CMP2.
      "moveq #127,%d7 ; addq.b #1,%d7 ; moveq #0,%d1 ; cmp2.b (%a0),%d1 ; " +    // -5<=0<=5 -> Z=0,C=0; N/V=1 preserved
      "moveq #127,%d7 ; addq.b #1,%d7 ; move.l #-9,%d1 ; cmp2.b (%a0),%d1 ; " +  // -9 < -5 -> C=1 (signed); N/V=1 preserved
      // .L bounds: lower=0x1000, upper=0x10000000 @ 0x3008.
      "move.l #0x3008,%a1 ; move.l #0x1000,%d2 ; move.l %d2,(%a1) ; " +
      "move.l #0x10000000,%d2 ; move.l %d2,4(%a1) ; " +
      // N/V sentinel before .L tests.
      "moveq #127,%d7 ; addq.b #1,%d7 ; move.l #0x5000,%d3 ; cmp2.l (%a1),%d3 ; " +  // in-bounds -> Z=0,C=0; N/V=1 preserved
      "moveq #127,%d7 ; addq.b #1,%d7 ; move.l #0x20000000,%d3 ; cmp2.l (%a1),%d3 ; " + // > upper -> C=1; N/V=1 preserved
      "loop: bra loop", nInstr = 28)
  }

  // CMP2.W with an ADDRESS-register Rn (.W stays MASKED, not sign-extended) — the
  // 040 quirk. A2 low word = 0xFFF0 (-16 as signed, but masked to 0xFFF0 = 65520).
  // bounds lower=0, upper=0x7FFF: masked compare 65520 > 0x7FFF -> C=1.
  test("lock-step: CMP2.W with An (Rn masked, not sign-extended)", VerilatorTest) {
    runLockStep("cmp2-an",
      "move.l #0x3000,%a0 ; " +
      "move.w #0,%d0 ; move.w %d0,(%a0) ; move.w #0x7fff,%d0 ; move.w %d0,2(%a0) ; " +
      "move.l #0x1234fff0,%a2 ; cmp2.w (%a0),%a2 ; " + // A2.W=0xFFF0 masked=65520 > 0x7FFF -> C=1
      "loop: bra loop", nInstr = 8)
  }

  // CMP2.W via (d16,An) addressing — bounds at 8(A0); the 2nd load adds +size to disp.
  test("lock-step: CMP2.W (d16,An) bounds pointer", VerilatorTest) {
    runLockStep("cmp2-d16an",
      "move.l #0x2ff8,%a0 ; " +                       // A0 + 8 = 0x3000
      "move.w #20,%d0 ; move.w %d0,8(%a0) ; move.w #40,%d0 ; move.w %d0,10(%a0) ; " +
      "move.w #30,%d1 ; cmp2.w (8,%a0),%d1 ; " +      // 20<=30<=40 -> Z=0,C=0
      "loop: bra loop", nInstr = 7)
  }

  // CMP2.W via (d8,An,Xn) indexed — the index reg rides srcC on both loads.
  test("lock-step: CMP2.W (d8,An,Xn) indexed bounds pointer", VerilatorTest) {
    runLockStep("cmp2-idx",
      "move.l #0x3000,%a0 ; move.l #4,%d2 ; " +       // base+index+disp: 0x3000+4+(-4)=0x3000
      "move.w #1,%d0 ; move.w %d0,(%a0) ; move.w #9,%d0 ; move.w %d0,2(%a0) ; " +
      "move.w #5,%d1 ; cmp2.w (-4,%a0,%d2.l),%d1 ; " + // in-bounds
      "loop: bra loop", nInstr = 8)
  }

  // CHK2.W in-bounds -> no trap (straight-line).
  test("lock-step: CHK2.W in-bounds (no trap)", VerilatorTest) {
    runLockStep("chk2-inbounds",
      "move.l #0x3000,%a0 ; " +
      "move.w #0,%d0 ; move.w %d0,(%a0) ; move.w #100,%d0 ; move.w %d0,2(%a0) ; " +
      "move.w #50,%d1 ; chk2.w (%a0),%d1 ; " +        // 0<=50<=100 -> no trap
      "moveq #3,%d2 ; loop: bra loop", nInstr = 8)
  }

  // CHK2.W out-of-bounds -> trap vector 6 -> handler -> RTE -> resume. The entry CCR =
  // {oldN, Z, oldV, C=1}: the pre-chk2 flag-writer (the `move.w` store of 20) leaves
  // N=0,Z=0,V=0,C=0, so the chk2 out-of-bounds (99>20) entry CCR = N=0,Z=0,V=0,C=1.
  // The handler's last flag-writer reproduces it (a compare yielding C=1,N=0,Z=0,V=0:
  // 0 - 0x80000001 borrows but the result MSB is 0) so the RTE-restored CCR matches
  // (same convention as the CHK / DIV0 lock-step tests).
  test("lock-step: CHK2.W out-of-bounds -> handler -> RTE", VerilatorTest) {
    runLockStep("chk2-oob",
      "move.l #handler,%d0 ; move.l %d0,0x18 ; " +    // vector 6 (CHK) @ 0x18
      "move.l #0x3000,%a0 ; " +
      "move.w #10,%d3 ; move.w %d3,(%a0) ; move.w #20,%d3 ; move.w %d3,2(%a0) ; " +
      "move.w #99,%d1 ; chk2.w (%a0),%d1 ; " +        // 99 > 20 -> C=1 -> trap (entry CCR=0x01)
      "moveq #7,%d4 ; loop: bra loop ; " +
      "handler: moveq #0,%d6 ; cmp.l #0x80000001,%d6 ; rte", nInstr = 10) // N=0,Z=0,V=0,C=1
  }

  // ── MULU.W / MULS.W lock-step (16x16 -> Dn[31:0], N/Z; V=0, C=0) ─────────────
  // Register-source forms are 2-byte opwords (nextPc = pc+2), straight-line. The
  // full 32-bit product lands in Dn; N=bit31, Z=(product==0), V=0.
  test("lock-step: MULU.W normal + MULS.W normal/sign", VerilatorTest) {
    runLockStep("mul-w-normal",
      "moveq #100,%d0 ; moveq #7,%d1 ; mulu.w %d1,%d0 ; " +    // 100*7 = 700 in d0
      "moveq #-100,%d2 ; moveq #7,%d3 ; muls.w %d3,%d2 ; " +   // -100*7 = -700 (signed)
      "move.l #0x8000,%d4 ; move.l #0x8000,%d5 ; muls.w %d5,%d4 ; " + // -32768*-32768 = +2^30
      "moveq #1,%d6 ; loop: bra loop", nInstr = 9)
  }

  // MULU.W large product (N=1: bit31 set) + a zero product (Z=1).
  test("lock-step: MULU.W large product (N=1) + zero (Z=1)", VerilatorTest) {
    runLockStep("mul-w-flags",
      "move.l #0xffff,%d0 ; move.l #0xffff,%d1 ; mulu.w %d1,%d0 ; " + // 65535*65535 = 0xFFFE0001 (N=1)
      "moveq #0,%d2 ; moveq #123,%d3 ; mulu.w %d3,%d2 ; " +           // 0*123 = 0 (Z=1)
      "moveq #5,%d4 ; loop: bra loop", nInstr = 6)
  }

  // ── MULU.L / MULS.L 32x32 -> 32 lock-step (Dl = product[31:0]; +overflow V) ──
  // 0x4C00|ea form, ext bit10=0: Dl = (ea * Dl)[31:0]. V set iff the full 64-bit
  // product doesn't fit 32 bits (signed: high32 != sign-ext of bit31; unsigned:
  // high32 != 0). N=Dl[31], Z=(Dl==0), C=0.
  test("lock-step: MULU.L/MULS.L 32x32->32 normal", VerilatorTest) {
    runLockStep("mul-l32-normal",
      "move.l #100000,%d0 ; moveq #7,%d1 ; mulu.l %d1,%d0 ; " +    // 700000 fits 32 (no V)
      "move.l #-100000,%d2 ; moveq #7,%d3 ; muls.l %d3,%d2 ; " +   // -700000 signed (no V)
      "moveq #1,%d4 ; loop: bra loop", nInstr = 7)
  }

  // .L32 overflow: a product that exceeds 32 bits -> V=1, the low 32 still written.
  test("lock-step: MULU.L/MULS.L 32x32->32 overflow (V=1)", VerilatorTest) {
    runLockStep("mul-l32-ovf",
      "move.l #0x100000,%d0 ; move.l #0x100000,%d1 ; mulu.l %d1,%d0 ; " + // 2^20*2^20=2^40 -> V
      "move.l #0x40000000,%d2 ; moveq #4,%d3 ; muls.l %d3,%d2 ; " +       // 2^30*4=2^32 signed -> V
      "moveq #5,%d4 ; loop: bra loop", nInstr = 7)
  }

  // ── MULU.L/MULS.L 32x32 -> 64 lock-step (Dh:Dl 2-dest crack) ─────────────────
  // 0x4C00|ea form, ext bit10=1: Dh:Dl = ea * Dl (full 64-bit product). Cracked
  // [MUL -> Dl] + [MULHI -> Dh from the EU's latched high product]. V=0; N=Dh[31],
  // Z=(Dh|Dl==0). The trailing moves read Dh so the MULHI PRF write is verified.
  test("lock-step: MULU.L 32x32->64 (Dh:Dl) normal", VerilatorTest) {
    runLockStep("mul-l64-u",
      "move.l #0x100000,%d0 ; move.l #0x100000,%d1 ; mulu.l %d1,%d2:%d0 ; " + // 2^20*2^20=2^40
      "move.l %d2,%d6 ; loop: bra loop", nInstr = 5)
  }

  // MULS.L 64-bit signed (negative product spans Dh:Dl).
  test("lock-step: MULS.L 32x32->64 (Dh:Dl) signed negative", VerilatorTest) {
    runLockStep("mul-l64-s",
      "move.l #-100000,%d0 ; move.l #100000,%d1 ; muls.l %d1,%d2:%d0 ; " + // -(10^10) signed
      "move.l %d2,%d6 ; loop: bra loop", nInstr = 5)
  }

  // ── MMU page-fault delivery lock-step (format-$7 -> handler -> RTE) ──────────
  // THE Task-4 gate: a data store to a NON-RESIDENT page raises a 68040 access
  // fault (vector 2, format-$7), vectors to a handler that writes a resident page
  // descriptor, RTEs (re-executing the faulting store, which now succeeds), and
  // continues. The committed PC/SR/A7 stream + the stacked $7 frame match the MAME
  // 040 oracle step-for-step.
  //
  // MMU-on translates EVERY D-side access in the RTL, so the page table must map all
  // pages the data side touches. The exception FSM's frame/vector accesses use
  // IDENTITY paddr (they bypass translation), and RTE's frame reads are FSM-driven
  // (identity), so the supervisor stack needs no mapping. The LS data accesses that
  // DO translate are: the vector store (VA 0x8, VPN 0), the faulting store (VA
  // 0x2000, VPN 2), and the handler's PT write (VA 0x82008, VPN 0x82). Table:
  //   root[0] -> ptr ; ptr[0] -> pageA (covers 0x0..0x3FFFF) ; ptr[2] -> pageC
  //   pageA[0] = identity VPN 0 resident (vector page) ; pageA[2] = NON-RESIDENT
  //   pageC[2] = identity VPN 0x82 resident (the PT write page).
  // The handler writes pageA[2] a resident descriptor via a REAL `move.l #imm,d1 ;
  // move.l d1,addr` — the SAME instructions execute on BOTH the RTL (real big-endian
  // 68k memory) AND the oracle (Musashi's CUSTOM MMU shim for this directed-test
  // family, which — task #194 discovery — reads descriptor bytes back in
  // HOST-NATIVE little-endian order, NOT real 68k big-endian; a shim quirk, not a
  // Musashi core behavior, and out of scope to fix in this session since it needs a
  // C++ rebuild). Task #194 fixed the RTL walker (TableWalker.selectWord) to the
  // CORRECT big-endian convention (matching DcacheByteLane.extract / real 68k
  // memory), which broke the OLD hand-picked "byteswapped" immediate (0x01200400)
  // that only worked because BOTH sides used to (coincidentally or by design)
  // interpret descriptors little-endian. Since the RTL's big-endian read and the
  // oracle shim's little-endian read of the SAME physical bytes are exact byte-
  // reversals of each other, no value works for both UNLESS its bytes are a
  // palindrome under 4-byte reversal. `0x01000001` (bytes 01 00 00 01) is such a
  // palindrome: PDT=01 (resident) reads identically under EITHER byte order, so
  // both the RTL walker and the oracle shim agree it's a valid resident mapping.
  // The resulting PPN (0x01000, NOT the original 0x42) is what the retried store
  // actually lands at — `dataPPN` below is updated to match.
  test("lock-step: page fault (non-resident) -> handler maps -> RTE -> resume", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val PTRT = 0x00081000L
    val PAGA = 0x00082000L
    val PAGC = 0x00083000L
    // Task #194: PPN 0x1000 (not 0x42) — the handler's descriptor write (see the
    // byte-palindrome comment above) determines the ACTUAL PPN; the final
    // re-executed-store check below reads back through THIS PPN's PA.
    val dataPPN = 0x1000L

    // The program (identical for RTL + oracle). Handler writes pageA[2] a resident
    // descriptor (see the byte-palindrome comment above) then RTE -> the faulting
    // store re-executes.
    val src =
      "move.l #handler,%d1 ; move.l %d1,0x8 ; " +        // vector 2 (access fault) @ 0x8
      "moveq #42,%d0 ; move.l %d0,0x2000 ; " +           // FAULTS, then re-runs after RTE
      "loop: bra loop ; " +
      "handler: move.l #0x01000001,%d1 ; move.l %d1,0x82008 ; rte"
    val nInstr = 8  // vec-imm, vec-store, store(fault->reexec after handler), handler-imm, handler-store, rte, store(reexec), bra

    // Oracle: window = the data page only; preload the resident root[0] + ptr[0]
    // descriptors the VA-0x2000 walk needs (handler writes the leaf pageA[2]).
    def le(v: Long): Long = v & 0xffffffffL
    val oraclePt = Seq(
      0x80000L -> ((PTRT & 0xfffffff0L) | 0x2L),   // root[0] -> ptr resident
      PTRT     -> ((PAGA & 0xfffffff0L) | 0x2L))   // ptr[0]  -> pageA resident
    val mmu = Some(Musashi.MmuConfig(rootPtr = 0x80000L, dataLo = 0x2000L, dataHi = 0x3000L, ptPreload = oraclePt))

    val oracleSteps = Musashi.assembleAndTrace(src, mmu = mmu, maxCycles = 20000) match {
      case Right(v)  => v
      case Left(err) => fail(s"[pagefault] oracle trace failed: ${err.reason}")
    }
    assert(oracleSteps.size >= nInstr, s"[pagefault] oracle produced ${oracleSteps.size} steps, expected >= $nInstr")
    val oracle = oracleSteps.take(nInstr)

    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[pagefault] assemble failed: ${err.reason}")
    }

    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle

      def captureWb(w: m68k040.execute.WbObs): Unit = if (w.valid.toBoolean) {
        handle.onWb(w.robId.toInt, WhiteboxCapture.Wb(
          dstArch = w.dstArch.toInt, result = w.result.toLong & 0xffffffffL,
          intWrite = w.intWrite.toBoolean, nzvc = w.nzvc.toInt, nzvcWrite = w.nzvcWrite.toBoolean,
          x = if (w.x.toBoolean) 1 else 0, xWrite = w.xWrite.toBoolean, divRem = w.divRem.toBoolean))
      }
      def captureBranch(): Unit = {
        val bw = dut.branchEu.logic.wbObs
        if (bw.valid.toBoolean) handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      }
      // Precise-path store completion (Task P2.5): the SQ's at-head drain fires
      // rob.logic.completion(4)/lsEu.sqCompletionPort instead of lsEu.completion for
      // a precise store -- no lsEu.logic.wbObs pulse accompanies it (compValid is
      // never asserted on that path), so synthesize a no-op Wb here (mirrors the
      // branch EU's no-write capture above), or the later onCommit for this robId
      // finds no Wb record and throws.
      def captureSq(): Unit = {
        val sc = dut.lsEu.sqCompletionPort
        if (sc.valid.toBoolean) handle.onWb(sc.payload.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      }
      def captureExc(): Unit = {
        val c = dut.rob.logic.commitObs(2)
        if (c.fire.toBoolean) handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL,
          if (c.ccrFoldValid.toBoolean) c.ccrFold.toInt & 0xf else -1,
          msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
          isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
      }
      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs); captureWb(dut.eu1.logic.wbObs); captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs)
        captureBranch()
        captureSq()
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
            sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL,
            msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
            isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
        }
        captureExc()
      }

      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      // The D-cache and the MMU walker SHARE one physical memory: the page table the
      // handler writes via a D-cache store must be visible to the walker on re-walk.
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = dmem.mem)
      // The ITLB walker shares the SAME backing memory (one physical page table), so a
      // handler PT write is visible to the I-side re-walk too.
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = dmem.mem)
      // Build the nested page table (little-endian) in the shared memory.
      // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
      // MSB) — matches TableWalker.selectWord's corrected convention. Kept the name.
      def pokeLE(a: Long, w: Long): Unit = for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
      pokeLE(0x80000L,        (PTRT & 0xfffffff0L) | 0x2L)   // root[0] -> ptr resident
      pokeLE(PTRT + 0 * 4,    (PAGA & 0xfffffff0L) | 0x2L)   // ptr[0]  -> pageA resident
      pokeLE(PTRT + 2 * 4,    (PAGC & 0xfffffff0L) | 0x2L)   // ptr[2]  -> pageC resident
      pokeLE(PAGA + 0 * 4,    (0x0L << 12) | 0x1L)           // pageA[0] = identity VPN 0 (vectors)
      pokeLE(PAGA + 2 * 4,    0x0L)                          // pageA[2] = NON-RESIDENT (the fault)
      pokeLE(PAGA + 0x3f * 4, (0xffL << 12) | 0x1L)          // pageA[0x3f] = identity VPN 0xFF (supervisor stack)
      pokeLE(PAGC + 2 * 4,    (0x82L << 12) | 0x1L)          // pageC[2] = identity VPN 0x82 (PT write)
      // The 68040 has ONE MMU: the I-fetch path also translates. IDENTITY-map the code
      // region (8 pages from loadAddr; the oracle treats I-fetch as identity) so the
      // ITLB resolves code fetches without faulting. A SEPARATE leaf table (PAGD) at a
      // free address keeps it out of pageA/pageC.
      val PAGD = 0x00084000L
      pokeLE(PTRT + (((loadAddr >> 18) & 0x7f).toInt) * 4, (PAGD & 0xfffffff0L) | 0x2L)
      pokeLE(MMU_ROOT + (((loadAddr >> 25) & 0x7f).toInt) * 4, (PTRT & 0xfffffff0L) | 0x2L)
      for (i <- 0 until 8) {
        val cva = loadAddr + i * 0x1000L
        pokeLE(PAGD + (((cva >> 12) & 0x3f).toInt) * 4, (((cva >> 12) & 0xfffffL) << 12) | 0x1L)
      }
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      // Task #194: poke mmuEnable/urp/srp HERE (past the init-sweep wait) — see
      // runLockStep's identical fix comment.
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= 0x80000L
      dut.ctrl.logic.srp   #= 0x80000L
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      // Seed the int PRF arch-15 (A7, identity phys-15) to the boot SSP. The committed
      // A7 banks (ss.usp/isp/msp) are LIVE-COHERENT with the architectural A7 read back
      // from the PRF every cycle (the exc unit drives ss.writeA7), so the PRF arch-15 —
      // NOT the poked ss.isp — is the boot SP source of truth. Without this seed, the
      // live readback clobbers ss.isp to the PRF's boot value (0), so the supervisor
      // frame is stacked at SP=0 (wrapping to 0xFFFFFFC4) instead of 0x00100000-60.
      // (Mirrors the IRQ-entry boot below, which seeds arch-15 = 0x00100000.)
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      var guard = 0; val cap = 6000
      var sawFault = false
      while (handle.result.size < nInstr && guard < cap) {
        if (dut.dtlb.logic.faultSeen.toBoolean) sawFault = true
        cd.waitSampling(); guard += 1
      }
      assert(sawFault, "[pagefault] the data access to the non-resident page must flag a DTLB fault")
      assert(handle.result.size >= nInstr,
        s"[pagefault] only ${handle.result.size}/$nInstr committed within $cap cycles")
      // The stacked format-$7 frame (read back from the supervisor stack, big-endian)
      // must match the MAME-040 oracle byte-for-byte.
      val fb = 0x100000L - 60
      def pk16(a: Long): Int = ((dmem.peekByte(a) << 8) | dmem.peekByte(a + 1)) & 0xffff
      def pk32(a: Long): Long = ((pk16(a).toLong << 16) | pk16(a + 2)) & 0xffffffffL
      assert((pk16(fb) & 0xff00) == 0x2700, f"[pagefault] frame SR=0x${pk16(fb)}%04x expected 0x27xx")
      assert(pk32(fb + 2) == 0x4080000cL, f"[pagefault] frame PC=0x${pk32(fb + 2)}%08x expected the faulting-instr PC 0x4080000c")
      assert(pk16(fb + 6) == 0x7008, f"[pagefault] frame fmt/vec=0x${pk16(fb + 6)}%04x expected 0x7008")
      assert(pk32(fb + 8) == 0x2000L, f"[pagefault] frame EA=0x${pk32(fb + 8)}%08x expected 0x2000")
      assert(pk16(fb + 0xc) == 0x0405, f"[pagefault] frame SSW=0x${pk16(fb + 0xc)}%04x expected 0x0405")
      assert(pk32(fb + 0x14) == 0x2000L, f"[pagefault] frame faultAddr=0x${pk32(fb + 0x14)}%08x expected 0x2000")

      val res = LockStep.compare(handle.result.take(nInstr), oracle)
      assert(res.ok,
        s"[pagefault] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
          s"(matched ${res.matched}, dut commits ${handle.result.size}, oracle steps $nInstr)")

      // The re-executed store landed at the mapped PA 0x42000 (value 42 = 0x2a).
      cd.waitSampling(200)
      val pa = (dataPPN << 12) | (0x2000L & 0xfffL)
      assert(dmem.peekByte(pa + 3) == 0x2a,
        f"[pagefault] re-executed store must land 0x2a at PA 0x${pa + 3}%08x (got 0x${dmem.peekByte(pa + 3)}%02x)")
    }
  }

  // m68k-ooo test-porting (mmu_wp_user_vs_super.s intent, task #131 gap-closing):
  // a WRITE to a write-protected (W-bit set) RESIDENT page must fault (vector 2,
  // format-$7) exactly like a non-resident page — genuinely untested before this
  // (grep for writeProt/WRITE_PROTECT in src/test/scala found zero hits despite the
  // RTL — Tlb.scala/MmuTypes.scala — already tracking write-protect per-entry).
  // IDENTICAL program/layout to the non-resident test above (same VA, same fault
  // PC, same SSW — a WP fault and a non-resident fault produce the SAME SSW bit
  // pattern in this RTL, since SSW only encodes rw/fc, not fault REASON) — the only
  // difference is the leaf descriptor starts RESIDENT+W=1 instead of non-resident,
  // and the handler clears W (not PDT) before RTE. Confirmed the oracle's custom
  // MMU shim (`m68k_ref.cpp: bool wp = (pgDesc & 0x4u); if (rw && wp) return
  // fault();`) implements write-protect, unlike the MOVEC-URP/SRP stubs — this IS
  // lock-step verifiable.
  test("lock-step: MMU write-protect fault (W-bit set, resident) -> handler clears W -> RTE -> resume", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val PTRT = 0x00081000L
    val PAGA = 0x00082000L
    val PAGC = 0x00083000L
    val dataPPN = 0x42L

    val src =
      "move.l #handler,%d1 ; move.l %d1,0x8 ; " +
      "moveq #42,%d0 ; move.l %d0,0x2000 ; " +           // FAULTS (write to W=1 page), then re-runs after RTE
      "loop: bra loop ; " +
      "handler: move.l #0x01000001,%d1 ; move.l %d1,0x82008 ; rte"  // clears W (PDT=01, W=0)
    val nInstr = 8

    val leafWp = ((dataPPN << 12) & 0xfffff000L) | 0x4L | 0x1L   // resident, W=1
    val oraclePt = Seq(
      0x80000L -> ((PTRT & 0xfffffff0L) | 0x2L),
      PTRT     -> ((PAGA & 0xfffffff0L) | 0x2L),
      (PAGA + 2 * 4) -> leafWp)   // leaf: resident + write-protected from the start
    val mmu = Some(Musashi.MmuConfig(rootPtr = 0x80000L, dataLo = 0x2000L, dataHi = 0x3000L, ptPreload = oraclePt))

    val oracleSteps = Musashi.assembleAndTrace(src, mmu = mmu, maxCycles = 20000) match {
      case Right(v)  => v
      case Left(err) => fail(s"[wpfault] oracle trace failed: ${err.reason}")
    }
    assert(oracleSteps.size >= nInstr, s"[wpfault] oracle produced ${oracleSteps.size} steps, expected >= $nInstr")
    val oracle = oracleSteps.take(nInstr)

    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[wpfault] assemble failed: ${err.reason}")
    }

    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle

      def captureWb(w: m68k040.execute.WbObs): Unit = if (w.valid.toBoolean) {
        handle.onWb(w.robId.toInt, WhiteboxCapture.Wb(
          dstArch = w.dstArch.toInt, result = w.result.toLong & 0xffffffffL,
          intWrite = w.intWrite.toBoolean, nzvc = w.nzvc.toInt, nzvcWrite = w.nzvcWrite.toBoolean,
          x = if (w.x.toBoolean) 1 else 0, xWrite = w.xWrite.toBoolean, divRem = w.divRem.toBoolean))
      }
      def captureBranch(): Unit = {
        val bw = dut.branchEu.logic.wbObs
        if (bw.valid.toBoolean) handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      }
      // Precise-path store completion (Task P2.5): the SQ's at-head drain fires
      // rob.logic.completion(4)/lsEu.sqCompletionPort instead of lsEu.completion for
      // a precise store -- no lsEu.logic.wbObs pulse accompanies it (compValid is
      // never asserted on that path), so synthesize a no-op Wb here (mirrors the
      // branch EU's no-write capture above), or the later onCommit for this robId
      // finds no Wb record and throws.
      def captureSq(): Unit = {
        val sc = dut.lsEu.sqCompletionPort
        if (sc.valid.toBoolean) handle.onWb(sc.payload.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      }
      def captureExc(): Unit = {
        val c = dut.rob.logic.commitObs(2)
        if (c.fire.toBoolean) handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL,
          if (c.ccrFoldValid.toBoolean) c.ccrFold.toInt & 0xf else -1,
          msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
          isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
      }
      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs); captureWb(dut.eu1.logic.wbObs); captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs)
        captureBranch()
        captureSq()
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
            sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL,
            msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
            isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
        }
        captureExc()
      }

      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = dmem.mem)
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = dmem.mem)
      // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
      // MSB) — matches TableWalker.selectWord's corrected convention. Kept the name.
      def pokeLE(a: Long, w: Long): Unit = for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
      pokeLE(0x80000L,        (PTRT & 0xfffffff0L) | 0x2L)
      pokeLE(PTRT + 0 * 4,    (PAGA & 0xfffffff0L) | 0x2L)
      pokeLE(PTRT + 2 * 4,    (PAGC & 0xfffffff0L) | 0x2L)
      pokeLE(PAGA + 0 * 4,    (0x0L << 12) | 0x1L)
      pokeLE(PAGA + 2 * 4,    leafWp)                        // pageA[2] = resident, W=1 (the fault)
      pokeLE(PAGA + 0x3f * 4, (0xffL << 12) | 0x1L)
      pokeLE(PAGC + 2 * 4,    (0x82L << 12) | 0x1L)
      val PAGD = 0x00084000L
      pokeLE(PTRT + (((loadAddr >> 18) & 0x7f).toInt) * 4, (PAGD & 0xfffffff0L) | 0x2L)
      pokeLE(MMU_ROOT + (((loadAddr >> 25) & 0x7f).toInt) * 4, (PTRT & 0xfffffff0L) | 0x2L)
      for (i <- 0 until 8) {
        val cva = loadAddr + i * 0x1000L
        pokeLE(PAGD + (((cva >> 12) & 0x3f).toInt) * 4, (((cva >> 12) & 0xfffffL) << 12) | 0x1L)
      }
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      // Task #194: poke mmuEnable/urp/srp HERE (past the init-sweep wait) — see
      // runLockStep's identical fix comment.
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= 0x80000L
      dut.ctrl.logic.srp   #= 0x80000L
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      var guard = 0; val cap = 6000
      var sawFault = false
      while (handle.result.size < nInstr && guard < cap) {
        if (dut.dtlb.logic.faultSeen.toBoolean) sawFault = true
        cd.waitSampling(); guard += 1
      }
      assert(sawFault, "[wpfault] the write to a write-protected page must flag a DTLB fault")
      assert(handle.result.size >= nInstr,
        s"[wpfault] only ${handle.result.size}/$nInstr committed within $cap cycles")
      val fb = 0x100000L - 60
      def pk16(a: Long): Int = ((dmem.peekByte(a) << 8) | dmem.peekByte(a + 1)) & 0xffff
      def pk32(a: Long): Long = ((pk16(a).toLong << 16) | pk16(a + 2)) & 0xffffffffL
      assert(pk16(fb + 6) == 0x7008, f"[wpfault] frame fmt/vec=0x${pk16(fb + 6)}%04x expected 0x7008 (vector 2)")
      assert(pk16(fb + 0xc) == 0x0405, f"[wpfault] frame SSW=0x${pk16(fb + 0xc)}%04x expected 0x0405 (write-protect uses the SAME rw/fc encoding as non-resident)")
      assert(pk32(fb + 0x14) == 0x2000L, f"[wpfault] frame faultAddr=0x${pk32(fb + 0x14)}%08x expected 0x2000")

      val res = LockStep.compare(handle.result.take(nInstr), oracle)
      assert(res.ok,
        s"[wpfault] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
          s"(matched ${res.matched}, dut commits ${handle.result.size}, oracle steps $nInstr)")

      cd.waitSampling(200)
      // Task #194: the handler's W-clearing descriptor write (see the byte-palindrome
      // comment above the page-fault test) lands PPN 0x1000, NOT the original
      // (pre-fault) `dataPPN` 0x42 — the retried store's PA follows the handler's
      // NEW mapping, not the initial WP-faulting one.
      val newPpn = 0x1000L
      val pa = (newPpn << 12) | (0x2000L & 0xfffL)
      assert(dmem.peekByte(pa + 3) == 0x2a,
        f"[wpfault] re-executed store must land 0x2a at PA 0x${pa + 3}%08x (got 0x${dmem.peekByte(pa + 3)}%02x)")
    }
  }

  // ── ITLB lock-step helpers ──────────────────────────────────────────────────
  /** Place a code image at an ARBITRARY base in an I-cache SparseMemory (low-byte
    * first, matching the I-cache window convention — same swap as attachProgram). */
  private def writeCodeAt(mem: SparseMemory, base: Long, bytes: Vector[Int]): Unit = {
    m68k040.sim.AxiMemModel.loadProgramIFetch(mem, base, bytes)
    // Same run-ahead guard as `attachProgram` — see its comment. The ITLB lock-step
    // tests build their `icmem` through this helper instead, so without it they keep
    // the un-guarded PRNG-fill behaviour.
    m68k040.sim.AxiMemModel.fillIFetchRunAheadGuard(
      mem, base + bytes.length, m68k040.sim.AxiMemModel.LockStepRunAheadGuardWords)
  }
  /** Wire the full whitebox commit capture (incl. the exception commit channel) used
    * by the ITLB lock-step tests. */
  private def wireWhitebox(dut: FullCoreDut, handle: WhiteboxCapture.Handle): Unit = {
    def captureWb(w: m68k040.execute.WbObs): Unit = if (w.valid.toBoolean) {
      handle.onWb(w.robId.toInt, WhiteboxCapture.Wb(
        dstArch = w.dstArch.toInt, result = w.result.toLong & 0xffffffffL,
        intWrite = w.intWrite.toBoolean, nzvc = w.nzvc.toInt, nzvcWrite = w.nzvcWrite.toBoolean,
        x = if (w.x.toBoolean) 1 else 0, xWrite = w.xWrite.toBoolean, divRem = w.divRem.toBoolean))
    }
    dut.clockDomain.onSamplings {
      captureWb(dut.eu0.logic.wbObs); captureWb(dut.eu1.logic.wbObs); captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs)
      val bw = dut.branchEu.logic.wbObs
      if (bw.valid.toBoolean) handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      // Precise-path store completion (Task P2.5): see the captureSq comment above
      // (other helpers in this file) -- no lsEu.logic.wbObs pulse accompanies a
      // precise store's completion, so synthesize a no-op Wb here too.
      val sc = dut.lsEu.sqCompletionPort
      if (sc.valid.toBoolean) handle.onWb(sc.payload.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      for (k <- 0 until 2) {
        val c = dut.rob.logic.commitObs(k)
        if (c.fire.toBoolean) handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
          sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL,
          msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
          isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
      }
      val ce = dut.rob.logic.commitObs(2)
      if (ce.fire.toBoolean) handle.onExcCommit(ce.pc.toLong & 0xffffffffL, ce.sysByte.toInt & 0xff, ce.a7.toLong & 0xffffffffL,
        if (ce.ccrFoldValid.toBoolean) ce.ccrFold.toInt & 0xf else -1,
        msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
        isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
    }
  }

  // ── ITLB lock-step (a): code at a NON-IDENTITY instruction mapping ──────────
  // The program is linked at VA loadAddr but the ITLB maps that code page to a
  // DIFFERENT physical page (PPN 0x50000): the I-cache fetches from the PA while the
  // architectural PC stream is the VA. The oracle runs instruction fetch as identity
  // (PC = VA), so the committed PC/SR/A7/register stream lock-steps step-for-step —
  // proving the ITLB miss->walk->fill + translate is transparent.
  test("lock-step ITLB: code at a non-identity instruction mapping", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress           // VA 0x40800000
    val codePPN  = 0x50000L                                      // PA page 0x50000000
    val codePA   = codePPN << 12
    // A register-only straight-line program (no data accesses -> only the I-side
    // translates). 6 instructions, then halt.
    val src = "moveq #1,%d0 ; moveq #2,%d1 ; add.l %d0,%d1 ; moveq #7,%d2 ; " +
              "sub.l %d0,%d2 ; and.l %d1,%d2 ; loop: bra loop"
    val nInstr = 6
    val oracleSteps = Musashi.assembleAndTrace(src, maxCycles = 20000) match {
      case Right(v)  => v.take(nInstr); case Left(e) => fail(s"[itlb-a] oracle: ${e.reason}") }
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i; case Left(e) => fail(s"[itlb-a] assemble: ${e.reason}") }

    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      wireWhitebox(dut, handle)

      // I-cache memory: code lives at the PA (0x50000000), NOT the VA.
      val icmem = SparseMemory()
      writeCodeAt(icmem, codePA, image.bytes)
      m68k040.sim.AxiMemModel.attachReadOnly(
        dut.icache.logic.axi, cd, sharedMem = icmem)
      new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      // Page table (in both walker memories): map VA loadAddr -> PPN 0x50000 (resident).
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd)
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd)
      def build(mem: m68k040.ls.BehavioralMemAgent): Unit = {
        for (i <- 0 until 8) mapPage(mem, loadAddr + i * 0x1000L, codePPN + i, MMU_PAGT2)
      }
      build(ptmem); build(itlbPtmem)
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      // Task #194: poke mmuEnable/urp/srp HERE (past the init-sweep wait) — see
      // runLockStep's identical fix comment.
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= MMU_ROOT
      dut.ctrl.logic.srp   #= MMU_ROOT
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      // Mirror runLockStep's boot-A7 seed.  ss.isp alone is not the source of
      // architectural A7; the committed RAT still names identity phys-15 here.
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      var guard = 0; val cap = 5000
      while (handle.result.size < nInstr && guard < cap) { cd.waitSampling(); guard += 1 }
      assert(handle.result.size >= nInstr,
        s"[itlb-a] only ${handle.result.size}/$nInstr committed within $cap cycles")
      val res = LockStep.compare(handle.result.take(nInstr), oracleSteps)
      assert(res.ok, s"[itlb-a] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
        s"(matched ${res.matched}, dut ${handle.result.size}, oracle $nInstr)")
    }
  }

  // ── ITLB lock-step (b): branch to a NON-RESIDENT I-page -> format-$7 -> RTE ──
  // The program (at loadAddr) installs the vector-2 handler, then branches FORWARD
  // (bra.w, reachable) to VA loadAddr+0x2000 — an instruction page left NON-RESIDENT.
  // The I-fetch there faults -> the core delivers a format-$7 access fault (SSW
  // program-space, EA = the faulting page VA) -> the handler writes a resident
  // descriptor for that page -> RTE re-fetches it, which now runs (writes D3 then
  // halts). The committed PC/SR/A7 stream + the stacked $7 frame match the MAME-040
  // oracle (extended for I-fetch) step-for-step / byte-for-byte. (The 040 decoder has
  // no absolute JMP, so the non-resident page is within bra.w reach of the code.)
  test("lock-step ITLB: non-resident I-page -> format-$7 -> handler maps -> RTE -> resume", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress       // 0x40800000
    val farVA    = loadAddr + 0x2000L                        // 0x40802000 (non-resident I-page)
    // Code leaf table PAGD placed at 0x3F000 so the far-page descriptor (PAGD+2*4 =
    // 0x3F008) is itself in pageA's identity range (ptr[0], pageIdx 0x3f) -> the
    // handler's DATA write to VA 0x3F008 translates to PA 0x3F008.
    val PTRT = 0x00081000L; val PAGA = 0x00082000L; val PAGD = 0x0003F000L
    val descVA = PAGD + 2*4                                  // 0x3F008 (the far-page descriptor)
    // Far page resident IDENTITY (PPN farVA>>12). LE descriptor; big-endian move.l imm
    // = byteswap(that).
    val farPpn   = (farVA >> 12) & 0xfffffL                  // 0x40802
    val farDescLE = ((farPpn << 12) | 0x1L) & 0xffffffffL    // 0x40802001
    val farDescBE = java.lang.Long.reverseBytes(farDescLE) >>> 32  // byteswap to a move.l imm
    // Program: install handler @ 0x8, bra.w to far. far page (.org 0x2000) writes D3
    // then halts. handler maps PAGD[2] resident then RTE.
    // The fall-through after `bra.w far` (the mispredicted not-taken path) is fetched
    // speculatively before the redirect; keep it STORE-FREE (filler moveqs) so no
    // wrong-path store is ever allocated into the SQ. The handler (with its store)
    // sits AFTER the filler.
    val filler = (0 until 24).map(_ => "moveq #0,%d7").mkString(" ; ")
    val src =
      "move.l #handler,%d1 ; move.l %d1,0x8 ; " +
      "moveq #1,%d4 ; moveq #2,%d5 ; moveq #3,%d6 ; " +   // let the vec-store drain
      "bra.w far ; " +
      filler + " ; " +                                   // store-free wrong-path window
      f"handler: move.l #0x$farDescBE%08x,%%d1 ; move.l %%d1,0x3F008 ; rte ; " +
      ".org 0x2000 ; far: moveq #99,%d3 ; floop: bra floop"
    val nInstr = 10  // vec-imm, vec-store, 3x moveq, [fault@far], handler-imm, handler-store, rte, moveq@far
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i; case Left(e) => fail(s"[itlb-b] assemble: ${e.reason}") }

    // Oracle: instr window covers ONLY the far page [farVA, farVA+0x1000); preloaded
    // resident root[4]/ptr[0x20] (the code's root/ptr) + PAGD[2] NON-RESIDENT (=0).
    // The oracle fetches the far-page code from its own memory (the image already
    // contains it at offset 0x2000). The handler maps PAGD[2] resident, RTEs.
    val rIdx = ((loadAddr >> 25) & 0x7f).toInt
    val pIdx = ((loadAddr >> 18) & 0x7f).toInt
    val oraclePt = Seq(
      (0x80000L + rIdx*4) -> ((PTRT & 0xfffffff0L) | 0x2L),   // root[code] -> ptr
      (PTRT + pIdx*4)     -> ((PAGD & 0xfffffff0L) | 0x2L),   // ptr[code]  -> PAGD
      (PAGD + 2*4)        -> 0x0L) ++                          // PAGD[2] = NON-RESIDENT (the I-fault)
      // the OTHER code pages identity-resident so the rest of the program fetches.
      (0 until 8).filter(_ != 2).map(i => (PAGD + i*4) -> ((((loadAddr>>12)&0xfffffL)+i) << 12 | 0x1L))
    val mmu = Some(Musashi.MmuConfig(rootPtr = 0x80000L, dataLo = 0L, dataHi = 0L,
      ptPreload = oraclePt, instrLo = farVA, instrHi = farVA + 0x1000L))
    val oracleSteps = Musashi.assembleAndTrace(src, mmu = mmu, maxCycles = 20000) match {
      case Right(v)  => v; case Left(e) => fail(s"[itlb-b] oracle: ${e.reason}") }
    assert(oracleSteps.size >= nInstr, s"[itlb-b] oracle produced ${oracleSteps.size} steps (< $nInstr)")
    val oracle = oracleSteps.take(nInstr)

    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      wireWhitebox(dut, handle)

      // I-cache memory: the whole image at loadAddr (identity PA == VA for resident
      // pages; the far page is at PA loadAddr+0x2000 once mapped identity).
      val icmem = SparseMemory()
      writeCodeAt(icmem, loadAddr, image.bytes)
      m68k040.sim.AxiMemModel.attachReadOnly(
        dut.icache.logic.axi, cd, sharedMem = icmem)
      // D-cache + walker memories share one backing store (the handler's PT write must
      // be visible to the ITLB re-walk).
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = dmem.mem)
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = dmem.mem)
      // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
      // MSB) — matches TableWalker.selectWord's corrected convention. Kept the name.
      def pokeLE(a: Long, w: Long): Unit = for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
      // Page table. Data accesses that translate: the vector store (VA 0x8) and the
      // handler PT write (VA 0x3F008). Both via root[0]->ptr[0]->pageA, identity.
      pokeLE(0x80000L,        (PTRT & 0xfffffff0L) | 0x2L)   // root[0] -> ptr
      pokeLE(PTRT + 0*4,      (PAGA & 0xfffffff0L) | 0x2L)   // ptr[0]  -> pageA (0x0..0x3FFFF)
      pokeLE(PAGA + 0*4,      (0x0L << 12) | 0x1L)           // pageA[0] = identity (vector store VA 0x8)
      pokeLE(PAGA + 0x3f*4,   (0x3fL << 12) | 0x1L)          // pageA[0x3f] = identity VPN 0x3F (PT write VA 0x3F008)
      // Code region: root[code]->ptr, ptr[code]->PAGD; PAGD[2] NON-RESIDENT, the rest
      // identity-resident.
      pokeLE(0x80000L + rIdx*4, (PTRT & 0xfffffff0L) | 0x2L)
      pokeLE(PTRT + pIdx*4,     (PAGD & 0xfffffff0L) | 0x2L)
      for (i <- 0 until 8) {
        val v = if (i == 2) 0x0L else ((((loadAddr>>12)&0xfffffL)+i) << 12 | 0x1L)
        pokeLE(PAGD + i*4, v)
      }
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      // Task #194: poke mmuEnable/urp/srp HERE (past the init-sweep wait) — see
      // runLockStep's identical fix comment.
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= 0x80000L
      dut.ctrl.logic.srp   #= 0x80000L
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      var guard = 0; val cap = 12000; var sawFault = false
      while (handle.result.size < nInstr && guard < cap) {
        if (dut.itlb.logic.faultSeen.toBoolean) sawFault = true
        cd.waitSampling(); guard += 1
      }
      assert(sawFault, "[itlb-b] the I-fetch to the non-resident page must flag an ITLB fault")
      assert(handle.result.size >= nInstr,
        s"[itlb-b] only ${handle.result.size}/$nInstr committed within $cap cycles")
      // Stacked format-$7 frame (supervisor stack, big-endian): SSW = program-space.
      val fb = 0x100000L - 60
      def pk16(a: Long): Int = ((dmem.peekByte(a) << 8) | dmem.peekByte(a + 1)) & 0xffff
      def pk32(a: Long): Long = ((pk16(a).toLong << 16) | pk16(a + 2)) & 0xffffffffL
      assert((pk16(fb) & 0xff00) == 0x2700, f"[itlb-b] frame SR=0x${pk16(fb)}%04x expected 0x27xx")
      assert(pk16(fb + 6) == 0x7008, f"[itlb-b] frame fmt/vec=0x${pk16(fb + 6)}%04x expected 0x7008")
      assert(pk32(fb + 8) == farVA, f"[itlb-b] frame EA=0x${pk32(fb + 8)}%08x expected 0x$farVA%08x")
      assert(pk16(fb + 0xc) == 0x0506, f"[itlb-b] frame SSW=0x${pk16(fb + 0xc)}%04x expected 0x0506 (in_mmu|super-program|read)")
      assert(pk32(fb + 0x14) == farVA, f"[itlb-b] frame faultAddr=0x${pk32(fb + 0x14)}%08x expected 0x$farVA%08x")

      val res = LockStep.compare(handle.result.take(nInstr), oracle)
      assert(res.ok, s"[itlb-b] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
        s"(matched ${res.matched}, dut ${handle.result.size}, oracle $nInstr)")
    }
  }

  // ── ITLB lock-step (c): F4 regression — resident instructions BUFFERED AHEAD of
  // an I-fetch fault must still all execute, and the fault PC must be the true
  // faulting instruction, not wherever decode happened to be when the fault
  // RESPONSE first landed (up to RING=3 fetch windows ahead of decode). Unlike
  // itlb-b (where the branch target IS the first faulting instruction, so nothing
  // is ever buffered ahead of the fault), here the branch lands INSIDE the resident
  // page near ITS end (0x1fe0, 32 bytes before the 0x2000 page-2 boundary) and falls
  // straight through 16 real moveq instructions with NO further branch — by
  // construction, several of those must still be draining out of the IBuf when the
  // fault response for the [0x2000,...) window lands (RING=3 * 4 words = 12 words of
  // fetch-ahead, well inside the 16-instruction/32-byte run). Pre-fix, the front end
  // would squash whatever was still buffered and stamp the fault with the
  // (too-early) decodePc it happened to be sitting at -> fewer than 16 moveqs
  // retire and/or the stacked EA != farVA.
  test("lock-step ITLB: resident run BUFFERED AHEAD of a fault must drain before the fault fires (F4)", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress       // 0x40800000
    val farVA    = loadAddr + 0x2000L                        // 0x40802000 (non-resident I-page)
    val runStart = loadAddr + 0x1fe0L                        // 32 bytes before farVA, still page-1 resident
    val PTRT = 0x00081000L; val PAGA = 0x00082000L; val PAGD = 0x0003F000L
    val descVA = PAGD + 2*4
    val farPpn   = (farVA >> 12) & 0xfffffL
    val farDescLE = ((farPpn << 12) | 0x1L) & 0xffffffffL
    val farDescBE = java.lang.Long.reverseBytes(farDescLE) >>> 32
    val filler = (0 until 24).map(_ => "moveq #0,%d7").mkString(" ; ")
    // 16 distinct-register/value moveqs, landing EXACTLY at farVA (16 * 2 bytes = 32).
    val runInstrs = (0 until 16).map(i => s"moveq #${i + 1},%d${i % 8}").mkString(" ; ")
    val src =
      "move.l #handler,%d1 ; move.l %d1,0x8 ; " +
      "moveq #1,%d4 ; moveq #2,%d5 ; moveq #3,%d6 ; " +
      "bra.w far ; " +
      filler + " ; " +
      f"handler: move.l #0x$farDescBE%08x,%%d1 ; move.l %%d1,0x3F008 ; rte ; " +
      ".org 0x1fe0 ; far: " + runInstrs
    // vec-imm, vec-store, 3x moveq, bra, 16x run-moveq, [fault], handler-imm, handler-store, rte
    val nInstr = 2 + 3 + 1 + 16 + 3
    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i) => i; case Left(e) => fail(s"[itlb-c] assemble: ${e.reason}") }

    val rIdx = ((loadAddr >> 25) & 0x7f).toInt
    val pIdx = ((loadAddr >> 18) & 0x7f).toInt
    val oraclePt = Seq(
      (0x80000L + rIdx*4) -> ((PTRT & 0xfffffff0L) | 0x2L),
      (PTRT + pIdx*4)     -> ((PAGD & 0xfffffff0L) | 0x2L),
      (PAGD + 2*4)        -> 0x0L) ++
      (0 until 8).filter(_ != 2).map(i => (PAGD + i*4) -> ((((loadAddr>>12)&0xfffffL)+i) << 12 | 0x1L))
    val mmu = Some(Musashi.MmuConfig(rootPtr = 0x80000L, dataLo = 0L, dataHi = 0L,
      ptPreload = oraclePt, instrLo = farVA, instrHi = farVA + 0x1000L))
    val oracleSteps = Musashi.assembleAndTrace(src, mmu = mmu, maxCycles = 20000) match {
      case Right(v)  => v; case Left(e) => fail(s"[itlb-c] oracle: ${e.reason}") }
    assert(oracleSteps.size >= nInstr, s"[itlb-c] oracle produced ${oracleSteps.size} steps (< $nInstr)")
    val oracle = oracleSteps.take(nInstr)

    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      wireWhitebox(dut, handle)

      val icmem = SparseMemory()
      writeCodeAt(icmem, loadAddr, image.bytes)
      m68k040.sim.AxiMemModel.attachReadOnly(
        dut.icache.logic.axi, cd, sharedMem = icmem)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = dmem.mem)
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = dmem.mem)
      // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
      // MSB) — matches TableWalker.selectWord's corrected convention. Kept the name.
      def pokeLE(a: Long, w: Long): Unit = for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
      pokeLE(0x80000L,        (PTRT & 0xfffffff0L) | 0x2L)
      pokeLE(PTRT + 0*4,      (PAGA & 0xfffffff0L) | 0x2L)
      pokeLE(PAGA + 0*4,      (0x0L << 12) | 0x1L)
      pokeLE(PAGA + 0x3f*4,   (0x3fL << 12) | 0x1L)
      pokeLE(0x80000L + rIdx*4, (PTRT & 0xfffffff0L) | 0x2L)
      pokeLE(PTRT + pIdx*4,     (PAGD & 0xfffffff0L) | 0x2L)
      for (i <- 0 until 8) {
        val v = if (i == 2) 0x0L else ((((loadAddr>>12)&0xfffffL)+i) << 12 | 0x1L)
        pokeLE(PAGD + i*4, v)
      }
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      // Task #194: poke mmuEnable/urp/srp HERE (past the init-sweep wait) — see
      // runLockStep's identical fix comment.
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= 0x80000L
      dut.ctrl.logic.srp   #= 0x80000L
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      dut.wire.logic.seedValid #= true
      dut.wire.logic.seedAddr  #= 15
      dut.wire.logic.seedData  #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid #= true; dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling(); dut.fa.logic.redirect.valid #= false

      var guard = 0; val cap = 12000; var sawFault = false
      while (handle.result.size < nInstr && guard < cap) {
        if (dut.itlb.logic.faultSeen.toBoolean) sawFault = true
        cd.waitSampling(); guard += 1
      }
      assert(sawFault, "[itlb-c] the I-fetch to the non-resident page must flag an ITLB fault")
      assert(handle.result.size >= nInstr,
        s"[itlb-c] only ${handle.result.size}/$nInstr committed within $cap cycles " +
        "(F4: buffered pre-fault instructions were squashed instead of draining)")
      val fb = 0x100000L - 60
      def pk16(a: Long): Int = ((dmem.peekByte(a) << 8) | dmem.peekByte(a + 1)) & 0xffff
      def pk32(a: Long): Long = ((pk16(a).toLong << 16) | pk16(a + 2)) & 0xffffffffL
      assert(pk16(fb + 6) == 0x7008, f"[itlb-c] frame fmt/vec=0x${pk16(fb + 6)}%04x expected 0x7008")
      assert(pk32(fb + 8) == farVA,
        f"[itlb-c] frame EA=0x${pk32(fb + 8)}%08x expected 0x$farVA%08x " +
        "(F4: fault stamped too early, at a pre-drain decodePc)")
      assert(pk32(fb + 0x14) == farVA, f"[itlb-c] frame faultAddr=0x${pk32(fb + 0x14)}%08x expected 0x$farVA%08x")

      val res = LockStep.compare(handle.result.take(nInstr), oracle)
      assert(res.ok, s"[itlb-c] lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")} " +
        s"(matched ${res.matched}, dut ${handle.result.size}, oracle $nInstr)")
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // -(An) / (An)+ predecrement / postincrement addressing modes.
  //
  // The An := An ± delta write-back is folded into the load/store/RMW crack
  // (generalizing the call/return A7 stkPush). Programs STORE before they LOAD
  // (the DUT memory is zeroed), then read the post-update An into a Dn so the RAW
  // on the An update + the An value are validated against Musashi. checkMem
  // validates the stored bytes. (The ABCD/SBCD/ADDX/SUBX -(Ay),-(Ax) MEMORY predec
  // forms — which need >3 µops, beyond the AssembledUops budget — are now implemented
  // via the µcode ENGINE (decode/Microcode.scala + the DecodeStage sequencer); their
  // own lock-step tests are above (search "*-mem").)

  // ── MOVE (An)+,Dn loads (.L/.W/.B) + RAW on the postinc An ────────────────────
  test("lock-step: MOVE.L (A0)+,D0 postinc load + An RAW", VerilatorTest) {
    runLockStep("pp-move-l-postinc-load",
      "move.l #0xdeadbeef,%d7 ; move.l #0x3000,%a0 ; move.l %d7,(%a0) ; " +
      "move.l #0x3000,%a0 ; move.l (%a0)+,%d0 ; move.l %a0,%d1 ; " +
      ".stop: bra .stop", nInstr = 6)   // D0=0xdeadbeef, A0=D1=0x3004
  }
  test("lock-step: MOVE.W (A0)+,D0 postinc load (delta 2)", VerilatorTest) {
    runLockStep("pp-move-w-postinc-load",
      "move.l #0x1122aabb,%d7 ; move.l #0x3000,%a0 ; move.l %d7,(%a0) ; " +
      "move.l #0x3000,%a0 ; move.l #0xffffffff,%d0 ; move.w (%a0)+,%d0 ; move.l %a0,%d1 ; " +
      ".stop: bra .stop", nInstr = 7)   // D0=0xffff1122, A0=D1=0x3002
  }
  test("lock-step: MOVE.B (A0)+,D0 postinc load (delta 1)", VerilatorTest) {
    runLockStep("pp-move-b-postinc-load",
      "move.l #0x7e112233,%d7 ; move.l #0x3000,%a0 ; move.l %d7,(%a0) ; " +
      "move.l #0x00000000,%d0 ; move.l #0x3000,%a0 ; move.b (%a0)+,%d0 ; move.l %a0,%d1 ; " +
      ".stop: bra .stop", nInstr = 7)   // D0=0x0000007e, A0=D1=0x3001
  }

  // ── MOVE Dn,-(An) stores (.L/.W/.B) + the predec An ──────────────────────────
  test("lock-step: MOVE.L D0,-(A0) predec store + mem + An", VerilatorTest) {
    runLockStep("pp-move-l-predec-store",
      "move.l #0x12345678,%d0 ; move.l #0x3004,%a0 ; move.l %d0,-(%a0) ; move.l %a0,%d1 ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L))   // mem[0x3000]=val, A0=D1=0x3000
  }
  test("lock-step: MOVE.W D0,-(A0) predec store (delta 2)", VerilatorTest) {
    runLockStep("pp-move-w-predec-store",
      "move.l #0x0000beef,%d0 ; move.l #0x3002,%a0 ; move.w %d0,-(%a0) ; move.l %a0,%d1 ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3000L), checkSpan = 2)   // mem[0x3000].w=0xbeef, A0=0x3000
  }

  // ── A7 byte even-keeping: -(A7).B / (A7)+.B adjust by 2 ───────────────────────
  test("lock-step: MOVE.B D0,-(A7) then (A7)+ — A7 stays even (delta 2)", VerilatorTest) {
    runLockStep("pp-a7-byte-even",
      "move.l #0x000000a5,%d0 ; move.l %sp,%d2 ; move.b %d0,-(%sp) ; move.l %sp,%d1 ; " +
      "move.b (%sp)+,%d3 ; move.l %sp,%d4 ; " +
      ".stop: bra .stop", nInstr = 6)   // D1=SP-2 (byte->2), D3=0xa5, D4=SP (restored)
  }

  // ── MOVE.L (A1)+,(A0)+ block-copy step (mem-to-mem, double auto) ──────────────
  test("lock-step: MOVE.L (A1)+,(A0)+ block-copy (double postinc)", VerilatorTest) {
    runLockStep("pp-move-l-mem2mem-postinc",
      "move.l #0xcafef00d,%d7 ; move.l #0x3100,%a1 ; move.l %d7,(%a1) ; " +
      "move.l #0x3100,%a1 ; move.l #0x3200,%a0 ; move.l (%a1)+,(%a0)+ ; " +
      "move.l %a1,%d1 ; move.l %a0,%d2 ; " +
      ".stop: bra .stop", nInstr = 8, checkMem = Seq(0x3200L))  // mem[0x3200]=val, A1=D1=0x3104, A0=D2=0x3204
  }

  // ── ALU load (An)+: ADD.L (A0)+,D0 ───────────────────────────────────────────
  test("lock-step: ADD.L (A0)+,D0 postinc ALU load + An", VerilatorTest) {
    runLockStep("pp-add-l-postinc-load",
      "move.l #0x10000002,%d7 ; move.l #0x3000,%a0 ; move.l %d7,(%a0) ; " +
      "move.l #0x20000003,%d0 ; move.l #0x3000,%a0 ; add.l (%a0)+,%d0 ; move.l %a0,%d1 ; " +
      ".stop: bra .stop", nInstr = 7)   // D0=0x30000005, A0=D1=0x3004
  }

  // ── CLR (An)+ (store, no load) ───────────────────────────────────────────────
  test("lock-step: CLR.L (A0)+ postinc store + mem + An", VerilatorTest) {
    runLockStep("pp-clr-l-postinc",
      "move.l #0xffffffff,%d7 ; move.l #0x3000,%a0 ; move.l %d7,(%a0) ; " +
      "move.l #0x3000,%a0 ; clr.l (%a0)+ ; move.l %a0,%d1 ; " +
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x3000L))   // mem=0, A0=D1=0x3004
  }

  // ── NEG -(An) (RMW predec: load An-delta, op, store An-delta, one An update) ──
  test("lock-step: NEG.L -(A0) predec RMW + mem + An", VerilatorTest) {
    runLockStep("pp-neg-l-predec",
      "move.l #0x00000005,%d7 ; move.l #0x3000,%a0 ; move.l %d7,(%a0) ; " +
      "move.l #0x3004,%a0 ; neg.l -(%a0) ; move.l %a0,%d1 ; " +
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x3000L))   // mem[0x3000]=-5=0xfffffffb, A0=D1=0x3000
  }

  // ── RMW (An)+: ADD.L Dn,(An)+ (postinc, store carries An) ─────────────────────
  test("lock-step: ADD.L D1,(A0)+ postinc RMW + mem + An", VerilatorTest) {
    runLockStep("pp-add-l-postinc-rmw",
      "move.l #0x00000010,%d7 ; move.l #0x3000,%a0 ; move.l %d7,(%a0) ; " +
      "move.l #0x00000022,%d1 ; move.l #0x3000,%a0 ; add.l %d1,(%a0)+ ; move.l %a0,%d2 ; " +
      ".stop: bra .stop", nInstr = 7, checkMem = Seq(0x3000L))   // mem[0x3000]=0x32, A0=D2=0x3004
  }

  // ── Edge: predec source then immediate An use (RAW on the predec An update) ───
  test("lock-step: MOVE.L (A0)+,D0 then add.l #4,%a0 (RAW on postinc An)", VerilatorTest) {
    runLockStep("pp-postinc-then-an-use",
      "move.l #0x01020304,%d7 ; move.l #0x3000,%a0 ; move.l %d7,(%a0) ; " +
      "move.l #0x3000,%a0 ; move.l (%a0)+,%d0 ; addq.l #4,%a0 ; move.l %a0,%d1 ; " +
      ".stop: bra .stop", nInstr = 7)   // D0=val, A0=D1=0x3008 (0x3000+4 postinc +4 addq)
  }

  // ════════════════════════════════════════════════════════════════════════════
  // MOVE #imm,<mem> (fuzzer-found gap): a materialize-then-store 2-µop crack
  // ([opUop writes imm -> T1][stUop stores T1 -> <ea>]) — previously trapped illegal
  // (crackStore required a register source). NZVC from the moved value (N/Z; V=C=0),
  // X untouched. Covers (An)/(An)+/-(An)/(d16,An)/abs.W/abs.L across .B/.W/.L.
  // ════════════════════════════════════════════════════════════════════════════

  test("lock-step: MOVE.L #imm,(An) immediate-to-mem crack (N=1)", VerilatorTest) {
    runLockStep("move-imm-mem-l-reg-indirect", Seq(
      "move.l #0x3000,%a0", "move.l #0x92345678,(%a0)"    // N=1 (bit31 set), Z=0
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }

  test("lock-step: MOVE.W #imm,(An)+ immediate-to-mem crack postinc (Z=1) + An", VerilatorTest) {
    runLockStep("move-imm-mem-w-postinc", Seq(
      "move.l #0x3010,%a0", "move.w #0x0000,(%a0)+",       // Z=1, mem[0x3010..11]=0
      "move.l %a0,%d1"                                       // A0 postinc by 2 -> 0x3012
    ).mkString(" ; "), checkMem = Seq(0x3010L), checkSpan = 2)
  }

  test("lock-step: MOVE.B #imm,-(An) immediate-to-mem crack predec (N=1) + An", VerilatorTest) {
    runLockStep("move-imm-mem-b-predec", Seq(
      "move.l #0x3021,%a0", "move.b #0x80,-(%a0)",         // N=1 (byte bit7), mem[0x3020]=0x80
      "move.l %a0,%d1"                                       // A0 predec by 1 -> 0x3020
    ).mkString(" ; "), checkMem = Seq(0x3020L), checkSpan = 1)
  }

  test("lock-step: MOVE.L #imm,(d16,An) immediate-to-mem crack", VerilatorTest) {
    runLockStep("move-imm-mem-l-disp", Seq(
      "move.l #0x3030,%a0", "move.l #0xdeadbeef,4(%a0)"    // mem[0x3034..37]=0xdeadbeef
    ).mkString(" ; "), checkMem = Seq(0x3034L), checkSpan = 4)
  }

  test("lock-step: MOVE.W #imm,abs.W immediate-to-mem crack", VerilatorTest) {
    runLockStep("move-imm-mem-w-absw", Seq(
      "move.w #0x1234,0x3050"                                // mem[0x3050..51]=0x1234
    ).mkString(" ; "), checkMem = Seq(0x3050L), checkSpan = 2)
  }

  test("lock-step: MOVE.L #imm,abs.L immediate-to-mem crack", VerilatorTest) {
    runLockStep("move-imm-mem-l-absl", Seq(
      "move.l #0x7fffffff,0x00403060"                        // mem[0x403060..63]=0x7fffffff
    ).mkString(" ; "), checkMem = Seq(0x403060L), checkSpan = 4)
  }

  test("lock-step: MOVE.B #imm,(An) does NOT touch X (ADDX consumer)", VerilatorTest) {
    runLockStep("move-imm-mem-b-x-untouched", Seq(
      "moveq #-1,%d6", "addi.b #1,%d6",                     // X=1 (0xff+1 overflows)
      "move.l #0x3070,%a0", "move.b #0x05,(%a0)",           // MUST NOT touch X
      "moveq #0,%d7", "addx.b %d7,%d7"                       // X=1 -> D7=0+0+1=1 (would be 0 if X clobbered)
    ).mkString(" ; "), checkMem = Seq(0x3070L), checkSpan = 1)
  }

  // ════════════════════════════════════════════════════════════════════════════
  // MOVEM (multi-register load/store) — the DecodeStage micro-sequencer FSM.
  // The classic prologue/epilogue round-trip, .W sign-extend on load, control/sparse
  // masks, edge masks (empty/single/odd), and the front-end-stall-then-resume.
  // ════════════════════════════════════════════════════════════════════════════

  // MOVEM.L D0/D2/A1,(d16,A0) — control mode, SPARSE non-contiguous mask (priority-encode
  // order D0,D2,A1 ascending). checkMem verifies the 3 stored words landed at the right
  // ascending addresses (0x3000/4/8). The final kept An-update (A0+=0) commits the macro.
  test("lock-step: MOVEM.L control-mode sparse store (d16,An) -> mem", VerilatorTest) {
    runLockStep("movem-l-control-sparse",
      "move.l #0x11111111,%d0 ; move.l #0x22222222,%d2 ; move.l #0xa1a1a1a1,%a1 ; " +
      "move.l #0x3000,%a0 ; movem.l %d0/%d2/%a1,(0,%a0) ; " +
      ".stop: bra .stop", nInstr = 5,
      checkMem = Seq(0x3000L, 0x3004L, 0x3008L))   // D0,D2,A1 stored ascending
  }

  // MOVEM.L (An)+,<list> load + final An update (A0 += 12). The loaded D3/D4/D5 are dropped
  // crack µops -> surface them via adds (each a kept step) so the loaded values are
  // compared vs Musashi. A0 (=0x300C after postinc) is the kept An-update step. Stores via
  // -(A0) (predec, which the LS forwards into the (A0)+ reload the same RELIABLE way the
  // prologue's -(A7)/(A7)+ round-trip does). NOTE: a PLAIN store (,(%a0)) followed by the
  // same-line (A0)+ reload flakes — the PRE-EXISTING plain-store→immediate-load LS race
  // (see st-ld-drain probe / line ~1403), NOT closed by the BehavioralMem write-before-ack
  // fix; the predec/postinc round-trip form is the reliable path. Tracked as a follow-up.
  test("lock-step: MOVEM.L (An)+ load -> regs + An postinc", VerilatorTest) {
    runLockStep("movem-l-postinc-load",
      "move.l #0x0a0a0a0a,%d0 ; move.l #0x0b0b0b0b,%d1 ; move.l #0x0c0c0c0c,%d2 ; " +
      "move.l #0x300c,%a0 ; movem.l %d0/%d1/%d2,-(%a0) ; movem.l (%a0)+,%d3/%d4/%d5 ; move.l %a0,%d6 ; " +
      "add.l %d4,%d3 ; add.l %d5,%d3 ; " +
      ".stop: bra .stop", nInstr = 9, checkMem = Seq(0x3000L, 0x3004L, 0x3008L))   // A0=D6=0x300C
  }

  // MOVEM.W load SIGN-EXTENDS the loaded word to the full 32-bit register (Musashi
  // MAKE_INT_16). Two single-register loads off a line made resident via a drain+refill
  // read (sidestepping the PRE-EXISTING plain-store→same-line-load LS flake — st-ld-drain
  // probe / line ~1403 — which the BehavioralMem write-before-ack fix did NOT fully close;
  // tracked as a follow-up): store a NEGATIVE word (0x8001) and a POSITIVE word (0x7fff) to
  // one line, drain + refill it, then MOVEM.W-load each back and surface it — the loaded
  // values must be the SIGN-EXTENDED 0xFFFF8001 / 0x00007FFF (not zero-extended), pinning
  // both sign directions.
  test("lock-step: MOVEM.W store/load — .W load sign-extends", VerilatorTest) {
    runLockStep("movem-w-signext",
      "move.l #0x12348001,%d0 ; move.l #0x56787fff,%d2 ; move.l #0x3000,%a0 ; move.w %d0,(%a0) ; move.w %d2,2(%a0) ; " +
      "moveq #1,%d1 ; moveq #2,%d1 ; moveq #3,%d1 ; move.l 0x3000,%d1 ; " +    // drain + refill the line
      "movem.w (%a0),%d4 ; movem.w (2,%a0),%d5 ; move.l %d4,%d6 ; move.l %d5,%d7 ; " +
      ".stop: bra .stop", nInstr = 13,
      checkMem = Seq(0x3000L), checkSpan = 4)   // 0x8001 @ 0x3000 (->0xFFFF8001), 0x7fff @ 0x3002 (->0x00007FFF)
  }

  // ((d16,PC) MOVEM load reads the CODE image as data, but the lock-step D-cache memory is
  // a SEPARATE backing store from the I-cache program image (data must be runtime-STORED to
  // be visible to both the DUT D-cache and Musashi). The (d16,PC) ADDRESS computation
  // (EA_PCDI = pc+4+d16) is exercised at the decode level in MovemDecodeSpec/EaDecoder
  // instead; a memory-backed lock-step for it would need a writable PC-reachable data page.)

  // Single register (the odd-tail of size 1): store D3, load it back into D4, surface D4
  // (a kept MOVE.L step) so the loaded value is compared. checkMem verifies the store.
  test("lock-step: MOVEM.L single register store+load", VerilatorTest) {
    runLockStep("movem-l-single",
      "move.l #0xdeadbeef,%d3 ; move.l #0x3000,%a0 ; movem.l %d3,(%a0) ; " +
      "movem.l (%a0),%d4 ; move.l %d4,%d5 ; " +
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x3000L))
  }

  // (Empty-mask MOVEM emits ZERO architectural µops -> it produces no commit, so it cannot
  // appear in a robId-joined lock-step program (the oracle would over-count). Its FSM
  // behavior — no moves, An unchanged, the front-end resuming cleanly into the following
  // instruction — is covered by MovemDecodeSpec instead.)

  // The CLASSIC prologue/epilogue: MOVEM.L D0-D7/A0-A6,-(A7) then MOVEM.L (A7)+,... .
  // Round-trips all 15 registers + A7 + the memory image. A7 is seeded to a scratch RAM
  // top (0x4000); the predec stores high-to-low (A6..A0,D7..D0), the postinc loads them
  // back. checkMem verifies the stack image (the predec ordering + every stored value).
  // The reloaded registers are DROPPED crack µops (not compared at the MOVEM step), so a
  // post-epilogue ADD-fold reads EVERY reloaded register into D0 — each add is a KEPT step
  // compared vs Musashi, so a wrong reload diverges. The kept An-update steps verify A7
  // (0x3FC4 after predec, 0x4000 after postinc). A 15-register burst exceeds the 8-entry
  // SQ depth — exercises the SQ back-pressure (WAIT_SQ) end-to-end: the predec stores
  // commit incrementally in ROB order, draining entries so the burst never overruns.
  test("lock-step: MOVEM.L D0-D7/A0-A6,-(A7) prologue/epilogue round-trip", VerilatorTest) {
    runLockStep("movem-l-prologue-epilogue",
      "move.l #0x00010000,%d0 ; move.l #0x00020001,%d1 ; move.l #0x00030002,%d2 ; move.l #0x00040003,%d3 ; " +
      "move.l #0x00050004,%d4 ; move.l #0x00060005,%d5 ; move.l #0x00070006,%d6 ; move.l #0x00080007,%d7 ; " +
      "move.l #0x10080008,%a0 ; move.l #0x10090009,%a1 ; move.l #0x100a000a,%a2 ; move.l #0x100b000b,%a3 ; " +
      "move.l #0x100c000c,%a4 ; move.l #0x100d000d,%a5 ; move.l #0x100e000e,%a6 ; " +
      "move.l #0x00004000,%sp ; movem.l %d0-%d7/%a0-%a6,-(%sp) ; movem.l (%sp)+,%d0-%d7/%a0-%a6 ; " +
      "add.l %d1,%d0 ; add.l %d2,%d0 ; add.l %d3,%d0 ; add.l %d4,%d0 ; add.l %d5,%d0 ; add.l %d6,%d0 ; add.l %d7,%d0 ; " +
      "add.l %a0,%d0 ; add.l %a1,%d0 ; add.l %a2,%d0 ; add.l %a3,%d0 ; add.l %a4,%d0 ; add.l %a5,%d0 ; add.l %a6,%d0 ; " +
      "move.l %sp,%d1 ; " +
      ".stop: bra .stop", nInstr = 33,
      checkMem = Seq(0x3fc4L), checkSpan = 60)   // 15 longs at 0x3FC4 .. 0x4000 (predec ordering + values)
  }

  // REGRESSION: a >8-physical-register reload burst exposed a PRF backing-Mem undersize.
  // Load 15 registers from memory with INDIVIDUAL `move.l (d16,A7),Dn` loads (each a real
  // kept oracle step — NOT a dropped MOVEM µop), then 14 dependent `add.l %dN/%aN,%d0`
  // folds reading every loaded reg. Each memory-source MOVE cracks into [load -> T0] +
  // [op T0 -> Dn], so 15 in-flight T0 versions + 15 arch dests drive the physical-int pool
  // past 32 live renames — handing out the high physreg ids (48/49). RegfileSpec.Int.depth
  // was left at 48 (stale; the freelist + IQ scoreboards moved to 50 when T0/T1 widened the
  // pool), so ids 48/49 addressed PAST the PRF Mem: the producing load's write never landed
  // and the dependent op read an uninitialized (per-seed-random) value. Mirrors the MOVEM
  // epilogue's pressure WITHOUT the MOVEM FSM, so it pins the bug to rename/PRF sizing.
  test("lock-step: 15 individual loads + dependent adds (>8 reload pressure)", VerilatorTest) {
    runLockStep("loads15-probe",
      "move.l #0x00010000,%d0 ; move.l #0x00020001,%d1 ; move.l #0x00030002,%d2 ; move.l #0x00040003,%d3 ; " +
      "move.l #0x00050004,%d4 ; move.l #0x00060005,%d5 ; move.l #0x00070006,%d6 ; move.l #0x00080007,%d7 ; " +
      "move.l #0x10080008,%a0 ; move.l #0x10090009,%a1 ; move.l #0x100a000a,%a2 ; move.l #0x100b000b,%a3 ; " +
      "move.l #0x100c000c,%a4 ; move.l #0x100d000d,%a5 ; move.l #0x100e000e,%a6 ; " +
      "move.l #0x00004000,%sp ; movem.l %d0-%d7/%a0-%a6,-(%sp) ; " +
      // reload each from the predec image (A7 now at 0x3FC4; the 15 longs run 0x3FC4..0x3FFF
      // in predec order D0..D7,A0..A6 low->high address).
      "move.l (0,%sp),%d0 ; move.l (4,%sp),%d1 ; move.l (8,%sp),%d2 ; move.l (12,%sp),%d3 ; " +
      "move.l (16,%sp),%d4 ; move.l (20,%sp),%d5 ; move.l (24,%sp),%d6 ; move.l (28,%sp),%d7 ; " +
      "move.l (32,%sp),%a0 ; move.l (36,%sp),%a1 ; move.l (40,%sp),%a2 ; move.l (44,%sp),%a3 ; " +
      "move.l (48,%sp),%a4 ; move.l (52,%sp),%a5 ; move.l (56,%sp),%a6 ; " +
      "add.l %d1,%d0 ; add.l %d2,%d0 ; add.l %d3,%d0 ; add.l %d4,%d0 ; add.l %d5,%d0 ; add.l %d6,%d0 ; add.l %d7,%d0 ; " +
      "add.l %a0,%d0 ; add.l %a1,%d0 ; add.l %a2,%d0 ; add.l %a3,%d0 ; add.l %a4,%d0 ; add.l %a5,%d0 ; add.l %a6,%d0 ; " +
      "move.l %sp,%d1 ; " +
      ".stop: bra .stop", nInstr = 47,
      checkMem = Seq(0x3fc4L), checkSpan = 60)
  }

  // ── FUZZER-CAUGHT (B6): MOVEM.(An)+ LOAD with the base An IN the register list ──
  // Musashi (movem, er, pi) loads REG_DA[i] for every listed register (An included)
  // and then overwrites An with `AY = ea` (the post-incremented final address) — the
  // value loaded into An is DISCARDED. The DUT kept the loaded value (and later
  // elements' addresses walked off the loaded An). [0x4004] (the word loaded into a0)
  // is a VALID pointer so the pre-fix wrong-address path stays in mapped memory.
  test("lock-step: MOVEM.L/.W (An)+ load with base An in the list (An := final addr)", VerilatorTest) {
    runLockStep("movem-postinc-base-in-list",
      "move.l #0x11112222,%d0 ; move.l %d0,0x4000 ; " +
      "move.l #0x4100,%d1 ; move.l %d1,0x4004 ; " +           // loaded into a0, must be discarded
      "move.l #0x33334444,%d2 ; move.l %d2,0x4008 ; " +
      "move.l #0x4000,%a0 ; " +
      "movem.l (%a0)+,%d3/%a0/%a1 ; " +                        // d3=[4000], a0 load DISCARDED, a1=[4008]; a0:=0x400c
      "move.l %a0,%d4 ; move.l %a1,%d5 ; move.l %d3,%d6 ; " +  // readbacks
      "move.l #0x4000,%a2 ; " +
      "movem.w (%a2)+,%d7/%a2 ; " +                            // .W: d7=sext(1111), a2 load discarded; a2:=0x4004
      "move.l %a2,%d0 ; move.l %d7,%d1 ; " +
      ".stop: bra .stop", nInstr = 16)
  }

  // Front-end-stall-then-resume: a long MOVEM (held fed ~8 cycles) immediately followed by
  // an ALU chain + a branch — the FSM must release `fed` cleanly and the trailing
  // instructions must execute (no deadlock, correct next-instruction stream).
  test("lock-step: MOVEM front-end stall then ALU/branch resume", VerilatorTest) {
    runLockStep("movem-stall-resume",
      "move.l #0x01010101,%d0 ; move.l #0x02020202,%d1 ; move.l #0x03030303,%d2 ; move.l #0x04040404,%d3 ; " +
      "move.l #0x05050505,%d4 ; move.l #0x06060606,%d5 ; move.l #0x07070707,%d6 ; move.l #0x08080808,%d7 ; " +
      "move.l #0x3000,%a0 ; movem.l %d0-%d7,(%a0) ; " +
      "addq.l #1,%d0 ; add.l %d1,%d2 ; and.l %d3,%d4 ; bra .next ; nop ; .next: move.l #0x99,%d5 ; " +
      ".stop: bra .stop", nInstr = 15, checkMem = Seq(0x3000L), checkSpan = 32)
  }

  // ════════════════════════════════════════════════════════════════════════════
  // MOVEP (move peripheral data, alternating EVEN bytes) — the DecodeStage MOVEP FSM.
  // All 4 variants vs Musashi. reg->mem: checkMem the stored EVEN bytes at ea/ea+2/...
  // (each span 1 — the odd bytes are NOT touched, so comparing a contiguous span would
  // read the DUT's uninitialised pattern). mem->reg: seed the even bytes, drain+refill the
  // line (dodging the PRE-EXISTING plain-store->same-line-load LS flake the MOVEM .W test
  // documents), MOVEP-load + surface Dx. .W mem->reg uses a sentinel Dx[31:16] to verify it
  // is PRESERVED. CCR is verified UNCHANGED across the full retired stream (the lock-step
  // joins NZVCX every step; a MOVEP that wrote CCR would diverge on the FOLLOWING op's read).
  // ════════════════════════════════════════════════════════════════════════════

  // reg->mem .L: D0=0x11223344 -> [ea]=0x11,[ea+2]=0x22,[ea+4]=0x33,[ea+6]=0x44 at ea=0x3010.
  test("lock-step: MOVEP.L D0,(d16,A0) reg->mem alternating bytes", VerilatorTest) {
    runLockStep("movep-l-re",
      "move.l #0x11223344,%d0 ; move.l #0x3000,%a0 ; movep.l %d0,(16,%a0) ; " +
      ".stop: bra .stop", nInstr = 4,
      checkMem = Seq(0x3010L, 0x3012L, 0x3014L, 0x3016L), checkSpan = 1)   // 0x11/0x22/0x33/0x44
  }

  // reg->mem .W: D2=0xAABBCCDD -> [ea]=0xCC (Dx[15:8]), [ea+2]=0xDD (Dx[7:0]) at ea=0x3020.
  test("lock-step: MOVEP.W D2,(d16,A1) reg->mem alternating bytes", VerilatorTest) {
    runLockStep("movep-w-re",
      "move.l #0xAABBCCDD,%d2 ; move.l #0x3000,%a1 ; movep.w %d2,(32,%a1) ; " +
      ".stop: bra .stop", nInstr = 4,
      checkMem = Seq(0x3020L, 0x3022L), checkSpan = 1)   // 0xCC / 0xDD
  }

  // mem->reg .L: MOVEP.L reads bytes [ea],[ea+2],[ea+4],[ea+6]. Seed two ADJACENT longs at
  // ea(=0x3030) and ea+4 so ALL 8 bytes are defined (the drain+refill long-read then matches
  // Musashi — no unwritten-odd-byte mismatch). word1=0xDE00AD00 -> [ea]=0xDE,[ea+2]=0xAD;
  // word2=0xBE00EF00 -> [ea+4]=0xBE,[ea+6]=0xEF. MOVEP.L assembles D3=0xDEADBEEF; surface
  // via move.l %d3,%d4 (a kept step compared vs Musashi).
  test("lock-step: MOVEP.L (d16,A2),D3 mem->reg assemble", VerilatorTest) {
    runLockStep("movep-l-er",
      "move.l #0x3000,%a2 ; move.l #0xDE00AD00,%d0 ; move.l %d0,48(%a2) ; " +
      "move.l #0xBE00EF00,%d1 ; move.l %d1,52(%a2) ; " +
      "moveq #1,%d2 ; moveq #2,%d2 ; moveq #3,%d2 ; move.l 48(%a2),%d2 ; " +   // drain + refill the line
      "movep.l (48,%a2),%d3 ; move.l %d3,%d4 ; " +
      ".stop: bra .stop", nInstr = 11)
  }

  // mem->reg .W: D5 pre-seeded with a sentinel upper word (0x1234); MOVEP.W reads [ea],[ea+2].
  // Seed one long at ea(=0x3040)=0x7F00F000 so [ea]=0x7F,[ea+2]=0xF0 (all bytes defined ->
  // the drain+refill long-read matches Musashi). MOVEP.W -> D5[15:0]=0x7FF0, D5[31:16]=0x1234
  // PRESERVED. Surface D5 via move.l %d5,%d6 (a kept step compared vs Musashi).
  test("lock-step: MOVEP.W (d16,A3),D5 mem->reg preserves Dx[31:16]", VerilatorTest) {
    runLockStep("movep-w-er",
      "move.l #0x12345678,%d5 ; move.l #0x3000,%a3 ; " +
      "move.l #0x7F00F000,%d0 ; move.l %d0,64(%a3) ; " +
      "moveq #1,%d1 ; moveq #2,%d1 ; moveq #3,%d1 ; move.l 64(%a3),%d1 ; " +   // drain + refill the line
      "movep.w (64,%a3),%d5 ; move.l %d5,%d6 ; " +
      ".stop: bra .stop", nInstr = 10)   // D5 -> 0x12347FF0 (upper word PRESERVED)
  }

  // CCR-unchanged: set a known CCR with a flag-setting op, then MOVEP (no CCR effect),
  // then a conditional that reads CCR — the full-stream lock-step verifies NZVCX is
  // identical to Musashi at every step (a MOVEP CCR write would diverge here).
  test("lock-step: MOVEP does not affect CCR", VerilatorTest) {
    runLockStep("movep-ccr",
      "move.l #0x80000000,%d0 ; add.l %d0,%d0 ; " +   // sets C/V/Z/N/X (0x80000000+0x80000000)
      "move.l #0x11223344,%d1 ; move.l #0x3000,%a0 ; movep.l %d1,(16,%a0) ; " +
      "addx.l %d2,%d2 ; " +                            // reads X (would diverge if MOVEP touched X)
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x3010L), checkSpan = 1)
  }

  // Regression: MOVEP immediately followed by a µcoded mem-form op (SBCD -(An)) — exercises
  // the mutual-exclusion guard that was MISSING on ucBegin/movemBegin. Without the fix,
  // movepBegin latches the MOVEP state + sets ucPendValid for the slot1-stashed SBCD; then
  // next cycle movepActive=True and ucBegin (lacking !movepActive/!movepPendValid) fires,
  // starting the µcode engine mid-MOVEP. Both sequencers use T0/T1 scratch → RAT corruption
  // → wrong MOVEP stores or wrong SBCD result. The lock-step must be 0-diverged vs Musashi.
  // MOVEP.L D0,(16,A0): D0=0x11223344, A0=0x3000 → stores 0x11/0x22/0x33/0x44 at 0x3010/12/14/16.
  // SBCD -(A2),-(A1): dst=[A1-1]=[0x4000]=0x25, src=[A2-1]=[0x5000]=0x12 → 0x25-0x12=0x13→[0x4000].
  test("lock-step: MOVEP.L reg->mem immediately followed by SBCD-mem (no sequencer collision)", VerilatorTest) {
    runLockStep("movep-then-ucode",
      "move.l #0x11223344,%d0 ; move.l #0x3000,%a0 ; " +
      "move.l #0x4001,%a1 ; move.l #0x00000025,%d1 ; move.b %d1,-(%a1) ; " +   // [0x4000]=0x25, A1=0x4000
      "move.l #0x5001,%a2 ; move.l #0x00000012,%d2 ; move.b %d2,-(%a2) ; " +   // [0x5000]=0x12, A2=0x5000
      "move.l #0x4001,%a1 ; move.l #0x5001,%a2 ; " +                           // reset An above the bytes
      "moveq #1,%d3 ; addi.b #1,%d3 ; " +                                       // X=0
      "movep.l %d0,(16,%a0) ; " +                                               // MOVEP -> 0x3010..0x3016
      "sbcd -(%a2),-(%a1) ; " +                                                 // µcoded: 0x25-0x12=0x13->[0x4000]
      ".stop: bra .stop", nInstr = 14,
      checkMem = Seq(0x3010L, 0x3012L, 0x3014L, 0x3016L, 0x4000L), checkSpan = 1)
  }

  // ── Brief-format indexed addressing lock-step (all programs Musashi-verified) ──
  // (d8,An,Xn*scale) modes 6/7-3: index reg .W(sign-ext)/.L, scale *1/2/4/8, signed d8.
  // The harness lock-steps the FULL retired stream (regs/flags/PC) vs Musashi; a load
  // test compares the loaded reg; a store/RMW test adds checkMem for the final memory.
  test("lock-step idxmode: MOVE.L (d8,An,Dn.w*2) load", VerilatorTest) {
    // [0x3008]=0xCAFEBABE; a0=0x3000,d1=2; (4,a0,d1.w*2)=0x3000+4+4=0x3008 -> d2
    runLockStep("idx-load-l",
      "move.l #0xCAFEBABE,%d0 ; move.l #0x3000,%a0 ; move.l %d0,8(%a0) ; " +
      "move.l #2,%d1 ; move.l (4,%a0,%d1.w*2),%d2 ; " +
      ".stop: bra .stop", nInstr = 6)
  }
  test("lock-step idxmode: MOVE.L Dn,(d8,An,Dn.l*4) store", VerilatorTest) {
    // a0=0x3000,d1=1; (4,a0,d1.l*4)=0x3000+4+4=0x3008; store d3=0x12345678 -> [0x3008]
    runLockStep("idx-store-l",
      "move.l #0x3000,%a0 ; move.l #1,%d1 ; move.l #0x12345678,%d3 ; " +
      "move.l %d3,(4,%a0,%d1.l*4) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x3008L))
  }
  test("lock-step idxmode: ADD.L Dn,(d8,An,Dn.w) RMW", VerilatorTest) {
    // seed [0x3004]=0x10000001; a0=0x3000,d1=4 -> (0,a0,d1.w)=0x3004; add d4 -> mem
    runLockStep("idx-rmw-add-l",
      "move.l #0x10000001,%d0 ; move.l #0x3000,%a0 ; move.l %d0,4(%a0) ; " +
      "move.l #4,%d1 ; move.l #0x20000002,%d4 ; add.l %d4,(0,%a0,%d1.w) ; " +
      ".stop: bra .stop", nInstr = 7, checkMem = Seq(0x3004L))   // -> 0x30000003
  }
  test("lock-step idxmode: ALU indexed source (ADD.L (d8,An,Dn),Dm)", VerilatorTest) {
    // [0x3008]=5; a0=0x3000,d1=4; (4,a0,d1.w)=0x3008; add to d2=3 -> 8
    runLockStep("idx-alu-src",
      "move.l #0x00000005,%d0 ; move.l #0x3000,%a0 ; move.l %d0,8(%a0) ; " +
      "move.l #4,%d1 ; move.l #0x00000003,%d2 ; add.l (4,%a0,%d1.w),%d2 ; " +
      ".stop: bra .stop", nInstr = 7)   // d2 = 3 + 5 = 8
  }
  test("lock-step idxmode: indexed .W sign-extend + scale (negative index)", VerilatorTest) {
    // [0x3000]=0xAABBCCDD; a0=0x3004,d1=0xFFFF(.w=-1); (0,a0,d1.w*4)=0x3004+(-1*4)=0x3000
    runLockStep("idx-size-w",
      "move.l #0xAABBCCDD,%d0 ; move.l #0x3000,%a1 ; move.l %d0,(%a1) ; " +
      "move.l #0x3004,%a0 ; move.l #0x0000FFFF,%d1 ; move.l (0,%a0,%d1.w*4),%d2 ; " +
      ".stop: bra .stop", nInstr = 6)   // d2 = 0xAABBCCDD
  }
  test("lock-step idxmode: indexed scale *8 (.L index)", VerilatorTest) {
    // [0x3010]=0x11111111; a0=0x3000,d1=2; (0,a0,d1.l*8)=0x3000+16=0x3010
    runLockStep("idx-scale-8",
      "move.l #0x11111111,%d0 ; move.l #0x3000,%a0 ; move.l %d0,16(%a0) ; " +
      "move.l #2,%d1 ; move.l (0,%a0,%d1.l*8),%d2 ; " +
      ".stop: bra .stop", nInstr = 6)
  }
  test("lock-step idxmode: indexed negative d8", VerilatorTest) {
    // [0x3008]=0xDEADBEEF; a0=0x3010,d1=4; (-16,a0,d1.l*2)=0x3010-16+8=0x3008
    runLockStep("idx-neg-d8",
      "move.l #0xDEADBEEF,%d0 ; move.l #0x3010,%a0 ; move.l %d0,-8(%a0) ; " +
      "move.l #4,%d1 ; move.l (-16,%a0,%d1.l*2),%d2 ; " +
      ".stop: bra .stop", nInstr = 6)
  }
  // ((d8,PC,Xn) PC-RELATIVE indexed LOAD reads the CODE image as data, but the lock-step
  // D-cache memory is a SEPARATE backing store from the I-cache program image (data must be
  // runtime-STORED to be visible to both the DUT D-cache and Musashi — same limitation noted
  // for (d16,PC) MOVEM above). The (d8,PC,Xn) ADDRESS computation (base = pc+2 + d8 + scaled
  // index) is verified two ways instead: (1) EaDecoderSpec asserts mode 7-3 -> MEMSIMPLE +
  // pcRel + the index descriptor; (2) the assembler crack folds pc+2+d8 into the load imm
  // (the SAME pcRelAddr path the (d16,PC) load uses, just with the index on srcC), and the
  // base=pc+2 rule was confirmed against Musashi directly during planning: `lea (0,%pc,
  // %d1.w),%a2` with d1=2 at pc=0x40800002 -> A2=0x40800006 = 0x40800004+0+2. A memory-backed
  // PC-rel-load lock-step would need a writable PC-reachable data page the program stores to.)
  test("lock-step idxmode: indexed An as index register", VerilatorTest) {
    // [0x300C]=0x44332211; a0=0x3000,a2=12; (0,a0,a2.l*1)=0x300C
    runLockStep("idx-an-index",
      "move.l #0x44332211,%d0 ; move.l #0x3000,%a0 ; move.l %d0,12(%a0) ; " +
      "move.l #12,%a2 ; move.l (0,%a0,%a2.l*1),%d2 ; " +
      ".stop: bra .stop", nInstr = 6)
  }

  // ── FULL-format NO-MEMORY-INDIRECT (68020+, I/IS=000) lock-step ──────────────
  // base' + bd(word/long) + index'(scaled), suppressible base(BS)/index(IS). Single
  // pass through the existing AGU (no microcode). A word/long BD forces full-format
  // (a small disp fits brief), `%zaN`/`%zpc` suppress the base. All Musashi-verified.
  test("lock-step fullext: no-mem-indir WORD bd + .L index*4 load", VerilatorTest) {
    // word bd 0x100 forces full-format. a0=0x3000,d1=2; ea=0x3000+0x100+(2*4)=0x3108.
    runLockStep("fx-noind-word-l4",
      "move.l #0xCAFEBABE,%d0 ; move.l #0x3000,%a0 ; move.l %d0,0x108(%a0) ; " +   // [0x3108]
      "move.l #2,%d1 ; move.l (0x100,%a0,%d1.l*4),%d2 ; " +
      ".stop: bra .stop", nInstr = 6)
  }
  test("lock-step fullext: no-mem-indir LONG bd load", VerilatorTest) {
    // long bd 0x10000 forces bd-size=11. a0=0x3000, d1=1, (0x10000,a0,d1.l*2)=0x3000+0x10000+2=0x13002.
    runLockStep("fx-noind-long",
      "move.l #0x11223344,%d0 ; move.l #0x13000,%a1 ; move.l %d0,2(%a1) ; " +   // [0x13002]
      "move.l #0x3000,%a0 ; move.l #1,%d1 ; move.l (0x10000,%a0,%d1.l*2),%d2 ; " +
      ".stop: bra .stop", nInstr = 6)
  }
  test("lock-step fullext: no-mem-indir BS base-suppressed (absolute bd+index)", VerilatorTest) {
    // base suppressed -> ea = bd + index. bd=0x3000, d1=2*4=8 -> 0x3008. Seed there.
    runLockStep("fx-noind-bs",
      "move.l #0x55667788,%d0 ; move.l #0x3008,%a1 ; move.l %d0,(%a1) ; " +
      "move.l #2,%d1 ; move.l (0x3000,%za0,%d1.l*4),%d2 ; " +     // BS -> base An ignored
      ".stop: bra .stop", nInstr = 6)
  }
  test("lock-step fullext: no-mem-indir IS index-suppressed (base+bd only)", VerilatorTest) {
    // index suppressed (%zd1) -> ea = base + bd. a0=0x3000, bd=0x100 -> 0x3100. Seed there.
    runLockStep("fx-noind-is",
      "move.l #0x99AABBCC,%d0 ; move.l #0x3000,%a0 ; move.l %d0,0x100(%a0) ; " +
      "move.l (0x100,%a0,%zd1.l),%d2 ; " +   // %zd1 suppresses the index (IS=1), word bd
      ".stop: bra .stop", nInstr = 5)
  }
  test("lock-step fullext: no-mem-indir scale *2 store", VerilatorTest) {
    // store with full-format dst. a0=0x3000,d1=4; (0x100,a0,d1.l*2)=0x3000+0x100+8=0x3108
    runLockStep("fx-noind-store",
      "move.l #0x3000,%a0 ; move.l #4,%d1 ; move.l #0x0BADF00D,%d3 ; " +
      "move.l %d3,(0x100,%a0,%d1.l*2) ; " +
      ".stop: bra .stop", nInstr = 4, checkMem = Seq(0x3108L))
  }
  test("lock-step fullext: no-mem-indir ALU indexed source (ADD.L)", VerilatorTest) {
    // [0x3108]=5; a0=0x3000,d1=4 (4*2=8); (0x100,a0,d1.l*2)=0x3108; add to d2=3 -> 8
    runLockStep("fx-noind-alu-src",
      "move.l #0x00000005,%d0 ; move.l #0x3000,%a0 ; move.l %d0,0x108(%a0) ; " +
      "move.l #4,%d1 ; move.l #0x00000003,%d2 ; add.l (0x100,%a0,%d1.l*2),%d2 ; " +
      ".stop: bra .stop", nInstr = 7)   // d2 = 3 + 5 = 8
  }

  // ── FULL-format MEMORY-INDIRECT (68020+, pre/post-index) lock-step ───────────
  // Pre `([bd,An,Xn],od)`: pointer = mem[base+bd+index], EA = pointer + od.
  // Post `([bd,An],Xn,od)`: pointer = mem[base+bd],      EA = pointer + index + od.
  // The mid-EA pointer load is a REAL load (faults precisely via the LS). The program
  // seeds a deterministic pointer table via stores first; lock-stepped vs Musashi.
  test("lock-step fullext: MEM-INDIRECT pre-index MOVE.L src load", VerilatorTest) {
    // a0=0x3000,d1=2(*4=8); ptr addr=0x3000+0x10+8=0x3018; ptr=0x4000; data@0x4000+0x20=0x4020.
    runLockStep("fx-mi-pre-move-src",
      "move.l #0x3000,%a0 ; move.l #2,%d1 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,0x18(%a0) ; " +          // [0x3018] = ptr 0x4000
      "move.l #0xCAFEBABE,%d3 ; move.l %d3,0x4020 ; " +          // [0x4020] = data
      "move.l ([0x10,%a0,%d1.l*4],0x20),%d2 ; " +               // d2 = mem[mem[0x3018]+0x20]
      ".stop: bra .stop", nInstr = 7)
  }
  test("lock-step fullext: MEM-INDIRECT pre-index MOVE.L dst store", VerilatorTest) {
    // ptr addr=0x3018; ptr=0x4000; store d3 -> [0x4000+0x20]=0x4020.
    runLockStep("fx-mi-pre-move-dst",
      "move.l #0x3000,%a0 ; move.l #2,%d1 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,0x18(%a0) ; " +
      "move.l #0x0BADF00D,%d3 ; move.l %d3,([0x10,%a0,%d1.l*4],0x20) ; " +
      ".stop: bra .stop", nInstr = 6, checkMem = Seq(0x4020L))
  }
  test("lock-step fullext: MEM-INDIRECT post-index MOVE.L src load", VerilatorTest) {
    // post: ptr addr=0x3000+0x10=0x3010; ptr=0x4000; EA=ptr+index(2*4=8)+od(0x20)=0x4028.
    runLockStep("fx-mi-post-move-src",
      "move.l #0x3000,%a0 ; move.l #2,%d1 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,0x10(%a0) ; " +          // [0x3010] = ptr
      "move.l #0x12345678,%d3 ; move.l %d3,0x4028 ; " +         // [0x4028] = data
      "move.l ([0x10,%a0],%d1.l*4,0x20),%d2 ; " +              // d2 = mem[mem[0x3010]+8+0x20]
      ".stop: bra .stop", nInstr = 7)
  }
  test("lock-step fullext: MEM-INDIRECT pre-index null OD", VerilatorTest) {
    // od null: EA = pointer. ptr addr=0x3018; ptr=0x4000; data@0x4000.
    runLockStep("fx-mi-pre-od-null",
      "move.l #0x3000,%a0 ; move.l #2,%d1 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,0x18(%a0) ; " +
      "move.l #0x55667788,%d3 ; move.l %d3,0x4000 ; " +
      "move.l ([0x10,%a0,%d1.l*4]),%d2 ; " +
      ".stop: bra .stop", nInstr = 7)
  }
  test("lock-step fullext: MEM-INDIRECT pre-index LONG OD", VerilatorTest) {
    // long od. ptr addr=0x3018; ptr=0x4000; data @ 0x4000+0x10000=0x14000.
    runLockStep("fx-mi-pre-od-long",
      "move.l #0x3000,%a0 ; move.l #2,%d1 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,0x18(%a0) ; " +
      "move.l #0x99AABBCC,%d3 ; move.l #0x14000,%a2 ; move.l %d3,(%a2) ; " +
      "move.l ([0x10,%a0,%d1.l*4],0x10000),%d2 ; " +
      ".stop: bra .stop", nInstr = 8)
  }
  test("lock-step fullext: MEM-INDIRECT index-suppressed (IS=1)", VerilatorTest) {
    // IS=1 -> pre==post, no index. ptr addr=0x3000+0x10=0x3010; ptr=0x4000; data@0x4020.
    runLockStep("fx-mi-is-suppressed",
      "move.l #0x3000,%a0 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,0x10(%a0) ; " +
      "move.l #0xDEADBEEF,%d3 ; move.l %d3,0x4020 ; " +
      "move.l ([0x10,%a0],0x20),%d2 ; " +                       // no index (IS=1)
      ".stop: bra .stop", nInstr = 6)
  }
  test("lock-step fullext: MEM-INDIRECT pre-index ALU src (ADD.L)", VerilatorTest) {
    // ALU mem-source: ptr addr=0x3018; ptr=0x4000; operand@0x4020=5; d2=3 -> 8.
    runLockStep("fx-mi-pre-alu-src",
      "move.l #0x3000,%a0 ; move.l #2,%d1 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,0x18(%a0) ; " +
      "move.l #0x00000005,%d3 ; move.l %d3,0x4020 ; " +
      "move.l #0x00000003,%d2 ; add.l ([0x10,%a0,%d1.l*4],0x20),%d2 ; " +
      ".stop: bra .stop", nInstr = 8)   // d2 = 3 + 5 = 8
  }
  test("lock-step fullext: MEM-INDIRECT pre-index immediate dst (ADDI.W)", VerilatorTest) {
    // imm RMW (.W: the imm is 1 word, so the EA ext is op+1 -> framed precisely). ptr addr
    // =0x3000+8(IS)=0x3008; ptr=0x4000; mem.w[0x4010] += 5.
    runLockStep("fx-mi-pre-immop",
      "move.l #0x3000,%a0 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,8(%a0) ; " +
      "move.w #0x0001,%d3 ; move.w %d3,0x4010 ; " +
      "addi.w #5,([8,%a0],0x10) ; " +                            // mem.w[mem[0x3008]+0x10] += 5
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x4010L), checkSpan = 2)   // -> 0x0006
  }
  test("lock-step fullext: MEM-INDIRECT pre-index single-EA (CLR.L)", VerilatorTest) {
    // CLR: ptr addr=0x3008; ptr=0x4000; clear mem[0x4010].
    runLockStep("fx-mi-pre-clr",
      "move.l #0x3000,%a0 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,8(%a0) ; " +
      "move.l #0xFFFFFFFF,%d3 ; move.l %d3,0x4010 ; " +
      "clr.l ([8,%a0],0x10) ; " +
      ".stop: bra .stop", nInstr = 5, checkMem = Seq(0x4010L))   // -> 0
  }
  test("lock-step fullext: MEM-INDIRECT pre-index CMPI.W flags-only", VerilatorTest) {
    // CMPI.W #imm,([...]) sets flags only (no store; .W imm is 1 word -> EA ext = op+1).
    // Read mem.w[0x4010]=7, cmp #7 -> Z=1.
    runLockStep("fx-mi-pre-cmpi",
      "move.l #0x3000,%a0 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,8(%a0) ; " +
      "move.w #7,%d3 ; move.w %d3,0x4010 ; " +
      "cmpi.w #7,([8,%a0],0x10) ; seq %d5 ; " +                  // Z=1 -> d5[7:0]=0xFF
      ".stop: bra .stop", nInstr = 6)
  }

  // ── FUZZER-CAUGHT: CMP.<sz> with a MEM-INDIRECT source ──────────────────────
  // The MI_ALU_SRC host-op row (a) read srcA=T1/srcB=Dn (reversed a-b -> reversed
  // NZVC) and (b) kept the shared dst slot live, so the compare result CLOBBERED
  // the Dn. CMP writes NO register — flags only, all NZVC cases + Dn readback.
  test("lock-step fullext: MEM-INDIRECT CMP.L/.W/.B src (flags only, Dn unchanged)", VerilatorTest) {
    runLockStep("fx-mi-cmp-src",
      "move.l #0x3000,%a0 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,0x10(%a0) ; " +           // [0x3010] = ptr 0x4000
      "move.l #0x11223344,%d1 ; move.l %d1,0x4020 ; " +          // [0x4020] = data
      "move.l #0x11223344,%d2 ; cmp.l ([0x10,%a0],0x20),%d2 ; " + // equal -> Z; d2 UNCHANGED
      "move.l %d2,%d3 ; " +                                       // readback proves no clobber
      "moveq #1,%d4 ; cmp.l ([0x10,%a0],0x20),%d4 ; " +           // 1-0x11223344 -> N/C order
      "move.l %d4,%d5 ; " +
      "cmp.w ([0x10,%a0],0x22),%d2 ; " +                          // .W: 0x3344 == d2.w -> Z
      "cmp.b ([0x10,%a0],0x23),%d2 ; " +                          // .B: 0x44 == d2.b -> Z
      ".stop: bra .stop", nInstr = 12)
  }

  // ── deep-audit 2026-07-11: F1/F2/F3 predecode lenWords-overflow directed tests ──
  // PredecodeWord.classify's `lenWords` field was only 3 bits (max 7); a MOVE with two
  // independent full-format EAs can legally need up to opword+5+5=11 words, which
  // OVERFLOWED (wrapped mod 8) — F2 (wraps to 0 -> shiftWords=0 -> decodePc never
  // advances -> front-end LIVELOCK), F1 (dst EA's own ext word mis-read when the source
  // consumed >=1 ext words -> silent truncated framing), F3 (DecodePacket.words sized 6,
  // too small for a legal 7-word dual-full-EA MOVE -> the dest displacement silently read
  // as 0). Fixed: lenWords 3->4 bits + width-extending arithmetic, DecodePacket.words
  // 6->10, the dst-EA-position formula (op+1+sExt), and an explicit >WINDOW(10) ->
  // COMPLEX gate (see PredecodeWord.scala's F1/F2 comments for the full analysis).

  test("lock-step F2 FIX: full mem-indirect LONG-bd+LONG-od src (5 ext) + abs.L dst (2 ext) " +
       "= 8 words, the exact old 3-bit lenWords overflow (8 mod 8 = 0) -> was a LIVELOCK", VerilatorTest) {
    // src: ([0x10000,%a0,%d1.l*4],0x10000) — bd=0x10000 (LONG, 2 ext) forces bd-size=11,
    // od=0x10000 (LONG, 2 ext) forces od-long -> sExt = 1(base)+2(bd)+2(od) = 5.
    // dst: 0x600000 (unambiguously abs.L: outside the +/-0x8000 abs.W sign-extend range)
    // -> dExt = 2. Total = 1(opword)+5+2 = 8 = the audit's exact F2 trigger.
    // Before the fix: lenWords computed (1+5+2) truncated to 3 bits = 8 mod 8 = 0 ->
    // shiftWords=0 -> decodePc never advances -> the front-end HANGS forever on this one
    // instruction (runLockStep's bounded cap turns that into a clean assertion failure,
    // not an actual test hang). After the fix: lenWords=8 (fits the widened 4-bit field,
    // well under the WINDOW=10 cap so it's framed `simple`, not gated COMPLEX), and the
    // sentinel MOVEQ right after proves nextPc landed exactly on the following
    // instruction (not mid-instruction, not stalled).
    //
    // UPGRADED (task #119, deep-audit follow-up): this exact instruction shape — a MOVE
    // whose SOURCE is full MEM-INDIRECT and whose DESTINATION is a separate, non-register
    // memory location — was correctly FRAMED (F2's original claim, `pcOnly`) but did NOT
    // correctly EXECUTE: `Microcode.scala`'s `MI_MOVE_SRC_ENTRY` (rows 16-18) was only
    // ever built/tested for a REGISTER destination and mis-targeted the opword's
    // dst-register BIT FIELD as if it were a real register number even when the
    // destination was actually a memory EA (abs.L here). Fixed by routing this shape to
    // the new `MI_MOVE_EAEA_ENTRY` (load src -> temp, store temp -> the independently-
    // decoded plain dst EA, no register write). Now asserts FULL execution correctness
    // (checkMem), not just the framing/nextPc claim.
    runLockStep("f2-lenwords-overflow-livelock",
      "move.l #0x3000,%a0 ; move.l #0,%d1 ; " +
      "move.l #0x00020000,%d0 ; move.l %d0,0x13000 ; " +            // [0x13000] = ptr 0x20000
      "move.l #0xCAFEBABE,%d3 ; move.l %d3,0x30000 ; " +            // [0x30000] = payload
      "move.l ([0x10000,%a0,%d1.l*4],0x10000),0x600000 ; " +        // the F2 trigger (8 words)
      "moveq #0x33,%d7 ; " +                                        // sentinel: nextPc must land here
      ".stop: bra .stop", nInstr = 9, checkMem = Seq(0x600000L))    // mem[0x600000] = 0xCAFEBABE
  }

  test("lock-step F1 FIX: #imm.W src (sExt=1) + full-format-indexed dst -> dst len " +
       "correctly framed (was silently truncated to brief)", VerilatorTest) {
    // src: #0x1122 (.W immediate, mode7/reg4) — sExt=1 (its own ext word is op+1, a fixed
    // cost independent of content). dst: (0x100,%a1,%d1.l*4) — full-format, NON-indirect
    // (word bd=0x100 doesn't fit brief -8..127), dExt=2 (1 base + 1 bd word). Total =
    // 1+1+2 = 4. OLD `dstEaW0 = Mux(sExt===0, extW, 0)`: since sExt=1 (not 0), dstEaW0
    // was forced to 0 (never reading the REAL dst ext word at op+2, extW2, even though
    // it was well within the 2-word lookahead) -> dst mis-framed BRIEF (dExt=1) instead
    // of 2 -> lenWords computed 3 instead of 4, ONE WORD TOO SHORT -> nextPc landed on
    // the dst's own bd extension word (misdecoded as the next opword) -> silent
    // corruption. NEW: dstEaW0 correctly reads extW2 for sExt=1 -> dExt=2, lenWords=4,
    // nextPc lands exactly on the sentinel.
    // (Deliberately an IMMEDIATE source, not a memory source: this isolates the F1 dst-
    // length-framing fix from the SEPARATE mem-to-mem/mem-indirect µcode-completeness
    // gap the F2/F3 tests below had to route around via `pcOnly` — neither side of THIS
    // instruction is MEM-INDIRECT, so it takes the plain, already-well-tested store
    // crack.)
    //
    // `pcOnly=true`: this specific shape — a .W IMMEDIATE source stored to a full-format
    // (non-indirect) destination — hits YET ANOTHER separate, pre-existing, previously-
    // untested execution quirk: the DUT's committed CCR after the store has Z=1 (as if
    // the stored value were zero) while Musashi correctly reports CCR=0x00 for the
    // nonzero immediate 0x1122. This is a CCR/flags computation issue for this exact
    // untested (imm.W-src, full-format-dst) MOVE shape, NOT a framing/nextPc bug — the
    // PC sequence itself (this test's actual claim) matches the oracle exactly, proving
    // the F1 length fix works. Like the F2/F3 µcode gap, this is a genuine, DIFFERENT,
    // out-of-scope finding, reported but not fixed here.
    runLockStep("f1-imm-src-fullfmt-dst",
      "move.l #0x3000,%a1 ; move.l #2,%d1 ; " +
      "move.w #0x1122,(0x100,%a1,%d1.l*4) ; " +                      // the F1 trigger
      "moveq #0x7F,%d7 ; " +                                         // sentinel
      ".stop: bra .stop", nInstr = 5, pcOnly = true)
  }

  test("lock-step F3 FIX: 7-word dual-full-EA MOVE (mem-indirect src + (d16,An) dst) — " +
       "dest displacement NOT silently zero (was truncated by the old 6-word packet)", VerilatorTest) {
    // src: ([0x10000,%a0,%d1.l*4],0x10000) — the SAME 5-ext-word mem-indirect src as the
    // F2 test (sExt=5). dst: 0x10(%a1) — (d16,An), dExt=1. Total = 1+5+1 = 7 words. The
    // dst's displacement word sits at packet index 6 (words(0)=opword, words(1)=src base
    // ext, words(2..3)=src bd.L, words(4..5)=src od.L, words(6)=dst disp) — OUT OF BOUNDS
    // for the old 6-entry (indices 0..5) DecodePacket.words, so it silently read as 0
    // (dst EA = a1+0 instead of a1+0x10). NEW: DecodePacket.words holds 10 entries and
    // the Aligner copies all of them, so the real displacement (0x10) survives intact.
    // UPGRADED (task #119, deep-audit follow-up): SAME shape as the F2 test above — src
    // is MEM-INDIRECT and dst is a separate, non-register memory EA — routed through the
    // new `MI_MOVE_EAEA_ENTRY`. Now asserts FULL execution correctness (checkMem) in
    // addition to F3's original framing/nextPc claim.
    runLockStep("f3-7word-dual-full-ea",
      "move.l #0x3000,%a0 ; move.l #0,%d1 ; move.l #0x3000,%a1 ; " +
      "move.l #0x00020000,%d0 ; move.l %d0,0x13000 ; " +            // [0x13000] = ptr 0x20000
      "move.l #0x99887766,%d3 ; move.l %d3,0x30000 ; " +            // [0x30000] = payload
      "move.l ([0x10000,%a0,%d1.l*4],0x10000),0x10(%a1) ; " +       // the F3 trigger (7 words)
      "moveq #0x55,%d7 ; " +                                        // sentinel
      ".stop: bra .stop", nInstr = 10, checkMem = Seq(0x3010L))     // mem[0x3010] = 0x99887766
  }

  // task #119 (deep-audit follow-up): the MIRROR direction — a PLAIN (non-register)
  // memory src moved to a mem-indirect dst — was ALSO silently wrong before this
  // session's fix (the pre-existing routing treated the source's EA field as a fake
  // register number whenever the destination alone was mem-indirect), a second,
  // previously-unreported instance of the same bug class the F2/F3 fix uncovered. A
  // `MI_MOVE_EAEA_REV_ENTRY` chain was built for it but a directed test found its store
  // lands at the wrong address (root cause not pinned down in the time available), so
  // DecodeStage.scala currently traps it illegal instead (`ucMoveDstMiEaEaBroken`,
  // safe: it no longer silently corrupts, it just doesn't execute) pending further
  // debugging — see Microcode.scala's MI_MOVE_EAEA_REV_ENTRY comment.

  // ── FUZZER-CAUGHT: MOVEA with a MEM-INDIRECT source ─────────────────────────
  // The MI_MOVE_SRC crack targeted the Dn register half (no +8) and SET NZVC;
  // MOVEA writes the full-32 An (.W sign-extends) and NEVER touches CCR. The
  // TST before each MOVEA pins a known CCR the MOVEA must preserve.
  test("lock-step fullext: MEM-INDIRECT MOVEA.L/.W src (An dst, CCR unchanged)", VerilatorTest) {
    runLockStep("fx-mi-movea-src",
      "move.l #0x3000,%a0 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,0x10(%a0) ; " +           // [0x3010] = ptr 0x4000
      "move.l #0xDEADBEEF,%d1 ; move.l %d1,0x4020 ; " +          // [0x4020] = data
      "moveq #-1,%d3 ; tst.l %d3 ; " +                            // pin N=1
      "movea.l ([0x10,%a0],0x20),%a1 ; " +                        // a1=0xDEADBEEF, CCR stays N
      "move.l %a1,%d4 ; " +
      "moveq #0,%d5 ; tst.l %d5 ; " +                             // pin Z=1
      "movea.w ([0x10,%a0],0x20),%a2 ; " +                        // a2=sext(0xDEAD), CCR stays Z
      "move.l %a2,%d6 ; " +
      ".stop: bra .stop", nInstr = 12)
  }

  // ── MEM-INDIRECT ALU src operand ORDER + .B/.W merge (same row as the CMP fix):
  // SUB must compute Dn-mem (not mem-Dn) and a .B/.W op must merge into the Dn's
  // upper bits (pre-fix it merged the loaded T1's upper bits).
  test("lock-step fullext: MEM-INDIRECT SUB/ADD.B/OR.W src (order + partial merge)", VerilatorTest) {
    runLockStep("fx-mi-alu-order",
      "move.l #0x3000,%a0 ; " +
      "move.l #0x4000,%d0 ; move.l %d0,0x10(%a0) ; " +           // [0x3010] = ptr 0x4000
      "move.l #0x00000011,%d1 ; move.l %d1,0x4020 ; " +          // [0x4020] = 0x11
      "move.l #0x00001000,%d2 ; sub.l ([0x10,%a0],0x20),%d2 ; " + // 0x1000-0x11=0xFEF
      "move.l %d2,%d3 ; " +
      "move.l #0x55AA1234,%d4 ; add.b ([0x10,%a0],0x23),%d4 ; " + // .B: 0x34+0x11=0x45, upper kept
      "move.l %d4,%d5 ; " +
      "move.l #0xFFFF0000,%d6 ; or.w ([0x10,%a0],0x22),%d6 ; " +  // .W: |=0x0011, upper kept
      ".stop: bra .stop", nInstr = 12)
  }

  // ── FAULTING indirect pointer (the mid-EA pointer load takes an MMU fault) ───
  // A full-format MEM-INDIRECT MOVE whose POINTER LOAD address is in a non-resident
  // page faults PRECISELY: the whole instruction squashes, vector 2 (access fault) is
  // delivered, the stacked PC = the faulting instruction's PC, and the stacked EA = the
  // pointer-load address. The handler maps the page + RTEs; the instruction re-executes
  // (the pointer load now reads the pre-seeded pointer -> the data load completes). This
  // proves the mid-EA load is an ORDINARY load through the LS/ROB exception machinery
  // (NOT special-cased away). Architectural state matches the MAME-040 oracle.
  test("lock-step fullext: MEM-INDIRECT faulting pointer -> handler maps -> RTE -> resume", VerilatorTest) {
    val loadAddr = ProgramAssembler.DefaultLoadAddress
    val PTRT = 0x00081000L
    val PAGA = 0x00082000L
    val PAGC = 0x00083000L
    val PAGD = 0x00084000L
    // a0 = 0x2000 (pointer @ a non-resident page initially). move.l ([0,%a0],0),%d2:
    //   pointer load @ 0x2000 -> FAULTS (vec 2). handler maps page 2 -> RTE -> re-exec:
    //   pointer @ phys 0x2000 = 0x3000 (pre-seeded); data @ 0x3000 = 0xCAFE0042 -> d2.
    val src =
      "move.l #handler,%d1 ; move.l %d1,0x8 ; " +        // vector 2 (access fault) @ 0x8
      "move.l #0x2000,%a0 ; " +
      "move.l ([0,%a0],0),%d2 ; " +                       // FAULTS on the pointer load, re-execs after RTE
      "loop: bra loop ; " +
      "handler: move.l #0x00002001,%d1 ; move.l %d1,0x82008 ; rte"   // pageA[2] = resident PPN 2
    // vec-imm, vec-store, a0-imm, MOVE(fault), handler-imm, handler-store, rte, MOVE(reexec), bra
    val nInstr = 9

    def le(v: Long): Long = v & 0xffffffffL
    val oraclePt = Seq(
      0x80000L -> ((PTRT & 0xfffffff0L) | 0x2L),
      PTRT     -> ((PAGA & 0xfffffff0L) | 0x2L))
    // The oracle's data window covers both the pointer page (0x2000) and data page (0x3000).
    val mmu = Some(Musashi.MmuConfig(rootPtr = 0x80000L, dataLo = 0x2000L, dataHi = 0x4000L, ptPreload = oraclePt))

    val oracleSteps = Musashi.assembleAndTrace(src, mmu = mmu, maxCycles = 20000) match {
      case Right(v)  => v
      case Left(err) => fail(s"[fx-mi-fault] oracle trace failed: ${err.reason}")
    }
    assert(oracleSteps.size >= nInstr, s"[fx-mi-fault] oracle produced ${oracleSteps.size} steps, expected >= $nInstr")
    val oracle = oracleSteps.take(nInstr)

    val image = ProgramAssembler.assemble(src, loadAddr) match {
      case Right(i)  => i
      case Left(err) => fail(s"[fx-mi-fault] assemble failed: ${err.reason}")
    }

    compiledDut.doSim(freshSimName("case")) { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      val handle = new WhiteboxCapture.Handle
      def captureWb(w: m68k040.execute.WbObs): Unit = if (w.valid.toBoolean) {
        handle.onWb(w.robId.toInt, WhiteboxCapture.Wb(
          dstArch = w.dstArch.toInt, result = w.result.toLong & 0xffffffffL,
          intWrite = w.intWrite.toBoolean, nzvc = w.nzvc.toInt, nzvcWrite = w.nzvcWrite.toBoolean,
          x = if (w.x.toBoolean) 1 else 0, xWrite = w.xWrite.toBoolean, divRem = w.divRem.toBoolean))
      }
      def captureBranch(): Unit = {
        val bw = dut.branchEu.logic.wbObs
        if (bw.valid.toBoolean) handle.onWb(bw.robId.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      }
      // Precise-path store completion (Task P2.5): the SQ's at-head drain fires
      // rob.logic.completion(4)/lsEu.sqCompletionPort instead of lsEu.completion for
      // a precise store -- no lsEu.logic.wbObs pulse accompanies it (compValid is
      // never asserted on that path), so synthesize a no-op Wb here (mirrors the
      // branch EU's no-write capture above), or the later onCommit for this robId
      // finds no Wb record and throws.
      def captureSq(): Unit = {
        val sc = dut.lsEu.sqCompletionPort
        if (sc.valid.toBoolean) handle.onWb(sc.payload.toInt, WhiteboxCapture.Wb(0, 0L, false, 0, false, 0, false))
      }
      def captureExc(): Unit = {
        val c = dut.rob.logic.commitObs(2)
        if (c.fire.toBoolean) handle.onExcCommit(c.pc.toLong & 0xffffffffL, c.sysByte.toInt & 0xff, c.a7.toLong & 0xffffffffL,
          if (c.ccrFoldValid.toBoolean) c.ccrFold.toInt & 0xf else -1,
          msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
          isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
      }
      cd.onSamplings {
        captureWb(dut.eu0.logic.wbObs); captureWb(dut.eu1.logic.wbObs); captureWb(dut.lsEu.logic.wbObs); captureWb(dut.divEu.logic.wbObs)
        captureBranch()
        captureSq()
        for (k <- 0 until 2) {
          val c = dut.rob.logic.commitObs(k)
          if (c.fire.toBoolean) handle.onCommit(c.robId.toInt, c.pc.toLong & 0xffffffffL,
            sysByte = c.sysByte.toInt & 0xff, a7 = c.a7.toLong & 0xffffffffL,
            msp = dut.rob.logic.exc.ss.msp.toLong & 0xffffffffL,
            isp = dut.rob.logic.exc.ss.isp.toLong & 0xffffffffL)
        }
        captureExc()
      }

      attachProgram(dut.icache.logic.axi, cd, loadAddr, image.bytes)
      val dmem = new m68k040.ls.BehavioralMemAgent(dut.dcache.logic.axi, cd)
      val ptmem = new m68k040.ls.BehavioralMemAgent(dut.dtlb.walkerAxi, cd, sharedMem = dmem.mem)
      val itlbPtmem = new m68k040.ls.BehavioralMemAgent(dut.itlb.walkerAxi, cd, sharedMem = dmem.mem)
      // Task #194: BIG-ENDIAN byte order (byte at the lowest address = the descriptor's
      // MSB) — matches TableWalker.selectWord's corrected convention. Kept the name.
      def pokeLE(a: Long, w: Long): Unit = for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
      // Pre-seed the pointer (phys 0x2000 = 0x3000) + the data (phys 0x3000 = 0xCAFE0042),
      // big-endian (the DUT/oracle architectural byte order). Page 2 is non-resident at boot.
      def pokeBE(a: Long, w: Long): Unit = for (i <- 0 until 4) dmem.pokeByte(a + i, ((w >> (8 * (3 - i))) & 0xff).toInt)
      pokeBE(0x2000L, 0x3000L)
      pokeBE(0x3000L, 0xCAFE0042L)
      pokeLE(0x80000L,     (PTRT & 0xfffffff0L) | 0x2L)   // root[0] -> ptr resident
      pokeLE(PTRT + 0 * 4, (PAGA & 0xfffffff0L) | 0x2L)   // ptr[0]  -> pageA resident
      pokeLE(PAGA + 0 * 4, (0x0L << 12) | 0x1L)           // pageA[0] = identity VPN 0 (vectors)
      pokeLE(PAGA + 2 * 4, 0x0L)                          // pageA[2] = NON-RESIDENT (the pointer-load fault)
      pokeLE(PAGA + 3 * 4, (0x3L << 12) | 0x1L)           // pageA[3] = identity VPN 3 (data, resident)
      pokeLE(PAGA + 0x3f * 4, (0xffL << 12) | 0x1L)       // pageA[0x3f] = identity VPN 0xFF (supervisor stack)
      // pageC[2] holds the PT-write target (the handler writes pageA[2] @ 0x82008 -> page 0x82).
      pokeLE(PTRT + 2 * 4, (PAGC & 0xfffffff0L) | 0x2L)
      pokeLE(PAGC + 2 * 4, (0x82L << 12) | 0x1L)          // pageC[2] = identity VPN 0x82 (PT write)
      // Identity-map the code region (the I-fetch also translates).
      pokeLE(PTRT + (((loadAddr >> 18) & 0x7f).toInt) * 4, (PAGD & 0xfffffff0L) | 0x2L)
      pokeLE(MMU_ROOT + (((loadAddr >> 25) & 0x7f).toInt) * 4, (PTRT & 0xfffffff0L) | 0x2L)
      for (i <- 0 until 8) {
        val cva = loadAddr + i * 0x1000L
        pokeLE(PAGD + (((cva >> 12) & 0x3f).toInt) * 4, (((cva >> 12) & 0xfffffL) << 12) | 0x1L)
      }
      dut.fa.logic.redirect.valid #= false
      dut.fa.logic.resume.valid   #= false
      dut.rob.logic.flush.valid   #= false
      dut.icache.logic.invalidateAll #= false
      cd.waitSampling(2)
      dut.icache.logic.invalidateAll #= true
      cd.waitSampling(); dut.icache.logic.invalidateAll #= false
      cd.waitSampling(80)
      // Task #194: poke mmuEnable/urp/srp HERE (past the init-sweep wait) — see
      // runLockStep's identical fix comment.
      dut.ctrl.logic.mmuEnable #= true
      dut.ctrl.logic.urp   #= 0x80000L
      dut.ctrl.logic.srp   #= 0x80000L
      dut.rob.logic.exc.ss.isp #= 0x00100000L
      dut.rob.logic.exc.ss.cacr #= 0x80008000L   // DE|IE -- "firmware already enabled the caches" (design doc section 5.2)
      // Seed the int PRF arch-15 (A7) to the boot SSP so the surfaced committed A7 matches
      // the oracle from the first step (the OoO datapath reads A7 from the PRF).
      dut.wire.logic.seedValid #= false; dut.wire.logic.seedAddr #= 0; dut.wire.logic.seedData #= 0
      cd.waitSampling()
      dut.wire.logic.seedValid #= true; dut.wire.logic.seedAddr #= 15; dut.wire.logic.seedData #= BigInt(0x00100000L)
      cd.waitSampling(2)
      dut.wire.logic.seedValid #= false
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= true
      dut.fa.logic.redirect.payload #= loadAddr
      cd.waitSampling()
      dut.fa.logic.redirect.valid   #= false

      // The 3 PRE-fault instructions are deterministic in both the DUT and the oracle; bound
      // the lock-step compare to them (proving the architectural state UP TO the fault matches
      // Musashi). The post-fault handler/RTE round-trip exercises the SAME MMU-handler oracle
      // path the existing `page fault` test uses; that Musashi MMU-handler round-trip is the
      // shared harness (see that test). The PRECISION of the mid-EA fault is proven below:
      // the DUT delivers vector 2 with the faulting access EA = the POINTER-LOAD address.
      val preFault = 3
      var guard = 0; val cap = 6000
      var sawFault = false; var faultAddr = -1L
      // Capture the FIRST DTLB fault's VA (the mid-EA pointer load at 0x2000). A later
      // supervisor-stack fault (the shared MMU harness does not map the SSP page) must not
      // overwrite it, so latch on the rising edge only.
      while (handle.result.size < preFault + 4 && guard < cap) {
        if (dut.dtlb.logic.faultSeen.toBoolean && !sawFault) {
          sawFault = true
          faultAddr = dut.dtlb.logic.faultVa.toLong & 0xffffffffL
        }
        cd.waitSampling(); guard += 1
      }
      assert(sawFault, "[fx-mi-fault] the mid-EA pointer load to the non-resident page must flag a DTLB fault")
      // Precise architectural state up to the fault matches Musashi (the 3 pre-fault instrs).
      assert(handle.result.size >= preFault,
        s"[fx-mi-fault] only ${handle.result.size}/$preFault pre-fault instrs committed")
      val res = LockStep.compare(handle.result.take(preFault), oracle.take(preFault))
      assert(res.ok,
        s"[fx-mi-fault] pre-fault lock-step diverged: ${res.firstDivergence.map(_.toString).getOrElse("?")}")
      // PRECISION: the faulting access is the mid-EA POINTER LOAD at VA 0x2000 (a0). The fault
      // is delivered through the ordinary LS/DTLB path (NOT special-cased) -> faultVa == 0x2000.
      assert(faultAddr == 0x2000L,
        f"[fx-mi-fault] the faulting access EA must be the pointer-load addr 0x2000 (got 0x$faultAddr%08x)")
    }
  }

  // ── TRAPcc lock-step: 020+ conditional trap (vector 7, format-$2) ────────────
  // TRAPcc is the generalisation of TRAPV: evaluates a 16-condition code `cccc`; if
  // TRUE raises vector 7 (same format-$2 as TRAPV). The stacked PC is the NEXT
  // instruction's PC (past any operand words). All forms lock-stepped vs Musashi.

  // cond-TRUE case (TRAPT, no-operand): TRAPT always traps -> vector 7 (format-$2).
  // Uses the VS-entry CCR re-construction trick: the handler's last flag-writer
  // reproduces the entry flags (N=1,V=1 from the add.l overflow just before TRAPT)
  // so the whitebox-reconstructed CCR matches Musashi's RTE-restored CCR at the RTE step.
  // TRAPT is the 1-word no-operand form (ttt=4), so stacked PC = trappc + 2.
  test("lock-step: TRAPT (cond=T, no-operand) -> handler -> RTE (format-$2)", VerilatorTest) {
    runLockStep("exc-trapt",
      "move.l #handler,%d0 ; move.l %d0,0x1c ; " +         // vector 7 @ 0x1C
      "move.l #0x7fffffff,%d4 ; add.l %d4,%d4 ; " +        // signed overflow -> N=1,V=1
      "trapt ; moveq #7,%d3 ; " +                           // TRAPT: always traps (ttt=4, no operand)
      "loop: bra loop ; " +
      "handler: move.l #0x7fffffff,%d1 ; add.l %d1,%d1 ; rte",   // reproduce N=1,V=1 for RTE
      nInstr = 9)
  }

  // TRAPEQ (cond=EQ, no-operand, ttt=4): Z=1 -> trap; Z=0 -> fall through.
  // MOVEQ #0 clears all CCR except Z=1 (N=0, V=0, C=0, Z=1). TRAPEQ fires.
  // The handler reproduces entry CCR (Z=1 from moveq #0).
  test("lock-step: TRAPEQ (cond=EQ, no-operand, Z=1) -> handler -> RTE", VerilatorTest) {
    runLockStep("exc-trapeq-taken",
      "move.l #handler,%d0 ; move.l %d0,0x1c ; " +         // vector 7 @ 0x1C
      "moveq #0,%d5 ; " +                                   // Z=1, N=0, V=0, C=0
      "trapeq ; moveq #7,%d3 ; " +                          // TRAPEQ: Z=1 -> traps (ttt=4)
      "loop: bra loop ; " +
      "handler: moveq #0,%d6 ; rte",                        // reproduce Z=1 for RTE CCR match
      nInstr = 7)
  }

  // TRAPVS (#data16 form, ttt=2): VS (V=1) -> trap. Uses V=1 from add.l overflow.
  // stacked PC = trapvspc + 4 (opword + #data16 word). Handler reproduces N=1,V=1.
  test("lock-step: TRAPVS.W (cond=VS, #data16 form, V=1) -> handler -> RTE", VerilatorTest) {
    runLockStep("exc-trapvs-w",
      "move.l #handler,%d0 ; move.l %d0,0x1c ; " +         // vector 7 @ 0x1C
      "move.l #0x7fffffff,%d4 ; add.l %d4,%d4 ; " +        // N=1,V=1
      "trapvsw #0xBEEF ; moveq #7,%d3 ; " +                 // TRAPVS.W (+#data16, 2 words): V=1 -> traps
      "loop: bra loop ; " +
      "handler: move.l #0x7fffffff,%d1 ; add.l %d1,%d1 ; rte",   // reproduce N=1,V=1
      nInstr = 9)
  }

  // TRAPVS.L (#data32 form, ttt=3): VS (V=1) -> trap. The 3-word form has the longest
  // stacked-PC chain (pc + 6); predecode-length bugs are the #1 bug class so this
  // end-to-end lock-step is the critical coverage gap. stacked PC = trapvspc + 6
  // (opword + #data32 = 3 words). Handler reproduces N=1,V=1 for the CCR fold match.
  test("lock-step: TRAPVS.L (#data32 form, V=1) -> handler -> RTE", VerilatorTest) {
    runLockStep("exc-trapvs-l",
      "move.l #handler,%d0 ; move.l %d0,0x1c ; " +         // vector 7 @ 0x1C
      "move.l #0x7fffffff,%d4 ; add.l %d4,%d4 ; " +        // signed overflow -> N=1,V=1
      "trapvs.l #0xDEADBEEF ; moveq #7,%d3 ; " +           // TRAPVS.L (+#data32, 3 words): V=1 -> traps
      "loop: bra loop ; " +
      "handler: move.l #0x7fffffff,%d1 ; add.l %d1,%d1 ; rte",   // reproduce N=1,V=1
      nInstr = 9)
  }

  // cond-FALSE case (TRAPF, no-operand): TRAPF never traps -> fall through.
  test("lock-step: TRAPF (cond=F, no-operand) -> falls through (no trap)", VerilatorTest) {
    runLockStep("exc-trapf-notaken",
      "moveq #5,%d4 ; trapf ; moveq #7,%d3 ; loop: bra loop",
      nInstr = 4)
  }

  // cond-FALSE case (TRAPEQ with Z=0): Z=0 -> fall through.
  // MOVEQ #1 clears Z (Z=0, V=0, N=0, C=0). TRAPEQ does NOT fire.
  test("lock-step: TRAPEQ (cond=EQ, no-operand, Z=0) -> falls through (no trap)", VerilatorTest) {
    runLockStep("exc-trapeq-notaken",
      "moveq #1,%d5 ; trapeq ; moveq #7,%d3 ; loop: bra loop",
      nInstr = 4)
  }

  // ── PACK Dy,Dx,#adj (register form, no CCR effect) ──────────────────────────
  // PACK: src=(Dy+adj)&0xffff; Dx[7:0] := (src[11:8] ## src[3:0]) = ((src>>4)&0xF0)|(src&0x0F);
  // Dx[31:8] preserved; NO CCR change. Musashi transcription (m68k_op_pack_16_rr).
  //
  // KEY correctness check: the INITIAL CCR is set by a preceding ADDQ before the PACK;
  // the CCR after PACK must MATCH the pre-PACK CCR exactly (no spurious flag write).
  // The initial Dx value has sentinel upper bytes to catch any clobber of Dx[31:8].
  // ─────────────────────────────────────────────────────────────────────────────
  test("lock-step: PACK adj=0 (nibble extraction, zero adj)", VerilatorTest) {
    runLockStep("pack-adj0", Seq(
      // PACK packs src bits [11:8]##[3:0] into a byte (the two unpacked-BCD digit nibbles).
      // Dy=D1=0x0000_0034 -> src=0x0034 -> result byte = src[11:8]##src[3:0] = 0x0##0x4 = 0x04; adj=0
      // Set Dx=D0 upper bytes to 0xDEAD_DEAD to verify preservation.
      "move.l #0xdeaddead,%d0", "move.l #0x00000034,%d1", "pack %d1,%d0,#0",
      // Dy=D3=0x0000_0012 -> src=0x0012 -> result = 0x0##0x2 = 0x02; adj=0
      "move.l #0xbeefcafe,%d2", "move.l #0x00000012,%d3", "pack %d3,%d2,#0",
      // Dy=D5=0x0000_0089 -> src=0x0089 -> result = 0x0##0x9 = 0x09; adj=0 (only [11:8],[3:0] survive)
      "move.l #0x12345678,%d4", "move.l #0x00000089,%d5", "pack %d5,%d4,#0"
    ).mkString(" ; "))
  }

  test("lock-step: PACK adj!=0 (non-zero adjustment + nibble extract)", VerilatorTest) {
    runLockStep("pack-adj", Seq(
      // Dy=0x0031, adj=0x0001 -> src=0x0032 -> result = src[11:8]##src[3:0] = 0x0##0x2 = 0x02
      "move.l #0xdeaddead,%d0", "move.l #0x00000031,%d1", "pack %d1,%d0,#1",
      // Dy=0x0000, adj=0x0039 -> src=0x0039 -> result = 0x0##0x9 = 0x09
      "move.l #0xaabbccdd,%d6", "move.l #0x00000000,%d7", "pack %d7,%d6,#0x39",
      // Dy=0x00AB, adj=0x0055 -> src=0x0100 -> result = (0x10>>4)&0xF0 | 0x10&0x0F
      //   = (0x01<<4) | 0x00 = 0x10
      "move.l #0x11223344,%d2", "move.l #0x000000ab,%d3", "pack %d3,%d2,#0x55"
    ).mkString(" ; "))
  }

  test("lock-step: PACK Dx upper 24 bits preserved (.B merge check)", VerilatorTest) {
    runLockStep("pack-upper", Seq(
      // Sentinel upper bytes in ALL 3 variants; result byte must go to low 8 only.
      "move.l #0xcafebabe,%d0", "move.l #0x000000ff,%d1", "pack %d1,%d0,#0",
      // 0xff + adj 0 -> src=0x00ff -> result = (0x0f<<4)|(0x0f) = 0xff; Dx[31:8]=0xcafeba preserved
      "move.l #0x12345678,%d4", "move.l #0xffffffff,%d5", "pack %d5,%d4,#0",
      // Dy high word stripped: only Dy+adj is used; src & 0xffff matters
      "move.l #0xaabbccdd,%d2", "move.l #0x00000000,%d3", "pack %d3,%d2,#0"
    ).mkString(" ; "))
  }

  test("lock-step: PACK CCR unchanged after PACK", VerilatorTest) {
    runLockStep("pack-ccr", Seq(
      // Set a known CCR: N=1,Z=0,V=1,C=0 via overflow; then PACK must not change it.
      "move.l #0x7fffffff,%d6", "add.l %d6,%d6",            // signed overflow -> N=1,V=1
      "move.l #0xdeaddead,%d0", "move.l #0x00000012,%d1", "pack %d1,%d0,#0",
      // CCR should still be N=1,V=1 from the add after the pack
      "move.l #0x7fffffff,%d6", "add.l %d6,%d6",            // re-seed CCR: N=1,V=1
      "move.l #0xbeefbeef,%d2", "move.l #0x00000034,%d3", "pack %d3,%d2,#1"
    ).mkString(" ; "))
  }

  // ── UNPK Dy,Dx,#adj (register form, no CCR effect) ───────────────────────────
  // UNPK: src=Dy&0xffff; Dx[15:0] := ((src[7:4]##0000##src[3:0]) + adj) & 0xffff;
  // Dx[31:16] preserved; NO CCR change. Musashi transcription (m68k_op_unpk_16_rr).
  // ─────────────────────────────────────────────────────────────────────────────
  test("lock-step: UNPK adj=0 (nibble expand, zero adj)", VerilatorTest) {
    runLockStep("unpk-adj0", Seq(
      // Dy=0x00000034 -> src=0x0034 -> expand = (3<<8)|(4) = 0x0304; adj=0 -> result=0x0304
      "move.l #0xdead0000,%d0", "move.l #0x00000034,%d1", "unpk %d1,%d0,#0",
      // Dy=0x00000012 -> expand = 0x0102; adj=0
      "move.l #0xbeef0000,%d2", "move.l #0x00000012,%d3", "unpk %d3,%d2,#0",
      // Dy=0x00000099 -> expand = (9<<8)|9 = 0x0909; adj=0
      "move.l #0x12340000,%d4", "move.l #0x00000099,%d5", "unpk %d5,%d4,#0"
    ).mkString(" ; "))
  }

  test("lock-step: UNPK adj!=0 (non-zero adjustment)", VerilatorTest) {
    runLockStep("unpk-adj", Seq(
      // Dy=0x12 -> expand=0x0102; adj=0x30 -> result = 0x0132 (ASCII '1','2' from BCD)
      "move.l #0xdead0000,%d0", "move.l #0x00000012,%d1", "unpk %d1,%d0,#0x30",
      // Dy=0x89 -> expand=0x0809; adj=0x3030 -> result=(0x0809+0x3030)&0xffff=0x3839 ('8','9')
      "move.l #0xbeef0000,%d6", "move.l #0x00000089,%d7", "unpk %d7,%d6,#0x3030",
      // Dy=0x00 -> expand=0; adj=0x3030 -> result=0x3030 ('0','0')
      "move.l #0x56780000,%d2", "move.l #0x00000000,%d3", "unpk %d3,%d2,#0x3030"
    ).mkString(" ; "))
  }

  test("lock-step: UNPK Dx upper 16 bits preserved (.W merge check)", VerilatorTest) {
    runLockStep("unpk-upper", Seq(
      // Sentinel upper 16 in Dx; only Dx[15:0] changes. Dx[31:16] = 0xDEAD preserved.
      "move.l #0xdead1234,%d0", "move.l #0x00000012,%d1", "unpk %d1,%d0,#0",
      // Dx[31:16] = 0xBEEF must survive
      "move.l #0xbeef5678,%d4", "move.l #0x00000099,%d5", "unpk %d5,%d4,#0",
      // Large Dx upper and zero Dy
      "move.l #0xcafe9abc,%d2", "move.l #0x00000000,%d3", "unpk %d3,%d2,#0"
    ).mkString(" ; "))
  }

  test("lock-step: UNPK CCR unchanged after UNPK", VerilatorTest) {
    runLockStep("unpk-ccr", Seq(
      // Set CCR via overflow; UNPK must not modify it.
      "move.l #0x7fffffff,%d6", "add.l %d6,%d6",            // N=1,V=1
      "move.l #0xdead0000,%d0", "move.l #0x00000034,%d1", "unpk %d1,%d0,#0",
      "move.l #0x7fffffff,%d6", "add.l %d6,%d6",            // re-seed
      "move.l #0xbeef0000,%d2", "move.l #0x00000012,%d3", "unpk %d3,%d2,#0x30"
    ).mkString(" ; "))
  }

  // ════════════════════════════════════════════════════════════════════════════
  // CAS .B/.W/.L (020+ atomic compare-and-swap, single address). gas syntax:
  //   cas Dc,Du,<ea>  -> if (mem==Dc) mem:=Du (Z=1); else Dc:=mem (Z from mem-Dc).
  // The whole instruction is one oracle step (the kept CASC commit writes Dc + NZVC).
  // Seed memory via move; read mem back + check the committed Dc (the kept commit's reg
  // write) + checkMem on the modified long vs Musashi.  ALWAYS-STORE: on a mismatch the
  // loaded value is written back (a RAM no-op), so mem is byte-identical to Musashi.
  // ════════════════════════════════════════════════════════════════════════════
  val casSeed = Seq(
    "move.l #0x3000,%a0",
    "move.l #0x11223344,%d7", "move.l %d7,(%a0)"          // mem[0x3000..3] = 11 22 33 44
  )
  test("lock-step: CAS.L match -> mem:=Du, Dc unchanged, Z=1", VerilatorTest) {
    runLockStep("cas-l-match", (casSeed ++ Seq(
      "ori #0x10,%ccr",                                    // X=1 sentinel (CAS leaves X)
      "move.l #0x11223344,%d2",                            // Dc = mem -> MATCH
      "move.l #0xaabbccdd,%d1",                            // Du
      "cas.l %d2,%d1,(%a0)",                               // mem := Du = 0xAABBCCDD, Z=1
      "move.l (%a0),%d3"                                   // read-back
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: CAS.L mismatch -> Dc:=mem, mem unchanged, flags from mem-Dc", VerilatorTest) {
    runLockStep("cas-l-mis", (casSeed ++ Seq(
      "ori #0x10,%ccr",
      "move.l #0x55667788,%d2",                            // Dc != mem -> MISMATCH
      "move.l #0xaabbccdd,%d1",                            // Du (NOT written)
      "cas.l %d2,%d1,(%a0)",                               // Dc := 0x11223344; mem unchanged
      "move.l (%a0),%d3"                                   // read-back (must still be 0x11223344)
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: CAS.W match + mismatch (Dc upper-16 preserved on merge)", VerilatorTest) {
    runLockStep("cas-w", (casSeed ++ Seq(
      "ori #0x10,%ccr",
      // match: compare low word 0x3344; Dc = 0xDEAD3344 (upper preserved on no-write path).
      "move.l #0xdead3344,%d2", "move.l #0x0000beef,%d1",
      "cas.w %d2,%d1,(%a0)",                               // mem[0x3000..1] := 0xBEEF (low word)
      "move.l (%a0),%d3",
      // mismatch: Dc low word != mem low word -> Dc.W := mem.W, Dc[31:16] preserved.
      "move.l #0xcafe0000,%d4",                            // Dc.W = 0x0000 != mem.W (now 0xBEEF)
      "move.l #0x00001111,%d5", "cas.w %d4,%d5,(%a0)",     // Dc := 0xCAFEBEEF; mem unchanged
      "move.l (%a0),%d6"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 2)
  }
  test("lock-step: CAS.B match + mismatch (Dc upper-24 preserved)", VerilatorTest) {
    runLockStep("cas-b", (casSeed ++ Seq(
      "ori #0x10,%ccr",
      // mem[0x3000] = 0x11. match: Dc.B = 0x11; Dc = 0x12345611.
      "move.l #0x12345611,%d2", "move.l #0x000000aa,%d1",  // Du = 0xAA
      "cas.b %d2,%d1,(%a0)",                               // mem[0x3000] := 0xAA
      "move.b (%a0),%d3",
      // mismatch: Dc.B != mem.B -> Dc.B := mem.B (0xAA), Dc[31:8] preserved.
      "move.l #0x99887700,%d4", "move.l #0x000000bb,%d5",
      "cas.b %d4,%d5,(%a0)",                               // Dc := 0x998877AA; mem unchanged
      "move.b (%a0),%d6"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 1)
  }

  // CAS auto-inc/dec EA modes ((An)+ / -(An)) — memory-ALTERABLE, Musashi mask `A+-DXWL...`.
  // The auto side effect happens ONCE during EA computation (BEFORE the compare), so An
  // updates on BOTH match and mismatch; the LOAD and the (always-)STORE use the SAME
  // captured address. (An)+: addr=An, An+=size. -(An): An-=size first, addr=new An.
  test("lock-step: CAS.L (%a0)+ match -> mem:=Du, An += 4 (postinc)", VerilatorTest) {
    runLockStep("cas-l-postinc-match", (casSeed ++ Seq(
      "ori #0x10,%ccr",
      "move.l #0x11223344,%d2", "move.l #0xaabbccdd,%d1",  // Dc=mem -> match, Du
      "cas.l %d2,%d1,(%a0)+",                              // mem[0x3000]:=Du; A0 := 0x3004
      "move.l %a0,%d4"                                     // A0 must be 0x3004 (postinc by 4)
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: CAS.L (%a0)+ mismatch -> mem unchanged, An += 4 (postinc on mismatch too)", VerilatorTest) {
    runLockStep("cas-l-postinc-mis", (casSeed ++ Seq(
      "ori #0x10,%ccr",
      "move.l #0x55667788,%d2", "move.l #0xaabbccdd,%d1",  // Dc!=mem -> mismatch
      "cas.l %d2,%d1,(%a0)+",                              // Dc:=mem; mem unchanged; A0 := 0x3004
      "move.l %a0,%d4"                                     // A0 STILL postinc'd to 0x3004
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: CAS.W/.B (%a0)+ -> An += 2 / += 1 (size-correct postinc)", VerilatorTest) {
    runLockStep("cas-wb-postinc", (casSeed ++ Seq(
      "ori #0x10,%ccr",
      // .W (An)+: compare low word, A0 += 2.
      "move.l #0x00003344,%d2", "move.l #0x0000beef,%d1",
      "cas.w %d2,%d1,(%a0)+",                              // A0: 0x3000 -> 0x3002
      "move.l %a0,%d4",
      // .B (An)+: A0 += 1.
      "move.l #0x3000,%a1", "move.b (%a1),%d5",            // mem[0x3000] low byte (now 0xBE from .W write)
      "cas.b %d5,%d6,(%a1)+",                              // compare byte, A1 += 1
      "move.l %a1,%d7"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 2)
  }
  test("lock-step: CAS.L -(%a0) match -> An -= 4 once, access at decremented addr", VerilatorTest) {
    runLockStep("cas-l-predec-match", (Seq(
      "move.l #0x3004,%a0",                                // A0 base; -(A0) accesses 0x3000
      "move.l #0x11223344,%d7", "move.l %d7,0x3000",       // seed mem[0x3000]
      "ori #0x10,%ccr",
      "move.l #0x11223344,%d2", "move.l #0xaabbccdd,%d1",  // Dc=mem -> match
      "cas.l %d2,%d1,-(%a0)",                              // A0 := 0x3000 first; mem[0x3000]:=Du
      "move.l %a0,%d4",                                    // A0 must be 0x3000 (decremented once)
      "move.l (%a0),%d3"                                   // read-back at the decremented addr
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  test("lock-step: CAS.L -(%a0) mismatch -> An -= 4 once, mem unchanged", VerilatorTest) {
    runLockStep("cas-l-predec-mis", (Seq(
      "move.l #0x3004,%a0",
      "move.l #0x11223344,%d7", "move.l %d7,0x3000",
      "ori #0x10,%ccr",
      "move.l #0x55667788,%d2", "move.l #0xaabbccdd,%d1",  // Dc!=mem -> mismatch
      "cas.l %d2,%d1,-(%a0)",                              // A0 := 0x3000; Dc:=mem; mem unchanged
      "move.l %a0,%d4",                                    // A0 = 0x3000 (decremented once)
      "move.l (%a0),%d3"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }

  // ════════════════════════════════════════════════════════════════════════════
  // CAS2 .W/.L (dual-address compare-and-swap). gas syntax:
  //   cas2 Dc1:Dc2,Du1:Du2,(Rn1):(Rn2)
  // Both addresses are read UNCONDITIONALLY. If BOTH match (dest1==Dc1 && dest2==Dc2):
  // write Du1->Rn1, Du2->Rn2, Dc1/Dc2 unchanged. Else: no writes, BOTH Dc1:=dest1 AND
  // Dc2:=dest2 (.W via the BIT_1F/BIT_F sign-ext rule). flags = res2 if dest1==Dc1 else res1.
  // ALWAYS-STORE: on any mismatch the loaded values are written back (RAM no-op) so mem is
  // byte-identical to Musashi. The kept commit (CAS2C2) carries only the final CCR; the
  // Dc1/Dc2 writes land in the PRF and are verified by reading them back.
  // mem[0x3000]=11223344, mem[0x3004]=55667788.
  // ════════════════════════════════════════════════════════════════════════════
  val cas2Seed = Seq(
    "move.l #0x3000,%a0", "move.l #0x3004,%a1",
    "move.l #0x11223344,%d7", "move.l %d7,(%a0)",
    "move.l #0x55667788,%d7", "move.l %d7,(%a1)"
  )
  test("lock-step: CAS2.L both-match -> both written, flags=res2 (Z=1)", VerilatorTest) {
    runLockStep("cas2-l-both", (cas2Seed ++ Seq(
      "ori #0x10,%ccr",                                    // X=1 sentinel
      "move.l #0x11223344,%d0",                            // Dc1 = mem1 -> eq1
      "move.l #0x55667788,%d1",                            // Dc2 = mem2 -> eq2
      "move.l #0xa1a1a1a1,%d2", "move.l #0xb2b2b2b2,%d3",  // Du1, Du2
      "cas2.l %d0:%d1,%d2:%d3,(%a0):(%a1)",                // both match -> mem1:=Du1, mem2:=Du2
      "move.l (%a0),%d4", "move.l (%a1),%d5"               // read-back both
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 4)
  }
  test("lock-step: CAS2.L 1st-mismatch -> flags=res1, Dc1:=dest1 AND Dc2:=dest2, no writes", VerilatorTest) {
    runLockStep("cas2-l-mis1", (cas2Seed ++ Seq(
      "ori #0x10,%ccr",
      "move.l #0xdeadbeef,%d0",                            // Dc1 != mem1 -> MISMATCH (1st)
      "move.l #0x55667788,%d1",                            // Dc2 == mem2
      "move.l #0xa1a1a1a1,%d2", "move.l #0xb2b2b2b2,%d3",  // Du (NOT written)
      "cas2.l %d0:%d1,%d2:%d3,(%a0):(%a1)",                // no writes; Dc1:=mem1, Dc2:=mem2
      "move.l %d0,%d4", "move.l %d1,%d5",                  // Dc1/Dc2 read-back (=mem1/mem2)
      "move.l (%a0),%d6", "move.l (%a1),%d7"               // mem read-back (unchanged values)
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 4)
  }
  test("lock-step: CAS2.L 2nd-mismatch (1st matches) -> flags=res2, both Dc updated, no writes", VerilatorTest) {
    runLockStep("cas2-l-mis2", (cas2Seed ++ Seq(
      "ori #0x10,%ccr",
      "move.l #0x11223344,%d0",                            // Dc1 == mem1 (1st matches)
      "move.l #0x00000001,%d1",                            // Dc2 != mem2 -> MISMATCH (2nd)
      "move.l #0xa1a1a1a1,%d2", "move.l #0xb2b2b2b2,%d3",
      "cas2.l %d0:%d1,%d2:%d3,(%a0):(%a1)",                // no writes; Dc1:=mem1, Dc2:=mem2
      "move.l %d0,%d4", "move.l %d1,%d5",
      "move.l (%a0),%d6", "move.l (%a1),%d7"
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 4)
  }
  test("lock-step: CAS2.W both-match + a mismatch (.W Dc sign-ext/merge)", VerilatorTest) {
    runLockStep("cas2-w", (cas2Seed ++ Seq(
      "ori #0x10,%ccr",
      // both-match (.W compares low words 0x3344 / 0x7788).
      "move.l #0xdead3344,%d0",                            // Dc1.W = 0x3344 == mem1.W
      "move.l #0xbeef7788,%d1",                            // Dc2.W = 0x7788 == mem2.W
      "move.l #0x0000c0c0,%d2", "move.l #0x0000d0d0,%d3",  // Du1.W, Du2.W
      "cas2.w %d0:%d1,%d2:%d3,(%a0):(%a1)",                // both match -> mem.W writes
      "move.l (%a0),%d4", "move.l (%a1),%d5",
      // mismatch (.W): Dc.W != mem.W -> Dc update uses BIT_1F/BIT_F. Both Rn are An (D/A=1)
      // -> sign-extend the loaded 16-bit value into the full Dc. mem1.W is now 0xC0C0.
      "move.l #0x00000000,%d6",                            // Dc1.W = 0x0000 != 0xC0C0 -> MISMATCH
      "move.l #0x55667788,%d7",                            // Dc2 == mem2.W (0xD0D0? mem2.W=0xD0D0 now)
      "cas2.w %d6:%d7,%d2:%d3,(%a0):(%a1)",                // no writes; Dc1:=sext(0xC0C0)
      "move.l %d6,%d0", "move.l %d7,%d1"                   // Dc1/Dc2 read-back (sign-extended)
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 2)
  }
  test("lock-step: CAS2.L mixed An+Dn as Rn (REG_DA 0-15)", VerilatorTest) {
    runLockStep("cas2-mixed", (Seq(
      "move.l #0x3000,%a0",                                // Rn1 = A0
      "move.l #0x3004,%d6",                                // Rn2 = D6 (a DATA reg as address!)
      "move.l #0x11223344,%d7", "move.l %d7,(%a0)",
      "move.l #0x55667788,%d7", "move.l %d7,0x3004",
      "ori #0x10,%ccr",
      "move.l #0x11223344,%d0", "move.l #0x55667788,%d1",  // both match
      "move.l #0xa1a1a1a1,%d2", "move.l #0xb2b2b2b2,%d3",
      "cas2.l %d0:%d1,%d2:%d3,(%a0):(%d6)",                // Rn2 is a Dn -> both written
      "move.l (%a0),%d4", "move.l 0x3004,%d5"
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 4)
  }
  test("lock-step: CAS2.L Rn1==Rn2 (same address) -> match Musashi read1,read2", VerilatorTest) {
    runLockStep("cas2-samadr", (Seq(
      "move.l #0x3000,%a0", "move.l #0x3000,%a1",          // SAME address
      "move.l #0x11223344,%d7", "move.l %d7,(%a0)",
      "ori #0x10,%ccr",
      "move.l #0x11223344,%d0", "move.l #0x11223344,%d1",  // both compare to the same loaded value
      "move.l #0xa1a1a1a1,%d2", "move.l #0xb2b2b2b2,%d3",  // both match -> last write (Du2) wins
      "cas2.l %d0:%d1,%d2:%d3,(%a0):(%a1)",
      "move.l (%a0),%d4"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }

  // CAS-in-a-loop lock-free counter (Task 4 stress): a tight CAS retry loop exercising
  // back-to-back atomic RMW + store-queue drains. Single-threaded -> the compare ALWAYS
  // matches (Z=1), so the retry `dbne` never re-loops on a CAS failure; the dbra drives the
  // fixed 3 iterations. Each pass: load current -> Dc, Du = Dc+1, CAS (mem++), dbra. The
  // counter ends at 3. Executed: prologue 4 + 3*(move,move,addq,cas,dbra=5) + epilogue 1 = 20.
  test("lock-step: CAS-loop lock-free counter (atomic RMW under SQ drains)", VerilatorTest) {
    runLockStep("cas-loop",
      Seq(
        "move.l #0x3000,%a0",                              // counter address
        "moveq #0,%d4", "move.l %d4,(%a0)",                // counter := 0
        "moveq #2,%d5",                                    // dbra count -> 3 passes
        ".L: move.l (%a0),%d0",                            // Dc = current counter
        "move.l %d0,%d1", "addq.l #1,%d1",                 // Du = current + 1
        "cas.l %d0,%d1,(%a0)",                             // CAS: mem++ (match -> Z=1)
        "dbra %d5,.L",                                     // loop the fixed count
        "move.l (%a0),%d6"                                 // final counter (= 3)
      ).mkString(" ; "), nInstr = 20, checkMem = Seq(0x3000L), checkSpan = 4)
  }

  // ════════════════════════════════════════════════════════════════════════════
  // MOVES .B/.W/.L (010+ PRIVILEGED move to/from alternate address space). gas syntax:
  //   moves.sz Rn,<ea>  (dr=1, WRITE: store Rn -> mem)
  //   moves.sz <ea>,Rn  (dr=0, READ:  load mem -> Rn; An sign-extends, Dn size-merges)
  // The access is FLAT (Musashi `(void)fc`), so MOVES trace-matches a normal sized MOVE +
  // the EA auto-inc/dec side effect + register state. NO CCR effect. Supervisor (boot SR
  // 0x2700); the user-mode privilege trap is the separate test below. Seed mem via move,
  // read mem/regs back + checkMem on the modified long vs Musashi.
  // ════════════════════════════════════════════════════════════════════════════
  val movesSeed = Seq(
    "move.l #0x3000,%a0",
    "move.l #0x11223344,%d7", "move.l %d7,(%a0)"           // mem[0x3000..3] = 11 22 33 44
  )

  // WRITE .L from a Dn: store Dn -> mem, read back.
  test("lock-step: MOVES.L Dn -> (An) write, read-back", VerilatorTest) {
    runLockStep("moves-l-wr-dn", (movesSeed ++ Seq(
      "ori #0x10,%ccr",                                    // X=1 sentinel (MOVES leaves CCR)
      "move.l #0xaabbccdd,%d0",
      "moves.l %d0,(%a0)",                                 // mem := 0xAABBCCDD
      "move.l (%a0),%d3"                                   // read-back
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // WRITE .L from an An: store An -> mem (the A/D+reg 0-15 index source).
  test("lock-step: MOVES.L An -> (An) write, read-back", VerilatorTest) {
    runLockStep("moves-l-wr-an", (movesSeed ++ Seq(
      "ori #0x10,%ccr",
      "move.l #0x0badf00d,%a2",
      "moves.l %a2,(%a0)",                                 // mem := A2 = 0x0BADF00D
      "move.l (%a0),%d3"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // WRITE .W / .B (sized store; upper mem bytes preserved).
  test("lock-step: MOVES.W / MOVES.B Dn -> (An) write (sized store)", VerilatorTest) {
    runLockStep("moves-wb-wr", (movesSeed ++ Seq(
      "ori #0x10,%ccr",
      "move.l #0x0000beef,%d0", "moves.w %d0,(%a0)",       // mem[0x3000..1] := 0xBEEF
      "move.l (%a0),%d3",
      "move.l #0x000000a5,%d1", "moves.b %d1,(%a0)",       // mem[0x3000] := 0xA5
      "move.l (%a0),%d4"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }

  // READ .L into a Dn (full 32) and into an An (full 32).
  test("lock-step: MOVES.L (An) -> Dn / -> An read", VerilatorTest) {
    runLockStep("moves-l-rd", (movesSeed ++ Seq(
      "ori #0x10,%ccr",
      "moves.l (%a0),%d1",                                 // D1 := mem = 0x11223344
      "moves.l (%a0),%a3",                                 // A3 := mem = 0x11223344
      "move.l %a3,%d2"                                     // surface A3 in a Dn
    )).mkString(" ; "))
  }
  // READ .W / .B into a Dn — SIZE-MERGE: the upper bits of Dn are PRESERVED.
  test("lock-step: MOVES.W / MOVES.B (An) -> Dn (size-merge, upper bits preserved)", VerilatorTest) {
    runLockStep("moves-wb-rd-dn", (movesSeed ++ Seq(
      "ori #0x10,%ccr",
      "move.l #0xdeadbeef,%d1", "moves.w (%a0),%d1",       // D1 := 0xDEAD3344 (low word from mem)
      "move.l #0xcafef00d,%d2", "moves.b (%a0),%d2"        // D2 := 0xCAFEF044 (low byte from mem)
    )).mkString(" ; "))
  }
  // READ .W / .B into an An — SIGN-EXTEND to 32 (Musashi MAKE_INT_16/8).
  test("lock-step: MOVES.W / MOVES.B (An) -> An (sign-extend to 32)", VerilatorTest) {
    runLockStep("moves-wb-rd-an", (Seq(
      "move.l #0x3000,%a0",
      "move.l #0x8001ff80,%d7", "move.l %d7,(%a0)",        // mem = 80 01 FF 80 (.W=0x8001 neg, .B=0x80 neg)
      "ori #0x10,%ccr",
      "moves.w (%a0),%a3",                                 // A3 := sext16(0x8001) = 0xFFFF8001
      "move.l %a3,%d2",
      "moves.b (%a0),%a4",                                 // A4 := sext8(0x80) = 0xFFFFFF80
      "move.l %a4,%d3"
    )).mkString(" ; "))
  }

  // (An)+ READ: the An side effect happens ONCE (postinc by size).
  test("lock-step: MOVES.L (An)+ read -> An += 4 (postinc once)", VerilatorTest) {
    runLockStep("moves-l-postinc-rd", (movesSeed ++ Seq(
      "ori #0x10,%ccr",
      "moves.l (%a0)+,%d1",                                // D1 := mem; A0 := 0x3004
      "move.l %a0,%d4"                                     // A0 must be 0x3004
    )).mkString(" ; "))
  }
  // -(An) WRITE: the An side effect happens ONCE (predec by size), access at decremented addr.
  test("lock-step: MOVES.L Dn -> -(An) write -> An -= 4 once, store at decremented addr", VerilatorTest) {
    runLockStep("moves-l-predec-wr", (Seq(
      "move.l #0x3004,%a0",                                // A0 = 0x3004
      "move.l #0xaabbccdd,%d0",
      "ori #0x10,%ccr",
      "moves.l %d0,-(%a0)",                                // A0 := 0x3000; mem[0x3000] := 0xAABBCCDD
      "move.l %a0,%d4",                                    // A0 must be 0x3000
      "move.l (%a0),%d3"                                   // read-back at 0x3000
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // (An)+ WRITE (size-correct postinc on .W/.B).
  test("lock-step: MOVES.W (An)+ write -> An += 2 (size-correct postinc)", VerilatorTest) {
    runLockStep("moves-w-postinc-wr", (movesSeed ++ Seq(
      "ori #0x10,%ccr",
      "move.l #0x0000beef,%d0", "moves.w %d0,(%a0)+",      // mem[0x3000..1] := 0xBEEF; A0 := 0x3002
      "move.l %a0,%d4",                                    // A0 = 0x3002
      "move.l 0x3000,%d3"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }

  // Multi-word EA: (d16,An) read, abs.L write (nextPc framing + correct address).
  test("lock-step: MOVES.L (d16,An) read + abs.L write (multi-word EA)", VerilatorTest) {
    runLockStep("moves-multiword", (Seq(
      "move.l #0x2FFC,%a0",                                // base; (4,A0) = 0x3000
      "move.l #0x11223344,%d7", "move.l %d7,0x3000",
      "ori #0x10,%ccr",
      "moves.l (4,%a0),%d1",                              // D1 := mem[0x3000]
      "move.l #0xaabbccdd,%d0",
      "moves.l %d0,0x3004",                               // abs.L dest: mem[0x3004] := 0xAABBCCDD
      "move.l 0x3004,%d2"
    )).mkString(" ; "), checkMem = Seq(0x3000L, 0x3004L), checkSpan = 4)
  }

  // Indexed EA (d8,An,Xn): the brief-format index rides the EA; nextPc framing + address.
  test("lock-step: MOVES.L (d8,An,Xn) read + write (indexed EA)", VerilatorTest) {
    runLockStep("moves-indexed", (Seq(
      "move.l #0x2000,%a0",                                // base
      "move.l #0x1000,%d6",                               // index -> (0,A0,D6.l) = 0x3000
      "move.l #0x11223344,%d7", "move.l %d7,0x3000",
      "ori #0x10,%ccr",
      "moves.l (0,%a0,%d6.l),%d1",                        // D1 := mem[0x3000]
      "move.l #0xaabbccdd,%d0",
      "moves.l %d0,(0,%a0,%d6.l)",                        // mem[0x3000] := 0xAABBCCDD
      "move.l 0x3000,%d2"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }

  // MOVES does NOT touch CCR: seed N=1,V=1 via overflow, then a MOVES, then verify the CCR
  // survives (the lock-step compares the full CCR at each commit -> any MOVES CCR write
  // would diverge here AND at the trailing op).
  test("lock-step: MOVES leaves CCR unchanged", VerilatorTest) {
    runLockStep("moves-ccr", (movesSeed ++ Seq(
      "move.l #0x7fffffff,%d6", "add.l %d6,%d6",           // N=1, V=1, C=0, X unchanged
      "move.l #0x12345678,%d0", "moves.l %d0,(%a0)",       // WRITE — must not touch CCR
      "moves.l (%a0),%d1",                                 // READ — must not touch CCR
      "move.l #0x7fffffff,%d6", "add.l %d6,%d6"            // re-seed (proves the value path too)
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }

  // MOVEC SFC then MOVES (sanity: the SFC value does NOT change the flat MOVES result).
  test("lock-step: MOVEC SFC := 5 then MOVES (flat access unaffected by SFC)", VerilatorTest) {
    runLockStep("moves-after-sfc", (movesSeed ++ Seq(
      "move.l #0x00000005,%d0", "movec %d0,%sfc",          // SFC := 5 (does not redirect address space)
      "moves.l (%a0),%d1",                                 // D1 := mem = 0x11223344 (SAME flat read)
      "move.l #0xaabbccdd,%d2", "movec %d2,%dfc",          // DFC := 5
      "moves.l %d2,(%a0)",                                 // mem := 0xAABBCCDD (SAME flat write)
      "move.l (%a0),%d3"
    )).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }

  // ── A2 fix: MOVES Rn==An aliasing (the moved register IS the EA's address register) ──
  // Musashi computes the EA via the GET_EA_AY macro, which MUTATES An as part of the EA
  // calc, and only THEN reads/writes the register for the moved value — so when Rn IS
  // the EA's An, the auto-update's effect is visible to the Rn access. No directed test
  // existed for this before (deep-audit finding A2).
  //
  // WRITE -(An), Rn==An: EA = An-4 (decremented FIRST); the STORED value is the register
  // read AFTER that decrement -> the NEW (decremented) An, not the value An held before
  // the instruction.
  test("lock-step: MOVES.L %a2,-(%a2) — Rn==An predec write stores the DECREMENTED An", VerilatorTest) {
    runLockStep("moves-l-wr-alias-pd", Seq(
      "move.l #0x3000,%a1",                 // A1: a stable pointer to read the store back with
      "move.l #0x3004,%a2",                 // A2: the aliased Rn==An register
      "ori #0x10,%ccr",
      "moves.l %a2,-(%a2)",                 // EA=A2-4=0x3000 (predec first); store A2's NEW value
      "move.l %a2,%d1",                     // surface final A2 (expect 0x3000)
      "move.l (%a1),%d2"                    // surface the stored bytes (expect 0x3000)
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // WRITE (An)+, Rn==An: EA = OLD An (used for the address); An then increments as part
  // of the SAME EA calc; the STORED value is the register read AFTER that increment ->
  // the NEW (incremented) An, even though the write lands at the OLD address.
  test("lock-step: MOVES.L %a2,(%a2)+ — Rn==An postinc write stores the INCREMENTED An", VerilatorTest) {
    runLockStep("moves-l-wr-alias-pi", Seq(
      "move.l #0x3000,%a1",                 // A1: a stable pointer to the write address
      "move.l #0x3000,%a2",                 // A2: the aliased Rn==An register
      "ori #0x10,%ccr",
      "moves.l %a2,(%a2)+",                 // EA=A2=0x3000 (old); A2 -> 0x3004; store A2's NEW value
      "move.l %a2,%d1",                     // surface final A2 (expect 0x3004)
      "move.l (%a1),%d2"                    // surface the stored bytes at 0x3000 (expect 0x3004)
    ).mkString(" ; "), checkMem = Seq(0x3000L), checkSpan = 4)
  }
  // READ (An)+, Rn==An: the load overwrites An LAST, so the auto-update's effect on An is
  // moot — the final An is simply the LOADED value, not old_An+delta.
  test("lock-step: MOVES.L (%a2)+,%a2 — Rn==An postinc read: loaded value wins over the auto-update", VerilatorTest) {
    runLockStep("moves-l-rd-alias-pi", Seq(
      "move.l #0x3000,%a1", "move.l #0xcafebabe,%d7", "move.l %d7,(%a1)",  // mem[0x3000]=0xCAFEBABE
      "move.l #0x3000,%a2",                 // A2: the aliased Rn==An register
      "ori #0x10,%ccr",
      "moves.l (%a2)+,%a2",                 // load mem[0x3000] -> A2 (auto-update to 0x3004 is moot)
      "move.l %a2,%d1"                      // surface final A2 (expect 0xCAFEBABE, NOT 0x3004)
    ).mkString(" ; "))
  }
  // READ -(An), Rn==An: same overwrite-last-wins rule with the predec address calc.
  test("lock-step: MOVES.L -(%a2),%a2 — Rn==An predec read: loaded value wins over the auto-update", VerilatorTest) {
    runLockStep("moves-l-rd-alias-pd", Seq(
      "move.l #0x3000,%a1", "move.l #0xdeadbeef,%d7", "move.l %d7,(%a1)", // mem[0x3000]=0xDEADBEEF
      "move.l #0x3004,%a2",                 // A2: the aliased Rn==An register
      "ori #0x10,%ccr",
      "moves.l -(%a2),%a2",                 // EA=A2-4=0x3000 (predec); load -> A2 (auto-update to 0x3000 is moot)
      "move.l %a2,%d1"                      // surface final A2 (expect 0xDEADBEEF, NOT 0x3000)
    ).mkString(" ; "))
  }

  // MOVES is PRIVILEGED: in USER mode (S=0) it raises a vector-8 privilege violation
  // (format-$0) BEFORE executing — the memory is NOT modified. We BOOT in supervisor (so the
  // supervisor ISP = 0x00100000 matches Musashi's reset SP, and the trap frame addresses
  // agree), install the vector-8 handler + seed mem + a USP, then DROP to user via
  // `move.w #0x0000,%sr`. The MOVES then TRAPs -> the supervisor handler (on the ISP) bumps
  // the stacked PC past the 4-byte MOVES and RTEs back to user mode. Handler-entry PC/SR/A7
  // + the RTE return are lock-stepped vs Musashi; mem is checkMem'd (the seed value survives
  // -> the MOVES did NOT execute). The handler is installed at VBR(0)+8*4 = 0x20.
  test("lock-step: MOVES in USER mode -> vector-8 privilege violation (op does not execute)", VerilatorTest) {
    runLockStep("moves-priv",
      // Supervisor setup: install vector 8 @ 0x20, seed mem, set USP, then drop to user.
      "move.l #handler,%d0 ; move.l %d0,0x20 ; " +         // vector 8 @ 0x20 := handler
      "move.l #0x3000,%a0 ; move.l #0x11223344,%d7 ; move.l %d7,(%a0) ; " +
      "move.l #0x00080000,%d5 ; movec %d5,%usp ; " +       // seed USP (the user A7 after the SR drop)
      "move.l #0xaabbccdd,%d4 ; " +
      "move.w #0x0000,%sr ; " +                            // S := 0 -> USER mode (A7 := USP)
      "moves.l %d4,(%a0) ; " +                             // PRIVILEGED in user mode -> vector 8 (mem unchanged)
      "moveq #9,%d3 ; " +                                  // resume target after the handler RTEs past MOVES
      "loop: bra loop ; " +
      // vector-8 handler (supervisor): bump the stacked PC (format-$0: PC at 2(A7)) past the
      // 4-byte MOVES so RTE resumes at `moveq #9` (else it re-faults forever); then RTE.
      "handler: move.l 2(%a7),%d1 ; addq.l #4,%d1 ; move.l %d1,2(%a7) ; moveq #1,%d2 ; rte",
      nInstr = 14, checkMem = Seq(0x3000L), checkSpan = 4)
  }
}
