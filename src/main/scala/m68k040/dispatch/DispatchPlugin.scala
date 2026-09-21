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
  * `fire`, so there is no combinational loop. When MemoryOrderService is present,
  * memory-record capacity joins the same handshake; reservations never consume
  * one side of a stalled ROB/IQ transaction. It is absent in production builders
  * until the full LSU record lifetime and retry paths are connected.
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

    val memoryOrder = host.get[m68k040.ls.MemoryOrderService].map(_.memoryOrder)
    val memoryReady = memoryOrder.map { order =>
      def isMemory(lane: Int): Bool = {
        val u = ren.uops.payload(lane)
        u.cluster === m68k040.isa.Cluster.LS &&
          (u.memOp === m68k040.isa.MemOp.LOAD || u.memOp === m68k040.isa.MemOp.STORE)
      }
      val mem0 = isMemory(0)
      val mem1 = ren.uop1Valid && isMemory(1)
      order.reserveSecond := mem0 && mem1
      order.reserve.payload(0).robId := Mux(mem0, rob.robId0, rob.robId1)
      order.reserve.payload(1).robId := rob.robId1
      order.reserve.payload(0).store := Mux(mem0,
        ren.uops.payload(0).memOp === m68k040.isa.MemOp.STORE,
        ren.uops.payload(1).memOp === m68k040.isa.MemOp.STORE)
      order.reserve.payload(1).store := ren.uops.payload(1).memOp === m68k040.isa.MemOp.STORE
      // Attributes are unresolved at dispatch. The tracker blocks past every
      // unknown address until LSU publication supplies final serialization facts.
      order.reserve.payload(0).serial := False
      order.reserve.payload(1).serial := False
      val ready = !order.flush && (!(mem0 || mem1) || order.reserve.ready)
      // Same transaction as ROB and IQ. reserve.ready is capacity-only, never
      // dependent on reserve.valid, so this adds no handshake combinational loop.
      order.reserve.valid := ren.uops.valid && rob.allocReady && iq.push.ready &&
        ready && (mem0 || mem1)
      ready
    }.getOrElse(True)
    val fire = ren.uops.valid && rob.allocReady && iq.push.ready && memoryReady
    val perfEvents = if (detailedPerf) Some(m68k040.services.PerfDetail.dispatchEvents(
      ren.uops.valid, rob.allocReady, iq.push.ready, ren.uop1Valid, memoryReady)) else None
    perfEventsWire.foreach(_ := perfEvents.get)
    ren.uops.ready := rob.allocReady && iq.push.ready && memoryReady

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
