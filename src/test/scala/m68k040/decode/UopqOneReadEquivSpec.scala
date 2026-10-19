package m68k040.decode

import m68k040.M68kSim
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.util.Random

/** CPU_UOPQ_ONE_READ: the one-read-port-per-bank MicroOpQueue must present EXACTLY the two-
  * read-port queue's pop payloads (both slots, valid or not, once every row has been written),
  * pop valids and push ready every cycle, under random push/pop/flush traffic. */
class UopqOneReadEquivSpec extends AnyFunSuite {
  class Dut extends Component {
    val a = new MicroOpQueue(depth = 16, oneRead = false)
    val b = new MicroOpQueue(depth = 16, oneRead = true)
    val pushValid = in Bool()
    val pushCount = in UInt(3 bits)
    val pushBits  = in Vec(Bits(widthOf(DecodedUop(ImmEnc.WIDTH)) bits), 4)
    val popReady  = in Bool()
    val flush     = in Bool()
    val ready     = out Bool()
    val mismatch  = out Bool()
    for (q <- Seq(a, b)) {
      q.io.push.valid := pushValid
      q.io.push.count := pushCount
      for (i <- 0 until 4) q.io.push.uops(i).assignFromBits(pushBits(i))
      q.io.pop.ready := popReady
      q.io.flush := flush
    }
    ready := a.io.push.ready
    a.count.simPublic()
    mismatch := a.io.pop.payload.asBits =/= b.io.pop.payload.asBits ||
                a.io.pop.valid =/= b.io.pop.valid || a.io.pop1Valid =/= b.io.pop1Valid ||
                a.io.push.ready =/= b.io.push.ready
  }

  test("CPU_UOPQ_ONE_READ queue == two-read queue") {
    M68kSim().withVerilator.compile(new Dut).doSim(seed = 31) { dut =>
      val rnd = new Random(31)
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      def drive(v: Boolean, n: Int): Unit = {
        dut.pushValid #= v; dut.pushCount #= n
        dut.pushBits.foreach(_.randomize())
      }
      dut.flush #= true; dut.popReady #= false; drive(false, 0)
      cd.waitSampling(2)
      // Warm-up: write all 16 rows of both queues (4 bursts of 4, no pop), then flush.
      dut.flush #= false
      for (_ <- 0 until 4) { drive(true, 4); cd.waitSampling() }
      drive(false, 0); dut.flush #= true; cd.waitSampling()
      var popped = 0L; var pushed = 0L; var wrapped = 0L
      for (n <- 0 until 200000) {
        dut.flush #= rnd.nextInt(400) == 0
        drive(rnd.nextInt(3) != 0, rnd.nextInt(5))
        dut.popReady #= rnd.nextInt(4) != 0
        sleep(1)
        assert(!dut.mismatch.toBoolean, s"one-read MicroOpQueue diverged at cycle $n")
        if (dut.popReady.toBoolean && dut.a.count.toInt > 0) popped += 1
        if (dut.pushValid.toBoolean && dut.ready.toBoolean) pushed += 1
        if (dut.a.count.toInt >= 12) wrapped += 1
        cd.waitSampling()
      }
      println(s"[UopqOneReadEquiv] popped=$popped pushed=$pushed deep=$wrapped")
      assert(popped > 50000 && pushed > 50000)
    }
  }
}
