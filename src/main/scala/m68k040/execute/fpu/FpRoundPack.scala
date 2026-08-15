package m68k040.execute.fpu

import spinal.core._

object FpRoundPack {
  /** Registered stages from `io.inValid` to `io.outValid`. */
  val Latency = 3
}

/** Normalise-range / round / pack, plus Decision 10's hardware-native OVFL and UNFL
  * substitution, for 80-bit extended results. Fully pipelined, initiation interval 1,
  * never stalls: results leave in the order they entered.
  *
  * This is a direct hardware transcription of SoftFloat's `roundAndPackFloatx80`
  * precision80 path (softfloat.c:598-670) -- deliberately, because that function IS the
  * lock-step oracle (Decision 1). The two SoftFloat quantities that need justification:
  *
  *  (a) The 64-bit `zSig1` low word is compressed to {round, sticky}. The precision80 path
  *      reads exactly two things from zSig1: `(sbits64)zSig1 < 0` (i.e. zSig1[63], our
  *      `round`) and `(bits64)(zSig1<<1) == 0` (i.e. zSig1[62:0] == 0, our `!sticky`),
  *      plus `zSig1 != 0` (our `round | sticky`). Nothing else. The compression is exact.
  *
  *  (b) `shift64ExtraRightJamming(zSig0, zSig1, 1 - zExp, ...)` on the subnormal arm is
  *      reproduced by jam-shifting the 66-bit word {sig, round, sticky}: for count c >= 1
  *      SoftFloat produces z1[63] = a0[c-1] and z1[62:0] != 0 iff (a0[c-2:0] != 0 or
  *      a1 != 0), which is bit-for-bit what a jam shift of {sig,round,sticky} yields, and
  *      saturating c at 66 is exact because a 67-position shift and a 4000-position shift
  *      produce the identical {0, 0, sticky=1}.
  *
  * OVERFLOW (Decision 10's 4-way table) is the `exp > 0x7FFE` arm. SoftFloat's own
  * selection expression is `RZ || (sign && RP) || (!sign && RM)` -> largest finite,
  * otherwise infinity, which is *exactly* the design spec's table:
  *   RN -> Infinity(sign) | RZ -> largest(sign) | RM -> +ovfl:largest, -ovfl:Inf
  *   | RP -> +ovfl:Inf, -ovfl:largest.
  *
  * UNDERFLOW (Decision 10's denormalise-then-round path) is the `exp <= 0` arm: shift the
  * mantissa right while the exponent climbs to the denormalised value (here: shift right by
  * 1-exp, exponent becomes 0), then round. If the shift empties the mantissa entirely the
  * same rounding increment produces zero-vs-smallest-denormal per sign and rounding mode --
  * i.e. the spec's "structurally identical in shape to overflow's" fallback table drops out
  * of the same increment logic rather than being a second table.
  *
  * Tininess is detected AFTER rounding, matching SoftFloat's
  * `float_detect_tininess = float_tininess_after_rounding` (softfloat-specialize:43). */
class FpRoundPack extends Component {
  val io = new Bundle {
    val inValid  = in Bool ()
    val inReq    = in(FpRoundReq())
    val outValid = out Bool ()
    val outRes   = out(FpResult())
  }

  // Rounding mode travels WITH the request (io.inReq.rmode = FPCR[5:4] at issue), so a
  // mid-flight FPCR write can never re-mux an already-issued result.
  val rn = io.inReq.rmode === 0
  val rz = io.inReq.rmode === 1
  val rm = io.inReq.rmode === 2
  val rp = io.inReq.rmode === 3

  private val allOnes64 = U(BigInt("FFFFFFFFFFFFFFFF", 16), 64 bits)

  // -- RP0: range classification -----------------------------------------------
  // Decides which of the three arms (overflow / subnormal / normal) applies, and the
  // rounding increment for the normal arm. Only 18-bit compares and a handful of gates.
  val s0 = new Area {
    val nz      = io.inReq.round || io.inReq.sticky
    // SoftFloat precision80 `increment` (softfloat.c:598-611).
    val incr    = Mux(rn, io.inReq.round,
                  Mux(rz, False,
                  Mux(io.inReq.sign, rm && nz, rp && nz)))
    val allOnes = io.inReq.sig === allOnes64
    val ovfl    = (io.inReq.exp > S(0x7FFE, 18 bits)) ||
                  (io.inReq.exp === S(0x7FFE, 18 bits) && allOnes && incr)
    val sub     = !ovfl && (io.inReq.exp <= S(0, 18 bits))
    // shift count 1 - exp, saturated at 66 (exact, see header note (b)).
    val cntFull = S(1, 18 bits) - io.inReq.exp
    val cntSat  = Mux(cntFull > S(66, 18 bits), U(66, 7 bits), cntFull.asUInt.resize(7))

    val vld   = RegNext(io.inValid) init False
    val req   = RegNext(io.inReq)
    val rIncr = RegNext(incr)
    val rOvfl = RegNext(ovfl)
    val rSub  = RegNext(sub)
    val rCnt  = RegNext(cntSat)
    // Latched so a mid-flight FPCR write cannot re-mux an in-flight result.
    val rRz   = RegNext(rz); val rRm = RegNext(rm); val rRp = RegNext(rp); val rRn = RegNext(rn)
  }

  // -- RP1: the subnormal (underflow) right-shift, and the final increment decision ---
  val s1 = new Area {
    // 66-bit {sig, round, sticky}; bit 0 IS the sticky position, so jamming into it is
    // always semantically correct.
    val ext   = (s0.req.sig ## s0.req.round ## s0.req.sticky).asUInt      // 66 bits
    val shC   = Fp80.shiftRightJamCoarse(ext, s0.rCnt(6 downto 3))
    val shF   = Fp80.shiftRightJamFine(shC, s0.rCnt(2 downto 0))
    val subSig    = shF(65 downto 2)
    val subRound  = shF(1)
    val subSticky = shF(0)

    val sig    = Mux(s0.rSub, subSig,    s0.req.sig)
    val round  = Mux(s0.rSub, subRound,  s0.req.round)
    val sticky = Mux(s0.rSub, subSticky, s0.req.sticky)
    val nz     = round || sticky
    val incr   = Mux(s0.rSub,
                     Mux(s0.rRn, round, Mux(s0.rRz, False,
                         Mux(s0.req.sign, s0.rRm && nz, s0.rRp && nz))),
                     s0.rIncr)
    // SoftFloat tininess is `float_tininess_after_rounding` (softfloat-specialize:43), so
    // isTiny is (exp < 0) || !increment || (sig != all-ones) -- true in every case this
    // arm can reach except the exact "rounds back up to the smallest normal" corner.
    val isTiny = (s0.req.exp < S(0, 18 bits)) || !s0.rIncr || (s0.req.sig =/= allOnes64)
    val exp0   = Mux(s0.rSub, S(0, 18 bits), s0.req.exp)

    val vld    = RegNext(s0.vld) init False
    val req    = RegNext(s0.req)
    val rSig   = RegNext(sig)
    val rIncr  = RegNext(incr)
    val rTieClr= RegNext(s0.rRn && !sticky)       // round-to-nearest-EVEN LSB clear
    val rExp   = RegNext(exp0)
    val rOvfl  = RegNext(s0.rOvfl)
    val rSub   = RegNext(s0.rSub)
    val rInex  = RegNext(nz)
    val rUnfl  = RegNext(s0.rSub && isTiny && nz)
    val rToMax = RegNext(s0.rRz || (s0.req.sign && s0.rRp) || (!s0.req.sign && s0.rRm))
  }

  // -- RP2: increment, carry fix-up, substitution, pack, classify ----------------
  val s2 = new Area {
    val sum   = s1.rSig +^ s1.rIncr.asUInt.resize(64)          // 65 bits
    val carry = sum(64)
    val incd  = sum(63 downto 0)
    val tied  = Mux(s1.rTieClr && s1.rIncr, incd & ~U(1, 64 bits), incd)

    val sigN = UInt(64 bits)
    val expN = SInt(18 bits)
    when(carry) {                                              // 0xFFFF..F + 1 -> 2^64
      sigN := U(BigInt(1) << 63, 64 bits); expN := s1.rExp + 1
    } elsewhen(!s1.rIncr && s1.rSig === 0) {                   // SoftFloat: zSig0==0 => exp=0
      sigN := U(0, 64 bits);              expN := S(0, 18 bits)
    } otherwise {
      sigN := tied
      // subnormal that rounded up into the smallest normal: SoftFloat sets zExp = 1
      expN := Mux(s1.rSub && tied(63), S(1, 18 bits), s1.rExp)
    }

    val packed = Fp80.pack(s1.req.sign, expN.asUInt.resize(15), sigN)
    // Decision 10's overflow table, verbatim.
    val ovflVal = Mux(s1.rToMax,
                      Fp80.pack(s1.req.sign, U(0x7FFE, 15 bits), allOnes64),
                      Fp80.packInf(s1.req.sign))

    val value = Mux(s1.req.bypass, s1.req.bypassValue,
                Mux(s1.rOvfl, ovflVal, packed))

    val exc = FpExcFlags()
    exc.snan  := s1.req.exc.snan
    exc.operr := s1.req.exc.operr
    exc.dz    := s1.req.exc.dz
    exc.ovfl  := !s1.req.bypass && s1.rOvfl
    exc.unfl  := !s1.req.bypass && s1.rUnfl
    // A bypassing front-end may declare its own inexactness (FINT/FINTRZ); the datapath's
    // own inexactness is only meaningful on the non-bypass path. Overflow is always inexact.
    exc.inex2 := s1.req.exc.inex2 || (!s1.req.bypass && (s1.rInex || s1.rOvfl))

    io.outValid    := RegNext(s1.vld) init False
    io.outRes.value   := RegNext(value)
    io.outRes.writeFp := RegNext(s1.req.writeFp)
    io.outRes.fpcc    := RegNext(Mux(s1.req.fpccFromSrc, s1.req.fpccOverride, Fp80.classify(value)))
    io.outRes.exc     := RegNext(exc)
  }
}
