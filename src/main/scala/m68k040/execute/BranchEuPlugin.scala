package m68k040.execute

import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{FpccRegFileService, IntRegFileService, NzvcRegFileService, RegFileReadPort, RegFileWritePort, RegFileBypassPort}
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
  val btbLen     = UInt(4 bits)   // exact (nextPc-pc)/2 architectural length
  // ── gshare PHT-update fields (slice 3) ──
  val phtValid   = Bool()         // a CONDITIONAL gshare-predicted branch (train pht[phtIndex])
  val phtIndex   = UInt(11 bits)  // the carried fetch-time folded-XOR index the lookup read
}

/** Execute-time conditional fault completion (generalized from the original TRAPV-
  * only fault). An EU drives this when an execute-time check raises a synchronous
  * fault that carries an exception VECTOR: TRAPV (vector 7, branch EU), CHK (vector
  * 6, div EU), DIV0 (vector 5, div EU), ADDRESS ERROR (vector 3, branch EU — task
  * #189: a taken control transfer whose target has bit0 set). The ROB marks the
  * entry faulted + the carried vector. The stacked PC (= nextPc for the group-2
  * traps, = the transfer instruction's OWN pc for address error — see
  * MicroOpAssembler's ibrUop.faultUsesNextPc=False) is already captured per-entry
  * at alloc (payload.pc/predNextPc + the faultUsesNextPc selector), so normally
  * only {robId, vector} would be
  * needed here (cf. lsFaultCompletion, which must also carry the execute-computed
  * EA). Address error is the one exception: its format-$2 frame's extra "ADDRESS"
  * word (SP+8) must carry the faulting ODD TARGET, which is only known at execute
  * time (unlike CHK/DIV0/TRAPV's PPC field, which is just the already-alloc-known
  * instruction PC) — `faultAddr` carries it, ROB-plumbed into faultAddrStore /
  * ExceptionUnit.entryFaultAddr exactly like an LS access-fault EA. Zero/unused for
  * TRAPV/CHK/DIV0 (their is2 path never reads entryFaultAddr). */
case class EuFault() extends Bundle {
  val robId     = UInt(6 bits)
  val vector    = UInt(8 bits)
  val faultAddr = UInt(32 bits)
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
  // KEEP this commit as its own oracle step even though it writes only a TEMP and no
  // flags — the SAME field AluEuPlugin.WbObs/LsEuPlugin/DivEuPlugin already carry, and
  // for the SAME reason. The `Scc <ea>` memory-destination crack (MicroOpAssembler's
  // `sccIsMem`) is a branch-EU µop whose op µop writes the condition byte to T1 with
  // keepCommit=True, its trailing rmwStore being the dropped µop. Without this field the
  // whitebox `emit` rule (WhiteboxCapture.onCommit: temp-write + no flags => drop) threw
  // the ENTIRE `Scc <mem>` instruction out of the lock-step retire stream, shifting every
  // subsequent index by one and producing a spurious "dut PC is ahead of oracle by the
  // length of the next instruction" divergence. That accounted for 40 of the fuzz
  // campaign's 57 divergences (see docs/superpowers/campaigns/2026-09-03-fuzz-to-zero-
  // divergences.md, cluster A). BrWbObs was the ONLY EU observation bundle missing it.
  val keepCommit = Bool()
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
object BranchEuPlugin {
  /** Sharing key for BranchEu's int write port; the ExceptionUnit's A7 write borrows it. */
  val IntWbKey = "branchEuIntWb"
}

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
  // Int read port for a brief-indexed control EA's index register (psrcC = Xn, task
  // #187): mirrors LsEuPlugin's `rdIndex` AGU port. JMP/JSR (d8,An,Xn)/(d8,PC,Xn) thread
  // the index register through `ibrUop.srcC*`; a plain (non-indexed) ibranch leaves
  // psrcCValid=False so the read is architecturally a don't-care (the S1 term is forced
  // to zero, exactly like the LS EU's `idxTerm0` gating).
  var idxRd: RegFileReadPort = null
  // FPCC read port for the FP conditional branches (FBcc). The 4-bit renamed FPCC is
  // {NaN, I, Z, N} = bit3..bit0 (FpAddPipe.scala's "Internal FPCC order" note). An FBcc
  // declares `readsFpcc`, which is all the IQ needs: `trigInit`'s FPCC dep and the
  // `cplxFpccWait` dynamic wait are both cluster-agnostic (keyed only on `readsFpcc`) and
  // the per-slot `ready` term already includes `!cplxFpccWait`, so a branch waiting on an
  // in-flight FCMP is handled by the SAME machinery as a CPLX reader -- no IQ change.
  var fpccRd: RegFileReadPort = null
  override def issue = issuePort
  override def completion = completionPort
  override def trapvFault = trapvFaultPort

  during setup {
    issuePort = Stream(IqContext())
    // Debug-only observability (task #139 hang investigation, 2026-07-16): simPublic
    // is a pure sim-visibility tag, never affects synthesized behavior. Left in place
    // afterward — harmless if unused, and useful for any future issue-readiness trace.
    issuePort.valid.simPublic(); issuePort.ready.simPublic()
    completionPort = Flow(BranchCompletion())
    trapvFaultPort = Flow(EuFault())
    nzRd = host[NzvcRegFileService].newRead(forceNoBypass = false)
    tgtRd = host[IntRegFileService].newRead(forceNoBypass = false)
    anRd  = host[IntRegFileService].newRead(forceNoBypass = false)
    // SHARED with the ExceptionUnit's A7 write (see FullCoreSynth's `a7Wr`). BranchEu
    // is the right lender precisely because it is FIXED-LATENCY: `s1Valid` is a plain
    // RegNext of `issuePort.valid` and `issuePort.ready` is unconditionally True, so
    // this port is busy for exactly ONE cycle after a branch issues and never longer.
    // That makes "the port is free" a STATIC bound rather than a dynamic busy signal --
    // by the time the exception FSM reaches its A7 write it has been through E_DRAIN
    // (store-queue drain) and the frame stores, many cycles after the last issue, and
    // no new uop can issue meanwhile because the pipe is flushed and retire is blocked.
    // Priority 1 = BranchEu wins a collision; a collision is a BUG, and RegFilePlugin
    // asserts on it in simulation rather than silently dropping the loser.
    anW   = host[IntRegFileService].newWrite(latency = 1, sharingKey = BranchEuPlugin.IntWbKey, priority = 1)
    anByp = host[IntRegFileService].newBypass()
    idxRd = host[IntRegFileService].newRead(forceNoBypass = false)
    fpccRd = host[FpccRegFileService].newRead(forceNoBypass = true)
  }

  val logic = during build new Area {
    // ---- S0: read NZVC source + (ibranch) the int target base + index ----
    issuePort.ready := True              // fixed-latency EU never structurally stalls
    val u0 = issuePort.payload.uop
    nzRd.addr  := u0.pNzvcSrc
    tgtRd.addr := u0.psrcA
    anRd.addr  := u0.psrcB     // pre-pop A7 base (RTS/RTR postinc)
    // Brief-indexed control EA (task #187): the index register Xn rides psrcC, sized+
    // scaled exactly like LsEuPlugin's `rdIndex`/`idxTerm0` AGU port (.W sign-extends the
    // low 16 bits, .L uses the full 32; then shift-left by the scale exponent 0..3 =>
    // *1/2/4/8). Zero when non-indexed (psrcCValid False) so a plain JMP/JSR/Bcc/RTS/RTR
    // adds nothing extra. Read in S0 (off the regfile, possibly bypassed); the scaled
    // term is REGISTERED into s1Index at the S0->S1 boundary so the target adder stays a
    // shallow stage off flops, mirroring `s1TgtBase` (no deep ALU-bypass cone).
    idxRd.addr := u0.psrcC
    fpccRd.addr := u0.pFpccSrc
    val idxRaw0   = Mux(u0.indexLong, idxRd.data.asUInt,
                        idxRd.data(15 downto 0).asSInt.resize(32).asUInt)
    val idxScaled = ((idxRaw0 << u0.indexScale).resize(32))
    val idxTerm0  = Mux(u0.psrcCValid, idxScaled, U(0, 32 bits))

    // ---- S0 -> S1 register (M2S) ----
    val s1Valid = RegNext(issuePort.valid) init False
    val s1Ctx   = RegNext(issuePort.payload)
    val s1Nzvc  = RegNext(nzRd.data)              // {N(3),Z(2),V(1),C(0)}
    val s1Fpcc  = RegNext(fpccRd.data)            // {NaN(3),I(2),Z(1),N(0)}
    val s1AnBase= RegNext(anRd.data.asUInt)       // pre-pop A7 (for the postinc write)
    // Registered int target base (psrcA). For an absolute / PC-folded ibranch the
    // assembler leaves psrcAValid=False (base contribution must be ZERO; the folded
    // value rides in imm), exactly as the LS EU's base-mux for absolute/PC EAs.
    val s1TgtBase = RegNext(Mux(u0.psrcAValid, tgtRd.data.asUInt, U(0, 32 bits)))
    // Registered scaled index term (brief-indexed control EA): 0 for a non-indexed ibranch.
    val s1Index = RegNext(idxTerm0)
    val u1 = s1Ctx.uop
    // Debug-only observability (2026-09-12): the FMOVEM.X-then-Bcc defect
    // (fpu_store_then_cmp_bcc.s) needs the flags this EU ACTUALLY acted on plus the
    // physical NZVC register it read them from, to tell a wrong-VALUE read from a
    // wrong-ADDRESS read. Registered copies only; mirrors the existing wbObs pattern.
    val s1NzAddr  = RegNext(u0.pNzvcSrc)
    val s1ReadsNz = RegNext(u0.readsNzvc)

    // ---- S1: condition eval (cond[3:0]) ----
    val n = s1Nzvc(3); val z = s1Nzvc(2); val v = s1Nzvc(1); val c = s1Nzvc(0)
    val intTaken = u1.cond.asUInt.mux(
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
    // ---- S1: FP condition eval (FBcc) ----------------------------------------------
    // The PRM's 32 FP conditional predicates split into two halves IDENTICAL in truth
    // value: 0x10..0x1F are the "signaling" spellings of 0x00..0x0F (SF/SEQ/GT/... vs
    // F/EQ/OGT/...), differing ONLY in whether an unordered operand raises BSUN. The
    // taken/not-taken decision therefore depends on cc[3:0] alone, so an FBcc reuses the
    // EXISTING 4-bit `cond` field and needs no wider uop state -- cc[4] matters only for
    // BSUN, which belongs to FP exception delivery (vec 48-54, not implemented). And
    // `readsFpcc` already distinguishes an FP branch from an integer one, so no extra
    // "isFpBranch" bit is needed either.
    val fpNan = s1Fpcc(3); val fpZ = s1Fpcc(1); val fpN = s1Fpcc(0)
    val fpTaken = u1.cond.asUInt.mux(
      0  -> False,                              // F   / SF    (never)
      1  -> fpZ,                                // EQ  / SEQ
      2  -> !(fpNan || fpZ || fpN),             // OGT / GT
      3  -> (fpZ || !(fpNan || fpN)),           // OGE / GE
      4  -> (fpN && !(fpNan || fpZ)),           // OLT / LT
      5  -> (fpZ || (fpN && !fpNan)),           // OLE / LE
      6  -> !(fpNan || fpZ),                    // OGL / GL
      7  -> !fpNan,                             // OR  / GLE
      8  -> fpNan,                              // UN  / NGLE
      9  -> (fpNan || fpZ),                     // UEQ / NGL
      10 -> (fpNan || !(fpN || fpZ)),           // UGT / NLE
      11 -> (fpNan || fpZ || !fpN),             // UGE / NLT
      12 -> (fpNan || (fpN && !fpZ)),           // ULT / NGE
      13 -> (fpNan || fpZ || fpN),              // ULE / NGT
      14 -> !fpZ,                               // NE  / SNE
      15 -> True                                // T   / ST    (always)
    )
    // Select, not merge: the two tables deliberately disagree on cond 0/1 (integer
    // 0=T/1=F vs FP 0=F/1=EQ).
    val taken = Mux(u1.readsFpcc, fpTaken, intTaken)

    // PC-relative target (Bcc/BRA/BSR). INDIRECT (ibranch) target = base + imm + scaled
    // index (a tiny AGU: base = psrcA for (An)/(d16,An)/RTS-RTR-T0, 0 for absolute/PC-
    // folded; imm = displacement / folded absolute / folded PC / 0; index = scaled Xn for
    // a brief-indexed control EA, 0 otherwise — task #187). All three terms are flops
    // (s1TgtBase/s1Index) or uop-derived (u1.imm, no ALU-result bypass), so this is a
    // SINGLE shallow 3-input adder off held flops, not a second serial add stage chained
    // onto s1TgtBase — mirrors LsEuPlugin's `s1Va = s1Base + s1Disp + s1Index` exactly
    // (see that file's FMax rationale comment).
    val relTarget = (u1.pc + 2 + u1.branchDisp.asUInt)
    val indTarget = (s1TgtBase + u1.imm.asUInt + s1Index)
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
    val rawRedirect = Mux(u1.isScc || u1.isCondTrap, False,
                      Mux(u1.isDbcc, dbBranch,
                      Mux(u1.ibranch, True, taken)))
    // ── Address error (task #189, vector 3) ───────────────────────────────────────
    // The 68040 requires the PC to always be even (M68040UM §8.2.3). ANY taken
    // control transfer (JMP/JSR/BRA/Bcc/BSR/DBcc/RTS/RTR — everything that can set
    // `rawRedirect`) whose resolved target has bit0 set must raise address error
    // instead of actually transferring control. Unlike isCondTrap (a STATIC, decode-
    // known fault), this is discovered dynamically here in S1 from the computed
    // `target` — any of the ibranch/relative-branch paths can produce an odd target
    // at runtime. Scc/isCondTrap are automatically excluded (rawRedirect is already
    // forced False for them, so target's LSB is never consulted).
    val addrErr   = rawRedirect && target(0)
    // Suppress the actual control transfer on an address-error target — exactly like
    // isCondTrap, this µop does NOT redirect fetch; the precise commit-time exception
    // FSM (driven by the euFault pulse below) performs the REAL redirect to the
    // vector-3 handler once this entry retires as faulted. This also naturally gives
    // the documented per-stage semantics for free via the existing fault/retire
    // machinery: a JSR/BSR's return-address push is a SEPARATE, earlier µop (the
    // crack's store phase) that already retired normally before this (later) branch
    // phase reaches retire and takes the fault; an RTS/RTR's A7 postinc / a DBcc's Dn
    // decrement below (anWrite) is explicitly gated off `addrErr` so the faulting
    // µop's register effect never reaches the PRF at all (belt-and-suspenders on top
    // of the normal rename/retire discipline, which alone would already prevent a
    // faulted entry's speculative write from ever becoming the architectural mapping).
    val redirect  = rawRedirect && !addrErr
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
    //  - RTS/RTR/RTD returns (ibranch with isReturn) — a last-target BTB is a poor
    //    return predictor; the return-address stack is slice 2. Excluded here so the
    //    BTB never learns a return and mis-predicts the next call site.
    // addrErr is ALSO excluded from BTB training (task #189) — a faulting branch
    // never actually executed a control transfer, so learning its (invalid, odd)
    // target would poison a future correctly-encoded taken prediction at the same PC.
    // isReturn is a dedicated DecodedUop/RenamedUop field (RTD-btb-training-fix), NOT
    // derived from anInc: RTD is a genuine return but folds its A7 deallocation into
    // a SEPARATE, un-fused ADD µop (its 4+disp16 amount can't fit anInc's 3 bits), so
    // its ibranch always carries anInc=0 -- an `anInc =/= 0` proxy would (and
    // previously did) misclassify it as a plain ibranch and let it train the BTB/FTB
    // with the popped return address.
    val isReturn   = u1.ibranch && u1.isReturn
    val isBtbBranch = s1Valid && !u1.isScc && !u1.isCondTrap && !isReturn && !addrErr &&
                      (u1.ibranch || u1.isBranch)
    // brType: uncond (1) = ibranch (JMP/JSR) OR an always-taken relative branch.
    // cond (0) = Bcc (cond>=2) / DBcc / FBcc. Used to force-take an unconditional on a
    // fresh BTB install (Ftb's ctrAlloc installs brType==1 as STRONGLY TAKEN) + for stats.
    //
    // "Always taken" is encoding-dependent and the two tables are OPPOSITE at cond==0:
    // integer cond 0 is T (BRA/BSR, always taken), but FP cond 0 is F/SF ("branch never").
    // Reading the integer meaning for an FBcc would install FBSF as strongly-taken and
    // mispredict it every single execution. Prediction-only -- the EU still resolves the
    // true direction and `mispredict` redirects -- but wrong, and free to get right.
    // FBF proper (cc==0 exactly) never arrives here at all: it decodes as a NOP. The
    // encoding that does is SF (0x10), whose cc[3:0] is likewise 0.
    val condAlwaysTaken = Mux(u1.readsFpcc, u1.cond.asUInt === U(15, 4 bits),   // FP T / ST
                                            u1.cond.asUInt === U(0,  4 bits))   // int T (BRA)
    val isUncond = u1.ibranch || (!u1.isDbcc && condAlwaysTaken)
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
    completionPort.payload.btbLen    := ((u1.nextPc - u1.pc) >> 1).resize(4)
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
    // addrErr excluded (task #189): an RTS/RTR that faults on an odd popped target
    // must NOT update A7 (the odd return address stays on the stack, unpopped); a
    // faulting DBcc must NOT commit its counter decrement. Scc is unaffected (never
    // redirects, so addrErr is always False for it).
    val anWrite    = s1Valid && !addrErr && ((u1.ibranch && u1.pdstValid) || condIntWr)
    val intData    = Mux(condIntWr, condIntVal, newAn.asBits)
    anW.valid     := anWrite;  anW.address := u1.pdst;  anW.data := intData
    anByp.valid   := anWrite;  anByp.address := u1.pdst; anByp.data := intData

    // ---- S1: isCondTrap execute-time conditional fault (vector 7 if cond taken) OR
    // an address-error fault (vector 3, task #189) ----
    // isCondTrap covers TRAPV (cond=9=VS, taken iff V) and TRAPcc (cond=cccc); both
    // deliver vector 7 (format-$2 group-2 trap). addrErr (see above) covers ANY taken
    // control transfer to an odd target; it delivers vector 3, ALSO a format-$2 frame,
    // but with the frame's extra "ADDRESS" word (SP+8) carrying the faulting target
    // (`faultAddr`) rather than the instruction's own PC (which the PPC/PC fields
    // already get for free via the ROB payload's alloc-time capture — see ibrUop's
    // faultUsesNextPc=False in MicroOpAssembler). The two fault sources are mutually
    // exclusive: isCondTrap ops force rawRedirect (hence addrErr) False above.
    trapvFaultPort.valid             := s1Valid && ((u1.isCondTrap && taken) || addrErr)
    trapvFaultPort.payload.robId     := s1Ctx.robId
    trapvFaultPort.payload.vector    := Mux(u1.isCondTrap, U(7, 8 bits), U(3, 8 bits))
    trapvFaultPort.payload.faultAddr := target   // meaningful only for the addrErr (vec 3) case

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
    // Mirrors AluEuPlugin's `wbObs.keepCommit := RegNext(...u1.keepCommit) init False`.
    wbObs.keepCommit := RegNext(u1.keepCommit) init False
    wbObs.simPublic()

    // ---- Debug-only branch-condition observability (2026-09-12) -------------------
    // Not consumed by any hardware; exists so a sim can answer "what flags did this
    // branch actually act on, and which physical NZVC register did they come from?".
    val brDbg = new Bundle {
      val valid   = Bool()
      val pc      = UInt(32 bits)
      val cond    = Bits(4 bits)
      val readsNz = Bool()
      val nzAddr  = UInt(u0.pNzvcSrc.getWidth bits)
      val nzvc    = Bits(4 bits)
      val taken   = Bool()
      val redirect= Bool()
      val nextPc  = UInt(32 bits)
    }
    brDbg.valid    := s1Valid
    brDbg.pc       := u1.pc
    brDbg.cond     := u1.cond
    brDbg.readsNz  := s1ReadsNz
    brDbg.nzAddr   := s1NzAddr
    brDbg.nzvc     := s1Nzvc
    brDbg.taken    := taken
    brDbg.redirect := redirect
    brDbg.nextPc   := nextPc
    brDbg.simPublic()
  }
}
