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
  // Dynamic load-wakeup broadcast: valid (with the produced pdst) the cycle a LOAD
  // completes and its data is in the PRF. Registered alongside the completion stage
  // so consumers do not have to reach into the (now-pipelined) internal s1 context.
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
  val robId      = UInt(6 bits)
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
class LsEuPlugin extends FiberPlugin with LsEuService {
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
  // FSAVE/FRESTORE's state-frame transfers are the first ones that are genuinely
  // VIRTUAL, so the same MUX now also hands over `xlate.req` — safe for exactly the
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
    robHeadIn              = UInt(6 bits)
    robHeadValidIn         = Bool()
    irqPreemptPendingIn    = Bool()
    sqCompletionPort       = Flow(UInt(6 bits))
    sqFaultCompletionPort  = Flow(LsFault())
    preciseDrainBusySig    = Bool()
    issuePort      = Stream(IqContext())
    issuePort.valid.simPublic(); issuePort.ready.simPublic(); issuePort.payload.robId.simPublic() // debug-only, task #139 finding #1; zero synth impact
    completionPort = Flow(UInt(6 bits))
    sqCommitPort   = Flow(UInt(6 bits))
    // Slot-1 commit: defaults to idle (allowOverride) so single-retire benches/stubs
    // need no wiring; the full-core wiring overrides it with {retire1, h1}.
    sqCommitBPort  = Flow(UInt(6 bits))
    sqCommitBPort.valid.allowOverride;   sqCommitBPort.valid   := False
    sqCommitBPort.payload.allowOverride; sqCommitBPort.payload := U(0, 6 bits)
    sqFlushSig     = Bool()
    wakeupPort     = Flow(UInt(6 bits))
    wakeupNzvcPort = Flow(UInt(4 bits))   // pNzvcDst of a completing NZVC-writing LS op
    faultCompletionPort = Flow(LsFault()); faultCompletionPort.simPublic()
    xlateRobIdSig  = UInt(6 bits)
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
    val dcache = host[DcacheService]
    val xlate  = host[DTranslationService]
    // The current architectural S bit (ROB-owned) — a normal LOAD/STORE's DTLB
    // request must reflect the ACTUAL current privilege level, not the hardcoded
    // `False` this slice previously used (a "slice-1 user-only simplification" — see
    // reqDrvSup below and the matching note in ExceptionUnit.scala). A supervisor-mode
    // data access to a supervisor-only page would otherwise fault. `host.get`
    // (optional): a standalone LS-EU DUT with no RobPlugin/PrivilegeService wired
    // defaults to False (user), unchanged for every existing non-full-core test. Does
    // It does not affect the exception sequencer's separate identity-physical
    // frame/vector accesses.
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

    // exc-arbitration inputs default-idle (allowOverride): a DUT that doesn't wire
    // the exception unit (standalone LS tests) sees excActive=False -> the LS EU
    // owns the cache exactly as before.
    excActive.allowOverride;            excActive := False
    excLoadCmdValid.allowOverride;      excLoadCmdValid := False
    excLoadCmdVaddr.allowOverride;      excLoadCmdVaddr := U(0, 32 bits)
    // Defaults to the vaddr (identity), NOT to zero: every DUT that wires the exception
    // unit's load command but not this new paddr pass-through keeps its pre-Task-11
    // identity-physical behaviour automatically, and a later override of
    // `excLoadCmdVaddr` is followed for free because this is a plain combinational alias.
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

    // Precise-path pass-throughs default-idle (allowOverride): a DUT that doesn't
    // wire the ROB (standalone LS tests) sees robHeadValidIn=False -> headPreciseReady
    // can never assert, matching the SQ's own pre-P2.5 dead-wired defaults.
    robHeadIn.allowOverride;           robHeadIn := U(0, 6 bits)
    robHeadValidIn.allowOverride;      robHeadValidIn := False
    irqPreemptPendingIn.allowOverride; irqPreemptPendingIn := False
    // Debug-only taps (zero synth impact, matches every other tap in this file):
    // a standalone LS-EU-only DUT (no real RobPlugin) needs to sim-poke these
    // directly to exercise the precise-drain path (e.g. the `liveCompletionFires`
    // collision-retry directed test in LsEuFastPreciseSpec).
    robHeadIn.simPublic(); robHeadValidIn.simPublic(); irqPreemptPendingIn.simPublic()

    // ---- store queue instance ----
    val sq = new StoreQueue(8)
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
    sq.io.drainAck := dcache.storeAck && !excStoreOutstanding
    sq.io.drainErr := dcache.storeErr && !excStoreOutstanding
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
    sqCompletionPort.payload := U(0, 6 bits)
    sqFaultCompletionPort.valid := False
    sqFaultCompletionPort.payload.assignDontCare()
    preciseDrainBusySig    := sq.io.preciseDrainBusy
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
    // The An write-back value for an auto-update µop (when it carries an int dst):
    //   PREDEC  -> s1Va (= base - eaDelta, the decremented An)
    //   POSTINC -> base + eaDelta
    val s1AnPost = (s1Base + u1.eaDelta).asBits
    val s1AnWb   = Mux(u1.eaAuto === m68k040.decode.EaAuto.POSTINC, s1AnPost, s1Va.asBits)
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

    // ── D1 elastic-front context ─────────────────────────────────────────────
    // Carry only fields which remain live after the AGU/register-file boundary.
    // The full 300+ bit RenamedUop stays in P1; P2/P3/P4 replicate this pruned token
    // instead, keeping the area cost of three simultaneously-resident accesses bounded.
    case class FrontPipeCtx() extends Bundle {
      val robId           = UInt(6 bits)
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
    tValid.simPublic(); txValid.simPublic(); p3Valid.simPublic(); p4Valid.simPublic(); txSecond.simPublic()
    tCtx.robId.simPublic(); txCtx.robId.simPublic(); p3Ctx.front.robId.simPublic(); p4Ctx.xlate.front.robId.simPublic()

    // ---- translate-at-execute: the LS EU DRIVES the D-side translation port ----
    // It presents the access VPN (loadVaddr, which the FSM sets to s1Va for slot A
    // or s1AddrB for slot B), the access class (write?=store, supervisor?), and
    // `valid` (a real demand). In parallel, a load may launch a tokenized D-cache
    // virtual-set probe. The LS EU alone consumes rsp, registers the resolved PA and
    // cache mode, and later presents them in DLoadCmd; the D-cache never samples this
    // tagged response. On a DTLB miss the response Stream remains invalid while the
    // walker runs; P2T holds the matching context and P2 may retain one younger op.
    // (request drivers are set after the stage controls are declared below.)
    val s1Paddr = (xlate.rsp.payload.ppn ## txCtx.vaddr(11 downto 0)).asUInt
    // Slot-B (split-access second half) translated physical address: SAME `xlate.rsp`
    // port, combined with addrB's OWN page offset (not s1Va's — a line-crossing split
    // stays within the same page but at a different 12-bit offset; only a page-
    // crossing split shares offset 0). Only meaningful the cycle the LIVE xlate
    // request/response actually corresponds to addrB's VPN (the new XLATE_B FSM
    // state below arms this via `xlateBArm` -> `xlateVaddr`, mirroring exactly how
    // `s1Paddr` above is only meaningful while IDLE is resolving slot A's request).
    val s1PaddrB = (xlate.rsp.payload.ppn ## txCtx.addrB(11 downto 0)).asUInt
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
    val llReg = new Area {
      val valid     = RegInit(False)
      val vaddr     = Reg(UInt(32 bits))
      val paddr     = Reg(UInt(32 bits))
      val addrB     = Reg(UInt(32 bits))
      val paddrB    = Reg(UInt(32 bits))
      val size      = Reg(m68k040.isa.Size())
      val cmode     = Reg(m68k040.cache.CacheMode())
      val cmodeB    = Reg(m68k040.cache.CacheMode())
      val robId     = Reg(UInt(6 bits))
      val twoAccess = RegInit(False)
      val bDone     = RegInit(False)   // slot A launched; now presenting slot B (cross)
    }

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
      val robId           = UInt(6 bits)
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
    val bkCtx      = Reg(BkCtx())
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
    // Split accesses remain on the small BK LAUNCH/WAIT_A/WAIT_B replay FSM and are
    // mutually exclusive with this queue.  They are rare and inherently two-pass;
    // the aligned hot path no longer enters a one-at-a-time state machine.
    case class AlignedLoadCtx() extends Bundle {
      val bk        = BkCtx()
      val vaddr     = UInt(32 bits)
      val paddr     = UInt(32 bits)
      val size      = m68k040.isa.Size()
      val cmode     = m68k040.cache.CacheMode()
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
    val alignedEnq      = Bool(); alignedEnq := False
    val alignedFull     = alignedCount === alignedDepth
    val alignedEmpty    = alignedCount === 0
    val alignedSendValid = !alignedEmpty && alignedValid(alignedSendPtr) &&
                           !alignedSent(alignedSendPtr) && !bkBusy && !excActive
    val alignedRspValid = !alignedEmpty && alignedValid(alignedRspPtr) &&
                          alignedSent(alignedRspPtr) && !bkBusy
    val alignedRspFire  = alignedRspValid && dcache.loadRsp.valid
    val alignedCanEnq   = !bkBusy && (!alignedFull || alignedRspFire)
    alignedCount.simPublic(); alignedFull.simPublic(); alignedEnq.simPublic()
    alignedSendValid.simPublic(); alignedRspFire.simPublic()
    // Observability for the arbitration tests: True the cycle a FRONT completion was
    // suppressed and held because the BACK claimed the shared comp* stage.
    val frontCompHeld = Bool(); frontCompHeld := False; frontCompHeld.simPublic()
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

    // ---- dcache load cmd: registered aligned queue or split replay register ----
    // Aligned commands come from `alignedSendPtr`; the rare split path retains llReg
    // and selects slot B after slot A returns.  The two sources are mutually exclusive
    // by construction (a split waits for alignedEmpty; aligned enqueue waits !bkBusy).
    // Both addresses are therefore registered at this boundary and preserve FMax #1.
    val alignedCmd = alignedMem(alignedSendPtr)
    val useSplitCmd = bkBusy
    val loadVaddr = UInt(32 bits)
    val loadPaddr = UInt(32 bits)
    loadVaddr := Mux(useSplitCmd,
                     Mux(llReg.bDone, llReg.addrB, llReg.vaddr), alignedCmd.vaddr)
    loadPaddr := Mux(useSplitCmd,
                     Mux(llReg.bDone, llReg.paddrB, llReg.paddr), alignedCmd.paddr)
    dcache.loadCmd.valid         := Mux(useSplitCmd, llReg.valid, alignedSendValid)
    dcache.loadCmd.payload.vaddr := loadVaddr
    dcache.loadCmd.payload.paddr := loadPaddr
    dcache.loadCmd.payload.size  := Mux(useSplitCmd, llReg.size, alignedCmd.size)
    dcache.loadCmd.payload.cacheMode := Mux(
      useSplitCmd, Mux(llReg.bDone, llReg.cmodeB, llReg.cmode), alignedCmd.cmode)
    dcache.loadCmd.payload.token := Mux(
      useSplitCmd,
      (False ## llReg.bDone ## llReg.robId.asBits).asUInt,
      (False ## False ## alignedCmd.bk.robId.asBits).asUInt)
    val alignedCmdFire = alignedSendValid && dcache.loadCmd.ready
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
    val splitDataA = m68k040.cache.DcacheByteLane.storeDataA(
      stOff, p3Ctx.front.size, p3Ctx.front.storeData)
    val splitStrbA = m68k040.cache.DcacheByteLane.storeStrbA(stOff, p3Ctx.front.size)
    val splitDataB = m68k040.cache.DcacheByteLane.storeDataB(
      stOff, p3Ctx.front.size, p3Ctx.front.storeData)
    val splitStrbB = m68k040.cache.DcacheByteLane.storeStrbB(stOff, p3Ctx.front.size)

    // ---- SQ alloc + fwd defaults ----
    sq.io.alloc.valid          := False
    sq.io.alloc.payload.robId  := p3Ctx.front.robId
    sq.io.alloc.payload.paddr  := p3Ctx.paddr
    sq.io.alloc.payload.data   := p3Ctx.front.storeData
    sq.io.alloc.payload.size   := p3Ctx.front.size
    sq.io.alloc.payload.nbytesA   := nbytesA_st
    // Aligned store: drain via {data,size} (fast path). Split store: explicit strobe.
    sq.io.alloc.payload.useStrbA  := p3Ctx.front.twoAccess
    sq.io.alloc.payload.strbA     := splitStrbA
    sq.io.alloc.payload.lineDataA := splitDataA
    sq.io.alloc.payload.validB    := p3Ctx.front.twoAccess
    sq.io.alloc.payload.paddrB    := p3Ctx.paddrB
    sq.io.alloc.payload.nbytesB   := nbytesB_st
    sq.io.alloc.payload.strbB     := splitStrbB
    sq.io.alloc.payload.lineDataB := splitDataB
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
    val p4RetryQuery = p4Valid &&
      (p4Ctx.fwdStall || (p4Ctx.fwdHit && p4Ctx.xlate.front.twoAccess))
    val fwdQueryCtx = Mux(p4RetryQuery, p4Ctx.xlate, p3Ctx)
    sq.io.fwd.query.robId := fwdQueryCtx.front.robId
    sq.io.fwd.query.paddr := fwdQueryCtx.paddr
    sq.io.fwd.query.size  := fwdQueryCtx.front.size

    val isLoad  = u1.memOp === MemOp.LOAD
    val isStore = u1.memOp === MemOp.STORE

    // ---- tagged elastic D-side translation command ─────────────────────────
    // P2 launches a first-half command atomically with the virtual-set probe. P2T
    // retains its pruned context until the matching response is consumed. A rare
    // split reuses P2T for addrB and takes command priority over a younger P2 op.
    val normalReqArm = Bool(); normalReqArm.allowOverride; normalReqArm := False
    val splitReqArm  = Bool(); splitReqArm.allowOverride;  splitReqArm  := False
    val reqFromSplit = splitReqArm
    val reqDrvVaddr  = Mux(reqFromSplit, txCtx.addrB, tCtx.vaddr)
    val reqDrvVpn    = reqDrvVaddr(31 downto 12)
    val reqDrvSup    = Mux(reqFromSplit, txCtx.supervisor, tCtx.supervisor)
    val reqDrvWrite  = Mux(reqFromSplit,
      txCtx.memOp === MemOp.STORE, tCtx.memOp === MemOp.STORE)
    val reqDrvRobId  = Mux(reqFromSplit, txCtx.robId, tCtx.robId)
    val reqDrvToken  = (xlateEpoch ## reqFromSplit ## reqDrvRobId.asBits).asUInt
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
    val probeCancelAll   = sqFlushSig || excActive
    val probeCancelToken = UInt(m68k040.cache.DLoadToken.Width bits)
    probeCancel := False
    probeCancelToken := U(0, m68k040.cache.DLoadToken.Width bits)
    val reqProbeToken = (False ## False ## tCtx.robId.asBits).asUInt

    dcache.loadProbe.valid         := probeWanted && xlate.req.ready
    dcache.loadProbe.payload.vaddr := tCtx.vaddr
    dcache.loadProbe.payload.token := reqProbeToken
    // The registered translation returns later; retain the raw virtual-set read and
    // resolve it by token when the physical command arrives. No second RAM read.
    dcache.loadProbe.payload.resolved := False
    dcache.loadProbe.payload.paddr := tCtx.vaddr
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
    val compRobId     = Reg(UInt(6 bits))
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
      dst.addrB           := s1AddrB
      dst.storeData       := s1StoreData
      dst.anWb            := s1AnWb
      dst.storeNzvc       := storeNzvc
      dst.size            := u1.size
      dst.memOp           := u1.memOp
      dst.twoAccess       := s1TwoAccess
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
    when(alignedRspFire) {
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
      alignedValid(alignedPushPtr)    := True
      alignedSent(alignedPushPtr)     := False
      alignedPoisoned(alignedPushPtr) := sqFlushSig
      alignedPushPtr                  := alignedPushPtr + 1
    }
    switch(alignedEnq ## alignedRspFire) {
      is(B"10") { alignedCount := alignedCount + 1 }
      is(B"01") { alignedCount := alignedCount - 1 }
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
      val robId      = UInt(6 bits)
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
    wakeupPort.valid   := compValid && compWakes && compPdstValid && !compIsFault
    wakeupPort.payload := compPdst
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
    val alignedRspEntry    = alignedMem(alignedRspPtr)
    val alignedRspIsPoison = alignedPoisoned(alignedRspPtr) || sqFlushSig
    when(alignedRspFire && !alignedRspIsPoison) {
      val suppressForLaterPrivCheck = alignedRspEntry.bk.needsSupervisor &&
                                      !alignedRspEntry.bk.xlateSup
      when(dcache.loadRsp.payload.fault && !suppressForLaterPrivCheck) {
        captureFaultDesc(alignedRspEntry.bk, alignedRspEntry.vaddr,
                         alignedRspEntry.size, atc = false)
      } otherwise {
        captureCompletionDesc(alignedRspEntry.bk, dcache.loadRsp.payload.data,
                              alignedRspEntry.size)
      }
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
        when(dcache.loadCmd.fire) {
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
        when(dcache.loadRsp.valid && !aDone) {
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
          when(dcache.loadCmd.fire) { llReg.valid := False; goto(WAIT_B) }
        }
      }

      WAIT_B.whenIsActive {
        when(dcache.loadRsp.valid) {
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
    val bkCompletes   = (bkInWaitB && dcache.loadRsp.valid) ||
                        (bkInWaitA && dcache.loadRsp.valid && dcache.loadRsp.payload.fault)
    bkCompletes.simPublic()
    // The back actually WRITES comp* this cycle (a poisoned back load writes nothing,
    // so the front is free to use the stage).
    val backCompFires = (bkCompletes && !bkPoisoned) ||
                        (alignedRspFire && !alignedRspIsPoison)
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
    val preciseReplayClaimsComp = preciseReplayWants && !backCompFires
    preciseReplayWants.simPublic(); preciseReplayClaimsComp.simPublic()

    // ── D1 ELASTIC FRONT: P1 AGU -> P2 DTLB/VIPT -> P3 SQ -> P4 resolve ──
    // Each registered cut owns its context. Backpressure propagates only while a
    // concrete downstream resource is unable to consume; accept-last turnover keeps
    // a resident same-page aligned stream at II=1 after fill.
    def cancelProbeFor(robId: UInt): Unit = {
      val token = (False ## False ## robId.asBits).asUInt
      probeCancel      := True
      probeCancelToken := token
    }

    // P4 is oldest among the unlaunched front stages, so it has first claim after an
    // already-launched cache/split response. A stalled SQ overlap is re-queried from
    // P4 (the query mux above selects it) until the older store drains.
    val p4CanLeave       = Bool(); p4CanLeave := False
    val p4CompletionFire = Bool(); p4CompletionFire := False
    val p4Front          = p4Ctx.xlate.front
    when(p4Valid && !sqFlushSig && !excActive) {
      val fullForward = p4Ctx.fwdHit && !p4Front.twoAccess
      val mustRetry    = p4Ctx.fwdStall || (p4Ctx.fwdHit && p4Front.twoAccess)
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
        p4Ctx.fwdHit   := sq.io.fwd.rsp.hit
        p4Ctx.fwdStall := sq.io.fwd.rsp.stall
        p4Ctx.fwdData  := sq.io.fwd.rsp.data
      } otherwise {
        when(!p4Front.twoAccess) {
          when(alignedCanEnq) {
            alignedEnq := True
            p4CanLeave := True
          }
        } otherwise {
          // Rare split replay drains the aligned response ring first, preserving the
          // D-cache's untagged response order without putting common aligned hits in
          // a one-at-a-time FSM.
          when(!bkBusy && alignedEmpty) {
            llReg.valid     := True
            llReg.vaddr     := p4Front.vaddr
            llReg.paddr     := p4Ctx.xlate.paddr
            llReg.addrB     := p4Front.addrB
            llReg.paddrB    := p4Ctx.xlate.paddrB
            llReg.size      := p4Front.size
            llReg.cmode     := p4Ctx.xlate.cmode
            llReg.cmodeB    := p4Ctx.xlate.cmodeB
            llReg.robId     := p4Front.robId
            llReg.twoAccess := True
            llReg.bDone     := False
            captureBkCtx(bkCtx, p4Front)
            bkBusy     := True
            bkPoisoned := False
            bkStart    := True
            p4CanLeave := True
          }
        }
      }
    }
    val p4Ready = !p4Valid || p4CanLeave

    // P3 performs the registered-PA SQ operation. Stores terminate here; loads capture
    // the registered forwarding result into P4. An older P4/front-back completion wins
    // the shared comp* port, so a fast store simply holds for one cycle on collision.
    val p3CanLeave       = Bool(); p3CanLeave := False
    val p3ToP4           = Bool(); p3ToP4 := False
    val p3CompletionFire = Bool(); p3CompletionFire := False
    val p3Front          = p3Ctx.front
    val p3IsLoad         = p3Front.memOp === MemOp.LOAD
    val p3IsStore        = p3Front.memOp === MemOp.STORE
    val olderThanP3Comp  = backCompFires || preciseReplayClaimsComp || p4CompletionFire
    when(p3Valid && !sqFlushSig && !excActive) {
      when(p3IsStore) {
        when(storePrivBlocked) {
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
    val txRspFire = xlate.rsp.fire && txValid && txWaitingRsp && txTokenMatch &&
                    !sqFlushSig && !excActive

    // The registered DTLB response and the synchronous virtual-set RAM output meet
    // here on the aligned all-hit path. Qualify that exact read by token/physical
    // tag; no extra CAM, raw-way holding buffer, or second cache read is required.
    val txEffectiveCmode = Mux(cacheCtrl.map(_.dcacheEnabled).getOrElse(False),
      xlate.rsp.payload.cacheMode, m68k040.cache.CacheMode.INHIBITED)
    dcache.loadProbeResolve.valid := txRspFire && !xlateFault && !txSecond &&
                                     (txCtx.memOp === MemOp.LOAD) && !txCtx.twoAccess
    dcache.loadProbeResolve.payload.token :=
      (False ## False ## txCtx.robId.asBits).asUInt
    dcache.loadProbeResolve.payload.paddr := s1Paddr
    dcache.loadProbeResolve.payload.cacheMode := txEffectiveCmode

    val txOut = XlatePipeCtx()
    txOut.front  := txCtx
    txOut.paddr  := Mux(txSecond, txPaddrA, s1Paddr)
    txOut.paddrB := Mux(txSecond, s1PaddrB, txCtx.addrB)
    txOut.cmode  := Mux(txSecond, txCmodeA, txEffectiveCmode)
    txOut.cmodeB := txEffectiveCmode

    when(txRspFire) {
      when(xlateFault) {
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
    splitReqArm := txValid && txSecond && !txWaitingRsp && !sqFlushSig && !excActive
    normalReqArm := tValid && tIsMem && txReady && !splitReqArm &&
                    !sqFlushSig && !excActive && (!tIsLoad || dcache.loadProbe.ready)
    val normalReqFire = xlate.req.fire && !reqFromSplit && !excActive
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
    issuePort.ready := s1Ready && !sqFlushSig && !excActive

    // Oldest-to-youngest valid updates, then accept-last replacements. Later writes
    // intentionally win when a stage consumes and accepts on the same edge.
    when(p4CanLeave) { p4Valid := False }
    when(p3CanLeave) { p3Valid := False }
    when(txCanLeave) { txValid := False; txSecond := False; txWaitingRsp := False }
    when(tCanLeave)  { tValid := False }
    when(s1ToT) { s1Valid := False }

    when(p3ToP4) {
      p4Valid          := True
      p4Ctx.xlate      := p3Ctx
      p4Ctx.fwdHit     := sq.io.fwd.rsp.hit
      p4Ctx.fwdStall   := sq.io.fwd.rsp.stall
      p4Ctx.fwdData    := sq.io.fwd.rsp.data
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
    when(sqFlushSig) {
      pendPush := pendReadyAfterThisCycle
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
    when((applyFast || applyBacklog) && !liveCompletionFires) {
      val e = pendMem(pendApply)
      sqCompletionPort.valid   := True
      sqCompletionPort.payload := e.robId
      when(!applyFault) {
        compValid      := True
        compRobId      := e.robId
        compData       := e.data
        compPdst       := e.pdst
        compPdstValid  := e.pdstValid
        compIsLoad     := False
        compWakes      := e.wakes
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
      } otherwise {
        sqFaultCompletionPort.valid   := True
        sqFaultCompletionPort.payload := Mux(applyFast, sq.io.sqFaultCompletion.payload, pendFaultPayload(pendApply))
      }
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
    when(excActive && excLoadCmdValid) {
      dcache.loadCmd.valid         := True
      dcache.loadCmd.payload.vaddr := excLoadCmdVaddr
      // Task 11: the PHYSICAL address now comes across explicitly instead of being
      // regenerated as the vaddr here. Every pre-existing exception-sequencer load is
      // still identity (ExceptionUnit's own `ldoPaddr` defaults to `ldoVaddr`), so this
      // is behaviour-identical for them; FRESTORE's header read supplies a real
      // DTLB-translated PA, which the old identity regeneration would have discarded.
      dcache.loadCmd.payload.paddr := excLoadCmdPaddr
      dcache.loadCmd.payload.size  := excLoadCmdSize
      // identity-physical, matching dcStore's exc-path cacheMode.
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
    when(dcache.store.fire && excActive && excStoreValid) {
      excStoreOutstanding := True
    }
    // The ordinary LS P2/P2T pipe fully relinquishes this translation stream for the
    // entire duration `excActive` is held. Historically that was because every
    // exception-sequencer access was already physical on the cache ports; since Task 11
    // it is ALSO what makes the hand-off below safe.
    val lsXlateReqValid = !excActive && (normalReqArm || splitReqArm)
    xlate.req.valid              := lsXlateReqValid
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
      xlate.req.payload.write      := excXlateWrite
      xlate.req.payload.token      := excXlateToken
    }
    excXlateReady := xlate.req.ready
    // Deliberately keyed off the LS-SIDE valid, NOT the muxed `xlate.req.valid`:
    // `umAccessRobId` tags a walk with the ROB id whose deferred U/M-bit commit it
    // belongs to, and an exception-sequencer translation belongs to no ROB entry. Feeding
    // the stale `reqDrvRobId` there would attribute the walk to an unrelated (possibly
    // already-retired) instruction.
    xlateRobIdSig                := Mux(lsXlateReqValid, reqDrvRobId, U(0, 6 bits))

    excLoadCmdReady := dcache.loadCmd.ready
  }
}
