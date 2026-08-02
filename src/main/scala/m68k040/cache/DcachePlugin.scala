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
    // AXI B (storeAck) lands, so S0/S1/S2 never overlap a second store. valids/tagMem
    // cannot change between S0 and S2 (no concurrent refill into the same flow), so
    // the S2 hit-detect is correct.
    val stMergeReg = Reg(Bits(128 bits))
    val stStrbReg  = Reg(Bits(16 bits))
    val stAddrReg  = Reg(UInt(32 bits))
    val stAwDone   = Reg(Bool()) init True   // True == no store in flight
    val stWDone    = Reg(Bool()) init True

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

    // ---- LOAD FSM (OVERRIDES the shared read port with PRIORITY over the store) ----
    val fsm = new StateMachine {
      val IDLE   = new State with EntryPoint
      val REFILL = new State
      val REPLAY = new State

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
        loadCmdPort.ready := !inFlight && xlate.rsp.ready
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
          // relative to the old async hit-detect is latency-agnostic).
          missPaddr := ldS1Paddr
          missSet   := ldS1Set
          missTag   := ldS1Tag
          missOff   := ldS1Off
          missSize  := ldS1Size
          missCmode := ldS1Cmode
          victimWay := victim(ldS1Set)
          arSent    := False
          busy      := True
          goto(REFILL)
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
        axi.r.ready := True
        when(axi.r.valid) {
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
          // Task #189: the refill's AXI response errored — no line was allocated
          // (REFILL above skipped the wr*/valids writes), so there is nothing to
          // "replay" as a hit. Delivered directly via busFaultResp (one pulse);
          // do NOT touch ldS1Valid/rdSet/rdEn here (a real re-launch would MISS
          // again forever — the line is still, correctly, not resident).
          busFaultResp := True
        } elsewhen(missCmode === CacheMode.INHIBITED) {
          // Task P1.4: no line was allocated (doAllocate was False above) — there
          // is nothing to "replay" as a hit. Deliver the just-fetched beat
          // directly, once, exactly like busFaultResp's one-pulse shape.
          inhibitedResp := True
        } otherwise {
          // Re-launch the read for the just-filled line; resolve as a guaranteed hit
          // into the response register one cycle later via the ldS1 path.
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
        }
        goto(IDLE)
      }
    }

    // ---- store read-port arbiter CONTROL (no BRAM-address logic here) ----
    // The store's read ADDRESS was already driven (as the base) before the FSM; the
    // FSM overrode rdSet/rdEn if a load used the port this cycle. So here we ONLY
    // resolve whether the store actually GOT the port: if the load took it
    // (loadUsesPort), the store's read was overridden -> hold in S1 and retry; else
    // its read launched -> advance to S2. This `loadUsesPort` consumer is a small
    // control register (stS2Valid/stS1Valid), NOT the high-fanout BRAM read-address.
    when(stS1Valid) {
      when(!fsm.loadUsesPort) {
        stS2Valid   := True
        stS2Payload := stS1Payload
      } otherwise {
        // Port taken by the load: hold in S1, retry next cycle.
        stS1Valid   := True
      }
    }

    // ---- store-S2: hit-detect (registered tag-read) + merge old line + write ----
    // Placed BEFORE the refill write in the FSM ordering is preserved by driving the
    // SAME wrEn/wrSet/wrData vectors: a same-cycle refill write (in REFILL) is the
    // LAST assignment and overrides this store write (refill has priority).
    // Single-outstanding guarantees they never actually collide.
    val stS2HitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      stS2HitVec(w) := valids(w)(stS2Set) && (rdTag(w) === stS2Tag)
    stS2HitVec.simPublic()   // DEBUG (task #189), temporary
    // Aligned/probe path derives the merge from {data,size,offset}; a SPLIT store
    // slot supplies an explicit line-relative strobe + 128-bit line-aligned data.
    val mergeData = Mux(stS2Payload.useStrb,
      stS2Payload.lineData,
      DcacheByteLane.storeData(stS2Off, stS2Payload.size, stS2Payload.data))
    val mergeStrb = Mux(stS2Payload.useStrb,
      stS2Payload.strb,
      DcacheByteLane.storeStrb(stS2Off, stS2Payload.size))
    val stS2MrgBytes = mergeData.subdivideIn(8 bits)
    // Task P1.4: an INHIBITED store never touches the cache array (skip the RMW
    // line write entirely) — only the AXI write-through beat below is unconditional.
    // Task P4.1: a COPYBACK-hit store resolves ENTIRELY on-chip (RMW + dirty-bit,
    // no AXI beat at all); WRITETHROUGH (hit or miss) and INHIBITED are unchanged.
    val stS2Inhibited = stS2Payload.cacheMode === CacheMode.INHIBITED
    val stS2Copyback  = stS2Payload.cacheMode === CacheMode.COPYBACK
    val stS2HitAny    = stS2HitVec.orR

    // Registered latch of the drain's identity, needed a cycle later by the AXI
    // B-ack site (Task P4.5's diagnostic-channel gate) and by the WT/beat drive.
    val stPreciseReg = Reg(Bool())

    when(stS2Valid) {
      when(!stS2Inhibited && stS2HitAny) {
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
      } otherwise {
        // WRITETHROUGH hit/miss (fast path, unchanged from before this task),
        // INHIBITED (precise path, unchanged from before this task), and COPYBACK
        // MISS (fast path -- Task P4.2 intercepts this case and overrides stAwDone/
        // stWDone back to True + routes to write-allocate instead; left as the
        // "issue an AXI beat" default here so P4.2's own gate is a pure ADDITION,
        // not a rewrite of this block).
        stAwDone := False
        stWDone  := False
      }
    }

    // 1-cycle-later local ack for a COPYBACK hit (design doc: "S2 or the following
    // cycle"). Combined into storeAckReg in Step 4 below.
    val cbHitAckReg = RegNext(stS2Valid && stS2Copyback && stS2HitAny, init = False)

    // AXI write-through driver (single 128-bit beat).
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
    // the response carried a non-OKAY resp (SLVERR/DECERR) — storeAckReg itself is
    // UNCHANGED (still pulses on ANY B, ok or err). storeErrReg is INERT this task:
    // nothing downstream reads it yet (wired into the SQ's precise-path drain
    // resolution in P2.5, the async diagnostic-fault channel in P4.6).
    storeErrReg := axi.b.valid && axi.b.ready && (axi.b.payload.resp =/= Axi4.resp.OKAY)
    storeAckReg := (axi.b.valid && axi.b.ready) || cbHitAckReg
  }

  override def loadCmd  = logic.loadCmdPort
  override def loadRsp  = logic.loadRspPort
  override def loadBusy = logic.loadBusyReg
  override def store    = logic.storePort
  override def storeAck = logic.storeAckReg
  override def storeErr = logic.storeErrReg
}
