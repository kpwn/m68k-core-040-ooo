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
  *    DTLB). No write-protect fault on fetch (a fetch is never a write).
  *
  * @param captureGateOnFlush THE NEGATIVE CONTROL FOR THE MISS-CAPTURE RACE, and the
  *   only reason this parameter exists. It defaults to `true` and NOTHING in
  *   `src/main` ever passes `false`, so it is a Scala compile-time constant that
  *   folds away entirely -- there is no mux, no flop and no netlist difference.
  *
  *   Setting it `false` restores the pre-2026-09-18 `needWalk`, i.e. deletes the
  *   `&& !atcFlush` term whose absence let an MMU control write land in the same
  *   cycle as a miss capture and leave a walk carrying no poison and the PRE-write
  *   TCR.P. `ItlbCaptureRaceSpec` elaborates the DUT BOTH ways and asserts the
  *   defect is present with `false` and absent with `true`.
  *
  *   WHY A PARAMETER RATHER THAN EDITING THE FILE FOR ONE RUN: a negative control
  *   that lives in a shell command is run once and then gone, and the guarantee it
  *   established quietly stops being checked. This one is part of the suite, so a
  *   future change that makes the gate ineffective for some OTHER reason -- not by
  *   deleting the term -- fails the `false` arm too, which a textual revert could
  *   never catch.
  */
class ItlbPlugin(entries: Int = Tlb.DefaultEntries,
                 ways: Int = Tlb.DefaultWays,
                 banks: Int = Tlb.DefaultBanks,
                 captureGateOnFlush: Boolean = true) extends FiberPlugin
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
  var _walkCmodePolicy: CacheMode.C = null
  override def walkLoadCmd:  Stream[DLoadCmd]  = _walkLoadCmd
  override def walkLoadRsp:  Flow[DLoadRsp]    = _walkLoadRsp
  override def walkStore:    Stream[DStoreCmd] = _walkStore
  override def walkStoreAck: Bool = _walkStoreAck
  override def walkStoreErr: Bool = _walkStoreErr
  override def walkCmodePolicy: CacheMode.C = _walkCmodePolicy

  during setup {
    _req = TranslationReq()
    _rsp = TranslationRsp()
    // U-write queue hooks (sibling-driven; default-idle in logic so a standalone DUT
    // that doesn't wire them still elaborates). Fetch sets U only.
    umCommitValid = Bool()
    umCommitId    = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
    umCommitBValid = Bool()
    umCommitBId    = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
    umFlush       = Bool()
    flushAll      = Bool()
    _walkLoadCmd  = Stream(DLoadCmd())
    _walkLoadRsp  = Flow(DLoadRsp())
    _walkStore    = Stream(DStoreCmd())
    _walkStoreAck = Bool()
    _walkStoreErr = Bool()
    _walkCmodePolicy = CacheMode()
  }

  // U deferred-write queue hooks (driven by the LS-cluster wiring, mirroring the DTLB).
  // NOTE there is deliberately NO `umAccessRobId` here, unlike the DTLB: an instruction
  // fetch is translated before rename, so the walk it triggers has no owning robId to
  // be tagged with. The I-side entry is born committed instead -- see
  // `UmWriteAlloc.preCommitted`. The commit/flush hooks below are still real: `umFlush`
  // discards a walk poisoned mid-flight, which is C6's guarantee and is unchanged.
  var umCommitValid: Bool = null
  var umCommitId:    UInt = null
  var umCommitBValid: Bool = null   // retire slot 1 (dual-retire) — see UmWriteQueue.commitB
  var umCommitBId:    UInt = null
  var umFlush:       Bool = null
  // PFLUSHA: flush ALL TLB entries + the walk-result latch (task #136).
  var flushAll:      Bool = null

  val logic = during build new Area {
    val tlb    = new Tlb(entries, ways, banks)
    // ── Multi-hot evidence plumbing (2026-09-17) ───────────────────────────────
    // `dbgMultiHotClear` is a plugin-level default-False wire so DebugCtrlPlugin can
    // override it from its write decode, and so a DUT that wires nothing still
    // elaborates with a latch that only ever accumulates. Never left undriven: an
    // un-poked testbench-driven input is not guaranteed 0 in this project's sim
    // (documented gotcha), and a randomly-asserted clear would silently erase the
    // evidence this register exists to preserve.
    val dbgMultiHotClear = Bool(); dbgMultiHotClear.allowOverride; dbgMultiHotClear := False
    tlb.io.dbgMultiHotClear := dbgMultiHotClear
    val walker = new TableWalker()

    // ── Table-walk traffic now goes through the D-CACHE, not a private AXI master ──
    // Mirrors `DtlbPlugin`'s identical block; see its comment for the full rationale.
    // The arbiter-driven halves are default-idle with `allowOverride` so a standalone
    // DUT still elaborates and can attach a sim-side `DcacheClientMemAgent`.
    _walkLoadCmd.valid   := walker.io.loadCmd.valid
    _walkLoadCmd.payload := walker.io.loadCmd.payload
    // Driven by the arbiter; WRITETHROUGH is the inert standalone-DUT default, the
    // same value the arbiter used to stamp unconditionally.
    _walkCmodePolicy.allowOverride; _walkCmodePolicy := CacheMode.WRITETHROUGH
    // The WALKER's own descriptor reads take the LIVE policy: a read does not
    // mutate, so nothing downstream depends on two reads agreeing.
    //
    // `allowOverride` because `_walkLoadCmd.payload` was just assigned as a WHOLE
    // BUNDLE above: SpinalHDL's no-latch/no-override check rejects an unconditional
    // field override of a just-assigned bundle as a complete assignment overlap (the
    // same gotcha `LsEuPlugin`'s walker legs document, which is why THEY assign field
    // by field). The `when(drainNeedRead)` override further down does not need it --
    // a CONDITIONAL override of an unconditionally assigned signal is ordinary
    // last-assignment-wins.
    _walkLoadCmd.payload.cacheMode.allowOverride
    _walkLoadCmd.payload.cacheMode := _walkCmodePolicy
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
    umCommitValid.allowOverride; umCommitValid := False
    umCommitId.allowOverride;    umCommitId    := U(0, m68k040.Global.ROB_ID_W_DEFAULT bits)
    umCommitBValid.allowOverride; umCommitBValid := False
    umCommitBId.allowOverride;    umCommitBId    := U(0, m68k040.Global.ROB_ID_W_DEFAULT bits)
    umFlush.allowOverride;       umFlush       := False
    flushAll.allowOverride;      flushAll      := False; flushAll.simPublic()

    // The ONE shared 68040 MMU control (read, not owned).
    val ctrl = host[MmuControlService]
    val mmuEnable = ctrl.mmuEnable
    val is8K      = ctrl.pageSize8K

    // ── TCR.P RE-KEYING: the ATC must be INVALIDATED when the page size changes ──
    // `tlbKey` below derives the ATC array key from the LIVE TCR.P, while the key a
    // resident entry was FILED under came from the TCR.P in force at ITS fill
    // (`tlbKeyOf(walkVpn, walkIs8K)`). Nothing in the tag records which page size
    // that was, so a TCR.P change silently RE-INTERPRETS every resident key: an
    // entry filled at 4 KB under key `v` becomes a CLEAN ONE-HOT match for the 8 KB
    // lookup of VPN 2v (VA*2) -- an architecturally different page answered with the
    // wrong PPN and NO fault. Because the hit is one-hot, the duplicate-fill
    // tripwire (`Tlb.dbgHitCount`) and the multi-hot fail-safe cannot see it.
    // Reproduced in both directions by `TlbPageSizeRekeySpec` (walker reads = 0,
    // hitVec popcount = 1, fault = false).
    //
    // The MC68040 UM requires software to PFLUSH after writing TC and the Q700 ROM
    // does exactly that -- but the hardware must not answer confidently wrong when
    // software omits it, and this implementation's aliasing is far more violent than
    // real silicon's (which masks A12 in the comparison, so a stale entry can only
    // mistranslate WITHIN the same 8 KB region; the shifted key aliases VA to VA*2).
    // Invalidate the whole ATC in the SAME cycle the key changes, driven from
    // MmuControl's commit-time TC write port, so the array is already empty on the
    // first cycle the new key is in force and there is no window at all.
    //
    // COST: NOT on the lookup path. `atcFlush` ORs one term into `invalidateAll`
    // (the valid-bit clear enable) and into `_req.ready` -- both already carry
    // `flushAll` from PFLUSHA -- and the term itself is one XNOR off an existing
    // commit-time signal. No tag bit, no comparator widening, no extra way-mux
    // level, zero added depth on the hit cone.
    val pageSizeRekey = ctrl.setPageSize.valid && (ctrl.setPageSize.payload =/= ctrl.pageSize8K)
    // ── FOURTH INSTANCE OF THE SAME FAMILY (2026-09-17): TC.E ────────────────────
    // The response mux below selects its arm at `_req.fire` -- `when(ttHit) ...
    // elsewhen(!mmuEnable) ... elsewhen(tlbHit)` -- and REGISTERS the result. The
    // chosen arm therefore records the `mmuEnable` of the CAPTURE cycle, while the
    // consumer receives the payload one or more cycles later. A MOVEC to TC that
    // clears TC.E in between leaves a TRANSLATED PA being delivered to an access that,
    // by then, should be identity-mapped -- and that access is necessarily YOUNGER
    // than the MOVEC, because MOVEC retires at the ROB head, so every older access has
    // already completed.
    //
    // Masked today by the same incidental chain as the root case: the sysOp retire
    // pulses a redirect that squashes younger ops. Nothing names it, nothing asserts
    // it. `pageSizeRekey` already kills a pending response on a TC.P change (it feeds
    // `atcFlush`, and the flush block clears `rspValid`); an E-only change did not.
    //
    // Deliberately a SEPARATE term from `pageSizeRekey` rather than folded into it:
    // `pageSizeRekey` is what `OFF_MMU_REKEY_COUNT` counts, and that CSR means "TCR.P
    // changed". Widening it would silently change what a hardware reading reports.
    //
    // COST: one XNOR into the existing OR. Same net, no new depth. Over-invalidating
    // on an E toggle is free in practice -- the ROM toggles TC.E a handful of times in
    // the whole boot.
    val mmuEnableChange = ctrl.setEnable.valid && (ctrl.setEnable.payload =/= ctrl.mmuEnable)
    mmuEnableChange.simPublic()
    // ── "WE SHOULD NOT DEPEND ON PFLUSHA" (owner directive, 2026-09-17) ──────────
    // A ROOT WRITE INVALIDATES THE ATC. `rootChanged` (above) only stops a walk that
    // is ALREADY IN FLIGHT from installing a result from the old tree; it does nothing
    // about entries that are ALREADY RESIDENT. On real 68040 those survive a MOVEC to
    // SRP/URP and software is required to PFLUSH. Under the owner's rule the hardware
    // must not mistranslate when software omits that flush, so the array is emptied on
    // the write instead.
    //
    // THIS IS REACHABLE IN CONFORMING SOFTWARE TOO, which is the stronger argument.
    // 7.0.1's MMU restore at image offset 0x27a4bc does:
    //     movec %d0,%tc ; movec %d0,%urp ; movec %d0,%srp ; <four TTR writes> ; pflusha
    // The roots change at 0x27a4c4/c8 and the PFLUSHA is eight instructions later at
    // 0x27a4ee. Every fetch and every data access in that window is translated with the
    // NEW roots installed and the OLD entries still resident -- a window the program
    // cannot close, because the architecture's contract is "flush AFTER". Flushing at
    // the write closes it.
    //
    // ON `valid` RATHER THAN A VALUE COMPARE: deliberately. Two extra OR inputs on a
    // net that already exists, versus two 32-bit comparators; and a root rewritten to
    // its own current value is so rare that spending area to avoid a harmless flush
    // would be the wrong trade. Over-invalidation costs re-walks, never correctness.
    //
    // CAN THIS BREAK CONFORMING SOFTWARE? No. A conforming program writes the root and
    // then PFLUSHes; it now gets two flushes instead of one, which is indistinguishable
    // except in timing. A program could only notice the difference by DEPENDING on
    // stale entries surviving a root write, and the architecture explicitly does not
    // permit that dependence -- there is no conforming program this can break.
    val rootWrite     = ctrl.setSrp.valid || ctrl.setUrp.valid
    // A TTR WRITE WITHDRAWS ANY PENDING RESPONSE (owner rule, 2026-09-17).
    //
    // TTRs do NOT leave stale ATC entries -- a transparent hit is checked BEFORE the
    // array and never fills it, so covering a region cannot be shadowed by an old
    // entry and uncovering one cannot expose an entry that was never created. The
    // hazard is the REGISTERED RESPONSE, the same shape as the TC.E case: the response
    // mux picks its arm at `_req.fire` and latches `{ppn, cacheMode}`. A TTR write
    // before that payload is consumed leaves either
    //   * a STALE CACHE MODE -- an access marked cacheable when the new TTR says
    //     inhibited. That is the exact shape of the DAFB MMIO bug documented at
    //     `ttHit` above: a burst refill issued against an AXI-lite-only slave, which
    //     SLVERRs every beat; or
    //   * a STALE ARM -- "TTR hit, PA = VA" for a region the new TTR no longer covers,
    //     which should have gone to the tables. A real mistranslation.
    //
    // Only THIS side's pair is watched: a D-side response's cache mode comes from
    // DTT0/DTT1 and an I-side's from ITT0/ITT1, so gating on the other pair would only
    // add flushes that cannot fix anything.
    val ttrWrite      = ctrl.setItt0.valid || ctrl.setItt1.valid
    val atcFlush      = flushAll || pageSizeRekey || mmuEnableChange || rootWrite || ttrWrite
    // ── ROOT CHANGED MID-WALK: the THIRD member of a family, made structural ──────
    // `walker.io.req.rootPtr` is read LIVE at walk LAUNCH (`Mux(missReqReg.sup, srp,
    // urp)`) and latched into the walker's `reqReg` there, so a walk carries the root
    // it started under. If software then writes SRP/URP -- MOVEC, control register
    // 0x806/0x807 -- while that walk is still reading descriptors, the walk completes
    // against a tree that is NO LONGER INSTALLED and installs the result in the ATC.
    //
    // Today that is masked INCIDENTALLY: a sysOp retire pulses a redirect, the redirect
    // raises `doFlush`, `doFlush` drives `umFlush`, and `umFlush` sets `walkUmPoison`
    // which happens to gate `tlb.io.fillValid`. Nothing asserts that chain, nothing
    // names it, and an incidental mask that nobody asserted is EXACTLY how the I-side
    // `walkFlushPoison` gap survived: its absence was masked the same way until a test
    // put a PFLUSHA in the window and watched a pre-flush translation get installed.
    //
    // THE PATTERN, which is why this is worth naming: the walk LATCHES some piece of
    // MMU configuration at launch and a consumer RE-DERIVES it live. Three defects in
    // this family have now been found within a day -- TCR.P re-keying the ATC key, the
    // ITLB's missing walk-flush poison, and this. Anywhere else configuration is
    // latched at miss time and re-read live is a candidate for a fourth.
    //
    // COST: one flop and an OR, off the lookup path. Self-healing exactly like
    // `walkFlushPoison`: suppressing the fill costs one re-walk under the NEW root,
    // which is the answer the access should have had.
    // NOTE (2026-09-17): now largely SUBSUMED by `rootWrite` feeding `atcFlush`
    // below, which also poisons an in-flight walk via `flushPoisonArm`. Kept --
    // one flop -- because it names the property directly and holds even if the
    // flush term is ever narrowed; it is a second line, not dead code.
    val rootChanged = RegInit(False)
    rootChanged.simPublic()
    when(walker.io.start) { rootChanged := False }
    when(ctrl.setSrp.valid || ctrl.setUrp.valid) { rootChanged := True }
    pageSizeRekey.simPublic()
    val urp       = ctrl.urp
    val srp       = ctrl.srp
    val itt0      = ctrl.itt0
    val itt1      = ctrl.itt1

    // Task #195 (mirrors DtlbPlugin's identical treatment): VA[12] is part of the
    // in-page offset in 8K mode, not the page number, so it must be excluded from
    // the TLB tag/index compare — two fetches differing only in VA[12] address the
    // same 8K page.
    //
    // 2026-09-09 CAPACITY FIX -- the I-side twin of DtlbPlugin's identical change;
    // read that file's block for the full derivation. Short form: `Tlb.bankOf` is
    // `vpn(0)`, so a key with bit 0 MASKED to zero made bank 1 unreachable and the
    // ITLB held 16 of its 32 entries whenever TCR.P=1. SHIFTING instead keeps the
    // key injective over the 19 meaningful VPN bits (two VAs name the same 8 KB page
    // exactly when they agree in vpn[19:1]) and gives bank = vpn(1), set = vpn(3:2).
    def tlbKeyOf(vpn: UInt, p8K: Bool): UInt = Mux(p8K, vpn(19 downto 1).resize(20), vpn)
    def tlbKey(vpn: UInt): UInt = tlbKeyOf(vpn, is8K)

    // The WALK key -- what `TableWalker` slices into root/pointer/page indices, and
    // what `latchVpn` holds. It must stay the MASKED form: the shifted key would
    // index every table level one bit off. See DtlbPlugin's `walkKey` comment.
    def walkKey(vpn: UInt): UInt = Mux(is8K, (vpn(19 downto 1) ## False).asUInt, vpn)

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
    // FC2 completes the ATC tag -- see DtlbPlugin's identical wiring. An instruction
    // fetch's address space is simply the current privilege level (there is no I-side
    // MOVES), but the bit must still be tagged: a supervisor fetch and a user fetch of
    // one virtual page are different translations whenever URP =/= SRP.
    tlb.io.lookupSup := _req.supervisor
    tlb.io.invalidateAll := atcFlush
    val tlbHit   = tlb.io.hit
    val tlbEntry = tlb.io.hitEntry

    // ---- result latch (serves the cycle(s) around a walk completion + faults) ----
    val latchValid  = RegInit(False)
    val latchVpn    = Reg(UInt(20 bits))
    val latchPpn    = Reg(UInt(20 bits))
    val latchSup    = Reg(Bool())   // the PAGE's supervisor-only protection attribute
    // ...and the ADDRESS SPACE (FC2) the latched walk ran in -- the latch is a
    // one-entry result cache in front of the ATC, so it needs the same tag the ATC
    // itself now carries or it re-introduces the very aliasing `Tlb.tagSup` closes.
    val latchFcSup  = Reg(Bool())
    val latchCmode  = Reg(CacheMode())
    val latchFault  = Reg(Bool())

    // ---- walker control. Fetch is always a READ (write=False). ----
    // Task #195: `latchVpn` was filled from a masked (tlbKey'd) walkVpn, so the live
    // side of this compare must be masked the SAME way, or two fetches to the same
    // 8K page differing only in VA[12] would spuriously miss the just-filled latch.
    val latchMatch = latchValid && (latchVpn === walkKey(_req.vpn)) &&
                     (latchFcSup === _req.supervisor)
    // ── FIFTH INSTANCE OF THE SAME FAMILY (2026-09-18): THE CAPTURE CYCLE ITSELF ──
    // `!atcFlush` is the I-side equivalent of the D side's `_req.ready := ... &&
    // !atcFlush`, and its absence was the one asymmetry left between the two plugins.
    //
    // THE HOLE. Everything that poisons an I-side walk keys off `missPending`, and
    // `missPending` is a REGISTER written by the capture arm below. On the cycle the
    // capture happens, `flushPoisonArm = atcFlush && missPending` reads the OLD (False)
    // `missPending`, so it does not arm -- while the capture arm itself writes
    // `walkFlushPoison := False`. A walk captured in the SAME cycle as an MMU control
    // write is therefore the one walk in this plugin that carries NO poison at all.
    //
    // WHY THAT IS A MISTRANSLATION AND NOT JUST A WASTED WALK. `missReqReg.is8K := is8K`
    // and `walkKey(_req.vpn)` both read the LIVE TCR.P, which on the write cycle is
    // still the PRE-write value, whereas the array key every later lookup forms
    // (`tlbKey`) is derived from the POST-write one. The walk then slices the tables
    // under the old page size and the fill files the result under the old key form --
    // which is exactly the TCR.P re-keying that `pageSizeRekey`/`atcFlush` exists to
    // prevent, reopened through a one-cycle window. A 4 KB-keyed entry for VPN v is a
    // clean ONE-HOT match for the 8 KB lookup of VPN 2v: wrong PPN, no fault, and
    // invisible to both `Tlb.dbgHitCount` and the multi-hot fail-safe.
    //
    // IT IS NOT MASKED BY THE REDIRECT. The sysOp sequencer writes the MMU control
    // registers in `S_APPLY` and only pulses `redirectValid` in `S_REDIR`, the cycle
    // AFTER -- so `doFlush` is low on the write cycle -- and the front end does not stop
    // for `excActive` anyway: `FetchAlignPlugin.ic.cmd.valid` has no `excActive` term
    // (that file's own comment: "the frontend free-runs for the WHOLE maintenance
    // walk"). The window is genuinely open. Measured by `ItlbCaptureRaceSpec`.
    //
    // GATE THE CAPTURE, DO NOT POISON THE WALK. Suppressing the capture costs one
    // cycle on a fetch that is already stalled (`_rsp.ready` is False in the miss arm)
    // and the very next cycle re-derives every term from the POST-write configuration,
    // which is the answer the fetch should have had. Poisoning instead would still run
    // a useless three-level walk. `atcFlush` is a one-cycle pulse on every one of its
    // terms, so this can never stall the front end for more than that cycle.
    //
    // COST: one input to an AND that already has six, in parallel with the deep
    // `tlbHit` cone that sets this expression's delay. No new level on the hit path.
    val needWalk   = mmuEnable && _req.valid && !tlbHit && !latchMatch && !ttHit &&
                     !walker.io.busy && !walker.io.done &&
                     (if (captureGateOnFlush) !atcFlush else True)

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
    // PFLUSHA-during-walk poison -- the I-side mirror of `DtlbPlugin.walkFlushPoison`,
    // which this plugin was missing. `flushAll`/`atcFlush` is a ONE-CYCLE pulse, so
    // gating only `tlb.io.fillValid` on it covers only the exact-same-cycle collision;
    // a walk that was ALREADY IN FLIGHT when the pulse passed still completes several
    // cycles later and installs its result into BOTH the ATC array and the sticky
    // one-entry `latchValid` result cache.
    //
    // That is the Q700 ROM's `_SwapMMUMode` shape verbatim (0x40803f4e):
    //     movec %a0,%srp     <- the page-table ROOT changes
    //     movec %d1,%tc
    //     pflusha            <- one-cycle flushAll
    //     move.w (%sp)+,%sr ; rts
    // The front end runs far ahead of retirement, so an I-side walk launched under
    // the OLD root can still be reading descriptors when the PFLUSHA retires; its
    // result then answers later fetches at a correct PC with a physical page out of
    // the PREVIOUS address map -- one-hot, no fault, invisible to the multi-hot
    // tripwire. Measured by `ItlbFlushPoisonSpec` (walk straddles the flush: 1 AR
    // before, 3 after; the next fetch is served ppn 0x33333 from the pre-flush tree
    // with ZERO re-walk reads, where the post-swap tree says 0x44444).
    val walkFlushPoison = RegInit(False)
    missPending.simPublic(); walkFlushPoison.simPublic()
    missReqReg.valid := False
    when(needWalk && !missReqReg.valid && !umQueueFull) {
      missReqReg.valid := True
      walkFlushPoison  := False
      missReqReg.vpn   := walkKey(_req.vpn)
      missReqReg.sup   := _req.supervisor
      missReqReg.is8K  := is8K
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
    // The page size this walk was LAUNCHED under (see the fill below).
    val walkIs8K = Reg(Bool())
    // ...and the address space it was launched in (the ATC's FC2 tag bit).
    val walkSup  = Reg(Bool())
    when(missReqReg.valid) {
      walkVpn   := missReqReg.vpn
      walkIs8K  := missReqReg.is8K
      walkSup   := missReqReg.sup
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
    // `walkVpn` is the WALK key (see `walkKey`); the array is indexed by the TLB key.
    // Keyed off the walk's OWN latched page size, not the live TCR.P, so a TCR
    // rewrite while a walk is in flight cannot file the result under a key the
    // lookup that requested it would never form -- the pre-fix code had this
    // property for free (the key was computed once, at capture) and it is kept.
    tlb.io.fillVpn   := tlbKeyOf(walkVpn, walkIs8K)
    tlb.io.fillSup   := walkSup
    tlb.io.fillEntry := fe
    when(walker.io.done) {
      missPending := False
      // A PFLUSHA-poisoned walk must not seed the sticky latch AT ALL (unlike the
      // `umFlush` case just below, where the already-squashed triggering access is
      // still given its one response and the latch is expired a cycle later). PFLUSHA
      // does NOT squash the pipeline, so the fetch that triggered this walk is still
      // live -- handing it the pre-flush translation would be the very mistranslation
      // this poison exists to prevent. Withholding the response is safe and
      // self-healing: `needWalk` re-fires the cycle after `walker.io.busy` drops and
      // re-walks under the NEW root, exactly as DtlbPlugin's `walkFlushPoison` arm
      // withholds `rspValid` and lets the LSU re-request.
      latchValid := !walkFlushPoison
      latchVpn   := walkVpn
      latchPpn   := walker.io.rsp.ppn
      latchSup   := walker.io.rsp.supervisor
      latchFcSup := walkSup
      latchCmode := walker.io.rsp.cacheMode
      latchFault := walker.io.rsp.fault
      // C6/C1-mirror fix: a poisoned walk (backend flush landed while it was still
      // in flight) must not cache its result into the ATC either -- see the long
      // comment at `walkUmPoison`'s declaration above. `!atcFlush` covers the
      // exact-same-cycle PFLUSHA/done collision and `!walkFlushPoison` (2026-09-17)
      // covers the far larger case the D side already covered: a PFLUSHA that landed
      // any number of cycles BEFORE this completion. See `walkFlushPoison`'s
      // declaration for the `_SwapMMUMode` sequence that reaches it.
      when(!walker.io.rsp.fault && !walkUmPoison && !walkFlushPoison && !atcFlush &&
           !rootChanged) {
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
    // The flush-poison ARM condition, named ONCE so the debug probe that counts it
    // (`OFF_MMU_IPOISON_COUNT`) cannot drift from the behaviour it reports.

    val flushPoisonArm = atcFlush && missPending
    flushPoisonArm.simPublic()
    when(atcFlush) {
      latchValid := False
    }
    // ...and mark any walk that is ALREADY in flight, so its late completion cannot
    // refill the array/latch it was just cleared out of (DtlbPlugin's identical arm).
    when(flushPoisonArm) { walkFlushPoison := True }

    // ---- deferred U descriptor-write queue (U-only on fetch; born committed) ----
    // Clean fetch walks have no ROB owner; their precommitted lifetime below is
    // unchanged by widening the shared queue's ordinary retirement interface.
    val retirement = host.get[m68k040.services.RobRetirementService]
    val umq = new UmWriteQueue(4, retireWidth = retirement.map(_.retiredRobIds.length).getOrElse(2))
    retirement.foreach(r => for (lane <- 2 until r.retiredRobIds.length)
      umq.io.commitExtra(lane - 2) << r.retiredRobIds(lane))
    umq.io.full.simPublic()   // sim-only: a full queue withholds the walker's admission credit
    umq.io.drain.valid.simPublic()   // sim-only: is there an entry waiting to drain?
    umQueueFull := umq.io.full
    // C6 fix: previously UNCONDITIONAL -- no poison of any kind, not even a
    // `flushAll` (PFLUSHA) gate (DtlbPlugin's equivalent line gates on
    // `!walkUmPoison && !walkFlushPoison && !flushAll`). A walk that completed
    // after a flush landed on it mid-walk would still allocate a real deferred
    // U-write entry tagged with an already-squashed robId -- see the long comment
    // at `walkUmPoison`'s declaration above for the full spurious-write mechanism
    // this was producing.
    umq.io.alloc.valid          := walker.io.done && walker.io.rsp.umWrite.valid &&
                                  !walker.io.rsp.fault && !walkUmPoison &&
                                  !walkFlushPoison && !atcFlush
    // NO OWNER EXISTS. An instruction fetch is translated before rename, so there is
    // no robId to tag this with; the field is inert for a pre-committed entry (it is
    // never compared, because the entry is already committed) and is driven to a
    // constant only because the bundle has the field for the D side's sake.
    //
    // WHY BORN-COMMITTED IS A REPAIR AND NOT A SEMANTIC CHANGE: an I-fetch IS
    // speculative, but U is MONOTONIC and ADVISORY, and today's code ALREADY PERFORMS
    // THIS WRITE -- just at an arbitrary time, under an unrelated instruction's
    // identity (robId 0, hardwired at every wiring site). Born-committed makes an
    // existing write deterministic; it does not add a new architectural commitment.
    // The page genuinely was fetched, and the ATC is filled on that same fetch, so
    // marking it used is exactly what the access did.
    umq.io.alloc.payload.robId  := U(0, m68k040.Global.ROB_ID_W_DEFAULT bits)
    umq.io.alloc.payload.preCommitted := True
    umq.io.alloc.payload.addr   := walker.io.rsp.umWrite.addr
    umq.io.alloc.payload.newByte:= walker.io.rsp.umWrite.newByte

    // ── AN INCIDENTAL MASK, NAMED AND CHECKED (race audit, 2026-09-18) ───────────
    // `when(umFlush && missPending)` above reads `missPending` as a REGISTER, and the
    // capture arm writes it. So a walk captured in the SAME cycle as `umFlush` gets
    // `walkUmPoison := False` from the capture and no poison from that arm -- the exact
    // shape of the `atcFlush` hole closed at `needWalk`. Unlike `atcFlush`, `umFlush`
    // is NOT gated out of the capture, and it is reachable: `FetchAlignPlugin`'s
    // `ic.cmd.valid` carries no flush term, so a wrong-path fetch can miss on the very
    // cycle `doFlush` pulses.
    //
    // That window is harmless TODAY for a reason that lives one line below and nowhere
    // else: the I-side entry is BORN COMMITTED, so it is immune to the flush it raced
    // (`UmWriteQueue.flush` keeps every committed entry) and it is an architecturally
    // legitimate write regardless -- the page genuinely was fetched. The TLB fill it
    // also permits is likewise allowed after a backend squash by this plugin's own rule.
    //
    // That is an INCIDENTAL mask, which is precisely how the missing `walkFlushPoison`
    // survived. It is not gated out in hardware because `needWalk` is a timing-relevant
    // conjunction and this costs nothing today -- but the dependency is made explicit
    // and machine-checked here instead of left implicit. Give the I side a real owning
    // robId (i.e. stop driving `preCommitted` to a constant) and C6 reopens through that
    // one-cycle window; this assertion goes live the moment that happens, and the fix is
    // to add `&& !umFlush` alongside the `&& !atcFlush` already on `needWalk`.
    GenerationFlags.simulation {
      assert(!umq.io.alloc.valid || umq.io.alloc.payload.preCommitted,
        "ItlbPlugin: an I-side U write was allocated WITHOUT preCommitted. A miss captured " +
        "in the same cycle as `umFlush` carries NO walkUmPoison (the poison arm reads " +
        "`missPending` before the capture arm writes it, and `needWalk` has no flush term), " +
        "so such an entry belongs to an already-squashed fetch and would sit uncommitted " +
        "until the ROB recycles its robId -- C6 verbatim. Born-committed is the ONLY thing " +
        "making that window safe. Gate `needWalk` on `!umFlush` before removing it.",
        FAILURE)
    }

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
    val drainAddrReg = Reg(UInt(32 bits))
    val drainBeatReg = Reg(Bits(128 bits))
    val drainStrbReg = Reg(Bits(16 bits))

    // ── MERGE, DO NOT OVERWRITE (2026-09-10) ────────────────────────────────────
    // Mirrors DtlbPlugin's block of the same name; see it for the full reasoning.
    // `newByte` was sampled at the walk's READ, and the descriptor's low byte also
    // carries PDT, W, CM and S, so writing the sample back verbatim reverts whatever
    // software stored into that byte in the meantime. The I-side sets only U (a fetch
    // is never a write), but it can revert a supervisor's descriptor store exactly
    // the same way.
    val UmSetMask     = B"8'h08"                 // U = bit 3 (the I-side never sets M)
    val drainNeedRead = RegInit(False)
    val drainReadPend = RegInit(False)
    // sim-only visibility: the U/M drain wedge (2026-09-10) is a HANG with nothing
    // in flight, so the only way to tell "walker held off by the drain" apart from
    // "walker never asked" is to see this state directly. simPublic does not affect
    // the synthesised netlist.
    drainNeedRead.simPublic(); drainReadPend.simPublic()
    drainArmed.simPublic();    drainAckWait.simPublic()
    // sim-only visibility: COUNT WALKS, DO NOT COUNT DESCRIPTOR-PORT TRANSACTIONS.
    // `walkLoadCmd` carries the three descriptor reads of a table search AND the U-bit
    // read-modify-write read the drain block above issues afterwards, so a transaction
    // count is NOT a walk count -- it reads one high per queued U write. Two `ItlbSpec`
    // assertions were re-pointed at this signal by `053e93f7` for exactly that reason;
    // `IcacheParallelViptSpec` and `FetchAlignResidentCadenceSpec` used the same proxy
    // and are re-pointed here. simPublic does not affect the synthesised netlist.
    walker.io.start.simPublic()
    val drainSetBits  = Reg(Bits(8 bits))
    val drainRdAddr   = Reg(UInt(32 bits))
    val drainOffReg   = Reg(UInt(4 bits))
    val drainDropAck  = RegInit(False)
    /** The descriptor cache-mode policy in force when THIS drain armed. */
    val drainCmode    = Reg(CacheMode()) init CacheMode.WRITETHROUGH; drainCmode.simPublic()
    drainDropAck := False

    // `drainArmingNow` is COMBINATIONAL on purpose. `drainNeedRead` is a register, so
    // it is not visible until the cycle AFTER arming -- which left a ONE-CYCLE HOLE in
    // the "hold the walker off for the whole drain read" guard below: on the arming
    // cycle itself the walker's `loadCmd.ready` was still high, so it could issue a
    // descriptor read in the same cycle the drain decided to issue one. Two reads
    // outstanding on a port whose response is unlabelled = swapped responses and a
    // WRONG TRANSLATION -- caught by this file's own tripwire the moment the drain
    // offer became a held level rather than a self-cancelling one-cycle pulse.
    // Combinational: `drainNeedRead` is a register (invisible on the arming cycle) and
    // `walker.io.busy` is `!fsm.isActive(IDLE)`, also registered, so it still reads
    // False on the cycle the walker STARTS. Both lags must be closed here.
    val drainArmingNow = umq.io.drain.valid && !drainArmed && !drainAckWait &&
                         !drainNeedRead && !drainReadPend &&
                         !walker.io.busy && !walker.io.start
    when(drainArmingNow) {
      drainNeedRead := True
      drainRdAddr   := umq.io.drain.payload.addr
      drainOffReg   := drainByteOff
      drainSetBits  := umq.io.drain.payload.newByte & UmSetMask
      // LATCH the policy for this drain. The re-read and the merged store that
      // follows it are one read-modify-write and MUST agree: see
      // `WalkerDcacheClient.walkCmodePolicy` for the DE 1->0 lost-M mechanism.
      drainCmode    := _walkCmodePolicy
    }
    // THE PORT ITSELF must be silenced while the drain owns it. `_walkLoadCmd.valid`
    // defaults to `walker.io.loadCmd.valid`, and during `drainReadPend` the drain's
    // read is OUTSTANDING while `drainNeedRead` is already False -- so the walker's
    // valid passed straight through and a SECOND read issued on a port that allows
    // exactly one. Holding `walker.io.loadCmd.ready` low only stops the walker from
    // BELIEVING it issued; it does not stop the port from issuing, so the walker's
    // response was then consumed by the drain. That is the swapped-response hazard
    // this file's tripwire exists to catch.
    when(drainArmingNow || drainReadPend) { _walkLoadCmd.valid := False }
    when(drainNeedRead) {
      _walkLoadCmd.valid             := True
      _walkLoadCmd.payload.vaddr     := drainRdAddr
      _walkLoadCmd.payload.paddr     := drainRdAddr
      _walkLoadCmd.payload.size      := m68k040.isa.Size.BYTE
      _walkLoadCmd.payload.lineOnly  := False
      _walkLoadCmd.payload.cacheMode := drainCmode
      _walkLoadCmd.payload.token     := U(m68k040.cache.DLoadToken.WALK_ITLB,
                                          m68k040.cache.DLoadToken.Width bits)
    }
    // Hold the walker off for the WHOLE drain read -- issue AND response -- not just
    // while issuing. Releasing it at acceptance lets the walker put a second read on a
    // port that carries one outstanding transaction, and the two responses are then
    // swapped: the drain consumes the walker's descriptor and the walker consumes the
    // drain's. That installs a WRONG TRANSLATION, which on the board showed up as an
    // F-line storm at a fixed RAM address on every boot.
    when(drainArmingNow || drainNeedRead || drainReadPend) { walker.io.loadCmd.ready := False }
    when(drainNeedRead && _walkLoadCmd.ready) {
      drainNeedRead := False
      drainReadPend := True
    }
    when(drainReadPend) { walker.io.loadRsp.valid := False }
    // A FAULTING re-read must not be merged. `data` is meaningless then, and writing
    // a merge of garbage back over a live descriptor would be far worse than dropping
    // a U/M update: it is exactly the corruption this whole change exists to remove.
    // Pop the entry without storing -- the bit is a hint the next walk re-derives, and
    // the faulting access itself reports the fault through its own path.
    val drainReadFault = drainReadPend && _walkLoadRsp.valid && _walkLoadRsp.payload.fault
    when(drainReadFault) {
      drainReadPend := False
      drainArmed    := False
      drainDropAck  := True
    }
    when(drainReadPend && _walkLoadRsp.valid && !_walkLoadRsp.payload.fault) {
      drainReadPend := False
      val curByte = _walkLoadRsp.payload.data(7 downto 0)
      val merged  = curByte | drainSetBits
      drainAddrReg := (drainRdAddr(31 downto 4) ## U(0, 4 bits)).asUInt
      val mBeat = Bits(128 bits); mBeat := B(0, 128 bits)
      val mStrb = Bits(16 bits);  mStrb := B(0, 16 bits)
      for (i <- 0 until 16) {
        when(drainOffReg === U(i, 4 bits)) {
          mBeat(8 * i + 7 downto 8 * i) := merged
          mStrb(i) := True
        }
      }
      drainBeatReg := mBeat
      drainStrbReg := mStrb
      drainArmed   := True
    }
    _walkStore.valid              := drainArmed
    _walkStore.payload.paddr      := drainAddrReg
    _walkStore.payload.data       := B(0, 32 bits)
    _walkStore.payload.size       := m68k040.isa.Size.LONG
    _walkStore.payload.useStrb    := True
    _walkStore.payload.strb       := drainStrbReg
    _walkStore.payload.lineData   := drainBeatReg
    _walkStore.payload.cacheMode  := drainCmode
    _walkStore.payload.precise    := False
    when(_walkStore.fire) { drainArmed := False; drainAckWait := True }
    when(drainAckWait && _walkStoreAck) { drainAckWait := False }
    // `drainDropAck` pops an entry whose re-read faulted, so a faulting descriptor
    // cannot wedge the queue head forever.
    umq.io.drainAck := (drainAckWait && _walkStoreAck) || drainDropAck

    // ── TRIPWIRE: ONE descriptor read outstanding, ever ─────────────────────────
    // The walk port carries a single outstanding transaction and its response is
    // unlabelled, so a second read in flight means the two responses are SWAPPED --
    // the drain consumes the walker's descriptor and the walker consumes the drain's,
    // installing a wrong translation. That is silent: every MMU unit test passed with
    // this hazard present, because a standalone DUT's memory agent answers instantly
    // and the walker never became busy inside the window. It cost a bitstream and a
    // boot campaign to find, as an F-line storm at a fixed RAM address on 5 of 5 boots.
    // Assert the invariant structurally so no future change can reintroduce it
    // quietly, whether or not a test happens to look.
    GenerationFlags.simulation {
      val descOutstanding = Reg(UInt(2 bits)) init (0)
      val descIssue = _walkLoadCmd.valid && _walkLoadCmd.ready
      val descRsp   = _walkLoadRsp.valid
      when(descIssue && !descRsp) { descOutstanding := descOutstanding + 1 }
      when(!descIssue && descRsp && descOutstanding =/= 0) { descOutstanding := descOutstanding - 1 }
      assert(
        !(descIssue && descOutstanding =/= 0 && !descRsp),
        "ItlbPlugin: a second descriptor read was issued while one was still outstanding " +
        "on the walk port -- the two responses will be swapped and a WRONG TRANSLATION " +
        "installed. The drain must hold walker.io.loadCmd.ready low for its whole read.",
        FAILURE)
    }

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
