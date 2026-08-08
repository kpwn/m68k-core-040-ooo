package m68k040.decode

import spinal.core._

/** `cinSel` encoding for [[AluOpClass]] — which carry-in the ALU adder takes. */
object AluCin {
  val ZERO = 0   // ADD / the non-arithmetic ops
  val ONE  = 1   // SUB / CMP / NEG  (a + ~b + 1)
  val X    = 2   // ADDX             (a + b + X)
  val NOTX = 3   // NEGX / SUBX      (a + ~b + !X, the borrow form)
}

/** Pre-decoded ALU op-class control (FMax "Lever N-A").
  *
  * Every field here is a PURE FUNCTION of `DecOp` — nothing else. Before this
  * existed, the ALU EU re-derived all of it COMBINATIONALLY at execute time from
  * `s1Ctx.uop.op`, twice and in series with the 32-bit adder:
  *
  *   `s1Ctx_uop_op` (registered since S0) -> 6 x `DecOp` equality compares (LUT6
  *   each) -> the `aEff`/`bEff`/`cin` operand-prep muxes -> CARRY8 chain -> flag
  *   gen -> a 5-deep serial 2:1 flag mux chain -> the ROB `nzvcValStore` /
  *   `sysValStore` / `xValStore` capture and the NZVC PRF write data.
  *
  * The netlist grounding measured 0.626 ns burned on the compares + operand prep
  * BEFORE the carry chain even starts, on a path whose whole budget is 4.0 ns —
  * ~2,368 flops / ~3,100 failing endpoints (~15% of the design's failing
  * endpoints) sit behind that one cone.
  *
  * The fix is the same "stop recomputing something already known" shape as
  * Lever B (size at I-cache refill) and Lever U1 (µcode-resume `OpSpec` reuse):
  * compute it ONCE, at rename (`RenameStage`), from the very same `dec.op` that
  * drives `RenamedUop.op`, and carry it in the µop. It then arrives at the ALU EU
  * as a plain registered field, off the critical path entirely.
  *
  * CORRECTNESS IS BY CONSTRUCTION, not by hand-copy: there is exactly ONE
  * producer of this bundle in the whole design, [[AluOpClass.of]], and exactly
  * ONE call site that baked it into a µop (`RenameStage.scala`, on the line
  * immediately after `r.op := dec.op`). `AluDatapath`'s one-argument
  * `apply(cmd)` overload also calls `of(cmd.op)`, so any consumer that does NOT
  * carry a pre-decoded class gets the identical value derived on the spot.
  *
  * NOTE: this is control ONLY. No architectural state, no new decode semantics —
  * removing every field here and re-deriving `of(op)` at the point of use would
  * restore the exact previous netlist behaviour.
  */
case class AluOpClass() extends Bundle {
  // ── the adder's operand prep (AluDatapath) ────────────────────────────────
  /** NEG/NEGX: force `aEff := 0` and `bEff := ~a` (negate the src1 operand). */
  val zeroA      = Bool()
  /** SUB/CMP/SUBX: `bEff := ~b` (only consulted when `!zeroA`). */
  val invB       = Bool()
  /** Adder carry-in select; see [[AluCin]]. */
  val cinSel     = Bits(2 bits)
  /** ADD/SUB/CMP/NEG/NEGX/ADDX/SUBX — C and V come from the adder (else 0). */
  val isArith    = Bool()
  /** SUB/CMP/NEG/NEGX/SUBX — C is a BORROW (`~cout`) rather than a carry. */
  val borrow     = Bool()
  // ── the datapath's flag overrides (AluDatapath) ───────────────────────────
  /** TAS: N/Z come from the ORIGINAL src1 byte, not from the result. */
  val isTas      = Bool()
  /** BITOP (BTST/BCHG/BCLR/BSET): Z = the tested bit's complement. */
  val isBitOp    = Bool()
  // ── the EU's final-flag mux selects (AluEuPlugin) ─────────────────────────
  /** NEGX/ADDX/SUBX: the 68k "Z is clear-only" multi-precision merge. */
  val isExtended = Bool()
  /** NBCD (a BCD op whose `dx` operand is forced to 0). */
  val isNbcd     = Bool()
  /** ABCD/SBCD/NBCD: the decimal-adjust datapath supplies NZVC + X. */
  val isBcd      = Bool()
  /** CASOP: the CAS/CAS2 compute kernel supplies the result + NZVC. */
  val isCasOp    = Bool()
  // ── the EU's fast/slow routing (AluEuPlugin) ──────────────────────────────
  /** SHIFT (line-E barrel shifter) — routed to the 2-cycle slow path. */
  val isShift    = Bool()
  /** BITFIELD — routed to the slow path alongside SHIFT. */
  val isBitfield = Bool()
}

object AluOpClass {

  /** THE definition. Every field is derived here and nowhere else; the
    * expressions below are verbatim the ones that previously lived inline in
    * `AluDatapath.apply` and `AluEuPlugin` (see `AluOpClassSpec`, which proves
    * this equivalence exhaustively over every `DecOp` encoding). */
  def of(op: DecOp.C): AluOpClass = {
    // The original inline compares (AluDatapath.scala, pre-Lever-N-A).
    val isSub  = op === DecOp.SUB || op === DecOp.CMP
    val isNeg  = op === DecOp.NEG
    val isNegx = op === DecOp.NEGX
    val isAddx = op === DecOp.ADDX
    val isSubx = op === DecOp.SUBX
    val nbcd   = op === DecOp.NBCD

    val c = AluOpClass()
    c.zeroA      := isNeg || isNegx
    c.invB       := isSub || isSubx
    c.cinSel     := Mux(isNegx || isSubx, B(AluCin.NOTX, 2 bits),
                     Mux(isSub  || isNeg, B(AluCin.ONE,  2 bits),
                       Mux(isAddx,        B(AluCin.X,    2 bits),
                                          B(AluCin.ZERO, 2 bits))))
    c.isArith    := (op === DecOp.ADD) || isSub || isNeg || isNegx || isAddx || isSubx
    c.borrow     := isSub || isNeg || isNegx || isSubx
    c.isTas      := op === DecOp.TAS
    c.isBitOp    := op === DecOp.BITOP
    c.isExtended := isNegx || isAddx || isSubx
    c.isNbcd     := nbcd
    c.isBcd      := (op === DecOp.BCD) || nbcd
    c.isCasOp    := op === DecOp.CASOP
    c.isShift    := op === DecOp.SHIFT
    c.isBitfield := op === DecOp.BITFIELD
    c
  }
}
