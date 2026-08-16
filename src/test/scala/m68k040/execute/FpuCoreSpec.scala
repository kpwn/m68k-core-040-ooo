package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import m68k040.execute.fpu._
import spinal.core.sim._
import spinal.core.ClockDomain
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

/** Standalone validation of the 80-bit extended FpuCore, in isolation from the IQ/ROB/
  * rename, mirroring MulCoreSpec.scala's structure. `FpRefModel` is the oracle; its
  * arithmetic is an independent construction (exact rational arithmetic) from the RTL's
  * SoftFloat-shaped one, so agreement is evidence rather than a shared bug.
  *
  * Every directed vector is checked TWICE: against a literal expected 80-bit word (so a bug
  * in FpRefModel cannot hide a bug in the RTL), and against FpRefModel (so the sweep can be
  * extended without hand-computing every word). If a literal and FpRefModel ever disagree,
  * the TEST is wrong -- resolve it against exact arithmetic, never by bending the RTL. */
class FpuCoreSpec extends AnyFunSuite {

  val PosZero = BigInt(0)
  val NegZero = BigInt(1) << 79
  val PosInf  = (BigInt(0x7FFF) << 64) | (BigInt(1) << 63)
  val NegInf  = (BigInt(1) << 79) | PosInf
  val QNan    = (BigInt(0x7FFF) << 64) | (BigInt(3) << 62)               // quiet: bit62 set
  val SNan    = (BigInt(0x7FFF) << 64) | (BigInt(1) << 63) | BigInt(1)   // signalling: bit62 clear
  val One     = (BigInt(0x3FFF) << 64) | (BigInt(1) << 63)
  val Two     = (BigInt(0x4000) << 64) | (BigInt(1) << 63)
  val Four    = (BigInt(0x4001) << 64) | (BigInt(1) << 63)
  val Half    = (BigInt(0x3FFE) << 64) | (BigInt(1) << 63)
  val MaxFin  = (BigInt(0x7FFE) << 64) | ((BigInt(1) << 64) - 1)
  val MinNorm = (BigInt(0x0001) << 64) | (BigInt(1) << 63)
  val MinSub  = BigInt(1)                                                // exp 0, mantissa 1
  val ThreePt5    = (BigInt(0x4000) << 64) | BigInt("E000000000000000", 16)
  val NegThreePt5 = (BigInt(1) << 79) | ThreePt5
  val Three   = (BigInt(0x4000) << 64) | BigInt("C000000000000000", 16)
  val Nine    = (BigInt(0x4002) << 64) | BigInt("9000000000000000", 16)

  // ── FSQRT exponent-path extremes ──────────────────────────────────────────────
  // FSQRT is the one op whose reference used to share the RTL's own formulation, so the
  // exponent path needs DIRECTED cover at both ends. Genuine denormalised operands (biased
  // exponent 0, integer bit clear -- value = sig * 2^-16445), and operands parked at the top
  // of the exponent range.
  val SubTwo   = BigInt(2)                                   // 2^-16444, an exact square
  val SubHiBit = BigInt(1) << 62                             // 2^-16383, clz = 1
  val SubMax   = (BigInt(1) << 63) - 1                       // largest subnormal
  val P16382   = (BigInt(0x7FFD) << 64) | (BigInt(1) << 63)  // 2^16382, an exact square
  val P16381   = (BigInt(0x7FFC) << 64) | (BigInt(1) << 63)  // 2^16381, odd exponent

  case class Got(value: BigInt, writeFp: Boolean, fpcc: Int,
                 snan: Boolean, operr: Boolean, ovfl: Boolean,
                 unfl: Boolean, dz: Boolean, inex: Boolean)

  def readRes(r: FpResult): Got = Got(
    r.value.toBigInt, r.writeFp.toBoolean, r.fpcc.toInt,
    r.exc.snan.toBoolean, r.exc.operr.toBoolean, r.exc.ovfl.toBoolean,
    r.exc.unfl.toBoolean, r.exc.dz.toBoolean, r.exc.inex2.toBoolean)

  /** Drive one fixed-lane op and wait for doneFixed, asserting the exact latency.
    *
    * LATENCY CONVENTION: `n` counts waitSampling() calls AFTER the accepting edge, so the
    * result is observed on the n-th cycle after acceptance. `FpuCore.FixedLatency` is the
    * number of registered stages between `start` and `doneFixed`, and the observed `n` is
    * that number (the same convention MulCoreSpec's dense burst test locks in for MulCore:
    * `firstDoneCycle == MulCore.Latency`). */
  def runFixed(dut: FpuCore, cd: ClockDomain, op: FpOp.E,
               d: BigInt, s: BigInt, rm: Int, crom: Int = 0): Got = {
    dut.io.start #= true; dut.io.op #= op
    dut.io.dst #= d; dut.io.src #= s; dut.io.rmode #= rm; dut.io.cromSel #= crom
    cd.waitSampling()
    dut.io.start #= false
    var n = 0
    while (!dut.io.doneFixed.toBoolean && n < FpuCore.FixedLatency + 8) { cd.waitSampling(); n += 1 }
    assert(dut.io.doneFixed.toBoolean, s"no doneFixed for $op")
    assert(n == FpuCore.FixedLatency,
      s"$op completed after $n cycles, expected exactly ${FpuCore.FixedLatency}")
    val g = readRes(dut.io.resFixed); cd.waitSampling(2); g
  }

  def runIter(dut: FpuCore, cd: ClockDomain, op: FpOp.E, d: BigInt, s: BigInt, rm: Int): Got = {
    dut.io.start #= true; dut.io.op #= op
    dut.io.dst #= d; dut.io.src #= s; dut.io.rmode #= rm; dut.io.cromSel #= 0
    cd.waitSampling()
    dut.io.start #= false
    var n = 0
    while (!dut.io.doneIter.toBoolean && n < FpDivSqrtCore.WorstCaseLatency + 16) {
      cd.waitSampling(); n += 1
    }
    assert(dut.io.doneIter.toBoolean, s"no doneIter for $op after $n cycles")
    assert(n <= FpDivSqrtCore.WorstCaseLatency,
      s"$op took $n cycles, above the declared worst case ${FpDivSqrtCore.WorstCaseLatency}")
    val g = readRes(dut.io.resIter)
    // doneIter is a LEVEL: prove it survives a cycle without ack, then release it.
    cd.waitSampling()
    assert(dut.io.doneIter.toBoolean, "doneIter dropped before iterAck")
    assert(readRes(dut.io.resIter) == g, "resIter changed before iterAck")
    dut.io.iterAck #= true; cd.waitSampling(); dut.io.iterAck #= false
    cd.waitSampling()
    assert(!dut.io.busyIter.toBoolean, "busyIter still set after iterAck")
    g
  }

  def init(dut: FpuCore, cd: ClockDomain): Unit = {
    dut.io.start #= false; dut.io.op #= FpOp.FMOVE
    dut.io.dst #= 0; dut.io.src #= 0; dut.io.rmode #= 0
    dut.io.cromSel #= 0; dut.io.iterAck #= false
    cd.forkStimulus(10); cd.waitSampling(5)
  }

  /** ONE Verilator build shared by every test (the ExecuteLockStepSpec lesson: a per-test
    * `compile` rebuilds the whole DUT and dominates runtime). */
  lazy val dut = M68kSim().withVerilator.compile(new FpuCore)

  // ────────────────────────────────────────────────────────────────────────────
  // Reference-model self-check. Runs WITHOUT the simulator, so a wrong literal is
  // caught before a single cycle of RTL is exercised.
  // ────────────────────────────────────────────────────────────────────────────
  test("FpRefModel agrees with the hand-computed directed literals") {
    val Onep5   = (BigInt(0x3FFF) << 64) | BigInt("C000000000000000", 16)
    val Seven   = (BigInt(0x4001) << 64) | BigInt("E000000000000000", 16)
    val Ulp     = (BigInt(0x3FC0) << 64) | (BigInt(1) << 63)          // 2^-63
    val HalfUlp = (BigInt(0x3FBF) << 64) | (BigInt(1) << 63)          // 2^-64
    val OnePlusUlp = (BigInt(0x3FFF) << 64) | (BigInt(1) << 63) | BigInt(1)
    assert(FpRefModel.add(One, One, 0)._1 == Two)
    assert(FpRefModel.add(Two, Two, 0)._1 == Four)
    assert(FpRefModel.sub(Four, Two, 0)._1 == Two)
    assert(FpRefModel.sub(Two, Two, 0)._1 == PosZero)
    assert(FpRefModel.sub(Two, Two, 2)._1 == NegZero, "exact cancellation is -0 under RM")
    assert(FpRefModel.mul(Two, Two, 0)._1 == Four)
    assert(FpRefModel.mul(ThreePt5, Two, 0)._1 == Seven)
    assert(FpRefModel.add(One, Half, 0)._1 == Onep5)
    assert(FpRefModel.sub(One, Half, 0)._1 == Half)
    assert(FpRefModel.sub(OnePlusUlp, One, 0)._1 == Ulp, "massive-cancellation close path")
    assert(FpRefModel.add(One, HalfUlp, 0)._1 == One, "RN tie to even keeps 1.0")
    assert(FpRefModel.add(One, HalfUlp, 3)._1 == OnePlusUlp, "RP rounds the tie up")
    // FDIV / FSQRT correctly-rounded literals
    assert(FpRefModel.div(Four, Two, 0)._1 == Two)
    assert(FpRefModel.div(Nine, Three, 0)._1 == Three)
    assert(FpRefModel.sqrt(Four, 0)._1 == Two)
    assert(FpRefModel.sqrt(Nine, 0)._1 == Three)
    assert(FpRefModel.sqrt(Two, 0)._1 == ((BigInt(0x3FFF) << 64) | BigInt("B504F333F9DE6484", 16)),
      "sqrt(2) correctly rounded")
    // FSQRT exponent extremes, hand-computed. sqrt halves the binary exponent, so a
    // subnormal (value = sig * 2^-16445) still lands mid-range: sqrt(2^-16445) =
    // sqrt(2) * 2^-8223, whose biased exponent is 16383 - 8223 = 8160 = $1FE0 and whose
    // significand is sqrt(2)'s. A wrong parity split or a wrong pre-normalise moves the
    // exponent, so these literals -- not the model -- are what pins it down.
    for ((v, e, inexact) <- Seq(
           (MinSub,   BigInt("1FE0B504F333F9DE6484", 16), true),   // 2^-16445
           (SubTwo,   BigInt("1FE18000000000000000", 16), false),  // 2^-16444, exact
           (SubHiBit, BigInt("1FFFB504F333F9DE6484", 16), true),   // 2^-16383
           (SubMax,   BigInt("1FFFFFFFFFFFFFFFFFFF", 16), true),
           (P16382,   BigInt("5FFE8000000000000000", 16), false),  // 2^16382, exact
           (P16381,   BigInt("5FFDB504F333F9DE6484", 16), true),   // 2^16381
           (MaxFin,   BigInt("5FFEFFFFFFFFFFFFFFFF", 16), true))) {
      val r = FpRefModel.sqrt(v, 0)
      assert(r._1 == e, f"ref sqrt($v%020x) -> ${r._1}%020x, expected $e%020x")
      assert(r._4 == inexact, f"ref sqrt($v%020x) INEX2 ${r._4}, expected $inexact")
      // sqrt maps the whole floatx80 range [2^-16445, 2^16384) into [2^-8223, 2^8192), so
      // FSQRT can neither overflow nor underflow -- for ANY operand, in ANY rounding mode.
      for (rm <- 0 to 3) {
        val q = FpRefModel.sqrt(v, rm)
        assert(!q._2 && !q._3, f"sqrt($v%020x) rm=$rm must raise neither OVFL nor UNFL")
      }
    }
    assert(FpRefModel.div(One, Three, 0)._1 == ((BigInt(0x3FFD) << 64) | BigInt("AAAAAAAAAAAAAAAB", 16)),
      "1/3 RN")
    assert(FpRefModel.div(One, Three, 1)._1 == ((BigInt(0x3FFD) << 64) | BigInt("AAAAAAAAAAAAAAAA", 16)),
      "1/3 RZ")
    // Decision 10's overflow table
    val NegMax = (BigInt(1) << 79) | MaxFin
    for ((v, rm, exp) <- Seq((MaxFin, 0, PosInf), (MaxFin, 1, MaxFin), (MaxFin, 2, MaxFin),
                             (MaxFin, 3, PosInf), (NegMax, 0, NegInf), (NegMax, 1, NegMax),
                             (NegMax, 2, NegInf), (NegMax, 3, NegMax))) {
      val r = FpRefModel.add(v, v, rm)
      assert(r._1 == exp, f"ref OVFL rm=$rm -> ${r._1}%020x, expected $exp%020x")
      assert(r._2 && r._4, "overflow must be flagged overflow+inexact")
    }
    // FMOVECR ROM: every high-confidence constant, and the gated ones against the table
    assert(FpCheapPipe.cromWords(FpCheapPipe.cromIndexOf(0x00)) == BigInt("4000C90FDAA22168C235", 16))
    assert(FpCheapPipe.cromWords(FpCheapPipe.cromIndexOf(0x32)) == One)
    assert(FpCheapPipe.cromWords(FpCheapPipe.cromIndexOf(0x0F)) == PosZero)
    assert(FpCheapPipe.cromWords(FpCheapPipe.cromIndexOf(0x41)) == PosZero, "undefined -> 0.0")
    // FINT reference
    assert(FpRefModel.roundToInt(ThreePt5, 0) == Four)
    assert(FpRefModel.roundToInt(ThreePt5, 1) == Three)
    assert(FpRefModel.roundToInt(Half, 0) == PosZero, "0.5 rounds half-to-even -> 0")
  }

  // ────────────────────────────────────────────────────────────────────────────
  test("FpuCore FADD/FSUB/FMUL: directed extended-precision vectors, all 4 rounding modes",
       VerilatorTest) {
    val Onep5      = (BigInt(0x3FFF) << 64) | BigInt("C000000000000000", 16)
    val Seven      = (BigInt(0x4001) << 64) | BigInt("E000000000000000", 16)
    val Ulp        = (BigInt(0x3FC0) << 64) | (BigInt(1) << 63)
    val HalfUlp    = (BigInt(0x3FBF) << 64) | (BigInt(1) << 63)
    val OnePlusUlp = (BigInt(0x3FFF) << 64) | (BigInt(1) << 63) | BigInt(1)
    val vec: Seq[(FpOp.E, BigInt, BigInt, Int, BigInt)] = Seq(
      (FpOp.FADD, One,  One,  0, Two),                       // 1 + 1 = 2
      (FpOp.FADD, Two,  Two,  0, Four),                      // 2 + 2 = 4
      (FpOp.FSUB, Four, Two,  0, Two),                       // 4 - 2 = 2
      (FpOp.FSUB, Two,  Two,  0, PosZero),                   // exact cancellation -> +0 (RN)
      (FpOp.FSUB, Two,  Two,  2, NegZero),                   // exact cancellation -> -0 (RM)
      (FpOp.FSUB, Two,  Two,  1, PosZero),                   // ... +0 under RZ
      (FpOp.FSUB, Two,  Two,  3, PosZero),                   // ... +0 under RP
      (FpOp.FMUL, Two,  Two,  0, Four),                      // 2 * 2 = 4
      (FpOp.FMUL, ThreePt5, Two, 0, Seven),                  // 3.5 * 2 = 7
      (FpOp.FADD, One,  Half, 0, Onep5),
      (FpOp.FSUB, One,  Half, 0, Half),
      // massive cancellation: (1 + 2^-63) - 1 == 2^-63 exactly (close path, exact)
      (FpOp.FSUB, OnePlusUlp, One, 0, Ulp),
      // round-to-nearest-EVEN tie: 1 + 2^-64 -> 1.0 (LSB already even)
      (FpOp.FADD, One, HalfUlp, 0, One),
      // same tie under RP rounds up by one ULP
      (FpOp.FADD, One, HalfUlp, 3, OnePlusUlp),
      // subnormal arithmetic through the shared back-end
      (FpOp.FADD, MinSub, MinSub, 0, BigInt(2)),
      // 2^-16382 - 2^-16445 = (2^63 - 1) * 2^-16445, i.e. the largest subnormal
      (FpOp.FSUB, MinNorm, MinSub, 0, (BigInt(1) << 63) - 1))

    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)
      for ((op, d, s, rm, exp) <- vec) {
        val g = runFixed(d0, cd, op, d, s, rm)
        assert(g.value == exp, f"$op d=$d%020x s=$s%020x rm=$rm -> ${g.value}%020x, expected $exp%020x")
        assert(g.writeFp, s"$op must write an FP destination")
        val ref = op match {
          case FpOp.FADD => FpRefModel.add(d, s, rm)
          case FpOp.FSUB => FpRefModel.sub(d, s, rm)
          case _         => FpRefModel.mul(d, s, rm)
        }
        assert(g.value == ref._1, f"$op disagrees with FpRefModel: ${g.value}%020x vs ${ref._1}%020x")
        assert(g.fpcc == FpRefModel.fpcc(g.value), s"$op FPCC ${g.fpcc}")
      }
    }
  }

  test("FpuCore FDIV/FSQRT: directed extended-precision vectors", VerilatorTest) {
    val vec: Seq[(FpOp.E, BigInt, BigInt, Int, BigInt)] = Seq(
      (FpOp.FDIV,  Four, Two,  0, Two),                      // 4 / 2 = 2
      (FpOp.FDIV,  Two,  Four, 0, Half),                     // 2 / 4 = 0.5
      (FpOp.FDIV,  One,  Two,  0, Half),
      (FpOp.FDIV,  Nine, Three,0, Three),                    // 9 / 3 = 3
      (FpOp.FSQRT, 0,    Four, 0, Two),                      // sqrt(4) = 2
      (FpOp.FSQRT, 0,    Nine, 0, Three),                    // sqrt(9) = 3
      (FpOp.FSQRT, 0,    One,  0, One),
      (FpOp.FSQRT, 0,    PosZero, 0, PosZero),
      (FpOp.FSQRT, 0,    NegZero, 0, NegZero),               // -0 passes through
      // sqrt(2), inexact, correctly rounded
      (FpOp.FSQRT, 0,    Two,  0, (BigInt(0x3FFF) << 64) | BigInt("B504F333F9DE6484", 16)),
      // ── the FSQRT exponent path at both ends ──
      // Genuine DENORMALISED operands (biased exponent 0, integer bit clear). These are the
      // vectors a wrong pre-normalise or a wrong exponent parity split cannot survive: the
      // randomised sweep used to "cover" them only against a model that shared the RTL's own
      // construction. sqrt(sig * 2^-16445) for sig = 1, 2, 2^62 and 2^63-1.
      (FpOp.FSQRT, 0,    MinSub,   0, BigInt("1FE0B504F333F9DE6484", 16)),
      (FpOp.FSQRT, 0,    SubTwo,   0, BigInt("1FE18000000000000000", 16)),   // exact square
      (FpOp.FSQRT, 0,    SubHiBit, 0, BigInt("1FFFB504F333F9DE6484", 16)),
      (FpOp.FSQRT, 0,    SubMax,   0, BigInt("1FFFFFFFFFFFFFFFFFFF", 16)),
      // ... and the top of the exponent range, even and odd, plus the largest finite.
      (FpOp.FSQRT, 0,    P16382,   0, BigInt("5FFE8000000000000000", 16)),   // exact square
      (FpOp.FSQRT, 0,    P16381,   0, BigInt("5FFDB504F333F9DE6484", 16)),
      (FpOp.FSQRT, 0,    MaxFin,   0, BigInt("5FFEFFFFFFFFFFFFFFFF", 16)),
      // 1/3, inexact, correctly rounded under RN then RZ
      (FpOp.FDIV,  One,  Three, 0, (BigInt(0x3FFD) << 64) | BigInt("AAAAAAAAAAAAAAAB", 16)),
      (FpOp.FDIV,  One,  Three, 1, (BigInt(0x3FFD) << 64) | BigInt("AAAAAAAAAAAAAAAA", 16)))

    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)
      for ((op, d, s, rm, exp) <- vec) {
        val g = runIter(d0, cd, op, d, s, rm)
        assert(g.value == exp, f"$op d=$d%020x s=$s%020x -> ${g.value}%020x, expected $exp%020x")
        val ref = if (op == FpOp.FDIV) FpRefModel.div(d, s, rm) else FpRefModel.sqrt(s, rm)
        assert(g.value == ref._1, f"$op disagrees with FpRefModel: ${g.value}%020x vs ${ref._1}%020x")
        assert(g.ovfl == ref._2 && g.unfl == ref._3 && g.inex == ref._4,
          f"$op d=$d%020x s=$s%020x flags ovfl=${g.ovfl}/${ref._2} " +
          f"unfl=${g.unfl}/${ref._3} inex=${g.inex}/${ref._4}")
      }
      // FSQRT halves the binary exponent, so the whole floatx80 range [2^-16445, 2^16384)
      // maps into [2^-8223, 2^8192): a correct FSQRT can NEVER overflow or underflow, for any
      // operand in any rounding mode. That makes an "underflowing FSQRT" vector impossible to
      // write; the equivalent evidence is the smallest and largest possible RESULTS (sqrt of
      // the smallest subnormal, sqrt of the largest finite) landing on their exact literals
      // above with both flags clear -- which is exactly what a broken exponent path breaks.
      for (s <- Seq(MinSub, SubTwo, SubHiBit, SubMax, P16382, P16381, MaxFin, MinNorm); rm <- 0 to 3) {
        val g = runIter(d0, cd, FpOp.FSQRT, 0, s, rm)
        val r = FpRefModel.sqrt(s, rm)
        assert(g.value == r._1, f"FSQRT rm=$rm $s%020x -> ${g.value}%020x, ref ${r._1}%020x")
        assert(!g.ovfl && !g.unfl, f"FSQRT rm=$rm $s%020x raised ovfl=${g.ovfl} unfl=${g.unfl}")
        assert(g.inex == r._4, f"FSQRT rm=$rm $s%020x INEX2 ${g.inex}, ref ${r._4}")
      }
    }
  }

  test("FpuCore OVFL: Decision 10's 4-way rounding-mode/sign substitution table", VerilatorTest) {
    val PosMax = MaxFin
    val NegMax = (BigInt(1) << 79) | MaxFin
    //   RN -> Infinity(sign) | RZ -> largest(sign)
    //   RM -> +ovfl:largest+, -ovfl:Inf-  | RP -> +ovfl:Inf+, -ovfl:largest-
    val cases: Seq[(BigInt, Int, BigInt)] = Seq(
      (PosMax, 0, PosInf), (PosMax, 1, PosMax), (PosMax, 2, PosMax), (PosMax, 3, PosInf),
      (NegMax, 0, NegInf), (NegMax, 1, NegMax), (NegMax, 2, NegInf), (NegMax, 3, NegMax))

    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)
      for ((v, rm, exp) <- cases) {
        val g = runFixed(d0, cd, FpOp.FADD, v, v, rm)
        assert(g.value == exp,
          f"OVFL rm=$rm sign=${FpRefModel.sign(v)} -> ${g.value}%020x, expected $exp%020x")
        assert(g.ovfl,  s"OVFL flag not set for rm=$rm")
        assert(g.inex,  s"OVFL must also be inexact (rm=$rm)")
        assert(!g.unfl, s"UNFL wrongly set on an overflow (rm=$rm)")
      }
      // Same table via FMUL, to prove the substitution lives in the SHARED back-end and is
      // not duplicated (or missing) per lane.
      val big = (BigInt(0x7000) << 64) | (BigInt(1) << 63)
      for ((rm, exp) <- Seq((0, PosInf), (1, PosMax), (2, PosMax), (3, PosInf))) {
        val g = runFixed(d0, cd, FpOp.FMUL, big, big, rm)
        assert(g.value == exp, f"FMUL OVFL rm=$rm -> ${g.value}%020x, expected $exp%020x")
        assert(g.ovfl)
      }
      // ... and via FDIV, proving the iterative lane's private FpRoundPack behaves identically.
      val tiny = (BigInt(0x0002) << 64) | (BigInt(1) << 63)
      for ((rm, exp) <- Seq((0, PosInf), (1, PosMax))) {
        val g = runIter(d0, cd, FpOp.FDIV, big, tiny, rm)
        assert(g.value == exp, f"FDIV OVFL rm=$rm -> ${g.value}%020x")
        assert(g.ovfl)
      }
    }
  }

  test("FpuCore UNFL: denormalise to a nonzero subnormal, and all the way to zero", VerilatorTest) {
    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)

      // (a) smallest normal / 2 -> the largest subnormal, exact, no INEX.
      val g1 = runIter(d0, cd, FpOp.FDIV, MinNorm, Two, 0)
      assert(g1.value == (BigInt(1) << 62), f"MinNorm/2 -> ${g1.value}%020x, expected 2^62")
      assert(!g1.inex, "MinNorm/2 is exact; INEX2 must be clear")
      assert(!g1.unfl, "tininess is detected AFTER rounding and this is exact -- UNFL must be clear")

      // (b) smallest subnormal / 2 -> tie at zero. RN/RZ/RM -> +0, RP -> MinSub.
      for ((rm, exp) <- Seq((0, PosZero), (1, PosZero), (2, PosZero), (3, MinSub))) {
        val g = runIter(d0, cd, FpOp.FDIV, MinSub, Two, rm)
        assert(g.value == exp, f"MinSub/2 rm=$rm -> ${g.value}%020x, expected $exp%020x")
        assert(g.unfl, s"UNFL must be set for MinSub/2 rm=$rm")
        assert(g.inex, s"MinSub/2 is inexact for rm=$rm")
        assert(g.value == FpRefModel.div(MinSub, Two, rm)._1, s"MinSub/2 rm=$rm vs reference")
      }
      // negative sign takes the mirror arm of the same table
      val negMinSub = (BigInt(1) << 79) | MinSub
      for ((rm, exp) <- Seq((0, NegZero), (2, negMinSub), (3, NegZero))) {
        val g = runIter(d0, cd, FpOp.FDIV, negMinSub, Two, rm)
        assert(g.value == exp, f"-MinSub/2 rm=$rm -> ${g.value}%020x, expected $exp%020x")
      }

      // (c) shift the mantissa completely out: a huge quotient exponent deficit.
      val huge = (BigInt(0x7FFE) << 64) | (BigInt(1) << 63)
      for ((rm, exp) <- Seq((0, PosZero), (1, PosZero), (2, PosZero), (3, MinSub))) {
        val g = runIter(d0, cd, FpOp.FDIV, MinSub, huge, rm)
        assert(g.value == exp, f"MinSub/huge rm=$rm -> ${g.value}%020x, expected $exp%020x")
        assert(g.unfl && g.inex, s"MinSub/huge rm=$rm must set UNFL and INEX2")
      }

      // (d) an FADD that underflows through the SHARED back-end (same table, other lane).
      val g4 = runFixed(d0, cd, FpOp.FSUB, MinSub, (BigInt(1) << 79) | MinSub, 0)
      assert(g4.value == BigInt(2), f"MinSub - (-MinSub) -> ${g4.value}%020x, expected 2*MinSub")
      assert(!g4.unfl, "exact subnormal sum: UNFL must be clear (tininess after rounding)")
      assert(!g4.inex, "exact subnormal sum: INEX2 must be clear")
    }
  }

  test("FpuCore NaN propagation, SNaN detection, and infinity arithmetic", VerilatorTest) {
    val quietedS = SNan | (BigInt(3) << 62)      // propagateFloatx80NaN OR-quiets both operands
    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)

      // quiet NaN propagates unchanged, does NOT set SNAN
      val a = runFixed(d0, cd, FpOp.FADD, QNan, One, 0)
      assert(a.value == QNan, f"QNaN + 1 -> ${a.value}%020x")
      assert(!a.snan, "quiet NaN must not raise SNAN")
      assert((a.fpcc & 8) != 0, "FPCC.NaN must be set")

      // signalling NaN is quieted on the way out AND raises SNAN
      val b = runFixed(d0, cd, FpOp.FADD, SNan, One, 0)
      assert(b.value == quietedS, f"SNaN + 1 -> ${b.value}%020x, expected quieted $quietedS%020x")
      assert(b.snan, "signalling NaN operand must set FPSR.SNAN")

      // SoftFloat's a-vs-b selection: a signalling `a` with a NaN `b` yields quieted b
      val c = runFixed(d0, cd, FpOp.FADD, SNan, QNan, 0)
      assert(c.value == QNan, f"SNaN + QNaN -> ${c.value}%020x, expected the quiet operand")
      assert(c.snan)

      case class E(op: FpOp.E, d: BigInt, s: BigInt, v: BigInt, operr: Boolean)
      val edges = Seq(
        E(FpOp.FADD, PosInf, NegInf, FpRefModel.DefaultNan, true),   // inf - inf
        E(FpOp.FSUB, PosInf, PosInf, FpRefModel.DefaultNan, true),
        E(FpOp.FADD, PosInf, One,    PosInf,                false),
        E(FpOp.FADD, One,    NegInf, NegInf,                false),
        E(FpOp.FSUB, One,    PosInf, NegInf,                false),
        E(FpOp.FMUL, PosInf, PosZero,FpRefModel.DefaultNan, true),   // 0 * inf
        E(FpOp.FMUL, NegZero,PosInf, FpRefModel.DefaultNan, true),
        E(FpOp.FMUL, PosInf, Two,    PosInf,                false),
        E(FpOp.FMUL, NegInf, Two,    NegInf,                false))
      for (e <- edges) {
        val g = runFixed(d0, cd, e.op, e.d, e.s, 0)
        assert(g.value == e.v,
          f"${e.op} ${e.d}%020x,${e.s}%020x -> ${g.value}%020x, expected ${e.v}%020x")
        assert(g.operr == e.operr, s"${e.op} OPERR ${g.operr}, expected ${e.operr}")
        val ref = e.op match {
          case FpOp.FADD => FpRefModel.add(e.d, e.s, 0)._1
          case FpOp.FSUB => FpRefModel.sub(e.d, e.s, 0)._1
          case _         => FpRefModel.mul(e.d, e.s, 0)._1
        }
        assert(g.value == ref, s"${e.op} disagrees with FpRefModel on an infinity edge")
      }

      val iterEdges = Seq(
        (FpOp.FDIV,  PosInf, PosInf,  FpRefModel.DefaultNan, true,  false),  // inf/inf
        (FpOp.FDIV,  PosZero,PosZero, FpRefModel.DefaultNan, true,  false),  // 0/0
        (FpOp.FDIV,  One,    PosZero, PosInf,                false, true ),  // 1/0 -> DZ
        (FpOp.FDIV,  One,    NegZero, NegInf,                false, true ),
        (FpOp.FDIV,  NegInf, Two,     NegInf,                false, false),
        (FpOp.FDIV,  Two,    PosInf,  PosZero,               false, false),
        (FpOp.FSQRT, BigInt(0), NegInf,  FpRefModel.DefaultNan, true,  false),
        (FpOp.FSQRT, BigInt(0), PosInf,  PosInf,                false, false),
        (FpOp.FSQRT, BigInt(0), (BigInt(1) << 79) | Four, FpRefModel.DefaultNan, true, false))
      for ((op, d, s, v, oe, dz) <- iterEdges) {
        val g = runIter(d0, cd, op, d, s, 0)
        assert(g.value == v, f"$op ${d}%020x,${s}%020x -> ${g.value}%020x, expected $v%020x")
        assert(g.operr == oe, s"$op OPERR ${g.operr}, expected $oe")
        assert(g.dz == dz, s"$op DZ ${g.dz}, expected $dz")
      }
      // an SNaN through the iterative lane still raises SNAN
      assert(runIter(d0, cd, FpOp.FDIV, SNan, Two, 0).snan, "FDIV of an SNaN must set SNAN")
      assert(runIter(d0, cd, FpOp.FSQRT, BigInt(0), SNan, 0).snan, "FSQRT of an SNaN must set SNAN")
    }
  }

  test("FpuCore FTST vs FCMP on infinity: FTST sets FPSR.I, FCMP does not", VerilatorTest) {
    // Internal FPCC layout, 2026-08-09 spec section 8: bit0=N bit1=Z bit2=I bit3=NaN.
    val N = 1; val Z = 2; val I = 4; val NAN = 8
    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)

      // ---- FTST: direct source classification (Musashi m68kfpu.c:1547-1554) ----
      val ftst = Seq(
        (PosInf,  I),          // <-- the whole point: I set
        (NegInf,  I | N),
        (PosZero, Z),
        (NegZero, Z | N),
        (One,     0),
        ((BigInt(1) << 79) | One, N),
        (QNan,    NAN),
        (SNan,    NAN))
      for ((v, exp) <- ftst) {
        val g = runFixed(d0, cd, FpOp.FTST, 0, v, 0)
        assert(g.fpcc == exp, f"FTST ${v}%020x -> FPCC ${g.fpcc}, expected $exp")
        assert(!g.writeFp, "FTST must not write an FP destination")
        assert(g.fpcc == FpRefModel.fpcc(v), "FTST must agree with the classifier reference")
      }
      assert(runFixed(d0, cd, FpOp.FTST, 0, SNan, 0).snan, "FTST of an SNaN must set FPSR.SNAN")

      // ---- FCMP: the explicit infinity table leaves I clear (m68kfpu.c:1525-1545) ----
      val fcmp = Seq(
        (PosInf, PosInf, Z),        // equal
        (NegInf, NegInf, Z | N),
        (PosInf, NegInf, 0),        // dst greater
        (NegInf, PosInf, N),        // dst less
        (PosInf, One,    0),        // +inf > finite
        (One,    PosInf, N),        // finite < +inf
        (NegInf, One,    N),
        (One,    NegInf, 0))
      for ((d, s, exp) <- fcmp) {
        val g = runFixed(d0, cd, FpOp.FCMP, d, s, 0)
        assert(g.fpcc == exp, f"FCMP ${d}%020x,${s}%020x -> FPCC ${g.fpcc}, expected $exp")
        assert((g.fpcc & I) == 0, "FCMP's infinity table must leave FPSR.I CLEAR")
        assert(!g.writeFp, "FCMP must not write an FP destination")
        assert(g.fpcc == FpRefModel.fcmp(d, s, 0), "FCMP must agree with the reference")
        assert(!g.operr, "Divergence Register D6: FCMP against an infinity raises no OPERR")
      }

      // THE MUTATION KILLER. A decode-time rewrite of FTST into `FCMP src, #0` would make
      // these two agree. They must not: FTST(+inf) sets I; FCMP(+inf, +0) does not.
      val tst = runFixed(d0, cd, FpOp.FTST, 0,      PosInf, 0)
      val cmp = runFixed(d0, cd, FpOp.FCMP, PosInf, PosZero, 0)
      assert((tst.fpcc & I) != 0 && (cmp.fpcc & I) == 0,
        s"FTST/FCMP infinity divergence lost: FTST=${tst.fpcc} FCMP=${cmp.fpcc} -- " +
        "FTST has been reduced to a compare-against-zero")

      // FCMP's non-special path still goes through the real subtract datapath.
      assert(runFixed(d0, cd, FpOp.FCMP, Two, One, 0).fpcc == 0, "2 vs 1 -> greater")
      assert(runFixed(d0, cd, FpOp.FCMP, One, Two, 0).fpcc == N, "1 vs 2 -> less")
      assert(runFixed(d0, cd, FpOp.FCMP, Two, Two, 0).fpcc == Z, "2 vs 2 -> equal")
      assert((runFixed(d0, cd, FpOp.FCMP, QNan, One, 0).fpcc & NAN) != 0, "NaN compare")
    }
  }

  test("FpuCore FABS/FNEG/FMOVE and FINT/FINTRZ", VerilatorTest) {
    val NegOne = (BigInt(1) << 79) | One
    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)

      for (v <- Seq(One, NegOne, PosZero, NegZero, PosInf, NegInf, QNan, MinSub)) {
        val abs = runFixed(d0, cd, FpOp.FABS, 0, v, 0)
        assert(abs.value == (v & ((BigInt(1) << 79) - 1)), f"FABS ${v}%020x")
        val neg = runFixed(d0, cd, FpOp.FNEG, 0, v, 0)
        assert(neg.value == (v ^ (BigInt(1) << 79)), f"FNEG ${v}%020x")
        val mov = runFixed(d0, cd, FpOp.FMOVE, 0, v, 0)
        assert(mov.value == v, f"FMOVE ${v}%020x")
        for (g <- Seq(abs, neg, mov)) {
          assert(g.writeFp, "FABS/FNEG/FMOVE write an FP destination")
          assert(g.fpcc == FpRefModel.fpcc(g.value), "FPCC must be classified from the RESULT")
        }
      }

      // FINT uses the FPCR mode; FINTRZ always truncates toward zero regardless of it.
      val nFour = (BigInt(1) << 79) | Four; val nThree = (BigInt(1) << 79) | Three
      val fint = Seq(
        (ThreePt5,    0, Four),  (ThreePt5,    1, Three), (ThreePt5,    2, Three), (ThreePt5, 3, Four),
        (NegThreePt5, 0, nFour), (NegThreePt5, 1, nThree),(NegThreePt5, 2, nFour), (NegThreePt5, 3, nThree),
        (Half,  0, PosZero),                                                // 0.5 -> 0 (half-to-EVEN)
        ((BigInt(0x3FFE) << 64) | BigInt("C000000000000000", 16), 0, One),  // 0.75 -> 1
        (One,   0, One), (PosZero, 0, PosZero), (NegZero, 0, NegZero),
        (PosInf,0, PosInf), (QNan, 0, QNan))
      for ((v, rm, exp) <- fint) {
        val g = runFixed(d0, cd, FpOp.FINT, 0, v, rm)
        assert(g.value == exp, f"FINT ${v}%020x rm=$rm -> ${g.value}%020x, expected $exp%020x")
        assert(g.value == FpRefModel.roundToInt(v, rm), "FINT must match floatx80_round_to_int")
        assert(g.inex == FpRefModel.roundToIntInexact(v, rm),
          f"FINT ${v}%020x rm=$rm INEX2 ${g.inex}")
      }
      // FINTRZ ignores FPCR in ALL four modes
      for (rm <- 0 to 3) {
        assert(runFixed(d0, cd, FpOp.FINTRZ, 0, ThreePt5,  rm).value == Three,
               s"FINTRZ(3.5) must truncate regardless of rm=$rm")
        assert(runFixed(d0, cd, FpOp.FINTRZ, 0, NegThreePt5, rm).value == nThree,
               s"FINTRZ(-3.5) must truncate regardless of rm=$rm")
      }
      // The result is EXTENDED-format, not a narrowing convert: a value far beyond int32
      // range passes through unchanged (Divergence Register D1 -- Musashi gets this wrong).
      val big = (BigInt(0x4100) << 64) | BigInt("ABCDEF0123456789", 16)
      assert(runFixed(d0, cd, FpOp.FINT,   0, big, 0).value == big, "FINT must not narrow")
      assert(runFixed(d0, cd, FpOp.FINTRZ, 0, big, 0).value == big, "FINTRZ must not narrow")
      // ... and a value inside the integral band but with a real fractional part rounds.
      val v2p5 = (BigInt(0x4000) << 64) | BigInt("A000000000000000", 16)      // 2.5
      assert(runFixed(d0, cd, FpOp.FINT, 0, v2p5, 0).value == Two, "2.5 -> 2 (half-to-even)")
    }
  }

  test("FpuCore FMOVECR constant ROM read-back", VerilatorTest) {
    // Only the HIGH-CONFIDENCE entries are asserted as literals here: these are
    // cross-confirmed by Musashi's table AND an independent exact correctly-rounded
    // computation. Offsets $0B and $38..$3F are read back and compared to
    // FpCheapPipe.cromWords instead, so the ROM is proven to hold what the source says
    // while the VALUES stay owned by the design-spec Divergence Register (D2/D3).
    val certain = Seq(
      (0x00, BigInt("4000C90FDAA22168C235", 16)),   // pi
      (0x0C, BigInt("4000ADF85458A2BB4A9B", 16)),   // e
      (0x0D, BigInt("3FFFB8AA3B295C17F0BC", 16)),   // log2(e)
      (0x0E, BigInt("3FFDDE5BD8A937287195", 16)),   // log10(e)
      (0x0F, BigInt(0)),                            // 0.0
      (0x30, BigInt("3FFEB17217F7D1CF79AC", 16)),   // ln(2)
      (0x31, BigInt("4000935D8DDDAAA8AC17", 16)),   // ln(10)
      (0x32, BigInt("3FFF8000000000000000", 16)),   // 10^0 = 1.0
      (0x33, BigInt("4002A000000000000000", 16)),   // 10^1
      (0x34, BigInt("4005C800000000000000", 16)),   // 10^2
      (0x35, BigInt("400C9C40000000000000", 16)),   // 10^4
      (0x36, BigInt("4019BEBC200000000000", 16)),   // 10^8
      (0x37, BigInt("40348E1BC9BF04000000", 16)))   // 10^16
    val gated = Seq(0x0B) ++ (0x38 to 0x3F)
    val undefined = Seq(0x01, 0x0A, 0x10, 0x2F, 0x40, 0x7F)

    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)
      for ((off, exp) <- certain) {
        val g = runFixed(d0, cd, FpOp.FMOVECR, 0, 0, 0, off)
        assert(g.value == exp, f"FMOVECR #$$$off%02x -> ${g.value}%020x, expected $exp%020x")
        assert(g.writeFp, "FMOVECR writes an FP destination")
        assert(g.fpcc == FpRefModel.fpcc(exp), s"FMOVECR #$off FPCC")
        assert(!g.snan, "FMOVECR never raises SNAN")
      }
      for (off <- gated) {
        val exp = FpCheapPipe.cromWords(FpCheapPipe.cromIndexOf(off))
        val g = runFixed(d0, cd, FpOp.FMOVECR, 0, 0, 0, off)
        assert(g.value == exp,
          f"FMOVECR #$$$off%02x -> ${g.value}%020x, but cromWords says $exp%020x")
      }
      for (off <- undefined) {
        val g = runFixed(d0, cd, FpOp.FMOVECR, 0, 0, 0, off)
        assert(g.value == 0, f"undefined FMOVECR offset $$$off%02x must read 0.0, got ${g.value}%020x")
      }
    }
  }

  test("FpuCore accepts eight consecutive fixed ops and completes them in issue order",
       VerilatorTest) {
    val vectors = Seq(
      (FpOp.FADD, One,  One,  0), (FpOp.FMUL, Two,  Two,  0),
      (FpOp.FABS, BigInt(0), (BigInt(1) << 79) | One, 0),
      (FpOp.FSUB, Four, Two,  0), (FpOp.FNEG, BigInt(0), One, 0),
      (FpOp.FMOVE,BigInt(0), Two, 0), (FpOp.FADD, Two, Two, 0),
      (FpOp.FMUL, ThreePt5, Two, 0))
    val expected = vectors.map { case (op, d, s, rm) => op match {
      case FpOp.FADD  => FpRefModel.add(d, s, rm)._1
      case FpOp.FSUB  => FpRefModel.sub(d, s, rm)._1
      case FpOp.FMUL  => FpRefModel.mul(d, s, rm)._1
      case FpOp.FABS  => s & ((BigInt(1) << 79) - 1)
      case FpOp.FNEG  => s ^ (BigInt(1) << 79)
      case _          => s } }

    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)
      var accepted = 0; var completed = 0; var firstDone = -1; var maxResident = 0
      val total = vectors.length + FpuCore.FixedLatency + 2
      for (cycle <- 0 until total) {
        if (cycle < vectors.length) {
          val (op, d, s, rm) = vectors(cycle)
          d0.io.start #= true; d0.io.op #= op
          d0.io.dst #= d; d0.io.src #= s; d0.io.rmode #= rm; d0.io.cromSel #= 0
        } else d0.io.start #= false
        cd.waitSampling()
        if (cycle < vectors.length) accepted += 1
        maxResident = math.max(maxResident, accepted - completed)
        val shouldDone = cycle >= FpuCore.FixedLatency &&
                         cycle <  FpuCore.FixedLatency + vectors.length
        assert(d0.io.doneFixed.toBoolean == shouldDone,
          s"cycle $cycle doneFixed=${d0.io.doneFixed.toBoolean}, expected $shouldDone")
        if (d0.io.doneFixed.toBoolean) {
          if (firstDone < 0) firstDone = cycle
          val got = d0.io.resFixed.value.toBigInt
          assert(got == expected(completed),
            f"dense result #$completed -> $got%020x, expected ${expected(completed)}%020x " +
            "(results must leave in ISSUE order)")
          completed += 1
        }
      }
      assert(completed == vectors.length, s"completed $completed/${vectors.length}")
      assert(firstDone == FpuCore.FixedLatency,
        s"first doneFixed at $firstDone, expected ${FpuCore.FixedLatency}")
      assert(maxResident >= 8, s"only $maxResident ops were simultaneously in flight")
      assert(!d0.io.doneFixed.toBoolean, "spurious doneFixed after the burst")
    }
  }

  test("FpuCore holds the iterative result across a colliding fixed completion", VerilatorTest) {
    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)
      // Launch FDIV, then keep the fixed lane saturated while it iterates.
      d0.io.start #= true; d0.io.op #= FpOp.FDIV
      d0.io.dst #= Four; d0.io.src #= Two; d0.io.rmode #= 0
      cd.waitSampling(); d0.io.start #= false
      // busyIter is combinational off `state`, a register updated on this very edge, so it
      // needs one delta to settle before it can be read (unlike doneFixed/doneIter, which
      // are register outputs).
      sleep(1)
      assert(d0.io.busyIter.toBoolean, "busyIter must assert on the accept cycle")
      // A second FDIV must be refused while the single context is occupied.
      d0.io.op #= FpOp.FDIV
      sleep(1)
      assert(!d0.io.ready.toBoolean, "ready must be low for FDIV while busyIter")
      d0.io.op #= FpOp.FADD
      sleep(1)
      assert(d0.io.ready.toBoolean, "a busy FDIV must NOT block the fixed lane")

      var fixedDone = 0
      var sawCollision = false
      for (_ <- 0 until FpDivSqrtCore.WorstCaseLatency + 20) {
        d0.io.start #= true; d0.io.op #= FpOp.FADD
        d0.io.dst #= One; d0.io.src #= One
        cd.waitSampling()
        if (d0.io.doneFixed.toBoolean) {
          fixedDone += 1
          assert(d0.io.resFixed.value.toBigInt == Two, "fixed lane corrupted by the iterative lane")
          if (d0.io.doneIter.toBoolean) sawCollision = true
        }
      }
      d0.io.start #= false; cd.waitSampling()
      assert(fixedDone > 20, s"fixed lane only completed $fixedDone ops during an FDIV")
      assert(d0.io.doneIter.toBoolean, "doneIter must still be held -- it was never acked")
      assert(sawCollision, "the test never actually collided a fixed completion with doneIter")
      assert(d0.io.resIter.value.toBigInt == Two, "the held FDIV result was corrupted")
      d0.io.iterAck #= true; cd.waitSampling(); d0.io.iterAck #= false; cd.waitSampling()
      assert(!d0.io.doneIter.toBoolean && !d0.io.busyIter.toBoolean, "iterAck did not release")
    }
  }

  def runSweep(seed: Long): Unit = {
    dut.doSim(s"sweep_$seed") { d0 =>
      val cd = d0.clockDomain; init(d0, cd)
      val rnd = new Random(seed)
      val specials = Seq(PosZero, NegZero, PosInf, NegInf, QNan, SNan, One, Two,
                         Half, MaxFin, MinNorm, MinSub, ThreePt5)
      def operand(): BigInt = rnd.nextInt(5) match {
        case 0 => specials(rnd.nextInt(specials.length))
        case 1 => // random normal in a mid range (no over/underflow) -- exercises the datapath
          (BigInt(rnd.nextInt(2)) << 79) |
          (BigInt(0x3000 + rnd.nextInt(0x3000)) << 64) |
          (BigInt(1) << 63) | BigInt(63, rnd)
        case 2 => // random subnormal
          (BigInt(rnd.nextInt(2)) << 79) | BigInt(63, rnd)
        case 3 => // near the overflow/underflow boundaries
          (BigInt(rnd.nextInt(2)) << 79) |
          (BigInt(if (rnd.nextBoolean()) 0x7FF0 + rnd.nextInt(15) else 1 + rnd.nextInt(15)) << 64) |
          (BigInt(1) << 63) | BigInt(63, rnd)
        case _ => BigInt(80, rnd)   // anything at all, including unnormals
      }
      // Unnormals are out of scope for the oracle (VERIFY-4); pseudo-denormals are
      // Divergence Register D5. Both are covered by directed tests / classification outputs.
      def usable(v: BigInt) = !Fp80RefHelpers.isUnnormal(v) && !Fp80RefHelpers.isPseudoDenorm(v)

      var fixedChecked = 0
      for (_ <- 0 until 500) {
        val rm = rnd.nextInt(4)
        val d = operand(); val s = operand()
        val op = Seq(FpOp.FADD, FpOp.FSUB, FpOp.FMUL)(rnd.nextInt(3))
        if (usable(d) && usable(s)) {
          val g = runFixed(d0, cd, op, d, s, rm)
          val ref = op match {
            case FpOp.FADD => FpRefModel.add(d, s, rm)
            case FpOp.FSUB => FpRefModel.sub(d, s, rm)
            case _         => FpRefModel.mul(d, s, rm) }
          assert(g.value == ref._1,
            f"$op rm=$rm d=$d%020x s=$s%020x -> ${g.value}%020x, ref ${ref._1}%020x")
          assert(g.ovfl == ref._2 && g.unfl == ref._3,
            f"$op rm=$rm d=$d%020x s=$s%020x flags ovfl=${g.ovfl}/${ref._2} unfl=${g.unfl}/${ref._3}")
          assert(g.inex == ref._4,
            f"$op rm=$rm d=$d%020x s=$s%020x inex=${g.inex}/${ref._4}")
          assert(g.fpcc == FpRefModel.fpcc(g.value), s"$op FPCC")
          fixedChecked += 1
        }
      }
      assert(fixedChecked > 250, s"sweep only exercised $fixedChecked fixed ops")

      var iterChecked = 0
      for (_ <- 0 until 100) {
        val rm = rnd.nextInt(4)
        val d = operand(); val s = operand()
        if (usable(d) && usable(s)) {
          val gd = runIter(d0, cd, FpOp.FDIV, d, s, rm)
          val rd = FpRefModel.div(d, s, rm)
          assert(gd.value == rd._1,
            f"FDIV rm=$rm $d%020x/$s%020x -> ${gd.value}%020x, ref ${rd._1}%020x")
          assert(gd.ovfl == rd._2 && gd.unfl == rd._3 && gd.inex == rd._4,
            f"FDIV rm=$rm $d%020x/$s%020x flags ovfl=${gd.ovfl}/${rd._2} " +
            f"unfl=${gd.unfl}/${rd._3} inex=${gd.inex}/${rd._4}")
          val gs = runIter(d0, cd, FpOp.FSQRT, 0, s, rm)
          val rs = FpRefModel.sqrt(s, rm)
          assert(gs.value == rs._1,
            f"FSQRT rm=$rm $s%020x -> ${gs.value}%020x, ref ${rs._1}%020x")
          assert(gs.ovfl == rs._2 && gs.unfl == rs._3 && gs.inex == rs._4,
            f"FSQRT rm=$rm $s%020x flags ovfl=${gs.ovfl}/${rs._2} " +
            f"unfl=${gs.unfl}/${rs._3} inex=${gs.inex}/${rs._4}")
          iterChecked += 1
        }
      }
      assert(iterChecked > 45, s"sweep only exercised $iterChecked iterative ops")

      // The cheap lane's FINT/FINTRZ carries real logic (the lastBit/roundBits mask, the
      // rounding add, the mask-to-zero carry fix-up) that the directed vectors only sample at
      // a handful of exponents. Sweep it across the whole range against
      // floatx80_round_to_int, together with FABS/FNEG/FMOVE/FTST/FCMP.
      var cheapChecked = 0
      for (_ <- 0 until 200) {
        val rm = rnd.nextInt(4)
        val v  = operand()
        if (usable(v)) {
          val gi = runFixed(d0, cd, FpOp.FINT, 0, v, rm)
          assert(gi.value == FpRefModel.roundToInt(v, rm),
            f"FINT rm=$rm $v%020x -> ${gi.value}%020x, ref ${FpRefModel.roundToInt(v, rm)}%020x")
          assert(gi.inex == FpRefModel.roundToIntInexact(v, rm), f"FINT rm=$rm $v%020x INEX2")
          val gz = runFixed(d0, cd, FpOp.FINTRZ, 0, v, rm)
          assert(gz.value == FpRefModel.roundToInt(v, 1),
            f"FINTRZ rm=$rm $v%020x -> ${gz.value}%020x (must ignore FPCR)")
          assert(runFixed(d0, cd, FpOp.FABS,  0, v, rm).value == (v & ((BigInt(1) << 79) - 1)))
          assert(runFixed(d0, cd, FpOp.FNEG,  0, v, rm).value == (v ^ (BigInt(1) << 79)))
          val gm = runFixed(d0, cd, FpOp.FMOVE, 0, v, rm)
          assert(gm.value == v && gm.fpcc == FpRefModel.fpcc(v), f"FMOVE $v%020x")
          val gt = runFixed(d0, cd, FpOp.FTST, 0, v, rm)
          assert(gt.fpcc == FpRefModel.fpcc(v) && !gt.writeFp, f"FTST $v%020x")
          cheapChecked += 1
        }
        val a = operand(); val b = operand()
        if (usable(a) && usable(b)) {
          val gc = runFixed(d0, cd, FpOp.FCMP, a, b, rm)
          assert(gc.fpcc == FpRefModel.fcmp(a, b, rm),
            f"FCMP rm=$rm $a%020x,$b%020x -> ${gc.fpcc}, ref ${FpRefModel.fcmp(a, b, rm)}")
          assert(!gc.writeFp, "FCMP must not write an FP destination")
        }
      }
      assert(cheapChecked > 100, s"sweep only exercised $cheapChecked cheap ops")
    }
  }

  test("FpuCore randomised sweep vs FpRefModel (seed A)", VerilatorTest) { runSweep(0xF9A11EL) }
  test("FpuCore randomised sweep vs FpRefModel (seed B)", VerilatorTest) { runSweep(0x5C0FE2L) }
  test("FpuCore randomised sweep vs FpRefModel (seed C)", VerilatorTest) { runSweep(0x1DEAD5L) }
  test("FpuCore randomised sweep vs FpRefModel (seed D)", VerilatorTest) { runSweep(0x68040FL) }

  test("FpuCore classifies unnormal and denormal operands combinationally", VerilatorTest) {
    val unnormal = (BigInt(0x4000) << 64) | (BigInt(1) << 62)   // exp != 0, integer bit clear
    val denorm   = BigInt(1) << 40
    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)
      d0.io.src #= unnormal; d0.io.dst #= denorm; cd.waitSampling()
      assert(d0.io.srcUnnormal.toBoolean, "unnormal source not classified")
      assert(!d0.io.dstUnnormal.toBoolean, "a subnormal is not an unnormal")
      assert(d0.io.dstDenorm.toBoolean, "denormal destination not classified")
      assert(!d0.io.srcDenorm.toBoolean)
      d0.io.src #= One; d0.io.dst #= One; cd.waitSampling()
      assert(!d0.io.srcUnnormal.toBoolean && !d0.io.dstDenorm.toBoolean)
    }
  }

  test("FpuCore iterative-lane latency: worst case and special-case short circuit",
       VerilatorTest) {
    dut.doSim { d0 =>
      val cd = d0.clockDomain; init(d0, cd)
      def measure(op: FpOp.E, d: BigInt, s: BigInt): Int = {
        d0.io.start #= true; d0.io.op #= op
        d0.io.dst #= d; d0.io.src #= s; d0.io.rmode #= 0
        cd.waitSampling(); d0.io.start #= false
        var n = 0
        while (!d0.io.doneIter.toBoolean && n < 200) { cd.waitSampling(); n += 1 }
        assert(d0.io.doneIter.toBoolean, s"$op never completed")
        d0.io.iterAck #= true; cd.waitSampling(); d0.io.iterAck #= false; cd.waitSampling()
        n
      }
      val full = measure(FpOp.FDIV, One, Three)
      assert(full == FpDivSqrtCore.WorstCaseLatency,
        s"FDIV full path took $full cycles, declared ${FpDivSqrtCore.WorstCaseLatency}")
      assert(measure(FpOp.FSQRT, 0, Two) == FpDivSqrtCore.WorstCaseLatency,
        "FSQRT must take the same worst-case latency as FDIV")
      val special = measure(FpOp.FDIV, One, PosZero)
      assert(special == FpDivSqrtCore.SpecialLatency,
        s"divide-by-zero short circuit took $special cycles, declared ${FpDivSqrtCore.SpecialLatency}")
    }
  }
}
