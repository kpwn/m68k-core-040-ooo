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
    * opmode -> `FpOp` mapping.
    *
    * `romConst` (fpSrcKind === ROMCONST, i.e. FMOVECR) is a MANDATORY veto, exactly as in
    * `opmodeToFpOp` below and for exactly the same reason: for FMOVECR the 7-bit field is
    * NOT an opmode at all, it is the constant-ROM OFFSET, driven verbatim from the command
    * word by `MicroOpAssembler` -- and `fpEmit` admits every FMOVECR offset (no `fpNative`
    * gate applies to that form). Offsets $04 and $20 are perfectly encodable constants, so
    * without this veto `FMOVECR #$04`/`#$20` would be steered onto the iterative lane while
    * `FpuCore` -- which sees `FpOp.FMOVECR`, the `romConst` override having already won --
    * executes them on the cheap fixed pipe. The result: `fpIterBusy` set with no descriptor
    * and no `doneIter` that can ever arrive, i.e. a permanently wedged iterative lane AND a
    * ROB head that never completes. Keeping the two functions symmetric on `romConst` is
    * what makes that unrepresentable. */
  def isIterativeOpmode(opmode: Bits, romConst: Bool): Bool =
    !romConst && iterOpmodes.map(o => opmode === B(o, 7 bits)).reduce(_ || _)

  // ══════════════════════════════════════════════════════════════════════════════════
  // THE cpGEN OPMODE TABLE, in ONE place.
  //
  // The MC68040 added 16 "forced rounding precision" encodings alongside the base ones
  // (M68000PRM per-instruction Instruction Format tables; MC68040UM 10.7: "Instructions
  // with an S or D (e.g., FSADD) have the same effect as setting the rounding precision
  // to S or D"). They are GENUINE 68040 HARDWARE instructions -- absent from UM Table
  // 9-10's unimplemented list, present in every FMOVE/FADD/... "Opmode field" table with
  // the note "Supported by MC68040 only".
  //
  // Note the two that do NOT follow the "base opmode | $40, plus $04 for double" pattern:
  // FSSQRT/FDSQRT are $41/$45, not $44/$48. The table is therefore written out literally
  // rather than derived by masking -- deriving it would silently alias FDMOVE ($44) onto
  // FSQRT ($04).
  //
  //   base                       single      double
  //   $00 FMOVE                  $40 FSMOVE  $44 FDMOVE
  //   $04 FSQRT                  $41 FSSQRT  $45 FDSQRT
  //   $18 FABS                   $58 FSABS   $5C FDABS
  //   $1A FNEG                   $5A FSNEG   $5E FDNEG
  //   $20 FDIV                   $60 FSDIV   $64 FDDIV
  //   $22 FADD                   $62 FSADD   $66 FDADD
  //   $23 FMUL                   $63 FSMUL   $67 FDMUL
  //   $28 FSUB                   $68 FSSUB   $6C FDSUB
  //   $01 FINT  $03 FINTRZ  $38 FCMP  $3A FTST   (no forced-precision form exists)
  //
  // ══════════════════════════════════════════════════════════════════════════════════

  /** (base opmode, single-precision opmode, double-precision opmode) for every operation
    * that has a forced-precision form. */
  val precisionFamily: Seq[(Int, Int, Int)] = Seq(
    (0x00, 0x40, 0x44),   // FMOVE  / FSMOVE  / FDMOVE
    (0x04, 0x41, 0x45),   // FSQRT  / FSSQRT  / FDSQRT
    (0x18, 0x58, 0x5C),   // FABS   / FSABS   / FDABS
    (0x1A, 0x5A, 0x5E),   // FNEG   / FSNEG   / FDNEG
    (0x20, 0x60, 0x64),   // FDIV   / FSDIV   / FDDIV
    (0x22, 0x62, 0x66),   // FADD   / FSADD   / FDADD
    (0x23, 0x63, 0x67),   // FMUL   / FSMUL   / FDMUL
    (0x28, 0x68, 0x6C))   // FSUB   / FSSUB   / FDSUB

  /** Opmodes with no forced-precision variant: FINT, FINTRZ, FCMP, FTST. */
  val precisionlessOpmodes: Seq[Int] = Seq(0x01, 0x03, 0x38, 0x3A)

  /** Every opmode this core executes in hardware. Anything else -- transcendentals,
    * FMOD/FREM/FSCALE/FGETEXP, FSINCOS -- routes to FPSP via vector 11. */
  val nativeOpmodes: Seq[Int] =
    (precisionFamily.flatMap(t => Seq(t._1, t._2, t._3)) ++ precisionlessOpmodes).sorted

  /** The ops FpuCore runs on its single-context ITERATIVE lane: FDIV and FSQRT, in all
    * three precision spellings each. */
  val iterOpmodes: Seq[Int] = Seq(0x20, 0x60, 0x64, 0x04, 0x41, 0x45)

  /** DYADIC ops compute `FPn <op> source`, so they READ the destination FPn as an operand:
    * FDIV/FADD/FMUL/FSUB/FCMP and their forced-precision forms. The monadic ops
    * (FMOVE/FABS/FNEG/FSQRT/FINT/FINTRZ/FTST) do not -- their result is a function of the
    * source alone, and claiming a false RAW dependency on FPn would needlessly serialize
    * independent FP work in the IQ. */
  val dyadicOpmodes: Seq[Int] =
    (Seq(0x20, 0x22, 0x23, 0x28).flatMap { b =>
       val t = precisionFamily.find(_._1 == b).get; Seq(t._1, t._2, t._3)
     } :+ 0x38).sorted

  /** FCMP ($38) and FTST ($3A) write ONLY the condition codes -- no FP destination. Neither
    * has a forced-precision form. */
  val noFpDstOpmodes: Seq[Int] = Seq(0x38, 0x3A)

  private def anyOf(opmode: Bits, set: Seq[Int]): Bool =
    set.map(o => opmode === B(o, 7 bits)).reduce(_ || _)

  /** The hardware-native opmode whitelist. `MicroOpAssembler.fpNative`, `DecodeStage`'s
    * `ucFpNative` and this core's own dispatch all read THIS, so the three cannot drift. */
  def isNativeOpmode(opmode: Bits): Bool  = anyOf(opmode, nativeOpmodes)
  def isDyadicOpmode(opmode: Bits): Bool  = anyOf(opmode, dyadicOpmodes)
  def isNoFpDstOpmode(opmode: Bits): Bool = anyOf(opmode, noFpDstOpmodes)

  /** The EFFECTIVE rounding precision for an operation: the one the INSTRUCTION forces if
    * it has a forced-precision opmode, else FPCR.PREC as it stands right now.
    *
    * MC68040UM 10.7: "Instructions with an S or D (e.g., FSADD) have the same effect as
    * setting the rounding precision to S or D." M68000PRM FMOVE: "FSMOVE and FDMOVE will
    * round the result to single or double precision, respectively, REGARDLESS of the
    * rounding precision selected in the floating-point control register."
    *
    * `romConst` is the same MANDATORY veto `opmodeToFpOp`/`isIterativeOpmode` carry, for
    * the same reason: for FMOVECR the 7-bit field is the constant-ROM OFFSET, not an
    * opmode, and offsets $40..$6C are perfectly encodable. Without the veto
    * `FMOVECR #$62,FPn` would silently force single-precision rounding. */
  def opmodeToPrecision(opmode: Bits, romConst: Bool, fpcrPrec: Bits): Bits = {
    val r = Bits(2 bits)
    r := fpcrPrec
    when(!romConst) {
      when(anyOf(opmode, precisionFamily.map(_._2))) { r := B(FpPrec.Sgl, 2 bits) }
      when(anyOf(opmode, precisionFamily.map(_._3))) { r := B(FpPrec.Dbl, 2 bits) }
    }
    r
  }

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
    val base: Seq[(Int, FpOp.E)] = Seq(
      0x01 -> FpOp.FINT, 0x03 -> FpOp.FINTRZ, 0x04 -> FpOp.FSQRT,
      0x18 -> FpOp.FABS, 0x1A -> FpOp.FNEG,   0x20 -> FpOp.FDIV,
      0x22 -> FpOp.FADD, 0x23 -> FpOp.FMUL,   0x28 -> FpOp.FSUB,
      0x38 -> FpOp.FCMP, 0x3A -> FpOp.FTST)
    val r = FpOp()
    r := FpOp.FMOVE                              // 0x00 FMOVE, and the unreachable default
    switch(opmode) {
      for ((o, e) <- base) is(B(o, 7 bits)) { r := e }
      // The forced-precision forms execute the SAME operation; only `prec` differs, and
      // that travels separately (opmodeToPrecision). $40/$44 (FSMOVE/FDMOVE) fall through
      // to the FMOVE default, exactly like $00.
      for ((b, sgl, dbl) <- precisionFamily if b != 0x00) {
        val e = base.toMap.apply(b)
        is(B(sgl, 7 bits)) { r := e }
        is(B(dbl, 7 bits)) { r := e }
      }
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
