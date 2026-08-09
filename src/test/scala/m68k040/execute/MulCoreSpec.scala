package m68k040.execute

import m68k040.{M68kSim, VerilatorTest}
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

/** Standalone validation of the DSP-mappable MulCore vs a Scala reference over
  * random + edge operands (Task 2). The core multiplies two 32-bit operands
  * (signed or unsigned) and produces the FULL 64-bit product split into
  * {prodLo[31:0], prodHi[31:0]}. The EU narrows .W/.L32/.L64 and computes flags.
  *
  * The multiply is REGISTERED (a*b latched) so Vivado infers DSP48E2 hard blocks;
  * `start` presents operands, `done` pulses when the product is valid. */
class MulCoreSpec extends AnyFunSuite {

  val M32 = (BigInt(1) << 32) - 1
  val M64 = (BigInt(1) << 64) - 1

  /** 64-bit product reference. Unsigned: a*b as magnitudes. Signed: sign-extend
    * each 32-bit operand, multiply, mask to 64 bits (two's-complement product). */
  def ref(signed: Boolean, a: BigInt, b: BigInt): BigInt = {
    def sx(v: BigInt) = { val m = BigInt(1) << 31; if ((v & M32) >= m) (v & M32) - (BigInt(1) << 32) else (v & M32) }
    val prod = if (!signed) (a & M32) * (b & M32) else sx(a) * sx(b)
    prod & M64
  }

  /** Drive one multiply; return the 64-bit product (prodHi:prodLo). */
  def runMul(dut: MulCore, cd: ClockDomain, signed: Boolean, a: BigInt, b: BigInt): BigInt = {
    dut.io.start  #= true
    dut.io.a      #= a & M32
    dut.io.b      #= b & M32
    dut.io.signed #= signed
    cd.waitSampling()
    dut.io.start #= false
    var guard = 0
    while (!dut.io.done.toBoolean && guard < 50) { cd.waitSampling(); guard += 1 }
    assert(dut.io.done.toBoolean, s"MulCore never asserted done for $a * $b (signed=$signed)")
    val lo = dut.io.prodLo.toBigInt
    val hi = dut.io.prodHi.toBigInt
    cd.waitSampling(2)
    (hi << 32) | lo
  }

  def runSuite(seed: Long): Unit = {
    M68kSim().withVerilator.compile(new MulCore).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.io.start #= false; dut.io.a #= 0; dut.io.b #= 0; dut.io.signed #= false
      cd.waitSampling(5)

      val rnd = new Random(seed)
      val INT_MIN = BigInt(0x80000000L)
      val INT_MAX = BigInt(0x7FFFFFFFL)

      // Edge operands (each tried signed + unsigned).
      val edges: Seq[(BigInt, BigInt)] = Seq(
        (BigInt(0), BigInt(0)),
        (BigInt(0), M32),
        (BigInt(1), BigInt(1)),
        (BigInt(1), M32),
        (M32, M32),                      // -1*-1 signed = 1 ; max*max unsigned
        (INT_MIN, INT_MIN),              // signed: 2^62
        (INT_MIN, BigInt(-1) & M32),     // signed INT_MIN * -1 = 2^31 (fits hi)
        (INT_MAX, INT_MAX),
        (INT_MIN, BigInt(1)),
        (BigInt(0xFFFF), BigInt(0xFFFF)),     // .W max unsigned
        (BigInt(0x8000), BigInt(0x8000)),     // .W signed boundary (after sext)
        (BigInt(0xDEADBEEFL), BigInt(0xCAFEBABEL)),
        (BigInt(100000), BigInt(100000)),     // .L32 overflow case
        (BigInt(0x10000), BigInt(0x10000))    // 2^16 * 2^16 = 2^32 (hi=1)
      )

      def check(signed: Boolean, a: BigInt, b: BigInt): Unit = {
        val got = runMul(dut, cd, signed, a, b)
        val exp = ref(signed, a, b)
        assert(got == exp, f"MulCore mismatch: signed=$signed a=$a%x b=$b%x -> $got%x (ref $exp%x)")
      }

      edges.foreach { case (a, b) => check(false, a, b); check(true, a, b) }

      for (_ <- 0 until 400) {
        val signed = rnd.nextBoolean()
        // bias toward small + 16-bit + full-32 to exercise both .W and .L paths
        def operand(): BigInt = rnd.nextInt(4) match {
          case 0 => BigInt(rnd.nextInt(0x10000))            // 16-bit
          case 1 => BigInt(32, rnd) & M32                   // full 32
          case 2 => BigInt(rnd.nextInt(1000))               // small
          case _ => Seq(BigInt(0), BigInt(1), M32, INT_MIN, INT_MAX)(rnd.nextInt(5))
        }
        check(signed, operand(), operand())
      }
    }
  }

  test("MulCore signed/unsigned: random + edge operands vs Scala reference (seed A)", VerilatorTest) {
    runSuite(0xABCD1234L)
  }
  test("MulCore signed/unsigned: random + edge operands vs Scala reference (seed B)", VerilatorTest) {
    runSuite(0x5A5A5A5L)
  }

  test("MulCore accepts and completes eight consecutive associated products", VerilatorTest) {
    val vectors = Seq(
      (false, BigInt(0),                 BigInt(0xFFFFFFFFL)),
      (true,  BigInt(0xFFFFFFFFL),       BigInt(0xFFFFFFFFL)),
      (false, BigInt(0xFFFFFFFFL),       BigInt(0xFFFFFFFFL)),
      (true,  BigInt(0x80000000L),       BigInt(2)),
      (false, BigInt(0x10000),           BigInt(0x10000)),
      (true,  BigInt(0x7FFFFFFFL),       BigInt(0x80000000L)),
      (false, BigInt(0xDEADBEEFL),       BigInt(0xCAFEBABEL)),
      (true,  BigInt(0xFFFFFFF9L),       BigInt(0x12345678L)))

    M68kSim().withVerilator.compile(new MulCore).doSim { dut =>
      val cd = dut.clockDomain; cd.forkStimulus(10)
      dut.io.start #= false; dut.io.a #= 0; dut.io.b #= 0; dut.io.signed #= false
      cd.waitSampling(5)

      var accepted = 0
      var completed = 0
      var firstDoneCycle = -1
      var maxResidentBeforeRetire = 0
      val totalCycles = vectors.length + MulCore.Latency + 2
      for (cycle <- 0 until totalCycles) {
        if (cycle < vectors.length) {
          val (signed, a, b) = vectors(cycle)
          dut.io.start #= true
          dut.io.signed #= signed
          dut.io.a #= a & M32
          dut.io.b #= b & M32
        } else {
          dut.io.start #= false
        }

        cd.waitSampling()
        if (cycle < vectors.length) accepted += 1
        // Count the just-accepted entry before retiring a same-edge result. This
        // observes every registered stage resident on the first done edge.
        maxResidentBeforeRetire = math.max(maxResidentBeforeRetire, accepted - completed)

        val shouldDone = cycle >= MulCore.Latency &&
          cycle < MulCore.Latency + vectors.length
        assert(dut.io.done.toBoolean == shouldDone,
          s"cycle $cycle done=${dut.io.done.toBoolean}, expected=$shouldDone")
        if (dut.io.done.toBoolean) {
          if (firstDoneCycle < 0) firstDoneCycle = cycle
          val (signed, a, b) = vectors(completed)
          val got = (dut.io.prodHi.toBigInt << 32) | dut.io.prodLo.toBigInt
          val exp = ref(signed, a, b)
          assert(got == exp,
            f"dense product #$completed signed=$signed got=$got%x expected=$exp%x")
          completed += 1
        }
      }

      assert(accepted == vectors.length)
      assert(completed == vectors.length, s"completed $completed/${vectors.length}")
      assert(firstDoneCycle == MulCore.Latency,
        s"first done at $firstDoneCycle, expected ${MulCore.Latency}")
      assert(maxResidentBeforeRetire >= MulCore.Latency,
        s"only $maxResidentBeforeRetire products were simultaneously resident")
      assert(!dut.io.done.toBoolean, "unexpected extra done after the dense burst")
    }
  }
}
