package m68k040.top

import m68k040.decode.DecodedUop
import m68k040.services.{BranchPredRec, DecodeUopService}
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Synthesis-only boundary: provides DecodeUopService from registered top-level
  * IO so the OoO backend (rename → ROB) can be synthesized out-of-context. The
  * inputs are registered (RegNext) so measured paths are internal reg→reg, not
  * unconstrained IO. */
class DecodeUopInputPlugin extends FiberPlugin with DecodeUopService {
  var uopsPort: Stream[Vec[DecodedUop]] = null
  var uop1ValidReg: Bool = null
  var uopPredPort: Vec[BranchPredRec] = null
  var flushTie: Bool = null
  var complexResumeTie: Flow[UInt] = null

  override def uops: Stream[Vec[DecodedUop]] = uopsPort
  override def uop1Valid: Bool = uop1ValidReg
  override def uopPred: Vec[BranchPredRec] = uopPredPort
  override def pipeFlush: Bool = flushTie
  override def backendFlush: Bool = flushTie   // no FP wide-imm table consumer in the backend-only synth host
  override def complexResume: Flow[UInt] = complexResumeTie

  during setup {
    uopsPort = Stream(Vec(DecodedUop(), 2))
    uop1ValidReg = Bool()
    uopPredPort = Vec(BranchPredRec(), 2)
    flushTie = False
    complexResumeTie = Flow(UInt(32 bits))
  }

  val logic = during build new Area {
    val uopsInPayload = in(Vec(DecodedUop(), 2))
    val uopsInValid   = in Bool ()
    val uop1ValidIn   = in Bool ()
    val uopsOutReady  = out Bool ()

    uopsPort.valid   := RegNext(uopsInValid)   init False
    uopsPort.payload := RegNext(uopsInPayload)
    uop1ValidReg     := RegNext(uop1ValidIn)   init False
    uopsOutReady     := RegNext(uopsPort.ready) init False
    // Branch-prediction side channel: registered IO, like the uop payload itself. The
    // backend-only synth host has no DecodeStage side table to read, so the record comes
    // in from the boundary alongside the uops it describes.
    val uopPredIn = in(Vec(BranchPredRec(), 2))
    uopPredPort := RegNext(uopPredIn)
    complexResumeTie.valid   := False
    complexResumeTie.payload := 0
  }
}
