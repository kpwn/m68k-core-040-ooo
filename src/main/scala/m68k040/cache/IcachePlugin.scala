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
    //
    //     UPDATE (2026-09-16): that whole paragraph described a hole this clear only
    //     PARTLY closes, and the rest is now closed structurally. `btbQueryValid0` (and
    //     the fetch-directed `ftqConfirm`, whose only confirmation was a 4-bit length
    //     match) ARE now gated on `ChunkPredecode.ctrlXfer` -- a control-transfer bit
    //     baked by `PredecodeWord.classify` at REFILL time, so the fetch-side gate is a
    //     pure memory-output AND with no logic added to the front end's FMax-critical
    //     arc. That matters because THIS clear only covers the SMC route: both predictors
    //     are keyed on VIRTUAL PC and nothing invalidates them on a TRANSLATION change
    //     (PFLUSH/PFLUSHA, a URP/SRP/TC or ITT0/ITT1 write), while the L1I -- being VIPT
    //     with a PHYSICAL tag -- correctly misses and refills the new bytes. The BTB clear
    //     below stays: it is still the right thing for the SMC case, and it also stops the
    //     predictor wasting entries on code that no longer exists.
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
    // Round-11 diagnostic (BUG_calibration_word_misplaced_0d00.md Part 57
    // recommendation #1): a build-time-only override so a SEPARATE
    // diagnostic bitstream can start this register False -- demand-fetch
    // -only, zero speculative MSHR allocations ever -- without touching
    // the sim-poke contract above (no new port; unset in every existing
    // testbench/build, so behavior there is bit-identical to before).
    // Read once at elaboration time, same `sys.env` pattern as
    // `SocketTop.scala`'s `DBG_BUILD_ID` / `FullCoreSynth.scala`'s
    // `readDbgBuildIdEnv`.
    val icPrefetchDefaultOn: Boolean = sys.env.get("DBG_IC_PREFETCH_DISABLE") match {
      case Some("1") => false
      case _         => true
    }
    val prefetchEnable = RegInit(if (icPrefetchDefaultOn) True else False)
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
    // ── CACR.IE (bit 15): THE INSTRUCTION-CACHE ENABLE ────────────────────────────
    // Until 2026-09-09 this bit had NO reader anywhere in the core -- `cacr(31)`/DE
    // was consumed in four places, `cacr(15)`/IE in none -- so the I-cache was
    // unconditionally enabled and could not be turned off. That is a real divergence
    // from a 68040 (and from the v1 core that boots this machine): software that
    // clears IE and relies on that INSTEAD of an explicit CINV goes on executing
    // stale instruction bytes, which is silent wrong CODE with no fault anywhere. It
    // also denies the boot campaign the I-side half of a bisection tool it already
    // has on the D side via DE.
    //
    // Same optional-`host.get` pattern as `privCtrl` above: a standalone I-cache DUT
    // with no RobPlugin wired defaults to TRUE (enabled), which is exactly the prior
    // unconditional behaviour -- unchanged for every existing directed I-cache test.
    val cacheCtrl = host.get[m68k040.services.CacheControlService]
    val icacheEnabled = cacheCtrl.map(_.icacheEnabled).getOrElse(True)
    // Task #195: TCR.P (8KB pages) — same optional-host pattern as `privCtrl` above.
    // A standalone I-cache DUT with no MmuControlPlugin wired defaults to False (4K
    // pages, the pre-#195 behavior, unchanged).
    val mmuCtrl = host.get[m68k040.services.MmuControlService]
    val is8K = mmuCtrl.map(_.pageSize8K).getOrElse(False)
    // A translation is demanded only while the cache is actively looking at a real
    // offered fetch. In particular, a cmd held upstream during a demand refill must
    // not start an unowned younger walk merely because its Stream valid stays high.
    // `lookupTick` opens this gate in IDLE and during a prefetch fill's install dwell;
    // all other states keep it closed.
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
    // (ITLB mux -> async LUTRAM read -> 20-bit compare -> answerable). M3b (Task 11)
    // deleted that arc outright: `cmdPort.fire` is now off registered state only.
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
    //
    // Task #251: this array's ONLY consumer anywhere in the design is the
    // `pfHitUseful` telemetry chain below (pfFilled -> pfFilledQ -> pfHitUsefulS1 ->
    // pfHitUseful), which itself has exactly ONE reader in the whole repo --
    // IcachePrefetchSpec's "M4: pfHitUseful telemetry" test. A 4-way x 64-set register
    // array (256 flops) existing solely to feed one testbench counter directly
    // contradicts this file's own adjacent principle a few hundred lines below ("the
    // core must not carry measurement-only registers"). `pfFilled` is null outside a
    // `GenerationFlags.simulation` elaboration (see M68kSim.scala / RobPlugin's
    // `pcStore` for the same idiom) -- every read/write site below is itself wrapped
    // in its own `GenerationFlags.simulation { ... }` block, so it must never be
    // referenced unguarded.
    val pfFilled = GenerationFlags.simulation { Vec.fill(ways)(Vec.fill(sets)(RegInit(False))) }

    // DEBUG (icache-corruption-fix task, temporary — mirrors DcachePlugin's
    // ldS1Valid/stS2Valid.simPublic() DEBUG hooks): exposes the raw shared arrays
    // for a directed test to assert an UNRELATED way's tag/data/pred content is
    // byte-for-byte unchanged by a same-set INHIBITED miss. No-op for synthesis.
    // CONVENTION (see docs/debug-trace-taps.md "Mem-typed state needs explicit
    // .simPublic() per instance"): tagMem/lineMem are Mem, not Reg -- each way's
    // instance needs its own simPublic() call or Mem.getBigInt(addr) throws
    // UNACCESSIBLE SIGNAL at sim time (the same gotcha hit D-cache validsMem/
    // dirtysMem in task #240).
    for (w <- 0 until ways) { tagMem(w).simPublic(); lineMem(w).simPublic() }
    valids.simPublic()

    // ---- invalidateAll: priority clear of all valid bits ----
    val anyInvalidate = invalidateAll || maintInvalidateAll
    when(anyInvalidate) {
      for (w <- 0 until ways; s <- 0 until sets) valids(w)(s) := False
      // Task #251: sim-only clear of the sim-only pfFilled array -- see its
      // declaration above.
      GenerationFlags.simulation {
        for (w <- 0 until ways; s <- 0 until sets) pfFilled(w)(s) := False
      }
    }

    // ---- live parallel-VIPT lookup context (binding amendment 2026-08-10) ----
    // Virtual set/beat arms the synchronous BRAM in the same cycle as the ITLB lookup.
    // Translation only qualifies the physical tag and the small S1 control context;
    // it is deliberately NOT on the BRAM address/enable or the wide data mux.
    val lookupPc        = cmdPort.payload.pc
    // Task #195: same PPN/offset-width split as LsEuPlugin's `s1Paddr` — 12-bit
    // offset (ppn's LSB is the real PA bit) for 4K pages, 13-bit offset (ppn's LSB
    // is architecturally undefined for 8K pages per the MC68040 UM — PA[12] comes
    // straight from the untranslated PC instead) for 8K pages. `is8K` is a plain
    // control-register read, same shape/cost as `xlate.rsp.cacheMode` already
    // consumed on this exact cycle below.
    val lookupPaddr     = Mux(is8K,
      (xlate.rsp.ppn(19 downto 1) ## lookupPc(12 downto 0)).asUInt,
      (xlate.rsp.ppn ## lookupPc(11 downto 0)).asUInt)
    val lookupFault     = xlate.rsp.fault
    val lookupCmode     = xlate.rsp.cacheMode
    // The PAGE's own cacheability, from the ITLB/TTR verdict alone. Kept separate
    // from `lookupCacheable` below because the two feed DIFFERENT decisions: this one
    // says "this is a DEVICE", the other says "this fetch may use the array".
    val lookupPageCacheable = lookupCmode =/= CacheMode.INHIBITED
    // ...and this is the effective verdict every array/allocate/prefetch decision
    // reads. CACR.IE=0 turns the whole cache off: it must not HIT (a resident line
    // would serve exactly the stale bytes IE exists to escape) and it must not
    // ALLOCATE. Both fall straight out of routing IE through the pre-existing,
    // already-tested no-allocate path -- `s0Cacheable := lookupCacheable` feeds
    // `s1Hit` (which ANDs `s0Cacheable`), `missCacheable` (which gates `doAllocate`)
    // and `s0FromMiss` (the direct-from-MSHR delivery for a non-allocated miss) --
    // rather than by inventing a second disable mechanism next to it.
    val lookupCacheable = lookupPageCacheable && icacheEnabled

    // ══ SPECULATIVE ACCESS TO CACHE-INHIBITED (DEVICE) SPACE — THE I-SIDE GATE ═══
    // MC68040 UM §3.1.2/§4: a cache-inhibited page denotes a DEVICE. A device READ has
    // an architecturally visible side effect (read-to-clear status, FIFO pop, interrupt
    // acknowledge), so it may not be performed speculatively — a squash cannot un-do it
    // at the device. `LsEuPlugin.p4LaunchOk` states and enforces exactly this rule for
    // D-side loads ("wait until I am the ROB head"). Until this signal existed nothing
    // enforced it on the I side, and the consequence was NOT theoretical: cache mode
    // reappeared only at `doAllocate` in the predecode dwell, i.e. AFTER both R beats
    // had already returned, so INHIBITED suppressed the ARRAY INSTALL and nothing else.
    // A wrong-path run-ahead fetch into a CM=10 page issued a real 64-byte INCR burst,
    // demonstrated on unmodified RTL by ExecuteLockStepSpec's "spec-mmio I-side THE
    // RULE" probe (see docs/superpowers/specs/2026-09-04-speculative-inhibited-mmio-
    // audit.md). One such burst spans four Quadra 53C96 registers, including the
    // read-to-clear Interrupt Status at +0x50.
    //
    // WHY THIS IS AN ACCEPT-SIDE GATE AND NOT AN S1 HOLD, which is the shape a reader
    // coming from the D side would expect. An S1 hold (the `s1Unresolved` `otherwise`
    // arm below) keeps a command that has ALREADY FIRED, so the plugin owes FetchAlign
    // a response for it forever: FetchAlign retires ring slots on responses and merely
    // marks them `ringStale` on a redirect, it never abandons them. A held wrong-path
    // inhibited fetch would therefore have to be either (a) launched anyway once the
    // machine drained — which is the very bus transaction this gate exists to prevent,
    // just later — or (b) abandoned with a synthesised response, which is silent
    // instruction-byte corruption the moment the staleness reasoning behind it is
    // wrong. Refusing the command at the Stream boundary has neither problem: nothing
    // has fired, no response is owed, and a redirect simply changes `cmdWindowPc`
    // underneath the un-accepted command (FetchAlign re-evaluates `ic.cmd.payload.pc`
    // combinationally every cycle and already tolerates a low `ready` for arbitrarily
    // long — `setBlocked` and `!s1Unresolved` both do it today).
    //
    // COST, stated honestly: this is the one place the M3b campaign's "cmdPort.ready
    // FROM REGISTERED STATE ONLY" property is deliberately relaxed. `lookupCacheable`
    // is a live `xlate.rsp.cacheMode` read, so the ITLB entry mux is back in the accept
    // cone — but only through an OR with a registered term, and only for the term that
    // makes a device access architecturally legal. There is no registered substitute:
    // the whole defect is that the cache mode was consulted one stage too late.
    val nonSpecFetch = Bool()
    nonSpecFetch.allowOverride
    // Default TRUE = "assume the fetch is architectural". Every standalone I-cache DUT
    // drives `cmdPort` from a directed probe rather than from a speculative frontend,
    // so for them the default is the TRUTH, not a weakening; only a DUT that actually
    // contains a speculating frontend (FullCoreSynth, SocketTop, and the three full-core
    // test DUTs) overrides it. A default of False would instead HANG every standalone
    // test that fetches an inhibited line.
    nonSpecFetch := True
    nonSpecFetch.simPublic()
    // DELIBERATELY keyed on `lookupPageCacheable`, NOT on `lookupCacheable`. This gate
    // exists because a CACHE-INHIBITED PAGE IS A DEVICE and a device read may not be
    // performed speculatively; it refuses the command until the machine is drained
    // (`nonSpecFetch := drainedQ && ringCount === 0`, SpeculativeFetchGate). CACR.IE=0
    // says nothing about what the page IS -- ordinary RAM stays ordinary RAM, it just
    // may not be cached -- and folding IE in here would declare EVERY fetch a device
    // access, so the frontend could only fetch with the pipeline empty. That is not a
    // slowdown, it is a livelock: the drain condition can never be met by a machine
    // that needs the fetch to make progress. IE belongs on the ARRAY decisions above
    // and nowhere near this one.
    val inhibitedSpecBlock = !lookupPageCacheable && !nonSpecFetch
    inhibitedSpecBlock.simPublic()

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
    // `missPC` has exactly TWO writers, and after M3b both are STILL confined to IDLE --
    // by a different, and stronger, argument than M2b's:
    //   (1) The S1 DEMAND MISS DISPATCH (M3b; this used to be `lookupTick`'s miss arm).
    //       It is explicitly gated on `fsm.isActive(fsm.IDLE)`, so it is IDLE-confined
    //       by construction rather than by an argument about `answerable`. Note this is
    //       a REAL CHANGE of shape that happens to preserve the invariant: the write now
    //       lands one cycle after the accept, but still on an IDLE cycle -- specifically
    //       the IDLE->REFILL edge, which is the same edge the old write landed on.
    //   (2) IDLE's speculative-install arm, textually inside `IDLE.whenIsActive`.
    // INSTALL_ARM, PREDECODE, REPLAY and FAULT write it not at all. So `missPC` is
    // frozen from the cycle the dwell is armed until the machine returns to IDLE, and
    // the enforcement assertion below still has a non-empty coverage window (pinned by
    // `dbgMissPcLocked`). Pinned by IcacheSpec's
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

    // ---- deadlock defense: per-slot generation counter (BUG_calibration_word_
    // misplaced_0d00.md, macqd700-soc repo, Parts 53-60) --------------------------
    // Parts 53-60's real-hardware ILA campaign structurally proved (Part 56 S8) that
    // `pfRspMatch`/`demandRspMatch` below are ID-INDEXED, not transaction-indexed: once
    // an AXI RID is reused for a NEW occupant while an OLDER transaction under that same
    // ID is still outstanding on the bus, the gate has no way to tell the two apart, and
    // a hardware capture (Part 57) found exactly this signature -- three live speculative
    // slots each one generation ahead of the last AR they actually got to send, with the
    // orphaned older response permanently jamming L2C's single shared output register and
    // deadlocking the whole speculative fetch path forever (Part 56 S8's closed circular-
    // wait proof). `IcacheIdReuseWhiteboxSpec` independently proved the gate accepts a
    // same-RID beat with NO cross-check against the slot's own current identity at all.
    //
    // `mshrGen(i)` -- a per-slot generation counter, incremented (wrapping) on EVERY
    // fresh allocation of slot `i` (the two `mshr*(idx) := ...` allocate write sites
    // below -- the demand miss-detect arm and the speculative `pfFreeMshr` arm -- are the
    // ONLY writers, mirroring exactly the two sites that (re)write `mshrTag`/`mshrPa`, so
    // a generation bump is inseparable from an identity change in real RTL).
    // `mshrArSentGen(i)` -- latched to `mshrGen(i)`'s value at the exact cycle `axi.ar.
    // fire` sends slot `i`'s AR (the single `mshrArSent(...) := True` site below).
    // Deliberately NOT reset at allocation, so it always holds the generation the LAST
    // AR actually put on the real bus for that slot belonged to, surviving any later
    // reallocation -- exactly Part 57's own debug-tap design, now load-bearing rather
    // than observation-only.
    //
    // `genMatch` below (built from these two) closes the gate against a response whose
    // RID is live but whose armed AR belongs to an OLDER generation than the slot's
    // CURRENT occupant -- and anything that fails it is drained-but-not-credited by the
    // `staleDrain` path (see the R-channel handler below), which is what actually breaks
    // the proven circular wait: an orphaned response can now ALWAYS be accepted off the
    // bus (unblocking L2C's shared register) without ever being misattributed to the
    // wrong occupant's fill.
    val mshrGen       = Vec.fill(MSHR_N)(RegInit(U(0, 4 bits)))
    val mshrArSentGen = Vec.fill(MSHR_N)(RegInit(U(0, 4 bits)))
    mshrGen.simPublic()
    mshrArSentGen.simPublic()

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
    // Part 61 fix-side verification (IcacheIdReuseWhiteboxSpec): lets a directed test
    // confirm a drained stale-generation beat left the per-slot beat-toggle bookkeeping
    // (and therefore the CURRENT occupant's own in-flight burst) completely untouched.
    mshrBeat.simPublic()
    // Round-13 whitebox ID-reuse mechanism test (IcacheIdReuseWhiteboxSpec):
    // `mshrPa` is the per-slot address the R-channel accept gate (`pfRspMatch`,
    // below) does NOT check -- it gates purely on RID (`rIdx`) + `mshrValid` +
    // `mshrArSent` + `!mshrComplete`. Exposing the address register lets that test
    // construct the worst case directly (poke a slot's `mshrPa` to a DIFFERENT
    // address than the one its outstanding AR was actually sent for, mirroring the
    // real boot's suspected "reallocated out from under an in-flight AXI ID"
    // race) and prove the gate accepts the mismatched beat anyway. `fillLo`/
    // `fillHi` are exposed the same way (each `Mem` instance needs its own call,
    // per the `tagMem`/`lineMem` precedent above) so the test can read back
    // exactly what landed in the slot's fill data after the accept.
    fillLo.simPublic()
    fillHi.simPublic()
    mshrPa.simPublic()
    mshrTag.simPublic()

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
    // M3b (Task 11): Task 5's transitional `ufaDataVec`/`ufaPredVec` Vec-index helpers
    // are DELETED with the registered `s1Way` they existed to index. The S1 stage below
    // pre-selects each way's LANE first and then one-hot AND-ORs the four narrow
    // results, so there is no Vec-index (i.e. no binary way mux) left to build.

    // ---- S0: the accept-cycle capture stage (M3a, Task 10) --------------------
    // NAMING, and it is load-bearing (spec §5.3.3). This is the SAME physical
    // register set that was called `s1*` up to Task 9: it is written IN the accept
    // cycle and read the NEXT cycle, so "s1" only ever described it from the
    // consumer's point of view. Task 11 adds the genuinely new S1 COMBINATIONAL stage
    // (the verdict, the one-hot way mux); the rename is what stops two different things
    // both being called "s1". `s1Way`, the one member of the old set M3 DELETES rather
    // than moves, is gone as of this task -- the way is COMPUTED at S1 now.
    //
    // ══ M3b (Task 11): `s0Valid` MEANS "A COMMAND WAS ACCEPTED LAST CYCLE" ═════════
    // It no longer means "a response is owed this cycle", and that widening is the
    // single most consequential line of this task. Up to Task 10 it was armed only on
    // the FAULT and HIT arms of the live comparator -- a demand MISS left it False,
    // because a miss produced no response. `s1Unresolved` is a MISS signal, so it can
    // only be built on a valid bit that is TRUE on a miss; hence the arming is now
    // UNCONDITIONAL on `cmdPort.fire`.
    //
    // The consequence, and it is a real one that the plan's literal `rspValidReg :=
    // s0Valid` would have got wrong: the RESPONSE must now be gated separately, by
    // `s0Valid && !s1Unresolved`. Leaving it as a bare `s0Valid` would emit a
    // response on the S1 cycle of every demand MISS -- one extra, data-less response
    // per miss, which FetchAlignPlugin attributes to the ring head and which would
    // then be followed by the REPLAY response for the same ring entry. That is a
    // double-retire of one ring slot: exactly risk R3's silent instruction-byte
    // mis-pairing. See `rspValidReg`'s assignment below.
    //
    // `s0Valid` is also HELD (see the S1 miss dispatch below the FSM) for as long as an
    // accepted miss has not been dispatched to a fill, so the S0 context stays alive
    // across the (bounded, <= 3 cycle) wait for the fill engine.
    //
    // THE ITLB RESULT STOPS AT `s0Ppn`/`s0Fault`/`s0Cacheable`. That is the whole
    // content of design principle P3 and the reason the census's clock-enable
    // endpoints in this plugin can stop being fed by a translation-fed comparator.
    // As of THIS task that is realised: the live comparator is deleted and every
    // consumer -- `cmdPort.ready`, the response path, the MSHR write, the prefetch
    // frontier gate and the AR arbiter -- reads the registered verdict.
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
    // M3b: which KIND of S0 context this is. False = an accepted fetch command (the
    // verdict is `s1HitVec`); True = a synthetic context injected by REPLAY or FAULT,
    // whose way is `installWay` and whose `tagQ`/`validsQ`/`s0Ppn`/`s0Cacheable` are
    // stale leftovers from the last accept and must NOT be consulted.
    //
    // SAFETY-CRITICAL, and the reason `s1Unresolved` carries a `!s0Replay` term that
    // the plan's literal `s0Valid && !s0Fault && !s1Hit` does not: REPLAY arms
    // `s0Valid` with `s0Fault := False`, and its stale `s1HitVec` is very likely
    // all-zero (the tags captured for the LAST accepted command need not match the
    // replayed line's PPN). Without `!s0Replay`, every REPLAY would look like a fresh
    // unresolved miss and dispatch a SECOND demand fill for a line that was just
    // installed -- an infinite refill loop on the first miss the core ever takes.
    val s0Replay = Reg(Bool()) init False
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
    // way-mux + window-decode already run off REGISTERED state (s1WayOh/s0Pc) with no
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
    // M3b: the "installed silently, not demand-hit yet" telemetry bit for each way of
    // the accessed set, captured on the SAME unconditional enable as `tagQ`/`validsQ`.
    // The clear used to run at S0 off `pfFilled(hitWayIdx)(lookupSet)` -- a live
    // async-tag-compare-derived INDEX into a 64-entry register array, i.e. exactly the
    // shape this task exists to remove from the accept cone. Capturing the four bits
    // and clearing at S1 off `s1HitVec` costs 4 flops and no accept-cone logic.
    //
    // Task #251: sim-only, same as `pfFilled` -- this shadow register exists purely to
    // stage `pfFilled` reads for the sim-only telemetry chain, so it is gated the same
    // way. Null outside `GenerationFlags.simulation`; every reference below is itself
    // guarded.
    val pfFilledQ = GenerationFlags.simulation { Reg(Vec(Bool(), ways)) }
    s0Valid := False   // default each cycle; armed on cmdPort.fire / REPLAY / FAULT,
                       // and HELD by the S1 miss dispatch below the FSM
    // Sim-only visibility for `IcacheVerdictShadowSpec` (and, from Task 11, for the
    // consumers' own directed tests). No synthesised logic.
    s0Valid.simPublic(); s0Pc.simPublic(); s0Set.simPublic(); s0Beat.simPublic()
    s0Lane.simPublic(); s0Ppn.simPublic(); s0Cacheable.simPublic(); s0Fault.simPublic()
    s0Replay.simPublic()

    // ══ M3b (Task 11): THE S1 VERDICT. The only hit/miss computation in the plugin. ══
    // Moved UP from its Task 10 position (it used to sit next to the live comparator it
    // shadowed) so that it precedes its consumers: the S1 output mux immediately below,
    // and `cmdPort.ready` inside the FSM.
    //
    // A tagBits-wide equality against a REGISTERED ppn, a 4-way OR, and an AND with
    // `s0Valid`. ~3 LUT levels off flop outputs -- versus the deleted LIVE path's
    // ITLB mux -> async LUTRAM read -> compare -> OR -> `answerable` -> `cmdPort.ready`,
    // which is the 20-level, 66 %-route arc that `synth/floorplan_frontend.xdc` names as
    // all 300 worst failing endpoints on the pinned routed checkpoint.
    //
    // PROVEN EQUIVALENT to the deleted live comparator, per-way and not merely
    // OR-reduced, by Task 10's in-RTL `dbgVerdictMatch` assertion running under the
    // whole 396-test lock-step suite plus `IcacheVerdictShadowSpec`'s independent
    // array-content model. That proof covered exactly these four expressions; this task
    // adds NO new term to them.
    val s1HitVec = Vec((0 until ways).map(w =>
      s0Cacheable && validsQ(w) && (tagQ(w) === s0Ppn)))
    val s1Hit    = s1HitVec.orR
    // Held high while an ACCEPTED command's miss has not yet been dispatched to a fill.
    // This is the signal that gates `cmdPort.ready`, dispatches the demand MSHR write,
    // freezes the prefetch frontier and steers the AR arbiter.
    //
    // `!s0Replay` is a DELIBERATE ADDITION to the plan's literal
    // `s0Valid && !s0Fault && !s1Hit` -- see `s0Replay`'s declaration for why omitting
    // it is an infinite refill loop rather than a subtlety. (`FAULT`'s synthetic
    // context is already excluded twice over: it sets both `s0Fault` and `s0Replay`.)
    val s1Unresolved = s0Valid && !s0Replay && !s0Fault && !s1Hit
    s1Hit.simPublic(); s1Unresolved.simPublic(); s1HitVec.foreach(_.simPublic())

    // ══ M4 (Task 12): THE REGISTERED S1 DISPOSITION ═══════════════════════════════
    // FOUR BITS and a line base are the entire interface between the lookup pipeline
    // and the speculation machinery. Nothing else crosses (design principle P4:
    // "the prefetch frontier and AR arbitration read ONLY registered state; a prefetch
    // decision one cycle later is architecturally invisible").
    //
    // These are S2 registers -- written at S1, read at S2 -- and `s1Line` HAS to be a
    // register of its own even though `s0Ppn`/`s0Set` are already flops: a HIT at S1
    // reopens `cmdPort.ready`, so a new `cmdPort.fire` can overwrite `s0Ppn`/`s0Set`
    // on the very cycle the S2 consumer runs. Re-deriving the line base from the live
    // `s0*` at S2 would seed the frontier from the WRONG command in exactly the
    // bubble-free hit stream the frontier exists for.
    //
    // `!s0Replay` mirrors `s1Unresolved`'s own qualifier: REPLAY and FAULT inject a
    // synthetic S0 context whose `s0Ppn`/`s0Cacheable`/`tagQ`/`validsQ` are the LAST
    // ACCEPTED command's leftovers (see `s0Replay`'s declaration), so admitting one
    // here would seed the frontier and compute `heldDemandMissQ` from state that does
    // not belong to it.
    //
    // HONEST SEVERITY (Task 12 review M2 -- an earlier revision of this comment implied
    // a correctness claim, and that is not what the evidence supports). MUTATION-TESTED:
    // with `!s0Replay` deleted the full I-cache suite still passes 57/57. What the term
    // actually buys, in both directions:
    //   - `heldDemandMissQ` (= `s1Disp.valid && !hit && !fault`) would go high for ONE
    //     cycle on each REPLAY, since the stale `s1HitVec` for a just-installed line is
    //     typically all-zero. That freezes the allocator for that one cycle. A
    //     PERFORMANCE nit, not a correctness bug -- nothing downstream mistakes it for a
    //     second fill, because the demand MSHR dispatch is driven by `s1Unresolved`,
    //     which carries its own `!s0Replay`.
    //   - The stale-context SEED is a no-op today rather than a wrong seed: REPLAY
    //     leaves `s0Set`/`s0Ppn` at the values of the very command that missed, so
    //     `s1Line == pfDemandLine`, the inner `when` is skipped and `pfWindowUpdate`
    //     stays low. The bus-error FAULT context would take the kill arm and clear
    //     `pfSeqValid`, which is conservative in the safe direction.
    // The term is kept because both of those are accidents of how REPLAY/FAULT happen
    // to arm S0 rather than properties anything enforces, and because it costs nothing.
    // No directed test is added for it: a one-cycle allocator freeze is below the
    // resolution of any assertion in this suite that would not itself be brittle.
    val s1Disp = new Bundle {
      val valid     = RegInit(False)
      val hit       = Reg(Bool())
      val fault     = Reg(Bool())
      val cacheable = Reg(Bool())
    }
    val s1Line = Reg(UInt(32 bits))
    s1Disp.valid     := s0Valid && !s0Replay
    s1Disp.hit       := s1Hit
    s1Disp.fault     := s0Fault
    s1Disp.cacheable := s0Cacheable
    // VIPT: the page offset is untranslated, so `s0Ppn ## s0Set ## 0` IS the physical
    // line base -- bit-identical to the deleted `lookupPaddr & ~63` one cycle earlier.
    s1Line           := (s0Ppn ## s0Set ## U(0, 6 bits)).asUInt
    s1Disp.valid.simPublic(); s1Disp.hit.simPublic(); s1Disp.fault.simPublic()
    s1Disp.cacheable.simPublic(); s1Line.simPublic()

    // ══ TASK 11 REVIEW FIX I1: THE 2-HOT WAY-SELECT NET, REPO-WIDE ════════════════
    // This task DELETED Task 10's in-RTL `dbgVerdictMatch` assertion (the live-vs-
    // registered comparator cross-check), and with it the only always-on, every-
    // simulation guard that stood over the way-select vector. Its replacement --
    // `IcacheVerdictShadowSpec`'s testbench-side one-hot check -- runs in FOUR tests of
    // ONE suite. That is a strictly narrower net than what was removed, at exactly the
    // moment the live comparator stopped watching, and it guards spec risk R3's worst
    // failure mode: `s1HitVec` feeds a one-hot AND-OR (`s1SelOh`/`s1DataW`/`s1PredW`
    // below), so a 2-hot vector does not fail loudly -- it silently ORs two ways'
    // instruction bytes together into ONE FetchRsp. FetchRsp carries no tag and
    // FetchAlignPlugin attributes by ring head, so the corruption is invisible until it
    // executes as the wrong opcode.
    //
    // A 2-hot vector means two ways of one set are simultaneously valid carrying the
    // same PPN, which is an ALLOCATOR invariant violation (one fill owner per set --
    // `pfCandSetBusy`/`demandSetOwned`, oracle 3), reached HERE at the consumer. Both
    // ends are now watched, and this end runs under every simulation in the repo: the
    // 396-test lock-step suites, `make test-fast`, and the fuzz campaign.
    //
    // Gated exactly like `s1Unresolved`'s own qualifier: REPLAY/FAULT inject a synthetic
    // S0 context whose `tagQ`/`validsQ`/`s0Ppn` are stale (see `s0Replay`'s
    // declaration), so `s1HitVec` is meaningless -- and unread -- on those cycles; the
    // output mux takes its `s0Replay` arm instead. Simulation-only, no synthesised
    // logic (same style as the `missPC` immutability, `mshrValid(DEMAND_IDX)` and
    // two-beat refill asserts elsewhere in this file, and as DcachePlugin's own
    // `CountOne(stS2HitVec) <= 1` store-hit assertion).
    assert(!(s0Valid && !s0Replay && !s0Fault) || CountOne(s1HitVec.asBits) <= U(1),
      "M3b: s1HitVec must be one-hot-or-zero on every non-replay non-fault response -- " +
      "a 2-hot vector silently ORs two cache ways into response data/predecode")

    // Slice I3 telemetry, moved from S0 to S1 with the verdict. `pfHitUseful` is a pure
    // wire (declared with the other FSM control nets below) that a testbench counts;
    // the `pfFilled` clear is real state. `!s0Replay` excludes the synthetic REPLAY/
    // FAULT contexts, whose `s1HitVec`/`pfFilledQ`/`s0Set` are stale.
    //
    // Task #251: the whole block is sim-only (it reads/writes `pfFilled`/`pfFilledQ`,
    // both null outside `GenerationFlags.simulation`). `pfHitUsefulS1` is declared
    // INSIDE the gate too -- referencing it unguarded (as the pre-#251 code did, with
    // an unconditional `Bool()` default-False declaration outside the gate) would
    // dereference a value real synthesis never even computes a meaningful default for
    // once the source registers are gone. `pfHitUseful` below (its one external reader)
    // is gated the same way.
    val pfHitUsefulS1 = GenerationFlags.simulation {
      val hu = Bool(); hu := False
      when(s0Valid && !s0Replay && !s0Fault) {
        for (w <- 0 until ways) when(s1HitVec(w) && pfFilledQ(w)) {
          pfFilled(w)(s0Set) := False
          hu                 := True
        }
      }
      hu
    }

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
      val rawW  = beat.subdivideIn(16 bits)   // raw bytes, index 0 = lowest addr
      val rawNx = nextLo.subdivideIn(16 bits) // 3 raw words of the next beat
      // Predecode consumes numeric big-endian 68k opwords, while the cache arrays
      // deliberately retain byte-address-invariant memory data.
      val w  = Vec((0 until WORDS_PER_BEAT).map(i => IcacheInstructionOrder.opword(rawW(i))))
      val nx = Vec((0 until 3).map(i => IcacheInstructionOrder.opword(rawNx(i))))
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

    // ---- S1 -> rsp output register (M3b, Task 11) ------------------------------
    // Per-way LANE PRE-SELECT runs IN PARALLEL with the tag compare (spec §5.3.2
    // device 1, NaxRiscv's BANKS_MUXES at §4.3 N-2): `s0Lane` is REGISTERED and
    // page-invariant, so it does not wait on the verdict. The one-hot AND-OR is then
    // over 64 + 32 bits instead of 256 + 128 -- one fewer OR-tree level and a quarter
    // of the LUTs. This replaces the old `ufaDataVec(s1Way)` binary way mux, which
    // selected 256 bits first and then windowed.
    val wayWindow = (0 until ways).map(w => ufaData(w).subdivideIn(64 bits)(s0Lane))
    // `windowPredBeat` takes a full PC and selects on pc(4:3); `s0Lane` IS s0Pc(4:3)
    // (both are captured from `lookupLaneIdx`/`lookupPc` at S0), so these two lines
    // select the same window. Passing `s0Pc` here rather than `s0Lane` keeps
    // `windowPredBeat`'s one signature shared with the bypass path -- do not "unify"
    // them by changing the helper.
    val wayPred   = (0 until ways).map(w => windowPredBeat(ufaPred(w), s0Pc).asBits)

    // ══ SG-2 (plan resolution; spec §5.3 sketches only the hit path) ═══════════════
    // REPLAY and FAULT deliver from a KNOWN way, not from a computed `s1HitVec`. One
    // 4-bit 2:1 mux off registered state -- it does not re-enter the accept cone.
    //
    // WAY-SELECTION CORRECTNESS, spelled out because this is the one genuinely NEW
    // signal M3b derives and Task 10's equivalence proof does NOT cover it for free:
    //   - The `!s0Replay` arm is `s1HitVec` VERBATIM -- the exact Vec Task 10's
    //     `dbgVerdictMatch` compared, PER WAY (review fix I1), against the live
    //     `hitVec` this task deletes. The old path fed `s1Way := hitWayIdx =
    //     OHToUInt(hitVec)` into a binary mux; `UIntToOh(OHToUInt(v)) === v` for any
    //     one-hot-or-zero `v`, and `hitVec` is one-hot-or-zero by the cache's own
    //     one-line-per-set invariant. So this arm selects the SAME way as before by
    //     construction, with the encode/decode round trip removed rather than trusted.
    //   - The `s0Replay` arm is `UIntToOh(installWay)`, and the old path set
    //     `s1Way := installWay` in REPLAY. Identical.
    //   - The ONE behavioural difference is FAULT, which used to force `s1Way := 0` and
    //     now selects `installWay`. Both deliver ARBITRARY array content: a fault
    //     response's `data` is DISCARDED by the only consumer
    //     (`FetchAlignPlugin.scala`'s word enqueue is gated on
    //     `!ic.rsp.payload.fault`), and `pred` is masked to all-zero by `s0Fault`
    //     below in both the old and the new form. Deliberate, and cheaper than
    //     carrying a third mux arm to reproduce a don't-care value.
    val s1WayOh = (0 until ways).map(w =>
      Mux(s0Replay, installWay === U(w, wayBits bits), s1HitVec(w)))

    /** One-hot AND-OR (spec §5.3.2 device 2) rather than `OHToUInt` + a binary mux: the
      * one-hot vector is already available, and encoding then re-decoding it is two
      * avoidable levels. `andMask` emits a plain replicated-AND, so the emitted Verilog
      * for this cone contains no encoder -- verified by reading `generated/`. */
    def ohOr(sel: Seq[Bool], data: Seq[Bits]): Bits =
      data.zip(sel).map { case (d, s) => d.andMask(s) }.reduceBalancedTree(_ | _)

    // Task icache-corruption-fix: bypass mux — for a REPLAY of a non-allocated
    // (INHIBITED) or poisoned miss (`s0FromMiss`), deliver directly from the
    // `bypWindow`/`bypPred` registers (mirrors DcachePlugin's inhibitedResp/missLine)
    // instead of the shared arrays, which were never written for that line.
    //
    // M2b: `missDataBeat`'s 512-bit half-select is gone with `lineReg`; the bypass is
    // already the exact 64-bit window REPLAY will deliver. EQUIVALENCE: the old form
    // selected beat `s0Pc(5)` of the 512-bit `lineReg` and then lane `s0Lane` of it;
    // REPLAY sets `s0Pc := missPC` and `s0Lane := missPC(4 downto 3)`, so the delivered
    // window was {beat missPC(5), lane missPC(4:3)} -- exactly the window the dwell
    // captures into `bypWindow` on the cycle `commitBeat === missPC(5)`.
    val s1Window = Mux(s0FromMiss, bypWindow, ohOr(s1WayOh, wayWindow))
    // M1b: predecode rides out of the SAME synchronous array as the data, selected by
    // the same one-hot way and a one-bit-narrower window index. The old path
    // (4 x PRED_BITS_PER_LINE async-LUTRAM reads captured into 1,024 flops) is gone.
    //
    // Fault placeholder (spec §5.1): the deleted S1 capture bank was written
    // all-zero on a translation fault (and in the task-#211 FAULT state), so the
    // window select of it delivered a zeroed predecode. With no such register -- and
    // with `ufaBeat` carrying whatever the speculatively-armed array read returned for
    // the faulting address's set/beat -- S1 must mask explicitly. One 2:1 mux on the
    // S1->rsp path (one LUT level), preserving the exact zeroed-predecode behaviour
    // Aligner/DecodeStage already rely on. Pinned by IcacheUnifiedArraySpec's
    // "M1b: a translation fault delivers ZEROED predecode".
    //
    // s0Fault and s0FromMiss are never both set (REPLAY forces s0Fault := False), so the
    // mask cannot disturb the INHIBITED/poisoned bypass.
    //
    // M2a: BOTH arms are ONE window wide (4 * PRED_BITS_PER_WORD). The bypass arm needs
    // no window select at all -- `bypPred` was captured pre-selected at missPC(5:3)
    // during the predecode dwell.
    val s1PredBits = Mux(s0FromMiss, bypPred, ohOr(s1WayOh, wayPred))
    val s1PredW    = Vec(Mux(s0Fault, B(0, s1PredBits.getWidth bits), s1PredBits)
                           .subdivideIn(PRED_BITS_PER_WORD bits)
                           .map(b => b.as(ChunkPredecode())))

    // ══ M3b: THE RESPONSE GATE. `s0Valid` alone is NOT the response condition. ═════
    // `s0Valid` now means "a command was accepted last cycle" and is therefore TRUE on
    // the S1 cycle of a demand MISS, which owes no response yet (its response is the
    // later REPLAY/FAULT one). See `s0Valid`'s declaration: a bare `rspValidReg :=
    // s0Valid` -- the plan's literal text -- emits one extra data-less response per
    // miss, double-retiring a FetchAlign ring slot. `s1Unresolved` is exactly "accepted
    // and not answerable", so its complement is exactly the response condition:
    //   s0Valid && !s1Unresolved  ==  s0Valid && (s0Replay || s0Fault || s1Hit)
    rspValidReg := s0Valid && !s1Unresolved
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
    // the (now deleted) live `hitVec`; the S0 capture (`tagQ`) needs the SAME value, and
    // a second `tagMem(w).readAsync(lookupSet)` call would elaborate a SECOND async read
    // port on each way's distributed RAM -- i.e. duplicate the LUTRAM. M3b leaves ONE
    // port with ONE reader: the S0 capture.
    val lookupTags   = Vec((0 until ways).map(w => tagMem(w).readAsync(lookupSet)))
    val lookupValids = Vec((0 until ways).map(w => valids(w)(lookupSet)))
    val lookupBeatSel   = lookupPc(5)
    val lookupLaneIdx   = lookupPc(4 downto 3)
    val lookupReadAddr  = (lookupSet ## lookupBeatSel).asUInt

    // ══ M3b (Task 11): THE LIVE ACCEPT-CONE COMPARATOR IS DELETED. ════════════════
    // `hitVec` / `isHit` / `hitWayIdx` / `answerable` / `heldDemandMiss` and Task 10's
    // whole shadow-equivalence apparatus (`dbgS0Fresh`, `dbgLiveHitQ`,
    // `dbgLiveHitVecQ`, `dbgVerdictMatch` and its in-RTL assertion) stood HERE and are
    // gone. What they proved is now load-bearing rather than observational: the single
    // surviving verdict is `s1HitVec`/`s1Hit`/`s1Unresolved`, computed near the S0
    // register set above from REGISTERED inputs only.
    //
    // `lookupTags`/`lookupValids` above SURVIVE, and their remaining reader is the S0
    // capture (`tagQ`/`validsQ`) alone -- they are latched, not compared, in this
    // cycle. That is precisely design principle P3: the ITLB result terminates at
    // `s0Ppn`/`s0Fault`/`s0Cacheable` and the async LUTRAM read terminates at `tagQ`.
    // Nothing derived from `xlate.rsp` reaches `cmdPort.ready` any more except
    // `xlate.rsp.ready` itself, which is a translation-SERVICE handshake (a cold ITLB
    // walk must still hold the command), not a translation-fed verdict.
    //
    // The two consumers of the deleted `heldDemandMiss` -- the prefetch frontier's
    // allocation freeze and the AR arbiter's blocking-slot priority -- read
    // `s1Unresolved` instead (see below the FSM). The two notions differ in WHERE the
    // stuck demand sits: `heldDemandMiss` meant "a miss is visible at the Stream
    // boundary and was refused"; `s1Unresolved` means "a miss was ACCEPTED and has not
    // been dispatched". Under M3 the second is the one that matters, because a miss is
    // now always accepted first and resolved afterwards.

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
    // M3b: driven from the S1 telemetry block near the verdict (it needs `s1HitVec`,
    // which is computed there). Kept as a wire under this name so every testbench that
    // counts it is untouched.
    //
    // Task #251: `pfHitUsefulS1` is now itself sim-only (see its declaration), so this
    // must be gated the same way. `IcachePrefetchSpec`'s M4 test reads
    // `dut.icache.logic.pfHitUseful` under `SimConfig.withVerilator`, whose DEFAULT
    // `_spinalConfig` already calls `.includeSimulation` (unlike `M68kSpinalConfig()`,
    // which `FullCoreSynth`/`GenFullCoreSynthVerilog` use un-simulation-flagged) --
    // confirmed from the SpinalHDL 1.14.1 `SpinalSimConfig` companion object's default
    // constructor, so the test's assertion power is unaffected.
    val pfHitUseful = GenerationFlags.simulation {
      val hu = Bool(); hu := pfHitUsefulS1; hu.simPublic(); hu
    }
    val demandFillStart = Bool(); demandFillStart := False
    // M3b: the demand fill is dispatched from S1, textually BELOW the FSM. This is the
    // request wire the plan's implementer note prefers over `fsm.forceGoto` -- it keeps
    // every `goto` inside the FSM, which is this file's convention.
    val startDemandFill = Bool(); startDemandFill := False
    // ── SG-1 (plan resolution; spec §5.3 does not cover this) ────────────────────
    // Today's accept gate USES the hit/fault verdict to decide acceptance while a
    // speculative slot owns the looked-up set:
    //     answerable = lookupFault || isHit || !pfLookupSetBusy       (IDLE)
    //     answerable = lookupFault || isHit                           (during a fill install)
    // Under M3 that verdict does not exist until S1, one cycle after acceptance.
    // Dropping the verdict disjuncts leaves exactly one term, `!pfLookupSetBusy`, which
    // is computed from `lookupSet` (VIRTUAL, page-invariant) against REGISTERED MSHR
    // state -- no translation, no tag compare -- i.e. exactly the kind of term design
    // principle P3 permits in the accept cone.
    //
    // DEVIATION FROM THE PLAN'S LITERAL TEXT, and it is a correction, not a
    // relaxation. The plan attributes the `|| !pfLookupSetBusy` disjunct to the
    // canStartFill = false (fill-install) arm and applies its resolution only there,
    // leaving IDLE with `pfAcceptOk := True` ("IDLE imposes no such restriction"). The
    // two arms are the other way round in the RTL: it is IDLE that carries the
    // disjunct. Applying the plan's own rule to the arm that actually has the term
    // means gating IDLE too -- and it MUST be gated, because IDLE is the one arm that
    // can dispatch a demand fill. Without it, a demand miss to a set a live speculative
    // fill already owns would be accepted and would dispatch a SECOND fill into that
    // set; if it is the same LINE, both install and the set ends up with two valid ways
    // carrying the same tag, which makes `s1HitVec` 2-hot and the one-hot AND-OR below
    // deliver the bitwise OR of two ways. That is the "one fill owner per set"
    // invariant the whole allocator is built around.
    //
    // COST: strictly more conservative than today. A HIT to a set that a speculative
    // slot happens to own is now held for the install dwell instead of answered.
    // Bounded (the slot is freed at the end of its 3-cycle dwell) and measured, not
    // assumed, by the Task 14 IPC gate. NO DEADLOCK: with nothing accepted,
    // `s1Unresolved` is low, so the AR arbiter is free to launch the blocking slot's
    // own read and drain it.
    val pfAcceptOk = Bool()
    pfAcceptOk := True     // only meaningful where `lookupTick` overrides it
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
    // `icacheEnabled`: with the cache off there is nothing to prefetch INTO -- a
    // speculative fill would allocate a line the demand path is forbidden to hit,
    // burning bus bandwidth to poison the array for whenever IE goes back on.
    // `s0KillsWindowQ` (which already carries `!s0Cacheable`) closes the window on
    // every accepted fetch anyway; this states the invariant at the source rather
    // than relying on that timing.
    val pfWindowHasCandidate = pfSeqValid && prefetchEnable && icacheEnabled &&
      (pfNextPa <= pfLimitPa) && (pfNextPa(31 downto 12) === pfDemandLine(31 downto 12))

    // A held demand matching any live speculative line must re-look-up after install;
    // a same-set/different-line demand waits as well, preserving one fill owner per set.
    //
    // M4: `lookupLineBase` (`lookupPaddr & ~63`) stood here and was DEAD -- its last
    // reader went away with an earlier slice. Deleted with the rest of this task's
    // live-`lookupPaddr` clean-out so the Step-7 grep means what it says.
    // Speculative-only, and it must stay that way: its ONLY consumers are SG-1's
    // `pfAcceptOk` inside `lookupTick` and `heldOnSetBusy` just below, both of which are
    // reached only while the machine can accept a command -- and in those states
    // `mshrValid(DEMAND_IDX)` is False by construction, so widening it to the whole file
    // would be a literal no-op, and a misleading one.
    val pfLookupSetBusy = Vec((0 until pfSlots).map(i =>
      mshrValid(i + AxiIds.I_SPEC_BASE) &&
        (mshrSet(i + AxiIds.I_SPEC_BASE) === lookupSet))).orR

    // ══ M3b: the SECOND half of what `heldDemandMiss` used to mean ════════════════
    // The deleted `heldDemandMiss` conflated two situations that M3 separates:
    //   (a) a demand that was ACCEPTED and whose miss is not yet dispatched -> that is
    //       `s1Unresolved`, a purely registered-input signal;
    //   (b) a demand that is OFFERED at the Stream boundary and REFUSED because a live
    //       speculative fill already owns its set -> that is this signal.
    // (b) does not disappear under M3 -- SG-1's accept gate is what creates it -- and it
    // is the one the AR arbiter's blocking-owner priority is about: the demand cannot
    // make progress until that specific slot's fill lands, so its AR must outrank the
    // lower-numbered slots' (`IcachePrefetchSpec`'s "a held demand's blocking owner
    // outranks lower-numbered slots in the AR arbiter", which is a real anti-starvation
    // property, not a tie-break preference).
    //
    // NO VERDICT IS INVOLVED, which is why this is not a re-creation of what this task
    // deleted: there is no tag compare and no ITLB PPN here. `pfLookupSetBusy` is
    // registered MSHR state against the VIRTUAL `lookupSet`, and `xlate.rsp.ready` is
    // the translation-SERVICE handshake (kept for parity with the deleted expression: a
    // command whose walk has not completed is not yet a demand worth reprioritising
    // for). It feeds ONLY the allocator freeze and the AR arbiter -- never
    // `cmdPort.ready`, whose `pfAcceptOk` term already carries `pfLookupSetBusy`
    // directly -- so it adds nothing to the accept cone.
    //
    // M4 (Task 12) makes this disposition fully registered -- see `heldOnSetBusyQ`
    // immediately below. `heldOnSetBusy` itself survives as the REGISTER INPUT only; it
    // no longer reaches any clock enable or payload mux.
    val heldOnSetBusy = lookupActive && cmdPort.valid && xlate.rsp.ready && pfLookupSetBusy

    // ══ M4: BOTH HALVES OF `demandStuck`, REGISTERED ══════════════════════════════
    // (a), registered. Because `s1Disp.valid`/`hit`/`fault` are all written from the
    // SAME cycle's `s0Valid && !s0Replay` / `s1Hit` / `s0Fault`, this expression is
    // EXACTLY `RegNext(s1Unresolved)` -- `Reg(x) && !Reg(y) && !Reg(z)` is
    // `Reg(x && !y && !z)`. Named the way the plan names it because it is the
    // "an architectural miss is visibly held" term, not a generic delay line.
    val heldDemandMissQ = s1Disp.valid && !s1Disp.hit && !s1Disp.fault

    // ── (b), registered, AND the AR arbiter's set key ────────────────────────────
    // TASK 11 LEFT THIS HALF-MIGRATED and named Task 12 as its owner: `pfBlockingArWant`
    // still keyed on the LIVE `lookupSet`, and the AR hold gate's `pfBlockingArAny`
    // escape hatch was taken under BOTH (a) and (b) even though the key only names the
    // right slot under (b). Under (a) the Stream boundary holds a DIFFERENT, younger
    // command, so `pfBlockingArAny` there was a term about an unrelated command opening
    // a gate held for the unresolved miss -- bounded and not a deadlock (it only ever
    // let ONE more speculative AR park), but meaningless. Both are fixed here:
    //   - the key is `heldSetQ`, the registered set of the demand that is actually
    //     being refused;
    //   - the escape hatch is qualified by `heldOnSetBusyQ`, so it is only taken in the
    //     case where a blocking owner can exist at all.
    //
    // `heldSetQ` IS EXACT, not merely stable: its enable is `heldOnSetBusy`, and its
    // only reader is qualified by `heldOnSetBusyQ = RegNext(heldOnSetBusy)`. So every
    // cycle it is read, it was written on the immediately preceding cycle, from the
    // `lookupSet` of the very demand that was refused. It therefore needs no init and
    // can never be consulted stale. (A reset value would be dead logic; SpinalHDL is
    // happy with an uninitialised `Reg` whose every read is qualified this way, and the
    // rest of the S0 context -- `s0Pc`, `s0Set`, `tagQ` ... -- is uninitialised for the
    // same reason.)
    //
    // WHY A ONE-CYCLE-LATE (b) IS SAFE, traced rather than asserted:
    //   - LIVENESS. The escape hatch exists to prevent a REAL deadlock: while a demand
    //     is refused because a speculative slot owns its set, the ONLY thing that can
    //     unblock it is that slot's own fill, so the arbiter must be allowed to launch
    //     an AR even though a demand is stuck. `heldOnSetBusy` is a LEVEL: it holds for
    //     as long as the demand is refused (Stream contract keeps `cmdPort.valid` high
    //     until `ready`, and `lookupActive` is asserted by both IDLE and the install
    //     dwell -- the states in which a refusal can occur). So the hatch opens one
    //     cycle later, never "not at all".
    //   - SAFETY. The one-fill-owner-per-set invariant is NOT carried by the outer
    //     `!demandStuck` freeze; it is carried by `pfCandSetBusy` (a live reduction over
    //     ALL MSHR entries) and `demandSetOwned`. Under (b), `pfCandSetBusy` is True for
    //     the refused demand's set BY DEFINITION -- a speculative slot owns it -- so the
    //     one uncovered cycle cannot allocate into that set.
    //   - Under (a), the uncovered cycle is the S1 cycle itself, and it is covered
    //     set-specifically by `demandSetOwned`'s `s1Unresolved && s0Set === pfCandSet`
    //     lane. SEE THAT LANE'S COMMENT: it was documented as "redundant with the outer
    //     `!s1Unresolved` gate ... kept because it survives a future reorganisation of
    //     that gate". M4 IS that reorganisation, and the lane is now LOAD-BEARING.
    //     It is deliberately NOT migrated to the registered form.
    val heldOnSetBusyQ = RegNext(heldOnSetBusy) init False
    val heldSetQ       = RegNextWhen(lookupSet, heldOnSetBusy)
    // "A demand fill is imminent or blocked" -- the union of (a) and (b), i.e. the exact
    // condition the deleted `heldDemandMiss` served at its two consumers, now entirely
    // out of flops.
    val demandStuckQ = heldDemandMissQ || heldOnSetBusyQ

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

    // M4: takes the line base as a PARAMETER, registered, instead of computing it from
    // the live `lookupPaddr`. The 32-bit add, the page-boundary clamp and the whole
    // comparison chain now run off flop outputs, one stage later. The BODY is otherwise
    // byte-identical to its pre-M4 form; `line` is simply bound to the argument instead
    // of to `lookupPaddr & ~63`, and `pageEnd` to `line(31:12)` instead of
    // `lookupPaddr(31:12)` -- the same 20 bits, since masking off bits 5:0 cannot change
    // bits 31:12.
    def seedPfWindow(line: UInt): Unit = {
      val pageEnd = (line(31 downto 12) ## U(0xfff, 12 bits)).asUInt & ~U(63, 32 bits)
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

    // ══ M4: THE FRONTIER IS SEEDED FROM THE REGISTERED DISPOSITION ════════════════
    // Enabled by `s1Disp` ALONE -- no `cmdPort.fire`, no live translation, no live
    // `lookupPaddr`. This block is what used to sit inside `when(cmdPort.fire)` in
    // `lookupTick`; it is moved here verbatim with `lookupPaddr & ~63` replaced by
    // `s1Line` and the enable replaced by `s1Disp.valid`.
    //
    // TEXTUAL POSITION IS LOAD-BEARING, twice, and it is preserved from the deleted
    // form (which elaborated inside the FSM, i.e. ABOVE both of these):
    //   - it must precede the allocator below, so that on a cycle where BOTH could
    //     write `pfNextPa` the allocator wins, exactly as before. (They are in fact
    //     mutually exclusive -- `pfWindowUpdate` covers every case in which
    //     `seedPfWindow` writes `pfNextPa` -- but the ordering is what makes that a
    //     belt-and-braces claim rather than the only thing holding it up.)
    //   - it must precede `when(anyInvalidate) { pfSeqValid := False }` below, so an
    //     invalidate still wins over a same-cycle seed.
    //
    // IDEMPOTENT UNDER THE S1 HOLD, which is new and required: `s0Valid` is HELD while
    // an accepted miss waits for the fill engine, so `s1Disp.valid` is True for the
    // whole hold rather than for one cycle. Re-running the seed is a no-op after the
    // first cycle -- `pfDemandLine` now equals `s1Line`, so the inner `when` is skipped
    // and `pfWindowUpdate` is False -- and the fault/uncacheable arm is likewise
    // idempotent (`pfSeqValid := False` twice is `pfSeqValid := False`).
    when(s1Disp.valid) {
      when(!s1Disp.fault && s1Disp.cacheable) {
        val sequential = pfSeqValid &&
                         (s1Line(31 downto 12) === pfDemandLine(31 downto 12)) &&
                         (s1Line === (pfDemandLine + U(64, 32 bits)))
        pfWindowUpdate := !pfSeqValid || ((s1Line =/= pfDemandLine) && !sequential)
        seedPfWindow(s1Line)
      } otherwise {
        pfWindowUpdate := True
        pfSeqValid     := False
      }
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
      // Stream command and re-look-up.** A demand miss to the SET a speculative fill
      // owns is not accepted or allocated; it remains stable and, once the fill lands,
      // HITS. M3b (Task 11) is what makes that statement rest on SG-1's
      // `pfAcceptOk = !pfLookupSetBusy` -- a page-invariant, registered-state term --
      // rather than on the deleted `answerable`'s live hit/fault verdict. A miss to some
      // OTHER set MAY now be accepted mid-dwell; it is simply held at S1 (`s1Unresolved`
      // keeps `s0Valid` alive and the port shut) until the machine reaches IDLE and
      // dispatches it, which is at most the remainder of the 3-cycle dwell away.
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
        // and tag lookup. Never gate this enable with a hit/miss VERDICT: doing so would
        // put translation and tag comparison back on the BRAM ENARDEN path (and under
        // M3b the verdict does not exist in this cycle at all). Reads for a miss/fault/
        // held command are harmless: a miss's response comes from REPLAY, which re-arms
        // this port itself, so the clobbered `ufaBeat` is never consumed.
        // M2c (SG-3 exception 1): reads `installSet`, the dedicated dwell register, not
        // `mshrSet(installIdx)` -- this comparator is in the ACCEPT cone and must not
        // grow a 5:1 Vec mux in front of it. `installSet` was maintained in lock-step
        // with the now-deleted `missSet` since Task 8, so this switch is a no-op.
        val setBlocked = if (canStartFill) False
                         else (fillArrayWrActive && (lookupSet === installSet))
        ufaReadAddr := lookupReadAddr
        ufaReadEn   := cmdPort.valid && !setBlocked

        // ══ M3b: `cmdPort.ready` FROM REGISTERED STATE ONLY. ═════════════════════
        // No ITLB mux, no async LUTRAM read, no 20-bit comparator and no 4-way OR
        // upstream of a Stream ready that leaves the plugin -- nor of any clock enable
        // in it. The three surviving terms are:
        //   `xlate.rsp.ready`  a translation-SERVICE handshake (a cold ITLB walk must
        //                      still hold the command); NOT a verdict.
        //   `!setBlocked`      the D3-SET-I guard: virtual `lookupSet` vs the registered
        //                      `installSet`. Unchanged.
        //   `!s1Unresolved`    a tagBits equality against a REGISTERED ppn, a 4-way OR
        //                      and an AND -- ~3 LUT levels off flop outputs.
        //   `pfAcceptOk`       SG-1's page-invariant, registered-state prefetch-fill
        //                      accept gate (see its declaration above the FSM).
        //
        // `!s1Unresolved` is what makes the accepted-then-resolved shape safe: from the
        // cycle a miss is DISCOVERED until it is dispatched to a fill, no younger
        // command may be accepted. `FetchRsp` carries no tag and `FetchAlignPlugin`
        // attributes responses by ring head, so admitting one would be silent
        // instruction-byte mis-pairing (risk R3). Pinned by IcacheVerdictShadowSpec's
        // "M3b: no younger command is accepted behind an unresolved miss", and its
        // opposite direction (the gate must be LOW on a hit, or every straight-line
        // fetch loses an issue slot) by "M3b: sustained II=1 on hits".
        //   `!inhibitedSpecBlock` the CM=10 speculation gate. See its declaration for
        //                      the full rationale, and for the one deliberate exception
        //                      it makes to the "registered state only" property above.
        pfAcceptOk := !pfLookupSetBusy
        cmdPort.ready := xlate.rsp.ready && !setBlocked && !s1Unresolved && pfAcceptOk &&
                         !inhibitedSpecBlock

        when(cmdPort.fire) {
          // ══ M3a S0 CAPTURE ═════════════════════════════════════════════════════
          // UNCONDITIONAL on every accepted command -- one shallow enable, no arm.
          // The page-invariant virtual address bits, plus the ONE cycle the
          // translation is allowed to be live. Placing any of this inside the
          // fault/hit/miss chain below would be a real bug: the MISS arm is precisely
          // the case Task 11's `s1Unresolved` needs a valid registered context for.
          //
          // M3b: `s0Valid`/`s0Replay`/`s0Atc`/`s0FromMiss` join the unconditional
          // capture. The fault/hit/miss `when` chain that used to follow is DELETED
          // whole -- it existed only to consume the live verdict, and every one of its
          // three arms' effects now happens at S1 (the response gate), at the S1 miss
          // dispatch (the MSHR write), or in the S1 telemetry block (the `pfFilled`
          // clear). What remains here is a flat capture under one shallow enable.
          //   `s0Atc  := lookupFault`  -- the old fault arm set True, the old hit arm
          //     set False, i.e. exactly `lookupFault`. (It is a response-payload
          //     attribute, not part of the verdict; FAULT sets the opposite value.)
          //   `s0FromMiss := False`    -- all three old arms set False here.
          s0Valid     := True
          s0Replay    := False
          s0Atc       := lookupFault
          s0FromMiss  := False
          s0Pc        := lookupPc
          s0Set       := lookupSet
          s0Beat      := lookupBeatSel
          s0Lane      := lookupLaneIdx
          s0Ppn       := lookupTag
          s0Cacheable := lookupCacheable
          // DEVIATION from the plan's Step 4 list, which leaves `s0Fault` arm-local:
          // it is captured here instead, because the plan's OWN "Produces" interface
          // calls it part of "the registered translation verdict" and because
          // `s1Unresolved` reads it. EXACTLY EQUIVALENT: the (now deleted) fault arm was
          // `when(lookupFault)` and set it True, the hit arm was reached only when
          // `!lookupFault` and set it False -- i.e. both set precisely `lookupFault`.
          s0Fault     := lookupFault
          for (w <- 0 until ways) {
            tagQ(w)      := lookupTags(w)
            validsQ(w)   := lookupValids(w)
          }
          // M3b: same enable, same index (`lookupSet`) as the tag/valid capture above,
          // so the four bits belong to the same set the verdict is computed for. Task
          // #251: split out of that loop and sim-gated -- `pfFilledQ`/`pfFilled` are
          // both sim-only telemetry plumbing now (see their declarations), whereas
          // `tagQ`/`validsQ` remain real synthesized state the verdict depends on.
          GenerationFlags.simulation {
            for (w <- 0 until ways) pfFilledQ(w) := pfFilled(w)(lookupSet)
          }
          // M4 (Task 12): the frontier seed / window-kill that stood HERE is deleted.
          // It was the LAST live-translation consumer outside the S0 capture: it read
          // `lookupFault`, `lookupCacheable` AND `lookupPaddr`, and it drove the clock
          // enables of `pfNextPa`/`pfDemandLine`/`pfLimitPa`/`pfSeqValid` (96 flops) as
          // well as `pfWindowUpdate`, which gates the whole allocator. Its replacement
          // runs off `s1Disp`/`s1Line` -- see "THE FRONTIER IS SEEDED FROM THE
          // REGISTERED DISPOSITION" above the FSM.
          // M3b: the fault/hit/miss verdict chain that stood HERE is deleted whole.
          // See the S1 miss dispatch below the FSM for where the MSHR allocation it
          // performed now lives, and why moving it there also deletes the separate
          // ~93-flop miss context the plan's spec §5.3.3 describes: `missPC`/`missPA`/
          // `missSet`/`missTag`/`victimWay` were a SECOND copy of values the `s0*`
          // registers already hold.
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
        //
        // ── M3b/SG-1 COMPANION: do not start a speculative install into the set of a
        // command accepted THIS cycle. Under M3 the demand miss is dispatched one cycle
        // after acceptance, so IDLE can no longer rely on `demandFillStart` to keep the
        // installer out of the way: on the accept cycle the verdict does not exist yet,
        // and if the installer wins, the accepted command's S1 dispatch is deferred for
        // the whole 3-cycle dwell (INSTALL_ARM + 2 x PREDECODE) while `tagQ`/`validsQ`
        // stay FROZEN at their accept-cycle values. Freezing them is what makes the held
        // miss correct -- but only if nothing installs into that set meanwhile, or the
        // frozen "miss" would allocate a SECOND way for a line that has become resident
        // (a 2-hot `s1HitVec`).
        //
        // HONEST STATUS: **redundant today, and deliberately kept.** SG-1's accept gate
        // already implies it -- `pfInstallAny` requires `mshrValid` on the installing
        // entry, so if that entry's set equalled `lookupSet` then `pfLookupSetBusy` would
        // be high, `pfAcceptOk` low, and `cmdPort.fire` low; the term is therefore
        // provably never True as the file stands, and no test can distinguish its
        // presence (confirmed: mutating it out changes nothing). It is kept because the
        // hold's correctness DEPENDS on that implication, SG-1 is explicitly flagged as
        // conservative and re-measured by Task 14's IPC gate, and a future relaxation of
        // `pfAcceptOk` would silently re-open this window with no local warning. Cost is
        // a 6-bit compare in an FSM transition condition -- `cmdPort.fire` is a FANOUT of
        // the accept cone, not an input to it, so `cmdPort.ready` is not lengthened.
        val pfInstallSetConflict = cmdPort.fire && (mshrSet(pfInstallMshr) === lookupSet)
        when(pfInstallAny && !demandFillStart && !pfInstallSetConflict) {
          installIdx    := pfInstallMshr
          installSet    := mshrSet(pfInstallMshr)
          installWay    := mshrWay(pfInstallMshr)
          missPC        := mshrPa(pfInstallMshr)
          missCacheable := True
          commitBeat    := U(0, 1 bits)
          predIsPfReg   := True
          goto(INSTALL_ARM)
        }
        // M3b: the demand fill's transition. LAST, so it wins over the speculative
        // install arm above on the cycle both are offered -- the same priority the old
        // in-line `goto(REFILL)` had via `demandFillStart` (which is still asserted, so
        // the arm above is doubly excluded).
        when(startDemandFill) { goto(REFILL) }
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
        s0Valid  := True
        // M3b: FAULT delivers from a KNOWN way (SG-2). The old form forced `s1Way := 0`
        // and got way 0's ARBITRARY array content; the new form gets `installWay`'s,
        // equally arbitrary. A fault response's `data` is DISCARDED by its only consumer
        // (FetchAlignPlugin enqueues words only under `!ic.rsp.payload.fault`) and its
        // `pred` is masked to all-zero off `s0Fault`. `s0Replay` also excludes this
        // synthetic context from `s1Unresolved` -- doubly, since `s0Fault` is set too.
        s0Replay := True
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
        s0Valid  := True
        // M3b (SG-2): REPLAY delivers the just-installed line from `installWay`, which
        // `s1WayOh` selects directly. This is bit-identical to the deleted
        // `s1Way := installWay` + binary mux.
        //
        // `s0Replay` is what keeps this synthetic context out of `s1Unresolved`. REPLAY
        // leaves `tagQ`/`validsQ`/`s0Ppn`/`s0Cacheable` at the LAST ACCEPTED command's
        // values, so `s1HitVec` here is meaningless and very likely all-zero -- without
        // the guard every REPLAY would read as a fresh unresolved miss and dispatch a
        // second fill for the line it has just installed, forever.
        s0Replay := True
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

    // ══ M3b (Task 11): THE DEMAND MISS DISPATCH, FROM S1 ══════════════════════════
    // Spec §5.3.3: the separate miss-context capture (missPC/missPA/missSet/missTag/
    // missCacheable/victimWay, ~93 flops, enable `cmdPort.fire && !fault && !hit`)
    // disappears -- the `s0*` registers ALREADY hold every one of those values, and the
    // enable is now the shallow `s1Unresolved && IDLE`. Placed outside the FSM, next to
    // the other hoisted datapath; the state change is requested via `startDemandFill`
    // (the plan's preferred form) so every `goto` stays inside the FSM.
    //
    // ── WHY THE `otherwise` HOLD EXISTS, and why it is safe ─────────────────────────
    // `s0Valid` defaults to False every cycle, so without a hold an accepted miss that
    // cannot be dispatched THIS cycle would simply evaporate: no fill, no response, and
    // FetchAlign's ring entry never retires -- a hard front-end wedge. The hold keeps
    // the whole S0 context (and therefore `s1Unresolved`, and therefore the closed
    // accept gate) alive until the fill engine is free.
    //
    // The hold is BOUNDED and its verdict cannot go stale while it lasts:
    //   - It can only occur while the FSM is NOT in IDLE, i.e. only across a
    //     SPECULATIVE install (INSTALL_ARM + 2 x PREDECODE, <= 3 cycles) -- IDLE
    //     dispatches unconditionally, and no other non-IDLE state calls `lookupTick`.
    //   - `tagQ`/`validsQ` are frozen (their only enable is `cmdPort.fire`, which the
    //     closed gate forbids). That is EXACT rather than merely stable, because
    //     nothing can install into `s0Set` during the hold: the running install is
    //     excluded by `setBlocked` if it started after the accept and by IDLE's
    //     `pfInstallSetConflict` guard if it started on the accept cycle, and any other
    //     speculative slot is excluded by SG-1's `!pfLookupSetBusy` at accept time plus
    //     the allocator's `!s1Unresolved` freeze during the hold.
    //   - `victim(s0Set)` likewise cannot advance (it advances only for `installSet`).
    // An `invalidateAll` during the hold can only turn MORE ways invalid, which leaves
    // the frozen verdict conservative (still a miss) and is handled downstream by the
    // MSHR poison bit exactly as before.
    when(s1Unresolved) {
      // `!fillArrayWrActive` is redundant with `isActive(IDLE)` (only PREDECODE asserts
      // it) and is kept as the plan writes it: it states the D3-SET-I intent locally.
      when(fsm.isActive(fsm.IDLE) && !fillArrayWrActive) {
        missPC                   := s0Pc
        missCacheable            := s0Cacheable
        mshrValid(DEMAND_IDX)    := True
        mshrArSent(DEMAND_IDX)   := False
        mshrComplete(DEMAND_IDX) := False
        mshrBeat(DEMAND_IDX)     := False
        mshrErr(DEMAND_IDX)      := False
        mshrPoison(DEMAND_IDX)   := False
        mshrGen(DEMAND_IDX)      := mshrGen(DEMAND_IDX) + 1  // deadlock defense: fresh allocation
        // Identical to the deleted `lookupPaddr` capture: `s0Ppn` IS `lookupPaddr(31:12)`
        // and `s0Pc(11:0)` IS `lookupPc(11:0)` = `lookupPaddr(11:0)` (VIPT: the page
        // offset is untranslated).
        mshrPa(DEMAND_IDX)       := (s0Ppn ## s0Pc(11 downto 0)).asUInt
        mshrSet(DEMAND_IDX)      := s0Set
        mshrTag(DEMAND_IDX)      := s0Ppn
        mshrWay(DEMAND_IDX)      := victim(s0Set)
        demandFillStart          := True
        startDemandFill          := True
      } otherwise {
        s0Valid := True   // HOLD (see above)
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
    // state is IDLE (the two writers -- M3b's S1 demand-miss dispatch, which is gated on
    // `fsm.isActive(fsm.IDLE)`, and IDLE's speculative install arm -- are both
    // IDLE-confined). Equivalently: on any cycle where the fill
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
    //
    // ── M3b: TWO MORE LANES, both for the same reason ────────────────────────────
    // `mshrValid(DEMAND_IDX)` only becomes True on the cycle the demand fill is
    // DISPATCHED, which under M3 is one cycle after the command was accepted. Two
    // earlier moments must therefore be covered explicitly, or a speculative slot can
    // be allocated to the set an accepted demand miss is about to claim -- two fills
    // owning one set, which is the invariant the whole allocator rests on. If they
    // targeted the same LINE the set would end up with two valid ways carrying the same
    // tag, i.e. a 2-hot `s1HitVec` feeding the one-hot AND-OR.
    //   - `s1Unresolved && s0Set === pfCandSet` (the plan's Step 3 extension) covers the
    //     accepted-but-not-yet-dispatched window. Under Task 11 it was redundant with the
    //     outer `!s1Unresolved` gate below and kept "because it survives a future
    //     reorganisation of that gate".
    //     ══ M4 (Task 12) IS THAT REORGANISATION, AND THIS LANE IS NOW LOAD-BEARING ══
    //     The outer gate is now `!demandStuckQ`, a REGISTERED disposition that is one
    //     cycle late, so it does NOT cover the S1 cycle of an accepted-but-held miss.
    //     This lane does, exactly and set-specifically. It is DELIBERATELY LEFT LIVE
    //     (`s1Unresolved`, `s0Set`) rather than migrated with the rest of Task 12: it is
    //     an allocator-input term, not a clock enable on the frontier registers, and
    //     migrating it would re-open precisely the two-fill-owners-per-set window that
    //     ends in a 2-hot `s1HitVec`. Mutation-proved in the Task 12 report: with this
    //     lane removed AND the outer gate registered, a same-set speculative allocation
    //     races an accepted demand miss.
    //   - `cmdPort.fire && lookupSet === pfCandSet` covers the ACCEPT CYCLE itself,
    //     which the plan's text does not. It is not hypothetical: when the frontier has
    //     stalled (no free slot, or prefetch disabled) `pfNextPa` can still be sitting
    //     exactly at the line the demand is now accepting, so `pfCandSet === lookupSet`.
    //     The deleted live `heldDemandMiss` covered this instant -- it was True on the
    //     accept cycle of a miss -- and `s1Unresolved`, being one cycle later, does not.
    val demandSetOwned = (mshrValid(DEMAND_IDX) && (mshrSet(DEMAND_IDX) === pfCandSet)) ||
                         (s1Unresolved && (s0Set === pfCandSet)) ||
                         (cmdPort.fire && (lookupSet === pfCandSet))
    // M3b: `!heldDemandMiss` -> `!demandStuck`, i.e. the union of "accepted miss not yet
    // dispatched" and "offered demand refused because a speculative slot owns its set".
    // Same purpose as before: freeze old-window allocation while a demand fill is
    // imminent or blocked, and let the AR arbiter launch only the owner that unblocks it.
    // M4: `!demandStuck` -> `!demandStuckQ`, i.e. this 96-flop frontier's clock enable
    // is now off flops end to end. It is a HEURISTIC freeze and one cycle of lateness in
    // it is a performance question, not a correctness one -- the correctness question,
    // "can a speculative slot be allocated to the set an accepted demand is about to
    // claim", is answered by `demandSetOwned`'s three lanes and `pfCandSetBusy` above,
    // all of which stay live/exact.
    //
    // ══ TASK 12 REVIEW FIX I1: THE WINDOW-KILL IS 2 CYCLES LATE. CLOSE T+1. ═══════
    // The window kill (`pfWindowUpdate := True; pfSeqValid := False` in the fault /
    // uncacheable arm of the frontier seed above) now runs off `s1Disp`, which is
    // `RegNext(s0Valid && !s0Replay)`. Naming the accept cycle T:
    //
    //   T    a command is accepted whose LIVE translation verdict is FAULT or
    //        INHIBITED. Nothing registered reflects it yet.
    //   T+1  `s0Fault`/`s0Cacheable` now carry the verdict, but the only freeze that
    //        reads them is `demandFillStart`, and only for the INHIBITED-in-IDLE
    //        dispatch; a FAULT never reaches the fill engine at all (`s1Unresolved`
    //        is False for it), so under Task 12 as committed a FAULT got NO freeze
    //        on this cycle.
    //   T+2  `s1Disp` fires, `pfWindowUpdate` goes high, `pfSeqValid` clears. Frozen
    //        from here on, permanently -- `pfWindowHasCandidate` needs `pfSeqValid`.
    //
    // The parent RTL killed the window ON cycle T (the seed read `lookupFault`/
    // `lookupCacheable` inside `when(cmdPort.fire)`), i.e. zero cycles of exposure.
    // Cycles T and T+1 are therefore a REGRESSION, and what they expose is a rule-P1
    // violation and not merely a lost prefetch: a speculative slot can be allocated
    // from -- and its AR launched against -- a window whose page the core has, on the
    // very cycle in question, been told is unmapped or cache-inhibited. Rule P1
    // forbids exactly that: no speculative bus transaction may rest on a stale
    // cacheability/backing assumption.
    //
    // This term closes T+1, for BOTH causes, off flop outputs only (`s0Valid`,
    // `s0Replay`, `s0Fault`, `s0Cacheable` are all Task-10/11 S0-capture registers),
    // so M4's "no live translation verdict in the frontier cone" property is intact.
    // `!s0Replay` matches `s1Unresolved`'s and `s1Disp.valid`'s own qualifier: the
    // synthetic REPLAY / bus-error-FAULT contexts leave `s0Cacheable` at the LAST
    // ACCEPTED command's value, so reading it there would freeze the allocator off a
    // stale bit (and neither of those contexts kills the window at T+2 either --
    // `s1Disp.valid` excludes them -- so freezing for them would not even be
    // conservative in the same direction).
    //
    // Purely conservative, zero legitimate prefetch lost: every cycle this term is
    // high is a cycle on which the window is already condemned and will be dead one
    // cycle later, so any allocation it suppresses was going to be the LAST one made
    // from a window that no longer describes a page the core may speculate into. A
    // plain cacheable miss (`s0Fault` low, `s0Cacheable` high) never asserts it, so
    // the bubble-free hit/miss stream the frontier exists for is untouched.
    //
    // ── ACCEPTED RESIDUAL: CYCLE T, BOUNDED TO ONE CYCLE ────────────────────────
    // Cycle T is NOT closed, deliberately. Scope, exactly:
    //   * requires an accepted command whose live verdict is FAULT or INHIBITED,
    //   * ON that one cycle only (T+1 onward is now covered by this term, T+2 onward
    //     by the kill itself),
    //   * with `pfSeqValid` high and a candidate in range, and
    //   * to be a genuine stale-cacheability violation rather than a merely wasted
    //     prefetch, that command must be to the SAME 4-KiB page as the window
    //     (`pfWindowHasCandidate` pins every candidate to `pfDemandLine`'s page), i.e.
    //     it takes a live same-page mapping/cacheability TRANSITION under an open
    //     prefetch window -- an ATC flush or descriptor rewrite landing between the
    //     seed and this fetch.
    //   * Consequence when it does hit: at most ONE speculative line of that page is
    //     allocated and read over AXI.
    //
    // Why it is not closed: nothing registered on cycle T carries the verdict -- the
    // ITLB produces it combinationally that cycle. The closures that exist all fail:
    //   * `!(cmdPort.fire && (lookupFault || !lookupCacheable))` -- puts the live
    //     translation verdict back into the allocator's enable cone, which is the one
    //     arc M4 exists to delete (spec §5.4). Rejected on design intent.
    //   * `!cmdPort.fire`, or `!cmdPort.fire`-narrowed-to-same-page -- verdict-free
    //     and all-flop except the handshake, but a bubble-free same-page hit stream
    //     fires every cycle, so the frontier would never advance. It converts a
    //     1-cycle residual into a permanently dead prefetcher, and is caught by
    //     "bubble-free resident demand stream does not starve speculative
    //     allocation". Rejected on cost.
    //   * Allocate at T, then RETRACT the slot at T+2 (clear `mshrValid` for
    //     speculative entries with `!mshrArSent` when the kill lands) -- would work
    //     only with the AR-hold write ALSO suppressed at T+1 and T+2, i.e. a new MSHR
    //     lifecycle transition (and its deadlock surface) added for one cycle of
    //     coverage. It also would not change the exposure CLASS: a slot allocated at
    //     T-1 whose AR has already gone out is equally reading a page whose mapping
    //     has just changed, and no retraction can recall an AXI transaction. The
    //     residual is therefore the tail of a property this design cannot hold
    //     absolutely in either the parent or M4 form. Rejected on cost/benefit.
    //
    // NOW PROVEN AND PINNED (Task 13). It is no longer "accepted and unobserved": the
    // residual is REACHED and its bound MEASURED by `IcachePrefetchSpec`'s "P1 residual
    // (cycle T): a fetch whose LIVE verdict is FAULT allocates AT MOST ONE speculative
    // line, in its own page". That test seeds a real five-line window with the allocator
    // shut off, then opens the allocator and offers the faulting command in the SAME
    // simulation delta -- so the first cycle `pfWindowHasCandidate` can be true IS the
    // accept cycle, with nothing to calibrate -- and asserts BOTH directions: at most
    // one speculative AR (the safety bound) and exactly one (non-vacuity: a zero would
    // mean either the residual was not reached or it was closed, and in the latter case
    // THIS COMMENT is the thing that is now wrong). It also pins the containment claim
    // by asserting the AR's address is inside `pfDemandLine`'s own 4 KiB page.
    // Mutation-verified, not merely written: deleting `!s0KillsWindowQ` below re-opens
    // T+1 and the test fails with "RULE-P1 BOUND VIOLATED: 2 speculative ARs".
    // If you change the timing of the window kill, that test is the one that will tell
    // you; the record lives in `IcacheMutationProofSpec` under M6.
    val s0KillsWindowQ = s0Valid && !s0Replay && (s0Fault || !s0Cacheable)
    when(pfWindowHasCandidate && !anyInvalidate && !demandFillStart &&
         !pfWindowUpdate && !demandStuckQ && !s0KillsWindowQ) {
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
        mshrGen(pfFreeMshr)      := mshrGen(pfFreeMshr) + 1  // deadlock defense: fresh allocation
        mshrPa(pfFreeMshr)       := pfNextPa
        mshrSet(pfFreeMshr)      := pfCandSet
        mshrTag(pfFreeMshr)      := pfCandTag
        mshrWay(pfFreeMshr)      := victim(pfCandSet)
        pfNextPa                 := pfNextPa + U(64, 32 bits)
      }
    }

    // ══ M4 (Task 12) SAFETY NET: ONE FILL OWNER PER SET, AT THE PRODUCER ══════════
    // Task 12 moves the allocator's outer freeze (`!demandStuck` -> `!demandStuckQ`)
    // behind a register, which means the freeze no longer covers the S1 cycle of an
    // accepted-but-held demand miss. The claim that this is safe rests entirely on the
    // SET-SPECIFIC guards (`pfCandSetBusy` and `demandSetOwned`'s three lanes) still
    // being exact -- an argument, four cycles long, about signals none of which is
    // observable from a testbench. So it stops being an argument.
    //
    // This is the PRODUCER end of exactly the invariant Task 11's review fix watches at
    // the CONSUMER end (`CountOne(s1HitVec) <= 1`, above): two fill owners for one set
    // is how two ways of one set end up simultaneously valid carrying the same tag, and
    // the consumer assertion only fires once the second install has actually landed AND
    // a lookup has hit it. This one fires the cycle the allocator makes the mistake,
    // which is the difference between a diagnosable failure and a puzzle.
    //
    // REPO-WIDE, deliberately, and for the same reason the 2-hot net was restored to
    // being repo-wide: it runs under the 396-test lock-step suites, `make test-fast`,
    // the whole GC-4 list and the fuzz campaign -- not in one directed suite. Costs
    // nothing in synthesis (SpinalHDL `assert` is simulation-only here, same style as
    // the two-beat refill asserts and the `missPC` immutability check).
    //
    // NON-VACUITY: proved by mutation in the Task 12 report -- removing
    // `demandSetOwned`'s `cmdPort.fire` lane makes this assertion fire, and it fires
    // BEFORE the 2-hot consumer assertion does.
    for (i <- 0 until MSHR_N; j <- i + 1 until MSHR_N) {
      assert(!(mshrValid(i) && mshrValid(j) && (mshrSet(i) === mshrSet(j))),
        s"I-cache MSHR entries $i and $j are both live and own the SAME set -- " +
        s"'one fill owner per set' is violated. Two fills into one set can install two " +
        s"valid ways carrying the same tag, which makes s1HitVec 2-hot and silently ORs " +
        s"two ways' instruction bytes into one FetchRsp (spec risk R3).")
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
    //
    // M4: keyed on `heldSetQ`, the REGISTERED set of the refused demand, not on the live
    // `lookupSet`. This vector feeds `pfChosenArSel`, i.e. the AR PAYLOAD select mux and
    // the 37-flop AR hold's clock enable -- spec §5.4 names both as places live lookup
    // state must not reach. `heldSetQ` is exact wherever it is read (see its
    // declaration): it always carries the `lookupSet` of the demand that was refused on
    // the immediately preceding cycle.
    val pfBlockingArWant = Vec((0 until pfSlots).map(i =>
      pfArWant(i) && (mshrSet(i + AxiIds.I_SPEC_BASE) === heldSetQ)))
    val pfBlockingArAny = pfBlockingArWant.orR
    val pfBlockingArSel = OHToUInt(OHMasking.first(pfBlockingArWant.asBits))
    // M3b: `heldDemandMiss` -> `heldOnSetBusy` for the blocking-owner PRIORITY, and
    // `demandStuck` for the hold gate below. M4: both, registered.
    //
    // The priority selector deliberately uses `heldOnSetBusyQ` ALONE, not `demandStuckQ`,
    // and this is a DEVIATION from the plan's literal Step 5, which writes
    // `Mux(heldDemandMissQ, pfBlockingArSel, pfArSel)`. The plan was written against the
    // pre-Task-11 file, where ONE `heldDemandMiss` signal covered both cases; Task 11
    // split it, and substituting the (a)-only `heldDemandMissQ` here DELETES the
    // blocking-owner priority outright. It is not a tie-break preference but a named
    // anti-starvation property with its own directed test, and the mutation is caught:
    // with `Mux(heldDemandMissQ, ...)` the suite's "a held demand's blocking owner
    // outranks lower-numbered slots in the AR arbiter" FAILS, launching slot 1's line
    // instead of the blocking slot 3's. (Verified, not reasoned about -- Task 12 report.)
    //
    // Why case (a) needs no blocking-owner steering (Task 12 review M1 -- the earlier
    // wording here argued from `pfAcceptOk`, which describes the pre-M4 code and is not
    // what holds post-M4): neither reader of `pfBlockingArWant` is enabled under (a)
    // [`heldDemandMissQ`], because both are qualified by `heldOnSetBusyQ` -- the
    // priority Mux immediately below, and the AR-hold escape hatch further down
    // (`heldOnSetBusyQ && pfBlockingArAny`). Under (a) alone the vector is computed but
    // never consulted.
    val pfChosenArSel = Mux(heldOnSetBusyQ, pfBlockingArSel, pfArSel)
    val pfChosenArMshr = (pfChosenArSel.resize(mshrIdxBits) +
                          U(AxiIds.I_SPEC_BASE, mshrIdxBits bits)).resize(mshrIdxBits)
    // ── Per-AXI-id BUS-outstanding tracking (2026-09-08, p163 campaign) ──────────
    // The MSHR state is NOT a record of what is outstanding on the bus: an `anyInvalidate`
    // (CPUSHA/CINVA BC -- the ROM's _BlockMove executes one after every >12-byte copy)
    // that lands while a demand fill is in flight poisons the slot, the miss FSM leaves
    // it, and the next demand miss RE-ARMS the same slot (`mshrArSent := False`,
    // `mshrGen + 1`) and presents a NEW AR under the SAME AXI id while the old
    // transaction's R beats have not returned. Caught by AxiMemModel's protocol checker
    // ("AR id=1 presented while ALREADY outstanding") on the ExecuteLockStepSpec p163
    // BlockMove+CPUSHA tests. It is not cosmetic: the R consumer's stale filter is
    // `mshrArSentGen === mshrGen`, a generation that is NOT carried on the bus, so once
    // the new AR has fired the OLD response (same id, in order) matches and is installed
    // as the NEW line's data -- the core then executes another line's bytes. Whether the
    // SoC pairs it that way depends only on whether anything ahead of the L2's id-busy
    // CAM accepts the duplicate AR (a register slice does).
    //
    // Fix: track the bus transaction per id, set on AR fire and cleared on the id's
    // R.last, and never present an AR for an id that is still outstanding. The old
    // response then arrives while `mshrArSent` of the re-armed slot is still False and
    // takes the existing `staleDrain` path; the new AR goes out afterwards and its
    // response is the one consumed. Termination: an outstanding transaction always
    // completes (the slave owes its beats), so the gate can only delay, never block.
    val arOutstanding = Vec.fill(MSHR_N)(RegInit(False))
    arOutstanding.foreach(_.simPublic())
    when(axi.ar.fire) { arOutstanding(arHoldId.resize(mshrIdxBits)) := True }
    when(axi.r.fire && axi.r.payload.last) { arOutstanding(axi.r.payload.id.resize(mshrIdxBits)) := False }

    when(!arHoldValid) {
      when(refillActive && !mshrArSent(DEMAND_IDX) && !arOutstanding(DEMAND_IDX)) {
        arHoldValid := True
        arHoldId    := U(AxiIds.I_DEMAND, AxiIds.ID_W bits)
        arHoldAddr  := mshrPa(DEMAND_IDX) & ~U(63, 32 bits)
      // M4: `demandStuck` -> `demandStuckQ`, and the `pfBlockingArAny` escape hatch is
      // now QUALIFIED by `heldOnSetBusyQ`. Task 11 left it unqualified, so under case (a)
      // the hatch could be opened by a blocking-owner match computed against a younger,
      // unrelated command at the Stream boundary. Narrowing it is strictly more
      // conservative and cannot deadlock: under (a) the demand fill is dispatched from
      // S1 as soon as the FSM reaches IDLE (a bounded <= 3-cycle speculative install
      // dwell, which needs no AR of its own), and the demand's OWN AR is launched by the
      // higher-priority `refillActive` arm above, not by this one.
      } elsewhen(pfAnyArWant && !demandFillStart && !arOutstanding(pfChosenArMshr) &&
                 (!demandStuckQ || (heldOnSetBusyQ && pfBlockingArAny))) {
        arHoldValid := True
        arHoldId    := (pfChosenArSel.resize(AxiIds.ID_W) +
                        U(AxiIds.I_SPEC_BASE, AxiIds.ID_W bits)).resized
        arHoldAddr  := mshrPa(pfChosenArMshr) & ~U(63, 32 bits)
      }
    }

    axi.ar.valid         := arHoldValid
    axi.ar.payload.addr  := arHoldAddr
    axi.ar.payload.id    := arHoldId
    // ── BURST WIDTH: DELIBERATELY UNCHANGED, INCLUDING FOR AN INHIBITED FETCH ─────
    // The D side has an exact byte cover for INHIBITED accesses (`MmioCover`) because a
    // load names a specific 1/2/4-byte operand and an AXI4 READ carries no byte enable,
    // so any byte the read covers is a byte the device sees read. An earlier revision of
    // this fix mirrored that here, narrowing an inhibited demand fill to a single 32-byte
    // beat. That was REMOVED on purpose, and this comment exists so the next reader does
    // not helpfully re-add it:
    //
    //   - Instruction fetch is inherently bursty and stays bursty. There is no "named
    //     operand" to cover exactly: the architectural unit of a fetch is a stream, and
    //     `FetchRsp` delivers a fixed 64-bit window plus a predecode that needs the three
    //     words FOLLOWING each word it classifies. Narrowing below a whole beat leaves
    //     those lookahead words undefined INSIDE the beat, where nothing detects it
    //     (unlike the beat-END case, which `classify` marks `ambiguousLine` for
    //     `Aligner.scala:63-65` to redo) — silent instruction mis-framing.
    //   - The core cannot tell MMIO from any other cache-inhibited memory; it has only
    //     the page attribute. And no real program executes from MMIO. So a NON-speculative
    //     inhibited fetch is a pathological case that does not need to be made safe: once
    //     the speculation gate above is in place, any inhibited fetch that still reaches
    //     the bus was architecturally demanded by the program, and the burst is then the
    //     program's problem rather than the core's.
    //   - Equivalently: the I-cache does not honour INHIBITED as a CACHING policy, and
    //     that is intended. It honours it as a SPECULATION policy, which is the property
    //     that actually protects devices.
    axi.ar.payload.len   := U(1, 8 bits)
    axi.ar.payload.size  := U(5, 3 bits)
    axi.ar.payload.burst := Axi4.burst.INCR
    // M2c: the AR id IS the MSHR index, so the demand/speculative branch is gone.
    when(axi.ar.fire) {
      arHoldValid := False
      mshrArSent(arHoldId.resize(mshrIdxBits)) := True
      // deadlock defense: record which generation THIS AR belongs to.
      mshrArSentGen(arHoldId.resize(mshrIdxBits)) := mshrGen(arHoldId.resize(mshrIdxBits))
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
    // deadlock defense (BUG_calibration_word_misplaced_0d00.md Parts 53-61): the
    // response's armed AR must belong to the SAME generation the slot is CURRENTLY on.
    // See `mshrGen`/`mshrArSentGen`'s declaration above for the full rationale. In the
    // natural (non-poked) RTL this is exactly redundant with `mshrArSent` -- both flip
    // together at allocate (gen bumps, arSent clears) and together at AR-fire (arSentGen
    // latches, arSent sets) -- so this term changes NOTHING on any path this design can
    // reach today; it only starts discriminating if some future bug (or an as-yet-
    // unattributed real SoC race) ever lets a slot's generation advance without its
    // `mshrArSent` bookkeeping tracking it, which is exactly the shape Part 57's
    // hardware capture inferred (three live slots each one generation ahead of the
    // last AR they actually sent).
    val genMatch = mshrArSentGen(rIdx) === mshrGen(rIdx)
    val demandRspMatch = ridIsDemand && refillActive && mshrArSent(DEMAND_IDX) && genMatch
    val pfRspMatch = ridIsPf && mshrValid(rIdx) && mshrArSent(rIdx) && !mshrComplete(rIdx) && genMatch

    // deadlock defense, THE actual fix for the proven circular wait (Part 56 S8): a
    // response landing on a RID this port itself owns (`ridIsDemand || ridIsPf` -- this
    // is the I-cache's own dedicated AXI master port, so every legal `axi.r.valid` beat
    // is one of these two ranges by construction) that is NOT a genuine match for the
    // CURRENTLY-armed occupant is, by elimination, a beat belonging to some OLDER,
    // already-superseded generation of that same slot -- an orphan the fabric still owes
    // a response for. Before this fix `axi.r.ready` could NEVER assert for such a beat
    // (Part 56 S8 step 1): it would sit at L2C's single shared output register forever,
    // jamming L2C's front door and permanently blocking the CURRENT occupant's own AR
    // from ever being accepted (steps 2-5 of that same proof) -- a real, hardware-
    // reproduced deadlock (13 investigation rounds, Parts 53-60).
    //
    // Precedent: `AxiReadResetAbsorber` already establishes this project's pattern for
    // "accept and discard a response nobody has a live claim on, so the bus does not
    // wedge" -- there, scoped to the window after a debug reset; here, scoped
    // continuously, per-ID, off the exact same-shaped "is there a live owner" gate this
    // file already computes. `staleDrain` asserts `axi.r.ready` for exactly this case
    // WITHOUT touching `rOwned`/`rFire` below, so the beat is consumed off the wire
    // (unblocking the fabric) but never written into `fillLo`/`fillHi` and never
    // credited toward `mshrBeat`/`mshrComplete` for the current occupant -- the slot's
    // real, current request is completely unaffected and can still complete normally
    // once its own AR is actually accepted and its own response actually arrives.
    val demandStaleRsp = ridIsDemand && !demandRspMatch
    val pfStaleRsp      = ridIsPf && !pfRspMatch
    val staleDrain = demandStaleRsp || pfStaleRsp
    axi.r.ready := demandRspMatch || pfRspMatch || staleDrain

    // Debug/verification-only: count drained stale beats (never read by any
    // synthesizable logic -- simPublic() for `IcacheIdReuseWhiteboxSpec`'s fix-side
    // assertions and any future dbg040 tap in the SoC-side port of this fix).
    val staleDrainCount = Reg(UInt(8 bits)) init 0
    when(axi.r.valid && axi.r.ready && staleDrain && staleDrainCount =/= U(255, 8 bits)) {
      staleDrainCount := staleDrainCount + 1
    }
    staleDrainCount.simPublic()

    when(axi.r.valid) {
      // Widened from "must be a live-owned beat" to "must be a RECOGNIZED-ID beat" now
      // that `staleDrain` legitimately accepts a recognized-but-stale-generation one --
      // a beat on neither range would still be a genuine "no live RID owner" protocol
      // violation (this port's own AXI ID space is exhaustively `ridIsDemand ||
      // ridIsPf`), so the assertion keeps exactly its original fault-catching power for
      // that case.
      assert(ridIsDemand || ridIsPf, "I-cache R beat has no live RID owner")
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
      // Generalised from the two identical per-path assertions M2b carried. Every I-side
      // fill is two beats, INHIBITED included — see the AR payload comment for why the
      // burst is deliberately not narrowed for a cache-inhibited fetch.
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
              // allocation does not depend on it. Task #251: sim-only, see `pfFilled`'s
              // declaration.
              GenerationFlags.simulation {
                pfFilled(w)(installSet) := predIsPf && !anyInvalidate
              }
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
