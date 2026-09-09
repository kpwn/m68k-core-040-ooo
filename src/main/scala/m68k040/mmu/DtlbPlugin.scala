package m68k040.mmu

import m68k040.cache.{CacheMode, DLoadCmd, DLoadRsp, DStoreCmd, DTranslationCmd, DTranslationRsp}
import m68k040.services.{DTranslationService, DtlbWalkerDcacheClient, MmuControlService}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** D-side MMU plugin: a banked DTLB + a hardware 3-level table walker behind
  * `DTranslationService`, replacing the identity stub.
  *
  *  - `mmuEnable` (sim-pokeable AND commit-time MOVEC-writable, task #131): when LOW the plugin is an
  *    identity passthrough (ppn=vpn, no fault, always ready) UNLESS a TTR
  *    (DTT0/DTT1) matches, in which case its own cache-mode wins (WRITETHROUGH
  *    otherwise) — TTRs are gated only by their own E bit and apply
  *    independently of `mmuEnable`, matching real 68040 semantics (see the
  *    `ttHit` comment below for why this changed).
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
                 banks: Int = Tlb.DefaultBanks) extends FiberPlugin
    with DTranslationService with DtlbWalkerDcacheClient {
  var _req: Stream[DTranslationCmd] = null
  var _rsp: Stream[DTranslationRsp] = null

  override def req: Stream[DTranslationCmd] = _req
  override def rsp: Stream[DTranslationRsp] = _rsp

  // ── Table-walk D-cache client port (see `WalkerDcacheClient`) ──────────────────
  // Allocated in `setup` so `LsEuPlugin`'s own `during build` can reference them
  // regardless of plugin build order, exactly like `umAccessRobId` below.
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
    _walkLoadCmd  = Stream(DLoadCmd())
    _walkLoadRsp  = Flow(DLoadRsp())
    _walkStore    = Stream(DStoreCmd())
    _walkStoreAck = Bool()
    _walkStoreErr = Bool()
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

  val logic = during build new Area {
    val tlb    = new Tlb(entries, ways, banks)
    val walker = new TableWalker()

    // ── Table-walk traffic now goes through the D-CACHE, not a private AXI master ──
    // The walker's descriptor reads are `_walkLoadCmd`/`_walkLoadRsp`; the deferred U/M
    // descriptor writeback is `_walkStore`/`_walkStoreAck` (see the drain below). Both
    // pairs are arbitrated onto `DcacheService`'s single load/store ports by
    // `LsEuPlugin`, which resolves this plugin through `DtlbWalkerDcacheClient`.
    //
    // The arbiter-driven halves are default-idle with `allowOverride` so a standalone
    // DUT (one with no `LsEuPlugin`) still elaborates; such a DUT attaches a sim-side
    // `DcacheClientMemAgent` to these ports instead, which is why they are simPublic.
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
    //
    // 2026-09-09 CAPACITY FIX -- THE MASK HALVED THE ATC. `Tlb.bankOf(vpn)` is
    // `vpn(0)` (32 entries = 2 banks x 4 ways x 4 sets, so bank = key[0], set =
    // key[2:1], tag = key[19:3]). Forcing key[0] to a CONSTANT ZERO in 8 KB mode
    // therefore made `bankOf` constant 0: BANK 1 WAS NEVER LOOKED UP AND NEVER
    // FILLED, so the ATC held 16 of its 32 entries whenever TCR.P=1. Not a
    // correctness bug (the masked key is still injective over the meaningful VPN
    // bits, so nothing mistranslates) but a pure, silent halving of capacity -- and
    // the board runs 8 KB pages with NEITHER TTR covering main memory, so every RAM
    // and ROM access walks the tables and pays for it.
    //
    // SHIFT instead of MASK. `vpn(19 downto 1)` zero-extended to 20 bits is still
    // injective -- two VAs name the same 8 KB page exactly when they agree in
    // vpn[19:1] (vpn(0) = VA[12] is an in-page offset bit at 8 KB), so distinct
    // pages keep distinct keys -- but now bank = vpn(1), set = vpn(3:2) and tag =
    // {0, vpn[19:4]}. 19 meaningful bits over 1 bank bit + 2 set bits + 16 live tag
    // bits: all 32 entries reachable, no aliasing. The top tag bit is a constant 0
    // (8 wasted flops per TLB); the alternative -- narrowing the tag in 8 KB mode --
    // would need a mode-dependent tag width, which the array cannot have.
    def tlbKeyOf(vpn: UInt, p8K: Bool): UInt = Mux(p8K, vpn(19 downto 1).resize(20), vpn)
    def tlbKey(vpn: UInt): UInt = tlbKeyOf(vpn, is8K)

    // ...AND WHY THAT IS NOT A ONE-LINE CHANGE. `missReqReg.vpn` is NOT a TLB key:
    // it is ALSO `walker.io.req.vpn`, and `TableWalker` slices it into the three
    // table indices (root = vpn[19:13], pointer = vpn[12:6], page = vpn[5:1] at
    // 8 KB). Feeding the SHIFTED key there would index every level of every table
    // search one bit off -- silent, total mistranslation. The walk keeps the MASKED
    // form (identical to the pre-fix key: bit 0 is not read by the walker in 8 KB
    // mode, so zeroing it costs nothing) and the TLB key is re-derived at the fill.
    // `tlbKey(walkKey(v)) === tlbKey(v)` because the mask only clears the one bit
    // the shift discards.
    def walkKey(vpn: UInt): UInt = Mux(is8K, (vpn(19 downto 1) ## False).asUInt, vpn)

    // ---- DTT0/DTT1 transparent-translation match (task #194) ----
    // A hit bypasses the walker/TLB entirely: PA=VA, no fault, no page table
    // consulted.
    //
    // CORRECTED (bug found via live-hardware boot investigation, see
    // docs/BUG_video_driver_selection.md in the parent macqd700-soc repo,
    // commit 4339fa7): this used to be additionally gated with `mmuEnable &&`
    // on top of each TTR's own E bit, on the claim that mmuEnable=False was
    // "already pure identity below" so TTRs added no observable difference.
    // That claim was ARCHITECTURALLY WRONG. On real 68040 hardware DTT0/DTT1
    // (and ITT0/ITT1) are gated ONLY by their own E bit (TTR bit 15) --
    // exactly like TtMatch.hit already checks internally (MmuTypes.scala) --
    // and apply regardless of whether paged translation (TC.E / mmuEnable) is
    // on. That is the entire point of TTRs: firmware marks address ranges
    // transparent/non-cacheable BEFORE paging is enabled. The Mac ROM does
    // exactly this at boot (TC=0): DTT1 covers the VIA/SCC/DAFB MMIO window
    // cache-inhibited. With the old `mmuEnable &&` gating, a TC=0 MMIO probe
    // fell through to the unconditional `!mmuEnable` WRITETHROUGH branch
    // below instead of honoring DTT1's INHIBITED setting -- the D-cache then
    // allocated a line and issued a burst refill against an AXI-lite-only
    // slave, which SLVERRs every beat since it never expects a burst there.
    // Fixed by dropping the `mmuEnable &&` here (each TTR's own E bit is
    // still enforced inside TtMatch.hit, so this is not "always transparent
    // when TTRs are configured" -- it's "transparent exactly when a TTR's
    // own E bit + base/mask/supervisor match", the real hardware condition)
    // and by re-priority-ordering the response mux below so a TTR hit is
    // checked before the mmuEnable-off fallback, not after.
    // DTT0 has priority over DTT1 when both match (checked first).
    val vaHi8   = _req.payload.vpn(19 downto 12)   // == va[31:24] (vpn is va[31:12])
    val dtt0Hit = TtMatch.hit(dtt0, vaHi8, _req.payload.supervisor)
    val dtt1Hit = !dtt0Hit && TtMatch.hit(dtt1, vaHi8, _req.payload.supervisor)
    val ttHit   = dtt0Hit || dtt1Hit

    // ---- TLB lookup (combinational) ----
    tlb.io.lookupVpn := tlbKey(_req.payload.vpn)
    // FC2 completes the ATC tag (MC68040 UM S3.3). `_req.payload.supervisor` is the
    // ACCESS's address space -- for MOVES that is SFC/DFC bit 2, not the current
    // privilege level -- so a user-space and a supervisor-space translation of one
    // virtual page coexist instead of overwriting each other's answer.
    tlb.io.lookupSup := _req.payload.supervisor
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

    _rsp.valid   := rspValid && !flushAll
    _rsp.payload := rspPayload
    _req.ready   := !missPending && (!rspValid || _rsp.ready) && !flushAll

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
      // TTR hit is checked FIRST regardless of mmuEnable: on real hardware a
      // TTR match bypasses the walker/TLB entirely, whether or not paging is
      // on (see the comment above ttHit's definition). The mmuEnable=False
      // fallback below is now only reached when the MMU is off AND no TTR
      // matched -- identity-map with WRITETHROUGH remains the correct default
      // for that "not specially marked" case.
      when(ttHit) {
        rspValid             := True
        rspPayload.ppn       := _req.payload.vpn
        rspPayload.cacheMode := TtMatch.cacheMode(Mux(dtt0Hit, dtt0, dtt1))
        rspPayload.fault     := False
        rspPayload.token     := _req.payload.token
        rspVpn               := _req.payload.vpn
      } elsewhen(!mmuEnable) {
        rspValid             := True
        rspPayload.ppn       := _req.payload.vpn
        rspPayload.cacheMode := CacheMode.WRITETHROUGH
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
        missReqReg.vpn   := walkKey(_req.payload.vpn)
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

    // ── sim-only observation taps (no RTL consumer; pruned from every netlist) ──
    // Added while chasing a silent MISTRANSLATION found by WalkerExcEntryWedgeSpec: a
    // core load issued AXI to a physical address whose PPN matched no descriptor in
    // memory, with no fault reported anywhere. These make it possible to say WHICH of
    // the four response classes above produced a given PPN instead of inferring it.
    rspValid.simPublic(); rspPayload.simPublic()
    val dbgFillFire = Bool(); dbgFillFire := False; dbgFillFire.simPublic()
    val dbgTtHit         = ttHit;         dbgTtHit.simPublic()
    val dbgTlbHit        = tlbHit;        dbgTlbHit.simPublic()
    val dbgNeedsMRefresh = needsMRefresh; dbgNeedsMRefresh.simPublic()
    val dbgTlbEntryPpn   = tlbEntry.ppn;  dbgTlbEntryPpn.simPublic()
    val dbgReqFire       = _req.fire;     dbgReqFire.simPublic()
    val dbgReqVpn        = _req.payload.vpn; dbgReqVpn.simPublic()
    val dbgMmuEnable     = mmuEnable;     dbgMmuEnable.simPublic()

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
    // The page size this walk was LAUNCHED under (see the fill below).
    val walkIs8K = Reg(Bool())
    // ...and the address space it was launched in (the ATC's FC2 tag bit).
    val walkSup  = Reg(Bool())
    val walkToken = Reg(UInt(m68k040.cache.DTranslationToken.Width bits))
    val walkRobId = Reg(UInt(6 bits))   // robId of the access that triggered the walk
    when(walker.io.start) {
      walkVpn   := missReqReg.vpn
      walkIs8K  := missReqReg.is8K
      walkSup   := missReqReg.sup
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
    // `walkVpn` is the WALK key (see `walkKey`); the array is indexed by the TLB key.
    // Keyed off the walk's OWN latched page size, not the live TCR.P, so a TCR
    // rewrite while a walk is in flight cannot file the result under a key the
    // lookup that requested it would never form -- the pre-fix code had this
    // property for free (the key was computed once, at capture) and it is kept.
    tlb.io.fillVpn   := tlbKeyOf(walkVpn, walkIs8K)
    // Filled under the address space the WALK ran in (it chose URP vs SRP with this
    // very bit), latched at launch alongside the VPN for the same reason.
    tlb.io.fillSup   := walkSup
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
      // Task (C1 fix): `walkUmPoison` means the deferred U/M descriptor WRITE for
      // this walk was correctly suppressed (see the umq.io.alloc.valid gate below)
      // because a backend flush landed on this walk while it was still in flight.
      // `fe.modified` above is `walker.io.rsp.modified` -- the walker's own computed
      // "M should now read as" value, which is True for ANY write-triggered walk
      // regardless of whether its memory write-back actually happened. Filling the
      // TLB with that value here, unconditionally, would cache "M already set" for
      // this entry while the real descriptor in memory still has M=0 (the write-back
      // that would have set it was correctly suppressed by walkUmPoison) -- the next
      // write to this page then takes the fast `tlbHit && !needsMRefresh` path and
      // never re-walks, permanently losing the M update for the life of this ATC
      // entry. The identical shape silently loses U on an ordinary cold-miss walk
      // that gets flushed: TlbEntry has no separate U field (Tlb.scala) -- once a
      // resident fill happens at all, no future access ever re-triggers a walk for
      // U's sake, so a poisoned cold miss's U-write, once suppressed, can never be
      // recovered by any consumer of this ATC entry.
      //
      // The correct fix is to gate the fill itself, not just narrow `fe.modified`'s
      // value with `&& !walkUmPoison`: that narrower alternative fixes the M-loss
      // case (a poisoned write walk would then cache modified=false, correctly
      // forcing a re-walk on the next write-hit's M-refresh check) but does NOTHING
      // for the U-loss case on a poisoned READ walk, where `walker.io.rsp.modified`
      // is already False and always was -- there is no "wrong" modified value to
      // correct there, yet the TLB would still get filled and the page's real U
      // bit would still be permanently stuck at 0 in memory with no other trigger
      // ever able to re-walk and repair it. Suppressing the fill entirely instead
      // forces a genuine, clean re-walk on the very next access to this page --
      // exactly the same real 3-level table search a first-time cold miss pays --
      // and that re-walk (assuming no second flush collision) will correctly queue
      // and, once its own instruction commits, drain BOTH U and M.
      when(!walker.io.rsp.fault && !walkFlushPoison && !flushAll && !walkUmPoison) {
        tlb.io.fillValid := True
      }
      // sim-only taps for the walker->TLB fill (see the `simPublic` block above).
      dbgFillFire := tlb.io.fillValid
    }

    // sim-only taps for the fill payload and the walk-poison state.
    val dbgFillVpn         = tlb.io.fillVpn;      dbgFillVpn.simPublic()
    val dbgFillPpn         = tlb.io.fillEntry.ppn; dbgFillPpn.simPublic()
    val dbgWalkerDone      = walker.io.done;      dbgWalkerDone.simPublic()
    val dbgWalkerRspPpn    = walker.io.rsp.ppn;   dbgWalkerRspPpn.simPublic()
    val dbgWalkerRspFault  = walker.io.rsp.fault; dbgWalkerRspFault.simPublic()
    val dbgWalkFlushPoison = walkFlushPoison;     dbgWalkFlushPoison.simPublic()
    val dbgWalkUmPoison    = walkUmPoison;        dbgWalkUmPoison.simPublic()
    val dbgFlushAll        = flushAll;            dbgFlushAll.simPublic()
    val dbgWalkVpn         = walkVpn;             dbgWalkVpn.simPublic()

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
    // (2026-09-09: the task #210 same-page interlock that used to be driven from here
    // is GONE -- it compared this VIRTUAL page number against the queue's PHYSICAL
    // descriptor addresses, so it was dead under any non-identity map and could not be
    // made to fire correctly without deadlocking. UmWriteQueue.scala carries the full
    // argument and the monotonicity tripwire that replaces it.)
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

    // ---- U/M drain: single-byte RMW as ONE D-cache store ----
    // The descriptor lives at a physical byte address; write just that byte (16-byte
    // line-aligned beat with a one-hot strobe at addr[3:0]). `DStoreCmd.useStrb` is
    // exactly this shape — the SQ already uses it to drain a split store whose byte
    // count is not a clean 1/2/4 `Size` — so the whole AW/W/B handshake FSM this
    // replaces collapses to a single elastic command plus its terminal ack.
    //
    // WHY THIS IS THE CORRECTNESS HALF OF THE CHANGE. The old path wrote the descriptor
    // byte straight to physical memory. A copyback L1D holding that same descriptor line
    // DIRTY (an ordinary supervisor `move.l` to a page-table entry) later evicts it and
    // writes the whole line back INCLUDING the stale U/M byte, silently discarding the
    // update. Losing an M means a dirty page is later evicted as clean — silent data
    // loss. Routing the write through the cache that owns the line closes it.
    //
    // Single-outstanding: `drainArmed` holds one command presented until it is accepted,
    // and the queue holds the entry until `drainAck` (the terminal `storeAck`).
    val drainArmed  = RegInit(False)
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
    _walkStore.payload.data       := B(0, 32 bits)   // don't-care under useStrb
    _walkStore.payload.size       := m68k040.isa.Size.LONG   // ditto
    _walkStore.payload.useStrb    := True
    _walkStore.payload.strb       := drainStrbReg
    _walkStore.payload.lineData   := drainBeatReg
    // Overwritten by the arbiter with the SAME fixed policy expression the walker's
    // reads use (`CACR.DE ? WRITETHROUGH : INHIBITED`). A per-half cache mode is
    // forbidden: the read and the write halves of one table search must agree, or the
    // read can hit an array copy the write never updated.
    _walkStore.payload.cacheMode  := CacheMode.WRITETHROUGH
    // NOT on the SQ's at-head precise path: this store belongs to no ROB entry's
    // precise fault reporting, and marking it precise would route a bus error into the
    // SQ's `sqFaultCompletion` against an unrelated robId.
    _walkStore.payload.precise    := False
    when(_walkStore.fire) { drainArmed := False; drainAckWait := True }
    when(drainAckWait && _walkStoreAck) { drainAckWait := False }
    // ack the queue once the store lands (the terminal `storeAck`). drainAck pops the
    // entry. `_walkStoreErr` is deliberately not consulted: the pre-existing AXI path
    // did not inspect `bresp` either, and inventing a fault report here would attribute
    // a bus error to whatever instruction happens to be retiring.
    umq.io.drainAck := drainAckWait && _walkStoreAck

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
