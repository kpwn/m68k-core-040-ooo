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
    val L0 = p0.lenWords  // UInt(3 bits)

    when(avail === 0) {
      // keep defaults: stall, nothing valid
    } .elsewhen(!p0.simple) {
      // Complex head: emit complex packet for slot0
      r.slot0.pc        := headPc
      for (i <- 0 until 5) {
        r.slot0.words(i) := words(i)
      }
      // wordCount = min(avail, 5)
      r.slot0.wordCount := (avail > 5).mux(U(5, 3 bits), avail.resize(3))
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
        for (i <- 0 until 5) {
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

        val L0L1     = (L0 +^ L1).resize(5)   // 4-bit sum (max 5+5=10, fits in 5 bits)
        val slot1Ok  = p1.simple && (avail.resize(5) >= L0L1) && (L0L1 <= U(10))

        when(slot1Ok) {
          // Slot1 = simple packet
          r.slot1.pc := headPc + (L0.resize(32) |<< 1)
          for (i <- 0 until 5) {
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
