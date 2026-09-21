package m68k040.execute.iq

import m68k040.rename.RenamedUop
import spinal.core._
import spinal.lib._

case class IqContext() extends Bundle {
  val uop   = RenamedUop()
  val robId = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
}

/** The NARROW per-slot record: exactly the `IqContext` fields the issue queue's OWN
  * combinational logic reads, plus the address of the cold (dispatch-only) remainder.
  *
  * WHY THIS EXISTS (see IssueQueuePlugin's "cold payload store" block for the full
  * argument). The IQ is a NaxRiscv-style COMPACTING queue: on every `push.fire` each
  * slot is rewritten from the line above, so the slot array is a 16-deep SHIFT REGISTER
  * over whatever it stores. Storing the whole `IqContext` there cost 16 x 418 = 6,688 FF
  * in the routed netlist, and connecting all 16 slots to all 5 select ports with 16:1
  * `MuxOH`s over that full width cost ~6,875 LUT -- the structure the 2026-08-25 census
  * named as the reason the IQ cluster spans 68 x 111 slices (family #17, section 4.5).
  *
  * But only a small minority of the context is actually READ by the IQ. Everything here
  * is read EVERY CYCLE by the per-slot wakeup CAM, the class/select masks, or the
  * retimed C+1 scoreboard clear. Everything NOT here is pure dispatch payload: written
  * once at push, read once at issue, and otherwise just shifted. That half now lives in
  * a robId-addressed `Mem` (LUTRAM) instead of a shift register, and only `robId` +
  * `coldWay` (7 bits) travel through the select cone to fetch it.
  *
  * THE PARTITION IS COMPILE-TIME CHECKED IN THE SAFE DIRECTION. If a field belongs in
  * the hot set and is missing here, the IQ's own logic fails to compile (there is no
  * such member). If a field is here that need not be, the only cost is area. There is NO
  * silent-wrong-data failure mode from getting the split wrong, which is exactly why the
  * issue-side payload is reassembled as a WHOLE-uop `Mem` read rather than by
  * re-merging hot fields over cold ones. */
case class IqHot() extends Bundle {
  val intW  = 6
  val flagW = 4
  val fpW   = 4

  // ---- Wakeup-CAM sources. Compared against every wakeup broadcast, for every slot,
  // every cycle (lsWakeMatch / cplxWakeMatch / aluSlowWakeMatch / the *NzvcWakeMatch /
  // cplxFpWakeMatch / cplxFpccWakeMatch loops, and their `*Remaining` re-evaluations). ----
  val psrcA    = UInt(intW bits);  val psrcAValid    = Bool()
  val psrcB    = UInt(intW bits);  val psrcBValid    = Bool()
  val psrcC    = UInt(intW bits);  val psrcCValid    = Bool()
  val pNzvcSrc = UInt(flagW bits); val readsNzvc     = Bool()
  val pXSrc    = UInt(flagW bits); val readsX        = Bool()
  val pFpSrcA  = UInt(fpW bits);   val psrcAFpValid  = Bool()
  val pFpSrcB  = UInt(fpW bits);   val psrcBFpValid  = Bool()
  val pFpccSrc = UInt(fpW bits);   val readsFpcc     = Bool()

  // ---- Class / select-mask fields. cluster/memOp/leaAddr are `isLs`/`isCplx`. ----
  //
  // The IQ SCHEDULES; it does not execute. Every question it used to ask of `op` was a
  // latency/dependency-CLASS question, so those answers are precomputed ONCE on the push
  // path (see assignFrom) and read here as plain flops:
  //
  //   isAluSlow          SHIFT -- four-stage EU path, dynamic slow wakeup (BITFIELD
  //                      left this path for the CPLX cluster; see DecOp.isAluSlow)
  //   srcBRegDespiteImm  PACK | UNPK | BITFIELD | BFRESOLVE -- useImm=True yet psrcB IS a
  //                      live register read (the EU reads s1RdB directly, bypassing the
  //                      useImm mux), so its srcB dependency must not be suppressed
  //   isDivFam           DIV | DIVREM      -- the one-at-a-time divide family
  //
  // WHY, measured rather than stylistic: `op` is 6 bits, and testing it inside a per-slot
  // cone drags its MuxOH into that cone. Exactly one such term --
  // `op === SHIFT || op === BITFIELD` in the scoreboard-clear cone -- was measured
  // post-route (xcku5p-ffvb676-2 @4.000ns, checkpoint 3cba17f) as the design's WNS holder:
  // 9 of the 10 worst paths at -1.699ns, against -1.518ns with that arc disabled. The fix
  // at the time was a comment telling future readers not to re-add the term. Precomputing
  // the class deletes the mechanism instead of warning about it.
  //
  // `op` itself remains only for the simPublic debug/whitebox hooks. It has NO functional
  // reader in the IQ, so it contributes nothing to the select, wakeup or scoreboard cones.
  val op       = m68k040.decode.DecOp()
  val isAluSlow         = Bool()
  val srcBRegDespiteImm = Bool()
  val isDivFam          = Bool()
  // Eligibility for the optional address-before-data store path. Decode once at
  // dispatch; the hot select cone only consumes this bit.
  val canEarlyStoreData = Bool()
  val cluster  = m68k040.isa.Cluster()
  val memOp    = m68k040.isa.MemOp()
  val leaAddr  = Bool()
  val isBranch = Bool()
  val useImm   = Bool()

  // ---- Destinations. Read by the RETIMED (C+1) static-scoreboard clear, which decodes
  // off the registered select-port payload. Keeping these in the hot record is what
  // preserves task #219 Fix 2 exactly: that clear still reads a plain flop, never a
  // memory, so no LUTRAM level is added to the sb*_busy cone it was created to shorten. ----
  val pdst     = UInt(intW bits);  val pdstValid   = Bool()
  val pNzvcDst = UInt(flagW bits); val writesNzvc  = Bool()
  val pXDst    = UInt(flagW bits); val writesX     = Bool()
  val pFpDst   = UInt(fpW bits);   val pFpDstValid = Bool()
  val pFpccDst = UInt(fpW bits);   val writesFpcc  = Bool()

  // ---- Cold-payload address. `robId` is the Mem index; `coldWay` selects which of the
  // two single-write-port banks holds it (see IssueQueuePlugin for why the banking is by
  // PUSH WAY and not by robId parity). ----
  val robId    = UInt(m68k040.Global.ROB_ID_W_DEFAULT bits)
  val coldWay  = Bool()

  /** Project the wide dispatch record onto the hot fields. The ONLY writer of an IqHot,
    * so the hot copy and the cold Mem row are written from the same source in the same
    * cycle and can never disagree. */
  def assignFrom(ctx: IqContext, way: Bool): Unit = assignFrom(ctx, way, false)
  def assignFrom(ctx: IqContext, way: Bool, earlyAutoStoreAddress: Boolean): Unit = {
    val u = ctx.uop
    psrcA := u.psrcA; psrcAValid := u.psrcAValid
    psrcB := u.psrcB; psrcBValid := u.psrcBValid
    psrcC := u.psrcC; psrcCValid := u.psrcCValid
    pNzvcSrc := u.pNzvcSrc; readsNzvc := u.readsNzvc
    pXSrc    := u.pXSrc;    readsX    := u.readsX
    pFpSrcA  := u.pFpSrcA;  psrcAFpValid := u.psrcAFpValid
    pFpSrcB  := u.pFpSrcB;  psrcBFpValid := u.psrcBFpValid
    pFpccSrc := u.pFpccSrc; readsFpcc := u.readsFpcc
    op := u.op; cluster := u.cluster; memOp := u.memOp
    // Scheduling classes, derived ONCE here instead of in every slot's cone.
    isAluSlow         := m68k040.decode.DecOp.isAluSlow(u.op)
    canEarlyStoreData := u.cluster === m68k040.isa.Cluster.LS &&
      u.memOp === m68k040.isa.MemOp.STORE && u.op === m68k040.decode.DecOp.MOVE &&
      u.psrcBValid && !u.stkPush &&
      ((u.eaAuto === m68k040.decode.EaAuto.NONE && !u.pdstValid) ||
        (Bool(earlyAutoStoreAddress) && u.eaAuto === m68k040.decode.EaAuto.POSTINC && u.pdstValid)) &&
      !u.movesAliasStore && !u.altAddrSpace && !u.needsSupervisor && !u.sysOp &&
      !u.leaAddr
    srcBRegDespiteImm := (u.op === m68k040.decode.DecOp.PACK)     ||
                         (u.op === m68k040.decode.DecOp.UNPK)     ||
                         (u.op === m68k040.decode.DecOp.BITFIELD) ||
                         (u.op === m68k040.decode.DecOp.BFRESOLVE)
    isDivFam          := (u.op === m68k040.decode.DecOp.DIV) ||
                         (u.op === m68k040.decode.DecOp.DIVREM)
    leaAddr := u.leaAddr; isBranch := u.isBranch; useImm := u.useImm
    pdst     := u.pdst;     pdstValid   := u.pdstValid
    pNzvcDst := u.pNzvcDst; writesNzvc  := u.writesNzvc
    pXDst    := u.pXDst;    writesX     := u.writesX
    pFpDst   := u.pFpDst;   pFpDstValid := u.pFpDstValid
    pFpccDst := u.pFpccDst; writesFpcc  := u.writesFpcc
    robId := ctx.robId
    coldWay := way
  }
}

/** Dynamic-completion wakeup for the four-stage SLOW ALU path. A SHIFT
  * produces its destinations atomically at S3, so the broadcast carries all
  * three physreg dsts (with per-class valid). The IQ clears its per-class slow-busy
  * bitmaps + any dependent's `aluSlowWait` matching ANY of the three. (Unlike LS/DIV,
  * which write only an int reg, a shift also writes the flag PRFs — hence the bundle.) */
case class AluSlowWakeup() extends Bundle {
  val pdst      = UInt(6 bits); val pdstValid = Bool()
  val pNzvcDst  = UInt(4 bits); val nzvcValid = Bool()
  val pXDst     = UInt(4 bits); val xValid     = Bool()
}

trait IssueQueueService {
  def push: Stream[Vec[IqContext]]   // length 2
  def pushSlot1Valid: Bool
  def issue: Vec[Stream[IqContext]]  // length 5 (ALU0, ALU1, branch, LS, CPLX/DivEu)
  /** Per-ALU look-ahead from the execution pipelines.  True means a FAST uop
    * selected now is guaranteed to leave the registered issue stage next cycle. */
  def aluFastAcceptNext: Vec[Bool]
  def flushPort: Bool
  /** LS integer-result wakeup: the producer broadcasts a physical destination
    * whose writeback is available now, or guaranteed on the next cycle when
    * earlyIntWakeup is enabled. Registered dependency clear and issue selection
    * must keep every consumer's operand capture after that writeback. This is
    * not a speculative cache-hit prediction. */
  def lsWakeup: Flow[UInt]
  /** LS NZVC readiness, separate from integer lsWakeup: an operation may produce
    * either or both. Writeback is available now, or guaranteed next cycle with
    * earlyNzvcWakeup. As for lsWakeup, registered dependency clear and selection
    * must keep operand capture after writeback, including newly pushed consumers.
    * The static latency-1 scoreboard does not release these dependencies. */
  def lsNzvcWakeup: Flow[UInt]
  /** Dynamic-completion wakeup for the CPLX cluster (DivEu): a multi-cycle DIV
    * broadcasts the pdst of its just-completed quotient/remainder; slots reading
    * that physreg become ready. Separate Flow from lsWakeup so a same-cycle LS load
    * + DIV completion never collide on one wakeup port. */
  def cplxWakeup: Flow[UInt]
  /** Dynamic-completion NZVC wakeup for the CPLX cluster (DivEu): broadcasts the
    * pNzvcDst of a just-completed CPLX op that writes flags (DIV/MUL normal + overflow,
    * CHK, CMP2/CHK2 — everything on the CPLX/DivEu port except the trailing DIVREM/MULHI
    * crack µops, which write no flags). Task #167 (ported-tests triage): before this
    * port existed, a CPLX NZVC producer was tracked ONLY in the static (latency-1)
    * `sbNzvc` scoreboard, whose busy bit is cleared the cycle the op ISSUES to DivEu
    * (task #141's `!slowFire` clear loop), not when its multi-cycle FSM actually
    * completes — correct only for CMP2/CHK2 (near-single-cycle) and accidentally
    * unnoticed for DIV/MUL (whose flags land many cycles later) because every prior
    * test happened to need a POST-op flag value equal to the STALE pre-op one. A
    * `bvc`/`bvs` immediately after a genuine DIV/MUL overflow (V=1) exposed it: the
    * branch read the stale (pre-multiply) flags instead of waiting for the real
    * writeback. Mirrors `lsNzvcWakeup` exactly, on the CPLX port instead of LS. */
  def cplxNzvcWakeup: Flow[UInt]
  /** Dynamic-completion wakeup for the SLOW ALU path: each ALU EU broadcasts the
    * int+NZVC+X dsts of its just-completed SHIFT at S3. A dependent
    * (int OR flag source) is held NOT-ready until a matching broadcast fires. The op
    * is tracked in SEPARATE slow-busy bitmaps (NOT the static latency-1 scoreboards), so
    * a dependent wakes from actual completion, not a static latency. ONE port per ALU EU (both can
    * complete a distinct shift the same cycle). */
  def aluSlowWakeup: Vec[Flow[AluSlowWakeup]]
  /** Dynamic-completion FP-DATA wakeup for the CPLX cluster: a completing FP op
    * (FADD/FSUB/FMUL/FDIV/FSQRT/FABS/FNEG/FMOVE/FINT/FINTRZ/FMOVECR) broadcasts the
    * 4-bit pFpDst of its just-written 80-bit FP physreg; a slot reading that FP physreg
    * becomes ready. SEPARATE Flow from `cplxWakeup` (int pdst): an FP op writes NO int
    * register at all, and the int and FP physreg id spaces are unrelated (6-bit int tags
    * vs 4-bit FP tags), so sharing one port would alias two different registers.
    * FP ops are variable-latency (FDIV/FSQRT iterate) and must NOT use the static
    * latency-1 scoreboard, whose busy bit is force-cleared at ISSUE time -- the exact
    * defect task #167 fixed for CPLX NZVC. FCMP/FTST drive no FP wakeup (no FP dst). */
  def cplxFpWakeup: Flow[UInt]
  /** Dynamic-completion FPCC wakeup for the CPLX cluster: broadcasts the pFpccDst of a
    * just-completed FP op that wrote the renamed FPCC {N,Z,I,NAN}. EVERY hardware-native
    * FP op writes FPCC (including FCMP/FTST, which write ONLY FPCC), so this port fires
    * for strictly more ops than `cplxFpWakeup`. Mirrors `cplxNzvcWakeup` exactly, on the
    * FPCC rename class instead of NZVC. */
  def cplxFpccWakeup: Flow[UInt]
}
