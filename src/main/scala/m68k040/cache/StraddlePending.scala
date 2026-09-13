package m68k040.cache

import spinal.core._
import spinal.lib._

/** Per-LINE records for straddles awaiting the next line's words.
  *
  * See DESIGN_icache_straddle_token.md. Only the last <=3 words of a line can be
  * `ambiguousLine` (a line is two 32-byte beats; beat 0's lookahead reaches into
  * beat 1, so only beat 1's tail has nowhere to look). This holds ONE record per
  * such line rather than one per word, because completing any of those positions
  * needs the same thing: the line's three tail words, plus the successor line's
  * first words.
  *
  * ── Why allocate at the line's OWN refill, before the answer is known
  *
  * The record is created while the fill FSM still has {way, set} in hand. That is
  * the whole point: when the successor line is later filled, resolution needs NO tag
  * probe to discover which way the predecessor lived in, and NO read of the
  * predecessor out of `lineMem` -- the record already carries both the location and
  * the words. It also reserves the slot, so a resolution can never arrive to find
  * the table full.
  *
  * ── Safety
  *
  * A pending record is pure speculation about a line that may be evicted before its
  * successor ever arrives. It is therefore killed by exactly the same events as a
  * resolved entry (`killWaySet` on a refill of that {way,set}, `killAll` on
  * CINV/CPUSHA), and a record that is never completed simply expires: the frontend
  * keeps re-classifying live, which is today's behaviour. Nothing here can produce a
  * WRONG length -- only the absence of a cached one.
  */
case class StraddlePending(records: Int, wayBits: Int, setBits: Int, lineKeyBits: Int) extends Area {
  require(records > 0 && isPow2(records), "records must be a power of two")
  val idxBits = log2Up(records)

  val valid   = Vec.fill(records)(RegInit(False))
  val way     = Vec.fill(records)(Reg(UInt(wayBits bits)))
  val set     = Vec.fill(records)(Reg(UInt(setBits bits)))
  /** Line address >> log2(lineBytes). The successor test is `lineKey + 1 === newKey`,
    * an exact identity -- NOT "adjacent set", which would alias every 64th line. */
  val lineKey = Vec.fill(records)(Reg(UInt(lineKeyBits bits)))
  /** The line's last three words, in increasing address order (tail(2) is the final
    * word of the line). Captured at refill, so resolution needs no array read. */
  val tail    = Vec.fill(records)(Vec.fill(3)(Reg(Bits(16 bits))))

  val allocPtr = RegInit(U(0, idxBits bits))

  /** Record a line whose tail could not be framed at refill. Replaces any existing
    * record for the same lineKey so a refetched line cannot leave two. */
  def allocate(w: UInt, s: UInt, key: UInt, t0: Bits, t1: Bits, t2: Bits): Unit = {
    for (k <- 0 until records)
      when(valid(k) && (lineKey(k) === key)) { valid(k) := False }
    val i = allocPtr
    valid(i)   := True
    way(i)     := w
    set(i)     := s
    lineKey(i) := key
    tail(i)(0) := t0; tail(i)(1) := t1; tail(i)(2) := t2
    allocPtr   := i + 1
  }

  /** Is `key` the successor of a pending line? Returns the one-hot match. At most one
    * record can match: `allocate` de-duplicates on lineKey. */
  def successorHits(key: UInt): Vec[Bool] =
    Vec((0 until records).map(i => valid(i) && (lineKey(i) + 1 === key)))

  /** Masked OR-reduce of a per-record field over a one-hot match (see StraddleTable's
    * note on why this is not MuxOH). */
  def selectU(hits: Vec[Bool], f: Int => UInt): UInt =
    (0 until records).map(i => f(i).asBits.andMask(hits(i))).reduce(_ | _).asUInt
  def selectB(hits: Vec[Bool], f: Int => Bits): Bits =
    (0 until records).map(i => f(i).andMask(hits(i))).reduce(_ | _)

  def clear(hits: Vec[Bool]): Unit =
    for (i <- 0 until records) when(hits(i)) { valid(i) := False }

  def killWaySet(w: UInt, s: UInt): Unit =
    for (i <- 0 until records)
      when(valid(i) && (way(i) === w) && (set(i) === s)) { valid(i) := False }

  def killAll(): Unit = for (i <- 0 until records) valid(i) := False
}
