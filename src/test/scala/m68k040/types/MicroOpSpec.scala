package m68k040.types

import m68k040.M68kParams
import spinal.core._
import org.scalatest.funsuite.AnyFunSuite

class MicroOpSpec extends AnyFunSuite {
  test("MicroOp has the spec 4.4 fields with param-derived widths") {
    SpinalConfig().generateVerilog(new Component {
      val p   = M68kParams()
      val uop = in(MicroOp(p))
      // field presence + widths (compile-time assertions inside elaboration)
      assert(uop.robId.getWidth == p.robIdWidth)
      assert(uop.psrc0.getWidth == p.physIntIdWidth)
      assert(uop.sqPtr0.getWidth == p.sqPtrWidth)
      assert(uop.twoAccess.isInstanceOf[Bool])
      out(uop.lastUop)        // keep something driven so elaboration is valid
    })
  }
}
