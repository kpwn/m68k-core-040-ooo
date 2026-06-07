package m68k040.execute

import m68k040.decode.DecOp
import m68k040.isa.Size
import spinal.core._

/** Pre-rename ALU command (operands already selected by the EU in Slice 3). */
case class AluCmd() extends Bundle {
  val op      = DecOp()
  val size    = Size()
  val src1    = Bits(32 bits)  // dst operand (SUB/CMP compute src1 - src2; the unary
                               //   ops NEG/NEGX/NOT/CLR/TST/SWAP/EXT/TAS operate on src1)
  val src2    = Bits(32 bits)  // src operand
  val xIn     = Bool()         // current X — used by NEGX (0 - Dn - X)
  val extByte = Bool()         // EXT/EXTB: sign-extend a BYTE source (else a word)
}

/** ALU result + computed condition codes. nzvc = N(bit3) Z(bit2) V(bit1) C(bit0).
  * NOTE: `nzvc` carries the RAW computed Z; NEGX's "Z is clear-only" rule (Z := Z_old
  * && result==0) is applied in the EU, which has the old Z (it reads NZVC). */
case class AluRsp() extends Bundle {
  val result = Bits(32 bits)  // low `size` bits valid; upper bits raw (writeback merges)
  val nzvc   = Bits(4 bits)
  val xOut   = Bool()         // = C for ADD/SUB/NEG/NEGX; don't-care otherwise
}

object AluDatapath {
  def apply(cmd: AluCmd): AluRsp = {
    val a = cmd.src1.asUInt
    val b = cmd.src2.asUInt

    // ── Arithmetic family (ADD/SUB/CMP + the unary NEG/NEGX) via one adder ───────
    // SUB/CMP: a - b           = a + ~b + 1.
    // NEG    : 0 - Dn          = 0 + ~Dn + 1   (a_eff = 0, the source operand is src1=Dn).
    // NEGX   : 0 - Dn - X      = 0 + ~Dn + !X  (cin = !X).
    // For NEG/NEGX the operand Dn is src1 (a); the adder's `a` input is forced to 0 and
    // ~Dn / cin produce the negate. The piece() flags use a_eff/b_eff so C/V/N/Z match
    // a true subtract-from-zero at the operation size.
    val isSub   = cmd.op === DecOp.SUB || cmd.op === DecOp.CMP
    val isNeg   = cmd.op === DecOp.NEG
    val isNegx  = cmd.op === DecOp.NEGX
    val isArith = cmd.op === DecOp.ADD || isSub || isNeg || isNegx
    // The operand to negate (NEG/NEGX) is src1 (a); ordinary ADD/SUB negate src2 (b).
    val aEff = Mux(isNeg || isNegx, U(0, 32 bits), a)
    val bEff = Mux(isNeg || isNegx, ~a, Mux(isSub, ~b, b))
    val cin  = Mux(isNegx, !cmd.xIn, Mux(isSub || isNeg, True, False))
    val sum32 = aEff + bEff + cin.asUInt.resize(32)

    // ── SWAP / EXT / TAS / NOT / CLR bit-manipulation results ────────────────────
    val swapRes = a(15 downto 0) ## a(31 downto 16)               // halves swapped (full-32)
    // EXT/EXTB: sign-extend the low byte (extByte) or word of src1 to 32 bits; the
    // writeback size-merge then keeps the .W (word) or full-32 (.L) portion.
    val extRes  = Mux(cmd.extByte,
                      a(7 downto 0).asSInt.resize(32).asBits,
                      a(15 downto 0).asSInt.resize(32).asBits).asUInt
    val tasRes  = (a(31 downto 8) ## (a(7 downto 0) | U(0x80, 8 bits)))  // set Dn[7]
    val notRes  = ~a

    val result = cmd.op.mux(
      DecOp.MOVE -> cmd.src2.asUInt,
      DecOp.AND  -> (a & b),
      DecOp.OR   -> (a | b),
      DecOp.EOR  -> (a ^ b),
      DecOp.NOT  -> notRes,
      DecOp.CLR  -> U(0, 32 bits),
      DecOp.TST  -> a,                  // TST writes no reg; flags come from src1
      DecOp.SWAP -> swapRes.asUInt,
      DecOp.EXT  -> extRes,
      DecOp.TAS  -> tasRes.asUInt,
      default    -> sum32               // ADD/SUB/CMP/NEG/NEGX
    )

    def piece(w: Int) = new Area {
      val ext  = (False ## aEff(w - 1 downto 0)).asUInt + (False ## bEff(w - 1 downto 0)).asUInt + cin.asUInt
      val cout = ext(w)
      val n    = result(w - 1)
      val z    = result(w - 1 downto 0) === 0
      val v    = (aEff(w - 1) === bEff(w - 1)) && (result(w - 1) =/= aEff(w - 1))
    }
    val p8 = piece(8); val p16 = piece(16); val p32 = piece(32)
    def bySize[T <: Data](vb: T, vw: T, vl: T): T =
      cmd.size.mux(Size.BYTE -> vb, Size.WORD -> vw, Size.LONG -> vl)

    val nGen   = bySize(p8.n, p16.n, p32.n)
    val zGen   = bySize(p8.z, p16.z, p32.z)
    val vArith = bySize(p8.v, p16.v, p32.v)
    val cout   = bySize(p8.cout, p16.cout, p32.cout)

    // TAS sets N/Z from the ORIGINAL Dn[7:0] (BEFORE bit7 is set), not from the result
    // (whose bit7 is always 1). N = a[7], Z = (a[7:0]==0).
    val isTas = cmd.op === DecOp.TAS
    val n     = Mux(isTas, a(7), nGen)
    val z     = Mux(isTas, a(7 downto 0) === 0, zGen)

    // C: ADD = cout, SUB/NEG/NEGX = borrow = ~cout. Logical/bit ops (incl. CLR/TAS) = 0.
    val cFlag = Mux(isArith, Mux(isSub || isNeg || isNegx, ~cout, cout), False)
    val vFlag = Mux(isArith, vArith, False)

    // CLR forces Z=1/N=0 (the result is 0 at every size). The generic n/z above already
    // give that (result==0 -> z=1, n=0), so no special-case is needed.
    val rsp = AluRsp()
    rsp.result := result.asBits
    rsp.nzvc   := n ## z ## vFlag ## cFlag
    rsp.xOut   := cFlag                 // X := C for NEG/NEGX (writesX gated by decode)
    rsp
  }
}
