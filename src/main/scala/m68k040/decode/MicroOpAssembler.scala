package m68k040.decode

import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, Size, MemOp}
import spinal.core._
import spinal.lib._

/** Cracking assembler: combines the EA-agnostic OperationDecoder with the
  * opcode-agnostic EaDecoder into a 1–2 µop SEQUENCE.
  *
  * - Register/immediate operands: 1 op µop (decode-matrix slice 1).
  * - memSimple EA SOURCE (the EASRC role): cracked into a LOAD µop → temp T0,
  *   then the op µop with the EASRC operand reading T0 (slice 2).
  *
  * Deferred (→ `unimplemented`): `(An)+`/`-(An)` side-effects, `(d8,An,Xn)` /
  * `(d8,PC,Xn)` indexed (memComplex), RMW-to-memory, and (until Task 4) memSimple
  * EA DESTINATION (the EADST store). */
object MicroOpAssembler {

  /** Internal int temp arch regs targeted by EA cracking. T2 is the 3rd temp added for
    * the microcode engine's 5-byte bit-field RMW chain (3 simultaneously-live temps
    * {lo,hi,res}); T3 (task #203) is the 4th, holding the memory-indirect dynamic-
    * offset bit-field RMW/INS chains' resolved pointer/Tb live across the funnel
    * compute. arch count = Isa.ARCH_INT_REGS = 20. */
  val T0 = 16
  val T1 = 17
  val T2 = 18
  val T3 = 19

  case class AssembledUops() extends Bundle {
    // Up to 3 µops per instruction (RTR = pop.w CCR + pop.l PC + ibranch). uops(0)
    // always valid; uops(1) iff count>=2; uops(2) iff count==3. The 3-µop case
    // (RTR) is decoded ONE-instruction-per-cycle (DecodeStage gates slot1 off) so the
    // 4-wide MicroOpQueue push never overflows (3 <= 4).
    val uops  = Vec(DecodedUop(), 3)
    val count = UInt(2 bits)         // 1, 2, or 3 µops valid
  }

  /** Fully-defaulted plain LOAD/STORE µop builder for the MOVEM micro-sequencer
    * (DecodeStage FSM). Every DecodedUop field assigned exactly once. The MOVEM move
    * µops are plain LS-cluster LOAD/STORE (NO flags, NO new EU): the FSM walks the
    * addresses via a constant base An (`base`/`baseValid`) + a per-element displacement
    * (`disp`), so it does NOT use the eaAuto fold (the single final An update is a
    * separate ADD µop, `movemAnUpdUop`).
    *
    *  - LOAD : [base+disp] -> reg (dstReg=reg). `.W` (sizeLong=False) SIGN-EXTENDS the
    *           loaded word to the full 32-bit register — reuse `isMovea` as the LS-EU
    *           "sign-extend .W load result" marker (the field already means "sign-extend
    *           a .W value"; the ALU EU is the only other consumer and never sees an
    *           LS-cluster µop). A `.L` load leaves isMovea False (full 32-bit load).
    *  - STORE: reg -> [base+disp] (srcBReg=reg = the stored data, no int dst, NO flags).
    */
  def movemMoveUop(reg: UInt, base: UInt, baseValid: Bool, disp: Bits, sizeLong: Bool,
                   isLoad: Bool, first: Bool, last: Bool, drop: Bool, valid: Bool, pc: UInt, nextPc: UInt,
                   idxReg: UInt, idxValid: Bool, idxLong: Bool, idxScale: UInt,
                   probeCount: UInt = U(0, 5 bits)): DecodedUop = {
    val u = DecodedUop()
    u.debugBreakValid := False; u.debugBreakSlot := 0
    u.fpInert()
    u.valid       := valid
    u.pc          := pc
    u.nextPc      := nextPc
    u.op          := DecOp.MOVE
    u.cluster     := Cluster.LS
    u.size        := Mux(sizeLong, Size.LONG, Size.WORD)
    u.memOp       := Mux(isLoad, MemOp.LOAD, MemOp.STORE)
    // base An (the address register); the FSM keeps it constant + walks `disp`.
    u.srcAReg     := base; u.srcAValid := baseValid
    // STORE data = the moved register (srcB); LOAD reads no srcB.
    u.srcBReg     := Mux(isLoad, U(0, 5 bits), reg); u.srcBValid := !isLoad
    // Index register for `(d8,PC,Xn)` brief-indexed MOVEM (task #200): rides srcC exactly
    // like any other indexed LS-cluster µop (see DecodedUop's srcC/indexLong/indexScale
    // doc). The FSM latches ONE constant idx* set for the whole macro (the index term does
    // NOT auto-update per element, unlike the running `disp`); idxValid=False for every
    // non-indexed MOVEM EA (the AGU zeroes the index contribution when srcCValid=False).
    u.srcCReg     := idxReg; u.srcCValid := idxValid
    // LOAD writes the register; STORE writes no int reg (no eaAuto fold here).
    u.dstReg      := reg; u.dstValid := isLoad
    u.useImm      := True; u.imm := disp
    u.readsNzvc   := False; u.readsX := False
    u.writesNzvc  := False; u.writesX := False     // MOVEM affects NO condition codes
    u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
    u.cond        := 0; u.branchDisp := 0
    u.unimplemented := False
    u.faulted     := False
    // `faultVector` REPURPOSED (task movem-translate-ahead; see DecodeStage.scala's
    // `movemProbeCount` comment for the producer side): 0 for every ordinary MOVEM
    // move (unchanged from before this task), or the macro's total element count
    // (1..16) on ONLY the very first LOAD-direction element. Safe to repurpose here
    // because `faultVector` is read downstream (RobPlugin) ONLY as the STATIC
    // fallback vector when an entry's `faulted` bit is set at alloc time (this
    // builder always sets `faulted := False`) AND no dynamic completion-time fault
    // record exists for it -- exactly the same "dead when not faulted" property
    // this codebase already relies on for other repurposed per-µop-context fields
    // (`isMovea` as the .W-load sign-extend marker, `divIsRem` as the generic
    // crack-drop marker, both documented at their own use sites in this file). A
    // MOVEM move that genuinely DOES fault (a real DTLB translate fault) is
    // delivered through the dynamic `faultCompletionPort`/`faultDynMem` path in
    // LsEuPlugin, which never reads this field — so a nonzero `faultVector` here
    // never collides with real fault-vector delivery.
    u.faultVector := probeCount.resize(8 bits); u.faultUsesNextPc := False
    u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
    u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
    u.divSigned   := False; u.div64 := False
    // `divRem` = the generic crack-DROP marker (like DIVREM / the source-EA An-update): a
    // dropped MOVEM move does NOT map to its own oracle step (the macro is ONE step — the
    // final An-update / last move is the kept commit), but its reg write still lands in the
    // PRF + is verified by a later reader (lock-step) / checkMem (stores).
    u.divIsRem    := drop
    u.isChk2      := False
    u.eaAuto      := EaAuto.NONE; u.eaDelta := 0     // NO auto-fold: addresses via disp
    u.ccrRestore  := False; u.toCcr := False
    u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
    // Reuse isMovea as the ".W load -> sign-extend the full 32-bit reg" marker (LOAD only).
    u.isMovea     := isLoad && !sizeLong
    u.isScc       := False; u.isDbcc := False
    u.indexLong   := idxLong; u.indexScale := idxScale
    u.leaAddr := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
    u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
    u.predTaken := False; u.predTarget := U(0, 32 bits)
    u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
    // Only the VERY FIRST emitted move of the whole MOVEM is the macro boundary
    // (firstOfInstr); every later move + the final An update is non-first, so an
    // interrupt is only taken at the MOVEM boundary (never mid-emission — the partly-
    // emitted moves would otherwise be re-run after RTE since they share the MOVEM pc).
    u.firstOfInstr := first
    // `lastOfInstr` (Stage 2 task 4): a move µop is the macro's LAST µop only for the
    // abs/PC-base MOVEM forms, which emit NO trailing An-update µop (movemHasFinal
    // False) — and then only the very last move of the very last emission cycle. The
    // DecodeStage FSM owns that determination (it holds the mask-drain state); this
    // builder just carries it.
    u.lastOfInstr := last
    u
  }

  /** The single final An update for `(An)+`/`-(An)` MOVEM: `An := An ± count*size`
    * (ONE ADD, not a per-move fold). It is the macro instruction's last µop (NOT first).
    * `signedDelta` is the full signed byte delta (+count*size for postinc, -count*size
    * for predec). It carries the nextPc so the ROB advances PC correctly at commit. */
  def movemAnUpdUop(an: UInt, signedDelta: SInt, valid: Bool, pc: UInt, nextPc: UInt): DecodedUop = {
    val u = DecodedUop()
    u.debugBreakValid := False; u.debugBreakSlot := 0
    u.fpInert()
    u.valid       := valid
    u.pc          := pc
    u.nextPc      := nextPc
    u.op          := DecOp.ADD
    u.cluster     := Cluster.INT
    u.size        := Size.LONG
    u.memOp       := MemOp.NONE
    u.srcAReg     := an; u.srcAValid := True
    u.srcBReg     := 0;  u.srcBValid := False
    u.srcCReg     := 0;  u.srcCValid := False
    u.dstReg      := an; u.dstValid := True
    u.useImm      := True; u.imm := signedDelta.resize(32).asBits
    u.readsNzvc   := False; u.readsX := False
    u.writesNzvc  := False; u.writesX := False     // An update sets NO flags
    u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
    u.cond        := 0; u.branchDisp := 0
    u.unimplemented := False
    u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
    u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
    u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
    u.divSigned   := False; u.div64 := False; u.divIsRem := False
    u.isChk2      := False
    u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
    u.ccrRestore  := False; u.toCcr := False
    u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
    u.isMovea     := False; u.isScc := False; u.isDbcc := False
    u.indexLong   := False; u.indexScale := 0
    u.leaAddr := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
    u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
    u.predTaken := False; u.predTarget := U(0, 32 bits)
    u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
    u.firstOfInstr := False    // trailing µop of the MOVEM macro
    // ...and, when it is emitted at all (every An-base MOVEM form, movemHasFinal), it is
    // UNCONDITIONALLY the macro's last µop: the FSM leaves movemAnUpdPhase straight to
    // movemActive=False once the queue accepts it (DecodeStage's MOVEM transition block).
    u.lastOfInstr := True
    u
  }

  /** MOVEM base/index register SNAPSHOT (task #200): a plain int-cluster register copy
    * (`dst := src + 0`, no flags) emitted ONCE at FSM entry, BEFORE any move µop, for the
    * two addressing shapes at risk of "in-list self-corruption": a CONTROL-mode base
    * ((An)/(d16,An) with the base An itself in the register list — case 3 of
    * movem_an_in_list.s) and a `(d8,PC,Xn)` index register that is itself in the list
    * (movem_pc_indexed.s). Every element's address then reads the immutable snapshot
    * (T0/T1) instead of the real architectural register, so an EARLIER move in the SAME
    * macro that happens to write that same architectural register can no longer corrupt
    * a LATER element's address via rename (the real register still receives its normal
    * load, if any — unlike `movemLoadDst`'s auto-update-mode redirect, which DISCARDS the
    * loaded value outright because Musashi's postinc/predec writeback wins there; here the
    * loaded value is genuinely KEPT — this copy only protects the SEPARATE addressing
    * use). Dropped from the oracle step count (like every intermediate MOVEM move); its
    * Tn write is real and consumed by every later per-element move via rename. Always the
    * macro's first emitted µop when present (the interrupt/firstOfInstr boundary). */
  def movemSnapUop(dst: UInt, src: UInt, valid: Bool, pc: UInt, nextPc: UInt): DecodedUop = {
    val u = DecodedUop()
    u.debugBreakValid := False; u.debugBreakSlot := 0
    u.fpInert()
    u.valid       := valid
    u.pc          := pc
    u.nextPc      := nextPc
    u.op          := DecOp.ADD
    u.cluster     := Cluster.INT
    u.size        := Size.LONG
    u.memOp       := MemOp.NONE
    u.srcAReg     := src; u.srcAValid := True
    u.srcBReg     := 0;   u.srcBValid := False
    u.srcCReg     := 0;   u.srcCValid := False
    u.dstReg      := dst; u.dstValid := True
    u.useImm      := True; u.imm := 0
    u.readsNzvc   := False; u.readsX := False
    u.writesNzvc  := False; u.writesX := False     // MOVEM affects NO condition codes
    u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
    u.cond        := 0; u.branchDisp := 0
    u.unimplemented := False
    u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
    u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
    u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
    u.divSigned   := False; u.div64 := False
    u.divIsRem    := True     // dropped: never the macro's kept commit
    u.isChk2      := False
    u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
    u.ccrRestore  := False; u.toCcr := False
    u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
    u.isMovea     := False; u.isScc := False; u.isDbcc := False
    u.indexLong   := False; u.indexScale := 0
    u.leaAddr := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
    u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
    u.predTaken := False; u.predTarget := U(0, 32 bits)
    u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
    u.firstOfInstr := True
    // The snapshot µop is emitted BEFORE any move and is never the macro's last µop:
    // the FSM only enters the snap phase from `movemBegin` with a NON-empty mask (an
    // empty-mask MOVEM clears movemActive at entry and emits nothing at all), so at
    // least one move µop always follows it.
    u.lastOfInstr := False
    u
  }

  // ════════════════════════════════════════════════════════════════════════════
  // FMOVEM.X data-register-list µop builders (task #241/#246, the `fmovemxActive` FSM --
  // the SECOND instantiation of the `RegListWalk` shared skeleton, see RegListWalk.scala).
  // Design spec: docs/superpowers/specs/2026-08-19-fmovem-data-list-design.md §§1-3/6.6.
  // LOAD direction only, EA modes `(An)`/`(d16,An)` only (this task's scope; store/auto-
  // update/indexed/PC-relative are tasks #242-245). Extended is the ONLY in-memory format
  // (§2: "always Extended... the ONLY format FMOVEM.X data-register-list transfers"), so
  // every element needs exactly the existing scalar Extended crack's per-element row shape
  // -- `[LOAD x3 chunks] + [UFpIssue]` (`Microcode.scala`'s `fpMemBaseGroup`, chunks=3) --
  // built DIRECTLY as hardware (mirroring `movemMoveUop`'s direct-construction style)
  // instead of routed through the µcode ROM/Ctx-resolve machinery (which is the µcode
  // SEQUENCER's own separate, unrelated engine -- this FSM bypasses it exactly like
  // `movemActive` bypasses `AssembledUops`).

  /** One 32-bit chunk LOAD into a FIXED scratch temp (`dstTemp` = T0/T1/T2, selected by the
    * FSM's own sub-phase counter), address = `base + disp` (the running per-element byte
    * offset + the chunk's 0/4/8 sub-offset, folded by the caller). Mirrors `movemMoveUop`'s
    * field-by-field style verbatim (plain LS-cluster load, no flags, no auto-fold -- this
    * task's admitted EA modes are both non-auto). UNCONDITIONALLY dropped from the lock-step
    * commit stream (`divIsRem := True`): it writes only a scratch temp, never a real
    * architectural destination on its own -- exactly like the µcode ROM's own `fpMemBaseGroup`
    * LOAD rows (never `isLast`/kept) and MOVEM's own `movemLoadDst`-redirected loads. The
    * loaded VALUE still reaches the scratch temp's real PRF slot via the ordinary rename/
    * wakeup path, so the following issue-row's srcA/srcB/srcC reads see it correctly. */
  def fmovemxLoadChunkUop(base: UInt, baseValid: Bool, disp: Bits, dstTemp: UInt,
                           first: Bool, valid: Bool, pc: UInt, nextPc: UInt): DecodedUop = {
    val u = DecodedUop()
    u.debugBreakValid := False; u.debugBreakSlot := 0
    u.fpInert()
    u.valid       := valid
    u.pc          := pc
    u.nextPc      := nextPc
    u.op          := DecOp.MOVE
    u.cluster     := Cluster.LS
    u.size        := Size.LONG
    u.memOp       := MemOp.LOAD
    u.srcAReg     := base; u.srcAValid := baseValid
    u.srcBReg     := 0;    u.srcBValid := False
    u.srcCReg     := 0;    u.srcCValid := False    // no index -- this task's EA scope has none
    u.dstReg      := dstTemp; u.dstValid := True
    u.useImm      := True; u.imm := disp
    u.readsNzvc   := False; u.readsX := False
    u.writesNzvc  := False; u.writesX := False     // FMOVEM affects NO integer condition codes
    u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
    u.cond        := 0; u.branchDisp := 0
    u.unimplemented := False
    u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
    u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
    u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
    u.divSigned   := False; u.div64 := False
    u.divIsRem    := True     // a scratch-temp load is NEVER the macro's kept commit
    u.isChk2      := False
    u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
    u.ccrRestore  := False; u.toCcr := False
    u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
    u.isMovea     := False
    u.isScc       := False; u.isDbcc := False
    u.indexLong   := False; u.indexScale := 0
    u.leaAddr := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
    u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
    u.predTaken := False; u.predTarget := U(0, 32 bits)
    u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
    u.firstOfInstr := first
    // A chunk LOAD is NEVER the macro's last µop: each element is [LOAD x3][issue row],
    // so the element's own FP issue row always follows the 3 chunk loads.
    u.lastOfInstr := False
    u
  }

  /** The per-element FP issue row: reads the 3 already-loaded chunk temps (T0/T1/T2, the
    * Extended-format srcA/srcB/srcC gateway -- `fpSrcKind = MEMEXT`, mirrors
    * `Microcode.scala`'s `UFpIssue` resolve block field-for-field) and writes the current
    * element's target FPn (`fpDst`, 0-7). `fpuOp = 0x00` (FMOVE -- a plain load/convert, no
    * compute; `FpSource.opmodeToFpOp`'s own documented safe default).
    *
    * ONE DELIBERATE DIVERGENCE from `UFpIssue`'s own field values, NOT a bug: `writesFpcc`
    * is forced FALSE here, where `UFpIssue` sets it unconditionally True. The design doc §2
    * is explicit that FMOVEM (list or otherwise) is NOT a compute instruction and must NOT
    * update FPSR/FPCC ("Musashi's `fmovem()` never touches FPSR") -- unlike a scalar single-
    * register `FMOVE <ea>,FPn`, which DOES update FPCC and is exactly what `UFpIssue` is
    * for. Building this uop directly (rather than routing through the µcode ROM's
    * `UFpIssue` descriptor) is what lets this field diverge correctly per element.
    *
    * `drop` mirrors MOVEM's own `movemHasFinal`-driven drop convention, generalized past 2
    * writers: EVERY element's issue row drops (`divIsRem := True`) EXCEPT the LAST emitted
    * element of the macro, whose issue row is the sole kept commit -- the exact same "N
    * dropped writers + 1 kept" shape the CPLX cluster's `DivEuPlugin` already relies on for
    * DIVREM (2 writers: the kept quotient move + the dropped remainder move) and MOVEM
    * already proves scales to N (`LsEuPlugin`'s `crackDrop <- divIsRem`); the CPLX cluster's
    * OWN `wbObs.keepCommit` is hardcoded False (`DivEuPlugin.scala`), so the "exactly one
    * non-drop writer per macro" convention -- not an explicit keepCommit override -- is the
    * ONLY mechanism CPLX writers have, and it generalizes to this FSM's up-to-8 writers with
    * no new EU-side plumbing. */
  def fmovemxIssueUop(fpDst: UInt, drop: Bool, first: Bool, last: Bool, valid: Bool,
                       pc: UInt, nextPc: UInt): DecodedUop = {
    val u = DecodedUop()
    u.debugBreakValid := False; u.debugBreakSlot := 0
    // NO `u.fpInert()` here (unlike every OTHER builder in this file): every fp* field is
    // explicitly, unconditionally set below -- calling fpInert() first would completely
    // overlap those same fields with no intervening `when`, which SpinalHDL's
    // PhaseCheck_noLatchNoOverride correctly flags as dead-code-shaped (exactly the class of
    // issue Microcode.scala's own UFpIssue resolve block documents wrapping in `when(Bool(...))`
    // to avoid -- here the simpler fix is to just not double-write these particular fields).
    u.valid       := valid
    u.pc          := pc
    u.nextPc      := nextPc
    u.op          := DecOp.FPU
    u.cluster     := Cluster.CPLX
    u.size        := Size.LONG
    u.memOp       := MemOp.NONE
    u.srcAReg     := U(T0, 5 bits); u.srcAValid := True
    u.srcBReg     := U(T1, 5 bits); u.srcBValid := True
    u.srcCReg     := U(T2, 5 bits); u.srcCValid := True
    u.dstReg      := 0; u.dstValid := False    // no INT destination -- FP PRF only
    u.useImm      := False; u.imm := 0
    u.readsNzvc   := False; u.readsX := False
    u.writesNzvc  := False; u.writesX := False
    u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
    u.cond        := 0; u.branchDisp := 0
    u.unimplemented := False
    u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
    u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
    u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
    u.divSigned   := False; u.div64 := False
    u.divIsRem    := drop
    u.isChk2      := False
    u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
    u.ccrRestore  := False; u.toCcr := False
    u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
    u.isMovea     := False
    u.isScc       := False; u.isDbcc := False
    u.indexLong   := False; u.indexScale := 0
    u.leaAddr := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
    u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
    u.predTaken := False; u.predTarget := U(0, 32 bits)
    u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
    u.firstOfInstr := first
    // The element's issue row is the macro's last µop exactly when this is the LAST
    // element of the list (`fmovemxIsLastElem`): the FSM clears fmovemxActive right
    // after that row is accepted. Same signal that already drives `drop` (inverted) --
    // the last element's issue row is BOTH the kept macro commit and the last µop.
    u.lastOfInstr := last
    // ── FP-domain fields (mirrors Microcode.scala's UFpIssue resolve block) ──────────
    u.fpuOp      := B(0, 7 bits)              // 0x00 FMOVE: plain load/convert, no compute
    u.fpDstReg   := fpDst.resize(3)
    u.writesFp   := True
    u.writesFpcc := False    // DELIBERATE divergence from UFpIssue -- see doc above (design §2)
    u.readsFpcc  := False
    u.fpSrcAReg  := fpDst.resize(3)           // harmless: usesFpSrcA=False below (not dyadic)
    u.usesFpSrcA := False
    u.fpSrcBReg  := 0
    u.usesFpSrcB := False                      // source is the memory-loaded temps, not FPm
    u.fpSrcFmt   := B"3'b010"                  // srcSpec code for Extended (design doc §2)
    u.fpSrcKind  := FpSrcKind.MEMEXT
    u.fpWideImm  := B(0, 80 bits)
    u
  }

  // ════════════════════════════════════════════════════════════════════════════
  // MOVEP micro-sequencer µop builders (the DecodeStage MOVEP FSM). MOVEP moves a
  // data register <-> alternating EVEN memory bytes; the FSM emits a byte-at-a-time
  // load/store sequence + the shift/and/or assembly using EXISTING DecOps (MOVE+memOp,
  // SHIFT, AND, OR). NO new EU datapath / DecOp. MOVEP has NO CCR effect (writesNzvc/
  // writesX False on every µop). All intermediate temp writes are DROPPED (divIsRem);
  // the single KEPT macro commit is the final `MOVE Tacc->Dx` (mem->reg) or the last
  // byte store carrying `keepCommit` (reg->mem, which writes no register).
  //
  // Every DecodedUop field is assigned exactly once (mirrors movemMoveUop's shape).
  // ════════════════════════════════════════════════════════════════════════════

  /** Common defaults for a MOVEP µop: NO flags, NO branch/sys/fault/index/auto markers,
    * pc/nextPc threaded for the macro commit. Mutated by the specific builders. */
  private def movepBase(pc: UInt, nextPc: UInt): DecodedUop = {
    val u = DecodedUop()
    u.debugBreakValid := False; u.debugBreakSlot := 0
    u.fpInert()
    // The specific builders below override a few fields (op/size/srcs/dst/flags) after
    // these defaults; allowOverride makes that last-wins (the defaults provide the inert
    // value for every field NOT touched by the builder, so no field is left UNASSIGNED).
    u.flattenForeach(_.allowOverride)
    u.valid       := True
    u.pc          := pc
    u.nextPc      := nextPc
    u.op          := DecOp.MOVE
    u.cluster     := Cluster.INT
    u.size        := Size.LONG
    u.memOp       := MemOp.NONE
    u.srcAReg     := 0; u.srcAValid := False
    u.srcBReg     := 0; u.srcBValid := False
    u.srcCReg     := 0; u.srcCValid := False
    u.dstReg      := 0; u.dstValid  := False
    u.useImm      := False; u.imm := 0
    u.readsNzvc   := False; u.readsX := False
    u.writesNzvc  := False; u.writesX := False     // MOVEP affects NO condition codes
    u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
    u.cond        := 0; u.branchDisp := 0
    u.unimplemented := False
    u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
    u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
    u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
    u.divSigned   := False; u.div64 := False
    u.divIsRem    := False
    u.isChk2      := False
    u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
    u.ccrRestore  := False; u.toCcr := False
    u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
    u.isMovea     := False; u.isScc := False; u.isDbcc := False
    u.indexLong   := False; u.indexScale := 0
    u.leaAddr := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
    u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
    u.predTaken := False; u.predTarget := U(0, 32 bits)
    u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
    u.firstOfInstr := False
    // Inert default. The AUTHORITATIVE value for every MOVEP µop is stamped once, by
    // the DecodeStage MOVEP FSM, from its OWN pre-existing per-step `movepLast` signal
    // (the same signal that ends the FSM) -- a single source of truth that cannot drift
    // from the FSM's real step sequence. See DecodeStage's `movepUop.lastOfInstr` stamp.
    u.lastOfInstr := False
    u
  }

  /** SHIFT µop: `src (LSL/LSR by `count`) -> dst`, LONG (no .B/.W merge). dirLeft selects
    * LSL (assemble) vs LSR (extract a byte for storing). shiftOp = 1 (LSL/LSR family).
    * dst is a temp (dropped); reads `src` (srcA). Used both to extract a byte from Dx
    * (reg->mem) and to position a loaded byte (mem->reg). */
  def movepShiftUop(src: UInt, dst: UInt, count: Int, dirLeft: Boolean,
                    first: Boolean, pc: UInt, nextPc: UInt): DecodedUop = {
    val u = movepBase(pc, nextPc)
    u.op       := DecOp.SHIFT
    u.shiftOp  := B"01"                 // tt=01 = LSL/LSR
    u.shiftDir := Bool(dirLeft)
    u.srcAReg  := src;  u.srcAValid := True
    u.dstReg   := dst;  u.dstValid  := True
    u.useImm   := True; u.imm := U(count, 32 bits).asBits
    u.divIsRem := True                  // intermediate temp write -> dropped
    u.firstOfInstr := Bool(first)
    u
  }

  /** AND µop: `src & imm -> dst` (LONG), no flags. Used by mem->reg .W to preserve
    * Dx[31:16] (imm = 0xFFFF0000) before assembling the low word. dst is a temp. */
  def movepAndMaskUop(src: UInt, dst: UInt, mask: Long, first: Boolean,
                      pc: UInt, nextPc: UInt): DecodedUop = {
    val u = movepBase(pc, nextPc)
    u.op       := DecOp.AND
    u.srcAReg  := src; u.srcAValid := True
    u.useImm   := True; u.imm := B(mask, 32 bits)
    u.dstReg   := dst; u.dstValid := True
    u.divIsRem := True
    u.firstOfInstr := Bool(first)
    u
  }

  /** OR µop: `srcA | srcB -> dst` (LONG), no flags. The accumulator merge. dst is a temp. */
  def movepOrUop(srcA: UInt, srcB: UInt, dst: UInt, pc: UInt, nextPc: UInt): DecodedUop = {
    val u = movepBase(pc, nextPc)
    u.op       := DecOp.OR
    u.srcAReg  := srcA; u.srcAValid := True
    u.srcBReg  := srcB; u.srcBValid := True
    u.dstReg   := dst;  u.dstValid := True
    u.divIsRem := True
    u
  }

  /** Byte LOAD µop: `[base + disp] -> T0` (.B, ZERO-extended into T0[31:8]=0 by the LS-EU
    * DcacheByteLane.extract). dst = T0 (temp, dropped). NO auto-update (MOVEP EA is
    * (d16,Ay) — no predec/postinc). */
  def movepLoadUop(base: UInt, disp: Bits, first: Boolean, pc: UInt, nextPc: UInt): DecodedUop = {
    val u = movepBase(pc, nextPc)
    u.op      := DecOp.MOVE
    u.cluster := Cluster.LS
    u.size    := Size.BYTE
    u.memOp   := MemOp.LOAD
    u.srcAReg := base; u.srcAValid := True
    u.dstReg  := U(T0, 5 bits); u.dstValid := True
    u.useImm  := True; u.imm := disp
    u.divIsRem := True                  // loaded byte is a temp -> dropped
    u.firstOfInstr := Bool(first)
    u
  }

  /** Byte STORE µop: `srcData(.B low byte) -> [base + disp]`. NO int dst, NO flags. The
    * stored byte is the low byte of the source register (Dx directly for the position-0
    * byte, or a shifted temp). `keep` forces the macro commit (reg->mem writes no reg, so
    * the LAST store carries keepCommit). NO auto-update. */
  def movepStoreUop(base: UInt, disp: Bits, srcData: UInt, keep: Boolean, first: Boolean,
                    pc: UInt, nextPc: UInt): DecodedUop = {
    val u = movepBase(pc, nextPc)
    u.op      := DecOp.MOVE
    u.cluster := Cluster.LS
    u.size    := Size.BYTE
    u.memOp   := MemOp.STORE
    u.srcAReg := base; u.srcAValid := True
    u.srcBReg := srcData; u.srcBValid := True   // store DATA
    u.useImm  := True; u.imm := disp
    u.keepCommit := Bool(keep)          // the kept macro commit (reg->mem) — others drop as RMW stores
    u.firstOfInstr := Bool(first)
    u
  }

  /** Final reg MOVE µop (mem->reg): `acc -> Dx` (full .L write; the .W form's acc
    * already carries the preserved Dx[31:16] from the AND mask). KEPT macro commit
    * (writes a real reg, not a temp; not divIsRem). srcB = acc (MOVE reads src2). */
  def movepFinalMoveUop(acc: UInt, dx: UInt, pc: UInt, nextPc: UInt): DecodedUop = {
    val u = movepBase(pc, nextPc)
    u.op      := DecOp.MOVE
    u.size    := Size.LONG              // full 32-bit write
    u.srcBReg := acc; u.srcBValid := True
    u.dstReg  := dx;  u.dstValid := True
    u
  }

  // FRONTEND-FMAX ANGLE E (predecode-offload): the OperationDecoder masked-pattern
  // table is a PURE function of the opword (pkt.words(0)) — the dominant combinational
  // cone half on the registered `fed_payload -> pushReg.uops` critical arc. By computing
  // the OpSpec on the PRE-register (aligner-output) opword and carrying it through the
  // FetchAlign->Decode register, the post-register `assemble` reads a REGISTERED spec
  // (a thin select) instead of re-running the table. `specIn` is the offloaded spec; when
  // None (decode-unit tests / standalone callers) the table runs inline as before. The
  // offload is per-SLOT (2 instances at decode), NOT per-IBuf-entry, so the IBuf shift mux
  // is untouched (no IBuf bloat). Byte-identical: OperationDecoder.decode(op) is the exact
  // same function whether evaluated here or one stage earlier.
  //
  // LEVER 2 (EaDecoder offload): the two PRIMARY EaDecoder.decode calls (srcEa = op[5:0]
  // and dstEa = the MOVE dest field) feed the op-µop `imm` (the IMM-mode immediate via
  // srcEa.imm) + the EA base/disp/auto/index routing, which is regen1's residual decode
  // limiter (`fed_payload_packets -> pushReg_payload_uops_*_imm`). EaDecoder.decode is a
  // PURE function of the EA field + size + the packet words (all available a full cycle
  // pre-register), so pre-computing srcEa/dstEa on the aligner-output packet and carrying
  // them in `Offload` collapses this cone to a registered read — byte-identical (same
  // function, one stage earlier). The bespoke SHIFTED-words re-decodes (immEa / divl /
  // mull / bfm / cmp2 / ucBfEaDec) are LEFT inline: they are narrow, op-class-specific,
  // and not on the dominant `imm` fanout — offloading them would bloat the carried payload
  // for little gain.
  case class Offload() extends Bundle {
    val spec  = OpSpec()
    val srcEa = EaSpec()
    val dstEa = EaSpec()
  }

  // ── MOVE dst-EA extension-word SHIFT (task #164) ───────────────────────────────
  // Plain MOVE.B/.W/.L is the ONLY op family whose `dst.kind === OperandKind.EADST`
  // (OperationDecoder.scala's sole `o.dst := eadst` site, MOVE opcode lines 0x1-0x3) --
  // i.e. the only op with TWO independent EA operands. The encoding's extension-word
  // ORDER is always src-then-dst, so the dst's own ext word(s) do NOT unconditionally
  // start at words(1) -- they start wherever the SOURCE EA's own ext words end. Both
  // `computeOffload` and the standalone `assemble` fallback below decoded `dstEa`
  // directly against the raw, UNSHIFTED `pkt.words`, which only happened to be correct
  // when the source consumed 0 ext words (Dn/An/(An)/(An)+/-(An) sources -- the only
  // shapes covered by the pilot MOVE tests). Any MOVE whose source has its OWN ext
  // word(s) -- (d16,An)/(d16,PC)/abs.W/abs.L/#imm/(d8,An,Xn) brief-or-full -- silently
  // misread the dst's disp/index from the SOURCE's own ext word instead, corrupting
  // the computed destination address (root cause of the movel_mem_to_mem /
  // move_abs_mem_to_abs / move_l_full_src_d16_dst / move_idx_idx / etc. failure
  // cluster -- ported-tests triage, cluster 8). Mirrors DecodeStage.scala's
  // `eaWordCount`/`miEaWordCount` (task #119), duplicated here (not shared -- that
  // copy is scoped to the µcode engine's `ucEntryPkt`, a distinct packet binding)
  // against a generic `words: Vec[Bits]`. A no-op (shift=0) for every register-direct
  // source, so this is a strict, backward-compatible generalization matching #119's
  // own precedent, not a new special case.
  private def miEaWordCountG(baseExtW: Bits): UInt = {
    val bdSize    = baseExtW(5 downto 4).asUInt
    val bdWords   = Mux(bdSize === U(2, 2 bits), U(1, 3 bits),
                     Mux(bdSize === U(3, 2 bits), U(2, 3 bits), U(0, 3 bits)))
    val odPresent = baseExtW(1)
    val odLong    = baseExtW(0)
    val odWords   = Mux(!odPresent, U(0, 3 bits), Mux(odLong, U(2, 3 bits), U(1, 3 bits)))
    (U(1, 3 bits) + bdWords + odWords).resize(3)
  }
  private def srcEaWordCount(mode: UInt, reg: UInt, size: Size.C, words: Vec[Bits]): UInt = {
    val n = UInt(3 bits); n := 0
    switch(mode) {
      is(U(5, 3 bits)) { n := 1 }                                  // (d16,An)
      is(U(6, 3 bits)) {                                           // (d8,An,Xn) brief / full-format
        when(words(1)(8)) { n := miEaWordCountG(words(1)) }
          .otherwise { n := 1 }
      }
      is(U(7, 3 bits)) {
        switch(reg) {
          is(U(0, 3 bits)) { n := 1 }   // (xxx).W
          is(U(1, 3 bits)) { n := 2 }   // (xxx).L
          is(U(2, 3 bits)) { n := 1 }   // (d16,PC)
          is(U(3, 3 bits)) {            // (d8,PC,Xn) brief / full-format
            when(words(1)(8)) { n := miEaWordCountG(words(1)) }
              .otherwise { n := 1 }
          }
          is(U(4, 3 bits)) { n := Mux(size === Size.LONG, U(2, 3 bits), U(1, 3 bits)) }  // #imm
        }
      }
    }
    n   // default 0: Dn/An/(An)/(An)+/-(An)
  }
  private def wordAtDynG(words: Vec[Bits], idx: UInt): Bits = {
    val out = Bits(16 bits); out := B(0, 16 bits)
    switch(idx) {
      for (i <- 0 until words.length) { is(U(i, 5 bits)) { out := words(i) } }
    }
    out
  }
  // Shifted words view for an IMMEDIATE-SOURCE re-decode (the line-0 `immEa` and the
  // MOVE-#imm `immDstEa`): the EA's own extension words start AFTER the immediate, i.e.
  // at op+2 for a .B/.W immediate and op+3 for a .L one.
  //
  // FOUR entries, not three. `EaDecoder` reads a full-format 32-bit base displacement as
  // `wAt(words,2) ## wAt(words,3)`, and `wAt` returns a hardwired 0 for an index past the
  // Vec's end -- so a 3-entry view silently dropped the LOW half-word of every long `bd`
  // (0x00010036 -> 0x00010000), a wrong address with no trap. A full-format OUTER
  // displacement would need a 5th/6th entry, but an EA that has one is EaClass.MEMINDIRECT,
  // which this fast path never executes (`lineImmBad` rejects it / the µcode engine owns
  // it). Same helper shape as the sibling fix on `fuzz/zero-divergence-campaign`.
  private def immShiftedWords(words: Vec[Bits], immIsLong: Bool): Vec[Bits] =
    Vec.tabulate(4) { i =>
      if (i == 0) words(0)
      else Mux(immIsLong, words(i + 2), words(i + 1))
    }

  // Shifted view for the dst-EA decode: index 0 unused (EaDecoder.decode never reads
  // it), indices 1.. = words(1+shift, 2+shift, ...).
  private def shiftedWordsFor(words: Vec[Bits], shift: UInt): Vec[Bits] =
    Vec.tabulate(words.length) { i =>
      if (i == 0) B(0, 16 bits)
      else wordAtDynG(words, (U(i, 5 bits) + shift.resize(5)).resize(5))
    }

  // ── FSF (xxx).L single-EA override (task #180) ──────────────────────────────────
  // FSF's real <ea> field bits (op[5:0] = mode7/reg7) are a RESERVED encoding under
  // the standard <ea> table — see OperationDecoder.scala's comment on opword 0xF27F
  // for the full derivation. ONLY for that exact literal opword, substitute a
  // hardcoded abs.L field (mode=111/reg=001) and a words view shifted by 1 (the
  // discarded FScc condition ext word at words(1) is skipped, so the abs.L address's
  // own 2 ext words land where EaDecoder's abs.L case expects them: words(1)##words(2)
  // of the shifted view = pkt.words(2)##pkt.words(3)). EaDecoder.scala's shared table
  // is untouched — mode7/reg7 stays ILLEGAL for every other opcode in the ISA.
  private def srcEaFor(pkt: DecodePacket, size: Size.C): EaSpec = {
    val isFsfAbsL = pkt.words(0) === B"16'hF27F"
    EaDecoder.decode(
      Mux(isFsfAbsL, B"6'b111001", pkt.words(0)(5 downto 0)),
      size,
      Mux(isFsfAbsL, shiftedWordsFor(pkt.words, U(1, 3 bits)), pkt.words))
  }

  /** Shared body of `computeOffload` / `computeOffloadFromWords`: identical in every
    * respect except WHERE the operand size comes from (see both callers). */
  private def offloadBody(pkt: DecodePacket, sz: Size.C): Offload = {
    val o = Offload()
    // UNCHANGED, and deliberately so: the full OpSpec (including `spec.size`) still rides
    // `fedIn.payload.specs(i)` for `assemble`. Lever B removes OperationDecoder from the
    // SERIAL CHAIN INTO `dstEa`, not from the design — the `raw -> spec_*` endpoints remain
    // a shallow reg-to-reg path with ample slack, which is where they belong.
    o.spec := OperationDecoder.decode(pkt.words(0))
    o.srcEa := srcEaFor(pkt, sz)
    val dstEaField = pkt.words(0)(8 downto 6) ## pkt.words(0)(11 downto 9)
    val dstShift = srcEaWordCount(pkt.words(0)(5 downto 3).asUInt, pkt.words(0)(2 downto 0).asUInt,
                                   sz, pkt.words)
    o.dstEa := EaDecoder.decode(dstEaField, sz, shiftedWordsFor(pkt.words, dstShift))
    o
  }

  /** Compute the offload (spec + the two primary EAs) on the PRE-register packet.
    * Carried through the FetchAlign->Decode register; `assemble` then reads it instead
    * of re-running OperationDecoder + the two EaDecoders on the registered opword.
    *
    * FMax "Lever B" (2026-08-08): the operand size feeding the two EA decodes is taken
    * from `pkt.size` — precomputed at I-cache REFILL time (see `ChunkPredecode.size`) —
    * instead of from `o.spec.size`. Before this, `size` arrived 9 logic levels / 2.288ns
    * deep into `OperationDecoder`'s cone and then fed `srcEaWordCount -> shiftedWordsFor
    * -> EaDecoder -> dstEa` IN SERIES, making the whole thing the design's 21-level OOC
    * WNS path; sourcing it from the `raw` register instead retires that family (measured
    * A/B: -1.216ns -> -0.824ns, +15.58MHz OOC, zero latency, zero IPC). The two are
    * equal by construction — `PredecodeWord.classify` CALLS `OperationDecoder.decode`.
    *
    * ONLY safe on Aligner-produced packets. Anything that hand-builds a `DecodePacket`
    * must call `computeOffloadFromWords` instead (and `assemble(pkt)` does). */
  def computeOffload(pkt: DecodePacket): Offload = offloadBody(pkt, pkt.size)

  /** Reference form: derives the operand size from the decoder itself, IGNORING
    * `pkt.size`. Two users, both required:
    *  - the standalone `assemble(pkt)` overload, used by ~14 unit specs that construct
    *    `DecodePacket`s by hand and so have no meaningful `pkt.size` to read;
    *  - `FedSpecsPacketPairingSpec`, where it is the REFERENCE MODEL that makes the gate a
    *    genuine comparison rather than a tautology: it recomputes the offload from
    *    `fed.packets(i).words` alone and demands bit equality with the `specs(i)` the
    *    pipeline actually carried (which were computed via the plumbed `pkt.size`). */
  def computeOffloadFromWords(pkt: DecodePacket): Offload =
    offloadBody(pkt, OperationDecoder.decode(pkt.words(0)).size)

  def assemble(pkt: DecodePacket): AssembledUops = assemble(pkt, None)
  def assemble(pkt: DecodePacket, specIn: Option[OpSpec]): AssembledUops =
    assembleImpl(pkt, specIn.map { s =>
      // Standalone/spec caller passed only an OpSpec: derive the EAs inline (the EaDecoder
      // offload applies only on the full DecodeStage path that supplies a complete Offload).
      val o = Offload()
      o.spec := s
      o.srcEa := srcEaFor(pkt, s.size)
      val dstEaField = pkt.words(0)(8 downto 6) ## pkt.words(0)(11 downto 9)
      val dstShift = srcEaWordCount(pkt.words(0)(5 downto 3).asUInt, pkt.words(0)(2 downto 0).asUInt,
                                     s.size, pkt.words)
      o.dstEa := EaDecoder.decode(dstEaField, s.size, shiftedWordsFor(pkt.words, dstShift))
      o
    })
  def assemble(pkt: DecodePacket, offIn: Offload): AssembledUops = assembleImpl(pkt, Some(offIn))

  private def assembleImpl(pkt: DecodePacket, offIn: Option[Offload]): AssembledUops = {
    val out = AssembledUops()
    val op  = pkt.words(0)
    // FMax Lever B: the no-offload fallback is reached ONLY from the standalone
    // `assemble(pkt)` overload, whose callers (~14 unit specs) hand-build `DecodePacket`s
    // and leave `pkt.size` unassigned. It must therefore derive the size from the decoder
    // (`computeOffloadFromWords`), NOT read `pkt.size`. The real DecodeStage path never
    // reaches here — it always supplies a complete `Offload` via `assemble(pkt, offload)`.
    val off = offIn.getOrElse(computeOffloadFromWords(pkt))
    val spec = off.spec

    // EA fields. Source EA = op(5..0). Dest EA (MOVE) = dstMode(8..6) ## dstReg(11..9).
    // LEVER 2: read the offloaded (pre-register-computed) EAs instead of re-decoding.
    val srcEa = off.srcEa
    val dstEa = off.dstEa

    // Line-0 immediate (IMMEXT): the trailing extension word(s), sized by the op.
    // .L = words(1)##words(2) (the full 32-bit value); .B/.W = words(1) (sign-extended,
    // matching the EaDecoder #imm path — only the low `size` bits are consumed by the
    // ALU / flag logic, so the upper extension is don't-care). The imm precedes any EA
    // ext, and a line-0 immediate's EA is mode0 (Dn, no ext) -> words(1..2) are the imm.
    val immExt = Mux(spec.size === Size.LONG,
                     pkt.words(1) ## pkt.words(2),
                     pkt.words(1).asSInt.resize(32).asBits)

    // Line-0 immediate mem-dest RMW (ADDI/.../EORI/CMPI #imm,<ea>): the EA's OWN
    // extension words follow the immediate (1 word for .B/.W, 2 for .L), NOT at
    // words(1). Re-decode the EA from a SHIFTED words vector so its disp/abs come from
    // the right offset (same shape as the DIV.L/MUL.L re-decode). `immEa` supplies the RMW
    // load/store ADDRESS + index descriptor when the op is a line-0 immediate; the plain
    // `srcEa` (words(1)-based) still drives every non-immediate path. NOTE (Part 122): the
    // operand CLASS is NOT offset-independent for the indexed modes -- see the
    // `srcEaIsMemSimpleEff` block below, which is what makes this correct.
    val immIsLong = spec.size === Size.LONG
    // BUG_calibration_word_misplaced_0d00.md Part 122: this view is FOUR words wide, not
    // three -- see `immShiftedWords` for why (a long base displacement lost its low
    // half-word). Real-hardware repro: the Quadra 700 ROM's RAM-sizing routine at
    // 0x000098E2, `0CB0 316D 6567 8170 000F EFFC` = `cmpi.l #imm,%a0@(0xFEFFC)`, bd=long.
    val immEa = EaDecoder.decode(
      op(5 downto 0), spec.size, immShiftedWords(pkt.words, immIsLong))
    // The EA descriptor for the RMW load/store ADDRESS: immEa for a line-0 immediate
    // (its ext follows the imm), srcEa otherwise.
    val opIsLineImm = spec.srcB.kind === OperandKind.IMMEXT
    val rmwEaDisp = Mux(opIsLineImm, immEa.disp, srcEa.disp)
    // ── LEGALITY must not depend on the IMMEDIATE's VALUE (Part 122 root cause) ──
    // The comment that used to sit above `opIsLineImm` claimed "klass/base/baseValid/pcRel
    // are offset-independent and identical; only `disp` differs". That is FALSE for the two
    // extension-word-CONTENT-dependent EA modes, 6 `(bd,An,Xn)` and 7-reg3 `(bd,PC,Xn)`:
    // `EaDecoder` reads `words(1)(8)` to pick brief-vs-FULL format, and out of that same
    // word it reads BS (base suppress) and I/IS (the memory-indirect selector that decides
    // MEMSIMPLE vs MEMINDIRECT). For a line-0 immediate, `words(1)` is NOT the EA's
    // extension word -- it is the IMMEDIATE's first word. So `srcEa.klass` was being
    // decided from the immediate's own bits: the instruction's LEGALITY was a function of
    // its OPERAND VALUE.
    //
    // Measured on real hardware (Part 121/122): the Quadra 700 ROM's RAM-sizing routine,
    // relocated into low RAM, executes
    //   0x000098E2: 0CB0 316D 6567 8170 000F EFFC = cmpi.l #$316D6567,%a0@(0xFEFFC)
    // The immediate's high word is 0x316D. Bit 8 of 0x316D is 1 -> "full format"; bits
    // [2:0] are 0b101 -> I/IS =/= 000 -> EaClass.MEMINDIRECT -> `lineImmBad` -> a spurious
    // vector-4 Illegal Instruction. The REAL extension word (0x8170, at op+3) has I/IS=000,
    // an ordinary single-pass MEMSIMPLE this fast path executes fine; MAME retires the
    // identical encoding 1985 times in 20 emulated seconds. That trap was the machine's
    // boot blocker (-> ROM MicroBug monitor -> failed `$0DB0` magic test -> ROM restart ->
    // vector table rebuilt on an odd stack -> odd VBR -> unrecoverable bus-error storm).
    // It is NOT specific to CMPI or to a .L immediate: any line-0 immediate (.W or .L; a
    // .B immediate word is 0x00xx so bit 8 is always clear) with an indexed destination and
    // an immediate whose bits [8] and [2:0] happen to look memory-indirect was rejected.
    // `move.l #imm,%a0@(0x17EFFC)` at 0x98C4/0x98D2 -- same EA, same 0x8170 word, same
    // cache line -- retires because a MOVE's destination goes through `offloadBody`'s
    // `dstShift`, which already accounts for the source immediate. Only the line-0
    // (EA-is-destination) family read the wrong word.
    //
    // The correction is deliberately NOT `Mux(opIsLineImm, immEa.klass, srcEa.klass)`.
    // `srcIsMem` feeds `srcEaOk`/`lineImmBad`/`bitOpMemBad`, i.e. the global `bad` tree --
    // this campaign has measured the same predicate at 50 MHz in `bad`, 25 MHz in a local
    // block override, and 0 MHz when matched off the RAW OPWORD into a narrow gate. So the
    // fixup is exactly that: a 2:1 select between two REGISTERED packet words, three bit
    // tests, and an opword compare. No new EaDecoder in `bad`'s cone, and no new term in
    // `bad` -- the existing `lineImmBad`/`bitOpMemBad`/`srcEaOk` terms simply get a correct
    // input. (`base` = 8+reg and `autoMode`/`autoDelta` (modes 3/4, no extension word) are
    // genuinely content-independent and are left alone, as is `pcRel`.)
    //
    // Scoped to EA mode 6 ONLY. The other content-dependent mode, 7-reg3 `(bd,PC,Xn)`, is
    // not reachable here for a line-0 immediate: `PredecodeWord`'s `memDestExt` rejects
    // every mode-7 register except 0/1, so such an instruction never arrives `pkt.simple`
    // and is already illegal via the `!pkt.simple` arm of `bad` (which also disables
    // cracking). Leaving it out keeps this fixup exactly as wide as the shapes it is
    // proven correct for -- in particular it must not force `baseValid` true for a
    // PC-relative EA, which has no An base at all.
    val l0EaIsIdx  = op(5 downto 3) === B"3'b110"
    val l0ExtW     = Mux(immIsLong, pkt.words(3), pkt.words(2))   // the EA's REAL ext word
    val l0IsMemInd = l0ExtW(8) && (l0ExtW(2 downto 0) =/= B"3'b000")
    val l0Fixup    = opIsLineImm && l0EaIsIdx
    // For mode 6 `EaDecoder` yields MEMSIMPLE or MEMINDIRECT and nothing else, so
    // "is MEMSIMPLE" is exactly "is not memory-indirect".
    val srcEaIsMemSimpleEff = Mux(l0Fixup, !l0IsMemInd, srcEa.klass === EaClass.MEMSIMPLE)
    // Full-format BS=1 suppresses the An base; brief format always has one.
    val srcEaBaseValidEff   = Mux(l0Fixup, !(l0ExtW(8) && l0ExtW(7)), srcEa.baseValid)
    // Task #199 (btst_pcrel_src static-bit-number sub-case): the (d16,PC)/(d8,PC,Xn)
    // PC-relative reference point is the ADDRESS OF THE EA's OWN EXTENSION WORD, not
    // unconditionally op+2 — for an `opIsLineImm` shape (static BTST/BCHG/BCLR/BSET
    // #n,<ea> or CMPI #imm,<ea>) the EA's own ext word is displaced past the
    // immediate's own word(s) (1 word for .B/.W-sized bit-number/immediate, 2 for
    // .L), i.e. its address is op+4 (or op+6 for .L), NOT op+2. Every other
    // (non-immediate) PC-rel family's EA ext word IS at op+2, so this is 0 for them.
    val pcRelImmShiftWords = Mux(opIsLineImm, Mux(immIsLong, U(2, 3 bits), U(1, 3 bits)), U(0, 3 bits))

    // ── Operand classification ───────────────────────────────────────────────
    val srcIsReg = (srcEa.klass === EaClass.DATAREG) || (srcEa.klass === EaClass.ADDRREG)
    val srcIsMem = srcEaIsMemSimpleEff
    val srcEaOk  = srcIsReg || (srcEa.klass === EaClass.IMM) || srcIsMem
    val dstEaOk  = (dstEa.klass === EaClass.DATAREG) || (dstEa.klass === EaClass.ADDRREG)
    val usesSrcEa = (spec.srcA.kind === OperandKind.EASRC) || (spec.srcB.kind === OperandKind.EASRC)
    val usesDstEa = (spec.dst.kind === OperandKind.EADST)

    // The op consumes a memSimple SOURCE EA -> crack a leading load µop into T0,
    // and the op reads T0 in the EASRC slot. EXCEPT ADDQ/SUBQ (srcB = IMMQ3): its
    // EASRC is the DESTINATION (a deferred memory RMW), NOT a load source -> a memory
    // EA there is illegal (addqMemBad), never a leading-load crack.
    val isAddqSubq = spec.srcB.kind === OperandKind.IMMQ3
    // Line-4 single-operand family (CLR/NEG/NEGX/NOT/TST/SWAP/EXT/TAS): the EASRC slot
    // is the DESTINATION operand (data-register only this slice), NOT a load source ->
    // a memory EA is the deferred RMW/store form (illegal), never a leading-load crack.
    val isLine4Unary = spec.op === DecOp.CLR || spec.op === DecOp.NEG || spec.op === DecOp.NEGX ||
                       spec.op === DecOp.NOT || spec.op === DecOp.TST || spec.op === DecOp.SWAP ||
                       spec.op === DecOp.EXT || spec.op === DecOp.TAS

    // ── Memory-destination RMW (MEMSIMPLE EAs) ─────────────────────────────────
    // For ADD/SUB/AND/OR/EOR Dn,<ea> + ADDI/.../EORI #imm,<ea> + ADDQ/SUBQ #n,<ea> +
    // CLR/NEG/NEGX/NOT/TST <ea>, OperationDecoder names the EA as BOTH srcA (read) AND
    // dst (written back) via the EASRC role (spec.dst.kind == EASRC). When that EA is
    // MEMSIMPLE we crack a memory RMW:
    //   crackRmw      : [load.sz <ea> -> T0] [op (T0 + Dn/#imm) -> T1 + flags] [store.sz T1 -> <ea>]
    //   crackLoadOnly : [load.sz <ea> -> T0] [op (flags only)]                 (TST / CMPI / CMP-mem; NO store)
    //   crackClr      : [CLR -> T1 (=0) + Z/N flags]                            [store.sz T1 -> <ea>] (NO load)
    // The EA is op[5:0] = `srcEa` (the SAME descriptor for load and store: MEMSIMPLE has
    // no side effect, so both recompute base+disp identically). SWAP/EXT are Dn-only;
    // TAS <ea> (task #158) now rides the SAME crackRmw load-op-store path -- its ALU
    // datapath (AluDatapath.tasRes) is already generic over srcA's origin (register or
    // the RMW crack's loaded T0), so no EU change was needed.
    // ── Bit op (BTST/BCHG/BCLR/BSET) size/modulo resolution ────────────────────
    // The dest sets the width: Dn (DATAREG) -> LONG (bit mod 32); memory (MEMSIMPLE) ->
    // BYTE (bit mod 8: BTST load-only, BSET/BCLR/BCHG mem-RMW crack). The EU applies the
    // modulo from `size`. OperationDecoder left the BITOP size at the default (WORD), so
    // resolve it here and override the op/load/store µop sizes below.
    val isBitOp     = spec.op === DecOp.BITOP
    val bitOpIsMem  = isBitOp && srcIsMem
    val bitOpSize   = Mux(bitOpIsMem, Size.BYTE, Size.LONG)

    // Memory bit-ops are BYTE-sized, but the bit-op opword has NO size field (the size
    // bits encode the bit-op TYPE), so OperationDecoder left spec.size at the WORD
    // default -> EaDecoder computed autoDelta=2 for an (An)+/-(An) bit-op EA. A BYTE
    // (An)+/-(An) must adjust An by 1 (A7-byte -> 2 to keep the stack pointer even).
    // Recompute the source-EA auto-delta at BYTE for a memory bit-op; non-bit-ops keep
    // srcEa.autoDelta (their spec.size is correct). stUop uses dstEa.autoDelta (MOVE-only,
    // never a bit-op) so it is unaffected.
    val srcIsA7        = srcEa.base === U(15, 5 bits)          // A7 = base 8+7
    val bitOpByteDelta = Mux(srcIsA7, U(2, 3 bits), U(1, 3 bits))
    val srcEaDelta     = Mux(bitOpIsMem, bitOpByteDelta, srcEa.autoDelta)

    val eaIsDst       = (spec.dst.kind === OperandKind.EASRC)
    val rmwOpInScope  = !(spec.op === DecOp.SWAP || spec.op === DecOp.EXT)
    // PC-relative EAs (d16,PC)/(d8,PC,Xn) are NOT alterable -> never a mem-dest RMW/store
    // destination (the 68k forbids writes to PC-space). They are SOURCE-only. Excluding
    // pcRel here keeps an indexed/displaced PC-rel RMW-dest on the illegal path (the
    // mem-dest gates only checked `=/= MEMSIMPLE`, which a pcRel MEMSIMPLE would pass).
    val memDest       = srcIsMem && eaIsDst && rmwOpInScope && !srcEa.pcRel
    val crackClr      = memDest && (spec.op === DecOp.CLR)
    val crackLoadOnly = memDest && !spec.dstWrites                       // TST / CMPI / CMP-mem (no store)
    val crackRmw      = memDest && spec.dstWrites && !crackClr           // load-op-store

    // ── EA auto-update (-(An)/(An)+) markers ───────────────────────────────────
    // The source EA (op[5:0] = srcEa) and (for MOVE) the dest EA (dstEa) may carry an
    // autoMode != NONE. The An := An ± delta write-back is folded into the load/store/
    // RMW crack: a STORE / RMW-store carries the An write on its (otherwise unused) int
    // dst (generalizing stkPush); a LOAD writes its loaded value, so the SOURCE-EA An
    // update rides a separate tiny ADD µop (`anUpdUop`). The load + store of a predec
    // RMW both carry eaAuto/eaDelta so they compute the SAME decremented address; only
    // the store writes An.
    val srcAuto    = srcIsMem && (srcEa.autoMode =/= EaAuto.NONE)
    val dstIsMem   = (dstEa.klass === EaClass.MEMSIMPLE)
    val dstAuto    = dstIsMem && (dstEa.autoMode =/= EaAuto.NONE)

    // MOVE reg -> memSimple destination -> a single STORE µop (data = the register
    // source). RMW (ALU op with a memory dst) is deferred (only MOVE stores). The store
    // data is the MOVE source register (srcB EASRC slot). MOVE #imm -> memSimple is ALSO
    // a crackStore (immToMemCase below): the immediate data cannot ride the STORE's own
    // useImm/imm (already dedicated to the dest EA's address displacement), so it is
    // materialized into T1 by a leading opUop first (2-µop crack), while the register-
    // source case stays the existing 1-µop [stUop] fast path.
    val crackStore = (spec.op === DecOp.MOVE) && usesDstEa && dstIsMem && (srcIsReg || srcEa.klass === EaClass.IMM)
    // MOVE #imm -> memSimple: the immediate-source flavor of crackStore. Needs the 2-µop
    // materialize-then-store crack (opUop writes imm -> T1; stUop stores T1).
    val immToMemCase = crackStore && (srcEa.klass === EaClass.IMM)
    // MOVE mem -> mem: source memSimple AND dest memSimple (any combination of plain /
    // predec / postinc). Cracked into [load src -> T0] [store T0 -> dst], with the dest
    // An update folded into the store and the SOURCE An update as a separate ADD µop
    // (the load occupies its int dst with the loaded value). Both EAs are MEMSIMPLE.
    val crackMemMem = (spec.op === DecOp.MOVE) && usesDstEa && dstIsMem && srcIsMem
    // The generic memSimple-SOURCE load crack: a TRUE source EA (NOT a mem destination)
    // and NOT a mem-to-mem MOVE (which has its own crack below). CMP2/CHK2 also names
    // its EA srcA (EASRC) but owns a bespoke 2-load+compare crack (below) -> excluded.
    val isCmp2Chk2Spec = spec.op === DecOp.CMP2CHK2
    // Bit-field MEMORY load-only form (slice 3a): a DecOp.BITFIELD whose EA is a memory
    // mode (mode>=2 -> NOT the register form's DATAREG). It owns a bespoke crack
    // ([load.L -> T0][opt load.B -> T1][BITFIELD bfMem]) — the EA must be RE-DECODED from
    // a words vector that SKIPS the bf-ext word (words(1)), like CMP2/CHK2. So it is
    // EXCLUDED from the generic crackLoad (whose srcEa mis-decodes disp/abs by one word).
    // Slice 3b: the bit-field RMW ops (BFCHG/BFCLR/BFSET/BFINS) are MICROCODED (the engine
    // owns the load-op-store sequence), so the 3a load-only bfm crack must NOT fire for
    // them — restrict isBfMemSpec to NON-microcoded BITFIELD-mem (the 3a load-only ops).
    val isBfMemSpec = (spec.op === DecOp.BITFIELD) && srcIsMem && !spec.microcoded
    val crackLoad = usesSrcEa && srcIsMem && !isAddqSubq && !isLine4Unary && !memDest && !crackMemMem && !isCmp2Chk2Spec && !isBfMemSpec

    // POST-instruction PC = pc + length(bytes). All µops of one instruction share
    // it so the (single, architectural) op-µop commit pc matches the reference's
    // next-instruction PC even for multi-word memory instructions.
    val nextPc = (pkt.pc + (pkt.lenWords << 1)).resize(32)

    // ── opUop = the operation (EASRC operand routed to T0 when cracked) ────────
    val opUop = DecodedUop()
    opUop.debugBreakValid := False; opUop.debugBreakSlot := 0
    opUop.fpInert()
    opUop.valid         := pkt.valid
    opUop.pc            := pkt.pc
    opUop.nextPc        := nextPc
    opUop.op            := spec.op
    opUop.cluster       := spec.cluster
    opUop.size          := spec.size
    opUop.memOp         := MemOp.NONE
    opUop.srcAReg       := 0; opUop.srcAValid := False
    opUop.srcBReg       := 0; opUop.srcBValid := False
    opUop.srcCReg       := 0; opUop.srcCValid := False
    opUop.dstReg        := 0; opUop.dstValid  := False
    opUop.useImm        := False; opUop.imm    := 0
    opUop.readsNzvc     := spec.readsNzvc; opUop.readsX := spec.readsX
    opUop.writesNzvc    := spec.writesNzvc; opUop.writesX := spec.writesX
    opUop.isBranch      := spec.isBranch; opUop.ibranch := False; opUop.stkPush := False; opUop.anInc := 0; opUop.isReturn := False; opUop.ccrRestore := False; opUop.toCcr := False; opUop.cond := spec.cond
    opUop.eaAuto        := EaAuto.NONE; opUop.eaDelta := 0
    opUop.branchDisp    := 0
    opUop.unimplemented := False
    opUop.faulted       := False
    opUop.faultVector   := 0
    opUop.faultUsesNextPc := False  // default: stack the faulting instr PC (pc); TRAP/TRAPV -> nextPc
    opUop.fpuSoftwareComplete := False; opUop.fpuCmdWord := B(0, 16 bits)
    opUop.sswInstr      := False; opUop.faultAtc := True
    opUop.isRte         := False
    opUop.isCondTrap    := False
    opUop.divSigned     := spec.divSigned
    opUop.div64         := spec.div64
    opUop.divIsRem      := False
    opUop.isChk2        := False
    opUop.shiftOp       := spec.shiftOp
    opUop.shiftDir      := spec.shiftDir
    opUop.bcdSub        := spec.bcdSub
    opUop.bitOp         := spec.bitOp
    opUop.bfOp          := spec.bfOp
    opUop.bfDynamic     := False
    opUop.bfMem         := False
    opUop.bfStoreForm   := 0
    opUop.extByte       := spec.extByte
    opUop.isMovea       := False
    opUop.isScc         := False; opUop.isDbcc := False
    opUop.indexLong     := False; opUop.indexScale := 0
    opUop.leaAddr := False; opUop.movesAliasStore := False; opUop.fromCcr := False; opUop.fromSr := False; opUop.needsSupervisor := False; opUop.keepCommit := False
    opUop.sysOp := False; opUop.sysKind := SysKind.NONE; opUop.sysReadDir := False
    opUop.predTaken := False; opUop.predTarget := U(0, 32 bits)
    opUop.phtValid := False; opUop.phtIndex := U(0, 11 bits); opUop.casForm := 0
    // CHK / DIV are group-2 traps (CHK vec6, DIV0 vec5) delivered execute-time via
    // euFault -> format-$2: they stack the NEXT instruction's PC (the 040 group-2
    // frame's PC = pc+len). The fault is conditional (set at execute), but faultPc is
    // captured at ALLOC, so faultUsesNextPc must be set NOW for the CPLX ops.
    when(spec.op === DecOp.CHK || spec.op === DecOp.DIV) {
      opUop.faultUsesNextPc := True
    }
    // The op µop is the FIRST µop of its instruction EXCEPT when a LOAD precedes it:
    // a memSimple-source crack ([load, op]) OR a mem-dest RMW / load-only crack
    // ([load, op, (store)]). For crackClr the op IS first (no leading load). (For every
    // other path opUop is uops(0), the macro-instruction boundary.)
    val opHasLeadingLoad = crackLoad || crackRmw || crackLoadOnly
    opUop.firstOfInstr  := !opHasLeadingLoad
    opUop.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)

    // --- srcA slot ---
    switch(spec.srcA.kind) {
      is(OperandKind.REGFIELD) {
        when(spec.srcA.isAddr) { opUop.srcAReg := (U(8, 5 bits) + op(11 downto 9).asUInt).resized }
          .otherwise { opUop.srcAReg := op(11 downto 9).asUInt.resize(5) }
        opUop.srcAValid := True
      }
      is(OperandKind.EASRC) {
        when(srcEa.klass === EaClass.IMM) { opUop.useImm := True; opUop.imm := srcEa.imm }
          .elsewhen(srcIsMem) { opUop.srcAReg := U(T0, 5 bits); opUop.srcAValid := True }
          .otherwise { opUop.srcAReg := srcEa.reg; opUop.srcAValid := True }
      }
      is(OperandKind.EADST) { opUop.srcAReg := dstEa.reg; opUop.srcAValid := True }
      is(OperandKind.IMMQ)  { opUop.useImm := True; opUop.imm := op(7 downto 0).asSInt.resize(32).asBits }
      default {}
    }

    // --- srcB slot ---
    switch(spec.srcB.kind) {
      is(OperandKind.REGFIELD) {
        when(spec.srcB.isAddr) { opUop.srcBReg := (U(8, 5 bits) + op(11 downto 9).asUInt).resized }
          .otherwise { opUop.srcBReg := op(11 downto 9).asUInt.resize(5) }
        opUop.srcBValid := True
      }
      is(OperandKind.EASRC) {
        when(srcEa.klass === EaClass.IMM) { opUop.useImm := True; opUop.imm := srcEa.imm }
          .elsewhen(srcIsMem) { opUop.srcBReg := U(T0, 5 bits); opUop.srcBValid := True }
          .otherwise { opUop.srcBReg := srcEa.reg; opUop.srcBValid := True }
      }
      is(OperandKind.EADST) { opUop.srcBReg := dstEa.reg; opUop.srcBValid := True }
      is(OperandKind.IMMQ)  { opUop.useImm := True; opUop.imm := op(7 downto 0).asSInt.resize(32).asBits }
      is(OperandKind.IMMEXT) { opUop.useImm := True; opUop.imm := immExt }   // line-0 trailing imm word(s)
      is(OperandKind.IMMQ3) {
        // ADDQ/SUBQ quick immediate: ddd = op[11:9], 1-8 with ddd==0 -> 8. Always
        // POSITIVE (1-8), zero-extended (the An full-32 path adds/subtracts it whole).
        val ddd = op(11 downto 9).asUInt
        opUop.useImm := True
        opUop.imm    := Mux(ddd === 0, U(8, 4 bits), ddd.resize(4)).resize(32).asBits
      }
      default {}
    }

    // --- dst slot ---
    switch(spec.dst.kind) {
      is(OperandKind.REGFIELD) {
        when(spec.dst.isAddr) { opUop.dstReg := (U(8, 5 bits) + op(11 downto 9).asUInt).resized }
          .otherwise { opUop.dstReg := op(11 downto 9).asUInt.resize(5) }
        opUop.dstValid := True
      }
      is(OperandKind.EASRC) {
        when(srcEa.klass =/= EaClass.IMM) {
          // memDest (RMW/CLR): the op result -> T1 (the trailing store reads T1, then
          // recomputes the EA). Otherwise (a data-reg EA destination) -> the EA register.
          when(memDest) { opUop.dstReg := U(T1, 5 bits) }
            .elsewhen(srcIsMem) { opUop.dstReg := U(T0, 5 bits) } .otherwise { opUop.dstReg := srcEa.reg }
          opUop.dstValid := True
        }
      }
      is(OperandKind.EADST) { opUop.dstReg := dstEa.reg; opUop.dstValid := True }
      default {}
    }
    when(spec.dst.kind =/= OperandKind.NONE && spec.dstWrites) { opUop.dstValid := True }
    when(spec.dst.kind =/= OperandKind.NONE && !spec.dstWrites) { opUop.dstValid := False } // CMP/CMPA

    // MOVE #imm,<mem> materialize (immToMemCase): override the generic EADST dst-routing
    // above (which pointed at dstEa.reg — meaningless for a MEMSIMPLE dest) so the op µop
    // writes the immediate into T1 instead. useImm/imm are already correctly populated by
    // the srcB EASRC-IMM case above (spec.srcB.kind===EASRC, srcEa.klass===IMM); the
    // trailing stUop then stores T1 (srcBReg mux below).
    when(immToMemCase) {
      opUop.dstReg   := U(T1, 5 bits)
      opUop.dstValid := True
    }

    // CLR mem-dest crack: the op writes 0 (no load precedes it), so it must NOT read T0
    // (there is no producing load). Drop the srcA/srcB reads — CLR ignores its input.
    when(crackClr) {
      opUop.srcAValid := False
      opUop.srcBValid := False
    }

    // ── Line-E shift/rotate operand routing (DecOp.SHIFT) ──────────────────────
    // Fixed-field operands: srcA = dst = Dr (op[2:0], the shifted data reg). Count:
    //  i=0 (shiftImm) -> immediate ccc = op[11:9]; ccc==0 means 8 (useImm/imm).
    //  i=1            -> 2nd data-reg source Dc = op[11:9] (srcB).
    // GATED to the REGISTER form only (spec.dst.kind =/= EASRC): the MEMORY form
    // (task #170-cluster10, below) already has srcA=dst=easrc set by the decoder,
    // so the generic EASRC switch above has ALREADY routed srcAReg/dstReg to
    // T0/T1 (crackRmw's load/store temps) -- this fixed-field Dr/ccc routing must
    // not clobber that (op[2:0]/op[11:9] mean something totally different for the
    // memory form: the EA's register field and the shift TYPE, not Dr/Dc).
    when(spec.op === DecOp.SHIFT && (spec.dst.kind =/= OperandKind.EASRC)) {
      val dr = op(2 downto 0).asUInt.resize(5)
      val ccc = op(11 downto 9).asUInt
      opUop.srcAReg := dr; opUop.srcAValid := True       // Dr (shift input)
      opUop.dstReg  := dr; opUop.dstValid  := True       // Dr (shift result)
      when(spec.shiftImm) {
        // immediate count: ccc 1..8, with ccc==0 -> 8.
        val cnt = Mux(ccc === 0, U(8, 6 bits), ccc.resize(6))
        opUop.useImm := True
        opUop.imm    := cnt.resize(32).asBits
        opUop.srcBValid := False
      } otherwise {
        // register count: srcB = Dc (op[11:9]); the EU masks to 6 bits.
        opUop.srcBReg := ccc.resize(5); opUop.srcBValid := True
        opUop.useImm  := False
      }
    }
    // ── Line-E MEMORY-form shift/rotate operand routing (task #170-cluster10) ──
    // srcA/dst are already generically routed to T0/T1 by the ordinary EASRC
    // switch above (crackRmw's load produces T0; the compute op reads/writes it
    // via T1, same as CLR/NEG/NOT/TAS-mem). Only the implicit count needs
    // forcing here -- the real ISA's memory-form shift always shifts exactly 1
    // bit; there is no register/immediate count operand at this encoding.
    when(spec.op === DecOp.SHIFT && (spec.dst.kind === OperandKind.EASRC)) {
      opUop.useImm    := True
      opUop.imm       := U(1, 32 bits).asBits
      opUop.srcBValid := False
    }

    // ── PACK/UNPK register form operand routing ─────────────────────────────────
    // srcA = Dx (op[11:9], old value = merge source for .B/.W upper-bit preservation) —
    // already routed via dnField REGFIELD above. srcB = Dy (op[2:0], source data) —
    // routed via EASRC (mode 000 DATAREG). Additionally, adj16 = pkt.words(1) rides
    // `imm` (useImm=True); the EU reads rdB.data (s1RdB) directly for Dy, bypassing
    // the useImm mux. Dst = Dx (dnField, already routed above).
    val packUnpkReg = (spec.op === DecOp.PACK) || (spec.op === DecOp.UNPK)
    when(packUnpkReg) {
      opUop.useImm := True
      opUop.imm    := pkt.words(1).resize(16).asSInt.resize(32).asBits  // adj16, sign-extended
    }

    // ── Bit-field register form operand routing (DecOp.BITFIELD) ────────────────
    // srcA = Dy (op[2:0], the field register). For BFINS (bfOp=7) srcB = Dn2 (the
    // insert source, ext[14:12]). dst = Dn2 (BFEXTU/BFEXTS/BFFFO, bfOp 4/5/6) / Dy
    // (BFCHG/BFCLR/BFSET/BFINS, bfOp 1/2/3/7) / none (BFTST, bfOp 0). The static
    // offset(5b)=ext[10:6] + raw width(5b)=ext[4:0] (0->32) are packed into imm
    // (imm[4:0]=offset, imm[9:5]=width). The EU normalizes width = ((width-1)&31)+1.
    val isBitfield = spec.op === DecOp.BITFIELD
    // Dynamic offset/width: Do=ext[11], Dw=ext[5]. When set, the offset/width come from
    // a register (read by the leading BFRESOLVE µop into T0) instead of the static imm.
    val bfExt   = pkt.words(1)
    val bfDo    = bfExt(11)
    val bfDw    = bfExt(5)
    val bfDyn   = isBitfield && (bfDo || bfDw) && !isBfMemSpec
    when(isBitfield && !isBfMemSpec) {
      val ext   = pkt.words(1)
      val dy    = op(2 downto 0).asUInt.resize(5)         // field register
      val dn2   = ext(14 downto 12).asUInt.resize(5)      // Dn2 (dest for EXTU/EXTS/FFO; src for INS)
      val off5  = ext(10 downto 6)                        // static offset 0..31
      val wd5   = ext(4 downto 0)                         // static raw width (0->32)
      // bfOp = op[10:8] (020 encoding): 0=BFTST,1=BFEXTU,2=BFCHG,3=BFEXTS,4=BFCLR,
      // 5=BFFFO,6=BFSET,7=BFINS. EXTU/EXTS/FFO (1/3/5) write Dn2; CHG/CLR/SET (2/4/6)
      // + INS (7) write Dy; TST (0) writes nothing. Only INS reads Dn2 (srcB).
      val bf    = spec.bfOp
      val isExtFfo = (bf === 1) || (bf === 3) || (bf === 5)   // BFEXTU/BFEXTS/BFFFO -> dst Dn2
      val isIns    = (bf === 7)                                // BFINS -> reads Dn2, writes Dy
      val isTst    = (bf === 0)                                // BFTST -> no write
      // srcA = Dy (field register)
      opUop.srcAReg := dy; opUop.srcAValid := True
      // srcB = Dn2 only for BFINS (the insert source register)
      opUop.srcBReg := dn2; opUop.srcBValid := isIns
      // dst: Dn2 for EXTU/EXTS/FFO; Dy for CHG/CLR/SET/INS; none for TST
      when(isExtFfo) { opUop.dstReg := dn2; opUop.dstValid := True }
        .elsewhen(isTst) { opUop.dstReg := 0; opUop.dstValid := False }
        .otherwise { opUop.dstReg := dy; opUop.dstValid := True }
      // STATIC: pack offset(5)+width(5) into imm; useImm so the EU's s1Src2 carries it.
      // DYNAMIC (Do||Dw): the packed offset/width is produced by the leading BFRESOLVE
      // µop into T0, read here via srcC; flag bfDynamic so the EU takes srcC[9:0] (NOT
      // imm). srcA=Dy / srcB=Dn2 (BFINS) unchanged. (BFINS-dynamic = Dy+Dn2+T0 = 3 srcs.)
      opUop.useImm := True
      opUop.imm    := (B(0, 22 bits) ## wd5 ## off5).resize(32)
      when(bfDyn) {
        opUop.bfDynamic := True
        opUop.srcCReg   := U(T0, 5 bits); opUop.srcCValid := True
        opUop.firstOfInstr := False        // the BFRESOLVE crack µop is first
      }
    }

    // MOVE: NZVC only if the destination EA is a data register.
    when(spec.writesNzvcIfDataDst) { opUop.writesNzvc := (dstEa.klass === EaClass.DATAREG) }

    // ── MOVE.B/.W to a DATA register: partial-register write (preserve Dn upper) ─
    // 68k semantics: MOVE.B writes Dn[7:0] / preserves Dn[31:8]; MOVE.W writes
    // Dn[15:0] / preserves Dn[31:16]. The ALU EU does the .B/.W size-merge using
    // s1Src1 (= srcA) as the OLD-value source — but a plain MOVE has no srcA, so the
    // EU would drop the upper bytes. Make the .B/.W reg-dest MOVE READ the old Dn as
    // srcA (the merge source); the EU's normal size-merge then preserves the upper
    // bytes. This adds a partial-register DEPENDENCY (the MOVE now reads its own
    // destination); rename allocates psrcA from the dest's current mapping and the IQ
    // wakeup (uop.psrcAValid) tracks it. UNCHANGED: MOVE.L (full 32 write, no merge
    // needed -> no srcA read), MOVE to memory (crackStore -> stUop, opUop unused),
    // MOVEA / MOVE to An (dstEa is ADDRREG, not DATAREG -> excluded; An is full-32).
    val moveDataDst = (spec.op === DecOp.MOVE) && (dstEa.klass === EaClass.DATAREG)
    val movePartial = moveDataDst && (spec.size === Size.BYTE || spec.size === Size.WORD)
    when(movePartial) {
      opUop.srcAReg   := dstEa.reg     // the destination Dn = the merge (old-value) source
      opUop.srcAValid := True
    }

    // ── MOVEA (MOVE with an ADDRESS-register destination) ──────────────────────
    // An is ALWAYS written full-32 (no partial merge) and sets NO flags; the .W form
    // sign-extends the 16-bit source. Mark the op so the ALU EU bypasses the .B/.W
    // merge and sign-extends a .W source. (A reg-direct An source is DATAREG/ADDRREG,
    // so no leading load is cracked; MOVEA's source EA is the usual srcB EASRC.)
    val moveAddrDst = (spec.op === DecOp.MOVE) && (dstEa.klass === EaClass.ADDRREG)
    when(moveAddrDst) {
      opUop.isMovea := True
    }

    // ── ADDA/SUBA/CMPA (An-destination arithmetic; dst = REGFIELD An) ──────────
    // An is written full-32 with NO partial merge and (ADDA/SUBA) NO flags; the .W
    // form SIGN-EXTENDS the 16-bit source to 32 before the full-32 op (Musashi
    // adda/suba/cmpa: `AX ± MAKE_INT_16(src)` / `dst - MAKE_INT_16(src)` at 32-bit
    // width, incl. CMPA's flags). Reuse `isMovea` as the "An-wide" marker on a
    // non-MOVE ALU op: the ALU EU widens the op to LONG, sign-extends src2 from
    // the .W size, bypasses the size-merge, and computes (CMPA) flags at 32-bit.
    // (isMovea on op==MOVE keeps its existing MOVEA meaning — the EU discriminates
    // on the op.)
    val anArith = (spec.dst.kind === OperandKind.REGFIELD) && spec.dst.isAddr &&
                  ((spec.op === DecOp.ADD) || (spec.op === DecOp.SUB) || (spec.op === DecOp.CMP))
    when(anArith) {
      opUop.isMovea := True
    }

    // ── ADDQ/SUBQ #n,An — full-32, NO flags (like ADDA/SUBA) ───────────────────
    // ADDQ/SUBQ (srcB = IMMQ3) whose DESTINATION EA (op[5:0] = srcEa, since dst=EASRC)
    // resolves to an ADDRESS register: the operation is full-32 regardless of the .B/.W
    // size field and writes NO condition codes (the 68k An rule). Force size LONG (the
    // ALU does a full-32 ADD/SUB; the .B/.W partial merge is bypassed) and clear the
    // flag write masks. The Dn-dest case (srcEa = DATAREG) keeps size/NZVCX from decode.
    val addqAddrDst = (spec.srcB.kind === OperandKind.IMMQ3) && (srcEa.klass === EaClass.ADDRREG)
    when(addqAddrDst) {
      opUop.size       := Size.LONG
      opUop.writesNzvc := False
      opUop.writesX    := False
    }

    // ── Bit op: resolve the op-µop size from the dest (Dn=LONG mod32 / mem=BYTE mod8) ─
    when(isBitOp) {
      opUop.size := bitOpSize
    }

    // Branch displacement (byte / word / long), reproducing the simple-decode rule.
    when(spec.isBranch) {
      val disp8 = op(7 downto 0)
      when(disp8 === 0x00) { opUop.branchDisp := pkt.words(1).asSInt.resize(32).asBits }
        .elsewhen(disp8 === M"11111111") { opUop.branchDisp := pkt.words(1) ## pkt.words(2) }
        .otherwise { opUop.branchDisp := disp8.asSInt.resize(32).asBits }
    }

    // ── ldUop = the LOAD (used only when crackLoad) ────────────────────────────
    // Address = base An (psrcA) + disp(imm); for (d16,PC) the PC is folded into the
    // absolute disp (base=0). dst = T0.
    val ldUop = DecodedUop()
    ldUop.debugBreakValid := False; ldUop.debugBreakSlot := 0
    ldUop.fpInert()
    ldUop.valid         := pkt.valid
    ldUop.pc            := pkt.pc
    ldUop.nextPc        := nextPc
    ldUop.op            := DecOp.MOVE
    ldUop.cluster       := Cluster.LS
    // mem-RMW / load-only access size: BYTE for a bit-op (mem BITOP is always byte),
    // else the op size. (BITOP left spec.size at the WORD default; resolve to BYTE.)
    ldUop.size          := Mux(isBitOp, Size.BYTE, spec.size)
    ldUop.memOp         := MemOp.LOAD
    ldUop.srcAReg       := srcEa.base; ldUop.srcAValid := srcEaBaseValidEff
    ldUop.srcBReg       := 0;          ldUop.srcBValid := False
    // Indexed EA: the index register rides srcC; the AGU sizes+scales it. For a line-0
    // immediate mem-dest the index descriptor lives on immEa (its ext = the brief word,
    // following the imm); otherwise srcEa. base+disp already account for pcRel/disp.
    val ldIdxEa = Mux(opIsLineImm, immEa, srcEa)
    ldUop.srcCReg       := ldIdxEa.indexReg;   ldUop.srcCValid := ldIdxEa.indexValid
    ldUop.indexLong     := ldIdxEa.indexLong;  ldUop.indexScale := ldIdxEa.indexScale
    // Task #189 code-review fix: a MEMORY-source privileged commit-time SYSTEM op
    // (e.g. MOVE.W <ea>,SR with a memory <ea> — move_ea_sr_memsrc_priv.s) cracks
    // into THIS generic load (reading <ea> into T0) followed by the sysOp µop
    // (which applies T0 to SR and is where the EXISTING privilege check —
    // RobPlugin's Track-D `sysPrivFault`, gated at the sysOp's OWN commit — lives).
    // On real 68k hardware the privilege check happens BEFORE the operand is even
    // fetched; this core's crack does the load unconditionally and only checks
    // privilege later, at the sysOp. That ordering gap was previously DORMANT
    // (harmless) because the load could never itself raise an architectural fault
    // — until task #189 added genuine bus-error delivery: a user-mode access to
    // an unmapped EA now faults THIS load (a program-order-EARLIER ROB entry than
    // the sysOp), which — being an earlier fault — squashes everything younger
    // (including the sysOp) before its privilege check ever runs, wrongly
    // delivering vector 2 instead of vector 8. Tagging this load `needsSupervisor`
    // (mirroring Track C's existing per-µop flag, harmless to reuse: RobPlugin's
    // `privOnly = privViolation && !faultedStore(h0)` already refuses to let a
    // FAULTED head take the priv vector, so this alone does not misfire when the
    // load actually faults) lets LsEuPlugin's bus-error path (task #189) check it
    // and suppress the fault report for exactly this one crack shape — see that
    // file's WAIT-state comment — restoring the ORIGINAL (pre-task-189) behavior
    // for the faulting case (the load completes with unused/don't-care data; the
    // sysOp's own Track-D check still correctly delivers vector 8) while ALSO
    // fixing the non-faulting case for free (a privileged memory-source sysOp
    // accessing ordinary, mapped, but still user-inaccessible memory now correctly
    // traps via Track C at the load itself, instead of relying solely on the
    // later sysOp commit).
    ldUop.leaAddr := False; ldUop.movesAliasStore := False; ldUop.fromCcr := False; ldUop.fromSr := False; ldUop.needsSupervisor := spec.sysOp; ldUop.keepCommit := False
    ldUop.sysOp := False; ldUop.sysKind := SysKind.NONE; ldUop.sysReadDir := False
    ldUop.predTaken := False; ldUop.predTarget := U(0, 32 bits)
    ldUop.phtValid := False; ldUop.phtIndex := U(0, 11 bits); ldUop.casForm := 0
    ldUop.dstReg        := U(T0, 5 bits); ldUop.dstValid := True
    ldUop.useImm        := True
    // disp = rmwEaDisp (immEa for a line-0 immediate mem-dest, else srcEa). A (d16,PC)
    // source folds pc into the absolute disp.
    // Task #199 (btst_pcrel_src static-bit-number sub-case): a static bit-op
    // (BTST/BCHG/BCLR/BSET #n,<ea>) is ALSO an `opIsLineImm` shape (srcB=IMMEXT,
    // same as ADDI/CMPI/etc — OperationDecoder's shared bit-op table sets
    // `o.srcB := immext` for the static form) whose EA's own extension word(s)
    // sit AFTER the bit-number ext word, i.e. `rmwEaDisp` (immEa.disp, already
    // correctly shifted by the imm word count) is the right displacement — NOT
    // the raw `srcEa.disp` (decoded against UNSHIFTED words, so for a static
    // bit-op it misreads the bit-number word itself as the d16 displacement).
    // This previously miscomputed the PC-relative absolute address for BOTH
    // static BTST #n,(d16,PC)/(d8,PC,Xn) (the only bit-op family whose PC-rel
    // target is architecturally legal, since it's read-only) and a CMPI
    // #imm,(d16,PC)/(d8,PC,Xn) source (also legal, also read-only) — no
    // corpus test exercised the CMPI case, but it shares the exact same bug.
    // `rmwEaDisp` already resolves opIsLineImm vs not (see its definition
    // above), so reusing it here (instead of the narrower `srcEa.disp`) fixes
    // the displacement value; `pcRelImmShiftWords` (see its definition above)
    // corrects the REFERENCE POINT itself (op+2 normally, op+4/op+6 for a
    // static-bit-op/CMPI immediate shape whose EA ext word is displaced past
    // the imm word(s)).
    val pcRelAddr = (pkt.pc + U(2, 32 bits) + (pcRelImmShiftWords << 1).resize(32 bits) +
                      rmwEaDisp.asUInt).asBits
    ldUop.imm           := Mux(srcEa.pcRel, pcRelAddr, rmwEaDisp)
    ldUop.readsNzvc     := False; ldUop.readsX := False
    ldUop.writesNzvc    := False; ldUop.writesX := False
    ldUop.isBranch      := False; ldUop.ibranch := False; ldUop.stkPush := False; ldUop.anInc := 0; ldUop.isReturn := False; ldUop.ccrRestore := False; ldUop.toCcr := False; ldUop.cond := 0
    // Auto-update SOURCE EA: the load computes the access address (PREDEC: An-delta;
    // POSTINC: An). The load does NOT write An (its int dst is the loaded value T0); the
    // An update rides a separate ADD µop (anUpdUop). For crackRmw the SAME eaAuto is on
    // BOTH the load (here) and the store so they access the same predec address.
    ldUop.eaAuto        := srcEa.autoMode; ldUop.eaDelta := srcEaDelta
    ldUop.branchDisp    := 0
    ldUop.unimplemented := False
    ldUop.faulted       := False; ldUop.faultVector := 0; ldUop.isRte := False
    ldUop.faultUsesNextPc := False
    ldUop.fpuSoftwareComplete := False; ldUop.fpuCmdWord := B(0, 16 bits)
    ldUop.sswInstr := False; ldUop.faultAtc := True; ldUop.isCondTrap := False
    ldUop.divSigned     := False; ldUop.div64 := False; ldUop.divIsRem := False
    ldUop.isChk2        := False
    ldUop.shiftOp := 0; ldUop.shiftDir := False; ldUop.isMovea := False; ldUop.isScc := False; ldUop.isDbcc := False; ldUop.extByte := False; ldUop.bitOp := 0; ldUop.bfOp := 0; ldUop.bfDynamic := False; ldUop.bfMem := False; ldUop.bfStoreForm := 0; ldUop.bcdSub := False
    ldUop.firstOfInstr  := True    // the LOAD is the FIRST µop of a cracked instruction
    ldUop.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)

    // MOVE #imm,<mem> destination EA RE-DECODE (immToMemCase only): the immediate
    // SOURCE precedes the destination EA's own extension words in the instruction
    // stream, so the offloaded `dstEa` (computed on the immediate-oblivious words
    // vector — words(1) assumed to be the dst's own first ext word) mis-reads disp/
    // index for any dst mode that HAS its own ext word ((d16,An)/(d8,An,Xn)/abs.W/
    // abs.L) — mirrors the immEa RMW re-decode above (shift by the imm word count).
    // (An)/(An)+/-(An) carry NO ext word (base/klass/autoMode come straight off the
    // opword mode/reg bits, independent of word content) so they are byte-identical
    // either way; only `disp`/index need the shift.
    val immDstEaField = op(8 downto 6) ## op(11 downto 9)
    // FOUR shifted words, not three -- identical rationale to `immEa` above (Part 122's
    // long-base-displacement truncation); this is the MOVE #imm,<mem> sibling.
    val immDstEa = EaDecoder.decode(
      immDstEaField, spec.size, immShiftedWords(pkt.words, immIsLong))
    val stDstEa = Mux(immToMemCase, immDstEa, dstEa)

    // ── stUop = the STORE (used only when crackStore) ──────────────────────────
    // Address = dst base An (psrcA) + dst disp(imm); data = the MOVE source register
    // (srcB). No int dst. MOVE to memory DOES set NZVC from the moved value (N=sign,
    // Z=value==0, V=0, C=0) — implementation (a): the STORE µop carries writesNzvc +
    // a renamed NZVC dest; the LS EU computes N/Z of the store data at the access size
    // and writes the NZVC PRF (+ bypass) at completion. (MOVEA — to an address reg —
    // never reaches here: an address-reg dst is not memSimple.)
    val stUop = DecodedUop()
    stUop.debugBreakValid := False; stUop.debugBreakSlot := 0
    stUop.fpInert()
    stUop.valid         := pkt.valid
    stUop.pc            := pkt.pc
    stUop.nextPc        := nextPc
    stUop.op            := DecOp.MOVE
    stUop.cluster       := Cluster.LS
    stUop.size          := spec.size
    stUop.memOp         := MemOp.STORE
    stUop.srcAReg       := stDstEa.base; stUop.srcAValid := stDstEa.baseValid
    // store DATA: the MOVE source register (reg-to-mem), OR the loaded value T0 for a
    // mem-to-mem MOVE (the leading load wrote T0), OR the materialized immediate T1 for
    // a MOVE #imm,<mem> (immToMemCase — the leading opUop wrote T1). The same store µop
    // folds the dest-EA An update + the MOVE-to-mem NZVC for all three forms.
    stUop.srcBReg       := Mux(crackMemMem, U(T0, 5 bits), Mux(immToMemCase, U(T1, 5 bits), srcEa.reg)); stUop.srcBValid := True
    // Indexed MOVE destination: the dest-EA index reg rides srcC (store DATA is srcB). A
    // mem-to-mem MOVE composes: srcA=dst base, srcB=T0 (loaded data), srcC=dst index.
    stUop.srcCReg       := stDstEa.indexReg;  stUop.srcCValid := stDstEa.indexValid
    stUop.indexLong     := stDstEa.indexLong; stUop.indexScale := stDstEa.indexScale
    stUop.leaAddr := False; stUop.movesAliasStore := False; stUop.fromCcr := False; stUop.fromSr := False; stUop.needsSupervisor := False; stUop.keepCommit := False
    stUop.sysOp := False; stUop.sysKind := SysKind.NONE; stUop.sysReadDir := False
    stUop.predTaken := False; stUop.predTarget := U(0, 32 bits)
    stUop.phtValid := False; stUop.phtIndex := U(0, 11 bits); stUop.casForm := 0
    // Auto-update DEST EA (-(An)/(An)+): the store's (otherwise unused) int dst carries
    // the An write (An := An ± delta) — generalizing stkPush to any An. PREDEC: addr =
    // An-delta = the written An; POSTINC: addr = An, written An = An+delta. The LS EU
    // selects compData per eaAuto. A non-auto store writes no int reg (dstValid False).
    stUop.dstReg        := stDstEa.base; stUop.dstValid  := dstAuto
    stUop.useImm        := True
    val stPcRelAddr = (pkt.pc + U(2, 32 bits) + stDstEa.disp.asUInt).asBits
    stUop.imm           := Mux(stDstEa.pcRel, stPcRelAddr, stDstEa.disp)
    stUop.readsNzvc     := False; stUop.readsX := False
    stUop.writesNzvc    := True;  stUop.writesX := False   // MOVE to memory sets NZVC
    stUop.isBranch      := False; stUop.ibranch := False; stUop.stkPush := False; stUop.anInc := 0; stUop.isReturn := False; stUop.ccrRestore := False; stUop.toCcr := False; stUop.cond := 0
    stUop.eaAuto        := stDstEa.autoMode; stUop.eaDelta := stDstEa.autoDelta
    stUop.branchDisp    := 0
    stUop.unimplemented := False
    stUop.faulted       := False; stUop.faultVector := 0; stUop.isRte := False
    stUop.faultUsesNextPc := False
    stUop.fpuSoftwareComplete := False; stUop.fpuCmdWord := B(0, 16 bits)
    stUop.sswInstr := False; stUop.faultAtc := True; stUop.isCondTrap := False
    stUop.divSigned     := False; stUop.div64 := False; stUop.divIsRem := False
    stUop.isChk2        := False
    stUop.shiftOp := 0; stUop.shiftDir := False; stUop.isMovea := False; stUop.isScc := False; stUop.isDbcc := False; stUop.extByte := False; stUop.bitOp := 0; stUop.bfOp := 0; stUop.bfDynamic := False; stUop.bfMem := False; stUop.bfStoreForm := 0; stUop.bcdSub := False
    // A single reg-to-mem STORE is its own first µop; a mem-to-mem store TRAILS the load,
    // and an immediate-materialize store (immToMemCase) TRAILS the materialize opUop.
    stUop.firstOfInstr  := !crackMemMem && !immToMemCase
    stUop.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)

    // ── rmwStUop = the STORE of a memory-destination RMW (crackRmw / crackClr) ──
    // The EA is op[5:0] = `srcEa` (the SAME descriptor the load used — MEMSIMPLE has no
    // side effect, so base+disp recompute identically). data = T1 (the op result). NO
    // int dst, NO flags (the op µop owns NZVCX). firstOfInstr=False (a trailing µop).
    val rmwStUop = DecodedUop()
    rmwStUop.debugBreakValid := False; rmwStUop.debugBreakSlot := 0
    rmwStUop.fpInert()
    rmwStUop.valid         := pkt.valid
    rmwStUop.pc            := pkt.pc
    rmwStUop.nextPc        := nextPc
    rmwStUop.op            := DecOp.MOVE
    rmwStUop.cluster       := Cluster.LS
    rmwStUop.size          := Mux(isBitOp, Size.BYTE, spec.size)   // bit-op store is byte
    rmwStUop.memOp         := MemOp.STORE
    rmwStUop.srcAReg       := srcEa.base; rmwStUop.srcAValid := srcEaBaseValidEff
    rmwStUop.srcBReg       := U(T1, 5 bits); rmwStUop.srcBValid := True       // store data = T1
    // Indexed RMW: load + store share ONE EA; the index reg rides srcC on BOTH so they
    // compute the SAME indexed address (mirrors how eaAuto is on both).
    val rmwIdxEa = Mux(opIsLineImm, immEa, srcEa)
    rmwStUop.srcCReg       := rmwIdxEa.indexReg;  rmwStUop.srcCValid := rmwIdxEa.indexValid
    rmwStUop.indexLong     := rmwIdxEa.indexLong; rmwStUop.indexScale := rmwIdxEa.indexScale
    rmwStUop.leaAddr := False; rmwStUop.movesAliasStore := False; rmwStUop.fromCcr := False; rmwStUop.fromSr := False; rmwStUop.needsSupervisor := False; rmwStUop.keepCommit := False
    rmwStUop.sysOp := False; rmwStUop.sysKind := SysKind.NONE; rmwStUop.sysReadDir := False
    rmwStUop.predTaken := False; rmwStUop.predTarget := U(0, 32 bits)
    rmwStUop.phtValid := False; rmwStUop.phtIndex := U(0, 11 bits); rmwStUop.casForm := 0
    // Auto-update RMW EA (-(An)/(An)+): the load + this store share ONE EA and ONE An
    // update — the store carries the An write (An := An ± delta) on its int dst (the
    // load carries the SAME eaAuto for its address but writes only T0). The An write
    // lands EXACTLY once (on the store).
    rmwStUop.dstReg        := srcEa.base; rmwStUop.dstValid  := srcAuto
    rmwStUop.useImm        := True
    // Same EA as the load (MEMSIMPLE recompute): rmwEaDisp (immEa for a line-0 immediate).
    val rmwStPcRelAddr = (pkt.pc + U(2, 32 bits) + srcEa.disp.asUInt).asBits
    rmwStUop.imm           := Mux(srcEa.pcRel, rmwStPcRelAddr, rmwEaDisp)
    rmwStUop.readsNzvc     := False; rmwStUop.readsX := False
    rmwStUop.writesNzvc    := False; rmwStUop.writesX := False   // the op µop owns the flags
    rmwStUop.isBranch      := False; rmwStUop.ibranch := False; rmwStUop.stkPush := False; rmwStUop.anInc := 0; rmwStUop.isReturn := False; rmwStUop.ccrRestore := False; rmwStUop.toCcr := False; rmwStUop.cond := 0
    rmwStUop.eaAuto        := srcEa.autoMode; rmwStUop.eaDelta := srcEaDelta
    rmwStUop.branchDisp    := 0
    rmwStUop.unimplemented := False
    rmwStUop.faulted       := False; rmwStUop.faultVector := 0; rmwStUop.isRte := False
    rmwStUop.faultUsesNextPc := False
    rmwStUop.fpuSoftwareComplete := False; rmwStUop.fpuCmdWord := B(0, 16 bits)
    rmwStUop.sswInstr := False; rmwStUop.faultAtc := True; rmwStUop.isCondTrap := False
    rmwStUop.divSigned     := False; rmwStUop.div64 := False; rmwStUop.divIsRem := False
    rmwStUop.isChk2        := False
    rmwStUop.shiftOp := 0; rmwStUop.shiftDir := False; rmwStUop.isMovea := False; rmwStUop.isScc := False; rmwStUop.isDbcc := False; rmwStUop.extByte := False; rmwStUop.bitOp := 0; rmwStUop.bfOp := 0; rmwStUop.bfDynamic := False; rmwStUop.bfMem := False; rmwStUop.bfStoreForm := 0; rmwStUop.bcdSub := False
    rmwStUop.firstOfInstr  := False    // the trailing store of a cracked RMW
    rmwStUop.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)

    // ── unimplemented gating (folded into opUop, last-wins) ────────────────────
    // Defer: non-simple, illegal op, a USED src EA that is neither reg/imm nor a
    // crackable memSimple, or a USED dst EA that is not a register AND not a
    // crackable MOVE store (mem-to-mem MOVE and RMW-to-mem stay unimplemented).
    // `bad` also disables cracking.
    val dstOk = dstEaOk || crackStore || crackMemMem
    // EOR (line B, register dest): the EA (op[5:0]) is the DESTINATION, read AND
    // written. This slice supports a DATA-REGISTER destination only; a memory EA is
    // the deferred RMW (load-op-store) form -> illegal. `eorMemBad` forces the illegal
    // path (it must NOT crack a leading load, which the generic srcEaOk would do).
    // ── ANDI/ORI/EORI #imm,CCR (the non-privileged to-CCR forms) ────────────────
    // Encoding 0000 ooo0 00 111100 + imm.B: line0, opmode ooo in {0=ORI,1=ANDI,5=EORI}
    // (bit8=0), size byte (ss=00), EA = mode7/reg4 (op[5:0]==0x3C). The op µop READS
    // the current CCR {X,N,Z,V,C} and WRITES it back: ccr5' = ccr5 op imm[4:0]. The
    // ALU EU does the read-modify-write. CCR ONLY — the privileged to-SR (word, ss=01)
    // forms are deferred (they stay illegal: ss=01 -> spec.op illegal at OperationDecoder).
    val isLineImm  = spec.srcB.kind === OperandKind.IMMEXT
    val isToCcr    = isLineImm && (op(5 downto 0) === B"6'b111100") && (spec.size === Size.BYTE) &&
                     (spec.op === DecOp.AND || spec.op === DecOp.OR || spec.op === DecOp.EOR)
    // ── ANDI/ORI/EORI #imm,SR (the PRIVILEGED to-SR forms) ───────────────────────
    // Same encoding shape as the non-privileged to-CCR form above (line0, opmode in
    // {0=ORI,1=ANDI,5=EORI}, EA=mode7/reg4 = op[5:0]==0x3C) disambiguated purely by
    // SIZE: CCR is the .B (ss=00) form, SR is the .W (ss=01) form -- OperationDecoder
    // makes no distinction (both ride the generic line-0-immediate AND/OR/EOR path
    // with EA=easrc/srcB=immext). Was previously left on the generic illegal path
    // (task risk: ANDI/ORI/EORI #imm,SR traps vector 4 instead of being a privileged
    // sysOp -- found via the cluster-6 exception/priv triage: EVERY test that used
    // `andi.w #0xDFFF,%sr` to drop to user mode faulted vector 4 on THAT instruction
    // itself, before even reaching the instruction under test). Reuses
    // SysKind.MOVE_TO_SR's ExceptionUnit S_APPLY case (writes srSys from result[15:8]
    // + surfaces result[4:0] as the new CCR) -- see AluEuPlugin's `isLogicSr`, which
    // recognizes sysKind===MOVE_TO_SR with op=/=MOVE as a "combine-with-old-SR" write
    // (old SR read from the committed srSysIn/CCR, not a register) instead of
    // MOVE_TO_SR's own plain direct write. srcA/srcB below don't carry the real
    // operand VALUE for this case -- only imm/useImm matter (already correctly
    // threaded by the generic IMMEXT srcB slot).
    val isToSr     = isLineImm && (op(5 downto 0) === B"6'b111100") && (spec.size === Size.WORD) &&
                     (spec.op === DecOp.AND || spec.op === DecOp.OR || spec.op === DecOp.EOR)
    // EOR (line B, register dest): the EA (op[5:0]) is the DESTINATION, read AND
    // written. This slice supports a DATA-REGISTER destination only; a memory EA is
    // the deferred RMW (load-op-store) form -> illegal. `eorMemBad` forces the illegal
    // path (it must NOT crack a leading load, which the generic srcEaOk would do). The
    // EORI #imm,CCR form (also spec.op==EOR, klass==IMM) is NOT gated here (-> !isToCcr).
    // A MEMSIMPLE EA is now a valid RMW destination (load-op-store crack); only An /
    // #imm / MEMCOMPLEX stay illegal -> reject "not DATAREG and not MEMSIMPLE".
    val eorMemBad = (spec.op === DecOp.EOR) && (srcEa.klass =/= EaClass.DATAREG) &&
                    (srcEa.klass =/= EaClass.MEMSIMPLE) && !isToCcr && !isToSr
    // ALU Dn,<ea> RMW (line 8/9/C/D opmode 4/5/6): the EA MUST be a memory-alterable mode
    // (MEMSIMPLE in scope). EA=Dn/An (the encoding overlaps no valid op) or MEMCOMPLEX ->
    // illegal. OperationDecoder named these (op != illegal); gate the bad EAs here.
    val line   = op(15 downto 12)
    val opmode = op(8 downto 6)
    val isAluRmwOp = (line === B"4'h8" || line === B"4'h9" || line === B"4'hC" || line === B"4'hD") &&
                     (opmode === 4 || opmode === 5 || opmode === 6)
    // EA mode 000 in the line-9/D RMW slot is ADDX/SUBX (a DATAREG operand, single ALU
    // µop), NOT a mem-RMW -> exclude it from the "RMW EA must be MEMSIMPLE" gate.
    val isAddxSubxReg = (line === B"4'h9" || line === B"4'hD") &&
                        (opmode === 4 || opmode === 5 || opmode === 6) && (op(5 downto 3) === 0)
    // ABCD (line C) / SBCD (line 8) register form: opmode 4, EA mode 000 (Dn-direct).
    // OperationDecoder already named it DecOp.BCD (a single register-direct ALU µop);
    // exclude it from the "RMW EA must be MEMSIMPLE" gate (its Dn-direct EA is intended).
    val isBcdReg = (spec.op === DecOp.BCD)
    // PACK/UNPK register forms (line 8, opmode 5/6, EA mode 000 Dn-direct): single-µop
    // register ops (Dy is srcB via mode-000 DATAREG). Excluded from the RMW-MEMSIMPLE gate.
    val aluRmwMemBad = isAluRmwOp && !isAddxSubxReg && !isBcdReg && !packUnpkReg && !spec.microcoded && (srcEa.klass =/= EaClass.MEMSIMPLE)
    // ADDQ/SUBQ (srcB = IMMQ3): the EA (op[5:0]) is the DESTINATION (read AND written).
    // This slice supports a DATA-register OR ADDRESS-register destination only; a memory
    // EA is the deferred RMW form -> illegal. `addqMemBad` forces the illegal path (it
    // must NOT crack a leading load, which the generic srcEaOk/crackLoad would do).
    val addqMemBad = (spec.srcB.kind === OperandKind.IMMQ3) &&
                     (srcEa.klass =/= EaClass.DATAREG) && (srcEa.klass =/= EaClass.ADDRREG) &&
                     (srcEa.klass =/= EaClass.MEMSIMPLE)
    // TST.W/TST.L An (68020+, task #170-cluster10): TST never writes back, so an
    // address-register-direct EA is architecturally legal (unlike CLR/NEG/NEGX/NOT,
    // which need an alterable dst and correctly reject An) -- for WORD/LONG only;
    // TST.B An stays illegal (matches real silicon, no byte-size An operand exists).
    // Mirrors the existing `addqAddrDst`/`addqMemBad` ADDRREG-whitelist precedent.
    val isTstAn = (spec.op === DecOp.TST) && (srcEa.klass === EaClass.ADDRREG) &&
                  (spec.size =/= Size.BYTE)
    // Line-4 unary (CLR/NEG/NEGX/NOT/TST/TAS/SWAP/EXT): DATA-register OR (CLR/NEG/NEGX/
    // NOT/TST/TAS) a MEMSIMPLE EA (the RMW crack). SWAP/EXT are Dn-only -> a non-data-reg
    // EA there stays illegal. An / #imm / MEMCOMPLEX always illegal (except TST.W/.L An).
    val line4UnaryMemBad = isLine4Unary && (srcEa.klass =/= EaClass.DATAREG) && !isTstAn &&
                           !(rmwOpInScope && (srcEa.klass === EaClass.MEMSIMPLE))
    // Line-0 immediate (srcB = IMMEXT): the EA (op[5:0]) is the DESTINATION. DATA-register
    // OR a MEMSIMPLE EA (the RMW crack); An / #imm / MEMCOMPLEX stay illegal. The to-CCR
    // form is the one exception.
    val lineImmBad = isLineImm && !isBitOp && (srcEa.klass =/= EaClass.DATAREG) &&
                     !srcEaIsMemSimpleEff && !isToCcr && !isToSr
    // ── .L-immediate + FULL-FORMAT dst EA: the `limmFullFmtDstBad` gate, REMOVED ──
    // Historical: a line-0 .L-immediate op with a FULL-FORMAT indexed dst EA (mode 6 or
    // 7-3, ext bit8=1) places the EA's first extension word at op+3 (after the 2-word .L
    // immediate). When this gate was written, PredecodeWord.classify's window only reached
    // op+1/op+2, so it framed the instruction as BRIEF (too short) -> nextPc short by the
    // bd/od words -> the FOLLOWING instruction mis-fetched. A forced vector-4 was the
    // honest resolution THEN, because predecode could not see op+3.
    //
    // That premise is gone. Task #153 threads a 3rd lookahead word (extW3 = op+3) into
    // classify(), and the line-0 mem-dest arm passes it to `memDestExt`, which frames the
    // full-format length EXACTLY (`fullExtLen`). When op+3 genuinely is not resident
    // (I-cache-line boundary), memDestExt's fallback frames brief AND raises
    // `ambiguousLine`, which Aligner.align turns into a STALL for slot 0 (re-resolved by
    // FetchAlignPlugin's `p0LiveReg` live re-classify over the 10-word head window) and a
    // pack-refusal for slot 1 (`slot1Ok` requires `!p1.ambiguousLine`). So a line-0
    // immediate can no longer reach decode with a guessed length: framing is exact by
    // construction, and the gate now only manufactures spurious vector-4 traps.
    //
    // Measured consequence of leaving it in (BUG_calibration_word_misplaced_0d00.md
    // Part 121/122): the Quadra 700 ROM's RAM-sizing routine, relocated to low RAM, runs
    //   0x000098E2: 0CB0 316D 6567 8170 000F EFFC = cmpi.l #$316D6567,%a0@(0xFEFFC)
    // (full-format, IS=1, BD-SIZE=long, I/IS=000 -> single-pass MEMSIMPLE). Real silicon
    // and MAME retire it (MAME: 1985 times in 20 emulated seconds); cpu040 raised vec=0x04
    // on its first execution, which cascaded into the ROM MicroBug monitor, the `$0DB0`
    // magic-test failure, a ROM restart, a vector table rebuilt on an odd stack, and an
    // odd VBR the machine could not recover from. This was the boot blocker.
    //
    // Nothing here weakens the illegal classification for the shapes the fast path really
    // cannot execute: a full-format MEMORY-INDIRECT dst (I/IS =/= 000) decodes to
    // EaClass.MEMINDIRECT, which `lineImmBad` (just above) still rejects whenever
    // DecodeStage has not routed it into the µcode engine; and a mode-7/reg-3 (PC-relative)
    // dst is not alterable, so `memDestExt` never frames it simple at all -> `!pkt.simple`.
    // Removing this term also removes a term from the global `bad` expression, which is a
    // strictly FMax-positive direction for that (deliberately closed) predicate.
    // Bit op (BTST/BCHG/BCLR/BSET, static or dynamic): the EA (op[5:0]) is the dest
    // (tested + written, except BTST). In scope: DATA-register (LONG, mod-32) OR a
    // MEMSIMPLE EA (BYTE, mod-8; BTST load-only, others mem-RMW crack). An / #imm /
    // MEMCOMPLEX (incl. predec/postinc/indexed) stay illegal -> defer. (Note: a static
    // bit-op has srcB=IMMEXT, so `isLineImm` is also true for it — `lineImmBad` excludes
    // bit-ops via `!isBitOp` so this gate is the single bit-op EA check, static+dynamic.)
    val bitOpMemBad = isBitOp && (srcEa.klass =/= EaClass.DATAREG) && !srcEaIsMemSimpleEff
    // PC-relative EA used as a DESTINATION (op[5:0] = the written EA, i.e. spec.dst.kind
    // == EASRC): ISA-illegal (PC-space is not alterable). The mem-dest gates above only
    // reject `=/= MEMSIMPLE`; a pcRel MEMSIMPLE (d16,PC)/(d8,PC,Xn) would otherwise slip
    // through (memDest is now false for pcRel, so no RMW crack fires -> would mis-crack a
    // leading load). Force the illegal path. Source pcRel (read) stays valid.
    // `&& spec.dstWrites` (task #169, ported-tests triage, btst_pcrel_src HANG): BTST
    // uniquely (PRM §4.16) accepts a PC-relative READ-ONLY target -- its `dst` field is
    // still set to the SAME `easrc` operand as BCHG/BCLR/BSET (OperationDecoder's shared
    // bit-op table always sets `o.dst := easrc`, for table uniformity), but `dstWrites`
    // is False for BTST specifically (tt==00), True for the other three. Without this
    // guard, this gate treated BTST identically to a REAL write and forced it illegal
    // even though nothing was ever going to be written -- the generic crackLoad path
    // (which already correctly folds pcRel into the load address, per ldUop's own
    // comment) never got a chance to fire.
    val eaDstPcRelBad = eaIsDst && srcIsMem && srcEa.pcRel && spec.dstWrites
    // ── Line-5 Scc / DBcc / TRAPcc (0101 cccc 11 mmmrrr) ────────────────────────
    // ss == 11 (op[7:6]). mode = op[5:3]. DBcc = mode 001 (+ disp16 word). Scc = any
    // other mode (a byte set on cond); in-scope = mode 000 (Dn). TRAPcc = mode 111 with
    // reg ∈ {2,3,4} (no-operand/word/long). cccc = op[11:8]. rrr = op[2:0].
    val isLine5    = (op(15 downto 12) === B"4'h5")
    val ss5        = op(7 downto 6)
    val mode5      = op(5 downto 3)
    val cccc5      = op(11 downto 8)
    val rrr5       = op(2 downto 0).asUInt.resize(5)
    val rrr5raw    = op(2 downto 0).asUInt
    val isDbccOp   = isLine5 && (ss5 === 3) && (mode5 === 1)
    // Scc <ea>: any mode except DBcc's mode=1, and (mode=7 valid only for reg 0/1 =
    // abs.W/abs.L; reg>=2 is TRAPcc's ttt operand-count selector, or a reserved mode-7
    // sub-form -- neither is a valid Scc destination). Task #160 widened this from
    // Dn-only (mode5===0) to the full memory-alterable EA set (mirroring CLR/NEG/NOT-
    // mem's "any MEMSIMPLE dest, non-pcRel" pattern); srcEa.klass (already EA-agnostic,
    // decoded from this SAME op[5:0] field regardless of instruction family) is what
    // the `when(isSccOp)` crack below actually branches on (DATAREG vs MEMSIMPLE).
    val isSccOp    = isLine5 && (ss5 === 3) && (mode5 =/= 1) &&
                     !((mode5 === 7) && (rrr5raw >= 2))
    // TRAPcc: line-5 ss==11, mode==7 (reg field is the ttt operand form), ttt ∈ {2,3,4}.
    //   ttt=4 (reg=4): no operand (1 word). ttt=2 (reg=2): #data16 (2 words).
    //   ttt=3 (reg=3): #data32 (3 words). Other ttt -> illegal (stays sccMemBad).
    val isTrapccOp = isLine5 && (ss5 === 3) && (mode5 === 7) &&
                     ((rrr5raw === 2) || (rrr5raw === 3) || (rrr5raw === 4))
    // A memory Scc / other mode-7 TRAPcc line-5 ss==11 form is deferred -> illegal.
    // Exclude TRAPcc (mode7,reg{2,3,4}) from the sccMemBad bucket.
    val sccMemBad  = isLine5 && (ss5 === 3) && (mode5 =/= 0) && (mode5 =/= 1) && !isTrapccOp
    // ── RTE (0x4E73) — a serializing return-from-exception µop (privileged). ────
    // Decoded here (line 0x4 is otherwise unimplemented) so it is NOT treated as an
    // illegal instruction. It commits like a no-op op µop but carries isRte; the
    // commit-side exception FSM acts on it at retire (pops the frame, redirects).
    val isRteOp = (op === B"16'h4E73")
    // ── TRAP #n (0x4E4n) — a decode-time UNCONDITIONAL software trap. ───────────
    // bits 15:4 == 0x4E4; n = op[3:0]. A faulted op µop (vector 32+n) delivering at
    // retire via the format-$0 FSM. TRAP is NOT restartable: it stacks the PC of the
    // NEXT instruction -> faultPc = nextPc. Decoded here (line 0x4 is otherwise
    // unimplemented) so it is NOT treated illegal.
    val isTrapOp = (op(15 downto 4) === B"12'h4E4")
    // ── TRAPV (0x4E76) — an EXECUTE-time CONDITIONAL trap (vector 7 if V). ──────
    // Decoded as a branch-class trap-check µop (isBranch so it issues to the branch
    // EU, readsNzvc so it reads V). The branch EU drives a trapvFault when V=1.
    val isTrapvOp = (op === B"16'h4E76")
    // DIVU.L/DIVS.L: opword 0100 1100 01 mmmrrr (op[15:6]==0x131). Decoded here (line 4
    // is otherwise illegal) from the extension word — NOT `bad`.
    val isDivLOp = (op(15 downto 6) === B"10'b0100110001")
    // MULU.L/MULS.L: opword 0100 1100 00 mmmrrr (op[15:6]==0x130). Decoded here (line 4
    // is otherwise illegal) from the extension word — NOT `bad`.
    val isMulLOp = (op(15 downto 6) === B"10'b0100110000")
    // ── JMP (0x4EC0 | ea) — a computed-target branch to the EA *address* (no push). ─
    // op[15:6] == 0100111011 (0x13B). Target = EA address: psrcA = base An (or none for
    // abs/PC), imm = displacement / folded absolute / folded PC. Control EA modes only:
    // (An), (d16,An), (xxx).W/.L, (d16,PC). Reg-direct / imm / (An)+ / -(An) / indexed
    // are illegal for JMP (-> `bad`). Decoded here (line 4 is otherwise illegal).
    val isJmpOp = (op(15 downto 6) === B"10'b0100111011")
    // JSR (0x4E80 | ea) — call: push retPC + ibranch to the EA address. Same control
    // EA modes as JMP. op[15:6] == 0100111010 (0x13A).
    val isJsrOp = (op(15 downto 6) === B"10'b0100111010")
    // A JMP/JSR control EA is the in-scope MEMSIMPLE set (the EaDecoder classifies
    // (An)/(d16,An)/(xxx)/(d16,PC) as MEMSIMPLE; predec/postinc are MEMCOMPLEX, reg-direct
    // DATAREG/ADDRREG, imm IMM). Indexed (d8,An,Xn)/(d8,PC,Xn) brief-format is MEMSIMPLE too
    // (task #187): the branch EU now has its own index-register read port (mirroring the
    // LS-EU AGU's), so a brief-indexed control EA is no longer rejected — `ibrUop` threads
    // srcC/indexLong/indexScale from `srcEa` below and the branch EU's target adder folds
    // in the scaled index. Full-format MEMORY-INDIRECT (EaClass.MEMINDIRECT, a genuinely
    // different EA class requiring a memory load of the pointer) is NOT in scope here —
    // `srcIsMem` already excludes it (only MEMSIMPLE) -- STALE COMMENT UPDATE (2026-09-01,
    // BUG_calibration_word_misplaced_0d00.md Part 70): task #201 (commit 8c1f0cd, merged
    // 2026-07-22) DID land that "future µcode-sequencer extension" — MI_LEA_ENTRY/MI_PEA_
    // ENTRY/MI_JMP_ENTRY/MI_JSR_ENTRY in Microcode.scala, routed via DecodeStage.scala's
    // s0IsLea/s0IsPea/s0IsJmp/s0IsJsr + s1mi_isLea/... + ucLeaMi/ucPeaMi/ucJmpMi/ucJsrMi
    // early-gate classifiers, ALL keyed off the raw opword bits (mirroring isLeaOp/isPeaOp/
    // isJmpOp/isJsrOp below) since this fast-path assembler carries no usable identity
    // signal for them either way. A MEMINDIRECT JMP/JSR/LEA/PEA is diverted to that engine
    // BEFORE `ctrlEaOk`/`jmpBad`/`jsrBad`/`leaPeaEaOk` below ever see it — this gate (and
    // this assembler's `bad` output generally) stays live only for the genuinely-illegal
    // shapes (An-direct/imm/predec/postinc source), exactly like the pre-existing MOVEM/
    // microcoded-op precedent. Directed regression: jsr_mem_indexed.s / jsr_preindexed_
    // memind_atrap_table.s / lea_memind_an.s / pea_memind.s / control_full_memind_
    // siblings.s (src/test/resources/m68kooo-ported-tests/asm/), all PASS as of this date,
    // including a byte-exact replica of real ROM `0x40809A04`
    // (`jsr @($400,D2.w*4)@(0)` / `4eb0 25a1 0400`). Do not re-derive "illegal/
    // unimplemented" from this comment/gate alone without also checking DecodeStage.scala's
    // early-routing gates — that is what Part 69 of the doc above did, and it produced a
    // false-positive "real cpu040 bug" finding for a case actually fixed 6 weeks earlier.
    val ctrlEaOk = srcIsMem
    // RTS (0x4E75) / RTR (0x4E77) are line-4 returns cracked below (NOT illegal).
    val isRtsBad = (op === B"16'h4E75")
    val isRtrBad = (op === B"16'h4E77")
    // LINK An,#disp16 (0100 1110 0101 0aaa) / UNLK An (0100 1110 0101 1aaa): line-4
    // stack-frame ops cracked below (NOT illegal). op[15:4]==0x4E5, op[3] selects.
    val isLinkOp = (op(15 downto 4) === B"12'h4E5") && !op(3)
    val isUnlkOp = (op(15 downto 4) === B"12'h4E5") &&  op(3)
    // LINK An,#disp32 (68020+, 0100 1000 0000 1 aaa, op[15:3]==0x901): the 32-bit-
    // displacement sibling of LINK.W above, distinct opcode region — cracked below with
    // the SAME machinery (linkPush/linkA7/linkAnU), just a wider displacement read from
    // words(1)##words(2) instead of a sign-extended words(1). Ported-tests triage
    // (link_long_unlk.s): previously entirely unhandled (fell through to `bad` ->
    // illegal vector 4, and with no vector-4 handler installed in the bare-metal test
    // harness the resulting fault cascade hung rather than trapped cleanly).
    val isLinkLOp = op(15 downto 3) === B(0x901, 13 bits)
    // EXG (line C, bit8=1, opmode in {01000,01001,10001}): a reg-reg swap cracked below
    // into 3 MOVE µops. Its opmode lands in the AND-RMW band (5/6) with a reg-direct EA,
    // which aluRmwMemBad would illegalise -> exclude from `bad` (mirror !isRtrBad).
    val isExgDD = (op(15 downto 12) === B"4'hC") && op(8) && (op(7 downto 3) === B"5'b01000") // EXG Dx,Dy
    val isExgAA = (op(15 downto 12) === B"4'hC") && op(8) && (op(7 downto 3) === B"5'b01001") // EXG Ax,Ay
    val isExgDA = (op(15 downto 12) === B"4'hC") && op(8) && (op(7 downto 3) === B"5'b10001") // EXG Dx,Ay
    val isExgOp = isExgDD || isExgAA || isExgDA
    // ── Track C: LEA / PEA / MOVE from-SR / from-CCR / to-CCR (line-4) ───────────
    // LEA (0100 An 1 11 mmmrrr): bit8=1, bits7:6=11, mode>=2. Control EA -> An (no flags).
    val isLeaOp = (op(15 downto 12) === B"4'h4") && op(8) && (op(7 downto 6) === B"11") &&
                  (op(5 downto 3).asUInt >= 2)
    // PEA (0100 1000 01 mmmrrr): op[15:6]==0x121, mode>=2 (CONTROL EA). Compute control
    // EA -> push to -(A7). Reg-direct (mode 000) is SWAP Dn (0x4840|rrr), which shares
    // op[15:6]==0x121 — require mode>=2 so SWAP keeps its own unary decode (and the
    // illegal reg-direct PEA is rejected), mirroring isLeaOp's mode>=2 guard.
    val isPeaOp = (op(15 downto 6) === B"10'b0100100001") && (op(5 downto 3).asUInt >= 2)
    // MOVE from SR (0x40C0) / from CCR (0x42C0): SR/CCR -> EA (.W). from-SR is PRIVILEGED.
    val isMoveFromSrOp  = (op(15 downto 6) === B"10'b0100000011")
    val isMoveFromCcrOp = (op(15 downto 6) === B"10'b0100001011")
    // MOVE to CCR (0x44C0): EA(.W low byte) -> CCR. NOT privileged.
    val isMoveToCcrOp   = (op(15 downto 6) === B"10'b0100010011")
    // LEA/PEA control-EA validity: in-scope MEMSIMPLE, NOT auto (-(An)/(An)+ illegal for
    // LEA/PEA), NOT indexed-with-no-AGU-support... (the LS-EU AGU DOES read the index, so
    // indexed IS allowed for LEA/PEA — unlike JMP/JSR's branch-EU AGU). PC-rel allowed.
    val leaPeaEaOk = (srcEa.klass === EaClass.MEMSIMPLE) && (srcEa.autoMode === EaAuto.NONE)
    val leaBad = isLeaOp && !leaPeaEaOk
    val peaBad = isPeaOp && !leaPeaEaOk
    // MOVE from-SR/CCR EA = op[5:0] is the WRITE DESTINATION: DATAREG (.W partial merge)
    // or a MEMSIMPLE non-pcRel EA (store crack). An-direct / #imm / pcRel / MEMCOMPLEX
    // (incl predec/postinc/indexed-full) are illegal.
    val mfDstIsDataReg = (srcEa.klass === EaClass.DATAREG)
    val mfDstIsMem     = (srcEa.klass === EaClass.MEMSIMPLE) && !srcEa.pcRel
    val moveFromSrBad  = isMoveFromSrOp  && !mfDstIsDataReg && !mfDstIsMem
    val moveFromCcrBad = isMoveFromCcrOp && !mfDstIsDataReg && !mfDstIsMem
    // MOVE-to-CCR source = op[5:0], a DATA-alterable read mode: DATAREG, #imm, or a
    // MEMSIMPLE source (the generic crackLoad prepends a load). An-direct (ADDRREG) is
    // NOT a valid MOVE-to-CCR source -> illegal.
    val mtcSrcOk    = (srcEa.klass === EaClass.DATAREG) || (srcEa.klass === EaClass.IMM) ||
                      (srcEa.klass === EaClass.MEMSIMPLE)
    val moveToCcrBad = isMoveToCcrOp && !mtcSrcOk
    // ── Track D: privileged commit-time SYSTEM ops (MOVE-to-SR / MOVE-USP / MOVEC) ─
    // Classified by OperationDecoder (spec.sysOp). NOT illegal; the op µop carries the
    // sysOp markers + reads its source register so the EU writeback VALUE is captured
    // for the commit FSM. The privilege check (S=0 -> vector 8) is at the serializing
    // retire, NOT decode.
    val isSysOp = spec.sysOp
    // RTD (0x4E74): a line-4 return cracked below (NOT illegal).
    val isRtdBad = (op === B"16'h4E74")
    // Merged illegal-detection exclusion list (Track C ops + Track D ops).
    val isCmp2Chk2Enc = !op(11) && !op(8) && (op(7 downto 6) === B"11") &&
                        (op(10 downto 9) =/= B"11") && (op(5 downto 3).asUInt >= 2) &&
                        (op(15 downto 12) === B"4'h0")     // line-0 CMP2/CHK2 (assembler-decoded)
    // Bit-field register form: the DYNAMIC offset/width forms (ext[11]=Do / ext[5]=Dw)
    // are now LEGAL (slice 2/3) — emitted as a 2-µop crack ([BFRESOLVE -> T0] [BITFIELD
    // bfDynamic]) below. There are no truly-illegal register-form Do/Dw combos. The
    // memory-operand forms (mode!=0) stay illegal via OperationDecoder (spec.illegal).
    // ── F-line FP-generic (cpGEN) extension-word decode ─────────────────────────
    // The opword names the FAMILY (OperationDecoder, Task 4); everything that matters
    // lives in words(1). Field positions confirmed against the in-tree vendored
    // tools/musashi/musashi/m68kfpu.c (fpgen_rm_reg: rm=(w2>>14)&1, src=(w2>>10)&7,
    // dst=(w2>>7)&7, opmode=w2&0x7f; m68040_fpu_op0's sub-switch on (w2>>13)&7).
    val fpExt      = pkt.words(1)
    val fpOpClass  = fpExt(15 downto 13)      // 000/010 arith, 011 FMOVE->ea, 100/101 ctrl, 110/111 FMOVEM
    val fpSrcSpec  = fpExt(12 downto 10)      // FPm (R/M=0) or the source data FORMAT (R/M=1)
    val fpDstFp    = fpExt(9 downto 7).asUInt // destination FPn
    val fpOpmode   = fpExt(6 downto 0)        // the operation (or, for FMOVECR, the ROM offset)
    val fpEaMode   = op(5 downto 3).asUInt
    val fpEaReg    = op(2 downto 0).asUInt

    // The hardware-native opmode whitelist (plan Global Constraints; every value
    // confirmed against m68kfpu.c's fpgen_rm_reg opmode switch). Anything else --
    // transcendentals, FMOD/FREM/FSCALE/FGETEXP, FSINCOS, and the 68040 rounded-precision
    // FSxxx/FDxxx variants (opmode bit 6 set) -- routes to FPSP via vector 11.
    val fpNative =
      (fpOpmode === B"7'h00") || (fpOpmode === B"7'h01") || (fpOpmode === B"7'h03") ||
      (fpOpmode === B"7'h04") || (fpOpmode === B"7'h18") || (fpOpmode === B"7'h1A") ||
      (fpOpmode === B"7'h20") || (fpOpmode === B"7'h22") || (fpOpmode === B"7'h23") ||
      (fpOpmode === B"7'h28") || (fpOpmode === B"7'h38") || (fpOpmode === B"7'h3A")
    // DYADIC ops compute `FPn <op> source`, so they READ the destination FPn as an
    // operand. The monadic ops (FMOVE/FABS/FNEG/FSQRT/FINT/FINTRZ/FTST) do not -- their
    // result is a function of the source alone, and claiming a false RAW dependency on
    // FPn would needlessly serialize independent FP work in the IQ.
    val fpDyadic =
      (fpOpmode === B"7'h20") || (fpOpmode === B"7'h22") || (fpOpmode === B"7'h23") ||
      (fpOpmode === B"7'h28") || (fpOpmode === B"7'h38")
    // FCMP (0x38) and FTST (0x3A) write ONLY the condition codes -- no FP destination.
    val fpNoFpDst = (fpOpmode === B"7'h38") || (fpOpmode === B"7'h3A")

    // Emittable forms (this task's scope -- see the plan's scope table). All of these are
    // single-uop and touch no memory:
    //   (a) opclass 000  : F<op> FPm,FPn
    //   (b) opclass 010 with an INT/single source specifier and <ea> = Dn (mode 0):
    //       F<op>.L/.W/.B/.S Dn,FPn -- a plain 32-bit int register read on the EXISTING
    //       int rename path (no 80-bit value ever enters IqContext, per the 2026-08-09
    //       design's gateway topology).
    //   (c) opclass 010 with source specifier 111 : FMOVECR #ccc,FPn (constant ROM).
    //   (d) opclass 010 with <ea> = mode7/reg4 (#imm), every non-Packed source format:
    //       F<op>.L/.W/.B/.S/.D/.X #imm,FPn -- see Step 4a/Step 5 below.
    // Real memory sources (<ea> >= mode 2) are DEFERRED to Task 6b -- they need a genuine
    // LS-EU load crack, and the X/D/P formats are 96/64/96 bits (multi-access), not a
    // single load or a decode-resident immediate. FMOVE-to-<ea> remains unowned by any
    // task in this plan. The FPCR/FPSR/FPIAR moves are Task 9's own encoding band.
    val fpFormIsReg    = (fpOpClass === B"3'b000")
    val fpFormIsMovecr = (fpOpClass === B"3'b010") && (fpSrcSpec === B"3'b111")
    val fpIntFmt       = (fpSrcSpec === B"3'b000") || (fpSrcSpec === B"3'b100") ||
                         (fpSrcSpec === B"3'b110") || (fpSrcSpec === B"3'b001")  // L / W / B / S
    val fpFormIsIntReg = (fpOpClass === B"3'b010") && fpIntFmt && (fpEaMode === U(0, 3 bits))

    // ── Immediate-source forms (this deliverable) ──────────────────────────────
    // `<ea>` = mode 7 / reg 4 is `#imm` for EVERY opclass, but only opclass 010 can pair
    // with it (a destination cannot be immediate, and opclass 000/registers-only forms
    // never consult the opword's <ea> field at all -- see Task 5's own note: "only a
    // SOURCE (opclass 010) can be immediate; an immediate destination is not encodable").
    val fpFormIsImm    = (fpOpClass === B"3'b010") &&
                         (fpEaMode === U(7, 3 bits)) && (fpEaReg === U(4, 3 bits))
    // Packed decimal (#imm, source spec 011) is explicitly OUT of hardware scope --
    // Decision 2 traps packed decimal to FPSP unconditionally, regardless of opmode. This
    // is a FORMAT exclusion, computed independently of `fpNative`'s opmode whitelist, so
    // "FADD.P #imm,FPn" (a native opmode paired with a non-native format) is excluded too.
    val fpImmIsPacked  = fpSrcSpec === B"3'b011"

    // ── Task 9: FMOVE.L <ea>,FPcr / FPcr,<ea>  (FPCR / FPSR / FPIAR) ────────────
    // opclass (ext[15:13]) 100 = <ea> -> control register(s), 101 = control register(s)
    // -> <ea>; ext[12:10] -- the SAME field `fpSrcSpec` names for the arithmetic forms --
    // is a one-hot register-select MASK {FPCR, FPSR, FPIAR}, MSB first. Encoding verified
    // by direct toolchain assembly (see SysKind.FMOVE_FPCTRL's comment for the full
    // table). This is a COMMIT-TIME SYSTEM op, not an FP-EU op: FPCR/FPSR/FPIAR are
    // non-renamed single-copy state (spec Decision 5), so ExceptionUnit's S_APPLY owns
    // the access, exactly like MOVEC.
    //
    // SCOPE (deliberate, mirrors MOVE-to-SR's own long-standing reg-only scope at the
    // `isSysOp` block below): exactly ONE mask bit set, and a register-direct <ea> only --
    // mode 000 (Dn) or mode 001 (An, architecturally legal for FPIAR only; we do not
    // police that, matching Musashi's permissiveness). Every other <ea> and every
    // multi-bit mask falls through UNCHANGED to the line-F illegal default -> vector 11.
    // That is the correct conservative behavior, not a gap being papered over: those
    // forms genuinely are not implemented yet.
    //
    // THE `#imm` FORM IS EXPLICITLY OUT OF SCOPE, and this is an evidence-based scope
    // NARROWING, not an oversight. `FMOVE.L #imm,FPSR` (F23C 8800 xxxxxxxx) needs BOTH a
    // 32-bit immediate value AND the 3-bit mask to reach commit, and `imm` can carry only
    // one of them (RobPlugin stores `u.imm(11 downto 0)` into `p.sysRc`, the only sysOp
    // side-channel that exists). The task brief proposed folding the mask into `casForm`
    // -- but `casForm` was checked and is NOT ROB-visible: it is threaded decode ->
    // `RenamedUop.casForm` -> `AluEuPlugin` only, and never enters `RobPayload`. Per the
    // brief's own instruction, the immediate form is therefore dropped here rather than
    // having a new ROB thread invented for it; it returns with Task 9b's FMOVEM-control
    // work, which needs a genuine multi-field side-channel regardless.
    val fpCtrlIsTo   = fpOpClass === B"3'b100"      // <ea> -> control register
    val fpCtrlIsFrom = fpOpClass === B"3'b101"      // control register -> <ea>
    val fpCtrlOneReg = (fpSrcSpec === B"3'b100") || (fpSrcSpec === B"3'b010") ||
                       (fpSrcSpec === B"3'b001")   // one-hot {FPCR, FPSR, FPIAR}
    val fpCtrlEaReg  = (fpEaMode === U(0, 3 bits)) || (fpEaMode === U(1, 3 bits))
    val fpCtrlEmit   = spec.fpGeneric && pkt.simple && (pkt.lenWords >= U(2)) &&
                       !spec.microcoded &&
                       (fpCtrlIsTo || fpCtrlIsFrom) && fpCtrlOneReg && fpCtrlEaReg

    // ── Task 14b: FMOVE FPn,<ea> (opclass 011) with a REGISTER-DIRECT destination ───
    // Modes 000 (Dn) and 001 (An) never reach the microcode engine at all
    // (OperationDecoder's `fpMemIsMemEa` excludes them), so -- exactly like Task 6's own
    // `fpFormIsIntReg` arm for the load direction -- this assembler emits them directly,
    // as a single `DecOp.FPSTORECVT` uop (chunk 0; every register-direct format is 1 word).
    //
    // WHICH formats have a register-direct arm at all is decided by Musashi's own writer
    // set, which matches the M68000PRM's <ea> restriction table here: `WRITE_EA_8/16/32`
    // have a mode-0 (Dn) arm, so Byte/Word/Long/Single are legal; `WRITE_EA_64` and
    // `WRITE_EA_FPE` do not (they `fatalerror`), so Double/Extended/Packed have none and
    // fall through UNCHANGED to the existing vector-11 F-line default. Only `WRITE_EA_32`
    // has a mode-1 (An) arm, so `An` is accepted for the Long format ONLY -- a deliberate
    // consistency choice with this project's own existing FP control-register precedent
    // (`fpCtrlEaReg` above already accepts modes 0 and 1 "matching Musashi's
    // permissiveness"), not a re-derivation from the manual.
    val fpStoreIsFrom  = fpOpClass === B"3'b011"
    val fpStoreRegFmt  = (fpSrcSpec === B"3'b000") || (fpSrcSpec === B"3'b001") ||
                         (fpSrcSpec === B"3'b100") || (fpSrcSpec === B"3'b110")  // L / S / W / B
    val fpStoreRegEmit = spec.fpGeneric && pkt.simple && (pkt.lenWords >= U(2)) &&
                         !spec.microcoded && fpStoreIsFrom &&
                         ((fpStoreRegFmt && (fpEaMode === U(0, 3 bits))) ||
                          ((fpSrcSpec === B"3'b000") && (fpEaMode === U(1, 3 bits))))
    // `.W`/`.B` into a data register is a PARTIAL-register write (the 68k data-register
    // rule); the EU merges over the old Dn, which it reads through srcA. `.L`/`.S` write
    // all 32 bits and need no source at all.
    val fpStoreRegMerge = (fpSrcSpec === B"3'b100") || (fpSrcSpec === B"3'b110")

    val fpEmit = spec.fpGeneric && pkt.simple && (pkt.lenWords >= U(2)) &&
                 (fpFormIsMovecr ||
                  ((fpFormIsReg || fpFormIsIntReg) && fpNative) ||
                  (fpFormIsImm && !fpImmIsPacked && fpNative))
    // Narrowed from Task 4's blanket `spec.fpGeneric`: everything cpGEN that this task
    // does NOT emit still takes the ordinary vector-11 F-line trap -- now with
    // faultUsesNextPc=True whenever predecode framed it (Step 2), which is what makes it
    // FPSP-completable instead of an infinite loop.
    // Task 6b: `&& !spec.microcoded` excludes the genuine memory-mode-<ea> cpGEN band --
    // OperationDecoder routes that whole band (opclass-agnostic) to the µcode ROM
    // (`spec.microcoded := True`), mirroring every other microcoded family's "kept
    // NON-illegal, the sequencer owns emission" contract (DecodeContracts.scala's
    // `microcoded` doc). Without this exclusion, `fpEmit` would correctly stay False for
    // a memory-mode source (none of fpFormIsReg/fpFormIsIntReg/fpFormIsMovecr/fpFormIsImm
    // ever match a memory <ea>), so `fpGenBad` would ALSO fire here and race the ROM
    // engine's own real accept/reject decision at `ucBegin` (which correctly rejects
    // Packed and every non-opclass-010 form back to a genuine vector-11 trap via
    // `FP_MEM_TRAP_ENTRY` -- the ROM, not this assembler-level gate, now owns that
    // decision for the whole memory-mode band).
    // Task 9: `&& !fpCtrlEmit` excludes the FMOVE-to/from-control-register band this
    // assembler now emits as a commit-time sysOp (it is cpGEN-shaped, so `spec.fpGeneric`
    // is set and `fpEmit` is False -- without this term it would take a spurious vector-11
    // trap instead of the sysOp it now is).
    // Task 14b: `&& !fpStoreRegEmit` for the same reason Task 9's `!fpCtrlEmit` term
    // exists -- the register-direct store forms are cpGEN-shaped (so `spec.fpGeneric` is
    // set and `fpEmit` is False), and without this term they would take a spurious
    // vector-11 trap instead of the uop they now are.
    val fpGenBad = spec.fpGeneric && !fpEmit && !fpCtrlEmit && !fpStoreRegEmit && !spec.microcoded

    // ── Immediate word extraction ────────────────────────────────────────────────
    // The immediate data ALWAYS starts at pkt.words(2) (right after opword + FP ext
    // word); only its WIDTH varies by format, matching Task 5's own fpImmWords table
    // exactly (Long/Single=2w, Word/Byte=1w, Double=4w, Extended=6w). Cross-checked here:
    // every width below consumes precisely the words Task 5 already frames for it, so
    // predecode's lenWords and this task's word indexing can never disagree.
    val fpImmLongVal   = pkt.words(2) ## pkt.words(3)                         // Long: 32 bits, no extension
    val fpImmWordVal   = pkt.words(2).asSInt.resize(32).asBits                // Word: sign-extend 16->32
    val fpImmByteVal   = pkt.words(2)(7 downto 0).asSInt.resize(32).asBits    // Byte: low byte, sign-extend 8->32
    val fpImmSingleVal = pkt.words(2) ## pkt.words(3)                         // Single: 32-bit BIT PATTERN verbatim
    val fpImmDoubleVal = pkt.words(2) ## pkt.words(3) ## pkt.words(4) ## pkt.words(5)  // Double: 64-bit BIT PATTERN
    // Extended: word2=sign+exp, word3=RESERVED (SKIPPED -- never read, matching Musashi's
    // load_extended_float80/READ_EA_FPE case 4 "immediate": d3=read_16(ea) [sign+exp],
    // d1=read_32(ea+4) [mantissa hi32], d2=read_32(ea+8) [mantissa lo32]; ea+2, the
    // reserved word, is never touched -- re-verified directly against
    // tools/musashi/musashi/m68kfpu.c:64-77,684-711 in this session), words4-7=64-bit
    // mantissa. This IS the internal Fp80 layout (Decision 1) -- zero conversion needed.
    val fpImmExtVal    = pkt.words(2) ## pkt.words(4) ## pkt.words(5) ## pkt.words(6) ## pkt.words(7)
    val bad = !isRteOp && !isTrapOp && !isTrapvOp && !isTrapccOp && !isDivLOp && !isMulLOp && !isJmpOp && !isJsrOp &&
              !isRtsBad && !isRtrBad && !isSccOp && !isDbccOp && !isLinkOp && !isLinkLOp && !isUnlkOp && !isExgOp &&
              !isLeaOp && !isPeaOp && !isMoveFromSrOp && !isMoveFromCcrOp && !isMoveToCcrOp &&
              !isSysOp && !isRtdBad && !isCmp2Chk2Enc && !isBfMemSpec &&
              (!pkt.simple || spec.illegal || eorMemBad || lineImmBad || addqMemBad || sccMemBad ||
               line4UnaryMemBad || aluRmwMemBad || bitOpMemBad || eaDstPcRelBad || fpGenBad ||
               (usesSrcEa && !srcEaOk) || (usesDstEa && !dstOk))
    // A JMP/JSR with a non-control EA is illegal (vector 4).
    val jmpBad = isJmpOp && !ctrlEaOk
    val jsrBad = isJsrOp && !ctrlEaOk
    when(isRteOp) {
      // a single architectural op µop carrying isRte; writes nothing, has a real PC.
      opUop.op            := DecOp.ILLEGAL  // no ALU action; the FSM handles it
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      // writesNzvc/writesX = False (task #176 REVERTED this to True, then task-176-
      // regression reverted it back to False — see ExceptionUnit.scala's
      // rteNzvcWriteValid doc comment for the full story). RTE restores the frame's
      // CCR into the REAL flags PRF via a DIRECT write into whatever physical
      // register nzvcRat/xRat's COMMITTED mapping currently names (mirrors
      // a7WriteValid/a7WriteData's already-safe pattern) — it does NOT need, and
      // must NOT take, a fresh rename allocation: a rename-allocated pNzvcDst/pXDst
      // sits "uncommitted" from the freelist's perspective for the FULL multi-cycle
      // R_DRAIN..R_REDIR FSM run (RenameStage's freelist-flush/RAT-rollback is fed by
      // `flushing`, which stays asserted via `excSquash` for that whole window), so a
      // wrong-path instruction renamed during that window can be handed the EXACT
      // SAME physical register RTE itself is mid-flight with — a confirmed silent-
      // corruption regression under back-to-back/nested exception storms
      // (exc_stack_atomicity_stress, pea_aline_irq_storm, via1_t1_irq_storm).
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      opUop.unimplemented := False
      opUop.faulted := False; opUop.faultVector := 0
      opUop.isRte   := True
      // RTE is PRIVILEGED (Musashi m68k_op_rte_32 checks FLAG_S and raises a privilege
      // violation BEFORE touching the stack when not supervisor). needsSupervisor routes
      // through the same commit-time privViolation gate as MOVE-from-SR (Track C):
      // RobPlugin's rteRetire excludes a privViolation head, so a user-mode RTE takes the
      // vector-8 fault path (faultRetire/privOnly) instead of the RTE pop/redirect FSM —
      // the frame is never popped and A7/S are left untouched.
      opUop.needsSupervisor := True
    }
    when(bad) {
      opUop.op            := DecOp.ILLEGAL
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := True
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      // Illegal instruction -> precise fault. Line-1010/Line-1111 ("Line-A"/"Line-F")
      // opcodes get their own dedicated vectors (10/11) per real 68040 hardware (Musashi
      // m68kcpu.h: opword top nibble 0xA/0xF traps unconditionally, distinct from the
      // generic vector-4 illegal-instruction path); everything else illegal is vector 4.
      // The op µop retires as the faulting head; the exception FSM stacks the frame +
      // vectors (both land in the generic short format-$0 frame, same as vector 4/8).
      opUop.faulted     := True
      opUop.faultVector := (op(15 downto 12).asUInt).mux(
        U(0xA, 4 bits) -> U(10, 8 bits),
        U(0xF, 4 bits) -> U(11, 8 bits),
        default        -> U(4, 8 bits)
      )
    }
    // ── Line-F trap PC flavor: pre-instruction vs post-instruction ───────────────
    // A vector-11 F-line trap comes in two flavors, and this project previously had only
    // one. The generic top-nibble fallback has an UNKNOWN instruction length, so it must
    // stack the FAULTING pc (restartable, faultUsesNextPc=False) -- the handler cannot
    // know how far to advance. But an F-line encoding whose FULL length predecode DID
    // frame is a different case: a real FPSP kernel emulates the instruction and RTEs,
    // and if the frame carries the faulting PC it re-executes the same opword forever.
    // Diagnosed in docs/superpowers/specs/2026-08-09-fpu-hardware-design.md section 5,
    // cross-checked against m68k-ooo's two vector-11 delivery paths (its packed-source
    // capture crack, which knows the length, raises an internal pseudo-vector that commit
    // translates back to architectural vector 11 while selecting the fall-through PC;
    // its plain top-nibble fallback keeps the faulting PC). The ARCHITECTURAL VECTOR IS
    // 11 IN BOTH CASES -- there is no second vector and no pseudo-vector exposed here,
    // only the PC-field selection the ROB already implements for TRAP/TRAPV/CHK/DIV0.
    //
    // The discriminator needs no new DecodePacket field, because Task 5 established the
    // invariant: a cpGEN instruction is NEVER genuinely one word (its extension word is
    // mandatory), so on a cpGEN opword lenWords===1 means "predecode declined to frame
    // it" and lenWords>=2 means "full length known". `pkt.simple` is required too: a
    // COMPLEX packet's lenWords is meaningless (0).
    val fpLenKnown = spec.fpGeneric && pkt.simple && (pkt.lenWords >= U(2))
    when(bad && fpLenKnown) {
      opUop.faultUsesNextPc := True
    }

    // ── F-line FP-generic uop assembly (DecOp.FPU, CPLX cluster) ────────────────
    when(fpEmit) {
      opUop.op       := DecOp.FPU
      opUop.cluster  := Cluster.CPLX      // spec Decision 9 -- shared CPLX cluster/IQ port/ROB port
      opUop.memOp    := MemOp.NONE
      opUop.unimplemented := False
      opUop.faulted  := False; opUop.faultVector := 0; opUop.faultUsesNextPc := False
      opUop.isBranch := False
      opUop.firstOfInstr := True          // a single uop: it IS the macro boundary
      opUop.fpuOp    := fpOpmode
      // The integer CCR is untouched by every FP op (FPCC is a separate rename class).
      opUop.readsNzvc := False; opUop.writesNzvc := False
      opUop.readsX    := False; opUop.writesX    := False
      // No INT destination: the 80-bit result goes to the FP PRF via Task 8's separate
      // writeback lane (the existing 32-bit CplxResult.data lane cannot carry it).
      opUop.dstValid := False
      // Destination FPn + FPCC. EVERY hardware-native FP op writes FPCC (Musashi calls
      // SET_CONDITION_CODES on every arm, including FMOVE-to-FPn and FMOVECR); FCMP and
      // FTST write ONLY FPCC. Nothing READS FPCC yet -- FBcc/FScc/FDBcc and
      // FMOVE-from-FPSR are deferred -- so readsFpcc stays False here; the rename class
      // and its IQ scoreboard (Task 3) exist so that lands as a pure addition.
      opUop.fpDstReg  := fpDstFp
      opUop.writesFp  := !fpNoFpDst
      opUop.writesFpcc := True
      opUop.readsFpcc  := False
      // srcA = the DESTINATION FPn read back, ONLY for the dyadic ops.
      opUop.fpSrcAReg := fpDstFp
      opUop.usesFpSrcA := fpDyadic
      // `fpSrcFmt` is meaningful whenever fpSrcKind indicates an opclass-010 form
      // (INTREG/INTIMM/SINGLEIMM/DOUBLEIMM/EXTIMM below); it is verbatim ext[12:10] --
      // for FPREG/ROMCONST it happens to be driven from whatever fpSrcSpec computes to
      // for THIS extension word's bit layout (harmless: fpSrcKind tells the EU never to
      // read it in those cases). Driven once here, outside the branch chain, so every
      // branch gets it for free instead of repeating it.
      opUop.fpSrcFmt := fpSrcSpec

      // Source routing.
      when(fpFormIsMovecr) {
        // FMOVECR: no register source at all; the constant's ROM offset rides `imm`.
        // useImm=True is safe here -- this uop has no integer srcB, and the IQ's
        // srcBIsReg() only consults useImm to decide whether to track psrcB, which is
        // invalid on this uop anyway.
        opUop.fpSrcKind := FpSrcKind.ROMCONST
        opUop.usesFpSrcB := False
        opUop.fpSrcBReg  := 0
        opUop.usesFpSrcA := False        // FMOVECR overwrites FPn; it never reads it
        // Override the `!fpNoFpDst` default above: for FMOVECR, `fpOpmode` is NOT an
        // opmode at all -- it's the ROM constant offset, and $38 (10^32, cromWords index
        // 14) / $3A (10^128, cromWords index 16) are real, defined offsets that happen to
        // numerically alias FCMP/FTST's opmodes. Every FMOVECR form writes its destination
        // FPn unconditionally; there is no FMOVECR variant that only sets FPCC.
        opUop.writesFp   := True
        opUop.srcAValid  := False; opUop.srcBValid := False
        opUop.useImm     := True
        opUop.imm        := fpOpmode.resize(32)
        opUop.fpWideImm  := B(0, 80 bits)
        opUop.size       := Size.LONG
      } .elsewhen(fpFormIsReg) {
        // F<op> FPm,FPn: the source is FP register FPm (ext[12:10]).
        opUop.fpSrcKind  := FpSrcKind.FPREG
        opUop.fpSrcBReg  := fpSrcSpec.asUInt
        opUop.usesFpSrcB := True
        opUop.srcAValid  := False; opUop.srcBValid := False
        opUop.useImm     := False
        opUop.fpWideImm  := B(0, 80 bits)
        opUop.size       := Size.LONG
      } .elsewhen(fpFormIsImm) {
        // F<op>.<fmt> #imm,FPn (THIS DELIVERABLE): no register source at all. The value
        // rides the NEW `fpWideImm` field (80 bits, carried through the IQ exactly like
        // `imm` already is -- IqContext embeds the WHOLE RenamedUop). `imm`/`useImm` stay
        // reserved for FMOVECR's ROM offset and are NOT reused here, so Task 8 has exactly
        // ONE dispatch: fpSrcKind selects the ROUTE (register / imm / fpWideImm),
        // fpSrcFmt selects the FORMAT within a fpWideImm-routed value.
        //
        // Gated identically to the register-form/INTREG cases: fpNative excludes every
        // transcendental/rounded-precision opmode regardless of source format (an
        // "FSIN.L #imm,FPn" still traps to FPSP, exactly like "FSIN FP1,FP0" already does);
        // Packed (fpImmIsPacked) is excluded independently of opmode by fpEmit's gate
        // above, so it is unreachable here.
        // (SpinalHDL's `.mux` type-inference doesn't resolve a Bits-key -> SpinalEnum-
        // value mapping cleanly, so this is a `when`/`elsewhen` chain instead of a mux
        // literal -- semantically identical to the brief's mux table.)
        opUop.fpSrcKind := FpSrcKind.INTIMM   // default: 000/100/110 = Long/Word/Byte;
                                               // 011/111 unreachable here (Packed excluded
                                               // by fpEmit's gate; FMOVECR claimed earlier)
        when(fpSrcSpec === B"3'b001") {
          opUop.fpSrcKind := FpSrcKind.SINGLEIMM  // Single
        } .elsewhen(fpSrcSpec === B"3'b010") {
          opUop.fpSrcKind := FpSrcKind.EXTIMM     // Extended
        } .elsewhen(fpSrcSpec === B"3'b101") {
          opUop.fpSrcKind := FpSrcKind.DOUBLEIMM  // Double
        }
        opUop.usesFpSrcB := False; opUop.fpSrcBReg := 0   // no FP register source
        opUop.srcAValid  := False; opUop.srcBValid := False   // no INT register source either
        opUop.useImm     := False    // `imm` is NOT used for these -- fpWideImm is, see above
        opUop.fpWideImm  := fpSrcSpec.mux(
          B"3'b000" -> (B(0, 48 bits) ## fpImmLongVal),
          B"3'b001" -> (B(0, 48 bits) ## fpImmSingleVal),
          B"3'b010" -> fpImmExtVal,
          B"3'b100" -> (B(0, 48 bits) ## fpImmWordVal),
          B"3'b101" -> (B(0, 16 bits) ## fpImmDoubleVal),
          B"3'b110" -> (B(0, 48 bits) ## fpImmByteVal),
          default   -> B(0, 80 bits)
        )
        opUop.size       := Size.LONG   // inert for these -- fpSrcFmt is the load-bearing width selector
      } .otherwise {
        // F<op>.L/.W/.B/.S Dn,FPn: a 32-bit INTEGER register read on the ORDINARY int
        // rename/scoreboard path (srcA/psrcA), converted to extended precision inside the
        // EU. This deliberately keeps every 80-bit value out of IqContext and the integer
        // operand mux, per the 2026-08-09 design's gateway topology.
        opUop.fpSrcKind  := FpSrcKind.INTREG
        opUop.usesFpSrcB := False
        opUop.fpSrcBReg  := 0
        opUop.srcAReg    := fpEaReg.resize(5)   // Dn (<ea> mode 0), i.e. arch reg 0..7
        opUop.srcAValid  := True
        opUop.srcBValid  := False
        opUop.useImm     := False
        opUop.fpWideImm  := B(0, 80 bits)
        // `size` distinguishes Word/Byte from the default 32-bit read (Long AND Single
        // both read a full 32-bit Dn -- Single's BIT-PATTERN-vs-INTEGER distinction is
        // now carried by `fpSrcFmt` above, not by `size`; this RESOLVES the open item this
        // task previously flagged for Task 8 -- see the updated note below).
        when(fpSrcSpec === B"3'b100") { opUop.size := Size.WORD }
          .elsewhen(fpSrcSpec === B"3'b110") { opUop.size := Size.BYTE }
          .otherwise { opUop.size := Size.LONG }
      }
    }

    // ── Task 14b: FMOVE FPn,Dn / FPn,An -- the register-direct STORE uop ────────────
    when(fpStoreRegEmit) {
      opUop.op            := DecOp.FPSTORECVT
      opUop.cluster       := Cluster.CPLX   // the only cluster with an FP-file read port
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := False
      opUop.faulted := False; opUop.faultVector := 0; opUop.faultUsesNextPc := False
      opUop.isBranch     := False
      opUop.firstOfInstr := True            // a single uop: it IS the macro boundary
      // The integer CCR is untouched by every FP op, and this one writes no FP state
      // either -- FPn is a pure SOURCE and the destination is an ordinary integer
      // register, so FPCC is NOT updated (Musashi's `fmove_reg_mem` calls neither
      // `SET_CONDITION_CODES` nor `float_raise`, confirmed by direct search).
      opUop.readsNzvc := False; opUop.writesNzvc := False
      opUop.readsX    := False; opUop.writesX    := False
      opUop.writesFp  := False; opUop.writesFpcc := False
      opUop.readsFpcc := False; opUop.fpDstReg   := 0
      // ext[9:7] is the SOURCE FPn (the role-flip of the load direction, where the same
      // field is the DESTINATION FPn -- see fpu_fmove_fpn_mem_nonzero_reg.s, the ported
      // regression that exists precisely because a sibling core read ext[12:10] here).
      opUop.fpSrcAReg  := fpDstFp
      opUop.usesFpSrcA := True
      opUop.usesFpSrcB := False; opUop.fpSrcBReg := 0
      opUop.fpSrcKind  := FpSrcKind.FPREG   // inert: this op has no FpuCore source gateway
      opUop.fpSrcFmt   := fpSrcSpec         // ext[12:10] = the DESTINATION format
      opUop.fpWideImm  := B(0, 80 bits)
      // The chunk index rides imm[1:0]; every register-direct format is a single chunk.
      opUop.useImm := True; opUop.imm := U(0, 32 bits).asBits
      // `.W`/`.B` read the old Dn back as the partial-write merge source.
      opUop.srcAReg   := fpEaReg.resize(5)
      opUop.srcAValid := fpStoreRegMerge
      opUop.srcBValid := False
      when(fpSrcSpec === B"3'b100") { opUop.size := Size.WORD }
        .elsewhen(fpSrcSpec === B"3'b110") { opUop.size := Size.BYTE }
        .otherwise { opUop.size := Size.LONG }
      // <ea> mode 000 = Dn (arch id 0..7), mode 001 = An (arch id 8..15, Long only).
      opUop.dstReg := Mux(fpEaMode === U(1, 3 bits),
                          (U(8, 5 bits) + fpEaReg.resize(5)).resize(5),
                          fpEaReg.resize(5))
      opUop.dstValid := True
    }

    // ── FSAVE unimplemented-instruction-frame trigger (Task 10, reduced scope) ──────
    // Reuses Task 6's own `fpFormIsReg`/`fpExt` locals -- NOT a re-derivation. Gated on
    // `bad` (this fires only on the trapping path) and on lenWords===2 exactly (not >=2):
    // the register-to-register form is ALWAYS exactly 2 words when its extension word was
    // actually resident at predecode time (Task 5's fpIsRegForm arm); if it was not
    // resident, predecode falls back to 1-word framing + ambiguousLine (Task 5's
    // `!extWKnown` arm), and this gate correctly declines rather than promising a frame
    // trigger built from words that were never really there.
    val fpuGenRegUnimpl = bad && spec.fpGeneric && fpFormIsReg && pkt.simple &&
                          (pkt.lenWords === U(2, pkt.lenWords.getWidth bits))
    when(fpuGenRegUnimpl) {
      opUop.fpuSoftwareComplete := True
      opUop.fpuCmdWord          := fpExt   // == pkt.words(1)
    }

    // ── FMOVE.L <ea>,FPcr / FPcr,<ea> : a COMMIT-TIME SYSTEM op (Task 9) ────────
    // Structurally identical to MOVEC's arm in the `isSysOp` block below -- same op
    // (MOVE), same cluster (INT), same operand routing, same `imm` side-channel -- but
    // driven from HERE rather than from `spec.sysKind`, because `OperationDecoder.decode`
    // sees the opword only and the direction/mask live in the extension word. That is an
    // established precedent, not a new mechanism: `isToSr` (ANDI/ORI/EORI #imm,SR, just
    // below) sets sysOp/sysKind exactly this way with `spec.sysOp` False.
    //
    // The register-select mask (ext[12:10]) rides `imm[2:0]` -- the SAME side-channel
    // MOVEC's 12-bit Rc id uses (RobPlugin stores `u.imm(11 downto 0)` into `p.sysRc`,
    // which ExceptionUnit latches as `sysCapRc`). `useImm` STAYS FALSE so the IQ treats
    // srcB as a REGISTER source and wakes the Rn dependency (`srcBIsReg` gates on
    // !useImm) -- the exact constraint MOVEC's own comment calls out.
    when(fpCtrlEmit) {
      opUop.op            := DecOp.MOVE     // result = the source value (write direction)
      opUop.cluster       := Cluster.INT    // NOT CPLX: no FP datapath is involved at all
      opUop.memOp         := MemOp.NONE
      opUop.size          := Size.LONG      // all three control registers are 32-bit
      opUop.unimplemented := False
      opUop.isBranch      := False
      opUop.faulted := False; opUop.faultVector := 0; opUop.faultUsesNextPc := False
      opUop.readsNzvc  := False; opUop.writesNzvc := False
      opUop.readsX     := False; opUop.writesX    := False
      // Explicitly NOT an FP-rename producer/consumer: the FPCR/FPSR/FPIAR halves are
      // non-renamed (owned by FpuControlPlugin), and the FPSR write's FPCC nibble goes
      // DIRECTLY into the FPCC PRF's committed physical register from ExceptionUnit
      // (rteNzvcWriteValid's already-proven-safe pattern) rather than through a rename
      // allocation. Keeping all four False is what makes this uop provably unable to
      // collide with RobPlugin's sysOp-read commit block, which force-clears
      // fpWrite/fpccWrite on `rc.commitPorts(0)`.
      opUop.writesFp := False; opUop.writesFpcc := False
      opUop.readsFpcc := False; opUop.fpDstReg := 0
      opUop.sysOp      := True
      opUop.sysKind    := SysKind.FMOVE_FPCTRL
      opUop.sysReadDir := fpCtrlIsFrom      // True = FPcr -> Rn
      opUop.firstOfInstr := True            // a single uop: it IS the macro boundary
      opUop.imm    := fpSrcSpec.resize(32)  // one-hot mask in imm[2:0] (NOT useImm)
      opUop.useImm := False
      opUop.srcAValid := False; opUop.srcBValid := False
      // <ea> mode 000 = Dn (arch id 0..7), mode 001 = An (arch id 8..15).
      val fpCtrlRnId = Mux(fpEaMode === U(1, 3 bits),
                           (U(8, 5 bits) + fpEaReg.resize(5)).resize(5),
                           fpEaReg.resize(5))
      when(fpCtrlIsFrom) {                  // FPcr -> Rn : a REAL renamed int dst
        // Same treatment as MOVEC's read direction: rename allocates a pdst, the ROB
        // commits the arch->pdst mapping at the serializing retire, and S_APPLY writes
        // the control-register VALUE into PRF[pdst] via sysRegWrite*.
        opUop.dstReg := fpCtrlRnId; opUop.dstValid := True
      } otherwise {                         // Rn -> FPcr
        // op is MOVE (result = srcB), so the ALU EU's writeback = Rn's value; the ROB
        // captures it (sysValStore) for the commit-time FpuControlPlugin write.
        opUop.srcBReg := fpCtrlRnId; opUop.srcBValid := True
        opUop.dstValid := False
      }
    }

    // ── ANDI/ORI/EORI #imm,CCR: a CCR read-modify-write op µop (ALU cluster) ─────
    // The base opUop already carries op = AND/OR/EOR + useImm/imm = the imm byte (via
    // the IMMEXT srcB). Override the operand/flag masks: NO int operands / dst; READS
    // NZVC + X (the current CCR) and WRITES NZVC + X (the result); toCcr tells the ALU
    // EU to assemble {X,N,Z,V,C}, apply the logical op against imm[4:0], and split the
    // result back into NZVC/X. (Last-wins after `bad`; isToCcr is never in `bad`.)
    when(isToCcr) {
      opUop.cluster   := Cluster.INT
      opUop.toCcr     := True
      opUop.srcAValid := False; opUop.srcBValid := False
      opUop.dstValid  := False
      opUop.readsNzvc := True;  opUop.readsX  := True
      opUop.writesNzvc := True; opUop.writesX := True
      opUop.unimplemented := False
    }
    // ── ANDI/ORI/EORI #imm,SR (PRIVILEGED): a commit-time system op ──────────────
    // (mirrors the true MOVE.W <ea>,SR sysOp wiring in the isSysOp block below, but
    // op stays AND/OR/EOR instead of being forced to MOVE, and there is no register/
    // memory EA source -- only the immediate). needsSupervisor is NOT used here (that
    // gate is for Track C ops); privilege is enforced the SAME way as every other
    // sysOp: RobPlugin's sysPrivFault (sysRetire && !committed-S), keyed off
    // opUop.sysOp/sysKind, independent of how op/toCcr/etc are set.
    when(isToSr) {
      opUop.cluster    := Cluster.INT
      opUop.sysOp      := True
      opUop.sysKind    := SysKind.MOVE_TO_SR
      opUop.sysReadDir := False
      opUop.firstOfInstr := True
      opUop.srcAValid  := False
      opUop.dstValid   := False
      opUop.readsNzvc  := False; opUop.readsX  := False
      opUop.writesNzvc := False; opUop.writesX := False
      opUop.unimplemented := False
    }
    // ── MOVE to CCR (0x44C0 | ea): CCR {X,N,Z,V,C} := src[4:0] (DIRECT, no fold). ──
    // NOT privileged. The op µop (ALU, toCcr write path) reads the EA source as srcB
    // (set EASRC in OperationDecoder -> reg/imm directly, or T0 from a cracked load) and
    // WRITES NZVC+X. op stays MOVE so the AluEu CCR mux does a direct assign (not AND/OR/
    // EOR). It does NOT read the old CCR (a full MOVE replaces it). srcB was already
    // routed by the srcB-slot switch (EASRC); keep it valid (the store data / direct src).
    when(isMoveToCcrOp) {
      opUop.op        := DecOp.MOVE
      opUop.cluster   := Cluster.INT
      opUop.toCcr     := True
      opUop.srcAValid := False
      opUop.dstValid  := False
      opUop.readsNzvc := False; opUop.readsX := False   // plain MOVE-to-CCR: no old-CCR read
      opUop.writesNzvc := True; opUop.writesX := True
      opUop.unimplemented := False
    }
    // ── MOVE from CCR (0x42C0 | ea): EA(.W) := zero-extend(CCR byte). NOT privileged. ──
    // The op µop produces the CCR byte as its INT result (fromCcr); reads NZVC+X. Reg dest
    // -> single ALU op (.W partial merge into Dn). Mem dest -> [fromCcr -> T1] + [store.w
    // T1 -> <ea>] (rmwStUop, the CLR-style trailing store). An-direct/pcRel/#imm illegal.
    when(isMoveFromCcrOp) {
      opUop.op         := DecOp.MOVE
      opUop.cluster    := Cluster.INT
      opUop.size       := Size.WORD
      opUop.fromCcr    := True
      opUop.readsNzvc  := True; opUop.readsX := True      // read CCR (toCcr read ports)
      opUop.writesNzvc := False; opUop.writesX := False
      opUop.srcBValid  := False; opUop.useImm := False
      opUop.unimplemented := False
      when(mfDstIsDataReg) {
        opUop.srcAReg := srcEa.reg; opUop.srcAValid := True   // .W merge upper-16 source = old Dn
        opUop.dstReg  := srcEa.reg; opUop.dstValid := True
      } otherwise {
        opUop.srcAValid := False
        opUop.dstReg := U(T1, 5 bits); opUop.dstValid := True // mem dest: store reads T1
        opUop.keepCommit := True                              // the kept commit (store dropped)
      }
    }
    // ── MOVE from SR (0x40C0 | ea): EA(.W) := zero-extend(16-bit SR). PRIVILEGED. ──────
    // SR = {srSysIn, CCR byte}; the AluEu builds it from srSysIn + NZVC+X (fromSr). Same
    // reg/mem dest routing as from-CCR. needsSupervisor -> the ROB delivers a vector-8
    // privilege violation at retire when the committed S bit is 0. firstOfInstr stays
    // True (no leading load) so the privilege check fires at the macro boundary.
    when(isMoveFromSrOp) {
      opUop.op         := DecOp.MOVE
      opUop.cluster    := Cluster.INT
      opUop.size       := Size.WORD
      opUop.fromSr     := True
      opUop.needsSupervisor := True
      opUop.readsNzvc  := True; opUop.readsX := True
      opUop.writesNzvc := False; opUop.writesX := False
      opUop.srcBValid  := False; opUop.useImm := False
      opUop.unimplemented := False
      when(mfDstIsDataReg) {
        opUop.srcAReg := srcEa.reg; opUop.srcAValid := True
        opUop.dstReg  := srcEa.reg; opUop.dstValid := True
      } otherwise {
        opUop.srcAValid := False
        opUop.dstReg := U(T1, 5 bits); opUop.dstValid := True
        opUop.keepCommit := True                              // the kept commit (store dropped)
      }
    }
    // Forced-illegal (vector 4) for a bad-EA MOVE-from-SR/CCR / MOVE-to-CCR (like jmpBad).
    when(moveFromSrBad || moveFromCcrBad || moveToCcrBad) {
      opUop.op            := DecOp.ILLEGAL
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := True
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.fromCcr := False; opUop.fromSr := False; opUop.toCcr := False; opUop.needsSupervisor := False; opUop.keepCommit := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.readsNzvc := False; opUop.readsX := False
      opUop.faulted := True; opUop.faultVector := 4; opUop.faultUsesNextPc := False
    }
    // ── Privileged commit-time SYSTEM ops: MOVE-to-SR / MOVE-USP / MOVEC ─────────
    // The op µop is the macro boundary (single µop; reg-source/reg-dest forms only —
    // a MEMORY-source MOVE-to-SR (load <ea> -> SR) is a fast-follow crack). It carries
    // the sysOp markers; the ROB retires it ALONE (serializing) and the ExceptionUnit
    // applies the effect at retire. WRITE direction: the source register is read so its
    // EU writeback VALUE is captured per-ROB-entry (-> the SystemState write). READ
    // direction: NO datapath dst — the FSM writes the int PRF arch-reg directly (the
    // dst arch reg rides dstReg, but dstValid=False so rename does NOT allocate a PRF
    // for it; the FSM uses the committed arch->phys mapping like the a7Write port). The
    // MOVEC Rc id (12-bit) + the A/D|reg# of the ext word are carried in `imm`.
    when(isSysOp) {
      opUop.op            := DecOp.MOVE     // result = the source value (for a WRITE)
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := False
      opUop.isBranch      := False
      opUop.writesNzvc := False; opUop.writesX := False
      opUop.readsNzvc  := False; opUop.readsX  := False
      opUop.faulted := False; opUop.faultVector := 0
      opUop.sysOp      := True
      opUop.sysKind    := spec.sysKind
      opUop.sysReadDir := spec.sysReadDir
      opUop.firstOfInstr := True
      // ── MOVEC ext word: bit15 = A/D (1=An), bits14:12 = reg#, bits11:0 = Rc. ──
      // The ext word is pkt.words(1). For a WRITE (Rn->Rc) the source Rn rides srcA;
      // for a READ (Rc->Rn) the dst Rn rides dstReg (dstValid=False). The Rc id rides
      // `imm[11:0]`. (MOVE-to-SR/MOVE-USP set their operands via the spec srcB/dst.)
      when(spec.sysKind === SysKind.MOVEC) {
        val ext   = pkt.words(1)
        val isAn  = ext(15)
        val regN  = ext(14 downto 12).asUInt
        val rnId  = Mux(isAn, (U(8, 5 bits) + regN.resize(5)).resize(5), regN.resize(5))
        val rc    = ext(11 downto 0)
        // Rc id rides imm[11:0], but useImm=FALSE: the EU ignores imm (a MOVEC write is
        // a MOVE whose result = srcB = Rn), while the ROB reads imm[11:0] for the Rc
        // directly. Keeping useImm=False is REQUIRED so the IQ treats srcB as a REGISTER
        // source (srcBIsReg gates on !useImm) -> the Rn dependency is woken correctly.
        opUop.imm := rc.resize(32)              // Rc id in imm[11:0] (NOT useImm)
        opUop.useImm := False
        opUop.srcAValid := False; opUop.srcBValid := False
        when(spec.sysReadDir) {                 // 0x4E7A Rc -> Rn: dst = Rn
          // The read dst is a REAL renamed register (dstValid=True): rename allocates a
          // pdst, the ROB commits the arch->pdst mapping at the serializing retire, and
          // the FSM writes the system VALUE into PRF[pdst]. A normal later reader of Rn
          // then sees the value (the committed mapping). (Unlike A7, an arbitrary Rn is
          // renamed, so the FSM must target pdst — the committed identity won't hold.)
          opUop.dstReg := rnId; opUop.dstValid := True
        } otherwise {                           // 0x4E7B Rn -> Rc: src = Rn -> srcB
          // The op is MOVE (result = srcB), so the ALU EU's wbObs.result = Rn's value;
          // the ROB captures it (sysValStore) for the commit-time SystemState write.
          opUop.srcBReg := rnId; opUop.srcBValid := True
          opUop.dstValid := False
        }
      }
      // MOVE-USP: the An is op[2:0] (NOT the op[11:9] the base anField uses). Override
      // the operand explicitly. READ (USP->An): dst = An (the FSM writes the PRF; no
      // datapath src). WRITE (An->USP): the source An -> srcB so the MOVE result = An
      // (captured into sysValStore for the SystemState write).
      when(spec.sysKind === SysKind.MOVE_USP) {
        val uspAn = (U(8, 5 bits) + op(2 downto 0).asUInt).resize(5)
        when(spec.sysReadDir) {                 // USP -> An (real renamed dst; FSM writes PRF[pdst])
          opUop.dstReg := uspAn; opUop.dstValid := True
          opUop.srcAValid := False; opUop.srcBValid := False
        } otherwise {                           // An -> USP
          opUop.srcBReg := uspAn; opUop.srcBValid := True
          opUop.srcAValid := False
          opUop.dstValid := False
        }
      }
      // RESET: no source, no dst, no value needed (the FSM is a no-op). Clear all operands
      // so nothing is read/written; the op-µop is purely the serializing macro boundary.
      when(spec.sysKind === SysKind.RESET) {
        opUop.srcAValid := False; opUop.srcBValid := False; opUop.dstValid := False
        opUop.useImm := False
      }
      // STOP: SR := imm16. The op-µop is a MOVE whose result = imm16 (zero-extended), so the
      // EU writeback VALUE (captured into sysValStore) carries the new SR to the FSM exactly
      // like MOVE-to-SR's register source. No int operands / dst.
      when(spec.sysKind === SysKind.STOP) {
        opUop.op := DecOp.MOVE
        opUop.srcAValid := False; opUop.srcBValid := False; opUop.dstValid := False
        opUop.useImm := True
        opUop.imm := pkt.words(1).asUInt.resize(32).asBits   // imm16 -> new SR (zero-ext)
      }
      // CPUSH/CINV: route An like PTEST does (srcB -> EU result -> sysValStore, so
      // S_APPLY has the address) + pack {scope,cacheSel} into imm[3:0] via the SAME
      // side-channel mechanism MOVEC's Rc id already uses (imm is read regardless of
      // useImm's IQ-operand-selection meaning). The actual cache-maintenance EFFECT is
      // still not implemented here (that's P5.4's DcachePlugin engine + P5.5's
      // ExceptionUnit dispatch) -- this arm only wires the operand ROUTING, which is
      // real, not a no-op placeholder: An's register id rides srcB (its VALUE arrives
      // via the normal EU writeback + sysValStore/sysCapVal capture, exactly like
      // MOVEC/PTEST), and scope/cache-selector ride the packed imm[3:0] nibble.
      // scope = opword[4:3], cacheSel = opword[7:6]; imm[3:2]=scope, imm[1:0]=cacheSel.
      when(spec.sysKind === SysKind.CPUSH || spec.sysKind === SysKind.CINV) {
        val maintAn = (U(8, 5 bits) + op(2 downto 0).asUInt).resize(5)
        opUop.srcBReg := maintAn; opUop.srcBValid := True
        opUop.srcAValid := False
        opUop.dstValid := False
        opUop.useImm := False   // srcB is a REAL register read (An), like MOVEC/PTEST
        opUop.imm := (op(4 downto 3) ## op(7 downto 6)).resize(32)
      }
      // PFLUSHA: no operands (opword-only), the flushAll pulse fires from S_APPLY
      // regardless of any register content.
      when(spec.sysKind === SysKind.PFLUSHA) {
        opUop.srcAValid := False; opUop.srcBValid := False; opUop.dstValid := False
        opUop.useImm := False
      }
      // PTEST: the An is op[2:0] (NOT the standard op[11:9] `anField`) — same override
      // precedent as MOVE_USP's uspAn. Always the "write" arm (An's value -> srcB, read
      // by the ALU EU as a plain MOVE result -> captured into sysValStore for the
      // S_APPLY commit-time MMUSR write); PTEST never writes a GPR directly.
      when(spec.sysKind === SysKind.PTEST) {
        val ptestAn = (U(8, 5 bits) + op(2 downto 0).asUInt).resize(5)
        opUop.srcBReg := ptestAn; opUop.srcBValid := True
        opUop.srcAValid := False
        opUop.dstValid := False
        opUop.useImm := False
      }
      // FSAVE / FRESTORE (Task 11): the An is op[2:0] (the standard <ea> reg field, NOT
      // the op[11:9] the base `anField` uses) -- same override precedent as MOVE_USP's
      // `uspAn` / PTEST's `ptestAn`. An's VALUE rides srcB so the ALU EU's plain MOVE
      // writeback lands it in `sysValStore` -> `sysCapVal`, giving S_APPLY the frame base
      // address.
      //
      // For the AUTO-UPDATE modes (FSAVE -(An), FRESTORE (An)+) the An must ALSO be
      // written back. dstReg=An with dstValid=True makes rename allocate a pdst that the
      // ROB commits at the serializing retire, and the FSM writes the updated value into
      // PRF[pdst] through the existing `sysRegWrite*` port -- byte-for-byte MOVEC's and
      // MOVE_USP's read-direction mechanism. The EU's own writeback into that pdst (which
      // for op=MOVE is just An's old value) is overwritten by the FSM at commit; that
      // EU-writes-then-FSM-overwrites shape is exactly what MOVE_USP's read arm already
      // relies on.
      //
      // imm[3:0] carries the EA mode (imm[3:1]) and an isRestore marker (imm[0]) through
      // the SAME imm -> `RobPlugin`'s `p.sysRc := u.imm(11 downto 0)` -> `sysCapRc`
      // side-channel that MOVEC's Rc id and CPUSH/CINV's {scope,cacheSel} nibble use.
      when(spec.sysKind === SysKind.FSAVE || spec.sysKind === SysKind.FRESTORE) {
        val fsvAn     = (U(8, 5 bits) + op(2 downto 0).asUInt).resize(5)
        val fsvIsRest = spec.sysKind === SysKind.FRESTORE
        // -(An) is mode 100 (FSAVE only); (An)+ is mode 011 (FRESTORE only). Plain (An)
        // (mode 010) writes nothing back. The decoder already restricted the admitted
        // modes per direction, so testing both here is safe and self-documenting.
        val fsvAuto   = (op(5 downto 3) === B"3'b100") || (op(5 downto 3) === B"3'b011")
        opUop.srcBReg   := fsvAn; opUop.srcBValid := True
        opUop.srcAValid := False
        opUop.useImm    := False   // srcB is a REAL register read (An), like MOVEC/PTEST
        opUop.imm       := (op(5 downto 3) ## fsvIsRest.asBits).resize(32)
        opUop.dstReg    := fsvAn
        opUop.dstValid  := fsvAuto
        opUop.isMovea   := True    // An destination: full-32 write, no partial merge
      }
    }
    when(isTrapOp) {
      // Unconditional faulted µop: vector 32+n, delivered at retire (format-$0).
      // The op carries no ALU action / operands and writes nothing.
      opUop.op            := DecOp.ILLEGAL   // no ALU action; the FSM delivers it
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      opUop.unimplemented := False
      opUop.isRte         := False
      opUop.faulted       := True
      opUop.faultVector   := (U(32, 8 bits) + op(3 downto 0).asUInt).resized
      // TRAP stacks the NEXT instruction's PC (= pc+2; nextPc), not its own.
      opUop.faultUsesNextPc := True
    }
    when(isTrapvOp) {
      // Branch-class cond-trap µop: issues to the branch EU, reads NZVC. The EU
      // evaluates cond=9 (VS, V-set) via `taken`; if taken drives a trapvFault (vector 7,
      // faultPc = nextPc). Not taken -> retires as a no-op. TRAPV is not restartable ->
      // faultUsesNextPc. The redirect is suppressed by the `isCondTrap` gate in the EU
      // regardless of `taken`, so completion/mispredict stay off the critical path.
      opUop.op            := DecOp.ILLEGAL    // no ALU action
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.writesNzvc := False; opUop.writesX := False
      opUop.unimplemented := False
      opUop.isRte         := False
      opUop.faulted       := False            // conditional: set at execute, not decode
      opUop.faultVector   := 0
      opUop.isBranch      := True             // route to the branch EU (NZVC read)
      // cond = 9 (VS): taken iff V=1, matching TRAPV semantics. The EU suppresses
      // redirect via the `isCondTrap` gate (no mispredict regardless of `taken`).
      opUop.cond          := 9
      opUop.branchDisp    := 0
      opUop.readsNzvc     := True
      opUop.isCondTrap    := True
      opUop.faultUsesNextPc := True
    }
    // ── TRAPcc (0101 cccc 11 111 ttt): conditional trap, vector 7, format-$2 ──────
    // ttt=4 (1 word), ttt=2 (+word), ttt=3 (+long). The operand words are handler-only
    // data; the CPU ignores them (affects only length, handled by predecode). The µop
    // is a branch-class cond-trap (isCondTrap, cond=cccc); the branch EU evaluates the
    // condition via `taken` and, if taken, drives a trapvFault (vector 7). Stacked PC
    // = nextPc (not restartable) -> faultUsesNextPc=True. No register/CCR change.
    when(isTrapccOp) {
      opUop.op            := DecOp.ILLEGAL    // no ALU action
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.writesNzvc := False; opUop.writesX := False
      opUop.unimplemented := False
      opUop.isRte         := False
      opUop.faulted       := False            // conditional: fault set at execute, not decode
      opUop.faultVector   := 0
      opUop.isBranch      := True             // route to the branch EU (NZVC read)
      opUop.cond          := cccc5            // the 4-bit condition code from op[11:8]
      opUop.branchDisp    := 0
      opUop.readsNzvc     := True
      opUop.isCondTrap    := True
      opUop.faultUsesNextPc := True
      // nextPc is the pc + length (1/2/3 words by ttt); predecode computes the correct
      // lenWords and the DecodePacket carries nextPc = pc + lenWords*2. No override needed.
    }
    // ── Scc Dn (0101 cccc 11 000rrr) — set Dn[7:0] := cond ? 0xFF : 0x00 ────────
    // A branch-class µop (routes to the branch EU's condition mux). It READS its dst Dn
    // (srcA = the merge upper-24 source) and WRITES Dn (dst = the byte-merged result);
    // it never redirects (the branch EU's Scc path forces taken/mispredict off and the
    // int write through anW). NO flags, NO ALU action. cond = cccc; the byte value is
    // computed in the EU from `taken`.
    // Scc <ea> memory destination (task #160): the EA (op[5:0] = srcEa, EA-agnostic and
    // already decoded regardless of instruction family) is MEMSIMPLE and non-pcRel (Scc
    // cannot target PC-space, and the broadened isSccOp already excludes every pcRel
    // encoding via its mode7-reg>=2 gate, so this is a defensive re-check, not load-
    // bearing). sccIsMem selects the 2-µop [compute cond -> T1][store T1 -> EA] crack
    // (below, at the final uops-array assembly) instead of the 1-µop Dn form.
    val sccIsMem = isSccOp && srcIsMem && !srcEa.pcRel
    when(isSccOp) {
      opUop.op            := DecOp.ILLEGAL    // no ALU action; the branch EU drives the write
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.isBranch      := True             // branch EU (condition mux)
      opUop.isScc         := True
      opUop.cond          := cccc5
      opUop.readsNzvc     := True             // read NZVC for the condition
      opUop.useImm        := False
      opUop.writesNzvc    := False; opUop.writesX := False
      opUop.branchDisp    := 0
      opUop.unimplemented := False
      opUop.faulted       := False; opUop.faultVector := 0
      opUop.isRte         := False; opUop.isCondTrap := False; opUop.ibranch := False
      when(sccIsMem) {
        // Memory dest: the branch EU write targets TEMP T1 instead of an architectural
        // Dn; a trailing plain STORE µop (rmwStUop -- the SAME generic memory-RMW
        // trailing-store helper MOVE-from-CCR/SR's mem-dest form already reuses) writes
        // T1's low BYTE to the computed EA, handling -(An)/(An)+ side effects and abs/
        // d16/indexed addressing via the proven srcEa/rmwStUop machinery, UNCHANGED.
        // NO leading load (the stored byte never depends on the OLD memory content,
        // unlike CLR/NEG/NOT-mem's RMW crack) -- mirrors crackClr's "no load precedes
        // it" shape. srcA is UNUSED (the branch EU's internal .B-merge upper-24 bits
        // would land in T1[31:8], discarded anyway since the trailing store is BYTE-
        // sized) -- drop the read entirely rather than wire a meaningless merge source.
        opUop.srcAValid := False
        opUop.srcBValid := False
        opUop.dstReg     := U(T1, 5 bits); opUop.dstValid := True
        opUop.keepCommit := True   // the kept commit; the trailing store is dropped
      } otherwise {
        opUop.srcAReg       := rrr5; opUop.srcAValid := True   // old Dn (merge upper-24 source)
        opUop.srcBValid     := False
        opUop.dstReg        := rrr5; opUop.dstValid := True    // Dn (byte-merged result)
      }
    }
    // ── DBcc Dn,disp (0101 cccc 11 001rrr + disp16) — decrement-and-branch ──────
    // A branch-class µop. READS Dn (srcA = the counter / merge source) + WRITES Dn (dst
    // = Mux(cond, Dn, {Dn[31:16], Dn[15:0]-1})); redirect = `!cond && (decW != -1)` to
    // pc+2+disp (the branch EU's relTarget). cond = cccc (DBRA/DBF = cccc=F=1). NO flags.
    when(isDbccOp) {
      opUop.op            := DecOp.ILLEGAL    // no ALU action; the branch EU drives it
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.isBranch      := True             // branch EU (condition mux + PC-rel target)
      opUop.isDbcc        := True
      opUop.cond          := cccc5
      opUop.readsNzvc     := True             // read NZVC for the condition
      opUop.srcAReg       := rrr5; opUop.srcAValid := True   // old Dn (counter / merge source)
      opUop.srcBValid     := False
      opUop.dstReg        := rrr5; opUop.dstValid := True    // Dn (decremented or unchanged)
      opUop.useImm        := False
      opUop.writesNzvc    := False; opUop.writesX := False
      // disp16 (the trailing extension word) — the PC-relative branch displacement.
      opUop.branchDisp    := pkt.words(1).asSInt.resize(32).asBits
      opUop.unimplemented := False
      opUop.faulted       := False; opUop.faultVector := 0
      opUop.isRte         := False; opUop.isCondTrap := False; opUop.ibranch := False
    }

    // ── INSTRUCTION-FETCH fault (the I-cache raised DecodePacket.fault) ─────────
    // Task #211: the I-cache raises `fault` for TWO distinct causes now — an ITLB
    // translation fault (non-resident / supervisor I-page) OR a physical AXI bus
    // error (SLVERR/DECERR) on a REFILL, e.g. a fetch to genuinely-unmapped space.
    // Either way the instruction bytes are don't-care: emit a single faulted op µop
    // that DELIVERS the format-$7 access fault (vector 2) at retire. The fetch PC
    // (the EA stacked in the $7 frame) is this µop's own `pc` -- ROB-fold Slice A
    // deleted the standalone `faultAddr` field that used to carry a redundant copy
    // of it here; RobPlugin's alloc-time `faultAddrStore` write now reads `.pc`
    // directly. sswInstr = 1 so the exception FSM stacks a program-space SSW.
    // `pkt.faultAtc` (carried from IcachePlugin's new
    // `atc` bit, task #211) selects the SSW ATC bit: True for the pre-existing
    // MMU/ATC-detected translation fault, False for the new bus-error cause —
    // mirroring how LsFault.atc already distinguishes the two causes on the D-side
    // (LsEuPlugin.captureFault). Last-wins over `bad`/RTE so a faulting fetch always
    // delivers.
    when(pkt.fault) {
      opUop.op            := DecOp.ILLEGAL   // no ALU action; the FSM delivers it
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := False
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      opUop.isRte         := False
      opUop.faulted       := True
      opUop.faultVector   := 2               // access fault -> format-$7
      opUop.sswInstr      := True            // instruction fetch (program-space SSW)
      opUop.faultAtc      := pkt.faultAtc    // True=ATC/MMU fault, False=bus error
    }

    // ── Shared MEM-source LOAD µop for DIV.L/MUL.L (task #180, ported-tests triage
    // cluster13/muldiv_indexed_mem_src) ──────────────────────────────────────────
    // Loads size=LONG from an arbitrary `ea: EaSpec` into T0 — this is what lets a
    // memSimple divisor/multiplier (previously force-illegal-trapped, "defer for
    // now") actually execute instead of dropping the whole instruction. Mirrors the
    // bit-field memory load-crack shape (`bfmLoadUop` below) but parameterized over
    // the EA so DIV.L and MUL.L share one helper. autoMode (An)+/-(An) is EXCLUDED
    // by the caller (divLOk/mulLOk below) -- an auto-update would need a 4th µop
    // (a dropped ADD, like crackMemMem's anUpdUop) and the .L64 forms are already at
    // the 3-µop budget ceiling (load+op+opHi) with zero room left, so auto-inc mem
    // divisors/multipliers stay illegal-trapped, unchanged from before. PC-relative
    // EAs fold pc+4 (not pc+2): the DIV.L/MUL.L selector ext word occupies pc+2, so
    // the EA's OWN ext word starts one word later than the "plain EA-first"
    // convention — identical shape to CMP2/CHK2/the bit-field memory crack below.
    def divMulLoadUop(ea: EaSpec): DecodedUop = {
      val u = DecodedUop()
      u.debugBreakValid := False; u.debugBreakSlot := 0
      u.fpInert()
      u.valid       := pkt.valid
      u.pc          := pkt.pc
      u.nextPc      := nextPc
      u.op          := DecOp.MOVE
      u.cluster     := Cluster.LS
      u.size        := Size.LONG
      u.memOp       := MemOp.LOAD
      u.srcAReg     := ea.base; u.srcAValid := ea.baseValid
      u.srcBReg     := 0;       u.srcBValid := False
      u.srcCReg     := ea.indexReg; u.srcCValid := ea.indexValid
      u.dstReg      := U(T0, 5 bits); u.dstValid := True
      u.useImm      := True
      u.imm         := Mux(ea.pcRel, (pkt.pc + U(4, 32 bits) + ea.disp.asUInt).asBits, ea.disp)
      u.readsNzvc   := False; u.readsX := False
      u.writesNzvc  := False; u.writesX := False
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
    u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
      u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
      u.divSigned   := False; u.div64 := False; u.divIsRem := False; u.isChk2 := False
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := ea.indexLong; u.indexScale := ea.indexScale
      u.leaAddr := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.predTaken := False; u.predTarget := U(0, 32 bits)
      u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
      u.firstOfInstr := True
      u.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)
      u
    }

    // ── DIVU.L / DIVS.L (32/32 and 64/32) — line-4 extension-word forms ─────────
    // Opword 0100 1100 01 mmmrrr (op[15:6]==0x131); the EA (op[5:0]) is the 32-bit
    // divisor. The EXTENSION WORD words(1) carries: Dq=ext[14:12] (quotient dst +
    // 32-bit dividend / 64-bit-dividend low), signed=ext[11], size64=ext[10] (1 =>
    // 64-bit dividend Dr:Dq), Dr=ext[2:0] (remainder dst + 64-bit-dividend high).
    //   - 32-bit form, Dr==Dq -> quotient ONLY (single DIV µop -> Dq).
    //   - 32-bit form, Dr!=Dq -> quotient -> Dq + remainder -> Dr (crack DIV+DIVREM).
    //   - 64-bit form          -> quotient -> Dq + remainder -> Dr (crack DIV+DIVREM).
    // The DIV µop reads Dq (psrcA) + the divisor EA (psrcB) [+ Dr via psrcC for 64b];
    // the trailing DIVREM µop writes the DivEu's latched remainder to Dr. Both are
    // CPLX (DivEu), single-outstanding -> issue in age order so the rem latch is valid.
    val ext = pkt.words(1)
    val divlDq     = ext(14 downto 12).asUInt.resize(5)
    val divlDr     = ext(2 downto 0).asUInt.resize(5)
    val divlSigned = ext(11)
    val divl64     = ext(10)
    // The DIV.L divisor is 32-bit -> re-decode the source EA at LONG size (so an
    // immediate divisor consumes 2 extension words / is the full 32-bit value). The
    // ext word is words(1); the EA's own extension words follow at words(2..).
    val divlSrcEa = EaDecoder.decode(op(5 downto 0), Size.LONG, Vec(pkt.words(0), pkt.words(2), pkt.words(3)))
    // Divisor EA reuses divlSrcEa (op[5:0]); reg/imm/memSimple.
    val divlDivisorIsImm = divlSrcEa.klass === EaClass.IMM
    val divlDivisorIsReg = (divlSrcEa.klass === EaClass.DATAREG) || (divlSrcEa.klass === EaClass.ADDRREG)
    val divlDivisorIsMem = divlSrcEa.klass === EaClass.MEMSIMPLE
    // remainder is produced (a 2nd dest) for the 64-bit form OR a 32-bit form whose
    // Dr field differs from Dq.
    val divlHasRem = divl64 || (divlDr =/= divlDq)

    // DIV (quotient) µop.
    val divlUop = DecodedUop()
    divlUop.debugBreakValid := False; divlUop.debugBreakSlot := 0
    divlUop.fpInert()
    divlUop.valid         := pkt.valid
    divlUop.pc            := pkt.pc
    divlUop.nextPc        := nextPc
    divlUop.op            := DecOp.DIV
    divlUop.cluster       := Cluster.CPLX
    divlUop.size          := Size.LONG
    divlUop.memOp         := MemOp.NONE
    divlUop.srcAReg       := divlDq; divlUop.srcAValid := True             // dividend (Dq / lo)
    // divisor: reg -> srcB; imm -> useImm; mem -> T0 (cracked load).
    when(divlDivisorIsImm) {
      divlUop.srcBReg := 0; divlUop.srcBValid := False
      divlUop.useImm  := True; divlUop.imm := divlSrcEa.imm
    } elsewhen(divlDivisorIsMem) {
      divlUop.srcBReg := U(T0, 5 bits); divlUop.srcBValid := True
      divlUop.useImm  := False; divlUop.imm := 0
    } otherwise {
      divlUop.srcBReg := divlSrcEa.reg; divlUop.srcBValid := True
      divlUop.useImm  := False; divlUop.imm := 0
    }
    divlUop.dstReg        := divlDq; divlUop.dstValid := True              // quotient -> Dq
    divlUop.readsNzvc     := True;  divlUop.readsX := False   // overflow preserves old N/Z/C (Musashi: only V set)
    divlUop.writesNzvc    := True;  divlUop.writesX := False               // DIV sets N/Z/V
    divlUop.isBranch      := False; divlUop.ibranch := False; divlUop.stkPush := False; divlUop.anInc := 0; divlUop.isReturn := False; divlUop.ccrRestore := False; divlUop.toCcr := False; divlUop.cond := 0; divlUop.branchDisp := 0
    divlUop.eaAuto        := EaAuto.NONE; divlUop.eaDelta := 0
    divlUop.unimplemented := False
    divlUop.faulted       := False; divlUop.faultVector := 0; divlUop.isRte := False
    divlUop.faultUsesNextPc := True            // DIV0 stacks nextPc (group-2 format-$2)
    divlUop.fpuSoftwareComplete := False; divlUop.fpuCmdWord := B(0, 16 bits)
    divlUop.sswInstr := False; divlUop.faultAtc := True; divlUop.isCondTrap := False
    divlUop.divSigned     := divlSigned; divlUop.div64 := divl64; divlUop.divIsRem := False
    divlUop.isChk2        := False
    divlUop.shiftOp := 0; divlUop.shiftDir := False; divlUop.isMovea := False; divlUop.isScc := False; divlUop.isDbcc := False; divlUop.extByte := False; divlUop.bitOp := 0; divlUop.bfOp := 0; divlUop.bfDynamic := False; divlUop.bfMem := False; divlUop.bfStoreForm := 0; divlUop.bcdSub := False
    divlUop.indexLong := False; divlUop.indexScale := 0
    divlUop.leaAddr := False; divlUop.movesAliasStore := False; divlUop.fromCcr := False; divlUop.fromSr := False; divlUop.needsSupervisor := False; divlUop.keepCommit := False
    divlUop.sysOp := False; divlUop.sysKind := SysKind.NONE; divlUop.sysReadDir := False
    divlUop.predTaken := False; divlUop.predTarget := U(0, 32 bits)
    divlUop.phtValid := False; divlUop.phtIndex := U(0, 11 bits); divlUop.casForm := 0
    // firstOfInstr: True unless a leading LOAD µop precedes it (memSimple divisor,
    // task #180 — the load becomes uops(0) and divlUop moves to uops(1)).
    divlUop.firstOfInstr  := !divlDivisorIsMem
    divlUop.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)
    // 64-bit dividend high word Dr: carried in srcC (psrcC after rename). For the
    // 32-bit form psrcC is unused.
    divlUop.srcCReg       := divlDr; divlUop.srcCValid := divl64

    // DIVREM (remainder-move) µop: CPLX, writes the DivEu's latched remainder to Dr.
    // Task #168 (ported-tests triage, divl_sz1_overflow HANG): srcA = Dr's OLD value
    // (a REAL register read, not the implicit-ordering-only placeholder this used to
    // be) so that on a DIV overflow (V=1, both Dq/Dr architecturally UNCHANGED per the
    // 68020+ PRM) DivEuPlugin can write Dr's old value back through instead of skipping
    // the write entirely -- mirroring the DIV µop's own s1A/Dq overflow fix (task #149).
    // Without a real srcA, "skip the write" left Dr's freshly-renamed pdst permanently
    // not-ready (writeInt=False -> the scoreboard/wakeup never fires), deadlocking any
    // later reader of Dr -- exactly task #149's own documented "KNOWN RESIDUAL GAP",
    // now hit for real by a 64/32 SZ=1 overflow test (divl_sz1_overflow.s) where Dr
    // (not just Dq) is preserved. The remainder itself (the non-overflow case) still
    // comes from the DivEu's internal latch, not this register read -- srcA exists
    // SOLELY to carry the old value through on the overflow path.
    val divremUop = DecodedUop()
    divremUop.debugBreakValid := False; divremUop.debugBreakSlot := 0
    divremUop.fpInert()
    divremUop.valid         := pkt.valid
    divremUop.pc            := pkt.pc
    divremUop.nextPc        := nextPc
    divremUop.op            := DecOp.DIVREM
    divremUop.cluster       := Cluster.CPLX
    divremUop.size          := Size.LONG
    divremUop.memOp         := MemOp.NONE
    divremUop.srcAReg       := divlDr; divremUop.srcAValid := True   // Dr's OLD value (overflow write-through)
    // srcB = Dq (divlUop's OWN destination), task #180 (ported-tests triage
    // cluster13/muldiv_indexed_mem_src): a REAL rename/scoreboard dependency on the
    // immediately-preceding DIV's destination, not just for its VALUE (unused by the
    // DIVREM compute below, which reads only s1A + the DivEu's internal remLatch) but
    // to CLOSE A LATENT ISSUE-ORDERING RACE. DIVREM's completion previously relied
    // SOLELY on "age-ordered single-outstanding CPLX issue" (an assumed invariant,
    // per the doc comment above) -- but IssueQueuePlugin's CPLX port picks the OLDEST
    // *READY* op each cycle, not a hard "older-blocks-younger" barrier. DIVREM has NO
    // real source operand of its own (srcA/Dr is unrelated to Dq whenever Dr!=Dq, the
    // common rem-producing case), so with reg/imm divisors DIV becomes ready fast
    // enough that DIVREM (pushed one cycle later) never actually got a chance to race
    // ahead -- but a memSimple divisor (task #180's new leading-LOAD crack) gives DIV
    // real latency, and DIVREM (deps-free) raced ahead and read `remLatch` before DIV
    // ever ran, confirmed via PORTED_TRACE_DIV (a stale/garbage writeback landed
    // BEFORE the DIV's own writeback in the trace). Reading Dq forces the rename
    // scoreboard to hold DIVREM not-ready until DIV's own completion writes it,
    // which is correct EVERY time regardless of the leading op's latency.
    // GATED to the memSimple-divisor case only: an earlier version of this fix made
    // the dependency unconditional and it REGRESSED 5 previously-PASSING reg/imm
    // ported tests (divl_basic, divl_sz0_dual_dest, divl_sz1_signed_neg,
    // divl_sz1_unsigned_basic, divide_test — all deadbeef/wrong-result, confirmed via
    // a stash-and-rerun bisection against the pre-fix baseline) — root cause not
    // fully chased down (something about the extra same-cycle intra-bundle rename
    // read tripping a scoreboard/free-list interaction for the reg/imm path, which
    // apparently relied on more than just "age-ordered issue" in a way this session
    // didn't have time to fully characterize), so the safe fix is to add the barrier
    // ONLY where it's actually needed (the new memSimple-divisor path, which has no
    // prior working behavior to regress).
    //
    // 2026-09-03 (Part 117) -- THE GAP THIS COMMENT LEFT OPEN WAS REAL, AND IT REACHED
    // HARDWARE. The reg/imm case it deliberately left unprotected is precisely the ROM's
    // `divsl.l %d2,%d6:%d5` in `_SlotManager $2C` (`SCalcsPointer`), and on a real board
    // the DIVREM did race ahead: 2 of 14 remainders wrong, two divides with IDENTICAL
    // operands returning different remainders, an sResource pointer moved by 4, and the
    // Slot Resource Table enumeration aborted -- a boot hang
    // (docs/BUG_calibration_word_misplaced_0d00.md Part 116/117). The stated assumption
    // ("with reg/imm divisors DIV becomes ready fast enough") fails whenever the
    // dividend's own producer is still in flight when the pair is dispatched.
    // The fix did NOT go here: this barrier is left exactly as it is (widening it is the
    // change known to regress those 5 tests, and the root cause of that regression is
    // still not understood). It went where the hazard actually lives -- DivEuPlugin's
    // remainder is now carried in a robId-KEYED stash with a per-robId valid bit cleared
    // on flush (the same structure the multiplier's high product already used), and
    // IssueQueuePlugin selects the divide family (DIV/DIVREM) IN AGE ORDER so a DIVREM
    // can never overtake its own DIV. This `srcBValid` dependency is therefore no longer
    // load-bearing for correctness in either case; it is retained only because removing
    // it is churn on a path with a known-fragile regression history.
    divremUop.srcBReg       := divlDq; divremUop.srcBValid := divlDivisorIsMem
    divremUop.srcCReg       := 0; divremUop.srcCValid := False
    divremUop.useImm        := False; divremUop.imm := 0
    divremUop.dstReg        := divlDr; divremUop.dstValid := True          // remainder -> Dr
    divremUop.readsNzvc     := False; divremUop.readsX := False
    divremUop.writesNzvc    := False; divremUop.writesX := False
    divremUop.isBranch      := False; divremUop.ibranch := False; divremUop.stkPush := False; divremUop.anInc := 0; divremUop.isReturn := False; divremUop.ccrRestore := False; divremUop.toCcr := False; divremUop.cond := 0; divremUop.branchDisp := 0
    divremUop.eaAuto        := EaAuto.NONE; divremUop.eaDelta := 0
    divremUop.unimplemented := False
    divremUop.faulted       := False; divremUop.faultVector := 0; divremUop.isRte := False
    divremUop.faultUsesNextPc := False
    divremUop.fpuSoftwareComplete := False; divremUop.fpuCmdWord := B(0, 16 bits)
    divremUop.sswInstr := False; divremUop.faultAtc := True; divremUop.isCondTrap := False
    divremUop.divSigned     := divlSigned; divremUop.div64 := divl64; divremUop.divIsRem := True
    divremUop.isChk2        := False
    divremUop.shiftOp := 0; divremUop.shiftDir := False; divremUop.isMovea := False; divremUop.isScc := False; divremUop.isDbcc := False; divremUop.extByte := False; divremUop.bitOp := 0; divremUop.bfOp := 0; divremUop.bfDynamic := False; divremUop.bfMem := False; divremUop.bfStoreForm := 0; divremUop.bcdSub := False
    divremUop.indexLong := False; divremUop.indexScale := 0
    divremUop.leaAddr := False; divremUop.movesAliasStore := False; divremUop.fromCcr := False; divremUop.fromSr := False; divremUop.needsSupervisor := False; divremUop.keepCommit := False
    divremUop.sysOp := False; divremUop.sysKind := SysKind.NONE; divremUop.sysReadDir := False
    divremUop.predTaken := False; divremUop.predTarget := U(0, 32 bits)
    divremUop.phtValid := False; divremUop.phtIndex := U(0, 11 bits); divremUop.casForm := 0
    divremUop.firstOfInstr  := False           // trailing crack µop
    divremUop.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)

    // DIV.L divisor EA: reg/imm (always OK), OR a memSimple (non-auto-update) EA —
    // task #180 (ported-tests triage cluster13/muldiv_indexed_mem_src): a leading
    // LOAD µop (divlLoadUop below) now supplies T0, so a memSimple divisor is no
    // longer force-illegal-trapped. (An)+/-(An) still isn't supported (would need a
    // 4th µop for the auto-update ADD — no room left at the .L64 3-µop ceiling) and
    // stays illegal, unchanged from before.
    val divlDivisorOkMem = divlDivisorIsMem && (divlSrcEa.autoMode === EaAuto.NONE)
    val divLOk = divlDivisorIsReg || divlDivisorIsImm || divlDivisorOkMem
    val divlLoadUop = divMulLoadUop(divlSrcEa)

    // An unsupported DIV.L (memSimple divisor) -> mark the op µop illegal (vector 4),
    // exactly like the `bad` path. opUop is already illegal for the 4C4x opword
    // (OperationDecoder's line-4 default), so we just force the faulted illegal fields.
    when(isDivLOp && !divLOk) {
      opUop.op            := DecOp.ILLEGAL
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := True
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      opUop.faulted := True; opUop.faultVector := 4; opUop.faultUsesNextPc := False
    }

    // ── MULU.L / MULS.L (32x32->32 and 32x32->64) — line-4 extension-word forms ──
    // Opword 0100 1100 00 mmmrrr (op[15:6]==0x130); EA (op[5:0]) is the 32-bit
    // multiplier. The EXTENSION WORD words(1) carries: Dl=ext[14:12] (the low-product
    // dst AND the multiplicand source), signed=ext[11] (MULS), size64=ext[10] (1 =>
    // 64-bit Dh:Dl result), Dh=ext[2:0] (the high-product dst, .L64 only).
    //   - 32x32->32 (bit10=0): product[31:0] -> Dl + V(overflow). single MUL µop.
    //   - 32x32->64 (bit10=1): product -> Dh:Dl. crack [MUL -> Dl] + [MULHI -> Dh].
    // MUL reads Dl (psrcA) + the multiplier EA (psrcB) — only 2 sources (no psrcC).
    val mullDl     = ext(14 downto 12).asUInt.resize(5)
    val mullDh     = ext(2 downto 0).asUInt.resize(5)
    val mullSigned = ext(11)
    val mull64     = ext(10)
    // The MUL.L multiplier is 32-bit -> re-decode the source EA at LONG size (reuses
    // the same EaDecoder call shape as DIV.L; the ext word is words(1), the EA's own
    // extension words follow at words(2..)).
    val mullSrcEa = EaDecoder.decode(op(5 downto 0), Size.LONG, Vec(pkt.words(0), pkt.words(2), pkt.words(3)))
    val mullMulIsImm = mullSrcEa.klass === EaClass.IMM
    val mullMulIsReg = (mullSrcEa.klass === EaClass.DATAREG) || (mullSrcEa.klass === EaClass.ADDRREG)
    val mullMulIsMem = mullSrcEa.klass === EaClass.MEMSIMPLE

    // MUL (low-product) µop. Writes Dl. Sets N/Z (+ V for the .L32 form).
    val mullUop = DecodedUop()
    mullUop.debugBreakValid := False; mullUop.debugBreakSlot := 0
    mullUop.fpInert()
    mullUop.valid         := pkt.valid
    mullUop.pc            := pkt.pc
    mullUop.nextPc        := nextPc
    mullUop.op            := DecOp.MUL
    mullUop.cluster       := Cluster.CPLX
    mullUop.size          := Size.LONG
    mullUop.memOp         := MemOp.NONE
    mullUop.srcAReg       := mullDl; mullUop.srcAValid := True              // multiplicand (Dl)
    when(mullMulIsImm) {
      mullUop.srcBReg := 0; mullUop.srcBValid := False
      mullUop.useImm  := True; mullUop.imm := mullSrcEa.imm
    } elsewhen(mullMulIsMem) {
      mullUop.srcBReg := U(T0, 5 bits); mullUop.srcBValid := True
      mullUop.useImm  := False; mullUop.imm := 0
    } otherwise {
      mullUop.srcBReg := mullSrcEa.reg; mullUop.srcBValid := True
      mullUop.useImm  := False; mullUop.imm := 0
    }
    mullUop.srcCReg       := 0; mullUop.srcCValid := False                  // 2 sources only
    mullUop.dstReg        := mullDl; mullUop.dstValid := True               // low product -> Dl
    mullUop.readsNzvc     := False; mullUop.readsX := False
    mullUop.writesNzvc    := True;  mullUop.writesX := False                // MUL sets N/Z (+V .L32)
    mullUop.isBranch      := False; mullUop.ibranch := False; mullUop.stkPush := False; mullUop.anInc := 0; mullUop.isReturn := False; mullUop.ccrRestore := False; mullUop.toCcr := False; mullUop.cond := 0; mullUop.branchDisp := 0
    mullUop.eaAuto        := EaAuto.NONE; mullUop.eaDelta := 0
    mullUop.unimplemented := False
    mullUop.faulted       := False; mullUop.faultVector := 0; mullUop.isRte := False
    mullUop.faultUsesNextPc := False
    mullUop.fpuSoftwareComplete := False; mullUop.fpuCmdWord := B(0, 16 bits)
    mullUop.sswInstr := False; mullUop.faultAtc := True; mullUop.isCondTrap := False
    mullUop.divSigned     := mullSigned; mullUop.div64 := mull64; mullUop.divIsRem := False
    mullUop.isChk2        := False
    mullUop.shiftOp := 0; mullUop.shiftDir := False; mullUop.isMovea := False; mullUop.isScc := False; mullUop.isDbcc := False; mullUop.extByte := False; mullUop.bitOp := 0; mullUop.bfOp := 0; mullUop.bfDynamic := False; mullUop.bfMem := False; mullUop.bfStoreForm := 0; mullUop.bcdSub := False
    mullUop.indexLong := False; mullUop.indexScale := 0
    mullUop.leaAddr := False; mullUop.movesAliasStore := False; mullUop.fromCcr := False; mullUop.fromSr := False; mullUop.needsSupervisor := False; mullUop.keepCommit := False
    mullUop.sysOp := False; mullUop.sysKind := SysKind.NONE; mullUop.sysReadDir := False
    mullUop.predTaken := False; mullUop.predTarget := U(0, 32 bits)
    mullUop.phtValid := False; mullUop.phtIndex := U(0, 11 bits); mullUop.casForm := 0
    // firstOfInstr: True unless a leading LOAD µop precedes it (memSimple multiplier,
    // task #180 — the load becomes uops(0) and mullUop moves to uops(1)).
    mullUop.firstOfInstr  := !mullMulIsMem
    mullUop.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)

    // MULHI (high-product move) µop (.L64 only): CPLX, writes the high product to Dh.
    // The value has no real source operand -- it comes from DivEuPlugin's high-product
    // stash. srcA reads Dl (mullUop's OWN destination) purely as a rename/scoreboard
    // ORDERING nudge, task #180 (ported-tests triage cluster13/muldiv_indexed_mem_src),
    // gated to the memSimple-multiplier case (`srcAValid := mullMulIsMem`) because an
    // unconditional version regressed reg/imm behaviour and the root cause was never
    // chased down (something about the extra same-cycle intra-bundle rename read
    // tripping a scoreboard/free-list interaction on that path).
    //
    // HISTORICAL NOTE, corrected 2026-09-03 (Part 117). This comment used to describe
    // the barrier as load-bearing against a `mulHiLatch` race. There is no `mulHiLatch`
    // and there has not been for some time: DivEuPlugin carries the high product in a
    // robId-KEYED `Mem` with a per-robId valid bit cleared on flush (`mulHiMem` /
    // `mulHiValid`), and MULHI is parked in `mulHiPendingQ` until ITS OWN robId's bit is
    // set. An early-issued MULHI therefore WAITS; it cannot read another multiply's
    // product, with or without this barrier. The divide side had no such structure --
    // it used a single anonymous global `remLatch` -- which is exactly why the same
    // race was real there and produced a hardware boot failure
    // (docs/BUG_calibration_word_misplaced_0d00.md Part 116/117). DivEuPlugin's
    // remainder now uses the multiplier's structure too. The barrier below is retained
    // as-is (it is harmless and this is not the place to re-open the reg/imm
    // regression), but it is NOT what makes MULHI correct.
    // Writes Dh; sets no flags (the MUL set N/Z; V=0).
    val mulhiUop = DecodedUop()
    mulhiUop.debugBreakValid := False; mulhiUop.debugBreakSlot := 0
    mulhiUop.fpInert()
    mulhiUop.valid         := pkt.valid
    mulhiUop.pc            := pkt.pc
    mulhiUop.nextPc        := nextPc
    mulhiUop.op            := DecOp.MULHI
    mulhiUop.cluster       := Cluster.CPLX
    mulhiUop.size          := Size.LONG
    mulhiUop.memOp         := MemOp.NONE
    mulhiUop.srcAReg       := mullDl; mulhiUop.srcAValid := mullMulIsMem
    mulhiUop.srcBReg       := 0; mulhiUop.srcBValid := False
    mulhiUop.srcCReg       := 0; mulhiUop.srcCValid := False
    mulhiUop.useImm        := False; mulhiUop.imm := 0
    mulhiUop.dstReg        := mullDh; mulhiUop.dstValid := True             // high product -> Dh
    mulhiUop.readsNzvc     := False; mulhiUop.readsX := False
    mulhiUop.writesNzvc    := False; mulhiUop.writesX := False
    mulhiUop.isBranch      := False; mulhiUop.ibranch := False; mulhiUop.stkPush := False; mulhiUop.anInc := 0; mulhiUop.isReturn := False; mulhiUop.ccrRestore := False; mulhiUop.toCcr := False; mulhiUop.cond := 0; mulhiUop.branchDisp := 0
    mulhiUop.eaAuto        := EaAuto.NONE; mulhiUop.eaDelta := 0
    mulhiUop.unimplemented := False
    mulhiUop.faulted       := False; mulhiUop.faultVector := 0; mulhiUop.isRte := False
    mulhiUop.faultUsesNextPc := False
    mulhiUop.fpuSoftwareComplete := False; mulhiUop.fpuCmdWord := B(0, 16 bits)
    mulhiUop.sswInstr := False; mulhiUop.faultAtc := True; mulhiUop.isCondTrap := False
    mulhiUop.divSigned     := mullSigned; mulhiUop.div64 := mull64; mulhiUop.divIsRem := False
    mulhiUop.isChk2        := False
    mulhiUop.shiftOp := 0; mulhiUop.shiftDir := False; mulhiUop.isMovea := False; mulhiUop.isScc := False; mulhiUop.isDbcc := False; mulhiUop.extByte := False; mulhiUop.bitOp := 0; mulhiUop.bfOp := 0; mulhiUop.bfDynamic := False; mulhiUop.bfMem := False; mulhiUop.bfStoreForm := 0; mulhiUop.bcdSub := False
    mulhiUop.indexLong := False; mulhiUop.indexScale := 0
    mulhiUop.leaAddr := False; mulhiUop.movesAliasStore := False; mulhiUop.fromCcr := False; mulhiUop.fromSr := False; mulhiUop.needsSupervisor := False; mulhiUop.keepCommit := False
    mulhiUop.sysOp := False; mulhiUop.sysKind := SysKind.NONE; mulhiUop.sysReadDir := False
    mulhiUop.predTaken := False; mulhiUop.predTarget := U(0, 32 bits)
    mulhiUop.phtValid := False; mulhiUop.phtIndex := U(0, 11 bits); mulhiUop.casForm := 0
    mulhiUop.firstOfInstr  := False           // trailing crack µop
    mulhiUop.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)

    // MUL.L multiplier EA: reg/imm (always OK), OR a memSimple (non-auto-update) EA —
    // task #180 (ported-tests triage cluster13/muldiv_indexed_mem_src): a leading
    // LOAD µop (mullLoadUop below) now supplies T0, so a memSimple multiplier is no
    // longer force-illegal-trapped. (An)+/-(An) still isn't supported (would need a
    // 4th µop for the auto-update ADD — no room left at the .L64 3-µop ceiling) and
    // stays illegal, unchanged from before.
    val mullMulOkMem = mullMulIsMem && (mullSrcEa.autoMode === EaAuto.NONE)
    val mulLOk = mullMulIsReg || mullMulIsImm || mullMulOkMem
    val mullLoadUop = divMulLoadUop(mullSrcEa)
    when(isMulLOp && !mulLOk) {
      opUop.op            := DecOp.ILLEGAL
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := True
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      opUop.faulted := True; opUop.faultVector := 4; opUop.faultUsesNextPc := False
    }

    // ── Bit-field MEMORY load-only crack (BFTST/BFEXTU/BFEXTS/BFFFO <ea>, static) ──
    // slice 3a. The bf-ext word (words(1)) carries Do=bit11, offset=bits[10:6], Dw=bit5,
    // width=bits[4:0] (0->32), Dn2=bits[14:12]. The EA's OWN ext words FOLLOW it (at
    // words(2..)), so re-decode the EA from a SHIFTED vector (same shape as CMP2/CHK2).
    //   byteAddr = EA + (offset>>3)        (offset>>3 in 0..3, FOLDED into the disp)
    //   bitOff   = offset & 7;  needHi = (bitOff + width) > 32  (STATIC)
    //   µop0 LOAD.L @[byteAddr]   -> T0  (the misaligned LONG `lo`; LS handles cross-line)
    //   µop1 LOAD.B @[byteAddr+4] -> T1  (the spill byte `hi`; ONLY when needHi)
    //   µopN BITFIELD bfMem  srcA=T0 srcB=T1(needHi) -> Dn2 (EXTU/EXTS/FFO) / none (TST)
    // The funnel + datapath run on the ALU EU (the slow BITFIELD pipe). DYNAMIC offset/
    // width on mem forms is deferred (the OperationDecoder mem arm gates Do/Dw out by only
    // naming the op; here bfMem ignores Do/Dw — slice 3a is STATIC only).
    val bfmEaDec = EaDecoder.decode(op(5 downto 0), Size.LONG, Vec(pkt.words(0), pkt.words(2), pkt.words(3)))
    // CONTROL modes only (MEMSIMPLE, no auto-update). (An)+/-(An) (autoMode != NONE) and
    // reg-direct/#imm are rejected -> illegal (vector 4).
    // Task #199 (bf_pcrel_idx_traps_alive): an INDEXED PC-relative EA — brief-format
    // (d8,PC,Xn) OR full-format (bd.W,PC,Xn.W) no-memind — is klass=MEMSIMPLE/pcRel=True/
    // indexValid=True, same as a plain (d16,PC) EA except for the index. This 3a static
    // crack's `bfmPcRelAddr` folds pc+4+disp into a pure absolute (base-less) displacement
    // at DECODE time; the index register is applied SEPARATELY by the LS-EU AGU at
    // EXECUTE time (same generic index-add path as any other indexed load) — so this
    // shape is not actually known-broken, but it is DELIBERATELY still out of scope (the
    // vendored test's own contract is fail-SAFE-not-fail-CORRECT: "architecturally legal
    // read EAs the decoder does not implement yet" — see that test's header comment,
    // which explicitly anticipates a FUTURE widening to a real value check once someone
    // does that verification work). Reject it here (same fail-safe illegal trap as
    // memory-indirect, autoMode, etc.) rather than let it silently execute.
    val bfmEaOk  = (bfmEaDec.klass === EaClass.MEMSIMPLE) && (bfmEaDec.autoMode === EaAuto.NONE) &&
                   !(bfmEaDec.pcRel && bfmEaDec.indexValid)
    val bfmOffset5 = bfExt(10 downto 6).asUInt              // static offset 0..31
    val bfmWidthRaw= bfExt(4 downto 0).asUInt               // raw width (0->32)
    val bfmWidth   = (((bfmWidthRaw - 1) & U(31, 5 bits)) + 1)   // 1..32
    val bfmDn2     = bfExt(14 downto 12).asUInt.resize(5)
    val bfmBfOp    = op(10 downto 8)
    val bfmByteOff = bfmOffset5 >> 3                        // 0..3 (FOLDED into disp)
    val bfmBitOff  = (bfmOffset5 & U(7, 5 bits)).resize(3)  // 0..7
    val bfmNeedHi  = (bfmBitOff.resize(6) + bfmWidth.resize(6)) > U(32, 6 bits)
    // The bit-field has 2 leading words (opword + bf-ext); the EA ext word lives at pc+4,
    // so (d16,PC)/(d8,PC,Xn) PC-relative accesses use pc+4 as the base (= addr of the EA
    // ext word), NOT pc+2 (which is the bf-ext word) — mirrors CMP2/CHK2.
    val bfmPcRelAddr = (pkt.pc + U(4, 32 bits) + bfmEaDec.disp.asUInt).asBits
    // byteAddr disp = EA disp + (offset>>3). For pcRel, fold pc+4 into the absolute first.
    val bfmDispLo = Mux(bfmEaDec.pcRel,
                        (bfmPcRelAddr.asUInt + bfmByteOff).asBits,
                        (bfmEaDec.disp.asUInt + bfmByteOff).asBits)
    val bfmDispHi = (bfmDispLo.asUInt + U(4, 32 bits)).asBits   // byteAddr+4 (the spill byte)
    // imm packing for the BITFIELD bfMem compute µop (same low layout as the static
    // register form: imm[4:0]=rotate offset(=0), imm[9:5]=rawWidth; PLUS the mem extras):
    //   imm[12:10]=bitOff, imm[13]=needHi, imm[18:14]=origOffset(0..31).
    val bfmImm = (B(0, 13 bits) ## bfmOffset5.asBits.resize(5) ## bfmNeedHi ## bfmBitOff.asBits.resize(3) ##
                  bfmWidthRaw.asBits.resize(5) ## B(0, 5 bits)).resize(32)
    def bfmLoadUop(disp: Bits, dst: Int, size: Size.C, first: Bool): DecodedUop = {
      val u = DecodedUop()
      u.debugBreakValid := False; u.debugBreakSlot := 0
      u.fpInert()
      u.valid       := pkt.valid
      u.pc          := pkt.pc
      u.nextPc      := nextPc
      u.op          := DecOp.MOVE
      u.cluster     := Cluster.LS
      u.size        := size
      u.memOp       := MemOp.LOAD
      u.srcAReg     := bfmEaDec.base; u.srcAValid := bfmEaDec.baseValid
      u.srcBReg     := 0;          u.srcBValid := False
      u.srcCReg     := bfmEaDec.indexReg; u.srcCValid := bfmEaDec.indexValid   // index (AGU)
      u.dstReg      := U(dst, 5 bits); u.dstValid := True
      u.useImm      := True; u.imm := disp
      u.readsNzvc   := False; u.readsX := False
      u.writesNzvc  := False; u.writesX := False
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
      u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
      u.divSigned   := False; u.div64 := False; u.divIsRem := False; u.isChk2 := False
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := bfmEaDec.indexLong; u.indexScale := bfmEaDec.indexScale
      u.leaAddr     := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp       := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.predTaken := False; u.predTarget := U(0, 32 bits)
      u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
      u.firstOfInstr := first
      u.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)
      u
    }
    val bfmLoadLo = bfmLoadUop(bfmDispLo, T0, Size.LONG, first = True)   // misaligned LONG `lo`
    val bfmLoadHi = bfmLoadUop(bfmDispHi, T1, Size.BYTE, first = False)  // spill byte `hi`
    // The BITFIELD bfMem compute µop (the macro architectural commit): srcA=T0, srcB=T1
    // (valid iff needHi), dst=Dn2 for EXTU/EXTS/FFO, none for BFTST.
    val bfmIsExtFfo = (bfmBfOp === 1) || (bfmBfOp === 3) || (bfmBfOp === 5)
    val bfmIsTst    = (bfmBfOp === 0)
    val bfmCompute = {
      val u = DecodedUop()
      u.debugBreakValid := False; u.debugBreakSlot := 0
      u.fpInert()
      u.valid       := pkt.valid
      u.pc          := pkt.pc
      u.nextPc      := nextPc
      u.op          := DecOp.BITFIELD
      u.cluster     := Cluster.INT
      u.size        := Size.LONG
      u.memOp       := MemOp.NONE
      u.srcAReg     := U(T0, 5 bits); u.srcAValid := True             // lo (LS-produced)
      u.srcBReg     := U(T1, 5 bits); u.srcBValid := bfmNeedHi        // hi (LS-produced, iff needHi)
      u.srcCReg     := 0;             u.srcCValid := False
      when(bfmIsExtFfo) { u.dstReg := bfmDn2; u.dstValid := True }
        .otherwise      { u.dstReg := 0;      u.dstValid := False }   // BFTST: no write
      u.useImm      := True; u.imm := bfmImm
      u.readsNzvc   := False; u.readsX := False
      u.writesNzvc  := True;  u.writesX := False                       // NZ only (V=C=0, X untouched)
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
      u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
      u.divSigned   := False; u.div64 := False; u.divIsRem := False; u.isChk2 := False
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0
      u.bfOp        := bfmBfOp; u.bfDynamic := False; u.bfMem := True; u.bfStoreForm := 0; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := False; u.indexScale := 0
      u.leaAddr     := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp       := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.predTaken := False; u.predTarget := U(0, 32 bits)
      u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
      u.firstOfInstr := False
      u.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)
      u
    }
    // A bit-field memory op with a non-control EA -> illegal (vector 4). DYNAMIC read-only mem
    // (Do||Dw, slice 3c) that REACHES the 3a crack is gated illegal here: the supported dynamic
    // read-only ops (BFTST/BFEXTU/BFEXTS) are routed to the µcode engine (slot0IsBfDynMem) and
    // their 3a output is DISCARDED, so this is harmless for them; the out-of-scope !baseValid (abs)
    // dynamic read-only forms are NOT routed and trap here rather than mis-cracking as static.
    // (BFFFO An-base dynamic-mem IS routed — the FFOFULL redesign.) (The static read-only mem forms keep the 3a crack.)
    val bfmBad = isBfMemSpec && (!bfmEaOk || bfDo || bfDw)
    when(bfmBad) {
      opUop.op            := DecOp.ILLEGAL
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := True
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.srcCValid := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      opUop.readsNzvc := False; opUop.readsX := False
      opUop.faulted := True; opUop.faultVector := 4; opUop.faultUsesNextPc := False
    }

    // ── CMP2 / CHK2 (0000 0ss0 11 mmm rrr) + ext word — the 2-load+compare crack ──
    // The EA (op[5:0] = srcEa, a CONTROL mode) points to the LOWER bound; the UPPER
    // bound is at EA+size. Crack:
    //   µop0 LOAD.size @[EA]      -> T0 (lower)   (first; reuses ldUop's address path)
    //   µop1 LOAD.size @[EA+size] -> T1 (upper)   (SAME base/index, disp += size)
    //   µop2 CMP2CHK2  srcA=T0 srcB=T1 srcC=Rn -> CCR {oldN,Z,oldV,C}; CHK2 EuFault{vec6}
    // The extension word (words(1)) carries: A/D=bit15, Rn=bits[14:12], R/M=bit11
    // (1=CHK2). Rn = D0-7 (A/D=0) or A0-7 (A/D=1). The compare's psrcC = Rn rides a
    // normal reg (statically tracked); T0/T1 are LS-produced (the IQ lsWait covers
    // BOTH srcA + srcB — the MOVEM multi-LS-source wakeup, class-agnostic, already
    // tracks psrcA/psrcB/psrcC). isChk2 + divSigned(reused as adReg=A/D) carry the
    // sub-kind/sign-ext rule; size from op[10:9].
    val isCmp2Chk2Op = isCmp2Chk2Enc
    val c2ssReal = op(10 downto 9)
    val c2Size   = Size()
    when(c2ssReal === 0) { c2Size := Size.BYTE }
      .elsewhen(c2ssReal === 1) { c2Size := Size.WORD }
      .otherwise { c2Size := Size.LONG }
    val c2SizeBytes = c2ssReal.mux(
      B"00" -> U(1, 32 bits), B"01" -> U(2, 32 bits), default -> U(4, 32 bits))
    val c2Ext   = pkt.words(1)
    val c2Ad    = c2Ext(15)                                  // A/D: 1 = address reg
    val c2Rn    = Mux(c2Ad, (U(8, 5 bits) + c2Ext(14 downto 12).asUInt).resize(5),
                            c2Ext(14 downto 12).asUInt.resize(5))
    val c2IsChk2= c2Ext(11)                                  // R/M: 1 = CHK2
    // The CMP2/CHK2 extension word is words(1); the EA's OWN extension words FOLLOW it
    // (at words(2..)). Re-decode the EA from a SHIFTED words vector so its disp/abs come
    // from the right offset (the same shape as the DIV.L/MUL.L re-decode). The bounds EA
    // is a CONTROL mode -> EaDecoder classifies (An)/(d16,An)/(xxx)/(d16,PC) (+ indexed)
    // as MEMSIMPLE; reg-direct/imm/(An)+/-(An) are NOT control. pcRel folds pc.
    val c2SrcEa = EaDecoder.decode(op(5 downto 0), c2Size, Vec(pkt.words(0), pkt.words(2), pkt.words(3)))
    // mode 6 ((d8,An,Xn), brief or full-format) and mode 7/reg 3 ((d8,PC,Xn), brief or
    // full-format) are architecturally LEGAL CMP2/CHK2 control EAs (§4.39), but this
    // 2-load+compare crack only forms a straight base+disp address (it never reads an
    // index register, walks a full-format bd/od chain, or follows memory-indirection) --
    // EaDecoder still classifies the brief-indexed and full-format-no-memind shapes as
    // MEMSIMPLE (they're supported for the GENERIC ALU/MOVE crackLoad's AGU), which used
    // to let CMP2/CHK2 silently admit them with a WRONG (index-less-if-full/garbage)
    // address. Force them illegal here as a fail-safe until the crack actually implements
    // index/full-format EA compute (chk2_cmp2_illegal_ea_traps.s's fail-safe-follow-up
    // set). Memory-indirect (klass MEMINDIRECT) is already excluded by the klass check
    // below; this adds the indexed/full-format MEMSIMPLE shapes on top of it.
    val c2Mode = op(5 downto 3)
    val c2Reg  = op(2 downto 0)
    val c2IndexedShape = (c2Mode === B"110") || ((c2Mode === B"111") && (c2Reg === B"011"))
    val c2EaOk  = (c2SrcEa.klass === EaClass.MEMSIMPLE) && (c2SrcEa.autoMode === EaAuto.NONE) &&
                  !c2IndexedShape
    // CMP2/CHK2 has 2 ext words: [opword][cmp2_ext][ea_ext]. The EA ext word lives at
    // pc+4, so (d16,PC)/(d8,PC,Xn) PC-relative accesses must use pc+4 as the base
    // (= address of the EA extension word), NOT pc+2 (which would be the cmp2_ext word).
    val c2PcRelAddr = (pkt.pc + U(4, 32 bits) + c2SrcEa.disp.asUInt).asBits
    // Load1 address = base An + disp (+ index); load2 = SAME + size.
    val c2Disp1 = Mux(c2SrcEa.pcRel, c2PcRelAddr, c2SrcEa.disp)
    val c2Disp2 = Mux(c2SrcEa.pcRel, (c2PcRelAddr.asSInt + c2SizeBytes.asSInt).asBits,
                                     (c2SrcEa.disp.asSInt + c2SizeBytes.asSInt).asBits)
    // Common load builder (every field once): addr = base + disp + index, -> dst.
    def c2LoadUop(disp: Bits, dst: Int, first: Bool): DecodedUop = {
      val u = DecodedUop()
      u.debugBreakValid := False; u.debugBreakSlot := 0
      u.fpInert()
      u.valid       := pkt.valid
      u.pc          := pkt.pc
      u.nextPc      := nextPc
      u.op          := DecOp.MOVE
      u.cluster     := Cluster.LS
      u.size        := c2Size
      u.memOp       := MemOp.LOAD
      u.srcAReg     := c2SrcEa.base; u.srcAValid := c2SrcEa.baseValid
      u.srcBReg     := 0;          u.srcBValid := False
      u.srcCReg     := c2SrcEa.indexReg; u.srcCValid := c2SrcEa.indexValid   // index (AGU)
      u.dstReg      := U(dst, 5 bits); u.dstValid := True
      u.useImm      := True; u.imm := disp
      u.readsNzvc   := False; u.readsX := False
      u.writesNzvc  := False; u.writesX := False
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
      u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
      u.divSigned   := False; u.div64 := False; u.divIsRem := False; u.isChk2 := False
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := c2SrcEa.indexLong; u.indexScale := c2SrcEa.indexScale
      u.leaAddr     := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp       := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.predTaken := False; u.predTarget := U(0, 32 bits)
      u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
      u.firstOfInstr := first
      u.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)
      u
    }
    val c2Load0 = c2LoadUop(c2Disp1, T0, first = True)       // lower @ [EA]
    val c2Load1 = c2LoadUop(c2Disp2, T1, first = False)      // upper @ [EA+size]
    // Compare µop (CPLX/DivEu): srcA=T0(lower), srcB=T1(upper), srcC=Rn (psrcC). The EU
    // computes Z/C per Musashi + the {oldN,Z,oldV,C} CCR RMW; CHK2 raises EuFault{vec6}
    // on out-of-bounds C. reads+writes NZVC (preserve N/V); no int dst.
    val c2Cmp = {
      val u = DecodedUop()
      u.debugBreakValid := False; u.debugBreakSlot := 0
      u.fpInert()
      u.valid       := pkt.valid
      u.pc          := pkt.pc
      u.nextPc      := nextPc
      u.op          := DecOp.CMP2CHK2
      u.cluster     := Cluster.CPLX
      u.size        := c2Size
      u.memOp       := MemOp.NONE
      u.srcAReg     := U(T0, 5 bits); u.srcAValid := True       // lower (LS-produced)
      u.srcBReg     := U(T1, 5 bits); u.srcBValid := True       // upper (LS-produced)
      u.srcCReg     := c2Rn;          u.srcCValid := True        // Rn (normal reg, psrcC)
      u.dstReg      := 0; u.dstValid := False
      u.useImm      := False; u.imm := 0
      u.readsNzvc   := True; u.readsX := False                   // RMW: read old N/V
      u.writesNzvc  := True; u.writesX := False                  // write {oldN,Z,oldV,C}
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0
      // CHK2 vec-6 is a group-2 (format-$2) trap delivered execute-time via euFault:
      // it stacks the NEXT instruction's PC. faultPc is captured at ALLOC, so set
      // faultUsesNextPc NOW (mirrors CHK / the DIV0 path).
      u.faultUsesNextPc := True
      u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
      u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
      u.divSigned   := c2Ad; u.div64 := False; u.divIsRem := False   // divSigned reused = adReg
      u.isChk2      := c2IsChk2
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := False; u.indexScale := 0
      u.leaAddr     := False; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp       := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.predTaken := False; u.predTarget := U(0, 32 bits)
      u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
      u.firstOfInstr := False                                   // trailing (loads are first)
      u.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)
      u
    }
    // A CMP2/CHK2 with a non-control EA -> illegal (vector 4).
    val c2Bad = isCmp2Chk2Op && !c2EaOk
    when(c2Bad) {
      opUop.op            := DecOp.ILLEGAL
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := True
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.srcCValid := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      opUop.readsNzvc := False; opUop.readsX := False
      opUop.faulted := True; opUop.faultVector := 4; opUop.faultUsesNextPc := False
    }

    // ── ibrUop = an INDIRECT branch to a computed EA address (JMP / JSR target). ──
    // target = base An (psrcA) + imm; imm = displacement (or folded absolute / folded
    // PC). The EA is op[5:0] (`srcEa`, control modes). For (d16,PC) the assembler folds
    // pc into the imm (base=0), exactly like the load crack's pcRelAddr.
    val ctrlPcRelAddr = (pkt.pc + U(2, 32 bits) + srcEa.disp.asUInt).asBits
    val ibrUop = DecodedUop()
    ibrUop.debugBreakValid := False; ibrUop.debugBreakSlot := 0
    ibrUop.fpInert()
    ibrUop.valid         := pkt.valid
    ibrUop.pc            := pkt.pc
    ibrUop.nextPc        := nextPc
    ibrUop.op            := DecOp.BRANCH
    ibrUop.cluster       := Cluster.INT
    ibrUop.size          := Size.LONG
    ibrUop.memOp         := MemOp.NONE
    ibrUop.srcAReg       := srcEa.base; ibrUop.srcAValid := srcEa.baseValid   // EA base An
    ibrUop.srcBReg       := 0;          ibrUop.srcBValid := False
    // Brief-indexed control EA (task #187): the index register Xn rides srcC, exactly like
    // the LS-EU AGU / LEA's leaGenUop. Non-indexed JMP/JSR leaves indexValid=False -> the
    // branch EU's index term is forced to zero (mirrors the LS EU's psrcCValid gating).
    ibrUop.srcCReg       := srcEa.indexReg; ibrUop.srcCValid := srcEa.indexValid
    ibrUop.dstReg        := 0;          ibrUop.dstValid  := False
    ibrUop.useImm        := True
    ibrUop.imm           := Mux(srcEa.pcRel, ctrlPcRelAddr, srcEa.disp)
    ibrUop.readsNzvc     := False; ibrUop.readsX := False
    ibrUop.writesNzvc    := False; ibrUop.writesX := False
    ibrUop.isBranch      := True;  ibrUop.ibranch := True
    ibrUop.stkPush       := False; ibrUop.anInc := 0; ibrUop.isReturn := False; ibrUop.ccrRestore := False; ibrUop.toCcr := False    // JMP: no An postinc (JSR/RTS override)
    ibrUop.eaAuto        := EaAuto.NONE; ibrUop.eaDelta := 0
    ibrUop.cond          := 0;     ibrUop.branchDisp := 0
    ibrUop.unimplemented := False
    ibrUop.faulted       := False; ibrUop.faultVector := 0; ibrUop.isRte := False
    ibrUop.faultUsesNextPc := False
    ibrUop.fpuSoftwareComplete := False; ibrUop.fpuCmdWord := B(0, 16 bits)
    ibrUop.sswInstr := False; ibrUop.faultAtc := True; ibrUop.isCondTrap := False
    ibrUop.divSigned     := False; ibrUop.div64 := False; ibrUop.divIsRem := False
    ibrUop.isChk2        := False
    ibrUop.shiftOp := 0; ibrUop.shiftDir := False; ibrUop.isMovea := False; ibrUop.isScc := False; ibrUop.isDbcc := False; ibrUop.extByte := False; ibrUop.bitOp := 0; ibrUop.bfOp := 0; ibrUop.bfDynamic := False; ibrUop.bfMem := False; ibrUop.bfStoreForm := 0; ibrUop.bcdSub := False
    ibrUop.indexLong := srcEa.indexLong; ibrUop.indexScale := srcEa.indexScale
    ibrUop.leaAddr := False; ibrUop.movesAliasStore := False; ibrUop.fromCcr := False; ibrUop.fromSr := False; ibrUop.needsSupervisor := False; ibrUop.keepCommit := False
    ibrUop.sysOp := False; ibrUop.sysKind := SysKind.NONE; ibrUop.sysReadDir := False
    ibrUop.predTaken := False; ibrUop.predTarget := U(0, 32 bits)
    ibrUop.phtValid := False; ibrUop.phtIndex := U(0, 11 bits); ibrUop.casForm := 0
    // JMP is a single µop (its own first); JSR's ibranch is the TRAILING µop (the push
    // is first), so firstOfInstr is False for JSR.
    ibrUop.firstOfInstr  := !isJsrOp
    ibrUop.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)

    // A JMP/JSR with a non-control EA -> illegal (vector 4), like the `bad` path.
    when(jmpBad || jsrBad) {
      opUop.op            := DecOp.ILLEGAL
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := True
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      opUop.faulted := True; opUop.faultVector := 4; opUop.faultUsesNextPc := False
    }

    // ── Call/return µop builder: every field assigned EXACTLY ONCE (SpinalHDL flags an
    // unconditional reassignment as an ASSIGNMENT OVERLAP), so the varying fields are
    // parameters with safe defaults. Used by the BSR/JSR/RTS/RTR cracks.
    val A7 = 15
    def mkUop(cluster: Cluster.C = Cluster.INT, memOp: MemOp.C = MemOp.NONE,
              srcAReg: UInt = U(0, 5 bits), srcAValid: Bool = False,
              srcBReg: UInt = U(0, 5 bits), srcBValid: Bool = False,
              dstReg: UInt = U(0, 5 bits),  dstValid: Bool = False,
              useImm: Bool = False, imm: Bits = B(0, 32 bits),
              isBranch: Bool = False, ibranch: Bool = False, stkPush: Bool = False,
              anInc: UInt = U(0, 3 bits), cond: Bits = B(0, 4 bits),
              branchDisp: Bits = B(0, 32 bits), first: Bool = True,
              size: Size.C = Size.LONG, ccrRestore: Bool = False,
              writesNzvc: Bool = False, writesX: Bool = False,
              op: DecOp.C = DecOp.MOVE, divIsRem: Bool = False,
              eaAuto: EaAuto.C = EaAuto.NONE, eaDelta: UInt = U(0, 3 bits),
              keepCommit: Bool = False, movesAliasStore: Bool = False,
              isReturn: Bool = False): DecodedUop = {
      val u = DecodedUop()
      u.debugBreakValid := False; u.debugBreakSlot := 0
      u.fpInert()
      u.valid := pkt.valid; u.pc := pkt.pc; u.nextPc := nextPc
      u.op := op; u.cluster := cluster; u.size := size; u.memOp := memOp
      u.srcAReg := srcAReg; u.srcAValid := srcAValid
      u.srcBReg := srcBReg; u.srcBValid := srcBValid
      u.srcCReg := 0; u.srcCValid := False
      u.dstReg := dstReg;  u.dstValid := dstValid
      u.useImm := useImm; u.imm := imm
      u.readsNzvc := False; u.readsX := False; u.writesNzvc := writesNzvc; u.writesX := writesX
      u.isBranch := isBranch; u.ibranch := ibranch; u.stkPush := stkPush; u.anInc := anInc
      u.isReturn := isReturn
      u.eaAuto := eaAuto; u.eaDelta := eaDelta
      u.ccrRestore := ccrRestore; u.toCcr := False
      u.cond := cond; u.branchDisp := branchDisp
      u.unimplemented := False
      u.faulted := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
      u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
      u.divSigned := False; u.div64 := False; u.divIsRem := divIsRem
      u.isChk2 := False
      u.shiftOp := 0; u.shiftDir := False; u.isMovea := False; u.isScc := False; u.isDbcc := False; u.extByte := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.bcdSub := False
      u.indexLong := False; u.indexScale := 0
      u.leaAddr := False; u.movesAliasStore := movesAliasStore; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := keepCommit
      u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.predTaken := False; u.predTarget := U(0, 32 bits)
      u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
      u.firstOfInstr := first
      u.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)
      u
    }

    // ── BSR (0x61xx) — crack into [push.l retPC -> -(A7)] + [bra pc+2+disp]. ──────
    // The push store (stkPush) writes A7 := A7-4 + stores retPC; the branch is an
    // UNCONDITIONAL PC-relative branch (cond=T) to pc+2+disp. (Line-6 BSR's opword
    // cond field is 1=F, so the plain branch path would never take it — we crack here
    // BEFORE the branch path and force cond=T.) The branch carries the architectural
    // commit PC; the push store is the FIRST µop, the branch the macro boundary's
    // single architectural op.
    // PUSH-store µop: stkPush store, addr = An - sizeBytes, data = retPc, int dst (An)
    // = the predecremented address. Used by BSR (here) + JSR (Task 5).
    def pushUop(an: Int, retPc: Bits, first: Bool): DecodedUop =
      mkUop(cluster = Cluster.LS, memOp = MemOp.STORE, stkPush = True,
            srcAReg = U(an, 5 bits), srcAValid = True,    // base An (A7)
            dstReg  = U(an, 5 bits), dstValid  = True,    // new A7 = A7-4
            useImm  = True, imm = retPc, first = first)   // store data = retPC
    // POP-load µop: load.l (An + disp) -> a temp dst.
    def popUop(an: Int, disp: Int, dst: Int, first: Bool): DecodedUop =
      mkUop(cluster = Cluster.LS, memOp = MemOp.LOAD,
            srcAReg = U(an, 5 bits), srcAValid = True,
            useImm  = True, imm = S(disp, 32 bits).asBits,
            dstReg  = U(dst, 5 bits), dstValid = True, first = first)
    // RETURN ibranch: ibranch -> tgt (psrcA + 0), folding An += inc (psrcB=An, dst=An,
    // anInc=inc). Used by RTS/RTR. isReturn=True: BTB/FTB training exclusion (task
    // RTD-btb-training-fix) -- a last-target predictor is a poor return predictor,
    // see BranchEuPlugin's isBtbBranch classification.
    def retBranchUop(tgt: Int, an: Int, inc: Int): DecodedUop =
      mkUop(isBranch = True, ibranch = True,
            srcAReg = U(tgt, 5 bits), srcAValid = True,   // target = tgt + 0
            useImm  = True, imm = B(0, 32 bits),
            srcBReg = U(an, 5 bits),  srcBValid = True,    // postinc base = An
            dstReg  = U(an, 5 bits),  dstValid = True,     // new A7 = A7 + inc
            isReturn = True,
            anInc = U(inc, 3 bits), first = False)

    val isBsr = (op(15 downto 8) === B"8'h61")
    val bsrDisp = {
      val disp8 = op(7 downto 0)
      val d = Bits(32 bits)
      when(disp8 === 0x00) { d := pkt.words(1).asSInt.resize(32).asBits }
        .elsewhen(disp8 === M"11111111") { d := pkt.words(1) ## pkt.words(2) }
        .otherwise { d := disp8.asSInt.resize(32).asBits }
      d
    }
    val bsrPush   = pushUop(A7, nextPc.asBits, first = True)
    val bsrBranch = mkUop(isBranch = True, cond = B(0, 4 bits),    // unconditional BRA
                          branchDisp = bsrDisp, first = False)

    // ── RTS (0x4E75) — crack into [load.l (A7) -> T0] + [ibranch -> T0 ; A7 += 4]. ─
    // The pop load reads (A7) into T0 (a temp); the trailing ibranch redirects to T0
    // and folds the A7 += 4 postincrement. The ibranch depends on the load's T0
    // (dynamic LS wakeup; the IQ tracks the load's pdst via lsBusy/lsWakeup).
    val isRtsOp   = (op === B"16'h4E75")
    val rtsLoad   = popUop(A7, disp = 0, dst = T0, first = True)
    val rtsBranch = retBranchUop(tgt = T0, an = A7, inc = 4)

    val isRtdOp   = (op === B"16'h4E74")

    // ── JSR (0x4E80|ea) — crack into [push.l retPC -> -(A7)] + [ibranch -> EA addr]. ─
    // The push store is FIRST; the ibranch (ibrUop, firstOfInstr=False for JSR) jumps
    // to the EA effective ADDRESS (psrcA = base An + imm = disp/folded), exactly like
    // JMP's target. (No An postinc — JSR does not pop.)
    val jsrPush = pushUop(A7, nextPc.asBits, first = True)

    // ── RTR (0x4E77) — pop CCR (word) then PC (long); restore CCR; A7 += 6. ─────────
    // Crack: [load.w (A7) -> CCR restore (NZVC:=d[3:0], X:=d[4])] + [load.l (A7+2) -> T0]
    // + [ibranch -> T0 ; A7 += 6]. RTR restores ONLY the CCR (SR low byte), never the
    // system byte. 3 µops (the widened AssembledUops budget). The CCR-restore load
    // writes the renamed NZVC + X PRFs from the loaded byte; the PC load -> T0; the
    // trailing ibranch redirects to T0 and folds A7 += 6 (2 for the CCR word + 4 PC).
    val isRtrOp   = (op === B"16'h4E77")
    val rtrCcr    = mkUop(cluster = Cluster.LS, memOp = MemOp.LOAD, size = Size.WORD,
                          srcAReg = U(A7, 5 bits), srcAValid = True,
                          useImm = True, imm = B(0, 32 bits),
                          ccrRestore = True, writesNzvc = True, writesX = True, first = True)
    val rtrPc     = popUop(A7, disp = 2, dst = T0, first = False)   // PC at (A7+2)
    val rtrBranch = retBranchUop(tgt = T0, an = A7, inc = 6)

    // ── LINK / UNLK (line-4 stack-frame ops; reuse the call/return crack machinery) ──
    // An (the frame-pointer register) = arch 8 + op[2:0]. disp16 (LINK) = sign-extended
    // words(1). The kept (architectural-commit) µop of each crack is the LAST one (so the
    // whitebox A7-fold `a7Run` is final at its step) and it writes An (the OTHER arch reg)
    // so BOTH An (the checked archReg) and A7 (a7Run) are validated at one oracle step.
    // The intermediate A7-fold ALU µop sets `divIsRem` — reused as the generic ALU
    // crack-DROP marker (its A7 write still folds; the ALU EU surfaces it as wbObs.divRem).
    val linkAn  = (U(8, 5 bits) + op(2 downto 0).asUInt).resized
    // LINK.W: sext(disp16) = words(1). LINK.L (isLinkLOp): the full 32-bit disp32 =
    // words(1)##words(2) (no sign-extension needed, already 32 bits).
    val linkDisp = Mux(isLinkLOp, (pkt.words(1) ## pkt.words(2)).asSInt,
                                  pkt.words(1).asSInt.resize(32))
    val negDisp  = (-linkDisp).asBits                                // -disp (for An:=A7-disp)

    // ADD-class crack µop (LONG, no flags): dst := srcA + imm. `drop` marks it a dropped
    // (folded) crack µop via divIsRem. Built on mkUop then op/divIsRem overridden.
    def addUop(srcA: UInt, imm: Bits, dst: UInt, first: Bool, drop: Bool): DecodedUop =
      mkUop(srcAReg = srcA, srcAValid = True, useImm = True, imm = imm,
            dstReg = dst, dstValid = True, first = first,
            op = DecOp.ADD, divIsRem = drop)

    // ── RTD (0x4E74) + disp16 — RTS with a stack-deallocation displacement. ───────
    // Pop PC from (A7), then A7 := A7 + 4 + disp16 (the 16-bit sign-extended frame
    // dealloc), then jump. NOT privileged (a user-mode return on the 68040). The anInc
    // field is only 3 bits (can't hold 4+disp16), so the A7 add is a SEPARATE dropped
    // ALU µop (like UNLK's A7-fold), and the ibranch carries NO anInc:
    //   [load.l (A7) -> T0 (first)] [A7 := A7 + (4+disp16) (ADD, drop)] [ibranch -> T0 (kept)].
    // The ibranch is the kept architectural commit (the redirect PC); the A7 add is a
    // dropped crack µop whose A7 write still lands in the PRF (verified by a later A7
    // reader, like LINK/UNLK). disp16 = sign-extended words(1).
    //
    // BUG FIX (RTD-btb-training-fix): this ibranch has anInc=0 (no real A7 arithmetic
    // of its own -- that's the separate rtdA7 µop above), so it must NOT rely on
    // BranchEuPlugin's old `anInc =/= 0` proxy to be recognized as a return -- that
    // proxy classified it as a plain ibranch and let it illegitimately train the
    // BTB/FTB with the popped return address (RTS/RTR correctly set anInc and were
    // excluded; RTD was not). Pass isReturn=True explicitly instead: a real, anInc-
    // independent classification flag (see DecodedUop.isReturn / BranchEuPlugin's
    // isBtbBranch), so the exclusion holds regardless of what anInc carries.
    val rtdDisp   = pkt.words(1).asSInt.resize(32)
    val rtdDealloc= (S(4, 32 bits) + rtdDisp).asBits          // 4 + disp16
    val rtdLoad   = popUop(A7, disp = 0, dst = T0, first = True)
    val rtdA7     = addUop(U(A7, 5 bits), rtdDealloc, U(A7, 5 bits), first = False, drop = True)
    val rtdBranch = mkUop(isBranch = True, ibranch = True,
                          srcAReg = U(T0, 5 bits), srcAValid = True,   // target = T0 + 0
                          useImm  = True, imm = B(0, 32 bits), first = False,
                          isReturn = True)

    // LINK An,#disp16 — [stkPush store dst=An, push old An] + [A7 := A7+disp (drop)]
    //                   + [An := A7-disp (kept)].
    // µop0: a stkPush whose base/dst is A7 (predecrement A7 := A7-4) but whose store DATA
    // is the OLD An (srcB) — the LsEu data0 mux selects the register when srcBValid.
    //
    // LINK A7,#d SPECIAL CASE (PRM §4.133: `SP-4->SP ; An->(SP) ; SP->An ; SP+d->SP`,
    // decrement FIRST): when An IS A7 both "An" and the predecremented "SP" are the SAME
    // register, so:
    //   - the pushed value must be the ALREADY-decremented A7 (A7_old-4), not a stale
    //     register read of "old An" (srcB reads A7 as of BEFORE this µop's own A7-4
    //     write, i.e. the pre-decrement value — wrong for the aliased case). Reuse the
    //     MOVES store-data-aliasing mux (`movesAliasStore`, see LsEuPlugin.s1StoreData):
    //     it substitutes the µop's own computed EA (s1Va = A7_old-4 for this stkPush) as
    //     the store data instead of the raw register read — exactly the value PRM wants.
    //   - `SP->An` is then a no-op (An already IS SP), so the trailing "+d" from linkA7
    //     must NOT be undone by linkAnU's usual "An := A7_current - disp" (that formula
    //     recovers the PRE-"+d" SP for a genuinely separate An — subtracting disp back off
    //     A7 when An=A7 would cancel linkA7's own +d). Feed imm=0 instead of negDisp so
    //     linkAnU degenerates to `A7 := A7_current` (an identity MOVE), landing on
    //     A7_old-4+d — the correct final aliased result.
    val linkIsA7 = (linkAn === U(A7, 5 bits))
    val linkPush = mkUop(cluster = Cluster.LS, memOp = MemOp.STORE, stkPush = True,
                         srcAReg = U(A7, 5 bits), srcAValid = True,   // base A7 (addr = A7-4)
                         srcBReg = linkAn,        srcBValid = True,    // store data = old An
                         dstReg  = U(A7, 5 bits), dstValid  = True,    // A7 := A7-4
                         first = True,
                         movesAliasStore = linkIsA7)  // An==A7: push the computed EA, not srcB
    val linkA7  = addUop(U(A7, 5 bits), linkDisp.asBits, U(A7, 5 bits), first = False, drop = True)
    val linkAnU = addUop(U(A7, 5 bits), Mux(linkIsA7, B(0, 32 bits), negDisp), linkAn, first = False, drop = False)

    // UNLK An — [load.l (An) -> T0] + [A7 := An+4 (drop)] + [An := T0 (kept MOVE)].
    // load.l (An + 0) -> T0 (the saved frame value). An is a hardware UInt (not a Scala
    // Int), so build the load directly via mkUop rather than the Int-keyed popUop.
    val unlkLoad = mkUop(cluster = Cluster.LS, memOp = MemOp.LOAD,
                         srcAReg = linkAn, srcAValid = True,
                         useImm = True, imm = B(0, 32 bits),
                         dstReg = U(T0, 5 bits), dstValid = True, first = True)
    val unlkA7   = addUop(linkAn, B(4, 32 bits), U(A7, 5 bits), first = False, drop = True)
    // MOVE T0 -> An: the ALU MOVE takes its moved value from src2 (= srcB), NOT srcA
    // (srcA is the .B/.W partial-merge OLD-value source). LONG move -> no merge, srcB only.
    val unlkAn   = mkUop(srcBReg = U(T0, 5 bits), srcBValid = True,        // MOVE T0 -> An
                         dstReg = linkAn, dstValid = True, first = False)

    // ── EXG (line C) — exchange two full-32 registers, NO flags. Cracked into 3 MOVE
    // µops through int temp T0: [T0 := regA] [regA := regB] [regB := T0]. Each is a
    // plain full-32 LONG MOVE (mkUop defaults: op=MOVE, INT, size=LONG, writesNzvc=False,
    // writesX=False, isMovea=False) — the ALU MOVE result = srcB (the MOVE source is the
    // srcB slot; srcA is the .B/.W partial-merge old-value source, unused for LONG). A
    // MOVE.L to an An writes it full-32 with no flags (cf. unlkAn above) so the D/A reg-id
    // mapping is the only subtlety. regA = bits-11:9 reg, regB = bits-2:0 reg:
    //   EXG Dx,Dy: regA=D(op11:9),    regB=D(op2:0)
    //   EXG Ax,Ay: regA=A(8+op11:9),  regB=A(8+op2:0)
    //   EXG Dx,Ay: regA=D(op11:9),    regB=A(8+op2:0)
    val exgRx   = op(11 downto 9).asUInt
    val exgRy   = op(2 downto 0).asUInt
    val exgRegA = Mux(isExgAA, (U(8, 5 bits) + exgRx).resized, exgRx.resize(5))            // A only for Ax,Ay
    val exgRegB = Mux(isExgAA || isExgDA, (U(8, 5 bits) + exgRy).resized, exgRy.resize(5)) // A for Ax,Ay & Dx,Ay
    // µ0 writes T0 (arch >= 16) -> the whitebox DROPS it as a temp-only write.
    // µ1 writes regA (an arch reg 0..15) -> a SECOND architectural write for one oracle
    // step. Mark it `divIsRem` (the generic ALU crack-DROP marker, like LINK's A7-fold):
    // its commit OBSERVATION is dropped, but the regA write still lands in the PRF and is
    // verified by a later instruction that reads regA (the DIVREM/Dr pattern). µ2 (regB)
    // is the single KEPT architectural commit, carrying the EXG instruction's oracle step.
    val exgU0 = mkUop(srcBReg = exgRegA, srcBValid = True, dstReg = U(T0, 5 bits), dstValid = True, first = True)
    val exgU1 = mkUop(srcBReg = exgRegB, srcBValid = True, dstReg = exgRegA,       dstValid = True, first = False, divIsRem = True)
    val exgU2 = mkUop(srcBReg = U(T0, 5 bits), srcBValid = True, dstReg = exgRegB, dstValid = True, first = False)

    // ── SOURCE-EA An update (-(An)/(An)+ where the leading load can't carry it) ──
    // A LOAD writes its loaded value to its int dst, so a source-EA auto-update rides a
    // separate trailing ADD µop: An := An ± delta (POSTINC +, PREDEC -). It is a CRACK
    // µop (the macro instruction's architectural commit is the op µop / mem-to-mem
    // store), so its commit observation is DROPPED via `divIsRem` (the generic crack-DROP
    // marker, like LINK's A7-fold) while its An write still lands in the PRF and is
    // verified by a later reader. ALU ADD (LONG, no flags): dst := srcA + imm.
    val srcAnReg   = srcEa.base
    val srcDelta32 = srcEaDelta.resize(32).asSInt
    val srcAnImm   = Mux(srcEa.autoMode === EaAuto.PREDEC, (-srcDelta32).asBits, srcDelta32.asBits)
    val anUpdUop   = addUop(srcAnReg, srcAnImm, srcAnReg, first = False, drop = True)

    // ── Sequence selection (each slot driven exactly once) ─────────────────────
    // pkt.fault  -> [op] (fetch fault delivery)
    // bad        -> [op] (count 1, op carries the illegal override)
    // DIV.L      -> [div] (quotient-only) or [div, divrem] (count 1 or 2); bad divisor
    //               -> [illegal]
    // crackStore -> [store] (count 1)
    // crackLoad  -> [load, op] (count 2)
    // JSR        -> [push, ibranch] (count 2)
    // RTR        -> [pop.w ccr, pop.l pc, ibranch] (count 3)
    // else       -> [op] (count 1)
    // ── LEA / PEA address-generate µop (LS cluster, memOp NONE, leaAddr) ────────
    // Computes the control-EA ADDRESS (base + disp + Xn*scale via the LS-EU AGU) and
    // writes it to an int dst — NO memory access, NO translate (never faults). LEA's dst
    // = An (op[11:9]); PEA's dst = T0 (then pushed). The index reg rides srcC (the AGU
    // sizes+scales it); a (d16,PC)/(d8,PC,Xn) folds pc+2 into imm (base=0). Built inline
    // (every field once) so no mkUop-overlap. `leaDst`/`leaFirst` parameterize LEA vs PEA.
    val ctrlEaPcRel = (pkt.pc + U(2, 32 bits) + srcEa.disp.asUInt).asBits
    def leaGenUop(leaDst: UInt, leaFirst: Bool): DecodedUop = {
      val u = DecodedUop()
      u.debugBreakValid := False; u.debugBreakSlot := 0
      u.fpInert()
      u.valid       := pkt.valid
      u.pc          := pkt.pc
      u.nextPc      := nextPc
      u.op          := DecOp.MOVE
      u.cluster     := Cluster.LS
      u.size        := Size.LONG
      u.memOp       := MemOp.NONE
      u.srcAReg     := srcEa.base; u.srcAValid := srcEa.baseValid     // base An
      u.srcBReg     := 0;          u.srcBValid := False
      u.srcCReg     := srcEa.indexReg; u.srcCValid := srcEa.indexValid // index Xn (AGU)
      u.dstReg      := leaDst;     u.dstValid  := True
      u.useImm      := True
      u.imm         := Mux(srcEa.pcRel, ctrlEaPcRel, srcEa.disp)        // disp / folded abs / folded pc
      u.readsNzvc   := False; u.readsX := False
      u.writesNzvc  := False; u.writesX := False
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0; u.isReturn := False
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.fpuSoftwareComplete := False; u.fpuCmdWord := B(0, 16 bits)
      u.sswInstr := False; u.faultAtc := True; u.isRte := False; u.isCondTrap := False
      u.divSigned   := False; u.div64 := False; u.divIsRem := False
      u.isChk2      := False
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bfStoreForm := 0; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := srcEa.indexLong; u.indexScale := srcEa.indexScale
      u.leaAddr     := True; u.movesAliasStore := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp       := False; u.sysKind := SysKind.NONE; u.sysReadDir := False   // (Track D fields; LEA is not a sysOp)
      u.predTaken := False; u.predTarget := U(0, 32 bits)
      u.phtValid := False; u.phtIndex := U(0, 11 bits); u.casForm := 0
      u.firstOfInstr := leaFirst
      u.lastOfInstr := False   // placeholder -- authoritative value stamped from out.count (see the crack tree below)
      u
    }
    val leaAn   = (U(8, 5 bits) + op(11 downto 9).asUInt).resized
    val leaUop  = leaGenUop(leaAn, leaFirst = True)             // LEA: addr -> An (single µop)
    val peaAddr = leaGenUop(U(T0, 5 bits), leaFirst = True)     // PEA: addr -> T0 (then push)
    // PEA push: stkPush store, base/dst A7 (A7 -= 4), store DATA = T0 (srcB) — the LINK
    // register-data stkPush precedent (LsEu data0 mux selects srcB when srcBValid).
    val peaPush = mkUop(cluster = Cluster.LS, memOp = MemOp.STORE, stkPush = True,
                        srcAReg = U(A7, 5 bits), srcAValid = True,    // base A7 (addr = A7-4)
                        srcBReg = U(T0, 5 bits), srcBValid = True,    // store data = computed EA
                        dstReg  = U(A7, 5 bits), dstValid  = True,    // A7 := A7-4
                        first = False, keepCommit = True)             // PEA's single kept commit (A7-=4)

    // ── BFRESOLVE µop (leading half of a Do/Dw bit-field crack) ────────────────
    // Computes the PACKED offset/width into T0 (read by the trailing BITFIELD bfDynamic
    // µop via srcC). srcA = offset-Dn = D[ext[8:6]] (read iff Do); srcB = width-Dn =
    // D[ext[2:0]] (read iff Dw). imm carries: imm[4:0]=static offset(ext[10:6]),
    // imm[9:5]=static raw-width(ext[4:0]), imm[10]=Do, imm[11]=Dw. The EU folds:
    //   packed = (Do?srcA[4:0]:imm[4:0]) | ((Dw?srcB[4:0]:imm[9:5]) << 5)
    // = the SAME packed layout the static imm uses (offset[4:0], raw width[9:5]). T0 is a
    // temp dst (kept-for-RAW, NOT dropped); the macro architectural commit is the BITFIELD
    // µop. FAST ALU op (lat-1) -> the static int scoreboard wakes the BITFIELD consumer.
    val bfOffDn = bfExt(8 downto 6).asUInt.resize(5)        // offset register (Do)
    val bfWdDn  = bfExt(2 downto 0).asUInt.resize(5)        // width register (Dw)
    val bfResImm = (B(0, 20 bits) ## bfDw ## bfDo ## bfExt(4 downto 0) ## bfExt(10 downto 6)).resize(32)
    val bfResolveUop = mkUop(
      op = DecOp.BFRESOLVE, cluster = Cluster.INT, size = Size.LONG,
      srcAReg = bfOffDn, srcAValid = bfDo,
      srcBReg = bfWdDn,  srcBValid = bfDw,
      dstReg  = U(T0, 5 bits), dstValid = True,
      useImm = True, imm = bfResImm, first = True)

    // Default the 3rd µop slot (only RTR uses it) so every path drives uops(2) once.
    out.uops(2) := opUop
    when(spec.microcoded) {
      // The DecodeStage µcode SEQUENCER owns emission (like MOVEM): emit a benign single
      // placeholder µop here so the assembler's `bad`/crack never fires. The sequencer
      // gates this off (it does not push the placeholder). opUop is already non-`bad`
      // (spec.illegal False + no USED src/dst EA), so unimplemented stays False.
      out.count   := 1
      out.uops(0) := opUop
      out.uops(1) := opUop
    } elsewhen(pkt.fault) {
      // Fetch fault dominates: a single faulted (vector-2) delivery µop.
      out.count   := 1
      out.uops(0) := opUop
      out.uops(1) := opUop
    } elsewhen(bad) {
      out.count   := 1
      out.uops(0) := opUop
      out.uops(1) := opUop
    } elsewhen(isBfMemSpec) {
      // Bit-field MEMORY load-only -> [load.L [byteAddr] -> T0] (opt [load.B [byteAddr+4]
      // -> T1]) [BITFIELD bfMem compute]. needHi (bitOff+width>32) selects the 3-µop span;
      // otherwise 2 µops. Bad EA (non-control) -> illegal (bfmBad forced opUop above).
      when(bfmBad) {
        out.count   := 1
        out.uops(0) := opUop
        out.uops(1) := opUop
      } elsewhen(bfmNeedHi) {
        out.count   := 3
        out.uops(0) := bfmLoadLo
        out.uops(1) := bfmLoadHi
        out.uops(2) := bfmCompute
      } otherwise {
        out.count   := 2
        out.uops(0) := bfmLoadLo
        out.uops(1) := bfmCompute
      }
    } elsewhen(isCmp2Chk2Op) {
      // CMP2/CHK2 -> [load.size [EA] -> T0] [load.size [EA+size] -> T1] [compare].
      // Bad EA (non-control) forced illegal above (c2Bad sets opUop faulted vec4).
      when(c2Bad) {
        out.count   := 1
        out.uops(0) := opUop
        out.uops(1) := opUop
      } otherwise {
        out.count   := 3
        out.uops(0) := c2Load0
        out.uops(1) := c2Load1
        out.uops(2) := c2Cmp
      }
    } elsewhen(isLeaOp) {
      // LEA -> single address-generate µop (addr -> An). Bad EA forced illegal above.
      out.count   := 1
      out.uops(0) := Mux(leaBad, opUop, leaUop)
      out.uops(1) := Mux(leaBad, opUop, leaUop)
    } elsewhen(isPeaOp) {
      // PEA -> [addr -> T0] + [stkPush store T0 -> -(A7)]. Bad EA -> illegal.
      out.count   := Mux(peaBad, U(1, 2 bits), U(2, 2 bits))
      out.uops(0) := Mux(peaBad, opUop, peaAddr)
      out.uops(1) := Mux(peaBad, opUop, peaPush)
    } elsewhen(isMoveFromSrOp || isMoveFromCcrOp) {
      // MOVE from SR/CCR -> reg dest: single ALU op. mem dest: [op -> T1] + [store.w T1].
      // Bad EA (An/#imm/pcRel/complex) forced illegal above.
      val mfBad = moveFromSrBad || moveFromCcrBad
      out.count   := Mux(mfBad, U(1, 2 bits), Mux(mfDstIsMem, U(2, 2 bits), U(1, 2 bits)))
      out.uops(0) := opUop
      out.uops(1) := Mux(mfBad, opUop, Mux(mfDstIsMem, rmwStUop, opUop))
    } elsewhen(isDivLOp) {
      when(!divLOk) {
        out.count   := 1
        out.uops(0) := opUop      // forced illegal (vector 4) above
        out.uops(1) := opUop
      } elsewhen(divlDivisorIsMem) {
        // memSimple divisor (task #180): [load.L <ea> -> T0] prepended. divlUop reads
        // T0 (already wired above) instead of a register/immediate.
        when(divlHasRem) {
          out.count   := 3
          out.uops(0) := divlLoadUop
          out.uops(1) := divlUop      // quotient -> Dq
          out.uops(2) := divremUop    // remainder -> Dr
        } otherwise {
          out.count   := 2
          out.uops(0) := divlLoadUop
          out.uops(1) := divlUop      // quotient-only (Dr==Dq)
        }
      } elsewhen(divlHasRem) {
        out.count   := 2
        out.uops(0) := divlUop      // quotient -> Dq
        out.uops(1) := divremUop    // remainder -> Dr
      } otherwise {
        out.count   := 1
        out.uops(0) := divlUop      // quotient-only (Dr==Dq)
        out.uops(1) := divlUop
      }
    } elsewhen(isMulLOp) {
      when(!mulLOk) {
        out.count   := 1
        out.uops(0) := opUop        // forced illegal (vector 4) above
        out.uops(1) := opUop
      } elsewhen(mullMulIsMem) {
        // memSimple multiplier (task #180): [load.L <ea> -> T0] prepended. mullUop
        // reads T0 (already wired above) instead of a register/immediate.
        when(mull64) {
          out.count   := 3
          out.uops(0) := mullLoadUop
          out.uops(1) := mullUop      // low product -> Dl
          out.uops(2) := mulhiUop     // high product -> Dh
        } otherwise {
          out.count   := 2
          out.uops(0) := mullLoadUop
          out.uops(1) := mullUop      // 32x32->32 (single dest Dl)
        }
      } elsewhen(mull64) {
        out.count   := 2
        out.uops(0) := mullUop      // low product -> Dl
        out.uops(1) := mulhiUop     // high product -> Dh
      } otherwise {
        out.count   := 1
        out.uops(0) := mullUop      // 32x32->32 (single dest Dl)
        out.uops(1) := mullUop
      }
    } elsewhen(isJmpOp) {
      // JMP -> a single indirect branch to the EA address (bad EA forced illegal above).
      out.count   := 1
      out.uops(0) := Mux(jmpBad, opUop, ibrUop)
      out.uops(1) := Mux(jmpBad, opUop, ibrUop)
    } elsewhen(isJsrOp) {
      // JSR -> [push.l retPC -> -(A7)] + [ibranch -> EA addr]. Bad EA -> illegal.
      out.count   := Mux(jsrBad, U(1, 2 bits), U(2, 2 bits))
      out.uops(0) := Mux(jsrBad, opUop, jsrPush)
      out.uops(1) := Mux(jsrBad, opUop, ibrUop)
    } elsewhen(isBsr) {
      // BSR -> [push.l retPC -> -(A7)] + [bra pc+2+disp].
      out.count   := 2
      out.uops(0) := bsrPush
      out.uops(1) := bsrBranch
    } elsewhen(isRtsOp) {
      // RTS -> [load.l (A7) -> T0] + [ibranch -> T0 ; A7 += 4].
      out.count   := 2
      out.uops(0) := rtsLoad
      out.uops(1) := rtsBranch
    } elsewhen(isRtdOp) {
      // RTD -> [load.l (A7) -> T0] + [A7 := A7 + (4+disp16) (drop)] + [ibranch -> T0].
      out.count   := 3
      out.uops(0) := rtdLoad
      out.uops(1) := rtdA7
      out.uops(2) := rtdBranch
    } elsewhen(isRtrOp) {
      // RTR -> [pop.w (A7) -> CCR] + [pop.l (A7+2) -> T0] + [ibranch -> T0 ; A7 += 6].
      out.count   := 3
      out.uops(0) := rtrCcr
      out.uops(1) := rtrPc
      out.uops(2) := rtrBranch
    } elsewhen(isLinkOp || isLinkLOp) {
      // LINK(.W disp16 / .L disp32) -> [stkPush store dst=An, push old An] +
      // [A7 := A7+disp (drop)] + [An := A7-disp (kept)]. Same crack for both forms —
      // linkDisp above already picks the right width/source words.
      out.count   := 3
      out.uops(0) := linkPush
      out.uops(1) := linkA7
      out.uops(2) := linkAnU
    } elsewhen(isUnlkOp) {
      // UNLK -> [load.l (An) -> T0] + [A7 := An+4 (drop)] + [An := T0 (kept)].
      out.count   := 3
      out.uops(0) := unlkLoad
      out.uops(1) := unlkA7
      out.uops(2) := unlkAn
    } elsewhen(isExgOp) {
      // EXG -> [MOVE.L regA -> T0] + [MOVE.L regB -> regA] + [MOVE.L T0 -> regB] (NO flags).
      out.count   := 3
      out.uops(0) := exgU0
      out.uops(1) := exgU1
      out.uops(2) := exgU2
    } elsewhen(crackStore) {
      // MOVE reg -> mem [-(An)/(An)+]: single STORE; the dest An update is FOLDED into
      // the store (its int dst), so no extra µop (register-source fast path, UNCHANGED).
      // MOVE #imm -> mem (immToMemCase): 2-µop materialize-then-store crack: [opUop
      // writes imm -> T1] [stUop stores T1 -> <ea>].
      out.count   := Mux(immToMemCase, U(2, 2 bits), U(1, 2 bits))
      out.uops(0) := Mux(immToMemCase, opUop, stUop)
      out.uops(1) := stUop
    } elsewhen(crackMemMem) {
      // MOVE mem -> mem [-(Ay)/(Ay)+ , -(Ax)/(Ax)+]: [load src -> T0] [src An ADD (drop)]
      // [store T0 -> dst] (+ dest An folded into the store). The SOURCE An update ADD is
      // ordered AFTER the load (reads the old An) but BEFORE the store (the kept commit),
      // so the running architectural A7 reflects a source-(A7)+ before the store's commit
      // snapshot. When the source is not auto, only [load, store] (the anUpd slot reuses
      // the store — count 2).
      out.count   := Mux(srcAuto, U(3, 2 bits), U(2, 2 bits))
      out.uops(0) := ldUop
      out.uops(1) := Mux(srcAuto, anUpdUop, stUop)
      out.uops(2) := stUop
    } elsewhen(crackRmw) {
      // mem-dest RMW -> [load.sz <ea> -> T0] [op (T0+Dn/#imm) -> T1 + flags] [store.sz T1 -> <ea>]
      // The dest=source An update is FOLDED into the store (one An write for the instr).
      out.count   := 3
      out.uops(0) := ldUop
      out.uops(1) := opUop
      out.uops(2) := rmwStUop
    } elsewhen(crackClr) {
      // CLR mem -> [CLR -> T1 (=0) + Z/N flags] [store.sz T1 -> <ea>] (NO load); the An
      // update is FOLDED into the store.
      out.count   := 2
      out.uops(0) := opUop
      out.uops(1) := rmwStUop
    } elsewhen(crackLoadOnly) {
      // TST / CMPI / CMP-mem -> [load.sz <ea> -> T0] [op (flags only)] (NO store). A
      // source-EA auto-update rides a dropped ADD ordered AFTER the load (old An) but
      // BEFORE the op (the kept commit) so the running A7 is current at the op's snapshot.
      out.count   := Mux(srcAuto, U(3, 2 bits), U(2, 2 bits))
      out.uops(0) := ldUop
      out.uops(1) := Mux(srcAuto, anUpdUop, opUop)
      out.uops(2) := opUop
    } elsewhen(crackLoad) {
      // memSimple SOURCE -> [load -> T0] [op (reads T0)]. A source-EA auto-update rides a
      // dropped ADD ordered AFTER the load (it reads the OLD An) but BEFORE the op (the
      // kept commit), so the running architectural A7 reflects a source-(A7)+ at the op's
      // commit snapshot. (The op reads T0; its psrcA tracks the load via the LS wakeup.)
      out.count   := Mux(srcAuto, U(3, 2 bits), U(2, 2 bits))
      out.uops(0) := ldUop
      out.uops(1) := Mux(srcAuto, anUpdUop, opUop)
      out.uops(2) := opUop
    } elsewhen(bfDyn) {
      // Bit-field DYNAMIC offset/width -> [BFRESOLVE -> T0] [BITFIELD bfDynamic (reads T0)].
      out.count   := 2
      out.uops(0) := bfResolveUop
      out.uops(1) := opUop
    } elsewhen(sccIsMem) {
      // Scc <ea> memory dest (task #160) -> [compute cond -> T1 (branch EU)] [store.b
      // T1 -> <ea>] (rmwStUop; NO leading load, like crackClr). The -(An)/(An)+ side
      // effect (An update) rides the trailing store, same as every other rmwStUop user.
      out.count   := 2
      out.uops(0) := opUop
      out.uops(1) := rmwStUop
    } otherwise {
      out.count   := 1
      out.uops(0) := opUop
      out.uops(1) := opUop
    }
    // ── Macro-boundary LAST stamp (Stage 2 task 4, spec section 6.2's `macroLast`) ──
    // `firstOfInstr` is decided per-builder because the builders themselves know which
    // µop leads their crack. `lastOfInstr` is stamped HERE instead, from the crack tree
    // directly above, because the crack tree IS the definition of "which µop is last":
    // every arm assigns `out.uops(0 .. count-1)` as ONE macro's µops in program order,
    // so the macro's last µop is exactly the one at index `count-1`.
    //
    // Doing it per-builder would have meant re-deriving that same fact as a boolean over
    // the crack conditions -- and those conditions are NOT mutually exclusive, only
    // PRIORITISED by this `when/elsewhen` chain. Example hazard: `opUop` is the middle
    // µop of a `crackRmw` [load, op, store] (last=False) but the WHOLE macro on the
    // higher-priority `bad` arm (count=1 -> last=True); a naive per-site `!crackRmw`
    // would silently emit a macro with NO last µop whenever both hold, which a debug
    // stop would then never be able to end on. (`firstOfInstr` carries that same latent
    // shape today -- see `opHasLeadingLoad` -- which is exactly why this field does not
    // copy the pattern.) Stamped last-wins, mirroring the predTaken/phtValid stamp below.
    for (i <- 0 until 3) {
      out.uops(i).lastOfInstr.allowOverride
      out.uops(i).lastOfInstr := out.count === U(i + 1, 2 bits)
    }
    // ── Fetch-time prediction stamp (BTB + bimodal, slice 1) ────────────────────
    // Stamp EVERY µop of this instruction with the SOURCE PACKET's predTaken/predTarget
    // (last-wins, after the crack tree above). Only the branch µop's prediction is read
    // by the branch EU; stamping the non-branch crack µops (push/load/An-update) is
    // harmless. This uniformly covers BSR/JSR/RTS (mkUop cracks) + Bcc/BRA (opUop) +
    // JMP (ibrUop) without per-builder threading. predTaken defaults False at the
    // packet until the fetch redirect is live, so this is behavior-neutral in step 1.
    for (i <- 0 until 3) {
      out.uops(i).predTaken.allowOverride;  out.uops(i).predTaken  := pkt.predTaken
      out.uops(i).predTarget.allowOverride; out.uops(i).predTarget := pkt.predTarget
      // gshare carry-down (slice 3): the conditional-gshare-predicted bit + the 11-bit
      // fetch-time index ride to retire. Only the branch µop's phtValid is acted on (the
      // ROB trains pht[phtIndex]); stamping the crack µops is harmless (a crack µop never
      // retires as a conditional branch). Last-wins after the crack tree, like predTaken.
      out.uops(i).phtValid.allowOverride;   out.uops(i).phtValid   := pkt.phtValid
      out.uops(i).phtIndex.allowOverride;   out.uops(i).phtIndex   := pkt.phtIndex
    }
    out
  }
}
