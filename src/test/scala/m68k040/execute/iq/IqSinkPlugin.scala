package m68k040.execute.iq

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only sink: drives issue.ready and exposes issue valid/robId as IO. */
class IqSinkPlugin extends FiberPlugin {
  val logic = during build new Area {
    val iq = host[IssueQueueService]

    val ready0 = in Bool ()
    val ready1 = in Bool ()
    iq.issue(0).ready := ready0
    iq.issue(1).ready := ready1
    // Port 2 (branch) is unused in these ALU-only tests; keep it ready so its
    // (always-idle, since the source pushes only non-branch uops) stream is
    // fully driven and never structurally back-pressures.
    iq.issue(2).ready := True
    // Port 3 (LS): driven-ready input; expose its valid/robId/pdst for the LS test.
    val ready3 = in Bool (); iq.issue(3).ready := ready3
    // Port 4 (CPLX/DivEu): unused in these tests; keep ready so it is fully driven.
    iq.issue(4).ready := True

    val v0 = out Bool (); val rob0 = out UInt (m68k040.Global.ROB_ID_W_DEFAULT bits)
    val v1 = out Bool (); val rob1 = out UInt (m68k040.Global.ROB_ID_W_DEFAULT bits)
    v0   := iq.issue(0).valid
    rob0 := iq.issue(0).payload.robId
    v1   := iq.issue(1).valid
    rob1 := iq.issue(1).payload.robId

    val v3 = out Bool (); val rob3 = out UInt (m68k040.Global.ROB_ID_W_DEFAULT bits); val pdst3 = out UInt (6 bits)
    v3    := iq.issue(3).valid
    rob3  := iq.issue(3).payload.robId
    pdst3 := iq.issue(3).payload.uop.pdst
  }
}
