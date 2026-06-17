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

  /** Internal int temp arch regs targeted by EA cracking. */
  val T0 = 16
  val T1 = 17

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
                   isLoad: Bool, first: Bool, drop: Bool, valid: Bool, pc: UInt, nextPc: UInt): DecodedUop = {
    val u = DecodedUop()
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
    u.srcCReg     := 0;   u.srcCValid := False
    // LOAD writes the register; STORE writes no int reg (no eaAuto fold here).
    u.dstReg      := reg; u.dstValid := isLoad
    u.useImm      := True; u.imm := disp
    u.readsNzvc   := False; u.readsX := False
    u.writesNzvc  := False; u.writesX := False     // MOVEM affects NO condition codes
    u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0
    u.cond        := 0; u.branchDisp := 0
    u.unimplemented := False
    u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
    u.faultAddr   := pc; u.sswInstr := False; u.isRte := False; u.isCondTrap := False
    u.divSigned   := False; u.div64 := False
    // `divRem` = the generic crack-DROP marker (like DIVREM / the source-EA An-update): a
    // dropped MOVEM move does NOT map to its own oracle step (the macro is ONE step — the
    // final An-update / last move is the kept commit), but its reg write still lands in the
    // PRF + is verified by a later reader (lock-step) / checkMem (stores).
    u.divIsRem    := drop
    u.isChk2      := False
    u.eaAuto      := EaAuto.NONE; u.eaDelta := 0     // NO auto-fold: addresses via disp
    u.ccrRestore  := False; u.toCcr := False
    u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.extByte := False
    // Reuse isMovea as the ".W load -> sign-extend the full 32-bit reg" marker (LOAD only).
    u.isMovea     := isLoad && !sizeLong
    u.isScc       := False; u.isDbcc := False
    u.indexLong   := False; u.indexScale := 0
    u.leaAddr := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
    u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
    // Only the VERY FIRST emitted move of the whole MOVEM is the macro boundary
    // (firstOfInstr); every later move + the final An update is non-first, so an
    // interrupt is only taken at the MOVEM boundary (never mid-emission — the partly-
    // emitted moves would otherwise be re-run after RTE since they share the MOVEM pc).
    u.firstOfInstr := first
    u
  }

  /** The single final An update for `(An)+`/`-(An)` MOVEM: `An := An ± count*size`
    * (ONE ADD, not a per-move fold). It is the macro instruction's last µop (NOT first).
    * `signedDelta` is the full signed byte delta (+count*size for postinc, -count*size
    * for predec). It carries the nextPc so the ROB advances PC correctly at commit. */
  def movemAnUpdUop(an: UInt, signedDelta: SInt, valid: Bool, pc: UInt, nextPc: UInt): DecodedUop = {
    val u = DecodedUop()
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
    u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0
    u.cond        := 0; u.branchDisp := 0
    u.unimplemented := False
    u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
    u.faultAddr   := pc; u.sswInstr := False; u.isRte := False; u.isCondTrap := False
    u.divSigned   := False; u.div64 := False; u.divIsRem := False
    u.isChk2      := False
    u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
    u.ccrRestore  := False; u.toCcr := False
    u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.extByte := False
    u.isMovea     := False; u.isScc := False; u.isDbcc := False
    u.indexLong   := False; u.indexScale := 0
    u.leaAddr := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
    u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
    u.firstOfInstr := False    // trailing µop of the MOVEM macro
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
    u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0
    u.cond        := 0; u.branchDisp := 0
    u.unimplemented := False
    u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
    u.faultAddr   := pc; u.sswInstr := False; u.isRte := False; u.isCondTrap := False
    u.divSigned   := False; u.div64 := False
    u.divIsRem    := False
    u.isChk2      := False
    u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
    u.ccrRestore  := False; u.toCcr := False
    u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.extByte := False
    u.isMovea     := False; u.isScc := False; u.isDbcc := False
    u.indexLong   := False; u.indexScale := 0
    u.leaAddr := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
    u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
    u.firstOfInstr := False
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

  def assemble(pkt: DecodePacket): AssembledUops = {
    val out = AssembledUops()
    val op  = pkt.words(0)
    val spec = OperationDecoder.decode(op)

    // EA fields. Source EA = op(5..0). Dest EA (MOVE) = dstMode(8..6) ## dstReg(11..9).
    val srcEa = EaDecoder.decode(op(5 downto 0), spec.size, pkt.words)
    val dstEaField = op(8 downto 6) ## op(11 downto 9)
    val dstEa = EaDecoder.decode(dstEaField, spec.size, pkt.words)

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
    // the right offset (same shape as the DIV.L/MUL.L re-decode). `immEa` is used ONLY
    // for the RMW load/store ADDRESS when the op is a line-0 immediate; the plain
    // `srcEa` (words(1)-based) still drives the operand CLASS (mode/reg are offset-
    // independent) and every non-immediate path.
    val immIsLong = spec.size === Size.LONG
    val immEa = EaDecoder.decode(
      op(5 downto 0), spec.size,
      Mux(immIsLong, Vec(pkt.words(0), pkt.words(3), pkt.words(4)),    // .L: imm = words(1..2)
                     Vec(pkt.words(0), pkt.words(2), pkt.words(3))))   // .B/.W: imm = words(1)
    // The EA descriptor for the RMW load/store ADDRESS: immEa for a line-0 immediate
    // (its ext follows the imm), srcEa otherwise. (klass/base/baseValid/pcRel are
    // offset-independent and identical; only `disp` differs.)
    val opIsLineImm = spec.srcB.kind === OperandKind.IMMEXT
    val rmwEaDisp = Mux(opIsLineImm, immEa.disp, srcEa.disp)

    // ── Operand classification ───────────────────────────────────────────────
    val srcIsReg = (srcEa.klass === EaClass.DATAREG) || (srcEa.klass === EaClass.ADDRREG)
    val srcIsMem = (srcEa.klass === EaClass.MEMSIMPLE)
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
    // no side effect, so both recompute base+disp identically). SWAP/EXT/TAS are Dn-only
    // (TAS-mem deferred) -> a memory EA there stays illegal (line4UnaryMemBad below).
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
    val rmwOpInScope  = !(spec.op === DecOp.SWAP || spec.op === DecOp.EXT || spec.op === DecOp.TAS)
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
    // data is the MOVE source register (srcB EASRC slot).
    val crackStore = (spec.op === DecOp.MOVE) && usesDstEa && dstIsMem && srcIsReg
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
    val isBfMemSpec = (spec.op === DecOp.BITFIELD) && srcIsMem
    val crackLoad = usesSrcEa && srcIsMem && !isAddqSubq && !isLine4Unary && !memDest && !crackMemMem && !isCmp2Chk2Spec && !isBfMemSpec

    // POST-instruction PC = pc + length(bytes). All µops of one instruction share
    // it so the (single, architectural) op-µop commit pc matches the reference's
    // next-instruction PC even for multi-word memory instructions.
    val nextPc = (pkt.pc + (pkt.lenWords << 1)).resize(32)

    // ── opUop = the operation (EASRC operand routed to T0 when cracked) ────────
    val opUop = DecodedUop()
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
    opUop.isBranch      := spec.isBranch; opUop.ibranch := False; opUop.stkPush := False; opUop.anInc := 0; opUop.ccrRestore := False; opUop.toCcr := False; opUop.cond := spec.cond
    opUop.eaAuto        := EaAuto.NONE; opUop.eaDelta := 0
    opUop.branchDisp    := 0
    opUop.unimplemented := False
    opUop.faulted       := False
    opUop.faultVector   := 0
    opUop.faultUsesNextPc := False  // default: stack the faulting instr PC (pc); TRAP/TRAPV -> nextPc
    opUop.faultAddr     := pkt.pc
    opUop.sswInstr      := False
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
    opUop.extByte       := spec.extByte
    opUop.isMovea       := False
    opUop.isScc         := False; opUop.isDbcc := False
    opUop.indexLong     := False; opUop.indexScale := 0
    opUop.leaAddr := False; opUop.fromCcr := False; opUop.fromSr := False; opUop.needsSupervisor := False; opUop.keepCommit := False
    opUop.sysOp := False; opUop.sysKind := SysKind.NONE; opUop.sysReadDir := False
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
    when(spec.op === DecOp.SHIFT) {
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
    ldUop.valid         := pkt.valid
    ldUop.pc            := pkt.pc
    ldUop.nextPc        := nextPc
    ldUop.op            := DecOp.MOVE
    ldUop.cluster       := Cluster.LS
    // mem-RMW / load-only access size: BYTE for a bit-op (mem BITOP is always byte),
    // else the op size. (BITOP left spec.size at the WORD default; resolve to BYTE.)
    ldUop.size          := Mux(isBitOp, Size.BYTE, spec.size)
    ldUop.memOp         := MemOp.LOAD
    ldUop.srcAReg       := srcEa.base; ldUop.srcAValid := srcEa.baseValid
    ldUop.srcBReg       := 0;          ldUop.srcBValid := False
    // Indexed EA: the index register rides srcC; the AGU sizes+scales it. For a line-0
    // immediate mem-dest the index descriptor lives on immEa (its ext = the brief word,
    // following the imm); otherwise srcEa. base+disp already account for pcRel/disp.
    val ldIdxEa = Mux(opIsLineImm, immEa, srcEa)
    ldUop.srcCReg       := ldIdxEa.indexReg;   ldUop.srcCValid := ldIdxEa.indexValid
    ldUop.indexLong     := ldIdxEa.indexLong;  ldUop.indexScale := ldIdxEa.indexScale
    ldUop.leaAddr := False; ldUop.fromCcr := False; ldUop.fromSr := False; ldUop.needsSupervisor := False; ldUop.keepCommit := False
    ldUop.sysOp := False; ldUop.sysKind := SysKind.NONE; ldUop.sysReadDir := False
    ldUop.dstReg        := U(T0, 5 bits); ldUop.dstValid := True
    ldUop.useImm        := True
    // disp = rmwEaDisp (immEa for a line-0 immediate mem-dest, else srcEa). A (d16,PC)
    // source folds pc into the absolute disp (never a line-0 immediate -> srcEa.disp).
    val pcRelAddr = (pkt.pc + U(2, 32 bits) + srcEa.disp.asUInt).asBits
    ldUop.imm           := Mux(srcEa.pcRel, pcRelAddr, rmwEaDisp)
    ldUop.readsNzvc     := False; ldUop.readsX := False
    ldUop.writesNzvc    := False; ldUop.writesX := False
    ldUop.isBranch      := False; ldUop.ibranch := False; ldUop.stkPush := False; ldUop.anInc := 0; ldUop.ccrRestore := False; ldUop.toCcr := False; ldUop.cond := 0
    // Auto-update SOURCE EA: the load computes the access address (PREDEC: An-delta;
    // POSTINC: An). The load does NOT write An (its int dst is the loaded value T0); the
    // An update rides a separate ADD µop (anUpdUop). For crackRmw the SAME eaAuto is on
    // BOTH the load (here) and the store so they access the same predec address.
    ldUop.eaAuto        := srcEa.autoMode; ldUop.eaDelta := srcEaDelta
    ldUop.branchDisp    := 0
    ldUop.unimplemented := False
    ldUop.faulted       := False; ldUop.faultVector := 0; ldUop.isRte := False
    ldUop.faultUsesNextPc := False
    ldUop.faultAddr     := pkt.pc; ldUop.sswInstr := False; ldUop.isCondTrap := False
    ldUop.divSigned     := False; ldUop.div64 := False; ldUop.divIsRem := False
    ldUop.isChk2        := False
    ldUop.shiftOp := 0; ldUop.shiftDir := False; ldUop.isMovea := False; ldUop.isScc := False; ldUop.isDbcc := False; ldUop.extByte := False; ldUop.bitOp := 0; ldUop.bfOp := 0; ldUop.bfDynamic := False; ldUop.bfMem := False; ldUop.bcdSub := False
    ldUop.firstOfInstr  := True    // the LOAD is the FIRST µop of a cracked instruction

    // ── stUop = the STORE (used only when crackStore) ──────────────────────────
    // Address = dst base An (psrcA) + dst disp(imm); data = the MOVE source register
    // (srcB). No int dst. MOVE to memory DOES set NZVC from the moved value (N=sign,
    // Z=value==0, V=0, C=0) — implementation (a): the STORE µop carries writesNzvc +
    // a renamed NZVC dest; the LS EU computes N/Z of the store data at the access size
    // and writes the NZVC PRF (+ bypass) at completion. (MOVEA — to an address reg —
    // never reaches here: an address-reg dst is not memSimple.)
    val stUop = DecodedUop()
    stUop.valid         := pkt.valid
    stUop.pc            := pkt.pc
    stUop.nextPc        := nextPc
    stUop.op            := DecOp.MOVE
    stUop.cluster       := Cluster.LS
    stUop.size          := spec.size
    stUop.memOp         := MemOp.STORE
    stUop.srcAReg       := dstEa.base; stUop.srcAValid := dstEa.baseValid
    // store DATA: the MOVE source register (reg-to-mem), OR the loaded value T0 for a
    // mem-to-mem MOVE (the leading load wrote T0). The same store µop folds the dest-EA
    // An update + the MOVE-to-mem NZVC for both forms.
    stUop.srcBReg       := Mux(crackMemMem, U(T0, 5 bits), srcEa.reg); stUop.srcBValid := True
    // Indexed MOVE destination: the dest-EA index reg rides srcC (store DATA is srcB). A
    // mem-to-mem MOVE composes: srcA=dst base, srcB=T0 (loaded data), srcC=dst index.
    stUop.srcCReg       := dstEa.indexReg;  stUop.srcCValid := dstEa.indexValid
    stUop.indexLong     := dstEa.indexLong; stUop.indexScale := dstEa.indexScale
    stUop.leaAddr := False; stUop.fromCcr := False; stUop.fromSr := False; stUop.needsSupervisor := False; stUop.keepCommit := False
    stUop.sysOp := False; stUop.sysKind := SysKind.NONE; stUop.sysReadDir := False
    // Auto-update DEST EA (-(An)/(An)+): the store's (otherwise unused) int dst carries
    // the An write (An := An ± delta) — generalizing stkPush to any An. PREDEC: addr =
    // An-delta = the written An; POSTINC: addr = An, written An = An+delta. The LS EU
    // selects compData per eaAuto. A non-auto store writes no int reg (dstValid False).
    stUop.dstReg        := dstEa.base; stUop.dstValid  := dstAuto
    stUop.useImm        := True
    val stPcRelAddr = (pkt.pc + U(2, 32 bits) + dstEa.disp.asUInt).asBits
    stUop.imm           := Mux(dstEa.pcRel, stPcRelAddr, dstEa.disp)
    stUop.readsNzvc     := False; stUop.readsX := False
    stUop.writesNzvc    := True;  stUop.writesX := False   // MOVE to memory sets NZVC
    stUop.isBranch      := False; stUop.ibranch := False; stUop.stkPush := False; stUop.anInc := 0; stUop.ccrRestore := False; stUop.toCcr := False; stUop.cond := 0
    stUop.eaAuto        := dstEa.autoMode; stUop.eaDelta := dstEa.autoDelta
    stUop.branchDisp    := 0
    stUop.unimplemented := False
    stUop.faulted       := False; stUop.faultVector := 0; stUop.isRte := False
    stUop.faultUsesNextPc := False
    stUop.faultAddr     := pkt.pc; stUop.sswInstr := False; stUop.isCondTrap := False
    stUop.divSigned     := False; stUop.div64 := False; stUop.divIsRem := False
    stUop.isChk2        := False
    stUop.shiftOp := 0; stUop.shiftDir := False; stUop.isMovea := False; stUop.isScc := False; stUop.isDbcc := False; stUop.extByte := False; stUop.bitOp := 0; stUop.bfOp := 0; stUop.bfDynamic := False; stUop.bfMem := False; stUop.bcdSub := False
    // A single reg-to-mem STORE is its own first µop; a mem-to-mem store TRAILS the load.
    stUop.firstOfInstr  := !crackMemMem

    // ── rmwStUop = the STORE of a memory-destination RMW (crackRmw / crackClr) ──
    // The EA is op[5:0] = `srcEa` (the SAME descriptor the load used — MEMSIMPLE has no
    // side effect, so base+disp recompute identically). data = T1 (the op result). NO
    // int dst, NO flags (the op µop owns NZVCX). firstOfInstr=False (a trailing µop).
    val rmwStUop = DecodedUop()
    rmwStUop.valid         := pkt.valid
    rmwStUop.pc            := pkt.pc
    rmwStUop.nextPc        := nextPc
    rmwStUop.op            := DecOp.MOVE
    rmwStUop.cluster       := Cluster.LS
    rmwStUop.size          := Mux(isBitOp, Size.BYTE, spec.size)   // bit-op store is byte
    rmwStUop.memOp         := MemOp.STORE
    rmwStUop.srcAReg       := srcEa.base; rmwStUop.srcAValid := srcEa.baseValid
    rmwStUop.srcBReg       := U(T1, 5 bits); rmwStUop.srcBValid := True       // store data = T1
    // Indexed RMW: load + store share ONE EA; the index reg rides srcC on BOTH so they
    // compute the SAME indexed address (mirrors how eaAuto is on both).
    val rmwIdxEa = Mux(opIsLineImm, immEa, srcEa)
    rmwStUop.srcCReg       := rmwIdxEa.indexReg;  rmwStUop.srcCValid := rmwIdxEa.indexValid
    rmwStUop.indexLong     := rmwIdxEa.indexLong; rmwStUop.indexScale := rmwIdxEa.indexScale
    rmwStUop.leaAddr := False; rmwStUop.fromCcr := False; rmwStUop.fromSr := False; rmwStUop.needsSupervisor := False; rmwStUop.keepCommit := False
    rmwStUop.sysOp := False; rmwStUop.sysKind := SysKind.NONE; rmwStUop.sysReadDir := False
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
    rmwStUop.isBranch      := False; rmwStUop.ibranch := False; rmwStUop.stkPush := False; rmwStUop.anInc := 0; rmwStUop.ccrRestore := False; rmwStUop.toCcr := False; rmwStUop.cond := 0
    rmwStUop.eaAuto        := srcEa.autoMode; rmwStUop.eaDelta := srcEaDelta
    rmwStUop.branchDisp    := 0
    rmwStUop.unimplemented := False
    rmwStUop.faulted       := False; rmwStUop.faultVector := 0; rmwStUop.isRte := False
    rmwStUop.faultUsesNextPc := False
    rmwStUop.faultAddr     := pkt.pc; rmwStUop.sswInstr := False; rmwStUop.isCondTrap := False
    rmwStUop.divSigned     := False; rmwStUop.div64 := False; rmwStUop.divIsRem := False
    rmwStUop.isChk2        := False
    rmwStUop.shiftOp := 0; rmwStUop.shiftDir := False; rmwStUop.isMovea := False; rmwStUop.isScc := False; rmwStUop.isDbcc := False; rmwStUop.extByte := False; rmwStUop.bitOp := 0; rmwStUop.bfOp := 0; rmwStUop.bfDynamic := False; rmwStUop.bfMem := False; rmwStUop.bcdSub := False
    rmwStUop.firstOfInstr  := False    // the trailing store of a cracked RMW

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
    // EOR (line B, register dest): the EA (op[5:0]) is the DESTINATION, read AND
    // written. This slice supports a DATA-REGISTER destination only; a memory EA is
    // the deferred RMW (load-op-store) form -> illegal. `eorMemBad` forces the illegal
    // path (it must NOT crack a leading load, which the generic srcEaOk would do). The
    // EORI #imm,CCR form (also spec.op==EOR, klass==IMM) is NOT gated here (-> !isToCcr).
    // A MEMSIMPLE EA is now a valid RMW destination (load-op-store crack); only An /
    // #imm / MEMCOMPLEX stay illegal -> reject "not DATAREG and not MEMSIMPLE".
    val eorMemBad = (spec.op === DecOp.EOR) && (srcEa.klass =/= EaClass.DATAREG) &&
                    (srcEa.klass =/= EaClass.MEMSIMPLE) && !isToCcr
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
    // Line-4 unary (CLR/NEG/NEGX/NOT/TST/SWAP/EXT/TAS): DATA-register OR (CLR/NEG/NEGX/
    // NOT/TST) a MEMSIMPLE EA (the RMW crack). SWAP/EXT/TAS are Dn-only (TAS-mem deferred)
    // -> a non-data-reg EA there stays illegal. An / #imm / MEMCOMPLEX always illegal.
    val line4UnaryMemBad = isLine4Unary && (srcEa.klass =/= EaClass.DATAREG) &&
                           !(rmwOpInScope && (srcEa.klass === EaClass.MEMSIMPLE))
    // Line-0 immediate (srcB = IMMEXT): the EA (op[5:0]) is the DESTINATION. DATA-register
    // OR a MEMSIMPLE EA (the RMW crack); An / #imm / MEMCOMPLEX stay illegal. The to-CCR
    // form is the one exception.
    val lineImmBad = isLineImm && !isBitOp && (srcEa.klass =/= EaClass.DATAREG) &&
                     (srcEa.klass =/= EaClass.MEMSIMPLE) && !isToCcr
    // Bit op (BTST/BCHG/BCLR/BSET, static or dynamic): the EA (op[5:0]) is the dest
    // (tested + written, except BTST). In scope: DATA-register (LONG, mod-32) OR a
    // MEMSIMPLE EA (BYTE, mod-8; BTST load-only, others mem-RMW crack). An / #imm /
    // MEMCOMPLEX (incl. predec/postinc/indexed) stay illegal -> defer. (Note: a static
    // bit-op has srcB=IMMEXT, so `isLineImm` is also true for it — `lineImmBad` excludes
    // bit-ops via `!isBitOp` so this gate is the single bit-op EA check, static+dynamic.)
    val bitOpMemBad = isBitOp && (srcEa.klass =/= EaClass.DATAREG) && (srcEa.klass =/= EaClass.MEMSIMPLE)
    // PC-relative EA used as a DESTINATION (op[5:0] = the written EA, i.e. spec.dst.kind
    // == EASRC): ISA-illegal (PC-space is not alterable). The mem-dest gates above only
    // reject `=/= MEMSIMPLE`; a pcRel MEMSIMPLE (d16,PC)/(d8,PC,Xn) would otherwise slip
    // through (memDest is now false for pcRel, so no RMW crack fires -> would mis-crack a
    // leading load). Force the illegal path. Source pcRel (read) stays valid.
    val eaDstPcRelBad = eaIsDst && srcIsMem && srcEa.pcRel
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
    val isSccOp    = isLine5 && (ss5 === 3) && (mode5 === 0)          // Scc Dn (in scope)
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
    // DATAREG/ADDRREG, imm IMM). Indexed (d8,An,Xn)/(d8,PC,Xn) is now MEMSIMPLE too, but an
    // INDEXED JMP/JSR target is DEFERRED here (the branch-EU AGU reads no index register —
    // it would silently drop the index). Reject an indexed control EA rather than mis-jump.
    val ctrlEaOk = srcIsMem && !srcEa.indexValid
    // RTS (0x4E75) / RTR (0x4E77) are line-4 returns cracked below (NOT illegal).
    val isRtsBad = (op === B"16'h4E75")
    val isRtrBad = (op === B"16'h4E77")
    // LINK An,#disp16 (0100 1110 0101 0aaa) / UNLK An (0100 1110 0101 1aaa): line-4
    // stack-frame ops cracked below (NOT illegal). op[15:4]==0x4E5, op[3] selects.
    val isLinkOp = (op(15 downto 4) === B"12'h4E5") && !op(3)
    val isUnlkOp = (op(15 downto 4) === B"12'h4E5") &&  op(3)
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
    val bad = !isRteOp && !isTrapOp && !isTrapvOp && !isTrapccOp && !isDivLOp && !isMulLOp && !isJmpOp && !isJsrOp &&
              !isRtsBad && !isRtrBad && !isSccOp && !isDbccOp && !isLinkOp && !isUnlkOp && !isExgOp &&
              !isLeaOp && !isPeaOp && !isMoveFromSrOp && !isMoveFromCcrOp && !isMoveToCcrOp &&
              !isSysOp && !isRtdBad && !isCmp2Chk2Enc && !isBfMemSpec &&
              (!pkt.simple || spec.illegal || eorMemBad || lineImmBad || addqMemBad || sccMemBad ||
               line4UnaryMemBad || aluRmwMemBad || bitOpMemBad || eaDstPcRelBad ||
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
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      opUop.unimplemented := False
      opUop.faulted := False; opUop.faultVector := 0
      opUop.isRte   := True
    }
    when(bad) {
      opUop.op            := DecOp.ILLEGAL
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := True
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
      // Illegal instruction -> precise fault, vector 4. The op µop retires as the
      // faulting head; the exception FSM stacks the frame + vectors.
      opUop.faulted     := True
      opUop.faultVector := 4
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
    when(isSccOp) {
      opUop.op            := DecOp.ILLEGAL    // no ALU action; the branch EU drives the write
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.isBranch      := True             // branch EU (condition mux)
      opUop.isScc         := True
      opUop.cond          := cccc5
      opUop.readsNzvc     := True             // read NZVC for the condition
      opUop.srcAReg       := rrr5; opUop.srcAValid := True   // old Dn (merge upper-24 source)
      opUop.srcBValid     := False
      opUop.dstReg        := rrr5; opUop.dstValid := True    // Dn (byte-merged result)
      opUop.useImm        := False
      opUop.writesNzvc    := False; opUop.writesX := False
      opUop.branchDisp    := 0
      opUop.unimplemented := False
      opUop.faulted       := False; opUop.faultVector := 0
      opUop.isRte         := False; opUop.isCondTrap := False; opUop.ibranch := False
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
    // The ITLB faulted translating this fetch (non-resident / supervisor I-page), so
    // the instruction bytes are don't-care: emit a single faulted op µop that DELIVERS
    // the format-$7 access fault (vector 2) at retire. faultAddr = the fetch PC (the
    // EA stacked in the $7 frame); sswInstr = 1 so the exception FSM stacks a
    // program-space SSW. Last-wins over `bad`/RTE so a faulting fetch always delivers.
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
      opUop.faultAddr     := pkt.pc          // faulting instruction PC
      opUop.sswInstr      := True            // instruction fetch (program-space SSW)
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
    divlUop.readsNzvc     := False; divlUop.readsX := False
    divlUop.writesNzvc    := True;  divlUop.writesX := False               // DIV sets N/Z/V
    divlUop.isBranch      := False; divlUop.ibranch := False; divlUop.stkPush := False; divlUop.anInc := 0; divlUop.ccrRestore := False; divlUop.toCcr := False; divlUop.cond := 0; divlUop.branchDisp := 0
    divlUop.eaAuto        := EaAuto.NONE; divlUop.eaDelta := 0
    divlUop.unimplemented := False
    divlUop.faulted       := False; divlUop.faultVector := 0; divlUop.isRte := False
    divlUop.faultUsesNextPc := True            // DIV0 stacks nextPc (group-2 format-$2)
    divlUop.faultAddr     := pkt.pc; divlUop.sswInstr := False; divlUop.isCondTrap := False
    divlUop.divSigned     := divlSigned; divlUop.div64 := divl64; divlUop.divIsRem := False
    divlUop.isChk2        := False
    divlUop.shiftOp := 0; divlUop.shiftDir := False; divlUop.isMovea := False; divlUop.isScc := False; divlUop.isDbcc := False; divlUop.extByte := False; divlUop.bitOp := 0; divlUop.bfOp := 0; divlUop.bfDynamic := False; divlUop.bfMem := False; divlUop.bcdSub := False
    divlUop.indexLong := False; divlUop.indexScale := 0
    divlUop.leaAddr := False; divlUop.fromCcr := False; divlUop.fromSr := False; divlUop.needsSupervisor := False; divlUop.keepCommit := False
    divlUop.sysOp := False; divlUop.sysKind := SysKind.NONE; divlUop.sysReadDir := False
    divlUop.firstOfInstr  := True
    // 64-bit dividend high word Dr: carried in srcC (psrcC after rename). For the
    // 32-bit form psrcC is unused.
    divlUop.srcCReg       := divlDr; divlUop.srcCValid := divl64

    // DIVREM (remainder-move) µop: CPLX, writes the DivEu's latched remainder to Dr.
    // No real register source (the remainder is the DivEu's internal latch) -> it has
    // an implicit dependency on the immediately-preceding DIV, enforced by age-ordered
    // single-outstanding CPLX issue. It writes Dr; sets no flags.
    val divremUop = DecodedUop()
    divremUop.valid         := pkt.valid
    divremUop.pc            := pkt.pc
    divremUop.nextPc        := nextPc
    divremUop.op            := DecOp.DIVREM
    divremUop.cluster       := Cluster.CPLX
    divremUop.size          := Size.LONG
    divremUop.memOp         := MemOp.NONE
    divremUop.srcAReg       := 0; divremUop.srcAValid := False
    divremUop.srcBReg       := 0; divremUop.srcBValid := False
    divremUop.srcCReg       := 0; divremUop.srcCValid := False
    divremUop.useImm        := False; divremUop.imm := 0
    divremUop.dstReg        := divlDr; divremUop.dstValid := True          // remainder -> Dr
    divremUop.readsNzvc     := False; divremUop.readsX := False
    divremUop.writesNzvc    := False; divremUop.writesX := False
    divremUop.isBranch      := False; divremUop.ibranch := False; divremUop.stkPush := False; divremUop.anInc := 0; divremUop.ccrRestore := False; divremUop.toCcr := False; divremUop.cond := 0; divremUop.branchDisp := 0
    divremUop.eaAuto        := EaAuto.NONE; divremUop.eaDelta := 0
    divremUop.unimplemented := False
    divremUop.faulted       := False; divremUop.faultVector := 0; divremUop.isRte := False
    divremUop.faultUsesNextPc := False
    divremUop.faultAddr     := pkt.pc; divremUop.sswInstr := False; divremUop.isCondTrap := False
    divremUop.divSigned     := divlSigned; divremUop.div64 := divl64; divremUop.divIsRem := True
    divremUop.isChk2        := False
    divremUop.shiftOp := 0; divremUop.shiftDir := False; divremUop.isMovea := False; divremUop.isScc := False; divremUop.isDbcc := False; divremUop.extByte := False; divremUop.bitOp := 0; divremUop.bfOp := 0; divremUop.bfDynamic := False; divremUop.bfMem := False; divremUop.bcdSub := False
    divremUop.indexLong := False; divremUop.indexScale := 0
    divremUop.leaAddr := False; divremUop.fromCcr := False; divremUop.fromSr := False; divremUop.needsSupervisor := False; divremUop.keepCommit := False
    divremUop.sysOp := False; divremUop.sysKind := SysKind.NONE; divremUop.sysReadDir := False
    divremUop.firstOfInstr  := False           // trailing crack µop

    // DIV.L is valid only when its divisor EA is reg/imm. A memSimple divisor would
    // need a leading load crack too (3-µop) -> defer for now; reg/imm cover the
    // lock-step + common cases.
    val divLOk = divlDivisorIsReg || divlDivisorIsImm

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
    mullUop.isBranch      := False; mullUop.ibranch := False; mullUop.stkPush := False; mullUop.anInc := 0; mullUop.ccrRestore := False; mullUop.toCcr := False; mullUop.cond := 0; mullUop.branchDisp := 0
    mullUop.eaAuto        := EaAuto.NONE; mullUop.eaDelta := 0
    mullUop.unimplemented := False
    mullUop.faulted       := False; mullUop.faultVector := 0; mullUop.isRte := False
    mullUop.faultUsesNextPc := False
    mullUop.faultAddr     := pkt.pc; mullUop.sswInstr := False; mullUop.isCondTrap := False
    mullUop.divSigned     := mullSigned; mullUop.div64 := mull64; mullUop.divIsRem := False
    mullUop.isChk2        := False
    mullUop.shiftOp := 0; mullUop.shiftDir := False; mullUop.isMovea := False; mullUop.isScc := False; mullUop.isDbcc := False; mullUop.extByte := False; mullUop.bitOp := 0; mullUop.bfOp := 0; mullUop.bfDynamic := False; mullUop.bfMem := False; mullUop.bcdSub := False
    mullUop.indexLong := False; mullUop.indexScale := 0
    mullUop.leaAddr := False; mullUop.fromCcr := False; mullUop.fromSr := False; mullUop.needsSupervisor := False; mullUop.keepCommit := False
    mullUop.sysOp := False; mullUop.sysKind := SysKind.NONE; mullUop.sysReadDir := False
    mullUop.firstOfInstr  := True

    // MULHI (high-product move) µop (.L64 only): CPLX, writes the EU's LATCHED high
    // product to Dh. No real register source (the high product is an internal latch)
    // -> implicit dependency on the immediately-preceding MUL, enforced by age-ordered
    // single-outstanding CPLX issue. Writes Dh; sets no flags (the MUL set N/Z; V=0).
    val mulhiUop = DecodedUop()
    mulhiUop.valid         := pkt.valid
    mulhiUop.pc            := pkt.pc
    mulhiUop.nextPc        := nextPc
    mulhiUop.op            := DecOp.MULHI
    mulhiUop.cluster       := Cluster.CPLX
    mulhiUop.size          := Size.LONG
    mulhiUop.memOp         := MemOp.NONE
    mulhiUop.srcAReg       := 0; mulhiUop.srcAValid := False
    mulhiUop.srcBReg       := 0; mulhiUop.srcBValid := False
    mulhiUop.srcCReg       := 0; mulhiUop.srcCValid := False
    mulhiUop.useImm        := False; mulhiUop.imm := 0
    mulhiUop.dstReg        := mullDh; mulhiUop.dstValid := True             // high product -> Dh
    mulhiUop.readsNzvc     := False; mulhiUop.readsX := False
    mulhiUop.writesNzvc    := False; mulhiUop.writesX := False
    mulhiUop.isBranch      := False; mulhiUop.ibranch := False; mulhiUop.stkPush := False; mulhiUop.anInc := 0; mulhiUop.ccrRestore := False; mulhiUop.toCcr := False; mulhiUop.cond := 0; mulhiUop.branchDisp := 0
    mulhiUop.eaAuto        := EaAuto.NONE; mulhiUop.eaDelta := 0
    mulhiUop.unimplemented := False
    mulhiUop.faulted       := False; mulhiUop.faultVector := 0; mulhiUop.isRte := False
    mulhiUop.faultUsesNextPc := False
    mulhiUop.faultAddr     := pkt.pc; mulhiUop.sswInstr := False; mulhiUop.isCondTrap := False
    mulhiUop.divSigned     := mullSigned; mulhiUop.div64 := mull64; mulhiUop.divIsRem := False
    mulhiUop.isChk2        := False
    mulhiUop.shiftOp := 0; mulhiUop.shiftDir := False; mulhiUop.isMovea := False; mulhiUop.isScc := False; mulhiUop.isDbcc := False; mulhiUop.extByte := False; mulhiUop.bitOp := 0; mulhiUop.bfOp := 0; mulhiUop.bfDynamic := False; mulhiUop.bfMem := False; mulhiUop.bcdSub := False
    mulhiUop.indexLong := False; mulhiUop.indexScale := 0
    mulhiUop.leaAddr := False; mulhiUop.fromCcr := False; mulhiUop.fromSr := False; mulhiUop.needsSupervisor := False; mulhiUop.keepCommit := False
    mulhiUop.sysOp := False; mulhiUop.sysKind := SysKind.NONE; mulhiUop.sysReadDir := False
    mulhiUop.firstOfInstr  := False           // trailing crack µop

    // MUL.L is valid only when its multiplier EA is reg/imm (a memSimple multiplier
    // would need a leading load crack -> defer; reg/imm cover lock-step + common cases).
    val mulLOk = mullMulIsReg || mullMulIsImm
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
    val bfmEaOk  = (bfmEaDec.klass === EaClass.MEMSIMPLE) && (bfmEaDec.autoMode === EaAuto.NONE)
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
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.faultAddr   := pkt.pc; u.sswInstr := False; u.isRte := False; u.isCondTrap := False
      u.divSigned   := False; u.div64 := False; u.divIsRem := False; u.isChk2 := False
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := bfmEaDec.indexLong; u.indexScale := bfmEaDec.indexScale
      u.leaAddr     := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp       := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.firstOfInstr := first
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
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.faultAddr   := pkt.pc; u.sswInstr := False; u.isRte := False; u.isCondTrap := False
      u.divSigned   := False; u.div64 := False; u.divIsRem := False; u.isChk2 := False
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0
      u.bfOp        := bfmBfOp; u.bfDynamic := False; u.bfMem := True; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := False; u.indexScale := 0
      u.leaAddr     := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp       := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.firstOfInstr := False
      u
    }
    // A bit-field memory op with a non-control EA -> illegal (vector 4).
    val bfmBad = isBfMemSpec && !bfmEaOk
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
    // as MEMSIMPLE; reg-direct/imm/(An)+/-(An) are NOT control. Indexed IS allowed (the
    // LS-EU AGU reads the index, like LEA). pcRel folds pc.
    val c2SrcEa = EaDecoder.decode(op(5 downto 0), c2Size, Vec(pkt.words(0), pkt.words(2), pkt.words(3)))
    val c2EaOk  = (c2SrcEa.klass === EaClass.MEMSIMPLE) && (c2SrcEa.autoMode === EaAuto.NONE)
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
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.faultAddr   := pkt.pc; u.sswInstr := False; u.isRte := False; u.isCondTrap := False
      u.divSigned   := False; u.div64 := False; u.divIsRem := False; u.isChk2 := False
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := c2SrcEa.indexLong; u.indexScale := c2SrcEa.indexScale
      u.leaAddr     := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp       := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.firstOfInstr := first
      u
    }
    val c2Load0 = c2LoadUop(c2Disp1, T0, first = True)       // lower @ [EA]
    val c2Load1 = c2LoadUop(c2Disp2, T1, first = False)      // upper @ [EA+size]
    // Compare µop (CPLX/DivEu): srcA=T0(lower), srcB=T1(upper), srcC=Rn (psrcC). The EU
    // computes Z/C per Musashi + the {oldN,Z,oldV,C} CCR RMW; CHK2 raises EuFault{vec6}
    // on out-of-bounds C. reads+writes NZVC (preserve N/V); no int dst.
    val c2Cmp = {
      val u = DecodedUop()
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
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0
      // CHK2 vec-6 is a group-2 (format-$2) trap delivered execute-time via euFault:
      // it stacks the NEXT instruction's PC. faultPc is captured at ALLOC, so set
      // faultUsesNextPc NOW (mirrors CHK / the DIV0 path).
      u.faultUsesNextPc := True
      u.faultAddr   := pkt.pc; u.sswInstr := False; u.isRte := False; u.isCondTrap := False
      u.divSigned   := c2Ad; u.div64 := False; u.divIsRem := False   // divSigned reused = adReg
      u.isChk2      := c2IsChk2
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := False; u.indexScale := 0
      u.leaAddr     := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp       := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.firstOfInstr := False                                   // trailing (loads are first)
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
    ibrUop.valid         := pkt.valid
    ibrUop.pc            := pkt.pc
    ibrUop.nextPc        := nextPc
    ibrUop.op            := DecOp.BRANCH
    ibrUop.cluster       := Cluster.INT
    ibrUop.size          := Size.LONG
    ibrUop.memOp         := MemOp.NONE
    ibrUop.srcAReg       := srcEa.base; ibrUop.srcAValid := srcEa.baseValid   // EA base An
    ibrUop.srcBReg       := 0;          ibrUop.srcBValid := False
    ibrUop.srcCReg       := 0;          ibrUop.srcCValid := False
    ibrUop.dstReg        := 0;          ibrUop.dstValid  := False
    ibrUop.useImm        := True
    ibrUop.imm           := Mux(srcEa.pcRel, ctrlPcRelAddr, srcEa.disp)
    ibrUop.readsNzvc     := False; ibrUop.readsX := False
    ibrUop.writesNzvc    := False; ibrUop.writesX := False
    ibrUop.isBranch      := True;  ibrUop.ibranch := True
    ibrUop.stkPush       := False; ibrUop.anInc := 0; ibrUop.ccrRestore := False; ibrUop.toCcr := False    // JMP: no An postinc (JSR/RTS override)
    ibrUop.eaAuto        := EaAuto.NONE; ibrUop.eaDelta := 0
    ibrUop.cond          := 0;     ibrUop.branchDisp := 0
    ibrUop.unimplemented := False
    ibrUop.faulted       := False; ibrUop.faultVector := 0; ibrUop.isRte := False
    ibrUop.faultUsesNextPc := False
    ibrUop.faultAddr     := pkt.pc; ibrUop.sswInstr := False; ibrUop.isCondTrap := False
    ibrUop.divSigned     := False; ibrUop.div64 := False; ibrUop.divIsRem := False
    ibrUop.isChk2        := False
    ibrUop.shiftOp := 0; ibrUop.shiftDir := False; ibrUop.isMovea := False; ibrUop.isScc := False; ibrUop.isDbcc := False; ibrUop.extByte := False; ibrUop.bitOp := 0; ibrUop.bfOp := 0; ibrUop.bfDynamic := False; ibrUop.bfMem := False; ibrUop.bcdSub := False
    ibrUop.indexLong := False; ibrUop.indexScale := 0
    ibrUop.leaAddr := False; ibrUop.fromCcr := False; ibrUop.fromSr := False; ibrUop.needsSupervisor := False; ibrUop.keepCommit := False
    ibrUop.sysOp := False; ibrUop.sysKind := SysKind.NONE; ibrUop.sysReadDir := False
    // JMP is a single µop (its own first); JSR's ibranch is the TRAILING µop (the push
    // is first), so firstOfInstr is False for JSR.
    ibrUop.firstOfInstr  := !isJsrOp

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
              keepCommit: Bool = False): DecodedUop = {
      val u = DecodedUop()
      u.valid := pkt.valid; u.pc := pkt.pc; u.nextPc := nextPc
      u.op := op; u.cluster := cluster; u.size := size; u.memOp := memOp
      u.srcAReg := srcAReg; u.srcAValid := srcAValid
      u.srcBReg := srcBReg; u.srcBValid := srcBValid
      u.srcCReg := 0; u.srcCValid := False
      u.dstReg := dstReg;  u.dstValid := dstValid
      u.useImm := useImm; u.imm := imm
      u.readsNzvc := False; u.readsX := False; u.writesNzvc := writesNzvc; u.writesX := writesX
      u.isBranch := isBranch; u.ibranch := ibranch; u.stkPush := stkPush; u.anInc := anInc
      u.eaAuto := eaAuto; u.eaDelta := eaDelta
      u.ccrRestore := ccrRestore; u.toCcr := False
      u.cond := cond; u.branchDisp := branchDisp
      u.unimplemented := False
      u.faulted := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.faultAddr := pkt.pc; u.sswInstr := False; u.isRte := False; u.isCondTrap := False
      u.divSigned := False; u.div64 := False; u.divIsRem := divIsRem
      u.isChk2 := False
      u.shiftOp := 0; u.shiftDir := False; u.isMovea := False; u.isScc := False; u.isDbcc := False; u.extByte := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.bcdSub := False
      u.indexLong := False; u.indexScale := 0
      u.leaAddr := False; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := keepCommit
      u.sysOp := False; u.sysKind := SysKind.NONE; u.sysReadDir := False
      u.firstOfInstr := first
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
    // anInc=inc). Used by RTS/RTR.
    def retBranchUop(tgt: Int, an: Int, inc: Int): DecodedUop =
      mkUop(isBranch = True, ibranch = True,
            srcAReg = U(tgt, 5 bits), srcAValid = True,   // target = tgt + 0
            useImm  = True, imm = B(0, 32 bits),
            srcBReg = U(an, 5 bits),  srcBValid = True,    // postinc base = An
            dstReg  = U(an, 5 bits),  dstValid = True,     // new A7 = A7 + inc
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
    val linkDisp = pkt.words(1).asSInt.resize(32)                    // sext(disp16)
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
    val rtdDisp   = pkt.words(1).asSInt.resize(32)
    val rtdDealloc= (S(4, 32 bits) + rtdDisp).asBits          // 4 + disp16
    val rtdLoad   = popUop(A7, disp = 0, dst = T0, first = True)
    val rtdA7     = addUop(U(A7, 5 bits), rtdDealloc, U(A7, 5 bits), first = False, drop = True)
    val rtdBranch = mkUop(isBranch = True, ibranch = True,
                          srcAReg = U(T0, 5 bits), srcAValid = True,   // target = T0 + 0
                          useImm  = True, imm = B(0, 32 bits), first = False)

    // LINK An,#disp16 — [stkPush store dst=An, push old An] + [A7 := A7+disp (drop)]
    //                   + [An := A7-disp (kept)].
    // µop0: a stkPush whose base/dst is A7 (predecrement A7 := A7-4) but whose store DATA
    // is the OLD An (srcB) — the LsEu data0 mux selects the register when srcBValid.
    val linkPush = mkUop(cluster = Cluster.LS, memOp = MemOp.STORE, stkPush = True,
                         srcAReg = U(A7, 5 bits), srcAValid = True,   // base A7 (addr = A7-4)
                         srcBReg = linkAn,        srcBValid = True,    // store data = old An
                         dstReg  = U(A7, 5 bits), dstValid  = True,    // A7 := A7-4
                         first = True)
    val linkA7  = addUop(U(A7, 5 bits), linkDisp.asBits, U(A7, 5 bits), first = False, drop = True)
    val linkAnU = addUop(U(A7, 5 bits), negDisp,         linkAn,        first = False, drop = False)

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
      u.isBranch    := False; u.ibranch := False; u.stkPush := False; u.anInc := 0
      u.cond        := 0; u.branchDisp := 0
      u.unimplemented := False
      u.faulted     := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.faultAddr   := pkt.pc; u.sswInstr := False; u.isRte := False; u.isCondTrap := False
      u.divSigned   := False; u.div64 := False; u.divIsRem := False
      u.isChk2      := False
      u.eaAuto      := EaAuto.NONE; u.eaDelta := 0
      u.ccrRestore  := False; u.toCcr := False
      u.shiftOp     := 0; u.shiftDir := False; u.bcdSub := False; u.bitOp := 0; u.bfOp := 0; u.bfDynamic := False; u.bfMem := False; u.extByte := False
      u.isMovea     := False; u.isScc := False; u.isDbcc := False
      u.indexLong   := srcEa.indexLong; u.indexScale := srcEa.indexScale
      u.leaAddr     := True; u.fromCcr := False; u.fromSr := False; u.needsSupervisor := False; u.keepCommit := False
      u.sysOp       := False; u.sysKind := SysKind.NONE; u.sysReadDir := False   // (Track D fields; LEA is not a sysOp)
      u.firstOfInstr := leaFirst
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
    } elsewhen(isLinkOp) {
      // LINK -> [stkPush store dst=An, push old An] + [A7 := A7+disp (drop)] + [An := A7-disp (kept)].
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
      // the store (its int dst), so no extra µop.
      out.count   := 1
      out.uops(0) := stUop
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
    } otherwise {
      out.count   := 1
      out.uops(0) := opUop
      out.uops(1) := opUop
    }
    out
  }
}
