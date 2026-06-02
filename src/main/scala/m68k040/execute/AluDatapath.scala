package m68k040.execute

import m68k040.decode.DecOp
import m68k040.isa.Size
import spinal.core._

/** Pre-rename ALU command (operands already selected by the EU in Slice 3). */
case class AluCmd() extends Bundle {
  val op   = DecOp()
  val size = Size()
  val src1 = Bits(32 bits)  // dst operand (SUB/CMP compute src1 - src2)
  val src2 = Bits(32 bits)  // src operand
  val xIn  = Bool()         // current X (unused by this op set; plumbed for ADDX later)
}

/** ALU result + computed condition codes. nzvc = N(bit3) Z(bit2) V(bit1) C(bit0). */
case class AluRsp() extends Bundle {
  val result = Bits(32 bits)  // low `size` bits valid; upper bits raw (writeback merges)
  val nzvc   = Bits(4 bits)
  val xOut   = Bool()         // = C for ADD/SUB; don't-care otherwise
}

object AluDatapath {
  def apply(cmd: AluCmd): AluRsp = {
    val a = cmd.src1.asUInt
    val b = cmd.src2.asUInt

    val isSub   = cmd.op === DecOp.SUB || cmd.op === DecOp.CMP
    val isArith = cmd.op === DecOp.ADD || isSub
    val cin     = isSub
    val b2      = Mux(isSub, ~b, b)
    val sum32   = a + b2 + cin.asUInt.resize(32)

    val result = cmd.op.mux(
      DecOp.MOVE -> cmd.src2.asUInt,
      DecOp.AND  -> (a & b),
      DecOp.OR   -> (a | b),
      default    -> sum32
    )

    def piece(w: Int) = new Area {
      val ext  = (False ## a(w - 1 downto 0)).asUInt + (False ## b2(w - 1 downto 0)).asUInt + cin.asUInt
      val cout = ext(w)
      val n    = result(w - 1)
      val z    = result(w - 1 downto 0) === 0
      val v    = (a(w - 1) === b2(w - 1)) && (result(w - 1) =/= a(w - 1))
    }
    val p8 = piece(8); val p16 = piece(16); val p32 = piece(32)
    def bySize[T <: Data](vb: T, vw: T, vl: T): T =
      cmd.size.mux(Size.BYTE -> vb, Size.WORD -> vw, Size.LONG -> vl)

    val n      = bySize(p8.n, p16.n, p32.n)
    val z      = bySize(p8.z, p16.z, p32.z)
    val vArith = bySize(p8.v, p16.v, p32.v)
    val cout   = bySize(p8.cout, p16.cout, p32.cout)

    val cFlag = Mux(isArith, Mux(isSub, ~cout, cout), False)
    val vFlag = Mux(isArith, vArith, False)

    val rsp = AluRsp()
    rsp.result := result.asBits
    rsp.nzvc   := n ## z ## vFlag ## cFlag
    rsp.xOut   := cFlag
    rsp
  }
}
