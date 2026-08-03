package m68k040.cache

import m68k040.isa.Size
import m68k040.services.DTranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

/** Synthesizable VIPT L1 data cache, mode-aware per the page's MMU cache attribute
  * (WRITETHROUGH / COPYBACK / INHIBITED).
  *
  * Geometry: 128 sets, 4 ways, 16-byte lines (8 KiB total). One 128-bit AXI beat
  * == one whole line (len=0).
  *   - LOAD: VIPT 2-cycle read (S0 launch BRAM tag+data read + translate; S1
  *     tag-compare against the REGISTERED tag-read, way-mux, byte-lane extract).
  *     Miss -> single-beat refill -> replay.
  *   - STORE: physical (SQ already translated). RMW the line if it hits.
  *     WRITETHROUGH/INHIBITED: ALWAYS issue an AXI write, no allocate on a miss
  *     (original behavior, unchanged). COPYBACK hit (Task P4.1): resolves ENTIRELY
  *     on-chip -- merge + set the line's dirty bit + local ack, zero AXI traffic.
  *     COPYBACK miss: write-allocate (Task P4.2).
  *
  * FMax (P0.1): ALL D-cache RAM reads are SYNCHRONOUS so Vivado infers BRAM
  * (RAMB36) instead of async distributed-RAM (LUTRAM / RAMD64E):
  *   - `dataMem` + `tagMem` are write + readSync ONLY (no readAsync) per way ->
  *     simple-dual-port BRAM (1 write + 1 sync-read).
  *   - The load data-read and the store-RMW old-line-read SHARE the one sync-read
  *     port (the cache is single-outstanding per the LS pipe; an explicit load>store
  *     arbiter defers the store-read by a cycle on the rare overlap).
  *   - Hit/miss is resolved ONE CYCLE LATER (in S1, against the registered tag-read
  *     vs the registered ppn-tag) — latency-agnostic; lock-step absorbs the +1.
  * Valids: register array. Victim: register array. */
class DcachePlugin extends FiberPlugin with DcacheService {

  private val geo     = CacheGeometry(cacheBytes = 8192, lineBytes = 16, ways = 4,
                                      indexingPolicy = CacheIndexingPolicy.Vipt)
  geo.requireViptSafe(4096, "L1D")
  private val ways    = geo.ways
  private val sets    = geo.sets
  private val tagBits = geo.tagBits          // 21
  private val setBits = geo.indexBits        // 7
  private val offBits = geo.offsetBits       // 4
  private val wayBits = log2Up(ways)

  val axiCfg = Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = 4)

  val logic = during build new Area {

    // ---- service ports (plain directionless Stream/Flow) ----
    val loadCmdPort = Stream(DLoadCmd())
    loadCmdPort.valid.simPublic(); loadCmdPort.ready.simPublic(); loadCmdPort.payload.simPublic()
    val loadRspPort = Flow(DLoadRsp())
    loadRspPort.valid.simPublic(); loadRspPort.payload.simPublic()
    val loadBusyReg = Bool()
    val storePort   = Flow(DStoreCmd())
    storePort.valid.simPublic(); storePort.payload.simPublic()  // debug-only (ported-tests cluster 11 trace)
    val storeAckReg = Bool()
    storeAckReg.simPublic()   // test-visibility only (P4.1 COPYBACK-hit directed test: no
                               // AXI B to observe on that path); no-op for synthesis
    val storeErrReg = Bool()   // Task P1.4: 1-cycle pulse, non-OKAY B alongside storeAckReg
    storeErrReg.simPublic()
    val axi         = master(Axi4(axiCfg))

    // ---- translation (D-side TLB) ----
    // The LS EU is the translate-at-execute requester: it DRIVES `xlate.req` (the
    // access VPN, write?, supervisor?) — for both loads (presented on loadCmd) and
    // stores (SQ-alloc paddr). The cache only READS `xlate.rsp`: it forms the load
    // hit-tag from `rsp.ppn` (consistent because the LS EU drives req.vpn from the
    // same vaddr it puts on loadCmd) and gates load acceptance on `rsp.ready` (a
    // DTLB miss holds it low while the walker runs -> the load is not accepted and
    // the LS EU stalls on its existing single-outstanding back-pressure path).
    val xlate = host[DTranslationService]

    // ---- storage (sync-read BRAM: write + readSync ONLY, no readAsync) ----
    val dataMem = Seq.fill(ways)(Mem(Bits(128 bits), sets))
    // tagMem is write+readSync only (see above) but its narrow width (tagBits ~
    // 20b x 128 sets) leads Vivado to infer IMPLIED DISTRIBUTED RAM (RAM64M8 LUTRAM)
    // instead of BRAM, unlike the wider dataMem which gets BRAM by default. This is
    // the exact structure the placer's congestion dump names (ldS1Tag_reg / tagMem_
    // spinal_port1 nets, iter_100_CongestedCLBsAndNets.txt) -- force BRAM (95% free
    // budget) to both decongest the hot corridor and remove ~192 LUTRAM LUTs/way.
    val tagMem  = Seq.fill(ways)(Mem(UInt(tagBits bits), sets).addAttribute("ram_style", "block"))
    val valids  = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))
    val dirtys  = Vec.fill(ways)(Vec.fill(sets)(RegInit(False)))
    dirtys.simPublic()   // test-visibility only (P4.1 directed tests); no-op for synthesis
    val victim  = Vec.fill(sets)(RegInit(U(0, wayBits bits)))

    // ---- single muxed data/tag write port per way (refill + store-write) ----
    val wrEn    = Vec.fill(ways)(False)
    val wrSet   = Vec.fill(ways)(U(0, setBits bits))
    val wrData  = Vec.fill(ways)(B(0, 128 bits))
    val wrTagEn = Vec.fill(ways)(False)             // tag write (refill only)
    val wrTag   = Vec.fill(ways)(U(0, tagBits bits))
    for (w <- 0 until ways) {
      dataMem(w).write(wrSet(w), wrData(w), wrEn(w))
      tagMem(w).write(wrSet(w), wrTag(w), wrTagEn(w))
    }

    // ---- shared synchronous read port per way (S0 launch -> S1 result) ----
    // Drives BOTH dataMem.readSync (line) AND tagMem.readSync (hit-detect) at the
    // SAME set address — the load and the store-RMW old-line read always read the
    // same single set, and they are arbitrated load>store onto this one port below
    // so each Mem has exactly one sync-read (=> simple-dual-port BRAM).
    val rdSet = UInt(setBits bits)
    val rdEn  = Bool()
    rdSet := U(0, setBits bits)
    rdEn  := False
    val rdData = Vec(dataMem.map(_.readSync(rdSet, rdEn)))
    val rdTag  = Vec(tagMem.map(_.readSync(rdSet, rdEn)))

    // ---- miss-state latches ----
    val missPaddr = Reg(UInt(32 bits))   // physical addr of the missing line (AXI refill base)
    val missSet   = Reg(UInt(setBits bits))
    val missTag   = Reg(UInt(tagBits bits))
    val missOff   = Reg(UInt(offBits bits))
    val missSize  = Reg(Size())
    // Task P1.4: an INHIBITED (MMIO) miss never allocates a line on refill (no
    // stale-data risk from caching a device register, no phantom "hit" on a
    // later access to the same address that may have changed underneath us).
    // Latched at miss-detect time so REFILL's allocate gate and REPLAY's
    // direct-response path both see the SAME cacheability the missing access
    // actually had (not a live signal that could have moved on).
    val missCmode = Reg(CacheMode())
    val victimWay = Reg(UInt(wayBits bits))
    victimWay.simPublic()   // test-visibility only (DcacheDrainRefillRaceSpec's
    // same-way-collision coincidence check); no-op for synthesis.
    // Task P4.3: the victim way's tag/line at the moment the miss/eviction
    // decision was made -- captured HERE (not re-read at EVICT_WR time) because the
    // shared read port that produced `rdTag(vw)`/`rdData(vw)` can be repointed at a
    // different set before EVICT_WR would otherwise get to it (see Finding 1 of
    // task-P4.3-report.md, folded in here via `pendingVictim*` below for the
    // store-drain-miss trigger). The dirty BIT itself is not separately latched
    // here (unlike tag/line) -- the miss-detect site's own `evictThis`/
    // `pendingVictimDirty` already fully resolves the goto(EVICT_WR)-vs-
    // goto(REFILL) decision at capture time and there is no later re-check that
    // would need a stable snapshot of it (no FSM state, once entered, ever
    // re-reads "was this a dirty victim" -- entering EVICT_WR at all already
    // encodes that fact).
    val victimEvictTag  = Reg(UInt(tagBits bits))
    val victimEvictLine = Reg(Bits(128 bits))
    val arSent    = Reg(Bool()) init False
    // Task #189 (bus error): latched across REFILL->REPLAY — did the AXI read
    // response for this refill come back with a non-OKAY resp (SLVERR/DECERR, e.g.
    // the test harness's BehavioralMemAgent DECERR-ing a genuinely-unmapped
    // address)? On an error the line is NOT allocated (no valid/tag/data write —
    // there is no real data to cache) and the fault rides through REPLAY into
    // `ldS1Fault` (a field that, before this task, was written by DcachePlugin but
    // never actually consumed downstream — LsEuPlugin's OWN MMU-fault detection
    // happens earlier, straight off `xlate.rsp.fault`, entirely bypassing this
    // field). Repurposed here as the (now real) BUS-fault carrier: LsEuPlugin's
    // aligned-load WAIT state reads it to raise a vector-2 access fault with
    // SSW.ATC=0 (a physical bus error, not an MMU/ATC-detected one) — see
    // LsEuPlugin's `captureFault(atc=false)` call site and ExceptionUnit's
    // `entryFaultAtc`.
    val missFault = Reg(Bool()) init False
    // Task P1.4: the just-fetched AXI beat for an INHIBITED miss, latched in
    // REFILL for REPLAY to deliver directly (no line was allocated, so a
    // re-launched "hit" read would only find garbage/stale BRAM contents).
    val missLine  = Reg(Bits(128 bits))
    // Task P4.2: does this in-flight refill service a store-drain miss (True)
    // or an ordinary load miss (False)? Distinguishes REPLAY's write-allocate
    // merge path from the load-fill/direct-response paths above.
    val refillReqIsStore = Reg(Bool()) init False

    // Store-drain-miss request, latched from store-S2 when a COPYBACK drain misses
    // (stS2Copyback && !stS2HitAny). Held until the (shared, single) refill engine
    // picks it up; a same-cycle load miss takes priority (mirrors this file's
    // existing load>store read-port precedent) -- the pending request simply stays
    // latched and is retried every IDLE cycle.
    val pendingStoreMiss   = RegInit(False)
    pendingStoreMiss.simPublic()   // test-visibility only (Fable5 Bug1 regression test:
    // times a racing load to land exactly on the IDLE cycle this is picked up).
    // No-op for synthesis.
    val pendingStorePaddr  = Reg(UInt(32 bits))
    val pendingMergeData   = Reg(Bits(128 bits))
    val pendingMergeStrb   = Reg(Bits(16 bits))
    // Task P4.3 Finding 1 fix: the victim way/dirty/tag/line for a store-drain-miss
    // trigger, captured HERE at store-S2 (the SAME cycle `stS2HitVec` reads
    // `rdTag`/`rdData` for `stS2Set` -- known-valid by construction, same read that
    // resolved `stS2HitAny=False`) rather than re-derived live at the eventual IDLE
    // pickup cycle. Re-deriving live is UNSAFE: `pendingStoreMiss` can be held for
    // many cycles (retried every IDLE cycle "until the refill engine is free"), and
    // the shared read port can legitimately be repointed at an unrelated set in the
    // meantime (e.g. REPLAY's own re-launch for a concurrent, unrelated, higher-
    // priority load miss) -- by pickup time `rdTag`/`rdData` could be reading a
    // completely different set. Locking the victim *decision* itself here (not just
    // its dirty/tag/line snapshot) is also strictly better than the old live
    // `victim(pSet)` re-read: it keeps the eviction writeback and the later
    // REFILL/REPLAY allocate consistent about which way is being replaced.
    val pendingVictimWay   = Reg(UInt(wayBits bits))
    val pendingVictimDirty = Reg(Bool())
    val pendingVictimTag   = Reg(UInt(tagBits bits))
    val pendingVictimLine  = Reg(Bits(128 bits))
    val storeAllocAckReg   = Bool(); storeAllocAckReg := False

    // Pulled forward from Task P4.5's own Step 1 (a plan-ordering bug: P4.5 declares
    // these but P4.2, which comes first, needs to drive them). P4.5's own eventual
    // dispatch must NOT re-declare these -- it only adds the sticky diagFaultValid/
    // diagFaultAddr/diagFaultResp/diagFaultKind registers and the WT-beat error site's
    // own pulse-driving `when` block.
    val diagFaultPulse     = Bool(); diagFaultPulse     := False
    val diagFaultPulseAddr = UInt(32 bits); diagFaultPulseAddr := U(0, 32 bits)
    val diagFaultPulseResp = UInt(2 bits);  diagFaultPulseResp := U(0, 2 bits)
    val diagFaultPulseKind = UInt(3 bits);  diagFaultPulseKind := U(0, 3 bits)

    // Review-added (kind=0/kind=1 collision detector -- see the sticky-latch comment
    // below near `diagFaultValid`): each site additionally raises its OWN
    // single-driver flag so a same-cycle collision between kind=0 (WT-beat, an
    // ordinary combinational `when`) and kind=1 (drain-miss write-allocate refill,
    // inside REPLAY's `whenIsActive`) can be asserted on directly, independent of
    // which one wins the shared diagFaultPulse*/Kind wires. Zero synth cost beyond
    // the assert itself (sim-only consumer).
    val diagFaultKind0Fires = Bool(); diagFaultKind0Fires := False
    val diagFaultKind1Fires = Bool(); diagFaultKind1Fires := False

    // ---- defaults ----
    loadCmdPort.ready := False
    axi.ar.valid := False
    axi.ar.payload.assignDontCare()
    axi.r.ready  := False
    axi.aw.valid := False
    axi.aw.payload.assignDontCare()
    axi.w.valid  := False
    axi.w.payload.assignDontCare()
    axi.b.ready  := True

    val busy = Reg(Bool()) init False
    loadBusyReg := busy

    // ---- load index/tag from cmd vaddr + PRE-TRANSLATED paddr ----
    // FMax: the physical tag comes from the requester's REGISTERED `loadCmd.paddr`
    // (translate-at-execute already resolved it into a register), NOT from the live
    // DTLB lookup `xlate.rsp.ppn`. VIPT: index on the page-invariant vaddr set bits;
    // tag on the physical bits. Hit-detect itself is deferred to S1 (registered).
    val cmdVaddr = loadCmdPort.payload.vaddr
    val cmdPaddr = loadCmdPort.payload.paddr
    val cmdSet   = cmdVaddr(offBits + setBits - 1 downto offBits)
    val cmdOff   = cmdVaddr(offBits - 1 downto 0)
    val cmdTag   = cmdPaddr(31 downto offBits + setBits)   // physical tag = paddr[31:offBits+setBits]

    // ---- LOAD S1 (registered hit-detect + response build) pipeline registers ----
    // The cycle after a load is accepted, the BRAM tag-read (rdTag) + data-read
    // (rdData) are valid. S1 resolves the hit against the REGISTERED ppn-tag + set,
    // way-muxes the data, lane-extracts, and (on a miss) starts the refill. The +1
    // cycle to start a refill is latency-agnostic (lock-step absorbs it).
    val ldS1Valid = RegInit(False)       // a load-read was launched into S1
    val ldS1Set   = Reg(UInt(setBits bits))
    val ldS1Tag   = Reg(UInt(tagBits bits))
    val ldS1Off   = Reg(UInt(offBits bits))
    val ldS1Size  = Reg(Size())
    // Task P1.4: the cacheability of the access that launched this S1 read. Gates
    // the hit vector below — an INHIBITED (MMIO) access must never observe a
    // stale resident alias left over from when the same physical line was
    // (validly) cached under a different cacheability, and must never itself
    // register a "hit" that would suppress the refill it architecturally needs.
    val ldS1Cmode = Reg(CacheMode())
    val ldS1Fault = Reg(Bool())
    val ldS1Paddr = Reg(UInt(32 bits))   // physical addr (refill base on a miss)
    ldS1Valid := False                   // default; armed on an accept below

    // S1 hit-detect against the REGISTERED tag-read vs the REGISTERED ppn-tag. The
    // compare's inputs are both registered (BRAM tag-read output + the latched ppn-
    // tag), so the response is driven COMBINATIONALLY in S1 — the SAME load latency
    // as the old async-LUTRAM hit-detect (1 cycle after accept), but the arc is now
    // a short registered-compare instead of the readAsync->compare->dataMem cone.
    // Task P1.4: `ldS1Cacheable` forces every way's hit bit low for an INHIBITED
    // access — always falls through to the miss/REFILL path below.
    val ldS1Cacheable = ldS1Cmode =/= CacheMode.INHIBITED
    val ldS1HitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      ldS1HitVec(w) := ldS1Cacheable && valids(w)(ldS1Set) && (rdTag(w) === ldS1Tag)
    val ldS1Hit     = ldS1HitVec.orR
    val ldS1HitWay  = OHToUInt(ldS1HitVec)
    // DEBUG (task #189 investigation, temporary): sim-only visibility.
    ldS1Valid.simPublic(); ldS1Set.simPublic(); ldS1Tag.simPublic(); ldS1Off.simPublic()
    ldS1Size.simPublic(); ldS1Hit.simPublic(); ldS1HitWay.simPublic()
    val ldS1Line    = rdData(ldS1HitWay)
    // Respond ONLY on a HIT (the fault flag rides along with the hit response,
    // matching baseline: `s1Fault := xlate.rsp.fault` was set ONLY in the hit branch).
    // A MISS — fault or not — falls through to REFILL below, exactly as baseline
    // (baseline's miss branch goes to REFILL regardless of xlate.rsp.fault; the
    // genuine non-resident-page fault is handled by the LS EU's own fault path, not
    // by short-circuiting a garbage cache response here).
    val ldS1Resp    = ldS1Valid && ldS1Hit

    // Task #189 (bus error): a REFILL that came back with a non-OKAY AXI response
    // never allocates the line (see REFILL below), so a normal ldS1Valid/hit
    // re-launch in REPLAY would MISS again and loop the refill forever. Instead
    // REPLAY drives this one-cycle pulse directly (bypassing ldS1Valid/Hit
    // entirely) to deliver a fault response for exactly one cycle. Declared here
    // (default False) and overridden by REPLAY below (later-assignment-wins).
    val busFaultResp = Bool(); busFaultResp := False
    busFaultResp.simPublic()   // DEBUG (task #189), temporary

    // Task P1.4: an INHIBITED miss never allocates a line (see REFILL below), so
    // — same shape as busFaultResp above — a normal ldS1Valid/hit re-launch in
    // REPLAY would MISS again forever. REPLAY drives this one-cycle pulse
    // directly instead, delivering the just-fetched AXI beat (`missLine`) without
    // ever touching the (never-written) BRAM.
    val inhibitedResp = Bool(); inhibitedResp := False
    inhibitedResp.simPublic()   // DEBUG, temporary (mirrors busFaultResp)

    loadRspPort.valid         := ldS1Resp || busFaultResp || inhibitedResp
    loadRspPort.payload.data  := Mux(inhibitedResp,
                                      DcacheByteLane.extract(missLine, missOff, missSize),
                                      DcacheByteLane.extract(ldS1Line, ldS1Off, ldS1Size))
    loadRspPort.payload.line  := Mux(inhibitedResp, missLine, ldS1Line)
    loadRspPort.payload.fault := Mux(busFaultResp, True, ldS1Fault)

    // ---- STORE write-through (PIPELINED RMW: S0 latch / S1 read / S2 merge+write) ----
    // FMax: the store RMW (old line readAsync + 16-lane byte-merge + write) used to
    // be a combinational cycle fronted by the far-placed exc-FSM/DTLB cone. With all
    // reads SYNC the old line + hit-tag are read from BRAM:
    //   store-S0 (on storePort.valid): latch the raw store payload into regs PHYSICALLY
    //     at the cache (a short route ending at a flop).
    //   store-S1 (next cycle): launch the SHARED sync read (old line + hit-tag) at the
    //     store's set — UNLESS the load FSM is using the read port this cycle (load
    //     priority); on conflict the store holds in S1 and retries next cycle.
    //   store-S2 (after the read lands): resolve the hit (registered tag-read), merge
    //     the registered old line with the store bytes, drive the dataMem write +
    //     latch the AXI write-through beat.
    // Single-outstanding: the producer never presents a 2nd store before this one's
    // AXI B (storeAck) lands, so S0/S1/S2 never overlap a SECOND STORE. It does NOT,
    // however, keep the cache arrays still across that window: the load-refill engine
    // (REFILL's allocate write, REPLAY's write-allocate merge) is completely
    // independent of the store side and can write valids/tagMem/dataMem at any point
    // between S0 and S2. Two consequences, both interlocked by `refillWriteHold`
    // (declared just above the load FSM below -- read its comment before touching
    // any of this):
    //   - a same-SET refill landing inside the S1..S2 window would invalidate the
    //     store's REGISTERED tag-read, so its S2 hit-detect would be stale;
    //   - a same-WAY refill (any set) landing on the S2 cycle itself would win the
    //     shared per-way array write port and silently DROP the store's own write.
    // `refillWriteHold` holds the refill side off in both cases (delay, never drop),
    // which is what makes the S2 hit-detect and the S2 array write correct.
    val stMergeReg = Reg(Bits(128 bits))
    val stStrbReg  = Reg(Bits(16 bits))
    val stAddrReg  = Reg(UInt(32 bits))
    val stAwDone   = Reg(Bool()) init True   // True == no store in flight
    val stWDone    = Reg(Bool()) init True
    // Task P4.3 AXI-hazard fix -- REVISION 3, the actual landed design. Two
    // earlier revisions were tried and BOTH proven unsafe by DcacheSpec's own new
    // AXI-hazard regression test (recorded here because the failure mode is
    // subtle enough that a future maintainer must not "simplify" this back to
    // either one):
    //   - Revision 1: fully shared stAddrReg/stMergeReg/stStrbReg/stAwDone/
    //     stWDone, EVICT_WR kickoff gated on store-idle, disambiguated via an AXI
    //     `id` tag with a "did I get preempted, retry" loop. FAILED: if
    //     EVICT_WR's AW handshakes before its W (or vice versa) and a store then
    //     overwrites the shared registers mid-pair, the AXI4 AW/W FIFO-ordering
    //     rule pairs the wrong address with the wrong data -- a genuine lost/
    //     misattributed write, not just a benign retry.
    //   - Revision 2: EVICT_WR given its OWN completion flags
    //     (`evictAwDone`/`evictWDone`, kept below) and drives axi.aw/axi.w
    //     directly from its own already-latched payload
    //     (`victimEvictTag`/`missSet`/`victimEvictLine`), gated OFF whenever
    //     `storeWantsAxi` (so it never physically contends with the store's own
    //     unconditional drive). This closes revision 1's register-corruption
    //     hole but NOT the underlying AXI4 ordering rule: this project's own AXI
    //     mem model (`AxiWriteEngine.update()`) pairs `aw`/`w` via plain
    //     per-channel FIFOs with NO per-master awareness -- if EITHER side's `w`
    //     (or `aw`) completes while ITS OWN matching `aw` (or `w`) is still
    //     outstanding, and the OTHER side's beat lands in the gap, the model
    //     pairs the two mismatched entries. Confirmed via a live signal trace
    //     (a temporary per-cycle aw/w/b valid/ready/id/addr printout, not kept
    //     in-tree -- see task-P4.3-P4.4-combined-report.md's "AXI-hazard fix:
    //     three revisions" section for the captured trace): EVICT_WR's `w`
    //     accepted before its `aw`; the store's `aw` then landed while EVICT's
    //     `w` was still the oldest unmatched entry -> the STORE's address got
    //     EVICT's data, and (symmetrically, later) EVICT's address got the
    //     STORE's data. `storeWantsAxi` protected EVICT_WR from the store's
    //     open pair, but nothing protected the store from EVICT_WR's own open
    //     pair -- a one-directional gate on a problem that is symmetric.
    //   - Revision 3 (landed): the SAME `storeWantsAxi`-style gate, applied in
    //     BOTH directions. `evictAxiPairOpen` (below) blocks the store-S2
    //     write-through path's own AW/W KICKOFF (not its array RMW write, not
    //     its cacheMode branching -- ONLY the AXI leg) for as long as EVICT_WR's
    //     own aw+w pair is genuinely incomplete, via a `pendingWtKickoff` latch
    //     (same shape as this file's existing `pendingStoreMiss` "retry every
    //     cycle until the shared resource is free" idiom). This is a
    //     deliberate, MINIMAL, BOUNDED exception to "the store side gets zero
    //     new stall logic": the window is exactly one eviction beat's own AW+W
    //     round trip (structurally the same shape as store-S1's own existing
    //     hold-and-retry on read-port contention), not a general busy/refill-
    //     duration stall, and it is the ONLY way to avoid the cross-attribution
    //     corruption above given a single shared physical AXI4 write port and
    //     this model's plain per-channel FIFO pairing -- proven necessary, not
    //     assumed, by two independently-failed attempts to avoid it.
    val evictAwDone = Reg(Bool()) init True   // True == no eviction beat in flight
    val evictWDone  = Reg(Bool()) init True
    // Does the store-S2 write-through path want (or currently hold) the physical
    // AXI write channels THIS cycle? Store-S2's own drive below is completely
    // unconditional on this signal (it never checks it) -- this exists PURELY to
    // gate EVICT_WR's own drive off, so the two never physically collide.
    val storeWantsAxi = !stAwDone || !stWDone
    // The symmetric direction: is EVICT_WR's own aw+w pair currently open (one or
    // both legs not yet accepted)? Gates the store-S2 write-through path's own
    // AXI kickoff (see `pendingWtKickoff` at the S2 site and its consumer below).
    val evictAxiPairOpen = !evictAwDone || !evictWDone
    // Level-held "still need to actually kick off the AXI aw/w for a write-
    // through/inhibited drain" latch -- set by store-S2 (one cycle, a pulse)
    // INSTEAD OF directly setting stAwDone/stWDone False when `evictAxiPairOpen`
    // holds it off; consumed (retried every cycle) below, exactly mirroring
    // `pendingStoreMiss`'s existing shape.
    val pendingWtKickoff = RegInit(False)

    // ---- store-S0: PURE payload latch (the cache-boundary flop) ----
    val s0Valid   = RegInit(False)
    val s0Payload = Reg(DStoreCmd())
    s0Valid := False  // default; armed on a store-accept below
    when(storePort.valid) {
      s0Valid   := True
      s0Payload := storePort.payload
    }

    // ---- store-S1: launch the shared sync read (old line + hit-tag) ----
    // Decoded off the REGISTERED S0 payload. The read launches onto the shared
    // rdSet/rdEn port only when the load FSM is NOT using it (arbiter below); on a
    // conflict the store stays in S1 (s1Pending) and retries next cycle.
    val stS1Valid   = RegInit(False)      // a store is in S1 awaiting its read launch
    val stS1Payload = Reg(DStoreCmd())
    val stS1Set     = stS1Payload.paddr(offBits + setBits - 1 downto offBits)
    val stS1Off     = stS1Payload.paddr(offBits - 1 downto 0)
    val stS1Tag     = stS1Payload.paddr(31 downto offBits + setBits)
    stS1Valid := False  // default; armed below when S0 advances / S1 holds

    // ---- store-S2: registered old line + hit + merge + write ----
    val stS2Valid   = RegInit(False)      // the store read has landed; merge+write now
    val stS2Payload = Reg(DStoreCmd())
    val stS2Set     = stS2Payload.paddr(offBits + setBits - 1 downto offBits)
    val stS2Off     = stS2Payload.paddr(offBits - 1 downto 0)
    val stS2Tag     = stS2Payload.paddr(31 downto offBits + setBits)
    stS2Valid := False  // default; armed when S1's read launches
    // DEBUG (task #189 investigation, temporary): sim-only visibility into the
    // store RMW hit/miss decision. simPublic is a no-op for synthesis.
    stS2Valid.simPublic(); stS2Payload.simPublic()

    // Store-S2 hit-detect + cacheability decode. Declared HERE (rather than next to
    // the S2 merge/write block far below, where it used to live) purely so that
    // `refillWriteHold` -- which must know whether store-S2 will drive the shared
    // per-way array write port THIS cycle, and into WHICH way -- can be built before
    // the load FSM that consumes it. Inputs are all registers/BRAM outputs available
    // at this point (valids, rdTag, stS2Payload); no behavioural change from the move.
    val stS2HitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      stS2HitVec(w) := valids(w)(stS2Set) && (rdTag(w) === stS2Tag)
    stS2HitVec.simPublic()   // DEBUG (task #189), temporary
    // Task P1.4: an INHIBITED store never touches the cache array (skip the RMW line
    // write entirely) — only the AXI write-through beat is unconditional.
    // Task P4.1: a COPYBACK-hit store resolves ENTIRELY on-chip (RMW + dirty-bit, no
    // AXI beat at all); WRITETHROUGH (hit or miss) and INHIBITED are unchanged.
    val stS2Inhibited = stS2Payload.cacheMode === CacheMode.INHIBITED
    val stS2Copyback  = stS2Payload.cacheMode === CacheMode.COPYBACK
    val stS2HitAny    = stS2HitVec.orR
    // EXACT predicate for "store-S2 drives wrEn/wrSet/wrData this cycle" — must stay
    // bit-identical to the guard on the S2 RMW write block below.
    val stS2ArrayWrite = stS2Valid && !stS2Inhibited && stS2HitAny
    stS2ArrayWrite.simPublic()   // test-visibility only (DcacheDrainRefillRaceSpec's
    // same-way-collision coincidence check); no-op for synthesis.

    // S0 -> S1: advance the latched payload into S1.
    when(s0Valid) {
      stS1Valid   := True
      stS1Payload := s0Payload
    }

    // ---- shared read-port arbitration (FMax: keep the live load-accept/DTLB cone OUT
    // of the high-fanout BRAM read-address net) ----
    // The store drives the read address as the BASE (off the REGISTERED stS1Payload —
    // a clean flop->BRAM-address arc). The LOAD FSM below OVERRIDES rdSet/rdEn LAST
    // (last-assignment wins) on a load-accept / REPLAY, so the load keeps priority.
    // Crucially the load-vs-store select on the rdSet net is ONLY `loadCmdPort.fire /
    // REPLAY` (the same cone baseline already had on the dataMem read address) — the
    // store base adds NO arbiter cone to that fo=high net. The "did the store actually
    // get the port?" question (loadUsesPort) feeds ONLY the low-fanout stS2Valid
    // control register, NOT the BRAM address — breaking the post-route critical path
    // (DTLB-walker -> loadCmdPort.ready -> arbiter -> tag/dataMem read-address).
    when(stS1Valid) {
      rdSet := stS1Set
      rdEn  := True
    }

    // ---- Drain-vs-refill array-write-port interlock (same-set OR same-way,
    // Task P4.4, design doc "Drain-vs-refill same-set/same-way interlock", also
    // folded through EVICT_WR's own eventual REFILL/REPLAY transition per this
    // combined task) ----
    // A store drain's S1 tag-read (registers stS1Set) through its S2 write (stS2Set)
    // is a 2-cycle window during which the store's OWN hit-detect is against a
    // REGISTERED tag-read. A concurrent load-refill/write-allocate array write into
    // the SAME set (a DIFFERENT line — same-line overlaps are already covered by the
    // SQ's own `sameLine` stall, see StoreQueue.scala) landing inside that window
    // would go unnoticed by the store's stale registered tag-read: S2 would then
    // merge into a way the refill just re-tagged (write-through: cached-line
    // corruption; copyback: a lost store — refill-priority silently discards the
    // store's only write). Hold (delay, NEVER drop) the refill/eviction side's
    // array write for up to 2 cycles while a same-set store drain is in its S1/S2
    // window — the store side gets ZERO new stall logic; it is unconditionally
    // unaffected by this signal.
    //
    // CRITICAL (post-P4.4 review fix) -- the SAME-WAY, DIFFERENT-SET term. The two
    // set-comparison terms above are NOT sufficient to protect the array write port,
    // because `wrEn/wrSet/wrData` is ONE port PER WAY, SHARED ACROSS ALL SETS (see
    // their declaration at the top of this file) — not one port per (way, set). A
    // refill/write-allocate write to (way w, set A) and a store-S2 RMW hit-write to
    // (way w, set B != A) both drive wrEn(w)/wrSet(w)/wrData(w) on the same cycle;
    // the FSM's assignments elaborate LAST (SpinalHDL StateMachine bodies run as a
    // `prePopTask`), so the refill silently WINS and the store's own array write is
    // DROPPED — no error, no retry, no fault.
    //
    // Under COPYBACK that is a silent MEMORY-corruption channel, not merely a lost
    // cache update: the S2 hit arm's dirty-bit write (`dirtys(w)(stS2Set) := True`)
    // is a SEPARATE register array, indexed by a DIFFERENT set, so it is NOT dropped
    // by the collision — the line ends up marked dirty holding STALE (pre-store)
    // data, which EVICT_WR will later faithfully write back to memory as if it were
    // the store's own result.
    //
    // The fix is deliberately NARROW: `stS2HitVec(victimWay)` fires only when the
    // store's actual hit way is literally the way the refill is about to allocate.
    // Widening the set comparison to "any set" instead would stall every refill
    // against every unrelated store anywhere in the cache, for a collision that is
    // harmless in the overwhelming majority of cases. Indexing the hit VECTOR (rather
    // than comparing an OHToUInt-decoded way index) also stays correct if the hit
    // vector were ever non-one-hot — the S2 write arm itself writes every hit way.
    // Same hold-and-retry philosophy: the refill is delayed, the store is never
    // dropped.
    //
    // Why this can't livelock (CORRECTED — the previous version of this comment
    // claimed the hold "lasts at most the single cycle store-S2 is valid", which
    // is FACTUALLY WRONG: under a back-to-back store stream `stS2Valid` (and
    // therefore `stS1Valid`) can be True on every consecutive cycle indefinitely
    // — trace their own `:=` assignments above, there is no structural 1-cycle
    // bound. The real invariant is a CONTRACT on the store producer, not a
    // width-of-signal fact: `DcacheService.store` (an unbuffered `Flow`, no
    // backpressure) is driven single-outstanding and ack-gated by the SQ/LS EU
    // — at most one store is ever "in flight" contesting this hold at a time,
    // and that store's ack always eventually arrives regardless of the hold
    // (WT ack rides the AXI B response, which this hold never gates; a
    // COPYBACK-hit ack comes from `cbHitAckReg`, likewise ungated; a
    // COPYBACK-miss store contributes `stS2HitAny = False`, so it can't assert
    // `stS2ArrayWrite`/hold its own refill in the first place). Once that one
    // store's ack lands the next store (if any) is a fresh contender, so the
    // hold can never accumulate an unbounded queue behind it. THIS MAKES THE
    // HOLD'S LIVENESS A CONTRACT the store side must preserve, not a
    // structural guarantee derived from this file alone — if the store side
    // ever grows a real multi-outstanding queue, this invariant (and this
    // whole no-livelock argument) would need re-examination.
    val refillWriteHold = (stS1Valid && (stS1Set === missSet)) ||
                          (stS2Valid && (stS2Set === missSet)) ||
                          // NOTE: this 3rd (same-way) term only means anything
                          // while `victimWay` holds a currently-relevant value,
                          // i.e. during REFILL/REPLAY immediately after a
                          // miss-detect latched it. In IDLE `victimWay` is
                          // stale from the previous miss and this term is
                          // harmless-but-meaningless there (nothing consumes
                          // `refillWriteHold` in IDLE today) — don't reuse it
                          // in an IDLE-adjacent context without re-checking.
                          (stS2ArrayWrite && stS2HitVec(victimWay))

    // ---- Task P5.4: cache-maintenance (CPUSH/CINV) walk, forward declarations ----
    // Declared HERE (ahead of the load FSM) because the load FSM's own load-accept
    // arm must read `maintBusyReg` -- while a maintenance walk owns the shared array
    // read port, NO new load may be accepted (see the maintenance Area far below for
    // the full mutual-exclusion argument; the short version is that this gate is what
    // makes the load FSM provably unable to drive rdSet/rdEn, kick off an eviction,
    // or open an AXI write pair for the entire duration of the walk).
    val maintBusyReg = RegInit(False)
    maintBusyReg.simPublic()   // test-visibility (the P5.4 quiesce regression test);
                                // no-op for synthesis

    // POST-P5.4-REVIEW forward declarations. Same forward-declaration pattern as
    // `maintBusyReg` directly above (and as `busFaultResp`/`inhibitedResp` earlier in
    // this file): declared HERE with a default, driven LATER by the `maint` Area
    // (last-assignment-wins), because consumers declared BEFORE that Area -- EVICT_WR,
    // the store-S2 write-through kickoff, the store S1->S2 arbiter and the load FSM's
    // latched-drain-miss pickup -- all need to read them.
    //
    // WHY these exist at all: the P5.4 review found the walk's mutual-exclusion story
    // was ONE-DIRECTIONAL. `WRB`'s own `axiFree` already deferred the WALK against the
    // store's and the eviction's open AXI write pairs, but nothing deferred the store
    // or the eviction against the WALK's own open pair -- which is EXACTLY the P4.3
    // "revision 1/2" failure mode recorded in EVICT_WR's REVISION HISTORY comment
    // below (AXI4's AW/W FIFO-ordering rule cross-attributes two interleaved open
    // pairs regardless of `id`). Same for the shared array READ port: the walk's
    // `READ` state wins last-assignment-wins on `rdSet`/`rdEn`, but the store side had
    // no way to KNOW it lost the port and so advanced S1->S2 on a silently-wrong read.
    //
    /** The maintenance walk's own AXI aw+w pair is open (one or both legs not yet
      * accepted). The symmetric counterpart of `storeWantsAxi`/`evictAxiPairOpen`.
      *
      * Declared WITHOUT a default (unlike the two below): the `maint` Area drives it
      * UNCONDITIONALLY and exactly once, so a default here would be a complete
      * assignment overlap (`PhaseCheck_noLatchNoOverride` rejects it). Leaving it
      * undriven-here also means a future refactor that drops that single driver fails
      * loudly as a missing-driver error rather than silently reading `False` and
      * re-opening the one-directional-gate hole this exists to close. */
    val maintAxiPairOpen = Bool()
    /** The maintenance walk drives the shared `rdSet`/`rdEn` read port THIS cycle
      * (its `READ` state). Exactly mirrors the load FSM's own `loadUsesPort`, and is
      * consumed by the SAME S1->S2 store arbiter, so a store that loses the port
      * holds in S1 and retries instead of advancing on a stolen read. */
    val maintUsesPort = Bool(); maintUsesPort := False
    /** The walk is past its quiesce `WAIT` and is actively touching the arrays / AXI.
      * Deliberately FALSE during `WAIT` -- see the load FSM's latched-drain-miss arm
      * for why gating that arm on `maintBusyReg` (which IS set during `WAIT`) instead
      * would be a genuine deadlock. Driven by the `maint` Area's own `walking`. */
    val maintWalking = Bool(); maintWalking := False

    // ---- LOAD FSM (OVERRIDES the shared read port with PRIORITY over the store) ----
    val fsm = new StateMachine {
      val IDLE     = new State with EntryPoint
      val EVICT_WR = new State
      val REFILL   = new State
      val REPLAY   = new State

      // In-flight gate: do not accept a new load while one is resolving in S1 (its
      // combinational response is live this same cycle). Single-outstanding on the
      // load side — identical to the old `s1Valid || loadRspPort.valid` accounting.
      val inFlight = ldS1Valid

      // Whether the load FSM uses the shared read port THIS cycle (set in the
      // load-accept and REPLAY arms below). The store arbiter reads this to defer.
      val loadUsesPort = False

      IDLE.whenIsActive {
        busy := False
        // Accept a load only when translation is RESOLVED this cycle (TLB hit or
        // identity). On a DTLB miss `rsp.ready` is False while the walker runs, so
        // the load is not accepted and the LS EU stays on its existing single-
        // outstanding back-pressure path (re-driving loadCmd.valid) until it hits.
        // Also held off while a store-drain miss is about to be picked up this same
        // cycle (the `elsewhen(pendingStoreMiss)` arm below): if a load were
        // accepted here too, next cycle the FSM is servicing the store in REFILL
        // and a load MISS has no handler (the miss-latch block above only runs
        // inside IDLE.whenIsActive) -- `ldS1Valid` would self-clear with no
        // response ever sent, hanging the LS EU forever. A load HIT would still
        // resolve fine, but there's no way to know that before accepting.
        // Task P5.4: ALSO held off for the whole duration of a cache-maintenance
        // walk. The walk drives the shared rdSet/rdEn port and the AXI write
        // channels; refusing new loads here is what keeps the load FSM pinned in
        // IDLE (no accept -> no rdSet drive, no miss -> no EVICT_WR entry -> no
        // eviction AXI pair) so the two can never contend. Bounded by construction
        // (a walk is at most sets*ways iterations), and the LS EU is architecturally
        // blocked from issuing anyway (the walk only ever runs under excActive).
        loadCmdPort.ready := !inFlight && xlate.rsp.ready && !pendingStoreMiss && !maintBusyReg
        when(loadCmdPort.fire) {
          // Launch the BRAM tag+data read for this set; resolve hit/miss in S1.
          rdSet        := cmdSet
          rdEn         := True
          loadUsesPort := True
          ldS1Valid    := True
          ldS1Set      := cmdSet
          ldS1Tag      := cmdTag
          ldS1Off      := cmdOff
          ldS1Size     := loadCmdPort.payload.size
          ldS1Cmode    := loadCmdPort.payload.cacheMode
          ldS1Fault    := xlate.rsp.fault
          ldS1Paddr    := cmdPaddr
        }
        // S1 resolution of a launched load read. A HIT drives the response
        // combinationally (ldS1Resp above, fault riding along). A MISS — fault or
        // not — starts the refill, EXACTLY as baseline (whose miss branch went to
        // REFILL unconditionally; only the hit branch carried xlate.rsp.fault).
        when(ldS1Valid && !ldS1Hit) {
          // Miss: latch miss-state and start the refill (the +1-cycle deferral
          // relative to the old async hit-detect is latency-agnostic). A same-
          // cycle load miss takes priority over a pending store-drain miss
          // (mirrors this file's existing load>store read-port precedent).
          val vw = victim(ldS1Set)
          missPaddr := ldS1Paddr
          missSet   := ldS1Set
          missTag   := ldS1Tag
          missOff   := ldS1Off
          missSize  := ldS1Size
          missCmode := ldS1Cmode
          victimWay := vw
          // Task P4.3: capture the victim's dirty/tag/line HERE — the shared read
          // port was pointed at ldS1Set exactly one cycle ago (the accept cycle
          // that launched this S1 read; load>store arbitration guarantees nothing
          // else could have repointed it since, see the arbitration comment
          // above), so `rdTag(vw)`/`rdData(vw)` are combinationally valid for
          // ldS1Set right now.
          //
          // OWN FINDING (beyond the brief): only an access that will actually
          // ALLOCATE the way (mirrors REFILL's own `doAllocate` gate just below,
          // `missCmode =/= INHIBITED`) has a real "victim" worth evicting. An
          // INHIBITED (MMIO) load ALWAYS reaches this branch (ldS1Cacheable forces
          // every hit bit low, so INHIBITED never hits by construction) yet never
          // allocates a way (doAllocate=False in REFILL) — round-robin `victim()`
          // still returns SOME way here regardless, and on a warm cache that way
          // will often be dirty. Without this guard EVERY MMIO load whose
          // round-robin victim happened to be dirty would spuriously kick off a
          // whole eviction writeback of an UNRELATED, still-resident line through
          // the shared AXI registers this task also just made shareable with the
          // store-drain path — for zero benefit (the way is never reallocated,
          // its dirty bit is never cleared here either). Gating on the SAME
          // condition REFILL already uses for `doAllocate` keeps "a victim is
          // chosen" and "a victim is evicted" consistent.
          val evictThis = dirtys(vw)(ldS1Set) && (ldS1Cmode =/= CacheMode.INHIBITED)
          victimEvictTag  := rdTag(vw)
          victimEvictLine := rdData(vw)
          refillReqIsStore := False
          arSent    := False
          busy      := True
          // evictAwDone/evictWDone reset ONLY when actually entering EVICT_WR --
          // resetting them unconditionally here would strand them at False
          // forever for every miss that skips straight to REFILL (a clean
          // victim), permanently blocking every future store's own AXI kickoff
          // via `evictAxiPairOpen` (a real bug caught by this task's own
          // baseline DcacheSpec regression run, not by the new hazard test).
          when(evictThis) {
            evictAwDone := False; evictWDone := False
            goto(EVICT_WR)
          } otherwise { goto(REFILL) }
        } .elsewhen(pendingStoreMiss && !maintWalking) {
          // A pending COPYBACK drain-miss (latched at store-S2, Step 2 above) --
          // held and retried every IDLE cycle until the refill engine is free.
          //
          // POST-P5.4-REVIEW (`&& !maintWalking`): this arm picks up a PREVIOUSLY
          // LATCHED miss, so -- unlike the new-load accept above, which is gated by
          // `loadCmdPort.ready`'s own `!maintBusyReg` -- nothing stopped it pulling
          // the load FSM out of IDLE into EVICT_WR/REFILL in the middle of an active
          // maintenance walk, racing the walk for the shared array read port and (on
          // a dirty victim) for the AXI write channels. Holding the latch is safe by
          // construction: `pendingStoreMiss` is already designed to be held and
          // retried indefinitely (see its own decl comment).
          //
          // GATE CHOICE -- `maintWalking`, NOT `maintBusyReg` (deviation from the fix
          // brief, deliberate, and load-bearing): `maintBusyReg` is ALSO set during
          // the walk's `WAIT` state, and `WAIT` only advances when `dcIdleForMaint`
          // -- which itself requires `!pendingStoreMiss`. Gating on `maintBusyReg`
          // would therefore make a maintenance command that arrives while a drain
          // miss is still latched wait forever for a latch that can no longer ever be
          // picked up: a REAL, reachable DEADLOCK (see DcacheSpec's
          // "...requested while a COPYBACK drain-miss is still pending..." regression
          // test, which hangs with the `maintBusyReg` form). `maintWalking` is False
          // during `WAIT`, so the pending miss drains normally, `busy` (a register,
          // conservatively True for the whole EVICT_WR/REFILL/REPLAY excursion and
          // the first IDLE cycle after it) holds `WAIT` off meanwhile, and the walk
          // starts only once the load FSM is genuinely back in IDLE.
          val pSet = pendingStorePaddr(offBits + setBits - 1 downto offBits)
          val pTag = pendingStorePaddr(31 downto offBits + setBits)
          missPaddr := pendingStorePaddr
          missSet   := pSet
          missTag   := pTag
          missOff   := pendingStorePaddr(offBits - 1 downto 0)
          missSize  := Size.LONG
          missCmode := CacheMode.COPYBACK
          // Task P4.3 Finding 1 fix: use the victim way/dirty/tag/line LOCKED at
          // store-S2 time (`pendingVictim*`) instead of re-deriving live here —
          // see that decl's comment for why a live re-derive is unsafe.
          victimWay       := pendingVictimWay
          victimEvictTag  := pendingVictimTag
          victimEvictLine := pendingVictimLine
          refillReqIsStore := True
          pendingStoreMiss := False
          arSent    := False
          busy      := True
          when(pendingVictimDirty) {
            evictAwDone := False; evictWDone := False
            goto(EVICT_WR)
          } otherwise { goto(REFILL) }
        }
      }

      // ---- EVICT_WR: dirty-victim writeback before allocate (Task P4.3) ----
      //
      // REVISION HISTORY (recorded because the FIRST attempt here was proven
      // unsafe by this task's OWN new regression test, not merely superseded by
      // taste): revision 1 reused the store-S2 write-through's shared AXI
      // registers (stAddrReg/stMergeReg/stStrbReg/stAwDone/stWDone), gated
      // EVICT_WR's kickoff on the store machine being idle, and used a distinct
      // AXI `id` (2 vs. the store's 1) plus a "did I get preempted, retry"
      // detection loop to stay safe against a LATER unrelated store barging in
      // mid-transaction. That design has a genuine AXI-PROTOCOL hole: if
      // EVICT_WR's own AW handshakes (evictAwDone-equivalent true) before its W
      // does, and a store then (per the mandate: unconditionally, no stall)
      // overwrites stMergeReg/stWDone with ITS OWN payload before EVICT_WR's W
      // goes out, the eventual W beat carries the STORE's data but AXI4's
      // AW/W FIFO-ordering rule pairs it with EVICT_WR's ALREADY-SENT (older,
      // unmatched) AW — the eviction's target address gets the STORE's data, and
      // the store's own target address gets NOTHING (its own AW is now the
      // NEWER, still-unmatched one, waiting on a W that never comes because the
      // model already consumed the one W beat sent). `DcacheSpec`'s "AXI-hazard
      // regression... (offset=N)" test caught this directly (an intermittent,
      // literal "the store's own byte never lands in memory" failure) before
      // this file was committed. Fixed here in revision 2: EVICT_WR gets its own
      // completion flags (`evictAwDone`/`evictWDone` above) and drives the
      // PHYSICAL `axi.aw`/`axi.w` directly from its own ALREADY-LATCHED payload
      // (`victimEvictTag`/`missSet`/`victimEvictLine` — no new address/data
      // registers needed, only the two done-flags), gated OFF on any cycle the
      // store wants the bus (`storeWantsAxi`) so the two NEVER physically
      // contend for `axi.aw`/`axi.w` on the same cycle — store-S2's own drive
      // (below) is completely unconditional/untouched, exactly as before this
      // task; EVICT_WR is the ONLY side that holds and retries. B-response
      // routing still uses `id` (2 for EVICT_WR, 1 for the store, unchanged) to
      // tell the two apart when both a store's and an eviction's B could
      // legitimately be in flight together (the physical AW/W presentation is
      // now mutually exclusive, but their B acks can still arrive out of order
      // once both have been accepted) — EVICT_WR simply ignores any B that isn't
      // tagged id=2 and keeps waiting; it never needs to guess or retry a
      // kickoff because nothing can now corrupt its own payload registers.
      EVICT_WR.whenIsActive {
        busy := True
        val evictAddr = (victimEvictTag ## missSet ## U(0, offBits bits)).asUInt
        // POST-P5.4-REVIEW: `&& !maintAxiPairOpen` extends revision 3's bidirectional
        // gate to the THIRD AXI write issuer this file has grown -- the cache-
        // maintenance walk's `WRB` writeback. `WRB` already gates itself off on
        // `storeWantsAxi || evictAxiPairOpen`; without the term added here that gate
        // was one-directional again, i.e. literally revision 2's proven-unsafe shape.
        when(!evictAwDone && !storeWantsAxi && !maintAxiPairOpen) {
          axi.aw.valid         := True
          axi.aw.payload.addr  := evictAddr
          axi.aw.payload.id    := U(2, 4 bits)
          axi.aw.payload.len   := U(0, 8 bits)
          axi.aw.payload.size  := U(4, 3 bits)
          axi.aw.payload.burst := Axi4.burst.INCR
          when(axi.aw.ready) { evictAwDone := True }
        }
        when(!evictWDone && !storeWantsAxi && !maintAxiPairOpen) {
          axi.w.valid        := True
          axi.w.payload.data := victimEvictLine
          axi.w.payload.strb := B(0xFFFF, 16 bits)
          axi.w.payload.last := True
          when(axi.w.ready) { evictWDone := True }
        }
        when(evictAwDone && evictWDone) {
          when(axi.b.valid && axi.b.payload.id === U(2, 4 bits)) {
            when(axi.b.payload.resp =/= Axi4.resp.OKAY) {
              diagFaultPulse     := True
              diagFaultPulseAddr := evictAddr
              diagFaultPulseResp := axi.b.payload.resp.asUInt.resize(2)
              diagFaultPulseKind := U(2, 3 bits)   // kind=2: dirty-victim eviction writeback
            }
            // Task P4.4's `refillWriteHold` (declared above the FSM) does NOT need
            // to gate THIS transition itself -- EVICT_WR never performs an array
            // write of its own (only the AXI aw/w writeback above); it is REFILL's
            // OWN allocate write (entered next, already gated via
            // `axi.r.ready := !refillWriteHold`) that is the actual array-write
            // site needing the interlock, and that gate applies regardless of
            // which state was active immediately before REFILL.
            goto(REFILL)
          }
          // else: either no B this cycle, or it's the store's own (id=1) --
          // ignore it and keep waiting for OUR OWN id=2 ack.
        }
      }

      REFILL.whenIsActive {
        busy := True
        // Refill from the PHYSICAL line base (the access was already translated;
        // missPaddr holds the resolved physical address). Under identity == vaddr.
        val lineBase = (missPaddr(31 downto offBits) ## U(0, offBits bits)).asUInt
        when(!arSent) {
          axi.ar.valid         := True
          axi.ar.payload.addr  := lineBase
          axi.ar.payload.id    := U(0, 4 bits)
          axi.ar.payload.len   := U(0, 8 bits)
          axi.ar.payload.size  := U(4, 3 bits)  // 16 bytes
          axi.ar.payload.burst := Axi4.burst.INCR
          when(axi.ar.ready) { arSent := True }
        }
        // Task P4.4: hold off ACCEPTING the R beat (rather than accepting it and
        // then trying to hold the array write separately) while `refillWriteHold`
        // is asserted — the slave/model simply keeps the beat presented (normal
        // AXI backpressure) until a same-set store drain's S1/S2 window closes.
        // This delays (never drops) the eventual allocate write by construction:
        // `doAllocate`'s wrEn/wrTagEn/valids/dirtys writes below only ever fire on
        // the SAME cycle the beat is actually accepted (`axi.r.fire`), which by
        // then is guaranteed `!refillWriteHold`.
        axi.r.ready := !refillWriteHold
        when(axi.r.fire) {
          // Task #189: a non-OKAY response (SLVERR/DECERR — genuinely unmapped or
          // erroring physical memory) carries NO real data. Do NOT allocate the
          // line (no valid/tag/data write — matches the existing no-allocate-on-
          // miss store policy just below in this file) and latch the fault for
          // REPLAY to report instead of re-launching a (bogus) hit.
          // Task P1.4: an INHIBITED (MMIO) access ALSO never allocates, even on a
          // clean OKAY response — a cacheable line must never be created from a
          // deliberately-uncacheable access (would let a later access to the same
          // physical line observe a stale device read as if it were a real cache
          // hit). `missLine` always latches the beat (fault or not, allocated or
          // not) so REPLAY's inhibitedResp path has real data to hand back.
          val respErr    = axi.r.payload.resp =/= Axi4.resp.OKAY
          val doAllocate = !respErr && (missCmode =/= CacheMode.INHIBITED)
          when(doAllocate) {
            for (w <- 0 until ways) when(victimWay === U(w, wayBits bits)) {
              wrEn(w)    := True
              wrSet(w)   := missSet
              wrData(w)  := axi.r.payload.data
              wrTagEn(w) := True
              wrTag(w)   := missTag
              valids(w)(missSet) := True
              dirtys(w)(missSet) := False   // a fresh allocate is always clean until
                                             // the write-allocate merge below (or a
                                             // later hit) dirties it
            }
            victim(missSet) := victim(missSet) + 1
          }
          missLine  := axi.r.payload.data
          missFault := respErr
          goto(REPLAY)
        }
      }

      REPLAY.whenIsActive {
        busy := True
        when(missFault) {
          when(refillReqIsStore) {
            // Drain-miss refill errored: NO allocation happened (doAllocate was
            // False). Task P4.5 wires this into the async diagnostic-fault channel
            // -- never architectural, never precise (a COPYBACK drain is always
            // fast-path by construction: !fast requires the page NOT be cacheable).
            diagFaultPulse     := True
            diagFaultPulseAddr := missPaddr
            diagFaultPulseResp := U(2, 2 bits)   // SLVERR-class; Task P4.5 refines
            diagFaultPulseKind := U(1, 3 bits)   // kind=1: drain-miss write-allocate refill
            diagFaultKind1Fires := True          // review-added collision detector
            storeAllocAckReg   := True           // still ack the (already-retired) drain
          } otherwise {
            // Task #189: the refill's AXI response errored — no line was allocated
            // (REFILL above skipped the wr*/valids writes), so there is nothing to
            // "replay" as a hit. Delivered directly via busFaultResp (one pulse);
            // do NOT touch ldS1Valid/rdSet/rdEn here (a real re-launch would MISS
            // again forever — the line is still, correctly, not resident).
            busFaultResp := True
          }
          goto(IDLE)
        } elsewhen(missCmode === CacheMode.INHIBITED) {
          // Task P1.4: no line was allocated (doAllocate was False above) — there
          // is nothing to "replay" as a hit. Deliver the just-fetched beat
          // directly, once, exactly like busFaultResp's one-pulse shape.
          inhibitedResp := True
          goto(IDLE)
        } elsewhen(refillReqIsStore) {
          // Task P4.4: this is an array-write site (write-allocate merge) — hold
          // here (do NOT goto(IDLE), retry the SAME cycle's work next cycle)
          // while `refillWriteHold` is asserted, exactly like REFILL's own
          // allocate write above.
          when(!refillWriteHold) {
            // Merge the store's bytes into the just-allocated line, mark it dirty,
            // ack the drain LOCALLY -- entirely off the retire timeline (this is a
            // post-commit event; the fast-path store retired long ago at SQ-alloc).
            val curBytes = missLine.subdivideIn(8 bits)
            val mBytes   = pendingMergeData.subdivideIn(8 bits)
            val newBytes = Vec(Bits(8 bits), 16)
            for (i <- 0 until 16) newBytes(i) := Mux(pendingMergeStrb(i), mBytes(i), curBytes(i))
            for (w <- 0 until ways) when(victimWay === U(w, wayBits bits)) {
              wrEn(w)   := True
              wrSet(w)  := missSet
              wrData(w) := newBytes.asBits
              dirtys(w)(missSet) := True
            }
            storeAllocAckReg := True
            goto(IDLE)
          }
          // else: held — stay in REPLAY, retry next cycle (retry-don't-drop).
        } otherwise {
          // Re-launch the read for the just-filled line; resolve as a guaranteed hit
          // into the response register one cycle later via the ldS1 path. (No array
          // WRITE here — just a read relaunch — so `refillWriteHold`, which guards
          // the write port, does not apply to this branch.)
          rdSet        := missSet
          rdEn         := True
          loadUsesPort := True
          ldS1Valid    := True
          ldS1Set      := missSet
          ldS1Tag      := missTag
          ldS1Off      := missOff
          ldS1Size     := missSize
          ldS1Cmode    := missCmode
          ldS1Fault    := False
          ldS1Paddr    := missPaddr
          goto(IDLE)
        }
      }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Task P5.4: cache-maintenance (CPUSH / CINV) walk engine
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // Services a Line/Page/All-scope push (writeback-dirty) and/or invalidate over
    // the D-cache arrays, one (set, way) per iteration.
    //
    // ── SAFETY: why this does NOT reuse the store path's AXI registers ──────────
    // The obvious implementation -- share `stAddrReg`/`stMergeReg`/`stAwDone`/
    // `stWDone` with the store-S2 write-through path and disambiguate the responses
    // with a distinct AXI `id` -- is EXACTLY "revision 1" of this file's Task P4.3
    // eviction writeback, which was PROVEN UNSAFE by a live regression failure (see
    // the long revision-history comment on `evictAwDone` above, ~L344, and EVICT_WR's
    // own ~L691). Two independent holes: (a) a concurrent writer overwriting the
    // shared address/data registers between our own AW and W hands the wrong data to
    // the wrong address, and (b) AXI4's AW/W FIFO-ordering rule cross-attributes two
    // interleaved open pairs regardless of `id` (ids demux the B RESPONSE, they do
    // NOT pair AW with W). An `id`-tagged shared-register design is not made safe by
    // the tag.
    //
    // The claim that "excActive guarantees the LS pipe is idle, so sharing is fine"
    // is ALSO false as stated, and was verified false directly: ExceptionUnit's
    // commit-time sysOp path (`sysTrigger`, which CPUSH/CINV take) used to go
    // straight to `S_APPLY` the very next cycle, with NO drain wait -- unlike
    // exception ENTRY (`E_DRAIN`) and RTE (`R_DRAIN`), both of which explicitly wait
    // on `sqDrained` before touching any store-adjacent state. `excActive` stops the
    // LS EU issuing anything NEW; it does nothing about an older COMMITTED store
    // still draining out of the StoreQueue (commit and drain are decoupled by
    // design), nor about a load-refill / dirty-victim eviction already accepted
    // before the flush landed. No sysOp before CPUSH/CINV ever touched the D-cache
    // AXI write registers, so this exposure is genuinely new.
    //
    // This engine is therefore protected THREE ways, deliberately redundantly:
    //   1. Its own dedicated AXI completion flags (`maintAwDone`/`maintWDone`) and
    //      its own latched address/data payload (`wbAddrReg`/`wbLineReg`) -- it never
    //      touches `stAwDone`/`stWDone`/`stAddrReg`/`stMergeReg`. Even if the
    //      quiescence invariant below were somehow violated, the failure mode is
    //      bounded contention, never silent register cross-corruption.
    //   2. Its AXI drive is gated off on any cycle either other writer holds an open
    //      pair (`storeWantsAxi || evictAxiPairOpen`), the same bidirectional-gate
    //      shape as P4.3's proven revision 3, so the three never physically present
    //      overlapping AW/W pairs. Deadlock-free because the walk only ever OPENS its
    //      pair when both of those are already false, and `maintBusyReg` then keeps
    //      them false (no new load accepted -> no eviction; no store can arrive).
    //   3. It refuses to START until `dcIdleForMaint` (below) is locally true -- a
    //      self-check, independent of any promise made by the caller. The caller
    //      (ExceptionUnit's new `S_DRAIN` state) separately waits on `sqDrained` AND
    //      on this same signal exported as `maintQuiesced`, so a walk cannot even be
    //      requested mid-drain.
    //
    // ── Array read port ────────────────────────────────────────────────────────
    // CORRECTED POST-P5.4-REVIEW. The ORIGINAL framing of this paragraph was: "the
    // walk drives rdSet/rdEn; while maintBusyReg no load can be accepted and the load
    // FSM is pinned in IDLE, and no store can be in S1, so nothing else drives the
    // port." That was WRONG ON BOTH COUNTS as shipped, and (per this file's standing
    // convention, cf. EVICT_WR's REVISION HISTORY) the wrong version is recorded here
    // rather than silently overwritten:
    //   - "the load FSM is pinned in IDLE": `maintBusyReg` gated only the NEW-LOAD
    //     accept (`loadCmdPort.ready`). The IDLE state's OTHER exit arm -- picking up
    //     an ALREADY-LATCHED `pendingStoreMiss` -- was ungated, so the load FSM could
    //     still leave IDLE into EVICT_WR/REFILL mid-walk. Now gated on
    //     `!maintWalking` (see that arm).
    //   - "no store can be in S1": nothing structurally prevented it. `storePort` is
    //     an unbuffered `Flow` with no backpressure; `dcIdleForMaint` omitted the LIVE
    //     `storePort.valid` (fixed below), so a store presented on the very cycle
    //     `WAIT` fell through to `READ` entered the pipe anyway and reached S1 during
    //     the walk.
    //
    // The ACTUAL, now-true invariant is a two-layer one:
    //   (1) LAST-ASSIGNMENT-WINS on the physical net: the walk's `rdSet`/`rdEn` drives
    //       elaborate AFTER the load FSM's (StateMachine bodies run as `prePopTask` in
    //       creation order and this machine is created second) and after the store's
    //       S1 base drive, so while the walk's `READ` state is active the walk ALWAYS
    //       physically owns the port. That much was, and remains, true.
    //   (2) EVERY OTHER CONSUMER OF THAT READ NOW KNOWS IT LOST. That is the property
    //       that actually matters, and the one that was missing: a stolen read is only
    //       harmless if whoever lost it defers instead of silently consuming the wrong
    //       data. New loads are refused (`maintBusyReg` on `loadCmdPort.ready`); a
    //       latched drain-miss stays latched (`!maintWalking`); and a store that
    //       reaches S1 anyway holds in S1 and retries (`maintUsesPort`, consumed by
    //       the same S1->S2 arbiter that already handles `fsm.loadUsesPort`) instead
    //       of advancing to S2 to RMW-merge against the walk's set.
    // Layer (2) is defence in depth BEHIND `dcIdleForMaint`/`WAIT`, not a substitute
    // for it: the sim asserts below still hold that no store is in S0..S2 and the load
    // FSM is in IDLE for the whole walk. They prove the invariant rather than assume
    // it; layer (2) makes the failure a bounded stall rather than silent corruption if
    // it is ever violated.

    /** Every D-cache datapath resource the walk needs, genuinely idle THIS cycle.
      *
      * POST-P5.4-REVIEW: `!storePort.valid` is a required conjunct, not belt-and-
      * braces. Every OTHER term here is a REGISTER; `s0Valid` (the store's cache-
      * boundary flop) only rises the cycle AFTER a store is presented. Without the
      * live combinational term this signal read True with a store exactly one flop
      * away from entering the pipe, and `WAIT` fell through to `READ` on precisely
      * that cycle -- the store then ran its S0/S1/S2 concurrently with the walk. */
    val dcIdleForMaint = !busy && !ldS1Valid && !pendingStoreMiss && !pendingWtKickoff &&
                         !storePort.valid && !s0Valid && !stS1Valid && !stS2Valid &&
                         stAwDone && stWDone && evictAwDone && evictWDone
    dcIdleForMaint.simPublic()

    val maintWalkingDbg = Bool(); maintWalkingDbg.simPublic()

    val maintCmdPort = Flow(CacheMaintCmd())
    maintCmdPort.valid.allowOverride
    maintCmdPort.payload.flatten.foreach(_.allowOverride)
    // Explicit ZERO defaults rather than `assignDontCare()`: a don't-care default
    // both propagates X into the latch registers in sim and (per this project's
    // documented SpinalHDL gotchas) hides a testbench poke from consumers. Nothing
    // drives this yet -- Task P5.5's ExceptionUnit dispatch is the sole future driver.
    maintCmdPort.valid            := False
    maintCmdPort.payload.push       := False
    maintCmdPort.payload.invalidate := False
    maintCmdPort.payload.scope      := U(0, 2 bits)
    maintCmdPort.payload.sel        := U(0, 2 bits)
    maintCmdPort.payload.addr       := U(0, 32 bits)

    // 1-cycle completion pulse (registered: default-False every cycle, driven True
    // for exactly one cycle at the end of the walk).
    val maintDoneReg = RegInit(False)
    maintDoneReg := False
    maintDoneReg.simPublic()

    val maint = new Area {
      val cmd     = Reg(CacheMaintCmd())
      val walkSet = Reg(UInt(setBits bits))
      val lastSet = Reg(UInt(setBits bits))
      val curWay  = Reg(UInt(wayBits bits))
      // Dedicated AXI write completion flags + latched payload -- NEVER the store's.
      val maintAwDone = Reg(Bool()) init True
      val maintWDone  = Reg(Bool()) init True
      val wbAddrReg   = Reg(UInt(32 bits))
      val wbLineReg   = Reg(Bits(128 bits))
      // POST-P5.4-REVIEW: drives the FORWARD-DECLARED `maintAxiPairOpen` (above the
      // load FSM) rather than being a local val, so the store-S2 write-through kickoff
      // and EVICT_WR -- both declared earlier in the file -- can gate on it. Was a
      // local val, which is why the AXI write-pair gate was one-directional.
      maintAxiPairOpen := !maintAwDone || !maintWDone
      // "The walk is past its quiesce WAIT and is actively touching the arrays / AXI
      // this cycle." Distinct from `maintBusyReg` (which is deliberately also set
      // during WAIT, so loads are refused while we are still waiting for the datapath
      // to settle -- during WAIT a store IS legitimately still in the pipe; that is
      // the entire point of the state). The sim asserts below key on THIS signal.
      // POST-P5.4-REVIEW: now an ALIAS of the forward-declared `maintWalking` (same
      // net, same semantics) so the load FSM's latched-drain-miss arm can read it.
      val walking = maintWalking

      // Does this command touch the D-cache array at all? sel: 01=DC, 10=IC, 11=BC.
      // An IC-only (10) command has no DC work -- Task P5.5 pulses the I-cache's own
      // invalidate separately -- so it completes immediately instead of walking 512
      // (set, way) pairs to do nothing.
      def touchesDc(c: CacheMaintCmd): Bool = (c.sel === U(1, 2 bits)) || (c.sel === U(3, 2 bits))

      val sm = new StateMachine {
        val IDLE  = new State with EntryPoint
        val WAIT  = new State   // command latched; hold until the D-cache is quiesced
        val READ  = new State   // launch the shared-port read at walkSet
        val CHECK = new State   // registered tag/data landed; match + push/invalidate
        val WRB   = new State   // one matching dirty line's writeback beat
        val NEXTW = new State   // advance way, then set

        IDLE.whenIsActive {
          maintBusyReg := False
          when(maintCmdPort.valid) {
            val p      = maintCmdPort.payload
            val tgtSet = p.addr(offBits + setBits - 1 downto offBits)
            val isLine = p.scope === U(1, 2 bits)
            cmd     := p
            walkSet := Mux(isLine, tgtSet, U(0, setBits bits))
            lastSet := Mux(isLine, tgtSet, U(sets - 1, setBits bits))
            curWay  := 0
            when(touchesDc(p)) {
              maintBusyReg := True
              goto(WAIT)
            } otherwise {
              // IC/BC-only: no D-cache work at all. Complete immediately (still a
              // real, single completion pulse so the caller's handshake is uniform).
              maintDoneReg := True
            }
          }
        }

        // Self-check (point 3 above): never begin touching the arrays / AXI until the
        // D-cache datapath is genuinely idle, INDEPENDENT of the caller's own
        // S_DRAIN wait. In the intended flow this is already true on entry and WAIT
        // costs exactly one cycle.
        WAIT.whenIsActive {
          maintBusyReg := True
          when(dcIdleForMaint) { goto(READ) }
        }

        READ.whenIsActive {
          maintBusyReg := True
          walking := True
          rdSet := walkSet
          rdEn  := True
          // POST-P5.4-REVIEW: announce that WE own the shared read port this cycle,
          // exactly as the load FSM's own read-launching arms set `loadUsesPort`. The
          // store S1->S2 arbiter consumes both; without this, a store that reached S1
          // during the walk had its BRAM read silently redirected to `walkSet` (the
          // walk's drives elaborate last) yet still advanced to S2 believing its own
          // read had landed -- RMW-merging against the wrong line's data and
          // hit-detect. Now it holds in S1 and retries, same as against the load.
          maintUsesPort := True
          goto(CHECK)
        }

        CHECK.whenIsActive {
          maintBusyReg := True
          walking := True
          val target = cmd.addr
          // Line scope: this exact line (set already fixed to the target set, but
          // compare it anyway so the predicate reads standalone). Page scope: the
          // 4K page number == paddr[31:12]; with tag = paddr[31:11] that is
          // tag[tagBits-1:1]. A 4K page spans every set (256 lines over 128 sets),
          // so Page and All both walk the whole array. All scope: everything.
          val lineMatch = (walkSet === target(offBits + setBits - 1 downto offBits)) &&
                          (rdTag(curWay) === target(31 downto offBits + setBits))
          val pageMatch = rdTag(curWay)(tagBits - 1 downto 1) === target(31 downto 12)
          val scopeHit  = Mux(cmd.scope === U(1, 2 bits), lineMatch,
                          Mux(cmd.scope === U(2, 2 bits), pageMatch, True))
          val resident  = valids(curWay)(walkSet)
          val matches   = resident && scopeHit
          val isDirty   = dirtys(curWay)(walkSet)
          when(matches && cmd.push && isDirty) {
            // Latch OUR OWN writeback payload now (point 1 above) -- the AXI leg
            // never re-reads the shared array port.
            wbAddrReg   := (rdTag(curWay) ## walkSet ## U(0, offBits bits)).asUInt
            wbLineReg   := rdData(curWay)
            maintAwDone := False
            maintWDone  := False
            goto(WRB)
          } otherwise {
            when(matches && cmd.invalidate) {
              for (w <- 0 until ways) when(curWay === U(w, wayBits bits)) {
                valids(w)(walkSet) := False
                dirtys(w)(walkSet) := False
              }
            }
            goto(NEXTW)
          }
        }

        WRB.whenIsActive {
          maintBusyReg := True
          walking := True
          // Gated off whenever either other AXI write issuer holds an open pair
          // (point 2 above). Both are guaranteed false here in the intended flow;
          // the gate is defence in depth, and is deadlock-free because neither can
          // newly become true while maintBusyReg holds.
          val axiFree = !storeWantsAxi && !evictAxiPairOpen
          when(!maintAwDone && axiFree) {
            axi.aw.valid         := True
            axi.aw.payload.addr  := wbAddrReg
            axi.aw.payload.id    := U(4, 4 bits)
            axi.aw.payload.len   := U(0, 8 bits)
            axi.aw.payload.size  := U(4, 3 bits)
            axi.aw.payload.burst := Axi4.burst.INCR
            when(axi.aw.ready) { maintAwDone := True }
          }
          when(!maintWDone && axiFree) {
            axi.w.valid        := True
            axi.w.payload.data := wbLineReg
            axi.w.payload.strb := B(0xFFFF, 16 bits)
            axi.w.payload.last := True
            when(axi.w.ready) { maintWDone := True }
          }
          when(maintAwDone && maintWDone) {
            // Demux the B by OUR OWN id (=== 4, fail-closed -- same reasoning as
            // `storeBAck`'s `=== 1` comment below): ignore anything else and keep
            // waiting. `axi.b.ready` is held True globally.
            when(axi.b.valid && axi.b.payload.id === U(4, 4 bits)) {
              when(axi.b.payload.resp =/= Axi4.resp.OKAY) {
                // Imprecise DIAGNOSTIC only, per the design's locked decision that a
                // writeback error is a diagnostic crash and not an architectural trap.
                diagFaultPulse     := True
                diagFaultPulseAddr := wbAddrReg
                diagFaultPulseResp := axi.b.payload.resp.asUInt.resize(2)
                diagFaultPulseKind := U(3, 3 bits)   // kind=3: CPUSH maintenance writeback
              }
              // The line is now clean in memory. CPUSH-without-invalidate keeps it
              // resident-and-clean; the invalidating form drops it.
              for (w <- 0 until ways) when(curWay === U(w, wayBits bits)) {
                dirtys(w)(walkSet) := False
                when(cmd.invalidate) { valids(w)(walkSet) := False }
              }
              goto(NEXTW)
            }
          }
        }

        NEXTW.whenIsActive {
          maintBusyReg := True
          walking := True
          when(curWay === U(ways - 1, wayBits bits)) {
            curWay := 0
            when(walkSet === lastSet) {
              maintDoneReg := True
              maintBusyReg := False
              goto(IDLE)
            } otherwise {
              walkSet := walkSet + 1
              goto(READ)
            }
          } otherwise {
            curWay := curWay + 1
            goto(READ)
          }
        }
      }
    }

    maintWalkingDbg := maint.walking

    // Review-added: prove (rather than assume) the mutual-exclusion invariants the
    // walk's array-port and AXI safety rest on. Sim-only; no synthesis cost.
    GenerationFlags.simulation {
      assert(!(maint.walking && (s0Valid || stS1Valid || stS2Valid)),
        "DcachePlugin: a STORE was in the S0..S2 pipeline while a cache-maintenance walk was actively running -- the walk's caller pulsed maintCmd without waiting for the StoreQueue to drain (ExceptionUnit S_DRAIN / DcacheService.maintQuiesced), AND the walk's own WAIT self-check was bypassed",
        FAILURE)
      assert(!(maint.walking && !fsm.isActive(fsm.IDLE)),
        "DcachePlugin: the load/refill FSM left IDLE during a cache-maintenance walk -- it can drive the shared rdSet/rdEn port and open an eviction AXI pair, both of which the walk assumes are exclusively its own",
        FAILURE)
      assert(!(maintAxiPairOpen && (storeWantsAxi || evictAxiPairOpen)),
        "DcachePlugin: the cache-maintenance writeback's AXI aw/w pair was open at the same time as the store's or the eviction's -- AXI4 AW/W FIFO ordering cross-attributes overlapping pairs regardless of id (the proven-unsafe P4.3 revision-1/2 failure mode)",
        FAILURE)
    }

    // ---- store read-port arbiter CONTROL (no BRAM-address logic here) ----
    // The store's read ADDRESS was already driven (as the base) before the FSM; the
    // FSM overrode rdSet/rdEn if a load used the port this cycle. So here we ONLY
    // resolve whether the store actually GOT the port: if the load took it
    // (loadUsesPort), the store's read was overridden -> hold in S1 and retry; else
    // its read launched -> advance to S2. This `loadUsesPort` consumer is a small
    // control register (stS2Valid/stS1Valid), NOT the high-fanout BRAM read-address.
    //
    // POST-P5.4-REVIEW: `maintUsesPort` joins `fsm.loadUsesPort` here. The cache-
    // maintenance walk's `READ` state is a THIRD driver of `rdSet`/`rdEn`, elaborated
    // after both of the others, so it likewise silently overrides the store's read --
    // but nothing told this arbiter, so the store advanced to S2 and RMW-merged
    // against the walk's set (wrong old data AND wrong hit-detect). Same hold-and-
    // retry response as against the load: delay, never corrupt.
    when(stS1Valid) {
      when(!fsm.loadUsesPort && !maintUsesPort) {
        stS2Valid   := True
        stS2Payload := stS1Payload
      } otherwise {
        // Port taken by the load or by the maintenance walk: hold in S1, retry
        // next cycle.
        stS1Valid   := True
      }
    }

    // ---- store-S2: merge old line + write (hit-detect hoisted above the FSM) ----
    // This block and the load FSM's refill/write-allocate arms drive the SAME
    // per-way wrEn/wrSet/wrData vectors, and the FSM's assignments elaborate LAST
    // (StateMachine bodies run as a `prePopTask`), so a same-cycle refill write
    // OVERRIDES this store write. That is NOT benign and is NOT prevented by
    // single-outstanding: single-outstanding only serialises the store side against
    // ITSELF; the load-refill engine runs completely independently of it. Both the
    // same-SET case and the same-WAY/different-SET case (the shared port is per-way,
    // not per-(way,set)) are what `refillWriteHold` above exists to interlock — it
    // holds the REFILL side off so this store write is never silently dropped. Do
    // not weaken that signal without re-reading its comment.
    // Aligned/probe path derives the merge from {data,size,offset}; a SPLIT store
    // slot supplies an explicit line-relative strobe + 128-bit line-aligned data.
    val mergeData = Mux(stS2Payload.useStrb,
      stS2Payload.lineData,
      DcacheByteLane.storeData(stS2Off, stS2Payload.size, stS2Payload.data))
    val mergeStrb = Mux(stS2Payload.useStrb,
      stS2Payload.strb,
      DcacheByteLane.storeStrb(stS2Off, stS2Payload.size))
    val stS2MrgBytes = mergeData.subdivideIn(8 bits)

    // Registered latch of the drain's identity, needed a cycle later by the AXI
    // B-ack site (Task P4.5's diagnostic-channel gate) and by the WT/beat drive.
    val stPreciseReg = Reg(Bool())

    when(stS2Valid) {
      // Guard kept literally identical to `stS2ArrayWrite` (declared above the FSM,
      // consumed by `refillWriteHold`): the interlock must fire on EXACTLY the cycles
      // this block drives the shared array write port.
      when(stS2ArrayWrite) {
        for (w <- 0 until ways) when(stS2HitVec(w)) {
          val curBytes = rdData(w).subdivideIn(8 bits)
          val newBytes = Vec(Bits(8 bits), 16)
          for (i <- 0 until 16) newBytes(i) := Mux(mergeStrb(i), stS2MrgBytes(i), curBytes(i))
          wrEn(w)   := True
          wrSet(w)  := stS2Set
          wrData(w) := newBytes.asBits
          when(stS2Copyback) { dirtys(w)(stS2Set) := True }
        }
      }
      stMergeReg   := mergeData
      stStrbReg    := mergeStrb
      stAddrReg    := (stS2Payload.paddr(31 downto offBits) ## U(0, offBits bits)).asUInt
      stPreciseReg := stS2Payload.precise
      when(stS2Copyback && stS2HitAny) {
        // COPYBACK HIT: the drain-throughput win -- resolved ENTIRELY on-chip, no
        // AXI beat at all. stAwDone/stWDone stay True (no AXI in flight); the ack
        // comes from cbHitAckReg below instead.
        stAwDone := True
        stWDone  := True
      } .elsewhen(stS2Copyback && !stS2HitAny) {
        // COPYBACK MISS: post-commit write-allocate, off the retire path entirely.
        // No AXI beat is issued directly from S2 -- the refill engine issues the AR.
        stAwDone := True
        stWDone  := True
        pendingStoreMiss  := True
        pendingStorePaddr := stS2Payload.paddr
        pendingMergeData  := mergeData
        pendingMergeStrb  := mergeStrb
        // Task P4.3 Finding 1 fix: lock the victim way/dirty/tag/line HERE, not at
        // the eventual IDLE pickup cycle -- see the `pendingVictim*` decl comment.
        val pVw = victim(stS2Set)
        pendingVictimWay   := pVw
        pendingVictimDirty := dirtys(pVw)(stS2Set)
        pendingVictimTag   := rdTag(pVw)
        pendingVictimLine  := rdData(pVw)
      } .otherwise {
        // WRITETHROUGH hit/miss (fast path, unchanged from before this task) and
        // INHIBITED (precise path, unchanged from before this task). The payload
        // (stAddrReg/stMergeReg/stStrbReg, set unconditionally above) is ALWAYS
        // latched immediately; the actual AXI KICKOFF fires IMMEDIATELY too
        // (zero added latency, byte-for-byte as before this task) UNLESS
        // EVICT_WR's own aw+w pair is genuinely still open THIS cycle (Task P4.3
        // AXI-hazard fix revision 3 -- see `evictAxiPairOpen`'s decl comment),
        // the rare case a `pendingWtKickoff` latch defers to the first later
        // cycle it clears.
        //
        // POST-P5.4-REVIEW: `maintAxiPairOpen` (the cache-maintenance walk's own
        // writeback pair) joins `evictAxiPairOpen` here for exactly the same reason
        // -- it is a third open AW/W pair on the same physical port, and AXI4's
        // AW/W FIFO-ordering rule cross-attributes interleaved pairs regardless of
        // `id`. Deadlock-free: deferring here leaves `stAwDone`/`stWDone` True, so
        // `storeWantsAxi` stays FALSE and the walk's own `WRB` gate (`!storeWantsAxi
        // && !evictAxiPairOpen`) is not blocked by the store that is waiting on it.
        when(!evictAxiPairOpen && !maintAxiPairOpen) {
          stAwDone := False
          stWDone  := False
        } otherwise {
          pendingWtKickoff := True
        }
      }
    }

    // Consume `pendingWtKickoff`: fire the actual stAwDone/stWDone kickoff the
    // moment EVICT_WR's own aw+w pair is no longer open (immediately, the
    // overwhelming majority of the time, since `evictAxiPairOpen` is False
    // whenever no eviction is in flight) -- retried every cycle until then,
    // mirroring `pendingStoreMiss`'s existing shape.
    // POST-P5.4-REVIEW: also held off while the maintenance walk's own writeback pair
    // is open (see the S2 kickoff site above for the full reasoning).
    when(pendingWtKickoff && !evictAxiPairOpen && !maintAxiPairOpen) {
      stAwDone := False
      stWDone  := False
      pendingWtKickoff := False
    }

    // 1-cycle-later local ack for a COPYBACK hit (design doc: "S2 or the following
    // cycle"). Combined into storeAckReg in Step 4 below.
    val cbHitAckReg = RegNext(stS2Valid && stS2Copyback && stS2HitAny, init = False)

    // AXI write-through driver (single 128-bit beat) -- the store-S2 write-through
    // path's OWN, EXCLUSIVE driver, byte-for-byte unchanged from before Task P4.3
    // (id fixed at 1). EVICT_WR (Task P4.3) never touches these registers or this
    // driver -- it has its own dedicated completion flags and drives axi.aw/axi.w
    // directly (see EVICT_WR's own block above), gated off whenever this driver
    // wants the bus (`storeWantsAxi`), so the two physically never collide.
    when(!stAwDone) {
      axi.aw.valid         := True
      axi.aw.payload.addr  := stAddrReg
      axi.aw.payload.id    := U(1, 4 bits)
      axi.aw.payload.len   := U(0, 8 bits)
      axi.aw.payload.size  := U(4, 3 bits)
      axi.aw.payload.burst := Axi4.burst.INCR
      when(axi.aw.ready) { stAwDone := True }
    }
    when(!stWDone) {
      axi.w.valid        := True
      axi.w.payload.data := stMergeReg
      axi.w.payload.strb := stStrbReg
      axi.w.payload.last := True
      when(axi.w.ready) { stWDone := True }
    }

    // ---- store write-through ACK ----
    // The write has landed in memory once the AXI B response handshakes (b.ready is
    // always True). The SQ holds the drained entry resident — still forwarding — until
    // this pulse, closing the stale-refill window for a younger load that misses L1D.
    // Task P1.4: storeErrReg pulses alongside storeAckReg (same B handshake) when
    // the response carried a non-OKAY resp (SLVERR/DECERR). storeErrReg is INERT
    // today: nothing downstream reads it yet (wired into the SQ's precise-path drain
    // resolution in P2.5, the async diagnostic-fault channel in P4.6).
    //
    // CRITICAL (post-P4.3 review fix): since Task P4.3 there are TWO independent AXI
    // write issuers on this one physical port -- the store-S2 write-through path
    // (id=1) and EVICT_WR's dirty-victim writeback (id=2). B responses MUST be
    // demultiplexed by id, exactly as EVICT_WR's own completion check already does:
    // an eviction's B is NOT a store completion. Accepting any B here made every
    // dirty-victim writeback fire a SPURIOUS storeAck (and, on a non-OKAY writeback,
    // a spurious storeErr). That is not cosmetic: `sq.io.drainAck := dcache.storeAck`
    // (LsEuPlugin), so a spurious ack pops the StoreQueue head and clears drainBusy
    // BEFORE that store's own AW/W have been accepted, letting the next drain re-kick
    // the shared stAddrReg/stMergeReg/stStrbReg mid-flight; `exc.dcStoreAck` likewise
    // sequences the ExceptionUnit's E_STWAIT frame writer. And an eviction writeback's
    // non-OKAY response is diagnostic-only by design (kind=2 above) -- it must never
    // reach `sq.io.drainErr`, which P2.5 turns into an architectural fault source.
    //
    // Demux by `=== id 1` (the store's OWN id), NOT `=/= id 2` (EVICT_WR's id):
    // fail CLOSED, not fail open. Today only ids 1 and 2 ever reach the B
    // channel so both forms are functionally identical, but `=/= 2` silently
    // acks on ANY future third AXI write issuer's completion if one is ever
    // added here without updating this site (exactly the spurious-ack failure
    // class this whole fix exists to close). `=== 1` instead makes an
    // unrecognized id simply not ack anything — a hung drain, which is loud
    // and debuggable, instead of a silent spurious ack.
    val storeBAck = axi.b.valid && axi.b.ready && (axi.b.payload.id === U(1, 4 bits))
    storeErrReg := storeBAck && (axi.b.payload.resp =/= Axi4.resp.OKAY)
    storeAckReg := storeBAck || cbHitAckReg || storeAllocAckReg

    // Task P4.6: pins design doc §5 item 7's one-ack-per-store contract now that
    // storeAckReg has three sources (store-S2 write-through AXI B, copyback-hit
    // S2, drain-miss write-allocate). Mirrors storeAckReg's drive above exactly
    // -- `storeBAck`, NOT raw `axi.b.valid && axi.b.ready`, since EVICT_WR's own
    // id=2 B response is already correctly excluded from storeAckReg by the id
    // filter and is not structurally a storeAck source at all. Zero synth cost.
    // Explicit `FAILURE` severity (3-arg form), matching this file's own
    // convention (see the diagFaultKind0Fires/diagFaultPulse asserts above).
    //
    // Why this can't fire: within one store, S2's when/elsewhen/otherwise picks
    // exactly one of the three (COPYBACK-hit -> cbHitAckReg, COPYBACK-miss ->
    // eventual storeAllocAckReg, WT/INHIBITED -> eventual storeBAck). Across
    // stores, StoreQueue.scala's `drainIssue = headReady && !drainBusy` (with
    // `drainBusy` cleared only by `io.drainAck`, wired in LsEuPlugin.scala as
    // `sq.io.drainAck := dcache.storeAck` -- the exact OR of all three sources)
    // is a real registered gate: the SQ cannot present a second store until the
    // aggregate storeAckReg has already fired for the first. Unlike the
    // kind=0/kind=1 diagnostic-pulse hazard above, this exclusivity is
    // hardware-enforced (by StoreQueue's drainBusy register), not merely an
    // unenforced timing coincidence -- it just lives in a different file.
    GenerationFlags.simulation {
      val ackSources = Seq(storeBAck, cbHitAckReg, storeAllocAckReg)
      assert(CountOne(ackSources) <= U(1),
        "DcachePlugin: more than one storeAck source pulsed the same cycle",
        FAILURE)
    }

    // ---- Task P4.5: async diagnostic-fault channel ----
    // WT-beat / INHIBITED-drain B-error site, ONLY for a FAST (non-precise) drain --
    // a precise drain's B-error is ALREADY correctly, precisely handled by the SQ's
    // sqFaultCompletion path (Task P2.4); routing it here TOO would be a double-report.
    when(storeErrReg && !stPreciseReg) {
      diagFaultPulse     := True
      diagFaultPulseAddr := stAddrReg
      diagFaultPulseResp := axi.b.payload.resp.asUInt.resize(2)
      diagFaultPulseKind := U(0, 3 bits)   // kind=0: WT-beat / INHIBITED-drain
      diagFaultKind0Fires := True          // review-added collision detector
    }

    // Sticky latch across all diagFaultPulse sites (kind=0 here; kind=1 drain-miss
    // write-allocate refill ~L822; kind=2 dirty-victim eviction writeback ~L738).
    // First-error-wins: `!diagFaultValid` in the guard below means the latch, once
    // set, is never overwritten by a later pulse.
    //
    // Review note: kind=0 (this WT-beat site, an ordinary combinational `when`) and
    // kind=1 (the drain-miss write-allocate refill site, ~L822, inside REPLAY's
    // `whenIsActive` body) are NOT structurally mutually exclusive by construction --
    // unlike kind=0/kind=2 (both gate on the same single-valued `axi.b.payload.id`,
    // so they can never both be true the same cycle) and kind=1/kind=2 (different,
    // mutually-exclusive FSM states). Today kind=0 and kind=1 never collide only
    // because this file's own "single-outstanding store" producer contract
    // (see the STORE write-through comment block above, ~L315-319) guarantees the
    // WT-beat's AXI B and a drain-miss refill can never both be in flight at once --
    // that is a PRODUCER-DISCIPLINE invariant, not a hardware interlock on this
    // latch. If that contract were ever violated, `StateMachine` bodies elaborate as
    // a `prePopTask` (this file's own documented wrEn/wrData last-assignment-wins
    // gotcha, see the P4.4 array-write-port comment above, ~L495-501) -- so kind=1's
    // `diagFaultPulse := True` would elaborate AFTER kind=0's, and on a genuine
    // same-cycle collision kind=1 would silently WIN, dropping the WT-beat error
    // from this diagnostic channel with no visible sign (both pulses still feed the
    // same `diagFaultPulse`/`diagFaultPulseKind` wires -- last-assignment-wins on
    // the whole bundle, not a per-kind merge).
    val diagFaultValid = RegInit(False)
    val diagFaultAddr  = Reg(UInt(32 bits))
    val diagFaultResp  = Reg(UInt(2 bits))
    val diagFaultKind  = Reg(UInt(3 bits))   // 0=WT-beat, 1=drain-miss write-allocate,
                                              // 2=eviction writeback, 3=CPUSH writeback (P5)
    diagFaultValid.simPublic(); diagFaultAddr.simPublic()
    diagFaultResp.simPublic();  diagFaultKind.simPublic()

    when(diagFaultPulse && !diagFaultValid) {
      diagFaultValid := True
      diagFaultAddr  := diagFaultPulseAddr
      diagFaultResp  := diagFaultPulseResp
      diagFaultKind  := diagFaultPulseKind
    }

    // Review-added: make the kind=0/kind=1 hazard documented above loudly
    // detectable instead of silently possible. This does NOT protect the sticky
    // latch (a real fix would need a proper per-kind arbiter/merge) -- it only
    // proves whether the "single-outstanding store" producer contract the
    // hazard's absence relies on is ever actually violated. See the sticky-latch
    // comment above for the full hazard description.
    GenerationFlags.simulation {
      assert(!(diagFaultKind0Fires && diagFaultKind1Fires),
        "DcachePlugin: kind=0 (WT-beat) and kind=1 (drain-miss write-allocate refill) diagnostic-fault pulses fired the SAME cycle -- the single-outstanding-store producer contract this file relies on to avoid arbitrating between them was violated; the sticky latch just silently dropped one of the two errors",
        FAILURE)
    }

    // Sim-side: fatal by default (design doc Sec 4.2/Sec 5 item 4) unless a directed
    // test explicitly opts in. A test that WANTS to trigger this path pokes
    // diagFaultExpected := True before doing so.
    //
    // Explicit `FAILURE` severity (3-arg form) -- NOT relying on the 2-arg
    // assert(cond, msg) overload's default severity (which, as of SpinalHDL
    // 1.14.1, does also happen to be FAILURE, confirmed by direct bytecode
    // inspection + a live-sim probe test). This is written explicitly anyway so
    // this call stays fatal even if a future SpinalHDL version changes that
    // default -- `FAILURE` is the one severity that actually emits a Verilog
    // `$finish` (via SpinalSim's fatal-assert detection); `ERROR`/`WARNING`/`NOTE`
    // only `$display`/`$warning`/`$info` and let simulation continue. NOTE: none
    // of this matters unless the enclosing SpinalConfig also has
    // `.includeSimulation` set (see M68kSim.scala) -- without it this whole
    // `GenerationFlags.simulation { ... }` block is never even elaborated.
    val diagFaultExpected = RegInit(False); diagFaultExpected.simPublic()
    GenerationFlags.simulation {
      assert(!(diagFaultPulse && !diagFaultExpected),
        "DcachePlugin: unexpected async diagnostic fault (a trusted-cacheable-path AXI transaction errored) -- if this test intends to exercise it, poke diagFaultExpected := True first",
        FAILURE)
    }
  }

  override def loadCmd  = logic.loadCmdPort
  override def loadRsp  = logic.loadRspPort
  override def loadBusy = logic.loadBusyReg
  override def store    = logic.storePort
  override def storeAck = logic.storeAckReg
  override def storeErr = logic.storeErrReg
  override def diagFault = logic.diagFaultValid
  override def maintCmd  = logic.maintCmdPort
  override def maintDone = logic.maintDoneReg
  // Exported precondition (see DcacheService.maintQuiesced's contract): the whole
  // D-cache datapath is idle AND no walk is already running.
  override def maintQuiesced = logic.dcIdleForMaint && !logic.maintBusyReg
}
