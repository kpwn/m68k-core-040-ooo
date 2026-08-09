package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import m68k040.decode.{DecOp, Microcode, OpSpec, OperandKind, OperationDecoder}
import m68k040.isa.Size
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

/** Cross-layer guard for the three meanings of line-B opmodes 4/5/6.
  *
  * The EA-mode bits are an opcode discriminator here, not a uniform destination EA:
  * mode 000 is register-destination EOR, mode 001 is CMPM, and alterable-memory modes
  * are EOR read-modify-write. Keeping predecode and OperationDecoder in the same DUT
  * makes a future drift in either classifier fail on the exact opcode that diverged.
  */
class LineBEorPartitionSpec extends AnyFunSuite {
  class Dut extends Component {
    val opword = in Bits (16 bits)
    val pred   = out(ChunkPredecode())
    val dec    = out(OpSpec())

    pred := PredecodeWord.classify(opword)
    dec  := OperationDecoder.decode(opword)
  }

  private val sizes = Seq(
    4 -> Size.BYTE,
    5 -> Size.WORD,
    6 -> Size.LONG
  )

  private def opcode(dn: Int, opmode: Int, mode: Int, reg: Int): Int =
    0xB000 | (dn << 9) | (opmode << 6) | (mode << 3) | reg

  test("EXHAUSTIVE line-B mode partition: Dn EOR vs CMPM vs memory EOR", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      def drive(op: Int): Unit = {
        dut.opword #= op
        sleep(1)
      }

      def assertEor(op: Int, size: Size.E, lenWords: Int): Unit = {
        drive(op)
        assert(dut.pred.simple.toBoolean, f"op=0x$op%04x EOR must predecode simple")
        assert(dut.pred.lenWords.toInt == lenWords,
          f"op=0x$op%04x EOR len=${dut.pred.lenWords.toInt}, expected $lenWords")
        assert(!dut.dec.illegal.toBoolean && !dut.dec.microcoded.toBoolean,
          f"op=0x$op%04x EOR must be direct, not illegal/microcoded")
        assert(dut.dec.op.toEnum == DecOp.EOR && dut.dec.size.toEnum == size,
          f"op=0x$op%04x decoded ${dut.dec.op.toEnum}/${dut.dec.size.toEnum}, expected EOR/$size")
        assert(dut.dec.srcA.kind.toEnum == OperandKind.EASRC,
          f"op=0x$op%04x EOR srcA must be its destination EA")
        assert(dut.dec.srcB.kind.toEnum == OperandKind.REGFIELD && !dut.dec.srcB.isAddr.toBoolean,
          f"op=0x$op%04x EOR srcB must be the Dn field")
        assert(dut.dec.dst.kind.toEnum == OperandKind.EASRC && dut.dec.dstWrites.toBoolean,
          f"op=0x$op%04x EOR must write its destination EA")
        assert(dut.dec.writesNzvc.toBoolean && !dut.dec.writesX.toBoolean,
          f"op=0x$op%04x EOR must write NZVC and preserve X")
      }

      def assertCmpm(op: Int, size: Size.E): Unit = {
        drive(op)
        assert(dut.pred.simple.toBoolean && dut.pred.lenWords.toInt == 1,
          f"op=0x$op%04x CMPM must predecode simple len1")
        assert(!dut.dec.illegal.toBoolean && dut.dec.microcoded.toBoolean,
          f"op=0x$op%04x CMPM must be legal and microcoded")
        assert(dut.dec.op.toEnum == DecOp.CMP && dut.dec.size.toEnum == size,
          f"op=0x$op%04x decoded ${dut.dec.op.toEnum}/${dut.dec.size.toEnum}, expected CMP/$size")
        assert(dut.dec.ucEntry.toInt == Microcode.CMPM_ENTRY,
          f"op=0x$op%04x ucEntry=${dut.dec.ucEntry.toInt}, expected ${Microcode.CMPM_ENTRY}")
        assert(!dut.dec.dstWrites.toBoolean && dut.dec.writesNzvc.toBoolean && !dut.dec.writesX.toBoolean,
          f"op=0x$op%04x CMPM must write only NZVC")
      }

      var eorRegChecked = 0
      var cmpmChecked   = 0
      var eorMemChecked = 0

      for {
        (opmode, size) <- sizes
        dn             <- 0 until 8
        reg            <- 0 until 8
      } {
        assertEor(opcode(dn, opmode, mode = 0, reg), size, lenWords = 1)
        eorRegChecked += 1

        assertCmpm(opcode(dn, opmode, mode = 1, reg), size)
        cmpmChecked += 1

        // All An-based alterable-memory forms. Modes 2/3/4 have no extension;
        // mode 5 has d16; mode 6 uses the default brief-index extension here.
        for ((mode, len) <- Seq(2 -> 1, 3 -> 1, 4 -> 1, 5 -> 2, 6 -> 2)) {
          assertEor(opcode(dn, opmode, mode, reg), size, len)
          eorMemChecked += 1
        }
      }

      // Absolute .W/.L are the two alterable mode-7 destinations. Exercise every
      // EOR source register and size; the other mode-7 submodes are read-only or invalid.
      for {
        (opmode, size) <- sizes
        dn             <- 0 until 8
        (reg, len)     <- Seq(0 -> 2, 1 -> 3)
      } {
        assertEor(opcode(dn, opmode, mode = 7, reg), size, len)
        eorMemChecked += 1
      }

      assert(eorRegChecked == 192)
      assert(cmpmChecked == 192)
      assert(eorMemChecked == 1008)
    }
  }

  test("line-B non-alterable mode-7 destinations never enter the simple EOR RMW path", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      var checked = 0
      for {
        (opmode, _) <- sizes
        dn          <- 0 until 8
        reg         <- 2 until 8
      } {
        val op = opcode(dn, opmode, mode = 7, reg)
        dut.opword #= op
        sleep(1)
        assert(!dut.pred.simple.toBoolean,
          f"op=0x$op%04x non-alterable mode-7 destination must not predecode as simple EOR RMW")
        checked += 1
      }
      assert(checked == 144)
    }
  }
}
