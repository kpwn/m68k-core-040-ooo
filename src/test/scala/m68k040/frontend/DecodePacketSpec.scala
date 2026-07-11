package m68k040.frontend

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class DecodePacketSpec extends AnyFunSuite {
  test("DecodePacket carries pc, up to 10 words, predecode flags") {
    SpinalConfig().generateVerilog(new Component {
      val p = DecodePacket()
      assert(p.pc.getWidth == 32)
      // Widened 6->10 (deep-audit F3, 2026-07-11): sized to the front-end's actual
      // HEAD_WORDS/Aligner.WINDOW visibility ceiling (see DecodePacket.scala's comment).
      assert(p.words.length == 10 && p.words(0).getWidth == 16)
      assert(p.wordCount.getWidth == 4)
      assert(p.lenWords.getWidth == 4)
      val s = master(Stream(Vec(DecodePacket(), 2)))
      s.valid := False; s.payload.foreach(_.assignDontCare())
      val probe = out(Bool()); probe := s.payload(0).simple
    })
  }
}
