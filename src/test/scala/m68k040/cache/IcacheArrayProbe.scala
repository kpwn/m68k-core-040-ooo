package m68k040.cache

import spinal.core.ClockDomain
import spinal.core.sim._

/** Raw cache-array read helpers, shared by IcacheSpec / IcachePrefetchSpec.
  *
  * WHY THIS EXISTS (implementation plan Task 3, spec risk R5). The corruption tests
  * in those two files read `lineMem`/`tagMem`/`valids` raw (`lineMem` was `dataMem`
  * before Task 5 renamed it, and predecode lived in its own array until Task 6) and
  * assert an UNRELATED way's content is byte-for-byte unchanged. They are the tests that caught
  * the real `victim`-pointer corruption bug (IcachePlugin.scala:216-232), so they must
  * survive the Unified Fetch Array restructure intact -- but that restructure changes
  * every array address they use. Routing all raw reads through here means the RTL
  * change touches ONE file's worth of accessors instead of ~30 call sites, and the
  * test change lands in a SEPARATE, independently reviewable commit against the OLD
  * RTL (which is what this file is, as of Task 3).
  *
  * DELIBERATELY BEAT-GRANULAR. `wayPred` took a `beat` from Task 3 onward even while the
  * predecode storage was still a separate line-granular array; the helper did the
  * slicing. M1 folded predecode into the data array, so the address really is
  * beat-granular now and only this file changed.
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
    * M1a/M1b: reads the Unified Fetch Array's INLINE predecode field. The accessor
    * switch itself landed early, in Task 5 (M1a) -- it could not wait, because
    * `IcacheUnifiedArraySpec`'s array-sweep half compared this accessor against the
    * shadow array and would otherwise have been comparing that array with itself.
    * Task 6 (M1b) completed the rest: the shadow array, its S1 capture bank, the in-RTL
    * equivalence monitor and the shadow-comparison half of oracle 4 are all deleted, so
    * this is now the ONLY predecode storage in the design. The signature is unchanged
    * from the pre-M1 line-granular form -- that was the whole point of making this
    * helper beat-granular in Task 3. */
  def wayPred(ic: IcachePlugin, way: Int, set: Int, beat: Int): BigInt = {
    require(beat == 0 || beat == 1, s"beat must be 0 or 1, got $beat")
    ic.logic.lineMem(way).getBigInt(set * 2 + beat) >> 256
  }

  /** MSHR CONTROL-file accessors (Task 9 / M2c). Entry 0 is the demand MSHR
    * (`AxiIds.I_DEMAND`); entries `I_SPEC_BASE .. I_SPEC_LAST` are the speculative
    * slots. `IcachePlugin.MSHR_N` is `1 + AxiIds.I_SPEC_SLOTS` -- note that is FIVE on
    * the current `AxiIds`, not the six the plan's prose assumes (I_SPEC_SLOTS == 4);
    * `mshrEntries` is the single place that arithmetic lives.
    *
    * `mshrValid(0)` means "the demand MSHR is live and owns `mshrSet(0)`" -- it spans
    * REFILL, the demand install dwell, REPLAY and FAULT. `mshrComplete(0)` is never
    * written by the RTL (the demand path's completion is the FSM's `refillDone`), so do
    * not build a liveness predicate on it. */
  def mshrEntries: Int = 1 + AxiIds.I_SPEC_SLOTS

  def mshrValid(ic: IcachePlugin, idx: Int): Boolean    = ic.logic.mshrValid(idx).toBoolean
  def mshrSet(ic: IcachePlugin, idx: Int): Int          = ic.logic.mshrSet(idx).toInt
  def mshrArSent(ic: IcachePlugin, idx: Int): Boolean   = ic.logic.mshrArSent(idx).toBoolean
  def mshrComplete(ic: IcachePlugin, idx: Int): Boolean = ic.logic.mshrComplete(idx).toBoolean

  /** Speculative-slot views, so `IcachePrefetchSpec`'s existing per-slot assertions
    * survive the demand/speculative unification with one rename in one place. `i` is a
    * SLOT index (0 .. I_SPEC_SLOTS-1), not an MSHR index. */
  def pfSlotValid(ic: IcachePlugin, i: Int): Boolean    = mshrValid(ic, i + AxiIds.I_SPEC_BASE)
  def pfSlotArSent(ic: IcachePlugin, i: Int): Boolean   = mshrArSent(ic, i + AxiIds.I_SPEC_BASE)
  def pfSlotComplete(ic: IcachePlugin, i: Int): Boolean = mshrComplete(ic, i + AxiIds.I_SPEC_BASE)
  def pfSlotSet(ic: IcachePlugin, i: Int): Int          = mshrSet(ic, i + AxiIds.I_SPEC_BASE)

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

/** Shared fetch driver: present one command, wait for it to fire, wait for its
  * response, and return the WHOLE observed payload (not just `data`).
  *
  * WHY (implementation plan Task 6 Step 1's implementer note). `IcacheSpec.fetch`
  * returns only the 64-bit data word, so it cannot serve the M1b read-path tests, which
  * have to inspect `fault` and the four delivered predecode chunks. Rather than
  * duplicating a second private helper in a second spec file, the extended form lives
  * here, beside the raw-array accessors it is always used with.
  *
  * PRECONDITION, same as `IcacheSpec.fetch`: exactly ONE outstanding command at a time.
  * `rspOut` is a Flow with no back-pressure, so a pipelined caller would race its own
  * responses.
  */
object IcacheFetchDriver {

  /** One observed `FetchRsp`. `pred(k)` slices the flat `rspPredBits` mirror the
    * `FetchProbePlugin` exposes; chunk 0 occupies the LOW `chunkBits` bits. */
  final case class Rsp(pc: BigInt, data: BigInt, fault: Boolean, atc: Boolean,
                       predBits: BigInt, chunkBits: Int) {
    def pred(k: Int): BigInt = {
      require(k >= 0 && k < 4, s"chunk index must be 0..3, got $k")
      (predBits >> (chunkBits * k)) & ((BigInt(1) << chunkBits) - 1)
    }
    def describe: String =
      f"pc=0x${pc.toString(16)} data=0x${data.toString(16)} fault=$fault atc=$atc " +
      s"pred=[${(0 until 4).map(k => pred(k).toString(16)).mkString(",")}]"
  }

  def fetchAndWait(ic: IcachePlugin, probe: FetchProbePlugin, cd: ClockDomain,
                   pc: Long): Rsp = {
    probe.logic.cmdIn.valid      #= true
    probe.logic.cmdIn.payload.pc #= pc
    cd.waitSamplingWhere(probe.logic.cmdIn.ready.toBoolean && probe.logic.cmdIn.valid.toBoolean)
    probe.logic.cmdIn.valid #= false
    cd.waitSamplingWhere(probe.logic.rspOut.valid.toBoolean)
    Rsp(pc        = probe.logic.rspOut.payload.pc.toBigInt,
        data      = probe.logic.rspOut.payload.data.toBigInt,
        fault     = probe.logic.rspOut.payload.fault.toBoolean,
        atc       = probe.logic.rspOut.payload.atc.toBoolean,
        predBits  = probe.logic.rspPredBits.toBigInt,
        chunkBits = ic.logic.PRED_BITS_PER_WORD)
  }
}
