package m68k040.frontend

import spinal.core._
import spinal.lib.Flow

/** Frontend-local registered boundary for Decode's rare complex-packet resume action.
  *
  * The source is a one-cycle pulse while FetchAlign is stalled, so one payload register
  * is sufficient. `cancel` has capture and visible-output priority: a backend flush can
  * never revive a stale complex fall-through target.
  */
object ComplexResumeActionPipe {
  def apply(source: Flow[UInt], cancel: Bool): Flow[UInt] = {
    val validReg  = Reg(Bool()) init False
    val targetReg = Reg(UInt(32 bits)) init 0

    when(cancel) {
      validReg := False
    } otherwise {
      validReg := source.valid
      when(source.valid) {
        targetReg := source.payload
      }
    }

    val action = Flow(UInt(32 bits))
    action.valid   := validReg && !cancel
    action.payload := targetReg
    action
  }
}
