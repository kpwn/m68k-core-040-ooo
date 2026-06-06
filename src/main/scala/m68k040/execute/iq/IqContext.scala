package m68k040.execute.iq

import m68k040.rename.RenamedUop
import spinal.core._
import spinal.lib._

case class IqContext() extends Bundle {
  val uop   = RenamedUop()
  val robId = UInt(6 bits)
}

trait IssueQueueService {
  def push: Stream[Vec[IqContext]]   // length 2
  def pushSlot1Valid: Bool
  def issue: Vec[Stream[IqContext]]  // length 5 (ALU0, ALU1, branch, LS, CPLX/DivEu)
  def flushPort: Bool
  /** Dynamic-completion wakeup (variant A): the LS EU broadcasts the pdst of a
    * just-completed load; slots reading that physreg become ready. */
  def lsWakeup: Flow[UInt]
  /** Dynamic-completion wakeup for the CPLX cluster (DivEu): a multi-cycle DIV
    * broadcasts the pdst of its just-completed quotient/remainder; slots reading
    * that physreg become ready. Separate Flow from lsWakeup so a same-cycle LS load
    * + DIV completion never collide on one wakeup port. */
  def cplxWakeup: Flow[UInt]
}
