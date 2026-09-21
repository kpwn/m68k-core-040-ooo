package m68k040.debug

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class HaltAfterLoadGuardSpec extends AnyFunSuite {
  class Dut extends Component {
    val inputs = in Bits(6 bits)
    val hold = out Bool()
    hold := HaltAfterLoadGuard(inputs(0), inputs(1), inputs(2), inputs(3))
  }

  test("all input combinations dominate exact guard; only stale or invalidated hits differ") {
    M68kSim().compile(new Dut).doSim { dut =>
      var additionalHolds = 0
      for (bits <- 0 until 64) {
        def b(i: Int): Boolean = (bits & (1 << i)) != 0
        val armed = b(0); val cmpArmed = b(1); val pending = b(2)
        val hit = b(3); val invalidated = b(4); val epochMatches = b(5)
        dut.inputs #= bits
        sleep(1)
        val precise = armed && ((!cmpArmed || pending) ||
          (cmpArmed && !pending && !invalidated && epochMatches && hit))
        val hold = dut.hold.toBoolean
        assert(!precise || hold, s"unsafe launch allowed for input $bits")
        assert(hold == (armed && (!cmpArmed || pending || hit)))
        if (hold != precise) {
          additionalHolds += 1
          assert(armed && cmpArmed && !pending && hit &&
            (invalidated || !epochMatches), s"unexplained extra hold for $bits")
        }
      }
      assert(additionalHolds == 3, "stale/invalidate coverage must be nonvacuous")
    }
  }
}
