package m68k040.execute

import m68k040.{VerilatorTest, SlowTest}
import m68k040.decode.DecOp
import m68k040.isa.Size
import m68k040.oracle.Musashi
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class AluDatapathSpec extends AnyFunSuite {

  class AluDut extends Component {
    val io = new Bundle {
      val cmd = in(AluCmd())
      val rsp = out(AluRsp())
    }
    io.rsp := AluDatapath(io.cmd)
  }

  case class OpCase(mnem: String, dec: SpinalEnumElement[DecOp.type], writesX: Boolean, writesDst: Boolean)
  val OPS = Seq(
    OpCase("move", DecOp.MOVE, writesX = false, writesDst = true),
    OpCase("add",  DecOp.ADD,  writesX = true,  writesDst = true),
    OpCase("sub",  DecOp.SUB,  writesX = true,  writesDst = true),
    OpCase("and",  DecOp.AND,  writesX = false, writesDst = true),
    OpCase("or",   DecOp.OR,   writesX = false, writesDst = true),
    OpCase("eor",  DecOp.EOR,  writesX = false, writesDst = true),
    OpCase("cmp",  DecOp.CMP,  writesX = false, writesDst = false)
  )
  case class SzCase(sfx: String, enum: SpinalEnumElement[Size.type], w: Int) { def mask: Long = if (w == 32) 0xFFFFFFFFL else (1L << w) - 1 }
  val SIZES = Seq(SzCase("b", Size.BYTE, 8), SzCase("w", Size.WORD, 16), SzCase("l", Size.LONG, 32))

  case class Exp(result: Long, ccr: Int)
  def oracle(mnem: String, sfx: String, src1: Long, src2: Long): Exp = {
    val src =
      f"""    move.l #0x${src1 & 0xffffffffL}%08x,%%d0
         |    move.l #0x${src2 & 0xffffffffL}%08x,%%d1
         |    $mnem.$sfx %%d1,%%d0
         |    move %%ccr,%%d2
         |    move.l %%d0,0xFFFF0000
         |""".stripMargin
    Musashi.assembleAndRun(src) match {
      case Right(st) => Exp(st.d(0), (st.d(2) & 0x1f).toInt)
      case Left(e)   => throw new RuntimeException(s"musashi: ${e.reason}\n$src")
    }
  }

  def check(dut: AluDut, oc: OpCase, sz: SzCase, src1: Long, src2: Long): Unit = {
    val exp = oracle(oc.mnem, sz.sfx, src1, src2)
    dut.io.cmd.op   #= oc.dec
    dut.io.cmd.size #= sz.enum
    dut.io.cmd.src1 #= BigInt(src1 & 0xffffffffL)
    dut.io.cmd.src2 #= BigInt(src2 & 0xffffffffL)
    dut.io.cmd.xIn  #= false
    sleep(1)
    val gotResult = dut.io.rsp.result.toLong & 0xffffffffL
    val gotNzvc   = dut.io.rsp.nzvc.toInt
    val gotX      = dut.io.rsp.xOut.toBoolean
    val gN = (gotNzvc >> 3) & 1; val gZ = (gotNzvc >> 2) & 1; val gV = (gotNzvc >> 1) & 1; val gC = gotNzvc & 1
    val eX = (exp.ccr >> 4) & 1; val eN = (exp.ccr >> 3) & 1; val eZ = (exp.ccr >> 2) & 1; val eV = (exp.ccr >> 1) & 1; val eC = exp.ccr & 1
    val ctx = f"${oc.mnem}.${sz.sfx} src1=0x$src1%x src2=0x$src2%x: musCCR=0x${exp.ccr}%02x musRes=0x${exp.result}%x got nzvc=$gotNzvc x=$gotX res=0x$gotResult%x"
    if (oc.writesDst) assert((gotResult & sz.mask) == (exp.result & sz.mask), s"result mismatch — $ctx")
    assert(gN == eN, s"N — $ctx"); assert(gZ == eZ, s"Z — $ctx")
    assert(gV == eV, s"V — $ctx"); assert(gC == eC, s"C — $ctx")
    if (oc.writesX) assert((if (gotX) 1 else 0) == eX, s"X — $ctx")
  }

  test("ADD.B directed: carry + overflow boundary", VerilatorTest) {
    SimConfig.withVerilator.compile(new AluDut).doSim { dut =>
      check(dut, OPS.find(_.mnem == "add").get, SIZES.head, 0x000000FFL, 0x00000001L)
      check(dut, OPS.find(_.mnem == "add").get, SIZES.head, 0x0000007FL, 0x00000001L)
    }
  }

  test("directed corner cases across all op x size", VerilatorTest) {
    SimConfig.withVerilator.compile(new AluDut).doSim { dut =>
      val vectors = Seq(
        (0x00000000L, 0x00000000L),
        (0x7FFFFFFFL, 0x00000001L),
        (0x80000000L, 0x00000001L),
        (0x000000FFL, 0x00000001L),
        (0x0000FFFFL, 0x00000001L),
        (0xFFFFFFFFL, 0x00000001L),
        (0x12345678L, 0x12345678L),
        (0x80808080L, 0x7F7F7F7FL),
        (0xA5A5A5A5L, 0x5A5A5A5AL),
        (0x00000080L, 0x00000080L)
      )
      for (oc <- OPS; sz <- SIZES; (s1, s2) <- vectors) check(dut, oc, sz, s1, s2)
    }
  }

  test("randomized sweep vs Musashi (all op x size)", VerilatorTest, SlowTest) {
    val rng = new scala.util.Random(0x68040)
    val PER = 24
    SimConfig.withVerilator.compile(new AluDut).doSim { dut =>
      for (oc <- OPS; sz <- SIZES) {
        for (_ <- 0 until PER) {
          val s1 = rng.nextInt() & 0xffffffffL
          val s2 = rng.nextInt() & 0xffffffffL
          check(dut, oc, sz, s1, s2)
        }
      }
    }
  }
}
