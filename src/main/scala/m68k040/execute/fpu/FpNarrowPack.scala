package m68k040.execute.fpu

import spinal.core._

/** Task 14b — the NARROWING converter behind `FMOVE FPn,<ea>` (cpGEN opclass 011).
  *
  * ONE registered stage (`FpNarrowPack.Latency == 1`), one instance, in the CPLX EU's
  * INTEGER lane (design decision D1):
  * `FpuCore`'s interface stays 80-bit-only and this is a separate focused module rather
  * than a new `FpuCore` output port, because a width-reduction shift-jam-round is a shallow
  * cone that fits the int lane's existing single-cycle `s1` shape and needs none of the FP
  * lane's arithmetic pipeline.
  *
  * It is a direct hardware transcription of the three SoftFloat routines Musashi's own
  * `fmove_reg_mem` (m68kfpu.c:1570-1632) calls, which makes it bit-exact against the
  * lock-step oracle by construction:
  *
  *   fmt 000 Long / 100 Word / 110 Byte : `floatx80_to_int32` + `roundAndPackInt32`
  *                                        (softfloat.c:67-101). Word/Byte are Musashi's
  *                                        `(sint16)`/`(sint8)` TRUNCATIONS of that same
  *                                        int32 -- the store row's own access size does
  *                                        the truncation, so this module emits the full
  *                                        32-bit value for all three.
  *   fmt 001 Single                     : `floatx80_to_float32` + `roundAndPackFloat32`
  *                                        (softfloat.c:245-294)
  *   fmt 101 Double                     : `floatx80_to_float64` + `roundAndPackFloat64`
  *                                        (softfloat.c:403-452)
  *   fmt 010 Extended                   : `store_extended_float80` (m68kfpu.c:80-85) --
  *                                        pure bit placement, no rounding, no exception.
  *   fmt 011/111 Packed                 : OUT OF SCOPE (design Decision 2); `DecodeStage`
  *                                        rejects both to `FP_MEM_TRAP_ENTRY` before any
  *                                        row of this datapath is ever built, so the
  *                                        `default` arm below is unreachable.
  *
  * MULTI-CHUNK (D2). Double is 2 words and Extended 3; the microcode re-runs the SAME
  * conversion once per store row and taps a different 32-bit slice each time (`io.chunk`).
  * The conversion is a pure function of the source register, so re-running it is exact.
  * The EXCEPTION byte is emitted by chunk 0 ONLY, so a 2/3-row program raises each
  * condition exactly once.
  *
  * EXCEPTIONS. `float_raise(float_flag_invalid)` on the integer path is the 68k OPERR (the
  * conversion has no representable result); the Word/Byte forms additionally raise OPERR
  * when the int32 result does not fit the narrower destination -- architecturally correct
  * per the MC68040 UM and NOT a Musashi behaviour (Musashi's `fmove_reg_mem` raises nothing
  * at all: it never calls `float_raise` or `SET_CONDITION_CODES`, verified by direct
  * search). Musashi is the oracle for the stored VALUE only, per the task brief's D7. */
object FpNarrowPack {
  /** Registered stages between the inputs and `io.word`/`io.exc`. */
  val Latency = 1
}

class FpNarrowPack extends Component {
  val io = new Bundle {
    /** The 80-bit source register (FPn). */
    val src   = in Bits (80 bits)
    /** ext[12:10] -- the DESTINATION format (the role-flip of the load direction's source
      * format field: same bit positions, opposite meaning). */
    val fmt   = in Bits (3 bits)
    /** Which 32-bit slice of a multi-word format this row stores (0 for every 1-word one). */
    val chunk = in UInt (2 bits)
    /** FPCR[5:4]: 0=RN 1=RZ 2=RM 3=RP. */
    val rmode = in Bits (2 bits)
    /** Valid `FpNarrowPack.Latency` cycles after the inputs. */
    val word  = out Bits (32 bits)
    /** Raised by chunk 0 only (D2). `dz` is structurally impossible here. */
    val exc   = out(FpExcFlags())
  }

  /** `shift64RightJamming` (softfloat-macros), count saturated at 64 -- exact, because any
    * count >= 64 produces the identical `{0 | (a != 0)}`. The operand is widened to 65 bits
    * purely so the coarse stage's `w >> 64` term has a non-zero result width. */
  private def jam(w: UInt, cnt: UInt): UInt = {
    val c   = Mux(cnt > U(64, 7 bits), U(64, 7 bits), cnt.resize(7))
    val w65 = w.resize(65)
    val sh  = Fp80.shiftRightJamFine(Fp80.shiftRightJamCoarse(w65, c(6 downto 3)), c(2 downto 0))
    sh(63 downto 0)
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // STAGE 0 -- exponent arithmetic, the jam shifts, and the round-increment ADD.
  // Everything with a wide carry chain lives here; stage 1 is left with range checks
  // and packing.
  // ═══════════════════════════════════════════════════════════════════════════════
  val s0 = new Area {
    val rn = io.rmode === 0
    val rz = io.rmode === 1
    val rm = io.rmode === 2
    val rp = io.rmode === 3

    val aSign      = Fp80.sign(io.src)
    val aExp       = Fp80.exp(io.src)          // 15 bits, biased
    val aSig       = Fp80.sig(io.src)          // 64 bits, explicit integer bit
    val aIsNan     = Fp80.isNan(io.src)
    val aIsSNan    = Fp80.isSNan(io.src)
    val aIsSpecial = aExp === U(0x7FFF, 15 bits)

    /** The `roundIncrement` selection shared by all three SoftFloat round-and-pack tails:
      * RN -> half-ulp, RZ -> 0, RM/RP -> all-ones only when that mode rounds AWAY from zero
      * for this sign. */
    def incrFor(sign: Bool, half: Int, allOnes: Int, w: Int): UInt =
      Mux(rn, U(half, w bits),
      Mux(rz, U(0, w bits),
      Mux(sign, Mux(rp, U(0, w bits), U(allOnes, w bits)),      // negative: RM rounds away
                Mux(rm, U(0, w bits), U(allOnes, w bits)))))    // positive: RP rounds away

    // ── Long / Word / Byte integer: floatx80_to_int32 + roundAndPackInt32 ──────────
    // A NaN is forced POSITIVE before rounding (softfloat.c: `if (aExp == 0x7FFF &&
    // aSig<<1) aSign = 0`), which is why a NaN saturates HIGH regardless of its own sign.
    val intSign  = Mux(aIsNan, False, aSign)
    val intScRaw = S(0x4037, 17 bits) - aExp.resize(17).asSInt
    val intSc    = Mux(intScRaw <= S(0, 17 bits), U(1, 7 bits),
                   Mux(intScRaw >= S(64, 17 bits), U(64, 7 bits), intScRaw.asUInt.resize(7)))
    val intSigSh = jam(aSig, intSc)
    val intRb    = intSigSh(6 downto 0)
    val intShr   = ((intSigSh +^ incrFor(intSign, 0x40, 0x7F, 8).resize(64)) >> 7).resize(58)
    val intTie   = (intRb === U(0x40, 7 bits)) && rn      // round-half-to-EVEN LSB clear
    val intAbs   = Mux(intTie, intShr & ~U(1, 58 bits), intShr)

    // ── Single: floatx80_to_float32 + roundAndPackFloat32 ─────────────────────────
    val f32Sig0 = jam(aSig, U(33, 7 bits))(31 downto 0)
    val f32Exp0 = Mux(aExp =/= 0 || f32Sig0 =/= 0,
                      aExp.resize(18).asSInt - S(0x3F81, 18 bits),
                      aExp.resize(18).asSInt)
    val f32Incr   = incrFor(aSign, 0x40, 0x7F, 8)
    val f32PreSum = f32Sig0 +^ f32Incr.resize(32)          // 33 bits
    val f32Ovfl = !aIsSpecial && ((f32Exp0 > S(0xFD, 18 bits)) ||
                  ((f32Exp0 === S(0xFD, 18 bits)) && f32PreSum(31)))
    val f32Sub  = !f32Ovfl && (f32Exp0 < S(0, 18 bits))
    val f32CntR = -f32Exp0
    val f32Cnt  = Mux(f32CntR >= S(64, 18 bits), U(64, 7 bits), f32CntR.asUInt.resize(7))
    val f32Sig  = Mux(f32Sub, jam(f32Sig0.resize(64), f32Cnt)(31 downto 0), f32Sig0)
    val f32ExpS = Mux(f32Sub, S(0, 18 bits), f32Exp0)
    val f32Rb   = f32Sig(6 downto 0)
    // `float_detect_tininess` is after-rounding for this SoftFloat build
    // (softfloat-specialize:43, the same constant FpRoundPack already relies on), so the
    // first of SoftFloat's three isTiny terms is constant-false.
    val f32Tiny = (f32Exp0 < S(-1, 18 bits)) || !f32PreSum(31)
    val f32Shr  = ((f32Sig +^ f32Incr.resize(32)) >> 7).resize(26)
    val f32Tie  = (f32Rb === U(0x40, 7 bits)) && rn
    val f32SigR = Mux(f32Tie, f32Shr & ~U(1, 26 bits), f32Shr)

    // ── Double: floatx80_to_float64 + roundAndPackFloat64 ─────────────────────────
    val f64Sig0 = jam(aSig, U(1, 7 bits))
    val f64Exp0 = Mux(aExp =/= 0 || aSig =/= 0,
                      aExp.resize(18).asSInt - S(0x3C01, 18 bits),
                      aExp.resize(18).asSInt)
    val f64Incr   = incrFor(aSign, 0x200, 0x3FF, 11)
    val f64PreSum = f64Sig0 +^ f64Incr.resize(64)          // 65 bits
    val f64Ovfl = !aIsSpecial && ((f64Exp0 > S(0x7FD, 18 bits)) ||
                  ((f64Exp0 === S(0x7FD, 18 bits)) && f64PreSum(63)))
    val f64Sub  = !f64Ovfl && (f64Exp0 < S(0, 18 bits))
    val f64CntR = -f64Exp0
    val f64Cnt  = Mux(f64CntR >= S(64, 18 bits), U(64, 7 bits), f64CntR.asUInt.resize(7))
    val f64Sig  = Mux(f64Sub, jam(f64Sig0, f64Cnt), f64Sig0)
    val f64ExpS = Mux(f64Sub, S(0, 18 bits), f64Exp0)
    val f64Rb   = f64Sig(9 downto 0)
    val f64Tiny = (f64Exp0 < S(-1, 18 bits)) || !f64PreSum(63)
    val f64Shr  = ((f64Sig +^ f64Incr.resize(64)) >> 10).resize(55)
    val f64Tie  = (f64Rb === U(0x200, 10 bits)) && rn
    val f64SigR = Mux(f64Tie, f64Shr & ~U(1, 55 bits), f64Shr)

    // ── Extended: store_extended_float80's exact 16/16/32/32 field layout ──────────
    // ea+0 = the sign+exponent word, ea+2 = a written ZERO (the reserved word the load
    // direction skips), ea+4 = mantissa[63:32], ea+8 = mantissa[31:0]. Chunk 0 is one LONG
    // store of {sign+exp, 0x0000}, byte-for-byte the pair of 16-bit writes Musashi does.
    val extWord = io.chunk.mux(
      U(0, 2 bits) -> (io.src(79 downto 64) ## B(0, 16 bits)),
      U(1, 2 bits) -> io.src(63 downto 32),
      default      -> io.src(31 downto 0))
  }

  // ─── the stage register ────────────────────────────────────────────────────────
  val r = new Area {
    val fmt      = RegNext(io.fmt)
    val chunk    = RegNext(io.chunk)
    val sign     = RegNext(s0.aSign)
    val isNan    = RegNext(s0.aIsNan)
    val isSNan   = RegNext(s0.aIsSNan)
    val isSpec   = RegNext(s0.aIsSpecial)
    // NaN payloads: commonNaNToFloat32/64 quiet the value and take aSig[61:40] / aSig[61:11].
    val nanS     = RegNext(s0.aSig(61 downto 40))
    val nanD     = RegNext(s0.aSig(61 downto 11))
    val extWord  = RegNext(s0.extWord)
    // integer path
    val intSign  = RegNext(s0.intSign)
    val intLow32 = RegNext(s0.intAbs(31 downto 0))
    val intHiNz  = RegNext(s0.intAbs(57 downto 32) =/= 0)
    val intRbNz  = RegNext(s0.intRb =/= 0)
    // single
    val f32SigR  = RegNext(s0.f32SigR)
    val f32ExpS  = RegNext(s0.f32ExpS(7 downto 0).asUInt)
    val f32Ovfl  = RegNext(s0.f32Ovfl)
    val f32Zero  = RegNext(s0.f32Incr === 0)   // toward-zero mode -> largest finite on ovfl
    val f32Unfl  = RegNext(s0.f32Sub && s0.f32Tiny && (s0.f32Rb =/= 0))
    val f32Inex  = RegNext(s0.f32Ovfl || (s0.f32Rb =/= 0))
    // double
    val f64SigR  = RegNext(s0.f64SigR)
    val f64ExpS  = RegNext(s0.f64ExpS(10 downto 0).asUInt)
    val f64Ovfl  = RegNext(s0.f64Ovfl)
    val f64Zero  = RegNext(s0.f64Incr === 0)
    val f64Unfl  = RegNext(s0.f64Sub && s0.f64Tiny && (s0.f64Rb =/= 0))
    val f64Inex  = RegNext(s0.f64Ovfl || (s0.f64Rb =/= 0))
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // STAGE 1 -- range checks, sign fix-up, packing, format/chunk muxing.
  // ═══════════════════════════════════════════════════════════════════════════════
  val s1 = new Area {
    // ── integer: SoftFloat's overflow test, reduced. `absZ>>32 != 0` OR the low word
    // exceeds the representable magnitude for this sign (0x7FFFFFFF positive,
    // 0x80000000 negative -- -2^31 IS representable, which is why they differ by one).
    val intOvf = r.intHiNz ||
                 Mux(r.intSign, r.intLow32 > U(BigInt("80000000", 16), 32 bits),
                                r.intLow32 > U(BigInt("7FFFFFFF", 16), 32 bits))
    val intVal = Mux(intOvf,
                     Mux(r.intSign, U(BigInt("80000000", 16), 32 bits),
                                    U(BigInt("7FFFFFFF", 16), 32 bits)),
                     Mux(r.intSign, (~r.intLow32) + 1, r.intLow32))
    // SoftFloat returns from the invalid arm BEFORE the inexact test, so an overflowing
    // conversion raises OPERR only.
    val intInex   = !intOvf && r.intRbNz
    val intSigned = intVal.asSInt
    val outOfWord = (intSigned > S(32767, 32 bits)) || (intSigned < S(-32768, 32 bits))
    val outOfByte = (intSigned > S(127, 32 bits))   || (intSigned < S(-128, 32 bits))

    // ── packFloatN, without the wide add. SoftFloat's `(exp<<23) + zSig` relies on the
    // significand carrying INTO the exponent field: the pre-round significand is confined
    // to [2^30, 2^31) (single) / [2^62, 2^63) (double), so after the increment and the
    // >>7 / >>10 the result is at most 2^24 / 2^53 and bits [24:23] / [53:52] hold exactly
    // the 0/1/2 that must be added to the exponent. `zSig == 0 => zExp = 0` is applied
    // first, exactly as SoftFloat does.
    val f32Z    = r.f32SigR === 0
    val f32Exp  = Mux(f32Z, U(0, 8 bits), r.f32ExpS) + Mux(f32Z, U(0, 2 bits), r.f32SigR(24 downto 23))
    val f32Norm = (r.sign ## f32Exp ## r.f32SigR(22 downto 0)).asUInt
    // `packFloat32(sign,0xFF,0) - (roundIncrement == 0)`: toward-zero modes deliver the
    // largest finite single instead of infinity.
    val f32OvflVal = Mux(r.f32Zero,
      (r.sign ## B(0xFE, 8 bits) ## B((BigInt(1) << 23) - 1, 23 bits)).asUInt,
      (r.sign ## B(0xFF, 8 bits) ## B(0, 23 bits)).asUInt)
    // commonNaNToFloat32 (softfloat-specialize): sign | 0x7FC00000 | ((aSig<<1) >> 41),
    // i.e. mantissa bit 22 forced set (quieted) over aSig[61:40].
    val f32NanVal = (r.sign ## B(0xFF, 8 bits) ## True ## r.nanS).asUInt
    val f32InfVal = (r.sign ## B(0xFF, 8 bits) ## B(0, 23 bits)).asUInt
    val f32Val = Mux(r.isSpec, Mux(r.isNan, f32NanVal, f32InfVal),
                 Mux(r.f32Ovfl, f32OvflVal, f32Norm))

    val f64Z    = r.f64SigR === 0
    val f64Exp  = Mux(f64Z, U(0, 11 bits), r.f64ExpS) + Mux(f64Z, U(0, 2 bits), r.f64SigR(53 downto 52))
    val f64Norm = (r.sign ## f64Exp ## r.f64SigR(51 downto 0)).asUInt
    val f64OvflVal = Mux(r.f64Zero,
      (r.sign ## B(0x7FE, 11 bits) ## B((BigInt(1) << 52) - 1, 52 bits)).asUInt,
      (r.sign ## B(0x7FF, 11 bits) ## B(0, 52 bits)).asUInt)
    // commonNaNToFloat64: sign | 0x7FF8000000000000 | ((aSig<<1) >> 12).
    val f64NanVal = (r.sign ## B(0x7FF, 11 bits) ## True ## r.nanD).asUInt
    val f64InfVal = (r.sign ## B(0x7FF, 11 bits) ## B(0, 52 bits)).asUInt
    val f64Val = Mux(r.isSpec, Mux(r.isNan, f64NanVal, f64InfVal),
                 Mux(r.f64Ovfl, f64OvflVal, f64Norm))

    val dblWord = Mux(r.chunk === 0, f64Val(63 downto 32).asBits, f64Val(31 downto 0).asBits)

    io.word := r.fmt.mux(
      B"3'b000" -> intVal.asBits,      // Long
      B"3'b001" -> f32Val.asBits,      // Single
      B"3'b010" -> r.extWord,          // Extended
      B"3'b100" -> intVal.asBits,      // Word  (the store row truncates)
      B"3'b101" -> dblWord,            // Double
      B"3'b110" -> intVal.asBits,      // Byte  (the store row truncates)
      default   -> B(0, 32 bits))      // 011/111 Packed -- unreachable, rejected at decode

    val isInt = (r.fmt === B"3'b000") || (r.fmt === B"3'b100") || (r.fmt === B"3'b110")
    val isSgl = r.fmt === B"3'b001"
    val isDbl = r.fmt === B"3'b101"
    val first = r.chunk === 0
    io.exc.snan  := first && (isSgl || isDbl) && r.isSNan
    io.exc.operr := first && isInt && (intOvf ||
                      ((r.fmt === B"3'b100") && outOfWord) ||
                      ((r.fmt === B"3'b110") && outOfByte))
    io.exc.ovfl  := first && ((isSgl && r.f32Ovfl) || (isDbl && r.f64Ovfl))
    io.exc.unfl  := first && ((isSgl && r.f32Unfl) || (isDbl && r.f64Unfl))
    io.exc.dz    := False
    io.exc.inex2 := first && !(r.isSpec && !isInt) &&
                    ((isInt && intInex) || (isSgl && r.f32Inex) || (isDbl && r.f64Inex))
  }
}
