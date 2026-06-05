package m68k040.execute

import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{NzvcRegFileService, RegFileReadPort}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Branch completion: robId + whether it mispredicted + the resolved next PC. */
case class BranchCompletion() extends Bundle {
  val robId      = UInt(6 bits)
  val mispredict = Bool()
  val nextPc     = UInt(32 bits)
}

/** TRAPV fault completion: the branch EU drives this (vector 7 implied) when a
  * TRAPV trap-check µop sees V=1 at execute. The ROB marks the entry faulted +
  * vector 7 + faultPc (mirrors lsFaultCompletion). */
case class TrapvFault() extends Bundle {
  val robId   = UInt(6 bits)
  val faultPc = UInt(32 bits)
}

/** Sim-only whitebox observation (branch writes no reg; CCR unchanged). */
case class BrWbObs() extends Bundle {
  val valid  = Bool()
  val robId  = UInt(6 bits)
  val nextPc = UInt(32 bits)
}

/** Plain-wire ports: producer (IQ/test) drives `issue`; consumer (ROB/test) reads `completion`. */
trait BranchEuService {
  def issue: Stream[IqContext]
  def completion: Flow[BranchCompletion]
  /** TRAPV execute-time conditional fault (vector 7): fires when a TRAPV trap-check
    * µop sees V=1. The ROB consumes it like lsFaultCompletion. */
  def trapvFault: Flow[TrapvFault]
}

/** Latency-1 branch EU. S0 reads NZVC; S1 evaluates the 68k condition, computes
  * target = pc+2+disp, taken, nextPc, mispredict (=taken; no predictor yet). */
class BranchEuPlugin extends FiberPlugin with BranchEuService {
  var issuePort: Stream[IqContext] = null
  var completionPort: Flow[BranchCompletion] = null
  var trapvFaultPort: Flow[TrapvFault] = null
  var nzRd: RegFileReadPort = null
  override def issue = issuePort
  override def completion = completionPort
  override def trapvFault = trapvFaultPort

  during setup {
    issuePort = Stream(IqContext())
    completionPort = Flow(BranchCompletion())
    trapvFaultPort = Flow(TrapvFault())
    nzRd = host[NzvcRegFileService].newRead(forceNoBypass = false)
  }

  val logic = during build new Area {
    // ---- S0: read NZVC source ----
    issuePort.ready := True              // fixed-latency EU never structurally stalls
    nzRd.addr := issuePort.payload.uop.pNzvcSrc

    // ---- S0 -> S1 register (M2S) ----
    val s1Valid = RegNext(issuePort.valid) init False
    val s1Ctx   = RegNext(issuePort.payload)
    val s1Nzvc  = RegNext(nzRd.data)              // {N(3),Z(2),V(1),C(0)}
    val u1 = s1Ctx.uop

    // ---- S1: condition eval (cond[3:0]) ----
    val n = s1Nzvc(3); val z = s1Nzvc(2); val v = s1Nzvc(1); val c = s1Nzvc(0)
    val taken = u1.cond.asUInt.mux(
      0  -> True,                  // T   (BRA / always)
      1  -> False,                 // F   (BSR-style false; no branch)
      2  -> (!c && !z),            // HI
      3  -> (c || z),              // LS
      4  -> !c,                    // CC / HS
      5  -> c,                     // CS / LO
      6  -> !z,                    // NE
      7  -> z,                     // EQ
      8  -> !v,                    // VC
      9  -> v,                     // VS
      10 -> !n,                    // PL
      11 -> n,                     // MI
      12 -> (n === v),             // GE
      13 -> (n =/= v),             // LT
      14 -> (!z && (n === v)),     // GT
      15 -> (z || (n =/= v))       // LE
    )
    val target = (u1.pc + 2 + u1.branchDisp.asUInt)
    val branchNextPc = Mux(taken, target, u1.pc + 2)
    // TRAPV is NOT a branch: it never redirects, and its commit nextPc is the µop's
    // own nextPc (= pc+2). A V=1 TRAPV faults via trapvFault (the ROB redirects to
    // the vector); a V=0 TRAPV retires straight through.
    val isTrapv = u1.isTrapv
    val nextPc  = Mux(isTrapv, u1.nextPc, branchNextPc)

    // ---- S1: completion (entry completes either way so it can retire) ----
    completionPort.valid              := s1Valid
    completionPort.payload.robId      := s1Ctx.robId
    completionPort.payload.mispredict := s1Valid && taken && !isTrapv  // TRAPV never redirects
    completionPort.payload.nextPc     := nextPc

    // ---- S1: TRAPV execute-time conditional fault (vector 7 if V=1) ----
    trapvFaultPort.valid         := s1Valid && isTrapv && v
    trapvFaultPort.payload.robId := s1Ctx.robId
    trapvFaultPort.payload.faultPc := u1.faultPc

    // ---- S1: sim-only whitebox (branch: no reg write, CCR unchanged) ----
    val wbObs = BrWbObs()
    wbObs.valid  := RegNext(s1Valid) init False
    wbObs.robId  := RegNext(s1Ctx.robId)
    wbObs.nextPc := RegNext(nextPc)
    wbObs.simPublic()
  }
}
