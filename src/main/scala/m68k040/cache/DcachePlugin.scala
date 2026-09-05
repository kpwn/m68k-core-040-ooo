package m68k040.cache

import m68k040.isa.Size
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
  *   - LOAD: VIPT 3-stage read (S0 launch BRAM tag+data read from the virtual
  *     set; S1 tag-compare against the request's PRE-TRANSLATED physical tag;
  *     S2 registered way-mux/byte-lane response).
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
  *     port. A one-bit bounded arbiter alternates fresh loads with a waiting store;
  *     internal replay/refill/maintenance work retains priority.
  *   - Hit/miss is resolved ONE CYCLE LATER (in S1, against the registered tag-read
  *     vs the registered ppn-tag) — latency-agnostic; lock-step absorbs the +1.
  * FMax (Slice 2, 2026-08-07): the LOAD hit RESPONSE build (way-select -> byte-lane
  *   extract -> loadRspPort) moved out of S1 into a new S2 register stage, so a load
  *   hit is S0 accept / S1 compare+way-select / S2 extract+respond. Hit/miss
  *   DETECTION and the whole EVICT_WR/REFILL/REPLAY machinery are unchanged (they
  *   read only `ldS1Hit`). One uniform extra cycle of load-to-use latency.
  * Valids: register array. Victim: register array. */
class DcachePlugin(val socketMerged: Boolean = false,
                   val earlyViptEnabled: Boolean = true) extends FiberPlugin with DcacheService {

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
    val loadProbePort = Stream(DLoadProbe())
    loadProbePort.valid.allowOverride; loadProbePort.valid := False
    loadProbePort.payload.vaddr.allowOverride; loadProbePort.payload.vaddr := U(0, 32 bits)
    loadProbePort.payload.token.allowOverride; loadProbePort.payload.token := U(0, DLoadToken.Width bits)
    loadProbePort.payload.resolved.allowOverride; loadProbePort.payload.resolved := False
    loadProbePort.payload.paddr.allowOverride; loadProbePort.payload.paddr := U(0, 32 bits)
    loadProbePort.payload.size.allowOverride; loadProbePort.payload.size := Size.LONG
    loadProbePort.payload.cacheMode.allowOverride
    loadProbePort.payload.cacheMode := CacheMode.INHIBITED
    loadProbePort.payload.needsLine.allowOverride; loadProbePort.payload.needsLine := False
    loadProbePort.valid.simPublic(); loadProbePort.ready.simPublic(); loadProbePort.payload.simPublic()
    val loadProbeCancelPort = Flow(DLoadProbeCancel())
    loadProbeCancelPort.valid.allowOverride; loadProbeCancelPort.valid := False
    loadProbeCancelPort.payload.token.allowOverride
    loadProbeCancelPort.payload.token := U(0, DLoadToken.Width bits)
    loadProbeCancelPort.payload.all.allowOverride
    loadProbeCancelPort.payload.all := False
    val loadProbeResolvePort = Flow(DLoadProbeResolve())
    loadProbeResolvePort.valid.allowOverride; loadProbeResolvePort.valid := False
    loadProbeResolvePort.payload.token.allowOverride
    loadProbeResolvePort.payload.token := U(0, DLoadToken.Width bits)
    loadProbeResolvePort.payload.paddr.allowOverride
    loadProbeResolvePort.payload.paddr := U(0, 32 bits)
    loadProbeResolvePort.payload.cacheMode.allowOverride
    loadProbeResolvePort.payload.cacheMode := CacheMode.INHIBITED
    val loadCmdPort = Stream(DLoadCmd())
    loadCmdPort.valid.simPublic(); loadCmdPort.ready.simPublic(); loadCmdPort.payload.simPublic()
    val loadRspPort = Flow(DLoadRsp())
    loadRspPort.valid.simPublic(); loadRspPort.payload.simPublic()
    val loadBusyReg = Bool()
    val storePort   = Stream(DStoreCmd())
    storePort.valid.simPublic(); storePort.ready.simPublic(); storePort.payload.simPublic()
    // NOTE on the "zero synthesis cost" claim repeated throughout this file (see
    // docs/debug-trace-taps.md, section on that exact repeated claim, for the full
    // writeup): true for QoR (area/timing are unaffected either way) but NOT true
    // for exact synthesized signal *names* -- simPublic() on a signal can stop it
    // being inlined into its consumer and renumber every auto-named
    // `when_<Plugin>_lNNN` signal below it (see ExecuteLockStepSpec.scala:179-182
    // for a worked example on a different plugin). This project's FMax methodology
    // diffs exact Vivado register names across commits, so don't assume a name
    // still resolves to the same signal across a commit that added/removed a
    // simPublic() call here.
    val storeAckReg = Bool()
    storeAckReg.simPublic()   // test-visibility only (P4.1 COPYBACK-hit directed test: no
                               // AXI B to observe on that path); no-op for synthesis
    val storeErrReg = Bool()   // Task P1.4: 1-cycle pulse, non-OKAY B alongside storeAckReg
    storeErrReg.simPublic()
    // WT-pipelining task: the pipelined-WT-specific slice of `storeAckReg` (see
    // that signal's own drive site for the exact expression and the reasoning for
    // why `!stPreciseReg` at a `storeBAck` instant is an exact classifier). Forward-
    // declared here, same pattern as `storeAckReg` itself, since `wtOutstanding`'s
    // accounting (declared far above the AXI B site) needs to reference it.
    val wtStoreAckReg = Bool()
    wtStoreAckReg.simPublic()
    // `socketMerged` (axi-socket adapter plan, Task 5): when this plugin's AXI is merged
    // onto the single socket `axi_d` by AxiDMergePlugin, the bundle must be DIRECTIONLESS
    // so a sibling plugin in the same Component can drive its response side --
    // `master(...)` would make ar.ready/r.valid/b.valid inputs of M68kCore, which cannot
    // be driven from inside (see FetchAlignPlugin.scala:64-66). No logic changes; the
    // default (false) is today's behaviour and today's top-level port, exactly.
    val axi         = if (socketMerged) Axi4(axiCfg) else master(Axi4(axiCfg))

    // ---- storage (sync-read BRAM: write + readSync ONLY, no readAsync) ----
    val dataMem = Seq.fill(ways)(Mem(Bits(128 bits), sets))
    // tagMem is write+readSync only (see above) but its narrow width (tagBits ~
    // 20b x 128 sets) leads Vivado to infer IMPLIED DISTRIBUTED RAM (RAM64M8 LUTRAM)
    // instead of BRAM, unlike the wider dataMem which gets BRAM by default. This is
    // the exact structure the placer's congestion dump names (ldS1Tag_reg / tagMem_
    // spinal_port1 nets, iter_100_CongestedCLBsAndNets.txt) -- force BRAM (95% free
    // budget) to both decongest the hot corridor and remove ~192 LUTRAM LUTs/way.
    val tagMem  = Seq.fill(ways)(Mem(UInt(tagBits bits), sets).addAttribute("ram_style", "block"))
    // Task #240 (D2.1, LUT-reduction slice): valids/dirtys folded from per-way
    // Vec.fill(ways)(Vec.fill(sets)(RegInit(False))) flop arrays (128 x 4 x 2 =
    // 1,024 FF, behind wide dynamic-index read muxes at every consumer) into
    // per-way single-write-port Mem(Bool, sets) -- LUTRAM by inference (1-bit-wide,
    // 128-deep, same shape distributed-RAM inference already relied on for tagMem
    // pre-block-attribute). Reads ride the SAME shared rdSet/rdEn synchronous port
    // dataMem/tagMem already use (see rdValid/rdDirty below) -- every consumer of
    // valids(w)(set)/dirtys(w)(set) already reads a sibling rdTag(w)/rdData(w) at
    // the IDENTICAL address in the IDENTICAL cycle (verified against current source
    // for every call site during this task), so no new read-port arbitration logic
    // is needed at all.
    //
    // Mem.init(all False) supplies the FPGA CONFIGURATION-time contents, but it does
    // not make these inferred RAMs respond to a later CPU/JTAG runtime reset.  A
    // same-bitstream reset therefore needs the explicit set walk below; otherwise
    // old valid+dirty lines survive while all surrounding control and replacement
    // registers reset, letting a new boot hit data from the previous one.
    val validsMem = Seq.fill(ways)(Mem(Bool(), sets) init Vector.fill(sets)(False))
    val dirtysMem = Seq.fill(ways)(Mem(Bool(), sets) init Vector.fill(sets)(False))
    // test-visibility only (DcacheSpec/DcacheDrainRefillRaceSpec peek dirtysMem(w)
    // via the sim-side Mem.getBigInt(addr) API, the same idiom IcachePlugin already
    // uses for tagMem/lineMem, see IcachePlugin.scala's own `.simPublic()` loop);
    // no-op for synthesis.
    // CONVENTION (task #240 gotcha, see docs/debug-trace-taps.md "Mem-typed state
    // needs explicit .simPublic() per instance" for the full writeup): unlike a flat
    // Reg/Vec(Reg) array, a Mem is NOT peekable via getBigInt/setBigInt in sim
    // without this explicit per-instance simPublic() loop -- omitting it throws
    // UNACCESSIBLE SIGNAL at sim time, not a silent no-op.
    for (w <- 0 until ways) { validsMem(w).simPublic(); dirtysMem(w).simPublic() }
    val victim  = Vec.fill(sets)(RegInit(U(0, wayBits bits)))
    // Runtime reset invalidation. RegInit(True) re-arms this on every reset (not
    // merely at FPGA configuration); one set is invalidated per cycle after reset
    // releases. Request acceptance and maintenance quiescence are gated below until
    // all sets are clean. Data/tag RAM need not be cleared: valid=False makes their
    // payload architecturally unreachable, exactly as a normal invalidate does.
    val resetSweepBusy = RegInit(True)
    val resetSweepSet  = Reg(UInt(setBits bits)) init 0
    resetSweepBusy.simPublic(); resetSweepSet.simPublic()

    // ---- single muxed data/tag write port per way (refill + store-write) ----
    val wrEn    = Vec.fill(ways)(False)
    val wrSet   = Vec.fill(ways)(U(0, setBits bits))
    val wrData  = Vec.fill(ways)(B(0, 128 bits))
    val wrTagEn = Vec.fill(ways)(False)             // tag write (refill only)
    val wrTag   = Vec.fill(ways)(U(0, tagBits bits))
    wrEn.simPublic(); wrSet.simPublic()   // DEBUG (pea-cache-evict-2026-08-19 investigation), temporary
    // Task #240 (D2.1): muxed write port per way for validsMem/dirtysMem, same
    // shape/idiom as wrEn/wrSet/wrData/wrTagEn/wrTag above (plain combinational
    // Vecs, last-assignment-wins across the writer sites below). Each way has
    // AT MOST ONE active writer per cycle -- the full exclusivity proof (which
    // writer sites, and why each pair is mutually exclusive) is recorded in this
    // task's commit message and report (task #240); the short version: REFILL-
    // allocate and REPLAY-merge are different states of the same single-active-
    // state `fsm` StateMachine; maint CHECK/WRB are different states of the same
    // single-active-state `maint.sm` StateMachine; the fsm-active group and the
    // maint-walking group are mutually exclusive per the existing FAILURE-severity
    // sim assert `!(maint.walking && !fsm.isActive(fsm.IDLE))` below; and store-S3's
    // dirty write is excluded from the fsm-active group by the EXISTING
    // `refillWriteHold`/`storeDrainRefillHold` hardware interlocks (not merely a
    // sim assert) and from the maint-walking group by the existing
    // `!(maint.walking && (... || stS3Valid))` assert.
    val validsWrEn   = Vec.fill(ways)(False)
    val validsWrSet  = Vec.fill(ways)(U(0, setBits bits))
    val validsWrData = Vec.fill(ways)(False)
    val dirtysWrEn   = Vec.fill(ways)(False)
    val dirtysWrSet  = Vec.fill(ways)(U(0, setBits bits))
    val dirtysWrData = Vec.fill(ways)(False)
    // Task #255: per-writer vote vectors -- the same "review-added collision
    // detector" idiom as `diagFaultKind0Fires`/`diagFaultKind1Fires` far below
    // (plain default-False signals, driven unconditionally at each real writer
    // site, consumed ONLY by a GenerationFlags.simulation-gated assert). Needed
    // because `validsWrEn(w)`/`dirtysWrEn(w)` themselves are last-assignment-wins
    // muxes: they show the FINAL result, not how many of the writer sites above
    // tried to fire for way `w` this cycle. These vote bits make the task-#240
    // hand-proven exclusivity a runtime-checked invariant instead of merely an
    // argued one. Zero synthesis cost: with no writer site ever pulsing a bit
    // (which only happens inside a live collision, provably unreachable per the
    // task-#240 report), each vote wire is a constant-False signal with no
    // fanout outside the simulation-only assert below, so Vivado's own dead-code
    // elimination removes it entirely from a non-simulation netlist.
    val validsVoteW1 = Vec.fill(ways)(False)   // W1: REFILL-allocate
    val validsVoteW0 = Vec.fill(ways)(False)   // W0: runtime-reset invalidate
    val validsVoteW2 = Vec.fill(ways)(False)   // W2: maint CHECK-invalidate
    val validsVoteW3 = Vec.fill(ways)(False)   // W3: maint WRB-invalidate
    val dirtysVoteD1 = Vec.fill(ways)(False)   // D1: REFILL-allocate
    val dirtysVoteD0 = Vec.fill(ways)(False)   // D0: runtime-reset clear
    val dirtysVoteD2 = Vec.fill(ways)(False)   // D2: REPLAY-merge write-allocate
    val dirtysVoteD3 = Vec.fill(ways)(False)   // D3: maint CHECK-invalidate
    val dirtysVoteD4 = Vec.fill(ways)(False)   // D4: maint WRB-clean
    val dirtysVoteD5 = Vec.fill(ways)(False)   // D5: store-S3-copyback
    when(resetSweepBusy) {
      for (w <- 0 until ways) {
        validsWrEn(w)   := True
        validsWrSet(w)  := resetSweepSet
        validsWrData(w) := False
        dirtysWrEn(w)   := True
        dirtysWrSet(w)  := resetSweepSet
        dirtysWrData(w) := False
        validsVoteW0(w) := True
        dirtysVoteD0(w) := True
      }
      when(resetSweepSet === U(sets - 1, setBits bits)) {
        resetSweepBusy := False
      } otherwise {
        resetSweepSet := resetSweepSet + 1
      }
    }
    for (w <- 0 until ways) {
      dataMem(w).write(wrSet(w), wrData(w), wrEn(w))
      tagMem(w).write(wrSet(w), wrTag(w), wrTagEn(w))
      validsMem(w).write(validsWrSet(w), validsWrData(w), validsWrEn(w))
      dirtysMem(w).write(dirtysWrSet(w), dirtysWrData(w), dirtysWrEn(w))
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
    rdEn.simPublic()
    rdSet.simPublic()   // DEBUG (pea-cache-evict-2026-08-19 investigation), temporary
    val rdData  = Vec(dataMem.map(_.readSync(rdSet, rdEn)))
    val rdTag   = Vec(tagMem.map(_.readSync(rdSet, rdEn)))
    // Task #240 (D2.1): rdValid/rdDirty ride the SAME rdSet/rdEn port as rdTag/
    // rdData -- every consumer below reads rdValid(w)/rdDirty(w) at the exact same
    // address+cycle it already reads rdTag(w)/rdData(w) at (verified per call site).
    val rdValid = Vec(validsMem.map(_.readSync(rdSet, rdEn)))
    val rdDirty = Vec(dirtysMem.map(_.readSync(rdSet, rdEn)))

    // ---- tokenized early VIPT result queue ----
    // A probe reserves one small entry and launches the virtual-set BRAM read in
    // parallel with the DTLB lookup. One shared two-stage result pipe registers the
    // selected 128-bit way, then stores only the extracted 32-bit value in the
    // reserved entry. This supports a probe every cycle without replicating a full
    // cache line per outstanding load. Four entries cover D1's fixed P2->command
    // distance; if they fill, probe.ready drops but the resolved load path remains
    // correct and simply uses the ordinary S1 read.
    val earlyProbeDepth = 4
    val earlyProbePtrW  = log2Up(earlyProbeDepth)
    val earlyProbeValids  = Vec.fill(earlyProbeDepth)(RegInit(False))
    val earlyProbeReadies = Vec.fill(earlyProbeDepth)(RegInit(False))
    val earlyProbeHits    = Vec.fill(earlyProbeDepth)(RegInit(False))
    val earlyProbeTokens  = Vec.fill(earlyProbeDepth)(Reg(UInt(DLoadToken.Width bits)))
    val earlyProbeVaddrs  = Vec.fill(earlyProbeDepth)(Reg(UInt(32 bits)))
    val earlyProbeData    = Vec.fill(earlyProbeDepth)(Reg(Bits(32 bits)))

    // Read metadata: valid in the cycle the synchronous BRAM output belongs to the
    // probe. Line metadata: one extra register cut keeps BRAM->way-select separate
    // from byte extraction and the small result array write.
    val probeReadValid = RegInit(False)
    val probeReadSlot  = Reg(UInt(earlyProbePtrW bits))
    val probeReadSet   = Reg(UInt(setBits bits))
    val probeReadTag   = Reg(UInt(tagBits bits))
    val probeReadOff   = Reg(UInt(offBits bits))
    val probeReadSize  = Reg(Size())
    val probeReadUsable = RegInit(False)
    val probeReadNeedsLine = RegInit(False)
    val probeLineValid = RegInit(False)
    val probeLineSlot  = Reg(UInt(earlyProbePtrW bits))
    val probeLineHit   = RegInit(False)
    val probeLineLine  = Reg(Bits(128 bits))
    val probeLineOff   = Reg(UInt(offBits bits))
    val probeLineSize  = Reg(Size())

    val earlyProbeValid = earlyProbeValids.asBits.orR
    val earlyProbeFresh = (earlyProbeValids.asBits & earlyProbeReadies.asBits).orR
    earlyProbeValid.simPublic(); earlyProbeFresh.simPublic()
    for (i <- 0 until earlyProbeDepth) {
      earlyProbeValids(i).simPublic(); earlyProbeReadies(i).simPublic()
      earlyProbeHits(i).simPublic(); earlyProbeTokens(i).simPublic()
      earlyProbeVaddrs(i).simPublic()
    }
    probeReadValid.simPublic(); probeReadSlot.simPublic(); probeReadSet.simPublic()
    probeReadTag.simPublic(); probeReadUsable.simPublic()
    probeLineValid.simPublic(); probeLineSlot.simPublic(); probeLineHit.simPublic()

    // ---- miss-state latches ----
    val missPaddr = Reg(UInt(32 bits))   // physical addr of the missing line (AXI refill base)
    val missSet   = Reg(UInt(setBits bits))
    val missTag   = Reg(UInt(tagBits bits))
    val missOff   = Reg(UInt(offBits bits))
    val missSize  = Reg(Size())
    // D6/D25/D30: the INHIBITED sub-transaction sequencer's cursor and its CLAMPED end.
    // Both are LINE-RELATIVE, so `subP`'s low bits are the emitted address's low bits and
    // the natural-alignment test needs no separate address arithmetic (spec 3.4).
    val subP   = Reg(UInt(offBits bits)) init 0
    val subEnd = Reg(UInt(offBits + 1 bits)) init 0
    // Task #236 fix: `MmioCover.stepLog2`'s adder/comparator tree used to be recomputed
    // COMBINATIONALLY every cycle from `subP`/`subEnd` inside REFILL.whenIsActive, even
    // though `subP`/`subEnd` only change at the two sites that write them (miss-detect
    // entry and the sub-transaction advance below) -- a multi-cycle AXI handshake wait
    // recomputed the same adder/comparator tree on every idle cycle for nothing. Registered
    // here, computed ONCE at each of those two write sites (from the NEW subP/subEnd, not
    // the stale current ones), so REFILL's own per-cycle path only ever reads a register.
    // Confirmed by the FMax regression investigation
    // (.superpowers/sdd/fmax-regression-investigation-2026-08-19-report.md) as the direct
    // cause of a real, measured 0.430ns synth-only WNS regression against the 197.278MHz
    // baseline -- this is that fix. Init value is architecturally irrelevant: `subLog2Reg`
    // is only ever consumed behind `inhib`, which is false until the entry site above has
    // already run and set a real value.
    val subLog2Reg = Reg(UInt(2 bits)) init 0
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
    // there is no real data to cache) and REPLAY emits the dedicated bus-fault
    // response. Translation faults are consumed by LsEuPlugin before loadCmd, so
    // the cache response's fault field is exclusively a physical bus-fault carrier:
    // LsEuPlugin's
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

    // FMax fix (pea-cache-evict-2026-08-19 WNS-regression follow-up): a compact,
    // register-free "did a REFILL/REPLAY array write land THIS cycle" pulse, always
    // targeting `missSet` (both of its two drive sites below write missSet, never
    // any other set -- see the REFILL-allocate and REPLAY-merge write() call sites).
    // Exists PURELY so downstream same-set collision checks (the early-probe
    // staleness logic below) can compare against this ONE (valid, set) pair instead
    // of scanning the raw per-way `wrEn`/`wrSet` write-port vectors -- keeps new
    // consumers off those already-widely-fanned-out nets. Mirrors this file's other
    // single-pulse "did X happen this cycle" flags (`busFaultResp`, `inhibitedResp`,
    // `storeAllocAckReg`).
    val missArrayWrite = Bool(); missArrayWrite := False
    // Same shape as `missArrayWrite` above, for the store-S3 RMW array write (the
    // ONLY other physical writer of the shared per-way dataMem/tagMem ports). Driven
    // at the S3 write site below from the already-computed `stS3ArrayWrite`/`stS3Set`
    // -- declared HERE (early) purely so the early-probe staleness checks further
    // down (which elaborate before `stS3ArrayWrite`/`stS3Set` in Scala source order)
    // can read a same-shaped (valid, set) pair without a forward reference, and
    // without themselves scanning the raw `wrEn`/`wrSet` write-port vectors.
    val storeArrayWrite    = Bool(); storeArrayWrite := False
    val storeArrayWriteSet = UInt(setBits bits); storeArrayWriteSet := U(0, setBits bits)

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
    // test-visibility only (FMax Lever F's positive-control sweep in
    // DcacheDrainRefillRaceSpec counts merge acks); already a live driver of
    // storeAckReg, so this cannot change the synthesised netlist.
    storeAllocAckReg.simPublic()

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
    loadProbePort.ready := False
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
    loadBusyReg := busy || resetSweepBusy

    // ---- load index/tag from cmd vaddr + PRE-TRANSLATED paddr ----
    // FMax: the physical tag comes from the requester's REGISTERED `loadCmd.paddr`
    // (translate-at-execute already resolved it into a register), never from a live
    // DTLB response. VIPT: index on the page-invariant vaddr set bits;
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
    val ldS1Paddr = Reg(UInt(32 bits))   // physical addr (refill base on a miss)
    ldS1Valid := False                   // default; armed on an accept below

    // Throughput slice C0: one bounded replay slot for the request accepted on the
    // exact cycle an older S1 probe discovers a miss. That younger request has
    // already completed the Stream handshake, but must not enter the untagged
    // response pipe ahead of the miss. Keeping its resolved command here lets the
    // refill finish, then re-launches it in order. One slot is sufficient because
    // the load FSM leaves IDLE immediately after the miss decision, so there can be
    // at most one such shadow request before ready drops structurally.
    val loadShadowValid = RegInit(False)
    val loadShadowCmd   = Reg(DLoadCmd())
    loadShadowValid.simPublic()

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
      ldS1HitVec(w) := ldS1Cacheable && rdValid(w) && (rdTag(w) === ldS1Tag)
    val ldS1Hit     = ldS1HitVec.orR
    val ldS1HitWay  = OHToUInt(ldS1HitVec)
    // DEBUG (task #189 investigation, temporary): sim-only visibility.
    ldS1Valid.simPublic(); ldS1Set.simPublic(); ldS1Tag.simPublic(); ldS1Off.simPublic()
    ldS1Size.simPublic(); ldS1Hit.simPublic(); ldS1HitWay.simPublic()
    val ldS1Line    = rdData(ldS1HitWay)

    // Finish the previous cycle's virtual-set read against the matching registered
    // DTLB response. Its token and PA qualify the synchronous BRAM output in this
    // cycle, so the cache stores only the selected line rather than all ways' raw
    // data. If translation arrives late, this probe records an unusable miss and
    // the resolved command safely falls back to the ordinary array-read path.
    val probeResolveCanceled = loadProbeCancelPort.valid &&
      (loadProbeCancelPort.payload.all ||
       (loadProbeCancelPort.payload.token === loadProbeResolvePort.payload.token))
    val probeResolveMatchesRead = loadProbeResolvePort.valid && probeReadValid &&
      earlyProbeValids(probeReadSlot) &&
      (earlyProbeTokens(probeReadSlot) === loadProbeResolvePort.payload.token) &&
      !probeResolveCanceled
    // ── FMax: compare the ways against BOTH candidate tags, then select ──
    // The original form selected the tag first and compared second:
    //
    //   probeResolvedTag    = Mux(probeResolveMatchesRead, resolvePort.paddr(tag),
    //                             probeReadTag)
    //   probeResolvedUsable = probeReadUsable || (probeResolveMatchesRead && ...)
    //   probeReadHitVec(w)  = probeResolvedUsable && rdValid(w) &&
    //                           (rdTag(w) === probeResolvedTag)
    //
    // so `probeResolveMatchesRead` had to traverse a tag mux AND the full way-tag
    // comparator (a CARRY8) before `probeReadHitVec` existed -- three logic levels,
    // at the far end of the core's longest cone. `probeResolveMatchesRead` is fed by
    // `loadProbeResolvePort.valid`, which the LS EU derives from the completion
    // arbitration, which the ROB retire pointer feeds through its payload Mem's async
    // read: measured on the standing 5.000ns OOC gate, `RobPlugin_logic_head_reg[0]`
    // -> ... -> `probeResolvedTag` -> `probeReadHitVec` -> `probeReadHitWay` ->
    // `probeLineLine_reg[*]` was 24 logic levels / 6.090ns / WNS -1.109ns, and the
    // dominant failing family (replicated across all 128 bits of `probeLineLine`).
    //
    // BOTH candidate tags are available at the top of the cycle -- `probeReadTag` is a
    // plain register, and the resolve port's paddr does not depend on
    // `probeResolveMatchesRead` -- so both comparisons can run in parallel with the
    // arbitration and only the SELECT stays behind it. `probeResolveMatchesRead` now
    // reaches `probeReadHitVec` through a single LUT.
    //
    // EQUIVALENCE, by cases on `probeResolveMatchesRead` (the only variable shared
    // between the two forms; `rdValid`/`rdTag`/`probeReadTag`/`probeReadUsable`/
    // `probeReadNeedsLine` and the resolve port's payload are identical in both):
    //   - True : old tag is the resolve paddr's tag and old usable is
    //            `probeReadUsable || (!probeReadNeedsLine && cmode =/= INHIBITED)` ==
    //            `probeUsableIfResolved`, giving
    //            `probeUsableIfResolved && rdValid(w) && (rdTag(w) === resolveTag)`.
    //   - False: old tag is `probeReadTag` and old usable collapses to
    //            `probeReadUsable`, giving
    //            `probeReadUsable && rdValid(w) && (rdTag(w) === probeReadTag)`.
    // Those are exactly the two arms below. Cost: one extra ways-wide tag comparator
    // (4 x tagBits); the tripwire re-evaluates the original expression every cycle.
    val probeResolveTagIn = loadProbeResolvePort.payload.paddr(31 downto offBits + setBits)
    val probeUsableIfResolved = probeReadUsable ||
      (!probeReadNeedsLine && (loadProbeResolvePort.payload.cacheMode =/= CacheMode.INHIBITED))
    val probeWayMatchResolved = Vec(Bool(), ways)
    val probeWayMatchRead     = Vec(Bool(), ways)
    for (w <- 0 until ways) {
      probeWayMatchResolved(w) := rdValid(w) && (rdTag(w) === probeResolveTagIn)
      probeWayMatchRead(w)     := rdValid(w) && (rdTag(w) === probeReadTag)
    }
    val probeReadHitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      probeReadHitVec(w) := Mux(probeResolveMatchesRead,
                                probeUsableIfResolved && probeWayMatchResolved(w),
                                probeReadUsable       && probeWayMatchRead(w))
    GenerationFlags.simulation {
      val probeResolvedTagRef = Mux(probeResolveMatchesRead, probeResolveTagIn, probeReadTag)
      val probeResolvedUsableRef = probeReadUsable ||
        (probeResolveMatchesRead && !probeReadNeedsLine &&
         (loadProbeResolvePort.payload.cacheMode =/= CacheMode.INHIBITED))
      for (w <- 0 until ways)
        assert(probeReadHitVec(w) === (probeResolvedUsableRef && rdValid(w) &&
                                       (rdTag(w) === probeResolvedTagRef)),
          "DcachePlugin: probeReadHitVec drifted from its original select-then-compare definition",
          FAILURE)
    }
    // (`probeReadHitWay = OHToUInt(probeReadHitVec)` used to live here. Its only consumer
    //  was the `probeLineLine` way mux below, which is now a `MuxOH` off the hit vector
    //  itself -- see there for why the binary round trip cost a critical-path level.)
    probeReadValid := False
    probeLineValid := probeReadValid
    when(probeReadValid) {
      probeLineSlot := probeReadSlot
      probeLineHit  := probeReadHitVec.asBits.orR
      // FMax: one-hot way mux instead of `rdData(OHToUInt(probeReadHitVec))`. The binary
      // round trip costs a level -- Vivado builds it as an `OHToUInt` reduction, then a
      // shared select decode broadcast to all 128 bits, then the per-bit mux -- and this
      // is the LAST hop of the core's worst path, so the level lands directly on WNS.
      // `MuxOH` consumes the already-one-hot vector directly (`probeReadHitVec` is a
      // way-tag hit vector: at most one way of a set may hold a given tag, which is the
      // same premise `OHToUInt` was already asserting).
      //
      // The ONE input for which the two forms differ is the all-zero vector, and there
      // the result is a proven don't-care: `MuxOH` yields 0 where `rdData(OHToUInt(0))`
      // yielded way 0's line. An all-zero `probeReadHitVec` sets `probeLineHit := False`
      // on the same edge, so next cycle `earlyProbeHits(probeLineSlot) := False`, and
      // `earlyProbeData`'s content for that entry can never be read: `earlyProbeHitData`
      // is consumed only under `useEarlyProbe`, which requires `earlyProbeHit`, which
      // requires `earlyProbeHits(i)`. A probe that missed always falls back to the
      // ordinary S1 read path and never serves this register's value.
      probeLineLine := MuxOH(probeReadHitVec, rdData)
      probeLineOff  := probeReadOff
      probeLineSize := probeReadSize
    }
    when(probeLineValid && earlyProbeValids(probeLineSlot)) {
      earlyProbeReadies(probeLineSlot) := True
      earlyProbeHits(probeLineSlot)    := probeLineHit
      earlyProbeData(probeLineSlot)    := DcacheByteLane.extract(
        probeLineLine, probeLineOff, probeLineSize)
    }

    // Match the later resolved command by both token and VA. A token-present but
    // not-yet-ready result holds command.ready briefly rather than orphaning the
    // entry. Miss/unusable results are consumed and fall through to the ordinary
    // S1 read. Only a ready hit bypasses that redundant read.
    val earlyProbePresentVec = Vec(Bool(), earlyProbeDepth)
    val earlyProbeMatchVec   = Vec(Bool(), earlyProbeDepth)
    val earlyProbeSetWriteVec = Vec(Bool(), earlyProbeDepth)
    for (i <- 0 until earlyProbeDepth) {
      earlyProbePresentVec(i) := earlyProbeValids(i) &&
                                 (earlyProbeTokens(i) === loadCmdPort.payload.token) &&
                                 (earlyProbeVaddrs(i) === cmdVaddr)
      earlyProbeMatchVec(i) := earlyProbePresentVec(i) && earlyProbeReadies(i)
      // A held result is a snapshot of the array read. Any intervening real write
      // to its virtual set can stale it before the tagged command arrives.
      //
      // FMax fix (pea-cache-evict-2026-08-19 WNS-regression follow-up): originally
      // compared each of the four bounded entries against the four raw per-way
      // `wrEn`/`wrSet` write-port vectors directly (16 AND/OR terms across the
      // whole depth). There are only ever TWO distinct writers of those shared
      // physical ports in any one cycle -- the store-S3 RMW write
      // (`storeArrayWrite`/`storeArrayWriteSet`) and the REFILL/REPLAY array write
      // (`missArrayWrite`/`missSet`, see their declarations) -- so comparing each
      // entry against those two already-compact (valid, set) pairs is exactly
      // equivalent coverage (same two possible physical sources) without adding
      // fanout to `wrEn`/`wrSet` themselves.
      val setBitsOf = earlyProbeVaddrs(i)(offBits + setBits - 1 downto offBits)
      earlyProbeSetWriteVec(i) := (storeArrayWrite && (storeArrayWriteSet === setBitsOf)) ||
                                   (missArrayWrite && (missSet === setBitsOf))
    }
    // Task pea-cache-evict-2026-08-19 fix: `earlyProbeSetWriteVec` above is a purely
    // COMBINATIONAL, THIS-CYCLE-ONLY check -- despite this block's own comment
    // claiming to cover "any intervening real write", it only ever sees a same-set
    // write that happens to land on the EXACT cycle `loadCmdPort` consumes the
    // entry. A probe's captured `earlyProbeData` can sit in this queue for many
    // cycles (the whole point of the "four-entry result queue absorbs the fixed
    // probe->command distance" design) while a SAME-LINE store is still draining
    // through the ordinary S1/S2/S3 pipe (barriers can hold a store in S1 for a long
    // time -- see `storeMissBarrier`/`loadMissStoreBarrier` above). A same-line
    // store that lands ANY cycle between the probe's read and the command's
    // eventual consumption was silently invisible to this check, so the stale
    // pre-store snapshot got served as if it were current. Confirmed via a
    // cycle-exact whitebox trace (pea_4x_cache_evict.s, iteration 1): a check-code
    // load's early probe captured the target line BEFORE that iteration's own
    // PEA stores had drained, and the later `loadCmdPort` consumption of that
    // already-ready entry never re-checked the (by-then long past) write.
    //
    // Fix: latch `earlyProbeSetWriteVec` into a STICKY per-entry register the
    // moment it fires while the entry is valid, covering the entry's WHOLE dwell
    // window (not just the consume cycle), cleared only when the entry is
    // (re)allocated for a new probe. `earlyProbeHit` below ORs the live
    // (this-cycle) and sticky (any-past-cycle) checks so no coverage is lost.
    val earlyProbeStale = Vec.fill(earlyProbeDepth)(RegInit(False))
    earlyProbeStale.simPublic()   // test-visibility only (pea-cache-evict-2026-08-19
                                   // regression); no-op for synthesis
    for (i <- 0 until earlyProbeDepth) {
      when(earlyProbeValids(i) && earlyProbeSetWriteVec(i)) {
        earlyProbeStale(i) := True
      }
    }
    val earlyProbeTokenPresent = earlyProbePresentVec.asBits.orR
    val earlyProbeOwnsCmd      = earlyProbeMatchVec.asBits.orR
    val earlyProbeMatchIdx     = OHToUInt(earlyProbeMatchVec.asBits)
    // ── FMax: `earlyProbeHit` as a per-entry OR, not an index-then-select ──
    // The original form was
    //
    //   earlyProbeOwnsCmd && earlyProbeHits(earlyProbeMatchIdx) &&
    //     !earlyProbeSetWriteVec(earlyProbeMatchIdx) && !earlyProbeStale(earlyProbeMatchIdx)
    //
    // which SERIALISES the CAM behind three separate 4:1 selects: the compare vector has
    // to be reduced to `earlyProbeMatchIdx` (an OHToUInt) before any of the three
    // per-entry qualifiers can even be looked up, and only then does the final AND with
    // `earlyProbeOwnsCmd` run. Measured from `earlyProbeMatchVec` that is four LUT levels
    // (matchVec -> OHToUInt -> 4:1 mux -> AND), and it sits in the middle of the core's
    // longest cone -- `loadCmd.vaddr` -> this CAM -> `earlyProbeHit` -> `useEarlyProbe`
    // -> `loadProbePort.ready` -> the LS EU's `normalReqArm`/`issuePort.ready` ready chain
    // -> the IssueQueue's slot-compaction `triggers` clock enables (23 levels, WNS
    // -1.109ns, the sole remaining failing family on the standing 5.000ns OOC gate).
    //
    // Distributing the qualifiers over the vector collapses that to TWO levels: each
    // entry's five terms fold into one LUT (`matchVec(i)` is itself `presentVec(i) &&
    // readies(i)`, so this is `presentVec & readies & hits & !setWrite & !stale`, five
    // inputs), and the four results OR together in one more.
    //
    // EQUIVALENCE. `earlyProbeOwnsCmd` is `earlyProbeMatchVec.orR`, and `OHToUInt`
    // requires -- as its name says -- a one-hot input:
    //   - `earlyProbeMatchVec` all zero: `earlyProbeOwnsCmd` is False so the old form is
    //     False, and an OR of `matchVec(i) && ...` over an all-zero vector is False too.
    //   - `earlyProbeMatchVec` one-hot at `i`: `earlyProbeMatchIdx` is exactly `i`, so the
    //     old form is `hits(i) && !setWrite(i) && !stale(i)` and the OR collapses to its
    //     single non-masked term, the identical expression.
    // Those are the only two cases the surrounding design admits. A multi-hot
    // `earlyProbeMatchVec` is already outside this block's contract and was ALREADY
    // mis-handled before this change, not newly so: `OHToUInt` on a multi-hot input ORs
    // the set indices together, which can name an entry that does not match the command
    // at all (e.g. `0b0110` yields index 3), and `earlyProbeHitData` -- unchanged here --
    // would then serve that unrelated entry's data. The design requires one-hotness for
    // the data path regardless of how the hit qualifier is written.
    //
    // Multi-hotness needs two simultaneously-valid entries carrying the SAME token AND
    // the same VA. Probe tokens are `(False ## False ## tCtx.robId)` (LsEuPlugin), i.e.
    // one per in-flight ROB id, and an entry is invalidated both when its command
    // consumes it and by the token/`all` cancel port. The tripwire below pins that
    // one-hot premise directly instead of leaving it as an unchecked comment.
    val earlyProbeHitVec       = Vec(Bool(), earlyProbeDepth)
    for (i <- 0 until earlyProbeDepth) {
      earlyProbeHitVec(i) := earlyProbeMatchVec(i) && earlyProbeHits(i) &&
                             !earlyProbeSetWriteVec(i) && !earlyProbeStale(i)
    }
    val earlyProbeHit          = earlyProbeHitVec.asBits.orR
    GenerationFlags.simulation {
      assert(CountOne(earlyProbeMatchVec.asBits) <= U(1),
        "DcachePlugin: early-probe match vector is multi-hot (duplicate token+VA entries)",
        FAILURE)
      assert(earlyProbeHit === (earlyProbeOwnsCmd && earlyProbeHits(earlyProbeMatchIdx) &&
                                !earlyProbeSetWriteVec(earlyProbeMatchIdx) &&
                                !earlyProbeStale(earlyProbeMatchIdx)),
        "DcachePlugin: earlyProbeHit drifted from its original index-then-select definition",
        FAILURE)
    }
    val earlyProbeHitData      = earlyProbeData(earlyProbeMatchIdx)
    // `!ldS1Valid` is a REAL structural conflict, not a conservative gate: the
    // useEarlyProbe consume arm below (`elsewhen(loadCmdPort.fire && useEarlyProbe)`)
    // writes ldS2Valid/ldS2Hit/ldS2Direct/ldS2DirectData/ldS2Line directly, and so
    // does the unconditional `ldS2Valid := ldS1Valid; ldS2Hit := ldS1Hit; ldS2Line :=
    // ldS1Line` a few lines above (LOAD S2) -- both target the SAME registers for the
    // SAME next cycle. If ldS1Valid is true THIS cycle (an ordinary S1 read launched
    // last cycle is resolving into ldS2 THIS cycle), letting a probe-hit bypass fire
    // too would have the later-in-program-order assignment silently clobber the real
    // S1 resolution one cycle later -- dropping a genuine load response. That
    // conflict window is exactly ONE cycle (the cycle ldS1Valid reads true), never
    // longer.
    //
    // Task pea-early-probe-absorbing-2026-08-24 (P4, review-found absorbing state):
    // the bug was NOT this one-cycle gate itself -- it was what used to happen to a
    // command caught by it. `useEarlyProbe` going false on a conflict cycle used to
    // fall all the way through the elsewhen chain below to the PLAIN
    // `elsewhen(loadCmdPort.fire)` ordinary-read arm (queued probe entry silently
    // discarded, see the invalidate block below), which itself sets `ldS1Valid :=
    // True` for the FOLLOWING cycle -- so the very next command hits the identical
    // conflict again, forever, under a saturated back-to-back load stream. One
    // disruption (a miss, a split load, a same-set store, a full probe queue -- any
    // one cycle that fails to take the probe fast path) was therefore never
    // recoverable without a multi-cycle bubble in `loadCmdPort.valid`.
    //
    // Fix: `earlyProbeConflict` below withholds `loadCmdPort.ready` for exactly the
    // conflicting cycle instead of admitting the command down the ordinary-read arm.
    // The queued probe entry survives untouched (nothing consumes/invalidates it
    // when the command does not fire), `ldS1Valid` is NOT re-armed (no ordinary read
    // is launched), and it naturally clears the very next cycle -- so the held
    // command fires via the fast path one cycle later instead of falling back for
    // the rest of the burst. Cost of a disruption: one stall cycle for the ONE
    // command caught on the conflicting edge, not a lasting mode change.
    val useEarlyProbe          = earlyProbeHit && !ldS1Valid
    val earlyProbeConflict     = earlyProbeHit && ldS1Valid
    val earlyProbeFreeVec      = Vec(Bool(), earlyProbeDepth)
    for (i <- 0 until earlyProbeDepth) earlyProbeFreeVec(i) := !earlyProbeValids(i)
    val earlyProbeHasFree      = earlyProbeFreeVec.asBits.orR
    val earlyProbeReusesConsume = !earlyProbeHasFree && loadCmdPort.valid && useEarlyProbe
    // ── FMax: the two CAM-dependent terms of `loadProbePort.ready`, folded into one ──
    // `loadProbePort.ready` (in the load FSM's IDLE arm below) used to AND together two
    // SEPARATE `useEarlyProbe`-dependent terms:
    //
    //   earlyProbeHasAllocSlot  ==  F || (!F && V && U)       -- "a queue slot exists"
    //   (!loadCmdPort.valid || useEarlyProbe)  ==  !V || U    -- "the shared read port
    //                                                            is free this cycle"
    //
    // with F = `earlyProbeHasFree`, V = `loadCmdPort.valid`, U = `useEarlyProbe`. Those
    // two are redundant with each other, and the redundancy cost two LUT levels on the
    // longest cone in the core (U -> earlyProbeReusesConsume -> earlyProbeHasAllocSlot ->
    // the ready AND-tree). Their conjunction simplifies EXACTLY, by cases on V:
    //
    //   (F || (!F && V && U)) && (!V || U)
    //     = (F || (V && U)) && (!V || U)            [absorption on the first term]
    //   V = 0 :  (F || 0) && (1)      =  F
    //   V = 1 :  (F || U) && (U)      =  U          [absorption: U && (F || U) == U]
    //     = Mux(V, U, F)
    //
    // so ONE 3-input select replaces both, and `useEarlyProbe` now reaches
    // `loadProbePort.ready` through a single LUT instead of three. This is a pure
    // Boolean identity -- every (F, V, U) assignment gives the same result as before --
    // not a policy change: a command that is present and does NOT consume a queued hit
    // still blocks the probe launch (V=1, U=0 -> False), and a cycle with no command
    // still needs a genuinely free slot (V=0 -> F).
    //
    // `earlyProbeReusesConsume` is deliberately left as its own signal: the
    // consume-and-replace guard at the `loadCmdPort.fire && earlyProbeOwnsCmd` block
    // below still reads it, and it is NOT the same expression as this one.
    val earlyProbeSlotAndPortFree = Mux(loadCmdPort.valid, useEarlyProbe, earlyProbeHasFree)
    // `OHToUInt` requires a one-hot input. The free vector is normally multi-hot;
    // mask it first or simultaneous residents can alias the same physical entry.
    val earlyProbeAllocIdx     = Mux(
      earlyProbeHasFree,
      OHToUInt(OHMasking.first(earlyProbeFreeVec.asBits)),
      earlyProbeMatchIdx)
    earlyProbeOwnsCmd.simPublic(); useEarlyProbe.simPublic(); earlyProbeConflict.simPublic()
    // Respond ONLY on a HIT. A MISS falls through to REFILL below. Translation
    // faults never enter this pipe: the resolved-command contract requires the LS
    // producer to consume them before issuing loadCmd.
    // (The actual response is built one cycle later, in the LOAD S2 block below --
    // FMax closure Slice 2. The HIT-ONLY policy described here is unchanged; only
    // the cycle the response leaves on moved.)

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

    // ---- LOAD S2 (registered post-hit-detect response build) ----
    // FMax closure Slice 2 (2026-08-07): the old S1 response build (way-select ->
    // byte-lane extract -> loadRspPort) was one flat ~13-level combinational cone
    // running from the BRAM tag-read output straight into LsEuPlugin's `compData`
    // register -- the worst post-route path after Slice 1 (-1.987ns). See
    // docs/superpowers/specs/2026-08-07-fmax-slice2-dcache-s1-split-design.md.
    //
    // S1's hit/miss DECISION (`ldS1Hit`, consumed ONLY by the miss/REFILL trigger
    // in the load FSM below) is deliberately UNTOUCHED -- only the HIT RESPONSE
    // DATA moves one cycle later, so miss-detect/eviction/refill timing is bit-for-
    // bit identical to before. Costs one uniform extra cycle of load-to-use latency
    // on every D-cache load hit (an explicitly accepted tradeoff, the 5th of this
    // shape in this file). No consumer-side change is needed: LsEuPlugin's `WAIT`/
    // `WAIT_A`/`WAIT_B` states and ExceptionUnit's vector-fetch / RTE-pop states are
    // all `when(loadRsp.valid)` event-driven, exactly like the already-much-later
    // busFaultResp/inhibitedResp REPLAY pulses they already handle.
    val ldS2Valid = RegInit(False)
    val ldS2Hit   = Reg(Bool())
    val ldS2Line  = Reg(Bits(128 bits))
    val ldS2Off   = Reg(UInt(offBits bits))
    val ldS2Size  = Reg(Size())
    val ldS2Direct = RegInit(False)
    val ldS2DirectData = Reg(Bits(32 bits))
    ldS2Valid := ldS1Valid
    ldS2Hit   := ldS1Hit
    ldS2Line  := ldS1Line
    ldS2Off   := ldS1Off
    ldS2Size  := ldS1Size
    ldS2Direct := False
    val ldS2Resp = ldS2Valid && ldS2Hit
    ldS2Valid.simPublic(); ldS2Hit.simPublic(); ldS2Resp.simPublic()

    loadRspPort.valid         := ldS2Resp || busFaultResp || inhibitedResp
    loadRspPort.payload.data  := Mux(inhibitedResp,
                                      DcacheByteLane.extract(missLine, missOff, missSize),
                                      Mux(ldS2Direct, ldS2DirectData,
                                          DcacheByteLane.extract(ldS2Line, ldS2Off, ldS2Size)))
    loadRspPort.payload.line  := Mux(inhibitedResp, missLine, ldS2Line)
    // Translation faults are terminated upstream and never become cache commands.
    // The D-cache response fault bit is exclusively a physical AXI refill error.
    loadRspPort.payload.fault := busFaultResp

    // ---- STORE drain (elastic S0 / S1 read / S2 compare / S3 merge+write) ----
    // FMax: the store RMW (old line readAsync + 16-lane byte-merge + write) used to
    // be a combinational cycle fronted by the far-placed exc-FSM/DTLB cone. With all
    // reads SYNC the old line + hit-tag are read from BRAM:
    //   store-S0 (on storePort.fire): latch the raw store payload into regs PHYSICALLY
    //     at the cache (a short route ending at a flop).
    //   store-S1 (next cycle): launch the SHARED sync read (old line + hit-tag) at the
    //     store's set — UNLESS the load FSM is using the read port this cycle (load
    //     priority); on conflict the store holds in S1 and retries next cycle.
    //   store-S2 (after the read lands): resolve hit/way and capture the selected line.
    //   store-S3: merge bytes, write the array/dirty state, and emit a local hit ack
    //     or launch the serialized AXI/write-allocate backend.
    // Resident COPYBACK hits overlap at every boundary and sustain II=1. Variable-
    // latency classes are admission barriers. The load-refill engine
    // (REFILL's allocate write, REPLAY's write-allocate merge) is completely
    // independent of the store side and can write valids/tagMem/dataMem across that
    // window. Two consequences, both
    // interlocked by `refillWriteHold`
    // (declared just above the load FSM below -- read its comment before touching
    // any of this):
    //   - a same-SET refill landing inside the S1..S2 window would invalidate the
    //     store's REGISTERED tag-read, so its S2 hit-detect would be stale;
    //   - a same-WAY refill (any set) landing on the S3 cycle itself would win the
    //     shared per-way array write port and silently DROP the store's own write.
    // `refillWriteHold` holds the refill side off in both cases (delay, never drop),
    // which is what makes the S2 lookup and S3 array write correct.
    val stMergeReg = Reg(Bits(128 bits))
    val stStrbReg  = Reg(Bits(16 bits))
    val stAddrReg  = Reg(UInt(32 bits))
    val stAwDone   = Reg(Bool()) init True   // True == no store in flight
    val stWDone    = Reg(Bool()) init True
    // D6/D26/D30 store-side sequencer state. LINE-RELATIVE, like the load side's.
    val stSubP      = Reg(UInt(offBits bits)) init 0
    val stSubEnd    = Reg(UInt(offBits + 1 bits)) init 0
    val stSubActive = RegInit(False)    // this drain is an INHIBITED covered sequence
    val stSubErr    = RegInit(False)    // OR of every sub-transaction's B response
    // Task #236 fix: the store-side mirror of `subLog2Reg` above -- `stSubLog2` used to be
    // an UNCONDITIONAL top-level `val` (not even gated behind `stSubActive`), recomputing
    // `MmioCover.stepLog2`'s adder/comparator tree every single cycle regardless of
    // activity. Registered at the same two sites `stSubP`/`stSubEnd` are ever written.
    val stSubLog2Reg = Reg(UInt(2 bits)) init 0
    // ── FMax: `stSubLast` is the store-side mirror of task #236's `stSubLog2Reg` ──
    // `stSubLast` ("this is the FINAL sub-transaction of the covered sequence") is a
    // PURE FUNCTION of four registers -- `stSubActive`, `stSubP`, `stSubEnd` and
    // `stSubLog2Reg` -- yet it used to be recomputed combinationally every cycle
    // (`!stSubActive || ((stSubP +^ stSubBytes) === stSubEnd)`, a 4-bit shifter + a
    // 5-bit adder + a 5-bit equality). That put `stSubP` at the HEAD of the single
    // longest combinational cone in the whole core, because `stSubLast` immediately
    // qualifies `storeAckReg` (`(storeBAck && stSubLast) || ...`) -- which is NOT a
    // register despite its name -- and `storeAck` then fans out, still
    // combinationally, through `sq.io.drainAck` -> `drainAckFire` -> `terminalAck`
    // -> the SQ head pop / `sqCompletion` -> LsEuPlugin's `preciseReplayClaimsComp`
    // -> `olderThanTxComp` -> `xlate.rsp.ready` -> `txRspFire` ->
    // `dcache.loadProbeResolve.valid` -> `probeResolveMatchesRead` ->
    // `probeResolvedTag` -> the early-VIPT probe's way-hit compare -> the 128-bit
    // `rdData(probeReadHitWay)` way mux -> `probeLineLine`. A measured OOC synth of
    // this exact netlist had EVERY failing endpoint family but one rooted at
    // `stSubP_reg` (116 of 121 families, worst -0.761ns at `probeLineLine_reg`),
    // with ~1.1ns of that 5.742ns path spent just getting from `stSubP` to
    // `storeAck` through the four LUT levels of this expression.
    //
    // Retimed exactly the way task #236 retimed `stSubLog2`: registered at the SAME
    // three sites that are the only writers of the four inputs, always computed from
    // the NEW values those sites are installing (never from the stale pre-edge
    // registers), so the register is bit-identical to the combinational form in
    // every cycle. `init True` matches the reset state (`stSubActive init False`
    // => `stSubLast` = True). A simulation-only tripwire below (`stSubLast`'s own
    // declaration site) machine-checks that equivalence every cycle rather than
    // trusting this argument.
    val stSubLastReg = RegInit(True)
    // The single definition of `stSubLast` in terms of a (possibly not-yet-committed)
    // set of sequencer values. Used both for the register updates below and for the
    // simulation tripwire, so the two can never drift apart.
    def stSubLastOf(active: Bool, p: UInt, e: UInt, log2: UInt): Bool =
      !active || ((p +^ (U(1, 4 bits) |<< log2).resize(4 bits)) === e)
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
    //     BOTH directions. `evictAxiPairOpen` (below) blocks the registered store-S3
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
    // Does the serialized store backend want (or currently hold) the physical
    // AXI write channels THIS cycle? Its own drive below is completely
    // unconditional on this signal (it never checks it) -- this exists PURELY to
    // gate EVICT_WR's own drive off, so the two never physically collide.
    val storeWantsAxi = !stAwDone || !stWDone
    // The symmetric direction: is EVICT_WR's own aw+w pair currently open (one or
    // both legs not yet accepted)? Gates the registered store-result stage's AXI
    // kickoff (see `pendingWtKickoff` and its consumer below).
    val evictAxiPairOpen = !evictAwDone || !evictWDone
    // Level-held "still need to actually kick off the AXI aw/w for a write-
    // through/inhibited drain" latch -- set by store-S3 (one cycle, a pulse)
    // INSTEAD OF directly setting stAwDone/stWDone False when `evictAxiPairOpen`
    // holds it off; consumed (retried every cycle) below, exactly mirroring
    // `pendingStoreMiss`'s existing shape.
    val pendingWtKickoff = RegInit(False)

    // Ordered store-drain admission. COPYBACK hits may occupy the existing
    // S0/S1/S2/S3 boundaries concurrently; variable-latency classes remain a
    // single-owner barrier and a discovered COPYBACK miss freezes younger stages.
    //
    // WT-pipelining (this task): `storeOutstanding` used to be a pure "S0..S3
    // stage occupancy" counter (max 4) because the only class that could remain
    // admitted-but-unacked BEYOND S3 was a fully-serial one, and admission itself
    // capped that at exactly one. A non-precise (fastStore) WRITETHROUGH store now
    // pipelines its AXI write the same way a COPYBACK hit's on-chip resolve always
    // has -- its `storeAckReg` no longer fires until the real AXI B, which can lag
    // admission by a full round trip, so several such descriptors can be
    // concurrently "outstanding" (admitted, kicked off, awaiting B) well past S3.
    // Widened 3->4 bits with real headroom (see the assert below for the proven
    // bound) instead of guessing a wider width.
    val storeOutstanding = RegInit(U(0, 4 bits))
    val serialStoreInFlight = RegInit(False)
    val storeMissBarrier = RegInit(False)
    // A load miss snapshots its dirty victim when ldS1 resolves. Any older store
    // already in S3 is forwarded into that snapshot; a store still in S1 has no
    // final line yet and must remain parked until the load refill/replay finishes.
    // This prevents a post-snapshot S3 write from being silently replaced by the
    // refill while EVICT_WR writes the pre-store victim image to memory.
    val loadMissStoreBarrier = RegInit(False)
    val refillNeedsStoreDrain = Bool(); refillNeedsStoreDrain := False
    storeOutstanding.simPublic(); serialStoreInFlight.simPublic(); storeMissBarrier.simPublic()
    loadMissStoreBarrier.simPublic()

    // ---- WT-pipelining admission classification -------------------------------
    // A store is fully-serial when it is `precise` (LsEuPlugin's `!fastStore`
    // classification, `sq.io.alloc.payload.precise := !fastStore`) OR when it is
    // INHIBITED. In REAL CPU traffic these two are the same set: `fastStore`
    // (LsEuPlugin.scala:612) requires `cmode =/= INHIBITED` unconditionally, so an
    // INHIBITED descriptor is ALWAYS `precise` already and the `=== INHIBITED`
    // term never actually changes admission for anything LsEuPlugin sends. It is
    // kept anyway, explicitly, as defense-in-depth rather than trusting that
    // external invariant here: DcachePlugin has no way to verify what produced
    // `storePort.payload`, and a directed whitebox test (or a future bug upstream)
    // presenting an INHIBITED descriptor with `precise` cleared must still get the
    // ORIGINAL, fully-serial treatment INHIBITED always had (pre-this-task,
    // `cacheMode =/= COPYBACK` covered it unconditionally) -- fail closed, not
    // open. The counter symmetry itself does not depend on this term (see
    // `stIsPipelinedWtReg`'s own decl comment for why the wtOutstanding
    // increment/decrement pair is self-consistent regardless), but admission
    // policy for a mis-tagged INHIBITED descriptor should not silently change too.
    //
    // The only descriptor whose ADMISSION treatment actually changes from before
    // this task is a WRITETHROUGH store that IS `fastStore` (MMU on, page not
    // inhibited, real traffic only ever reaches this via LsEuPlugin) -- exactly
    // the "ordinary hot path" this task targets.
    //
    // Why this doesn't touch bus-error precision (see this task's design note,
    // also recorded in the commit message): a `!fastStore` store already
    // completes its ROB bookkeeping BEFORE its physical write happens
    // (LsEuPlugin's `captureCompletion`/precise-drain split) -- a bus error on
    // its beat was ALREADY async/diagnostic-only, not synchronous, before this
    // change (see the `storeErrReg && !stPreciseReg` -> `diagFaultPulse` kind=0
    // site far below, which existed unconditionally beforehand and covered this
    // exact case already, one store at a time). Pipelining only changes how many
    // such already-async writes can be concurrently in flight, never whether a
    // given one's fault is precise.
    val inputStoreSerial = storePort.payload.precise ||
      (storePort.payload.cacheMode === CacheMode.INHIBITED)
    val inputPipelinedWt = !inputStoreSerial &&
      (storePort.payload.cacheMode === CacheMode.WRITETHROUGH)
    val inputCopyback = !inputStoreSerial &&
      (storePort.payload.cacheMode === CacheMode.COPYBACK)
    inputPipelinedWt.simPublic(); inputCopyback.simPublic()

    // Count of admitted, non-precise WRITETHROUGH descriptors whose AXI write has
    // not yet B-acked (a STRICT SUBSET of `storeOutstanding`, tracked separately
    // so admission can cap concurrent AXI-pending WT writes independently of
    // COPYBACK's own, much shorter, S0..S3-bounded residency). Capped at
    // `MAX_WT_OUTSTANDING` below by the admission gate -- 3 bits (max 7) is ample
    // headroom over that cap.
    val MAX_WT_OUTSTANDING = 4
    val wtOutstanding = RegInit(U(0, 3 bits))
    wtOutstanding.simPublic()

    // ---- store-S0: elastic payload latch (the cache-boundary flop) ----
    val s0Valid   = RegInit(False)
    val s0Payload = Reg(DStoreCmd())
    s0Valid.simPublic()

    // ---- store-S1: launch the shared sync read (old line + hit-tag) ----
    // Decoded off the REGISTERED S0 payload. The read launches onto the shared
    // rdSet/rdEn port only when the load FSM is NOT using it (arbiter below); on a
    // conflict the store stays in S1 (s1Pending) and retries next cycle.
    val stS1Valid   = RegInit(False)      // a store is in S1 awaiting its read launch
    val stS1Payload = Reg(DStoreCmd())
    stS1Valid.simPublic()
    val stS1Set     = stS1Payload.paddr(offBits + setBits - 1 downto offBits)
    val stS1Off     = stS1Payload.paddr(offBits - 1 downto 0)
    val stS1Tag     = stS1Payload.paddr(31 downto offBits + setBits)
    stS1Set.simPublic(); stS1Off.simPublic(); stS1Tag.simPublic()   // DEBUG (pea-cache-evict-2026-08-19), temporary
    // Bounded arbitration only under real read-port contention.  One fresh load or
    // probe may win over a waiting store; the next fresh read yields.  Internal
    // replay/refill/maintenance work is never delayed by this bit.
    val storeReadOwed = RegInit(False)
    storeReadOwed.simPublic()

    // ---- store-S2: registered lookup request; terminate tag compare here ----
    val stS2Valid   = RegInit(False)
    val stS2Payload = Reg(DStoreCmd())
    val stS2Set     = stS2Payload.paddr(offBits + setBits - 1 downto offBits)
    val stS2Off     = stS2Payload.paddr(offBits - 1 downto 0)
    val stS2Tag     = stS2Payload.paddr(31 downto offBits + setBits)
    stS2Valid := False  // default; armed when S1's read launches

    // ---- store-S3: registered lookup result; merge/write/ack stage ----
    // This cut is intentional for FPGA timing: tag BRAM + compare ends at these
    // flops, while byte merge, dirty update and the array write start here.
    val stS3Valid   = RegInit(False)
    val stS3Payload = Reg(DStoreCmd())
    val stS3Hit     = RegInit(False)
    val stS3Way     = Reg(UInt(wayBits bits))
    val stS3OldLine = Reg(Bits(128 bits))
    val stS3Set     = stS3Payload.paddr(offBits + setBits - 1 downto offBits)
    val stS3Off     = stS3Payload.paddr(offBits - 1 downto 0)
    val stS3Tag     = stS3Payload.paddr(31 downto offBits + setBits)
    val stS3Inhibited = stS3Payload.cacheMode === CacheMode.INHIBITED
    val stS3Copyback  = stS3Payload.cacheMode === CacheMode.COPYBACK
    stS3Valid := False

    val stS3MergeData = Mux(stS3Payload.useStrb,
      stS3Payload.lineData,
      DcacheByteLane.storeData(stS3Off, stS3Payload.size, stS3Payload.data))
    val stS3MergeStrb = Mux(stS3Payload.useStrb,
      stS3Payload.strb,
      DcacheByteLane.storeStrb(stS3Off, stS3Payload.size))
    val stS3OldBytes = stS3OldLine.subdivideIn(8 bits)
    val stS3MrgBytes = stS3MergeData.subdivideIn(8 bits)
    val stS3NewBytes = Vec(Bits(8 bits), 16)
    for (i <- 0 until 16)
      stS3NewBytes(i) := Mux(stS3MergeStrb(i), stS3MrgBytes(i), stS3OldBytes(i))
    val stS3MergedLine = stS3NewBytes.asBits
    val stS3ArrayWrite = stS3Valid && stS3Hit && !stS3Inhibited
    // A miss may launch the shared synchronous victim read on the same cycle this
    // S3 store writes that exact set/way.  The miss resolves one cycle later, when
    // the live S3 signals are already gone, while both the generated simulation
    // RAM and FPGA block RAM are allowed to return the pre-write line/dirty bit for
    // that read-during-write collision.  Carry the completed write across that one
    // cycle so both load- and store-miss victim snapshots can bypass the stale RAM
    // result.  This complements (and does not replace) their existing live-S3
    // bypass, which covers a write coincident with the resolution cycle itself.
    val stS3WriteD1     = RegNext(stS3ArrayWrite) init False
    val stS3WriteSetD1  = Reg(UInt(setBits bits))
    val stS3WriteWayD1  = Reg(UInt(wayBits bits))
    val stS3WriteTagD1  = Reg(UInt(tagBits bits))
    val stS3WriteLineD1 = Reg(Bits(128 bits))
    val stS3WriteCopybackD1 = Reg(Bool())
    stS3WriteD1.simPublic()
    when(stS3ArrayWrite) {
      stS3WriteSetD1      := stS3Set
      stS3WriteWayD1      := stS3Way
      stS3WriteTagD1      := stS3Tag
      stS3WriteLineD1     := stS3MergedLine
      stS3WriteCopybackD1 := stS3Copyback
    }
    stS3OldLine.simPublic(); stS3MergedLine.simPublic()   // DEBUG (pea-cache-evict-2026-08-19), temporary

    stS3Valid.simPublic(); stS3Payload.simPublic(); stS3Hit.simPublic()
    stS3Set.simPublic(); stS3Way.simPublic(); stS3ArrayWrite.simPublic()
    // DEBUG (task #189 investigation, temporary): sim-only visibility into the
    // store RMW hit/miss decision. simPublic is a no-op for synthesis.
    stS2Valid.simPublic(); stS2Payload.simPublic()

    // Store-S2 hit-detect + cacheability decode. Declared HERE (rather than next to
    // the S2 merge/write block far below, where it used to live) purely so that
    // `refillWriteHold` -- which must know which registered S3 result will drive the
    // shared per-way array write port -- can be built before
    // the load FSM that consumes it. Inputs are all registers/BRAM outputs available
    // at this point (valids, rdTag, stS2Payload); no behavioural change from the move.
    val stS2HitVec = Vec(Bool(), ways)
    for (w <- 0 until ways)
      stS2HitVec(w) := rdValid(w) && (rdTag(w) === stS2Tag)
    stS2HitVec.simPublic()   // DEBUG (task #189), temporary
    // Task P1.4: an INHIBITED store never touches the cache array (skip the RMW line
    // write entirely) — only the AXI write-through beat is unconditional.
    // Task P4.1: a COPYBACK-hit store resolves ENTIRELY on-chip (RMW + dirty-bit, no
    // AXI beat at all); WRITETHROUGH (hit or miss) and INHIBITED are unchanged.
    val stS2Inhibited = stS2Payload.cacheMode === CacheMode.INHIBITED
    val stS2Copyback  = stS2Payload.cacheMode === CacheMode.COPYBACK
    val stS2HitAny    = stS2HitVec.orR
    val stS2HitWay    = OHToUInt(stS2HitVec)
    val stS2MergeData = Mux(stS2Payload.useStrb,
      stS2Payload.lineData,
      DcacheByteLane.storeData(stS2Off, stS2Payload.size, stS2Payload.data))
    val stS2MergeStrb = Mux(stS2Payload.useStrb,
      stS2Payload.strb,
      DcacheByteLane.storeStrb(stS2Off, stS2Payload.size))
    val stS2RawHitLine = MuxOH(stS2HitVec, rdData)
    // If the preceding store writes this physical line in S3 while this lookup's
    // synchronous read returns, capture the preceding store's final line.  FPGA
    // BRAM read-during-write behaviour is not part of the architectural contract.
    val stS2UsesS3Line = stS3ArrayWrite && stS2HitAny &&
      (stS2Set === stS3Set) && (stS2Tag === stS3Tag) && (stS2HitWay === stS3Way)
    val stS2CapturedLine = Mux(stS2UsesS3Line, stS3MergedLine, stS2RawHitLine)
    stS2UsesS3Line.simPublic()
    // Historical test/debug alias: the physical write moved to registered S3.
    val stS2ArrayWrite = stS3ArrayWrite
    stS2ArrayWrite.simPublic()   // test-visibility only (DcacheDrainRefillRaceSpec's
    // same-way-collision coincidence check); no-op for synthesis.
    val storeMissDiscovered = stS2Valid && stS2Copyback && !stS2HitAny
    val loadMissDiscovered  = ldS1Valid && !ldS1Hit
    val loadVictimFromS3Dbg = Bool(); loadVictimFromS3Dbg := False; loadVictimFromS3Dbg.simPublic()
    val storeVictimFromS3Dbg = Bool(); storeVictimFromS3Dbg := False; storeVictimFromS3Dbg.simPublic()
    storeMissDiscovered.simPublic()
    loadMissDiscovered.simPublic()

    when(storeAllocAckReg) { storeMissBarrier := False }
    when(storeMissDiscovered) { storeMissBarrier := True }

    // ---- shared read-port arbitration (FMax: keep the live load-accept cone OUT
    // of the high-fanout BRAM read-address net) ----
    // The store drives the read address as the BASE (off the REGISTERED stS1Payload —
    // a clean flop->BRAM-address arc). The LOAD FSM below OVERRIDES rdSet/rdEn LAST
    // (last-assignment wins) on a load-accept / REPLAY, so the load keeps priority.
    // Crucially the load-vs-store select on the rdSet net is ONLY `loadCmdPort.fire /
    // REPLAY` (the same cone baseline already had on the dataMem read address) — the
    // store base adds NO arbiter cone to that fo=high net. The "did the store actually
    // get the port?" question (loadUsesPort) feeds ONLY the low-fanout stS2Valid
    // control register, NOT the BRAM address — breaking the post-route critical path
    // (loadCmdPort.ready -> arbiter -> tag/dataMem read-address).
    // A load-miss decision may speculatively repoint the read port at this S1
    // descriptor; only the S1->S2 valid capture is held below. Keeping the raw
    // address drive independent of combinational load hit/miss avoids a
    // tag-BRAM-result -> next BRAM-address timing cone.
    when(stS1Valid && !storeMissBarrier && !loadMissStoreBarrier) {
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
    // the SAME set (a DIFFERENT line) landing inside that window would go unnoticed
    // by the store's stale registered tag-read: S2 would then
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
    // cache update: the S2 hit arm's dirty-bit write (`dirtys(w)(stS2Set) := True`,
    // now `dirtysWrEn/dirtysWrSet/dirtysWrData` post-task-#240) is a SEPARATE write
    // port, indexed by a DIFFERENT set, so it is NOT dropped
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
    // Liveness with a real multi-flight Stream is explicit: once a refill waits,
    // `refillNeedsStoreDrain` stops new store admission and the finite S0/S1/S2/S3
    // pipe drains. Parked younger descriptors are excluded from this hold while a
    // miss barrier owns the arrays, so they cannot deadlock the refill that will
    // eventually release them.
    //
    // LS-cluster review finding P5 note: `stS1Set`/`stS2Set`/`missSet` below are raw
    // SET-INDEX compares (`paddr(offBits+setBits-1 downto offBits)`), not tag-aware --
    // "same set" is a strict SUPERSET of "same line" (same line implies same set index
    // trivially). This hold therefore ALREADY covers an exact-same-line collision
    // unconditionally, independent of whatever the SQ's `sameLine` stall does or does
    // not exclude. That generality used to be incidental (the SQ's old mode-agnostic
    // `sameLine` stall meant a load could never even reach here while an older
    // same-line store was mid-drain, so this term's same-line coverage was never
    // exercised in practice); since StoreQueue.scala's `sameLine` no longer stalls a
    // same-line, non-overlapping load behind an older undrained COPYBACK store, this
    // term's same-line generality is now the ACTUAL, load-bearing protection for that
    // exact race (a same-line load-refill landing during a COPYBACK store's S1/S2
    // hit-write window) -- verified directly against this RTL, not assumed.
    val refillWriteHold = (stS1Valid && !storeMissBarrier && !loadMissStoreBarrier &&
                           (stS1Set === missSet)) ||
                          (stS2Valid && (stS2Set === missSet)) ||
                          // NOTE: this 3rd (same-way) term only means anything
                          // while `victimWay` holds a currently-relevant value,
                          // i.e. during REFILL/REPLAY immediately after a
                          // miss-detect latched it. In IDLE `victimWay` is
                          // stale from the previous miss and this term is
                          // harmless-but-meaningless there (nothing consumes
                          // `refillWriteHold` in IDLE today) — don't reuse it
                          // in an IDLE-adjacent context without re-checking.
                          (stS3ArrayWrite && (stS3Way === victimWay))

    // REPLAY's store-miss merge must never collide with a real S2 array writer.
    // Younger accepted descriptors are deliberately held in S0/S1 by
    // `storeMissBarrier`; S1 occupancy alone is harmless because it has not launched
    // a read and drives no write. Keeping this register-only S2 guard preserves the
    // Lever-F timing cut while allowing the miss merge to complete in the presence
    // of safely parked younger descriptors.
    val storeDrainRefillHold = stS2Valid || stS3Valid

    // A live pulse remains as a non-vacuity/debug hook. Normal Stream/barrier logic
    // makes it unreachable; a forced internal positive control may opt in.
    val storeDrainHoldFired = Bool(); storeDrainHoldFired := False
    storeDrainHoldFired.simPublic()
    val storeDrainHoldExpected = RegInit(False); storeDrainHoldExpected.simPublic()
    GenerationFlags.simulation {
      assert(!(storeDrainHoldFired && !storeDrainHoldExpected),
        "DcachePlugin: an S2 array writer survived into COPYBACK drain-miss REPLAY; " +
        "the merge was delayed, but the miss barrier failed to park younger stores",
        FAILURE)
    }

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
    val freshLoadUsesPort = Bool(); freshLoadUsesPort := False

    // ---- LOAD FSM (OVERRIDES the shared read port with PRIORITY over the store) ----
    val fsm = new StateMachine {
      val IDLE     = new State with EntryPoint
      val EVICT_WR = new State
      val REFILL   = new State
      val REPLAY   = new State

      // Throughput slices A+C0: S2 is a frozen response snapshot (no RAM/AXI use),
      // and the one-entry `loadShadowCmd` above closes the remaining S1 miss shadow.
      // Therefore IDLE may accept a resolved load every cycle on the all-hit path:
      // while the older request resolves in S1, the new request either launches its
      // synchronous read (older hit) or is captured for ordered replay (older miss).
      // The latter handshake cannot be followed by another accept because the FSM
      // leaves IDLE on the miss edge. This is a bounded two-operation miss window,
      // not unbounded hit-under-miss state, and needs no response tag or line buffer.

      // Whether the load FSM uses the shared read port THIS cycle (set in the
      // load-accept and REPLAY arms below). The store arbiter reads this to defer.
      val loadUsesPort = False

      IDLE.whenIsActive {
        busy := False
        // `DLoadCmd` is a resolved request: its producer already translated the
        // address, rejected translation faults, and registered paddr/cacheMode.
        // Never consult a live, untagged DTLB response here. That response can
        // already belong to a younger request once the LS front end is pipelined.
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
        // Early virtual-set lookup admission. A normal resolved command owns the
        // single BRAM read port; a command consuming a queued hit needs no read, so
        // the next probe may launch on the same edge. The four-entry result queue
        // absorbs the fixed probe->command distance at one launch per cycle.
        // FMax: the six CAM-INDEPENDENT admission terms are grouped first and the single
        // folded CAM-dependent term (`earlyProbeSlotAndPortFree`, see its declaration for
        // the case-by-case proof that it is exactly the old `earlyProbeHasAllocSlot &&
        // (!loadCmdPort.valid || useEarlyProbe)`) is ANDed in last, so `useEarlyProbe`
        // reaches this net through one LUT rather than three.
        val probeAdmitBase = !resetSweepBusy && !loadShadowValid &&
                             !pendingStoreMiss && !maintBusyReg &&
                             !(ldS1Valid && !ldS1Hit) &&
                             !(storeReadOwed && stS1Valid)
        loadProbePort.ready := probeAdmitBase && earlyProbeSlotAndPortFree
        when(loadProbePort.fire) {
          val canceledAtLaunch = loadProbeCancelPort.valid &&
                                 (loadProbeCancelPort.payload.all ||
                                  (loadProbeCancelPort.payload.token === loadProbePort.payload.token))
          rdSet        := loadProbePort.payload.vaddr(offBits + setBits - 1 downto offBits)
          rdEn         := True
          loadUsesPort := True
          freshLoadUsesPort := True
          when(!canceledAtLaunch) {
            earlyProbeValids(earlyProbeAllocIdx)  := True
            earlyProbeReadies(earlyProbeAllocIdx) := False
            earlyProbeHits(earlyProbeAllocIdx)    := False
            earlyProbeTokens(earlyProbeAllocIdx)  := loadProbePort.payload.token
            earlyProbeVaddrs(earlyProbeAllocIdx)  := loadProbePort.payload.vaddr
            // Task pea-cache-evict-2026-08-19 fix: a freshly (re)allocated entry's
            // sticky staleness must not carry over from whatever this physical slot
            // held before -- reset it here, elaborated AFTER (and so overriding on
            // the shared reuse-same-cycle edge) the generic per-cycle sticky-set
            // loop above. `earlyProbeSetWriteVec(earlyProbeAllocIdx)` itself is NOT
            // usable for this: it still compares against the OLD occupant's
            // `earlyProbeVaddrs` (this same block overwrites that register only for
            // the FOLLOWING cycle), so a write racing THIS NEW probe's own address
            // on THIS SAME launch cycle would go undetected by it. Check the new
            // probe's own target set directly instead -- this is the exact
            // "read-launch-vs-S3-write, same cycle" hazard `stS1SameLineAsS3`
            // patches for the store side, mirrored here for the probe's own launch.
            //
            // FMax fix (WNS-regression follow-up): the original revision of this
            // check scanned the raw per-way `wrEn`/`wrSet` write-port vectors (a
            // 4-way OR of per-way ANDs), adding a brand-new consumer directly onto
            // those already widely-fanned-out physical BRAM write-port nets. There
            // are only ever TWO distinct writers of those ports in any one cycle --
            // the store-S3 RMW write (`stS3ArrayWrite`/`stS3Set`, already read one
            // screen down by `stS1SameLineAsS3` for the store side's own mirror-image
            // hazard) and the REFILL/REPLAY array write (`missArrayWrite`/`missSet`,
            // both of its two drive sites target `missSet` exclusively -- see their
            // declarations). Comparing against those two already-compact (valid, set)
            // pairs instead is exactly equivalent (same coverage: any write, from
            // either possible source, to this probe's target set) but never touches
            // `wrEn`/`wrSet` at all.
            val allocTargetSet = loadProbePort.payload.vaddr(offBits + setBits - 1 downto offBits)
            val allocRacesArrayWrite = (stS3ArrayWrite && (stS3Set === allocTargetSet)) ||
                                       (missArrayWrite && (missSet === allocTargetSet))
            earlyProbeStale(earlyProbeAllocIdx) := allocRacesArrayWrite
            probeReadValid  := True
            probeReadSlot   := earlyProbeAllocIdx
            probeReadSet    := loadProbePort.payload.vaddr(offBits + setBits - 1 downto offBits)
            probeReadTag    := loadProbePort.payload.paddr(31 downto offBits + setBits)
            probeReadOff    := loadProbePort.payload.vaddr(offBits - 1 downto 0)
            probeReadSize   := loadProbePort.payload.size
            probeReadUsable := (if (earlyViptEnabled) {
              loadProbePort.payload.resolved &&
                !loadProbePort.payload.needsLine &&
                (loadProbePort.payload.cacheMode =/= CacheMode.INHIBITED)
            } else False)
            probeReadNeedsLine := loadProbePort.payload.needsLine
          }
        }

        // A command which owns a pre-existing probe is old work, so maintenance's
        // WAIT phase must let it drain. New commands remain blocked for the entire
        // maintenance interval. `maintWalking` is false only during that quiesce wait.
        val resolveOldProbeDuringMaint = earlyProbeOwnsCmd && !maintWalking
        // `!earlyProbeConflict`: task pea-early-probe-absorbing-2026-08-24 (P4). Hold
        // this exact command for the one cycle its owned, ready probe hit collides
        // with an in-flight ordinary S1 resolution (see `earlyProbeConflict`'s own
        // comment above `useEarlyProbe`) instead of admitting it down the
        // ordinary-read arm, which used to re-arm `ldS1Valid` and perpetuate the
        // conflict for every following command in a saturated stream.
        loadCmdPort.ready := !resetSweepBusy && !loadShadowValid && !pendingStoreMiss &&
                             (!maintBusyReg || resolveOldProbeDuringMaint) &&
                             (!earlyProbeTokenPresent || earlyProbeOwnsCmd) &&
                             (!(storeReadOwed && stS1Valid) || useEarlyProbe) &&
                             !earlyProbeConflict

        when(loadCmdPort.fire && earlyProbeOwnsCmd) {
          // Full-queue consume-and-replace reuses this physical entry for the new
          // probe on the same edge; keep the launch block's replacement valid.
          when(!(loadProbePort.fire && earlyProbeReusesConsume &&
                 (earlyProbeAllocIdx === earlyProbeMatchIdx))) {
            earlyProbeValids(earlyProbeMatchIdx)  := False
            earlyProbeReadies(earlyProbeMatchIdx) := False
          }
        }

        // A shadow accepted behind an earlier miss has priority over a new external
        // command once the refill returns. `maintWalking` (not maintBusyReg) is the
        // gate: maintenance WAIT deliberately leaves walking low while pre-existing
        // work drains, otherwise loadShadowValid would make dcIdleForMaint false and
        // the two sides would deadlock waiting on each other.
        when(loadShadowValid && !pendingStoreMiss && !maintWalking &&
             !(ldS1Valid && !ldS1Hit)) {
          rdSet        := loadShadowCmd.vaddr(offBits + setBits - 1 downto offBits)
          rdEn         := True
          loadUsesPort := True
          ldS1Valid    := True
          ldS1Set      := loadShadowCmd.vaddr(offBits + setBits - 1 downto offBits)
          ldS1Tag      := loadShadowCmd.paddr(31 downto offBits + setBits)
          ldS1Off      := loadShadowCmd.vaddr(offBits - 1 downto 0)
          ldS1Size     := loadShadowCmd.size
          ldS1Cmode    := loadShadowCmd.cacheMode
          ldS1Paddr    := loadShadowCmd.paddr
          loadShadowValid := False
        } elsewhen(loadCmdPort.fire && ldS1Valid && !ldS1Hit) {
          // The older S1 request misses this cycle. The command still FIRES — that
          // is what permits II=1 hits — but its RAM read is deferred so no untagged
          // younger response can pass the older refill. Replay it from the internal
          // slot on the first safe IDLE cycle after the refill.
          loadShadowCmd   := loadCmdPort.payload
          loadShadowValid := True
        } elsewhen(loadCmdPort.fire && useEarlyProbe) {
          // The queued VIPT result already contains the physical-tag-qualified,
          // size-extracted hit data. Bypass the redundant normal S1 read and land in
          // the existing registered S2 response stage.
          ldS2Valid      := True
          ldS2Hit        := True
          ldS2Direct     := True
          ldS2DirectData := earlyProbeHitData
          ldS2Line       := B(0, 128 bits)
          ldS2Off        := cmdOff
          ldS2Size       := loadCmdPort.payload.size
        } elsewhen(loadCmdPort.fire) {
          // Launch the BRAM tag+data read for this set; resolve hit/miss in S1.
          rdSet        := cmdSet
          rdEn         := True
          loadUsesPort := True
          freshLoadUsesPort := True
          ldS1Valid    := True
          ldS1Set      := cmdSet
          ldS1Tag      := cmdTag
          ldS1Off      := cmdOff
          ldS1Size     := loadCmdPort.payload.size
          ldS1Cmode    := loadCmdPort.payload.cacheMode
          // Translation faults are consumed before a command is emitted. The only
          // fault generated by the cache itself is a physical AXI refill error,
          // delivered through busFaultResp below.
          ldS1Paddr    := cmdPaddr
        }
        // S1 resolution of a launched load read. A HIT drives the response
        // one cycle later out of S2. A MISS starts the refill.
        when(loadMissDiscovered) {
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
          // D25's clamp, applied where the range ENTERS the sequencer rather than where it
          // is consumed: `end = min(off + n, 16)`. The incoming range is NOT already
          // line-contained -- LsEuPlugin.scala:759 sends both split slots at the full
          // original size -- so trusting it would emit a transaction one line past the
          // access, which for a cross-page access is a page the core never translated.
          //
          // NOTE ON SITE CHOICE: `grep -n 'missCmode :='` finds TWO sites, not one -- this
          // load-miss site (`ldS1Cmode`) and the store-drain-miss site below
          // (`missCmode := CacheMode.COPYBACK`, a hardcoded literal). Only THIS site can
          // ever latch `missCmode === INHIBITED` -- a COPYBACK drain-miss never does -- so
          // `subP`/`subEnd` only need a real value here; REFILL's `inhib` gate (derived
          // from `missCmode`) is False for the whole store-drain excursion regardless of
          // what these two registers hold left over from a prior load.
          // Task #236 fix: compute the new subP/subEnd into locals ONCE, use them both for
          // the register writes below AND to register subLog2Reg from the same new values
          // (never from the stale current subP/subEnd, which reading `subP`/`subEnd`
          // directly here would do -- SpinalHDL register reads see the pre-edge value even
          // inside the same `when` block as their own `:=`).
          val newSubP   = ldS1Paddr(offBits - 1 downto 0)
          val newSubEnd = m68k040.socket.MmioCover.clampedEnd(
                      ldS1Paddr(offBits - 1 downto 0),
                      m68k040.socket.MmioCover.sizeBytes(ldS1Size))
          subP       := newSubP
          subEnd     := newSubEnd
          subLog2Reg := m68k040.socket.MmioCover.stepLog2(newSubP, newSubEnd)
          // `missFault` must be cleared here: REFILL's INHIBITED arm now ACCUMULATES
          // (`missFault || respErr`) across a multi-sub-transaction sequence rather than
          // assigning once, so a stale True left over from a PRIOR access would otherwise
          // leak into this one's first sub-transaction. The store-drain site does not
          // need this: REFILL's non-INHIBITED arm still assigns `missFault := respErr`
          // (a plain overwrite, not an OR) on every entry, so no stale value can survive
          // there regardless of what it held on entry.
          missFault := False
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
          val victimFromS3 = stS3ArrayWrite && (stS3Set === ldS1Set) &&
            (stS3Way === vw)
          val victimFromS3D1 = stS3WriteD1 && (stS3WriteSetD1 === ldS1Set) &&
            (stS3WriteWayD1 === vw)
          loadVictimFromS3Dbg := victimFromS3 || victimFromS3D1
          val victimDirtyNow = rdDirty(vw) ||
            (victimFromS3 && stS3Copyback) ||
            (victimFromS3D1 && stS3WriteCopybackD1)
          val evictThis = victimDirtyNow && (ldS1Cmode =/= CacheMode.INHIBITED)
          victimEvictTag  := Mux(victimFromS3, stS3Tag,
            Mux(victimFromS3D1, stS3WriteTagD1, rdTag(vw)))
          victimEvictLine := Mux(victimFromS3, stS3MergedLine,
            Mux(victimFromS3D1, stS3WriteLineD1, rdData(vw)))
          loadMissStoreBarrier := True
          GenerationFlags.simulation {
            assert(!stS2Valid,
              "DcachePlugin: load and store S2 simultaneously claimed the one synchronous read result",
              FAILURE)
          }
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
          axi.aw.payload.id    := U(AxiIds.D_PUSH, AxiIds.ID_W bits)
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
          when(axi.b.valid && axi.b.payload.id === U(AxiIds.D_PUSH, AxiIds.ID_W bits)) {
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

        // ── D4/D24/D30: INHIBITED accesses get a REAL AxSIZE and a byte-granular address
        // The cacheable path below is bit-identical to before: one len=0/size=4 beat at the
        // line base. This arm is reachable only when `missCmode === INHIBITED`, and an
        // INHIBITED load ALWAYS reaches REFILL because `ldS1Cacheable` (:353) forces every
        // hit bit low by construction.
        //
        // WHY THE FIX IS HERE AND NOT IN LsEuPlugin's split predicate: `s1CrossLine` /
        // `s1CrossPage` are computed at S1 from `s1Va` (:439-444), BEFORE translation
        // resolves, so `cacheMode` is not known there. Making the predicate
        // cacheMode-independent would put every misaligned CACHEABLE access on the rare
        // two-pass replay FSM and cost real IPC on the hot path.
        val inhib   = missCmode === CacheMode.INHIBITED
        // Task #236 fix: was `m68k040.socket.MmioCover.stepLog2(subP, subEnd)`, recomputed
        // combinationally every cycle. Now a register read -- see `subLog2Reg`'s own
        // declaration comment for why this is safe (registered at both sites that ever
        // change subP/subEnd, from the same new values, never stale).
        val subLog2 = subLog2Reg
        val subBytes = (U(1, 4 bits) |<< subLog2).resize(4 bits)     // 1, 2 or 4
        val subAddr = (missPaddr(31 downto offBits) ## subP).asUInt
        val subLast = (subP +^ subBytes) === subEnd

        when(!arSent) {
          axi.ar.valid         := True
          axi.ar.payload.addr  := Mux(inhib, subAddr, lineBase)
          axi.ar.payload.id    := U(AxiIds.dRefill(0), AxiIds.ID_W bits)
          axi.ar.payload.len   := U(0, 8 bits)
          axi.ar.payload.size  := Mux(inhib, subLog2.resize(3 bits), U(4, 3 bits))
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
        // Once the refill beat is waiting, stop accepting further store drains.
        // The finite resident store pipe continues to drain where its ordering
        // barriers permit, so the collision hold clears in bounded time even
        // under an infinite producer.
        refillNeedsStoreDrain := axi.r.valid && refillWriteHold
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
              validsWrEn(w)   := True
              validsWrSet(w)  := missSet
              validsWrData(w) := True
              dirtysWrEn(w)   := True
              dirtysWrSet(w)  := missSet
              dirtysWrData(w) := False   // a fresh allocate is always clean until
                                             // the write-allocate merge below (or a
                                             // later hit) dirties it
              validsVoteW1(w) := True    // Task #255 exclusivity tripwire (W1/D1)
              dirtysVoteD1(w) := True
            }
            victim(missSet) := victim(missSet) + 1
            missArrayWrite  := True
          }
          when(inhib) {
            // Merge THIS sub-transaction's bytes into `missLine` at their own line offsets.
            // A narrow AXI read returns its bytes in the lanes matching its own address, so
            // the returned byte at line offset i is at data[8i +: 8] -- the same index it
            // occupies in `missLine`. No shifting, and the existing extraction at :499-502
            // (`DcacheByteLane.extract(missLine, missOff, missSize)`) stays correct
            // unchanged, whether missLine was filled by one 16-byte beat or by three narrow
            // ones.
            val rBytes   = axi.r.payload.data.subdivideIn(8 bits)
            val curBytes = missLine.subdivideIn(8 bits)
            val newBytes = Vec(Bits(8 bits), 1 << offBits)
            for (i <- 0 until (1 << offBits)) {
              val inSub = (U(i, offBits + 1 bits) >= subP.resize(offBits + 1 bits)) &&
                          (U(i, offBits + 1 bits) < (subP +^ subBytes))
              newBytes(i) := Mux(inSub, rBytes(i), curBytes(i))
            }
            missLine  := newBytes.asBits
            // Sticky across the sequence: a non-OKAY response on ANY sub-transaction raises
            // the existing busFaultResp, exactly as a single-beat refill error does today.
            missFault := missFault || respErr
            when(subLast || respErr) {
              goto(REPLAY)
            } otherwise {
              // Task #236 fix: register the NEXT sub-transaction's subLog2 here too, from
              // the same newSubP this cycle computes for subP itself -- `subEnd` doesn't
              // change on an advance, only subP does. `subBytes` used below is still this
              // cycle's (current, not yet advanced) value, correctly read from `subLog2Reg`
              // before it gets overwritten -- SpinalHDL register reads see the pre-edge
              // value, so `subBytes`'s own combinational derivation earlier in this block
              // is unaffected by this same-cycle `subLog2Reg :=`.
              val newSubP = (subP +^ subBytes).resize(offBits bits)
              subP       := newSubP
              subLog2Reg := m68k040.socket.MmioCover.stepLog2(newSubP, subEnd)
              arSent     := False                      // arm the next sub-transaction
            }
          } otherwise {
            missLine  := axi.r.payload.data
            missFault := respErr
            goto(REPLAY)
          }
        }

        // Spec section 13 turns the D30 cases into a HARD check: an INHIBITED
        // sub-transaction whose byte extent leaves the architectural access, or whose
        // AxADDR is not naturally aligned for its own AxSIZE, is a BUG, not a case to
        // absorb.
        GenerationFlags.simulation {
          when(inhib && axi.ar.valid) {
            assert((subP +^ subBytes) <= subEnd,
              "DcachePlugin: an INHIBITED sub-transaction runs past the access", FAILURE)
            assert(subP >= missPaddr(offBits - 1 downto 0),
              "DcachePlugin: an INHIBITED sub-transaction starts before the access", FAILURE)
            assert((subP.asBits & ((subBytes - 1).asBits.resize(offBits))) === B(0, offBits bits),
              "DcachePlugin: an INHIBITED AxADDR is not naturally aligned for its AxSIZE",
              FAILURE)
          }
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
            loadMissStoreBarrier := False
          }
          goto(IDLE)
        } elsewhen(missCmode === CacheMode.INHIBITED) {
          // Task P1.4: no line was allocated (doAllocate was False above) — there
          // is nothing to "replay" as a hit. Deliver the just-fetched beat
          // directly, once, exactly like busFaultResp's one-pulse shape.
          inhibitedResp := True
          loadMissStoreBarrier := False
          goto(IDLE)
        } elsewhen(refillReqIsStore) {
          // Task P4.4: this is an array-write site (write-allocate merge) — hold
          // here (do NOT goto(IDLE), retry the SAME cycle's work next cycle)
          // while the array-write interlock is asserted, exactly like REFILL's own
          // allocate write above.
          //
          // FMax Lever F: this site uses `storeDrainRefillHold` (register-only,
          // strictly stronger) rather than `refillWriteHold` (which depends on the
          // tag-BRAM read + the 21-bit store-S2 tag compare). See that signal's
          // declaration for the full argument; REFILL's `axi.r.ready` above is
          // deliberately left on the precise `refillWriteHold`.
          when(!storeDrainRefillHold) {
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
              dirtysWrEn(w)   := True
              dirtysWrSet(w)  := missSet
              dirtysWrData(w) := True
              dirtysVoteD2(w) := True    // Task #255 exclusivity tripwire (D2)
            }
            storeAllocAckReg := True
            missArrayWrite   := True
            goto(IDLE)
          } otherwise {
            // Held — stay in REPLAY, retry next cycle (retry-don't-drop). Per the
            // producer-contract argument at `storeDrainRefillHold`'s declaration
            // this arm is UNREACHABLE in the real core; the detector below is what
            // makes that claim falsifiable rather than merely asserted.
            storeDrainHoldFired := True
          }
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
          ldS1Paddr    := missPaddr
          loadMissStoreBarrier := False
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
    //   - "no store can be in S1": nothing structurally prevented it. The
    //     historical unbackpressured `storePort` Flow could enter on the WAIT→READ edge.
    //     The current Stream instead lowers ready once maintenance is latched and
    //     keeps held valid outside the quiescence predicate.
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
    // for it: the sim asserts below still hold that no store is in S0..S3 and the load
    // FSM is in IDLE for the whole walk. They prove the invariant rather than assume
    // it; and for the SHARED ARRAY READ PORT specifically, layer (2) makes a violation
    // a bounded stall rather than silent corruption.
    //
    // SCOPE CORRECTION (Task P5.5, folding in the P5.4 critical-fix review's finding
    // I-1): the sentence above used to make that "bounded stall, not silent
    // corruption" claim GENERALLY. It is only true of the read port. The walk's array
    // WRITES -- the `valids`/`dirtys` clears in `CHECK` and `WRB` -- are NOT arbitrated
    // against store-S3's own write to those same arrays, which elaborates FIRST, so a
    // genuine same-cycle collision there would be last-assignment-wins (the walk's
    // clear winning over the store's set), i.e. silent, not a stall. That ordering gap
    // is UNREACHABLE today and stays documented rather than fixed (Task #240: this
    // claim is UNCHANGED by the valids/dirtys register-array -> per-way Mem fold --
    // `validsWrEn/validsWrSet/validsWrData` and `dirtysWrEn/dirtysWrSet/dirtysWrData`
    // are plain combinational Vecs with the exact same last-assignment-wins ordering
    // the raw register-array writes had, feeding a single write() call per way; the
    // exclusivity proof this task added covers exactly this reachability question --
    // see the writer-site declaration comment above `validsWrEn`): `dcIdleForMaint`
    // (registered-stage and accepted-count terms) plus `WAIT` mean no store is
    // anywhere in S0..S3 when the walk starts, the FAILURE-severity sim assert below
    // catches it if one ever is, and the caller (ExceptionUnit's `S_DRAIN` ->
    // `S_APPLY` -> `S_MAINTWAIT` serialization, Task P5.5) additionally waits on
    // `sqDrained && maintQuiesced` before requesting the walk and holds the whole
    // sequencer until it completes.

    /** Every D-cache datapath resource the walk needs, genuinely idle THIS cycle.
      *
      * The live Stream valid is deliberately NOT part of quiescence.  Once a
      * maintenance command is latched, `maintBusyReg` lowers store ready, so a
      * producer may legally hold valid while the resident store pipeline drains.
      * Waiting for that valid to fall would deadlock a well-formed Stream.  The
      * registered stages, accepted-count, miss barrier, and physical AXI pair
      * flags below are the actual resource-occupancy proof.
      *
      * FMax closure Slice 2: `!ldS2Valid` is REQUIRED, but for a DIFFERENT reason than
      * `!ldS1Valid` -- `ldS2*`'s payload is a frozen register snapshot (no live shared-
      * resource read behind it, unlike S1), so there is no corruption mechanism this
      * term is closing. It exists to preserve this signal's documented intent --
      * "every load/store pipeline stage fully drained" -- now that the load pipe has a
      * third stage: a maintenance walk's tag/valid/dirty invalidation must not start
      * while a load response is still resolving, even though that response's DATA
      * cannot itself be corrupted by the walk. Costs at most one extra cycle of
      * walk-start delay on an already-rare, ROB-serialized event. */
    val dcIdleForMaint = !resetSweepBusy && !busy && !ldS1Valid && !ldS2Valid && !loadShadowValid &&
                         !earlyProbeValid &&
                         !pendingStoreMiss && !pendingWtKickoff &&
                         !s0Valid && !stS1Valid && !stS2Valid && !stS3Valid &&
                         (storeOutstanding === 0) && !serialStoreInFlight &&
                         !storeMissBarrier &&
                         stAwDone && stWDone && evictAwDone && evictWDone
    dcIdleForMaint.simPublic()

    /** 2026-09-05 walker-stall observability (p141), read live over jtag_axi at
      * `DebugRegMap.OFF_STALL_DC`. Pure observation: no new state, no consumer
      * inside this plugin, nothing feeds back into the datapath.
      *
      * WHY EVERY TERM SEPARATELY. `dcIdleForMaint` above is a 17-way conjunction,
      * and the whole question this capture exists to answer is *which* term never
      * clears when the `0x40806b68` A-line exception entry wedges. Probing the
      * AND (or `maintQuiesced`, which is that AND plus `!maintBusyReg`) would
      * report only "not idle" -- the fact already known from the frozen retire
      * count -- and would not discriminate between a stuck load stage, a stuck
      * store stage, an undrained AXI write channel, and a leaked
      * `storeOutstanding` credit. Those four point at different RTL.
      *
      * Bit layout MUST stay in sync with the `dcIdleForMaint` expression above;
      * the order here is deliberately the same, left to right. */
    val dbgStallDcPack = Bits(32 bits)
    dbgStallDcPack := B(0, 32 bits)
    dbgStallDcPack(0)  := resetSweepBusy
    dbgStallDcPack(1)  := busy
    dbgStallDcPack(2)  := ldS1Valid
    dbgStallDcPack(3)  := ldS2Valid
    dbgStallDcPack(4)  := loadShadowValid
    dbgStallDcPack(5)  := earlyProbeValid
    dbgStallDcPack(6)  := pendingStoreMiss
    dbgStallDcPack(7)  := pendingWtKickoff
    dbgStallDcPack(8)  := s0Valid
    dbgStallDcPack(9)  := stS1Valid
    dbgStallDcPack(10) := stS2Valid
    dbgStallDcPack(11) := stS3Valid
    dbgStallDcPack(12) := serialStoreInFlight
    dbgStallDcPack(13) := storeMissBarrier
    // The four AXI write-channel completion flags are ACTIVE-HIGH-DONE in the
    // conjunction (`&& stAwDone && ...`), so a ZERO here is a term that is
    // holding `dcIdleForMaint` low -- the opposite polarity to the bits above.
    dbgStallDcPack(14) := stAwDone
    dbgStallDcPack(15) := stWDone
    dbgStallDcPack(16) := evictAwDone
    dbgStallDcPack(17) := evictWDone
    dbgStallDcPack(21 downto 18) := storeOutstanding.asBits.resize(4 bits)
    dbgStallDcPack(22) := dcIdleForMaint
    dbgStallDcPack(23) := maintBusyReg
    // [24] is the composite the ExceptionUnit actually waits on, so a capture can
    // confirm the CSR view agrees with the consumer rather than assuming it.
    dbgStallDcPack(24) := dcIdleForMaint && !maintBusyReg

    dbgStallDcPack.simPublic()

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
    val maintErrorReg = RegInit(False)
    maintErrorReg := False
    maintErrorReg.simPublic()

    // ── TCR.P (8 KB pages), task #195 follow-up / Part 131 ─────────────────────
    // PAGE-scope CPUSHP/CINVP must cover the page as sized by TCR.P, not a fixed
    // 4 KB block (MC68040UM §4: the page-scope operand is "the page containing the
    // address", and the page size is a TC property). Task #195 threaded TCR.P
    // through the table walker, both TLBs, LSU physical-address formation and
    // PTEST, but DcachePlugin -- which had no MMU handle at all -- was missed, so
    // on an 8 KB-page machine a CPUSHP covered only the 4 KB half containing the
    // operand address. Measured live on the boot path (docs spec 2026-09-04
    // "covering flush and page cache mode", §5.1): 3 of 20 consecutive ROM
    // `cpushp bc,(a1)` invocations asked for a range that straddles the 4 KB
    // boundary we stopped at, all in low RAM on CM=01 copyback pages.
    //
    // `host.get` (optional) mirrors IcachePlugin.scala:144-145 exactly: a standalone
    // D-cache DUT with no MmuControlPlugin wired resolves to None -> `False` = 4 KB
    // pages, i.e. bit-for-bit the pre-fix behaviour for every existing test. Reading
    // the service through the `logic` Handle is what makes this safe against the
    // cross-Fiber-task ordering race documented at MmuControl.scala:55-88 -- the
    // getter BLOCKS this build body until MmuControlPlugin's own body has run, so
    // `is8K` is never a Scala-null here. MmuControlPlugin's build body reads nothing
    // from `host`, so this cannot deadlock.
    val mmuCtrl = host.get[m68k040.services.MmuControlService]
    val is8K    = mmuCtrl.map(_.pageSize8K).getOrElse(False)

    val maint = new Area {
      val cmd     = Reg(CacheMaintCmd())
      // TCR.P sampled at command accept, so a walk that spans hundreds of cycles
      // uses ONE consistent granule end-to-end (a MOVEC to TC cannot retire while
      // the maintenance sysOp is in flight, but pinning it makes that independent of
      // that argument) and so the CHECK-stage comparator sees a LOCAL register
      // rather than a long cross-plugin route into its critical path.
      val cmdIs8K = Reg(Bool()) init False; cmdIs8K.simPublic()
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
            cmdIs8K := is8K
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
          // compare it anyway so the predicate reads standalone).
          //
          // Page scope: the page number, sized by TCR.P (`cmdIs8K`, latched at accept).
          // With lineBytes=16 and sets=128 the tag is paddr[31:11], so
          //   4 KB page number == paddr[31:12] == tag[tagBits-1:1]
          //   8 KB page number == paddr[31:13] == tag[tagBits-1:2]
          // Expressed as one wide compare plus a conditional extra bit rather than a
          // Mux of two comparators: `pageHiMatch` is the 8 KB predicate, and in 4 KB
          // mode it is additionally qualified by paddr[12] (== tag[1]). A page of
          // EITHER size spans every set (256 or 512 lines over 128 sets), so Page and
          // All both walk the whole array; only the tag-prefix predicate differs.
          // All scope: everything.
          val lineMatch = (walkSet === target(offBits + setBits - 1 downto offBits)) &&
                          (rdTag(curWay) === target(31 downto offBits + setBits))
          val pageHiMatch = rdTag(curWay)(tagBits - 1 downto 2) === target(31 downto 13)
          val pageLoMatch = rdTag(curWay)(1) === target(12)
          val pageMatch   = pageHiMatch && (cmdIs8K || pageLoMatch)
          val scopeHit  = Mux(cmd.scope === U(1, 2 bits), lineMatch,
                          Mux(cmd.scope === U(2, 2 bits), pageMatch, True))
          val resident  = rdValid(curWay)
          val matches   = resident && scopeHit
          val isDirty   = rdDirty(curWay)
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
                validsWrEn(w)   := True
                validsWrSet(w)  := walkSet
                validsWrData(w) := False
                dirtysWrEn(w)   := True
                dirtysWrSet(w)  := walkSet
                dirtysWrData(w) := False
                validsVoteW2(w) := True   // Task #255 exclusivity tripwire (W2/D3)
                dirtysVoteD3(w) := True
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
            axi.aw.payload.id    := U(AxiIds.D_EVICT, AxiIds.ID_W bits)
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
            when(axi.b.valid && axi.b.payload.id === U(AxiIds.D_EVICT, AxiIds.ID_W bits)) {
              val writebackOk = axi.b.payload.resp === Axi4.resp.OKAY
              when(!writebackOk) {
                maintErrorReg       := True
                // Imprecise DIAGNOSTIC only, per the design's locked decision that a
                // writeback error is a diagnostic crash and not an architectural trap.
                diagFaultPulse     := True
                diagFaultPulseAddr := wbAddrReg
                diagFaultPulseResp := axi.b.payload.resp.asUInt.resize(2)
                diagFaultPulseKind := U(3, 3 bits)   // kind=3: CPUSH maintenance writeback
              }
              // Only a successful response makes memory authoritative. On an error,
              // preserve the valid+dirty line so a debugger can report failure and
              // retry without having silently discarded the only current copy.
              when(writebackOk) {
                for (w <- 0 until ways) when(curWay === U(w, wayBits bits)) {
                  dirtysWrEn(w)   := True
                  dirtysWrSet(w)  := walkSet
                  dirtysWrData(w) := False
                  dirtysVoteD4(w) := True   // Task #255 exclusivity tripwire (D4)
                  when(cmd.invalidate) {
                    validsWrEn(w)   := True
                    validsWrSet(w)  := walkSet
                    validsWrData(w) := False
                    validsVoteW3(w) := True   // Task #255 exclusivity tripwire (W3)
                  }
                }
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
      assert(!(maint.walking && (s0Valid || stS1Valid || stS2Valid || stS3Valid)),
        "DcachePlugin: a STORE was in the S0..S3 pipeline while a cache-maintenance walk was actively running -- the walk's caller pulsed maintCmd without waiting for the StoreQueue to drain (ExceptionUnit S_DRAIN / DcacheService.maintQuiesced), AND the walk's own WAIT self-check was bypassed",
        FAILURE)
      assert(!(maint.walking && !fsm.isActive(fsm.IDLE)),
        "DcachePlugin: the load/refill FSM left IDLE during a cache-maintenance walk -- it can drive the shared rdSet/rdEn port and open an eviction AXI pair, both of which the walk assumes are exclusively its own",
        FAILURE)
      assert(!(maintAxiPairOpen && (storeWantsAxi || evictAxiPairOpen)),
        "DcachePlugin: the cache-maintenance writeback's AXI aw/w pair was open at the same time as the store's or the eviction's -- AXI4 AW/W FIFO ordering cross-attributes overlapping pairs regardless of id (the proven-unsafe P4.3 revision-1/2 failure mode)",
        FAILURE)
    }

    // Task #255: turn the task-#240 hand-proven `valids`/`dirtys` write-mux
    // exclusivity into a runtime-checked invariant, same `CountOne(...) <= U(1)`
    // idiom as `stS2HitVec`/`ackSources` below. `validsWrEn(w)`/`dirtysWrEn(w)`
    // are plain last-assignment-wins muxes: reading them alone cannot tell "one
    // writer fired" apart from "two+ writers raced this way and the last one in
    // source order silently won (wrong WrSet/WrData, not just a wrong enable)".
    // The per-writer vote vectors declared next to `validsWrEn`/`dirtysWrEn`
    // above exist exactly to make that distinction, so this checks each way,
    // each cycle, that AT MOST ONE of the enumerated writer sites voted --
    // matching, not weakening or strengthening, the task-#240 report's argued
    // invariant. Sim-only; zero synthesis cost (see the vote-vector declaration
    // comment for why).
    GenerationFlags.simulation {
      for (w <- 0 until ways) {
        assert(CountOne(Seq(validsVoteW0(w), validsVoteW1(w), validsVoteW2(w), validsVoteW3(w))) <= U(1),
          "DcachePlugin: multiple writers targeted validsMem(w) the same cycle -- the task-#240 write-mux exclusivity proof was violated",
          FAILURE)
        assert(CountOne(Seq(dirtysVoteD0(w), dirtysVoteD1(w), dirtysVoteD2(w), dirtysVoteD3(w), dirtysVoteD4(w), dirtysVoteD5(w))) <= U(1),
          "DcachePlugin: multiple writers targeted dirtysMem(w) the same cycle -- the task-#240 write-mux exclusivity proof was violated",
          FAILURE)
      }
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
    // WT-pipelining (this task): `stAddrReg`/`stMergeReg`/`stStrbReg`/`stPreciseReg`
    // are SINGLE registers -- the one physical "kickoff in flight" snapshot the AXI
    // aw/w drive reads from (below). Before this task exactly one WRITETHROUGH/
    // INHIBITED descriptor could ever be admitted at a time (the old
    // `storeOutstanding === 0` gate), so a second descriptor reaching S3 and
    // overwriting these registers WHILE the first's aw/w handshake was still
    // outstanding was unreachable. Now that several non-precise WRITETHROUGH
    // descriptors can be concurrently admitted (see `wtOutstanding`), that window
    // is live: hold S0->S1->S2 promotion (this term) while an OLDER kickoff still
    // owns (or is about to own) the registers.
    //
    // REVIEW-CAUGHT BUG (directed-test-caught, `DcacheSpec` "a second non-precise
    // WRITETHROUGH store's AW fires before the first store's B arrives"): the
    // FIRST version of this gate was just `storeWantsAxi || pendingWtKickoff`.
    // That is NOT enough, because `storeWantsAxi` only reads True the cycle AFTER
    // an older entry's OWN S3 resolution (`stAwDone := False` is a registered
    // write, visible starting the next cycle) -- but S2->S3 promotion
    // (`when(stS2Valid) {...}`) is completely UNGATED (by design, a 1-cycle
    // pass-through stage) and a TRAILING entry's S1->S2 promotion decision is made
    // ONE CYCLE BEFORE that, off the CURRENT (still-stale, pre-edge) value of
    // `storeWantsAxi`. Concretely, with two WT entries advancing in lockstep one
    // stage apart: while the leader occupies S2 (about to resolve into S3 next
    // cycle), `storeWantsAxi` is STILL False (the leader hasn't reached S3 yet) --
    // so the trailing entry's S1->S2 promotion was NOT held, and it lands in S2
    // exactly when the leader lands in S3, then unconditionally promotes into S3
    // itself the very next cycle -- exactly the cycle after the leader's kickoff
    // registers first became live, silently OVERWRITING `stAddrReg`/`stMergeReg`/
    // `stAwDone`/`stWDone` with the trailing entry's own payload before the
    // leader's aw/w had any guaranteed chance to be accepted. The fix closes the
    // gap by ALSO holding while an older non-COPYBACK entry is CURRENTLY resolving
    // S2 or S3 (`stS2Copyback`/`stS3Copyback` already exist for exactly this
    // classification) -- i.e. the hold now covers the older entry's ENTIRE
    // S2-through-accepted-kickoff window, with no one-cycle seam.
    val wtKickoffBusy = storeWantsAxi || pendingWtKickoff ||
      (stS2Valid && !stS2Copyback) || (stS3Valid && !stS3Copyback)

    val storePipeHeld = storeMissBarrier || storeMissDiscovered ||
      loadMissStoreBarrier || loadMissDiscovered || wtKickoffBusy

    // Task pea-cache-evict-2026-08-19 fix: a store parked in S1 whose read is about
    // to launch THIS cycle, targeting the SAME line (set+tag) an OLDER store is
    // writing back THIS SAME cycle (stS3ArrayWrite), must not launch that read yet.
    // The shared dataMem/tagMem read port has no read-during-write forwarding (see
    // `stS2UsesS3Line`'s own comment two screens up, which patches the mirror-image
    // hazard: an older store's S3 write landing on the SAME cycle a younger store's
    // S2 already resolved, i.e. the read was launched ONE cycle before the write).
    // That existing forward only covers stores pipelined with a ONE-cycle S2-to-S2
    // gap (back-to-back, zero slack). It does NOT cover a store whose S1
    // read-launch cycle lands exactly on an older same-line store's S3 write cycle
    // -- which happens for a TWO-cycle S2-to-S2 gap, the pattern this project's own
    // ordered-drain admission logic actually produces once a barrier releases a
    // backlog of same-line stores (confirmed via a cycle-exact whitebox trace of
    // pea_4x_cache_evict_once.s: four back-to-back COPYBACK-hit PEA stores to one
    // line, each pair exactly two cycles apart at S2, silently reverted the
    // immediately-older sibling's byte on EVERY pair -- store-to-load forwarding in
    // the LSU happened to mask it for the two youngest stores in that specific
    // repro, but the cache ARRAY was genuinely corrupted underneath; a later
    // same-line load or eviction observes it too, as the ported test's failing
    // check(s) do).
    //
    // Fix: HOLD (delay, never drop) this store's S1->S2 promotion for exactly one
    // extra cycle instead of trying to forward a second, older snapshot into S2 --
    // simpler and provably correct, matching this file's established
    // hold-and-retry philosophy elsewhere (`refillWriteHold`, `storeMissBarrier`,
    // `loadMissStoreBarrier`). By the very next cycle the racing S3 write has
    // landed (S3 is exactly a one-cycle pulse per store), so the re-launched read
    // is genuinely fresh. The raw read-launch itself (the `rdSet`/`rdEn` drive
    // above, on the shared high-fanout BRAM read-address net) is deliberately left
    // UNGATED here -- a held store simply gets a wasted, uncaptured read this
    // cycle (the same benign pattern already exercised whenever `fsm.loadUsesPort`/
    // `maintUsesPort`/`storePipeHeld` block promotion today) and retries next
    // cycle; this keeps the fix off that FMax-sensitive net entirely.
    val stS1SameLineAsS3 = stS1Valid && stS3ArrayWrite &&
      (stS1Set === stS3Set) && (stS1Tag === stS3Tag)
    stS1SameLineAsS3.simPublic()   // test-visibility only (pea-cache-evict-2026-08-19
                                    // regression); no-op for synthesis

    val stS1Advance = stS1Valid && !fsm.loadUsesPort && !maintUsesPort && !storePipeHeld &&
      !stS1SameLineAsS3
    val stS1Ready   = !stS1Valid || stS1Advance
    val s0Advance   = s0Valid && stS1Ready && !storePipeHeld
    val s0Ready     = !s0Valid || s0Advance

    when(stS1Advance) { storeReadOwed := False }
    when(freshLoadUsesPort && stS1Valid) { storeReadOwed := True }

    // Admission, per class (WT-pipelining task):
    //   - precise (fully serial): needs a totally empty pipe -- unchanged,
    //     `storeOutstanding === 0`. `storeOutstanding` counts EVERY admitted-
    //     unacked descriptor of every class (see its own decl comment), so this
    //     transitively also requires `wtOutstanding === 0` -- a precise store
    //     cannot be admitted while a pipelined WT write is still AXI-pending.
    //   - COPYBACK: stage-credit based as before (no `storeOutstanding` gate at
    //     all), PLUS a new `wtOutstanding === 0` term. This is the one new
    //     restriction COPYBACK picks up from this task: it must not race ahead of
    //     an OLDER, still AXI-pending WT write. A WT write's completion
    //     (`storeAckReg`/the SQ's blind "ack pops the oldest accepted half"
    //     contract, StoreQueue.scala's `drainAckFire`) can lag admission by a full
    //     AXI round trip, while COPYBACK's own completion is near-immediate (S3,
    //     no AXI at all on a hit) -- letting a COPYBACK ack overtake an older,
    //     still-open WT beat would surface an OUT-OF-ORDER ack to the StoreQueue.
    //     The reverse direction (WT admitted behind an outstanding COPYBACK) needs
    //     no such gate: the single-lane S0-S3 pipe is strictly FIFO by
    //     construction (S2/S3 are one-descriptor-at-a-time stages, no overtaking),
    //     so an older COPYBACK always resolves (fast) before a younger WT even
    //     reaches S2, let alone kicks off its own AXI leg.
    //   - pipelined WT (non-precise WRITETHROUGH): stage-credit based too, capped
    //     at `MAX_WT_OUTSTANDING` (a real, chosen resource bound -- see
    //     `wtOutstanding`'s own decl comment) instead of the old "admit only when
    //     the pipe is totally empty" restriction.
    storePort.ready := !resetSweepBusy && s0Ready && !storePipeHeld && !serialStoreInFlight &&
      !maintBusyReg && !refillNeedsStoreDrain &&
      (!inputStoreSerial || (storeOutstanding === 0)) &&
      (!inputCopyback || (wtOutstanding === 0)) &&
      (!inputPipelinedWt || (wtOutstanding =/= U(MAX_WT_OUTSTANDING, wtOutstanding.getWidth bits)))

    when(stS1Advance) { stS1Valid := False }
    when(s0Advance) {
      stS1Valid   := True
      stS1Payload := s0Payload
    }
    when(s0Advance) { s0Valid := False }
    when(storePort.fire) {
      s0Valid   := True
      s0Payload := storePort.payload
    }
    when(stS1Advance) {
      stS2Valid   := True
      stS2Payload := stS1Payload
    }

    when(storeAckReg) { serialStoreInFlight := False }
    when(storePort.fire && inputStoreSerial) { serialStoreInFlight := True }
    when(storePort.fire && !storeAckReg) {
      storeOutstanding := storeOutstanding + 1
    } elsewhen(!storePort.fire && storeAckReg) {
      storeOutstanding := storeOutstanding - 1
    }

    // wtOutstanding: same net +1/-1/no-op-on-same-cycle-cancel shape as
    // `storeOutstanding` above, scoped to the pipelined-WT subset. `wtStoreAckReg`
    // is forward-declared (SpinalHDL wire, driven below at the same site as
    // `storeAckReg`'s own drive -- see that site's comment for why `!stPreciseReg`
    // at a `storeBAck` instant can ONLY ever mean "a non-precise WRITETHROUGH
    // store's beat": COPYBACK never reaches the AXI B path (hit resolves on-chip,
    // miss's write-allocate acks via `storeAllocAckReg`) and INHIBITED is always
    // `precise`, so this is an exact, not merely conservative, classifier).
    val wtStoreFire = storePort.fire && inputPipelinedWt
    when(wtStoreFire && !wtStoreAckReg) {
      wtOutstanding := wtOutstanding + 1
    } elsewhen(!wtStoreFire && wtStoreAckReg) {
      wtOutstanding := wtOutstanding - 1
    }

    // Registered latch of the drain's identity, needed a cycle later by the AXI
    // B-ack site (Task P4.5's diagnostic-channel gate) and by the WT/beat drive.
    val stPreciseReg = Reg(Bool())
    // WT-pipelining task: an EXPLICIT, self-contained classifier for "the AXI leg
    // this S3 kickoff is about to drive belongs to a pipelined (non-precise
    // WRITETHROUGH) store" -- captured the same way and at the same site as
    // `stPreciseReg`, consumed by `wtStoreAckReg` at the B-ack site so
    // `wtOutstanding`'s increment (admission, gated on `inputPipelinedWt`) and
    // decrement (ack) are driven by the EXACT SAME classification, rather than by
    // `!stPreciseReg` inferring it indirectly. `!stPreciseReg` alone would silently
    // assume the external invariant "INHIBITED implies precise"
    // (LsEuPlugin.scala's `fastStore` does enforce this for real CPU traffic, via
    // `cmode =/= INHIBITED`) -- correct for real traffic, but NOT something
    // DcachePlugin can verify about its own `storePort` input, and a directed
    // whitebox test (or a future bug upstream) presenting a non-precise INHIBITED
    // store would then decrement `wtOutstanding` on its ack despite never having
    // incremented it on admission (`inputPipelinedWt` requires `cacheMode ===
    // WRITETHROUGH`, INHIBITED never qualifies) -- an increment/decrement mismatch
    // that eventually underflows the counter. Caught exactly this way by
    // DcacheSpec's pre-existing "inhibited store skips the line write" test, which
    // pokes `precise=false` on an INHIBITED store directly (that combination is
    // architecturally unreachable from real LsEuPlugin traffic, but the counter
    // must not depend on that being true).
    val stIsPipelinedWtReg = Reg(Bool())

    // WT-pipelining task -- REVIEW-CAUGHT BUG #2 (directed-test-caught, DcacheSpec
    // "a pipelined WRITETHROUGH store's bus error stays diagnostic-only..."):
    // `diagFaultPulseAddr` (the async diagnostic-fault channel's kind=0 site,
    // below) used to read `stAddrReg` directly at the exact cycle `storeErrReg`
    // pulses (i.e. at B-arrival time). That is correct ONLY when at most one
    // WT/INHIBITED kickoff can ever be outstanding -- true before this task. Once
    // several pipelined WT kickoffs can be outstanding concurrently, `stAddrReg`
    // is reused by each LATER kickoff the moment the CURRENT one's aw/w are both
    // accepted (see `wtKickoffBusy`) -- which happens WELL BEFORE the earlier
    // kickoff's B (and therefore its possible fault) actually arrives, since aw/w
    // acceptance is fast and B is a full round trip later. By the time an OLDER
    // kickoff's B lands, `stAddrReg` may already hold a NEWER store's address,
    // silently misattributing the fault.
    //
    // Fix: a small FIFO of addresses, pushed in STRICT KICKOFF-CAPTURE order (the
    // same site `stAddrReg` itself is written, scoped to the non-COPYBACK branch
    // that actually goes on to produce an AXI B -- see the push site below) and
    // popped in STRICT B-ARRIVAL order (`storeBAck && stSubLast`, the same event
    // that terminates each descriptor). These two orders are PROVABLY identical:
    // kickoffs are issued strictly one-at-a-time (`wtKickoffBusy` gate) and AXI4
    // guarantees same-ID (`D_STORE` is a single constant id) B responses complete
    // in issue order -- so the Nth push always corresponds to the Nth pop,
    // regardless of how many are concurrently in flight. Depth ==
    // `MAX_WT_OUTSTANDING`: the proven bound on how many non-COPYBACK descriptors
    // can be simultaneously B-pending (a precise/INHIBITED descriptor can never
    // overlap with anything else, including itself, so it never pushes this
    // counter past that same bound).
    // Pointers are ONE BIT WIDER than `log2Up(depth)` (the classic ring-buffer
    // full/empty disambiguation): true occupancy can legitimately reach
    // `MAX_WT_OUTSTANDING` exactly (that is the whole point of the cap), and a
    // bare `log2Up(depth)`-bit pointer pair cannot distinguish that from empty
    // (push == pop, mod depth, in both cases). The extra bit makes `push - pop`
    // (unsigned wraparound) a genuine 0..depth occupancy count; only the low
    // `log2Up(depth)` bits are used to INDEX the storage array.
    val wtFaultFifoIdxW = log2Up(MAX_WT_OUTSTANDING)
    val wtFaultFifoAddr = Vec.fill(MAX_WT_OUTSTANDING)(Reg(UInt(32 bits)))
    val wtFaultFifoPush = RegInit(U(0, wtFaultFifoIdxW + 1 bits))
    val wtFaultFifoPop  = RegInit(U(0, wtFaultFifoIdxW + 1 bits))
    wtFaultFifoPush.simPublic(); wtFaultFifoPop.simPublic()

    // S2 either discovers the variable-latency COPYBACK miss or captures a fixed
    // resident/serial result into S3.  `storeMissDiscovered` already freezes the
    // younger S1 and S0 in this same cycle; they relaunch their lookup after refill,
    // so no rollback tag or duplicate array read is required.
    when(stS2Valid) {
      when(stS2Copyback && !stS2HitAny) {
        // COPYBACK MISS: post-commit write-allocate, off the retire path entirely.
        // No AXI beat is issued directly from S2 -- the refill engine issues the AR.
        stAwDone := True
        stWDone  := True
        pendingStoreMiss  := True
        pendingStorePaddr := stS2Payload.paddr
        pendingMergeData  := stS2MergeData
        pendingMergeStrb  := stS2MergeStrb
        // Task P4.3 Finding 1 fix: lock the victim way/dirty/tag/line HERE, not at
        // the eventual IDLE pickup cycle -- see the `pendingVictim*` decl comment.
        val pVw = victim(stS2Set)
        val pVictimFromS3 = stS3ArrayWrite && (stS3Set === stS2Set) &&
          (stS3Way === pVw)
        val pVictimFromS3D1 = stS3WriteD1 && (stS3WriteSetD1 === stS2Set) &&
          (stS3WriteWayD1 === pVw)
        storeVictimFromS3Dbg := pVictimFromS3 || pVictimFromS3D1
        pendingVictimWay   := pVw
        pendingVictimDirty := rdDirty(pVw) ||
          (pVictimFromS3 && stS3Copyback) ||
          (pVictimFromS3D1 && stS3WriteCopybackD1)
        pendingVictimTag   := Mux(pVictimFromS3, stS3Tag,
          Mux(pVictimFromS3D1, stS3WriteTagD1, rdTag(pVw)))
        pendingVictimLine  := Mux(pVictimFromS3, stS3MergedLine,
          Mux(pVictimFromS3D1, stS3WriteLineD1, rdData(pVw)))
      } otherwise {
        stS3Valid   := True
        stS3Payload := stS2Payload
        stS3Hit     := stS2HitAny
        stS3Way     := stS2HitWay
        stS3OldLine := stS2CapturedLine
      }
    }

    // S3 owns all hit-side effects.  The registered hit/way cut keeps the tag BRAM
    // compare out of the byte-merge, dirty-bit, write-enable and local-ack cones.
    when(stS3Valid) {
      when(stS3ArrayWrite) {
        for (w <- 0 until ways) when(stS3Way === U(w, wayBits bits)) {
          wrEn(w)   := True
          wrSet(w)  := stS3Set
          wrData(w) := stS3MergedLine
          when(stS3Copyback) {
            dirtysWrEn(w)   := True
            dirtysWrSet(w)  := stS3Set
            dirtysWrData(w) := True
            dirtysVoteD5(w) := True   // Task #255 exclusivity tripwire (D5)
          }
        }
        storeArrayWrite    := True
        storeArrayWriteSet := stS3Set
      }

      stMergeReg   := stS3MergeData
      stStrbReg    := stS3MergeStrb
      stAddrReg    := (stS3Payload.paddr(31 downto offBits) ## U(0, offBits bits)).asUInt
      stPreciseReg := stS3Payload.precise
      stIsPipelinedWtReg := !stS3Payload.precise &&
        (stS3Payload.cacheMode === CacheMode.WRITETHROUGH)

      // ── D5/D26/D30: the store's byte range, derived on the CORE-SIDE strobe ──────────
      // D5: reversal within a nibble preserves popcount and contiguity but NOT the offset a
      // run starts at, so this derivation MUST run before SocketByteOrder is applied at the
      // socket boundary. The two transforms are order-dependent; SocketByteOrder's doc
      // comment carries the other half of this statement.
      //
      // Two forms, and the second is normative rather than an optimisation (spec 3.3.1):
      //   useStrb = false -> [paddr[3:0], paddr[3:0] + sizeBytes(size))
      //   useStrb = true  -> the RUN OF SET BITS in the 16-bit line-relative strobe.
      // `size` is NOT an input on the second path and must not be fallen back to:
      // StoreQueue.scala:264 drives slot B's size as a flat Size.LONG whatever its true
      // 1-3-byte extent, and slot B's paddr is the next LINE BASE so paddr[3:0] = 0. A
      // size-derived range would read [0,4) for a slot whose real extent is [0,1) -- and
      // D30's cover would then cover that over-wide range EXACTLY, and exactly wrongly,
      // naming up to three peripheral registers the architectural access never touched.
      val stOffS3   = stS3Payload.paddr(offBits - 1 downto 0)
      val stNBytes  = m68k040.socket.MmioCover.sizeBytes(stS3Payload.size)
      val stStartSz = stOffS3
      val stEndSz   = m68k040.socket.MmioCover.clampedEnd(stOffS3, stNBytes)
      val stStartSt = m68k040.socket.MmioCover.strbRunStart(stS3MergeStrb)
      val stEndSt   = m68k040.socket.MmioCover.strbRunEnd(stS3MergeStrb)
      // Task #236 fix: compute the new stSubP/stSubEnd into locals ONCE, use them both for
      // the register writes AND to register stSubLog2Reg from the same new values -- never
      // from stSubP/stSubEnd read directly here, which would see the pre-edge (stale) value.
      val newStSubP   = Mux(stS3Payload.useStrb, stStartSt, stStartSz)
      val newStSubEnd = Mux(stS3Payload.useStrb, stEndSt,   stEndSz)
      val newStSubLog2 = m68k040.socket.MmioCover.stepLog2(newStSubP, newStSubEnd)
      stSubP       := newStSubP
      stSubEnd     := newStSubEnd
      stSubLog2Reg := newStSubLog2
      stSubActive := stS3Inhibited
      // FMax retime (see `stSubLastReg`'s declaration): recomputed here from the
      // SAME new values this site installs -- `stSubActive`'s new value is
      // `stS3Inhibited`, not the stale register, exactly as `stSubLog2Reg` above
      // uses `newStSubP`/`newStSubEnd` and not the stale `stSubP`/`stSubEnd`.
      stSubLastReg := stSubLastOf(stS3Inhibited, newStSubP, newStSubEnd, newStSubLog2)
      stSubErr    := False

      GenerationFlags.simulation {
        // On the useStrb = false path the two derivations are the SAME range by
        // construction (DcacheTypes.scala:312-320's storeStrbA sets exactly bytes
        // off .. min(off+n,16)-1). Assert it rather than assume it, so a future change to
        // the merge-strobe derivation is loud instead of silently re-widening the range.
        when(stS3Valid && stS3Inhibited && !stS3Payload.useStrb) {
          assert(stStartSt === stStartSz && stEndSt === stEndSz,
            "DcachePlugin: the strobe-derived and size-derived store ranges disagree on a " +
            "useStrb=false INHIBITED store", FAILURE)
        }
      }

      when(stS3Copyback) {
        // A COPYBACK descriptor reaches S3 only on a resident hit.
        stAwDone := True
        stWDone  := True
      } otherwise {
        // WRITETHROUGH hit/miss and INHIBITED remain the sole accepted descriptor
        // until AXI B.  If another physical writer has an open AW/W pair, retain the
        // existing deferred-kickoff discipline.
        when(!evictAxiPairOpen && !maintAxiPairOpen) {
          stAwDone := False
          stWDone  := False
        } otherwise {
          pendingWtKickoff := True
        }
        // WT-pipelining task: push this descriptor's address for correct B-time
        // fault attribution -- see `wtFaultFifoAddr`'s own decl comment. Pushed
        // HERE (unconditional on the immediate-vs-deferred kickoff split above)
        // because either way this descriptor WILL eventually produce exactly one
        // AXI B, whether its aw/w fire this cycle or later via `pendingWtKickoff`.
        wtFaultFifoAddr(wtFaultFifoPush(wtFaultFifoIdxW - 1 downto 0)) :=
          (stS3Payload.paddr(31 downto offBits) ## U(0, offBits bits)).asUInt
        wtFaultFifoPush := wtFaultFifoPush + 1
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

    // Any later user of the single synchronous read port supersedes the held early
    // Forwarded/faulted/squashed loads never emit loadCmd. Their explicit cancel
    // releases the matching tokenized entry and lets maintenance quiesce. A probe
    // canceled on its own launch edge is suppressed in the launch block above.
    when(loadProbeCancelPort.valid) {
      for (i <- 0 until earlyProbeDepth) {
        when(earlyProbeValids(i) && (loadProbeCancelPort.payload.all ||
             (earlyProbeTokens(i) === loadProbeCancelPort.payload.token))) {
          earlyProbeValids(i)  := False
          earlyProbeReadies(i) := False
        }
      }
    }

    // Keep the association token alive but turn a stale snapshot into an explicit
    // ready miss. The later command then consumes the entry and performs the normal
    // synchronous read, after the array mutation. This block is intentionally after
    // probe-result capture so the invalidation wins on a same-cycle write/read race.
    for (i <- 0 until earlyProbeDepth) {
      when(earlyProbeValids(i) && earlyProbeSetWriteVec(i)) {
        earlyProbeReadies(i) := True
        earlyProbeHits(i)    := False
      }
    }

    // S3 is itself the registered local-result stage, so COPYBACK hit write and ack
    // occur together without a further result register.
    val cbHitAckReg = stS3Valid && stS3Copyback && stS3Hit

    // AXI write-through driver -- the registered store result path's OWN, EXCLUSIVE
    // driver (id fixed at 1). EVICT_WR (Task P4.3) never touches these registers or
    // this driver -- it has its own dedicated completion flags and drives axi.aw/axi.w
    // directly (see EVICT_WR's own block above), gated off whenever this driver
    // wants the bus (`storeWantsAxi`), so the two physically never collide.
    //
    // ── D30: one naturally-aligned sub-transaction per iteration on an INHIBITED store ──
    // The cacheable / write-through path is untouched: `stSubActive` is False there, so
    // `subLog2` collapses to size=4 at the line base and the strobe passes through whole.
    // Task #236 fix: was recomputed combinationally every cycle, UNCONDITIONALLY (this val
    // sat outside any `stSubActive`/state gate at all). Now a register read -- see
    // `stSubLog2Reg`'s own declaration comment.
    val stSubLog2  = stSubLog2Reg
    val stSubBytes = (U(1, 4 bits) |<< stSubLog2).resize(4 bits)
    // FMax retime: a plain register read now (see `stSubLastReg`'s declaration for
    // the full cone this used to sit at the head of). The tripwire below is what
    // actually PINS the equivalence -- it re-evaluates the original combinational
    // definition every cycle and fails loudly on any drift, so a future change to
    // any of the three `stSubP`/`stSubEnd`/`stSubActive` write sites that forgets to
    // update `stSubLastReg` alongside them cannot land silently.
    val stSubLast  = stSubLastReg
    GenerationFlags.simulation {
      assert(stSubLastReg === stSubLastOf(stSubActive, stSubP, stSubEnd, stSubLog2Reg),
        "DcachePlugin: stSubLastReg drifted from its combinational definition",
        FAILURE)
    }
    // Mask the merged 16-bit strobe down to THIS sub-transaction's own bytes. Every
    // asserted WSTRB bit therefore lies inside the addressed transfer -- the AXI4 rule v1
    // violates at axi_narrow_to_wide.v:43, where strobe 0110 is paired with awsize=1 at an
    // even address, asserting byte 2 outside a transfer whose lanes are bytes 0-1.
    val stSubStrbMask = Bits(1 << offBits bits)
    for (i <- 0 until (1 << offBits)) {
      // The load-side sequencer's identical mask (:1358) resizes `p` to `offBits + 1` bits
      // before comparing against the `offBits + 1`-bit group index -- SpinalHDL's UInt
      // comparator requires matching declared widths even though every value on both sides
      // is provably in-range, so the raw offBits-wide `stSubP` needs the same explicit
      // resize the brief's snippet omitted (compile-time-only fix, no behavior change).
      stSubStrbMask(i) := (U(i, offBits + 1 bits) >= stSubP.resize(offBits + 1 bits)) &&
                          (U(i, offBits + 1 bits) < (stSubP +^ stSubBytes))
    }
    val stSubAddr = (stAddrReg(31 downto offBits) ## stSubP).asUInt

    when(!stAwDone) {
      axi.aw.valid         := True
      axi.aw.payload.addr  := Mux(stSubActive, stSubAddr, stAddrReg)
      axi.aw.payload.id    := U(AxiIds.D_STORE, AxiIds.ID_W bits)
      axi.aw.payload.len   := U(0, 8 bits)
      axi.aw.payload.size  := Mux(stSubActive, stSubLog2.resize(3 bits), U(4, 3 bits))
      axi.aw.payload.burst := Axi4.burst.INCR
      when(axi.aw.ready) { stAwDone := True }
    }
    when(!stWDone) {
      axi.w.valid        := True
      // A narrow AXI write places its bytes in the lanes matching its own address, and
      // `stMergeReg` is already a full-line, byte-offset-indexed image, so the data needs
      // no shifting -- only the strobe narrows.
      axi.w.payload.data := stMergeReg
      axi.w.payload.strb := Mux(stSubActive, stStrbReg & stSubStrbMask, stStrbReg)
      axi.w.payload.last := True
      when(axi.w.ready) { stWDone := True }
    }

    GenerationFlags.simulation {
      when(stSubActive && axi.aw.valid) {
        assert((stSubP +^ stSubBytes) <= stSubEnd,
          "DcachePlugin: an INHIBITED store sub-transaction runs past the access", FAILURE)
        assert((stSubP.asBits & ((stSubBytes - 1).asBits.resize(offBits))) === B(0, offBits bits),
          "DcachePlugin: an INHIBITED AWADDR is not naturally aligned for its AWSIZE", FAILURE)
        assert(((stStrbReg & stSubStrbMask) & ~stSubStrbMask) === B(0, (1 << offBits) bits),
          "DcachePlugin: a WSTRB bit lies outside the addressed transfer", FAILURE)
      }
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
    // write issuers on this one physical port -- the store write-through backend
    // (id=1) and EVICT_WR's dirty-victim writeback (id=2). B responses MUST be
    // demultiplexed by id, exactly as EVICT_WR's own completion check already does:
    // an eviction's B is NOT a store completion. Accepting any B here made every
    // dirty-victim writeback fire a SPURIOUS storeAck (and, on a non-OKAY writeback,
    // a spurious storeErr). That is not cosmetic: `sq.io.drainAck := dcache.storeAck`
    // (LsEuPlugin), so a spurious ack pops the StoreQueue head
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
    val storeBAck = axi.b.valid && axi.b.ready && (axi.b.payload.id === U(AxiIds.D_STORE, AxiIds.ID_W bits))
    val storeBErr = storeBAck && (axi.b.payload.resp =/= Axi4.resp.OKAY)
    // D30/spec 3.4 "Stores": assert storeAck only after the LAST B handshake, and make
    // storeErr the OR of ALL of them. This is not cosmetic -- `sq.io.drainAck :=
    // dcache.storeAck` (LsEuPlugin), so an ack after sub-transaction 1 of 3 pops the
    // StoreQueue head before the remaining AW/W have been accepted, letting the next drain
    // re-kick the shared stAddrReg/stMergeReg/stStrbReg mid-flight. The plugin's own
    // one-ack-per-descriptor sim asserts at :1972-1992 are what pin this.
    when(storeBAck && !stSubLast) {
      // Advance and re-arm the pair. The existing fail-closed `=== D_STORE` demux above is
      // preserved for EVERY sub-transaction, unchanged.
      // Task #236 fix: register the NEXT sub-transaction's stSubLog2 here too, from the same
      // newStSubP this cycle computes for stSubP -- stSubEnd doesn't change on an advance.
      val newStSubP = (stSubP +^ stSubBytes).resize(offBits bits)
      val newStSubLog2 = m68k040.socket.MmioCover.stepLog2(newStSubP, stSubEnd)
      stSubP       := newStSubP
      stSubLog2Reg := newStSubLog2
      // FMax retime (see `stSubLastReg`'s declaration): `stSubActive` is necessarily
      // True inside this arm (`!stSubLast` implies it, by `stSubLast`'s definition)
      // and this site does not write it, so the new value is a literal True.
      stSubLastReg := stSubLastOf(True, newStSubP, stSubEnd, newStSubLog2)
      // 2026-09-05: the SAME `!evictAxiPairOpen && !maintAxiPairOpen` gate the other two
      // kickoff sites carry (:2933 and :2957). This site is the third writer of
      // `stAwDone`/`stWDone` and was the only one WITHOUT it -- a real hole, not a
      // stylistic gap, because `storeWantsAxi` is derived from these two flags and
      // EVICT_WR's AXI drive is gated on it (:1811/:1820).
      //
      // Un-gated, the sequence is: at cycle T, `stAwDone` still reads True (a registered
      // write lands on the NEXT edge), so `storeWantsAxi` is False and EVICT_WR is
      // presenting AW(evictAddr, id=D_PUSH). If the fabric does not accept it that cycle,
      // then at T+1 `storeWantsAxi` goes True, EVICT_WR's drive is gated OFF, and the
      // store's own `when(!stAwDone)` drive takes over -- AWVALID stays continuously
      // high, but its ADDR and ID CHANGE mid-handshake. That is an AXI4 violation, and on
      // this SoC it is a silent-corruption path rather than a tolerated one: `axi_xbar.v`
      // LATCHES AND COMMITS a request in the cycle AWVALID is first seen
      // (`axi_xbar.v:2789-2810`) and only pulses AWREADY one cycle later, without
      // re-validating. The xbar therefore commits the EVICTION's address and id, the
      // STORE consumes the AWREADY and believes its own write was accepted, the W beat
      // that follows carries the STORE's data, and the single B comes back tagged
      // `ws_mid == D_PUSH` -- so the store's `=== D_STORE` demux never sees its ack and
      // `storeOutstanding` never drains.
      //
      // Deferring through `pendingWtKickoff` is exactly what the two sibling sites do,
      // and its consumer (:2957) is source-ordered ABOVE this block, so the deferred
      // kickoff fires on the first cycle EVICT_WR's pair is closed. `pendingWtKickoff` is
      // itself a `dcIdleForMaint` term, so a deferred kickoff can never be silently
      // stranded. `stSubP`/`stSubLog2Reg`/`stSubLastReg` still advance unconditionally
      // above -- only the AXI re-arm is held.
      when(!evictAxiPairOpen && !maintAxiPairOpen) {
        stAwDone := False
        stWDone  := False
      } otherwise {
        pendingWtKickoff := True
      }
      stSubErr := stSubErr || storeBErr
    }
    // FMax retime: `stSubActive := False` forces `stSubLast` True by definition, and
    // this arm is source-ordered AFTER both other writers, so the same last-assignment-
    // wins precedence the original combinational form got for free is preserved here.
    when(storeBAck && stSubLast) { stSubActive := False; stSubLastReg := True }
    // WT-pipelining task: pop `wtFaultFifoAddr` on the SAME terminal-B event that
    // ends every non-COPYBACK descriptor's AXI leg (`storeBAck && stSubLast`),
    // one-for-one with the push at S3 -- see that FIFO's own decl comment for the
    // ordering proof. Popped unconditionally (error or not): every push WILL
    // produce exactly one terminal B, and a stale/unpopped entry would silently
    // desync the FIFO for every descriptor after it.
    when(storeBAck && stSubLast) { wtFaultFifoPop := wtFaultFifoPop + 1 }
    storeErrReg := (storeBAck && stSubLast) && (stSubErr || storeBErr)
    storeAckReg := (storeBAck && stSubLast) || cbHitAckReg || storeAllocAckReg
    // WT-pipelining task: the pipelined-WT slice of the AXI-B ack source above,
    // driven from `stIsPipelinedWtReg` (captured at S3 alongside `stPreciseReg`,
    // see its own decl comment for why this must be an explicit classifier rather
    // than inferred from `!stPreciseReg`) so it exactly mirrors `inputPipelinedWt`,
    // the admission-side condition that incremented `wtOutstanding` for this same
    // descriptor -- the increment and decrement are provably symmetric regardless
    // of what any caller (real LsEuPlugin traffic or a directed test poking
    // DcachePlugin's `storePort` directly) presents.
    wtStoreAckReg := (storeBAck && stSubLast) && stIsPipelinedWtReg

    // Task P4.6: pins design doc §5 item 7's one-ack-per-store contract now that
    // storeAckReg has three sources (write-through AXI B, registered copyback-hit
    // S3, drain-miss write-allocate). Mirrors storeAckReg's drive above exactly
    // -- `storeBAck`, NOT raw `axi.b.valid && axi.b.ready`, since EVICT_WR's own
    // id=2 B response is already correctly excluded from storeAckReg by the id
    // filter and is not structurally a storeAck source at all. Zero synth cost.
    // Explicit `FAILURE` severity (3-arg form), matching this file's own
    // convention (see the diagFaultKind0Fires/diagFaultPulse asserts above).
    //
    // COPYBACK hits may now ack on consecutive cycles. Variable-latency classes are
    // hardware barriers, so a WT B or store-allocate ack cannot overlap a hit ack.
    GenerationFlags.simulation {
      assert(CountOne(stS2HitVec) <= U(1),
        "DcachePlugin: multiple ways matched one store lookup",
        FAILURE)
      val ackSources = Seq(storeBAck, cbHitAckReg, storeAllocAckReg)
      assert(CountOne(ackSources) <= U(1),
        "DcachePlugin: more than one storeAck source pulsed the same cycle",
        FAILURE)
      assert(!(storeAckReg && (storeOutstanding === 0)),
        "DcachePlugin: storeAck pulsed with no accepted store descriptor",
        FAILURE)
      assert(!(serialStoreInFlight && (storeOutstanding =/= 1)),
        "DcachePlugin: a serial/precise store coexisted with another accepted descriptor",
        FAILURE)
      assert(!(storePort.fire && inputStoreSerial && (storeOutstanding =/= 0)),
        "DcachePlugin: serial/precise store accepted before older descriptors drained",
        FAILURE)
      // WT-pipelining task: this bound is no longer "S0/S1/S2/S3 capacity" alone.
      // `storeOutstanding` now also counts pipelined WT descriptors that have
      // fully exited the shallow pipe and are purely AXI-B-pending. Proven bound:
      // at most 4 descriptors can be simultaneously RESIDENT in S0..S3 (one
      // occupant per stage, structurally unchanged by this task) PLUS at most
      // `MAX_WT_OUTSTANDING` (4) pipelined-WT descriptors already past S3 and
      // purely AXI-pending (capped by the `wtOutstanding` admission gate) -- 8
      // total. (A COPYBACK descriptor can only be resident concurrently with a
      // nonzero `wtOutstanding` if it was admitted BEFORE that WT ramp started --
      // see `storePort.ready`'s per-class comment -- so this bound is not
      // additionally inflated by COPYBACK/WT combinations beyond that.)
      assert(storeOutstanding <= U(8, 4 bits),
        "DcachePlugin: store descriptor occupancy exceeded the proven S0-S3 + pipelined-WT bound",
        FAILURE)
      assert(wtOutstanding <= U(MAX_WT_OUTSTANDING, wtOutstanding.getWidth bits),
        "DcachePlugin: wtOutstanding exceeded its own admission cap",
        FAILURE)
      assert(wtOutstanding <= storeOutstanding,
        "DcachePlugin: wtOutstanding must be a subset of storeOutstanding",
        FAILURE)
      // The ordering invariant this whole task rests on: a COPYBACK/precise
      // descriptor is never admitted while an older pipelined-WT write is still
      // AXI-pending, so its (fast, near-immediate) ack can never race ahead of an
      // older WT's still-open AXI beat and surface an out-of-order ack to the
      // StoreQueue (whose `drainAck` blindly pops its oldest accepted half).
      assert(!(storePort.fire && inputCopyback && (wtOutstanding =/= 0)),
        "DcachePlugin: a COPYBACK descriptor was admitted while an older pipelined WT write was still AXI-pending",
        FAILURE)
      // wtFaultFifo occupancy (push - pop, unsigned wraparound over the
      // one-bit-wider pointers) must never exceed its own depth -- see the
      // pointer decl comment for the full disambiguation argument.
      assert((wtFaultFifoPush - wtFaultFifoPop) <= U(MAX_WT_OUTSTANDING, wtFaultFifoIdxW + 1 bits),
        "DcachePlugin: wtFaultFifo occupancy exceeded MAX_WT_OUTSTANDING",
        FAILURE)
    }

    // ---- Task P4.5: async diagnostic-fault channel ----
    // WT-beat / INHIBITED-drain B-error site, ONLY for a FAST (non-precise) drain --
    // a precise drain's B-error is ALREADY correctly, precisely handled by the SQ's
    // sqFaultCompletion path (Task P2.4); routing it here TOO would be a double-report.
    when(storeErrReg && !stPreciseReg) {
      diagFaultPulse     := True
      // WT-pipelining task: read the FRONT of `wtFaultFifoAddr`, NOT `stAddrReg`
      // directly -- see that FIFO's own decl comment. `storeErrReg` and the pop
      // above (`when(storeBAck && stSubLast) { wtFaultFifoPop := ... }`) fire off
      // the SAME `storeBAck && stSubLast` event this same cycle, so the FIFO's
      // CURRENT (pre-pop-edge) front entry is still exactly this descriptor's own
      // address.
      diagFaultPulseAddr := wtFaultFifoAddr(wtFaultFifoPop(wtFaultFifoIdxW - 1 downto 0))
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
    // because the cache's serial-class and miss barriers guarantee the WT-beat's
    // AXI B and a drain-miss refill can never both be in flight at once. If that
    // hardware invariant were ever violated, `StateMachine` bodies elaborate as
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
    // proves whether the store-backend barrier invariant the hazard's absence
    // relies on is ever actually violated. See the sticky-latch
    // comment above for the full hazard description.
    GenerationFlags.simulation {
      assert(!(diagFaultKind0Fires && diagFaultKind1Fires),
        "DcachePlugin: kind=0 (WT-beat) and kind=1 (drain-miss write-allocate refill) diagnostic-fault pulses fired the SAME cycle -- the serial store-backend barrier was violated; the sticky latch just silently dropped one of the two errors",
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

    // ── Late-sampled stall observability ─────────────────────────────────────────────
    // ASSIGNED HERE, at the very end of the Area, and NOT beside the other
    // `dbgStallDcPack` bits ~1000 lines up: SpinalHDL resolves conditional drives by
    // SOURCE ORDER (last assignment wins), so reading `axi.ar.valid`/`axi.aw.valid`/
    // `axi.w.valid` at the earlier site would capture only the drives elaborated before
    // it -- REFILL's AR (:1876) and EVICT_WR's aw/w (:1812/:1821) but NOT the store
    // backend's own aw/w (:3034/:3043), silently reporting the write channels idle
    // whenever the store path is the one driving them. Every AXI drive in this file is
    // above this point, so these seven bits see the final values.
    /** [31:25], 2026-09-05: WHICH state `busy` is stuck in, and whether any AXI beat is
      * actually outstanding. The p141 ILA capture pinned the wedge to exactly one of the
      * 17 terms above -- `busy` -- and there it stopped, because `busy` is True across
      * the whole EVICT_WR/REFILL/REPLAY excursion and cannot tell them apart. The three
      * candidates need different fixes and are otherwise INDISTINGUISHABLE in the pack:
      * with `evictAwDone`/`evictWDone` both set (as measured), EVICT_WR waiting on a B
      * whose `id =/= D_PUSH` never arrives (:1828) and REFILL waiting on an R that never
      * arrives (:1899) produce a bit-identical [24:0].
      *
      * [26:25] separates them. [28]/[29]/[30]/[31] then say whether the core is still
      * ASKING (a valid presented and unaccepted -- a fabric-side stall) or has gone
      * quiet with a response outstanding (an ID-filter or grant-routing loss, which no
      * watchdog in this design covers: `AxiDMerge.wr.wedge` is gated on its own `busy`,
      * and `axi.b.ready` is held True globally at :468, so a B whose id matches no
      * consumer is silently consumed and dropped). [27] `arSent` distinguishes "REFILL
      * has not issued its AR yet" from "issued, awaiting R".
      *
      * Costs nothing to carry: these are seven previously-zero bits of an already-
      * exported 32-bit pack, no new ports, no new state, no datapath consumer. */
    dbgStallDcPack(26 downto 25) := Mux(fsm.isActive(fsm.EVICT_WR), B("01"),
                                    Mux(fsm.isActive(fsm.REFILL),   B("10"),
                                    Mux(fsm.isActive(fsm.REPLAY),   B("11"), B("00"))))
    dbgStallDcPack(27) := arSent
    dbgStallDcPack(28) := axi.ar.valid
    dbgStallDcPack(29) := axi.r.valid
    dbgStallDcPack(30) := axi.b.valid
    dbgStallDcPack(31) := axi.aw.valid || axi.w.valid

  }

  override def loadProbe = logic.loadProbePort
  override def loadProbeResolve = logic.loadProbeResolvePort
  override def loadProbeCancel = logic.loadProbeCancelPort
  override def loadCmd  = logic.loadCmdPort
  override def loadRsp  = logic.loadRspPort
  override def loadBusy = logic.loadBusyReg
  override def store    = logic.storePort
  override def storeAck = logic.storeAckReg
  override def storeErr = logic.storeErrReg
  override def diagFault = logic.diagFaultValid
  override def maintCmd  = logic.maintCmdPort
  override def maintDone = logic.maintDoneReg
  override def maintError = logic.maintErrorReg
  // Exported precondition (see DcacheService.maintQuiesced's contract): the whole
  // D-cache datapath is idle AND no walk is already running.
  override def maintQuiesced = logic.dcIdleForMaint && !logic.maintBusyReg
}
