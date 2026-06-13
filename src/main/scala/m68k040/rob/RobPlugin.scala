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
    val head  = Reg(UInt(robIdW bits)) init 0
    val tail  = Reg(UInt(robIdW bits)) init 0
    val count = Reg(UInt(log2Up(depth + 1) bits)) init 0
    head.simPublic(); tail.simPublic(); count.simPublic()

    // ── External ports ────────────────────────────────────────────────────────
    // Plain directionless service wires: a sibling plugin (the EU wiring) DRIVES
    // completion(k).valid/payload; this ROB consumes them. Standalone tests poke
    // them in sim (simPublic). Mirrors the RenameCommitService.commitPorts wiring
    // convention (sibling-driven, directionless).
    // 4 completion ports: ALU0, ALU1, LS EU, CPLX EU (DivEu) (sibling-driven).
    val completion = Vec.fill(4)(Flow(UInt(robIdW bits)))
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
    val faultVecStore = Vec.fill(depth)(RegInit(U(0, 8 bits)))
    // Commit-time PRIVILEGED SYSTEM ops (MOVE-to-SR / MOVE-USP / MOVEC): captured at
    // alloc (RegInit, reset per-alloc like faultedStore). `sysOpStore` = this entry is
    // a serializing system op; `sysKindStore` selects which; `sysReadDirStore` = read
    // SYSTEM->Rn vs write Rn->SYSTEM; `sysDstArchStore` = the Rn for a read (the FSM
    // writes the int PRF); `sysRcStore` = the 12-bit MOVEC control-reg id (from imm).
    // `sysValStore` = the captured source VALUE (wbObs.result) for a write direction.
    val sysOpStore      = Vec.fill(depth)(RegInit(False))
    val sysKindStore    = Vec.fill(depth)(RegInit(m68k040.decode.SysKind.NONE()))
    val sysReadDirStore = Vec.fill(depth)(RegInit(False))
    val sysDstArchStore = Vec.fill(depth)(RegInit(U(0, 5 bits)))
    val sysRcStore      = Vec.fill(depth)(RegInit(U(0, 12 bits)))
    val sysValStore     = Vec.fill(depth)(Reg(Bits(32 bits)))
    // The EU's `completion` port (marks `completes`) fires ONE cycle BEFORE its `wbObs`
    // (the value, captured into sysValStore via ccrCompletion). So a write-direction
    // sysOp head could `sysRetire` (gated on completes) before its VALUE lands -> the FSM
    // would latch a STALE sysValStore. `sysValRdyStore` is set BY the ccrCompletion (same
    // cycle the value lands); sysRetire gates on it so the write triggers only AFTER the
    // value is captured. RegInit(False), reset per-alloc (mirrors faultedStore).
    val sysValRdyStore  = Vec.fill(depth)(RegInit(False))
    val faultPcStore  = Vec.fill(depth)(RegInit(U(0, 32 bits)))
    // needsSupervisor per-entry (set at alloc from the µop): a PRIVILEGED op (MOVE-from-
    // SR). When such a head retires while the committed S bit is 0, the ROB converts it
    // to a faulted vector-8 (privilege violation, format-$0) entry — precise, like a
    // statically faulted head, but conditional on the runtime committed S. RegInit(False),
    // reset per-alloc (mirrors faultedStore).
    val needsSupStore = Vec.fill(depth)(RegInit(False))
    // MMU access-fault per-entry capture (set at COMPLETION from the LS EU's
    // faultCompletion, NOT at alloc — an MMU fault is discovered at execute). On a
    // faulting LS access the LS EU marks the entry faulted vector 2 + the faulting VA
    // + the SSW access attrs {write, sizeBits, supervisor}; the exception FSM stacks
    // the format-$7 frame from these. RegInit Vecs, reset per-alloc (mirrors
    // faultedStore) so a re-used index never carries a stale MMU fault.
    val faultAddrStore = Vec.fill(depth)(RegInit(U(0, 32 bits)))
    val faultWrStore   = Vec.fill(depth)(RegInit(False))
    val faultSizeStore = Vec.fill(depth)(RegInit(U(0, 2 bits)))
    val faultSupStore  = Vec.fill(depth)(RegInit(False))
    // Instruction-fetch access-fault: set at ALLOC for a faulted (vector-2) µop whose
    // fault came from the I-cache (sswInstr). Selects a program-space SSW in the $7
    // frame. RegInit(False), reset per-alloc (mirrors faultedStore).
    val faultInstrStore = Vec.fill(depth)(RegInit(False))
    // Interrupt-recognition per-entry capture (RegInit, reset per-alloc like the
    // fault Vecs). `firstStore` = the µop is the FIRST of a macro-instruction (an
    // interrupt may be taken only at such a head). `pcStore` = the head
    // INSTRUCTION's PC = the stacked PC for an interrupt (the not-yet-committed
    // instruction, re-executed after RTE). RegInit so a never/re-allocated index
    // reads deterministically.
    val firstStore = Vec.fill(depth)(RegInit(False))
    val pcStore    = Vec.fill(depth)(RegInit(U(0, 32 bits)))
    // LS access-fault completion (driven by the LS-cluster wiring, like
    // branchCompletion). Default-idle (allowOverride) so a standalone DUT elaborates.
    val lsFaultCompletion = Flow(m68k040.execute.LsFault())
    lsFaultCompletion.valid.allowOverride;            lsFaultCompletion.valid := False
    lsFaultCompletion.payload.robId.allowOverride;    lsFaultCompletion.payload.robId := U(0, robIdW bits)
    lsFaultCompletion.payload.faultAddr.allowOverride;lsFaultCompletion.payload.faultAddr := U(0, 32 bits)
    lsFaultCompletion.payload.write.allowOverride;    lsFaultCompletion.payload.write := False
    lsFaultCompletion.payload.sizeBits.allowOverride; lsFaultCompletion.payload.sizeBits := U(0, 2 bits)
    lsFaultCompletion.payload.supervisor.allowOverride; lsFaultCompletion.payload.supervisor := False
    lsFaultCompletion.simPublic()
    // Execute-time conditional fault completion (generalized; driven by the branch EU
    // for TRAPV and the div EU for CHK/DIV0). When an execute-time check raises a
    // synchronous group-2 trap the EU drives this with {robId, vector}; the ROB marks
    // the entry FAULTED + the carried vector. faultPc (= nextPc) and the PPC (=
    // instruction pc, pcStore) are already captured per-entry at alloc, so this only
    // flips faulted+vector. Default-idle (allowOverride) so a standalone DUT
    // elaborates; the EU-wiring OVERRIDES it. (Field name kept as `euFaultCompletion`.)
    val euFaultCompletion = Flow(m68k040.execute.EuFault())
    euFaultCompletion.valid.allowOverride;          euFaultCompletion.valid := False
    euFaultCompletion.payload.robId.allowOverride;  euFaultCompletion.payload.robId := U(0, robIdW bits)
    euFaultCompletion.payload.vector.allowOverride; euFaultCompletion.payload.vector := U(0, 8 bits)
    euFaultCompletion.simPublic()
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
    // Committed S (supervisor) bit — FORWARD-DECLARED (the privilege check below gates on
    // it); DRIVEN from exc.ss.s after the exc unit is built.
    val committedS = Bool(); committedS.simPublic()
    val headReady   = (count > 0) && completes(h0) && !flushing
    // Privilege violation: a needsSupervisor head retiring in USER mode (committed S==0)
    // takes a vector-8 (format-$0) exception. Treated like a faulted head — the op does
    // NOT commit its result (precise). Only meaningful when the head is otherwise ready.
    val privViolation = headReady && needsSupStore(h0) && !committedS && excIdle; privViolation.simPublic()
    // The head triggers an exception when it is a STATICALLY faulted head OR a privilege
    // violation. Both route through the same entry FSM (faultPcStore / faultVecStore are
    // overridden below for the privilege case).
    val faultRetire = headReady && (faultedStore(h0) || privViolation) && excIdle; faultRetire.simPublic()
    val rteRetire   = headReady && isRteStore(h0)   && excIdle; rteRetire.simPublic()
    // A commit-time PRIVILEGED SYSTEM op (MOVE-to-SR / MOVE-USP / MOVEC) at the head:
    // serializing (retires ALONE, like fault/RTE). It triggers either the system-op
    // FSM (S=1 supervisor) OR a vector-8 privilege fault (S=0 user) — resolved below
    // after the exc unit exposes ss.s. A faulted/RTE head takes priority over a sysOp
    // (a head can't be both: sysOps are never faulted/RTE at alloc).
    // Gated on sysValRdyStore so a WRITE-direction sysOp triggers only AFTER its source
    // VALUE has been captured (the EU's `completion` precedes its `wbObs` by one cycle).
    // The READ direction also waits one harmless extra cycle (its wbObs sets the flag too).
    val sysRetire   = headReady && sysOpStore(h0) && sysValRdyStore(h0) &&
                      !faultedStore(h0) && !isRteStore(h0) &&
                      excIdle; sysRetire.simPublic()

    // ── Interrupt recognition (precise, at a macro-instruction boundary) ─────────
    // Take an interrupt BETWEEN instructions when ALL hold:
    //   (a) iplIn > srSys[2:0] (the SR I-mask) OR iplIn == 7 (NMI always);
    //   (b) the ROB head is the FIRST µop of an instruction (firstStore(h0)) — never
    //       mid-cracked-instruction;
    //   (c) the head is NOT faulted / NOT RTE (its own exception/return has priority);
    //   (d) excIdle (the exc-FSM is not already running);
    //   (e) a head is present (count>0) and we are not flushing.
    // We do NOT require completes(h0): the interrupt PREEMPTS the head (it does not
    // commit — it re-executes after RTE). The stacked PC is the head INSTRUCTION's
    // PC (pcStore(h0)); the vector is the simple-protocol curVec computed below.
    // `interruptPending` is FORWARD-DECLARED here (retire0 gates on it) and DRIVEN
    // after the exc unit is built (it reads the SR I-mask from exc.ss.srSys).
    val interruptPending = Bool(); interruptPending.simPublic()
    val interruptLevel = UInt(3 bits); interruptLevel := iplIn; interruptLevel.simPublic()
    // Simple-protocol vector: autovector (24+level) or the vectored input.
    val interruptVec = UInt(8 bits)
    interruptVec := Mux(iackAvec, (U(24, 8 bits) + iplIn).resized, iackVector)
    interruptVec.simPublic()
    val interruptPc = UInt(32 bits); interruptPc := pcStore(h0); interruptPc.simPublic()

    // A normal retire is also blocked while an interrupt is pending (the head does
    // not commit — like the faulted-head case).
    // retire0 blocked by: a faulted/RTE/sysOp head (all serializing), a pending interrupt,
    // OR a privilege-violation head (Track C MOVE-from-SR in user mode). retire1 likewise
    // excludes a needsSupervisor (C) or sysOp (D) head from slot-1 (both serializing).
    val retire0 = headReady && !faultedStore(h0) && !isRteStore(h0) && !sysOpStore(h0) &&
                  !interruptPending && !privViolation
    val retire1 = retire0 && (count > 1) && completes(h1) && !p0.retireAlone && !p1.retireAlone &&
                  !faultedStore(h1) && !isRteStore(h1) && !needsSupStore(h1) && !sysOpStore(h1)

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
    // (A commit-time SYSTEM op READ commits its dst arch->pdst mapping at the trigger —
    // driven AFTER the exc unit is built, see `sysReadCommit` below, since the S=1
    // decision needs exc.ss.s.)

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
    // entry at alloc (faultPcStore), so the $7 frame's PC field is available. Placed
    // with the other completion marks (BEFORE the alloc-reset) so alloc wins on a
    // re-used index. completes is set by the LS EU's normal completion port too (the
    // faulted access still completes so the entry can retire + trigger the exception).
    when(lsFaultCompletion.valid) {
      faultedStore(lsFaultCompletion.payload.robId)   := True
      faultVecStore(lsFaultCompletion.payload.robId)  := U(2, 8 bits)  // access fault
      faultAddrStore(lsFaultCompletion.payload.robId) := lsFaultCompletion.payload.faultAddr
      faultWrStore(lsFaultCompletion.payload.robId)   := lsFaultCompletion.payload.write
      faultSizeStore(lsFaultCompletion.payload.robId) := lsFaultCompletion.payload.sizeBits
      faultSupStore(lsFaultCompletion.payload.robId)  := lsFaultCompletion.payload.supervisor
      // A DATA (LS) access fault is data-space, NEVER an instruction fetch — clear the
      // SSW-instr bit explicitly so it does not inherit the alloc'd µop's sswInstr
      // (which is only meaningful for I-fetch-fault µops). Without this the SSW
      // data/program bit was seed-flaky (the µop's unset sswInstr randomized).
      faultInstrStore(lsFaultCompletion.payload.robId) := False
    }
    // Execute-time conditional fault (TRAPV / CHK / DIV0): flip the entry FAULTED +
    // the CARRIED vector. faultPc is already the µop's nextPc (captured at alloc into
    // faultPcStore via faultUsesNextPc), so the format-$2 frame stacks the right PC;
    // the PPC is pcStore. The entry also completes via its normal completion port (so
    // it can retire + trigger the exception). Placed BEFORE alloc-reset (alloc wins on
    // a re-used index). NOT an instruction-fetch fault -> clear the SSW-instr bit.
    when(euFaultCompletion.valid) {
      faultedStore(euFaultCompletion.payload.robId)    := True
      faultVecStore(euFaultCompletion.payload.robId)   := euFaultCompletion.payload.vector
      faultInstrStore(euFaultCompletion.payload.robId) := False
      // faultPcStore is already the µop's nextPc (captured at alloc) — no write.
    }

    when(alloc0) {
      payload.write(tail, payloadFrom(allocUopVec(0)))
      completes(tail)       := False
      mispredictStore(tail) := False
      faultedStore(tail)  := allocUopVec(0).faulted
      isRteStore(tail)    := allocUopVec(0).isRte
      faultVecStore(tail) := allocUopVec(0).faultVector
      faultPcStore(tail)  := Mux(allocUopVec(0).faultUsesNextPc, allocUopVec(0).nextPc, allocUopVec(0).pc)
      faultWrStore(tail)  := False; faultSupStore(tail) := False
      // Instruction-fetch fault: capture the fetch PC as the EA + the SSW-instr bit.
      faultAddrStore(tail)  := allocUopVec(0).faultAddr
      faultInstrStore(tail) := allocUopVec(0).sswInstr
      firstStore(tail) := allocUopVec(0).firstOfInstr
      needsSupStore(tail) := allocUopVec(0).needsSupervisor
      pcStore(tail)    := allocUopVec(0).pc
      nzvcWrStore(tail) := False; xWrStore(tail) := False
      sysOpStore(tail)      := allocUopVec(0).sysOp
      sysKindStore(tail)    := allocUopVec(0).sysKind
      sysReadDirStore(tail) := allocUopVec(0).sysReadDir
      sysDstArchStore(tail) := allocUopVec(0).dstArch
      sysRcStore(tail)      := allocUopVec(0).imm(11 downto 0).asUInt
      sysValRdyStore(tail)  := False
    }
    when(alloc1) {
      payload.write(tail + 1, payloadFrom(allocUopVec(1)))
      completes(tail + 1)       := False
      mispredictStore(tail + 1) := False
      faultedStore(tail + 1)  := allocUopVec(1).faulted
      isRteStore(tail + 1)    := allocUopVec(1).isRte
      faultVecStore(tail + 1) := allocUopVec(1).faultVector
      faultPcStore(tail + 1)  := Mux(allocUopVec(1).faultUsesNextPc, allocUopVec(1).nextPc, allocUopVec(1).pc)
      faultWrStore(tail + 1)  := False; faultSupStore(tail + 1) := False
      faultAddrStore(tail + 1)  := allocUopVec(1).faultAddr
      faultInstrStore(tail + 1) := allocUopVec(1).sswInstr
      firstStore(tail + 1) := allocUopVec(1).firstOfInstr
      needsSupStore(tail + 1) := allocUopVec(1).needsSupervisor
      pcStore(tail + 1)    := allocUopVec(1).pc
      nzvcWrStore(tail + 1) := False; xWrStore(tail + 1) := False
      sysOpStore(tail + 1)      := allocUopVec(1).sysOp
      sysKindStore(tail + 1)    := allocUopVec(1).sysKind
      sysReadDirStore(tail + 1) := allocUopVec(1).sysReadDir
      sysDstArchStore(tail + 1) := allocUopVec(1).dstArch
      sysRcStore(tail + 1)      := allocUopVec(1).imm(11 downto 0).asUInt
      sysValRdyStore(tail + 1)  := False
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
    val exceptionPc      = UInt(32 bits); exceptionPc     := Mux(privVec8, pcStore(h0),    faultPcStore(h0));  exceptionPc.simPublic()
    // Access-fault (vector 2) extras for the format-$7 frame: the faulting VA + the
    // SSW access attrs {write, sizeBits, supervisor}. Meaningful only when the head's
    // vector is 2; the exception FSM selects the $7 path on the vector.
    val exceptionFaultAddr = UInt(32 bits); exceptionFaultAddr := faultAddrStore(h0); exceptionFaultAddr.simPublic()
    val exceptionFaultWr   = Bool();        exceptionFaultWr   := faultWrStore(h0);   exceptionFaultWr.simPublic()
    val exceptionFaultSize = UInt(2 bits);  exceptionFaultSize := faultSizeStore(h0); exceptionFaultSize.simPublic()
    val exceptionFaultSup  = Bool();        exceptionFaultSup  := faultSupStore(h0);  exceptionFaultSup.simPublic()
    val exceptionFaultInstr= Bool();        exceptionFaultInstr:= faultInstrStore(h0);exceptionFaultInstr.simPublic()

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
    val excEntryTrigger = exceptionPending || interruptPending
    // exceptionVector/exceptionPc already fold in the sysPrivFault (vector 8 + the sysOp
    // PC); a fault/trap uses them, an interrupt overrides with its own vector/PC.
    val excEntryVector  = Mux(interruptPending, interruptVec, exceptionVector)
    val excEntryPc      = Mux(interruptPending, interruptPc,  exceptionPc)
    val exc = new m68k040.exception.ExceptionUnit(
      ss = new m68k040.exception.SystemState,
      entryTrigger = excEntryTrigger, entryVector = excEntryVector, entryPc = excEntryPc,
      // PPC for a format-$2 group-2 trap (TRAPV/CHK/DIV0) = the trapping INSTRUCTION's
      // PC. pcStore(h0) holds the instruction PC (variable-length safe; entryPc-2 only
      // worked for the 2-byte TRAPV). Interrupts ignore entryPpc (format-$0).
      entryPpc     = pcStore(h0),
      rteTrigger   = rteRetire,        rtePc        = p0.predNextPc,
      committedCcr = ccrForException,
      // Access-fault (vector 2) extras for the format-$7 frame.
      entryFaultAddr = faultAddrStore(h0),
      entryFaultWr   = faultWrStore(h0),
      entryFaultSup  = faultSupStore(h0),
      entryFaultInstr= faultInstrStore(h0),
      entryIsInterrupt = interruptPending,
      entryIplLevel    = interruptLevel,
      // ── Commit-time SYSTEM op (supervisor): drive the S_APPLY FSM ──────────────
      // sysTriggerSig is forward-declared (the S=1 vs S=0 decision needs exc.ss.s,
      // available only after exc is built) and driven below. The captured context comes
      // straight from the head's per-entry sysOp stores + the captured value.
      sysTrigger = sysTriggerSig,
      sysKind    = sysKindStore(h0).asBits.asUInt.resize(2),
      sysReadDir = sysReadDirStore(h0),
      sysVal     = sysValStore(h0),
      sysRc      = sysRcStore(h0),
      sysDstPhys = p0.intNew,        // the read µop's rename-allocated pdst (FSM writes it)
      sysPc      = pcStore(h0),
      sysNextPc  = p0.predNextPc)
    excIdle := !exc.active
    val excActive = exc.active; excActive.simPublic()
    // Drive the forward-declared committed-S (the privilege check gates on it).
    committedS := exc.ss.s
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
    when(sysTriggerSig && sysReadDirStore(h0)) {
      rc.commitPorts(0).valid     := True
      rc.commitPorts(0).intArch   := p0.archRegId
      rc.commitPorts(0).intNew    := p0.intNew
      rc.commitPorts(0).intOld    := p0.intOld
      rc.commitPorts(0).intWrite  := p0.intWrite
      rc.commitPorts(0).nzvcWrite := False
      rc.commitPorts(0).xWrite    := False
    }

    // ── Drive interrupt recognition (needs the SR I-mask from exc.ss, built above) ─
    // iplIn > srSys[2:0] (mask) OR iplIn==7 (NMI), at a first-µop non-faulted/non-RTE
    // head, excIdle, head present, not flushing. (Forward-declared above so retire0
    // can gate on it.) A pending PRIVILEGE VIOLATION takes priority over an interrupt
    // (the head's own exception delivers first), so exclude it.
    val maskI = exc.ss.srSys(2 downto 0)
    val iplActive = (iplIn > maskI) || (iplIn === U(7, 3 bits))
    interruptPending := (count > 0) && !flushing && excIdle &&
                        firstStore(h0) && !faultedStore(h0) && !isRteStore(h0) &&
                        !privViolation && !sysOpStore(h0) &&
                        iplActive
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
}
