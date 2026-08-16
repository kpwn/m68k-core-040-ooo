package m68k040.exception

import m68k040.cache.{DLoadCmd, DLoadRsp, DStoreCmd, TranslationReq, TranslationRsp}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.fsm._

/** Commit-side exception sequencer (slice 1: format-$0 precise delivery + RTE).
  *
  * Instantiated inside the ROB's commit area. It is a SERIALIZING hardware FSM:
  * commit pauses while it runs (the ROB has already flushed younger work via the
  * registered redirect when the fault retired). Non-speculatively, at commit, it:
  *
  *  ENTRY (illegal / privilege fault):
  *    oldSr = (srSys << 8) | committedCcr
  *    srSys.S := 1 ; srSys.T := 0   (enter supervisor, clear trace)
  *    frameBase = SSP - 8           (A7 is banked to SSP since S becomes 1)
  *    stack format-$0 frame to frameBase (Musashi m68ki_stack_frame_0000 order):
  *      [base+0] = SR (16b, big-endian)         <- pushed LAST  (low addr)
  *      [base+2] = PC hi word                    }  push_32(pc)
  *      [base+4] = PC lo word                    }
  *      [base+6] = format/vector word = vec<<2   <- pushed FIRST (high addr)
  *    SSP := frameBase
  *    vec = load mem[VBR + vector*4]
  *    redirect fetch -> vec (supervisor)
  *
  *  RTE (return-from-exception, Task 4): pop the frame, restore SR (+ maybe bank
  *    A7 back to USP), restore PC, SSP += 8, redirect -> restored PC.
  *
  * The D-cache load/store ports + D-side translation request are exposed and
  * driven by the FSM; the full-core wiring MUXes them onto the real D-cache (the
  * LS EU is idle during a serializing exception). For these tests the MMU is off
  * (identity), so translation is a pass-through.
  */
class ExceptionUnit(
    val ss: SystemState,
    // The ONE 68040 MMU control (task #131): the MOVEC READ case below reads
    // mmuCtrl.mmuEnable/urp/srp for the Rc->Rn direction (TCR/URP/SRP). The WRITE
    // direction (real supervisor code programming the MMU) was ATTEMPTED and
    // REVERTED — see MmuControlPlugin's doc comment for the confirmed regression
    // it caused. Those Rc values fall through to the default RAZ/WI case on write.
    mmuCtrl: m68k040.services.MmuControlService,
    // The ONE owner of the non-renamed FP control state (FPCR / FPSR's non-FPCC bytes /
    // FPIAR — spec Decision 5). Read AND written by the SysKind.FMOVE_FPCTRL arm of
    // S_APPLY below, exactly like mmuCtrl is by the MOVEC arm. OPTIONAL (defaults to
    // null -> a local idle null-object built below): the standalone `SysOpApplySpec` DUT
    // hand-rolls its MMU mock and has no FpuControlPlugin, and must keep elaborating
    // unchanged. RobPlugin resolves the real one via `host.get[FpuControlService]`.
    fpuCtrlOpt: m68k040.services.FpuControlService = null,
    entryTrigger: Bool, entryVector: UInt, entryPc: UInt,
    // Format-$2 group-2 trap PPC = the trapping INSTRUCTION's PC (TRAPV/CHK/DIV0). For
    // TRAPV this equals entryPc-2 (a 2-byte op), but CHK/DIV0 are variable-length, so
    // the PPC is supplied explicitly. Default entryPc-2 keeps the old TRAPV-only unit
    // tests (which don't pass entryPpc) byte-for-byte.
    entryPpc: UInt = null,
    rteTrigger: Bool, rtePc: UInt,
    committedCcr: UInt,
    // Access-fault (vector 2) extras for the format-$7 frame. Default-driven idle
    // (a DUT that does not supply them — e.g. the slice-1 format-$0 unit tests —
    // passes the defaults; the $7 path is selected only when entryVector === 2).
    entryFaultAddr: UInt = U(0, 32 bits),
    entryFaultWr:   Bool = False,
    entryFaultSup:  Bool = False,
    // Instruction-fetch access fault: build a PROGRAM-space SSW (vs data) and force
    // the R/W bit to read. Default False => data fault (unchanged for LS faults).
    entryFaultInstr: Bool = False,
    // Task #189: the access SIZE (00=byte,01=word,10=long — LsFault.sizeBits'
    // encoding) for the SSW SIZE field, and the ATC bit (True=MMU/ATC-detected
    // fault, False=plain physical bus error). Defaults preserve the exact
    // pre-existing behavior for callers that don't pass them (unit tests): SIZE=0
    // (byte, the old always-zero field) and ATC=True (the old hardcoded value).
    entryFaultSize: UInt = U(0, 2 bits),
    entryFaultAtc:  Bool = True,
    // INTERRUPT entry (vs fault/trap). When True the entry SR-write additionally
    // raises the SR I-mask to `entryIplLevel` (so equal/lower interrupts are held
    // until RTE; NMI sets 7). Fault/trap entries leave the mask unchanged (S=1 /
    // T=0 only). Default False => the existing fault/trap behavior is unchanged.
    entryIsInterrupt: Bool = False,
    entryIplLevel:    UInt = U(0, 3 bits),
    // ── Commit-time PRIVILEGED SYSTEM op (MOVE-to-SR / MOVE-USP / MOVEC) ─────────
    // A serializing system op at the head (S=1 supervisor — the user-mode case is a
    // vector-8 fault delivered via entryTrigger instead). The FSM applies the effect
    // (write committed SR/USP/VBR + re-bank A7, or read system->Rn), pulses the obs
    // (post-state sysByte + re-banked A7), and redirects to sysNextPc (serialize).
    //   sysKind     : the decode.SysKind enum's ENCODED value (RobPlugin sends
    //                 `payload.sysKind.asBits.asUInt.resize(4)` at h0 — folded from
    //                 the old standalone `sysKindStore` Vec into RobPayload's Mem by
    //                 LUT-reduction Task B1). 4 bits since task #198 pushed the
    //                 element count past the old 3-bit ceiling. Do NOT write raw
    //                 ordinals against this field — S_APPLY dispatches via
    //                 `skOrd(SysKind.X)`, which derives the literal from the enum (see
    //                 the helper next to sysCapKind's declaration).
    //   sysReadDir  : read SYSTEM->Rn (True) vs write Rn->SYSTEM (False).
    //   sysVal      : the captured source VALUE (for a write).
    //   sysRc       : the 12-bit MOVEC control-reg id.
    //   sysDstPhys  : the PHYSICAL dst reg for a READ (the rename-allocated pdst of
    //                 the read µop — see its own declaration below for the full
    //                 rationale; the FSM writes the int PRF).
    //   sysPc       : the sysOp instruction's PC (the obs commit PC).
    //   sysNextPc   : the next instruction's PC (the redirect target after serialize).
    sysTrigger:   Bool = False,
    sysKind:      UInt = U(0, 4 bits),
    sysReadDir:   Bool = False,
    sysVal:       Bits = B(0, 32 bits),
    // Task 9b: the FMOVEM control-register LIST form's per-POSITION transfer values,
    // captured by RobPlugin at each marked load's in-order retirement (see
    // `RobPlugin.sysAux`). Read only by the `FMOVE_FPCTRL` S_APPLY arm, and only when
    // `sysRc`'s BATCH bit (bit 3) is set; the single-register fast-crack form leaves that
    // bit clear and keeps using `sysVal` exactly as before. Defaulted so the standalone
    // unit-test DUTs (`SysOpApplySpec`) need no change.
    sysAux:       Vec[Bits] = null,
    sysRc:        UInt = U(0, 12 bits),
    // The PHYSICAL dst reg for a READ (the rename-allocated pdst of the read µop). The
    // ROB commits the arch->pdst mapping at the serializing retire; the FSM writes the
    // system VALUE into PRF[pdst]. (An arbitrary Rn is renamed, unlike the identity A7.)
    sysDstPhys:   UInt = U(0, 6 bits),
    sysPc:        UInt = U(0, 32 bits),
    sysNextPc:    UInt = U(0, 32 bits),
    // Task 11: the ROB head's per-entry "this is a RECOGNIZED-but-unsupported FP op
    // awaiting software completion" bit + its command word (Task 10's
    // `fpuSoftwareComplete` / `fpuCmdWord`, stored at alloc). Read ONLY at vector-11
    // delivery, to latch the state a later FSAVE turns into the unimplemented-instruction
    // frame. Defaulted so every standalone unit DUT needs no change.
    entryFpuUnimp: Bool = False,
    entryFpuCmd:   Bits = B(0, 16 bits)) extends Area {

  // ── exposed D-cache request ports (wiring MUXes them onto the real cache) ────
  val dcLoadCmd  = Stream(DLoadCmd())
  val dcLoadRsp  = Flow(DLoadRsp())
  val dcLoadBusy = Bool()
  val dcStore    = Stream(DStoreCmd()); dcStore.simPublic()
  val dcStoreAck = Bool()
  // consumer-side inputs default (wiring drives them; allowOverride so a DUT that
  // does NOT wire the exception D-cache ports still elaborates — the wiring layer
  // OVERRIDES these when present).
  dcLoadCmd.ready.allowOverride;   dcLoadCmd.ready := False
  dcLoadRsp.valid.allowOverride;   dcLoadRsp.valid := False
  dcLoadRsp.payload.allowOverride; dcLoadRsp.payload.assignDontCare()
  dcLoadBusy.allowOverride;        dcLoadBusy := False
  dcStore.ready.allowOverride;     dcStore.ready := False
  dcStoreAck.allowOverride;        dcStoreAck := False

  // ── Task P5.5: cache-maintenance (CPUSH / CINV) dispatch ports ───────────────
  // `maintCmdOut` is a 1-cycle Flow pulse issued from S_APPLY's CPUSH/CINV arms and
  // wired (by the top level / each test DUT) to `DcacheService.maintCmd`, which
  // latches the payload and runs a multi-cycle Line/Page/All walk. It is DELIBERATELY
  // a single-cycle pulse: DcachePlugin's walk entry is `IDLE.when(maintCmdPort.valid)`,
  // so holding it asserted through the wait state below would re-trigger the walk.
  val maintCmdOut = Flow(m68k040.cache.CacheMaintCmd())
  maintCmdOut.valid := False
  maintCmdOut.payload.assignDontCare()
  // Completion handshake for the walk `maintCmdOut` kicked off (wired from
  // `DcacheService.maintDone`). Defaults True — mirroring the `sqDrained`/`dcQuiesced`
  // "no consumer wired, don't block" convention of this same file — so a standalone
  // ExceptionUnit-only DUT with no D-cache at all does not hang forever in
  // `S_MAINTWAIT`.
  val maintDoneIn = Bool(); maintDoneIn.allowOverride; maintDoneIn := True
  // 1-cycle I-cache full-invalidate pulse, wired to `IcachePlugin.logic
  // .maintInvalidateAll`. Pulsed whenever the CPUSH/CINV cache selector names IC (10)
  // or BC (11) — but NOT in the S_APPLY arm that issues the command: it fires on
  // `S_MAINTWAIT`'s completion transition, AFTER the D-side maintenance walk has
  // finished, so a fetch cannot re-cache pre-writeback code mid-walk (see the
  // S_MAINTWAIT comment for the full reasoning). The same pulse also invalidates the
  // BTB (fanned out at the wiring sites); RAS and gshare are deliberately NOT touched
  // — see IcachePlugin's `maintInvalidateAll` declaration for the recorded rationale.
  val icMaintPulse = Bool(); icMaintPulse := False

  // ── exposed D-side translation request (wiring MUXes it onto DTranslationService) ─
  // LEGACY, I-SIDE-SHAPED, AND UNCONSUMED. `dtReq`/`dtRsp` are `TranslationReq`/
  // `TranslationRsp` -- the COMBINATIONAL, UNTAGGED I-side bundle family. No DUT in this
  // project has ever wired them (confirmed: zero references outside this file), and they
  // are structurally incapable of talking to the real D-side `DTranslationService`, whose
  // contract is `Stream[DTranslationCmd]`/`Stream[DTranslationRsp]` plus an 8-bit token
  // to match responses across an elastic pipeline. They are kept only because the
  // existing frame/vector states drive `dtoVld`/`dtoVpn` into them; the REAL D-side
  // acquisition Task 11 added for FSAVE/FRESTORE is `dxReq*`/`dxRsp*` below.
  val dtReq = TranslationReq()
  val dtRsp = TranslationRsp()
  dtRsp.ready.allowOverride;     dtRsp.ready := True
  dtRsp.ppn.allowOverride;       dtRsp.ppn := U(0, 20 bits)
  dtRsp.cacheMode.allowOverride; dtRsp.cacheMode.assignDontCare()
  dtRsp.fault.allowOverride;     dtRsp.fault := False

  // ── REAL D-side DTLB acquisition (Task 11) ───────────────────────────────────
  // FSAVE's frame stores and FRESTORE's header load are the FIRST exception-sequencer
  // memory accesses that are genuinely VIRTUAL: every pre-existing one (entry frames,
  // RTE pops, vector fetches) is identity-physical by construction. So this unit needs a
  // real translation, and gets one by TIME-MULTIPLEXING the single `DTranslationService`
  // port -- which is deliberately single-producer by design -- via the already-proven
  // `excActive` MUX. `LsEuPlugin` already fully idles its own claim for the entire
  // duration `excActive` is held (`xlate.req.valid := !excActive && ...`, and
  // `xlate.rsp.ready := ... || excActive || ...`), exactly like it already hands the
  // D-cache load/store command ports themselves over; the wiring extends that same MUX.
  //
  // Flattened into plain signals rather than a `Stream`, matching how `excLoadCmd*`/
  // `excStore*` already cross into `LsEuPlugin` as loose `var`s.
  val dxReqValid = Bool();        dxReqValid := False;         dxReqValid.simPublic()
  val dxReqVpn   = UInt(20 bits); dxReqVpn   := U(0, 20 bits); dxReqVpn.simPublic()
  val dxReqWrite = Bool();        dxReqWrite := False
  /** Back-pressure from `DTranslationService.req.ready`, routed through the LS EU's MUX.
    * Defaults True -- the "no consumer wired, don't block" convention this file already
    * uses for `maintDoneIn`/`sqDrained`/`dcQuiesced` -- so a standalone DUT with no DTLB
    * never hangs in a translation-request state. */
  val dxReqReady = Bool(); dxReqReady.allowOverride; dxReqReady := True; dxReqReady.simPublic()

  /** Registered, tagged translation response, wired STRAIGHT from
    * `DTranslationService.rsp` (not through the LS EU) -- byte-for-byte how `dcLoadRsp`
    * is already wired straight from `DcacheService.loadRsp` while only the COMMAND side
    * goes through the LS EU's MUX.
    *
    * Defaults implement an IDENTITY translation that resolves in the very next cycle:
    * `valid` True, `ppn` echoing the VPN this unit last requested, `fault` False. That is
    * the same graceful degradation `maintDoneIn := True` provides, and it makes a
    * standalone ExceptionUnit DUT behave exactly like the MMU-off identity case. */
  val dxRspValid = Bool();        dxRspValid.allowOverride
  val dxRspPpn   = UInt(20 bits); dxRspPpn.allowOverride
  val dxRspFault = Bool();        dxRspFault.allowOverride
  val dxRspToken = UInt(m68k040.cache.DTranslationToken.Width bits); dxRspToken.allowOverride

  /** The token this unit stamps on every translation request it issues.
    *
    * `DTranslationToken`'s documented composition is `{backendEpoch, splitPhase,
    * robId[5:0]}`, and an ExceptionUnit-originated request has no natural `robId`. This
    * mirrors the ALREADY-ESTABLISHED sibling convention on the D-cache side, where
    * `DLoadToken`'s own doc comment reserves "[7] source (0 = LS ROB, 1 = serializing
    * exception unit)" and this unit stamps `U(0x80)` on `dcLoadCmd.payload.token`. So:
    * bit[7] = 1 (source = exception unit), bits[6:0] = 0.
    *
    * The token is NOT what makes response matching correct, and this is deliberate --
    * `LsEuPlugin` can in principle produce the same 8-bit value, since all 64 robIds and
    * both epoch/split bits are reachable. The REAL guarantee is structural, and holds
    * without any token at all:
    *   (a) `xlate.req.valid := !excActive && ...` -- the LS pipe issues NO translation
    *       request for the entire duration of an episode, so nothing new can be launched
    *       alongside ours;
    *   (b) `DtlbPlugin` is strictly SINGLE-OUTSTANDING -- `_req.ready := !missPending &&
    *       (!rspValid || _rsp.ready) && !flushAll` -- so it cannot accept our request
    *       while a walk is in flight;
    *   (c) `xlate.rsp.ready` is forced True while `excActive`, so any pre-episode
    *       straggler response is RETIRED in (at the latest) the same cycle our request
    *       fires, under DtlbPlugin's own documented accept-last ordering.
    * Therefore the first response observed AFTER `dxReqValid && dxReqReady` is ours. The
    * token match below is defence in depth against that argument being invalidated by a
    * future multi-outstanding DTLB, and the constant gives such a change an obvious hook.
    */
  val ExcDtlbToken = 0x80

  /** VPN of the translation request currently outstanding (latched on request fire).
    * Also the source of the unwired-DUT identity default just below. */
  val dxPendVpn = Reg(UInt(20 bits)) init 0
  dxRspValid := True
  dxRspPpn   := dxPendVpn
  dxRspFault := False
  dxRspToken := U(ExcDtlbToken, m68k040.cache.DTranslationToken.Width bits)
  /** True on the cycle the outstanding request's OWN response is on the bus. */
  val dxRspMine = dxRspValid && (dxRspToken === U(ExcDtlbToken, m68k040.cache.DTranslationToken.Width bits))

  // `active` is high whenever the FSM is mid-sequence; the wiring gates the MUX on it.
  val active = Bool()

  // ── Live committed A7 (arch reg 15) read back from the int PRF ────────────────
  // The full-core wiring drives this every cycle with the committed A7 value (PRF
  // read at the committed arch-15 phys mapping). It feeds ss.writeA7 so the committed
  // bank (usp/isp/msp selected by committed S,M) continuously mirrors the architectural
  // A7 — making a MOVE-to-SR (S,M) switch load a correctly-preserved bank and the
  // exception FSM read a live supervisor SP. Default = ss.a7 (a self-hold echo: writeA7
  // writes the active bank back to itself = no-op) so standalone unit-test DUTs that
  // don't wire the PRF readback are NOT stomped to 0; allowOverride lets the full-core
  // wiring override it with the real committed-A7 readback.
  val committedA7In = UInt(32 bits); committedA7In.allowOverride; committedA7In := ss.a7

  // ── FP control state (Task 9) ────────────────────────────────────────────────
  // The real service if the host supplied one, else a local all-idle null object so the
  // standalone unit DUTs keep elaborating (same OPTIONAL-service pattern RobPlugin uses
  // for mmuCtrl/intCtrl).
  val fpuCtrl: m68k040.services.FpuControlService =
    if (fpuCtrlOpt != null) fpuCtrlOpt else new m68k040.services.FpuControlService {
      override def fpcr  = U(0, 32 bits)
      override def fpsr  = U(0, 32 bits)
      override def fpiar = U(0, 32 bits)
      override def setFpcr   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
      override def setFpsr   = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
      override def setFpiar  = { val f = Flow(UInt(32 bits)); f.valid := False; f.payload := U(0, 32 bits); f }
      override def orFpsrExc = { val f = Flow(Bits(8 bits)); f.valid := False; f.payload := B(0, 8 bits); f }
      override def roundingMode = B(0, 2 bits)
      override def precision    = B(0, 2 bits)
      override def excEnable    = B(0, 8 bits)
      // Task 11. A standalone DUT with no FpuControlPlugin reports "no FP op has ever
      // executed and nothing is pending", so an FSAVE there emits the NULL frame — the
      // same all-idle degradation every other member of this null object provides.
      override def everExecuted    = False
      override def setEverExecuted = { val f = Flow(Bool()); f.valid := False; f.payload := False; f }
      override def uiValid         = False
      override def uiCmdReg1B      = B(0, 16 bits)
      override def uiSrcOperand    = B(0, 80 bits)
      override def uiDstOperand    = B(0, 80 bits)
      override def setUnimpFrame   = { val f = Flow(Bits(176 bits)); f.valid := False; f.payload := B(0, 176 bits); f }
      override def clearUnimp      = { val f = Flow(Bool()); f.valid := False; f.payload := False; f }
    }
  // ORDERING GUARD (loud, elaboration-time). Everything below reads `fpuCtrl.fpcr/.fpsr/
  // .fpiar` EAGERLY (the FPSR-read splice `fpsrArch` is a plain `val`, and the S_APPLY mux
  // is built during this constructor), so FpuControlPlugin's own `logic` Area must ALREADY
  // have elaborated -- i.e. it must be listed BEFORE RobPlugin in the plugin Seq, exactly
  // like MmuControlPlugin is. Getting that wrong otherwise surfaces as a bare
  // NullPointerException from deep inside a Fiber callback, which is genuinely hard to
  // read; fail with the actual instruction instead.
  require(fpuCtrl.fpcr != null && fpuCtrl.fpsr != null && fpuCtrl.fpiar != null,
    "FpuControlService resolved but its `logic` Area has not elaborated yet — list " +
      "FpuControlPlugin BEFORE RobPlugin in the plugin Seq (same constraint MmuControlPlugin has)")
  // A null object builds a FRESH Flow on every `setFpcr` call, so the S_APPLY arm below
  // must bind each port ONCE and drive that binding (otherwise its `.valid := True` would
  // land on a throwaway Flow and the surviving one would keep its idle default -- silent,
  // and invisible in the real-service case where it happens to work). Binding here also
  // makes the real-service case a plain reference, unchanged.
  private val setFpcrPort  = fpuCtrl.setFpcr
  private val setFpsrPort  = fpuCtrl.setFpsr
  private val setFpiarPort = fpuCtrl.setFpiar
  // Task 11: same bind-once discipline, same reason.
  private val setEverExecutedPort = fpuCtrl.setEverExecuted
  private val setUnimpFramePort   = fpuCtrl.setUnimpFrame
  private val clearUnimpPort      = fpuCtrl.clearUnimp

  // Live COMMITTED FPCC, read back from the FPCC PRF at its committed arch->phys mapping
  // by the full-core wiring -- byte-for-byte the `committedA7In` pattern above (an
  // optional, allowOverride input whose default keeps every unit DUT elaborating). FPCC
  // is RENAMED (spec Decision 4), so it is NOT in FpuControlPlugin; an architectural FPSR
  // READ has to splice it in. Internal layout is [3:0] = {NaN, I, Z, N}
  // (RegfileSpec.Fpcc's own doc); architectural FPSR[27:24] = {N, Z, I, NaN} is the
  // REVERSED presentation of that group.
  val committedFpccIn = Bits(4 bits); committedFpccIn.allowOverride; committedFpccIn := B(0, 4 bits)

  // ── Task 11: committed FP operand readbacks for the unimplemented-instruction frame ──
  // Same optional-input pattern as `committedA7In`/`committedFpccIn`: a full-core wiring
  // may read the FP PRF at the committed arch->phys mapping for the SOURCE FPm and
  // DESTINATION FPn named by the captured command word, and drive them here. A DUT that
  // does not wire them leaves the frame's operand fields zero, which degrades exactly
  // where the missing dependency is -- the frame still carries a correct header, a correct
  // length code and a correct CMDREG1B, which is what actually routes the FPSP.
  //
  // NOT WIRED IN ANY DUT AS OF THIS TASK, deliberately and explicitly: Task 1's FP regfile
  // has no committed-mapping read port exposed at this site yet. Both inputs therefore sit
  // at their zero defaults, STAG/DTAG read as "Zero" (001) and the ETEMP/FPTEMP fields are
  // zero. Wiring them is a pure addition when that read port lands.
  val committedFpSrcIn = Bits(80 bits); committedFpSrcIn.allowOverride; committedFpSrcIn := B(0, 80 bits)
  val committedFpDstIn = Bits(80 bits); committedFpDstIn.allowOverride; committedFpDstIn := B(0, 80 bits)
  /** The FP register numbers the captured command word names, exposed so a future wiring
    * can address the FP RAT with them. CMDREG1B layout (MC68040 UM Figure 9-8, 1989 1st
    * ed. p.9-33): [15:13] OPCLASS, [12:10] SRC (Rx), [9:7] DST (Ay), [6:0] OPMODE. */
  val fpuSrcArch = entryFpuCmd(12 downto 10).asUInt; fpuSrcArch.simPublic()
  val fpuDstArch = entryFpuCmd(9 downto 7).asUInt;   fpuDstArch.simPublic()
  // Architectural FPSR WRITE -> the renamed FPCC's committed physical register. Mirrors
  // rteNzvcWriteValid/rteNzvcWriteData EXACTLY (see its doc comment below for the full
  // argument for why a DIRECT committed-mapping write is required and a rename allocation
  // is not): the FMOVE-to-FPSR uop keeps writesFpcc FALSE, takes no freelist pop, and the
  // wiring writes whatever physical register fpccRat's committed mapping currently names.
  // Safe for the same reason A7's direct write is safe: this fires only inside the
  // SERIALIZING S_APPLY, where retire0/retire1 are blocked and the EUs are flushed, so no
  // same-cycle multi-writer collision on that physical register is possible.
  val fpccWriteValid = Bool();       fpccWriteValid := False;       fpccWriteValid.simPublic()
  val fpccWriteData  = Bits(4 bits); fpccWriteData  := B(0, 4 bits); fpccWriteData.simPublic()

  // FPCC internal {NaN(3), I(2), Z(1), N(0)} <-> architectural FPSR[27:24] {N,Z,I,NaN}.
  // Both directions are the same 4-bit reversal (an involution), written out twice only
  // so each call site reads in its own direction.
  private def fpccToArch(internal: Bits): Bits =
    internal(0) ## internal(1) ## internal(2) ## internal(3)   // -> {N,Z,I,NaN}
  private def fpccFromArch(arch: Bits): Bits =
    arch(0) ## arch(1) ## arch(2) ## arch(3)                   // -> {NaN,I,Z,N}
  // Architectural FPSR read = the non-FPCC bytes from FpuControlPlugin (which stores 0 in
  // [27:24] structurally) OR the live committed FPCC, reversed into arch bit order.
  val fpsrArch = fpuCtrl.fpsr | (fpccToArch(committedFpccIn).asUInt.resize(32) |<< 24)

  // The store queue is drained (no committed store still heading to memory). The
  // entry FSM waits for this before stacking its frame so it never steals the
  // D-cache store port from an older committed store's in-flight write-through
  // (which would silently drop that store). Default True (unit DUTs w/o an LS EU).
  val sqDrained = Bool(); sqDrained.allowOverride; sqDrained := True

  // Task P5.4: the D-cache datapath is genuinely idle (load/refill FSM, eviction
  // engine, store S0..S3 pipe, and BOTH AXI write-completion flag pairs). Wired from
  // `DcacheService.maintQuiesced`; default True for the many unit DUTs that have no
  // D-cache at all.
  //
  // WHY the commit-time sysOp path needs this (a confirmed real safety gap, not a
  // theoretical one). CPUSH/CINV — unlike every sysOp before them (MOVEC / STOP /
  // PFLUSHA / PTEST / RESET / MOVE-to-SR / MOVE-USP, none of which touch the D-cache
  // at all) — hand a cache-maintenance walk the D-cache's shared array read port and
  // its AXI write channels. `excActive` alone does NOT make that safe: it stops the
  // LS EU from issuing anything NEW, but an OLDER, ALREADY-COMMITTED store can still
  // be draining out of the StoreQueue (commit and drain are decoupled by the precise-
  // drain design), and a load refill / dirty-victim eviction accepted before the flush
  // landed can still be mid-AXI-transaction. Either one concurrently owns exactly the
  // resources the walk would take.
  //
  // The ENTRY (`E_DRAIN`) and RTE (`R_DRAIN`) paths already wait on `sqDrained` for
  // precisely the analogous reason ("wait for older committed stores to fully drain
  // before we use the store port"); the sysOp path went straight to `S_APPLY` the very
  // next cycle because no sysOp had ever needed the guarantee. `S_DRAIN` below closes
  // that gap for the whole sysOp family.
  val dcQuiesced = Bool(); dcQuiesced.allowOverride; dcQuiesced := True

  // ── captured per-event state ────────────────────────────────────────────────
  val curVec   = Reg(UInt(8 bits)); curVec.simPublic()
  val curPc    = Reg(UInt(32 bits)); curPc.simPublic()   // ENTRY: faulting PC to stack; RTE: restored PC
  val oldSr    = Reg(UInt(16 bits))   // ENTRY: SR to stack
  val frameBase= Reg(UInt(32 bits))   // ENTRY: new SP = supervisor bank (M?MSP:ISP) - frame size; RTE: old SP
  val vecTarget= Reg(UInt(32 bits))   // redirect target
  // ENTRY: is this an access fault (vector 2)? -> stack a format-$7 frame (30 words)
  // instead of format-$0 (4 words). Captured at trigger.
  val curIs7   = RegInit(False)
  // ENTRY: is this a format-$2 trap (TRAPV / CHK / DIV0 on the 68040)? -> stack a
  // 6-word format-$2 frame {SR, PC, 0x2000|vec<<2, PPC} (Musashi m68ki_stack_frame_0010
  // for CPU_TYPE 68040). Captured at trigger. PPC = the trap instr's own PC.
  val curIs2   = RegInit(False)
  val curPpc   = Reg(UInt(32 bits))   // format-$2 PPC (the trap instruction's PC)
  // ENTRY: is this an INTERRUPT entry? -> raise the SR I-mask to curLevel in the
  // entry SR-write (fault/trap entries leave the mask unchanged). Captured at trigger.
  val curIsInt = RegInit(False)
  val curLevel = Reg(UInt(3 bits))    // interrupt level for the I-mask raise
  val curFault = Reg(UInt(32 bits))   // faulting VA (EA + fault-address fields of $7)
  val curSsw   = Reg(UInt(16 bits))   // $7 special status word

  // ── Task #132: interrupt-with-M=1 throwaway frame (format-$1, Slice B) ──────────
  // Real 68040 semantics (Musashi m68ki_exception_interrupt, m68kcpu.h:2226-2235):
  // an INTERRUPT taken while M=1 stacks a NORMAL format-$0 frame on the CURRENT
  // active stack (MSP, since M hasn't been cleared yet), THEN clears M (rebanking
  // to ISP) and stacks a SECOND, format-$1 "throwaway" frame — same {PC,SR,vector}
  // content, just a different format nibble — on the NOW-active ISP. The handler
  // runs on ISP with M=0. RTE reads the throwaway frame first: it applies the
  // frame's SR (which still carries M=1, so A7 re-banks BACK to MSP) and DISCARDS
  // the frame's PC, then — still within the SAME rte instruction — re-reads the
  // format word now sitting at the top of MSP (the real format-$0 frame) and pops
  // THAT one for real (PC used, final SR applied). Net effect: both stacks end up
  // back at their pre-entry depth; only ONE observable commit (the final pop).
  // curThrowaway/frameBase2/stFrame2 are the ENTRY-side bookkeeping; the RTE side
  // needs no extra state — `popIs1` (mirrors popIs7/popIs2) drives the loop-back.
  val curThrowaway = RegInit(False)   // this INTERRUPT entry needs a $1 throwaway frame
  val frameBase2   = Reg(UInt(32 bits))   // ISP-relative base for the $1 frame (entry only)
  val stFrame2     = RegInit(False)       // E_STORE loop is currently on the 2nd ($1) frame

  // ── Commit-time SYSTEM op captured state (latched at sysTrigger) ─────────────
  // Width is 4 bits since task #198 added PTEST (the 9th SysKind element) — but the
  // S_APPLY switch below no longer hard-codes ANY ordinal: it compares against
  // `skOrd(SysKind.X)` (see the helper right below), so an enum insertion/reorder can
  // never silently re-point an arm again. The `require` next to that helper fails
  // elaboration loudly if the enum ever outgrows this 4-bit field.
  val sysCapKind    = Reg(UInt(4 bits))
  val sysCapReadDir = Reg(Bool())
  val sysCapVal     = Reg(Bits(32 bits))
  val sysCapRc      = Reg(UInt(12 bits))
  val sysCapDstPhys = Reg(UInt(6 bits))
  val sysCapPc      = Reg(UInt(32 bits))
  val sysCapNextPc  = Reg(UInt(32 bits))

  // ── SysKind -> raw `sysKind`/`sysCapKind` ordinal (SYMBOLIC, elaboration-time) ──
  // The `sysKind` port is a plain UInt, not a SpinalEnumCraft, because RobPlugin hands
  // it over as `sysKindStore(h0).asBits.asUInt.resize(4)` (the ROB stores the enum, the
  // port carries its encoded value). The S_APPLY switch below therefore has to compare
  // against NUMBERS — but it must never SPELL those numbers out by hand: Task P5.2
  // inserted SysKind.CINV between CPUSH and PFLUSHA, which shifted PFLUSHA 7->8 and
  // PTEST 8->9 and silently re-pointed three hand-written literal arms (CINV took over
  // PFLUSHA's TLB flush, PFLUSHA took over PTEST's MMUSR write, and PTEST's arm matched
  // nothing at all). `skOrd` derives each literal from the enum itself, using the SAME
  // encoding `asBits` uses on the producer side, so any future insertion/reorder is
  // automatically tracked.
  private def skOrd(e: SpinalEnumElement[m68k040.decode.SysKind.type]): UInt =
    U(m68k040.decode.SysKind.defaultEncoding.getValue(e), 4 bits)
  // Loud elaboration-time guard: if SysKind ever grows past 16 elements, the 4-bit
  // `sysKind` port (and RobPlugin's `.resize(4)`) would silently TRUNCATE the ordinal.
  // Fail the build instead.
  require(
    m68k040.decode.SysKind.defaultEncoding.getWidth(m68k040.decode.SysKind) <= 4,
    s"SysKind no longer fits the 4-bit sysKind/sysCapKind field " +
      s"(needs ${m68k040.decode.SysKind.defaultEncoding.getWidth(m68k040.decode.SysKind)} bits) — " +
      "widen ExceptionUnit.sysKind/sysCapKind AND RobPlugin's .resize(...) together")

  // RTE-own-PC, captured at rteTrigger (task #177): `rtePc` aliases a LIVE ROB
  // signal (`payload.pc` read at h0, i.e. `p0.pc` -- folded from the old standalone
  // `pcStore` Vec into RobPayload's Mem by LUT-reduction Task B1; same live-aliasing
  // behavior, different storage) indexed by the head pointer. The RTE FSM is
  // multi-cycle (IDLE -rteTrigger-> R_DRAIN -> ... -> the format-check state that
  // actually consumes rtePc, many cycles later). While the FSM runs, `excSquash`
  // (asserted every cycle once `exc.active`) forces the ROB's `tail := head` every
  // cycle, so any younger speculative µop the front-end allocates in the meantime
  // reuses the SAME physical ROB slot h0 still points at -- silently overwriting
  // `p0.pc` with a DIFFERENT (soon-to-be-squashed) instruction's PC before the
  // format-error path ever reads it. Reading the live `rtePc` wire late (as the
  // format-error path used to) therefore returns garbage, not RTE's own PC -- it
  // must be LATCHED here, on the SAME cycle rteTrigger fires (before
  // excActive/excSquash starts reusing the slot), exactly like sysCapPc is latched
  // at sysTrigger below.
  val rteCapPc = Reg(UInt(32 bits))

  // RTE pop accumulators
  val popSr = Reg(UInt(16 bits))
  val popPc = Reg(UInt(32 bits))
  // RTE format select: the stacked format word @base+6 top nibble (0 = format-$0,
  // 7 = format-$7 access-fault). Chooses the pop size (8 vs 60 bytes). For $7 RTE
  // restores SR/PC and RESUMES at the stacked PC (= faulting instr -> re-executes),
  // discarding the rest of the frame — matching MAME's RTE case 7.
  val popIs7 = RegInit(False); popIs7.simPublic()
  // format-$2 (top nibble 2): the 6-word trap frame (TRAPV/CHK/DIV0). RTE pops 12
  // bytes and resumes at the stacked PC (= the next instruction; TRAPV is not
  // restarted), discarding the format word + PPC. Matches Musashi RTE case 2.
  val popIs2 = RegInit(False); popIs2.simPublic()
  // format-$1 (top nibble 1, task #132): the M=1-interrupt throwaway frame. RTE
  // applies its SR (re-banking back to MSP, M restored to 1) and DISCARDS its PC,
  // then loops back to pop the REAL format-$0 frame now at the top of MSP — see
  // R_REDIR. Musashi: m68k_in.c rte's `case 1: /* Throwaway */ ... goto rte_loop`.
  val popIs1 = RegInit(False); popIs1.simPublic()
  val popFmtWord = Reg(UInt(16 bits)); popFmtWord.simPublic()

  // ── redirect outputs (the ROB ORs these into its registered redirect) ────────
  val redirectValid = Bool(); redirectValid := False
  val redirectPc    = UInt(32 bits); redirectPc := vecTarget

  // ── commit observation for the exception/RTE "instruction" (lock-step). At the
  // redirect cycle the event delivers its POST-state: the handler-entry PC (entry)
  // / restored PC (RTE), the resulting SR (16b) and A7. Mirrors Musashi's trace
  // step for the faulting / RTE instruction. ───────────────────────────────────
  val obsFire    = Bool();        obsFire := False
  val obsPc      = UInt(32 bits); obsPc := U(0, 32 bits)
  val obsSysByte = UInt(8 bits);  obsSysByte := U(0, 8 bits)   // post-event SR system byte
  val obsA7      = UInt(32 bits); obsA7 := U(0, 32 bits)
  // MOVE-to-SR writes the FULL CCR (X N Z V C = sysVal[4:0]) as an ABSOLUTE value (vs
  // the per-bit fold of NZVC the CHK entry uses). When `obsSetCcr5Valid`, the lock-step
  // whitebox SETS the running CCR to `obsSetCcr5` for this obs step (the only commit
  // path that writes the full CCR outside a normal Wb). Default invalid.
  val obsSetCcr5Valid = Bool();       obsSetCcr5Valid := False
  val obsSetCcr5      = UInt(5 bits); obsSetCcr5      := U(0, 5 bits)
  // True when this obs is an INTERRUPT entry (vs a fault/trap entry or RTE). The
  // lock-step harness drops the separate interrupt-entry record because Musashi's
  // trace BUNDLES the interrupt entry with the first handler instruction in one
  // step (an async interrupt consumes no user instruction); a synchronous
  // fault/trap entry is its own oracle step (the faulting instruction consumed it),
  // so its obs is kept. The post-entry state is still verified by the first handler
  // instruction's commit (it carries the mask-raised SR + decremented A7).
  val obsIsInterrupt = Bool();    obsIsInterrupt := False
  // True when this obs is an exception/trap ENTRY (vs an RTE). The ROB uses it to
  // apply a faulting-instruction CCR fold (CHK) only to the entry step, not RTE.
  val obsIsEntry = Bool();        obsIsEntry := False

  // ── Architectural A7 (int reg 15) write-back. The committed A7 lives in BOTH the
  // SystemState bank (ss.isp/ss.msp/ss.usp) AND the int register file (arch reg 15) the
  // datapath reads. When the exception changes A7 (entry: SSP-=8; RTE: restore +
  // maybe re-bank to USP), it must update reg 15 so the handler's (A7)/disp(A7)
  // stack accesses see the new SP. The full-core wiring connects this to an int PRF
  // write port (phys = committed arch-15 mapping; identity phys-15 while A7 is
  // unrenamed). obsFire qualifies it (same cycle as the event's commit obs). ─────
  val a7WriteValid = Bool();        a7WriteValid := obsFire
  val a7WriteData  = UInt(32 bits); a7WriteData := obsA7

  // ── Generalized arch-reg PRF write (commit-time system op READ direction) ─────
  // MOVE-USP / MOVEC READ (system reg -> Rn) writes an ARBITRARY int arch reg (the Rn),
  // not just A7. The full-core wiring connects this to an int PRF write port at the
  // committed arch->phys mapping (identity while unrenamed in the tested programs). The
  // S_APPLY FSM state pulses it. Default idle.
  val sysRegWriteValid = Bool();        sysRegWriteValid := False;        sysRegWriteValid.simPublic()
  val sysRegWritePhys  = UInt(6 bits);  sysRegWritePhys  := U(0, 6 bits);  sysRegWritePhys.simPublic()
  val sysRegWriteData  = UInt(32 bits); sysRegWriteData  := U(0, 32 bits); sysRegWriteData.simPublic()
  // PFLUSHA: a 1-cycle pulse consumed by DtlbPlugin/ItlbPlugin's `flushAll` port (mirrors
  // the existing `umFlush` top-level fan-out — see FullCoreSynth.scala/the test DUTs).
  // Only PFLUSHA drives this (S_APPLY's SysKind.PFLUSHA arm); every other sysKind —
  // CPUSH/CINV included — leaves it False.
  val sysFlushAllValid = Bool();        sysFlushAllValid := False;        sysFlushAllValid.simPublic()

  // ── RTE CCR restore -> REAL flags PRF (task #176, redesigned task-176-regression) ──
  // Fires exactly at RTE's REAL frame pop (R_REDIR, non-throwaway branch — the SAME
  // cycle obsFire/redirectValid fire for RTE). Carries the frame's popped CCR bits
  // {X,N,Z,V,C}.
  //
  // ORIGINAL (task #176) design wrote these into a FRESH rename-allocated pNzvcDst/
  // pXDst (RTE's µop carried writesNzvc/writesX so decode/rename popped new physical
  // registers), then a RobPlugin commit block folded the new arch->phys mapping into
  // nzvcRat/xRat. That mechanism caused a CONFIRMED regression under back-to-back/
  // nested exception storms (exc_stack_atomicity_stress, pea_aline_irq_storm,
  // via1_t1_irq_storm): RTE's OWN freelist pop sits "uncommitted" (from the
  // Freelist's `commHead` perspective) for the ENTIRE multi-cycle R_DRAIN..R_REDIR
  // FSM run, because `flushing` (which gates the ROB's retire0/1 AND, via
  // `rc.flushPort`, RenameStage's RAT-rollback/freelist-flush) stays asserted the
  // WHOLE time via `excSquash`. The freelist's `head := commHead` fires EVERY cycle
  // during that window, so RTE's own not-yet-pushed pNzvcDst/pXDst are treated as
  // still-speculative and their ring slot is handed right back out — and since
  // `IssueQueuePlugin` forces `push.ready` True during its OWN matching flush
  // (`readyReg := True` in its flush branch), the frontend/rename keep firing and
  // popping the SAME (never-advanced) id for the whole window. A wrong-path
  // instruction renamed during this window can therefore receive the EXACT SAME
  // physical nzvc/x register RTE itself is mid-flight with; if that wrong-path uop's
  // EU write lands (it can survive briefly once the IQ's flush finally drops for one
  // cycle before the frontend's OWN redirect lands), it silently clobbers the
  // register RTE's OWN restore -- now also the live nzvcRat/xRat mapping, because of
  // the same commit -- is relying on. Confirmed via direct trace + a bisection
  // matrix: disabling ONLY the RobPlugin commit-block (keeping the write ports)
  // cured all 3 regressions; disabling ONLY the write ports (keeping the commit)
  // did not.
  //
  // NEW design mirrors the ALREADY-PROVEN-SAFE `a7WriteValid`/`a7WriteData` pattern
  // (SystemState's committed A7 restore, ExceptionUnit.scala class-level comment
  // above): NZVC/X are archDepth=1 singleton "architectural registers" whose
  // COMMITTED physical mapping essentially never needs to change here -- only its
  // CONTENTS do. So RTE's µop keeps writesNzvc/writesX FALSE (no rename allocation,
  // no freelist interaction, no RAT remap, no exposure window at all) and the wiring
  // plugins write `rteNzvcWriteData`/`rteXWriteData` DIRECTLY into whatever physical
  // register `RenameStage.committedPhysNzvc`/`committedPhysX` CURRENTLY names (a
  // plain in-place content update, exactly like `a7Wr.address := committedPhysA7`) --
  // see FullCoreSynth.scala/FuzzDut.scala/IpcBenchSpec.scala/ExecuteLockStepSpec.scala.
  // Safe for the same reason A7's direct write is safe: nothing else can be
  // committing to this same physical register while RTE's serializing FSM owns the
  // ROB head (retire0/1 are blocked the whole time), so a same-cycle multi-writer
  // collision cannot occur, and there is no freelist pop/push at all to race.
  //
  // task #192: this SAME pair of ports is now ALSO pulsed by S_REDIR for a
  // MOVE-to-SR / STOP commit (the two sysKinds that write the FULL CCR via
  // `obsSetCcr5`). Before this fix, MOVE-to-SR/STOP's CCR write landed ONLY in
  // RobPlugin's `committedCcr` whitebox-tracking shadow (fed by `obsSetCcr5Valid`,
  // consumed for exception-frame stacking + lock-step comparison) -- never in the
  // REAL NZVC/X physical registers an ordinary Bcc/ADDX/flag-consumer actually
  // reads. A Bcc immediately following `MOVE #imm,SR`/`MOVE Dn,SR`/`MOVE <ea>,SR`/
  // STOP therefore saw STALE flags (ported-tests move_ea_sr_ccr_direct family).
  // Reusing rteNzvcWriteValid/rteXWriteValid (rather than adding a new port) is
  // safe: this is the SAME single FSM, RTE's own pulse (R_POP/R_SRREQ family, see
  // below) and this sysOp pulse (S_REDIR) are DIFFERENT states of the SAME `fsm`,
  // so `whenIsActive` makes the two drivers mutually exclusive by construction --
  // and MOVE-to-SR/STOP are themselves serializing sysOps (sysRetire retires the
  // ROB head ALONE), so the same "nothing else can be committing to this physical
  // register concurrently" safety argument applies unchanged.
  val rteNzvcWriteValid = Bool();       rteNzvcWriteValid := False;       rteNzvcWriteValid.simPublic()
  val rteNzvcWriteData  = Bits(4 bits); rteNzvcWriteData  := B(0, 4 bits); rteNzvcWriteData.simPublic()
  val rteXWriteValid    = Bool();       rteXWriteValid    := False;       rteXWriteValid.simPublic()
  val rteXWriteData     = Bool();       rteXWriteData     := False;       rteXWriteData.simPublic()

  // ── SystemState write defaults (the FSM pulses them) ────────────────────────
  ss.setSrSys.valid := False; ss.setSrSys.payload := U(0, 8 bits)
  ss.setIsp.valid   := False; ss.setIsp.payload   := U(0, 32 bits)
  ss.setMsp.valid   := False; ss.setMsp.payload   := U(0, 32 bits)
  ss.setVbr.valid   := False; ss.setVbr.payload   := U(0, 32 bits)
  ss.setUsp.valid   := False; ss.setUsp.payload   := U(0, 32 bits)
  // LIVE-COHERENT committed A7: drive writeA7 EVERY cycle with the live committed A7
  // (routed by committed S,M inside SystemState). This keeps ss.usp/isp/msp mirroring
  // the architectural A7 of the active bank. The FSM's setIsp/setMsp/setUsp pulses on
  // serializing cycles WIN over writeA7 (they are listed AFTER writeA7 in SystemState's
  // when-chain — later-when-wins), so a frame-store SP write is not clobbered.
  // SETTLE CAVEAT (Slice A scope): committedA7In (the PRF readback) lags the architectural
  // A7 by the Mem-write->async-read latency, so the ACTIVE bank tracks A7 with ~1-2 cycle
  // lag. This is invisible to real consumers: the exc FSM reads the bank only after E_DRAIN
  // (settled), and MOVE-to-SR (S,M) switches are serializing. A RAPID M re-toggle
  // (M=0->1->0 within the settle window) is NOT validated here — it could leave a
  // briefly-active bank's shadow stale when deselected. Add a directed test for that before
  // Slice B (interrupt throwaway frame) relies on cross-toggle preservation.
  ss.writeA7.valid  := True; ss.writeA7.payload  := committedA7In

  // ── D-cache STORE: REGISTERED output (FMax). The frame-word store payload is a
  // combinational mux off the FSM step `stStep`; driving it straight onto the
  // D-cache store port put `stStep -> store-merge -> SQ-overlap-compare` on the
  // LS EU's critical SQ-forward arc. We compute the store into combinational
  // `sto*` and REGISTER it onto `dcStore` (the exception FSM is serializing /
  // multi-cycle + ack-gated, so the extra cycle is free). The registered Stream
  // remains valid and payload-stable until accepted; this cuts the arc without
  // reverting to a one-cycle pulse that can be dropped. ────────────────────────
  val stoVld   = Bool();        stoVld := False
  val stoPaddr = UInt(32 bits); stoPaddr := U(0, 32 bits)
  val stoData  = Bits(32 bits); stoData := B(0, 32 bits)
  val stoSize  = Size();        stoSize := Size.LONG
  val stoValidReg = RegInit(False)
  val stoPaddrReg = Reg(UInt(32 bits))
  val stoDataReg  = Reg(Bits(32 bits))
  val stoSizeReg  = Reg(Size())
  val stoCmodeReg = Reg(m68k040.cache.CacheMode())
  dcStore.valid           := stoValidReg
  dcStore.payload.paddr   := stoPaddrReg
  dcStore.payload.data    := stoDataReg
  dcStore.payload.size    := stoSizeReg
  dcStore.payload.useStrb := False
  dcStore.payload.strb    := B(0, 16 bits)
  dcStore.payload.lineData:= B(0, 128 bits)
  // ── Exception-sequencer D-cache access cacheability (Task P5.7 root-cause fix) ──
  // The exception sequencer's frame/vector accesses are always identity-physical
  // (MMU-off in slice-1), so there is no page attribute to consult -- but CACR.DE
  // still applies. Task P5.6 made DE=0 mean LITERALLY fully uncached for every data
  // access and folded that into `LsEuPlugin`'s `s2Cmode` capture; these two ports are
  // the D-cache's OTHER data-access requesters and were missed, leaving a REAL
  // coherency hole with DE=0:
  //   - an ordinary program store is INHIBITED, so it writes AXI and NEVER touches
  //     the L1D array (Task P1.4: `stS2Inhibited` skips the RMW entirely);
  //   - this unit's frame/vector LOAD, hardcoded WRITETHROUGH, still MISSES ->
  //     REFILLS -> **ALLOCATES** a resident line (`doAllocate` only excludes
  //     INHIBITED);
  //   - so once a line is resident, every later program store to it is invisible to
  //     the array, and the NEXT exception-sequencer load of that line HITS the stale
  //     copy.
  // That is exactly the classic trap-handler idiom: the handler reads the stacked
  // frame, PATCHES the stacked PC/SR with an ordinary store to skip the faulting
  // instruction, then RTEs -- and the RTE's frame load reads back the UNPATCHED
  // value, re-entering the same fault forever (a deterministic HANG, unaffected by
  // raising the cycle budget). Same hole for a VBR vector the program rewrites after
  // an earlier exception already pulled that line in.
  // Fix: honour DE here too, exactly like `s2Cmode` does -- DE=0 => INHIBITED (no
  // allocate, no array read, no stale hit); DE=1 => WRITETHROUGH, byte-for-byte the
  // previous behaviour. `ss.cacr(31)` is the same bit RobPlugin already publishes as
  // `CacheControlService.dcacheEnabled` (`_dcacheEnabled := exc.ss.cacr(31)`), read
  // directly here since `ss` is this unit's own state -- no new port or dependency.
  val excCacheMode = Mux(ss.cacr(31), m68k040.cache.CacheMode.WRITETHROUGH,
                                      m68k040.cache.CacheMode.INHIBITED)
  dcStore.payload.cacheMode := stoCmodeReg
  // The exception sequencer's frame pushes are conceptually "always awaited" --
  // driving precise=True means a hypothetical bus error there is simply never
  // routed to the new async diagnostic channel (Task P4.5), which is correct:
  // today's exception-store path has no fault-reporting mechanism at all and
  // this task must not invent one for it.
  dcStore.payload.precise := True
  when(dcStore.fire) {
    stoValidReg := False
  }
  when(stoVld) {
    // E_STORE is a one-cycle capture state and cannot be re-entered until the
    // previous store's terminal ack, so this never overwrites a held command.
    assert(!stoValidReg,
      "ExceptionUnit: attempted to overwrite an unaccepted frame-store command",
      FAILURE)
    stoValidReg := True
    stoPaddrReg := stoPaddr
    stoDataReg  := stoData
    stoSizeReg  := stoSize
    stoCmodeReg := excCacheMode
  }

  // ── D-cache LOAD + D-TLB req: REGISTERED outputs (FMax). The frame/vector load
  // vaddr (off `frameBase`/`vecTarget`) drives the D-cache hit/miss-tag + the LS
  // EU's SQ-overlap compare; combinationally that put `frameBase -> miss-tag ->
  // SQ-compare -> fwdData` on the critical arc. We register the load cmd + the
  // matching D-TLB request. The load states hold the request until the registered
  // `dcLoadCmd.fire`, so the extra cycle is free + the cmd/vpn stay consistent. ──
  val ldoVld   = Bool();        ldoVld := False
  val ldoVaddr = UInt(32 bits); ldoVaddr := U(0, 32 bits)
  val ldoSize  = Size();        ldoSize := Size.LONG
  // Task 11: the PHYSICAL address of the load. Every pre-existing exception-sequencer
  // load is identity-physical, so this defaults to `ldoVaddr` and those states are
  // byte-for-byte unchanged; only FRESTORE's header read (whose address is genuinely
  // virtual) overrides it with a real DTLB-translated PA.
  val ldoPaddr = UInt(32 bits); ldoPaddr := ldoVaddr
  // This is a real Stream source, not a delayed pulse. A plain RegNext(ldoVld)
  // leaves valid asserted for one tail cycle after the request state observes
  // fire. The former II=3 D-cache masked that protocol violation with ready=0;
  // an II=1 cache accepts the tail as a duplicate (RTE then consumes shifted frame
  // words). Capture once and hold the complete payload until the actual handshake.
  val ldoValidReg = RegInit(False)
  val ldoVaddrReg = Reg(UInt(32 bits))
  val ldoPaddrReg = Reg(UInt(32 bits))
  val ldoSizeReg  = Reg(Size())
  val ldoCmodeReg = Reg(m68k040.cache.CacheMode())
  dcLoadCmd.valid         := ldoValidReg
  dcLoadCmd.payload.vaddr := ldoVaddrReg
  // Identity (paddr == vaddr) for every pre-existing exception-sequencer load, because
  // `ldoPaddr` defaults to `ldoVaddr`; FRESTORE's header read supplies a real
  // DTLB-translated PA here instead (Task 11).
  dcLoadCmd.payload.paddr := ldoPaddrReg
  dcLoadCmd.payload.size  := ldoSizeReg
  // Identity-physical, same rationale (and same DE=0 fix) as dcStore.payload.cacheMode
  // above -- this is in fact the ALLOCATING half of that coherency hole.
  // NOTE: this particular field is currently INERT in every integrated DUT --
  // FuzzDut/FullCoreSynth wire only `dcLoadCmd.payload.vaddr/size` into the LS EU and
  // LsEuPlugin's exception-arbitration mux REGENERATES the cache mode itself. That mux
  // carries the same DE fold (see its comment); this assignment is kept correct and in
  // sync so the field is never a trap for a future wiring that does forward it.
  dcLoadCmd.payload.cacheMode := ldoCmodeReg
  dcLoadCmd.payload.token := U(0x80, m68k040.cache.DLoadToken.Width bits)
  when(dcLoadCmd.fire) {
    ldoValidReg := False
  }
  when(ldoVld && !ldoValidReg) {
    ldoValidReg := True
    ldoVaddrReg := ldoVaddr
    ldoPaddrReg := ldoPaddr
    ldoSizeReg  := ldoSize
    ldoCmodeReg := excCacheMode
  }

  val dtoVld = Bool();        dtoVld := False
  val dtoVpn = UInt(20 bits); dtoVpn := U(0, 20 bits)
  val dtoWr  = Bool();        dtoWr := False
  dtReq.valid      := RegNext(dtoVld) init False
  dtReq.vpn        := RegNext(dtoVpn)
  dtReq.supervisor := True
  dtReq.write      := RegNext(dtoWr) init False

  // helper: present one aligned store of `sz` at `va`. The store paddr is the
  // identity-translated va (MMU off in slice-1 exception tests; a real-DTLB frame
  // translation is a fast-follow). Both sto* (the store) and dto* (the matching
  // D-TLB request, for the cache's coherence) are REGISTERED onto dcStore/dtReq.
  def driveStore(va: UInt, sz: Size.C, data: Bits): Unit = {
    dtoVld := True; dtoVpn := va(31 downto 12); dtoWr := True
    stoVld   := True
    stoPaddr := va
    stoSize  := sz
    stoData  := data.resize(32)
  }
  // Identity supervisor PHYSICAL store WITHOUT a translation request. The exception
  // frame/vector accesses are physical (paddr == va); the D-cache store port uses the
  // paddr directly. Driving the DTLB here would (with the MMU live) start a walk whose
  // multi-cycle not-ready stalls the store state -> re-pulsed stores. So we don't.
  def driveStoreNoXlate(va: UInt, sz: Size.C, data: Bits): Unit = {
    stoVld   := True
    stoPaddr := va
    stoSize  := sz
    stoData  := data.resize(32)
  }

  // ENTRY frame-store step. The ExceptionUnit deliberately keeps one frame word
  // outstanding: issue through the held Stream, then wait for its write-through ACK
  // before constructing the next. Word index is from frameBase (LOW address), ascending.
  //   format-$0 (illegal/privilege): 4 words [SR, PC hi, PC lo, vec<<2].
  //   format-$7 (access fault):     30 words ($3C) — MAME m68ki_stack_frame_0111:
  //     [+0x00]=SR [+0x02]=PChi [+0x04]=PClo [+0x06]=0x7000|(vec<<2)
  //     [+0x08]=EAhi [+0x0a]=EAlo [+0x0c]=SSW [+0x0e..0x12]=0 (3 words)
  //     [+0x14]=faultAddr hi [+0x16]=faultAddr lo [+0x18..0x3a]=0 (18 words).
  //   stStep counts WORDS (5 bits, 0..29). lastStep = 3 ($0) or 29 ($7).
  val stStep = Reg(UInt(5 bits)) init 0; stStep.simPublic()
  // Task #163: a frame word can land at a D-cache LINE-relative offset of 15 (the
  // last byte of a 16-byte line) whenever frameBase/frameBase2 is ODD-aligned such
  // that (frameBase + step*2) & 0xF == 15 -- the WORD's second byte then belongs to
  // the NEXT cache line entirely. `driveStoreNoXlate`'s plain {paddr,size} store
  // path (DcachePlugin's non-useStrb DcacheByteLane.storeStrb/storeData) has NO
  // cross-line-boundary handling (unlike the ordinary LsEuPlugin store path, which
  // explicitly computes a two-access split for exactly this case) -- it silently
  // DROPS the byte that would fall at offset 16 (storeStrb's `bits` array only spans
  // indices 0..15, so a would-be index-16 strobe bit never gets set) while
  // storeData's `out(off+1)` wraps a 4-bit index mod 16 back to offset 0 (harmless
  // there ONLY because storeStrb correctly leaves that merged byte un-strobed).  Net
  // effect: the crossing word's SECOND byte is never written -- neither into the
  // cache nor the AXI write-through -- leaving stale memory content, discovered via
  // exc_aline_odd_sp_mmu_dcache (frameBase=0xFFFD lands word[1]=PC[31:16] at line
  // offset 15..16). FIX: when a frame word would cross, split it into two ordinary
  // (non-crossing, single-byte) driveStoreNoXlate pushes instead of one WORD push.
  // `stSplitLow` sequences the second (low) byte of a just-split word.
  val stSplitLow = Reg(Bool()) init False
  // lastStep: curIs7/curIs2 are always False for an interrupt entry (never $7/$2),
  // so this already correctly reads 4 words (U(3)) for BOTH passes of a throwaway
  // entry (frame $0 then frame $1, each 8 bytes) — no stFrame2 dependency needed.
  val lastStep = Mux(curIs7, U(29, 5 bits), Mux(curIs2, U(5, 5 bits), U(3, 5 bits)))
  // Task #132: while stacking the 2nd (throwaway) frame, address off frameBase2
  // (ISP-relative) instead of frameBase (MSP-relative).
  def frameWordAddr(step: UInt): UInt = (Mux(stFrame2, frameBase2, frameBase) + (step << 1)).resized
  // Common low-4-word prefix: SR, PC hi, PC lo, format/vector word. The format/vector
  // word is curVec<<2 for $0, 0x2000|(curVec<<2) for $2, 0x7000|(curVec<<2) for $7,
  // 0x1000|(curVec<<2) for the $1 throwaway frame (task #132; stFrame2 selects it —
  // curIs7/curIs2 are already False for any interrupt, so no conflict with those).
  val fmtVecWord = Mux(stFrame2,        (U(0x1000, 16 bits) | (curVec << 2).resize(16)),
                   Mux(curIs7, (U(0x7000, 16 bits) | (curVec << 2).resize(16)),
                   Mux(curIs2, (U(0x2000, 16 bits) | (curVec << 2).resize(16)),
                               (curVec << 2).resize(16))))
  def frameWordData(step: UInt): Bits = {
    val out = Bits(16 bits)
    out := B(0, 16 bits)
    switch(step) {
      is(U(0, 5 bits)) { out := oldSr.asBits }
      is(U(1, 5 bits)) { out := curPc(31 downto 16).asBits }
      is(U(2, 5 bits)) { out := curPc(15 downto 0).asBits }
      is(U(3, 5 bits)) { out := fmtVecWord.asBits }
      // format-$2 words (steps 4..5): PPC (the trap instruction's own PC). For $7
      // these same step indices carry the EA (overridden just below).
      is(U(4, 5 bits))  { out := Mux(curIs2, curPpc(31 downto 16), curFault(31 downto 16)).asBits }
      is(U(5, 5 bits))  { out := Mux(curIs2, curPpc(15 downto 0),  curFault(15 downto 0)).asBits }
      // $7-only words (steps 6..29). For $0/$2 these steps never execute.
      is(U(6, 5 bits))  { out := curSsw.asBits }                   // SSW
      is(U(10, 5 bits)) { out := curFault(31 downto 16).asBits }   // fault addr hi
      is(U(11, 5 bits)) { out := curFault(15 downto 0).asBits }    // fault addr lo
      // all other steps (7,8,9,12..29) stack 0 (internal registers).
    }
    out
  }

  // ══ FSAVE / FRESTORE state frames (Task 11) ═══════════════════════════════════
  //
  // PRIMARY SOURCE for every offset and every field position below (Step 2, executed --
  // this is a transcription off the rendered figure, not a derivation):
  //   MC68040 User's Manual, 1989 FIRST EDITION, section 9.7 "Floating-Point State
  //   Frames", **Figure 9-7 "Floating-Point State Frames (Sheet 2 of 2)", page 9-32**,
  //   with the field SEMANTICS from the definition list on pages 9-33/9-34.
  // Independently corroborated against Motorola's own FPSP (fpsp040) frame offsets --
  // STAG at +$04, CMDREG1B at +$08, DTAG at +$0C, the E1/E3 byte at +$10 with E1 = bit
  // 2, FPTEMP at +$14, ETEMP at +$20 -- which agree with the figure exactly.
  //
  //   $00  [31:24] VERSION NUMBER   [23:16] $28 (length code)  [15:0] reserved
  //   $04  [31:29] STAG             (3-bit source data type)   rest reserved
  //   $08  [31:16] CMDREG1B         (the faulting command word) [15:0] reserved
  //   $0C  [31:29] DTAG             (3-bit destination data type) rest reserved
  //   $10  bit 26 = E1              (i.e. byte $10 bit 2)      rest reserved
  //   $14  [31] FPTS  [30:16] FPTE  [15:0] reserved     } FPTEMP = DESTINATION operand
  //   $18  FPTM[63:32]                                  }
  //   $1C  FPTM[31:00]                                  }
  //   $20  [31] ETS   [30:16] ETE   [15:0] reserved     } ETEMP  = SOURCE operand
  //   $24  ETM[63:32]                                   }
  //   $28  ETM[31:00]                                   }
  //                                        11 longwords = 44 bytes = 22 words
  //
  // NOTE, RECORDED BECAUSE IT IS A REAL PRIMARY-SOURCE CONFLICT AND NOT A TRANSCRIPTION
  // SLIP: the LATER revision of this manual (M68040UM/AD rev 1, section 9.7, where the
  // same figure is renumbered **Figure 9-10**) specifies a **26-word / 52-byte**
  // unimplemented-instruction frame with length code $30, and says so in prose in four
  // separate places. The 1989 first edition says 22 words / 44 bytes / $28, which is what
  // the approved design spec (docs/superpowers/specs/2026-08-14-fpu-fpsp-design.md) locked
  // and what this task implements. The two editions genuinely disagree; the null, idle and
  // busy frames are identical in both. Changing to the 52-byte shape is a DESIGN-level
  // decision, not an implementation choice, and is flagged for one -- see this task's
  // report. Nothing else in this file depends on which is chosen.
  //
  // The FPU state-frame VERSION byte, finalized here as the design spec directs ("$40 is
  // a reasonable default choice ... finalized at implementation time, not a design
  // blocker"). $41 because (a) it is exactly what Figure 9-7's own idle-frame illustration
  // shows and what MAME's 68040 FSAVE writes (`m68ki_write_32(addr, 0x41000000)`), (b) the
  // vendored `fpu_fsave_idle_format_byte.s` asserts exactly 0x41, and (c) the Q700 FPSP's
  // `if ((frame[0] & 0xf) == 0) skip_restore` optimization needs a NON-ZERO low nibble on a
  // non-null frame -- $40 would make every idle frame look null to it. The NULL frame
  // still FORCES byte $00 to $00 (Figure 9-7 shows `$00`, not the version, in the null
  // frame's top byte), keeping that same optimization correct in the other direction.
  val FPU_FRAME_VERSION = 0x41

  val fsFrameBase = Reg(UInt(32 bits)); fsFrameBase.simPublic()  // LOW address of the frame
  val fsSize      = Reg(UInt(8 bits))                            // total frame size in BYTES
  val fsIsNull    = RegInit(False)
  val fsIsUnimp   = RegInit(False)
  val fsStep      = Reg(UInt(5 bits)) init 0; fsStep.simPublic() // WORD index, 0..21
  val fsSplitLow  = RegInit(False)
  val fsAnWrite   = RegInit(False)                               // does this op write An back?
  // Translate-on-VPN-change bookkeeping (design addendum point 3). A 44-byte frame spans
  // at most one page boundary, so a full replica of LsEuPlugin's two-pass cross-page split
  // is disproportionate: compare each step's VPN against the last translated one and
  // re-request only when it changes. At most 2 translation requests per frame, never 22,
  // and every word still gets a real, current translation.
  val fsPpn      = Reg(UInt(20 bits)) init 0
  val fsLastVpn  = Reg(UInt(20 bits)) init 0
  val fsVpnValid = RegInit(False)
  /** Sticky: a DTLB translation for an FSAVE/FRESTORE frame access FAULTED. Drives
    * `RobPlugin.coreHaltedIn` through the top-level wiring. See F_HALT. */
  val fsXlateFault = RegInit(False); fsXlateFault.simPublic()

  /** 3-bit STAG/DTAG operand data-type tag from an 80-bit extended value.
    * Encodings verbatim from the MC68040 UM (1989 1st ed.) p.9-34 "STAG, DTAG":
    *   000 Normalized / 001 Zero / 010 Infinity / 011 NAN
    *   100 Extended precision denormalized or unnormalized input
    *   101 Single or double precision denormalized input
    * 101 is not reachable here: this unit only ever sees operands already widened to
    * extended precision, so a denormal is always the 100 case. */
  def fpTag(v: Bits): Bits = {
    val exp        = v(78 downto 64)
    val man        = v(63 downto 0)
    val expAllOnes = exp.andR
    val expZero    = !exp.orR
    val manZero    = !man.orR
    Mux(expAllOnes, Mux(manZero, B"3'b010", B"3'b011"),
    Mux(expZero,    Mux(manZero, B"3'b001", B"3'b100"), B"3'b000"))
  }

  def fsFrameWordAddr(step: UInt): UInt = (fsFrameBase + (step << 1)).resized
  def fsFrameWordData(step: UInt): Bits = {
    val out = Bits(16 bits); out := B(0, 16 bits)
    // Header word: [15:8] = version, [7:0] = the length-in-hex indicator (NOT a format
    // enum -- the pop size is 4 + this byte, for every frame flavour).
    //   null : $00 $00   (version FORCED to $00 -- identifies null)
    //   idle : $41 $00   (0 extra bytes)
    //   unimp: $41 $28   (40 extra bytes -> 44 total)
    val hdr = Mux(fsIsNull,  B(0x0000, 16 bits),
              Mux(fsIsUnimp, B((FPU_FRAME_VERSION << 8) | 0x28, 16 bits),
                             B((FPU_FRAME_VERSION << 8) | 0x00, 16 bits)))
    val src = fpuCtrl.uiSrcOperand   // ETEMP  : [79] sign, [78:64] exponent, [63:0] mantissa
    val dst = fpuCtrl.uiDstOperand   // FPTEMP : same layout
    switch(step) {
      is(U(0,  5 bits)) { out := hdr }                                        // $00 version|len
      is(U(2,  5 bits)) { out := fpTag(src) ## B(0, 13 bits) }                // $04 STAG  [31:29]
      is(U(4,  5 bits)) { out := fpuCtrl.uiCmdReg1B }                         // $08 CMDREG1B [31:16]
      is(U(6,  5 bits)) { out := fpTag(dst) ## B(0, 13 bits) }                // $0C DTAG  [31:29]
      is(U(8,  5 bits)) { out := B(0, 5 bits) ## True ## B(0, 10 bits) }      // $10 E1 = bit 26
      is(U(10, 5 bits)) { out := dst(79 downto 64) }                          // $14 FPTS|FPTE
      is(U(12, 5 bits)) { out := dst(63 downto 48) }                          // $18 FPTM[63:32]
      is(U(13, 5 bits)) { out := dst(47 downto 32) }
      is(U(14, 5 bits)) { out := dst(31 downto 16) }                          // $1C FPTM[31:00]
      is(U(15, 5 bits)) { out := dst(15 downto 0) }
      is(U(16, 5 bits)) { out := src(79 downto 64) }                          // $20 ETS|ETE
      is(U(18, 5 bits)) { out := src(63 downto 48) }                          // $24 ETM[63:32]
      is(U(19, 5 bits)) { out := src(47 downto 32) }
      is(U(20, 5 bits)) { out := src(31 downto 16) }                          // $28 ETM[31:00]
      is(U(21, 5 bits)) { out := src(15 downto 0) }
      // every other word is reserved -> 0 (the default above)
    }
    out
  }
  /** 22 words ($2C bytes) for the unimplemented-instruction frame, 2 words (4 bytes) for
    * null and idle alike (both are a single longword per Figure 9-7). */
  val fsLastStep = Mux(fsIsUnimp, U(21, 5 bits), U(1, 5 bits))

  val fsm = new StateMachine {
    val IDLE      = new State with EntryPoint
    // ENTRY path
    val E_DRAIN   = new State    // wait for the SQ to drain before grabbing the port
    val E_STORE   = new State    // issue one frame word store
    val E_STWAIT  = new State    // await its write-through ACK; advance step
    val E_VECREQ  = new State    // issue vector load @ VBR+vec*4
    val E_VECWAIT = new State    // await vector load rsp
    val E_REDIR   = new State    // pulse redirect, commit SSP/SR, done
    // RTE path
    val R_DRAIN   = new State    // wait for the SQ to drain + the live-A7 readback to
                                  // settle before capturing frameBase (mirrors E_DRAIN)
    val R_SRREQ   = new State    // load SR word @ base+0
    val R_SRWAIT  = new State
    // Task #189: the PC field is read as TWO WORD sub-reads (hi @ base+2, lo @
    // base+4) instead of one LONG @ base+2. A single LONG read's byte-lane extract
    // can silently read PAST a 16-byte D-cache line (`DcacheByteLane.extract`
    // has no cross-line awareness — only the general LsEuPlugin AGU path splits a
    // crossing access into two proper sub-accesses; this exception-FSM read path
    // is a simpler, purpose-built sequencer that never got that treatment). A
    // format-$2 (12-byte) frame's PC field lands at base+2, which — unlike the
    // format-$0/$7 cases that predate task #189 — now regularly sits at a
    // non-4-aligned frameBase (any supSp-12), making a 4-byte read spanning a
    // line boundary a real, not just theoretical, case (found via
    // exc_addr_error_odd_rte.s: frameBase=0xFFEC, PC field @0xFFEE crosses into
    // the 0xFFF0 line). Splitting to WORD granularity narrows the exposure to
    // the SAME residual class the store side already carries post-task-163 (a
    // WORD landing exactly at line-relative offset 15) — not fully eliminated,
    // but no test in the corpus hits that narrower case; a full byte-level
    // cross-line read split (mirroring E_STORE's `crosses`/`stSplitLow`) would
    // close it completely if a future test ever does.
    val R_PCREQ   = new State    // load PC hi word @ base+2
    val R_PCWAIT  = new State
    val R_PCREQ2  = new State    // load PC lo word @ base+4
    val R_PCWAIT2 = new State
    val R_FMTREQ  = new State    // load format word @ base+6 (select $0 vs $7 pop)
    val R_FMTWAIT = new State
    val R_REDIR   = new State
    // Commit-time SYSTEM-op path (MOVE-to-SR / MOVE-USP / MOVEC). S_APPLY writes the
    // committed system state + the int PRF (read dir) in one cycle; S_REDIR pulses the
    // obs (post-state) + redirects (so the re-banked A7 / new S settle before the obs).
    // Task P5.4: wait for the SQ to drain + the D-cache to go idle before APPLYing.
    // Mirrors E_DRAIN/R_DRAIN's existing shape; see `dcQuiesced`'s doc comment above
    // for the full rationale (CPUSH/CINV's maintenance walk takes the D-cache array
    // port + AXI write channels, which excActive alone does NOT free).
    val S_DRAIN   = new State
    val S_APPLY   = new State
    // Task P5.5: CPUSH/CINV only. S_APPLY pulses `maintCmdOut` for exactly ONE cycle,
    // but the D-cache maintenance walk it starts takes many cycles (a Page/All scope
    // walks all 128 sets x 4 ways, and every dirty match adds an AXI writeback). Hold
    // the sequencer here — still excActive, so the front-end stays squashed and the LS
    // pipe stays idle — until `maintDoneIn` pulses, THEN redirect. Mirrors E_STWAIT's
    // shape (issue in one state, await the ack in the next). Every OTHER sysKind skips
    // this state entirely and goes straight to S_REDIR, so no existing sysOp pays for
    // it. Distinct from and complementary to S_DRAIN, which waits for the D-cache to be
    // idle BEFORE the walk starts.
    val S_MAINTWAIT = new State
    val S_REDIR   = new State
    // ── Task 11: FSAVE / FRESTORE state-frame transfer ────────────────────────────
    // Same two-state REQ/WAIT shape as every other memory step in this FSM
    // (E_STORE/E_STWAIT, R_SRREQ/R_SRWAIT, E_VECREQ/E_VECWAIT, ...), plus a dedicated
    // translation REQ/WAIT pair per direction because these are the only accesses this
    // unit makes that are genuinely virtual.
    val F_XREQ    = new State   // FSAVE: request a DTLB translation for the current word
    val F_XWAIT   = new State   // FSAVE: await it; latch PPN or escalate on fault
    val F_STORE   = new State   // FSAVE: issue one frame word (translated)
    val F_STWAIT  = new State   // FSAVE: await its write-through ACK; advance
    val F_RXREQ   = new State   // FRESTORE: request the header word's translation
    val F_RXWAIT  = new State   // FRESTORE: await it
    val F_HDRREQ  = new State   // FRESTORE: load the header word
    val F_HDRWAIT = new State   // FRESTORE: dispatch on version / length byte
    val F_RDONE   = new State   // FRESTORE: An += popSize
    val F_RSETTLE = new State   // FRESTORE: let the committed-A7 readback settle
    val F_HALT    = new State   // terminal: a frame translation faulted (see below)

    IDLE.whenIsActive {
      when(entryTrigger) {
        // An INTERRUPT entry is always a format-$0 frame (never $7/$2), regardless
        // of its vector value (autovector 24+level or a vectored 0..255). Fault/trap
        // entries select $7 (access fault, vector 2) / $2 (TRAPV, vector 7) by vector.
        val is7 = !entryIsInterrupt && (entryVector === 2)   // access fault -> format-$7
        // The 68040 group-2 traps stack a 6-word format-$2 frame {SR, PC(=nextPc),
        // 0x2000|vec<<2, PPC} (Musashi m68ki_stack_frame_0010): TRAPV (vector 7), CHK
        // (vector 6), DIV0/integer-divide-by-zero (vector 5), AND F-line (vector 11).
        //
        // F-line's inclusion here is DISPUTED, not settled — flagging honestly rather
        // than re-asserting task #176's original "confirmed" framing, which a code
        // review found overconfident. Musashi's own m68ki_exception_1111 unconditionally
        // uses format-$0 for vector 11 regardless of CPU_TYPE, and the MC68040 User's
        // Manual (9.6.1) ties format-$2 specifically to a RECOGNIZED-but-hardware-
        // unimplemented FPU coprocessor-ID-1 opcode (cpID = op[11:9] == 001, Table
        // 9-10) — a genuinely illegal/unrecognized F-line opcode (which is arguably ALL
        // this FPU-less core can ever produce, since it has zero FPU decode) should by
        // that reading stack format-$0 instead, matching Musashi. BUT the vendored
        // m68k-ooo ported test `exc_user_vbr_rte_matrix.s` hardcodes an explicit
        // `cmp.l #0x0001FFF4,%a7` check (i.e. format-$2, 12 bytes) for its own F-line
        // opcode (`0xF123`, cpID=000 — the "illegal" case by the manual reading above),
        // and this project's standing goal is to match the m68k-ooo test corpus. Kept
        // format-$2 here (diverging from Musashi, like the already-established CPUSH
        // gap below) to match the test corpus; the corresponding ExecuteLockStepSpec
        // "line-F opcode" test uses `pcOnly` for the same reason. If a future session
        // gets more definitive primary-source clarity (e.g. finding real 68040 silicon
        // or a more complete manual excerpt that resolves the cpID question), revisit
        // this — it's a genuine unresolved disagreement between two sources of truth,
        // not a confidently-verified fact either direction.
        // Vector 3 (ADDRESS ERROR, task #189) is ALSO a format-$2, 12-byte frame — a
        // taken control transfer (JMP/JSR/BRA/Bcc/BSR/DBcc/RTS/RTR) to an odd target
        // (M68040UM §8.2.3). Detected dynamically in the branch EU (BranchEuPlugin's
        // `addrErr`), delivered via the same generalized euFault->faultVecStore path
        // as TRAPV/CHK/DIV0. Unlike those three (whose PC field = nextPc, "already
        // executed, resume after"), address error's PC field = the TRANSFER
        // instruction's OWN pc (retry-after-fix semantics — the test corpus's
        // handlers patch the frame and RTE back to re-attempt the same transfer).
        // That already falls out for free: ibrUop/relative-branch µops all default
        // faultUsesNextPc=False, so the ROB's fault-PC mux already yields the
        // instruction's own pc (payload capture at alloc, see RobPlugin's
        // exceptionPc comment) -> entryPc IS that pc.
        // Vector 9 (TRACE, task #193) is ALSO a format-$2, 12-byte frame — confirmed
        // against BOTH Musashi (m68ki_exception_trace -> m68ki_stack_frame_0010 for
        // any CPU_TYPE above 68010) and the ported test corpus itself
        // (exc_trace_t1.s hardcodes an explicit `cmp.l #0x00002024,%d0` check on the
        // captured format/vector word, i.e. format-$2 | vec9<<2). entryPc = the
        // RESUME pc (the instruction AFTER the traced one — RobPlugin's
        // tracePendingFire path); entryPpc = the traced instruction's OWN pc
        // (RobPlugin's tracePendingPpc), mirroring Musashi's REG_PC/REG_PPC split.
        val is2 = !entryIsInterrupt &&
                  ((entryVector === 7) || (entryVector === 6) || (entryVector === 5) ||
                   (entryVector === 11) || (entryVector === 3) || (entryVector === 9))
        // PPC: supplied explicitly (variable-length CHK/DIV0); fall back to entryPc-2
        // for callers that don't pass it (the TRAPV-only unit tests, 2-byte op).
        val ppc = if (entryPpc != null) entryPpc else (entryPc - 2).resized
        // Address error's extra format-$2 word (SP+8, "ADDRESS") carries the faulting
        // ODD TARGET (entryFaultAddr, execute-time-computed — see BranchEuPlugin's
        // `addrErr`/`faultAddr` and RobPlugin's euFaultCompletion->faultAddrStore),
        // NOT the trapping-instruction PC that TRAPV/CHK/DIV0 put there. Every other
        // vector routed through is2 keeps using `ppc` unchanged.
        val ppcOrTarget = Mux(entryVector === 3, entryFaultAddr, ppc.resized)
        curVec    := entryVector
        curPc     := entryPc
        curIs7    := is7
        curIs2    := is2
        curIsInt  := entryIsInterrupt
        curLevel  := entryIplLevel
        curPpc    := ppcOrTarget.resized
        curFault  := entryFaultAddr
        // ── Task 11: capture the unimplemented-instruction state ─────────────────
        // A RECOGNIZED-but-unsupported FP op is being handed to FPSP via vector 11.
        // Latch what a subsequent FSAVE needs to build the 44-byte unimplemented-
        // instruction frame: the command word (CMDREG1B) plus both operands, from which
        // STAG/DTAG and the FPTS/FPTE/FPTM + ETS/ETE/ETM fields are derived at emit time.
        // Non-speculative: exception delivery is at commit.
        when(entryVector === 11 && entryFpuUnimp) {
          setUnimpFramePort.valid   := True
          setUnimpFramePort.payload := entryFpuCmd ## committedFpSrcIn ## committedFpDstIn
          // FPIAR := the faulting instruction's OWN PC (its architectural definition,
          // "last FP exception PC"). `entryPc` is the POST-instruction PC for this class
          // (Task 10 sets faultUsesNextPc so a real FPSP can RTE past the instruction),
          // so the instruction's own PC is `ppc`.
          setFpiarPort.valid   := True
          setFpiarPort.payload := ppcOrTarget.resized
        }
        // SSW = (in_mmu 0x400) | fc | (rw<<8); fc = data space (bit0=1) + supervisor
        // (bit2) if a supervisor access; rw = read?1:write?0 (MAME m68ki_aerr).
        // The faulting access's privilege = the PRE-exception S bit (SR bit13 =
        // srSys bit5), read here BEFORE the FSM sets S. (The LS EU's translate-time
        // supervisor flag — reqDrvSup, PrivilegeService-driven since the I/D-side MMU
        // privilege fix — now tracks this same architectural S bit too, so the two
        // agree; the SSW still reads the SR directly here to stay exactly byte-for-byte
        // with the MAME oracle, which reads the SR S bit, not a future MOVES/SFC mode.)
        // entryFaultSup is retained for a future MOVES/SFC-driven mode.
        val faultSuper = ss.srSys(5) || entryFaultSup
        // FC space (bit2=supervisor): DATA access => bit0 set (01/101); INSTRUCTION
        // fetch => program space, bit1 set (10/110). Mirrors MAME's m68040 SSW TM/FC
        // (data=...001/101, program=...010/110). An instruction fetch is always a
        // READ (rw bit = 1).
        val spaceBits = Mux(entryFaultInstr, U(0x2, 3 bits), U(0x1, 3 bits))
        val fc  = Mux(faultSuper, U(0x4, 3 bits), U(0x0, 3 bits)) | spaceBits
        val rwB = Mux(entryFaultInstr, U(1, 1 bits), Mux(entryFaultWr, U(0, 1 bits), U(1, 1 bits)))
        // Task #189: two SSW bugs fixed here, both previously characterized as
        // "narrower, currently-moot" (moot because the ONLY fault source before
        // this task was the MMU/ATC path, for which ATC=1 always happened to be
        // right, and nothing ever checked SIZE):
        //   (1) ATC (bit 10) was hardcoded 1 unconditionally — now genuinely
        //       entryFaultAtc-driven: 1 for an MMU/ATC-detected translation fault,
        //       0 for a plain physical bus error (SLVERR/DECERR with zero MMU
        //       involvement) — see LsFault.atc / LsEuPlugin's captureFault(atc=).
        //   (2) SIZE (bits 6:5) was never populated at all (always read 0b00,
        //       coincidentally "long") — now built from entryFaultSize, which
        //       carries LsFault.sizeBits' encoding (00=byte,01=word,10=long) and
        //       needs translating to the SSW's OWN size encoding
        //       (00=long,01=byte,10=word,11=reserved).
        val atcBit  = Mux(entryFaultAtc, U(0x400, 16 bits), U(0, 16 bits))
        val sswSize = entryFaultSize.mux(
          U(0, 2 bits) -> U(1, 2 bits),   // our BYTE -> SSW 01
          U(1, 2 bits) -> U(2, 2 bits),   // our WORD -> SSW 10
          U(2, 2 bits) -> U(0, 2 bits),   // our LONG -> SSW 00
          default      -> U(0, 2 bits))
        val sizeField = (sswSize << 5).resize(16)
        curSsw    := (atcBit | sizeField | fc.resize(16) |
                      (rwB ## U(0, 8 bits)).asUInt.resize(16))
        oldSr     := (ss.srSys ## committedCcr.resize(8 bits)).asUInt
        // new SP = supervisor bank (M?MSP:ISP) - frame size
        //   format-$0 = 8 bytes, format-$2 = 12 bytes, format-$7 = 60 bytes.
        // (PRELIMINARY value; recomputed from the settled live bank on E_DRAIN->E_STORE.)
        // new SP = current supervisor stack (M ? MSP : ISP) - frame size. M is PRESERVED
        // across fault/trap entry (the &0x3f mask in E_REDIR keeps bit4), so the
        // post-stack SP is written back to this SAME bank.
        val supSp = Mux(ss.m, ss.msp, ss.isp)
        val nb = Mux(is7, supSp - 60, Mux(is2, supSp - 12, supSp - 8))
        frameBase := nb
        // Task #132: an INTERRUPT taken while M=1 needs the format-$1 throwaway
        // frame. ss.m itself has no readback lag (it's a plain srSys bit, unlike
        // ss.msp/ss.isp which mirror the committed-A7 PRF readback with settle
        // latency) — safe to read live here, same as is7/is2 above.
        curThrowaway := entryIsInterrupt && ss.m
        stFrame2     := False
        // compute vector fetch base = VBR + vec*4
        vecTarget := (ss.vbr + (entryVector << 2)).resized
        stStep    := 0
        stSplitLow := False   // task #163: clear any split-word carry from a prior entry
        goto(E_DRAIN)
      } elsewhen(rteTrigger) {
        // RTE reads the frame at the CURRENT A7 (SSP). Do NOT capture frameBase yet —
        // ss.msp/ss.isp mirror the committed-A7 PRF readback with the SAME ~1-cycle
        // settle latency the ENTRY path's E_DRAIN state exists to wait out (see its
        // comment). An RTE whose immediately-preceding instruction is an ordinary
        // renamed A7-modifying store (e.g. a hand-built frame pushed via `move -(%a7)`,
        // as opposed to the exception FSM's OWN internal E_STORE writes, which apply
        // synchronously and were therefore always already-settled by the time a LATER
        // RTE observed them) would otherwise capture a STALE pre-push frameBase here,
        // reading garbage SR/PC/format from the wrong stack address — a wild-PC hang.
        // Route through R_DRAIN first (mirrors E_DRAIN) to wait for the SQ to drain
        // and recompute frameBase from the SETTLED bank.
        // Task #177: latch RTE's own PC NOW (this cycle, before excActive/excSquash
        // starts reusing the ROB slot h0 still points at — see rteCapPc's comment).
        rteCapPc := rtePc
        goto(R_DRAIN)
      } elsewhen(sysTrigger) {
        // Commit-time SYSTEM op (supervisor; the user-mode case is a vector-8 fault via
        // entryTrigger). Latch the captured context; apply next cycle.
        sysCapKind    := sysKind
        sysCapReadDir := sysReadDir
        sysCapVal     := sysVal
        sysCapRc      := sysRc
        sysCapDstPhys := sysDstPhys
        sysCapPc      := sysPc
        sysCapNextPc  := sysNextPc
        goto(S_DRAIN)
      }
    }

    // Task P5.4: quiesce before applying a commit-time sysOp. See `dcQuiesced`'s
    // doc comment for why this is REQUIRED (CPUSH/CINV) and safe for the sysOps that
    // do not need it (a single extra cycle in the common case -- both conditions are
    // already true the overwhelming majority of the time).
    //
    // DEADLOCK ANALYSIS (why this cannot hang):
    //   - `sqDrained` == StoreQueue empty. Entering this state raises `excActive`,
    //     whose RISING EDGE pulses `sqFlush` (FullCoreSynth's `excEnteringSq`) --
    //     squashing SPECULATIVE younger stores (which would otherwise never retire and
    //     never drain) while KEEPING committed ones, which continue draining under
    //     their own machinery. That is byte-for-byte the same precondition E_DRAIN
    //     relies on, and it is exercised on every exception in the corpus.
    //   - The SQ's drain does NOT depend on this sysOp completing: the CPUSH/CINV is
    //     at the ROB head and has not retired, but the SQ drains committed entries
    //     asynchronously, gated only on the D-cache store port -- not on retirement of
    //     anything younger. There is no cycle.
    //   - `dcQuiesced` is a conjunction of "no transaction in flight" terms, every one
    //     of which is cleared by a bounded, self-driving completion (the load FSM
    //     always returns to IDLE, a pending store-miss is picked up at the next IDLE,
    //     a pending write-through kickoff fires as soon as the eviction pair closes).
    //     With the LS EU flushed, nothing re-arms them.
    S_DRAIN.whenIsActive {
      when(sqDrained && dcQuiesced) {
        goto(S_APPLY)
      }
    }

    // Wait for older committed stores to fully drain before we use the store port.
    E_DRAIN.whenIsActive {
      when(sqDrained) {
        // RECOMPUTE frameBase from the SETTLED live supervisor bank (M ? MSP : ISP).
        // The IDLE entry-capture computed frameBase from ss.supBank on the IDLE->entry
        // edge, but the live committed-A7 readback has a 1-cycle latency, so that early
        // value can be stale. By E_DRAIN->E_STORE the readback has settled, so recompute
        // from the current bank: new SP = supervisor bank (M?MSP:ISP) - frame size
        // (format-$0 = 8, format-$2 = 12, format-$7 = 60 bytes).
        val supSp = Mux(ss.m, ss.msp, ss.isp)
        frameBase := Mux(curIs7, supSp - 60, Mux(curIs2, supSp - 12, supSp - 8))
        // Task #132: the throwaway ($1) frame's base — settled ISP minus 8 bytes.
        // ss.m is True here (curThrowaway only set when it was), so ss.isp is
        // exactly the bank that will become active once M clears — same settle
        // treatment as frameBase above (read from the settled bank at E_DRAIN, not
        // the possibly-stale IDLE-time snapshot).
        frameBase2 := ss.isp - 8
        goto(E_STORE)
      }
    }

    // ── ENTRY: stack the frame (one word at a time) ─────────────────────────────
    E_STORE.whenIsActive {
      // Capture the store EXACTLY ONCE, hold its registered Stream command until
      // accepted, then wait its terminal ACK.
      // The exception sequencer is a supervisor PHYSICAL access: the store paddr is
      // identity, and the D-cache store port uses that paddr directly — it needs NO
      // translation. We must NOT gate on `dtRsp.ready`
      // (with the MMU live, the frame VPN walks and dtRsp.ready drops for several
      // cycles, during which a combinational `driveStore` would recapture the same
      // word repeatedly). One cycle here captures exactly one stable Stream item.
      // Task #163: split a line-crossing word (line-relative offset 15) into two
      // single-byte pushes — see stSplitLow's doc comment above.
      val addr    = frameWordAddr(stStep)
      val data    = frameWordData(stStep)
      val crosses = addr(3 downto 0) === U(15, 4 bits)
      when(stSplitLow) {
        driveStoreNoXlate(addr + U(1, 32 bits), Size.BYTE, data(7 downto 0))
      } elsewhen(crosses) {
        driveStoreNoXlate(addr, Size.BYTE, data(15 downto 8))
      } otherwise {
        driveStoreNoXlate(addr, Size.WORD, data)
      }
      goto(E_STWAIT)
    }
    E_STWAIT.whenIsActive {
      // The registered command remains asserted here until dcStore.fire. Wait for
      // the accepted write to reach storeAck before advancing to the next frame word
      // or vector fetch. Stream ready now represents cache occupancy directly.
      when(dcStoreAck) {
        val addr    = frameWordAddr(stStep)
        val crosses = addr(3 downto 0) === U(15, 4 bits)
        when(crosses && !stSplitLow) {
          // Just issued the HIGH byte of a split word; issue the LOW byte next
          // (same stStep, same frame word — do not advance).
          stSplitLow := True
          goto(E_STORE)
        } otherwise {
          stSplitLow := False
          when(stStep === lastStep) {
            // Task #132: after finishing frame $0 (stFrame2 still False), a throwaway
            // entry loops back into E_STORE for the SECOND ($1) frame instead of
            // proceeding to the vector fetch. frameWordAddr/fmtVecWord above already
            // switch to frameBase2/format-1 once stFrame2 is True.
            when(curThrowaway && !stFrame2) {
              stFrame2 := True; stStep := 0; goto(E_STORE)
            } otherwise {
              goto(E_VECREQ)
            }
          } otherwise { stStep := stStep + 1; goto(E_STORE) }
        }
      }
    }
    // ── ENTRY: fetch the handler vector ─────────────────────────────────────────
    E_VECREQ.whenIsActive {
      dtoVld := True; dtoVpn := vecTarget(31 downto 12); dtoWr := False
      ldoVld := True; ldoVaddr := vecTarget; ldoSize := Size.LONG
      when(dcLoadCmd.fire) { goto(E_VECWAIT) }
    }
    E_VECWAIT.whenIsActive {
      // keep the translation valid while the load is in flight
      dtoVld := True; dtoVpn := vecTarget(31 downto 12)
      when(dcLoadRsp.valid) {
        vecTarget := dcLoadRsp.payload.data.asUInt
        goto(E_REDIR)
      }
    }
    E_REDIR.whenIsActive {
      // commit the architectural side-effects + redirect
      // NB: ss.writeA7 (live readback) also fires every cycle, but setIsp/setMsp here
      // WIN by SystemState's later-when ordering — this serializing SP write is authoritative.
      // Task #132: a throwaway entry stacked to BOTH banks (frame $0 -> MSP, frame $1
      // -> ISP) — write BOTH, not just the (single, old-ss.m-selected) bank the
      // non-throwaway path uses.
      when(curThrowaway) {
        ss.setMsp.valid := True; ss.setMsp.payload := frameBase
        ss.setIsp.valid := True; ss.setIsp.payload := frameBase2
      } .elsewhen(ss.m) { ss.setMsp.valid := True; ss.setMsp.payload := frameBase }
      .otherwise { ss.setIsp.valid := True; ss.setIsp.payload := frameBase }
      // enter supervisor, clear trace: set S (bit5), clear T1/T0 (bits 7,6).
      // For an INTERRUPT entry ALSO raise the SR I-mask (bits 2:0) to the interrupt
      // level so equal/lower interrupts are held until RTE (NMI sets 7); fault/trap
      // entries leave the mask unchanged. (newSysBase clears S/T only; the mask bits
      // 2:0 are preserved for faults, overwritten with curLevel for interrupts.)
      // Task #132: a throwaway entry ALSO clears M (bit4) here — the handler runs
      // on ISP with M=0; M=1 only comes back when RTE re-pops the $1 frame's SR.
      val keepMask = Mux(curThrowaway, U(0x2f, 8 bits), U(0x3f, 8 bits))
      val newSysBase = (ss.srSys | U(0x20, 8 bits)) & keepMask
      val newSys = Mux(curIsInt,
                       (newSysBase & U(0xf8, 8 bits)) | curLevel.resize(8),
                       newSysBase)
      ss.setSrSys.valid := True; ss.setSrSys.payload := newSys
      redirectValid := True
      redirectPc    := vecTarget
      // commit observation: the faulting instruction's trace step == handler entry
      // with the post-exception SR system byte (S set, T cleared) + A7 = new SSP.
      // (CCR is unchanged by the exception -> the whitebox carries it.) For a
      // throwaway entry the HANDLER runs on ISP (M now 0), so obsA7 = frameBase2
      // (the new ISP), not frameBase (the now-inactive MSP result).
      obsFire    := True
      obsIsEntry := True
      obsPc      := vecTarget
      obsSysByte := newSys
      obsA7      := Mux(curThrowaway, frameBase2, frameBase)
      obsIsInterrupt := curIsInt
      goto(IDLE)
    }

    // ── RTE: pop the frame, restore SR + PC, SSP += 8, redirect ─────────────────
    // Wait for older committed stores to fully drain (mirrors E_DRAIN) before reading
    // the live supervisor-bank A7 for frameBase -- see the rteTrigger comment above.
    R_DRAIN.whenIsActive {
      when(sqDrained) {
        frameBase := Mux(ss.m, ss.msp, ss.isp)   // RTE reads the SETTLED current supervisor stack
        goto(R_SRREQ)
      }
    }
    R_SRREQ.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 0)(31 downto 12)
      ldoVld := True; ldoVaddr := frameBase + 0; ldoSize := Size.WORD
      when(dcLoadCmd.fire) { goto(R_SRWAIT) }
    }
    R_SRWAIT.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 0)(31 downto 12)
      when(dcLoadRsp.valid) {
        popSr := dcLoadRsp.payload.data(15 downto 0).asUInt
        goto(R_PCREQ)
      }
    }
    R_PCREQ.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 2)(31 downto 12)
      ldoVld := True; ldoVaddr := frameBase + 2; ldoSize := Size.WORD
      when(dcLoadCmd.fire) { goto(R_PCWAIT) }
    }
    R_PCWAIT.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 2)(31 downto 12)
      when(dcLoadRsp.valid) {
        popPc(31 downto 16) := dcLoadRsp.payload.data(15 downto 0).asUInt
        goto(R_PCREQ2)
      }
    }
    R_PCREQ2.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 4)(31 downto 12)
      ldoVld := True; ldoVaddr := frameBase + 4; ldoSize := Size.WORD
      when(dcLoadCmd.fire) { goto(R_PCWAIT2) }
    }
    R_PCWAIT2.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 4)(31 downto 12)
      when(dcLoadRsp.valid) {
        popPc(15 downto 0) := dcLoadRsp.payload.data(15 downto 0).asUInt
        goto(R_FMTREQ)
      }
    }
    // Read the format word @base+6 to select the pop size ($0 = 8 bytes, $7 = 60).
    R_FMTREQ.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 6)(31 downto 12)
      ldoVld := True; ldoVaddr := frameBase + 6; ldoSize := Size.WORD
      when(dcLoadCmd.fire) { goto(R_FMTWAIT) }
    }
    R_FMTWAIT.whenIsActive {
      dtoVld := True; dtoVpn := (frameBase + 6)(31 downto 12)
      when(dcLoadRsp.valid) {
        // top nibble selects the pop size: 7 => format-$7 (60 bytes), 2 => format-$2
        // (12 bytes), 1 => format-$1 throwaway (task #132, see below), else format-$0
        // (8 bytes).
        val nib = dcLoadRsp.payload.data(15 downto 12).asUInt
        // This core only ever STACKS formats $0/$1/$2/$7 (see frameWordData/fmtVecWord
        // above) — any OTHER format nibble in a popped frame is malformed (hand-built,
        // corrupted, or a format this core never produces) and real 68020+ silicon
        // raises the format-error exception (vector 14) instead of blindly restoring
        // SR/PC from it (task #170-cluster10; previously there was NO vector-14
        // dispatch anywhere — a bad format nibble silently fell through to the
        // format-$0 8-byte-pop default and mis-executed). Musashi/PRM semantics: the
        // malformed frame is left UNTOUCHED on the stack (SR/PC are never applied from
        // it), and a NEW format-$0 frame is pushed BELOW it for vector 14, with the
        // saved PC = the address of the RTE instruction itself (so a vec-14 handler
        // that patches the frame and RTEs again re-attempts the SAME original RTE).
        popFmtWord := dcLoadRsp.payload.data(15 downto 0).asUInt
        popIs7 := nib === U(7, 4 bits)
        popIs2 := nib === U(2, 4 bits)
        popIs1 := nib === U(1, 4 bits)
        val fmtOk = (nib === U(0, 4 bits)) || (nib === U(1, 4 bits)) ||
                    (nib === U(2, 4 bits)) || (nib === U(7, 4 bits))
        // Task #189: an otherwise well-formed frame whose PC field is ODD is a
        // SEPARATE malformation from a bad format nibble — the 68040 PC must always
        // be even (M68040UM §8.2.3). Checked only when the format itself is valid
        // (a bad-format frame already routes to vector 14 below regardless of PC
        // parity — no test exercises the combination, and format-error is the more
        // fundamental defect) and NOT for the format-$1 throwaway pop (popIs1's PC
        // field is discarded — m68ki_fake_pull_32 — so its parity is architecturally
        // irrelevant). `popPc` was already captured in R_PCWAIT above (frameBase+2,
        // same offset for every format).
        val pcOdd = fmtOk && !popIs1 && popPc(0)
        when(fmtOk && !pcOdd) {
          goto(R_REDIR)
        } elsewhen(pcOdd) {
          // Synthesize a vector-3 (address error) format-$2 ENTRY — CONSERVATIVE
          // model, mirrors the vector-14 format-error path just below (the malformed
          // frame is left IN PLACE; SR/A7 are NOT applied from it), except the
          // vector/format differ: format-$2 (12 bytes, via curIs2). UNLIKE vector 14
          // (whose PC field is rteCapPc, the RTE instruction's own address, for a
          // direct retry), BOTH the PC field (SP+2, curPc) and the extra ADDRESS
          // field (SP+8, curPpc) here carry the ODD RESUME PC itself (popPc) — the
          // documented test contract (exc_addr_error_odd_rte.s header): "carries the
          // odd resume PC in BOTH the PC field and the instruction-address field".
          // A handler wanting to retry the RTE must explicitly patch SP+2 to the
          // RTE's own address itself before RTE-ing out of THIS frame (the test does
          // exactly that) — this frame's PC field does not default to it.
          curVec       := U(3, 8 bits)
          curPc        := popPc
          curIs7       := False
          curIs2       := True
          curIsInt     := False
          curLevel     := U(0, 3 bits)
          curPpc       := popPc
          curFault     := U(0, 32 bits)
          curThrowaway := False
          stFrame2     := False
          oldSr        := (ss.srSys ## committedCcr.resize(8 bits)).asUInt
          vecTarget    := (ss.vbr + (U(3, 8 bits) << 2)).resized
          stStep       := 0
          stSplitLow   := False
          goto(E_DRAIN)
        } otherwise {
          // Synthesize a vector-14 format-$0 ENTRY, reusing the normal E_* frame-push
          // path unchanged (mirrors the IDLE->entryTrigger setup above, specialized to
          // a non-interrupt/non-$7/non-$2/non-throwaway format-$0 case).
          curVec       := U(14, 8 bits)
          // Task #177: use the LATCHED rteCapPc (captured at rteTrigger), not the
          // live `rtePc` wire — by this state (many cycles past rteTrigger) the ROB
          // slot h0 pointed at has been continuously reused by excSquash's per-cycle
          // `tail := head`, so a late live read of `rtePc` no longer reflects RTE's
          // own PC.
          curPc        := rteCapPc
          curIs7       := False
          curIs2       := False
          curIsInt     := False
          curLevel     := U(0, 3 bits)
          curPpc       := rteCapPc
          curFault     := U(0, 32 bits)
          curThrowaway := False
          stFrame2     := False
          oldSr        := (ss.srSys ## committedCcr.resize(8 bits)).asUInt
          vecTarget    := (ss.vbr + (U(14, 8 bits) << 2)).resized
          stStep       := 0
          stSplitLow   := False
          goto(E_DRAIN)
        }
      }
    }
    R_REDIR.whenIsActive {
      // restore the SR (system byte) — same mechanism for BOTH the throwaway ($1)
      // pop and the real pop; only WHICH bytes were popped / what happens next differs.
      ss.setSrSys.valid := True; ss.setSrSys.payload := popSr(15 downto 8)
      when(popIs1) {
        // Task #132: this was the format-$1 THROWAWAY frame — its PC is discarded
        // (Musashi: m68ki_fake_pull_32 for the PC). Its SR still carries M=1 (it was
        // captured before M was cleared at entry), so applying it re-banks A7 back
        // to MSP. Reclaim the frame's 8 bytes on the CURRENT (ISP) bank — we are
        // guaranteed supervisor here (RTE only runs at S=1) and M was 0 while the
        // handler ran, so ISP was the active bank; frameBase is exactly where THIS
        // frame was popped from. Do NOT redirect / do NOT fire an obs — this is not
        // a real completion yet (Musashi's `goto rte_loop`, still inside ONE rte
        // instruction). Re-read ss.msp: it was untouched since entry (only ISP/M
        // moved while M=0), so it is exactly frame $0's base — loop back to pop it.
        ss.setIsp.valid := True; ss.setIsp.payload := frameBase + 8
        frameBase := ss.msp
        goto(R_SRREQ)
      } .otherwise {
        // SSP += frame size (8 for $0/$1, 12 for $2, 60 for $7). The CURRENT A7 is
        // SSP (we were supervisor); after restoring SR the bank may switch to USP, so
        // write SSP explicitly. For $7 the popPc is the faulting instruction's PC ->
        // RTE resumes by RE-EXECUTING it (the handler has fixed the mapping), matching
        // MAME. For a throwaway's SECOND (real, format-$0) pop, popIs1 is False here
        // (this frame's own format nibble is 0) — the bank write below correctly
        // targets ss.m, which by this point already reads True (the throwaway pass's
        // setSrSys landed a cycle ago, restoring M=1) — same code path as any other
        // format-$0 RTE, no throwaway-specific branch needed.
        val newSsp = frameBase + Mux(popIs7, U(60, 32 bits), Mux(popIs2, U(12, 32 bits), U(8, 32 bits)))
        when(ss.m) { ss.setMsp.valid := True; ss.setMsp.payload := newSsp }
        .otherwise { ss.setIsp.valid := True; ss.setIsp.payload := newSsp }
        redirectValid := True
        redirectPc    := popPc
        // task #176 (redesigned, task-176-regression): restore the popped CCR
        // {X,N,Z,V,C} into the REAL flags PRF (not just committedCcr) so a later
        // Bcc/flag-reader actually observes it -- a DIRECT in-place write into
        // whatever physical register is CURRENTLY nzvcRat/xRat's committed mapping
        // (see the class-level doc comment on rteNzvcWriteValid above), driven by the
        // wiring plugins exactly like a7WriteValid/a7WriteData. SR bit layout: bit4=X,
        // bits3..0=N,Z,V,C (matches our internal 4-bit nzvc field).
        rteNzvcWriteValid := True
        rteNzvcWriteData  := popSr(3 downto 0).asBits
        rteXWriteValid    := True
        rteXWriteData     := popSr(4)
        // commit observation: RTE's trace step == restored PC + restored SR sysByte
        // + A7. A7 after RTE = popped-SSP if S restored supervisor, else USP. The CCR
        // is restored from the frame too, but the whitebox carries it (RTE restores
        // the same CCR the matching exception entry saved -> reconstructed CCR holds).
        obsFire    := True
        obsPc      := popPc
        obsSysByte := popSr(15 downto 8)
        // A7 after RTE = restored-(S,M) bank. popSr(13)=S, popSr(12)=M. For a plain
        // (non-throwaway) format-$0/$2/$7 RTE, M is unchanged so the popped bank ==
        // the restored supervisor bank and obsA7 resolves to Mux(S, newSsp, usp) —
        // identical to the old (pre-#132) behavior. For a throwaway's SECOND pop,
        // popSr(12) is again M=1 (frame $0's own captured SR) and ss.m already reads
        // True (set by the first pass), so poppedSameBank is True and obsA7 = newSsp
        // (the fully-unwound MSP) — correct, matches the "final A7==MSP_TOP" spec.
        val poppedSameBank = (popSr(12) === ss.m)
        val rsupBank = Mux(popSr(12), ss.msp, ss.isp)
        obsA7 := Mux(popSr(13), Mux(poppedSameBank, newSsp, rsupBank), ss.usp)
        goto(IDLE)
      }
    }

    // ── Commit-time SYSTEM op: APPLY the effect to committed state (1 cycle) ──────
    // Every arm below dispatches on `skOrd(SysKind.X)` — the ordinal derived FROM the
    // enum (see skOrd's comment above), never a hand-written number — so inserting or
    // reordering a SysKind element can no longer silently re-point these arms.
    // The write direction's source value is sysCapVal; the read direction writes the
    // int PRF arch-reg (sysRegWrite*). SysKind.NONE (and anything unmatched) does
    // nothing: the switch has no `default`, and every effect below is a pulse onto a
    // signal that is unconditionally defaulted idle earlier in this Area, so "no arm
    // matched" is a genuine, side-effect-free no-op (S_APPLY still falls through to
    // S_REDIR, which serializes + advances PC — the RESET behavior). Task P5.5: CPUSH/
    // CINV are no longer part of that no-op set; they issue a real maintenance command
    // and detour through S_MAINTWAIT before S_REDIR (see the exit below).
    S_APPLY.whenIsActive {
      switch(sysCapKind) {
        is(skOrd(m68k040.decode.SysKind.MOVE_TO_SR)) { // MOVE to SR : sysVal.W -> SR
          // System byte = sysVal[15:8], CCR = sysVal[4:0]. Writing srSys may flip S ->
          // A7 re-banks. The committed CCR is tracked in the ROB; we surface the new SR
          // (sysByte) in the obs, and the ROB folds sysVal[4:0] into committedCcr (so the
          // whitebox's running CCR resyncs). Re-bank A7: write the int PRF arch-15 with
          // the NEW-S bank's value (computed from the post-write S in S_REDIR via ss.a7).
          ss.setSrSys.valid := True; ss.setSrSys.payload := sysCapVal(15 downto 8).asUInt
        }
        is(skOrd(m68k040.decode.SysKind.MOVE_USP)) { // MOVE USP : An<->USP
          when(sysCapReadDir) {                     // USP -> An : write the int PRF[pdst]
            sysRegWriteValid := True
            sysRegWritePhys  := sysCapDstPhys
            sysRegWriteData  := ss.usp
          } otherwise {                             // An -> USP : write the USP bank
            ss.setUsp.valid := True; ss.setUsp.payload := sysCapVal.asUInt
          }
        }
        is(skOrd(m68k040.decode.SysKind.MOVEC)) {   // MOVEC : Rc<->Rn
          when(sysCapReadDir) {                     // Rc -> Rn : read the committed reg
            sysRegWriteValid := True
            sysRegWritePhys  := sysCapDstPhys
            // Rc id: VBR=0x801, USP=0x800, SFC=0x000, DFC=0x001 (3-bit, zero-extended),
            // CACR=0x002 (RAZ), TCR=0x003 (E bit only — bit 15; P/page-size + other
            // bits RAZ, this core is 4K-pages-only), URP=0x806, SRP=0x807,
            // MSP=0x803, ISP=0x804 (task #170-cluster10: MSP/ISP banking itself already
            // works via the S/M-bit A7 Mux -- ss.msp/ss.isp ARE the real committed
            // registers backing it -- but they were not yet separately MOVEC-addressable;
            // exposing them here is a direct passthrough to those same registers, no new
            // storage). SFC/DFC are real 3-bit committed regs (Musashi reads them
            // zero-extended; round-trips with the write below). ITT0/ITT1/DTT0/DTT1
            // (task #194) are now real, functional transparent-translation registers
            // owned by MmuControlPlugin — see its doc comment for the TT-window match
            // logic (TtMatch) consumed by ItlbPlugin/DtlbPlugin.
            sysRegWriteData  := sysCapRc.mux(
              U(0x801, 12 bits) -> ss.vbr,
              U(0x800, 12 bits) -> ss.usp,
              U(0x803, 12 bits) -> ss.msp,
              U(0x804, 12 bits) -> ss.isp,
              U(0x000, 12 bits) -> ss.sfc.resize(32),
              U(0x001, 12 bits) -> ss.dfc.resize(32),
              U(0x003, 12 bits) -> Mux(mmuCtrl.mmuEnable, U(0x8000, 32 bits), U(0, 32 bits)),
              U(0x002, 12 bits) -> ss.cacr,
              U(0x004, 12 bits) -> mmuCtrl.itt0,
              U(0x005, 12 bits) -> mmuCtrl.itt1,
              U(0x006, 12 bits) -> mmuCtrl.dtt0,
              U(0x007, 12 bits) -> mmuCtrl.dtt1,
              U(0x806, 12 bits) -> mmuCtrl.urp,
              U(0x807, 12 bits) -> mmuCtrl.srp,
              // MMUSR (0x805), task #198: PTEST's result register, read-only from the
              // arch side (real hardware has no MOVEC-write case for it either — falls
              // to the `other Rc: WI` default below).
              U(0x805, 12 bits) -> mmuCtrl.mmusr,
              default           -> U(0, 32 bits))   // other unmodeled Rc -> RAZ (read 0)
          } otherwise {                             // Rn -> Rc : write the committed reg
            switch(sysCapRc) {
              is(U(0x801, 12 bits)) { ss.setVbr.valid := True; ss.setVbr.payload := sysCapVal.asUInt }
              is(U(0x800, 12 bits)) { ss.setUsp.valid := True; ss.setUsp.payload := sysCapVal.asUInt }
              // SFC/DFC: write the low 3 bits (Musashi masks `& 7`); upper bits ignored.
              is(U(0x000, 12 bits)) { ss.setSfc.valid := True; ss.setSfc.payload := sysCapVal(2 downto 0).asUInt }
              is(U(0x001, 12 bits)) { ss.setDfc.valid := True; ss.setDfc.payload := sysCapVal(2 downto 0).asUInt }
              // CACR (0x002): real committed storage (task #170-cluster10), round-
              // trippable via MOVEC but with NO functional effect on the D-cache (it
              // always caches regardless — matches this core's pre-existing behavior,
              // only the storage/round-trip half was missing).
              is(U(0x002, 12 bits)) { ss.setCacr.valid := True; ss.setCacr.payload := sysCapVal.asUInt }
              // TCR (0x003), task #194 (revives task #131's reverted attempt — see
              // MmuControlPlugin's doc comment for why this is now believed safe):
              // only the E (enable) bit, TCR bit 15, is modeled (this core is
              // 4K-pages-only — P/page-size + other bits are don't-cares, WI).
              is(U(0x003, 12 bits)) { mmuCtrl.setEnable.valid := True; mmuCtrl.setEnable.payload := sysCapVal(15) }
              // ITT0/ITT1 (0x004/0x005) and DTT0/DTT1 (0x006/0x007), task #194: real,
              // FUNCTIONAL transparent-translation registers (TtMatch, consumed by
              // ItlbPlugin/DtlbPlugin to bypass the walker for a covered region) —
              // previously ITT0 was inert storage (task #180) and ITT1/DTT0/DTT1 were
              // entirely unmodeled.
              is(U(0x004, 12 bits)) { mmuCtrl.setItt0.valid := True; mmuCtrl.setItt0.payload := sysCapVal.asUInt }
              is(U(0x005, 12 bits)) { mmuCtrl.setItt1.valid := True; mmuCtrl.setItt1.payload := sysCapVal.asUInt }
              is(U(0x006, 12 bits)) { mmuCtrl.setDtt0.valid := True; mmuCtrl.setDtt0.payload := sysCapVal.asUInt }
              is(U(0x007, 12 bits)) { mmuCtrl.setDtt1.valid := True; mmuCtrl.setDtt1.payload := sysCapVal.asUInt }
              // MSP (0x803) / ISP (0x804), task #170-cluster10: direct writes to the
              // SAME committed registers the S/M-bit A7 Mux already reads (ss.msp/
              // ss.isp) — no new storage, this is purely exposing the existing bank
              // registers as MOVEC-addressable.
              is(U(0x803, 12 bits)) { ss.setMsp.valid := True; ss.setMsp.payload := sysCapVal.asUInt }
              is(U(0x804, 12 bits)) { ss.setIsp.valid := True; ss.setIsp.payload := sysCapVal.asUInt }
              // URP (0x806) / SRP (0x807), task #194 (revives task #131's reverted
              // attempt): real MOVEC-driven root-pointer write.
              is(U(0x806, 12 bits)) { mmuCtrl.setUrp.valid := True; mmuCtrl.setUrp.payload := sysCapVal.asUInt }
              is(U(0x807, 12 bits)) { mmuCtrl.setSrp.valid := True; mmuCtrl.setSrp.payload := sysCapVal.asUInt }
              // other Rc: WI (write-ignored, RAZ-WI default).
            }
          }
        }
        is(skOrd(m68k040.decode.SysKind.RESET)) {   // RESET : no architectural state change
          // The external reset line is not modeled for lock-step; RESET is an internal NOP.
          // S_REDIR just advances PC (the obs carries the UNCHANGED sysByte + A7).
        }
        is(skOrd(m68k040.decode.SysKind.STOP)) {    // STOP : SR := sysVal[15:0]
          // Identical SR write to MOVE-to-SR: system byte = sysVal[15:8] (S/T/I incl. the
          // new I-mask), CCR = sysVal[4:0]. A7 re-banks on an S flip (S_REDIR via ss.a7).
          // The HALT itself is the ROB `stopped` state (set on the STOP sysRetire).
          ss.setSrSys.valid := True; ss.setSrSys.payload := sysCapVal(15 downto 8).asUInt
        }
        // ── CPUSH / CINV (Task P5.5): REAL cache-maintenance dispatch ──────────────
        // Both arms exist EXPLICITLY rather than being left to fall through, and that
        // is load-bearing history, not style: before Task P5.2 these were hand-written
        // raw ordinal literals, and inserting SysKind.CINV between CPUSH and PFLUSHA
        // silently re-pointed three arms — CINV's freshly-inserted ordinal 7 landed on
        // what used to be PFLUSHA's literal arm and spuriously pulsed a full TLB flush.
        // Every arm in this switch now derives its literal from the enum via `skOrd`.
        //
        // `sysCapRc` packs the CPUSH/CINV opword fields (MicroOpAssembler:
        // `imm := op(4 downto 3) ## op(7 downto 6)`): bits [3:2] = SCOPE
        // (01=Line, 10=Page, 11=All), bits [1:0] = CACHE SELECTOR (01=DC, 10=IC,
        // 11=BC). `sysCapVal` carries An's value = the target address (meaningful for
        // Line/Page scope only). The D-cache side ignores an IC-only (10) selector and
        // completes immediately (DcachePlugin's `touchesDc`), so the command is always
        // sent — the selector, not the caller, decides whether there is D-side work.
        //
        // The I-side half of the command (`icMaintPulse`, asserted when the selector
        // names IC=10 or BC=11) is DELIBERATELY *not* pulsed in these arms. It fires
        // later — in `S_MAINTWAIT`'s completion transition, once the D-side walk has
        // actually finished. See that state's comment for why the ordering is
        // load-bearing.
        is(skOrd(m68k040.decode.SysKind.CPUSH)) {   // CPUSH : push (writeback) dirty lines
          val cacheSel = sysCapRc(1 downto 0)
          val scope    = sysCapRc(3 downto 2)
          maintCmdOut.valid              := True
          maintCmdOut.payload.push       := True
          maintCmdOut.payload.invalidate := False
          maintCmdOut.payload.scope      := scope
          maintCmdOut.payload.sel        := cacheSel
          maintCmdOut.payload.addr       := sysCapVal.asUInt
          // (The I-cache clear for an IC/BC selector happens in S_MAINTWAIT, below.)
        }
        is(skOrd(m68k040.decode.SysKind.CINV)) {    // CINV : invalidate (no writeback)
          val cacheSel = sysCapRc(1 downto 0)
          val scope    = sysCapRc(3 downto 2)
          maintCmdOut.valid              := True
          maintCmdOut.payload.push       := False
          maintCmdOut.payload.invalidate := True
          maintCmdOut.payload.scope      := scope
          maintCmdOut.payload.sel        := cacheSel
          maintCmdOut.payload.addr       := sysCapVal.asUInt
          // (The I-cache clear for an IC/BC selector happens in S_MAINTWAIT, below.)
        }
        is(skOrd(m68k040.decode.SysKind.PFLUSHA)) { // PFLUSHA : flush all ATC/TLB entries
          // A REAL effect, unlike CPUSH/RESET — pulses the 1-cycle flushAll signal that
          // DtlbPlugin/ItlbPlugin clear their TLB + walk-result latch on.
          sysFlushAllValid := True
        }
        is(skOrd(m68k040.decode.SysKind.PTEST)) {   // PTEST : (An) -> MMUSR
          // This core's MMU has no real per-page R/W/CM/fault status to probe (same
          // "stub MMU" limitation PFLUSH/PFLUSHA already lean on). When the MMU is
          // disabled — the only configuration the ported corpus's ptest_w_an exercises
          // — PA=VA (identity) and the page is always "resident" (R=1), which is
          // architecturally EXACT for that configuration, not an approximation.
          // MMUSR := (An & page-mask) | R(=1); every other MMUSR status bit (B/G/U0/
          // U1/S/CM/M/W/T) reads 0 (no real translation-fault/write-protect/CM
          // probing modeled). sysCapVal carries An's value (write direction, exactly
          // like MOVE_USP's An->USP arm — see MicroOpAssembler's PTEST case).
          mmuCtrl.setMmusr.valid   := True
          mmuCtrl.setMmusr.payload := (sysCapVal.asUInt & U(0xFFFFF000L, 32 bits)) | U(1, 32 bits)
        }
        is(skOrd(m68k040.decode.SysKind.FMOVE_FPCTRL)) { // FMOVE(M) <ea> <-> FPCR/FPSR/FPIAR
          // sysCapRc[2:0] is the register-select mask {FPCR, FPSR, FPIAR} (ext[12:10],
          // routed through the imm side-channel -- the same route MOVEC's 12-bit Rc id
          // takes). sysCapRc[3] is the Task 9b BATCH marker:
          //
          //   batch = 0 : Task 9's single-register fast crack (`FMOVE.L Dn,FPcr` /
          //               `FMOVE.L FPcr,Dn`). Exactly one mask bit set; the one value
          //               rides `sysCapVal`, as it always has.
          //   batch = 1 : Task 9b's microcoded FMOVEM control-register LIST form, write
          //               direction only. ANY subset of the three registers may be set,
          //               and each one's value comes from the `sysAux` slot named by its
          //               POSITION in the list -- the values the program's own ordinary,
          //               fully-translated, fault-capable `MLoad` rows already fetched.
          //
          // The write arm below was ALREADY three independent, non-exclusive `when`s
          // rather than a one-hot `elsewhen` chain, so generalizing it to "any subset"
          // needed no restructuring at all -- only the per-register value source became
          // mask-dependent. The READ arm stays exactly as Task 9 left it: the store
          // direction of the list form uses no sysOp whatsoever (design spec Decision 3),
          // so a batch read can never reach here.
          //
          // NOT PRIVILEGED, unlike every other arm in this switch: real 68040
          // FMOVE-to/from-FPcr is a USER instruction. RobPlugin's sysTriggerSig/
          // sysPrivFault carry the matching exclusion, so this arm can be reached with
          // committed S == 0.
          // Per-register value source. `batch` selects the `sysAux` slot named by the
          // register's POSITION in the list: FPCR is always transferred first, FPSR
          // second, FPIAR last (M68000PRM p. 5-91, Divergence Register D9), so a selected
          // register's position is simply the count of selected registers ahead of it.
          val fpCtrlBatch = sysCapRc(3)
          val fpCtrlAux   = if (sysAux != null) sysAux else Vec.fill(3)(B(0, 32 bits))
          val fpCtrlPosFpsr  = sysCapRc(2).asUInt.resize(2)
          val fpCtrlPosFpiar = (sysCapRc(2).asUInt +^ sysCapRc(1).asUInt).resize(2)
          def fpCtrlValue(pos: UInt): Bits = Mux(fpCtrlBatch, fpCtrlAux(pos), sysCapVal)
          when(sysCapReadDir) {                   // FPcr -> Rn : write the int PRF[pdst]
            sysRegWriteValid := True
            sysRegWritePhys  := sysCapDstPhys
            sysRegWriteData  := Mux(sysCapRc(2), fpuCtrl.fpcr,
                                Mux(sysCapRc(1), fpsrArch, fpuCtrl.fpiar))
          } otherwise {                           // {Rn, mem} -> FPcr : write the committed reg
            when(sysCapRc(2)) {
              setFpcrPort.valid   := True
              setFpcrPort.payload := fpCtrlValue(U(0, 2 bits)).asUInt
            }
            when(sysCapRc(1)) {
              // The non-FPCC bytes land in FpuControlPlugin (which masks [27:24] off
              // itself); the FPCC nibble is reversed back to the internal layout and
              // written DIRECTLY into the FPCC PRF's committed physical register.
              val fpsrVal = fpCtrlValue(fpCtrlPosFpsr)
              setFpsrPort.valid   := True
              setFpsrPort.payload := fpsrVal.asUInt
              fpccWriteValid      := True
              fpccWriteData       := fpccFromArch(fpsrVal(27 downto 24))
            }
            when(sysCapRc(0)) {
              setFpiarPort.valid   := True
              setFpiarPort.payload := fpCtrlValue(fpCtrlPosFpiar).asUInt
            }
          }
        }
        // ── FSAVE <ea> (Task 11) ────────────────────────────────────────────────
        is(skOrd(m68k040.decode.SysKind.FSAVE)) {
          // Frame-type selection from LIVE FPU state. This needs NO memory read at all,
          // which is exactly why FSAVE (unlike FRESTORE) sits in the middle band the
          // design addendum describes: runtime-selected, but not data-dependent.
          //   unimplemented-instruction : a recognized-but-unsupported FP op is pending
          //                               software completion (Task 10's vector-11 path
          //                               latched it) -- this is the route-to-FPSP frame
          //   null                      : no FP op has executed since reset / null restore
          //   idle                      : otherwise
          val isUnimp = fpuCtrl.uiValid
          val isNull  = !fpuCtrl.everExecuted && !isUnimp
          val size    = Mux(isUnimp, U(44, 8 bits), U(4, 8 bits))
          fsIsNull  := isNull
          fsIsUnimp := isUnimp
          fsSize    := size
          // sysCapRc[3:1] = the EA mode, sysCapRc[0] = isRestore (0 here).
          //   -(An) (mode 100): the frame occupies [An-size, An), and An := An-size.
          //   (An)  (mode 010): the frame occupies [An, An+size), An unchanged.
          val isPredec = sysCapRc(3 downto 1) === U(4, 3 bits)
          val base     = Mux(isPredec, sysCapVal.asUInt - size.resize(32), sysCapVal.asUInt)
          fsFrameBase := base
          fsAnWrite   := isPredec
          fsStep      := 0
          fsSplitLow  := False
          fsVpnValid  := False    // force a translation before the first word
          // An write-back through the SAME port MOVEC's read direction uses. Driven HERE
          // (not after the stores) because the value is already known combinationally --
          // exactly MOVEC's shape. FRESTORE cannot do this (its size comes from memory),
          // hence its separate F_RDONE state.
          when(isPredec) {
            sysRegWriteValid := True
            sysRegWritePhys  := sysCapDstPhys
            sysRegWriteData  := base
          }
          // Emitting the frame CONSUMES the pending unimplemented state -- that hand-off
          // IS the route-to-FPSP mechanism.
          when(isUnimp) { clearUnimpPort.valid := True; clearUnimpPort.payload := True }
        }
        // ── FRESTORE <ea> (Task 11) ─────────────────────────────────────────────
        is(skOrd(m68k040.decode.SysKind.FRESTORE)) {
          // The frame base is An for BOTH admitted modes ((An)+ and (An)); only the
          // write-back differs. The pop size is unknown until the header word is read.
          fsFrameBase := sysCapVal.asUInt
          fsAnWrite   := sysCapRc(3 downto 1) === U(3, 3 bits)   // (An)+ postincrement
          fsVpnValid  := False
        }
      }
      // Task P5.5: CPUSH/CINV just pulsed a maintenance command whose walk runs for
      // many cycles; hold the (still serializing) sequencer until it reports done
      // before redirecting. Every other sysKind's effect completed within this single
      // S_APPLY cycle and goes straight on, exactly as before. Symbolic ordinals
      // (skOrd), same reasoning as the switch above.
      //
      // Task 11: FSAVE/FRESTORE detour through their own frame-transfer sub-sequences
      // first. The transition MUST live here and not inside the `switch` arms above --
      // a `goto` written there would be overridden by this later, unconditional one.
      when(sysCapKind === skOrd(m68k040.decode.SysKind.CPUSH) ||
           sysCapKind === skOrd(m68k040.decode.SysKind.CINV)) {
        goto(S_MAINTWAIT)
      } elsewhen(sysCapKind === skOrd(m68k040.decode.SysKind.FSAVE)) {
        goto(F_XREQ)
      } elsewhen(sysCapKind === skOrd(m68k040.decode.SysKind.FRESTORE)) {
        goto(F_RXREQ)
      } otherwise {
        goto(S_REDIR)
      }
    }
    // Task P5.5: await the maintenance walk's completion pulse. `maintCmdOut.valid` is
    // driven True ONLY inside S_APPLY's CPUSH/CINV arms, so it has already dropped back
    // to its declared `False` default by the time we get here — which is exactly right:
    // `maintCmd` is a Flow and DcachePlugin's walk entry is
    // `IDLE.when(maintCmdPort.valid)`, so a held-asserted command would re-trigger the
    // walk on completion instead of ending it. `maintDoneIn` defaults True for DUTs
    // with no D-cache wired, so this state costs them one cycle and never hangs.
    //
    // `icMaintPulse` (the I-side full invalidate, for a selector naming IC=10 or BC=11)
    // is asserted HERE, on the walk's completion transition, and NOT back in S_APPLY.
    // That ordering is load-bearing, not cosmetic: the architecturally correct CPUSH
    // sequence is D-side-writeback-COMPLETES *then* I-side-invalidate. A BC-selector
    // CPUSH ALL walk runs 1500+ cycles, and nothing gates instruction fetch on the
    // exception FSM being active (`FetchAlignPlugin`'s `ic.cmd.valid` has no `excActive`
    // term), so pulsing the I-invalidate at walk START would clear the I-cache and then
    // leave a multi-thousand-cycle window in which a fetch can refill lines from memory
    // the D-side has not yet written back — silently re-caching PRE-writeback (stale)
    // code for exactly the lines the CPUSH exists to make coherent. Pulsing at
    // completion NARROWS that window (not fully closes it): a same-cycle-only guard
    // elsewhere in IcachePlugin.scala means a refill whose AXI read was issued before
    // this invalidate pulse but whose allocation commits after it can still install
    // stale bytes. This re-timing eliminates the multi-thousand-cycle exposure for a
    // long BC-selector walk, but a short op like a Line-scope CINV can still race it —
    // that residual gap is tracked for a later task, not fixed here. The I-cache is
    // never dirty (read-only), so "push" and "invalidate" are the same operation on
    // that side, and CPUSH and CINV share this single exit.
    //
    // `sysCapRc` is a Reg still holding the CPUSH/CINV opword fields here, so the cache
    // selector is simply re-derived (same bit slice as the S_APPLY arms above).
    //
    // The BTB is fanned out from this same pulse at the wiring sites (FullCoreSynth +
    // the test DUTs) and therefore inherits the corrected timing for free.
    S_MAINTWAIT.whenIsActive {
      when(maintDoneIn) {
        val cacheSel = sysCapRc(1 downto 0)
        when(cacheSel === U(2, 2 bits) || cacheSel === U(3, 2 bits)) { icMaintPulse := True }
        goto(S_REDIR)
      }
    }
    // S_REDIR: the system-state writes from S_APPLY have now COMMITTED (a cycle later),
    // so ss.a7 / ss.srSys reflect the new state. Pulse the obs (post-state sysByte +
    // re-banked A7) + write the int PRF arch-15 with the re-banked A7 (a MOVE-to-SR S
    // flip switches the active bank), and redirect to the next instruction (serialize).
    S_REDIR.whenIsActive {
      // Re-bank A7 in the int PRF: ss.a7 = Mux(s, Mux(m, msp, isp), usp) with the
      // POST-write (S,M). The
      // a7Write port is qualified by obsFire (below). For MOVE-to-SR this carries the
      // user/supervisor SP switch into the datapath's arch-15. For MOVE-USP/MOVEC that
      // wrote USP while in supervisor, ss.a7 (=isp/msp) is unchanged -> a harmless re-write.
      obsFire    := True
      obsPc      := sysCapNextPc            // the sysOp's commit step == its nextPc
      obsSysByte := ss.srSys                // post-write system byte (S/T/I)
      obsA7      := ss.a7                   // re-banked A7 (Mux on post-write S)
      // MOVE-to-SR AND STOP write the full CCR (sysVal[4:0]) -> surface it so the whitebox
      // resyncs its running CCR to this absolute value. Other sysOps (MOVE-USP/MOVEC/
      // RESET/CPUSH/CINV/PFLUSHA/PTEST) leave CCR untouched. Symbolic ordinals (skOrd),
      // same reasoning as the S_APPLY switch above.
      when(sysCapKind === skOrd(m68k040.decode.SysKind.MOVE_TO_SR) ||
           sysCapKind === skOrd(m68k040.decode.SysKind.STOP)) {
        obsSetCcr5Valid := True
        obsSetCcr5      := sysCapVal(4 downto 0).asUInt
        // task #192: ALSO land the CCR in the REAL NZVC/X physical registers (not
        // just the whitebox `committedCcr` shadow above) -- see the class-level doc
        // comment on rteNzvcWriteValid for the full rationale/safety argument. SR/CCR
        // bit layout: bit4=X, bits3..0=N,Z,V,C (matches RTE's popSr(3 downto 0)/
        // popSr(4) split above).
        rteNzvcWriteValid := True
        rteNzvcWriteData  := sysCapVal(3 downto 0)
        rteXWriteValid    := True
        rteXWriteData     := sysCapVal(4)
      }
      redirectValid := True
      redirectPc    := sysCapNextPc
      goto(IDLE)
    }

    // ══ FSAVE: emit the state frame, one translated word at a time ═══════════════
    //
    // The address of the byte(s) this step actually touches. Task #163's cross-cache-line
    // word split is preserved VERBATIM (it is a separate, already-proven-necessary bug
    // class, unrelated to translation): `driveStoreNoXlate`'s plain {paddr,size} store
    // path has NO cross-line handling and silently DROPS the second byte of a word landing
    // at line-relative offset 15, so a crossing word is split into two single-byte pushes.
    // `fsave -(An)` with an odd An is perfectly legal, so this is reachable, not theoretical.
    //
    // Deriving the VPN from `fsCurVa` (the split-aware address) rather than from the word
    // address is what makes the translate-on-VPN-change rule correct at a page boundary:
    // a word at page offset $FFF splits, and its low byte genuinely lives in the NEXT
    // page, so the split phase re-triggers a translation all by itself.
    val fsWordAddr = fsFrameWordAddr(fsStep)
    val fsCrosses  = fsWordAddr(3 downto 0) === U(15, 4 bits)
    val fsCurVa    = Mux(fsSplitLow, fsWordAddr + U(1, 32 bits), fsWordAddr)
    val fsCurVpn   = fsCurVa(31 downto 12)
    val fsNeedXlate = !fsVpnValid || (fsCurVpn =/= fsLastVpn)

    F_XREQ.whenIsActive {
      dxReqValid := True
      dxReqVpn   := fsCurVpn
      dxReqWrite := True                       // the frame push is a STORE
      when(dxReqReady) { dxPendVpn := fsCurVpn; goto(F_XWAIT) }
    }
    F_XWAIT.whenIsActive {
      when(dxRspMine) {
        when(dxRspFault) {
          fsXlateFault := True
          goto(F_HALT)
        } otherwise {
          fsPpn      := dxRspPpn
          fsLastVpn  := dxPendVpn
          fsVpnValid := True
          goto(F_STORE)
        }
      }
    }
    F_STORE.whenIsActive {
      when(fsNeedXlate) {
        // A step (or a split phase) walked into a new page -- re-translate before
        // touching memory. At most one re-entry per frame in practice: 44 bytes can
        // cross at most one page boundary.
        goto(F_XREQ)
      } otherwise {
        val data = fsFrameWordData(fsStep)
        val pa   = (fsPpn ## fsCurVa(11 downto 0)).asUInt
        // NOTE: the ADDRESS is now genuinely translated, but the CACHE MODE deliberately
        // still comes from `excCacheMode` (the CACR.DE fold every other exception-path
        // store uses) rather than from `dxRspCacheMode`. Honouring the page's own cache
        // mode here would be more architecturally faithful, but it would also re-open the
        // task-P5.7 DE=0 coherency hole unless the DE fold were reapplied on top -- out of
        // scope for this task, and flagged rather than silently taken.
        when(fsSplitLow)     { driveStoreNoXlate(pa, Size.BYTE, data(7 downto 0)) }
        .elsewhen(fsCrosses) { driveStoreNoXlate(pa, Size.BYTE, data(15 downto 8)) }
        .otherwise           { driveStoreNoXlate(pa, Size.WORD, data) }
        goto(F_STWAIT)
      }
    }
    F_STWAIT.whenIsActive {
      when(dcStoreAck) {
        when(fsCrosses && !fsSplitLow) {
          // Just pushed the HIGH byte of a split word; push the LOW byte next (same
          // fsStep, same frame word -- do not advance).
          fsSplitLow := True
          goto(F_STORE)
        } otherwise {
          fsSplitLow := False
          when(fsStep === fsLastStep) { goto(S_REDIR) }
          .otherwise                  { fsStep := fsStep + 1; goto(F_STORE) }
        }
      }
    }

    // ══ FRESTORE: read the header word, dispatch on it ═══════════════════════════
    // Structurally R_FMTREQ/R_FMTWAIT: a single WORD read whose value selects what
    // happens next. Exactly ONE translated access is needed regardless of the real
    // frame's size, because a non-null frame's BODY is deliberately never applied (see
    // F_HDRWAIT) -- so the translate-on-VPN-change machinery degenerates to
    // "translate once, use once" here and is deliberately NOT built as a loop.
    F_RXREQ.whenIsActive {
      dxReqValid := True
      dxReqVpn   := fsFrameBase(31 downto 12)
      dxReqWrite := False                      // the header access is a LOAD
      when(dxReqReady) { dxPendVpn := fsFrameBase(31 downto 12); goto(F_RXWAIT) }
    }
    F_RXWAIT.whenIsActive {
      when(dxRspMine) {
        when(dxRspFault) {
          fsXlateFault := True
          goto(F_HALT)
        } otherwise {
          fsPpn      := dxRspPpn
          fsLastVpn  := dxPendVpn
          fsVpnValid := True
          goto(F_HDRREQ)
        }
      }
    }
    F_HDRREQ.whenIsActive {
      ldoVld   := True
      ldoVaddr := fsFrameBase
      ldoPaddr := (fsPpn ## fsFrameBase(11 downto 0)).asUInt
      ldoSize  := Size.WORD
      // WORD granularity, not LONG, for the same reason task #189 split RTE's PC read:
      // `DcacheByteLane.extract` has no cross-line awareness. The residual exposure is
      // identical to (and no worse than) that already-documented one -- a WORD landing
      // exactly at line-relative offset 15, which for the header would ALSO be a page
      // crossing. Not closed here; it is the same narrow residual class R_PCREQ carries.
      when(dcLoadCmd.fire) { goto(F_HDRWAIT) }
    }
    F_HDRWAIT.whenIsActive {
      when(dcLoadRsp.valid) {
        val hdr     = dcLoadRsp.payload.data(15 downto 0)
        val version = hdr(15 downto 8)
        val lenByte = hdr(7 downto 0).asUInt
        // Pop size = 4 + the length-in-hex indicator, for EVERY frame flavour. This is
        // the one FRESTORE rule that is architecturally universal, and it is exactly what
        // `fsave_frestore_basic.s`'s pop-size matrix checks (NULL -> 4, $28 -> 44,
        // $60 -> 100, and the FPSP's manufactured version-$41/size-$00 pseudo-null -> 4).
        // It costs nothing to get right for frame shapes this core never EMITS.
        fsSize := (U(4, 9 bits) + lenByte.resize(9)).resize(8)
        // A NULL frame (version byte $00) aborts all FPU operations and puts the FPU into
        // the RESET state -- MC68040 UM (1989 1st ed.) p.9-30: "When an FRESTORE of a null
        // state frame is performed, all FPU operations are aborted, and the FPU enters the
        // reset state." That is the only FRESTORE behavior real hardware unconditionally
        // requires, and the only one implemented. A non-null frame's BODY fields are
        // deliberately NOT applied (spec-narrowed scope): real 68040 FSAVE saves no
        // control registers at all, which is precisely why the ROM FPSP prologue
        // separately does FMOVEM.L FPIAR/FPSR/FPCR,-(A7).
        when(version === B(0, 8 bits)) {
          setEverExecutedPort.valid   := True; setEverExecutedPort.payload := False
          clearUnimpPort.valid        := True; clearUnimpPort.payload      := True
          setFpcrPort.valid   := True; setFpcrPort.payload   := U(0, 32 bits)
          setFpsrPort.valid   := True; setFpsrPort.payload   := U(0, 32 bits)
          setFpiarPort.valid  := True; setFpiarPort.payload  := U(0, 32 bits)
          fpccWriteValid := True; fpccWriteData := B(0, 4 bits)
        } otherwise {
          // A non-null frame means "the FPU had state" -> idle, not null, on a later FSAVE.
          setEverExecutedPort.valid := True; setEverExecutedPort.payload := True
        }
        goto(F_RDONE)
      }
    }
    // The An write-back for FRESTORE (An)+ has to happen HERE, not in S_APPLY: the pop
    // size is only known once the header has been read out of memory.
    F_RDONE.whenIsActive {
      when(fsAnWrite) {
        sysRegWriteValid := True
        sysRegWritePhys  := sysCapDstPhys
        sysRegWriteData  := fsFrameBase + fsSize.resize(32)
      }
      goto(F_RSETTLE)
    }
    // MANDATORY settle cycles before S_REDIR, for the `FRESTORE (A7)+` case specifically.
    //
    // S_REDIR unconditionally re-banks A7 by writing `committedPhysA7 := ss.a7` (its
    // `a7WriteValid := obsFire` port). When the sysOp's own auto-update destination IS A7,
    // the committed RAT now maps arch-15 at the SAME pdst `F_RDONE` just wrote -- so if
    // `ss.isp/msp` have not yet caught up, S_REDIR writes the STALE pre-FRESTORE A7 straight
    // back over the new one. `ss.writeA7` is fed from `committedA7In`, a PRF readback, so
    // the new value needs two cycles to appear there and then in the bank register:
    //   F_RDONE  : PRF[pdst] <= base+popSize      (visible next cycle)
    //   +1       : committedA7In = base+popSize -> ss.isp <= base+popSize
    //   +2       : ss.a7 reads the new bank      -> S_REDIR's re-bank is a harmless no-op
    // FSAVE needs no equivalent state: its own multi-cycle F_STORE/F_STWAIT frame loop
    // always provides far more than two cycles between the S_APPLY write and S_REDIR.
    val fsSettle = Reg(UInt(2 bits)) init 0
    F_RSETTLE.whenIsActive {
      fsSettle := fsSettle + 1
      when(fsSettle === U(2, 2 bits)) { fsSettle := 0; goto(S_REDIR) }
    }

    // ══ Terminal halt on a frame-translation fault ═══════════════════════════════
    //
    // DELIBERATE, DOCUMENTED DIVERGENCE FROM REAL 68040 BEHAVIOR. Real hardware would
    // take a precise ACCESS FAULT (vector 2, format-$7) here. This core cannot: an
    // ExceptionUnit episode genuinely cannot cleanly re-enter itself mid-sequence
    // (`RobPlugin`'s `faultRetire` is gated on `excIdle`, which is unconditionally False
    // for the whole episode), and unlike the one existing self-re-entry precedent (RTE
    // synthesizing a vector-3/14 entry for a malformed frame format) -- which only works
    // because RTE's path up to that point is READ-ONLY -- a partially-stacked FSAVE frame
    // has already committed real stores, and this project has no unwind machinery for
    // that. Building genuine nested/precise-fault re-entry would be new, unproven,
    // safety-critical machinery that exists nowhere else in this core.
    //
    // So instead the fault escalates to the existing sticky, first-error-wins
    // `RobPlugin.coreHaltedIn` -- the SAME escalation this project already uses for the
    // structurally analogous problem (`DcachePlugin`'s diagnostic-fault channel: WT-beat
    // drain, refill, eviction writeback, CPUSH writeback). `fsXlateFault` is a sticky Reg
    // driven out to the top level, where it ORs into `coreHaltedIn` alongside
    // `dc.diagFault`.
    //
    // This state is TERMINAL BY DESIGN and that is the point: it must actually STOP the
    // frame transfer, not merely raise a flag while the loop keeps writing garbage past
    // the faulting page. `fsStep` never advances, no store is ever driven again, and the
    // FSM never returns to IDLE -- which keeps `active` (and therefore `excActive`) held,
    // matching `coreHalted`'s own semantics (`headReady` forced False, the frontend
    // quiesced, interrupts blocked). A halted core stays halted.
    //
    // SCOPING NOTE so this is not mistaken for solving more than it does: this closes the
    // gap for FSAVE/FRESTORE's OWN new translated accesses only. The pre-existing fact
    // that ordinary entry/RTE frame stores mark `precise := True` specifically so a bus
    // error there never reaches the diagnostic channel at all is a SEPARATE, pre-existing
    // gap -- not introduced by Task 11 and not closed by it.
    F_HALT.whenIsActive {
      fsXlateFault := True   // hold it asserted; the ROB-side latch is sticky anyway
    }
  }

  // `active` high whenever the FSM is mid-sequence (not IDLE).
  active := !fsm.isActive(fsm.IDLE)

  // ---- debug-only observability (task #139 wild-PC / a7-minus-8 investigation) ----
  // Zero synth impact (sim tap only, not referenced by any RTL logic).
  active.simPublic()
  redirectValid.simPublic(); redirectPc.simPublic()
  val dbgFsmIsIdle    = fsm.isActive(fsm.IDLE);      dbgFsmIsIdle.simPublic()
  val dbgFsmIsEDrain  = fsm.isActive(fsm.E_DRAIN);   dbgFsmIsEDrain.simPublic()
  val dbgFsmIsEStore  = fsm.isActive(fsm.E_STORE);   dbgFsmIsEStore.simPublic()
  val dbgFsmIsEStWait = fsm.isActive(fsm.E_STWAIT);  dbgFsmIsEStWait.simPublic()
  val dbgFsmIsEVecReq = fsm.isActive(fsm.E_VECREQ);  dbgFsmIsEVecReq.simPublic()
  val dbgFsmIsEVecWait= fsm.isActive(fsm.E_VECWAIT); dbgFsmIsEVecWait.simPublic()
  val dbgFsmIsERedir  = fsm.isActive(fsm.E_REDIR);   dbgFsmIsERedir.simPublic()
}
