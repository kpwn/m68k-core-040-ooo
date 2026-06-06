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

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort
  override def wakeup: Flow[UInt]       = wakeupPort
  override def euFault: Flow[EuFault]   = euFaultPort

  during setup {
    issuePort      = Stream(IqContext())
    completionPort = Flow(UInt(6 bits))
    wakeupPort     = Flow(UInt(6 bits))
    euFaultPort    = Flow(EuFault()); euFaultPort.simPublic()
    val irf = host[IntRegFileService]
    rdA = irf.newRead()   // CHK: Dn ; DIV: dividend low (Dq)
    rdB = irf.newRead()   // CHK: bound ; DIV: divisor (when register)
    rdH = irf.newRead()   // DIV64: dividend high (Dr)
    intW = irf.newWrite(latency = 1); intByp = irf.newBypass()
    val nz = host[NzvcRegFileService]
    nzvcW = nz.newWrite(latency = 1); nzvcByp = nz.newBypass()
  }

  val logic = during build new Area {
    // ---- S0: read operands ----
    val u0 = issuePort.payload.uop
    rdA.addr := u0.psrcA
    rdB.addr := u0.psrcB
    rdH.addr := u0.psrcC     // DIV.L 64/32 dividend HIGH word (Dr) via the 3rd source
    val s0A = rdA.data
    val s0B = Mux(u0.useImm, u0.imm, rdB.data)
    val s0H = rdH.data

    // single-outstanding busy. A µop occupying s1 (not yet consumed) ALSO blocks a new
    // issue — otherwise a 2nd µop (e.g. the cracked DIVREM following its DIV) would
    // fire the cycle after the first while `busy` is still being set, overwriting s1.
    val busy = RegInit(False)
    val s1Valid = RegInit(False)
    issuePort.ready := !busy && !s1Valid

    // ---- S0 -> S1 register (M2S), captured on issue.fire ----
    val s1Ctx   = Reg(IqContext())
    val s1A     = Reg(Bits(32 bits))
    val s1B     = Reg(Bits(32 bits))
    val s1H     = Reg(Bits(32 bits))
    val u1 = s1Ctx.uop

    // default: clear s1Valid unless held by busy (set on capture below)
    when(issuePort.fire) {
      s1Valid := True
      s1Ctx   := issuePort.payload
      s1A     := s0A
      s1B     := s0B
      s1H     := s0H
    } otherwise {
      when(!busy) { s1Valid := False }
    }

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

    compValid     := False
    compNzvcWrite := False
    compFault     := False

    // ---- CHK compare (single-cycle) ----
    // CHK.W compares the low 16 bits (sign-extended); CHK.L the full 32. N=1 if
    // Dn<0, N=0 if Dn>bound; trap (vector 6) iff Dn<0 || Dn>bound. CHK writes no
    // register and (per Musashi) sets N as above; Z/V/C are left to match Musashi
    // (validated in lock-step). We do NOT write NZVC here (CHK leaves CCR per the
    // 68k "undefined except N" rule; the lock-step reconstructs CCR and the handler
    // never depends on the unchanged bits — the directed ChkSpec checks N + trap).
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
    // Z/V/C = 0 (matches Musashi's CHK CCR for both trap and no-trap paths). CHK
    // ALWAYS commits this NZVC (even on the trap path, so the stacked CCR's N matches).
    val chkN     = chkNeg
    val chkNzvc  = (chkN ## False ## False ## False).asBits   // N Z(0) V(0) C(0)

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
    val divNzvcOver   = (False ## False ## True ## False).asBits     // overflow: V=1
    // On overflow: V=1, NO result write (Dn unchanged). On DIV0: euFault vec5, no write.

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
    // N/Z for the .W/.L32 forms come from the low 32-bit product (V handled in T4).
    val mulLoN = mulLo(31)
    val mulLoZ = mulLo === 0
    val mulNzvcW = (mulLoN ## mulLoZ ## False ## False).asBits   // .W: N Z V(0) C(0)

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
          } elsewhen(isDivRem) {
            // Trailing remainder-move: write the latched remainder to Dr. If the
            // preceding DIV overflowed, NO dest was written -> skip this write too
            // (writeInt=!ovLatch). Sets no flags (the DIV µop already set NZVC).
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
          // Overflow -> V=1, NO register write. Normal -> write quotient + N/Z (V=0).
          when(divUnit.io.overflow) {
            captureComplete(B(0, 32 bits), divNzvcOver, True, False)
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
    completionPort.valid   := compValid
    completionPort.payload := compRobId
    intW.valid     := compValid && compPdstValid && !compFault
    intW.address   := compPdst
    intW.data      := compData
    intByp.valid   := intW.valid
    intByp.address := compPdst
    intByp.data    := compData
    nzvcW.valid     := compValid && compNzvcWrite && !compFault
    nzvcW.address   := compNzvcDst
    nzvcW.data      := compNzvc
    nzvcByp.valid   := nzvcW.valid
    nzvcByp.address := nzvcW.address
    nzvcByp.data    := nzvcW.data
    // Dynamic-completion wakeup: a completing DIV that produced a physreg.
    wakeupPort.valid   := compValid && compPdstValid && !compFault
    wakeupPort.payload := compPdst
    // Generalized euFault (CHK vec6 / DIV0 vec5).
    euFaultPort.valid         := compValid && compFault
    euFaultPort.payload.robId := compRobId
    euFaultPort.payload.vector:= compFaultVec

    // ---- sim-only whitebox ----
    val wbObs = WbObs()
    wbObs.valid     := compValid
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
    wbObs.simPublic()
    // CHK N flag observation (sim-only) for directed tests.
    val chkNObs = Bool(); chkNObs := chkN && isChk && s1Valid; chkNObs.simPublic()
  }
}
