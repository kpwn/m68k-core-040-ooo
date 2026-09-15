package m68k040.rename

import m68k040.decode.DecodedUop
import m68k040.services.DecodeUopService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only plugin: PRODUCES DecodeUopService from a top-level-driven Stream.
  *
  * The test drives `src.valid` and `src.payload` (and `s1v`) as top-level IO;
  * RenameStage (the consumer) drives `src.ready`. Following the plain-wire
  * service convention, `src` is a plain Stream returned from `uops`.
  */
class DecodeUopSourcePlugin extends FiberPlugin with DecodeUopService {
  val logic = during build new Area {
    val src = Stream(Vec(DecodedUop(), 2))
    // Test drives valid + payload as top-level inputs; consumer drives ready.
    in(src.valid)
    src.payload.foreach(in(_))
    src.ready.simPublic()
    val s1v = in Bool ()
    val flush = False
    val resume = Flow(UInt(32 bits))
    resume.valid   := False
    resume.payload := 0
  }

  override def uops: Stream[Vec[DecodedUop]] = logic.src
  override def uop1Valid: Bool               = logic.s1v
  override def pipeFlush: Bool               = logic.flush
  // No DecodeStage here (this plugin REPLACES it), so there is no FP wide-immediate side
  // table to reclaim: tie the backend squash to the same inert wire as the frontend one.
  override def backendFlush: Bool            = logic.flush
  override def complexResume: Flow[UInt]     = logic.resume
}
