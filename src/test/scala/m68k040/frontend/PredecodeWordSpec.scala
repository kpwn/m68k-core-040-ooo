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

  // ── Brief-format indexed EA length framing (1 extension word) ─────────────────
  test("indexed-EA framing: MOVE/ALU/RMW src + dest -> SIMPLE +1 ext word", VerilatorTest) {
    SimConfig.withVerilator.compile(new Dut).doSim { dut =>
      def chk(op: Int, len: Int, name: String): Unit = {
        dut.op #= op; sleep(1)
        assert(dut.res.simple.toBoolean, f"$name op=0x$op%04x expected simple")
        assert(dut.res.lenWords.toInt == len, f"$name op=0x$op%04x len rtl=${dut.res.lenWords.toInt} exp=$len")
      }
      // move.l (4,%a0,%d1.w*2),%d2 = 0x2430 (MOVE.L dst D2, src mode6 reg0)
      chk(0x2430, 2, "MOVE.L (d8,An,Xn) src")
      // move.l (6,%pc,%d1.w*2),%d2 = 0x243B (src mode7 reg3)
      chk(0x243B, 2, "MOVE.L (d8,PC,Xn) src")
      // add.l %d1,(4,%a0,%d2.w) = 0xD3B0 (line D, Dn=D1 src, opmode6 .L, dst mode6 reg0)
      chk(0xD3B0, 2, "ADD.L Dn,(d8,An,Xn) RMW dest")
      // add.l (4,%a0,%d1.w),%d2 = 0xD4B0 (line D, dst D2, opmode2 .L src, src mode6 reg0)
      chk(0xD4B0, 2, "ADD.L (d8,An,Xn),Dn src")
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
