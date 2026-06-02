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
  def issue: Vec[Stream[IqContext]]  // length 2 (two ALU ports)
  def flushPort: Bool
}
