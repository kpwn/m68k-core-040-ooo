package m68k040.mmu

import m68k040.cache.{CacheMode, DTranslationCmd, DTranslationRsp}
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
  *  - When HIGH: every accepted tagged command looks up the TLB. A HIT produces a
  *    held registered response one cycle later, with the access-class permission
  *    fault captured alongside the same token. A MISS blocks further commands and
  *    launches the single `TableWalker`; completion fills the TLB (speculative fill
  *    OK) and produces the original tagged response.
  *  - The walk's deferred U/M descriptor write is exposed on `umWrite` (drained at
  *    commit by the U/M-write queue — Task 4); the DTLB does NOT write it itself.
  *
  * `urp`/`srp` are sim-pokeable AND commit-time MOVEC-writable (task #131); the
  * walk root is selected per-access from the request's own supervisor bit. */
class DtlbPlugin(entries: Int = Tlb.DefaultEntries,
                 ways: Int = Tlb.DefaultWays,
                 banks: Int = Tlb.DefaultBanks,
                 val socketMerged: Boolean = false) extends FiberPlugin with DTranslationService {
  var _req: Stream[DTranslationCmd] = null
  var _rsp: Stream[DTranslationRsp] = null

  override def req: Stream[DTranslationCmd] = _req
  override def rsp: Stream[DTranslationRsp] = _rsp

  during setup {
    // Allocate the service ports in the plugin's own scope (deterministic — avoids
    // a cross-scope assignment when a consumer touches rsp before this plugin builds).
    _req = Stream(DTranslationCmd())
    _rsp = Stream(DTranslationRsp())
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
  // PFLUSHA: flush ALL TLB entries + the elastic result slot (task #136). Mirrors umFlush's
  // default-idle/allowOverride shape so a standalone DUT elaborates.
  var flushAll:      Bool = null
  // AXI port for the walker + U/M descriptor write drain (full Axi4). Surfaces as
  // top IO so the testbench / synth top attaches the page-table memory.
  var walkerAxi: Axi4 = null
  def axiCfg: Axi4Config = Axi4Config(addressWidth = 32, dataWidth = 128, idWidth = 4)

  val logic = during build new Area {
    val tlb    = new Tlb(entries, ways, banks)
    val walker = new TableWalker()
    walkerAxi = (if (socketMerged) Axi4(axiCfg) else master(Axi4(axiCfg))).setName("dtlbAxi")
    walkerAxi.ar << walker.io.axi.ar
    walkerAxi.r  >> walker.io.axi.r
    // aw/w are driven by the U/M descriptor-write drain below (default idle).
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
    // between walker traffic and everything else. The drain ack at :340 is already gated on
    // this handshake, so a rejected beat simply does not ack.
    walkerAxi.b.ready  := walkerAxi.b.payload.id === U(m68k040.cache.AxiIds.WALK_WRITE,
                                                       m68k040.cache.AxiIds.ID_W bits)

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
    val is8K      = ctrl.pageSize8K
    val urp       = ctrl.urp
    val srp       = ctrl.srp
    val dtt0      = ctrl.dtt0
    val dtt1      = ctrl.dtt1

    // Task #195: in 8K-page mode, VA[12] (vpn(0)) is part of the in-page offset, not
    // the page number — two accesses differing only in VA[12] address the SAME 8K
    // page and MUST hit/fill the same TLB entry. Masking it out of the TLB-facing key
    // is sufficient (Tlb.scala itself is untouched: it just sees a 20-bit key with
    // bit 0 forced to a constant in 8K mode, so two such VAs collide into one tag/
    // set/bank exactly like a real duplicate lookup would). The walker's own PGI
    // computation (TableWalker's RD_PTR state) never reads vpn(0) in 8K mode either,
    // so masking it before `missReqReg.vpn` costs nothing there.
    def tlbKey(vpn: UInt): UInt = Mux(is8K, (vpn(19 downto 1) ## False).asUInt, vpn)

    // ---- DTT0/DTT1 transparent-translation match (task #194) ----
    // A hit bypasses the walker/TLB entirely: PA=VA, no fault, no page table
    // consulted. Only consulted while the MMU is enabled (mmuEnable=False is
    // ALREADY pure identity below — TTRs add no observable difference there, and
    // gating this way keeps every pre-existing MMU-disabled test bit-for-bit
    // unchanged). DTT0 has priority over DTT1 when both match (checked first).
    val vaHi8   = _req.payload.vpn(19 downto 12)   // == va[31:24] (vpn is va[31:12])
    val dtt0Hit = mmuEnable && TtMatch.hit(dtt0, vaHi8, _req.payload.supervisor)
    val dtt1Hit = mmuEnable && !dtt0Hit && TtMatch.hit(dtt1, vaHi8, _req.payload.supervisor)
    val ttHit   = dtt0Hit || dtt1Hit

    // ---- TLB lookup (combinational) ----
    tlb.io.lookupVpn := tlbKey(_req.payload.vpn)
    tlb.io.invalidateAll := flushAll
    val tlbHit   = tlb.io.hit
    val tlbEntry = tlb.io.hitEntry

    // ─────────────────────────────────────────────────────────────────────────
    // Elastic registered response.  The 32-entry ATC remains the existing shallow
    // two-bank/four-way structure; this change adds no CAM entries or lookup copy.
    // The deep hitVec/way-mux cone ends at rspPayload.  Command readiness depends
    // only on registered response/walker occupancy, never on the current hit result.
    def permFault(wp: Bool, sup: Bool, write: Bool, supervisor: Bool): Bool =
      (write && wp) || (sup && !supervisor)

    val rspValid   = RegInit(False)
    val rspPayload = Reg(DTranslationRsp())
    val rspVpn     = Reg(UInt(20 bits)) // sim-only precise fault observation
    val missPending = RegInit(False)
    val walkFlushPoison = RegInit(False)
    val walkUmPoison = RegInit(False)
    missPending.simPublic()

    // Task #210: forward-declared (umq itself is built further below, mirroring
    // umQueueFull's own forward-declaration) -- True while ANY queued-but-not-yet-
    // drained deferred U/M descriptor write targets the SAME PAGE as this cycle's
    // incoming request. See the assignment site (next to `umq`) for the full
    // rationale: a resolved translation alone is not enough to guarantee a
    // PROGRAM-ORDER-LATER access to that same page observes the updated
    // descriptor, because the actual memory RMW only drains after the triggering
    // instruction commits -- strictly later than when its own translation
    // response (and hence its retirement) becomes possible.
    val umqPageHazard = Bool()

    _rsp.valid   := rspValid && !flushAll
    _rsp.payload := rspPayload
    _req.ready   := !missPending && (!rspValid || _rsp.ready) && !flushAll && !umqPageHazard

    when(_rsp.fire) { rspValid := False }

    // ─────────────────────────────────────────────────────────────────────────
    // FMax: REGISTER the miss→walker TRIGGER (sever the Dcache valids → walker cone).
    //
    // Driving `walker.io.req.vpn` and `walker.io.start` from a live miss result
    // LIVE put the cross-module cone `Dcache valids → LsEu → _req.vpn → tlb hitVec
    // → walker fsm CE` (WNS −1.206, the gate-failer) directly on the walker FSM's
    // clock-enable. The walker has its OWN AXI port — there is NO data coupling,
    // only this trigger chain. Capture the miss request {vpn,write,super,robId}
    // into `missReqReg` flops on the accepted miss cycle, then drive
    // walker.io.req/.start from those FLOPS one cycle later. The deep hitVec/valids
    // cone now ENDS at missReqReg; the walker is driven from registers.
    //
    // Single-outstanding: `missReqReg.valid` holds one captured miss until the
    // walker and one deferred-U/M slot are available. `missPending` prevents another
    // command from entering until that walk resolves or is invalidated:
    //   N   : req.fire miss -> missReqReg.valid<=1 (capture)    [walker idle]
    //   N+1 : walker.io.start = missReqReg.valid = 1 (launch)   [walker still idle;
    //         capture suppressed by !missReqReg.valid; walker latches reqReg]
    //   N+2 : walker busy; walk runs to fill the TLB and held response register.
    // rootPtr is already an MmuControl flop -> driven live.
    // Forward-declared credit from the U/M queue below. A full queue delays only a
    // captured cold miss's walker launch; direct resident hits still use the same
    // readiness and never acquire a queue-full dependency.
    val umQueueFull = Bool()
    val missReqReg = new Area {
      val valid = RegInit(False)
      val vpn   = Reg(UInt(20 bits))
      val write = Reg(Bool())
      val sup   = Reg(Bool())
      val is8K  = Reg(Bool())
      val token = Reg(UInt(m68k040.cache.DTranslationToken.Width bits))
      val robId = Reg(UInt(6 bits))
    }
    // Task #210 (MC68040 UM S3.3): "If the page is not write protected and the
    // modified bit of the ATC entry is clear, a table search proceeds to set the
    // modified bit in both the page descriptor in memory and in the ATC." A
    // write-hit against a resident entry whose cached M bit is still clear must
    // NOT take the fast direct-hit response path below -- it must fall through to
    // the SAME walker capture a genuine miss uses.
    //
    // This is deliberately NOT "respond immediately, refresh in the background":
    // an earlier version of this fix did exactly that and LOOKED correct in
    // isolation (the walk genuinely ran, the descriptor genuinely got queued for
    // an RMW) but silently never drained on the real full core. Root cause:
    // `UmWriteQueue.commit` marks committed only entries that are ALREADY
    // allocated at the exact cycle the commit pulse arrives (a one-cycle Flow,
    // not sticky) -- see UmWriteQueue.scala. The cold-miss path is safe by
    // construction because its response (and hence the triggering instruction's
    // OWN retirement) is held back until `walker.io.done`, the SAME cycle the
    // umq allocation happens, so the ROB cannot possibly commit that robId before
    // its entry exists. An immediate hit response breaks that ordering outright:
    // the instruction can retire (and its commit pulse can fire) many cycles
    // before the background walk even finishes, let alone allocates -- the
    // pulse arrives, finds no matching entry yet, and the later allocation is
    // permanently stuck uncommitted (never drains). Blocking here costs the
    // access one real 3-level walk (exactly the "a table search proceeds"
    // latency real hardware pays too) but is correct by the same construction
    // the existing miss path already relies on.
    val fault = permFault(tlbEntry.writeProt, tlbEntry.supervisor,
                           _req.payload.write, _req.payload.supervisor)
    val needsMRefresh = tlbHit && !fault && _req.payload.write && !tlbEntry.modified
    // Direct classes all produce a registered tagged response. A TLB miss (or a
    // write-hit needing the M-refresh table search above) instead captures the
    // existing single walker context. Accept-last ordering lets an old response
    // fire while the next direct hit is captured in the same cycle.
    when(_req.fire) {
      when(!mmuEnable) {
        rspValid             := True
        rspPayload.ppn       := _req.payload.vpn
        rspPayload.cacheMode := CacheMode.WRITETHROUGH
        rspPayload.fault     := False
        rspPayload.token     := _req.payload.token
        rspVpn               := _req.payload.vpn
      } elsewhen(ttHit) {
        rspValid             := True
        rspPayload.ppn       := _req.payload.vpn
        rspPayload.cacheMode := TtMatch.cacheMode(Mux(dtt0Hit, dtt0, dtt1))
        rspPayload.fault     := False
        rspPayload.token     := _req.payload.token
        rspVpn               := _req.payload.vpn
      } elsewhen(tlbHit && !needsMRefresh) {
        rspValid             := True
        rspPayload.ppn       := tlbEntry.ppn
        rspPayload.cacheMode := tlbEntry.cacheMode
        rspPayload.fault     := fault
        rspPayload.token     := _req.payload.token
        rspVpn               := _req.payload.vpn
      } otherwise {
        missReqReg.valid := True
        missReqReg.vpn   := tlbKey(_req.payload.vpn)
        missReqReg.write := _req.payload.write
        missReqReg.sup   := _req.payload.supervisor
        missReqReg.is8K  := is8K
        missReqReg.token := _req.payload.token
        missReqReg.robId := umAccessRobId
        missPending      := True
        walkFlushPoison  := False
        walkUmPoison     := False
      }
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
    walker.io.req.is8K    := missReqReg.is8K
    walker.io.start       := missReqReg.valid && !umQueueFull && !flushAll
    when(walker.io.start) { missReqReg.valid := False }

    // VPN a walk is servicing: latched at walk-LAUNCH (the registered-trigger cycle)
    // so the fill/result target the right VPN even if a later command changes it.
    val walkVpn = Reg(UInt(20 bits))
    val walkToken = Reg(UInt(m68k040.cache.DTranslationToken.Width bits))
    val walkRobId = Reg(UInt(6 bits))   // robId of the access that triggered the walk
    when(walker.io.start) {
      walkVpn   := missReqReg.vpn
      walkToken := missReqReg.token
      walkRobId := missReqReg.robId
    }

    // On walk completion: latch the result and (if no fault) fill the TLB.
    val fe = TlbEntry()
    fe.vpnTag     := 0   // set by the TLB fill from fillVpn
    fe.ppn        := walker.io.rsp.ppn
    fe.writeProt  := walker.io.rsp.writeProt
    fe.supervisor := walker.io.rsp.supervisor
    fe.cacheMode  := walker.io.rsp.cacheMode
    // Task #210: the leaf descriptor's M bit as of this fill (see WalkRsp.modified
    // doc) -- correctly False on a fresh cold-miss read, True on any walk the
    // write access itself triggered (the walker always sets M on a write).
    fe.modified   := walker.io.rsp.modified
    tlb.io.fillValid := False
    tlb.io.fillVpn   := walkVpn
    tlb.io.fillEntry := fe
    when(walker.io.done) {
      missPending := False
      when(!walkFlushPoison && !flushAll) {
        assert(!rspValid, "walker result collided with an occupied DTLB response")
        rspValid             := True
        rspPayload.ppn       := walker.io.rsp.ppn
        rspPayload.cacheMode := walker.io.rsp.cacheMode
        rspPayload.fault     := walker.io.rsp.fault
        rspPayload.token     := walkToken
        rspVpn               := walkVpn
      }
      when(!walker.io.rsp.fault && !walkFlushPoison && !flushAll) {
        tlb.io.fillValid := True
      }
    }

    // PFLUSHA clears the response slot and prevents a pre-flush in-flight walk from
    // refilling the just-invalidated ATC. Branch/exception flush may retain a
    // speculative resident fill; the tagged response is discarded by the LSU epoch.
    when(flushAll) {
      rspValid := False
      when(missReqReg.valid) {
        missReqReg.valid := False
        missPending      := False
      } elsewhen(missPending) {
        walkFlushPoison := True
      }
    }
    // A speculative resident translation may still fill after a backend squash,
    // but its architectural deferred U/M write belongs to the killed ROB entry and
    // must never be allocated after the one-cycle flush pulse has passed.
    when(umFlush && missPending) { walkUmPoison := True }

    // ---- deferred U/M descriptor-write queue (speculative; drained at commit) ----
    // A non-faulting walk that needs to set U (and M on a write) pushes {robId, addr,
    // newByte}; the entry drains at the triggering instruction's commit (RMW the
    // descriptor byte over this same AXI bus) and is discarded on a flush. NOT
    // performed speculatively.
    val umq = new UmWriteQueue(4)
    umQueueFull := umq.io.full
    // Task #210: a queued-but-undrained deferred descriptor write is invisible to
    // ordinary memory reads until it actually lands (commit marks it drainable;
    // the AXI RMW itself then takes further cycles) -- strictly LATER than when
    // the triggering instruction's own translation resolves. A PROGRAM-ORDER-LATER
    // access to the SAME PAGE (page-granularity, not exact-byte: cheap and only
    // ever over-blocks, never under-blocks) must not translate/proceed until that
    // write has drained, or it can observe the stale pre-update descriptor byte --
    // exactly the read-after-the-triggering-write race this task's own ATC test
    // exercises. Real 68040 hardware has no such gap because a table search is
    // simply part of the write's own (fully synchronous, non-speculative) bus
    // activity; this is this OoO core's equivalent enforcement.
    umq.io.pageQuery := _req.payload.vpn
    umqPageHazard    := umq.io.pageHazard
    umq.io.alloc.valid          := walker.io.done && walker.io.rsp.umWrite.valid &&
                                  !walker.io.rsp.fault && !walkUmPoison &&
                                  !walkFlushPoison && !flushAll
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
    // ack the queue once the write lands (b handshake). drainAck pops the entry.
    umq.io.drainAck := walkerAxi.b.valid && walkerAxi.b.ready

    // Sim-only sticky fault observation: set whenever a translation resolves with a
    // fault flagged (FLAGGED only — no exception delivery this slice). The lock-step
    // harness asserts it for the non-resident-page fault test.
    val faultSeen = RegInit(False); faultSeen.simPublic()
    // Sim-only: the faulting access's page-base VA (vpn<<12) — the lock-step harness
    // asserts the mid-EA pointer-load fault carries the POINTER-LOAD address (precision).
    val faultVa   = Reg(UInt(32 bits)) init (0); faultVa.simPublic()
    when(_rsp.fire && _rsp.payload.fault) {
      faultSeen := True
      faultVa := (rspVpn << 12).resize(32)
    }
  }
}
