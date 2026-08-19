package m68k040.mmu

import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.{TranslationService, MmuControlService}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.misc.plugin.FiberPlugin

/** I-side MMU plugin: a banked ITLB + a SEPARATE hardware 3-level table walker
  * behind `TranslationService`, replacing the identity stub. Mirrors `DtlbPlugin`
  * but reads the shared `MmuControlService` (the ONE 68040 MMU enable + root — I and
  * D translation share it) and is instruction-fetch only.
  *
  *  - `mmuEnable` LOW => pure identity passthrough (ppn=vpn, cacheable, no fault,
  *    always ready): every MMU-disabled test / lock-step is unchanged.
  *  - When HIGH: a fetch demand (`req.valid`) looks up the ITLB. A HIT returns
  *    ppn/perms/cacheMode in 1 cycle; a supervisor page accessed in user mode flags
  *    a perm fault. A MISS drops `rsp.ready` (the I-cache stalls on its existing
  *    miss/back-pressure path) and launches this ITLB's OWN `TableWalker` (dedicated
  *    AXI read port to the shared page table); on completion the ITLB is filled
  *    (speculative fill OK) and a subsequent lookup hits. A walk that faults
  *    (non-resident / supervisor) is served from a result latch with `rsp.fault`
  *    (the I-cache raises DecodePacket.fault from it).
  *  - Instruction fetch is a READ: the walk sets only the descriptor U bit (never M).
  *    Since the I-cache always drives `req.write=False`, the walker's deferred write
  *    is U-only; it drains at commit via the same U-only queue path (reused from the
  *    DTLB). No write-protect fault on fetch (a fetch is never a write). */
class ItlbPlugin(entries: Int = Tlb.DefaultEntries,
                 ways: Int = Tlb.DefaultWays,
                 banks: Int = Tlb.DefaultBanks,
                 val socketMerged: Boolean = false) extends FiberPlugin with TranslationService {
  var _req: TranslationReq = null
  var _rsp: TranslationRsp = null

  override def req: TranslationReq = _req
  override def rsp: TranslationRsp = _rsp

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
  // Dedicated AXI port for THIS ITLB's walker + the U descriptor-write drain.
  var walkerAxi: Axi4 = null
  def axiCfg: Axi4Config = Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = 4)

  val logic = during build new Area {
    val tlb    = new Tlb(entries, ways, banks)
    val walker = new TableWalker()
    walkerAxi = (if (socketMerged) Axi4(axiCfg) else master(Axi4(axiCfg))).setName("itlbAxi")
    walkerAxi.ar << walker.io.axi.ar
    walkerAxi.r  >> walker.io.axi.r
    walkerAxi.aw.valid := False; walkerAxi.aw.payload.assignDontCare()
    walkerAxi.w.valid  := False; walkerAxi.w.payload.assignDontCare()
    // D27 (axi-socket adapter spec section 4.3): FAIL-CLOSED on the response ID, matching
    // the D-cache's own discipline verbatim (DcachePlugin.scala:1948-1956: "an
    // unrecognized id simply not ack anything -- a hung drain, which is loud and
    // debuggable, instead of a silent spurious ack").
    //
    // Today this walker is a physically separate master and the only responses reaching it
    // are its own, so the unconditional `True` was safe. After the D8 merge it is the
    // arbiter's owner latch, and NOTHING ELSE, that stands between this walker and a
    // response belonging to the D-cache or the reset-vector reader. This guard is the
    // second line of defence that makes the safety argument uniform across all three
    // merged masters instead of resting on the arbiter alone.
    //
    // It does NOT disambiguate ITLB from DTLB -- they share AR=2/AW=3 (AxiIds.scala:67,69)
    // and D8's owner latch is what separates them. It fail-closes the CLASS boundary
    // between walker traffic and everything else. The drain ack at :271 is already gated on
    // this handshake, so a rejected beat simply does not ack.
    walkerAxi.b.ready  := walkerAxi.b.payload.id === U(m68k040.cache.AxiIds.WALK_WRITE,
                                                       m68k040.cache.AxiIds.ID_W bits)

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
    // entirely: PA=VA, no fault. Only consulted while the MMU is enabled. ITT0 has
    // priority over ITT1 when both match. ---- ----
    val vaHi8   = _req.vpn(19 downto 12)   // == va[31:24]
    val itt0Hit = mmuEnable && TtMatch.hit(itt0, vaHi8, _req.supervisor)
    val itt1Hit = mmuEnable && !itt0Hit && TtMatch.hit(itt1, vaHi8, _req.supervisor)
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
    missReqReg.valid := False
    when(needWalk && !missReqReg.valid && !umQueueFull) {
      missReqReg.valid := True
      missReqReg.vpn   := tlbKey(_req.vpn)
      missReqReg.sup   := _req.supervisor
      missReqReg.is8K  := is8K
      missReqReg.robId := umAccessRobId
    }

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
      latchValid := True
      latchVpn   := walkVpn
      latchPpn   := walker.io.rsp.ppn
      latchSup   := walker.io.rsp.supervisor
      latchCmode := walker.io.rsp.cacheMode
      latchFault := walker.io.rsp.fault
      when(!walker.io.rsp.fault) {
        tlb.io.fillValid := True
      }
    }
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
    umq.io.alloc.valid          := walker.io.done && walker.io.rsp.umWrite.valid && !walker.io.rsp.fault
    umq.io.alloc.payload.robId  := walkRobId
    umq.io.alloc.payload.addr   := walker.io.rsp.umWrite.addr
    umq.io.alloc.payload.newByte:= walker.io.rsp.umWrite.newByte
    umq.io.commit.valid   := umCommitValid
    umq.io.commit.payload := umCommitId
    umq.io.commitB.valid   := umCommitBValid
    umq.io.commitB.payload := umCommitBId
    umq.io.flush          := umFlush

    // ---- U drain: single-byte RMW over the ITLB AXI write channel ----
    val drainAwDone = RegInit(True)
    val drainWDone  = RegInit(True)
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
    when(umq.io.drain.valid && drainAwDone && drainWDone) {
      drainAddrReg := (umq.io.drain.payload.addr(31 downto 4) ## U(0, 4 bits)).asUInt
      drainBeatReg := drainBeat
      drainStrbReg := drainStrb
      drainAwDone  := False
      drainWDone   := False
    }
    when(!drainAwDone) {
      walkerAxi.aw.valid        := True
      walkerAxi.aw.payload.addr := drainAddrReg
      walkerAxi.aw.payload.id   := U(m68k040.cache.AxiIds.WALK_WRITE, m68k040.cache.AxiIds.ID_W bits)
      walkerAxi.aw.payload.len  := U(0, 8 bits)
      walkerAxi.aw.payload.size := U(4, 3 bits)
      walkerAxi.aw.payload.burst := spinal.lib.bus.amba4.axi.Axi4.burst.INCR
      when(walkerAxi.aw.ready) { drainAwDone := True }
    }
    when(!drainWDone) {
      walkerAxi.w.valid        := True
      walkerAxi.w.payload.data := drainBeatReg
      walkerAxi.w.payload.strb := drainStrbReg
      walkerAxi.w.payload.last := True
      when(walkerAxi.w.ready) { drainWDone := True }
    }
    umq.io.drainAck := walkerAxi.b.valid && walkerAxi.b.ready

    // ---- response mux ----
    // hit-class perm fault for a fetch: a user fetch of a supervisor page. (A fetch
    // is never a write, so write-protect never applies.)
    def permFault(sup: Bool): Bool = sup && !_req.supervisor

    when(!mmuEnable) {
      _rsp.ready     := True
      _rsp.ppn       := _req.vpn
      _rsp.cacheMode := CacheMode.WRITETHROUGH
      _rsp.fault     := False
    } elsewhen(ttHit) {
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
