package m68k040.rob

import m68k040.services.{RenameUopService, RenameCommitService, RobAllocService,
  CacheControlService, FrontendQuiesceService, DebugCommitService}
import m68k040.rename.RenamedUop
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only: PRODUCES RenameUopService from top-level-driven signals.
  * Test drives src.valid + src.payload + u1v; consumer drives src.ready. */
class RenameUopSourcePlugin extends FiberPlugin with RenameUopService {
  val logic = during build new Area {
    val src = Stream(Vec(RenamedUop(), 2))
    in(src.valid)
    src.payload.foreach(in(_))
    src.ready.simPublic()
    val u1v = in Bool ()
  }
  override def uops: Stream[Vec[RenamedUop]] = logic.src
  override def uop1Valid: Bool               = logic.u1v
}

/** Test-only mini-dispatch: drives the ROB's passive RobAllocService directly
  * from a RenameUopService (no IQ). Mirrors DispatchPlugin's ROB-alloc side so
  * RobPluginSpec can exercise the ring/retire path without an IssueQueue.
  * Back-pressure is ROB-only: ren.uops.ready := rob.allocReady. */
class RobAllocDriverPlugin extends FiberPlugin {
  val logic = during build new Area {
    val ren = host[RenameUopService]
    val rob = host[RobAllocService]
    val fire = ren.uops.valid && rob.allocReady
    ren.uops.ready := rob.allocReady
    rob.allocFire   := fire
    rob.allocUop(0) := ren.uops.payload(0)
    rob.allocUop(1) := ren.uops.payload(1)
    rob.allocSlot1  := ren.uop1Valid
  }
}

/** Test-only: CONSUMES RenameCommitService from RobPlugin, exposing commit ports
  * + flush as top-level IO so the test can observe retire-driven commit/free. */
class RenameCommitSinkPlugin extends FiberPlugin with RenameCommitService {
  val logic = during build new Area {
    // Directionless service wires (RobPlugin drives these).
    val commits = Vec.fill(2)(Flow(m68k040.rob.CommitSlot()))
    val flushP  = Bool()
    // Mirror to top-level outputs for observation.
    val commitValidOut = out(Vec(Bool(), 2))
    val commitArchOut  = out(Vec(UInt(5 bits), 2))
    val commitOldOut   = out(Vec(UInt(6 bits), 2))
    // FP data + FPCC commit fields (2026-08-16 FP-commit-path fix): the ROB is the
    // ONLY producer of these, so a directed test needs them observable to prove the
    // rename identity really threads RobPayload -> CommitSlot.
    val commitFpArchOut  = out(Vec(UInt(3 bits), 2))
    val commitFpNewOut   = out(Vec(UInt(4 bits), 2))
    val commitFpOldOut   = out(Vec(UInt(4 bits), 2))
    val commitFpWrOut    = out(Vec(Bool(), 2))
    val commitFpccNewOut = out(Vec(UInt(4 bits), 2))
    val commitFpccOldOut = out(Vec(UInt(4 bits), 2))
    val commitFpccWrOut  = out(Vec(Bool(), 2))
    for (k <- 0 until 2) {
      commitValidOut(k) := commits(k).valid
      commitArchOut(k)  := commits(k).intArch
      commitOldOut(k)   := commits(k).intOld
      commitFpArchOut(k)  := commits(k).fpArchDst
      commitFpNewOut(k)   := commits(k).fpNew
      commitFpOldOut(k)   := commits(k).fpOld
      commitFpWrOut(k)    := commits(k).fpWrite
      commitFpccNewOut(k) := commits(k).fpccNew
      commitFpccOldOut(k) := commits(k).fpccOld
      commitFpccWrOut(k)  := commits(k).fpccWrite
    }
    val flushOut = out(Bool())
    flushOut := flushP
  }
  override def commitPorts: Vec[Flow[CommitSlot]] = logic.commits
  override def flushPort:   Bool                  = logic.flushP
}

/** Test-only: exposes CacheControlService as top-level IO for SpinalSim.
  * Test reads dut.cacheCtrl.logic.dcacheEnabledOut. */
class CacheControlSinkPlugin extends FiberPlugin {
  val logic = during build new Area {
    val cc = host[CacheControlService]
    val dcacheEnabledOut = out(Bool())
    dcacheEnabledOut := cc.dcacheEnabled
  }
}

/** Test-only: consumes the actual ROB-owned FrontendQuiesceService and exposes
  * both phases as named top-level outputs. This verifies the setup-allocated
  * service boundary itself rather than reaching into the provider's private wire.
  */
class FrontendQuiesceSinkPlugin extends FiberPlugin {
  val logic = during build new Area {
    val q = host[FrontendQuiesceService]
    val activeOut = out(Bool())
    val nextOut = out(Bool())
    activeOut := q.active
    nextOut := q.next
  }
}

/** Test-only: consumes the actual ROB-owned DebugCommitService and exposes every
  * accessor as a named top-level output. Mirrors FrontendQuiesceSinkPlugin exactly
  * -- verifies the setup-allocated service boundary itself rather than reaching
  * into the provider's private wire. Stage 2 task 2: implements nothing, just
  * peeks the (currently inert) read-side accessors for a directed test.
  */
class DebugCommitSinkPlugin extends FiberPlugin {
  val logic = during build new Area {
    val d = host[DebugCommitService]
    val effectiveHaltOut    = out(Bool())
    val autoHaltLatchedOut  = out(Bool())
    val haltReasonDebugOut  = out(UInt(3 bits))
    val livePcOut           = out(UInt(32 bits))
    val lastPcOut           = out(UInt(32 bits))
    val macroCountOut       = out(UInt(64 bits))
    val haltHitInstCountOut = out(UInt(64 bits))
    val haltAfterConsumedOut = out(Bool())
    val haltHitPcOut = out(UInt(32 bits))
    val breakpointHitValidOut = out(Bool())
    val breakpointHitSlotOut = out(UInt(2 bits))
    val exceptionPendingOut = out(Bool())
    val haltExceptionVectorOut = out(UInt(8 bits))
    val haltExceptionPcOut = out(UInt(32 bits))
    val haltExceptionFaultAddressOut = out(UInt(32 bits))
    effectiveHaltOut    := d.effectiveHalt
    autoHaltLatchedOut  := d.autoHaltLatched
    haltReasonDebugOut  := d.haltReasonDebug
    livePcOut           := d.livePc
    lastPcOut           := d.lastPc
    macroCountOut       := d.macroCount
    haltHitInstCountOut := d.haltHitInstCount
    haltAfterConsumedOut := d.haltAfterConsumed
    haltHitPcOut := d.haltHitPc
    breakpointHitValidOut := d.breakpointHit.valid
    breakpointHitSlotOut := d.breakpointHit.payload
    exceptionPendingOut := d.exceptionPending
    haltExceptionVectorOut := d.haltExceptionVector
    haltExceptionPcOut := d.haltExceptionPc
    haltExceptionFaultAddressOut := d.haltExceptionFaultAddress
  }
}
