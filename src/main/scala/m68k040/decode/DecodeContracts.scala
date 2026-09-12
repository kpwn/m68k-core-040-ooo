package m68k040.decode

import m68k040.isa.{Cluster, Size}
import spinal.core._

/** Where a backend operand slot sources its value. EA-agnostic: `EASRC`/`EADST`
  * mean "the operand encoded in the EA field" — register vs memory is the
  * EaDecoder's call. `REGFIELD` = a register named by a fixed opword field (Dn,
  * bits 11-9). `IMMQ` = the MOVEQ 8-bit signed immediate. */
object OperandKind extends SpinalEnum {
  // IMMEXT = a full-width immediate from the trailing EXTENSION word(s) (line-0
  // immediates ADDI/SUBI/ANDI/ORI/EORI/CMPI). Sized by the op size: .B/.W = 1 word
  // (low byte / word), .L = 2 words. Distinct from IMMQ (the MOVEQ 8-bit signed
  // opword immediate) and from the EA #imm (mode7/reg4, resolved by the EaDecoder).
  // IMMQ3 = the ADDQ/SUBQ 3-bit "quick" immediate (opword bits 11:9, value 1-8 with
  // ddd==0 meaning 8). Distinct from IMMQ (the MOVEQ 8-bit signed opword immediate).
  val NONE, REGFIELD, EASRC, EADST, IMMQ, IMMEXT, IMMQ3 = newElement()
}

case class OperandSrc() extends Bundle {
  val kind   = OperandKind()
  val isAddr = Bool()        // REGFIELD only: An (true) vs Dn (false)
  def setNone(): Unit = { kind := OperandKind.NONE; isAddr := False }
}

/** What an EA field decodes to (opcode-agnostic). This slice only produces the
  * register/immediate classes; the memory classes are reserved for later slices. */
object EaClass extends SpinalEnum {
  // MEMINDIRECT = a 68020+ full-format MEMORY-INDIRECT EA (`([bd,An,Xn],od)` pre-index
  // or `([bd,An],Xn,od)` post-index): a mid-EA pointer LOAD followed by the host op on
  // the loaded pointer + od (+ index for post). Routed to the microcode-v2 engine (the
  // single-pass no-memory-indirect full-format I/IS=000 form stays MEMSIMPLE).
  val DATAREG, ADDRREG, IMM, MEMSIMPLE, MEMINDIRECT, MEMCOMPLEX, ILLEGAL = newElement()
}

/** EA auto-update side-effect (predec/postinc): NONE for every non-auto EA;
  * POSTINC for `(An)+` (EA = An, then An += delta); PREDEC for `-(An)` (An -= delta
  * first, EA = the decremented An). The assembler folds the `An := An ± delta`
  * write-back into the load/store/RMW crack; the LS EU computes the access address
  * (An / An-delta) + the An write. Generalizes the call/return A7 stkPush. */
object EaAuto extends SpinalEnum {
  val NONE, POSTINC, PREDEC = newElement()
}

case class EaSpec() extends Bundle {
  val klass = EaClass()
  val reg   = UInt(5 bits)    // full reg id: Dn=0..7, An=8..15, temps 16/17 (valid for DATAREG/ADDRREG)
  val imm   = Bits(32 bits)   // valid for IMM
  // memSimple base/disp (valid for MEMSIMPLE; produced by the memory-cracking slice).
  val baseValid = Bool()      // a base An register read is needed (false for abs / PC-rel)
  val base      = UInt(5 bits)// base An reg id (when baseValid)
  val disp      = Bits(32 bits)// displacement / absolute address (final disp once pcRel folded)
  val pcRel     = Bool()      // (d16,PC): the assembler folds pc into `disp` (base=0)
  // EA auto-update (predec/postinc, modes 4/3). NONE for every other EA. autoDelta is
  // the An adjust in bytes (1/2/4, or 2 for a BYTE access on A7 to keep SP even); valid
  // only when autoMode != NONE. The base An reg id is `base` (= 8+reg) with baseValid.
  val autoMode  = EaAuto()
  val autoDelta = UInt(3 bits)
  // Brief-format INDEXED EA (modes 6 / 7-3): EA = base + sext(d8) + (Xn sized) << scale.
  // indexValid => an index register read is needed (Xn). indexReg = the full reg id
  // (Dn=0..7 / An=8..15). indexLong => .L index (full 32); False => .W (sign-extend low
  // 16). indexScale = the 2-bit scale exponent (0=*1,1=*2,2=*4,3=*8). The d8 displacement
  // rides `disp` (base+disp fold as usual); PC-rel (mode 7-3) folds pc+2+d8 (base=0).
  // NONE for every non-indexed EA. Carried on MEMSIMPLE alongside base/disp/autoMode.
  val indexValid = Bool()
  val indexReg   = UInt(5 bits)
  val indexLong  = Bool()
  val indexScale = UInt(2 bits)
  // ── 68020+ full-format MEMORY-INDIRECT (EaClass.MEMINDIRECT) ─────────────────
  // The pointer load's address = base'(per baseValid/pcRel) + bd(`disp`) + index'(for
  // pre-index only). The OUTER displacement `od` is added to the LOADED pointer (the
  // host op's `(T+od)` base). `memPost` selects post-index (`([bd,An],Xn,od)`: pointer
  // loaded at base+bd, then +Xn+od) vs pre-index (`([bd,An,Xn],od)`: pointer at
  // base+bd+Xn, then +od). The index fields (indexReg/Long/Scale/indexValid) are reused;
  // for MEMINDIRECT they describe Xn (IS-suppressible: indexValid=!IS). All inert for the
  // non-MEMINDIRECT classes. (No-memory-indirect I/IS=000 stays MEMSIMPLE — single pass.)
  val od      = Bits(32 bits)
  val memPost = Bool()
}
object EaSpec {
  def illegalDefault(): EaSpec = {
    val e = EaSpec()
    e.klass := EaClass.ILLEGAL; e.reg := 0; e.imm := 0
    e.baseValid := False; e.base := 0; e.disp := 0; e.pcRel := False
    e.autoMode := EaAuto.NONE; e.autoDelta := 0
    e.indexValid := False; e.indexReg := 0; e.indexLong := False; e.indexScale := 0
    e.od := 0; e.memPost := False
    e
  }
}

/** EA-agnostic operation descriptor produced by the OperationDecoder. */
/** WHICH INSTRUCTION SHAPE the assembler must build, decided ONCE by
  * `OperationDecoder` and read by `MicroOpAssembler`.
  *
  * WHY THIS EXISTS. `MicroOpAssembler` used to re-match the OPWORD to recognise these
  * families -- `isJmpOp = op(15 downto 6) === B"10'b0100111011"` and 20 more like it --
  * while `OperationDecoder` was ALREADY testing the same bit patterns a few hundred lines
  * away (it has to, to set `eaHand`/`eaSrcValid`). The same comparison lived in two files
  * and was kept in step by hand. This field carries the decoder's answer forward instead.
  *
  * WHY IT IS NOT A `DecOp` VALUE. `DecodedUop.op` is 6 bits and FMax-critical: a single
  * extra `op === X` test in IssueQueuePlugin's scoreboard-clear cone was measured
  * post-route as the design's WNS holder (9 of the 10 worst paths, -1.699ns,
  * checkpoint 3cba17f). `OpSpec` is DECODE-TIME -- the assembler consumes it and it never
  * reaches the issue queue -- so a form here costs nothing in that cone.
  *
  * NONE means "no special shape": the ordinary operand-driven path applies. */
object OpForm extends SpinalEnum {
  val NONE,
      SCC,          // Scc <ea>            -- branch-EU condition write, assembler-built
      LEA,          // LEA <ea>,An         -- address generate
      PEA,          // PEA <ea>            -- address generate + push
      JMP, JSR,     // computed-target branches
      DIVL, MULL,   // .L forms; the Dl:Dh/size selector is in the extension word
      MOVEFROMSR,   // MOVE SR,<ea>        -- privileged on the 040
      MOVEFROMCCR,  // MOVE CCR,<ea>
      // Exact-opword line-4 forms. The decoder did not recognise ANY of these before --
      // they were matched only in MicroOpAssembler, which is what made them "by-hand
      // constructions". Naming them here does not make the decoder own their uops; it
      // only stops the shape being re-derived from bits in a second file.
      RTE,          // 0x4E73
      RTS,          // 0x4E75  (the assembler's predicate is misnamed `isRtsBad`)
      RTD,          // 0x4E74  (`isRtdBad`)
      RTR,          // 0x4E77  (`isRtrBad`)
      TRAPV         // 0x4E76
      = newElement()
}

case class OpSpec() extends Bundle {
  val op       = DecOp()
  /** The instruction SHAPE, for families MicroOpAssembler builds by hand. See OpForm. */
  val form     = OpForm()
  val size     = Size()
  val cluster  = Cluster()
  val srcA     = OperandSrc()
  val srcB     = OperandSrc()
  val dst      = OperandSrc()
  val dstWrites = Bool()                 // the dst slot is written back
  val readsNzvc = Bool();  val writesNzvc = Bool()
  val readsX    = Bool();  val writesX    = Bool()
  // MOVE writes NZVC only when its destination is a DATA register; the dst type
  // lives in the EA class, so the rule is delegated to the assembler.
  val writesNzvcIfDataDst = Bool()
  val isBranch = Bool();   val cond = Bits(4 bits)
  val illegal  = Bool()
  // CPLX (DivEu) control: DIVS vs DIVU, and the 64-bit-dividend form. CHK/DIVU leave
  // these False. (div64 / the Dr:Dq pair are set by the line-4 DIV.L decode in T6/T7.)
  val divSigned = Bool()
  val div64     = Bool()
  // Line-E shift/rotate control: shiftOp = tt (0=AS,1=LS,2=ROX,3=RO), shiftDir = d
  // (1=left), shiftImm = the `i=0` immediate-count form (count = ccc, 0->8 in the
  // assembler). Non-shift ops leave these at 0/False.
  val shiftOp   = Bits(2 bits)
  val shiftDir  = Bool()
  val shiftImm  = Bool()
  // Packed-BCD sub-kind (DecOp.BCD): False = ABCD (add), True = SBCD (subtract). Non-BCD
  // ops leave it False.
  val bcdSub    = Bool()
  // Bit op (DecOp.BITOP) sub-kind: tt = 00 BTST, 01 BCHG, 10 BCLR, 11 BSET. Non-bit
  // ops leave it 0. The bit-number source is the immediate (static) or srcB (dynamic);
  // the dest width (LONG Dn / BYTE mem) is resolved by the assembler from the EA.
  val bitOp     = Bits(2 bits)
  // Bit-field op sub-kind (DecOp.BITFIELD): bfOp = op[10:8] (0=BFTST..7=BFINS). Non
  // bit-field ops leave it 0. The static offset/width are packed into `imm` by the
  // assembler (offset = ext[10:6], raw width = ext[4:0], 0->32 normalized in the EU).
  val bfOp      = Bits(3 bits)
  // Line-4 EXT/EXTB byte-source marker (DecOp.EXT): the sign-extend source is a BYTE
  // (EXT.W / EXTB.L) rather than a word (EXT.L). Non-EXT ops leave it False.
  val extByte   = Bool()
  // MOVEM (`0100 1 d 001 s mmmrrr` + 16-bit register mask): the core's first variable-
  // length, multi-cycle-emitted instruction. OperationDecoder only CLASSIFIES it (keeps
  // it non-illegal, a benign placeholder like EXG); the DecodeStage micro-sequencer FSM
  // reads `movem`/`movemDir`/`movemSizeLong` + the EA (via the EaDecoder on op[5:0]) and
  // the mask (words(1)) to emit the per-register load/store µops over multiple cycles.
  //   movemDir       : 0 = registers->memory (STORE), 1 = memory->registers (LOAD)
  //   movemSizeLong  : 0 = .W (load sign-extends), 1 = .L
  val movem         = Bool()
  val movemDir      = Bool()
  val movemSizeLong = Bool()
  // ── MOVEP (move peripheral data, alternating even bytes) ────────────────────
  // Line-0 `0000 rrr 1 oo 001 aaa` + disp16. Like MOVEM, OperationDecoder only
  // CLASSIFIES it (keeps it non-illegal, a benign placeholder); the DecodeStage MOVEP
  // micro-sequencer FSM reads `movep`/`movepDir`/`movepSizeLong` + Dx (op[11:9]) + Ay
  // (op[2:0]) + disp16 (words(1)) to emit the byte loads/stores + the shift/and/or
  // assembly µops over multiple cycles.
  //   movepDir       : 0 = memory->register (LOAD+assemble), 1 = register->memory (STORE bytes)
  //   movepSizeLong  : 0 = .W (2 bytes), 1 = .L (4 bytes)
  val movep         = Bool()
  val movepDir      = Bool()
  val movepSizeLong = Bool()
  // ── Microcode engine routing (v1: straight-line cold opcodes) ───────────────
  // A cold/complex opcode whose µop stream EXCEEDS the ≤3-µop fast-crack budget is
  // emitted by the DecodeStage µcode SEQUENCER instead of the MicroOpAssembler crack.
  // OperationDecoder sets `microcoded := True` + `ucEntry` (the entry µPC into the
  // Microcode ROM); the sequencer walks the ROM from `ucEntry`. Like `movem`, the op
  // is kept NON-illegal (a benign placeholder so the assembler's `bad` never fires —
  // the sequencer owns emission). First customers: the ABCD/SBCD/ADDX/SUBX
  // -(Ay),-(Ax) MEMORY forms (6 µops). Default: not microcoded.
  val microcoded    = Bool()
  // entry µPC into the Microcode ROM. Widened 4 -> 5 bits in slice 3b; widened 5 -> 6 bits
  // for CAS/CAS2 (romSize hits 45: BCD 6 + bit-field 10 + full-ext mem-indirect 15 + CAS 4
  // + CAS2 10), so 5 bits (0..31) overflowed. slice 3c dynamic-mem bit-field pushes romSize
  // past 64 (entries up to 86), so 7 bits (0..127). task #197's MI_BF_* family (memory-
  // indirect bit-field) pushes romSize past 128 (entries up to 165), so 8 bits (0..255).
  // Task 6b's FP-generic memory-source load family (18 format x EA-bucket entry groups +
  // 1 trap entry, 58 new rows) pushes romSize from 252 to 310, overflowing 8 bits (0..255)
  // -> widened to 9 bits (0..511). FMax-neutral (cold decode field).
  val ucEntry       = UInt(9 bits)
  // ── Commit-time PRIVILEGED SYSTEM ops (MOVE-to-SR / MOVE-USP / MOVEC) ─────────
  // Classification only (the assembler builds the op µop carrying these): `sysOp`
  // marks a serializing commit-time system op, `sysKind` selects which, `sysReadDir`
  // the direction (read SYSTEM->Rn vs write Rn->SYSTEM). Default: not a sysOp.
  val sysOp         = Bool()
  val sysKind       = SysKind()
  val sysReadDir    = Bool()
  // ── Effective-address typing ────────────────────────────────────────────────
  // WHICH opword field is an effective address, and WHERE its extension words
  // start. Deliberately SEPARATE from srcA/srcB/dst.kind: those say how an operand
  // is PLUMBED (and drive MicroOpAssembler's crack selection), while these say what
  // the EA MACHINERY must resolve. An instruction the assembler builds BY HAND --
  // Scc, LEA, PEA, JMP, JSR, MOVE from SR/CCR, DIV.L/MUL.L -- still HAS an effective
  // address, and a gate asking "does this EA need a pointer chain walked?" must be
  // able to see it without knowing the opcode. Typing the EA does not require this
  // decoder to own the rest of the instruction.
  //   eaSrcValid : op[5:0] is a real EA this instruction addresses through.
  //   eaSrcShift : extension words that PRECEDE the EA's own first extension word
  //                (0 for most; 1 for a bit-number / bit-field / Rn / Dc:Du / Dl:Dh
  //                word; 2 for a .L line-0 immediate). This is the ONE legitimately
  //                per-op residue of EA typing, and it is per-op because the
  //                ENCODING differs, not because the opcode does.
  //   eaDstValid : the MOVE dst field (op[11:6], swapped) is a real EA.
  // MOVEM/MOVEP deliberately leave eaSrcValid False -- see OperationDecoder.
  val eaSrcValid = Bool()
  val eaSrcShift = UInt(2 bits)
  val eaDstValid = Bool()
  // ── F-line FP-generic (cpGEN) family marker ─────────────────────────────────
  // True for `1111 001 000 mmmrrr` -- the ONE thing about an FP instruction that is a
  // function of the OPWORD alone. Which operation it is (FADD vs FMUL), which FP
  // registers it touches, and whether it writes an FP register all live in the
  // EXTENSION word, which this decoder does not (and must not) see: decode() is called
  // at I-cache REFILL time by PredecodeWord.scala:64 to bake ChunkPredecode.size, where
  // no extension word exists. MicroOpAssembler owns that half, reading pkt.words(1) --
  // the same split CMP2/CHK2 already uses. `op`/`cluster` are set to FPU/CPLX here so
  // the family is classified; the assembler refines or faults it.
  val fpGeneric     = Bool()
}
object OpSpec {
  def illegalDefault(): OpSpec = {
    val o = OpSpec()
    o.op := DecOp.ILLEGAL; o.size := Size.WORD; o.cluster := Cluster.INT
    o.form := OpForm.NONE
    o.srcA.setNone(); o.srcB.setNone(); o.dst.setNone()
    o.dstWrites := False
    o.readsNzvc := False; o.writesNzvc := False
    o.readsX := False;    o.writesX := False
    o.writesNzvcIfDataDst := False
    o.isBranch := False;  o.cond := 0
    o.illegal := True
    o.divSigned := False; o.div64 := False
    o.shiftOp := 0; o.shiftDir := False; o.shiftImm := False
    o.bcdSub := False
    o.bitOp := 0
    o.bfOp := 0
    o.extByte := False
    o.movem := False; o.movemDir := False; o.movemSizeLong := False
    o.movep := False; o.movepDir := False; o.movepSizeLong := False
    o.microcoded := False; o.ucEntry := 0
    o.sysOp := False; o.sysKind := SysKind.NONE; o.sysReadDir := False
    o.eaSrcValid := False; o.eaSrcShift := 0; o.eaDstValid := False
    o.fpGeneric := False
    o
  }
}
