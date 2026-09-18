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

  // ── Control-transfer bit (2026-09-16, "predecoded branch gate") ────────────────
  // True iff THIS word's opword is a control-transfer instruction, i.e. one whose
  // decode can produce a µop that reaches `BranchEuPlugin` AND that the BTB/FTB is
  // allowed to learn:
  //
  //   Bcc / BRA / BSR      (line 6, every condition)
  //   DBcc                 (line 5, ss==11, mode==001)
  //   JMP / JSR            (0x4E80..0x4EFF)
  //   RTE / RTD / RTS / RTR(0x4E73 / 74 / 75 / 77)
  //   FBcc.W/.L            (line F type 010/011) EXCEPT cc==0 (FBF/FNOP decodes as a NOP)
  //   FDBcc                (line F type 001, mode 001)
  //
  // WHY IT EXISTS. `FetchAlignPlugin`'s fetch-time prediction enables --
  // `btbQueryValid0` and the fetch-directed `ftqConfirm` -- were gated only on "a slot
  // was emitted", never on the slot actually BEING a branch. `predTaken` is stamped on
  // every µop of the emitted instruction (MicroOpAssembler's last-wins stamp) but is
  // READ ONLY BY `BranchEuPlugin`, so a stale BTB/FTB entry hitting an ordinary ALU/LS
  // instruction redirected fetch with NOTHING downstream able to detect it, and the
  // wrong-path instructions RETIRED. Both predictors are keyed on VIRTUAL PC and are
  // invalidated only by I-cache maintenance and the external boot port -- nothing
  // invalidates them on a translation change (PFLUSH/PFLUSHA, URP/SRP/TC write, 24/32-bit
  // mode switch) -- so the stale-entry precondition is reachable without any SMC at all.
  // See IcachePlugin's `maintInvalidateAll` comment, which conceded exactly this.
  //
  // WHY IT LIVES HERE rather than at fetch time. The alternative -- classifying the
  // opword inside FetchAlignPlugin -- puts ~7 opword comparators into the
  // `slot0Predicted -> suppressSlot1 -> decodePcNext` arc, which is the measured
  // front-end FMax floor. Baking it at REFILL time makes the fetch-side gate a pure
  // memory-output AND: `btbQueryValid0 := predEnable && res.slot0Valid &&
  // headPred(0).ctrlXfer`, where the new term is a register/LUTRAM output arriving at
  // t~=0 and folding into the existing AND's LUT. Exactly the `size` precedent
  // ("FMax Lever B") above, for exactly the same reason.
  //
  // AMBIGUITY. Like `size`, and UNLIKE `simple`/`lenWords`, this is a pure function of
  // the OPWORD ALONE -- no extension-word lookahead -- so it is never
  // `ambiguousLine`-qualified, is identical in both arms of Aligner's `p0` mux, and is
  // NOT part of the straddle-token field reuse documented below. Consumers read
  // `preds(0).ctrlXfer` DIRECTLY, never through `p0`.
  //
  // FAIL-CLOSED. Every default/undefined path drives it False (no prediction), never
  // True: InstructionBuffer's beyond-`count` head default, FetchAlignPlugin's push
  // default-drive, and PredecodeWord's own unconditional default. A word this
  // classifier cannot place is therefore un-predictable, not mis-predictable.
  //
  // COHERENCE WITH THE PREDICTORS. A BTB/FTB entry outlives the cache line it was learned
  // from; this bit does not, and that asymmetry is deliberate and is what makes the gate
  // sound. `ctrlXfer` is not a cached copy of the predictor's claim -- it is a property of
  // THE BYTES BEING FETCHED RIGHT NOW. It is produced at refill from the very line the
  // fetch consumes, and it travels with those words through the fetch ring and the
  // instruction buffer, so `headPred(0)` is by construction the predecode of `head(0)`.
  // There is no window in which the gate sees an old line's bit against a new line's
  // bytes. When a line is evicted and refilled with DIFFERENT contents at the SAME virtual
  // address (the translation-change case above, or any eviction), the refill re-runs
  // `classify` on the new bytes and exactly two things can happen:
  //   - the new bytes are not a control transfer -> gate 0 -> the surviving predictor entry
  //     is never consulted. NO PREDICTION.
  //   - the new bytes ARE a control transfer -> gate 1 -> the (possibly wrong-target)
  //     prediction is allowed through, and it is now riding a real branch micro-op, so
  //     `BranchEuPlugin.mispredict` checks direction AND target and redirects at resolve.
  //     A RECOVERED MISPREDICT.
  // Neither outcome is "a wrong prediction that nothing checks", which is the outcome the
  // ungated path had.
  val ctrlXfer = Bool()

    // ── Straddle TOKEN (2026-09-13, 200 MHz campaign) ─────────────────────────
    // See DESIGN_icache_straddle_token.md. When `ambiguousLine` is set, Aligner
    // discards this whole entry (`p0 = Mux(preds(0).ambiguousLine, p0LiveReg,
    // preds(0))`, Aligner.scala), so `simple` and `lenWords` are DEAD PAYLOAD in
    // exactly that case. They are therefore reused, with no growth of the line
    // array, to publish the refill-resolved length indirectly:
    //
    //   ambiguousLine=0 : `simple`/`lenWords` mean what they always meant.
    //   ambiguousLine=1 : `simple`   -> hasToken  (a side-table entry exists)
    //                     `lenWords` -> token idx (0..15)
    //
    // `size` is deliberately NOT reused: it is not ambiguity-qualified and Aligner
    // reads `preds(0).size` directly in BOTH arms of that mux.
    //
    // Reading a token is ALWAYS optional -- the side entry carries an ownerKey that
    // must match {way,set,word}, and any mismatch (recycled entry, evicted line)
    // falls back to the live re-classify, i.e. exactly today's behaviour. There is
    // no failure mode in which a stale token yields a WRONG length rather than no
    // length; that asymmetry is the reason for the indirection.
    def hasToken: Bool = ambiguousLine && simple
    def tokenIdx: UInt = lenWords
    /** Mark this word as "straddle, resolved -- see side-table entry `idx`". */
    def setToken(idx: UInt): Unit = {
      ambiguousLine := True
      simple        := True
      lenWords      := idx.resize(4)
    }
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
  // LsEuPlugin composition: {pad, backendEpoch, splitPhase, robId[5:0]}.
  /** Bits reserved for the robId, FIXED AT 6 and deliberately independent of
    * Global.ROB_ID_W -- same contract as DLoadToken.RobIdBits. A smaller ROB pads into
    * this field so the composition and every reserved value stay bit-identical.
    * `Global.robTag` takes this as a real parameter; it is not decoration. */
  val RobIdBits = 6

  /** NINE bits, so bit 8 is a SOURCE bit no LS-pipe token can ever set.
    *
    * WHY THIS IS NOT EIGHT (2026-09-18). The LS composition is
    * `{backendEpoch, splitPhase, robId[5:0]}` = exactly 8 bits, so at Width = 8 the
    * epoch occupies bit 7 and the LS pipe CAN form 0x80 -- the value reserved for
    * `ExceptionUnit.ExcDtlbToken`. The old text here called that "a naming convention,
    * not an exclusion", and it was right: nothing made the collision impossible. It
    * merely happened to be unreachable for a while, because `robTag` padded at the top
    * and a 5-bit `ROB_ID_W_DEFAULT` left the composition one bit short of bit 7.
    *
    * That is an accidental guarantee, and this campaign exists because accidental
    * guarantees keep turning into defects: four MMU config-latch faults masked only by
    * a `doFlush -> umFlush -> walkUmPoison` chain nobody asserted; `RobIdBits` itself
    * documented as load-bearing in two files with zero readers. Restoring the
    * documented layout would have SPENT this one -- correct against the docs, and
    * exactly the trade that produces the next finding.
    *
    * At nine bits the composition still occupies bits 7:0 and `robTag`'s top pad makes
    * bit 8 structurally zero for every LS token, so `ExcDtlbToken` = 0x100 is
    * unreachable BY CONSTRUCTION, the way `DLoadToken`'s 0x80/0x81/0x82 already are
    * (all four of its callers pass a constant False as the top bit).
    *
    * COST, measured rather than assumed: this token is stored in no Vec and no Mem --
    * three scalar registers widen by one bit (`DtlbPlugin.missReqReg.token`,
    * `DtlbPlugin.walkToken`, `LsEuPlugin.txToken`), i.e. +3 flops. Its two consumers
    * are equalities (`LsEuPlugin.txTokenMatch`, `ExceptionUnit.dxRspMine`) and a 9-input
    * equality occupies the same two LUT6 levels as an 8-input one, so no depth is added.
    * It is NOT the D-cache early-probe CAM's token -- that is `DLoadToken`, unchanged at
    * 8 bits -- so none of the CAM's six compares are touched. */
  val Width = 9
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
