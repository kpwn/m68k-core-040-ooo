package m68k040.rob

import m68k040.services.{RenameCommitService, CommitTraceService, RobAllocService, RedirectService}
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
class RobPlugin extends FiberPlugin with CommitTraceService with RobAllocService with RedirectService {

  /** One ROB entry's commit/free + trace payload. */
  case class RobPayload() extends Bundle {
    val predNextPc = UInt(32 bits)
    val archRegId  = UInt(5 bits)
    val intNew     = UInt(6 bits); val intOld = UInt(6 bits); val intWrite = Bool()
    val nzvcNew    = UInt(4 bits); val nzvcOld = UInt(4 bits); val nzvcWrite = Bool()
    val xNew       = UInt(4 bits); val xOld = UInt(4 bits); val xWrite = Bool()
    val retireAlone = Bool()
  }

  val logic = during build new Area {
    val rc = host[RenameCommitService]

    val depth  = 64
    val robIdW = log2Up(depth) // = 6, wraps naturally

    // ── Ring storage ────────────────────────────────────────────────────────
    val payload   = Mem(RobPayload(), depth)
    val completes = Vec.fill(depth)(RegInit(False))
    val head  = Reg(UInt(robIdW bits)) init 0
    val tail  = Reg(UInt(robIdW bits)) init 0
    val count = Reg(UInt(log2Up(depth + 1) bits)) init 0
    head.simPublic(); tail.simPublic(); count.simPublic()

    // ── External ports ────────────────────────────────────────────────────────
    // Plain directionless service wires: a sibling plugin (the EU wiring) DRIVES
    // completion(k).valid/payload; this ROB consumes them. Standalone tests poke
    // them in sim (simPublic). Mirrors the RenameCommitService.commitPorts wiring
    // convention (sibling-driven, directionless).
    // 3 completion ports: ALU0, ALU1, LS EU (sibling-driven, directionless).
    val completion = Vec.fill(3)(Flow(UInt(robIdW bits)))
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
    branchCompletion.simPublic()
    // mispredictStore MUST default False: a freshly-allocated branch entry is "not
    // yet known mispredicted" until its EU completion (branchCompletion) says so.
    // Without this, `doFlushReg := ... && mispredictStore(h0)` reads a stale/uninit
    // bit and can spuriously flush (a branch that completes via the normal port).
    // Reset per-alloc below (mirrors `completes`), with alloc-priority on a reused index.
    val mispredictStore = Vec.fill(depth)(RegInit(False))
    val nextPcStore     = Vec.fill(depth)(Reg(UInt(32 bits)))
    // Precise-fault per-entry capture — RegInit Vecs reset per-alloc (mirrors
    // mispredictStore). RegInit(False) guarantees a never-allocated / re-allocated
    // entry reads "not faulted / not RTE" deterministically (no uninit-Mem flake).
    val faultedStore  = Vec.fill(depth)(RegInit(False))
    val isRteStore    = Vec.fill(depth)(RegInit(False))
    val faultVecStore = Vec.fill(depth)(Reg(UInt(8 bits)))
    val faultPcStore  = Vec.fill(depth)(Reg(UInt(32 bits)))

    // ── Build a RobPayload from a RenamedUop ───────────────────────────────────
    def payloadFrom(u: RenamedUop): RobPayload = {
      val p = RobPayload()
      p.predNextPc := u.nextPc
      p.archRegId  := u.dstArch
      p.intNew     := u.pdst;     p.intOld := u.pdstOld;   p.intWrite  := u.pdstValid
      p.nzvcNew    := u.pNzvcDst; p.nzvcOld := u.pNzvcOld;  p.nzvcWrite := u.writesNzvc
      p.xNew       := u.pXDst;    p.xOld := u.pXOld;        p.xWrite    := u.writesX
      // A faulted µop AND an RTE retire ALONE (precise / serializing): they must be
      // the head and the only retirer this cycle. (faulted/isRte themselves live in
      // RegInit per-entry Vecs — see faultedStore/isRteStore — reset per-alloc like
      // mispredictStore, so an uninit Mem field can never spuriously trigger.)
      p.retireAlone := u.isBranch
      p
    }

    // ── Commit / retire (combinational, read at head) ──────────────────────────
    val h0 = head
    val h1 = head + 1
    val p0 = payload.readAsync(h0)
    val p1 = payload.readAsync(h1)
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
    val headReady   = (count > 0) && completes(h0) && !flushing
    val faultRetire = headReady && faultedStore(h0) && excIdle; faultRetire.simPublic()
    val rteRetire   = headReady && isRteStore(h0)   && excIdle; rteRetire.simPublic()
    val retire0 = headReady && !faultedStore(h0) && !isRteStore(h0)
    // A faulted/RTE entry at h1 must NOT commit in slot1 (it is serializing — it
    // retires alone when it reaches the head).
    val retire1 = retire0 && (count > 1) && completes(h1) && !p0.retireAlone && !p1.retireAlone &&
                  !faultedStore(h1) && !isRteStore(h1)

    val traceVec     = Vec(CommitTrace(), 2)
    val traceFireVec = Vec(Bool(), 2)

    // defaults
    for (k <- 0 until 2) {
      rc.commitPorts(k).valid := False
      rc.commitPorts(k).payload.assignDontCare()
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
    val commitPc0 = Mux(p0.retireAlone, nextPcStore(h0), p0.predNextPc)
    val commitPc1 = Mux(p1.retireAlone, nextPcStore(h1), p1.predNextPc)
    when(retire0) { driveCommit(0, p0, commitPc0) }
    when(retire1) { driveCommit(1, p1, commitPc1) }

    val retiredThisCycle = (retire1 ? U(2) | (retire0 ? U(1) | U(0))).resize(count.getWidth)

    // ── Passive alloc interface (driven by DispatchPlugin) ──────────────────────
    // Plain-wire service convention: the ROB EXPOSES these via RobAllocService;
    // the sibling DispatchPlugin DRIVES allocFireSig/allocUopVec/allocSlot1Sig
    // (do NOT default-drive them here — that would double-drive). allocReadySig /
    // robId1Sig are driven here (ROB produces them).
    val allocReadySig = Bool(); allocReadySig := count <= (depth - 2)
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
      nextPcStore(branchCompletion.payload.robId)     := branchCompletion.payload.nextPc
    }

    when(alloc0) {
      payload.write(tail, payloadFrom(allocUopVec(0)))
      completes(tail)       := False
      mispredictStore(tail) := False
      faultedStore(tail)  := allocUopVec(0).faulted
      isRteStore(tail)    := allocUopVec(0).isRte
      faultVecStore(tail) := allocUopVec(0).faultVector
      faultPcStore(tail)  := allocUopVec(0).pc
    }
    when(alloc1) {
      payload.write(tail + 1, payloadFrom(allocUopVec(1)))
      completes(tail + 1)       := False
      mispredictStore(tail + 1) := False
      faultedStore(tail + 1)  := allocUopVec(1).faulted
      isRteStore(tail + 1)    := allocUopVec(1).isRte
      faultVecStore(tail + 1) := allocUopVec(1).faultVector
      faultPcStore(tail + 1)  := allocUopVec(1).pc
    }
    when(allocFireSig) {
      tail := tail + Mux(allocSlot1Sig, U(2, robIdW bits), U(1, robIdW bits))
    }

    // ── Retire-side state update (head advance; validity is count-derived) ──────
    head := head + Mux(retire1, U(2, robIdW bits), Mux(retire0, U(1, robIdW bits), U(0, robIdW bits)))

    // ── count update (alloc + retire) ──────────────────────────────────────────
    count := count + allocThisCycle - retiredThisCycle

    // ── Commit-time mispredict redirect (REGISTERED pulse) ──────────────────────
    // When the retiring head is a mispredicting branch (retireAlone), register the
    // flush for next cycle. doFlushReg is the ONLY flush signal that fans out, and
    // it drives only pointer/reg resets (FMax: no combinational execute->flush path).
    // The exception FSM's final redirect (vector target / RTE restored PC) is ORed
    // into it below (after the exc unit is built).
    val branchRedirect = retire0 && p0.retireAlone && mispredictStore(h0)

    // ── Precise-fault exception-pending (combinational at faulted retire) ───────
    // When the head is a faulted µop ready to retire, signal an exception with its
    // vector + the FAULTING instruction's PC. The commit-side exception FSM consumes
    // this (squashes, stacks the frame, vectors). The faulted entry does NOT commit
    // (retire0 is gated `!p0.faulted`).
    val exceptionPending = Bool();    exceptionPending := faultRetire;       exceptionPending.simPublic()
    val exceptionVector  = UInt(8 bits);  exceptionVector := faultVecStore(h0); exceptionVector.simPublic()
    val exceptionPc      = UInt(32 bits); exceptionPc     := faultPcStore(h0);  exceptionPc.simPublic()

    // ── Committed CCR (X N Z V C, bits 4..0) — folded at retire, like the whitebox ─
    // Needed by the exception FSM to build the stacked SR low byte byte-for-byte.
    val committedCcr = RegInit(U(0, 5 bits)); committedCcr.simPublic()
    // slot0 then slot1 fold (slot1 is younger -> applied after slot0).
    val ccrAfter0 = UInt(5 bits)
    ccrAfter0 := committedCcr
    when(retire0 && p0.nzvcWrite) { ccrAfter0(3 downto 0) := p0.nzvcNew }
    when(retire0 && p0.xWrite)    { ccrAfter0(4)          := p0.xNew(0) }
    val ccrAfter1 = UInt(5 bits)
    ccrAfter1 := ccrAfter0
    when(retire1 && p1.nzvcWrite) { ccrAfter1(3 downto 0) := p1.nzvcNew }
    when(retire1 && p1.xWrite)    { ccrAfter1(4)          := p1.xNew(0) }
    committedCcr := ccrAfter1

    // ── Commit-side exception sequencer (entry FSM + RTE) ───────────────────────
    val exc = new m68k040.exception.ExceptionUnit(
      ss = new m68k040.exception.SystemState,
      entryTrigger = exceptionPending, entryVector = faultVecStore(h0), entryPc = faultPcStore(h0),
      rteTrigger   = rteRetire,        rtePc        = p0.predNextPc,
      committedCcr = committedCcr)
    excIdle := !exc.active
    val excActive = exc.active; excActive.simPublic()
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
    when(branchRedirect)    { flushPcReg := nextPcStore(h0) }
    when(exc.redirectValid) { flushPcReg := exc.redirectPc }

    // ── Flush (squash all in-flight) — pointer-only, driven by the registered ─────
    // redirect pulse OR the test flush port.
    when(flushing) {
      tail  := head
      count := 0
    }
    rc.flushPort := flushing

    // ── Sim-only commit observation (lock-step harness consumes this) ───────────
    case class CommitObs() extends Bundle { val fire = Bool(); val robId = UInt(robIdW bits); val pc = UInt(32 bits) }
    // Registered (sim-only) so the lock-step harness reading them in onSamplings
    // gets stable one-cycle pulses (reading combinational retire signals there races).
    val commitObs = Vec(CommitObs(), 2); commitObs.simPublic()
    commitObs(0).fire := RegNext(retire0) init False; commitObs(0).robId := RegNext(h0); commitObs(0).pc := RegNext(commitPc0)
    commitObs(1).fire := RegNext(retire1) init False; commitObs(1).robId := RegNext(h1); commitObs(1).pc := RegNext(commitPc1)
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
}
