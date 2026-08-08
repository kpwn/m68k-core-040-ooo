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
    // FMax "Lever D" (2026-08-08): the RAW `L0` (slot0's predecoded lenWords), exported
    // UNCONDITIONALLY — outside the arm mux-chain every other Result field goes through.
    // It is the BTB's slot-1 late-select index: `slot1.pc == slot0.pc + 2*slot1Sel` by
    // construction, so the BTB can read all 9 `slot1Sel` candidates speculatively off the
    // registered `decodePc` and consume `slot1Sel` only at the final mux, instead of
    // needing it to form the read ADDRESS. Deliberately NOT `slot0.lenWords` (which is
    // the same value in every arm that can set `slot1Valid`, but reaches the consumer one
    // arm-mux later — the whole point of this lever is to keep this signal's arc as short
    // as it possibly can be). See `Btb.scala`'s `spec2*` block for the full derivation.
    val slot1Sel   = UInt(4 bits)
  }

  /** `p0LiveReg`: the REGISTERED live re-classification of the buffer head (see the task
    * #202 comment block below, and FetchAlignPlugin's `p0LiveReg` for the mechanism +
    * its head-window-change invalidation). Guaranteed by construction to be EITHER
    * bit-identical to a same-cycle `PredecodeWord.classify()` of THIS cycle's
    * `words`/`avail`, OR `ambiguousLine=True` ("not resolved yet" -> stall). */
  def align(headPc: UInt, words: Vec[Bits], preds: Vec[ChunkPredecode], avail: UInt,
            p0LiveReg: ChunkPredecode): Result = {
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
    // is False for the overwhelming majority of instructions).
    //
    // FMax closure slice 1 (2026-08-07): that live re-classify is NO LONGER computed here.
    // It lives in `FetchAlignPlugin` and is REGISTERED (`p0LiveReg`, passed in) rather than
    // consumed same-cycle. Rationale: STA had to charge the FULL depth of a second real
    // `classify()` decoder against `L0`'s arrival time for EVERY instruction (the Mux's
    // select is a runtime bit STA cannot prove almost-always-false), and `L0` feeds nearly
    // the whole rest of the front-end critical path (slot-1 PC, the realignment barrel, the
    // BTB query, `effShift`, `decodePcNext`, the IBuf shift amount). A direct attribution
    // measurement (not a guess) put this at ~1.6ns of this core's post-route FMax
    // shortfall. See
    // `docs/superpowers/specs/2026-08-07-fmax-frontend-p0live-pipelining-design.md`.
    // ARCHITECTURALLY this is behaviour-preserving: FetchAlignPlugin invalidates
    // `p0LiveReg` (forces `ambiguousLine=True`) on EVERY change of the classify inputs
    // (`words(0..3)` / the avail-derived valid flags), so the value seen here is either
    // exactly what a same-cycle classify() would have produced, or "not resolved yet" —
    // which lands in the identical `.elsewhen(p0.ambiguousLine)` stall arm below. The only
    // observable difference is at most a couple of extra stall cycles on the already-rare,
    // already-multi-cycle-tolerant ambiguous-head case.
    val p0 = Mux(preds(0).ambiguousLine, p0LiveReg, preds(0))
    val L0 = p0.lenWords  // UInt(4 bits)

    // FMax "Lever D": export L0 raw (see `Result.slot1Sel`). Assigned exactly once, here,
    // OUTSIDE the arm chain below — so no `when` arm ever adds a mux level to it. It is
    // only ever CONSUMED (by the BTB slot-1 late select) when `slot1Valid` is set, i.e.
    // inside the `slot1Ok` arm, where `slot1.pc == headPc + 2*L0` holds by construction.
    r.slot1Sel := L0

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
      // FMax Lever B: see the identical assignment in the simple-slot0 arm below for the
      // full "why preds(0), not p0" derivation. A complex packet's `size` is unused by
      // `computeOffload`'s consumers in practice, but it is carried anyway so the pairing
      // invariant is unconditional (the gate checks every emitted packet, not just simple
      // ones) and so no downstream consumer can ever read a don't-care.
      r.slot0.size      := preds(0).size

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
          if (i == 0) {
            // FMax "Frontend Lever A" (2026-08-08): the i==0 mask is a TAUTOLOGY and is
            // deleted. At i==0 the guard reads `when(0 < L0)`, and this loop only runs
            // inside the `.otherwise` arm of `!p0.simple` (line ~143) -- i.e. p0.simple is
            // already known True here -- and `PredecodeWord.classify(...).simple` never
            // coincides with `lenWords === 0`. That property is not asserted, it is PROVEN
            // exhaustively by `PredecodeSimpleLenSpec` (all 65536 opwords x all extension-
            // word content x all validity combos = 268,435,456 configurations, run to
            // completion, mutation-checked); writing that test also uncovered and fixed the
            // one place the property genuinely did NOT hold (a 3-bit length-sum wrap in
            // PredecodeWord's line-0 immediate mem-dest site -- see its comment).
            //
            // WHY IT IS WORTH DELETING: the opword is the header word every downstream
            // decode gate needs FIRST, and this mask put `L0` (itself the output of the
            // predecode/`preds(0)` mux) in front of it, so `OperationDecoder`/`EaDecoder`'s
            // whole cone could not start until L0 resolved. A netlist-level trace of this
            // design's WNS-holding path (`ibuf pred_lenWords[0] -> DecodeStage
            // specs_1_dstEa_disp[28]`) measured that dependency at >= 0.318ns via a direct
            // `report_timing -through` probe of the unmasked sibling net on the routed
            // checkpoint. See
            // `docs/superpowers/specs/2026-08-08-fmax-frontend-levera-tautological-mask-design.md`.
            //
            // The i >= 1 masks below are UNTOUCHED and remain LOAD-BEARING: the offloaded
            // `dstEa` computation (MicroOpAssembler.computeOffload) depends on the zero-fill
            // beyond L0, per FMax slice 3's post-mortem correctness derivation. This slice
            // removes the mask at the ONE index that is architecturally guaranteed to be
            // inside the instruction whenever the loop runs at all.
            r.slot0.words(0) := words(0)
          } else {
            when(U(i) < L0) {
              r.slot0.words(i) := words(i)
            } .otherwise {
              r.slot0.words(i) := 0
            }
          }
        }
        r.slot0.wordCount := L0
        r.slot0.simple    := True
        r.slot0.complex   := False
        r.slot0.lenWords  := L0
        r.slot0.fault     := False
        // FMax "Lever B": slot0's precomputed size. Read from `preds(0)` DIRECTLY, NOT from
        // `p0` — this is load-bearing in both directions:
        //  (a) CORRECTNESS: it is behaviour-identical. `p0 = Mux(preds(0).ambiguousLine,
        //      p0LiveReg, preds(0))`, and `ambiguousLine` exists solely because
        //      `simple`/`lenWords` can need an extension word that lay past the refill-time
        //      64-byte line boundary. `size` needs ONLY the opword, which is `words(0)` in
        //      both arms of that mux (the live reclassify classifies the same head word),
        //      so both mux inputs carry the identical value.
        //  (b) TIMING: `p0` is what produces `L0`, and `L0` feeds nearly the whole rest of
        //      the front end. Bypassing the mux keeps `size` off `L0`'s arrival chain, so it
        //      is available to `computeOffload` at t≈0 out of the `raw` register — which is
        //      the entire point of the lever.
        r.slot0.size      := preds(0).size

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
        //
        // task #209 (bf_memind_dyn_straddle wild-PC): `p1` comes STRAIGHT from the
        // statically-baked, per-64-byte-line `preds` array (baked ONCE at IcachePlugin
        // REFILL time) with NO live-reclassify equivalent to slot0's `p0LiveReg` above
        // (task #202) and — critically — with no `ambiguousLine` check at all. When the
        // candidate slot1 instruction's OWN opword lands on the LAST word of its 64-byte
        // I-cache line, its extension-word lookahead (needed to disambiguate brief- vs
        // full-format mode-6/mode7-reg3 EAs) falls past the line boundary at bake time,
        // so the baked prediction is a GUESSED "assume brief" with `ambiguousLine=True` —
        // exactly the case task #202 fixed for slot0 via the live reclassify, but slot1
        // was never given the same treatment. Trusting that guessed (too-short) `L1` here silently
        // truncates the real instruction, leaving its own trailing extension word(s)
        // behind as bogus "leftover" bytes the next fetch mis-decodes as a stray
        // instruction — confirmed via PORTED_TRACE_MI/PORTED_TRACE_FED/PORTED_TRACE_EXC
        // on `bf_memind_dyn_straddle` (a memory-indirect BFEXTS landing on word 31 of
        // line 0 lost its own `bd` extension word this way, read a garbage pointer,
        // DECERR'd, and vectored through an unseeded vector table to a wild PC). Simplest
        // safe fix, mirroring the `p0.ambiguousLine` stall arm above: refuse to pack an
        // ambiguous prediction into slot1 this cycle at all (`slot1Ok=False`) — the
        // candidate instruction is re-attempted as the NEXT cycle's `headPc` instead,
        // where it goes through the exact `p0LiveReg` live-reclassify path and resolves
        // correctly once `avail` has grown enough (bounded, same as every other
        // `ambiguousLine` stall in this file — no new livelock risk). Costs at most one
        // extra front-end bubble on the rare cache-line-boundary case; zero effect on
        // every non-ambiguous slot1 (the overwhelming majority).
        val L0L1     = (L0 +^ L1).resize(5)
        val slot1Ok  = p1.simple && !p1.ambiguousLine && (avail.resize(5) >= L0L1) && (L0L1 <= U(WINDOW, 5 bits))

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
            if (i == 0) {
              // FMax "Frontend Lever A" -- same tautological-mask deletion as slot 0 above,
              // against L1 instead of L0; see that comment for the full derivation, the
              // exhaustive proof (`PredecodeSimpleLenSpec`) and the netlist measurement.
              // Here the guard reads `when(0 < L1)`, and this loop only runs inside
              // `when(slot1Ok)`, whose very first term is `p1.simple` (line ~202) -- so L1
              // is the lenWords of an instruction already known simple, hence >= 1.
              // `idx` at i==0 is just `L0`, so slot 1's opword is now available as soon as
              // L0 resolves, without additionally waiting on L1.
              // i >= 1 stays masked (load-bearing zero-fill beyond L1).
              r.slot1.words(0) := words(idx)
            } else {
              when(U(i) < L1) {
                r.slot1.words(i) := words(idx)
              } .otherwise {
                r.slot1.words(i) := 0
              }
            }
          }
          r.slot1.wordCount := L1
          r.slot1.simple    := True
          r.slot1.lenWords  := L1
          r.slot1.complex   := False
          r.slot1.fault     := False
          // FMax "Lever B": slot1's precomputed size, from `p1 = preds(L0)` — the SAME
          // dynamic mux that already yields `L1` and that `words(idx)` above is paired
          // against. No new mux rank is created: the existing `preds(L0)` select merely
          // gets 2 bits wider. (Unlike slot0 there is no ambiguity mux to bypass here —
          // `slot1Ok` already requires `!p1.ambiguousLine`.)
          r.slot1.size      := p1.size

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
