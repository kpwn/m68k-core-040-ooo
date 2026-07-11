package m68k040.cache

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class ChunkPredecodeSpec extends AnyFunSuite {
  test("ChunkPredecode is 5 bits; FetchRsp carries 4 of them") {
    SpinalConfig().generateVerilog(new Component {
      val c = ChunkPredecode()
      assert(c.simple.isInstanceOf[Bool])
      // Widened 3->4 bits (deep-audit F1/F2/F3, 2026-07-11): see ChunkPredecode's field
      // comment (a MOVE with two full-format EAs can need up to 11 words, which overflowed
      // the old 3-bit field).
      assert(c.lenWords.getWidth == 4)
      assert(c.asBits.getWidth == 5)
      val r = master(Flow(FetchRsp()))
      r.valid := False
      r.payload.assignDontCare()
      assert(r.payload.pred.length == 4)
      val probe = out(Bool()); probe := r.payload.pred(0).simple
    })
  }
}
