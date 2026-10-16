package m68k040.execute

import m68k040.VerilatorTest
import m68k040.isa.Size
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** CPU_SHIFT_FUNNEL equivalence: `ShifterFunnel` (one funnel) vs `Shifter` (the per-op
  * barrel shifters), side by side on the same command. Exhaustive over op x dir x size x
  * X-in x every 6-bit count (register form) and the reachable immediate counts 1..8, with
  * edge + random source words. Compares the size-masked result (the only bits the EU's
  * S3 size merge reads) and N/Z/V/C/X, bit for bit. Also checks the STAGED forms. */
class ShifterFunnelEquivSpec extends AnyFunSuite {
  class Dut extends Component {
    val io = new Bundle {
      val cmd = in(ShiftCmd())
      val oldR = out(ShiftRsp()); val newR = out(ShiftRsp())
      val oldS = out(ShiftRsp()); val newS = out(ShiftRsp())
    }
    io.oldR := Shifter(io.cmd)
    io.newR := ShifterFunnel(io.cmd)
    io.oldS := Shifter.stage2(Shifter.stage1b(Shifter.stage1a(io.cmd)))
    io.newS := ShifterFunnel.stage2(ShifterFunnel.stage1b(ShifterFunnel.stage1a(io.cmd)))
  }
  private def szEnum(size: Int): SpinalEnumElement[Size.type] =
    size match { case 8 => Size.BYTE; case 16 => Size.WORD; case 32 => Size.LONG }

  test("ShifterFunnel == Shifter (exhaustive op/dir/size/x/count, edge+random data)", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      val rnd = new scala.util.Random(0x5f17)
      val edge = Seq(0x00000000L, 0x00000001L, 0x7fffffffL, 0x80000000L, 0xffffffffL,
        0xa5a5a5a5L, 0x5a5a5a5aL, 0x80000001L, 0x0000ff00L, 0x00008001L, 0x00000080L,
        0x0000007fL, 0x00008000L, 0x00007fffL, 0x12345678L, 0xdeadbeefL, 0xcafebabeL)
      val srcs = edge ++ Seq.fill(48)(rnd.nextLong() & 0xffffffffL)
      var n = 0L
      for (tt <- 0 until 4; size <- Seq(8, 16, 32); dir <- Seq(false, true)) {
        val mask = if (size == 32) 0xffffffffL else (1L << size) - 1
        val cases = (1 to 8).map(c => (c, true)) ++ (0 to 63).map(c => (c, false))
        for (src <- srcs; xIn <- Seq(false, true); (count, isImm) <- cases) {
          dut.io.cmd.shiftOp #= tt; dut.io.cmd.dirLeft #= dir; dut.io.cmd.size #= szEnum(size)
          dut.io.cmd.data #= BigInt(src); dut.io.cmd.count #= count
          dut.io.cmd.isImm #= isImm; dut.io.cmd.xIn #= xIn
          sleep(1)
          def chk(o: ShiftRsp, nw: ShiftRsp, tag: String): Unit = {
            val ctx = f"$tag tt=$tt dir=$dir size=$size src=0x$src%08x x=$xIn count=$count imm=$isImm"
            assert((o.result.toLong & mask) == (nw.result.toLong & mask),
              f"result old=0x${o.result.toLong & mask}%x new=0x${nw.result.toLong & mask}%x $ctx")
            assert(o.n.toBoolean == nw.n.toBoolean, s"N $ctx")
            assert(o.z.toBoolean == nw.z.toBoolean, s"Z $ctx")
            assert(o.v.toBoolean == nw.v.toBoolean, s"V $ctx")
            assert(o.c.toBoolean == nw.c.toBoolean, s"C $ctx")
            assert(o.xOut.toBoolean == nw.xOut.toBoolean, s"X $ctx")
          }
          chk(dut.io.oldR, dut.io.newR, "comb")
          chk(dut.io.oldS, dut.io.newS, "staged")
          n += 1
        }
      }
      println(s"ShifterFunnelEquivSpec: $n vectors identical")
    }
  }
}
