package m68k040.mmu

import m68k040.cache.{CacheMode, TranslationReq, TranslationRsp}
import m68k040.services.{DTranslationService, MmuControlService}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.misc.plugin.FiberPlugin

/** D-side MMU plugin: a banked DTLB + a hardware 3-level table walker behind
  * `DTranslationService`, replacing the identity stub.
  *
  *  - `mmuEnable` (sim-pokeable AND commit-time MOVEC-writable, task #131): when LOW the plugin is a pure
  *    identity passthrough (ppn=vpn, cacheable, no fault, always ready) — every
  *    existing MMU-disabled test/lock-step is unchanged.
  *  - When HIGH: on a translation demand (`req.valid`), look up the TLB. A HIT
  *    returns ppn/perms/cacheMode in 1 cycle (`rsp.ready`), with a perm fault flagged
  *    for a write to a write-protected page or a user access to a supervisor page. A
  *    MISS drops `rsp.ready` (the consumer stalls on its existing back-pressure path)
  *    and launches the `TableWalker`; on completion the TLB is filled (speculative
  *    fill OK) and a subsequent lookup hits. A walk that faults (non-resident /
  *    write-protect / supervisor) is served directly from a result latch with
  *    `rsp.fault` (FLAGGED, no exception delivery this slice).
  *  - The walk's deferred U/M descriptor write is exposed on `umWrite` (drained at
  *    commit by the U/M-write queue — Task 4); the DTLB does NOT write it itself.
  *
  * `urp`/`srp` are sim-pokeable AND commit-time MOVEC-writable (task #131); the
  * walk root is selected per-access from the request's own supervisor bit. */
class DtlbPlugin(entries: Int = Tlb.DefaultEntries,
                 ways: Int = Tlb.DefaultWays,
                 banks: Int = Tlb.DefaultBanks) extends FiberPlugin with DTranslationService {
  var _req: TranslationReq = null
  var _rsp: TranslationRsp = null

  override def req: TranslationReq = _req
  override def rsp: TranslationRsp = _rsp

  during setup {
    // Allocate the service ports in the plugin's own scope (deterministic — avoids
    // a cross-scope assignment when a consumer touches rsp before this plugin builds).
    _req = TranslationReq()
    _rsp = TranslationRsp()
    // U/M queue hooks (sibling-driven; default-idle in logic via allowOverride so a
    // standalone DUT that doesn't wire them still elaborates).
    umAccessRobId = UInt(6 bits)
    umCommitValid = Bool()
    umCommitId    = UInt(6 bits)
    umCommitBValid = Bool()
    umCommitBId    = UInt(6 bits)
    umFlush       = Bool()
    flushAll      = Bool()
  }

  // U/M deferred-write queue hooks (driven by the LS-cluster wiring):
  //  - umAccessRobId : robId of the access currently being translated (tags a walk's
  //    U/M write so it drains at THAT instruction's commit)
  //  - umCommit      : ROB retired this robId (mark the queued U/M write committable)
  //  - umFlush       : mispredict squash (discard speculative U/M writes)
  var umAccessRobId: UInt = null
  var umCommitValid: Bool = null
  var umCommitId:    UInt = null
  var umCommitBValid: Bool = null   // retire slot 1 (dual-retire) — see UmWriteQueue.commitB
  var umCommitBId:    UInt = null
  var umFlush:       Bool = null
  // PFLUSHA: flush ALL TLB entries + the walk-result latch (task #136). Mirrors umFlush's
  // default-idle/allowOverride shape so a standalone DUT elaborates.
  var flushAll:      Bool = null
  // AXI port for the walker + U/M descriptor write drain (full Axi4). Surfaces as
  // top IO so the testbench / synth top attaches the page-table memory.
  var walkerAxi: Axi4 = null
  def axiCfg: Axi4Config = Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = 4)

  val logic = during build new Area {
    val tlb    = new Tlb(entries, ways, banks)
    val walker = new TableWalker()
    walkerAxi = master(Axi4(axiCfg)).setName("dtlbAxi")
    walkerAxi.ar << walker.io.axi.ar
    walkerAxi.r  >> walker.io.axi.r
    // aw/w are driven by the U/M descriptor-write drain below (default idle).
    walkerAxi.aw.valid := False; walkerAxi.aw.payload.assignDontCare()
    walkerAxi.w.valid  := False; walkerAxi.w.payload.assignDontCare()
    walkerAxi.b.ready  := True

    // U/M queue hooks: default-idle (allowOverride) so a standalone DUT elaborates;
    // the LS-cluster wiring OVERRIDES them.
    umAccessRobId.allowOverride; umAccessRobId := U(0, 6 bits)
    umCommitValid.allowOverride; umCommitValid := False
    umCommitId.allowOverride;    umCommitId    := U(0, 6 bits)
    umCommitBValid.allowOverride; umCommitBValid := False
    umCommitBId.allowOverride;    umCommitBId    := U(0, 6 bits)
    umFlush.allowOverride;       umFlush       := False
    flushAll.allowOverride;      flushAll      := False; flushAll.simPublic()

    // The ONE 68040 MMU control is owned by MmuControlPlugin and shared with the
    // ITLB; the DTLB only READS it (no longer owns its own enable/root regs).
    val ctrl = host[MmuControlService]
    val mmuEnable = ctrl.mmuEnable
    val urp       = ctrl.urp
    val srp       = ctrl.srp

    // ---- TLB lookup (combinational) ----
    tlb.io.lookupVpn := _req.vpn
    tlb.io.invalidateAll := flushAll
    val tlbHit   = tlb.io.hit
    val tlbEntry = tlb.io.hitEntry

    // ─────────────────────────────────────────────────────────────────────────
    // FMax: REGISTER the TLB hit-path result (decouple the deep `hitVec` cone).
    //
    // The combinational hit lookup is `lookupVpn -> banked way-mux tag-compare
    // (hitVec, CARRY8) -> tlb.io.hit / hitEntry -> permFault/ppn -> rsp.ready/.fault`.
    // That arc was the SHARED root of the route-dominated full-core limiters: it fans
    // (a) into the LS-EU FSM s1Valid next-state, (b) into the D-cache load-accept +
    // dataMem read/store-write, and (c) into the D-cache s1Fault — every consumer
    // starts a fresh long cone off the LIVE lookup.
    //
    // Capture the hit's {vpn, ppn, writeProt, supervisor, cacheMode} into a 1-entry
    // result register (`hr*`). The hit-class response is served from this REGISTER
    // when it holds the live req.vpn (`hrMatch`), so the deep hitVec cone now ENDS at
    // a flop instead of fanning combinationally into all consumers. permFault is
    // recomputed against the LIVE req.write/supervisor (a 2-LUT cone, not the deep
    // hitVec) so the access-class semantics are unchanged.
    //
    // Cost: the FIRST cycle a new vpn is presented, hrMatch is False (the register
    // doesn't yet hold this vpn) so rsp.ready is low for that one cycle — the consumer
    // stalls exactly as it already does on a DTLB miss (single-outstanding back-
    // pressure holds req.vpn stable), then hrMatch asserts next cycle. Lock-step is
    // latency-agnostic, so the +1 translate cycle on a TLB hit is free. The register
    // is updated EVERY cycle a hit is observed, so it tracks the held vpn within one
    // cycle and self-heals after a fill (a just-filled entry hits next cycle -> hr
    // captures it). The `latchMatch` fast-path (walk-completion / fault) is unchanged
    // and still serves combinationally so a walk result is delivered without an extra
    // cycle (it already cost the multi-cycle walk).
    val hrValid = RegInit(False)
    val hrVpn   = Reg(UInt(20 bits))
    val hrPpn   = Reg(UInt(20 bits))
    val hrWp    = Reg(Bool())
    val hrSup   = Reg(Bool())
    val hrCmode = Reg(CacheMode())
    hrValid := False
    when(mmuEnable && _req.valid && tlbHit) {
      hrValid := True
      hrVpn   := _req.vpn
      hrPpn   := tlbEntry.ppn
      hrWp    := tlbEntry.writeProt
      hrSup   := tlbEntry.supervisor
      hrCmode := tlbEntry.cacheMode
    }
    val hrMatch = hrValid && (hrVpn === _req.vpn)

    // ---- result latch (serves the cycle(s) around a walk completion + faults) ----
    val latchValid  = RegInit(False)
    val latchVpn    = Reg(UInt(20 bits))
    val latchPpn    = Reg(UInt(20 bits))
    val latchWp     = Reg(Bool())
    val latchSup    = Reg(Bool())
    val latchCmode  = Reg(CacheMode())
    val latchFault  = Reg(Bool())

    // ---- walker control ----
    // A walk is needed when enabled + a live demand + TLB miss + the latch doesn't
    // already hold this VPN's result + the walker is idle.
    val latchMatch = latchValid && (latchVpn === _req.vpn)
    // Suppress re-trigger on the `done` cycle: busy has dropped but the fill/latch
    // (registered) have not yet taken effect, so a naive needWalk would spuriously
    // restart the walker for the just-resolved VPN.
    val needWalk   = mmuEnable && _req.valid && !tlbHit && !latchMatch &&
                     !walker.io.busy && !walker.io.done

    // ─────────────────────────────────────────────────────────────────────────
    // FMax: REGISTER the miss→walker TRIGGER (sever the Dcache valids → walker cone).
    //
    // Driving `walker.io.req.vpn := _req.vpn` and `walker.io.start := needWalk`
    // LIVE put the cross-module cone `Dcache valids → LsEu → _req.vpn → tlb hitVec
    // → walker fsm CE` (WNS −1.206, the gate-failer) directly on the walker FSM's
    // clock-enable. The walker has its OWN AXI port — there is NO data coupling,
    // only this trigger chain. Capture the miss request {vpn,write,super,robId}
    // into `missReqReg` flops on the cycle `needWalk` fires, then drive
    // walker.io.req/.start from those FLOPS one cycle later. The deep hitVec/valids
    // cone now ENDS at missReqReg; the walker is driven from registers.
    //
    // Single-outstanding: `missReqReg.valid` is a 1-cycle pulse. `needWalk` already
    // gates on walker-idle (!busy) + the anti-respin (!done, !latchMatch), and we
    // additionally suppress capture while a launch is already pending
    // (`!missReqReg.valid`) so EXACTLY ONE start pulse is emitted per miss:
    //   N   : needWalk -> missReqReg.valid<=1 (capture)         [walker idle]
    //   N+1 : walker.io.start = missReqReg.valid = 1 (launch)   [walker still idle;
    //         capture suppressed by !missReqReg.valid; walker latches reqReg]
    //   N+2 : walker busy -> needWalk false (no re-launch); walk runs to fill/latch.
    // The LS-EU holds the request valid + stalls (rsp.ready=False) until the walk
    // fills the TLB; the +1 cycle to LAUNCH is latency-agnostic (lock-step is
    // instruction-level). rootPtr is already an MmuControl flop -> driven live.
    val missReqReg = new Area {
      val valid = RegInit(False)
      val vpn   = Reg(UInt(20 bits))
      val write = Reg(Bool())
      val sup   = Reg(Bool())
      val robId = Reg(UInt(6 bits))
    }
    missReqReg.valid := False
    when(needWalk && !missReqReg.valid) {
      missReqReg.valid := True
      missReqReg.vpn   := _req.vpn
      missReqReg.write := _req.write
      missReqReg.sup   := _req.supervisor
      missReqReg.robId := umAccessRobId
    }

    // Real 68040 semantics: a supervisor-space access walks SRP, a user-space
    // access walks URP (task #131 — previously both shared ONE `rootPtr`, which
    // was architecturally wrong: MOVEC-driven user code couldn't have its OWN
    // page tables independent of supervisor's). missReqReg.sup is the SAME bit
    // already latched for walker.io.req.isSuper below.
    walker.io.req.vpn     := missReqReg.vpn
    walker.io.req.rootPtr := Mux(missReqReg.sup, srp, urp)
    walker.io.req.isWrite := missReqReg.write
    walker.io.req.isSuper := missReqReg.sup
    walker.io.start       := missReqReg.valid

    // VPN a walk is servicing: latched at walk-LAUNCH (the registered-trigger cycle)
    // so the fill/latch target the right VPN even if _req.vpn changes while walking.
    val walkVpn = Reg(UInt(20 bits))
    val walkRobId = Reg(UInt(6 bits))   // robId of the access that triggered the walk
    when(missReqReg.valid) {
      walkVpn   := missReqReg.vpn
      walkRobId := missReqReg.robId
    }

    // On walk completion: latch the result and (if no fault) fill the TLB.
    val fe = TlbEntry()
    fe.vpnTag     := 0   // set by the TLB fill from fillVpn
    fe.ppn        := walker.io.rsp.ppn
    fe.writeProt  := walker.io.rsp.writeProt
    fe.supervisor := walker.io.rsp.supervisor
    fe.cacheMode  := walker.io.rsp.cacheMode
    tlb.io.fillValid := False
    tlb.io.fillVpn   := walkVpn
    tlb.io.fillEntry := fe
    when(walker.io.done) {
      latchValid := True
      latchVpn   := walkVpn
      latchPpn   := walker.io.rsp.ppn
      latchWp    := walker.io.rsp.writeProt
      latchSup   := walker.io.rsp.supervisor
      latchCmode := walker.io.rsp.cacheMode
      // Task #137 fix: only latch the walker's fault verdict as STICKY when the
      // reason is access-INDEPENDENT (NON_RESIDENT — any access to a non-resident
      // page always faults, regardless of read/write/privilege). WRITE_PROTECT and
      // SUPERVISOR are access-DEPENDENT (a write-protected page faults on a WRITE
      // but not a READ; a supervisor page faults for USER but not SUPERVISOR) — for
      // those, latchFault must NOT be sticky-true, or a later `latchMatch` hit from
      // a DIFFERENT access (e.g. a read right after the write that triggered this
      // walk) incorrectly inherits the ORIGINAL access's fault verdict. permFault()
      // below already correctly RE-derives write-protect/supervisor faults live
      // against the current request (latchWp/latchSup are captured unconditionally,
      // regardless of fault status) — latchFault only needs to cover the case
      // permFault can't: non-residency.
      latchFault := walker.io.rsp.fault && (walker.io.rsp.faultReason === MmuFaultReason.NON_RESIDENT)
      when(!walker.io.rsp.fault) {
        tlb.io.fillValid := True
      }
    }
    // A faulting walk caches its FAULT in the result latch (the TLB is NOT filled on
    // a fault). On a flush (umFlush == the commit-time doFlush squash — which fires
    // when an access fault is DELIVERED) invalidate the latch so a re-executed access
    // after the handler maps the page RE-WALKS (and now sees the resident descriptor)
    // rather than re-reading the stale non-resident fault. A real 68040 handler
    // PFLUSHes the ATC before RTE; clearing the 1-entry latch here is the equivalent
    // for our result cache (and is harmless on a branch-mispredict flush — it just
    // forces one re-walk). The filled TLB is left intact (it only holds resident
    // translations, which remain valid across a flush).
    when(umFlush) {
      latchValid := False
    }
    // PFLUSHA: the TLB array itself is cleared combinationally via tlb.io.invalidateAll
    // above; the 1-entry walk-result latch needs its own explicit clear (it bypasses the
    // TLB array entirely on a match) or a same-VPN access right after the flush would
    // still hit stale latched state.
    when(flushAll) {
      latchValid := False
    }

    // ---- deferred U/M descriptor-write queue (speculative; drained at commit) ----
    // A non-faulting walk that needs to set U (and M on a write) pushes {robId, addr,
    // newByte}; the entry drains at the triggering instruction's commit (RMW the
    // descriptor byte over this same AXI bus) and is discarded on a flush. NOT
    // performed speculatively.
    val umq = new UmWriteQueue(4)
    umq.io.alloc.valid          := walker.io.done && walker.io.rsp.umWrite.valid && !walker.io.rsp.fault
    umq.io.alloc.payload.robId  := walkRobId
    umq.io.alloc.payload.addr   := walker.io.rsp.umWrite.addr
    umq.io.alloc.payload.newByte:= walker.io.rsp.umWrite.newByte
    umq.io.commit.valid   := umCommitValid
    umq.io.commit.payload := umCommitId
    umq.io.commitB.valid   := umCommitBValid
    umq.io.commitB.payload := umCommitBId
    umq.io.flush          := umFlush

    // ---- U/M drain: single-byte RMW over the DTLB AXI write channel ----
    // The descriptor lives at a physical byte address; write just that byte (16-byte
    // beat with a one-hot strobe at addr[3:0]). Single-outstanding: present aw+w,
    // hold until both handshake, ack on b. The queue holds the entry until `drainAck`.
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
      walkerAxi.aw.payload.id   := U(3, 4 bits)
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
    // ack the queue once the write lands (b handshake). drainAck pops the entry.
    umq.io.drainAck := walkerAxi.b.valid && walkerAxi.b.ready

    // ---- response mux ----
    // hit-class perm fault: a write to a write-protected page, or a user access to
    // a supervisor page (computed from the TLB/latch perms + the access class).
    def permFault(wp: Bool, sup: Bool): Bool =
      (_req.write && wp) || (sup && !_req.supervisor)

    when(!mmuEnable) {
      // identity passthrough (existing behavior)
      _rsp.ready     := True
      _rsp.ppn       := _req.vpn
      _rsp.cacheMode := CacheMode.CACHEABLE
      _rsp.fault     := False
    } elsewhen(hrMatch) {
      // hit served from the REGISTERED result (the deep hitVec cone ended at hr*).
      // permFault is recomputed against the live req.write/supervisor (short cone);
      // the held entry perms (hrWp/hrSup) and ppn/cacheMode come from flops.
      _rsp.ready     := True
      _rsp.ppn       := hrPpn
      _rsp.cacheMode := hrCmode
      _rsp.fault     := permFault(hrWp, hrSup)
    } elsewhen(latchMatch) {
      // walk just resolved this VPN (fault, or the 1-cycle gap before the fill).
      _rsp.ready     := True
      _rsp.ppn       := latchPpn
      _rsp.cacheMode := latchCmode
      _rsp.fault     := latchFault || permFault(latchWp, latchSup)
    } otherwise {
      // miss: walking (rsp not ready -> consumer stalls).
      _rsp.ready     := False
      _rsp.ppn       := _req.vpn
      _rsp.cacheMode := CacheMode.CACHEABLE
      _rsp.fault     := False
    }

    // Sim-only sticky fault observation: set whenever a translation resolves with a
    // fault flagged (FLAGGED only — no exception delivery this slice). The lock-step
    // harness asserts it for the non-resident-page fault test.
    val faultSeen = RegInit(False); faultSeen.simPublic()
    // Sim-only: the faulting access's page-base VA (vpn<<12) — the lock-step harness
    // asserts the mid-EA pointer-load fault carries the POINTER-LOAD address (precision).
    val faultVa   = Reg(UInt(32 bits)) init (0); faultVa.simPublic()
    when(_rsp.ready && _rsp.fault) { faultSeen := True; faultVa := (_req.vpn << 12).resize(32) }
  }
}
