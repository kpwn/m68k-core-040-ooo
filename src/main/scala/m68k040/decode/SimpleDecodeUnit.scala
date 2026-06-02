package m68k040.decode

import m68k040.frontend.DecodePacket
import m68k040.isa.{Cluster, Size}
import spinal.core._

/** Pure combinational decode for "simple" (register/immediate) DecodePackets. */
object SimpleDecodeUnit {
  def decode(pkt: DecodePacket): DecodedUop = {
    val uop = DecodedUop()

    // ---------- defaults ----------
    uop.valid         := pkt.valid
    uop.pc            := pkt.pc
    uop.op            := DecOp.ILLEGAL
    uop.cluster       := Cluster.INT
    uop.size          := Size.WORD
    uop.srcAReg       := 0
    uop.srcAValid     := False
    uop.srcBReg       := 0
    uop.srcBValid     := False
    uop.dstReg        := 0
    uop.dstValid      := False
    uop.useImm        := False
    uop.imm           := B(0, 32 bits)
    uop.readsNzvc     := False
    uop.readsX        := False
    uop.writesNzvc    := False
    uop.writesX       := False
    uop.isBranch      := False
    uop.cond          := B(0, 4 bits)
    uop.branchDisp    := B(0, 32 bits)
    uop.unimplemented := False

    // ---------- non-simple → ILLEGAL/unimplemented ----------
    when(!pkt.simple) {
      uop.op            := DecOp.ILLEGAL
      uop.unimplemented := True
    } otherwise {
      val op   = pkt.words(0)
      val cls  = op(15 downto 12)

      // ----------------------------------------------------------------
      // cls 0x7 — MOVEQ
      // ----------------------------------------------------------------
      when(cls === 0x7) {
        when(op(8) === False) {
          uop.op        := DecOp.MOVE
          uop.size      := Size.LONG
          uop.dstReg    := op(11 downto 9).asUInt.resized
          uop.dstValid  := True
          uop.useImm    := True
          uop.imm       := op(7 downto 0).asSInt.resize(32).asBits
          uop.writesNzvc := True
        } otherwise {
          // op(8)==1 → ILLEGAL/unimplemented
          uop.op            := DecOp.ILLEGAL
          uop.unimplemented := True
        }
      }

      // ----------------------------------------------------------------
      // cls 1/2/3 — MOVE.B/.L/.W
      // ----------------------------------------------------------------
      .elsewhen(cls === 0x1 || cls === 0x2 || cls === 0x3) {
        val srcMode = op(5 downto 3)
        val srcReg  = op(2 downto 0)
        val dstMode = op(8 downto 6)
        // dstReg field (bits 11-9) — as UInt for arithmetic
        val dstRegField = op(11 downto 9).asUInt

        uop.op := DecOp.MOVE

        // size
        when(cls === 0x1) {
          uop.size := Size.BYTE
        } .elsewhen(cls === 0x3) {
          uop.size := Size.WORD
        } .otherwise {
          uop.size := Size.LONG
        }

        // source — the ALU MOVE path computes result = src2 (= srcB), so a
        // register-source MOVE must put the source operand in srcB (NOT srcA).
        when(srcMode === 0) {
          // Dn
          uop.srcBReg   := srcReg.asUInt.resized
          uop.srcBValid := True
        } .elsewhen(srcMode === 1) {
          // An
          uop.srcBReg   := (8 + srcReg.asUInt).resized
          uop.srcBValid := True
        } .elsewhen(srcMode === 7 && srcReg === 4) {
          // #imm
          uop.useImm := True
          when(cls === 0x2) {
            // LONG: two extension words
            uop.imm := pkt.words(1) ## pkt.words(2)
          } .otherwise {
            uop.imm := pkt.words(1).asSInt.resize(32).asBits
          }
        } .otherwise {
          uop.unimplemented := True
        }

        // destination
        when(dstMode === 0) {
          // Dn → sets flags
          uop.dstReg     := dstRegField.resized
          uop.dstValid   := True
          uop.writesNzvc := True
        } .elsewhen(dstMode === 1) {
          // An (MOVEA) → no flags
          uop.dstReg   := (8 + dstRegField).resized
          uop.dstValid := True
        } .otherwise {
          uop.unimplemented := True
        }
      }

      // ----------------------------------------------------------------
      // cls 0x6 — Bcc/BSR/BRA
      // ----------------------------------------------------------------
      .elsewhen(cls === 0x6) {
        val disp8 = op(7 downto 0)
        val cond4 = op(11 downto 8)

        uop.op       := DecOp.BRANCH
        uop.isBranch := True
        uop.cond     := cond4

        when(disp8 === 0x00) {
          // word displacement in extension word
          uop.branchDisp := pkt.words(1).asSInt.resize(32).asBits
        } .elsewhen(disp8 === M"11111111") {
          // long displacement: words(1)##words(2)
          uop.branchDisp := pkt.words(1) ## pkt.words(2)
        } .otherwise {
          // short byte displacement
          uop.branchDisp := disp8.asSInt.resize(32).asBits
        }

        // readsNzvc if cond >= 2
        uop.readsNzvc := (cond4.asUInt >= 2)
      }

      // ----------------------------------------------------------------
      // cls 0x8(OR), 0x9(SUB), 0xB(CMP), 0xC(AND), 0xD(ADD)
      // ----------------------------------------------------------------
      .elsewhen(cls === 0x8 || cls === 0x9 || cls === 0xB || cls === 0xC || cls === 0xD) {
        val opmode = op(8 downto 6)
        val eaMode = op(5 downto 3)
        val eaReg  = op(2 downto 0)
        val dn     = op(11 downto 9).asUInt

        // EA must be register (mode 0 or 1)
        val eaIsReg = (eaMode === 0 || eaMode === 1)

        when(!eaIsReg) {
          uop.unimplemented := True
        } .otherwise {
          // Dn→EA RMW forms: opmode 4,5,6
          // Also for cls 8(OR) and C(AND): opmode 3 and 7 are MUL/DIV → unimplemented
          val isRmw = (opmode === 4 || opmode === 5 || opmode === 6)
          val isMulDiv = ((cls === 0x8 || cls === 0xC) && (opmode === 3 || opmode === 7))

          when(isRmw || isMulDiv) {
            uop.unimplemented := True
          } .otherwise {
            // compute eaRegFull: mode==1 means An (8+reg), mode==0 means Dn
            val eaRegFull = UInt(4 bits)
            when(eaMode === 1) {
              eaRegFull := (8 + eaReg.asUInt).resized
            } .otherwise {
              eaRegFull := eaReg.asUInt.resized
            }

            // Set op
            switch(cls) {
              is(0x8) { uop.op := DecOp.OR  }
              is(0x9) { uop.op := DecOp.SUB }
              is(0xB) { uop.op := DecOp.CMP }
              is(0xC) { uop.op := DecOp.AND }
              is(0xD) { uop.op := DecOp.ADD }
            }

            // opmode 0,1,2 → EA→Dn. The ALU datapath computes src1 - src2 (src1 =
            // destination operand) and MOVE-style result = src2, so srcA must be
            // the DESTINATION (Dn) and srcB the source (EA): SUB/CMP need Dn - EA,
            // not EA - Dn. (ADD/AND/OR are commutative so order is irrelevant for
            // them, which previously masked this in 2-byte ADD-only checks.)
            when(opmode === 0 || opmode === 1 || opmode === 2) {
              uop.srcAReg   := dn.resized
              uop.srcAValid := True
              uop.srcBReg   := eaRegFull
              uop.srcBValid := True
              uop.dstReg    := dn.resized

              when(cls =/= 0xB) { uop.dstValid := True }

              when(opmode === 0) { uop.size := Size.BYTE }
              .elsewhen(opmode === 1) { uop.size := Size.WORD }
              .otherwise { uop.size := Size.LONG }

              // flags
              when(cls === 0xD || cls === 0x9) {
                // ADD/SUB
                uop.writesNzvc := True
                uop.writesX    := True
              } .elsewhen(cls === 0xC || cls === 0x8) {
                // AND/OR
                uop.writesNzvc := True
              } .elsewhen(cls === 0xB) {
                // CMP
                uop.writesNzvc := True
              }
            }

            // opmode 3,7 → ADDA/SUBA/CMPA (EA→An)
            .elsewhen(opmode === 3 || opmode === 7) {
              uop.srcAReg   := eaRegFull
              uop.srcAValid := True
              uop.srcBReg   := (8 + dn).resized
              uop.srcBValid := True
              uop.dstReg    := (8 + dn).resized

              when(cls =/= 0xB) { uop.dstValid := True }
              // CMPA: !dstValid (no write), but writesNzvc
              when(cls === 0xB) { uop.writesNzvc := True }
              // ADDA/SUBA: no flags
              // size: 3→WORD, 7→LONG
              when(opmode === 3) { uop.size := Size.WORD }
              .otherwise { uop.size := Size.LONG }
            }
          }
        }
      }

      // ----------------------------------------------------------------
      // else → ILLEGAL/unimplemented
      // ----------------------------------------------------------------
      .otherwise {
        uop.op            := DecOp.ILLEGAL
        uop.unimplemented := True
      }
    }

    uop
  }
}
