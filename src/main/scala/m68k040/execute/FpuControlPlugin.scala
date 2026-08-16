package m68k040.execute

import m68k040.services.FpuControlService
import spinal.core._
import spinal.core.sim._
import spinal.lib.Flow
import spinal.lib.misc.plugin.FiberPlugin

/** The single owner of the non-renamed FP control state (spec Decision 5): FPCR, FPSR's
  * non-FPCC bytes, FPIAR. Structurally a direct copy of `MmuControlPlugin`
  * (`src/main/scala/m68k040/mmu/MmuControl.scala:91-152`): committed `Reg`s (simPublic for
  * directed pokes), one default-idle `Flow` write port each, a single `when(port.valid)`
  * writer, and the `_field`/`override def` indirection that lets the service be resolved
  * before `logic` elaborates.
  *
  * WHY THESE THREE ARE NOT RENAMED (spec Decision 5, contrast Decision 4's FPCC): none sit
  * on the speculative hot path. FPCR changes only via an explicit FMOVE-to-FPCR and is read
  * for rounding mode, never branched on; FPSR's exception-status/accrued bytes are
  * architecturally an in-order/precise-exception concept, updated at commit exactly like
  * this project's existing `faultPc` capture; FPIAR is write-once-per-exception. A single
  * physical copy each with a commit-time interlock is cheaper and easier to get
  * precise-exception-correct than extending rename to registers that gain nothing from
  * speculative read-ahead.
  *
  * FPSR bit layout (architectural, MC68040 UM section 9.2.3): [31:24] condition code
  * (FPCC in [27:24]), [23:16] quotient, [15:8] exception status (EXC), [7:0] accrued
  * exception (AEXC). Bits [27:24] are NOT stored here — FPCC is renamed (Task 2/3) and
  * `fpsr` reads 0 there. The write port masks them off structurally (not by convention)
  * so the invariant cannot rot. */
class FpuControlPlugin extends FiberPlugin with FpuControlService {
  var _fpcr:  UInt = null
  var _fpsr:  UInt = null
  var _fpiar: UInt = null

  var _setFpcr:   Flow[UInt] = null
  var _setFpsr:   Flow[UInt] = null
  var _setFpiar:  Flow[UInt] = null
  var _orFpsrExc: Flow[Bits] = null

  // Task 11 (FSAVE/FRESTORE state frames).
  var _everExecuted:    Bool       = null
  var _setEverExecuted: Flow[Bool] = null
  var _uiValid:         Bool       = null
  var _uiCmdReg1B:      Bits       = null
  var _uiSrcOperand:    Bits       = null
  var _uiDstOperand:    Bits       = null
  var _setUnimpFrame:   Flow[Bits] = null
  var _clearUnimp:      Flow[Bool] = null

  override def fpcr:  UInt = _fpcr
  override def fpsr:  UInt = _fpsr
  override def fpiar: UInt = _fpiar

  override def setFpcr:   Flow[UInt] = _setFpcr
  override def setFpsr:   Flow[UInt] = _setFpsr
  override def setFpiar:  Flow[UInt] = _setFpiar
  override def orFpsrExc: Flow[Bits] = _orFpsrExc

  override def everExecuted:    Bool       = _everExecuted
  override def setEverExecuted: Flow[Bool] = _setEverExecuted
  override def uiValid:         Bool       = _uiValid
  override def uiCmdReg1B:      Bits       = _uiCmdReg1B
  override def uiSrcOperand:    Bits       = _uiSrcOperand
  override def uiDstOperand:    Bits       = _uiDstOperand
  override def setUnimpFrame:   Flow[Bits] = _setUnimpFrame
  override def clearUnimp:      Flow[Bool] = _clearUnimp

  override def roundingMode: Bits = _fpcr(5 downto 4).asBits
  override def precision:    Bits = _fpcr(7 downto 6).asBits
  override def excEnable:    Bits = _fpcr(15 downto 8).asBits

  /** EXC -> AEXC fold.
    *
    * PRIMARY SOURCE (Task 9 Step 12, executed — this is a verified transcription, not a
    * derivation): MC68040 User's Manual (M68040UM/AD rev 1), **Section 9.2.3.4 "Accrued
    * Exception (AEXC) Byte", pages 9-5/9-6**, whose Figure 9-6 gives the AEXC layout and
    * whose equation table ("These equations apply to setting the AEXC bits at the end of
    * each operation affecting the AEXC byte") gives, verbatim:
    * {{{
    *   New AEXC Bit  =  Old AEXC Bit  V  EXC Bits
    *   IOP           =  IOP           V  (SNAN V OPERR)
    *   OVFL          =  OVFL          V  (OVFL)
    *   UNFL          =  UNFL          V  (UNFL ^ INEX2)
    *   DZ            =  DZ            V  (DZ)
    *   INEX          =  INEX          V  (INEX1 V INEX2 V OVFL)
    * }}}
    *
    * EXC byte bit positions, from the SAME section's Figure 9-5 (FPSR[15:8], MSB first):
    *   15 BSUN, 14 SNAN, 13 OPERR, 12 OVFL, 11 UNFL, 10 DZ, 9 INEX2, 8 INEX1
    * i.e. within this 8-bit payload: 7 BSUN, 6 SNAN, 5 OPERR, 4 OVFL, 3 UNFL, 2 DZ,
    * 1 INEX2, 0 INEX1.
    *
    * AEXC byte bit positions, Figure 9-6 (FPSR[7:0], MSB first):
    *   7 IOP, 6 OVFL, 5 UNFL, 4 DZ, 3 INEX, 2:0 reserved (always 0).
    *
    * CORRECTION RECORDED (the reason Step 12 was made non-optional): the task brief's
    * draft of this function had `IOP = BSUN | SNAN | OPERR`. The manual's equation
    * EXCLUDES BSUN — AEXC.IOP accrues from SNAN and OPERR only. This is consistent with
    * BSUN's own description (section 9.7.1, page 9-25): BSUN is a PRE-instruction
    * exception raised by an IEEE-nonaware conditional predicate against the FPCC NAN bit,
    * not an arithmetic result condition, and when its trap is disabled the manual states
    * "the floating-point condition is evaluated as if it were the equivalent IEEE aware
    * conditional predicate. No exceptions are taken." Folding BSUN into the sticky
    * accrued-invalid-operation bit would make a disabled-trap FBEQ-on-NaN permanently
    * poison AEXC.IOP, which the manual explicitly does not do.
    *
    * Note also (same section, page 9-6): "At the end of most operations (FMOVEM and FMOVE
    * excluded), the bits in the EXC byte are logically combined to form an AEXC value that
    * is logically ORed into the existing AEXC byte." The FMOVE/FMOVEM exclusion is why
    * this fold lives on the EU's `orFpsrExc` port and NOT on the architectural `setFpsr`
    * write path: an FMOVE to/from a control register must never accrue anything. */
  private def aexcOf(exc: Bits): Bits = {
    val iop  = exc(6) || exc(5)                 // SNAN V OPERR      (NOT BSUN -- see above)
    val ovfl = exc(4)                           // OVFL
    val unfl = exc(3) && exc(1)                 // UNFL ^ INEX2
    val dz   = exc(2)                           // DZ
    val inex = exc(0) || exc(1) || exc(4)       // INEX1 V INEX2 V OVFL
    (iop ## ovfl ## unfl ## dz ## inex ## B(0, 3 bits))
  }

  val logic = during build new Area {
    // Power-on state: FPCR = 0 (round-to-nearest, extended precision, all traps disabled),
    // FPSR = 0, FPIAR = 0 -- the real 68040 reset state, and the state a null-frame
    // FRESTORE returns to (Task 11).
    val fpcr  = Reg(UInt(32 bits)) init 0; fpcr.simPublic()
    val fpsr  = Reg(UInt(32 bits)) init 0; fpsr.simPublic()
    val fpiar = Reg(UInt(32 bits)) init 0; fpiar.simPublic()

    val setFpcr   = Flow(UInt(32 bits))
    val setFpsr   = Flow(UInt(32 bits))
    val setFpiar  = Flow(UInt(32 bits))
    val orFpsrExc = Flow(Bits(8 bits))
    setFpcr.valid.allowOverride;   setFpcr.valid   := False; setFpcr.payload.allowOverride;   setFpcr.payload   := U(0, 32 bits)
    setFpsr.valid.allowOverride;   setFpsr.valid   := False; setFpsr.payload.allowOverride;   setFpsr.payload   := U(0, 32 bits)
    setFpiar.valid.allowOverride;  setFpiar.valid  := False; setFpiar.payload.allowOverride;  setFpiar.payload  := U(0, 32 bits)
    orFpsrExc.valid.allowOverride; orFpsrExc.valid := False; orFpsrExc.payload.allowOverride; orFpsrExc.payload := B(0, 8 bits)
    setFpcr.valid.simPublic();   setFpcr.payload.simPublic()
    setFpsr.valid.simPublic();   setFpsr.payload.simPublic()
    setFpiar.valid.simPublic();  setFpiar.payload.simPublic()
    orFpsrExc.valid.simPublic(); orFpsrExc.payload.simPublic()

    when(setFpcr.valid)  { fpcr  := setFpcr.payload }
    // FPCC ([27:24]) is renamed and lives in the FPCC PRF -- mask it out of this copy so
    // an architectural FPSR write can never leave a second, stale condition-code source.
    when(setFpsr.valid)  { fpsr  := setFpsr.payload & U(0xF0FFFFFFL, 32 bits) }
    when(setFpiar.valid) { fpiar := setFpiar.payload }
    // EU exception-status OR-in. Lower priority than an explicit FMOVE-to-FPSR: a `when`
    // written LATER wins in SpinalHDL, so the architectural write above must come FIRST
    // for the EU port to lose an exact-same-cycle collision -- but they cannot in fact
    // collide (the FMOVE write happens at a SERIALIZING commit, with the EU flushed), so
    // this ordering is defence-in-depth, not a live arbitration.
    when(orFpsrExc.valid && !setFpsr.valid) {
      fpsr(15 downto 8) := fpsr(15 downto 8) | orFpsrExc.payload.asUInt
      fpsr(7 downto 0)  := fpsr(7 downto 0)  | aexcOf(orFpsrExc.payload).asUInt
    }

    // ── Task 11: FSAVE null-vs-idle discriminator ─────────────────────────────────
    // RegInit(False): power-on reports NULL ("no FP instruction has executed since the
    // last hardware reset"), which is both what real hardware does and what lets an OS
    // skip a pointless FRESTORE. Set by the first committed FP-register write; cleared
    // ONLY by a null-frame FRESTORE (the one FRESTORE behavior real hardware
    // unconditionally requires -- "all FPU operations are aborted, and the FPU enters
    // the reset state", MC68040 UM 1989 1st ed. p.9-30).
    val everExecuted = RegInit(False); everExecuted.simPublic()
    val setEverExecuted = Flow(Bool())
    setEverExecuted.valid.allowOverride;   setEverExecuted.valid   := False
    setEverExecuted.payload.allowOverride; setEverExecuted.payload := False
    setEverExecuted.valid.simPublic();     setEverExecuted.payload.simPublic()

    // ── Task 11: latched unimplemented-instruction state ──────────────────────────
    // Written at vector-11 delivery for a RECOGNIZED FP op (Task 10's
    // `fpuSoftwareComplete`), read back by a later FSAVE, cleared when that FSAVE
    // consumes it.
    val uiValid      = RegInit(False);        uiValid.simPublic()
    val uiCmdReg1B   = Reg(Bits(16 bits)) init 0; uiCmdReg1B.simPublic()
    val uiSrcOperand = Reg(Bits(80 bits)) init 0; uiSrcOperand.simPublic()
    val uiDstOperand = Reg(Bits(80 bits)) init 0; uiDstOperand.simPublic()
    val setUnimpFrame = Flow(Bits(176 bits))
    val clearUnimp    = Flow(Bool())
    setUnimpFrame.valid.allowOverride;   setUnimpFrame.valid   := False
    setUnimpFrame.payload.allowOverride; setUnimpFrame.payload := B(0, 176 bits)
    clearUnimp.valid.allowOverride;      clearUnimp.valid      := False
    clearUnimp.payload.allowOverride;    clearUnimp.payload    := False
    setUnimpFrame.valid.simPublic();     setUnimpFrame.payload.simPublic()
    clearUnimp.valid.simPublic()

    when(setEverExecuted.valid) { everExecuted := setEverExecuted.payload }
    when(setUnimpFrame.valid) {
      uiValid      := True
      uiCmdReg1B   := setUnimpFrame.payload(175 downto 160)
      uiSrcOperand := setUnimpFrame.payload(159 downto 80)
      uiDstOperand := setUnimpFrame.payload(79 downto 0)
    }
    // A LATER `when` wins in SpinalHDL, so a same-cycle clear beats a set. That ordering
    // can only matter if an FSAVE retires in the very cycle a vector-11 entry is
    // delivered, which the serializing sysOp/exception FSM makes impossible (both are
    // states of the SAME ExceptionUnit FSM) -- defence in depth, not a live arbitration.
    when(clearUnimp.valid) { uiValid := False }

    _fpcr = fpcr; _fpsr = fpsr; _fpiar = fpiar
    _setFpcr = setFpcr; _setFpsr = setFpsr; _setFpiar = setFpiar; _orFpsrExc = orFpsrExc
    _everExecuted = everExecuted; _setEverExecuted = setEverExecuted
    _uiValid = uiValid; _uiCmdReg1B = uiCmdReg1B
    _uiSrcOperand = uiSrcOperand; _uiDstOperand = uiDstOperand
    _setUnimpFrame = setUnimpFrame; _clearUnimp = clearUnimp
  }
}
