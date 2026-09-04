package m68k040.mmu

import m68k040.cache.{CacheMode, DLoadCmd, DLoadRsp, DStoreCmd, TranslationReq, TranslationRsp}
import m68k040.services.{TranslationService, ItlbWalkerDcacheClient, MmuControlService}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** I-side MMU plugin: a banked ITLB + a SEPARATE hardware 3-level table walker
  * behind `TranslationService`, replacing the identity stub. Mirrors `DtlbPlugin`
  * but reads the shared `MmuControlService` (the ONE 68040 MMU enable + root — I and
  * D translation share it) and is instruction-fetch only.
  *
  *  - `mmuEnable` LOW => identity passthrough (ppn=vpn, no fault, always ready)
  *    UNLESS a TTR (ITT0/ITT1) matches, in which case its own cache-mode wins
  *    (WRITETHROUGH otherwise) — TTRs are gated only by their own E bit and
  *    apply independently of `mmuEnable`, matching real 68040 semantics (see
  *    the `ttHit` comment below for why this changed).
  *  - When HIGH: a fetch demand (`req.valid`) looks up the ITLB. A HIT returns
  *    ppn/perms/cacheMode in 1 cycle; a supervisor page accessed in user mode flags
  *    a perm fault. A MISS drops `rsp.ready` (the I-cache stalls on its existing
  *    miss/back-pressure path) and launches this ITLB's OWN `TableWalker`, whose
  *    descriptor reads are `DcacheService` client traffic arbitrated onto the D-cache's
  *    load port (68040 table searches are DATA accesses even for an instruction
  *    translation); on completion the ITLB is filled
  *    (speculative fill OK) and a subsequent lookup hits. A walk that faults
  *    (non-resident / supervisor) is served from a result latch with `rsp.fault`
  *    (the I-cache raises DecodePacket.fault from it).
  *  - Instruction fetch is a READ: the walk sets only the descriptor U bit (never M).
  *    Since the I-cache always drives `req.write=False`, the walker's deferred write
  *    is U-only; it drains at commit via the same U-only queue path (reused from the
  *    DTLB). No write-protect fault on fetch (a fetch is never a write). */
class ItlbPlugin(entries: Int = Tlb.DefaultEntries,
                 ways: Int = Tlb.DefaultWays,
                 banks: Int = Tlb.DefaultBanks) extends FiberPlugin
    with TranslationService with ItlbWalkerDcacheClient {
  var _req: TranslationReq = null
  var _rsp: TranslationRsp = null

  override def req: TranslationReq = _req
  override def rsp: TranslationRsp = _rsp

  // ── Table-walk D-cache client port (see `WalkerDcacheClient`) ──────────────────
  // 68040 table searches are DATA accesses even for an instruction translation, so
  // the I-side walker is a client of the D-cache, not the I-cache. That is not a new
  // cross-side path: this walker was already a D-side AXI master, because it issues
  // the U-bit descriptor WRITE and `axi_i` is AR/R only.
  var _walkLoadCmd:  Stream[DLoadCmd]  = null
  var _walkLoadRsp:  Flow[DLoadRsp]    = null
  var _walkStore:    Stream[DStoreCmd] = null
  var _walkStoreAck: Bool = null
  var _walkStoreErr: Bool = null
  override def walkLoadCmd:  Stream[DLoadCmd]  = _walkLoadCmd
  override def walkLoadRsp:  Flow[DLoadRsp]    = _walkLoadRsp
  override def walkStore:    Stream[DStoreCmd] = _walkStore
  override def walkStoreAck: Bool = _walkStoreAck
  override def walkStoreErr: Bool = _walkStoreErr

  during setup {
    _req = TranslationReq()
    _rsp = TranslationRsp()
    // U-write queue hooks (sibling-driven; default-idle in logic so a standalone DUT
    // that doesn't wire them still elaborates). Fetch sets U only.
    umAccessRobId = UInt(6 bits)
    umCommitValid = Bool()
    umCommitId    = UInt(6 bits)
    umCommitBValid = Bool()
    umCommitBId    = UInt(6 bits)
    umFlush       = Bool()
    flushAll      = Bool()
    _walkLoadCmd  = Stream(DLoadCmd())
    _walkLoadRsp  = Flow(DLoadRsp())
    _walkStore    = Stream(DStoreCmd())
    _walkStoreAck = Bool()
    _walkStoreErr = Bool()
  }

  // U deferred-write queue hooks (driven by the LS-cluster wiring, mirroring the DTLB;
  // for the I-side these carry the fetching access's robId / commit / flush).
  var umAccessRobId: UInt = null
  var umCommitValid: Bool = null
  var umCommitId:    UInt = null
  var umCommitBValid: Bool = null   // retire slot 1 (dual-retire) — see UmWriteQueue.commitB
  var umCommitBId:    UInt = null
  var umFlush:       Bool = null
  // PFLUSHA: flush ALL TLB entries + the walk-result latch (task #136).
  var flushAll:      Bool = null

  val logic = during build new Area {
    val tlb    = new Tlb(entries, ways, banks)
    val walker = new TableWalker()

    // ── Table-walk traffic now goes through the D-CACHE, not a private AXI master ──
    // Mirrors `DtlbPlugin`'s identical block; see its comment for the full rationale.
    // The arbiter-driven halves are default-idle with `allowOverride` so a standalone
    // DUT still elaborates and can attach a sim-side `DcacheClientMemAgent`.
    _walkLoadCmd.valid   := walker.io.loadCmd.valid
    _walkLoadCmd.payload := walker.io.loadCmd.payload
    _walkLoadCmd.ready.allowOverride; _walkLoadCmd.ready := False
    walker.io.loadCmd.ready := _walkLoadCmd.ready
    _walkLoadRsp.valid.allowOverride;   _walkLoadRsp.valid := False
    _walkLoadRsp.payload.allowOverride; _walkLoadRsp.payload.assignDontCare()
    walker.io.loadRsp := _walkLoadRsp
    _walkStoreAck.allowOverride; _walkStoreAck := False
    _walkStoreErr.allowOverride; _walkStoreErr := False
    _walkStore.ready.allowOverride; _walkStore.ready := False
    _walkLoadCmd.valid.simPublic(); _walkLoadCmd.ready.simPublic()
    _walkLoadCmd.payload.simPublic(); _walkLoadRsp.simPublic()
    _walkStore.valid.simPublic(); _walkStore.ready.simPublic()
    _walkStore.payload.simPublic(); _walkStoreAck.simPublic()

    // U queue hooks default-idle (allowOverride) so a standalone DUT elaborates.
    umAccessRobId.allowOverride; umAccessRobId := U(0, 6 bits)
    umCommitValid.allowOverride; umCommitValid := False
    umCommitId.allowOverride;    umCommitId    := U(0, 6 bits)
    umCommitBValid.allowOverride; umCommitBValid := False
    umCommitBId.allowOverride;    umCommitBId    := U(0, 6 bits)
    umFlush.allowOverride;       umFlush       := False
    flushAll.allowOverride;      flushAll      := False; flushAll.simPublic()

    // The ONE shared 68040 MMU control (read, not owned).
    val ctrl = host[MmuControlService]
    val mmuEnable = ctrl.mmuEnable
    val is8K      = ctrl.pageSize8K
    val urp       = ctrl.urp
    val srp       = ctrl.srp
    val itt0      = ctrl.itt0
    val itt1      = ctrl.itt1

    // Task #195 (mirrors DtlbPlugin's identical treatment): VA[12] is part of the
    // in-page offset in 8K mode, not the page number, so it must be excluded from
    // the TLB tag/index compare — two fetches differing only in VA[12] address the
    // same 8K page.
    def tlbKey(vpn: UInt): UInt = Mux(is8K, (vpn(19 downto 1) ## False).asUInt, vpn)

    // ---- ITT0/ITT1 transparent-translation match (task #194) — mirrors DtlbPlugin's
    // DTT0/DTT1 treatment exactly, on the I-side. A hit bypasses the walker/TLB
    // entirely: PA=VA, no fault.
    //
    // CORRECTED (same bug/fix as DtlbPlugin.scala, see its comment for the full
    // real-hardware trace): this used to be additionally gated with
    // `mmuEnable &&` on top of each TTR's own E bit, on the claim that this was
    // an observable no-op since mmuEnable=False was already pure identity. That
    // was architecturally wrong -- ITT0/ITT1 are gated ONLY by their own E bit on
    // real 68040 hardware (already checked inside TtMatch.hit) and apply
    // independently of paged translation, exactly like the D-side DTT0/DTT1.
    // Fixed by dropping `mmuEnable &&` here and by re-priority-ordering the
    // response mux below so a TTR hit is checked before the mmuEnable-off
    // fallback. ITT0 has priority over ITT1 when both match. ---- ----
    val vaHi8   = _req.vpn(19 downto 12)   // == va[31:24]
    val itt0Hit = TtMatch.hit(itt0, vaHi8, _req.supervisor)
    val itt1Hit = !itt0Hit && TtMatch.hit(itt1, vaHi8, _req.supervisor)
    val ttHit   = itt0Hit || itt1Hit
    val ttInhibited = Mux(itt0Hit, TtMatch.inhibited(itt0), TtMatch.inhibited(itt1))

    // ---- TLB lookup (combinational) ----
    tlb.io.lookupVpn := tlbKey(_req.vpn)
    tlb.io.invalidateAll := flushAll
    val tlbHit   = tlb.io.hit
    val tlbEntry = tlb.io.hitEntry

    // ---- result latch (serves the cycle(s) around a walk completion + faults) ----
    val latchValid  = RegInit(False)
    val latchVpn    = Reg(UInt(20 bits))
    val latchPpn    = Reg(UInt(20 bits))
    val latchSup    = Reg(Bool())
    val latchCmode  = Reg(CacheMode())
    val latchFault  = Reg(Bool())

    // ---- walker control. Fetch is always a READ (write=False). ----
    // Task #195: `latchVpn` was filled from a masked (tlbKey'd) walkVpn, so the live
    // side of this compare must be masked the SAME way, or two fetches to the same
    // 8K page differing only in VA[12] would spuriously miss the just-filled latch.
    val latchMatch = latchValid && (latchVpn === tlbKey(_req.vpn))
    val needWalk   = mmuEnable && _req.valid && !tlbHit && !latchMatch && !ttHit &&
                     !walker.io.busy && !walker.io.done

    // ─────────────────────────────────────────────────────────────────────────
    // FMax: REGISTER the miss→walker TRIGGER (sever the _req/tlbHit → walker cone).
    //
    // Driving `walker.io.req.vpn := _req.vpn` and `walker.io.start := needWalk`
    // LIVE put the cross-module cone `I-fetch _req.vpn → tlb hitVec → walker fsm
    // CE` directly on the walker FSM's clock-enable (the I-side mirror of the
    // merged DTLB gate-failer). The walker has its OWN AXI port — there is NO data
    // coupling, only this trigger chain. Capture the miss request {vpn,super,robId}
    // into `missReqReg` flops on the cycle `needWalk` fires, then drive
    // walker.io.req/.start from those FLOPS one cycle later. The deep hitVec cone
    // now ENDS at missReqReg; the walker is driven from registers.
    //
    // Single-outstanding: `missReqReg.valid` is a 1-cycle pulse. `needWalk` already
    // gates on walker-idle (!busy) + the anti-respin (!done, !latchMatch), and we
    // additionally suppress capture while a launch is already pending
    // (`!missReqReg.valid`) so EXACTLY ONE start pulse is emitted per miss:
    //   N   : needWalk -> missReqReg.valid<=1 (capture)         [walker idle]
    //   N+1 : walker.io.start = missReqReg.valid = 1 (launch)   [walker still idle;
    //         capture suppressed by !missReqReg.valid; walker latches reqReg]
    //   N+2 : walker busy -> needWalk false (no re-launch); walk runs to fill/latch.
    // The I-cache holds the fetch valid + stalls on its ITLB miss until the walk
    // fills the TLB; the +1 cycle to LAUNCH is latency-agnostic (lock-step is
    // instruction-level). isWrite is constant False (a fetch is never a write);
    // rootPtr is already an MmuControl flop -> driven live.
    // A cold miss may wait here for one deferred U/M slot. This keeps the resident
    // hit path unchanged while preventing the single walker from completing into a
    // full queue and overwriting an older update.
    val umQueueFull = Bool()
    val missReqReg = new Area {
      val valid = RegInit(False)
      val vpn   = Reg(UInt(20 bits))
      val sup   = Reg(Bool())
      val is8K  = Reg(Bool())
      val robId = Reg(UInt(6 bits))
    }
    // C6 fix: mirrors DtlbPlugin's `missPending`/`walkUmPoison` pair exactly, adapted
    // to this plugin's own walk-in-progress tracking (ItlbPlugin has no `missPending`
    // register of its own -- DtlbPlugin's equivalent single-outstanding window is
    // "captured but not yet resolved", which here spans from the SAME capture cycle
    // through `walker.io.done` inclusive; unlike DtlbPlugin's `missReqReg.valid`
    // -- which stays high across cycles until the walker actually launches --
    // this plugin's `missReqReg.valid` is a bare one-cycle pulse (`walker.io.start`
    // reads it directly the very next cycle with no additional gating), so a
    // dedicated sticky register is needed to span the whole walk, exactly like the
    // D-side `missPending`).
    //
    // Before this fix `ItlbPlugin` had NO poison of any kind on its walk-completion
    // path: a walk that was still in flight when a backend flush (`umFlush`) landed
    // still unconditionally (a) filled the TLB and (b) allocated a real deferred U
    // -write queue entry tagged with the now-squashed access's robId. (b) is C6's
    // named bug -- `UmWriteQueue.commit` only marks an entry committed if it is
    // ALREADY allocated at the exact cycle a commit pulse for its robId arrives; a
    // late allocation just sits invalid-but-unflushed (the earlier `io.flush` pulse
    // already passed) until the ROB *recycles* that same robId (<=64 retires later),
    // at which point an unrelated, later instruction's OWN commit pulse drains it --
    // a real architectural memory write performed on behalf of a wrong-path fetch.
    // (a) is the ITLB-side mirror of C1: once filled, a resident TLB hit never
    // re-triggers a walk (there is no I-side write-hit/M-refresh re-walk path at
    // all), so a poisoned cold-miss fill would otherwise permanently lose the page's
    // U bit with nothing left to ever repair it -- exactly the failure mode this fix
    // closes on the D-side.
    val missPending  = RegInit(False)
    val walkUmPoison = RegInit(False)
    missPending.simPublic()
    missReqReg.valid := False
    when(needWalk && !missReqReg.valid && !umQueueFull) {
      missReqReg.valid := True
      missReqReg.vpn   := tlbKey(_req.vpn)
      missReqReg.sup   := _req.supervisor
      missReqReg.is8K  := is8K
      missReqReg.robId := umAccessRobId
      missPending      := True
      walkUmPoison     := False
    }
    // A speculative resident translation may still fill after a backend squash, but
    // its architectural deferred U write belongs to the killed ROB entry and must
    // never be allocated after the one-cycle flush pulse has passed (mirrors
    // DtlbPlugin's identical `when(umFlush && missPending)` gate verbatim).
    when(umFlush && missPending) { walkUmPoison := True }

    // Real 68040 semantics: a supervisor-space fetch walks SRP, a user-space fetch
    // walks URP (task #131 — see DtlbPlugin's identical treatment).
    walker.io.req.vpn     := missReqReg.vpn
    walker.io.req.rootPtr := Mux(missReqReg.sup, srp, urp)
    walker.io.req.isWrite := False
    walker.io.req.isSuper := missReqReg.sup
    walker.io.req.is8K    := missReqReg.is8K
    walker.io.start       := missReqReg.valid

    // VPN a walk is servicing: latched at walk-LAUNCH (the registered-trigger cycle)
    // so the fill/latch target the right VPN even if _req.vpn changes while walking.
    val walkVpn = Reg(UInt(20 bits))
    val walkRobId = Reg(UInt(6 bits))
    when(missReqReg.valid) {
      walkVpn   := missReqReg.vpn
      walkRobId := missReqReg.robId
    }

    // On walk completion: latch the result and (if no fault) fill the TLB.
    val fe = TlbEntry()
    fe.vpnTag     := 0
    fe.ppn        := walker.io.rsp.ppn
    fe.writeProt  := walker.io.rsp.writeProt
    fe.supervisor := walker.io.rsp.supervisor
    fe.cacheMode  := walker.io.rsp.cacheMode
    // Fetch is always a read (task #210's write-hit M-refresh is a D-side-only
    // concept); still thread the walker's own computed value through rather than
    // a bare constant so the field is never left with a made-up value.
    fe.modified   := walker.io.rsp.modified
    tlb.io.fillValid := False
    tlb.io.fillVpn   := walkVpn
    tlb.io.fillEntry := fe
    when(walker.io.done) {
      missPending := False
      latchValid := True
      latchVpn   := walkVpn
      latchPpn   := walker.io.rsp.ppn
      latchSup   := walker.io.rsp.supervisor
      latchCmode := walker.io.rsp.cacheMode
      latchFault := walker.io.rsp.fault
      // C6/C1-mirror fix: a poisoned walk (backend flush landed while it was still
      // in flight) must not cache its result into the ATC either -- see the long
      // comment at `walkUmPoison`'s declaration above. `!flushAll` additionally
      // covers the exact-same-cycle PFLUSHA/done collision (DtlbPlugin's own
      // `walkFlushPoison` latch exists to ALSO cover a PFLUSHA that landed several
      // cycles before completion; this plugin has no such latch and none is added
      // here -- that residual gap is pre-existing on this walk-completion path
      // (the sibling `latchValid` handling below has the identical same-cycle-only
      // limitation already) and is out of scope for this fix).
      when(!walker.io.rsp.fault && !walkUmPoison && !flushAll) {
        tlb.io.fillValid := True
      }
    }
    // C6-mirror, latch layer: `ItlbPlugin` has a SECOND caching layer DtlbPlugin
    // has no equivalent of -- this 1-entry walk-result latch is STICKY (it
    // persists until explicitly invalidated by umFlush/flushAll or overwritten by
    // a later walk), unlike DtlbPlugin's elastic 1-cycle `rspValid` (naturally
    // self-clearing via `_rsp.fire`). Gating `tlb.io.fillValid` alone is NOT
    // sufficient here: a poisoned walk still sets `latchValid`/`latchVpn` above
    // (deliberately -- the already-squashed triggering access still needs ITS OWN
    // response the cycle after `walker.io.done`, exactly mirroring the D-side's
    // choice to still deliver `rspValid` for a poisoned walk), and without this
    // guard that entry would persist and silently serve a LATER, UNRELATED access
    // to the SAME page via `latchMatch` without ever re-walking -- the identical
    // U-loss shape C1 closes on the TLB fill, just reachable through this second
    // cache instead. Force the latch back invalid exactly one cycle after a
    // poisoned completion: enough for the one immediate response (`needWalk`
    // itself reads `latchMatch` and stays suppressed that same cycle, so no
    // redundant walk is attempted while the response is being served), never long
    // enough to be observed as a real cache hit by anything else.
    val poisonedLatchExpire = RegNext(walker.io.done && walkUmPoison, init = False)
    when(poisonedLatchExpire) { latchValid := False }
    // On a flush (the commit-time access-fault squash), invalidate the latch so a
    // re-fetch after the handler maps the page RE-WALKS (sees the now-resident
    // descriptor) instead of re-reading the stale non-resident fault. Harmless on a
    // branch-mispredict flush (one extra re-walk). The filled TLB stays intact.
    when(umFlush) {
      latchValid := False
    }
    // PFLUSHA: the TLB array is cleared combinationally via tlb.io.invalidateAll above;
    // the 1-entry walk-result latch needs its own explicit clear (see DtlbPlugin).
    when(flushAll) {
      latchValid := False
    }

    // ---- deferred U descriptor-write queue (U-only on fetch; drained at commit) ----
    // A non-faulting walk that needs to set U pushes {robId, addr, newByte}; the
    // entry drains at the fetching instruction's commit and is discarded on a flush.
    val umq = new UmWriteQueue(4)
    umQueueFull := umq.io.full
    // Task #210 added a mandatory pageQuery/pageHazard port to UmWriteQueue for
    // the D-side write-hit M-refresh race; the I-side has no such race (fetches
    // never set M, and no write-hit-triggered re-walk exists here) so this is
    // wired but deliberately NOT consulted -- keeps ItlbPlugin's own behavior
    // byte-for-byte unchanged.
    umq.io.pageQuery := _req.vpn
    // C6 fix: previously UNCONDITIONAL -- no poison of any kind, not even a
    // `flushAll` (PFLUSHA) gate (DtlbPlugin's equivalent line gates on
    // `!walkUmPoison && !walkFlushPoison && !flushAll`). A walk that completed
    // after a flush landed on it mid-walk would still allocate a real deferred
    // U-write entry tagged with an already-squashed robId -- see the long comment
    // at `walkUmPoison`'s declaration above for the full spurious-write mechanism
    // this was producing.
    umq.io.alloc.valid          := walker.io.done && walker.io.rsp.umWrite.valid &&
                                  !walker.io.rsp.fault && !walkUmPoison && !flushAll
    umq.io.alloc.payload.robId  := walkRobId
    umq.io.alloc.payload.addr   := walker.io.rsp.umWrite.addr
    umq.io.alloc.payload.newByte:= walker.io.rsp.umWrite.newByte
    umq.io.commit.valid   := umCommitValid
    umq.io.commit.payload := umCommitId
    umq.io.commitB.valid   := umCommitBValid
    umq.io.commitB.payload := umCommitBId
    umq.io.flush          := umFlush

    // ---- U drain: single-byte RMW as ONE D-cache store ----
    // Mirrors `DtlbPlugin`'s identical block; see its comment for why this is the
    // correctness half of the change. The I-side sets only U (a fetch is never a write),
    // but the lost-update mechanism is the same one.
    val drainArmed   = RegInit(False)
    val drainAckWait = RegInit(False)
    val drainByteOff = umq.io.drain.payload.addr(3 downto 0)
    val drainBeat = Bits(128 bits)
    val drainStrb = Bits(16 bits)
    drainBeat := B(0, 128 bits)
    drainStrb := B(0, 16 bits)
    for (i <- 0 until 16) {
      when(drainByteOff === U(i, 4 bits)) {
        drainBeat(8 * i + 7 downto 8 * i) := umq.io.drain.payload.newByte
        drainStrb(i) := True
      }
    }
    val drainAddrReg = Reg(UInt(32 bits))
    val drainBeatReg = Reg(Bits(128 bits))
    val drainStrbReg = Reg(Bits(16 bits))
    when(umq.io.drain.valid && !drainArmed && !drainAckWait) {
      drainAddrReg := (umq.io.drain.payload.addr(31 downto 4) ## U(0, 4 bits)).asUInt
      drainBeatReg := drainBeat
      drainStrbReg := drainStrb
      drainArmed   := True
    }
    _walkStore.valid              := drainArmed
    _walkStore.payload.paddr      := drainAddrReg
    _walkStore.payload.data       := B(0, 32 bits)
    _walkStore.payload.size       := m68k040.isa.Size.LONG
    _walkStore.payload.useStrb    := True
    _walkStore.payload.strb       := drainStrbReg
    _walkStore.payload.lineData   := drainBeatReg
    _walkStore.payload.cacheMode  := CacheMode.WRITETHROUGH   // stamped by the arbiter
    _walkStore.payload.precise    := False
    when(_walkStore.fire) { drainArmed := False; drainAckWait := True }
    when(drainAckWait && _walkStoreAck) { drainAckWait := False }
    umq.io.drainAck := drainAckWait && _walkStoreAck

    // ---- response mux ----
    // hit-class perm fault for a fetch: a user fetch of a supervisor page. (A fetch
    // is never a write, so write-protect never applies.)
    def permFault(sup: Bool): Bool = sup && !_req.supervisor

    // TTR hit is checked FIRST regardless of mmuEnable (matches DtlbPlugin's fix
    // and real hardware priority: a TTR match bypasses the walker/TLB entirely,
    // whether or not paging is on). The mmuEnable=False fallback is now only
    // reached when the MMU is off AND no TTR matched.
    when(ttHit) {
      // ITT0/ITT1 transparent-translation hit (task #194): bypasses the walker/TLB
      // entirely — PA=VA, never faults. The I-side never inspects WRITETHROUGH vs
      // COPYBACK (no I-side stores), so collapsing the cacheable case to a fixed
      // WRITETHROUGH rather than calling TtMatch.cacheMode is an intentional
      // simplification, not a bug: it only needs the inhibited/cacheable boolean
      // it already had.
      _rsp.ready     := True
      _rsp.ppn       := _req.vpn
      _rsp.cacheMode := Mux(ttInhibited, CacheMode.INHIBITED, CacheMode.WRITETHROUGH)
      _rsp.fault     := False
    } elsewhen(!mmuEnable) {
      _rsp.ready     := True
      _rsp.ppn       := _req.vpn
      _rsp.cacheMode := CacheMode.WRITETHROUGH
      _rsp.fault     := False
    } elsewhen(tlbHit) {
      _rsp.ready     := True
      _rsp.ppn       := tlbEntry.ppn
      _rsp.cacheMode := tlbEntry.cacheMode
      _rsp.fault     := permFault(tlbEntry.supervisor)
    } elsewhen(latchMatch) {
      _rsp.ready     := True
      _rsp.ppn       := latchPpn
      _rsp.cacheMode := latchCmode
      _rsp.fault     := latchFault || permFault(latchSup)
    } otherwise {
      _rsp.ready     := False
      _rsp.ppn       := _req.vpn
      _rsp.cacheMode := CacheMode.WRITETHROUGH   // don't-care (rsp not ready)
      _rsp.fault     := False
    }

    // Sim-only sticky fault observation (mirrors the DTLB).
    val faultSeen = RegInit(False); faultSeen.simPublic()
    when(_rsp.ready && _rsp.fault) { faultSeen := True }
  }
}
