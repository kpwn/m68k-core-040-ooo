package m68k040.decode

import m68k040.services.{DecodeFeedService, DecodeUopService}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** DecodeStage: 2-wide decode plugin with µop expansion.
  *
  * Hosts two cracking MicroOpAssembler decodes (one per slot), each producing a
  * 1–2 µop sequence. The two sequences are packed in program order into the
  * MicroOpQueue (up to 4 µops/cycle in), which drains a steady 2/cycle to rename
  * (DecodeUopService.uops). The queue absorbs the variable-rate expansion and is
  * flushed on a mispredict (pipeFlush). The decode→rename skid that the previous
  * 1:1 passthrough provided is now the queue itself.
  */
class DecodeStage extends FiberPlugin with DecodeUopService {

  val logic = during build new Area {
    val df = host[DecodeFeedService]

    // Crack both slots into µop sequences.
    val a0 = MicroOpAssembler.assemble(df.feed.payload(0))
    val a1 = MicroOpAssembler.assemble(df.feed.payload(1))

    val slot1Valid = df.feed.valid && df.slot1Valid
    val n0 = a0.count                                  // 1 or 2 (slot0 always present when feed.valid)
    val n1 = Mux(slot1Valid, a1.count, U(0, 2 bits))   // slot1's µops (0 if slot1 invalid)

    // ── µop expansion queue ──────────────────────────────────────────────────
    val queue = new MicroOpQueue(depth = 16)

    // Pack slot0's then slot1's µops at compacted positions (n0 ∈ {1,2}):
    //   push(0) = a0[0]
    //   push(1) = (n0==2) ? a0[1] : a1[0]
    //   push(2) = (n0==2) ? a1[0] : a1[1]
    //   push(3) = a1[1]
    queue.io.push.uops(0) := a0.uops(0)
    queue.io.push.uops(1) := Mux(n0 === U(2), a0.uops(1), a1.uops(0))
    queue.io.push.uops(2) := Mux(n0 === U(2), a1.uops(0), a1.uops(1))
    queue.io.push.uops(3) := a1.uops(1)

    val totalCount = (n0 +^ n1).resize(3)              // 1..4
    queue.io.push.count := totalCount
    queue.io.push.valid := df.feed.valid

    // Feed handshake: accept the burst when the queue has room.
    df.feed.ready := queue.io.push.ready

    // Flush: default-driven False (allowOverride) so a sibling wiring plugin can
    // OVERRIDE it from RedirectService.doFlush (full core). Driving it from
    // host[RedirectService] here would create a Fiber build-order cycle.
    val pipeFlush = Bool(); pipeFlush.allowOverride; pipeFlush := False
    queue.io.flush := pipeFlush

    // ── Rename-facing output ───────────────────────────────────────────────────
    val uopsOut = queue.io.pop
    val uop1Sig = queue.io.pop1Valid
  }

  override def uops: Stream[Vec[DecodedUop]] = logic.uopsOut
  override def uop1Valid: Bool               = logic.uop1Sig
}
