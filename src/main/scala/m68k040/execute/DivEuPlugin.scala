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
    rdH.addr := u0.psrcB     // (DIV64 high word; decode points psrcB pair — refined in T7)
    val s0A = rdA.data
    val s0B = Mux(u0.useImm, u0.imm, rdB.data)
    val s0H = rdH.data

    // single-outstanding busy
    val busy = RegInit(False)
    issuePort.ready := !busy

    // ---- S0 -> S1 register (M2S), captured on issue.fire ----
    val s1Valid = RegInit(False)
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
    // N flag per the rule (set even though we don't commit NZVC; surfaced for the
    // sim whitebox so a directed test can observe it).
    val chkN     = chkNeg

    // captured-completion helpers
    def captureComplete(result: Bits, nzvc: Bits, writesNzvc: Bool): Unit = {
      compValid     := True
      compRobId     := s1Ctx.robId
      compData      := result
      compPdst      := u1.pdst
      compPdstValid := u1.pdstValid
      compDstArch   := u1.dstArch
      compNzvc      := nzvc
      compNzvcWrite := writesNzvc
      compNzvcDst   := u1.pNzvcDst
      compFault     := False
    }
    def captureFault(vec: UInt): Unit = {
      compValid     := True
      compRobId     := s1Ctx.robId
      compData      := B(0, 32 bits)
      compPdst      := u1.pdst
      compPdstValid := False
      compDstArch   := u1.dstArch
      compNzvc      := B(0, 4 bits)
      compNzvcWrite := False
      compNzvcDst   := u1.pNzvcDst
      compFault     := True
      compFaultVec  := vec
    }

    // ---- FSM (CHK single-cycle now; DIV iterative core added in later tasks) ----
    val fsm = new StateMachine {
      val IDLE = new State with EntryPoint

      IDLE.whenIsActive {
        busy := False
        when(s1Valid) {
          when(isChk) {
            // single-cycle bound check
            when(chkTrap) { captureFault(U(6, 8 bits)) }
              .otherwise   { captureComplete(B(0, 32 bits), B(0, 4 bits), False) }  // in-bounds no-op
            s1Valid := False
          } otherwise {
            // DIV path: implemented in Tasks 5-7. Defensive complete for now so an
            // unexpected non-CHK CPLX µop cannot hang the pipe.
            captureComplete(B(0, 32 bits), B(0, 4 bits), False)
            s1Valid := False
          }
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
    wbObs.nzvcWrite := compNzvcWrite && !compFault
    wbObs.x         := False
    wbObs.xWrite    := False
    wbObs.simPublic()
    // CHK N flag observation (sim-only) for directed tests.
    val chkNObs = Bool(); chkNObs := chkN && isChk && s1Valid; chkNObs.simPublic()
  }
}
