package m68k040.frontend

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class DecodePacketSpec extends AnyFunSuite {
  test("DecodePacket carries pc, up to 5 words, predecode flags") {
    SpinalConfig().generateVerilog(new Component {
      val p = DecodePacket()
      assert(p.pc.getWidth == 32)
      assert(p.words.length == 5 && p.words(0).getWidth == 16)
      assert(p.wordCount.getWidth == 3)
      assert(p.lenWords.getWidth == 3)
      val s = master(Stream(Vec(DecodePacket(), 2)))
      s.valid := False; s.payload.foreach(_.assignDontCare())
      val probe = out(Bool()); probe := s.payload(0).simple
    })
  }
}
