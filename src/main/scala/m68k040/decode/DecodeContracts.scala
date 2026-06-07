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
  val NONE, REGFIELD, EASRC, EADST, IMMQ, IMMEXT = newElement()
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

case class EaSpec() extends Bundle {
  val klass = EaClass()
  val reg   = UInt(5 bits)    // full reg id: Dn=0..7, An=8..15, temps 16/17 (valid for DATAREG/ADDRREG)
  val imm   = Bits(32 bits)   // valid for IMM
  // memSimple base/disp (valid for MEMSIMPLE; produced by the memory-cracking slice).
  val baseValid = Bool()      // a base An register read is needed (false for abs / PC-rel)
  val base      = UInt(5 bits)// base An reg id (when baseValid)
  val disp      = Bits(32 bits)// displacement / absolute address (final disp once pcRel folded)
  val pcRel     = Bool()      // (d16,PC): the assembler folds pc into `disp` (base=0)
}
object EaSpec {
  def illegalDefault(): EaSpec = {
    val e = EaSpec()
    e.klass := EaClass.ILLEGAL; e.reg := 0; e.imm := 0
    e.baseValid := False; e.base := 0; e.disp := 0; e.pcRel := False
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
    o
  }
}
