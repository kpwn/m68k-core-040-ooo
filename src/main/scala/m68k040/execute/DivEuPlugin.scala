package m68k040.execute

import m68k040.decode.DecOp
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService,
  RegFileReadPort, RegFileWritePort, RegFileBypassPort}
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.misc.plugin.FiberPlugin

/** Plain-wire CPLX (complex / DivEu) service. Producer (IQ/test) drives `issue`;
  * the ROB-side wiring reads `completion`, `wakeup`, and the generalized `euFault`. */
trait DivEuService {
  def issue: Stream[IqContext]
  def completion: Flow[UInt]   // robId (ROB completion port)
  def wakeup: Flow[UInt]       // pdst of a completing CPLX op (dynamic wakeup)
  /** pNzvcDst of a completing CPLX op that writes flags (dynamic-completion NZVC
    * wakeup — task #167). SEPARATE from `wakeup` (int pdst): CHK2/CMP2/CHK write NZVC
    * with no int dst at all, and DIV/MUL's int dst and NZVC dst can wake at different
    * moments relative to a consumer that only reads one or the other. Mirrors
    * LsEuService's `wakeupNzvc`. */
  def wakeupNzvc: Flow[UInt]
  /** Execute-time conditional fault (CHK -> vector 6, DIV0 -> vector 5). The ROB
    * consumes it like the branch EU's trapvFault (generalized euFault). */
  def euFault: Flow[EuFault]
  /** Mispredict/exception squash (the RedirectService doFlush pulse). A MULTI-CYCLE
    * op (DIV/MUL) in flight when a flush hits is WRONG-PATH: its late completion
    * would land after the ROB reuses its robId. The fixed pipeline and result queues
    * are cleared; the iterative divider is poisoned until its internal iteration
    * finishes. All same-cycle and late side effects are suppressed. */
  def cplxFlush: Bool
}

/** Pruned descriptor that follows the fixed MUL datapath.  Do not replace this
  * with IqContext: the multiplier needs only result-routing and flag metadata. */
case class MulPipeContext() extends Bundle {
  val robId       = UInt(6 bits)
  val pdst        = UInt(6 bits)
  val pdstValid   = Bool()
  val pNzvcDst    = UInt(4 bits)
  val writesNzvc  = Bool()
  val dstArch     = UInt(5 bits)
  val size        = Size()
  val signed      = Bool()
  val is64        = Bool()
}

/** A cracked MULHI tail may arrive before its high product.  Queueing its small
  * routing descriptor removes it from the sole CPLX issue port without carrying
  * a full IqContext or inventing a false PRF dependency. */
case class MulHiContext() extends Bundle {
  val robId       = UInt(6 bits)
  val pdst        = UInt(6 bits)
  val dstArch     = UInt(5 bits)
}

/** One result waiting for the existing single CPLX completion/writeback lane. */
case class CplxResult() extends Bundle {
  val robId       = UInt(6 bits)
  val data        = Bits(32 bits)
  val pdst        = UInt(6 bits)
  val pdstValid   = Bool()
  val nzvc        = Bits(4 bits)
  val nzvcWrite   = Bool()
  val pNzvcDst    = UInt(4 bits)
  val dstArch     = UInt(5 bits)
  val fault       = Bool()
  val faultVec    = UInt(8 bits)
  val crackTail   = Bool()
  val flushed     = Bool()
  // Only a main .L64 MUL result uses these fields.  They are consumed when the
  // low result leaves the MUL queue and become the ROB-keyed MULHI stash entry.
  val mulHigh     = Bits(32 bits)
  val mul64       = Bool()
}

/** CPLX execution unit: fixed-latency II=1 MUL, CHK/CMP2, and iterative DIV.
  *
  * The iterative/legacy lane remains single-outstanding, but MUL owns an independent
  * seven-stage descriptor/product pipeline and result FIFO.  It may accept every cycle
  * while a divide iterates.  Both lanes retain the existing single dynamic-completion
  * wakeup and PRF/ROB result port through a lossless arbiter. CHK is single-cycle;
  * it writes no register and, when out-of-bounds, raises euFault{vec6}. DIV writes the
  * quotient (+ the remainder via a trailing DIVREM crack) and on divisor==0 raises
  * euFault{vec5}; overflow sets V (no write, no trap).
  *
  * Pipeline:
  *   S0  read srcA (CHK: Dn ; DIV: dividend low/Dq) + srcB (CHK: bound ; DIV: divisor).
  *       For DIV64 the high dividend word (Dr) is also read (a third read of psrcB?
  *       no — see decode: DIV reads Dq via psrcA, divisor via imm/psrcB; the 64-bit
  *       high word Dr is read via a dedicated 3rd port). M2S register.
  *   S1  CHK: compare -> complete (no write) or euFault. DIV: launch the iterative
  *       core; while busy, hold; on done -> writeback + complete + wakeup (or euFault).
  */
class DivEuPlugin extends FiberPlugin with DivEuService {
  var issuePort: Stream[IqContext] = null
  var completionPort: Flow[UInt]   = null
  var wakeupPort: Flow[UInt]       = null
  var wakeupNzvcPort: Flow[UInt]   = null
  var euFaultPort: Flow[EuFault]   = null
  var rdA, rdB, rdH: RegFileReadPort = null
  var intW: RegFileWritePort = null;  var intByp: RegFileBypassPort = null
  var nzvcW: RegFileWritePort = null; var nzvcByp: RegFileBypassPort = null
  var nzvcRd: RegFileReadPort = null  // CMP2/CHK2 old-NZVC read (preserve N/V in the RMW)
  var flushSig: Bool = null

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort
  override def wakeup: Flow[UInt]       = wakeupPort
  override def wakeupNzvc: Flow[UInt]   = wakeupNzvcPort
  override def euFault: Flow[EuFault]   = euFaultPort
  override def cplxFlush: Bool          = flushSig

  during setup {
    issuePort      = Stream(IqContext())
    completionPort = Flow(UInt(6 bits))
    wakeupPort     = Flow(UInt(6 bits))
    wakeupNzvcPort = Flow(UInt(4 bits))
    euFaultPort    = Flow(EuFault()); euFaultPort.simPublic()
    flushSig       = Bool()
    // default-driven idle (allowOverride) so a standalone test that does not wire a
    // flush still elaborates; the BackendWiring drives it from doFlush.
    flushSig.allowOverride; flushSig := False
    val irf = host[IntRegFileService]
    rdA = irf.newRead()   // CHK: Dn ; DIV: dividend low (Dq)
    rdB = irf.newRead()   // CHK: bound ; DIV: divisor (when register)
    rdH = irf.newRead()   // DIV64: dividend high (Dr)
    intW = irf.newWrite(latency = 1); intByp = irf.newBypass()
    val nz = host[NzvcRegFileService]
    nzvcW = nz.newWrite(latency = 1); nzvcByp = nz.newBypass()
    nzvcRd = nz.newRead(forceNoBypass = false)   // CMP2/CHK2 reads old N/V to preserve them
  }

  val logic = during build new Area {
    // ---- S0: read operands ----
    val u0 = issuePort.payload.uop
    rdA.addr := u0.psrcA
    rdB.addr := u0.psrcB
    rdH.addr := u0.psrcC     // DIV.L 64/32 dividend HIGH word (Dr) via the 3rd source
    nzvcRd.addr := u0.pNzvcSrc                  // CMP2/CHK2 old NZVC (preserve N/V)
    val s0A = rdA.data
    val s0B = Mux(u0.useImm, u0.imm, rdB.data)
    val s0H = rdH.data
    val s0Nzvc = nzvcRd.data                    // {N(3),Z(2),V(1),C(0)}

    // Iterative/legacy lane occupancy.  A main MUL bypasses this lane and has its own
    // credit gate below; every other CPLX operation still uses s1 + the divider FSM.
    val busy = RegInit(False)
    val s1Valid = RegInit(False)
    val issueIsMul   = u0.op === DecOp.MUL
    val issueIsMulHi = u0.op === DecOp.MULHI
    val mulCanAccept = Bool(); mulCanAccept.allowOverride; mulCanAccept := False
    val mulHiAvailable = Bool(); mulHiAvailable.allowOverride; mulHiAvailable := False
    // The IQ connection is a non-collapsing registered Stream.  When its current
    // valid is low, stale payload bits must not hold ready low or the IQ cannot load
    // a new lane-eligible candidate behind an active divider.
    issuePort.ready := !flushSig && (!issuePort.valid || Mux(issueIsMul,
      mulCanAccept,
      Mux(issueIsMulHi, mulHiAvailable, !busy && !s1Valid)))

    // ---- debug-only observability (task #139 CMP2/CHK2 hang investigation) ----
    // Zero synth impact (sim tap only, not referenced by any RTL logic).
    busy.simPublic(); s1Valid.simPublic()
    issuePort.valid.simPublic(); issuePort.ready.simPublic()
    issuePort.payload.robId.simPublic(); issuePort.payload.uop.op.simPublic()

    // ---- S0 -> S1 register (M2S), captured on issue.fire ----
    val s1Ctx   = Reg(IqContext())
    val s1A     = Reg(Bits(32 bits))
    val s1B     = Reg(Bits(32 bits))
    val s1H     = Reg(Bits(32 bits))
    val s1Nzvc  = Reg(Bits(4 bits))    // old {N,Z,V,C} for the CMP2/CHK2 RMW
    val u1 = s1Ctx.uop

    // Main MUL and MULHI have independent pipelines/queues below.  Every other
    // operation captures the legacy lane context and clears it explicitly on use.
    when(issuePort.fire && !issueIsMul && !issueIsMulHi) {
      s1Valid := True
      s1Ctx   := issuePort.payload
      s1A     := s0A
      s1B     := s0B
      s1H     := s0H
      s1Nzvc  := s0Nzvc
    }

    // Mispredict/exception squash: a 1-cycle doFlush pulse. Latch it so the eventual
    // completion of an iterative DIV that was IN FLIGHT at the flush is
    // suppressed — its robId may be reused by a correct-path op before the (late)
    // completion lands. `flushed` is set on any flush while busy/s1Valid (an in-flight
    // op); it is captured into compFlushed at the op's completion and cleared then.
    val flushed = RegInit(False)
    when(flushSig && (busy || s1Valid)) { flushed := True }
    // A flush also drops a not-yet-launched legacy s1 op.
    when(flushSig) { s1Valid := False }

    // ─────────────────────────────────────────────────────────────────────────
    // Registered COMPLETION / WRITEBACK stage (mirrors LsEu): the arbiter's result
    // is captured into comp* registers and DRIVES the
    // completion/writeback/wakeup/euFault ports the SAME or next cycle. A 1-cycle
    // pulse: default-clear, set only by a capture.
    val compValid     = RegInit(False)
    val compRobId     = Reg(UInt(6 bits))
    val compData      = Reg(Bits(32 bits))
    val compPdst      = Reg(UInt(6 bits))
    val compPdstValid = RegInit(False)
    val compNzvc      = Reg(Bits(4 bits))
    val compNzvcWrite = RegInit(False)
    val compNzvcDst   = Reg(UInt(nzvcW.address.getWidth bits))
    val compDstArch   = Reg(UInt(5 bits))
    // euFault capture (CHK out-of-bounds vec6 / DIV0 vec5).
    val compFault     = RegInit(False)
    val compFaultVec  = Reg(UInt(8 bits))
    // True when the captured completion is the trailing DIVREM crack µop (whitebox
    // drops its commit; the PRF write still lands). Captured in the capture helpers.
    val compDivRem    = RegInit(False)
    // True when the completing op was WRONG-PATH (a flush hit it in flight). Its
    // completion/writeback/wakeup/euFault are suppressed (see the port drives below).
    val compFlushed   = RegInit(False)

    compValid     := False
    compNzvcWrite := False
    compFault     := False

    // CHK/CMP2/DIV/DIVREM produce into one held legacy result.  This removes
    // the old direct multi-source assignments to comp*, so a simultaneous MUL result
    // can remain queued rather than being overwritten on the Flow-only ROB port.
    val legacyValid = RegInit(False)
    val legacyResult = Reg(CplxResult())

    // ---- CHK compare (single-cycle) ----
    // CHK.W compares the low 16 bits (sign-extended); CHK.L the full 32. Trap
    // (vector 6) iff Dn<0 || Dn>bound. CHK writes no register, but DOES commit a
    // full NZVC every execution (both trap and no-trap paths) — see chkNzvc below.
    val isChk = u1.op === DecOp.CHK
    val chkDn = u1.size.mux(
      Size.WORD -> s1A(15 downto 0).asSInt.resize(32),
      Size.LONG -> s1A.asSInt,
      default   -> s1A.asSInt)
    val chkBound = u1.size.mux(
      Size.WORD -> s1B(15 downto 0).asSInt.resize(32),
      Size.LONG -> s1B.asSInt,
      default   -> s1B.asSInt)
    val chkNeg   = chkDn < 0
    val chkOver  = chkDn > chkBound
    val chkTrap  = chkNeg || chkOver
    // N flag per the rule: N=1 if Dn<0, N=0 otherwise (incl. Dn>bound and in-bounds).
    // Z = (Dn==0) — Musashi's m68k_op_chk_{16,32}_d: `FLAG_Z = ZFLAG_16/32(src)`, set
    // UNCONDITIONALLY from the checked value's own zero-ness (labeled "Undocumented" in
    // Musashi but real, oracle-matching 68k behavior — NOT hardcoded 0 as previously
    // assumed here; found via a 200-seed fuzz campaign, task #139, 2026-07-16). V/C are
    // genuinely always 0 (also "Undocumented" in Musashi, confirmed unconditional).
    // CHK ALWAYS commits this NZVC (even on the trap path, so the stacked CCR matches).
    val chkN     = chkNeg
    val chkZ     = chkDn === 0
    val chkNzvc  = (chkN ## chkZ ## False ## False).asBits   // N Z V(0) C(0)

    // ─────────────────────────────────────────────────────────────────────────
    // CMP2 / CHK2 bounds compare (single-cycle, transcribed VERBATIM from Musashi
    // m68k_op_chk2cmp2_{8,16,32}). Operands: s1A = lower (T0, LS-loaded), s1B = upper
    // (T1, LS-loaded), s1H = Rn (the compared reg, via psrcC). lower/upper are SIGN-
    // extended from the loaded size; Rn is masked to the size then, for .B/.W, sign-
    // extended ONLY for a DATA reg (u1.divSigned reused as adReg: True = An -> NO
    // sign-extend, stays masked). .L uses the full 32 bits (no mask / no sign-ext).
    //   FLAG_Z = !((upper==compare)||(lower==compare))  -> Z_bit = (==lower || ==upper)
    //   FLAG_C = signed(compare<lower || compare>upper)  (both ternary branches equal)
    // CCR RMW = {oldN, Z, oldV, C} (preserve N/V; readsNzvc/writesNzvc). CHK2 (isChk2)
    // raises EuFault{vec6} on C (out-of-bounds); CMP2 never traps.
    val isCmp2 = u1.op === DecOp.CMP2CHK2
    val c2Lower = u1.size.mux(
      Size.BYTE -> s1A( 7 downto 0).asSInt.resize(32),
      Size.WORD -> s1A(15 downto 0).asSInt.resize(32),
      default   -> s1A.asSInt)
    val c2Upper = u1.size.mux(
      Size.BYTE -> s1B( 7 downto 0).asSInt.resize(32),
      Size.WORD -> s1B(15 downto 0).asSInt.resize(32),
      default   -> s1B.asSInt)
    // compare (Rn): mask to size; then for .B/.W, sign-extend ONLY when adReg==False
    // (data reg). For an address reg (.B/.W) it stays masked (zero-extended -> positive).
    // .L is the full 32 bits regardless of adReg.
    val c2AdReg = u1.divSigned                  // reused: True = An (no .B/.W sign-ext)
    val c2RnByte = s1H( 7 downto 0)
    val c2RnWord = s1H(15 downto 0)
    val c2Compare = SInt(32 bits)
    switch(u1.size) {
      is(Size.BYTE) {
        c2Compare := Mux(c2AdReg, (B(0, 24 bits) ## c2RnByte).asSInt,    // An: zero-ext (masked)
                                  c2RnByte.asSInt.resize(32))            // Dn: sign-ext
      }
      is(Size.WORD) {
        c2Compare := Mux(c2AdReg, (B(0, 16 bits) ## c2RnWord).asSInt,
                                  c2RnWord.asSInt.resize(32))
      }
      default { c2Compare := s1H.asSInt }                               // .L: full 32
    }
    val c2Zbit = (c2Compare === c2Lower) || (c2Compare === c2Upper)
    val c2Cbit = (c2Compare < c2Lower) || (c2Compare > c2Upper)         // signed OOB
    val c2OldN = s1Nzvc(3)
    val c2OldV = s1Nzvc(1)
    val c2Nzvc = (c2OldN ## c2Zbit ## c2OldV ## c2Cbit).asBits          // {oldN, Z, oldV, C}
    val c2Trap = u1.isChk2 && c2Cbit                                    // CHK2 out-of-bounds

    // captured-completion helpers. `writeInt` lets the caller suppress the register
    // write (e.g. DIV overflow: V=1 but Dn unchanged) without a second overlapping
    // assignment to compPdstValid.
    def captureComplete(result: Bits, nzvc: Bits, writesNzvc: Bool, writeInt: Bool): Unit = {
      legacyValid              := True
      legacyResult.robId       := s1Ctx.robId
      legacyResult.data        := result
      legacyResult.pdst        := u1.pdst
      legacyResult.pdstValid   := u1.pdstValid && writeInt
      legacyResult.dstArch     := u1.dstArch
      legacyResult.nzvc        := nzvc
      legacyResult.nzvcWrite   := writesNzvc
      legacyResult.pNzvcDst    := u1.pNzvcDst
      legacyResult.fault       := False
      legacyResult.faultVec    := 0
      // The trailing crack µop (DIVREM or MULHI) is coalesced into the preceding
      // op's oracle step (its own commit is dropped, the PRF write still lands).
      legacyResult.crackTail   := u1.divIsRem || (u1.op === DecOp.MULHI)
      // Was this op (or its in-flight predecessor) flushed? If so the completion is
      // wrong-path and must not drive any port (its robId may have been reused).
      legacyResult.flushed     := flushed || flushSig
      legacyResult.mulHigh     := 0
      legacyResult.mul64       := False
      flushed       := False                 // captured -> consume the latch
    }
    def captureFault(vec: UInt, nzvc: Bits, writesNzvc: Bool): Unit = {
      legacyValid              := True
      legacyResult.robId       := s1Ctx.robId
      legacyResult.data        := 0
      legacyResult.pdst        := u1.pdst
      legacyResult.pdstValid   := False
      legacyResult.dstArch     := u1.dstArch
      legacyResult.nzvc        := nzvc
      legacyResult.nzvcWrite   := writesNzvc
      legacyResult.pNzvcDst    := u1.pNzvcDst
      legacyResult.fault       := True
      legacyResult.faultVec    := vec
      legacyResult.crackTail   := False
      legacyResult.flushed     := flushed || flushSig
      legacyResult.mulHigh     := 0
      legacyResult.mul64       := False
      flushed       := False
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DIV integration (DIVU/DIVS): the iterative DivUnit (sign-normalize +
    // magnitudes + fix-up + overflow). Operands are EXTENDED to the core's 64/32
    // width here per the form/sign:
    //   .W   : dividend = Dn (32b) s/z-ext to 64 ; divisor = EA[15:0] s/z-ext to 32.
    //   .L32 : dividend = Dq (32b) s/z-ext to 64 ; divisor = EA (32b).
    //   .L64 : dividend = Dr:Dq (64b)            ; divisor = EA (32b).  (T7)
    val isDiv = u1.op === DecOp.DIV
    val divForm = DivForm()
    when(u1.size === Size.WORD) { divForm := DivForm.W }
      .otherwise { divForm := Mux(u1.div64, DivForm.L64, DivForm.L32) }
    // dividend low 32 = s1A (Dn/Dq). high 32 = s1H (Dr, only for .L64). For non-.L64
    // the high half is the sign/zero extension of s1A.
    val divSigned = u1.divSigned
    // dividend: for .W the 32-bit Dn is the value; sign/zero-extend to 64.
    val dividend64 = UInt(64 bits)
    when(u1.div64) {
      dividend64 := (s1H ## s1A).asUInt
    } elsewhen(u1.size === Size.WORD) {
      // .W: the WHOLE 32-bit Dn is the dividend (32/16). s/z-ext 32->64.
      dividend64 := Mux(divSigned && s1A(31), (B(0xFFFFFFFFL, 32 bits) ## s1A).asUInt, (B(0, 32 bits) ## s1A).asUInt)
    } otherwise {
      // .L32: 32-bit dividend s/z-ext 32->64.
      dividend64 := Mux(divSigned && s1A(31), (B(0xFFFFFFFFL, 32 bits) ## s1A).asUInt, (B(0, 32 bits) ## s1A).asUInt)
    }
    val divisor32 = UInt(32 bits)
    when(u1.size === Size.WORD) {
      // 16-bit divisor in s1B[15:0]; s/z-ext to 32.
      divisor32 := Mux(divSigned && s1B(15), (B(0xFFFF, 16 bits) ## s1B(15 downto 0)).asUInt, (B(0, 16 bits) ## s1B(15 downto 0)).asUInt)
    } otherwise {
      divisor32 := s1B.asUInt
    }

    val divUnit = new DivUnit
    divUnit.io.start    := False
    divUnit.io.dividend := dividend64
    divUnit.io.divisor  := divisor32
    divUnit.io.signed   := divSigned
    divUnit.io.form     := divForm

    // ---- pack the DIV result into Dn per form ----
    // .W   : Dn = {remainder[15:0], quotient[15:0]}.
    // .L32/.L64 quotient-only path (this task handles .W; .L in T6/T7) -> quotient.
    val resQ = divUnit.io.quotient
    val resR = divUnit.io.remainder
    val divResultW = (resR(15 downto 0) ## resQ(15 downto 0)).asBits  // .W packed
    val divResult  = Mux(u1.size === Size.WORD, divResultW, resQ.asBits)
    // NZVC: N/Z from the quotient (at the dest width); V = overflow; C = 0.
    val qN = Mux(u1.size === Size.WORD, resQ(15), resQ(31))
    val qZ = Mux(u1.size === Size.WORD, resQ(15 downto 0) === 0, resQ === 0)
    val divNzvcNormal = (qN ## qZ ## False ## False).asBits          // N Z V(0) C(0)
    // Overflow flags (FUZZER-CAUGHT B5): Musashi's divs/divu overflow path is
    // `FLAG_V = VFLAG_SET; return;` — N, Z and C keep their PRE-DIV values. Only V
    // is set. The old NZVC is read via the CMP2/CHK2 nzvcRd port (DIV µops set
    // readsNzvc) and latched in s1Nzvc.
    val divNzvcOver   = (s1Nzvc(3) ## s1Nzvc(2) ## True ## s1Nzvc(0)).asBits  // {oldN, oldZ, V=1, oldC}
    // On overflow: V=1 (N/Z/C preserved), NO result write (Dn unchanged). On DIV0:
    // euFault vec5, no write.

    // ---- DIVREM (remainder-move) support: latch the just-finished DIV's REMAINDER
    // (and whether it overflowed) so the trailing DIVREM µop writes Dr. The DIVREM is
    // the next CPLX µop in age order (single-outstanding), so the latch is valid. On a
    // DIV overflow NEITHER dest is written -> the DIVREM must also skip its write.
    val isDivRem = u1.divIsRem
    val remLatch = Reg(Bits(32 bits))
    val ovLatch  = RegInit(False)

    // ─────────────────────────────────────────────────────────────────────────
    // MUL integration: a fixed seven-stage DSP datapath, pruned descriptor pipe,
    // and credit-reserved result FIFO.  It never occupies s1/busy and therefore
    // continues to accept while DivUnit iterates.
    val mulIssueA = Bits(32 bits)
    val mulIssueB = Bits(32 bits)
    when(u0.size === Size.WORD) {
      mulIssueA := Mux(u0.divSigned && s0A(15), B(0xFFFF, 16 bits), B(0, 16 bits)) ## s0A(15 downto 0)
      mulIssueB := Mux(u0.divSigned && s0B(15), B(0xFFFF, 16 bits), B(0, 16 bits)) ## s0B(15 downto 0)
    } otherwise {
      mulIssueA := s0A
      mulIssueB := s0B
    }

    // Reservation is released only when a product leaves the result FIFO. Allow
    // the complete non-stallable pipe plus push-to-pop visibility to be resident
    // so a continuous MUL stream never bubbles before its first retirement.
    val mulResultDepth = MulCore.Latency + 2
    val mulResultQ = StreamFifo(CplxResult(), mulResultDepth)
    val mulHiPendingQ = StreamFifo(MulHiContext(), mulResultDepth)
    mulResultQ.io.flush := flushSig
    mulHiPendingQ.io.flush := flushSig

    val mulReserved = Reg(UInt(log2Up(mulResultDepth + 1) bits)) init 0
    val mulRetire = mulResultQ.io.pop.fire
    mulCanAccept := (mulReserved =/= mulResultDepth) || mulRetire
    mulHiAvailable := mulHiPendingQ.io.push.ready

    val mulStart = issuePort.fire && issueIsMul
    val mulHiAccept = issuePort.fire && issueIsMulHi
    when(mulHiAccept) { assert(u0.pdstValid, "MULHI must carry an integer destination") }
    mulHiPendingQ.io.push.valid := mulHiAccept
    mulHiPendingQ.io.push.payload.robId := issuePort.payload.robId
    mulHiPendingQ.io.push.payload.pdst := u0.pdst
    mulHiPendingQ.io.push.payload.dstArch := u0.dstArch

    switch(mulStart ## mulRetire) {
      is(B"10") { mulReserved := mulReserved + 1 }
      is(B"01") { mulReserved := mulReserved - 1 }
    }
    when(flushSig) { mulReserved := 0 }

    val mulCore = new MulCore
    mulCore.io.start  := mulStart
    mulCore.io.a      := mulIssueA
    mulCore.io.b      := mulIssueB
    mulCore.io.signed := u0.divSigned

    val mulCtx = Vec.fill(MulCore.Latency)(Reg(MulPipeContext()))
    val mulCtxValid = Vec.fill(MulCore.Latency)(RegInit(False))
    mulCtxValid(0) := mulStart
    when(mulStart) {
      mulCtx(0).robId      := issuePort.payload.robId
      mulCtx(0).pdst       := u0.pdst
      mulCtx(0).pdstValid  := u0.pdstValid
      mulCtx(0).pNzvcDst   := u0.pNzvcDst
      mulCtx(0).writesNzvc := u0.writesNzvc
      mulCtx(0).dstArch    := u0.dstArch
      mulCtx(0).size       := u0.size
      mulCtx(0).signed     := u0.divSigned
      mulCtx(0).is64       := u0.div64
    }
    for (i <- 1 until MulCore.Latency) {
      mulCtxValid(i) := mulCtxValid(i - 1)
      when(mulCtxValid(i - 1)) { mulCtx(i) := mulCtx(i - 1) }
    }
    when(flushSig) { mulCtxValid.foreach(_ := False) }

    val mulDoneCtx = mulCtx.last
    val mulLo = mulCore.io.prodLo
    val mulHi = mulCore.io.prodHi
    val mulLoN = mulLo(31)
    val mulSext = Mux(mulLoN, B(0xFFFFFFFFL, 32 bits), B(0, 32 bits))
    val mulOverflow = Mux(mulDoneCtx.signed, mulHi =/= mulSext, mulHi =/= B(0, 32 bits))
    val mulV = (mulDoneCtx.size =/= Size.WORD) && !mulDoneCtx.is64 && mulOverflow
    val mulN = Mux(mulDoneCtx.is64, mulHi(31), mulLoN)
    val mulZ = Mux(mulDoneCtx.is64, (mulHi | mulLo) === 0, mulLo === 0)
    val mulNzvc = (mulN ## mulZ ## mulV ## False).asBits

    mulResultQ.io.push.valid := mulCtxValid.last && mulCore.io.done && !flushSig
    mulResultQ.io.push.payload.robId       := mulDoneCtx.robId
    mulResultQ.io.push.payload.data        := mulLo
    mulResultQ.io.push.payload.pdst        := mulDoneCtx.pdst
    mulResultQ.io.push.payload.pdstValid   := mulDoneCtx.pdstValid
    mulResultQ.io.push.payload.nzvc        := mulNzvc
    mulResultQ.io.push.payload.nzvcWrite   := mulDoneCtx.writesNzvc
    mulResultQ.io.push.payload.pNzvcDst    := mulDoneCtx.pNzvcDst
    mulResultQ.io.push.payload.dstArch     := mulDoneCtx.dstArch
    mulResultQ.io.push.payload.fault       := False
    mulResultQ.io.push.payload.faultVec    := 0
    mulResultQ.io.push.payload.crackTail   := False
    mulResultQ.io.push.payload.flushed     := False
    mulResultQ.io.push.payload.mulHigh     := mulHi
    mulResultQ.io.push.payload.mul64       := mulDoneCtx.is64
    when(mulCtxValid.last) { assert(mulCore.io.done) }
    assert(!mulResultQ.io.push.valid || mulResultQ.io.push.ready,
      "reserved MUL result FIFO credit was not available at product completion")

    // A main .L64 result is associated with the immediately following MULHI ROB
    // entry.  Store high under (mainRob+1) so the queued tail indexes with its own id.
    val mulHiMem = Mem(Bits(32 bits), 64)
    val mulHiValid = Reg(Bits(64 bits)) init 0
    val mulHiHead = mulHiPendingQ.io.pop.payload
    val mulHiHeadReady = mulHiPendingQ.io.pop.valid && mulHiValid(mulHiHead.robId)
    val mulHiData = mulHiMem.readAsync(mulHiHead.robId)

    // ---- FSM ----
    val fsm = new StateMachine {
      val IDLE  = new State with EntryPoint
      val DIVING = new State    // DivUnit iterating

      IDLE.whenIsActive {
        busy := False
        when(s1Valid) {
          when(isChk) {
            // single-cycle bound check; CHK writes NZVC (N per rule, Z/V/C=0) on BOTH
            // paths so the committed/stacked CCR's N matches Musashi.
            when(chkTrap) { captureFault(U(6, 8 bits), chkNzvc, True) }
              .otherwise   { captureComplete(B(0, 32 bits), chkNzvc, True, False) }  // in-bounds: N=0, no reg write
            s1Valid := False
          } elsewhen(isCmp2) {
            // CMP2/CHK2: single-cycle bounds compare. Write the CCR RMW {oldN,Z,oldV,C}
            // (no int dst). CHK2 out-of-bounds (c2Trap) -> EuFault vec6 (still writing
            // the CCR so the stacked frame's flags match Musashi); CMP2 always completes.
            when(c2Trap) { captureFault(U(6, 8 bits), c2Nzvc, True) }
              .otherwise { captureComplete(B(0, 32 bits), c2Nzvc, True, False) }
            s1Valid := False
          } elsewhen(isDivRem) {
            // Trailing remainder-move: write the latched remainder to Dr. Task #168
            // (ported-tests triage, divl_sz1_overflow HANG) closes the residual gap
            // documented here since task #149: on a DIV overflow (V=1, Dq/Dr BOTH
            // architecturally unchanged), this µop now writes s1A (Dr's own OLD value,
            // read via the new real srcA=divlDr operand in MicroOpAssembler.scala)
            // THROUGH to its freshly-renamed pdst -- exactly mirroring the DIV µop's
            // own s1A/Dq overflow fix (task #149) -- instead of skipping the write
            // (writeInt=False), which left the pdst permanently not-ready and
            // deadlocked any later reader of Dr. writeInt is now unconditionally True;
            // only the DATA differs (old Dr on overflow, the fresh remainder otherwise).
            captureComplete(Mux(ovLatch, s1A, remLatch), B(0, 4 bits), False, True)
            s1Valid := False
          } elsewhen(isDiv) {
            when(divisor32 === 0) {
              // DIV0 -> euFault vector 5, no write, no flag change (Musashi leaves CCR).
              captureFault(U(5, 8 bits), B(0, 4 bits), False)
              s1Valid := False
            } otherwise {
              divUnit.io.start := True
              busy := True
              s1Valid := False        // consumed into DIVING (its operands are latched)
              goto(DIVING)
            }
          } otherwise {
            // defensive complete (unexpected CPLX µop) so the pipe can't hang.
            captureComplete(B(0, 32 bits), B(0, 4 bits), False, False)
            s1Valid := False
          }
        }
      }

      DIVING.whenIsActive {
        busy := True
        when(divUnit.io.done) {
          // Latch the remainder (signed) + overflow for the trailing DIVREM µop. For
          // the .W form the remainder is packed into the quotient result (no DIVREM);
          // for the .L forms the DIVREM writes Dr from this latch.
          remLatch := resR.asBits
          ovLatch  := divUnit.io.overflow
          // Overflow -> V=1, Dq ARCHITECTURALLY unchanged. Normal -> write quotient + N/Z (V=0).
          // On overflow this WRITES s1A (Dq's own OLD/pre-divide value, already read as the
          // dividend source -- same register, so s1A IS "the old Dq") back through to the
          // µop's freshly-renamed pdst, rather than skipping the write: in a renamed OoO
          // pipe, "leave the destination unchanged" for an instruction whose decode-time
          // pdstValid is True still allocates a NEW physical register for the old arch reg,
          // so *something* must write it or that pdst's scoreboard/ready bit never sets and
          // any consumer waiting on it stalls forever (found via ported-tests triage,
          // divl_basic.s: a genuine ROB-head deadlock, not a divider hang -- DivCore is a
          // fixed 64-cycle iterator that always completes). writeInt=True here (was False)
          // fixes that hazard generally, independent of the divide-by-(-1) erratum above.
          when(divUnit.io.overflow) {
            captureComplete(s1A, divNzvcOver, True, True)
          } otherwise {
            captureComplete(divResult, divNzvcNormal, u1.writesNzvc, True)
          }
          busy    := False
          s1Valid := False
          goto(IDLE)
        }
      }

    }

    // Atomic three-source completion arbitration.  The legacy result and both
    // FIFOs hold their payloads until selected, so the Flow-only ROB lane cannot
    // lose a same-cycle DIV/MUL or MUL/MULHI collision.  Legacy wins to bound an
    // older divider; a ready MULHI wins next; pure MUL drains every cycle.
    mulResultQ.io.pop.ready := False
    mulHiPendingQ.io.pop.ready := False
    val arbCollisionObs = legacyValid && mulResultQ.io.pop.valid
    arbCollisionObs.simPublic()

    def captureArb(result: CplxResult): Unit = {
      compValid     := True
      compRobId     := result.robId
      compData      := result.data
      compPdst      := result.pdst
      compPdstValid := result.pdstValid
      compDstArch   := result.dstArch
      compNzvc      := result.nzvc
      compNzvcWrite := result.nzvcWrite
      compNzvcDst   := result.pNzvcDst
      compFault     := result.fault
      compFaultVec  := result.faultVec
      compDivRem    := result.crackTail
      compFlushed   := result.flushed
    }

    when(!flushSig) {
      when(legacyValid) {
        captureArb(legacyResult)
        legacyValid := False
      } elsewhen(mulHiHeadReady) {
        compValid     := True
        compRobId     := mulHiHead.robId
        compData      := mulHiData
        compPdst      := mulHiHead.pdst
        compPdstValid := True
        compDstArch   := mulHiHead.dstArch
        compNzvc      := 0
        compNzvcWrite := False
        compNzvcDst   := 0
        compFault     := False
        compFaultVec  := 0
        compDivRem    := True
        compFlushed   := False
        mulHiPendingQ.io.pop.ready := True
        mulHiValid(mulHiHead.robId) := False
      } elsewhen(mulResultQ.io.pop.valid) {
        captureArb(mulResultQ.io.pop.payload)
        mulResultQ.io.pop.ready := True
        when(mulResultQ.io.pop.payload.mul64) {
          val tailRobId = (mulResultQ.io.pop.payload.robId + 1).resized
          mulHiMem.write(tailRobId, mulResultQ.io.pop.payload.mulHigh)
          mulHiValid(tailRobId) := True
        }
      }
    }
    when(flushSig) {
      legacyValid := False
      compValid := False
      mulHiValid := 0
    }

    // ---- drive ports from the registered completion stage ----
    // compFlushed suppresses a WRONG-PATH completion (a multi-cycle op flushed in
    // flight): no completion / PRF write / wakeup / euFault — its robId may already
    // be reused by a correct-path op.
    val compLive = compValid && !compFlushed && !flushSig
    completionPort.valid   := compLive
    completionPort.payload := compRobId
    intW.valid     := compLive && compPdstValid && !compFault
    intW.address   := compPdst
    intW.data      := compData
    intByp.valid   := intW.valid
    intByp.address := compPdst
    intByp.data    := compData
    nzvcW.valid     := compLive && compNzvcWrite && !compFault
    nzvcW.address   := compNzvcDst
    nzvcW.data      := compNzvc
    nzvcByp.valid   := nzvcW.valid
    nzvcByp.address := nzvcW.address
    nzvcByp.data    := nzvcW.data
    // Dynamic-completion wakeup: a completing CPLX op that produced a physreg.
    wakeupPort.valid   := compLive && compPdstValid && !compFault
    wakeupPort.payload := compPdst
    // Dynamic-completion NZVC wakeup (task #167): a completing CPLX op that wrote flags
    // (DIV/MUL/CHK/CMP2/CHK2 — NOT the flagless DIVREM/MULHI crack tail). Gated the same
    // way as the int wakeup (!compFault: a faulting µop's rename rolls back, so its pdst
    // is never really live for a surviving consumer) so the two wakeups stay symmetric.
    wakeupNzvcPort.valid   := compLive && compNzvcWrite && !compFault
    wakeupNzvcPort.payload := compNzvcDst
    // Generalized euFault (CHK vec6 / DIV0 vec5). faultAddr is unused for these
    // (only the branch EU's address-error case, vector 3, reads entryFaultAddr in
    // the is2 frame path — task #189); default 0.
    euFaultPort.valid            := compLive && compFault
    euFaultPort.payload.robId    := compRobId
    euFaultPort.payload.vector   := compFaultVec
    euFaultPort.payload.faultAddr:= U(0, 32 bits)

    // ---- sim-only whitebox ----
    val wbObs = WbObs()
    wbObs.valid     := compLive
    wbObs.robId     := compRobId
    wbObs.dstArch   := compDstArch
    wbObs.result    := compData
    wbObs.intWrite  := compPdstValid && !compFault
    wbObs.nzvc      := compNzvc
    // CHK sets N even as it traps -> its NZVC must reach the ROB's committed-CCR fold
    // (ccrCompletion) so the stacked frame's CCR matches Musashi. compNzvcWrite is
    // True for a CHK fault (and False for DIV0/normal-no-flag), so the wbObs flag is
    // compNzvcWrite directly (NOT masked by !compFault — unlike the PRF write, which
    // is masked since the faulting µop's rename rolls back).
    wbObs.nzvcWrite := compNzvcWrite
    wbObs.x         := False
    wbObs.xWrite    := False
    wbObs.divRem    := compDivRem
    wbObs.keepCommit := False
    wbObs.simPublic()

    // ---- ccrObs (task #176) ----
    // DivEu's `wbObs` above is ALREADY driven straight from `compLive` — the same
    // signal that drives `completionPort.valid` — so it carries no extra delay (unlike
    // the ALU EU's sim-delayed `wbObs`). A plain alias, so the ROB wiring can uniformly
    // source the real `ccrCompletion` from `.logic.ccrObs` across all 4 EUs.
    val ccrObs = wbObs
    // CHK N flag observation (sim-only) for directed tests.
    val chkNObs = Bool(); chkNObs := chkN && isChk && s1Valid; chkNObs.simPublic()
  }
}
