package m68k040.rob

import m68k040.services.{RenameCommitService, CommitTraceService, RobAllocService, RedirectService, BtbUpdateService, BtbUpdate, GshareUpdateService, GshareUpdate, PrivilegeService, CacheControlService, FrontendQuiesceService}
import m68k040.rename.RenamedUop
import m68k040.types.CommitTrace
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

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
class RobPlugin extends FiberPlugin with CommitTraceService with RobAllocService with RedirectService with BtbUpdateService with GshareUpdateService with PrivilegeService with CacheControlService with FrontendQuiesceService {

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
  during setup {
    _supervisor             = Bool()
    _dcacheEnabled          = Bool()
    _frontendQuiesceActive  = Bool()
    _frontendQuiesceNext    = Bool()
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
    // (`isRteStore` folded into `payload.isRte` — LUT-reduction B1.)
    val faultVecStore = Vec.fill(depth)(RegInit(U(0, 8 bits)))
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
    val sysValStore     = Vec.fill(depth)(Reg(Bits(32 bits)))
    // The EU's `completion` port (marks `completes`) fires ONE cycle BEFORE its `wbObs`
    // (the value, captured into sysValStore via ccrCompletion). So a write-direction
    // sysOp head could `sysRetire` (gated on completes) before its VALUE lands -> the FSM
    // would latch a STALE sysValStore. `sysValRdyStore` is set BY the ccrCompletion (same
    // cycle the value lands); sysRetire gates on it so the write triggers only AFTER the
    // value is captured. RegInit(False), reset per-alloc (mirrors faultedStore).
    val sysValRdyStore  = Vec.fill(depth)(RegInit(False))
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
    val coreHalted = RegInit(False); coreHalted.simPublic()
    when(coreHaltedIn) { coreHalted := True }
    // MMU access-fault per-entry capture (set at COMPLETION from the LS EU's
    // faultCompletion, NOT at alloc — an MMU fault is discovered at execute). On a
    // faulting LS access the LS EU marks the entry faulted vector 2 + the faulting VA
    // + the SSW access attrs {write, sizeBits, supervisor}; the exception FSM stacks
    // the format-$7 frame from these. RegInit Vecs, reset per-alloc (mirrors
    // faultedStore) so a re-used index never carries a stale MMU fault.
    val faultAddrStore = Vec.fill(depth)(RegInit(U(0, 32 bits)))
    val faultWrStore   = Vec.fill(depth)(RegInit(False))
    // RegInit(2) = our LsFault.sizeBits "LONG" encoding, which ExceptionUnit's SSW
    // builder translates to SSW.SIZE=00 ("long") — the SAME bit pattern the SSW's
    // SIZE field always read before task #189 wired it up (it was simply never
    // populated, always reading 0b00). Keeps any vector-2 fault that DOESN'T flow
    // through LsEuPlugin's captureFault() (i.e. never writes this Vec) — e.g. a
    // static alloc-time / ITLB-sourced vector-2 fault — byte-for-byte unchanged
    // from pre-task-189 behavior instead of picking up a new SIZE value nobody
    // ever computed for it.
    val faultSizeStore = Vec.fill(depth)(RegInit(U(2, 2 bits)))
    val faultSupStore  = Vec.fill(depth)(RegInit(False))
    // Task #189: True = MMU/ATC-detected (DTLB) fault, False = plain physical bus
    // error (SLVERR/DECERR). RegInit(True) so a re-used index defaults to the
    // pre-existing (MMU) behavior unless a NEW fault explicitly clears it — see
    // LsFault.atc / LsEuPlugin's captureFault(atc=...).
    val faultAtcStore  = Vec.fill(depth)(RegInit(True))
    // Instruction-fetch access-fault: set at ALLOC for a faulted (vector-2) µop whose
    // fault came from the I-cache (sswInstr). Selects a program-space SSW in the $7
    // frame. RegInit(False), reset per-alloc (mirrors faultedStore).
    val faultInstrStore = Vec.fill(depth)(RegInit(False))
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
    val nzvcValStore = Vec.fill(depth)(Reg(UInt(4 bits)))
    val nzvcWrStore  = Vec.fill(depth)(RegInit(False))
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
      p.needsSup   := u.needsSupervisor
      p.sysOp      := u.sysOp
      p.sysKind    := u.sysKind
      p.sysReadDir := u.sysReadDir
      p.sysRc      := u.imm(11 downto 0).asUInt
      // ROB-fold Slice A: the 1-bit fault-PC selector (replaces faultPcStore).
      p.faultUsesNextPc := u.faultUsesNextPc
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
    // Commit-time mispredict redirect is a REGISTERED pulse (declared here so the
    // retire guards can gate on it). `flushing` = test flush OR the registered
    // redirect pulse; it drives ONLY pointer/reg resets (no combinational fanout).
    val doFlushReg = RegInit(False); doFlushReg.simPublic()
    val flushPcReg = Reg(UInt(32 bits)); flushPcReg.simPublic()
    // Exception squash: high on the entry/RTE trigger cycle AND while the FSM runs
    // (serializing — keep younger work squashed + block alloc/retire). Driven after
    // the exc unit is built; forward-declared so `flushing` can gate on it.
    val excSquash = Bool()
    val flushing   = flush.valid || doFlushReg || excSquash

    // The head is a faulted µop ready to retire -> take the exception INSTEAD of a
    // normal commit (precise: the faulting instruction does not commit its result).
    // An RTE head is serializing too: it triggers the exception-return FSM and does
    // NOT drive a normal int/flag commit. The exception FSM (exc) gates these so a
    // trigger fires once per event (excIdle).
    val excIdle = Bool()   // driven below from exc.active
    // Committed S (supervisor) bit — the SAME wire as the PrivilegeService `_supervisor`
    // pre-allocated in `setup` (see the class-level comment above); FORWARD-DECLARED
    // here too (the privilege check below gates on it) and DRIVEN from exc.ss.s after
    // the exc unit is built.
    val committedS = _supervisor; committedS.simPublic()
    val headReady   = (count > 0) && completes(h0) && !flushing && !coreHalted
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
    val retire0 = headReady && !faultedStore(h0) && !p0.isRte && !p0.sysOp &&
                  !interruptPending && !privViolation && !stopped && !tracePendingFire
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
                  !h0TraceArmed && !h0PreciseCompletedSticky

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
    // LS MMU access-fault completion: mark the entry FAULTED (vector 2) + record the
    // faulting VA + SSW attrs. The faulting instruction's PC is already captured per
    // entry at alloc (payload.pc/predNextPc + the faultUsesNextPc selector — Slice A),
    // so the $7 frame's PC field is available. Placed
    // with the other completion marks (BEFORE the alloc-reset) so alloc wins on a
    // re-used index. completes is set by the LS EU's normal completion port too (the
    // faulted access still completes so the entry can retire + trigger the exception).
    // ── FMax "LS/ROB Lever C": per-entry write-select hoist ──────────────────
    // (docs/superpowers/specs/2026-08-08-fmax-lsrob-leverc-writeenable-flatten-design.md,
    //  docs/superpowers/plans/2026-08-08-fmax-lsrob-leverc-writeenable-flatten-plan.md)
    //
    // SpinalHDL ALREADY elaborates `when(p.valid){ store(p.robId) := d }` into
    // exactly `for i: if(p.valid && oneHot(p.robId)(i)) store(i) <= d` (the emitted
    // `_zz_ = 1 <<< robId` + per-entry `if(_zz[i])` bodies). Writing that form out
    // by hand is therefore BIT-IDENTICAL — same boolean function, same signals,
    // same cycle, same last-assign priority. Nothing is registered; no latency
    // changes; ports #1/#2 keep INDEPENDENT selects so two different robIds can
    // still be written the same cycle (the reason the two ports exist at all, see
    // the port-declaration comment at the top of this plugin).
    //
    // The point of writing it out is the `keep` attribute. Without it the tool
    // absorbs `p.valid` directly into every per-bit data mux, so the single LATE
    // node ends up with fanout 2084 on the routed netlist. Load decomposition of
    // that net: 2019 of 2083 loads are `faultAddrStore` (64x32) per-bit DATA
    // muxes, only 64 are the per-entry write-ENABLE decode that sits on the
    // critical path — i.e. the enable path pays a 0.671 ns route hop it does not
    // cause, because it shares a driver with a 2019-load data-mux array. The
    // named+kept per-entry select gives the data muxes a LOCAL (fanout ~33) node
    // to consume instead.
    //
    // Textual order is load-bearing: the ls loop must stay before the sq loop, and
    // both before euFaultCompletion / alloc0 / alloc1, so SpinalHDL's last-assign
    // priority (alloc1 > alloc0 > eu > sq > ls) is bit-for-bit unchanged.
    val lsFaultOh  = UIntToOh(lsFaultCompletion.payload.robId, depth)
    val sqFaultOh  = UIntToOh(sqFaultCompletion.payload.robId, depth)
    val lsFaultSel = Vec(Bool(), depth)
    val sqFaultSel = Vec(Bool(), depth)
    for (i <- 0 until depth) {
      lsFaultSel(i) := lsFaultCompletion.valid && lsFaultOh(i)
      sqFaultSel(i) := sqFaultCompletion.valid && sqFaultOh(i)
      lsFaultSel(i).setName(s"lsFaultSel_$i").addAttribute("keep", "true")
      sqFaultSel(i).setName(s"sqFaultSel_$i").addAttribute("keep", "true")
    }
    for (i <- 0 until depth) {
      when(lsFaultSel(i)) {
        faultedStore(i)   := True
        faultVecStore(i)  := U(2, 8 bits)  // access fault
        faultAddrStore(i) := lsFaultCompletion.payload.faultAddr
        faultWrStore(i)   := lsFaultCompletion.payload.write
        faultSizeStore(i) := lsFaultCompletion.payload.sizeBits
        faultSupStore(i)  := lsFaultCompletion.payload.supervisor
        faultAtcStore(i)  := lsFaultCompletion.payload.atc
        // A DATA (LS) access fault is data-space, NEVER an instruction fetch — clear the
        // SSW-instr bit explicitly so it does not inherit the alloc'd µop's sswInstr
        // (which is only meaningful for I-fetch-fault µops). Without this the SSW
        // data/program bit was seed-flaky (the µop's unset sswInstr randomized).
        faultInstrStore(i) := False
      }
    }
    // SQ precise-path drain fault (Task P2.4): identical treatment to lsFaultCompletion
    // above, a second independent port so an older drained store's bus error and a
    // younger in-flight access's translate-time MMU fault can both land the same
    // cycle. Placed BEFORE the alloc-reset (alloc wins on a re-used index).
    for (i <- 0 until depth) {
      when(sqFaultSel(i)) {
        faultedStore(i)   := True
        faultVecStore(i)  := U(2, 8 bits)
        faultAddrStore(i) := sqFaultCompletion.payload.faultAddr
        faultWrStore(i)   := sqFaultCompletion.payload.write
        faultSizeStore(i) := sqFaultCompletion.payload.sizeBits
        faultSupStore(i)  := sqFaultCompletion.payload.supervisor
        faultAtcStore(i)  := sqFaultCompletion.payload.atc
        faultInstrStore(i):= False
      }
    }
    // Execute-time conditional fault (TRAPV / CHK / DIV0 / address-error task #189):
    // flip the entry FAULTED + the CARRIED vector. faultPc is already the µop's own
    // pc or nextPc (selected at the exceptionPc read site via payload.faultUsesNextPc,
    // captured at alloc, per-op — see MicroOpAssembler), so the format-$2 frame stacks
    // the right PC; the PPC/
    // ADDRESS field is payload.pc for the PPC-style traps (TRAPV/CHK/DIV0) OR the
    // execute-time faultAddr (odd target) for address error — see faultAddrStore
    // below + ExceptionUnit's `entryVector === 3` mux. The entry also completes via
    // its normal completion port (so it can retire + trigger the exception). Placed
    // BEFORE alloc-reset (alloc wins on a re-used index). NOT an instruction-fetch
    // fault -> clear the SSW-instr bit.
    when(euFaultCompletion.valid) {
      faultedStore(euFaultCompletion.payload.robId)    := True
      faultVecStore(euFaultCompletion.payload.robId)   := euFaultCompletion.payload.vector
      faultInstrStore(euFaultCompletion.payload.robId) := False
      // Only meaningful for vector 3 (address error) — ExceptionUnit only reads
      // entryFaultAddr for the is2 frame when entryVector===3. Harmless (unused) 0
      // for TRAPV/CHK/DIV0.
      faultAddrStore(euFaultCompletion.payload.robId)  := euFaultCompletion.payload.faultAddr
      // The fault PC is already the µop's own pc/nextPc (payload capture at alloc +
      // the faultUsesNextPc read-site mux, Slice A) — no write.
    }
    // FP enabled-trap escalation (vectors 49-54): identical treatment to euFaultCompletion
    // above, on its own port. Also placed BEFORE alloc-reset so alloc wins on a re-used
    // index. faultAddr is unused for these (the vector-3 address-error case is the only
    // entryFaultAddr reader) and the EU drives 0.
    when(fpFaultCompletion.valid) {
      faultedStore(fpFaultCompletion.payload.robId)    := True
      faultVecStore(fpFaultCompletion.payload.robId)   := fpFaultCompletion.payload.vector
      faultInstrStore(fpFaultCompletion.payload.robId) := False
      faultAddrStore(fpFaultCompletion.payload.robId)  := fpFaultCompletion.payload.faultAddr
    }

    when(alloc0) {
      payload.write(tail, payloadFrom(allocUopVec(0)))
      completes(tail)       := False
      mispredictStore(tail) := False
      branchTakenStore(tail) := False
      btbIsBranchStore(tail) := False
      phtValidStore(tail)   := False
      faultedStore(tail)  := allocUopVec(0).faulted
      faultVecStore(tail) := allocUopVec(0).faultVector
      // (fault PC: payload.faultUsesNextPc selects pc/nextPc at the read site — Slice A.)
      faultWrStore(tail)  := False; faultSupStore(tail) := False
      // Instruction-fetch fault: capture the fetch PC as the EA + the SSW-instr bit.
      faultAddrStore(tail)  := allocUopVec(0).faultAddr
      faultInstrStore(tail) := allocUopVec(0).sswInstr
      // Task #211: explicit per-alloc write (mirrors faultInstrStore) so a REUSED
      // index never inherits a stale ATC bit left behind by an earlier LS bus-fault
      // occupant of this same slot — the RegInit(True) default alone only covered a
      // never-yet-written slot, not a reused one.
      faultAtcStore(tail)   := allocUopVec(0).faultAtc
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
      faultVecStore(tail + 1) := allocUopVec(1).faultVector
      faultWrStore(tail + 1)  := False; faultSupStore(tail + 1) := False
      faultAddrStore(tail + 1)  := allocUopVec(1).faultAddr
      faultInstrStore(tail + 1) := allocUopVec(1).sswInstr
      faultAtcStore(tail + 1)   := allocUopVec(1).faultAtc
      nzvcWrStore(tail + 1) := False; xWrStore(tail + 1) := False
      sysValRdyStore(tail + 1)  := False
      GenerationFlags.simulation { pcStore(tail + 1) := allocUopVec(1).pc }
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
    val branchRedirect = retire0 && p0.retireAlone && mispredictStore(h0)

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
    val exceptionVector  = UInt(8 bits);  exceptionVector := Mux(privVec8, U(8, 8 bits),  faultVecStore(h0)); exceptionVector.simPublic()
    // Fault PC (ROB-fold Slice A): re-derive the old faultPcStore content from the
    // payload Mem — Mux(faultUsesNextPc, predNextPc, pc), the exact expression the
    // deleted array captured at alloc (same sources, same cycle, same address).
    val exceptionPc      = UInt(32 bits); exceptionPc     := Mux(privVec8, p0.pc,    Mux(p0.faultUsesNextPc, p0.predNextPc, p0.pc));  exceptionPc.simPublic()
    // Access-fault (vector 2) extras for the format-$7 frame: the faulting VA + the
    // SSW access attrs {write, sizeBits, supervisor}. Meaningful only when the head's
    // vector is 2; the exception FSM selects the $7 path on the vector.
    val exceptionFaultAddr = UInt(32 bits); exceptionFaultAddr := faultAddrStore(h0); exceptionFaultAddr.simPublic()
    val exceptionFaultWr   = Bool();        exceptionFaultWr   := faultWrStore(h0);   exceptionFaultWr.simPublic()
    val exceptionFaultSize = UInt(2 bits);  exceptionFaultSize := faultSizeStore(h0); exceptionFaultSize.simPublic()
    val exceptionFaultSup  = Bool();        exceptionFaultSup  := faultSupStore(h0);  exceptionFaultSup.simPublic()
    val exceptionFaultInstr= Bool();        exceptionFaultInstr:= faultInstrStore(h0);exceptionFaultInstr.simPublic()
    // Task #189: ATC bit source (True=MMU/ATC fault, False=plain bus error) — was
    // previously hardcoded True unconditionally in ExceptionUnit (see its SSW-
    // builder comment); now genuinely per-fault.
    val exceptionFaultAtc  = Bool();        exceptionFaultAtc  := faultAtcStore(h0); exceptionFaultAtc.simPublic()

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
        override def urp = U(0, 32 bits)
        override def srp = U(0, 32 bits)
        override def itt0 = U(0, 32 bits)
        override def itt1 = U(0, 32 bits)
        override def dtt0 = U(0, 32 bits)
        override def dtt1 = U(0, 32 bits)
        override def mmusr = U(0, 32 bits)
        override def setEnable = { val f = Flow(Bool()); f.valid := False; f.payload := False; f }
        override def setUrp    = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setSrp    = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setItt0   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setItt1   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setDtt0   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setDtt1   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
        override def setMmusr  = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
      })
    val exc = new m68k040.exception.ExceptionUnit(
      ss = new m68k040.exception.SystemState,
      mmuCtrl = mmuCtrl,
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
      entryFaultAddr = faultAddrStore(h0),
      entryFaultWr   = faultWrStore(h0),
      entryFaultSup  = faultSupStore(h0),
      entryFaultInstr= faultInstrStore(h0),
      // Task #189: SIZE field + ATC bit — both now genuinely threaded (were
      // previously computed here but the SIZE one was never passed at all, and
      // ATC was hardcoded true in ExceptionUnit; see that file's SSW-builder).
      entryFaultSize = faultSizeStore(h0),
      entryFaultAtc  = faultAtcStore(h0),
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
      sysRc      = p0.sysRc,
      sysDstPhys = p0.intNew,        // the read µop's rename-allocated pdst (FSM writes it)
      sysPc      = p0.pc,
      sysNextPc  = p0.predNextPc)
    excIdle := !exc.active
    val excActive = exc.active; excActive.simPublic()
    // Drive the forward-declared committed-S (the privilege check gates on it).
    committedS := exc.ss.s
    _dcacheEnabled := exc.ss.cacr(31)
    // The commit-time system op's S=1 vs S=0 split (needs exc.ss.s): S=1 supervisor ->
    // drive the S_APPLY FSM (sysTrigger); S=0 user -> a vector-8 privilege fault.
    sysTriggerSig := sysRetire && exc.ss.s
    sysPrivFault  := sysRetire && !exc.ss.s
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
    when(sysTriggerSig && p0.sysReadDir) {
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
    // Normal recognition: a first-µop non-faulted/non-RTE/non-sysOp head is present. When
    // STOPPED the ROB is empty (count==0, no head) and the IRQ must wake the halted core
    // with no head present, so OR in `stopped` as a recognition gate.
    val normalIrqGate = (count > 0) && p0.first && !faultedStore(h0) &&
                        !p0.isRte && !privViolation && !p0.sysOp &&
                        !preciseDrainBusyIn
    // A halted core (Task P4.5) recognizes no interrupt -- deliberately NOT
    // wakeable, matching the design doc's decision (unlike `stopped`, which IS
    // interrupt-wakeable).
    interruptPending := (normalIrqGate || stopped) && !flushing && excIdle && iplActive && !coreHalted
    // Priority-rule invariant (design doc §4.1/§5 item 8): interruptPending can only
    // go true when normalIrqGate held (which now requires !preciseDrainBusyIn), so a
    // LAUNCHED precise drain must never coexist with a newly-recognized interrupt at
    // the SAME head. This assert exists purely to catch a future edit that loosens
    // normalIrqGate's preciseDrainBusyIn term.
    // Explicit `FAILURE` severity -- see M68kSim.scala for why `.includeSimulation`
    // must also be set on the enclosing SpinalConfig for this block to elaborate at
    // all (without it, `GenerationFlags.simulation { ... }` is silently skipped).
    GenerationFlags.simulation {
      assert(!(interruptPending && preciseDrainBusyIn),
        "RobPlugin: interruptPending recognized while a precise SQ drain was in flight",
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
    _frontendQuiesceActive := stopped || coreHalted
    _frontendQuiesceNext := stoppedNext || coreHaltedNext
    _frontendQuiesceActive.simPublic()
    _frontendQuiesceNext.simPublic()

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
                          !preciseDrainBusyIn
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
    doFlushReg := branchRedirect || exc.redirectValid
    when(branchRedirect)    { flushPcReg := nextPcRd0 }   // Slice B: shared h0 read port
    when(exc.redirectValid) { flushPcReg := exc.redirectPc }

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
    }
    val commitObs = Vec(CommitObs(), 3); commitObs.simPublic()
    commitObs(0).fire := RegNext(retire0) init False; commitObs(0).robId := RegNext(h0); commitObs(0).pc := RegNext(commitPc0)
    commitObs(0).sysByte := RegNext(exc.ss.srSys); commitObs(0).a7 := RegNext(exc.ss.a7); commitObs(0).isInterrupt := False
    commitObs(0).ccrFold := 0; commitObs(0).ccrFoldValid := False
    commitObs(0).setCcr5 := 0; commitObs(0).setCcr5Valid := False
    commitObs(1).fire := RegNext(retire1) init False; commitObs(1).robId := RegNext(h1); commitObs(1).pc := RegNext(commitPc1)
    commitObs(1).sysByte := RegNext(exc.ss.srSys); commitObs(1).a7 := RegNext(exc.ss.a7); commitObs(1).isInterrupt := False
    commitObs(1).ccrFold := 0; commitObs(1).ccrFoldValid := False
    commitObs(1).setCcr5 := 0; commitObs(1).setCcr5Valid := False
    // Exception / RTE commit (handler-entry or restored PC + post-event sysByte/A7).
    // For a FAULT entry where the faulting head wrote flags (CHK), carry its NZVC fold
    // so the whitebox folds it (the faulting µop's Wb never retires normally).
    commitObs(2).fire := RegNext(exc.obsFire) init False; commitObs(2).robId := RegNext(h0)
    commitObs(2).pc := RegNext(exc.obsPc); commitObs(2).sysByte := RegNext(exc.obsSysByte); commitObs(2).a7 := RegNext(exc.obsA7)
    // Fold value held since the trigger (aligned with the late obsFire entry pulse).
    // Applied ONLY to a fault/trap ENTRY obs (obsIsEntry) that is not an interrupt and
    // whose faulting instruction wrote flags (CHK). RTE obs / interrupt entries carry
    // no fold (their running-CCR reconstruction is already correct).
    commitObs(2).ccrFold      := RegNext(heldCcrFold)
    commitObs(2).ccrFoldValid := RegNext(exc.obsFire && exc.obsIsEntry && !exc.obsIsInterrupt && heldCcrFoldValid) init False
    commitObs(2).isInterrupt := RegNext(exc.obsIsInterrupt) init False
    // MOVE-to-SR's absolute CCR write (registered alongside the obs pulse).
    commitObs(2).setCcr5      := RegNext(exc.obsSetCcr5)
    commitObs(2).setCcr5Valid := RegNext(exc.obsFire && exc.obsSetCcr5Valid) init False
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

  override def btbUpdate = logic.btbUpdateFlow
  override def gshareUpdate = logic.gshareUpdateFlow

  // `supervisor` (PrivilegeService) is implemented above via the `setup`-allocated
  // `_supervisor` wire — see the class-level comment near its declaration.
}
