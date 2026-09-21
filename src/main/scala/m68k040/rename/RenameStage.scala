package m68k040.rename

import m68k040.services.{CommittedMapService, CommittedFpMapService, DecodeUopService, RenameUopService, RenameCommitService}
import m68k040.rob.CommitSlot
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin

/** RenameStage: 2-wide register rename (int + split CCR: NZVC and X).
  *
  * - Three RATs (int, nzvc, x) provide arch->phys mappings with O(1) rollback.
  * - Three freelists allocate physical destinations.
  * - Intra-group hazards: slot1 RAW from slot0's int/flag writes; slot1 pdstOld
  *   bypass when slot0 wrote the same int reg; WAW handled by two distinct
  *   freelist pops (RatTable multi-write-bypass makes slot1 final in the RAT).
  * - Committed-identity init: seeds the int RAT committed RAM with identity
  *   (arch i -> phys i) and the flag RATs (arch 0 -> phys 0) so flush restores
  *   sane mappings. Normal operation gated until init done.
  * - flush: rollback all RATs + flush all freelists.
  * - commit: minimal int-RAT commit port (updates committed mapping).
  */
class RenameStage(val retireWidth: Int = 2, val preparedRetirement: Boolean = false)
    extends FiberPlugin with RenameUopService with RenameCommitService
    with CommittedMapService with CommittedFpMapService with m68k040.services.PreparedCommitService {
  require(if (preparedRetirement) Set(4, 8, 16)(retireWidth) else Set(2, 4)(retireWidth),
    "ordinary retirement supports 2/4 lanes; prepared publication supports caps 4/8/16")

  val logic = during build new Area {
    val du = host[DecodeUopService]
    val mapCommitWidth = if (preparedRetirement) 2 else retireWidth

    // ── RATs (int RAT: 4 srcA/B reads + 2 srcC reads + 2 dst-old reads = 8 ports) ──
    // srcC is the DIVU.L/DIVS.L 64/32 dividend-high (Dr) source (per slot).
    val intRat  = RatTable(physIdWidth = 6, archDepth = m68k040.isa.Isa.ARCH_INT_REGS, writePorts = 2, commitPorts = mapCommitWidth, readPorts = 8, prepared = preparedRetirement)
    val nzvcRat = RatTable(physIdWidth = 4, archDepth = 1,  writePorts = 2, commitPorts = mapCommitWidth, readPorts = 2, prepared = preparedRetirement)
    val xRat    = RatTable(physIdWidth = 4, archDepth = 1,  writePorts = 2, commitPorts = mapCommitWidth, readPorts = 2, prepared = preparedRetirement)
    // FP data RAT (8 arch FP0-FP7, 16 physical). Read-port budget (6): FP macro-ops
    // need at most 2 sources (dyadic FADD/FSUB/FMUL/FDIV) + 1 dst-old read (freelist
    // WAW bookkeeping) per slot -- 2 slots x (2 src + 1 dst-old) = 6, provisioned even
    // though the CPLX cluster can only ISSUE one FP op/cycle (decode/rename still
    // processes 2 macro-ops/cycle and both could be FP-sourced this cycle).
    val fpRat   = RatTable(physIdWidth = 4, archDepth = 8, writePorts = 2, commitPorts = mapCommitWidth, readPorts = 6, prepared = preparedRetirement)
    // FPCC RAT (N/Z/I/NAN, archDepth=1 -- mirrors nzvcRat/xRat exactly).
    val fpccRat = RatTable(physIdWidth = 4, archDepth = 1, writePorts = 2, commitPorts = mapCommitWidth, readPorts = 2, prepared = preparedRetirement)
    // sim-only debug visibility (directed rename-only test, RenameStageFpSpec):
    // committedPhys has no other consumer in this task (no ExceptionUnit-style
    // reader exists yet for FP/FPCC), so it would otherwise be pruned from the
    // isolated-RenameStage sim build.
    spinal.core.sim.SimPublic(fpRat.io.committedPhys, fpccRat.io.committedPhys)
    // STRUCTURAL LOCK-STEP (2026-09-04, NaxRiscv comparison item 2): the committed
    // int/NZVC/X mappings are what turns `CommittedMapService` into a full
    // architectural-register read for the lock-step harness -- `intPhys(i)` names the
    // physical register holding arch reg i, and RegFilePlugin's sim-only `shadow` Vec
    // holds its value. Together they give a 16-register + CCR structural compare that
    // does NOT depend on the DUT volunteering a writeback record (the hole
    // `LockStep.scala:18-19` documents as "the trust model, not full coverage").
    // `intRat.committedPhys(15)` already has a real RTL consumer (committedPhysA7); the
    // rest are pruned from a Verilator build without this, exactly like the FP pair above.
    // Sim-only name preservation: zero synthesis cost.
    spinal.core.sim.SimPublic(intRat.io.committedPhys, nzvcRat.io.committedPhys,
                              xRat.io.committedPhys)

    // ── Freelists ────────────────────────────────────────────────────────────
    // The int pool size is DERIVED, never restated: rename hands out these ids, and the
    // IQ's int busy bitmaps (Global.PHYS_INT_REGS wide) and the int PRF Mem
    // (RegfileSpec.Int.depth) are what they index. An over-sized freelist hands out ids
    // with no busy bit and no PRF row: the busy SET is dropped, the busy READ is 0, so the
    // producer's consumers issue before it has written — a wrong operand with no fault.
    // See Global.PHYS_INT_REGS_DEFAULT for the full note and the two times this shipped.
    val intFree  = Freelist(physCount = m68k040.Global.PHYS_INT_REGS_DEFAULT, archCount = m68k040.isa.Isa.ARCH_INT_REGS, popPorts = 2, pushPorts = retireWidth)
    val nzvcFree = Freelist(physCount = 16, archCount = 1,  popPorts = 2, pushPorts = retireWidth)
    val xFree    = Freelist(physCount = 16, archCount = 1,  popPorts = 2, pushPorts = retireWidth)
    val fpFree   = Freelist(physCount = 16, archCount = 8, popPorts = 2, pushPorts = retireWidth)
    val fpccFree = Freelist(physCount = 16, archCount = 1, popPorts = 2, pushPorts = retireWidth)

    // ── flush / commit ports ──────────────────────────────────────────────────
    // Plain directionless service wires (RenameCommitService): ROB (a sibling
    // plugin) drives these; this stage consumes them. Standalone rename tests
    // poke them in sim (simPublic).
    val flush = Bool()
    val commitPorts = Vec.fill(retireWidth)(Flow(CommitSlot()))
    val preparedPort = if (preparedRetirement) Some(m68k040.services.PreparedCommitPort()) else None
    spinal.core.sim.SimPublic(commitPorts)   // sim-only debug (bf3c bring-up)

    // ── Committed-identity init ────────────────────────────────────────────────
    // Counter 0..(ARCH_INT_REGS-1) drives intRat.commits(0) with (addr=i, data=i) —
    // identity for D0-7/A0-7 AND the four temp arch regs T0/T1/T2/T3 (16,17,18,19);
    // the flag RATs commit (addr 0, data 0) on the first cycle. Gate normal operation
    // until done. The terminal compare is driven by the canonical arch count so a
    // bump of Isa.ARCH_INT_REGS flows here automatically. Counter is 5 bits (0..19 fits).
    val initDone    = Reg(Bool()) init False
    val initCounter = Reg(UInt(5 bits)) init 0   // 0..(ARCH_INT_REGS-1)
    when(!initDone) {
      when(initCounter === U(m68k040.isa.Isa.ARCH_INT_REGS - 1)) {
        initDone := True
      }
      initCounter := initCounter + 1
    }

    // ── Default flow valids for commit ports (driven by the init/commit mux) ───
    for (w <- 0 until mapCommitWidth) {
      intRat.io.commits(w).valid  := False
      intRat.io.commits(w).payload.assignDontCare()
      nzvcRat.io.commits(w).valid := False
      nzvcRat.io.commits(w).payload.assignDontCare()
      xRat.io.commits(w).valid    := False
      xRat.io.commits(w).payload.assignDontCare()
      fpRat.io.commits(w).valid   := False
      fpRat.io.commits(w).payload.assignDontCare()
      fpccRat.io.commits(w).valid := False
      fpccRat.io.commits(w).payload.assignDontCare()
    }
    // Commit records enter each freelist's one-cycle reclamation stage. The
    // committed-pop boundary advances now; old pdsts become available next cycle.
    for (k <- 0 until retireWidth) {
      intFree.io.push(k).valid   := commitPorts(k).valid && commitPorts(k).intWrite
      intFree.io.push(k).payload := commitPorts(k).intOld
      nzvcFree.io.push(k).valid   := commitPorts(k).valid && commitPorts(k).nzvcWrite
      nzvcFree.io.push(k).payload := commitPorts(k).nzvcOld
      xFree.io.push(k).valid   := commitPorts(k).valid && commitPorts(k).xWrite
      xFree.io.push(k).payload := commitPorts(k).xOld
      fpFree.io.push(k).valid   := commitPorts(k).valid && commitPorts(k).fpWrite
      fpFree.io.push(k).payload := commitPorts(k).fpOld
      fpccFree.io.push(k).valid   := commitPorts(k).valid && commitPorts(k).fpccWrite
      fpccFree.io.push(k).payload := commitPorts(k).fpccOld
    }

    // ── Rollback / flush wiring ────────────────────────────────────────────────
    intRat.io.rollback  := flush
    nzvcRat.io.rollback := flush
    xRat.io.rollback    := flush
    fpRat.io.rollback    := flush
    fpccRat.io.rollback  := flush
    intFree.io.flush  := flush
    nzvcFree.io.flush := flush
    xFree.io.flush    := flush
    fpFree.io.flush   := flush
    fpccFree.io.flush := flush

    // ── Output stream ──────────────────────────────────────────────────────────
    val uopsPort = Stream(Vec(RenamedUop(), 2))

    // Stream handshake / init gating
    // EVERY freelist rename can pop from this cycle must be in this gate. Omitting one
    // is a SILENT-CORRUPTION bug, not a throughput bug: Freelist.io.pop(k).id is a bare
    // `ram.readAsync(head + ...)` with no internal underflow guard, so an un-gated pop
    // from an exhausted pool hands out an id that is STILL LIVE (and underflows `count`),
    // putting two in-flight uops on the same physical register. fpFree/fpccFree were
    // missing here when Task 2 landed them; fpFree starts with only 8 free physical FP
    // registers (physCount=16, archCount=8), so any sustained FP-using program hit it
    // almost immediately once Task 8 gave writesFp a real producer.
    val freeReady = intFree.io.popReady && nzvcFree.io.popReady && xFree.io.popReady &&
                    fpFree.io.popReady && fpccFree.io.popReady
    preparedPort.foreach(_.resourcePressure := !freeReady)
    // freeReady must gate the OUTPUT valid as well as the input ready. Otherwise a
    // downstream consumer (DispatchPlugin) that does not itself observe freeReady
    // could fire on uopsPort while du.uops does NOT fire (freeReady low) — the
    // frontend would then NOT advance (feed.fire is gated by du.uops.ready which
    // includes freeReady) and re-present the SAME packet next cycle, dispatching it
    // twice (the duplicate-instruction bug). Tying valid to freeReady keeps the
    // upstream feed.fire and the downstream dispatch.fire in lock-step.
    // ── Tier-1 rename halt (two-tier reschedule, 2026-09-04) ───────────────────
    // Default-driven False (allowOverride) so the wiring plugin can OVERRIDE it from
    // `RobPlugin.logic.earlyPend` (full core); driving it from host[RobPlugin] here
    // would create a Fiber build-order cycle, exactly like `pipeFlush` below.
    //
    // While an early (EU-resolution-time) branch reschedule is pending, rename must
    // FREEZE — no freelist pop, no RAT write, no ROB allocation — while the frontend
    // refills down the corrected path. This is deliberately expressed as one more
    // term on the SAME gate as `freeReady`, and for the same reason that comment
    // gives: it must gate the OUTPUT valid as well as the INPUT ready, or dispatch
    // could fire on `uopsPort` while `du.uops` does not, dispatching the same packet
    // twice. Nothing downstream needs rename to advance in order to retire, so this
    // cannot deadlock; see RobPlugin's `earlyPend` doc comment for the full argument.
    val allocHalt = Bool(); allocHalt.allowOverride; allocHalt := False
    uopsPort.valid := du.uops.valid && initDone && freeReady && !allocHalt
    du.uops.ready  := initDone && uopsPort.ready && freeReady && !allocHalt
    val fire = du.uops.fire
    val uop1Sig = du.uops.valid && du.uop1Valid

    // ── Per-slot rename ────────────────────────────────────────────────────────
    // Build raw (pre-bypass) renamed uops, then apply intra-group bypass for slot1.
    val raw = Vec(RenamedUop(), 2)

    for (s <- 0 until 2) {
      val dec = du.uops.payload(s)
      // The fetch-time prediction record no longer rides the DECODED uop -- it is
      // re-expanded from `dec.brPredTag` by DecodeStage and handed over parallel to the
      // payload (see DecodeUopService.uopPred / DecodedUop.brPredTag). `RenamedUop` is
      // UNCHANGED: it still carries the four fields, so the IQ, the ROB and the branch EU
      // see exactly what they saw before.
      val decPred = du.uopPred(s)
      val r   = raw(s)
      // Resource allocation (freelist pops + RAT writes) for slot 1 must ALSO be
      // gated on uop1Valid — otherwise an empty/invalid second slot whose decoded
      // dstValid/writesNzvc/writesX happen to be set would spuriously pop a pdst
      // (never freed, since it never commits) and drain the freelist. Slot 0 always
      // allocates when the group fires.
      val slotEn = if (s == 0) fire else (fire && uop1Sig)

      // int src reads
      intRat.io.reads(2 * s).addr     := dec.srcAReg
      intRat.io.reads(2 * s + 1).addr := dec.srcBReg
      // int dst-old read
      intRat.io.reads(4 + s).addr     := dec.dstReg
      // int srcC read (DIV.L 64/32 dividend-high Dr; ports 6,7)
      intRat.io.reads(6 + s).addr     := dec.srcCReg
      // flag src reads (single arch entry, addr 0)
      nzvcRat.io.reads(s).addr := 0
      xRat.io.reads(s).addr    := 0
      // FP data RAT: 2 src reads + 1 dst-old read per slot (read-port-budget note
      // above). FPCC RAT: single architectural entry, addr hardwired to 0 (the
      // nzvcRat/xRat pattern).
      fpRat.io.reads(2 * s).addr     := dec.fpSrcAReg
      fpRat.io.reads(2 * s + 1).addr := dec.fpSrcBReg
      fpRat.io.reads(4 + s).addr     := dec.fpDstReg
      fpccRat.io.reads(s).addr := 0

      // copy decoded fields
      r.valid        := dec.valid
      r.pc           := dec.pc
      r.lenWords     := dec.lenWords
      r.op           := dec.op
      r.cluster      := dec.cluster
      r.size         := dec.size
      r.memOp        := dec.memOp
      r.useImm       := dec.useImm
      r.imm          := dec.imm
      r.isBranch     := dec.isBranch
      r.ibranch      := dec.ibranch
      r.anInc        := dec.anInc
      r.isReturn     := dec.isReturn
      r.stkPush      := dec.stkPush
      r.eaAuto       := dec.eaAuto
      r.eaDelta      := dec.eaDelta
      r.ccrRestore   := dec.ccrRestore
      r.toCcr        := dec.toCcr
      r.cond         := dec.cond
      r.unimplemented:= dec.unimplemented
      r.faulted      := dec.faulted
      r.faultVector  := dec.faultVector
      r.faultUsesNextPc := dec.faultUsesNextPc
      r.fpuSoftwareComplete := dec.fpuSoftwareComplete
      r.fpuCmdWord          := dec.fpuCmdWord
      r.sswInstr     := dec.sswInstr
      r.faultAtc     := dec.faultAtc
      r.isRte        := dec.isRte
      r.isCondTrap   := dec.isCondTrap
      r.divSigned    := dec.divSigned
      r.div64        := dec.div64
      r.divIsRem     := dec.divIsRem
      r.isChk2       := dec.isChk2
      r.shiftOp      := dec.shiftOp
      r.shiftDir     := dec.shiftDir
      r.bcdSub       := dec.bcdSub
      r.bitOp        := dec.bitOp
      r.bfOp         := dec.bfOp
      r.bfDynamic    := dec.bfDynamic
      r.bfMem        := dec.bfMem
      r.bfStoreForm  := dec.bfStoreForm
      r.extByte      := dec.extByte
      r.isMovea      := dec.isMovea
      r.isScc        := dec.isScc
      r.isDbcc       := dec.isDbcc
      r.firstOfInstr := dec.firstOfInstr
      r.lastOfInstr  := dec.lastOfInstr
      r.debugBreakValid := dec.debugBreakValid
      r.debugBreakSlot  := dec.debugBreakSlot
      r.indexLong    := dec.indexLong
      r.indexScale   := dec.indexScale
      r.leaAddr         := dec.leaAddr
      r.movesAliasStore := dec.movesAliasStore
      r.altAddrSpace    := dec.altAddrSpace
      r.fromCcr         := dec.fromCcr
      r.fromSr          := dec.fromSr
      r.needsSupervisor := dec.needsSupervisor
      r.keepCommit      := dec.keepCommit
      r.sysOp        := dec.sysOp
      r.sysKind      := dec.sysKind
      r.sysReadDir   := dec.sysReadDir
      r.predTaken    := decPred.predTaken
      r.predTarget   := decPred.predTarget
      r.phtValid     := decPred.phtValid
      r.phtIndex     := decPred.phtIndex
      r.casForm      := dec.casForm
      r.fpuOp        := dec.fpuOp
      r.fpSrcKind    := dec.fpSrcKind
      r.fpSrcFmt     := dec.fpSrcFmt

      // architectural int dst reg (threaded for commit RAT update + CommitTrace)
      r.dstArch    := dec.dstReg

      // int operands
      r.psrcA      := intRat.io.reads(2 * s).data
      r.psrcAValid := dec.srcAValid
      r.psrcB      := intRat.io.reads(2 * s + 1).data
      r.psrcBValid := dec.srcBValid
      r.psrcC      := intRat.io.reads(6 + s).data
      r.psrcCValid := dec.srcCValid
      // debug-only (task #144): does this mu-op's architectural srcBReg (pre-rename)
      // correctly resolve to the LATEST RAT mapping (psrcB, post-rename)?
      dec.pc.simPublic(); dec.srcBReg.simPublic(); dec.srcBValid.simPublic()
      r.psrcB.simPublic(); r.psrcBValid.simPublic(); slotEn.simPublic()

      // int dst allocation
      intFree.io.pop(s).take := slotEn && dec.dstValid
      r.pdst       := intFree.io.pop(s).id
      r.pdstValid  := dec.dstValid
      r.pdstOld    := intRat.io.reads(4 + s).data
      intRat.io.writes(s).valid := slotEn && dec.dstValid
      intRat.io.writes(s).addr  := dec.dstReg
      intRat.io.writes(s).data  := intFree.io.pop(s).id

      // NZVC src + dst
      r.pNzvcSrc  := nzvcRat.io.reads(s).data
      r.readsNzvc := dec.readsNzvc
      nzvcFree.io.pop(s).take := slotEn && dec.writesNzvc
      r.pNzvcDst  := nzvcFree.io.pop(s).id
      r.writesNzvc:= dec.writesNzvc
      r.pNzvcOld  := nzvcRat.io.reads(s).data
      nzvcRat.io.writes(s).valid := slotEn && dec.writesNzvc
      nzvcRat.io.writes(s).addr  := 0
      nzvcRat.io.writes(s).data  := nzvcFree.io.pop(s).id

      // X src + dst
      r.pXSrc  := xRat.io.reads(s).data
      r.readsX := dec.readsX
      xFree.io.pop(s).take := slotEn && dec.writesX
      r.pXDst  := xFree.io.pop(s).id
      r.writesX:= dec.writesX
      r.pXOld  := xRat.io.reads(s).data
      xRat.io.writes(s).valid := slotEn && dec.writesX
      xRat.io.writes(s).addr  := 0
      xRat.io.writes(s).data  := xFree.io.pop(s).id

      // FP data src + dst (Task 6 lands the real decode source: MicroOpAssembler's
      // fpEmit arm drives dec.fpSrcAReg/fpSrcBReg/fpDstReg/usesFpSrcA/usesFpSrcB/
      // writesFp; every non-FP construction site keeps them inert via fpInert()).
      r.pFpSrcA      := fpRat.io.reads(2 * s).data
      r.psrcAFpValid := dec.usesFpSrcA
      r.pFpSrcB      := fpRat.io.reads(2 * s + 1).data
      r.psrcBFpValid := dec.usesFpSrcB
      r.pFpOld       := fpRat.io.reads(4 + s).data
      // architectural FP dst reg (threaded for the commit-time fpRat commit ADDRESS --
      // the FP analog of `r.dstArch := dec.dstReg` above)
      r.fpDstArch    := dec.fpDstReg
      fpFree.io.pop(s).take := slotEn && dec.writesFp
      r.pFpDst       := fpFree.io.pop(s).id
      r.pFpDstValid  := dec.writesFp
      fpRat.io.writes(s).valid := slotEn && dec.writesFp
      fpRat.io.writes(s).addr  := dec.fpDstReg
      fpRat.io.writes(s).data  := fpFree.io.pop(s).id

      // FPCC src + dst
      r.pFpccSrc  := fpccRat.io.reads(s).data
      r.readsFpcc := dec.readsFpcc
      fpccFree.io.pop(s).take := slotEn && dec.writesFpcc
      r.pFpccDst   := fpccFree.io.pop(s).id
      r.writesFpcc := dec.writesFpcc
      r.pFpccOld   := fpccRat.io.reads(s).data
      fpccRat.io.writes(s).valid := slotEn && dec.writesFpcc
      fpccRat.io.writes(s).addr  := 0
      fpccRat.io.writes(s).data  := fpccFree.io.pop(s).id
    }

    // ---- Debug-only rename observability (2026-09-12) ------------------------
    // Added for the FMOVEM.X-then-Bcc defect (fpu_store_then_cmp_bcc.s): a sim needs
    // each renamed uop's pc together with its NZVC source/destination physregs to tell
    // a wrong-VALUE read from a wrong-REGISTER read. Debug only; no hardware consumer.
    val rnDbg = Vec(Seq.tabulate(2) { s =>
      val b = new Bundle {
        val valid     = Bool()
        val pc        = UInt(32 bits)
        val readsNzvc = Bool()
        val writesNzvc= Bool()
        val pNzvcSrc  = UInt(4 bits)
        val pNzvcDst  = UInt(4 bits)
      }
      b
    })
    spinal.core.sim.SimPublic(rnDbg)

    // ── Intra-group hazards (slot1 reads slot0's writes) ───────────────────────
    val dec0 = du.uops.payload(0)
    val dec1 = du.uops.payload(1)
    val slot0 = raw(0)
    val slot1 = raw(1)

    // int RAW
    when(dec0.dstValid && dec0.dstReg === dec1.srcAReg) { slot1.psrcA := slot0.pdst }
    when(dec0.dstValid && dec0.dstReg === dec1.srcBReg) { slot1.psrcB := slot0.pdst }
    when(dec0.dstValid && dec0.dstReg === dec1.srcCReg) { slot1.psrcC := slot0.pdst }
    // int dst-old: slot1 overwrites a reg slot0 also wrote -> old is slot0's pdst
    when(dec0.dstValid && dec0.dstReg === dec1.dstReg)  { slot1.pdstOld := slot0.pdst }
    // flag RAW (single arch entry)
    when(dec0.writesNzvc) { slot1.pNzvcSrc := slot0.pNzvcDst; slot1.pNzvcOld := slot0.pNzvcDst }
    when(dec0.writesX)    { slot1.pXSrc := slot0.pXDst;       slot1.pXOld := slot0.pXDst }
    // FP data RAW/WAW + FPCC RAW (mirrors the int RAW/WAW pattern above; Task 6
    // lands the real dec0/dec1 FP fields).
    when(dec0.writesFp) {
      when(dec0.fpDstReg === dec1.fpSrcAReg) { slot1.pFpSrcA := slot0.pFpDst }
      when(dec0.fpDstReg === dec1.fpSrcBReg) { slot1.pFpSrcB := slot0.pFpDst }
      when(dec0.fpDstReg === dec1.fpDstReg)  { slot1.pFpOld  := slot0.pFpDst }
    }
    when(dec0.writesFpcc) {
      slot1.pFpccSrc := slot0.pFpccDst
      slot1.pFpccOld := slot0.pFpccDst
    }

    // debug-only: capture the POST-bypass rename identities (see rnDbg above)
    rnDbg(0).valid      := fire
    rnDbg(0).pc         := slot0.pc
    rnDbg(0).readsNzvc  := slot0.readsNzvc
    rnDbg(0).writesNzvc := slot0.writesNzvc
    rnDbg(0).pNzvcSrc   := slot0.pNzvcSrc.resized
    rnDbg(0).pNzvcDst   := slot0.pNzvcDst.resized
    rnDbg(1).valid      := uop1Sig
    rnDbg(1).pc         := slot1.pc
    rnDbg(1).readsNzvc  := slot1.readsNzvc
    rnDbg(1).writesNzvc := slot1.writesNzvc
    rnDbg(1).pNzvcSrc   := slot1.pNzvcSrc.resized
    rnDbg(1).pNzvcDst   := slot1.pNzvcDst.resized

    uopsPort.payload(0) := slot0
    uopsPort.payload(1) := slot1
    uopsPort.payload(0).valid.allowOverride; uopsPort.payload(0).valid := du.uops.valid
    uopsPort.payload(1).valid.allowOverride; uopsPort.payload(1).valid := uop1Sig
    // sim-only debug visibility (bf3c bring-up)
    spinal.core.sim.SimPublic(fire, uop1Sig)
    uopsPort.payload.foreach { p =>
      spinal.core.sim.SimPublic(p.pc, p.dstArch, p.pdst, p.pdstOld, p.pdstValid,
                                p.psrcA, p.psrcB, p.psrcC)
      // FP/FPCC rename identity (same zero-synth-cost debug convention as the int
      // fields above): the FP-commit-path regression tests read the tag rename
      // ACTUALLY allocated this cycle to prove distinctness / commit round-tripping.
      spinal.core.sim.SimPublic(p.pFpDst, p.pFpDstValid, p.pFpOld, p.fpDstArch,
                                p.pFpccDst, p.writesFpcc, p.pFpccOld, p.pFpSrcA, p.pFpccSrc)
    }

    // ── Commit + init mux on intRat.commits(0) ─────────────────────────────────
    when(!initDone) {
      // init drives commits(0) with identity (addr=i, data=i)
      intRat.io.commits(0).valid := True
      intRat.io.commits(0).addr  := initCounter.resized
      intRat.io.commits(0).data  := initCounter.resized
      // FP data RAT: identity-seed FP0-FP7 -> phys 0-7 during the FIRST 8 ticks of
      // the SAME initCounter (which already counts 0..19 for the int RAT) --
      // archDepth=8 only needs 0..7, no separate counter required (see task-2 brief
      // §Step 9). Gated additionally so it stops driving once past tick 7.
      when(initCounter < U(8)) {
        fpRat.io.commits(0).valid := True
        fpRat.io.commits(0).addr  := initCounter.resize(3)
        fpRat.io.commits(0).data  := initCounter.resize(4)
      }
      // flag RATs (+ FPCC, archDepth=1 like nzvc/x): seed arch 0 -> phys 0 on the
      // first init cycle.
      when(initCounter === U(0)) {
        nzvcRat.io.commits(0).valid := True
        nzvcRat.io.commits(0).addr  := 0
        nzvcRat.io.commits(0).data  := 0
        xRat.io.commits(0).valid    := True
        xRat.io.commits(0).addr     := 0
        xRat.io.commits(0).data     := 0
        fpccRat.io.commits(0).valid := True
        fpccRat.io.commits(0).addr  := 0
        fpccRat.io.commits(0).data  := 0
      }
    } otherwise {
      // normal: commits(0) driven by the commit ports (int RAT only on slot 0
      // because commits(0) is the init-muxed port; nzvc/x/fpcc committed on both
      // slots via the k-loop below). fpRat mirrors intRat exactly here (both are
      // the archDepth>1 RATs, so both need a real commits(0) normal-op path --
      // deviates from the task-2 brief's Step 10 pseudocode, which only wired
      // fpRat.commits(1): an FP dest committing in SLOT 0 would otherwise never
      // update the FP RAT's committed mapping, a WAW-freelist-recycling gap that
      // is inert for this task [no decode path drives writesFp yet] but would be a
      // real correctness bug once Task 8 lands real FP writeback).
      intRat.io.commits(0).valid := commitPorts(0).valid && commitPorts(0).intWrite
      intRat.io.commits(0).addr  := commitPorts(0).intArch
      intRat.io.commits(0).data  := commitPorts(0).intNew
      fpRat.io.commits(0).valid := commitPorts(0).valid && commitPorts(0).fpWrite
      fpRat.io.commits(0).addr  := commitPorts(0).fpArchDst
      fpRat.io.commits(0).data  := commitPorts(0).fpNew
    }
    // Additional int/FP commit lanes are not init-muxed. Lane order is age
    // order; RatTable's highest-valid-lane priority preserves dense WAW batches.
    when(initDone) {
      for (k <- 1 until mapCommitWidth) {
        intRat.io.commits(k).valid := commitPorts(k).valid && commitPorts(k).intWrite
        intRat.io.commits(k).addr  := commitPorts(k).intArch
        intRat.io.commits(k).data  := commitPorts(k).intNew
        fpRat.io.commits(k).valid := commitPorts(k).valid && commitPorts(k).fpWrite
        fpRat.io.commits(k).addr  := commitPorts(k).fpArchDst
        fpRat.io.commits(k).data  := commitPorts(k).fpNew
      }
    }
    for (k <- 0 until mapCommitWidth) {
      // Lane 0 is init-muxed; the remaining lanes have no identity-seed writes.
      val gate = if (k == 0) initDone else True
      when(gate) {
        nzvcRat.io.commits(k).valid := commitPorts(k).valid && commitPorts(k).nzvcWrite
        nzvcRat.io.commits(k).addr  := 0
        nzvcRat.io.commits(k).data  := commitPorts(k).nzvcNew
        xRat.io.commits(k).valid := commitPorts(k).valid && commitPorts(k).xWrite
        xRat.io.commits(k).addr  := 0
        xRat.io.commits(k).data  := commitPorts(k).xNew
        fpccRat.io.commits(k).valid := commitPorts(k).valid && commitPorts(k).fpccWrite
        fpccRat.io.commits(k).addr  := 0
        fpccRat.io.commits(k).data  := commitPorts(k).fpccNew
      }
    }

    preparedPort.foreach { port =>
      val rats = Seq(intRat, nzvcRat, xRat, fpRat, fpccRat)
      for ((rat, bank) <- rats.zipWithIndex) {
        rat.io.prepareBegin := port.begin
        rat.io.prepareAbort := port.abort
        rat.io.publish := port.publish
        for (lane <- 0 until 2) {
          val p = port.writes(lane).payload
          val w = rat.io.prepare(lane)
          val (write, addr, data) = bank match {
            case 0 => (p.intWrite, p.intArch, p.intNew)
            case 1 => (p.nzvcWrite, U(0, 0 bits), p.nzvcNew)
            case 2 => (p.xWrite, U(0, 0 bits), p.xNew)
            case 3 => (p.fpWrite, p.fpArchDst, p.fpNew)
            case 4 => (p.fpccWrite, U(0, 0 bits), p.fpccNew)
          }
          w.valid := port.writes(lane).valid && write
          w.addr := addr.resized; w.data := data.resized
        }
        // Whole-image publication replaces normal map writes; every free record
        // still travels through commitPorts and the registered reclamation lanes.
        when(port.publish) { rat.io.commits.foreach(_.valid := False) }
      }
      assert(!port.begin || initDone, "map preparation before rename initialization")
    }

    // Flush-able pipeline register (rename -> dispatch boundary). Squashed by the
    // ROB's commit-time mispredict redirect. Default-driven False (allowOverride)
    // so a sibling wiring plugin can OVERRIDE it from RedirectService.doFlush
    // (full core). Driving it here from host[RedirectService] directly would create
    // a Fiber build-order cycle (ROB depends back through rename), so the wire is
    // left for the wiring plugin to drive.
    val pipeFlush = Bool(); pipeFlush.allowOverride; pipeFlush := False
    val uopsStaged = m68k040.frontend.PipeStage(uopsPort, pipeFlush)
  }

  override def uops: Stream[Vec[RenamedUop]] = logic.uopsStaged
  override def uop1Valid: Bool               = logic.uopsStaged.payload(1).valid

  /** COMMITTED phys mapping of arch reg 15 (A7). The exception unit reads the live
    * committed A7 value from the int PRF at this phys to keep ss.usp/isp/msp coherent
    * with the architectural A7 every cycle (driven by the backend wiring layer). */
  def committedPhysA7: UInt = logic.intRat.committedPhys(15)

  /** COMMITTED phys mapping of the singleton NZVC / X "architectural registers"
    * (archDepth=1, always arch index 0). task #176-regression: RTE's CCR restore
    * writes DIRECTLY into whatever physical register these currently name (mirrors
    * committedPhysA7's already-safe pattern) instead of rename-allocating a fresh
    * pNzvcDst/pXDst — see ExceptionUnit.scala's rteNzvcWriteValid doc comment for why
    * the rename-allocation approach was reverted. */
  def committedPhysNzvc: UInt = logic.nzvcRat.committedPhys(0)
  def committedPhysX:    UInt = logic.xRat.committedPhys(0)

  /** COMMITTED phys mapping of the singleton FPCC {NaN,I,Z,N} group (archDepth=1, always
    * arch index 0) — the FP analogue of committedPhysNzvc, and used for exactly the same
    * two jobs by exactly the same mechanism (Task 9's architectural FMOVE to/from FPSR):
    *  - READ: the backend wiring reads the FPCC PRF at this phys every cycle and feeds it
    *    to `ExceptionUnit.committedFpccIn`, so an architectural FPSR read can splice the
    *    live FPCC into FPSR[27:24] (FpuControlPlugin deliberately stores 0 there).
    *  - WRITE: an architectural FPSR write pushes the frame's FPCC nibble DIRECTLY into
    *    this physical register (`ExceptionUnit.fpccWriteValid/Data`) instead of
    *    rename-allocating a fresh pFpccDst — see rteNzvcWriteValid's doc comment for the
    *    confirmed silent-corruption regression that rules the rename-allocation approach
    *    out for a serializing-FSM writer. */
  def committedPhysFpcc: UInt = logic.fpccRat.committedPhys(0)

  // CommittedMapService: the only cross-plugin path to committed integer/CCR maps.
  // Exposing the Vec does not create a second producer; RatTable's commReg remains the
  // sole storage and all consumers receive read-only signals.
  override def intPhys: Vec[UInt] = logic.intRat.io.committedPhys
  override def nzvcPhys: UInt = logic.nzvcRat.committedPhys(0)
  override def xPhys: UInt = logic.xRat.committedPhys(0)
  override def fpPhys: Vec[UInt] = logic.fpRat.io.committedPhys

  override def commitPorts: Vec[Flow[CommitSlot]] = logic.commitPorts
  override def preparedCommit = logic.preparedPort
  override def flushPort:   Bool                  = logic.flush
}
