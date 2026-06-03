package m68k040.decode

import m68k040.isa.{Cluster, Size, MemOp}
import spinal.core._

object DecOp extends SpinalEnum {
  val MOVE, ADD, SUB, AND, OR, CMP, BRANCH, ILLEGAL = newElement()
}

/** Pre-rename µop: the decode→rename contract. Architectural operands
  * (D0-7 = 0..7, A0-7 = 8..15, T0/T1 temps = 16/17). Reg ids are 5 bits so the
  * cracker's temp targets fit. Rename maps these to physical MicroOp fields. */
case class DecodedUop() extends Bundle {
  val valid        = Bool()
  val pc           = UInt(32 bits)
  val nextPc       = UInt(32 bits)   // POST-instruction PC = pc + length (all µops of an instr share it)
  val op           = DecOp()
  val cluster      = Cluster()
  val size         = Size()
  val memOp        = MemOp()
  val srcAReg      = UInt(5 bits); val srcAValid = Bool()
  val srcBReg      = UInt(5 bits); val srcBValid = Bool()
  val dstReg       = UInt(5 bits); val dstValid  = Bool()
  val useImm       = Bool();       val imm       = Bits(32 bits)
  val readsNzvc    = Bool();       val readsX    = Bool()
  val writesNzvc   = Bool();       val writesX   = Bool()
  val isBranch     = Bool()
  val cond         = Bits(4 bits)
  val branchDisp   = Bits(32 bits)
  val unimplemented= Bool()
}
