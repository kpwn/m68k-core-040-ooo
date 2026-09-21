package m68k040.top

import m68k040.services.CommitTraceService
import m68k040.types.CommitTrace
import m68k040.execute.iq.IssueQueueService
import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/** Synthesis-only anchor: routes the CommitTraceService (plain wires) out to
  * top-level IO so the retire path — and everything feeding it — is not
  * dead-code-eliminated by synthesis. Lets an OOC FMax run see the real
  * frontend→rename→dispatch→ROB→commit pipeline.
  *
  * The now-passive ROB's two `completion` ports are `slave(Flow(...))`, so they
  * already surface as top-level inputs — no tie-off needed. But when an
  * IssueQueue is present (the dispatch path now pushes to it), its directionless
  * issue/flush wires need driving; anchor them from/to registered top IO so the
  * dispatch→IQ-push path elaborates and the age-select output isn't trimmed. */
class SynthProbePlugin extends FiberPlugin {
  val logic = during build new Area {
    val ct = host[CommitTraceService]
    val traceOut = out(Vec.fill(ct.trace.length)(CommitTrace()))
    val fireOut  = out(Vec.fill(ct.trace.length)(Bool()))
    for (k <- ct.trace.indices) {
      traceOut(k) := RegNext(ct.trace(k))
      fireOut(k)  := RegNext(ct.traceFire(k)) init False
    }

    // If an IssueQueue is present, anchor its issue/flush so dispatch's push has
    // a complete handshake and the queue's age-select output isn't trimmed.
    host.get[IssueQueueService].foreach { iq =>
      iq.flushPort      := RegNext(in Bool ()) init False
      iq.issue(0).ready := RegNext(in Bool ()) init False
      iq.issue(1).ready := RegNext(in Bool ()) init False
      iq.issue(2).ready := RegNext(in Bool ()) init False
      iq.issue(3).ready := RegNext(in Bool ()) init False
      iq.issue(4).ready := RegNext(in Bool ()) init False
      // Keep both sides of the fast-accept candidate masks live. These are
      // independent registered probe inputs because this harness has no ALU EU
      // from which to derive the real one-cycle-ahead reservation forecast.
      iq.aluFastAcceptNext(0) := RegNext(in Bool ()) init False
      iq.aluFastAcceptNext(1) := RegNext(in Bool ()) init False
      val iqIssue0V = out(RegNext(iq.issue(0).valid) init False)
      val iqIssue0R = out(RegNext(iq.issue(0).payload.robId))
      val iqIssue1V = out(RegNext(iq.issue(1).valid) init False)
      val iqIssue1R = out(RegNext(iq.issue(1).payload.robId))
      val iqIssue2V = out(RegNext(iq.issue(2).valid) init False)
      val iqIssue2R = out(RegNext(iq.issue(2).payload.robId))
      val iqIssue3V = out(RegNext(iq.issue(3).valid) init False)
      val iqIssue3R = out(RegNext(iq.issue(3).payload.robId))
      val iqIssue4V = out(RegNext(iq.issue(4).valid) init False)
      val iqIssue4R = out(RegNext(iq.issue(4).payload.robId))
    }
  }
}
