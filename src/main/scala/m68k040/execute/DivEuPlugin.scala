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
  def wakeup: Flow[UInt]       // pdst of a completing DIV (dynamic-completion wakeup)
  /** Execute-time conditional fault (CHK -> vector 6, DIV0 -> vector 5). The ROB
    * consumes it like the branch EU's trapvFault (generalized euFault). */
  def euFault: Flow[EuFault]
  /** Mispredict/exception squash (the RedirectService doFlush pulse). A MULTI-CYCLE
    * op (DIV/MUL) in flight when a flush hits is WRONG-PATH: its late completion
    * would land after the ROB reuses its robId. We abort the FSM + suppress the
    * stale completion/writeback so it cannot pollute the reused entry. (The ROB also
    * filters wrong-path completions by robId; this additionally protects against the
    * multi-cycle straddle where completion lands after reuse.) */
  def cplxFlush: Bool
}

/** CPLX execution unit: CHK (bound-check trap, single-cycle) + multi-cycle DIVU/DIVS.
  *
  * Single-outstanding, busy-gated, with a dynamic-completion wakeup (mirrors
  * LsEuPlugin): the EU deasserts issue.ready while a divide iterates, and broadcasts
  * the producing pdst the cycle the result lands. CHK is single-cycle (no iteration);
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
  var euFaultPort: Flow[EuFault]   = null
  var rdA, rdB, rdH: RegFileReadPort = null
  var intW: RegFileWritePort = null;  var intByp: RegFileBypassPort = null
  var nzvcW: RegFileWritePort = null; var nzvcByp: RegFileBypassPort = null
  var nzvcRd: RegFileReadPort = null  // CMP2/CHK2 old-NZVC read (preserve N/V in the RMW)
  var flushSig: Bool = null

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort
  override def wakeup: Flow[UInt]       = wakeupPort
  override def euFault: Flow[EuFault]   = euFaultPort
  override def cplxFlush: Bool          = flushSig

  during setup {
    issuePort      = Stream(IqContext())
    completionPort = Flow(UInt(6 bits))
    wakeupPort     = Flow(UInt(6 bits))
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

    // single-outstanding busy. A µop occupying s1 (not yet consumed) ALSO blocks a new
    // issue — otherwise a 2nd µop (e.g. the cracked DIVREM following its DIV) would
    // fire the cycle after the first while `busy` is still being set, overwriting s1.
    val busy = RegInit(False)
    val s1Valid = RegInit(False)
    issuePort.ready := !busy && !s1Valid

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

    // default: clear s1Valid unless held by busy (set on capture below)
    when(issuePort.fire) {
      s1Valid := True
      s1Ctx   := issuePort.payload
      s1A     := s0A
      s1B     := s0B
      s1H     := s0H
      s1Nzvc  := s0Nzvc
    } otherwise {
      when(!busy) { s1Valid := False }
    }

    // Mispredict/exception squash: a 1-cycle doFlush pulse. Latch it so the eventual
    // completion of a multi-cycle op (DIV/MUL) that was IN FLIGHT at the flush is
    // suppressed — its robId may be reused by a correct-path op before the (late)
    // completion lands. `flushed` is set on any flush while busy/s1Valid (an in-flight
    // op); it is captured into compFlushed at the op's completion and cleared then.
    val flushed = RegInit(False)
    when(flushSig && (busy || s1Valid)) { flushed := True }
    // A flush also drops a not-yet-launched s1 op (it never enters DIVING/MULING).
    when(flushSig) { s1Valid := False }

    // ─────────────────────────────────────────────────────────────────────────
    // Registered COMPLETION / WRITEBACK stage (mirrors LsEu): the decision (CHK
    // compare / DIV result) is captured into comp* registers and DRIVES the
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
      compValid     := True
      compRobId     := s1Ctx.robId
      compData      := result
      compPdst      := u1.pdst
      compPdstValid := u1.pdstValid && writeInt
      compDstArch   := u1.dstArch
      compNzvc      := nzvc
      compNzvcWrite := writesNzvc
      compNzvcDst   := u1.pNzvcDst
      compFault     := False
      // The trailing crack µop (DIVREM or MULHI) is coalesced into the preceding
      // op's oracle step (its own commit is dropped, the PRF write still lands).
      compDivRem    := u1.divIsRem || (u1.op === DecOp.MULHI)
      // Was this op (or its in-flight predecessor) flushed? If so the completion is
      // wrong-path and must not drive any port (its robId may have been reused).
      compFlushed   := flushed || flushSig
      flushed       := False                 // captured -> consume the latch
    }
    def captureFault(vec: UInt, nzvc: Bits, writesNzvc: Bool): Unit = {
      compValid     := True
      compRobId     := s1Ctx.robId
      compData      := B(0, 32 bits)
      compPdst      := u1.pdst
      compPdstValid := False
      compDstArch   := u1.dstArch
      compNzvc      := nzvc
      compNzvcWrite := writesNzvc      // CHK still sets N even when it traps (Musashi)
      compNzvcDst   := u1.pNzvcDst
      compFault     := True
      compFaultVec  := vec
      compDivRem    := False
      compFlushed   := flushed || flushSig
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
    // MUL integration (MULU/MULS): the registered DSP-mappable MulCore. MUL is the
    // SAME CPLX EU (folded in, not a separate plugin) — it reuses this FSM, the
    // busy-gated single-outstanding issue, the completion/wakeup ports, and the
    // crack-latch mechanism (MULHI mirrors DIVREM). 2 source operands only (no psrcC).
    //   .W   : 16x16 -> Dn[31:0]. Operands = s1A[15:0] / s1B[15:0], s/z-ext to 32.
    //   .L32 : 32x32 -> Dl[31:0] + V(overflow). Operands = s1A / s1B (full 32).  (T4)
    //   .L64 : 32x32 -> Dh:Dl. MUL writes Dl (low), MULHI writes Dh (latched high). (T5)
    val isMul    = u1.op === DecOp.MUL
    val isMulHi  = u1.op === DecOp.MULHI
    val mulSigned = u1.divSigned          // reused as the MULS marker
    // Operand A/B for MulCore: .W extends the low 16 bits; .L uses the full 32.
    val mulA = Bits(32 bits)
    val mulB = Bits(32 bits)
    when(u1.size === Size.WORD) {
      mulA := Mux(mulSigned && s1A(15), B(0xFFFF, 16 bits), B(0, 16 bits)) ## s1A(15 downto 0)
      mulB := Mux(mulSigned && s1B(15), B(0xFFFF, 16 bits), B(0, 16 bits)) ## s1B(15 downto 0)
    } otherwise {
      mulA := s1A
      mulB := s1B
    }

    val mulCore = new MulCore
    mulCore.io.start  := False
    mulCore.io.a      := mulA
    mulCore.io.b      := mulB
    mulCore.io.signed := mulSigned

    // The full 64-bit product is split lo/hi by MulCore. For .W/.L32 the dest is the
    // low 32; .L64 writes lo -> Dl + (latched) hi -> Dh. The high product is LATCHED
    // at done for the trailing MULHI crack (no re-multiply), mirroring remLatch.
    val mulLo = mulCore.io.prodLo
    val mulHi = mulCore.io.prodHi
    val mulHiLatch = Reg(Bits(32 bits))
    // N/Z for the .W/.L32 forms come from the low 32-bit product.
    val mulLoN = mulLo(31)
    val mulLoZ = mulLo === 0
    // .L32 overflow: the full 64-bit product is not representable in 32 bits. Signed:
    // high32 != the sign-extension of bit31 (i.e. != all-ones when lo<0, != 0 when
    // lo>=0). Unsigned: high32 != 0. (.W can't overflow; .L64 V=0.)
    val mulSext = Mux(mulLoN, B(0xFFFFFFFFL, 32 bits), B(0, 32 bits))
    val mulOverflow = Mux(mulSigned, mulHi =/= mulSext, mulHi =/= B(0, 32 bits))
    val mulV = (u1.size =/= Size.WORD) && !u1.div64 && mulOverflow   // .L32 only
    // .L64: N/Z come from the FULL 64-bit product (N=hi[31], Z=(hi|lo==0)); V=0.
    // .W/.L32: N/Z from the low 32-bit product; V=overflow (.L32 only).
    val mulN = Mux(u1.div64, mulHi(31), mulLoN)
    val mulZ = Mux(u1.div64, (mulHi | mulLo) === 0, mulLoZ)
    val mulNzvcW = (mulN ## mulZ ## mulV ## False).asBits   // N Z V C(0)

    // ---- FSM ----
    val fsm = new StateMachine {
      val IDLE  = new State with EntryPoint
      val DIVING = new State    // DivUnit iterating
      val MULING = new State    // MulCore registering the product

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
            // Trailing remainder-move: write the latched remainder to Dr. If the
            // preceding DIV overflowed, NO dest was written -> skip this write too
            // (writeInt=!ovLatch). Sets no flags (the DIV µop already set NZVC).
            // KNOWN RESIDUAL GAP (ported-tests triage, divl_basic.s): unlike the DIV
            // µop's own overflow branch (fixed above to write s1A = Dq's old value
            // through, so its pdst still gets marked ready), this DIVREM µop has NO
            // real source operand (srcAValid=False -- it has no register dependency,
            // only an implicit ordering one on the preceding DIV) to copy Dr's old
            // value from, so on ovLatch=True its pdst is left permanently not-ready
            // the SAME way the DIV's used to be -- a latent scoreboard-deadlock hazard
            // for any FUTURE overflow case the divide-by-(-1) erratum (DivUnit.scala)
            // doesn't also suppress. Not currently reachable by any known test (the
            // only exercised overflow case, INT_MIN/-1, is now suppressed by the
            // erratum fix so ovLatch is False for it), so left unfixed here rather
            // than rushing a new real Dr-source operand into the crack. Proper fix:
            // give divremUop a real srcA = divlDr (old Dr value) and write it through
            // here on overflow, mirroring the DIV µop's s1A fix above.
            captureComplete(remLatch, B(0, 4 bits), False, !ovLatch)
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
          } elsewhen(isMulHi) {
            // Trailing high-product move (.L64): write the latched high product to Dh.
            // MUL can't overflow into a no-write (.L64 V=0), so always write. No flags.
            captureComplete(mulHiLatch, B(0, 4 bits), False, True)
            s1Valid := False
          } elsewhen(isMul) {
            // launch the registered DSP multiply (1-cycle); hold busy until done.
            mulCore.io.start := True
            busy := True
            s1Valid := False          // consumed into MULING (operands latched in MulCore)
            goto(MULING)
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

      MULING.whenIsActive {
        busy := True
        when(mulCore.io.done) {
          // Latch the high product for a trailing MULHI (.L64). The .W form writes the
          // low 32-bit product with N/Z from it (V=0, C=0). (.L32 V + .L64 N/Z = T4/T5.)
          mulHiLatch := mulHi
          captureComplete(mulLo, mulNzvcW, u1.writesNzvc, True)
          busy    := False
          s1Valid := False
          goto(IDLE)
        }
      }
    }

    // ---- drive ports from the registered completion stage ----
    // compFlushed suppresses a WRONG-PATH completion (a multi-cycle op flushed in
    // flight): no completion / PRF write / wakeup / euFault — its robId may already
    // be reused by a correct-path op.
    val compLive = compValid && !compFlushed
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
    // Dynamic-completion wakeup: a completing DIV that produced a physreg.
    wakeupPort.valid   := compLive && compPdstValid && !compFault
    wakeupPort.payload := compPdst
    // Generalized euFault (CHK vec6 / DIV0 vec5).
    euFaultPort.valid         := compLive && compFault
    euFaultPort.payload.robId := compRobId
    euFaultPort.payload.vector:= compFaultVec

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
    // CHK N flag observation (sim-only) for directed tests.
    val chkNObs = Bool(); chkNObs := chkN && isChk && s1Valid; chkNObs.simPublic()
  }
}
