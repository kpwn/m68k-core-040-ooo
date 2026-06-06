package m68k040.decode

import m68k040.isa.{Cluster, Size}
import spinal.core._
import spinal.lib._

/** EA-agnostic operation decoder: opword -> OpSpec. Masked-pattern table grouped
  * by line nibble. Reproduces today's op set over reg/imm modes; memory/complex
  * forms are marked illegal (handled by later slices). */
object OperationDecoder {
  def decode(opword: Bits): OpSpec = {
    val o = OpSpec.illegalDefault()
    o.allowOverride

    val line = opword(15 downto 12)
    val dnField = OperandSrc(); dnField.kind := OperandKind.REGFIELD; dnField.isAddr := False
    val anField = OperandSrc(); anField.kind := OperandKind.REGFIELD; anField.isAddr := True
    val easrc   = OperandSrc(); easrc.kind := OperandKind.EASRC;  easrc.isAddr := False
    val eadst   = OperandSrc(); eadst.kind := OperandKind.EADST;  eadst.isAddr := False
    val immq    = OperandSrc(); immq.kind  := OperandKind.IMMQ;   immq.isAddr := False

    switch(line) {
      // ---- MOVEQ (0111 rrr0 dddddddd) ----
      is(0x7) {
        when(opword(8) === False) {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.LONG
          o.srcB := immq
          o.dst := dnField; o.dstWrites := True
          o.writesNzvc := True
        }
      }
      // ---- MOVE.B/.W/.L (00 ss ...) src EA = bits 5-0, dst EA = bits 11-6 ----
      is(0x1, 0x3, 0x2) {
        o.illegal := False
        o.op := DecOp.MOVE
        when(line === 0x1) { o.size := Size.BYTE }
          .elsewhen(line === 0x3) { o.size := Size.WORD }
          .otherwise { o.size := Size.LONG }
        o.srcB := easrc                       // source EA -> srcB (ALU MOVE result = src2)
        o.dst  := eadst; o.dstWrites := True   // dest EA
        o.writesNzvcIfDataDst := True          // NZVC only if dst is a data reg (assembler resolves)
      }
      // ---- CHK.W/CHK.L (0100 ddd 1 ss mmmrrr) ----
      // bit8=1 marks CHK in line 4; bit7 selects size (.W=1 / .L=0), bit6=0. The
      // checked register Dn is bits 11:9 (read as srcA); the bound is the EA (srcB).
      // CHK writes NO register and leaves CCR per the 68k rule (only N is meaningful,
      // set by the EU at execute; matched in lock-step). Routed to the CPLX (DivEu).
      is(0x4) {
        when(opword(8) && !opword(6)) {
          o.illegal := False
          o.op := DecOp.CHK
          o.cluster := Cluster.CPLX
          o.size := Mux(opword(7), Size.WORD, Size.LONG)
          o.srcA := dnField            // Dn (checked value)
          o.srcB := easrc              // bound (EA)
          o.dst.setNone(); o.dstWrites := False
          o.writesNzvc := True         // CHK sets N (1 if Dn<0, 0 if Dn>=0); Z=V=C=0
        }
      }
      // ---- Bcc / BSR / BRA (0110 cccc dddddddd) ----
      is(0x6) {
        o.illegal := False
        o.op := DecOp.BRANCH; o.isBranch := True
        o.cond := opword(11 downto 8)
        o.cluster := Cluster.INT
        o.readsNzvc := (opword(11 downto 8).asUInt >= 2)
      }
      // ---- OR/SUB/CMP/AND/ADD (1ooo ... ) ----
      is(0x8, 0x9, 0xB, 0xC, 0xD) {
        val opmode = opword(8 downto 6)
        val isRmw  = (opmode === 4 || opmode === 5 || opmode === 6)
        // DIVU.W (line 0x8 opmode 3) / DIVS.W (line 0x8 opmode 7): 32-bit dividend Dn
        // (bits 11:9) / 16-bit divisor EA -> Dn = {rem[31:16], q[15:0]}. BOTH DIVs are
        // line 8 (the OR group); BOTH MULs are line C (the AND group) -> MULU/MULS
        // (line 0xC opmode 3/7) stay illegal (separate slice).
        val isDivuW = (line === 0x8) && (opmode === 3)
        val isDivsW = (line === 0x8) && (opmode === 7)
        val isMulDiv = ((line === 0x8 || line === 0xC) && (opmode === 3 || opmode === 7))
        when(isDivuW || isDivsW) {
          o.illegal := False
          o.op := DecOp.DIV
          o.cluster := Cluster.CPLX
          o.size := Size.WORD
          o.srcA := dnField               // 32-bit dividend Dn
          o.srcB := easrc                 // 16-bit divisor EA
          o.dst := dnField; o.dstWrites := True   // result -> Dn
          o.writesNzvc := True            // DIV sets N/Z/V (C=0)
          o.divSigned := isDivsW
        } .elsewhen(!isRmw && !isMulDiv) {
          o.illegal := False
          switch(line) {
            is(0x8) { o.op := DecOp.OR }
            is(0x9) { o.op := DecOp.SUB }
            is(0xB) { o.op := DecOp.CMP }
            is(0xC) { o.op := DecOp.AND }
            is(0xD) { o.op := DecOp.ADD }
          }
          when(opmode === 0 || opmode === 1 || opmode === 2) {
            // EA -> Dn : srcA = Dn (dest operand), srcB = EA
            o.srcA := dnField; o.srcB := easrc; o.dst := dnField
            when(line =/= 0xB) { o.dstWrites := True }   // CMP writes no reg
            when(opmode === 0) { o.size := Size.BYTE }
              .elsewhen(opmode === 1) { o.size := Size.WORD }
              .otherwise { o.size := Size.LONG }
            when(line === 0xD || line === 0x9) { o.writesNzvc := True; o.writesX := True }   // ADD/SUB
              .elsewhen(line === 0xC || line === 0x8 || line === 0xB) { o.writesNzvc := True } // AND/OR/CMP
          } .elsewhen(opmode === 3 || opmode === 7) {
            // ADDA/SUBA/CMPA : srcA = EA, srcB = An, dst An
            o.srcA := easrc; o.srcB := anField; o.dst := anField
            when(line =/= 0xB) { o.dstWrites := True }
            when(line === 0xB) { o.writesNzvc := True }  // CMPA sets flags, no write
            when(opmode === 3) { o.size := Size.WORD } .otherwise { o.size := Size.LONG }
          }
        }
      }
    }
    o
  }
}
