package m68k040.frontend

import m68k040.cache.ChunkPredecode
import spinal.core._

object PredecodeWord {
  // `extW` = word op+1 (the EA's first ext word for an EA-FIRST op). `extW2` = word op+2
  // (the EA's first ext word for a line-0 immediate op with a .B/.W immediate, where the
  // EA ext follows the 1-word immediate). Needed to frame a 68020+ FULL-format indexed EA's
  // variable length (1 + bd + od ext words); the brief path ignores them. Callers without
  // ext words use the shorter overloads.
  def classify(op: Bits): ChunkPredecode = classify(op, B(0, 16 bits), B(0, 16 bits))
  def classify(op: Bits, extW: Bits): ChunkPredecode = classify(op, extW, B(0, 16 bits))
  def classify(op: Bits, extW: Bits, extW2: Bits): ChunkPredecode =
    classify(op, extW, extW2, extWValid = true, extW2Valid = true)
  // `extWValid`/`extW2Valid` (F5, 2026-07-11): Scala-level (elaboration-time) flags — True
  // unless the IcachePlugin caller determined `extW`/`extW2` was zero-filled because the
  // real word lives past the 64-byte I-cache line being predecoded (i and i+1/i+2 are plain
  // Scala Ints at that call site, so "is word i+k past the line end" is a COMPILE-TIME
  // constant per predecode instantiation, not a runtime signal — free to plumb through as a
  // Boolean). Default True preserves existing behavior for every other caller (unit tests +
  // the 1/2/3-arg overloads above), which always supply real data.
  // task #153 (ported-tests memind cluster): a 5th `extW3` word (op+3) is needed ONLY to
  // correctly frame a line-0 .L-immediate op with a full-format mem-indirect DESTINATION
  // (the 2-word .L immediate pushes the EA's first ext word from op+2 to op+3, one word
  // beyond the original 2-word lookahead — see the dedicated comment at its use site below).
  // Every pre-existing caller (unit tests + this 5-arg overload) keeps the OLD "extW3
  // unknown" behavior bit-for-bit (extW3Valid=false) — ONLY IcachePlugin's real 7-arg call
  // (below), which has the whole cache line already resident, supplies real data.
  def classify(op: Bits, extW: Bits, extW2: Bits, extWValid: Boolean, extW2Valid: Boolean): ChunkPredecode =
    classify(op, extW, extW2, B(0, 16 bits), extWValid, extW2Valid, extW3Valid = false)
  def classify(op: Bits, extW: Bits, extW2: Bits, extW3: Bits,
               extWValid: Boolean, extW2Valid: Boolean, extW3Valid: Boolean): ChunkPredecode =
    classify(op, extW, extW2, extW3, Bool(extWValid), Bool(extW2Valid), Bool(extW3Valid))
  // task #202 (I-cache-line-boundary predecode gap, Aligner live-reclassify): a hardware-
  // Bool-typed overload, for callers that need a RUNTIME-conditional validity decision
  // (e.g. "avail >= 2", a genuine signal) instead of a Scala elaboration-time constant like
  // every OTHER existing caller (IcachePlugin's real per-line-word call unrolls `i` as a
  // plain Scala Int, so "is word i+k past the line end" IS a compile-time constant there —
  // see the doc comment above). Same semantics, `Bool` params instead of `Boolean`; the
  // Boolean-typed overload above is now a thin `Bool(...)`-wrapping shim over this one, so
  // BOTH produce byte-identical hardware for every existing (compile-time-constant) caller.
  def classify(op: Bits, extW: Bits, extW2: Bits, extW3: Bits,
               extWValid: Bool, extW2Valid: Bool, extW3Valid: Bool): ChunkPredecode = {
    val extWKnown  = extWValid
    val extW2Known = extW2Valid
    val extW3Known = extW3Valid
    val r = ChunkPredecode()
    r.simple        := False
    r.lenWords      := U(0, 4 bits)
    // task #202: defaults False; set True only inside the specific branches below that
    // actually hit an eaExt/memDestExt "assume brief when unknown" guess.
    r.ambiguousLine := False

    val cls = op(15 downto 12).asUInt

    // Full-format (bit8=1) EA extension length = 1 (ext word) + bd + od words:
    //   bd words: bits5:4 -> 00|01=0, 10=1, 11=2.  od words: bit1(present)? bit0(long)?2:1 : 0.
    // `eaW` is the EA's FIRST extension word (the word after the opword for an EA-first op).
    def fullExtLen(eaW: Bits): UInt = {
      val bdSz  = eaW(5 downto 4).asUInt
      val bdW   = Mux(bdSz === U(2, 2 bits), U(1, 3 bits),
                  Mux(bdSz === U(3, 2 bits), U(2, 3 bits), U(0, 3 bits)))
      val odW   = Mux(!eaW(1), U(0, 3 bits), Mux(eaW(0), U(2, 3 bits), U(1, 3 bits)))
      (U(1, 3 bits) + bdW + odW).resize(3)
    }

    // In-scope MEMSIMPLE RMW-DESTINATION EA: (An)=mode2 (0 ext), (An)+=mode3 (0 ext),
    // -(An)=mode4 (0 ext), (d16,An)=mode5 (1 ext), (xxx).W=mode7/reg0 (1 ext),
    // (xxx).L=mode7/reg1 (2 ext). Returns (ok, ext). The predec/postinc auto modes carry
    // no extension word (the An side-effect is folded by the assembler crack). The indexed
    // (mode 6 / 7-3) + (d16,PC) (read-only, not alterable) + #imm modes are NOT in scope
    // -> ok=False -> COMPLEX. Used to frame the mem-dest RMW lengths (so nextPc is right).
    // `eaWKnown`: True iff `eaW` genuinely holds the real word at the claimed position (op+1
    // or op+2, whichever the caller passed) — False when that position is beyond the
    // classify() 2-word lookahead (e.g. the source EA itself already consumed it) or beyond
    // the I-cache line being predecoded (F5). When False for a mode that NEEDS to read the
    // word's content to distinguish brief-vs-full-format (mode 6 / mode7-reg3), we must NOT
    // silently trust a zero-filled `eaW` as "brief" (that's the F1/F5 silent-corruption
    // class) — instead reject (ok=False -> COMPLEX), a safe trap instead of a wrong guess.
    // task #202: 3rd return value `ambiguous` — True iff this call landed in the
    // mode-6 "assume brief when unknown" fallback (the eaWKnown=False path). See
    // ChunkPredecode.ambiguousLine's doc comment for the full rationale/consumer
    // (Aligner's live re-classify). Every EXISTING (ok, ext) value/behavior is
    // UNCHANGED — this only adds a 3rd, purely-informational output.
    def memDestExt(mode: UInt, reg: UInt, eaW: Bits = B(0, 16 bits), eaWKnown: Bool = True): (Bool, UInt, Bool) = {
      val ok  = Bool(); val ext = UInt(3 bits); val amb = Bool()
      ok := False; ext := U(0, 3 bits); amb := False
      switch(mode) {
        is(U(2, 3 bits)) { ok := True; ext := U(0, 3 bits) }   // (An)
        is(U(3, 3 bits)) { ok := True; ext := U(0, 3 bits) }   // (An)+ postincrement (no ext)
        is(U(4, 3 bits)) { ok := True; ext := U(0, 3 bits) }   // -(An) predecrement (no ext)
        is(U(5, 3 bits)) { ok := True; ext := U(1, 3 bits) }   // (d16,An)
        is(U(6, 3 bits)) {                                     // (d8,An,Xn) brief / full-format
          // ASSUME brief (1 ext word) when the ext word isn't resident yet
          // (IcachePlugin at an I-cache-line boundary), rather than reject as
          // COMPLEX (task #170-cluster10). Rejecting here forced an ordinary
          // crackable memory-RMW instruction (CLR/NEG/NOT/TAS/Scc/shift-mem/
          // EOR-mem/ADDQ-mem/etc, whichever routes through this helper) to a
          // spurious vector-4 illegal trap purely because ITS OWN extension
          // word happened to straddle a 64-byte cache-line boundary -- a
          // permanent wild-PC HANG in the bare-metal ported-test harness
          // (no vector-4 handler), strictly worse than the rare full-format
          // mis-framing this trades for. Mirrors the line-0-immediate
          // mem-dest .L-imm case's identical "assume brief" tradeoff below.
          // task #202: flag `amb` when guessing so a slot0 consumer (Aligner)
          // can re-resolve for real once more words are actually available.
          ok := True
          ext := Mux(eaWKnown, Mux(eaW(8), fullExtLen(eaW), U(1, 3 bits)), U(1, 3 bits))
          when(!eaWKnown) { amb := True }
        }
        is(U(7, 3 bits)) {
          switch(reg) {
            is(U(0, 3 bits)) { ok := True; ext := U(1, 3 bits) }   // (xxx).W
            is(U(1, 3 bits)) { ok := True; ext := U(2, 3 bits) }   // (xxx).L
            // NOTE: (d8,PC,Xn) (reg 3) is PC-relative => NOT alterable => NOT a mem-dest
            // (read-only). Left rejected (complex -> assembler illegal). Source-only.
            default { ok := False }
          }
        }
      }
      (ok, ext, amb)
    }

    // EA extension words; returns (ok, ext). ok=False => complex. `eaW` = the EA's first
    // ext word (op+1 for an EA-first op) — used ONLY to frame a full-format indexed EA.
    // task #202: 3rd return value `ambiguous` (see memDestExt's identical doc comment
    // above). ALSO fixes an inconsistency: mode7/reg3 ((d8,PC,Xn)) previously REJECTED
    // (ok=False -> COMPLEX -> illegal) whenever eaWKnown was False, unlike mode-6's
    // "assume brief" fallback -- an unnecessary spurious-illegal-trap risk for this EA
    // shape specifically (e.g. a boundary-straddling `jmp (d,PC,Xn)`, ported-tests
    // rom_frontier_decode_matrix case 11). Now mirrors mode-6: guess brief, flag `amb`.
    def eaExt(mode: UInt, reg: UInt, sizeL: Bool, allowImm: Boolean, eaW: Bits, eaWKnown: Bool = True): (Bool, UInt, Bool) = {
      val ok  = Bool()
      val ext = UInt(3 bits)
      val amb = Bool()
      ok  := True
      ext := U(0, 3 bits)
      amb := False
      switch(mode) {
        is(U(0, 3 bits), U(1, 3 bits), U(2, 3 bits), U(3, 3 bits), U(4, 3 bits)) {
          ext := U(0, 3 bits)
        }
        is(U(5, 3 bits)) {
          ext := U(1, 3 bits)
        }
        is(U(6, 3 bits)) {
          // (d8,An,Xn) brief = 1 ext word; FULL-format (bit8=1) = 1 + bd + od (1..5).
          // ASSUME brief when unknown (task #170-cluster10) -- see memDestExt's
          // identical mode-6 fix for the full rationale (an I-cache-line-
          // boundary "can't tell" rejection was forcing ordinary crackable
          // ALU-src/etc <ea>,Dn instructions to a spurious illegal trap).
          ext := Mux(eaWKnown, Mux(eaW(8), fullExtLen(eaW), U(1, 3 bits)), U(1, 3 bits))
          when(!eaWKnown) { amb := True }
        }
        is(U(7, 3 bits)) {
          switch(reg) {
            is(U(0, 3 bits)) { ext := U(1, 3 bits) }
            is(U(1, 3 bits)) { ext := U(2, 3 bits) }
            is(U(2, 3 bits)) { ext := U(1, 3 bits) }
            is(U(3, 3 bits)) {                            // (d8,PC,Xn) brief / full-format
              // task #202: mirror mode-6's "assume brief + flag ambiguous" instead of
              // rejecting outright when the ext word isn't resident yet.
              ext := Mux(eaWKnown, Mux(eaW(8), fullExtLen(eaW), U(1, 3 bits)), U(1, 3 bits))
              when(!eaWKnown) { amb := True }
            }
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
      (ok, ext, amb)
    }

    switch(cls) {
      // Line-0 immediates: ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,<ea> + the to-CCR/to-SR
      // forms. 0000 ooo0 ss mmmrrr + imm. opmode ooo (11:9) in {0=ORI,1=ANDI,2=SUBI,
      // 3=ADDI,5=EORI,6=CMPI}; bit8=0; size ss (7:6) in {00=.B,01=.W,10=.L}; imm words
      // = 1 for .B/.W, 2 for .L (the imm precedes any EA ext). Data-reg dest (mode0)
      // => 1+immWords; to-CCR/to-SR (...00/01 111100, ANDI/ORI/EORI only) => 1+1 (a
      // SINGLE 16-bit ext word regardless of ss=00(CCR)/01(SR) -- the low byte is all
      // that's meaningful for CCR, but the encoding always carries a full word). Was:
      // "SR dest (mode7/reg4 .W) ... -> COMPLEX (deferred)" -- found via the cluster-6
      // exception/priv triage to be a genuine predecode-framing gap stacked on top of
      // a separate MicroOpAssembler illegal-classification gap (same 2-bug shape as
      // task #152/#158-160: a decode-side fix alone does not resolve the hang/fault
      // unless predecode ALSO learns the instruction's length). Mem destinations stay
      // deferred (RMW) => COMPLEX. (Mirrors PredecodeRef.)
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
        val isToCcrSr = (mode === 7) && (reg === 4) && (ss === 0 || ss === 1)
        val ccrOk    = opmode === 0 || opmode === 1 || opmode === 5   // ANDI/ORI/EORI only
        when(isImmOp && sizeOk) {
          when(isToCcrSr) {
            when(ccrOk) { r.simple := True; r.lenWords := U(2, 4 bits) }   // opword + imm word (CCR byte or SR word)
          } elsewhen(mode === 0) {
            r.simple := True; r.lenWords := (U(1, 3 bits) + immWords).resized   // data-reg dest
          } otherwise {
            // mem-dest RMW (ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,<ea>): opword + imm words +
            // the EA extension (imm precedes the EA ext). In-scope MEMSIMPLE dest only. For
            // a full-format dst, the EA's first ext word = op+2 when the imm is 1 word
            // (.B/.W -> extW2); a .L imm pushes it to op+3 -> extW3 (task #153: a 3rd
            // lookahead word, threaded through from IcachePlugin's already-resident cache
            // line — see `classify`'s extW3 doc comment). When extW3/extW2 genuinely isn't
            // available (every pre-existing caller, or IcachePlugin at a cache-line
            // boundary), `memDestExt` itself now assumes brief internally for its mode-6
            // case (task #170-cluster10 — previously ONLY the .L path here faked
            // known:=True/eaW:=0 to get that same "assume brief" outcome, leaving .B/.W
            // to pass its real (possibly False) extW2Known straight through into the
            // OLD memDestExt's "reject unknown -> COMPLEX" branch — a real, hit gap,
            // see byte_lane_indexed_rmw's CMPI.B repro). Passing the real extW2Known/
            // extW3Known through unconditionally now (no forced-True faking needed) is
            // simpler AND uniform across .B/.W/.L, and doesn't change the .L path's
            // outcome (a zero-filled fake word already had bit8=0="brief" -> ext=1,
            // exactly what memDestExt's internal fallback now also returns directly).
            val immDstEaW = Mux(ss === U(2, 2 bits), extW3, extW2)
            val immDstEaKnown = Mux(ss === U(2, 2 bits), extW3Known, extW2Known)
            val (mok, mext, mamb) = memDestExt(mode, reg, immDstEaW, immDstEaKnown)
            when(mok) {
              // F2-CLASS LENGTH-WRAP FIX (2026-08-08, found by the exhaustive
              // `PredecodeSimpleLenSpec` proof this file's `simple => lenWords>=1`
              // invariant now carries -- see that spec + the FMax "Frontend Lever A"
              // design/plan under docs/superpowers/). This was the ONE `r.lenWords`
              // site in this file summing THREE terms at a 3-bit width: `immWords`
              // reaches 2 (.L immediate) and `mext` reaches 5 (a full-format mode-6
              // mem destination with a long base displacement AND a long outer
              // displacement), so the true total reaches 1+2+5 = 8 -- which the plain
              // `+` (whose result width is max(operand widths) = 3 bits, NOT the 4-bit
              // destination field's width) truncated to 0. Concrete pre-fix repro:
              // `classify(op=0x00B0, extW3=0x0133, extW3Valid=True)` (ORI.L
              // #imm,(bd,An,Xn) with a full-format destination extension word) returned
              // `simple=True, lenWords=0` -- exactly the F2 livelock signature already
              // documented for the MOVE path below (lenWords=0 -> Aligner shiftWords=0
              // -> decodePc never advances). Fixed the same way that path was: use the
              // WIDTH-EXTENDING `+^` (3->4->5 bits) so the true value (max 8) is
              // computed exactly. No `<= WINDOW` guard is needed here (unlike MOVE,
              // whose two independent full-format EAs can reach 11): 8 <= WINDOW(10),
              // so the aligner's `avail < L0` stall always terminates.
              r.simple := True; r.lenWords := (U(1, 3 bits) +^ immWords +^ mext).resized
              r.ambiguousLine := mamb
            }
          }
          // SR dest (mode7/reg4 .W) / out-of-scope EAs -> COMPLEX (deferred)
        }
        // ── Bit ops (BTST/BCHG/BCLR/BSET) ──────────────────────────────────────
        // dynamic 0000 rrr 1 tt mmmrrr (bit8=1, NOT mode 001=MOVEP); static 0000 1000
        // tt mmmrrr (bits 11:8 == 1000) + bit-number ext word. Dn dest (mode 0) -> 1
        // (dynamic) / 2 (static, opword + bit word). Memory dest -> +EA ext (the bit
        // word precedes the EA ext for the static form). An/#imm/MEMCOMPLEX -> COMPLEX
        // (the assembler's illegal path). MOVEP (dynamic mode 001) stays COMPLEX.
        val isDynBit  = bit8 && (mode =/= U(1, 3 bits))         // exclude MOVEP
        val isStatBit = op(11 downto 8) === B"1000"            // opmode 4
        val bitBase   = Mux(isStatBit, U(2, 4 bits), U(1, 4 bits))   // +1 for the static bit word
        // tt (bits 7:6) selects the bit-op sub-kind, identically positioned for both the
        // dynamic and static encodings: 00=BTST, 01=BCHG, 10=BCLR, 11=BSET.
        val bitTt    = op(7 downto 6)
        val isBtstOp = (isDynBit || isStatBit) && (bitTt === B(0, 2 bits))
        when(isDynBit || isStatBit) {
          when(mode === U(0, 3 bits)) {                         // Dn dest (LONG)
            r.simple := True; r.lenWords := bitBase
          } otherwise {                                         // memory dest (BYTE) -> +EA ext
            // Dynamic bit-op: EA ext = op+1 (extW). Static: a bit-number word precedes the
            // EA ext (op+1 is the bit word, EA ext = op+2 = extW2).
            val bitDstEaW    = Mux(isStatBit, extW2, extW)
            val bitDstEaKnown = Mux(isStatBit, extW2Known, extWKnown)
            val (mok, mext, mamb) = memDestExt(mode, reg, bitDstEaW, bitDstEaKnown)
            when(mok) {
              r.simple := True; r.lenWords := (bitBase + mext).resized
              r.ambiguousLine := mamb
            } elsewhen(isBtstOp && (mode === U(7, 3 bits)) && (reg === U(2, 3 bits))) {
              // BTST uniquely (PRM §4.16) also accepts PC-relative READ-ONLY targets --
              // NOT shared with BCHG/BCLR/BSET, which require a data-alterable (writable)
              // EA and correctly stay COMPLEX/illegal here (memDestExt has no PC-relative
              // case at all, by design, for those 3). Task #169 (ported-tests triage,
              // btst_pcrel_src HANG): memDestExt's shared mem-dest table rejected mode=7
              // entirely except reg 0/1 (abs.W/.L), so BTST Dm,(d16,PC) fell to COMPLEX ->
              // illegal (vector 4, no handler in the bare-metal harness) -> wild-PC HANG.
              // (d16,PC): 1 ext word (the displacement), same shape as (d16,An).
              r.simple := True; r.lenWords := (bitBase + U(1, 3 bits)).resized
            } elsewhen(isBtstOp && (mode === U(7, 3 bits)) && (reg === U(3, 3 bits))) {
              // BTST Dm,(d8,PC,Xn) brief/full-format -- same PRM §4.16 read-only allowance.
              when(bitDstEaKnown) {
                r.simple := True
                r.lenWords := (bitBase + Mux(bitDstEaW(8), fullExtLen(bitDstEaW), U(1, 3 bits))).resized
              }
            }
          }
        }
        // ── MOVEP (0000 rrr 1 oo 001 aaa) + disp16 ──────────────────────────────
        // bit8=1 && mode==001 (the (d16,Ay) form), all 4 variants (oo in {0,1,2,3}):
        // opword + disp16 -> SIMPLE len 2. Carved out of the dyn-bit-op path (which
        // excludes mode 001). The DecodeStage MOVEP FSM owns the µop emission.
        val isMovep = bit8 && (mode === U(1, 3 bits))
        when(isMovep) { r.simple := True; r.lenWords := U(2, 4 bits) }
        // ── CMP2/CHK2 (0000 0ss0 11 mmm rrr) + ext word ────────────────────────
        // bit11==0, ss=op[10:9] (.B/.W/.L, ss=/=3), bit8==0, bits[7:6]==11, EA a
        // CONTROL mode (mode>=2; reject postinc(3)/predec(4)). len = opword + ext
        // word + the EA extension. (Does not alias the immediate path — that needs
        // bits[7:6]=/=3 — nor the bit-ops — those need bit8/bit11 set.)
        val isCmp2Chk2 = !op(11) && !bit8 && (op(7 downto 6).asUInt === U(3, 2 bits)) &&
                         (op(10 downto 9).asUInt =/= U(3, 2 bits)) && (mode >= U(2, 3 bits))
        when(isCmp2Chk2) {
          // Control modes: (An)=2, (d16,An)=5, (d8,An,Xn)=6, (xxx).W/.L=7/0,1,
          // (d16,PC)=7/2, (d8,PC,Xn)=7/3. Reject (An)+=3 / -(An)=4 (NOT control) and
          // #imm. eaExt(allowImm=false) accepts 3/4 (ext 0) but those are non-control
          // -> the assembler illegalises them (a mis-frame of a non-control EA is
          // harmless: the illegal op flushes at the faulting pc).
          val ctrlMode = (mode === U(2, 3 bits)) || (mode === U(5, 3 bits)) ||
                         (mode === U(6, 3 bits)) || (mode === U(7, 3 bits))
          // CMP2/CHK2 EA ext follows a PRECEDING ext word, so op+1 is NOT the EA ext;
          // pass 0 to keep brief framing (full-format here is out of scope).
          val (ok, e, _) = eaExt(mode, reg, sizeL = False, allowImm = false, eaW = B(0, 16 bits))
          when(ok && ctrlMode) {
            r.simple   := True
            r.lenWords := (U(2, 3 bits) + e).resized   // opword + ext word + EA ext
          }
        }
        // ── CAS / CAS2 (020+ atomic compare-and-swap) ──────────────────────────
        // CAS  `0000 1ss0 11 mmm rrr` + 1 ext word + the EA ext: op[15:11]=00001,
        // bit8=0, op[7:6]=11, op[10:9]=size (=/=00). len = opword + 1 ext + EA ext.
        // The EA ext FOLLOWS the (single) compare/update ext word, so op+1 is NOT the
        // EA ext -> pass eaW=0 (brief framing; full-format CAS EA is out of scope and
        // would mis-frame, but the in-scope memory-alterable EAs (An/(d16,An)/(d8,An,Xn)/
        // (xxx).W/.L) all fit the brief lengths). CAS2 `...111100` is a FIXED 3-word
        // instruction (opword + 2 ext) — no EA. (Disjoint from CMP2/CHK2: that needs
        // op[11]=0; CAS needs op[11]=1.)
        val isCasFamily = (op(15 downto 11) === B"00001") && !bit8 &&
                          (op(7 downto 6) === B"11") && (op(10 downto 9) =/= B"00")
        val isCas2Pre   = op(5 downto 0) === B"111100"
        when(isCasFamily) {
          when(isCas2Pre) {
            r.simple := True; r.lenWords := U(3, 4 bits)        // opword + 2 ext words
          } otherwise {
            // memory-ALTERABLE EA (Musashi `A+-DXWL...`): (An)/(An)+/-(An)/(d16,An)/
            // (d8,An,Xn)/(xxx).W/.L. (An)+/-(An) carry 0 EA ext (like (An)). Reject
            // Dn/An/PC-rel/#imm -> stays COMPLEX (decode illegalises it).
            val casOk = (mode === U(2, 3 bits)) || (mode === U(3, 3 bits)) || (mode === U(4, 3 bits)) ||
                        (mode === U(5, 3 bits)) || (mode === U(6, 3 bits)) ||
                        ((mode === U(7, 3 bits)) && ((reg === U(0, 3 bits)) || (reg === U(1, 3 bits))))
            val (ok, e, _) = eaExt(mode, reg, sizeL = False, allowImm = false, eaW = B(0, 16 bits))
            when(ok && casOk) {
              r.simple   := True
              r.lenWords := (U(2, 3 bits) + e).resized          // opword + 1 ext + EA ext
            }
          }
        }
        // ── MOVES (010+ PRIVILEGED) `0000 1110 ss mmm rrr` + 1 ext + EA ext ────
        // op[15:8]=0x0E, op[7:6]=ss in {00,01,10} (11 is CAS). The EA ext FOLLOWS the
        // (single) dr/A-D/reg ext word, so op+1 is NOT the EA ext -> pass eaW=0 (brief
        // framing; the in-scope memory-alterable EAs all fit the brief lengths). len =
        // opword + 1 ext + EA ext. (An)+/-(An) carry 0 EA ext (like (An)). Reject
        // Dn/An/PC-rel/#imm -> stays COMPLEX (decode illegalises it). Same EA mask as CAS.
        val isMovesFamily = (op(15 downto 8) === B"00001110") && (op(7 downto 6) =/= B"11")
        when(isMovesFamily) {
          val movesOk = (mode === U(2, 3 bits)) || (mode === U(3, 3 bits)) || (mode === U(4, 3 bits)) ||
                        (mode === U(5, 3 bits)) || (mode === U(6, 3 bits)) ||
                        ((mode === U(7, 3 bits)) && ((reg === U(0, 3 bits)) || (reg === U(1, 3 bits))))
          val (ok, e, _) = eaExt(mode, reg, sizeL = False, allowImm = false, eaW = B(0, 16 bits))
          when(ok && movesOk) {
            r.simple   := True
            r.lenWords := (U(2, 3 bits) + e).resized            // opword + 1 ext + EA ext
          }
        }
      }

      // MOVE.B / MOVE.L / MOVE.W
      is(U(1, 4 bits), U(2, 4 bits), U(3, 4 bits)) {
        val sizeL   = cls === U(2, 4 bits)
        val srcMode = op(5 downto 3).asUInt
        val srcReg  = op(2 downto 0).asUInt
        val dstMode = op(8 downto 6).asUInt
        val dstReg  = op(11 downto 9).asUInt

        // MOVE source EA is the FIRST ext word -> extW is its ext word (full-format OK).
        val (sOk, sExt, sAmb) = eaExt(srcMode, srcReg, sizeL, allowImm = true, eaW = extW, eaWKnown = extWKnown)

        // dst: modes 0-5 via eaExt(allowImm=false), mode 6 = brief indexed (1 ext word) OR
        // full-format, mode 7 only reg0/reg1, else complex. mode 7-3 ((d8,PC,Xn)) + 7-2
        // ((d16,PC)) are PC-relative => NOT alterable => NOT a MOVE destination -> complex
        // (assembler illegal). The MOVE dst inline path (NOT eaExt) owns this since eaExt is
        // the source variant (which DOES accept (d16,PC)/(d8,PC,Xn) as read-only sources).
        val dOk  = Bool()
        val dExt = UInt(3 bits)
        dOk  := True
        dExt := U(0, 3 bits)

        // F1 FIX (deep-audit 2026-07-11): the dst EA's own first ext word sits at op+1+sExt,
        // NOT unconditionally op+1 — the OLD code (`Mux(sExt===0, extW, 0)`) only read it
        // correctly when the source consumed ZERO ext words; any source needing its own ext
        // word (sExt>=1, e.g. (d16,An)) silently forced dstEaW0=0, mis-framing a full-format
        // dest as brief (1 word) instead of its true 2-5 -> the aligner desynced from the
        // real instruction boundary (silent corruption, not a trap). Correct position:
        //   sExt==0 -> op+1 = extW  (2-word lookahead)
        //   sExt==1 -> op+2 = extW2 (2-word lookahead)
        //   sExt==2 -> op+3 = extW3 (task #155, ported-tests memind cluster: a source
        //     consuming exactly 2 ext words -- abs.L, #imm.L -- combined with a
        //     full-format dst, e.g. `MOVE.L (xxx).L,([bd.W,An],od.W)`; extW3 is the SAME
        //     3rd lookahead word added for the line-0 .L-immediate case above, reused
        //     here verbatim)
        //   sExt>=3 -> op+4.. : STILL beyond even the 3-word lookahead (the source is
        //     ITSELF full-format, consuming 3+ ext words) — we cannot see that far, so we
        //     must NOT guess; dstEaKnown=False routes this (rare: both EAs full-format)
        //     through the eaWKnown gate below, which safely rejects (COMPLEX) rather than
        //     silently assuming brief.
        val dstEaW0    = Mux(sExt === U(0, 3 bits), extW,
                          Mux(sExt === U(1, 3 bits), extW2,
                          Mux(sExt === U(2, 3 bits), extW3, B(0, 16 bits))))
        val dstEaKnown = Mux(sExt === U(0, 3 bits), extWKnown,
                          Mux(sExt === U(1, 3 bits), extW2Known,
                          Mux(sExt === U(2, 3 bits), extW3Known, False)))
        when(dstMode === U(7, 3 bits)) {
          switch(dstReg) {
            is(U(0, 3 bits)) { dExt := U(1, 3 bits) }
            is(U(1, 3 bits)) { dExt := U(2, 3 bits) }
            default          { dOk  := False }
          }
        } elsewhen(dstMode === U(6, 3 bits)) {
          // (d8,An,Xn) brief = 1 ext word; FULL-format dst (bit8=1) = 1 + bd + od.
          when(dstEaKnown) {
            dExt := Mux(dstEaW0(8), fullExtLen(dstEaW0), U(1, 3 bits))
          } otherwise {
            dOk := False   // can't tell brief vs full -> COMPLEX (safe), see F1 note above
          }
        } otherwise {
          val (o, e, _) = eaExt(dstMode, dstReg, sizeL, allowImm = false, eaW = dstEaW0)
          dOk  := o
          dExt := e
        }

        // MOVE (incl. mem-to-mem): both EAs in-scope MEMSIMPLE/reg/imm -> SIMPLE. The
        // assembler cracks mem-to-mem into [load src -> T0][store T0 -> dst] (+ folded
        // An auto-updates). lenWords = opword + src ext + dst ext (predec/postinc add 0).
        //
        // F2 FIX (deep-audit 2026-07-11): sExt and dExt are each up to 5 (a full-format EA
        // with long bd + long od: 1 base + 2 bd + 2 od), so the true total can reach
        // opword(1)+sExt(5)+dExt(5)=11 — this OVERFLOWED the old 3-bit lenWords field (max
        // 7, wraps mod 8): the F2 livelock (wraps to 0 -> shiftWords=0 -> decodePc never
        // advances) and the sibling F1/F3 silent mis-framing (wraps to a small nonzero
        // length). Two independent fixes are needed together:
        //  (1) Use a WIDTH-EXTENDING sum (`+^`, not the old plain `+`, which truncates to
        //      its OPERANDS' width and would itself wrap at 8 regardless of the final
        //      field's width) so the true value (up to 11) is computed exactly.
        //  (2) The front-end's aligner can only ever see WINDOW=10 words at once
        //      (InstructionBuffer.HEAD_WORDS); an instruction whose real length is 11 would
        //      make the aligner's `avail < L0` stall condition permanently true (avail can
        //      never reach 11) — a NEW hang. So any total > WINDOW is intentionally NOT
        //      marked `simple` (falls to the COMPLEX/illegal path instead): a safe trap for
        //      this vanishingly rare (both EAs simultaneously maximal full-format)
        //      combination, instead of either a wraparound-corruption or an unreachable-stall
        //      hang.
        val totalLen = (U(1, 3 bits) +^ sExt +^ dExt)   // width-extends each +^: 3->4->5 bits, max 11
        when(sOk && dOk && (totalLen <= U(Aligner.WINDOW, totalLen.getWidth bits))) {
          r.simple   := True
          r.lenWords := totalLen.resized
          // task #202: the source EA's own ambiguity (sAmb) is the only one threaded here —
          // the dst mode-6 branch above already safely rejects (dOk:=False -> COMPLEX)
          // rather than guessing when ITS ext word is unknown, so it never reaches this
          // point with an unresolved guess baked in.
          r.ambiguousLine := sAmb
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
        // NOP (0x4E71): single-word, no architectural effect. MUST be predecode-framed
        // (simple, len 1) — without a case it fell to the COMPLEX head path, and the
        // assembler's `!pkt.simple` gate turned a plain NOP into a spurious vector-4
        // ILLEGAL (found by the first lock-step program that actually committed a NOP —
        // the bf3c BFINS test; every earlier program happened to never execute one).
        val isNop   = op === B"16'h4E71"
        when(isTrap || isTrapv || isRts || isRtr || isNop) {
          r.simple   := True
          r.lenWords := U(1, 4 bits)
        }
        // LINK An,#disp16 (0100 1110 0101 0aaa, op[15:4]==0x4E5, op[3]=0): opword + a
        // disp16 extension word -> SIMPLE len 2. UNLK An (op[3]=1): single word -> len 1.
        // (The cracker decodes both in the assembler, like RTS/JSR; predecode only frames.)
        val isLink = (op(15 downto 4) === B"12'h4E5") && !op(3)
        val isUnlk = (op(15 downto 4) === B"12'h4E5") &&  op(3)
        when(isLink) { r.simple := True; r.lenWords := U(2, 4 bits) }
        when(isUnlk) { r.simple := True; r.lenWords := U(1, 4 bits) }
        // LINK An,#disp32 (68020+, 0100 1000 0000 1 aaa, op[15:3]==0x901): opword + a
        // disp32 (2-word) extension -> SIMPLE len 3. Distinct opcode region from LINK.W/
        // UNLK (0x4E5x) above -- ported-tests triage (link_long_unlk.s), previously
        // entirely unhandled here (fell through to the illegal/complex default, framing
        // 0 extra words and desyncing the next fetch -- the eventual observed symptom was
        // a HANG, not a trap, since the wild PC/illegal cascade never reaches a sentinel
        // write in this bare-metal harness).
        val isLinkL = op(15 downto 3) === B(0x901, 13 bits)
        when(isLinkL) { r.simple := True; r.lenWords := U(3, 4 bits) }
        // ── Privileged commit-time SYSTEM ops (frame the length so nextPc is right) ─
        // MOVE to SR (0100 0110 11 mmmrrr): opword + the source EA's ext words (the
        // EA is a .W source). MOVE USP (0100 1110 0110 d rrr = 0x4E6x): single word.
        // MOVEC (0x4E7A/0x4E7B): opword + 1 ext word {A/D|reg#|Rc}. These commit-time
        // sysOps re-fetch younger work after the serializing retire, so a precise nextPc
        // matters (the redirect target = nextPc).
        val isMoveToSr = op(15 downto 6) === B"10'b0100011011"
        when(isMoveToSr) {
          val srcMode = op(5 downto 3).asUInt
          val srcReg  = op(2 downto 0).asUInt
          val (ok, e, amb) = eaExt(srcMode, srcReg, sizeL = False, allowImm = true, eaW = extW, eaWKnown = extWKnown)  // .W source EA
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized; r.ambiguousLine := amb }
        }
        val isMoveUsp = op(15 downto 4) === B"12'h4E6"
        when(isMoveUsp) { r.simple := True; r.lenWords := U(1, 4 bits) }
        val isMovec = op(15 downto 1) === B"15'b010011100111101"   // 0x4E7A / 0x4E7B
        when(isMovec) { r.simple := True; r.lenWords := U(2, 4 bits) }   // opword + ext word
        // RTD (0x4E74) + disp16: opword + 1 disp word -> SIMPLE len 2 (RTS-with-dealloc).
        val isRtd = op === B"16'h4E74"
        when(isRtd) { r.simple := True; r.lenWords := U(2, 4 bits) }
        // RESET (0x4E70): single-word privileged sysOp -> SIMPLE len 1 (nextPc = pc+2, the
        // redirect target after the serializing retire).
        val isReset = op === B"16'h4E70"
        when(isReset) { r.simple := True; r.lenWords := U(1, 4 bits) }
        // STOP (0x4E72) + imm16: opword + 1 imm word -> SIMPLE len 2 (nextPc = pc+4).
        val isStop = op === B"16'h4E72"
        when(isStop) { r.simple := True; r.lenWords := U(2, 4 bits) }
        // CHK.W/CHK.L (0100 ddd 1 s 0 mmmrrr): bit8=1, bit6=0. The bound is an EA
        // source (sizeL = .L when bit7=0). 1 opword + the EA extension words.
        val isChk = op(8) && !op(6)
        when(isChk) {
          val sizeL   = !op(7)                       // CHK.L when bit7=0
          val srcMode = op(5 downto 3).asUInt
          val srcReg  = op(2 downto 0).asUInt
          val (ok, e, amb) = eaExt(srcMode, srcReg, sizeL, allowImm = true, eaW = extW, eaWKnown = extWKnown)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
            r.ambiguousLine := amb
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
          val (ok, e, _) = eaExt(srcMode, srcReg, sizeL = True, allowImm = true, eaW = B(0, 16 bits))  // EA ext follows the Dl/Dh word
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
        // Line-4 single-operand DATA-register family (the unary group), all single-word
        // -> SIMPLE len1. CLR/NEG/NEGX/NOT/TST (0100 oooo ss 000rrr, oooo in {0,2,4,6,A},
        // ss != 11, mode 000=Dn) + SWAP (0x4840-47) / EXT.W (0x4880-87) / EXT.L
        // (0x48C0-C7) / EXTB.L (0x49C0-C7) / TAS (0x4AC0-C7). Memory-dest forms (mode !=
        // 000) are the deferred RMW slice -> COMPLEX (the assembler's illegal path).
        // bit8=0 for all EXCEPT EXTB.L (matched by its own pattern).
        val u4o    = op(11 downto 8).asUInt
        val u4ss   = op(7 downto 6).asUInt
        val u4mode = op(5 downto 3).asUInt
        val isUnaryArith = !op(8) && (u4ss =/= U(3, 2 bits)) && (u4mode === U(0, 3 bits)) &&
                           (u4o === U(0, 4 bits) || u4o === U(2, 4 bits) || u4o === U(4, 4 bits) ||
                            u4o === U(6, 4 bits) || u4o === U(0xA, 4 bits))
        val isSwap  = op(15 downto 3) === B"13'b0100100001000"   // 0x4840-47
        val isExtW  = op(15 downto 3) === B"13'b0100100010000"   // 0x4880-87
        val isExtL  = op(15 downto 3) === B"13'b0100100011000"   // 0x48C0-C7
        val isExtbL = op(15 downto 3) === B"13'b0100100111000"   // 0x49C0-C7
        val isTas   = op(15 downto 3) === B"13'b0100101011000"   // 0x4AC0-C7
        // NBCD Dn (0x4800-07, task #159): register form only (memory-EA NBCD stays
        // deferred/illegal -- no memDestExt framing added, unlike TAS-mem above).
        val isNbcd  = op(15 downto 3) === B"13'b0100100000000"   // 0x4800-07
        // BKPT #n (0x4848-4F, task #178 cluster12/exc_bkpt_decode): single-word,
        // no operands, no EA -- decoded as a plain no-op (like NOP/RESET) so it
        // commits cleanly through the ROB instead of illegal-trapping. The 3-bit
        // breakpoint vector (op[2:0]) is architecturally irrelevant here (no bus-
        // level breakpoint-acknowledge cycle is modeled).
        val isBkpt  = op(15 downto 3) === B"13'b0100100001001"   // 0x4848-4F
        when(isUnaryArith || isSwap || isExtW || isExtL || isExtbL || isTas || isNbcd || isBkpt) {
          r.simple   := True
          r.lenWords := U(1, 4 bits)
        }
        // CLR/NEG/NEGX/NOT/TST <ea> mem-dest (the RMW crack): mode != 000, ss != 11,
        // oooo in {0,2,4,6,A}, bit8=0. In-scope MEMSIMPLE dest -> opword + EA ext.
        // SWAP/EXT are Dn-only (mode 000, matched above); TAS-mem is framed separately
        // below (its ss field is ALWAYS 11, which this ss!=11 filter exists specifically
        // to exclude, since ss=11 at mode=000 is the Dn-form TAS opword, not TST).
        val isUnaryMem = !op(8) && (u4ss =/= U(3, 2 bits)) && (u4mode =/= U(0, 3 bits)) &&
                         (u4o === U(0, 4 bits) || u4o === U(2, 4 bits) || u4o === U(4, 4 bits) ||
                          u4o === U(6, 4 bits) || u4o === U(0xA, 4 bits))
        when(isUnaryMem) {
          val (mok, mext, mamb) = memDestExt(u4mode, op(2 downto 0).asUInt, extW, extWKnown)   // EA is op+1
          when(mok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + mext).resized
            r.ambiguousLine := mamb
          }
        }
        // TST.W/TST.L An (68020+, task #170-cluster10): mode=001 (An-direct) is a
        // legal TST operand for WORD/LONG only (TST.B An stays illegal, matching
        // real silicon -- byte-size address-register operands are never legal).
        // Unlike CLR/NEG/NEGX/NOT (which need an alterable dst and correctly
        // reject An via memDestExt's missing mode==1 case), TST never writes back,
        // so An is fine here. Register-direct -> 0 ext words, same as the Dn form
        // isUnaryArith already frames above.
        val isTstAn = !op(8) && (u4ss =/= U(3, 2 bits)) && (u4ss =/= U(0, 2 bits)) &&
                      (u4mode === U(1, 3 bits)) && (u4o === U(0xA, 4 bits))
        when(isTstAn) {
          r.simple   := True
          r.lenWords := U(1, 4 bits)
        }
        // TAS <ea> mem-dest (task #158): op[15:6]==0b0100101011, mode != 000 (mode=000 is
        // the Dn form, already matched by `isTas` above). Opword + EA ext, same shape as
        // isUnaryMem but keyed off the full fixed opcode (not o4/ss, which collide with
        // TST at mode=000 only -- irrelevant here since mode is forced non-zero).
        val isTasMem = (op(15 downto 6) === B"10'b0100101011") && (u4mode =/= U(0, 3 bits))
        when(isTasMem) {
          val (mok, mext, mamb) = memDestExt(u4mode, op(2 downto 0).asUInt, extW, extWKnown)   // EA is op+1
          when(mok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + mext).resized
            r.ambiguousLine := mamb
          }
        }
        // MOVEM (0100 1 d 001 s mmmrrr) + 16-bit register-mask ext word: a 2+-word
        // instruction = opword + mask + the EA's OWN extension words (the mask precedes the
        // EA ext). In-scope modes: (An)/(An)+/-(An) add 0, (d16,An)/(xxx).W/(d16,PC) add 1,
        // (xxx).L adds 2 -> lenWords = 1 (opword) + 1 (mask) + EA ext. Direction (bit10)
        // chooses store (control-alterable + -(An)) vs load (control + (An)+ + (d16,PC)/
        // (xxx)); for FRAMING the length only depends on the EA's ext-word count, so a single
        // memDestExt-style table covers store modes and the load adds (An)+ / (d16,PC).
        // Indexed (mode 6 / 7-3), reg-direct, #imm -> NOT in scope -> COMPLEX (never enter
        // the FSM). bit8=0, bits9:7=001, bit11=1 (does not alias CHK / the unary group).
        val isMovem = op(11) && (op(9 downto 7) === B"001")
        when(isMovem) {
          val mmMode = op(5 downto 3).asUInt
          val mmReg  = op(2 downto 0).asUInt
          val mmDir  = op(10)                         // 0 store / 1 load
          val mmOk   = Bool(); val mmExt = UInt(3 bits)
          mmOk := False; mmExt := U(0, 3 bits)
          switch(mmMode) {
            is(U(2, 3 bits)) { mmOk := True; mmExt := U(0, 3 bits) }                    // (An) (store+load)
            is(U(3, 3 bits)) { mmOk := mmDir;  mmExt := U(0, 3 bits) }                   // (An)+ LOAD only
            is(U(4, 3 bits)) { mmOk := !mmDir; mmExt := U(0, 3 bits) }                   // -(An) STORE only
            is(U(5, 3 bits)) { mmOk := True; mmExt := U(1, 3 bits) }                     // (d16,An)
            is(U(7, 3 bits)) {
              switch(mmReg) {
                is(U(0, 3 bits)) { mmOk := True; mmExt := U(1, 3 bits) }                 // (xxx).W
                is(U(1, 3 bits)) { mmOk := True; mmExt := U(2, 3 bits) }                 // (xxx).L
                is(U(2, 3 bits)) { mmOk := mmDir; mmExt := U(1, 3 bits) }                // (d16,PC) LOAD only
                default          { mmOk := False }
              }
            }
            default { mmOk := False }
          }
          when(mmOk) {
            r.simple   := True
            r.lenWords := (U(2, 3 bits) + mmExt).resized   // opword + mask + EA ext
          }
        }
        val isJmp = op(15 downto 6) === B"10'b0100111011"
        val isJsr = op(15 downto 6) === B"10'b0100111010"
        when(isJmp || isJsr) {
          val srcMode = op(5 downto 3).asUInt
          val srcReg  = op(2 downto 0).asUInt
          val (ok, e, amb) = eaExt(srcMode, srcReg, sizeL = False, allowImm = false, eaW = extW, eaWKnown = extWKnown)
          // Reg-direct (modes 0,1) and (An)+/-(An) (modes 3,4) are NOT control modes;
          // eaExt accepts them (ext 0) but they are illegal for JMP/JSR. Restrict to
          // the in-scope control modes so predecode frames the right length AND a
          // non-control EA stays complex (-> the assembler's illegal path). Mode 6
          // ((d8,An,Xn) brief/full-format indexed) IS a control mode (task #187: the
          // branch EU gained an index-register read port, mirroring CMP2/CHK2's own
          // ctrlMode two cases below, which already included it) -- omitting it here
          // was a predecode-framing bug independent of MicroOpAssembler's ctrlEaOk gate:
          // a mode-6 JMP/JSR fell through with lenWords at its prior/default value (0),
          // so the assembled `nextPc`/pushed return address was WRONG (nextPc==pc) even
          // though eaExt itself already computes the correct 1-ext-word brief length.
          val ctrlMode = (srcMode === U(2, 3 bits)) || (srcMode === U(5, 3 bits)) ||
                         (srcMode === U(6, 3 bits)) || (srcMode === U(7, 3 bits))
          when(ok && ctrlMode) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized   // opword + EA ext
            r.ambiguousLine := amb
          }
        }
        // ── LEA An,<ea> (0100 An 1 11 mmmrrr): bit8=1, bits7:6=11, mode>=2. Control EA,
        // opword + EA ext. (isUnary's EXTB.L is mode 000 -> excluded by mode>=2.) An
        // out-of-scope EA still frames its len via eaExt; the assembler faults the EA.
        val isLea = op(8) && (op(7 downto 6) === B"11") && (op(5 downto 3).asUInt >= 2)
        when(isLea) {
          val (ok, e, amb) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = false, eaW = extW, eaWKnown = extWKnown)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized; r.ambiguousLine := amb }
        }
        // ── PEA <ea> (0100 1000 01 mmmrrr): control EA, opword + EA ext.
        val isPea = op(15 downto 6) === B"10'b0100100001"
        when(isPea) {
          val (ok, e, amb) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = false, eaW = extW, eaWKnown = extWKnown)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized; r.ambiguousLine := amb }
        }
        // ── MOVE from SR (0x40C0) / from CCR (0x42C0): SR/CCR -> EA (.W), data EA.
        val isMoveFromSr  = op(15 downto 6) === B"10'b0100000011"
        val isMoveFromCcr = op(15 downto 6) === B"10'b0100001011"
        when(isMoveFromSr || isMoveFromCcr) {
          val (ok, e, amb) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = false, eaW = extW, eaWKnown = extWKnown)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized; r.ambiguousLine := amb }
        }
        // ── MOVE to CCR (0x44C0): EA(.W) -> CCR, data EA incl #imm.
        val isMoveToCcr = op(15 downto 6) === B"10'b0100010011"
        when(isMoveToCcr) {
          val (ok, e, amb) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = true, eaW = extW, eaWKnown = extWKnown)
          when(ok) { r.simple := True; r.lenWords := (U(1, 3 bits) + e).resized; r.ambiguousLine := amb }
        }
      }

      // Line-5: ADDQ/SUBQ (0101 ddd q ss mmmrrr, ss != 11) + Scc/DBcc (ss == 11).
      //   ss != 11 (ADDQ/SUBQ): dest EA mode 0/1 (Dn/An) -> SIMPLE len1; memory dest is
      //     the deferred RMW -> COMPLEX (the assembler's illegal path).
      //   ss == 11: mode 001 -> DBcc (opword + disp16) -> SIMPLE len2; mode 000 -> Scc Dn
      //     -> SIMPLE len1; mode 7 ttt in {2,3,4} -> TRAPcc; mode 7 ttt in {0,1} (abs.W/
      //     .L) and modes 2-6 -> Scc <ea> memory dest (task #160, the RMW-store crack --
      //     opword + EA ext, same memDestExt table CLR/NEG/NOT-mem and ADDQ/SUBQ mem-dest
      //     already use above). mode 7 ttt in {5,6,7} stays COMPLEX (memDestExt rejects
      //     mode7 outside reg 0/1 -> mok=False -> falls through un-simple).
      is(U(5, 4 bits)) {
        val ss   = op(7 downto 6).asUInt
        val mode = op(5 downto 3).asUInt
        when(ss =/= U(3, 2 bits)) {
          when(mode === U(0, 3 bits) || mode === U(1, 3 bits)) {   // ADDQ/SUBQ Dn / An
            r.simple := True; r.lenWords := U(1, 4 bits)
          } otherwise {                                            // ADDQ/SUBQ #n,<ea> mem-dest (RMW)
            val (mok, mext, mamb) = memDestExt(mode, op(2 downto 0).asUInt, extW, extWKnown)   // EA is op+1
            when(mok) { r.simple := True; r.lenWords := (U(1, 3 bits) + mext).resized; r.ambiguousLine := mamb }
          }
        } otherwise {                                              // ss == 11
          when(mode === U(1, 3 bits)) {                            // DBcc + disp16
            r.simple := True; r.lenWords := U(2, 4 bits)
          } elsewhen(mode === U(0, 3 bits)) {                      // Scc Dn
            r.simple := True; r.lenWords := U(1, 4 bits)
          } elsewhen(mode === U(7, 3 bits)) {                      // TRAPcc (mode 7) / Scc abs.W/.L
            val ttt = op(2 downto 0).asUInt
            when(ttt === U(4, 3 bits)) {                           // TRAPcc (no operand, 1 word)
              r.simple := True; r.lenWords := U(1, 4 bits)
            } elsewhen(ttt === U(2, 3 bits)) {                     // TRAPcc.W (#data16, 2 words)
              r.simple := True; r.lenWords := U(2, 4 bits)
            } elsewhen(ttt === U(3, 3 bits)) {                     // TRAPcc.L (#data32, 3 words)
              r.simple := True; r.lenWords := U(3, 4 bits)
            } otherwise {                                          // Scc (xxx).W/.L (ttt 0/1)
              val (mok, mext, mamb) = memDestExt(mode, op(2 downto 0).asUInt, extW, extWKnown)
              when(mok) { r.simple := True; r.lenWords := (U(1, 3 bits) + mext).resized; r.ambiguousLine := mamb }
            }
            // ttt 5/6/7 -> memDestExt(mode=7, reg>=5) rejects -> mok=False -> COMPLEX
          } otherwise {                                            // Scc <ea> memory (modes 2-6)
            val (mok, mext, mamb) = memDestExt(mode, op(2 downto 0).asUInt, extW, extWKnown)   // EA is op+1
            when(mok) { r.simple := True; r.lenWords := (U(1, 3 bits) + mext).resized; r.ambiguousLine := mamb }
          }
        }
      }

      // MOVEQ
      is(U(7, 4 bits)) {
        when(op(8) === False) {
          r.simple   := True
          r.lenWords := U(1, 4 bits)
        }
      }

      // Bcc / BRA / BSR
      is(U(6, 4 bits)) {
        val d8 = op(7 downto 0).asUInt
        r.simple := True
        when(d8 === U(0x00, 8 bits)) {
          r.lenWords := U(2, 4 bits)
        } elsewhen(d8 === U(0xff, 8 bits)) {
          r.lenWords := U(3, 4 bits)
        } otherwise {
          r.lenWords := U(1, 4 bits)
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
        // EXG (line C, bit8=1): 1100 xxx 1 ooooo yyy with ooooo in {01000,01001,10001}
        // (EXG Dx,Dy / Ax,Ay / Dx,Ay). A single-word reg-reg swap (no extension) -> simple,
        // len=1. EXG's opmode(8:6) is 5 (01000/01001) or 6 (10001), so it would otherwise
        // hit the AND-RMW memDestExt path below with a reg-direct EA -> NOT MEMSIMPLE ->
        // complex -> wrong length. Frame it BEFORE that band (it dominates the chain here).
        val isExg = (cls === U(0xC, 4 bits)) && op(8) &&
                    (op(7 downto 3) === B"5'b01000" ||   // EXG Dx,Dy
                     op(7 downto 3) === B"5'b01001" ||   // EXG Ax,Ay
                     op(7 downto 3) === B"5'b10001")      // EXG Dx,Ay
        when(isExg) {
          r.simple   := True
          r.lenWords := U(1, 4 bits)
        } elsewhen(isDivuW || isDivsW || isMuluW || isMulsW) {
          // 16-bit multiplier/divisor EA (sizeL = false: word operand size for #imm).
          val (ok, e, amb) = eaExt(srcMode, srcReg, sizeL = False, allowImm = true, eaW = extW, eaWKnown = extWKnown)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
            r.ambiguousLine := amb
          }
        } elsewhen(opmode =/= U(4, 3 bits) && opmode =/= U(5, 3 bits) && opmode =/= U(6, 3 bits) && !isMulDiv) {
          // Task #140: OR/SUB/AND/ADD <ea>,Dn (opmode 0/1/2) AND their ADDA/SUBA
          // <ea>,An variants (opmode 3/7) all admit an IMMEDIATE source EA on real
          // 68k (identical semantics to ORI/SUBI/ANDI/ADDI, just the alternate
          // encoding assemblers rarely emit) -- allowImm was wrongly False here,
          // so predecode framed `adda.l #imm,An` (and the whole ADD/SUB/AND/OR/
          // ADDA/SUBA-with-#imm-source family) as simple=false/lenWords=0. That
          // starves the front-end (nextPc=pc, matches the documented "len=0 ->
          // stall" class of predecode gaps) until it eventually decodes as
          // whatever garbage bytes follow, which can misdecode as a genuine
          // illegal-instruction opword -- a spurious vector-4 trap whose vector-
          // fetch then reads an uninitialized table entry, landing PC at garbage.
          // Root-caused via fuzz seed 13 (see
          // eori-mem-then-adda-spurious-illegal-2026-07-16 memory); minimal repro
          // is `adda.l #imm,An` completely standalone (no preceding instruction
          // needed at all).
          val sizeL = (opmode === U(2, 3 bits)) || (opmode === U(7, 3 bits))
          val (ok, e, amb) = eaExt(srcMode, srcReg, sizeL, allowImm = true, eaW = extW, eaWKnown = extWKnown)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
            r.ambiguousLine := amb
          }
        } elsewhen(opmode === U(4, 3 bits) || opmode === U(5, 3 bits) || opmode === U(6, 3 bits)) {
          // ADDX/SUBX register form (mode 000) AND -(Ay),-(Ax) MEMORY form (mode 001,
          // MICROCODED): both are single 1-word ops. The reg form is Dx := Dx +/- Dy +/- X
          // (1 µop); the mem form's 6 µops come from the DecodeStage µcode SEQUENCER, so
          // predecode only needs LEN=1. Mode 001 is An-direct (never a valid ADD/SUB-to-mem
          // dst) so it is unambiguously the X-mem form. Frame both (else memDestExt rejects
          // mode 000/001 -> unframed -> nextPc=pc -> the front-end stalls).
          val isAddxSubxReg = (cls === U(9, 4 bits) || cls === U(0xD, 4 bits)) &&
                              (srcMode === U(0, 3 bits) || srcMode === U(1, 3 bits))
          // ABCD (line C) / SBCD (line 8): opmode 4. Register form (mode 000) + -(Ay),-(Ax)
          // MEMORY form (mode 001, microcoded). Both single 1-word ops (the mem 6 µops come
          // from the µcode sequencer). Mode 001 An-direct = the X-mem form; frame both.
          val isBcdReg = (cls === U(8, 4 bits) || cls === U(0xC, 4 bits)) &&
                         (opmode === U(4, 3 bits)) && (srcMode === U(0, 3 bits) || srcMode === U(1, 3 bits))
          // PACK (line 8, opmode 5) / UNPK (line 8, opmode 6): opword + 16-bit adj extension.
          // Frame BOTH the register form (srcMode 000) AND the deferred memory form (srcMode 001)
          // as len=2 so the front-end doesn't stall on the memory form (decode illegalises it).
          // Line C with opmode 5/6 is OR.W/.L, NOT PACK/UNPK -> exclude line C here.
          val isPackUnpkFrame = (cls === U(8, 4 bits)) &&
                                (opmode === U(5, 3 bits) || opmode === U(6, 3 bits)) &&
                                (srcMode === U(0, 3 bits) || srcMode === U(1, 3 bits))
          when(isAddxSubxReg || isBcdReg) {
            r.simple   := True
            r.lenWords := U(1, 4 bits)
          } elsewhen(isPackUnpkFrame) {
            r.simple   := True
            r.lenWords := U(2, 4 bits)   // opword + adj16 extension word
          } otherwise {
            // ALU Dn,<ea> RMW (opmode 4/5/6 = .B/.W/.L mem-dest): opword + EA ext. The EA
            // MUST be a MEMSIMPLE alterable-memory mode (the assembler illegalises Dn/An/
            // MEMCOMPLEX). (DIVU/MULU are opmode 3/7, excluded.)
            val (mok, mext, mamb) = memDestExt(srcMode, srcReg, extW, extWKnown)   // EA is op+1
            when(mok) {
              r.simple   := True
              r.lenWords := (U(1, 3 bits) + mext).resized
              r.ambiguousLine := mamb
            }
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
        // EOR (opmode 4/5/6): EA is the DESTINATION (read+written). Register (data-reg,
        // mode0) dest -> simple len1; An-direct (CMPM) and memory-dest (RMW) -> COMPLEX.
        val isEor   = (opmode === U(4, 3 bits)) || (opmode === U(5, 3 bits)) ||
                      (opmode === U(6, 3 bits))
        // CMPM (Ay)+,(Ax)+ : same opmode band as EOR, but An-direct (srcMode 1) — the
        // slot EOR's Dn-dest / mem-dest branches both exclude. Single opword, NO
        // extension words (mirrors the isAddxSubxReg||isBcdReg carve-out above).
        val isCmpm  = isEor && (srcMode === U(1, 3 bits))
        when(isCmp) {
          // Task #140 follow-up: CMP/CMPA <ea>,Dn/An (opmode 0/1/2/3/7) admits an
          // IMMEDIATE source EA on real 68k (same alternate-encoding legality as
          // ADD/SUB/AND/OR/ADDA/SUBA, fixed for line 8/9/C/D above but this line-B
          // block was never touched) -- allowImm was wrongly False here, so predecode
          // framed `cmpa.l #imm,An` (and CMP #imm,Dn via this encoding) as
          // simple=false/lenWords=0, the same front-end stall -> wild-PC cascade as
          // task #140. Root-caused via fuzz seed=154 (cmpa.l #0x1,%a2).
          val sizeL = (opmode === U(2, 3 bits)) || (opmode === U(7, 3 bits))
          val (ok, e, amb) = eaExt(srcMode, srcReg, sizeL, allowImm = true, eaW = extW, eaWKnown = extWKnown)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + e).resized
            r.ambiguousLine := amb
          }
        } elsewhen(isEor && (srcMode === U(0, 3 bits))) {
          r.simple   := True
          r.lenWords := U(1, 4 bits)                  // EOR Dn,Dm (register dest)
        } elsewhen(isCmpm) {
          r.simple   := True
          r.lenWords := U(1, 4 bits)                  // CMPM (Ay)+,(Ax)+ (single opword)
        } elsewhen(isEor) {
          // EOR Dn,<ea> mem-dest (RMW): opword + EA ext. In-scope MEMSIMPLE dest only;
          // An-direct (CMPM) / MEMCOMPLEX -> COMPLEX (the assembler's illegal path).
          val (mok, mext, mamb) = memDestExt(srcMode, srcReg, extW, extWKnown)   // EA is op+1
          when(mok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + mext).resized
            r.ambiguousLine := mamb
          }
        }
      }

      // Line-E register-form shifts/rotates (1110 ccc d ss i tt rrr): single-word,
      // no extension -> SIMPLE len 1. ss=11 is the MEMORY single-bit form (1110 ccc d
      // 11 mmmrrr, shift <ea> by 1) which is the deferred RMW slice -> COMPLEX.
      is(U(0xE, 4 bits)) {
        val ss = op(7 downto 6).asUInt
        when(ss =/= U(3, 2 bits)) {
          r.simple   := True
          r.lenWords := U(1, 4 bits)
        }
        // Bit-field register form (BFxxx Dn{...}): op[11:8]>=8 (op[11]=1), op[7:6]==3,
        // mode 000 (op[5:3]==0). opword + the bit-field extension word -> SIMPLE len 2.
        // (ss=11 with op[11]=0 = the deferred memory single-bit shift, or mode!=0 = a
        // memory bit-field, both stay COMPLEX -> the assembler's illegal path.)
        val isBitfieldReg = op(11) && (ss === U(3, 2 bits)) && (op(5 downto 3).asUInt === U(0, 3 bits))
        when(isBitfieldReg) {
          r.simple   := True
          r.lenWords := U(2, 4 bits)
        }
        // Bit-field MEMORY form (BFxxx <ea>): op[11]=1, op[7:6]==3, mode>=2 (memory EA).
        // len = opword + bf-ext word + the EA's OWN ext words (per mode). The bf-ext word
        // precedes the EA ext (same shape as CMP2/CHK2 -> +2 base). Slice 3a framed only
        // the LOAD-only ops {0,1,3,5}; slice 3b adds the RMW ops {2,4,6,7} (microcoded) —
        // predecode computes only LENGTH (identical for load-only and RMW), so frame ALL
        // EIGHT bfOps. The LEGALITY split (PC-rel illegal for RMW, control-alterable only)
        // is enforced in OperationDecoder, NOT here.
        val bfMemMode = op(5 downto 3).asUInt
        val isBitfieldMem = op(11) && (ss === U(3, 2 bits)) && (bfMemMode >= 2)
        when(isBitfieldMem) {
          // The bit-field EA ext follows the bf-ext word, so op+1 is NOT the EA ext -- the
          // EA's OWN first ext word is at op+2 = extW2 (same shift as the static bit-OP
          // family's `bitDstEaW` above / the line-0 .B/.W-immediate family's `immDstEaW`).
          //
          // task #197 (bitfield-memory-indirect cluster) FOUND + FIXED: this previously
          // passed `eaW = B(0, 16 bits)` (a HARDCODED zero) with the default `eaWKnown =
          // True` -- telling `eaExt` "the real ext word is definitely all-zero" instead of
          // "unknown" or "here it is". For mode 6 / mode-7-reg-3, `eaExt` reads `eaW(8)` to
          // pick brief- (1 ext word) vs full-format (1+bd+od, up to 5); with a hardcoded
          // zero, `eaW(8)` was ALWAYS 0 -> ALWAYS assumed brief, even for a genuine 68020+
          // full-format memory-indirect bit-field EA. `lenWords` therefore came out too
          // SHORT for every such EA, silently resyncing fetch mid-instruction -- a wild-PC
          // HANG completely independent of (and upstream of) any bit-field decode/microcode
          // logic. Passing the REAL extW2 (+ eaWKnown = extW2Known, so an unresident/past-
          // line-end word correctly falls back to the existing eaWKnown=False "assume
          // brief" safety net, exactly like every other mode-6/mode-7-3 caller in this file)
          // fixes it.
          val (ok, e, amb) = eaExt(bfMemMode, op(2 downto 0).asUInt, sizeL = False, allowImm = false,
                              eaW = extW2, eaWKnown = extW2Known)
          when(ok) {
            r.simple   := True
            r.lenWords := (U(2, 3 bits) + e).resized   // opword + bf-ext + EA ext
            r.ambiguousLine := amb
          }
        }
        // Line-E MEMORY-form shift/rotate (1110 ttt d 11 mmm rrr, task #170-cluster10,
        // ASL/ASR/LSL/LSR/ROXL/ROXR/ROL/ROR <ea>, word-only, implicit count=1): op[11]=0
        // (distinguishes from the bit-field register/memory forms above, which both
        // require op[11]=1), ss=11 (op[7:6]==3). Alterable memory EA only (modes 2-6,
        // mode7 reg0/1) via the same memDestExt table CLR/NEG/NOT/TAS/Scc-mem already
        // use (mode 0/Dn and mode 1/An naturally fall to ok=False -> COMPLEX/illegal,
        // matching the real ISA -- this instruction has no register-direct form at
        // this encoding, RO/RS's Dn opword lives entirely under ss!=3 above).
        val shiftMemMode = op(5 downto 3).asUInt
        val isShiftMem = !op(11) && (ss === U(3, 2 bits))
        when(isShiftMem) {
          val (mok, mext, mamb) = memDestExt(shiftMemMode, op(2 downto 0).asUInt, extW, extWKnown)
          when(mok) {
            r.simple   := True
            r.lenWords := (U(1, 3 bits) + mext).resized
            r.ambiguousLine := mamb
          }
        }
      }

      // Line-1010 ("Line-A") / Line-1111 ("Line-F") emulator traps: real 68040 hardware
      // raises vector 10 / vector 11 unconditionally on the top nibble alone, with NO
      // further decode of the rest of the opword (unimplemented FPU ops land here on
      // real F-line hardware too, since this core has no FPU) -> SIMPLE single-word,
      // no extension words consumed. See OperationDecoder (spec.illegal stays default
      // True for these lines) + MicroOpAssembler's `bad` fallback, which picks vector
      // 10/11 instead of the generic vector 4 based on this same top-nibble check.
      is(U(0xA, 4 bits)) { r.simple := True; r.lenWords := U(1, 4 bits) }
      is(U(0xF, 4 bits)) {
        // FSF (xxx).L narrow carve-out (task #180, exc_fsf_xxx_l_no_fline): opword
        // 0xF27F + ext1 (discarded) + a 2-word abs.L address = 4 words total, unlike
        // every other line-F op admitted so far (all single-word). See
        // OperationDecoder.scala for the full derivation/rationale.
        // MOVE16 (Ax)+,(Ay)+ (task #207): opword 0xF620|Ax + 1 ext word carrying Ay
        // (ext[14:12]) = 2 words total. Only THIS exact form (op & 0xFFF8 == 0xF620) is
        // framed as 2 words — the other 3 absolute-addressing MOVE16 forms (F600/F608/
        // F610/F618) are out of scope and stay on the existing 1-word `otherwise` arm
        // (matching real F-line-trap behavior: vector 11, no further decode).
        when(op === B"16'hF27F") {
          r.simple   := True
          r.lenWords := U(4, 4 bits)
        } .elsewhen(op(15 downto 3) === U(0xF620 >> 3, 13 bits).asBits) {
          r.simple   := True
          r.lenWords := U(2, 4 bits)
        } .otherwise {
          r.simple := True; r.lenWords := U(1, 4 bits)
        }
      }

      default { /* complex: r stays simple=False, lenWords=0 */ }
    }
    r
  }
}
