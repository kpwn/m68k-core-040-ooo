package m68k040.frontend

import m68k040.cache.ChunkPredecode
import spinal.core._

/** CPU_PREDECODE_CODED: `PredecodeWord.classify` with the length arithmetic factored out of
  * the decision logic. Bit-identical to `PredecodeWord.classify(op, w1, w2, w3, k1, k2, k3)`
  * (PredecodeCodedEquivSpec).
  *
  * Every EA-extension length in `classify` is either an op-only constant, or -- for the
  * indexed modes (d8,An,Xn)/(d8,PC,Xn) -- `known ? (w(8) ? fullExtLen(w) : 1) : 1` of ONE of
  * the three lookahead words. The decision chain below therefore carries a CODE
  * {lenC: op-only constant, lenSel: one-hot lookahead-word select} instead of a 4-bit value,
  * and the length is ONE add at the end: lenC + AND-OR(lenSel, ix). `ix(j)` is a per-word
  * value, so a caller classifying a whole chunk computes it ONCE per absolute word
  * (`PredecodeCoded.ix`) and every classifier that sees that word as lookahead shares it.
  * The ambiguity bit is carried the same way {ambC, ambSel} -> ambC | (ambSel & ~known).
  * MOVE (two EAs, the destination's lookahead word depends on the source's length) keeps its
  * own add and is selected by `useMove`. */
object PredecodeCoded {
  def fullExtLen(eaW: Bits): UInt = {
    val bdSz  = eaW(5 downto 4).asUInt
    val bdW   = Mux(bdSz === U(2, 2 bits), U(1, 3 bits),
                Mux(bdSz === U(3, 2 bits), U(2, 3 bits), U(0, 3 bits)))
    val odW   = Mux(!eaW(1), U(0, 3 bits), Mux(eaW(0), U(2, 3 bits), U(1, 3 bits)))
    (U(1, 3 bits) + bdW + odW).resize(3)
  }

  /** Extension length of an indexed EA whose (brief|full) extension word is `w`. */
  def ix(w: Bits, known: Bool): UInt =
    Mux(known, Mux(w(8), fullExtLen(w), U(1, 3 bits)), U(1, 3 bits))

  /** `ChunkPredecode.ctrlXfer` exactly as `PredecodeWord.classify` computes it (op only). */
  def ctrlXfer(op: Bits): Bool = {
    val cxLine6  = op(15 downto 12) === B"4'h6"
    val cxDbcc   = (op(15 downto 12) === B"4'h5") &&
                   (op(7 downto 6) === B"2'b11") && (op(5 downto 3) === B"3'b001")
    val cxJmpJsr = op(15 downto 7) === B"9'b010011101"
    val cxRet    = (op(15 downto 3) === B"13'b0100111001110") &&
                   ((op(2 downto 0) === B"3'b011") || (op(2 downto 0) === B"3'b100") ||
                    (op(2 downto 0) === B"3'b101") || (op(2 downto 0) === B"3'b111"))
    val cxFpT    = (op(15 downto 12) === B"4'hF") && (op(11 downto 9) === B"3'b001")
    val cxFbcc   = cxFpT && (op(8 downto 7) === B"2'b01") && (op(5 downto 0) =/= B"6'b000000")
    val cxFdbcc  = cxFpT && (op(8 downto 6) === B"3'b001") && (op(5 downto 3) === B"3'b001")
    cxLine6 || cxDbcc || cxJmpJsr || cxRet || cxFbcc || cxFdbcc
  }

  /** Classify `op` given its three lookahead words, their validity, and their shared `ix`. */
  def classify(op: Bits, extW: Bits, extW2: Bits, extW3: Bits,
               extWKnown: Bool, extW2Known: Bool, extW3Known: Bool,
               ix1: UInt, ix2: UInt, ix3: UInt): ChunkPredecode = {
    val r = ChunkPredecode()
    r.size := m68k040.decode.OperationDecoder.decode(op).size

    r.ctrlXfer := ctrlXfer(op)

    val knV = Vec(extWKnown, extW2Known, extW3Known)
    val ixV = Vec(ix1, ix2, ix3)
    val W1 = B"3'b001"; val W2 = B"3'b010"; val W3 = B"3'b100"; val WN = B"3'b000"

    // ---- the code ----
    val lenC    = UInt(4 bits); lenC    := U(0, 4 bits)
    val lenSel  = Bits(3 bits); lenSel  := WN
    val useMove = Bool();       useMove := False
    val ambC    = Bool();       ambC    := False
    val ambSel  = Bits(3 bits); ambSel  := WN

    /** Symbolic EA extension: ext = c + (sel selects a word ? ix(word) : 0). */
    case class Ext(c: UInt, sel: Bits)
    def setLen(k: Int, e: Ext): Unit = { lenC := (U(k, 4 bits) + e.c.resize(4)); lenSel := e.sel; useMove := False }
    def setLenK(k: UInt): Unit = { lenC := k.resize(4); lenSel := WN; useMove := False }
    def setLenN(n: Int): Unit = setLenK(U(n, 4 bits))
    def setAmb(sel: Bits): Unit = { ambC := False; ambSel := sel }

    // Mirrors classify's memDestExt: (ok, ext, amb) -- amb selector = ext selector.
    def memDestExt(mode: UInt, reg: UInt, wsel: Bits): (Bool, Ext) = {
      val ok = Bool(); val c = UInt(3 bits); val sel = Bits(3 bits)
      ok := False; c := U(0, 3 bits); sel := WN
      switch(mode) {
        is(U(2, 3 bits), U(3, 3 bits), U(4, 3 bits)) { ok := True }
        is(U(5, 3 bits)) { ok := True; c := U(1, 3 bits) }
        is(U(6, 3 bits)) { ok := True; sel := wsel }
        is(U(7, 3 bits)) {
          switch(reg) {
            is(U(0, 3 bits)) { ok := True; c := U(1, 3 bits) }
            is(U(1, 3 bits)) { ok := True; c := U(2, 3 bits) }
            default { ok := False }
          }
        }
      }
      (ok, Ext(c, sel))
    }
    // Mirrors classify's eaExt.
    def eaExt(mode: UInt, reg: UInt, sizeL: Bool, allowImm: Boolean, wsel: Bits): (Bool, Ext) = {
      val ok = Bool(); val c = UInt(3 bits); val sel = Bits(3 bits)
      ok := True; c := U(0, 3 bits); sel := WN
      switch(mode) {
        is(U(5, 3 bits)) { c := U(1, 3 bits) }
        is(U(6, 3 bits)) { sel := wsel }
        is(U(7, 3 bits)) {
          switch(reg) {
            is(U(0, 3 bits)) { c := U(1, 3 bits) }
            is(U(1, 3 bits)) { c := U(2, 3 bits) }
            is(U(2, 3 bits)) { c := U(1, 3 bits) }
            is(U(3, 3 bits)) { sel := wsel }
            is(U(4, 3 bits)) {
              if (allowImm) c := Mux(sizeL, U(2, 3 bits), U(1, 3 bits)) else ok := False
            }
            default { ok := False }
          }
        }
        default {}
      }
      (ok, Ext(c, sel))
    }

    val cls = op(15 downto 12).asUInt

    // ---- MOVE (cls 1/2/3): computed outside the switch, selected by `useMove` ----
    val mvSizeL   = cls === U(2, 4 bits)
    val mvSrcMode = op(5 downto 3).asUInt
    val mvSrcReg  = op(2 downto 0).asUInt
    val mvDstMode = op(8 downto 6).asUInt
    val mvDstReg  = op(11 downto 9).asUInt
    val (mvSOk, mvS) = eaExt(mvSrcMode, mvSrcReg, mvSizeL, allowImm = true, W1)
    val mvSExt = mvS.c + Mux(mvS.sel(0), ix1, U(0, 3 bits))
    val mvSAmb = mvS.sel(0) && !extWKnown
    val mvDstKn = mvSExt.mux(0 -> extWKnown, 1 -> extW2Known, 2 -> extW3Known, default -> False)
    val mvDstIx = mvSExt.mux(0 -> ix1, 1 -> ix2, 2 -> ix3, default -> U(1, 3 bits))
    val mvDOk  = Bool(); val mvDExt = UInt(3 bits); val mvDAmb = Bool()
    mvDOk := True; mvDExt := U(0, 3 bits); mvDAmb := False
    when(mvDstMode === U(7, 3 bits)) {
      switch(mvDstReg) {
        is(U(0, 3 bits)) { mvDExt := U(1, 3 bits) }
        is(U(1, 3 bits)) { mvDExt := U(2, 3 bits) }
        default          { mvDOk  := False }
      }
    } elsewhen(mvDstMode === U(6, 3 bits)) {
      mvDExt := mvDstIx
      when(!mvDstKn) { mvDAmb := True }
    } elsewhen(mvDstMode === U(5, 3 bits)) {
      mvDExt := U(1, 3 bits)
    }
    val mvTotal = U(1, 3 bits) +^ mvSExt +^ mvDExt
    val mvOk    = mvSOk && mvDOk && (mvTotal <= U(Aligner.WINDOW, mvTotal.getWidth bits))

    switch(cls) {
      is(U(0, 4 bits)) {
        val opmode = op(11 downto 9).asUInt
        val bit8   = op(8)
        val ss     = op(7 downto 6).asUInt
        val mode   = op(5 downto 3).asUInt
        val reg    = op(2 downto 0).asUInt
        val isImmOp = !bit8 && (opmode === 0 || opmode === 1 || opmode === 2 ||
                                opmode === 3 || opmode === 5 || opmode === 6)
        val sizeOk   = ss =/= 3
        val immWords = Mux(ss === 2, U(2, 3 bits), U(1, 3 bits))
        val isToCcrSr = (mode === 7) && (reg === 4) && (ss === 0 || ss === 1)
        val ccrOk    = opmode === 0 || opmode === 1 || opmode === 5
        when(isImmOp && sizeOk) {
          when(isToCcrSr) {
            when(ccrOk) { setLenN(2) }
          } elsewhen(mode === 0) {
            setLenK(U(1, 4 bits) + immWords.resize(4))
          } otherwise {
            val immDstSel = Mux(ss === U(2, 2 bits), W3, W2)
            val (mok, me) = memDestExt(mode, reg, immDstSel)
            when(mok) {
              lenC := U(1, 4 bits) + immWords.resize(4) + me.c.resize(4); lenSel := me.sel; useMove := False
              setAmb(me.sel)
            } elsewhen(opmode === 6 && mode === 7 && (reg === 2 || reg === 3)) {
              val (pok, pe) = eaExt(mode, reg, False, allowImm = false, immDstSel)
              when(pok) {
                lenC := U(1, 4 bits) + immWords.resize(4) + pe.c.resize(4); lenSel := pe.sel; useMove := False
                setAmb(pe.sel)
              }
            }
          }
        }
        val isDynBit  = bit8 && (mode =/= U(1, 3 bits))
        val isStatBit = op(11 downto 8) === B"1000"
        val bitBase   = Mux(isStatBit, U(2, 4 bits), U(1, 4 bits))
        val bitTt    = op(7 downto 6)
        val isBtstOp = (isDynBit || isStatBit) && (bitTt === B(0, 2 bits))
        when(isDynBit || isStatBit) {
          when(mode === U(0, 3 bits)) {
            setLenK(bitBase)
          } otherwise {
            val bitDstSel  = Mux(isStatBit, W2, W1)
            val bitDstKnown = Mux(isStatBit, extW2Known, extWKnown)
            val (mok, me) = memDestExt(mode, reg, bitDstSel)
            val bitLenChain = when(mok) {
              lenC := bitBase + me.c.resize(4); lenSel := me.sel; useMove := False
              setAmb(me.sel)
            } elsewhen(isBtstOp && (mode === U(7, 3 bits)) && (reg === U(2, 3 bits))) {
              setLenK(bitBase + U(1, 4 bits))
            } elsewhen(isBtstOp && (mode === U(7, 3 bits)) && (reg === U(3, 3 bits))) {
              // only when known: ix(word) = the full/brief length then.
              when(bitDstKnown) { lenC := bitBase; lenSel := bitDstSel; useMove := False }
            }
            if (m68k040.top.ShippingCoreConfig.upc10) {
              bitLenChain.elsewhen(isBtstOp && isDynBit && (mode === U(7, 3 bits)) && (reg === U(4, 3 bits))) {
                setLenN(2)
              }
            }
          }
        }
        val isMovep = bit8 && (mode === U(1, 3 bits))
        when(isMovep) { setLenN(2) }
        val isCmp2Chk2 = !op(11) && !bit8 && (op(7 downto 6).asUInt === U(3, 2 bits)) &&
                         (op(10 downto 9).asUInt =/= U(3, 2 bits)) && (mode >= U(2, 3 bits))
        when(isCmp2Chk2) {
          val ctrlMode = (mode === U(2, 3 bits)) || (mode === U(5, 3 bits)) ||
                         (mode === U(6, 3 bits)) || (mode === U(7, 3 bits))
          val (ok, e) = eaExt(mode, reg, sizeL = False, allowImm = false, W2)
          when(ok && ctrlMode) { setLen(2, e); setAmb(e.sel) }
        }
        val isCasFamily = (op(15 downto 11) === B"00001") && !bit8 &&
                          (op(7 downto 6) === B"11") && (op(10 downto 9) =/= B"00")
        val isCas2Pre   = op(5 downto 0) === B"111100"
        when(isCasFamily) {
          when(isCas2Pre) {
            setLenN(3)
          } otherwise {
            val casOk = (mode === U(2, 3 bits)) || (mode === U(3, 3 bits)) || (mode === U(4, 3 bits)) ||
                        (mode === U(5, 3 bits)) || (mode === U(6, 3 bits)) ||
                        ((mode === U(7, 3 bits)) && ((reg === U(0, 3 bits)) || (reg === U(1, 3 bits))))
            val (ok, e) = eaExt(mode, reg, sizeL = False, allowImm = false, W2)
            when(ok && casOk) { setLen(2, e); setAmb(e.sel) }
          }
        }
        val isMovesFamily = (op(15 downto 8) === B"00001110") && (op(7 downto 6) =/= B"11")
        when(isMovesFamily) {
          val movesOk = (mode === U(2, 3 bits)) || (mode === U(3, 3 bits)) || (mode === U(4, 3 bits)) ||
                        (mode === U(5, 3 bits)) || (mode === U(6, 3 bits)) ||
                        ((mode === U(7, 3 bits)) && ((reg === U(0, 3 bits)) || (reg === U(1, 3 bits))))
          val (ok, e) = eaExt(mode, reg, sizeL = False, allowImm = false, W2)
          when(ok && movesOk) { setLen(2, e); setAmb(e.sel) }
        }
      }

      is(U(1, 4 bits), U(2, 4 bits), U(3, 4 bits)) {
        when(mvOk) {
          useMove := True
          ambC := mvSAmb || mvDAmb; ambSel := WN
        }
      }

      is(U(4, 4 bits)) {
        val isTrap  = op(15 downto 4) === B"12'h4E4"
        val isTrapv = op === B"16'h4E76"
        val isRts   = op === B"16'h4E75"
        val isRtr   = op === B"16'h4E77"
        val isNop   = op === B"16'h4E71"
        when(isTrap || isTrapv || isRts || isRtr || isNop) { setLenN(1) }
        val isLink = (op(15 downto 4) === B"12'h4E5") && !op(3)
        val isUnlk = (op(15 downto 4) === B"12'h4E5") &&  op(3)
        when(isLink) { setLenN(2) }
        when(isUnlk) { setLenN(1) }
        val isLinkL = op(15 downto 3) === B(0x901, 13 bits)
        when(isLinkL) { setLenN(3) }
        val isMoveToSr = op(15 downto 6) === B"10'b0100011011"
        when(isMoveToSr) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = true, W1)
          when(ok) { setLen(1, e); setAmb(e.sel) }
        }
        val isMoveUsp = op(15 downto 4) === B"12'h4E6"
        when(isMoveUsp) { setLenN(1) }
        val isMovec = op(15 downto 1) === B"15'b010011100111101"
        when(isMovec) { setLenN(2) }
        val isRtd = op === B"16'h4E74"
        when(isRtd) { setLenN(2) }
        val isReset = op === B"16'h4E70"
        when(isReset) { setLenN(1) }
        val isStop = op === B"16'h4E72"
        when(isStop) { setLenN(2) }
        val isChk = op(8) && !op(6)
        when(isChk) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, !op(7), allowImm = true, W1)
          when(ok) { setLen(1, e); setAmb(e.sel) }
        }
        val isDivL = op(15 downto 6) === B"10'b0100110001"
        val isMulL = op(15 downto 6) === B"10'b0100110000"
        when(isDivL || isMulL) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = True, allowImm = true, W2)
          when(ok) { setLen(2, e); setAmb(e.sel) }
        }
        val u4o    = op(11 downto 8).asUInt
        val u4ss   = op(7 downto 6).asUInt
        val u4mode = op(5 downto 3).asUInt
        val isUnaryArith = !op(8) && (u4ss =/= U(3, 2 bits)) && (u4mode === U(0, 3 bits)) &&
                           (u4o === U(0, 4 bits) || u4o === U(2, 4 bits) || u4o === U(4, 4 bits) ||
                            u4o === U(6, 4 bits) || u4o === U(0xA, 4 bits))
        val isSwap  = op(15 downto 3) === B"13'b0100100001000"
        val isExtW  = op(15 downto 3) === B"13'b0100100010000"
        val isExtL  = op(15 downto 3) === B"13'b0100100011000"
        val isExtbL = op(15 downto 3) === B"13'b0100100111000"
        val isTas   = op(15 downto 3) === B"13'b0100101011000"
        val isNbcd  = op(15 downto 3) === B"13'b0100100000000"
        val isBkpt  = op(15 downto 3) === B"13'b0100100001001"
        when(isUnaryArith || isSwap || isExtW || isExtL || isExtbL || isTas || isNbcd || isBkpt) {
          setLenN(1)
        }
        val isUnaryMem = !op(8) && (u4ss =/= U(3, 2 bits)) && (u4mode =/= U(0, 3 bits)) &&
                         (u4o === U(0, 4 bits) || u4o === U(2, 4 bits) || u4o === U(4, 4 bits) ||
                          u4o === U(6, 4 bits) || u4o === U(0xA, 4 bits))
        when(isUnaryMem) {
          val (mok, me) = memDestExt(u4mode, op(2 downto 0).asUInt, W1)
          val unaryLenChain = when(mok) {
            setLen(1, me); setAmb(me.sel)
          } elsewhen(u4o === U(0xA, 4 bits) && u4mode === U(7, 3 bits) &&
                     ((op(2 downto 0) === B"3'b010") || (op(2 downto 0) === B"3'b011"))) {
            val (tok, te) = eaExt(u4mode, op(2 downto 0).asUInt, u4ss === U(2, 2 bits), allowImm = false, W1)
            when(tok) { setLen(1, te); setAmb(te.sel) }
          }
          if (m68k040.top.ShippingCoreConfig.upc10) {
            unaryLenChain.elsewhen(u4o === U(0xA, 4 bits) && (op(5 downto 0) === B"6'b111100")) {
              setLenK(Mux(u4ss === U(2, 2 bits), U(3, 4 bits), U(2, 4 bits)))
            }
          }
        }
        val isTstAn = !op(8) && (u4ss =/= U(3, 2 bits)) && (u4ss =/= U(0, 2 bits)) &&
                      (u4mode === U(1, 3 bits)) && (u4o === U(0xA, 4 bits))
        when(isTstAn) { setLenN(1) }
        val isTasMem = (op(15 downto 6) === B"10'b0100101011") && (u4mode =/= U(0, 3 bits))
        val isNbcdMem = (op(15 downto 6) === B"10'b0100100000") && (u4mode >= U(2, 3 bits))
        when(isTasMem || isNbcdMem) {
          val (mok, me) = memDestExt(u4mode, op(2 downto 0).asUInt, W1)
          when(mok) { setLen(1, me); setAmb(me.sel) }
        }
        val isMovem = op(11) && (op(9 downto 7) === B"001")
        when(isMovem) {
          val mmMode = op(5 downto 3).asUInt
          val mmReg  = op(2 downto 0).asUInt
          val mmDir  = op(10)
          val mmOk   = Bool(); val mmExt = UInt(3 bits)
          mmOk := False; mmExt := U(0, 3 bits)
          switch(mmMode) {
            is(U(2, 3 bits)) { mmOk := True; mmExt := U(0, 3 bits) }
            is(U(3, 3 bits)) { mmOk := mmDir;  mmExt := U(0, 3 bits) }
            is(U(4, 3 bits)) { mmOk := !mmDir; mmExt := U(0, 3 bits) }
            is(U(5, 3 bits)) { mmOk := True; mmExt := U(1, 3 bits) }
            is(U(7, 3 bits)) {
              switch(mmReg) {
                is(U(0, 3 bits)) { mmOk := True; mmExt := U(1, 3 bits) }
                is(U(1, 3 bits)) { mmOk := True; mmExt := U(2, 3 bits) }
                is(U(2, 3 bits)) { mmOk := mmDir; mmExt := U(1, 3 bits) }
                default          { mmOk := False }
              }
            }
            default { mmOk := False }
          }
          when(mmOk) { setLenK(U(2, 4 bits) + mmExt.resize(4)) }
        }
        val isJmp = op(15 downto 6) === B"10'b0100111011"
        val isJsr = op(15 downto 6) === B"10'b0100111010"
        when(isJmp || isJsr) {
          val srcMode = op(5 downto 3).asUInt
          val (ok, e) = eaExt(srcMode, op(2 downto 0).asUInt, sizeL = False, allowImm = false, W1)
          val ctrlMode = (srcMode === U(2, 3 bits)) || (srcMode === U(5, 3 bits)) ||
                         (srcMode === U(6, 3 bits)) || (srcMode === U(7, 3 bits))
          when(ok && ctrlMode) { setLen(1, e); setAmb(e.sel) }
        }
        val isLea = op(8) && (op(7 downto 6) === B"11") && (op(5 downto 3).asUInt >= 2)
        when(isLea) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = false, W1)
          when(ok) { setLen(1, e); setAmb(e.sel) }
        }
        val isPea = op(15 downto 6) === B"10'b0100100001"
        when(isPea) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = false, W1)
          when(ok) { setLen(1, e); setAmb(e.sel) }
        }
        val isMoveFromSr  = op(15 downto 6) === B"10'b0100000011"
        val isMoveFromCcr = op(15 downto 6) === B"10'b0100001011"
        when(isMoveFromSr || isMoveFromCcr) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = false, W1)
          when(ok) { setLen(1, e); setAmb(e.sel) }
        }
        val isMoveToCcr = op(15 downto 6) === B"10'b0100010011"
        when(isMoveToCcr) {
          val (ok, e) = eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = true, W1)
          when(ok) { setLen(1, e); setAmb(e.sel) }
        }
      }

      is(U(5, 4 bits)) {
        val ss   = op(7 downto 6).asUInt
        val mode = op(5 downto 3).asUInt
        when(ss =/= U(3, 2 bits)) {
          when(mode === U(0, 3 bits) || mode === U(1, 3 bits)) {
            setLenN(1)
          } otherwise {
            val (mok, me) = memDestExt(mode, op(2 downto 0).asUInt, W1)
            when(mok) { setLen(1, me); setAmb(me.sel) }
          }
        } otherwise {
          when(mode === U(1, 3 bits)) {
            setLenN(2)
          } elsewhen(mode === U(0, 3 bits)) {
            setLenN(1)
          } elsewhen(mode === U(7, 3 bits)) {
            val ttt = op(2 downto 0).asUInt
            when(ttt === U(4, 3 bits)) {
              setLenN(1)
            } elsewhen(ttt === U(2, 3 bits)) {
              setLenN(2)
            } elsewhen(ttt === U(3, 3 bits)) {
              setLenN(3)
            } otherwise {
              val (mok, me) = memDestExt(mode, op(2 downto 0).asUInt, W1)
              when(mok) { setLen(1, me); setAmb(me.sel) }
            }
          } otherwise {
            val (mok, me) = memDestExt(mode, op(2 downto 0).asUInt, W1)
            when(mok) { setLen(1, me); setAmb(me.sel) }
          }
        }
      }

      is(U(7, 4 bits)) {
        when(op(8) === False) { setLenN(1) }
      }

      is(U(6, 4 bits)) {
        val d8 = op(7 downto 0).asUInt
        when(d8 === U(0x00, 8 bits)) {
          setLenN(2)
        } elsewhen(d8 === U(0xff, 8 bits)) {
          setLenN(3)
        } otherwise {
          setLenN(1)
        }
      }

      is(U(8, 4 bits), U(9, 4 bits), U(0xC, 4 bits), U(0xD, 4 bits)) {
        val opmode  = op(8 downto 6).asUInt
        val srcMode = op(5 downto 3).asUInt
        val srcReg  = op(2 downto 0).asUInt
        val isDivuW = (cls === U(8, 4 bits)) && (opmode === U(3, 3 bits))
        val isDivsW = (cls === U(8, 4 bits)) && (opmode === U(7, 3 bits))
        val mulEaOk = srcMode =/= U(1, 3 bits)
        val isMuluW = (cls === U(0xC, 4 bits)) && (opmode === U(3, 3 bits)) && mulEaOk
        val isMulsW = (cls === U(0xC, 4 bits)) && (opmode === U(7, 3 bits)) && mulEaOk
        val isMulDiv = (cls === U(8, 4 bits) || cls === U(0xC, 4 bits)) &&
                       (opmode === U(3, 3 bits) || opmode === U(7, 3 bits))
        val isExg = (cls === U(0xC, 4 bits)) && op(8) &&
                    (op(7 downto 3) === B"5'b01000" || op(7 downto 3) === B"5'b01001" ||
                     op(7 downto 3) === B"5'b10001")
        when(isExg) {
          setLenN(1)
        } elsewhen(isDivuW || isDivsW || isMuluW || isMulsW) {
          val (ok, e) = eaExt(srcMode, srcReg, sizeL = False, allowImm = true, W1)
          when(ok) { setLen(1, e); setAmb(e.sel) }
        } elsewhen(opmode =/= U(4, 3 bits) && opmode =/= U(5, 3 bits) && opmode =/= U(6, 3 bits) && !isMulDiv) {
          val sizeL = (opmode === U(2, 3 bits)) || (opmode === U(7, 3 bits))
          val (ok, e) = eaExt(srcMode, srcReg, sizeL, allowImm = true, W1)
          when(ok) { setLen(1, e); setAmb(e.sel) }
        } elsewhen(opmode === U(4, 3 bits) || opmode === U(5, 3 bits) || opmode === U(6, 3 bits)) {
          val isAddxSubxReg = (cls === U(9, 4 bits) || cls === U(0xD, 4 bits)) &&
                              (srcMode === U(0, 3 bits) || srcMode === U(1, 3 bits))
          val isBcdReg = (cls === U(8, 4 bits) || cls === U(0xC, 4 bits)) &&
                         (opmode === U(4, 3 bits)) && (srcMode === U(0, 3 bits) || srcMode === U(1, 3 bits))
          val isPackUnpkFrame = (cls === U(8, 4 bits)) &&
                                (opmode === U(5, 3 bits) || opmode === U(6, 3 bits)) &&
                                (srcMode === U(0, 3 bits) || srcMode === U(1, 3 bits))
          when(isAddxSubxReg || isBcdReg) {
            setLenN(1)
          } elsewhen(isPackUnpkFrame) {
            setLenN(2)
          } otherwise {
            val (mok, me) = memDestExt(srcMode, srcReg, W1)
            when(mok) { setLen(1, me); setAmb(me.sel) }
          }
        }
      }

      is(U(0xB, 4 bits)) {
        val opmode  = op(8 downto 6).asUInt
        val srcMode = op(5 downto 3).asUInt
        val srcReg  = op(2 downto 0).asUInt
        val isCmp   = (opmode === U(0, 3 bits)) || (opmode === U(1, 3 bits)) ||
                      (opmode === U(2, 3 bits)) || (opmode === U(3, 3 bits)) ||
                      (opmode === U(7, 3 bits))
        val isEor   = (opmode === U(4, 3 bits)) || (opmode === U(5, 3 bits)) ||
                      (opmode === U(6, 3 bits))
        val isCmpm  = isEor && (srcMode === U(1, 3 bits))
        when(isCmp) {
          val sizeL = (opmode === U(2, 3 bits)) || (opmode === U(7, 3 bits))
          val (ok, e) = eaExt(srcMode, srcReg, sizeL, allowImm = true, W1)
          when(ok) { setLen(1, e); setAmb(e.sel) }
        } elsewhen(isEor && (srcMode === U(0, 3 bits))) {
          setLenN(1)
        } elsewhen(isCmpm) {
          setLenN(1)
        } elsewhen(isEor) {
          val (mok, me) = memDestExt(srcMode, srcReg, W1)
          when(mok) { setLen(1, me); setAmb(me.sel) }
        }
      }

      is(U(0xE, 4 bits)) {
        val ss = op(7 downto 6).asUInt
        when(ss =/= U(3, 2 bits)) { setLenN(1) }
        val isBitfieldReg = op(11) && (ss === U(3, 2 bits)) && (op(5 downto 3).asUInt === U(0, 3 bits))
        when(isBitfieldReg) { setLenN(2) }
        val bfMemMode = op(5 downto 3).asUInt
        val isBitfieldMem = op(11) && (ss === U(3, 2 bits)) && (bfMemMode >= 2)
        when(isBitfieldMem) {
          val (ok, e) = eaExt(bfMemMode, op(2 downto 0).asUInt, sizeL = False, allowImm = false, W2)
          when(ok) { setLen(2, e); setAmb(e.sel) }
        }
        val isShiftMem = !op(11) && (ss === U(3, 2 bits))
        when(isShiftMem) {
          val (mok, me) = memDestExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, W1)
          when(mok) { setLen(1, me); setAmb(me.sel) }
        }
      }

      is(U(0xA, 4 bits)) { setLenN(1) }
      is(U(0xF, 4 bits)) {
        val fpIsGen   = (op(11 downto 9) === B"3'b001") && (op(8 downto 6) === B"3'b000")
        val fpSaveRest = (op(11 downto 9) === B"3'b001") &&
                         ((op(8 downto 6) === B"3'b100") || (op(8 downto 6) === B"3'b101"))
        val (fsvEaOk, fsvE) =
          eaExt(op(5 downto 3).asUInt, op(2 downto 0).asUInt, sizeL = False, allowImm = false, W1)
        val fsccMode  = op(5 downto 3).asUInt
        val fsccReg   = op(2 downto 0).asUInt
        val fsccIsEa  = (fsccMode =/= 1) && !((fsccMode === 7) && (fsccReg >= 2))
        val fpScc     = (op(11 downto 9) === B"3'b001") && (op(8 downto 6) === B"3'b001") && fsccIsEa
        val fpType001 = (op(11 downto 9) === B"3'b001") && (op(8 downto 6) === B"3'b001")
        val fpDbcc    = fpType001 && (fsccMode === 1)
        val fpTrapcc  = fpType001 && (fsccMode === 7) &&
                        ((fsccReg === 2) || (fsccReg === 3) || (fsccReg === 4))
        val fpTrapccLen = Mux(fsccReg === 4, U(2, 4 bits),
                          Mux(fsccReg === 2, U(3, 4 bits), U(4, 4 bits)))
        val (fsccEaOk, fsccE) = eaExt(fsccMode, fsccReg, sizeL = False, allowImm = false, W2)
        val fpBcc     = (op(11 downto 9) === B"3'b001") && (op(8 downto 7) === B"2'b01")
        val fpClass   = extW(15 downto 13)
        val fpSrcSpec = extW(12 downto 10).asUInt
        val fpEaMode  = op(5 downto 3).asUInt
        val fpEaReg   = op(2 downto 0).asUInt
        val fpIsImmEa = (fpEaMode === U(7, 3 bits)) && (fpEaReg === U(4, 3 bits))
        val fpImmWords = UInt(3 bits)
        switch(fpSrcSpec) {
          is(U(0, 3 bits)) { fpImmWords := U(2, 3 bits) }
          is(U(1, 3 bits)) { fpImmWords := U(2, 3 bits) }
          is(U(2, 3 bits)) { fpImmWords := U(6, 3 bits) }
          is(U(3, 3 bits)) { fpImmWords := U(6, 3 bits) }
          is(U(4, 3 bits)) { fpImmWords := U(1, 3 bits) }
          is(U(5, 3 bits)) { fpImmWords := U(4, 3 bits) }
          is(U(6, 3 bits)) { fpImmWords := U(1, 3 bits) }
          default          { fpImmWords := U(0, 3 bits) }
        }
        val fpIsMovecr = (fpClass === B"3'b010") && (fpSrcSpec === U(7, 3 bits))
        val fpIsRegForm = (fpClass === B"3'b000")
        val (fpEaOk, fpE) = eaExt(fpEaMode, fpEaReg, sizeL = False, allowImm = false, W2)

        when(op === B"16'hF27F") {
          setLenN(4)
        } .elsewhen(op(15 downto 3) === U(0xF620 >> 3, 13 bits).asBits) {
          setLenN(2)
        } .elsewhen(fpIsGen && !extWKnown) {
          setLenN(2)
          ambC := True; ambSel := WN
        } .elsewhen(fpIsGen && (fpIsRegForm || fpIsMovecr)) {
          setLenN(2)
        } .elsewhen(fpIsGen && fpIsImmEa && (fpClass === B"3'b010")) {
          setLenK(U(2, 4 bits) + fpImmWords.resize(4))
        } .elsewhen(fpIsGen && fpIsImmEa && (fpClass === B"3'b100")) {
          setLenN(4)
        } .elsewhen(fpIsGen && fpEaOk) {
          setLen(2, fpE); setAmb(fpE.sel)
        } .elsewhen(fpDbcc) {
          setLenN(3)
        } .elsewhen(fpTrapcc) {
          setLenK(fpTrapccLen)
        } .elsewhen(fpScc && fsccEaOk) {
          setLen(2, fsccE); setAmb(fsccE.sel)
        } .elsewhen(fpBcc) {
          setLenK(Mux(op(6), U(3, 4 bits), U(2, 4 bits)))
        } .elsewhen(fpSaveRest && fsvEaOk) {
          setLen(1, fsvE); setAmb(fsvE.sel)
        } .otherwise {
          setLenN(1)
        }
      }

      default { }
    }

    // ---- the ONE length add / ambiguity reduction ----
    val ixPick = Vec((0 until 3).map(j => ixV(j) & U(3 bits, default -> lenSel(j)))).reduce(_ | _)
    r.lenWords      := Mux(useMove, mvTotal.resize(4), lenC + ixPick.resize(4))
    r.ambiguousLine := ambC || (ambSel & ~knV.asBits).orR
    r
  }
}
