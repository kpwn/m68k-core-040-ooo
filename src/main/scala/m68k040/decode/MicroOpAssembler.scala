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
    val uops  = Vec(DecodedUop(), 2)
    val count = UInt(2 bits)         // 1 or 2 µops valid (uops(0) always, uops(1) iff count===2)
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
    opUop.dstReg        := 0; opUop.dstValid  := False
    opUop.useImm        := False; opUop.imm    := 0
    opUop.readsNzvc     := spec.readsNzvc; opUop.readsX := spec.readsX
    opUop.writesNzvc    := spec.writesNzvc; opUop.writesX := spec.writesX
    opUop.isBranch      := spec.isBranch; opUop.cond := spec.cond
    opUop.branchDisp    := 0
    opUop.unimplemented := False
    opUop.faulted       := False
    opUop.faultVector   := 0
    opUop.faultPc       := pkt.pc   // default: faulting instr PC (TRAP/TRAPV override -> nextPc)
    opUop.faultAddr     := pkt.pc
    opUop.sswInstr      := False
    opUop.isRte         := False
    opUop.isTrapv       := False

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
    ldUop.dstReg        := U(T0, 5 bits); ldUop.dstValid := True
    ldUop.useImm        := True
    val pcRelAddr = (pkt.pc + U(2, 32 bits) + srcEa.disp.asUInt).asBits
    ldUop.imm           := Mux(srcEa.pcRel, pcRelAddr, srcEa.disp)
    ldUop.readsNzvc     := False; ldUop.readsX := False
    ldUop.writesNzvc    := False; ldUop.writesX := False
    ldUop.isBranch      := False; ldUop.cond := 0
    ldUop.branchDisp    := 0
    ldUop.unimplemented := False
    ldUop.faulted       := False; ldUop.faultVector := 0; ldUop.isRte := False
    ldUop.faultPc       := pkt.pc
    ldUop.faultAddr     := pkt.pc; ldUop.sswInstr := False; ldUop.isTrapv := False

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
    stUop.dstReg        := 0;          stUop.dstValid  := False
    stUop.useImm        := True
    val stPcRelAddr = (pkt.pc + U(2, 32 bits) + dstEa.disp.asUInt).asBits
    stUop.imm           := Mux(dstEa.pcRel, stPcRelAddr, dstEa.disp)
    stUop.readsNzvc     := False; stUop.readsX := False
    stUop.writesNzvc    := True;  stUop.writesX := False   // MOVE to memory sets NZVC
    stUop.isBranch      := False; stUop.cond := 0
    stUop.branchDisp    := 0
    stUop.unimplemented := False
    stUop.faulted       := False; stUop.faultVector := 0; stUop.isRte := False
    stUop.faultPc       := pkt.pc
    stUop.faultAddr     := pkt.pc; stUop.sswInstr := False; stUop.isTrapv := False

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
    val bad = !isRteOp && !isTrapOp && !isTrapvOp &&
              (!pkt.simple || spec.illegal || (usesSrcEa && !srcEaOk) || (usesDstEa && !dstOk))
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
      opUop.faultPc       := nextPc
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
      opUop.cond          := 0
      opUop.branchDisp    := 0
      opUop.readsNzvc     := True
      opUop.isTrapv       := True
      opUop.faultPc       := nextPc
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

    // ── Sequence selection (each slot driven exactly once) ─────────────────────
    // bad        -> [op] (count 1, op carries the illegal override)
    // crackStore -> [store] (count 1)
    // crackLoad  -> [load, op] (count 2)
    // else       -> [op] (count 1)
    when(pkt.fault) {
      // Fetch fault dominates: a single faulted (vector-2) delivery µop.
      out.count   := 1
      out.uops(0) := opUop
      out.uops(1) := opUop
    } elsewhen(bad) {
      out.count   := 1
      out.uops(0) := opUop
      out.uops(1) := opUop
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
