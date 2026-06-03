package m68k040.decode

import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.lib._

/** Combines the EA-agnostic OperationDecoder with the opcode-agnostic EaDecoder
  * into one DecodedUop. This slice: register/immediate operands only, exactly one
  * µop per instruction (no temps, no memory). Any operand that resolves to a
  * memory/illegal EA -> `unimplemented` (memory cracking is a later slice). */
object MicroOpAssembler {
  def assemble(pkt: DecodePacket): DecodedUop = {
    val uop = DecodedUop()
    val op  = pkt.words(0)
    val spec = OperationDecoder.decode(op)

    // EA fields. Source EA = op(5..0). Dest EA (MOVE) = dstMode(8..6) ## dstReg(11..9).
    val srcEa = EaDecoder.decode(op(5 downto 0), spec.size, pkt.words)
    val dstEaField = op(8 downto 6) ## op(11 downto 9)
    val dstEa = EaDecoder.decode(dstEaField, spec.size, pkt.words)

    // ---- defaults ----
    uop.valid         := pkt.valid
    uop.pc            := pkt.pc
    uop.op            := spec.op
    uop.cluster       := spec.cluster
    uop.size          := spec.size
    uop.srcAReg       := 0; uop.srcAValid := False
    uop.srcBReg       := 0; uop.srcBValid := False
    uop.dstReg        := 0; uop.dstValid  := False
    uop.useImm        := False; uop.imm    := 0
    uop.readsNzvc     := spec.readsNzvc; uop.readsX := spec.readsX
    uop.writesNzvc    := spec.writesNzvc; uop.writesX := spec.writesX
    uop.isBranch      := spec.isBranch; uop.cond := spec.cond
    uop.branchDisp    := 0
    uop.unimplemented := False

    // A µop is reg/imm-decodable only if every active EA-sourced operand is a
    // register or immediate. Memory/illegal EAs (or illegal op, or !simple) ->
    // unimplemented (cleared fields above keep the bundle well-formed).
    val srcEaOk = (srcEa.klass === EaClass.DATAREG) || (srcEa.klass === EaClass.ADDRREG) || (srcEa.klass === EaClass.IMM)
    val dstEaOk = (dstEa.klass === EaClass.DATAREG) || (dstEa.klass === EaClass.ADDRREG)
    val usesSrcEa = (spec.srcA.kind === OperandKind.EASRC) || (spec.srcB.kind === OperandKind.EASRC)
    val usesDstEa = (spec.dst.kind === OperandKind.EADST)

    // ---- place each operand slot (inlined per-slot; identical logic) ----
    // --- srcA slot ---
    switch(spec.srcA.kind) {
      is(OperandKind.REGFIELD) {
        when(spec.srcA.isAddr) { uop.srcAReg := (U(8, 4 bits) + op(11 downto 9).asUInt).resized }
          .otherwise { uop.srcAReg := op(11 downto 9).asUInt.resize(4) }
        uop.srcAValid := True
      }
      is(OperandKind.EASRC) {
        when(srcEa.klass === EaClass.IMM) { uop.useImm := True; uop.imm := srcEa.imm }
          .otherwise { uop.srcAReg := srcEa.reg; uop.srcAValid := True }
      }
      is(OperandKind.EADST) { uop.srcAReg := dstEa.reg; uop.srcAValid := True }
      is(OperandKind.IMMQ)  { uop.useImm := True; uop.imm := op(7 downto 0).asSInt.resize(32).asBits }
      default {}
    }

    // --- srcB slot ---
    switch(spec.srcB.kind) {
      is(OperandKind.REGFIELD) {
        when(spec.srcB.isAddr) { uop.srcBReg := (U(8, 4 bits) + op(11 downto 9).asUInt).resized }
          .otherwise { uop.srcBReg := op(11 downto 9).asUInt.resize(4) }
        uop.srcBValid := True
      }
      is(OperandKind.EASRC) {
        when(srcEa.klass === EaClass.IMM) { uop.useImm := True; uop.imm := srcEa.imm }
          .otherwise { uop.srcBReg := srcEa.reg; uop.srcBValid := True }
      }
      is(OperandKind.EADST) { uop.srcBReg := dstEa.reg; uop.srcBValid := True }
      is(OperandKind.IMMQ)  { uop.useImm := True; uop.imm := op(7 downto 0).asSInt.resize(32).asBits }
      default {}
    }

    // --- dst slot ---
    switch(spec.dst.kind) {
      is(OperandKind.REGFIELD) {
        when(spec.dst.isAddr) { uop.dstReg := (U(8, 4 bits) + op(11 downto 9).asUInt).resized }
          .otherwise { uop.dstReg := op(11 downto 9).asUInt.resize(4) }
        uop.dstValid := True
      }
      is(OperandKind.EASRC) {
        when(srcEa.klass =/= EaClass.IMM) { uop.dstReg := srcEa.reg; uop.dstValid := True }
      }
      is(OperandKind.EADST) { uop.dstReg := dstEa.reg; uop.dstValid := True }
      default {}
    }
    when(spec.dst.kind =/= OperandKind.NONE && spec.dstWrites) { uop.dstValid := True }
    when(spec.dst.kind =/= OperandKind.NONE && !spec.dstWrites) { uop.dstValid := False } // CMP/CMPA

    // MOVE: NZVC only if the destination EA is a data register.
    when(spec.writesNzvcIfDataDst) { uop.writesNzvc := (dstEa.klass === EaClass.DATAREG) }

    // Branch displacement (byte / word / long), reproducing the simple-decode rule.
    when(spec.isBranch) {
      val disp8 = op(7 downto 0)
      when(disp8 === 0x00) { uop.branchDisp := pkt.words(1).asSInt.resize(32).asBits }
        .elsewhen(disp8 === M"11111111") { uop.branchDisp := pkt.words(1) ## pkt.words(2) }
        .otherwise { uop.branchDisp := disp8.asSInt.resize(32).asBits }
    }

    // ---- unimplemented gating ----
    when(!pkt.simple || spec.illegal ||
         (usesSrcEa && !srcEaOk) || (usesDstEa && !dstEaOk)) {
      uop.op            := DecOp.ILLEGAL
      uop.unimplemented := True
      uop.dstValid := False; uop.srcAValid := False; uop.srcBValid := False
      uop.writesNzvc := False; uop.writesX := False; uop.isBranch := False
    }
    uop
  }
}
