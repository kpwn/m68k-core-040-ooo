package m68k040.execute.fpu

import spinal.core._

/** Source-operand gateway helpers for the CPLX cluster's FP writeback lane.
  *
  * Everything here is COMBINATIONAL and EXACT. Every conversion below widens a narrower
  * format into the internal 80-bit extended layout, and extended has both a wider exponent
  * range and a wider significand than any of the sources, so no rounding is possible and no
  * INEX2 can be raised. That is why none of these reuse `FpRoundPack`: routing an exact
  * value through a 3-stage rounding pipe would add latency and an arbitration source for a
  * result that is already final. (`FpuCore` itself still rounds -- these only build its
  * `io.src` input.)
  *
  * The transcriptions are from the vendored SoftFloat this project's oracle links
  * (`tools/musashi/musashi/softfloat/softfloat.c`), which is what Musashi's `m68kfpu.c`
  * calls for every non-extended `<ea>` source (`m68kfpu.c:1196-1235`).
  *
  * ── ONE DELIBERATE, DOCUMENTED DEPARTURE FROM LITERAL SoftFloat ──
  * `float32_to_floatx80`/`float64_to_floatx80` route a NaN source through
  * `commonNaNToFloatx80`, which force-sets bit 62 (`0xC000000000000000`), i.e. QUIETS a
  * signalling NaN and raises `float_flag_invalid` inside the conversion. These helpers
  * instead set only the explicit integer bit (bit 63) and leave the payload's own MSB
  * alone, so a signalling source stays signalling into `FpuCore`.
  *
  * This is observationally equivalent AND strictly better on the exception flag:
  *   - For a QUIET NaN source the payload MSB is already 1, so bit 62 is already set and the
  *     two constructions produce a bit-identical 80-bit word.
  *   - For a SIGNALLING NaN source, `FpuCore`'s front-ends detect it (`Fp80.isSNan`), raise
  *     `FpExcFlags.snan` -- which is the architectural counterpart of SoftFloat's
  *     `float_flag_invalid` and which a pre-quieted operand would have silently lost -- and
  *     then quiet it through `Fp80.propagateNan`, whose `|0xC000000000000000` produces
  *     exactly the word SoftFloat's conversion would have produced. `propagateNan`'s
  *     operand-selection is unchanged either way, because it branches on `isNan`, which is
  *     true for both the signalling and the quieted form.
  */
object FpSource {

  /** True for the two raw ISA opmodes (extension word [6:0]) that `FpuCore` executes on its
    * single-context iterative lane: 0x20 FDIV, 0x04 FSQRT. Dispatches on the RAW OPMODE, not
    * on `FpOp`, so the EU can gate `issuePort.ready` before it has computed the
    * opmode -> `FpOp` mapping. */
  def isIterativeOpmode(opmode: Bits): Bool =
    (opmode === B"7'h20") || (opmode === B"7'h04")

  /** `RenamedUop.op` is always `DecOp.FPU` for the whole F-line family (one DecOp), so the
    * real per-operation selector is `RenamedUop.fpuOp`, the raw 7-bit extension-word opmode.
    * This maps it onto `FpOp`.
    *
    * FMOVECR has no opmode of its own -- its command word's [6:0] carries the constant ROM
    * OFFSET, not an operation code -- so it is identified by `fpSrcKind === ROMCONST` and
    * overrides the table. Opmode 0x00 is FMOVE, which is also the safe default: decode's
    * `fpNative` whitelist already rejected every opmode not listed here to the FPSP
    * (vector-11) path, so no other value can reach this mapping. */
  def opmodeToFpOp(opmode: Bits, romConst: Bool): FpOp.C = {
    val r = FpOp()
    r := FpOp.FMOVE                              // 0x00 FMOVE, and the unreachable default
    switch(opmode) {
      is(B"7'h01") { r := FpOp.FINT }
      is(B"7'h03") { r := FpOp.FINTRZ }
      is(B"7'h04") { r := FpOp.FSQRT }
      is(B"7'h18") { r := FpOp.FABS }
      is(B"7'h1A") { r := FpOp.FNEG }
      is(B"7'h20") { r := FpOp.FDIV }
      is(B"7'h22") { r := FpOp.FADD }
      is(B"7'h23") { r := FpOp.FMUL }
      is(B"7'h28") { r := FpOp.FSUB }
      is(B"7'h38") { r := FpOp.FCMP }
      is(B"7'h3A") { r := FpOp.FTST }
    }
    when(romConst) { r := FpOp.FMOVECR }
    r
  }

  /** SoftFloat `int32_to_floatx80` (softfloat.c:846-860) verbatim:
    *   a == 0                -> +0 (sign forced 0, not the source's)
    *   shiftCount = clz32(|a|) + 32 ; exp = 0x403E - shiftCount ; sig = |a| << shiftCount
    * i.e. exp = 0x401E - clz32(|a|). Exact: 32 integer bits fit in a 64-bit significand.
    *
    * The m68k `<ea>` integer source is SIGNED for every width -- Musashi reads it as
    * `sint8`/`sint16`/`sint32` and calls this on the sign-extended value
    * (`m68kfpu.c:1196,1221-1222,1234-1235`) -- so `v` must already be sign-extended to 32
    * bits by the caller. */
  def intToExtended(v: Bits): Bits = {
    require(v.getWidth == 32, "intToExtended takes a 32-bit sign-extended integer")
    val a      = v.asSInt
    val sign   = a < 0
    val mag    = Mux(sign, (-a).asBits, v)        // |a|; |INT_MIN| = 0x80000000 fits unsigned
    val c      = Fp80.clz(mag)                    // 0..32 (32 only when v == 0)
    val sig    = (B(0, 32 bits) ## mag).asUInt |<< (c.resize(7) + 32)
    val exp    = U(0x401E, 15 bits) - c.resize(15)
    Mux(v === 0, Fp80.packZero(False), sign ## exp ## sig.asBits)
  }

  /** SoftFloat `float32_to_floatx80` (softfloat.c:1196-1216), plus the SNaN-preservation
    * departure documented on this object. `v` is a 32-bit IEEE-754 single BIT PATTERN. */
  def singleToExtended(v: Bits): Bits = {
    require(v.getWidth == 32, "singleToExtended takes a 32-bit IEEE-754 single bit pattern")
    val sign = v(31)
    val aExp = v(30 downto 23).asUInt            // 8 bits
    val aSig = v(22 downto 0)                    // 23-bit fraction
    val isMax = aExp === 0xFF
    val isSub = (aExp === 0) && (aSig =/= 0)
    val isZro = (aExp === 0) && (aSig === 0)
    // normalizeFloat32Subnormal: shiftCount = clz32(frac) - 8 = clz23(frac) + 1, so the
    // final significand is frac << (clz23 + 41) and the biased exponent is 0x3F80 - clz23.
    val c    = Fp80.clz(aSig)                    // 0..23 (23 only when aSig == 0)
    val sigSub = (B(0, 41 bits) ## aSig).asUInt |<< (c.resize(7) + 41)
    // The normal arm ((frac | hidden) << 40) and the Inf/NaN arm (0x8000.. | frac << 40)
    // are the SAME 64-bit expression, because the hidden bit and the explicit integer bit
    // land in the same place: 1 ## frac23 ## 40 zeros.
    val sigNorm = B"1" ## aSig ## B(0, 40 bits)
    val exp = UInt(15 bits)
    when(isMax)      { exp := U(Fp80.ExpInf, 15 bits) }
      .elsewhen(isSub) { exp := U(0x3F80, 15 bits) - c.resize(15) }
      .otherwise       { exp := aExp.resize(15) + U(0x3F80, 15 bits) }
    Mux(isZro, Fp80.packZero(sign),
      sign ## exp ## Mux(isSub, sigSub.asBits, sigNorm))
  }

  /** SoftFloat `float64_to_floatx80` (softfloat.c:2122-2143), plus the SNaN-preservation
    * departure documented on this object. `v` is a 64-bit IEEE-754 double BIT PATTERN.
    * This is the shape Musashi's own `double_to_fx80` helper (`m68kfpu.c:55-62`) wraps. */
  def doubleToExtended(v: Bits): Bits = {
    require(v.getWidth == 64, "doubleToExtended takes a 64-bit IEEE-754 double bit pattern")
    val sign = v(63)
    val aExp = v(62 downto 52).asUInt            // 11 bits
    val aSig = v(51 downto 0)                    // 52-bit fraction
    val isMax = aExp === 0x7FF
    val isSub = (aExp === 0) && (aSig =/= 0)
    val isZro = (aExp === 0) && (aSig === 0)
    // normalizeFloat64Subnormal: shiftCount = clz64(frac) - 11 = clz52(frac) + 1, so the
    // final significand is frac << (clz52 + 12) and the biased exponent is 0x3C00 - clz52.
    val c    = Fp80.clz(aSig)                    // 0..52 (52 only when aSig == 0)
    val sigSub = (B(0, 12 bits) ## aSig).asUInt |<< (c.resize(7) + 12)
    val sigNorm = B"1" ## aSig ## B(0, 11 bits)
    val exp = UInt(15 bits)
    when(isMax)      { exp := U(Fp80.ExpInf, 15 bits) }
      .elsewhen(isSub) { exp := U(0x3C00, 15 bits) - c.resize(15) }
      .otherwise       { exp := aExp.resize(15) + U(0x3C00, 15 bits) }
    Mux(isZro, Fp80.packZero(sign),
      sign ## exp ## Mux(isSub, sigSub.asBits, sigNorm))
  }
}
