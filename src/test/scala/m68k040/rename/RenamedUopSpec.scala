package m68k040.rename

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class RenamedUopSpec extends AnyFunSuite {
  test("RenamedUop carries physical operands") {
    SpinalConfig().generateVerilog(new Component {
      val u = RenamedUop()
      assert(u.pdst.getWidth == 6 && u.psrcA.getWidth == 6 && u.pdstOld.getWidth == 6)
      assert(u.pNzvcDst.getWidth == 4 && u.pXDst.getWidth == 4)
      val s = master(Stream(Vec(RenamedUop(), 2)))
      s.valid := False; s.payload.foreach(_.assignDontCare())
      val probe = out(Bool()); probe := s.payload(0).pdstValid
    })
  }
}
