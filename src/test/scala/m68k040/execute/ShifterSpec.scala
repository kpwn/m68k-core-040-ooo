package m68k040.execute

import m68k040.{VerilatorTest, SlowTest}
import m68k040.isa.Size
import m68k040.oracle.Musashi
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** Task 2: barrel-shifter datapath validation.
  *
  * Layer 1 (no RTL): ShiftRef (the Scala mirror of Musashi) is checked against the
  * LIVE Musashi runner for every op x dir x size x count edge — this proves the
  * reference faithful.
  *
  * Layer 2: the RTL `Shifter` is checked against ShiftRef exhaustively over the same
  * op x dir x size x count grid (+ X-in for ROX), so the datapath is validated before
  * it is wired into the ALU EU (Task 3). */
class ShifterSpec extends AnyFunSuite {

  // tt -> (rightMnem, leftMnem)
  val OPS = Seq(
    (0, "asr", "asl"),
    (1, "lsr", "lsl"),
    (2, "roxr", "roxl"),
    (3, "ror", "rol")
  )
  val SIZES = Seq((8, "b"), (16, "w"), (32, "l"))

  // ── Layer 1: ShiftRef vs live Musashi ──────────────────────────────────────
  // Drive Musashi with a known X-in (set/clear via a prior add) then the shift,
  // capturing result + CCR. For ROX/count-0 the X-in matters.
  private def musashiShift(mnem: String, sfx: String, src: Long, count: Int, isImm: Boolean, xIn: Int): (Long, Int) = {
    // Set X to xIn: move #x to ccr (X is bit4). Use the .b ANDI/ORI on CCR? Simplest:
    // move #imm,%ccr sets all of NZVCX from imm[4:0].
    val ccrInit = if (xIn != 0) 0x10 else 0x00
    val shiftLine =
      if (isImm) f"    $mnem.$sfx #$count,%%d0\n"
      else       f"    move.l #$count,%%d1\n    $mnem.$sfx %%d1,%%d0\n"
    val srcInit = sfx match {
      case "b" => f"    move.b #0x${src & 0xff}%02x,%%d0\n"
      case "w" => f"    move.w #0x${src & 0xffff}%04x,%%d0\n"
      case "l" => f"    move.l #0x${src & 0xffffffffL}%08x,%%d0\n"
    }
    val prog =
      srcInit +
      f"    move #0x$ccrInit%02x,%%ccr\n" +
      shiftLine +
      "    move %ccr,%d2\n" +
      "    move.l %d0,0xFFFF0000\n"
    Musashi.assembleAndRun(prog) match {
      case Right(st) => (st.d(0), (st.d(2) & 0x1f).toInt)
      case Left(e)   => throw new RuntimeException(s"musashi: ${e.reason}\n$prog")
    }
  }

  private def sizeMask(size: Int): Long = if (size == 32) 0xffffffffL else (1L << size) - 1

  test("ShiftRef mirrors live Musashi (all op x dir x size, imm + reg count, X-in edges)", SlowTest) {
    val srcs = Seq(0x00000000L, 0x00000001L, 0x7fffffffL, 0x80000000L, 0xffffffffL,
                   0xa5a5a5a5L, 0x5a5a5a5aL, 0x80000001L, 0x0000ff00L, 0x00008001L,
                   0x12345678L, 0xdeadbeefL)
    for ((tt, rMnem, lMnem) <- OPS; (size, sfx) <- SIZES) {
      // immediate counts 1..8 (ccc); register counts: 0..size+2, mod-64 edges, and 63.
      val immCounts = (1 to 8)
      val regCounts = (Seq(0, 1, size - 1, size, size + 1, 63) ++ (if (size < 32) Seq(size + size) else Seq(33))).distinct
      for ((dir, mnem) <- Seq((false, rMnem), (true, lMnem))) {
        val xIns = if (tt == 2) Seq(0, 1) else Seq(0)   // X-in only matters for ROX (and count-0, covered by reg count 0)
        for (src <- srcs; xIn <- xIns) {
          // immediate form (ccc 1..8). ccc==0 means 8 architecturally; the assembler
          // encodes #8 directly, so test the literal 1..8.
          for (c <- immCounts) {
            val (mr, mc) = musashiShift(mnem, sfx, src, c, isImm = true, xIn)
            val r = ShiftRef.eval(tt, dir, size, src, c, isImm = true, xIn)
            val ctx = f"$mnem.$sfx #$c src=0x$src%08x xIn=$xIn IMM: mus res=0x$mr%x ccr=0x$mc%02x ref res=0x${r.res}%x N=${r.n} Z=${r.z} V=${r.v} C=${r.c} X=${r.x}"
            assert((mr & sizeMask(size)) == (r.res & sizeMask(size)), s"IMM result — $ctx")
            assert(((mc >> 4) & 1) == r.x, s"IMM X — $ctx")
            assert(((mc >> 3) & 1) == r.n, s"IMM N — $ctx")
            assert(((mc >> 2) & 1) == r.z, s"IMM Z — $ctx")
            assert(((mc >> 1) & 1) == r.v, s"IMM V — $ctx")
            assert((mc & 1) == r.c, s"IMM C — $ctx")
          }
          // register form (count = Dc & 0x3f). We test the literal count value.
          for (c <- regCounts) {
            val (mr, mc) = musashiShift(mnem, sfx, src, c, isImm = false, xIn)
            val r = ShiftRef.eval(tt, dir, size, src, c & 0x3f, isImm = false, xIn)
            val ctx = f"$mnem.$sfx #$c(reg) src=0x$src%08x xIn=$xIn REG: mus res=0x$mr%x ccr=0x$mc%02x ref res=0x${r.res}%x N=${r.n} Z=${r.z} V=${r.v} C=${r.c} X=${r.x}"
            assert((mr & sizeMask(size)) == (r.res & sizeMask(size)), s"REG result — $ctx")
            assert(((mc >> 4) & 1) == r.x, s"REG X — $ctx")
            assert(((mc >> 3) & 1) == r.n, s"REG N — $ctx")
            assert(((mc >> 2) & 1) == r.z, s"REG Z — $ctx")
            assert(((mc >> 1) & 1) == r.v, s"REG V — $ctx")
            assert((mc & 1) == r.c, s"REG C — $ctx")
          }
        }
      }
    }
  }

  // ── Layer 2: RTL Shifter vs ShiftRef ────────────────────────────────────────
  class ShifterDut extends Component {
    val io = new Bundle {
      val cmd = in(ShiftCmd())
      val rsp = out(ShiftRsp())
    }
    io.rsp := Shifter(io.cmd)
  }

  private def szEnum(size: Int): SpinalEnumElement[Size.type] =
    size match { case 8 => Size.BYTE; case 16 => Size.WORD; case 32 => Size.LONG }

  test("RTL Shifter matches ShiftRef (all op x dir x size, imm + reg count, X-in)", VerilatorTest) {
    SimConfig.withVerilator.compile(new ShifterDut).doSim { dut =>
      val srcs = Seq(0x00000000L, 0x00000001L, 0x7fffffffL, 0x80000000L, 0xffffffffL,
                     0xa5a5a5a5L, 0x5a5a5a5aL, 0x80000001L, 0x0000ff00L, 0x00008001L,
                     0x12345678L, 0xdeadbeefL, 0xcafebabeL)
      for ((tt, _, _) <- OPS; (size, _) <- SIZES; dir <- Seq(false, true)) {
        // imm counts 1..8; reg counts 0..63 (full sweep — cheap in RTL).
        val cases =
          (1 to 8).map(c => (c, true)) ++ (0 to 63).map(c => (c, false))
        for (src <- srcs; xIn <- Seq(0, 1); (count, isImm) <- cases) {
          dut.io.cmd.shiftOp #= tt
          dut.io.cmd.dirLeft #= dir
          dut.io.cmd.size    #= szEnum(size)
          dut.io.cmd.data    #= BigInt(src & 0xffffffffL)
          dut.io.cmd.count   #= count
          dut.io.cmd.isImm   #= isImm
          dut.io.cmd.xIn     #= (xIn != 0)
          sleep(1)
          val effCount = if (isImm) count else (count & 0x3f)
          val r = ShiftRef.eval(tt, dir, size, src, effCount, isImm, xIn)
          val gRes = dut.io.rsp.result.toLong & sizeMask(size)
          val gN = if (dut.io.rsp.n.toBoolean) 1 else 0
          val gZ = if (dut.io.rsp.z.toBoolean) 1 else 0
          val gV = if (dut.io.rsp.v.toBoolean) 1 else 0
          val gC = if (dut.io.rsp.c.toBoolean) 1 else 0
          val gX = if (dut.io.rsp.xOut.toBoolean) 1 else 0
          val ctx = f"tt=$tt dir=$dir size=$size src=0x$src%08x xIn=$xIn count=$count imm=$isImm: ref res=0x${r.res}%x N=${r.n}Z=${r.z}V=${r.v}C=${r.c}X=${r.x} got res=0x$gRes%x N=${gN}Z=${gZ}V=${gV}C=${gC}X=${gX}"
          assert(gRes == (r.res & sizeMask(size)), s"result — $ctx")
          assert(gN == r.n, s"N — $ctx")
          assert(gZ == r.z, s"Z — $ctx")
          assert(gV == r.v, s"V — $ctx")
          assert(gC == r.c, s"C — $ctx")
          assert(gX == r.x, s"X — $ctx")
        }
      }
    }
  }
}
