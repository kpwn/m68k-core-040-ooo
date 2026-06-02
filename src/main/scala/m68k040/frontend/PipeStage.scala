package m68k040.frontend

import spinal.core._
import spinal.lib._

/** A 1-deep registered Stream stage (m2s: registers valid + payload forward) with
  * a `flush` that clears the held entry. Full throughput when downstream is ready.
  * `flush` is tied False until 3d wires it to the mispredict-flush broadcast (then
  * an in-flight wrong-path group is discarded — see the frontend-pipeline spec). */
object PipeStage {
  def apply[T <: Data](in: Stream[T], flush: Bool): Stream[T] = {
    val out = Stream(in.payloadType())
    val valid = RegInit(False)
    val data  = Reg(in.payloadType())
    // accept a new entry when the slot is empty or draining this cycle
    val slotFree = !valid || out.ready
    when(slotFree) { valid := in.valid; data := in.payload }
    when(flush)    { valid := False }
    in.ready    := slotFree
    out.valid   := valid
    out.payload := data
    out
  }
}
