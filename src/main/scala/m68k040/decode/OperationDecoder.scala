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
      // 3=ADDI,5=EORI,6=CMPI (4=bit/BTST-imm, 7=MOVES -> its OWN decode arm further down
      // this same line-0 block, `isMovesFamily`; it is NOT out of scope). bit8=0; size
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
        // ── Bit ops (BTST/BCHG/BCLR/BSET) ──────────────────────────────────────
        // dynamic 0000 rrr 1 tt mmmrrr (bit8=1, NOT mode 001=MOVEP); static 0000 1000
        // tt mmmrrr (bits 11:8 == 1000). tt (bits 7:6): 00 BTST, 01 BCHG, 10 BCLR, 11
        // BSET. The EA (op[5:0]) is the DESTINATION (tested + written, except BTST). The
        // bit number is Dn (dynamic, bits 11:9) or the trailing ext word (static). The
        // size/modulo is dest-dependent (Dn=LONG mod32, mem=BYTE mod8) -> the assembler
        // resolves it. This does NOT match the immediates above (isImm needs !bit8 +
        // opmode in {0,1,2,3,5,6}; static bit-op is opmode 4, dynamic is bit8=1).
        val mode     = opword(5 downto 3)
        val tt       = opword(7 downto 6)
        // ── CMP2/CHK2 (020+ bounds-check against a memory pair) ─────────────────
        // Encoding `0000 0ss0 11 mmm rrr` + ext word (Musashi `0000 0ss0 11......`):
        // bit11==0, ss=op[10:9] (00=.B,01=.W,10=.L), bit8==0, bits[7:6]==11 (the
        // family marker — also blocks the line-0 immediate path, which needs ss=/=3),
        // EA = a CONTROL mode (mode>=2). The EA points to the LOWER bound; the UPPER
        // bound is at EA+size. The compared register Rn + the CMP2-vs-CHK2 selector +
        // the A/D bit live in the extension word — the MicroOpAssembler owns the
        // 2-load+compare crack (reading word2). OperationDecoder only NAMES the op
        // (CPLX/DivEu, size from ss, reads+writes NZVC for the {oldN,Z,oldV,C} RMW) +
        // the EA as srcA so the predecode/EaDecoder frame it; the assembler overrides
        // the operand routing. mode<2 (Dn/An/postinc) stays ILLEGAL here.
        val ssCmp2 = opword(10 downto 9)
        val isCmp2Chk2 = !opword(11) && !opword(8) && (opword(7 downto 6) === 3) &&
                         (ssCmp2 =/= 3) && (opword(5 downto 3).asUInt >= 2)
        when(isCmp2Chk2) {
          o.illegal := False
          o.op      := DecOp.CMP2CHK2
          o.cluster := Cluster.CPLX
          when(ssCmp2 === 0) { o.size := Size.BYTE }
            .elsewhen(ssCmp2 === 1) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
          o.srcA := easrc                  // the EA bounds pointer (LOWER bound base)
          o.dst.setNone(); o.dstWrites := False
          o.readsNzvc  := True             // RMW: read old N/V to preserve them
          o.writesNzvc := True             // write {oldN, Z, oldV, C}
        }
        val isDynBit = opword(8) && (mode =/= B"001")            // exclude MOVEP (mode 001)
        // ── MOVEP (0000 rrr 1 oo 001 aaa) + disp16 ─────────────────────────────
        // bit8=1, mode (op[5:3]) == 001, opmode (op[8:6]) >= 4 (i.e. bit7|bit6 set: oo in
        // {00.W m->r already has opmode 4, ...}). Actually opmode = bit8 ## op[7:6]; with
        // bit8=1 the opmode is 4(.W m->r)/5(.L m->r)/6(.W r->m)/7(.L r->m) — all >= 4.
        // dir = op[7] (0 = mem->reg, 1 = reg->mem); size = op[6] (0 = .W, 1 = .L). The
        // DecodeStage MOVEP FSM owns the µop emission; OperationDecoder only marks it
        // NON-illegal (a benign MOVE placeholder so the assembler's `bad` never fires —
        // the FSM gates the normal crack off when active). EA-agnostic: the FSM forms the
        // addresses from Ay (op[2:0]) + disp16 directly (no EaDecoder routing here).
        val isMovep = opword(8) && (mode === B"001")
        when(isMovep) {
          o.illegal := False
          o.op := DecOp.MOVE                     // benign placeholder; the FSM produces the real µops
          o.movep := True
          o.movepDir := opword(7)
          o.movepSizeLong := opword(6)
        }
        val isStatBit= opword(11 downto 8) === B"1000"           // opmode 4 (bit8=0)
        when(isDynBit || isStatBit) {
          o.illegal := False
          o.op    := DecOp.BITOP
          o.bitOp := tt
          o.srcA  := easrc                          // the destination operand (tested + written)
          o.srcB  := Mux(isDynBit, dnField, immext) // bit number: Dn (dyn) or ext word (stat)
          o.dst   := easrc
          o.dstWrites := (tt =/= B"00")             // BTST writes nothing
          o.readsNzvc  := True                      // the EU needs old N/V/C to preserve them
          o.writesNzvc := True                      // Z computed; the EU preserves N/V/C (Z-only)
          // size is dest-dependent (Dn=LONG, mem=BYTE) -> resolved in the assembler.
        }
        // ── CAS / CAS2 (020+ atomic compare-and-swap) ──────────────────────────
        // CAS  `0000 1ss0 11 mmm rrr` + 1 ext word: op[15:11]=00001, op[8]=0,
        // op[7:6]=11, op[10:9]=size (01=.B,10=.W,11=.L). EA op[5:0] = memory-ALTERABLE
        // (Musashi mask `A+-DXWL...`): (An)/(An)+/-(An)/(d16,An)/(d8,An,Xn)/(xxx).W/.L —
        // INCLUDES the auto-inc/dec modes. Reject Dn/An/PC-rel/#imm.
        // CAS2 `0000 1ss0 11 111100` + 2 ext words: same family, op[5:0]=111100 (mode7/
        // reg4). Both route through the v2 microcode engine (CAS 4 µops, CAS2 10 µops) —
        // the whole crack EXCEEDS the fast budget. OperationDecoder marks `microcoded`
        // + a DEFAULT ucEntry (CAS_ENTRY / CAS2_ENTRY); DecodeStage's ucBegin populates
        // the Dc/Du/Rn ext-word fields (OperationDecoder is ext-word-free). The op is a
        // benign placeholder (DecOp.CASOP, non-illegal) so the assembler's `bad` never
        // fires. Flags: NZVC, no X. (Disjoint from the bit-op / CMP2 / immediate arms:
        // CAS needs op[11]=1, op[8]=0, op[7:6]=11 — see the encoding cross-check.)
        val casFamily = (opword(15 downto 11) === B"00001") && !opword(8) &&
                        (opword(7 downto 6) === B"11")
        val isCas2    = opword(5 downto 0) === B"111100"   // mode 7 / reg 4
        // CAS EA legality (memory-ALTERABLE, Musashi mask `A+-DXWL...`): (An)=2, (An)+=3,
        // -(An)=4, (d16,An)=5, (d8,An,Xn)=6, (xxx).W=7/0, (xxx).L=7/1. Memory-alterable
        // INCLUDES auto-inc/dec (it is NOT alterable-CONTROL). Reject Dn/An (0/1), PC-rel
        // (7/2, 7/3) and #imm (7/4).
        val casMode   = opword(5 downto 3)
        val casReg    = opword(2 downto 0)
        val casEaOk   = (casMode === B"010") || (casMode === B"011") || (casMode === B"100") ||
                        (casMode === B"101") || (casMode === B"110") ||
                        ((casMode === B"111") && ((casReg === B"000") || (casReg === B"001")))
        def setCasSize(): Unit = {
          when(opword(10 downto 9) === B"01") { o.size := Size.BYTE }
            .elsewhen(opword(10 downto 9) === B"10") { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
        }
        when(casFamily && (opword(10 downto 9) =/= B"00")) {
          when(isCas2) {
            o.illegal := False
            o.microcoded := True
            o.ucEntry := U(Microcode.CAS2_ENTRY, o.ucEntry.getWidth bits)
            o.op := DecOp.CASOP
            o.cluster := Cluster.INT
            setCasSize()                     // CAS2.W / CAS2.L
            o.writesNzvc := True             // final NZVC (no X)
          } elsewhen(casEaOk) {
            o.illegal := False
            o.microcoded := True
            o.ucEntry := U(Microcode.CAS_ENTRY, o.ucEntry.getWidth bits)
            o.op := DecOp.CASOP
            o.cluster := Cluster.INT
            setCasSize()
            o.srcA := easrc                  // the EA (so predecode/EaDecoder frame it)
            o.writesNzvc := True             // NZVC from cmp(dest,Dc) (no X)
          }
          // A non-memory-alterable CAS EA (Dn/An/PC-rel/#imm) stays ILLEGAL
          // (the default o.illegal=True; the microcoded arm above did not fire) -> vector-4.
        }
        // ── MOVES (010+ PRIVILEGED move to/from alternate address space) ────────
        // Encoding `0000 1110 ss mmm rrr` + 1 ext word: op[15:8]=0x0E (op[15:11]=00001,
        // op[10:8]=110), op[7:6]=ss (00=.B,01=.W,10=.L; 11 is NOT moves -> that is the CAS
        // family). DISJOINT from CAS (op[7:6]=11), the line-0 immediates (need opmode in
        // {0,1,2,3,5,6} = op[15:9] patterns, op[8]=0), the bit-ops (op[8]=1 or op[15:8]=
        // 0x80), CMP2/CHK2 (op[7:6]=11). EA = memory-ALTERABLE (Musashi mask `A+-DXWL...`,
        // SAME as CAS): (An)/(An)+/-(An)/(d16,An)/(d8,An,Xn)/(xxx).W/.L. Reject Dn/An/PC-rel/
        // #imm. The dr/A-D/reg fields live in the ext word -> DecodeStage.ucBegin picks the
        // WRITE vs READ entry; OperationDecoder just marks it microcoded + names op=MOVES +
        // size. The privilege flag (needsSupervisor) is carried on the first µop via Ctx.
        val isMovesFamily = (opword(15 downto 8) === B"00001110") &&
                            (opword(7 downto 6) =/= B"11")
        // Reuse the CAS EA legality (identical memory-alterable mask).
        when(isMovesFamily && casEaOk) {
          o.illegal    := False
          o.microcoded := True
          o.ucEntry    := U(Microcode.MOVES_WRITE_ENTRY, o.ucEntry.getWidth bits)  // placeholder; ucBegin picks WRITE/READ by dr
          o.op         := DecOp.MOVES
          o.cluster    := Cluster.INT
          o.srcA       := easrc            // the EA (so predecode/EaDecoder frame it)
          // size from op[7:6] (00=.B,01=.W,10=.L). NO CCR effect.
          when(opword(7 downto 6) === B"00") { o.size := Size.BYTE }
            .elsewhen(opword(7 downto 6) === B"01") { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
        }
        // A non-memory-alterable MOVES EA (Dn/An/PC-rel/#imm) stays ILLEGAL (default).
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
        // Scc <ea> memory-destination size fix-up (task #160): the assembler builds the
        // WHOLE Scc instruction by hand (branch-EU condition write; illegal-gating is
        // via the assembler's isSccOp exclusion, not spec.illegal), so this decoder does
        // NOT otherwise touch Scc/DBcc/TRAPcc (ss==3) at all. But `o.spec.size` feeds
        // BOTH the offloaded EaDecoder call (autoDelta for -(An)/(An)+ EAs -- WITHOUT
        // this, a byte Scc predec/postinc would decrement/increment An by the WRONG
        // amount, the WORD default) and the assembler's rmwStUop (the memory-form's
        // trailing store size). Harmless for DBcc/TRAPcc (neither reads srcEa-derived
        // size/delta in its own hand-built crack).
        when(ss === 3) { o.size := Size.BYTE }
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
        // EXTB.L (0100 1001 11 000 rrr, op[15:6]==0x127) has bit8=1 & bit6=0 and would
        // otherwise alias the CHK pattern; decode it as the unary EXT (byte->long) below
        // and exclude it from CHK. (The remaining bit8=1/bit6=0 line-4 opwords are CHK.)
        val isExtbL = opword(15 downto 6) === B"10'b0100100111"
        when(opword(8) && !opword(6) && !isExtbL) {
          o.illegal := False
          o.op := DecOp.CHK
          o.cluster := Cluster.CPLX
          o.size := Mux(opword(7), Size.WORD, Size.LONG)
          o.srcA := dnField            // Dn (checked value)
          o.srcB := easrc              // bound (EA)
          o.dst.setNone(); o.dstWrites := False
          o.writesNzvc := True         // CHK sets N (1 if Dn<0, 0 if Dn>=0); Z=V=C=0
        }
        // ── Line-4 single-operand DATA-register family (the unary group) ──────────
        // CLR/NEG/NEGX/NOT/TST (`0100 oooo ss 000rrr`, oooo selects the op, ss the size
        // .B/.W/.L, mode 000 = Dn) + SWAP/EXT/EXTB/TAS (the `0100 1000`/`1001`/`1010`
        // sub-group). The EA (op[5:0]) is the DESTINATION operand: srcA = the read
        // (merge / operand) source, dst = the same EA register (written back, except
        // TST). EASRC routes to the EA register in the assembler; the assembler gates a
        // non-Dn EA (mode != 000) to the deferred memory form (illegal). bit8=0 here
        // EXCEPT EXTB.L (handled by its explicit pattern, which dominates).
        val o4   = opword(11 downto 8)   // op selector for NEGX/CLR/NEG/NOT/TST
        val ss4  = opword(7 downto 6)
        def setSize(): Unit = {
          when(ss4 === 0) { o.size := Size.BYTE }
            .elsewhen(ss4 === 1) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
        }
        when(!opword(8) && ss4 =/= 3 && (o4 === 0x0 || o4 === 0x2 || o4 === 0x4 ||
                                         o4 === 0x6 || o4 === 0xA)) {
          o.illegal := False
          o.srcA := easrc                       // dst operand read (merge / source)
          o.dst  := easrc                        // writeback to the same EA register
          setSize()
          switch(o4) {
            is(0x0) {   // NEGX: 0-Dn-X, NZVCX, reads X + old Z (Z clear-only)
              o.op := DecOp.NEGX; o.dstWrites := True
              o.writesNzvc := True; o.writesX := True
              o.readsX := True; o.readsNzvc := True
            }
            is(0x2) {   // CLR: Dn:=0, N=0/Z=1/V=0/C=0 (no X)
              o.op := DecOp.CLR; o.dstWrites := True
              o.writesNzvc := True
            }
            is(0x4) {   // NEG: 0-Dn, NZVCX (X=C)
              o.op := DecOp.NEG; o.dstWrites := True
              o.writesNzvc := True; o.writesX := True
            }
            is(0x6) {   // NOT: ~Dn, NZ (V=0,C=0)
              o.op := DecOp.NOT; o.dstWrites := True
              o.writesNzvc := True
            }
            is(0xA) {   // TST: flags only (NZ); NO write
              o.op := DecOp.TST; o.dstWrites := False
              o.writesNzvc := True
            }
          }
        }
        // SWAP Dn (0100 1000 0100 0rrr, op[15:3]==0x0908): full-32 halves swap, NZ.
        when(opword(15 downto 3) === B"13'b0100100001000") {
          o.illegal := False
          o.op := DecOp.SWAP; o.size := Size.LONG
          o.srcA := easrc; o.dst := easrc; o.dstWrites := True
          o.writesNzvc := True
        }
        // EXT.W Dn (0100 1000 1000 0rrr, op[15:3]==0x0910): byte->word (.W, NZ, extByte).
        when(opword(15 downto 3) === B"13'b0100100010000") {
          o.illegal := False
          o.op := DecOp.EXT; o.size := Size.WORD; o.extByte := True
          o.srcA := easrc; o.dst := easrc; o.dstWrites := True
          o.writesNzvc := True
        }
        // EXT.L Dn (0100 1000 1100 0rrr, op[15:3]==0x0918): word->long (full-32, NZ).
        when(opword(15 downto 3) === B"13'b0100100011000") {
          o.illegal := False
          o.op := DecOp.EXT; o.size := Size.LONG
          o.srcA := easrc; o.dst := easrc; o.dstWrites := True
          o.writesNzvc := True
        }
        // EXTB.L Dn (0100 1001 1100 0rrr, op[15:3]==0x0938): byte->long (full-32, NZ, extByte).
        when(opword(15 downto 3) === B"13'b0100100111000") {
          o.illegal := False
          o.op := DecOp.EXT; o.size := Size.LONG; o.extByte := True
          o.srcA := easrc; o.dst := easrc; o.dstWrites := True
          o.writesNzvc := True
        }
        // TAS Dn / TAS <ea> (0100 1010 11 mmmrrr, op[15:6]==0x0958>>3==0b0100101011):
        // N/Z from the operand's original byte; bit7:=1. Dn-direct is a single ALU µop
        // (srcA=dst=Dn); a MEMSIMPLE EA is the generic load-op-store RMW crack (the
        // assembler's `rmwOpInScope`/`line4UnaryMemBad` gate the EA class -- An-direct/
        // #imm/MEMCOMPLEX/pcRel stay illegal there). Task #158.
        when(opword(15 downto 6) === B"10'b0100101011") {
          o.illegal := False
          o.op := DecOp.TAS; o.size := Size.BYTE
          o.srcA := easrc; o.dst := easrc; o.dstWrites := True
          o.writesNzvc := True
        }
        // NBCD Dn (0100 1000 00 000 rrr, op[15:3]==0b0100100000000): Dn := BCD(0-Dn-X)
        // (register form ONLY -- memory-EA NBCD stays deferred/illegal, task #159; the
        // 68k NBCD memory-dest RMW crack, like TAS-mem previously, needs its own
        // predecode framing + assembler RMW-crack wiring, not attempted this slice).
        // srcA=srcB=Dn (EASRC, mode 000 -> DATAREG -> both read the SAME register): srcA
        // is the .B-merge upper-24 source (sizeMerged always reads s1Src1), srcB supplies
        // the "dy" operand the SBCD datapath subtracts. AluEuPlugin forces the SBCD
        // formula's "dx" (normally s1Src1, Dx) to the constant 0 when op==NBCD, reusing
        // the bcdSub=True (subtract) cone verbatim otherwise. Flags mirror SBCD exactly.
        when(opword(15 downto 3) === B"13'b0100100000000") {
          o.illegal := False
          o.op := DecOp.NBCD; o.size := Size.BYTE
          o.srcA := easrc; o.srcB := easrc; o.dst := easrc; o.dstWrites := True
          o.bcdSub := True
          o.writesNzvc := True; o.writesX := True
          o.readsX := True; o.readsNzvc := True
        }
        // BKPT #n (0100 1000 0100 1 vvv, op[15:3]==0b0100100001001, task #178
        // cluster12/exc_bkpt_decode): no operands, no flags, no state change --
        // decoded as a plain no-op (mirrors the NOP 0x4E71 case below exactly).
        // Real 040 silicon runs a breakpoint-acknowledge bus cycle here (illegal-
        // trapping if unacknowledged); that bus-level protocol is not modeled --
        // this core simply commits BKPT like NOP, which is sufficient for it to
        // retire cleanly and be observable at commit (the harness-side "clean
        // debug halt" detection watches for exactly that commit event).
        when(opword(15 downto 3) === B"13'b0100100001001") {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.LONG
          o.dst.setNone(); o.dstWrites := False
        }
        // MOVEM (0100 1 d 001 s mmmrrr) + 16-bit register-mask ext word: bit11=1, bit10=d
        // (0 store / 1 load), bits 9:7=001, bit6=s (0 .W / 1 .L). The DecodeStage micro-
        // sequencer FSM owns the µop emission (reads movem/movemDir/movemSizeLong + the EA
        // + the mask); OperationDecoder only marks it NON-illegal (a benign MOVE placeholder
        // like EXG so the assembler's `bad` never fires — the FSM gates the normal crack off
        // when active). The EA mode field DISAMBIGUATES from EXT.W (0x4880, mode 000) /
        // EXT.L (0x48C0, mode 000), which share bit11=1 & bits9:7=001 but use Dn-direct
        // (mode 0): a real MOVEM EA is a MEMORY mode (>=2), so EXCLUDE reg-direct modes 0/1.
        // Indexed An-base (mode 6) is admitted below in BOTH directions — LOAD by task
        // movem-agu-index-hazard-2026-08-19, STORE by task movem-idx-an-store-2026-09-11
        // (`(d8,An,Xn)` is control-ALTERABLE, so `MOVEM <list>,(d8,An,Xn)` is a real 68040
        // instruction; see the long note at the `movemEaOk` gate itself). #imm + the
        // reserved mode-7 regs >= 4 remain OUT OF SCOPE (genuinely illegal on silicon
        // too). `(d8,PC,Xn)` (mode 7 reg 3) GAINED an index-register
        // read port in the FSM (task #200, MicroOpAssembler.movemMoveUop's new srcC/
        // indexLong/indexScale threading), originally admitted here ONLY for `.L` (bit6=1).
        // ported-tests triage (movem_pc_idx_w HANG investigation): `.W` PC-indexed MOVEM is
        // a real, supported 68040 addressing form and the FSM that computes the EA is
        // ALREADY fully size-generic — `DecodeStage.scala`'s `movemBegin` block latches
        // `movemSizeLong := eopw(6)` unconditionally (same register, same code path as
        // every other MOVEM EA), and the PC-indexed extension-word length is fixed at
        // exactly 1 word (opword+mask+ext = 3 words total) REGARDLESS of `.W` vs `.L` — the
        // size only changes the per-element transfer width, not the EA shape. The `.L`-only
        // gate here was therefore an unnecessary, narrowly-scoped restriction (not a real
        // FSM limitation), and it hid a genuine correctness bug, not a clean illegal trap.
        // Pre-fix, `movemEaOk` was False for `.W` here, so `spec0.movem` was also False and
        // `DecodeStage.scala`'s `movemBegin`/`movemPcIdx*` resume machinery NEVER engaged --
        // that machinery is gated purely on this decoder's own classification, not on EA
        // shape alone. What actually unstuck the front end pre-fix was a DIFFERENT,
        // coincidental mechanism: `PredecodeWord.scala`'s `mmOk` table always frames
        // mode-7-reg-3 as `complex` regardless of legality, and since `illegal=True` sent
        // the opword down the ordinary vector-4 exception path, that exception's own
        // redirect substituted for the missing explicit resume. Since this test's own
        // vector-4 handler was never installed (correctly so, once `.W` is admitted --
        // this addressing form is meant to execute, not trap), the CPU vectored through
        // whatever uninitialized garbage lived at vector 4 and free-ran forever: an
        // apparent HANG that was actually a mis-vectored illegal exception, not a stalled
        // front end. Admitting `.W` here (dropping the `opword(6)` restriction) routes the
        // opword through the SAME already-working FSM/resume path as `.L` PC-indexed
        // instead, so the exception-path workaround is no longer needed for this case. `movem_idx_unimpl_traps.s`'s old `_c4` case (which pinned `.W`
        // PC-indexed as a required illegal trap) is now stale and has been updated to drop
        // that expectation, matching upstream m68k-ooo's own 2026-07-22 Phase-2 item #3 fix
        // (which generalized the identical An-indexed-.W crack to also drop the PC-indexed
        // `.L`-only restriction) — see `movem_pc_idx_w.s`'s header for the upstream
        // rationale. A `.L`/`.W` opword here is ALWAYS `complex`-framed by
        // PredecodeWord.scala (its `mmOk` table never marks mode-7-reg-3 `simple`, for
        // EITHER size) — DecodeStage.scala's MOVEM FSM drives a dedicated `movemPcIdx*`-
        // prefixed front-end resume (mirroring the existing mem-indirect-MOVE
        // `ucComplexResume` mechanism) to unstick fetch instead of hanging. Full-format
        // `(bd,PC,Xn)` (ext word bit8=1) is NOT distinguishable from brief here (this
        // classifier only sees the first opword) — it silently computes a wrong-but-bounded
        // EA (never a hang, see DecodeStage.scala's `eIsPcIdxMovem` comment); untested,
        // out of scope, characterized not fixed (task #200 report). task #165 (ported-tests
        // triage): this classifier used
        // to read `mmMode4 >= 2` UNCONDITIONALLY, on the (FALSE) assumption that
        // PredecodeWord.scala's `isMovem` COMPLEX framing alone would keep an indexed
        // MOVEM out of the DecodeStage FSM — but `slot0IsMovem` (DecodeStage.scala) gates
        // PURELY on this decoder's `spec0.movem`, independent of predecode's simple/complex
        // marking. An indexed MOVEM opword therefore silently entered the FSM anyway, with
        // its unhandled EA mode falling through DecodeStage's mode-switch default (base
        // invalid, disp=0) — executing as a garbage-address MOVEM to/from address 0 instead
        // of cleanly faulting. Worse: since nothing in this codebase ever drives
        // FetchAlignPlugin's `resume` port (only a real redirect/exception clears its
        // `stalled` latch, set the instant predecode's OWN complex/COMPLEX framing was
        // emitted), the frontend never resumed fetching once the FSM silently "completed" —
        // a PERMANENT stall (observed as a HANG, not a FAIL/illegal-trap). Restricting this
        // classifier's EA-mode gate to exactly the FSM-supported shapes (mirroring
        // PredecodeWord's own `mmOk` table) leaves indexed MOVEM `illegal=True` (the
        // OpSpec.illegalDefault()), so it now takes the ordinary vector-4 path instead.
        // task movem-agu-index-hazard-2026-08-19: `(d8,An,Xn)` brief-indexed LOAD (mode
        // 110) is admitted here too, mirroring the `(d8,PC,Xn)` (mode 7 reg 3) precedent
        // directly above -- DecodeStage.scala's FSM now has a real EA-compute-then-N-LOAD
        // crack for it (base=An + index=Xn*scale + sext(d8), the same srcA/srcC/imm shape
        // the AGU already proves correct for ordinary non-MOVEM indexed instructions), plus
        // the generalized `eIdxPresent`-gated front-end resume + a TWO-STEP base+index
        // snapshot (mode 6 is the first MOVEM EA shape with a REAL base register AND a real
        // index register live at once, unlike PC-indexed where the base is folded into a
        // literal and (An)/(d16,An) which have no index).
        // task movem-idx-an-store-2026-09-11: the STORE direction (opword(10)=0) of that
        // SAME mode-6 EA is now admitted too. `(d8,An,Xn)` is a CONTROL ALTERABLE mode, so
        // `MOVEM <list>,(d8,An,Xn)` is a REAL, legal MC68040 instruction (Musashi's own
        // `movem_re_*` EA mask is `A+-DXWL`, i.e. it includes mode 6); trapping it was a
        // divergence from silicon, and the Quadra 700 ROM is exactly the kind of code that
        // reaches for it. Admitting it needed NO new FSM state: DecodeStage.scala's MOVEM
        // micro-sequencer is already DIRECTION-AGNOSTIC for this EA -- `eIsAnIdxMovem` /
        // `eBaseDispV` / `eIdxPresent` / the base+index snapshot sequencing / the
        // `movemPcIdxResumeFire` front-end resume / the `movemHasFinal` `An += 0` kept
        // commit are all selected on the EA MODE alone, never on `eopw(10)`, and
        // `MicroOpAssembler.movemMoveUop` already threads `idxReg` onto srcC for BOTH
        // directions (a STORE's srcA=base, srcB=data, srcC=index triple is the same shape
        // `MicroOpAssembler`'s ordinary indexed-store crack -- `stUop.srcCReg :=
        // stDstEa.indexReg` -- has always emitted, so the LS EU/AGU read port already
        // exists). The two LOAD-only mechanisms in the FSM (`movemLoadDst`'s postinc
        // base-in-list DISCARD and the `movemProbeCount` far-page probe) are both gated on
        // `movemIsLoad` and correctly stay inert for a store: a MOVEM STORE writes no
        // architectural register, so it needs neither the in-list discard nor mid-list
        // register rollback. The base/index SNAPSHOT µops still fire (they are gated on the
        // EA shape, not the direction); they are redundant-but-harmless for a store for the
        // same reason, and keeping them shared avoids a second, direction-specific entry
        // path through the FSM. `movem_idx_unimpl_traps.s` no longer pins this shape (see
        // that file's header for the same "a pin on an implemented shape would be wrong"
        // update it already took twice before); positive coverage lives in
        // `movem_idx_an_store.s` + the `movem-idx-an-store-*` lock-steps.
        // STILL out of scope for BOTH directions: reg-direct (modes 0/1), #imm / the
        // reserved mode-7 regs >= 4, and FULL-FORMAT (bd,An/PC,Xn) extension words (ext
        // bit8=1), which this first-opword-only classifier cannot distinguish from brief
        // and which degrade to a wrong-but-bounded EA (characterized, not fixed -- task
        // #200). Note also that this gate is DIRECTION-AGNOSTIC where a real 68040 is not:
        // `(An)+` STORE, `-(An)` LOAD and PC-relative STORE are accepted here although
        // silicon takes vector 4 on them. That is an OVER-acceptance (we execute where the
        // 040 traps), the opposite polarity of the gap this task closed, and no assembler
        // can even emit those encodings -- tracked, not fixed here.
        val mmMode4 = opword(5 downto 3)
        val mmReg4  = opword(2 downto 0)
        val movemEaOk = (mmMode4.asUInt >= 2 && mmMode4.asUInt <= 5) ||
                        (mmMode4 === B"3'b111" && mmReg4.asUInt <= 2) ||
                        (mmMode4 === B"3'b111" && mmReg4 === B"3'b011") ||  // (d8,PC,Xn), .W and .L
                        (mmMode4 === B"3'b110")                             // (d8,An,Xn) LOAD+STORE, .W and .L
        when(opword(11) && (opword(9 downto 7) === B"001") && movemEaOk) {
          o.illegal := False
          o.op := DecOp.MOVE                     // benign placeholder; the FSM produces the real µops
          o.movem := True
          o.movemDir := opword(10)
          o.movemSizeLong := opword(6)
        }
        // ── LEA An,<ea> (0100 An 1 11 mmmrrr): bit8=1, bits7:6=11. Compute the control-
        // EA ADDRESS -> An (full-32, no flags). A benign MOVE placeholder; the assembler
        // builds the leaAddr crack (LS address-generate). The An dst rides op[11:9]. The
        // EA must be a MEMORY/control mode (mode >= 2): reg-direct (Dn/An) LEA is illegal
        // AND mode 000 + bits7:6=11 is EXTB.L (0x49C0, op[15:6]==0100100111) — excluding
        // mode 000/001 keeps EXTB.L on the EXT path and rejects the illegal reg-direct LEA.
        // (CHK is bit6=0 -> disjoint; MOVEM is bit8=0 -> disjoint.)
        val leaMode = opword(5 downto 3)
        when(opword(8) && (opword(7 downto 6) === B"11") && (leaMode.asUInt >= 2)) {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.LONG
          // operands left to the assembler's leaAddr crack (the EA is an ADDRESS the AGU
          // computes, not a loaded value — no EASRC operand here).
        }
        // ── PEA <ea> (0100 1000 01 mmmrrr): op[15:6]==0x121. Push the control-EA address.
        // The EA must be a CONTROL addressing mode (mode field >= 2): reg-direct (mode 000
        // = Dn) is NOT PEA — 0x48400|rrr is SWAP Dn (op[15:3]==0x0908, mode 000), which
        // shares op[15:6]==0x121 and would otherwise be clobbered here. Excluding mode
        // 000/001 keeps SWAP on its own pattern (and rejects the illegal reg-direct PEA).
        when((opword(15 downto 6) === B"10'b0100100001") && (opword(5 downto 3).asUInt >= 2)) {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.LONG
        }
        // ── MOVE from SR (0100 0000 11 mmmrrr): op[15:6]==0x103. SR(16) -> EA (.W).
        // PRIVILEGED (040): the assembler sets needsSupervisor (ROB vector-8 if S==0).
        when(opword(15 downto 6) === B"10'b0100000011") {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.WORD
        }
        // ── MOVE from CCR (0100 0010 11 mmmrrr): op[15:6]==0x10B. CCR(byte,ZX) -> EA (.W).
        when(opword(15 downto 6) === B"10'b0100001011") {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.WORD
        }
        // ── MOVE to CCR (0100 0100 11 mmmrrr): op[15:6]==0x113. EA(.W low byte) -> CCR.
        // srcB = the EA source (so a memSimple EA gets the generic leading-load crack);
        // the assembler marks the op µop toCcr (direct CCR := src[4:0]).
        when(opword(15 downto 6) === B"10'b0100010011") {
          o.illegal := False
          o.op := DecOp.MOVE; o.size := Size.WORD
          o.srcB := easrc
        }
        // ── MOVE to SR (0100 0110 11 mmmrrr) + ea : src.W -> SR (PRIVILEGED) ──────
        // The opmode-6 case of the 0x4xC0 family (Track C owns opmodes 0/2/4 =
        // MOVE-from-SR/from-CCR/to-CCR; keep this carve to opmode 6 ONLY so the
        // controller reconciles). A COMMIT-TIME SYSTEM op: it writes the FULL SR
        // (system byte incl S/T/I + CCR), re-banks A7 on an S flip, serializes, and
        // a committed S=0 traps (vector 8 — checked at retire). The EA src is read
        // as srcB (the move source, .W); no dst reg write (the SR is committed state,
        // applied by the ExceptionUnit). NOT illegal; the assembler builds the sysOp
        // µop reading the EA source. EA mode field op[5:3] disambiguated from the
        // unary/MOVEM patterns (distinct bits 15:6).
        when(opword(15 downto 6) === B"10'b0100011011") {
          o.illegal := False
          o.op := DecOp.MOVE                     // result = srcB (the EA source word)
          o.size := Size.WORD
          o.srcB := easrc                        // the EA source (.W) -> SR
          o.dst.setNone(); o.dstWrites := False  // no reg write; SR is committed state
          o.sysOp := True
          o.sysKind := SysKind.MOVE_TO_SR
          o.sysReadDir := False                  // write <ea> -> SR
        }
        // ── MOVE USP (0100 1110 0110 d rrr): An <-> USP (PRIVILEGED) ──────────────
        // 0x4E60|reg = An -> USP (d=0, sysReadDir=False); 0x4E68|reg = USP -> An
        // (d=1, sysReadDir=True). opword(15 downto 4) == 0x4E6. The An is op[2:0].
        // A COMMIT-TIME SYSTEM op (the USP bank lives in SystemState). Direction d =
        // bit3. WRITE: src An rides srcB (read in the datapath, value captured). READ:
        // dst An rides dst (the FSM writes int PRF arch-An). S=0 -> vector 8.
        when(opword(15 downto 4) === B"12'h4E6") {
          o.illegal := False
          o.op := DecOp.MOVE
          o.size := Size.LONG
          o.sysOp := True
          o.sysKind := SysKind.MOVE_USP
          o.sysReadDir := opword(3)              // 1 = USP -> An (read), 0 = An -> USP (write)
          when(opword(3)) {                      // USP -> An : dst = An (op[2:0]+8)
            o.dst := anField; o.dstWrites := False  // the FSM writes the int PRF (not a normal rename dst)
          } otherwise {                          // An -> USP : src An -> srcB
            o.srcB := anField
            o.dst.setNone(); o.dstWrites := False
          }
        }
        // ── MOVEC (0100 1110 0111 101 d): Rc <-> Rn (PRIVILEGED) + ext word ───────
        // 0x4E7A = Rc -> Rn (d=0, sysReadDir=True); 0x4E7B = Rn -> Rc (d=1,
        // sysReadDir=False). opword(15 downto 1) == B"...0100111001111101" i.e.
        // opword(15 downto 1) === 0x4E7A>>1. The ext word {A/D, reg#, 12-bit Rc} is
        // parsed by the assembler (the operands need the ext word). A COMMIT-TIME
        // SYSTEM op. S=0 -> vector 8.
        when(opword(15 downto 1) === B"15'b010011100111101") {
          o.illegal := False
          o.op := DecOp.MOVE
          o.size := Size.LONG
          o.sysOp := True
          o.sysKind := SysKind.MOVEC
          o.sysReadDir := !opword(0)             // 0x4E7A (bit0=0) = Rc->Rn (read); 0x4E7B = Rn->Rc (write)
          // operands resolved by the assembler from the ext word (A/D + reg# + Rc).
        }
        // ── NOP (0x4E71): no architectural effect — commits and advances PC, nothing
        // else. (The real 040 NOP is a pipeline synchronizer; an in-order-retiring
        // no-write µop is architecturally equivalent — Musashi's m68k_op_nop body is
        // empty.) Decoded as a no-operand, no-dst, no-flags MOVE: the op-µop writes no
        // register (dstWrites=False) and no CCR bits, so the commit is a pure PC step.
        // Was MISSING entirely (predecode had no case either) -> a NOP trapped vector-4
        // (found by the first lock-step program that actually committed a NOP).
        when(opword === B"16'h4E71") {
          o.illegal := False
          o.op := DecOp.MOVE
          o.size := Size.LONG
          o.dst.setNone(); o.dstWrites := False
        }
        // ── RESET (0x4E70): privileged; asserts the external reset line for 512 clks.
        // Architecturally a NOP (no state change). A COMMIT-TIME SYSTEM op so it serializes
        // + advances PC like the other sysOps; S=0 -> vector-8. The FSM does nothing but
        // consume it + redirect to nextPc.
        when(opword === B"16'h4E70") {
          o.illegal := False
          o.op := DecOp.MOVE
          o.size := Size.LONG
          o.sysOp := True
          o.sysKind := SysKind.RESET
          o.sysReadDir := False
          o.dst.setNone(); o.dstWrites := False
        }
        // ── STOP (0x4E72) + imm16: privileged; SR := imm16 then HALT until IRQ > new mask.
        // A COMMIT-TIME SYSTEM op: the SR write reuses the MOVE-to-SR S_APPLY path (the
        // value = imm16, carried by the assembler as a MOVE-imm op-µop -> sysValStore). The
        // halt is the ROB `stopped` state. S=0 -> vector-8.
        when(opword === B"16'h4E72") {
          o.illegal := False
          o.op := DecOp.MOVE                     // result = imm16 (the new SR), captured for the FSM
          o.size := Size.WORD
          o.sysOp := True
          o.sysKind := SysKind.STOP
          o.sysReadDir := False
          o.dst.setNone(); o.dstWrites := False
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
      // ss (bits 7:6) = .B/.W/.L (11 selects the word-sized memory single-bit form).
      // d (bit8) = direction (1=left). i (bit5) = count source (0 immediate ccc, 1 reg
      // Dc). tt (bits 4:3) = family: 00 ASL/ASR, 01 LSL/LSR, 10 ROXL/ROXR, 11 ROL/ROR.
      // rrr (bits 2:0) = Dr (the shifted data reg). The assembler fills the fixed-field
      // operands (srcA=Dr, dst=Dr, srcB=Dc for the reg form) + the immediate count.
      // Flags: N/Z always; V only for ASL (set in the EU); writesX for AS/LS/ROX, NOT
      // for RO. ROX always reads X; AS/LS read X only for a register count,
      // where count=0 must preserve it. Immediate counts are always 1..8.
      is(0xE) {
        val ss = opword(7 downto 6)
        val tt = opword(4 downto 3)
        val mode = opword(5 downto 3)
        when(ss =/= 3) {                    // ss=11 is the memory single-bit form
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
          val isRox = (tt === 2)            // ROXL/ROXR rotate through X
          val isRo = (tt === 3)             // ROL/ROR do NOT touch X
          o.writesX := !isRo
          o.readsX  := isRox || (opword(5) && !isRo)
        }
        // ── Line-E MEMORY-form shift/rotate (1110 ttt d 11 mmm rrr, task #170-
        // cluster10): op[11]=0 (op[11]=1 at ss=11 is the bit-field register/memory
        // forms, handled separately below), ss=11 (op[7:6]==3), mode != 000/001
        // (Dn/An -- illegal, no register-direct form at this encoding; the Dn form
        // lives entirely under ss!=3 above). Word-only, implicit count=1 (real ISA:
        // memory shifts always shift exactly 1 bit, never a register/immediate
        // count). EA is the operand read AND written back (RMW), same srcA=dst=easrc
        // shape CLR/NEG/NOT/TAS/Scc-mem already use -- rides the existing generic
        // `crackRmw` load->op->store path unmodified once MicroOpAssembler routes
        // the memory form's operands (see `isShiftMem` there).
        val isShiftMem = !opword(11) && (ss === 3) && (mode =/= 0) && (mode =/= 1)
        when(isShiftMem) {
          o.illegal := False
          o.op := DecOp.SHIFT
          o.cluster := Cluster.INT
          o.size := Size.WORD
          o.shiftOp  := opword(10 downto 9)   // same AS/LS/ROX/RO encoding as the reg form's tt
          o.shiftDir := opword(8)
          o.shiftImm := True                  // implicit count=1 (MicroOpAssembler forces imm=1)
          o.srcA := easrc; o.dst := easrc; o.dstWrites := True
          o.writesNzvc := True
          val isRoxMem = (opword(10 downto 9) === 2)
          val isRoMem = (opword(10 downto 9) === 3)
          o.writesX := !isRoMem
          o.readsX  := isRoxMem              // implicit count=1: only ROX consumes old X
        }
        // ── Bit-field register form (BFxxx Dn{#off:#wd}) — slice 1 ──────────────
        // 1110 1ooo 11 000 rrr: ss=11 (op[7:6]==3), op[11]=1 (op[11:8]>=8), mode 000
        // (Dn). bfOp = op[10:8], real 020 encoding (0=BFTST,1=BFEXTU,2=BFCHG,3=BFEXTS,
        // 4=BFCLR,5=BFFFO,6=BFSET,7=BFINS). One ALU/shifter slow µop; the assembler routes srcA=Dy
        // (op[2:0]), BFINS srcB=Dn2 (ext[14:12]), the dst (Dn2/Dy/none), and packs the
        // static offset/width from the ext word. Do=1/Dw=1 (dynamic) -> illegal (the
        // assembler gates from the ext word); mode!=0 (memory bit-field) stays illegal.
        // Flags: NZ only, V=C=0, X UNTOUCHED.
        val isBitfieldReg = opword(11) && (mode.asUInt === 0)   // ss==3 implied by this arm
        when(ss === 3 && isBitfieldReg) {
          o.illegal := False
          o.op := DecOp.BITFIELD
          o.cluster := Cluster.INT
          o.size := Size.LONG
          o.bfOp := opword(10 downto 8)
          // operands (srcA=Dy, srcB=Dn2 for BFINS, dst per bfOp) + the offset/width imm
          // are routed by the MicroOpAssembler (it owns the ext word). Mark NZ writes
          // (V=C=0 in the EU); readsX/writesX stay False (X untouched).
          o.writesNzvc := True
        }
        // ── Bit-field MEMORY load-only form (BFxxx <ea>, static) — slice 3a ──────
        // 1110 1ooo 11 mmm rrr with ss=3, op[11]=1, mode>=2 (a memory/control EA),
        // bfOp in {0=BFTST,1=BFEXTU,3=BFEXTS,5=BFFFO} (the LOAD-only ops). The RMW
        // ops (BFCHG/BFCLR/BFSET/BFINS = 2/4/6/7) at a memory EA stay illegal (slice
        // 3b). The MicroOpAssembler owns the EA crack ([load.L -> T0][opt load.B ->
        // T1][BITFIELD bfMem]); OperationDecoder only NAMES the op (BITFIELD, NZ
        // write) + marks the EA as srcA so predecode/EaDecoder frame it. The
        // assembler rejects a non-control EA (postinc/predec/Dn/An/#imm) -> illegal.
        val bfMemOp = opword(10 downto 8)
        val bfMemLoadOnly = (bfMemOp === 0) || (bfMemOp === 1) || (bfMemOp === 3) || (bfMemOp === 5)
        val isBitfieldMem = opword(11) && (mode.asUInt >= 2) && bfMemLoadOnly
        when(ss === 3 && isBitfieldMem) {
          o.illegal := False
          o.op := DecOp.BITFIELD
          o.cluster := Cluster.INT
          o.size := Size.LONG
          o.bfOp := bfMemOp
          o.srcA := easrc                  // the memory EA base (so predecode/EaDecoder frame it)
          o.writesNzvc := True             // NZ only (V=C=0, X untouched)
        }
        // ── Bit-field MEMORY RMW form (BFCHG/BFCLR/BFSET/BFINS, static) — slice 3b ──
        // 1110 1ooo 11 mmm rrr with ss=3, op[11]=1, bfOp in {2=BFCHG,4=BFCLR,6=BFSET,
        // 7=BFINS} at a CONTROL-ALTERABLE EA. The whole load-op-store sequence (3 µops
        // for a 4-byte span, 7 for a 5-byte span) EXCEEDS the 3-µop crack budget AND the
        // 5-byte span needs 3 live temps, so ALL four RMW ops route through the v2
        // microcode engine. OperationDecoder marks `microcoded` + a DEFAULT ucEntry (the
        // 4-byte entry); DecodeStage's ucBegin computes the REAL entry from the latched
        // bfNeedHi (the bf-ext word is not visible here — OperationDecoder is ext-free).
        // CONTROL-ALTERABLE only: (An) m2, (d16,An) m5, (d8,An,Xn) m6, (xxx).W 7-0,
        // (xxx).L 7-1. PC-rel (7-2/7-3) is NOT alterable (read-only) -> ILLEGAL for RMW;
        // (An)+/-(An)/Dn/An/#imm -> ILLEGAL (as in 3a). The illegal split is HERE (mode
        // test) since the assembler does not crack the microcoded RMW.
        val bfMemRmwOp = (bfMemOp === 2) || (bfMemOp === 4) || (bfMemOp === 6) || (bfMemOp === 7)
        val bfMemReg = opword(2 downto 0)
        val ctrlAlterable = (mode.asUInt === 2) || (mode.asUInt === 5) || (mode.asUInt === 6) ||
                            ((mode.asUInt === 7) && ((bfMemReg.asUInt === 0) || (bfMemReg.asUInt === 1)))
        when(ss === 3 && opword(11) && bfMemRmwOp && ctrlAlterable) {
          o.illegal := False
          o.microcoded := True
          o.ucEntry := U(Microcode.BF_RMW_4B_ENTRY, o.ucEntry.getWidth bits)  // overridden by ucBegin (needHi)
          o.op := DecOp.BITFIELD
          o.cluster := Cluster.INT
          o.size := Size.LONG
          o.bfOp := bfMemOp
          o.writesNzvc := True             // NZ only (V=C=0, X untouched)
        }
        // A RMW bit-field at a NON-control-alterable EA (PC-rel/(An)+/-(An)/Dn/An/#imm)
        // stays ILLEGAL (the default `o.illegal` from illegalDefault is True; the
        // microcoded arm above did not fire, so no override — vector-4 illegal).
      }
      // ---- OR/SUB/CMP/AND/ADD (1ooo ... ) ----
      is(0x8, 0x9, 0xB, 0xC, 0xD) {
        val opmode = opword(8 downto 6)
        val isRmw  = (opmode === 4 || opmode === 5 || opmode === 6)
        // EOR (line B, opmode 4/5/6 = .B/.W/.L): `Dn ^ <ea> -> <ea>`. The EA is the
        // DESTINATION operand (read AND written), Dn (bits 11:9) the source. Register
        // destination mode0 is the direct register form; supported alterable-memory
        // destinations are cracked into load-op-store by the assembler. An-direct
        // (mode 1) is CMPM, NOT EOR -> excluded.
        // Flags: NZ, V=C=0 (no X). srcA=EA (dst operand), srcB=Dn, dst=EA.
        val isEor = (line === 0xB) && isRmw && (opword(5 downto 3) =/= 1)
        // CMPM (Ay)+,(Ax)+ (line B, opmode 4/5/6, `opword(5 downto 3)===1` — the An-direct
        // slot isEor excludes): `1011 xxx1 ss001 yyy`, Ax=op[11:9], size=op[7:6], Ay=op[2:0].
        // Single opword, NO extension words. A new 5-row microcode entry (dual postinc LOAD
        // Ay/Ax -> CMP flags-only); microcoded ops bypass normal srcA/srcB/dst operand
        // routing (DecodeStage.scala reads ucEntry/opword directly), so none is set here.
        val isCmpm = (line === 0xB) && isRmw && (opword(5 downto 3) === 1)
        // DIVU.W (line 0x8 opmode 3) / DIVS.W (line 0x8 opmode 7): 32-bit dividend Dn
        // (bits 11:9) / 16-bit divisor EA -> Dn = {rem[31:16], q[15:0]}. BOTH DIVs are
        // line 8 (the OR group). MULU.W (line 0xC opmode 3) / MULS.W (line 0xC opmode
        // 7): 16x16 -> Dn[31:0], the full 32-bit product; N/Z from the product, V=0.
        val isDivuW = (line === 0x8) && (opmode === 3)
        val isDivsW = (line === 0x8) && (opmode === 7)
        val isMuluW = (line === 0xC) && (opmode === 3)
        val isMulsW = (line === 0xC) && (opmode === 7)
        val isMulDiv = ((line === 0x8 || line === 0xC) && (opmode === 3 || opmode === 7))
        // ADDX/SUBX register form (Dy,Dx): line D/9, RMW opmode (bit8=1, ss in .B/.W/.L),
        // EA mode field (bits 5:3) == 000 = Dn-direct. That slot is NOT a valid ADD/SUB
        // encoding (a Dn-direct RMW destination is illegal), so it IS ADDX (line D) /
        // SUBX (line 9): Dx := Dx +/- Dy +/- X. srcA = Dx (bits 11:9, read+written), srcB
        // = the EA 000yyy -> EaDecoder DATAREG Dy (the source). Reuses NEGX's X-in
        // (readsX/cmd.xIn) + old-Z (readsNzvc) for the clear-only-Z merge. The memory
        // form `-(Ay),-(Ax)` (bit3=1 -> EA mode 001) is NOT caught here and stays on the
        // RMW path where the assembler rejects An-direct -> illegal (deferred w/ MOVEM).
        val eaMode     = opword(5 downto 3)
        val isAddxSubx = isRmw && (line === 0x9 || line === 0xD) && (eaMode === B"000")
        // EXG (line C, bit8=1, reg-direct): 1100 xxx 1 ooooo yyy with ooooo in
        // {01000 (Dx,Dy), 01001 (Ax,Ay), 10001 (Dx,Ay)}. Fully cracked in the
        // MicroOpAssembler (impl A, like RTS/RTR); OperationDecoder only keeps these
        // opwords NON-illegal (a benign MOVE placeholder the assembler's isExgOp arm
        // overrides) so the assembler's `bad` does not fire. Checked BEFORE isMulDiv so an
        // EXG whose Dx field happens to be opmode 3/7 is not mis-named MULU/MULS.
        val isExg = (line === 0xC) && opword(8) &&
                    (opword(7 downto 3) === B"5'b01000" ||
                     opword(7 downto 3) === B"5'b01001" ||
                     opword(7 downto 3) === B"5'b10001")
        // ── PACK/UNPK register forms (line 8 ONLY): 1000 xxx 1 0100/1000 0 yyy + adj16 ──
        // PACK Dy,Dx,#adj: opmode 5 (bits7:5=`101`), UNPK Dy,Dx,#adj: opmode 6 (bits7:5=`110`).
        // op[5:3]=`000` selects the register form (R=op[3]=0); the memory form (op[5:3]=`001`)
        // is DEFERRED (stays illegal). Line 8 only — opmode 5/6 on line C is OR.W/OR.L.
        // Dx = op[11:9] (dst, .B merge source for PACK / .W for UNPK), Dy = op[2:0] (src).
        // adj16 is pkt.words(1), carried in `imm` (useImm=True). NO CCR effect.
        val isPackReg = (line === 0x8) && (opmode === 5) && (opword(5 downto 3) === B"3'b000")
        val isUnpkReg = (line === 0x8) && (opmode === 6) && (opword(5 downto 3) === B"3'b000")
        // ── PACK/UNPK MEMORY forms -(Ay),-(Ax),#adj (task #198): op[5:3]=`001` (An-direct
        // predec) instead of the register form's `000` — same discriminator ADDX/SUBX/BCD
        // use for their own reg-vs-mem split. A >3-µop sequence via the SAME DecodeStage
        // µcode SEQUENCER as BCD_MEM_ENTRY (Microcode.PACK_MEM_ENTRY / UNPK_MEM_ENTRY),
        // reusing the EXISTING register-form PACK/UNPK ALU compute cone (AluEuPlugin's
        // isPack/isUnpk) fed from two/one predec-loaded byte temp(s) instead of a real Dy
        // register — see Microcode.scala's PACK_MEM_ENTRY/UNPK_MEM_ENTRY header comments.
        // Predecode already frames this exact opword pattern as len=2 (opword+adj16) —
        // see PredecodeWord.scala's `isPackUnpkFrame` (framed BOTH forms from day one).
        val isPackMem = (line === 0x8) && (opmode === 5) && (opword(5 downto 3) === B"3'b001")
        val isUnpkMem = (line === 0x8) && (opmode === 6) && (opword(5 downto 3) === B"3'b001")
        // ABCD (line C) / SBCD (line 8), REGISTER form: 1xx0 xxx 1 0000 0 yyy.
        //   bit8=1 + bits7:6=00 = opmode 4 (the AND/OR-RMW band); bits 5:4=00 + bit3=0
        //   ("00000") select the DATA-register form. xxx(11:9)=Dx (dst + a source),
        //   yyy(2:0)=Dy (a source). The memory form (bit3=1 -> -(Ay),-(Ax)) fails the
        //   "00000" test and stays illegal/deferred. Checked FIRST so the AND/OR-RMW
        //   branch (which would reject this Dn-direct EA as illegal) never sees it.
        //   srcB = easrc: the EA field 000yyy -> EaDecoder DATAREG Dy (the ADDX/SUBX
        //   precedent — a fixed data-reg read of bits 2:0 without new operand plumbing).
        val isBcdReg = (line === 0x8 || line === 0xC) && opword(8) &&
                       (opword(7 downto 3) === B"5'b00000")
        // ── BCD MEMORY form -(Ay),-(Ax): line 8/C, opmode 4, EA mode field 001 ──────
        // 1xx0 xxx 1 0000 1 yyy (bit3=1). A >3-µop sequence the DecodeStage µcode
        // SEQUENCER emits ([load(Ay)->T0][Ay-=d][load(Ax)->T1][Ax-=d][BCD T1,T0->T2]
        // [store T2->(Ax)]). Marked microcoded + NON-illegal (a benign placeholder like
        // MOVEM so the assembler's `bad` never fires — the sequencer owns emission).
        // op=BCD + bcdSub + size carry the kind to Microcode.Ctx. Checked BEFORE isBcdReg.
        val isBcdMem = (line === 0x8 || line === 0xC) && opword(8) &&
                       (opword(7 downto 3) === B"5'b00001")
        // ── ADDX/SUBX MEMORY form -(Ay),-(Ax): line 9/D, opmode 4/5/6, EA mode 001 ──
        // Same µcode sequence; op=ADDX/SUBX + size (.B/.W/.L) carry the kind.
        val isAddxSubxMem = isRmw && (line === 0x9 || line === 0xD) && (eaMode === B"001")
        when(isPackReg) {
          o.illegal := False
          o.op      := DecOp.PACK
          o.size    := Size.BYTE          // .B merge: only Dx[7:0] written; Dx[31:8] preserved
          o.srcA    := dnField            // Dx (bits 11:9): .B merge source (the old Dx value)
          o.srcB    := easrc              // EA 000yyy -> DATAREG Dy (the source)
          o.dst     := dnField; o.dstWrites := True
          // adj16 in imm (pkt.words(1)) routed at assemble time; no CCR reads or writes
        } .elsewhen(isUnpkReg) {
          o.illegal := False
          o.op      := DecOp.UNPK
          o.size    := Size.WORD          // .W merge: only Dx[15:0] written; Dx[31:16] preserved
          o.srcA    := dnField            // Dx (bits 11:9): .W merge source (the old Dx value)
          o.srcB    := easrc              // EA 000yyy -> DATAREG Dy (the source)
          o.dst     := dnField; o.dstWrites := True
          // adj16 in imm (pkt.words(1)) routed at assemble time; no CCR reads or writes
        } .elsewhen(isPackMem) {
          o.illegal    := False
          o.microcoded := True
          o.ucEntry    := U(Microcode.PACK_MEM_ENTRY, o.ucEntry.getWidth bits)
          o.op         := DecOp.PACK     // carried to Microcode.Ctx.op for the compute row
          o.size       := Size.BYTE      // byte-wise predec reads/store
        } .elsewhen(isUnpkMem) {
          o.illegal    := False
          o.microcoded := True
          o.ucEntry    := U(Microcode.UNPK_MEM_ENTRY, o.ucEntry.getWidth bits)
          o.op         := DecOp.UNPK
          o.size       := Size.BYTE
        } .elsewhen(isBcdMem) {
          o.illegal := False
          o.microcoded := True
          o.ucEntry := U(Microcode.BCD_MEM_ENTRY, o.ucEntry.getWidth bits)
          o.op     := DecOp.BCD
          o.bcdSub := (line === 0x8)              // line 8 = SBCD (subtract), line C = ABCD (add)
          o.size   := Size.BYTE                    // BCD is byte-only
          o.readsNzvc := True; o.writesNzvc := True; o.readsX := True; o.writesX := True
        } .elsewhen(isAddxSubxMem) {
          o.illegal := False
          o.microcoded := True
          o.ucEntry := U(Microcode.BCD_MEM_ENTRY, o.ucEntry.getWidth bits)
          o.op   := Mux(line === 0xD, DecOp.ADDX, DecOp.SUBX)
          when(opmode === 4) { o.size := Size.BYTE }
            .elsewhen(opmode === 5) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
          o.readsNzvc := True; o.writesNzvc := True; o.readsX := True; o.writesX := True
        } .elsewhen(isBcdReg) {
          o.illegal := False
          o.op     := DecOp.BCD
          o.bcdSub := (line === 0x8)            // line 8 = SBCD (subtract), line C = ABCD (add)
          o.size   := Size.BYTE                 // BCD is byte-only
          o.srcA   := dnField                   // Dx (bits 11:9): dst operand + .B merge source
          o.srcB   := easrc                     // EA 000yyy -> DATAREG Dy (the source byte)
          o.dst    := dnField; o.dstWrites := True
          o.readsNzvc  := True                  // EU needs old Z (clear-only) + computes N/V
          o.writesNzvc := True
          o.readsX     := True                  // X-in (the carry/borrow into the add/sub)
          o.writesX    := True                  // X-out := decimal carry/borrow
        } .elsewhen(isAddxSubx) {
          o.illegal := False
          o.op   := Mux(line === 0xD, DecOp.ADDX, DecOp.SUBX)
          o.srcA := dnField      // Dx (bits 11:9), the dst operand `a` (read + written)
          o.srcB := easrc        // EA 000yyy -> EaDecoder DATAREG Dy = source `b`
          o.dst  := dnField; o.dstWrites := True
          when(opmode === 4) { o.size := Size.BYTE }
            .elsewhen(opmode === 5) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
          o.writesNzvc := True; o.writesX := True   // NZVC + X (X = carry/borrow out)
          o.readsX     := True                      // X carry/borrow IN (like NEGX)
          o.readsNzvc  := True                      // old Z for the clear-only-Z merge
        } .elsewhen(isExg) {
          // Benign placeholder: not illegal, no register effects here; the assembler
          // builds the 3 MOVE µops (regA->T0 ; regB->regA ; T0->regB) from the opword.
          o.illegal := False
          o.op := DecOp.MOVE
        } .elsewhen(isDivuW || isDivsW) {
          o.illegal := False
          o.op := DecOp.DIV
          o.cluster := Cluster.CPLX
          o.size := Size.WORD
          o.srcA := dnField               // 32-bit dividend Dn
          o.srcB := easrc                 // 16-bit divisor EA
          o.dst := dnField; o.dstWrites := True   // result -> Dn
          o.writesNzvc := True            // DIV sets N/Z/V (C=0)
          o.readsNzvc  := True            // overflow preserves old N/Z/C (Musashi: only V set)
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
        } .elsewhen(isCmpm) {
          // CMPM (Ay)+,(Ax)+ : Musashi (m68k_in.c) src=read(Ay)+ FIRST; dst=read(Ax)+
          // SECOND; res=dst-src; N/Z/V/C from res; X untouched; no register/memory write.
          o.illegal    := False
          o.op         := DecOp.CMP
          o.microcoded := True
          o.ucEntry    := U(Microcode.CMPM_ENTRY, o.ucEntry.getWidth bits)
          when(opmode === 4) { o.size := Size.BYTE }
            .elsewhen(opmode === 5) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
          o.writesNzvc := True             // NZVC from res; X untouched (the µcode compute row)
        } .elsewhen(isEor) {
          // EOR Dn,<ea>: srcA = EA (dst operand), srcB = Dn, dst = EA (same field).
          o.illegal := False
          o.op := DecOp.EOR
          o.srcA := easrc; o.srcB := dnField; o.dst := easrc; o.dstWrites := True
          when(opmode === 4) { o.size := Size.BYTE }
            .elsewhen(opmode === 5) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
          o.writesNzvc := True            // EOR sets N/Z (V=0, C=0); no X
        } .elsewhen(isRmw && (line === 0x8 || line === 0x9 || line === 0xC || line === 0xD)) {
          // ALU Dn,<ea> RMW (the memory-destination forms): opmode 4/5/6 = .B/.W/.L.
          // The EA (op[5:0]) is the DESTINATION operand (read AND written back); Dn
          // (bits 11:9) is the source. srcA = EA (dst operand / `a`), srcB = Dn, dst =
          // EA. The assembler gates a non-MEMSIMPLE EA (An / #imm / MEMCOMPLEX) to
          // illegal and cracks a MEMSIMPLE EA into [load -> T0][op -> T1][store T1].
          o.illegal := False
          switch(line) {
            is(0x8) { o.op := DecOp.OR }
            is(0x9) { o.op := DecOp.SUB }
            is(0xC) { o.op := DecOp.AND }
            is(0xD) { o.op := DecOp.ADD }
          }
          o.srcA := easrc; o.srcB := dnField; o.dst := easrc; o.dstWrites := True
          when(opmode === 4) { o.size := Size.BYTE }
            .elsewhen(opmode === 5) { o.size := Size.WORD }
            .otherwise { o.size := Size.LONG }
          when(line === 0x8 || line === 0xC) { o.writesNzvc := True }                 // OR/AND: NZ
            .otherwise { o.writesNzvc := True; o.writesX := True }                     // ADD/SUB: NZVCX
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
            // ADDA/SUBA/CMPA : srcA = An (the DESTINATION operand — the ALU computes
            // a-b, so the An must be the minuend for SUBA/CMPA), srcB = EA (the
            // source), dst An. The .W form sign-extends the 16-bit source to 32 and
            // the op runs full-32 (no partial merge; Musashi adda/suba/cmpa .W use
            // MAKE_INT_16(src) against the whole An) — the assembler marks the op
            // µop isMovea (the An-wide marker) and the ALU EU widens it.
            o.srcA := anField; o.srcB := easrc; o.dst := anField
            when(line =/= 0xB) { o.dstWrites := True }
            when(line === 0xB) { o.writesNzvc := True }  // CMPA sets flags, no write
            when(opmode === 3) { o.size := Size.WORD } .otherwise { o.size := Size.LONG }
          }
        }
      }

      // Line-1111 (top nibble 0xF): CPUSH/CINV (top byte 0xF4, see the arm's own
      // comment below), MOVE16, PFLUSH family, PTEST, and the single-literal FSF
      // carve-out are the only non-illegal encodings; everything else under line-F
      // stays illegal (falls to illegalDefault, vector 11, via MicroOpAssembler's
      // top-nibble faultVector select).
      is(0xF) {
        // ── CPUSH/CINV (line-1111, top byte 0xF4: 1111 0100 CC O SS AAA -- Task P5.1's
        // cross-checked encoding): privileged cache push/invalidate. bit[5]=1 selects
        // CPUSH (push, optionally invalidate, matching lines), bit[5]=0 selects CINV
        // (invalidate matching lines WITHOUT writeback, discarding any dirty data) --
        // see the corpus's own cpush_line_basic.s/cinv_line_basic.s header comments.
        // This arm claims the ENTIRE 0xF4xx byte unconditionally (CC/SS/AAA are not
        // gated here) -- a COMMIT-TIME SYSTEM op; serializes + advances PC; S=0 ->
        // vector-8. The real cache-maintenance effect (which lines get pushed/
        // invalidated) is wired up in Task P5.4 (DcachePlugin engine) + P5.5
        // (ExceptionUnit dispatch); this task only adds correct decode of which
        // op it is. Scope/cache-selector routing to MicroOpAssembler (the packed
        // `imm` side-channel, mirroring MOVEC's Rc-id precedent) is Task P5.3.
        val isCpushFamily = !opword(11) && opword(10) && !opword(9) && !opword(8)
        when(isCpushFamily) {
          o.illegal := False
          o.op := DecOp.MOVE
          o.size := Size.LONG
          o.sysOp := True
          o.sysKind := Mux(opword(5), SysKind.CPUSH, SysKind.CINV)
          o.sysReadDir := False
          o.dst.setNone(); o.dstWrites := False
        }
        // ── MOVE16 (Ax)+,(Ay)+ (0xF620|Ax, task #207): 68040 INTEGER cache-line-move —
        // ONLY the (Ax)+,(Ay)+ post-increment form is decoded (opword bits[2:0]=Ax; the
        // extension word's bits[14:12]=Ay). The 3 absolute-addressing forms (F600/F608/
        // F610/F618) deliberately stay on the F-line illegal default (vector 11) — see
        // the ported test's own doc comment (move16_basic.s): only the (Ax)+,(Ay)+ form
        // exists in Musashi, this project's golden lock-step oracle. A >3-µop sequence
        // (four LONG (Ax)+n -> (Ay)+n transfers using the SNAPSHOTTED ORIGINAL Ax/Ay,
        // then Ax+=16 and Ay+=16 unconditionally) — microcoded via Microcode.MOVE16_ENTRY
        // (see its own header comment for the exact row shape + why the two write-backs
        // are ordered Ax-then-Ay to get Musashi's documented Ax==Ay "+32 net" behavior
        // for free from ordinary program-order rename semantics, no special-casing).
        val isMove16 = (opword(15 downto 3) === U(0xF620 >> 3, 13 bits).asBits)
        when(isMove16) {
          o.illegal    := False
          o.microcoded := True
          o.ucEntry    := U(Microcode.MOVE16_ENTRY, o.ucEntry.getWidth bits)
          o.op         := DecOp.MOVE
          o.size       := Size.LONG
        }
        // ── PFLUSH family (0xF500-0xF51F, mode field op[5:3] in {0,1,2,3}): privileged
        // "flush ATC/TLB entries" — task #166 (ported-tests triage, cluster 9). Real
        // 68040 encoding `1111 0101 00 mmm rrr` (op[7:6]=00 fixed): mode(op[5:3])
        // 000=PFLUSHN(An) [flush non-global entries matching An], 001=PFLUSH(An)
        // [flush ALL entries matching An], 010=PFLUSHAN [flush all entries, FC
        // don't-care], 011=PFLUSHA (reg field ignored/0) [flush ALL ATC entries, both
        // address spaces]. This core's MMU has no real per-VA/per-FC selective ATC
        // tracking (DtlbPlugin/ItlbPlugin's Tlb only exposes a blanket
        // `invalidateAll`) — a "stub" MMU per the ported test's own header comment
        // ("stub MMU drops the ATC tag... no global tracking in stub"), so EVERY mode
        // in this family is treated identically: a COMMIT-TIME SYSTEM op that pulses
        // the SAME flushAll signal PFLUSHA already drives (over-invalidating relative
        // to the selective PRM semantics is always functionally SAFE, just less
        // precise — no test in this corpus checks selectivity). S=0 -> vector-8.
        // Previously only the single exact PFLUSHA opword (0xF518) was admitted;
        // PFLUSH(An)/PFLUSHN(An)/PFLUSHAN fell to the illegal default (vector 4, no
        // handler in the bare-metal ported-test harness -> permanent wild-PC HANG).
        // The register field (op[2:0]) is read as An by predecode/EaDecoder's normal
        // srcA framing is NOT needed here (no real per-VA effect) — left unread, same
        // as the pre-existing PFLUSHA treatment.
        val isPflushFamily = (opword(15 downto 6) === B"10'b1111010100") &&
                             (opword(5 downto 3).asUInt <= 3)
        when(isPflushFamily) {
          o.illegal := False
          o.op := DecOp.MOVE
          o.size := Size.LONG
          o.sysOp := True
          o.sysKind := SysKind.PFLUSHA
          o.sysReadDir := False
          o.dst.setNone(); o.dstWrites := False
        }
        // ── PTEST{R,W} (An) (0xF548-0xF54F / 0xF568-0xF56F): privileged MMU-probe —
        // task #198. Derived from the ported test's own literal opwords (ptest_w_an.s):
        // 0xF548=ptestw(a0), 0xF549=ptestw(a1), 0xF568=ptestr(a0), 0xF56A=ptestr(a2).
        // Fixed bits: [15:8]=0xF5, [7]=0, [6]=1 (this is what disambiguates it from the
        // PFLUSH family right above, which has [6]=0 over the same [15:8]=0xF5 byte),
        // [4]=0, [3]=1. Free bits: [5]=R/W direction (unused here — this stub-MMU
        // implementation treats PTESTR/PTESTW identically, matching the "MMU disabled:
        // PA=VA, R=1" case the ported test itself documents as the only one exercised),
        // [2:0]=An. A COMMIT-TIME SYSTEM op like PFLUSHA: no GPR write (the result lands
        // in MMUSR, read back separately via `movec %mmusr,Rn` — Rc id 0x805, added to
        // the MOVEC read mux in ExceptionUnit.scala). sysReadDir=False (An -> MMUSR is a
        // "write" direction in the sysOp sense, mirroring MOVE_USP's An->USP arm) — the
        // assembler overrides srcB with the REAL An bit position (op[2:0], not the
        // standard op[11:9] `anField`), same precedent as MOVE_USP/MOVEC. S=0 -> vector 8
        // (automatic: ANY sysOp head retiring at S=0 traps, no separate needsSupervisor
        // needed — see RobPlugin's sysPrivFault).
        val isPtest = (opword(15 downto 8) === B"8'hF5") && !opword(7) && opword(6) &&
                      !opword(4) && opword(3)
        when(isPtest) {
          o.illegal := False
          o.op := DecOp.MOVE
          o.size := Size.LONG
          o.sysOp := True
          o.sysKind := SysKind.PTEST
          o.sysReadDir := False
          o.srcB := anField   // placeholder; MicroOpAssembler overrides with the real op[2:0] An
          o.dst.setNone(); o.dstWrites := False
        }
        // ── FSAVE / FRESTORE (Task 11) ──────────────────────────────────────────
        // `1111 001 100 mmmrrr` = 0xF300|<ea> (opclass 100, FSAVE) and
        // `1111 001 101 mmmrrr` = 0xF340|<ea> (opclass 101, FRESTORE). Both PRIVILEGED.
        // Confirmed via `m68k-linux-gnu-as -m68040 -m68881` (F327 / F310 / F35F / F350).
        //
        // NON-OVERLAP with every other line-F carve-out in this arm: CPUSH/CINV (0xF4xx)
        // and PFLUSH/PTEST (0xF5xx) both have opword[11:9] = 010; MOVE16 (0xF620) has
        // 011; cpGEN/FSF have opword[8:6] = 000/001. This arm requires (001, 100) or
        // (001, 101), which none of them can produce -- and it is precisely the "band
        // holds FSAVE/FRESTORE, owned by Task 9/11" carve-out the cpGEN arm below
        // already documents as reserved.
        //
        // SCOPE: the register-indirect EA modes only --
        //   FSAVE    : mode 100 (-(An))  and mode 010 ((An))   [predecrement + control]
        //   FRESTORE : mode 011 ((An)+)  and mode 010 ((An))   [postincrement + control]
        // matching the architectural alterable/control restrictions on each. The
        // displacement/absolute forms (0xF338/0xF378 etc.) need a real EA computation
        // that this commit-time sysOp path has no AGU for, so they stay OUT of scope on
        // the line-F vector-11 fall-through.
        val fsvBase    = opword(11 downto 9) === B"3'b001"
        val fsvMode    = opword(5 downto 3)
        val isFsave    = fsvBase && (opword(8 downto 6) === B"3'b100") &&
                         ((fsvMode === B"3'b100") || (fsvMode === B"3'b010"))
        val isFrestore = fsvBase && (opword(8 downto 6) === B"3'b101") &&
                         ((fsvMode === B"3'b011") || (fsvMode === B"3'b010"))
        when(isFsave || isFrestore) {
          o.illegal    := False
          o.op         := DecOp.MOVE
          o.size       := Size.LONG
          o.sysOp      := True
          o.sysKind    := Mux(isFsave, SysKind.FSAVE, SysKind.FRESTORE)
          o.sysReadDir := False    // both take An as a SOURCE value (PTEST's precedent)
          o.srcA.setNone(); o.srcB.setNone()
          o.dst.setNone(); o.dstWrites := False
        }
        // ── F-line FP-GENERIC (cpGEN): `1111 001 000 mmmrrr` ────────────────────
        // Coprocessor ID 001 (the FPU) + type field 000 (the general FP instruction,
        // as opposed to 001=FScc/FDBcc/FTRAPcc, 010=FBcc.W, 011=FBcc.L, and the
        // FSAVE/FRESTORE encodings above bit 8). Verified against Musashi's own
        // dispatcher (tools/musashi/musashi/m68kfpu.c, m68040_fpu_op0: the cpGEN case
        // is `(REG_IR >> 6) & 3 == 0`, then a sub-switch on extension-word bits
        // [15:13]) -- see Task 4's encoding table for the full evidence list.
        //
        // NON-OVERLAP with the existing line-F carve-outs, checked bit-by-bit:
        //   CPUSH/CINV 0xF4xx  -> opword[11:9] = 010
        //   PFLUSH/PTEST 0xF5xx-> opword[11:9] = 010
        //   MOVE16 0xF620      -> opword[11:9] = 011
        //   FSF 0xF27F         -> opword[11:9] = 001 BUT opword[8:6] = 001 (FScc)
        // Only cpGEN is (001, 000), so this arm claims 0xF200-0xF23F and nothing else.
        // In particular it must NOT match any opword with bit 8 set -- that band holds
        // FSAVE/FRESTORE, owned by Task 9/11.
        //
        // WHAT THIS ARM CANNOT DECIDE: the operation, the FP registers, and whether an
        // <ea> is even used are all extension-word fields, and decode() sees the opword
        // only (PredecodeWord.scala:64 calls it at I-cache refill time). So this arm
        // classifies the FAMILY -- non-illegal, DecOp.FPU, Cluster.CPLX -- and
        // MicroOpAssembler refines it from pkt.words(1) or routes it to the vector-11
        // F-line trap. Until Task 6 lands, MicroOpAssembler's `fpGenBad` term faults
        // ALL of it, so this arm is behavior-neutral on its own.
        val isFpGeneric = (opword(11 downto 9) === B"3'b001") && (opword(8 downto 6) === B"3'b000")
        when(isFpGeneric) {
          o.illegal   := False
          o.fpGeneric := True
          o.op        := DecOp.FPU
          o.cluster   := Cluster.CPLX     // spec Decision 9: shared CPLX cluster, no new Cluster value
          o.size      := Size.LONG        // inert; the FP operand format lives in the ext word
          // No operand slots are named here: the EA (opword[5:0]) is meaningful ONLY for
          // the R/M=1 (extension-word bit 14) forms, and this decoder cannot see that bit.
          // The assembler routes both srcA (the int source of an FMOVE.L Dn,FPn) and the
          // FP register fields itself.
          o.srcA.setNone(); o.srcB.setNone(); o.dst.setNone(); o.dstWrites := False
          o.readsNzvc := False; o.writesNzvc := False   // FP ops touch FPCC, never the integer CCR
          o.readsX    := False; o.writesX    := False
        }
        // ── Task 6b: F-line FP-generic MEMORY-mode <ea> -> the µcode ROM ────────
        // `F<op> <mem>,FPn` (opclass 010, this task), `FMOVE FPn,<mem>` (011, store,
        // NOT this task), `FMOVE(M) <ea>,FPCR/FPSR/FPIAR` (100/101, Task 9's territory),
        // and `FMOVEM <ea>,list`/`list,<ea>` (110/111, unowned) all share this SAME
        // opword shape `0xF200|<ea>` -- the opclass field that disambiguates them lives
        // entirely in ext[15:13] (words(1)), which this decoder cannot see (Task 4's own
        // grounding: decode() runs opword-only at I-cache refill time). So this arm
        // routes the whole memory-mode-<ea> cpGEN band (opclass-agnostic) to the µcode
        // ROM with ONE shared placeholder entry; `DecodeStage`'s `ucBegin` -- which DOES
        // see the real ext word (mirrors the bit-field family's own
        // `BF_RMW_4B_ENTRY "overridden by ucBegin"` precedent above) -- does the REAL
        // opclass/format/EA-mode dispatch, rejecting every non-opclass-010 form and
        // Packed (fpSrcSpec 011) back to a genuine vector-11 trap (`FP_MEM_TRAP_ENTRY`,
        // faultUsesNextPc=True since Task 5 already frames cpGEN length).
        //
        // Gated on: cpGEN family (isFpGeneric) AND the opword's <ea> mode indicates
        // memory (mode != 0 Dn — Task 6's direct-emit INTREG path owns that; mode != 1
        // An — never a valid FP source; NOT mode 7/reg 4 #imm — Task 6's direct-emit
        // IMMEDIATE path owns that too, INCLUDING its own Packed-immediate exclusion
        // (`fpImmIsPacked`, MicroOpAssembler.scala) — routing #imm through this gate
        // would silently bypass Task 6's own already-correct immediate-form handling,
        // a real regression caught live by `FpAssembleSpec`'s existing Packed-immediate
        // and out-of-scope-forms tests). This intentionally ALSO matches the
        // opclass-011/100/101/110/111 memory forms and Packed MEMORY sources
        // (Finding 1) — ucBegin resolves the ambiguity for real once it can see
        // ext[15:13]/ext[12:10]. Reserved <ea> encodings (mode 7/reg 5..7) are NOT
        // excluded here (this decoder cannot distinguish "reserved" from "genuine
        // memory" without the ext word either) — they route through the µcode engine
        // too and are correctly rejected by `ucBegin`'s own `EaClass =/= MEMSIMPLE`
        // check (mirrors how a reserved bit-field EA is handled).
        val fpMemEaMode  = opword(5 downto 3).asUInt
        val fpMemEaReg   = opword(2 downto 0).asUInt
        val fpMemIsImmEa = (fpMemEaMode === U(7, 3 bits)) && (fpMemEaReg === U(4, 3 bits))
        val fpMemIsMemEa = isFpGeneric && (fpMemEaMode =/= U(0, 3 bits)) && (fpMemEaMode =/= U(1, 3 bits)) &&
                           !fpMemIsImmEa
        when(fpMemIsMemEa) {
          o.microcoded := True
          // Placeholder -- ucBegin ALWAYS overrides this once it reads the real ext
          // word (never actually reaches the sequencer at this value; set to the trap
          // entry itself as a safe, self-documenting default in case that invariant is
          // ever violated).
          o.ucEntry := U(Microcode.FP_MEM_TRAP_ENTRY, o.ucEntry.getWidth bits)
        }
        // FSF (xxx).L — task #180 (ported-tests triage, cluster13/exc_fsf_xxx_l_no_fline):
        // opword 0xF27F, ext1 0x0000 (the FScc "false" predicate — condition field is
        // OPER_I_16()&0x3f per Musashi's fscc(), 0 selects always-false), then a 2-word
        // abs.L address. VERIFIED via toolchain (m68k-linux-gnu-as -m68040 -m68881):
        // the CANONICAL FSF (xxx).L per the standard <ea> table (mode=111,reg=001) is
        // actually opword 0xF279 -- 0xF27F is mode=111,reg=111, a RESERVED <ea> encoding
        // under the standard MC68000PRM Table 2-4 (matches no as/objdump mnemonic either).
        // BUT this exact literal byte sequence is independently confirmed as real,
        // static Q700 boot-ROM bytes (docs/bug_b_atrap_divergence.md, ROM SHA1
        // 7a8ee468d16e64f2ad10cb8d1a45e6f07cc9e212, offset 0x4088D244) that real 68040+FPU
        // silicon executes successfully (Q700/MAME boot past this point) -- almost
        // certainly an undocumented real-hardware EA-decode quirk this project's docs
        // couldn't independently re-derive from first principles (manual bit derivation
        // "left genuine ambiguity" per the prior triage session). Since the condition is
        // always-false, FSF's ENTIRE effect is "store byte 0 at <ea>, no compute, no
        // exception, CCR unaffected" -- reusing CLR's op semantics (writes a constant 0)
        // but with writesNzvc FALSE (unlike CLR's own Dn form): real FScc never touches
        // condition codes, and this narrow single-literal-opcode carve-out doesn't
        // generalize to the rest of the FScc/FBcc/FDBcc family (still F-line vec 11).
        // MicroOpAssembler.scala substitutes a hardcoded abs.L EA field (mode7/reg1) +
        // a words-shifted-by-1 view (skipping the discarded ext1) ONLY for this exact
        // opword bit pattern -- EaDecoder.scala's shared <ea> table is untouched (mode7/
        // reg7 stays ILLEGAL for every other opcode in the ISA, zero blast radius).
        when(opword === B"16'hF27F") {
          o.illegal := False
          o.op := DecOp.CLR
          o.size := Size.BYTE
          o.srcA := easrc; o.dst := easrc
          o.dstWrites := True
          o.writesNzvc := False
        }
      }
    }
    o
  }
}
