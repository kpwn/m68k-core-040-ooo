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
    val activePc = UInt(32 bits)
    xlate.req.valid      := True
    xlate.req.vpn        := activePc(31 downto 12)
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
    // Slice I3: per-(way,set) "this line was installed by a PREFETCH and has not been
    // demand-hit yet". 4 x 64 = 256 flops. It is LOAD-BEARING, not just telemetry: the
    // trigger "first demand HIT in a line that was itself prefetched -> prefetch the
    // next line" is what keeps the prefetch stream running once prefetching has caught
    // up with the demand stream. Without it a prefetch can only be triggered by a
    // demand MISS, which leaves the stream alternating miss/hit (half the available
    // win) instead of fully covered. Cleared on a demand fill of the same way/set, on
    // the first demand hit, and by invalidateAll.
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

    // ---- T-stage: REGISTERED ITLB translation (FMax pipeline split) ----
    // The cmd is accepted into this stage once the ITLB has RESOLVED the translation
    // (xlate.rsp.ready). We REGISTER the translated physical address ({ppn, pc[11:0]})
    // + fault here, so the I-cache hit-detect below tags on the REGISTERED physical
    // paddr (tPaddr) instead of the LIVE `xlate.rsp.ppn`. This removes the ITLB `Tlb`
    // way-mux / walk-latch mux from the VIPT hit cone (tag-compare -> hit ->
    // dataMem ENARDEN / s1Pred), which was the post-ITLB route-dominated limiter.
    // VIPT: index on the page-invariant pc[11:6] set bits; tag on the registered
    // physical bits. MMU-off (identity, ppn==pc[31:12]) flows through the SAME
    // register so both modes are pipelined uniformly. Costs ONE translate-latency
    // cycle on a fetch (the frontend is single-outstanding + latency-agnostic).
    val tValid = RegInit(False)
    val tPc    = Reg(UInt(32 bits))
    val tPaddr = Reg(UInt(32 bits))   // {ppn, pc[11:0]} captured at translate time
    val tFault = RegInit(False)
    // Cache-mode attribute (MMU page CM bits / DTT-ITT window), captured alongside
    // tPaddr/tFault at the SAME translate-time register point. An INHIBITED I-fetch
    // must never allocate into the I-cache (device/MMIO instruction space, or a
    // deliberately non-cacheable region — self-modifying-code / debug scenarios rely
    // on every fetch seeing fresh memory) and must never be satisfied by a stale
    // resident line left over from before the mapping's cache-mode attribute changed.
    // Mirrors DcachePlugin's ldS1Cmode/missCmode (task P1.4).
    val tCmode = Reg(CacheMode())

    // Derived off the REGISTERED tCmode (same register the hit-detect cone already
    // reads tPaddr from) — an INHIBITED fetch forces every way's hit bit low below,
    // so a stale resident alias is bypassed rather than served, and it always falls
    // through to the miss/REFILL path (which itself refuses to allocate — see the
    // PREDECODE `doAllocate` gate below).
    val tCacheable = tCmode =/= CacheMode.INHIBITED

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

    // ---- shared data-array read port (synchronous; BRAM) ----
    // Address+enable are driven by the FSM (IDLE hit accept, or REPLAY). The
    // result `dataBeat` is the registered BRAM output, valid the NEXT cycle.
    val dataReadAddr = UInt((setBits + 1) bits)
    val dataReadEn   = Bool()
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
    // Task #211: which cause armed s1Fault — True = ITLB/MMU translation fault
    // (tFault below), False = a physical AXI bus error caught in REFILL (new FAULT
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
    axi.ar.valid  := False
    axi.ar.payload.assignDontCare()
    axi.r.ready   := False
    activePc      := cmdPort.payload.pc

    // ---- hit detection (combinational, from the REGISTERED T-stage paddr) ----
    // VIPT: index with the page-invariant pc[11:6] set bits (from the registered tPc);
    // tag with the REGISTERED physical page number tPaddr[31:12]. The live ITLB
    // way-mux is NOT in this cone (it was already resolved into tPaddr the prior
    // cycle), so the tag-compare -> hit -> dataMem ENARDEN arc is route-clean.
    val idlePc    = tPc
    val idleSet   = idlePc(11 downto 6)
    val idleTag   = tPaddr(31 downto 12)
    val hitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      hitVec(w) := tCacheable && valids(w)(idleSet) && (tagMem(w).readAsync(idleSet) === idleTag)
    val isHit       = hitVec.orR
    val hitWayIdx   = OHToUInt(hitVec)
    val idleBeatSel = idlePc(5)
    val idleLaneIdx = idlePc(4 downto 3)
    val idleReadAddr  = (idleSet ## idleBeatSel).asUInt
    val idlePredEntry = Vec(predMem.map(_.readAsync(idleSet)))

    // Depth-2 pipelined accept (VARIANT 1): the cache is a flow-through 3-stage pipe
    // (T-stage -> S1 -> rsp), each a single register that ADVANCES every cycle. The
    // T-stage is consumed (freed) every IDLE cycle it is valid, so a NEW cmd may be
    // accepted INTO the T-stage the SAME cycle the prior translation is consumed into
    // S1 — i.e. ≥2 fetches in flight (T + S1 + rsp). The downstream S1/rsp never
    // back-pressure (flow-through), so the only accept block is the FSM not being in
    // IDLE (a refill in progress). The single-outstanding `inFlight` gate is REMOVED;
    // accept gates ONLY on the ITLB resolve (xlate.rsp.ready) inside IDLE. The consume
    // `tValid := False` is reordered to NOT clobber a same-cycle cmdPort.fire (see the
    // tAccept/tConsume split below) so the back-to-back accept is not dropped.

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
    val refillIsPf        = Bool(); refillIsPf        := False
    val refillDone        = Bool()   // driven by the datapath, read by the FSM
    val refillErr         = Bool()
    val predActive        = Bool(); predActive        := False
    val predIsPf          = Bool(); predIsPf          := False
    val fillArrayWrActive = Bool(); fillArrayWrActive := False
    // Slice I3 telemetry: pure wires (zero flops, zero synthesis cost) that a
    // testbench counts to derive design doc §8.3's "prefetch issued / useful /
    // wasted". Deliberately NOT synthesised counters -- the core must not carry
    // measurement-only registers.
    val pfHitUseful  = Bool(); pfHitUseful  := False; pfHitUseful.simPublic()
    val pfReqFromHit = Bool(); pfReqFromHit := False

    // ── Slice I3: the next-line prefetch candidate (rules P1–P4, design doc §4.1(ii)) ──
    //
    // *** THESE RULES ARE BINDING. They are the standing no-SoC-address-map rule
    //     (GC1) applied to speculation: the core may only prefetch where it has
    //     class-(a) evidence -- a translation it ALREADY resolved -- and must never
    //     record a verdict about an address it merely guessed at. ***
    //
    //   P1 — a prefetch is issued ONLY for a next line whose translation is ALREADY
    //        RESIDENT in the ITLB and whose page is software-configured cacheable. A
    //        prefetch must NEVER trigger a table walk and must never be issued for an
    //        INHIBITED page.
    //   P2 — a prefetch that receives a non-OKAY response is SILENTLY DISCARDED: no
    //        allocation, no architectural fault, no sticky diagnostic record, no core
    //        halt. Mandatory precisely BECAUSE the core cannot know whether the address
    //        is backed; the prefetch never asserted that it was.
    //   P3 — because P2 allocates nothing, a later DEMAND fetch of that line issues a
    //        NEW REAL transaction and gets its own contemporaneous response, with the
    //        existing task-#211 `r.resp` check delivering the fault precisely. No
    //        cached verdict, in either direction.
    //   P4 — a prefetch must never cross a page boundary on a GUESS.
    //
    // P1 and P4 are satisfied BY CONSTRUCTION here, with no ITLB probe at all and
    // therefore no way to accidentally arm the walker: the prefetch line is
    // `source + 64`, and it is issued ONLY when that address lies in the SAME 4 KiB
    // page as the access whose translation is ALREADY resolved (`pfSrcPa`). A page
    // holds 64 lines, so this covers 63 of every 64 next-lines; the 64th (a
    // page-crossing next-line) is simply DROPPED, which is exactly what P4 requires.
    // Same page also means the same page descriptor, hence the same CM bits -- so the
    // cacheability of the source access carries over, satisfying P1's INHIBITED rule
    // (the two trigger sites each additionally require their source to be cacheable).
    val pfSrcVa = UInt(32 bits)
    val pfSrcPa = UInt(32 bits)
    pfSrcVa := missPC      // default: the REPLAY trigger (a demand fill just landed)
    pfSrcPa := missPA
    val pfNextVa   = ((pfSrcVa(31 downto 6) + 1) @@ U(0, 6 bits)).resize(32)
    val pfSamePage = pfNextVa(31 downto 12) === pfSrcVa(31 downto 12)
    val pfSet      = pfNextVa(11 downto 6)
    val pfTag      = pfSrcPa(31 downto 12)
    val pfPa       = pfSrcPa(31 downto 12) @@ pfNextVa(11 downto 0)
    // Suppression: never prefetch a line that is already resident. (A line already in
    // an MSHR, or in the same set as an active MSHR -- the D3-SET-I exclusion -- cannot
    // arise: the fill engine is shared, so a prefetch is only ever started when it is
    // free, and the prefetched set is always `sourceSet + 1`.)
    val pfResident = Vec((0 until ways).map(w =>
                       valids(w)(pfSet) && (tagMem(w).readAsync(pfSet) === pfTag))).orR
    val prefetchArmed = prefetchEnable && pfSamePage && !pfResident

    /** Re-point the shared fill engine at the prefetch candidate. Costs no new state:
      * a prefetch is never in flight at the same time as a demand fill. */
    def startPrefetch(): Unit = {
      missPC        := pfPa      // unused (a prefetch produces no response); kept defined
      missSet       := pfSet
      missTag       := pfTag
      missPA        := pfPa
      missCacheable := True      // P1: same page as a cacheable source => same CM bits
      victimWay     := victim(pfSet)
      beatCnt       := U(0, 1 bits)
      arSent        := False
      missBusFault  := False
      missPoison    := False
      commitBeat    := U(0, 1 bits)
    }

    // ---- FSM ----
    val fsm = new StateMachine {
      val IDLE      = new State with EntryPoint
      val REFILL    = new State
      val PREDECODE = new State
      val REPLAY    = new State
      // ── Slice I3: the next-line PREFETCH fill ────────────────────────────────
      // A prefetch reuses the SAME fill machinery as a demand refill (missPA/missSet/
      // missTag/victimWay/beatCnt/arSent/lineReg/commitBeat and the single 16-instance
      // classify group), which is why it costs no new line buffer, no second dataMem
      // write port and no second predecode group. What makes that safe is design doc
      // §6.2's key simplification: **a prefetch has NO waiting consumer.** It never
      // produces a `FetchRsp`, never occupies a FetchAlign ring entry, never needs a
      // tag and cannot produce a fault. It is a pure allocate -- so `FetchAlignPlugin`
      // needs no change whatsoever and the `ibufRoomForIssue` reservation is untouched.
      //
      // These two states differ from REFILL/PREDECODE in exactly one way that matters:
      // they keep the FETCH PORT OPEN and keep answering HITS (`lookupTick(false)`).
      // That is the whole of the "non-blocking accept" this design can safely have --
      // see the big ordering note on `lookupTick`.
      val PF_REFILL = new State
      val PF_PRED   = new State
      // Task #211: a REFILL whose AXI read response(s) came back non-OKAY
      // (SLVERR/DECERR — genuinely unmapped or erroring physical memory). No line
      // is allocated (PREDECODE, which does the tag/pred/valid writes, is skipped
      // entirely); this delivers a one-shot fault response instead, mirroring
      // DcachePlugin's REPLAY/busFaultResp handling (task #189).
      val FAULT     = new State

      // ----- IDLE: T-accept (translate) + consume the REGISTERED T-stage -----
      // Two decoupled, flow-through actions (depth-2: accept may overlap consume):
      //
      // (1) T-ACCEPT: present `cmdPort.pc` to the ITLB; once it has RESOLVED the
      //     translation (xlate.rsp.ready), REGISTER {ppn,pc[11:0]} + fault into the
      //     T-stage. MMU-off identity is always ready, so MMU-disabled fetch is
      //     unchanged. On an ITLB MISS rsp.ready is LOW (the walker is running): we do
      //     not accept (stall) until it resolves.
      // (2) CONSUME: when a translated fetch is registered (tValid), tag-compare off
      //     the REGISTERED tPaddr (the live ITLB way-mux is OUT of this cone) and
      //     either emit a fault placeholder, arm the S1 hit read, or start a refill
      //     from the registered physical line base. A resolved translation that FAULTS
      //     (non-resident / supervisor I-page) is consumed as a fault placeholder
      //     (s1Fault) WITHOUT a refill — the rsp carries fault -> DecodePacket.fault.
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
      // SAME cheap mechanism the plan itself sanctions: **hold the T-stage and
      // re-look-up.** A demand miss during a prefetch fill is not consumed and not
      // allocated; it simply retries every cycle, and once the fill lands it HITS.
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
        activePc      := cmdPort.payload.pc
        // Depth-2 accept: gate ONLY on the ITLB resolve (no single-outstanding block).
        // The T-stage is freed every cycle it is consumed (below), so a new cmd may
        // enter it the same cycle. NOTE: a MISS this cycle leaves IDLE (goto REFILL);
        // the accept is still permitted (cmdPort.ready is combinational and we are
        // still in IDLE), and the same-cycle-accepted translation is held in the
        // T-stage and re-consumed after the refill returns to IDLE — so it is NOT lost.
        cmdPort.ready := xlate.rsp.ready

        // (2) Consume the registered translation FIRST (so its `tValid := False` is
        // OVERRIDDEN by a same-cycle (1) T-accept's `tValid := True` below — the
        // depth-2 back-to-back accept must not be dropped). The consume reads the
        // REGISTERED tPc/tPaddr (old values), unaffected by the new accept's writes.
        when(tValid) {
          // FMax: arm the data-BRAM read whenever a translated fetch is present —
          // INDEPENDENT of the hit/fault decision. The result is only CONSUMED when
          // s1Valid is set (a real hit below), so a redundant read on a miss/fault
          // cycle is harmless (power only). Off the registered tPaddr, so the tag-
          // compare is no longer in the data-BRAM `ENARDEN` critical path.
          dataReadAddr := idleReadAddr
          dataReadEn   := True
          // D3-SET-I (see above): under a prefetch fill, a lookup into the fill's own
          // set is not answerable this cycle.
          val setBlocked = if (canStartFill) False else (fillArrayWrActive && (idleSet === missSet))
          when(!setBlocked) { tValid := False }   // consumed (a same-cycle accept re-sets it)

          // Register the RAW per-way predMem entries (way-mux + window-decode deferred
          // to S1 keyed off s1Way). On a fault placeholder the entries are zeroed
          // (windowPred(0) == the old getZero placeholder).
          when(setBlocked) {
            // HOLD: neither consume nor accept. Retried next cycle.
            cmdPort.ready := False
          } elsewhen(tFault) {
            // Translation fault: emit a fault response (no data, no refill).
            s1Valid := True
            s1Way   := U(0, wayBits bits)
            s1Pc    := idlePc
            s1Fault := True
            s1Atc   := True   // ITLB/MMU-detected (task #211)
            s1Lane  := idleLaneIdx
            s1PredEntries := Vec.fill(ways)(B(0, PRED_BITS_PER_LINE bits))
            s1FromMiss := False
          } elsewhen(isHit) {
            // Hit: the data BRAM read is already armed above; latch the S1 control.
            s1Valid := True
            s1Way   := hitWayIdx
            s1Pc    := idlePc
            s1Fault := False
            s1Atc   := False
            s1Lane  := idleLaneIdx
            s1PredEntries := idlePredEntry
            s1FromMiss := False
            // Slice I3, second trigger (design doc §6.2): the FIRST demand hit in a
            // line that was itself prefetched is the cheap "that prefetch was useful,
            // keep going" heuristic. Consume the bit so it fires once per prefetched
            // line, and request the next line. `pfHitUseful` is a pure wire (no
            // register, no synthesis cost) that a testbench counts to derive the
            // design doc §8.3 "prefetch useful" statistic.
            when(pfFilled(hitWayIdx)(idleSet)) {
              pfFilled(hitWayIdx)(idleSet) := False
              pfHitUseful := True
              if (canStartFill) {
                pfReqFromHit := True
                // Re-point the prefetch candidate at THIS hit's (already resolved)
                // translation -- rule P1 needs a resident translation, and the T-stage
                // has one for exactly this address.
                pfSrcVa := idlePc
                pfSrcPa := tPaddr
              }
            }
          } otherwise {
            if (canStartFill) {
              missPC    := idlePc
              missSet   := idleSet
              missTag   := idleTag
              // PHYSICAL line base for the refill: the registered translated PA =
              // {ppn, pc[11:0]} (MMU-off identity: ppn == pc[31:12], == the VA).
              missPA    := tPaddr
              missCacheable := tCacheable
              victimWay := victim(idleSet)
              beatCnt   := U(0, 1 bits)
              arSent    := False
              missBusFault := False   // task #211: reset per-refill
              missPoison   := False    // slice I1: fresh fill, not yet invalidated
              goto(REFILL)
            } else {
              // MERGE / DEMOTION (plan I1.2 + I3.2): a demand miss arriving while a
              // prefetch fill is in flight is HELD, not allocated and not dropped.
              // Dropping it would lose a fetch and wedge FetchAlign's ring accounting.
              // If it is the line being prefetched, the retry HITS once the fill lands
              // (that is the demotion case, and it is where the prefetch pays off
              // partially). If it is a different line, the retry allocates a normal
              // demand fill once the prefetch completes.
              tValid := True
              cmdPort.ready := False
            }
          }
        }

        // (1) T-accept: register the resolved translation. Placed LAST so its
        // `tValid := True` wins over the consume's `tValid := False` on a back-to-back
        // accept cycle (depth-2). On a miss cycle this same-cycle accept is preserved
        // across the refill (tValid stays True through REFILL/PREDECODE/REPLAY and is
        // consumed on the IDLE return).
        when(cmdPort.fire) {
          tValid := True
          tPc    := cmdPort.payload.pc
          tPaddr := (xlate.rsp.ppn ## cmdPort.payload.pc(11 downto 0)).asUInt
          tFault := xlate.rsp.fault
          tCmode := xlate.rsp.cacheMode
        }
      }

      IDLE.whenIsActive {
        lookupTick(canStartFill = true)
        // A prefetch requested by the "useful prefetch => keep going" hit trigger can
        // only start from IDLE (the fill engine is shared). Placed AFTER lookupTick so
        // a demand miss in the same cycle wins the engine -- demand always has
        // priority over prefetch.
        // (`pfReqFromHit` is set only in the HIT arm, so it is mutually exclusive with
        // the miss arm's `goto(REFILL)` -- demand can never lose the engine to it.)
        when(pfReqFromHit && prefetchArmed) {
          startPrefetch()
          goto(PF_REFILL)
        }
      }
      // ----- REFILL / PF_REFILL: issue AXI AR; collect the 2 R beats -----
      // Both states share ONE hoisted fill datapath (`refillActive`, below the FSM).
      // The demand and prefetch fills are NOT allowed to run concurrently: they share
      // `lineReg`/`beatCnt`/`missPA`/`missSet`/`missTag`/`victimWay`/`commitBeat` and
      // the single 16-instance classify group, which is exactly why slice I3 costs no
      // second line buffer, no second dataMem write port and no second predecode group.
      // This is also honest about the SoC: the crossbar is ONE outstanding read per
      // master port (design doc §3.2), so a second concurrent I-side fill would buy
      // nothing end-to-end today anyway. Prefetch wins by starting a line's fill EARLY
      // in TIME, not by overlapping it with another transaction (§6.2).
      REFILL.whenIsActive {
        activePc     := missPC
        refillActive := True
        when(refillDone) {
          when(refillErr) { goto(FAULT) } otherwise { goto(PREDECODE) }
        }
      }

      PF_REFILL.whenIsActive {
        refillActive := True
        refillIsPf   := True
        // THE point of slice I1: the fetch port stays OPEN and hits keep being served
        // for the whole prefetch fill. See `lookupTick`'s ordering note.
        lookupTick(canStartFill = false)
        when(refillDone) {
          // P2 -- a prefetch that receives a non-OKAY response is SILENTLY DISCARDED:
          // no allocation, no architectural fault, no sticky diagnostic record, no core
          // halt. Mandatory precisely BECAUSE the core cannot know whether the address
          // is backed; the prefetch never asserted that it was (design doc §4.1(ii) P2,
          // and the standing no-SoC-address-map rule GC1). Note there is nothing to
          // "deliver" either way -- a prefetch has no waiting consumer.
          //
          // P3 follows from P2: because nothing was allocated, a later DEMAND fetch of
          // that line issues a NEW REAL transaction and gets its own contemporaneous
          // response, with the task-#211 r.resp check below delivering the fault
          // precisely through the FAULT state. No cached verdict, in either direction.
          when(refillErr) { goto(IDLE) } otherwise { goto(PF_PRED) }
        }
      }

      // ----- FAULT: deliver a one-shot bus-error fault response; no allocation -----
      // Task #211: reached only via REFILL's non-OKAY AXI response. PREDECODE (the
      // tag/pred/valid/dataMem writes) is skipped entirely — no line is allocated,
      // matching the D-side no-allocate-on-error policy — and the victim pointer is
      // NOT advanced (this way was never actually filled). A PREFETCH burst error never
      // comes here (rule P2, above): it has no waiting fetch to fault.
      FAULT.whenIsActive {
        activePc := missPC
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
        activePc  := missPC
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
        when(commitBeat === U(1, 1 bits)) { goto(IDLE) }
      }

      // ----- REPLAY: arm S1 read for the just-filled line, then IDLE -----
      REPLAY.whenIsActive {
        activePc := missPC
        val replayBeatSel  = missPC(5)
        val replayReadAddr = (missSet ## replayBeatSel).asUInt
        val replayPredEntry = Vec(predMem.map(_.readAsync(missSet)))

        dataReadAddr := replayReadAddr
        dataReadEn   := True
        s1Valid := True
        s1Way   := victimWay
        s1Pc    := missPC
        // A refill only happens for a NON-faulting translation (the T-stage fault
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

        // ── Slice I3 trigger #1: a demand fill just allocated line L -> prefetch L+64.
        // Placed here rather than in PREDECODE so the demand's own response is already
        // armed off the ARRAYS (stable) before the prefetch starts overwriting
        // `lineReg`; the earliest a prefetch beat can land is two cycles after this, so
        // the demand's S1->rsp read of `lineReg`/`missPred` (the INHIBITED/poison bypass
        // path) is never raced.
        when(prefetchArmed && missCacheable && !missPoison) {
          startPrefetch()
          goto(PF_REFILL)
        } otherwise {
          goto(IDLE)
        }
      }
    }

    // ══ Hoisted fill datapath — shared by the DEMAND and PREFETCH fills ═══════════
    // Single call site per Mem write port, which is a hard requirement, not style:
    // an earlier version of the icache-burst-fault fix split the two beats across two
    // FSM states each with its own `.write()` call site, and that DID synthesize a real
    // second write port -- SpinalHDL's `MultiPortWritesSymplifier` then failed to
    // blackbox it during simulation elaboration. One call site, muxed by a register.

    // ---- refill: AR + beat accumulation into lineReg ----
    refillDone := False
    refillErr  := False
    when(refillActive) {
      // Refill from the PHYSICAL line base (missPA, the translated PA); identity
      // when the MMU is off (missPA == missPC).
      val lineBase = missPA & ~U(63, 32 bits)
      when(!arSent) {
        axi.ar.valid         := True
        axi.ar.payload.addr  := lineBase
        // Routed by ID (design doc §1.2/§7.1): a prefetch's beats and a demand's beats
        // must never be confusable. They cannot be outstanding together in this design,
        // but the ID is still distinct so the SoC L2's `id_busy_c` front door does not
        // serialise a prefetch behind an unrelated demand of the same ID, and so a bus
        // monitor can attribute traffic.
        axi.ar.payload.id    := Mux(refillIsPf,
                                    U(AxiIds.I_PREFETCH, AxiIds.ID_W bits),
                                    U(AxiIds.I_DEMAND,   AxiIds.ID_W bits))
        axi.ar.payload.len   := U(1, 8 bits)
        axi.ar.payload.size  := U(5, 3 bits)
        axi.ar.payload.burst := Axi4.burst.INCR
        when(axi.ar.ready) { arSent := True }
      }

      axi.r.ready := True
      when(axi.r.valid) {
        // Task #211: a non-OKAY response (SLVERR/DECERR — genuinely unmapped or
        // erroring physical memory) on EITHER beat of this 2-beat line burst means
        // there is no real data to cache. Still absorb the beat into `lineReg`
        // (UNCONDITIONAL — a register, not a shared array, so it is safe), but latch
        // the error so the FSM routes to FAULT (demand) or silently discards
        // (prefetch, rule P2) once the burst completes.
        //
        // dataMem is NOT written here, per-beat, at all (icache-burst-fault-fix): a
        // legal AXI ordering returns beat 0 OKAY and only then beat 1 SLVERR, so at
        // beat-0-write time nothing yet knows the burst will fault, and beat 0's real
        // data would already have been spliced into `dataMem(victimWay)` -- corrupting
        // whatever OTHER address that round-robin-selected way is still validly
        // resident for. The ACTUAL array commit is therefore deferred to the predecode
        // dwell below, reached only when the ENTIRE burst is confirmed OKAY.
        val respErr = axi.r.payload.resp =/= Axi4.resp.OKAY
        when(respErr) { missBusFault := True }
        when(beatCnt === U(0, 1 bits)) {
          lineReg(255 downto 0)   := axi.r.payload.data
        } otherwise {
          lineReg(511 downto 256) := axi.r.payload.data
        }
        beatCnt := beatCnt + 1
        when(axi.r.payload.last) {
          refillDone := True
          refillErr  := missBusFault || respErr
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
              // Slice I3: remember whether this line arrived by prefetch. Set for a
              // prefetch fill, CLEARED for a demand fill of the same way/set (so a
              // demand-filled line never spuriously re-triggers the "useful prefetch"
              // heuristic).
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
