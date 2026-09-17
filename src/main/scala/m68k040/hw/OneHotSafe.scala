package m68k040.hw

import spinal.core._
import spinal.lib._

/** Fail-safe reductions for way/entry MATCH vectors (2026-09-17).
  *
  * ── THE HAZARD ──────────────────────────────────────────────────────────────
  * Every set-associative lookup in this core reduces a per-way match vector two
  * ways at once: a scalar "did we hit" (`orR`) and a way SELECT (`OHToUInt`, or a
  * one-hot AND-OR / `MuxOH`). Both selects are defined ONLY for a one-hot-or-zero
  * vector, and both FAIL SILENTLY on a multi-hot one:
  *
  *   - `OHToUInt` OR-s the set bit positions, so ways {0,1} select way 3 -- a way
  *     that did not match at all.
  *   - `MuxOH` / a one-hot AND-OR emits the bitwise OR of the selected entries, so
  *     two ways' data are merged into one response.
  *
  * Neither raises anything. The consumer receives a well-formed, non-faulting
  * answer built out of the wrong bits: an unrelated cache line delivered as load
  * data, two instruction lines OR-ed into one fetch response, or -- worst -- an
  * OR-ed TLB entry, i.e. a PPN nobody ever wrote, handed upward as a VALID
  * translation. `Tlb.scala`'s own comment records that last one actually
  * happening (`WalkerExcEntryWedgeSpec` caught 0xae0cf000 for a VA whose only
  * descriptor says 0x60008). That was fixed at the FILL end; the CONSUMER was
  * left unguarded, which is what this object closes.
  *
  * ── THE FAIL-SAFE ───────────────────────────────────────────────────────────
  * Treat a multi-hot match as a MISS. A miss is an outcome every one of these
  * lookups already handles correctly (refill, or a table walk), so the fail-safe
  * needs no new path -- and, critically, it needs no change to the data mux: the
  * OR-ed value is still COMPUTED, it is simply never CONSUMED, because at every
  * site the data is read only under the hit bit.
  *
  * ── WHY IT IS FREE ──────────────────────────────────────────────────────────
  * At `ways = 4` -- which is every site in this core (I-cache 4, D-cache 4, TLB 4
  * per bank) -- `orR(v[3:0])` and "exactly one of v[3:0]" are BOTH Boolean
  * functions of exactly four variables, and every function of <= 6 variables is
  * one LUT6 on this device family. So the substitution is a TRUTH-TABLE CHANGE,
  * not added logic: same fanin nets, same fanout, same logic level, same LUT
  * count. It is not "cheap", it is free, and it stays free at 8 ways (both forms
  * are two levels there).
  *
  * The expressions below are written as an explicit `any && !atLeastTwo`
  * sum-of-products rather than `CountOne(v) === 1` so that the EMITTED form is
  * already a plain combinational function of `v` with no adder for synthesis to
  * have to collapse. `any` and `atLeastTwo` are local to each call and have no
  * other fanout, so they merge into the single downstream LUT.
  *
  * ── WHAT THIS DOES NOT COVER ────────────────────────────────────────────────
  * A transient that settles to a clean ONE-HOT match on the WRONG way is
  * indistinguishable from a correct hit here and stays invisible. Only tag-array
  * parity/ECC catches that. This closes the multi-hot half of the transient
  * space, not all of it.
  */
object OneHotSafe {
  /** True iff at least two bits of `v` are set -- the fail-safe trigger. */
  def multiHot(v: Seq[Bool]): Bool =
    if (v.length < 2) False
    else v.combinations(2).map { case Seq(a, b) => a && b }.toSeq.reduceBalancedTree(_ || _)

  def multiHot(v: Vec[Bool]): Bool = multiHot(v.toSeq)

  /** The fail-safe hit reduction: `orR` with the multi-hot case removed. */
  def exactlyOne(v: Seq[Bool]): Bool =
    if (v.isEmpty) False
    else v.reduceBalancedTree(_ || _) && !multiHot(v)

  def exactlyOne(v: Vec[Bool]): Bool = exactlyOne(v.toSeq)

  /** Sticky evidence for ONE multi-hot site, readable over JTAG.
    *
    * WHY THIS IS HARDWARE AND NOT ANOTHER SIMULATION. `live-mmu` exposes only
    * TC/DTT/ITT/SRP/URP -- there is no TLB entry dump -- so a duplicate entry is
    * currently UNOBSERVABLE on the board, and every symptom that points at one is
    * circumstantial. A sticky bit that says "this structure returned a multi-hot
    * match" is direct evidence, and its ABSENCE after a failed boot falsifies the
    * hypothesis just as cleanly. That is worth a handful of flops.
    *
    *  - `sticky` latches on the first occurrence and stays set until an explicit
    *    debug write clears it. It must NOT self-clear: the whole point is to survive
    *    an arbitrary amount of subsequent execution, including a wedge.
    *  - `count` saturates at 15 rather than wrapping, so "one glitch" and "happening
    *    constantly" are distinguishable. A wrapping counter read as 3 could mean 3 or
    *    19; a saturating one read as 15 means "at least 15", which is the honest
    *    statement.
    *  - `addr` latches the FIRST occurrence only (`when(!sticky)`) and is then frozen,
    *    so it can be correlated against a faulting address without a later event
    *    overwriting it.
    *
    * `clear` is written AFTER the capture arms, so an explicit debug clear wins over a
    * same-cycle detection. That is the right precedence for a diagnostic latch: the
    * host asked for a clean slate, and a detection on the very next cycle re-arms it
    * anyway.
    */
  case class MultiHotEvidence(sticky: Bool, count: UInt, addr: UInt)

  def evidence(fire: Bool, addr: UInt, clear: Bool): MultiHotEvidence = {
    val sticky = RegInit(False)
    val count  = Reg(UInt(4 bits)) init 0
    val first  = Reg(UInt(32 bits)) init 0
    when(fire) {
      sticky := True
      when(count =/= U(15, 4 bits)) { count := count + 1 }
      when(!sticky) { first := addr.resized }
    }
    when(clear) {
      sticky := False
      count  := 0
      first  := 0
    }
    MultiHotEvidence(sticky, count, first)
  }
}
