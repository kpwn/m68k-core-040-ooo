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
    case 6                 => None           // (d8,An,Xn) indexed -> complex
    case 7 => reg match {
      case 0 => Some(1)                      // abs.W
      case 1 => Some(2)                      // abs.L
      case 2 => Some(1)                      // (d16,PC)
      case 3 => None                         // (d8,PC,Xn)
      case 4 => if (allowImm) Some(if (sizeL) 2 else 1) else None  // #imm
      case _ => None
    }
    case _ => None
  }
  def isMem(mode: Int): Boolean = mode > 1   // not Dn(0)/An(1)

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
      //   - to-CCR (...00 111100, size byte, mode7/reg4): 1 + 1 (the imm byte word).
      // SR forms (...01 111100, word) are privileged -> COMPLEX (out of scope). Memory
      // destinations are the deferred RMW slice -> COMPLEX. Everything else COMPLEX.
      case 0x0 =>
        val opmode = (op >> 9) & 7
        val bit8   = (op >> 8) & 1
        val ss     = (op >> 6) & 3
        val mode   = (op >> 3) & 7
        val reg    = op & 7
        val isImmOp = bit8 == 0 && (opmode == 0 || opmode == 1 || opmode == 2 ||
                                    opmode == 3 || opmode == 5 || opmode == 6)
        val immWords = ss match { case 0 | 1 => 1; case 2 => 2; case _ => -1 }  // -1 = illegal size
        val isToCcr = mode == 7 && reg == 4 && ss == 0           // ANDI/ORI/EORI #imm,CCR (byte)
        val ccrOk   = opmode == 0 || opmode == 1 || opmode == 5  // ANDI/ORI/EORI only (to CCR)
        if (!isImmOp || immWords < 0) COMPLEX
        else if (isToCcr) { if (ccrOk) CP(simple = true, lenWords = 1 + 1) else COMPLEX }
        else if (mode == 0) CP(simple = true, lenWords = 1 + immWords)  // data-reg dest
        else COMPLEX                                              // mem dest / SR -> deferred
      case 0x1 | 0x2 | 0x3 =>
        val sizeL   = cls == 0x2
        val srcMode = (op >> 3) & 7; val srcReg = op & 7
        val dstMode = (op >> 6) & 7; val dstReg = (op >> 9) & 7
        val se = eaExt(srcMode, srcReg, sizeL, allowImm = true)
        val de = dstMode match {
          case 0 | 1 | 2 | 3 | 4 | 5 => eaExt(dstMode, dstReg, sizeL, allowImm = false)
          case 7 => dstReg match { case 0 => Some(1); case 1 => Some(2); case _ => None }
          case _ => None
        }
        if (se.isEmpty || de.isEmpty) COMPLEX
        else {
          val srcMem = isMem(srcMode)
          val dstMem = dstMode != 1 && isMem(dstMode)
          if (srcMem && dstMem) COMPLEX
          else CP(simple = true, lenWords = 1 + se.get + de.get)
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
        if (isTrap || isTrapv || isRts || isRtr) CP(simple = true, lenWords = 1)
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
        } else COMPLEX
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
        else if (opmode == 4 || opmode == 5 || opmode == 6 || isMulDiv) COMPLEX
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
        // frames the register (data-reg, mode0) EOR dest as simple len1; An-direct
        // (mode1 = CMPM) and memory-dest EOR (RMW) are deferred -> COMPLEX.
        val isEor = opmode == 4 || opmode == 5 || opmode == 6
        if (opmode == 0 || opmode == 1 || opmode == 2 || opmode == 3 || opmode == 7) {
          val sizeL = opmode == 2 || opmode == 7
          eaExt(srcMode, srcReg, sizeL, allowImm = false) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else if (isEor && srcMode == 0) CP(simple = true, lenWords = 1)  // EOR Dn,Dm
        else COMPLEX
      case _ => COMPLEX
    }
  }
}
