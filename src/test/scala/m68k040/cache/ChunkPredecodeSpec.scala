package m68k040.cache

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class ChunkPredecodeSpec extends AnyFunSuite {
  test("ChunkPredecode is 4 bits; FetchRsp carries 4 of them") {
    SpinalConfig().generateVerilog(new Component {
      val c = ChunkPredecode()
      assert(c.simple.isInstanceOf[Bool])
      assert(c.lenWords.getWidth == 3)
      assert(c.asBits.getWidth == 4)
      val r = master(Flow(FetchRsp()))
      r.valid := False
      r.payload.assignDontCare()
      assert(r.payload.pred.length == 4)
      val probe = out(Bool()); probe := r.payload.pred(0).simple
    })
  }
}
