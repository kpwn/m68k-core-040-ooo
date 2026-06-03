package m68k040.decode

import m68k040.isa.{Cluster, Size}
import spinal.core._

/** Where a backend operand slot sources its value. EA-agnostic: `EASRC`/`EADST`
  * mean "the operand encoded in the EA field" — register vs memory is the
  * EaDecoder's call. `REGFIELD` = a register named by a fixed opword field (Dn,
  * bits 11-9). `IMMQ` = the MOVEQ 8-bit signed immediate. */
object OperandKind extends SpinalEnum {
  val NONE, REGFIELD, EASRC, EADST, IMMQ = newElement()
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
    o
  }
}
