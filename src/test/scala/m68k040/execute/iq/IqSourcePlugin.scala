package m68k040.execute.iq

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** Test-only source: drives the IssueQueue push port from top-level IO.
  * All RenamedUop fields are exposed per push-slot so Task 2 can reuse this,
  * but Task 1 only needs robId (everything else defaults independent/ready). */
class IqSourcePlugin extends FiberPlugin {
  val logic = during build new Area {
    val iq = host[IssueQueueService]

    val pushValid = in Bool ()
    val slot1Valid = in Bool ()
    val flush = in Bool ()
    val pushReady = out Bool ()

    // Per-slot driving signals (k = 0, 1).
    case class SlotIo() {
      val robId      = in UInt (6 bits)
      val cluster    = in(m68k040.isa.Cluster())
      val memOp      = in(m68k040.isa.MemOp())
      val pdst       = in UInt (6 bits); val pdstValid  = in Bool ()
      val psrcA      = in UInt (6 bits); val psrcAValid = in Bool ()
      val psrcB      = in UInt (6 bits); val psrcBValid = in Bool ()
      val useImm     = in Bool ()
      val readsNzvc  = in Bool (); val writesNzvc = in Bool ()
      val pNzvcSrc   = in UInt (4 bits); val pNzvcDst = in UInt (4 bits)
      val readsX     = in Bool (); val writesX = in Bool ()
      val pXSrc      = in UInt (4 bits); val pXDst = in UInt (4 bits)
      // True => this uop is a line-E SHIFT (the slow-ALU S3 producer); else MOVE.
      val isShift    = in Bool ()
      // FP-data and FPCC push fields (Task 3): mirrors psrcA/pdst/pNzvc shapes, 4-bit
      // FP tags (16 physical FP-data / FPCC entries).
      val pFpSrcA    = in UInt (4 bits); val psrcAFpValid = in Bool ()
      val pFpSrcB    = in UInt (4 bits); val psrcBFpValid = in Bool ()
      val pFpDst     = in UInt (4 bits); val pFpDstValid  = in Bool ()
      val pFpccSrc   = in UInt (4 bits); val readsFpcc    = in Bool ()
      val pFpccDst   = in UInt (4 bits); val writesFpcc   = in Bool ()
    }
    val s0 = SlotIo()
    val s1 = SlotIo()

    def mkSlot(io: SlotIo): IqContext = {
      val c = IqContext()
      c.robId := io.robId
      val u = c.uop
      // Drive EVERY field first, then override the ones this harness cares about.
      //
      // This used to be a hand-written list of "safe defaults". RenamedUop then GREW -- it
      // is 84 fields now, the list reached 44 -- and the ~40 fields it never learned about
      // were left undriven, so SpinalHDL's PhaseCheck_noLatchNoOverride failed elaboration
      // with `NO DRIVER ON ... RenamedUop`. That killed EVERY spec in this directory:
      // IssueQueueSpec, IqAluSlowSpec, IqLsSpec, IqCplxSpec, IqFpSpec, IqColdPayloadSpec --
      // 20 tests -- and did it silently, because a suite that cannot elaborate reports
      // FAILURES, which read like broken behaviour rather than absent coverage.
      //
      // Zeroing the whole bundle in one line cannot drift: a field added to RenamedUop
      // tomorrow is driven here today. The explicit assignments below still win, since in
      // SpinalHDL the later assignment takes precedence.
      // `allowOverride` is required: SpinalHDL's noLatchNoOverride check treats a whole-
      // bundle default followed by field overrides as an ASSIGNMENT OVERLAP, even though
      // the later assignment is exactly the intended winner.
      u.allowOverride()
      u := u.getZero
      // Safe defaults for unused fields so the bundle is fully driven.
      u.valid        := True
      // `pc` is a COLD field (it lives only in the cold payload Mem, never in the narrow
      // IqHot record), so a test that checks it has genuinely checked the Mem read. Derived
      // from robId rather than taken as a NEW INPUT PORT, deliberately: every pre-existing
      // spec in this directory has its own `idle()` and would leave a new port undriven,
      // and SpinalSim gives an undriven input a per-seed RANDOM value, not 0. That is not
      // hypothetical -- an earlier revision of this file did add `pc`/`isBranch` as inputs
      // and turned IqLsSpec/IqCplxSpec/IssueQueueSpec into ~40%-pass seed lotteries (a
      // randomly-True `isBranch` re-routes an LS uop onto the branch port, so the LS port
      // never fires). Deriving from an input every spec already drives makes that whole
      // failure class unreachable.
      u.pc           := io.robId.resized
      u.op      := Mux(io.isShift, m68k040.decode.DecOp.SHIFT, m68k040.decode.DecOp.MOVE)
      u.cluster := io.cluster
      u.memOp   := io.memOp
      u.size    := m68k040.isa.Size.LONG
      u.imm          := 0
      // Branch-class marker for the standalone harness, encoded as cluster === EA. `EA` is
      // read by NO IQ predicate (isLs wants LS, isCplx wants CPLX) and is driven by no spec
      // in this directory, so it is a free encoding slot -- and, unlike a new input port, it
      // is a value every spec already drives. Lets a test exercise issue port 2.
      u.isBranch     := (io.cluster === m68k040.isa.Cluster.EA)
      u.cond         := 0
      u.branchDisp   := 0
      u.unimplemented := False
      u.ibranch := False; u.anInc := 0; u.stkPush := False; u.ccrRestore := False
      u.dstArch      := 0
      u.pdstOld      := 0
      u.pNzvcOld     := 0
      u.pXOld        := 0
      // Wired fields.
      u.useImm       := io.useImm
      u.pdst         := io.pdst;     u.pdstValid  := io.pdstValid
      u.psrcA        := io.psrcA;    u.psrcAValid := io.psrcAValid
      u.psrcB        := io.psrcB;    u.psrcBValid := io.psrcBValid
      u.readsNzvc    := io.readsNzvc;  u.writesNzvc := io.writesNzvc
      u.pNzvcSrc     := io.pNzvcSrc;   u.pNzvcDst   := io.pNzvcDst
      u.readsX       := io.readsX;     u.writesX    := io.writesX
      u.pXSrc        := io.pXSrc;      u.pXDst      := io.pXDst
      u.pFpSrcA := io.pFpSrcA; u.psrcAFpValid := io.psrcAFpValid
      u.pFpSrcB := io.pFpSrcB; u.psrcBFpValid := io.psrcBFpValid
      u.pFpDst  := io.pFpDst;  u.pFpDstValid  := io.pFpDstValid
      u.pFpccSrc := io.pFpccSrc; u.readsFpcc  := io.readsFpcc
      u.pFpccDst := io.pFpccDst; u.writesFpcc := io.writesFpcc
      u.pFpOld   := 0; u.pFpccOld := 0; u.fpDstArch := 0    // rename bookkeeping only; the IQ never reads them
      // FP-generic op-identity fields (Task 4/6, `DecodedUop.fpuOp`/`fpSrcKind`/
      // `fpSrcFmt`/`fpWideImm`, carried VERBATIM through rename -- unlike `fpSrcAReg`/
      // `fpDstReg`/`usesFpSrcA`/`usesFpSrcB`, which rename replaces with the physical FP
      // tags above, these four are opcode/format IDENTITY, not register references, so
      // rename passes them through unchanged). PRE-EXISTING GAP found 2026-08-15 while
      // investigating a Task 6b regression: these were NEVER added here when Task 4/6
      // landed them on `RenamedUop` (same "fields added after this stub was first
      // written" gap this file's own header already anticipates) -- left genuinely
      // undriven, which happened to stay harmless while `FpSrcKind` was 3 bits wide, but
      // is exactly the "every bundle field needs a driver" hazard this file's comments
      // warn about. Confirmed via a clean-baseline bisection (`git stash`): once Task 6b
      // widens `FpSrcKind` 3->4 bits (2 new elements, MEMPAIR/MEMEXT), the previously-
      // dormant undriven fields perturbed `IqCplxSpec`/`IqAluSlowSpec` (unrelated
      // arbitration-timing tests, not exercising anything FP-specific) -- fixed here by
      // finally giving them the same safe-default treatment as every other post-hoc field.
      u.fpuOp := 0; u.fpSrcKind := m68k040.decode.FpSrcKind.FPREG; u.fpSrcFmt := 0; u.fpWideImm := 0
      // Third source (DIV.L 64/32) + CPLX/div control + precise-fault fields: safe
      // defaults (these IQ tests don't exercise DIV/CHK/faults).
      u.psrcC        := 0; u.psrcCValid := False
      u.divSigned    := False; u.div64 := False; u.divIsRem := False
      u.isChk2 := False
      u.nextPc       := 0
      u.faulted      := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.isRte        := False; u.isCondTrap := False; u.isScc := False; u.isDbcc := False
      u.sswInstr := False
      u.firstOfInstr := True
      // SAME "fields added after this stub was written" gap as the fpuOp/fpSrcKind block
      // below, found the same way: six fields landed by the FPU (Task 10/11) and debug-
      // halt work were never back-filled here. They stayed dormant only because nothing
      // in the standalone IQ harness read them, so SpinalHDL dead-code-eliminated the
      // whole per-bit chain before the latch check ran. The IQ cold-payload split ends
      // that: a `Mem` write port is a single object with a full-width data input, so
      // every bit must have a driver whether or not any reader survives -- which is
      // precisely why this latent harness gap surfaced now rather than staying buried.
      // (Confirmed harness-only: GenFullCoreSynthVerilog elaborates clean, because the
      // real decode/rename path drives all six.)
      u.fpuSoftwareComplete := False; u.fpuCmdWord := 0
      u.faultAtc            := True   // MicroOpAssembler's universal default
      u.lastOfInstr         := True
      u.debugBreakValid     := False; u.debugBreakSlot := 0
      u.predTaken    := False; u.predTarget := 0
      // Fields added to RenamedUop after this stub was first written (shifts/BCD/bit-ops/
      // bit-field/MOVEA/LEA/MOVE-from-CCR-SR/system-ops/indexed-EA/gshare). None feed the
      // IQ issue/wakeup logic under test, but every bundle field needs a driver (else the
      // slot Reg is undriven -> PhaseCheck_noLatchNoOverride). Drive safe defaults.
      u.eaAuto       := m68k040.decode.EaAuto.NONE
      u.eaDelta      := 0
      u.toCcr        := False
      u.shiftOp      := 0; u.shiftDir := False
      u.bcdSub       := False
      u.bitOp        := 0
      u.bfOp         := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0
      u.extByte      := False
      u.isMovea      := False
      u.indexLong    := False; u.indexScale := 0
      u.leaAddr      := False
      u.movesAliasStore := False; u.altAddrSpace := False
      u.fromCcr      := False; u.fromSr := False; u.needsSupervisor := False
      u.keepCommit   := False
      u.sysOp        := False; u.sysKind := m68k040.decode.SysKind.NONE; u.sysReadDir := False
      u.phtValid     := False; u.phtIndex := 0
      u.casForm      := 0
      c
    }

    iq.push.valid       := pushValid
    iq.pushSlot1Valid   := slot1Valid
    iq.flushPort        := flush
    iq.push.payload(0)  := mkSlot(s0)
    iq.push.payload(1)  := mkSlot(s1)
    pushReady           := iq.push.ready

    // Per-EU next-cycle fast acceptance (sim-driven).  These emulate the two ALU
    // pipeline look-ahead bits so candidate-mask behavior can be tested directly.
    val aluFastAccept0 = in Bool ()
    val aluFastAccept1 = in Bool ()
    iq.aluFastAcceptNext(0) := aluFastAccept0
    iq.aluFastAcceptNext(1) := aluFastAccept1

    // Dynamic LS wakeup (sim-driven; overrides the IQ's idle default).
    val lsWakeupValid = in Bool (); val lsWakeupPdst = in UInt (6 bits)
    iq.lsWakeup.valid   := lsWakeupValid
    iq.lsWakeup.payload := lsWakeupPdst

    // Dynamic SLOW-ALU (shift) wakeup (sim-driven; emulates the ALU EU's S3 broadcast).
    val aluSlowWakeupValid = in Bool ()
    val aluSlowWakeupPdst  = in UInt (6 bits); val aluSlowWakeupPdstV = in Bool ()
    val aluSlowWakeupNzvc  = in UInt (4 bits); val aluSlowWakeupNzvcV = in Bool ()
    val aluSlowWakeupX     = in UInt (4 bits); val aluSlowWakeupXV    = in Bool ()
    // Drive port 0 (emulating eu0); port 1 keeps the IQ idle default (False).
    iq.aluSlowWakeup(0).valid            := aluSlowWakeupValid
    iq.aluSlowWakeup(0).payload.pdst     := aluSlowWakeupPdst
    iq.aluSlowWakeup(0).payload.pdstValid:= aluSlowWakeupPdstV
    iq.aluSlowWakeup(0).payload.pNzvcDst := aluSlowWakeupNzvc
    iq.aluSlowWakeup(0).payload.nzvcValid:= aluSlowWakeupNzvcV
    iq.aluSlowWakeup(0).payload.pXDst    := aluSlowWakeupX
    iq.aluSlowWakeup(0).payload.xValid   := aluSlowWakeupXV

    // Dynamic CPLX FP-DATA / FPCC wakeup (sim-driven; overrides the IQ's idle default).
    val cplxFpWakeupValid = in Bool (); val cplxFpWakeupTag = in UInt (4 bits)
    iq.cplxFpWakeup.valid   := cplxFpWakeupValid
    iq.cplxFpWakeup.payload := cplxFpWakeupTag
    val cplxFpccWakeupValid = in Bool (); val cplxFpccWakeupTag = in UInt (4 bits)
    iq.cplxFpccWakeup.valid   := cplxFpccWakeupValid
    iq.cplxFpccWakeup.payload := cplxFpccWakeupTag
  }
}
