package m68k040.cache

import spinal.core._
import spinal.lib._

/** Side table holding REFILL-RESOLVED lengths for line-straddling instructions.
  *
  * See DESIGN_icache_straddle_token.md. A word whose length could not be determined
  * at refill (its disambiguating extension word lay past the 64-byte line boundary)
  * is marked `ambiguousLine` and, once resolved, carries a TOKEN into this table
  * instead of a length. The table is deliberately tiny: only the TAIL instruction of
  * a line can straddle, and only lines actually resolved allocate an entry.
  *
  * ── Why a token + side table, and not a corrected length written back into the line
  *
  * Every failure mode here must degrade to "resolve it live once more", never to a
  * WRONG length. Writing a corrected length back into `lineMem` has a dangerous
  * direction: if the line is replaced between resolve and write, the write lands in
  * a LIVE line and the frontend frames garbage. With a token, the pointer lives in
  * the line's own predecode, so it dies with the line on eviction; the worst case
  * becomes a stale READ, which `ownerKey` catches.
  *
  * ── The ownerKey invariant
  *
  * An entry is only trusted when its `ownerKey` matches the {way,set,word} being
  * looked up. That makes entry RECYCLING safe without any coordination: if entry K
  * is reallocated to a different line, an old token pointing at K simply misses.
  */
case class StraddleTable(entries: Int, wayBits: Int, setBits: Int, wordBits: Int) extends Area {
  require(entries > 0 && isPow2(entries), "entries must be a power of two")
  val idxBits = log2Up(entries)

  val valid     = Vec.fill(entries)(RegInit(False))
  val ownWay    = Vec.fill(entries)(Reg(UInt(wayBits bits)))
  val ownSet    = Vec.fill(entries)(Reg(UInt(setBits bits)))
  val ownWord   = Vec.fill(entries)(Reg(UInt(wordBits bits)))
  val lenWords  = Vec.fill(entries)(Reg(UInt(4 bits)))

  /** Round-robin allocation. No LRU: entries are cheap and a wrong eviction only
    * costs one live re-classify. */
  val allocPtr = RegInit(U(0, idxBits bits))

  /** Read by INDEX (token form): hit only when valid AND owned by this {way,set,word}. */
  def lookup(idx: UInt, way: UInt, set: UInt, word: UInt): (Bool, UInt) = {
    val hit = valid(idx) && (ownWay(idx) === way) && (ownSet(idx) === set) && (ownWord(idx) === word)
    (hit, lenWords(idx))
  }

  /** Read by ADDRESS -- the form actually used on the fetch path.
    *
    * Keying the table by {way,set,word} rather than pointing at it with a token
    * stored in the line means the I-cache line array is NEVER written to publish a
    * resolution: no read-modify-write of the 352-bit lineMem entry, no lazy
    * write-back queue, and no window in which a stale fix-up can land in a line that
    * has since been replaced. A recycled line simply stops matching.
    *
    * 16 entries x a 13-bit compare, fully parallel -- it replaces a 30-logic-level
    * classifier on the fetch critical path, so the comparator cost is not the point.
    *
    * At most ONE entry can match: `allocate` refuses to create a duplicate key, and
    * every key written names a distinct {way,set,word}. */
  def lookupByKey(way: UInt, set: UInt, word: UInt): (Bool, UInt) = {
    val hits = Vec((0 until entries).map(i =>
      valid(i) && (ownWay(i) === way) && (ownSet(i) === set) && (ownWord(i) === word)))
    val hit  = hits.asBits.orR
    // OR-reduce the masked lengths: at most one `hits` bit is set (see above), so this
    // is a one-hot select. Deliberately NOT MuxOH -- if the no-duplicate-key invariant
    // were ever broken, MuxOH silently ORs the SET BITS of several entries into a
    // plausible-looking wrong length, whereas this makes `hit` still true and the
    // length a detectable mix. The frontend treats any doubt as "resolve live", and
    // StraddleTableSpec pins the single-match property directly.
    val len  = lenWords.zip(hits).map { case (l, h) => l.asBits.andMask(h) }
                       .reduce(_ | _).asUInt
    (hit, len)
  }

  /** Allocate an entry for a newly resolved straddle; returns the index used.
    *
    * Invalidates any existing entry with the SAME key first, so `lookupByKey` can
    * never see two matches. Re-resolving the same straddle (the line was evicted and
    * refetched, say) therefore replaces rather than duplicates. */
  def allocate(way: UInt, set: UInt, word: UInt, len: UInt): UInt = {
    for (k <- 0 until entries)
      when(valid(k) && (ownWay(k) === way) && (ownSet(k) === set) && (ownWord(k) === word)) {
        valid(k) := False
      }
    val i = allocPtr
    valid(i)    := True
    ownWay(i)   := way
    ownSet(i)   := set
    ownWord(i)  := word
    lenWords(i) := len.resize(4)
    allocPtr    := i + 1
    i
  }

  /** Kill every entry owned by a {way,set} that is being refilled. The token in the
    * old line is about to be overwritten by fresh predecode anyway; this keeps the
    * table from holding entries no token can reach. */
  def killWaySet(way: UInt, set: UInt): Unit =
    for (i <- 0 until entries)
      when(valid(i) && (ownWay(i) === way) && (ownSet(i) === set)) { valid(i) := False }

  /** CINV / CPUSHA: drop everything. */
  def killAll(): Unit = for (i <- 0 until entries) valid(i) := False
}
