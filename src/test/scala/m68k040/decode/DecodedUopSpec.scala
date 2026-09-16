package m68k040.decode

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

class DecodedUopSpec extends AnyFunSuite {
  test("DecodedUop has the spec fields") {
    SpinalConfig().generateVerilog(new Component {
      val u = DecodedUop()
      assert(u.pc.getWidth == 32 && u.imm.getWidth == 32)
      assert(u.srcAReg.getWidth == 5 && u.dstReg.getWidth == 5)   // 5-bit int reg ids (incl. T0/T1 temps)
      assert(u.cond.getWidth == 4 && u.imm.getWidth == 32)
      val s = master(Stream(Vec(DecodedUop(), 2)))
      s.valid := False; s.payload.foreach(_.assignDontCare())
      val probe = out(Bool()); probe := s.payload(0).isBranch
    })
  }
}
