package m68k040.verif

import m68k040.types.CommitTrace
import spinal.core._

/** Minimal stand-in for the (not-yet-built) commit stage: a registered
  * passthrough of a CommitTrace stream. Lets the verification harness exercise
  * the CommitTrace port + SpinalSim capture path end-to-end before the real
  * pipeline exists. Replace with the real commit plugin's trace port later. */
case class TraceReplay() extends Component {
  val io = new Bundle {
    val in  : CommitTrace = CommitTrace()
    val out : CommitTrace = CommitTrace()
  }
  in(io.in)
  out(io.out)
  io.out := RegNext(io.in)
}
