package m68k040.execute

import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{IntRegFileService, NzvcRegFileService, RegFileReadPort, RegFileWritePort, RegFileBypassPort}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Branch completion: robId + whether it mispredicted + the resolved next PC.
  * PLUS the BTB-update payload (fetch-time predictor, slice 1): the branch's own PC,
  * its resolved taken direction + target, its brType, and `isBranch` (this completion
  * is a real control-transfer branch worth learning). The ROB records these per-entry
  * at completion and drives the BTB write port at retire (no wrong-path pollution). */
case class BranchCompletion() extends Bundle {
  val robId      = UInt(6 bits)
  val mispredict = Bool()
  val nextPc     = UInt(32 bits)
  // ── BTB-update fields ──
  val isBranch   = Bool()         // a real predictable branch (Bcc/BRA/BSR/JMP/JSR/DBcc)
  val btbPc      = UInt(32 bits)  // the branch instruction's PC (BTB index/tag source)
  val btbTaken   = Bool()         // resolved taken (the bimodal counter direction)
  val btbTarget  = UInt(32 bits)  // resolved taken-target (the learned BTB target)
  val brType     = UInt(2 bits)   // 0=cond (Bcc/DBcc), 1=uncond (BRA/BSR/JMP/JSR)
  // ── gshare PHT-update fields (slice 3) ──
  val phtValid   = Bool()         // a CONDITIONAL gshare-predicted branch (train pht[phtIndex])
  val phtIndex   = UInt(11 bits)  // the carried fetch-time folded-XOR index the lookup read
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
  /** isCondTrap execute-time conditional fault (vector 7): fires when a cond-trap µop
    * (TRAPV/TRAPcc) evaluates its `cond` as taken. The ROB consumes it like
    * lsFaultCompletion. Carries the vector so ROB euFault handling is shared with CHK/DIV0. */
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
    // isCondTrap (TRAPV/TRAPcc): NEVER redirects regardless of `taken` — it is a
    // fault, not a control transfer. Gate explicitly (cond carries the real condition,
    // not the fixed F=cond1 of the old TRAPV, so the `taken` path must be suppressed).
    val redirect  = Mux(u1.isScc || u1.isCondTrap, False,
                    Mux(u1.isDbcc, dbBranch,
                    Mux(u1.ibranch, True, taken)))
    // Fall-through PC = the instruction's POST-PC (pc + length). DBcc is a 2-word
    // instruction (opword + disp16) so its not-taken/expiry PC is pc+4, NOT pc+2 —
    // use the assembler-computed u1.nextPc (also correct for a not-taken Bcc.w).
    val nextPc    = Mux(redirect, target, u1.nextPc)

    // ---- S1: branch prediction verification (BTB + bimodal, slice 1) ────────────
    // `redirect` is the ACTUAL taken/redirect condition (the old "mispredict" meaning).
    // With the fetch-time predictor, the branch EU now VERIFIES the prediction:
    //   actualTaken  = redirect (the existing taken/redirect condition)
    //   actualTarget = target (the existing PC-relative / indirect target)
    //   mispredict   = (predTaken != actualTaken) || (actualTaken && actualTarget != predTarget)
    // A correctly-predicted branch (predTaken==actualTaken && target matches) does NOT
    // mispredict -> NO commit-time flush -> the speculatively-fetched correct path
    // retires (the win). predTaken defaults False everywhere until the fetch redirect is
    // live, so this reduces to `mispredict == actualTaken` (== today's behavior).
    // isCondTrap (TRAPV/TRAPcc) never redirects (redirect is forced False above) and is
    // never predicted (predTaken False) -> mispredict stays False, as before.
    val actualTaken  = redirect
    val actualTarget = target
    val mispredict   = (u1.predTaken =/= actualTaken) ||
                       (actualTaken && (actualTarget =/= u1.predTarget))

    // ── BTB-update classification (which branches the BTB learns) ────────────────
    // In scope (slice 1): Bcc/BRA/BSR (relative) + JMP/JSR (ibranch, anInc==0). OUT:
    //  - Scc (not a control transfer) and isCondTrap (TRAPV/TRAPcc, a fault) — never learned.
    //  - RTS/RTR returns (ibranch with anInc != 0) — a last-target BTB is a poor return
    //    predictor; the return-address stack is slice 2. Excluded here so the BTB never
    //    learns a return and mis-predicts the next call site.
    val isReturn   = u1.ibranch && (u1.anInc =/= U(0, 3 bits))
    val isBtbBranch = s1Valid && !u1.isScc && !u1.isCondTrap && !isReturn &&
                      (u1.ibranch || u1.isBranch)
    // brType: uncond (1) = ibranch (JMP/JSR) OR an always-taken relative branch
    // (cond==0: BRA/BSR). cond (0) = Bcc (cond>=2) / DBcc. Used to force-take an
    // unconditional on a fresh BTB install + for stats.
    val isUncond = u1.ibranch || (!u1.isDbcc && (u1.cond.asUInt === U(0, 4 bits)))
    val brType   = Mux(isUncond, U(1, 2 bits), U(0, 2 bits))

    // ---- S1: completion (entry completes either way so it can retire) ----
    completionPort.valid              := s1Valid
    completionPort.payload.robId      := s1Ctx.robId
    completionPort.payload.mispredict := s1Valid && mispredict
    completionPort.payload.nextPc     := nextPc
    // BTB-update payload: the branch's PC + resolved taken/target + brType. The ROB
    // records these per-entry and drives the BTB write port at retire (no wrong-path
    // pollution). btbTarget is the ACTUAL resolved target (learned even on a not-taken
    // resolve, so a later taken hit predicts the right place).
    completionPort.payload.isBranch  := isBtbBranch
    completionPort.payload.btbPc     := u1.pc
    completionPort.payload.btbTaken  := actualTaken
    completionPort.payload.btbTarget := target
    completionPort.payload.brType    := brType
    // gshare PHT-update carry (slice 3): a conditional gshare-predicted branch (phtValid,
    // set at fetch on a condBtbHit) trains pht[phtIndex] toward actualTaken at retire. The
    // carried fetch-time index — not a retire-time recompute — is mandatory (the GHR has
    // shifted by retire). The branch EU just forwards the carried {phtValid, phtIndex} +
    // the resolved direction (btbTaken == actualTaken, already driven above).
    completionPort.payload.phtValid  := s1Valid && u1.phtValid
    completionPort.payload.phtIndex  := u1.phtIndex

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

    // ---- S1: isCondTrap execute-time conditional fault (vector 7 if cond taken) ----
    // Covers TRAPV (cond=9=VS, taken iff V) and TRAPcc (cond=cccc). Both deliver
    // vector 7 (format-$2 group-2 trap); the redirect is suppressed above.
    trapvFaultPort.valid          := s1Valid && u1.isCondTrap && taken
    trapvFaultPort.payload.robId  := s1Ctx.robId
    trapvFaultPort.payload.vector := U(7, 8 bits)   // vector 7 (TRAPV / TRAPcc)

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
