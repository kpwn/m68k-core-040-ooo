package m68k040.cache

import spinal.core.sim._

/** Raw cache-array read helpers, shared by IcacheSpec / IcachePrefetchSpec.
  *
  * WHY THIS EXISTS (implementation plan Task 3, spec risk R5). The corruption tests
  * in those two files read `dataMem`/`predMem`/`tagMem`/`valids` raw and assert an
  * UNRELATED way's content is byte-for-byte unchanged. They are the tests that caught
  * the real `victim`-pointer corruption bug (IcachePlugin.scala:216-232), so they must
  * survive the Unified Fetch Array restructure intact -- but that restructure changes
  * every array address they use. Routing all raw reads through here means the RTL
  * change touches ONE file's worth of accessors instead of ~30 call sites, and the
  * test change lands in a SEPARATE, independently reviewable commit against the OLD
  * RTL (which is what this file is, as of Task 3).
  *
  * DELIBERATELY BEAT-GRANULAR. `wayPred` takes a `beat` even though today's `predMem`
  * is line-granular; the helper does the slicing. After M1 folds predecode into the
  * data array the address really is beat-granular and only this file changes.
  */
object IcacheArrayProbe {

  /** All four raw arrays' content for one (way, set), for before/after comparison. */
  case class WaySnapshot(data: Seq[BigInt], pred: Seq[BigInt], tag: BigInt, valid: Boolean) {
    def describe: String =
      s"data=[${data.map(_.toString(16)).mkString(",")}] " +
      s"pred=[${pred.map(_.toString(16)).mkString(",")}] " +
      s"tag=0x${tag.toString(16)} valid=$valid"
  }

  /** 256-bit instruction-byte content of one beat of one (way, set).
    * M1a: reads the Unified Fetch Array's low 256 bits (`lineMem` replaced `dataMem`). */
  def wayData(ic: IcachePlugin, way: Int, set: Int, beat: Int): BigInt = {
    require(beat == 0 || beat == 1, s"beat must be 0 or 1, got $beat")
    val entry = ic.logic.lineMem(way).getBigInt(set * 2 + beat)
    entry & ((BigInt(1) << 256) - 1)
  }

  /** PRED_BITS_PER_BEAT-bit predecode content of one beat of one (way, set).
    *
    * M1a: reads the Unified Fetch Array's INLINE predecode field, NOT the (still
    * present, shadow) `predMem`. Deviation from the plan's Task 5 Step 7 note, which
    * said this accessor keeps reading `predMem` until Task 6 -- it cannot, because
    * `IcacheUnifiedArraySpec`'s array-sweep half compares this accessor against
    * `predMem` and would then be comparing `predMem` with itself. Task 5 Step 1's own
    * text ("that half is written against IcacheArrayProbe ... rather than against
    * predMem directly") only makes sense with the accessor already switched, and the
    * plan is explicit elsewhere that a tautological oracle is worthless. Switching it
    * here also makes IcacheSpec/IcachePrefetchSpec's corruption tests exercise the new
    * array immediately, which is strictly more coverage. Task 6's Step 4 is therefore
    * already satisfied when it is reached. The signature is unchanged -- that was the
    * whole point of making this helper beat-granular in Task 3. */
  def wayPred(ic: IcachePlugin, way: Int, set: Int, beat: Int): BigInt = {
    require(beat == 0 || beat == 1, s"beat must be 0 or 1, got $beat")
    ic.logic.lineMem(way).getBigInt(set * 2 + beat) >> 256
  }

  def wayTag(ic: IcachePlugin, way: Int, set: Int): BigInt =
    ic.logic.tagMem(way).getBigInt(set)

  def wayValid(ic: IcachePlugin, way: Int, set: Int): Boolean =
    ic.logic.valids(way)(set).toBoolean

  def snapshotWay(ic: IcachePlugin, way: Int, set: Int): WaySnapshot =
    WaySnapshot(
      data  = Seq(0, 1).map(b => wayData(ic, way, set, b)),
      pred  = Seq(0, 1).map(b => wayPred(ic, way, set, b)),
      tag   = wayTag(ic, way, set),
      valid = wayValid(ic, way, set))

  /** Assert an unrelated way's content is byte-for-byte unchanged. The message names
    * the exact field that moved, because "something changed" is useless in a
    * corruption test. */
  def assertUnchanged(before: WaySnapshot, after: WaySnapshot, what: String): Unit = {
    assert(before.data == after.data,
      s"$what: lineMem data CORRUPTED -- before ${before.describe} after ${after.describe}")
    assert(before.pred == after.pred,
      s"$what: predecode CORRUPTED -- before ${before.describe} after ${after.describe}")
    assert(before.tag == after.tag,
      s"$what: tag changed -- before 0x${before.tag.toString(16)} after 0x${after.tag.toString(16)}")
    assert(before.valid == after.valid,
      s"$what: valid changed -- before ${before.valid} after ${after.valid}")
  }
}
