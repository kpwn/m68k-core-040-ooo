package m68k040.top

import m68k040.services.CommitTraceService
import m68k040.types.CommitTrace
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** Synthesis-only anchor: routes the CommitTraceService (plain wires) out to
  * top-level IO so the retire path — and everything feeding it — is not
  * dead-code-eliminated by synthesis. Lets an OOC FMax run see the real
  * frontend→rename→ROB→commit pipeline. */
class SynthProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val ct = host[CommitTraceService]
    val traceOut = out(Vec.fill(2)(CommitTrace()))
    val fireOut  = out(Vec.fill(2)(Bool()))
    for (k <- 0 until 2) {
      traceOut(k) := RegNext(ct.trace(k))
      fireOut(k)  := RegNext(ct.traceFire(k)) init False
    }
  }
}
