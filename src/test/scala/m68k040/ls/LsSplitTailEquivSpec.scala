package m68k040.ls

import m68k040.VerilatorTest
import m68k040.isa.Size
import m68k040.cache.DcacheByteLane
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** CPU_LS_SPLIT_TAIL equivalence: `extractCrossTail(splitKeep(A)[47:24], splitKeep(B)[23:0])`
  * vs `extractCross(A, B)` for every line-crossing (off, size) -- off + nBytes > 16 -- over
  * random lines, bit for bit. */
class LsSplitTailEquivSpec extends AnyFunSuite {
  class Dut extends Component {
    val io = new Bundle {
      val a, b = in Bits(128 bits)
      val off = in UInt(4 bits)
      val size = in(Size())
      val oldO, newO = out Bits(32 bits)
    }
    io.oldO := DcacheByteLane.extractCross(io.a, io.b, io.off, io.size)
    io.newO := DcacheByteLane.extractCrossTail(DcacheByteLane.splitKeep(io.a)(47 downto 24),
      DcacheByteLane.splitKeep(io.b)(23 downto 0), io.off, io.size)
  }
  test("extractCrossTail == extractCross on every crossing access", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val rnd = new scala.util.Random(0x5717)
      var n = 0
      for (_ <- 0 until 3000; (sz, nb) <- Seq((Size.BYTE, 1), (Size.WORD, 2), (Size.LONG, 4)); off <- 0 until 16 if off + nb > 16) {
        dut.io.a #= BigInt(128, rnd); dut.io.b #= BigInt(128, rnd)
        dut.io.off #= off; dut.io.size #= sz
        sleep(1)
        assert(dut.io.oldO.toBigInt == dut.io.newO.toBigInt, s"off=$off size=$sz")
        n += 1
      }
      println(s"LsSplitTailEquivSpec: $n vectors identical")
    }
  }
}
