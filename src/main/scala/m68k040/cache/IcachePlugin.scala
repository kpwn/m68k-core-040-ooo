package m68k040.cache

import m68k040.services.{FetchService, TranslationService, PrivilegeService}
import m68k040.frontend.PredecodeWord
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4ReadOnly, Axi4}
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

/** Synthesizable VIPT L1 instruction cache.
  *
  * Geometry: 64 sets, 4 ways, 64-byte lines (16 KiB total).
  * AXI read interface: 256-bit (32 B/beat) × 2 beats per line refill.
  * Implements FetchService (cmd/rsp) and resolves TranslationService.
  *
  * FSM: IDLE → REFILL → REPLAY → IDLE.
  */
class IcachePlugin extends FiberPlugin with FetchService {

  // ---- geometry (single source of truth) ----
  private val geo          = CacheGeometry.l1i040
  geo.requireViptSafe(4096, "L1I")         // alias-safety guard (4 KiB pages)
  private val ways         = geo.ways
  private val sets         = geo.sets
  private val tagBits      = geo.tagBits
  private val beatsPerLine = geo.lineBytes / 32   // 256-bit (32 B) beats
  private val setBits      = geo.indexBits
  private val wayBits      = log2Up(ways)
  private val pfSlots      = AxiIds.I_SPEC_SLOTS
  // M2c: `pfIdxBits` is DELETED. Every index in the fill machinery is an MSHR index
  // (`mshrIdxBits`) now; the slot-relative width existed only to size the demand/
  // speculative translation this task removes.

  // idWidth widened 2 -> 4 (ratified slice V2a.1): socket-conformant (AXI_IW = 4)
  // and, more immediately, the I side needs two DISTINCT ids the moment slice I3's
  // prefetch MSHR can be outstanding alongside the demand MSHR. See AxiIds.
  val axiCfg = Axi4Config(addressWidth = 32, dataWidth = 256, idWidth = AxiIds.ID_W)

  // ---- logic Area (built during build phase) ----
  val logic = during build new Area {

    // ---- FetchService ports: plain directionless Stream/Flow (NOT slave/master) ----
    val cmdPort       = Stream(FetchCmd())
    val rspPort       = Flow(FetchRsp())
    val axi           = master(Axi4ReadOnly(axiCfg))
    val invalidateAll = in Bool()
    // Task P5.4: an INTERNAL second invalidate-all source for the CINV/CPUSH cache
    // maintenance selector (sel = IC or BC). Deliberately a separate wire from the
    // external top-IO `invalidateAll` port above, which stays a bare `in Bool()` --
    // so every existing sim poke and every existing top-level connection of it is
    // completely unaffected. Task P5.5 wired its real driver: ExceptionUnit's
    // `icMaintPulse`, pulsed from the S_APPLY CPUSH/CINV arms whenever the cache
    // selector names IC (10) or BC (11). It defaults idle here (allowOverride, so that
    // driver can override it), which is what keeps every DUT that does NOT wire it
    // (standalone I-cache tests) unchanged.
    //
    // ── DECIDED, Task P5.5 Step 9, CORRECTED in the P5.5 follow-up: BTB yes, RAS/gshare no ──
    // This wire itself only ever touches the I-cache's own `valids` array (the `when`
    // below). Predictor invalidation is fanned out from the SAME source signal
    // (`ExceptionUnit.icMaintPulse`) at the wiring sites — FullCoreSynth plus the three
    // test DUTs (ExecuteLockStepSpec / FuzzDut / IpcBenchSpec) — where each already has
    // a `btb.logic.invalidateAll := ...` line. The split is:
    //
    //   - BTB: IS invalidated on a CINV/CPUSH-IC. Step 9 originally argued no predictor
    //     needed it because every prediction is corrected at resolve. That argument is
    //     WRONG for the BTB specifically. `FetchAlignPlugin`'s BTB lookup
    //     (`btbQueryValid0 := predEnable && res.slot0Valid`) is NOT gated on predecode
    //     agreeing the slot is a branch, so a stale BTB hit stamps `predTaken`/redirects
    //     fetch on ANY instruction kind — and `predTaken` is read ONLY by
    //     `BranchEuPlugin`, so an ALU/LS uop carrying a stale `predTaken` is verified by
    //     nothing. SMC that replaces a taken branch at PC X with a non-branch, followed
    //     by a correct CINV IC and a correct refetch of the new bytes, could still have
    //     the stale BTB entry silently redirect fetch away from X, and the wrong-path
    //     instructions RETIRE. That is an architectural divergence, not a perf artifact.
    //   - RAS: NOT invalidated, and does not need to be. The RAS predict is gated on
    //     `s0IsReturn`, recomputed every cycle from the FRESHLY FETCHED slot0 opword
    //     (RTS=0x4E75 / RTR=0x4E77). If SMC replaces the return with something else, the
    //     new bytes simply don't classify as a return and the RAS is never consulted.
    //   - gshare: NOT invalidated, and does not need to be. It only overrides the
    //     DIRECTION of a branch that hit the BTB, and `BranchEuPlugin`'s
    //     `mispredict` cross-checks predicted direction AND target against the actual
    //     resolved ones, so a stale gshare bit cannot survive uncaught. (It is also
    //     already reachable via the BTB clear above: no BTB hit -> no gshare override.)
    //
    // The `valids` array is the plainest case, and that is why it IS cleared here:
    // leaving it stale would make the core FETCH WRONG BYTES.
    //
    // The external `invalidateAll` port keeps its own broader fan-out (BTB + RAS +
    // gshare + this array) — right for ITS purpose, a boot/reset-time full clear.
    val maintInvalidateAll = Bool()
    maintInvalidateAll.allowOverride
    maintInvalidateAll := False

    // ---- slice I3: next-line prefetch on/off control ----
    // Design doc §8.3 requires the measurement sweep to run `prefetch in {on, off}`
    // from ONE compiled DUT, so this cannot be a compile-time parameter.
    //
    // It is deliberately NOT a top-level `in Bool()` either. That was tried and is a
    // TRAP: `prefetchEnable` is created inside the plugin's Area, so an `in Bool()`
    // becomes a port of the TOP-LEVEL component, where SpinalHDL's `default(...)` has
    // no parent to apply it at -- every full-core testbench would silently read it as
    // 0 and prefetch would be dead in exactly the runs that measure it. (Confirmed:
    // the first IPC sweep after adding it came back bit-identical to baseline.)
    //
    // A self-assigned RegInit is the pattern this codebase already uses for
    // sim-pokeable controls (see `ICacheModeTranslationPlugin.cmodeEn`): pokeable from
    // any testbench via `simPublic`, and in synthesis it is a register whose only
    // driver is itself, so it constant-folds to True and costs nothing. Default ON, so
    // no DUT anywhere needs a wiring change.
    val prefetchEnable = RegInit(True)
    prefetchEnable.simPublic()
    prefetchEnable := prefetchEnable

    // ---- resolve TranslationService ----
    val xlate = host[TranslationService]
    // The current architectural S bit (ROB-owned, same signal the privilege-violation
    // check gates on) — an instruction fetch's function code must reflect the ACTUAL
    // current privilege level, not a hardcoded one. A hardcoded False here meant any
    // supervisor-only code page (the normal kernel configuration) permission-denied
    // EVERY fetch once the MMU was enabled — a permanent boot-blocker (the CPU could
    // never fetch its own supervisor code). Mirrors the DTLB-side fix in LsEuPlugin.
    // `host.get` (optional): a standalone I-cache DUT with no RobPlugin/PrivilegeService
    // wired defaults to False (user), exactly the prior hardcoded behavior — unchanged
    // for every existing non-full-core test.
    val privCtrl = host.get[PrivilegeService]
    // A translation is demanded only while the cache is actively looking at a real
    // offered fetch. In particular, a cmd held upstream during a demand refill must
    // not start an unowned younger walk merely because its Stream valid stays high.
    // `lookupTick` opens this gate in IDLE and during the answerable portion of a
    // prefetch fill; all other states keep it closed.
    val lookupActive = Bool()
    lookupActive := False
    xlate.req.valid      := lookupActive && cmdPort.valid
    xlate.req.vpn        := cmdPort.payload.pc(31 downto 12)
    xlate.req.supervisor := privCtrl.map(_.supervisor).getOrElse(False)
    xlate.req.write      := False

    // ---- storage arrays ----
    // Tags + pred: async-read LUTRAM (single write port -> distributed RAM). Kept
    // async so hit/miss is resolved in the accept cycle (a miss starts REFILL with
    // no added latency).
    val tagMem  = Seq.fill(ways)(Mem(UInt(tagBits bits), sets))
    // Packed per-line predecode width = ChunkPredecode's bit width * 32 words/line. Was a
    // hardcoded 128 (= 4 bits/word * 32) back when ChunkPredecode was 4 bits; DERIVED now
    // (deep-audit F1/F2/F3, 2026-07-11: ChunkPredecode widened 4->5 bits, so this grows to
    // 160) so a future width change can't silently desync this packing from the real size.
    val PRED_BITS_PER_WORD = ChunkPredecode().getBitsWidth
    // M2a (Task 7): no longer used by any RTL expression -- the last whole-line packed
    // predecode value (`missPred`) is gone. Retained as the derived documentation of the
    // line-granular width the comments above and below still reason in.
    val PRED_BITS_PER_LINE = PRED_BITS_PER_WORD * 32
    // Slice I2 (per-beat predecode): one AXI beat is 32 bytes = 16 words of a 32-word
    // line, so the single classify group is 16 wide and is used TWICE per refill.
    val WORDS_PER_BEAT     = 16
    val PRED_BITS_PER_BEAT = PRED_BITS_PER_WORD * WORDS_PER_BEAT

    // ---- M1: the Unified Fetch Array ----------------------------------------
    // ONE synchronous array per way carries a beat's 256 instruction bits AND that
    // beat's PRED_BITS_PER_BEAT bits of per-word predecode. Replaces the old
    // {dataMem (sync BRAM), separate whole-line async-LUTRAM predecode array with two
    // read ports} pair. M1b (Task 6) deleted that second array, its S1 capture bank and
    // the M1a shadow-equivalence monitor that proved the two agreed bit-for-bit.
    //
    // WHY (design spec §5.1). The old predecode array's line-granular ASYNC read forced
    // all four ways' whole PRED_BITS_PER_LINE-bit entries to be captured into a
    // registered bank -- 4 x 256 = 1,024 flops, whose clock enable was `cmdPort.fire`
    // (ITLB mux -> async LUTRAM read -> 20-bit compare -> answerable).
    // synth/floorplan_frontend.xdc records that arc as EVERY ONE of the worst 300 unique
    // failing endpoints on the pinned routed checkpoint. Beat-granular storage makes the
    // predecode address ALREADY the address the data array uses, so the capture bank has
    // no reason to exist.
    //
    // 2 beats x 64 sets = 128 entries per way, synchronous read (BRAM).
    val UFA_W   = 256 + PRED_BITS_PER_BEAT
    val lineMem = Seq.fill(ways)(Mem(Bits(UFA_W bits), sets * beatsPerLine))
    // Valid bits: register array, cleared by invalidateAll
    val valids  = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))
    // Round-robin victim pointer per set
    val victim  = Vec.fill(sets)(RegInit(U(0, wayBits bits)))
    // Per-(way,set) "installed silently and not demand-hit yet" telemetry. The moving
    // window is extended by every accepted cacheable demand; it does not depend on
    // this bit. Cleared on a demand fill of the same way/set, on the first demand hit,
    // and by invalidateAll.
    val pfFilled = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))

    // DEBUG (icache-corruption-fix task, temporary — mirrors DcachePlugin's
    // ldS1Valid/stS2Valid.simPublic() DEBUG hooks): exposes the raw shared arrays
    // for a directed test to assert an UNRELATED way's tag/data/pred content is
    // byte-for-byte unchanged by a same-set INHIBITED miss. No-op for synthesis.
    for (w <- 0 until ways) { tagMem(w).simPublic(); lineMem(w).simPublic() }
    valids.simPublic()

    // ---- invalidateAll: priority clear of all valid bits ----
    val anyInvalidate = invalidateAll || maintInvalidateAll
    when(anyInvalidate) {
      for (w <- 0 until ways; s <- 0 until sets) valids(w)(s) := False
      for (w <- 0 until ways; s <- 0 until sets) pfFilled(w)(s) := False
    }

    // ---- live parallel-VIPT lookup context (binding amendment 2026-08-10) ----
    // Virtual set/beat arms the synchronous BRAM in the same cycle as the ITLB lookup.
    // Translation only qualifies the physical tag and the small S1 control context;
    // it is deliberately NOT on the BRAM address/enable or the wide data mux.
    val lookupPc        = cmdPort.payload.pc
    val lookupPaddr     = (xlate.rsp.ppn ## lookupPc(11 downto 0)).asUInt
    val lookupFault     = xlate.rsp.fault
    val lookupCmode     = xlate.rsp.cacheMode
    val lookupCacheable = lookupCmode =/= CacheMode.INHIBITED

    // ---- install-context latches (M2c: NOT part of the MSHR control file) ----
    // M2c splits what used to be one undifferentiated `miss*` pile into two things
    // with genuinely different lifetimes:
    //
    //   - the MSHR CONTROL FILE (`mshr*`, declared below): "a fill I have requested and
    //     am tracking on the bus". One uniform entry per AXI ID, demand at index 0.
    //   - the INSTALL CONTEXT (`installIdx`/`installSet`/`installWay` + the two
    //     registers here): "the fill I am currently committing into the arrays". There
    //     is exactly ONE installer, shared by both kinds of fill, so this context is a
    //     set of scalars, loaded on whichever arm enters INSTALL_ARM.
    //
    // `missPC` and `missCacheable` are the two install-context fields with no
    // per-entry counterpart in spec section 5.2's list, so they stay scalars.
    // CORRECTION to the plan's stated reason for keeping them ("demand-only ... a
    // speculative fill has no PC to replay and is cacheable by construction"): they are
    // NOT demand-only. IDLE's speculative-install arm writes BOTH of them today
    // (`missPC := pfPa(sel)`, `missCacheable := True`) and the dwell reads both, so
    // they are install context, not demand state. The conclusion (keep them as
    // scalars) is unchanged; only the reason is.
    //   - `missPC` selects the bypass capture window (`missPC(5)`/`missPC(4:3)`) and
    //     supplies REPLAY's `s0Pc`/`s0Lane`. For a speculative install its value is
    //     consumed by nothing (`s0FromMiss` is never armed), but it must still be
    //     written, because the capture happens unconditionally.
    //   - `missCacheable` gates `doAllocate`.
    val missPC    = Reg(UInt(32 bits))
    // Cacheability of the access that caused this miss, latched at miss-detect time
    // (the same instant mshrPa/mshrSet/mshrTag(DEMAND_IDX) are latched) so
    // REFILL/PREDECODE's allocate gate sees the SAME cache-mode the missing fetch
    // actually had. A speculative install forces it True (rule P1: an INHIBITED page
    // is never prefetched).
    val missCacheable = Reg(Bool())
    // ---- M2: the Unified MSHR line file --------------------------------------
    // ONE line-data structure for demand AND speculative fills, indexed by AXI ID.
    // Entry 0 is the demand MSHR (AxiIds.I_DEMAND == 0); entries 1..pfSlots are the
    // speculative slots (AxiIds.I_SPEC_BASE == 1), so the AXI RID IS the index -- no
    // decode, no asymmetry, no copy.
    //
    // WHY (design spec section 5.2). `lineReg` was a 512-flop bank whose D-input was a
    // 512-bit mux between an AXI beat and a 512-bit ASYNC READ of a 4-entry LUTRAM
    // (a 512 x 4:1 mux), and whose IDLE clock enable was
    // `pfInstallAny && !demandFillStart` -- a four-way reduction over four slot-state
    // vectors, AND-ed with a function of cmdPort.fire. Exactly the shape design
    // principle P2/P3 forbids. It is also the checkpoint-forensics diagnostic's named
    // landing zone for BOTH the B3 and P2 regressions, and the baseline's tied
    // runner-up worst path (FetchAlignPlugin_stalled_reg/C -> lineReg_reg[418]/D,
    // -1.472 ns, tying nominal WNS to three decimals).
    //
    // DRIFT NOTE (Task 8, verified against AxiIds.scala): the plan's text parenthesises
    // MSHR_N as "(6)" on the assumption pfSlots == 5. `AxiIds.I_SPEC_SLOTS` is
    // I_SPEC_LAST(4) - I_SPEC_BASE(1) + 1 == **4** (four physical speculative slots
    // maintaining a five-LINE logical lookahead -- see the frontier comment below), so
    // the real MSHR_N is 5 and mshrIdxBits is 3. The FORMULA `1 + pfSlots` is the plan's
    // and is used verbatim; only the parenthesised constant was stale.
    val MSHR_N = 1 + pfSlots                              // 5 = 1 demand + 4 speculative
    val mshrIdxBits = log2Up(MSHR_N)
    val fillLo = Mem(Bits(256 bits), MSHR_N)              // beat 0
    val fillHi = Mem(Bits(256 bits), MSHR_N)              // beat 1
    // Task icache-burst-fault-fix: PREDECODE's lineMem commit-beat phase — which
    // beat of the MSHR line-file entry (and which lineMem address) the SINGLE
    // write-port commit is
    // targeting THIS cycle of PREDECODE's 2-cycle dwell. See PREDECODE below for why
    // this must stay a single write-port/single-call-site design (SpinalHDL's
    // multi-write-Mem blackboxing broke on a two-call-site version of this fix).
    // Always starts a fresh refill's PREDECODE dwell at 0 (reset on wrap, below).
    val commitBeat = Reg(UInt(1 bits)) init U(0, 1 bits)
    // Task #211: latched across REFILL->FAULT — did any AXI read-beat response for
    // this refill come back with a non-OKAY resp (SLVERR/DECERR)? On an error the
    // line is NOT allocated (PREDECODE's tag/pred/valid writes are skipped entirely
    // — see the REFILL->FAULT transition below) and the fault is delivered via the
    // new FAULT state, mirroring DcachePlugin's missFault/busFaultResp (task #189).
    // M2c: this was `missBusFault`, a demand-only scalar; it is now `mshrErr(0)`, the
    // demand entry's lane of the uniform error field (the speculative slots' `pfErr`
    // was always the same bit under a different name).
    // Task icache-corruption-fix (review of 69a867c, "icache: wire xlate.rsp.cacheMode
    // into IcachePlugin"): that commit correctly gated tagMem/valids/victim-advance
    // under doAllocate for an INHIBITED-mode miss, but left the ACTUAL data/predecode
    // writes to the shared per-way arrays unconditional. The round-robin `victim`
    // pointer is the SAME pointer used by cacheable and INHIBITED misses alike — once
    // a set has taken >=4 real allocations it cycles back onto a way that is still
    // VALID and resident for some other, unrelated address. An unconditional write
    // there silently corrupts that other way's data/pred while its tag/valid stay
    // untouched (still claiming the OLD address is validly resident) -> the next
    // ordinary fetch to that address silently returns the WRONG bytes. Fix mirrors
    // DcachePlugin's `missLine`/`inhibitedResp` direct-delivery pattern exactly:
    // lineMem's write is now gated (REFILL, below) and a bypass register pair carries
    // the response instead. M2b (Task 8) narrowed the DATA half of that bypass the same
    // way M2a narrowed the predecode half: `bypWindow` (64 flops, declared with
    // `installIdx` below) replaces `lineReg`'s 512, latched UNCONDITIONALLY in
    // PREDECODE; `bypPred` is the predecode-side counterpart, latched on the same
    // cycle, so REPLAY can deliver an INHIBITED line's data AND predecode straight
    // from these two registers, entirely bypassing the (for that case, never-written)
    // Unified Fetch Array.
    //
    // M2a (Task 7): NARROWED, PRED_BITS_PER_LINE (256) flops -> 4*PRED_BITS_PER_WORD
    // (32); confirmed as `reg [31:0] IcachePlugin_logic_bypPred` in the generated
    // netlist, with `missPred` absent from it entirely. This used to be `missPred`, a
    // whole-PRED_BITS_PER_LINE-bit register selected by a whole-line
    // pc(5:3) window helper, purely because REPLAY was written to reuse the same
    // whole-line selector the array path used. Only ONE window is ever consumed:
    // REPLAY answers exactly ONE fetch, at `missPC`, and a FetchRsp is one 64-bit
    // window = 4 words of predecode. So capture exactly that window, on whichever
    // dwell cycle classifies the beat `missPC(5)` names.
    //
    // EQUIVALENCE, spelled out because this is the whole review surface of M2a. The
    // old read was `windowPredLine(missPred, s0Pc)` = word-group `s0Pc(5 downto 3)` of
    // the packed line, and REPLAY sets `s0Pc := missPC`, so it was word-group
    // missPC(5:3) = {beat missPC(5), lane missPC(4:3)}. The new capture takes
    // `beatPred`'s lane `missPC(4:3)` on the cycle `commitBeat === missPC(5)` -- and
    // `beatPred` on that cycle is exactly the classification of beat `commitBeat`
    // (`beatSrc = Mux(isLoBeat, fillLoQ, fillHiQ)`). Same 4 chunks,
    // same order (`subdivideIn` index 0 = LSB = lowest address throughout this file).
    //
    // WRITE/READ ORDERING, unchanged from the M1b split-write argument and RE-VERIFIED
    // for M2b's PF_PRED->PREDECODE merge: this register is read ONLY on the
    // `s0FromMiss` bypass, armed in REPLAY. `s0FromMiss` is a Reg, so the S1->rsp mux
    // that consumes `bypPred`/`bypWindow` evaluates on the cycle AFTER REPLAY -- the
    // IDLE cycle. A SPECULATIVE install writes both registers too (PF_PRED always did)
    // and never arms `s0FromMiss`, and it can only be armed from IDLE; under M2b it
    // then spends a whole INSTALL_ARM cycle before its PREDECODE dwell writes them, so
    // the earliest speculative overwrite is TWO cycles after the IDLE cycle that
    // consumes them (one cycle under the old PF_PRED shape). The margin got WIDER, not
    // narrower.
    //
    // MISS-PC IMMUTABILITY (the invariant this capture rests on, carried forward from
    // M2a's review and re-verified for M2b). The capture selects its window with
    // `missPC(5)` / `missPC(4:3)` on one dwell cycle and REPLAY re-reads `missPC` on a
    // later cycle, so `missPC` must not change during the dwell. It cannot:
    // `missPC` has exactly TWO writers, and after M2b both are still confined to IDLE.
    //   (1) `lookupTick`'s miss arm. `lookupTick` is called from IDLE
    //       (canStartFill = true) and -- new in M2b -- from PREDECODE when
    //       `predIsPfReg` (canStartFill = false, inherited verbatim from PF_PRED).
    //       With canStartFill = false, `answerable = lookupFault || isHit`, and the
    //       miss arm is the `otherwise` of `when(lookupFault) ... elsewhen(isHit)`, so
    //       it requires `!lookupFault && !isHit` -- which makes `answerable` false,
    //       hence `cmdPort.ready` false, hence `cmdPort.fire` false. The arm is
    //       structurally unreachable outside IDLE.
    //   (2) IDLE's speculative-install arm, textually inside `IDLE.whenIsActive`.
    // INSTALL_ARM and REPLAY call neither. So `missPC` is frozen from the cycle the
    // dwell is armed until the machine returns to IDLE. Pinned by IcacheSpec's
    // "M2a: the narrowed predecode bypass delivers the correct WINDOW" and
    // "M2b: missPC is immutable across the whole PREDECODE dwell" tests.
    val bypPred = Reg(Bits(4 * PRED_BITS_PER_WORD bits))

    // ── Slice I1, closure (2) of the `invalidateAll` hazard (design doc §6.5) ────
    // An `invalidateAll` that lands while a fill is in flight must not be undone by
    // that fill completing and re-installing the line it was told to drop. Closure (1)
    // (the fill's `valids` write yields to a SAME-cycle invalidate) already exists;
    // it does nothing for an invalidate that arrives one cycle EARLIER, mid-burst.
    // So: poison the in-flight fill. A poisoned fill still runs to completion on the
    // bus (there is no transaction-cancel in AXI -- design doc §10.F) and still
    // delivers its response (see below), but allocates NOTHING.
    //
    // §6.5's bullet 3 ("invalidate the s1*/rsp*Reg stages") is DELIBERATELY NOT
    // ADOPTED, and this is the reasoning, recorded so it is not re-derived:
    // `FetchAlignPlugin` runs a depth-3 outstanding ring whose `ringCount` is
    // incremented on `ic.cmd.fire` and decremented ONLY on `ic.rsp.valid`
    // (FetchAlignPlugin.scala:171-190, :302-312). There is no timeout and no other
    // decrement path. Cancelling an in-flight response would strand that ring entry
    // forever: `ringCount` stays elevated, `ringFull` eventually blocks `ic.cmd.valid`
    // permanently, and the front end stops fetching with no recovery -- a hard hang
    // CREATED by the fix. The response is therefore always delivered; the data is
    // still what was at that address when the fetch was issued, and CINV/CPUSH is
    // architecturally a software synchronisation point. If a future requirement really
    // needs the in-flight fetch discarded, the correct mechanism is FetchAlign's
    // EXISTING `ringStale` bit (consumed-and-discarded, ring still retires) -- a
    // FetchAlign change, not an IcachePlugin one.
    // M2c: this was `missPoison`, a demand-only scalar; it is now `mshrPoison(0)`, the
    // demand entry's lane of the uniform poison field, and the "poison every live fill
    // on an invalidate" rule is ONE loop over the whole file instead of one loop over
    // the speculative slots plus a separate `when(anyInvalidate && !IDLE)` line.

    // ---- M2c: the unified MSHR CONTROL file ----------------------------------
    // Entry 0 = the demand MSHR (AxiIds.I_DEMAND). Entries 1..pfSlots = the speculative
    // slots (AxiIds.I_SPEC_BASE..I_SPEC_LAST). Every field that used to exist twice --
    // once as a scalar `miss*`/`beatCnt`/`arSent` for demand and once as a pfSlots-wide
    // Vec for speculation -- is ONE Vec now, and the AXI RID is the index into it.
    // Together with M2b's `fillLo`/`fillHi` line file (indexed the same way) that makes
    // the whole MSHR -- data AND control -- uniform.
    //
    // HONEST ACCOUNTING (spec section 5.2). This is flop-NEUTRAL to within 2 bits, and
    // that figure is MEASURED, not estimated: counting sequentially-assigned `reg` bits
    // whose names begin `IcachePlugin_` in `generated/M68kFullCoreSynth.v` gives
    // 5,548 bits before this commit and 5,550 after, i.e. **+2**. Entry 0 adds 66 FF of
    // control state; the demand scalars it replaces are 62 FF (missPA 32 + missSet 6 +
    // missTag 20 + beatCnt 1 + arSent 1 + missBusFault 1 + missPoison 1 -- victimWay's 2
    // are a rename to installWay, not a deletion) and pfInstallIdx's 2 go with them.
    // `mshrComplete(DEMAND_IDX)` is still EMITTED as a reg (it has a reset), and is
    // included in the +2. Task 9 review fix I1 (post-landing hardening, before Task 10):
    // it IS now driven True on entry 0's own last R beat, same as every other entry --
    // see the R-channel handler below -- so it no longer relies on constant-folding an
    // always-False signal; it is a genuinely uniform field. GC-1 still defers synth
    // measurement here, so the FF-count claim above (measured pre-I1) is otherwise
    // unchanged: I1 redirects an existing write enable, it adds no new register.
    // The value of this change is in spec section 9.5's fallback column -- deleting the
    // demand/speculative asymmetry from the file the ledger has repeatedly found
    // hardest to reason about -- not in a flop count. GC-1 forbids measuring its FMax
    // effect here, deliberately.
    //
    // BEHAVIOUR-NEUTRALITY, likewise measured rather than argued: the full (cycle, AXI
    // id, address) AR trace for a fixed 108-command stimulus (oracle 1's hostile mix
    // followed by a 96-line sequential stream) is byte-identical across this commit --
    // 101 AR fires, same cycles, same ids, same addresses. No state was added to or
    // removed from the FSM, so there is no latency change in either direction.
    //
    // ENTRY 0's SEMANTICS, spelled out because M2c is exactly where getting them wrong
    // is silent:
    //   - `mshrValid(0)` means "the demand MSHR is live and OWNS `mshrSet(0)`". It is
    //     set on the IDLE miss-detect that starts a refill and cleared on the way back
    //     to IDLE (REPLAY / FAULT), so it is True across REFILL, INSTALL_ARM(demand),
    //     PREDECODE(demand), REPLAY and FAULT -- exactly the state list `demandSetOwned`
    //     used to enumerate by hand.
    //     DELIBERATE DEVIATION from the plan's Step 5 note ("an mshrValid(0) would be a
    //     second, redundant source of truth"): it is not redundant, it REPLACES the
    //     hand-written state list, and without it this task's load-bearing deliverable
    //     -- oracle 3 covering entry 0 -- would be vacuous (an always-False
    //     `mshrValid(0)` can never appear in the oracle's `live` set). The R-channel
    //     demand match deliberately does NOT read it (it stays on `refillActive`), so
    //     there is no second source of truth for the bus-side liveness either.
    //   - `mshrComplete(0)`: UPDATED by Task 9 review fix I1. It is now written True on
    //     entry 0's own R-channel last beat, exactly like every other entry -- the demand
    //     path's completion is STILL primarily the FSM's `refillDone` (that
    //     `refillDone`/`refillErr` handoff is not a redundant copy of `mshrComplete`; it
    //     is what tells the FSM the response is owed), but `mshrComplete(DEMAND_IDX)` is
    //     no longer left permanently False. It is reset False on every new demand
    //     allocation (the IDLE miss-detect arm, alongside `mshrArSent`/`mshrBeat`/
    //     `mshrErr`/`mshrPoison`) and is only ever read paired with `mshrValid` by every
    //     current and (intended) future consumer, so a stale True surviving after REPLAY/
    //     FAULT until the next allocation is inert.
    //   - every other entry-0 field carries exactly what its old `miss*` scalar did.
    val DEMAND_IDX   = AxiIds.I_DEMAND      // 0
    val mshrValid    = Vec.fill(MSHR_N)(RegInit(False))
    val mshrArSent   = Vec.fill(MSHR_N)(RegInit(False))
    val mshrComplete = Vec.fill(MSHR_N)(RegInit(False))
    val mshrBeat     = Vec.fill(MSHR_N)(RegInit(False))
    val mshrErr      = Vec.fill(MSHR_N)(RegInit(False))
    val mshrPoison   = Vec.fill(MSHR_N)(RegInit(False))
    val mshrPa       = Vec.fill(MSHR_N)(Reg(UInt(32 bits)))
    val mshrSet      = Vec.fill(MSHR_N)(Reg(UInt(setBits bits)))
    val mshrTag      = Vec.fill(MSHR_N)(Reg(UInt(tagBits bits)))
    val mshrWay      = Vec.fill(MSHR_N)(Reg(UInt(wayBits bits)))

    // The install target, registered one cycle AHEAD of the dwell so the file read is
    // synchronous. Set in INSTALL_ARM's two entry arms (REFILL's clean completion and
    // IDLE's speculative install); stable for the whole PREDECODE dwell.
    val installIdx = Reg(UInt(mshrIdxBits bits)) init U(0, mshrIdxBits bits)
    // SG-3 (plan, exception 2): a dedicated register rather than mshrSet(installIdx).
    // The D3-SET-I guard `fillArrayWrActive && (lookupSet === installSet)` sits in the
    // ACCEPT cone; sourcing it from a Vec index would put a 5:1 mux in front of that
    // comparator, in exactly the cone this whole design exists to shorten. One register
    // instead -- and the same register also supplies ALL FIVE array-write ADDRESS paths
    // in the dwell (`lineMem` / `tagMem` / `valids` / `pfFilled` / `victim`), which is
    // strictly correct because it was loaded from exactly the entry being installed.
    // Introduced in Task 8 (M2b) and maintained in lock-step with the now-deleted
    // `missSet`; M2c switches every one of those consumers over to it, so the switch is
    // a no-op by construction.
    val installSet = Reg(UInt(setBits bits))
    // SG-3 (plan, exception 3): likewise a dedicated register rather than
    // mshrWay(installIdx) -- it feeds a 4-way decoded `when(installWay === U(w))` on
    // EVERY array-write path, so a 5:1 mux in front of it would be replicated four
    // times. This is the old `victimWay` renamed: `mshrWay` still records the victim at
    // ALLOCATE time (that is the field spec section 5.2 lists), and `installWay` is
    // loaded from it once, on the arm into INSTALL_ARM.
    val installWay = Reg(UInt(wayBits bits))
    // The synchronous read of the line being installed. KeepAttribute per GC-7: if
    // these become fabric flops, lineReg has been re-created under a different name.
    val fillLoQ = fillLo.readSync(installIdx)
    val fillHiQ = fillHi.readSync(installIdx)
    KeepAttribute(fillLoQ)
    KeepAttribute(fillHiQ)
    // M2b: the DATA half of the narrowed bypass (the predecode half is Task 7's
    // `bypPred`). 64 flops replace `lineReg`'s 512 for the INHIBITED/poisoned replay.
    // See `bypPred`'s declaration above for the shared capture-window equivalence and
    // the write/read-ordering + missPC-immutability arguments that cover both.
    val bypWindow = Reg(Bits(64 bits))

    // Registered sequential frontier. It is seeded only by an accepted, resolved,
    // cacheable demand and is capped at five same-page lines ahead. Installed lines
    // free speculative slots before demand reaches them, allowing four physical
    // speculative slots to maintain the five-line logical lookahead.
    val pfSeqValid   = RegInit(False)
    val pfDemandLine = Reg(UInt(32 bits))
    val pfNextPa     = Reg(UInt(32 bits))
    val pfLimitPa    = Reg(UInt(32 bits))

    // M2c: `mshrSet` joins the sim-public set. Oracle 3 previously had to RECONSTRUCT
    // each speculative slot's set from the address of the last AR issued under that
    // slot's AXI id, purely because `pfSet` was not public (see the deviation note in
    // IcacheOrderOracleSpec). It now reads the register directly -- and that is what
    // lets it cover entry 0, whose set never appears on the bus at a time the old
    // reconstruction could attribute it.
    mshrValid.simPublic()
    mshrArSent.simPublic()
    mshrComplete.simPublic()
    mshrSet.simPublic()

    // ---- shared unified-array read port (synchronous; BRAM) ----
    // Address+enable are driven by the FSM (IDLE hit accept, or REPLAY), from
    // PAGE-INVARIANT virtual bits only. The result `ufaBeat` is the registered memory
    // output, valid the NEXT cycle.
    val ufaReadAddr = UInt((setBits + 1) bits)
    val ufaReadEn   = Bool()
    ufaReadEn.simPublic()
    val xlateReadyDbg = Bool()
    xlateReadyDbg := xlate.rsp.ready
    xlateReadyDbg.simPublic()
    ufaReadAddr := U(0, (setBits + 1) bits)
    ufaReadEn   := False
    val ufaBeat = Vec(lineMem.map(_.readSync(ufaReadAddr, ufaReadEn)))
    // Spec §4.3 N-3 / GC-7: the RAM output register IS the pipeline register.
    // KeepAttribute stops synthesis replicating it or absorbing it back into fabric --
    // if it does, M1 delivers nothing and `lineReg`/the deleted S1 predecode capture
    // bank have been re-created under different names. VERIFIED in the emitted netlist:
    // each `ufaBeat_w` carries
    // `(* keep, syn_keep *)`. Note it lands on the WIRE that aliases the inferred RAM's
    // output register (`lineMem_w_spinal_port0`), not on that register's declaration --
    // enough to stop the net being optimised through, but the actual "did the output
    // register stay inside the BRAM" question is only answerable at the Task 6 Step 8
    // synth-only gate (risk R1, memory inference), which GC-1 defers.
    ufaBeat.foreach(b => KeepAttribute(b))
    def ufaData(w: Int): Bits = ufaBeat(w)(255 downto 0)
    def ufaPred(w: Int): Bits = ufaBeat(w)(UFA_W - 1 downto 256)
    // Task 5 transitional: `s1Way` is still a registered UInt here (M3/Task 11 replaces
    // it with a one-hot). Vec-index the per-way slices so the existing S1 mux keeps its
    // exact shape.
    val ufaDataVec = Vec((0 until ways).map(w => ufaData(w)))
    val ufaPredVec = Vec((0 until ways).map(w => ufaPred(w)))

    // ---- S0: the accept-cycle capture stage (M3a, Task 10) --------------------
    // NAMING, and it is load-bearing (spec §5.3.3). This is the SAME physical
    // register set that was called `s1*` up to Task 9: it is written IN the accept
    // cycle and read the NEXT cycle, so "s1" only ever described it from the
    // consumer's point of view. Task 11 adds a genuinely new S1 COMBINATIONAL stage
    // (the verdict, the one-hot way mux); renaming now is what stops two different
    // things both being called "s1". The `s1Way` register below is deliberately NOT
    // renamed -- it is the one member of the old set that M3 DELETES rather than
    // moves (Task 11 computes the way at S1 instead of carrying it), so it keeps its
    // old name for exactly as long as it survives.
    //
    // THE ITLB RESULT STOPS AT `s0Ppn`/`s0Fault`/`s0Cacheable`. That is the whole
    // content of design principle P3 and the reason the census's clock-enable
    // endpoints in this plugin can stop being fed by a translation-fed comparator.
    // At THIS task nothing downstream consumes the registered verdict yet -- the live
    // `isHit` still drives `cmdPort.ready`, the response path, the MSHR writes and the
    // prefetch/AR paths, exactly as before. Task 11 (M3b) flips the consumers over.
    //
    // MEASURED COST (`generated/M68kFullCoreSynth.v`, both commits regenerated and the
    // `IcachePlugin_*` register declarations diffed): **+114 bits**, and that is the
    // ONLY register change anywhere in the plugin. +112 of it is the S0 context M3
    // needs (`s0Set` 6, `s0Beat` 1, `s0Ppn` 20, `s0Cacheable` 1, `tagQ` 4x20, `validsQ`
    // 4x1); +2 is the transitional shadow (`dbgS0Fresh`, `dbgLiveHitQ`), deleted in
    // Task 11. The `s1*` -> `s0*` rename is bit-neutral (38 bits in, 38 out). The
    // `tagMem` port count is UNCHANGED at 2 async reads + 1 write per way -- the reason
    // `lookupTags` is hoisted below rather than a second `readAsync` being elaborated.
    val s0Valid = Reg(Bool()) init False
    // Transitional (deleted in Task 11): the registered hit way, still driving the
    // S1->rsp mux below.
    val s1Way   = Reg(UInt(wayBits bits))
    val s0Pc    = Reg(UInt(32 bits))
    // HONEST ACCOUNTING, netlist-verified rather than assumed: `s0Set` and `s0Beat`
    // have NO RTL reader at this task -- their consumers (the S1 array re-address and
    // the D3-SET-I guard) land in Tasks 11/12. They are nevertheless emitted as real
    // registers in `generated/M68kFullCoreSynth.v` (`simPublic` keeps them), so this
    // task's true cost is 7 flops of not-yet-used state, counted in the +114 below.
    val s0Set   = Reg(UInt(setBits bits))    // virtual, page-invariant: lookupPc(11:6)
    val s0Beat  = Reg(Bool())                // virtual, page-invariant: lookupPc(5)
    val s0Ppn   = Reg(UInt(tagBits bits))    // <-- the translation terminates here
    val s0Cacheable = Reg(Bool())            // ...and here (cacheMode =/= INHIBITED)
    val s0Fault = Reg(Bool())
    // Task #211: which cause armed s0Fault — True = ITLB/MMU translation fault,
    // False = a physical AXI bus error caught in REFILL (new FAULT
    // state below). Only meaningful when s0Fault is set; rides to rsp.payload.atc.
    val s0Atc   = Reg(Bool())
    val s0Lane  = Reg(UInt(2 bits))
    // M1b: the 4 x PRED_BITS_PER_LINE S1 predecode CAPTURE BANK is GONE. Predecode
    // now rides out of the SAME synchronous array as the data (`ufaBeat`), so the
    // way-mux + window-decode already run off REGISTERED state (s1Way/s0Pc) with no
    // capture register of their own, and the arc that owned every one of the worst 300
    // failing endpoints on the pinned routed checkpoint no longer has an endpoint.
    // The bank's all-zero-on-fault placeholder becomes an explicit S1 mask below.
    //
    // Task icache-corruption-fix: True only for a REPLAY of a non-allocated
    // (INHIBITED-mode) miss — routes the S1->rsp mux below to deliver straight from
    // the `bypWindow`/`bypPred` bypass registers instead of the Unified Fetch Array
    // (which was never written for that case). Explicitly set at
    // EVERY s0Valid-arming site (mirrors s0Fault/s0Atc), never left to a stale value.
    val s0FromMiss = Reg(Bool())
    // ---- M3a: the registered tag/valid context the S1 verdict compares against ----
    // Spec §14 Q1's RECOMMENDED DEFAULT, and NaxRiscv's own FPGA choice (§4.3 N-1,
    // `tagsReadAsync = withDistributedRam`): keep `tagMem` as distributed RAM and
    // REGISTER its async read, rather than converting it to a synchronous memory.
    // Same "compare registered against registered" property, 4 x tagBits flops, no
    // BRAM-latency question on the tag path, and a trivially reviewable diff.
    //
    // Captured UNCONDITIONALLY on `cmdPort.fire` (see `lookupTick` below) -- one
    // shallow enable, the same enable the rest of the S0 context uses. Capturing them
    // inside any of the fault/hit/miss arms would be a real bug: the miss arm is
    // exactly the case Task 11's `s1Unresolved` needs them for.
    val tagQ    = Reg(Vec(UInt(tagBits bits), ways))
    val validsQ = Reg(Vec(Bool(), ways))
    s0Valid := False   // default each cycle; armed in IDLE-hit / REPLAY below
    // Sim-only visibility for `IcacheVerdictShadowSpec` (and, from Task 11, for the
    // consumers' own directed tests). No synthesised logic.
    s0Valid.simPublic(); s0Pc.simPublic(); s0Set.simPublic(); s0Beat.simPublic()
    s0Lane.simPublic(); s0Ppn.simPublic(); s0Cacheable.simPublic(); s0Fault.simPublic()

    // ---- rsp output register stage ----
    val rspValidReg = Reg(Bool()) init False
    val rspPcReg    = Reg(UInt(32 bits))
    val rspDataReg  = Reg(Bits(64 bits))
    val rspFaultReg = Reg(Bool())
    val rspAtcReg   = Reg(Bool())
    val rspPredReg  = Reg(Vec(ChunkPredecode(), 4))

    // ---- window predecode helper ----
    // A "window" = 4 words (8 bytes, matching the 64-bit FetchRsp granularity). A 64B line
    // holds 32/4=8 windows, selected by pc(5:3) (3 bits, 0..7 — independent of
    // PRED_BITS_PER_WORD, so this selector width is unaffected by the F1/F2/F3 widening).
    // Each window-chunk is 4*PRED_BITS_PER_WORD bits (was the hardcoded 16 = 4*4 when
    // ChunkPredecode was 4 bits); each of the 4 per-window entries is PRED_BITS_PER_WORD
    // bits (was the hardcoded 4).
    /** Slice I2 (MSHR design doc §6.4, option I-b) — classify the 16 words of ONE beat.
      *
      * WAS: a single 32-way unrolled group inside PREDECODE, i.e. 32 independent
      * instances of a ~860-line combinational decode tree (order 860 EA-length
      * decoders and 160 3-bit adders) elaborated once and fired once per refill. That
      * block is the largest single suspected LUT driver in the front end (§1.5,
      * §11.Q8) and it is also the structural obstacle to I-side MSHRs, because it
      * needs the WHOLE line present in one cycle.
      *
      * NOW: ONE 16-instance group, elaborated once and USED TWICE — on PREDECODE's
      * commitBeat==0 cycle for the low beat and on its commitBeat==1 cycle for the
      * high beat. PREDECODE already dwelt exactly 2 cycles (the single-write-port
      * lineMem commit, see below), so this is a **2x instance cut at exactly today's
      * latency** — no extra cycle anywhere.
      *
      * DEVIATION FROM THE PLAN, RECORDED DELIBERATELY: the ratified plan's I2 section
      * computes a 4x cut (32 -> 8) and a ~-190-flop `lineReg` deletion. Both of those
      * numbers assume slice V2b (narrow the I-cache AXI master 256 -> 128 bits, so a
      * beat is 8 words and a line is 4 beats) has landed first. V2b is socket-
      * conformance work and is OUT OF SCOPE for the IPC-push initiative this landed
      * under, so the recomputed figures for today's 256-bit / 2-beat geometry are:
      * **32 -> 16 instances (2x, the design doc's original I-b number)** and `lineReg`
      * RETAINED. That retention argument -- both beats must be held somewhere because
      * the icache-burst-fault fix DEFERS the lineMem commit until the whole burst's
      * pass/fail is known, and the same storage doubles as the INHIBITED
      * direct-delivery source -- was correct, but it only ever justified holding the
      * line, not holding it in a 512-flop register. M2b (Task 8) supplies both jobs from
      * the `fillLo`/`fillHi` MSHR line file instead: the file holds both beats until the
      * dwell reads them, and the INHIBITED/poisoned direct delivery is the pre-selected
      * 64-bit `bypWindow` captured out of that read. `lineReg` is DELETED.
      * Slice I2 originally added one
      * beat's worth of predecode held for one cycle so a whole-line packed value could
      * be assembled combinationally; M1b deleted that staging register by writing the
      * bypass register's two halves directly, one per dwell cycle, and M2a narrowed
      * that register to a single window (`bypPred`). NET NEW STATE: zero.
      *
      * `beat`      : the beat being classified (one entry-half of the MSHR line file).
      * `nextLo`    : the NEXT beat's low 3 words, supplying the lookahead for this
      *               beat's last 3 words.
      * `nextValid` : whether `nextLo` is real data. False for the LAST beat of a line,
      *               where the lookahead genuinely runs past the line end — the same
      *               `extWValid = false` boundary discipline the whole-line predecode
      *               already used (the F5 fix), which makes `classify` reject as
      *               COMPLEX rather than guess brief-vs-full, or take the documented
      *               assume-brief extW3 fallback. `ambiguousLine` then marks it and
      *               `Aligner.scala:63-65` re-classifies live from the instruction
      *               buffer's own already-fetched words.
      *
      * *** ZERO NEW AMBIGUITY. *** Exhaustively checked in
      * `PerBeatPredecodeEquivSpec`: for every one of the 32 absolute word positions,
      * this scheme presents `classify` the SAME three lookahead WORDS and the SAME
      * three validity FLAGS the whole-line scheme did. Only the final 3 words of the
      * line see `valid = false`, which is exactly the case that already existed. */
    def classifyBeat(beat: Bits, nextLo: Bits, nextValid: Bool): Bits = {
      val w  = beat.subdivideIn(16 bits)      // WORDS_PER_BEAT words, index 0 = lowest addr
      val nx = nextLo.subdivideIn(16 bits)    // 3 words of the next beat
      def word(i: Int): Bits =
        if (i < WORDS_PER_BEAT) w(i) else Mux(nextValid, nx(i - WORDS_PER_BEAT), B(0, 16 bits))
      def wordValid(i: Int): Bool =
        if (i < WORDS_PER_BEAT) True else nextValid
      Vec((0 until WORDS_PER_BEAT).map { i =>
        PredecodeWord.classify(
          w(i), word(i + 1), word(i + 2), word(i + 3),
          extWValid  = wordValid(i + 1),
          extW2Valid = wordValid(i + 2),
          extW3Valid = wordValid(i + 3))
      }).asBits
    }

    /** M1b: beat-granular window select. A "window" = 4 words (8 bytes, the FetchRsp
      * granularity). A 32-byte BEAT holds 4 windows, selected by pc(4:3) -- one bit
      * narrower than the old whole-line pc(5:3), because the beat that reaches S1 was
      * already selected by pc(5) when the array was addressed.
      *
      * `subdivideIn` yields index 0 = LOWEST bits (the same convention `classifyBeat`'s
      * `beat.subdivideIn(16 bits)` and the data path's `s1Window` beat subdivision
      * already rely on), so window `pc(4:3)` here is the same 4 words the old
      * whole-line form selected with pc(5:3) once pc(5) has picked the beat, and the
      * 4 per-word chunks keep their ascending-address order. */
    def windowPredBeat(entry: Bits, pc: UInt): Vec[ChunkPredecode] = {
      val win  = entry.subdivideIn(4 * PRED_BITS_PER_WORD bits)(pc(4 downto 3))
      val nibs = win.subdivideIn(PRED_BITS_PER_WORD bits)
      Vec(nibs.map(b => b.as(ChunkPredecode())))
    }

    // M2a (Task 7): the LEGACY whole-line form (`windowPredLine`, a pc(5:3) select over
    // a PRED_BITS_PER_LINE-bit packed line) is DELETED with its only caller. The
    // INHIBITED/poisoned bypass now holds a pre-selected single window (`bypPred`), so
    // nothing in the design selects a window out of a whole packed line any more.

    // ---- S1 -> rsp output register (runs every cycle; meaningful when s0Valid) ----
    // Way-mux the registered raw beats/pred by s1Way, then window-decode — all off
    // REGISTERED state (s1Way/s0Lane/s0Pc), so neither the data nor the pred select
    // is in the IDLE hit cone.
    // Task icache-corruption-fix: bypass mux — for a REPLAY of a non-allocated
    // (INHIBITED) miss (s0FromMiss), deliver directly from the miss-latch registers
    // (mirrors DcachePlugin's inhibitedResp/missLine) instead of the shared arrays,
    // which were never written for that line.
    //
    // M2b: `missDataBeat`'s 512-bit half-select is gone with `lineReg`; the bypass is
    // already the exact 64-bit window REPLAY will deliver. EQUIVALENCE: the old form
    // selected beat `s0Pc(5)` of the 512-bit `lineReg` and then lane `s0Lane` of it;
    // REPLAY sets `s0Pc := missPC` and `s0Lane := missPC(4 downto 3)`, so the delivered
    // window was {beat missPC(5), lane missPC(4:3)} -- exactly the window the dwell
    // captures into `bypWindow` on the cycle `commitBeat === missPC(5)`.
    val s1Window = Mux(s0FromMiss, bypWindow,
                                   ufaDataVec(s1Way).subdivideIn(64 bits)(s0Lane))
    // M1b: predecode now rides out of the SAME synchronous array as the data, selected
    // by the same registered way (`s1Way`) and a one-bit-narrower window index. The old
    // path (4 x PRED_BITS_PER_LINE async-LUTRAM reads captured into 1,024 flops) is gone.
    //
    // Fault placeholder (spec §5.1): the deleted S1 capture bank was written
    // all-zero on a translation fault (and in the task-#211 FAULT state), so the
    // window select of it delivered a zeroed predecode. With no such register -- and with
    // `ufaBeat` carrying whatever the speculatively-armed array read returned for the
    // faulting address's set/beat, under a FORCED s1Way of 0 -- S1 must mask explicitly.
    // One 2:1 mux on the S1->rsp path (one LUT level), preserving the exact
    // zeroed-predecode behaviour Aligner/DecodeStage already rely on. Pinned by
    // IcacheUnifiedArraySpec's "M1b: a translation fault delivers ZEROED predecode".
    //
    // s0Fault and s0FromMiss are never both set (REPLAY forces s0Fault := False), so the
    // mask cannot disturb the INHIBITED/poisoned bypass.
    //
    // M2a: BOTH arms are now ONE window wide (4 * PRED_BITS_PER_WORD). The bypass arm
    // needs no window select at all -- `bypPred` was captured pre-selected at
    // missPC(5:3) during the predecode dwell, which is exactly the window
    // `windowPredLine(missPred, s0Pc)` used to extract here (REPLAY sets s0Pc := missPC).
    val s1PredBits = Mux(s0FromMiss, bypPred,
                                     windowPredBeat(ufaPredVec(s1Way), s0Pc).asBits)
    val s1PredW    = Vec(Mux(s0Fault, B(0, s1PredBits.getWidth bits), s1PredBits)
                           .subdivideIn(PRED_BITS_PER_WORD bits)
                           .map(b => b.as(ChunkPredecode())))

    rspValidReg := s0Valid
    rspPcReg    := s0Pc
    rspDataReg  := s1Window
    rspFaultReg := s0Fault
    rspAtcReg   := s0Atc
    rspPredReg  := s1PredW

    // ---- rsp outputs (combinational from the registered stage) ----
    rspPort.valid         := rspValidReg
    rspPort.payload.pc    := rspPcReg
    rspPort.payload.data  := rspDataReg
    rspPort.payload.fault := rspFaultReg
    rspPort.payload.atc   := rspAtcReg
    rspPort.payload.pred  := rspPredReg

    // ---- default output assignments ----
    cmdPort.ready := False
    axi.ar.payload.assignDontCare()

    // ---- parallel VIPT hit detection ----
    // The page-invariant virtual set/beat independently arms the data BRAM. The live
    // ITLB PPN participates only in the tag compare and terminates at the S1 control
    // registers; it cannot reach the BRAM address/enable or the 256-bit data mux.
    val lookupSet = lookupPc(11 downto 6)
    val lookupTag = lookupPaddr(31 downto 12)
    // M3a: the async tag read is HOISTED to a named signal. It used to be inlined in
    // `hitVec` below; the S0 capture (`tagQ`) needs the SAME value, and a second
    // `tagMem(w).readAsync(lookupSet)` call would elaborate a SECOND async read port on
    // each way's distributed RAM -- i.e. duplicate the LUTRAM. One port, two readers.
    // Naming it also makes the capture provably the same net the live comparator uses,
    // which is what the equivalence assertion below is comparing storage forms of.
    val lookupTags   = Vec((0 until ways).map(w => tagMem(w).readAsync(lookupSet)))
    val lookupValids = Vec((0 until ways).map(w => valids(w)(lookupSet)))
    val hitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      hitVec(w) := lookupCacheable && lookupValids(w) && (lookupTags(w) === lookupTag)
    val isHit       = hitVec.orR
    val hitWayIdx   = OHToUInt(hitVec)
    val lookupBeatSel   = lookupPc(5)
    val lookupLaneIdx   = lookupPc(4 downto 3)
    val lookupReadAddr  = (lookupSet ## lookupBeatSel).asUInt

    // ══ M3a (Task 10): the S1 verdict, computed from REGISTERED inputs only ═══════
    // A tagBits-wide equality against a registered PPN, a 4-way OR, and an AND with
    // `s0Valid`. ~3 LUT levels off flop outputs -- versus the LIVE path's
    // ITLB mux -> async LUTRAM read -> compare -> OR -> `answerable` -> `cmdPort.ready`,
    // which is the 20-level, 66 %-route arc that `synth/floorplan_frontend.xdc` names as
    // all 300 worst failing endpoints on the pinned routed checkpoint.
    //
    // NOTHING CONSUMES THESE YET. This task is behaviour-neutral by construction: the
    // live `isHit` still drives `cmdPort.ready`, `s1Way`, the S1 arming, the MSHR
    // allocation and `heldDemandMiss`. Task 11 (M3b) flips the consumers and deletes the
    // live comparator. The names below are the exact ones Tasks 11/12 use.
    val s1HitVec = Vec((0 until ways).map(w =>
      s0Cacheable && validsQ(w) && (tagQ(w) === s0Ppn)))
    val s1Hit    = s1HitVec.orR
    // Held high while an accepted command's miss has not yet been dispatched to a fill.
    // Task 11 makes this gate `cmdPort.ready`; this task only computes it.
    val s1Unresolved = s0Valid && !s0Fault && !s1Hit
    s1Hit.simPublic(); s1Unresolved.simPublic(); s1HitVec.foreach(_.simPublic())

    // ---- Task 10 (M3a) TRANSITIONAL shadow-equivalence check --------------------
    // Deleted in Task 11 together with the live comparator.
    //
    // WHY THIS IS NOT A TAUTOLOGY, spelled out because Task 5's review caught the design
    // spec's own suggested check being one. The two sides are DIFFERENT STORAGE of the
    // same instant, not two spellings of one expression:
    //   - LIVE side: the 1-bit result of the live comparator, latched at the accept edge
    //     (`dbgLiveHitQ`). Four 20-bit compares and a 4-way OR happen BEFORE the flop.
    //   - REGISTERED side: 4 x tagBits + 4 valid bits + tagBits of PPN + 1 cacheable bit
    //     are latched at that same edge (105 flops), and the four compares and the OR are
    //     re-evaluated AFTER the flops, from those registers.
    // A capture written under the wrong ENABLE (inside a fault/hit/miss arm rather than
    // unconditionally on `cmdPort.fire`), at the wrong INDEX (`s0Set` instead of
    // `lookupSet`, i.e. one command stale), with the wrong BIT RANGE on the PPN, or with
    // a dropped term (`s0Cacheable`) all make the two disagree -- and all four are
    // mutation-proven in `IcacheVerdictShadowSpec`. What it deliberately does NOT claim
    // to prove is that the LIVE verdict is itself correct; that is oracle 1/2's and the
    // lock-step suite's job, and neither side of this check is changed by this task.
    //
    // GATE: `dbgS0Fresh`, i.e. "the previous cycle was an accepted command", NOT the
    // plan's literal `s0Valid`. DELIBERATE AND STRICTLY STRONGER. `s0Valid` is armed
    // only on the FAULT and HIT arms -- a demand MISS leaves it False -- so an `s0Valid`
    // gate would never check the verdict on the one case the registered path exists to
    // handle (Task 11's `s1Unresolved` is a MISS signal). With `dbgS0Fresh` every
    // accepted command is checked, hit, miss and fault alike.
    //
    // NO FAULT EXEMPTION, also deliberate, also strictly stronger than the plan's text.
    // The plan exempts `dbgLiveFaultQ` on the grounds that a faulting translation makes
    // `isHit` meaningless. It does make it meaningless, but it does not make it
    // DIFFERENT: both sides sample the very same `lookupCacheable`/`lookupValids`/
    // `lookupTags`/`lookupTag` nets on the very same edge, so whatever garbage a faulting
    // ITLB response puts on them is captured identically by both. Exempting faults would
    // silently drop coverage of every faulting accept, and the M3a directed fault test
    // below confirms empirically that the un-exempted form holds.
    // TASK 10 REVIEW FIX I1: compare the FULL ONE-HOT VECTOR, not just its OR-reduction.
    // The original check (`s1Hit === dbgLiveHitQ`) was proven by mutation to be INVISIBLE
    // to a consistent way-permutation of the capture (`validsQ(w) := lookupValids(ways-1-w)`
    // together with the matching `tagQ` permutation): the OR-reduced verdict still says
    // "hit", while the registered path identifies the WRONG WAY. Task 11 turns `s1WayOh`
    // straight into the S1 data/predecode select, so a permuted way is exactly risk R3's
    // silent mis-paired-instruction-bytes failure -- no crash, wrong bytes. The 4
    // additional transitional flops (`dbgLiveHitVecQ`, one per way) are deleted in Task 11
    // together with the rest of the shadow scaffolding, same lifecycle as `dbgLiveHitQ`.
    val dbgS0Fresh      = RegNext(cmdPort.fire) init False
    val dbgLiveHitQ     = RegNextWhen(isHit, cmdPort.fire) init False
    val dbgLiveHitVecQ  = RegNextWhen(hitVec.asBits, cmdPort.fire) init B(0, ways bits)
    val dbgVerdictMatch = Bool()
    dbgVerdictMatch := !dbgS0Fresh || (s1HitVec.asBits === dbgLiveHitVecQ)
    dbgS0Fresh.simPublic(); dbgLiveHitQ.simPublic(); dbgVerdictMatch.simPublic()
    dbgLiveHitVecQ.simPublic()
    // In-RTL, synthesis-inert, and therefore running under EVERY test in the tree --
    // lock-step, the fuzz campaign and the ported corpus included -- not only under the
    // directed shadow suite. Same style as the `missPC` immutability assertion (M2b) and
    // the `refillActive => mshrValid(DEMAND_IDX)` assertion (Task 9 review fix M3).
    assert(dbgVerdictMatch,
      "M3a SHADOW VIOLATED: the S1 per-way hit VECTOR computed from the REGISTERED S0 " +
      "context (tagQ/validsQ/s0Ppn/s0Cacheable) disagrees with the LIVE accept-cycle " +
      "hitVec, delayed one cycle -- either the hit/miss verdict or the SELECTED WAY " +
      "differs. M3 cannot flip until these are identical -- risk R3's failure " +
      "mode is silent instruction-byte mis-pairing, not a crash.")
    // An architectural miss can remain visibly held while a speculative owner or
    // the shared installer drains.  Freeze old-window allocation and let the AR
    // arbiter launch only the same-set owner needed to unblock it.
    val heldDemandMiss = lookupActive && cmdPort.valid && xlate.rsp.ready &&
                         !lookupFault && !isHit

    // DEBUG (Task P5.5 regression test, mirrors the existing simPublic DEBUG hooks
    // above): high for exactly the ONE cycle on which PREDECODE performs the
    // tag/valid ALLOCATION write for a just-completed refill. Purely combinational off
    // registers, so a directed test can read it right after a clock edge and line an
    // `invalidateAll`/`maintInvalidateAll` pulse up with that write — the precise
    // collision the refill-vs-invalidate priority guard below exists to survive. No-op
    // for synthesis (drives nothing).
    val dbgAllocCommitCycle = Bool()
    dbgAllocCommitCycle := False
    dbgAllocCommitCycle.simPublic()
    // DEBUG companion, same purpose: high during the cycle IMMEDIATELY BEFORE
    // `dbgAllocCommitCycle` — REFILL's final AXI beat, the cycle that transitions into
    // PREDECODE's commitBeat==0. A testbench samples signals just before the clock edge
    // that latches them, so a poke issued at that sampling point takes effect for the
    // FOLLOWING cycle; the test therefore has to trigger off this one-cycle-earlier
    // signal to get its invalidate pulse to overlap the allocation write. (It then
    // re-checks `dbgAllocCommitCycle` on the next sampling point to prove the two
    // really did line up, rather than assuming it.) No-op for synthesis.
    val dbgAllocCommitPending = Bool()
    dbgAllocCommitPending := False
    dbgAllocCommitPending.simPublic()

    // ══ Slice I1/I3 control nets — driven by the FSM, consumed by the hoisted
    //    fill datapath below it (see that block for why the datapath is hoisted). ══
    val refillActive      = Bool(); refillActive      := False
    val refillDone        = Bool()   // driven by the datapath, read by the FSM
    val refillErr         = Bool()
    val predActive        = Bool(); predActive        := False
    // M2b: `PF_PRED` is merged into `PREDECODE`, so "which kind of install is this
    // dwell?" can no longer be a state identity -- it becomes a REGISTER, written by
    // whichever arm entered INSTALL_ARM and stable for the whole dwell. `predIsPf` is
    // kept as a wire aliasing it so the existing `simPublic` and every testbench that
    // reads it (IcachePrefetchSpec.scala's INSTALL0/INSTALL1 invalidate-race stages)
    // are completely unaffected.
    //
    // It is written on BOTH arms into INSTALL_ARM (True from IDLE's speculative
    // install, False from REFILL's clean completion) and read ONLY under `predActive`,
    // i.e. only in PREDECODE -- which is reachable only through INSTALL_ARM. Its value
    // BETWEEN dwells is therefore don't-care, and the one testbench that samples the
    // alias already qualifies it with `predActive`.
    val predIsPfReg       = RegInit(False)
    val predIsPf          = Bool(); predIsPf          := predIsPfReg
    predActive.simPublic(); predIsPf.simPublic(); commitBeat.simPublic()
    val fillArrayWrActive = Bool(); fillArrayWrActive := False
    // Slice I3 telemetry: pure wires (zero flops, zero synthesis cost) that a
    // testbench counts to derive design doc §8.3's "prefetch issued / useful /
    // wasted". Deliberately NOT synthesised counters -- the core must not carry
    // measurement-only registers.
    val pfHitUseful     = Bool(); pfHitUseful     := False; pfHitUseful.simPublic()
    val demandFillStart = Bool(); demandFillStart := False
    // A lookup that RESETS/KILLS the frontier owns the allocator cycle. Same-line
    // and same-page sequential hits do not: a bubble-free hit stream must still let
    // freed speculative IDs advance the fifth/farther candidate.
    val pfWindowUpdate  = Bool(); pfWindowUpdate  := False

    // ── Five-line registered stream window (binding design 2026-08-10) ────────
    // The frontier and candidate are registers, preserving the earlier FMax lesson:
    // never put live translation + increment + async tag lookup + allocation enables
    // in one cone. Candidates use the already-resolved same-page PPN, never an ITLB
    // request, and stop at the 4-KiB boundary (rules P1/P4).
    val pfCandSet = pfNextPa(11 downto 6)
    val pfCandTag = pfNextPa(31 downto 12)
    val pfCandResident = Vec((0 until ways).map(w =>
      valids(w)(pfCandSet) && (tagMem(w).readAsync(pfCandSet) === pfCandTag))).orR
    // SPECULATIVE-ONLY reductions (index `i + I_SPEC_BASE`). Entry 0 is deliberately
    // NOT included in `pfCandLive`: a candidate that matches the in-flight DEMAND line
    // must WAIT (the `pfCandSetBusy` arm below), not advance the frontier past it --
    // including it here would turn a wait into a skip and change allocator behaviour.
    val pfCandLive = Vec((0 until pfSlots).map(i =>
      mshrValid(i + AxiIds.I_SPEC_BASE) &&
        ((mshrPa(i + AxiIds.I_SPEC_BASE) & ~U(63, 32 bits)) === pfNextPa))).orR
    // ...whereas "does ANY live fill already own this set?" IS uniform over the whole
    // file. This subsumes the hand-written `demandSetOwned` state list (see its
    // declaration below the FSM, which survives as the named i == DEMAND_IDX lane).
    val pfCandSetBusy = Vec((0 until MSHR_N).map(i =>
      mshrValid(i) && (mshrSet(i) === pfCandSet))).orR
    // The prefetcher may only allocate SPECULATIVE entries; entry 0 is the FSM's.
    val pfFreeVec = Vec((0 until pfSlots).map(i => !mshrValid(i + AxiIds.I_SPEC_BASE)))
    val pfHasFree = pfFreeVec.orR
    val pfFreeIdx = OHToUInt(OHMasking.first(pfFreeVec.asBits))
    val pfFreeMshr = (pfFreeIdx.resize(mshrIdxBits) +
                      U(AxiIds.I_SPEC_BASE, mshrIdxBits bits)).resize(mshrIdxBits)
    val pfWindowHasCandidate = pfSeqValid && prefetchEnable &&
      (pfNextPa <= pfLimitPa) && (pfNextPa(31 downto 12) === pfDemandLine(31 downto 12))

    // A held demand matching any live speculative line must re-look-up after install;
    // a same-set/different-line demand waits as well, preserving one fill owner per set.
    val lookupLineBase = lookupPaddr & ~U(63, 32 bits)
    // Speculative-only, and it must stay that way: its ONLY consumer is `answerable`
    // inside `lookupTick(canStartFill = true)`, which is reached only from IDLE, and in
    // IDLE `mshrValid(DEMAND_IDX)` is False by construction -- so widening it to the
    // whole file would be a literal no-op, and a misleading one.
    val pfLookupSetBusy = Vec((0 until pfSlots).map(i =>
      mshrValid(i + AxiIds.I_SPEC_BASE) &&
        (mshrSet(i + AxiIds.I_SPEC_BASE) === lookupSet))).orR

    // Speculative-only: entry 0's install is driven by the FSM's own REFILL->INSTALL_ARM
    // transition, not by this arbiter. (Task 9 review fix I1: `mshrComplete(DEMAND_IDX)`
    // IS now set, same as every other entry -- see the R-channel handler -- but this
    // arbiter still deliberately excludes entry 0, because its install path is the FSM
    // transition above, not a background installer pick-up.)
    val pfInstallVec = Vec((0 until pfSlots).map(i =>
      mshrValid(i + AxiIds.I_SPEC_BASE) && mshrComplete(i + AxiIds.I_SPEC_BASE) &&
        !mshrErr(i + AxiIds.I_SPEC_BASE) && !mshrPoison(i + AxiIds.I_SPEC_BASE)))
    val pfInstallAny = pfInstallVec.orR
    val pfInstallSel = OHToUInt(OHMasking.first(pfInstallVec.asBits))
    val pfInstallMshr = (pfInstallSel.resize(mshrIdxBits) +
                         U(AxiIds.I_SPEC_BASE, mshrIdxBits bits)).resize(mshrIdxBits)

    def seedPfWindow(): Unit = {
      val line = lookupPaddr & ~U(63, 32 bits)
      val pageEnd = (lookupPaddr(31 downto 12) ## U(0xfff, 12 bits)).asUInt & ~U(63, 32 bits)
      val wantedLimit = line + U(5 * 64, 32 bits)
      val clampedLimit = Mux(wantedLimit(31 downto 12) === line(31 downto 12),
                             wantedLimit, pageEnd)
      when(!pfSeqValid || (line =/= pfDemandLine)) {
        // A virtual page crossing must always restart from line+64, even when the
        // two physical pages happen to be contiguous.  The old page's clamp can
        // otherwise leave pfNextPa pointing at the new demand line itself.
        val sequential = pfSeqValid &&
                         (line(31 downto 12) === pfDemandLine(31 downto 12)) &&
                         (line === (pfDemandLine + U(64, 32 bits)))
        when(!sequential) { pfNextPa := line + U(64, 32 bits) }
        pfDemandLine := line
        pfLimitPa    := clampedLimit
      }
      pfSeqValid := True
    }

    // Stable registered AR holding point. It intentionally allows one arbitration
    // bubble after each fire; five requests still launch far inside the 70-cycle
    // memory window, while payload stability is structural under arbitrary ready.
    val arHoldValid = RegInit(False)
    val arHoldId    = Reg(UInt(AxiIds.ID_W bits))
    val arHoldAddr  = Reg(UInt(32 bits))

    // ---- FSM ----
    val fsm = new StateMachine {
      val IDLE      = new State with EntryPoint
      val REFILL    = new State
      // M2b: the arm cycle. installIdx/installSet are registered here so the MSHR line
      // file read that feeds the dwell is SYNCHRONOUS. Costs the demand refill +1
      // cycle on a ~78-cycle DDR-bound event (1.3%) and replaces the speculative
      // install path's IDLE 512-bit LUTRAM->register copy with a one-cycle synchronous
      // file read. Spec section 5.6. Deliberately does NOTHING else -- giving it another
      // job would put logic back on the path M2 exists to clear.
      val INSTALL_ARM = new State
      // M2b: ONE install/classify dwell for BOTH demand and speculative fills; the old
      // separate `PF_PRED` state is deleted and its two extra behaviours (hold the
      // lookup port open with canStartFill = false, and free the speculative slot on
      // completion) are parameterised by the registered `predIsPfReg`.
      val PREDECODE = new State
      val REPLAY    = new State
      // Task #211: a REFILL whose AXI read response(s) came back non-OKAY
      // (SLVERR/DECERR — genuinely unmapped or erroring physical memory). No line
      // is allocated (PREDECODE, which does the tag/pred/valid writes, is skipped
      // entirely); this delivers a one-shot fault response instead, mirroring
      // DcachePlugin's REPLAY/busFaultResp handling (task #189).
      val FAULT     = new State

      // ----- IDLE / PF lookup: parallel virtual-set BRAM + live ITLB/tag -----
      // A resolved command is classified and accepted in one cycle. Virtual set/beat
      // arms the BRAM independently; the live physical tag/fault/cache mode terminates
      // only at the S1 context. A cold ITLB request or a prefetch-time cache miss is
      // held at the Stream boundary and retried without an internal T-stage.
      //
      // ── SLICE I1: WHY THE FETCH PORT IS OPEN DURING A *PREFETCH* FILL AND NOT
      //    DURING A *DEMAND* FILL. Read this before changing `canStartFill`. ──
      //
      // The ratified plan's I1 asks for a general non-blocking accept ("letting the
      // cache keep accepting, and answering hits, during a refill"). That is NOT
      // safely implementable here, and the reason is an ORDERING contract, not an
      // effort budget: `FetchRsp` carries no tag, and `FetchAlignPlugin` attributes
      // every response to its outstanding ring's HEAD (FetchAlignPlugin.scala:263,283)
      // -- design doc §6.3 keeps it that way ON PURPOSE. So responses must leave this
      // cache in the order the fetches were accepted. If a younger fetch that HITS
      // were answered while an older fetch's DEMAND fill was still in flight, the hit's
      // data would be attributed to the older ring entry and the fill's data to the
      // younger one: silently mis-paired instruction bytes. Serving demand hits under a
      // demand miss therefore requires response tagging plus a ring rework, which §6.3
      // explicitly declines.
      //
      // A PREFETCH fill has no such problem, because it produces NO response at all.
      // While one is in flight the only responses in flight are demand responses, and
      // demand fetches are still issued and answered strictly in order. So:
      //
      //   - during a DEMAND fill  -> the fetch port stays closed (exactly as before),
      //   - during a PREFETCH fill -> the fetch port stays OPEN and hits are served.
      //
      // The second bullet is what makes slice I3 pay: a prefetch takes as long as a
      // miss (~78 cycles under DDR), and if it blocked the fetch port for that long it
      // would cost more than it saved.
      //
      // The plan's I1.2 "same-line duplicate-miss suppression" and I3.2 "demotion"
      // (a demand fetch attaching to an in-flight prefetch) are both delivered by the
      // SAME cheap mechanism the plan itself sanctions: **backpressure the offered
      // Stream command and re-look-up.** A demand miss during a prefetch fill is not
      // accepted or allocated; it remains stable and, once the fill lands, HITS.
      // Observable requirement met: N same-line fetches produce exactly ONE AR. It also
      // satisfies rules M1/M2/P2/P3 with no extra logic -- an errored fill allocates
      // nothing, so the held fetch re-looks-up, misses, and issues its OWN real
      // transaction, getting its own contemporaneous response and faulting precisely
      // via the existing task-#211 FAULT path. No error verdict is ever cached, in
      // either direction. M3 is satisfied structurally: an INHIBITED page is never
      // allocated and never prefetched (rule P1), so there is nothing to merge across
      // cacheability classes.
      //
      // `canStartFill` = false also carries the **D3-SET-I lookup-vs-fill-write rule**:
      // a lookup that indexes the SET the in-flight fill is committing into must not be
      // answered, because `tagMem` is a write-first async-read LUTRAM while `valids` is
      // a register array -- on the commit cycle a same-set lookup would read the NEW
      // tag against the OLD valid bit and could report a spurious HIT on a line whose
      // data beat is being written that same cycle. Held instead, and retried.
      def lookupTick(canStartFill: Boolean): Unit = {
        lookupActive := True

        // Arm the BRAM solely from virtual page-offset bits, in parallel with the ITLB
        // and tag lookup. Never gate this enable with hitVec/isHit: doing so would put
        // translation and tag comparison back on the BRAM ENARDEN path. Reads for a
        // miss/fault/held command are harmless because no S1 context consumes them.
        // M2c (SG-3 exception 1): reads `installSet`, the dedicated dwell register, not
        // `mshrSet(installIdx)` -- this comparator is in the ACCEPT cone and must not
        // grow a 5:1 Vec mux in front of it. `installSet` was maintained in lock-step
        // with the now-deleted `missSet` since Task 8, so this switch is a no-op.
        val setBlocked = if (canStartFill) False
                         else (fillArrayWrActive && (lookupSet === installSet))
        ufaReadAddr := lookupReadAddr
        ufaReadEn   := cmdPort.valid && !setBlocked

        // IDLE may accept any resolved command, including the demand miss it captures.
        // During a prefetch fill, only an answerable hit/fault may fire; a miss remains
        // held at the Stream boundary until the shared fill engine becomes free.
        val answerable = if (canStartFill)
          (lookupFault || isHit || !pfLookupSetBusy)
        else
          (lookupFault || isHit)
        cmdPort.ready := xlate.rsp.ready && !setBlocked && answerable

        when(cmdPort.fire) {
          // ══ M3a S0 CAPTURE ═════════════════════════════════════════════════════
          // UNCONDITIONAL on every accepted command -- one shallow enable, no arm.
          // The page-invariant virtual address bits, plus the ONE cycle the
          // translation is allowed to be live. Placing any of this inside the
          // fault/hit/miss chain below would be a real bug: the MISS arm is precisely
          // the case Task 11's `s1Unresolved` needs a valid registered context for.
          //
          // BEHAVIOUR NEUTRALITY of the two writes that were previously arm-local
          // (`s0Pc`, `s0Lane`, which the fault and hit arms each set to these exact
          // values and the miss arm did not set at all): on the miss arm both are now
          // written where they previously held their prior value. Nothing observes
          // that. `s0Valid` is False on the miss cycle, so the S1->rsp path's use of
          // them is gated off (`rspValidReg := s0Valid`), REFILL reads neither, and
          // REPLAY overwrites both from `missPC` before it re-arms `s0Valid`.
          s0Pc        := lookupPc
          s0Set       := lookupSet
          s0Beat      := lookupBeatSel
          s0Lane      := lookupLaneIdx
          s0Ppn       := lookupTag
          s0Cacheable := lookupCacheable
          // DEVIATION from the plan's Step 4 list, which leaves `s0Fault` arm-local:
          // it is captured here instead, because the plan's OWN "Produces" interface
          // calls it part of "the registered translation verdict" and because
          // `s1Unresolved` reads it. EXACTLY EQUIVALENT: the fault arm below is
          // `when(lookupFault)` and set it True, the hit arm is reached only when
          // `!lookupFault` and set it False -- i.e. both set precisely `lookupFault`.
          // The miss arm left it STALE; it is now False there, which nothing observes
          // (`s0Valid` is False on a miss cycle, so `rspFaultReg`'s copy is gated off,
          // REFILL reads it not at all, and REPLAY/FAULT rewrite it before re-arming
          // `s0Valid`). `s0Atc` is deliberately NOT hoisted: it is a response-payload
          // attribute distinguishing an ATC fault from a bus-error fault, not part of
          // the translation verdict, and FAULT sets it to the opposite value.
          s0Fault     := lookupFault
          for (w <- 0 until ways) {
            tagQ(w)    := lookupTags(w)
            validsQ(w) := lookupValids(w)
          }
          when(!lookupFault && lookupCacheable) {
            val line = lookupPaddr & ~U(63, 32 bits)
            val sequential = pfSeqValid &&
                             (line(31 downto 12) === pfDemandLine(31 downto 12)) &&
                             (line === (pfDemandLine + U(64, 32 bits)))
            pfWindowUpdate := !pfSeqValid ||
                              ((line =/= pfDemandLine) && !sequential)
            seedPfWindow()
          } otherwise {
            pfWindowUpdate := True
            pfSeqValid := False
          }
          // M3a: `s0Pc`/`s0Lane`/`s0Fault` are NOT restated in these arms -- the
          // unconditional capture above already wrote them with these exact values, and
          // a restatement would silently shadow any future change to it.
          when(lookupFault) {
            // Translation fault: emit a fault response (no data, no refill).
            s0Valid := True
            s1Way   := U(0, wayBits bits)
            s0Atc   := True
            // M1b: no S1 predecode capture bank to zero -- the S1->rsp path masks
            // predecode to all-zero directly off `s0Fault` (see `s1PredW` above).
            s0FromMiss := False
          } elsewhen(isHit) {
            s0Valid := True
            s1Way   := hitWayIdx
            s0Atc   := False
            s0FromMiss := False

            // The first demand hit in a prefetched line keeps the stream moving.
            when(pfFilled(hitWayIdx)(lookupSet)) {
              pfFilled(hitWayIdx)(lookupSet) := False
              pfHitUseful := True
            }
          } otherwise {
            // Reachable only in IDLE because answerable excludes a prefetch-time miss.
            // M2c: this is now an ordinary MSHR ALLOCATION -- the same ten fields the
            // speculative allocator writes, at index 0. `mshrValid(DEMAND_IDX) := True`
            // is what makes entry 0 own `lookupSet` for the whole fill, replacing the
            // hand-written `demandSetOwned` state list.
            mshrValid(DEMAND_IDX)    := True
            mshrArSent(DEMAND_IDX)   := False
            mshrComplete(DEMAND_IDX) := False
            mshrBeat(DEMAND_IDX)     := False
            mshrErr(DEMAND_IDX)      := False
            mshrPoison(DEMAND_IDX)   := False
            mshrPa(DEMAND_IDX)       := lookupPaddr
            mshrSet(DEMAND_IDX)      := lookupSet
            mshrTag(DEMAND_IDX)      := lookupTag
            mshrWay(DEMAND_IDX)      := victim(lookupSet)
            missPC        := lookupPc
            missCacheable := lookupCacheable
            demandFillStart := True
            goto(REFILL)
          }
        }
      }

      IDLE.whenIsActive {
        lookupTick(canStartFill = true)
        // Demand allocation wins. Otherwise a completed silent line is registered
        // into the shared installer.
        //
        // M2b: no 512-bit LUTRAM->register copy. Just name the entry and arm the file
        // read; PREDECODE reads it out synchronously next-next cycle.
        // M2c: this arm now loads the INSTALL CONTEXT only. It no longer copies the
        // installing slot's pa/tag/poison into the demand scalars (`missPA`/`missTag`/
        // `missPoison`) the way it had to when those were the shared staging registers:
        //   - `missPA` was pure dead code here (its only reader is `arHoldAddr`, under
        //     `refillActive`, i.e. only in REFILL) and is DELETED with the write;
        //   - the tag and the poison bit are read straight out of the MSHR entry being
        //     installed (`mshrTag(installIdx)` / `mshrPoison(installIdx)`) in the dwell,
        //     so entry 0 is never clobbered and `mshrValid(0)`/`mshrSet(0)` stay
        //     truthful -- which is exactly what lets oracle 3 trust entry 0.
        when(pfInstallAny && !demandFillStart) {
          installIdx    := pfInstallMshr
          installSet    := mshrSet(pfInstallMshr)
          installWay    := mshrWay(pfInstallMshr)
          missPC        := mshrPa(pfInstallMshr)
          missCacheable := True
          commitBeat    := U(0, 1 bits)
          predIsPfReg   := True
          goto(INSTALL_ARM)
        }
      }
      // ----- Demand refill; speculative R traffic is handled independently by RID -----
      REFILL.whenIsActive {
        refillActive := True
        // Task 9 review fix M3 (reviewer-flagged "free" hardening, directly aimed at
        // preventing a repeat of the M2b `INSTALL_ARM`/`demandSetOwned` bug class):
        // `refillActive` and `mshrValid(DEMAND_IDX)` are two liveness notions for the
        // SAME thing -- the demand MSHR entry owning `mshrSet(DEMAND_IDX)` during its
        // own fill -- maintained by construction rather than by a single shared flag
        // (see the ENTRY 0's SEMANTICS note above `mshrValid`'s declaration for why
        // `refillActive` is deliberately not read off `mshrValid(DEMAND_IDX)` on the
        // R-channel match). `mshrValid(DEMAND_IDX)` is set on the IDLE miss-detect that
        // starts a refill and only cleared in REPLAY/FAULT, both reached strictly AFTER
        // REFILL, so this invariant should hold on every cycle of REFILL by
        // construction; a future edit (M3's restructuring is the very next task) that
        // drifts the two apart is exactly the class of bug the M2b fix already had to
        // catch once. Simulation-only, no synthesised logic (same style as the
        // `missPC` immutability assert above and the two-beat refill asserts below).
        assert(mshrValid(DEMAND_IDX),
          "I-cache REFILL active but mshrValid(DEMAND_IDX) is False -- the demand MSHR " +
          "entry's liveness has drifted out of sync with the FSM's own refillActive " +
          "state")
        when(refillDone) {
          when(refillErr) { goto(FAULT) } otherwise {
            installIdx  := U(AxiIds.I_DEMAND, mshrIdxBits bits)
            installSet  := mshrSet(DEMAND_IDX)
            installWay  := mshrWay(DEMAND_IDX)
            predIsPfReg := False
            goto(INSTALL_ARM)
          }
        }
      }

      // M2b: one cycle. installIdx/installSet were registered by whoever entered here;
      // fillLoQ/fillHiQ present the line on the FIRST PREDECODE cycle. Nothing else
      // happens -- deliberately: this state exists to make the file read synchronous,
      // and giving it any other job would put logic back on the path M2 is clearing.
      //
      // The fetch port is CLOSED here (no `lookupTick`), which is the conservative
      // choice in both directions: for a demand install it is mandatory (the demand's
      // own REPLAY response is still owed, and FetchRsp carries no tag), and for a
      // speculative install it costs one held cycle that the retry loop absorbs.
      INSTALL_ARM.whenIsActive { goto(PREDECODE) }

      // ----- FAULT: deliver a one-shot bus-error fault response; no allocation -----
      // Task #211: reached only via REFILL's non-OKAY AXI response. PREDECODE (the
      // tag/valid/lineMem writes) is skipped entirely — no line is allocated,
      // matching the D-side no-allocate-on-error policy — and the victim pointer is
      // NOT advanced (this way was never actually filled). A PREFETCH burst error never
      // comes here (rule P2, above): it has no waiting fetch to fault.
      FAULT.whenIsActive {
        s0Valid := True
        s1Way   := U(0, wayBits bits)
        s0Pc    := missPC
        s0Fault := True
        s0Atc   := False   // physical bus error, not ATC/MMU-detected
        s0Lane  := missPC(4 downto 3)
        // M1b: predecode is masked to all-zero off `s0Fault` on the S1->rsp path.
        s0FromMiss := False
        // M2c: the demand MSHR's ownership of its set ends here. Exactly the cycle the
        // old hand-written `demandSetOwned` state list stopped including FAULT.
        mshrValid(DEMAND_IDX) := False
        goto(IDLE)
      }

      // ----- PREDECODE: classify the line, write lineMem + tag/valid -----
      // Body hoisted to the shared `predActive` datapath below the FSM (single
      // `lineMem(w).write(...)` / `tagMem(w).write(...)` call
      // site each -- see the icache-burst-fault-fix comment there for why a SECOND call
      // site is not merely untidy but breaks SpinalHDL's MultiPortWritesSymplifier).
      PREDECODE.whenIsActive {
        predActive := True
        // M2b: PF_PRED merged in. A SPECULATIVE install produces no response, so the
        // fetch port may stay open and serve hits (see the slice-I1 ordering comment on
        // `lookupTick` above). A DEMAND install must keep it closed -- its own REPLAY
        // response is still owed and FetchRsp carries no tag, so a younger hit answered
        // here would be attributed to the older ring entry (silent instruction-byte
        // mis-pairing).
        when(predIsPfReg) {
          // Slice I1 / D3-SET-I: the array-write cycle is the one cycle a concurrent
          // lookup into the SAME set must not be answered (write-first async tagMem vs
          // a register `valids` array -> a same-set lookup could read the NEW tag
          // against the OLD valid bit and report a spurious hit on a half-written
          // line). Held and retried instead.
          fillArrayWrActive := True
          lookupTick(canStartFill = false)
        }
        when(commitBeat === U(1, 1 bits)) {
          when(predIsPfReg) {
            // M2c: freed by MSHR index. `pfInstallIdx` (a second, slot-relative copy of
            // the same number `installIdx` already holds) is deleted. Guarded by
            // `predIsPfReg`, so this decoder can never reach entry 0.
            mshrValid(installIdx)    := False
            mshrArSent(installIdx)   := False
            mshrComplete(installIdx) := False
            goto(IDLE)
          } otherwise {
            goto(REPLAY)
          }
        }
      }

      // ----- REPLAY: arm S1 read for the just-filled line, then IDLE -----
      REPLAY.whenIsActive {
        // M2c: `installSet`/`installWay` rather than `mshrSet(0)`/`mshrWay(0)`. REPLAY is
        // demand-only (it is reached only from a PREDECODE dwell with predIsPfReg false,
        // where INSTALL_ARM loaded both registers from entry 0), so the two are equal
        // here by construction -- and reading back the line through the SAME registers
        // that addressed the write is the property this state actually depends on.
        val replayBeatSel  = missPC(5)
        val replayReadAddr = (installSet ## replayBeatSel).asUInt

        ufaReadAddr := replayReadAddr
        ufaReadEn   := True
        s0Valid := True
        s1Way   := installWay
        s0Pc    := missPC
        // A refill only happens for a NON-faulting translation (the live fault
        // path emits a placeholder without refilling), so the replayed line is fault-
        // free by construction — off the live ITLB rsp entirely. A bus-erroring
        // refill never reaches REPLAY (it routes to FAULT instead, task #211).
        s0Fault := False
        s0Atc   := False
        s0Lane  := missPC(4 downto 3)
        // Task icache-corruption-fix: `ufaBeat` (armed via
        // ufaReadAddr/ufaReadEn above) only holds meaningful content when the line
        // was actually allocated (missCacheable) — for a non-allocated (INHIBITED)
        // miss the array was not written THIS refill and may still hold stale,
        // unrelated content left by a prior allocation to this same way/set.
        // `s0FromMiss` routes the S1->rsp mux to the `bypWindow`/`bypPred` bypass
        // registers instead for that case, so latching this array read regardless
        // is harmless (simply unused).
        //
        // Slice I1: poison (an invalidateAll that landed mid-fill) suppresses the
        // allocation too, so the same bypass must carry the response for that case --
        // the arrays were deliberately NOT written, and the in-flight fetch must still
        // be answered or FetchAlign's ring never retires its entry (see the poison
        // note above `mshrPoison`'s declaration for the full front-end-wedge analysis).
        s0FromMiss := !missCacheable || mshrPoison(DEMAND_IDX)
        // M2c: the demand MSHR's ownership of its set ends here, one cycle before the
        // machine is back in IDLE -- exactly where the old state list stopped.
        mshrValid(DEMAND_IDX) := False

        goto(IDLE)
      }
    }

    // ══ M2b: MISS-PC IMMUTABILITY, ENFORCED RATHER THAN ARGUED ═══════════════════
    // `bypPred` (M2a) and `bypWindow` (M2b) are captured on ONE dwell cycle, selected
    // by `missPC(5)` / `missPC(4:3)`, and consumed later by REPLAY, which re-reads
    // `missPC` for `s0Pc`/`s0Lane`. The whole narrowing is only correct because
    // `missPC` cannot change between those two points. That has been true twice over
    // (M2a's review verified it; M2b's PF_PRED->PREDECODE merge preserved it -- see the
    // full writers-enumeration argument at `bypPred`'s declaration), and BOTH times it
    // was an undocumented, untested property that a future edit could break silently:
    // nothing would fail loudly, the response would just carry a window from the wrong
    // part of the line.
    //
    // So it stops being an argument. `missPC` may only change on a cycle whose FSM
    // state is IDLE (the two writers -- `lookupTick`'s miss arm and IDLE's speculative
    // install arm -- are both IDLE-confined). Equivalently: on any cycle where the fill
    // machine was already engaged on the PREVIOUS cycle too, `missPC` must equal its
    // own previous value. Requiring the previous cycle to be engaged as well is what
    // excludes the legitimate arming write, which lands on the IDLE->dwell edge; a
    // write anywhere INSIDE the dwell still shows up one cycle later and trips this.
    //
    // This is a simulation assertion in the same style as the two-beat refill asserts
    // below; it costs nothing in synthesis and it runs under EVERY test in the suite,
    // including lock-step and the fuzz campaign.
    val dwellActive = fsm.isActive(fsm.REFILL) || fsm.isActive(fsm.INSTALL_ARM) ||
                      fsm.isActive(fsm.PREDECODE) || fsm.isActive(fsm.REPLAY) ||
                      fsm.isActive(fsm.FAULT)
    val prevDwell   = RegNext(dwellActive) init False
    val missPCPrev  = RegNext(missPC)
    when(prevDwell && dwellActive) {
      assert(missPC === missPCPrev,
        "missPC changed during a fill dwell -- the bypPred/bypWindow capture window " +
        "(missPC(5), missPC(4:3)) is no longer the window REPLAY will deliver")
    }
    // DEBUG (mirrors the existing dbgAllocCommit* hooks): high on exactly the cycles
    // the assertion above is ACTIVE, so a directed test can prove its coverage window
    // is non-empty instead of passing vacuously. No-op for synthesis (drives nothing).
    val dbgMissPcLocked = Bool()
    dbgMissPcLocked := prevDwell && dwellActive
    dbgMissPcLocked.simPublic()
    missPC.simPublic()

    // ══ Five-ID fill pool + single shared installer ═══════════════════════════════
    refillDone := False
    refillErr  := False

    // Allocate at most one registered same-page candidate per cycle. Resident/live
    // candidates advance the frontier without consuming a slot; same-set conflicts
    // wait, enforcing one fill owner per set.
    //
    // ── M2c: "one fill owner per set" is now ONE uniform reduction ─────────────────
    // `pfCandSetBusy` (declared above the FSM) reduces `mshrValid(i) && mshrSet(i) ===
    // pfCandSet` over ALL MSHR_N entries, entry 0 included. `demandSetOwned` survives
    // only as the NAMED i == DEMAND_IDX lane of that same reduction -- it is what the
    // ledger, the M2b review and oracle 3's failure message all refer to by name, and
    // keeping it visible is what makes the mutation proof legible. It is redundant with
    // `pfCandSetBusy` by construction; the `||` below folds away.
    //
    // WHAT REPLACED WHAT, because this is a behavioural claim and not a cosmetic one.
    // Before M2c: `demandSetOwned` was a hand-written five-state list AND-ed with
    // `missSet === pfCandSet`, where `missSet` was a SHARED staging register that a
    // speculative install overwrote with the installing slot's set. So on a speculative
    // dwell the term guarded the SPECULATIVE set. After M2c: `mshrValid(DEMAND_IDX)` is
    // True on exactly the five states that list enumerated MINUS the speculative-install
    // dwell (INSTALL_ARM/PREDECODE with predIsPfReg), and `mshrSet(DEMAND_IDX)` is the
    // demand set and nothing else. The dropped coverage is EXACTLY the speculative
    // dwell, which M2b's own note already established is fully covered by the
    // installing entry's own `mshrValid`/`mshrSet` lane: it is cleared on the
    // commitBeat==1 cycle, taking effect only as the machine leaves, so every cycle of
    // the dwell still has that entry live and owning that set. Net allocator behaviour
    // is unchanged, and the M2b INSTALL_ARM hole stays closed -- structurally now,
    // rather than by remembering to name a state.
    val demandSetOwned = mshrValid(DEMAND_IDX) && (mshrSet(DEMAND_IDX) === pfCandSet)
    when(pfWindowHasCandidate && !anyInvalidate && !demandFillStart &&
         !pfWindowUpdate && !heldDemandMiss) {
      when(pfCandLive) {
        pfNextPa := pfNextPa + U(64, 32 bits)
      } elsewhen(pfCandSetBusy || demandSetOwned) {
        // Wait. A resident candidate may be the victim that the current same-set
        // owner is about to evict, so resident suppression is only legal once the
        // set becomes owner-free.
      } elsewhen(pfCandResident) {
        pfNextPa := pfNextPa + U(64, 32 bits)
      } elsewhen(pfHasFree) {
        // The same ten fields the demand miss-detect arm writes at index 0.
        mshrValid(pfFreeMshr)    := True
        mshrArSent(pfFreeMshr)   := False
        mshrComplete(pfFreeMshr) := False
        mshrBeat(pfFreeMshr)     := False
        mshrErr(pfFreeMshr)      := False
        mshrPoison(pfFreeMshr)   := False
        mshrPa(pfFreeMshr)       := pfNextPa
        mshrSet(pfFreeMshr)      := pfCandSet
        mshrTag(pfFreeMshr)      := pfCandTag
        mshrWay(pfFreeMshr)      := victim(pfCandSet)
        pfNextPa                 := pfNextPa + U(64, 32 bits)
      }
    }

    // Completed errors and poisoned silent fills allocate nothing and free locally.
    // SPECULATIVE ONLY, and this restriction is now load-bearing rather than merely
    // inert (Task 9 review fix I1 made `mshrComplete(DEMAND_IDX)` a real, driven bit):
    // entry 0's error/poison handling is owned entirely by the FSM (`refillErr` ->
    // FAULT, or `mshrPoison(DEMAND_IDX)` read by REPLAY's `s0FromMiss`), and its "free"
    // is the FSM's own return to IDLE (REPLAY/FAULT). Widening this loop to `0 until
    // MSHR_N` would race that FSM-owned teardown -- clearing `mshrValid(DEMAND_IDX)` a
    // cycle out of step with the state machine that is supposed to own it -- so it must
    // stay pfSlots-only even though entry 0's `mshrComplete` is no longer always False.
    for (i <- 0 until pfSlots) {
      val e = i + AxiIds.I_SPEC_BASE
      when(mshrValid(e) && mshrComplete(e) && (mshrErr(e) || mshrPoison(e))) {
        mshrValid(e)    := False
        mshrArSent(e)   := False
        mshrComplete(e) := False
      }
    }
    // ...whereas "an invalidate poisons every live fill" IS uniform over the whole file.
    // M2c: this ONE loop replaces the old speculative-only
    // `when(anyInvalidate && pfValid(i)) pfPoison(i) := True` PLUS the separate
    // `when(anyInvalidate && !fsm.isActive(IDLE)) { missPoison := True }` line that used
    // to sit at the very bottom of this file. EQUIVALENCE for entry 0: `mshrValid(0)`
    // is True on exactly REFILL/INSTALL_ARM(demand)/PREDECODE(demand)/REPLAY/FAULT,
    // i.e. every non-IDLE state in which a demand fill exists; the only non-IDLE cycles
    // it is False on are a SPECULATIVE dwell, where the old line poisoned the shared
    // `missPoison` staging register -- and that is now covered by the installing entry's
    // own lane, which is live for the whole dwell. Position matters: this loop
    // elaborates AFTER the allocator above, so on a cycle that both allocates and
    // invalidates the poison would win -- except the allocator is itself gated on
    // `!anyInvalidate`, so the two can never fire together. It elaborates AFTER the FSM
    // too, so it likewise wins over the miss-detect arm's `mshrPoison(0) := False` --
    // and there it is a no-op, because `mshrValid(0)` is still False on the cycle the
    // demand fill is being allocated (exactly the `!IDLE` gate the old line carried).
    for (i <- 0 until MSHR_N) {
      when(anyInvalidate && mshrValid(i)) { mshrPoison(i) := True }
    }
    when(anyInvalidate) { pfSeqValid := False }

    // Registered AR holding point: demand always wins an empty arbiter, then the
    // lowest speculative slot. Payload cannot change under backpressure.
    // Speculative-only: entry 0's AR is launched by the `refillActive` arm below.
    val pfArWant = Vec((0 until pfSlots).map { i =>
      val e = i + AxiIds.I_SPEC_BASE
      mshrValid(e) && !mshrArSent(e) && !mshrComplete(e)
    })
    val pfAnyArWant = pfArWant.orR
    val pfArSel = OHToUInt(OHMasking.first(pfArWant.asBits))
    // Do not create a new speculative AR hold in front of an architectural miss
    // already visible at the command boundary. An AR that was presented earlier
    // remains stable until fire, as AXI requires; this guard handles the empty-holder
    // arbitration case and the matching-fill error/retry boundary.
    val pfBlockingArWant = Vec((0 until pfSlots).map(i =>
      pfArWant(i) && (mshrSet(i + AxiIds.I_SPEC_BASE) === lookupSet)))
    val pfBlockingArAny = pfBlockingArWant.orR
    val pfBlockingArSel = OHToUInt(OHMasking.first(pfBlockingArWant.asBits))
    val pfChosenArSel = Mux(heldDemandMiss, pfBlockingArSel, pfArSel)
    val pfChosenArMshr = (pfChosenArSel.resize(mshrIdxBits) +
                          U(AxiIds.I_SPEC_BASE, mshrIdxBits bits)).resize(mshrIdxBits)
    when(!arHoldValid) {
      when(refillActive && !mshrArSent(DEMAND_IDX)) {
        arHoldValid := True
        arHoldId    := U(AxiIds.I_DEMAND, AxiIds.ID_W bits)
        arHoldAddr  := mshrPa(DEMAND_IDX) & ~U(63, 32 bits)
      } elsewhen(pfAnyArWant && !demandFillStart &&
                 (!heldDemandMiss || pfBlockingArAny)) {
        arHoldValid := True
        arHoldId    := (pfChosenArSel.resize(AxiIds.ID_W) +
                        U(AxiIds.I_SPEC_BASE, AxiIds.ID_W bits)).resized
        arHoldAddr  := mshrPa(pfChosenArMshr) & ~U(63, 32 bits)
      }
    }

    axi.ar.valid         := arHoldValid
    axi.ar.payload.addr  := arHoldAddr
    axi.ar.payload.id    := arHoldId
    axi.ar.payload.len   := U(1, 8 bits)
    axi.ar.payload.size  := U(5, 3 bits)
    axi.ar.payload.burst := Axi4.burst.INCR
    // M2c: the AR id IS the MSHR index, so the demand/speculative branch is gone.
    when(axi.ar.fire) {
      arHoldValid := False
      mshrArSent(arHoldId.resize(mshrIdxBits)) := True
    }

    // Route every R beat solely by RID -- and after M2c the RID IS the MSHR index, so
    // there is no per-path index arithmetic left at all.
    val rIdx = axi.r.payload.id.resize(mshrIdxBits)
    val ridIsDemand = axi.r.payload.id === U(AxiIds.I_DEMAND, AxiIds.ID_W bits)
    val ridIsPf = (axi.r.payload.id >= U(AxiIds.I_SPEC_BASE, AxiIds.ID_W bits)) &&
                  (axi.r.payload.id <= U(AxiIds.I_SPEC_LAST, AxiIds.ID_W bits))
    // Entry 0's "live on the bus" condition stays the FSM's `refillActive`, NOT
    // `mshrValid(DEMAND_IDX)`: `mshrValid(0)` spans the whole fill INCLUDING the install
    // dwell and REPLAY, whereas an R beat is only legal in REFILL. Reading mshrValid
    // here would widen the accept window by four states and silence the assertion below
    // for exactly the beats it exists to catch.
    val demandRspMatch = ridIsDemand && refillActive && mshrArSent(DEMAND_IDX)
    val pfRspMatch = ridIsPf && mshrValid(rIdx) && mshrArSent(rIdx) && !mshrComplete(rIdx)
    axi.r.ready := demandRspMatch || pfRspMatch

    when(axi.r.valid) {
      assert(demandRspMatch || pfRspMatch, "I-cache R beat has no live RID owner")
    }

    // ---- M2b: uniform R-channel write. NO demand/speculative asymmetry. ----
    // The AXI RID IS the MSHR index (I_DEMAND == 0, I_SPEC_BASE == 1), so entry 0 is
    // the demand line and entries 1..pfSlots are the speculative ones with no decode
    // and no copy. Exactly ONE `.write` call site per memory (GC-6): a second breaks
    // SpinalHDL's MultiPortWritesSymplifier -- a real previously-shipped breakage in
    // this file, see the icache-burst-fault-fix note on the lineMem commit below.
    //
    // READ/WRITE COLLISION, checked rather than assumed (these Mems have a `readSync`
    // port at `installIdx` running unconditionally, and SpinalHDL's default
    // read-under-write policy for a synchronous read is `dontCare`):
    //   - entry 0 is written only while `demandRspMatch`, which needs `refillActive`,
    //     i.e. only in REFILL. `installIdx` is only READ FOR REAL from PREDECODE, and
    //     the final beat's write lands the cycle BEFORE INSTALL_ARM, so the address is
    //     re-sampled after the write has settled.
    //   - a speculative entry is written only while `!mshrComplete(i)`, and an entry can
    //     only win the installer once `mshrComplete(i)` is set, so no write can reach the
    //     entry being installed.
    //   - a stale `installIdx` left pointing at an entry that is being refilled DOES
    //     collide, and the `dontCare` output that produces is consumed by nothing:
    //     `beatSrc`/`beatNext3` are only sampled under `predActive`.
    //
    // ---- M2c: ONE R-channel handler. No demand/speculative branch at all. ----
    // The beat counter, the error latch and the two-beat assertion were duplicated per
    // path; each is now a single `mshr*(rIdx)` statement. The ONLY thing that still
    // distinguishes the two is what happens ON TOP of "the burst finished": the demand
    // path additionally hands `refillDone`/`refillErr` to the FSM (which owes a
    // response), whereas the speculative path relies solely on `mshrComplete` for the
    // background installer to pick it up.
    //
    // Task 9 review fix I1: `mshrComplete(rIdx) := True` on the last beat is now
    // UNCONDITIONAL -- entry 0 sets it exactly like every other entry, instead of the
    // demand branch setting only `refillDone`/`refillErr` and leaving
    // `mshrComplete(DEMAND_IDX)` permanently False. This makes the whole Vec genuinely
    // uniform (matching the plan's advertised "Produces, for Tasks 11/12" interface),
    // so a FUTURE unqualified reduction over `mshrComplete` (a map, a count, a
    // generalised installer) reads a real value for the demand entry instead of a
    // silent False. It costs no new flop: the register was already emitted (see the
    // M2c honest-accounting note above `mshrComplete`'s declaration) and was expected
    // to constant-fold away specifically because it was never driven True -- now it
    // is driven, for exactly the one flop that was already there.
    //
    // No existing consumer's value changes: every current READER of `mshrComplete`
    // indexes it either via `i + AxiIds.I_SPEC_BASE` (the speculative-only reductions)
    // or via `rIdx` under an `ridIsPf` guard (`pfRspMatch`) -- both are structurally
    // confined to the speculative range and can never observe index `DEMAND_IDX`. The
    // demand entry's own liveness window is bounded by `mshrValid(DEMAND_IDX)` /
    // `refillActive`, reset False on every new demand allocation below, so a stale
    // `True` left over from a prior fill is inert wherever a future reduction pairs it
    // with `mshrValid` (exactly how every current speculative reduction is written).
    val rOwned    = demandRspMatch || pfRspMatch
    val rFire     = axi.r.fire && rOwned
    fillLo.write(rIdx, axi.r.payload.data, enable = rFire && !mshrBeat(rIdx))
    fillHi.write(rIdx, axi.r.payload.data, enable = rFire &&  mshrBeat(rIdx))

    when(rFire) {
      val respErr = axi.r.payload.resp =/= Axi4.resp.OKAY
      // Generalised from the two identical per-path assertions M2b carried.
      assert(axi.r.payload.last === mshrBeat(rIdx),
        "I-cache refill must be exactly two beats")
      when(respErr) { mshrErr(rIdx) := True }
      mshrBeat(rIdx) := !mshrBeat(rIdx)
      when(axi.r.payload.last) {
        mshrComplete(rIdx) := True
        when(ridIsDemand) {
          refillDone := True
          refillErr  := mshrErr(DEMAND_IDX) || respErr
        }
      }
    }

    // ---- predecode dwell: classify both beats, then commit the arrays ----
    // ── Slice I2: ONE 16-instance classify group, used on BOTH cycles of the dwell
    // (commitBeat 0 = low beat, commitBeat 1 = high beat). See `classifyBeat` above for
    // the full rationale and the equivalence argument.
    //
    // M2b: the dwell classifies out of the MSHR line file, not out of `lineReg`. The
    // file is split Lo/Hi precisely so classifyBeat's cross-beat lookahead (the low
    // beat's last 3 words need words 16/17/18) is still a single-cycle read: both
    // halves are presented simultaneously by the two readSync ports at `installIdx`.
    //
    // The lookahead for the LOW beat's last 3 words (line words 13/14/15 need 16/17/18)
    // is taken straight out of `fillHiQ`, which is already resident this
    // cycle — so the beat-lag the design doc describes is not even needed here; the
    // whole line is presented by the file read by the time the dwell runs. For the HIGH beat the
    // lookahead runs past the line end, which is the pre-existing `extWValid = false`
    // boundary case (the F5 fix): `classify` refuses to guess brief-vs-full for
    // anything needing that word's content and rejects as COMPLEX instead of silently
    // mis-framing, `ambiguousLine` marks it, and `Aligner.scala:63-65` re-classifies
    // live from the instruction buffer's own already-fetched words.
    val isLoBeat  = commitBeat === U(0, 1 bits)
    val beatSrc   = Mux(isLoBeat, fillLoQ, fillHiQ)
    val beatNext3 = Mux(isLoBeat, fillHiQ(47 downto 0), B(0, 48 bits))
    val beatPred  = classifyBeat(beatSrc, beatNext3, isLoBeat)

    when(predActive) {
      // An INHIBITED-mode fetch never allocates a line — no tag/valid write, no
      // victim-pointer advance (that way is not consumed; the same victim way is tried
      // again on the NEXT real allocation to this set). Slice I1 adds the poison bit: an
      // `invalidateAll` that landed mid-fill also suppresses the allocation, so a fill
      // in flight when the cache was told to drop everything cannot quietly re-install
      // its line one cycle later. (Closure (1), the same-cycle guard on the `valids`
      // write, is still present below and covers the exactly-simultaneous case.)
      //
      // M2c: the poison is read from the ENTRY BEING INSTALLED rather than from a
      // shared `missPoison` staging register that IDLE's speculative-install arm had to
      // load. Same value, one fewer copy, and entry 0 stays untouched by a speculative
      // install -- which is what lets `mshrValid(0)`/`mshrSet(0)` mean what oracle 3
      // now assumes they mean.
      val doAllocate = missCacheable && !mshrPoison(installIdx)
      // M2a/M2b: capture ONLY the window this refill's own fetch will consume, in BOTH
      // data and predecode. `missPC(5)` picks the beat, `missPC(4:3)` picks the window
      // inside it. Written on whichever dwell cycle classifies that beat, so it is
      // stable by the time REPLAY runs.
      // Deliberately OUTSIDE the `doAllocate` gate -- the whole point of the bypass is
      // that it carries the response for lines that are NOT allocated. See `bypPred`'s
      // declaration for the full equivalence, write/read-ordering and missPC-
      // immutability arguments (they cover `bypWindow` identically -- same predicate,
      // same lane index, same cycle).
      when(commitBeat === missPC(5).asUInt) {
        bypWindow := beatSrc.subdivideIn(64 bits)(missPC(4 downto 3))
        bypPred   := beatPred.subdivideIn(4 * PRED_BITS_PER_WORD bits)(missPC(4 downto 3))
      }
      when(isLoBeat) {
        // DEBUG only: the cycle immediately BEFORE the allocation write. Its one
        // consumer, IcacheSpec's invalidate-race test, self-checks the pairing by
        // re-reading `dbgAllocCommitCycle` on the next sampling point.
        dbgAllocCommitPending := doAllocate
      } otherwise {
        // The tag/valid commit still happens on commitBeat==1 only. Nothing downstream
        // shifts: REPLAY is entered the cycle AFTER commitBeat==1 either way.
        dbgAllocCommitCycle := doAllocate
        for (w <- 0 until ways) {
          when(installWay === U(w, wayBits bits)) {
            when(doAllocate) {
              // M2c: the write ADDRESS comes from `installSet` (SG-3 exception 2) and the
              // way decode from `installWay` (exception 3); only the tag DATA is read
              // out of the file at `installIdx`, where a 5:1 mux is harmless (it is
              // write data, not an accept-cone comparator and not an array address).
              tagMem(w).write(installSet, mshrTag(installIdx))
              // The `valids` write MUST yield to a same-cycle invalidate-all. The
              // declaration site above calls itself a "priority clear of all valid
              // bits", and that WAS the intent — but the clear elaborates EARLIER in
              // this file than this write, so under SpinalHDL's last-assignment-wins
              // the refill's `True` silently won on any cycle both fired, re-validating
              // a line the invalidate was supposed to clear. Reachable, not theoretical:
              // nothing gates instruction FETCH on `excActive`, so a wrong-path or
              // run-ahead refill really can be committing on the exact cycle a
              // commit-time CPUSH/CINV dispatch pulses `maintInvalidateAll` -- and with
              // slice I3 a PREFETCH fill widens that window further.
              when(!anyInvalidate) {
                valids(w)(installSet) := True
              }
              // Telemetry only: record whether the installed line arrived through a
              // silent speculative ID. Demand installation clears the marker; window
              // allocation does not depend on it.
              pfFilled(w)(installSet) := predIsPf && !anyInvalidate
            }
          }
        }
        when(doAllocate) {
          victim(installSet) := victim(installSet) + 1
        }
      }
      when(doAllocate) {
        for (w <- 0 until ways) {
          when(installWay === U(w, wayBits bits)) {
            // M1: ONE write, ONE call site (a SECOND Mem.write call site breaks
            // SpinalHDL's MultiPortWritesSymplifier -- a real previously-shipped
            // breakage in this file, see the icache-burst-fault-fix note above). The
            // entry is {beat predecode, beat data}, at exactly the address and on
            // exactly the cycle the old data-only write used. `beatSrc` is literally
            // the expression the old write inlined, so the data half is unchanged by
            // construction.
            lineMem(w).write((installSet ## commitBeat).asUInt, beatPred ## beatSrc)
          }
        }
      }
      commitBeat := commitBeat + 1
      when(!isLoBeat) {
        commitBeat := U(0, 1 bits)   // reset for the NEXT fill's predecode dwell
      }
    }

    // ---- slice I1 closure (2): poison an in-flight fill on invalidateAll ----
    // M2c: DELETED from here. It is now the `when(anyInvalidate && mshrValid(i))` lane
    // of the ONE uniform poison loop above, which covers the demand entry and the
    // speculative slots with the same statement. See that loop for the cycle-by-cycle
    // equivalence argument against the old `!fsm.isActive(IDLE)` gate, and the poison
    // note above `mshrPoison`'s declaration for why the response is still delivered and
    // why section 6.5's "invalidate the s1*/rsp* stages" bullet is deliberately NOT
    // adopted.

    // FetchService accessors
    def cmd: Stream[FetchCmd] = cmdPort
    def rsp: Flow[FetchRsp]   = rspPort
  }

  // ---- FetchService trait implementation ----
  override def cmd: Stream[FetchCmd] = logic.cmdPort
  override def rsp: Flow[FetchRsp]   = logic.rspPort
}
