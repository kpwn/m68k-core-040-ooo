package m68k040.cache

import spinal.core._

/** Convert one raw byte-address-invariant instruction word into the numeric 68k
  * opword consumed by predecode/decode.  In raw cache data, bits[7:0] are the byte
  * at the lower address; a 68k instruction word is big-endian, so that byte is the
  * opword's most-significant byte.  Keep this conversion at opcode consumers: the
  * I-cache data arrays and FetchRsp retain their documented raw-byte convention. */
object IcacheInstructionOrder {
  def opword(raw: Bits): Bits = {
    require(raw.getWidth == 16, s"IcacheInstructionOrder.opword needs 16 bits (got ${raw.getWidth})")
    raw(7 downto 0) ## raw(15 downto 8)
  }
}

/** Per-16-bit-word predecode result (one per chunk; 32 per 64-byte line).
  * `lenWords` (1..10 words = 2..20 bytes) is meaningful only when `simple`. Widened
  * 3->4 bits (2026-07-11, deep-audit F2/F1/F3): a MOVE with a full-format-indexed
  * SOURCE (up to 5 ext words: 1 base + 2 bd.L + 2 od.L) and a full-format-indexed
  * DEST (up to 5 more) sums to as much as opword+5+5=11 words, which overflowed the
  * old 3-bit field (max 7) and WRAPPED — the F2 livelock (wraps to 0 -> shiftWords=0
  * -> decodePc never advances) and the F1/F3 silent mis-framing (wraps to a small
  * nonzero length -> the aligner truncates mid-instruction). 4 bits (max 15) is wide
  * enough for the true worst case with headroom; PredecodeWord additionally gates any
  * MOVE whose true total would exceed 10 (the front-end's HEAD_WORDS/Aligner.WINDOW
  * visibility ceiling) as COMPLEX rather than risk the aligner stalling forever on an
  * unreachable length (same "ucEntry 5->6->7" width-growth precedent as the microcode
  * ROM). */
case class ChunkPredecode() extends Bundle {
  val simple   = Bool()
  val lenWords = UInt(4 bits)
  // task #202 (I-cache-line-boundary predecode gap): True iff `simple`/`lenWords` were
  // computed by GUESSING (the "assume brief when unknown" fallback in PredecodeWord's
  // eaExt/memDestExt mode-6 / mode7-reg3 cases) because the real extension word needed
  // to disambiguate brief- vs full-format wasn't resident at REFILL-time predecode (it
  // lives past the 64-byte I-cache line boundary, in a not-yet-fetched line). The guess
  // is architecturally correct ONLY when the real EA happens to be brief; a genuine
  // full-format EA landing at this exact boundary is silently under-framed (wrong
  // lenWords) if trusted as-is. Aligner (FetchAlignPlugin) uses this bit to gate a
  // LIVE re-classification of slot0 from the buffer's own already-fetched raw words
  // once enough of them are actually available (see Aligner.align) — the buffer's
  // fetch-ahead pipeline routinely already holds the next line's words by the time
  // decode reaches this instruction, even though IcachePlugin's one-shot per-line
  // REFILL predecode could not see them. Defaults False (every other classification
  // is unaffected; a caller that never sets it behaves exactly as before).
  val ambiguousLine = Bool()
  // FMax "Lever B" (2026-08-08): `OperationDecoder.decode(op).size` for THIS word, baked
  // at I-cache REFILL time so DecodeStage's post-`raw` offload cone (`computeOffload`)
  // need not re-derive it IN SERIES with the destination-EA decode. Measured on the
  // pre-lever netlist, `OperationDecoder`'s size sub-cone sat at the HEAD of the design's
  // OOC WNS path, costing 9 of its 21 logic levels (2.288ns / 44%); sourcing `size` from
  // a register instead retires that family outright (+15.58MHz OOC, measured A/B).
  //
  // Unlike `lenWords`/`simple`, `size` is a pure function of the OPWORD ALONE — it needs
  // no extension-word lookahead — so it is never `ambiguousLine`-qualified and is
  // identical in both arms of Aligner's `p0` ambiguity mux (see Aligner.align, which
  // deliberately reads `preds(0).size` rather than `p0.size` for exactly that reason).
  //
  // `PredecodeWord.classify` populates this by CALLING the real `OperationDecoder`, not by
  // re-deriving it: equality with the decode-stage value therefore holds BY CONSTRUCTION,
  // leaving only a plumbing/pairing invariant to prove (FedSpecsPacketPairingSpec). See
  // `docs/superpowers/specs/2026-08-08-fmax-leverb-precompute-size-design.md`.
  val size = m68k040.isa.Size()
}

object CacheMode extends SpinalEnum {
  val WRITETHROUGH, COPYBACK, INHIBITED = newElement()

  /** Decode a raw 2-bit CM field (page-descriptor bits[6:5] / TTR bits[6:5],
    * MC68040 UM S3.1.2): 00=writethrough, 01=copyback, 10/11=inhibited (this
    * core's single-outstanding LS pipe is already serialized, so the two
    * "inhibited"/"inhibited serialized" encodings collapse to one INHIBITED
    * value — no fourth enum element is needed). `cm2(1)` = bit6 (the existing
    * `pgInhibited`/`TtMatch.inhibited` bit), `cm2(0)` = bit5. */
  def decode(cm2: Bits): CacheMode.C = {
    val m = CacheMode()
    when(cm2(1)) {
      m := INHIBITED
    } elsewhen (cm2(0)) {
      m := COPYBACK
    } otherwise {
      m := WRITETHROUGH
    }
    m
  }
}

/** Upstream fetch request: a 32-bit PC. */
case class FetchCmd() extends Bundle {
  val pc = UInt(32 bits)
}

/** Fetch response: the 64-bit (8-byte) window at pc, plus fault. */
case class FetchRsp() extends Bundle {
  val pc    = UInt(32 bits)
  val data  = Bits(64 bits)   // little-endian window: data[7:0] = byte at pc+0 ... data[63:56] = byte at pc+7
                              // (architectural big-endian byte-order is the fetch/align stage's concern, deferred)
  val fault = Bool()
  // Task #211: which cause raised `fault` — True = ITLB/MMU-detected translation
  // fault (non-resident / supervisor I-page); False = a physical AXI bus error
  // (SLVERR/DECERR) on a REFILL (e.g. a fetch to genuinely-unmapped space). Only
  // meaningful when `fault` is set. Mirrors LsFault.atc on the D-side (task #189).
  val atc   = Bool()
  val pred  = Vec(ChunkPredecode(), 4)   // predecode for the 4 words of the returned window
}

/** Translation request (virtual page number).
  *  - `valid` : a real translation demand this cycle (a DTLB may start a table
  *    walk only when valid; the identity stub ignores it). Default-driven for
  *    back-compat: an undriven consumer leaves it False (no walk).
  *  - `write` : the access is a store (selects the M-bit / write-protect check). */
case class TranslationReq() extends Bundle {
  val valid      = Bool()
  val vpn        = UInt(20 bits)   // addr[31:12]
  val supervisor = Bool()
  val write      = Bool()
}

/** Translation response (physical page number + cache mode).
  *  - `ready` : the translation is resolved THIS cycle (a TLB hit, or identity).
  *    On a DTLB miss it is False while the walker runs, so the consumer (D-cache)
  *    must NOT accept the access — it stalls on its existing back-pressure path. */
case class TranslationRsp() extends Bundle {
  val ready     = Bool()
  val ppn       = UInt(20 bits)
  val cacheMode = CacheMode()
  val fault     = Bool()
}

/** Tagged D-side translation command.  Unlike the independent I-side port, the
  * D-side is an elastic request/response pipeline: the LSU may launch a new VPN
  * while the preceding registered response is consumed. */
object DTranslationToken {
  // LsEuPlugin composition: {backendEpoch, splitPhase, robId[5:0]}.
  //
  // Task 11 reserves the value $80 for the serializing commit-side ExceptionUnit's own
  // FSAVE/FRESTORE state-frame translations, mirroring the sibling `DLoadToken`
  // convention ("[7] source (0 = LS ROB, 1 = serializing exception unit)"). That
  // reservation is a naming convention, not an exclusion — the LS pipe can in principle
  // form the same 8-bit value — but the two producers are time-multiplexed and can never
  // have a request in flight simultaneously (see ExceptionUnit.ExcDtlbToken for the full
  // structural argument).
  val Width = 8
}
case class DTranslationCmd() extends Bundle {
  val vpn        = UInt(20 bits)
  val supervisor = Bool()
  val write      = Bool()
  val token      = UInt(DTranslationToken.Width bits)
}
case class DTranslationRsp() extends Bundle {
  val ppn       = UInt(20 bits)
  val cacheMode = CacheMode()
  val fault     = Bool()
  val token     = UInt(DTranslationToken.Width bits)
}
