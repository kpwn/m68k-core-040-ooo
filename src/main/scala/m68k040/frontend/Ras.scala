package m68k040.frontend

import m68k040.Global
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin

/** Return-address stack (RAS) for RTS/RTR prediction (frontend predictor, slice 2).
  *
  * A small circular stack of return PCs in the fetch front-end, parallel to the
  * BtbPlugin (NOT entangled with it). On a fetched call (BSR/JSR) FetchAlign pushes
  * the call's fall-through PC; on a fetched return (RTS/RTR) it pops + predicts the
  * top-of-stack as the return target. The prediction rides the EXISTING slice-1
  * predTaken/predTarget carry-down to the branch EU, which always verifies the real
  * return target (loaded from the stack) — so a corrupt RAS is only a perf loss,
  * never a correctness bug (recovery = the existing commit-time redirect).
  *
  * Recovery scheme = CHECKPOINT/RESTORE (2026-08-28, independent of the
  * `0x40800284` wild-jump investigation that surfaced this gap as a side effect —
  * see the commit message). The ORIGINAL design deliberately accepted corruption:
  * speculative rasSp/count/contents were never checkpointed or restored on a flush,
  * reasoning that the EU always verifies the real RTS/RTR target so a single wrong
  * guess is only a perf loss. That reasoning is TRUE for one wrong guess, but false
  * for the aggregate: because nothing ever rolled rasSp/count back, EVERY
  * misprediction anywhere in the program permanently walked the speculative
  * pointer/occupancy away from architectural reality (wrong-path pushes/pops from an
  * eventually-flushed instruction sequence are never undone), so prediction QUALITY
  * degraded unboundedly for the rest of execution (until the next full I-cache
  * invalidate blindly wiped it) even though architectural correctness stayed
  * protected by the EU-verify.
  *
  * Fix: a checkpoint/restore pair (`checkpointSave` / `checkpointRestore`), mirroring
  * this codebase's established idiom for OTHER speculative frontend/rename state —
  * RenameStage's RatTable/Freelist keep a "committed" shadow that is copied back into
  * the speculative table on a flush, rather than a per-branch checkpoint stack (see
  * `RenameStage.scala`'s doc comment: "flush: rollback all RATs + flush all
  * freelists"). The RAS has no cheap way to know, from inside the frontend alone,
  * exactly which in-flight instruction is "the one that will retire safely next" (no
  * per-instruction retire-linked commit signal is threaded down to it — adding one
  * would be real ROB-side plumbing, out of scope for this narrowly-scoped predictor
  * fix), so the wiring layer approximates RenameStage's precise "committed shadow"
  * with a conservative, always-sound proxy: refresh the checkpoint (`checkpointSave`)
  * whenever the ROB is completely drained (`rob.count === 0`) — at that instant
  * NOTHING is outstanding/unresolved, so the live RAS state is, by construction,
  * exactly the architecturally correct one (module the few cycles of fetch->dispatch
  * pipeline latency, an accepted, bounded approximation). `checkpointRestore` fires on
  * any event that discards everything younger than a known-good point: the ROB's own
  * commit-time flush (`RedirectService.doFlush`, wired at `BackendWiringPlugin`) and
  * the frontend's own `ftqMismatch` recovery (a fetch-directed re-framing correction
  * that never reaches the ROB at all, so it needs its own restore trigger). This
  * bounds RAS drift to "whatever spans the last ROB-drain to the next flush" instead
  * of the previous unbounded lifetime-of-the-program drift — a strict, provable
  * improvement, though not a per-branch-precise one (see the checkpointSave/Restore
  * port doc below for the full soundness argument).
  *
  * Content, not just the pointer: a NAIVE version of this fix would checkpoint only
  * `rasSp`/`count` (the pointer/occupancy), reasoning that entries below a restored
  * `rasSp` are untouched "real" data that just becomes reachable again. That
  * reasoning is sound ONLY if wrong-path speculation between a checkpoint and its
  * restore can never PUSH more than `entries` times (i.e. never wraps the circular
  * buffer and overwrites a slot the restored pointer still needs). This core's ROB is
  * 64 deep (`RobPlugin.depth`) while the RAS defaults to 16 entries
  * (`Global.RAS_ENTRIES`), so a sustained wrong-path run of back-to-back calls
  * CAN wrap the buffer more than once before a flush corrects it. Rather than
  * depend on that being rare, the checkpoint captures the FULL `entries`x32-bit `ras`
  * array too (`ckRas`) — the extra storage is the same order of magnitude as this
  * design already spends on much larger RAT/Freelist checkpoints elsewhere, so the
  * area cost is negligible and the wraparound question is closed for free instead of
  * merely bounded.
  *
  * Interface (directionless plain wires, the BtbPlugin/IcachePlugin convention —
  * driven/read by the wiring layer; idle-defaulted with concrete zeros so a
  * standalone DUT elaborates):
  *  - pushValid / pushRetPc : push the return PC on a fetched call (DRIVEN by FetchAlign).
  *  - popValid              : pop on a fetched (predicted) return (DRIVEN by FetchAlign).
  *  - predValid (comb)      : count > 0 — a return can be predicted this cycle (OUTPUT).
  *  - predTarget (comb)     : ras[rasSp-1], the top-of-stack return PC (OUTPUT).
  *  - invalidateAll         : clear count/rasSp (driven from the I-cache invalidate).
  *  - checkpointSave        : copy the CURRENT (post this-cycle's push/pop) rasSp/
  *    count/ras into the checkpoint shadow (ckRasSp/ckCount/ckRas). Driven from the
  *    wiring layer whenever the ROB is fully drained (see the doc comment above).
  *  - checkpointRestore     : copy the checkpoint shadow back into rasSp/count/ras,
  *    undoing any wrong-path push/pop since the last save. Driven from the wiring
  *    layer on RedirectService.doFlush (the ROB's commit-time correction) OR
  *    FetchAlignPlugin's ftqMismatch (a frontend-only re-framing correction). These
  *    two conditions are independent (a ROB-drain and an ftqMismatch CAN coincide),
  *    so the wiring layer explicitly ANDs `!checkpointRestore`'s condition into
  *    `checkpointSave`'s so the two ports are never driven together (checked in sim
  *    below); checkpointRestore is ALSO given last-assignment priority in the RTL
  *    itself, so a save-then-immediately-restore is always well-defined (the save's
  *    own write is simply overridden) even if a future driver ever violated that.
  *
  * At most ONE push OR one pop per cycle (an instruction is a call XOR a return, and
  * the aligner emits at most one predicted-redirecting slot/cycle) -> single-ported. */
class RasPlugin extends FiberPlugin {

  val logic = during build new Area {
    val entries = Global.RAS_ENTRIES.get
    require((entries & (entries - 1)) == 0, "rasEntries must be a power of two")
    val spBits  = log2Up(entries)            // pointer width (wraps)
    val cntBits = log2Up(entries) + 1        // occupancy 0..entries (saturating)

    // ---- storage: a 16x32 register Vec (1-cycle combinational read; tiny) ----
    // RegInit so SpinalSim does not seed-randomize the contents (a head read of an
    // entry while count claims it valid would otherwise be flaky garbage).
    val ras   = Vec.fill(entries)(RegInit(U(0, 32 bits)))
    val rasSp = RegInit(U(0, spBits bits))   // speculative top-of-stack (next push slot)
    val count = RegInit(U(0, cntBits bits))  // saturating occupancy (0..entries)
    rasSp.simPublic(); count.simPublic()

    // ---- checkpoint shadow (rollback-on-flush, see the class doc comment) ----
    val ckRas   = Vec.fill(entries)(RegInit(U(0, 32 bits)))
    val ckRasSp = RegInit(U(0, spBits bits))
    val ckCount = RegInit(U(0, cntBits bits))
    ckRasSp.simPublic(); ckCount.simPublic()

    // ---- ports (directionless plain wires, idle-defaulted with concrete zeros) ----
    val pushValid = Bool();        pushValid.allowOverride;  pushValid := False
    val pushRetPc = UInt(32 bits); pushRetPc.allowOverride;  pushRetPc := U(0, 32 bits)
    val popValid  = Bool();        popValid.allowOverride;   popValid  := False
    val invalidateAll = Bool();    invalidateAll.allowOverride; invalidateAll := False
    val checkpointSave    = Bool(); checkpointSave.allowOverride;    checkpointSave    := False
    val checkpointRestore = Bool(); checkpointRestore.allowOverride; checkpointRestore := False
    // sim-only debug visibility (directed lock-step RAS-prediction-accuracy tests):
    // these otherwise have no OTHER reason to stay a stable, prunable-proof handle.
    pushValid.simPublic(); pushRetPc.simPublic(); popValid.simPublic()
    checkpointSave.simPublic(); checkpointRestore.simPublic()

    // ---- combinational predict read: top-of-stack, valid iff non-empty ----
    val predValid  = (count =/= U(0, cntBits bits))
    val predTarget = ras(rasSp - U(1, spBits bits))
    predValid.simPublic(); predTarget.simPublic()

    val cntMax = U(entries, cntBits bits)

    // ---- combinational NEXT rasSp/count (post this-cycle's push/pop, pre
    //      invalidate/restore). Computed explicitly (rather than only inside the
    //      `when(pushValid)`/`when(popValid)` blocks below) so `checkpointSave` can
    //      capture "the state as of the end of THIS cycle" regardless of whether the
    //      save pulse lands on the SAME cycle as a push/pop (this instruction's own
    //      effect must be included in what gets saved) or a later, quiet cycle (the
    //      value simply passes through unchanged). ----
    val nextRasSp = UInt(spBits bits); nextRasSp := rasSp
    val nextCount = UInt(cntBits bits); nextCount := count

    // ---- push (call): ras[rasSp] := retPc ; rasSp++ ; count := min(count+1, entries).
    //      Overflow (push when full) is circular — the pointer wraps and overwrites the
    //      oldest; count saturates. A hint structure; EU-verify covers a wrong guess.
    when(pushValid) {
      ras(rasSp) := pushRetPc
      nextRasSp  := rasSp + U(1, spBits bits)
      when(count =/= cntMax) { nextCount := count + U(1, cntBits bits) }
    }
    // ---- pop (predicted return): rasSp-- ; count := max(count-1, 0). FetchAlign only
    //      asserts popValid when predValid (count>0), so this never underflows the
    //      saturation; the guard is belt-and-suspenders. push XOR pop (call XOR return),
    //      so the two never collide on the same cycle.
    when(popValid) {
      nextRasSp := rasSp - U(1, spBits bits)
      when(count =/= U(0, cntBits bits)) { nextCount := count - U(1, cntBits bits) }
    }
    rasSp := nextRasSp
    count := nextCount

    // ---- invalidateAll: clear occupancy (and the pointer) on an I-cache flush ----
    when(invalidateAll) {
      count := U(0, cntBits bits)
      rasSp := U(0, spBits bits)
    }

    // ---- checkpointSave: snapshot the post-this-cycle rasSp/count/ras into the
    //      checkpoint shadow. See the class doc comment for WHEN the wiring layer
    //      drives this (ROB fully drained -> nothing outstanding -> live state is
    //      architecturally correct by construction). ----
    when(checkpointSave) {
      ckRasSp := nextRasSp
      ckCount := nextCount
      for (i <- 0 until entries) {
        ckRas(i) := Mux(pushValid && (rasSp === U(i, spBits bits)), pushRetPc, ras(i))
      }
    }

    // ---- checkpointRestore: undo any wrong-path push/pop since the last save by
    //      copying the checkpoint shadow back. Placed LAST so it wins (SpinalHDL:
    //      last assignment wins) over invalidateAll/push/pop on the impossible cycle
    //      where more than one of these fire together. ----
    when(checkpointRestore) {
      rasSp := ckRasSp
      count := ckCount
      for (i <- 0 until entries) { ras(i) := ckRas(i) }
    }

    GenerationFlags.simulation {
      when(!ClockDomain.current.isResetActive) {
        assert(!(checkpointSave && checkpointRestore),
          "RasPlugin: checkpointSave and checkpointRestore asserted the same cycle " +
          "-- no driver should ever do this (save = ROB-drained refresh, restore = a flush)",
          FAILURE)
      }
    }
  }
}
