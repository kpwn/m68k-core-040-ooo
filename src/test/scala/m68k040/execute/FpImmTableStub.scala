package m68k040.execute

import m68k040.Global
import m68k040.services.FpImmTableService
import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only stand-in for DecodeStage's FP wide-immediate side table
  * (FpImmTableService) for harnesses that host DivEuPlugin WITHOUT a DecodeStage and
  * hand-build their RenamedUops. The read data is a plain wire the harness may override
  * (default 0): a harness that wants a specific immediate drives `ports.rdData` with it,
  * ignoring the tag -- exactly what those harnesses used to do by writing the removed
  * `uop.fpWideImm` field directly. The consumer-driven ports are default-idle so the
  * stub also elaborates in a harness that never issues an FP uop. */
class FpImmTableStub extends FiberPlugin with FpImmTableService {
  val ports = during setup new Area {
    val rdAddr = UInt(Global.FP_IMM_TAG_W bits); rdAddr.allowOverride; rdAddr := 0
    val rdData = Bits(80 bits);                   rdData.allowOverride; rdData := 0
    val free   = Flow(UInt(Global.FP_IMM_TAG_W bits))
    free.valid.allowOverride;   free.valid   := False
    free.payload.allowOverride; free.payload := 0
  }
  override def fpImmRdAddr: UInt     = ports.rdAddr
  override def fpImmRdData: Bits     = ports.rdData
  override def fpImmFree: Flow[UInt] = ports.free
}
