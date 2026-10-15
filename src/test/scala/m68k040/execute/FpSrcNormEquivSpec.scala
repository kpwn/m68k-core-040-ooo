package m68k040.execute

import m68k040.VerilatorTest
import m68k040.execute.fpu.FpSource
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** CPU_FP_SRC_NORM1 equivalence: `FpSource.toExtendedShared` vs the three per-format
  * converters selected the way both call sites select them, bit for bit over edge
  * values (zeros, subnormals of every leading-zero count, normals, Inf/NaN, INT_MIN,
  * every power of two) and random bit patterns. */
class FpSrcNormEquivSpec extends AnyFunSuite {
  class Dut extends Component {
    val io = new Bundle {
      val isInt, isSgl = in Bool()
      val v = in Bits(64 bits)
      val oldO, newO = out Bits(80 bits)
    }
    // distinct inputs per format (DivEu feeds three different muxes)
    val iv = io.v(31 downto 0); val sv = io.v(63 downto 32) ^ io.v(31 downto 0); val dv = io.v
    io.oldO := Mux(io.isInt, FpSource.intToExtended(iv), Mux(io.isSgl, FpSource.singleToExtended(sv), FpSource.doubleToExtended(dv)))
    io.newO := FpSource.toExtendedShared(io.isInt, io.isSgl, iv, sv, dv)
  }
  test("toExtendedShared == per-format converters", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val rnd = new scala.util.Random(0xf9)
      val edge = scala.collection.mutable.ArrayBuffer[BigInt]()
      for (i <- 0 until 64) { edge += (BigInt(1) << i); edge += ((BigInt(1) << i) - 1) }
      for (e <- Seq(0L, 0xffL, 0x7ffL)) for (i <- 0 until 52) {
        edge += (BigInt(e) << 52) | (BigInt(1) << i)                       // double sub/normal
        edge += (BigInt(e) << 23) | (BigInt(1) << (i % 23)) | (BigInt(0x80000000L) << 32)
      }
      edge ++= Seq(BigInt(0), BigInt("8000000000000000", 16), BigInt(0x80000000L), BigInt(0x7fffffffL),
        BigInt("ffffffffffffffff", 16), BigInt(0xffffffffL), BigInt(0x7f800000L), BigInt(0xff800000L),
        BigInt("7ff0000000000000", 16), BigInt("7ff8000000000001", 16))
      val all = edge.toSeq ++ Seq.fill(60000)(BigInt(64, rnd)) ++
        Seq.fill(20000)(BigInt(64, rnd) >> rnd.nextInt(64))
      var n = 0
      for (v <- all; (ii, ss) <- Seq((true, false), (false, true), (false, false), (true, true))) {
        dut.io.v #= v; dut.io.isInt #= ii; dut.io.isSgl #= ss
        sleep(1)
        assert(dut.io.oldO.toBigInt == dut.io.newO.toBigInt,
          f"v=0x$v%x isInt=$ii isSgl=$ss old=0x${dut.io.oldO.toBigInt}%x new=0x${dut.io.newO.toBigInt}%x")
        n += 1
      }
      println(s"FpSrcNormEquivSpec: $n vectors identical")
    }
  }
}
