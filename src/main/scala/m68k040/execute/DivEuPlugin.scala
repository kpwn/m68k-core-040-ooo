package m68k040.execute

import m68k040.decode.{DecOp, FpSrcKind}
import m68k040.execute.fpu.{FpExcFlags, FpNarrowPack, FpResult, FpSource, FpuCore}
import m68k040.execute.iq.IqContext
import m68k040.execute.regfile.{FpccRegFileService, FpRegFileService, IntRegFileService,
  NzvcRegFileService, RegFileReadPort, RegFileWritePort, RegFileBypassPort}
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
  // ── FP writeback lane (structurally independent of everything above) ──────────
  // The existing `completion`/`wakeup`/`euFault` trio is the 32-bit int/NZVC lane and is
  // UNCHANGED. An 80-bit FP result cannot ride it (`CplxResult.data` and the `compData`
  // completion register are hardcoded 32-bit, and unlike MULHI's 2x32 crack there is no
  // 32-bit destination file to crack INTO -- FP's destination is a new 80-bit regfile with
  // its own natural width), so the FP result gets its own descriptor pipe, its own
  // completion register, its own ROB completion port and its own regfile write ports,
  // sharing only the single CPLX issue port. The structural precedent is the MUL lane's
  // independence, not MULHI's chunking.
  /** robId of a completing FP op (its own ROB completion port -- a same-cycle int and FP
    * completion is possible and neither may be dropped on a non-backpressured Flow). */
  def fpCompletion: Flow[UInt]
  /** pFpDst of a completing FP op (IQ `cplxFpWakeup`; 4-bit FP tag space). */
  def fpWakeup: Flow[UInt]
  /** pFpccDst of a completing FP op (IQ `cplxFpccWakeup`). SEPARATE from `fpWakeup`:
    * FCMP/FTST write FPCC with no FP destination at all. */
  def fpccWakeup: Flow[UInt]
  /** Enabled-trap escalation for an FP arithmetic exception (vectors 49-54). Distinct from
    * `euFault` so an FP escalation and a same-cycle CHK/DIV0 fault cannot collide on one
    * non-backpressured Flow. */
  def fpFault: Flow[EuFault]
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

/** Pruned descriptor that follows an in-flight FP operation, mirroring MulPipeContext's
  * role exactly: result ROUTING metadata only, no operand values (FpuCore holds those) and
  * no operation selector (the op is already inside FpuCore's own pipe, and the rounding mode
  * travels with the request inside `FpRoundReq.rmode`). */
case class FpPipeContext() extends Bundle {
  val robId     = UInt(6 bits)
  val pdst      = UInt(4 bits)   // FP data physical dest (RenamedUop.pFpDst)
  val pdstValid = Bool()         // False for FCMP/FTST (FPCC-only ops)
  val pFpccDst  = UInt(4 bits)
  val fpccWrite = Bool()
}

/** MC68040 FP arithmetic exception vectors (UM Table 8-1 / exception vector assignments).
  * Only reachable via the FPCR enable byte, i.e. only when a program explicitly asked for
  * the trap; the hardware-native OVFL/UNFL substitution (design Decision 10) already
  * produced a usable result inside FpuCore for the ordinary, non-enabled case. */
object FpVector {
  val Inex   = 49
  val Dz     = 50
  val Unfl   = 51
  val Operr  = 52
  val Ovfl   = 53
  val Snan   = 54
}

object DivEuPlugin {
  /** Task 14c: connect the CPLX EU's FP lane to the live FP control state.
    *
    * Factored out because FOUR different top levels build the same DivEu + FpuControlPlugin
    * pair (`top.BackendWiringPlugin`, `lockstep.ExecuteLockStepSpec`, `fuzz.FuzzDut`,
    * `bench.IpcBenchSpec`) and a divergence between them would silently give the lock-step
    * and fuzz DUTs different FP semantics from the synthesized core -- exactly the class of
    * drift this task exists to close.
    *
    * Must be called from a context where `svc`'s own `logic` Area has already elaborated
    * (i.e. the same seam that already carries `fpCtrlFpcrIn`), because every accessor below
    * dereferences the plugin's committed `Reg`s.
    *
    * BIT ORDER (the one non-obvious part). `svc.excEnable` is `FPCR(15 downto 8)`, so
    * within that 8-bit slice index i means FPCR[8+i]. The MC68040 UM's Figure 9-2 gives the
    * ENABLE byte as 15 BSUN, 14 SNAN, 13 OPERR, 12 OVFL, 11 UNFL, 10 DZ, 9 INEX2, 8 INEX1
    * -- so 6=SNAN, 5=OPERR, 4=OVFL, 3=UNFL, 2=DZ, 1=INEX2. `FpExcFlags` is a plain
    * declaration-ordered SpinalHDL Bundle whose flattened layout carries no architectural
    * meaning, so this is written out field by field rather than as a slice assignment.
    * BSUN (bit 7) and INEX1 (bit 0) have no `FpExcFlags` counterpart on purpose: BSUN is a
    * branch-side condition and INEX1 is packed-decimal-only, so neither can ever be raised
    * by this lane and enabling them cannot change what it does. */
  def wireFpControl(divEu: DivEuPlugin, svc: m68k040.services.FpuControlService): Unit = {
    divEu.fpCtrlFpcrIn  := svc.fpcr
    divEu.fpCtrlFpsrIn  := svc.fpsr
    divEu.fpCtrlFpiarIn := svc.fpiar
    divEu.fpRmodeIn     := svc.roundingMode
    val en = svc.excEnable
    divEu.fpExcEnableIn.snan  := en(6)
    divEu.fpExcEnableIn.operr := en(5)
    divEu.fpExcEnableIn.ovfl  := en(4)
    divEu.fpExcEnableIn.unfl  := en(3)
    divEu.fpExcEnableIn.dz    := en(2)
    divEu.fpExcEnableIn.inex2 := en(1)
    svc.orFpsrExc.valid   := divEu.fpExcAccrualPort.valid
    svc.orFpsrExc.payload := divEu.fpExcAccrualPort.payload
  }
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
  // ---- FP lane ports ----
  var fpCompletionPort: Flow[UInt] = null
  var fpWakeupPort: Flow[UInt]     = null
  var fpccWakeupPort: Flow[UInt]   = null
  var fpFaultPort: Flow[EuFault]   = null
  var fpRdA, fpRdB: RegFileReadPort = null
  var fpW: RegFileWritePort   = null
  var fpccW: RegFileWritePort = null
  var fpccRd: RegFileReadPort = null                                // Task 9b: FPCC reader
  // Task 9b: live FPCR/FPSR/FPIAR, driven by the wiring plugin (see `setup` for why these
  // are input wires and not a direct `host[FpuControlService]` read).
  var fpCtrlFpcrIn:  UInt = null
  var fpCtrlFpsrIn:  UInt = null
  var fpCtrlFpiarIn: UInt = null
  /** FPCR[5:4] rounding mode (0=RN 1=RZ 2=RM 3=RP), sampled at ISSUE and carried with the
    * request inside FpuCore, so an FPCR write landing mid-flight cannot re-mux an
    * already-issued result. Default-driven RN (allowOverride) so a standalone EU DUT that
    * instantiates no `FpuControlPlugin` still elaborates; in the real core (Task 14c) the
    * wiring plugin drives it from `FpuControlService.roundingMode`, i.e. the live FPCR.
    * The default is also the architecturally correct value for an unwired DUT: FPCR
    * resets to 0. */
  var fpRmodeIn: Bits = null
  /** Per-class exception-ENABLE bits, i.e. FPCR[15:8] re-mapped field by field into
    * `FpExcFlags` (the re-map lives in `DivEuPlugin.wireFpControl`). Same contract as
    * `fpRmodeIn`: default all-clear for a standalone DUT, driven from
    * `FpuControlService.excEnable` in the real core (Task 14c). Gates `fpFault` escalation
    * ONLY — the ordinary substituted OVFL/UNFL result is produced inside FpuCore and must
    * NOT trap, or every overflow re-introduces the per-op trap cost this design removes. */
  var fpExcEnableIn: FpExcFlags = null
  /** Task 14c: the FPSR exception-status byte this lane raised for the operation whose
    * result is being delivered this cycle, in the ARCHITECTURAL FPSR[15:8] bit order
    * (7 BSUN, 6 SNAN, 5 OPERR, 4 OVFL, 3 UNFL, 2 DZ, 1 INEX2, 0 INEX1 — MC68040 UM
    * Figure 9-5). BSUN and INEX1 are always 0 here: BSUN is a branch-side condition and
    * INEX1 is packed-decimal-only, so neither can originate in this lane (the same reason
    * `FpExcFlags` has no field for either). Consumed by `FpuControlPlugin.orFpsrExc`,
    * which does the EXC -> AEXC fold. Valid only for a non-flushed delivery, and only
    * when the byte is non-zero (an all-clear byte would OR in nothing). */
  var fpExcAccrualPort: Flow[Bits] = null

  override def issue: Stream[IqContext] = issuePort
  override def completion: Flow[UInt]   = completionPort
  override def wakeup: Flow[UInt]       = wakeupPort
  override def wakeupNzvc: Flow[UInt]   = wakeupNzvcPort
  override def euFault: Flow[EuFault]   = euFaultPort
  override def fpCompletion: Flow[UInt] = fpCompletionPort
  override def fpWakeup: Flow[UInt]     = fpWakeupPort
  override def fpccWakeup: Flow[UInt]   = fpccWakeupPort
  override def fpFault: Flow[EuFault]   = fpFaultPort
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
    // ---- FP lane ----
    fpCompletionPort = Flow(UInt(6 bits))
    fpWakeupPort     = Flow(UInt(4 bits))
    fpccWakeupPort   = Flow(UInt(4 bits))
    fpFaultPort      = Flow(EuFault()); fpFaultPort.simPublic()
    fpRmodeIn = Bits(2 bits); fpRmodeIn.allowOverride; fpRmodeIn := B"2'b00"
    fpExcEnableIn = FpExcFlags()
    fpExcEnableIn.flatten.foreach(_.allowOverride)
    fpExcEnableIn.clearExc()
    fpExcAccrualPort = Flow(Bits(8 bits)); fpExcAccrualPort.simPublic()
    val fprf = host[FpRegFileService]
    // NO bypass port on either FP file, deliberately. The int/NZVC files need one because a
    // STATIC latency-1 scoreboard wakeup can put a consumer's regfile read in the very cycle
    // of the producer's write. Every FP consumer instead waits on a DYNAMIC completion
    // wakeup (`cplxFpWakeup`/`cplxFpccWakeup`, IssueQueuePlugin's cplxFpWait/cplxFpccWait):
    // the wakeup clears a REGISTERED wait bit, the woken slot can be selected no earlier
    // than the next cycle, and the CPLX issue Stream is itself registered -- so the earliest
    // possible dependent read is two cycles after the write port fires, by which time the
    // synchronous Mem write has landed. An 80-bit bypass comparator/mux on every FP read
    // would be pure area for an unreachable case.
    fpRdA = fprf.newRead(forceNoBypass = true)   // FPn (the destination, read back for dyadic ops)
    fpRdB = fprf.newRead(forceNoBypass = true)   // FPm (fpSrcKind === FPREG only)
    fpW   = fprf.newWrite(latency = 1)
    fpccW = host[FpccRegFileService].newWrite(latency = 1)
    // ---- Task 9b: FMOVEM control-register LIST form, STORE direction ----
    // The design's FIRST FPCC *reader*. Deliberately `forceNoBypass = true`, for exactly
    // the reason the two FP-file reads above give: `DecOp.FPCTRLRD` declares a real
    // `readsFpcc`, so IssueQueuePlugin's DYNAMIC `cplxFpccWait` holds it until at least
    // two cycles after the producing write port fired -- a bypass comparator on a 16x4
    // file would be pure area for an unreachable case. (16 entries x 4 bits: one 4-bit
    // 16:1 mux, negligible next to the int file's 50x32.)
    fpccRd = host[FpccRegFileService].newRead(forceNoBypass = true)
    // FPCR / FPSR (non-FPCC bytes) / FPIAR, read LIVE for `DecOp.FPCTRLRD`.
    //
    // Deliberately plain `allowOverride` INPUT WIRES driven by a sibling wiring plugin,
    // NOT a `host[FpuControlService]` lookup. Reading the service directly from this
    // plugin's `logic` is a real, reproducible Fiber build-order hazard:
    // `FpuControlPlugin` publishes its registers through `var _fpcr/_fpsr/_fpiar` that
    // are only assigned inside its own `during build` Area, so a consumer whose `logic`
    // happens to elaborate first gets `null` and dies with a bare NullPointerException.
    // That is exactly what happened here (caught by this task's own synth gate, not by
    // any simulation DUT -- the plugin ORDER differs between FullCoreSynth and the
    // lock-step DUT, so it reproduced only in Verilog generation), and it is the same
    // hazard `ExceptionUnit`'s `require(fpuCtrl.fpcr != null, ...)` guard documents.
    //
    // The `allowOverride`-input + wiring-plugin seam is this codebase's established,
    // ORDER-PROOF answer to it -- byte-for-byte the pattern `ExceptionUnit.committedA7In`
    // / `.committedFpccIn` and `DecodeStage.pipeFlush` already use. The idle default also
    // keeps every standalone DivEu DUT elaborating without wiring anything.
    //
    // Reading these live and unsynchronised is safe (design spec Decision 3): FPCR and
    // FPIAR have exactly ONE writer, ExceptionUnit's S_APPLY, always behind a serializing
    // sysOp whose retirement unconditionally squashes and re-fetches everything younger,
    // so a stale speculative read can never retire. Structurally identical to
    // MmuControlService's already-live urp/srp/dtt0/dtt1 reads in
    // DtlbPlugin/LsEuPlugin/ItlbPlugin.
    //
    // FPSR IS DIFFERENT SINCE TASK 14c and this comment used to be wrong about it. `fpsr`
    // now has a SECOND writer -- `FpuControlPlugin`'s `orFpsrExc` OR-accumulate
    // (`FpuControlPlugin.scala`, the `when(orFpsrExc.valid && !setFpsr.valid)` arm), fed
    // from `fpExcAccrualPort` below at EXECUTE-time completion, NOT behind any sysOp. The
    // 2026-08-16 FP-control design's Decision 4 required whoever wired that port to
    // re-assess this argument for real; the assessment is recorded in that spec and
    // summarised here:
    //
    //   * MAIN PATH SAFE, BUT INCIDENTALLY SO. The only consumer of these live reads is
    //     `UFpCtrlRead` (FMOVEM-control / FMOVE-from-FPSR). That row ALWAYS declares
    //     `readsFpcc` (Microcode.scala, `UFpCtrlRead`'s comment: the position-to-register
    //     choice is a runtime mux, so the row cannot know statically whether it is the
    //     FPSR one), and EVERY HW-native FP op writes FPCC (Microcode.scala's
    //     `u.writesFpcc := True` for the FP-op family). So the CPLX dynamic-wakeup
    //     scoreboard holds any FPSR read at least two cycles past the producing FP op's
    //     `fpccW`, which fires the SAME cycle as its `fpExcAccrualPort` accrual (both are
    //     gated on `fpCompLive`). The ordering is real, but it is load-bearing on an FPCC
    //     dependency that has nothing to do with FPSR by design -- it would evaporate if
    //     `readsFpcc` were ever narrowed to "only the FPSR position". Treat it as an
    //     invariant to preserve, not as a coincidence to tidy away.
    //
    //   * KNOWN, DEFERRED RESIDUAL. Task 14b's `UFpStoreCvt` (the FMOVE FPn,<ea> store
    //     -direction narrow converter) is a SECOND accrual site into the same port, and it
    //     declares NO FPCC dependency at all (`readsFpcc` stays default `False`). So an
    //     `FMOVE FPn,<ea>` that raises OPERR/INEX2/OVFL/UNFL/SNAN in the converter,
    //     immediately followed by an `FMOVEM.L FPSR,-(An)`, has no ordering guarantee: the
    //     stored FPSR image may miss the just-accrued sticky bit. This is NOT fixed here.
    //     Severity is bounded by construction: the accrual is a pure OR of STICKY bits, so
    //     the worst case is a snapshot one cycle late, never a torn or incoherent value,
    //     and it is self-healing on any later read. Nothing exercises it today either --
    //     the accrual-vs-read race only matters to a program that reads FPSR to dispatch,
    //     and there is no FPCR-enabled-trap handler substrate on this branch yet. Closing
    //     it is a real interlock change (give `UFpStoreCvt` a wakeup dependency, or make
    //     the accrual retire-time), and belongs in its own gated slice.
    fpCtrlFpcrIn  = UInt(32 bits); fpCtrlFpcrIn.allowOverride;  fpCtrlFpcrIn  := U(0, 32 bits)
    fpCtrlFpsrIn  = UInt(32 bits); fpCtrlFpsrIn.allowOverride;  fpCtrlFpsrIn  := U(0, 32 bits)
    fpCtrlFpiarIn = UInt(32 bits); fpCtrlFpiarIn.allowOverride; fpCtrlFpiarIn := U(0, 32 bits)
  }

  val logic = during build new Area {
    // ---- S0: read operands ----
    val u0 = issuePort.payload.uop
    rdA.addr := u0.psrcA
    rdB.addr := u0.psrcB
    rdH.addr := u0.psrcC     // DIV.L 64/32 dividend HIGH word (Dr) via the 3rd source
    nzvcRd.addr := u0.pNzvcSrc                  // CMP2/CHK2 old NZVC (preserve N/V)
    fpccRd.addr := u0.pFpccSrc                  // Task 9b: DecOp.FPCTRLRD's FPCC source
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
    // FP: exactly one DecOp for the whole F-line family, so the SPECIFIC operation (and
    // therefore which of FpuCore's two lanes it lands on) comes from the raw ISA opmode.
    val issueIsFp      = u0.op === DecOp.FPU
    // ... EXCEPT for FMOVECR, whose 7-bit field is a constant-ROM offset, not an opmode:
    // `romConst` vetoes the iterative classification exactly as it overrides the FpOp
    // mapping below, so `FMOVECR #$04`/`#$20` cannot be misrouted onto a lane that would
    // never produce a `doneIter` for them.
    val fpIsRomConst   = u0.fpSrcKind === FpSrcKind.ROMCONST
    val fpOpIsIter     = FpSource.isIterativeOpmode(u0.fpuOp, fpIsRomConst)
    val issueIsFpIter  = issueIsFp && fpOpIsIter        // FDIV / FSQRT -> single-context lane
    val issueIsFpFixed = issueIsFp && !fpOpIsIter       // everything else -> II=1 fixed pipe
    val fpIterBusy     = RegInit(False)
    val mulCanAccept = Bool(); mulCanAccept.allowOverride; mulCanAccept := False
    val mulHiAvailable = Bool(); mulHiAvailable.allowOverride; mulHiAvailable := False
    // The IQ connection is a non-collapsing registered Stream.  When its current
    // valid is low, stale payload bits must not hold ready low or the IQ cannot load
    // a new lane-eligible candidate behind an active divider.
    //
    // The FP fixed lane accepts UNCONDITIONALLY (no credit gate, unlike MUL's result FIFO):
    // its result lands in a dedicated 1-cycle completion register that is re-armed every
    // cycle, so a back-to-back II=1 FP stream never needs one. The FP ITERATIVE lane is
    // single-context, exactly like DivUnit, and `fpIterBusy` also stays set across a
    // FLUSHED iterative op until FpDivSqrtCore is genuinely free again (see the iterAck
    // contract below) -- which is precisely what makes the gate correct rather than
    // optimistic.
    issuePort.ready := !flushSig && (!issuePort.valid || Mux(issueIsMul,
      mulCanAccept,
      Mux(issueIsMulHi, mulHiAvailable,
      Mux(issueIsFpFixed, True,
      Mux(issueIsFpIter, !fpIterBusy, !busy && !s1Valid)))))

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
    val s1Fpcc  = Reg(Bits(4 bits))    // Task 9b: the renamed FPCC nibble (DecOp.FPCTRLRD)
    // Task 14b: the 80-bit FPn source of a `DecOp.FPSTORECVT`, captured from the SAME
    // unconditionally-addressed `fpRdA` port the FP lane reads (`fpRdA.addr := u0.pFpSrcA`
    // below) -- byte-for-byte the shape `s1Fpcc` already uses for the FPCC file. Latching
    // the RAW source (rather than a converted result) keeps the whole `FpNarrowPack` cone
    // inside S1, out of series with the FP register-file read.
    val s1FpSrc = Reg(Bits(80 bits))
    val u1 = s1Ctx.uop

    // Main MUL, MULHI and FP have independent pipelines/queues below.  Every other
    // operation captures the legacy lane context and clears it explicitly on use.
    // (An FP uop MUST be excluded here: the legacy FSM's defensive `otherwise` arm would
    // otherwise complete it a second time, on the int lane, with its robId.)
    when(issuePort.fire && !issueIsMul && !issueIsMulHi && !issueIsFp) {
      s1Valid := True
      s1Ctx   := issuePort.payload
      s1A     := s0A
      s1B     := s0B
      s1H     := s0H
      s1Nzvc  := s0Nzvc
      s1Fpcc  := fpccRd.data           // Task 9b
      s1FpSrc := fpRdA.data            // Task 14b
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

    // ─────────────────────────────────────────────────────────────────────────
    // Shared bound-operand sign-extension (task #256): CHK is mathematically CMP2
    // with the lower bound hardwired to 0 -- its "Dn" (checked value) occupies the
    // exact s1A slot CMP2/CHK2's lower bound does, and its "bound" (upper limit)
    // occupies the exact s1B slot CMP2/CHK2's upper bound does. Both instructions
    // sign-extend that slot by the SAME per-size rule (BYTE is unreachable for CHK,
    // which never decodes with size=BYTE, so its arm is simply dead code on that
    // path, not a behavior change). Since isChk/isCmp2 are mutually exclusive (one
    // op occupies S1 at a time) this was previously TWO fully redundant 32-bit
    // sign-extend cones computed in parallel every cycle regardless of which op (if
    // either) was actually in S1; folding them into one physical cone removes that
    // duplication (~20-50 LUT) with no numeric change on either path -- verified
    // against the real 68040 PRM: CHK traps iff Dn<0 || Dn>bound, CMP2/CHK2 traps
    // (CHK2 only) iff compare is outside [lower,upper], and CHK IS that formula with
    // lower==0. What is genuinely NOT shared (kept fully separate below, per-op):
    // the trap/flag COMPOSITION -- CHK's Z is (Dn==0) and it OVERWRITES all of NZVC,
    // while CMP2/CHK2's Z is (compare==lower||compare==upper) and it's an RMW that
    // PRESERVES old N/V -- and CMP2/CHK2's compared value (`c2Compare`, from s1H)
    // has its own adReg-dependent zero/sign-extend that CHK has no equivalent of.
    // Fault-vector wiring was checked too and needs NO unification: it was already
    // shared correctly pre-existing -- CHK and CHK2 both raise vector 6 (M68000 PRM
    // exception-vector table: "6 -- CHK, CHK2 Instruction", a single shared vector,
    // not two different ones) via the same `captureFault` helper, and both mark
    // `faultUsesNextPc := True` at assembly (MicroOpAssembler.scala:746 for CHK,
    // :3058 for CMP2CHK2) since both are group-2/format-$2 traps that stack the
    // NEXT instruction's PC.
    def boundExtend(v: Bits): SInt = u1.size.mux(
      Size.BYTE -> v( 7 downto 0).asSInt.resize(32),
      Size.WORD -> v(15 downto 0).asSInt.resize(32),
      default   -> v.asSInt)
    val boundLo = boundExtend(s1A)   // CHK: Dn (checked value).   CMP2/CHK2: lower bound.
    val boundHi = boundExtend(s1B)   // CHK: bound (upper limit).  CMP2/CHK2: upper bound.

    // ---- CHK compare (single-cycle) ----
    // CHK.W compares the low 16 bits (sign-extended); CHK.L the full 32. Trap
    // (vector 6) iff Dn<0 || Dn>bound. CHK writes no register, but DOES commit a
    // full NZVC every execution (both trap and no-trap paths) — see chkNzvc below.
    val isChk = u1.op === DecOp.CHK
    val chkNeg   = boundLo < 0
    val chkOver  = boundLo > boundHi
    val chkTrap  = chkNeg || chkOver
    // N flag per the rule: N=1 if Dn<0, N=0 otherwise (incl. Dn>bound and in-bounds).
    // Z = (Dn==0) — Musashi's m68k_op_chk_{16,32}_d: `FLAG_Z = ZFLAG_16/32(src)`, set
    // UNCONDITIONALLY from the checked value's own zero-ness (labeled "Undocumented" in
    // Musashi but real, oracle-matching 68k behavior — NOT hardcoded 0 as previously
    // assumed here; found via a 200-seed fuzz campaign, task #139, 2026-07-16). V/C are
    // genuinely always 0 (also "Undocumented" in Musashi, confirmed unconditional).
    // CHK ALWAYS commits this NZVC (even on the trap path, so the stacked CCR matches).
    val chkN     = chkNeg
    val chkZ     = boundLo === 0
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
    val c2Lower = boundLo
    val c2Upper = boundHi
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
    // ── Task 9b: FMOVEM control-register LIST form, STORE direction ─────────────────
    // `DecOp.FPCTRLRD`: read the `pos`-th SELECTED control register into an int temp.
    // See the FSM arm below for the full rationale; this is just the datapath.
    val isFpCtrlRd = u1.op === DecOp.FPCTRLRD
    // Idle null object for a standalone DivEu DUT with no FpuControlPlugin (mirrors
    // ExceptionUnit's own `fpuCtrlOpt` fallback).
    val fpCtrlMask = u1.imm(2 downto 0)                 // {FPCR, FPSR, FPIAR}, MSB-first
    val fpCtrlPos  = u1.imm(5 downto 4).asUInt          // this row's transfer position
    // Architectural transfer order: FPCR first, FPSR second, FPIAR last (D9). A selected
    // register's position == how many selected registers precede it.
    val fpCtrlPosFpcr  = U(0, 2 bits)
    val fpCtrlPosFpsr  = fpCtrlMask(2).asUInt.resize(2)
    val fpCtrlPosFpiar = (fpCtrlMask(2).asUInt +^ fpCtrlMask(1).asUInt).resize(2)
    // FPSR read-back: the non-FPCC bytes from FpuControlPlugin (it masks [27:24] off
    // itself) OR the renamed FPCC nibble, reversed {NaN,I,Z,N} -> {N,Z,I,NaN} exactly as
    // ExceptionUnit's `fpsrArch` does. `s1Fpcc` was read at S0 from `pFpccSrc`.
    val fpCtrlFpccArch = s1Fpcc(0) ## s1Fpcc(1) ## s1Fpcc(2) ## s1Fpcc(3)
    val fpCtrlFpsrArch = fpCtrlFpsrIn | (fpCtrlFpccArch.asUInt.resize(32) |<< 24)
    val fpCtrlRdValue = Mux(fpCtrlMask(2) && (fpCtrlPos === fpCtrlPosFpcr),  fpCtrlFpcrIn,
                        Mux(fpCtrlMask(1) && (fpCtrlPos === fpCtrlPosFpsr),  fpCtrlFpsrArch,
                        Mux(fpCtrlMask(0) && (fpCtrlPos === fpCtrlPosFpiar), fpCtrlFpiarIn,
                                                                             U(0, 32 bits)))).asBits
    // ── Task 14b: FMOVE FPn,<ea> (opclass 011) -- the narrowing conversion ───────────
    // `DecOp.FPSTORECVT`: convert the 80-bit FPn latched at S0 into ONE 32-bit chunk of
    // the destination format and complete it as an ordinary int-lane result. This is a
    // deliberate SIBLING of `isFpCtrlRd` above, not a divergent shape: same lane, same
    // single-cycle S1 arm, same `captureComplete`. The one structural difference is that
    // it reads the FP data file instead of the FPCC file, which needs no new port --
    // `fpRdA.addr` is already `u0.pFpSrcA` unconditionally (see the FP lane below), and
    // this uop declares a real `usesFpSrcA`, so rename hands it a genuine `pFpSrcA` and
    // the CPLX dynamic-wakeup scoreboard gates issue exactly as for any FP consumer.
    val isFpStoreCvt = u1.op === DecOp.FPSTORECVT
    val fpNarrow = new FpNarrowPack
    fpNarrow.io.src   := s1FpSrc
    fpNarrow.io.fmt   := u1.fpSrcFmt          // opclass 011: the DESTINATION format
    fpNarrow.io.chunk := u1.imm(1 downto 0).asUInt
    fpNarrow.io.rmode := fpRmodeIn
    // Register-direct `.W`/`.B` destinations are a PARTIAL-register write (only the low
    // word/byte of Dn changes) -- the ordinary 68k data-register rule, and the one place
    // this core deliberately diverges from Musashi, whose `WRITE_EA_16`/`WRITE_EA_8`
    // assign `REG_D[reg] = data` from a uint16/uint8 and therefore ZERO-EXTEND over the
    // whole register (Divergence Register D11). Every MEMORY row is `Size.LONG` here (its
    // own `MStore` row carries the real access size and does the truncation), so the merge
    // is unreachable for them by construction.
    val fpCvtRaw = fpNarrow.io.word
    val fpStoreValue = u1.size.mux(
      Size.WORD -> (s1A(31 downto 16) ## fpCvtRaw(15 downto 0)),
      Size.BYTE -> (s1A(31 downto  8) ## fpCvtRaw( 7 downto 0)),
      default   -> fpCvtRaw)
    // FPSR EXC accrual for this lane, in the architectural FPSR[15:8] order. Registered
    // one cycle so it lines up with the ordinary completion delivery; merged (OR-ed, which
    // is lossless because `FpuControlService.orFpsrExc` IS an OR-accumulate) with the FP
    // lane's own accrual at the port drive below.
    val fpStoreExcVld = RegInit(False)
    val fpStoreExcReg = Reg(Bits(8 bits)) init 0
    fpStoreExcVld := False
    // `FpNarrowPack` carries ONE registered stage (see its header for the synth evidence
    // that forced the split), so an `FPSTORECVT` occupies S1 for two cycles: the first
    // feeds the converter's stage register from the already-stable `s1FpSrc`, the second
    // completes. `s1Valid` is held across both, which is what blocks a new CPLX issue --
    // the same single-outstanding contract the legacy lane already has. Cost is one cycle
    // per conversion row, invisible next to the store rows that follow it.
    val fpCvtWait = RegInit(False)
    when(flushSig) { fpCvtWait := False }
    // FLUSH CONTRACT for the two-cycle conversion (Task 14b review, Critical #1).
    //
    // The clear above is NOT self-sufficient: it sits EARLIER in the same clocked block
    // than the FSM arm below, so a later `fpCvtWait := True` in the FSM wins (SpinalHDL /
    // Verilog last-write-wins within one `always`). The FSM's continuation arm must
    // therefore never run on a flushed cycle -- if it did, two real failures followed from
    // an ordinary branch mispredict:
    //   1. `flushed` LEAKS. `flushed` is set by `when(flushSig && (busy || s1Valid))` above
    //      and is consumed ONLY by `captureComplete`/`captureFault`. Every other S1 arm
    //      (CHK / CMP2 / FPCTRLRD / DIVREM / ...) captures unconditionally whenever
    //      `s1Valid` is set, INCLUDING on a flushed cycle -- the capture is stamped
    //      `legacyResult.flushed := flushed || flushSig` so it is discarded downstream, and
    //      the latch is cleared. A continuation arm that captures nothing leaves the latch
    //      set with no owner, and the NEXT legacy-lane op -- a correct-path one -- inherits
    //      it, so ITS completion/writeback/wakeup are all suppressed by `compFlushed` and
    //      its ROB entry never retires: a hard deadlock.
    //   2. `fpCvtWait` STICKS at 1, so the next `FPSTORECVT` takes the "read the result"
    //      branch on its FIRST S1 cycle and samples `FpNarrowPack`'s output register, which
    //      was clocked from the PREVIOUS cycle's `s1FpSrc` -- a stale value, stored to
    //      memory.
    // The fix is to take the same capture-and-DISCARD path every sibling arm already takes:
    // `fpCvtFlushing` forces the completion branch on a flushed cycle, which stamps the
    // result flushed (nothing is delivered), clears `flushed`, clears `fpCvtWait`, and drops
    // `s1Valid` -- leaving the lane in exactly the state a flush must leave it in.
    val fpCvtFlushing = flushed || flushSig

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

    // ═════════════════════════════════════════════════════════════════════════
    // FP LANE. A second, fully parallel writeback lane: its own descriptor pipe, its
    // own completion register, its own ROB completion / regfile write / wakeup / fault
    // ports. It shares ONLY the single CPLX issue port with DIV/MUL/CHK/CMP2/CHK2, so
    // nothing above this point changes behaviour for a non-FP uop.
    // ═════════════════════════════════════════════════════════════════════════
    val fpu = new FpuCore()

    // ---- S0: register-file reads (issue cycle) ------------------------------
    // FPn (the destination read back, dyadic ops only) and FPm (fpSrcKind === FPREG).
    fpRdA.addr := u0.pFpSrcA
    fpRdB.addr := u0.pFpSrcB

    val fpAccept     = issuePort.fire && issueIsFp
    val fpFixedStart = fpAccept && issueIsFpFixed
    val fpIterStart  = fpAccept && issueIsFpIter

    def loadFpCtx(c: FpPipeContext): Unit = {
      c.robId     := issuePort.payload.robId
      c.pdst      := u0.pFpDst
      c.pdstValid := u0.pFpDstValid
      c.pFpccDst  := u0.pFpccDst
      c.fpccWrite := u0.writesFpcc
    }

    // ---- FS1: the FP lane's ISSUE REGISTER (task #218, FMax closure) --------
    // WHY THIS STAGE EXISTS, and why it captures the RAW operands rather than the converted
    // 80-bit value. Everything the FpSource gateway does used to run in the ISSUE cycle,
    // hanging off the integer PRF's BYPASS output -- i.e. combinationally off AluEuPlugin's
    // S1 result cone, with no flip-flop anywhere between the ALU's own context registers and
    // the FP pipes' first-stage classification registers. On the FPU-integration branch that
    // was the design's single worst path (post-route, `fc6ffe3_fpu_postrouteN9_decode_fetch/
    // fullcore_route_timing.rpt`): AluEuPlugin_logic_s1Ctx_uop_op_reg[0] ->
    // DivEuPlugin_logic_fpu/mulPipe/m0_sClz_reg[2], 6.652ns over 27 logic levels,
    // WNS -2.669ns (149.95 MHz, vs a 201.450 MHz pre-FPU baseline).
    //
    // That report also gives the delay BREAKDOWN, which is what fixes the cut point here:
    //   ALU result cone + int-PRF bypass mux ...... 2.834ns  (up to `DivEuPlugin_logic_s1A`)
    //   the FpSource conversion cone below ........ 3.169ns  (`fpFromInt`/`srcR`, ~15 levels)
    //   FpMulPipe's first-stage CLZ/classify ...... 0.649ns  (`m0_sInf`/`m0_sClz`)
    // So registering the CONVERTED value (`fpSrcVal`, i.e. at FpuCore's port) would have left
    // a 5.97ns first half and bought ~0.65ns. Registering the RAW operands here -- BEFORE the
    // conversion cone -- splits the path at its real midpoint into 2.83ns + 3.85ns, which
    // takes this family off the critical list outright instead of shaving its tail.
    //
    // COST, stated honestly: +1 cycle on EVERY FP issue. The fixed lane's EU-visible latency
    // is FpuCore.FixedLatency + 1, and FDIV/FSQRT begin iterating one cycle later.
    //
    // GC-F2 (FpuCore.FixedLatency is a hard constant the descriptor shadow pipe is sized
    // from): the pipe below is STILL exactly FpuCore.FixedLatency deep, because FpuCore
    // itself is unchanged -- what moved is WHEN the pipe is pushed. It is pushed on
    // `fpS1Fixed`, the cycle `fpu.io.start` actually fires, NOT on the original `fpAccept`.
    // Pushing it on `fpAccept` while `fpu.io.start` fires a cycle later would misalign the
    // shadow pipe against the real pipeline by exactly one cycle and silently associate every
    // fixed-lane completion with the WRONG robId/pdst/pFpccDst (mutation-killed, task #218
    // Step 4: FpuProtocolSpec's dense-8-fill and 4-in-flight tests both fail on it).
    //
    // Operand SAMPLING TIME is unchanged: every value below is captured on the accepting
    // edge, exactly as before -- only its use moves a cycle later.
    val fpS1Valid  = RegNext(fpAccept) init False
    val fpS1Fixed  = RegNext(fpFixedStart) init False
    val fpS1Ctx    = Reg(FpPipeContext())
    val fpS1IntA   = Reg(Bits(32 bits))     // rdA: int source / Double+Extended chunk T0
    val fpS1IntB   = Reg(Bits(32 bits))     // rdB: Double low half / Extended chunk T1
    val fpS1IntC   = Reg(Bits(32 bits))     // rdH: Extended chunk T2
    val fpS1FpDst  = Reg(Bits(80 bits))     // fpRdA: FPn read back for dyadic ops
    val fpS1FpSrc  = Reg(Bits(80 bits))     // fpRdB: FPm (fpSrcKind === FPREG)
    val fpS1Imm    = Reg(Bits(80 bits))     // u0.fpWideImm
    val fpS1Kind   = Reg(FpSrcKind())
    val fpS1Fmt    = Reg(Bits(3 bits))
    val fpS1Opmode = Reg(Bits(7 bits))      // u0.fpuOp: the FpOp selector AND io.cromSel
    val fpS1Rmode  = Reg(Bits(2 bits))      // FPCR[5:4] as it stood at ISSUE, not at start
    when(fpAccept) {
      fpS1IntA   := rdA.data
      // `rdB.data` RAW rather than `s0B`: the microcode-emitted FP rows set useImm=True to
      // carry the packed FP command word, which would otherwise mux the int immediate over
      // the real T1 chunk.
      fpS1IntB   := rdB.data
      fpS1IntC   := rdH.data
      fpS1FpDst  := fpRdA.data
      fpS1FpSrc  := fpRdB.data
      fpS1Imm    := u0.fpWideImm
      fpS1Kind   := u0.fpSrcKind
      fpS1Fmt    := u0.fpSrcFmt
      fpS1Opmode := u0.fpuOp
      fpS1Rmode  := fpRmodeIn
      loadFpCtx(fpS1Ctx)
    }

    // ---- FS1: source-operand gateway (conversion cone) ---------------------
    // The INT-register-borne source kinds all read the ORDINARY int PRF ports this EU
    // already holds. `rdH`'s address is `u0.psrcC` UNCONDITIONALLY (shared with DIVL's
    // 64-bit dividend high word and CMP2/CHK2's compared Rn), so MEMEXT's third chunk needs
    // no new port and no mutual-exclusion argument at all: there is exactly one port, its
    // address expression is the same in every case, and only the DOWNSTREAM consumer
    // differs. Same for rdA/rdB.
    // Byte/Word integer sources are SIGNED (Musashi m68kfpu.c:1221-1222,1234-1235 reads
    // them as sint16/sint8 before int32_to_floatx80); the width selector is `fpSrcFmt`,
    // NOT `size`, because the microcode memory path leaves `size` at its ROM-row default.
    val fpFmt = fpS1Fmt
    val fpIntFromReg = fpFmt.mux(
      B"3'b100" -> fpS1IntA(15 downto 0).asSInt.resize(32).asBits,   // Word, sign-extended
      B"3'b110" -> fpS1IntA( 7 downto 0).asSInt.resize(32).asBits,   // Byte, sign-extended
      default   -> fpS1IntA)                                          // Long (000) / unused
    // One converter instance per FORMAT, with the register-vs-immediate choice muxed on the
    // INPUT side -- three conversion cones, not six.
    val fpIntIn = Mux(fpS1Kind === FpSrcKind.INTIMM, fpS1Imm(31 downto 0), fpIntFromReg)
    val fpSglIn = Mux(fpS1Kind === FpSrcKind.SINGLEIMM, fpS1Imm(31 downto 0), fpS1IntA)
    val fpDblIn = Mux(fpS1Kind === FpSrcKind.DOUBLEIMM, fpS1Imm(63 downto 0),
                                                        fpS1IntA ## fpS1IntB)
    val fpFromInt = FpSource.intToExtended(fpIntIn)
    val fpFromSgl = FpSource.singleToExtended(fpSglIn)
    val fpFromDbl = FpSource.doubleToExtended(fpDblIn)
    val fpSrcVal = fpS1Kind.mux(
      FpSrcKind.FPREG     -> fpS1FpSrc,
      // INTREG covers BOTH `F<op>.<fmt> Dn,FPn` and the 1-chunk memory formats (the crack's
      // load already parked the value in a temp int reg, making the two indistinguishable).
      // Single (fmt 001) is a 32-bit BIT PATTERN, not an integer.
      FpSrcKind.INTREG    -> Mux(fpFmt === B"3'b001", fpFromSgl, fpFromInt),
      FpSrcKind.INTIMM    -> fpFromInt,
      FpSrcKind.SINGLEIMM -> fpFromSgl,
      FpSrcKind.DOUBLEIMM -> fpFromDbl,
      // Extended immediate / Extended memory load: the internal Fp80 layout already.
      // MEMEXT is pure bit placement -- {T0[31:16], T1, T2} IS the 80-bit value.
      FpSrcKind.EXTIMM    -> fpS1Imm,
      FpSrcKind.MEMPAIR   -> fpFromDbl,
      FpSrcKind.MEMEXT    -> (fpS1IntA(31 downto 16) ## fpS1IntB ## fpS1IntC),
      // ROMCONST (FMOVECR): no source operand at all -- io.cromSel carries the ROM offset.
      FpSrcKind.ROMCONST  -> B(0, 80 bits))

    val fpS1IsRomConst = fpS1Kind === FpSrcKind.ROMCONST
    // NOT gated on `flushSig`. An accepted request must always reach FpuCore, exactly as it
    // did when `start` was `fpAccept`: the ITERATIVE lane's `fpIterBusy` is already set (see
    // below) and only clears on a real `doneIter`, so suppressing the start of a request that
    // was flushed one cycle after acceptance would wedge that lane permanently. Wrong-path
    // FIXED results are dropped by the shadow pipe's flush clear instead, as before.
    fpu.io.start   := fpS1Valid
    fpu.io.op      := FpSource.opmodeToFpOp(fpS1Opmode, fpS1IsRomConst)
    fpu.io.dst     := fpS1FpDst
    fpu.io.src     := fpSrcVal
    fpu.io.rmode   := fpS1Rmode
    fpu.io.cromSel := fpS1Opmode      // meaningful only when fpSrcKind === ROMCONST

    // ---- fixed lane: a descriptor shadow pipe, sized from FpuCore.FixedLatency ----
    // Identical construction to mulCtx/mulCtxValid above (load index 0 at start, shift up,
    // read `.last` on done); FpuCore.io.doneFixed is an unconditional 1-cycle pulse exactly
    // FixedLatency cycles after an accepted start, in issue order, so `.last` is the
    // matching descriptor by construction.
    // The push is on `fpS1Fixed` -- the cycle `fpu.io.start` fires -- NOT on `fpFixedStart`.
    // See the FS1 comment above for why that one cycle is load-bearing (GC-F2).
    val fpFixedCtx      = Vec.fill(FpuCore.FixedLatency)(Reg(FpPipeContext()))
    val fpFixedCtxValid = Vec.fill(FpuCore.FixedLatency)(RegInit(False))
    fpFixedCtxValid(0) := fpS1Fixed
    when(fpS1Fixed) { fpFixedCtx(0) := fpS1Ctx }
    for (i <- 1 until FpuCore.FixedLatency) {
      fpFixedCtxValid(i) := fpFixedCtxValid(i - 1)
      when(fpFixedCtxValid(i - 1)) { fpFixedCtx(i) := fpFixedCtx(i - 1) }
    }
    when(flushSig) { fpFixedCtxValid.foreach(_ := False) }

    // ---- iterative lane: ONE held context for its whole data-dependent duration ----
    val fpIterCtx = Reg(FpPipeContext())
    // FpDivSqrtCore has NO flush/abort input, by design (it is deliberately ROB-unaware).
    // Its `done` is a LEVEL held until `ack`, and `busy` stays high across that hold, so a
    // flushed FDIV/FSQRT MUST STILL BE ACKNOWLEDGED when its real `doneIter` eventually
    // arrives or the iterative engine wedges for the rest of the program. Therefore a flush
    // does NOT clear `fpIterBusy` (which would make `fpIterDone` -- and with it `iterAck` --
    // unreachable forever); it sets this sticky POISON bit instead, which suppresses only
    // the architectural side effects. `fpIterBusy` then clears on the real completion, at
    // which point the lane is genuinely reusable.
    val fpIterFlushed = RegInit(False)
    // DELIBERATELY still on `fpIterStart` (the ACCEPT cycle), not on FS1 like the fixed
    // lane's shadow pipe, and the asymmetry is load-bearing in both directions:
    //  * `fpIterBusy` MUST be set at acceptance, because it IS the issue-port's admission
    //    gate (`issuePort.ready`, above). Moving it to FS1 would leave a one-cycle window in
    //    which a SECOND FDIV/FSQRT is admitted while the first is still in the issue
    //    register -- and FpDivSqrtCore's own `start` is swallowed while it is busy, so the
    //    second op would vanish and its robId would never complete.
    //  * `fpIterCtx` needs no shift-alignment at all (unlike the fixed pipe): it is a SINGLE
    //    held context, and `fpIterBusy` -- set on this very cycle -- already excludes any
    //    other iterative op from overwriting it before the result lands.
    when(fpIterStart) {
      fpIterBusy := True
      fpIterFlushed := False
      loadFpCtx(fpIterCtx)
    }
    when(flushSig && fpIterBusy) { fpIterFlushed := True }

    // ---- completion arbitration, FP-internal only ----
    // The fixed lane cannot be back-pressured (its `doneFixed` is an unconditional pulse) so
    // it wins; the iterative lane CAN wait (its `done` is a held level) so it retries next
    // cycle. Starvation is bounded rather than merely unlikely: an FP op younger than the
    // in-flight FDIV cannot retire ahead of it, so a continuous fixed-FP stream fills the
    // 64-entry ROB, dispatch stalls, `doneFixed` goes quiet, and the iterative result lands.
    val fpFixedDone = fpu.io.doneFixed && fpFixedCtxValid.last
    val fpIterDone  = fpu.io.doneIter && fpIterBusy
    // Acknowledge exactly when the result is CONSUMED: written back, or discarded because it
    // is wrong-path. Never on a cycle when the fixed lane took the writeback register and the
    // iterative result still has to be delivered -- that would silently drop it.
    val fpIterTake  = fpIterDone && (fpIterFlushed || flushSig || !fpFixedDone)
    fpu.io.iterAck := fpIterTake
    when(fpIterTake) { fpIterBusy := False; fpIterFlushed := False }

    // A second, independent completion register. compValid/compData/... above remain
    // exclusively the int/NZVC 32-bit path and are untouched by this lane.
    val fpCompValid     = RegInit(False)
    val fpCompRobId     = Reg(UInt(6 bits))
    val fpCompPdst      = Reg(UInt(4 bits))
    val fpCompPdstValid = RegInit(False)
    val fpCompData      = Reg(Bits(80 bits))
    val fpCompFpccDst   = Reg(UInt(4 bits))
    val fpCompFpccWrite = RegInit(False)
    val fpCompFpcc      = Reg(Bits(4 bits))
    val fpCompFault     = RegInit(False)
    val fpCompFaultVec  = Reg(UInt(8 bits))
    // Task 14c: the architectural FPSR EXC byte for the delivered op (see
    // `fpExcAccrualPort`'s doc for the bit order and why BSUN/INEX1 are structurally 0).
    val fpCompExc       = Reg(Bits(8 bits)) init 0
    fpCompValid := False
    fpCompFault := False

    // Enabled-trap escalation. FpuCore has no fault output at all: the OVFL/UNFL
    // SUBSTITUTION (design Decision 10) already happened inside it and `res.value` is the
    // substituted result, which is the whole point -- the baseline path must never trap, or
    // every overflow re-introduces the per-op trap cost this design exists to remove. Only a
    // program that explicitly set the matching FPCR enable bit gets vectored here.
    // Priority is the MC68040's simultaneous-exception order (SNAN, OPERR, OVFL, UNFL, DZ,
    // INEX); BSUN is a branch-side condition and INEX1 is packed-decimal-only, so neither
    // can originate in this lane and neither is present in FpExcFlags.
    // Evaluated unconditionally (once per FpuCore result port) rather than inside the
    // capture branch, so `vec` is a plain fully-driven combinational signal.
    case class FpEscalation() extends Bundle { val esc = Bool(); val vec = UInt(8 bits) }
    /** Task 14b factored this out of `fpEscalation(res)` so a SECOND raising site (the int
      * lane's narrowing converter, which produces an `FpExcFlags` but no `FpResult`) shares
      * the identical enable gate and priority order instead of growing a parallel one. */
    def fpEscalationOf(exc: FpExcFlags): FpEscalation = {
      val en = fpExcEnableIn
      val r  = FpEscalation()
      r.esc := (exc.snan  && en.snan)  || (exc.operr && en.operr) ||
               (exc.ovfl  && en.ovfl)  || (exc.unfl  && en.unfl)  ||
               (exc.dz    && en.dz)    || (exc.inex2 && en.inex2)
      when(exc.snan && en.snan)          { r.vec := U(FpVector.Snan,  8 bits) }
        .elsewhen(exc.operr && en.operr) { r.vec := U(FpVector.Operr, 8 bits) }
        .elsewhen(exc.ovfl  && en.ovfl)  { r.vec := U(FpVector.Ovfl,  8 bits) }
        .elsewhen(exc.unfl  && en.unfl)  { r.vec := U(FpVector.Unfl,  8 bits) }
        .elsewhen(exc.dz    && en.dz)    { r.vec := U(FpVector.Dz,    8 bits) }
        .otherwise                       { r.vec := U(FpVector.Inex,  8 bits) }
      r
    }
    def fpEscalation(res: FpResult): FpEscalation = fpEscalationOf(res.exc)
    val fpEscFixed = fpEscalation(fpu.io.resFixed)
    val fpEscIter  = fpEscalation(fpu.io.resIter)
    // Task 14b: the same gate applied to the narrowing converter's own flags. An enabled
    // OPERR/OVFL/UNFL/INEX on an `FMOVE FPn,<ea>` vectors from the CONVERSION uop, which
    // is strictly OLDER than the `MStore` row(s) that would have written memory -- so the
    // ROB's excSquash kills the store and the trap is precise (nothing was written).
    val fpStoreEsc = fpEscalationOf(fpNarrow.io.exc)

    /** FpExcFlags -> architectural FPSR[15:8] EXC byte (MC68040 UM Figure 9-5:
      * 15 BSUN, 14 SNAN, 13 OPERR, 12 OVFL, 11 UNFL, 10 DZ, 9 INEX2, 8 INEX1). This is a
      * genuine RE-ORDER, not a reinterpretation of the bundle's own bit packing --
      * `FpExcFlags` is a plain declaration-ordered Bundle with no architectural meaning
      * attached to its flattened layout, so the mapping is written out field by field. */
    def fpsrExcByte(e: FpExcFlags): Bits =
      False ## e.snan ## e.operr ## e.ovfl ## e.unfl ## e.dz ## e.inex2 ## False
    /** Task 14b: the narrowing converter's own EXC byte, in the same architectural order. */
    val fpStoreExcNow = fpsrExcByte(fpNarrow.io.exc)

    def fpWriteback(ctx: FpPipeContext, res: FpResult, esc: FpEscalation): Unit = {
      fpCompExc       := fpsrExcByte(res.exc)
      fpCompValid     := True
      fpCompRobId     := ctx.robId
      fpCompPdst      := ctx.pdst
      fpCompPdstValid := ctx.pdstValid
      fpCompData      := res.value
      fpCompFpccDst   := ctx.pFpccDst
      fpCompFpccWrite := ctx.fpccWrite
      fpCompFpcc      := res.fpcc
      fpCompFault     := esc.esc
      fpCompFaultVec  := Mux(esc.esc, esc.vec, U(0, 8 bits))
    }

    when(!flushSig) {
      when(fpFixedDone) {
        fpWriteback(fpFixedCtx.last, fpu.io.resFixed, fpEscFixed)
      } elsewhen(fpIterDone && !fpIterFlushed) {
        fpWriteback(fpIterCtx, fpu.io.resIter, fpEscIter)
      }
    }
    when(flushSig) { fpCompValid := False }

    // ---- drive the FP lane's external ports ----
    // No per-descriptor `flushed` latch is needed here (unlike the int lane's compFlushed):
    // every FP capture happens in a `!flushSig` cycle from a source whose own valid the same
    // flush clears, so a wrong-path result can never reach this register in the first place.
    val fpCompLive = fpCompValid && !flushSig
    fpCompletionPort.valid   := fpCompLive
    fpCompletionPort.payload := fpCompRobId
    // `!fpCompFault` mirrors the int lane exactly: a faulting uop's rename rolls back at the
    // exception, so its pdst is never live for a surviving consumer.
    fpW.valid   := fpCompLive && fpCompPdstValid && !fpCompFault
    fpW.address := fpCompPdst
    fpW.data    := fpCompData
    fpccW.valid   := fpCompLive && fpCompFpccWrite && !fpCompFault
    fpccW.address := fpCompFpccDst
    fpccW.data    := fpCompFpcc
    fpWakeupPort.valid     := fpW.valid
    fpWakeupPort.payload   := fpCompPdst
    fpccWakeupPort.valid   := fpccW.valid
    fpccWakeupPort.payload := fpCompFpccDst
    fpFaultPort.valid             := fpCompLive && fpCompFault
    fpFaultPort.payload.robId     := fpCompRobId
    fpFaultPort.payload.vector    := fpCompFaultVec
    fpFaultPort.payload.faultAddr := U(0, 32 bits)
    // Task 14c: FPSR exception-status accrual. Deliberately NOT gated on `!fpCompFault`:
    // when a trap IS enabled the handler must still find the EXC bit that caused it (the
    // FPSP reads FPSR.EXC to dispatch), so an escalating op accrues exactly like a
    // substituting one -- the enable byte selects whether a VECTOR is also taken, not
    // whether the status bit is recorded.
    //
    // Task 14b: the int lane's narrowing converter is a SECOND raising site on this same
    // port. Merging them is lossless rather than an arbitration problem, because the sink
    // (`FpuControlService.orFpsrExc`) is an OR-ACCUMULATE into FPSR.EXC -- so a same-cycle
    // collision between an FP-lane result and an `FMOVE FPn,<ea>` conversion is resolved by
    // OR-ing the two bytes, which is exactly what two separate sequential accruals would
    // have produced. Each side contributes 0 when it is not delivering.
    val fpStoreAccrualLive = fpStoreExcVld && !flushSig && (fpStoreExcReg =/= 0)
    val fpLaneAccrualLive  = fpCompLive && (fpCompExc =/= 0)
    fpExcAccrualPort.valid   := fpLaneAccrualLive || fpStoreAccrualLive
    fpExcAccrualPort.payload := Mux(fpLaneAccrualLive,  fpCompExc,     B(0, 8 bits)) |
                                Mux(fpStoreAccrualLive, fpStoreExcReg, B(0, 8 bits))

    // ---- sim-only whitebox for the FP lane (zero synth impact) ----
    fpCompValid.simPublic(); fpCompRobId.simPublic(); fpCompData.simPublic()
    fpCompPdst.simPublic(); fpCompPdstValid.simPublic()
    fpCompFpcc.simPublic(); fpCompFpccDst.simPublic(); fpCompFpccWrite.simPublic()
    fpCompFault.simPublic(); fpCompFaultVec.simPublic(); fpCompExc.simPublic()
    fpIterBusy.simPublic(); fpIterFlushed.simPublic(); fpIterTake.simPublic()
    fpu.io.busyIter.simPublic(); fpu.io.doneIter.simPublic()
    fpSrcVal.simPublic()   // the source-operand gateway's converted 80-bit output

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
          } elsewhen(isFpCtrlRd) {
            // ── Task 9b: FMOVEM control-register LIST form, STORE direction ──────────
            // Single-cycle read of the `pos`-th SELECTED control register into an int
            // temp. Both operands ride the imm: `mask` = ext[12:10] {FPCR,FPSR,FPIAR}
            // MSB-first, `pos` = this row's transfer POSITION in the list.
            //
            // Position -> register uses the architectural order (M68000PRM p. 5-91,
            // Divergence Register D9): FPCR is always transferred first, FPSR second,
            // FPIAR last, so a selected register's position is simply the number of
            // selected registers ahead of it. That one runtime mux is what lets all seven
            // masks share three popcount-keyed microcode programs.
            //
            // FPSR's condition-code nibble is NOT part of `fpuCtrl.fpsr` (that register
            // masks [27:24] off itself) -- FPCC is renamed, so it comes from the FPCC PRF
            // read this µop declared a real `readsFpcc` dependency on, reversed back to
            // the architectural {N,Z,I,NaN} bit order exactly as ExceptionUnit's own
            // `fpsrArch` does for the single-register read direction.
            captureComplete(fpCtrlRdValue, B(0, 4 bits), False, True)
            s1Valid := False
          } elsewhen(isFpStoreCvt) {
            // ── Task 14b: FMOVE FPn,<ea> -- one 32-bit chunk of the narrowed value ────
            // Single-cycle, exactly like the `isFpCtrlRd` sibling above. On an ENABLED
            // exception the uop takes an EU fault instead of completing: its `pdst` write
            // is suppressed and every younger uop (including this program's own `MStore`
            // rows) is squashed, so the trap is precise -- memory is untouched. With the
            // trap DISABLED the substituted/saturated value is stored and only the FPSR
            // EXC accrual below records what happened, mirroring the FP lane's own
            // Decision-10 posture.
            //
            // `fpCvtFlushing` (see its declaration for the full rationale) forces the
            // completion branch on a FLUSHED cycle even when the converter has not run yet:
            // the capture is a pure DISCARD -- `captureComplete` stamps
            // `legacyResult.flushed` so no port is driven, and clears the `flushed` latch
            // that would otherwise leak onto the next, correct-path legacy op. Never take
            // the escalation branch on such a cycle: its inputs are a half-run conversion.
            when(fpCvtWait || fpCvtFlushing) {
              when(fpStoreEsc.esc && !fpCvtFlushing) {
                captureFault(fpStoreEsc.vec, B(0, 4 bits), False)
              } otherwise {
                captureComplete(fpStoreValue, B(0, 4 bits), False, True)
              }
              when(!fpCvtFlushing) {
                fpStoreExcVld := True
                fpStoreExcReg := fpStoreExcNow
              }
              fpCvtWait := False
              s1Valid   := False
            } otherwise {
              fpCvtWait := True     // converter stage 0 -> its register this cycle
            }
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
