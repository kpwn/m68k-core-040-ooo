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

  /** Read: hit ONLY when the entry is valid AND owned by this exact {way,set,word}. */
  def lookup(idx: UInt, way: UInt, set: UInt, word: UInt): (Bool, UInt) = {
    val hit = valid(idx) && (ownWay(idx) === way) && (ownSet(idx) === set) && (ownWord(idx) === word)
    (hit, lenWords(idx))
  }

  /** Allocate an entry for a newly resolved straddle; returns the index used. */
  def allocate(way: UInt, set: UInt, word: UInt, len: UInt): UInt = {
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
