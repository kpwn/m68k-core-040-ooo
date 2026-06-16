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
        // TAS Dn (0100 1010 11 000 rrr, op[15:3]==0x0958): N/Z from Dn[7:0]; Dn[7]:=1.
        when(opword(15 downto 3) === B"13'b0100101011000") {
          o.illegal := False
          o.op := DecOp.TAS; o.size := Size.BYTE
          o.srcA := easrc; o.dst := easrc; o.dstWrites := True
          o.writesNzvc := True
        }
        // MOVEM (0100 1 d 001 s mmmrrr) + 16-bit register-mask ext word: bit11=1, bit10=d
        // (0 store / 1 load), bits 9:7=001, bit6=s (0 .W / 1 .L). The DecodeStage micro-
        // sequencer FSM owns the µop emission (reads movem/movemDir/movemSizeLong + the EA
        // + the mask); OperationDecoder only marks it NON-illegal (a benign MOVE placeholder
        // like EXG so the assembler's `bad` never fires — the FSM gates the normal crack off
        // when active). The EA mode field DISAMBIGUATES from EXT.W (0x4880, mode 000) /
        // EXT.L (0x48C0, mode 000), which share bit11=1 & bits9:7=001 but use Dn-direct
        // (mode 0): a real MOVEM EA is a MEMORY mode (>=2), so EXCLUDE reg-direct modes 0/1.
        // (Indexed mode 6 / 7-3 stay framed COMPLEX by predecode -> never enter the FSM.)
        val mmMode4 = opword(5 downto 3)
        when(opword(11) && (opword(9 downto 7) === B"001") && (mmMode4.asUInt >= 2)) {
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
        val mode = opword(5 downto 3)
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
        } .elsewhen(isBcdMem) {
          o.illegal := False
          o.microcoded := True
          o.ucEntry := U(Microcode.BCD_MEM_ENTRY, 4 bits)
          o.op     := DecOp.BCD
          o.bcdSub := (line === 0x8)              // line 8 = SBCD (subtract), line C = ABCD (add)
          o.size   := Size.BYTE                    // BCD is byte-only
          o.readsNzvc := True; o.writesNzvc := True; o.readsX := True; o.writesX := True
        } .elsewhen(isAddxSubxMem) {
          o.illegal := False
          o.microcoded := True
          o.ucEntry := U(Microcode.BCD_MEM_ENTRY, 4 bits)
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
