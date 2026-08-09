package m68k040.frontend

/** Authoritative pure-Scala opword classifier (the spec for predecode).
  * RTL PredecodeWord must be equivalent (proven by exhaustive sweep). */
object PredecodeRef {
  final case class CP(simple: Boolean, lenWords: Int)
  val COMPLEX = CP(false, 0)

  /** Extension-word count for an EA mode/reg. None => complex (indexed/PC-indexed
    * or, when allowImm=false, immediate). sizeL selects #imm width (long=2). */
  def eaExt(mode: Int, reg: Int, sizeL: Boolean, allowImm: Boolean): Option[Int] = mode match {
    case 0 | 1 | 2 | 3 | 4 => Some(0)        // Dn, An, (An), (An)+, -(An)
    case 5                 => Some(1)        // (d16,An)
    case 6                 => Some(1)        // (d8,An,Xn) brief indexed -> 1 ext word
    case 7 => reg match {
      case 0 => Some(1)                      // abs.W
      case 1 => Some(2)                      // abs.L
      case 2 => Some(1)                      // (d16,PC)
      case 3 => Some(1)                      // (d8,PC,Xn) brief indexed -> 1 ext word
      case 4 => if (allowImm) Some(if (sizeL) 2 else 1) else None  // #imm
      case _ => None
    }
    case _ => None
  }
  def isMem(mode: Int): Boolean = mode > 1   // not Dn(0)/An(1)

  /** In-scope MEMSIMPLE RMW-DESTINATION EA ext: (An)=2 (0), (An)+=3 (0), -(An)=4 (0),
    * (d16,An)=5 (1), (xxx).W=mode7/reg0 (1), (xxx).L=mode7/reg1 (2). The predec/postinc
    * auto modes carry no extension word (the An side-effect is folded by the crack).
    * Indexed (mode 6 / 7-3), (d16,PC) (read-only), and #imm are NOT in scope -> None. */
  def memDestExt(mode: Int, reg: Int): Option[Int] = mode match {
    case 2 => Some(0)
    case 3 => Some(0)
    case 4 => Some(0)
    case 5 => Some(1)
    case 6 => Some(1)        // (d8,An,Xn) brief indexed (alterable mem-dest)
    // (d8,PC,Xn) (mode 7/3) is PC-relative => NOT alterable => NOT a mem-dest -> None.
    case 7 => reg match { case 0 => Some(1); case 1 => Some(2); case _ => None }
    case _ => None
  }

  def classify(op0: Int): CP = {
    val op  = op0 & 0xffff
    val cls = (op >> 12) & 0xf
    cls match {
      // Line-0 immediates: ADDI/SUBI/ANDI/ORI/EORI/CMPI #imm,<ea> + the to-CCR forms.
      // 0000 ooo0 ss mmmrrr + imm. opmode ooo (bits 11:9): 0=ORI,1=ANDI,2=SUBI,3=ADDI,
      // 5=EORI,6=CMPI (4=bit/BTST-imm, 7=MOVES — both out of scope). bit8=0 (bit8=1 =
      // bit-ops-dynamic / MOVEP). size ss (bits 7:6): 00=.B,01=.W,10=.L; 11 illegal.
      // imm words: .B/.W = 1, .L = 2 (the imm precedes any EA extension).
      //   - reg dest (mode 0 = Dn): 1 + immWords. (An-direct mode1 is illegal for these.)
      //   - to-CCR/to-SR (...00/01 111100, mode7/reg4): 1 + 1 (a single 16-bit ext
      //     word regardless of ss=00(CCR, byte)/01(SR, word) — the encoding always
      //     carries one full word; only the low byte is meaningful for CCR). Was
      //     previously "SR forms -> COMPLEX (out of scope, privileged)" — the RTL
      //     now implements ANDI/ORI/EORI #imm,SR as a privileged commit-time sysOp
      //     (cluster-6 exception/priv triage), so the reference model's framing must
      //     match. Memory destinations are the deferred RMW slice -> COMPLEX.
      // Everything else COMPLEX.
      case 0x0 =>
        val opmode = (op >> 9) & 7
        val bit8   = (op >> 8) & 1
        val ss     = (op >> 6) & 3
        val mode   = (op >> 3) & 7
        val reg    = op & 7
        val isImmOp = bit8 == 0 && (opmode == 0 || opmode == 1 || opmode == 2 ||
                                    opmode == 3 || opmode == 5 || opmode == 6)
        val immWords = ss match { case 0 | 1 => 1; case 2 => 2; case _ => -1 }  // -1 = illegal size
        val isToCcr = mode == 7 && reg == 4 && (ss == 0 || ss == 1)  // ANDI/ORI/EORI #imm,CCR/SR
        val ccrOk   = opmode == 0 || opmode == 1 || opmode == 5  // ANDI/ORI/EORI only (to CCR/SR)
        // Bit ops: dynamic 0000 rrr 1 tt mmmrrr (bit8=1, NOT mode 001=MOVEP); static
        // 0000 1000 tt mmmrrr (bits 11:8 == 1000) + bit-number word. Dn dest -> 1
        // (dynamic) / 2 (static); memory -> +EA ext. An/#imm/MEMCOMPLEX -> COMPLEX.
        val isDynBit  = bit8 == 1 && mode != 1                   // exclude MOVEP (mode 001)
        val isStatBit = ((op >> 8) & 0xf) == 0x8                 // opmode 4
        val bitBase   = if (isStatBit) 2 else 1                  // +1 for the static bit word
        val bitTt     = (op >> 6) & 3                            // 00=BTST, 01/10/11 write
        val isBtst    = (isDynBit || isStatBit) && bitTt == 0
        if (isImmOp && immWords >= 0) {
          if (isToCcr) { if (ccrOk) CP(simple = true, lenWords = 1 + 1) else COMPLEX }
          else if (mode == 0) CP(simple = true, lenWords = 1 + immWords)  // data-reg dest
          else memDestExt(mode, reg) match {                      // mem-dest RMW (imm + EA ext)
            case Some(e) => CP(simple = true, lenWords = 1 + immWords + e)
            case None    => COMPLEX                               // SR / out-of-scope -> deferred
          }
        } else if (isDynBit || isStatBit) {
          if (mode == 0) CP(simple = true, lenWords = bitBase)    // Dn dest (LONG)
          else memDestExt(mode, reg) match {                      // memory dest (BYTE) -> +EA ext
            case Some(e) => CP(simple = true, lenWords = bitBase + e)
            // BTST is read-only, so unlike BCHG/BCLR/BSET it also accepts the two
            // PC-relative source EAs. The opword-only reference models indexed-PC as
            // brief (+1), matching PredecodeWord.classify(op)'s zero extension word;
            // the full RTL overload resolves a supplied full-format extension exactly.
            case None if isBtst && mode == 7 && (reg == 2 || reg == 3) =>
              CP(simple = true, lenWords = bitBase + 1)
            case None    => COMPLEX                               // An/#imm/MEMCOMPLEX -> deferred
          }
        } else if (bit8 == 1 && mode == 1) {
          // MOVEP (0000 rrr 1 oo 001 aaa) + disp16: all 4 variants -> SIMPLE len 2
          // (opword + disp16). Carved out of the dyn-bit-op path (which excludes mode 001).
          CP(simple = true, lenWords = 2)
        } else if (((op >> 11) & 1) == 0 && bit8 == 0 && ((op >> 6) & 3) == 3 &&
                   ((op >> 9) & 3) != 3 && mode >= 2) {
          // CMP2/CHK2 (0000 0ss0 11 mmm rrr) + ext word: bit11==0, ss=op[10:9]
          // (.B/.W/.L, =/=3), bit8==0, bits[7:6]==11, EA a CONTROL mode (mode>=2,
          // reject postinc/predec). len = opword + ext word + EA ext.
          val ctrlMode = mode == 2 || mode == 5 || mode == 6 || mode == 7
          eaExt(mode, reg, sizeL = false, allowImm = false) match {
            case Some(e) if ctrlMode => CP(simple = true, lenWords = 2 + e)
            case _                   => COMPLEX
          }
        } else if (((op >> 11) & 0x1f) == 0x01 && bit8 == 0 && ((op >> 6) & 3) == 3 &&
                   ((op >> 9) & 3) != 0) {
          // CAS / CAS2 (020+ atomic compare-and-swap). CAS `0000 1ss0 11 mmm rrr` +
          // 1 ext + EA ext: op[15:11]=00001, bit8=0, op[7:6]=11, op[10:9]=size (=/=00).
          // CAS2 `...111100` = FIXED 3 words (opword + 2 ext). (Disjoint from CMP2/CHK2,
          // which needs op[11]=0; CAS needs op[11]=1.)
          val isCas2 = (op & 0x3f) == 0x3c                  // mode 7 / reg 4
          if (isCas2) CP(simple = true, lenWords = 3)        // opword + 2 ext words
          else {
            // memory-ALTERABLE EA (Musashi `A+-DXWL...`): (An)=2, (An)+=3, -(An)=4,
            // (d16,An)=5, (d8,An,Xn)=6, (xxx).W/.L=7/0,1. INCLUDES auto-inc/dec.
            val casOk = mode == 2 || mode == 3 || mode == 4 || mode == 5 || mode == 6 ||
                        (mode == 7 && (reg == 0 || reg == 1))
            eaExt(mode, reg, sizeL = false, allowImm = false) match {
              case Some(e) if casOk => CP(simple = true, lenWords = 2 + e)  // opword + 1 ext + EA ext
              case _                => COMPLEX
            }
          }
        } else if (((op >> 8) & 0xff) == 0x0e && ((op >> 6) & 3) != 3) {
          // MOVES (010+ PRIVILEGED) `0000 1110 ss mmm rrr` + 1 ext + EA ext: op[15:8]=0x0E,
          // op[7:6]=ss in {00,01,10} (11 is CAS). len = opword + 1 ext + EA ext. Memory-
          // ALTERABLE EA (SAME mask as CAS): (An)=2,(An)+=3,-(An)=4,(d16,An)=5,(d8,An,Xn)=6,
          // (xxx).W/.L=7/0,1. Reject Dn/An/PC-rel/#imm -> COMPLEX (decode illegalises it).
          val movesOk = mode == 2 || mode == 3 || mode == 4 || mode == 5 || mode == 6 ||
                        (mode == 7 && (reg == 0 || reg == 1))
          eaExt(mode, reg, sizeL = false, allowImm = false) match {
            case Some(e) if movesOk => CP(simple = true, lenWords = 2 + e)  // opword + 1 ext + EA ext
            case _                  => COMPLEX
          }
        } else COMPLEX
      case 0x1 | 0x2 | 0x3 =>
        val sizeL   = cls == 0x2
        val srcMode = (op >> 3) & 7; val srcReg = op & 7
        val dstMode = (op >> 6) & 7; val dstReg = (op >> 9) & 7
        val se = eaExt(srcMode, srcReg, sizeL, allowImm = true)
        val de = dstMode match {
          case 0 | 1 | 2 | 3 | 4 | 5 => eaExt(dstMode, dstReg, sizeL, allowImm = false)
          case 6 =>
            // (d8,An,Xn) brief indexed destination: 1 ext word — but this reference model
            // (like the RTL `classify()` it mirrors) only ever has a 2-word lookahead past
            // the opword (extW@op+1, extW2@op+2). The dest's OWN ext word sits at
            // op+1+se, which is visible only when se<=1. When se>=2 (e.g. an #imm.L
            // source, which itself consumes op+1+op+2), the dest's brief-vs-full status
            // is UNKNOWABLE here -> COMPLEX (F1 fix, 2026-07-11: reject rather than
            // silently assume brief — this exhaustive sweep drives extW=extW2=0, so a
            // naive "always brief" answer would happen to self-consistently match the
            // OLD, buggy RTL, but not the FIXED RTL, which now correctly refuses to
            // guess in this case).
            se match {
              case Some(s) if s <= 1 => Some(1)
              case _                 => None
            }
          // mode 7 reg 2/3 ((d16,PC)/(d8,PC,Xn)) are PC-relative => NOT a MOVE dest -> None.
          case 7 => dstReg match { case 0 => Some(1); case 1 => Some(2); case _ => None }
          case _ => None
        }
        if (se.isEmpty || de.isEmpty) COMPLEX
        else {
          // MOVE incl. mem-to-mem: the assembler cracks mem-to-mem into [load][store]
          // (+ folded An auto-updates). len = opword + src ext + dst ext.
          CP(simple = true, lenWords = 1 + se.get + de.get)
        }
      case 0x4 =>
        // TRAP #n (0x4E4x) / TRAPV (0x4E76): single-word, predecoded simple len-1 so
        // the decoder computes the correct nextPc (= pc+2, the trap's stacked PC).
        // Other line-4 opcodes stay complex.
        val isTrap  = (op & 0xfff0) == 0x4e40
        val isTrapv = op == 0x4e76
        // RTS (0x4E75) / RTR (0x4E77): single-word return instructions (simple len-1).
        val isRts   = op == 0x4e75
        val isRtr   = op == 0x4e77
        // NOP (0x4E71): single-word, no architectural effect (simple len-1). PRE-EXISTING
        // GAP found 2026-07-11 while validating the F1/F2/F3 predecode-overflow fix: this
        // reference model was never updated when NOP predecode support was added to the
        // RTL (`6f6b9ac feat(isa): NOP (0x4E71)`), so the exhaustive 65536-opword sweep
        // was silently failing at op=0x4E71 (rtl simple=true, ref simple=false) before this
        // fix — unrelated to the F-series bugs, but caught (and fixed) here since a stale
        // reference model defeats this test's whole purpose as a regression guard.
        val isNop   = op == 0x4e71
        // CHK.W/CHK.L (0100 ddd 1 s 0 mmmrrr): bit8=1, bit6=0; bound is an EA source
        // (sizeL = .L when bit7=0). 1 opword + the EA extension words.
        val isChk = ((op >> 8) & 1) == 1 && ((op >> 6) & 1) == 0
        // DIVU.L/DIVS.L (0100 1100 01 mmmrrr) / MULU.L/MULS.L (0100 1100 00 mmmrrr):
        // opword + the ext word + the 32-bit EA extension (same framing for both — the
        // RTL PredecodeWord frames both since the multiply slice shipped MUL.L).
        val isDivL = ((op >> 6) & 0x3ff) == 0x131
        val isMulL = ((op >> 6) & 0x3ff) == 0x130
        // JMP (0100111011 mmmrrr) / JSR (0100111010 mmmrrr): computed-target branch to
        // the EA address. 1 opword + control-EA ext. Control modes only:
        // (An)=2, (d16,An)=5, (xxx).W/.L/(d16,PC)=mode7 reg0/1/2.
        val isJmp = ((op >> 6) & 0x3ff) == 0x13b
        val isJsr = ((op >> 6) & 0x3ff) == 0x13a
        // Line-4 single-operand DATA-register family (the unary group): single-word ->
        // SIMPLE len1. CLR/NEG/NEGX/NOT/TST (0100 oooo ss 000rrr, oooo in {0,2,4,6,A},
        // ss != 11, mode 000=Dn) + SWAP (0x4840-47) / EXT.W (0x4880-87) / EXT.L
        // (0x48C0-C7) / EXTB.L (0x49C0-C7) / TAS (0x4AC0-C7). Memory-dest forms (mode !=
        // 000) are the deferred RMW slice -> COMPLEX. bit8=0 except EXTB.L (checked
        // BEFORE isChk, which it would otherwise alias: 0x49C0 has bit8=1 & bit6=0).
        val u4o    = (op >> 8) & 0xf
        val u4ss   = (op >> 6) & 3
        val u4mode = (op >> 3) & 7
        val isUnaryArith = ((op >> 8) & 1) == 0 && u4ss != 3 && u4mode == 0 &&
                           (u4o == 0 || u4o == 2 || u4o == 4 || u4o == 6 || u4o == 0xA)
        val is13 = (op >> 3) & 0x1fff   // op[15:3]
        val isSwap  = is13 == 0x0908    // 0x4840-47
        val isExtW  = is13 == 0x0910    // 0x4880-87
        val isExtL  = is13 == 0x0918    // 0x48C0-C7
        val isExtbL = is13 == 0x0938    // 0x49C0-C7
        val isTas   = is13 == 0x0958    // 0x4AC0-C7
        // NBCD Dn (0x4800-07): the implemented register-only form is one word.
        // Memory-EA NBCD remains deferred, so keep this exact 13-bit pattern rather
        // than widening the generic unary-EA table.
        val isNbcd  = is13 == 0x0900
        val isUnary = isUnaryArith || isSwap || isExtW || isExtL || isExtbL || isTas || isNbcd
        // CLR/NEG/NEGX/NOT/TST <ea> mem-dest (RMW): mode != 000, ss != 11, oooo in
        // {0,2,4,6,A}, bit8=0. In-scope MEMSIMPLE dest -> opword + EA ext. SWAP/EXT/TAS
        // are Dn-only (mode 000); TAS-mem deferred.
        val isUnaryMem = ((op >> 8) & 1) == 0 && u4ss != 3 && u4mode != 0 &&
                         (u4o == 0 || u4o == 2 || u4o == 4 || u4o == 6 || u4o == 0xA)
        // LINK An,#disp16 (op[15:4]==0x4E5, op[3]=0): opword + disp16 -> simple len 2.
        // UNLK An (op[3]=1): single word -> simple len 1. (bit6=1 here -> not isChk.)
        val isLink = (op & 0xfff8) == 0x4e50
        val isUnlk = (op & 0xfff8) == 0x4e58
        // 68020+ LINK.L An,#disp32 occupies the otherwise-An-direct NBCD region
        // 0x4808-0x480f and carries two displacement extension words.
        val isLinkL = (op & 0xfff8) == 0x4808
        if (isLinkL) CP(simple = true, lenWords = 3)
        else if (isLink) CP(simple = true, lenWords = 2)
        else if (isUnlk) CP(simple = true, lenWords = 1)
        else if (isTrap || isTrapv || isRts || isRtr || isNop) CP(simple = true, lenWords = 1)
        else if (isUnary) CP(simple = true, lenWords = 1)
        else if (isUnaryMem) memDestExt(u4mode, op & 7) match {
          case Some(e) => CP(simple = true, lenWords = 1 + e)
          case None    => COMPLEX
        }
        else if (isChk) {
          val sizeL   = ((op >> 7) & 1) == 0
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          eaExt(srcMode, srcReg, sizeL, allowImm = true) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else if (isDivL || isMulL) {
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          eaExt(srcMode, srcReg, sizeL = true, allowImm = true) match {
            case Some(e) => CP(simple = true, lenWords = 2 + e)
            case None    => COMPLEX
          }
        } else if (isJmp || isJsr) {
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          val ctrlMode = srcMode == 2 || srcMode == 5 || srcMode == 7
          eaExt(srcMode, srcReg, sizeL = false, allowImm = false) match {
            case Some(e) if ctrlMode => CP(simple = true, lenWords = 1 + e)
            case _                   => COMPLEX
          }
        } else if (((op >> 6) & 0x3ff) == 0x11b) {
          // MOVE to SR (0100 0110 11 mmmrrr): opword + the .W source EA ext words.
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          eaExt(srcMode, srcReg, sizeL = false, allowImm = true) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else if ((op & 0xfff0) == 0x4e60) {
          // MOVE USP (0100 1110 0110 d rrr = 0x4E6x): single word.
          CP(simple = true, lenWords = 1)
        } else if ((op & 0xfffe) == 0x4e7a) {
          // MOVEC (0x4E7A Rc->Rn / 0x4E7B Rn->Rc): opword + 1 ext word {A/D|reg#|Rc}.
          CP(simple = true, lenWords = 2)
        } else if (op == 0x4e74) {
          // RTD (0x4E74) + disp16: opword + 1 disp word.
          CP(simple = true, lenWords = 2)
        } else if (op == 0x4e70) {
          // RESET (0x4E70): single-word privileged sysOp.
          CP(simple = true, lenWords = 1)
        } else if (op == 0x4e72) {
          // STOP (0x4E72) + imm16: opword + 1 imm word.
          CP(simple = true, lenWords = 2)
        } else if (op.&(0x0800) != 0 && ((op >> 7) & 7) == 1) {
          // MOVEM (0100 1 d 001 s mmmrrr) + 16-bit mask: opword + mask + EA ext. In-scope:
          // (An)/(An)+/-(An) +0, (d16,An)/(xxx).W/(d16,PC) +1, (xxx).L +2. Direction (bit10)
          // restricts (An)+ to LOAD, -(An) to STORE, (d16,PC) to LOAD. Out-of-scope modes
          // (indexed/reg-direct/#imm) -> COMPLEX. (No alias: bit8=0 so not CHK; u4o = 8/C
          // so not the unary group.)
          val mmDir  = ((op >> 10) & 1) == 1   // 0 store / 1 load
          val mmMode = (op >> 3) & 7
          val mmReg  = op & 7
          val mmExt: Option[Int] = mmMode match {
            case 2 => Some(0)                               // (An)
            case 3 => if (mmDir) Some(0) else None          // (An)+ LOAD only
            case 4 => if (!mmDir) Some(0) else None         // -(An) STORE only
            case 5 => Some(1)                               // (d16,An)
            case 7 => mmReg match {
              case 0 => Some(1)                             // (xxx).W
              case 1 => Some(2)                             // (xxx).L
              case 2 => if (mmDir) Some(1) else None        // (d16,PC) LOAD only
              case _ => None
            }
            case _ => None
          }
          mmExt match {
            case Some(e) => CP(simple = true, lenWords = 2 + e)
            case None    => COMPLEX
          }
        } else if (((op >> 8) & 1) == 1 && ((op >> 6) & 3) == 3 && (((op >> 3) & 7) >= 2)) {
          // LEA An,<ea> (0100 An 1 11 mmmrrr): bit8=1, bits7:6=11, mode>=2 (control EA;
          // reg-direct mode0/1 + EXTB.L mode0 excluded). opword + EA ext. (isUnary above
          // already consumed EXTB.L.) eaExt accepts the in-scope modes; an out-of-scope
          // control EA frames len via eaExt anyway (the assembler faults the illegal EA).
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          eaExt(srcMode, srcReg, sizeL = false, allowImm = false) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else if (((op >> 6) & 0x3ff) == 0x121) {
          // PEA <ea> (0100 1000 01 mmmrrr): control EA, opword + EA ext.
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          eaExt(srcMode, srcReg, sizeL = false, allowImm = false) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else if (((op >> 6) & 0x3ff) == 0x103 || ((op >> 6) & 0x3ff) == 0x10b) {
          // MOVE from SR (0x40C0) / from CCR (0x42C0): SR/CCR -> EA (.W), data EA. opword
          // + EA ext (no #imm dest).
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          eaExt(srcMode, srcReg, sizeL = false, allowImm = false) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else if (((op >> 6) & 0x3ff) == 0x113) {
          // MOVE to CCR (0x44C0): EA(.W) -> CCR, data EA incl #imm. opword + EA ext.
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          eaExt(srcMode, srcReg, sizeL = false, allowImm = true) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else COMPLEX
      // Line-5: ADDQ/SUBQ (ss != 11) + Scc/DBcc (ss == 11). See PredecodeWord.
      case 0x5 =>
        val ss   = (op >> 6) & 3
        val mode = (op >> 3) & 7
        if (ss != 3) {
          if (mode == 0 || mode == 1) CP(simple = true, lenWords = 1)  // ADDQ/SUBQ Dn/An
          else memDestExt(mode, op & 7) match {                        // ADDQ/SUBQ #n,<ea> mem-dest
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else {
          if (mode == 1) CP(simple = true, lenWords = 2)               // DBcc + disp16
          else if (mode == 0) CP(simple = true, lenWords = 1)          // Scc Dn
          else if (mode == 7) {                                        // TRAPcc (mode 7)
            val ttt = op & 7
            ttt match {
              case 4 => CP(simple = true, lenWords = 1)               // TRAPcc (no operand)
              case 2 => CP(simple = true, lenWords = 2)               // TRAPcc.W (#data16)
              case 3 => CP(simple = true, lenWords = 3)               // TRAPcc.L (#data32)
              case _ => COMPLEX                                        // other ttt -> illegal
            }
          } else COMPLEX                                               // mem Scc -> deferred
        }
      case 0x7 =>
        if (((op >> 8) & 1) == 0) CP(simple = true, lenWords = 1) else COMPLEX
      case 0x6 =>
        val d8 = op & 0xff
        val len = if (d8 == 0x00) 2 else if (d8 == 0xff) 3 else 1
        CP(simple = true, lenWords = len)
      case 0x8 | 0x9 | 0xC | 0xD =>
        val opmode  = (op >> 6) & 7
        val srcMode = (op >> 3) & 7; val srcReg = op & 7
        // classes 0x8(OR-group)/0xC(AND-group) opmode 3/7 = DIVU/DIVS/MULU/MULS.
        //   BOTH DIVs are line 8 (DIVU.W opmode 3, DIVS.W opmode 7) -> SIMPLE (1 +
        //   16-bit divisor EA ext). BOTH MULs are line C (opmode 3/7) -> SIMPLE too
        //   (the multiply slice ships MULU.W/MULS.W framing; An-direct mode1 is NOT a
        //   legal MUL EA so it stays complex -> mulEaOk = srcMode != 1). The RTL
        //   PredecodeWord frames both; this ref mirrors it.
        val isDivuW = (cls == 0x8) && (opmode == 3)
        val isDivsW = (cls == 0x8) && (opmode == 7)
        val mulEaOk = srcMode != 1
        val isMuluW = (cls == 0xC) && (opmode == 3) && mulEaOk
        val isMulsW = (cls == 0xC) && (opmode == 7) && mulEaOk
        val isMulDiv = (cls == 0x8 || cls == 0xC) && (opmode == 3 || opmode == 7)
        val srcMode0 = (op >> 3) & 7; val srcReg0 = op & 7
        if (isDivuW || isDivsW || isMuluW || isMulsW) {
          eaExt(srcMode0, srcReg0, sizeL = false, allowImm = true) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        }
        else if (isMulDiv) COMPLEX
        // ADDX/SUBX register form (line 9/D, opmode 4/5/6, EA mode 000 = Dn-direct):
        // a single 1-word op (Dx := Dx +/- Dy +/- X). Mode 000 is NOT a valid
        // ADD/SUB-to-mem dst, so frame it simple len1. Memory form (mode 001) -> COMPLEX.
        // ADDX/SUBX register form (mode 000) AND -(Ay),-(Ax) MEMORY form (mode 001,
        // microcoded): both are single 1-word ops (the mem form's 6 µops come from the
        // DecodeStage µcode sequencer; predecode only needs LEN=1). Mode 001 is An-direct
        // (never a valid ADD/SUB mem dst) so it is unambiguously the X-mem form.
        else if ((cls == 0x9 || cls == 0xD) && (opmode == 4 || opmode == 5 || opmode == 6) && (srcMode == 0 || srcMode == 1))
          CP(simple = true, lenWords = 1)
        // EXG (line C, bit8=1): 1100 xxx 1 ooooo yyy with ooooo (bits 7:3) in {01000,01001,
        // 10001} (EXG Dx,Dy / Ax,Ay / Dx,Ay). A single-word reg-reg swap -> simple len1
        // (mirrors the RTL PredecodeWord, which frames it before the AND-RMW band).
        else if (cls == 0xC && ((op >> 8) & 1) == 1 &&
                 (((op >> 3) & 0x1f) == 0x08 || ((op >> 3) & 0x1f) == 0x09 || ((op >> 3) & 0x1f) == 0x11))
          CP(simple = true, lenWords = 1)
        // ABCD (line C) / SBCD (line 8) register form: opmode 4, EA mode 000 (Dn-direct).
        // A single 1-word op (Dx := BCD(Dx +/- Dy +/- X)). Mode 000 is NOT a valid mem-dst,
        // so frame it simple len1 (mirrors the RTL). Memory form (mode 001) -> COMPLEX.
        // ABCD/SBCD register form (mode 000) AND -(Ay),-(Ax) MEMORY form (mode 001,
        // microcoded): both single 1-word ops (opmode 4 only; the mem 6 µops come from
        // the µcode sequencer). Mode 001 is An-direct (not a valid mem dst) -> X-mem form.
        else if ((cls == 0x8 || cls == 0xC) && opmode == 4 && (srcMode == 0 || srcMode == 1))
          CP(simple = true, lenWords = 1)
        // PACK (line 8, opmode 5) / UNPK (line 8, opmode 6): opword + 16-bit adj word = len 2.
        // Both the register form (srcMode 000) AND the deferred memory form (srcMode 001) are
        // framed as len=2 so the front-end doesn't stall; the decode illegalises the mem form.
        // Line C opmode 5/6 is OR.W/.L, NOT PACK/UNPK — excluded.
        else if (cls == 0x8 && (opmode == 5 || opmode == 6) && (srcMode == 0 || srcMode == 1))
          CP(simple = true, lenWords = 2)
        else if (opmode == 4 || opmode == 5 || opmode == 6)        // ALU Dn,<ea> RMW mem-dest
          memDestExt(srcMode, srcReg) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        else {
          val sizeL = opmode == 2 || opmode == 7   // opmode 7 here can only be ADDA.L/SUBA.L (cls 9/D)
          eaExt(srcMode, srcReg, sizeL, allowImm = false) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        }
      case 0xB =>
        val opmode  = (op >> 6) & 7
        val srcMode = (op >> 3) & 7; val srcReg = op & 7
        // CMP (opmode 0/1/2 = .B/.W/.L, EA source) and CMPA (3/7). EOR (opmode 4/5/6)
        // is a SEPARATE family: EA is the DESTINATION (read AND written). This slice
        // frames the register (data-reg, mode0) EOR dest as simple len1; memory-dest
        // EOR (RMW) as simple opword+EA-ext; An-direct (mode1) is CMPM (Ay)+,(Ax)+, a
        // single opword with NO extension words (see isCmpm below).
        val isEor  = opmode == 4 || opmode == 5 || opmode == 6
        // CMPM (Ay)+,(Ax)+: same opmode band as EOR, but An-direct (srcMode==1) — a
        // FIXED single-opword encoding (the "mode 001" bits here are literally CMPM's own
        // opcode pattern, not a real EA mode). PRE-EXISTING GAP found 2026-07-11 (same
        // class as the NOP gap above): this reference model never got a CMPM case when
        // CMPM predecode support was added to the RTL (`2e93d88`/`2e04cc8`), so the
        // exhaustive sweep was silently failing at every CMPM opword (e.g. 0xB108) —
        // unrelated to the F-series bugs, fixed here for the same reason as NOP.
        val isCmpm = isEor && srcMode == 1
        if (opmode == 0 || opmode == 1 || opmode == 2 || opmode == 3 || opmode == 7) {
          val sizeL = opmode == 2 || opmode == 7
          eaExt(srcMode, srcReg, sizeL, allowImm = false) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else if (isEor && srcMode == 0) CP(simple = true, lenWords = 1)  // EOR Dn,Dm
        else if (isCmpm) CP(simple = true, lenWords = 1)                   // CMPM (Ay)+,(Ax)+
        else if (isEor) memDestExt(srcMode, srcReg) match {                // EOR Dn,<ea> mem-dest
          case Some(e) => CP(simple = true, lenWords = 1 + e)
          case None    => COMPLEX                                          // MEMCOMPLEX
        }
        else COMPLEX
      // Line-E register-form shifts/rotates (1110 ccc d ss i tt rrr): single-word.
      // ss=11 is the memory single-bit form (deferred RMW) -> COMPLEX.
      case 0xE =>
        val ss = (op >> 6) & 3
        // Bit-field register form: op[11]=1, ss==3, mode 000 -> SIMPLE len 2
        // (opword + the bit-field ext word). ss=11 with op[11]=0 (memory single-bit
        // shift) or mode!=0 (memory bit-field) -> COMPLEX (deferred RMW).
        val isBitfieldReg = (((op >> 11) & 1) == 1) && (ss == 3) && (((op >> 3) & 7) == 0)
        // Bit-field MEMORY form (BFxxx <ea>): op[11]=1, ss==3, mode>=2 (memory EA). len =
        // opword + bf-ext word + the EA's own ext words (per mode), i.e. 2 + eaExt. Predecode
        // computes LENGTH only, identical for the load-only ops {BFTST/BFEXTU/BFEXTS/BFFFO}
        // and the RMW ops {BFCHG/BFCLR/BFSET/BFINS}, so frame ALL of them (the LEGALITY split
        // — PC-rel illegal for RMW, control-alterable only — is enforced in OperationDecoder).
        // Mirrors the RTL PredecodeWord isBitfieldMem arm.
        val bfMemMode = (op >> 3) & 7
        val isBitfieldMem = (((op >> 11) & 1) == 1) && (ss == 3) && (bfMemMode >= 2)
        if (isBitfieldReg) CP(simple = true, lenWords = 2)
        else if (isBitfieldMem) eaExt(bfMemMode, op & 7, sizeL = false, allowImm = false) match {
          case Some(e) => CP(simple = true, lenWords = 2 + e)   // opword + bf-ext + EA ext
          case None    => COMPLEX
        }
        else if (ss != 3) CP(simple = true, lenWords = 1)
        else COMPLEX
      case _ => COMPLEX
    }
  }
}
