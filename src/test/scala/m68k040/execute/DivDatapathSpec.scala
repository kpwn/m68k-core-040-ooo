package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

/** Standalone validation of the unsigned radix-2 (restoring) DivCore vs a Scala
  * reference over random + edge operands (Task 3). The core divides a 64-bit
  * unsigned dividend by a 32-bit unsigned divisor -> full 64-bit quotient + 32-bit
  * remainder; divByZero on divisor==0. Overflow/sign are decided by the EU, not here. */
class DivDatapathSpec extends AnyFunSuite {

  /** Drive one unsigned divide; return (quotient64, remainder32, divByZero). */
  def runDiv(dut: DivCore, cd: ClockDomain, dividend: BigInt, divisor: BigInt): (BigInt, BigInt, Boolean) = {
    dut.io.start #= true
    dut.io.dividend #= dividend & ((BigInt(1) << 64) - 1)
    dut.io.divisor  #= divisor  & ((BigInt(1) << 32) - 1)
    cd.waitSampling()
    dut.io.start #= false
    // wait for done (or divByzero immediate)
    var guard = 0
    while (!dut.io.done.toBoolean && guard < 200) { cd.waitSampling(); guard += 1 }
    assert(dut.io.done.toBoolean, s"DivCore never asserted done for $dividend / $divisor")
    val q  = dut.io.quotient.toBigInt
    val r  = dut.io.remainder.toBigInt
    val dz = dut.io.divByZero.toBoolean
    cd.waitSampling(2)
    (q, r, dz)
  }

  def runSuite(seed: Long): Unit = {
    M68kSim().withVerilator.compile(new DivCore).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.io.start #= false; dut.io.dividend #= 0; dut.io.divisor #= 0
      cd.waitSampling(5)

      val rnd = new Random(seed)
      val M32 = (BigInt(1) << 32) - 1
      val M64 = (BigInt(1) << 64) - 1

      // Edge operands.
      val edges: Seq[(BigInt, BigInt)] = Seq(
        (BigInt(0), BigInt(1)),
        (BigInt(1), BigInt(1)),
        (M64, BigInt(1)),                 // max dividend / 1 -> q=max
        (M64, M32),                       // max / max divisor
        (M32, BigInt(1)),
        (BigInt(0xFFFF), BigInt(0x10)),
        (BigInt(100), BigInt(7)),
        (BigInt(0x1_0000_0000L), BigInt(2)),  // 2^32 / 2
        (M64, BigInt(2)),
        (BigInt(0xDEADBEEFL), BigInt(0xCAFEL)),
        (BigInt(5), BigInt(0))            // divide by zero
      )

      def check(dividend: BigInt, divisor: BigInt): Unit = {
        val (q, r, dz) = runDiv(dut, cd, dividend, divisor)
        if (divisor == 0) {
          assert(dz, s"expected divByZero for $dividend / 0")
        } else {
          val refQ = dividend / divisor
          val refR = dividend % divisor
          assert(!dz, s"unexpected divByZero for $dividend / $divisor")
          assert(q == refQ, f"quotient mismatch: $dividend%x / $divisor%x = $q%x (ref $refQ%x)")
          assert(r == refR, f"remainder mismatch: $dividend%x %% $divisor%x = $r%x (ref $refR%x)")
        }
      }

      edges.foreach { case (a, b) => check(a, b) }

      for (_ <- 0 until 400) {
        val dividend = BigInt(64, rnd) & M64
        // bias divisors toward small + full-32-bit values
        val divisor = rnd.nextInt(4) match {
          case 0 => BigInt(1 + rnd.nextInt(0xFFFF))
          case 1 => BigInt(32, rnd) & M32
          case 2 => BigInt(16, rnd) & 0xFFFF
          case _ => BigInt(1)
        }
        check(dividend, if (divisor == 0) BigInt(1) else divisor)
      }
    }
  }

  test("DivCore unsigned: random + edge operands vs Scala reference (seed A)", VerilatorTest) {
    runSuite(0xD112345L)
  }
  test("DivCore unsigned: random + edge operands vs Scala reference (seed B)", VerilatorTest) {
    runSuite(0x9E3779B9L)
  }

  // ── DivUnit (signed/unsigned + overflow + sign fix-up) vs a 68k Scala reference ──

  sealed trait Form { def qw: Int }
  case object FW   extends Form { def qw = 16 }   // 32/16
  case object FL32 extends Form { def qw = 32 }   // 32/32
  case object FL64 extends Form { def qw = 32 }   // 64/32

  val P32 = BigInt(1) << 32
  val P16 = BigInt(1) << 16
  val P64 = BigInt(1) << 64

  /** 68k reference result: (quotient, remainder, overflow, divByZero) at the dest
    * width. `dividendArch` is the architectural dividend (32-bit for W/L32, 64-bit
    * for L64); `divisorArch` is 16-bit (W) or 32-bit (L*). Signed uses truncate-
    * toward-zero; the remainder takes the DIVIDEND's sign. */
  def ref(form: Form, signed: Boolean, dividendArch: BigInt, divisorArch: BigInt)
      : (BigInt, BigInt, Boolean, Boolean) = {
    val qw = form.qw
    val dw = if (form == FL64) 64 else 32
    val vw = if (form == FW) 16 else 32
    if (divisorArch == 0) return (BigInt(0), BigInt(0), false, true)
    val (dividend, divisor) =
      if (!signed) (dividendArch, divisorArch)
      else {
        def sx(v: BigInt, w: Int) = { val m = BigInt(1) << (w - 1); if (v >= m) v - (BigInt(1) << w) else v }
        (sx(dividendArch, dw), sx(divisorArch, vw))
      }
    // truncate toward zero
    val q = dividend / divisor                 // Scala BigInt / truncates toward zero
    val r = dividend - q * divisor             // remainder; sign of dividend
    val qmask = (BigInt(1) << qw) - 1
    val rmask = (BigInt(1) << qw) - 1
    val overflow =
      if (!signed) q >= (BigInt(1) << qw)
      else {
        val maxPos = (BigInt(1) << (qw - 1)) - 1
        val minNeg = -(BigInt(1) << (qw - 1))
        q > maxPos || q < minNeg
      }
    ((q & qmask), (r & rmask), overflow, false)
  }

  /** Drive DivUnit with architectural operands; the EU-level extension is mirrored
    * here (sign/zero-extend the dividend to 64, divisor to 32). */
  def runUnit(dut: DivUnit, cd: ClockDomain, form: Form, signed: Boolean,
              dividendArch: BigInt, divisorArch: BigInt): (BigInt, BigInt, Boolean, Boolean) = {
    val dw = if (form == FL64) 64 else 32
    val vw = if (form == FW) 16 else 32
    def ext(v: BigInt, w: Int, toW: Int): BigInt = {
      if (!signed) v & ((BigInt(1) << w) - 1)
      else { val m = BigInt(1) << (w - 1); val s = if ((v & ((BigInt(1) << w) - 1)) >= m) v | ~((BigInt(1) << w) - 1) else v; s & ((BigInt(1) << toW) - 1) }
    }
    dut.io.start    #= true
    dut.io.dividend #= ext(dividendArch, dw, 64)
    dut.io.divisor  #= ext(divisorArch, vw, 32)
    dut.io.signed   #= signed
    dut.io.form     #= (form match { case FW => DivForm.W; case FL32 => DivForm.L32; case FL64 => DivForm.L64 })
    cd.waitSampling()
    dut.io.start #= false
    var guard = 0
    while (!dut.io.done.toBoolean && guard < 200) { cd.waitSampling(); guard += 1 }
    assert(dut.io.done.toBoolean, s"DivUnit never done: $form signed=$signed $dividendArch / $divisorArch")
    val q = dut.io.quotient.toBigInt
    val r = dut.io.remainder.toBigInt
    val ov = dut.io.overflow.toBoolean
    val dz = dut.io.divByZero.toBoolean
    cd.waitSampling(2)
    (q, r, ov, dz)
  }

  def runUnitSuite(seed: Long): Unit = {
    M68kSim().withVerilator.compile(new DivUnit).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.io.start #= false; dut.io.dividend #= 0; dut.io.divisor #= 0
      dut.io.signed #= false; dut.io.form #= DivForm.W
      cd.waitSampling(5)
      val rnd = new Random(seed)

      def check(form: Form, signed: Boolean, dividend: BigInt, divisor: BigInt): Unit = {
        val (q, r, ov, dz) = runUnit(dut, cd, form, signed, dividend, divisor)
        val (rq, rr, rov, rdz) = ref(form, signed, dividend, divisor)
        assert(dz == rdz, s"$form signed=$signed $dividend/$divisor: dz=$dz ref=$rdz")
        if (!rdz) {
          assert(ov == rov, f"$form signed=$signed div=$dividend%x dvr=$divisor%x: ov=$ov ref=$rov (q=$q%x r=$r%x)")
          if (!rov) {
            // result only defined when no overflow / no div0
            val qmask = if (form == FW) (P16 - 1) else (P32 - 1)
            assert((q & qmask) == rq, f"$form s=$signed $dividend%x/$divisor%x: q=$q%x ref=$rq%x")
            assert((r & qmask) == rr, f"$form s=$signed $dividend%x/$divisor%x: r=$r%x ref=$rr%x")
          }
        }
      }

      // Directed edges (incl. the signed INT_MIN/-1 overflow).
      check(FW, false, BigInt(100), BigInt(7))
      check(FW, false, BigInt(0xFFFFFFFFL), BigInt(1))   // overflow (q > 16 bits)
      check(FW, false, BigInt(0x10000), BigInt(2))       // q=0x8000 fits unsigned 16
      check(FW, true,  BigInt(-100) & (P32 - 1), BigInt(7))
      check(FW, true,  BigInt(100), BigInt(-7) & (P16 - 1))
      check(FW, true,  BigInt(-100) & (P32 - 1), BigInt(-7) & (P16 - 1))
      check(FW, true,  BigInt(0x80000000L), BigInt(1))   // -2^31 / 1 -> overflow 16
      check(FL32, false, BigInt(0xFFFFFFFFL), BigInt(1))  // no overflow (fits 32)
      check(FL32, true,  BigInt(0x80000000L), BigInt(0xFFFFFFFFL)) // INT_MIN / -1 -> overflow
      check(FL32, true,  BigInt(-1) & (P32 - 1), BigInt(1))
      check(FL64, false, P64 - 1, BigInt(1))             // overflow (q > 32 bits)
      check(FL64, false, BigInt(0x1_0000_0000L), BigInt(2)) // 2^32/2 = 2^31 fits
      check(FL64, false, P64 - 1, P32 - 1)               // max/max32
      check(FL64, true,  (BigInt(-1)) & (P64 - 1), BigInt(1)) // -1 / 1
      check(FL64, true,  (BigInt(1) << 40), BigInt(2))   // 2^40/2 = 2^39 -> overflow 32
      check(FW, false, BigInt(5), BigInt(0))             // div0
      check(FL32, true, BigInt(5), BigInt(0))            // div0

      for (_ <- 0 until 200) {
        val signed = rnd.nextBoolean()
        val form = rnd.nextInt(3) match { case 0 => FW; case 1 => FL32; case _ => FL64 }
        val dividend = form match {
          case FL64 => BigInt(64, rnd)
          case _    => BigInt(32, rnd)
        }
        val vw = if (form == FW) 16 else 32
        val divisor = rnd.nextInt(3) match {
          case 0 => BigInt(1 + rnd.nextInt(0x7FFF))
          case 1 => BigInt(vw, rnd)
          case _ => BigInt(1)
        }
        check(form, signed, dividend, if (divisor == 0) BigInt(1) else divisor)
      }
    }
  }

  test("DivUnit signed/unsigned + overflow + sign fix-up vs reference (seed A)", VerilatorTest) {
    runUnitSuite(0xABCDEF1L)
  }
  test("DivUnit signed/unsigned + overflow + sign fix-up vs reference (seed B)", VerilatorTest) {
    runUnitSuite(0x5A5A5A5L)
  }
}
