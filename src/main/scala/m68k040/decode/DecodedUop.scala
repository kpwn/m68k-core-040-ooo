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
  // Precise-fault capture (exception slice 1): `faulted` marks this µop as raising
  // a synchronous fault at retire; `faultVector` is the m68k exception vector
  // NUMBER (4 = illegal instruction, 8 = privilege violation). Default: no fault.
  val faulted      = Bool()
  val faultVector  = UInt(8 bits)
  // The PC value STACKED in the exception frame for this fault. For most faults
  // (illegal, privilege, I-fetch) this is the FAULTING instruction's PC (= `pc`,
  // the default the assembler sets). TRAP/TRAPV stack the PC of the NEXT
  // instruction (they are not restartable) -> the assembler sets faultPc = nextPc.
  val faultPc      = UInt(32 bits)
  // Access-fault (vector 2) extras for the format-$7 frame, used for an
  // INSTRUCTION-FETCH fault (the I-cache raised DecodePacket.fault). `faultAddr` is
  // the faulting fetch PC (the EA stacked in the $7 frame); `sswInstr` set => the
  // fault was an instruction fetch (the exception FSM stacks a program-space SSW
  // instead of data-space). Both default to 0/False (data faults / no fault).
  val faultAddr    = UInt(32 bits)
  val sswInstr     = Bool()
  // RTE (return-from-exception): a serializing exception-return µop. Default False.
  val isRte        = Bool()
}
