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
    val eaIsDst       = (spec.dst.kind === OperandKind.EASRC)
    val rmwOpInScope  = !(spec.op === DecOp.SWAP || spec.op === DecOp.EXT || spec.op === DecOp.TAS)
    val memDest       = srcIsMem && eaIsDst && rmwOpInScope
    val crackClr      = memDest && (spec.op === DecOp.CLR)
    val crackLoadOnly = memDest && !spec.dstWrites                       // TST / CMPI / CMP-mem (no store)
    val crackRmw      = memDest && spec.dstWrites && !crackClr           // load-op-store
    // The generic memSimple-SOURCE load crack: a TRUE source EA (NOT a mem destination).
    val crackLoad = usesSrcEa && srcIsMem && !isAddqSubq && !isLine4Unary && !memDest

    // MOVE reg -> memSimple destination -> a single STORE µop (data = the register
    // source). Mem-to-mem (source also memSimple) is deferred. RMW (ALU op with a
    // memory dst) is deferred (only MOVE stores). The store data is the MOVE source
    // register (which OperationDecoder placed in the srcB EASRC slot).
    val dstIsMem  = (dstEa.klass === EaClass.MEMSIMPLE)
    val crackStore = (spec.op === DecOp.MOVE) && usesDstEa && dstIsMem && srcIsReg

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
    opUop.branchDisp    := 0
    opUop.unimplemented := False
    opUop.faulted       := False
    opUop.faultVector   := 0
    opUop.faultUsesNextPc := False  // default: stack the faulting instr PC (pc); TRAP/TRAPV -> nextPc
    opUop.faultAddr     := pkt.pc
    opUop.sswInstr      := False
    opUop.isRte         := False
    opUop.isTrapv       := False
    opUop.divSigned     := spec.divSigned
    opUop.div64         := spec.div64
    opUop.divIsRem      := False
    opUop.shiftOp       := spec.shiftOp
    opUop.shiftDir      := spec.shiftDir
    opUop.extByte       := spec.extByte
    opUop.isMovea       := False
    opUop.isScc         := False; opUop.isDbcc := False
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
    ldUop.size          := spec.size
    ldUop.memOp         := MemOp.LOAD
    ldUop.srcAReg       := srcEa.base; ldUop.srcAValid := srcEa.baseValid
    ldUop.srcBReg       := 0;          ldUop.srcBValid := False
    ldUop.srcCReg       := 0;          ldUop.srcCValid := False
    ldUop.dstReg        := U(T0, 5 bits); ldUop.dstValid := True
    ldUop.useImm        := True
    // disp = rmwEaDisp (immEa for a line-0 immediate mem-dest, else srcEa). A (d16,PC)
    // source folds pc into the absolute disp (never a line-0 immediate -> srcEa.disp).
    val pcRelAddr = (pkt.pc + U(2, 32 bits) + srcEa.disp.asUInt).asBits
    ldUop.imm           := Mux(srcEa.pcRel, pcRelAddr, rmwEaDisp)
    ldUop.readsNzvc     := False; ldUop.readsX := False
    ldUop.writesNzvc    := False; ldUop.writesX := False
    ldUop.isBranch      := False; ldUop.ibranch := False; ldUop.stkPush := False; ldUop.anInc := 0; ldUop.ccrRestore := False; ldUop.toCcr := False; ldUop.cond := 0
    ldUop.branchDisp    := 0
    ldUop.unimplemented := False
    ldUop.faulted       := False; ldUop.faultVector := 0; ldUop.isRte := False
    ldUop.faultUsesNextPc := False
    ldUop.faultAddr     := pkt.pc; ldUop.sswInstr := False; ldUop.isTrapv := False
    ldUop.divSigned     := False; ldUop.div64 := False; ldUop.divIsRem := False
    ldUop.shiftOp := 0; ldUop.shiftDir := False; ldUop.isMovea := False; ldUop.isScc := False; ldUop.isDbcc := False; ldUop.extByte := False
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
    stUop.srcBReg       := srcEa.reg;  stUop.srcBValid := True            // store data
    stUop.srcCReg       := 0;          stUop.srcCValid := False
    stUop.dstReg        := 0;          stUop.dstValid  := False
    stUop.useImm        := True
    val stPcRelAddr = (pkt.pc + U(2, 32 bits) + dstEa.disp.asUInt).asBits
    stUop.imm           := Mux(dstEa.pcRel, stPcRelAddr, dstEa.disp)
    stUop.readsNzvc     := False; stUop.readsX := False
    stUop.writesNzvc    := True;  stUop.writesX := False   // MOVE to memory sets NZVC
    stUop.isBranch      := False; stUop.ibranch := False; stUop.stkPush := False; stUop.anInc := 0; stUop.ccrRestore := False; stUop.toCcr := False; stUop.cond := 0
    stUop.branchDisp    := 0
    stUop.unimplemented := False
    stUop.faulted       := False; stUop.faultVector := 0; stUop.isRte := False
    stUop.faultUsesNextPc := False
    stUop.faultAddr     := pkt.pc; stUop.sswInstr := False; stUop.isTrapv := False
    stUop.divSigned     := False; stUop.div64 := False; stUop.divIsRem := False
    stUop.shiftOp := 0; stUop.shiftDir := False; stUop.isMovea := False; stUop.isScc := False; stUop.isDbcc := False; stUop.extByte := False
    stUop.firstOfInstr  := True    // a single STORE µop is its own first µop

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
    rmwStUop.size          := spec.size
    rmwStUop.memOp         := MemOp.STORE
    rmwStUop.srcAReg       := srcEa.base; rmwStUop.srcAValid := srcEa.baseValid
    rmwStUop.srcBReg       := U(T1, 5 bits); rmwStUop.srcBValid := True       // store data = T1
    rmwStUop.srcCReg       := 0;          rmwStUop.srcCValid := False
    rmwStUop.dstReg        := 0;          rmwStUop.dstValid  := False
    rmwStUop.useImm        := True
    // Same EA as the load (MEMSIMPLE recompute): rmwEaDisp (immEa for a line-0 immediate).
    val rmwStPcRelAddr = (pkt.pc + U(2, 32 bits) + srcEa.disp.asUInt).asBits
    rmwStUop.imm           := Mux(srcEa.pcRel, rmwStPcRelAddr, rmwEaDisp)
    rmwStUop.readsNzvc     := False; rmwStUop.readsX := False
    rmwStUop.writesNzvc    := False; rmwStUop.writesX := False   // the op µop owns the flags
    rmwStUop.isBranch      := False; rmwStUop.ibranch := False; rmwStUop.stkPush := False; rmwStUop.anInc := 0; rmwStUop.ccrRestore := False; rmwStUop.toCcr := False; rmwStUop.cond := 0
    rmwStUop.branchDisp    := 0
    rmwStUop.unimplemented := False
    rmwStUop.faulted       := False; rmwStUop.faultVector := 0; rmwStUop.isRte := False
    rmwStUop.faultUsesNextPc := False
    rmwStUop.faultAddr     := pkt.pc; rmwStUop.sswInstr := False; rmwStUop.isTrapv := False
    rmwStUop.divSigned     := False; rmwStUop.div64 := False; rmwStUop.divIsRem := False
    rmwStUop.shiftOp := 0; rmwStUop.shiftDir := False; rmwStUop.isMovea := False; rmwStUop.isScc := False; rmwStUop.isDbcc := False; rmwStUop.extByte := False
    rmwStUop.firstOfInstr  := False    // the trailing store of a cracked RMW

    // ── unimplemented gating (folded into opUop, last-wins) ────────────────────
    // Defer: non-simple, illegal op, a USED src EA that is neither reg/imm nor a
    // crackable memSimple, or a USED dst EA that is not a register AND not a
    // crackable MOVE store (mem-to-mem MOVE and RMW-to-mem stay unimplemented).
    // `bad` also disables cracking.
    val dstOk = dstEaOk || crackStore
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
    val aluRmwMemBad = isAluRmwOp && (srcEa.klass =/= EaClass.MEMSIMPLE)
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
    val lineImmBad = isLineImm && (srcEa.klass =/= EaClass.DATAREG) &&
                     (srcEa.klass =/= EaClass.MEMSIMPLE) && !isToCcr
    // ── Line-5 Scc / DBcc (0101 cccc 11 mmmrrr) ─────────────────────────────────
    // ss == 11 (op[7:6]). mode = op[5:3]. DBcc = mode 001 (+ disp16 word). Scc = any
    // other mode (a byte set on cond); in-scope = mode 000 (Dn). Memory Scc (mode>=2)
    // + TRAPcc (mode 7, reg 2/3/4) are deferred -> illegal. cccc = op[11:8] (the branch
    // EU's 16-condition field). rrr = op[2:0] (Dn for Scc / the DBcc counter Dn).
    val isLine5    = (op(15 downto 12) === B"4'h5")
    val ss5        = op(7 downto 6)
    val mode5      = op(5 downto 3)
    val cccc5      = op(11 downto 8)
    val rrr5       = op(2 downto 0).asUInt.resize(5)
    val isDbccOp   = isLine5 && (ss5 === 3) && (mode5 === 1)
    val isSccOp    = isLine5 && (ss5 === 3) && (mode5 === 0)          // Scc Dn (in scope)
    // A memory/TRAPcc line-5 ss==11 form (not DBcc mode 001, not Scc-Dn mode 000) is
    // deferred -> illegal (`sccMemBad`).
    val sccMemBad  = isLine5 && (ss5 === 3) && (mode5 =/= 0) && (mode5 =/= 1)
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
    // A JMP/JSR control EA is the in-scope MEMSIMPLE set (the EaDecoder already
    // classifies (An)/(d16,An)/(xxx)/(d16,PC) as MEMSIMPLE; indexed/predec/postinc are
    // MEMCOMPLEX, reg-direct DATAREG/ADDRREG, imm IMM). So a valid control EA == srcIsMem.
    val ctrlEaOk = srcIsMem
    // RTS (0x4E75) / RTR (0x4E77) are line-4 returns cracked below (NOT illegal).
    val isRtsBad = (op === B"16'h4E75")
    val isRtrBad = (op === B"16'h4E77")
    // LINK An,#disp16 (0100 1110 0101 0aaa) / UNLK An (0100 1110 0101 1aaa): line-4
    // stack-frame ops cracked below (NOT illegal). op[15:4]==0x4E5, op[3] selects.
    val isLinkOp = (op(15 downto 4) === B"12'h4E5") && !op(3)
    val isUnlkOp = (op(15 downto 4) === B"12'h4E5") &&  op(3)
    val bad = !isRteOp && !isTrapOp && !isTrapvOp && !isDivLOp && !isMulLOp && !isJmpOp && !isJsrOp &&
              !isRtsBad && !isRtrBad && !isSccOp && !isDbccOp && !isLinkOp && !isUnlkOp &&
              (!pkt.simple || spec.illegal || eorMemBad || lineImmBad || addqMemBad || sccMemBad ||
               line4UnaryMemBad || aluRmwMemBad ||
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
      // Branch-class trap-check µop: issues to the branch EU, reads NZVC(V). The EU
      // drives a trapvFault (vector 7) iff V=1; otherwise it retires as a no-op. It
      // writes no register and (like a branch) leaves CCR unchanged. TRAPV is not
      // restartable -> faultPc = nextPc (the stacked PC when it traps).
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
      // cond = F (1): branchEU `taken`=False -> mispredict stays False and the branch
      // nextPc = pc+2 (= TRAPV's nextPc) WITHOUT any isTrapv special-case on the
      // mispredict/nextPc outputs (keeps those off the critical completion->ROB arc).
      opUop.cond          := 1
      opUop.branchDisp    := 0
      opUop.readsNzvc     := True
      opUop.isTrapv       := True
      opUop.faultUsesNextPc := True
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
      opUop.isRte         := False; opUop.isTrapv := False; opUop.ibranch := False
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
      opUop.isRte         := False; opUop.isTrapv := False; opUop.ibranch := False
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
    divlUop.unimplemented := False
    divlUop.faulted       := False; divlUop.faultVector := 0; divlUop.isRte := False
    divlUop.faultUsesNextPc := True            // DIV0 stacks nextPc (group-2 format-$2)
    divlUop.faultAddr     := pkt.pc; divlUop.sswInstr := False; divlUop.isTrapv := False
    divlUop.divSigned     := divlSigned; divlUop.div64 := divl64; divlUop.divIsRem := False
    divlUop.shiftOp := 0; divlUop.shiftDir := False; divlUop.isMovea := False; divlUop.isScc := False; divlUop.isDbcc := False; divlUop.extByte := False
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
    divremUop.unimplemented := False
    divremUop.faulted       := False; divremUop.faultVector := 0; divremUop.isRte := False
    divremUop.faultUsesNextPc := False
    divremUop.faultAddr     := pkt.pc; divremUop.sswInstr := False; divremUop.isTrapv := False
    divremUop.divSigned     := divlSigned; divremUop.div64 := divl64; divremUop.divIsRem := True
    divremUop.shiftOp := 0; divremUop.shiftDir := False; divremUop.isMovea := False; divremUop.isScc := False; divremUop.isDbcc := False; divremUop.extByte := False
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
    mullUop.unimplemented := False
    mullUop.faulted       := False; mullUop.faultVector := 0; mullUop.isRte := False
    mullUop.faultUsesNextPc := False
    mullUop.faultAddr     := pkt.pc; mullUop.sswInstr := False; mullUop.isTrapv := False
    mullUop.divSigned     := mullSigned; mullUop.div64 := mull64; mullUop.divIsRem := False
    mullUop.shiftOp := 0; mullUop.shiftDir := False; mullUop.isMovea := False; mullUop.isScc := False; mullUop.isDbcc := False; mullUop.extByte := False
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
    mulhiUop.unimplemented := False
    mulhiUop.faulted       := False; mulhiUop.faultVector := 0; mulhiUop.isRte := False
    mulhiUop.faultUsesNextPc := False
    mulhiUop.faultAddr     := pkt.pc; mulhiUop.sswInstr := False; mulhiUop.isTrapv := False
    mulhiUop.divSigned     := mullSigned; mulhiUop.div64 := mull64; mulhiUop.divIsRem := False
    mulhiUop.shiftOp := 0; mulhiUop.shiftDir := False; mulhiUop.isMovea := False; mulhiUop.isScc := False; mulhiUop.isDbcc := False; mulhiUop.extByte := False
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
    ibrUop.cond          := 0;     ibrUop.branchDisp := 0
    ibrUop.unimplemented := False
    ibrUop.faulted       := False; ibrUop.faultVector := 0; ibrUop.isRte := False
    ibrUop.faultUsesNextPc := False
    ibrUop.faultAddr     := pkt.pc; ibrUop.sswInstr := False; ibrUop.isTrapv := False
    ibrUop.divSigned     := False; ibrUop.div64 := False; ibrUop.divIsRem := False
    ibrUop.shiftOp := 0; ibrUop.shiftDir := False; ibrUop.isMovea := False; ibrUop.isScc := False; ibrUop.isDbcc := False; ibrUop.extByte := False
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
              op: DecOp.C = DecOp.MOVE, divIsRem: Bool = False): DecodedUop = {
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
      u.ccrRestore := ccrRestore; u.toCcr := False
      u.cond := cond; u.branchDisp := branchDisp
      u.unimplemented := False
      u.faulted := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.faultAddr := pkt.pc; u.sswInstr := False; u.isRte := False; u.isTrapv := False
      u.divSigned := False; u.div64 := False; u.divIsRem := divIsRem
      u.shiftOp := 0; u.shiftDir := False; u.isMovea := False; u.isScc := False; u.isDbcc := False; u.extByte := False
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
    val unlkAn   = mkUop(srcAReg = U(T0, 5 bits), srcAValid = True,        // MOVE T0 -> An
                         dstReg = linkAn, dstValid = True, first = False)

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
    // Default the 3rd µop slot (only RTR uses it) so every path drives uops(2) once.
    out.uops(2) := opUop
    when(pkt.fault) {
      // Fetch fault dominates: a single faulted (vector-2) delivery µop.
      out.count   := 1
      out.uops(0) := opUop
      out.uops(1) := opUop
    } elsewhen(bad) {
      out.count   := 1
      out.uops(0) := opUop
      out.uops(1) := opUop
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
    } elsewhen(crackStore) {
      out.count   := 1
      out.uops(0) := stUop
      out.uops(1) := stUop
    } elsewhen(crackRmw) {
      // mem-dest RMW -> [load.sz <ea> -> T0] [op (T0+Dn/#imm) -> T1 + flags] [store.sz T1 -> <ea>]
      out.count   := 3
      out.uops(0) := ldUop
      out.uops(1) := opUop
      out.uops(2) := rmwStUop
    } elsewhen(crackClr) {
      // CLR mem -> [CLR -> T1 (=0) + Z/N flags] [store.sz T1 -> <ea>] (NO load)
      out.count   := 2
      out.uops(0) := opUop
      out.uops(1) := rmwStUop
    } elsewhen(crackLoadOnly) {
      // TST / CMPI / CMP-mem -> [load.sz <ea> -> T0] [op (flags only)] (NO store)
      out.count   := 2
      out.uops(0) := ldUop
      out.uops(1) := opUop
    } elsewhen(crackLoad) {
      out.count   := 2
      out.uops(0) := ldUop
      out.uops(1) := opUop
    } otherwise {
      out.count   := 1
      out.uops(0) := opUop
      out.uops(1) := opUop
    }
    out
  }
}
