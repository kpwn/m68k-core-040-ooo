package m68k040.execute

import m68k040.cache.{DcacheService, DLoadCmd, DStoreCmd}
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

/** AGU + Load/Store EU (LS-1 slice): conservative single-outstanding pipe.
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
  * issue.ready deasserts while a load is in flight (busy) -> EU is occupied. */
class LsEuPlugin extends FiberPlugin with LsEuService {
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
  // While `excActive`, the commit-side ExceptionUnit owns the D-cache + D-TLB
  // request ports (the LS pipe is squashed/idle — serializing). The LS EU MUXes
  // these exc requests onto the cache it already owns. Default-idle (allowOverride)
  // so standalone LS tests / a DUT that doesn't wire them are unchanged.
  var excActive: Bool = null
  var excLoadCmdValid: Bool = null; var excLoadCmdVaddr: UInt = null; var excLoadCmdSize: m68k040.isa.Size.C = null
  var excLoadCmdReady: Bool = null
  var excStoreValid: Bool = null;   var excStorePayload: DStoreCmd = null
  var excXlateValid: Bool = null;   var excXlateVpn: UInt = null
  var excXlateWrite: Bool = null;   var excXlateSupervisor: Bool = null
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
    excLoadCmdReady = Bool()
    excStoreValid   = Bool(); excStorePayload = DStoreCmd()
    excXlateValid   = Bool(); excXlateVpn = UInt(20 bits)
    excXlateWrite   = Bool(); excXlateSupervisor = Bool()
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
    // NOT affect the exception sequencer's own physical accesses (excXlateSupervisor,
    // always True — those are a separate override, last-wins, below).
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
    excLoadCmdSize.allowOverride;       excLoadCmdSize := m68k040.isa.Size.LONG
    excStoreValid.allowOverride;        excStoreValid := False
    excStorePayload.allowOverride;      excStorePayload.assignDontCare()
    excXlateValid.allowOverride;        excXlateValid := False
    excXlateVpn.allowOverride;          excXlateVpn := U(0, 20 bits)
    excXlateWrite.allowOverride;        excXlateWrite := False
    excXlateSupervisor.allowOverride;   excXlateSupervisor := True
    excLoadCmdReady.allowOverride;      excLoadCmdReady := False

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
    dcache.store << sq.io.drain
    sq.io.drainAck := dcache.storeAck   // pop a drained entry only once memory is written
    sq.io.drainErr := dcache.storeErr
    sqEmptySig := sq.io.empty           // surfaced for the exception FSM's drain wait
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
    // chained onto the ALU's result. (Latency-agnostic: lock-step is instruction-level
    // and the EU is single-outstanding.)
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

    // ---- translate-at-execute: the LS EU DRIVES the D-side translation port ----
    // It presents the access VPN (loadVaddr, which the FSM sets to s1Va for slot A
    // or s1AddrB for slot B), the access class (write?=store, supervisor?), and
    // `valid` (a real demand). The D-cache READS the response (rsp.ppn for its load
    // hit-tag, rsp.ready to gate load acceptance). On a DTLB miss `rsp.ready` is
    // False while the walker runs; the LS EU stalls (does NOT alloc a store / does
    // NOT accept the load) on its existing single-outstanding path until ready.
    // (req drivers are set after loadVaddr is declared, below.)
    val s1Paddr = (xlate.rsp.ppn ## s1Va(11 downto 0)).asUInt
    // Slot-B (split-access second half) translated physical address: SAME `xlate.rsp`
    // port, combined with addrB's OWN page offset (not s1Va's — a line-crossing split
    // stays within the same page but at a different 12-bit offset; only a page-
    // crossing split shares offset 0). Only meaningful the cycle the LIVE xlate
    // request/response actually corresponds to addrB's VPN (the new XLATE_B FSM
    // state below arms this via `xlateBArm` -> `xlateVaddr`, mirroring exactly how
    // `s1Paddr` above is only meaningful while IDLE is resolving slot A's request).
    val s1PaddrB = (xlate.rsp.ppn ## s1AddrB(11 downto 0)).asUInt
    val xlateReady = xlate.rsp.ready    // False on an enabled-MMU TLB miss (walking)
    val xlateFault = xlate.rsp.fault

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
    // agnostic; the EU is single-outstanding and already stalls the walker on a
    // DTLB miss). Identity (MMU-disabled) flows through the SAME register so both
    // modes are pipelined uniformly. All RegInit / Reg (no uninit fanout).
    val s2Paddr  = Reg(UInt(32 bits))
    val s2PaddrB = Reg(UInt(32 bits))
    val s2Fault  = RegInit(False)
    val s2Cmode  = Reg(m68k040.cache.CacheMode())

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
                    (s2Cmode =/= m68k040.cache.CacheMode.INHIBITED)
    // Root-cause fix (post-Task-P2.5 lock-step investigation): a privileged STORE
    // (e.g. MOVES.L Dn,<ea>) executed in user mode must NEVER let its memory write
    // reach the SQ at all -- mirrors the EXISTING `suppressForLaterPrivCheck`
    // pattern above (line ~1254, same `u1.needsSupervisor && !xlate.req.supervisor`
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
    val storePrivBlocked = u1.needsSupervisor && !xlate.req.supervisor
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
    // cmdSet); the AGU adder ends at the llReg flop. +1 cache-launch cycle is latency-
    // agnostic (the EU is single-outstanding; s1* are held stable while busy). All
    // RegInit / Reg (no uninit fanout).
    val llReg = new Area {
      val valid     = RegInit(False)
      val vaddr     = Reg(UInt(32 bits))
      val paddr     = Reg(UInt(32 bits))
      val addrB     = Reg(UInt(32 bits))
      val paddrB    = Reg(UInt(32 bits))
      val size      = Reg(m68k040.isa.Size())
      val cmode     = Reg(m68k040.cache.CacheMode())
      val twoAccess = RegInit(False)
      val bDone     = RegInit(False)   // slot A launched; now presenting slot B (cross)
    }

    // Slot-B (split-access second half) DTLB translate-request arm (mmu-split-
    // second-half fix): set for the duration of the new XLATE_B FSM state, while
    // resolving addrB's REAL translation. Selects addrB as the LIVE `xlate.req` VPN
    // via `xlateVaddr` below. Deliberately INDEPENDENT of `llReg.bDone` (which
    // selects addrB for the REGISTERED cache-launch command at a later pipeline
    // point, once slot A's line has already landed) — this Reg governs only the
    // DTLB request interface, and only during the translate phase (well before the
    // cache is ever launched for either slot).
    val xlateBArm = RegInit(False)

    // ---- dcache load cmd: driven from the REGISTERED launch stage (llReg) ----
    // slot A = llReg.vaddr/paddr; slot B (cross) = llReg.addrB/paddrB (selected once
    // slot A is captured -> bDone). The cache tags on this REGISTERED address (the AGU
    // sum that produced it ended at the llReg flop). `loadVaddr`/`loadPaddr` are kept
    // as locals so the same nets feed the exc-arbitration MUX path unchanged.
    val loadVaddr = UInt(32 bits)
    val loadPaddr = UInt(32 bits)
    loadVaddr := Mux(llReg.bDone, llReg.addrB,  llReg.vaddr)
    loadPaddr := Mux(llReg.bDone, llReg.paddrB, llReg.paddr)
    dcache.loadCmd.valid        := llReg.valid
    dcache.loadCmd.payload.vaddr := loadVaddr
    dcache.loadCmd.payload.paddr := loadPaddr
    dcache.loadCmd.payload.size  := llReg.size
    dcache.loadCmd.payload.cacheMode := llReg.cmode

    // ---- store split (byte-lane) for the SQ entry ----
    // Slot B's physical address is `s1PaddrB` (declared above alongside `s1Paddr`) —
    // the REAL DTLB translation of addrB's own VPN, resolved by the XLATE_B FSM
    // state before `s2PaddrB` (below) latches it. addrB can land on a different
    // page than s1Va with entirely different perms/residency (mmu-split-second-half
    // fix) — it is NOT assumed identity-mapped.
    // sizeBytes of the store; bytes in slot A = (16 - lineOffSt), spill -> slot B.
    val stOff      = s1Va(3 downto 0)
    val stBytes    = sizeBytes(u1.size)                 // 1/2/4
    val bytesInA   = (U(16) - stOff.resize(5 bits))     // 1..16
    val nbytesA_st = Mux(s1TwoAccess, bytesInA.resize(3 bits), stBytes)
    val nbytesB_st = Mux(s1TwoAccess, (stBytes - bytesInA).resize(3 bits), U(0, 3 bits))
    val splitDataA = m68k040.cache.DcacheByteLane.storeDataA(stOff, u1.size, s1StoreData)
    val splitStrbA = m68k040.cache.DcacheByteLane.storeStrbA(stOff, u1.size)
    val splitDataB = m68k040.cache.DcacheByteLane.storeDataB(stOff, u1.size, s1StoreData)
    val splitStrbB = m68k040.cache.DcacheByteLane.storeStrbB(stOff, u1.size)

    // ---- SQ alloc + fwd defaults ----
    sq.io.alloc.valid          := False
    sq.io.alloc.payload.robId  := s1Ctx.robId
    sq.io.alloc.payload.paddr  := s2Paddr
    sq.io.alloc.payload.data   := s1StoreData
    sq.io.alloc.payload.size   := u1.size
    sq.io.alloc.payload.nbytesA   := nbytesA_st
    // Aligned store: drain via {data,size} (fast path). Split store: explicit strobe.
    sq.io.alloc.payload.useStrbA  := s1TwoAccess
    sq.io.alloc.payload.strbA     := splitStrbA
    sq.io.alloc.payload.lineDataA := splitDataA
    sq.io.alloc.payload.validB    := s1TwoAccess
    sq.io.alloc.payload.paddrB    := s2PaddrB
    sq.io.alloc.payload.nbytesB   := nbytesB_st
    sq.io.alloc.payload.strbB     := splitStrbB
    sq.io.alloc.payload.lineDataB := splitDataB
    // Precise-path fields (P2): placeholder wiring, replaced for real in Task P2.2
    // (the fast/precise classification). vaddr/vaddrB/cacheMode/supervisor are the
    // real live values already computed above for this access.
    sq.io.alloc.payload.vaddr      := s1Va
    sq.io.alloc.payload.vaddrB     := s1AddrB
    sq.io.alloc.payload.cacheMode  := s2Cmode
    sq.io.alloc.payload.supervisor := xlate.req.supervisor
    // Task P2.2: the real fast/precise classification (was a `False` placeholder in
    // Task P2.1). Live default off `fastStore`, matching every other field's
    // unconditional-default-before-the-FSM style above; the XLATE/WAIT_SQ
    // alloc-success branches below re-state it explicitly alongside the
    // conditional `captureCompletion` call for readability (same value, harmless
    // last-assignment-wins restatement).
    sq.io.alloc.payload.precise    := !fastStore
    sq.io.fwd.query.robId := s1Ctx.robId
    sq.io.fwd.query.paddr := s2Paddr
    sq.io.fwd.query.size  := u1.size

    val isLoad  = u1.memOp === MemOp.LOAD
    val isStore = u1.memOp === MemOp.STORE

    // ---- drive the D-side translation request (translate-at-execute) ----
    // VPN = the access address the EU is currently presenting. With FMax #1 the cache
    // `loadCmd` is now driven off the REGISTERED `llReg`; the DTLB request must instead
    // track the LIVE access being translated (translation runs in IDLE/XLATE_B, BEFORE
    // the registered cache launch — it feeds s2Paddr/s2PaddrB, which llReg later
    // captures). So drive the VPN from `xlateVaddr` (s1Va for slot A / s1AddrB for
    // slot B, selected by `xlateBArm` — set for the duration of the new XLATE_B state,
    // which performs addrB's REAL DTLB translate; mmu-split-second-half fix). s1AddrB
    // is the registered-base-derived next-line base (a shallow stage off s1Va), NOT on
    // the eaDelta->tag arc the cache cmd registered away. `valid` asserts whenever a
    // memory µop is resident in S1 (a real translation demand -> the DTLB may walk on
    // a miss) — for EITHER slot, sequenced one after the other (the DTLB is a single-
    // outstanding resource: one walker instance, one miss-request register — see
    // DtlbPlugin). `write` selects the store M-bit / write-protect check (the SAME
    // access class for both slots of one instruction). `supervisor` = the live
    // architectural S bit (reqDrvSup below), NOT hardcoded.
    //
    // NOTE: `xlateVaddr` deliberately does NOT also select on `llReg.bDone` (the
    // cache-launch slot-B select, set later in WAIT_A once slot A's line has landed).
    // Before this fix it did — but that request's response was never consumed (slot
    // B's paddr was a hardcoded identity shortcut, see the mmu-split-second-half
    // design note above `s1PaddrB`), so it was pure wasted DTLB traffic. Now that
    // XLATE_B performs addrB's real translate BEFORE the cache is ever launched (i.e.
    // strictly before WAIT_A), re-requesting the SAME vpn again in WAIT_A would be
    // redundant AND would risk double-pushing this access's addrB U/M-descriptor
    // write into the (unguarded-capacity) UmWriteQueue — see DtlbPlugin's `umq`.
    val xlateVaddr = Mux(xlateBArm, s1AddrB, s1Va)

    // ─────────────────────────────────────────────────────────────────────────
    // FMax #2: REGISTER the LS-EU -> DTLB translation-request INTERFACE.
    //
    // After FMax #1 the cache `loadCmd` tags on a registered address, but the DTLB
    // request below is still combinational off the LIVE access (s1Valid/xlateVaddr
    // via eaDelta) — under MMU-on it fed the DTLB `tlb.io.lookupVpn := _req.vpn` cone
    // LIVE, so the route-dominated arc `Dcache valids/ldS1Set -> LsEu -> _req.vpn/
    // _req.valid -> DTLB hitVec -> hr*/missReqReg/CE -> s2Paddr -> tagMem` STILL landed
    // the AGU/eaDelta source on the cache tag access (the post-FMax#1 worst path:
    // s1Ctx_uop_eaDelta -> tagMem via the DTLB cone). REGISTER the request {valid,vpn,
    // write,super,robId} into `reqReg` flops here and drive the DTLB `req` from THOSE
    // flops, so the DTLB hitVec/hr*/missReqReg cone STARTS at a register inside the
    // DTLB pblock instead of fanning combinationally out of the LS-EU.
    //
    // Latency: the request is held STABLE while the EU is busy (s1* held; xlateVaddr
    // tracks the held s1Va / s1AddrB in the cross slot-B state), so the registered req
    // settles to the live access within one cycle and stays put until completion. The
    // FSM only consumes a translation result once the registered req MATCHES the access
    // it is resolving (`reqMatch`), exactly mirroring the single-outstanding DTLB-miss
    // stall (rsp.ready low -> hold S1, retry). +1 translate cycle is latency-agnostic.
    //
    // `reqDrv*` are combinational nets written by the base drive (here) and, last-wins,
    // by the exception-unit arbitration override at the END of `logic`. The single
    // register stage (`reqReg`) + the final `xlate.req`/`xlateRobIdSig` drive are
    // emitted AFTER both writers so the registered interface reflects the resolved req.
    val reqDrvValid = Bool()
    val reqDrvVpn   = UInt(20 bits)
    val reqDrvSup   = Bool()
    val reqDrvWrite = Bool()
    val reqDrvRobId = UInt(6 bits)
    reqDrvValid := s1Valid && (isLoad || isStore)
    reqDrvVpn   := xlateVaddr(31 downto 12)   // FMax #1's live access VPN (NOT the llReg cmd)
    reqDrvSup   := privCtrl.map(_.supervisor).getOrElse(False)
    reqDrvWrite := isStore
    reqDrvRobId := s1Ctx.robId

    val reqReg = new Area {
      val valid = RegInit(False)
      val vpn   = Reg(UInt(20 bits))
      val sup   = Reg(Bool())
      val write = Reg(Bool())
      val robId = Reg(UInt(6 bits))
    }
    // `reqMatch`: the REGISTERED req corresponds to the access the FSM is currently
    // presenting (held s1 access via xlateVaddr / isStore). Gates the IDLE consume so a
    // STALE rsp (the registered req still holding a previous access's vpn for one cycle
    // after S1 advances) is never mistaken for THIS access's translation. Under identity
    // (MMU-off) rsp.ready is always True, but reqMatch still requires the flop to have
    // captured this vpn -> identical +1-cycle alignment in both MMU modes.
    val reqMatch = reqReg.valid && (reqReg.vpn === xlateVaddr(31 downto 12)) &&
                   (reqReg.write === isStore)

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
    // NZVC writeback for a MOVE-to-memory store (N/Z of the moved value, V=C=0).
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
    // SAFE under single-outstanding (issue.ready := !busy && !s1Valid &&
    // !compValid): at most one LS µop is ever in the forward/resolve stages, and
    // the s1 context (hence the SQ query) is held stable across the registered
    // query and its RESOLVE use (busy holds s1Valid; no concurrent store-alloc in
    // this pipe; a draining entry is held-resident-until-ack so it keeps
    // forwarding). A wrong-path load still completes but the ROB filters its
    // completion by robId, exactly as before this retime (the LS EU did not gate
    // the in-flight load on flush previously either). All RegInit / Reg (no uninit
    // fanout).
    val fwdHit   = RegInit(False)
    val fwdStall = RegInit(False)
    val fwdData  = Reg(Bits(32 bits))

    // ---- two-access (cross-line / cross-page) capture ----
    // The captured 128-bit line from slot A; slot B's line arrives in WAIT_B and
    // is merged with it. `lineOff` is the byte offset of the access within line A.
    // `aDone` records that slot A's line was captured (loadRsp is a 1-cycle pulse,
    // so we latch it and then keep driving slot B's cmd until the cache accepts it).
    val lineA    = Reg(Bits(128 bits))
    val aDone    = RegInit(False)
    val lineOff  = s1Va(3 downto 0)

    // ---- MOVE-to-memory NZVC (computed from the store data at the access size) ----
    // m68k MOVE sets N = sign bit of the moved value at the size, Z = (value==0 over
    // the size's bytes), V = 0, C = 0. The store data (s1Data) holds the source
    // register; only the low `size` bytes are written / observed. Computed off the
    // already-registered s1Data (no new long arc).
    val stN = u1.size.mux(
      m68k040.isa.Size.BYTE -> s1Data(7),
      m68k040.isa.Size.WORD -> s1Data(15),
      m68k040.isa.Size.LONG -> s1Data(31))
    val stZ = u1.size.mux(
      m68k040.isa.Size.BYTE -> (s1Data(7 downto 0)  === 0),
      m68k040.isa.Size.WORD -> (s1Data(15 downto 0) === 0),
      m68k040.isa.Size.LONG -> (s1Data === 0))
    val storeNzvc = stN ## stZ ## False ## False   // N Z V(0) C(0)

    // captured-decision -> register (called in the decision cycle). A STACK-PUSH store
    // writes its int dst (A7) with the PREDECREMENTED address (s1Va) — not the load
    // `result` — and wakes consumers of A7.
    // An EA auto-update STORE (-(An)/(An)+) writes its base An (its int dst) with the
    // s1AnWb value — generalizing the stkPush A7 side-effect to any An. A LOAD with an
    // auto EA writes its LOADED value to its int dst (the An update rides a separate ADD
    // crack µop); so the An write is selected ONLY for an eaAuto STORE.
    val isAutoStoreAn = isStore && (u1.eaAuto =/= m68k040.decode.EaAuto.NONE)
    def captureCompletion(result: Bits): Unit = {
      liveCompletionFires := True
      compValid     := True
      compRobId     := s1Ctx.robId
      // MOVEM.W LOAD sign-extends the loaded word to the full 32-bit register (Musashi
      // MAKE_INT_16). The DcacheByteLane.extract path ZERO-extends a WORD; the MOVEM-load
      // µop carries `isMovea` (reused as the ".W load -> sign-extend" marker; the ALU EU
      // is the only other isMovea consumer and never sees an LS-cluster µop). A .L MOVEM
      // load + every non-MOVEM load leave isMovea False (full / zero-extended result).
      val ldResult = Mux(u1.isMovea && isLoad && (u1.size === m68k040.isa.Size.WORD),
                         result(15 downto 0).asSInt.resize(32).asBits, result)
      // LEA address-generate: the int result IS the computed effective address (s1Va),
      // exactly like a stkPush writes its predecremented address. No memory was accessed.
      compData      := Mux(u1.leaAddr, s1Va.asBits,
                       Mux(u1.stkPush, s1Va.asBits,
                       Mux(isAutoStoreAn, s1AnWb, ldResult)))
      // A CCR-restore load writes NO int reg (it restores flags); a stack-push store's
      // int dst is A7 (handled via compData above); a plain load writes its int dst.
      compPdst      := u1.pdst
      compPdstValid := u1.pdstValid && !u1.ccrRestore
      compIsLoad    := isLoad
      // Wake an int-producing load / stkPush store / EA-auto store (the predec/postinc
      // An side-effect). A CCR-restore load produces no int reg -> no (stale-pdst) wakeup.
      compWakes     := (isLoad && !u1.ccrRestore) || u1.stkPush || u1.leaAddr || (isAutoStoreAn && u1.pdstValid)
      compStkPush   := u1.stkPush
      compCcrRestore := u1.ccrRestore
      // Drop the commit record of an EA-auto store that is an RMW/CLR AUXILIARY store:
      // it writes An but NOT NZVC (the op µop owns the flags + is the macro commit). A
      // MOVE store (reg-to-mem OR mem-to-mem) writes NZVC and IS the macro commit (it
      // carries the PC) -> KEPT. So drop iff it writes An but no flags.
      compEaAutoDrop := isAutoStoreAn && !u1.writesNzvc
      // Trailing RMW/CLR store: a STORE writing neither an int reg nor flags. An EA-auto
      // store writes An (pdstValid) -> NOT a dropped RMW store (its An commit is kept).
      compRmwStore  := isStore && !u1.pdstValid && !u1.writesNzvc && !u1.stkPush
      compCrackDrop := u1.divIsRem    // MOVEM move (load/store) crack-drop marker
      compKeepCommit := u1.keepCommit // PEA push: force-keep the macro commit
      compDstArch   := u1.dstArch
      // CCR-restore (RTR): NZVC := loaded[3:0], X := loaded[4] (CCR bit layout
      // X=4,N=3,Z=2,V=1,C=0). Otherwise a MOVE-to-mem store's NZVC = N/Z of the stored
      // value (V=C=0). A plain load writes neither (u1.writesNzvc/X are False).
      compNzvc      := Mux(u1.ccrRestore, result(3 downto 0), storeNzvc)
      compNzvcWrite := u1.writesNzvc
      compNzvcDst   := u1.pNzvcDst
      compX         := result(4)
      compXWrite    := u1.writesX
      compXDst      := u1.pXDst
      compIsFault   := False
    }

    // captured-FAULT -> register (called in the decision cycle when an access takes a
    // DTLB rsp.fault). Completes (compValid) so the ROB marks done, but flagged as a
    // fault (no reg write / no store alloc / no wakeup); drives faultCompletion. The
    // SSW attrs: write = store, sizeBits = encoded access size, supervisor = the
    // access function-code supervisor bit (xlate.req.supervisor for this access).
    // `atc` (task #189): True (default, preserves the pre-existing MMU-fault
    // behavior) for the DTLB-translation-fault call site; the NEW bus-error call
    // site (D-cache refill AXI resp error, WAIT state below) passes False.
    // `faultAddr` (mmu-split-second-half fix): defaults to s1Va (slot A / every
    // pre-existing call site, unchanged) — the XLATE_B call site (addrB's own
    // translation faulting) passes s1AddrB so the SSW/format-$7 frame reports the
    // ACTUAL faulting half's address, not slot A's.
    def captureFault(atc: Boolean = true, faultAddr: UInt = s1Va): Unit = {
      liveCompletionFires := True
      compValid     := True
      compRobId     := s1Ctx.robId
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
      compFaultWr   := isStore
      compFaultSize := u1.size.mux(
        m68k040.isa.Size.BYTE -> U(0, 2 bits),
        m68k040.isa.Size.WORD -> U(1, 2 bits),
        m68k040.isa.Size.LONG -> U(2, 2 bits))
      compFaultSup  := xlate.req.supervisor
      compFaultAtc  := Bool(atc)
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
    def deferCompletion(): Unit = {
      val e = PendingStoreWb()
      e.robId      := s1Ctx.robId
      e.dstArch    := u1.dstArch
      // A store never reaches captureCompletion's leaAddr/ldResult branches (those
      // are load/LEA-only) -- the store result Mux collapses to exactly this.
      e.data       := Mux(u1.stkPush, s1Va.asBits, Mux(isAutoStoreAn, s1AnWb, B(0, 32 bits)))
      e.pdst       := u1.pdst
      e.pdstValid  := u1.pdstValid
      e.wakes      := u1.stkPush || (isAutoStoreAn && u1.pdstValid)
      e.stkPush    := u1.stkPush
      e.eaAutoDrop := isAutoStoreAn && !u1.writesNzvc
      e.rmwStore   := isStore && !u1.pdstValid && !u1.writesNzvc && !u1.stkPush
      e.crackDrop  := u1.divIsRem
      e.keepCommit := u1.keepCommit
      e.nzvc       := storeNzvc
      e.nzvcWrite  := u1.writesNzvc
      e.nzvcDst    := u1.pNzvcDst
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
      // `poisoned` (the sticky wrong-path latch) cannot cover it either: it is a Reg
      // set BY this same flush pulse, so it only reads True from the NEXT cycle on --
      // a store whose alloc arm fires ON the flush cycle still sees poisoned=False.
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
    // NZVC writeback + bypass for a MOVE-to-memory store (mirrors the int path; the
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
    // Dynamic NZVC-wakeup: a completing NZVC-writing LS op (a MOVE-to-memory store or an
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

    val busy = RegInit(False); busy.simPublic(); s1Valid.simPublic()
    // Single-outstanding: do not accept a new µop while a decision is pending
    // (s1Valid), a load is in flight (busy), or a registered completion is occupying
    // the writeback stage this cycle (compValid). compValid is a 1-cycle pulse, so
    // this only stalls issue for that one extra cycle.
    issuePort.ready := !busy && !s1Valid && !compValid

    // Task #139 finding #1 mechanism #2: this core recovers branch mispredicts at
    // RETIRE time only (task #116 -- resolve-time recovery is a pending IPC lever),
    // so `sqFlushSig` (== the ROB's registered doFlush pulse) can fire many cycles
    // AFTER a wrong-path µop was legitimately issued into this EU (issued back when
    // it still looked correct-path). `sqFlushSig` itself is only a ONE-CYCLE pulse,
    // but a straggling access can still be several states deep (XLATE/RESOLVE/WAIT/
    // etc) when it fires and take several MORE cycles to reach its own completion --
    // well past that one-cycle window. Nothing previously caught this: the StoreQueue
    // squashes its OWN then-resident uncommitted entries on `io.flush` (StoreQueue.
    // scala's `when(io.flush){ keep := valids&&committed... }`), but a store that
    // hadn't reached `sq.io.alloc.valid` YET at the flush cycle sails through
    // unchecked afterwards (`when(io.alloc.valid && !io.flush)` only reads the
    // CURRENT cycle's flush). Root-caused via a direct SQ-ALLOC-vs-pcStore trace
    // (fuzz-campaign-divergence-2026-07-16 memory): a wrong-path store's SQ entry
    // was allocated under a robId number the ROB later reclaimed and reused for a
    // real, later instruction -- the orphan entry (never `committed`, since its
    // true wrong-path owner never retires) sits at the SQ ring head forever,
    // permanently blocking drain (classic head-of-line block, same symptom class as
    // finding #1's original same-cycle-race mechanism, but a genuinely different
    // multi-cycle root cause). `poisoned` sticky-latches across a straggling
    // access's remaining lifetime so its eventual terminal action can be dropped
    // with NO observable side effect (no SQ alloc, no ROB completion/wakeup) --
    // exactly as if it had never been issued, matching what SHOULD happen to a
    // squashed instruction.
    val poisoned = RegInit(False); poisoned.simPublic()
    when(issuePort.fire) {
      poisoned := False
    } elsewhen(sqFlushSig && (busy || s1Valid)) {
      poisoned := True
    }
    sqFlushSig.simPublic()

    // S0 -> S1 advance (only when not busy in a wait state). The bypassed BASE operand
    // (`base0`) is latched here — NOT the computed effective address — so the deep
    // ALU->base cone ends at the `s1Base` flop. The effective address `s1Va`, the
    // cross-detection, and the next-line base (`s1AddrB`/`s1CrossLine`/`s1CrossPage`/
    // `s1TwoAccess`) are all derived COMBINATIONALLY off `s1Base`/`s1Va` above (shallow
    // stages off the flop), so neither the AGU adder nor the +16/cross compares are
    // chained onto the ALU result.
    when(issuePort.fire) {
      s1Valid := True
      s1Ctx   := issuePort.payload
      s1Base  := base0
      s1Data  := data0
      s1Index := idxTerm0
    } otherwise {
      when(!busy) { s1Valid := False }
    }

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

    val fsm = new StateMachine {
      val IDLE    = new State with EntryPoint
      val XLATE_B = new State  // split access ONLY: real DTLB translate of addrB (2nd half)
      val XLATE   = new State  // registered translated paddr -> SQ-fwd query / store alloc
      val RESOLVE = new State  // registered SQ-fwd result -> completion / cache launch
      val LAUNCH  = new State  // registered cache launch: drive loadCmd off llReg (FMax #1)
      val WAIT    = new State   // aligned: cache load cmd accepted, awaiting loadRsp
      val WAIT_A  = new State    // cross: slot A accepted, awaiting line A
      val WAIT_B  = new State    // cross: slot B accepted, awaiting line B -> merge
      val WAIT_SQ = new State    // store: SQ full, hold the alloc until an entry drains

      IDLE.whenIsActive {
        busy := False
        // Clear the cross slot-B select left set by a PRIOR cross-line LOAD (WAIT_A sets
        // llReg.bDone := True and the load completes WITHOUT clearing it). bDone gates
        // `xlateVaddr` (s1AddrB when set), and a STORE translates in IDLE/XLATE WITHOUT
        // ever passing through RESOLVE (where a load re-clears bDone), so a stale bDone
        // would make the store translate the NEXT-line base (s1AddrB) instead of s1Va —
        // corrupting slot A's paddr to the wrong line and DROPPING its write-through
        // (PRE-EXISTING cross-line-store-after-load bug). Cleared here every IDLE cycle;
        // re-armed only by WAIT_A for an in-flight cross load.
        llReg.bDone := False
        when(s1Valid) {
          when(u1.leaAddr) {
            // LEA address-generate: complete immediately with the computed EA address
            // (s1Va) as the int result. NO translate, NO cache access -> never page-faults.
            // The int dst (An / T0) write + wakeup ride captureCompletion's leaAddr path.
            // Task #139 mechanism #2: suppress for a poisoned (squashed) access.
            when(!poisoned) { captureCompletion(s1Va.asBits) }
            busy    := False
            s1Valid := False
          } elsewhen(isLoad || isStore) {
            // Translate-at-execute: REGISTER the translated paddr (+perm fault) in
            // this stage so the SQ overlap-compare / store-alloc consume a REGISTERED
            // s2Paddr next cycle (the DTLB lookup is no longer in series with the
            // SQ-compare). Only advance once translation is RESOLVED (TLB hit /
            // identity); on a DTLB miss `xlateReady` is False while the walker runs ->
            // hold S1 (busy) and retry next cycle (the existing single-outstanding
            // stall). `busy` alone is not enough on the stall path (the S0->S1 advance
            // reads the pre-update busy and would clear s1Valid this cycle), so
            // re-assert s1Valid explicitly (later write wins).
            busy := True
            // Only consume the translation once the REGISTERED req matches THIS access
            // (`reqMatch`, FMax #2): on the first IDLE cycle for a new access the
            // registered req still holds the previous vpn, so its rsp is stale. reqMatch
            // asserts the next cycle (the req settled to this held access); until then
            // hold S1 (the explicit re-assert below), exactly like the DTLB-miss stall.
            when(xlateReady && reqMatch) {
              when(xlateFault) {
                // MMU access fault: the translation RESOLVED with a fault (non-
                // resident / write-protect / supervisor). The access does NOT
                // proceed (no SQ alloc / no cache launch); it completes as a FAULT
                // (vector 2) so the ROB flags the entry for precise delivery.
                // Task #139 mechanism #2: suppress for a poisoned (squashed) access.
                when(!poisoned) { captureFault() }
                busy    := False
                s1Valid := False
                goto(IDLE)
              } otherwise {
                s2Paddr  := s1Paddr
                s2Fault  := xlateFault
                // CACR.DE=0 (design doc §4.3/§5 item 2, USER DECISION): literally
                // fully uncached, matching real silicon -- effectiveMode = DE ?
                // pageMode : INHIBITED for EVERY data access. Folding this in HERE
                // (the single point s2Cmode is captured, regardless of whether the
                // access is aligned or split -- XLATE_B never re-captures s2Cmode,
                // see the file-level note above this state) makes every later
                // consumer -- llReg.cmode (loads), sq.io.alloc.payload.cacheMode
                // (stores), fastStore's classification -- automatically respect
                // DE=0 with no other code changes anywhere.
                s2Cmode  := Mux(cacheCtrl.map(_.dcacheEnabled).getOrElse(False),
                                xlate.rsp.cacheMode, m68k040.cache.CacheMode.INHIBITED)
                // mmu-split-second-half fix: a split access's second half (addrB) can
                // land on a genuinely different page than slot A, with independent
                // residency/perms — it needs its OWN real DTLB translation, not an
                // identity shortcut. Arm it (xlateBArm -> xlateVaddr selects addrB) and
                // resolve it in XLATE_B before falling through to the EXISTING XLATE
                // state (store-alloc / SQ-fwd-query logic there is entirely unchanged —
                // it already consumes s2PaddrB, just now genuinely translated). An
                // aligned (non-split) access skips XLATE_B entirely -> zero added
                // latency for the overwhelming-common case.
                when(s1TwoAccess) {
                  xlateBArm := True
                  goto(XLATE_B)
                } otherwise {
                  s2PaddrB := s1AddrB   // unused (sq.io.alloc.payload.validB=False / no
                                        // load ever reads llReg.paddrB without bDone),
                                        // kept deterministic for readability only.
                  goto(XLATE)
                }
              }
            } otherwise {
              s1Valid := True
            }
          } otherwise {
            when(!poisoned) { captureCompletion(B(0, 32 bits)) }   // non-memory (defensive)
          }
        }
      }

      // XLATE_B (mmu-split-second-half fix): split-access second half (addrB) real
      // DTLB translate. Entered ONLY from IDLE's slot-A resolve when `s1TwoAccess`.
      // Mirrors IDLE's own translate-and-wait pattern exactly (`xlateReady &&
      // reqMatch`), just for addrB's VPN (selected via `xlateBArm` -> `xlateVaddr`)
      // instead of s1Va's. The DTLB is a single-outstanding resource (one walker
      // instance, one miss-request register — see DtlbPlugin) so this necessarily
      // SEQUENCES after slot A's translate completed, never runs concurrently with
      // it. On a DTLB miss for addrB's page the walker may run (multi-cycle); the
      // FSM simply holds here (busy), exactly like IDLE holds S1 on a slot-A miss —
      // architecturally correct extra latency, not a regression (the fast/common
      // case, a TLB hit or the same page as slot A re-hitting `hrMatch`, resolves in
      // the same +1 cycle pattern as slot A's own translate).
      XLATE_B.whenIsActive {
        busy := True
        when(xlateReady && reqMatch) {
          when(xlateFault) {
            // addrB's OWN translation faulted (independently of slot A, which already
            // resolved cleanly — that's exactly why this state exists). Report the
            // fault at addrB's own address, not slot A's.
            when(!poisoned) { captureFault(faultAddr = s1AddrB) }
            xlateBArm := False
            busy    := False
            s1Valid := False
            goto(IDLE)
          } otherwise {
            // s1PaddrB (declared alongside s1Paddr, above) combines THIS response's
            // ppn with addrB's own page offset — the REAL translated physical address.
            s2PaddrB  := s1PaddrB
            xlateBArm := False
            goto(XLATE)
          }
        }
      }

      // Translated-paddr resolve: s2Paddr is now REGISTERED, so the SQ overlap-compare
      // / store-alloc fed from it start fresh this cycle (off the long DTLB-lookup arc).
      // s1 is still held (busy) so the SQ query / store-split inputs are stable.
      XLATE.whenIsActive {
        busy := True
        when(isStore) {
          // allocate into the SQ; "executes" immediately (no int dst). Store
          // completion drives a CONSTANT compData (off the SQ-compare arc), so it
          // captures here directly without the extra resolve cycle.
          //
          // BACK-PRESSURE: the SQ alloc Flow has no `ready`, so allocating while the
          // ring is FULL would overrun it (silent store-drop / count corruption — the
          // bug a >8-store MOVEM burst exposed). If full, DO NOT alloc; HOLD the µop
          // (busy, s1 held) in WAIT_SQ until an older committed store drains and frees
          // an entry. A split store consumes exactly ONE ring entry (slot B is a second
          // slot WITHIN the entry), so the single `io.full` check covers it. Deadlock-
          // free: the stalled store is strictly YOUNGER than the resident ones, which
          // commit incrementally in ROB order and drain (hold-until-ack), freeing a slot.
          // `sq.io.full` is read ONLY here in the execute FSM (off the IQ select cone),
          // mirroring the push-only `lsBusy` discipline — no IQ-critical-path impact.
          //
          // Task #139 mechanism #2: a poisoned (squashed) store must NEVER reach
          // `sq.io.alloc` — that's the root cause this fix closes (an orphaned
          // wrong-path SQ entry that never becomes `committed`, permanently blocking
          // ring drain). Drop it immediately, bypassing the full-check/WAIT_SQ path
          // entirely (no need to wait for ring space for a store we won't commit).
          when(poisoned) {
            busy    := False
            s1Valid := False
            goto(IDLE)
          } elsewhen(storePrivBlocked) {
            // Privileged store (e.g. MOVES.L) in user mode: complete immediately
            // (matching every other instruction's eager completes()) but NEVER
            // touch `sq.io.alloc` -- no memory write is ever queued. See
            // `storePrivBlocked`'s declaration comment for the full story.
            captureCompletion(B(0, 32 bits))
            busy    := False
            s1Valid := False
            goto(IDLE)
          } elsewhen(!sq.io.full) {
            sq.io.alloc.valid           := True
            sq.io.alloc.payload.precise := !fastStore
            when(fastStore) {
              // exactly today's path: architectural completion the SAME cycle as alloc.
              captureCompletion(B(0, 32 bits))
            } otherwise {
              // precise: latch the withheld completion for later replay (see
              // `deferCompletion` above) when the SQ confirms the drain.
              deferCompletion()
            }
            // !fastStore: allocate WITHOUT completing. The LS EU's single-outstanding
            // contract does not depend on completion -- it frees here regardless; the
            // store's ROB completion arrives later from the SQ's at-head drain
            // (StoreQueue's sqCompletion/sqFaultCompletion Flows, Task P2.4).
            busy    := False
            s1Valid := False
            goto(IDLE)
          } otherwise {
            goto(WAIT_SQ)
          }
        } otherwise {
          // load: capture the SQ-forward compare result (off the registered s2Paddr)
          // and resolve next cycle.
          fwdHit   := sq.io.fwd.rsp.hit
          fwdStall := sq.io.fwd.rsp.stall
          fwdData  := sq.io.fwd.rsp.data
          goto(RESOLVE)
        }
      }

      // Decision-resolve from the REGISTERED SQ-forward result (off the long
      // s1Paddr -> SQ-compare arc). s1 is still held (busy), so a re-query for the
      // stall-retry path reads stable SQ content.
      //
      // A CROSS load (s1TwoAccess) never takes the single-slot full-forward fast
      // path: the SQ holds a split store as two slots and a same-addr/size forward
      // can't span the boundary, so a cross load with ANY overlap STALLS until the
      // older store drains to memory, then reads the merged value from the cache.
      RESOLVE.whenIsActive {
        busy := True
        when(fwdHit && !s1TwoAccess) {
          // full-overlap forward: skip the cache (aligned only).
          // Task #139 mechanism #2: suppress for a poisoned (squashed) access.
          when(!poisoned) { captureCompletion(fwdData) }
          busy    := False
          s1Valid := False
          goto(IDLE)
        } elsewhen(fwdStall || (fwdHit && s1TwoAccess)) {
          // overlap with an older store: re-sample the SQ and retry. For a cross
          // load any overlap (hit or partial) holds until the store drains.
          fwdHit   := sq.io.fwd.rsp.hit
          fwdStall := sq.io.fwd.rsp.stall
          fwdData  := sq.io.fwd.rsp.data
        } otherwise {
          // no forward: CAPTURE the resolved access into the launch register (llReg)
          // and launch the cache off the flop next cycle (FMax #1 — severs the live
          // s1Va -> loadCmd.vaddr -> cmdTag/cmdSet AGU->tag arc). All inputs are flops/
          // shallow-stages off s1Base (s1Va/s1AddrB) or already-registered (s2Paddr/B).
          llReg.valid     := True
          llReg.vaddr     := s1Va
          llReg.paddr     := s2Paddr
          llReg.addrB     := s1AddrB
          llReg.paddrB    := s2PaddrB
          llReg.size      := u1.size
          llReg.cmode     := s2Cmode
          llReg.twoAccess := s1TwoAccess
          llReg.bDone     := False
          goto(LAUNCH)
        }
      }

      // Registered cache launch (FMax #1): `loadCmd` is driven from `llReg` (the flop)
      // by the cmd drive above — the cache tags on a REGISTERED address. Hold here
      // (llReg.valid asserted) until the cache accepts (loadCmd.fire), then go to WAIT
      // (aligned) / WAIT_A (cross). The +1 cache-launch cycle is latency-agnostic.
      LAUNCH.whenIsActive {
        busy := True
        when(dcache.loadCmd.fire) {
          // Slot A accepted -> drop loadCmd.valid (do NOT re-issue slot A while WAIT/
          // WAIT_A awaits its response). For a cross access WAIT_A re-asserts the launch
          // for slot B (bDone) once slot A's line lands.
          llReg.valid := False
          when(llReg.twoAccess) { aDone := False; goto(WAIT_A) }
          .otherwise { goto(WAIT) }
        }
      }

      // cmd accepted; the dcache delivers exactly one loadRsp (fixed for a hit,
      // late after a refill). Do NOT re-drive loadCmd here. Route the refilled
      // load through the SAME registered completion stage for uniformity.
      WAIT.whenIsActive {
        busy := True
        when(dcache.loadRsp.valid) {
          // Task #189: a genuine physical bus error (D-cache refill AXI resp
          // errored — SLVERR/DECERR) rides `dcache.loadRsp.payload.fault`. This
          // field was previously dead (DcachePlugin wrote it from a stale/always-
          // False source; nothing downstream ever read it — MMU faults are
          // detected earlier, straight off `xlate.rsp.fault`, never reaching the
          // cache at all). Now repurposed as the bus-fault carrier: complete as a
          // FAULT (vector 2, SSW.ATC=0 — atc=false) instead of a normal load.
          //
          // EXCEPTION (code-review fix): `u1.needsSupervisor` is also (task #189,
          // MicroOpAssembler's ldUop comment) tagged True for the generic load
          // that feeds a memory-source privileged commit-time SYSTEM op (e.g.
          // MOVE <ea>,SR). That load is program-order EARLIER than the sysOp µop
          // that owns the ACTUAL privilege check (RobPlugin's Track-D
          // sysPrivFault, at the sysOp's own commit) — a bus-fault on THIS load
          // would otherwise squash the sysOp before its privilege check ever
          // runs, wrongly delivering vector 2 instead of vector 8 for a user-mode
          // access (move_ea_sr_memsrc_priv.s). Suppress the fault report for
          // exactly this crack shape: complete normally with don't-care data (the
          // value is never actually applied — the later sysOp's own Track-D
          // check traps first), restoring the pre-task-189 behavior for the
          // faulting case while leaving the bus-error mechanism fully live for
          // every ordinary load.
          //
          // FURTHER EXCEPTION (2nd code-review fix): the suppression above must
          // only apply when the access is genuinely user-mode — gating on the
          // STATIC `u1.needsSupervisor` tag alone (regardless of the actual
          // runtime S bit) silently swallowed a real bus error for a SUPERVISOR-
          // mode `MOVE <ea>,SR`: the later sysOp's own privilege check would then
          // correctly NOT trap (already supervisor), so nothing else catches the
          // fault, and garbage/stale `loadRsp.payload.data` (meaningless on a
          // bus-error response — no line was allocated) would silently commit
          // into SR. Re-check the LIVE `xlate.req.supervisor` (the same signal
          // `captureFault` itself already reads into `compFaultSup` below) so the
          // suppression only fires for the genuine user-mode race this was built
          // for; a supervisor-mode bus error reports normally.
          when(!poisoned) {
            val suppressForLaterPrivCheck = u1.needsSupervisor && !xlate.req.supervisor
            when(dcache.loadRsp.payload.fault && !suppressForLaterPrivCheck) { captureFault(atc = false) }
            .otherwise { captureCompletion(dcache.loadRsp.payload.data) }
          }
          busy    := False
          s1Valid := False
          goto(IDLE)
        }
      }

      // CROSS slot A: capture line A (latched via aDone since loadRsp is a 1-cycle
      // pulse), then launch slot B at s1AddrB (the cache re-translates addrB's VPN:
      // same page for a line-cross, next page for a page-cross). Each slot can
      // independently hit / miss-refill the L1D.
      WAIT_A.whenIsActive {
        busy := True
        when(dcache.loadRsp.valid && !aDone) {
          // Slot A's line landed: latch it and ARM slot B's launch (set bDone so the
          // cmd drive selects llReg.addrB/paddrB) — but DO NOT assert valid yet; bDone
          // registers next cycle, so present slot B's cmd from the following cycle when
          // the addrB select is live (avoids a spurious slot-A re-issue this cycle).
          lineA       := dcache.loadRsp.payload.line
          aDone       := True
          llReg.bDone := True
        }
        when(aDone) {
          // bDone is now registered -> the cmd presents slot B (addrB/paddrB). Re-assert
          // the launch valid and hold until the cache accepts slot B, then drop it.
          llReg.valid := True
          when(dcache.loadCmd.fire) { llReg.valid := False; goto(WAIT_B) }
        }
      }

      // CROSS slot B: capture line B, merge sizeBytes spanning the boundary, done.
      WAIT_B.whenIsActive {
        busy := True
        when(dcache.loadRsp.valid) {
          val merged = m68k040.cache.DcacheByteLane.extractCross(
            lineA, dcache.loadRsp.payload.line, lineOff, u1.size)
          // Task #139 mechanism #2: suppress for a poisoned (squashed) access.
          when(!poisoned) { captureCompletion(merged) }
          busy    := False
          s1Valid := False
          goto(IDLE)
        }
      }

      // STORE back-pressure stall: the SQ was full when this store reached its alloc
      // point. Hold the µop (busy => issue.ready low => no younger µop enters; s1 is
      // held so the store-split inputs / s2Paddr stay stable) and do NOT alloc until
      // an older committed store drains (`io.full` deasserts). Then alloc + complete,
      // exactly as the non-full XLATE store path. Deadlock-free (the draining stores
      // are strictly older — incremental ROB-order commit guarantees forward progress).
      WAIT_SQ.whenIsActive {
        busy := True
        // Task #139 mechanism #2: a poisoned (squashed) store must never reach
        // `sq.io.alloc` -- drop it immediately rather than continuing to wait
        // for ring space for a store we will not commit.
        when(poisoned) {
          busy    := False
          s1Valid := False
          goto(IDLE)
        } elsewhen(!sq.io.full) {
          sq.io.alloc.valid           := True
          sq.io.alloc.payload.precise := !fastStore
          when(fastStore) { captureCompletion(B(0, 32 bits)) } otherwise { deferCompletion() }
          busy    := False
          s1Valid := False
          goto(IDLE)
        }
      }
    }

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
    // comp*/completion stage, UNLESS the live EU pipe already claimed it this cycle
    // (liveCompletionFires) -- a collision just retries next cycle; the backlog is
    // bounded by the SQ's own depth (pendPush can never outrun pendApply by more
    // than `pendDepth`, since a new precise alloc is itself gated on `!sq.io.full`).
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
    // BACKLOG PATH (an earlier entry is still waiting, or this cycle lost a
    // `liveCompletionFires` collision on a prior cycle): apply from the
    // REGISTERED `pendFault`/`pendFaultPayload`, exactly as before.
    val applyFast    = (pendApply === pendReady) && sq.io.sqCompletion.valid
    val applyBacklog = pendApply =/= pendReady
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
    val dbgIsIdle    = fsm.isActive(fsm.IDLE);    dbgIsIdle.simPublic()
    val dbgIsXlateB  = fsm.isActive(fsm.XLATE_B); dbgIsXlateB.simPublic()
    val dbgIsXlate   = fsm.isActive(fsm.XLATE);   dbgIsXlate.simPublic()
    val dbgIsResolve = fsm.isActive(fsm.RESOLVE); dbgIsResolve.simPublic()
    val dbgIsLaunch  = fsm.isActive(fsm.LAUNCH);  dbgIsLaunch.simPublic()
    val dbgIsWait    = fsm.isActive(fsm.WAIT);    dbgIsWait.simPublic()
    val dbgIsWaitA   = fsm.isActive(fsm.WAIT_A);  dbgIsWaitA.simPublic()
    val dbgIsWaitB   = fsm.isActive(fsm.WAIT_B);  dbgIsWaitB.simPublic()
    val dbgIsWaitSQ  = fsm.isActive(fsm.WAIT_SQ); dbgIsWaitSQ.simPublic()
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
    wbObs.nzvc      := compNzvc          // MOVE-to-mem store flags OR RTR CCR-restore NZVC
    wbObs.nzvcWrite := compNzvcWrite     // store NZVC or RTR CCR-restore
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
      // exception sequencer runs MMU-off (identity, slice-1): paddr == vaddr.
      dcache.loadCmd.payload.paddr := excLoadCmdVaddr
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
    }
    when(excActive && excStoreValid) {
      dcache.store.valid          := True
      dcache.store.payload        := excStorePayload
    }
    // Exc override of the (combinational) translation-request DRIVE — last-wins over
    // the base drive above. This feeds the SAME `reqDrv*` nets that get registered into
    // `reqReg` and drive `xlate.req` below, so the exc request is registered on the
    // identical 1-cycle boundary (FMax #2). The exc sequencer is a supervisor PHYSICAL
    // access and does NOT gate on dtRsp.ready (see ExceptionUnit), so the +1 register
    // latency only delays the (suppress-spurious-walk) request presentation — no
    // behavior change. Its own dtReq is already a RegNext upstream.
    when(excActive && excXlateValid) {
      reqDrvValid := True
      reqDrvVpn   := excXlateVpn
      reqDrvWrite := excXlateWrite
      reqDrvSup   := excXlateSupervisor
      // robId is meaningless for the exc (no speculative U/M tagging on the
      // serializing commit-side access); keep the base value.
    }

    // ── Single registered stage for the DTLB request interface (sever the cross-module
    // hitVec/hr*/missReqReg cone — see the reqReg rationale above). Driven from the
    // resolved `reqDrv*` (base or exc override). Emitted as the LAST drivers of
    // `xlate.req` so it is the sole writer.
    reqReg.valid := reqDrvValid
    reqReg.vpn   := reqDrvVpn
    reqReg.sup   := reqDrvSup
    reqReg.write := reqDrvWrite
    reqReg.robId := reqDrvRobId
    xlate.req.valid      := reqReg.valid
    xlate.req.vpn        := reqReg.vpn
    xlate.req.supervisor := reqReg.sup
    xlate.req.write      := reqReg.write
    xlateRobIdSig        := reqReg.robId

    excLoadCmdReady := dcache.loadCmd.ready
  }
}
