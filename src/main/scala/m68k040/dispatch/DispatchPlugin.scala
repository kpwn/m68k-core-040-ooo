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
  * `cpuBootThrottleEn` (2026-08-27, see docs/BUG_calibration_word_misplaced_0d00.md
  * Part 7 in the companion SoC repo): a real, SoC-driven functional input, NOT a
  * debug tap. cpu040's OoO throughput lets a fixed-iteration ROM POST busy-loop
  * (the Q700 boot calibration routine) exhaust well inside VIA1 Timer 2's real
  * countdown -- a race a real 68040 (and this project's own slower, in-order v1
  * core) never wins. The SoC only asserts this signal while VIA1 Timer 2 has a
  * real countdown in flight AND a bounded (~200ms) early-boot window hasn't yet
  * elapsed -- both self-bounding, so this is provably inert for the rest of the
  * machine's uptime (see the SoC-side `cpu_boot_throttle_en_w` derivation for the
  * two gating conditions). Gating admission rate here (not fetch/decode/rename,
  * and not anything already in flight) keeps the blast radius to exactly "how
  * fast do NEW instructions enter the pipeline" -- correctness of in-flight ROB/
  * IQ/EU state is completely untouched, and DispatchPlugin sits well away from
  * this core's documented FMax-critical-path families (fanout-heavy FetchAlign/
  * Decode broadcast, RobPlugin's per-entry store arrays), so this should carry
  * negligible FMax risk. */
class DispatchPlugin extends FiberPlugin {
  val logic = during build new Area {
    val ren = host[RenameUopService]
    val rob = host[RobAllocService]
    val iq  = host[IssueQueueService]

    val cpuBootThrottleEn = in Bool ()

    // 1-in-ThrottleDivisor admission rate while throttled. Resets to 0 the
    // instant the throttle deasserts, so there is no stale partial-count
    // carryover into normal (untouched) full-speed operation. Divisor is
    // sized with generous margin, not hand-tuned against a specific
    // measured loop duration -- it only needs to reliably push round 1's
    // total real duration past VIA1 Timer 2's real countdown, comfortably
    // within the SoC side's own ~200ms early-boot window cap.
    val ThrottleDivisor = 64
    val throttleCtr = Reg(UInt(log2Up(ThrottleDivisor) bits)) init 0
    when(cpuBootThrottleEn) {
      throttleCtr := throttleCtr + 1
    } otherwise {
      throttleCtr := 0
    }
    val throttleAllow = !cpuBootThrottleEn || (throttleCtr === 0)

    val fire = ren.uops.valid && rob.allocReady && iq.push.ready && throttleAllow
    ren.uops.ready := rob.allocReady && iq.push.ready && throttleAllow

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
