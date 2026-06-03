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

    // ── stUop = the STORE (used only when crackStore) ──────────────────────────
    // Address = dst base An (psrcA) + dst disp(imm); data = the MOVE source register
    // (srcB). No int dst, no flags (MOVE to memory writes no NZVC).
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
    stUop.writesNzvc    := False; stUop.writesX := False
    stUop.isBranch      := False; stUop.cond := 0
    stUop.branchDisp    := 0
    stUop.unimplemented := False

    // ── unimplemented gating (folded into opUop, last-wins) ────────────────────
    // Defer: non-simple, illegal op, a USED src EA that is neither reg/imm nor a
    // crackable memSimple, or a USED dst EA that is not a register AND not a
    // crackable MOVE store (mem-to-mem MOVE and RMW-to-mem stay unimplemented).
    // `bad` also disables cracking.
    val dstOk = dstEaOk || crackStore
    val bad = !pkt.simple || spec.illegal || (usesSrcEa && !srcEaOk) || (usesDstEa && !dstOk)
    when(bad) {
      opUop.op            := DecOp.ILLEGAL
      opUop.cluster       := Cluster.INT
      opUop.memOp         := MemOp.NONE
      opUop.unimplemented := True
      opUop.dstValid := False; opUop.srcAValid := False; opUop.srcBValid := False
      opUop.writesNzvc := False; opUop.writesX := False; opUop.isBranch := False
    }

    // ── Sequence selection (each slot driven exactly once) ─────────────────────
    // bad        -> [op] (count 1, op carries the illegal override)
    // crackStore -> [store] (count 1)
    // crackLoad  -> [load, op] (count 2)
    // else       -> [op] (count 1)
    when(bad) {
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
