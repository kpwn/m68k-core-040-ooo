package m68k040.cache

import spinal.core._

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
  // is unaffected; a caller that never sets it behaves exactly as before). */
  val ambiguousLine = Bool()
}

object CacheMode extends SpinalEnum {
  val CACHEABLE, INHIBITED = newElement()
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
