package m68k040.rob

import m68k040.services.{RenameCommitService, CommitTraceService, RobAllocService, RedirectService, BtbUpdateService, BtbUpdate, GshareUpdateService, GshareUpdate, PrivilegeService, CacheControlService, FrontendQuiesceService, DebugCommitService, DebugSystemApply, DebugSystemStateService, DebugHistoryService, DebugBranchEvent, DebugExceptionEvent}
import m68k040.rename.RenamedUop
import m68k040.types.CommitTrace
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Commit-owned debug stop state (debug/control Stage 2, design spec section 6). */
object DebugHaltState extends SpinalEnum {
  val RUNNING, STOP_PENDING, RECOVER, HALTED, STEP_RUNNING = newElement()
}

/** JTAG-visible stop classification, distinct from socket.HaltReason's fatal
  * subsystem classification. */
object DebugHaltReasonCode {
  val NONE = 0
  val MANUAL = 1
  val STEP = 2
  val HALT_AFTER = 3
  val FATAL = 4
  val BREAKPOINT = 5
  val EXCEPTION = 6
  val A7_ODD = 7      // the A7-odd halt lane (OFF_A7ODD_CTL) requested the stop
  // NOTE: the 3-bit field is full. The PC-RANGE lane therefore reports A7_ODD too;
  // OFF_PCRANGE_COUNT / OFF_A7ODD_COUNT tell the two lanes apart at the host.
}

/** RobPlugin: instruction-level reorder buffer ring with 2-wide in-order retire.
  *
  * - Alloc: PASSIVE RobAllocService — DispatchPlugin takes robIds and drives the
  *   alloc-fire/uop/slot1 wires; the ROB writes the ring slots.
  * - Completion: two external completion.Flow(robId) ports mark entries done.
  * - Retire: in-order, up to 2/cycle, drives RenameCommitService.commitPorts
  *   (commit committed-RAT + free old pdsts) and exposes CommitTrace + commitObs.
  * - Flush: squash all in-flight entries (tail := head, count := 0).
  *
  * retireAlone entries (branches, for now) retire 1-wide.
  */
class RobPlugin extends FiberPlugin with CommitTraceService with RobAllocService with RedirectService with BtbUpdateService with GshareUpdateService with PrivilegeService with CacheControlService with FrontendQuiesceService with DebugCommitService with DebugSystemStateService with DebugHistoryService {

  // PrivilegeService: the wire is allocated in `setup` (BEFORE any plugin's `build`
  // runs) and driven inside `logic` (build) below, mirroring TranslationService's
  // _req/_rsp pattern. This is NOT cosmetic: `logic`'s very first statement
  // (`host[RenameCommitService]`) blocks on RenameStage, which blocks on Decode, which
  // blocks on FetchAlign, which blocks on IcachePlugin's OWN `cmd` output — so a
  // consumer (IcachePlugin) resolving `host[PrivilegeService].supervisor` as a member
  // of `logic` would deadlock the whole Fiber chain in a cycle (Icache -> Rob -> Rename
  // -> Decode -> FetchAlign -> Icache). Exposing a pre-allocated wire from `setup`
  // breaks the cycle: IcachePlugin gets a stable wire reference immediately and only
  // combinationally taps its (later-driven) value.
  private var _supervisor: Bool = null
  override def supervisor: Bool = _supervisor
  // CacheControlService: same setup-allocated-wire pattern as PrivilegeService above,
  // for the identical reason (breaks the DcachePlugin <- RobPlugin Fiber dependency
  // cycle). Mirrors ss.cacr(31) combinationally; INERT in P1 (no consumer yet).
  private var _dcacheEnabled: Bool = null
  override def dcacheEnabled: Bool = _dcacheEnabled
  // FrontendQuiesceService: setup-allocated for the same Fiber-cycle reason as
  // PrivilegeService. FetchAlign consumes `next` while ROB build itself depends on
  // rename -> decode -> FetchAlign, so exposing a build-local signal would deadlock
  // elaboration. ROB remains the sole halt-state owner.
  private var _frontendQuiesceActive: Bool = null
  private var _frontendQuiesceNext: Bool = null
  override def active: Bool = _frontendQuiesceActive
  override def next: Bool = _frontendQuiesceNext
  // DebugCommitService: same setup-allocated-wire pattern as PrivilegeService/
  // FrontendQuiesceService above, for the identical Fiber-cycle reason (breaks the
  // Icache -> Rob -> Rename -> Decode -> FetchAlign -> Icache dependency cycle).
  // Pure plumbing in this task (Stage 2 task 2): every field below is driven to an
  // inert default in `logic`; later tasks give them real producers.
  private var _debugEffectiveHalt:    Bool = null
  private var _debugAutoHaltLatched:  Bool = null
  private var _debugHaltReason:       UInt = null
  private var _debugLivePc:           UInt = null
  private var _debugLastPc:           UInt = null
  private var _debugMacroCount:       UInt = null
  private var _debugHaltHitInstCount: UInt = null
  private var _debugHaltAfterConsumed: Bool = null
  private var _debugHaltHitPc:         UInt = null
  private var _debugBreakpointHit:     Flow[UInt] = null
  private var _debugExceptionPending:  Bool = null
  private var _debugHaltExceptionVector: UInt = null
  private var _debugHaltExceptionPc: UInt = null
  private var _debugHaltExceptionFaultAddress: UInt = null
  private var _debugDblFaultPc:  UInt = null
  private var _debugDblFaultVec: UInt = null
  override def effectiveHalt:    Bool = _debugEffectiveHalt
  override def autoHaltLatched:  Bool = _debugAutoHaltLatched
  override def haltReasonDebug:  UInt = _debugHaltReason
  override def livePc:           UInt = _debugLivePc
  override def lastPc:           UInt = _debugLastPc
  override def macroCount:       UInt = _debugMacroCount
  override def haltHitInstCount: UInt = _debugHaltHitInstCount
  override def haltAfterConsumed: Bool = _debugHaltAfterConsumed
  override def haltHitPc: UInt = _debugHaltHitPc
  override def breakpointHit: Flow[UInt] = _debugBreakpointHit
  override def exceptionPending: Bool = _debugExceptionPending
  override def haltExceptionVector: UInt = _debugHaltExceptionVector
  override def haltExceptionPc: UInt = _debugHaltExceptionPc
  override def haltExceptionFaultAddress: UInt = _debugHaltExceptionFaultAddress
  override def dblFaultPc:  UInt = _debugDblFaultPc
  override def dblFaultVec: UInt = _debugDblFaultVec
  during setup {
    _supervisor             = Bool()
    _dcacheEnabled          = Bool()
    _frontendQuiesceActive  = Bool()
    _frontendQuiesceNext    = Bool()
    _debugEffectiveHalt     = Bool()
    _debugAutoHaltLatched   = Bool()
    _debugHaltReason        = UInt(3 bits)
    _debugLivePc            = UInt(32 bits)
    _debugLastPc            = UInt(32 bits)
    _debugMacroCount        = UInt(64 bits)
    _debugHaltHitInstCount  = UInt(64 bits)
    _debugHaltAfterConsumed = Bool()
    _debugHaltHitPc         = UInt(32 bits)
    _debugBreakpointHit     = Flow(UInt(2 bits))
    _debugExceptionPending  = Bool()
    _debugHaltExceptionVector = UInt(8 bits)
    _debugHaltExceptionPc = UInt(32 bits)
    _debugHaltExceptionFaultAddress = UInt(32 bits)
    _debugDblFaultPc  = UInt(32 bits)
    _debugDblFaultVec = UInt(8 bits)
  }

  /** One ROB entry's commit/free + trace payload. */
  case class RobPayload() extends Bundle {
    val predNextPc = UInt(32 bits)
    val archRegId  = UInt(5 bits)
    val intNew     = UInt(6 bits); val intOld = UInt(6 bits); val intWrite = Bool()
    val nzvcNew    = UInt(4 bits); val nzvcOld = UInt(4 bits); val nzvcWrite = Bool()
    val xNew       = UInt(4 bits); val xOld = UInt(4 bits); val xWrite = Bool()
    // FP data + FPCC commit/free info -- the EXACT analog of the int (archRegId/intNew/
    // intOld/intWrite) and NZVC/X (nzvcNew/nzvcOld/nzvcWrite) groups above, and required
    // for the same reason: `CommitSlot` carries these to RenameStage's fpRat commit port
    // and fpFree push port, and the ROB is the only thing that knows the rename identity
    // at RETIRE time. They were missing until 2026-08-16 (Task 2 added the CommitSlot
    // fields but the ROB drove them to inert False/0 defaults "no producer yet"), which
    // meant an FP writer's new phys tag NEVER entered the committed FP RAT and its old
    // phys tag NEVER returned to fpFree -- so fpRat.io.rollback (asserted on every branch
    // mispredict / exception) silently reverted all architectural FP state to the reset
    // identity mapping, and the 8-deep fpFree pool drained permanently.
    // fpArchDst mirrors archRegId (the fpRat commit ADDRESS); FPCC needs no address field
    // (archDepth=1, address hardwired to 0 -- same as nzvc/x).
    val fpArchDst  = UInt(3 bits)
    val fpNew      = UInt(4 bits); val fpOld = UInt(4 bits); val fpWrite = Bool()
    val fpccNew    = UInt(4 bits); val fpccOld = UInt(4 bits); val fpccWrite = Bool()
    val retireAlone = Bool()
    // ── LUT-reduction B1: alloc-only per-entry state folded in ─────────────────
    // These 8 fields used to be standalone `Vec.fill(depth)(RegInit(...))` arrays
    // (isRteStore/firstStore/needsSupStore/sysOpStore/sysKindStore/sysReadDirStore/
    // sysRcStore/pcStore). They are written ONLY at alloc (tail / tail+1) — exactly
    // like the fields above — so they belong in the same Mem, which
    // MultiPortWritesSymplifier lowers to distributed-RAM banks instead of 64 FFs +
    // a 64:1 read mux per bit.
    //
    // SAFETY (why losing the `RegInit` default is fine): `payload` has no `init`, so a
    // slot that has NEVER been written since power-on holds undefined content. Every
    // read of these fields is at h0 or h1 and is conjunctively gated by either
    // `headReady` (= count>0 && completes(h0) && ...) or a bare `count > 0`
    // (normalIrqGate / traceNormalGate) — and `count > 0` ALREADY proves entry h0 was
    // written, because `count` is incremented by the very same `when(alloc0/alloc1)`
    // blocks that perform `payload.write(tail/tail+1, ...)`, and the ROB's valid
    // window is exactly [head, head+count). The remaining reads (interruptPc,
    // exceptionPc, ExceptionUnit's entryPpc/rtePc/sysKind/sysReadDir/sysRc/sysPc) are
    // combinational computations whose CONSUMERS are gated by entryTrigger /
    // rteTrigger / sysTrigger / interruptPending, each of which is itself derived from
    // headReady or count>0. So this is the identical discipline that already protects
    // predNextPc/archRegId/retireAlone above — not a new hazard.
    val pc         = UInt(32 bits)
    val isRte      = Bool()
    val first      = Bool()
    // Macro-boundary LAST marker (Stage 2 task 4, spec section 6.2's `macroLast`), the
    // exact mirror of `first`: True iff this entry is the LAST µop of its macro. Captured
    // at ALLOC from `RenamedUop.lastOfInstr` (decode-time known), NOT derived here from
    // ring occupancy -- a 3+-µop crack can reach `count == 1` with a MIDDLE µop at the
    // head before its real last µop is even allocated (MicroOpQueue pops <= 2 µops/cycle
    // and DispatchPlugin gates 2-wide dispatch on ROB/IQ backpressure), so any
    // occupancy-based derivation reads "macro complete" mid-macro. Read only under the
    // same headReady / count>0 gating as `first` (see the SAFETY note above).
    val last       = Bool()
    val debugBreakValid = Bool()
    val debugBreakSlot  = UInt(2 bits)
    val needsSup   = Bool()
    val sysOp      = Bool()
    val sysKind    = m68k040.decode.SysKind()
    val sysReadDir = Bool()
    val sysRc      = UInt(12 bits)
    // ── LUT-reduction ROB-fold Slice A (2026-08-08 area spec §4) ───────────────
    // Replaces the deleted 64x32 `faultPcStore` Reg-Vec: that array only ever held
    // Mux(faultUsesNextPc, nextPc, pc) captured at alloc — BOTH operands of which
    // are ALREADY stored here (`predNextPc` == u.nextPc, `pc` == u.pc). Storing
    // the 1-bit selector instead and re-deriving the mux at the single read site
    // (`exceptionPc`) is bit-identical: same sources, same alloc write, same
    // headReady/count>0 read gating as every other B1 field (see SAFETY above).
    val faultUsesNextPc = Bool()
    // ── LUT-reduction ROB-fold Slice C (fault-completion 4->1 arbitration) ──────
    // The ALLOC-TIME half of the per-entry fault record. Slice C splits the old
    // `fault*Store` Reg-Vec family into two disjoint halves by WRITER:
    //   * alloc-time (a decode/I-fetch-sourced static fault)  -> HERE, in `payload`,
    //     which is already written by exactly these two alloc ports;
    //   * completion-time (ls/sq/eu/fp)                        -> `faultDynMem`, a
    //     single-write-port async-read Mem fed by an age-arbitrated write bus.
    // `faultDynStore` (an alloc-reset 1-bit Reg-Vec gate, the same discipline
    // `btbIsBranchStore`/`phtValidStore` already use for `branchTrainMem`) selects
    // which half a read at h0 sees.
    //
    // Only THREE alloc-time fault fields need storing:
    //   * the fault ADDRESS is `pc` (Slice A proved `RenamedUop.faultAddr` was
    //     bit-identical to `.pc` at every decode write site) -- already stored above;
    //   * `faultWr`/`faultSup` were written to a CONSTANT False by both alloc ports --
    //     re-derived as a constant at the read site, no storage;
    //   * `faultSize` had NO alloc write at all (the task #257-era bug Slice D tracked)
    //     -- now a constant U(2) ("LONG") at the read site, which is exactly the
    //     RegInit the old Vec declared and the pre-task-189 SSW SIZE=00 behaviour.
    val faultVector = UInt(8 bits)
    val sswInstr    = Bool()
    val faultAtc    = Bool()
    // ── LUT-reduction ROB-fold (task #249) ──────────────────────────────────────
    // Replaces the deleted `fpuUnimpStore`/`fpuCmdStore` (Vec.fill(depth)(...)):
    // Task 11's per-entry FP unimplemented-instruction marker + its command word,
    // written ONLY at alloc (tail/tail+1, same 2-write-port shape every other B1
    // field above already uses -- tail != tail+1 always holds at this depth, so the
    // 2-port collision case MultiPortWritesSymplifier's XOR-LVT lowering must avoid
    // never arises) and read ONLY at the single retire-time site (vector-11
    // delivery, `entryFpuUnimp`/`entryFpuCmd` below), gated by the same
    // headReady/count>0 discipline as every other B1 field (see SAFETY above).
    val fpuUnimp = Bool()
    val fpuCmd   = Bits(16 bits)
  }

  /** COMPLETION-TIME per-entry fault record (ROB-fold Slice C).
    *
    * The union of every field the four fault-completion ports (ls / sq / eu / fp)
    * used to write into their own `Vec.fill(depth)(Reg)` arrays. Stored in ONE
    * single-write-port async-read `Mem` fed by the age-arbitrated write bus below
    * -- the same 1W/1R shape as `nextPcMem`/`branchTrainMem`, which
    * `MultiPortWritesSymplifier` leaves alone entirely (`writes.size <= 1` is its
    * early return), so there is no LVT/XOR bank machinery and no same-cycle
    * multi-writer collision to arbitrate inside the RAM.
    */
  case class RobFaultDyn() extends Bundle {
    val addr = UInt(32 bits)   // was faultAddrStore
    val vec  = UInt(8 bits)    // was faultVecStore
    val size = UInt(2 bits)    // was faultSizeStore  (LsFault.sizeBits encoding)
    val wr   = Bool()          // was faultWrStore
    val sup  = Bool()          // was faultSupStore
    val atc  = Bool()          // was faultAtcStore
    // (faultInstrStore is NOT here: all four completion ports wrote a CONSTANT
    //  False into it, so the completion-side value is re-derived as a constant at
    //  the read site. Only the alloc side carries a real value -- payload.sswInstr.)
  }

  /** BTB/gshare retire-time training payload (task #129, area). Written ONLY by
    * branchCompletion (a single writer — never at alloc, see below), so this is a
    * plain single-write-port sync-read Mem: safe to map to BRAM (no multi-writer
    * collision arbitration needed, unlike `payload` which has 2 alloc write ports).
    */
  case class BranchTrainPayload() extends Bundle {
    val pc       = UInt(32 bits)
    val taken    = Bool()
    val target   = UInt(32 bits)
    val brType   = UInt(2 bits)
    val len      = UInt(4 bits)
    val phtIndex = UInt(11 bits)
  }

  val logic = during build new Area {
    val rc = host[RenameCommitService]
    // External interrupt inputs (simple protocol). The recognition logic (Task 3)
    // compares iplIn vs the SR I-mask and selects the vector; here (Task 2) we
    // mirror them simPublic so a directed test sees the inputs reach the ROB. The
    // service owner (InterruptControlPlugin) defaults idle (ipl=0) so existing
    // tests are unchanged. A standalone ROB DUT with no InterruptControlPlugin
    // gets idle defaults via the fallback below.
    val intCtrl = host.get[m68k040.services.InterruptControlService]
    val iplIn      = UInt(3 bits); iplIn.simPublic()
    val iackAvec   = Bool();       iackAvec.simPublic()
    val iackVector = UInt(8 bits); iackVector.simPublic()
    intCtrl match {
      case Some(c) => iplIn := c.iplIn; iackAvec := c.iackAvec; iackVector := c.iackVector
      case None    => iplIn := 0;       iackAvec := False;      iackVector := 0
    }

    val depth  = 64
    val robIdW = log2Up(depth) // = 6, wraps naturally

    // ── Ring storage ────────────────────────────────────────────────────────
    val payload   = Mem(RobPayload(), depth)
    val completes = Vec.fill(depth)(RegInit(False))
    completes.foreach(_.simPublic()) // debug-only, task #139 finding #1; zero synth impact
    val head  = Reg(UInt(robIdW bits)) init 0
    val tail  = Reg(UInt(robIdW bits)) init 0
    val count = Reg(UInt(log2Up(depth + 1) bits)) init 0
    head.simPublic(); tail.simPublic(); count.simPublic()

    // ── External ports ────────────────────────────────────────────────────────
    // Plain directionless service wires: a sibling plugin (the EU wiring) DRIVES
    // completion(k).valid/payload; this ROB consumes them. Standalone tests poke
    // them in sim (simPublic). Mirrors the RenameCommitService.commitPorts wiring
    // convention (sibling-driven, directionless).
    // 6 completion ports: ALU0, ALU1, LS EU, CPLX EU (DivEu int/NZVC lane), SQ precise-path
    // drain, CPLX EU FP lane (sibling-driven).
    // Port 5 is a SEPARATE port rather than an arbitration onto port 3 because the CPLX EU's
    // int/NZVC lane and its 80-bit FP writeback lane are structurally independent pipelines
    // sharing only the issue port: a DIV/MUL/CHK result and an FP result can complete in the
    // same cycle, and a completion Flow has no backpressure with which to recover a dropped
    // one. Same reasoning that already gives the SQ drain its own port 4.
    val completion = Vec.fill(6)(Flow(UInt(robIdW bits)))
    // Default-drive (idle) so the ROB elaborates standalone; a sibling EU-wiring
    // plugin OVERRIDES these via allowOverride, and standalone tests poke them in
    // sim (simPublic).
    completion.foreach { c =>
      c.valid.allowOverride;   c.valid   := False
      c.payload.allowOverride; c.payload := U(0, robIdW bits)
      c.simPublic()
    }
    val flush      = slave(Flow(NoData()))

    // Branch completion: the branch EU marks a robId complete and records its
    // {mispredict, nextPc} for commit-time recovery. Directionless service wire,
    // same convention as `completion`: default-driven idle (allowOverride) so the
    // ROB elaborates standalone; a sibling branch EU OVERRIDES it, and standalone
    // tests poke it in sim (simPublic).
    val branchCompletion = Flow(m68k040.execute.BranchCompletion())
    branchCompletion.valid.allowOverride; branchCompletion.valid := False
    // Concrete (not assignDontCare) idle defaults — mirrors `completion`'s `:= U(0)`.
    // assignDontCare drives the payload to don't-care; a sim poke updates the public
    // mirror but the CONSUMER reads the don't-care net, so `completes(robId)` indexes
    // garbage instead of the poked robId. Concrete zero defaults make the poke visible.
    branchCompletion.payload.robId.allowOverride;      branchCompletion.payload.robId := U(0, robIdW bits)
    branchCompletion.payload.mispredict.allowOverride; branchCompletion.payload.mispredict := False
    branchCompletion.payload.nextPc.allowOverride;     branchCompletion.payload.nextPc := U(0, 32 bits)
    branchCompletion.payload.isBranch.allowOverride;   branchCompletion.payload.isBranch := False
    branchCompletion.payload.btbPc.allowOverride;      branchCompletion.payload.btbPc := U(0, 32 bits)
    branchCompletion.payload.btbTaken.allowOverride;   branchCompletion.payload.btbTaken := False
    branchCompletion.payload.btbTarget.allowOverride;  branchCompletion.payload.btbTarget := U(0, 32 bits)
    branchCompletion.payload.brType.allowOverride;     branchCompletion.payload.brType := U(0, 2 bits)
    branchCompletion.payload.btbLen.allowOverride;     branchCompletion.payload.btbLen := U(0, 4 bits)
    branchCompletion.payload.phtValid.allowOverride;   branchCompletion.payload.phtValid := False
    branchCompletion.payload.phtIndex.allowOverride;   branchCompletion.payload.phtIndex := U(0, 11 bits)
    branchCompletion.simPublic()

    // ── Retire-time BTB update output (BtbUpdateService) ────────────────────────
    // Pulses the cycle a BTB-eligible branch retires (driven below near branchRedirect,
    // where retire0/h0 are in scope). Concrete idle defaults so a standalone DUT (no
    // BtbPlugin consumer) elaborates; the BtbPlugin reads it in the full core.
    // btbUpdateValidComb is the raw combinational decision; the EXPOSED btbUpdateFlow's
    // .valid is a REGISTERED copy of it (task #124, Fable audit F6) — training is
    // latency-insensitive (only affects FUTURE fetches, long after this retire), so the
    // extra cycle is free and removes this write from the retire-cone fanout. Registering
    // the EXPOSED name (not a separate wrapper) means every existing consumer — the
    // BtbUpdateService getter AND the handful of test DUTs that wire
    // `rob.logic.btbUpdateFlow` directly — picks up the fix with no further changes.
    // The PAYLOAD is driven directly from branchTrainMem's readSync output below (task
    // #129, area) — that Mem read is ITSELF the register (readSync has 1-cycle latency,
    // matching what the old RegNext(btbUpdateComb.payload) provided), so there is no
    // separate comb payload stage anymore.
    val btbUpdateValidComb = Bool(); btbUpdateValidComb := False
    val btbUpdateFlow = Flow(BtbUpdate())
    btbUpdateFlow.valid := RegNext(btbUpdateValidComb) init False
    btbUpdateFlow.simPublic()
    // ── Retire-time gshare PHT update output (GshareUpdateService, slice 3) ──────
    // Pulses the cycle a CONDITIONAL gshare-predicted branch retires (driven below near
    // branchRedirect). Concrete idle defaults so a standalone DUT (no GsharePlugin
    // consumer) elaborates; the GsharePlugin reads it in the full core. 11-bit index.
    // Same registered-output treatment as btbUpdateFlow above (task #124); payload same
    // Mem-readSync treatment as btbUpdateFlow above (task #129).
    val gshareUpdateValidComb = Bool(); gshareUpdateValidComb := False
    val gshareUpdateFlow = Flow(GshareUpdate(11))
    gshareUpdateFlow.valid := RegNext(gshareUpdateValidComb) init False
    gshareUpdateFlow.simPublic()
    // mispredictStore MUST default False: a freshly-allocated branch entry is "not
    // yet known mispredicted" until its EU completion (branchCompletion) says so.
    // Without this, `doFlushReg := ... && mispredictStore(h0)` reads a stale/uninit
    // bit and can spuriously flush (a branch that completes via the normal port).
    // Reset per-alloc below (mirrors `completes`), with alloc-priority on a reused index.
    val mispredictStore = Vec.fill(depth)(RegInit(False))
    mispredictStore.foreach(_.simPublic()) // debug-only observability, task #139 investigation; zero synth impact
    // ── LUT-reduction ROB-fold Slice B (2026-08-08 area spec §5) ────────────────
    // WAS `Vec.fill(depth)(Reg(UInt(32 bits)))` (2048 FF + a 64:1 read mux per bit at
    // TWO read addresses). Exactly ONE writer (branchCompletion, below) — the same
    // single-writer shape as branchTrainMem — so this folds to a plain 1W async-read
    // Mem with no MultiPortWritesSymplifier XOR-LVT involvement (no same-cycle
    // same-address collision hazard by construction). Staleness safety is unchanged
    // and never depended on the storage kind: the old Reg-Vec was equally
    // uninitialised (no RegInit, no alloc reset); every read is gated by
    // p0/p1.retireAlone (alloc-written payload) or mispredictStore(h0) (alloc-reset
    // Reg-Vec), and both gates only go True via the SAME branchCompletion that
    // freshens this row — completion strictly precedes retire, so the earliest read
    // of a row is the cycle after its write (readAsync returns the OLD word during a
    // same-cycle write, identical to a Reg's Q, and that case is unreachable anyway).
    // `ram_style`=distributed (NOT block): the reads must be asynchronous, which BRAM
    // cannot serve — mirrors DcachePlugin's explicit-attribute idiom.
    val nextPcMem       = Mem(UInt(32 bits), depth)
    nextPcMem.addAttribute("ram_style", "distributed")
    // Task #193 (trace exception T0): the branch EU's RESOLVED taken/redirect decision
    // (BranchCompletion.btbTaken == `actualTaken`, driven UNCONDITIONALLY for every
    // completing branch-family µop regardless of BTB eligibility — see BranchEuPlugin's
    // `redirect`/`actualTaken`). This is EXACTLY 68k trace-T0's "taken change of flow"
    // condition (ibranch always True; Bcc/BRA iff taken; DBcc iff looping) — reused
    // as-is rather than re-deriving it. RegInit(False), reset per-alloc like
    // mispredictStore (a never-completed/re-used index reads "not taken").
    val branchTakenStore = Vec.fill(depth)(RegInit(False))
    // ── BTB-update per-entry capture (fetch-time predictor, slice 1) ────────────
    // Recorded from branchCompletion (the branch EU) and read at retire to drive the
    // BTB write port (the BtbUpdate service below). btbIsBranchStore RegInit(False)
    // so a never-allocated / re-used-but-not-yet-completed entry never spuriously
    // updates the BTB (mirrors mispredictStore's alloc-priority discipline).
    val btbIsBranchStore = Vec.fill(depth)(RegInit(False))
    // ── gshare PHT-update per-entry capture (slice 3) ───────────────────────────
    // phtValidStore : this retiring branch is a CONDITIONAL gshare-predicted one whose
    // carried fetch-time index (in branchTrainMem) must be trained at retire. RegInit(False)
    // so a never-allocated / re-used-but-not-yet-completed entry never spuriously updates
    // the PHT (mirrors btbIsBranchStore's alloc-priority discipline). The resolved
    // direction reuses branchTrainMem's `taken` field (== actualTaken).
    val phtValidStore    = Vec.fill(depth)(RegInit(False))
    // pc/taken/target/brType/phtIndex: task #129 (area) — folded into ONE sync-read
    // Mem (branchTrainMem, below) instead of 5 separate Reg-Vec arrays. NO alloc-time
    // reset write: btbIsBranchStore/phtValidStore (above, unchanged Reg-Vecs) are the
    // alloc-reset GATES that make a stale/never-written Mem row unreachable — a retiring
    // entry only ever reads branchTrainMem(h0) when one of those gates is True, which is
    // only ever set True by the SAME branchCompletion write that freshens this row, and
    // completion always strictly precedes retire in time. This keeps branchTrainMem to a
    // SINGLE write port (branchCompletion only) — safe for BRAM (no same-cycle multi-
    // writer collision arbitration needed, unlike `payload`'s 2 alloc write ports; see
    // [[fpga-synth-multiwrite-mem]] for why a naive multi-write sync-read Mem is unsafe).
    val branchTrainMem   = Mem(BranchTrainPayload(), depth)
    // Precise-fault per-entry capture — RegInit Vecs reset per-alloc (mirrors
    // mispredictStore). RegInit(False) guarantees a never-allocated / re-allocated
    // entry reads "not faulted / not RTE" deterministically (no uninit-Mem flake).
    val faultedStore  = Vec.fill(depth)(RegInit(False))
    // Whitebox observability for the ROB-fold Slice C arbitration tests (a directed
    // test has to be able to see that a DROPPED younger fault really left no mark at
    // its own robId, which no derived signal exposes). Debug-only, same convention
    // and zero synthesis impact as `completes.foreach(_.simPublic())` above.
    faultedStore.foreach(_.simPublic())
    // (`isRteStore` folded into `payload.isRte` — LUT-reduction B1.)
    // (`faultVecStore` split by ROB-fold Slice C: the alloc-time vector is
    //  `payload.faultVector`, the completion-time vector is `faultDynMem`'s `vec`,
    //  selected by the `faultDynStore` gate — see the Slice C note below.)
    // Commit-time PRIVILEGED SYSTEM ops (MOVE-to-SR / MOVE-USP / MOVEC): captured at
    // alloc. `payload.sysOp` = this entry is a serializing system op;
    // `payload.sysKind` selects which; `payload.sysReadDir` = read SYSTEM->Rn vs write
    // Rn->SYSTEM; `payload.sysRc` = the 12-bit MOVEC control-reg id (from imm) — all
    // four folded into the `payload` Mem (LUT-reduction B1). The Rn for a read
    // direction is `payload.archRegId` (== the µop's dstArch), which the sysRead commit
    // below already uses; the old `sysDstArchStore` Vec held that same dstArch but was
    // NEVER read anywhere, so it was deleted outright rather than folded.
    // `sysValStore` = the captured source VALUE (wbObs.result) for a write direction —
    // written at COMPLETION (ccrCompletion), not at alloc, so it stays a Reg Vec.
    //
    // ── DO NOT try to fold this into `intPrf[payload.intNew]` (ROB restructure Slice E) ──
    // A post-Slice-C recon proposed deleting this array (64x32 = 2048 FF, and the
    // measured post-route #1 limiter `sysValStore_38_reg[18]/D`) on the theory that its
    // contents are pure duplication: the completing EU that drove `ccrCompletion.result`
    // also wrote that same value into the integer PRF at `payload.intNew`, so the ROB copy
    // could be replaced by a PRF read. That is FALSE, and failing in the direction of
    // SILENT CORRUPTION — the physical-register-lifetime hazard class of tasks
    // #176/#194/#200. Two independent reasons, both verified against the RTL:
    //
    // (1) A write-direction sysOp HAS NO PHYSICAL DESTINATION AT ALL. Every one of them —
    //     MOVE-to-SR, ANDI/ORI/EORI-to-SR, STOP, MOVEC Rn->Rc, MOVE An->USP, CPUSH, CINV,
    //     PTEST, FMOVE Rn->FPcr — is assembled with `opUop.dstValid := False` (see
    //     MicroOpAssembler's sysOp block; the destination is a SYSTEM register, not a
    //     renamed GPR). So `payload.intWrite` is False, no freelist pop happens, and NO
    //     PRF WRITE EVER OCCURS. The value exists ONLY here. These are exactly the entries
    //     the `sysVal` read site below (-> ExceptionUnit `sysCapVal`) serves.
    //
    // (2) Worse, `payload.intNew` for such an entry is not merely stale — it ALIASES A
    //     LIVE YOUNGER REGISTER. RenameStage gates only the freelist *take*
    //     (`intFree.io.pop(s).take := slotEn && dec.dstValid`) while driving
    //     `r.pdst := intFree.io.pop(s).id` UNCONDITIONALLY, and Freelist drives
    //     `io.pop(k).id := ram.readAsync(head + ...)` regardless of `take` ("Non-asserted
    //     ports' id outputs are don't-care"). So a non-allocating uop's `pdst` carries the
    //     CURRENT FREELIST HEAD = the physical register handed to the very NEXT allocating
    //     instruction. Reading `intPrf[payload.intNew]` here would return a strictly
    //     YOUNGER instruction's data. Pinned as an executable fact by
    //     RenameStageSpec's "pdst is don't-care when pdstValid=False" tripwire.
    //
    // Note `CcrCompletion.intWrite` is wired by every top level but read NOWHERE in this
    // file — do not read it as evidence that a PRF write accompanied the capture.
    //
    // Separately, even where a PRF value DOES exist (the FPCTRL_CAP loads feeding the
    // `sysAux` read site DO carry a real renamed temp dst), converting only that site
    // frees nothing — the array must stay for (1) — while ADDING a PRF read port whose
    // index comes from `payload.readAsync(h0)`, i.e. serialising a Mem read into a PRF
    // read inside the retire path. Strictly negative.
    val sysValStore     = Vec.fill(depth)(Reg(Bits(32 bits)))
    // The EU's `completion` port (marks `completes`) fires ONE cycle BEFORE its `wbObs`
    // (the value, captured into sysValStore via ccrCompletion). So a write-direction
    // sysOp head could `sysRetire` (gated on completes) before its VALUE lands -> the FSM
    // would latch a STALE sysValStore. `sysValRdyStore` is set BY the ccrCompletion (same
    // cycle the value lands); sysRetire gates on it so the write triggers only AFTER the
    // value is captured. RegInit(False), reset per-alloc (mirrors faultedStore).
    val sysValRdyStore  = Vec.fill(depth)(RegInit(False))
    // ── Task 9b: FMOVEM control-register LIST form — commit-time value capture ──────
    // Three 32-bit side registers holding the load direction's transfer values, one per
    // POSITION in the register list. `ExceptionUnit`'s single-value `sysVal` side channel
    // cannot carry a 2- or 3-register batch, and the sysOp that applies them must be the
    // program's LAST µop (a sysOp at the ROB head squashes everything younger), so the
    // values have to be delivered by the µops that ALREADY ran — the loads themselves.
    //
    // A microcode `MLoad` row tagged `Microcode.Desc(fpCtrlCap = true)` carries
    // `SysKind.FPCTRL_CAP` with `sysOp = False` (so it is an entirely ordinary,
    // non-serializing load in every other respect, and invisible to `sysRetire`/
    // `sysTriggerSig`/`sysPrivFault`, all of which gate on `p.sysOp`). At its IN-ORDER
    // RETIREMENT its own already-captured `sysValStore` entry is copied here, into the
    // slot named by its destination temp (T0/T1/T2 = arch 16/17/18 -> slot 0/1/2).
    //
    // Why in-order retirement and not completion: completion is out-of-order, so a
    // YOUNGER FMOVEM program's load could complete before an OLDER one's terminal sysOp
    // reached the head and clobber a slot it was about to read. Retirement cannot — the
    // terminal sysOp is the youngest µop of its own program, and every µop that retires
    // before it is older. Nothing younger can have written these slots.
    //
    // A flush needs no handling: a squashed program's µops never retire, so they never
    // write a slot, and a re-executed program re-writes every slot it reads before its own
    // terminal sysOp can trigger.
    // `init 0` is not needed for correctness (every slot the terminal sysOp reads was
    // written by its own program's loads first — it only ever indexes positions below the
    // mask's popcount, and exactly that many loads preceded it), but an uninitialised Reg
    // randomises per simulation seed in this project's harnesses, which has historically
    // produced seed-dependent lock-step flakiness. Determinism is free here.
    val sysAux = Vec.fill(3)(RegInit(B(0, 32 bits))); sysAux.foreach(_.simPublic())
    // (faultPcStore — a 64x32 Reg-Vec holding Mux(faultUsesNextPc, nextPc, pc) captured
    //  at alloc — deleted by ROB-fold Slice A: it was a redundant mux of two values the
    //  `payload` Mem already stores; the 1-bit selector now rides in payload.faultUsesNextPc
    //  and the mux is re-derived at the single read site, `exceptionPc` below.)
    // needsSupervisor per-entry (set at alloc from the µop): a PRIVILEGED op (MOVE-from-
    // SR). When such a head retires while the committed S bit is 0, the ROB converts it
    // to a faulted vector-8 (privilege violation, format-$0) entry — precise, like a
    // statically faulted head, but conditional on the runtime committed S. Lives in
    // `payload.needsSup` (LUT-reduction B1).
    // ── STOP (0x4E72) halt state ────────────────────────────────────────────────
    // Set when a SysKind.STOP sysOp serializes its retire in SUPERVISOR (S=1; the S=0
    // case is a vector-8 fault, not a halt). While `stopped` the core quiesces: no
    // instruction retires (the ROB is empty post-redirect anyway) AND fetch is held
    // (the `quiesce` output gates the front-end). An interrupt recognized at the right
    // level CLEARS it (the IRQ entry resumes execution at the handler).
    val stopped = RegInit(False); stopped.simPublic()
    // The PC to RESUME at when an IRQ wakes the halted core (= STOP's nextPc, latched at the
    // STOP retire). While stopped the ROB is empty (count==0), so p0.pc is stale — the
    // interrupt entry must stack THIS PC as the return address (else RTE resumes at garbage).
    val stoppedPc = Reg(UInt(32 bits)) init 0; stoppedPc.simPublic()
    // ── CORE HALT (Task P4.5) ───────────────────────────────────────────────
    // A sticky variant of the STOP quiesce shape (design doc Sec 4.2, USER
    // DECISION 2026-07-23) -- gates retire + fetch -- but, unlike `stopped`,
    // NEVER cleared by an interrupt (only debug/reset recovers it).
    // Sibling-driven from the D-cache's diagFault (a configuration-error
    // report, not a case precision protects against or an interrupt can
    // meaningfully service).
    val coreHaltedIn = Bool(); coreHaltedIn.allowOverride; coreHaltedIn := False
    coreHaltedIn.simPublic()   // pokable from a standalone DUT, same convention as preciseDrainBusyIn
    // Registered commands are driven by BackendWiringPlugin in Task 6. Idle defaults
    // keep a standalone/no-debug RobPlugin behaviorally identical to Stage 1.
    val debugStopRequestIn = Bool(); debugStopRequestIn.allowOverride; debugStopRequestIn := False
    debugStopRequestIn.simPublic()
    val debugResumeRequestIn = Bool(); debugResumeRequestIn.allowOverride; debugResumeRequestIn := False
    debugResumeRequestIn.simPublic()
    val debugStepRequestIn = Bool(); debugStepRequestIn.allowOverride; debugStepRequestIn := False
    debugStepRequestIn.simPublic()
    val debugClearStickyIn = Bool(); debugClearStickyIn.allowOverride; debugClearStickyIn := False
    debugClearStickyIn.simPublic()
    // DebugSystemStateService command. The DebugCtrl apply FSM emits one pulse after
    // all shared-PRF writes have completed. A standalone/no-Stage-3 ROB sees an inert
    // command, preserving the existing exception/system-state behavior.
    val debugSystemApplyIn = Flow(DebugSystemApply())
    debugSystemApplyIn.valid.allowOverride; debugSystemApplyIn.valid := False
    debugSystemApplyIn.payload.allowOverride; debugSystemApplyIn.payload.assignDontCare()
    debugSystemApplyIn.simPublic()
    // DebugCommitService-owned halt-after configuration. These are ROB-local wires,
    // not sibling-plugin IO; idle defaults preserve standalone elaboration.
    val haltAfterTargetIn = UInt(64 bits); haltAfterTargetIn.allowOverride
    haltAfterTargetIn := U(0, 64 bits); haltAfterTargetIn.simPublic()
    val haltAfterEpochIn = UInt(8 bits); haltAfterEpochIn.allowOverride
    haltAfterEpochIn := U(0, 8 bits); haltAfterEpochIn.simPublic()
    val haltAfterArmedIn = Bool(); haltAfterArmedIn.allowOverride
    haltAfterArmedIn := False; haltAfterArmedIn.simPublic()
    val haltAfterInvalidateIn = Bool(); haltAfterInvalidateIn.allowOverride
    haltAfterInvalidateIn := False; haltAfterInvalidateIn.simPublic()
    val haltExceptionMaskIn = Bits(256 bits); haltExceptionMaskIn.allowOverride
    haltExceptionMaskIn := 0; haltExceptionMaskIn.simPublic()
    // A7-ODD halt lane configuration (DebugCommitService.configureA7OddHalt) and its
    // sticky stop request, which rides the MANUAL-stop path so the halt lands on a
    // clean macro boundary exactly like a host-requested stop. Driven by `a7OddLane`.
    val haltA7OddEnIn = Bool(); haltA7OddEnIn.allowOverride
    haltA7OddEnIn := False; haltA7OddEnIn.simPublic()
    val haltA7OddThreshIn = UInt(16 bits); haltA7OddThreshIn.allowOverride
    haltA7OddThreshIn := U(0, 16 bits); haltA7OddThreshIn.simPublic()
    val a7OddStopReq = Bool(); a7OddStopReq.simPublic()
    // PC-RANGE halt lane configuration and its sticky stop request.
    val pcRangeStopReq = Bool(); pcRangeStopReq.simPublic()
    val haltPcRangeEnIn = Bool(); haltPcRangeEnIn.allowOverride
    haltPcRangeEnIn := False; haltPcRangeEnIn.simPublic()
    val haltPcRangeLoIn = UInt(32 bits); haltPcRangeLoIn.allowOverride
    haltPcRangeLoIn := U(0, 32 bits); haltPcRangeLoIn.simPublic()
    val haltPcRangeHiIn = UInt(32 bits); haltPcRangeHiIn.allowOverride
    haltPcRangeHiIn := U(0, 32 bits); haltPcRangeHiIn.simPublic()
    // D28 (axi-socket adapter spec section 6.4): the halt seam carries a KIND alongside the
    // Bool. "Halts" is only half a diagnostic; an operator staring at a wedged core has to
    // know why. Deliberately an OBSERVATION, not a control path -- `coreHalted`'s three
    // consumers (`headReady` below, `interruptPending`, and the frontend-quiesce service)
    // are untouched, so this cannot perturb the halt semantics any existing test depends on.
    //
    // FIRST-WINS, not last: the first cause is the one that explains the machine. A later
    // producer firing against an already-halted core is a CONSEQUENCE, not a second bug, and
    // overwriting would hide the real one.
    val haltReasonIn = UInt(m68k040.socket.HaltReason.W bits)
    haltReasonIn.allowOverride
    haltReasonIn := U(m68k040.socket.HaltReason.NONE, m68k040.socket.HaltReason.W bits)
    haltReasonIn.simPublic()
    val coreHalted = RegInit(False); coreHalted.simPublic()
    val haltReason = RegInit(U(m68k040.socket.HaltReason.NONE, m68k040.socket.HaltReason.W bits))
    haltReason.simPublic()
    when(coreHaltedIn) {
      coreHalted := True
      // `!coreHalted` is the first-wins guard. It reads the REGISTER, not `coreHaltedIn`,
      // so two producers asserting on the SAME cycle resolve by whatever the shared
      // `haltReasonIn` wire carries that cycle -- which is the priority the driver in
      // FullCoreSynth encodes, not an accident of elaboration order.
      when(!coreHalted) { haltReason := haltReasonIn }
    }
    // MMU access-fault per-entry capture (set at COMPLETION from the LS EU's
    // faultCompletion, NOT at alloc — an MMU fault is discovered at execute). On a
    // faulting LS access the LS EU marks the entry faulted vector 2 + the faulting VA
    // + the SSW access attrs {write, sizeBits, supervisor}; the exception FSM stacks
    // the format-$7 frame from these. RegInit Vecs, reset per-alloc (mirrors
    // faultedStore) so a re-used index never carries a stale MMU fault.
    //
    // ── LUT-reduction ROB-fold Slice C ─────────────────────────────────────────
    // WAS seven separate `Vec.fill(64)(Reg)` arrays — faultAddrStore (64x32),
    // faultVecStore (64x8), faultSizeStore (64x2), faultWrStore / faultSupStore /
    // faultAtcStore / faultInstrStore (64x1 each) = 2944 FF — each with SIX
    // independent write ports (ls, sq, eu, fp, alloc0, alloc1) and a 64:1 read mux
    // per bit at h0. `faultAddrStore` alone contributed 2019 of the 2083 loads on
    // the routed netlist's worst fanout net (see the write-select hoist note below).
    //
    // Now split by WRITER into two disjoint halves plus a 1-bit selector:
    //
    //   faultDynMem   — the completion-time half {addr,vec,size,wr,sup,atc}, ONE
    //                   write port fed by the age-arbitrated ls/sq/eu/fp bus.
    //   payload       — the alloc-time half {pc, faultVector, sswInstr, faultAtc}
    //                   (already a 2-alloc-write-port Mem; wr/sup/size were
    //                   constants at alloc and are re-derived, not stored).
    //   faultDynStore — 1-bit alloc-reset gate: "this entry's fault record is the
    //                   COMPLETION-time one". Set ONLY by the arbitration winner
    //                   (the same cycle, and only for the index, whose Mem row that
    //                   write freshens); cleared by alloc0/alloc1 (alloc keeps its
    //                   existing priority over a same-cycle completion on a re-used
    //                   index). This is the identical staleness discipline
    //                   btbIsBranchStore/phtValidStore already provide for
    //                   branchTrainMem and mispredictStore/retireAlone for nextPcMem:
    //                   an uninitialised Mem row is UNREACHABLE because the only
    //                   thing that opens the gate is the write that fills the row.
    //
    // `ram_style`=distributed (NOT block): the read must be ASYNCHRONOUS (it feeds
    // the same-cycle retire/exception decode), which BRAM cannot serve — mirrors
    // nextPcMem/DcachePlugin's explicit-attribute idiom.
    val faultDynMem   = Mem(RobFaultDyn(), depth)
    faultDynMem.addAttribute("ram_style", "distributed")
    val faultDynStore = Vec.fill(depth)(RegInit(False))
    faultDynStore.foreach(_.simPublic())   // debug-only, see faultedStore above
    // The retired constants, kept as named vals so the read sites below read as the
    // documented "what the alloc port used to write" rather than as magic numbers:
    //   - alloc wrote faultWrStore/faultSupStore := False unconditionally;
    //   - alloc wrote faultSizeStore NOT AT ALL (Slice D's bug — a re-used index
    //     inherited the previous occupant's LsFault.sizeBits, which a static
    //     alloc-time / I-fetch-sourced vector-2 fault then stacked into its
    //     format-$7 SSW SIZE field). U(2) is our LsFault.sizeBits "LONG" encoding,
    //     which ExceptionUnit's SSW builder translates to SSW.SIZE=00 ("long") —
    //     the SAME bit pattern the old array's RegInit(2) declared and the same one
    //     the SSW SIZE field always read before task #189 wired it up.
    val faultAllocWr   = False
    val faultAllocSup  = False
    val faultAllocSize = U(2, 2 bits)
    // ── Slice C tripwire: a SIM-ONLY shadow of the OLD seven-array structure ─────
    // These reproduce, bit for bit, what the DELETED `fault*Store` Reg-Vecs would
    // hold: the same six write ports (ls, sq, eu, fp, alloc0, alloc1), unarbitrated,
    // resolved by the same last-assign priority, with eu/fp still leaving
    // {size,wr,sup,atc} untouched. The read-site assertions below then pin the new
    // arbitrated derivation against them at every point the values are actually
    // CONSUMED — which is the direct executable form of the drop-is-unobservable
    // proof (a fired assert means a dropped younger fault reached the head).
    //
    // ONE deliberate deviation: `fsSize` DOES get an alloc-time write here. That is
    // Slice D's bug fix (the old array had none, so a re-used index stacked a
    // previous occupant's LsFault.sizeBits into its format-$7 SSW SIZE field), and
    // shadowing the bug would just make the tripwire assert the bug back.
    //
    // Elaborated only under `includeSimulation` (see M68kSim.scala) => zero synthesis
    // cost. Like `pcStore`, every one of these vals is null in a synth/GenVerilog
    // build and must never be referenced outside a `GenerationFlags.simulation` block.
    val fsFaulted = GenerationFlags.simulation { Vec.fill(depth)(RegInit(False)) }
    val fsVec     = GenerationFlags.simulation { Vec.fill(depth)(RegInit(U(0, 8 bits))) }
    val fsAddr    = GenerationFlags.simulation { Vec.fill(depth)(RegInit(U(0, 32 bits))) }
    val fsSize    = GenerationFlags.simulation { Vec.fill(depth)(RegInit(U(2, 2 bits))) }
    val fsWr      = GenerationFlags.simulation { Vec.fill(depth)(RegInit(False)) }
    val fsSup     = GenerationFlags.simulation { Vec.fill(depth)(RegInit(False)) }
    val fsAtc     = GenerationFlags.simulation { Vec.fill(depth)(RegInit(True)) }
    val fsInstr   = GenerationFlags.simulation { Vec.fill(depth)(RegInit(False)) }
    // Task 11: per-entry FP unimplemented-instruction marker + its command word, written
    // ONLY at alloc (same discipline as the fault* family above) from Task 10's decode
    // fields. Read by ExceptionUnit at vector-11 delivery to latch the state a later
    // FSAVE turns into the unimplemented-instruction state frame.
    // (ROB-fold task #249: folded into `payload.fpuUnimp`/`payload.fpuCmd` -- see
    // RobPayload. No standalone Vec anymore.)
    // Instruction-fetch access-fault: set at ALLOC for a faulted (vector-2) µop whose
    // fault came from the I-cache (sswInstr). Selects a program-space SSW in the $7
    // frame. (ROB-fold Slice C: this is an ALLOC-ONLY value — all four completion
    // ports wrote a constant False into the old array — so it folds into
    // `payload.sswInstr` and the completion-side constant is re-derived at the read
    // site as `!faultDynStore(h0)`.)
    // Interrupt-recognition per-entry capture — now `payload.first` / `payload.pc`
    // (LUT-reduction B1). `payload.first` = the µop is the FIRST of a macro-instruction
    // (an interrupt may be taken only at such a head). `payload.pc` = the head
    // INSTRUCTION's PC = the stacked PC for an interrupt (the not-yet-committed
    // instruction, re-executed after RTE).
    //
    // SIM-ONLY whitebox shadow of `payload.pc`: MiHangTraceSpec (task #139's saved
    // diagnostic) peeks the PC of an ARBITRARY robId (an SQ slot's owner), which the
    // real `payload` Mem cannot serve — MultiPortWritesSymplifier rewrites that Mem out
    // of the netlist entirely (into RamAsyncMwMux banks), so `payload.getBigInt(i)` is
    // not available in simulation. Elaborated ONLY when `includeSimulation` is set (see
    // M68kSim.scala) => zero synthesis cost; `pcStore` is null in every synth/GenVerilog
    // build, so it must never be referenced outside a `GenerationFlags.simulation` block.
    val pcStore = GenerationFlags.simulation {
      val v = Vec.fill(depth)(RegInit(U(0, 32 bits)))
      v.foreach(_.simPublic())
      v
    }
    // LS access-fault completion (driven by the LS-cluster wiring, like
    // branchCompletion). Default-idle (allowOverride) so a standalone DUT elaborates.
    val lsFaultCompletion = Flow(m68k040.execute.LsFault())
    lsFaultCompletion.valid.allowOverride;            lsFaultCompletion.valid := False
    lsFaultCompletion.payload.robId.allowOverride;    lsFaultCompletion.payload.robId := U(0, robIdW bits)
    lsFaultCompletion.payload.faultAddr.allowOverride;lsFaultCompletion.payload.faultAddr := U(0, 32 bits)
    lsFaultCompletion.payload.write.allowOverride;    lsFaultCompletion.payload.write := False
    lsFaultCompletion.payload.sizeBits.allowOverride; lsFaultCompletion.payload.sizeBits := U(0, 2 bits)
    lsFaultCompletion.payload.supervisor.allowOverride; lsFaultCompletion.payload.supervisor := False
    lsFaultCompletion.payload.atc.allowOverride;      lsFaultCompletion.payload.atc := True
    lsFaultCompletion.simPublic()
    // SQ precise-path drain fault (Task P2.4): a second lsFaultCompletion-shaped port
    // rather than sharing one -- the LS EU can fault a YOUNGER access (a translate-
    // time MMU fault) the SAME cycle the SQ faults an OLDER, already-drained precise
    // store's bus error. Identical shape/defaults to lsFaultCompletion above.
    val sqFaultCompletion = Flow(m68k040.execute.LsFault())
    sqFaultCompletion.valid.allowOverride;            sqFaultCompletion.valid := False
    sqFaultCompletion.payload.robId.allowOverride;    sqFaultCompletion.payload.robId := U(0, robIdW bits)
    sqFaultCompletion.payload.faultAddr.allowOverride;sqFaultCompletion.payload.faultAddr := U(0, 32 bits)
    sqFaultCompletion.payload.write.allowOverride;    sqFaultCompletion.payload.write := False
    sqFaultCompletion.payload.sizeBits.allowOverride; sqFaultCompletion.payload.sizeBits := U(0, 2 bits)
    sqFaultCompletion.payload.supervisor.allowOverride; sqFaultCompletion.payload.supervisor := False
    sqFaultCompletion.payload.atc.allowOverride;      sqFaultCompletion.payload.atc := True
    sqFaultCompletion.simPublic()
    // Interrupt/trace preemption interlock (ROB-side half; Task P2.4 builds the SQ-side
    // half). Sibling-driven (the SQ), same allowOverride/simPublic convention as
    // lsFaultCompletion/completion above. While a precise-path store is draining from
    // the SQ, the head must NOT be preempted by an interrupt or trace exception (the
    // drain has already left the ROB's own retire-time control and must be allowed to
    // finish/fault on its own terms before any new exception vectors). Default-idle
    // (False) so a standalone DUT elaborates with no gating; dead-wired until Task
    // P2.5 wires it to the real SQ.
    val preciseDrainBusyIn = Bool(); preciseDrainBusyIn.allowOverride; preciseDrainBusyIn := False
    preciseDrainBusyIn.simPublic()
    // Load-side sibling of `preciseDrainBusyIn`: an INHIBITED load's bus read is
    // genuinely outstanding (launched, response not yet consumed -- LsEuPlugin's
    // `inhibitedLoadBusySig`). Kept as a SEPARATE input rather than folded into
    // `preciseDrainBusyIn` -- that name/comment is store-drain-specific, and the
    // two conditions have independent launch/clear events (a store drains from the
    // SQ at head; a load launches from LsEuPlugin's P4 once it IS head). Same
    // rationale as `preciseDrainBusyIn`: while a device read is in flight, the head
    // must not be preempted by a newly-recognized interrupt/trace, or the AXI
    // response (a real, possibly clear-on-read/pop device side effect) gets
    // silently poisoned/discarded and the SAME instruction re-issues a SECOND real
    // device read after RTE.
    val inhibitedLoadBusyIn = Bool(); inhibitedLoadBusyIn.allowOverride; inhibitedLoadBusyIn := False
    inhibitedLoadBusyIn.simPublic()
    // Execute-time conditional fault completion (generalized; driven by the branch EU
    // for TRAPV and the div EU for CHK/DIV0). When an execute-time check raises a
    // synchronous group-2 trap the EU drives this with {robId, vector}; the ROB marks
    // the entry FAULTED + the carried vector. faultPc (= nextPc) and the PPC (=
    // instruction pc, payload.pc) are already captured per-entry at alloc, so this only
    // flips faulted+vector. Default-idle (allowOverride) so a standalone DUT
    // elaborates; the EU-wiring OVERRIDES it. (Field name kept as `euFaultCompletion`.)
    val euFaultCompletion = Flow(m68k040.execute.EuFault())
    euFaultCompletion.valid.allowOverride;          euFaultCompletion.valid := False
    euFaultCompletion.payload.robId.allowOverride;  euFaultCompletion.payload.robId := U(0, robIdW bits)
    euFaultCompletion.payload.vector.allowOverride; euFaultCompletion.payload.vector := U(0, 8 bits)
    euFaultCompletion.payload.faultAddr.allowOverride; euFaultCompletion.payload.faultAddr := U(0, 32 bits)
    euFaultCompletion.simPublic()
    // FP enabled-trap escalation (vectors 49-54), driven by the CPLX EU's FP writeback lane.
    // A SECOND euFault-shaped port rather than a share of the one above, for the same reason
    // completion port 5 is separate from port 3: the int/NZVC lane and the FP lane are
    // independent pipelines that can both fault in the same cycle, and a Flow cannot recover
    // a dropped one. (Identical precedent: sqFaultCompletion vs lsFaultCompletion.)
    // Inert until the FPCR/FPSR control task drives the EU's exception-ENABLE input: FPCR
    // resets to 0 and no decode path can write it yet, so no escalation is reachable — the
    // same "real driver, currently-constant source" state mmuEnable/iplIn were in before
    // their own producers landed.
    val fpFaultCompletion = Flow(m68k040.execute.EuFault())
    fpFaultCompletion.valid.allowOverride;          fpFaultCompletion.valid := False
    fpFaultCompletion.payload.robId.allowOverride;  fpFaultCompletion.payload.robId := U(0, robIdW bits)
    fpFaultCompletion.payload.vector.allowOverride; fpFaultCompletion.payload.vector := U(0, 8 bits)
    fpFaultCompletion.payload.faultAddr.allowOverride; fpFaultCompletion.payload.faultAddr := U(0, 32 bits)
    fpFaultCompletion.simPublic()
    // Per-entry committed-CCR VALUE capture (set at completion from the EU writeback
    // values via ccrCompletion). Folded into committedCcr at retire (for the stacked
    // exception frame). RegInit False so an unwired entry contributes nothing.
    // Slice E ALSO assessed folding these two into `nzvcPrf[p.nzvcNew]` / `xPrf[p.xNew]`.
    // Unlike `sysValStore` (see its declaration above — that fold is unsound), the SAFETY
    // argument here does hold: every read below is gated on `nzvcWrStore`/`xWrStore`,
    // which the EUs drive from the SAME rename-time `writesNzvc`/`writesX` flag that gates
    // both the freelist pop and the real PRF write (`nzvcW.valid := fastFire &&
    // u1.writesNzvc; nzvcW.address := u1.pNzvcDst`), so whenever a read fires the phys reg
    // is genuinely allocated; and every read is a single-cycle read at the entry's OWN
    // retire/fault cycle, at which point no younger entry can have committed a redefinition
    // (in-order commit) and no flush can have recycled the tag (every flush in this design
    // is head-relative — `branchRedirect = retire0 && ...`, and the exception FSM triggers
    // off the head — so any earlier flush would have squashed this entry rather than let it
    // reach retire).
    //
    // It was NOT done anyway: the prize is only 64*4 + 64*1 = 320 FF, while the cost is a
    // read index that moves from `head` (a plain register, available at cycle start) to
    // `payload.readAsync(h0).nzvcNew` — serialising a Mem read into a PRF read inside the
    // retire path, on a netlist sitting 0.069ns from its 200MHz target. Bad trade; revisit
    // only if the retire path stops being the binding constraint.
    val nzvcValStore = Vec.fill(depth)(Reg(UInt(4 bits)))
    val nzvcWrStore  = Vec.fill(depth)(RegInit(False))
    nzvcValStore.simPublic(); nzvcWrStore.simPublic()   // sim-only taps (committedCcr shadow tests)
    val xValStore    = Vec.fill(depth)(RegInit(False))
    val xWrStore     = Vec.fill(depth)(RegInit(False))
    // CCR-value completion: the EU-wiring drives {robId, nzvc, nzvcWrite, x, xWrite}
    // for a completing CCR-writer (one port per EU). Default-idle (allowOverride) so
    // a DUT that doesn't wire it elaborates; the full-core wiring OVERRIDES it from
    // the EUs' wbObs.
    val ccrCompletion = Vec.fill(4)(Flow(m68k040.rob.CcrCompletion()))
    ccrCompletion.foreach { c =>
      c.valid.allowOverride;            c.valid := False
      c.payload.robId.allowOverride;    c.payload.robId := U(0, robIdW bits)
      c.payload.nzvc.allowOverride;     c.payload.nzvc := U(0, 4 bits)
      c.payload.nzvcWrite.allowOverride;c.payload.nzvcWrite := False
      c.payload.x.allowOverride;        c.payload.x := False
      c.payload.xWrite.allowOverride;   c.payload.xWrite := False
      c.payload.result.allowOverride;   c.payload.result := B(0, 32 bits)
      c.payload.intWrite.allowOverride; c.payload.intWrite := False
      c.simPublic()
    }

    // ── Build a RobPayload from a RenamedUop ───────────────────────────────────
    def payloadFrom(u: RenamedUop): RobPayload = {
      val p = RobPayload()
      p.predNextPc := u.nextPc
      p.archRegId  := u.dstArch
      p.intNew     := u.pdst;     p.intOld := u.pdstOld;   p.intWrite  := u.pdstValid
      p.nzvcNew    := u.pNzvcDst; p.nzvcOld := u.pNzvcOld;  p.nzvcWrite := u.writesNzvc
      p.xNew       := u.pXDst;    p.xOld := u.pXOld;        p.xWrite    := u.writesX
      // FP data + FPCC: thread the ALREADY-DECIDED rename identity through to retire.
      // The ROB is NOT a second source of truth here -- it never re-derives a tag; the
      // FP PRF write itself happens at COMPLETION (DivEuPlugin's FP writeback lane, which
      // writes RegFilePluginFp at this same u.pFpDst), and these fields only drive the
      // commit-time fpRat/fpccRat committed-mapping update + the fpFree/fpccFree push of
      // the OLD tag. Exactly the int/NZVC/X split above.
      p.fpArchDst  := u.fpDstArch
      p.fpNew      := u.pFpDst;   p.fpOld := u.pFpOld;      p.fpWrite   := u.pFpDstValid
      p.fpccNew    := u.pFpccDst; p.fpccOld := u.pFpccOld;  p.fpccWrite := u.writesFpcc
      // A faulted µop AND an RTE retire ALONE (precise / serializing): they must be
      // the head and the only retirer this cycle. (`faulted` still lives in a RegInit
      // per-entry Vec — faultedStore — reset per-alloc like mispredictStore. `isRte`
      // moved into this Mem in LUT-reduction B1: safe because EVERY read of it is
      // conjunctively gated by `headReady`/`count > 0`, which already prove the slot was
      // written by an alloc — see the RobPayload SAFETY note. What must NOT move into an
      // uninit Mem is any bit that is read WITHOUT such a gate.)
      p.retireAlone := u.isBranch
      // LUT-reduction B1 — alloc-only fields, formerly standalone Reg-Vecs.
      p.pc         := u.pc
      p.isRte      := u.isRte
      p.first      := u.firstOfInstr
      p.last       := u.lastOfInstr
      p.debugBreakValid := u.debugBreakValid
      p.debugBreakSlot  := u.debugBreakSlot
      p.needsSup   := u.needsSupervisor
      p.sysOp      := u.sysOp
      p.sysKind    := u.sysKind
      p.sysReadDir := u.sysReadDir
      p.sysRc      := u.imm(11 downto 0).asUInt
      // ROB-fold Slice A: the 1-bit fault-PC selector (replaces faultPcStore).
      p.faultUsesNextPc := u.faultUsesNextPc
      // ROB-fold Slice C: the ALLOC-TIME half of the per-entry fault record
      // (replaces the alloc write ports of faultVecStore / faultInstrStore /
      // faultAtcStore — same sources, same alloc cycle, same address).
      p.faultVector := u.faultVector
      p.sswInstr    := u.sswInstr
      p.faultAtc    := u.faultAtc
      // ROB-fold (task #249): FSAVE's route-to-FPSP source state (replaces
      // fpuUnimpStore/fpuCmdStore -- Task 10's decode fields, same as before).
      p.fpuUnimp := u.fpuSoftwareComplete
      p.fpuCmd   := u.fpuCmdWord
      p
    }

    // ── Commit / retire (combinational, read at head) ──────────────────────────
    val h0 = head
    val h1 = head + 1
    val p0 = payload.readAsync(h0)
    val p1 = payload.readAsync(h1)
    // debug-only, task B2: expose the raw payload read (incl. the 8 LUT-reduction-B1
    // folded fields) so a directed test can round-trip-verify them directly instead of
    // only through derived consumers (rteRetire/sysRetire/privViolation/...). Zero
    // synthesis cost, same convention as head/tail/count/completes above.
    p0.simPublic(); p1.simPublic()

    // ── Macro-boundary detection for debug stop (Stage 2) ───────────────────────
    // "Is the entry retiring at h0 THIS cycle the LAST µop of its macro?" -- spec
    // section 6.2's macroLast, now a real alloc-time-captured per-entry fact
    // (RobPayload.last, threaded from DecodedUop/RenamedUop.lastOfInstr through every
    // MicroOpAssembler/Microcode crack site). A ring-occupancy-derived form
    // ((count <= 1) || p1.first) was tried first and is WRONG: a 3-µop crack (e.g. a
    // memory-destination RMW [load, op, store]) can reach count==1 with h0 = the MIDDLE
    // op µop, mid-macro, before its store is even allocated -- so a debug stop taken at
    // that instant would RECOVER there and the store would never execute. Valid only
    // under the usual `count > 0` gating (p0 is an uninitialised-Mem read otherwise).
    val h0IsMacroLast = p0.last
    h0IsMacroLast.simPublic()
    // Commit-time mispredict redirect is a REGISTERED pulse (declared here so the
    // retire guards can gate on it). `flushing` = test flush OR the registered
    // redirect pulse; it drives ONLY pointer/reg resets (no combinational fanout).
    val doFlushReg = RegInit(False); doFlushReg.simPublic()
    val flushPcReg = Reg(UInt(32 bits)); flushPcReg.simPublic()
    val debugHaltState = Reg(DebugHaltState()) init DebugHaltState.RUNNING
    debugHaltState.simPublic()
    val debugHalted = debugHaltState === DebugHaltState.HALTED
    debugHalted.simPublic()
    val debugQuiesceActive = (debugHaltState =/= DebugHaltState.RUNNING) &&
      (debugHaltState =/= DebugHaltState.STEP_RUNNING)
    // Exact next-state truth for FetchAlign's localized quiesce register: begin
    // quiescing on the stop-sampling edge and release on the resume edge.
    // Declared before retire so the pipelined halt-after result can gate the following
    // macro without putting its 64-bit compare on the commit hot path.
    val debugMacroCountReg = Reg(UInt(64 bits)) init 0
    debugMacroCountReg.simPublic()
    val haltAfterCmpCountReg = RegNext(debugMacroCountReg) init 0
    val haltAfterCmpEpochReg = RegNext(haltAfterEpochIn) init 0
    val haltAfterCmpArmedReg = RegNext(haltAfterArmedIn && !haltAfterInvalidateIn) init False
    val haltAfterCmpHitReg = RegNext(debugMacroCountReg >= haltAfterTargetIn) init False
    haltAfterCmpCountReg.simPublic(); haltAfterCmpEpochReg.simPublic()
    haltAfterCmpArmedReg.simPublic(); haltAfterCmpHitReg.simPublic()
    val haltAfterComparePending = RegInit(False); haltAfterComparePending.simPublic()
    val haltAfterArmedPrev = RegNext(haltAfterArmedIn) init False
    val haltAfterArmRise = haltAfterArmedIn && !haltAfterArmedPrev
    val haltAfterDue = haltAfterArmedIn && haltAfterCmpArmedReg &&
      !haltAfterComparePending && !haltAfterInvalidateIn &&
      (haltAfterCmpEpochReg === haltAfterEpochIn) && haltAfterCmpHitReg
    haltAfterDue.simPublic()
    // On arm and after every completed macro, hold the next macro for the single cycle
    // needed to sample the new count and register the wide comparison result.
    val haltAfterRetireBlock = haltAfterArmedIn &&
      (!haltAfterCmpArmedReg || haltAfterComparePending)

    val debugStepBoundaryHit = Bool() // driven after retire/boundary classification
    val debugBreakpointBoundaryHit = Bool()
    val debugExceptionBoundaryHit = Bool()
    val debugQuiesceNext = Mux(debugHaltState === DebugHaltState.RUNNING,
      debugStopRequestIn || a7OddStopReq || pcRangeStopReq || haltAfterDue || debugBreakpointBoundaryHit || debugExceptionBoundaryHit,
      Mux(debugHaltState === DebugHaltState.HALTED,
        !(debugResumeRequestIn || debugStepRequestIn),
        Mux(debugHaltState === DebugHaltState.STEP_RUNNING,
          debugStepBoundaryHit || debugBreakpointBoundaryHit || debugExceptionBoundaryHit, True)))
    val debugLivePcReg = Reg(UInt(32 bits)) init 0
    debugLivePcReg.simPublic()
    // Exception squash: high on the entry/RTE trigger cycle AND while the FSM runs
    // (serializing — keep younger work squashed + block alloc/retire). Driven after
    // the exc unit is built; forward-declared so `flushing` can gate on it.
    val excSquash = Bool()
    val flushing   = flush.valid || doFlushReg || excSquash
    flushing.simPublic() // 2026-08-27 boot-investigation debug tap, see interruptPending's

    // The head is a faulted µop ready to retire -> take the exception INSTEAD of a
    // normal commit (precise: the faulting instruction does not commit its result).
    // An RTE head is serializing too: it triggers the exception-return FSM and does
    // NOT drive a normal int/flag commit. The exception FSM (exc) gates these so a
    // trigger fires once per event (excIdle).
    val excIdle = Bool()   // driven below from exc.active
    excIdle.simPublic() // 2026-08-27 boot-investigation debug tap
    // Committed S (supervisor) bit — the SAME wire as the PrivilegeService `_supervisor`
    // pre-allocated in `setup` (see the class-level comment above); FORWARD-DECLARED
    // here too (the privilege check below gates on it) and DRIVEN from exc.ss.s after
    // the exc unit is built.
    val committedS = _supervisor; committedS.simPublic()
    val headReady   = (count > 0) && completes(h0) && !flushing && !coreHalted && !debugHalted
    // Privilege violation: a needsSupervisor head retiring in USER mode (committed S==0)
    // takes a vector-8 (format-$0) exception. Treated like a faulted head — the op does
    // NOT commit its result (precise). Only meaningful when the head is otherwise ready.
    val privViolation = headReady && p0.needsSup && !committedS && excIdle; privViolation.simPublic()
    // The head triggers an exception when it is a STATICALLY faulted head OR a privilege
    // violation. Both route through the same entry FSM (the fault PC mux / faultVecStore
    // are overridden below for the privilege case).
    val faultRetire = headReady && (faultedStore(h0) || privViolation) && excIdle; faultRetire.simPublic()
    // An RTE head that ALSO needs supervisor (Track C: payload.needsSup set at decode) and
    // is retiring in USER mode is a privViolation, not a real RTE — exclude it here so it
    // routes through faultRetire/privOnly (vector-8, format-$0, stack untouched) instead
    // of the RTE pop/redirect FSM. In supervisor mode privViolation is False and RTE
    // proceeds normally (unaffected — this mirrors the existing MOVE-from-SR privilege
    // gate, not a new mechanism).
    val rteRetire   = headReady && p0.isRte   && !privViolation && excIdle; rteRetire.simPublic()
    // A commit-time PRIVILEGED SYSTEM op (MOVE-to-SR / MOVE-USP / MOVEC) at the head:
    // serializing (retires ALONE, like fault/RTE). It triggers either the system-op
    // FSM (S=1 supervisor) OR a vector-8 privilege fault (S=0 user) — resolved below
    // after the exc unit exposes ss.s. A faulted/RTE head takes priority over a sysOp
    // (a head can't be both: sysOps are never faulted/RTE at alloc).
    // Gated on sysValRdyStore so a WRITE-direction sysOp triggers only AFTER its source
    // VALUE has been captured (the EU's `completion` precedes its `wbObs` by one cycle).
    // The READ direction also waits one harmless extra cycle (its wbObs sets the flag too).
    val sysRetire   = headReady && p0.sysOp && sysValRdyStore(h0) &&
                      !faultedStore(h0) && !p0.isRte &&
                      excIdle; sysRetire.simPublic()

    // ── NMI (level 7) edge-latch ─────────────────────────────────────────────────
    // Real 68040 NMI is EDGE-triggered (Musashi m68kcpu.c:m68k_set_irq — `if(old_level
    // != 0x0700 && CPU_INT_LEVEL == 0x0700) nmi_pending = TRUE;`, consumed+cleared once
    // in m68ki_check_interrupts). A plain level compare (`iplIn === 7`) would
    // re-recognize NMI EVERY CYCLE the line is merely HELD at 7 -> infinite re-entry.
    // Latch on a <7 -> ==7 transition; clear when that NMI is actually taken (below,
    // after `interruptPending` is driven). Levels 1..6 stay level-sensitive (compared
    // against the live SR I-mask every cycle) — unaffected. Computed here (independent
    // of the exc unit) so `interruptLevel`/`interruptVec` can consult it below: once
    // latched, the CPU services vector/level 7 even if `iplIn` is later dropped by the
    // SoC before the FSM gets around to recognizing it (mirrors Musashi always calling
    // `m68ki_exception_interrupt(7)` on a consumed nmi_pending, not the live level).
    val iplInPrev  = RegNext(iplIn, init = U(0, 3 bits))
    val nmiEdge    = (iplInPrev =/= U(7, 3 bits)) && (iplIn === U(7, 3 bits))
    val nmiPending = RegInit(False); nmiPending.simPublic()
    when(nmiEdge) { nmiPending := True }

    // ── Interrupt recognition (precise, at a macro-instruction boundary) ─────────
    // Take an interrupt BETWEEN instructions when ALL hold:
    //   (a) iplIn > srSys[2:0] (the SR I-mask) OR a latched NMI edge is pending;
    //   (b) the ROB head is the FIRST µop of an instruction (p0.first) — never
    //       mid-cracked-instruction;
    //   (c) the head is NOT faulted / NOT RTE (its own exception/return has priority);
    //   (d) excIdle (the exc-FSM is not already running);
    //   (e) a head is present (count>0) and we are not flushing.
    // We do NOT require completes(h0): the interrupt PREEMPTS the head (it does not
    // commit — it re-executes after RTE). The stacked PC is the head INSTRUCTION's
    // PC (p0.pc); the vector is the simple-protocol curVec computed below.
    // `interruptPending` is FORWARD-DECLARED here (retire0 gates on it) and DRIVEN
    // after the exc unit is built (it reads the SR I-mask from exc.ss.srSys).
    val interruptPending = Bool(); interruptPending.simPublic()
    val interruptLevel = UInt(3 bits)
    interruptLevel := Mux(nmiPending, U(7, 3 bits), iplIn); interruptLevel.simPublic()
    // Simple-protocol vector: autovector (24+level) or the vectored input.
    val interruptVec = UInt(8 bits)
    interruptVec := Mux(iackAvec, (U(24, 8 bits) + interruptLevel).resized, iackVector)
    interruptVec.simPublic()
    // While stopped (ROB empty), stack the latched STOP-successor PC (the resume point);
    // otherwise the preempted head instruction's PC. (`stopped`/`stoppedPc` are Regs above.)
    val interruptPc = UInt(32 bits); interruptPc := Mux(stopped, stoppedPc, p0.pc); interruptPc.simPublic()

    // ── Trace exception (T0/T1), task #193 ───────────────────────────────────────
    // A POSTPONED exception (vector 9, format-$2): fires AFTER a traced instruction
    // has fully, normally retired, at the NEXT macro-instruction boundary (never
    // mid-crack — see the arming/dispatch split below). `tracePendingReg` latches
    // "some already-retired instruction was trace-armed and is awaiting dispatch";
    // `tracePendingPc`/`tracePendingPpc` latch its {resume PC, own PC} for the
    // eventual format-$2 frame (PC field = resume address = the NEXT instruction;
    // PPC field = the traced instruction's own address — mirrors Musashi's
    // REG_PC/REG_PPC split in m68ki_exception_trace/m68ki_stack_frame_0010).
    //
    // `tracePendingFire` (FORWARD-DECLARED here, retire0/retire1 gate on it; DRIVEN
    // after the exc unit is built, since it needs exc.ss.srSys) mirrors
    // `interruptPending`'s own forward-declaration precedent exactly. `h0TraceArmed`
    // (also forward-declared) says "the instruction ABOUT to retire at h0 THIS cycle
    // is itself trace-armed" — used to force single-wide retire (block retire1) so a
    // paired h1 never slips past a boundary that must be traced before it executes
    // (prm_trace_t1_before_next.s's exact concern, generalized to 2-wide retire).
    val tracePendingFire = Bool(); tracePendingFire.simPublic()
    val h0TraceArmed     = Bool(); h0TraceArmed.simPublic()
    val tracePendingReg  = RegInit(False); tracePendingReg.simPublic()
    val tracePendingPc   = Reg(UInt(32 bits)); tracePendingPc.simPublic()
    val tracePendingPpc  = Reg(UInt(32 bits)); tracePendingPpc.simPublic()

    // A normal retire is also blocked while an interrupt is pending (the head does
    // not commit — like the faulted-head case).
    // retire0 blocked by: a faulted/RTE/sysOp head (all serializing), a pending interrupt,
    // a pending TRACE dispatch (task #193), OR a privilege-violation head (Track C
    // MOVE-from-SR in user mode). retire1 likewise excludes a needsSupervisor (C) or
    // sysOp (D) head from slot-1 (both serializing), AND a trace-armed h0 (so h1 never
    // retires in the same cycle as the instruction that just armed a pending trace —
    // it must wait for the trace exception to be taken first).
    // Task 9b: a value-capture-marked µop must not retire before its EU writeback has
    // landed in `sysValStore` — otherwise `sysAux` would latch the PREVIOUS occupant of
    // that ROB index. Textually the same guard `sysRetire` already applies to a
    // write-direction sysOp's own source value, for the identical reason (the EU's
    // `completion` port fires one cycle before its `ccrCompletion` value on some EUs).
    // In practice never stalls — these rows are LS loads, whose `ccrObs` is a plain alias
    // of the same registered completion — but the guard makes that a property of the ROB
    // rather than of LsEuPlugin's internal timing.
    // NB: deliberately a `def`, not a `val` — binding a SpinalEnum ELEMENT to a named
    // hardware-scope val makes SpinalHDL's reflective auto-naming rename the element
    // itself, which then leaks out through `.toEnum.toString` anywhere in the design.
    def sysAuxCapKind = m68k040.decode.SysKind.FPCTRL_CAP
    val sysAuxRdy0 = (p0.sysKind =/= sysAuxCapKind) || sysValRdyStore(h0)
    val sysAuxRdy1 = (p1.sysKind =/= sysAuxCapKind) || sysValRdyStore(h1)
    debugBreakpointBoundaryHit := (count > 0) && p0.first && p0.debugBreakValid &&
      excIdle && !flushing && !stopped && !coreHalted &&
      ((debugHaltState === DebugHaltState.RUNNING) ||
       (debugHaltState === DebugHaltState.STOP_PENDING) ||
       (debugHaltState === DebugHaltState.STEP_RUNNING))
    debugBreakpointBoundaryHit.simPublic()
    val retire0 = headReady && !faultedStore(h0) && !p0.isRte && !p0.sysOp &&
                  !interruptPending && !privViolation && !stopped && !tracePendingFire &&
                  sysAuxRdy0 && !haltAfterDue && !haltAfterRetireBlock &&
                  !debugBreakpointBoundaryHit
    // Root-cause fix (post-Task-P2.5 lock-step investigation): completion port 4
    // (the SQ precise-path at-head drain) fires ASYNCHRONOUSLY, many cycles after
    // its store's issue -- unlike every other completion source, which settles
    // (compValid) at EXECUTE time, long before its instruction reaches h0 (so an
    // external async input like an interrupt line has had many prior cycles to be
    // sampled/recognized before a dual-retire pairing can occur). A precise store's
    // own h0 retire-eligibility can therefore arrive on the SAME cycle its
    // immediate successor (h1) has ALSO been sitting completes-ready for a while,
    // letting h1 slip through in the SAME dual-retire cycle before any observer
    // reacting to h0's retire (e.g. a testbench/interrupt-controller sampling at
    // instruction boundaries) gets a chance to act on it. STICKY, level-sensitive
    // latch (post-review fix -- the original was a one-shot RegNext pulse that
    // self-cleared exactly one cycle after the completion(4) pulse regardless of
    // whether h0's own retire0 had actually fired that cycle yet; if retire0 was
    // ADDITIONALLY delayed past that single cycle by an unrelated stall coincident
    // with h0 -- interruptPending, tracePendingFire, stopped, all of which also gate
    // retire0 above -- the guard would have already self-cleared before h0's real
    // retire cycle, silently reopening the exact dual-retire race this mechanism
    // exists to prevent). Sets on the completion(4) pulse and clears when h0 stops
    // being the entry it was armed for -- either retire0 fires (h0 itself retires and
    // head advances past it) or `flushing` reassigns that ROB index (see the P2.7 note
    // on the clear condition below) -- so it is level-sensitive like `h0TraceArmed`
    // ("stays asserted every cycle until h0 is gone") rather than a one-shot pulse,
    // which is what makes it deliver its "force single-wide retire" intent reliably.
    val h0PreciseCompletedSticky = RegInit(False); h0PreciseCompletedSticky.simPublic()
    when(retire0 || flushing) {
      // `|| flushing` (P2.7 review fix): `retire0` is NOT the only way h0 stops being
      // the entry this guard was armed for. Every OTHER head-consuming path --
      // faultRetire (the drain took a real bus error -> faultedStore(h0) -> the
      // exception FSM), rteRetire, sysRetire, a branch-mispredict redirect, or a test
      // flush -- ends in `flushing`, which sets `count := 0` / `tail := head` and hands
      // that same ROB index straight to the NEXT (brand-new) instruction. Clearing only
      // on retire0 therefore carried the guard across the flush boundary and disabled
      // 2-wide retire for an unrelated successor -- and for a back-to-back run of
      // non-retire0 heads (a chain of sysOps/RTEs) it could stay asserted for many
      // instructions. Correctness was never at risk (the guard only ever FORCES
      // single-wide retire), but the IPC loss was silent. `flushing` is exactly the
      // "this head index is being reassigned" event, so it is the right co-clear;
      // h0TraceArmed needs no equivalent because it is combinational off h0.
      h0PreciseCompletedSticky := False
    }.elsewhen(completion(4).valid && (completion(4).payload === h0)) {
      // retire0 given priority above is intentionally defensive: architecturally a
      // fresh completion(4) pulse for the exact entry that is ALSO retiring this
      // same cycle cannot occur (that pulse would have had to fire a prior cycle to
      // make completes(h0)/retire0 true in the first place), but ordering it this
      // way costs nothing and removes any ambiguity.
      h0PreciseCompletedSticky := True
    }
    val retire1 = retire0 && (count > 1) && completes(h1) && !p0.retireAlone && !p1.retireAlone &&
                  !faultedStore(h1) && !p1.isRte && !p1.needsSup && !p1.sysOp &&
                  !h0TraceArmed && !h0PreciseCompletedSticky && sysAuxRdy1 &&
                  !(haltAfterArmedIn && h0IsMacroLast) &&
                  !((debugStopRequestIn || (debugHaltState === DebugHaltState.STOP_PENDING)) &&
                    h0IsMacroLast) &&
                  !((debugHaltState === DebugHaltState.STEP_RUNNING) && h0IsMacroLast)
    val debugMacroCountInc =
      (retire0 && p0.last).asUInt.resize(2) +
      (retire1 && p1.last).asUInt.resize(2)

    // Ordinary manual/halt-after stops are post-commit. The macro at the head when
    // the request is sampled completes in full; the slot-1 guard above prevents the
    // same retire cycle from crossing into its successor. HALTED is not observable
    // until the following RECOVER cycle has driven the registered flush.
    val debugAutoHaltLatchedReg = RegInit(False); debugAutoHaltLatchedReg.simPublic()
    val debugStopActive = debugStopRequestIn || a7OddStopReq || pcRangeStopReq || haltAfterDue ||
      (debugHaltState === DebugHaltState.STOP_PENDING)
    val debugSequencerBoundaryHit = Bool() // driven below from the completed redirect
    val debugNormalBoundaryHit = retire0 && h0IsMacroLast
    // With no ROB work and no exception/system sequencer active, the core is already at
    // a clean macro boundary. Without this arm a manual request sampled between fetch
    // bursts entered STOP_PENDING forever waiting for a retirement that quiescing had
    // deliberately prevented from ever arriving.
    val debugIdleBoundaryHit = (count === 0) && excIdle && !flushing
    // A valid halt-after result describes the boundary AFTER an already-completed
    // target macro. retire0 is held above, so any current h0 is its untouched successor.
    val debugAutomaticBoundaryHit = haltAfterDue && excIdle && !flushing
    val debugStopBoundaryHit = debugStopActive &&
      (debugNormalBoundaryHit || debugSequencerBoundaryHit || debugIdleBoundaryHit ||
       debugAutomaticBoundaryHit)
    debugStopBoundaryHit.simPublic()
    val debugStepNormalBoundaryHit = (debugHaltState === DebugHaltState.STEP_RUNNING) &&
      ((retire0 && p0.last) || (retire1 && p1.last))
    debugStepBoundaryHit := (debugHaltState === DebugHaltState.STEP_RUNNING) &&
      (debugStepNormalBoundaryHit || debugSequencerBoundaryHit)
    debugStepBoundaryHit.simPublic()
    // Hold the debug recover trigger (and, symmetrically, `debugPcApply` further
    // below) while a PRECISE store's drain is genuinely in flight
    // (`preciseDrainBusyIn` -- an already-ACCEPTED, irrevocable bus write; same
    // gate `normalIrqGate`/`traceNormalGate` already use to keep an
    // interrupt/trace from preempting this identical hazard, see that doc comment
    // above). `debugRecoverEnter`/`debugPcApply` are the only two of `doFlushReg`'s
    // four disjuncts that are NOT retire-gated -- `branchRedirect`/
    // `exc.redirectValid` can only ever land on a RETIRING head, and an
    // uncommitted precise entry blocks retire of everything at/behind it (see
    // StoreQueue.scala's own flush doc comment) -- but a JTAG-driven
    // halt/step/breakpoint/PC-apply can land on ANY cycle, including mid-drain of
    // a precise store whose bus write has already left the CPU and cannot be
    // un-issued. `efcd953` fixed the SYMPTOM (StoreQueue's flush `keep()` logic,
    // `headDrainInFlight`) so a squash here can no longer permanently wedge the SQ
    // ring -- this closes the gap at the SOURCE instead: hold the debug halt/
    // PC-apply trigger itself back until the drain resolves (its one AXI
    // transaction, typically a handful of cycles) rather than letting it fire and
    // squash the in-flight half at all. `headDrainInFlight` stays in StoreQueue.scala
    // as defense-in-depth (e.g. the separate, NOT retire-gated `coreHalted`/
    // D-cache-fatal path -- see `coreHaltedIn`'s doc comment -- is not covered by
    // this ROB-side gate either).
    //
    // A plain combinational AND (not a latch) is correct: `debugBreakpointBoundaryHit`
    // is LEVEL-held against the unchanging ROB head while `preciseDrainBusyIn` keeps
    // `retire0` (hence `h0`) frozen, so it simply re-observes true the first cycle the
    // gate opens. The other three disjuncts are retire0-synchronized pulses that, by
    // construction, cannot themselves occur while a drain is genuinely UNRESOLVED
    // (retire0 requires `completes(h0)`, which requires the SQ's own completion for
    // that entry, which only fires once the drain has already resolved) -- the only
    // window where they can coincide with `preciseDrainBusyIn` is StoreQueue's
    // deliberate one-cycle-past-resolution pad (its own doc comment: "so the ROB's
    // normalIrqGate/traceNormalGate never race a same-cycle preempt against a drain
    // that just resolved"), which self-heals via `debugIdleBoundaryHit`/
    // `debugAutomaticBoundaryHit`/the next macro's own boundary if that exact pulse
    // is missed -- identical, already-shipped tradeoff to `interruptPending`'s.
    val debugRecoverEnterCond = debugExceptionBoundaryHit || debugBreakpointBoundaryHit ||
      debugStopBoundaryHit || debugStepBoundaryHit
    val debugRecoverEnter = debugRecoverEnterCond && !preciseDrainBusyIn
    debugRecoverEnter.simPublic()
    val debugHaltHitInstCountReg = Reg(UInt(64 bits)) init 0
    debugHaltHitInstCountReg.simPublic()
    when(debugStopBoundaryHit) {
      // Automatic results carry the sampled count as well as the epoch. Manual stops
      // capture the post-commit count of the boundary macro on this same edge.
      debugHaltHitInstCountReg := Mux(haltAfterDue || debugAutoHaltLatchedReg,
        haltAfterCmpCountReg, debugMacroCountReg + debugMacroCountInc.resized)
    }
    when(debugBreakpointBoundaryHit || debugExceptionBoundaryHit) {
      debugHaltHitInstCountReg := debugMacroCountReg
    }
    val debugHaltHitPcReg = Reg(UInt(32 bits)) init 0
    debugHaltHitPcReg.simPublic()
    val debugExceptionPendingReg = RegInit(False)
    val debugHaltExceptionVectorReg = Reg(UInt(8 bits)) init 0
    val debugHaltExceptionPcReg = Reg(UInt(32 bits)) init 0
    val debugHaltExceptionFaultAddressReg = Reg(UInt(32 bits)) init 0
    debugExceptionPendingReg.simPublic(); debugHaltExceptionVectorReg.simPublic()
    debugHaltExceptionPcReg.simPublic(); debugHaltExceptionFaultAddressReg.simPublic()
    when(debugClearStickyIn) { debugHaltHitPcReg := 0 }
    when(debugBreakpointBoundaryHit) { debugHaltHitPcReg := p0.pc }
    // Every transition into RECOVER below is ANDed with `!preciseDrainBusyIn` --
    // the FSM must stay put (RUNNING/STOP_PENDING/STEP_RUNNING) for as long as a
    // precise SQ drain is in flight, exactly mirroring `debugRecoverEnter`'s own
    // gate above (these raw disjuncts are its constituents; see that doc comment
    // for the full rationale/timing argument). A STOP_PENDING park is unaffected
    // (`debugStopBoundaryHit` cannot itself be true while a drain is genuinely
    // UNRESOLVED -- see above -- so the `.otherwise` arm below already handles it).
    switch(debugHaltState) {
      is(DebugHaltState.RUNNING) {
        when((debugExceptionBoundaryHit || debugBreakpointBoundaryHit) && !preciseDrainBusyIn) {
          debugHaltState := DebugHaltState.RECOVER
        }.elsewhen(debugStopRequestIn || a7OddStopReq || pcRangeStopReq || haltAfterDue) {
          when(debugStopBoundaryHit && !preciseDrainBusyIn) { debugHaltState := DebugHaltState.RECOVER }
            .otherwise                                      { debugHaltState := DebugHaltState.STOP_PENDING }
        }
      }
      is(DebugHaltState.STOP_PENDING) {
        when((debugExceptionBoundaryHit || debugBreakpointBoundaryHit || debugStopBoundaryHit) &&
             !preciseDrainBusyIn) {
          debugHaltState := DebugHaltState.RECOVER
        }
      }
      is(DebugHaltState.RECOVER) {
        debugHaltState := DebugHaltState.HALTED
      }
      is(DebugHaltState.HALTED) {
        when(debugStepRequestIn && !coreHalted) {
          debugHaltState := DebugHaltState.STEP_RUNNING
        }.elsewhen(debugResumeRequestIn && !coreHalted) {
          debugHaltState := DebugHaltState.RUNNING
        }
      }
      is(DebugHaltState.STEP_RUNNING) {
        when((debugExceptionBoundaryHit || debugBreakpointBoundaryHit || debugStepBoundaryHit) &&
             !preciseDrainBusyIn) {
          debugHaltState := DebugHaltState.RECOVER
        }
      }
    }
    val debugStepRejected = RegInit(False); debugStepRejected.simPublic()
    val debugHaltReasonReg = Reg(UInt(3 bits)) init DebugHaltReasonCode.NONE
    debugHaltReasonReg.simPublic()
    when(debugClearStickyIn) {
      debugStepRejected := False
      debugHaltReasonReg := DebugHaltReasonCode.NONE
    }
    when(debugStepRequestIn && ((debugHaltState =/= DebugHaltState.HALTED) || coreHalted)) {
      debugStepRejected := True
    }
    // A newly-entering fatal condition wins a same-cycle clear or debug recovery.
    when(coreHaltedIn && !coreHalted) {
      debugHaltReasonReg := DebugHaltReasonCode.FATAL
    }.elsewhen(debugRecoverEnter) {
      debugHaltReasonReg := Mux(debugExceptionBoundaryHit,
        U(DebugHaltReasonCode.EXCEPTION, 3 bits),
        Mux(debugBreakpointBoundaryHit,
          U(DebugHaltReasonCode.BREAKPOINT, 3 bits),
          Mux(debugHaltState === DebugHaltState.STEP_RUNNING,
            U(DebugHaltReasonCode.STEP, 3 bits),
            Mux(haltAfterDue || debugAutoHaltLatchedReg,
              U(DebugHaltReasonCode.HALT_AFTER, 3 bits),
              Mux(a7OddStopReq || pcRangeStopReq,
                U(DebugHaltReasonCode.A7_ODD, 3 bits),
                U(DebugHaltReasonCode.MANUAL, 3 bits))))))
    }
    GenerationFlags.simulation {
      assert(!((debugHalted || coreHalted) && (retire0 || retire1)),
        "RobPlugin: architectural retirement occurred during effective debug halt", FAILURE)
      assert(!((debugHaltState === DebugHaltState.STEP_RUNNING) && retire1 && p0.last),
        "RobPlugin: single-step dual-retired across a macro boundary", FAILURE)
      assert(!((debugHaltState === DebugHaltState.STEP_RUNNING) && (debugMacroCountInc > 1)),
        "RobPlugin: single-step completed more than one macro in one cycle", FAILURE)
      assert(!(debugBreakpointBoundaryHit && (retire0 || retire1)),
        "RobPlugin: breakpoint-marked macro retired on its pre-effect stop cycle", FAILURE)
    }
    when((debugHaltState === DebugHaltState.RUNNING) && haltAfterDue) {
      debugAutoHaltLatchedReg := True
    }
    // Clear provenance only when the FSM actually accepts resume. A command racing
    // RECOVER is not accepted by the state machine and must not erase the reason before
    // HALTED becomes observable.
    when(debugResumeRequestIn && (debugHaltState === DebugHaltState.HALTED) && !coreHalted) {
      debugAutoHaltLatchedReg := False
    }
    // ── Debug macro-retire counter (Stage 2, feature bit 22 macro_retire_count) ────
    // Counts COMPLETED macro-instructions, not micro-ops: increments once per retiring
    // entry whose payload.last is True. A 5-uop MOVEM retiring across 5 cycles increments
    // exactly once, on its final uop. This timing is load-bearing for halt-after: count N
    // means macro N is wholly committed and the following macro is still untouched.
    // Free-running: never cleared
    // except by CPU reset (it lives in RobPlugin's own logic, which IS the CPU-reset
    // domain -- unlike DebugCtrlPlugin's surviving debug-domain registers).
    when(debugMacroCountInc =/= 0) { debugMacroCountReg := debugMacroCountReg + debugMacroCountInc.resized }
    val haltAfterMacroCompleted = (retire0 && p0.last) || (retire1 && p1.last)
    when(!haltAfterArmedIn || haltAfterInvalidateIn) {
      haltAfterComparePending := False
    }.elsewhen(haltAfterArmRise || haltAfterMacroCompleted) {
      haltAfterComparePending := True
    }.elsewhen(haltAfterComparePending) {
      haltAfterComparePending := False
    }

    // ── Debug last-committed-PC (Stage 2, OFF_LAST_PC) ──────────────────────────
    // The PC of the most recently retired MACRO (not every uop -- a trailing uop of
    // a cracked macro shares its leading uop's PC by construction, per payload.pc's
    // own doc comment, so capturing on EVERY retire vs only on first=True retires is
    // observationally identical for this field; captured unconditionally on any
    // retire for simplicity, matching that equivalence).
    val debugLastPcReg = Reg(UInt(32 bits)) init 0
    debugLastPcReg.simPublic()
    when(retire1)      { debugLastPcReg := p1.pc }
      .elsewhen(retire0) { debugLastPcReg := p0.pc }

    // Task 9b: the capture itself. Slot = destination temp - T0 (the transfer's position
    // in the register list). Both retire slots are handled, and the two writes can never
    // target the same slot:
    //   - within one program the capture rows carry distinct temps (T0/T1/T2), and
    //   - two DIFFERENT programs' capture rows can never be h0/h1 in the same cycle,
    //     because the older program's terminal sysOp sits between them and a sysOp head
    //     retires through `sysRetire` alone (both `retire0` and `retire1` exclude
    //     `p.sysOp`), so it can never be paired away.
    def sysAuxSlotOf(arch: UInt): UInt = (arch - U(m68k040.decode.MicroOpAssembler.T0, 5 bits)).resize(2)
    when(retire0 && (p0.sysKind === sysAuxCapKind)) { sysAux(sysAuxSlotOf(p0.archRegId)) := sysValStore(h0) }
    when(retire1 && (p1.sysKind === sysAuxCapKind)) { sysAux(sysAuxSlotOf(p1.archRegId)) := sysValStore(h1) }

    val traceVec     = Vec(CommitTrace(), 2)
    val traceFireVec = Vec(Bool(), 2)

    // defaults
    for (k <- 0 until 2) {
      rc.commitPorts(k).valid := False
      rc.commitPorts(k).payload.assignDontCare()
      // FP/FPCC commit-field IDLE defaults. `driveCommit` below overrides them with the
      // retiring entry's real values; these defaults matter because assignDontCare above
      // leaves them 'bx in the netlist, while RenameStage's fpFree/fpRat consumers gate
      // only on commitPorts(k).valid && ...fpWrite -- and commitPorts(k).valid is True on
      // essentially every commit, not just FP ones. Concrete inert defaults keep the
      // non-FP commit paths (and the sysRead commit below) genuinely false in the
      // synthesizable netlist, not merely false-by-2-state-simulator-luck.
      rc.commitPorts(k).fpArchDst := U(0, 3 bits)
      rc.commitPorts(k).fpNew     := U(0, 4 bits)
      rc.commitPorts(k).fpOld     := U(0, 4 bits)
      rc.commitPorts(k).fpWrite   := False
      rc.commitPorts(k).fpccNew   := U(0, 4 bits)
      rc.commitPorts(k).fpccOld   := U(0, 4 bits)
      rc.commitPorts(k).fpccWrite := False
      traceFireVec(k) := False
      traceVec(k).assignDontCare()
    }

    def driveCommit(k: Int, p: RobPayload, commitPc: UInt): Unit = {
      rc.commitPorts(k).valid     := True
      rc.commitPorts(k).intArch   := p.archRegId
      rc.commitPorts(k).intNew    := p.intNew
      rc.commitPorts(k).intOld    := p.intOld
      rc.commitPorts(k).intWrite  := p.intWrite
      rc.commitPorts(k).nzvcNew   := p.nzvcNew
      rc.commitPorts(k).nzvcOld   := p.nzvcOld
      rc.commitPorts(k).nzvcWrite := p.nzvcWrite
      rc.commitPorts(k).xNew      := p.xNew
      rc.commitPorts(k).xOld      := p.xOld
      rc.commitPorts(k).xWrite    := p.xWrite
      rc.commitPorts(k).fpArchDst := p.fpArchDst
      rc.commitPorts(k).fpNew     := p.fpNew
      rc.commitPorts(k).fpOld     := p.fpOld
      rc.commitPorts(k).fpWrite   := p.fpWrite
      rc.commitPorts(k).fpccNew   := p.fpccNew
      rc.commitPorts(k).fpccOld   := p.fpccOld
      rc.commitPorts(k).fpccWrite := p.fpccWrite

      traceFireVec(k)          := True
      traceVec(k).fire         := True
      traceVec(k).pc           := commitPc
      traceVec(k).opword       := 0
      traceVec(k).archRegId    := p.archRegId
      traceVec(k).archRegWrite := 0
      traceVec(k).archRegValid := p.intWrite
      traceVec(k).ccr          := 0
      traceVec(k).ccrValid     := False
      traceVec(k).memAddr      := 0
      traceVec(k).memData      := 0
      traceVec(k).memWrite     := False
      traceVec(k).excTaken     := False
      traceVec(k).excVector    := 0
    }

    // Branch trace nextPc: a branch's commit pc is its RESOLVED nextPc (not the
    // predicted predNextPc). Branches are retireAlone, so retire1 can never be a
    // branch -> the slot-1 Mux is harmless.
    // ROB-fold Slice B: exactly TWO hoisted readAsync ports (each call allocates a
    // physical port) — nextPcRd0 serves BOTH commitPc0 and flushPcReg (same h0
    // address, same mux the old Reg-Vec read shared), nextPcRd1 serves commitPc1.
    val nextPcRd0 = nextPcMem.readAsync(h0)
    val nextPcRd1 = nextPcMem.readAsync(h1)
    val commitPc0 = Mux(p0.retireAlone, nextPcRd0, p0.predNextPc)
    val commitPc1 = Mux(p1.retireAlone, nextPcRd1, p1.predNextPc)
    val debugMacroRetirePc = Vec.fill(2)(Flow(UInt(32 bits)))
    debugMacroRetirePc(0).valid := retire0 && p0.last
    debugMacroRetirePc(0).payload := p0.pc
    debugMacroRetirePc(1).valid := retire1 && p1.last
    debugMacroRetirePc(1).payload := p1.pc
    // Sim-only taps (root-cause fix, post-Task-P2.5 lock-step investigation): the
    // IRQ lock-step harness's reactive interrupt-line poke needs to react to the
    // RAW retire event (not `commitObs`, which is ANOTHER RegNext cycle behind --
    // see `commitObs(0).fire := RegNext(retire0)` below) to have any chance of
    // landing in time for the immediate successor's OWN retire decision, which is
    // only ONE raw cycle behind h0's retire (head advances the very next cycle).
    retire0.simPublic(); retire1.simPublic()
    commitPc0.simPublic(); commitPc1.simPublic()
    when(retire0) { driveCommit(0, p0, commitPc0) }
    when(retire1) { driveCommit(1, p1, commitPc1) }
    // Keep a meaningful restart point even when the ROB drains completely before a
    // later debug request arrives. Slot 1 is younger and therefore wins when it closes
    // a second macro in the same cycle.
    when(retire0 && p0.last) { debugLivePcReg := commitPc0 }
    when(retire1 && p1.last) { debugLivePcReg := commitPc1 }
    // (A commit-time SYSTEM op READ commits its dst arch->pdst mapping at the trigger —
    // driven AFTER the exc unit is built, see `sysReadCommit` below, since the S=1
    // decision needs exc.ss.s.)

    val retiredThisCycle = (retire1 ? U(2) | (retire0 ? U(1) | U(0))).resize(count.getWidth)

    // ── Passive alloc interface (driven by DispatchPlugin) ──────────────────────
    // Plain-wire service convention: the ROB EXPOSES these via RobAllocService;
    // the sibling DispatchPlugin DRIVES allocFireSig/allocUopVec/allocSlot1Sig
    // (do NOT default-drive them here — that would double-drive). allocReadySig /
    // robId1Sig are driven here (ROB produces them).
    // allocReadySig: forward-declared here, WIRED below (task #124, Fable audit F4) as
    // a REGISTERED next-cycle predicate — mirrors the IssueQueue's readyReg pattern.
    // The live combinational `count <= depth-2` comparator was a top-2 fanout net
    // (5114 net) gating the whole rename->decode->fetch chain every cycle.
    val allocReadySig = Bool()
    val allocFireSig  = Bool()                 // DRIVEN by DispatchPlugin
    val allocUopVec   = Vec(RenamedUop(), 2)   // DRIVEN by DispatchPlugin
    val allocSlot1Sig = Bool()                 // DRIVEN by DispatchPlugin
    val robId1Sig     = UInt(robIdW bits); robId1Sig := tail + 1

    val alloc0 = allocFireSig
    val alloc1 = allocFireSig && allocSlot1Sig
    val allocThisCycle = (alloc1 ? U(2) | (alloc0 ? U(1) | U(0))).resize(count.getWidth)

    // ── Completion mark (alloc-reset has priority on a reused index) ────────────
    // MUST come BEFORE the alloc-reset writes below so that on a re-allocated index
    // a stale wrong-path completion (set here) is OVERRIDDEN by the alloc's
    // completes:=False (later `when` wins in SpinalHDL).
    for (c <- completion) when(c.valid) { completes(c.payload) := True }
    // Branch completion also marks complete + records {mispredict, nextPc}. Placed
    // with the other completion sets (BEFORE the alloc-reset) so alloc wins on a
    // re-used index (Task 1 alloc-priority).
    when(branchCompletion.valid) {
      completes(branchCompletion.payload.robId)       := True
      mispredictStore(branchCompletion.payload.robId) := branchCompletion.payload.mispredict
      // Single write port into nextPcMem (ROB-fold Slice B) — the ONLY writer, like
      // branchTrainMem below.
      nextPcMem.write(branchCompletion.payload.robId, branchCompletion.payload.nextPc)
      // Task #193: capture the resolved taken/redirect decision (trace-T0 gate).
      branchTakenStore(branchCompletion.payload.robId) := branchCompletion.payload.btbTaken
      // BTB-update capture (read at retire to drive the BTB write port).
      btbIsBranchStore(branchCompletion.payload.robId) := branchCompletion.payload.isBranch
      // gshare PHT-update capture (slice 3): the conditional-predicted bit (read at
      // retire to train the exact PHT entry the lookup read).
      phtValidStore(branchCompletion.payload.robId)    := branchCompletion.payload.phtValid
      // Single write port into branchTrainMem (task #129) — see the comment at its
      // declaration for why this is the ONLY writer.
      val btrainWr = BranchTrainPayload()
      btrainWr.pc       := branchCompletion.payload.btbPc
      btrainWr.taken    := branchCompletion.payload.btbTaken
      btrainWr.target   := branchCompletion.payload.btbTarget
      btrainWr.brType   := branchCompletion.payload.brType
      btrainWr.len      := branchCompletion.payload.btbLen
      btrainWr.phtIndex := branchCompletion.payload.phtIndex
      branchTrainMem.write(branchCompletion.payload.robId, btrainWr)
    }
    // CCR-value completion: record each completing instruction's NZVC/X VALUES per
    // entry (BEFORE the alloc-reset so a re-used index's alloc wins). One port/EU.
    for (c <- ccrCompletion) when(c.valid) {
      nzvcValStore(c.payload.robId) := c.payload.nzvc
      nzvcWrStore(c.payload.robId)  := c.payload.nzvcWrite
      xValStore(c.payload.robId)    := c.payload.x
      xWrStore(c.payload.robId)     := c.payload.xWrite
      // Capture the EU writeback VALUE for a commit-time system op's write direction
      // (the op µop is a MOVE -> result = the source register). The ROB ignores it for
      // non-sysOps. (Placed BEFORE alloc-reset so a re-used index's alloc wins.)
      sysValStore(c.payload.robId)  := c.payload.result
      // The value has landed -> a write-direction sysOp may now trigger (this fires the
      // cycle AFTER the EU's `completion` set `completes`, closing the value-vs-trigger
      // race). An intWrite-only capture qualifies (a sysOp µop always writes via its EU).
      sysValRdyStore(c.payload.robId) := True
    }
    // ══════════════════════════════════════════════════════════════════════════════
    // ROB-fold Slice C — the FOUR fault-completion ports, age-arbitrated into ONE
    // write bus over a single-write-port Mem.
    // ══════════════════════════════════════════════════════════════════════════════
    //
    // WAS: four independent per-entry write ports (ls / sq / eu / fp), each writing
    // its own copy of the same logical record {addr, vector, size, wr, sup, atc,
    // instr} into seven separate `Vec.fill(64)(Reg)` arrays, resolved by SpinalHDL's
    // last-assign priority. 2944 FF, six write ports each, a 64:1 read mux per bit —
    // and, via `faultAddrStore`'s 64x32 per-bit data muxes, 2019 of the 2083 loads on
    // the routed netlist's worst fanout net (the reason "LS/ROB Lever C", the kept
    // per-entry write-select hoist that used to live here, existed at all).
    //
    // NOW: one arbitrated write into `faultDynMem` (1W/1R-async, so
    // MultiPortWritesSymplifier's `writes.size <= 1` early return leaves it a plain
    // distributed RAM — no LVT/XOR banks, no in-RAM collision arbitration) plus the
    // 1-bit `faultDynStore` gate. The Lever C hoist is DELETED, not ported: the data
    // muxes whose fanout it was working around no longer exist, and the winner's
    // robId now decodes to exactly 2 one-bit Reg-Vecs + the RAM's own address pins
    // (~128 loads, down from ~2084), which is the same fix by construction.
    //
    // ── ARBITRATION ──────────────────────────────────────────────────────────────
    // Age of a valid port = `robId - head`, evaluated in robIdW(=6)-bit UInt
    // arithmetic, i.e. EXACTLY (robId - head) mod 64. It is a true, total, unambiguous
    // age order across the 0/63 wrap because every in-flight entry lies in
    // [head, head+count) and `count <= depth-2 = 62` is enforced by allocReadySig —
    // so every port's age is in [0, 62], distinct entries have distinct ages, and the
    // minimum age is the OLDEST entry. Naive `robId < robId` comparison would be wrong
    // here; this is not.
    //
    // The winner is the minimum age; TIES (which can only mean the same robId) go to
    // the LATER port in the ls -> sq -> eu -> fp fold order, which is precisely the
    // last-assign priority the four `when` blocks used to have (alloc > fp > eu > sq >
    // ls). Alloc's priority over all four is preserved separately, by the alloc ports
    // clearing `faultDynStore` AFTER this block (see `when(alloc0)` below).
    //
    // ── CORRECTNESS PROOF: dropping the younger of two same-cycle faults is sound ──
    //
    // CLAIM. If two or more of the four ports fire in the same cycle at DIFFERENT
    // robIds, keeping only the oldest one's write — and silently dropping the others,
    // including their `faultedStore` marks — is architecturally unobservable.
    //
    // Let W be the winner (oldest) and L any loser (strictly younger, since ages are
    // distinct for distinct entries, per the age argument above).
    //
    // (1) L's fault record can only ever be OBSERVED through a read at the ROB head.
    //     Every read of this family is at h0 (`exceptionVector` / `exceptionFaultAddr`
    //     / `exceptionFault{Wr,Size,Sup,Instr,Atc}` and the ExceptionUnit's
    //     entryFault* arguments — all `...Store(h0)`), and `faultedStore` is read at
    //     h0 and at h1 (retire1's gate) and nowhere else. There is no read at an
    //     arbitrary robId anywhere in the design.
    //
    // (2) For L to reach h0 or h1, `head` must advance past W. `head` advances ONLY
    //     via retire0/retire1 (the single `head := head + Mux(retire1,2,Mux(retire0,1,0))`
    //     statement) — a flush does NOT advance it (`when(flushing){ tail := head;
    //     count := 0 }`, pointer-only).
    //
    // (3) W cannot retire. `retire0` is gated on `!faultedStore(h0)` and `retire1` on
    //     `!faultedStore(h1)`; W IS marked faulted (it won the arbitration, so its
    //     mark landed). A faulted head therefore has exactly one exit:
    //     `faultRetire = headReady && (faultedStore(h0) || privViolation) && excIdle`.
    //
    // (4) `faultRetire` drives `exceptionPending` -> the exception FSM -> `excSquash`
    //     / `exc.redirectValid` -> `doFlushReg` -> `flushing`, and `when(flushing){
    //     tail := head; count := 0 }` squashes the ENTIRE ROB — not merely everything
    //     younger. L is destroyed without ever having been at the head.
    //
    // (5) The interval between the two completions and W's retirement is irrelevant:
    //     the invariant is a program-order property of the ring (L is behind W and
    //     the ring only ever drains in order), not a timing coincidence. Nothing can
    //     reorder them, and nothing else can retire W out from under the fault —
    //     `normalIrqGate` and `traceNormalGate` are both gated on `!faultedStore(h0)`,
    //     and `privOnly` only ADDS a vector-8 path for a NON-faulted head.
    //
    // (6) The only other way W leaves without delivering is an UNRELATED earlier flush
    //     (an older mispredict, a debug recover). That flush is also a whole-ROB squash
    //     by (4)'s statement, so it takes L with it. Symmetric, still unobservable.
    //
    // Therefore L's mark is unreachable in every case. QED.
    //
    // Two deliberate design choices follow from the proof:
    //   * The loser's `faultedStore` mark is dropped TOO (not kept). Keeping it while
    //     dropping its record would leave L in a self-inconsistent "faulted, but the
    //     fault payload is the alloc-time one (usually vector 0)" state — strictly
    //     worse than not marking it, if the proof were ever violated by a future
    //     change. Gate and Mem row are always written by the same statement, so
    //     `faultDynStore(i)` True ALWAYS implies row i is fresh.
    //   * A same-robId, same-cycle collision resolves to the WINNING PORT'S WHOLE
    //     record, where the old code produced a per-field HYBRID (e.g. an ls+eu
    //     collision took eu's vector/addr but kept ls's size/wr/sup/atc, because eu
    //     never assigned those fields). That case is unreachable by construction — an
    //     entry is issued to exactly one EU pipeline, and ls (translate/access) and sq
    //     (at-head drain) are sequential PHASES of one store, never simultaneous — and
    //     the hybrid was meaningless anyway. Priority is preserved regardless.
    //
    // ── WHAT EACH PORT DRIVES ON THE UNIFIED BUS ─────────────────────────────────
    // ls / sq carry a real {addr, size, wr, sup, atc} and a hard vector 2. eu / fp
    // used to write only {faulted, vector, addr, instr}, leaving {size, wr, sup, atc}
    // holding whatever the slot already had; they now drive the unified bus with the
    // same values that yielded, byte for byte:
    //   wr  = False, sup = False  — exactly what BOTH alloc ports wrote, so identical.
    //   atc = True                — `MicroOpAssembler` sets `u.faultAtc := True` for
    //                               every µop except the I-fetch-fault path, and the
    //                               old Vec's RegInit was True, so this is the value
    //                               an eu/fp fault always read.
    //   size = faultAllocSize (LONG) — the old array had NO alloc write (Slice D's
    //                               bug), so an eu/fp fault read a STALE size from a
    //                               previous occupant. LONG is the declared RegInit
    //                               and the pre-task-189 SSW SIZE=00 behaviour.
    // All four of those fields are architecturally READ only through the format-$7
    // SSW, which ExceptionUnit builds only for `is7 = !interrupt && vector === 2`
    // (`curSsw` is stacked at frame step 6, a $7-only step). eu vectors are 3/5/6/7,
    // fp vectors are 49..54 — never 2 — so none of these are observable for eu/fp at
    // all. `instr` is not on the bus: all four ports wrote a constant False, which the
    // read site re-derives as `!faultDynStore(h0)`.
    //
    // Textual position is still load-bearing, for the same reason as before: this
    // block must stay BEFORE `when(alloc0)`/`when(alloc1)` so alloc's `faultedStore`/
    // `faultDynStore` writes keep winning on a re-used index.
    case class RobFaultPort() extends Bundle {
      val valid = Bool()
      val robId = UInt(robIdW bits)
      val age   = UInt(robIdW bits)
      val d     = RobFaultDyn()
    }
    def faultPortOf(v: Bool, id: UInt, addr: UInt, vec: UInt,
                    size: UInt, wr: Bool, sup: Bool, atc: Bool): RobFaultPort = {
      val p = RobFaultPort()
      p.valid  := v
      p.robId  := id
      p.age    := id - head   // (robId - head) mod 64 — see the ARBITRATION note above
      p.d.addr := addr; p.d.vec := vec; p.d.size := size
      p.d.wr   := wr;   p.d.sup := sup; p.d.atc  := atc
      p
    }
    // Fold order == the old textual order == the old last-assign priority.
    val faultPorts = Seq(
      faultPortOf(lsFaultCompletion.valid, lsFaultCompletion.payload.robId,
                  lsFaultCompletion.payload.faultAddr, U(2, 8 bits),
                  lsFaultCompletion.payload.sizeBits, lsFaultCompletion.payload.write,
                  lsFaultCompletion.payload.supervisor, lsFaultCompletion.payload.atc),
      faultPortOf(sqFaultCompletion.valid, sqFaultCompletion.payload.robId,
                  sqFaultCompletion.payload.faultAddr, U(2, 8 bits),
                  sqFaultCompletion.payload.sizeBits, sqFaultCompletion.payload.write,
                  sqFaultCompletion.payload.supervisor, sqFaultCompletion.payload.atc),
      faultPortOf(euFaultCompletion.valid, euFaultCompletion.payload.robId,
                  euFaultCompletion.payload.faultAddr, euFaultCompletion.payload.vector,
                  faultAllocSize, faultAllocWr, faultAllocSup, True),
      faultPortOf(fpFaultCompletion.valid, fpFaultCompletion.payload.robId,
                  fpFaultCompletion.payload.faultAddr, fpFaultCompletion.payload.vector,
                  faultAllocSize, faultAllocWr, faultAllocSup, True))
    // `b` (the later, higher-priority port) wins on an age TIE — see ARBITRATION.
    val faultWin = faultPorts.reduceLeft { (a, b) =>
      Mux(b.valid && (!a.valid || (b.age <= a.age)), b, a)
    }
    faultWin.valid.setName("faultWinValid"); faultWin.robId.setName("faultWinRobId")
    faultWin.valid.simPublic(); faultWin.robId.simPublic()
    faultDynMem.write(faultWin.robId, faultWin.d, faultWin.valid)
    when(faultWin.valid) {
      faultedStore(faultWin.robId)  := True
      faultDynStore(faultWin.robId) := True
    }
    when(alloc0) {
      payload.write(tail, payloadFrom(allocUopVec(0)))
      completes(tail)       := False
      mispredictStore(tail) := False
      branchTakenStore(tail) := False
      btbIsBranchStore(tail) := False
      phtValidStore(tail)   := False
      faultedStore(tail)  := allocUopVec(0).faulted
      // ROB-fold Slice C: the alloc-time fault RECORD (vector / sswInstr / faultAtc,
      // plus `pc` as the fault EA — Slice A-2 proved `RenamedUop.faultAddr` was
      // bit-identical to `.pc` at every decode write site, see DecodedUop.scala's
      // faultAddr-deletion comment) is written by `payloadFrom` above. Clearing
      // `faultDynStore` here is what makes alloc WIN over a same-cycle completion on
      // a re-used index — the exact priority the old six-writer Vecs got from
      // SpinalHDL's last-assign rule, and the reason this block must stay textually
      // AFTER the fault-arbitration block above. It also subsumes task #211's
      // explicit per-alloc ATC write and Slice D's missing per-alloc SIZE write: a
      // re-used index can no longer inherit ANY completion-time field from a previous
      // occupant, because the gate that would let it be read is cleared right here.
      faultDynStore(tail) := False
      // (Task 11 fpuUnimp/fpuCmd: written by payloadFrom above — ROB-fold task #249.)
      nzvcWrStore(tail) := False; xWrStore(tail) := False
      sysValRdyStore(tail)  := False
      // (isRte/first/needsSup/pc/sysOp/sysKind/sysReadDir/sysRc are written by
      //  payloadFrom above — LUT-reduction B1.)
      GenerationFlags.simulation { pcStore(tail) := allocUopVec(0).pc }
    }
    when(alloc1) {
      payload.write(tail + 1, payloadFrom(allocUopVec(1)))
      completes(tail + 1)       := False
      mispredictStore(tail + 1) := False
      branchTakenStore(tail + 1) := False
      btbIsBranchStore(tail + 1) := False
      phtValidStore(tail + 1)   := False
      faultedStore(tail + 1)  := allocUopVec(1).faulted
      // ROB-fold Slice C: same treatment as the alloc0 port above.
      faultDynStore(tail + 1) := False
      // (Task 11 fpuUnimp/fpuCmd: written by payloadFrom above — ROB-fold task #249.)
      nzvcWrStore(tail + 1) := False; xWrStore(tail + 1) := False
      sysValRdyStore(tail + 1)  := False
      GenerationFlags.simulation { pcStore(tail + 1) := allocUopVec(1).pc }
    }
    // ── Slice C tripwire: drive the sim-only shadow of the OLD structure ─────────
    // Deliberately written HERE, after both alloc ports, in the OLD textual order —
    // ls, sq, eu, fp, alloc0, alloc1 — so SpinalHDL's last-assign priority reproduces
    // the deleted code's resolution exactly (alloc1 > alloc0 > fp > eu > sq > ls),
    // including the per-FIELD retain behaviour for eu/fp (which never wrote
    // size/wr/sup/atc). See the shadow declarations above for the one deliberate
    // deviation (fsSize gets the alloc write the old array was missing).
    GenerationFlags.simulation {
      when(lsFaultCompletion.valid) {
        val i = lsFaultCompletion.payload.robId
        fsFaulted(i) := True;  fsVec(i)  := U(2, 8 bits)
        fsAddr(i)    := lsFaultCompletion.payload.faultAddr
        fsWr(i)      := lsFaultCompletion.payload.write
        fsSize(i)    := lsFaultCompletion.payload.sizeBits
        fsSup(i)     := lsFaultCompletion.payload.supervisor
        fsAtc(i)     := lsFaultCompletion.payload.atc
        fsInstr(i)   := False
      }
      when(sqFaultCompletion.valid) {
        val i = sqFaultCompletion.payload.robId
        fsFaulted(i) := True;  fsVec(i)  := U(2, 8 bits)
        fsAddr(i)    := sqFaultCompletion.payload.faultAddr
        fsWr(i)      := sqFaultCompletion.payload.write
        fsSize(i)    := sqFaultCompletion.payload.sizeBits
        fsSup(i)     := sqFaultCompletion.payload.supervisor
        fsAtc(i)     := sqFaultCompletion.payload.atc
        fsInstr(i)   := False
      }
      when(euFaultCompletion.valid) {
        val i = euFaultCompletion.payload.robId
        fsFaulted(i) := True
        fsVec(i)     := euFaultCompletion.payload.vector
        fsInstr(i)   := False
        fsAddr(i)    := euFaultCompletion.payload.faultAddr
      }
      when(fpFaultCompletion.valid) {
        val i = fpFaultCompletion.payload.robId
        fsFaulted(i) := True
        fsVec(i)     := fpFaultCompletion.payload.vector
        fsInstr(i)   := False
        fsAddr(i)    := fpFaultCompletion.payload.faultAddr
      }
      when(alloc0) {
        fsFaulted(tail) := allocUopVec(0).faulted
        fsVec(tail)     := allocUopVec(0).faultVector
        fsWr(tail)      := False; fsSup(tail) := False
        fsAddr(tail)    := allocUopVec(0).pc
        fsInstr(tail)   := allocUopVec(0).sswInstr
        fsAtc(tail)     := allocUopVec(0).faultAtc
        fsSize(tail)    := faultAllocSize   // <- Slice D's missing alloc write
      }
      when(alloc1) {
        fsFaulted(tail + 1) := allocUopVec(1).faulted
        fsVec(tail + 1)     := allocUopVec(1).faultVector
        fsWr(tail + 1)      := False; fsSup(tail + 1) := False
        fsAddr(tail + 1)    := allocUopVec(1).pc
        fsInstr(tail + 1)   := allocUopVec(1).sswInstr
        fsAtc(tail + 1)     := allocUopVec(1).faultAtc
        fsSize(tail + 1)    := faultAllocSize
      }
    }
    when(allocFireSig) {
      tail := tail + Mux(allocSlot1Sig, U(2, robIdW bits), U(1, robIdW bits))
    }

    // ── Retire-side state update (head advance; validity is count-derived) ──────
    head := head + Mux(retire1, U(2, robIdW bits), Mux(retire0, U(1, robIdW bits), U(0, robIdW bits)))

    // ── count update (alloc + retire) ──────────────────────────────────────────
    val countNext = count + allocThisCycle - retiredThisCycle
    count := countNext
    // allocReadySig: registered off countNext (no extra adder — reuses the SAME
    // expression that drives count itself), so only the COMPARE result is a register
    // instead of a live combinational `count <= depth-2` read every cycle. init True
    // matches count=0 at reset. Overridden to True on a flush below (mirrors the
    // IssueQueue readyReg pattern: count resets to 0 there too, always <= depth-2).
    allocReadySig := RegNext(countNext <= (depth - 2)) init True

    // ── Commit-time mispredict redirect (REGISTERED pulse) ──────────────────────
    // When the retiring head is a mispredicting branch (retireAlone), register the
    // flush for next cycle. doFlushReg is the ONLY flush signal that fans out, and
    // it drives only pointer/reg resets (FMax: no combinational execute->flush path).
    // The exception FSM's final redirect (vector target / RTE restored PC) is ORed
    // into it below (after the exc unit is built).
    //
    // ── INVESTIGATED AND REJECTED: a "registered early-flush" fast path ─────────
    // (2026-08-31, boot-investigation follow-up, see the companion SoC repo's
    // docs/BUG_calibration_word_misplaced_0d00.md Part 61). The obvious textbook
    // fix for "flush latency scales with how long the branch takes to reach head"
    // is to register `branchCompletion`'s {robId, mispredict, nextPc} at EU
    // resolution (S1) and fire a NARROW squash — tail := robId+1, preserving
    // [head, robId] so older still-in-flight work survives — one cycle later,
    // instead of waiting for full in-order retire. This was investigated in depth
    // and found UNSAFE to bolt onto this codebase's rollback machinery as scoped:
    //
    // `RatTable.io.rollback` / `Freelist.io.flush` (RenameStage.scala) are each a
    // SINGLE GLOBAL "restore to the committed shadow" operation — `location := 0`
    // reverting EVERY arch register to `commReg`, `head := commHead` returning
    // EVERY in-flight speculative pop — with no per-branch or per-robId
    // granularity. There is no existing hook to roll back "only what's younger
    // than robId X" while leaving [head, X) untouched. Firing today's `flushing`
    // (doFlushReg) EARLY, before the branch reaches head, therefore ALSO discards
    // every not-yet-retired OLDER entry between the current head and the branch —
    // and if `flushPc` is set to the branch's resolved target (the natural choice
    // for a "narrow" squash), fetch resumes PAST those older instructions without
    // ever re-executing them: a silent, permanent loss of their architectural
    // effects. Redirecting instead to the current committed PC avoids that
    // corruption but degenerates into "eagerly squash everything in flight the
    // instant ANY branch anywhere resolves mispredicted" — safe, but strictly
    // MORE aggressive than today's design (discards legitimate older in-flight
    // work too) and does not deliver the intended narrow/fast recovery at all.
    // A real fix needs actual per-branch (or small-N) RAT/Freelist checkpoint+
    // restore, mirroring RasPlugin's checkpointSave/checkpointRestore (Ras.scala,
    // landed 2026-08-28 from this SAME investigation) — but RAS's own doc comment
    // explicitly notes a precise per-branch version needs "real ROB-side plumbing,
    // out of scope" for that narrower fix; the INT/NZVC/X side is the same size
    // of lift, properly scoped as its own follow-up, not a 1-cycle timing tweak.
    // Today's retire-gated `branchRedirect` below is CORRECT (re-verified via
    // deep_mispredict/unstable_branch/mispredict/adv_a7_spec_flush/
    // adv_store_squash_mispredict/adv_flush_restart_store, all green) precisely
    // BECAUSE it only fires once the branch IS head — at that instant "restore to
    // committed" and "restore to just-after-the-branch" are the same state by
    // construction. Do not re-derive this from scratch; read Part 61 first.
    val branchRedirect = retire0 && p0.retireAlone && mispredictStore(h0)
    branchRedirect.simPublic() // 2026-08-27 boot-investigation debug tap

    // ── branchTrainMem retire-time read (task #129, area) ───────────────────────
    // A single readSync port, enabled on every retire0 (regardless of which consumer
    // below needs it — cheaper than two separately-gated read ports, and harmless when
    // neither gate fires since the *Flow.valid stays False that cycle). This read IS the
    // register: its output lands the cycle AFTER retire0, exactly matching the old
    // RegNext(*Comb.payload) timing, so BtbPlugin/GsharePlugin see no timing change.
    val branchTrainRd = branchTrainMem.readSync(h0, retire0)
    btbUpdateFlow.payload.pc     := branchTrainRd.pc
    btbUpdateFlow.payload.taken  := branchTrainRd.taken
    btbUpdateFlow.payload.target := branchTrainRd.target
    btbUpdateFlow.payload.brType := branchTrainRd.brType
    btbUpdateFlow.payload.len    := branchTrainRd.len
    gshareUpdateFlow.payload.index := branchTrainRd.phtIndex
    gshareUpdateFlow.payload.taken := branchTrainRd.taken
    val debugBranchRetire = Flow(DebugBranchEvent())
    debugBranchRetire.valid.simPublic()
    debugBranchRetire.payload.pc.simPublic()
    debugBranchRetire.payload.nextPc.simPublic()
    debugBranchRetire.payload.taken.simPublic()
    debugBranchRetire.payload.mispredicted.simPublic()
    debugBranchRetire.payload.branchType.simPublic()
    val debugBranchMispredict = RegNextWhen(mispredictStore(h0), btbUpdateValidComb) init False
    val debugBranchFallthrough = (btbUpdateFlow.payload.pc +
      (btbUpdateFlow.payload.len.resize(32) << 1)).resized
    debugBranchRetire.valid := btbUpdateFlow.valid
    debugBranchRetire.payload.pc := btbUpdateFlow.payload.pc
    debugBranchRetire.payload.nextPc := Mux(btbUpdateFlow.payload.taken,
      btbUpdateFlow.payload.target, debugBranchFallthrough)
    debugBranchRetire.payload.taken := btbUpdateFlow.payload.taken
    debugBranchRetire.payload.mispredicted := debugBranchMispredict
    debugBranchRetire.payload.branchType := btbUpdateFlow.payload.brType

    // ── Retire-time BTB update (fetch-time predictor, slice 1) ──────────────────
    // When a BTB-eligible branch retires at the head (retire0 — branches are
    // retireAlone, so they always retire in slot 0), drive the BtbPlugin write port
    // with its captured PC/target/brType + resolved direction. ONLY at retire => no
    // wrong-path pollution (a squashed wrong-path branch never reaches retire).
    when(retire0 && btbIsBranchStore(h0)) {
      btbUpdateValidComb := True
    }
    // ── Retire-time gshare PHT update (slice 3) ─────────────────────────────────
    // When a CONDITIONAL gshare-predicted branch retires at the head (retire0 — branches
    // are retireAlone → always slot 0), train pht[carried-index] toward its RESOLVED
    // direction (branchTrainRd.taken == actualTaken). ONLY at retire ⇒ no wrong-path
    // pollution. The carried fetch-time index (branchTrainRd.phtIndex) — not a
    // retire-time recompute — is mandatory (the speculative GHR has shifted by retire).
    when(retire0 && phtValidStore(h0)) {
      gshareUpdateValidComb := True
    }

    // ── Precise-fault exception-pending (combinational at faulted retire) ───────
    // When the head is a faulted µop ready to retire, signal an exception with its
    // vector + the FAULTING instruction's PC. The commit-side exception FSM consumes
    // this (squashes, stacks the frame, vectors). The faulted entry does NOT commit
    // (retire0 is gated `!p0.faulted`).
    // Forward-declared sysOp signals (Track D): the priv decision needs exc.ss.s, built
    // below, so default idle here and OVERRIDE after the exc unit. sysTriggerSig drives
    // the system-op apply FSM (S=1); sysPrivFault is the S=0 privilege-violation trap.
    val sysPrivFault  = Bool(); sysPrivFault.allowOverride;  sysPrivFault  := False; sysPrivFault.simPublic()
    val sysTriggerSig = Bool(); sysTriggerSig.allowOverride; sysTriggerSig := False; sysTriggerSig.simPublic()
    // TWO privilege-violation sources, both deliver vector 8 (format-$0) + the faulting
    // instr's PC (restartable): privOnly = Track C MOVE-from-SR head in user mode (NOT
    // itself statically faulted — a head both faulted AND priv keeps its static fault);
    // sysPrivFault = Track D commit-time system op at S=0. Either ORs into exceptionPending
    // (faultRetire already folds privViolation; the extra ||privOnly is safe-redundant).
    val privOnly = privViolation && !faultedStore(h0)
    val privVec8 = privOnly || sysPrivFault
    val exceptionPending = Bool();    exceptionPending := faultRetire || sysPrivFault || privOnly; exceptionPending.simPublic()
    // ── ROB-fold Slice C: the single h0 read of the per-entry fault record ───────
    // `faultDynStore(h0)` selects between the COMPLETION-time record (faultDynMem,
    // written by the age-arbitrated ls/sq/eu/fp bus) and the ALLOC-time one
    // (`payload`, written by alloc0/alloc1). Reading the Mem is safe despite it
    // having no `init`: the gate is RegInit(False), cleared by both alloc ports, and
    // set ONLY by the very statement that writes the row — so `faultDyn0` True always
    // implies row h0 is fresh. Identical discipline to branchTrainMem's
    // btbIsBranchStore/phtValidStore gates and nextPcMem's retireAlone/mispredictStore
    // gates. The alloc-side halves that used to be CONSTANTS in the old Vec writes
    // (`wr`/`sup` := False, `size` := LONG) are re-derived here rather than stored.
    //
    // This is the ONLY read of the family. Both former read sites (`exception*` here
    // and the ExceptionUnit's `entryFault*` arguments below) consume these same
    // signals, so the fold costs one read port, not two.
    val faultDyn0  = faultDynStore(h0); faultDyn0.simPublic()
    val faultRec0  = faultDynMem.readAsync(h0)
    val faultVec0  = UInt(8 bits);  faultVec0  := Mux(faultDyn0, faultRec0.vec,  p0.faultVector)
    val faultAddr0 = UInt(32 bits); faultAddr0 := Mux(faultDyn0, faultRec0.addr, p0.pc)
    val faultSize0 = UInt(2 bits);  faultSize0 := Mux(faultDyn0, faultRec0.size, faultAllocSize)
    val faultWr0   = Bool();        faultWr0   := Mux(faultDyn0, faultRec0.wr,   faultAllocWr)
    val faultSup0  = Bool();        faultSup0  := Mux(faultDyn0, faultRec0.sup,  faultAllocSup)
    val faultAtc0  = Bool();        faultAtc0  := Mux(faultDyn0, faultRec0.atc,  p0.faultAtc)
    // All four completion ports wrote a constant False into the old faultInstrStore
    // (a DATA/execute fault is never an instruction fetch), so the completion-side
    // value is the gate itself.
    val faultInstr0 = Bool();       faultInstr0 := !faultDyn0 && p0.sswInstr
    val exceptionVector  = UInt(8 bits);  exceptionVector := Mux(privVec8, U(8, 8 bits),  faultVec0); exceptionVector.simPublic()
    // Fault PC (ROB-fold Slice A): re-derive the old faultPcStore content from the
    // payload Mem — Mux(faultUsesNextPc, predNextPc, pc), the exact expression the
    // deleted array captured at alloc (same sources, same cycle, same address).
    val exceptionPc      = UInt(32 bits); exceptionPc     := Mux(privVec8, p0.pc,    Mux(p0.faultUsesNextPc, p0.predNextPc, p0.pc));  exceptionPc.simPublic()
    // Access-fault (vector 2) extras for the format-$7 frame: the faulting VA + the
    // SSW access attrs {write, sizeBits, supervisor}. Meaningful only when the head's
    // vector is 2; the exception FSM selects the $7 path on the vector.
    val exceptionFaultAddr = UInt(32 bits); exceptionFaultAddr := faultAddr0;  exceptionFaultAddr.simPublic()
    val exceptionFaultWr   = Bool();        exceptionFaultWr   := faultWr0;    exceptionFaultWr.simPublic()
    val exceptionFaultSize = UInt(2 bits);  exceptionFaultSize := faultSize0;  exceptionFaultSize.simPublic()
    val exceptionFaultSup  = Bool();        exceptionFaultSup  := faultSup0;   exceptionFaultSup.simPublic()
    val exceptionFaultInstr= Bool();        exceptionFaultInstr:= faultInstr0; exceptionFaultInstr.simPublic()
    // Task #189: ATC bit source (True=MMU/ATC fault, False=plain bus error) — was
    // previously hardcoded True unconditionally in ExceptionUnit (see its SSW-
    // builder comment); now genuinely per-fault.
    val exceptionFaultAtc  = Bool();        exceptionFaultAtc  := faultAtc0;   exceptionFaultAtc.simPublic()

    // ── Slice C tripwire: the arbitrated bus must be INDISTINGUISHABLE at the head ─
    // Compare the new derivation against the sim-only shadow of the old seven-array
    // structure (declared/driven above) at exactly the points the values are read.
    //
    // The `faultedStore` check is the sharp one: a DROPPED younger fault is the only
    // way the two can ever disagree, so this assert firing is precisely the event the
    // correctness proof says is impossible. It is checked on `headReady` (which
    // implies count>0, i.e. h0 really is an allocated entry) rather than only on
    // faultRetire, so a wrongly-UNmarked head is caught on the cycle it would have
    // retired normally instead of faulting.
    //
    // {size, wr, sup, atc, instr} are gated on `exceptionVector === 2`: that is the
    // only vector for which ExceptionUnit builds a format-$7 frame (`is7 =
    // !interrupt && vector === 2`) and therefore the only place these five are
    // architecturally read. eu/fp faults (vectors 3/5/6/7 and 49..54) intentionally
    // drive the unified bus with the constants documented at the arbitration block
    // instead of the old array's retained values, and the shadow deliberately keeps
    // the old retain behaviour — so an ungated compare would flag a difference that
    // provably cannot reach an architectural frame. Under vector 2 the fault is
    // always ls/sq-sourced (which write every field) or alloc-sourced (which now
    // writes every field too), and the two must agree exactly.
    GenerationFlags.simulation {
      when(headReady) {
        assert(faultedStore(h0) === fsFaulted(h0),
          "RobPlugin Slice C: arbitrated fault mark at h0 disagrees with the " +
          "unarbitrated shadow -- a dropped younger fault reached the ROB head", FAILURE)
      }
      when(faultRetire) {
        assert(faultVec0  === fsVec(h0),
          "RobPlugin Slice C: fault vector at h0 drifted from the old-structure shadow", FAILURE)
        assert(faultAddr0 === fsAddr(h0),
          "RobPlugin Slice C: fault address at h0 drifted from the old-structure shadow", FAILURE)
        when(exceptionVector === 2) {
          assert(faultSize0  === fsSize(h0),
            "RobPlugin Slice C: $7 SSW size at h0 drifted from the old-structure shadow", FAILURE)
          assert(faultWr0    === fsWr(h0),
            "RobPlugin Slice C: $7 SSW write bit at h0 drifted from the old-structure shadow", FAILURE)
          assert(faultSup0   === fsSup(h0),
            "RobPlugin Slice C: $7 SSW supervisor bit at h0 drifted from the old-structure shadow", FAILURE)
          assert(faultAtc0   === fsAtc(h0),
            "RobPlugin Slice C: $7 SSW ATC bit at h0 drifted from the old-structure shadow", FAILURE)
          assert(faultInstr0 === fsInstr(h0),
            "RobPlugin Slice C: $7 SSW instr bit at h0 drifted from the old-structure shadow", FAILURE)
        }
      }
    }

    // ── Committed CCR (X N Z V C, bits 4..0) — VALUE, folded at retire ───────────
    // The ROB has no CCR value on its payload (only phys IDs), so the EU writeback
    // VALUES are recorded per-entry at completion (ccrCompletion, like the branch
    // completion's {mispredict,nextPc}) and folded into a committed-CCR register at
    // retire. Used ONLY by the (rare, serializing) exception FSM for the stacked
    // frame's SR low byte (byte-for-byte vs Musashi). Default 0 if unwired (the
    // standalone ROB/exception unit tests don't drive ccrCompletion and don't check
    // the stacked CCR; the full-core wiring drives it from the EUs).
    val committedCcr = RegInit(U(0, 5 bits)); committedCcr.simPublic()
    val ccrAfter0 = UInt(5 bits); ccrAfter0 := committedCcr
    when(retire0 && nzvcWrStore(h0)) { ccrAfter0(3 downto 0) := nzvcValStore(h0) }
    when(retire0 && xWrStore(h0))    { ccrAfter0(4)          := xValStore(h0) }
    val ccrAfter1 = UInt(5 bits); ccrAfter1 := ccrAfter0
    when(retire1 && nzvcWrStore(h1)) { ccrAfter1(3 downto 0) := nzvcValStore(h1) }
    when(retire1 && xWrStore(h1))    { ccrAfter1(4)          := xValStore(h1) }
    // SAME-CYCLE FLAG BYPASS (2026-09-09, the "interrupt after a MOVE to memory restores the
    // handler's flags" defect). A store that completes through the StoreQueue's precise /
    // deferred path is marked complete by `sqCompletionPort` COMBINATIONALLY (LsEuPlugin's
    // apply arm) while its NZVC rides `compValid`, a REGISTER, one cycle later -- so the store
    // can retire in the very cycle its `ccrCompletion` arrives, when `nzvcWrStore(h)` is still
    // the alloc-time False and the fold above sees nothing. The flags PRF is unaffected (the
    // EU writes it directly); only this shadow -- the CCR the next exception STACKS and RTE
    // therefore restores -- silently kept the pre-store value (lock-step: a `move.b %d0,(%a1)`
    // followed by an IRQ RTE'd with the handler's CCR; measured directly by the
    // `committedCcr shadow` test: the wbObs for the head fires in the retire cycle). Fold the
    // live port when it names the retiring head, same-cycle -- zero latency change.
    for (c <- ccrCompletion) {
      when(retire0 && c.valid && c.payload.robId === h0) {
        when(c.payload.nzvcWrite) { ccrAfter0(3 downto 0) := c.payload.nzvc }
        when(c.payload.xWrite)    { ccrAfter0(4)          := c.payload.x }
      }
      when(retire1 && c.valid && c.payload.robId === h1) {
        when(c.payload.nzvcWrite) { ccrAfter1(3 downto 0) := c.payload.nzvc }
        when(c.payload.xWrite)    { ccrAfter1(4)          := c.payload.x }
      }
    }
    committedCcr := ccrAfter1
    // (MOVE-to-SR's absolute full-CCR write to committedCcr is applied AFTER the exc
    // unit is built — see `exc.obsSetCcr5Valid` override below.)

    // CCR the exception stacks: committedCcr PLUS the FAULTING head's own flag effects
    // when it writes flags (CHK sets N even as it traps -> the stacked CCR's N must
    // reflect it, matching Musashi). Applied ONLY to a FAULT/trap entry (faultRetire):
    // an INTERRUPT preempts the head BEFORE its effects (it re-executes after RTE), so
    // an interrupt stacks the plain committedCcr. For faults that don't modify CCR
    // (illegal/TRAPV/DIV0/access-fault) nzvcWrStore(h0) is False -> == committedCcr.
    val ccrForException = UInt(5 bits); ccrForException := committedCcr
    when(faultRetire && nzvcWrStore(h0)) { ccrForException(3 downto 0) := nzvcValStore(h0) }
    when(faultRetire && xWrStore(h0))    { ccrForException(4)          := xValStore(h0) }
    // CAPTURE the faulting instruction's own NZVC fold at the trigger cycle and HOLD
    // it until the (much-later) exception-entry obs fires (the lock-step whitebox
    // folds it onto the running CCR for the entry step). Only a CHK fault writes flags
    // as it traps; other faults leave nzvcWrStore(h0)=False -> held invalid.
    val heldCcrFold      = Reg(UInt(4 bits))
    val heldCcrFoldValid = RegInit(False)
    when(faultRetire) {
      heldCcrFold      := nzvcValStore(h0)
      heldCcrFoldValid := nzvcWrStore(h0)
    }

    // ── Commit-side exception sequencer (entry FSM + RTE) ───────────────────────
    // entryTrigger has TWO sources: a faulted/trap head (exceptionPending) OR an
    // interrupt at a macro-instruction boundary (interruptPending). They are
    // mutually exclusive (exceptionPending requires faulted(h0); interruptPending
    // requires !faulted(h0)). For an interrupt entry the vector = the simple-protocol
    // curVec (interruptVec), the stacked PC = the head INSTRUCTION's PC (interruptPc),
    // entryIsInterrupt selects the SR I-mask:=level update + format-$0 (not $7/$2).
    // Task #193: a pending TRACE dispatch (vector 9) is a THIRD entryTrigger source,
    // prioritized BETWEEN exceptionPending (real synchronous faults/priv — highest)
    // and interruptPending (lowest) per Motorola's exception-priority grouping (trace
    // outranks interrupts but a genuine fault/priv violation at the SAME boundary
    // still wins — see tracePendingFire's own gating, which already excludes a
    // faulted/priv/sysOp/RTE head). Mutually exclusive with both by construction:
    // tracePendingFire requires !faultedStore(h0)/!privViolation/!p0.sysOp
    // (excludes exceptionPending's sources) and is independent of iplActive/stopped's
    // interrupt-only gating (though both CAN be simultaneously ready at one boundary —
    // the Mux order below is what actually enforces trace-over-interrupt priority).
    val excEntryTrigger = exceptionPending || tracePendingFire || interruptPending
    // exceptionVector/exceptionPc already fold in the sysPrivFault (vector 8 + the sysOp
    // PC); a fault/trap uses them, trace overrides with vector 9 + its latched resume
    // PC, else an interrupt overrides with its own vector/PC.
    val excEntryVector  = Mux(exceptionPending, exceptionVector,
                          Mux(tracePendingFire, U(9, 8 bits), interruptVec))
    val excEntryPc      = Mux(exceptionPending, exceptionPc,
                          Mux(tracePendingFire, tracePendingPc, interruptPc))
    // MMU control (task #131): resolved here so the exception FSM's MOVEC read-side
    // case (Rc->Rn: TCR/URP/SRP) can read mmuCtrl.mmuEnable/urp/srp — MmuControlPlugin
    // has no dependencies of its own, so this is a plain leaf lookup (no Fiber-cycle
    // risk, unlike PrivilegeService). The write-side (real supervisor code
    // PROGRAMMING the MMU via MOVEC) was reverted — see MmuControlPlugin's doc
    // comment — so mmuCtrl is READ-ONLY here now.
    // OPTIONAL (host.get, mirrors intCtrl below): standalone ROB unit-test DUTs
    // (RobPluginSpec/RobFaultSpec/etc.) don't instantiate an MmuControlPlugin — fall
    // back to a throwaway idle implementation so those DUTs keep elaborating
    // unchanged. The full-core DUTs DO include MmuControlPlugin (same pattern DtlbPlugin/
    // ItlbPlugin already rely on).
    val mmuCtrl: m68k040.services.MmuControlService =
      host.get[m68k040.services.MmuControlService].getOrElse(new m68k040.services.MmuControlService {
        override def mmuEnable = False
        override def pageSize8K = False
        override def urp = U(0, 32 bits)
        override def srp = U(0, 32 bits)
        override def itt0 = U(0, 32 bits)
        override def itt1 = U(0, 32 bits)
        override def dtt0 = U(0, 32 bits)
        override def dtt1 = U(0, 32 bits)
        override def mmusr = U(0, 32 bits)
        override def setEnable = { val f = Flow(Bool()); f.valid := False; f.payload := False; f }
        override def setPageSize = { val f = Flow(Bool()); f.valid := False; f.payload := False; f }
        override def setUrp    = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setSrp    = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setItt0   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setItt1   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setDtt0   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setDtt1   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setMmusr  = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
      })
    // FP control state (Task 9): the exception FSM's SysKind.FMOVE_FPCTRL arm reads and
    // writes FPCR/FPSR/FPIAR here. OPTIONAL for exactly the same reason mmuCtrl is:
    // standalone ROB unit DUTs (RobPluginSpec/RobFaultSpec/...) do not instantiate an
    // FpuControlPlugin. `null` selects ExceptionUnit's own local idle null object.
    val fpuCtrl: m68k040.services.FpuControlService =
      host.get[m68k040.services.FpuControlService].getOrElse(null)
    // ── Task 11: drive FSAVE's null-vs-idle discriminator from FP commit ──────────
    // "At least one instruction has been executed since the last hardware reset or
    // FRESTORE of a null state frame" (MC68040 UM 1989 1st ed. p.9-30). Keyed on a
    // committed FP REGISTER WRITE, which is a deliberately CONSERVATIVE reading of the
    // manual's "nonconditional floating-point instruction": every instruction the manual
    // excludes (FNOP, FBcc, FDBcc, FScc, FTRAPcc) writes no FP register, so none of them
    // can set this bit -- exactly the required behavior, and it cannot drift.
    //
    // Placed BEFORE the ExceptionUnit is built, deliberately: a null-frame FRESTORE
    // CLEARS the same flag from `F_HDRWAIT`, and SpinalHDL's later-`when`-wins ordering
    // must let that clear beat this set. (They cannot actually collide -- retirement is
    // blocked for the whole serializing episode -- so this is defence in depth.)
    if (fpuCtrl != null) {
      when((rc.commitPorts(0).valid && rc.commitPorts(0).fpWrite) ||
           (rc.commitPorts(1).valid && rc.commitPorts(1).fpWrite)) {
        fpuCtrl.setEverExecuted.valid   := True
        fpuCtrl.setEverExecuted.payload := True
      }
    }
    val exc = new m68k040.exception.ExceptionUnit(
      ss = new m68k040.exception.SystemState,
      mmuCtrl = mmuCtrl,
      fpuCtrlOpt = fpuCtrl,
      entryTrigger = excEntryTrigger, entryVector = excEntryVector, entryPc = excEntryPc,
      // PPC for a format-$2 group-2 trap (TRAPV/CHK/DIV0) = the trapping INSTRUCTION's
      // PC. p0.pc holds the instruction PC (variable-length safe; entryPc-2 only
      // worked for the 2-byte TRAPV). Interrupts ignore entryPpc (format-$0). Task #193:
      // a pending TRACE dispatch's PPC is `tracePendingPpc` (the ALREADY-RETIRED traced
      // instruction's own PC, latched at arm time) — by the time tracePendingFire fires,
      // h0/p0.pc point at the NEXT (about to be preempted) instruction, not the
      // traced one, so p0.pc would be wrong here.
      entryPpc     = Mux(tracePendingFire, tracePendingPpc, p0.pc),
      // rtePc = the RTE instruction's OWN PC (task #177 fix; was p0.predNextPc = RTE's
      // pc+length, i.e. the address AFTER RTE, which broke the format-error retry
      // contract -- a vec-14 handler that patches the malformed frame and re-RTEs must
      // land back ON the original RTE, not 2 bytes past it). RTE retires solely at h0
      // (rteRetire is gated on p0.isRte), so p0.pc is RTE's own committed
      // PC, mirroring entryPpc's pattern just above.
      rteTrigger   = rteRetire,        rtePc        = p0.pc,
      committedCcr = ccrForException,
      // Access-fault (vector 2) extras for the format-$7 frame.
      entryFaultAddr = faultAddr0,
      entryFaultWr   = faultWr0,
      entryFaultSup  = faultSup0,
      entryFaultInstr= faultInstr0,
      // Task #189: SIZE field + ATC bit — both now genuinely threaded (were
      // previously computed here but the SIZE one was never passed at all, and
      // ATC was hardcoded true in ExceptionUnit; see that file's SSW-builder).
      entryFaultSize = faultSize0,
      entryFaultAtc  = faultAtc0,
      entryIsInterrupt = interruptPending,
      entryIplLevel    = interruptLevel,
      // ── Commit-time SYSTEM op (supervisor): drive the S_APPLY FSM ──────────────
      // sysTriggerSig is forward-declared (the S=1 vs S=0 decision needs exc.ss.s,
      // available only after exc is built) and driven below. The captured context comes
      // straight from the head's per-entry sysOp stores + the captured value.
      sysTrigger = sysTriggerSig,
      sysKind    = p0.sysKind.asBits.asUInt.resize(4),   // task #198: 4 bits (10 SysKind
                                                                // elements as of task P5.2's CINV
                                                                // insertion -- ExceptionUnit's
                                                                // S_APPLY dispatch reads this same
                                                                // .asBits.asUInt.resize(4) shape via
                                                                // symbolic skOrd(SysKind.X) helpers,
                                                                // not hand-written ordinals, so this
                                                                // comment no longer needs to track a
                                                                // specific element's numeric value)
      sysReadDir = p0.sysReadDir,
      sysVal     = sysValStore(h0),
      sysAux     = sysAux,          // Task 9b: the load direction's per-position values
      sysRc      = p0.sysRc,
      sysDstPhys = p0.intNew,        // the read µop's rename-allocated pdst (FSM writes it)
      // The ARCHITECTURAL id behind that same pdst. The FSM needs it for exactly one
      // question -- "is this sysOp's own destination A7?" -- which decides whether
      // S_REDIR's A7 re-bank must be sourced from the sysOp's own write instead of the
      // (stale/garbage) `ss.a7`. See ExceptionUnit's `sysOwnA7Valid` doc comment.
      sysDstArch = p0.archRegId,
      sysPc      = p0.pc,
      sysNextPc  = p0.predNextPc,
      // Task 11: the head's FP unimplemented-instruction marker + command word, read
      // only at vector-11 delivery. ROB-fold task #249: now payload.fpuUnimp/fpuCmd.
      entryFpuUnimp = p0.fpuUnimp,
      entryFpuCmd   = p0.fpuCmd)
    excIdle := !exc.active
    val excActive = exc.active; excActive.simPublic()
    // Drive the forward-declared committed-S (the privilege check gates on it).
    committedS := exc.ss.s
    _dcacheEnabled := exc.ss.cacr(31)
    // The commit-time system op's S=1 vs S=0 split (needs exc.ss.s): S=1 supervisor ->
    // drive the S_APPLY FSM (sysTrigger); S=0 user -> a vector-8 privilege fault.
    // EXCEPTION to the "every sysOp is privileged" rule (Task 9): FMOVE to/from a
    // floating-point CONTROL register (FPCR/FPSR/FPIAR) is a real 68040 USER instruction
    // -- in line-F only FSAVE/FRESTORE and the MMU/cache maintenance ops are privileged,
    // and the MC68040 UM's own FPSP prologue runs these from user code. It is a sysOp
    // purely because FPCR/FPSR/FPIAR are non-renamed single-copy state that must be
    // touched at a serializing retire (spec Decision 5), NOT because of privilege. So it
    // drives the S_APPLY FSM regardless of committed S, and never raises vector 8.
    val sysUserOk = p0.sysKind === m68k040.decode.SysKind.FMOVE_FPCTRL
    sysTriggerSig := sysRetire && (exc.ss.s || sysUserOk)
    sysPrivFault  := sysRetire && !exc.ss.s && !sysUserOk
    // MOVE-to-SR writes the FULL CCR (sysVal[4:0]) as an absolute value: override the
    // committed CCR when the system-op FSM applies it (so a LATER exception's stacked SR
    // low byte is correct). The FSM surfaces obsSetCcr5 on S_REDIR; apply it last-wins
    // (after the retire0/retire1 folds above — SpinalHDL last-`when` wins).
    when(exc.obsSetCcr5Valid) { committedCcr := exc.obsSetCcr5 }

    // ── Commit-time SYSTEM op READ: commit the dst arch->pdst mapping at the trigger ──
    // A MOVE-USP/MOVEC Rc->Rn (read) does NOT go through retire0 (it is serializing),
    // but its renamed dst MUST commit (arch->pdst into the committed RAT + free the old
    // pdst) so a normal later reader of Rn sees the FSM-written value (PRF[pdst]). Driven
    // at the trigger cycle (sysTriggerSig = sysRetire && S=1) for a read-direction head.
    // Only the int-RAT/freelist commit (intWrite); no trace (the obs is the ExcRec).
    // Last-wins over the default/retire0 (retire0 is gated off the sysOp head).
    //
    // Task 11 WIDENED the gate from `p0.sysReadDir` alone to "read direction OR the head
    // actually has a renamed int destination". FSAVE -(An) / FRESTORE (An)+ are the first
    // sysOps whose An write-back rides `sysRegWrite*` while `sysReadDir` is FALSE (An is a
    // genuine SOURCE for them -- its value has to reach `sysVal`/`sysCapVal` as the frame
    // base -- and it is ALSO an auto-update destination). With the old gate their PRF
    // write landed in a physical register the committed RAT never pointed at, so a later
    // reader of An silently saw the pre-FSAVE value; found by this task's directed tests.
    //
    // This is a STRICT SUPERSET of the old condition, not a change to any existing case:
    // every read-direction sysOp (MOVEC Rc->Rn, MOVE-USP USP->An, FMOVE FPcr->Rn) sets
    // `dstValid` and therefore `intWrite` too, and every write-direction one leaves
    // `intWrite` False -- so for them the block's effect is identical either way.
    when(sysTriggerSig && (p0.sysReadDir || p0.intWrite)) {
      rc.commitPorts(0).valid     := True
      rc.commitPorts(0).intArch   := p0.archRegId
      rc.commitPorts(0).intNew    := p0.intNew
      rc.commitPorts(0).intOld    := p0.intOld
      rc.commitPorts(0).intWrite  := p0.intWrite
      rc.commitPorts(0).nzvcWrite := False
      rc.commitPorts(0).xWrite    := False
      // A sysOp (MOVE-USP / MOVEC) never writes FP or FPCC; state it EXPLICITLY here for
      // the same reason nzvcWrite/xWrite are stated -- this block last-wins over
      // driveCommit, so an FP-writing entry that somehow reached this path must not leak
      // a spurious fpFree push / fpRat commit.
      rc.commitPorts(0).fpWrite   := False
      rc.commitPorts(0).fpccWrite := False
    }

    // ── RTE CCR restore -> REAL flags PRF: NOT handled here (task #176-regression) ──
    // task #176 originally rename-allocated a fresh pNzvcDst/pXDst for RTE's µop and
    // committed the new arch->phys mapping here, mirroring the sysOp-read block just
    // above. That mechanism caused a CONFIRMED silent-corruption regression under
    // back-to-back/nested exception storms (exc_stack_atomicity_stress,
    // pea_aline_irq_storm, via1_t1_irq_storm): a rename-allocated pNzvcDst/pXDst sits
    // "uncommitted" from RenameStage's freelist perspective for RTE's ENTIRE
    // multi-cycle R_DRAIN..R_REDIR FSM run (`flushing`, which gates the freelist's
    // flush/rollback via `rc.flushPort`, stays asserted the whole time via
    // `excSquash`), so a wrong-path instruction renamed during that window could be
    // handed the EXACT SAME physical register RTE itself was mid-flight with,
    // clobbering it. The restore is now a DIRECT write into whatever physical
    // register nzvcRat/xRat's COMMITTED mapping already names — mirroring the
    // already-safe `a7WriteValid`/`a7WriteData` pattern — driven by the wiring
    // plugins (FullCoreSynth.scala/FuzzDut.scala/IpcBenchSpec.scala/
    // ExecuteLockStepSpec.scala) directly off `exc.rteNzvcWriteValid`/
    // `exc.rteXWriteValid`, entirely outside RenameStage's rename/freelist/RAT-commit
    // machinery. See ExceptionUnit.scala's `rteNzvcWriteValid` doc comment for the
    // full story.

    // ── Drive interrupt recognition (needs the SR I-mask from exc.ss, built above) ─
    // iplIn > srSys[2:0] (mask) OR a latched NMI edge (level 7, `nmiPending` above), at
    // a first-µop non-faulted/non-RTE head, excIdle, head present, not flushing.
    // (Forward-declared above so retire0 can gate on it.) A pending PRIVILEGE VIOLATION
    // takes priority over an interrupt (the head's own exception delivers first), so
    // exclude it.
    val maskI = exc.ss.srSys(2 downto 0)
    val iplActive = (iplIn > maskI) || nmiPending
    iplActive.simPublic()
    // Normal recognition: a first-µop non-faulted/non-RTE/non-sysOp head is present. When
    // STOPPED the ROB is empty (count==0, no head) and the IRQ must wake the halted core
    // with no head present, so OR in `stopped` as a recognition gate.
    val normalIrqGate = (count > 0) && p0.first && !faultedStore(h0) &&
                        !p0.isRte && !privViolation && !p0.sysOp &&
                        !preciseDrainBusyIn && !inhibitedLoadBusyIn
    // 2026-08-27 boot-investigation debug taps (zero synth impact): localize why a
    // genuinely pending+unmasked+enabled interrupt is not recognized while the ROB
    // is retiring a tight self-looping branch (dbf), despite being recognized
    // instantly once that loop is broken externally -- real-hardware-confirmed,
    // see docs/BUG_calibration_word_misplaced_0d00.md Part 8 in the SoC repo.
    normalIrqGate.simPublic()
    p0.first.simPublic()
    faultedStore(h0).simPublic()
    preciseDrainBusyIn.simPublic()
    inhibitedLoadBusyIn.simPublic()
    // A halted core (Task P4.5) recognizes no interrupt -- deliberately NOT
    // wakeable, matching the design doc's decision (unlike `stopped`, which IS
    // interrupt-wakeable).
    interruptPending := (normalIrqGate || stopped) && !flushing && excIdle && iplActive && !coreHalted
    // Priority-rule invariant (design doc §4.1/§5 item 8): interruptPending can only
    // go true when normalIrqGate held (which now requires !preciseDrainBusyIn AND
    // !inhibitedLoadBusyIn), so a LAUNCHED precise store drain -- or an outstanding
    // inhibited-load device read -- must never coexist with a newly-recognized
    // interrupt at the SAME head. These asserts exist purely to catch a future edit
    // that loosens normalIrqGate's busy terms.
    // Explicit `FAILURE` severity -- see M68kSim.scala for why `.includeSimulation`
    // must also be set on the enclosing SpinalConfig for this block to elaborate at
    // all (without it, `GenerationFlags.simulation { ... }` is silently skipped).
    GenerationFlags.simulation {
      assert(!(interruptPending && preciseDrainBusyIn),
        "RobPlugin: interruptPending recognized while a precise SQ drain was in flight",
        FAILURE)
      assert(!(interruptPending && inhibitedLoadBusyIn),
        "RobPlugin: interruptPending recognized while an inhibited load's device read was in flight",
        FAILURE)
    }
    // Consume the NMI latch the same cycle it is actually taken — gated on `nmiPending`
    // itself (not the live `iplIn`), so a latched edge is serviced as vector/level 7
    // even if the SoC has already dropped the line by the recognition cycle (mirrors
    // Musashi clearing nmi_pending in m68ki_check_interrupts right as it converts to
    // m68ki_exception_interrupt(7), independent of the current CPU_INT_LEVEL). A held
    // level-7 line does NOT re-arm nmiPending (no new edge) -> no re-entry until the
    // line drops and rises again.
    when(interruptPending && nmiPending) { nmiPending := False }
    // STOP halts the core after its serializing retire (supervisor only; S=0 -> vector-8
    // via sysPrivFault). The interrupt entry RESUMES it: clear `stopped` when an interrupt
    // is recognized. (sysTriggerSig / interruptPending are both built above.)
    val stopEnter = sysTriggerSig && (p0.sysKind === m68k040.decode.SysKind.STOP)
    stopEnter.simPublic()
    when(stopEnter) {
      stopped   := True
      stoppedPc := p0.predNextPc      // STOP's nextPc = the resume point (IRQ stacks this)
    }
    when(interruptPending) { stopped := False }

    // Cycle-exact frontend localization. These expressions mirror the registers'
    // actual last-assignment priorities above: interrupt clear wins over STOP set,
    // while fatal halt is sticky. FetchAlign registers `next` on this same edge, so
    // its local quiesce bit equals `active` after every edge without putting the
    // remote ROB registers into the live fetch command cone.
    val stoppedNext = (stopped || stopEnter) && !interruptPending
    val coreHaltedNext = coreHalted || coreHaltedIn
    _frontendQuiesceActive := stopped || coreHalted || debugQuiesceActive
    _frontendQuiesceNext := stoppedNext || coreHaltedNext || debugQuiesceNext
    _frontendQuiesceActive.simPublic()
    _frontendQuiesceNext.simPublic()

    // ── DebugCommitService readback ─────────────────────────────────────────────
    _debugEffectiveHalt    := debugHalted || coreHalted
    _debugAutoHaltLatched  := debugAutoHaltLatchedReg
    _debugHaltReason       := Mux(coreHalted, U(DebugHaltReasonCode.FATAL, 3 bits),
      debugHaltReasonReg)
    _debugLivePc           := debugLivePcReg
    _debugLastPc           := debugLastPcReg   // Task 3: real producer
    _debugMacroCount       := debugMacroCountReg // Task 3: real producer
    _debugHaltHitInstCount := debugHaltHitInstCountReg
    _debugHaltAfterConsumed := (debugHaltState === DebugHaltState.RUNNING) && haltAfterDue
    _debugHaltHitPc := debugHaltHitPcReg
    _debugBreakpointHit.valid := debugBreakpointBoundaryHit
    _debugBreakpointHit.payload := p0.debugBreakSlot
    _debugExceptionPending := debugExceptionPendingReg
    _debugHaltExceptionVector := debugHaltExceptionVectorReg
    // 2026-09-09: straight from the exception sequencer's own sticky capture -- these are
    // set exactly once, at the double fault, and the core is halted from that cycle on, so
    // no halt-capture latch of their own is needed (unlike the halt-on-exception group
    // above, which samples a LIVE event stream).
    _debugDblFaultPc  := exc.dblFaultPc
    _debugDblFaultVec := exc.dblFaultVec
    _debugHaltExceptionPc := debugHaltExceptionPcReg
    _debugHaltExceptionFaultAddress := debugHaltExceptionFaultAddressReg

    // ── DebugSystemStateService live readback + halted apply sink ──────────────
    // The command producer accepts it only at effective halt. Keep that policy
    // assertion here too, at the architectural owner boundary.
    val debugSystemSr = (exc.ss.srSys.asBits ## B(0, 3 bits) ## committedCcr.asBits).asUInt
    val debugSystemTc = (B(0, 16 bits) ## mmuCtrl.mmuEnable.asBits ##
      mmuCtrl.pageSize8K.asBits ## B(0, 14 bits)).asUInt
    debugSystemSr.simPublic(); debugSystemTc.simPublic()
    when(debugSystemApplyIn.valid) {
      val c = debugSystemApplyIn.payload
      when(c.srValid) {
        exc.ss.setSrSys.valid := True
        exc.ss.setSrSys.payload := c.sr(15 downto 8)
        committedCcr := c.sr(4 downto 0)
      }
      when(c.vbrValid)  { exc.ss.setVbr.valid := True;  exc.ss.setVbr.payload := c.vbr }
      when(c.uspValid)  { exc.ss.setUsp.valid := True;  exc.ss.setUsp.payload := c.usp }
      when(c.mspValid)  { exc.ss.setMsp.valid := True;  exc.ss.setMsp.payload := c.msp }
      when(c.ispValid)  { exc.ss.setIsp.valid := True;  exc.ss.setIsp.payload := c.isp }
      when(c.cacrValid) { exc.ss.setCacr.valid := True; exc.ss.setCacr.payload := c.cacr }
      when(c.sfcValid)  { exc.ss.setSfc.valid := True;  exc.ss.setSfc.payload := c.sfc }
      when(c.dfcValid)  { exc.ss.setDfc.valid := True;  exc.ss.setDfc.payload := c.dfc }
      when(c.tcValid) {
        mmuCtrl.setEnable.valid := True; mmuCtrl.setEnable.payload := c.tc(15)
        mmuCtrl.setPageSize.valid := True; mmuCtrl.setPageSize.payload := c.tc(14)
      }
      when(c.itt0Valid) { mmuCtrl.setItt0.valid := True; mmuCtrl.setItt0.payload := c.itt0 }
      when(c.itt1Valid) { mmuCtrl.setItt1.valid := True; mmuCtrl.setItt1.payload := c.itt1 }
      when(c.dtt0Valid) { mmuCtrl.setDtt0.valid := True; mmuCtrl.setDtt0.payload := c.dtt0 }
      when(c.dtt1Valid) { mmuCtrl.setDtt1.valid := True; mmuCtrl.setDtt1.payload := c.dtt1 }
      when(c.urpValid)  { mmuCtrl.setUrp.valid := True; mmuCtrl.setUrp.payload := c.urp }
      when(c.srpValid)  { mmuCtrl.setSrp.valid := True; mmuCtrl.setSrp.payload := c.srp }
      when(c.pcValid)   { debugLivePcReg := c.pc }
      when(c.tcValid || c.itt0Valid || c.itt1Valid || c.dtt0Valid || c.dtt1Valid ||
           c.urpValid || c.srpValid) {
        // Any address-translation edit invalidates both ATCs on the same architectural
        // apply edge. The full-core wiring already fans this sole-owner pulse to ITLB
        // and DTLB; no debug plugin reaches into either TLB.
        exc.sysFlushAllValid := True
      }
    }
    GenerationFlags.simulation {
      assert(!(debugSystemApplyIn.valid && !(debugHalted || coreHalted)),
        "RobPlugin: debug architectural apply reached commit owner while running", FAILURE)
    }

    val debugExceptionEntry = Flow(DebugExceptionEvent())
    debugExceptionEntry.valid.simPublic()
    debugExceptionEntry.payload.vector.simPublic()
    debugExceptionEntry.payload.exceptionPc.simPublic()
    debugExceptionEntry.payload.faultAddress.simPublic()
    debugExceptionEntry.payload.handlerPc.simPublic()
    debugExceptionEntry.valid := exc.obsFire && exc.obsIsEntry
    debugExceptionEntry.payload.vector := exc.obsVector
    debugExceptionEntry.payload.exceptionPc := exc.obsExceptionPc
    debugExceptionEntry.payload.faultAddress := exc.obsFaultAddress
    debugExceptionEntry.payload.handlerPc := exc.obsHandlerPc
    debugExceptionBoundaryHit := debugExceptionEntry.valid &&
      haltExceptionMaskIn(debugExceptionEntry.payload.vector) && !coreHalted &&
      ((debugHaltState === DebugHaltState.RUNNING) ||
       (debugHaltState === DebugHaltState.STOP_PENDING) ||
       (debugHaltState === DebugHaltState.STEP_RUNNING))
    debugExceptionBoundaryHit.simPublic()
    when(debugClearStickyIn) {
      debugExceptionPendingReg := False
      debugHaltExceptionVectorReg := 0
      debugHaltExceptionPcReg := 0
      debugHaltExceptionFaultAddressReg := 0
    }
    when(debugExceptionBoundaryHit) {
      debugExceptionPendingReg := True
      debugHaltExceptionVectorReg := debugExceptionEntry.payload.vector
      debugHaltExceptionPcReg := debugExceptionEntry.payload.exceptionPc
      debugHaltExceptionFaultAddressReg := debugExceptionEntry.payload.faultAddress
    }

    // ── Drive trace-exception (T0/T1) recognition (task #193) ───────────────────
    // T1/T0 are bits 7/6 of the SR SYSTEM byte (srSys(7)=T1, srSys(6)=T0 — see
    // SystemState's class doc). Read COMBINATIONALLY here: any SAME-cycle SR write
    // from a retiring sysOp (S_APPLY) or RTE (R_REDIR) only LANDS in srSys the
    // FOLLOWING cycle (`when(setSrSys.valid){srSys:=...}` is a plain register
    // update), so this always observes the value as of BEFORE this cycle's own
    // retiring instruction — exactly the "changes to T0/T1 take effect starting
    // the FOLLOWING instruction" semantics (matches Musashi's m68ki_trace_t1(),
    // sampled at the TOP of each instruction's execution, i.e. before it runs).
    val t1Armed = exc.ss.srSys(7)
    val t0Armed = exc.ss.srSys(6)
    // T0 fires only for a genuinely-resolved TAKEN change of flow, and only for the
    // one retiring µop that actually IS the branch-family op (p0.retireAlone) — an
    // ordinary ALU/LS head is never itself a change of flow. branchTakenStore mirrors
    // Musashi's m68ki_trace_t0() call sites (only inside a taken-branch code path).
    val h0ChangeOfFlow = p0.retireAlone && branchTakenStore(h0)
    h0TraceArmed := t1Armed || (t0Armed && h0ChangeOfFlow)
    // ARM: latch a pending trace whenever a genuinely-committing instruction retires
    // under an active trace condition. Covers BOTH retire0 (ordinary ALU/LS/branch
    // heads) and sysRetire (the serializing MOVE-to-SR/ANDI-ORI-EORI-SR/MOVEC/
    // MOVE-USP/STOP/etc. apply, task #193) — a memory-source MOVE-to-SR macro's
    // LEADING load (plain retire0, non-sysOp) and its trailing sysOp apply
    // (sysRetire) belong to the SAME macro and carry IDENTICAL pc/nextPc (one
    // fetched/predecoded instruction, cracked -- see MicroOpAssembler: every crack
    // µop of one macro shares the same `pc`/`nextPc`), and SR cannot change
    // mid-macro (only the sysOp phase itself writes it, landing the cycle AFTER
    // ITS OWN retire) -- so latching from EITHER phase (or both, redundantly) reads
    // the same t1Armed and produces the identical latched {pc,ppc}. This is what
    // keeps a multi-µop crack (e.g. `move (sp)+,sr`, trace_storm_move_sp_postinc_
    // sr_tmp1.s) from mis-arming with a WRONG boundary — see `traceNormalGate`
    // below for the (separate) DISPATCH-side guard that keeps the exception from
    // firing mid-crack.
    when(retire0 && h0TraceArmed) {
      tracePendingReg := True
      tracePendingPc  := commitPc0        // resume PC = the address AFTER the traced instr
      tracePendingPpc := p0.pc      // the traced instruction's OWN pc (format-$2 PPC)
    }
    when(sysRetire && t1Armed) {
      tracePendingReg := True
      tracePendingPc  := p0.predNextPc    // = sysNextPc, the sysOp's own resume point
      tracePendingPpc := p0.pc
    }
    // DISPATCH: only at a genuine NEW-macro boundary (p0.first), exactly like
    // normalIrqGate — this is what defers a trace armed mid-crack (e.g. the LOAD half
    // of a memory-source MOVE-to-SR) until the crack's sysOp phase has ALSO retired
    // (p0.sysOp is excluded here, so a forced-firstOfInstr sysOp uop — see
    // MicroOpAssembler's `opUop.firstOfInstr := True` inside `when(isSysOp)` — can
    // never itself be mistaken for "a new macro boundary" and cause an early fire).
    val traceNormalGate = (count > 0) && p0.first && !faultedStore(h0) &&
                          !p0.isRte && !privViolation && !p0.sysOp &&
                          !preciseDrainBusyIn && !inhibitedLoadBusyIn
    tracePendingFire := tracePendingReg && traceNormalGate && !flushing && excIdle
    when(tracePendingFire) { tracePendingReg := False }

    // Squash + serialize while the FSM runs (NOT on the trigger cycle, when the FSM
    // is still IDLE and the fault/RTE head must retire-trigger). On the trigger cycle
    // excActive is False, so faultRetire/rteRetire fire and the exc captures; next
    // cycle the FSM is active -> count:=0 squashes the faulted/RTE entry + younger.
    excSquash := excActive

    // ── Final registered redirect: branch mispredict OR exception vector/RTE PC ──
    // The exc unit pulses redirectValid (one cycle) at the end of an entry/RTE
    // sequence with the target (vector / restored PC). doFlushReg is the registered
    // fan-out pulse; it drives the IQ/skid/fetch redirect for both cases.
    // A debug recovery shares the existing registered global flush. A coincident
    // resolved branch owns the restart PC (actual target/fall-through); exception
    // redirect remains highest priority. Capture the same selected value directly
    // into debugLivePcReg -- reading flushPcReg here would capture its OLD value.
    // Exception, RTE, interrupt, and serializing-system macros complete only when
    // their sequencer installs its redirect PC; treating that pulse as a boundary
    // prevents STOP_PENDING from parking halfway through architectural state update.
    debugSequencerBoundaryHit := exc.redirectValid
    val debugStepRestartPc = Mux(retire1 && p1.last, p1.predNextPc, p0.predNextPc)
    val debugRestartPc = Mux(exc.redirectValid, exc.redirectPc,
                         Mux(branchRedirect, nextPcRd0,
                         Mux(debugBreakpointBoundaryHit, p0.pc,
                         Mux(haltAfterDue || debugAutoHaltLatchedReg, debugLivePcReg,
                         Mux(debugHaltState === DebugHaltState.STEP_RUNNING, debugStepRestartPc,
                         Mux(count === 0, debugLivePcReg, p0.predNextPc))))))
    // A halted PC edit must update the frontend's registered restart point as well as
    // debugLivePcReg. Reusing the ordinary registered flush keeps every speculative
    // consumer empty and does not release the debug halt.
    // Gated the same way as `debugRecoverEnter` above (see that doc comment):
    // `debugSystemApplyIn.valid` is only ever issued once `debugHalted ||
    // coreHalted` already holds (RobPlugin's own assert just above enforces the
    // precondition), and with `debugRecoverEnter` now gated, entry into
    // `debugHalted` itself cannot happen while `preciseDrainBusyIn` is asserted --
    // so this term is structurally unreachable via that path and is here purely
    // as defense-in-depth for the OTHER, non-retire-gated `coreHalted` (D-cache
    // fatal) path, which this gate does not otherwise cover.
    val debugPcApply = debugSystemApplyIn.valid && debugSystemApplyIn.payload.pcValid &&
      !preciseDrainBusyIn
    doFlushReg := branchRedirect || exc.redirectValid || debugRecoverEnter || debugPcApply
    when(debugPcApply)       { flushPcReg := debugSystemApplyIn.payload.pc }
    when(branchRedirect)    { flushPcReg := nextPcRd0 }   // Slice B: shared h0 read port
    when(exc.redirectValid) { flushPcReg := exc.redirectPc }
    when(debugRecoverEnter && !branchRedirect && !exc.redirectValid) {
      flushPcReg := Mux(debugBreakpointBoundaryHit, p0.pc,
        Mux(debugHaltState === DebugHaltState.STEP_RUNNING,
          debugStepRestartPc, p0.predNextPc))
    }
    when(debugRecoverEnter) { debugLivePcReg := debugRestartPc }

    // ── TIER 1: EU-resolution-time PC redirect + rename halt (2026-09-04) ────────
    // Design: docs/superpowers/specs/2026-09-04-two-tier-reschedule-design.md
    // Source of the idea: NaxRiscv's two-tier reschedule
    // (`misc/CommitPlugin.scala:118-157`, `frontend/FrontendPlugin.scala:66-67`),
    // analysed in `2026-09-04-naxriscv-architecture-comparison.md` §4.
    //
    // THIS IS NOT THE REVERTED 2026-09-03 EARLY FLUSH. Read the "INVESTIGATED AND
    // REJECTED" block above `branchRedirect` before touching this. That attempt
    // performed the TIER-2 action (the global `flushing` rollback: RAT restore,
    // freelist restore, `tail := head`, SQ squash, TLB U/M-queue flush) at TIER-1
    // time, which is why it had to resume at the committed PC, why mispredicts
    // more than doubled, and why it produced 69 `DIVERGED[HANG]` StoreQueue wedges.
    //
    // What Tier 1 changes, exhaustively — TWO things, and NEITHER is a flush:
    //   (1) `earlyFire` — a one-cycle pulse that drives ONLY the frontend fetch
    //       redirect (`FetchAlignPlugin.mispredictRedirect`) to the branch's OWN
    //       resolved `nextPc`, plus the purely-frontend `DecodeUopService.pipeFlush`
    //       (FetchAlign→decode skid, MicroOpQueue, decode-side µcode sequencers) and
    //       the RAS predictor checkpoint restore. Every one of those consumers holds
    //       PRE-RENAME state only: no architectural register, no ROB entry, no
    //       physical-register allocation, no store.
    //   (2) `earlyPend` — a sticky "rename is halted" flag consumed by
    //       `RenameStage.logic.allocHalt`. It FREEZES speculative state (no freelist
    //       pop, no RAT write, no ROB allocation) rather than rolling it back.
    //
    // What Tier 1 explicitly does NOT touch, and MUST NEVER touch:
    //   `doFlushReg` / `flushing` / `rc.flushPort` / `tail := head` / `count := 0` /
    //   `IssueQueueService.flushPort` / `RenameStage.pipeFlush` / `LsEuPlugin.sqFlush`
    //   (StoreQueue) / `DtlbPlugin.umFlush` / `ItlbPlugin.umFlush` (`UmWriteQueue`) /
    //   `DivEuPlugin.cplxFlush`.
    //
    //   The TLB one is a hard safety constraint, not a preference. `UmWriteQueue`
    //   discards uncommitted entries on `io.flush` (`UmWriteQueue.scala:111-118`);
    //   that is conservative-safe ONLY because today's `io.flush` is retire-gated,
    //   so a needed M (modified) descriptor write is already marked `committed` and
    //   survives. Firing it earlier could drop an M write for an instruction that
    //   still retires — a dirty page later evicted as clean, i.e. SILENT DATA LOSS
    //   (M is a correctness bit; U is only a hint). Tier 1 asserts NO flush, so
    //   `umFlush` keeps its sole driver `doFlushReg` and that direction stays closed.
    //   See `2026-09-04-naxriscv-architecture-comparison.md` §7.5(2).
    //
    // Tier 2 is UNCHANGED: `branchRedirect` still fires only at the ROB head and
    // still drives the entire global rollback, where "restore to committed" and
    // "restore to just-after-the-branch" are the same state by construction. The
    // mispredicting branch is never discarded early, so BTB/gshare training stays
    // retire-gated exactly as before (the reverted attempt had to add early
    // training because it threw the branch away; this one does not).
    //
    // The BENEFIT is refetch latency: fetch/align/decode of the correct path runs
    // during the shadow of waiting for the branch to reach the head, instead of
    // starting from scratch afterwards. To collect it, Tier 2 SUPPRESSES its own
    // frontend redirect + frontend skid flush when — and only when — it is the
    // retirement of the exact branch Tier 1 already redirected for (`earlySuppressFe`
    // below). Any other flush source, any robId mismatch, and any PC mismatch falls
    // back to today's full frontend flush.
    //
    // DEADLOCK ARGUMENT for the rename halt. `earlyPend` is set only for a branch
    // that is resident in the ROB with `mispredictStore` set. Retire is in-order and
    // needs nothing from rename, so that branch necessarily reaches the head and
    // fires `branchRedirect` (or an older exception/debug event fires first); either
    // way `flushing` asserts and clears `earlyPend`. The halt is implemented as an
    // extra term on the SAME `du.uops.ready`/`uopsPort.valid` gate that already
    // carries `freeReady` (`RenameStage.scala:138-139`), i.e. an already-proven
    // backpressure path that handles mid-macro stalls.
    //
    // `earlyPend` deliberately stays asserted THROUGH the `flushing` cycle (it is
    // cleared by a registered assignment, so it reads True during that cycle). That
    // is load-bearing: with the frontend skid flush suppressed, `du.uops.valid` can
    // be high on the rollback cycle, and renaming against a RAT/freelist that is
    // being restored the same cycle would corrupt the mapping.
    val earlyPend  = RegInit(False);            earlyPend.simPublic()
    val earlyFire  = RegInit(False);            earlyFire.simPublic()
    val earlyRobId = Reg(UInt(robIdW bits)) init 0
    val earlyPcReg = Reg(UInt(32 bits))     init 0
    earlyRobId.simPublic(); earlyPcReg.simPublic()

    // Oldest-wins arbitration, NaxRiscv `CommitPlugin.scala:118-144`. Age is
    // `robId - head` mod 64, the same wraparound-safe idiom `faultWin` uses at the
    // completion ports. `bcInFlight` additionally rejects a completion whose robId
    // is not currently resident — the branch EU has no flush input, so a wrong-path
    // branch already squashed by an earlier Tier-2 flush can still report one or two
    // cycles later; today that is harmless (alloc-reset overrides), but it must not
    // be allowed to steer the fetch PC.
    val bcAge  = (branchCompletion.payload.robId - head).resize(count.getWidth)
    val curAge = (earlyRobId - head).resize(count.getWidth)
    val bcInFlight = bcAge < count
    val earlyArm = branchCompletion.valid && branchCompletion.payload.mispredict &&
      bcInFlight && !flushing && !excActive && !coreHalted && !debugQuiesceActive &&
      (!earlyPend || (bcAge < curAge))
    earlyFire := earlyArm
    when(earlyArm) {
      earlyPend  := True
      earlyRobId := branchCompletion.payload.robId
      earlyPcReg := branchCompletion.payload.nextPc
    }
    // Tier 2 releases the halt. Registered assignment => `earlyPend` still reads
    // True on the `flushing` cycle itself (see the note above). Placed AFTER the
    // `when(earlyArm)` block so the clear wins on any coincidence — `earlyArm`
    // already gates on `!flushing`, so this is defence in depth against a future
    // edit, not a live case (SpinalHDL last-assignment-wins).
    when(flushing) { earlyPend := False }

    // Tier 2 frontend-flush suppression. True for exactly one cycle, aligned with
    // `doFlushReg`, when this flush IS the retirement of the branch Tier 1 already
    // redirected for. Fail-safe by construction: the robId must match, the resolved
    // PC must match, and no other flush source may be active — otherwise the
    // frontend takes today's full flush + redirect.
    val earlyHit = branchRedirect && earlyPend && (h0 === earlyRobId) &&
      (nextPcRd0 === earlyPcReg) &&
      !exc.redirectValid && !debugRecoverEnter && !debugPcApply
    val earlySuppressFe = RegNext(earlyHit) init False
    earlySuppressFe.simPublic()

    // ── Flush (squash all in-flight) — pointer-only, driven by the registered ─────
    // redirect pulse OR the test flush port.
    when(flushing) {
      tail  := head
      count := 0
      // The ROB is empty next cycle (count=0 <= depth-2 always) — re-assert immediately
      // rather than waiting a cycle for the RegNext(countNext<=...) path to catch up
      // (countNext was computed off the PRE-flush count/alloc/retire this cycle).
      allocReadySig := True
    }
    rc.flushPort := flushing

    // ── Invariant: a commit must never be presented on a flushing cycle ─────────
    // RenameStage's freelists gate their push-consumption on `!io.flush` (see
    // Freelist.scala) — a push offered on a flushing cycle would be SILENTLY
    // DROPPED (the old pdst never returns to the pool -> a permanent physreg
    // leak). That is safe ONLY because every producer of `rc.commitPorts(_).valid`
    // (driveCommit's retire0/retire1 call sites above, AND the sysOp-read commit
    // block gated by sysTriggerSig/sysRetire) is itself gated through `headReady`,
    // which ANDs in `!flushing` directly (see headReady's definition above) — so
    // commitPorts(k).valid is architecturally UNREACHABLE while `flushing` (=
    // `rc.flushPort`, the exact same wire Freelist reads as `io.flush`) is high.
    // This is not merely a retire-ordering convention (branch-mispredict commit
    // landing the cycle before doFlushReg asserts, excSquash occupying the ROB
    // head for its whole run) — it is tautological given `headReady`'s own gate.
    // This assert pins that guarantee so a FUTURE commit path that bypasses
    // `headReady` (a new sysOp variant, a new FSM-driven commit, etc.) trips
    // immediately here instead of silently leaking a physical register.
    GenerationFlags.simulation {
      assert(!(flushing && (rc.commitPorts(0).valid || rc.commitPorts(1).valid)),
        "RobPlugin: a commitPorts.valid was presented while flushing was asserted -- " +
        "the corresponding Freelist push would be silently dropped by its !io.flush gate",
        FAILURE)
    }

    // ── Sim-only commit observation (lock-step harness consumes this) ───────────
    // Carries the POST-instruction SR (full 16-bit) + A7 so the lock-step can
    // compare them against Musashi's OracleStep.sr / a(7) through an exception.
    // Channel 2 is the exception/RTE "instruction" commit (handler-entry / restored
    // PC + the post-event SR/A7), produced by the ExceptionUnit's obs.
    // Carries the post-instruction SR SYSTEM BYTE (S/I/T) + A7. The lock-step
    // whitebox combines sysByte with its OWN reconstructed CCR (the ROB has no CCR
    // VALUE on the hot path) to form the full 16-bit SR. Channel 2 is the
    // exception/RTE "instruction" commit (handler-entry / restored PC + the
    // post-event sysByte/A7), produced by the ExceptionUnit's obs.
    case class CommitObs() extends Bundle {
      val fire = Bool(); val robId = UInt(robIdW bits); val pc = UInt(32 bits)
      val sysByte = UInt(8 bits); val a7 = UInt(32 bits)
      // Faulting instruction's own NZVC fold for the lock-step whitebox (channel 2,
      // entry only): when a faulting head WROTE flags (CHK sets N as it traps), the
      // whitebox folds these 4 bits onto the running CCR before the entry step (its
      // own Wb never retires normally). `ccrFoldValid` qualifies it; for entries that
      // don't modify CCR (TRAPV/DIV0/access-fault) / interrupts / RTE it is False
      // (the whitebox's running CCR already reflects the architectural state).
      //
      // OPEN RTL BUG, tracked at `docs/BUG_chk_chk2_flags_not_committed_on_trap.md`:
      // these bits reach the STACKED SR but are never committed to the architectural
      // CCR, so a CHK/CHK2 handler executes on the PREVIOUS instruction's flags
      // (`smi %d5` gives 0x00 on the DUT, 0xFF on Musashi). The scoped fix is an
      // `entryNzvcWriteValid` into `RenameStage.committedPhysNzvc` driven from the
      // same `heldCcrFold` the frame already uses — symmetric with the existing
      // `rteNzvcWriteValid` path. NOT implemented; needs its own test and synth gate.
      val ccrFold      = UInt(4 bits)
      val ccrFoldValid = Bool()
      // True for an INTERRUPT-entry obs (channel 2 only). The lock-step harness drops
      // it (Musashi bundles the interrupt entry with the first handler instruction).
      val isInterrupt = Bool()
      // MOVE-to-SR (commit-time system op): an ABSOLUTE 5-bit CCR write (X N Z V C). The
      // whitebox SETS its running CCR to this value (vs the per-bit NZVC fold). Default
      // invalid (every fault/RTE/interrupt obs leaves the running CCR via the fold path).
      val setCcr5      = UInt(5 bits)
      val setCcr5Valid = Bool()
      // Macro-boundary LAST marker of the RETIRING entry (`RobPayload.last`, i.e.
      // `DecodedUop.lastOfInstr`). The lock-step whitebox needs it to tell a DROPPED
      // crack µop that LEADS its macro (a BSR/JSR/LINK stack push, a cracked leading
      // load) from one that TRAILS it (the (An)+/-(An) auto-update folded onto a
      // mem-dest RMW/CLR/Scc store). Both are dropped for step alignment and both may
      // carry a real architectural An write, but only the TRAILING one lands AFTER its
      // macro's kept commit record -- so only that one has to be folded BACKWARD into
      // the record already emitted. Without this bit the whitebox folded it FORWARD
      // onto the NEXT instruction's record instead, reporting A7 one instruction late
      // for `CLR/NEG/NEGX/NOT/TAS/Scc <ea>` with an (A7)+/-(A7) EA (Part 124).
      val macroLast = Bool()
    }
    // Task #249: sim-only like every other tap in this section (pcStore above,
    // faultedStore.simPublic, etc.), but unlike its sibling `pcStore` this whole
    // section was never actually GATED by `GenerationFlags.simulation` -- it always
    // elaborated 3x91 bits of registers + fan-in into every synth/GenVerilog build.
    // Its only real consumer, IplAckPlugin's `rob.logic.commitObs(2)` read
    // (`IplAckPlugin.scala:105`), is itself already inside a `GenerationFlags.
    // simulation` block, so that access is unaffected by gating the producer too.
    // `commitObs(0)`/`commitObs(1)` have zero consumers anywhere (grep-confirmed) --
    // this whole block is sim-only observability, exactly like `pcStore`. Mirrors
    // pcStore's exact idiom: the val itself is `null` in every synth/GenVerilog
    // build, so (like pcStore) it must never be referenced outside a
    // `GenerationFlags.simulation` block -- which is already true of its sole
    // consumer above.
    val commitObs = GenerationFlags.simulation {
      val obs = Vec(CommitObs(), 3); obs.simPublic()
      obs(0).fire := RegNext(retire0) init False; obs(0).robId := RegNext(h0); obs(0).pc := RegNext(commitPc0)
      obs(0).sysByte := RegNext(exc.ss.srSys); obs(0).a7 := RegNext(exc.ss.a7); obs(0).isInterrupt := False
      obs(0).ccrFold := 0; obs(0).ccrFoldValid := False
      obs(0).setCcr5 := 0; obs(0).setCcr5Valid := False
      obs(0).macroLast := RegNext(p0.last) init False
      obs(1).fire := RegNext(retire1) init False; obs(1).robId := RegNext(h1); obs(1).pc := RegNext(commitPc1)
      obs(1).sysByte := RegNext(exc.ss.srSys); obs(1).a7 := RegNext(exc.ss.a7); obs(1).isInterrupt := False
      obs(1).ccrFold := 0; obs(1).ccrFoldValid := False
      obs(1).setCcr5 := 0; obs(1).setCcr5Valid := False
      obs(1).macroLast := RegNext(p1.last) init False
      // Exception / RTE commit (handler-entry or restored PC + post-event sysByte/A7).
      // For a FAULT entry where the faulting head wrote flags (CHK), carry its NZVC fold
      // so the whitebox folds it (the faulting µop's Wb never retires normally).
      obs(2).fire := RegNext(exc.obsFire) init False; obs(2).robId := RegNext(h0)
      obs(2).pc := RegNext(exc.obsPc); obs(2).sysByte := RegNext(exc.obsSysByte); obs(2).a7 := RegNext(exc.obsA7)
      // Fold value held since the trigger (aligned with the late obsFire entry pulse).
      // Applied ONLY to a fault/trap ENTRY obs (obsIsEntry) that is not an interrupt and
      // whose faulting instruction wrote flags (CHK). RTE obs / interrupt entries carry
      // no fold (their running-CCR reconstruction is already correct).
      obs(2).ccrFold      := RegNext(heldCcrFold)
      obs(2).ccrFoldValid := RegNext(exc.obsFire && exc.obsIsEntry && !exc.obsIsInterrupt && heldCcrFoldValid) init False
      obs(2).isInterrupt := RegNext(exc.obsIsInterrupt) init False
      // MOVE-to-SR's absolute CCR write (registered alongside the obs pulse).
      obs(2).setCcr5      := RegNext(exc.obsSetCcr5)
      obs(2).setCcr5Valid := RegNext(exc.obsFire && exc.obsSetCcr5Valid) init False
      // Channel 2 is the exception/RTE pseudo-instruction step (ExcRec), which is never
      // a cracked macro's trailing µop -- the whitebox never consults macroLast there.
      obs(2).macroLast := True
      obs
    }

    // ── A7-ODD TRIPWIRE (2026-09-09, the p164 odd-SSP boot defect) ──────────────────
    // ELABORATION-GATED on the CPU040_A7_TRIPWIRE environment variable: absent from every
    // synth / default GenVerilog build (the `if` is Scala, so nothing is elaborated), which
    // is why it is not under GenerationFlags.simulation -- the SoC's GenSocketTopVerilog does
    // not enable includeSimulation, and the tripwire must run in that Verilator flow.
    // Watches the COMMITTED active-bank A7 (`exc.ss.a7`: the live PRF readback of arch-15,
    // the same value the exception FSM sizes frames from) and prints, on every EVEN->ODD and
    // ODD->EVEN transition, the last 8 retired instruction PCs (r = normal retire, x =
    // exception/RTE pseudo-commit), the SR system byte and a running retire count. A 68k
    // stack pointer must never be odd, so an EVEN->ODD print names the instruction (or the
    // exception sequence) that broke it; the ODD->EVEN print pairs with it so a transient
    // (e.g. the documented sysOp readback garbage) can be told from a persistent drift.
    // `report` lowers to a `$display` under `ifndef SYNTHESIS`, so even a build that set
    // the variable by accident would synthesize to nothing but the ring + counter.
    // ── A7-ODD HALT LANE (synthesizable; 2026-09-09, the boot odd-SSP defect) ──────────
    // Hardware counterpart of the sim tripwire below. Watches the COMMITTED active-bank
    // A7 (`exc.ss.a7`). While it is odd, `run` counts retired macros; once `run` reaches
    // the host-programmed threshold (OFF_A7ODD_CTL[31:16]) with the lane enabled, a sticky
    // stop request is raised and the core halts on the next clean macro boundary with
    // reason A7_ODD. At every EVEN->ODD edge the lane latches the PC retiring in that
    // cycle (0 when none), the two PCs retired before it, and the odd A7 -- so the halt
    // shows exactly which instruction produced the odd stack pointer, even when the
    // threshold delayed the stop. The lane re-arms only after A7 has been even again, so
    // resuming from the halt (A7 still odd) does not re-trip immediately.
    val a7OddLane = new Area {
      val a7       = exc.ss.a7
      val odd      = a7(0)
      val oddPrev  = RegNext(odd) init False
      val last0    = Reg(UInt(32 bits)) init 0   // newest retired PC (registered)
      val last1    = Reg(UInt(32 bits)) init 0
      when(retire0 && retire1) { last0 := p1.pc; last1 := p0.pc }
        .elsewhen(retire0)     { last0 := p0.pc; last1 := last0 }
      val pcNow    = Mux(retire1, p1.pc, Mux(retire0, p0.pc, U(0, 32 bits)))
      val run      = Reg(UInt(16 bits)) init 0
      when(!odd) { run := 0 }
        .elsewhen(run < U(0xFFF0, 16 bits)) { run := run + retiredThisCycle.resized }
      val pc0      = Reg(UInt(32 bits)) init 0
      val pc1      = Reg(UInt(32 bits)) init 0
      val pc2      = Reg(UInt(32 bits)) init 0
      val value    = Reg(UInt(32 bits)) init 0
      val episodes = Reg(UInt(16 bits)) init 0
      when(haltA7OddEnIn && odd && !oddPrev) {
        pc0 := pcNow; pc1 := last0; pc2 := last1; value := a7
        episodes := episodes + 1
      }
      val armed    = RegInit(True)
      val req      = RegInit(False)
      val hit      = haltA7OddEnIn && odd && armed && (run >= haltA7OddThreshIn) &&
                     !coreHalted && (debugHaltState === DebugHaltState.RUNNING)
      when(hit)  { req := True; armed := False }
      when(!odd) { armed := True }
      when((debugHaltState === DebugHaltState.HALTED) || debugClearStickyIn || !haltA7OddEnIn) {
        req := False
      }
      pc0.simPublic(); pc1.simPublic(); pc2.simPublic(); value.simPublic()
      episodes.simPublic(); run.simPublic(); req.simPublic(); hit.simPublic()
    }
    a7OddStopReq := a7OddLane.req

    // ── PC-RANGE HALT LANE (2026-09-09) ────────────────────────────────────────────
    // Halts the core the moment a macro RETIRES with its PC inside a host-programmed
    // window. The boot defect ends with a wild jump into DRAM the OS never allocated
    // (executing memory-test filler at 9-12 MB while the OS heap ends near 8 MB); the
    // filler addresses differ every boot, so an exact-match breakpoint cannot catch it
    // but a RANGE can. The stop rides the same MANUAL-stop path as the A7 lane, so the
    // halt lands on a clean macro boundary with the retire-PC ring still holding the
    // instructions that jumped there.
    val pcRangeLane = new Area {
      val hit0     = retire0 && (p0.pc >= haltPcRangeLoIn) && (p0.pc <= haltPcRangeHiIn)
      val hit1     = retire1 && (p1.pc >= haltPcRangeLoIn) && (p1.pc <= haltPcRangeHiIn)
      val hit      = haltPcRangeEnIn && (hit0 || hit1)
      val hitPc    = Mux(hit0, p0.pc, p1.pc)
      val last0    = Reg(UInt(32 bits)) init 0
      val last1    = Reg(UInt(32 bits)) init 0
      when(retire0 && retire1) { last0 := p1.pc; last1 := p0.pc }
        .elsewhen(retire0)     { last0 := p0.pc; last1 := last0 }
      val pc0      = Reg(UInt(32 bits)) init 0
      val pc1      = Reg(UInt(32 bits)) init 0
      val pc2      = Reg(UInt(32 bits)) init 0
      val count    = Reg(UInt(16 bits)) init 0
      val seen     = RegInit(False)
      when(hit && !seen) { pc0 := hitPc; pc1 := last0; pc2 := last1; seen := True }
      when(hit && count < U(0xFFFF, 16 bits)) { count := count + 1 }
      val req      = RegInit(False)
      when(hit && !coreHalted && (debugHaltState === DebugHaltState.RUNNING)) { req := True }
      when(debugClearStickyIn || !haltPcRangeEnIn) { req := False; seen := False }
      pc0.simPublic(); pc1.simPublic(); pc2.simPublic(); count.simPublic()
      req.simPublic(); hit.simPublic()
    }
    pcRangeStopReq := pcRangeLane.req

    if (sys.env.contains("CPU040_A7_TRIPWIRE")) new Area {
      val a7      = exc.ss.a7
      val odd     = a7(0)
      val oddPrev = RegNext(odd) init False
      val ringPc  = Vec.fill(8)(Reg(UInt(32 bits)) init 0)
      val ringX   = Vec.fill(8)(Reg(Bool()) init False)
      val retired = Reg(UInt(48 bits)) init 0
      val hits    = Reg(UInt(16 bits)) init 0
      retired := retired + retiredThisCycle.resized
      // Shift the ring by however many instructions retired this cycle (slot 0 is the
      // oldest of a pair, so it lands deeper). An exception/RTE commit (obsFire) never
      // coincides with a normal retire (the ROB is drained), so its own shift is safe.
      when(retire0 && retire1) {
        for (i <- 7 downto 2) { ringPc(i) := ringPc(i - 2); ringX(i) := ringX(i - 2) }
        ringPc(1) := p0.pc; ringX(1) := False
        ringPc(0) := p1.pc; ringX(0) := False
      } elsewhen(retire0) {
        for (i <- 7 downto 1) { ringPc(i) := ringPc(i - 1); ringX(i) := ringX(i - 1) }
        ringPc(0) := p0.pc; ringX(0) := False
      }
      when(exc.obsFire) {
        for (i <- 7 downto 1) { ringPc(i) := ringPc(i - 1); ringX(i) := ringX(i - 1) }
        ringPc(0) := exc.obsPc; ringX(0) := True
      }
      // ODD-EPISODE TRACE: while A7 is odd (the ROM legitimately runs short odd-SSP stretches
      // and a real 68040 undoes them exactly), print every retired instruction PC and every
      // change of the committed A7, so the one instruction whose A7 update differs from the
      // architecture can be read straight off the log. Capped per elaboration.
      val cyc     = Reg(UInt(48 bits)) init 0
      cyc := cyc + 1
      val a7Prev  = RegNext(a7) init 0
      val traceN  = Reg(UInt(16 bits)) init 0
      val tracing = odd && traceN < 6000
      when(tracing && (retire0 || retire1 || exc.obsFire)) {
        traceN := traceN + 1
        report(Seq("[A7-TRACE] cyc=", cyc, " retire pc0=", p0.pc, " r0=", retire0, " pc1=", p1.pc, " r1=", retire1,
                   " excObs=", exc.obsFire, " excPc=", exc.obsPc, " a7=", a7))
      }
      when((odd || oddPrev) && (a7 =/= a7Prev) && traceN < 6000) {
        report(Seq("[A7-TRACE] cyc=", cyc, " A7 ", a7Prev, " -> ", a7, " srSys=", exc.ss.srSys))
      }
      when((odd ^ oddPrev) && hits < 64) {
        hits := hits + 1
        report(Seq(
          "[A7-TRIPWIRE] cyc=", cyc, " odd=", odd, " (1 = A7 just went ODD, 0 = back even) a7=", a7,
          " srSys=", exc.ss.srSys, " isp=", exc.ss.isp, " msp=", exc.ss.msp, " usp=", exc.ss.usp,
          " retired=", retired, " lastPcs(newest first):",
          " ", ringPc(0), ringX(0), " ", ringPc(1), ringX(1), " ", ringPc(2), ringX(2),
          " ", ringPc(3), ringX(3), " ", ringPc(4), ringX(4), " ", ringPc(5), ringX(5),
          " ", ringPc(6), ringX(6), " ", ringPc(7), ringX(7)))
      }
    }
  }

  override def trace     = logic.traceVec
  override def traceFire = logic.traceFireVec

  override def allocReady = logic.allocReadySig
  override def robId0     = logic.tail
  override def robId1     = logic.robId1Sig
  override def allocFire  = logic.allocFireSig
  override def allocUop   = logic.allocUopVec
  override def allocSlot1 = logic.allocSlot1Sig

  override def doFlush = logic.doFlushReg
  override def flushPc = logic.flushPcReg
  override def request(stop: Bool, resume: Bool, step: Bool, clearSticky: Bool): Unit = {
    logic.debugStopRequestIn := stop
    logic.debugResumeRequestIn := resume
    logic.debugStepRequestIn := step
    logic.debugClearStickyIn := clearSticky
  }
  override def configureHaltAfter(target: UInt, epoch: UInt, armed: Bool,
                                  invalidate: Bool): Unit = {
    logic.haltAfterTargetIn := target
    logic.haltAfterEpochIn := epoch
    logic.haltAfterArmedIn := armed
    logic.haltAfterInvalidateIn := invalidate
  }
  override def configureExceptionMask(mask: Bits): Unit = {
    logic.haltExceptionMaskIn := mask
  }
  override def configureA7OddHalt(enable: Bool, threshold: UInt): Unit = {
    logic.haltA7OddEnIn := enable
    logic.haltA7OddThreshIn := threshold
  }
  override def a7OddPc0:      UInt = logic.a7OddLane.pc0
  override def a7OddPc1:      UInt = logic.a7OddLane.pc1
  override def a7OddPc2:      UInt = logic.a7OddLane.pc2
  override def a7OddValue:    UInt = logic.a7OddLane.value
  override def a7OddEpisodes: UInt = logic.a7OddLane.episodes
  override def configurePcRangeHalt(enable: Bool, lo: UInt, hi: UInt): Unit = {
    logic.haltPcRangeEnIn := enable
    logic.haltPcRangeLoIn := lo
    logic.haltPcRangeHiIn := hi
  }
  override def pcRangePc0:   UInt = logic.pcRangeLane.pc0
  override def pcRangePc1:   UInt = logic.pcRangeLane.pc1
  override def pcRangePc2:   UInt = logic.pcRangeLane.pc2
  override def pcRangeCount: UInt = logic.pcRangeLane.count

  override def sr: UInt = logic.debugSystemSr
  override def vbr: UInt = logic.exc.ss.vbr
  override def usp: UInt = logic.exc.ss.usp
  override def msp: UInt = logic.exc.ss.msp
  override def isp: UInt = logic.exc.ss.isp
  override def cacr: UInt = logic.exc.ss.cacr
  override def sfc: UInt = logic.exc.ss.sfc
  override def dfc: UInt = logic.exc.ss.dfc
  override def tc: UInt = logic.debugSystemTc
  override def itt0: UInt = logic.mmuCtrl.itt0
  override def itt1: UInt = logic.mmuCtrl.itt1
  override def dtt0: UInt = logic.mmuCtrl.dtt0
  override def dtt1: UInt = logic.mmuCtrl.dtt1
  override def urp: UInt = logic.mmuCtrl.urp
  override def srp: UInt = logic.mmuCtrl.srp
  override def mmusr: UInt = logic.mmuCtrl.mmusr
  override def requestApply(cmd: Flow[DebugSystemApply]): Unit = {
    logic.debugSystemApplyIn.valid := cmd.valid
    logic.debugSystemApplyIn.payload := cmd.payload
  }

  override def macroRetirePc: Vec[Flow[UInt]] = logic.debugMacroRetirePc
  override def branchRetire: Flow[DebugBranchEvent] = logic.debugBranchRetire
  override def exceptionEntry: Flow[DebugExceptionEvent] = logic.debugExceptionEntry

  override def btbUpdate = logic.btbUpdateFlow
  override def gshareUpdate = logic.gshareUpdateFlow

  // `supervisor` (PrivilegeService) is implemented above via the `setup`-allocated
  // `_supervisor` wire — see the class-level comment near its declaration.
}
