package m68k040.execute.fpu

import spinal.core._

object FpDivSqrtCore {
  /** Radix-2 restoring steps. 65 for BOTH FDIV and FSQRT: both need 64 significand bits plus
    * one round bit, and radix 2 produces exactly one result bit per iteration. Sticky comes
    * free from `finalRemainder != 0`, needing no extra iteration. (The 2026-08-09 spec §6's
    * "56 -> 67 iterations" estimate is now an exact number.) */
  val Iterations = 65

  /** Clock edges from an accepted `start` until `done` is observable, worst case:
    * 1 (accept/latch) + 1 (S_CLZ classify+LZC) + 1 (S_NORM pre-normalise) + 1 (S_SETUP)
    * + 65 (S_ITER) + 1 (S_ROUND hand-off) + FpRoundPack.Latency (3) + 1 (capture into
    * `resReg`/`doneR`) = 73. Documentation only -- this lane is a busy/done handshake, not a
    * constant-latency pipe, and `FpuCoreSpec` measures the real number rather than assuming
    * it. Special cases (NaN / Inf / zero / divide-by-zero / sqrt-of-negative) short-circuit
    * S_NORM..S_ITER entirely and finish in `SpecialLatency` cycles. */
  val WorstCaseLatency = 73
  val SpecialLatency   = 7
}

/** One held iterative context shared by FDIV and FSQRT (2026-08-09 spec §3: "one held
  * iterative context", NOT replicated). Radix-2 restoring, 65 iterations for both.
  *
  * Radix-2 restoring is chosen over radix-4 SRT deliberately: it is structurally identical
  * to the existing DivCore.scala:79-92 -- one wide compare/subtract plus a 2:1 mux per cycle
  * -- with DivCore's own note that "the per-cycle path is one (W+1)-bit compare/subtract + a
  * 2:1 mux (shallow), so FMax is dominated elsewhere". That claim already survived this
  * design's post-route gates at 32-bit width; extending the same shape to 68 bits changes the
  * carry chain from 5 to 9 CARRY8 blocks, not the topology. Radix-4 SRT would halve the count
  * to 33 but introduces a quotient-digit-selection PLA and a redundant (carry-save) partial
  * remainder -- a genuinely new critical-path family, which is precisely what 2026-08-09
  * spec §7 forbids. Recorded as a documented future lever, gated on real profiling.
  *
  * ── DIVIDE FORMULATION ──
  * With A = aSig, B = bSig (both normalised into [2^63, 2^64) by S_NORM):
  *   dEff = (A >= B) ? 2B : B ;  Q = floor(A * 2^65 / dEff) ;  rem = A * 2^65 mod dEff
  *   sig = Q[64:1], round = Q[0], sticky = (rem != 0)
  *   zExp = aExp - bExp + ((A >= B) ? 0x3FFF : 0x3FFE)
  * This is bit-identical to softfloat.c:3444-3468's estimateDiv128To64 construction:
  * SoftFloat's zSig0 = floor(A*2^63/B) = Q>>1, its round bit zSig1[63] = floor(2R/B) mod 2
  * = Q[0] with R = A*2^63 mod B, and its sticky (zSig1[62:0] != 0, OR the exact
  * `zSig1 |= ((rem1|rem2) != 0)` fix-up) = (2R mod B != 0) = (A*2^64 mod B != 0) = rem != 0.
  *
  * ── SQUARE-ROOT FORMULATION ──
  * `result = Q * 2^k` with `Q = floor(sqrt(A))` a 65-bit integer and
  * `A = sig << (E even ? 65 : 66)`, where `E = aExp - 0x3FFF`. The parity split forces
  * `A in [2^128, 2^130)` so `Q in [2^64, 2^65)` in both cases, and
  * `zExp = (E >>> 1) + 0x3FFF` (arithmetic shift = floor), which is softfloat.c:3594
  * verbatim. 130 bits at two bits per iteration = 65 iterations.
  * `sig = Q[64:1]`, `round = Q[0]`, `sticky = (A - Q^2) != 0`.
  *
  * ── WHY THIS LANE HAS ITS OWN FpRoundPack ──
  * Its completion time is data-dependent, so it can collide with the fixed lane's.
  * Instantiating a second FpRoundPack removes the structural hazard entirely, which is
  * strictly cheaper than an arbiter plus a hold buffer INSIDE this component and keeps the
  * "one atomic arbiter" where 2026-08-09 spec §3 puts it -- in the EU, above FpuCore.
  *
  * ── THE done/ack CONTRACT (differs from DivCore on purpose) ──
  * DivCore.io.done is a one-cycle pulse. Here `io.done` is a LEVEL, held until `io.ack`, and
  * `io.busy` stays asserted across the hold. This directly implements the spec §3 requirement
  * that "a non-backpressured completion Flow is not permission to drop either result": if the
  * EU's arbiter grants the fixed lane this cycle, the iterative result is still there next
  * cycle. */
class FpDivSqrtCore extends Component {
  val io = new Bundle {
    val start  = in Bool ()
    val isSqrt = in Bool ()
    val dst    = in Bits (80 bits)      // dividend (FPn); ignored by FSQRT
    val src    = in Bits (80 bits)      // divisor (<ea>); the operand for FSQRT
    val rmode  = in Bits (2 bits)
    val busy   = out Bool ()
    val done   = out Bool ()
    val ack    = in Bool ()
    val res    = out(FpResult())
  }

  val S_IDLE = 0; val S_CLZ = 1; val S_NORM = 2; val S_SETUP = 3
  val S_ITER = 4; val S_ROUND = 5; val S_HOLD = 6
  val state = RegInit(U(S_IDLE, 3 bits))

  val sqrtR  = RegInit(False)
  val dstR   = Reg(Bits(80 bits)) init 0; val srcR = Reg(Bits(80 bits)) init 0
  val aSig   = Reg(UInt(64 bits)) init 0; val bSig = Reg(UInt(64 bits)) init 0
  val aExp   = Reg(SInt(18 bits)) init 0; val bExp = Reg(SInt(18 bits)) init 0
  val zSign  = RegInit(False);            val zExp = Reg(SInt(18 bits)) init 0
  val aClz   = Reg(UInt(7 bits)) init 0;  val bClz = Reg(UInt(7 bits)) init 0
  val aSubR  = RegInit(False);            val bSubR = RegInit(False)
  val rmodeR = Reg(Bits(2 bits)) init 0   // FPCR[5:4] latched at accept

  val rem    = Reg(UInt(68 bits)) init 0
  val quot   = Reg(UInt(65 bits)) init 0
  val dEff   = Reg(UInt(65 bits)) init 0     // divide: the effective divisor
  val rad    = Reg(UInt(130 bits)) init 0    // sqrt: the radicand, consumed 2 bits/iteration
  val cnt    = Reg(UInt(7 bits)) init 0

  val bypReq = Reg(FpRoundReq())
  val bypass = RegInit(False)
  val resReg = Reg(FpResult())
  val doneR  = RegInit(False)

  val rp = new FpRoundPack
  rp.io.inValid := False
  // Default to the held bypass descriptor rather than don't-care: the round/pack registers
  // shift unconditionally, and a defined default keeps X out of the netlist and out of sim.
  rp.io.inReq := bypReq

  io.busy := state =/= U(S_IDLE, 3 bits)
  io.done := doneR
  io.res  := resReg

  switch(state) {
    is(U(S_IDLE, 3 bits)) {
      when(io.start) {
        sqrtR := io.isSqrt; dstR := io.dst; srcR := io.src; rmodeR := io.rmode
        state := U(S_CLZ, 3 bits)
      }
    }

    // -- S_CLZ: classify, resolve every special case, count leading zeros -------
    is(U(S_CLZ, 3 bits)) {
      val a = Mux(sqrtR, srcR, dstR)        // FSQRT's single operand arrives on `src`
      val b = srcR
      val aNan = Fp80.isNan(a); val bNan = Fp80.isNan(b)
      val aInf = Fp80.isInf(a); val bInf = Fp80.isInf(b)
      val aZ   = a(78 downto 0) === 0;  val bZ = b(78 downto 0) === 0
      val sgn  = Mux(sqrtR, False, Fp80.sign(a) ^ Fp80.sign(b))

      val r = FpRoundReq().overridable()
      r.sign := sgn; r.exp := 0; r.sig := 0; r.round := False; r.sticky := False
      r.writeFp := True; r.fpccFromSrc := False; r.fpccOverride := 0
      r.bypass := True; r.bypassValue := Fp80.defaultNan
      r.rmode := rmodeR
      r.exc.clearExc()
      r.exc.snan := Mux(sqrtR, Fp80.isSNan(a), Fp80.isSNan(a) || Fp80.isSNan(b))

      val takeBypass = Bool(); takeBypass := True
      when(sqrtR) {                                       // softfloat.c:3577-3592
        when(aNan)                       { r.bypassValue := Fp80.propagateNan(a, a) }
        .elsewhen(aInf && !Fp80.sign(a)) { r.bypassValue := a }
        .elsewhen(aInf)                  { r.bypassValue := Fp80.defaultNan; r.exc.operr := True }
        .elsewhen(Fp80.sign(a) && aZ)    { r.bypassValue := a }        // -0 -> -0
        .elsewhen(Fp80.sign(a))          { r.bypassValue := Fp80.defaultNan; r.exc.operr := True }
        .elsewhen(aZ)                    { r.bypassValue := Fp80.packZero(False) }
        .otherwise                       { takeBypass := False }
      } otherwise {                                       // softfloat.c:3401-3432
        when(aNan || bNan)         { r.bypassValue := Fp80.propagateNan(a, b) }
        .elsewhen(aInf && bInf)    { r.bypassValue := Fp80.defaultNan; r.exc.operr := True }
        .elsewhen(aInf)            { r.bypassValue := Fp80.packInf(sgn) }
        .elsewhen(bInf)            { r.bypassValue := Fp80.packZero(sgn) }
        .elsewhen(bZ && aZ)        { r.bypassValue := Fp80.defaultNan; r.exc.operr := True }
        .elsewhen(bZ)              { r.bypassValue := Fp80.packInf(sgn); r.exc.dz := True }
        .elsewhen(aZ)              { r.bypassValue := Fp80.packZero(sgn) }
        .otherwise                 { takeBypass := False }
      }

      bypReq := r; bypass := takeBypass; zSign := sgn
      aSig := Fp80.sig(a); bSig := Fp80.sig(b)
      aExp := Fp80.exp(a).asSInt.resize(18); bExp := Fp80.exp(b).asSInt.resize(18)
      aSubR := Fp80.exp(a) === 0; bSubR := Fp80.exp(b) === 0
      aClz := Fp80.clz(a(63 downto 0)).resize(7)
      bClz := Fp80.clz(b(63 downto 0)).resize(7)
      state := Mux(takeBypass, U(S_ROUND, 3 bits), U(S_NORM, 3 bits))
    }

    // -- S_NORM: pre-normalise subnormal operands (normalizeFloatx80Subnormal) --
    is(U(S_NORM, 3 bits)) {
      when(aSubR) { aSig := aSig |<< aClz; aExp := S(1, 18 bits) - aClz.asSInt.resize(18) }
      when(bSubR && !sqrtR) {
        bSig := bSig |<< bClz; bExp := S(1, 18 bits) - bClz.asSInt.resize(18)
      }
      state := U(S_SETUP, 3 bits)
    }

    // -- S_SETUP ---------------------------------------------------------------
    is(U(S_SETUP, 3 bits)) {
      cnt  := 0
      quot := 0
      when(sqrtR) {
        val e     = aExp - S(0x3FFF, 18 bits)
        val even  = !e(0)
        zExp := (e >> 1) + S(0x3FFF, 18 bits)
        rad  := Mux(even, (aSig << 65).resize(130), (aSig << 66).resize(130))
        rem  := 0
      } otherwise {
        val aGe = aSig >= bSig
        // Q = floor(aSig * 2^65 / dEff) with dEff = 2*bSig when aSig >= bSig, else bSig.
        dEff := Mux(aGe, (bSig << 1).resize(65), bSig.resize(65))
        zExp := aExp - bExp + Mux(aGe, S(0x3FFF, 18 bits), S(0x3FFE, 18 bits))
        rem  := aSig.resize(68)
      }
      state := U(S_ITER, 3 bits)
    }

    // -- S_ITER: 65 radix-2 restoring steps ------------------------------------
    is(U(S_ITER, 3 bits)) {
      when(sqrtR) {
        val r2 = ((rem(65 downto 0) ## rad(129 downto 128)).asUInt).resize(68)
        val t  = ((quot(64 downto 0) ## B"01").asUInt).resize(68)      // (q << 2) | 1
        val ge = r2 >= t
        rem  := Mux(ge, r2 - t, r2)
        quot := (quot(63 downto 0) ## ge).asUInt
        rad  := (rad(127 downto 0) ## B"00").asUInt
      } otherwise {
        val r2 = (rem(66 downto 0) ## False).asUInt.resize(68)
        val ge = r2 >= dEff.resize(68)
        rem  := Mux(ge, r2 - dEff.resize(68), r2)
        quot := (quot(63 downto 0) ## ge).asUInt
      }
      cnt := cnt + 1
      when(cnt === U(FpDivSqrtCore.Iterations - 1, 7 bits)) { state := U(S_ROUND, 3 bits) }
    }

    // -- S_ROUND: hand the tuple to this lane's private FpRoundPack -------------
    is(U(S_ROUND, 3 bits)) {
      rp.io.inValid := True
      when(!bypass) {
        rp.io.inReq.bypass := False
        rp.io.inReq.sign   := zSign
        rp.io.inReq.exp    := zExp
        rp.io.inReq.sig    := quot(64 downto 1)
        rp.io.inReq.round  := quot(0)
        rp.io.inReq.sticky := rem =/= 0
      }
      state := U(S_HOLD, 3 bits)
    }

    // -- S_HOLD: capture, then hold `done` until the EU's arbiter acknowledges --
    is(U(S_HOLD, 3 bits)) {
      when(rp.io.outValid) { resReg := rp.io.outRes; doneR := True }
      when(doneR && io.ack) { doneR := False; state := U(S_IDLE, 3 bits) }
    }
  }
}
