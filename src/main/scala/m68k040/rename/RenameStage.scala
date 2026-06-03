package m68k040.rename

import m68k040.services.{DecodeUopService, RenameUopService, RenameCommitService}
import m68k040.rob.CommitSlot
import spinal.core._
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
class RenameStage extends FiberPlugin with RenameUopService with RenameCommitService {

  val logic = during build new Area {
    val du = host[DecodeUopService]

    // ── RATs (int RAT: 4 src reads + 2 dst-old reads = 6 read ports) ─────────
    val intRat  = RatTable(physIdWidth = 6, archDepth = 18, writePorts = 2, commitPorts = 2, readPorts = 6)
    val nzvcRat = RatTable(physIdWidth = 4, archDepth = 1,  writePorts = 2, commitPorts = 2, readPorts = 2)
    val xRat    = RatTable(physIdWidth = 4, archDepth = 1,  writePorts = 2, commitPorts = 2, readPorts = 2)

    // ── Freelists ────────────────────────────────────────────────────────────
    val intFree  = Freelist(physCount = 50, archCount = 18, popPorts = 2, pushPorts = 2)
    val nzvcFree = Freelist(physCount = 16, archCount = 1,  popPorts = 2, pushPorts = 2)
    val xFree    = Freelist(physCount = 16, archCount = 1,  popPorts = 2, pushPorts = 2)

    // ── flush / commit ports ──────────────────────────────────────────────────
    // Plain directionless service wires (RenameCommitService): ROB (a sibling
    // plugin) drives these; this stage consumes them. Standalone rename tests
    // poke them in sim (simPublic).
    val flush = Bool()
    val commitPorts = Vec.fill(2)(Flow(CommitSlot()))

    // ── Committed-identity init ────────────────────────────────────────────────
    // Counter 0..17 drives intRat.commits(0) with (addr=i, data=i) — identity for
    // D0-7/A0-7 AND the two temp arch regs T0/T1 (16,17); the flag RATs commit
    // (addr 0, data 0) on the first cycle. Gate normal operation until done.
    val initDone    = Reg(Bool()) init False
    val initCounter = Reg(UInt(5 bits)) init 0   // 0..17
    when(!initDone) {
      when(initCounter === U(17)) {
        initDone := True
      }
      initCounter := initCounter + 1
    }

    // ── Default flow valids for commit ports (driven by the init/commit mux) ───
    for (w <- 0 until 2) {
      intRat.io.commits(w).valid  := False
      intRat.io.commits(w).payload.assignDontCare()
      nzvcRat.io.commits(w).valid := False
      nzvcRat.io.commits(w).payload.assignDontCare()
      xRat.io.commits(w).valid    := False
      xRat.io.commits(w).payload.assignDontCare()
    }
    // Close the freelist loop: free old pdsts of retired instructions.
    for (k <- 0 until 2) {
      intFree.io.push(k).valid   := commitPorts(k).valid && commitPorts(k).intWrite
      intFree.io.push(k).payload := commitPorts(k).intOld
      nzvcFree.io.push(k).valid   := commitPorts(k).valid && commitPorts(k).nzvcWrite
      nzvcFree.io.push(k).payload := commitPorts(k).nzvcOld
      xFree.io.push(k).valid   := commitPorts(k).valid && commitPorts(k).xWrite
      xFree.io.push(k).payload := commitPorts(k).xOld
    }

    // ── Rollback / flush wiring ────────────────────────────────────────────────
    intRat.io.rollback  := flush
    nzvcRat.io.rollback := flush
    xRat.io.rollback    := flush
    intFree.io.flush  := flush
    nzvcFree.io.flush := flush
    xFree.io.flush    := flush

    // ── Output stream ──────────────────────────────────────────────────────────
    val uopsPort = Stream(Vec(RenamedUop(), 2))

    // Stream handshake / init gating
    val freeReady = intFree.io.popReady && nzvcFree.io.popReady && xFree.io.popReady
    // freeReady must gate the OUTPUT valid as well as the input ready. Otherwise a
    // downstream consumer (DispatchPlugin) that does not itself observe freeReady
    // could fire on uopsPort while du.uops does NOT fire (freeReady low) — the
    // frontend would then NOT advance (feed.fire is gated by du.uops.ready which
    // includes freeReady) and re-present the SAME packet next cycle, dispatching it
    // twice (the duplicate-instruction bug). Tying valid to freeReady keeps the
    // upstream feed.fire and the downstream dispatch.fire in lock-step.
    uopsPort.valid := du.uops.valid && initDone && freeReady
    du.uops.ready  := initDone && uopsPort.ready && freeReady
    val fire = du.uops.fire
    val uop1Sig = du.uops.valid && du.uop1Valid

    // ── Per-slot rename ────────────────────────────────────────────────────────
    // Build raw (pre-bypass) renamed uops, then apply intra-group bypass for slot1.
    val raw = Vec(RenamedUop(), 2)

    for (s <- 0 until 2) {
      val dec = du.uops.payload(s)
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
      // flag src reads (single arch entry, addr 0)
      nzvcRat.io.reads(s).addr := 0
      xRat.io.reads(s).addr    := 0

      // copy decoded fields
      r.valid        := dec.valid
      r.pc           := dec.pc
      r.op           := dec.op
      r.cluster      := dec.cluster
      r.size         := dec.size
      r.memOp        := dec.memOp
      r.useImm       := dec.useImm
      r.imm          := dec.imm
      r.isBranch     := dec.isBranch
      r.cond         := dec.cond
      r.branchDisp   := dec.branchDisp
      r.unimplemented:= dec.unimplemented

      // architectural int dst reg (threaded for commit RAT update + CommitTrace)
      r.dstArch    := dec.dstReg

      // int operands
      r.psrcA      := intRat.io.reads(2 * s).data
      r.psrcAValid := dec.srcAValid
      r.psrcB      := intRat.io.reads(2 * s + 1).data
      r.psrcBValid := dec.srcBValid

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
    }

    // ── Intra-group hazards (slot1 reads slot0's writes) ───────────────────────
    val dec0 = du.uops.payload(0)
    val dec1 = du.uops.payload(1)
    val slot0 = raw(0)
    val slot1 = raw(1)

    // int RAW
    when(dec0.dstValid && dec0.dstReg === dec1.srcAReg) { slot1.psrcA := slot0.pdst }
    when(dec0.dstValid && dec0.dstReg === dec1.srcBReg) { slot1.psrcB := slot0.pdst }
    // int dst-old: slot1 overwrites a reg slot0 also wrote -> old is slot0's pdst
    when(dec0.dstValid && dec0.dstReg === dec1.dstReg)  { slot1.pdstOld := slot0.pdst }
    // flag RAW (single arch entry)
    when(dec0.writesNzvc) { slot1.pNzvcSrc := slot0.pNzvcDst; slot1.pNzvcOld := slot0.pNzvcDst }
    when(dec0.writesX)    { slot1.pXSrc := slot0.pXDst;       slot1.pXOld := slot0.pXDst }

    uopsPort.payload(0) := slot0
    uopsPort.payload(1) := slot1
    uopsPort.payload(0).valid.allowOverride; uopsPort.payload(0).valid := du.uops.valid
    uopsPort.payload(1).valid.allowOverride; uopsPort.payload(1).valid := uop1Sig

    // ── Commit + init mux on intRat.commits(0) ─────────────────────────────────
    when(!initDone) {
      // init drives commits(0) with identity (addr=i, data=i)
      intRat.io.commits(0).valid := True
      intRat.io.commits(0).addr  := initCounter.resized
      intRat.io.commits(0).data  := initCounter.resized
      // flag RATs: seed arch 0 -> phys 0 on the first init cycle
      when(initCounter === U(0)) {
        nzvcRat.io.commits(0).valid := True
        nzvcRat.io.commits(0).addr  := 0
        nzvcRat.io.commits(0).data  := 0
        xRat.io.commits(0).valid    := True
        xRat.io.commits(0).addr     := 0
        xRat.io.commits(0).data     := 0
      }
    } otherwise {
      // normal: commits(0) driven by the commit ports (int RAT only on slot 0
      // because commits(0) is the init-muxed port; nzvc/x committed on both slots).
      intRat.io.commits(0).valid := commitPorts(0).valid && commitPorts(0).intWrite
      intRat.io.commits(0).addr  := commitPorts(0).intArch
      intRat.io.commits(0).data  := commitPorts(0).intNew
    }
    // commits(1) for int and both slots for the flag RATs are not init-muxed.
    when(initDone) {
      intRat.io.commits(1).valid := commitPorts(1).valid && commitPorts(1).intWrite
      intRat.io.commits(1).addr  := commitPorts(1).intArch
      intRat.io.commits(1).data  := commitPorts(1).intNew
    }
    for (k <- 0 until 2) {
      // commits(0) is init-muxed (only drivable once init is done); commits(1) is free.
      val gate = if (k == 0) initDone else True
      when(gate) {
        nzvcRat.io.commits(k).valid := commitPorts(k).valid && commitPorts(k).nzvcWrite
        nzvcRat.io.commits(k).addr  := 0
        nzvcRat.io.commits(k).data  := commitPorts(k).nzvcNew
        xRat.io.commits(k).valid := commitPorts(k).valid && commitPorts(k).xWrite
        xRat.io.commits(k).addr  := 0
        xRat.io.commits(k).data  := commitPorts(k).xNew
      }
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

  override def commitPorts: Vec[Flow[CommitSlot]] = logic.commitPorts
  override def flushPort:   Bool                  = logic.flush
}
