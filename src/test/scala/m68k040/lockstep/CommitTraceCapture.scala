package m68k040.lockstep

import m68k040.types.CommitTrace
import spinal.core.ClockDomain
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer

/** Samples a CommitTrace port once per clock and records a CommitObservation on
  * every cycle where `fire` is high. Use inside a doSim block. */
object CommitTraceCapture {
  final class Handle(buf: ArrayBuffer[CommitObservation]) {
    def result(): Seq[CommitObservation] = buf.toVector
  }

  def start(port: CommitTrace, cd: ClockDomain): Handle = {
    val buf = ArrayBuffer.empty[CommitObservation]
    cd.onSamplings {
      if (port.fire.toBoolean) {
        buf += CommitObservation(
          pc           = port.pc.toLong,
          archRegId    = port.archRegId.toInt,
          archRegWrite = port.archRegWrite.toLong,
          archRegValid = port.archRegValid.toBoolean,
          ccr          = port.ccr.toInt,
          memAddr      = port.memAddr.toLong,
          memData      = port.memData.toLong,
          memWrite     = port.memWrite.toBoolean
        )
      }
    }
    new Handle(buf)
  }
}
