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
  // EA auto-update (-(An)/(An)+): the LS EU computes the access addr (PREDEC: An-delta,
  // POSTINC: An) and (for an auto STORE) writes An := An ± eaDelta on its int dst —
  // generalizing stkPush to any An/size. Threaded from decode.
  val eaAuto       = m68k040.decode.EaAuto()
  val eaDelta      = UInt(3 bits)
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
  // isCondTrap: a branch-class execute-time conditional trap µop. The branch EU
  // evaluates `cond` (via `taken`); if taken drives a trapvFault (vector 7). Threaded.
  // TRAPV uses cond=9 (VS); TRAPcc uses cond=cccc. Never causes a redirect.
  val isCondTrap   = Bool()
  // Instruction-fetch access-fault extras (format-$7): faultAddr = fetch PC stacked
  // as the EA; sswInstr = 1 => program-space SSW (vs data). Threaded from decode.
  val faultAddr    = UInt(32 bits); val sswInstr = Bool()
  // CPLX-cluster (DivEu) control: CHK / DIV. divSigned=DIVS; div64=64-bit dividend
  // (Dr:Dq); divIsRem=the trailing remainder-move (DIVREM) µop. Threaded from decode.
  val divSigned    = Bool()
  val div64        = Bool()
  val divIsRem     = Bool()
  // CMP2/CHK2 bounds-compare sub-kind (DecOp.CMP2CHK2): isChk2 selects the CHK2
  // trap-on-out-of-bounds form (vs CMP2 flags-only). The A/D bit rides divSigned
  // (True = An, no .B/.W sign-extend of Rn). Threaded from decode to the DivEu.
  val isChk2       = Bool()
  // Line-E shift/rotate control (DecOp.SHIFT): shiftOp = tt (0=AS,1=LS,2=ROX,3=RO),
  // shiftDir = d (1=left). Count = useImm/imm (i=0) or psrcB=Dc (i=1). Threaded.
  val shiftOp      = Bits(2 bits)
  val shiftDir     = Bool()
  // Packed-BCD sub-kind (DecOp.BCD): False = ABCD (add), True = SBCD (subtract). The
  // ALU EU's decimal-adjust datapath keys off it. Threaded from decode to the ALU EU.
  val bcdSub       = Bool()
  // Bit op (DecOp.BITOP): tt = 00 BTST, 01 BCHG, 10 BCLR, 11 BSET. Bit number = the
  // immediate (static) or psrcB=Dn (dynamic); the tested data = psrcA. Z-only flag
  // write (the ALU EU preserves N/V/C). Threaded from decode to the ALU EU.
  val bitOp        = Bits(2 bits)
  // Bit-field op (DecOp.BITFIELD): bfOp = op[10:8] (0=BFTST..7=BFINS). The static
  // offset/width ride `imm` (imm[4:0]=offset, imm[9:5]=raw width, 0->32). Threaded
  // from decode to the ALU EU's bit-field (slow/shifter-path) datapath.
  val bfOp         = Bits(3 bits)
  // Bit-field DYNAMIC marker (DecOp.BITFIELD, Do||Dw crack): the EU takes offset/raw-
  // width from srcC[9:0] (the BFRESOLVE-packed T0) instead of `imm`. Threaded decode->EU.
  val bfDynamic    = Bool()
  // Bit-field MEMORY load-only marker (DecOp.BITFIELD, mem EA, slice 3a): the EU funnels
  // field32 from srcA=T0 (the misaligned LONG) + srcB=T1 (spill byte) using the static
  // bitOff/needHi/origOffset packed in `imm`, feeding the datapath with rotate offset=0
  // and ffoBase=origOffset. Threaded decode->EU. False = register form.
  val bfMem        = Bool()
  // Bit-field RMW store-form (DecOp.BITFIELD bfMem, slice 3b): 0=RES,1=LO4,2=LO5,3=HI5 —
  // the inverse-funnel output the ALU EU emits for a memory RMW compute µop. Threaded
  // decode->EU. Default 0 (RES / load-only).
  val bfStoreForm  = UInt(2 bits)
  // Line-4 EXT/EXTB byte-source marker (DecOp.EXT): the sign-extend source is a BYTE
  // (EXT.W / EXTB.L) rather than a word (EXT.L). Threaded from decode to the ALU EU.
  val extByte      = Bool()
  // MOVEA (MOVE with an ADDRESS-register dst): full-32 write, .W sign-extends the
  // source, no flags. The ALU EU keys off it to bypass the .B/.W partial merge.
  val isMovea      = Bool()
  // Line-5 Scc / DBcc (branch-EU condition-path µops). Scc: Dn[7:0] := cond?0xFF:0x00
  // (no branch, no flags). DBcc: conditional Dn.W decrement + PC-relative branch on
  // `!cond && (decW != -1)`. Both read NZVC (cond) + Dn (psrcA) + write Dn (pdst).
  val isScc        = Bool()
  val isDbcc       = Bool()
  // Macro-instruction boundary marker (interrupts): True for the FIRST µop of an
  // instruction (the only multi-µop case is a memSimple-source crack [load, op]).
  val firstOfInstr = Bool()
  // Brief-format indexed EA: the index reg rides psrcC/psrcCValid; these size+scale it
  // in the LS-EU AGU (indexLong => full 32 vs .W sign-extend; indexScale = *1/2/4/8).
  val indexLong  = Bool()
  val indexScale = UInt(2 bits)
  // LEA address-generate (LS): write s1Va to the int dst, no mem access. Threaded.
  val leaAddr  = Bool()
  // MOVE from/to CCR/SR (ALU). fromCcr/fromSr select the CCR/SR int result; needs-
  // Supervisor = privileged (ROB vector-8 check on the committed S bit). Threaded.
  val fromCcr  = Bool()
  val fromSr   = Bool()
  val needsSupervisor = Bool()
  // Lock-step macro-commit marker (sim whitebox only): KEEP this µop's commit as the
  // instruction's single oracle step (PEA push / mem-dest MOVE-from-CCR/SR op µop).
  val keepCommit = Bool()
  // Commit-time privileged system op (MOVE-to-SR / MOVE-USP / MOVEC): serializing
  // retire, applied by the ExceptionUnit (re-banks A7 + redirects), S=0 -> vector-8.
  // sysReadDir = read SYSTEM->Rn (the FSM writes int PRF arch-dstArch with the
  // committed value) vs write Rn->SYSTEM (the source rides psrcA, its writeback VALUE
  // captured per-ROB-entry). The MOVEC Rc id rides `imm`. Default: not a sysOp.
  val sysOp        = Bool()
  val sysKind      = m68k040.decode.SysKind()
  val sysReadDir   = Bool()
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
