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
  private val pfIdxBits    = log2Up(pfSlots)

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
    val PRED_BITS_PER_LINE = PRED_BITS_PER_WORD * 32
    // Slice I2 (per-beat predecode): one AXI beat is 32 bytes = 16 words of a 32-word
    // line, so the single classify group is 16 wide and is used TWICE per refill.
    val WORDS_PER_BEAT     = 16
    val PRED_BITS_PER_BEAT = PRED_BITS_PER_WORD * WORDS_PER_BEAT
    val predMem = Seq.fill(ways)(Mem(Bits(PRED_BITS_PER_LINE bits), sets))
    // Data: synchronous-read BRAM. 2 beats x 64 sets = 128 entries per way.
    val dataMem = Seq.fill(ways)(Mem(Bits(256 bits), sets * beatsPerLine))
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
    for (w <- 0 until ways) { tagMem(w).simPublic(); predMem(w).simPublic(); dataMem(w).simPublic() }
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

    // ---- miss-state latches ----
    val missPC    = Reg(UInt(32 bits))
    val missPA    = Reg(UInt(32 bits))   // translated physical line address (refill AXI)
    val missSet   = Reg(UInt(setBits bits))
    val missTag   = Reg(UInt(tagBits bits))
    // Cacheability of the access that caused this miss, latched at miss-detect time
    // (same instant missPA/missSet/missTag are latched) so REFILL/PREDECODE's
    // allocate gate sees the SAME cache-mode the missing fetch actually had.
    val missCacheable = Reg(Bool())
    val victimWay = Reg(UInt(wayBits bits))
    val beatCnt   = Reg(UInt(1 bits)) init U(0, 1 bits)
    val arSent    = Reg(Bool()) init False
    val lineReg   = Reg(Bits(512 bits))
    // Task icache-burst-fault-fix: PREDECODE's dataMem commit-beat phase — which
    // half of `lineReg` (and which dataMem address) the SINGLE write-port commit is
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
    val missBusFault = Reg(Bool()) init False
    // Task icache-corruption-fix (review of 69a867c, "icache: wire xlate.rsp.cacheMode
    // into IcachePlugin"): that commit correctly gated tagMem/valids/victim-advance
    // under doAllocate for an INHIBITED-mode miss, but left the ACTUAL dataMem/predMem
    // writes to the shared per-way arrays unconditional. The round-robin `victim`
    // pointer is the SAME pointer used by cacheable and INHIBITED misses alike — once
    // a set has taken >=4 real allocations it cycles back onto a way that is still
    // VALID and resident for some other, unrelated address. An unconditional write
    // there silently corrupts that other way's data/pred while its tag/valid stay
    // untouched (still claiming the OLD address is validly resident) -> the next
    // ordinary fetch to that address silently returns the WRONG bytes. Fix mirrors
    // DcachePlugin's `missLine`/`inhibitedResp` direct-delivery pattern exactly:
    // dataMem's write is now gated (REFILL, below) and `lineReg` (already latched
    // UNCONDITIONALLY every REFILL beat) doubles as the data-side direct-delivery
    // source; `missPred` is the NEW predecode-side counterpart, latched UNCONDITIONALLY
    // in PREDECODE below so REPLAY can deliver an INHIBITED line's predecode straight
    // from this register, entirely bypassing the (for that case, never-written) predMem
    // array.
    val missPred = Reg(Bits(PRED_BITS_PER_LINE bits))
    // Slice I2: the low beat's predecode result, produced on PREDECODE's commitBeat==0
    // cycle and held for one cycle so commitBeat==1 can assemble the whole-line packed
    // value from {this, the high beat's freshly-classified result}. See PREDECODE.
    val predAccumLo = Reg(Bits(PRED_BITS_PER_BEAT bits))

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
    val missPoison = RegInit(False)

    // ---- five-ID stream-prefetch pool (IDs 1..4; ID 0 remains demand) ----
    // Each speculative slot carries only control. The two 256-bit beats live in
    // shallow ID-indexed memories and are copied into the existing shared `lineReg`
    // only when that completed slot wins the installer. This avoids four additional
    // 512-bit register copies and keeps the 16-instance predecoder single-copy.
    val pfValid    = Vec.fill(pfSlots)(RegInit(False))
    val pfArSent   = Vec.fill(pfSlots)(RegInit(False))
    val pfComplete = Vec.fill(pfSlots)(RegInit(False))
    val pfBeat     = Vec.fill(pfSlots)(RegInit(False))
    val pfErr      = Vec.fill(pfSlots)(RegInit(False))
    val pfPoison   = Vec.fill(pfSlots)(RegInit(False))
    val pfPa       = Vec.fill(pfSlots)(Reg(UInt(32 bits)))
    val pfSet      = Vec.fill(pfSlots)(Reg(UInt(setBits bits)))
    val pfTag      = Vec.fill(pfSlots)(Reg(UInt(tagBits bits)))
    val pfWay      = Vec.fill(pfSlots)(Reg(UInt(wayBits bits)))
    val pfLineLo   = Mem(Bits(256 bits), pfSlots)
    val pfLineHi   = Mem(Bits(256 bits), pfSlots)
    val pfInstallIdx = Reg(UInt(pfIdxBits bits))

    // Registered sequential frontier. It is seeded only by an accepted, resolved,
    // cacheable demand and is capped at five same-page lines ahead. Installed lines
    // free speculative slots before demand reaches them, allowing four physical
    // speculative slots to maintain the five-line logical lookahead.
    val pfSeqValid   = RegInit(False)
    val pfDemandLine = Reg(UInt(32 bits))
    val pfNextPa     = Reg(UInt(32 bits))
    val pfLimitPa    = Reg(UInt(32 bits))
    // Test visibility only (zero synthesis cost -- these are already real registers):
    // the window-clobber regression test (`IcachePrefetchSpec`, "a non-sequential
    // demand that HITS ...") asserts the EXACT frontier triple cycle-by-cycle, which
    // is the only way to distinguish "the seed survived" from "the allocator's stale
    // advance overwrote it" -- the two differ solely in this register's value.
    pfSeqValid.simPublic(); pfDemandLine.simPublic()
    pfNextPa.simPublic(); pfLimitPa.simPublic()

    pfValid.simPublic()
    pfArSent.simPublic()
    pfComplete.simPublic()

    // ---- shared data-array read port (synchronous; BRAM) ----
    // Address+enable are driven by the FSM (IDLE hit accept, or REPLAY). The
    // result `dataBeat` is the registered BRAM output, valid the NEXT cycle.
    val dataReadAddr = UInt((setBits + 1) bits)
    val dataReadEn   = Bool()
    dataReadEn.simPublic()
    val xlateReadyDbg = Bool()
    xlateReadyDbg := xlate.rsp.ready
    xlateReadyDbg.simPublic()
    dataReadAddr := U(0, (setBits + 1) bits)
    dataReadEn   := False
    val dataBeat = Vec(dataMem.map(_.readSync(dataReadAddr, dataReadEn)))

    // ---- S1 (response-build) pipeline registers ----
    // The cycle after a read is launched, dataBeat is ready: mux by the latched
    // hit-way, lane-select, and register into the rsp output stage below.
    val s1Valid = Reg(Bool()) init False
    val s1Way   = Reg(UInt(wayBits bits))
    val s1Pc    = Reg(UInt(32 bits))
    val s1Fault = Reg(Bool())
    // Task #211: which cause armed s1Fault — True = ITLB/MMU translation fault,
    // False = a physical AXI bus error caught in REFILL (new FAULT
    // state below). Only meaningful when s1Fault is set; rides to rsp.payload.atc.
    val s1Atc   = Reg(Bool())
    val s1Lane  = Reg(UInt(2 bits))
    // FMax: register the RAW per-way predMem entries (the readAsync results, indexed
    // by the page-invariant set bits — NOT a hit-way select), and defer the way-mux +
    // window-decode to the S1->rsp stage keyed off the REGISTERED s1Way. This mirrors
    // the data path (s1Beat = dataBeat(s1Way)) and keeps the hitWayIdx/predMem way-mux
    // + windowPred OUT of the IDLE consume cone (which was the route-dominated arc into
    // s1Pred). A fault placeholder registers all-zero entries (windowPred of zero = a
    // zeroed predecode, matching the old getZero placeholder).
    val s1PredEntries = Reg(Vec(Bits(PRED_BITS_PER_LINE bits), ways))
    // Task icache-corruption-fix: True only for a REPLAY of a non-allocated
    // (INHIBITED-mode) miss — routes the S1->rsp mux below to deliver straight from
    // the `lineReg`/`missPred` bypass registers instead of `dataBeat`/`s1PredEntries`
    // (the shared arrays, which were never written for that case). Explicitly set at
    // EVERY s1Valid-arming site (mirrors s1Fault/s1Atc), never left to a stale value.
    val s1FromMiss = Reg(Bool())
    s1Valid := False   // default each cycle; armed in IDLE-hit / REPLAY below

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
      * dataMem commit, see below), so this is a **2x instance cut at exactly today's
      * latency** — no extra cycle anywhere.
      *
      * DEVIATION FROM THE PLAN, RECORDED DELIBERATELY: the ratified plan's I2 section
      * computes a 4x cut (32 -> 8) and a ~-190-flop `lineReg` deletion. Both of those
      * numbers assume slice V2b (narrow the I-cache AXI master 256 -> 128 bits, so a
      * beat is 8 words and a line is 4 beats) has landed first. V2b is socket-
      * conformance work and is OUT OF SCOPE for the IPC-push initiative this landed
      * under, so the recomputed figures for today's 256-bit / 2-beat geometry are:
      * **32 -> 16 instances (2x, the design doc's original I-b number)** and `lineReg`
      * RETAINED. `lineReg` cannot be deleted at 2 beats/line: the icache-burst-fault
      * fix requires the dataMem commit to be DEFERRED until the whole burst's pass/fail
      * is known, so both beats must still be held somewhere, and it doubles as the
      * INHIBITED direct-delivery source (`missDataBeat`). The only added state is
      * `predAccumLo` (one beat's worth of predecode, held one cycle).
      *
      * `beat`      : the beat being classified (a half of `lineReg`).
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

    def windowPred(entry: Bits, pc: UInt): Vec[ChunkPredecode] = {
      val win  = entry.subdivideIn(4 * PRED_BITS_PER_WORD bits)(pc(5 downto 3))
      val nibs = win.subdivideIn(PRED_BITS_PER_WORD bits)
      Vec(nibs.map(b => b.as(ChunkPredecode())))
    }

    // ---- S1 -> rsp output register (runs every cycle; meaningful when s1Valid) ----
    // Way-mux the registered raw beats/pred by s1Way, then window-decode — all off
    // REGISTERED state (s1Way/s1Lane/s1Pc), so neither the data nor the pred select
    // is in the IDLE hit cone.
    // Task icache-corruption-fix: bypass mux — for a REPLAY of a non-allocated
    // (INHIBITED) miss (s1FromMiss), deliver directly from the miss-latch registers
    // (mirrors DcachePlugin's inhibitedResp/missLine) instead of the shared arrays,
    // which were never written for that line. `lineReg` is 2 beats (512b); select
    // the same half REPLAY's array-based `replayBeatSel` would have (s1Pc(5), s1Pc
    // already holds missPC here).
    val missDataBeat = Mux(s1Pc(5), lineReg(511 downto 256), lineReg(255 downto 0))
    val s1Beat   = Mux(s1FromMiss, missDataBeat, dataBeat(s1Way))
    val s1Window = s1Beat.subdivideIn(64 bits)(s1Lane)
    val s1PredW  = Mux(s1FromMiss, windowPred(missPred, s1Pc), windowPred(s1PredEntries(s1Way), s1Pc))
    rspValidReg := s1Valid
    rspPcReg    := s1Pc
    rspDataReg  := s1Window
    rspFaultReg := s1Fault
    rspAtcReg   := s1Atc
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
    val hitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      hitVec(w) := lookupCacheable && valids(w)(lookupSet) &&
                   (tagMem(w).readAsync(lookupSet) === lookupTag)
    val isHit       = hitVec.orR
    val hitWayIdx   = OHToUInt(hitVec)
    val lookupBeatSel   = lookupPc(5)
    val lookupLaneIdx   = lookupPc(4 downto 3)
    val lookupReadAddr  = (lookupSet ## lookupBeatSel).asUInt
    val lookupPredEntry = Vec(predMem.map(_.readAsync(lookupSet)))
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
    val predIsPf          = Bool(); predIsPf          := False
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
    // Slice 1c review round 1 (FIX 2): non-fatal telemetry replacing two sim-fatal
    // asserts on conditions the RTL deliberately permits (see the Slice 1c block
    // further down for the full benign-outcome analysis of each). Same zero-cost
    // idiom as `pfHitUseful` above: pure wires, no flops, no synthesis footprint.
    //   - `pfAllocSetCollide`: the allocator granted a speculative slot for the exact
    //     set a demand miss is claiming on this very cycle. Outcome: one wasted
    //     self-evicting fill (both owners latch the same `victim(S)`, installs are
    //     FSM-serialised).
    //   - `pfArDemandRace`: a speculative want claimed `arHoldValid` on the exact
    //     cycle a demand fill started. Outcome: the demand's AR is delayed by one AR
    //     handshake (`arHoldValid` clears at `axi.ar.fire`).
    val pfAllocSetCollide = Bool(); pfAllocSetCollide := False; pfAllocSetCollide.simPublic()
    val pfArDemandRace    = Bool(); pfArDemandRace    := False; pfArDemandRace.simPublic()

    // ── Five-line registered stream window (binding design 2026-08-10) ────────
    // The frontier and candidate are registers, preserving the earlier FMax lesson:
    // never put live translation + increment + async tag lookup + allocation enables
    // in one cone. Candidates use the already-resolved same-page PPN, never an ITLB
    // request, and stop at the 4-KiB boundary (rules P1/P4).
    val pfCandSet = pfNextPa(11 downto 6)
    val pfCandTag = pfNextPa(31 downto 12)
    val pfCandResident = Vec((0 until ways).map(w =>
      valids(w)(pfCandSet) && (tagMem(w).readAsync(pfCandSet) === pfCandTag))).orR
    val pfCandLive = Vec((0 until pfSlots).map(i =>
      pfValid(i) && ((pfPa(i) & ~U(63, 32 bits)) === pfNextPa))).orR
    val pfCandSetBusy = Vec((0 until pfSlots).map(i =>
      pfValid(i) && (pfSet(i) === pfCandSet))).orR
    val pfFreeVec = Vec((0 until pfSlots).map(i => !pfValid(i)))
    val pfHasFree = pfFreeVec.orR
    val pfFreeIdx = OHToUInt(OHMasking.first(pfFreeVec.asBits))
    val pfWindowHasCandidate = pfSeqValid && prefetchEnable &&
      (pfNextPa <= pfLimitPa) && (pfNextPa(31 downto 12) === pfDemandLine(31 downto 12))

    // A held demand matching any live speculative line must re-look-up after install;
    // a same-set/different-line demand waits as well, preserving one fill owner per set.
    val lookupLineBase = lookupPaddr & ~U(63, 32 bits)
    val pfLookupSetBusy = Vec((0 until pfSlots).map(i =>
      pfValid(i) && (pfSet(i) === lookupSet))).orR

    val pfInstallVec = Vec((0 until pfSlots).map(i =>
      pfValid(i) && pfComplete(i) && !pfErr(i) && !pfPoison(i)))
    val pfInstallAny = pfInstallVec.orR
    val pfInstallSel = OHToUInt(OHMasking.first(pfInstallVec.asBits))

    // ── Slice 1a (design spec section 3 boundary B3): the install decision must not
    // be a function of the LIVE demand verdict. Today `lineReg`'s 512-bit capture
    // enable and the fanout-518 `pfInstallIdx` select are reached from
    // `cmdPort.fire` through `demandFillStart`, which is what puts the whole
    // `applyNow -> ITLB -> tag -> accept -> install` chain into one cycle.
    //
    // The inversion: arm the install from a REGISTER, and make the demand-miss
    // capture yield to it instead. Nothing architectural depends on an install
    // happening in any particular cycle - a completed speculative line is
    // speculative by construction (handoff section 15 step 9's standing licence).
    //
    // H9 anti-starvation. `installStarves` is DIAGNOSTIC ONLY -- it does NOT gate
    // arming, and must not be made to again (see below). The property it used to
    // enforce is instead structural, and strictly stronger:
    //
    //   the allocation site (`!heldDemandMissReg`, in the Slice 1c gate further
    //   down) freezes ALL new speculative allocation for the entire duration any
    //   demand is held. So while a demand stays held, the speculative pool can only
    //   ever drain -- at most `pfSlots` slots exist and each installs exactly once,
    //   bounding the held demand's wait to `pfSlots` install episodes.
    //
    // Gating the ARM on `installStarves` (as this code used to do) is not merely
    // redundant against that bound -- it is a live deadlock, because installing an
    // already-COMPLETE slot is precisely what RELIEVES a demand's hold:
    //   1. demand B is held because `pfLookupSetBusy` matches a speculative slot S
    //      (`:538-539` tests `pfValid(i)` only -- a complete-but-uninstalled slot
    //      still asserts it);
    //   2. other slots install while B waits, driving `installDeferCnt` to the
    //      threshold and latching `installStarves`;
    //   3. S then completes. With `!installStarves` on the arm, S can never install,
    //      so `pfValid(S)` never clears, so `pfLookupSetBusy` never clears, so B is
    //      never admitted, so `heldDemandMiss` never falls -- and the counter neither
    //      resets (needs `!heldDemandMiss`) nor increments (needs
    //      `pfInstallArm || predActive`, both now permanently false), so
    //      `installStarves` stays latched forever. Closed loop, no internal escape:
    //      the AR-demotion path acts only on slots that have not yet sent their AR
    //      (a complete slot has none left to demote), and the only `pfValid` clear
    //      independent of the arm is the poison/error cleanup, which needs an
    //      EXTERNAL trigger (`anyInvalidate` pulse or a bus error).
    // Letting every complete slot take its install turn is therefore the fix, not the
    // hazard. `installDeferMax` is retained purely as the telemetry threshold below.
    val installDeferMax = 4
    // Worst case for one CONTINUOUS hold: every slot in the pool installs before the
    // held demand's own turn, and each install episode contributes exactly 3 counted
    // cycles (1 `pfInstallArm` arm-decision cycle in IDLE + the 2 `PF_PRED` dwell
    // cycles, during which `pfInstallArm` is itself masked low by `!predActive`).
    // `heldDemandMiss` requires `lookupActive`, which is raised ONLY by `lookupTick`
    // in IDLE and PF_PRED -- REFILL/PREDECODE/REPLAY/FAULT do not call it -- so a
    // demand-side PREDECODE can never contribute counts (it drives the counter's
    // `!heldDemandMiss` reset instead). Derived from `pfSlots` rather than written as
    // a literal so it cannot silently drift if the pool is ever resized.
    val installDeferBound = pfSlots * 3
    val installDeferCntW  = log2Up(installDeferBound + 2)
    val installDeferCnt = Reg(UInt(installDeferCntW bits)) init 0
    installDeferCnt.simPublic()
    val installStarves  = installDeferCnt >= U(installDeferMax, installDeferCntW bits)
    installStarves.simPublic()
    // `&& !predActive` (implementation correction over the literal design-spec
    // snippet, same file, same interface): `pfInstallVec`/`pfInstallAny` read
    // `pfValid`/`pfComplete`, which only clear at the FSM edge that ends PF_PRED
    // (`commitBeat === 1`). A bare `RegNext(pfInstallAny && !installStarves)` samples
    // `pfInstallAny` COMBINATIONALLY on that very same last PF_PRED cycle -- one
    // cycle BEFORE the clear takes effect -- so it latches "true" one cycle too late
    // and re-arms IDLE for a phantom second install of an already-freed slot the
    // instant control returns to IDLE (proven live: `installDeferCnt` free-runs
    // through TWO back-to-back PF_PRED dwells for a single completed slot and trips
    // this very oracle at count 6, see task-4-report.md evidence trace). Gating the
    // register's input with `!predActive` (true for both PREDECODE and PF_PRED)
    // stops it from latching off that stale, about-to-be-cleared reading; the
    // earliest a fresh arm can now form is the FIRST genuinely idle cycle after
    // control returns to IDLE, which is exactly the 1-cycle RegNext latency the
    // interface contract (`pfInstallArm` = "a register") already implies.
    //
    // `&& !anyInvalidate` (second implementation correction, review round 1
    // finding 1): `!predActive` closes the CONSUME path (`pfValid`/`pfComplete`
    // clearing at PF_PRED's own commit) but not the POISON path. `anyInvalidate`
    // can be a single-cycle pulse, and `when(anyInvalidate && pfValid(i)) {
    // pfPoison(i) := True }` (below) drops every slot out of `pfInstallVec`
    // (`pfInstallVec` excludes `pfPoison`) only on the cycle AFTER the pulse --
    // one cycle later than `pfInstallArm`'s own register would otherwise reflect
    // it, since `pfInstallArm` samples `pfInstallAny` BEFORE the poison write
    // lands. Without this term: cycle N a slot is complete-and-clean (armable)
    // and `anyInvalidate` pulses; `pfInstallArm` latches true for N+1 regardless.
    // At N+1 `pfInstallAny` is correctly now 0 (poisoned), but IDLE still sees
    // `pfInstallArm == 1` and fires a full install using `pfInstallSel =
    // OHToUInt(OHMasking.first(all-zero)) = 0` -- a PHANTOM install of slot 0
    // regardless of whether slot 0 was ever the armed one. If slot 0 happens to
    // be free-and-clean, that phantom install re-validates a stale tag/line one
    // cycle after the very invalidateAll meant to invalidate it (missPoison :=
    // pfPoison(0) || anyInvalidate reads False -- pfPoison(0) hasn't been set
    // for THIS slot, and the anyInvalidate pulse has already passed). If slot 0
    // is live with an outstanding AR instead, the phantom PF_PRED clears
    // `pfValid(0)`/`pfArSent(0)` out from under it, so its real R beats later
    // fail `pfRspMatch` and wedge the R channel against the
    // "I-cache R beat has no live RID owner" assert. An invalidate arriving
    // DURING the already-armed cycle is unaffected and stays safe on its own
    // (`missPoison := pfPoison(sel) || anyInvalidate` is live that cycle); this
    // term only closes the ONE-CYCLE-STALE-ARM-VS-FRESH-POISON gap.
    val pfInstallArm    = RegNext(pfInstallAny && !predActive && !anyInvalidate) init False
    pfInstallArm.simPublic()

    // H9 counter update (implementation correction over the literal design-spec
    // snippet, same file, same interface): gating purely on `heldDemandMiss` over-
    // counts. `heldDemandMiss` is also true for the PRE-EXISTING, unrelated,
    // legitimately-unbounded hold documented at its own definition above ("An
    // architectural miss can remain visibly held while a speculative owner or the
    // shared installer drains") -- e.g. waiting on a same-set fill that is still
    // AR_PENDING/R0/R1 (`pfLookupSetBusy`, no install anywhere near armed yet), which
    // can legitimately run for the line's full AXI service time. Counting THAT
    // toward H9's bound trips the oracle below on pre-existing, correct traffic
    // (proven live: `IcachePrefetchSpec`'s "AR-pending demotion" and "silent refill
    // error" tests, which deliberately hold a demand behind a still-in-flight
    // silent fill for far longer than `installDeferMax`, both trip it). H9 is
    // specifically about yielding to an ARMED (or actively installing) slot, so the
    // counter must count only that: `pfInstallArm` covers the IDLE arm-decision
    // cycle, `predActive` covers the two PF_PRED cycles that follow it (during which
    // `pfInstallArm` itself is masked low by the `!predActive` guard above).
    //
    // The RESET, however, stays keyed on `!heldDemandMiss` alone (review round 1
    // finding 2), not on the same `(pfInstallArm || predActive)` term as the
    // increment: the maximum contiguous `(pfInstallArm || predActive)` window is
    // 3 cycles (1 arm + 2 PF_PRED dwell), after which the `!predActive` guard on
    // `pfInstallArm` forces one genuinely-idle gap cycle before the NEXT slot (if
    // any) can arm -- resetting on that gap cycle would zero the counter every
    // single episode, making `installStarves` (`cnt >= installDeferMax`)
    // permanently unreachable and degrading H9 from a CUMULATIVE-defer detector
    // (the chained "install, gap, install, ..." scenario the design rationale
    // above names, "~15 cycles") to a per-episode one that can never actually
    // starve. Resetting only when the demand is no longer held (fired, or never
    // was) lets the count carry across gap cycles within one continuous hold.
    when(heldDemandMiss) {
      when(pfInstallArm || predActive) {
        when(installDeferCnt =/= installDeferCnt.maxValue) { installDeferCnt := installDeferCnt + 1 }
      }
    } otherwise {
      installDeferCnt := 0
    }

    // Design spec section 12.1 oracle 4: a demand miss held by an armed install must
    // be admitted within the bounded wait. Simulation-only; `GenerationFlags.simulation`
    // is the house pattern for keeping an assert out of the synthesised netlist.
    // The bound is `pfSlots` install episodes x 3 counted cycles each, NOT the old
    // `installDeferMax + 1`: that literal only held while `installStarves` hard-gated
    // arming (which capped the count at the threshold by construction, and deadlocked
    // -- see `installDeferMax` above). With the gate gone the counter legitimately
    // runs to the full drain of the frozen pool, so asserting 5 here would convert the
    // removed deadlock into a spurious failure. The counter saturates one short of
    // `maxValue` above `installDeferBound`, so this assert stays live rather than
    // being masked by wraparound.
    //
    // The bound is EXACTLY tight and that is INTENTIONAL (review round 1 minor): the
    // maximum reachable count is `pfSlots * 3` = 12, which is precisely
    // `installDeferBound`, and the comparison is `<=`, so a legal full-pool drain
    // passes with zero margin and ANY thirteenth counted cycle fails. Padding it
    // would only buy slack against a mechanism that does not exist -- there is no
    // source of a 13th cycle other than a genuine H9 violation, because the count
    // is gated on `pfInstallArm || predActive` (1 arm + 2 PF_PRED per episode) and
    // `pfSlots` is the hard ceiling on episodes within one continuous hold.
    GenerationFlags.simulation {
      assert(installDeferCnt <= U(installDeferBound, installDeferCntW bits),
        "H9 bounded-wait violated: a demand miss was deferred behind installs for too long")
    }

    // ── Slice 1c (design spec section 3 boundary B3): the speculative allocator and
    // the AR arbiter are the last two consumers of the LIVE demand verdict. Both are
    // pure speculation control - a slot allocated or an AR launched one cycle late
    // costs at most one cycle of prefetch earliness against a ~70-78 cycle line
    // service, and neither can change an architectural outcome (the demand refill's
    // own AR is launched from `refillActive && !arSent`, which is registered FSM
    // state and is NOT touched here).
    val heldDemandMissReg  = RegNext(heldDemandMiss)  init False
    heldDemandMissReg.simPublic()

    // ── Slice 1c, review round 1 (FIX 1, Critical): `pfWindowUpdate` is NOT
    // registered, and `demandFillStart` is not mirrored at all. Both mirrors were
    // written in the first pass and both were wrong, for two DIFFERENT reasons:
    //
    //   `pfWindowUpdateReg` (removed -- it was actively CORRUPTING the window).
    //   `pfWindowUpdate`'s declaration states the invariant: a lookup that
    //   RESETS/KILLS the frontier OWNS the allocator cycle. Slice 1b made the
    //   window rewrite happen one cycle after the accept (`seedValidReg`), so the
    //   owning cycle is the `seedPfWindow()` cycle -- exactly the cycle on which
    //   `pfWindowUpdate` is live-high. Gating the allocator on the REGISTERED
    //   mirror blocks the cycle AFTER the rewrite (where nothing needs blocking)
    //   and leaves the rewrite cycle itself wide open: `seedPfWindow()` writes
    //   `pfNextPa := line + 64` and the allocator, elaborated LATER in this same
    //   file, writes `pfNextPa := pfNextPa + 64` -- last assignment wins, so the
    //   stale advance silently overwrote the fresh seed. `pfDemandLine`/`pfLimitPa`
    //   move to the new stream while `pfNextPa` keeps the abandoned frontier: the
    //   prefetcher then either fetches lines of the abandoned stream (waste plus
    //   evictions) or, in the common cross-page redirect, goes permanently dead
    //   (`pfWindowHasCandidate` requires `pfNextPa`'s page to equal
    //   `pfDemandLine`'s) until the next non-sequential demand happens to reseed
    //   it. A demand MISS is accidentally protected (`demandFillStartReg` was true
    //   at accept+1) and so is an INHIBITED accept (`pfSeqValid := False`), but an
    //   ordinary taken branch into ALREADY-CACHED code -- a non-sequential demand
    //   that HITS -- is fully exposed. Covered by `IcachePrefetchSpec`'s "a
    //   non-sequential demand that HITS re-seeds the prefetch frontier" test,
    //   which asserts the exact frontier triple at accept+2.
    //   And the registration bought ZERO timing: after slice 1b `pfWindowUpdate` is
    //   derived purely from registers (`seedValidReg`, `seedCacheableReg`,
    //   `seedPaddrReg`, `pfSeqValid`, `pfDemandLine`) and was never in the live
    //   `xlate.rsp`/`isHit` verdict cone this slice exists to cut. Pure loss.
    //
    //   `demandFillStartReg` (removed -- it was a no-op guard). At accept+1 the FSM
    //   is unconditionally in REFILL (`demandFillStart` and `goto(REFILL)` are the
    //   same statement), so:
    //     - AR arbiter: `refillActive` is true and `arSent` was just cleared at
    //       capture, so the `refillActive && !arSent` branch ABOVE the speculative
    //       `elsewhen` always wins; the term was unreachable.
    //     - allocator: `demandSetOwned` (REFILL/PREDECODE/REPLAY/FAULT with
    //       `missSet === pfCandSet`) already blocks the only set that matters, so
    //       the term only cost one cycle of prefetch earliness for candidates that
    //       could never have conflicted.
    //
    // What the two mirrors were nominally protecting against is real but BENIGN,
    // and is now recorded as non-fatal telemetry (`pfAllocSetCollide` /
    // `pfArDemandRace` below) rather than as sim-fatal asserts on conditions this
    // RTL deliberately permits:
    //   (a) allocator, on the ACCEPT cycle itself (neither mirror ever covered it):
    //       a candidate that VIPT-aliases onto the exact set the demand is claiming
    //       can be granted a slot. Both owners latch the same `victim(S)` and the
    //       installs are FSM-serialised, so the outcome is one wasted self-evicting
    //       fill -- no corruption, no lost line.
    //   (b) AR arbiter, on the accept cycle: a fresh speculative want can claim
    //       `arHoldValid` before `refillActive` turns true. `arHoldValid` clears at
    //       `axi.ar.fire`, so the demand's own AR is delayed by ONE AR handshake --
    //       not by the speculative transaction's round trip.

    // ── Slice 1b (design spec section 3 boundary B3, hazard H10): the prefetch
    // window is seeded from a REGISTERED accepted-demand context, not from the live
    // `lookupPaddr` (which is literally `xlate.rsp.ppn ## pc(11:0)`). The window is a
    // speculative hint: nothing architectural reads it in the cycle the demand is
    // accepted, so one cycle of prefetch lateness is free (handoff section 15 step 9).
    //
    // The page-crossing restart rule inside seedPfWindow is ORDER-sensitive, not
    // TIMING-sensitive: it compares this line against the previously recorded
    // `pfDemandLine`. Feeding it a one-cycle-late but correctly ORDERED stream of
    // accepted demands preserves it exactly.
    val seedValidReg     = RegInit(False)
    val seedPaddrReg     = Reg(UInt(32 bits))
    val seedCacheableReg = RegInit(False)
    seedValidReg.simPublic(); seedCacheableReg.simPublic()

    def seedPfWindow(): Unit = {
      val line = seedPaddrReg & ~U(63, 32 bits)
      val pageEnd = (seedPaddrReg(31 downto 12) ## U(0xfff, 12 bits)).asUInt & ~U(63, 32 bits)
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
      val PREDECODE = new State
      val REPLAY    = new State
      // Silent fills collect independently by RID. A completed speculative line is
      // copied into the shared lineReg in IDLE, then uses this single installer state.
      val PF_PRED   = new State
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
        val setBlocked = if (canStartFill) False
                         else (fillArrayWrActive && (lookupSet === missSet))
        dataReadAddr := lookupReadAddr
        dataReadEn   := cmdPort.valid && !setBlocked

        // IDLE may accept any resolved command, including the demand miss it captures.
        // During a prefetch fill, only an answerable hit/fault may fire; a miss remains
        // held at the Stream boundary until the shared fill engine becomes free.
        val answerable = if (canStartFill)
          (lookupFault || isHit || (!pfInstallArm && !pfLookupSetBusy))
        else
          (lookupFault || isHit)
        cmdPort.ready := xlate.rsp.ready && !setBlocked && answerable

        when(cmdPort.fire) {
          // Slice 1b: capture only. The window itself is evaluated one cycle later,
          // outside the accept cone, from `seedValidReg`/`seedPaddrReg`.
          seedValidReg     := True
          seedPaddrReg     := lookupPaddr
          seedCacheableReg := !lookupFault && lookupCacheable
          when(lookupFault) {
            // Translation fault: emit a fault response (no data, no refill).
            s1Valid := True
            s1Way   := U(0, wayBits bits)
            s1Pc    := lookupPc
            s1Fault := True
            s1Atc   := True
            s1Lane  := lookupLaneIdx
            s1PredEntries := Vec.fill(ways)(B(0, PRED_BITS_PER_LINE bits))
            s1FromMiss := False
          } elsewhen(isHit) {
            s1Valid := True
            s1Way   := hitWayIdx
            s1Pc    := lookupPc
            s1Fault := False
            s1Atc   := False
            s1Lane  := lookupLaneIdx
            s1PredEntries := lookupPredEntry
            s1FromMiss := False

            // The first demand hit in a prefetched line keeps the stream moving.
            when(pfFilled(hitWayIdx)(lookupSet)) {
              pfFilled(hitWayIdx)(lookupSet) := False
              pfHitUseful := True
            }
          } otherwise {
            // Reachable only in IDLE because answerable excludes a prefetch-time miss.
            missPC    := lookupPc
            missSet   := lookupSet
            missTag   := lookupTag
            missPA    := lookupPaddr
            missCacheable := lookupCacheable
            victimWay := victim(lookupSet)
            beatCnt   := U(0, 1 bits)
            arSent    := False
            missBusFault := False
            missPoison   := False
            demandFillStart := True
            goto(REFILL)
          }
        }
      }

      IDLE.whenIsActive {
        lookupTick(canStartFill = true)
        // Slice 1a: `pfInstallArm` is a register, so `lineReg`'s capture enable and
        // `pfInstallIdx`'s 518-fanout select are reached from a flop. `answerable`
        // above guarantees no demand miss can be captured in the same cycle, so the
        // old `&& !demandFillStart` live veto is not merely redundant - it is gone.
        when(pfInstallArm) {
          pfInstallIdx := pfInstallSel
          lineReg := pfLineHi.readAsync(pfInstallSel) ## pfLineLo.readAsync(pfInstallSel)
          missPC        := pfPa(pfInstallSel)
          missPA        := pfPa(pfInstallSel)
          missSet       := pfSet(pfInstallSel)
          missTag       := pfTag(pfInstallSel)
          missCacheable := True
          victimWay     := pfWay(pfInstallSel)
          missPoison    := pfPoison(pfInstallSel) || anyInvalidate
          commitBeat    := U(0, 1 bits)
          goto(PF_PRED)
        }
      }
      // ----- Demand refill; speculative R traffic is handled independently by RID -----
      REFILL.whenIsActive {
        refillActive := True
        when(refillDone) {
          when(refillErr) { goto(FAULT) } otherwise { goto(PREDECODE) }
        }
      }

      // ----- FAULT: deliver a one-shot bus-error fault response; no allocation -----
      // Task #211: reached only via REFILL's non-OKAY AXI response. PREDECODE (the
      // tag/pred/valid/dataMem writes) is skipped entirely — no line is allocated,
      // matching the D-side no-allocate-on-error policy — and the victim pointer is
      // NOT advanced (this way was never actually filled). A PREFETCH burst error never
      // comes here (rule P2, above): it has no waiting fetch to fault.
      FAULT.whenIsActive {
        s1Valid := True
        s1Way   := U(0, wayBits bits)
        s1Pc    := missPC
        s1Fault := True
        s1Atc   := False   // physical bus error, not ATC/MMU-detected
        s1Lane  := missPC(4 downto 3)
        s1PredEntries := Vec.fill(ways)(B(0, PRED_BITS_PER_LINE bits))
        s1FromMiss := False
        goto(IDLE)
      }

      // ----- PREDECODE / PF_PRED: classify the line, write predMem + tag/valid -----
      // Body hoisted to the shared `predActive` datapath below the FSM (single
      // `dataMem(w).write(...)` / `predMem(w).write(...)` / `tagMem(w).write(...)` call
      // site each -- see the icache-burst-fault-fix comment there for why a SECOND call
      // site is not merely untidy but breaks SpinalHDL's MultiPortWritesSymplifier).
      PREDECODE.whenIsActive {
        predActive := True
        when(commitBeat === U(1, 1 bits)) { goto(REPLAY) }
      }

      PF_PRED.whenIsActive {
        predActive := True
        predIsPf   := True
        // Slice I1 / D3-SET-I: the array-write cycle is the one cycle a concurrent
        // lookup into the SAME set must not be answered (write-first async tagMem vs a
        // register `valids` array -> a same-set lookup could read the NEW tag against
        // the OLD valid bit and report a spurious hit on a half-written line). Held and
        // retried instead.
        fillArrayWrActive := True
        lookupTick(canStartFill = false)
        when(commitBeat === U(1, 1 bits)) {
          pfValid(pfInstallIdx)    := False
          pfArSent(pfInstallIdx)   := False
          pfComplete(pfInstallIdx) := False
          goto(IDLE)
        }
      }

      // ----- REPLAY: arm S1 read for the just-filled line, then IDLE -----
      REPLAY.whenIsActive {
        val replayBeatSel  = missPC(5)
        val replayReadAddr = (missSet ## replayBeatSel).asUInt
        val replayPredEntry = Vec(predMem.map(_.readAsync(missSet)))

        dataReadAddr := replayReadAddr
        dataReadEn   := True
        s1Valid := True
        s1Way   := victimWay
        s1Pc    := missPC
        // A refill only happens for a NON-faulting translation (the live fault
        // path emits a placeholder without refilling), so the replayed line is fault-
        // free by construction — off the live ITLB rsp entirely. A bus-erroring
        // refill never reaches REPLAY (it routes to FAULT instead, task #211).
        s1Fault := False
        s1Atc   := False
        s1Lane  := missPC(4 downto 3)
        // Task icache-corruption-fix: `replayPredEntry`/`dataBeat` (armed via
        // dataReadAddr/dataReadEn above) only hold meaningful content when the line
        // was actually allocated (missCacheable) — for a non-allocated (INHIBITED)
        // miss neither array was written THIS refill and may still hold stale,
        // unrelated content left by a prior allocation to this same way/set.
        // `s1FromMiss` routes the S1->rsp mux to the `lineReg`/`missPred` bypass
        // registers instead for that case, so latching these array reads regardless
        // is harmless (simply unused).
        //
        // Slice I1: `missPoison` (an invalidateAll that landed mid-fill) suppresses the
        // allocation too, so the same bypass must carry the response for that case --
        // the arrays were deliberately NOT written, and the in-flight fetch must still
        // be answered or FetchAlign's ring never retires its entry (see `missPoison`'s
        // declaration for the full front-end-wedge analysis).
        s1PredEntries := replayPredEntry
        s1FromMiss := !missCacheable || missPoison

        goto(IDLE)
      }
    }

    // Slice 1b: the deferred window update. `seedValidReg` is a one-shot; the
    // decision rules below are byte-for-byte the ones that used to run inside
    // `when(cmdPort.fire)`, only their operands are registered.
    seedValidReg := False   // default: one-shot, overridden by the capture above
    when(seedValidReg) {
      when(seedCacheableReg) {
        val line = seedPaddrReg & ~U(63, 32 bits)
        val sequential = pfSeqValid &&
                         (line(31 downto 12) === pfDemandLine(31 downto 12)) &&
                         (line === (pfDemandLine + U(64, 32 bits)))
        pfWindowUpdate := !pfSeqValid || ((line =/= pfDemandLine) && !sequential)
        seedPfWindow()
      } otherwise {
        pfWindowUpdate := True
        pfSeqValid := False
      }
    }

    // ══ Five-ID fill pool + single shared installer ═══════════════════════════════
    refillDone := False
    refillErr  := False

    // Allocate at most one registered same-page candidate per cycle. Resident/live
    // candidates advance the frontier without consuming a slot; same-set conflicts
    // wait, enforcing one fill owner per set.
    val demandSetOwned = (fsm.isActive(fsm.REFILL) || fsm.isActive(fsm.PREDECODE) ||
                          fsm.isActive(fsm.REPLAY) || fsm.isActive(fsm.FAULT)) &&
                         (missSet === pfCandSet)
    // `pfWindowUpdate` is read LIVE (review round 1 FIX 1): the cycle that REWRITES
    // the frontier is the cycle the allocator must yield, because `seedPfWindow()`
    // and the `pfNextPa := pfNextPa + 64` advances below are last-assignment-wins
    // writes to the same register and the allocator is elaborated later. It is a
    // register-only expression (see the Slice 1c block above), so reading it live
    // costs this slice nothing.
    when(pfWindowHasCandidate && !anyInvalidate &&
         !pfWindowUpdate && !heldDemandMissReg) {
      when(pfCandLive) {
        pfNextPa := pfNextPa + U(64, 32 bits)
      } elsewhen(pfCandSetBusy || demandSetOwned) {
        // Wait. A resident candidate may be the victim that the current same-set
        // owner is about to evict, so resident suppression is only legal once the
        // set becomes owner-free.
      } elsewhen(pfCandResident) {
        pfNextPa := pfNextPa + U(64, 32 bits)
      } elsewhen(pfHasFree) {
        // Slice 1c telemetry, direction (a) (review round 1 FIX 2 -- this was a
        // sim-fatal assert and must not be, because the RTL permits it and the
        // outcome is benign): a demand miss captured THIS cycle plus a candidate
        // that VIPT-aliases onto the exact set the demand is about to own. Both
        // owners latch the same `victim(S)` and the installs are FSM-serialised, so
        // it costs one wasted self-evicting fill and nothing else.
        pfAllocSetCollide := demandFillStart && (pfCandSet === lookupSet)
        pfValid(pfFreeIdx)    := True
        pfArSent(pfFreeIdx)   := False
        pfComplete(pfFreeIdx) := False
        pfBeat(pfFreeIdx)     := False
        pfErr(pfFreeIdx)      := False
        pfPoison(pfFreeIdx)   := False
        pfPa(pfFreeIdx)       := pfNextPa
        pfSet(pfFreeIdx)      := pfCandSet
        pfTag(pfFreeIdx)      := pfCandTag
        pfWay(pfFreeIdx)      := victim(pfCandSet)
        pfNextPa              := pfNextPa + U(64, 32 bits)
      }
    }

    // Completed errors and poisoned silent fills allocate nothing and free locally.
    for (i <- 0 until pfSlots) {
      when(pfValid(i) && pfComplete(i) && (pfErr(i) || pfPoison(i))) {
        pfValid(i)    := False
        pfArSent(i)   := False
        pfComplete(i) := False
      }
      when(anyInvalidate && pfValid(i)) { pfPoison(i) := True }
    }
    when(anyInvalidate) { pfSeqValid := False }

    // Registered AR holding point: demand always wins an empty arbiter, then the
    // lowest speculative slot. Payload cannot change under backpressure.
    val pfArWant = Vec((0 until pfSlots).map(i => pfValid(i) && !pfArSent(i) && !pfComplete(i)))
    val pfAnyArWant = pfArWant.orR
    val pfArSel = OHToUInt(OHMasking.first(pfArWant.asBits))
    // Do not create a new speculative AR hold in front of an architectural miss
    // already visible at the command boundary. An AR that was presented earlier
    // remains stable until fire, as AXI requires; this guard handles the empty-holder
    // arbitration case and the matching-fill error/retry boundary.
    //
    // DEVIATION FROM THE BRIEF (proven by a live hang, not by style preference): the
    // brief's Step 3 snippet compares against `missSet` here, on the claim that
    // "missSet ... is latched at miss-capture time and is exactly the set the held
    // demand needs unblocked." That is false for a demand that is HELD *BEFORE ever
    // being captured* -- exactly the case `heldDemandMiss`'s own declaration-site
    // comment names ("An architectural miss can remain visibly held while a
    // speculative owner or the shared installer drains") and exactly what
    // `IcachePrefetchSpec`'s "AR-pending demotion launches the blocking silent owner
    // without deadlock" test exercises: `cmdIn.ready` is asserted False on purpose
    // (`pfLookupSetBusy` blocks `answerable`), so `cmdPort.fire` never happens, so
    // `demandFillStart` never fires, so `missSet` is NEVER written to this demand's
    // set -- it keeps whatever value the PREVIOUS accepted demand left behind. With
    // `missSet` here, `pfBlockingArWant`/`pfBlockingArAny` therefore stay False for
    // the entire hold, `(!heldDemandMissReg || pfBlockingArAny)` is permanently
    // False, no further speculative AR is ever granted, the blocking slot's own AR
    // never launches, `pfLookupSetBusy` never clears, and the demand is held
    // forever. CONFIRMED LIVE: running the brief's snippet verbatim hung the
    // Verilator suite for 44+ minutes at 100% CPU, stuck inside exactly that test
    // with no further progress (see task-6-report.md for the run transcript and the
    // kill/diagnose trace). `lookupSet` is kept LIVE here instead (as the
    // pre-existing code already did): it is cheap address bits off
    // `cmdPort.payload.pc` (via `lookupPc`), not part of the translation-latency
    // verdict cone (`xlate.rsp.ready`/`lookupFault`/`isHit`) this slice registers --
    // `heldDemandMissReg` (the actual verdict, used both in the Mux select below and
    // in the gating term) is what makes this registered state; the address compare
    // does not need to be, and moving it to `missSet` is not a "strict improvement in
    // precision," it is a correctness regression for the not-yet-captured case.
    val pfBlockingArWant = Vec((0 until pfSlots).map(i =>
      pfArWant(i) && (pfSet(i) === lookupSet)))
    val pfBlockingArAny = pfBlockingArWant.orR
    val pfBlockingArSel = OHToUInt(OHMasking.first(pfBlockingArWant.asBits))
    val pfChosenArSel = Mux(heldDemandMissReg, pfBlockingArSel, pfArSel)
    when(!arHoldValid) {
      when(refillActive && !arSent) {
        arHoldValid := True
        arHoldId    := U(AxiIds.I_DEMAND, AxiIds.ID_W bits)
        arHoldAddr  := missPA & ~U(63, 32 bits)
      } elsewhen(pfAnyArWant && (!heldDemandMissReg || pfBlockingArAny)) {
        // Slice 1c telemetry, direction (b) (review round 1 FIX 2 -- was a sim-fatal
        // assert; same reasoning as direction (a)): a fresh speculative want claimed
        // `arHoldValid` on the exact cycle a demand fill started, before
        // `refillActive` turned true. The demand's own `refillActive && !arSent`
        // branch above needs `!arHoldValid`, so it waits -- but only until
        // `axi.ar.fire` clears the holder, i.e. ONE AR handshake, not a round trip.
        pfArDemandRace := demandFillStart
        arHoldValid := True
        arHoldId    := (pfChosenArSel.resize(AxiIds.ID_W) +
                        U(AxiIds.I_SPEC_BASE, AxiIds.ID_W bits)).resized
        arHoldAddr  := pfPa(pfChosenArSel) & ~U(63, 32 bits)
      }
    }

    axi.ar.valid         := arHoldValid
    axi.ar.payload.addr  := arHoldAddr
    axi.ar.payload.id    := arHoldId
    axi.ar.payload.len   := U(1, 8 bits)
    axi.ar.payload.size  := U(5, 3 bits)
    axi.ar.payload.burst := Axi4.burst.INCR
    when(axi.ar.fire) {
      arHoldValid := False
      when(arHoldId === U(AxiIds.I_DEMAND, AxiIds.ID_W bits)) {
        arSent := True
      } otherwise {
        val sentIdx = (arHoldId - U(AxiIds.I_SPEC_BASE, AxiIds.ID_W bits)).resize(pfIdxBits)
        pfArSent(sentIdx) := True
      }
    }

    // Route every R beat solely by RID. ID 0 feeds the existing precise demand
    // context; IDs 1..4 feed one shallow speculative line store.
    val ridIsDemand = axi.r.payload.id === U(AxiIds.I_DEMAND, AxiIds.ID_W bits)
    val ridIsPf = (axi.r.payload.id >= U(AxiIds.I_SPEC_BASE, AxiIds.ID_W bits)) &&
                  (axi.r.payload.id <= U(AxiIds.I_SPEC_LAST, AxiIds.ID_W bits))
    val pfRspIdx = (axi.r.payload.id -
                    U(AxiIds.I_SPEC_BASE, AxiIds.ID_W bits)).resize(pfIdxBits)
    val demandRspMatch = ridIsDemand && refillActive && arSent
    val pfRspMatch = ridIsPf && pfValid(pfRspIdx) && pfArSent(pfRspIdx) && !pfComplete(pfRspIdx)
    axi.r.ready := demandRspMatch || pfRspMatch

    when(axi.r.valid) {
      assert(demandRspMatch || pfRspMatch, "I-cache R beat has no live RID owner")
    }

    val pfRspFire = axi.r.fire && pfRspMatch
    pfLineLo.write(pfRspIdx, axi.r.payload.data, enable = pfRspFire && !pfBeat(pfRspIdx))
    pfLineHi.write(pfRspIdx, axi.r.payload.data, enable = pfRspFire && pfBeat(pfRspIdx))

    when(axi.r.fire) {
      val respErr = axi.r.payload.resp =/= Axi4.resp.OKAY
      when(demandRspMatch) {
        assert(axi.r.payload.last === beatCnt.asBool,
          "demand I-cache refill must be exactly two beats")
        when(respErr) { missBusFault := True }
        when(beatCnt === U(0, 1 bits)) {
          lineReg(255 downto 0) := axi.r.payload.data
        } otherwise {
          lineReg(511 downto 256) := axi.r.payload.data
        }
        beatCnt := beatCnt + 1
        when(axi.r.payload.last) {
          refillDone := True
          refillErr  := missBusFault || respErr
        }
      } otherwise {
        assert(axi.r.payload.last === pfBeat(pfRspIdx),
          "speculative I-cache refill must be exactly two beats")
        when(respErr) { pfErr(pfRspIdx) := True }
        pfBeat(pfRspIdx) := !pfBeat(pfRspIdx)
        when(axi.r.payload.last) {
          pfComplete(pfRspIdx) := True
          pfErr(pfRspIdx) := pfErr(pfRspIdx) || respErr
        }
      }
    }

    // ---- predecode dwell: classify both beats, then commit the arrays ----
    // ── Slice I2: ONE 16-instance classify group, used on BOTH cycles of the dwell
    // (commitBeat 0 = low beat, commitBeat 1 = high beat). See `classifyBeat` above for
    // the full rationale and the equivalence argument.
    //
    // The lookahead for the LOW beat's last 3 words (line words 13/14/15 need 16/17/18)
    // is taken straight out of `lineReg`'s high half, which is already resident this
    // cycle — so the beat-lag the design doc describes is not even needed here; the
    // whole line is in a register by the time the dwell runs. For the HIGH beat the
    // lookahead runs past the line end, which is the pre-existing `extWValid = false`
    // boundary case (the F5 fix): `classify` refuses to guess brief-vs-full for
    // anything needing that word's content and rejects as COMPLEX instead of silently
    // mis-framing, `ambiguousLine` marks it, and `Aligner.scala:63-65` re-classifies
    // live from the instruction buffer's own already-fetched words.
    val isLoBeat  = commitBeat === U(0, 1 bits)
    val beatSrc   = Mux(isLoBeat, lineReg(255 downto 0), lineReg(511 downto 256))
    val beatNext3 = Mux(isLoBeat, lineReg(303 downto 256), B(0, 48 bits))
    val beatPred  = classifyBeat(beatSrc, beatNext3, isLoBeat)
    // Whole-line packed value, meaningful only on commitBeat==1 (the low half is the
    // previous cycle's registered result, the high half is live).
    val packedPred = Bits(PRED_BITS_PER_LINE bits)
    packedPred(PRED_BITS_PER_BEAT - 1 downto 0)                  := predAccumLo
    packedPred(PRED_BITS_PER_LINE - 1 downto PRED_BITS_PER_BEAT) := beatPred

    when(predActive) {
      // An INHIBITED-mode fetch never allocates a line — no tag/valid write, no
      // victim-pointer advance (that way is not consumed; the same victim way is tried
      // again on the NEXT real allocation to this set). Slice I1 adds `missPoison`: an
      // `invalidateAll` that landed mid-fill also suppresses the allocation, so a fill
      // in flight when the cache was told to drop everything cannot quietly re-install
      // its line one cycle later. (Closure (1), the same-cycle guard on the `valids`
      // write, is still present below and covers the exactly-simultaneous case.)
      val doAllocate = missCacheable && !missPoison
      when(isLoBeat) {
        predAccumLo := beatPred
        // DEBUG only: the cycle immediately BEFORE the allocation write. Its one
        // consumer, IcacheSpec's invalidate-race test, self-checks the pairing by
        // re-reading `dbgAllocCommitCycle` on the next sampling point.
        dbgAllocCommitPending := doAllocate
      } otherwise {
        // `packedPred` is only whole-line-complete on commitBeat==1, so the array
        // commit and the `missPred` bypass latch both happen here. Nothing downstream
        // shifts: REPLAY is entered the cycle AFTER commitBeat==1 either way.
        missPred := packedPred
        dbgAllocCommitCycle := doAllocate
        for (w <- 0 until ways) {
          when(victimWay === U(w, wayBits bits)) {
            when(doAllocate) {
              predMem(w).write(missSet, packedPred)
              tagMem(w).write(missSet, missTag)
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
                valids(w)(missSet) := True
              }
              // Telemetry only: record whether the installed line arrived through a
              // silent speculative ID. Demand installation clears the marker; window
              // allocation does not depend on it.
              pfFilled(w)(missSet) := predIsPf && !anyInvalidate
            }
          }
        }
        when(doAllocate) {
          victim(missSet) := victim(missSet) + 1
        }
      }
      when(doAllocate) {
        for (w <- 0 until ways) {
          when(victimWay === U(w, wayBits bits)) {
            dataMem(w).write((missSet ## commitBeat).asUInt,
              Mux(isLoBeat, lineReg(255 downto 0), lineReg(511 downto 256)))
          }
        }
      }
      commitBeat := commitBeat + 1
      when(!isLoBeat) {
        commitBeat := U(0, 1 bits)   // reset for the NEXT fill's predecode dwell
      }
    }

    // ---- slice I1 closure (2): poison an in-flight fill on invalidateAll ----
    // See `missPoison`'s declaration for why the response is still delivered and why
    // §6.5's "invalidate the s1*/rsp* stages" bullet is deliberately NOT adopted.
    when(anyInvalidate && !fsm.isActive(fsm.IDLE)) { missPoison := True }

    // FetchService accessors
    def cmd: Stream[FetchCmd] = cmdPort
    def rsp: Flow[FetchRsp]   = rspPort
  }

  // ---- FetchService trait implementation ----
  override def cmd: Stream[FetchCmd] = logic.cmdPort
  override def rsp: Flow[FetchRsp]   = logic.rspPort
}
