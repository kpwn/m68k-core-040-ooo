package m68k040.execute

import m68k040.cache.{DcacheService, DLoadCmd, DStoreCmd, DTranslationToken}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService, XRegFileService, RegFileReadPort, RegFileWritePort, RegFileBypassPort}
import m68k040.isa.MemOp
import m68k040.ls.{StoreQueue, SqAlloc, SqFwdQuery}
import m68k040.services.DTranslationService
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

/** Plain-wire LS EU service. Producer (IQ/test) drives `issue`; the ROB-side
  * wiring drives `sqCommit`/`sqFlush` and reads `completion`. */
trait LsEuService {
  def issue: Stream[IqContext]
  def completion: Flow[UInt]   // robId (dynamic-completion wakeup source)
  def sqCommit: Flow[UInt]     // ROB retired this store robId (retire slot 0)
  def sqCommitB: Flow[UInt]    // retire slot 1 — a store CAN dual-retire at h1 (behind
                               // a long-latency head); missing this slot loses the store
  def sqFlush: Bool            // mispredict squash
  def sqDrained: Bool          // no committed/speculative store remains in the SQ
  // Integer-result wakeup: normally registered alongside writeback. The optional
  // earlyIntWakeup path announces a guaranteed next-cycle writeback instead;
  // consumers must preserve the IQ's registered wake/select timing contract.
  def wakeup: Flow[UInt]       // pdst of a completing load (valid only when it writes a reg)
  // Dynamic NZVC-wakeup broadcast: valid (with the produced pNzvcDst) the cycle a STORE
  // that writes NZVC (a MOVE-to-memory store) — or an RTR CCR-restore — completes and
  // its NZVC is in the PRF. A flag-reader (e.g. a bit-op's RMW µop) of such an in-flight
  // LS-produced NZVC waits on this (the static IQ scoreboard cannot clear it: an LS op
  // issues on the LS port, generating no static latency-1 ALU/branch wakeup event).
  def wakeupNzvc: Flow[UInt]   // pNzvcDst of a completing NZVC-writing LS op
  // robId of the access currently being translated (tags a DTLB walk's deferred U/M
  // descriptor write so it drains at THAT instruction's commit).
  def xlateRobId: UInt
  // MMU access-fault completion: the cycle an LS access takes a DTLB rsp.fault, mark
  // its ROB entry FAULTED (vector 2, access fault) with the faulting VA + the SSW
  // access attributes {write, sizeBits, supervisor}. The ROB consumes this like
  // branchCompletion (records per-entry, raises the precise exception at retire).
  def faultCompletion: Flow[LsFault]
}

/** LS access-fault completion payload: which ROB entry faulted (vector 2 implied),
  * the faulting VA, and the SSW access attributes. `write` = store (R/W=write),
  * `sizeBits` = encoded access size (00=byte,01=word,10=long), `supervisor` = the
  * access function-code supervisor bit. `atc` (task #189): True for a genuine
  * MMU/ATC-detected translation fault (DTLB rsp.fault — non-resident / write-
  * protect / supervisor), False for a plain PHYSICAL bus error (a D-cache refill
  * whose AXI response errored — SLVERR/DECERR — with zero MMU involvement). Feeds
  * the format-$7 frame's SSW.ATC bit (bit 10) — see ExceptionUnit's
  * `entryFaultAtc`. Was previously hardcoded True unconditionally (moot before
  * this task since the ONLY existing fault source was the MMU path). */
case class LsFault() extends Bundle {
  val robId      = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
  val faultAddr  = UInt(32 bits)
  val write      = Bool()
  val sizeBits   = UInt(2 bits)
  val supervisor = Bool()
  val atc        = Bool()
}

/** AGU + Load/Store EU with decoupled front resolve and cache back end.
  *
  * S0  read base reg (psrcA) + store data (psrcB); va = base + disp(imm);
  *     request translation (vpn). M2S register.
  * S1  receive ppn -> paddr.
  *     LOAD : query SQ forward + drive dcache.loadCmd. If SQ full-overlap hit ->
  *            forwarded data. Else wait for dcache.loadRsp (refill back-pressures
  *            via dcache.loadBusy). On data ready -> int PRF write + completion
  *            (the dynamic wakeup) + wbObs.
  *     STORE: sq.alloc(robId, paddr, data, size); completion fires (store
  *            "executes" == SQ-allocated; it drains at commit).
  * An aligned non-forwarded load enters a four-entry in-order descriptor ring, so
  * multiple cache operations can remain resident while untagged responses preserve
  * acceptance order. Split-line/page loads use a mutually-exclusive two-pass replay. */
/** @param walkerAgeLimit  W8's fairness bound: after this many un-granted cycles a table
  *                        walker's `force` bit outranks the ordinary LS pipe for the
  *                        D-cache load port (never the exception sequencer's own
  *                        presented command). A constructor parameter, not a `Global`
  *                        key, so directed tests can shorten it.
  * @param walkerWedgeLimit W26's observability bound: cycles a walker may hold either
  *                        D-cache port with NO progress on any channel before the sticky
  *                        `walkerPortWedge` report rises.
  *
  *                        Sized so that NO legitimate bounded operation can reach it, not
  *                        merely so that it is large. The binding case is a CPUSH/CINV
  *                        maintenance walk: `DcachePlugin` refuses both client directions
  *                        for its whole duration, which is `sets*ways = 512` iterations
  *                        each of which may write a dirty victim back at DDR latency --
  *                        order 10^4-10^5 cycles, and none of it produces any of the
  *                        progress events this counter resets on. (`quiesceHold` now also
  *                        covers `S_MAINTWAIT`, so a walker should not be holding a grant
  *                        across one at all; this bound is the second line of defence, and
  *                        a false halt would be far worse than a late report.) 2^20 cycles
  *                        is ~5 ms at 200 MHz. */
class LsEuPlugin(val walkerAgeLimit: Int = 64,
                 val walkerWedgeLimit: Int = 1 << 20,
                 val alignedLoadFallThrough: Boolean = false,
                 val earlyIntWakeup: Boolean = false,
                 val sqSubwordForwarding: Boolean = false,
                 val reserveLateStore: Boolean = false) extends FiberPlugin with LsEuService {
  // ─────────────────────────────────────────────────────────────────────────
  // D1 elastic LS front (spec `2026-08-09-ipc-ls-eu-full-pipeline-design.md`):
  // P1 owns the full issue context and registered operands; P2 launches DTLB+VIPT;
  // P3 owns the resolved PA and SQ operation; P4 resolves forwarding/cache launch.
  // Aligned loads enter an ordered descriptor ring. Only rare split loads retain a
  // serial BK_IDLE/LAUNCH/WAIT_A/WAIT_B replay FSM.

  var issuePort: Stream[IqContext] = null
  var completionPort: Flow[UInt]   = null
  var sqCommitPort: Flow[UInt]     = null
  var sqCommitBPort: Flow[UInt]    = null
  var sqFlushSig: Bool             = null
  var wakeupPort: Flow[UInt]       = null
  var wakeupNzvcPort: Flow[UInt]   = null
  var faultCompletionPort: Flow[LsFault] = null
  var rdBase, rdData: RegFileReadPort = null
  var rdIndex: RegFileReadPort = null   // brief-format indexed EA: the index register Xn (psrcC)
  var intW: RegFileWritePort = null
  var intByp: RegFileBypassPort = null
  var nzvcW: RegFileWritePort = null
  var nzvcByp: RegFileBypassPort = null
  // X-flag write/bypass for the RTR CCR-restore load (X := loaded[4]).
  var xW: RegFileWritePort = null
  var xByp: RegFileBypassPort = null

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort
  override def sqCommit: Flow[UInt]     = sqCommitPort
  override def sqCommitB: Flow[UInt]    = sqCommitBPort
  override def sqFlush: Bool            = sqFlushSig
  override def sqDrained: Bool          = sqEmptySig
  override def wakeup: Flow[UInt]       = wakeupPort
  override def wakeupNzvc: Flow[UInt]   = wakeupNzvcPort
  override def faultCompletion: Flow[LsFault] = faultCompletionPort
  var xlateRobIdSig: UInt = null
  override def xlateRobId: UInt         = xlateRobIdSig

  // ── Exception-unit cache arbitration (full-core wiring drives these) ─────────
  // While `excActive`, the commit-side ExceptionUnit owns the D-cache request
  // ports (the LS pipe is squashed/idle — serializing).
  // Default-idle (allowOverride) keeps standalone LS tests unchanged.
  //
  // Task 11: the exception sequencer's frame/vector accesses used to be UNIFORMLY
  // identity-physical, which is why this MUX never covered the tagged DTLB service.
  // FSAVE/FRESTORE's state-frame transfers were the first ones that were genuinely
  // VIRTUAL. As of 2026-09-09 they are no longer special: the ENTRY frame push, the
  // handler VECTOR fetch and the RTE frame pop all translate too, so EVERY memory
  // access this sequencer makes now goes through this hand-off and there is no
  // identity-physical exception path left anywhere. The same MUX hands over
  // `xlate.req` — safe for exactly the
  // reason the D-cache hand-off is: the LS pipe already relinquishes its own DTLB
  // claim for the entire duration `excActive` is held (`xlate.req.valid := !excActive
  // && ...` below, and `xlate.rsp.ready` unconditionally True while `excActive`).
  var excActive: Bool = null
  var excLoadCmdValid: Bool = null; var excLoadCmdVaddr: UInt = null; var excLoadCmdSize: m68k040.isa.Size.C = null
  var excLoadCmdPaddr: UInt = null
  var excLoadCmdReady: Bool = null
  var excStoreValid: Bool = null;   var excStorePayload: DStoreCmd = null
  var excStoreReady: Bool = null
  // DTLB request hand-off (Task 11). Mirrors the `excLoadCmd*` shape exactly.
  var excXlateValid: Bool = null; var excXlateVpn: UInt = null
  var excXlateWrite: Bool = null; var excXlateToken: UInt = null
  var excXlateReady: Bool = null
  var sqEmptySig: Bool = null   // store queue drained (no committed store in flight)

  // ── Precise-path SQ<->ROB pass-throughs (Task P2.5 wires these end-to-end;
  // mirrors the excActive/excLoadCmdValid pattern above exactly). ──
  var robHeadIn: UInt = null
  var robHeadValidIn: Bool = null
  var irqPreemptPendingIn: Bool = null
  var sqCompletionPort: Flow[UInt] = null
  var sqFaultCompletionPort: Flow[LsFault] = null
  var preciseDrainBusySig: Bool = null
  // ── Inhibited-load preemption interlock (task: interrupt/trace/debug-auto-halt
  // preempting an already-bus-active inhibited LOAD) ──
  // `debugHaltImminentIn`: True the cycle a debug automatic-halt boundary (halt-
  // after-N-macros) is about to apply to the CURRENT ROB head -- i.e. RobPlugin's
  // own `haltAfterDue || haltAfterRetireBlock`, the EXACT pair already gating
  // `retire0`. Mirrors `irqPreemptPendingIn` (an async external event) but for a
  // fully PREDICTABLE internal one: the debug session's own retire-count target.
  var debugHaltImminentIn: Bool = null
  // `inhibitedLoadBusySig`: True from the cycle an INHIBITED load's bus command
  // launches until the LAUNCHING UOP RETIRES (it leaves the ROB head) -- see
  // `launcherStillAtHead` at the drive site. It is NOT "response consumed +1 cycle";
  // that was the PRE-4b6a91bc behaviour and this comment described it for a while
  // after the fix landed, which is actively misleading: the whole point of the fix is
  // that an IRQ arriving in the +1-cycle window squashed a macro whose device read had
  // ALREADY happened, and the replay re-popped the device. It mirrors
  // StoreQueue's `preciseDrainBusyReg`/`io.preciseDrainBusy` in ROLE, but tracks
  // the LOAD-launch half of the same "a precise device transaction is in flight"
  // concept instead of the STORE-drain half. Fed to the ROB as a sibling of
  // `preciseDrainBusyIn` (kept separate, not folded in: that name is store-
  // specific, and the two conditions have independent, non-overlapping launch/
  // clear events).
  var inhibitedLoadBusySig: Bool = null
  // `quiesceHold` (W19): asserted by the commit-time exception sequencer across its
  // maintenance-quiesce window (`S_DRAIN || S_APPLY`), and it closes TABLE-WALKER
  // admission to the D-cache ports only -- never the CORE's own.
  //
  // WHY IT HAS TO EXIST. `ExceptionUnit`'s own written deadlock analysis for `S_DRAIN`
  // ends "With the LS EU flushed, nothing re-arms them" -- i.e. once the LS pipe is
  // flushed, the D-cache's in-flight-transaction terms are all self-clearing and
  // `dcQuiesced` is guaranteed to settle. A table walker on the same ports makes that
  // sentence FALSE: it is not flushed, it is not part of the LS pipe, and it can start
  // a fresh D-cache access at any time. `S_APPLY` is included as well as `S_DRAIN`
  // because `maintCmdOut` pulses in `S_APPLY` while the D-cache's own `maintBusyReg`
  // only rises a cycle later, leaving a one-cycle fully-open window.
  //
  // The hold is at COMMAND granularity: a walk already in flight simply stalls between
  // descriptor reads, which is bounded by the maintenance walk's own completion. The
  // dependency graph stays `walker -> maintenance`, never the reverse.
  //
  // Default-idle (`allowOverride`, False) so every DUT that does not wire it still
  // elaborates with today's behaviour.
  var quiesceHold: Bool = null

  during setup {
    excActive       = Bool()
    excLoadCmdValid = Bool(); excLoadCmdVaddr = UInt(32 bits); excLoadCmdSize = m68k040.isa.Size()
    excLoadCmdPaddr = UInt(32 bits)
    excLoadCmdReady = Bool()
    excStoreValid   = Bool(); excStorePayload = DStoreCmd(); excStoreReady = Bool()
    excXlateValid   = Bool(); excXlateVpn = UInt(20 bits)
    excXlateWrite   = Bool(); excXlateToken = UInt(DTranslationToken.Width bits)
    excXlateReady   = Bool()
    sqEmptySig      = Bool()
    robHeadIn              = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
    robHeadValidIn         = Bool()
    irqPreemptPendingIn    = Bool()
    sqCompletionPort       = Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits))
    sqFaultCompletionPort  = Flow(LsFault())
    preciseDrainBusySig    = Bool()
    debugHaltImminentIn    = Bool()
    inhibitedLoadBusySig   = Bool()
    quiesceHold            = Bool()
    issuePort      = Stream(IqContext())
    issuePort.valid.simPublic(); issuePort.ready.simPublic(); issuePort.payload.robId.simPublic() // debug-only, task #139 finding #1; zero synth impact
    completionPort = Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits))
    sqCommitPort   = Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits))
    // Slot-1 commit: defaults to idle (allowOverride) so single-retire benches/stubs
    // need no wiring; the full-core wiring overrides it with {retire1, h1}.
    sqCommitBPort  = Flow(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits))
    sqCommitBPort.valid.allowOverride;   sqCommitBPort.valid   := False
    sqCommitBPort.payload.allowOverride; sqCommitBPort.payload := U(0, m68k040.Global.ROB_ID_W_DEFAULT bits)
    sqFlushSig     = Bool()
    wakeupPort     = Flow(UInt(6 bits))
    wakeupNzvcPort = Flow(UInt(4 bits))   // pNzvcDst of a completing NZVC-writing LS op
    faultCompletionPort = Flow(LsFault()); faultCompletionPort.simPublic()
    xlateRobIdSig  = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
    val irf = host[IntRegFileService]
    rdBase = irf.newRead()
    rdData = irf.newRead()
    rdIndex = irf.newRead()   // brief-format indexed EA: the index register Xn (psrcC)
    intW   = irf.newWrite(latency = 1)
    intByp = irf.newBypass()
    // MOVE-to-memory sets NZVC (impl (a)): the LS EU writes the NZVC PRF + bypass at
    // store completion. rename allocates the store µop a unique pNzvcDst, so this
    // distinct physical write port never collides with the ALU EUs' NZVC writers.
    val nz = host[NzvcRegFileService]
    nzvcW   = nz.newWrite(latency = 1)
    nzvcByp = nz.newBypass()
    // X write/bypass for the RTR CCR-restore load (X := loaded[4]). rename gives the
    // ccr-restore load a unique pXDst, so this is a distinct physical X write port.
    val xrf = host[XRegFileService]
    xW   = xrf.newWrite(latency = 1)
    xByp = xrf.newBypass()
  }

  val logic = during build new Area {
    val lateStoreData = host.get[m68k040.services.LateStoreDataService].flatMap(_.lateStoreData)
    val dcache = host[DcacheService]
    val xlate  = host[DTranslationService]
    // The current architectural S bit (ROB-owned) — a normal LOAD/STORE's DTLB
    // request must reflect the ACTUAL current privilege level, not the hardcoded
    // `False` this slice previously used (a "slice-1 user-only simplification" — see
    // reqDrvSup below and the matching note in ExceptionUnit.scala). A supervisor-mode
    // data access to a supervisor-only page would otherwise fault. `host.get`
    // (optional): a standalone LS-EU DUT with no RobPlugin/PrivilegeService wired
    // defaults to False (user), unchanged for every existing non-full-core test. Does
    // It does not affect the exception sequencer's frame/vector accesses, which carry
    // their own supervisor bit (hardwired True at the `excActive` hand-off below --
    // the sequencer runs supervisor by definition) rather than this live architectural
    // one. They are no longer identity-physical (2026-09-09); they are translated with
    // that hardwired supervisor bit.
    val privCtrl = host.get[m68k040.services.PrivilegeService]

    // fast/precise store classification (Task P2.2). OPTIONAL (host.get, mirrors
    // privCtrl above / RobPlugin's own mmuCtrl fallback): a standalone LS-EU DUT
    // with no MmuControlPlugin/RobPlugin wired (most existing directed LS tests —
    // they use DIdentityTranslationPlugin, no MMU present) falls back to the real
    // architectural reset defaults (mmuEnable=0, CACR.DE=0) below, at the point
    // `fastStore` is computed — i.e. those DUTs classify every store as precise,
    // which mirrors actual 68040 reset-state hardware (MMU off, D-cache off), not a
    // test-harness special case.
    val mmuCtrl2  = host.get[m68k040.services.MmuControlService]
    val cacheCtrl = host.get[m68k040.services.CacheControlService]
    // Task #195: TCR.P (8KB pages) — read the SAME way `fastStore` reads mmuEnable
    // just below: a standalone LS-EU DUT with no MmuControlPlugin wired (most
    // existing directed LS tests) defaults to False (4K pages), the pre-#195
    // behavior, unchanged.
    val is8K = mmuCtrl2.map(_.pageSize8K).getOrElse(False)

    // exc-arbitration inputs default-idle (allowOverride): a DUT that doesn't wire
    // the exception unit (standalone LS tests) sees excActive=False -> the LS EU
    // owns the cache exactly as before.
    excActive.allowOverride;            excActive := False
    excLoadCmdValid.allowOverride;      excLoadCmdValid := False
    excLoadCmdVaddr.allowOverride;      excLoadCmdVaddr := U(0, 32 bits)
    // Defaults to the vaddr, NOT to zero, so a DUT that wires the exception unit's load
    // command but not this paddr pass-through still elaborates.
    //
    // ⚠ THIS DEFAULT IS A TRAP, and it cost real debugging time on 2026-09-09. It is
    // SILENT: a DUT that forgets the pass-through does not fail to elaborate and does
    // not assert -- it quietly runs every exception-sequencer access identity-mapped,
    // which is indistinguishable from correct behaviour under an identity page table
    // and is silent WRONG DATA under any other. `FullCoreDut` (the whole lock-step
    // suite) had exactly that gap, together with the matching one on the exception
    // unit's own `dxRsp*` defaults, so NO lock-step test could observe a translated
    // exception frame -- including FSAVE/FRESTORE's, translated since Task 11.
    // If you add a DUT, wire `excLoadCmdPaddr` AND the `dxReq*`/`dxRsp*` port; copy
    // FullCoreSynth, do not rely on these defaults.
    excLoadCmdPaddr.allowOverride;      excLoadCmdPaddr := excLoadCmdVaddr
    excLoadCmdSize.allowOverride;       excLoadCmdSize := m68k040.isa.Size.LONG
    excStoreValid.allowOverride;        excStoreValid := False
    excStorePayload.allowOverride;      excStorePayload.assignDontCare()
    excLoadCmdReady.allowOverride;      excLoadCmdReady := False
    excXlateValid.allowOverride;        excXlateValid := False
    excXlateVpn.allowOverride;          excXlateVpn := U(0, 20 bits)
    excXlateWrite.allowOverride;        excXlateWrite := False
    excXlateToken.allowOverride;        excXlateToken := U(0, DTranslationToken.Width bits)
    excXlateReady.allowOverride;        excXlateReady := False
    quiesceHold.allowOverride;          quiesceHold := False

    // Precise-path pass-throughs default-idle (allowOverride): a DUT that doesn't
    // wire the ROB (standalone LS tests) sees robHeadValidIn=False -> headPreciseReady
    // can never assert, matching the SQ's own pre-P2.5 dead-wired defaults.
    robHeadIn.allowOverride;           robHeadIn := U(0, m68k040.Global.ROB_ID_W_DEFAULT bits)
    robHeadValidIn.allowOverride;      robHeadValidIn := False
    irqPreemptPendingIn.allowOverride; irqPreemptPendingIn := False
    // A DUT with no RobPlugin (most standalone LS tests) never has a debug-auto-
    // halt session armed -- idle-default False, matching `irqPreemptPendingIn`.
    debugHaltImminentIn.allowOverride; debugHaltImminentIn := False
    // Debug-only taps (zero synth impact, matches every other tap in this file):
    // a standalone LS-EU-only DUT (no real RobPlugin) needs to sim-poke these
    // directly to exercise the precise-drain path (e.g. the `liveCompletionFires`
    // collision-retry directed test in LsEuFastPreciseSpec).
    robHeadIn.simPublic(); robHeadValidIn.simPublic(); irqPreemptPendingIn.simPublic()
    debugHaltImminentIn.simPublic()


    // ═══════════════════════════════════════════════════════════════════════════════
    // TABLE-WALK ↔ D-CACHE PORT ARBITRATION
    // ═══════════════════════════════════════════════════════════════════════════════
    //
    // The ITLB and DTLB table walkers used to own private 128-bit AXI masters straight
    // to physical memory. They no longer do: their three dependent descriptor READS and
    // their deferred U/M descriptor WRITEBACK are ordinary `DcacheService` client
    // traffic, and this block is what merges them onto the D-cache's single load port
    // and single store port alongside the LS pipe and the exception sequencer.
    //
    // WHY (correctness, not performance). A page-table entry the supervisor wrote with
    // an ordinary `move.l` sits DIRTY in the copyback L1D and is invisible in backing
    // memory until that line is evicted. A walker reading memory behind the cache's back
    // therefore reads the STALE descriptor and installs a wrong translation; and a
    // walker U/M writeback that goes only to memory is later overwritten wholesale by
    // the eviction of that same dirty line, losing the update. Losing an M means a dirty
    // page is later evicted as clean -- silent data loss. Both directions are closed by
    // putting the traffic through the cache that owns the line, which is also what a
    // real 68040 does (table searches go through the data cache).
    //
    // WHERE THE ARBITRATION LIVES, AND WHY HERE. `DcachePlugin.scala` receives ZERO
    // edits and gains no port and no client awareness: this file already owns a
    // two-source override mux for the exception sequencer, and extending that mux is a
    // far smaller change than adding a second requester tier inside a 3200-line cache.
    // (NaxRiscv puts the equivalent mux inside its `DataCachePlugin` via a public
    // `newLoadPort`; both placements are one layer above the raw cache, and the
    // difference is which file absorbs the risk.)
    //
    // DEADLOCK. There is no circular dependency, and the reason is structural rather
    // than argued: `DcachePlugin` contains zero references to any translation service.
    // It never invokes the MMU and operates purely on a pre-translated `paddr` supplied
    // by its caller. A table search computes its descriptor addresses arithmetically
    // from the root pointer and VA index slices, so a WALK'S D-CACHE ACCESS REQUIRES NO
    // TRANSLATION. The dependency graph is `access -> walk -> cache`: a chain, not a
    // cycle. The two waits this block does create are both bounded and are argued at
    // their own sites: the early-VIPT probe hand-over (see `probeCancelAll` below) and
    // the maintenance quiesce (`quiesceHold`).
    //
    // RESPONSE IDENTITY. `DLoadRsp` carries no token -- responses are matched
    // POSITIONALLY today, by the aligned ring's own pointer. Adding a second concurrent
    // load client therefore needs an out-of-band identity, which is the `ldFifo` below:
    // a tag pushed on every accepted `loadCmd` and popped in lockstep with every
    // `loadRsp`, exploiting the D-cache's in-order response contract that the aligned
    // ring already depends on. That is what lets a walker's descriptor read be
    // outstanding at the same time as ordinary LS loads instead of serialising them.
    val walkIdxItlb = 0
    val walkIdxDtlb = 1
    val walkClients: Seq[Option[m68k040.services.WalkerDcacheClient]] =
      Seq(host.get[m68k040.services.ItlbWalkerDcacheClient],
          host.get[m68k040.services.DtlbWalkerDcacheClient])
    val walkPresent = walkClients.map(_.isDefined)
    val anyWalkerPresent = walkPresent.exists(identity)

    // Per-walker request views. A DUT that hosts no TLB plugin (the standalone LS-EU
    // tests) sees these permanently idle, every admission predicate below folds to
    // False, `ldOwner` never leaves CORE, and the whole block constant-folds away.
    val walkLdReq = Vec(Bool(), 2)
    val walkStReq = Vec(Bool(), 2)
    val walkLdPayload = Vec(m68k040.cache.DLoadCmd(), 2)
    val walkStPayload = Vec(m68k040.cache.DStoreCmd(), 2)
    // ── FMax: a REGISTERED stage on the walker LOAD request (TLB-miss path) ───────
    // `walkLoadCmd.valid` is a live combinational function of the walker FSM — for the
    // ITLB that is `descSplitIdx`, which post-synth reports name as the startpoint of
    // EVERY one of the 30 worst setup paths:
    //
    //   ItlbPlugin walker/descSplitIdx -> (35 Itlb cells) -> (16 LsEu cells)
    //     -> IssueQueuePlugin lines_*_ways_*_sel      29 levels, 6.486 ns, 76.7% route
    //
    // The walkers share this D-cache port, so the INSTRUCTION-side page-table walker's
    // state reached the LS admission predicates (`walkerLoadAdmit` / `ldCand`) and from
    // there the whole ready chain — `s1Ready -> issuePort.ready -> (IQ) selPorts.fire
    // -> the per-slot triggers` — the same four-plugin spine this file already documents
    // as a recurring worst-path family (see the `applyBacklog` cut note below).
    //
    // Staging the request breaks that root: the walker's combinational request now
    // terminates at a flop, and the LS side sees a register. The handshake is unchanged
    // (`m2sPipe` is valid/ready-correct), so the only cost is ONE CYCLE per descriptor
    // fetch — on the TLB MISS path, which already costs a full table walk of several
    // memory accesses. Making the rare path one cycle longer to shorten the cone that
    // gates EVERY issue-queue selection is the intended trade.
    //
    // LOAD side only: the walker STORE request (the U/M writeback) is not on any
    // reported critical path, and leaving it alone keeps the blast radius to one stream.
    val walkLdStaged = Vec.tabulate(2) { i =>
      walkClients(i) match {
        case Some(c) => c.walkLoadCmd.m2sPipe()
        case None    => { val d = Stream(m68k040.cache.DLoadCmd()); d.valid := False
                          d.payload.assignDontCare(); d }
      }
    }
    for (i <- 0 until 2) {
      walkClients(i) match {
        case Some(c) =>
          walkLdReq(i)     := walkLdStaged(i).valid
          walkLdPayload(i) := walkLdStaged(i).payload
          walkStReq(i)     := c.walkStore.valid
          walkStPayload(i) := c.walkStore.payload
        case None =>
          walkLdReq(i) := False
          walkStReq(i) := False
          walkLdPayload(i).assignDontCare()
          walkStPayload(i).assignDontCare()
      }
    }

    // ── Owner codes. THREE-valued (the ordinary LS pipe and the exception sequencer
    // are ONE arbiter owner, `CORE`, because their mutual exclusion is already proven
    // and already implemented by the exception override mux at the bottom of this
    // file). Deliberately DISTINCT from the FOUR-valued response tag below: CORE-LS
    // and CORE-EXC are one owner but two response identities, and the two encodings
    // must never be used interchangeably.
    val OWNER_CORE = 0
    val OWNER_ITLB = 1
    val OWNER_DTLB = 2
    val ldOwner = RegInit(U(OWNER_CORE, 2 bits))
    val stOwner = RegInit(U(OWNER_CORE, 2 bits))
    ldOwner.simPublic(); stOwner.simPublic()
    val walkerOwnsLoad  = ldOwner =/= U(OWNER_CORE, 2 bits)
    val walkerOwnsStore = stOwner =/= U(OWNER_CORE, 2 bits)
    // `ldOwner`/`stOwner` are REGISTERS, and every use below -- the payload mux select,
    // the `valid` gate and every owner qualification -- reads the SAME register on the
    // SAME cycle. That is the same-cycle owner-coherence invariant: a mux selecting off
    // a combinational grant while a qualification reads a registered owner would
    // reintroduce exactly the silent-corruption class this arbitration exists to avoid,
    // through a one-cycle timing gap instead of a missing site.
    val ldWalkSelDtlb = ldOwner === U(OWNER_DTLB, 2 bits)
    val stWalkSelDtlb = stOwner === U(OWNER_DTLB, 2 bits)

    // ── FMax: PRE-MUX the two walker legs before the boundary mux ──────────────────
    // `dcache.loadCmd.payload.vaddr` feeds `DcachePlugin`'s `cmdSet` -> `rdSet`, the
    // BRAM read-address net that this design's own post-route reports have repeatedly
    // named as the critical arc. Letting both walkers reach that mux directly would
    // widen it from 3-way to 5-way on a 32-bit bus in a route-dominated netlist. The
    // two walker payloads are register-sourced at their producers (`TableWalker.descAddr`,
    // the TLB plugins' `drainAddrReg`) and the select here is a REGISTER, so collapsing
    // them to one leg first is a short flop-to-flop 2:1 and returns the boundary mux to
    // 4-way -- one leg more than today, not two. It also means adding a third walker
    // later would not widen the boundary at all.
    val walkLdSel = Mux(ldWalkSelDtlb, walkLdPayload(walkIdxDtlb), walkLdPayload(walkIdxItlb))
    val walkLdSelValid = Mux(ldWalkSelDtlb, walkLdReq(walkIdxDtlb), walkLdReq(walkIdxItlb))
    val walkStSel = Mux(stWalkSelDtlb, walkStPayload(walkIdxDtlb), walkStPayload(walkIdxItlb))
    val walkStSelValid = Mux(stWalkSelDtlb, walkStReq(walkIdxDtlb), walkStReq(walkIdxItlb))

    // ── The FIXED architectural cache mode for table-walk traffic (W1/W2/W3) ───────
    // `CACR.DE ? WRITETHROUGH : INHIBITED`, stamped HERE by the mux, never derived from
    // an address and never carried on the walk request.
    //
    //   * It is not derived from a descriptor because that would be CIRCULAR -- the
    //     descriptor fetch is the thing that resolves attributes. Real 68040 table
    //     searches are physical-only for the same reason. This is also why it does not
    //     violate the standing "never assume a static SoC address decode map" rule:
    //     there is no page attribute in existence to consult, for anyone, ever. The
    //     MAPPED PAGE's cache mode is still read from its descriptor and is untouched
    //     by any of this.
    //   * WRITETHROUGH specifically, not merely "cacheable": under WRITETHROUGH the U/M
    //     update lands in BOTH the array and memory in all three residency states, with
    //     no dependence on the eviction path. COPYBACK would be correct but would trade
    //     a proof for a dependency on exactly the eviction path that created the bug.
    //   * INHIBITED when `CACR.DE = 0` because the whole D-cache is off then: an
    //     INHIBITED store never touches the array, so the array cannot go stale.
    //   * The read half and the write half use the SAME expression from the SAME
    //     signal. A per-half mode would let a read hit an array copy the write never
    //     updated.
    //
    // ── FIXED (race audit, 2026-09-18). The bullet above said "the read half and the
    // write half use the SAME expression from the SAME signal", which is SPATIAL
    // agreement; the invariant needs TEMPORAL agreement. This was a LIVE `CACR.DE` read
    // sampled independently by the load leg and the store leg, and one table search spans
    // both -- three descriptor reads, the deferred U/M drain's re-read, then that drain's
    // merged store -- with a MOVEC to CACR free to retire anywhere inside it. Worse,
    // `quiesceHold` made the split MORE likely: a CACR write is a sysOp whose
    // `S_DRAIN`/`S_APPLY` deny the walker a fresh STORE grant, so a drain that had already
    // completed its re-read under the old DE was parked until after the write landed and
    // then stamped with the new mode. DE 1->0 left the descriptor line ALLOCATED by the
    // WRITETHROUGH re-read holding the pre-update byte while the INHIBITED store updated
    // only memory -- M lost, and a dirty page later evicted as clean.
    //
    // The fix moves the stamp to the client, which is the only place that knows which
    // accesses form one read-modify-write. See `WalkerDcacheClient.walkCmodePolicy`.
    val walkCacheMode = Mux(cacheCtrl.map(_.dcacheEnabled).getOrElse(False),
                            m68k040.cache.CacheMode.WRITETHROUGH,
                            m68k040.cache.CacheMode.INHIBITED)
    // EXPORT the policy; do not stamp it onto the legs. Each TLB plugin uses it live for
    // the walker's own (read-only) descriptor fetches and LATCHES it for the U/M drain's
    // read-modify-write pair, which is the only part of a table search that mutates and
    // therefore the only part that needs the two halves to agree ACROSS CYCLES rather
    // than merely come from the same net. See `WalkerDcacheClient.walkCmodePolicy`.
    walkClients.foreach(_.foreach(_.walkCmodePolicy := walkCacheMode))

    // ── Load-response ownership FIFO ───────────────────────────────────────────────
    // Depth 8, which is the real bound and not a round number: the aligned ring is 4
    // deep so CORE-LS can have 4 loads outstanding, and a walker's descriptor read can
    // be outstanding concurrently with all of them, so 5 entries are reachable. 8 is the
    // next power of two. Every admission predicate carries `!ldFifoFull` so a push can
    // never overrun.
    val LDTAG_CORE_LS  = 0
    val LDTAG_CORE_EXC = 1
    val LDTAG_ITLB     = 2
    val LDTAG_DTLB     = 3
    val ldFifoDepth = 8
    val ldFifoTags  = Vec.fill(ldFifoDepth)(Reg(UInt(2 bits)) init U(LDTAG_CORE_LS, 2 bits))
    val ldFifoPushPtr = RegInit(U(0, log2Up(ldFifoDepth) + 1 bits))
    val ldFifoPopPtr  = RegInit(U(0, log2Up(ldFifoDepth) + 1 bits))
    val ldFifoOcc   = ldFifoPushPtr - ldFifoPopPtr
    val ldFifoEmpty = ldFifoOcc === 0
    val ldFifoFull  = ldFifoOcc === U(ldFifoDepth, ldFifoOcc.getWidth bits)
    ldFifoOcc.simPublic()
    // The PRE-POP head: it identifies THIS cycle's response, so it must be read before
    // the pop below advances the pointer.
    val ldRspTag = ldFifoTags(ldFifoPopPtr(log2Up(ldFifoDepth) - 1 downto 0))
    ldRspTag.simPublic()
    val coreLsRspValid  = dcache.loadRsp.valid && (ldRspTag === U(LDTAG_CORE_LS, 2 bits))
    val coreExcRspValid = dcache.loadRsp.valid && (ldRspTag === U(LDTAG_CORE_EXC, 2 bits))

    // ── Exception-sequencer load bookkeeping ───────────────────────────────────────
    // `exc.dcLoadRsp` is wired at the top level STRAIGHT off `dcache.loadRsp`, with no
    // qualification -- deliberately, so no DUT has to be re-wired on the response side.
    // What makes that safe is DRAIN-TO-ZERO at the CORE-EXC boundary, and it must hold
    // against EVERY other client, not only walkers: an exception load is admitted only
    // when the ownership FIFO is completely empty, and no other client is admitted while
    // one is presented or outstanding. So while the exception sequencer is waiting, the
    // only response that can arrive is its own.
    val excLoadOutstanding = RegInit(False)
    excLoadOutstanding.simPublic()
    val ldBusyExc = excLoadOutstanding || excLoadCmdValid
    val excLoadAdmit = excLoadCmdValid && (ldOwner === U(OWNER_CORE, 2 bits)) &&
                       ldFifoEmpty && !ldFifoFull

    // ---- store queue instance ----
    val sq = new StoreQueue(8, subwordForwarding = sqSubwordForwarding,
      reserveLateStore = reserveLateStore)
    sq.io.commit  << sqCommitPort
    sq.io.commitB << sqCommitBPort
    sq.io.flush  := sqFlushSig
    // Ordinary SQ traffic owns the elastic store command by default.  The
    // exception sequencer may override it below only after quiescing the SQ; when
    // it does, explicitly hold the SQ side so a command cannot be accepted under
    // the exception payload.
    dcache.store.valid   := sq.io.drain.valid
    dcache.store.payload := sq.io.drain.payload
    sq.io.drain.ready    := dcache.store.ready
    // The terminal response is untagged. Retain the accepted owner because
    // excActive is already high while E_DRAIN still lets ordinary SQ stores finish.
    val excStoreOutstanding = RegInit(False)
    excStoreOutstanding.simPublic()
    // ── W13: the terminal store ack is UNTAGGED, so it must be demultiplexed by the
    // latched store owner. `StoreQueue` already ASSERTS on a stray ack, so mis-routing
    // a walker's U/M-store ack into the SQ is a hard failure, not a silent one -- which
    // is exactly why this qualification is mandatory rather than prudent.
    //
    // Only one of the three can be outstanding at a time on the store direction (the
    // store side is drain-to-zero: see the grant machine at the bottom of this file), so
    // these three terms are mutually exclusive by construction.
    val walkStOutstanding = RegInit(False)
    walkStOutstanding.simPublic()
    sq.io.drainAck := dcache.storeAck && !excStoreOutstanding && !walkStOutstanding
    sq.io.drainErr := dcache.storeErr && !excStoreOutstanding && !walkStOutstanding
    // CORE store credits: SQ drains AND exception-sequencer stores, minus CORE acks.
    // The store port is handed to a walker only at zero, so a walker's U/M store can
    // never interleave with a core store the untagged ack could then be attributed to.
    //
    // FOUR bits, mirroring the width of the count it shadows. `DcachePlugin`'s own
    // `storeOutstanding` is 4 bits and asserts `storeOutstanding <= 8`, so EIGHT core
    // stores can legally be in flight. At three bits this counter wrapped 7 -> 0 on the
    // eighth and spuriously satisfied `stGrantOk`'s `coreStOutstanding === 0`
    // drain-to-zero term with eight core stores outstanding -- handing the store port to
    // a walker whose untagged `storeAck` then demultiplexes to the wrong client, and
    // masking `sq.io.drainAck`/`coreStAck` (both are qualified `!walkStOutstanding`) for
    // every core store whose ack lands inside the walker's window. The wrap also read
    // back as a legal 0 to the `!(walkStOutstanding && coreStOutstanding =/= 0)`
    // tripwire below, so the tripwire could not see it either.
    //
    // HONEST SCOPE: this is carried forward from `030651f` on the RTL argument, NOT on a
    // measurement. `WalkerExcEntryWedgeSpec`'s store-port stress rows drive the D-cache
    // to at most FOUR outstanding stores in every configuration (dcMax=4, mirror max=4,
    // zero cycles at 8), so no test in this tree currently reaches the wrap. Widening is
    // behaviourally identical below 8 and correct at and above it.
    val coreStOutstanding = Reg(UInt(4 bits)) init 0
    coreStOutstanding.simPublic()
    sqEmptySig := sq.io.empty           // surfaced for the exception FSM's drain wait
    // Simulation-only visibility for the full-path exception/SQ arbitration proof.
    // These are existing queue signals, not a second producer or a cross-plugin API.
    sq.io.empty.simPublic()
    sq.io.drain.valid.simPublic()
    sq.io.drain.ready.simPublic()
    // ---- precise-path at-head drain (Task P2.5): now fully closed-loop, routed
    // through LsEuPlugin's own pass-through wires (host DUT wires these to the
    // ROB's h0/count/interruptPending/tracePendingFire and drains sqCompletion/
    // sqFaultCompletion/preciseDrainBusy back into the ROB's 5th completion port). ----
    sq.io.robHeadIn           := robHeadIn
    sq.io.robHeadValidIn      := robHeadValidIn
    sq.io.irqPreemptPendingIn := irqPreemptPendingIn
    // sqCompletionPort/sqFaultCompletionPort are NOT a raw passthrough of
    // sq.io.sqCompletion/sqFaultCompletion -- see the ready/apply/flush block
    // below (near `deferCompletion`/`pendMem`). ROB retire-eligibility (port 4 /
    // `completes(robId)`) must not race ahead of the deferred wbObs replay that
    // shares the SAME robId -- a real observed corruption
    // (`bsr-loop-mispredict`): `completes()` firing off the RAW `sq.io.
    // sqCompletion` let the ROB retire (and, once retired, its physical robId
    // slot could be REALLOCATED to a brand-new, later instruction) BEFORE a
    // collision-delayed replay had actually applied -- landing the STALE replay
    // on the wrong (by-then-reallocated) robId. Idle-default here; driven from
    // the SAME apply event as the wbObs replay so the two can never separate.
    sqCompletionPort.valid   := False
    sqCompletionPort.payload := U(0, m68k040.Global.ROB_ID_W_DEFAULT bits)
    sqFaultCompletionPort.valid := False
    sqFaultCompletionPort.payload.assignDontCare()
    preciseDrainBusySig    := sq.io.preciseDrainBusy
    // Sim-only tap (Part 127): the width of a precise store's drain window is the
    // POSITIVE CONTROL for every D-side-latency lock-step result -- a `dcfg` that
    // silently failed to take effect would leave this at the ~2-cycle zero-latency
    // value and make those results vacuous. Zero synth impact, same convention as
    // `sqCompletionPort.simPublic()` immediately below.
    preciseDrainBusySig.simPublic()
    // Sim-only tap: the lock-step/fuzz/IPC-bench harnesses read sqCompletionPort
    // directly (to synthesize a placeholder Wb record for a precise store's
    // completion, since no lsEu.logic.wbObs pulse accompanies it) -- needs
    // simPublic() like every other debug-only tap in this file.
    sqCompletionPort.simPublic()

    // ---- S0: read operands ----
    val u0 = issuePort.payload.uop
    rdBase.addr := u0.psrcA
    rdData.addr := u0.psrcB
    // Address = base + disp. Absolute / PC-relative EAs carry NO base register
    // (psrcAValid=false; the assembler folded the absolute/PC value into imm), so
    // the base contribution must be ZERO there — otherwise the stale psrcA (which
    // defaults to physreg 0) would corrupt the computed address.
    // FMax: the AGU BASE operand crosses the ALU->LS boundary COMBINATIONALLY — the
    // ALU EU's S1 result bypasses (intByp) into rdBase.data the same cycle a dependent
    // LS µop reads it. Computing `va0 = base + disp` here in S0 therefore chained the
    // ALU datapath adder + the AGU adder across the boundary into the `s1Va` flop (the
    // 26-level, 3x CARRY8, -2.375ns OOC worst path: AluEu.s1Src2 -> ALU result ->
    // bypass -> base0 -> va0 -> s1Va). FIX (standing rule: registered module
    // boundaries): REGISTER the bypassed base operand at the LS boundary (`s1Base`),
    // and compute the effective address `s1Va` in S1 off that flop + the shallow
    // (uop-derived, no-bypass) displacement. The ALU->base cone now ENDS at `s1Base`;
    // the AGU adder is a SEPARATE shallow stage (s1Base_reg + disp -> s1Va), not
    // chained onto the ALU's result. (Latency-agnostic: lock-step is instruction-level,
    // and the front context is held until its decision or cache handoff.)
    val base0 = Mux(u0.psrcAValid, rdBase.data.asUInt, U(0, 32 bits))
    // STORE DATA: `imm` (retPC) for a BSR/JSR stack-push (predecrement, srcB invalid),
    // else the (possibly bypassed) source register. A LINK push is ALSO a stkPush but
    // carries the pushed register (the old An) in srcB (srcBValid), so it reads rdData
    // — the `!u0.srcBValid` guard keeps the imm path for BSR/JSR while letting LINK push
    // a register. Captured into `s1Data` at the boundary; it is NOT on the AGU cone
    // (feeds only the SQ entry), so it stays a registered value.
    val data0 = Mux(u0.stkPush && !u0.psrcBValid, u0.imm, rdData.data)   // push: retPC (imm) / LINK: old An (srcB)

    // Brief-format indexed EA: the index register Xn rides psrcC. .W sign-extends the
    // low 16 bits, .L uses the full 32; then shift-left by the scale exponent (0..3 =>
    // *1/2/4/8). Zero when no index (psrcCValid false) so a non-indexed access adds
    // nothing. Read in S0 (off the regfile, possibly bypassed); the scaled term is
    // REGISTERED into s1Index at the S0->S1 boundary so the AGU adder stays a shallow
    // stage off flops (mirrors s1Base) — Xn is an architectural reg read, not on the
    // deep ALU->base bypass cone.
    rdIndex.addr := u0.psrcC
    val idxRaw0  = Mux(u0.indexLong, rdIndex.data.asUInt,
                       rdIndex.data(15 downto 0).asSInt.resize(32).asUInt)
    // Scale shift: 32-bit MODULAR (m68k address arithmetic wraps mod 2^32, matching
    // Musashi). `<<` by the 2-bit exponent keeps 32 bits (overflow bits discarded) —
    // explicit resize so the result width is unambiguously 32 (the s1Index reg width).
    val idxScaled = ((idxRaw0 << u0.indexScale).resize(32))
    val idxTerm0 = Mux(u0.psrcCValid, idxScaled, U(0, 32 bits))

    // ---- access size in bytes (1/2/4) ----
    // A single m68k access spans at most two 16-byte lines / two 4 KB pages, so a
    // single "second access" suffices. Used by the S1 cross-detection (below) and
    // the store byte-lane split.
    def sizeBytes(s: m68k040.isa.Size.C): UInt = {
      val n = UInt(3 bits); n := 1
      switch(s) {
        is(m68k040.isa.Size.BYTE) { n := 1 }
        is(m68k040.isa.Size.WORD) { n := 2 }
        is(m68k040.isa.Size.LONG) { n := 4 }
      }
      n
    }

    // ---- S0 -> S1 register (M2S) ----
    // `s1Base` is the REGISTERED (post-bypass) AGU base operand; the deep ALU->base
    // cone ends here. `s1Data` is the registered store data (off the AGU cone).
    val s1Valid = RegInit(False)
    val s1Ctx   = Reg(IqContext())
    s1Ctx.robId.simPublic() // debug-only observability, task #139 finding #1 investigation; zero synth impact
    val s1Base  = Reg(UInt(32 bits))
    val s1Data  = Reg(Bits(32 bits))
    val s1LateData = lateStoreData.map(p => RegNextWhen(p.issuePending, issuePort.fire) init False)
    // Registered scaled index term (brief-format indexed EA): 0 for a non-indexed access.
    val s1Index = Reg(UInt(32 bits))
    val u1 = s1Ctx.uop

    // ---- S1: AGU effective address (off the REGISTERED base) ----
    // addr = base + disp. STACK-PUSH (BSR/JSR) predecrements: disp = -sizeBytes (the
    // store data is `imm`=retPC, handled via data0 -> s1Data). Otherwise disp = imm.
    // Both come from the registered uop (`u1`) — no bypass — so `s1Va` is a SINGLE
    // shallow adder off the `s1Base` flop, not chained onto the ALU result. `s1Va` is
    // combinational off held flops (s1Base/s1Ctx are held stable while busy), so every
    // FSM consumer (IDLE+ cycles) reads a consistent address; the translated paddr is
    // registered downstream in s2Paddr/s2PaddrB.
    def szBytes1(s: m68k040.isa.Size.C): SInt = {
      val n = SInt(32 bits); n := 1
      switch(s) {
        is(m68k040.isa.Size.BYTE) { n := 1 }
        is(m68k040.isa.Size.WORD) { n := 2 }
        is(m68k040.isa.Size.LONG) { n := 4 }
      }
      n
    }
    // EA auto-update (-(An)/(An)+) access offset:
    //   PREDEC  -> base - eaDelta (the decremented An IS the access address + new An)
    //   POSTINC -> base           (access at An; the An update is base + eaDelta, below)
    // generalizing the stkPush A7 predecrement (-sizeBytes on A7) to any An/delta. A
    // non-auto / non-push µop uses the displacement (`imm`) exactly as before.
    val eaDelta1 = u1.eaDelta.resize(32).asSInt
    val s1Disp = Mux(u1.stkPush, -szBytes1(u1.size),
                 Mux(u1.eaAuto === m68k040.decode.EaAuto.PREDEC,  -eaDelta1,
                 Mux(u1.eaAuto === m68k040.decode.EaAuto.POSTINC,  S(0, 32 bits),
                     u1.imm.asSInt)))
    // Effective address = base + disp + scaled index. The index term is 0 for non-indexed
    // accesses (s1Index latched from idxTerm0, gated by psrcCValid). Indexed modes never
    // carry eaAuto/stkPush (mutually exclusive EA modes), so s1Disp = plain imm (d8 or
    // pc+2+d8) for an indexed µop and s1Index adds the scaled Xn. All three are flops/
    // uop-derived (no ALU-result bypass) -> a single shallow adder, not on the ALU cone.
    val s1Va   = (s1Base.asSInt + s1Disp + s1Index.asSInt).asUInt
    // Debug-only address-capture taps (fuzz cluster E investigation). Naming/observability
    // only -- no hardware, same pattern as s1Ctx.robId/s1AddrB above.
    s1Valid.simPublic(); s1Va.simPublic(); s1Base.simPublic(); s1Disp.simPublic()
    s1Index.simPublic(); s1Data.simPublic(); s1Ctx.uop.pc.simPublic()
    s1Ctx.uop.imm.simPublic(); s1Ctx.uop.useImm.simPublic()
    // Sim-only taps for the committed-store address tripwire (test/lockstep/StoreAddrTripwire.scala):
    // the S0 base operand as read (post-bypass) and its physical source, plus the S1 store
    // context, so a drained store can be traced back to WHERE its base register value came
    // from. Zero synth impact (simPublic only), same convention as the taps above.
    base0.simPublic(); u0.psrcA.simPublic(); u0.psrcAValid.simPublic(); u0.memOp.simPublic()
    u0.pc.simPublic(); u0.pdst.simPublic(); u0.pdstValid.simPublic(); u0.eaAuto.simPublic()
    u0.stkPush.simPublic()
    u1.memOp.simPublic(); u1.psrcA.simPublic(); u1.psrcAValid.simPublic(); u1.eaAuto.simPublic()
    u1.pdst.simPublic(); u1.pdstValid.simPublic()
    // The An write-back value for an auto-update µop (when it carries an int dst):
    //   PREDEC  -> s1Va (= base - eaDelta, the decremented An)
    //   POSTINC -> base + eaDelta
    val s1AnPost = (s1Base + u1.eaDelta).asBits
    val s1AnWb   = Mux(u1.eaAuto === m68k040.decode.EaAuto.POSTINC, s1AnPost, s1Va.asBits)
    s1AnWb.simPublic()   // tripwire tap (see the block above s1Va)
    // A2 fix: the MOVES write µop reads Rn AND folds the (An)+/-(An) auto write-back
    // into the SAME atomic store. When Rn statically aliases the EA's An (decode-time
    // marker, see DecodedUop.movesAliasStore), Musashi's actual byte-written value is
    // the AUTO-UPDATED An (the EA calc mutates An first, then the store reads it) — NOT
    // the raw Rn register read (which, read at S0 from the SAME physical register,
    // still reflects the pre-this-µop value). s1AnWb is already the exact value the An
    // write-back itself commits this cycle; reuse it as the store data instead of
    // inventing a second adder.
    val s1StoreData = Mux(u1.movesAliasStore, s1AnWb, s1Data)

    // ---- AGU cross-line / cross-page detection (S1, off s1Va) ----
    // The cross-detection / next-line base USED to be computed in S0 off the
    // combinational `va0` (= base+disp) and latched into s1* the same cycle, which —
    // because `base0` bypasses combinationally from the ALU — fused the +16 / cross
    // compares onto the ALU->va chain (2 of the original 4 chained CARRY8 adders). They
    // are now derived off `s1Va` (itself a shallow adder off the `s1Base` flop), so the
    // next-line base / cross compares are a SEPARATE shallow stage downstream of the
    // registered base — NOT chained onto the ALU result. `s1Va` is stable across the
    // FSM (s1Base is held while busy; issue.ready gates a new latch), so every FSM
    // consumer (IDLE+ cycles) reads consistent values; the slot-B paddr is registered
    // downstream in `s2PaddrB` (IDLE). simPublic for directed probing (AguCrossSpec
    // samples one cycle after the S1 latch, where s1Base/s1Va are valid+stable, so the
    // comb values match the old registered ones exactly).
    val nBytes1    = sizeBytes(u1.size)
    val lineOff1   = s1Va(3 downto 0)
    val pageOff1   = s1Va(11 downto 0)
    val s1CrossLine = (lineOff1 +^ nBytes1) > U(16)
    val s1CrossPage = (pageOff1 +^ nBytes1) > U(4096)
    val s1TwoAccess = s1CrossLine || s1CrossPage
    // addrB = next line base = (s1Va & ~15) + 16. When crossPage this equals the next
    // page base (the line that crosses the page boundary is the page-aligned first
    // line of the next page).
    val s1AddrB     = (s1Va & ~U(15, 32 bits)) + 16
    s1CrossLine.simPublic(); s1CrossPage.simPublic(); s1TwoAccess.simPublic(); s1AddrB.simPublic()

    // ── MOVEM far-page probe (task movem-translate-ahead) ──────────────────────
    // Closes docs/BUG_movem_midlist_load_fault_no_rollback.md: a LOAD-direction
    // MOVEM whose register-list span crosses a page boundary must not let ANY
    // element commit if the far (second) page is going to fault -- otherwise an
    // earlier element's per-uop retire (already irrevocable the instant it
    // happens; see the bug doc's root-cause section) leaves it visibly loaded even
    // though a later element in the SAME macro then faults. `u1.faultVector`
    // carries the macro's total element count (repurposed; see
    // MicroOpAssembler.movemMoveUop's doc comment) on ONLY the first emitted
    // element of a LOAD MOVEM macro; 0 on every other uop (every non-MOVEM access,
    // every non-first MOVEM element, and every STORE-direction MOVEM element --
    // STORE deliberately does NOT get this treatment, see the doc comment there).
    val movemProbeCount  = u1.faultVector.resize(5 bits)             // 0..16; 0 = not a probe carrier
    val movemProbeActive = (u1.memOp === MemOp.LOAD) && (movemProbeCount =/= 0)
    val movemElemLong    = u1.size === m68k040.isa.Size.LONG
    // Elements are packed contiguously at s1Va + k*elemSize (k = 0..count-1, in
    // MASK order -- i.e. emission order -- not architectural register-number
    // order; true regardless of a sparse mask, since MOVEM always packs the
    // transferred registers back-to-back in memory by list position). The count-1
    // scale-by-elemSize is a compile-time-constant shift (2 or 4), never a real
    // multiplier: `<<2`/`<<1` selected by size, mirroring `movemCycleDelta`'s own
    // shift-not-multiply comment in DecodeStage.scala.
    val movemCnt1 = Mux(movemProbeCount === 0, U(0, 8 bits),
      (movemProbeCount.resize(8 bits) - U(1, 8 bits)))                 // elements AFTER the first: 0..15
    val movemSpanFromFirst = Mux(movemElemLong, (movemCnt1 << 2), (movemCnt1 << 1)).resize(32)
    val movemAddrHi        = s1Va + movemSpanFromFirst                 // the LAST element's own address
    // Page-crossing check: a FIXED 4K boundary (mirrors `s1CrossPage`'s own
    // existing 4K-only check just above -- same file, same established pattern).
    // Task #195's TCR.P (8K pages) does NOT need to be threaded through here: a
    // real 8K boundary is ALWAYS also a 4K boundary (8192 is a multiple of 4096),
    // so this check never MISSES a genuine crossing or computes a wrong crossing
    // address for one; in 8K mode it can only ever fire on a 4K sub-boundary that
    // isn't a true page edge, costing one harmless extra DTLB round trip on an
    // already-rare page-crossing MOVEM (never a false fault: a VPN slice that maps
    // into the SAME real 8K page as the first element resolves identically to it).
    // Skipped entirely when `s1TwoAccess` is ALREADY true for the FIRST element's
    // OWN reasons (its single 2/4-byte access itself straddles a page): in that
    // (astronomically rare) case the macro's total span still can't exceed 2
    // pages (<=64 bytes < any real page size), and the EXISTING per-access split
    // already translates both of them, so nothing new is needed.
    val movemCrosses = movemProbeActive && !s1TwoAccess &&
                        (s1Va(31 downto 12) =/= movemAddrHi(31 downto 12))
    // The exact address of the FIRST element landing on the far page -- NOT
    // `movemAddrHi` (the LAST element), which would report the WRONG fault EA
    // whenever more than one element lands on the far page (e.g. the pinned
    // lock-step test: D0@0x2ff8/D1@0x2ffc resident, D2@0x3000/D3@0x3004 non-
    // resident -- the correct faultAddr is D2's 0x3000, not D3's 0x3004). Musashi
    // (and this core's own per-uop translate, were it allowed to run) would fault
    // AT that first far-page element, so this is the exact value a genuine
    // per-element translate of it would also produce -- not an approximation.
    val movemFarPageStart = (movemAddrHi(31 downto 12) ## U(0, 12 bits)).asUInt
    val movemDiff         = (movemFarPageStart - s1Va).resize(8 bits)      // 1..63 whenever movemCrosses
    val movemDiffRounded  = Mux(movemElemLong,
      (movemDiff + 3) & ~U(3, 8 bits), (movemDiff + 1) & ~U(1, 8 bits))    // round UP to the next elemSize multiple
    val movemCrossAddr    = s1Va + movemDiffRounded.resize(32)
    movemCrosses.simPublic(); movemCrossAddr.simPublic()

    // ── D1 elastic-front context ─────────────────────────────────────────────
    // Carry only fields which remain live after the AGU/register-file boundary.
    // The full 300+ bit RenamedUop stays in P1; P2/P3/P4 replicate this pruned token
    // instead, keeping the area cost of three simultaneously-resident accesses bounded.
    case class FrontPipeCtx() extends Bundle {
      val lateDataPending = if (lateStoreData.nonEmpty) Bool() else null
      val lateDataTag = if (lateStoreData.nonEmpty) UInt(6 bits) else null
      val robId           = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
      val vaddr           = UInt(32 bits)
      val addrB           = UInt(32 bits)
      val storeData       = Bits(32 bits)
      val anWb            = Bits(32 bits)
      val storeNzvc       = Bits(4 bits)
      val size            = m68k040.isa.Size()
      val memOp           = MemOp()
      val twoAccess       = Bool()
      val pdst            = UInt(6 bits)
      val pdstValid       = Bool()
      val dstArch         = UInt(5 bits)
      val stkPush         = Bool()
      val autoStoreAn     = Bool()
      val ccrRestore      = Bool()
      val signExtW        = Bool()
      val leaAddr         = Bool()
      val writesNzvc      = Bool()
      val pNzvcDst        = UInt(nzvcW.address.getWidth bits)
      val writesX         = Bool()
      val pXDst           = UInt(xW.address.getWidth bits)
      val crackDrop       = Bool()
      val keepCommit      = Bool()
      val needsSupervisor = Bool()
      val supervisor      = Bool()
      // The ADDRESS-SPACE bit this access translates and protection-checks under.
      // Identical to `supervisor` for every instruction except MOVES, whose data
      // access runs in the alternate address space named by SFC (read form) or DFC
      // (write form) -- FC[2] is the supervisor/user selector. See
      // DecodedUop.altAddrSpace.
      //
      // WHY IT IS A SEPARATE FIELD FROM `supervisor` RATHER THAN A REPLACEMENT OF IT.
      // `supervisor` is ALSO the operand of two PRIVILEGE checks (`storePrivBlocked`
      // and `txSuppressForLaterPrivCheck`, both `needsSupervisor && !supervisor`) and
      // of the fault completion's SSW bit. MOVES is privileged no matter what DFC
      // holds, so folding the function code into `supervisor` would make a USER-mode
      // `moves.l %d0,(%a0)` with DFC=5 look supervisor to the vector-8 check and skip
      // its privilege violation -- trading an address-space bug for a protection
      // hole. The two bits are genuinely different questions ("who is running" vs
      // "which space is being addressed") and are kept apart.
      //
      // COST ON THE TRANSLATE PATH: none. This is computed in the S1 capture cycle
      // and consumed at `reqDrvSup` by SUBSTITUTION -- the existing
      // `Mux(reqFromSplit, txCtx.*, tCtx.*)` 2:1 mux keeps exactly the shape it had,
      // with both arms still plain register outputs. No gate is added between a
      // register and the DTLB request.
      val fcSup           = Bool()
      // MOVEM far-page probe (task movem-translate-ahead): True iff `twoAccess`/
      // `addrB` on THIS entry were set for a translate-ONLY probe of the macro's
      // far boundary page, not a genuine second data access. Read exactly once, at
      // `txOut` construction below, to suppress `twoAccess` before it ever reaches
      // P3/P4 (the real split-access dispatch: `alignedEnqSplit`, the SQ split-
      // store alloc, the split-forward query, ...) — none of those must ever see a
      // MOVEM probe as a real access. The DTLB round-trip itself (the ONLY thing
      // that must happen for a probe) is already complete by the time `txOut` is
      // built: it reuses the SAME `txSecond`/`xlateBArm` second-round-trip FSM a
      // genuine split uses, and a fault found in it is delivered via the SAME
      // `captureFaultFront(txCtx, txCtx.addrB)` call a genuine split's B-side fault
      // already uses (see the `xlateFault` branch below) — unmodified in both
      // cases. Only the NON-fault, "let the access continue" branch needs this
      // extra bit, to stop a probe-only success from turning into a real 2nd access.
      val probeOnlyB      = Bool()
    }
    case class XlatePipeCtx() extends Bundle {
      val front  = FrontPipeCtx()
      val paddr  = UInt(32 bits)
      val paddrB = UInt(32 bits)
      val cmode  = m68k040.cache.CacheMode()
      val cmodeB = m68k040.cache.CacheMode()
    }
    case class ResolvePipeCtx() extends Bundle {
      val xlate    = XlatePipeCtx()
      val fwdHit   = Bool()
      val fwdStall = Bool()
      val fwdData  = Bits(32 bits)
      // The captured verdict was taken under the SQ's INHIBITED serialization barrier
      // (`SqFwdRsp.serial`): `fwdHit` is masked and NOT trustworthy. p4 keeps
      // re-querying (the query mux selects p4) until a verdict is captured with the
      // barrier absent; only then may it forward or launch. Without this, a load whose
      // exact-match older store sat behind an older inhibited store parked on
      // `olderInhibitedStore` holding hit=0/stall=0 and, when the barrier lifted,
      // launched to the D-cache while the store was still undrained -- returning the
      // slot's previous generation (ROM 0x408990E2 `move.l (sp),d0` after nine 53C96
      // FIFO stores + `jsr`, caught on hardware 2026-09-08).
      val fwdSerial = Bool()
    }

    val tValid   = RegInit(False)              // P2: registered DTLB + VIPT request
    val tCtx     = Reg(FrontPipeCtx())
    val txValid  = RegInit(False)              // P2T: accepted request awaiting response
    val txCtx    = Reg(FrontPipeCtx())
    val txSecond = RegInit(False)              // rare split: response/command is addrB
    val txWaitingRsp = RegInit(False)
    val txPaddrA = Reg(UInt(32 bits))
    val txCmodeA = Reg(m68k040.cache.CacheMode())
    val txToken  = Reg(UInt(DTranslationToken.Width bits))
    val xlateEpoch = RegInit(False)
    val p3Valid  = RegInit(False)              // P3: registered PA -> SQ query/store
    val p3Ctx    = Reg(XlatePipeCtx())
    val p4Valid  = RegInit(False)              // P4: registered SQ response -> resolve
    val p4Ctx    = Reg(ResolvePipeCtx())
    // ── FMax (2026-09-16): a REGISTERED shadow of the SQ forward-query MUX SELECT.
    //
    // The select is `p4RetryQuery == p4Valid && (fwdStall || (fwdHit && twoAccess) ||
    // fwdSerial)`. Every operand is already a flop, so this is not a depth problem --
    // it is a ONE-LUT-plus-a-fanout-156-net problem sitting in FRONT of the deepest
    // combinational cone in the LS EU (StoreQueue's 8-entry line+mask compare, ~12
    // levels). The post-route report for this checkpoint:
    //
    //   p4Ctx_fwdStall_reg/C -> LUT5 (p4RetryQuery) -> [fo=156, 0.432 ns]
    //     -> LUT3 (fwdQueryCtx.paddr mux) -> [fo=16, 0.597 ns] -> sq perEntry compare
    //     -> ... -> p4Ctx_fwdHit_reg/D          15 levels, 72.9% route, -0.313 ns
    //
    // i.e. ~0.65 ns of the 5.17 ns arc is spent deciding WHICH registered address to
    // compare, before the compare starts. Precomputing the select into its own flop
    // deletes the LUT5 and its high-fanout net from the arc and, being a flop, lets
    // `phys_opt_design` replicate it across the mux's 156 sinks (it cannot usefully
    // replicate a LUT whose inputs are five separate flops).
    //
    // COST: one flop plus the duplicated retry term (~2 LUTs). ZERO cycles -- this is
    // a same-cycle value, not a pipeline stage; load-use latency is unchanged.
    //
    // CORRECTNESS. This register is a MIRROR: it is assigned at exactly the four sites
    // that change `p4Valid` or the three `fwd*` fields it reads, in the same source
    // (= generated always-block) order, so last-assignment-wins resolves identically.
    // Divergence would mean the SQ is queried with the WRONG context's address and a
    // forward verdict captured for a different address -- the silent-wrong-data class
    // (cf. the 2026-09-08 `fwdSerial` bug recorded on `ResolvePipeCtx`). So the
    // original expression is retained as `p4RetryQueryRef` and machine-checked against
    // this register every cycle under `GenerationFlags.simulation`; the shadow is
    // pruned from every netlist and fires in every LS/cache/lockstep suite.
    val p4RetryQ = RegInit(False)
    tValid.simPublic(); txValid.simPublic(); p3Valid.simPublic(); p4Valid.simPublic(); txSecond.simPublic()
    tCtx.robId.simPublic(); txCtx.robId.simPublic(); p3Ctx.front.robId.simPublic(); p4Ctx.xlate.front.robId.simPublic()
    // Debug-only taps (zero synth impact): the p4 load's physical address and the SQ
    // forward verdict it is holding -- the stale-forward-verdict regression test reads
    // these to prove a load launched to the cache while its producing store was resident.
    p4Ctx.xlate.paddr.simPublic(); p4Ctx.fwdHit.simPublic(); p4Ctx.fwdStall.simPublic()
    p4Ctx.fwdSerial.simPublic()

    // ---- translate-at-execute: the LS EU DRIVES the D-side translation port ----
    // It presents the access VPN (loadVaddr, which the FSM sets to s1Va for slot A
    // or s1AddrB for slot B), the access class (write?=store, supervisor?), and
    // `valid` (a real demand). In parallel, a load may launch a tokenized D-cache
    // virtual-set probe. The LS EU alone consumes rsp, registers the resolved PA and
    // cache mode, and later presents them in DLoadCmd; the D-cache never samples this
    // tagged response. On a DTLB miss the response Stream remains invalid while the
    // walker runs; P2T holds the matching context and P2 may retain one younger op.
    // (request drivers are set after the stage controls are declared below.)
    // Task #195: PA = PPN ## page-offset, but the offset is 12 bits (VA[11:0]) for
    // 4K pages and 13 bits (VA[12:0]) for 8K pages — VA[12] moves from "PPN's LSB"
    // to "top bit of the offset" when TCR.P=1. The 8K-mode PPN slice drops
    // `ppn(0)` (the raw page descriptor's bit 12, architecturally undefined for 8K
    // pages per the MC68040 UM — PA[12] instead comes straight from the
    // untranslated VA[12], same as any other offset bit). `is8K` is a plain
    // MmuControlPlugin register read (same cost/shape as `mmuEnable` already read
    // on this same cone below); this Mux is a static width-select on an existing
    // combinational concat that already registers into `p3Ctx` the next cycle —
    // it adds no new pipeline stage.
    val s1Paddr = Mux(is8K,
      (xlate.rsp.payload.ppn(19 downto 1) ## txCtx.vaddr(12 downto 0)).asUInt,
      (xlate.rsp.payload.ppn ## txCtx.vaddr(11 downto 0)).asUInt)
    // Slot-B (split-access second half) translated physical address: SAME `xlate.rsp`
    // port, combined with addrB's OWN page offset (not s1Va's — a line-crossing split
    // stays within the same page but at a different offset; only a page-crossing
    // split shares offset 0). Only meaningful the cycle the LIVE xlate request/
    // response actually corresponds to addrB's VPN (the XLATE_B FSM state below arms
    // this via `xlateBArm` -> `xlateVaddr`, mirroring exactly how `s1Paddr` above is
    // only meaningful while IDLE is resolving slot A's request).
    val s1PaddrB = Mux(is8K,
      (xlate.rsp.payload.ppn(19 downto 1) ## txCtx.addrB(12 downto 0)).asUInt,
      (xlate.rsp.payload.ppn ## txCtx.addrB(11 downto 0)).asUInt)
    val xlateReady = xlate.rsp.valid
    val xlateFault = xlate.rsp.payload.fault

    // ─────────────────────────────────────────────────────────────────────────
    // FMax #3: PIPELINE the DTLB lookup -> SQ-forward / store-alloc.
    //
    // With mmuEnable LIVE the DTLB hit-path lookup (s1Va -> banked way-mux -> ppn)
    // sits IN FRONT of the s1Paddr that feeds the 8-entry SQ overlap-compare reduce
    // (-> fwdData). That fused arc (s1Va_reg -> DTLB -> s1Paddr -> SQ-compare ->
    // fwdData_reg) was the 25-level / 6.349ns critical path (157MHz). We REGISTER
    // the translated physical address (+perm fault) in a dedicated translate stage
    // (XLATE), so the SQ-forward query / store-alloc consume the REGISTERED paddr
    // the NEXT cycle. This splits the arc into
    //   s1Va_reg -> DTLB lookup -> s2Paddr_reg                 (translate stage)
    //   s2Paddr_reg -> SQ-compare -> fwd*_reg / alloc          (forward stage)
    // at the cost of ONE extra translate-latency cycle (lock-step is latency-
    // agnostic; the one-resident front holds a DTLB miss while an older cache access
    // may remain in the independent back stage). Identity flows through the SAME register so both
    // modes are pipelined uniformly. All RegInit / Reg (no uninit fanout).
    // Historical signal names retained as aliases for directed tests and comments.
    // D1 makes the owning storage explicit in P3's `p3Ctx`.
    val s2Paddr  = p3Ctx.paddr
    val s2PaddrB = p3Ctx.paddrB
    val s2Cmode  = p3Ctx.cmode
    val s2CmodeB = p3Ctx.cmodeB

    // Task P2.2: fast := mmuEnabled && cacheable(s2Cmode) — a pure combinational
    // read of already-latched architectural facts (mmuEnable is live config state;
    // s2Cmode is REGISTERED at XLATE, same stage s2Paddr is available), no
    // probe/filter/history. CACR.DE is NOT tested directly here (Task P5.6): it is
    // folded into s2Cmode's own capture upstream (s2Cmode reads as INHIBITED
    // whenever DE=0, regardless of the page's own attribute), so this expression
    // needs only 2 terms, not 3. A store for which this is False (MMU-off,
    // INHIBITED, or DE=0-via-s2Cmode) is a PRECISE-path store: the XLATE/WAIT_SQ
    // store arms below allocate it into the SQ but withhold `captureCompletion` —
    // its ROB completion instead comes later from the SQ's at-head drain (Task
    // P2.4).
    val fastStore = mmuCtrl2.map(_.mmuEnable).getOrElse(False) &&
                    (p3Ctx.cmode =/= m68k040.cache.CacheMode.INHIBITED) &&
                    (!p3Ctx.front.twoAccess ||
                     (p3Ctx.cmodeB =/= m68k040.cache.CacheMode.INHIBITED))
    // Root-cause fix (post-Task-P2.5 lock-step investigation): a privileged STORE
    // (e.g. MOVES.L Dn,<ea>) executed in user mode must NEVER let its memory write
    // reach the SQ at all -- mirrors the EXISTING `suppressForLaterPrivCheck`
    // pattern above (line ~1254, same `u1.needsSupervisor && !u1.supervisor`
    // check, there for a memory-SOURCE sysOp's leading load) for the memory-DEST
    // case. Why not instead gate the SQ's at-head drain (`headPreciseReady`) on
    // `!privViolation`? Tried first -- deadlocks: RobPlugin's `privViolation`
    // itself requires `headReady` (== `completes(h0)`), which for a PRECISE store
    // is driven EXCLUSIVELY by this very drain completing -- a genuine circular
    // dependency once the drain is blocked pending a violation-check that can
    // itself never resolve without the (now-blocked) drain. The PRE-P2 baseline
    // was never exposed to this: `captureCompletion` fired eagerly at alloc
    // (completes() immediately, matching normal instructions), while the
    // ACTUAL WRITE waited on `committed(head)` (itself downstream of `retire0`,
    // already gated `!privViolation`) -- i.e. completes()-the-bookkeeping and
    // write-the-memory were ALREADY decoupled, just not through the SQ's new
    // precise-path proxy. This restores exactly that decoupling for the one case
    // that needs it: complete immediately (matching every other instruction; the
    // orphaned register/flag effects are invisible either way -- retire0 excludes
    // `privViolation`, and the flush that follows rolls back the speculative
    // rename mapping, the SAME mechanism that already hides every wrong-path
    // effect in this OoO design) but skip `sq.io.alloc` ENTIRELY, so no memory
    // write is ever queued.
    val storePrivBlocked = p3Ctx.front.needsSupervisor && !p3Ctx.front.supervisor
    // Debug-only observability (mirrors compValid/compRobId's task #139 taps just
    // below): zero synth impact, lets a directed test inspect the classification
    // decision directly instead of inferring it from completion timing alone.
    fastStore.simPublic(); s2Cmode.simPublic()

    // ─────────────────────────────────────────────────────────────────────────
    // FMax #1: REGISTER the AGU effective address at the D-cache boundary.
    //
    // `loadCmd.vaddr` was driven LIVE from `s1Va` (= s1Base + s1Disp(eaDelta/eaAuto/
    // stkPush) + s1Index). That AGU sum fanned combinationally across the LS-EU ->
    // D-cache module boundary into the cache's BRAM tag/data read-address (cmdSet/
    // cmdTag -> rdSet) — the route-dominated worst path (s1Ctx_uop_{eaDelta,eaAuto,
    // stkPush}/C -> DcachePlugin tagMem/D). CAPTURE the resolved access {vaddr,paddr,
    // addrB,paddrB,size,twoAccess} into `llReg` flops the cycle the FSM decides to go
    // to the cache (the new LAUNCH state), and drive `loadCmd` from THOSE flops the
    // next cycle. The cache now tags on a REGISTERED address (flop -> loadCmd ->
    // cmdSet); the AGU adder ends at the llReg flop. The handoff captures every source
    // before s1* may advance, and the independent front hides this +1 launch cycle when
    // useful. All RegInit / Reg (no uninit fanout).
    // Task (ring-drain fix): split loads no longer launch through this register --
    // see the class-level comment on `AlignedLoadCtx` further down. Nothing writes
    // `vaddr`/`paddr`/`addrB`/`paddrB`/`size`/`cmode`/`cmodeB`/`robId` any more (their
    // only writer was the P4 split-launch arm this task removed), so each now needs
    // its own `init` -- otherwise SpinalHDL rejects the register as never-assigned.
    // Kept, rather than deleted, as harmless dead infrastructure alongside `bkFsm`
    // (see that comment for why).
    val llReg = new Area {
      val valid     = RegInit(False)
      val vaddr     = Reg(UInt(32 bits)) init 0
      val paddr     = Reg(UInt(32 bits)) init 0
      val addrB     = Reg(UInt(32 bits)) init 0
      val paddrB    = Reg(UInt(32 bits)) init 0
      val size      = Reg(m68k040.isa.Size()) init m68k040.isa.Size.BYTE
      val cmode     = Reg(m68k040.cache.CacheMode()) init m68k040.cache.CacheMode.INHIBITED
      val cmodeB    = Reg(m68k040.cache.CacheMode()) init m68k040.cache.CacheMode.INHIBITED
      val robId     = Reg(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)) init 0
      val twoAccess = RegInit(False)
      val bDone     = RegInit(False)   // slot A launched; now presenting slot B (cross)
    }
    // ── Why the tag (2026-09-18) ────────────────────────────────────────────────
    // The comment above records that the split-launch arm that used to WRITE
    // vaddr/paddr/addrB/paddrB/size/cmode/cmodeB/robId was removed, and that an `init`
    // was added so "SpinalHDL does not reject the register as never-assigned". An init
    // alone does NOT satisfy that check: SpinalHDL still raises UNASSIGNED REGISTER and
    // asks for exactly this tag. Because these are still READ (by the equally dead
    // `bkFsm`, e.g. `captureCompletionDesc(bkCtx, result, llReg.size)`) they survive
    // pruning, so the check fires -- but ONLY when the design is elaborated with
    // `.includeSimulation` (`M68kSim()`), which is why the SYNTHESIS flow builds fine
    // and every full-core SIMULATION harness does not.
    //
    // Effect on hardware: NONE. A register with an init and no driver is a constant
    // either way; the tag only tells SpinalHDL the absence of a driver is intentional.
    // Without it, `FuzzCoreDut` -- the DUT behind BOTH the 933-program ported corpus
    // (`PortedTestRunner.compiled`) and the Musashi lock-step fuzzer
    // (`FuzzRunner.compiled`) -- fails elaboration with 20 errors, so neither whole-core
    // correctness net can run at all.
    llReg.vaddr.allowUnsetRegToAvoidLatch
    llReg.paddr.allowUnsetRegToAvoidLatch
    llReg.addrB.allowUnsetRegToAvoidLatch
    llReg.paddrB.allowUnsetRegToAvoidLatch
    llReg.size.allowUnsetRegToAvoidLatch
    llReg.cmode.allowUnsetRegToAvoidLatch
    llReg.cmodeB.allowUnsetRegToAvoidLatch
    llReg.robId.allowUnsetRegToAvoidLatch

    // ── BACK-STAGE completion descriptor (spec §3.2(c)) ──────────────────────
    // Everything `captureCompletion`/`captureFault` read from `u1`/`s1Ctx`/`xlate`
    // must be captured at the RESOLVE->LAUNCH handoff, because after the handoff the
    // FRONT stage is translating a DIFFERENT µop and every one of those live reads
    // would silently belong to that younger µop.
    //
    // Restricted to what a non-forwarded LOAD can reach: the back stage is only ever
    // entered from RESOLVE, and RESOLVE is only reachable from XLATE's `otherwise`
    // (== !isStore) arm, so `isStore`, the store-only auto-update path, and
    // `compRmwStore`/`compEaAutoDrop` (both require isStore) are provably False here.
    // `u1.leaAddr` is likewise unreachable: IDLE completes an LEA before XLATE.
    // `u1.stkPush` is a store-only marker for the same reason.
    //
    // `lineOff` (= s1Va(3 downto 0)) and `u1.size`, both read by WAIT_B's
    // extractCross, are ALREADY carried as llReg.vaddr(3 downto 0) / llReg.size —
    // no new state for those.
    case class BkCtx() extends Bundle {
      val robId           = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
      val pdst            = UInt(6 bits)
      val pdstValid       = Bool()
      val wakes           = Bool()
      val ccrRestore      = Bool()
      val signExtW        = Bool()   // MOVEM.W load sign-extend (u1.isMovea && size==WORD)
      val dstArch         = UInt(5 bits)
      val writesNzvc      = Bool()
      val pNzvcDst        = UInt(nzvcW.address.getWidth bits)
      val writesX         = Bool()
      val pXDst           = UInt(xW.address.getWidth bits)
      val crackDrop       = Bool()
      val keepCommit      = Bool()
      val needsSupervisor = Bool()
      // spec §3.2(d) — THE correctness fix. Never read a live translation request
      // today (`suppressForLaterPrivCheck`, and `captureFault`'s `compFaultSup`). The
      // registered DTLB request re-captures `reqDrvSup` (the LIVE architectural S bit)
      // EVERY cycle, so once the back stage outlives its own S1 residency those reads
      // report whatever the S bit is NOW, not what it was for THIS access. Capturing it
      // here is not a refactor detail: it is a correctness requirement of the split.
      val xlateSup        = Bool()
    }
    // Same reasoning as `llReg` above: its only writer (`captureBkCtx(bkCtx, ...)`
    // from the P4 split-launch arm) was removed by this task, so it needs an
    // explicit zero init to remain a legal (if now permanently-idle) register.
    val bkCtx      = RegInit(BkCtx().getZero)
    // Dead alongside `bkFsm`/`llReg`: `captureBkCtx(bkCtx, ...)` was its only writer and
    // was removed with the split-launch arm, while `captureCompletionDesc`/
    // `captureFaultDesc` still READ it from the (unreachable) back-stage FSM -- so it is
    // never pruned and SpinalHDL raises UNASSIGNED REGISTER for every field. See the
    // block comment on `llReg`'s identical tags above; behaviour-neutral, and required
    // for any `.includeSimulation` elaboration of a full core to succeed at all.
    bkCtx.flatten.foreach(_.allowUnsetRegToAvoidLatch)
    // Rare split replay occupied. Aligned requests never set this bit; their untagged
    // response association is owned by the ordered descriptor ring below.
    val bkBusy     = RegInit(False); bkBusy.simPublic()
    // Split replay poison is separate from the resident front poison: a new front
    // issue must never clear an older split access still draining in the back stage.
    // The aligned ring carries one poison bit per descriptor instead.
    val bkPoisoned = RegInit(False); bkPoisoned.simPublic()
    // Combinational handoff pulse: the front's RESOLVE asserts it, the back's BK_IDLE
    // consumes it in the SAME cycle, so the back reaches LAUNCH on exactly the cycle
    // the pre-split FSM did (a Reg-based handshake would cost one extra cycle).
    val bkStart    = Bool(); bkStart := False; bkStart.simPublic()

    // ── Aligned-load command/response descriptor queue (full-pipeline spec C3) ──
    //
    // The D-cache accepts and returns resident aligned hits at II=1, in order, but
    // DLoadRsp deliberately carries no id.  The former single bkCtx/BK_WAIT slot
    // threw that capacity away: the next load could not leave RESOLVE until the
    // previous response arrived.  Keep four *pruned* descriptors in acceptance
    // order instead.  `alignedSend` identifies the oldest descriptor whose command
    // has not handshaken; `alignedRsp` identifies the oldest accepted command whose
    // untagged response has not arrived.  A miss may stretch the distance between
    // them, but cannot reorder either pointer (the cache's miss-shadow contract is
    // also in order).
    //
    // Split (misaligned / line-or-page-crossing) accesses ALSO use this ring now
    // (task: close the P3 ring-drain-then-serialize gap). A split load pushes TWO
    // contiguous descriptors in the SAME cycle -- slot A (`twoAccess=True,
    // splitSecond=False`, the low half) immediately followed by slot B
    // (`twoAccess=True, splitSecond=True`, the high half that performs the merge
    // and completes the instruction). Both halves of one pair are ALWAYS pushed
    // atomically at `alignedPushPtr`/`alignedPushPtr+1`, so they are always
    // physically contiguous in ring order -- nothing can ever be pushed between
    // them. That invariant is what the response-side pairing
    // (`alignedRspIsSplitA` / the `alignedRspAbortsPair` cancel, which reaches slot B
    // as `alignedRspPtr + 1`) leans on, and it is sound there because slot B provably
    // cannot have popped before its own slot A, so index `rspPtr + 1` is still slot B.
    //
    // It is NOT, however, sound in the other direction: "the entry at slot B's index
    // MINUS one is its slot A" is FALSE, because once slot A pops its index is free
    // and a newer, unrelated descriptor can be pushed onto it while slot B is still
    // unsent. The send-side hold (`alignedSendHeld` below) originally made exactly
    // that mistake and deadlocked the core on real hardware; it now tests the
    // POINTERS (`alignedRspPtr =/= alignedSendPtr`) instead. See the full proof and
    // the hardware repro in the comment on `alignedSlotAPending` below.
    // This replaces the old design where a split
    // access first drained the ENTIRE ring (`alignedEmpty`), then ran a completely
    // separate serial `bkFsm` (LAUNCH -> WAIT_A -> WAIT_B) outside the ring
    // entirely. `bkFsm`/`llReg`/`bkCtx`/`bkBusy` (declared above) are kept as
    // structurally-present but now-electrically-dead infrastructure -- nothing
    // drives `bkStart` any more -- rather than ripped out, since several other
    // signals (`useSplitCmd`, debug taps, `inhibitedLoadBusySig`'s sibling terms)
    // already degrade safely to their idle default once `bkBusy` never asserts.
    case class AlignedLoadCtx() extends Bundle {
      val bk        = BkCtx()
      val vaddr     = UInt(32 bits)
      val paddr     = UInt(32 bits)
      val size      = m68k040.isa.Size()
      val cmode     = m68k040.cache.CacheMode()
      val twoAccess   = Bool()   // this entry is one half of a split-load pair
      val splitSecond = Bool()   // False = slot A (low half); True = slot B (high half, merges+completes)
      // Slot B only: slot A's OWN byte offset within its line (its vaddr(3:0)).
      // Slot B's own `vaddr` is line-aligned (offset 0 by construction -- it is
      // literally the next line/page base), so the original access offset needed
      // by `DcacheByteLane.extractCross` at merge time would otherwise be lost.
      val mergeOff    = UInt(4 bits)
    }
    private val alignedDepth = 4
    private val alignedPtrW  = log2Up(alignedDepth)
    val alignedMem      = Vec.fill(alignedDepth)(Reg(AlignedLoadCtx()))
    val alignedValid    = Vec.fill(alignedDepth)(RegInit(False))
    val alignedSent     = Vec.fill(alignedDepth)(RegInit(False))
    val alignedPoisoned = Vec.fill(alignedDepth)(RegInit(False))
    val alignedPushPtr  = Reg(UInt(alignedPtrW bits)) init 0
    val alignedSendPtr  = Reg(UInt(alignedPtrW bits)) init 0
    val alignedRspPtr   = Reg(UInt(alignedPtrW bits)) init 0
    val alignedCount    = Reg(UInt(log2Up(alignedDepth + 1) bits)) init 0
    val alignedEnq      = Bool(); alignedEnq := False        // single-descriptor push (ordinary aligned load)
    val alignedEnqSplit = Bool(); alignedEnqSplit := False   // two-descriptor push (split-load pair)
    val alignedFull     = alignedCount === alignedDepth
    val alignedEmpty    = alignedCount === 0
    // Slot B may not be SENT until its own slot A (provably the entry immediately
    // behind it in ring order -- see the class-level comment above) has actually
    // been popped from the ring. This is the ONLY thing that still serializes a
    // split access's two sub-transactions -- both for a real architectural reason
    // (a genuine bus fault on slot A must abort the whole access before slot B's
    // read is ever issued -- mirrors the old bkFsm's WAIT_A fault arm exactly, and
    // matters most for an INHIBITED device access, which must never issue two
    // reads for one aborted instruction) and because slot B's merge needs slot A's
    // captured line, which does not exist until slot A resolves. Ordinary
    // (non-split) entries are completely unaffected -- they pipeline normally, and
    // so does slot A of any pair (only slot B is ever held).
    //
    // "Slot A has not been popped yet" is expressed as `alignedRspPtr =/= alignedSendPtr`,
    // NOT as "the slot physically behind slot B is still valid".
    //
    // PROOF that the pointer form is exact. We are deciding whether to SEND the entry
    // at `alignedSendPtr`, so that entry is by definition unsent. `alignedSendPtr`
    // only ever advances past an entry that handshaked a command (`alignedCmdFire`),
    // so slot A -- strictly older than slot B -- has necessarily been SENT. Responses
    // are strict FIFO (the D-cache's in-order contract, stated above), and
    // `alignedRspPtr` walks ring order monotonically, so `alignedRspPtr` reaches slot
    // B's index if and only if every strictly-older entry, slot A included, has
    // already popped. Hence: A pending <=> rspPtr =/= sendPtr. (The one place that
    // moves `alignedSendPtr` non-monotonically, `alignedRspAbortsPair`, retires slot A
    // and slot B TOGETHER, so `alignedSendPtr` can never land on a slot B whose slot A
    // was skipped.)
    //
    // ---- WHY NOT THE POSITIONAL FORM (the bug this replaces) ----
    // This used to read `alignedValid(alignedSendPtr - 1)`, i.e. "is the slot behind me
    // still occupied". That is only a proxy for "my slot A is still pending", and the
    // proxy BREAKS as soon as the ring wraps: once slot A pops, its INDEX is free and
    // the very next push can land a brand-new, completely unrelated descriptor there --
    // re-asserting `alignedValid(alignedSendPtr - 1)` and holding slot B forever.
    // Nothing then ever sends (sends are in-order and blocked at slot B) and nothing
    // ever responds (`alignedRspPtr` points at the unsent slot B), so the ring wedges
    // FULL and the whole core stops retiring.
    //
    // The window is a single cycle wide and trivially reachable: with the ring full,
    // `alignedPushPtr === alignedRspPtr`, and `alignedCanEnq` deliberately allows a
    // push on a cycle where a response pops (`!alignedFull || alignedRspFire`). So on
    // the exact cycle slot A's response fires, an ordinary load pushes onto slot A's
    // own index -- and because the `alignedEnq` write comes AFTER the pop's
    // `alignedValid(alignedRspPtr) := False` in this file, the push's `True` wins.
    // Slot B, still held that same cycle (it reads the OLD `alignedValid`), never gets
    // another chance.
    //
    // OBSERVED ON REAL HARDWARE: `MOVEM.L (SP)+,D1/D2/D4/D5/A0/A2/A3` at PC 0x40815646
    // with ISP = 0x0017FFBA (2 mod 4) wedged a Q700 board permanently -- zero macro
    // retirement, zero bus traffic -- while the same instruction with ISP forced to
    // 0x0017FFB8 ran fine. A MOVEM.L walking a non-longword-aligned base is exactly the
    // access stream that produces the required MIXTURE of split and ordinary loads:
    // successive longwords land at line offsets 10,14,2,6,10,14,2..., so only every
    // fourth one crosses a line. Reproduced in `ExecuteLockStepSpec`'s
    // "MOVEM.L round trip, base N mod 4" matrix; the captured ring state at the wedge
    // was push=send=rsp=1, count=4, entry[1] = an unsent slot B, entry[0] = an ORDINARY
    // (twoAccess=False) descriptor sitting on the popped slot A's index.
    val alignedSlotAPending = alignedRspPtr =/= alignedSendPtr
    // `alignedValid(alignedSendPtr)` is REQUIRED here, not redundant with
    // `alignedSendValid`'s own check below: this val is read unconditionally, and an
    // empty/never-yet-pushed ring slot's `AlignedLoadCtx` fields are an uninitialized
    // register (X in sim, don't-care in synthesis) until its first push -- reading
    // `.twoAccess`/`.splitSecond` off an invalid slot without this guard let simulator
    // register randomization spuriously hold even an ORDINARY (non-split) send.
    val alignedSendHeld = alignedValid(alignedSendPtr) && alignedMem(alignedSendPtr).twoAccess &&
                          alignedMem(alignedSendPtr).splitSecond && alignedSlotAPending
    val alignedSendValid = !alignedEmpty && alignedValid(alignedSendPtr) &&
                           !alignedSent(alignedSendPtr) && !bkBusy && !excActive &&
                           !alignedSendHeld
    val alignedRspValid = !alignedEmpty && alignedValid(alignedRspPtr) &&
                          alignedSent(alignedRspPtr) && !bkBusy
    val alignedRspFire  = alignedRspValid && coreLsRspValid
    val alignedCanEnq   = !bkBusy && (!alignedFull || alignedRspFire)
    // A split pair needs TWO free slots this cycle (accounting for a same-cycle
    // pop exactly like `alignedCanEnq` does for one). This is the actual fix for
    // the ring-drain stall: previously a split load needed the ring FULLY EMPTY
    // (up to a 4-cycle wait); now it needs at most 2 free slots, and often 0 extra
    // cycles at all if the ring already has room.
    val alignedCanEnqSplit = !bkBusy &&
      ((alignedCount <= U(alignedDepth - 2, alignedCount.getWidth bits)) ||
       ((alignedCount === U(alignedDepth - 1, alignedCount.getWidth bits)) && alignedRspFire))
    alignedCount.simPublic(); alignedFull.simPublic(); alignedEnq.simPublic()
    alignedSendValid.simPublic(); alignedRspFire.simPublic(); alignedEnqSplit.simPublic()
    alignedCanEnqSplit.simPublic(); alignedSendHeld.simPublic()
    // Ring bookkeeping observability: needed to diagnose a SEND-side stall from a
    // testbench (which entry is at the send/response head, and whether the slot
    // physically behind slot B is still resident). Added when the misaligned-MOVEM
    // ring-wrap deadlock was root-caused -- `ExecuteLockStepSpec`'s CR_STALL dump
    // reads all of these. simPublic is name-preservation only, no hardware cost.
    alignedSendPtr.simPublic(); alignedRspPtr.simPublic(); alignedPushPtr.simPublic()
    alignedValid.foreach(_.simPublic()); alignedSent.foreach(_.simPublic())
    alignedMem.foreach { e => e.twoAccess.simPublic(); e.splitSecond.simPublic() }
    // ── DEADLOCK TRIPWIRE for the split-load send-hold ────────────────────────────
    // Holding slot B is a WAIT ON SLOT A'S RESPONSE, so it is only ever legitimate
    // while a command is genuinely outstanding on the bus. `alignedRspPtr` names the
    // oldest un-popped entry; when slot B is held it is strictly older than slot B in
    // ring order, and `alignedSendPtr` only advances past entries that handshaked a
    // command -- so that entry MUST be both valid and already sent. If it is ever
    // held while the response head is unsent, no command can complete (responses are
    // in order) and no command can be issued (sends are in order and blocked at slot
    // B): the ring is wedged, combinationally provable, with no timeout needed.
    //
    // This fires INSTANTLY on the exact hardware bug this replaced -- the captured
    // wedge state was send=rsp=1 with `alignedSent(1) = False` -- so any future
    // regression of the same class is caught by the first directed test that hits it
    // rather than by a board that stops retiring. Simulation-only: pruned from every
    // synthesised netlist.
    GenerationFlags.simulation {
      assert(!(alignedSendHeld && !(alignedValid(alignedRspPtr) && alignedSent(alignedRspPtr))),
        "LsEuPlugin: split-load slot B is send-held while the aligned-ring response head " +
        "is not an outstanding (valid+sent) entry -- the hold has latched onto something " +
        "that can never resolve; the ring is deadlocked",
        FAILURE)
    }
    // Observability for the arbitration tests: True the cycle a FRONT completion was
    // suppressed and held because the BACK claimed the shared comp* stage.
    val frontCompHeld = Bool(); frontCompHeld := False; frontCompHeld.simPublic()
    // True the cycle a FRONT stage (P2/P3/P4) actually WROTE the shared comp* stage --
    // set at the single pair of sites that can do so (`captureCompletionFront` /
    // `captureFaultFront`), so it cannot drift from them. Used ONLY by the
    // `preciseReplayClaimsComp` tripwire below (a `GenerationFlags.simulation` block),
    // so it is pruned out of every synthesised netlist.
    val frontCompFires = Bool(); frontCompFires := False; frontCompFires.simPublic()
    // A later flush poisons whatever is draining in the back. Plain component statement:
    // SpinalHDL elaborates StateMachine bodies from a pre-pop task, i.e. AFTER every
    // plain statement, so the FRONT FSM's handoff assignment below correctly WINS on the
    // handoff cycle (where `bkBusy` is still False anyway and this does not fire).
    when(sqFlushSig && bkBusy) { bkPoisoned := True }

    // Slot-B (split-access second half) DTLB translate-request arm (mmu-split-
    // second-half fix): set for the duration of the new XLATE_B FSM state, while
    // resolving addrB's REAL translation. Selects addrB as the LIVE `xlate.req` VPN
    // via `xlateVaddr` below. Deliberately INDEPENDENT of `llReg.bDone` (which
    // selects addrB for the REGISTERED cache-launch command at a later pipeline
    // point, once slot A's line has already landed) — this Reg governs only the
    // DTLB request interface, and only during the translate phase (well before the
    // cache is ever launched for either slot).
    val xlateBArm = txValid && txSecond && !txWaitingRsp

    // ---- dcache load cmd: registered aligned queue (covers ordinary AND split loads) ----
    // Every command -- ordinary aligned, or either half of a split pair -- now comes
    // from `alignedSendPtr` uniformly. `llReg`/`bkFsm` are kept as dead infrastructure
    // (see the class-level comment on `AlignedLoadCtx`) but nothing drives `bkStart`
    // any more, so `useSplitCmd` (= `bkBusy`) is permanently False and this mux
    // always selects the aligned-ring branch. Left in place rather than deleted: it
    // costs nothing once `bkBusy` is a constant (synthesis removes the dead mux arm),
    // and keeps this diff from having to touch every downstream reference of
    // `loadVaddr`/`loadPaddr` for no functional gain.
    // A P4 load with no older unsent command may send while allocating its
    // response descriptor. Admission remains the existing P4 decision: no SQ
    // forward, unresolved dependency, inhibited access or split can take this arm.
    // Never use ready to select the payload; a refused offer becomes a normal
    // queued command with the same token and payload on the following cycle.
    // Select address/token from registered ring state, not the late enqueue
    // permission. Otherwise ROB-age/barrier logic precedes the cache probe CAM
    // and tag/dirty read even though those payload bits already live in P4.
    val alignedFallThroughSelect = if (alignedLoadFallThrough) {
      !alignedFull && (alignedSendPtr === alignedPushPtr)
    } else False
    val alignedFallThrough = alignedFallThroughSelect && alignedEnq &&
      (p4Ctx.xlate.cmode =/= m68k040.cache.CacheMode.INHIBITED)
    alignedFallThrough.simPublic()
    val alignedCmd = AlignedLoadCtx()
    alignedCmd := alignedMem(alignedSendPtr)
    when(alignedFallThroughSelect) {
      alignedCmd.vaddr := p4Ctx.xlate.front.vaddr
      alignedCmd.paddr := p4Ctx.xlate.paddr
      alignedCmd.size := p4Ctx.xlate.front.size
      alignedCmd.cmode := p4Ctx.xlate.cmode
      alignedCmd.twoAccess := False
      alignedCmd.splitSecond := False
      alignedCmd.bk.robId := p4Ctx.xlate.front.robId
    }
    GenerationFlags.simulation {
      assert(!(alignedFallThroughSelect && alignedSendValid),
        "LsEuPlugin: fall-through payload selected over a queued command", FAILURE)
    }
    val useSplitCmd = bkBusy
    val loadVaddr = UInt(32 bits)
    val loadPaddr = UInt(32 bits)
    loadVaddr := Mux(useSplitCmd,
                     Mux(llReg.bDone, llReg.addrB, llReg.vaddr), alignedCmd.vaddr)
    loadPaddr := Mux(useSplitCmd,
                     Mux(llReg.bDone, llReg.paddrB, llReg.paddr), alignedCmd.paddr)
    // ── Owner qualification of the CORE-LS load command (see the arbitration block
    // near the top of `logic`). `coreLsGrant` is a conjunction of two REGISTERED nets,
    // and it is what stops the LS pipe issuing while the port has been handed to a
    // walker. `alignedCmdFire` below MUST carry the same term: `dcache.loadCmd.ready`
    // never references `valid`, so an unqualified `alignedSendValid && ready` would
    // advance the ring past an entry whose command was never actually presented.
    val coreLsGrant     = (ldOwner === U(OWNER_CORE, 2 bits)) && !excLoadCmdValid
    val coreLsLoadReq   = Mux(useSplitCmd, llReg.valid, alignedSendValid || alignedFallThrough)
    val coreLsLoadAdmit = coreLsLoadReq && coreLsGrant && !ldFifoFull
    dcache.loadCmd.valid         := coreLsLoadAdmit
    dcache.loadCmd.payload.vaddr := loadVaddr
    dcache.loadCmd.payload.paddr := loadPaddr
    dcache.loadCmd.payload.size  := Mux(useSplitCmd, llReg.size, alignedCmd.size)
    dcache.loadCmd.payload.cacheMode := Mux(
      useSplitCmd, Mux(llReg.bDone, llReg.cmodeB, llReg.cmode), alignedCmd.cmode)
    // Token layout is a DOCUMENTED contract (DcacheTypes.scala `DLoadToken`): bit [7]
    // = non-LS source, bit [6] = split half, [5:0] = ROB id. The ring path (split pairs
    // pushed through the aligned ring since 03b8ab0e) must stamp bit [6] from the
    // descriptor's own `splitSecond`, exactly as the serial `llReg` path stamps it from
    // `bDone`. 03b8ab0e left it hardwired False, so slot B went out tagged as plain
    // `robId` and the encoding silently diverged from its contract (caught by
    // DtlbCrossPageSplitSpec's exact per-half token check). No consumer misbehaved --
    // the only reader, DcachePlugin's early-probe CAM, also qualifies on vaddr -- but
    // a documented encoding is not something a perf refactor gets to change silently.
    dcache.loadCmd.payload.token := Mux(
      useSplitCmd,
      m68k040.Global.robTag(False ## llReg.bDone, llReg.robId, m68k040.cache.DLoadToken.Width,
                            m68k040.cache.DLoadToken.RobIdBits),
      m68k040.Global.robTag(False ## (alignedCmd.twoAccess && alignedCmd.splitSecond),
       alignedCmd.bk.robId, m68k040.cache.DLoadToken.Width, m68k040.cache.DLoadToken.RobIdBits))
    // 2026-09-09 line-wrap tripwire (see `DLoadCmd.lineOnly`): BOTH halves of a
    // cross-line split pair consume `loadRsp.line`, never `loadRsp.data` -- slot A is
    // deliberately presented at the ORIGINAL crossing offset/size. Flagging them here
    // is what lets `DcacheByteLane.extract`'s wrap assertion stay strict for every
    // other requester (the exception sequencer, the table walker) instead of being
    // weakened to tolerate this one legitimate producer.
    dcache.loadCmd.payload.lineOnly := Mux(useSplitCmd, True, alignedCmd.twoAccess)
    val alignedCmdFire = (alignedSendValid || alignedFallThrough) &&
                          coreLsGrant && !ldFifoFull && dcache.loadCmd.ready
    val alignedFallThroughFire = alignedFallThrough && alignedCmdFire
    alignedCmdFire.simPublic()

    // ---- store split (byte-lane) for the SQ entry ----
    // Slot B's physical address is `s1PaddrB` (declared above alongside `s1Paddr`) —
    // the REAL DTLB translation of addrB's own VPN, resolved by the XLATE_B FSM
    // state before `s2PaddrB` (below) latches it. addrB can land on a different
    // page than s1Va with entirely different perms/residency (mmu-split-second-half
    // fix) — it is NOT assumed identity-mapped.
    // sizeBytes of the store; bytes in slot A = (16 - lineOffSt), spill -> slot B.
    val stOff      = p3Ctx.front.vaddr(3 downto 0)
    val stBytes    = sizeBytes(p3Ctx.front.size)         // 1/2/4
    val bytesInA   = (U(16) - stOff.resize(5 bits))     // 1..16
    val nbytesA_st = Mux(p3Ctx.front.twoAccess, bytesInA.resize(3 bits), stBytes)
    val nbytesB_st = Mux(p3Ctx.front.twoAccess,
                         (stBytes - bytesInA).resize(3 bits), U(0, 3 bits))
    // strb/lineData for both slots are no longer computed here (task #252): they are
    // pure functions of (paddr low nibble, size, data), which the SQ already stores
    // for unrelated reasons -- StoreQueue now re-derives them combinationally at its
    // own drain read point instead of carrying a 288-bit redundant Mem row per entry.

    // ---- SQ alloc + fwd defaults ----
    sq.io.alloc.valid          := False
    sq.io.alloc.payload.robId  := p3Ctx.front.robId
    sq.io.alloc.payload.paddr  := p3Ctx.paddr
    sq.io.alloc.payload.data   := p3Ctx.front.storeData
    sq.io.alloc.payload.size   := p3Ctx.front.size
    sq.io.alloc.payload.nbytesA   := nbytesA_st
    // Aligned store: drain via {data,size} (fast path). Split store: explicit strobe.
    sq.io.alloc.payload.useStrbA  := p3Ctx.front.twoAccess
    sq.io.alloc.payload.validB    := p3Ctx.front.twoAccess
    sq.io.alloc.payload.paddrB    := p3Ctx.paddrB
    sq.io.alloc.payload.nbytesB   := nbytesB_st
    // Precise-path fields (P2): placeholder wiring, replaced for real in Task P2.2
    // (the fast/precise classification). vaddr/vaddrB/cacheMode/supervisor are the
    // real live values already computed above for this access.
    sq.io.alloc.payload.vaddr      := p3Ctx.front.vaddr
    sq.io.alloc.payload.vaddrB     := p3Ctx.front.addrB
    sq.io.alloc.payload.cacheMode  := p3Ctx.cmode
    sq.io.alloc.payload.cacheModeB := p3Ctx.cmodeB
    sq.io.alloc.payload.supervisor := p3Ctx.front.supervisor
    // Task P2.2: the real fast/precise classification (was a `False` placeholder in
    // Task P2.1). Live default off `fastStore`, matching every other field's
    // unconditional-default-before-the-FSM style above; the XLATE/WAIT_SQ
    // alloc-success branches below re-state it explicitly alongside the
    // conditional `captureCompletion` call for readability (same value, harmless
    // last-assignment-wins restatement).
    sq.io.alloc.payload.precise    := !fastStore
    // Store-to-load forwarding is only an architectural optimization for ordinary
    // cacheable memory.  An INHIBITED access denotes serialized/device memory: an
    // overlapping older store must reach the device first, then the younger load
    // must perform a real read so it can observe device state (rather than echoing
    // the store data out of the SQ).  Keep re-querying while such an overlap is
    // resident; when it drains, the normal no-hit path emits the inhibited D-cache
    // command.  For a split access, require both halves to be cacheable.
    val p3SqForwardAllowed =
      (p3Ctx.cmode =/= m68k040.cache.CacheMode.INHIBITED) &&
      (!p3Ctx.front.twoAccess ||
       (p3Ctx.cmodeB =/= m68k040.cache.CacheMode.INHIBITED))
    val p4SqForwardAllowed =
      (p4Ctx.xlate.cmode =/= m68k040.cache.CacheMode.INHIBITED) &&
      (!p4Ctx.xlate.front.twoAccess ||
       (p4Ctx.xlate.cmodeB =/= m68k040.cache.CacheMode.INHIBITED))
    // The REFERENCE expression (what the select means). It has no RTL consumer: the
    // mux below selects off the registered mirror `p4RetryQ`, and this is what that
    // mirror is machine-checked against in simulation (see `p4RetryQ`'s declaration).
    val p4RetryQueryRef = p4Valid &&
      (p4Ctx.fwdStall || (p4Ctx.fwdHit && p4Ctx.xlate.front.twoAccess) || p4Ctx.fwdSerial)
    val p4RetryQuery = p4RetryQ
    p4RetryQuery.simPublic(); p4RetryQueryRef.simPublic()
    GenerationFlags.simulation {
      assert(p4RetryQ === p4RetryQueryRef,
        "LsEuPlugin: the registered SQ forward-query select diverged from its reference " +
        "-- the store-to-load forward query is using the WRONG pipeline context's address",
        FAILURE)
    }
    val fwdQueryCtx = Mux(p4RetryQuery, p4Ctx.xlate, p3Ctx)
    sq.io.fwd.query.robId := fwdQueryCtx.front.robId
    sq.io.fwd.query.paddr := fwdQueryCtx.paddr
    sq.io.fwd.query.size  := fwdQueryCtx.front.size
    // Cross-page forward-hazard fix: carry the query's own independently-translated
    // second half (`paddrB`), gated by `front.twoAccess`, exactly mirroring how
    // `SqAlloc.validB`/`paddrB` are populated a few lines above for a split STORE's
    // own alloc. `fwdQueryCtx.paddrB` is already computed by the same XLATE_B FSM
    // state as `p3Ctx.paddrB` regardless of memOp (a split LOAD's slot B is
    // translated the same way a split STORE's is) -- see `s1PaddrB`'s declaration.
    sq.io.fwd.query.splitB := fwdQueryCtx.front.twoAccess
    sq.io.fwd.query.paddrB := fwdQueryCtx.paddrB
    sq.io.fwd.query.inhibited :=
      (fwdQueryCtx.cmode === m68k040.cache.CacheMode.INHIBITED) ||
      (fwdQueryCtx.front.twoAccess &&
       (fwdQueryCtx.cmodeB === m68k040.cache.CacheMode.INHIBITED))

    val isLoad  = u1.memOp === MemOp.LOAD
    val isStore = u1.memOp === MemOp.STORE

    // ---- tagged elastic D-side translation command ─────────────────────────
    // P2 launches a first-half command atomically with the virtual-set probe. P2T
    // retains its pruned context until the matching response is consumed. A rare
    // split reuses P2T for addrB and takes command priority over a younger P2 op.
    val normalReqArm = Bool(); normalReqArm.allowOverride; normalReqArm := False
    val splitReqArm  = Bool(); splitReqArm.allowOverride;  splitReqArm  := False
    // ── FMax (2026-09-16): the DTLB request PAYLOAD SELECT is `xlateBArm` -- the three
    // P2T flops and nothing else -- NOT `splitReqArm`.
    //
    // `splitReqArm` is `xlateBArm` ANDed with the ARBITRATION gate
    // (`!sqFlushSig && !dcLoadHeldByOther`), and `dcLoadHeldByOther` carries
    // `walkerOwnsLoad`, i.e. the `ldOwner` arbitration register. Using it as the mux
    // select put `ldOwner` in front of `reqDrvVpn`, which is `tlb.io.lookupVpn`, which
    // is the operand of the DTLB's 4-way/2-bank hitVec compare tree. The post-route
    // report for this checkpoint names exactly that arc as a worst path:
    //
    //   LsEuPlugin ldOwner_reg[1]/C -> (LUT2 walkerOwnsLoad, LUT2 dcLoadHeldByOther,
    //     LUT5 splitReqArm) -> reqDrvVpn -> DtlbPlugin tlb hitVec (11 levels)
    //     -> DtlbPlugin rspPayload_ppn_reg[2]/CE      15 levels, 73.9% route, -0.313 ns
    //
    // (`rspPayload`'s clock-enable is `_req.fire && !missBranch`, and `missBranch`
    // is the hit cone -- see the response mux in DtlbPlugin.) So a three-valued port
    // owner that changes only on a TLB MISS was gating a 28-bit CAM compare that runs
    // on EVERY translation.
    //
    // The class-level note on `FrontPipeCtx.fcSup` already states the intended
    // property -- "the existing `Mux(reqFromSplit, txCtx.*, tCtx.*)` 2:1 mux keeps
    // exactly the shape it had, with both arms still plain register outputs. No gate
    // is added between a register and the DTLB request" -- but the SELECT had picked
    // up three gate levels since. This restores it.
    //
    // WHY IT IS EXACTLY EQUIVALENT, not merely "close enough". `reqFromSplit` drives
    // ONLY payload (`reqDrvVaddr`/`Sup`/`Write`/`RobId`/`Token`) plus the two
    // `*Fire` predicates. Payload is architecturally observable only while
    // `xlate.req.valid` is high, and `xlate.req.valid` is
    // `!excActive && (normalReqArm || splitReqArm)` -- every term of which already
    // carries `!sqFlushSig && !dcLoadHeldByOther`. So in the ONLY case where the two
    // selects differ (`xlateBArm && (sqFlushSig || dcLoadHeldByOther)`) the LS-side
    // valid is LOW and the payload is a don't-care. `normalReqFire`/`splitReqFire`
    // are both `xlate.req.fire && ... && !excActive`, which likewise cannot be true
    // with the LS-side valid low (the only other driver of `xlate.req.valid` is the
    // exception override, which requires `excActive`).
    val reqFromSplit = xlateBArm
    val reqDrvVaddr  = Mux(reqFromSplit, txCtx.addrB, tCtx.vaddr)
    val reqDrvVpn    = reqDrvVaddr(31 downto 12)
    // The ADDRESS-SPACE bit, not the privilege bit -- see `FrontPipeCtx.fcSup`. Both
    // arms are register outputs, exactly as they were when this read `.supervisor`,
    // so the translate path's depth is unchanged.
    val reqDrvSup    = Mux(reqFromSplit, txCtx.fcSup, tCtx.fcSup)
    val reqDrvWrite  = Mux(reqFromSplit,
      txCtx.memOp === MemOp.STORE, tCtx.memOp === MemOp.STORE)
    val reqDrvRobId  = Mux(reqFromSplit, txCtx.robId, tCtx.robId)
    val reqDrvToken  = m68k040.Global.robTag(xlateEpoch ## reqFromSplit, reqDrvRobId, DTranslationToken.Width,
                                                DTranslationToken.RobIdBits)
    val tIsLoad  = tCtx.memOp === MemOp.LOAD
    val tIsStore = tCtx.memOp === MemOp.STORE
    val tIsMem   = tIsLoad || tIsStore

    // P2 request and context are atomic, so there is no freshness heuristic.
    val reqStale = False
    val reqFresh = True
    val reqMatch = normalReqArm || splitReqArm
    // Directed-test probes only (LsEuCrossSpec's freshness waveform tests); zero synth impact.
    reqFresh.simPublic(); reqMatch.simPublic(); reqStale.simPublic(); xlateBArm.simPublic()

    // ── Parallel VIPT launch ─────────────────────────────────────────────────
    // P2 is the registered boundary which already launches the DTLB lookup.
    // Present the same token's full VA to the D-cache early-probe port from those
    // flops, so the page-invariant virtual-set RAM read starts in parallel with the
    // TLB lookup. The later resolved loadCmd carries the physical tag + same token.
    // Forward/fault cancels by token; squash/exception cancels the whole queue.
    val probeWanted      = normalReqArm && tIsLoad
    val probeCancel      = Bool()
    // ── W11: handing the load port to a walker MUST also cancel every resident
    // early-VIPT probe, and this is a CORRECTNESS requirement, not an optimisation.
    //
    // `DcachePlugin`'s `loadCmdPort.ready` carries `(!earlyProbeTokenPresent ||
    // earlyProbeOwnsCmd)`: a resident probe token blocks any load command that does not
    // own it. Without this term the sequence is (1) the mux hands the load port to a
    // walker and deasserts CORE's `loadCmd.valid`; (2) `loadProbePort.ready` is gated on
    // `(!loadCmdPort.valid || useEarlyProbe)`, so with CORE's valid now low the LS EU
    // keeps launching probes; (3) all four probe slots fill with tokens whose matching
    // load commands the mux will never let through; (4) the walker's command matches no
    // token, so `loadCmdPort.ready` is false FOREVER. Neither side advances.
    //
    // The fix reuses machinery that already exists for exactly this hand-over: the
    // design already cancels every resident probe when the port goes to the exception
    // sequencer. Cancelling a probe is functionally free -- an absent probe
    // qualification simply makes the later resolved command take the ordinary path --
    // so this is a performance event, never a correctness one.
    //
    // NaxRiscv is immune to this whole class for a nameable reason: it has no
    // allocating structure that outlives one pass through the cache port. Our
    // early-probe token array is exactly such a structure, so this term is the price of
    // a feature NaxRiscv does not have, and it has no outside precedent to lean on.
    val probeCancelAll   = sqFlushSig || excActive || walkerOwnsLoad
    val probeCancelToken = UInt(m68k040.cache.DLoadToken.Width bits)
    probeCancel := False
    probeCancelToken := U(0, m68k040.cache.DLoadToken.Width bits)
    val reqProbeToken = m68k040.Global.robTag(False ## False, tCtx.robId, m68k040.cache.DLoadToken.Width,
                                              m68k040.cache.DLoadToken.RobIdBits)

    dcache.loadProbe.valid         := probeWanted && xlate.req.ready
    dcache.loadProbe.payload.vaddr := tCtx.vaddr
    dcache.loadProbe.payload.token := reqProbeToken
    // The registered translation returns later; retain the raw virtual-set read and
    // resolve it by token when the physical command arrives. No second RAM read.
    // NO early physical-address hint, and there cannot be one: this probe launches in
    // the SAME cycle as the DTLB request, so nothing here has been translated yet.
    // `paddrHint` used to be assigned `tCtx.vaddr` -- a VIRTUAL address in a field the
    // D-cache builds a PHYSICAL tag from. That was inert (the `resolved` gate below is
    // hard-wired False, and it is this file's ONLY producer) but it was a trap: it made
    // "set resolved := True" look like a one-line optimisation (and `allowPretranslatedProbeHints`
    // is not the guard people assume -- it ANDs with `resolved`, so flipping it alone
    // is already inert) while silently arming
    // virtual-vs-physical tag comparisons -- correct under an identity map, a false-hit
    // generator under a real one. Supplying no hint at all means that shortcut now
    // fails loudly rather than plausibly. The SAFE early-hit path is unaffected and
    // untouched: it qualifies this same array read from `loadProbeResolve.paddr`
    // (driven from `s1Paddr` below), which IS translated. See DcacheTypes.DLoadProbe.
    dcache.loadProbe.payload.resolved  := False
    dcache.loadProbe.payload.paddrHint := U(0, 32 bits)
    dcache.loadProbe.payload.size  := tCtx.size
    dcache.loadProbe.payload.cacheMode := m68k040.cache.CacheMode.INHIBITED
    dcache.loadProbe.payload.needsLine := tCtx.twoAccess
    dcache.loadProbeCancel.valid         := probeCancel || probeCancelAll
    dcache.loadProbeCancel.payload.token := probeCancelToken
    dcache.loadProbeCancel.payload.all   := probeCancelAll
    dcache.loadProbeResolve.valid             := False
    dcache.loadProbeResolve.payload.token     := U(0, m68k040.cache.DLoadToken.Width bits)
    dcache.loadProbeResolve.payload.paddr     := U(0, 32 bits)
    dcache.loadProbeResolve.payload.cacheMode := m68k040.cache.CacheMode.INHIBITED
    val parallelViptLaunch = dcache.loadProbe.fire && xlate.req.fire &&
                             (dcache.loadProbe.payload.vaddr(31 downto 12) === xlate.req.payload.vpn)
    parallelViptLaunch.simPublic()
    probeWanted.simPublic(); probeCancel.simPublic(); probeCancelAll.simPublic()

    // ─────────────────────────────────────────────────────────────────────────
    // FMax: registered COMPLETION + WRITEBACK stage.
    //
    // The decision (SQ-forward compare / store-alloc / cache hit-detect) is a long
    // combinational arc off s1Paddr. Previously it fed completionPort/intW/wbObs and
    // hence ROB.completes in the SAME cycle (the 21-level, 72%-route critical path).
    // We now CAPTURE the decision result into registers in the decision cycle and
    // DRIVE completion/writeback/wakeup from those registers the NEXT cycle (one
    // mux level: reg -> port). This splits the arc into
    //   s1Paddr -> SQ-fwd/cache-hit -> comp* reg   (short)
    //   comp* reg -> completion -> ROB.completes    (short)
    // at the cost of ONE extra completion-latency cycle (lock-step is latency-
    // agnostic). All comp* are RegInit/Reg (no uninit fanout).
    val compValid     = RegInit(False)
    // Announce only an already-selected successful result. The IQ has a
    // registered dependency clear followed by registered issue selection, so a
    // consumer reaches operand capture after the ordinary writeback register.
    val nextIntWake = Flow(UInt(6 bits))
    nextIntWake.valid := False
    nextIntWake.payload := 0
    val compRobId     = Reg(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits))
    compValid.simPublic(); compRobId.simPublic() // debug-only, task #139 finding #1; zero synth impact
    val compData      = Reg(Bits(32 bits))
    compData.simPublic() // debug-only, task #144
    val compPdst      = Reg(UInt(6 bits))
    val compPdstValid = RegInit(False)
    val compIsLoad    = RegInit(False)   // load (writes a reg + wakes) vs store
    // A STACK-PUSH store produces an int reg (the predecremented A7) — unlike a plain
    // store. It must write the int PRF AND broadcast a wakeup (a consumer of the new
    // A7 — e.g. a following push/pop — waits on it). `compWakes` gates the wakeup for
    // BOTH a load and a stkPush store; `compStkPush` selects the int write (= s1Va).
    val compWakes     = RegInit(False)
    // A STACK-PUSH store is a CRACK µop of BSR/JSR (the macro instruction's single
    // architectural commit is the trailing branch). The lock-step whitebox DROPS its
    // commit record (like DIVREM) but STILL folds its A7 write into the running A7.
    val compStkPush   = RegInit(False)
    // RTR CCR-restore load: also a CRACK µop (the RTR macro instruction's single commit
    // is the trailing ibranch) -> DROP its commit record (like stkPush) but still fold
    // its CCR (NZVC/X) into the running architectural CCR.
    val compCcrRestore = RegInit(False)
    // An EA auto-update (-(An)/(An)+) STORE that is NOT the macro commit (a mem-dest RMW
    // store, a CLR store, or a mem-to-mem store) writes An (its int dst) but is a CRACK
    // µop -> DROP its commit record (like stkPush) while its An write still lands in the
    // PRF (verified by a later An reader) and folds the running A7 when An==A7. A SINGLE
    // reg-to-mem store (MOVE Dn,-(An), firstOfInstr) IS the macro commit -> kept.
    val compEaAutoDrop = RegInit(False)
    // A mem-dest RMW / CLR TRAILING store (the macro instruction's single architectural
    // commit is the op µop, which carries the PC + flags). A trailing RMW store writes
    // NEITHER an int reg NOR flags (the op µop owns NZVCX) — unlike a MOVE-to-mem store
    // (writes NZVC) or a stack-push store (writes A7). DROP its commit record (it has no
    // architectural register/flag effect; the memory effect is checked via checkMem).
    val compRmwStore  = RegInit(False)
    // Generic crack-DROP marker carried on the µop (`divIsRem`): a MOVEM LOAD writes an
    // arch reg (so it is NOT a compRmwStore) yet must be DROPPED from the oracle-step stream
    // (the MOVEM macro is ONE step — the final An-update is the kept commit). The loaded
    // value still lands in the PRF + is verified by a later reader. (compRmwStore already
    // drops MOVEM STORES — a store with no reg/flag write — so this covers the loads.)
    val compCrackDrop = RegInit(False)
    // Macro-commit KEEP marker (PEA's push): force the whitebox to keep this commit.
    val compKeepCommit = RegInit(False)
    val compDstArch   = Reg(UInt(5 bits))
    // NZVC writeback for a memory MOVE (N/Z of the moved value, V=C=0).
    val compNzvc      = Reg(Bits(4 bits))
    compNzvc.simPublic() // debug-only, task #144
    val compNzvcWrite = RegInit(False)
    val compNzvcDst   = Reg(UInt(nzvcW.address.getWidth bits))
    compNzvcWrite.simPublic(); compNzvcDst.simPublic() // debug-only, task #139/#141 lsNzvc investigation
    // RTR CCR-restore (X := loaded[4]); NZVC := loaded[3:0] reuses compNzvc.
    val compX         = RegInit(False)
    val compXWrite    = RegInit(False)
    val compXDst      = Reg(UInt(xW.address.getWidth bits))
    // MMU access-FAULT completion: when an access takes a DTLB rsp.fault, it still
    // COMPLETES (compValid -> the ROB marks the entry done so it can retire), but as
    // a FAULT: it writes NO register / allocs NO store / wakes nothing, and drives
    // faultCompletion {robId, faultAddr, write, sizeBits, supervisor} so the ROB
    // flags the entry (vector 2) for precise delivery at retire. RegInit(False) so an
    // unfaulted access never spuriously flags. Captured alongside the comp* stage.
    val compIsFault   = RegInit(False)
    val compFaultAddr = Reg(UInt(32 bits))
    val compFaultWr   = RegInit(False)
    val compFaultSize = Reg(UInt(2 bits))
    val compFaultSup  = RegInit(False)
    // Task #189: True for the existing MMU/ATC (DTLB) fault path, False for a
    // plain physical bus error (D-cache refill AXI resp error) — see LsFault.atc.
    val compFaultAtc  = RegInit(True)
    // Root-cause fix (post-P2.5 lock-step investigation): True the one cycle
    // `captureCompletion`/`captureFault` actually drives the shared comp*/completion
    // stage (the LIVE EU pipe's own decision this cycle). The deferred precise-store
    // replay below (see `pendMem`/`deferCompletion`) reads this AFTER the fsm (later
    // in elaboration order -> sees the fully-resolved value) to give the live path
    // strict priority for the single shared completion stage, retrying next cycle on
    // a collision instead of silently dropping either completion.
    val liveCompletionFires = Bool()

    // ─────────────────────────────────────────────────────────────────────────
    // FMax #2: PIPELINE the SQ-forward query result -> completion decision.
    //
    // The 8-entry SQ overlap-compare + youngest-full-overlap `best` reduce off
    // `s1Paddr` (== s1Va_reg -> xlate -> paddr) feeding compData/compValid in the
    // SAME cycle was the 17-level, 70%-route critical path (s1Va_reg ->
    // SQ-compare -> compData_reg/CE). We REGISTER the SQ forward response
    // (hit/data/stall) for a load in the decision cycle, and DRIVE the completion
    // capture from those registers the NEXT cycle (a RESOLVE state). This splits
    //   s1Va_reg -> s1Paddr -> SQ-fwd-compare -> fwd* reg          (short)
    //   fwd* reg -> captureCompletion -> compData_reg              (short, 1 mux)
    // at the cost of ONE extra load-latency cycle (lock-step is latency-agnostic).
    //
    // D1 carries this response with its pruned P4 context. Multiple operations may
    // occupy P2/P3/P4 concurrently, while each stage holds its own context stable
    // under backpressure. Flush invalidates every unlaunched stage before it can
    // complete; already-launched cache descriptors use poison-and-drain.
    // ---- two-access (cross-line / cross-page) capture ----
    // The captured 128-bit line from slot A; slot B's line arrives in WAIT_B and
    // is merged with it. `lineOff` is the byte offset of the access within line A.
    // `aDone` records that slot A's line was captured (loadRsp is a 1-cycle pulse,
    // so we latch it and then keep driving slot B's cmd until the cache accepts it).
    val lineA    = Reg(Bits(128 bits))
    val aDone    = RegInit(False)

    // ---- MOVE-to-memory NZVC (computed from the store data at the access size) ----
    // m68k MOVE sets N = sign bit of the moved value at the size, Z = (value==0 over
    // the size's bytes), V = 0, C = 0. The store data (s1Data) holds the source
    // register; only the low `size` bytes are written / observed. Computed off the
    // already-registered s1Data (no new long arc).
    def moveNzvc(value: Bits, size: m68k040.isa.Size.C): Bits = {
      val n = size.mux(
        m68k040.isa.Size.BYTE -> value(7),
        m68k040.isa.Size.WORD -> value(15),
        m68k040.isa.Size.LONG -> value(31))
      val z = size.mux(
        m68k040.isa.Size.BYTE -> (value(7 downto 0)  === 0),
        m68k040.isa.Size.WORD -> (value(15 downto 0) === 0),
        m68k040.isa.Size.LONG -> (value === 0))
      n ## z ## False ## False
    }
    val storeNzvc = moveNzvc(s1Data, u1.size)

    def captureFrontCtx(dst: FrontPipeCtx): Unit = {
      dst.robId           := s1Ctx.robId
      dst.vaddr           := s1Va
      // MOVEM far-page probe (task movem-translate-ahead): when this uop is the
      // macro's first LOAD element AND its span crosses a page, addrB carries the
      // exact crossing address (not the ordinary next-line addrB) and twoAccess is
      // forced True to drive the SAME xlateBArm/txSecond second-round-trip FSM a
      // genuine split reuses -- see the s1AddrB-adjacent comment block above for
      // the full derivation. `probeOnlyB` is the ONLY new piece of state: it tells
      // `txOut` (far downstream) to suppress `twoAccess` again before P3, so a
      // successful (non-faulting) probe never turns into a real second access.
      dst.addrB           := Mux(movemCrosses, movemCrossAddr, s1AddrB)
      dst.storeData       := s1StoreData
      lateStoreData.foreach { _ =>
        dst.lateDataPending := s1LateData.get
        dst.lateDataTag := u1.psrcB
      }
      dst.anWb            := s1AnWb
      dst.storeNzvc       := storeNzvc
      dst.size            := u1.size
      dst.memOp           := u1.memOp
      dst.twoAccess       := s1TwoAccess || movemCrosses
      dst.probeOnlyB      := movemCrosses
      dst.pdst            := u1.pdst
      dst.pdstValid       := u1.pdstValid
      dst.dstArch         := u1.dstArch
      dst.stkPush         := u1.stkPush
      dst.autoStoreAn     := (u1.memOp === MemOp.STORE) &&
                             (u1.eaAuto =/= m68k040.decode.EaAuto.NONE)
      dst.ccrRestore      := u1.ccrRestore
      dst.signExtW        := u1.isMovea && (u1.memOp === MemOp.LOAD) &&
                             (u1.size === m68k040.isa.Size.WORD)
      dst.leaAddr         := u1.leaAddr
      dst.writesNzvc      := u1.writesNzvc
      dst.pNzvcDst        := u1.pNzvcDst
      dst.writesX         := u1.writesX
      dst.pXDst           := u1.pXDst
      dst.crackDrop       := u1.divIsRem
      dst.keepCommit      := u1.keepCommit
      dst.needsSupervisor := u1.needsSupervisor
      dst.supervisor      := privCtrl.map(_.supervisor).getOrElse(False)
      // MOVES: the access runs in the address space named by SFC (read) / DFC
      // (write), not in the current privilege level's. FC[2] is the supervisor/user
      // selector on a 68040, so bit 2 of the selected function-code register IS the
      // DTLB request's `supervisor` bit -- it chooses URP vs SRP for the table
      // search and it is what the ATC's supervisor-only page protection compares
      // against. Every other µop keeps the live architectural S bit.
      //
      // Reading SFC/DFC live is sound: their only writer is MOVEC, a SERIALIZING
      // sysOp (it retires alone at the ROB head, so every older MOVES has already
      // translated, and it squashes everything younger, so every younger MOVES
      // re-executes against the new value). Same argument PrivilegeService already
      // makes for the S bit itself.
      //
      // A standalone LS-EU DUT with no PrivilegeService wired falls back to the
      // live-supervisor value (False), exactly the pre-change behaviour.
      val altFcSup = privCtrl.map { pv =>
        Mux(u1.memOp === MemOp.STORE, pv.destFc(2), pv.sourceFc(2))
      }.getOrElse(False)
      dst.fcSup           := Mux(u1.altAddrSpace, altFcSup,
                                 privCtrl.map(_.supervisor).getOrElse(False))
    }

    // captured-decision -> register (called in the decision cycle). A STACK-PUSH store
    // writes its int dst (A7) with the PREDECREMENTED address (s1Va) — not the load
    // `result` — and wakes consumers of A7.
    // An EA auto-update STORE (-(An)/(An)+) writes its base An (its int dst) with the
    // s1AnWb value — generalizing the stkPush A7 side-effect to any An. A LOAD with an
    // auto EA writes its LOADED value to its int dst (the An update rides a separate ADD
    // crack µop); so the An write is selected ONLY for an eaAuto STORE.
    def captureCompletionFront(ctx: FrontPipeCtx, result: Bits): Unit = {
      liveCompletionFires := True
      frontCompFires := True
      compValid     := True
      compRobId     := ctx.robId
      // MOVEM.W LOAD sign-extends the loaded word to the full 32-bit register (Musashi
      // MAKE_INT_16). The DcacheByteLane.extract path ZERO-extends a WORD; the MOVEM-load
      // µop carries `isMovea` (reused as the ".W load -> sign-extend" marker; the ALU EU
      // is the only other isMovea consumer and never sees an LS-cluster µop). A .L MOVEM
      // load + every non-MOVEM load leave isMovea False (full / zero-extended result).
      val ldResult = Mux(ctx.signExtW,
                         result(15 downto 0).asSInt.resize(32).asBits, result)
      // LEA address-generate: the int result IS the computed effective address (s1Va),
      // exactly like a stkPush writes its predecremented address. No memory was accessed.
      compData      := Mux(ctx.leaAddr, ctx.vaddr.asBits,
                       Mux(ctx.stkPush, ctx.vaddr.asBits,
                       Mux(ctx.autoStoreAn, ctx.anWb, ldResult)))
      // A CCR-restore load writes NO int reg (it restores flags); a stack-push store's
      // int dst is A7 (handled via compData above); a plain load writes its int dst.
      compPdst      := ctx.pdst
      compPdstValid := ctx.pdstValid && !ctx.ccrRestore
      compIsLoad    := ctx.memOp === MemOp.LOAD
      // Wake an int-producing load / stkPush store / EA-auto store (the predec/postinc
      // An side-effect). A CCR-restore load produces no int reg -> no (stale-pdst) wakeup.
      compWakes     := ((ctx.memOp === MemOp.LOAD) && !ctx.ccrRestore) ||
                       ctx.stkPush || ctx.leaAddr || (ctx.autoStoreAn && ctx.pdstValid)
      nextIntWake.valid := ctx.pdstValid && !ctx.ccrRestore &&
        ((ctx.memOp === MemOp.LOAD) || ctx.stkPush || ctx.leaAddr || ctx.autoStoreAn)
      nextIntWake.payload := ctx.pdst
      compStkPush   := ctx.stkPush
      compCcrRestore := ctx.ccrRestore
      // Drop the commit record of an EA-auto store that is an RMW/CLR AUXILIARY store:
      // it writes An but NOT NZVC (the op µop owns the flags + is the macro commit). A
      // MOVE store (reg-to-mem OR mem-to-mem) writes NZVC and IS the macro commit (it
      // carries the PC) -> KEPT. So drop iff it writes An but no flags.
      compEaAutoDrop := ctx.autoStoreAn && !ctx.writesNzvc
      // Trailing RMW/CLR store: a STORE writing neither an int reg nor flags. An EA-auto
      // store writes An (pdstValid) -> NOT a dropped RMW store (its An commit is kept).
      compRmwStore  := (ctx.memOp === MemOp.STORE) && !ctx.pdstValid &&
                       !ctx.writesNzvc && !ctx.stkPush
      compCrackDrop := ctx.crackDrop
      compKeepCommit := ctx.keepCommit
      compDstArch   := ctx.dstArch
      // CCR-restore (RTR): NZVC := loaded[3:0], X := loaded[4] (CCR bit layout
      // X=4,N=3,Z=2,V=1,C=0). Otherwise a memory MOVE's NZVC = N/Z of the value
      // actually transferred (V=C=0); non-MOVE accesses leave writesNzvc False.
      compNzvc      := Mux(ctx.ccrRestore, result(3 downto 0),
                           Mux(ctx.memOp === MemOp.LOAD,
                               moveNzvc(result, ctx.size), ctx.storeNzvc))
      compNzvcWrite := ctx.writesNzvc
      compNzvcDst   := ctx.pNzvcDst
      compX         := result(4)
      compXWrite    := ctx.writesX
      compXDst      := ctx.pXDst
      compIsFault   := False
    }

    // captured-FAULT -> register (called in the decision cycle when an access takes a
    // DTLB rsp.fault). Completes (compValid) so the ROB marks done, but flagged as a
    // fault (no reg write / no store alloc / no wakeup); drives faultCompletion. The
    // SSW attrs: write = store, sizeBits = encoded access size, supervisor = the
    // access function-code supervisor bit captured for this access.
    // `atc` (task #189): True (default, preserves the pre-existing MMU-fault
    // behavior) for the DTLB-translation-fault call site; the NEW bus-error call
    // site (D-cache refill AXI resp error, WAIT state below) passes False.
    // `faultAddr` (mmu-split-second-half fix): defaults to s1Va (slot A / every
    // pre-existing call site, unchanged) — the XLATE_B call site (addrB's own
    // translation faulting) passes s1AddrB so the SSW/format-$7 frame reports the
    // ACTUAL faulting half's address, not slot A's.
    def captureFaultFront(ctx: FrontPipeCtx, faultAddr: UInt,
                          atc: Boolean = true): Unit = {
      liveCompletionFires := True
      frontCompFires := True
      compValid     := True
      compRobId     := ctx.robId
      compPdstValid := False
      compNzvcWrite := False
      compXWrite    := False
      compIsLoad    := False
      compWakes     := False
      compStkPush   := False
      compCcrRestore := False
      compEaAutoDrop := False
      compCrackDrop := False
      compKeepCommit := False
      compIsFault   := True
      compFaultAddr := faultAddr
      compFaultWr   := ctx.memOp === MemOp.STORE
      compFaultSize := ctx.size.mux(
        m68k040.isa.Size.BYTE -> U(0, 2 bits),
        m68k040.isa.Size.WORD -> U(1, 2 bits),
        m68k040.isa.Size.LONG -> U(2, 2 bits))
      compFaultSup  := ctx.supervisor
      compFaultAtc  := Bool(atc)
    }

    // ── BACK-STAGE capture (spec §3.2(c)) ────────────────────────────────────
    // The back-stage twin of `captureCompletion`, reading `bkCtx`/`llReg` instead of
    // the live `u1`/`s1Ctx`/`s1Va`. Every field the load path cannot reach is
    // hardcoded to its provable value (see BkCtx's declaration comment) rather than
    // carried as a flop.
    def captureCompletionDesc(ctx: BkCtx, result: Bits,
                              size: m68k040.isa.Size.C): Unit = {
      liveCompletionFires := True
      compValid      := True
      compRobId      := ctx.robId
      compData       := Mux(ctx.signExtW, result(15 downto 0).asSInt.resize(32).asBits, result)
      compPdst       := ctx.pdst
      compPdstValid  := ctx.pdstValid
      compIsLoad     := True
      compWakes      := ctx.wakes
      nextIntWake.valid := ctx.pdstValid && ctx.wakes
      nextIntWake.payload := ctx.pdst
      compStkPush    := False           // store-only marker; unreachable from RESOLVE
      compCcrRestore := ctx.ccrRestore
      compEaAutoDrop := False           // requires isStore
      compRmwStore   := False           // requires isStore
      compCrackDrop  := ctx.crackDrop
      compKeepCommit := ctx.keepCommit
      compDstArch    := ctx.dstArch
      // RTR restores the low CCR bits verbatim. A normal memory-to-register MOVE
      // derives N/Z from the sized returned value; using result(3:0) here silently
      // turned arbitrary data bits into flags once aligned loads entered the ring.
      compNzvc       := Mux(ctx.ccrRestore, result(3 downto 0), moveNzvc(result, size))
      compNzvcWrite  := ctx.writesNzvc
      compNzvcDst    := ctx.pNzvcDst
      compX          := result(4)
      compXWrite     := ctx.writesX
      compXDst       := ctx.pXDst
      compIsFault    := False
    }
    def captureCompletionBk(result: Bits): Unit =
      captureCompletionDesc(bkCtx, result, llReg.size)

    // Descriptor form of `captureFault`: aligned entries and both split halves pass
    // their own captured context/address, so an untagged refill bus error remains
    // precisely associated. Translation faults still complete in the front path.
    def captureFaultDesc(ctx: BkCtx, vaddr: UInt, size: m68k040.isa.Size.C,
                         atc: Boolean): Unit = {
      liveCompletionFires := True
      compValid      := True
      compRobId      := ctx.robId
      compPdstValid  := False
      compNzvcWrite  := False
      compXWrite     := False
      compIsLoad     := False
      compWakes      := False
      compStkPush    := False
      compCcrRestore := False
      compEaAutoDrop := False
      compCrackDrop  := False
      compKeepCommit := False
      compIsFault    := True
      compFaultAddr  := vaddr
      compFaultWr    := False           // requires isStore
      compFaultSize  := size.mux(
        m68k040.isa.Size.BYTE -> U(0, 2 bits),
        m68k040.isa.Size.WORD -> U(1, 2 bits),
        m68k040.isa.Size.LONG -> U(2, 2 bits))
      // spec §3.2(d): the CAPTURED supervisor bit, never the live one.
      compFaultSup   := ctx.xlateSup
      compFaultAtc   := Bool(atc)
    }
    // Capture the back-stage descriptor at the RESOLVE->LAUNCH handoff. Called from
    // the FRONT FSM's RESOLVE "no forward" arm, in the SAME cycle llReg is captured,
    // so every source is the still-resident S1 context of THIS load.
    def captureBkCtx(dst: BkCtx, src: FrontPipeCtx): Unit = {
      dst.robId           := src.robId
      dst.pdst            := src.pdst
      dst.pdstValid       := src.pdstValid && !src.ccrRestore
      // Carried as the FULL pre-split expression rather than the load-only subset, so
      // this can never silently diverge if a future µop shape reaches RESOLVE.
      dst.wakes           := ((src.memOp === MemOp.LOAD) && !src.ccrRestore) ||
                             src.stkPush || src.leaAddr ||
                             (src.autoStoreAn && src.pdstValid)
      dst.ccrRestore      := src.ccrRestore
      dst.signExtW        := src.signExtW
      dst.dstArch         := src.dstArch
      dst.writesNzvc      := src.writesNzvc
      dst.pNzvcDst        := src.pNzvcDst
      dst.writesX         := src.writesX
      dst.pXDst           := src.pXDst
      dst.crackDrop       := src.crackDrop
      dst.keepCommit      := src.keepCommit
      dst.needsSupervisor := src.needsSupervisor
      dst.xlateSup        := src.supervisor
    }

    // Aligned descriptor ring bookkeeping.  Response retirement is emitted before
    // enqueue so a full-ring pop+push to the same physical slot leaves the new entry
    // valid (accept-last, matching the house elastic-stage convention).
    //
    // `alignedRspEntry`/`alignedRspIsPoison` are declared HERE (rather than only at
    // the capture site further below, where the pre-split version of this code
    // declared them) because the split-abort case below needs them to decide
    // whether to skip an extra ring slot; the later capture logic reuses these same
    // vals rather than re-declaring them.
    val alignedRspEntry    = alignedMem(alignedRspPtr)
    val alignedRspIsPoison = alignedPoisoned(alignedRspPtr) || sqFlushSig
    // True when this response is slot A of a split pair. A non-poisoned FAULT on
    // slot A aborts the whole instruction right here -- mirroring the old bkFsm's
    // WAIT_A fault arm exactly -- and slot B (provably the very next ring entry,
    // still unsent because of `alignedSendHeld`) must be cancelled outright rather
    // than ever being sent: a real fault must not trigger a second, phantom bus
    // transaction for the other half of the same instruction (this matters most
    // for an INHIBITED device access, where a second read would be a genuine
    // architectural side-effect bug, not just wasted bus bandwidth). A poisoned
    // slot A (flush in flight) is deliberately NOT special-cased here: it drains
    // through the real hardware exactly like every other poisoned entry (see
    // `alignedSendValid`, which never gates on poison either), so slot B simply
    // becomes sendable the ordinary way once slot A pops and is discarded the
    // ordinary way (poisoned) once its own response arrives.
    val alignedRspIsSplitA = alignedRspEntry.twoAccess && !alignedRspEntry.splitSecond
    val alignedRspAbortsPair = alignedRspFire && alignedRspIsSplitA &&
                               dcache.loadRsp.payload.fault && !alignedRspIsPoison
    alignedRspAbortsPair.simPublic()
    // True iff this response CONCLUDES the whole instruction's cache activity: an
    // ordinary (non-split) entry always concludes on its one response; a split
    // slot A only concludes on a fault (the abort case above); slot B always
    // concludes (merge success, or its own fault). Used both to gate the shared
    // comp* stage (an in-flight slot A capturing its line for later merge must NOT
    // claim the stage) and to clear `inhibitedLoadBusySig`'s busy flag only once
    // the FULL split instruction -- not just its first half -- has resolved.
    val alignedRspTerminal = !alignedRspEntry.twoAccess || alignedRspEntry.splitSecond ||
                             dcache.loadRsp.payload.fault
    alignedRspTerminal.simPublic()
    when(alignedRspAbortsPair) {
      // At this exact cycle `alignedSendPtr` is guaranteed to equal
      // `alignedRspPtr + 1` (slot B's own index): slot B is architecturally
      // contiguous with slot A (both pushed atomically the same cycle by
      // `alignedEnqSplit` below) and, held by `alignedSendHeld`, cannot have been
      // sent yet. Retire it directly (never sent, so no dangling command) and
      // advance BOTH the send and response pointers past it, instead of the
      // ordinary single-entry advance.
      //
      // This FORWARD index reach (`alignedRspPtr + 1`) is sound in a way the send
      // side's old BACKWARD reach (`alignedSendPtr - 1`) was not, and the asymmetry
      // is worth stating because the backward one deadlocked real hardware: an index
      // can only be REUSED by a later push once its occupant has popped, and slot B
      // provably cannot pop before slot A (responses are strict FIFO and slot B is
      // still unsent here). So `alignedRspPtr + 1` is always still slot B. Slot A,
      // by contrast, pops FIRST -- freeing its index for reuse while slot B is still
      // resident -- which is exactly why "the entry behind me" was never a valid
      // stand-in for "my slot A". See `alignedSlotAPending` above.
      alignedValid(alignedRspPtr)     := False
      alignedSent(alignedRspPtr)      := False
      alignedPoisoned(alignedRspPtr)  := False
      alignedValid(alignedRspPtr + 1) := False
      alignedRspPtr                    := alignedRspPtr + 2
      alignedSendPtr                   := alignedRspPtr + 2
    } elsewhen(alignedRspFire) {
      alignedValid(alignedRspPtr)    := False
      alignedSent(alignedRspPtr)     := False
      alignedPoisoned(alignedRspPtr) := False
      alignedRspPtr                  := alignedRspPtr + 1
    }
    when(alignedCmdFire) {
      alignedSent(alignedSendPtr) := True
      alignedSendPtr              := alignedSendPtr + 1
    }
    when(sqFlushSig) {
      for (i <- 0 until alignedDepth) {
        when(alignedValid(i)) { alignedPoisoned(i) := True }
      }
    }
    when(alignedEnq) {
      val dst = alignedMem(alignedPushPtr)
      captureBkCtx(dst.bk, p4Ctx.xlate.front)
      dst.vaddr := p4Ctx.xlate.front.vaddr
      dst.paddr := p4Ctx.xlate.paddr
      dst.size  := p4Ctx.xlate.front.size
      dst.cmode := p4Ctx.xlate.cmode
      dst.twoAccess   := False
      dst.splitSecond := False
      dst.mergeOff    := U(0, 4 bits)
      alignedValid(alignedPushPtr)    := True
      alignedSent(alignedPushPtr)     := alignedFallThroughFire
      alignedPoisoned(alignedPushPtr) := sqFlushSig
      alignedPushPtr                  := alignedPushPtr + 1
    }
    // Split-load pair push: slot A at `alignedPushPtr`, slot B immediately behind
    // it at `alignedPushPtr+1` -- ALWAYS pushed together, in the SAME cycle, which
    // is the structural invariant every other piece of split-pairing logic in this
    // file (`alignedSendHeld`, `alignedRspAbortsPair`) relies on.
    when(alignedEnqSplit) {
      val idxA = alignedPushPtr
      val idxB = alignedPushPtr + 1
      val dstA = alignedMem(idxA)
      val dstB = alignedMem(idxB)
      captureBkCtx(dstA.bk, p4Ctx.xlate.front)
      dstA.vaddr := p4Ctx.xlate.front.vaddr
      dstA.paddr := p4Ctx.xlate.paddr
      dstA.size  := p4Ctx.xlate.front.size
      dstA.cmode := p4Ctx.xlate.cmode
      dstA.twoAccess   := True
      dstA.splitSecond := False
      dstA.mergeOff    := U(0, 4 bits)
      captureBkCtx(dstB.bk, p4Ctx.xlate.front)
      dstB.vaddr := p4Ctx.xlate.front.addrB
      dstB.paddr := p4Ctx.xlate.paddrB
      dstB.size  := p4Ctx.xlate.front.size
      dstB.cmode := p4Ctx.xlate.cmodeB
      dstB.twoAccess   := True
      dstB.splitSecond := True
      dstB.mergeOff    := p4Ctx.xlate.front.vaddr(3 downto 0)
      alignedValid(idxA)    := True; alignedValid(idxB)    := True
      alignedSent(idxA)     := False; alignedSent(idxB)     := False
      alignedPoisoned(idxA) := sqFlushSig; alignedPoisoned(idxB) := sqFlushSig
      alignedPushPtr         := alignedPushPtr + 2
    }
    // The selector is `push-kind ## response`. Every reachable combination has an arm:
    // `alignedEnq` and `alignedEnqSplit` are mutually exclusive by construction (the
    // `p4Front.twoAccess` if/else at the enqueue site), so 110/111 cannot occur, and 000
    // correctly holds.
    //
    // 101 -- an ORDINARY push with a response in the same cycle -- was MISSING, and
    // "missing" silently means "hold the register". For an ordinary pop that is right
    // (+1 -1 = 0). For an `alignedRspAbortsPair` pop it is WRONG: an abort retires slot A
    // AND slot B together, so the correct update is +1 -2 = -1 and the counter instead
    // stayed put. `alignedCount` is then permanently one higher than the true occupancy,
    // and since it is the ADMISSION gate (`alignedFull`/`alignedEmpty`/`alignedCanEnq`),
    // four such coincidences pin `alignedFull` high on an EMPTY ring: `alignedRspFire`
    // can never fire again (nothing valid to respond), `alignedCanEnq` is dead, no load is
    // ever admitted again and the core silently stops retiring with every architectural
    // register intact. Reproduced by `cpush_split_fault_ring_leak.s`; see that file.
    //
    // The ring ORDER is fine in this case and does not need fixing: with the ring full
    // `alignedPushPtr === alignedRspPtr`, so the new descriptor lands on slot A's
    // just-freed index, and `alignedRspPtr`/`alignedSendPtr` advancing to `alignedRspPtr+2`
    // reaches it again after wrapping -- it is the correct, in-order next entry, not an
    // orphan. Only the count was wrong.
    switch(alignedEnq ## alignedEnqSplit ## alignedRspFire) {
      is(B"100") { alignedCount := alignedCount + 1 }         // ordinary push only
      is(B"001") {                                             // response only
        alignedCount := Mux(alignedRspAbortsPair, alignedCount - 2, alignedCount - 1)
      }
      is(B"010") { alignedCount := alignedCount + 2 }         // split-pair push only
      is(B"011") {                                             // split-pair push + response
        alignedCount := Mux(alignedRspAbortsPair, alignedCount, alignedCount + 1)
      }
      is(B"101") {                                             // ordinary push + response
        alignedCount := Mux(alignedRspAbortsPair, alignedCount - 1, alignedCount)
      }
    }

    // ── Sim-only ring-accounting tripwire (2026-09-17) ───────────────────────────
    // `alignedCount` is a redundant encoding of `alignedValid`: it MUST equal the number
    // of occupied ring slots on every cycle boundary. It is redundant because it is needed
    // COMBINATIONALLY (`alignedFull`/`alignedEmpty`/`alignedCanEnq*` sit in the admission
    // path and cannot afford a live popcount), and redundant state is exactly the state
    // that desynchronises silently.
    //
    // The consequence of a desync is not a wrong value, it is a DEAD MACHINE, and the
    // signature is indistinguishable from "it just froze" -- which is why this is asserted
    // rather than left to be inferred from a hang. It is the invariant the switch above
    // exists to maintain, so it is checked here rather than trusted.
    GenerationFlags.simulation {
      when(alignedFallThrough) {
        assert(!alignedSendValid && alignedEnq && !alignedEnqSplit && !sqFlushSig,
          "LsEuPlugin: fall-through collided with an older command, split or flush", FAILURE)
      }
      assert(alignedCount === CountOne(alignedValid).resize(alignedCount.getWidth),
        "LsEuPlugin: alignedCount desynchronised from the aligned-ring occupancy " +
        "(popcount of alignedValid). The counter gates admission (alignedFull / " +
        "alignedEmpty / alignedCanEnq), so an over-count permanently removes ring " +
        "capacity and an over-count of alignedDepth wedges load admission entirely.",
        FAILURE)
    }

    // ── Precise-store deferred-completion replay (root-cause fix, post-P2.5
    // lock-step investigation) ──────────────────────────────────────────────
    // Task P2.2 makes `captureCompletion` for a store CONDITIONAL on `fastStore`,
    // intentionally withholding compValid (and therefore the real int/NZVC/X PRF
    // writes, the dynamic wakeups, `completionPort`, AND the sim-only `wbObs`
    // whitebox tap) for a precise store at alloc time — its ROB *retire-eligibility*
    // (`completes`) is separately and correctly driven via completion port 4 /
    // `sq.io.sqCompletion` (Task P2.3/P2.4), but nothing ever replayed the withheld
    // captureCompletion effects once the SQ actually confirmed the drain. That gap
    // is the root cause of the P2.5 lock-step regression (142/394 instead of
    // 390/394, "commit robId=N with no writeback observed" on almost every
    // LS-touching test — including pure LOADS whose *prologue* stores, under the
    // MMU-off default, are classified precise): WhiteboxCapture.onCommit requires
    // an `onWb` record for every retiring robId, and a precise store's An
    // auto-update / MOVE-to-mem NZVC write never happened at all, in REAL hardware
    // too (not just the sim tap) — `intW`/`nzvcW`/`wakeupPort` are driven from the
    // exact same comp* registers as `wbObs`.
    //
    // Fix: latch exactly what `captureCompletion` would have captured into a small
    // side FIFO at alloc time (depth = the SQ's own capacity — a precise store can
    // never be resident beyond the SQ's 8 entries), and REPLAY it into the shared
    // comp* stage when `sq.io.sqCompletion` confirms the drain. Success vs fault is
    // distinguished per-entry (`pendFault`); a faulted drain's effects are dropped
    // entirely (never applied) — that store's exception delivery already happens
    // via the separately-wired `sq.io.sqFaultCompletion` -> `rob.logic.sqFaultCompletion`
    // (mirrors `lsFaultCompletion`), so replaying anything here would be redundant
    // (or, if compIsFault were (mis)used, would spuriously double-fire
    // `faultCompletionPort` with stale fields). A flush discards any NOT-YET-
    // confirmed entries (mirrors StoreQueue's own squash-uncommitted-on-flush rule
    // — an unconfirmed entry is, by construction, still speculative); an
    // already-confirmed-but-not-yet-applied entry is left alone (it already reached
    // the ROB head and completed, so it is guaranteed older than anything a later
    // flush could legitimately discard).
    case class PendingStoreWb() extends Bundle {
      val robId      = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
      val dstArch    = UInt(5 bits)
      val data       = Bits(32 bits)
      val pdst       = UInt(6 bits)
      val pdstValid  = Bool()
      val wakes      = Bool()
      val stkPush    = Bool()
      val eaAutoDrop = Bool()
      val rmwStore   = Bool()
      val crackDrop  = Bool()
      val keepCommit = Bool()
      val nzvc       = Bits(4 bits)
      val nzvcWrite  = Bool()
      val nzvcDst    = UInt(nzvcW.address.getWidth bits)
    }
    val pendDepth = 8   // == StoreQueue(8)'s own depth; see comment above
    val pendMem   = Vec.fill(pendDepth)(Reg(PendingStoreWb()))
    val pendFault = Vec.fill(pendDepth)(RegInit(False))
    // Latched verbatim from `sq.io.sqFaultCompletion.payload` the cycle it fires
    // (a Flow, valid for exactly that one cycle) -- replayed out through
    // `sqFaultCompletionPort` at the (possibly much later) apply cycle, alongside
    // `sqCompletionPort`, so the two ROB-facing signals never separate from the
    // wbObs replay they must stay synchronized with (see the port-driving
    // comment above the `sqCompletionPort.valid := False` default).
    val pendFaultPayload = Vec.fill(pendDepth)(Reg(LsFault()))
    // Part 127: this entry's SQ partner was ORPHANED by a flush (its ROB entry was
    // destroyed while its physical write was already in flight -- see StoreQueue's
    // `orphans`). Its slot must still be CONSUMED, so the two rings stay in lock step,
    // but nothing may be driven from it: no ROB completion (the robId now belongs to a
    // different instruction), no PRF/NZVC write-back (the rename it names was rolled
    // back), no wakeup.
    val pendOrphan = Vec.fill(pendDepth)(RegInit(False))
    val pendPtrW  = log2Up(pendDepth)
    val pendPush  = Reg(UInt(pendPtrW bits)) init 0   // next free slot to WRITE (alloc time)
    val pendReady = Reg(UInt(pendPtrW bits)) init 0   // SQ has confirmed the drain up to here
    val pendApply = Reg(UInt(pendPtrW bits)) init 0   // already replayed into comp* up to here
    // Debug-only taps (zero synth impact): let a directed test observe the
    // ready/apply backlog pointers directly, to confirm a `liveCompletionFires`
    // collision genuinely occurred (rather than inferring it indirectly).
    pendPush.simPublic(); pendReady.simPublic(); pendApply.simPublic()
    liveCompletionFires.simPublic()

    // Called instead of `captureCompletion` on the `!fastStore` (precise) store-alloc
    // arms — same decision cycle, same live u1/s1 signals, just latched for later
    // instead of driving comp* immediately.
    def deferCompletion(src: FrontPipeCtx): Unit = {
      val e = PendingStoreWb()
      e.robId      := src.robId
      e.dstArch    := src.dstArch
      // A store never reaches captureCompletion's leaAddr/ldResult branches (those
      // are load/LEA-only) -- the store result Mux collapses to exactly this.
      e.data       := Mux(src.stkPush, src.vaddr.asBits,
                          Mux(src.autoStoreAn, src.anWb, B(0, 32 bits)))
      e.pdst       := src.pdst
      e.pdstValid  := src.pdstValid
      e.wakes      := src.stkPush || (src.autoStoreAn && src.pdstValid)
      e.stkPush    := src.stkPush
      e.eaAutoDrop := src.autoStoreAn && !src.writesNzvc
      e.rmwStore   := (src.memOp === MemOp.STORE) && !src.pdstValid &&
                      !src.writesNzvc && !src.stkPush
      e.crackDrop  := src.crackDrop
      e.keepCommit := src.keepCommit
      e.nzvc       := src.storeNzvc
      e.nzvcWrite  := src.writesNzvc
      e.nzvcDst    := src.pNzvcDst
      // ROOT CAUSE of the P2.7 ported-corpus regression's 15 HANGs (movea_sp_sp_plain_load,
      // cmpa_word_imm_sentinel_branch, all 7 tmp1_reuse_loop_then_crack* variants, ...):
      // this push MUST be gated on `!sqFlushSig`, LOCALLY and in the SAME cycle, mirroring
      // StoreQueue's own `when(io.alloc.valid && !io.flush)` alloc gate. `pendMem` and
      // the SQ ring are a lock-step PAIR -- every precise SQ entry has exactly one
      // pendMem entry, in the same order -- and `sqCompletion` (which advances
      // `pendReady`) is generated per SQ pop. If a flush drops the SQ alloc but the
      // pendMem push still lands, the two streams desynchronize BY ONE FOREVER: from
      // then on every precise store's drain replays the WRONG pendMem entry, so ROB
      // completion port 4 fires for a STALE robId while the real store's robId never
      // completes -> the ROB head parks on it permanently (HANG), and where the stale
      // robId is still live its An-auto/A7/NZVC writeback is applied with the wrong
      // data (WRONG ANSWER).
      //
      // Why the separate `when(sqFlushSig) { pendPush := pendReadyAfterThisCycle }`
      // rollback below does NOT cover this (it was written believing it did): that
      // block is a PLAIN component statement, while THIS code lives inside a
      // `StateMachine` state body, and SpinalHDL elaborates StateMachine bodies from a
      // pre-pop task -- i.e. AFTER every plain statement in the component. So the FSM's
      // `pendPush := pendPush + 1` is emitted LAST and silently WINS over the rollback
      // (confirmed in the generated Verilog: the rollback assignment precedes both FSM
      // increments in the same always block, so the increments override it). The
      // rollback is still needed and still correct for the MULTI-cycle case (entries
      // pushed on earlier cycles whose drain the flush cancels); this gate handles the
      // same-cycle case the rollback structurally cannot reach.
      //
      // D1 also gates P3's store-allocation arm directly with `!sqFlushSig`, but keep
      // this local gate as the lock-step invariant at the actual pendMem write site.
      when(!sqFlushSig) {
        pendMem(pendPush) := e
        pendFault(pendPush) := False   // fresh slot: clear any stale fault flag from a prior occupant
        pendOrphan(pendPush) := False  // ditto for the orphan mark (Part 127)
        pendPush := pendPush + 1
      }
    }

    // ---- drive completion / writeback / wakeup from the registered stage ----
    // A FAULTED access still completes (marks the ROB entry done) but writes NO
    // register / wakes nothing — its result is replaced by the precise exception.
    completionPort.valid   := compValid
    completionPort.payload := compRobId
    intW.valid     := compValid && compPdstValid && !compIsFault
    intW.address   := compPdst
    intW.data      := compData
    intByp.valid   := compValid && compPdstValid && !compIsFault
    intByp.address := compPdst
    intByp.data    := compData
    // NZVC writeback + bypass for a memory MOVE (mirrors the int path; the
    // bypass forwards to a dependent flag-reader issuing the same cycle, exactly as
    // the ALU EU's NZVC bypass).
    nzvcW.valid     := compValid && compNzvcWrite && !compIsFault
    nzvcW.address   := compNzvcDst
    nzvcW.data      := compNzvc
    nzvcByp.valid   := nzvcW.valid
    nzvcByp.address := nzvcW.address
    nzvcByp.data    := nzvcW.data
    // X writeback + bypass for the RTR CCR-restore load (X := loaded[4]).
    xW.valid     := compValid && compXWrite && !compIsFault
    xW.address   := compXDst
    xW.data      := B(compX)
    xByp.valid   := xW.valid
    xByp.address := xW.address
    xByp.data    := xW.data
    // Dynamic load-wakeup: a completing LOAD or a STACK-PUSH store (both produce an
    // int physreg) broadcasts; a plain store completes too but writes no register.
    val registeredIntWake = compValid && compWakes && compPdstValid && !compIsFault
    wakeupPort.valid   := (if(earlyIntWakeup) nextIntWake.valid else registeredIntWake)
    wakeupPort.payload := (if(earlyIntWakeup) nextIntWake.payload else compPdst)
    GenerationFlags.simulation {
      val announced = RegNext(nextIntWake.valid) init False
      val announcedDst = RegNextWhen(nextIntWake.payload, nextIntWake.valid)
      assert(announced === registeredIntWake,
        "LsEuPlugin: early integer wakeup did not match next-cycle writeback", FAILURE)
      when(announced) {
        assert(announcedDst === compPdst,
          "LsEuPlugin: early integer wakeup announced the wrong physical register", FAILURE)
      }
    }
    // Dynamic NZVC-wakeup: a completing NZVC-writing LS op (a memory MOVE or an
    // RTR CCR-restore) broadcasts its pNzvcDst the cycle its NZVC lands in the PRF. The IQ
    // holds a flag-reader of that NZVC until this fires (the static scoreboard cannot —
    // an LS op generates no static ALU/branch wakeup event).
    wakeupNzvcPort.valid   := compValid && compNzvcWrite && !compIsFault
    wakeupNzvcPort.payload := compNzvcDst
    // MMU access-fault completion (registered, alongside the comp* stage).
    faultCompletionPort.valid           := compValid && compIsFault
    faultCompletionPort.payload.robId   := compRobId
    faultCompletionPort.payload.faultAddr  := compFaultAddr
    faultCompletionPort.payload.write      := compFaultWr
    faultCompletionPort.payload.sizeBits   := compFaultSize
    faultCompletionPort.payload.supervisor := compFaultSup
    faultCompletionPort.payload.atc        := compFaultAtc

    // Compatibility/debug summary of front occupancy. D1 invalidates all unlaunched
    // stage valids on flush instead of carrying one sticky poison bit through an FSM.
    val busy = tValid || p3Valid || p4Valid
    busy.simPublic(); s1Valid.simPublic()
    sqFlushSig.simPublic()

    // compValid is a single-cycle pulse: default-clear, re-set only by a capture.
    // compNzvcWrite likewise (so a stale store's NZVC write does not linger).
    // compIsFault likewise (so a non-faulting access never lingers a stale fault).
    compValid := False
    compNzvcWrite := False
    compXWrite := False
    compIsFault := False
    // Default-clear; set True by captureCompletion/captureFault below (read AFTER
    // the fsm closes, by the deferred-replay arbitration -- see its comment).
    liveCompletionFires := False

    // The untagged response belongs to the oldest SENT aligned descriptor.  This
    // block is after the comp*/liveCompletionFires default clears, so its capture is
    // the effective writer for the cycle.  A front completion observes
    // `backCompFires` below and yields; a precise-store replay observes the resulting
    // liveCompletionFires and retries.  A same-cycle flush poisons immediately even
    // though alignedPoisoned itself is registered.
    //
    // `alignedRspEntry`/`alignedRspIsPoison`/`alignedRspIsSplitA` are declared up at
    // the ring-bookkeeping site above (the abort-pointer-skip logic needs them too);
    // reused here unchanged.
    //
    // Split slot A holds the raw line for slot B's merge -- captured into
    // `splitMergeLine` below rather than into a per-descriptor field, because
    // strict ring FIFO order (send order == response order, and a pair's two
    // halves are always contiguous -- see the class-level comment on
    // `AlignedLoadCtx`) guarantees at most ONE pair can ever be in this
    // "slot A resolved, slot B not yet resolved" window at a time: slot B is
    // always the very next thing sent (once `alignedSendHeld` clears) and the very
    // next response processed, so nothing else can land in between. A genuine
    // fault on slot A is handled by `alignedRspAbortsPair` above (ring bookkeeping)
    // and completes the instruction as a fault HERE, exactly like the aligned
    // (non-split) fault arm; slot B is never sent for that case, so it can never
    // reach this block at all.
    //
    // ── CHECKED, NOT ARGUED (2026-09-17, backlog item 9) ─────────────────────
    // Everything above is an ARGUMENT, and the register it defends had no valid
    // bit, no pairing tag, no reset and no squash clear.  Its correctness rested
    // entirely on a coupling that is implicit and lives somewhere else: the only
    // thing stopping a LATER pair's slot B merging against a PREVIOUS pair's
    // residual line is that a flush poisons BOTH halves, so slot B's own capture
    // is skipped too.  That coupling is in `alignedPoisoned`'s bookkeeping, not
    // in the merge, and nothing here would notice if it broke -- a longword
    // assembled from two unrelated lines still looks like an address, which is
    // precisely the shape of the wrong-PC-on-RTE defect.
    //
    // Three things make it self-defending instead:
    //
    //  1. A VALID bit, set when slot A captures and cleared when slot B consumes.
    //  2. A PAIRING TAG -- the pair's robId.  Slot A and slot B are two halves of
    //     ONE instruction, so their `bk.robId` is the same; a slot B whose robId
    //     does not match the line in the register is, by construction, not the
    //     partner of whatever put it there.
    //  3. A SQUASH CLEAR.  On `sqFlushSig` the line is zeroed and the valid bit
    //     dropped, so a stale line cannot outlive the flush that orphaned it even
    //     if the poison coupling ever stops holding.  Clearing to a CONSTANT is
    //     what makes the residue deterministic rather than another instruction's
    //     data, and it is free: `when(cond) { reg := 0 }` maps to the flops' own
    //     synchronous-reset pin, not to 128 LUTs of mux on their D.
    //
    // The pairing check itself is a simulation assertion rather than a hardware
    // mux, deliberately: a mismatch means the ring's send/response ordering has
    // ALREADY desynced, which is a deeper bug than this register, and gating the
    // merge on it in hardware would cost a 128-bit mux on the merge path to
    // substitute one wrong answer for another.  What hardware guarantees is the
    // part that actually reduces harm -- that the residue after a squash is a
    // known constant, not a previous instruction's line.
    val splitMergeLine  = Reg(Bits(128 bits)) init B(0, 128 bits)
    val splitMergeValid = RegInit(False)
    val splitMergeRob   = Reg(UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)) init 0
    // Test-visibility only (LsEuSplitRingSpec's squash-clear fence); no-op for
    // synthesis, same idiom as DcachePlugin's `earlyProbeStale.simPublic()`.
    splitMergeLine.simPublic()
    splitMergeValid.simPublic()
    when(alignedRspFire && !alignedRspIsPoison) {
      when(alignedRspIsSplitA && !dcache.loadRsp.payload.fault) {
        splitMergeLine  := dcache.loadRsp.payload.line
        splitMergeValid := True
        splitMergeRob   := alignedRspEntry.bk.robId
      } otherwise {
        val suppressForLaterPrivCheck = alignedRspEntry.bk.needsSupervisor &&
                                        !alignedRspEntry.bk.xlateSup
        when(dcache.loadRsp.payload.fault && !suppressForLaterPrivCheck) {
          captureFaultDesc(alignedRspEntry.bk, alignedRspEntry.vaddr,
                           alignedRspEntry.size, atc = false)
        } elsewhen(alignedRspEntry.twoAccess && alignedRspEntry.splitSecond) {
          // Slot B: merge slot A's captured line with this cycle's own line using
          // the ORIGINAL access offset/size (mirrors the old bkFsm WAIT_B arm's
          // `extractCross(lineA, loadRsp.line, llReg.vaddr(3:0), llReg.size)`).
          //
          // backlog item 9: the line being merged must be THIS pair's slot A, and
          // it must still be there.  Both are invariants of the ring's ordering,
          // so they are CHECKED every time the merge runs rather than left as the
          // paragraph above.
          GenerationFlags.simulation {
            assert(splitMergeValid,
              "LsEuPlugin: split slot B merged against an EMPTY splitMergeLine -- " +
              "slot A never captured, or a squash cleared it and slot B was not " +
              "poisoned with it. The merged longword would be half constant zero.",
              FAILURE)
            assert(!splitMergeValid || (splitMergeRob === alignedRspEntry.bk.robId),
              "LsEuPlugin: split slot B merged against ANOTHER PAIR's line " +
              "(splitMergeRob != this entry's robId) -- the aligned ring's " +
              "send/response ordering has desynced. The merged longword is " +
              "assembled from two unrelated lines.",
              FAILURE)
          }
          val merged = m68k040.cache.DcacheByteLane.extractCross(
            splitMergeLine, dcache.loadRsp.payload.line,
            alignedRspEntry.mergeOff, alignedRspEntry.size)
          captureCompletionDesc(alignedRspEntry.bk, merged, alignedRspEntry.size)
          splitMergeValid := False   // consumed
        } otherwise {
          captureCompletionDesc(alignedRspEntry.bk, dcache.loadRsp.payload.data,
                                alignedRspEntry.size)
        }
      }
    }
    // backlog item 9: a squash orphans whatever slot A left here.  Elaborated
    // AFTER the capture above so it wins on the edge a flush and a slot-A
    // response coincide -- that response is being poisoned anyway.  The 128-bit
    // clear costs no LUTs (it is the flops' synchronous-reset pin).
    when(sqFlushSig) {
      splitMergeLine  := B(0, 128 bits)
      splitMergeValid := False
    }

    // ── SPLIT-ACCESS REPLAY FSM: owns the cold two-line D-cache access ─────────
    // Declared before the elastic front's arbitration so `bkFsm.isActive(...)` is
    // available to completion-collision guards. It reads `bkStart`/`bkPoisoned`/`bkCtx`/
    // `llReg` (all declared above) and writes only back-owned state, so there is no
    // elaboration-order dependency in the other direction.
    val bkFsm = new StateMachine {
      val BK_IDLE = new State with EntryPoint
      val LAUNCH  = new State   // split replay: launch slot A off llReg (FMax #1)
      val WAIT_A  = new State   // cross: slot A accepted, awaiting line A
      val WAIT_B  = new State   // cross: slot B accepted, awaiting line B -> merge

      BK_IDLE.whenIsActive {
        // spec §5.5 — THE SECOND correctness fix. This housekeeping used to live in the
        // FRONT's IDLE. Once the front can be in IDLE while a CROSS load is mid-flight
        // in WAIT_A (llReg.bDone set, slot B not yet launched), clearing it from the
        // front would re-point the back's slot-B cache command at slot A's address:
        // the already-fixed cross-line-store-after-load hazard reappearing in a new
        // form. It is BACK-owned now, cleared only when the back is genuinely idle.
        llReg.bDone := False
        when(bkStart) { goto(LAUNCH) }
      }

      LAUNCH.whenIsActive {
        when(coreLsLoadAdmit && dcache.loadCmd.ready) {
          // Slot A accepted -> drop loadCmd.valid (do NOT re-issue slot A while WAIT/
          // WAIT_A awaits its response). For a cross access WAIT_A re-asserts the launch
          // for slot B (bDone) once slot A's line lands.
          llReg.valid := False
          // Only split accesses enter this FSM; aligned loads use the descriptor ring.
          aDone := False
          goto(WAIT_A)
        }
      }

      WAIT_A.whenIsActive {
        when(coreLsRspValid && !aDone) {
          when(dcache.loadRsp.payload.fault) {
            when(!bkPoisoned) {
              captureFaultDesc(bkCtx, llReg.vaddr, llReg.size, atc = false)
            }
            llReg.valid := False
            bkBusy      := False
            goto(BK_IDLE)
          } otherwise {
            lineA       := dcache.loadRsp.payload.line
            aDone       := True
            llReg.bDone := True
          }
        }
        when(aDone) {
          llReg.valid := True
          when(coreLsLoadAdmit && dcache.loadCmd.ready) { llReg.valid := False; goto(WAIT_B) }
        }
      }

      WAIT_B.whenIsActive {
        when(coreLsRspValid) {
          // `lineOff`/`u1.size` are back-carried as llReg.vaddr(3 downto 0)/llReg.size
          // (spec §3.2(c)) — reading the live s1Va/u1 here would be the same class of
          // staleness bug as the supervisor read above.
          val merged = m68k040.cache.DcacheByteLane.extractCross(
            lineA, dcache.loadRsp.payload.line, llReg.vaddr(3 downto 0), llReg.size)
          when(!bkPoisoned) {
            when(dcache.loadRsp.payload.fault) {
              captureFaultDesc(bkCtx, llReg.addrB, llReg.size, atc = false)
            } otherwise {
              captureCompletionBk(merged)
            }
          }
          bkBusy := False
          goto(BK_IDLE)
        }
      }
    }

    // ── Front-vs-back completion arbitration (spec §3.2(f) / §5.3) ───────────
    // Today exactly one thing drives the shared comp* stage per cycle. After the split
    // three writers exist: the front's live capture, the back's live capture, and the
    // precise-store deferred replay. The replay already yields to `liveCompletionFires`.
    // Front-vs-back is arbitrated HERE, and the BACK WINS: `dcache.loadRsp` is a
    // 1-cycle Flow with no backpressure (DcacheTypes.scala:74), so a missed back
    // completion LOSES the load, while the front can simply hold one cycle.
    //
    // Derived from `bkFsm.isActive` rather than from an assignment inside either FSM,
    // so neither FSM's statement-emission order can affect it.
    val bkInWaitA     = bkFsm.isActive(bkFsm.WAIT_A)
    val bkInWaitB     = bkFsm.isActive(bkFsm.WAIT_B)
    // The back RELEASES this cycle (poisoned or not) — the front's Slice-1 WAIT_BK
    // uses this to free S1 on exactly the pre-split cycle.
    val bkCompletes   = (bkInWaitB && coreLsRspValid) ||
                        (bkInWaitA && coreLsRspValid && dcache.loadRsp.payload.fault)
    bkCompletes.simPublic()
    // The back actually WRITES comp* this cycle (a poisoned back load writes nothing,
    // so the front is free to use the stage). A split slot A's own successful
    // (non-terminal) response does NOT write comp* -- it only stashes a line for
    // slot B's later merge -- so it must NOT claim the stage either, or an
    // unrelated front completion due the same cycle would be wrongly held for a
    // cycle that never actually produced a completion.
    val backCompFires = (bkCompletes && !bkPoisoned) ||
                        (alignedRspFire && !alignedRspIsPoison && alignedRspTerminal)
    backCompFires.simPublic()

    // A precise store only enters this replay stream once it has reached the ROB
    // head and the SQ has drained it.  It is therefore architecturally older than
    // every still-unlaunched P2/P3/P4 operation.  Give it the shared completion
    // stage ahead of those younger operations; otherwise an II=1 stream of LEAs or
    // forwarded loads can starve the replay forever.  An already-launched cache
    // response remains highest priority because its Flow has no backpressure.
    val applyFast           = (pendApply === pendReady) && sq.io.sqCompletion.valid
    val applyBacklog        = pendApply =/= pendReady
    val preciseReplayWants  = applyFast || applyBacklog
    // ── FMax (the register cut): the front-gating claim is `applyBacklog` ALONE ──
    //
    // THE PROBLEM. `preciseReplayClaimsComp` used to be `preciseReplayWants &&
    // !backCompFires`, i.e. it included `applyFast`, and `applyFast` carries
    // `sq.io.sqCompletion.valid` -- which is a LIVE, same-cycle function of
    // `io.drainAck`, which is `dcache.storeAck`. Every front stage reads
    // `preciseReplayClaimsComp` through `olderThanTComp`/`olderThanTxComp`/
    // `olderThanP3Comp`, and the front stages' ready chain runs
    // p4Ready -> p3Ready -> txCanConsumeRsp -> txReady -> tReady -> s1Ready ->
    // `issuePort.ready` -> the IQ's `selPorts(3).fire`/`events` -> the per-slot
    // `triggers` clock enables. So the D-cache store pipe, the StoreQueue drain
    // handshake, the LS EU completion arbitration and the IssueQueue's slot
    // bookkeeping were ONE uncut combinational chain, four plugins wide.
    //
    // Measured on the standing 5.000ns OOC gate (xcku5p-ffvb676-2-e), that chain WAS
    // the core's worst path AND its dominant failing family -- 5402 of 5664 failing
    // endpoints rooted at `DcachePlugin_logic_stS3Valid`, worst -0.744ns:
    //
    //   stS3Valid -(fo=220)-> cbHitAckReg/storeMissBarrier -> the store FSM decode
    //     -> storeAckReg -> sq.io.drainAck (1.297ns) -> drainAckFire (1.554ns)
    //     -> terminalAck/sqCompletion.valid (1.837ns) -> preciseReplayWants (2.067ns)
    //     -> preciseReplayClaimsComp -> olderThanTxComp -> txCanConsumeRsp (3.309ns)
    //     -> txCanLeave -> txReady -> normalReqArm -> tCanLeave -> tReady
    //     -> s1Ready/issuePort.ready -> (IQ) selPorts(3).fire -> events (5.445ns)
    //     -> IssueQueuePlugin_logic_lines_7_ways_1_triggers[0]/D (5.755ns)
    //
    // 22 logic levels, 82.7% route delay. The SECOND family (`stS3Valid` ->
    // `sq/acceptedHalves`/`sq/sendPtr`, -0.679ns) traverses the SAME first eight hops
    // and only then diverges (via `txCanConsumeRsp` -> the LS EU's load-port drive ->
    // the D-cache's `s0Advance`/`stS1Advance` store-pipe arbitration -> `drain.ready`).
    // Both families are cut by cutting this ONE node.
    //
    // THE CUT. `applyBacklog` (`pendApply =/= pendReady`) is a comparison of two plain
    // REGISTERS; `applyFast` is the only live term. Claiming the stage on `applyBacklog`
    // alone makes `preciseReplayClaimsComp` -- and therefore the entire front ready
    // chain and the whole IQ `triggers`/`events` cone -- a function of registers plus
    // `backCompFires` only. The eight hops above (2.067ns of the 5.755ns path) are gone
    // from every path that reads it.
    //
    // WHY THIS IS SAFE (mutual exclusion is NOT what this signal provides). The
    // "at most one writer of comp* per cycle" invariant has never depended on
    // `preciseReplayClaimsComp`: the replay's own apply gate is
    // `(applyFast || applyBacklog) && !liveCompletionFires`, and BOTH front capture
    // sites (`captureCompletionFront`, `captureFaultFront`) and BOTH back capture sites
    // (`captureCompletionDesc`, `captureFaultDesc`) set `liveCompletionFires`. The
    // replay already yields to whoever actually took the stage, unconditionally.
    // `preciseReplayClaimsComp` is purely a PRIORITY/anti-starvation signal: it makes
    // younger front producers stand down so an older precise store's replay cannot be
    // starved forever by an II=1 stream of LEAs or forwarded loads.
    //
    // WHY IPC IS PRESERVED -- the shared stage still retires exactly one completion per
    // cycle, in BOTH schemes; only the ORDER changes in a tie:
    //   * NO collision (the overwhelmingly common case: a precise drain-ack lands with
    //     no front completion due the same cycle) -- `liveCompletionFires` is False, so
    //     the fast path applies THAT SAME CYCLE, bit-identically to before. The
    //     same-cycle fast path that `irq-nmi`'s one-extra-register-stage timing is
    //     calibrated against is fully retained on the uncontended path.
    //   * COLLISION (a front completion is due the same cycle a fast-path replay is) --
    //     before: the FRONT stalls one cycle and the replay goes first. After: the
    //     FRONT goes first and the replay slips to the next cycle. Either way ONE
    //     completion is produced this cycle and the other the next; no cycle is wasted,
    //     no stage idles, and the LS front pipe is strictly LESS backpressured than
    //     before (the P2/P3/P4 hold is gone).
    // So this is not a one-cycle tax on every transaction -- on the uncongested path it
    // costs literally nothing, and on the genuinely contended path it swaps the order of
    // two completions that were always going to be serialized against each other anyway.
    //
    // BOUNDED DEFERRAL (no new starvation). `pendReady` is advanced by an UNCONDITIONAL
    // `when(sq.io.sqCompletion.valid)` below -- it does not depend on the entry being
    // applied. So a fast-path replay that loses a collision is, on the VERY NEXT cycle,
    // `pendApply =/= pendReady`, i.e. `applyBacklog`, i.e. it claims the stage with full
    // priority over the front. The deferral is at most one cycle, once, per entry. And
    // `applyFast` requires `pendApply === pendReady`, so `applyFast` and `applyBacklog`
    // are mutually exclusive -- dropping `applyFast` here removes no backlog coverage.
    val preciseReplayClaimsComp = applyBacklog && !backCompFires
    preciseReplayWants.simPublic(); preciseReplayClaimsComp.simPublic()
    // Tripwire (simulation only, pruned from every netlist): the priority contract this
    // signal exists to enforce. If a future edit drops `preciseReplayClaimsComp` from
    // one of the `olderThan*Comp` terms -- or adds a sixth front capture site that does
    // not consult them -- an older precise replay could be starved by a younger front
    // producer, silently, with no functional failure until a device store wedges. Rather
    // than trust the four call sites to stay in sync, machine-check the invariant every
    // cycle (same pattern as `DcachePlugin`'s `stSubLastReg` drift check and this file's
    // own `txRspFire` identity check).
    GenerationFlags.simulation {
      assert(!(preciseReplayClaimsComp && frontCompFires),
        "LsEuPlugin: a younger FRONT completion took the shared comp* stage while an " +
        "older precise-store replay claimed it (preciseReplayClaimsComp priority violated)",
        FAILURE)
    }

    // ── D1 ELASTIC FRONT: P1 AGU -> P2 DTLB/VIPT -> P3 SQ -> P4 resolve ──
    // Each registered cut owns its context. Backpressure propagates only while a
    // concrete downstream resource is unable to consume; accept-last turnover keeps
    // a resident same-page aligned stream at II=1 after fill.
    def cancelProbeFor(robId: UInt): Unit = {
      val token = m68k040.Global.robTag(False ## False, robId, m68k040.cache.DLoadToken.Width,
                                        m68k040.cache.DLoadToken.RobIdBits)
      probeCancel      := True
      probeCancelToken := token
    }

    // P4 is oldest among the unlaunched front stages, so it has first claim after an
    // already-launched cache/split response. A stalled SQ overlap is re-queried from
    // P4 (the query mux above selects it) until the older store drains.
    val p4CanLeave       = Bool(); p4CanLeave := False
    val p4CompletionFire = Bool(); p4CompletionFire := False; p4CompletionFire.simPublic()
    val p4Front          = p4Ctx.xlate.front

    // ── Cache-inhibited accesses are PRECISE, in both directions ────────────────
    //
    // INHIBITED denotes a serialized DEVICE access, not merely an uncacheable one.
    // Two architectural rules follow, and both are enforced HERE -- at the single
    // point where a load actually launches a bus transaction (stores terminate at
    // p3, so this gate is load-only by construction):
    //
    //   1. A device READ has an architecturally visible side effect at the device,
    //      so it must never be performed speculatively, and it must observe every
    //      older store that has already been performed.  It therefore waits until
    //      this load IS the ROB head -- every program-older instruction retired --
    //      and the store queue has fully drained.
    //   2. A device WRITE is a FULL MEMORY BARRIER.  No younger memory access of
    //      any kind may pass it, so ANY load waits for the ring to drain while an
    //      inhibited store is resident (`sq.io.hasInhibitedStore`), regardless of
    //      address -- device command and status ports routinely differ.
    //
    // WHY EXPRESSED AS "WAIT UNTIL I AM THE HEAD" AND NOT "WAIT UNTIL THAT STORE
    // DRAINS".  The latter is not self-resolving: a load spinning in p4 holds
    // LS-EU/D-cache resources that the very drain it waits on can require, so the
    // two deadlock.  That is not hypothetical -- on 2026-08-22 this core wedged
    // permanently on `TST.B` of a VIA register (bus healthy, slave answering an
    // independent master, zero exceptions), and a debug halt, which is precisely
    // what stops the retry, released it.  Reaching the ROB head CANNOT deadlock:
    // the ROB retires strictly in order, a younger stalled load never prevents an
    // older instruction from retiring, and the SQ's own at-head drain is likewise
    // guaranteed to progress.  So the release condition is inevitable.
    val p4Inhibited = (p4Ctx.xlate.cmode === m68k040.cache.CacheMode.INHIBITED) ||
                      (p4Front.twoAccess &&
                       (p4Ctx.xlate.cmodeB === m68k040.cache.CacheMode.INHIBITED))
    val p4AtRobHead = robHeadValidIn && (p4Front.robId === robHeadIn)
    // AGE-QUALIFIED, and that is load-bearing -- see StoreQueue's `barrierEnt`.  A
    // younger store CAN allocate behind a load parked here, so gating on whole-ring
    // occupancy would deadlock: the younger store cannot commit until this load
    // retires, and this load would never launch.  Only OLDER entries gate.
    //
    // TERMINATION, both arms:
    //   * inhibited: at the ROB head every program-older instruction has retired, so
    //     every older store is COMMITTED, and committed entries drain unconditionally
    //     at the ring head -- no ROB dependency at all.  `olderStore` therefore clears.
    //   * ordinary:  an older inhibited store is precise and drains when IT is the ROB
    //     head, which it reaches because a younger parked load never prevents an older
    //     instruction from retiring.  `olderInhibitedStore` therefore clears.
    sq.io.barrier.robId := p4Front.robId
    // ── Preemption interlock (post-f5f9fe13 hardening) ──────────────────────────
    // Reaching the ROB head is necessary but not SUFFICIENT for a device read to be
    // safe to launch: two more sources can still discard this exact head on a LATER
    // cycle, after it has already fired the bus command, silently costing the
    // device a real clear-on-read/pop side effect on re-execution:
    //
    //   * `irqPreemptPendingIn` -- an interrupt or trace exception is ALREADY known
    //     pending this cycle (mirrors StoreQueue's own `headPreciseReady` term
    //     exactly -- see StoreQueue.scala:270). Covers the case where preemption is
    //     recognized BEFORE this load would otherwise launch.
    //   * `debugHaltImminentIn` -- a debug automatic-halt (`haltAfterDue ||
    //     haltAfterRetireBlock`) is due for the CURRENT head. This is the EXACT
    //     pair already gating RobPlugin's own `retire0` for the halt-after
    //     successor; without it here, the successor could still launch its device
    //     read one cycle before `haltAfterDue` itself becomes true (a RegNext-
    //     delayed comparison), then get discarded by the debug-recover flush that
    //     follows -- same double-read hazard, different trigger.
    //
    // An interrupt/trace that becomes pending only AFTER this load has ALREADY
    // launched (irqPreemptPendingIn was False the launch cycle) is a separate,
    // narrower race -- closed by `inhibitedLoadBusySig` gating the ROB's own
    // interrupt/trace RECOGNITION below, not by anything here (this gate guards
    // only the LAUNCH decision itself). No such after-the-fact race exists
    // for the debug-auto-halt source: `debugHaltImminentIn` cannot even light up
    // for THIS head before the head itself becomes valid, so there is no earlier-
    // launch window to protect against — see the class-level doc comment on
    // `debugHaltImminentIn`.
    val p4PreemptSafe = !irqPreemptPendingIn && !debugHaltImminentIn
    val p4LaunchOk  = Mux(p4Inhibited,
                          p4AtRobHead && !sq.io.barrier.olderStore && p4PreemptSafe,
                          !sq.io.barrier.olderInhibitedStore)
    p4Inhibited.simPublic(); p4AtRobHead.simPublic(); p4LaunchOk.simPublic()
    when(p4Valid && !sqFlushSig && !excActive) {
      val fullForward = p4Ctx.fwdHit && !p4Front.twoAccess
      // `fwdSerial`: the held verdict was masked by the inhibited-store barrier -- keep
      // re-querying until one is captured with the barrier absent (it lifts exactly when
      // `sq.io.barrier.olderInhibitedStore` would, so this adds no new wait condition).
      val mustRetry    = p4Ctx.fwdStall || (p4Ctx.fwdHit && p4Front.twoAccess) || p4Ctx.fwdSerial
      when(fullForward) {
        when(backCompFires || preciseReplayClaimsComp) {
          frontCompHeld := True
        } otherwise {
          captureCompletionFront(p4Front, p4Ctx.fwdData)
          cancelProbeFor(p4Front.robId)
          p4CompletionFire := True
          p4CanLeave       := True
        }
      } elsewhen(mustRetry) {
        p4Ctx.fwdHit   := sq.io.fwd.rsp.hit && p4SqForwardAllowed
        // NOT `|| (hit && !allowed)`: a suppressed forward must fall through to the
        // launch gate below, not spin here.  See `p4LaunchOk`.
        p4Ctx.fwdStall := sq.io.fwd.rsp.stall
        p4Ctx.fwdData  := sq.io.fwd.rsp.data
        p4Ctx.fwdSerial := sq.io.fwd.rsp.serial
        // MIRROR 1 of 4 (see `p4RetryQ`). `p4Ctx.xlate` is NOT rewritten in this arm,
        // so next-cycle `twoAccess` is this cycle's `p4Front.twoAccess`; and this arm
        // never sets `p4CanLeave`, so next-cycle `p4Valid` is still True (a later
        // `p4CanLeave`/flush mirror overrides this assignment if that changes).
        p4RetryQ := sq.io.fwd.rsp.stall ||
                    ((sq.io.fwd.rsp.hit && p4SqForwardAllowed) && p4Front.twoAccess) ||
                    sq.io.fwd.rsp.serial
      } otherwise {
        when(p4LaunchOk) {
        when(!p4Front.twoAccess) {
          when(alignedCanEnq) {
            alignedEnq := True
            p4CanLeave := True
          }
        } otherwise {
          // Split (misaligned / line-or-page-crossing) load: push slot A + slot B
          // as a contiguous pair into the SAME aligned descriptor ring ordinary
          // loads use, instead of draining the ring empty and running the separate
          // serial `bkFsm`. This needs only `alignedCanEnqSplit` (>= 2 free ring
          // slots, accounting for a same-cycle pop) rather than `alignedEmpty` (the
          // whole ring drained) -- the actual fix for the ring-drain stall. See the
          // long design comment on `AlignedLoadCtx` / `alignedEnqSplit` above for
          // the full mechanism (send-side hold, response-side merge, fault-abort).
          when(alignedCanEnqSplit) {
            alignedEnqSplit := True
            p4CanLeave      := True
          }
        }
        }
      }
    }
    val p4Ready = !p4Valid || p4CanLeave

    // ── inhibitedLoadBusySig: registered launch-through-consumption-plus-one-cycle
    // busy for an INHIBITED load's bus transaction -- the load-side mirror of
    // StoreQueue's `preciseDrainBusyReg` (StoreQueue.scala:584-590). Fed to the ROB
    // as a sibling of `preciseDrainBusyIn` so `normalIrqGate`/`traceNormalGate`
    // never recognize a NEW interrupt/trace while this load's device read is
    // genuinely outstanding -- closing the race `p4PreemptSafe` above cannot: an
    // interrupt/trace that only becomes pending AFTER this exact cycle (once the
    // load has already launched) is invisible to a launch-time gate by
    // construction.
    //
    // Launch: `alignedEnq` (single aligned access) or `alignedEnqSplit` (split
    // pair -- both halves now push through the SAME ring; see the class-level
    // comment on `AlignedLoadCtx`), each ONLY when `p4Inhibited` -- both events
    // also fire for ORDINARY loads sharing the same ring hardware, and busy must
    // never latch for those (that would silently reintroduce a one-at-a-time
    // chokepoint on the ordinary hot path, serializing every load behind a
    // phantom "precise" wait it never needed).
    //
    // Consume: the FIRST TERMINAL response (`alignedRspFire && alignedRspTerminal`)
    // observed while busy -- deliberately NOT plain `alignedRspFire`, which would
    // also fire on a split slot A's own non-terminal (merge-only) response and
    // clear busy one whole sub-access too early, reopening exactly the race
    // `435e9efb` closed (an interrupt/trace becoming recognizable between a split
    // inhibited load's two sub-reads). Responses drain STRICTLY in enqueue (FIFO)
    // order, and an inhibited load's pair enqueues no later than any load behind
    // it (`p4Inhibited` only launches once this load IS the ROB head, i.e. every
    // OLDER access has already retired and therefore already drained) -- so the
    // first terminal-consume event observed after launch is always THIS
    // instruction's own terminal response (slot B's, or slot A's own fault-abort)
    // -- no robId tag needed, exactly like `preciseDrainBusyReg` needs none.
    //
    // Deliberately does NOT distinguish poisoned-and-discarded from a genuine
    // successful completion: "the AXI response is actually consumed" (the
    // transaction resolves on the bus) is what makes it safe for a NEW inhibited
    // load or a NEW interrupt/trace recognition to proceed -- not whether this
    // particular instruction's result was kept.
    val loadBusyReg = RegInit(False); loadBusyReg.simPublic()
    val inhibitedLoadLaunch = (alignedEnq && p4Inhibited && !p4Front.twoAccess) ||
                              (alignedEnqSplit && p4Inhibited)
    val inhibitedLoadConsume = loadBusyReg && alignedRspFire && alignedRspTerminal
    // ── 2026-09-07 REWORK (hardware wedge ROM 0x40899664, bitstream 0xAFA3628F,
    // sim repro "inhibited-load IRQ storm" in ExecuteLockStepSpec): clearing busy
    // "+1 cycle past resolution" left a GAP between response consumption and the
    // µop's RETIRE — completion-port contention (`frontCompHeld`) or same-macro
    // trailing µops can hold retire for several cycles, and an interrupt
    // recognized in that gap SQUASHES a macro whose device read has already
    // happened. The re-execution then re-reads the device: measured 178 device
    // ARs for 150 architectural loads under an IRQ storm, and on hardware the
    // 53C96 FIFO ran exactly one word ahead of the ROM's blind pseudo-DMA drain,
    // pinning the CPU forever on a DAFB-handshake beat DRQ can never satisfy.
    //
    // Busy therefore stays asserted from LAUNCH until the launching µop has
    // RETIRED: the response has been consumed AND the ROB head has moved past
    // the launcher's robId. Head-advance is the retire witness — while busy no
    // interrupt/trace can be recognized (this signal gates normalIrqGate /
    // traceNormalGate), a parked head µop cannot be squashed by anything else
    // those gates admit, so the head leaving the launcher's robId proves the
    // µop retired rather than vanished. Once the load µop retires, any
    // remaining µops of the same macro are interrupt-immune via the ROB's
    // `p0.first` recognition term, so clearing here is exact — no window
    // remains on either side. Termination: a completed head µop always
    // retires (retire0 does not depend on interrupt recognition), so busy
    // cannot latch forever.
    val inhibitedLaunchRobId = Reg(cloneOf(p4Front.robId))
    val inhibitedRespSeen    = RegInit(False)
    when(inhibitedLoadConsume) { inhibitedRespSeen := True }
    val launcherStillAtHead = robHeadValidIn && (robHeadIn === inhibitedLaunchRobId)
    when(inhibitedLoadLaunch) {
      loadBusyReg          := True
      inhibitedRespSeen    := False
      inhibitedLaunchRobId := p4Front.robId
    }.elsewhen(loadBusyReg && inhibitedRespSeen && !launcherStillAtHead) {
      loadBusyReg := False
    }
    inhibitedLoadBusySig := loadBusyReg
    inhibitedLoadLaunch.simPublic(); inhibitedLoadConsume.simPublic()
    inhibitedRespSeen.simPublic()

    // P3 performs the registered-PA SQ operation. Stores terminate here; loads capture
    // the registered forwarding result into P4. An older P4/front-back completion wins
    // the shared comp* port, so a fast store simply holds for one cycle on collision.
    val p3CanLeave       = Bool(); p3CanLeave := False
    val p3ToP4           = Bool(); p3ToP4 := False
    val p3CompletionFire = Bool(); p3CompletionFire := False
    val p3Front          = p3Ctx.front
    val p3IsLoad         = p3Front.memOp === MemOp.LOAD
    val p3IsStore        = p3Front.memOp === MemOp.STORE
    val p3LateDataPending = lateStoreData.map(_ => p3Front.lateDataPending).getOrElse(False)
    val p3Reserved = if(reserveLateStore) RegInit(False) else False
    val p3ReservationFire = Bool(); p3ReservationFire := False
    val p3ReservedPublish = Bool()
    if(!reserveLateStore) p3ReservedPublish := False
    val lateDataCapture = Bool()
    if (lateStoreData.isEmpty) lateDataCapture := False
    lateStoreData.foreach { p =>
      p.queryTag := p3Front.lateDataTag
      // One registered clear cycle after the producer's busy bit clears. This
      // covers next-cycle LS early wakeups before reusing the data read port.
      val readyPrior = RegNext(p3Valid && p3LateDataPending && p.queryReady &&
        !sqFlushSig && !excActive) init False
      // A reserved store may now leave on its capture edge. Its readiness must
      // not qualify a different pending source replacing P3 on that same edge.
      if(reserveLateStore) when(p3CanLeave) { readyPrior := False }
      // The live source cannot be reallocated before this store completes. Once
      // qualified, readiness cannot revoke; keep the wide busy lookup off the
      // read-port arbitration/issue-ready cone.
      lateDataCapture := p3Valid && p3LateDataPending && readyPrior &&
        !sqFlushSig && !excActive
      when(lateDataCapture) {
        rdData.addr := p3Front.lateDataTag
        p3Ctx.front.storeData := rdData.data
        p3Ctx.front.storeNzvc := moveNzvc(rdData.data, p3Front.size)
        p3Ctx.front.lateDataPending := False
      }
      GenerationFlags.simulation {
        when(lateDataCapture) {
          assert(p.queryReady, "late store source readiness revoked before capture", FAILURE)
          assert(!issuePort.fire, "late store capture collided with a new data-port reader", FAILURE)
        }
        when(issuePort.fire && p.issuePending) {
          assert(u0.memOp === MemOp.STORE && u0.psrcBValid && !u0.pdstValid &&
            !u0.stkPush && u0.eaAuto === m68k040.decode.EaAuto.NONE && !u0.movesAliasStore,
            "late store data issued on an unsupported operation", FAILURE)
        }
        when(p3Valid && p3LateDataPending && !lateDataCapture) {
          assert((!sq.io.alloc.valid || p3ReservationFire) && !p3CompletionFire,
            "store published before its late data capture", FAILURE)
        }
      }
    }
    lateDataCapture.simPublic(); p3LateDataPending.simPublic()
    val olderThanP3Comp  = backCompFires || preciseReplayClaimsComp || p4CompletionFire
    if(reserveLateStore) {
      val reservedSlot = Reg(UInt(sq.ptrW bits))
      sq.io.reserveOnly := p3ReservationFire
      sq.io.publish.valid := p3ReservedPublish
      sq.io.publish.slot := reservedSlot
      sq.io.publish.robId := p3Front.robId
      sq.io.publish.data := rdData.data
      when(p3ReservationFire) {
        p3Reserved := True
        reservedSlot := sq.io.allocSlot
      }
      when(p3CanLeave || sqFlushSig || excActive) { p3Reserved := False }
      p3ReservedPublish := p3Reserved && lateDataCapture
      GenerationFlags.simulation {
        when(p3Reserved) {
          assert(p3Valid && p3IsStore && fastStore && !storePrivBlocked,
            "LSU: SQ reservation escaped its P3 store owner", FAILURE)
        }
        when(p3ReservedPublish) {
          assert(!sq.io.alloc.valid, "LSU: reserved data fill duplicated SQ allocation", FAILURE)
        }
      }
    }
    p3ReservationFire.simPublic(); p3ReservedPublish.simPublic(); p3Reserved.simPublic()
    when(p3Valid && !sqFlushSig && !excActive) {
      when(p3IsStore) {
        when(p3Reserved) {
          when(!p3LateDataPending || lateDataCapture) {
            when(olderThanP3Comp) {
              frontCompHeld := True
            } otherwise {
              // The data has either been captured already, or is being written
              // directly into the reserved SQ entry on this very edge.
              captureCompletionFront(p3Front, B(0, 32 bits))
              when(lateDataCapture) { compNzvc := moveNzvc(rdData.data, p3Front.size) }
              p3CompletionFire := True
              p3CanLeave := True
            }
          }
        } elsewhen(p3LateDataPending) {
          // Address has resolved; preserve ordering until data is resident.
          // If capacity only appears on the capture edge, use the ordinary
          // captured-data path next cycle; never create an unfillable reservation.
          if(reserveLateStore) when(fastStore && !storePrivBlocked && !sq.io.full && !lateDataCapture) {
            sq.io.alloc.valid := True
            sq.io.alloc.payload.precise := False
            p3ReservationFire := True
          }
        } elsewhen(storePrivBlocked) {
          when(olderThanP3Comp) {
            frontCompHeld := True
          } otherwise {
            captureCompletionFront(p3Front, B(0, 32 bits))
            p3CompletionFire := True
            p3CanLeave       := True
          }
        } elsewhen(!sq.io.full) {
          when(fastStore && olderThanP3Comp) {
            frontCompHeld := True
          } otherwise {
            sq.io.alloc.valid           := True
            sq.io.alloc.payload.precise := !fastStore
            when(fastStore) {
              captureCompletionFront(p3Front, B(0, 32 bits))
              p3CompletionFire := True
            } otherwise {
              deferCompletion(p3Front)
            }
            p3CanLeave := True
          }
        }
      } elsewhen(p3IsLoad) {
        when(p4Ready) {
          p3ToP4     := True
          p3CanLeave := True
        }
      } otherwise {
        // Defensive: P1 routes LEA/non-memory LS-cluster µops through P2, not P3.
        p3CanLeave := True
      }
    }
    val p3Ready = !p3Valid || p3CanLeave

    // P2T owns an accepted tagged translation until its held response is consumed.
    // Response A can advance while request B enters on the same cycle, so different
    // resident VPNs have the same II=1 cadence as a same-page stream.
    val txCanLeave       = Bool(); txCanLeave := False
    val txToP3           = Bool(); txToP3 := False
    val txFront          = txCtx
    val txTokenMatch     = xlate.rsp.payload.token === txToken
    val txMatchedRsp     = txValid && txWaitingRsp && xlate.rsp.valid && txTokenMatch
    val txFirstSplitRsp  = !txSecond && txCtx.twoAccess
    val olderThanTxComp  = backCompFires || preciseReplayClaimsComp ||
                           p4CompletionFire || p3CompletionFire
    val txCanConsumeRsp  = Bool(); txCanConsumeRsp := False
    when(txMatchedRsp && !sqFlushSig && !excActive) {
      when(xlateFault) {
        txCanConsumeRsp := !olderThanTxComp
      } elsewhen(txFirstSplitRsp) {
        txCanConsumeRsp := True
      } otherwise {
        txCanConsumeRsp := p3Ready
      }
    }
    // A stale response after flush has no owner and must vacate the one-entry DTLB
    // slot. A live matching response is backpressured until P3/completion can take it.
    xlate.rsp.ready := !txValid || !txWaitingRsp || !txTokenMatch ||
                       sqFlushSig || excActive || txCanConsumeRsp
    // ── FMax: `txRspFire` IS `txCanConsumeRsp` -- do not re-derive it through
    //          `xlate.rsp.ready` ──
    // This used to be
    //
    //   xlate.rsp.fire && txValid && txWaitingRsp && txTokenMatch && !sqFlushSig &&
    //     !excActive
    //
    // i.e. it re-entered `xlate.rsp.ready`'s six-way OR (through `fire`) and then ANDed
    // five of that OR's own disjuncts back on top of it. That put two extra LUT levels
    // between `txCanConsumeRsp` and `dcache.loadProbeResolve.valid` -- and
    // `txCanConsumeRsp` is fed by the completion arbitration
    // (`olderThanTxComp` -> `preciseReplayClaimsComp` -> the ROB retire-side signals),
    // while `loadProbeResolve.valid` heads straight into the D-cache's early-VIPT
    // way-tag compare and the 128-bit way mux that lands in `probeLineLine`. Measured
    // on the standing 5.000ns OOC gate, that is the core's worst path:
    // `RobPlugin_logic_head_reg[0]` -> the ROB payload Mem's async retire read -> the
    // retire-side exception/trace decode -> this arbitration -> `loadProbeResolve.valid`
    // -> `probeResolvedTag` -> `probeReadHitVec` -> `probeReadHitWay` -> `probeLineLine`
    // (24 logic levels, 6.090ns, WNS -1.109ns, replicated over all 128 bits).
    //
    // The two are EXACTLY the same signal. Let
    //   C = txValid && txWaitingRsp && txTokenMatch && !sqFlushSig && !excActive
    // Under C, every one of `xlate.rsp.ready`'s first five disjuncts is False
    // (`!txValid`, `!txWaitingRsp`, `!txTokenMatch`, `sqFlushSig`, `excActive`), so
    // `xlate.rsp.ready` degenerates to `txCanConsumeRsp` and
    //
    //   txRspFire = xlate.rsp.valid && C && txCanConsumeRsp
    //             = (txMatchedRsp && !sqFlushSig && !excActive) && txCanConsumeRsp
    //
    // because `txMatchedRsp` is by definition `txValid && txWaitingRsp &&
    // xlate.rsp.valid && txTokenMatch`. And `txCanConsumeRsp` defaults False and is
    // assigned ONLY inside `when(txMatchedRsp && !sqFlushSig && !excActive)` just above,
    // so `txCanConsumeRsp` already implies that entire guard. The left factor is
    // therefore redundant and
    //
    //   txRspFire == txCanConsumeRsp.                                              ∎
    //
    // `xlate.rsp.ready` itself is UNCHANGED (it is a real output to the DTLB and still
    // has to report readiness in the flush/quiesce/stale-response cases this expression
    // drops); only this internal consumer stops taking the long way round to a value it
    // already has. The tripwire pins the identity against the original expression.
    val txRspFire = txCanConsumeRsp
    GenerationFlags.simulation {
      assert(txRspFire === (xlate.rsp.fire && txValid && txWaitingRsp && txTokenMatch &&
                            !sqFlushSig && !excActive),
        "LsEuPlugin: txRspFire drifted from its original xlate.rsp.fire-derived definition",
        FAILURE)
    }

    // The registered DTLB response and the synchronous virtual-set RAM output meet
    // here on the aligned all-hit path. Qualify that exact read by token/physical
    // tag; no extra CAM, raw-way holding buffer, or second cache read is required.
    val txEffectiveCmode = Mux(cacheCtrl.map(_.dcacheEnabled).getOrElse(False),
      xlate.rsp.payload.cacheMode, m68k040.cache.CacheMode.INHIBITED)
    dcache.loadProbeResolve.valid := txRspFire && !xlateFault && !txSecond &&
                                     (txCtx.memOp === MemOp.LOAD) && !txCtx.twoAccess
    dcache.loadProbeResolve.payload.token :=
      m68k040.Global.robTag(False ## False, txCtx.robId, m68k040.cache.DLoadToken.Width,
                            m68k040.cache.DLoadToken.RobIdBits)
    dcache.loadProbeResolve.payload.paddr := s1Paddr
    dcache.loadProbeResolve.payload.cacheMode := txEffectiveCmode

    val txOut = XlatePipeCtx()
    // MOVEM far-page probe (task movem-translate-ahead): `front.twoAccess` is set
    // by the bulk `txOut.front := txCtx` copy below and then PATCHED here for the
    // probe-only case -- `allowOverride` (same idiom this file already uses for
    // every other "default then override" field, e.g. `excActive` above) is
    // required for SpinalHDL to accept the second, narrower assignment instead of
    // reporting an ASSIGNMENT OVERLAP elaboration error.
    //
    // A probe-only twoAccess that reaches here NEVER faulted (the fault branch
    // below raises the exception straight from `txCtx` and sets `txCanLeave`
    // WITHOUT `txToP3`, so it never reaches `txOut`/P3 at all -- see the
    // `xlateFault` branch). So this is always the "confirmed clean" case: the
    // far-page round trip already did its ONLY job (the DTLB lookup + ATC fill,
    // same side effects an ordinary translation already has), and its result
    // (paddrB/cmodeB) is now discarded -- clear `twoAccess` before it reaches
    // P3/P4, so NONE of the real second-access consumers downstream
    // (`alignedEnqSplit`'s real cache dispatch, the SQ split-store alloc, the SQ
    // forward split-query) ever see this as a genuine 2nd access. The first
    // element's OWN access (paddr/cmode, computed off slot A exactly as for any
    // ordinary non-split access) is completely unaffected.
    txOut.front.twoAccess.allowOverride
    txOut.front  := txCtx
    txOut.front.twoAccess := txCtx.twoAccess && !txCtx.probeOnlyB
    txOut.paddr  := Mux(txSecond, txPaddrA, s1Paddr)
    txOut.paddrB := Mux(txSecond, s1PaddrB, txCtx.addrB)
    txOut.cmode  := Mux(txSecond, txCmodeA, txEffectiveCmode)
    txOut.cmodeB := txEffectiveCmode

    // Task #191 fix: a needsSupervisor-tagged access executed in user mode should NEVER
    // reach translation in the first place -- it must be a vector-8 privilege violation,
    // not whatever the MMU/ATC's genuine translation fault reports (vector 2). Mirrors the
    // EXISTING `suppressForLaterPrivCheck` pattern used for the aligned back-stage bus-error
    // path above (`alignedRspEntry.bk.needsSupervisor && !alignedRspEntry.bk.xlateSup`,
    // `xlateSup` being that struct's own copy of `.supervisor`): when suppressed, do NOT
    // report a fault at all -- fall through to the SAME control path a genuinely successful
    // translation takes (`elsewhen(txFirstSplitRsp)` / the final `otherwise`, both of which
    // never call `captureFaultFront`/`cancelProbeFor` for an ordinary response either). The
    // entry proceeds as if untranslated data were valid; RobPlugin's own commit-time
    // privViolation check (independent of what this completion reported) correctly squashes
    // it at retire, and the OoO flush/rename-rollback machinery already hides every
    // wrong-path effect either way -- the same reasoning this file's own
    // `storePrivBlocked`/EQ decoupling comment (above) already establishes for the
    // analogous privileged-STORE case.
    val txSuppressForLaterPrivCheck = txCtx.needsSupervisor && !txCtx.supervisor
    when(txRspFire) {
      when(xlateFault && !txSuppressForLaterPrivCheck) {
        captureFaultFront(txCtx, Mux(txSecond, txCtx.addrB, txCtx.vaddr))
        cancelProbeFor(txCtx.robId)
        txCanLeave := True
      } elsewhen(txFirstSplitRsp) {
        txPaddrA := s1Paddr
        txCmodeA := txEffectiveCmode
        txSecond     := True
        txWaitingRsp := False
      } otherwise {
        txToP3     := True
        txCanLeave := True
      }
    }
    val txReady = !txValid || txCanLeave

    // A split's second translation reuses P2T and has priority over a younger P2
    // command. Ordinary P2 loads reserve both the translation response slot and the
    // virtual-probe queue before asserting either valid, making the two fires atomic.
    // W11's second half: suppress NEW probe launches for as long as a walker owns the
    // load port, so the cancel above cannot race a same-cycle launch. `dcLoadHeldByOther`
    // folds the walker-owner bit into the existing `!excActive` conjunction rather than
    // adding a separate gate, so the two hand-overs stay one mechanism.
    val dcLoadHeldByOther = excActive || walkerOwnsLoad
    // `xlateBArm` IS `txValid && txSecond && !txWaitingRsp` (declared with the P2T
    // FSM above); naming it here rather than re-spelling it is what makes the
    // payload-select/arbitration split at `reqFromSplit` visible at both ends.
    splitReqArm := xlateBArm && !sqFlushSig && !dcLoadHeldByOther
    // `!xlateBArm` rather than `!splitReqArm`: identical (this conjunction already
    // carries `!sqFlushSig && !dcLoadHeldByOther`, so under it `!splitReqArm` reduces
    // to `!xlateBArm`) and it keeps the split's command PRIORITY independent of the
    // arbitration gate, exactly as the payload select now is.
    normalReqArm := tValid && tIsMem && txReady && !xlateBArm &&
                    !sqFlushSig && !dcLoadHeldByOther && (!tIsLoad || dcache.loadProbe.ready)
    val normalReqFire = xlate.req.fire && !reqFromSplit && !excActive
    // ── LIVENESS TRIPWIRE (2026-09-15): a memory op parked in P2 with nothing holding it
    // architecturally must launch within a bounded time. `dcLoadHeldByOther` (exception
    // ownership, a walker owning the port) and a flush are the only legitimate
    // indefinite holds; everything else -- the D-cache's registered probe credit, the
    // DTLB's ready, P2T draining its previous request -- is a bounded wait (a device
    // read or a three-level table walk is hundreds of cycles, never tens of thousands).
    // The existing suites see a no-forward-progress bug here only as a generic
    // timeout; this names the site. Simulation-only, pruned from every netlist.
    GenerationFlags.simulation {
      val p2StallCycles = Reg(UInt(16 bits)) init 0
      val p2Parked = tValid && tIsMem && !dcLoadHeldByOther && !sqFlushSig && !normalReqFire
      when(p2Parked) { p2StallCycles := p2StallCycles + 1 } otherwise { p2StallCycles := 0 }
      assert(p2StallCycles < U(20000, 16 bits),
        "LsEuPlugin: a memory op has been parked in P2 for 20000 cycles without launching " +
          "(probe credit / DTLB ready / P2T never freed) -- no forward progress on load admission",
        FAILURE)
    }
    // Debug-only taps (zero synth impact): P2 launch gating terms.
    normalReqFire.simPublic(); dcLoadHeldByOther.simPublic(); dcache.loadProbe.ready.simPublic(); xlate.req.ready.simPublic()
    val splitReqFire  = xlate.req.fire && reqFromSplit && !excActive

    // P2 launches the tagged DTLB command and virtual-set probe. Memory operations
    // leave only on a real command handshake; LEA/non-memory retain their shallow
    // direct completion path.
    val tCanLeave       = Bool(); tCanLeave := False
    val olderThanTComp = backCompFires || preciseReplayClaimsComp ||
                         p4CompletionFire || p3CompletionFire

    when(tValid && !sqFlushSig && !excActive) {
      when(tCtx.leaAddr || !tIsMem) {
        when(olderThanTComp) {
          frontCompHeld := True
        } otherwise {
          captureCompletionFront(tCtx, B(0, 32 bits))
          tCanLeave := True
        }
      } otherwise {
        when(normalReqFire) { tCanLeave := True }
      }
    }
    val tReady = !tValid || tCanLeave

    // P1 is the only full IqContext register. Its accept-last ready chain is control-
    // only; the ALU-bypass data path still ends at s1Base/s1Data/s1Index exactly as
    // before. A flush invalidates every unlaunched stage in one edge.
    val s1ToT   = s1Valid && tReady
    val s1Ready = !s1Valid || s1ToT
    issuePort.ready := s1Ready && !sqFlushSig && !excActive && !lateDataCapture

    // Oldest-to-youngest valid updates, then accept-last replacements. Later writes
    // intentionally win when a stage consumes and accepts on the same edge.
    when(p4CanLeave) { p4Valid := False; p4RetryQ := False }   // MIRROR 2 of 4
    when(p3CanLeave) { p3Valid := False }
    when(txCanLeave) { txValid := False; txSecond := False; txWaitingRsp := False }
    when(tCanLeave)  { tValid := False }
    when(s1ToT) { s1Valid := False }

    when(p3ToP4) {
      p4Valid          := True
      p4Ctx.xlate      := p3Ctx
      p4Ctx.fwdHit     := sq.io.fwd.rsp.hit && p3SqForwardAllowed
      p4Ctx.fwdStall   := sq.io.fwd.rsp.stall
      p4Ctx.fwdData    := sq.io.fwd.rsp.data
      p4Ctx.fwdSerial  := sq.io.fwd.rsp.serial
      // MIRROR 3 of 4 (see `p4RetryQ`). Next-cycle `p4Valid` is True and next-cycle
      // `p4Ctx.xlate.front.twoAccess` is `p3Ctx.front.twoAccess` (the line above).
      p4RetryQ         := sq.io.fwd.rsp.stall ||
                          ((sq.io.fwd.rsp.hit && p3SqForwardAllowed) &&
                           p3Ctx.front.twoAccess) ||
                          sq.io.fwd.rsp.serial
    }
    when(txToP3) {
      p3Valid := True
      p3Ctx   := txOut
    }
    when(splitReqFire) {
      txWaitingRsp := True
      txToken      := reqDrvToken
    }
    when(normalReqFire) {
      txValid      := True
      txCtx        := tCtx
      txSecond     := False
      txWaitingRsp := True
      txToken      := reqDrvToken
    }
    when(s1ToT) {
      tValid := True
      captureFrontCtx(tCtx)
    }
    when(issuePort.fire) {
      s1Valid := True
      s1Ctx   := issuePort.payload
      s1Base  := base0
      s1Data  := data0
      s1Index := idxTerm0
    }

    // All front-stage effects above are gated off on flush/exception ownership, so
    // invalidating the owned valids is sufficient; only already-launched cache work
    // needs the descriptor poison-and-drain machinery.
    when(sqFlushSig || excActive) {
      s1Valid     := False
      tValid      := False
      txValid     := False
      txSecond    := False
      txWaitingRsp:= False
      p3Valid     := False
      p4Valid     := False
      p4RetryQ    := False   // MIRROR 4 of 4 (see `p4RetryQ`)
    }
    // Epoch is a squash generation, not an exception-active level. Toggling it on
    // every cycle of a serializing exception would eventually alias a stale tagged
    // response; one toggle per backend flush is sufficient to poison old work.
    when(sqFlushSig) { xlateEpoch := !xlateEpoch }
    // ── Precise-store deferred-completion replay: ready/apply/flush (root-cause
    // fix, post-P2.5 lock-step investigation) — see the `deferCompletion` comment
    // above for the full design. Placed AFTER the fsm (elaboration-order-later, so
    // `liveCompletionFires` below reads this cycle's FULLY resolved value). ──────
    //
    // The SQ confirms the OLDEST outstanding precise entry's drain -- `sqCompletion`
    // corresponds 1:1, in order, with `pendReady` (both advance exactly once per
    // precise-store SQ pop, success or fault).
    when(sq.io.sqCompletion.valid) {
      pendFault(pendReady) := sq.io.sqFaultCompletion.valid
      // Latch the WHOLE fault payload now (this Flow pulse is the only cycle it is
      // valid) so it can be replayed out `sqFaultCompletionPort` at apply time,
      // however much later that lands -- see `sqCompletionPort`'s port-driving
      // comment above the SQ instance for why it can no longer be a raw passthrough.
      pendFaultPayload(pendReady) := sq.io.sqFaultCompletion.payload
      // Part 127: carry the SQ's own orphan verdict for THIS pop into the slot, exactly
      // like `pendFault` above (the `sqCompletionOrphan` qualifier is only valid on the
      // `sqCompletion` cycle). The apply stage below discards an orphaned entry.
      pendOrphan(pendReady) := sq.io.sqCompletionOrphan
      pendReady := pendReady + 1
    }
    // Flush discards any NOT-YET-confirmed entries (mirrors StoreQueue's own
    // squash-uncommitted-on-flush `keep` rule). Already-confirmed entries
    // (< pendReady) are untouched -- see the design comment above for why that is
    // always safe. GOTCHA (found via a real observed bug: `bsr-loop-mispredict`'s
    // RAS-recovery test corrupted a LATER a7 fold after a same-cycle flush):
    // `pendReady` is a Reg -- reading it here (a SEPARATE `when` from the block
    // above) sees this cycle's OLD value even when `sq.io.sqCompletion.valid` ALSO
    // fires this exact cycle, so collapsing `pendPush` to the raw (pre-increment)
    // `pendReady` would discard the entry that is completing THIS SAME cycle --
    // whose memory write has ALREADY unconditionally happened (the drain already
    // fired), so it is by definition confirmed, not speculative, and must still be
    // replayed. Add the same +1 explicitly so a same-cycle
    // flush-races-a-completion never regresses `pendPush` below the true
    // (post-this-cycle) ready count.
    val pendReadyAfterThisCycle = pendReady + (sq.io.sqCompletion.valid ? U(1, pendPtrW bits) | U(0, pendPtrW bits))
    // Part 127 (defect SS8(a) of Part 126): this rollback was written as "discard every
    // entry the SQ has not yet confirmed", mirroring StoreQueue's own
    // squash-uncommitted-on-flush rule -- but StoreQueue's rule has an EXCEPTION that
    // was never mirrored here. `StoreQueue.headDrainInFlight` deliberately KEEPS an
    // uncommitted precise head whose drain half DcachePlugin has already accepted (the
    // Part 37 fix: its physical write already left the CPU, so the ring must be allowed
    // to unwind rather than be abandoned mid-sequence). That kept entry's `pendMem`
    // slot sits AT `pendReady`, so collapsing `pendPush` onto `pendReady` discards it
    // -- and the next precise alloc then overwrites the still-in-flight record. When
    // the old drain finally acks, `pendReady` advances over an entry that is no longer
    // the one that completed, and the two rings are off by one FOREVER: exactly the
    // "ROB head parks on it permanently (HANG)" failure `deferCompletion`'s own comment
    // describes, plus An/A7/NZVC write-backs applied with the wrong data.
    //
    // `RobPlugin.scala`'s `!preciseDrainBusyIn` retire gate closes most of this window,
    // but `preciseDrainBusyReg` is REGISTERED one cycle after `preciseLaunch`
    // (`StoreQueue.scala`), so a flush landing on the launch cycle itself still slips
    // through -- and `doFlushReg` fires from `debugRecoverEnter`/`debugPcApply`, which
    // are NOT retire-gated. That made halting this machine over JTAG during a precise
    // drain a guaranteed hang.
    //
    // Fix: take the keep decision from the component that makes it, rather than
    // re-deriving it. `io.flushKeptPrecise` is high on exactly the flush cycles where
    // StoreQueue kept an uncommitted precise head, and at most ONE such entry can ever
    // exist (a precise drain's `noAccepted`/`sendAtHead` gates make it the sole
    // occupant of the drain pipe), so the correction is exactly +1.
    when(sqFlushSig) {
      pendPush := pendReadyAfterThisCycle +
                  (sq.io.flushKeptPrecise ? U(1, pendPtrW bits) | U(0, pendPtrW bits))
    }
    // Replay the oldest ready-but-not-yet-applied entry's effects into the SHARED
    // comp*/completion stage. The arbitration above makes younger P2/P3/P4
    // completion producers yield to this replay; only an unbackpressured launched
    // cache response can still set `liveCompletionFires` and make the replay retry.
    // The backlog is bounded by the SQ's own depth (pendPush can never outrun
    // pendApply by more than `pendDepth`, since a new precise alloc is itself gated
    // on `!sq.io.full`).
    // A FAULTED entry's register/flag/wbObs effects are dropped entirely (no
    // compValid at all -- firing it would be at best redundant and at worst wrong,
    // since compIsFault also drives `faultCompletionPort`, a DIFFERENT port meant
    // only for the DTLB/MMU-fault path). `sqCompletionPort`/`sqFaultCompletionPort`
    // (ROB retire-eligibility, port 4) fire HERE -- the SAME cycle as the wbObs
    // replay, success or fault -- never off the raw (possibly much earlier)
    // `sq.io.sqCompletion`, so the ROB can never retire (and reallocate) this
    // robId before its own replay has actually landed. See the port-driving
    // comment above the SQ instance for the corruption this fixes.
    //
    // FAST PATH (no existing backlog): apply THIS SAME cycle off the RAW `sq.io.
    // sqCompletion`/`sqFaultCompletion` -- `pendFault`/`pendFaultPayload` for
    // THIS entry are not written until NEXT cycle (see the block above), so the
    // fast path reads the live SQ signals directly instead. Without this, EVERY
    // precise completion would gain a full extra cycle of latency vs. the
    // pre-this-fix baseline (`pendReady` is a Reg -- `pendApply =/= pendReady`
    // cannot see a same-cycle `sqCompletion.valid` at all) -- found via a real
    // observed bug: `irq-nmi` needs EXACTLY one extra register stage (RobPlugin's
    // own nmiEdge/nmiPending latch) beyond a direct-compare interrupt's timing,
    // calibrated against this SAME-cycle fast path; the always-registered version
    // added a SECOND stage on top and mis-timed the injection by a cycle.
    // BACKLOG PATH (an earlier entry is still waiting, typically after a launched
    // cache-response collision): apply from the REGISTERED
    // `pendFault`/`pendFaultPayload`, exactly as before.
    val applyFault   = Mux(applyFast, sq.io.sqFaultCompletion.valid, pendFault(pendApply))
    // Part 127 (defect SS8(b) of Part 126): an ORPHANED entry -- one a flush kept
    // mid-drain, whose ROB entry that same flush destroyed -- must be CONSUMED (so the
    // pendMem and SQ rings stay in lock step) but must drive NOTHING. Its robId now
    // belongs to whatever instruction the ROB allocated next, so `sqCompletionPort`
    // would mark that instruction complete and retire it without executing; its `pdst`
    // names a rename the flush already rolled back, so `intW`/`nzvcW`/`wakeupPort`
    // would write a physical register that the freelist may have re-handed out.
    val applyOrphan  = Mux(applyFast, sq.io.sqCompletionOrphan, pendOrphan(pendApply))
    when((applyFast || applyBacklog) && !liveCompletionFires) {
      val e = pendMem(pendApply)
      when(!applyOrphan) {
        sqCompletionPort.valid   := True
        sqCompletionPort.payload := e.robId
      }
      when(!applyFault && !applyOrphan) {
        compValid      := True
        compRobId      := e.robId
        compData       := e.data
        compPdst       := e.pdst
        compPdstValid  := e.pdstValid
        compIsLoad     := False
        compWakes      := e.wakes
        nextIntWake.valid := e.pdstValid && e.wakes
        nextIntWake.payload := e.pdst
        compStkPush    := e.stkPush
        compCcrRestore := False
        compEaAutoDrop := e.eaAutoDrop
        compRmwStore   := e.rmwStore
        compCrackDrop  := e.crackDrop
        compKeepCommit := e.keepCommit
        compDstArch    := e.dstArch
        compNzvc       := e.nzvc
        compNzvcWrite  := e.nzvcWrite
        compNzvcDst    := e.nzvcDst
        compX          := False
        compXWrite     := False
        compIsFault    := False
      } elsewhen(!applyOrphan) {
        // Fault branch. Also suppressed for an orphan: `sqFaultCompletionPort` targets
        // the SAME dead robId as `sqCompletionPort`, so delivering it would raise a
        // bus-error exception against an unrelated instruction.
        sqFaultCompletionPort.valid   := True
        sqFaultCompletionPort.payload := Mux(applyFast, sq.io.sqFaultCompletion.payload, pendFaultPayload(pendApply))
      }
      // The slot is consumed either way -- that is the whole point: an orphan must not
      // be left in the ring, or `pendApply` never catches `pendReady` again.
      pendApply := pendApply + 1
    }

    // ---- debug-only FSM-state observability (task #139 finding #1 investigation) ----
    // Zero synth impact (sim tap only, not referenced by any RTL logic).
    val dbgIsIdle    = !tValid && !p3Valid && !p4Valid; dbgIsIdle.simPublic()
    val dbgIsXlateB  = txValid && txSecond;              dbgIsXlateB.simPublic()
    val dbgIsXlate   = p3Valid;                         dbgIsXlate.simPublic()
    val dbgIsResolve = p4Valid;                         dbgIsResolve.simPublic()
    val dbgIsWaitSQ  = p3Valid && p3IsStore && sq.io.full
    dbgIsWaitSQ.simPublic()
    val dbgIsWaitBk  = False; dbgIsWaitBk.simPublic()
    // BACK-stage taps. NAMES DELIBERATELY UNCHANGED from the pre-split front states —
    // `MiHangTraceSpec` and `P27HangTraceSpec` reference them by these exact names.
    val dbgIsLaunch  = bkFsm.isActive(bkFsm.LAUNCH);  dbgIsLaunch.simPublic()
    // Retain the historical tap name for trace scripts; aligned loads no longer
    // occupy a BK_WAIT state.
    val dbgIsWait    = Bool(); dbgIsWait := False; dbgIsWait.simPublic()
    val dbgIsWaitA   = bkFsm.isActive(bkFsm.WAIT_A);  dbgIsWaitA.simPublic()
    val dbgIsWaitB   = bkFsm.isActive(bkFsm.WAIT_B);  dbgIsWaitB.simPublic()
    val dbgBkIdle    = bkFsm.isActive(bkFsm.BK_IDLE); dbgBkIdle.simPublic()
    sq.io.full.simPublic()
    u1.eaAuto.simPublic(); isLoad.simPublic(); isStore.simPublic()
    sq.io.fwd.query.robId.simPublic()
    sq.io.fwd.rsp.hit.simPublic(); sq.io.fwd.rsp.stall.simPublic()

    // ---- wbObs (sim-only whitebox) ----
    // Driven straight from the registered completion stage (already a register), so
    // the observed writeback aligns exactly with the completion/wakeup the ROB sees.
    val wbObs = WbObs()
    wbObs.valid     := compValid
    wbObs.robId     := compRobId
    wbObs.dstArch   := compDstArch
    wbObs.result    := compData
    wbObs.intWrite  := compPdstValid
    wbObs.nzvc      := compNzvc          // memory-MOVE flags OR RTR CCR-restore NZVC
    wbObs.nzvcWrite := compNzvcWrite     // memory-MOVE NZVC or RTR CCR-restore
    wbObs.x         := compX             // RTR CCR-restore X (loaded[4])
    wbObs.xWrite    := compXWrite
    // Reuse `divRem` as the generic "crack µop — DROP this commit record" marker: a
    // stack-push store is the leading crack µop of BSR/JSR (the trailing branch is the
    // macro instruction's single commit). Its A7 write is still folded into running A7.
    wbObs.divRem    := compStkPush || compCcrRestore || compRmwStore || compEaAutoDrop || compCrackDrop
    // PEA's push (stkPush store) is the macro instruction's single KEPT commit (it folds
    // A7 -= 4 and is the last µop — there is no trailing branch like BSR/JSR). keepCommit
    // overrides the stkPush divRem-drop in the whitebox.
    wbObs.keepCommit := compKeepCommit
    wbObs.simPublic()

    // ---- ccrObs (task #176) ----
    // Unlike the ALU EU's `wbObs` (deliberately delayed one extra cycle past its
    // completionPort, to match the sim-only `commitObs` reconstruction join), the LS
    // EU's `wbObs` above is ALREADY driven straight from the same registered
    // `compValid`/`compRobId`/... that drive `completionPort` — no extra delay. A plain
    // alias, so the ROB wiring can uniformly source the real `ccrCompletion` from
    // `.logic.ccrObs` across all 4 EUs regardless of which ones needed the #176 fix.
    val ccrObs = wbObs

    // ── Exception-unit cache arbitration MUX (LAST drivers — override the LS EU's
    // cache/TLB requests while the commit-side exception sequencer is ACTIVELY
    // accessing the cache). Placed at the end of `logic` (same scope) so the LS
    // EU's drives are the base and these override them. Gated on the exc's PER-PORT
    // request valids (NOT excActive) so the SQ drain / a quiescing LS access keeps
    // the port on cycles the exc isn't using it (the exception is serializing, so
    // any older LS store has already committed/drained by the time the exc stores).
    //
    // ── FMax: the D-cache load-command override selects on `excLoadCmdValid` ALONE ──
    // This `when`'s condition is the select of the ENTIRE `dcache.loadCmd` payload mux
    // (valid + 32-bit vaddr + 32-bit paddr + size + cacheMode + token): one node with
    // ~104-way fanout. `excLoadCmdValid` is a plain register (`ExceptionUnit.ldoValidReg`),
    // but `excActive` is a raw, live decode of the commit-side exception FSM's state
    // register (`RobPlugin.logic.excActive` = `ExceptionUnit.active` =
    // `!fsm.isActive(IDLE)`). ANDing the two put that raw FSM-state decode -- itself a
    // fanout-113 net -- at the HEAD of the core's longest cone, one full LUT level and
    // one long high-fanout route ahead of the vaddr mux it selects:
    //
    //   exc_fsm_stateReg[2] -> (LUT6) excActive && excLoadCmdValid -> (LUT3) loadCmd.vaddr
    //     -> DcachePlugin's 4-entry early-probe VA CAM -> earlyProbeHit
    //     -> loadProbePort.ready -> (LsEu) normalReqArm -> tCanLeave -> tReady
    //     -> s1Ready -> issuePort.ready -> (IQ) selPorts(3).fire -> the slot-compaction
    //     select cone -> the per-slot `triggers` clock enables.
    //
    // A measured OOC synth of this exact netlist had that family as the SOLE remaining
    // blocker: 23 logic levels, WNS -1.109ns, and all but 7 of the 421 failing endpoints
    // rooted at `exc_fsm_stateReg[2]`.
    //
    // `excActive` is REDUNDANT here, because `excLoadCmdValid => excActive` holds by
    // construction. Proof, from `ExceptionUnit.scala`:
    //   (1) `excLoadCmdValid` is `dcLoadCmd.valid`, which is exactly `ldoValidReg`.
    //   (2) `ldoValidReg` is set ONLY by `when(ldoVld && !ldoValidReg)`, and `ldoVld` is
    //       driven True at exactly six sites, every one of them inside the
    //       `whenIsActive` body of a non-IDLE state: E_VECREQ, R_SRREQ, R_PCREQ,
    //       R_PCREQ2, R_FMTREQ, F_HDRREQ.
    //   (3) Each of those six states has EXACTLY ONE exit, `when(dcLoadCmd.fire) { goto
    //       (<its own>WAIT) }`. On the cycle `ldoValidReg` is being set it is still
    //       False, so `dcLoadCmd.valid` is low, so `fire` is low -- the FSM cannot leave
    //       that state on the setting edge. The state therefore still holds the cycle
    //       `ldoValidReg` first reads True.
    //   (4) `ldoValidReg` is cleared by `when(dcLoadCmd.fire) { ldoValidReg := False }`,
    //       the SAME condition that advances the FSM to the WAIT state -- so it falls on
    //       the exact edge the state leaves, never later.
    //   (5) Hence `ldoValidReg` is True only while the FSM is in one of those six *REQ
    //       states, all of which are != IDLE, i.e. `active` (== `excActive`) is True.
    //   (6) The only three `goto(IDLE)` sites in the whole FSM are in E_REDIR, R_REDIR
    //       and S_REDIR; none of the three raises `ldoVld`, and every path into them
    //       passes through a *WAIT state entered on the `fire` that already cleared
    //       `ldoValidReg`. IDLE itself never raises `ldoVld`, and `ldoValidReg` is
    //       `RegInit(False)`.
    //
    // This is not a new assumption: the invariant is ALREADY load-bearing today, on the
    // ready side of this very handshake. `excLoadCmdReady := dcache.loadCmd.ready` at the
    // bottom of this block is NOT gated on `excActive`, so `dcLoadCmd.fire` is
    // `ldoValidReg && dcache.loadCmd.ready`. If `ldoValidReg` could ever be True with
    // `excActive` False, the command would be "accepted" and retired without this mux
    // ever presenting it to the cache -- the sequencer would then wait forever in its
    // WAIT state for a response that was never requested. The core already depends on
    // (5) holding; this only stops paying a critical-path LUT level to re-check it.
    //
    // The tripwire below machine-checks (5) every cycle in simulation rather than asking
    // the reader to trust the derivation, exactly as `DcachePlugin`'s `stSubLastReg`
    // drift check does for its own retime. It is vacuous in the standalone LS EU tests
    // (both signals default False there, see their `allowOverride` defaults above) and
    // live in every integrated DUT -- FullCoreSynth, FuzzDut, ExecuteLockStepSpec and
    // IpcBenchSpec all wire `excLoadCmdValid := exc.dcLoadCmd.valid` against the same
    // `excActive := rob.logic.excActive`.
    GenerationFlags.simulation {
      assert(!excLoadCmdValid || excActive,
        "LsEuPlugin: exception load command valid while the exception FSM is IDLE " +
        "(excLoadCmdValid => excActive violated)",
        FAILURE)
    }
    when(excLoadCmdValid) {
      // W23: `valid` -- NOT this `when`'s condition -- carries the admission predicate.
      // Keeping the header at the single registered `excLoadCmdValid` net preserves the
      // FMax property the long comment above establishes (that node is the select of the
      // whole ~104-bit payload mux); the extra conjunction rides on the 1-bit `valid`
      // instead, where it costs one LUT on a net that is not the payload mux select.
      //
      // The admission predicate is `excLoadAdmit` -- the SAME expression that gates the
      // FIFO push tag and `excLoadCmdReady` at the bottom of this block. A weaker
      // predicate here would reopen the very hole it closes, because
      // `dcache.loadCmd.ready` never references `valid`.
      dcache.loadCmd.valid         := excLoadAdmit
      dcache.loadCmd.payload.vaddr := excLoadCmdVaddr
      // Task 11: the PHYSICAL address now comes across explicitly instead of being
      // regenerated as the vaddr here. Every pre-existing exception-sequencer load is
      // still identity (ExceptionUnit's own `ldoPaddr` defaults to `ldoVaddr`), so this
      // is behaviour-identical for them; FRESTORE's header read supplies a real
      // DTLB-translated PA, which the old identity regeneration would have discarded.
      dcache.loadCmd.payload.paddr := excLoadCmdPaddr
      dcache.loadCmd.payload.size  := excLoadCmdSize
      // The exception sequencer always consumes `loadRsp.data` -- never the raw line --
      // so its commands must NOT be exempt from the extract wrap tripwire. Explicit
      // (not inherited from the LS branch above, whose value tracks the aligned ring).
      dcache.loadCmd.payload.lineOnly := False
      // (Historically this said "identity-physical, matching dcStore's exc-path
      // cacheMode". The address half of that is obsolete as of 2026-09-09 -- the
      // sequencer's loads carry a real DTLB-translated PA now. The CACHE MODE half
      // still holds and is what the rest of this comment is about.)
      //
      // Task P5.7 root-cause fix -- CACR.DE MUST be honoured here too. This mux is
      // the LIVE driver of the exception sequencer's frame/vector LOADS (FuzzDut /
      // FullCoreSynth wire only `excLoadCmdVaddr/Size` across, NOT the
      // ExceptionUnit's own `dcLoadCmd.payload.cacheMode` -- this line regenerates
      // it), so P5.6's "DE=0 => literally fully uncached", folded into `s2Cmode`
      // for LS-EU accesses, never reached the exception path. The resulting
      // coherency hole with DE=0:
      //   - an ordinary program store is INHIBITED: it writes AXI and NEVER touches
      //     the L1D array (Task P1.4 `stS2Inhibited` skips the RMW entirely);
      //   - this load, hardcoded WRITETHROUGH, still misses -> refills -> ALLOCATES
      //     a resident line (DcachePlugin's `doAllocate` only excludes INHIBITED);
      //   - from then on every program store to that line is invisible to the array
      //     and the NEXT exception-sequencer load of it HITS the stale copy.
      // That is precisely the universal trap-handler idiom -- read the stacked
      // frame, PATCH the stacked PC with an ordinary store to step over the faulting
      // instruction, RTE -- so RTE reloads the UNPATCHED PC and re-enters the same
      // fault forever: a deterministic HANG no cycle budget can clear. Confirmed by
      // cycle trace on priv_user_andi_sr_traps: the first trap's RTE read the patched
      // PC (line not yet resident), the second read back the exception unit's OWN
      // pushed frame word instead of the handler's patch (1-cycle response = array
      // hit), looping on the same instruction forever.
      // Same expression as `s2Cmode`'s own DE fold, for the same reason.
      dcache.loadCmd.payload.cacheMode := Mux(cacheCtrl.map(_.dcacheEnabled).getOrElse(False),
                                              m68k040.cache.CacheMode.WRITETHROUGH,
                                              m68k040.cache.CacheMode.INHIBITED)
      host.get[m68k040.services.SerializedMemoryContextService].foreach { ctx =>
        when(ctx.instructionActive) {
          // Serialized ordinary instructions obey CACR.DE just like the hot LSU.
          // Keep this fold at the live port even if the producer also applies it.
          dcache.loadCmd.payload.cacheMode := Mux(
            cacheCtrl.map(_.dcacheEnabled).getOrElse(False),
            ctx.instructionCacheMode, m68k040.cache.CacheMode.INHIBITED)
        }
      }
      dcache.loadCmd.payload.token := U(0x80, m68k040.cache.DLoadToken.Width bits)
    }
    excStoreReady := False
    when(excActive && excStoreValid) {
      sq.io.drain.ready := False
      excStoreReady := dcache.store.ready
      dcache.store.valid   := True
      dcache.store.payload := excStorePayload
    }
    when(dcache.storeAck && excStoreOutstanding) {
      excStoreOutstanding := False
    }
    // W23's store-side twin: qualified by the store owner for the same reason the load
    // side is. With a walker owning the store port `dcache.store.fire` is the WALKER's
    // handshake, and setting `excStoreOutstanding` off it would both hijack the walker's
    // ack and leave the exception sequencer's own store permanently unacked.
    when(dcache.store.fire && excActive && excStoreValid && !walkerOwnsStore) {
      excStoreOutstanding := True
    }
    // The ordinary LS P2/P2T pipe fully relinquishes this translation stream for the
    // entire duration `excActive` is held. Historically that was because every
    // exception-sequencer access was already physical on the cache ports; since Task 11
    // it is ALSO what makes the hand-off below safe.
    val lsXlateReqValid = !excActive && (normalReqArm || splitReqArm)
    xlate.req.valid              := lsXlateReqValid
    // ── Tripwire for the `reqFromSplit` payload-select/arbitration split (see the long
    // note at `reqFromSplit`). The equivalence argument there is a PROOF, but the thing
    // it protects -- a DTLB request that carries slot B's address under slot A's token,
    // or the reverse -- is a silent-mistranslation bug, so it is machine-checked rather
    // than merely reasoned about: whenever this port is actually presenting an LS
    // request, the (now ungated) payload select must equal the (gated) split arm.
    // Simulation-only; pruned from every netlist.
    GenerationFlags.simulation {
      assert(!lsXlateReqValid || (reqFromSplit === splitReqArm),
        "LsEuPlugin: the DTLB request PAYLOAD select disagrees with the arbitration-gated " +
        "split arm while the LS-side request is VALID -- the request would carry the " +
        "wrong half's address/supervisor/token",
        FAILURE)
    }
    xlate.req.payload.vpn        := reqDrvVpn
    xlate.req.payload.supervisor := reqDrvSup
    xlate.req.payload.write      := reqDrvWrite
    xlate.req.payload.token      := reqDrvToken
    // Task 11: hand the DTLB REQUEST port to the commit-side exception sequencer for its
    // FSAVE/FRESTORE state-frame accesses -- the first exception-path accesses that are
    // genuinely virtual. Structurally identical to the `excActive && excLoadCmdValid`
    // D-cache hand-off above (a LAST-driver `when` in the same scope, gated on the exc's
    // OWN per-port request valid rather than on `excActive` alone), and it can only fire
    // in cycles the LS pipe has already given the port up.
    //
    // The RESPONSE side needs nothing here: `xlate.rsp.ready` is already unconditionally
    // True while `excActive` (see its assignment above), so a response fires the cycle it
    // is valid and the exception unit samples it combinationally -- exactly how
    // `exc.dcLoadRsp` is wired straight off the D-cache rather than through this MUX.
    when(excActive && excXlateValid) {
      xlate.req.valid              := True
      xlate.req.payload.vpn        := excXlateVpn
      xlate.req.payload.supervisor := True    // the exception sequencer runs supervisor
      host.get[m68k040.services.SerializedMemoryContextService].foreach { ctx =>
        when(ctx.instructionActive) {
          xlate.req.payload.supervisor := ctx.instructionSupervisor
        }
      }
      xlate.req.payload.write      := excXlateWrite
      xlate.req.payload.token      := excXlateToken
    }
    excXlateReady := xlate.req.ready
    // Deliberately keyed off the LS-SIDE valid, NOT the muxed `xlate.req.valid`:
    // `umAccessRobId` tags a walk with the ROB id whose deferred U/M-bit commit it
    // belongs to, and an exception-sequencer translation belongs to no ROB entry. Feeding
    // the stale `reqDrvRobId` there would attribute the walk to an unrelated (possibly
    // already-retired) instruction.
    xlateRobIdSig                := Mux(lsXlateReqValid, reqDrvRobId, U(0, m68k040.Global.ROB_ID_W_DEFAULT bits))

    // ═══════════════════════════════════════════════════════════════════════════════
    // WALKER LEGS OF THE D-CACHE PORT MUX  (last drivers -- they override both the
    // ordinary LS pipe's base drives and the exception sequencer's overrides above)
    // ═══════════════════════════════════════════════════════════════════════════════
    //
    // Placed after the exception block deliberately. `excLoadAdmit`/`excStoreAdmit`
    // already require `ldOwner`/`stOwner` to be CORE, so the two overrides are mutually
    // exclusive by construction; being last simply makes that structurally obvious
    // instead of relying on the reader to re-derive it.

    // ── Admission predicates ───────────────────────────────────────────────────────
    // Provably pairwise exclusive with `coreLsLoadAdmit` and `excLoadAdmit`: those two
    // both require `ldOwner === CORE` and differ on `excLoadCmdValid`, while these two
    // require `ldOwner` to name their own walker. The sim assertion at the bottom of
    // this block machine-checks it every cycle rather than asking the reader to trust it.
    val walkerLoadAdmit = Vec(Bool(), 2)
    val walkerStoreAdmit = Vec(Bool(), 2)
    walkerLoadAdmit(walkIdxItlb) := walkLdReq(walkIdxItlb) &&
                                    (ldOwner === U(OWNER_ITLB, 2 bits)) && !ldFifoFull
    walkerLoadAdmit(walkIdxDtlb) := walkLdReq(walkIdxDtlb) &&
                                    (ldOwner === U(OWNER_DTLB, 2 bits)) && !ldFifoFull
    walkerStoreAdmit(walkIdxItlb) := walkStReq(walkIdxItlb) && (stOwner === U(OWNER_ITLB, 2 bits))
    walkerStoreAdmit(walkIdxDtlb) := walkStReq(walkIdxDtlb) && (stOwner === U(OWNER_DTLB, 2 bits))

    // ── Load leg ───────────────────────────────────────────────────────────────────
    when(walkerOwnsLoad) {
      dcache.loadCmd.valid   := walkLdSelValid && !ldFifoFull
      // Field-by-field rather than a whole-bundle assign followed by two overrides:
      // SpinalHDL's no-latch/no-override check rejects a same-scope field override of a
      // just-assigned bundle as a complete assignment overlap.
      dcache.loadCmd.payload.vaddr := walkLdSel.vaddr
      dcache.loadCmd.payload.paddr := walkLdSel.paddr
      dcache.loadCmd.payload.size  := walkLdSel.size
      // W1/W2/W3, REVISED (race audit, 2026-09-18): the policy is EXPORTED to the client
      // (`walkCmodePolicy`, driven where `walkCacheMode` is defined) and the client stamps
      // its own payload -- live for a descriptor read, latched for the U/M drain's RMW
      // pair. Stamping here re-derived `CACR.DE` independently at this leg and at the
      // store leg below, which is spatial agreement but not temporal agreement across the
      // hundreds of cycles one table search spans.
      dcache.loadCmd.payload.cacheMode := walkLdSel.cacheMode
      // W25: a RESERVED token per walker, never a don't-care. A don't-care token could
      // match a live early-probe entry on token AND vaddr and silently answer this
      // descriptor read out of that probe's captured data.
      dcache.loadCmd.payload.token := Mux(ldWalkSelDtlb,
        U(m68k040.cache.DLoadToken.WALK_DTLB, m68k040.cache.DLoadToken.Width bits),
        U(m68k040.cache.DLoadToken.WALK_ITLB, m68k040.cache.DLoadToken.Width bits))
      // A descriptor read consumes `loadRsp.data`, never the raw line -- so it must NOT
      // inherit the LS branch's `lineOnly` (which tracks the aligned ring and would
      // silently exempt the walker from the `DcacheByteLane.extract` wrap tripwire).
      dcache.loadCmd.payload.lineOnly := False
    }

    // ── Store leg ──────────────────────────────────────────────────────────────────
    when(walkerOwnsStore) {
      // W24: `sq.io.drain.ready := dcache.store.ready` is UNCONDITIONAL at its own site.
      // Without this hold a walker's U/M store silently consumes the SQ drain's `ready`
      // and a core store is permanently lost. The exception override already closes
      // exactly this hole for its own case; this is the same hold for the walker case.
      sq.io.drain.ready := False
      excStoreReady     := False
      dcache.store.valid   := walkStSelValid
      dcache.store.payload.paddr    := walkStSel.paddr
      dcache.store.payload.data     := walkStSel.data
      dcache.store.payload.size     := walkStSel.size
      dcache.store.payload.useStrb  := walkStSel.useStrb
      dcache.store.payload.strb     := walkStSel.strb
      dcache.store.payload.lineData := walkStSel.lineData
      dcache.store.payload.precise  := walkStSel.precise
      // W3: the client latched this at drain-arm time so it is the SAME value its own
      // re-read used -- the guarantee the old "same expression, same signal" wording
      // claimed but could not provide, because the two legs fire in different cycles.
      dcache.store.payload.cacheMode := walkStSel.cacheMode
    }

    // ── Grant machine, LOAD direction ──────────────────────────────────────────────
    // Base priority is `CORE-EXC > CORE-LS > walkers`, i.e. TODAY'S behaviour exactly
    // whenever no walker is pending. Fairness comes from a per-walker aging counter:
    // after `walkerAgeLimit` un-granted cycles that walker's `force` bit outranks
    // CORE-LS (never CORE-EXC's own presented command).
    //
    // A grant covers exactly ONE command and is released as soon as that command is
    // accepted, so CORE-LS is never held off for longer than it takes the cache to
    // accept one load -- and, because of the ownership FIFO, a walker's descriptor read
    // may be OUTSTANDING at the same time as up to four ordinary LS loads. Only the
    // command port is exclusive, not the pipeline.
    //
    // STARVATION, both directions. A walker cannot starve: `force` bounds the wait at
    // `walkerAgeLimit`. CORE-LS cannot starve either: a walker requests only while it
    // has an un-issued descriptor read, which is at most one command per memory round
    // trip, and the grant is released on acceptance.
    val ldWalkRr = RegInit(False)   // False => ITLB wins the tie-break next
    val ldAge = Vec.fill(2)(Reg(UInt(log2Up(walkerAgeLimit + 1) bits)) init 0)
    val ldForce = Vec(Bool(), 2)
    for (i <- 0 until 2) {
      ldForce(i) := ldAge(i) === U(walkerAgeLimit, ldAge(i).getWidth bits)
      when(!walkLdReq(i) || walkerOwnsLoad) {
        ldAge(i) := 0
      } elsewhen (!ldForce(i)) {
        ldAge(i) := ldAge(i) + 1
      }
    }
    val ldCand = Vec(Bool(), 2)
    for (i <- 0 until 2) ldCand(i) := walkLdReq(i) && (!coreLsLoadReq || ldForce(i))
    val ldPickDtlb = ldCand(walkIdxDtlb) && (!ldCand(walkIdxItlb) || ldWalkRr)
    val ldGrantOk = (ldOwner === U(OWNER_CORE, 2 bits)) && !ldBusyExc && !quiesceHold &&
                    !ldFifoFull && (ldCand(walkIdxItlb) || ldCand(walkIdxDtlb))
    when(ldGrantOk) {
      ldOwner  := Mux(ldPickDtlb, U(OWNER_DTLB, 2 bits), U(OWNER_ITLB, 2 bits))
      ldWalkRr := !ldPickDtlb
    }
    when(walkerOwnsLoad) {
      // Release on acceptance, or immediately if the granted walker stopped asking (it
      // was flushed, or its walk resolved between the grant decision and this cycle).
      when(!walkLdSelValid || (dcache.loadCmd.valid && dcache.loadCmd.ready)) {
        ldOwner := U(OWNER_CORE, 2 bits)
      }
    }

    // ── Grant machine, STORE direction (drain-to-zero) ─────────────────────────────
    // The store direction keeps drain-to-zero rather than borrowing the load side's
    // FIFO, because `storeAck` is a single untagged terminal pulse and `StoreQueue`
    // asserts on a stray one: identifying a store response positionally would need the
    // same FIFO on a direction that has no throughput case for it. A walker U/M store
    // is one store, issued once per walk that actually changes a descriptor byte.
    //
    // Note the deliberate ASYMMETRY with the load side: the store grant is NOT gated on
    // `!sq.io.drain.valid`. Handing the port over at the first genuinely idle cycle
    // costs the SQ at most one store's latency and removes any need for a store-side
    // starvation argument at all.
    val stWalkRr = RegInit(False)
    val stCandItlb = walkStReq(walkIdxItlb)
    val stCandDtlb = walkStReq(walkIdxDtlb)
    val stPickDtlb = stCandDtlb && (!stCandItlb || stWalkRr)
    val stGrantOk = (stOwner === U(OWNER_CORE, 2 bits)) && (coreStOutstanding === 0) &&
                    !dcache.store.fire && !excStoreValid && !excStoreOutstanding &&
                    !quiesceHold && (stCandItlb || stCandDtlb)
    when(stGrantOk) {
      stOwner  := Mux(stPickDtlb, U(OWNER_DTLB, 2 bits), U(OWNER_ITLB, 2 bits))
      stWalkRr := !stPickDtlb
    }
    when(walkerOwnsStore) {
      when(!walkStOutstanding && !walkStSelValid) { stOwner := U(OWNER_CORE, 2 bits) }
    }

    // ── Outstanding-transaction bookkeeping ────────────────────────────────────────
    val walkStFire = walkerOwnsStore && dcache.store.valid && dcache.store.ready
    when(walkStOutstanding && dcache.storeAck) { walkStOutstanding := False }
    when(walkStFire) { walkStOutstanding := True }

    val coreStFire = dcache.store.fire && !walkerOwnsStore
    val coreStAck  = dcache.storeAck && !walkStOutstanding
    // ── W13, THIRD consumer: the exception sequencer's own terminal ack ────────────
    // `ExceptionUnit.dcStoreAck` is wired STRAIGHT off `DcacheService.storeAck` by every
    // integrated DUT, unqualified. With two store clients that was safe: the sequencer
    // only stores after `E_DRAIN` has waited on `sqDrained`, so no SQ store can be
    // outstanding to ack in its place. A TABLE WALKER is a third client and breaks that,
    // and it is NOT enough that `stGrantOk` refuses a NEW grant while the sequencer has a
    // store presented -- a walker store granted EARLIER can still be outstanding when the
    // sequencer reaches `E_STORE`, and its ack then advances `E_STWAIT` while
    // `stoValidReg` is still set. The next `E_STORE` trips ExceptionUnit's own
    // "attempted to overwrite an unaccepted frame-store command" assertion.
    //
    // Found exactly that way: this fired in `ExecuteLockStepSpec`'s MOVEM store-fault
    // case before this term existed. Consume THIS signal rather than the raw `storeAck`.
    //
    // Deliberately NOT also qualified against the SQ's acks: that is pre-existing
    // behaviour protected by the sequencer's own `sqDrained` discipline, and narrowing it
    // here would be an unrelated change smuggled into this one.
    val excStoreAckOut = dcache.storeAck && !walkStOutstanding
    excStoreAckOut.simPublic()
    // 2026-09-18: the error pulse must be filtered by the SAME `walkStOutstanding`
    // term as the ack it rides with, or a TABLE WALKER store's bus error would be
    // attributed to the exception sequencer's frame push and double-fault the core for
    // someone else's failure. Structurally paired with the line above on purpose.
    val excStoreErrOut = dcache.storeErr && !walkStOutstanding
    excStoreErrOut.simPublic()
    when(coreStFire && !coreStAck) { coreStOutstanding := coreStOutstanding + 1 }
    when(!coreStFire && coreStAck && (coreStOutstanding =/= 0)) {
      coreStOutstanding := coreStOutstanding - 1
    }

    // W30: `excLoadOutstanding`'s CLEAR is the TAGGED term, not an unqualified
    // `dcache.loadRsp.valid`. The store side's untagged mirror is safe only because
    // `E_DRAIN`/`R_DRAIN` wait on `sqDrained`; there is no load-side analogue of that
    // invariant, so the clear has to be qualified rather than justified.
    when(coreExcRspValid) { excLoadOutstanding := False }
    when(excLoadAdmit && dcache.loadCmd.ready) { excLoadOutstanding := True }

    // ── Ownership FIFO push/pop ────────────────────────────────────────────────────
    // The pushed tag is encoded COMBINATIONALLY, on the cycle of the command handshake,
    // from the four admission predicates that actually gate the mux legs -- never read
    // from a separately-clocked owner register. Reading a register here would reopen the
    // same-cycle coherence hole from the other side: the mux would select off one value
    // and the tag record the other.
    val ldPushTag = UInt(2 bits)
    ldPushTag.simPublic()   // sim-only: lets a test BALANCE commands vs responses
                            // per owner tag. A per-tag mismatch is direct proof a
                            // response was delivered to the wrong requester.
    ldPushTag := U(LDTAG_CORE_LS, 2 bits)
    when(excLoadAdmit)                     { ldPushTag := U(LDTAG_CORE_EXC, 2 bits) }
    when(walkerLoadAdmit(walkIdxItlb))     { ldPushTag := U(LDTAG_ITLB, 2 bits) }
    when(walkerLoadAdmit(walkIdxDtlb))     { ldPushTag := U(LDTAG_DTLB, 2 bits) }
    when(dcache.loadCmd.valid && dcache.loadCmd.ready) {
      ldFifoTags(ldFifoPushPtr(log2Up(ldFifoDepth) - 1 downto 0)) := ldPushTag
      ldFifoPushPtr := ldFifoPushPtr + 1
    }
    when(dcache.loadRsp.valid) { ldFifoPopPtr := ldFifoPopPtr + 1 }

    // ── Route the responses back to the walkers ────────────────────────────────────
    for (i <- 0 until 2) {
      val tagI = if (i == walkIdxItlb) LDTAG_ITLB else LDTAG_DTLB
      val ownerI = if (i == walkIdxItlb) OWNER_ITLB else OWNER_DTLB
      walkClients(i).foreach { c =>
        // The staged request is what the admission logic saw, so the grant goes back to
        // the STAGE, not to the walker directly (the stage forwards the release).
        walkLdStaged(i).ready := dcache.loadCmd.ready && walkerLoadAdmit(i)
        c.walkLoadRsp.valid   := dcache.loadRsp.valid && (ldRspTag === U(tagI, 2 bits))
        c.walkLoadRsp.payload := dcache.loadRsp.payload
        c.walkStore.ready     := dcache.store.ready && walkerStoreAdmit(i)
        c.walkStoreAck        := dcache.storeAck && walkStOutstanding &&
                                 (stOwner === U(ownerI, 2 bits))
        c.walkStoreErr        := dcache.storeErr && walkStOutstanding &&
                                 (stOwner === U(ownerI, 2 bits))
      }
    }

    // ── W26: PRODUCTION wedge report for this new merge point ──────────────────────
    // A wedge here produces NO AXI grant to time out, so it is invisible to every
    // existing watchdog (the AXI D-merge's bounded-grant timer, the D-cache's own
    // diagnostic fault, and the halt-reason channel). Held grant with no progress on
    // either direction for `walkerWedgeLimit` cycles raises a sticky report which the
    // top level folds into the halt-reason channel.
    val walkGrantHeld = walkerOwnsLoad || walkerOwnsStore
    val walkGrantProgress = (dcache.loadCmd.valid && dcache.loadCmd.ready) ||
                            dcache.loadRsp.valid ||
                            (dcache.store.valid && dcache.store.ready) || dcache.storeAck
    // ═══ THE WATCHDOG OBSERVES THROUGH A PIPELINE STAGE ════════════════════════════
    // (2026-09-17, ROB head-pointer fanout family, ~29 failing setup paths named
    //  `RobPlugin_logic_head_reg => LsEuPlugin_logic_walkWedgeCnt_reg`.)
    //
    // `walkGrantProgress` contains `dcache.store.valid` and `dcache.loadCmd.valid`,
    // whose core legs are `sq.io.drain.valid` / the LS pipe's launch -- and BOTH carry
    // StoreQueue's `robIds(head) === io.robHeadIn` and `p4AtRobHead` in their cones.
    // That is how the ROB head pointer ends up feeding a WATCHDOG COUNTER: not by any
    // design intent, just by being upstream of "did the shared D-cache port move this
    // cycle". Registering the two observation terms cuts the entire cone -- the
    // counter's inputs become flops, so nothing upstream of them is timed against it.
    //
    // WHY A CYCLE OF LAG IS FREE HERE. This is a saturating watchdog with
    // `walkerWedgeLimit = 1 << 20`, and both terms are delayed by the SAME one cycle,
    // so the reset/increment relationship is preserved exactly and the count is merely
    // shifted. `walkerPortWedge` therefore latches one cycle later out of ~1,048,576 --
    // and it is a sticky diagnostic folded into the halt-reason channel, with no
    // datapath consumer whatsoever. IPC cost: zero cycles.
    val walkGrantHeldReg     = RegNext(walkGrantHeld)     init False
    val walkGrantProgressReg = RegNext(walkGrantProgress) init False
    val walkWedgeCnt = Reg(UInt(log2Up(walkerWedgeLimit + 1) bits)) init 0
    when(!walkGrantHeldReg || walkGrantProgressReg) {
      walkWedgeCnt := 0
    } elsewhen (walkWedgeCnt =/= U(walkerWedgeLimit, walkWedgeCnt.getWidth bits)) {
      walkWedgeCnt := walkWedgeCnt + 1
    }
    val walkerPortWedge = RegInit(False)
    when(walkGrantHeldReg && (walkWedgeCnt === U(walkerWedgeLimit, walkWedgeCnt.getWidth bits))) {
      walkerPortWedge := True
    }
    walkerPortWedge.simPublic()

    /** 2026-09-05 walker-stall observability (p141), read live over jtag_axi at
      * `DebugRegMap.OFF_STALL_GRANT`. Pure observation -- no new state (every bit
      * below already exists), no consumer, nothing feeds back into the datapath.
      *
      * This is the "who holds the walker grant" half of the capture. The D-cache
      * port is arbitrated between the CORE and the two table walkers, and since
      * arm C (`2db5bd3`) routed MMU table walks through L1D that arbiter sits
      * directly on the path the `0x40806b68` A-line exception entry takes. The
      * bits that matter, in order of what they discriminate:
      *
      *   ldOwner/stOwner   WHO holds each direction (CORE / ITLB / DTLB). If a
      *                     walker owns a port and never releases it, this names
      *                     which walker.
      *   walkerPortWedge   the design's OWN sticky detector for exactly that --
      *                     grant held with no progress for `walkerWedgeLimit`
      *                     cycles. If this reads 1 the stall is proven to be in
      *                     the walker/port hand-over and not upstream of it.
      *   ldGrantOk/stGrantOk  whether a NEW grant is currently possible. Both
      *                     low with the core wanting the port is the starvation
      *                     shape; `quiesceHold` is the reason they would be low
      *                     during an exception entry.
      *   coreStOutstanding the counter widened 3->4 bits by `56ad2d4`. It gates
      *                     `stGrantOk`, so a non-zero stuck value here is the
      *                     latent-wrap failure that commit predicted, observed. */
    val dbgStallGrantPack = Bits(32 bits)
    dbgStallGrantPack := B(0, 32 bits)
    dbgStallGrantPack(1 downto 0) := ldOwner.asBits.resize(2 bits)
    dbgStallGrantPack(3 downto 2) := stOwner.asBits.resize(2 bits)
    dbgStallGrantPack(4)  := ldGrantOk
    dbgStallGrantPack(5)  := stGrantOk
    dbgStallGrantPack(6)  := quiesceHold
    dbgStallGrantPack(7)  := walkGrantHeld
    dbgStallGrantPack(8)  := walkerOwnsLoad
    dbgStallGrantPack(9)  := walkerOwnsStore
    dbgStallGrantPack(10) := walkerPortWedge
    dbgStallGrantPack(11) := walkGrantProgress
    dbgStallGrantPack(15 downto 12) := coreStOutstanding.asBits.resize(4 bits)
    dbgStallGrantPack(16) := walkStOutstanding
    dbgStallGrantPack(17) := ldBusyExc
    dbgStallGrantPack(18) := ldFifoFull
    dbgStallGrantPack(19) := coreLsLoadReq
    dbgStallGrantPack(20) := walkLdReq(walkIdxItlb)
    dbgStallGrantPack(21) := walkLdReq(walkIdxDtlb)
    dbgStallGrantPack(22) := walkStReq(walkIdxItlb)
    dbgStallGrantPack(23) := walkStReq(walkIdxDtlb)
    dbgStallGrantPack(24) := dcache.loadCmd.valid
    dbgStallGrantPack(25) := dcache.loadCmd.ready
    dbgStallGrantPack(26) := dcache.loadRsp.valid
    dbgStallGrantPack(27) := dcache.store.valid
    dbgStallGrantPack(28) := dcache.store.ready
    dbgStallGrantPack(29) := dcache.storeAck
    dbgStallGrantPack(30) := excStoreOutstanding
    dbgStallGrantPack(31) := walkWedgeCnt =/= 0
    dbgStallGrantPack.simPublic()

    // ── Structural tripwires (simulation only; pruned from every synthesised netlist) ──
    GenerationFlags.simulation {
      val admitCount = coreLsLoadAdmit.asUInt +^ excLoadAdmit.asUInt +^
                       walkerLoadAdmit(walkIdxItlb).asUInt +^ walkerLoadAdmit(walkIdxDtlb).asUInt
      assert(admitCount <= U(1),
        "LsEuPlugin: more than one D-cache LOAD client was admitted in the same cycle -- " +
        "the ownership-FIFO push tag is no longer a well-defined one-hot", FAILURE)
      val stAdmitCount = walkerStoreAdmit(walkIdxItlb).asUInt +^ walkerStoreAdmit(walkIdxDtlb).asUInt
      assert(stAdmitCount <= U(1),
        "LsEuPlugin: both walkers were admitted to the D-cache STORE port in the same cycle",
        FAILURE)
      assert(!(dcache.loadRsp.valid && ldFifoEmpty),
        "LsEuPlugin: a D-cache load response arrived with an EMPTY ownership FIFO -- the " +
        "push/pop lockstep has desynchronised and every response after this one is " +
        "attributed to the wrong client", FAILURE)
      assert(!(walkStOutstanding && excStoreOutstanding),
        "LsEuPlugin: a walker U/M store and an exception-sequencer store were outstanding " +
        "at the same time -- the untagged storeAck cannot be demultiplexed", FAILURE)
      assert(!(walkStOutstanding && (coreStOutstanding =/= 0)),
        "LsEuPlugin: a walker U/M store was outstanding alongside a CORE store -- the " +
        "store direction's drain-to-zero hand-over has been violated", FAILURE)
      // W19's own tripwire: a walker must never be granted inside the maintenance
      // quiesce window, or `ExceptionUnit`'s written "nothing re-arms them" deadlock
      // proof for `S_DRAIN` becomes false.
      assert(!(quiesceHold && (ldGrantOk || stGrantOk)),
        "LsEuPlugin: a table walker was granted a D-cache port during the exception " +
        "sequencer's maintenance quiesce window", FAILURE)

      // ── REACHABILITY, because the assertion above cannot tell you it is idle ──────
      // The race audit classified this pair UNKNOWN rather than HANDLED, and the reason
      // is worth keeping at the site: nothing establishes that the guarded condition is
      // ever ENTERED. An assertion that is never evaluated under the circumstance it
      // guards is indistinguishable from one that holds, so `quiesceHold` could have
      // been dead for a release and every run would still be green.
      //
      // This counts the cycles a walker actually WANTED a port while the hold was
      // active -- the precondition the assertion exists to make safe. A directed run
      // (MMU on with real table walks, plus a CPUSH/CINV so the sequencer reaches
      // `S_DRAIN`/`S_APPLY`/`S_MAINTWAIT`) can then assert this is NON-ZERO, which is
      // what turns the UNKNOWN into a HANDLED.
      //
      // `walkLdReq`/`walkStReq` are existing nets already tapped into
      // `dbgStallGrantPack(20..23)`, so this adds no new signal; the counter is
      // simulation-only and pruned from every netlist.
    }

    // ── REACHABILITY COUNTER (see the tripwire block above) ──────────────────────
    // Declared at AREA level, not inside the `GenerationFlags.simulation` block, and
    // that is the whole point: a `val` inside that block is a local, so nothing outside
    // could ever read it and the counter would be write-only -- useless for the one job
    // it has, which is letting a directed run ASSERT the window was reached.
    // `GenerationFlags.simulation { ... }` RETURNS its value, so the register is still
    // elaborated only in simulation and pruned from every netlist; this is the idiom
    // `StoreQueue.fsNbytesA`/`fsPaddrHiA` already use for exactly this reason.
    val quiesceBlockedWalker = GenerationFlags.simulation {
      val c = Reg(UInt(16 bits)) init 0
      when(quiesceHold && (walkLdReq.orR || walkStReq.orR) && c =/= U(0xffff, 16 bits)) {
        c := c + 1
      }
      c.simPublic()
      c
    }

    // W23: `excLoadCmdReady` was UNCONDITIONAL. `ExceptionUnit` computes
    // `dcLoadCmd.fire` as `ldoValidReg && ready`, so an unqualified ready would retire
    // the sequencer's command on a cycle this mux never presented it -- the sequencer
    // would then wait forever in its WAIT state for a response that was never requested.
    excLoadCmdReady := dcache.loadCmd.ready && excLoadAdmit
  }
}
