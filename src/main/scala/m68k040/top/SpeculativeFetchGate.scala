package m68k040.top

import m68k040.cache.IcachePlugin
import m68k040.decode.DecodeStage
import m68k040.frontend.FetchAlignPlugin
import m68k040.rename.RenameStage
import m68k040.rob.RobPlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.PluginHost

/** Drives `IcachePlugin.logic.nonSpecFetch` — the I-side cache-inhibited speculation
  * gate — for a DUT that actually contains a speculating frontend.
  *
  * ==What it is for==
  * MC68040 UM §3.1.2/§4: a cache-inhibited page denotes a DEVICE, and a device read has
  * an architecturally visible side effect, so it must not be performed speculatively.
  * `LsEuPlugin.p4LaunchOk` enforces that for D-side loads by waiting for the ROB head.
  * An instruction fetch has no ROB entry to be the head of — it is what CREATES ROB
  * entries — so "at the head" has to be re-expressed. This object computes the
  * equivalent:
  *
  * > **The whole machine, from the instruction buffer to the ROB, holds nothing.**
  *
  * ==Why that is sufficient, argued rather than asserted==
  * `fetchPc` can only turn out to be wrong-path if something REDIRECTS it, and every
  * redirect source in this core is an instruction that is older than the fetch:
  *   - a fetch-time BTB/RAS prediction, from an opword already resident in the IBuf or
  *     already presented by the aligner (`res.slot0Valid`);
  *   - a decode-side resume (`ucComplexResumeValidReg`);
  *   - a branch resolved in `BranchEuPlugin` (Tier 1 `earlyPend`) or at retire
  *     (`doFlushReg`), both of which require a live ROB entry;
  *   - a commit-time exception (`excActive`).
  * If NONE of those places holds anything, then every instruction ever fetched has
  * already retired and no redirect can be produced, so `fetchPc` is by elimination the
  * architectural successor of the last retired instruction. The fetch is then exactly as
  * non-speculative as a D-side access at the ROB head.
  *
  * ==Why it cannot deadlock==
  * Blocking the fetch is what CAUSES the drain: nothing new enters the machine, so the
  * IBuf, the decode queue and the ROB all empty out and the predicate becomes true. Two
  * traps were avoided deliberately:
  *   - `ibuf.io.cnt === 0` is NOT a term. An instruction can straddle two fetch windows,
  *     leaving words resident that cannot yet form a uop; requiring an empty IBuf would
  *     deadlock exactly there. `!res.slot0Valid` is the right term: it says the aligner
  *     cannot currently produce an instruction, which is precisely the case where more
  *     words are genuinely needed AND the case where nothing resident can redirect.
  *   - `pendingDrop === 0` is NOT a term. `pendingDrop` is a leading-word drop intent
  *     that is only consumed BY the next fetch firing, so requiring it to be zero would
  *     deadlock any inhibited fetch that follows a redirect to an odd-word address.
  *
  * ==Shape==
  * The wide reduction is REGISTERED, and only `ringCount === 0` stays combinational.
  * That split is load-bearing in both directions:
  *   - registering the rest is safe because, on any cycle following a fully drained one,
  *     the ONLY thing that can have re-filled the machine is a fetch response — and that
  *     requires `ringCount =/= 0` on the drained cycle, a contradiction;
  *   - `ringCount === 0` must stay live, or the cycle right after an inhibited fetch is
  *     accepted would still see the stale registered `True` and let a second, genuinely
  *     speculative run-ahead fetch through.
  */
object SpeculativeFetchGate {

  /** Wire the gate. Call from the full-core wiring plugin's `during build` Area, after
    * every referenced plugin's own `logic` Area exists (the wiring plugin is last in the
    * plugin list in every DUT that uses this). */
  def wire(host: PluginHost): Unit = {
    val ic  = host[IcachePlugin]
    val fa  = host[FetchAlignPlugin]
    val dec = host[DecodeStage]
    val ren = host[RenameStage]
    val rob = host[RobPlugin]

    val fl = fa.logic
    val dl = dec.logic
    val rl = ren.logic
    val ol = rob.logic

    // ── the frontend holds no instruction and is not redirecting ────────────────
    val frontendQuiet =
      (fl.ringCount === 0) &&            // no I-cache request outstanding
      !fl.redirectThisCycle &&           // redirect / resume+stalled / mispredict /
                                         //   predictFire / ftqMismatch, all five
      !fl.predictPending &&
      !fl.ftqMismatchPending &&
      !fl.targetHoldValid &&             // no held (backpressured) fetch target
      (fl.ftqCount === 0) &&             // no live fetch-target plan
      !fl.res.slot0Valid &&              // the aligner cannot form an instruction
      !fl.feed.valid &&                  // nothing being handed to decode
      !fl.stalled && !fl.quiesce && !fl.faultHold

    // ── decode holds no uop ─────────────────────────────────────────────────────
    // Four registered stream stages plus the MicroOpQueue plus every multi-cycle
    // expander's active/pending pair. `queue.io.pop.valid` IS `count =/= 0`
    // (MicroOpQueue.scala:109); the count register itself is not a port and must not be
    // reached into from here.
    val decodeQuiet =
      !dl.rawIn.valid && !dl.raw.valid && !dl.fed.valid && !dl.pushReg.valid &&
      !dl.queue.io.pop.valid &&
      !dl.stashValid &&
      !dl.movemActive && !dl.movemPendValid &&
      !dl.movemSnapPhase && !dl.movemSnapIdxPending && !dl.movemAnUpdPhase &&
      !dl.movepActive && !dl.movepPendValid &&
      !dl.ucActive && !dl.ucPendValid &&
      !dl.fmovemxActive &&
      !dl.ucComplexResumeValidReg        // a decode->fetch resume redirect in waiting

    // ── rename holds no uop ─────────────────────────────────────────────────────
    // `uopsStaged` (RenameStage.scala:470) is a real 1-deep register, so `rob.count`
    // lags `du.uops.fire` by at least one cycle: omitting it would leave a window in
    // which a fully renamed branch exists with an EMPTY ROB.
    val renameQuiet = !rl.uopsPort.valid && !rl.uopsStaged.valid

    // ── the ROB holds nothing and owes no flush ─────────────────────────────────
    // `flushing` already folds `flush.valid || doFlushReg || excSquash`. The three
    // `early*` signals are the Tier-1 (EU-resolution-time) reschedule, which is
    // deliberately live ACROSS the flushing cycle that zeroes `count`.
    val robQuiet =
      (ol.count === 0) && !ol.flushing &&
      !ol.earlyPend && !ol.earlyFire && !ol.earlySuppressFe

    val drainedRaw = frontendQuiet && decodeQuiet && renameQuiet && robQuiet
    val drainedQ   = RegNext(drainedRaw) init False
    drainedRaw.simPublic(); drainedQ.simPublic()

    ic.logic.nonSpecFetch := drainedQ && (fl.ringCount === 0)
  }
}
