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
  * ⛔ MEASURED COST OF "not per-branch-precise" (2026-09-26, `BranchPredictIpcSpec`).
  * The imprecision is not a small residual: it makes EVERY MISPREDICTED CALL COST TWO
  * MISPREDICTS -- its own, and its callee's return.
  *
  * The flush fires at the mispredicting branch's RETIRE, so when the branch IS the call
  * (a `jsr (An)` whose target the BTB could not know), the call's own push is by then
  * ARCHITECTURAL. `checkpointRestore` reverts it anyway, because the checkpoint predates
  * it -- and `checkpointSave` cannot help, since `rob.count === 0` next becomes true
  * right AFTER the flush, re-saving the already-reverted state. The callee's `rts` then
  * finds no prediction at all.
  *
  * On the `br-ind` probe (one `jsr (%a0)`, 32 targets in a fixed cycle) that is exactly
  * 1024 of 2050 retired mispredicts -- FIFTY PERCENT, in a kernel whose only genuine
  * unpredictability is the indirect target. `br-ind-2` (2 targets) shows the same
  * coupling at 200 of 678. And the bucket is INVISIBLE to both shipped board class
  * counters: `OFF_PERF_MISPRED_UNCOND`/`_COND` come from `debugBranchRetire`, which is
  * gated on `isBtbBranch` and excludes returns, while `OFF_MISPRED_COUNT` counts them --
  * so `MISPRED_COUNT - (UNCOND + COND)` reads it on silicon today, no rebuild needed.
  *
  * Neither "keep" nor "restore" can be right here, so this needs REAL recovery, and the
  * shape is already proven in this codebase one plugin over: GsharePlugin's `ghrArch` is
  * a RETIRE-TIME architectural history that the flush copies wholesale into the
  * speculative register. The RAS wants the same thing -- an architectural shadow stack
  * updated when a call or a return RETIRES -- which is the "per-instruction retire-linked
  * commit signal ... real ROB-side plumbing" this comment declares out of scope below.
  * It is the ranked next step, not a nice-to-have: `rob.count === 0` is the wrong proxy
  * precisely in the case that matters.
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
  *  - checkpointSave        : ARM a refresh of the checkpoint shadow (ckRasSp/ckCount/
  *    ckRas). Driven from the wiring layer whenever the ROB is fully drained (see the
  *    doc comment above) -- in the synth/bench/lockstep compositions that is now the
  *    ROB's REGISTERED `countIsZero` flag, a bit-exact restatement of `count === 0`.
  *    The copy itself lands the CYCLE AFTER the arm and reads the live registers
  *    directly, which captures bit-identically the same state the old same-cycle form
  *    hand-computed with `nextRasSp`/`nextCount`/a push-bypass mux -- see the FMax
  *    note at the `ckSaveArm` declaration for why that is exact rather than
  *    approximate, and for the two bounded corners (a restore on the deferred cycle
  *    cancels that one refresh; an `invalidateAll` on the arming cycle is now
  *    correctly reflected in the checkpoint).
  *  - checkpointRestore     : copy the checkpoint shadow back into rasSp/count/ras,
  *    undoing any wrong-path push/pop since the last save. Driven from the wiring
  *    layer on RedirectService.doFlush (the ROB's commit-time correction) OR
  *    FetchAlignPlugin's ftqMismatch (a frontend-only re-framing correction). Restore
  *    always wins over save: the shadow write is gated by `!checkpointRestore` and the
  *    live-array restore is placed LAST (SpinalHDL last-assignment-wins) over
  *    invalidateAll/push/pop. Wiring sites therefore no longer need to AND
  *    `!checkpointRestore` into their `checkpointSave` term (and must not, if the
  *    point is to keep the ROB->frontend route purely register-to-register).
  *
  * At most ONE push OR one pop per cycle (an instruction is a call XOR a return, and
  * the aligner emits at most one predicted-redirecting slot/cycle) -> single-ported.
  *
  * ════════════════════════════════════════════════════════════════════════════════════
  * `branchRepair` (2026-09-27, DEFAULT OFF): RE-APPLY THE FLUSHING BRANCH'S OWN EFFECT
  * ════════════════════════════════════════════════════════════════════════════════════
  * The measured defect above is that `checkpointRestore` reverts a push that was already
  * ARCHITECTURAL, because the flush fires at the mispredicting branch's own recovery and
  * the checkpoint predates that branch. The missing state is therefore EXACTLY ONE
  * ENTRY -- the flushing branch's own push (a mispredicted call) or its own pop (a
  * mispredicted, RAS-predicted return) -- and `branchRepair` puts it back:
  *
  *   repairValid/repairKind/repairData : driven from the ROB, one cycle before the repair
  *     is applied. `kind` is CALL (re-push `data` = the call's fall-through PC) or RET
  *     (re-pop the entry whose address is `data` = the target the RAS predicted).
  *
  * WHY NOT A FULL RETIRE-TIME ARCHITECTURAL SHADOW STACK (the `ghrArch` idiom the defect
  * note names). Measured on this core, a shadow stack CANNOT repair the dominant flush
  * path. The ROB's Tier-1 reschedule (`earlyFire`) redirects fetch at the branch's
  * RESOLUTION, i.e. BEFORE it reaches the head -- which is exactly why GsharePlugin's
  * `flushRepair` is wired to `doFlush` and deliberately NOT to `earlyFire` ("Tier 1 fires
  * BEFORE the mispredicting branch reaches the ROB head, so the architectural GHR does
  * not yet hold that branch's bit"). A retire-time RAS shadow has the same hole, and it
  * is not a corner: when Tier 1 owns the redirect, Tier 2 SUPPRESSES the frontend flush
  * (`earlySuppressFe`), so the RAS restore never happens again and there is no later
  * chance to repair. Worse, a shadow stack restored at Tier 1 would DELETE the pushes of
  * older, correct-path calls that are still in flight. So the repair has to be the
  * flushing branch's own delta, applied wherever the restore itself fires -- which is
  * Skadron/Ahuja/Martonosi/Clark's finding (MICRO-31, 1998) that a SMALL amount of repair
  * state recovers most of the loss, in the shape this pipeline can actually deliver.
  * A shadow stack remains complementary (it would make the RESTORE SOURCE more recent
  * than the last ROB drain) but it is not what fixes the double mispredict.
  *
  * IDEMPOTENT BY CONSTRUCTION, not "push one more". The repair states an invariant --
  * "the top of stack is this call's return address" / "this return's entry is gone" --
  * and compares against the live `predTarget` before acting. That matters: the checkpoint
  * is refreshed whenever the ROB drains, and on this front-end-starved machine the ROB is
  * empty most cycles, so the checkpoint CAN already contain the call's push (a drain in
  * the few cycles between the call's fetch-time push and its dispatch). A blind push
  * would then leave the stack one entry too deep and simply move the second mispredict to
  * the CALLER's return. The compare costs one 32-bit equality against a mux that already
  * exists (`predTarget` is the predictor's own combinational read).
  *
  * TIMING. Every port is a bare register Q on the ROB side, and this module consumes them
  * behind one more flop (`rp.arm`), so `branchRepair` adds NOTHING to the redirect /
  * `ftbBlocked` cone -- the standing rule for predictor recovery on this core ("register
  * the event, then repair"; see GsharePlugin's `repairArm`). The repair therefore lands
  * one cycle AFTER the restore, which is also what makes it read the RESTORED stack.
  *
  * A restore on the repair cycle WINS (its block is placed last), so the repair is
  * dropped rather than applied to a state it was not computed against. */
object RasRepair {
  val W    = 2
  val NONE = 0
  /** Re-push `data` (the call's fall-through PC) unless it is already the top of stack. */
  val CALL = 1
  /** Re-pop `data` (the return target the RAS predicted) if it is back on top. */
  val RET  = 2
}

class RasPlugin(val branchRepair: Boolean = false,
                /** `CPU_RAS_BRANCH_SNAPSHOT` (DEFAULT OFF): per-branch pointer + TOS recovery.
                  * See the `snap` block below and `RasSnapRec`. */
                val branchSnapshot: Boolean = false)
    extends FiberPlugin with m68k040.services.RasSnapshotService {
  override def snapshotEnabled: Boolean = branchSnapshot
  override def liveSnapshot: m68k040.services.RasSnapRec = logic.snapLive

  val logic = during build new Area {
    val entries = Global.RAS_ENTRIES.get
    require((entries & (entries - 1)) == 0, "rasEntries must be a power of two")
    val spBits  = log2Up(entries)            // pointer width (wraps)
    val cntBits = log2Up(entries) + 1        // occupancy 0..entries (saturating)

    // ---- storage: a 16x32 register Vec (1-cycle combinational read; tiny) ----
    // Payload has no reset: count owns visibility. A push writes its row before
    // making it occupied; a restore copies data and occupancy from one checkpoint.
    // Empty predTarget is unspecified and FetchAlign qualifies it with predValid.
    // Keep every pointer/count/save-arm reset (docs/ras-payload-reset.md).
    val ras   = Vec.fill(entries)(Reg(UInt(32 bits)))
    val rasSp = RegInit(U(0, spBits bits))   // speculative top-of-stack (next push slot)
    val count = RegInit(U(0, cntBits bits))  // saturating occupancy (0..entries)
    rasSp.simPublic(); count.simPublic()

    // ---- checkpoint shadow (rollback-on-flush, see the class doc comment) ----
    val ckRas   = Vec.fill(entries)(Reg(UInt(32 bits)))
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
    // ---- branchRepair ports (see the class doc). Declared unconditionally so every
    //      wiring layer compiles either way; with the option OFF nothing reads them and
    //      they prune to nothing. Idle-defaulted, like every other port here. ----
    val repairValid = Bool();                 repairValid.allowOverride; repairValid := False
    val repairKind  = UInt(RasRepair.W bits);  repairKind.allowOverride;  repairKind  := U(RasRepair.NONE, RasRepair.W bits)
    val repairData  = UInt(32 bits);           repairData.allowOverride;  repairData  := U(0, 32 bits)
    repairValid.simPublic(); repairKind.simPublic(); repairData.simPublic()
    // sim-only debug visibility (directed lock-step RAS-prediction-accuracy tests):
    // these otherwise have no OTHER reason to stay a stable, prunable-proof handle.
    pushValid.simPublic(); pushRetPc.simPublic(); popValid.simPublic()
    checkpointSave.simPublic(); checkpointRestore.simPublic()

    // ---- FMax (200 MHz, 2026-10-06): ONE-ENTRY WRITE BUFFER for the payload push ----
    // `pushValid` is `feed.fire && !faultHold && s0IsCall`, whose VALID half is the
    // ftqHead -> ftqMem async read -> availEff -> Aligner -> feed.valid cone (12 levels in
    // the routed 200 MHz build). Before this change that cone drove the CE of ALL
    // entries*32 = 512 `ras` payload flops (`ras(rasSp) := pushRetPc`) -- 512 of the
    // design's failing endpoints, every one sourced from `ftqHead_reg`.
    //
    // The push's POINTER/OCCUPANCY effect (`rasSp`/`count`, 9 flops) still lands on the
    // push cycle exactly as before. Only the 32-bit PAYLOAD write is parked for one cycle
    // in `pendValid/pendIdx/pendData` (pendData/pendIdx are plain RegNext, so the deep
    // cone reaches exactly ONE flop's D: `pendValid`), and is committed into `ras` on the
    // next cycle with a CE that is a pure function of registers
    // (`pendValid && pendIdx === i`).
    //
    // EXACTNESS. The LOGICAL stack content is `ras` with slot `pendIdx` overridden by
    // `pendData` while `pendValid` -- bit-identical to what the old same-cycle write held
    // in `ras` Q. Every reader of the payload is routed through that view:
    //   * `predTarget` (bypass mux below; `pendIdx`/`rasSp` are both registers, so the
    //     select is a 4-bit equality off flops, parallel to the 16:1 read mux);
    //   * the deferred `checkpointSave` copy (`ckRas(i) := rasView(i)`).
    // Writers of `ras` that can collide with the parked write, in priority order (last
    // assignment wins, matching the old sequential order):
    //   * the parked write lands FIRST (it is the OLDEST event);
    //   * `branchRepair`'s push (`ras(rasSp) := data`) lands after it -- in the old code
    //     the fetch push happened a cycle earlier, so the repair overwrote it;
    //   * `checkpointRestore` lands last and overwrites all rows -- in the old code the
    //     restore also overwrote the previous cycle's push.
    // A push on the SAME cycle as a restore was dropped by the old code (restore wins over
    // the push's array write); `pendValid` is therefore gated with `!checkpointRestore`
    // so the parked write cannot resurrect it one cycle later. A push on the same cycle
    // as `invalidateAll` still writes its row (the old code did too: invalidate clears only
    // rasSp/count); that row is unowned (count=0) and never observable.
    // ---- branchSnapshot ports (see the `snap` block below). Declared unconditionally,
    //      idle-defaulted, like the repair ports; with the option OFF nothing reads them. ----
    val snapApply = Bool();          snapApply.allowOverride; snapApply := False
    val snapSp    = UInt(spBits bits);  snapSp.allowOverride;    snapSp    := U(0, spBits bits)
    val snapCount = UInt(cntBits bits); snapCount.allowOverride; snapCount := U(0, cntBits bits)
    val snapTos   = UInt(32 bits);      snapTos.allowOverride;   snapTos   := U(0, 32 bits)
    snapApply.simPublic()
    if (branchSnapshot) {
      require(spBits == m68k040.services.RasSnapRec.SP_W && cntBits == m68k040.services.RasSnapRec.CNT_W,
        s"branchSnapshot: RasSnapRec widths are fixed for a 16-entry RAS (entries=$entries)")
    }
    // A wrong-path push on the snapshot-apply cycle must not land one cycle LATER on top of
    // the restored top-of-stack (it would if its slot is `snapSp-1`), so it is dropped
    // exactly like a push on a restore cycle. `snapApply` is a bare register Q.
    val pendValidRef = if (branchSnapshot) RegNext(pushValid && !checkpointRestore && !snapApply) init False
                       else RegNext(pushValid && !checkpointRestore) init False
    // CPU_RAS_CK_PEND: the checkpoint stores the RAW `ras` rows plus a copy of the parked
    // write ({ckPendValid, ckPendIdx, ckPendData}); a restore reloads the parked write from
    // that copy instead of resolving it into every checkpoint row at save time. The LOGICAL
    // stack (`ras` overridden by the parked write) after a restore is the same checkpoint,
    // and every reader already goes through that override. Drops the 16x32 save-side mux.
    val ckPendOn    = m68k040.top.ShippingCoreConfig.rasCkPend
    val ckPendValid = if (ckPendOn) (Reg(Bool()) init False) else null
    val ckPendIdx   = if (ckPendOn) Reg(UInt(spBits bits)) else null
    val ckPendData  = if (ckPendOn) Reg(UInt(32 bits)) else null
    val pendValid = if (!ckPendOn) pendValidRef
                    else RegNext(Mux(checkpointRestore, ckPendValid, pushValid && (if (branchSnapshot) !snapApply else True))) init False
    val pendIdx   = if (!ckPendOn) RegNext(rasSp) else RegNext(Mux(checkpointRestore, ckPendIdx, rasSp))
    val pendData  = if (!ckPendOn) RegNext(pushRetPc) else RegNext(Mux(checkpointRestore, ckPendData, pushRetPc))
    pendValid.simPublic(); pendIdx.simPublic(); pendData.simPublic()
    def rasView(i: Int): UInt = Mux(pendValid && pendIdx === U(i, spBits bits), pendData, ras(i))
    when(pendValid) { ras(pendIdx) := pendData }

    // ---- combinational predict read: top-of-stack, valid iff non-empty ----
    val predValid  = (count =/= U(0, cntBits bits))
    val topIdx     = rasSp - U(1, spBits bits)
    val predTarget = Mux(pendValid && pendIdx === topIdx, pendData, ras(topIdx))
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
      // payload write is parked in pendValid/pendIdx/pendData (see above)
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

    // ---- branchRepair: re-apply the FLUSHING branch's own push/pop (see class doc) ----
    // Placed after `rasSp := nextRasSp` / `count := nextCount` (so it overrides a
    // same-cycle fetch push/pop, which cannot occur anyway -- the redirect has just
    // squashed the frontend) and BEFORE invalidateAll/checkpointRestore, so both of
    // those still win.
    val rp = if (branchRepair) new Area {
      // One flop between the port and the action: the port is a register Q on the ROB
      // side, `checkpointRestore` fires on the cycle the port is asserted, and the
      // restored state is therefore what `ras`/`rasSp`/`count` HOLD on this arm cycle.
      val arm  = RegNext(repairValid && repairKind =/= U(RasRepair.NONE, RasRepair.W bits)) init False
      val kind = RegNextWhen(repairKind, repairValid) init U(RasRepair.NONE, RasRepair.W bits)
      val data = RegNextWhen(repairData, repairValid) init U(0, 32 bits)
      arm.simPublic(); kind.simPublic(); data.simPublic()
      // `predValid`/`predTarget` ARE the predictor's answer for the next return, so
      // comparing against them states the repair's invariant directly (and reuses the
      // existing top-of-stack mux rather than adding a second read).
      val topIsData = predValid && (predTarget === data)
      val doPush = arm && (kind === U(RasRepair.CALL, RasRepair.W bits)) && !topIsData
      val doPop  = arm && (kind === U(RasRepair.RET,  RasRepair.W bits)) && topIsData
      doPush.simPublic(); doPop.simPublic()
      when(doPush) {
        ras(rasSp) := data
        rasSp      := rasSp + U(1, spBits bits)
        when(count =/= cntMax) { count := count + U(1, cntBits bits) }
      }
      when(doPop) {
        rasSp := rasSp - U(1, spBits bits)
        count := count - U(1, cntBits bits)   // topIsData implies count =/= 0
      }
    } else null

    // ---- branchSnapshot: restore the MISPREDICTING BRANCH's own post-fetch state ----
    // `snapApply` (a register Q from the ROB, = Tier-1 `earlyFire` && the branch carried a
    // snapshot) installs the pointer/occupancy the RAS had right after that branch's fetch
    // group, and rewrites the one entry at its top of stack. This REPLACES, for that event,
    // both the ROB-drain checkpoint copy (which the wiring then withholds) and the
    // `branchRepair` delta (the snapshot already includes the branch's own push/pop), so
    // every correct-path call/return fetched between the last ROB drain and the branch is
    // kept instead of being rolled back. Entries below the restored top come from the
    // live array (only a wrong-path pop-then-push sequence deeper than one level can have
    // overwritten them: a prediction-QUALITY residual, never a correctness one -- the
    // branch EU verifies every return target).
    //
    // Placed after the fetch push/pop and the repair (it wins over both: anything else on
    // this cycle is wrong-path or stale) and BEFORE invalidateAll/checkpointRestore, which
    // keep their priority. All controls and data are register Qs; the per-entry write
    // enable is a 4-bit decode of `snapSp-1` off flops. Nothing joins the redirect cone.
    val snap = if (branchSnapshot) new Area {
      val tosIdx = snapSp - U(1, spBits bits)
      when(snapApply) {
        rasSp := snapSp
        count := snapCount
        when(snapCount =/= U(0, cntBits bits)) { ras(tosIdx) := snapTos }
      }
    } else null
    // ---- live post-group state for DecodeStage's per-tag snapshot table ----
    // Register Qs plus the existing push-bypassed top-of-stack read (`predTarget`).
    val snapLive = m68k040.services.RasSnapRec()
    if (branchSnapshot) {
      snapLive.valid := True
      snapLive.sp    := rasSp
      snapLive.count := count
      snapLive.tos   := predTarget
    } else snapLive.setInert()

    // ---- invalidateAll: clear occupancy (and the pointer) on an I-cache flush ----
    when(invalidateAll) {
      count := U(0, cntBits bits)
      rasSp := U(0, spBits bits)
    }

    // ---- checkpointSave: snapshot rasSp/count/ras into the checkpoint shadow.
    //      See the class doc comment for WHEN the wiring layer arms this (ROB fully
    //      drained -> nothing outstanding -> live state is architecturally correct by
    //      construction) and for the ONE-CYCLE-DEFERRED APPLY below. ----
    //
    // FMax (2026-09-15): `checkpointSave` is an ARM; the copy lands the cycle AFTER.
    // The previous form applied the copy on the arming cycle, which forced it to
    // pre-apply this cycle's push by hand:
    //     ckRas(i) := Mux(pushValid && rasSp === i, pushRetPc, ras(i))
    // `pushValid` is `feed.fire && !faultHold && s0IsCall` (FetchAlignPlugin), and
    // `feed.fire` carries DecodeStage's ready ladder -- ~17 levels of logic. That put
    // the deepest combinational cone in the frontend on the D pin of all
    // `entries`*32 `ckRas` flops, and `ckRas_*_reg[*]/D` became the worst endpoint
    // family in the whole design. Deferring by one cycle removes that cone outright:
    // the `ras` REGISTERS already hold "post this cycle's push" state on the next
    // edge, so the copy degenerates to a flop-to-flop `ckRas(i) := ras(i)` with ZERO
    // levels of logic between them.
    //
    // Why the CAPTURED VALUE is bit-identical, not merely "close enough": SpinalHDL
    // register reads return Q, so on the deferred cycle `ras(i)`/`rasSp`/`count` read
    // back exactly `Mux(pushValid && rasSp===i, pushRetPc, ras(i))`/`nextRasSp`/
    // `nextCount` as evaluated on the ARMING cycle -- i.e. precisely what the old code
    // wrote. A push/pop on the deferred cycle itself cannot contaminate the snapshot:
    // it only changes the D inputs of `ras`/`rasSp`/`count`, never their Q. So the
    // checkpoint CONTENT is unchanged; only the cycle it is installed on moves by one.
    //
    // The one behavioural consequence is the `!checkpointRestore` term below: a
    // restore landing on the deferred cycle cancels that refresh, so the RAS rolls
    // back to the PREVIOUS ROB-drain checkpoint instead of this one. That is sound by
    // the same argument that licenses the whole scheme -- an older drain snapshot is
    // still a state in which nothing was outstanding, so it is still a valid restore
    // point; it is strictly a (rare, bounded) prediction-QUALITY question, never a
    // correctness one, because the branch EU verifies every RTS/RTR target. It cannot
    // capture a MORE speculative state than intended: the deferred read is the arming
    // cycle's end-of-cycle state, full stop.
    //
    // The mirror corner -- a restore on the ARMING cycle, which the old wiring
    // suppressed with its `&& !rasCheckpointRestore` term and this one no longer does
    // -- is a provable NO-OP, not a new hazard: the restore has last-assignment
    // priority, so at the end of the arming cycle `ras`/`rasSp`/`count` hold EXACTLY
    // the checkpoint, and the deferred copy therefore writes `ckRas := ckRas`.
    //
    // Second-order improvement, called out so it is not mistaken for a regression:
    // the deferred read now sees `invalidateAll`'s effect too (that block writes
    // `rasSp`/`count` directly, bypassing `nextRasSp`/`nextCount`). So an I-cache
    // invalidate on a drained-ROB cycle now also empties the CHECKPOINT, where before
    // the checkpoint kept the pre-invalidate occupancy and a later restore resurrected
    // it. Strictly closer to the architectural model.
    val ckSaveArm = RegNext(checkpointSave) init False
    val ckSaveNow = ckSaveArm && !checkpointRestore
    ckSaveArm.simPublic(); ckSaveNow.simPublic()
    when(ckSaveNow) {
      ckRasSp := rasSp
      ckCount := count
      // rasView: the parked push from the ARMING cycle has not reached `ras` Q yet.
      if (!ckPendOn) for (i <- 0 until entries) { ckRas(i) := rasView(i) }
      else {
        for (i <- 0 until entries) { ckRas(i) := ras(i) }
        ckPendValid := pendValid; ckPendIdx := pendIdx; ckPendData := pendData
      }
    }
    // Sim-only shadow of the reference checkpoint (rows resolved at save, restore copies
    // them back with no parked write): after every restore the logical stack must match.
    if (ckPendOn) GenerationFlags.simulation {
      val refCk = Vec.fill(entries)(Reg(UInt(32 bits)))
      val refValid = Reg(Bool()) init False
      when(ckSaveNow) { for (i <- 0 until entries) refCk(i) := rasView(i); refValid := True }
      val chk = RegNext(checkpointRestore && refValid) init False
      val refAtRestore = RegNext(refCk)
      when(chk) {
        for (i <- 0 until entries)
          assert(rasView(i) === refAtRestore(i),
            "RAS CPU_RAS_CK_PEND: restored logical stack diverged from the reference checkpoint", FAILURE)
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

    // NOTE (2026-09-15): the old sim assertion `!(checkpointSave && checkpointRestore)`
    // is GONE on purpose. It policed a DRIVER contract -- every wiring site had to AND
    // `!rasCheckpointRestore` into its `checkpointSave` term -- that only existed
    // because save and restore both wrote the shadow/live arrays on the same cycle.
    // With the deferred apply the arm and the restore are no longer even the same
    // cycle, so the contract is unenforceable at the port, and the mutual exclusion is
    // now guaranteed INSIDE the module by `ckSaveNow = ckSaveArm && !checkpointRestore`
    // (restore wins, by construction, with no driver cooperation needed). Keeping the
    // AND at the wiring sites would also have kept the exact thing this change
    // removes: combinational logic in front of the long ROB->frontend route.
  }
}
