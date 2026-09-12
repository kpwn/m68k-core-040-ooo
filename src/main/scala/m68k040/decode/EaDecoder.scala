package m68k040.decode

import m68k040.isa.Size
import spinal.core._
import spinal.lib._

/** Opcode-agnostic effective-address decoder. Resolves register-direct + immediate
  * modes, and (memory-cracking slice) the IN-SCOPE memSimple modes' base/disp:
  * `(An)`, `(d16,An)`, `(xxx).W`, `(xxx).L`, `(d16,PC)`, plus the auto-update modes
  * `(An)+` (POSTINC) / `-(An)` (PREDEC) — MEMSIMPLE base=An, disp=0, carrying
  * `autoMode`/`autoDelta` so the assembler folds the `An := An ± delta` write-back
  * into the load/store/RMW crack (generalizing the call/return A7 stkPush). The
  * deferred indexed modes — `(d8,An,Xn)`, `(d8,PC,Xn)` — stay MEMCOMPLEX so the
  * assembler keeps them `unimplemented`. The first extension word is `words(1)`
  * (PC/abs disp). PC-rel `disp` is the raw d16; the assembler folds the PC (it owns
  * `pkt.pc`). */
object EaDecoder {
  // Dynamically select words(idx) for a full-format OD position (idx ∈ {2,3,4,5}),
  // bounded by the actual Vec length. Some callers (e.g. immEa/immDstEa, the bf/CMP2
  // re-decodes) pass only 3 entries -- those EAs never carry a full-format OD, so the
  // out-of-range candidates read 0 for them. But `MicroOpAssembler.shiftedWordsFor` (the
  // dstEa caller in `computeOffload`) passes the FULL 10-word window, so for THAT caller
  // this dynamic read is real and live over the whole idx ∈ {2..5} range
  // -- do not assume "only 3 entries" holds for every caller when reasoning about this fn.
  private def fOdWordAt(words: Vec[Bits], idx: UInt): Bits = {
    val n = words.length
    val out = Bits(16 bits); out := B(0, 16 bits)
    switch(idx) {
      for (i <- 2 until 6 if i < n) { is(U(i, 3 bits)) { out := words(i) } }
    }
    out
  }
  // Static word read bounded by the Vec length. Same caveat as `fOdWordAt` above: some
  // callers pass only 3 entries (out-of-range reads 0 for them), but not all -- check the
  // specific caller before assuming a fixed-width Vec.
  private def wAt(words: Vec[Bits], i: Int): Bits =
    if (i < words.length) words(i) else B(0, 16 bits)

  def decode(eaField: Bits, size: Size.C, words: Vec[Bits]): EaSpec = {
    val e    = EaSpec()
    val mode = eaField(5 downto 3)
    val reg  = eaField(2 downto 0)

    // An adjust in bytes for an auto (predec/postinc) EA: sizeBytes, EXCEPT a BYTE
    // access on A7 (reg==7) -> 2 (keep the stack pointer even — the 68k A7 rule).
    def autoDeltaOf: UInt = {
      val sb = size.mux(Size.BYTE -> U(1, 3 bits), Size.WORD -> U(2, 3 bits), Size.LONG -> U(4, 3 bits))
      Mux(size === Size.BYTE && reg === B"3'b111", U(2, 3 bits), sb)
    }

    // defaults
    e.klass := EaClass.ILLEGAL
    e.reg   := 0
    e.imm   := 0
    e.baseValid := False
    e.base      := (U(8, 5 bits) + reg.asUInt).resized   // An base (used by mem modes)
    e.disp      := 0
    e.pcRel     := False
    e.autoMode  := EaAuto.NONE
    e.baseDirect := False
    e.autoDelta := 0
    e.indexValid := False
    e.indexReg   := 0
    e.indexLong  := False
    e.indexScale := 0
    e.od         := 0
    e.memPost    := False

    // Brief-format extension word (modes 6 / 7-3): D/A(15) | Xn(14:12) | W/L(11) |
    // scale(10:9) | brief=0(8) | d8(7:0). bit8=1 => FULL format (memory-indirect /
    // base-outer-disp) — OUT OF SCOPE (Track B µcode) -> the caller keeps MEMCOMPLEX.
    val extW        = words(1)
    val briefIsFull = extW(8)                              // bit8=1 => full ext format
    val idxDA       = extW(15)                             // 1 => An, 0 => Dn
    val idxXn       = extW(14 downto 12).asUInt
    val idxReg      = Mux(idxDA, (U(8, 5 bits) + idxXn).resized, idxXn.resize(5))
    val idxLong     = extW(11)                             // 1 => .L, 0 => .W
    val idxScale    = extW(10 downto 9).asUInt
    val idxD8       = extW(7 downto 0).asSInt.resize(32).asBits   // sext(d8)

    // ── 68020+ FULL-format extension word (bit8=1) ─────────────────────────────
    // Layout: D/A(15) REG(14:12) W/L(11) SCALE(10:9) 1(8) BS(7) IS(6) BD-SIZE(5:4)
    //         0(3) I/IS(2:0) ; then bd (0/16/32-bit), then od (0/16/32-bit). The
    // trailing words follow the ext word in `words`: ext=words(1), bd@words(2..),
    // od@words(2+bdWords..). I/IS table (Musashi m68ki_get_ea_ix, spec §3):
    //   IS=0 000 = No Memory Indirect (single-pass, -> MEMSIMPLE)
    //   bit2=0 (001/010/011) = pre-index ; bit2=1 (101/110/111) = post-index
    //   od: bits1:0 -> 00 none, 01 reserved(none), 10 word, 11 long.
    val fBs      = extW(7)                                  // base suppress
    val fIs      = extW(6)                                  // index suppress
    val fBdSize  = extW(5 downto 4).asUInt                  // 00/01 null, 10 word, 11 long
    val fIis     = extW(2 downto 0).asUInt                  // I/IS selector
    // BD = words(2) (word, sign-extended) or words(2)##words(3) (long); else 0.
    val fBd      = Mux(fBdSize === U(2, 2 bits), wAt(words, 2).asSInt.resize(32).asBits,
                   Mux(fBdSize === U(3, 2 bits), wAt(words, 2) ## wAt(words, 3), B(0, 32 bits)))
    val fBdWords = Mux(fBdSize === U(2, 2 bits), U(1, 3 bits),
                   Mux(fBdSize === U(3, 2 bits), U(2, 3 bits), U(0, 3 bits)))
    // OD position = 2 + bdWords. OD present iff bit1; long iff bit0. Index `words`
    // statically by the candidate OD bases (bdWords ∈ {0,1,2} -> OD at 2/3/4).
    val fOdPresent = extW(1)
    val fOdLong    = extW(0)
    val fOdPos     = (U(2, 3 bits) + fBdWords).resize(3)    // OD word index = 2 + bdWords
    val fOdLo      = fOdWordAt(words, fOdPos)               // the OD word
    val fOdHi      = fOdWordAt(words, (fOdPos + U(1, 3 bits)).resize(3))  // OD+1 (long)
    val fOd        = Mux(!fOdPresent, B(0, 32 bits),
                     Mux(fOdLong, fOdLo ## fOdHi, fOdLo.asSInt.resize(32).asBits))
    // Classification: I/IS==000 -> single-pass (MEMSIMPLE); else MEMINDIRECT. bit2
    // selects post(1)/pre(0). IS=1 (index suppressed) collapses to pre with Xn=0.
    val fNoMemInd = fIis === U(0, 3 bits)
    val fMemPost  = extW(2)

    switch(mode) {
      is(0) { e.klass := EaClass.DATAREG; e.reg := reg.asUInt.resized }                  // Dn
      is(1) { e.klass := EaClass.ADDRREG; e.reg := (U(8, 5 bits) + reg.asUInt).resized } // An
      is(2) {                                                                            // (An)
        e.baseDirect := True                 // EA is the base An itself
        e.klass := EaClass.MEMSIMPLE; e.baseValid := True; e.disp := 0
      }
      is(3) {                                      // (An)+ postincrement
        e.baseDirect := True                 // EA is the base An itself
        e.klass := EaClass.MEMSIMPLE; e.baseValid := True; e.disp := 0
        e.autoMode := EaAuto.POSTINC; e.autoDelta := autoDeltaOf
      }
      is(4) {                                      // -(An) predecrement
        e.baseDirect := True                 // EA is the base An itself
        e.klass := EaClass.MEMSIMPLE; e.baseValid := True; e.disp := 0
        e.autoMode := EaAuto.PREDEC;  e.autoDelta := autoDeltaOf
      }
      is(5) {                                      // (d16,An)
        e.klass := EaClass.MEMSIMPLE; e.baseValid := True
        e.disp  := words(1).asSInt.resize(32).asBits
      }
      is(6) {                                      // mode 6: (d8,An,Xn) brief / full-format (...,An,Xn)
        when(briefIsFull) {
          // FULL-format. base = An (BS-suppressible), disp = bd, index = Xn (IS-suppr).
          e.baseValid  := !fBs
          e.disp       := fBd
          e.indexValid := !fIs
          e.indexReg   := idxReg; e.indexLong := idxLong; e.indexScale := idxScale
          e.od         := fOd
          e.memPost    := fMemPost
          when(fNoMemInd) { e.klass := EaClass.MEMSIMPLE }      // I/IS=000 single-pass
            .otherwise    { e.klass := EaClass.MEMINDIRECT }    // mem-indirect -> µcode
        } .otherwise {
          e.klass := EaClass.MEMSIMPLE; e.baseValid := True
          e.disp  := idxD8
          e.indexValid := True; e.indexReg := idxReg
          e.indexLong  := idxLong; e.indexScale := idxScale
        }
      }
      is(7) {
        switch(reg) {
          is(0) {                                  // (xxx).W
            e.klass := EaClass.MEMSIMPLE; e.baseValid := False
            e.disp  := words(1).asSInt.resize(32).asBits
          }
          is(1) {                                  // (xxx).L
            e.klass := EaClass.MEMSIMPLE; e.baseValid := False
            e.disp  := words(1) ## words(2)
          }
          is(2) {                                  // (d16,PC) — assembler folds pc
            e.klass := EaClass.MEMSIMPLE; e.baseValid := False; e.pcRel := True
            e.disp  := words(1).asSInt.resize(32).asBits
          }
          is(3) {                                  // mode 7-3: (d8,PC,Xn) brief / full-format (...,PC,Xn)
            when(briefIsFull) {
              // FULL-format PC-relative. The base is the PC (BS-suppressible: when BS
              // set, the PC base is dropped -> an absolute-ish bd+index). The assembler
              // folds pc into the disp when pcRel; when BS, base=0 (no fold), disp=bd.
              e.baseValid  := False
              e.pcRel      := !fBs           // BS suppresses the PC base (no pc fold)
              e.disp       := fBd
              e.indexValid := !fIs
              e.indexReg   := idxReg; e.indexLong := idxLong; e.indexScale := idxScale
              e.od         := fOd
              e.memPost    := fMemPost
              when(fNoMemInd) { e.klass := EaClass.MEMSIMPLE }
                .otherwise    { e.klass := EaClass.MEMINDIRECT }
            } .otherwise {
              e.klass := EaClass.MEMSIMPLE; e.baseValid := False; e.pcRel := True
              e.disp  := idxD8
              e.indexValid := True; e.indexReg := idxReg
              e.indexLong  := idxLong; e.indexScale := idxScale
            }
          }
          is(4) {                                  // #imm
            e.klass := EaClass.IMM
            when(size === Size.LONG) {
              e.imm := words(1) ## words(2)
            } otherwise {
              // byte uses the low 8 bits of the (sign-extended) word per m68k
              e.imm := words(1).asSInt.resize(32).asBits
            }
          }
          default { e.klass := EaClass.ILLEGAL }
        }
      }
    }
    e
  }
}
