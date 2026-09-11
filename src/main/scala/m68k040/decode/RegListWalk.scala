package m68k040.decode

import spinal.core._
import spinal.lib._

/** Task #241/#246 ("Approach A", `docs/superpowers/specs/2026-08-19-fmovem-data-list-
  * design.md` §6.6): the SHARED, PARAMETERIZED skeleton extracted from `DecodeStage.scala`'s
  * `movemActive` FSM (integer MOVEM), reused by BOTH integer MOVEM (the refactored first
  * instantiation) and the new FMOVEM.X data-register-list FSM (the second instantiation,
  * `fmovemxActive`).
  *
  * WHAT THIS OBJECT HOLDS (per the design doc's "what's shared" bullet list): the two purely
  * COMBINATIONAL primitives every register-list walk needs --
  *   (1) priority-encoding the lowest 1 or 2 set bits of a runtime mask (any width), and
  *   (2) the bit-position -> register-number mapping, whose FORWARD/REVERSE formula is a
  *       caller-supplied function, NOT hardcoded to either instantiation's own formula (int
  *       MOVEM: bit i -> reg i forward / reg 15-i reverse; FP data-list: bit n -> FPn forward
  *       (identity) / FP(7-n) reverse -- see the design doc §2/§6.6: "NOTE the FP ordering is
  *       NOT the same formula as int's, despite the superficial forward/reverse similarity").
  *
  * WHAT THIS OBJECT DELIBERATELY DOES NOT HOLD (see the task-241 report for the full
  * rationale): the actual FSM state registers, the idle/active/drain/final-update transition
  * logic, and the per-element µop-emission callback all stay in `DecodeStage.scala`, owned
  * independently by each instantiation (`movemActive` / `fmovemxActive`). Both instantiations'
  * ENTRY-DETECTION and FETCH-HOLD signals still fold into the SAME pre-existing shared
  * aggregate gates (`movemHoldsFed`-style OR term, the wide `!movemActive && !movemBegin &&
  * ...` AND-gate) per the design doc §6.5's binding hot-case-cost constraint -- that folding
  * happens at the `DecodeStage.scala` call sites, not inside this object, since those gates
  * are specific, already-widened, textually-shared Scala vals in that file.
  */
object RegListWalk {

  /** Priority-extract the lowest TWO set bits of `mask` (any width). Returns
    * (bit0, mask-after-clearing-bit0, has-a-second-bit, bit1, mask-after-clearing-both) --
    * the EXACT pre-refactor shape of `movemBit0`/`movemMask1`/`movemHas1`/`movemBit1`/
    * `movemMask2`, now parameterized by width instead of hardcoded to 16 bits. Used by any
    * instantiation that walks up to 2 elements/cycle (int MOVEM). */
  case class Extract2(bit0: UInt, mask1: UInt, has1: Bool, bit1: UInt, mask2: Bits)
  def extractLowest2(mask: Bits): Extract2 = {
    val mask0 = mask.asUInt
    val bit0  = OHToUInt(OHMasking.first(mask))
    val mask1 = mask0 & (mask0 - 1)
    val has1  = mask1 =/= 0
    val bit1  = OHToUInt(OHMasking.first(mask1.asBits))
    val mask2 = (mask1 & (mask1 - 1)).asBits
    Extract2(bit0, mask1, has1, bit1, mask2)
  }

  /** Priority-extract just the LOWEST set bit of `mask` (any width): (bit0, mask-after-
    * clearing-bit0). Used by any instantiation that walks exactly 1 element/cycle (the FP
    * data-list FSM: each element needs multiple internal sub-phase cycles -- 3 chunk loads
    * + 1 issue row for Extended -- so at most one mask bit is ever consumed per ELEMENT,
    * unlike int MOVEM's up-to-2-bits-per-CYCLE walk). */
  def extractLowest1(mask: Bits): (UInt, Bits) = {
    val mask0 = mask.asUInt
    val bit0  = OHToUInt(OHMasking.first(mask))
    val mask1 = (mask0 & (mask0 - 1)).asBits
    (bit0, mask1)
  }

  /** Same primitive from the OTHER end: the HIGHEST set bit, and the mask with it cleared.
    *
    * FMOVEM.X `-(An)` needs this. Its frame occupies [An-12N, An) and the element walk runs
    * ASCENDING from An-12N, so to place the list's LAST register at the lowest address (and
    * therefore the first-transferred register adjacent to An, as the predecrement form
    * requires) the mask must be consumed from the top down. Consuming it lowest-first with
    * an ascending address walk lays the block down exactly reversed. */
  def extractHighest1(mask: Bits): (UInt, Bits) = {
    val hi   = OHMasking.last(mask)
    val bitN = OHToUInt(hi)
    (bitN, (mask.asUInt & ~hi.asUInt).asBits)
  }

  /** Direction-selected extraction: `fromTop` picks `extractHighest1`, else `extractLowest1`. */
  def extract1(mask: Bits, fromTop: Bool): (UInt, Bits) = {
    val (loBit, loRest) = extractLowest1(mask)
    val (hiBit, hiRest) = extractHighest1(mask)
    (Mux(fromTop, hiBit, loBit), Mux(fromTop, hiRest, loRest))
  }

  /** Bit-position -> register-number mapping: `reverse` selects the caller's REVERSE
    * formula, else the caller's FORWARD formula. Deliberately takes both maps as FUNCTIONS
    * (not a fixed `width - 1 - bit` formula) -- int MOVEM's reverse map is `15 - bit`
    * (16-entry mask, `-(An)` predecrement); the FP data-list's reverse map is `7 - bit`
    * (8-entry mask, the "control/postincrement list" ext-word format) -- see the design
    * doc §6.6's explicit warning not to hardcode int's own formula here. */
  def regOf(bit: UInt, reverse: Bool, forward: UInt => UInt, rev: UInt => UInt): UInt =
    Mux(reverse, rev(bit), forward(bit))
}
