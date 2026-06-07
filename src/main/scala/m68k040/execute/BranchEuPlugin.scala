package m68k040.execute

import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService, RegFileReadPort, RegFileWritePort, RegFileBypassPort}
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

/** Execute-time conditional fault completion (generalized from the original TRAPV-
  * only fault). An EU drives this when an execute-time check raises a synchronous
  * fault that carries an exception VECTOR: TRAPV (vector 7, branch EU), CHK (vector
  * 6, div EU), DIV0 (vector 5, div EU). The ROB marks the entry faulted + the
  * carried vector. The stacked PC (= nextPc for these group-2 traps) and the PPC
  * (= the instruction PC) are already captured per-entry at alloc (faultPcStore /
  * pcStore), so only {robId, vector} are needed here (cf. lsFaultCompletion, which
  * must also carry the execute-computed EA). All three are format-$2 group-2 traps. */
case class EuFault() extends Bundle {
  val robId  = UInt(6 bits)
  val vector = UInt(8 bits)
}

/** Sim-only whitebox observation. A plain branch writes no reg; an RTS/RTR ibranch
  * writes A7 (the postincremented SP) — `anWrite`/`anData`/`anArch` report it so the
  * lock-step whitebox reconstructs the A7 update (joined by robId). */
case class BrWbObs() extends Bundle {
  val valid   = Bool()
  val robId   = UInt(6 bits)
  val nextPc  = UInt(32 bits)
  val anWrite = Bool()
  val anData  = Bits(32 bits)
  val anArch  = UInt(5 bits)
}

/** Plain-wire ports: producer (IQ/test) drives `issue`; consumer (ROB/test) reads `completion`. */
trait BranchEuService {
  def issue: Stream[IqContext]
  def completion: Flow[BranchCompletion]
  /** TRAPV execute-time conditional fault (vector 7): fires when a TRAPV trap-check
    * µop sees V=1. The ROB consumes it like lsFaultCompletion. Generalized to carry
    * the vector so the ROB's euFault handling is shared with CHK/DIV0. */
  def trapvFault: Flow[EuFault]
}

/** Latency-1 branch EU. S0 reads NZVC; S1 evaluates the 68k condition, computes
  * target = pc+2+disp, taken, nextPc, mispredict (=taken; no predictor yet). */
class BranchEuPlugin extends FiberPlugin with BranchEuService {
  var issuePort: Stream[IqContext] = null
  var completionPort: Flow[BranchCompletion] = null
  var trapvFaultPort: Flow[EuFault] = null
  var nzRd: RegFileReadPort = null
  // Int read port for an INDIRECT branch (ibranch): reads the target base operand
  // (psrcA) — the EA base An for JSR/JMP, or the popped target value for RTS/RTR.
  var tgtRd: RegFileReadPort = null
  // Int read port for the An POSTINCREMENT base (psrcB = the pre-pop A7) + an int
  // write port for the new A7 (RTS/RTR fold the postinc into the trailing ibranch).
  var anRd: RegFileReadPort = null
  var anW:  RegFileWritePort = null
  var anByp: RegFileBypassPort = null
  override def issue = issuePort
  override def completion = completionPort
  override def trapvFault = trapvFaultPort

  during setup {
    issuePort = Stream(IqContext())
    completionPort = Flow(BranchCompletion())
    trapvFaultPort = Flow(EuFault())
    nzRd = host[NzvcRegFileService].newRead(forceNoBypass = false)
    tgtRd = host[IntRegFileService].newRead(forceNoBypass = false)
    anRd  = host[IntRegFileService].newRead(forceNoBypass = false)
    anW   = host[IntRegFileService].newWrite(latency = 1)
    anByp = host[IntRegFileService].newBypass()
  }

  val logic = during build new Area {
    // ---- S0: read NZVC source + (ibranch) the int target base ----
    issuePort.ready := True              // fixed-latency EU never structurally stalls
    nzRd.addr  := issuePort.payload.uop.pNzvcSrc
    tgtRd.addr := issuePort.payload.uop.psrcA
    anRd.addr  := issuePort.payload.uop.psrcB     // pre-pop A7 base (RTS/RTR postinc)

    // ---- S0 -> S1 register (M2S) ----
    val s1Valid = RegNext(issuePort.valid) init False
    val s1Ctx   = RegNext(issuePort.payload)
    val s1Nzvc  = RegNext(nzRd.data)              // {N(3),Z(2),V(1),C(0)}
    val s1AnBase= RegNext(anRd.data.asUInt)       // pre-pop A7 (for the postinc write)
    // Registered int target base (psrcA). For an absolute / PC-folded ibranch the
    // assembler leaves psrcAValid=False (base contribution must be ZERO; the folded
    // value rides in imm), exactly as the LS EU's base-mux for absolute/PC EAs.
    val s1TgtBase = RegNext(Mux(issuePort.payload.uop.psrcAValid, tgtRd.data.asUInt, U(0, 32 bits)))
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
    // PC-relative target (Bcc/BRA/BSR). INDIRECT (ibranch) target = base + imm (a
    // tiny AGU: base = psrcA for (An)/(d16,An)/RTS-RTR-T0, 0 for absolute/PC-folded;
    // imm = displacement / folded absolute / folded PC / 0).
    val relTarget = (u1.pc + 2 + u1.branchDisp.asUInt)
    val indTarget = (s1TgtBase + u1.imm.asUInt)
    val target    = Mux(u1.ibranch, indTarget, relTarget)

    // ── Line-5 Scc / DBcc — the condition-path int writes + DBcc branch ─────────
    // Scc/DBcc read their destination Dn via psrcA (s1TgtBase = the old Dn). `taken`
    // is the evaluated condition cccc.
    val oldDn = s1TgtBase.asBits                               // old Dn (the merge source)
    // Scc: Dn[7:0] := taken ? 0xFF : 0x00 (preserve Dn[31:8]); never redirects.
    val sccByte   = Mux(taken, B"8'hFF", B"8'h00")
    val sccResult = (oldDn(31 downto 8) ## sccByte)
    // DBcc: if cond FALSE -> Dn.W -= 1 (preserve Dn[31:16]); branch if decW != -1.
    // If cond TRUE -> Dn unchanged, fall through. `taken` is the cond; DBcc decrements
    // when !taken. The Dn write is ALWAYS performed (rename allocated a new pdst), with
    // the value = unchanged Dn (cond true) or the 16-bit-decremented Dn (cond false).
    val dbDecW    = (oldDn(15 downto 0).asUInt - 1).resize(16)
    val dbDecFull = (oldDn(31 downto 16) ## dbDecW.asBits)
    val dbExpired = dbDecW === U(0xFFFF, 16 bits)              // reached -1 -> fall through
    val dbResult  = Mux(taken, oldDn, dbDecFull)              // cond true -> unchanged
    val dbBranch  = u1.isDbcc && !taken && !dbExpired         // decrement non-expired -> branch

    // An ibranch ALWAYS redirects (unconditional); a Bcc/BRA redirects iff `taken`;
    // DBcc redirects iff dbBranch; Scc never redirects.
    val redirect  = Mux(u1.isScc, False,
                    Mux(u1.isDbcc, dbBranch,
                    Mux(u1.ibranch, True, taken)))
    val nextPc    = Mux(redirect, target, u1.pc + 2)

    // ---- S1: completion (entry completes either way so it can retire) ----
    // TRAPV is decoded with cond=F (taken=False), so it naturally yields mispredict=
    // False and nextPc = pc+2 (= its own nextPc) with NO isTrapv term on these
    // outputs — keeping the completion->ROB->IQ-select arc off the critical path. A
    // V=1 TRAPV instead raises trapvFault (below); a V=0 TRAPV retires as a no-op.
    completionPort.valid              := s1Valid
    completionPort.payload.robId      := s1Ctx.robId
    completionPort.payload.mispredict := s1Valid && redirect  // TRAPV: redirect=False -> no redirect
    completionPort.payload.nextPc     := nextPc

    // ---- S1: branch-EU int write (RTS/RTR postinc A7, OR Scc/DBcc Dn write) ----
    // Three mutually-exclusive int-write sources, all to the renamed pdst:
    //  - RTS/RTR ibranch: A7 := pre-pop A7 (s1AnBase) + anInc (4=RTS, 6=RTR).
    //  - Scc:  Dn := {Dn[31:8], cond?0xFF:0x00}.
    //  - DBcc: Dn := cond ? Dn : {Dn[31:16], Dn.W-1}.
    // A plain Bcc/BRA/BSR/JMP/JSR has pdstValid=False -> no int write.
    val newAn      = (s1AnBase + u1.anInc).resize(32)
    val condIntVal = Mux(u1.isScc, sccResult, dbResult)              // Scc vs DBcc
    val condIntWr  = (u1.isScc || u1.isDbcc) && u1.pdstValid
    val anWrite    = s1Valid && ((u1.ibranch && u1.pdstValid) || condIntWr)
    val intData    = Mux(condIntWr, condIntVal, newAn.asBits)
    anW.valid     := anWrite;  anW.address := u1.pdst;  anW.data := intData
    anByp.valid   := anWrite;  anByp.address := u1.pdst; anByp.data := intData

    // ---- S1: TRAPV execute-time conditional fault (vector 7 if V=1) ----
    trapvFaultPort.valid          := s1Valid && u1.isTrapv && v
    trapvFaultPort.payload.robId  := s1Ctx.robId
    trapvFaultPort.payload.vector := U(7, 8 bits)   // TRAPV -> vector 7

    // ---- S1: sim-only whitebox. A plain branch writes NO reg; an RTS/RTR ibranch
    // writes A7 (the postincremented SP); Scc/DBcc write Dn. Report that int write +
    // value (`intData`) so the lock-step whitebox reconstructs the register update
    // (dstArch = A7 for ibranch, Dn for Scc/DBcc). ----
    val wbObs = BrWbObs()
    wbObs.valid   := RegNext(s1Valid) init False
    wbObs.robId   := RegNext(s1Ctx.robId)
    wbObs.nextPc  := RegNext(nextPc)
    wbObs.anWrite := RegNext(anWrite) init False
    wbObs.anData  := RegNext(intData)
    wbObs.anArch  := RegNext(u1.dstArch)
    wbObs.simPublic()
  }
}
