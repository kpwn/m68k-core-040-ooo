package m68k040.execute

import m68k040.VerilatorTest
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** CPU_BF_TRIM equivalence: Bitfield.stage2T(stage1T(cmd)) vs stage2(stage1(cmd)), bit for
  * bit, exhaustive over op x offset x raw width with edge + random dy/dn2/ffoBase. */
class BitfieldTrimEquivSpec extends AnyFunSuite {
  class Dut extends Component {
    val io = new Bundle {
      val cmd = in(BitfieldCmd())
      val oldR, newR = out(BitfieldRsp())
    }
    io.oldR := Bitfield.stage2(Bitfield.stage1(io.cmd))
    io.newR := Bitfield.stage2T(Bitfield.stage1T(io.cmd))
  }
  test("Bitfield trimmed == Bitfield", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val rnd = new scala.util.Random(0xbf)
      val edge = Seq(0L, 0xffffffffL, 0x80000000L, 1L, 0x7fffffffL, 0xa5a5a5a5L, 0x5a5a5a5aL)
      var n = 0
      for (op <- 0 until 8; off <- 0 until 32; w <- 0 until 32; k <- 0 until 8) {
        def pick() = if (k < 3) edge(rnd.nextInt(edge.size)) else rnd.nextLong() & 0xffffffffL
        dut.io.cmd.dy #= pick(); dut.io.cmd.dn2 #= pick(); dut.io.cmd.ffoBase #= pick()
        dut.io.cmd.offset #= off; dut.io.cmd.rawWidth #= w; dut.io.cmd.bfOp #= op
        sleep(1)
        val ctx = s"op=$op off=$off w=$w"
        assert(dut.io.oldR.result.toBigInt == dut.io.newR.result.toBigInt, s"result $ctx")
        assert(dut.io.oldR.n.toBoolean == dut.io.newR.n.toBoolean, s"N $ctx")
        assert(dut.io.oldR.z.toBoolean == dut.io.newR.z.toBoolean, s"Z $ctx")
        n += 1
      }
      println(s"BitfieldTrimEquivSpec: $n vectors identical")
    }
  }
}
