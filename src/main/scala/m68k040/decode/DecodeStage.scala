package m68k040.decode

import m68k040.services.{DecodeFeedService, DecodeUopService}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** DecodeStage: 2-wide decode plugin.
  *
  * Hosts two SimpleDecodeUnit instances (one per slot), drives uop validity
  * from the feed handshake (not from DecodePacket.valid, which is don't-care
  * from the Aligner), and provides a 1:1 combinational passthrough.
  */
class DecodeStage extends FiberPlugin with DecodeUopService {

  val logic = during build new Area {
    val df = host[DecodeFeedService]

    // 2-wide uop output stream
    val uopsPort = Stream(Vec(DecodedUop(), 2))

    // Combinational decode of both slots
    val d0 = SimpleDecodeUnit.decode(df.feed.payload(0))
    val d1 = SimpleDecodeUnit.decode(df.feed.payload(1))

    // Assign decoded content, then override validity from the feed handshake.
    // Use allowOverride on the .valid fields to resolve the overlap between the
    // full-bundle assignment and the subsequent validity override.
    uopsPort.payload(0).allowOverride
    uopsPort.payload(0) := d0
    uopsPort.payload(0).valid := df.feed.valid

    uopsPort.payload(1).allowOverride
    uopsPort.payload(1) := d1
    uopsPort.payload(1).valid := df.feed.valid && df.slot1Valid

    // Stream handshake: 1:1 combinational passthrough
    uopsPort.valid  := df.feed.valid
    df.feed.ready   := uopsPort.ready

    // slot1Valid signal exposed via the service
    val uop1Sig = df.feed.valid && df.slot1Valid

    // Flush-able pipeline register (decode -> rename boundary). Squashed by the
    // ROB's commit-time mispredict redirect. Default-driven False (allowOverride)
    // so a sibling wiring plugin can OVERRIDE it from RedirectService.doFlush
    // (full core). Driving it here from host[RedirectService] directly would create
    // a Fiber build-order cycle (consumer build forces ROB build, ROB depends back
    // through rename->decode->fetch), so the wire is left for the wiring plugin.
    val pipeFlush = Bool(); pipeFlush.allowOverride; pipeFlush := False
    val uopsStaged = m68k040.frontend.PipeStage(uopsPort, pipeFlush)
  }

  override def uops: Stream[Vec[DecodedUop]] = logic.uopsStaged
  override def uop1Valid: Bool               = logic.uopsStaged.payload(1).valid
}
