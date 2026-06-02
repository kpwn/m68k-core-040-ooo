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

    val v0 = out Bool (); val rob0 = out UInt (6 bits)
    val v1 = out Bool (); val rob1 = out UInt (6 bits)
    v0   := iq.issue(0).valid
    rob0 := iq.issue(0).payload.robId
    v1   := iq.issue(1).valid
    rob1 := iq.issue(1).payload.robId
  }
}
