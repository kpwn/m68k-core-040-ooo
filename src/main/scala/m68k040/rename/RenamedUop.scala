package m68k040.rename

import m68k040.isa.{Cluster, Size, MemOp}
import m68k040.decode.DecOp
import spinal.core._

/** Rename -> dispatch/ROB contract: DecodedUop fields + physical operands.
  * pdstOld/pNzvcOld/pXOld are the previous mappings, carried for commit-time free. */
case class RenamedUop() extends Bundle {
  val intW  = 6   // log2Up(PHYS_INT_REGS=48)
  val flagW = 4   // log2Up(PHYS_NZVC_REGS=16) = log2Up(PHYS_X_REGS=16)
  val valid        = Bool()
  val pc           = UInt(32 bits)
  val nextPc       = UInt(32 bits)   // POST-instruction PC (pc + length); used for commit pc
  val op           = DecOp()
  val cluster      = Cluster()
  val size         = Size()
  val memOp        = MemOp()
  val useImm       = Bool();  val imm = Bits(32 bits)
  val isBranch     = Bool();  val cond = Bits(4 bits); val branchDisp = Bits(32 bits)
  // Indirect / computed-target branch (JSR/JMP/RTS/RTR): target = psrcA + imm,
  // unconditional redirect. Threaded from decode.
  val ibranch      = Bool()
  // Stack-pop postincrement folded into the trailing ibranch (RTS=4/RTR=6): when the
  // ibranch has an int dst, the branch EU writes dst := psrcB + anInc. Threaded.
  val anInc        = UInt(3 bits)
  // Stack-push store (BSR/JSR): addr = psrcA - sizeBytes; data = imm; int dst (A7)
  // := the predecremented address. Threaded from decode.
  val stkPush      = Bool()
  // RTR CCR-restore load: NZVC := loaded[3:0], X := loaded[4] (no int dst). Threaded.
  val ccrRestore   = Bool()
  // ANDI/ORI/EORI #imm,CCR: ALU-cluster CCR read-modify-write (ccr5' = ccr5 op
  // imm[4:0]); reads+writes NZVC+X, no int dst. Threaded from decode.
  val toCcr        = Bool()
  val unimplemented= Bool()
  // Precise-fault capture (exception slice 1): `faulted` + `faultVector` (4=illegal,
  // 8=privilege) threaded from decode; `isRte` marks a return-from-exception µop.
  val faulted      = Bool();  val faultVector = UInt(8 bits); val isRte = Bool()
  // Stack `nextPc` (not `pc`) in the exception frame — set for TRAP/TRAPV (not
  // restartable). 1-bit; the ROB selects pc vs nextPc at alloc. Threaded from decode.
  val faultUsesNextPc = Bool()
  // TRAPV: an execute-time conditional trap-check µop (branch-class). The branch EU
  // drives a trapvFault (vector 7) only if V=1 at execute. Threaded from decode.
  val isTrapv      = Bool()
  // Instruction-fetch access-fault extras (format-$7): faultAddr = fetch PC stacked
  // as the EA; sswInstr = 1 => program-space SSW (vs data). Threaded from decode.
  val faultAddr    = UInt(32 bits); val sswInstr = Bool()
  // CPLX-cluster (DivEu) control: CHK / DIV. divSigned=DIVS; div64=64-bit dividend
  // (Dr:Dq); divIsRem=the trailing remainder-move (DIVREM) µop. Threaded from decode.
  val divSigned    = Bool()
  val div64        = Bool()
  val divIsRem     = Bool()
  // Line-E shift/rotate control (DecOp.SHIFT): shiftOp = tt (0=AS,1=LS,2=ROX,3=RO),
  // shiftDir = d (1=left). Count = useImm/imm (i=0) or psrcB=Dc (i=1). Threaded.
  val shiftOp      = Bits(2 bits)
  val shiftDir     = Bool()
  // Macro-instruction boundary marker (interrupts): True for the FIRST µop of an
  // instruction (the only multi-µop case is a memSimple-source crack [load, op]).
  val firstOfInstr = Bool()
  val dstArch = UInt(5 bits)   // architectural int dst reg 0..17 (incl T0/T1); for commit RAT + CommitTrace
  val psrcA = UInt(intW bits); val psrcAValid = Bool()
  val psrcB = UInt(intW bits); val psrcBValid = Bool()
  // Third physical source: ONLY the DIVU.L/DIVS.L 64/32 form (dividend high word Dr).
  // All other µops leave psrcCValid=False.
  val psrcC = UInt(intW bits); val psrcCValid = Bool()
  val pdst  = UInt(intW bits); val pdstValid  = Bool(); val pdstOld = UInt(intW bits)
  val pNzvcSrc = UInt(flagW bits); val readsNzvc  = Bool()
  val pNzvcDst = UInt(flagW bits); val writesNzvc = Bool(); val pNzvcOld = UInt(flagW bits)
  val pXSrc    = UInt(flagW bits); val readsX     = Bool()
  val pXDst    = UInt(flagW bits); val writesX    = Bool(); val pXOld   = UInt(flagW bits)
}
