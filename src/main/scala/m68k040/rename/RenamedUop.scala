package m68k040.rename

import m68k040.isa.{Cluster, Size}
import m68k040.decode.DecOp
import spinal.core._

/** Rename -> dispatch/ROB contract: DecodedUop fields + physical operands.
  * pdstOld/pNzvcOld/pXOld are the previous mappings, carried for commit-time free. */
case class RenamedUop() extends Bundle {
  val intW  = 6   // log2Up(PHYS_INT_REGS=48)
  val flagW = 4   // log2Up(PHYS_NZVC_REGS=16) = log2Up(PHYS_X_REGS=16)
  val valid        = Bool()
  val pc           = UInt(32 bits)
  val op           = DecOp()
  val cluster      = Cluster()
  val size         = Size()
  val useImm       = Bool();  val imm = Bits(32 bits)
  val isBranch     = Bool();  val cond = Bits(4 bits); val branchDisp = Bits(32 bits)
  val unimplemented= Bool()
  val dstArch = UInt(4 bits)   // architectural int dst reg (for commit RAT update + CommitTrace)
  val psrcA = UInt(intW bits); val psrcAValid = Bool()
  val psrcB = UInt(intW bits); val psrcBValid = Bool()
  val pdst  = UInt(intW bits); val pdstValid  = Bool(); val pdstOld = UInt(intW bits)
  val pNzvcSrc = UInt(flagW bits); val readsNzvc  = Bool()
  val pNzvcDst = UInt(flagW bits); val writesNzvc = Bool(); val pNzvcOld = UInt(flagW bits)
  val pXSrc    = UInt(flagW bits); val readsX     = Bool()
  val pXDst    = UInt(flagW bits); val writesX    = Bool(); val pXOld   = UInt(flagW bits)
}
