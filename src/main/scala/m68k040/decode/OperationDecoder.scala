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
    val immext  = OperandSrc(); immext.kind := OperandKind.IMMEXT; immext.isAddr := False
    val immq3   = OperandSrc(); immq3.kind := OperandKind.IMMQ3;  immq3.isAddr := False

    switch(line) {
      // ---- Line-0 immediates: ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,<ea> ----
      // 0000 ooo0 ss mmmrrr + imm. opmode ooo (bits 11:9): 0=ORI,1=ANDI,2=SUBI,
      // 3=ADDI,5=EORI,6=CMPI (4=bit/BTST-imm, 7=MOVES -> out of scope). bit8=0; size
      // ss (bits 7:6): 00=.B,01=.W,10=.L (11 illegal). The IMMEDIATE is srcB (the
      // trailing ext word(s), sized by the op); the EA (op[5:0]) is the DESTINATION
      // operand (srcA, read) AND the writeback dst (dst=EASRC). CMPI writes no reg.
      // Register/data-reg destination only this slice (memory-dest RMW deferred -> the
      // assembler gates a non-data-reg EA to illegal). EA-agnostic: the assembler
      // maps EASRC to the EA register / cracks; this decoder just names the operands.
      is(0x0) {
        val opmode = opword(11 downto 9)
        val ss     = opword(7 downto 6)
        val isImm  = !opword(8) && (opmode === 0 || opmode === 1 || opmode === 2 ||
                                    opmode === 3 || opmode === 5 || opmode === 6)
        when(isImm && ss =/= 3) {
          o.illegal := False
          switch(opmode) {
            is(0) { o.op := DecOp.OR }
            is(1) { o.op := DecOp.AND }
            is(2) { o.op := DecOp.SUB }
            is(3) { o.op := DecOp.ADD }
            is(5) { o.op := DecOp.EOR }
            is(6) { o.op := DecOp.CMP }
          }
          o.srcA := easrc                      // EA = the destination operand (read)
          o.srcB := immext                     // the trailing immediate word(s)
          o.dst  := easrc                       // writeback to the same EA register
          when(opmode =/= 6) { o.dstWrites := True }   // CMPI writes no reg
          when(ss === 0) { o.size := Size.BYTE }
            .elsewhen(ss === 1) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
          when(opmode === 2 || opmode === 3) { o.writesNzvc := True; o.writesX := True }   // ADDI/SUBI
            .otherwise { o.writesNzvc := True }                                            // AND/OR/EOR/CMP
        }
      }
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
      // ---- Line-5: ADDQ/SUBQ #n,<ea> (0101 ddd q ss mmmrrr, ss != 11) ----
      // q = bit8 (0 = ADDQ, 1 = SUBQ); ddd = bits 11:9 (the quick immediate 1-8, with
      // ddd==0 meaning 8 — resolved in the assembler from the IMMQ3 operand). ss (7:6)
      // = .B/.W/.L (11 is the Scc/DBcc/TRAPcc family, handled in the assembler). The EA
      // (op[5:0]) is the DESTINATION operand: read as srcA (the ALU `a`/merge source),
      // written back as dst (same EA). Dn dest -> ADD/SUB + NZVCX (size-merged for .B/.W);
      // An dest -> full-32 ADD/SUB, NO flags (the assembler forces size LONG + clears the
      // flag masks when the EA resolves to an address register, like ADDA/SUBA). Scc/DBcc
      // (ss==11) are produced by the assembler (branch-EU condition path), so OpSpec here
      // leaves them illegal; the assembler overrides. Memory-dest ADDQ/SUBQ is deferred.
      is(0x5) {
        val ss = opword(7 downto 6)
        when(ss =/= 3) {                       // ss==11 -> Scc/DBcc (assembler-built)
          o.illegal := False
          o.op := Mux(opword(8), DecOp.SUB, DecOp.ADD)
          o.srcA := easrc                       // EA = the destination operand (read)
          o.srcB := immq3                       // the quick immediate (1-8, 0->8)
          o.dst  := easrc                        // writeback to the same EA register
          o.dstWrites := True
          when(ss === 0) { o.size := Size.BYTE }
            .elsewhen(ss === 1) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
          o.writesNzvc := True; o.writesX := True   // Dn dest; assembler clears for An dest
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
      // ---- Line-E register-form shifts/rotates (1110 ccc d ss i tt rrr) ----
      // ss (bits 7:6) = .B/.W/.L (11 = the memory single-bit form -> deferred/illegal).
      // d (bit8) = direction (1=left). i (bit5) = count source (0 immediate ccc, 1 reg
      // Dc). tt (bits 4:3) = family: 00 ASL/ASR, 01 LSL/LSR, 10 ROXL/ROXR, 11 ROL/ROR.
      // rrr (bits 2:0) = Dr (the shifted data reg). The assembler fills the fixed-field
      // operands (srcA=Dr, dst=Dr, srcB=Dc for the reg form) + the immediate count.
      // Flags: N/Z always; V only for ASL (set in the EU); writesX for AS/LS/ROX, NOT
      // for RO; readsX for AS/LS/ROX (count-0 preserves X) + ROX (rotate-through-X).
      is(0xE) {
        val ss = opword(7 downto 6)
        val tt = opword(4 downto 3)
        when(ss =/= 3) {                    // ss=11 is the memory single-bit form (deferred)
          o.illegal := False
          o.op := DecOp.SHIFT
          o.cluster := Cluster.INT
          when(ss === 0) { o.size := Size.BYTE }
            .elsewhen(ss === 1) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
          o.shiftOp  := tt
          o.shiftDir := opword(8)
          o.shiftImm := !opword(5)          // i=0 -> immediate count
          o.dstWrites := True
          o.writesNzvc := True
          val isRo = (tt === 3)             // ROL/ROR do NOT touch X
          o.writesX := !isRo
          o.readsX  := !isRo                // AS/LS/ROX read X (count-0 preserve + ROX-through-X)
        }
      }
      // ---- OR/SUB/CMP/AND/ADD (1ooo ... ) ----
      is(0x8, 0x9, 0xB, 0xC, 0xD) {
        val opmode = opword(8 downto 6)
        val isRmw  = (opmode === 4 || opmode === 5 || opmode === 6)
        // EOR (line B, opmode 4/5/6 = .B/.W/.L): `Dn ^ <ea> -> <ea>`. The EA is the
        // DESTINATION operand (read AND written), Dn (bits 11:9) the source. Register
        // destination only this slice (memory-dest RMW deferred -> the assembler gates
        // a non-reg EA to illegal). An-direct (mode 1) is CMPM, NOT EOR -> excluded.
        // Flags: NZ, V=C=0 (no X). srcA=EA (dst operand), srcB=Dn, dst=EA.
        val isEor = (line === 0xB) && isRmw && (opword(5 downto 3) =/= 1)
        // DIVU.W (line 0x8 opmode 3) / DIVS.W (line 0x8 opmode 7): 32-bit dividend Dn
        // (bits 11:9) / 16-bit divisor EA -> Dn = {rem[31:16], q[15:0]}. BOTH DIVs are
        // line 8 (the OR group). MULU.W (line 0xC opmode 3) / MULS.W (line 0xC opmode
        // 7): 16x16 -> Dn[31:0], the full 32-bit product; N/Z from the product, V=0.
        val isDivuW = (line === 0x8) && (opmode === 3)
        val isDivsW = (line === 0x8) && (opmode === 7)
        val isMuluW = (line === 0xC) && (opmode === 3)
        val isMulsW = (line === 0xC) && (opmode === 7)
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
        } .elsewhen((isMuluW || isMulsW) && (opword(5 downto 3) =/= 1)) {
          // MULU.W/MULS.W: 16x16 -> Dn[31:0]. The multiplier EA is a DATA addressing
          // mode; An-direct (mode 1) is NOT a legal MUL EA (matches the 040 ISA +
          // Musashi's "A+-DXWLdxI" mode set, which excludes An-direct) -> stays illegal.
          o.illegal := False
          o.op := DecOp.MUL
          o.cluster := Cluster.CPLX
          o.size := Size.WORD
          o.srcA := dnField               // Dn[15:0] multiplicand
          o.srcB := easrc                 // 16-bit multiplier EA
          o.dst := dnField; o.dstWrites := True   // product -> Dn[31:0]
          o.writesNzvc := True            // MUL sets N/Z (V=0, C=0)
          o.divSigned := isMulsW          // reuse divSigned as the MULS marker
        } .elsewhen(isEor) {
          // EOR Dn,<ea>: srcA = EA (dst operand), srcB = Dn, dst = EA (same field).
          o.illegal := False
          o.op := DecOp.EOR
          o.srcA := easrc; o.srcB := dnField; o.dst := easrc; o.dstWrites := True
          when(opmode === 4) { o.size := Size.BYTE }
            .elsewhen(opmode === 5) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
          o.writesNzvc := True            // EOR sets N/Z (V=0, C=0); no X
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
