package m68k040.decode

import m68k040.isa.{Cluster, Size, MemOp}
import spinal.core._

object DecOp extends SpinalEnum {
  val MOVE, ADD, SUB, AND, OR, EOR, CMP, BRANCH, ILLEGAL,
      // CPLX-cluster (DivEu) ops: bound-check trap + integer divide. DIV carries
      // {signed, the .W/.L/64-form, quotient-only-vs-remainder} via size + the
      // divForm/divSigned/div64 µop fields below.
      CHK, DIV, DIVREM,
      // CPLX-cluster integer multiply (MULU/MULS). MUL writes the product low word
      // (Dn[31:0] for .W/.L32, Dl[31:0] for .L64); MULHI is the trailing crack µop
      // for the .L64 form (writes the LATCHED high product Dh from MulCore). Sign +
      // form carried via size + divSigned (reused for MULS) + div64 (.L64 marker).
      MUL, MULHI,
      // Line-E register-form shift/rotate (ASL/ASR/LSL/LSR/ROXL/ROXR/ROL/ROR). The
      // op carries `shiftOp` (tt: 0=AS,1=LS,2=ROX,3=RO) + `shiftDir` (1=left) + size;
      // the count is the immediate (useImm/imm, the `i=0` form) or the 2nd data-reg
      // source Dc (srcB, the `i=1` form). Routed to the ALU EU's barrel shifter.
      SHIFT = newElement()
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
  // Third source: ONLY the DIVU.L/DIVS.L 64/32 form (the dividend HIGH word Dr); all
  // other µops leave srcCValid=False. Threaded through rename to a 3rd physical source.
  val srcCReg      = UInt(5 bits); val srcCValid = Bool()
  val dstReg       = UInt(5 bits); val dstValid  = Bool()
  val useImm       = Bool();       val imm       = Bits(32 bits)
  val readsNzvc    = Bool();       val readsX    = Bool()
  val writesNzvc   = Bool();       val writesX   = Bool()
  val isBranch     = Bool()
  // Indirect / computed-target branch (JSR/JMP/RTS/RTR): when set, the branch EU
  // forms its target from a SOURCE OPERAND (`psrcA + imm`) instead of `pc+2+disp`,
  // and ALWAYS redirects (unconditional). For JSR/JMP psrcA is the EA base An (imm =
  // displacement / folded absolute / folded PC) — a tiny AGU in the branch EU; for
  // RTS/RTR psrcA is the popped target value (imm = 0). Default False (a plain Bcc/
  // BRA/BSR forms pc+2+disp as before).
  val ibranch      = Bool()
  // Stack-pop postincrement folded into the trailing ibranch (RTS/RTR): when the
  // ibranch has an int dst (dstReg = A7), the branch EU writes A7 := psrcB + anInc
  // (psrcB = the pre-pop A7). anInc = 4 (RTS) / 6 (RTR: word CCR + long PC). 0 = no
  // postinc (JSR/JMP — no An write). 3 bits hold 0/4/6. Default 0.
  val anInc        = UInt(3 bits)
  val cond         = Bits(4 bits)
  val branchDisp   = Bits(32 bits)
  val unimplemented= Bool()
  // Precise-fault capture (exception slice 1): `faulted` marks this µop as raising
  // a synchronous fault at retire; `faultVector` is the m68k exception vector
  // NUMBER (4 = illegal instruction, 8 = privilege violation). Default: no fault.
  val faulted      = Bool()
  val faultVector  = UInt(8 bits)
  // Which PC the exception frame stacks for this fault. Most faults (illegal,
  // privilege, I-fetch) stack the FAULTING instruction's PC (= `pc`, the default).
  // TRAP/TRAPV stack the PC of the NEXT instruction (not restartable): the assembler
  // sets this flag and the ROB stacks `nextPc` instead of `pc` (a 1-bit select keeps
  // the rename/dispatch pipeline narrow — no extra 32-bit field).
  val faultUsesNextPc = Bool()
  // Access-fault (vector 2) extras for the format-$7 frame, used for an
  // INSTRUCTION-FETCH fault (the I-cache raised DecodePacket.fault). `faultAddr` is
  // the faulting fetch PC (the EA stacked in the $7 frame); `sswInstr` set => the
  // fault was an instruction fetch (the exception FSM stacks a program-space SSW
  // instead of data-space). Both default to 0/False (data faults / no fault).
  val faultAddr    = UInt(32 bits)
  val sswInstr     = Bool()
  // RTE (return-from-exception): a serializing exception-return µop. Default False.
  val isRte        = Bool()
  // TRAPV (0x4E76): an execute-time CONDITIONAL trap. A branch-class trap-check µop
  // (isBranch + readsNzvc) marked isTrapv; the branch EU reads NZVC and, if V=1,
  // drives a trapvFault (vector 7, faultPc = nextPc). If V=0 it retires as a no-op.
  val isTrapv      = Bool()
  // ── CPLX-cluster (DivEu) control (CHK / DIV) ────────────────────────────────
  // `divSigned` = DIVS (vs DIVU) / CHK is always signed-compare. `div64` = the
  // 64-bit-dividend form (Dr:Dq); `divForm` selects the writeback/iteration width.
  // For the 64/32 + 32/32-with-remainder forms the decode CRACKS into a DIV µop
  // (quotient -> Dq) + a DIVREM µop (the latched remainder -> Dr); `divIsRem` marks
  // the trailing remainder-move µop. These default to harmless values for non-CPLX.
  val divSigned    = Bool()
  val div64        = Bool()     // 64-bit dividend (Dr:Dq) form
  val divIsRem     = Bool()     // this µop is the trailing remainder-move (DIVREM)
  // ── Stack-push store (BSR/JSR call: push return-PC to -(A7)) ────────────────
  // A predecrement-store µop: address = base An (psrcA) - sizeBytes; the STORE DATA
  // is the immediate (`imm` carries retPC = nextPc, NOT a displacement); and the
  // µop's single int dst = the SAME predecremented address (the new A7) — a store
  // has no data dst, so this lone int write is the A7 side-effect. Reuses one int
  // dst + the imm field (no 2nd write port / no 2nd 32-bit field). Default False.
  val stkPush      = Bool()
  // ── RTR CCR-restore load (pop the saved CCR word) ──────────────────────────
  // A LOAD µop that, instead of writing an int reg, RESTORES the CCR from the loaded
  // word's low byte: NZVC := data[3:0], X := data[4]. RTR restores ONLY the CCR (SR
  // low byte), never the system byte. The LS EU writes the renamed NZVC + X PRFs.
  // Default False. (writesNzvc/writesX on this µop select the flag dests.)
  val ccrRestore   = Bool()
  // ── ANDI/ORI/EORI #imm,CCR (the non-privileged to-CCR forms) ────────────────
  // An ALU-cluster µop that READS the current CCR {X,N,Z,V,C} and WRITES it back:
  // ccr5' = ccr5 <op> imm[4:0], op = AND/OR/EOR (in `op`), imm[4:0] in `imm`. It
  // reads NZVC (pNzvcSrc) + X (pXSrc) and writes NZVC (pNzvcDst) + X (pXDst); no int
  // dst. The ALU EU assembles ccr5, applies the logical op, and splits the result
  // back into NZVC/X. Default False (every other op leaves it clear). CCR only — the
  // privileged system-byte (to SR) forms are deferred.
  val toCcr        = Bool()
  // ── Line-E shift/rotate control (DecOp.SHIFT) ───────────────────────────────
  // shiftOp = tt (0=ASL/ASR, 1=LSL/LSR, 2=ROXL/ROXR, 3=ROL/ROR); shiftDir = d
  // (1=left). The count is useImm/imm (i=0, ccc 1-8 with 0->8) or srcB=Dc (i=1).
  // Default 0/False (non-shift µops). Threaded through rename to the ALU EU.
  val shiftOp      = Bits(2 bits)
  val shiftDir     = Bool()
  // MOVEA (MOVE / MOVEQ-class with an ADDRESS-register destination): An is ALWAYS
  // written full-32 and sets NO flags; the .W form SIGN-EXTENDS the 16-bit source to
  // 32 bits. The ALU EU keys off this to bypass the .B/.W partial-register merge
  // (which is for DATA-reg destinations only) and to sign-extend a .W MOVEA source.
  // Default False (every other op, incl. MOVE-to-Dn, leaves it clear).
  val isMovea      = Bool()
  // Macro-instruction boundary marker: True for the FIRST µop of an instruction.
  // The cracker emits 1 or 2 µops/instruction; the only 2-µop case is a memSimple
  // source crack -> [load, op] where the LOAD is first. An interrupt may be taken
  // only when the ROB head is a first µop (never mid-cracked-instruction). Default
  // True (every single-µop instruction is its own first µop).
  val firstOfInstr = Bool()
}
