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
}
