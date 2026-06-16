package m68k040.execute

import spinal.core._

/** Bit-field datapath command (the REGISTER form, static offset/width — slice 1).
  * `dy` is the field register (op[2:0]); `dn2` is the insert source (BFINS) — for
  * the other ops it is don't-care. `offset` 0..31 (0 = the MSB, bit 31). `rawWidth`
  * is the 5-bit ext-word width field (0 -> 32; normalized here). `bfOp` = op[10:8]
  * (0=BFTST,1=BFCHG,2=BFCLR,3=BFSET,4=BFEXTU,5=BFEXTS,6=BFFFO,7=BFINS). */
case class BitfieldCmd() extends Bundle {
  val dy       = Bits(32 bits)
  val dn2      = Bits(32 bits)
  val offset   = UInt(5 bits)
  val rawWidth = UInt(5 bits)
  val bfOp     = Bits(3 bits)
}

/** Bit-field datapath result. `result` is the value written to the destination
  * (Dy or Dn2; ignored for BFTST). `n`/`z` are the CCR N/Z bits (V=C=0, X untouched
  * — the EU's writesX mask is False). */
case class BitfieldRsp() extends Bundle {
  val result = Bits(32 bits)
  val n      = Bool()
  val z      = Bool()
}

/** Combinational bit-field datapath, transcribed from Musashi's `_32_d` BFxxx forms.
  *
  * m68k bit numbering: offset 0 = bit 31 (the MSB); the field is `width` bits from
  * bit (31-offset) toward the LSB. `mask = ROR_32(0xffffffff << (32-width), offset)`.
  * All eight ops share the rotate (`rotL = ROL_32(Dy, offset)`); the result mux +
  * the CLZ priority-encode (BFFFO) + the arithmetic-vs-logical extract shift complete
  * the datapath. Reused on the ALU EU's slow/shifter (lat-matched) path. */
object Bitfield {
  // ROL_32(x, n) via a 64-bit funnel: (x##x) rotated. n in 0..31.
  private def rol32(x: Bits, n: UInt): Bits = {
    val wide = (x.asUInt.resize(64) << n)            // n in 0..31 -> <= 63
    (wide(31 downto 0) | wide(63 downto 32)).asBits
  }
  // ROR_32(x, n) = ROL_32(x, (32 - n) & 31). n in 0..31.
  private def ror32(x: Bits, n: UInt): Bits = {
    val back = ((U(32, 6 bits) - n.resize(6)) & U(31, 6 bits)).resize(5)
    rol32(x, back)
  }

  def apply(cmd: BitfieldCmd): BitfieldRsp = new Area {
    val rsp = BitfieldRsp()

    val offset = cmd.offset
    // width = ((rawWidth - 1) & 31) + 1  -> 1..32 (rawWidth 0 -> 32).
    val width6 = (((cmd.rawWidth - 1) & U(31, 5 bits)).resize(6) + 1)   // 1..32
    // 32 - width (0..31): the left-justify / extract shift amount.
    val shAmt  = (U(32, 6 bits) - width6).resize(5)                     // 0..31

    // mask = ROR_32(0xffffffff << (32 - width), offset). The pre-rotate mask is the
    // top `width` bits set: 0xffffffff << (32-width). shAmt in 0..31.
    val maskBase = (B(0xffffffffL, 32 bits).asUInt << shAmt)(31 downto 0).asBits
    val mask     = ror32(maskBase, offset)

    // rotL = ROL_32(Dy, offset).
    val rotL   = rol32(cmd.dy, offset)
    // (Dy << offset) — for the N flag of TST/CHG/CLR/SET (N = bit31 of Dy<<offset).
    val dyShL  = (cmd.dy.asUInt << offset)(31 downto 0).asBits

    // BFINS insert value = (Dn2 << (32-width)) & 0xffffffff (left-justified low width bits).
    val insVal = (cmd.dn2.asUInt << shAmt)(31 downto 0).asBits
    // insert positioned = ROR_32(insVal, offset).
    val insPos = ror32(insVal, offset)

    // Extract: logical (BFEXTU/BFFFO) vs arithmetic (BFEXTS) right shift of rotL by shAmt.
    val extU   = (rotL.asUInt >> shAmt).asBits
    val extS   = (rotL.asSInt >> shAmt).asBits
    // BFFFO: field = data >> (32-width) (= extU). Scan field MSB->LSB for the first set
    // bit within the `width` MSBs; Dn2 := offset + (#leading-zero bits before first set)
    // (= offset + width if the field is all zero). The field's `width` bits sit in
    // extU[width-1:0]; left-justify to extU<<shAmt == rotL & maskBase so the CLZ over
    // the top 32 bits, CLAMPED to width, gives the count. Use rotL's top `width` bits.
    // Equivalent: clz of (extU left-justified to bit (width-1)). We left-justify the
    // width-bit field to the MSB via (extU << (32-width)) and CLZ that, clamped to width.
    val fieldLJ = (extU.asUInt << shAmt)(31 downto 0).asBits   // width-bit field at the top
    val clzRaw  = clz32(fieldLJ)                               // 0..32 (32 if all-zero)
    val clzClamped = Mux(clzRaw > width6, width6, clzRaw)      // clamp to width
    val ffoRes  = (offset.resize(7) + clzClamped.resize(7)).resize(32).asBits

    // CHG/CLR/SET mask-modify.
    val chgRes = (cmd.dy.asUInt ^ mask.asUInt).asBits
    val clrRes = (cmd.dy.asUInt & ~mask.asUInt).asBits
    val setRes = (cmd.dy.asUInt | mask.asUInt).asBits
    // BFINS: (Dy & ~mask) | insPos.
    val insRes = ((cmd.dy.asUInt & ~mask.asUInt) | insPos.asUInt).asBits

    // ── N flag ──────────────────────────────────────────────────────────────────
    // TST/CHG/CLR/SET: N = bit31 of (Dy<<offset). EXTU/EXTS/FFO: N = rotL[31].
    // INS: N = bit31 of insVal (the inserted value, pre-position).
    val nShift = dyShL(31)
    val nRot   = rotL(31)
    val nIns   = insVal(31)
    // bfOp = op[10:8] (the 020 bit-field encoding): 0=BFTST,1=BFEXTU,2=BFCHG,
    // 3=BFEXTS,4=BFCLR,5=BFFFO,6=BFSET,7=BFINS. EXTU/EXTS/FFO (1/3/5) use rotL[31];
    // INS (7) uses the inserted-value MSB; TST/CHG/CLR/SET (0/2/4/6) use (Dy<<offset)[31].
    val n = cmd.bfOp.mux(
      B"3'd1" -> nRot, B"3'd3" -> nRot, B"3'd5" -> nRot,   // EXTU/EXTS/FFO
      B"3'd7" -> nIns,                                       // INS
      default -> nShift)                                    // TST/CHG/CLR/SET

    // ── Z flag (value == 0) ───────────────────────────────────────────────────────
    // TST/CHG/CLR/SET: Z = (Dy & mask) == 0. EXTU: extU==0. EXTS: extS==0. FFO: field
    // (=extU) ==0. INS: insVal==0.
    val zTstFamily = (cmd.dy.asUInt & mask.asUInt) === 0
    val zExtU      = extU.asUInt === 0
    val zExtS      = extS.asUInt === 0
    val zFfo       = extU.asUInt === 0
    val zIns       = insVal.asUInt === 0
    val z = cmd.bfOp.mux(
      B"3'd1" -> zExtU, B"3'd3" -> zExtS, B"3'd5" -> zFfo,
      B"3'd7" -> zIns,
      default -> zTstFamily)

    // ── result mux ────────────────────────────────────────────────────────────────
    val result = cmd.bfOp.mux(
      B"3'd0" -> cmd.dy,          // BFTST: no write (don't-care; dstValid=False)
      B"3'd1" -> extU,            // BFEXTU
      B"3'd2" -> chgRes,          // BFCHG
      B"3'd3" -> extS,            // BFEXTS
      B"3'd4" -> clrRes,          // BFCLR
      B"3'd5" -> ffoRes,          // BFFFO
      B"3'd6" -> setRes,          // BFSET
      default -> insRes)          // 7 = BFINS

    rsp.result := result
    rsp.n := n
    rsp.z := z
  }.rsp

  /** Count leading zeros of a 32-bit value: 0..32 (32 if all zero). Small balanced
    * tree (priority encode). */
  private def clz32(x: Bits): UInt = {
    val cnt = UInt(6 bits)
    when(x === 0) { cnt := 32 } otherwise {
      // OHToUInt of the MSB-most set bit -> 31 - position; clz = 31 - msbIndex.
      val msbIdx = UInt(5 bits)
      msbIdx := 0
      for (i <- 0 until 32) {
        when(x(i)) { msbIdx := U(i, 5 bits) }   // last (highest) set wins
      }
      cnt := (U(31, 6 bits) - msbIdx.resize(6))
    }
    cnt
  }
}
