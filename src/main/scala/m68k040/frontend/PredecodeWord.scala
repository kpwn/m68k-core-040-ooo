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
        when(isTrap || isTrapv) {
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
        //   BOTH DIVs are line 8: DIVU.W = opmode 3, DIVS.W = opmode 7. They ARE
        //   handled now (1 opword + the 16-bit-divisor EA extension). BOTH MULs are
        //   line C (opmode 3/7) -> stay complex (separate slice).
        val isDivuW = (cls === U(8, 4 bits)) && (opmode === U(3, 3 bits))
        val isDivsW = (cls === U(8, 4 bits)) && (opmode === U(7, 3 bits))
        val isMulDiv = (cls === U(8, 4 bits) || cls === U(0xC, 4 bits)) &&
                       (opmode === U(3, 3 bits) || opmode === U(7, 3 bits))
        when(isDivuW || isDivsW) {
          // 16-bit divisor EA (sizeL = false: word operand size for #imm extension).
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
