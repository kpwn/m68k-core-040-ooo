package m68k040.frontend

import m68k040.VerilatorTest
import m68k040.cache.ChunkPredecode
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class PredecodeWordSpec extends AnyFunSuite {
  class Dut extends Component {
    val op  = in  Bits (16 bits)
    val res = out (ChunkPredecode())
    res := PredecodeWord.classify(op)
  }
  test("RTL PredecodeWord matches PredecodeRef for ALL 65536 opwords", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      for (op <- 0 until 65536) {
        dut.op #= op
        sleep(1)
        val ref = PredecodeRef.classify(op)
        val rs  = dut.res.simple.toBoolean
        assert(rs == ref.simple, f"op=0x$op%04x simple: rtl=$rs ref=${ref.simple}")
        if (ref.simple)
          assert(dut.res.lenWords.toInt == ref.lenWords,
            f"op=0x$op%04x len: rtl=${dut.res.lenWords.toInt} ref=${ref.lenWords}")
      }
    }
  }

  test("BCD/ADDX/SUBX -(Ay),-(Ax) memory forms frame simple len=1", VerilatorTest) {
    // ABCD -(A1),-(A0) 0xC109 ; SBCD 0x8109 ; ADDX.L -(A2),-(A3) 0xD78A ; SUBX.B -(A1),-(A0) 0x9109
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      for (op <- Seq(0xC109, 0x8109, 0xD78A, 0x9109)) {
        dut.op #= op; sleep(1)
        assert(dut.res.simple.toBoolean, f"opword 0x$op%04x must frame simple")
        assert(dut.res.lenWords.toInt == 1, f"opword 0x$op%04x must frame lenWords=1, got ${dut.res.lenWords.toInt}")
      }
    }
  }
}
