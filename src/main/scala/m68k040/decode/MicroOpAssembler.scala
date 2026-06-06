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

    // ── Operand classification ───────────────────────────────────────────────
    val srcIsReg = (srcEa.klass === EaClass.DATAREG) || (srcEa.klass === EaClass.ADDRREG)
    val srcIsMem = (srcEa.klass === EaClass.MEMSIMPLE)
    val srcEaOk  = srcIsReg || (srcEa.klass === EaClass.IMM) || srcIsMem
    val dstEaOk  = (dstEa.klass === EaClass.DATAREG) || (dstEa.klass === EaClass.ADDRREG)
    val usesSrcEa = (spec.srcA.kind === OperandKind.EASRC) || (spec.srcB.kind === OperandKind.EASRC)
    val usesDstEa = (spec.dst.kind === OperandKind.EADST)

    // The op consumes a memSimple SOURCE EA -> crack a leading load µop into T0,
    // and the op reads T0 in the EASRC slot.
    val crackLoad = usesSrcEa && srcIsMem

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
    opUop.isBranch      := spec.isBranch; opUop.ibranch := False; opUop.stkPush := False; opUop.anInc := 0; opUop.ccrRestore := False; opUop.cond := spec.cond
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
    // CHK / DIV are group-2 traps (CHK vec6, DIV0 vec5) delivered execute-time via
    // euFault -> format-$2: they stack the NEXT instruction's PC (the 040 group-2
    // frame's PC = pc+len). The fault is conditional (set at execute), but faultPc is
    // captured at ALLOC, so faultUsesNextPc must be set NOW for the CPLX ops.
    when(spec.op === DecOp.CHK || spec.op === DecOp.DIV) {
      opUop.faultUsesNextPc := True
    }
    // The op µop is the FIRST µop of its instruction EXCEPT when it is the trailing
    // op of a 2-µop memSimple-source crack ([load, op]) — i.e. when crackLoad. (For
    // every other path opUop is uops(0), the macro-instruction boundary.)
    opUop.firstOfInstr  := !crackLoad

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
          when(srcIsMem) { opUop.dstReg := U(T0, 5 bits) } .otherwise { opUop.dstReg := srcEa.reg }
          opUop.dstValid := True
        }
      }
      is(OperandKind.EADST) { opUop.dstReg := dstEa.reg; opUop.dstValid := True }
      default {}
    }
    when(spec.dst.kind =/= OperandKind.NONE && spec.dstWrites) { opUop.dstValid := True }
    when(spec.dst.kind =/= OperandKind.NONE && !spec.dstWrites) { opUop.dstValid := False } // CMP/CMPA

    // MOVE: NZVC only if the destination EA is a data register.
    when(spec.writesNzvcIfDataDst) { opUop.writesNzvc := (dstEa.klass === EaClass.DATAREG) }

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
    val pcRelAddr = (pkt.pc + U(2, 32 bits) + srcEa.disp.asUInt).asBits
    ldUop.imm           := Mux(srcEa.pcRel, pcRelAddr, srcEa.disp)
    ldUop.readsNzvc     := False; ldUop.readsX := False
    ldUop.writesNzvc    := False; ldUop.writesX := False
    ldUop.isBranch      := False; ldUop.ibranch := False; ldUop.stkPush := False; ldUop.anInc := 0; ldUop.ccrRestore := False; ldUop.cond := 0
    ldUop.branchDisp    := 0
    ldUop.unimplemented := False
    ldUop.faulted       := False; ldUop.faultVector := 0; ldUop.isRte := False
    ldUop.faultUsesNextPc := False
    ldUop.faultAddr     := pkt.pc; ldUop.sswInstr := False; ldUop.isTrapv := False
    ldUop.divSigned     := False; ldUop.div64 := False; ldUop.divIsRem := False
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
    stUop.isBranch      := False; stUop.ibranch := False; stUop.stkPush := False; stUop.anInc := 0; stUop.ccrRestore := False; stUop.cond := 0
    stUop.branchDisp    := 0
    stUop.unimplemented := False
    stUop.faulted       := False; stUop.faultVector := 0; stUop.isRte := False
    stUop.faultUsesNextPc := False
    stUop.faultAddr     := pkt.pc; stUop.sswInstr := False; stUop.isTrapv := False
    stUop.divSigned     := False; stUop.div64 := False; stUop.divIsRem := False
    stUop.firstOfInstr  := True    // a single STORE µop is its own first µop

    // ── unimplemented gating (folded into opUop, last-wins) ────────────────────
    // Defer: non-simple, illegal op, a USED src EA that is neither reg/imm nor a
    // crackable memSimple, or a USED dst EA that is not a register AND not a
    // crackable MOVE store (mem-to-mem MOVE and RMW-to-mem stay unimplemented).
    // `bad` also disables cracking.
    val dstOk = dstEaOk || crackStore
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
    val bad = !isRteOp && !isTrapOp && !isTrapvOp && !isDivLOp && !isMulLOp && !isJmpOp && !isJsrOp &&
              !isRtsBad && !isRtrBad &&
              (!pkt.simple || spec.illegal || (usesSrcEa && !srcEaOk) || (usesDstEa && !dstOk))
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
    divlUop.isBranch      := False; divlUop.ibranch := False; divlUop.stkPush := False; divlUop.anInc := 0; divlUop.ccrRestore := False; divlUop.cond := 0; divlUop.branchDisp := 0
    divlUop.unimplemented := False
    divlUop.faulted       := False; divlUop.faultVector := 0; divlUop.isRte := False
    divlUop.faultUsesNextPc := True            // DIV0 stacks nextPc (group-2 format-$2)
    divlUop.faultAddr     := pkt.pc; divlUop.sswInstr := False; divlUop.isTrapv := False
    divlUop.divSigned     := divlSigned; divlUop.div64 := divl64; divlUop.divIsRem := False
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
    divremUop.isBranch      := False; divremUop.ibranch := False; divremUop.stkPush := False; divremUop.anInc := 0; divremUop.ccrRestore := False; divremUop.cond := 0; divremUop.branchDisp := 0
    divremUop.unimplemented := False
    divremUop.faulted       := False; divremUop.faultVector := 0; divremUop.isRte := False
    divremUop.faultUsesNextPc := False
    divremUop.faultAddr     := pkt.pc; divremUop.sswInstr := False; divremUop.isTrapv := False
    divremUop.divSigned     := divlSigned; divremUop.div64 := divl64; divremUop.divIsRem := True
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
    mullUop.isBranch      := False; mullUop.ibranch := False; mullUop.stkPush := False; mullUop.anInc := 0; mullUop.ccrRestore := False; mullUop.cond := 0; mullUop.branchDisp := 0
    mullUop.unimplemented := False
    mullUop.faulted       := False; mullUop.faultVector := 0; mullUop.isRte := False
    mullUop.faultUsesNextPc := False
    mullUop.faultAddr     := pkt.pc; mullUop.sswInstr := False; mullUop.isTrapv := False
    mullUop.divSigned     := mullSigned; mullUop.div64 := mull64; mullUop.divIsRem := False
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
    mulhiUop.isBranch      := False; mulhiUop.ibranch := False; mulhiUop.stkPush := False; mulhiUop.anInc := 0; mulhiUop.ccrRestore := False; mulhiUop.cond := 0; mulhiUop.branchDisp := 0
    mulhiUop.unimplemented := False
    mulhiUop.faulted       := False; mulhiUop.faultVector := 0; mulhiUop.isRte := False
    mulhiUop.faultUsesNextPc := False
    mulhiUop.faultAddr     := pkt.pc; mulhiUop.sswInstr := False; mulhiUop.isTrapv := False
    mulhiUop.divSigned     := mullSigned; mulhiUop.div64 := mull64; mulhiUop.divIsRem := False
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
    ibrUop.stkPush       := False; ibrUop.anInc := 0; ibrUop.ccrRestore := False    // JMP: no An postinc (JSR/RTS override)
    ibrUop.cond          := 0;     ibrUop.branchDisp := 0
    ibrUop.unimplemented := False
    ibrUop.faulted       := False; ibrUop.faultVector := 0; ibrUop.isRte := False
    ibrUop.faultUsesNextPc := False
    ibrUop.faultAddr     := pkt.pc; ibrUop.sswInstr := False; ibrUop.isTrapv := False
    ibrUop.divSigned     := False; ibrUop.div64 := False; ibrUop.divIsRem := False
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
              writesNzvc: Bool = False, writesX: Bool = False): DecodedUop = {
      val u = DecodedUop()
      u.valid := pkt.valid; u.pc := pkt.pc; u.nextPc := nextPc
      u.op := DecOp.MOVE; u.cluster := cluster; u.size := size; u.memOp := memOp
      u.srcAReg := srcAReg; u.srcAValid := srcAValid
      u.srcBReg := srcBReg; u.srcBValid := srcBValid
      u.srcCReg := 0; u.srcCValid := False
      u.dstReg := dstReg;  u.dstValid := dstValid
      u.useImm := useImm; u.imm := imm
      u.readsNzvc := False; u.readsX := False; u.writesNzvc := writesNzvc; u.writesX := writesX
      u.isBranch := isBranch; u.ibranch := ibranch; u.stkPush := stkPush; u.anInc := anInc
      u.ccrRestore := ccrRestore
      u.cond := cond; u.branchDisp := branchDisp
      u.unimplemented := False
      u.faulted := False; u.faultVector := 0; u.faultUsesNextPc := False
      u.faultAddr := pkt.pc; u.sswInstr := False; u.isRte := False; u.isTrapv := False
      u.divSigned := False; u.div64 := False; u.divIsRem := False
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
    } elsewhen(crackStore) {
      out.count   := 1
      out.uops(0) := stUop
      out.uops(1) := stUop
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
