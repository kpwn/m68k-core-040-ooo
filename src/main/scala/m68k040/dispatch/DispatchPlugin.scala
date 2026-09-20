package m68k040.dispatch

import m68k040.services.{RenameUopService, RobAllocService}
import m68k040.execute.iq.IssueQueueService
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Consumes the renamed µop stream; takes robIds from the ROB alloc interface;
  * fans each µop to ROB-alloc AND IssueQueue-push with combined back-pressure.
  *
  * The handshake: `ren.uops.ready = rob.allocReady && iq.push.ready`, and
  * `fire = ren.uops.valid && rob.allocReady && iq.push.ready`. Both ROB-alloc
  * and IQ-push are gated by the same `fire`, so they allocate the SAME µops with
  * the SAME robIds in the SAME cycle (no desync). `rob.allocReady` and
  * `iq.push.ready` are registered/combinational predicates that do NOT depend on
  * `fire`, so there is no combinational loop.
  *
  * HISTORY: a `cpuBootThrottleEn` input once gated admission rate here, to slow
  * the core through the Q700 ROM's boot calibration loop. It was REMOVED
  * 2026-09-03: the project owner rejected throttling as the fix in favour of ROM
  * patches, and leaving the mechanism wired in as dead capability invited
  * accidental future use. Do not reintroduce it -- if a ROM loop measures CPU
  * speed on purpose, patch the ROM's calibration constants instead. */
class DispatchPlugin(val detailedPerf: Boolean = false) extends FiberPlugin
    with m68k040.services.DispatchPerfDetailService {
  private var perfEventsWire: Option[Bits] = None
  during setup {
    if (detailedPerf) perfEventsWire = Some(Bits(m68k040.services.PerfDetail.DispatchCount bits))
  }
  override def dispatchPerfEvents: Option[Bits] = perfEventsWire
  val logic = during build new Area {
    val ren = host[RenameUopService]
    val rob = host[RobAllocService]
    val iq  = host[IssueQueueService]

    val fire = ren.uops.valid && rob.allocReady && iq.push.ready
    val perfEvents = if (detailedPerf) Some(m68k040.services.PerfDetail.dispatchEvents(
      ren.uops.valid, rob.allocReady, iq.push.ready, ren.uop1Valid)) else None
    perfEventsWire.foreach(_ := perfEvents.get)
    ren.uops.ready := rob.allocReady && iq.push.ready

    // drive ROB alloc
    rob.allocFire   := fire
    rob.allocUop(0) := ren.uops.payload(0)
    rob.allocUop(1) := ren.uops.payload(1)
    rob.allocSlot1  := ren.uop1Valid

    // drive IQ push (same robIds the ROB will use)
    iq.push.valid := fire
    iq.push.payload(0).uop := ren.uops.payload(0); iq.push.payload(0).robId := rob.robId0
    iq.push.payload(1).uop := ren.uops.payload(1); iq.push.payload(1).robId := rob.robId1
    iq.pushSlot1Valid := ren.uop1Valid
  }
}
