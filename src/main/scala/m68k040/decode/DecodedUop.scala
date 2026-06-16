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
      SHIFT,
      // Line-4 single-operand data-register ops (the unary family). All read the dst
      // Dn as srcA (the .B/.W partial-merge / operand source) and write Dn (except TST).
      //   CLR  : Dn(size):=0 (N=0,Z=1,V=0,C=0).
      //   NEG  : Dn := 0-Dn (NZVCX; X=C; V overflow).
      //   NEGX : Dn := 0-Dn-X (NZVCX; reads X + old Z; Z is CLEAR-ONLY).
      //   NOT  : Dn := ~Dn (NZ; V=0,C=0).
      //   TST  : flags only (NZ; V=0,C=0); no write.
      //   SWAP : Dn := {Dn[15:0],Dn[31:16]} (full-32; NZ from the 32-bit result).
      //   EXT  : sign-extend the low byte/word -> .W/.L (NZ); `extByte` selects the
      //          byte-source form (EXT.W byte->word .W; EXTB.L byte->long; EXT.L
      //          word->long when extByte=0).
      //   TAS  : N/Z from Dn[7:0] (V=0,C=0); then Dn[7]:=1 (byte partial merge).
      CLR, NEG, NEGX, NOT, TST, SWAP, EXT, TAS,
      // Extended add/subtract (register form, Dy,Dx). Like ADD/SUB but with the X
      // (extend) bit folded into the carry/borrow-in, and the 68k CLEAR-ONLY Z
      // (Z := Z_old && result==0) — exactly the NEGX rule. srcA=Dx (read+written),
      // srcB=Dy. Reuse NEGX's X-in (cmd.xIn) + old-Z (readsNzvc) machinery; no new field.
      //   ADDX : Dx := Dx + Dy + X (NZVCX; reads X + old Z; Z CLEAR-ONLY, like NEGX).
      //   SUBX : Dx := Dx - Dy - X (NZVCX; reads X + old Z; Z CLEAR-ONLY, like NEGX).
      ADDX, SUBX,
      // Packed-BCD add/subtract (ABCD/SBCD, register form, BYTE). One op with a 1-bit
      // `bcdSub` sub-kind (mirrors SHIFT/shiftDir): False=ABCD (Dx+Dy+X with decimal
      // adjust), True=SBCD (Dx-Dy-X). srcA=Dx (dst + .B-merge source), srcB=Dy, dst=Dx.
      // C/X = decimal carry/borrow (res>0x99); Z is CLEAR-ONLY (Z &= res==0, like NEGX);
      // N=res[7] and V=bit7(~rawLoSum & res) are computed to MATCH Musashi (officially
      // "undefined" but the full-CCR lock-step compares them). Routed to the ALU EU.
      BCD,
      // Bit op (BTST/BCHG/BCLR/BSET): tests bit n -> Z = complement of that bit; all
      // but BTST then set/clear/toggle it. The op carries `bitOp` (tt: 00 BTST, 01
      // BCHG, 10 BCLR, 11 BSET). Bit number = the immediate (static, useImm) or srcB=Dn
      // (dynamic). Dest width: LONG (Dn, bit mod 32) or BYTE (memory, bit mod 8) — set
      // by the assembler from the EA. Z-only flag write (preserve N/V/C; X untouched).
      BITOP,
      // PACK/UNPK register forms (68020+, no memory form this slice).
      // PACK Dy,Dx,#adj : src16=(Dy+adj)&0xffff; Dx[7:0]:=(src16[11:8]##src16[3:0]); Dx[31:8] preserved.
      // UNPK Dy,Dx,#adj : src16=Dy&0xffff; Dx[15:0]:=((src16[7:4]##4'b0##src16[3:0])+adj)&0xffff; Dx[31:16] preserved.
      // Size: BYTE (PACK, .B merge writes Dx[7:0]) / WORD (UNPK, .W merge writes Dx[15:0]).
      // adj16 rides `imm` (useImm=True); srcA=Dx (old-value .B/.W merge source),
      // srcB=Dy (the data source), dst=Dx. NO CCR effect.
      PACK, UNPK,
      // CMP2/CHK2 bounds-compare µop (020+, CPLX/DivEu). The 2-load+compare crack puts
      // the LOWER bound in srcA (T0, LS-loaded) and the UPPER bound in srcB (T1, LS-
      // loaded); the compared register Rn rides srcC (psrcC, a normal reg). The EU
      // sign-extends lower/upper from the loaded size, masks/sign-extends Rn per
      // size + the A/D bit (`divSigned` reused as adReg: True=An, no .B/.W sign-ext),
      // and writes the CCR RMW {oldN, Z, oldV, C} (readsNzvc + writesNzvc, preserving
      // N/V). For CHK2 (`isChk2`) an out-of-bounds C raises EuFault{vector 6}; CMP2
      // never traps. Z := Rn==lower||Rn==upper; C := signed(Rn<lower||Rn>upper).
      CMP2CHK2 = newElement()
}

/** Commit-time privileged-system-op kind (DecodedUop.sysOp / .sysKind). Selects how
  * the ExceptionUnit applies the op at the serializing retire. NONE for non-sysOps. */
object SysKind extends SpinalEnum {
  val NONE,
      MOVE_TO_SR,   // <ea>.W -> SR (system byte + CCR); re-banks A7 on an S flip.
      MOVE_USP,     // An <-> USP (direction in sysReadDir).
      MOVEC,        // Rn <-> Rc {VBR/USP/CACR/...} (direction in sysReadDir, Rc in imm).
      // RESET (0x4E70): privileged; asserts the external reset line. Architecturally a NOP
      // (no state change); the FSM consumes it + advances PC (serializing). S=0 -> vector 8.
      RESET,
      // STOP (0x4E72) + imm16: privileged; SR := imm16 (reuses the MOVE-to-SR SR-write +
      // banking) then HALT until an interrupt with level > the new I-mask. SR write is the
      // S_APPLY path; the halt is the ROB `stopped` state. S=0 -> vector 8.
      STOP          // (RESET=4, STOP=5 — needs the 3-bit FSM ctx widened in T0.)
      = newElement()
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
  // isCondTrap: a branch-class execute-time CONDITIONAL trap µop (isBranch + readsNzvc).
  // The branch EU evaluates the 16-condition `cond` field (via `taken`); if taken it
  // drives a trapvFault (vector 7, faultPc = nextPc). If not taken it retires as a no-op.
  // TRAPV (0x4E76) uses isCondTrap with cond=9 (VS, V=1). TRAPcc uses cond=cccc.
  // The µop NEVER causes a branch redirect regardless of `taken` (the EU suppresses it).
  val isCondTrap   = Bool()
  // ── CPLX-cluster (DivEu) control (CHK / DIV) ────────────────────────────────
  // `divSigned` = DIVS (vs DIVU) / CHK is always signed-compare. `div64` = the
  // 64-bit-dividend form (Dr:Dq); `divForm` selects the writeback/iteration width.
  // For the 64/32 + 32/32-with-remainder forms the decode CRACKS into a DIV µop
  // (quotient -> Dq) + a DIVREM µop (the latched remainder -> Dr); `divIsRem` marks
  // the trailing remainder-move µop. These default to harmless values for non-CPLX.
  val divSigned    = Bool()
  val div64        = Bool()     // 64-bit dividend (Dr:Dq) form
  val divIsRem     = Bool()     // this µop is the trailing remainder-move (DIVREM)
  // ── CMP2/CHK2 bounds-compare sub-kind (DecOp.CMP2CHK2) ───────────────────────
  // isChk2: True = CHK2 (out-of-bounds C raises EuFault{vec6}); False = CMP2 (flags
  // only, never traps). The A/D bit (whether Rn is a data or address reg, for the
  // .B/.W sign-extension rule) is carried in `divSigned` (reused: True = An, NOT
  // sign-extended for .B/.W; False = Dn, sign-extended). Default False.
  val isChk2       = Bool()
  // ── Stack-push store (BSR/JSR call: push return-PC to -(A7)) ────────────────
  // A predecrement-store µop: address = base An (psrcA) - sizeBytes; the STORE DATA
  // is the immediate (`imm` carries retPC = nextPc, NOT a displacement); and the
  // µop's single int dst = the SAME predecremented address (the new A7) — a store
  // has no data dst, so this lone int write is the A7 side-effect. Reuses one int
  // dst + the imm field (no 2nd write port / no 2nd 32-bit field). Default False.
  val stkPush      = Bool()
  // ── EA auto-update (general -(An)/(An)+, modes 4/3) ─────────────────────────
  // A mem µop whose base An gets `An := An ± eaDelta` folded in (generalizing stkPush
  // to ANY An and size). POSTINC: access addr = An (psrcA), An := An + eaDelta.
  // PREDEC: access addr = An - eaDelta (the same addr stkPush computes for A7, now any
  // delta), An := An - eaDelta. The An write rides the µop's int dst (dstReg=An) when
  // the µop produces the An value (a STORE / RMW-store with no data dst, or a dedicated
  // An-update mem µop); a LOAD that ALSO produces a value writes the value to its dst
  // and the An update is a SEPARATE crack µop (the load can write only one int reg).
  // eaAuto is carried on BOTH the load AND the store of a predec RMW so they compute the
  // SAME decremented address; only the An-writer carries the int dst. NONE/0 = no update.
  val eaAuto       = EaAuto()
  val eaDelta      = UInt(3 bits)
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
  // ── Packed-BCD add/subtract sub-kind (DecOp.BCD) ─────────────────────────────
  // bcdSub: False = ABCD (Dx := BCD(Dx + Dy + X)); True = SBCD (Dx := BCD(Dx - Dy - X)).
  // BYTE only. The ALU EU runs the decimal-adjust datapath + the BCD flag merge (C/X =
  // decimal carry/borrow, clear-only Z, Musashi-exact N/V). Default False.
  val bcdSub       = Bool()
  // ── Bit op (BTST/BCHG/BCLR/BSET): tt = 00 BTST, 01 BCHG, 10 BCLR, 11 BSET. ──────
  // The bit number is the immediate (static form, useImm) or srcB=Dn (dynamic). Dest
  // width: LONG (Dn, bit mod 32) or BYTE (memory, bit mod 8) — set by the assembler
  // from the EA. The ALU EU runs the bit-op datapath + a Z-only flag write. Default 0.
  val bitOp        = Bits(2 bits)
  // ── Line-4 EXT/EXTB source-width marker (DecOp.EXT) ──────────────────────────
  // EXT sign-extends the low byte/word of Dn. `extByte` = the source is a BYTE
  // (Dn[7:0]) rather than a word (Dn[15:0]): EXT.W (byte->word, size WORD, extByte)
  // + EXTB.L (byte->long, size LONG, extByte). EXT.L (word->long, size LONG) leaves
  // extByte=False. Default False (every non-EXT op). The destination width is `size`.
  val extByte      = Bool()
  // MOVEA (MOVE / MOVEQ-class with an ADDRESS-register destination): An is ALWAYS
  // written full-32 and sets NO flags; the .W form SIGN-EXTENDS the 16-bit source to
  // 32 bits. The ALU EU keys off this to bypass the .B/.W partial-register merge
  // (which is for DATA-reg destinations only) and to sign-extend a .W MOVEA source.
  // Default False (every other op, incl. MOVE-to-Dn, leaves it clear).
  val isMovea      = Bool()
  // ── Line-5 Scc / DBcc (branch-EU condition-path µops) ───────────────────────
  // Scc (`0101 cccc 11 000rrr`): set Dn[7:0] := cond(cccc) ? 0xFF : 0x00, preserve the
  // upper 24 bits, NO flags. A branch-class µop (isBranch, readsNzvc, cond=cccc) that
  // reads its destination Dn (psrcA, the merge source) and writes it (pdst); it NEVER
  // redirects (not a control transfer). Default False.
  val isScc        = Bool()
  // DBcc (`0101 cccc 11001rrr` + disp16): decrement-and-branch. If cond FALSE -> Dn.W
  // -= 1 (partial, preserve Dn[31:16]); branch to pc+2+disp if Dn.W (after dec) != -1.
  // If cond TRUE -> Dn unchanged, fall through. A branch-class µop (isBranch, readsNzvc,
  // cond=cccc) reading Dn (psrcA) + writing Dn (pdst, the merged decrement-or-unchanged)
  // + a PC-relative redirect gated on `!cond && (decW != -1)`. Default False.
  val isDbcc       = Bool()
  // Macro-instruction boundary marker: True for the FIRST µop of an instruction.
  // The cracker emits 1 or 2 µops/instruction; the only 2-µop case is a memSimple
  // source crack -> [load, op] where the LOAD is first. An interrupt may be taken
  // only when the ROB head is a first µop (never mid-cracked-instruction). Default
  // True (every single-µop instruction is its own first µop).
  val firstOfInstr = Bool()
  // ── Brief-format indexed EA (the AGU index term) ────────────────────────────
  // For a mem µop whose address is an indexed EA, the index register rides srcCReg/
  // srcCValid (psrcC after rename), and these two fields tell the LS-EU AGU how to
  // size+scale it: indexLong => use the full 32-bit Xn (else sign-extend Xn[15:0]);
  // indexScale = the 2-bit scale exponent (0=*1,1=*2,2=*4,3=*8). EA = base + disp +
  // (Xn sized) << indexScale. NONE/0 for every non-indexed µop (srcCValid gates it).
  val indexLong  = Bool()
  val indexScale = UInt(2 bits)
  // ── LEA address-generate (DecOp.MOVE, LS cluster) ───────────────────────────
  // Compute the control-EA ADDRESS (base + disp + Xn*scale, via the LS-EU AGU) and
  // write it to the int dst — NO memory access, NO translate (so it never faults).
  // The LS EU completes it in IDLE with s1Va as the result (reusing the stkPush
  // address-writeback precedent). Default False.
  val leaAddr  = Bool()
  // ── MOVE from/to SR/CCR (ALU cluster) ───────────────────────────────────────
  // fromCcr: the int result = the CCR byte {X,N,Z,V,C} zero-extended (.W). fromSr:
  // the int result = the 16-bit SR = {srSysIn, CCR byte} zero-extended (.W). Both
  // READ NZVC+X (the toCcr read ports). needsSupervisor: a privileged op (MOVE-from-
  // SR) — the ROB converts the head to a faulted vector-8 entry at retire if the
  // committed S bit is 0. Default False (every other op).
  val fromCcr  = Bool()
  val fromSr   = Bool()
  val needsSupervisor = Bool()
  // ── Lock-step macro-commit marker (sim whitebox only; no hardware effect) ────
  // Forces the lock-step whitebox to KEEP this µop's commit as the macro instruction's
  // single oracle step, even though it writes only a TEMP / is an otherwise-dropped crack
  // µop (stkPush / rmwStore). Used by ops whose every µop would otherwise be dropped so
  // the instruction vanishes from the commit stream: PEA (push T0 -> -(A7), the kept A7-
  // updating commit) and the mem-dest MOVE-from-CCR/SR op µop (-> T1, the kept step; its
  // trailing store is dropped). The EUs OR it into wbObs.keepCommit. Default False.
  val keepCommit = Bool()
  // ── Commit-time PRIVILEGED SYSTEM ops (MOVE-to-SR / MOVE-USP / MOVEC) ─────────
  // These ops write/read COMMITTED architectural system state (srSys/usp/isp/msp/vbr/
  // cacr) owned by the commit-side ExceptionUnit/SystemState — they CANNOT execute
  // out-of-order. `sysOp` marks such a µop: the ROB retires it ALONE (serializing,
  // like RTE), the ExceptionUnit applies the effect at retire (re-banking A7 +
  // redirecting younger work), and a committed S=0 converts it into a vector-8
  // privilege-violation trap (the check is at COMMIT because S is committed state).
  //   sysKind  : which system op (SysKind enum below).
  //   sysReadDir: read SYSTEM-reg -> Rn (True) vs write Rn -> SYSTEM-reg (False).
  // For a WRITE the source register rides the normal srcA (read in the OoO datapath,
  // its writeback VALUE captured per-ROB-entry like nzvcValStore -> fed to the FSM).
  // For a READ the destination Rn rides the normal dstReg/dstValid (the FSM writes
  // the int PRF arch-Rn with the committed system value). The MOVEC control-reg id
  // (12-bit Rc) rides `imm` (no value source competes for it on a MOVEC). Default:
  // not a sysOp. (Reuses srcA/dstReg/imm to keep the rename/dispatch width minimal.)
  val sysOp        = Bool()
  val sysKind      = SysKind()
  val sysReadDir   = Bool()
}
