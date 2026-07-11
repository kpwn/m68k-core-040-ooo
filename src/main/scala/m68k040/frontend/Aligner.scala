package m68k040.frontend

import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.lib._

object Aligner {
  val WINDOW = 10

  case class Result() extends Bundle {
    val slot0      = DecodePacket()
    val slot0Valid = Bool()
    val slot1      = DecodePacket()
    val slot1Valid = Bool()
    val shiftWords = UInt(4 bits)
    val stall      = Bool()
    val complex    = Bool()
  }

  def align(headPc: UInt, words: Vec[Bits], preds: Vec[ChunkPredecode], avail: UInt): Result = {
    val r = Result()

    // Default-assign all packet fields to don't-care first
    r.slot0.assignDontCare()
    r.slot1.assignDontCare()
    // Prediction defaults: NOT predicted. FetchAlign OVERRIDES predTaken/predTarget on
    // the predicted-taken branch's slot after the aligner runs (so this stays a concrete
    // default, not a don't-care a sim poke / FetchAlign drive can't see).
    r.slot0.predTaken := False; r.slot0.predTarget := U(0, 32 bits)
    r.slot1.predTaken := False; r.slot1.predTarget := U(0, 32 bits)
    // gshare carry-down defaults (slice 3): not a gshare-predicted conditional. FetchAlign
    // OVERRIDES phtValid/phtIndex on the emitted conditional's slot0 after the aligner runs.
    r.slot0.phtValid := False; r.slot0.phtIndex := U(0, 11 bits)
    r.slot1.phtValid := False; r.slot1.phtIndex := U(0, 11 bits)

    // Default control signals
    r.slot0Valid  := False
    r.slot1Valid  := False
    r.shiftWords  := 0
    r.stall       := True
    r.complex     := False

    val p0 = preds(0)
    val L0 = p0.lenWords  // UInt(4 bits)

    when(avail === 0) {
      // keep defaults: stall, nothing valid
    } .elsewhen(!p0.simple) {
      // Complex head: emit complex packet for slot0
      r.slot0.pc        := headPc
      for (i <- 0 until WINDOW) {
        r.slot0.words(i) := words(i)
      }
      // wordCount = min(avail, WINDOW)
      r.slot0.wordCount := (avail > U(WINDOW, 4 bits)).mux(U(WINDOW, 4 bits), avail.resize(4))
      r.slot0.simple    := False
      r.slot0.complex   := True
      r.slot0.lenWords  := 0
      r.slot0.fault     := False

      r.slot0Valid  := True
      r.slot1Valid  := False
      r.shiftWords  := 0
      r.stall       := True
      r.complex     := True
    } .otherwise {
      // p0.simple is true
      when(avail < L0) {
        // Stall: insufficient words for head instruction
        // keep defaults
      } .otherwise {
        // Slot0 = simple packet
        r.slot0.pc       := headPc
        for (i <- 0 until WINDOW) {
          when(U(i) < L0) {
            r.slot0.words(i) := words(i)
          } .otherwise {
            r.slot0.words(i) := 0
          }
        }
        r.slot0.wordCount := L0
        r.slot0.simple    := True
        r.slot0.complex   := False
        r.slot0.lenWords  := L0
        r.slot0.fault     := False

        r.slot0Valid := True
        r.stall      := False

        // Try to fill slot1
        val p1 = preds(L0.resize(4))
        val L1 = p1.lenWords

        // L0/L1 are each up to WINDOW (10); +^ width-extends by 1 bit regardless of operand
        // width, so the sum (max 10+10=20) safely fits the 5-bit resize. slot1Ok additionally
        // requires the COMBINED length to fit within one WINDOW's worth of visible words
        // (else slot1's words weren't actually all present in `words`/`preds`, both sized
        // WINDOW) — gated on WINDOW itself, not a bare coincidental constant.
        val L0L1     = (L0 +^ L1).resize(5)
        val slot1Ok  = p1.simple && (avail.resize(5) >= L0L1) && (L0L1 <= U(WINDOW, 5 bits))

        when(slot1Ok) {
          // Slot1 = simple packet
          r.slot1.pc := headPc + (L0.resize(32) |<< 1)
          for (i <- 0 until WINDOW) {
            // `words` is a WINDOW(10)-element Vec, whose dynamic index must be EXACTLY
            // log2Up(WINDOW)=4 bits wide (SpinalHDL errors on an over-wide Vec index) —
            // so resize(4), not the full +^ width. Safe: slot1Ok already guarantees
            // L0+L1<=WINDOW, and this index is only ever CONSUMED (below) when i<L1, so
            // the real max is L0+i <= L0+L1-1 <= WINDOW-1, which fits in 4 bits without
            // truncation; the (i>=L1) high-`i` iterations are don't-care (discarded below).
            val idx = (L0 +^ U(i)).resize(4)
            when(U(i) < L1) {
              r.slot1.words(i) := words(idx)
            } .otherwise {
              r.slot1.words(i) := 0
            }
          }
          r.slot1.wordCount := L1
          r.slot1.simple    := True
          r.slot1.lenWords  := L1
          r.slot1.complex   := False
          r.slot1.fault     := False

          r.slot1Valid  := True
          r.shiftWords  := L0L1.resize(4)
        } .otherwise {
          r.slot1Valid  := False
          r.shiftWords  := L0.resize(4)
        }
      }
    }

    r
  }
}
