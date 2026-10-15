package m68k040.decode

import spinal.core._
import scala.collection.mutable

/** CPU_IMM_CONE: one-hot "atom select" form of the assembler's immediate cone.
  *
  * Flag OFF (`enabled = false`): every entry point returns the ORIGINAL expression it is
  * handed (`apply(key)(v)` -> `v`, `alt(orig)(...)` -> `orig`) and `width` is 32, so the
  * assembler elaborates exactly the expressions it always did.
  *
  * Flag ON: an immediate is `A + (P ? pc : 0) + K`, and a uop's `imm` field carries a CODE
  * instead of the 32-bit value:
  *   bits [0, A_MAX)            one-hot over the per-packet A atoms (32-bit values: packet
  *                              word slices/extensions, EA displacements, opword fields...)
  *   bit  A_MAX                 P: add the packet pc (PC-relative folds)
  *   bits [A_MAX+1, width)      one-hot over the per-packet K atoms (small addends: the
  *                              fold offsets 2/4/2+2*shift, instruction length, +size ...)
  * An all-zero code is the value 0, exactly the OFF form's `imm := 0` defaults.
  *
  * All of the assembler's decision logic (the `when`/`switch` trees, the crack `Mux`es
  * between whole uops) is untouched: it steers `width`-bit constants instead of 32-bit
  * data. So each crack position's data mux collapses to one AND-OR over the atoms that
  * can actually reach it (synthesis prunes code bits that are constant 0 there), and the
  * ~dozen per-packet `pc + k + disp` adders collapse to ONE adder per position, which
  * DecodeStage places AFTER the existing one-cycle-late GROUP-bank write register (see
  * `wideImm`), off the decode cone. Every PC fold is exact mod 2^32 regardless of how the
  * three terms were associated in the OFF form.
  *
  * Keys dedupe atoms that are the SAME function of the packet built at several sites
  * (e.g. sign-extended words(1)); the first registration's expression is used.
  * Equivalence to the OFF form is checked exhaustively by ImmConeEquivSpec. */
class ImmAtoms(val enabled: Boolean) {
  import ImmAtoms._
  val width: Int = if (enabled) WIDTH else 32
  private val aKeys = mutable.LinkedHashMap[String, Int]()
  private val aVals = mutable.ArrayBuffer[Bits]()
  private val kKeys = mutable.LinkedHashMap[String, Int]()
  private val kVals = mutable.ArrayBuffer[Bits]()
  /** The packet pc the P bit adds (set by the assembler). */
  var pc: UInt = null

  def zero: Bits = B(0, width bits)
  private def bit(i: Int): Bits = B(BigInt(1) << i, width bits)

  /** Code of A atom `key` (registering `v` on first use). ON only. */
  def a(key: String)(v: => Bits): Bits = {
    require(enabled)
    val i = aKeys.getOrElseUpdate(key, {
      val e = v
      require(e.getWidth == 32, s"imm A atom '$key' must be 32 bits, got ${e.getWidth}")
      aVals += e; aVals.length - 1
    })
    require(i < A_MAX, s"imm A atoms exceed ImmAtoms.A_MAX=$A_MAX")
    bit(i)
  }
  /** Code of K atom `key` (a small addend; registered as 32 bits). ON only. */
  def k(key: String)(v: => Bits): Bits = {
    require(enabled)
    val i = kKeys.getOrElseUpdate(key, {
      val e = v
      require(e.getWidth == 32, s"imm K atom '$key' must be 32 bits, got ${e.getWidth}")
      kVals += e; kVals.length - 1
    })
    require(i < K_MAX, s"imm K atoms exceed ImmAtoms.K_MAX=$K_MAX")
    bit(A_MAX + 1 + i)
  }
  /** Code of the P (add pc) term. ON only. */
  def p: Bits = { require(enabled); bit(A_MAX) }

  /** OFF: `orig`. ON: `code` (built from `a`/`k`/`p`, may itself be a Mux of codes). */
  def alt(orig: => Bits)(code: => Bits): Bits = if (!enabled) orig else code
  /** A plain value atom: OFF `v`, ON the A code of `key`. */
  def apply(key: String)(v: => Bits): Bits = alt(v)(a(key)(v))

  def aAtoms: Seq[Bits] = aVals.toList
  def kAtoms: Seq[Bits] = kVals.toList
  def names: Seq[String] = (0 until WIDTH).map { i =>
    if (i < A_MAX) aKeys.find(_._2 == i).map(_._1).getOrElse("")
    else if (i == A_MAX) "+pc"
    else kKeys.find(_._2 == i - A_MAX - 1).map("+" + _._1).getOrElse("")
  }
  def used: Seq[Int] = aKeys.values.toList ++ (if (enabled) Seq(A_MAX) else Nil) ++ kKeys.values.map(_ + A_MAX + 1)

  private def andOr(code: Bits, base: Int, vals: Seq[Bits]): Bits =
    if (vals.isEmpty) B(0, 32 bits)
    else vals.zipWithIndex.map { case (v, i) => v & B(32 bits, default -> code(base + i)) }.reduce(_ | _)

  /** Code -> the three addends (A value, P, K value). */
  def parts(code: Bits): ImmParts = {
    val r = ImmParts()
    r.a := andOr(code, 0, aVals.toList)
    r.p := code(A_MAX)
    r.k := andOr(code, A_MAX + 1, kVals.toList)
    r
  }
  /** Code -> the 32-bit value, combinationally. */
  def expand(code: Bits): Bits = parts(code).value(pc)
}

case class ImmParts() extends Bundle {
  val a = Bits(32 bits)
  val p = Bool()
  val k = Bits(32 bits)
  def value(pc: UInt): Bits = (a.asUInt + Mux(p, pc, U(0, 32 bits)) + k.asUInt).asBits
}

object ImmAtoms {
  val A_MAX = 48
  val K_MAX = 15
  val WIDTH = A_MAX + 1 + K_MAX
}
