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

    // task #202 (I-cache-line-boundary predecode gap): `preds(0)` was baked ONCE, per
    // 64-byte line, at IcachePlugin REFILL time — it could not see past that line, so an
    // EA whose brief-vs-full-format framing needed a lookahead word beyond the boundary
    // was GUESSED (`ambiguousLine` marks this; see ChunkPredecode's doc comment). By the
    // time this instruction is the buffer HEAD here, the front-end's independent
    // multi-outstanding fetch pipeline has very likely ALREADY pulled the next line's
    // words into this SAME buffer too — `words`/`avail` are PC-relative, not
    // line-relative, so they transparently span physical I-cache lines. Re-run the exact
    // same classify() logic against the LIVE buffered words, gated on how many of ITS OWN
    // lookahead words `avail` actually covers, and prefer that live answer whenever the
    // baked one was a guess. If `avail` is still too small to resolve it for real, the
    // live reclass reports `ambiguousLine=True` again (byte-identical guess) — the stall
    // check below treats that as "not enough data yet" (same shape as `avail < L0`), so
    // the aligner just waits: fetch keeps running independently of a stalled decode (see
    // FetchAlignPlugin's `ic.cmd.valid`, not gated on this stall), so `avail` keeps
    // growing until it genuinely resolves — no new hang, bounded by HEAD_WORDS=10 >> the
    // <=3-word lookahead any single case needs. Selected by a single bit (`ambiguousLine`
    // is False for the overwhelming majority of instructions), but see the commit
    // message / task202 report for the synth-gate verdict on whether instantiating a 2nd
    // classify() here is FMax-safe on this front-end-critical path.
    val p0Live = PredecodeWord.classify(words(0), words(1), words(2), words(3),
      extWValid = avail >= U(2, 4 bits), extW2Valid = avail >= U(3, 4 bits), extW3Valid = avail >= U(4, 4 bits))
    val p0 = Mux(preds(0).ambiguousLine, p0Live, preds(0))
    val L0 = p0.lenWords  // UInt(4 bits)

    when(avail === 0) {
      // keep defaults: stall, nothing valid
    } .elsewhen(p0.ambiguousLine) {
      // Still can't resolve for real (avail hasn't grown enough yet) -- wait rather than
      // emit a possibly-wrong guess. Identical shape to the `avail < L0` stall below.
      // keep defaults: stall, nothing valid
    } .elsewhen(!p0.simple && (avail < U(WINDOW, 4 bits))) {
      // task #204 (both-sides-memory-indirect MOVE investigation): a complex packet used
      // to be emitted IMMEDIATELY on whatever `avail` happened to be this cycle, instead of
      // waiting for the full lookahead window -- DecodeStage's own "the FULL up-to-10-word
      // aligner window is always resident" claim (see its ucMoveRealLenWords/ucMoveLenKnown
      // comment) was only true BY LUCK: decode is usually busy enough for `avail` to have
      // already ramped up to WINDOW by the time it gets around to consuming a complex
      // packet, but a complex mem-indirect MOVE reached VERY early (e.g. right after boot,
      // few outstanding fetches yet) can be latched with `wordCount` as small as 2 -- and
      // PipeStage.scala's 1-deep skid buffer ALWAYS grabs into an empty slot regardless of
      // downstream readiness (`slotFree = !valid || out.ready`), so there is no way for
      // DecodeStage to defer that first latch itself. A both-mem-indirect full-format MOVE
      // whose dst extension words are still beyond the small `avail` window reads GARBAGE
      // at `ucDstEaWords`, silently misclassifying the dst as non-mem-indirect (bypassing
      // MI_MOVE_BOTH_MI_ENTRY/MI_MOVE_BOTH_MI_ILLEGAL_ENTRY both) -- observed directly via
      // PORTED_TRACE_MI on move_l_memind_to_memind (avail=2 at the complex head, needs 5).
      // FIX: simply wait for `avail>=WINDOW` before ever emitting a complex packet at all
      // -- mirrors the pre-existing `avail < L0` stall the SIMPLE-packet arm below already
      // uses, and makes the "full window always resident" invariant every downstream
      // consumer (DecodeStage's bit-field/CAS/MOVES/mem-indirect families) already assumes
      // ACTUALLY hold, instead of holding by coincidence. Zero regression risk for any
      // currently-passing complex-packet case: DecodeStage's own comment already notes
      // "every currently passing complex case observed in this corpus had wordCount==10"
      // -- i.e. no passing test relies on a sub-WINDOW `avail` snapshot. `avail` is driven
      // by InstructionBuffer's independent multi-outstanding fetch pipeline (unrelated to
      // whether THIS packet is consumed) and caps at WINDOW itself, so this cannot
      // deadlock in this simulator (memory reads never structurally fail — see the
      // `SparseMemory` model), only add a few extra stall cycles the very first time a
      // complex packet is reached early after a redirect/boot.
      // keep defaults: stall, nothing valid (identical shape to the `avail < L0` stall
      // below and to the `avail === 0`/`ambiguousLine` stalls above)
    } .elsewhen(!p0.simple) {
      // Complex head: emit complex packet for slot0 (avail >= WINDOW guaranteed here)
      r.slot0.pc        := headPc
      for (i <- 0 until WINDOW) {
        r.slot0.words(i) := words(i)
      }
      // wordCount = min(avail, WINDOW) = WINDOW (avail>=WINDOW guaranteed by the gate above)
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
