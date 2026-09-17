package m68k040

/** robId values for directed tests, DERIVED from the ROB depth instead of written out.
  *
  * WHY THIS FILE EXISTS. Several specs were written when the ROB was 64 deep and picked
  * their robIds as bare literals in 32..63 -- "a big id, obviously distinct from the
  * small ones this test already uses". `Global.ROB_DEPTH_DEFAULT` later became 32, every
  * robId PORT narrowed to `ROB_ID_W_DEFAULT` = 5 bits, and each of those literals became
  * an out-of-range poke that aborts the run at stimulus-setup time:
  *
  *     ASSIGNMENT ERROR : 40 is outside the range of ... : UInt[5 bits]
  *     [Error] Simulation failed at time=220
  *
  * That happens BEFORE the DUT does anything, so the suites read as behavioural failures
  * when no behaviour ran at all. On 2026-09-18 that was ELEVEN of the seventeen
  * `m68k040.ls` reds and BOTH `m68k040.mmu` reds -- including two that had been carried
  * on a known-red list as if they were real.
  *
  * WHY NOT JUST SMALLER LITERALS. Because that is the same bomb with a longer fuse: it
  * re-breaks on the next depth change, in exactly the same silent way, and the next
  * person also has to prove their change was not responsible. Every value here is a
  * function of the depth, so the next change edits this file and nothing else.
  *
  * WHAT THE OLD LITERALS ACTUALLY MEANT, preserved here as named intent:
  *   - 63 was "the widest id" -- a NEUTRAL age qualifier, see `maxId`.
  *   - 32..55 were "ids that cannot collide with the small ones" -- see `highBlock`.
  *   - a lone different id (e.g. a post-flush ROB head) -- see `otherThan`.
  */
object TestRobIds {

  /** The id space every robId port in the design is sized for. */
  val depth: Int = Global.ROB_DEPTH_DEFAULT

  /** The widest value a robId port can carry.
    *
    * `StoreQueue.olderThan(a, b)` compares `(a - robHeadIn)` against `(b - robHeadIn)`,
    * so with the usual test head of 0 the maximum id is "youngest possible" -- an age
    * qualifier that excludes nothing. That is precisely what the old literal 63 meant in
    * the 6-bit space, and it is why this is `(1 << width) - 1` rather than a number. */
  val maxId: Int = (1 << Global.ROB_ID_W_DEFAULT) - 1

  /** `count` distinct, ASCENDING ids packed against the TOP of the id space.
    *
    * Ascending order is load-bearing, not cosmetic: with a test head of 0 the id IS the
    * age, so a caller that wants "these stores are in program order" gets that for free,
    * exactly as the old ascending literals did. Packing at the top keeps them clear of
    * the small ids (0..~15) the same tests use for unrelated traffic -- the property the
    * 32..63 literals were reaching for. */
  def highBlock(count: Int): IndexedSeq[Int] = {
    require(count >= 1 && count < depth,
      s"TestRobIds.highBlock($count): a ROB of depth $depth cannot supply $count distinct ids " +
      s"below the reserved sentinel")
    // STRICTLY BELOW `maxId`. Reserving the top id is not tidiness: `maxId` is the
    // neutral "youngest possible" forwarding query, and `StoreQueue.olderThan` is a
    // STRICT compare (`ageA < ageB`), so an entry that shares the sentinel's id is NOT
    // older than the query and silently drops out of the age-qualified barrier. Handing
    // the sentinel out as an ordinary id cost one real regression
    // (StoreQueueDcacheDrainPipelineSpec's "COPYBACK miss and WRITETHROUGH descriptors
    // form ordered barriers") before this guard existed.
    (depth - 1 - count) until (depth - 1)
  }

  /** A valid id that is NOT `id` -- for "the ROB head moved on to something else". */
  def otherThan(id: Int): Int = {
    require(depth >= 2, "TestRobIds.otherThan needs a ROB deeper than one entry")
    (id + 1) % depth
  }
}
