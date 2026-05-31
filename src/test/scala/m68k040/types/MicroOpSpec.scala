package m68k040.types

import m68k040.M68kParams
import spinal.core._
import org.scalatest.funsuite.AnyFunSuite

class MicroOpSpec extends AnyFunSuite {
  test("MicroOp has the spec 4.4 fields with param-derived widths") {
    SpinalConfig().generateVerilog(new Component {
      val p   = M68kParams()
      // local signal (not a port): drive with don't-care so nothing is undriven
      val uop = MicroOp(p)
      uop.assignDontCare()
      // route a field to a real output port so the component isn't empty
      val probe = out(Bool())
      probe := uop.lastUop

      // field presence + param-derived widths (elaboration-time assertions)
      assert(uop.robId.getWidth == p.robIdWidth)
      assert(uop.psrc0.getWidth == p.physIntIdWidth)
      assert(uop.sqPtr0.getWidth == p.sqPtrWidth)
      assert(uop.twoAccess.isInstanceOf[Bool])
    })
  }
}
