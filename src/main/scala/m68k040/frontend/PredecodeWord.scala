package m68k040.frontend

import m68k040.cache.ChunkPredecode
import spinal.core._

object PredecodeWord {
  def classify(op: Bits): ChunkPredecode = {
    val r = ChunkPredecode()
    r.simple   := False
    r.lenWords := U(0, 3 bits)

    val cls = op(15 downto 12).asUInt

    // EA extension words; returns (ok, ext). ok=False => complex.
    def eaExt(mode: UInt, reg: UInt, sizeL: Bool, allowImm: Boolean): (Bool, UInt) = {
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
          ok := False
        }
        is(U(7, 3 bits)) {
          switch(reg) {
            is(U(0, 3 bits)) { ext := U(1, 3 bits) }
            is(U(1, 3 bits)) { ext := U(2, 3 bits) }
            is(U(2, 3 bits)) { ext := U(1, 3 bits) }
            is(U(3, 3 bits)) { ok := False }
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
          }
          // else (mem dest / SR) -> COMPLEX (deferred)
        }
      }

      // MOVE.B / MOVE.L / MOVE.W
      is(U(1, 4 bits), U(2, 4 bits), U(3, 4 bits)) {
        val sizeL   = cls === U(2, 4 bits)
        val srcMode = op(5 downto 3).asUInt
        val srcReg  = op(2 downto 0).asUInt
        val dstMode = op(8 downto 6).asUInt
        val dstReg  = op(11 downto 9).asUInt

        val (sOk, sExt) = eaExt(srcMode, srcReg, sizeL, allowImm = true)

        // dst: modes 0-5 via eaExt(allowImm=false), mode 7 only reg0/reg1, else complex
        val dOk  = Bool()
        val dExt = UInt(3 bits)
        dOk  := True
        dExt := U(0, 3 bits)

        when(dstMode === U(7, 3 bits)) {
          switch(dstReg) {
            is(U(0, 3 bits)) { dExt := U(1, 3 bits) }
            is(U(1, 3 bits)) { dExt := U(2, 3 bits) }
            default          { dOk  := False }
          }
        } elsewhen(dstMode === U(6, 3 bits)) {
          dOk := False
        } otherwise {
          val (o, e) = eaExt(dstMode, dstReg, sizeL, allowImm = false)
          dOk  := o
          dExt := e
        }

        // mem-to-mem check: srcMem = mode > 1; dstMem = mode != 1 && mode > 1
        val srcMem = srcMode > U(1, 3 bits)
        val dstMem = (dstMode =/= U(1, 3 bits)) && (dstMode > U(1, 3 bits))

        when(sOk && dOk && !(srcMem && dstMem)) {
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
        // CHK.W/CHK.L (0100 ddd 1 s 0 mmmrrr): bit8=1, bit6=0. The bound is an EA
        // source (sizeL = .L when bit7=0). 1 opword + the EA extension words.
        val isChk = op(8) && !op(6)
        when(isChk) {
          val sizeL   = !op(7)                       // CHK.L when bit7=0
          val srcMode = op(5 downto 3).asUInt
          val srcReg  = op(2 downto 0).asUInt
          val (ok, e) = eaExt(srcMode, srcReg, sizeL, allowImm = true)
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
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = True, allowImm = true)  // 32-bit source
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
        val isJmp = op(15 downto 6) === B"10'b0100111011"
        val isJsr = op(15 downto 6) === B"10'b0100111010"
        when(isJmp || isJsr) {
          val srcMode = op(5 downto 3).asUInt
          val srcReg  = op(2 downto 0).asUInt
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = False, allowImm = false)
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
        when(isDivuW || isDivsW || isMuluW || isMulsW) {
          // 16-bit multiplier/divisor EA (sizeL = false: word operand size for #imm).
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = False, allowImm = true)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
          }
        } elsewhen(opmode =/= U(4, 3 bits) && opmode =/= U(5, 3 bits) && opmode =/= U(6, 3 bits) && !isMulDiv) {
          val sizeL = (opmode === U(2, 3 bits)) || (opmode === U(7, 3 bits))
          val (ok, e) = eaExt(srcMode, srcReg, sizeL, allowImm = false)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
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
        when(isCmp) {
          val sizeL = (opmode === U(2, 3 bits)) || (opmode === U(7, 3 bits))
          val (ok, e) = eaExt(srcMode, srcReg, sizeL, allowImm = false)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
          }
        }
      }

      default { /* complex: r stays simple=False, lenWords=0 */ }
    }
    r
  }
}
