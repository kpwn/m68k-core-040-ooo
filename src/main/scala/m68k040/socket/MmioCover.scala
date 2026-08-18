package m68k040.socket

import m68k040.isa.Size
import spinal.core._
import spinal.lib._

/** The bounded exact-cover arithmetic for INHIBITED (MMIO) AXI sizing (design spec D6,
  * D25, D30, sections 3.3-3.4).
  *
  * ==The problem==
  * The D-side emits `size=4` (16 bytes) at the line base for every access, INHIBITED
  * included, and relies on WSTRB alone for lane selection. Two independent failures against
  * this SoC: `ddr_ctrl`'s `align_error(size, addr)` correctly SLVERRs a size/address
  * mismatch, and -- far worse -- byte-addressed I/O registers are selected by ADDRESS, not
  * strobe (`peripheral_bus.v:68-72` picks the byte with `addr[1:0]`), so a line-aligned
  * 16-byte MMIO read touches four registers at once and fires read-to-clear side effects on
  * three the access never named. SCC, VIA, IWM, SCSI and ADB all live in that space.
  *
  * ==Why an EXACT cover and not just a legality fix (D30)==
  * A write's `size=2` + `WSTRB=0110` carries the full "which bytes are wanted" information
  * to the slave, so a conforming slave can get it right. A covering READ carries NOTHING:
  * AXI4 reads have no byte-enable field at all, so `ARSIZE=2` at the group base is
  * indistinguishable from a genuine longword read and no slave, however well written, can
  * recover which two bytes the CPU actually wanted. A downstream-only fix for the load case
  * is impossible IN PRINCIPLE, not merely unimplemented. That is why the fix is here.
  *
  * ==The rule (D30), verbatim from spec section 3.4==
  * {{{
  *   p = start
  *   while (p != end) {
  *     sz = the largest of {4,2,1} with (p % sz == 0) && (p + sz <= end)
  *     emit (address = p, AxSIZE = log2(sz))
  *     p += sz
  *   }
  * }}}
  * `p` is LINE-RELATIVE and the containing line is 16-byte aligned, so `p`'s low bits ARE
  * the emitted address's low bits: the alignment test needs no separate address arithmetic.
  *
  * ==Why it is bounded at three, as a proof rather than an observation==
  * `Size` has exactly three members, so `n` is in {1,2,4}, and after the D25 clamp
  * `end - start <= 4`. Within one 4-byte group a range of length `L` at group offset `gs`
  * needs at most 2 pieces, and the complete set of 2-piece rows is `(L=2,gs=1)`,
  * `(L=3,gs=0)`, `(L=3,gs=1)`. Both groups needing 2 would require a 6-byte range. Hence at
  * most 3, always -- no loop bound to trust, no data-dependent iteration count. The earlier
  * "a fix would need a general N-way byte sequencer" objection was wrong because the thing
  * it avoided does not exist.
  *
  * ==Two range derivations, one cover (D5, D24, D26)==
  * LOADS and `useStrb = false` stores derive `[start, end)` from `(paddr[3:0], size)`.
  * A `useStrb = true` store slot derives it from the RUN OF SET BITS in its 16-bit
  * line-relative strobe, and **`size` is not an input on that path**: `StoreQueue.scala:264`
  * drives slot B's `size` as a flat `Size.LONG` regardless of its true 1-3-byte extent, and
  * slot B's `paddr` is the next line base, so `paddr[3:0] = 0`. Deriving `[off, off+n)`
  * there would read `[0,4)` for a slot whose real extent is `[0,1)` -- and this cover would
  * then cover that over-wide range EXACTLY, and exactly wrongly, naming up to three
  * peripheral registers the architectural access never touched. The strobe is the only
  * field on `DStoreCmd` that still carries the true extent.
  *
  * ==Ordering hazard with the section 2 permutation (D5)==
  * The store derivation runs on the CORE-SIDE, pre-permutation strobe. Nibble reversal
  * preserves popcount and contiguity but NOT the offset a run starts at, so deriving from
  * the post-permutation strobe would produce the mirror-image address. This code therefore
  * lives inside the D-cache and `SocketByteOrder` is applied strictly afterwards, at the
  * socket boundary. The two transforms are order-dependent; see `SocketByteOrder`'s doc
  * comment for the other half of this statement. */
object MmioCover {

  /** The proved upper bound on sub-transactions per INHIBITED access. */
  val MAX_SUBS = 3

  /** 1 / 2 / 4 for BYTE / WORD / LONG. */
  def sizeBytes(size: Size.C): UInt = {
    val n = UInt(3 bits); n := U(1, 3 bits)
    switch(size) {
      is(Size.BYTE) { n := U(1, 3 bits) }
      is(Size.WORD) { n := U(2, 3 bits) }
      is(Size.LONG) { n := U(4, 3 bits) }
    }
    n
  }

  /** D25: `end = min(off + n, 16)`.
    *
    * THE CLAMP IS NOT DEFENSIVE PROGRAMMING; it is load-bearing, and omitting it creates a
    * wrong-address bus transaction that does not exist today. `LsEuPlugin.scala:759` issues
    * BOTH slots of a cross-boundary split at the FULL original size
    * (`Mux(useSplitCmd, llReg.size, alignedCmd.size)` has no slot-B arm) while switching
    * only the address, so slot A arrives with `off + n > 16`. Deriving group indices from
    * that unclamped range yields a sub-transaction at line offset 16 -- the NEXT line -- and
    * when the containing line is the last of its page, that is the first line of the next
    * PHYSICAL PAGE, an address the core never translated. And it is not a corner of a
    * corner: `lineOff = pageOff mod 16`, so `pageOff + n > 4096` FORCES `lineOff + n > 16`
    * (`LsEuPlugin.scala:441-443`) -- every cross-page access is one of these. */
  def clampedEnd(off: UInt, n: UInt): UInt = {
    val sum = off.resize(5 bits) +^ n.resize(5 bits)     // widening: off=15,n=4 -> 19
    val e = UInt(5 bits)
    e := Mux(sum > U(16, 6 bits), U(16, 5 bits), sum.resize(5 bits))
    e
  }

  /** The D30 greedy choice at line-relative pointer `p` against clamped `end`, expressed as
    * an `AxSIZE` (0 = 1 byte, 1 = 2 bytes, 2 = 4 bytes).
    *
    * Written as two independent predicates with last-assignment-wins rather than a
    * priority chain, so the "largest that fits and is aligned" rule is literal. */
  def stepLog2(p: UInt, end: UInt): UInt = {
    val pw   = p.resize(5 bits)
    val can2 = (p(0) === False) && ((pw +^ U(2, 5 bits)) <= end.resize(6 bits))
    val can4 = (p(1 downto 0) === U(0, 2 bits)) && ((pw +^ U(4, 5 bits)) <= end.resize(6 bits))
    val r = UInt(2 bits)
    r := U(0, 2 bits)
    when(can2) { r := U(1, 2 bits) }
    when(can4) { r := U(2, 2 bits) }
    r
  }

  /** Lowest set bit of a 16-bit line-relative strobe. Zero when the strobe is empty, which
    * the callers never present (a drained store always strobes at least one byte). */
  def strbRunStart(strb: Bits): UInt = {
    val s = UInt(4 bits)
    s := U(0, 4 bits)
    // Iterate DOWNWARD so the lowest set index is assigned last and therefore wins.
    for (i <- 15 to 0 by -1) when(strb(i)) { s := U(i, 4 bits) }
    s
  }

  /** Highest set bit of a 16-bit line-relative strobe, PLUS ONE (an exclusive end).
    *
    * The run is contiguous by construction -- a split slot is a contiguous sub-range of one
    * architectural access (`DcacheTypes.scala:312-320` / `:335-343`). A hypothetically
    * sparse strobe stays SAFE rather than correct: the cover spans the run's convex hull,
    * and each sub-transaction still carries the exact strobe bits, so no byte is written
    * that was not strobed. */
  def strbRunEnd(strb: Bits): UInt = {
    val e = UInt(5 bits)
    e := U(0, 5 bits)
    // Iterate UPWARD so the highest set index is assigned last and therefore wins.
    for (i <- 0 to 15) when(strb(i)) { e := U(i + 1, 5 bits) }
    e
  }

  /** The identical greedy loop as pure Scala, returning `(lineRelativeOffset, nBytes)`.
    *
    * Kept beside the hardware for the same reason `SocketByteOrder.modelData` is: it is the
    * golden model the exhaustive 48+48-case proof compares against, and one definition
    * cannot drift from itself. */
  def model(start: Int, end: Int): Seq[(Int, Int)] = {
    require(0 <= start && start < end && end <= 16,
      s"MmioCover.model: [$start,$end) is not a non-empty range inside one 16-byte line")
    val out = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    var p = start
    while (p != end) {
      val sz = if (p % 4 == 0 && p + 4 <= end) 4
               else if (p % 2 == 0 && p + 2 <= end) 2
               else 1
      out += ((p, sz))
      p += sz
    }
    require(out.length <= MAX_SUBS,
      s"MmioCover.model: [$start,$end) needed ${out.length} sub-transactions, " +
      s"which contradicts the spec's proof that the bound is $MAX_SUBS")
    out.toSeq
  }
}
