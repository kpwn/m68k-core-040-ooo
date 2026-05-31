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
}
