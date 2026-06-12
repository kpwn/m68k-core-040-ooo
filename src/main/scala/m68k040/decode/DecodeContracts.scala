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
  val DATAREG, ADDRREG, IMM, MEMSIMPLE, MEMCOMPLEX, ILLEGAL = newElement()
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
}
object EaSpec {
  def illegalDefault(): EaSpec = {
    val e = EaSpec()
    e.klass := EaClass.ILLEGAL; e.reg := 0; e.imm := 0
    e.baseValid := False; e.base := 0; e.disp := 0; e.pcRel := False
    e.autoMode := EaAuto.NONE; e.autoDelta := 0
    e.indexValid := False; e.indexReg := 0; e.indexLong := False; e.indexScale := 0
    e
  }
}

/** EA-agnostic operation descriptor produced by the OperationDecoder. */
case class OpSpec() extends Bundle {
  val op       = DecOp()
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
  // ── Microcode engine routing (v1: straight-line cold opcodes) ───────────────
  // A cold/complex opcode whose µop stream EXCEEDS the ≤3-µop fast-crack budget is
  // emitted by the DecodeStage µcode SEQUENCER instead of the MicroOpAssembler crack.
  // OperationDecoder sets `microcoded := True` + `ucEntry` (the entry µPC into the
  // Microcode ROM); the sequencer walks the ROM from `ucEntry`. Like `movem`, the op
  // is kept NON-illegal (a benign placeholder so the assembler's `bad` never fires —
  // the sequencer owns emission). First customers: the ABCD/SBCD/ADDX/SUBX
  // -(Ay),-(Ax) MEMORY forms (6 µops). Default: not microcoded.
  val microcoded    = Bool()
  val ucEntry       = UInt(4 bits)   // entry µPC (ROM is small; 4 bits is ample for v1)
}
object OpSpec {
  def illegalDefault(): OpSpec = {
    val o = OpSpec()
    o.op := DecOp.ILLEGAL; o.size := Size.WORD; o.cluster := Cluster.INT
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
    o.extByte := False
    o.movem := False; o.movemDir := False; o.movemSizeLong := False
    o.microcoded := False; o.ucEntry := 0
    o
  }
}
