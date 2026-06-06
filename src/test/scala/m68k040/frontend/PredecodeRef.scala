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
        // CHK.W/CHK.L (0100 ddd 1 s 0 mmmrrr): bit8=1, bit6=0; bound is an EA source
        // (sizeL = .L when bit7=0). 1 opword + the EA extension words.
        val isChk = ((op >> 8) & 1) == 1 && ((op >> 6) & 1) == 0
        if (isTrap || isTrapv) CP(simple = true, lenWords = 1)
        else if (isChk) {
          val sizeL   = ((op >> 7) & 1) == 0
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          eaExt(srcMode, srcReg, sizeL, allowImm = true) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
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
        //   16-bit divisor EA ext). BOTH MULs are line C (opmode 3/7) -> complex.
        val isDivuW = (cls == 0x8) && (opmode == 3)
        val isDivsW = (cls == 0x8) && (opmode == 7)
        val isMulDiv = (cls == 0x8 || cls == 0xC) && (opmode == 3 || opmode == 7)
        val srcMode0 = (op >> 3) & 7; val srcReg0 = op & 7
        if (isDivuW || isDivsW) {
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
        if (opmode == 0 || opmode == 1 || opmode == 2 || opmode == 3 || opmode == 7) {
          val srcMode = (op >> 3) & 7; val srcReg = op & 7
          val sizeL = opmode == 2 || opmode == 7
          eaExt(srcMode, srcReg, sizeL, allowImm = false) match {
            case Some(e) => CP(simple = true, lenWords = 1 + e)
            case None    => COMPLEX
          }
        } else COMPLEX
      case _ => COMPLEX
    }
  }
}
