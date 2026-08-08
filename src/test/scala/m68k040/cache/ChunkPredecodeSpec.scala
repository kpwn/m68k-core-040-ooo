package m68k040.cache

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class ChunkPredecodeSpec extends AnyFunSuite {
  test("ChunkPredecode is 8 bits; FetchRsp carries 4 of them") {
    SpinalConfig().generateVerilog(new Component {
      val c = ChunkPredecode()
      assert(c.simple.isInstanceOf[Bool])
      // Widened 3->4 bits (deep-audit F1/F2/F3, 2026-07-11): see ChunkPredecode's field
      // comment (a MOVE with two full-format EAs can need up to 11 words, which overflowed
      // the old 3-bit field).
      assert(c.lenWords.getWidth == 4)
      // Widened 5->6 bits (task #202, 2026-07-22): added `ambiguousLine`, a 1-bit flag
      // for the I-cache-line-boundary predecode fix (see ChunkPredecode's field comment).
      // Widened 6->8 bits (FMax "Lever B", 2026-08-08): added `size`, the 2-bit
      // `OperationDecoder.decode(op).size` baked at REFILL time so DecodeStage need not
      // re-derive it in series with the destination-EA decode. This width is what drives
      // `IcachePlugin`'s `predMem` from 192 to 256 bits per line/way (the plugin itself
      // needs no edit — every width there derives from `ChunkPredecode().getBitsWidth`).
      assert(c.size.getBitsWidth == 2)
      assert(c.asBits.getWidth == 8)
      val r = master(Flow(FetchRsp()))
      r.valid := False
      r.payload.assignDontCare()
      assert(r.payload.pred.length == 4)
      val probe = out(Bool()); probe := r.payload.pred(0).simple
    })
  }
}
