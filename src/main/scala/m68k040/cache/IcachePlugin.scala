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

  val axiCfg = Axi4Config(addressWidth = 32, dataWidth = 256, idWidth = 2)

  // ---- logic Area (built during build phase) ----
  val logic = during build new Area {

    // ---- FetchService ports: plain directionless Stream/Flow (NOT slave/master) ----
    val cmdPort       = Stream(FetchCmd())
    val rspPort       = Flow(FetchRsp())
    val axi           = master(Axi4ReadOnly(axiCfg))
    val invalidateAll = in Bool()

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
    val predMem = Seq.fill(ways)(Mem(Bits(PRED_BITS_PER_LINE bits), sets))
    // Data: synchronous-read BRAM. 2 beats x 64 sets = 128 entries per way.
    val dataMem = Seq.fill(ways)(Mem(Bits(256 bits), sets * beatsPerLine))
    // Valid bits: register array, cleared by invalidateAll
    val valids  = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))
    // Round-robin victim pointer per set
    val victim  = Vec.fill(sets)(RegInit(U(0, wayBits bits)))

    // DEBUG (icache-corruption-fix task, temporary — mirrors DcachePlugin's
    // ldS1Valid/stS2Valid.simPublic() DEBUG hooks): exposes the raw shared arrays
    // for a directed test to assert an UNRELATED way's tag/data/pred content is
    // byte-for-byte unchanged by a same-set INHIBITED miss. No-op for synthesis.
    for (w <- 0 until ways) { tagMem(w).simPublic(); predMem(w).simPublic(); dataMem(w).simPublic() }
    valids.simPublic()

    // ---- invalidateAll: priority clear of all valid bits ----
    when(invalidateAll) {
      for (w <- 0 until ways; s <- 0 until sets) valids(w)(s) := False
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

    // ---- FSM ----
    val fsm = new StateMachine {
      val IDLE      = new State with EntryPoint
      val REFILL    = new State
      val PREDECODE = new State
      val REPLAY    = new State
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
      IDLE.whenIsActive {
        activePc      := cmdPort.payload.pc
        // Depth-2 accept: gate ONLY on the ITLB resolve (no single-outstanding block).
        // The T-stage is freed every IDLE cycle (consume below), so a new cmd may enter
        // it the same cycle. NOTE: a MISS this cycle leaves IDLE (goto REFILL); the
        // accept is still permitted (cmdPort.ready is combinational and we are still in
        // IDLE), and the same-cycle-accepted translation is held in the T-stage and
        // re-consumed after the refill returns to IDLE — so it is NOT lost.
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
          tValid := False   // consumed this cycle (a same-cycle accept re-sets it True)

          // Register the RAW per-way predMem entries (way-mux + window-decode deferred
          // to S1 keyed off s1Way). On a fault placeholder the entries are zeroed
          // (windowPred(0) == the old getZero placeholder).
          when(tFault) {
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
          } otherwise {
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
            goto(REFILL)
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

      // ----- REFILL: issue AXI AR; collect 2 R beats into dataMem -----
      REFILL.whenIsActive {
        activePc := missPC
        // Refill from the PHYSICAL line base (missPA, the translated PA); identity
        // when the MMU is off (missPA == missPC).
        val lineBase = missPA & ~U(63, 32 bits)

        when(!arSent) {
          axi.ar.valid         := True
          axi.ar.payload.addr  := lineBase
          axi.ar.payload.id    := U(0, 2 bits)
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
          // (UNCONDITIONAL — see below), but latch the error so the FSM routes to
          // FAULT instead of PREDECODE once the burst completes (mirrors
          // DcachePlugin's REFILL respErr handling, task #189). NOTE (updated by the
          // icache-corruption-fix / icache-burst-fault-fix tasks): the ORIGINAL #211
          // comment here claimed the dataMem write was "harmless — the line's valid
          // bit is never set on this path, so it can never be read back as a hit" —
          // that reasoning was WRONG whenever the round-robin victim pointer aliases
          // onto a way that IS valid for some other address. dataMem is no longer
          // written from this state at all (see below) — the actual array commit is
          // deferred to PREDECODE (which now dwells 2 cycles for exactly this reason
          // — see its comment below), reached only once the WHOLE burst's pass/fail
          // is known, closing both the per-beat case (icache-corruption-fix)
          // and the cross-beat case where an earlier OK beat is committed before a
          // later beat's error is known (icache-burst-fault-fix).
          val respErr = axi.r.payload.resp =/= Axi4.resp.OKAY
          when(respErr) { missBusFault := True }
          // Task icache-burst-fault-fix (review of 156cf6b): dataMem is NO LONGER
          // written here, per-beat, at all. The 156cf6b fix gated the per-beat write
          // under `missBusFault`/`respErr` evaluated AT THAT BEAT — which correctly
          // suppresses the write for a beat that itself errors, or any beat AFTER an
          // earlier beat in the same burst errored (missBusFault, once latched,
          // carries forward). It does NOT protect the opposite, equally legal AXI
          // ordering: beat 0 returns OKAY (respErr=False, missBusFault still False)
          // and gets written for real, and ONLY THEN does beat 1 (the LAST beat)
          // return SLVERR/DECERR — at the moment beat 0 is written, nothing yet knows
          // the burst will fault. The overall refill still correctly routes to FAULT
          // (below) with tagMem/predMem/valids untouched, but beat 0's real data was
          // already spliced into `dataMem(victimWay)` — corrupting that way exactly
          // like the original 69a867c bug, just triggered by a mid-burst AXI error
          // instead of a cache-mode setting, and NOT caught by the missBusFault gate
          // because the fault isn't known yet at write time.
          //
          // Fix: mirror how tagMem/predMem/valids already dodge this — defer the
          // ACTUAL array write until the full burst's pass/fail is known. `lineReg`
          // (below) stays the UNCONDITIONAL per-beat accumulator (already safe: a
          // register, not a shared array) and is now the ONLY source for the real
          // dataMem commit, which happens over PREDECODE's 2-cycle dwell (see its
          // comment below) — reached ONLY via REFILL's `otherwise { goto(PREDECODE) }`
          // below, i.e. only when the ENTIRE burst (both beats) is confirmed OKAY. A
          // burst that faults on any beat routes to FAULT instead, which never visits
          // PREDECODE, so dataMem is byte-for-byte untouched for that refill — same
          // discipline FAULT already gave tagMem/predMem/valids.
          when(beatCnt === U(0, 1 bits)) {
            lineReg(255 downto 0)   := axi.r.payload.data
          } otherwise {
            lineReg(511 downto 256) := axi.r.payload.data
          }
          beatCnt := beatCnt + 1
          when(axi.r.payload.last) {
            when(missBusFault || respErr) { goto(FAULT) } otherwise { goto(PREDECODE) }
          }
        }
      }

      // ----- FAULT: deliver a one-shot bus-error fault response; no allocation -----
      // Task #211: reached only via REFILL's non-OKAY AXI response. PREDECODE (the
      // tag/pred/valid/dataMem writes) is skipped entirely — no line is allocated,
      // matching the D-side no-allocate-on-error policy — and the victim pointer is
      // NOT advanced (this way was never actually filled).
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

      // ----- PREDECODE: classify line, write predMem + tag/valid -----
      PREDECODE.whenIsActive {
        activePc := missPC
        val words  = lineReg.subdivideIn(16 bits)
        // Per-word predecode. A full-format indexed EA's length depends on its EXTENSION
        // word: at op+1 (an EA-first op) or op+2 (a line-0 immediate / static bit-op, whose
        // EA ext follows a 1-word imm/bit word). Pass words(i+1) + words(i+2) (0 past the
        // line end — a full-format opword whose ext word spills to the next line is the
        // inherent per-line predecode boundary).
        //
        // F5 FIX (deep-audit 2026-07-11): the OLD code zero-filled the spilled word and
        // relied on a "handled on re-frame" comment for a re-frame mechanism that never
        // existed anywhere in the tree — a zero-filled ext word reads as brief-format
        // (bit8=0), so any full-format op landing at this exact per-line boundary silently
        // mis-framed as brief (same silent-corruption class as F1), and nothing ever
        // corrected it. There is no general "wait for the next line" mechanism here (predecode
        // runs per-line, independently, before the next line may even be fetched), so —
        // mirroring how this predecoder already treats any OTHER out-of-scope EA (reject ->
        // COMPLEX, the assembler's illegal/refetch path) — we now tell `classify` explicitly
        // that a boundary-zero-filled word is NOT real data (`extWValid`/`extW2Valid`, below).
        // `classify` then refuses to guess brief-vs-full for anything that would need to read
        // that word's content (mode 6 / mode7-reg3 full-format detection) and instead rejects
        // (COMPLEX) — a safe trap instead of a silent mis-frame. `i` is a plain Scala Int
        // here (this whole predecode is elaborated once per line-word, 32 instances), so
        // "is word i+1/i+2 past the line end" is a compile-time constant, not new hardware.
        // task #153 (ported-tests memind cluster): also pass a 3rd lookahead word (op+3) —
        // needed ONLY to correctly frame a line-0 .L-immediate op with a full-format
        // mem-indirect destination (the 2-word .L immediate pushes the EA's first ext word
        // from op+2 to op+3, one word beyond the original 2-word lookahead). `words` is the
        // WHOLE cache line, already resident in `lineReg` this same cycle (see the F5 comment
        // above) — words(i+3) costs nothing new in hardware, just a wider static mux inside
        // `classify` itself. Same F5 boundary discipline: unavailable (line-end) -> `classify`
        // falls back to its pre-existing "assume brief" framing for this specific shape (NOT
        // the F5 reject-as-COMPLEX doctrine — see `classify`'s extW3 comment for why).
        val nWords = words.length
        val chunks = Vec((0 until nWords).map(i =>
          PredecodeWord.classify(words(i),
            if (i + 1 < nWords) words(i + 1) else B(0, 16 bits),
            if (i + 2 < nWords) words(i + 2) else B(0, 16 bits),
            if (i + 3 < nWords) words(i + 3) else B(0, 16 bits),
            extWValid  = i + 1 < nWords,
            extW2Valid = i + 2 < nWords,
            extW3Valid = i + 3 < nWords)))
        val packed = chunks.asBits
        // Task (I-side cacheMode wiring, mirrors DcachePlugin task P1.4): an
        // INHIBITED-mode fetch never allocates a line — no tag/valid write, no
        // victim-pointer advance (this way is not consumed; the same victim way is
        // tried again on the NEXT real allocation to this set).
        //
        // Task icache-corruption-fix (review of 69a867c): predMem's write is now
        // ALSO gated under doAllocate (it previously was NOT — the primary bug this
        // task fixes: the shared per-way array can alias onto a way that is still
        // VALID/resident for some other address via the round-robin victim pointer,
        // and an unconditional write there silently corrupts that other way's
        // predecode while its tag/valid stay untouched). `missPred` latches `packed`
        // UNCONDITIONALLY below (mirrors DcachePlugin's `missLine`) so REPLAY can
        // still deliver an INHIBITED line's predecode directly, entirely bypassing
        // the (for that case, never-written) predMem array — see the S1->rsp bypass
        // mux (`s1FromMiss`) above and REPLAY below.
        val doAllocate = missCacheable
        missPred := packed
        // Task icache-burst-fault-fix: dataMem's commit is deferred from REFILL to
        // here (see the REFILL comment above) — reached only when the FULL 2-beat
        // burst was confirmed OKAY, using `lineReg` (the safe, register-backed
        // accumulator) as the source instead of the live `axi.r.payload.data`.
        //
        // PREDECODE now DWELLS for 2 cycles (`commitBeat` below) so the two beats
        // commit through a SINGLE `dataMem(w).write(...)` call site — i.e. one
        // physical write port per way, address/data MUXED by `commitBeat` — instead
        // of two simultaneous writes in one cycle (which would need a genuine 2nd
        // write port on what must stay a single-write-port BRAM; an earlier version
        // of this fix split beat-0/beat-1 across two FSM states, PREDECODE+COMMIT,
        // each with its own `.write()` call site, and that DID synthesize a real
        // second write port — SpinalHDL's `MultiPortWritesSymplifier` then failed to
        // blackbox it during simulation elaboration. One call site, muxed by a
        // register, avoids the hazard entirely — mirrors REFILL's own original
        // single-write-port-per-beat discipline). predMem/tagMem/valids/victim are
        // unaffected by this — they were already single-call-site/single-cycle and
        // only fire once, gated below by `commitBeat === 0`.
        when(commitBeat === U(0, 1 bits)) {
          for (w <- 0 until ways) {
            when(victimWay === U(w, wayBits bits)) {
              when(doAllocate) {
                predMem(w).write(missSet, packed)
                tagMem(w).write(missSet, missTag)
                valids(w)(missSet) := True
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
                Mux(commitBeat === U(0, 1 bits), lineReg(255 downto 0), lineReg(511 downto 256)))
            }
          }
        }
        commitBeat := commitBeat + 1
        when(commitBeat === U(1, 1 bits)) {
          commitBeat := U(0, 1 bits)   // reset for the NEXT refill's PREDECODE dwell
          goto(REPLAY)
        }
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
        s1PredEntries := replayPredEntry
        s1FromMiss := !missCacheable

        goto(IDLE)
      }
    }

    // FetchService accessors
    def cmd: Stream[FetchCmd] = cmdPort
    def rsp: Flow[FetchRsp]   = rspPort
  }

  // ---- FetchService trait implementation ----
  override def cmd: Stream[FetchCmd] = logic.cmdPort
  override def rsp: Flow[FetchRsp]   = logic.rspPort
}
