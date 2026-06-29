package m68k040.frontend

import m68k040.cache.ChunkPredecode
import spinal.core._

object PredecodeWord {
  // `extW` = word op+1 (the EA's first ext word for an EA-FIRST op). `extW2` = word op+2
  // (the EA's first ext word for a line-0 immediate op with a .B/.W immediate, where the
  // EA ext follows the 1-word immediate). Needed to frame a 68020+ FULL-format indexed EA's
  // variable length (1 + bd + od ext words); the brief path ignores them. Callers without
  // ext words use the shorter overloads.
  def classify(op: Bits): ChunkPredecode = classify(op, B(0, 16 bits), B(0, 16 bits))
  def classify(op: Bits, extW: Bits): ChunkPredecode = classify(op, extW, B(0, 16 bits))
  def classify(op: Bits, extW: Bits, extW2: Bits): ChunkPredecode = {
    val r = ChunkPredecode()
    r.simple   := False
    r.lenWords := U(0, 3 bits)

    val cls = op(15 downto 12).asUInt

    // Full-format (bit8=1) EA extension length = 1 (ext word) + bd + od words:
    //   bd words: bits5:4 -> 00|01=0, 10=1, 11=2.  od words: bit1(present)? bit0(long)?2:1 : 0.
    // `eaW` is the EA's FIRST extension word (the word after the opword for an EA-first op).
    def fullExtLen(eaW: Bits): UInt = {
      val bdSz  = eaW(5 downto 4).asUInt
      val bdW   = Mux(bdSz === U(2, 2 bits), U(1, 3 bits),
                  Mux(bdSz === U(3, 2 bits), U(2, 3 bits), U(0, 3 bits)))
      val odW   = Mux(!eaW(1), U(0, 3 bits), Mux(eaW(0), U(2, 3 bits), U(1, 3 bits)))
      (U(1, 3 bits) + bdW + odW).resize(3)
    }

    // In-scope MEMSIMPLE RMW-DESTINATION EA: (An)=mode2 (0 ext), (An)+=mode3 (0 ext),
    // -(An)=mode4 (0 ext), (d16,An)=mode5 (1 ext), (xxx).W=mode7/reg0 (1 ext),
    // (xxx).L=mode7/reg1 (2 ext). Returns (ok, ext). The predec/postinc auto modes carry
    // no extension word (the An side-effect is folded by the assembler crack). The indexed
    // (mode 6 / 7-3) + (d16,PC) (read-only, not alterable) + #imm modes are NOT in scope
    // -> ok=False -> COMPLEX. Used to frame the mem-dest RMW lengths (so nextPc is right).
    def memDestExt(mode: UInt, reg: UInt, eaW: Bits = B(0, 16 bits)): (Bool, UInt) = {
      val ok  = Bool(); val ext = UInt(3 bits)
      ok := False; ext := U(0, 3 bits)
      switch(mode) {
        is(U(2, 3 bits)) { ok := True; ext := U(0, 3 bits) }   // (An)
        is(U(3, 3 bits)) { ok := True; ext := U(0, 3 bits) }   // (An)+ postincrement (no ext)
        is(U(4, 3 bits)) { ok := True; ext := U(0, 3 bits) }   // -(An) predecrement (no ext)
        is(U(5, 3 bits)) { ok := True; ext := U(1, 3 bits) }   // (d16,An)
        is(U(6, 3 bits)) {                                     // (d8,An,Xn) brief / full-format
          ok := True; ext := Mux(eaW(8), fullExtLen(eaW), U(1, 3 bits))
        }
        is(U(7, 3 bits)) {
          switch(reg) {
            is(U(0, 3 bits)) { ok := True; ext := U(1, 3 bits) }   // (xxx).W
            is(U(1, 3 bits)) { ok := True; ext := U(2, 3 bits) }   // (xxx).L
            // NOTE: (d8,PC,Xn) (reg 3) is PC-relative => NOT alterable => NOT a mem-dest
            // (read-only). Left rejected (complex -> assembler illegal). Source-only.
            default { ok := False }
          }
        }
      }
      (ok, ext)
    }

    // EA extension words; returns (ok, ext). ok=False => complex. `eaW` = the EA's first
    // ext word (op+1 for an EA-first op) — used ONLY to frame a full-format indexed EA.
    def eaExt(mode: UInt, reg: UInt, sizeL: Bool, allowImm: Boolean, eaW: Bits): (Bool, UInt) = {
      val ok  = Bool()
      val ext = UInt(3 bits)
      ok  := True
      ext := U(0, 3 bits)
      switch(mode) {
        is(U(0, 3 bits), U(1, 3 bits), U(2, 3 bits), U(3, 3 bits), U(4, 3 bits)) {
          ext := U(0, 3 bits)
        }
        is(U(5, 3 bits)) {
          ext := U(1, 3 bits)
        }
        is(U(6, 3 bits)) {
          // (d8,An,Xn) brief = 1 ext word; FULL-format (bit8=1) = 1 + bd + od (1..5).
          ext := Mux(eaW(8), fullExtLen(eaW), U(1, 3 bits))
        }
        is(U(7, 3 bits)) {
          switch(reg) {
            is(U(0, 3 bits)) { ext := U(1, 3 bits) }
            is(U(1, 3 bits)) { ext := U(2, 3 bits) }
            is(U(2, 3 bits)) { ext := U(1, 3 bits) }
            is(U(3, 3 bits)) {                            // (d8,PC,Xn) brief / full-format
              ext := Mux(eaW(8), fullExtLen(eaW), U(1, 3 bits))
            }
            is(U(4, 3 bits)) {
              if (allowImm) {
                ext := Mux(sizeL, U(2, 3 bits), U(1, 3 bits))
              } else {
                ok := False
              }
            }
            default { ok := False }
          }
        }
      }
      (ok, ext)
    }

    switch(cls) {
      // Line-0 immediates: ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,<ea> + the to-CCR forms.
      // 0000 ooo0 ss mmmrrr + imm. opmode ooo (11:9) in {0=ORI,1=ANDI,2=SUBI,3=ADDI,
      // 5=EORI,6=CMPI}; bit8=0; size ss (7:6) in {00=.B,01=.W,10=.L}; imm words = 1
      // for .B/.W, 2 for .L (the imm precedes any EA ext). Data-reg dest (mode0) =>
      // 1+immWords; to-CCR (...00 111100, byte, ANDI/ORI/EORI only) => 1+1. SR / mem
      // destinations are deferred (privileged / RMW) => COMPLEX. (Mirrors PredecodeRef.)
      is(U(0, 4 bits)) {
        val opmode = op(11 downto 9).asUInt
        val bit8   = op(8)
        val ss     = op(7 downto 6).asUInt
        val mode   = op(5 downto 3).asUInt
        val reg    = op(2 downto 0).asUInt
        val isImmOp = !bit8 && (opmode === 0 || opmode === 1 || opmode === 2 ||
                                opmode === 3 || opmode === 5 || opmode === 6)
        val sizeOk   = ss =/= 3
        val immWords = Mux(ss === 2, U(2, 3 bits), U(1, 3 bits))   // .L=2, .B/.W=1
        val isToCcr  = (mode === 7) && (reg === 4) && (ss === 0)
        val ccrOk    = opmode === 0 || opmode === 1 || opmode === 5   // ANDI/ORI/EORI only
        when(isImmOp && sizeOk) {
          when(isToCcr) {
            when(ccrOk) { r.simple := True; r.lenWords := U(2, 3 bits) }   // opword + imm byte word
          } elsewhen(mode === 0) {
            r.simple := True; r.lenWords := (U(1, 3 bits) + immWords).resized   // data-reg dest
          } otherwise {
            // mem-dest RMW (ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,<ea>): opword + imm words +
            // the EA extension (imm precedes the EA ext). In-scope MEMSIMPLE dest only. For
            // a full-format dst, the EA's first ext word = op+2 when the imm is 1 word
            // (.B/.W -> extW2); a .L imm pushes it to op+3 (not visible -> brief framing).
            val immDstEaW = Mux(ss === U(2, 2 bits), B(0, 16 bits), extW2)
            val (mok, mext) = memDestExt(mode, reg, immDstEaW)
            when(mok) {
              r.simple := True; r.lenWords := (U(1, 3 bits) + immWords + mext).resized
            }
          }
          // SR dest (mode7/reg4 .W) / out-of-scope EAs -> COMPLEX (deferred)
        }
        // ── Bit ops (BTST/BCHG/BCLR/BSET) ──────────────────────────────────────
        // dynamic 0000 rrr 1 tt mmmrrr (bit8=1, NOT mode 001=MOVEP); static 0000 1000
        // tt mmmrrr (bits 11:8 == 1000) + bit-number ext word. Dn dest (mode 0) -> 1
        // (dynamic) / 2 (static, opword + bit word). Memory dest -> +EA ext (the bit
        // word precedes the EA ext for the static form). An/#imm/MEMCOMPLEX -> COMPLEX
        // (the assembler's illegal path). MOVEP (dynamic mode 001) stays COMPLEX.
        val isDynBit  = bit8 && (mode =/= U(1, 3 bits))         // exclude MOVEP
        val isStatBit = op(11 downto 8) === B"1000"            // opmode 4
        val bitBase   = Mux(isStatBit, U(2, 3 bits), U(1, 3 bits))   // +1 for the static bit word
        when(isDynBit || isStatBit) {
          when(mode === U(0, 3 bits)) {                         // Dn dest (LONG)
            r.simple := True; r.lenWords := bitBase
          } otherwise {                                         // memory dest (BYTE) -> +EA ext
            // Dynamic bit-op: EA ext = op+1 (extW). Static: a bit-number word precedes the
            // EA ext (op+1 is the bit word, EA ext = op+2 = extW2).
            val bitDstEaW = Mux(isStatBit, extW2, extW)
            val (mok, mext) = memDestExt(mode, reg, bitDstEaW)
            when(mok) { r.simple := True; r.lenWords := (bitBase + mext).resized }
          }
        }
        // ── MOVEP (0000 rrr 1 oo 001 aaa) + disp16 ──────────────────────────────
        // bit8=1 && mode==001 (the (d16,Ay) form), all 4 variants (oo in {0,1,2,3}):
        // opword + disp16 -> SIMPLE len 2. Carved out of the dyn-bit-op path (which
        // excludes mode 001). The DecodeStage MOVEP FSM owns the µop emission.
        val isMovep = bit8 && (mode === U(1, 3 bits))
        when(isMovep) { r.simple := True; r.lenWords := U(2, 3 bits) }
        // ── CMP2/CHK2 (0000 0ss0 11 mmm rrr) + ext word ────────────────────────
        // bit11==0, ss=op[10:9] (.B/.W/.L, ss=/=3), bit8==0, bits[7:6]==11, EA a
        // CONTROL mode (mode>=2; reject postinc(3)/predec(4)). len = opword + ext
        // word + the EA extension. (Does not alias the immediate path — that needs
        // bits[7:6]=/=3 — nor the bit-ops — those need bit8/bit11 set.)
        val isCmp2Chk2 = !op(11) && !bit8 && (op(7 downto 6).asUInt === U(3, 2 bits)) &&
                         (op(10 downto 9).asUInt =/= U(3, 2 bits)) && (mode >= U(2, 3 bits))
        when(isCmp2Chk2) {
          // Control modes: (An)=2, (d16,An)=5, (d8,An,Xn)=6, (xxx).W/.L=7/0,1,
          // (d16,PC)=7/2, (d8,PC,Xn)=7/3. Reject (An)+=3 / -(An)=4 (NOT control) and
          // #imm. eaExt(allowImm=false) accepts 3/4 (ext 0) but those are non-control
          // -> the assembler illegalises them (a mis-frame of a non-control EA is
          // harmless: the illegal op flushes at the faulting pc).
          val ctrlMode = (mode === U(2, 3 bits)) || (mode === U(5, 3 bits)) ||
                         (mode === U(6, 3 bits)) || (mode === U(7, 3 bits))
          // CMP2/CHK2 EA ext follows a PRECEDING ext word, so op+1 is NOT the EA ext;
          // pass 0 to keep brief framing (full-format here is out of scope).
          val (ok, e) = eaExt(mode, reg, sizeL = False, allowImm = false, eaW = B(0, 16 bits))
          when(ok && ctrlMode) {
            r.simple   := True
            r.lenWords := (U(2, 3 bits) + e).resized   // opword + ext word + EA ext
          }
        }
        // ── CAS / CAS2 (020+ atomic compare-and-swap) ──────────────────────────
        // CAS  `0000 1ss0 11 mmm rrr` + 1 ext word + the EA ext: op[15:11]=00001,
        // bit8=0, op[7:6]=11, op[10:9]=size (=/=00). len = opword + 1 ext + EA ext.
        // The EA ext FOLLOWS the (single) compare/update ext word, so op+1 is NOT the
        // EA ext -> pass eaW=0 (brief framing; full-format CAS EA is out of scope and
        // would mis-frame, but the in-scope memory-alterable EAs (An/(d16,An)/(d8,An,Xn)/
        // (xxx).W/.L) all fit the brief lengths). CAS2 `...111100` is a FIXED 3-word
        // instruction (opword + 2 ext) — no EA. (Disjoint from CMP2/CHK2: that needs
        // op[11]=0; CAS needs op[11]=1.)
        val isCasFamily = (op(15 downto 11) === B"00001") && !bit8 &&
                          (op(7 downto 6) === B"11") && (op(10 downto 9) =/= B"00")
        val isCas2Pre   = op(5 downto 0) === B"111100"
        when(isCasFamily) {
          when(isCas2Pre) {
            r.simple := True; r.lenWords := U(3, 3 bits)        // opword + 2 ext words
          } otherwise {
            // memory-ALTERABLE EA (Musashi `A+-DXWL...`): (An)/(An)+/-(An)/(d16,An)/
            // (d8,An,Xn)/(xxx).W/.L. (An)+/-(An) carry 0 EA ext (like (An)). Reject
            // Dn/An/PC-rel/#imm -> stays COMPLEX (decode illegalises it).
            val casOk = (mode === U(2, 3 bits)) || (mode === U(3, 3 bits)) || (mode === U(4, 3 bits)) ||
                        (mode === U(5, 3 bits)) || (mode === U(6, 3 bits)) ||
                        ((mode === U(7, 3 bits)) && ((reg === U(0, 3 bits)) || (reg === U(1, 3 bits))))
            val (ok, e) = eaExt(mode, reg, sizeL = False, allowImm = false, eaW = B(0, 16 bits))
            when(ok && casOk) {
              r.simple   := True
              r.lenWords := (U(2, 3 bits) + e).resized          // opword + 1 ext + EA ext
            }
          }
        }
        // ── MOVES (010+ PRIVILEGED) `0000 1110 ss mmm rrr` + 1 ext + EA ext ────
        // op[15:8]=0x0E, op[7:6]=ss in {00,01,10} (11 is CAS). The EA ext FOLLOWS the
        // (single) dr/A-D/reg ext word, so op+1 is NOT the EA ext -> pass eaW=0 (brief
        // framing; the in-scope memory-alterable EAs all fit the brief lengths). len =
        // opword + 1 ext + EA ext. (An)+/-(An) carry 0 EA ext (like (An)). Reject
        // Dn/An/PC-rel/#imm -> stays COMPLEX (decode illegalises it). Same EA mask as CAS.
        val isMovesFamily = (op(15 downto 8) === B"00001110") && (op(7 downto 6) =/= B"11")
        when(isMovesFamily) {
          val movesOk = (mode === U(2, 3 bits)) || (mode === U(3, 3 bits)) || (mode === U(4, 3 bits)) ||
                        (mode === U(5, 3 bits)) || (mode === U(6, 3 bits)) ||
                        ((mode === U(7, 3 bits)) && ((reg === U(0, 3 bits)) || (reg === U(1, 3 bits))))
          val (ok, e) = eaExt(mode, reg, sizeL = False, allowImm = false, eaW = B(0, 16 bits))
          when(ok && movesOk) {
            r.simple   := True
            r.lenWords := (U(2, 3 bits) + e).resized            // opword + 1 ext + EA ext
          }
        }
      }

      // MOVE.B / MOVE.L / MOVE.W
      is(U(1, 4 bits), U(2, 4 bits), U(3, 4 bits)) {
        val sizeL   = cls === U(2, 4 bits)
        val srcMode = op(5 downto 3).asUInt
        val srcReg  = op(2 downto 0).asUInt
        val dstMode = op(8 downto 6).asUInt
        val dstReg  = op(11 downto 9).asUInt

        // MOVE source EA is the FIRST ext word -> extW is its ext word (full-format OK).
        val (sOk, sExt) = eaExt(srcMode, srcReg, sizeL, allowImm = true, eaW = extW)

        // dst: modes 0-5 via eaExt(allowImm=false), mode 6 = brief indexed (1 ext word),
        // mode 7 only reg0/reg1, else complex. mode 7-3 ((d8,PC,Xn)) + 7-2 ((d16,PC)) are
        // PC-relative => NOT alterable => NOT a MOVE destination -> complex (assembler
        // illegal). The MOVE dst inline path (NOT eaExt) owns this since eaExt is the
        // source variant (which DOES accept (d16,PC)/(d8,PC,Xn) as read-only sources).
        val dOk  = Bool()
        val dExt = UInt(3 bits)
        dOk  := True
        dExt := U(0, 3 bits)

        // The dst EA's first ext word is op+1 only when the src ext = 0 (else brief).
        val dstEaW0 = Mux(sExt === U(0, 3 bits), extW, B(0, 16 bits))
        when(dstMode === U(7, 3 bits)) {
          switch(dstReg) {
            is(U(0, 3 bits)) { dExt := U(1, 3 bits) }
            is(U(1, 3 bits)) { dExt := U(2, 3 bits) }
            default          { dOk  := False }
          }
        } elsewhen(dstMode === U(6, 3 bits)) {
          // (d8,An,Xn) brief = 1 ext word; FULL-format dst (bit8=1) = 1 + bd + od.
          dExt := Mux(dstEaW0(8), fullExtLen(dstEaW0), U(1, 3 bits))
        } otherwise {
          val (o, e) = eaExt(dstMode, dstReg, sizeL, allowImm = false, eaW = dstEaW0)
          dOk  := o
          dExt := e
        }

        // MOVE (incl. mem-to-mem): both EAs in-scope MEMSIMPLE/reg/imm -> SIMPLE. The
        // assembler cracks mem-to-mem into [load src -> T0][store T0 -> dst] (+ folded
        // An auto-updates). lenWords = opword + src ext + dst ext (predec/postinc add 0).
        when(sOk && dOk) {
          r.simple   := True
          r.lenWords := (U(1, 3 bits) + sExt + dExt).resized
        }
      }

      // TRAP #n (0x4E4x) / TRAPV (0x4E76): single-word instructions. Predecode them
      // as SIMPLE length-1 so the decoder computes the correct nextPc (= pc+2), which
      // TRAP/TRAPV stack as the (not-restartable) return PC. Other line-4 opcodes
      // (RTE/illegal/etc.) stay complex (their stacked PC is the FAULTING PC = pc, so
      // their nextPc is unused, and RTE's commit PC comes from the popped frame).
      is(U(4, 4 bits)) {
        val isTrap  = op(15 downto 4) === B"12'h4E4"   // 0x4E4x
        val isTrapv = op === B"16'h4E76"
        // RTS (0x4E75) / RTR (0x4E77): single-word RETURN instructions cracked into a
        // pop + an indirect branch. Predecode SIMPLE length-1 so the aligner frames the
        // next instruction correctly (the RTS/RTR commit PC is the redirect target, so
        // nextPc itself is unused — but the length must be right for fetch framing).
        val isRts   = op === B"16'h4E75"
        val isRtr   = op === B"16'h4E77"
        when(isTrap || isTrapv || isRts || isRtr) {
          r.simple   := True
          r.lenWords := U(1, 3 bits)
        }
        // LINK An,#disp16 (0100 1110 0101 0aaa, op[15:4]==0x4E5, op[3]=0): opword + a
        // disp16 extension word -> SIMPLE len 2. UNLK An (op[3]=1): single word -> len 1.
        // (The cracker decodes both in the assembler, like RTS/JSR; predecode only frames.)
        val isLink = (op(15 downto 4) === B"12'h4E5") && !op(3)
        val isUnlk = (op(15 downto 4) === B"12'h4E5") &&  op(3)
        when(isLink) { r.simple := True; r.lenWords := U(2, 3 bits) }
        when(isUnlk) { r.simple := True; r.lenWords := U(1, 3 bits) }
        // ── Privileged commit-time SYSTEM ops (frame the length so nextPc is right) ─
        // MOVE to SR (0100 0110 11 mmmrrr): opword + the source EA's ext words (the
        // EA is a .W source). MOVE USP (0100 1110 0110 d rrr = 0x4E6x): single word.
        // MOVEC (0x4E7A/0x4E7B): opword + 1 ext word {A/D|reg#|Rc}. These commit-time
        // sysOps re-fetch younger work after the serializing retire, so a precise nextPc
        // matters (the redirect target = nextPc).
        val isMoveToSr = op(15 downto 6) === B"10'b0100011011"
        when(isMoveToSr) {
          val srcMode = op(5 downto 3).asUInt
          val srcReg  = op(2 downto 0).asUInt
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = False, allowImm = true, eaW = extW)  // .W source EA
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized }
        }
        val isMoveUsp = op(15 downto 4) === B"12'h4E6"
        when(isMoveUsp) { r.simple := True; r.lenWords := U(1, 3 bits) }
        val isMovec = op(15 downto 1) === B"15'b010011100111101"   // 0x4E7A / 0x4E7B
        when(isMovec) { r.simple := True; r.lenWords := U(2, 3 bits) }   // opword + ext word
        // RTD (0x4E74) + disp16: opword + 1 disp word -> SIMPLE len 2 (RTS-with-dealloc).
        val isRtd = op === B"16'h4E74"
        when(isRtd) { r.simple := True; r.lenWords := U(2, 3 bits) }
        // RESET (0x4E70): single-word privileged sysOp -> SIMPLE len 1 (nextPc = pc+2, the
        // redirect target after the serializing retire).
        val isReset = op === B"16'h4E70"
        when(isReset) { r.simple := True; r.lenWords := U(1, 3 bits) }
        // STOP (0x4E72) + imm16: opword + 1 imm word -> SIMPLE len 2 (nextPc = pc+4).
        val isStop = op === B"16'h4E72"
        when(isStop) { r.simple := True; r.lenWords := U(2, 3 bits) }
        // CHK.W/CHK.L (0100 ddd 1 s 0 mmmrrr): bit8=1, bit6=0. The bound is an EA
        // source (sizeL = .L when bit7=0). 1 opword + the EA extension words.
        val isChk = op(8) && !op(6)
        when(isChk) {
          val sizeL   = !op(7)                       // CHK.L when bit7=0
          val srcMode = op(5 downto 3).asUInt
          val srcReg  = op(2 downto 0).asUInt
          val (ok, e) = eaExt(srcMode, srcReg, sizeL, allowImm = true, eaW = extW)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
          }
        }
        // DIVU.L/DIVS.L (0100 1100 01 mmmrrr) / MULU.L/MULS.L (0100 1100 00 mmmrrr):
        // opword + 1 extension word (the Dl/Dh/signed/size word) + the 32-bit EA
        // extension. Same framing for both (the ext word + a 32-bit source EA).
        val isDivL = op(15 downto 6) === B"10'b0100110001"
        val isMulL = op(15 downto 6) === B"10'b0100110000"
        when(isDivL || isMulL) {
          val srcMode = op(5 downto 3).asUInt
          val srcReg  = op(2 downto 0).asUInt
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = True, allowImm = true, eaW = B(0, 16 bits))  // EA ext follows the Dl/Dh word
          when(ok) {
            r.simple   := True
            r.lenWords := (U(2, 3 bits) + e).resized   // opword + ext word + EA ext
          }
        }
        // JMP (0100111011 mmmrrr) / JSR (0100111010 mmmrrr): a computed-target branch
        // to the EA *address*. 1 opword + the control-EA extension words. Control modes
        // only — (An)/(d16,An)/(xxx).W/.L/(d16,PC); reg-direct / imm / (An)+ / -(An) /
        // indexed are NOT control modes (eaExt returns ok=False or an EA that the
        // assembler rejects -> illegal). allowImm=false so #imm is NOT a valid mode.
        // Line-4 single-operand DATA-register family (the unary group), all single-word
        // -> SIMPLE len1. CLR/NEG/NEGX/NOT/TST (0100 oooo ss 000rrr, oooo in {0,2,4,6,A},
        // ss != 11, mode 000=Dn) + SWAP (0x4840-47) / EXT.W (0x4880-87) / EXT.L
        // (0x48C0-C7) / EXTB.L (0x49C0-C7) / TAS (0x4AC0-C7). Memory-dest forms (mode !=
        // 000) are the deferred RMW slice -> COMPLEX (the assembler's illegal path).
        // bit8=0 for all EXCEPT EXTB.L (matched by its own pattern).
        val u4o    = op(11 downto 8).asUInt
        val u4ss   = op(7 downto 6).asUInt
        val u4mode = op(5 downto 3).asUInt
        val isUnaryArith = !op(8) && (u4ss =/= U(3, 2 bits)) && (u4mode === U(0, 3 bits)) &&
                           (u4o === U(0, 4 bits) || u4o === U(2, 4 bits) || u4o === U(4, 4 bits) ||
                            u4o === U(6, 4 bits) || u4o === U(0xA, 4 bits))
        val isSwap  = op(15 downto 3) === B"13'b0100100001000"   // 0x4840-47
        val isExtW  = op(15 downto 3) === B"13'b0100100010000"   // 0x4880-87
        val isExtL  = op(15 downto 3) === B"13'b0100100011000"   // 0x48C0-C7
        val isExtbL = op(15 downto 3) === B"13'b0100100111000"   // 0x49C0-C7
        val isTas   = op(15 downto 3) === B"13'b0100101011000"   // 0x4AC0-C7
        when(isUnaryArith || isSwap || isExtW || isExtL || isExtbL || isTas) {
          r.simple   := True
          r.lenWords := U(1, 3 bits)
        }
        // CLR/NEG/NEGX/NOT/TST <ea> mem-dest (the RMW crack): mode != 000, ss != 11,
        // oooo in {0,2,4,6,A}, bit8=0. In-scope MEMSIMPLE dest -> opword + EA ext.
        // SWAP/EXT/TAS are Dn-only (mode 000, matched above); TAS-mem deferred.
        val isUnaryMem = !op(8) && (u4ss =/= U(3, 2 bits)) && (u4mode =/= U(0, 3 bits)) &&
                         (u4o === U(0, 4 bits) || u4o === U(2, 4 bits) || u4o === U(4, 4 bits) ||
                          u4o === U(6, 4 bits) || u4o === U(0xA, 4 bits))
        when(isUnaryMem) {
          val (mok, mext) = memDestExt(u4mode, op(2 downto 0).asUInt, extW)   // EA is op+1
          when(mok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + mext).resized
          }
        }
        // MOVEM (0100 1 d 001 s mmmrrr) + 16-bit register-mask ext word: a 2+-word
        // instruction = opword + mask + the EA's OWN extension words (the mask precedes the
        // EA ext). In-scope modes: (An)/(An)+/-(An) add 0, (d16,An)/(xxx).W/(d16,PC) add 1,
        // (xxx).L adds 2 -> lenWords = 1 (opword) + 1 (mask) + EA ext. Direction (bit10)
        // chooses store (control-alterable + -(An)) vs load (control + (An)+ + (d16,PC)/
        // (xxx)); for FRAMING the length only depends on the EA's ext-word count, so a single
        // memDestExt-style table covers store modes and the load adds (An)+ / (d16,PC).
        // Indexed (mode 6 / 7-3), reg-direct, #imm -> NOT in scope -> COMPLEX (never enter
        // the FSM). bit8=0, bits9:7=001, bit11=1 (does not alias CHK / the unary group).
        val isMovem = op(11) && (op(9 downto 7) === B"001")
        when(isMovem) {
          val mmMode = op(5 downto 3).asUInt
          val mmReg  = op(2 downto 0).asUInt
          val mmDir  = op(10)                         // 0 store / 1 load
          val mmOk   = Bool(); val mmExt = UInt(3 bits)
          mmOk := False; mmExt := U(0, 3 bits)
          switch(mmMode) {
            is(U(2, 3 bits)) { mmOk := True; mmExt := U(0, 3 bits) }                    // (An) (store+load)
            is(U(3, 3 bits)) { mmOk := mmDir;  mmExt := U(0, 3 bits) }                   // (An)+ LOAD only
            is(U(4, 3 bits)) { mmOk := !mmDir; mmExt := U(0, 3 bits) }                   // -(An) STORE only
            is(U(5, 3 bits)) { mmOk := True; mmExt := U(1, 3 bits) }                     // (d16,An)
            is(U(7, 3 bits)) {
              switch(mmReg) {
                is(U(0, 3 bits)) { mmOk := True; mmExt := U(1, 3 bits) }                 // (xxx).W
                is(U(1, 3 bits)) { mmOk := True; mmExt := U(2, 3 bits) }                 // (xxx).L
                is(U(2, 3 bits)) { mmOk := mmDir; mmExt := U(1, 3 bits) }                // (d16,PC) LOAD only
                default          { mmOk := False }
              }
            }
            default { mmOk := False }
          }
          when(mmOk) {
            r.simple   := True
            r.lenWords := (U(2, 3 bits) + mmExt).resized   // opword + mask + EA ext
          }
        }
        val isJmp = op(15 downto 6) === B"10'b0100111011"
        val isJsr = op(15 downto 6) === B"10'b0100111010"
        when(isJmp || isJsr) {
          val srcMode = op(5 downto 3).asUInt
          val srcReg  = op(2 downto 0).asUInt
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = False, allowImm = false, eaW = extW)
          // Reg-direct (modes 0,1) and (An)+/-(An) (modes 3,4) are NOT control modes;
          // eaExt accepts them (ext 0) but they are illegal for JMP/JSR. Restrict to
          // the in-scope control modes so predecode frames the right length AND a
          // non-control EA stays complex (-> the assembler's illegal path).
          val ctrlMode = (srcMode === U(2, 3 bits)) || (srcMode === U(5, 3 bits)) ||
                         (srcMode === U(7, 3 bits))
          when(ok && ctrlMode) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized   // opword + EA ext
          }
        }
        // ── LEA An,<ea> (0100 An 1 11 mmmrrr): bit8=1, bits7:6=11, mode>=2. Control EA,
        // opword + EA ext. (isUnary's EXTB.L is mode 000 -> excluded by mode>=2.) An
        // out-of-scope EA still frames its len via eaExt; the assembler faults the EA.
        val isLea = op(8) && (op(7 downto 6) === B"11") && (op(5 downto 3).asUInt >= 2)
        when(isLea) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = false, eaW = extW)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized }
        }
        // ── PEA <ea> (0100 1000 01 mmmrrr): control EA, opword + EA ext.
        val isPea = op(15 downto 6) === B"10'b0100100001"
        when(isPea) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = false, eaW = extW)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized }
        }
        // ── MOVE from SR (0x40C0) / from CCR (0x42C0): SR/CCR -> EA (.W), data EA.
        val isMoveFromSr  = op(15 downto 6) === B"10'b0100000011"
        val isMoveFromCcr = op(15 downto 6) === B"10'b0100001011"
        when(isMoveFromSr || isMoveFromCcr) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = false, eaW = extW)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized }
        }
        // ── MOVE to CCR (0x44C0): EA(.W) -> CCR, data EA incl #imm.
        val isMoveToCcr = op(15 downto 6) === B"10'b0100010011"
        when(isMoveToCcr) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = true, eaW = extW)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized }
        }
      }

      // Line-5: ADDQ/SUBQ (0101 ddd q ss mmmrrr, ss != 11) + Scc/DBcc (ss == 11).
      //   ss != 11 (ADDQ/SUBQ): dest EA mode 0/1 (Dn/An) -> SIMPLE len1; memory dest is
      //     the deferred RMW -> COMPLEX (the assembler's illegal path).
      //   ss == 11: mode 001 -> DBcc (opword + disp16) -> SIMPLE len2; mode 000 -> Scc Dn
      //     -> SIMPLE len1; other modes (memory Scc / TRAPcc) deferred -> COMPLEX.
      is(U(5, 4 bits)) {
        val ss   = op(7 downto 6).asUInt
        val mode = op(5 downto 3).asUInt
        when(ss =/= U(3, 2 bits)) {
          when(mode === U(0, 3 bits) || mode === U(1, 3 bits)) {   // ADDQ/SUBQ Dn / An
            r.simple := True; r.lenWords := U(1, 3 bits)
          } otherwise {                                            // ADDQ/SUBQ #n,<ea> mem-dest (RMW)
            val (mok, mext) = memDestExt(mode, op(2 downto 0).asUInt, extW)   // EA is op+1
            when(mok) { r.simple := True; r.lenWords := (U(1, 3 bits) + mext).resized }
          }
        } otherwise {                                              // ss == 11
          when(mode === U(1, 3 bits)) {                            // DBcc + disp16
            r.simple := True; r.lenWords := U(2, 3 bits)
          } elsewhen(mode === U(0, 3 bits)) {                      // Scc Dn
            r.simple := True; r.lenWords := U(1, 3 bits)
          } elsewhen(mode === U(7, 3 bits)) {                      // TRAPcc (mode 7)
            val ttt = op(2 downto 0).asUInt
            when(ttt === U(4, 3 bits)) {                           // TRAPcc (no operand, 1 word)
              r.simple := True; r.lenWords := U(1, 3 bits)
            } elsewhen(ttt === U(2, 3 bits)) {                     // TRAPcc.W (#data16, 2 words)
              r.simple := True; r.lenWords := U(2, 3 bits)
            } elsewhen(ttt === U(3, 3 bits)) {                     // TRAPcc.L (#data32, 3 words)
              r.simple := True; r.lenWords := U(3, 3 bits)
            }
            // other ttt -> COMPLEX (stays ILLEGAL)
          }
        }
      }

      // MOVEQ
      is(U(7, 4 bits)) {
        when(op(8) === False) {
          r.simple   := True
          r.lenWords := U(1, 3 bits)
        }
      }

      // Bcc / BRA / BSR
      is(U(6, 4 bits)) {
        val d8 = op(7 downto 0).asUInt
        r.simple := True
        when(d8 === U(0x00, 8 bits)) {
          r.lenWords := U(2, 3 bits)
        } elsewhen(d8 === U(0xff, 8 bits)) {
          r.lenWords := U(3, 3 bits)
        } otherwise {
          r.lenWords := U(1, 3 bits)
        }
      }

      // OR/SUB/AND/ADD (and their ADDA/SUBA/ORA/ANDA variants)
      is(U(8, 4 bits), U(9, 4 bits), U(0xC, 4 bits), U(0xD, 4 bits)) {
        val opmode  = op(8 downto 6).asUInt
        val srcMode = op(5 downto 3).asUInt
        val srcReg  = op(2 downto 0).asUInt
        // classes 8(OR-group)/C(AND-group) opmode 3/7 = DIVU/DIVS/MULU/MULS.
        //   BOTH DIVs are line 8: DIVU.W = opmode 3, DIVS.W = opmode 7.
        //   BOTH MULs are line C: MULU.W = opmode 3, MULS.W = opmode 7.
        //   All four are 1 opword + the 16-bit EA extension (the multiplier/divisor).
        val isDivuW = (cls === U(8, 4 bits)) && (opmode === U(3, 3 bits))
        val isDivsW = (cls === U(8, 4 bits)) && (opmode === U(7, 3 bits))
        // MUL.W's multiplier is a DATA addressing mode — An-direct (srcMode 1) is NOT
        // legal (matches decode + the 040 ISA), so it stays COMPLEX (-> illegal), never
        // framed as a simple 1-word MUL. (This also keeps free-running garbage with an
        // An-direct EA from framing as a live multi-cycle MUL.)
        val mulEaOk = srcMode =/= U(1, 3 bits)
        val isMuluW = (cls === U(0xC, 4 bits)) && (opmode === U(3, 3 bits)) && mulEaOk
        val isMulsW = (cls === U(0xC, 4 bits)) && (opmode === U(7, 3 bits)) && mulEaOk
        val isMulDiv = (cls === U(8, 4 bits) || cls === U(0xC, 4 bits)) &&
                       (opmode === U(3, 3 bits) || opmode === U(7, 3 bits))
        // EXG (line C, bit8=1): 1100 xxx 1 ooooo yyy with ooooo in {01000,01001,10001}
        // (EXG Dx,Dy / Ax,Ay / Dx,Ay). A single-word reg-reg swap (no extension) -> simple,
        // len=1. EXG's opmode(8:6) is 5 (01000/01001) or 6 (10001), so it would otherwise
        // hit the AND-RMW memDestExt path below with a reg-direct EA -> NOT MEMSIMPLE ->
        // complex -> wrong length. Frame it BEFORE that band (it dominates the chain here).
        val isExg = (cls === U(0xC, 4 bits)) && op(8) &&
                    (op(7 downto 3) === B"5'b01000" ||   // EXG Dx,Dy
                     op(7 downto 3) === B"5'b01001" ||   // EXG Ax,Ay
                     op(7 downto 3) === B"5'b10001")      // EXG Dx,Ay
        when(isExg) {
          r.simple   := True
          r.lenWords := U(1, 3 bits)
        } elsewhen(isDivuW || isDivsW || isMuluW || isMulsW) {
          // 16-bit multiplier/divisor EA (sizeL = false: word operand size for #imm).
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = False, allowImm = true, eaW = extW)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
          }
        } elsewhen(opmode =/= U(4, 3 bits) && opmode =/= U(5, 3 bits) && opmode =/= U(6, 3 bits) && !isMulDiv) {
          val sizeL = (opmode === U(2, 3 bits)) || (opmode === U(7, 3 bits))
          val (ok, e) = eaExt(srcMode, srcReg, sizeL, allowImm = false, eaW = extW)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
          }
        } elsewhen(opmode === U(4, 3 bits) || opmode === U(5, 3 bits) || opmode === U(6, 3 bits)) {
          // ADDX/SUBX register form (mode 000) AND -(Ay),-(Ax) MEMORY form (mode 001,
          // MICROCODED): both are single 1-word ops. The reg form is Dx := Dx +/- Dy +/- X
          // (1 µop); the mem form's 6 µops come from the DecodeStage µcode SEQUENCER, so
          // predecode only needs LEN=1. Mode 001 is An-direct (never a valid ADD/SUB-to-mem
          // dst) so it is unambiguously the X-mem form. Frame both (else memDestExt rejects
          // mode 000/001 -> unframed -> nextPc=pc -> the front-end stalls).
          val isAddxSubxReg = (cls === U(9, 4 bits) || cls === U(0xD, 4 bits)) &&
                              (srcMode === U(0, 3 bits) || srcMode === U(1, 3 bits))
          // ABCD (line C) / SBCD (line 8): opmode 4. Register form (mode 000) + -(Ay),-(Ax)
          // MEMORY form (mode 001, microcoded). Both single 1-word ops (the mem 6 µops come
          // from the µcode sequencer). Mode 001 An-direct = the X-mem form; frame both.
          val isBcdReg = (cls === U(8, 4 bits) || cls === U(0xC, 4 bits)) &&
                         (opmode === U(4, 3 bits)) && (srcMode === U(0, 3 bits) || srcMode === U(1, 3 bits))
          // PACK (line 8, opmode 5) / UNPK (line 8, opmode 6): opword + 16-bit adj extension.
          // Frame BOTH the register form (srcMode 000) AND the deferred memory form (srcMode 001)
          // as len=2 so the front-end doesn't stall on the memory form (decode illegalises it).
          // Line C with opmode 5/6 is OR.W/.L, NOT PACK/UNPK -> exclude line C here.
          val isPackUnpkFrame = (cls === U(8, 4 bits)) &&
                                (opmode === U(5, 3 bits) || opmode === U(6, 3 bits)) &&
                                (srcMode === U(0, 3 bits) || srcMode === U(1, 3 bits))
          when(isAddxSubxReg || isBcdReg) {
            r.simple   := True
            r.lenWords := U(1, 3 bits)
          } elsewhen(isPackUnpkFrame) {
            r.simple   := True
            r.lenWords := U(2, 3 bits)   // opword + adj16 extension word
          } otherwise {
            // ALU Dn,<ea> RMW (opmode 4/5/6 = .B/.W/.L mem-dest): opword + EA ext. The EA
            // MUST be a MEMSIMPLE alterable-memory mode (the assembler illegalises Dn/An/
            // MEMCOMPLEX). (DIVU/MULU are opmode 3/7, excluded.)
            val (mok, mext) = memDestExt(srcMode, srcReg, extW)   // EA is op+1
            when(mok) {
              r.simple   := True
              r.lenWords := (U(1, 3 bits) + mext).resized
            }
          }
        }
      }

      // CMP / EOR
      is(U(0xB, 4 bits)) {
        val opmode  = op(8 downto 6).asUInt
        val srcMode = op(5 downto 3).asUInt
        val srcReg  = op(2 downto 0).asUInt
        val isCmp   = (opmode === U(0, 3 bits)) || (opmode === U(1, 3 bits)) ||
                      (opmode === U(2, 3 bits)) || (opmode === U(3, 3 bits)) ||
                      (opmode === U(7, 3 bits))
        // EOR (opmode 4/5/6): EA is the DESTINATION (read+written). Register (data-reg,
        // mode0) dest -> simple len1; An-direct (CMPM) and memory-dest (RMW) -> COMPLEX.
        val isEor   = (opmode === U(4, 3 bits)) || (opmode === U(5, 3 bits)) ||
                      (opmode === U(6, 3 bits))
        when(isCmp) {
          val sizeL = (opmode === U(2, 3 bits)) || (opmode === U(7, 3 bits))
          val (ok, e) = eaExt(srcMode, srcReg, sizeL, allowImm = false, eaW = extW)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
          }
        } elsewhen(isEor && (srcMode === U(0, 3 bits))) {
          r.simple   := True
          r.lenWords := U(1, 3 bits)                  // EOR Dn,Dm (register dest)
        } elsewhen(isEor) {
          // EOR Dn,<ea> mem-dest (RMW): opword + EA ext. In-scope MEMSIMPLE dest only;
          // An-direct (CMPM) / MEMCOMPLEX -> COMPLEX (the assembler's illegal path).
          val (mok, mext) = memDestExt(srcMode, srcReg, extW)   // EA is op+1
          when(mok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + mext).resized
          }
        }
      }

      // Line-E register-form shifts/rotates (1110 ccc d ss i tt rrr): single-word,
      // no extension -> SIMPLE len 1. ss=11 is the MEMORY single-bit form (1110 ccc d
      // 11 mmmrrr, shift <ea> by 1) which is the deferred RMW slice -> COMPLEX.
      is(U(0xE, 4 bits)) {
        val ss = op(7 downto 6).asUInt
        when(ss =/= U(3, 2 bits)) {
          r.simple   := True
          r.lenWords := U(1, 3 bits)
        }
        // Bit-field register form (BFxxx Dn{...}): op[11:8]>=8 (op[11]=1), op[7:6]==3,
        // mode 000 (op[5:3]==0). opword + the bit-field extension word -> SIMPLE len 2.
        // (ss=11 with op[11]=0 = the deferred memory single-bit shift, or mode!=0 = a
        // memory bit-field, both stay COMPLEX -> the assembler's illegal path.)
        val isBitfieldReg = op(11) && (ss === U(3, 2 bits)) && (op(5 downto 3).asUInt === U(0, 3 bits))
        when(isBitfieldReg) {
          r.simple   := True
          r.lenWords := U(2, 3 bits)
        }
        // Bit-field MEMORY form (BFxxx <ea>): op[11]=1, op[7:6]==3, mode>=2 (memory EA).
        // len = opword + bf-ext word + the EA's OWN ext words (per mode). The bf-ext word
        // precedes the EA ext (same shape as CMP2/CHK2 -> +2 base). Slice 3a framed only
        // the LOAD-only ops {0,1,3,5}; slice 3b adds the RMW ops {2,4,6,7} (microcoded) —
        // predecode computes only LENGTH (identical for load-only and RMW), so frame ALL
        // EIGHT bfOps. The LEGALITY split (PC-rel illegal for RMW, control-alterable only)
        // is enforced in OperationDecoder, NOT here.
        val bfMemMode = op(5 downto 3).asUInt
        val isBitfieldMem = op(11) && (ss === U(3, 2 bits)) && (bfMemMode >= 2)
        when(isBitfieldMem) {
          // The bit-field EA ext follows the bf-ext word, so op+1 is NOT the EA ext.
          val (ok, e) = eaExt(bfMemMode, op(2 downto 0).asUInt, sizeL = False, allowImm = false, eaW = B(0, 16 bits))
          when(ok) {
            r.simple   := True
            r.lenWords := (U(2, 3 bits) + e).resized   // opword + bf-ext + EA ext
          }
        }
      }

      default { /* complex: r stays simple=False, lenWords=0 */ }
    }
    r
  }
}
