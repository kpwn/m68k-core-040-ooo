package m68k040.types

import m68k040.M68kParams
import spinal.core._

/** The architectural unit (spec 4.5). The ROB is keyed by INSTRUCTION, never by
  * µop. Commit is the single precise retire boundary and the lock-step compare point. */
case class RobEntry(p: M68kParams) extends Bundle {
  val valid        = Bool()
  val pc           = UInt(32 bits)
  val predNextPc   = UInt(32 bits)
  val length       = UInt(3 bits)
  val simple       = Bool()
  val retireAlone  = Bool()
  val serialize    = Bool()

  // mappings for commit + physreg release (new + old), per renamed resource
  val intArchDst   = UInt(log2Up(m68k040.isa.Isa.ARCH_INT_REGS) bits)
  val intNewPdst   = UInt(p.physIntIdWidth bits)
  val intOldPdst   = UInt(p.physIntIdWidth bits)
  val intWrites    = Bool()
  val nzvcNewPdst  = UInt(p.physNzvcIdWidth bits)
  val nzvcOldPdst  = UInt(p.physNzvcIdWidth bits)
  val xNewPdst     = UInt(p.physXIdWidth bits)
  val xOldPdst     = UInt(p.physXIdWidth bits)
  val ccrWriteMask = Bits(5 bits)     // X,N,Z,V,C this instruction writes

  // store-queue slots owned by this instruction (range; two for misaligned)
  val sqPtrBase    = UInt(p.sqPtrWidth bits)
  val sqPtrCount   = UInt(2 bits)

  // completion bookkeeping (spec 4.5.1): singleUop bypasses the counter
  val singleUop      = Bool()
  val outstandingUops= UInt(3 bits)
  val complete       = Bool()

  // precise-exception state (spec 8)
  val excValid     = Bool()
  val excVector    = UInt(8 bits)
  val faultAddr    = UInt(32 bits)
  val stackFormat  = UInt(4 bits)

  val branchResolved = Bool()
  val mispredicted   = Bool()
}
