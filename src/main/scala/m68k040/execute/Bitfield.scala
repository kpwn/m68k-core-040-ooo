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
  // BFFFO additive base: Dn2 := ffoBase + (#leading zeros). The REGISTER form sets
  // ffoBase = offset (the field's bit offset within Dy). The MEMORY form left-justifies
  // the field into `dy` (rotate offset = 0) but the FFO base must be the ORIGINAL memory
  // bit offset (0..31), NOT 0 — so it is carried separately. Default callers set it to
  // `offset` to preserve register-form behavior. 32 bits: register form / static-mem use
  // offset (0..31); the DYNAMIC memory BFFFO needs the FULL signed 32-bit offset (Musashi
  // result = original_offset + first-set-index), fed from Dn[off].
  val ffoBase  = UInt(32 bits)
}

/** Bit-field datapath result. `result` is the value written to the destination
  * (Dy or Dn2; ignored for BFTST). `n`/`z` are the CCR N/Z bits (V=C=0, X untouched
  * — the EU's writesX mask is False). */
case class BitfieldRsp() extends Bundle {
  val result = Bits(32 bits)
  val n      = Bool()
  val z      = Bool()
}

/** FMax split point (task #123, Fable audit 2026-07-12 finding F1): the intermediate
  * result between `Bitfield.stage1` (the 4 parallel rotates + left-justify, cheap) and
  * `Bitfield.stage2` (the 32-input clz priority encoder + the 8-way result mux, the
  * deep half). Meant to be REGISTERED between the two stages by the caller — mirrors
  * the existing `Shifter.stage1a`/`stage1b` split. Carries the rotate/shift outputs
  * PLUS the small set of cmd fields stage2 still needs (dy/bfOp/ffoBase), so the
  * caller does not also have to thread the original BitfieldCmd across the cut. */
case class BitfieldMid() extends Bundle {
  val dy       = Bits(32 bits)   // pass-through: mask-modify (chg/clr/set/ins) + BFTST result
  val bfOp     = Bits(3 bits)    // pass-through: the final result/flag mux selector
  val ffoBase  = UInt(32 bits)   // pass-through: BFFFO's additive base
  val mask     = Bits(32 bits)
  val rotL     = Bits(32 bits)
  val dyShL    = Bits(32 bits)
  val insVal   = Bits(32 bits)
  val insPos   = Bits(32 bits)
  val extU     = Bits(32 bits)
  val extS     = Bits(32 bits)
  val fieldLJ  = Bits(32 bits)
  val width6   = UInt(6 bits)    // 1..32; needed to clamp the stage2 clz count
}

/** Combinational bit-field datapath, transcribed from Musashi's `_32_d` BFxxx forms.
  *
  * m68k bit numbering: offset 0 = bit 31 (the MSB); the field is `width` bits from
  * bit (31-offset) toward the LSB. `mask = ROR_32(0xffffffff << (32-width), offset)`.
  * All eight ops share the rotate (`rotL = ROL_32(Dy, offset)`); the result mux +
  * the CLZ priority-encode (BFFFO) + the arithmetic-vs-logical extract shift complete
  * the datapath.
  *
  * SOLE CALLER: `DivEuPlugin`'s bit-field lane (the CPLX cluster). It used to live on
  * the ALU EU's slow/shifter path, which meant TWO copies of this funnel + mask-gen +
  * BFFFO priority encoder, since `AluEuPlugin` is instantiated twice (eu0/eu1) — an
  * expensive duplicate for a rare 68020+ instruction family. CPLX is instantiated once.
  *
  * Split into `stage1`/`stage2` (task #123): the ORIGINAL single-cycle `apply` chained
  * 4 parallel barrel rotates into an 8-way result mux whose BFFFO leg carries a
  * 32-input clz — 20 LUT levels in one cone (the post-ISA-completion #1 critical
  * path). `stage1` computes the rotates/shifts (cheap); the caller registers the
  * `BitfieldMid` result; `stage2` computes the clz + mask-modify + final mux off the
  * registered midpoint. `apply` is kept as a thin single-cycle composition (stage1
  * then stage2, no register) for any caller that doesn't need the split. */
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

  /** Stage 1 (cheap half): the rotate/shift/left-justify cone. No clz, no result mux. */
  def stage1(cmd: BitfieldCmd): BitfieldMid = new Area {
    val mid = BitfieldMid()

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
    // (= offset + width if the field is all zero). The field's `width` bits, left-justified
    // to the MSB, are what stage2's CLZ (clamped to width) scans. Left-justifying is
    // `extU << shAmt`, which is provably == `rotL & maskBase`: extU = rotL >> shAmt
    // (logical), and (x >> s) << s == x & (0xffffffff << s) for any 32-bit x and s in
    // 0..31 (the shift-right/shift-left round-trip zeroes exactly the low s bits — exactly
    // what masking with maskBase = 0xffffffff<<shAmt does). Verified exhaustively over
    // offset in 0..31 x width in 1..32 x random Dy (task #253) before making this swap;
    // reuses the already-computed rotL/maskBase instead of a second 32-bit shift of extU.
    val fieldLJ = (rotL.asUInt & maskBase.asUInt).asBits          // width-bit field at the top

    mid.dy      := cmd.dy
    mid.bfOp    := cmd.bfOp
    mid.ffoBase := cmd.ffoBase
    mid.mask    := mask
    mid.rotL    := rotL
    mid.dyShL   := dyShL
    mid.insVal  := insVal
    mid.insPos  := insPos
    mid.extU    := extU
    mid.extS    := extS
    mid.fieldLJ := fieldLJ
    mid.width6  := width6
  }.mid

  /** Stage 2 (deep half): the clz + mask-modify + 8-way result/flag mux, off an
    * ALREADY-REGISTERED `BitfieldMid`. */
  def stage2(mid: BitfieldMid): BitfieldRsp = new Area {
    val rsp = BitfieldRsp()

    val clzRaw  = clz32(mid.fieldLJ)                            // 0..32 (32 if all-zero)
    val clzClamped = Mux(clzRaw > mid.width6, mid.width6, clzRaw)  // clamp to width
    // FFO result = ffoBase + clz. ffoBase = offset for the register form (set by the
    // caller); the memory form left-justifies the field (rotate offset = 0) and sets
    // ffoBase = the ORIGINAL memory bit offset so the result is original_offset + clz.
    val ffoRes  = (mid.ffoBase + clzClamped.resize(32)).asBits

    // CHG/CLR/SET mask-modify.
    val chgRes = (mid.dy.asUInt ^ mid.mask.asUInt).asBits
    val clrRes = (mid.dy.asUInt & ~mid.mask.asUInt).asBits
    val setRes = (mid.dy.asUInt | mid.mask.asUInt).asBits
    // BFINS: (Dy & ~mask) | insPos.
    val insRes = ((mid.dy.asUInt & ~mid.mask.asUInt) | mid.insPos.asUInt).asBits

    // ── N flag ──────────────────────────────────────────────────────────────────
    // TST/CHG/CLR/SET: N = bit31 of (Dy<<offset). EXTU/EXTS/FFO: N = rotL[31].
    // INS: N = bit31 of insVal (the inserted value, pre-position).
    val nShift = mid.dyShL(31)
    val nRot   = mid.rotL(31)
    val nIns   = mid.insVal(31)
    // bfOp = op[10:8] (the 020 bit-field encoding): 0=BFTST,1=BFEXTU,2=BFCHG,
    // 3=BFEXTS,4=BFCLR,5=BFFFO,6=BFSET,7=BFINS. EXTU/EXTS/FFO (1/3/5) use rotL[31];
    // INS (7) uses the inserted-value MSB; TST/CHG/CLR/SET (0/2/4/6) use (Dy<<offset)[31].
    val n = mid.bfOp.mux(
      B"3'd1" -> nRot, B"3'd3" -> nRot, B"3'd5" -> nRot,   // EXTU/EXTS/FFO
      B"3'd7" -> nIns,                                       // INS
      default -> nShift)                                    // TST/CHG/CLR/SET

    // ── Z flag (value == 0) ───────────────────────────────────────────────────────
    // TST/CHG/CLR/SET: Z = (Dy & mask) == 0. EXTU: extU==0. EXTS: extS==0. FFO: field
    // (=extU) ==0. INS: insVal==0.
    val zTstFamily = (mid.dy.asUInt & mid.mask.asUInt) === 0
    val zExtU      = mid.extU.asUInt === 0
    val zExtS      = mid.extS.asUInt === 0
    val zFfo       = mid.extU.asUInt === 0
    val zIns       = mid.insVal.asUInt === 0
    val z = mid.bfOp.mux(
      B"3'd1" -> zExtU, B"3'd3" -> zExtS, B"3'd5" -> zFfo,
      B"3'd7" -> zIns,
      default -> zTstFamily)

    // ── result mux ────────────────────────────────────────────────────────────────
    val result = mid.bfOp.mux(
      B"3'd0" -> mid.dy,          // BFTST: no write (don't-care; dstValid=False)
      B"3'd1" -> mid.extU,        // BFEXTU
      B"3'd2" -> chgRes,          // BFCHG
      B"3'd3" -> mid.extS,        // BFEXTS
      B"3'd4" -> clrRes,          // BFCLR
      B"3'd5" -> ffoRes,          // BFFFO
      B"3'd6" -> setRes,          // BFSET
      default -> insRes)          // 7 = BFINS

    rsp.result := result
    rsp.n := n
    rsp.z := z
  }.rsp

  /** Single-cycle composition (stage1 then stage2, no register in between) — kept for
    * any caller that doesn't need the FMax split. */
  def apply(cmd: BitfieldCmd): BitfieldRsp = stage2(stage1(cmd))

  /** Count leading zeros of a 32-bit value: 0..32 (32 if all zero). Linear priority
    * encode (Vivado retimes); BFFFO-only path, off the existing critical cone. If a
    * post-route gate ever shows clz32 limiting, replace with an explicit 5-level tree. */
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
