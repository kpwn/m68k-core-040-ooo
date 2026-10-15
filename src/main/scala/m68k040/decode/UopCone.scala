package m68k040.decode

import spinal.core._
import scala.collection.mutable

/** CPU_UOP_CONE: one-hot "builder select" form of the assembler's crack tree.
  *
  * The crack tree (`MicroOpAssembler.assembleImpl`, the `when(isDynamicFmovem) ...
  * elsewhen ...` chain) picks, per crack position, one of ~40 whole builder uops
  * (`opUop`, `ldUop`, `jsrPush`, ...), each ~200 bits wide. Written as a priority chain of
  * whole-bundle assignments, every one of those bits gets its own copy of the decision
  * chain. Here the chain steers a short one-hot CODE over the builders instead, and each
  * position's uop is ONE AND-OR over only the builders that can reach it (field bits that
  * are constant 0 in a builder drop out of the OR; constant-1 bits reduce to an OR of
  * select bits).
  *
  * Flag OFF (`enabled = false`): `set` performs exactly the original `out.uops(k) := ...`
  * assignment, with `Mux` choices rebuilt as the original bundle `Mux`es, so the assembler
  * elaborates the same hardware it always did.
  *
  * Equivalence: the chain is complete (every path assigns positions 0/1; position 2 has a
  * default) and every code is a constant one-hot or a `Mux` of such, so exactly one select
  * bit is set and the AND-OR returns that builder's bits. Checked by UopConeEquivSpec. */
class UopCone(val enabled: Boolean, immW: Int) {
  import UopCone._
  private val srcs = mutable.ArrayBuffer[DecodedUop]()
  /** Per-position code (ON only); driven in the crack tree via `set`. */
  val sel: Seq[Bits] = if (enabled) Seq.fill(3)(Bits(MAX bits)) else Nil
  /** Per-position result (ON only); `out.uops(k)` defaults to this before the crack tree. */
  val res: Seq[DecodedUop] = if (enabled) Seq.fill(3)(DecodedUop(immW)) else Nil

  private def idx(u: DecodedUop): Int = {
    val i = srcs.indexWhere(_ eq u)
    if (i >= 0) i else { srcs += u; require(srcs.length <= MAX, s"UopCone: > $MAX builders"); srcs.length - 1 }
  }
  private def code(c: Choice): Bits = c match {
    case One(u)       => B(BigInt(1) << idx(u), MAX bits)
    case Sel(b, x, y) => Mux(b, code(x), code(y))
  }
  private def realize(c: Choice): DecodedUop = c match {
    case One(u)       => u
    case Sel(b, x, y) => Mux(b, realize(x), realize(y))
  }
  /** The crack-tree assignment `out.uops(k) := c`. */
  def set(out: Vec[DecodedUop], k: Int, c: Choice): Unit =
    if (enabled) sel(k) := code(c) else out(k) := realize(c)

  /** ON: build `res` from `sel` (call once, after the crack tree). Per leaf field, the
    * builders are grouped by the signal that (unconditionally, transitively) drives that
    * field -- e.g. every builder's `pc` is `pkt.pc` -- and the field is
    * `OR_g(root_g & OR_{k in g} sel_k)`; a field with ONE group is the root itself
    * (the select is one-hot and complete, so the OR of all selects is 1). */
  def finish(): Unit = if (enabled) {
    def root(b: BaseType): BaseType = {
      var r = b; var n = 0
      var d = r.getSingleDriver
      def same(x: BaseType): Boolean = (x, b) match {
        case (xv: BitVector, bv: BitVector) =>
          xv.getClass == bv.getClass && bv.getWidth > 0 && xv.getWidth == bv.getWidth
        case (xe: SpinalEnumCraft[_], be: SpinalEnumCraft[_]) => xe.spinalEnum eq be.spinalEnum
        case (_: Bool, _: Bool) => true
        case _ => false
      }
      while (d.isDefined && n < 16 && same(d.get)) {
        r = d.get; d = r.getSingleDriver; n += 1
      }
      r
    }
    val flat = srcs.map(_.flatten)
    for (k <- 0 until 3) {
      val s = sel(k)
      val rf = res(k).flatten
      for (f <- rf.indices) {
        val roots = flat.map(fl => root(fl(f)))
        val groups = mutable.ArrayBuffer[(BaseType, mutable.ArrayBuffer[Int])]()
        for ((r, i) <- roots.zipWithIndex) groups.find(_._1 eq r) match {
          case Some(g) => g._2 += i
          case None    => groups += ((r, mutable.ArrayBuffer(i)))
        }
        val w = widthOf(rf(f))
        if (groups.length == 1) rf(f).assignFromBits(groups.head._1.asBits)
        else rf(f).assignFromBits(groups.map { case (r, is) =>
          r.asBits & B(w bits, default -> is.map(i => s(i)).reduce(_ || _)) }.reduce(_ | _))
      }
    }
  }
  def builders: Int = srcs.length
}

object UopCone {
  val MAX = 64
  sealed trait Choice
  case class One(u: DecodedUop) extends Choice
  case class Sel(c: Bool, a: Choice, b: Choice) extends Choice
  implicit def one(u: DecodedUop): Choice = One(u)
  def mx(c: Bool, a: Choice, b: Choice): Choice = Sel(c, a, b)
}
